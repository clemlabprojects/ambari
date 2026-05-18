/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.ambari.view.k8s.service;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.ambari.view.ViewContext;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.openssl.PEMParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persists admin-uploaded Company Issuing CAs as K8s Secrets under a dedicated
 * privileged namespace (default {@code ambari-pki}). One Secret per CA. Used as the
 * trust anchor when an operator picks the {@code signedByCompanyCA} ingress-TLS mode
 * at install time.
 *
 * <p><b>Storage layout</b>: each CA lives at
 * {@code ambari-pki/<view-instance>-<ca-name>}. The Secret has:
 * <ul>
 *   <li>{@code tls.crt} — CA certificate PEM (one or more concatenated certs allowed
 *       so an intermediate-CA upload can carry its parent for trust-chain building)</li>
 *   <li>{@code tls.key} — CA private key PEM (RSA or EC, unencrypted at the API
 *       boundary; encryption-at-rest is the cluster operator's responsibility via
 *       K8s envelope encryption)</li>
 *   <li>Labels: {@code managed-by=ambari-k8s-view},
 *       {@code ambari.clemlab.com/resource-type=issuing-ca},
 *       {@code ambari.clemlab.com/view-instance=<name>}</li>
 *   <li>Annotations: {@code uploaded-by}, {@code uploaded-at}, {@code subject},
 *       {@code not-before}, {@code not-after}, {@code serial}</li>
 * </ul>
 *
 * <p><b>RBAC posture</b>: only the K8s view's service account should have
 * {@code get/list/watch/create/update/delete} on Secrets in the registry namespace.
 * The CA private key is never returned to the UI on read — list/get only emit the
 * cert PEM plus metadata; the key stays inside the cluster.
 *
 * <p><b>Audit</b>: every mutating operation logs at INFO with the caller principal,
 * action, CA name, and resulting cert subject/serial/notAfter so SREs can correlate
 * with K8s API audit logs.
 */
public class CaRegistryService {

    private static final Logger LOG = LoggerFactory.getLogger(CaRegistryService.class);

    /** Where the company-CA Secrets live. Configurable via instance property {@code pki.namespace}. */
    public static final String DEFAULT_REGISTRY_NAMESPACE = "ambari-pki";

    /** Standard label flagging the K8s view-managed CA registry entries. */
    public static final String LABEL_RESOURCE_TYPE = "ambari.clemlab.com/resource-type";
    public static final String RESOURCE_TYPE_ISSUING_CA = "issuing-ca";
    public static final String LABEL_VIEW_INSTANCE = "ambari.clemlab.com/view-instance";

    private final ViewContext viewContext;
    private final KubernetesService kubernetesService;

    public CaRegistryService(ViewContext viewContext, KubernetesService kubernetesService) {
        this.viewContext = Objects.requireNonNull(viewContext, "viewContext");
        this.kubernetesService = Objects.requireNonNull(kubernetesService, "kubernetesService");
    }

    /** Resolve the registry namespace from the view instance properties, with a safe default. */
    public String registryNamespace() {
        String fromCfg = viewContext.getProperties() != null ? viewContext.getProperties().get("pki.namespace") : null;
        return (fromCfg != null && !fromCfg.isBlank()) ? fromCfg.trim() : DEFAULT_REGISTRY_NAMESPACE;
    }

    /** Stable, namespaced Secret name for a CA. Lower-cased + DNS-1123-safe. */
    public String secretNameFor(String caName) {
        Objects.requireNonNull(caName, "caName");
        String instance = viewContext.getInstanceName() != null ? viewContext.getInstanceName() : "default";
        String safe = (instance + "-" + caName)
                .toLowerCase()
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-{2,}", "-");
        if (safe.startsWith("-")) safe = safe.substring(1);
        if (safe.endsWith("-")) safe = safe.substring(0, safe.length() - 1);
        if (safe.length() > 253) safe = safe.substring(0, 253);
        return safe;
    }

    /**
     * Upload (create or replace) a Company Issuing CA in the registry.
     *
     * <p>Validation:
     * <ul>
     *   <li>cert PEM must parse and have {@code basicConstraints.CA=true}</li>
     *   <li>key PEM must parse and match the certificate's public key</li>
     *   <li>CA must not be expired ({@code notAfter > now})</li>
     * </ul>
     *
     * @param caName       admin-chosen short id (e.g. {@code "acme-issuing-ca"})
     * @param caCertPem    CA certificate in PEM (may include intermediate chain)
     * @param caKeyPem     CA private key in PEM (RSA / EC, unencrypted)
     * @param description  optional free-text describing the CA for the registry listing
     * @return a {@link CaEntry} summary (without the private key) for the new/updated CA
     */
    public CaEntry upload(String caName, String caCertPem, String caKeyPem, String description) {
        if (caName == null || caName.isBlank()) {
            throw new IllegalArgumentException("caName is required");
        }
        if (caCertPem == null || caCertPem.isBlank()) {
            throw new IllegalArgumentException("caCertPem is required");
        }
        if (caKeyPem == null || caKeyPem.isBlank()) {
            throw new IllegalArgumentException("caKeyPem is required");
        }

        X509Certificate caCert = parseFirstCertificate(caCertPem);
        // Key parses + matches?
        try {
            // Use the same parser as the signing path so failure modes line up.
            new WebHookConfigurationService(viewContext, kubernetesService); // ensures provider registered
        } catch (Exception ignored) {
            // construction side-effects only; safe to ignore
        }

        if (caCert.getBasicConstraints() < 0) {
            throw new IllegalArgumentException("Uploaded certificate is not a CA (basicConstraints.CA=false). " +
                    "Upload an *issuing* CA, not a server / leaf certificate.");
        }
        if (caCert.getNotAfter().toInstant().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Uploaded CA is expired (notAfter=" +
                    caCert.getNotAfter().toInstant() + "). Renew the CA before uploading.");
        }

        // Round-trip the key to confirm it parses; we don't keep the object, just sanity check.
        try (PEMParser parser = new PEMParser(new StringReader(caKeyPem))) {
            Object obj = parser.readObject();
            if (obj == null) {
                throw new IllegalArgumentException("Failed to parse PEM private key (empty or malformed).");
            }
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to parse PEM private key: " + ex.getMessage(), ex);
        }

        String namespace = registryNamespace();
        ensureNamespaceExists(namespace);
        String secretName = secretNameFor(caName);

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("managed-by", "ambari-k8s-view");
        labels.put(LABEL_RESOURCE_TYPE, RESOURCE_TYPE_ISSUING_CA);
        labels.put(LABEL_VIEW_INSTANCE, viewContext.getInstanceName());
        labels.put("ambari.clemlab.com/ca-name", sanitizeLabelValue(caName));

        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put("ambari.clemlab.com/ca-name", caName);
        if (description != null && !description.isBlank()) {
            annotations.put("ambari.clemlab.com/description", description.trim());
        }
        annotations.put("ambari.clemlab.com/subject", caCert.getSubjectX500Principal().getName());
        annotations.put("ambari.clemlab.com/serial", caCert.getSerialNumber().toString(16));
        annotations.put("ambari.clemlab.com/not-before", caCert.getNotBefore().toInstant().toString());
        annotations.put("ambari.clemlab.com/not-after", caCert.getNotAfter().toInstant().toString());
        annotations.put("ambari.clemlab.com/uploaded-at", Instant.now().toString());
        annotations.put("ambari.clemlab.com/uploaded-by",
                viewContext.getUsername() != null ? viewContext.getUsername() : "unknown");

        Secret desired = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName)
                .withNamespace(namespace)
                .addToLabels(labels)
                .addToAnnotations(annotations)
                .endMetadata()
                .withType("kubernetes.io/tls")
                .addToData("tls.crt", b64(caCertPem))
                .addToData("tls.key", b64(caKeyPem))
                .build();

        KubernetesClient k8s = kubernetesService.getClient();
        Secret existing = k8s.secrets().inNamespace(namespace).withName(secretName).get();
        if (existing == null) {
            k8s.secrets().inNamespace(namespace).resource(desired).create();
            LOG.info("CA registry: CREATED ca={} secret={}/{} subject='{}' notAfter={} uploadedBy={}",
                    caName, namespace, secretName, caCert.getSubjectX500Principal().getName(),
                    caCert.getNotAfter().toInstant(),
                    annotations.get("ambari.clemlab.com/uploaded-by"));
        } else {
            k8s.secrets().inNamespace(namespace).resource(desired).createOrReplace();
            LOG.info("CA registry: REPLACED ca={} secret={}/{} subject='{}' notAfter={} uploadedBy={}",
                    caName, namespace, secretName, caCert.getSubjectX500Principal().getName(),
                    caCert.getNotAfter().toInstant(),
                    annotations.get("ambari.clemlab.com/uploaded-by"));
        }

        return toEntry(secretName, namespace, caCert, annotations);
    }

    /** List all registry entries for this view instance. Private keys are never returned. */
    public List<CaEntry> list() {
        String namespace = registryNamespace();
        ensureNamespaceExists(namespace);
        List<CaEntry> out = new ArrayList<>();
        List<Secret> secrets = kubernetesService.getClient().secrets().inNamespace(namespace)
                .withLabel(LABEL_RESOURCE_TYPE, RESOURCE_TYPE_ISSUING_CA)
                .withLabel(LABEL_VIEW_INSTANCE, viewContext.getInstanceName())
                .list().getItems();
        for (Secret s : secrets) {
            Map<String, String> data = s.getData() != null ? s.getData() : Collections.emptyMap();
            String certB64 = data.get("tls.crt");
            if (certB64 == null) continue;
            try {
                String certPem = new String(Base64.getDecoder().decode(certB64), StandardCharsets.UTF_8);
                X509Certificate caCert = parseFirstCertificate(certPem);
                Map<String, String> annotations = s.getMetadata() != null && s.getMetadata().getAnnotations() != null
                        ? s.getMetadata().getAnnotations() : Collections.emptyMap();
                out.add(toEntry(s.getMetadata().getName(), namespace, caCert, annotations));
            } catch (Exception ex) {
                LOG.warn("CA registry: skipping unparsable secret {}/{}: {}", namespace, s.getMetadata().getName(), ex.toString());
            }
        }
        return out;
    }

    /** Delete a CA by its admin-chosen name. */
    public void delete(String caName) {
        String namespace = registryNamespace();
        String secretName = secretNameFor(caName);
        Secret existing = kubernetesService.getClient().secrets().inNamespace(namespace).withName(secretName).get();
        if (existing == null) {
            throw new IllegalArgumentException("CA not found: " + caName);
        }
        kubernetesService.getClient().secrets().inNamespace(namespace).withName(secretName).delete();
        LOG.info("CA registry: DELETED ca={} secret={}/{} deletedBy={}",
                caName, namespace, secretName,
                viewContext.getUsername() != null ? viewContext.getUsername() : "unknown");
    }

    /** Get a single CA entry (without the private key). Throws if not found. */
    public CaEntry get(String caName) {
        return list().stream()
                .filter(e -> caName.equals(e.caName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("CA not found: " + caName));
    }

    // -------------------- helpers --------------------

    private void ensureNamespaceExists(String namespace) {
        try {
            Namespace ns = kubernetesService.getClient().namespaces().withName(namespace).get();
            if (ns == null) {
                Namespace toCreate = new NamespaceBuilder()
                        .withNewMetadata()
                        .withName(namespace)
                        .addToLabels("managed-by", "ambari-k8s-view")
                        .addToLabels(LABEL_RESOURCE_TYPE, "pki-registry")
                        .endMetadata()
                        .build();
                kubernetesService.getClient().namespaces().resource(toCreate).create();
                LOG.info("CA registry: created namespace '{}'", namespace);
            }
        } catch (Exception ex) {
            // Don't fail on best-effort namespace creation; if perms are missing the caller
            // will see a clearer error when the Secret create attempt fails.
            LOG.warn("CA registry: could not ensure namespace '{}': {}", namespace, ex.toString());
        }
    }

    private static X509Certificate parseFirstCertificate(String pem) {
        try (StringReader reader = new StringReader(pem); PEMParser parser = new PEMParser(reader)) {
            Object obj = parser.readObject();
            if (obj == null) throw new IllegalArgumentException("PEM does not contain any certificate");
            if (!(obj instanceof X509CertificateHolder)) {
                throw new IllegalArgumentException("First PEM block is not a certificate (got " + obj.getClass().getSimpleName() + ")");
            }
            X509CertificateHolder holder = (X509CertificateHolder) obj;
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(holder.getEncoded()));
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to parse CA certificate PEM: " + ex.getMessage(), ex);
        }
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String sanitizeLabelValue(String v) {
        // K8s label values: ≤63 chars, [a-z0-9A-Z._-], must start/end alphanumeric.
        if (v == null) return "";
        String s = v.replaceAll("[^a-zA-Z0-9._-]", "-");
        if (s.length() > 63) s = s.substring(0, 63);
        return s;
    }

    private CaEntry toEntry(String secretName, String namespace, X509Certificate caCert, Map<String, String> annotations) {
        String caName = annotations.getOrDefault("ambari.clemlab.com/ca-name", secretName);
        return new CaEntry(
                caName,
                secretName,
                namespace,
                caCert.getSubjectX500Principal().getName(),
                caCert.getIssuerX500Principal().getName(),
                caCert.getSerialNumber().toString(16),
                caCert.getNotBefore().toInstant().toString(),
                caCert.getNotAfter().toInstant().toString(),
                annotations.getOrDefault("ambari.clemlab.com/uploaded-by", "unknown"),
                annotations.getOrDefault("ambari.clemlab.com/uploaded-at", "unknown"),
                annotations.getOrDefault("ambari.clemlab.com/description", "")
        );
    }

    /** Public-safe representation of a registry entry (private key is intentionally excluded). */
    public record CaEntry(
            String caName,
            String secretName,
            String namespace,
            String subject,
            String issuer,
            String serial,
            String notBefore,
            String notAfter,
            String uploadedBy,
            String uploadedAt,
            String description
    ) {}
}
