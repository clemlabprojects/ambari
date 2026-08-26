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
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.view.k8s.service;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.apache.ambari.view.ViewContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Operator-managed truststores created from a public certificate PEM (a trust anchor, no private
 * key — distinct from {@link CaRegistryService}, which stores issuing CAs *with* keys).
 *
 * <p>Each truststore is a Secret named {@code <sanitized-name>-truststore} in a single management
 * namespace (instance property {@code truststore.namespace}, falling back to {@code pki.namespace},
 * default {@code ambari-pki}) carrying {@code ca.crt} (PEM) plus a JVM-consumable
 * {@code truststore.jks}/{@code truststore.password}. Truststores flagged default
 * ({@link #ANNOTATION_TRUST_DEFAULT}) are trusted by every release; others can be selected per
 * release in the install wizard. {@link TrustBundleService} merges both into the release truststore.
 */
public class TruststoreRegistryService {

    private static final Logger LOG = LoggerFactory.getLogger(TruststoreRegistryService.class);

    public static final String DEFAULT_MGMT_NAMESPACE = "ambari-pki";
    public static final String SUFFIX = "-truststore";

    public static final String LABEL_MANAGED_BY = "managed-by";
    public static final String MANAGED_BY_VALUE = "ambari-k8s-view";
    public static final String LABEL_RESOURCE_TYPE = "ambari.clemlab.com/resource-type";
    public static final String RESOURCE_TYPE_TRUSTSTORE = "truststore";
    public static final String LABEL_VIEW_INSTANCE = "ambari.clemlab.com/view-instance";
    public static final String ANNOTATION_TRUST_DEFAULT = "ambari.clemlab.com/trust-default";
    public static final String ANNOTATION_DESCRIPTION = "ambari.clemlab.com/description";
    public static final String ANNOTATION_UPLOADED_BY = "ambari.clemlab.com/uploaded-by";
    public static final String ANNOTATION_UPLOADED_AT = "ambari.clemlab.com/uploaded-at";

    private final ViewContext viewContext;
    private final KubernetesService kubernetesService;

    public TruststoreRegistryService(ViewContext viewContext, KubernetesService kubernetesService) {
        this.viewContext = Objects.requireNonNull(viewContext, "viewContext");
        this.kubernetesService = Objects.requireNonNull(kubernetesService, "kubernetesService");
    }

    /** Management namespace where operator-created truststores live. */
    public String managementNamespace() {
        Map<String, String> props = viewContext.getProperties();
        if (props != null) {
            String v = props.get("truststore.namespace");
            if (v == null || v.isBlank()) {
                v = props.get("pki.namespace");
            }
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return DEFAULT_MGMT_NAMESPACE;
    }

    /** Stable, DNS-1123-safe Secret name ({@code <name>-truststore}) for a truststore. */
    public String secretNameFor(String name) {
        Objects.requireNonNull(name, "name");
        String base = name.toLowerCase();
        if (base.endsWith(SUFFIX)) {
            base = base.substring(0, base.length() - SUFFIX.length());
        }
        String safe = base.replaceAll("[^a-z0-9-]", "-").replaceAll("-{2,}", "-");
        if (safe.startsWith("-")) safe = safe.substring(1);
        if (safe.endsWith("-")) safe = safe.substring(0, safe.length() - 1);
        if (safe.isBlank()) {
            throw new IllegalArgumentException("name must contain at least one alphanumeric character");
        }
        if (safe.length() > 240) safe = safe.substring(0, 240);
        return safe + SUFFIX;
    }

    /**
     * Create (or replace) a truststore from a public certificate PEM.
     *
     * @param name        operator-chosen short id
     * @param caCertPem   one or more PEM certificates (trust anchors, no private key)
     * @param makeDefault whether the truststore should be trusted by every release
     * @param description optional free text
     * @return summary of the created truststore
     */
    public Map<String, Object> create(String name, String caCertPem, boolean makeDefault, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (caCertPem == null || caCertPem.isBlank()) {
            throw new IllegalArgumentException("caCertPem is required");
        }
        List<X509Certificate> certs = WebHookConfigurationService.parsePemCertificates(caCertPem);
        if (certs.isEmpty()) {
            throw new IllegalArgumentException("No X.509 certificate found in the provided PEM");
        }

        String namespace = managementNamespace();
        String secretName = secretNameFor(name);
        String canonicalPem = WebHookConfigurationService.toPemBundle(certs);
        char[] password = UUID.randomUUID().toString().replace("-", "").toCharArray();
        byte[] jks = WebHookConfigurationService.buildJksFromCerts(certs, password);

        Map<String, String> data = new LinkedHashMap<>();
        data.put("ca.crt", b64(canonicalPem.getBytes(StandardCharsets.UTF_8)));
        data.put("truststore.jks", b64(jks));
        data.put("truststore.password", b64(new String(password).getBytes(StandardCharsets.UTF_8)));

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(LABEL_MANAGED_BY, MANAGED_BY_VALUE);
        labels.put(LABEL_RESOURCE_TYPE, RESOURCE_TYPE_TRUSTSTORE);
        labels.put(LABEL_VIEW_INSTANCE, sanitizeLabel(viewContext.getInstanceName()));

        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put(ANNOTATION_TRUST_DEFAULT, Boolean.toString(makeDefault));
        if (description != null && !description.isBlank()) {
            annotations.put(ANNOTATION_DESCRIPTION, description.trim());
        }
        annotations.put(ANNOTATION_UPLOADED_BY, currentUser());
        annotations.put(ANNOTATION_UPLOADED_AT, Instant.now().toString());
        annotations.put("ambari.clemlab.com/subject", certs.get(0).getSubjectX500Principal().getName());

        ensureNamespace(namespace);
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName)
                .withNamespace(namespace)
                .withLabels(labels)
                .withAnnotations(annotations)
                .endMetadata()
                .withType("Opaque")
                .withData(data)
                .build();

        Secret existing = kubernetesService.getClient().secrets().inNamespace(namespace).withName(secretName).get();
        if (existing == null) {
            kubernetesService.getClient().secrets().inNamespace(namespace).resource(secret).create();
            LOG.info("truststore-registry: CREATED {}/{} certs={} default={} by={}",
                    namespace, secretName, certs.size(), makeDefault, annotations.get(ANNOTATION_UPLOADED_BY));
        } else {
            kubernetesService.getClient().secrets().inNamespace(namespace).resource(secret).update();
            LOG.info("truststore-registry: REPLACED {}/{} certs={} default={} by={}",
                    namespace, secretName, certs.size(), makeDefault, annotations.get(ANNOTATION_UPLOADED_BY));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("namespace", namespace);
        summary.put("name", secretName);
        summary.put("caCount", certs.size());
        summary.put("isDefault", makeDefault);
        summary.put("managed", true);
        return summary;
    }

    /** Flip the default flag on a managed truststore. Multiple truststores may be default. */
    public Map<String, Object> setDefault(String namespace, String name, boolean value) {
        String ns = (namespace == null || namespace.isBlank()) ? managementNamespace() : namespace;
        Secret secret = kubernetesService.getClient().secrets().inNamespace(ns).withName(name).get();
        if (secret == null) {
            throw new IllegalArgumentException("Truststore not found: " + ns + "/" + name);
        }
        requireManaged(secret);
        if (secret.getMetadata().getAnnotations() == null) {
            secret.getMetadata().setAnnotations(new LinkedHashMap<>());
        }
        secret.getMetadata().getAnnotations().put(ANNOTATION_TRUST_DEFAULT, Boolean.toString(value));
        kubernetesService.getClient().secrets().inNamespace(ns).resource(secret).update();
        LOG.info("truststore-registry: set default={} on {}/{}", value, ns, name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("namespace", ns);
        out.put("name", name);
        out.put("isDefault", value);
        return out;
    }

    /** Delete a managed truststore. Refuses to delete non-managed (e.g. per-release) truststores. */
    public void delete(String namespace, String name) {
        String ns = (namespace == null || namespace.isBlank()) ? managementNamespace() : namespace;
        Secret secret = kubernetesService.getClient().secrets().inNamespace(ns).withName(name).get();
        if (secret == null) {
            throw new IllegalArgumentException("Truststore not found: " + ns + "/" + name);
        }
        requireManaged(secret);
        kubernetesService.getClient().secrets().inNamespace(ns).withName(name).delete();
        LOG.info("truststore-registry: DELETED {}/{}", ns, name);
    }

    /** Return the {@code ca.crt} PEM of a truststore referenced by its short name (management ns). */
    public String caPemByName(String name) {
        String secretName = secretNameFor(name);
        return kubernetesService.readOpaqueSecretKeyAsBytes(managementNamespace(), secretName, "ca.crt")
                .map(b -> new String(b, StandardCharsets.UTF_8))
                .orElseThrow(() -> new IllegalArgumentException("Truststore not found or missing ca.crt: " + name));
    }

    /** Return the {@code ca.crt} PEM bundles of every truststore flagged default (management ns). */
    public List<String> defaultTruststorePems() {
        List<String> out = new ArrayList<>();
        String ns = managementNamespace();
        List<Secret> secrets;
        try {
            secrets = kubernetesService.getClient().secrets().inNamespace(ns)
                    .withLabel(LABEL_RESOURCE_TYPE, RESOURCE_TYPE_TRUSTSTORE).list().getItems();
        } catch (Exception e) {
            LOG.warn("truststore-registry: could not list default truststores in {}: {}", ns, e.toString());
            return out;
        }
        for (Secret s : secrets) {
            Map<String, String> ann = s.getMetadata() != null ? s.getMetadata().getAnnotations() : null;
            boolean isDefault = ann != null && Boolean.parseBoolean(ann.getOrDefault(ANNOTATION_TRUST_DEFAULT, "false"));
            if (!isDefault) continue;
            Map<String, String> data = s.getData();
            if (data != null && data.containsKey("ca.crt")) {
                out.add(new String(Base64.getDecoder().decode(data.get("ca.crt")), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private void requireManaged(Secret secret) {
        Map<String, String> labels = secret.getMetadata() != null ? secret.getMetadata().getLabels() : null;
        boolean managed = labels != null && RESOURCE_TYPE_TRUSTSTORE.equals(labels.get(LABEL_RESOURCE_TYPE));
        if (!managed) {
            throw new IllegalArgumentException("Refusing to modify a non view-managed truststore "
                    + "(only truststores created in this tab can be changed)");
        }
    }

    private void ensureNamespace(String namespace) {
        try {
            Namespace ns = kubernetesService.getClient().namespaces().withName(namespace).get();
            if (ns == null) {
                Namespace toCreate = new NamespaceBuilder()
                        .withNewMetadata().withName(namespace)
                        .addToLabels(LABEL_MANAGED_BY, MANAGED_BY_VALUE)
                        .endMetadata().build();
                kubernetesService.getClient().namespaces().resource(toCreate).create();
                LOG.info("truststore-registry: created management namespace '{}'", namespace);
            }
        } catch (Exception e) {
            LOG.warn("truststore-registry: could not ensure namespace '{}': {}", namespace, e.toString());
        }
    }

    private String currentUser() {
        try {
            String u = viewContext.getUsername();
            return (u == null || u.isBlank()) ? "unknown" : u;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String b64(byte[] raw) {
        return Base64.getEncoder().encodeToString(raw);
    }

    private static String sanitizeLabel(String v) {
        if (v == null) return "default";
        String s = v.replaceAll("[^a-zA-Z0-9._-]", "-");
        if (s.length() > 63) s = s.substring(0, 63);
        return s.isBlank() ? "default" : s;
    }
}
