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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Secret;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.store.InstanceSettings;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Company Issuing CAs administrators upload, kept by the view for every Kubernetes cluster it deploys to: the
 * certificate in the view's settings, the private key encrypted there with the Ambari master key
 * ({@link InstanceSettings#putSecret}). Used when an operator picks the {@code signedByCompanyCA} ingress TLS mode:
 * KDPS signs the release's leaf certificate itself, so the CA key never goes to the cluster the release is deployed to.
 * Only promoting a CA to a cert-manager ClusterIssuer writes it to a cluster, the one the administrator names.
 *
 * <p>Earlier versions kept each CA as a {@code kubernetes.io/tls} Secret in the Company CA project
 * ({@link PkiNamespaces#caNamespace()}, {@code ambari-pki} by default) of the one cluster KDPS knew. Those are imported
 * once from the default cluster, and left in place; deleting a CA also deletes its old Secret.</p>
 *
 * <p>A CA is named by the administrator ({@code caName}) and identified by {@link #secretNameFor} of that name, which
 * is what the deploy wizard's picker sends and what releases deployed earlier recorded: both are accepted.</p>
 *
 * <p><b>Audit</b>: every change logs at INFO with the caller, the action, the CA and its certificate's subject, serial
 * and expiry.</p>
 */
public class CaRegistryService {

    private static final Logger LOG = LoggerFactory.getLogger(CaRegistryService.class);

    /** Where earlier versions kept the Company CA Secrets unless the view settings named another project. */
    public static final String DEFAULT_REGISTRY_NAMESPACE = PkiNamespaces.DEFAULT_NAMESPACE;

    /** Label of the CA Secrets earlier versions wrote, and of the deploy wizard's Company CA picker. */
    public static final String LABEL_RESOURCE_TYPE = "ambari.clemlab.com/resource-type";
    public static final String RESOURCE_TYPE_ISSUING_CA = "issuing-ca";
    public static final String LABEL_VIEW_INSTANCE = "ambari.clemlab.com/view-instance";
    /** The selector of the deploy wizard's Company CA picker ({@code lookupLabel} of the service definitions). */
    public static final String PICKER_SELECTOR = LABEL_RESOURCE_TYPE + "=" + RESOURCE_TYPE_ISSUING_CA;

    static final String INDEX_KEY = "pki.cas";
    static final String IMPORTED_KEY = "pki.cas.imported";
    private static final String ENTRY_PREFIX = "pki.ca.";
    private static final String KEY_SUFFIX = ".key";

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Serializes the changes of the registry: a new service is made for each request. */
    private static final Object LOCK = new Object();

    private final ViewContext viewContext;
    /** The default cluster, where earlier versions kept the CAs: read once to import them. */
    private final KubernetesService defaultCluster;
    private final PkiNamespaces namespaces;
    private final InstanceSettings settings;

    /**
     * @param viewContext    the view instance
     * @param defaultCluster the default cluster's service, from which the CAs of earlier versions are imported once
     */
    public CaRegistryService(ViewContext viewContext, KubernetesService defaultCluster) {
        this(viewContext, defaultCluster, new PkiNamespaces(viewContext), new InstanceSettings(viewContext));
    }

    CaRegistryService(ViewContext viewContext, KubernetesService defaultCluster, PkiNamespaces namespaces,
                      InstanceSettings settings) {
        this.viewContext = Objects.requireNonNull(viewContext, "viewContext");
        this.defaultCluster = defaultCluster;
        this.namespaces = Objects.requireNonNull(namespaces, "namespaces");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** The project where earlier versions kept the CA Secrets on the default cluster. */
    public String registryNamespace() {
        return namespaces.caNamespace();
    }

    /** Stable, DNS-1123-safe identifier of a CA, which earlier versions also used as its Secret's name. */
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
     * Uploads a CA, or replaces the one of the same name.
     *
     * @param caName      admin-chosen name (e.g. {@code "acme-issuing-ca"})
     * @param caCertPem   CA certificate in PEM (may include intermediate chain)
     * @param caKeyPem    CA private key in PEM (RSA / EC, unencrypted)
     * @param description optional free text for the registry listing
     * @return the CA as listed, without its private key
     * @throws IllegalArgumentException when the certificate is not a valid, current CA or the key cannot be read
     * @throws org.apache.ambari.view.k8s.security.MasterKeyUnavailableException when the key cannot be encrypted
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
        if (caCert.getBasicConstraints() < 0) {
            throw new IllegalArgumentException("Uploaded certificate is not a CA (basicConstraints.CA=false). " +
                    "Upload an *issuing* CA, not a server / leaf certificate.");
        }
        if (caCert.getNotAfter().toInstant().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Uploaded CA is expired (notAfter=" +
                    caCert.getNotAfter().toInstant() + "). Renew the CA before uploading.");
        }
        // The key must be readable and be the certificate's: a leaf signed with another key would never verify.
        WebHookConfigurationService.checkCaKeyMatches(caCertPem, caKeyPem);
        importOnce();
        String id = secretNameFor(caName);
        Stored stored = new Stored(caName.trim(), caCertPem, description == null ? "" : description.trim(),
                viewContext.getUsername() != null ? viewContext.getUsername() : "unknown", Instant.now().toString());
        boolean replaced;
        synchronized (LOCK) {
            List<String> ids = ids();
            replaced = ids.contains(id);
            store(id, stored, caKeyPem);
            if (!replaced) {
                ids.add(id);
                writeIds(ids);
            }
        }
        LOG.info("CA registry: {} ca={} id={} subject='{}' notAfter={} uploadedBy={}", replaced ? "REPLACED" : "CREATED",
                caName, id, caCert.getSubjectX500Principal().getName(), caCert.getNotAfter().toInstant(),
                stored.uploadedBy());
        return toEntry(id, stored);
    }

    /** Every CA, by name. Private keys are never returned. */
    public List<CaEntry> list() {
        importOnce();
        List<CaEntry> out = new ArrayList<>();
        for (String id : ids()) {
            Stored stored = read(id);
            if (stored == null) {
                continue;
            }
            try {
                out.add(toEntry(id, stored));
            } catch (RuntimeException ex) {
                LOG.warn("CA registry: skipping the CA {}, whose certificate cannot be read: {}", id, ex.toString());
            }
        }
        out.sort(java.util.Comparator.comparing(CaEntry::caName));
        return out;
    }

    /**
     * A CA by its name or its identifier, without its private key.
     *
     * @throws IllegalArgumentException when there is no such CA
     */
    public CaEntry get(String caNameOrId) {
        String wanted = caNameOrId == null ? "" : caNameOrId.trim();
        return list().stream()
                .filter(e -> wanted.equals(e.caName()) || wanted.equals(e.secretName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("CA not found: " + caNameOrId));
    }

    /**
     * What a CA signs with: its certificate and private key, for KDPS to sign a release's leaf certificate.
     *
     * @throws IllegalArgumentException when there is no such CA
     * @throws IllegalStateException    when its key cannot be read, or it is not a CA
     */
    public WebHookConfigurationService.CertificateAuthorityMaterial material(String caNameOrId) {
        CaEntry entry = get(caNameOrId);
        Stored stored = read(entry.secretName());
        String keyPem = settings.getSecret(keyKey(entry.secretName()));
        if (stored == null || keyPem == null || keyPem.isBlank()) {
            throw new IllegalStateException("The private key of the Company CA '" + entry.caName()
                    + "' cannot be read; upload the CA again.");
        }
        if (parseFirstCertificate(stored.certPem()).getBasicConstraints() < 0) {
            throw new IllegalStateException("The Company CA '" + entry.caName() + "' holds a leaf certificate "
                    + "(basicConstraints.CA=false). Upload an issuing CA.");
        }
        return new WebHookConfigurationService.CertificateAuthorityMaterial(stored.certPem(), keyPem,
                "kdps-settings://" + entry.secretName());
    }

    /**
     * Deletes a CA, and the Secret an earlier version kept it in, when it is still there.
     *
     * @throws IllegalArgumentException when there is no such CA
     */
    public void delete(String caNameOrId) {
        CaEntry entry = get(caNameOrId);
        synchronized (LOCK) {
            List<String> ids = ids();
            ids.remove(entry.secretName());
            writeIds(ids);
            settings.putText(entryKey(entry.secretName()), null);
            settings.delete(keyKey(entry.secretName()));
        }
        deleteLegacySecret(entry.secretName());
        LOG.info("CA registry: DELETED ca={} id={} deletedBy={}", entry.caName(), entry.secretName(),
                viewContext.getUsername() != null ? viewContext.getUsername() : "unknown");
    }

    /**
     * Makes a CA a cert-manager {@code ClusterIssuer} of kind {@code ca} on a cluster: its certificate and key go to a
     * {@code kubernetes.io/tls} Secret in cert-manager's namespace there, which the issuer names.
     *
     * @param cluster the cluster the issuer is created on
     * @return the issuer's name ({@code clemlab-<ca name>})
     */
    public String promote(String caNameOrId, KubernetesService cluster) {
        CaEntry entry = get(caNameOrId);
        WebHookConfigurationService.CertificateAuthorityMaterial ca = material(caNameOrId);
        String issuerName = "clemlab-" + entry.caName().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        cluster.applyCertManagerClusterIssuerFromCa(issuerName, entry.secretName(), ca.caCertificatePem(),
                ca.caPrivateKeyPem());
        LOG.info("CA registry: PROMOTED ca={} to ClusterIssuer {} on cluster {} by {}", entry.caName(), issuerName,
                cluster.cluster(), viewContext.getUsername() != null ? viewContext.getUsername() : "unknown");
        return issuerName;
    }

    // -------------------- the CAs of earlier versions --------------------

    /**
     * Imports, once, the CAs earlier versions kept as Secrets on the default cluster. When the cluster cannot be read,
     * nothing is marked: the import is tried again next time.
     */
    private void importOnce() {
        if ("true".equals(settings.get(IMPORTED_KEY)) || defaultCluster == null) {
            return;
        }
        synchronized (LOCK) {
            if ("true".equals(settings.get(IMPORTED_KEY))) {
                return;
            }
            List<Secret> legacy;
            try {
                legacy = defaultCluster.getClient().secrets().inNamespace(registryNamespace())
                        .withLabel(LABEL_RESOURCE_TYPE, RESOURCE_TYPE_ISSUING_CA)
                        .withLabel(LABEL_VIEW_INSTANCE, viewContext.getInstanceName())
                        .list().getItems();
            } catch (RuntimeException e) {
                LOG.warn("CA registry: the CAs of earlier versions could not be read from {} on the default cluster; "
                        + "tried again later: {}", registryNamespace(), e.toString());
                return;
            }
            List<String> ids = ids();
            int imported = 0;
            for (Secret secret : legacy) {
                Map<String, String> data = secret.getData() != null ? secret.getData() : Collections.emptyMap();
                String id = secret.getMetadata().getName();
                if (ids.contains(id) || data.get("tls.crt") == null || data.get("tls.key") == null) {
                    continue;
                }
                Map<String, String> annotations = secret.getMetadata().getAnnotations() != null
                        ? secret.getMetadata().getAnnotations() : Collections.emptyMap();
                Stored stored = new Stored(annotations.getOrDefault("ambari.clemlab.com/ca-name", id),
                        decode(data.get("tls.crt")),
                        annotations.getOrDefault("ambari.clemlab.com/description", ""),
                        annotations.getOrDefault("ambari.clemlab.com/uploaded-by", "unknown"),
                        annotations.getOrDefault("ambari.clemlab.com/uploaded-at", "unknown"));
                store(id, stored, decode(data.get("tls.key")));
                ids.add(id);
                imported++;
            }
            writeIds(ids);
            settings.put(IMPORTED_KEY, "true");
            if (imported > 0) {
                LOG.info("CA registry: imported {} CA(s) of an earlier version from {} on the default cluster; their "
                        + "Secrets are left in place", imported, registryNamespace());
            }
        }
    }

    private void deleteLegacySecret(String id) {
        if (defaultCluster == null) {
            return;
        }
        try {
            Secret legacy = defaultCluster.getClient().secrets().inNamespace(registryNamespace()).withName(id).get();
            if (legacy != null && legacy.getMetadata().getLabels() != null
                    && RESOURCE_TYPE_ISSUING_CA.equals(legacy.getMetadata().getLabels().get(LABEL_RESOURCE_TYPE))) {
                defaultCluster.getClient().secrets().inNamespace(registryNamespace()).withName(id).delete();
                LOG.info("CA registry: deleted the Secret {}/{} an earlier version kept the CA in", registryNamespace(), id);
            }
        } catch (RuntimeException e) {
            LOG.warn("CA registry: the Secret {}/{} an earlier version kept the CA in could not be deleted: {}",
                    registryNamespace(), id, e.toString());
        }
    }

    // -------------------- storage --------------------

    /** A CA as the settings keep it; its private key is kept apart, encrypted. */
    record Stored(String caName, String certPem, String description, String uploadedBy, String uploadedAt) { }

    private static String entryKey(String id) {
        return ENTRY_PREFIX + id;
    }

    private static String keyKey(String id) {
        return ENTRY_PREFIX + id + KEY_SUFFIX;
    }

    private void store(String id, Stored stored, String keyPem) {
        try {
            settings.putSecret(keyKey(id), keyPem);
            settings.putText(entryKey(id), JSON.writeValueAsString(stored));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("The CA " + id + " cannot be written: " + e.getOriginalMessage(), e);
        }
    }

    private Stored read(String id) {
        String json = settings.getText(entryKey(id));
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, Stored.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            LOG.warn("CA registry: the CA {} cannot be read: {}", id, e.getOriginalMessage());
            return null;
        }
    }

    private List<String> ids() {
        String json = settings.get(INDEX_KEY);
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(JSON.readValue(json, new TypeReference<List<String>>() { }));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("The list of Company CAs cannot be read: " + e.getOriginalMessage(), e);
        }
    }

    private void writeIds(List<String> ids) {
        try {
            settings.put(INDEX_KEY, JSON.writeValueAsString(ids));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("The list of Company CAs cannot be written: " + e.getOriginalMessage(), e);
        }
    }

    // -------------------- helpers --------------------

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

    private static String decode(String base64) {
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    private CaEntry toEntry(String id, Stored stored) {
        X509Certificate caCert = parseFirstCertificate(stored.certPem());
        return new CaEntry(
                stored.caName(),
                id,
                "",
                caCert.getSubjectX500Principal().getName(),
                caCert.getIssuerX500Principal().getName(),
                caCert.getSerialNumber().toString(16),
                caCert.getNotBefore().toInstant().toString(),
                caCert.getNotAfter().toInstant().toString(),
                stored.uploadedBy(),
                stored.uploadedAt(),
                stored.description()
        );
    }

    /**
     * Public-safe representation of a registry entry (private key is intentionally excluded). {@code secretName} is
     * the CA's identifier ({@link #secretNameFor}); {@code namespace} is empty, the view keeping the CA itself.
     */
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
