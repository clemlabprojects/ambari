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
 * <p>Each truststore is a Secret named {@code <sanitized-name>-truststore} carrying {@code ca.crt} (PEM) plus a
 * JVM-consumable {@code truststore.jks}/{@code truststore.password}, labelled
 * {@link #LABEL_RESOURCE_TYPE}={@value #RESOURCE_TYPE_TRUSTSTORE}. {@link TrustBundleService} merges the ones a
 * release trusts into its release truststore. There are two scopes:</p>
 * <ul>
 *   <li><b>shared</b>: in the management namespace (see {@link PkiNamespaces#truststoreNamespace()}, set in Cluster
 *       Settings). Any release may select one, and one flagged default ({@link #ANNOTATION_TRUST_DEFAULT}) is trusted
 *       by every release;</li>
 *   <li><b>project</b>: in a project releases are deployed to. Only the releases of that project may select one, and
 *       one flagged default is trusted by every release of that project.</li>
 * </ul>
 *
 * <p>A project truststore never reaches a release of another project: whoever may write Secrets in one project could
 * otherwise add a trust anchor to the releases of every other. Within its own project it gives nothing its editors do
 * not already have, since they can change the releases themselves.</p>
 */
public class TruststoreRegistryService {

    private static final Logger LOG = LoggerFactory.getLogger(TruststoreRegistryService.class);

    public static final String DEFAULT_MGMT_NAMESPACE = PkiNamespaces.DEFAULT_NAMESPACE;
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

    /** {@link KubernetesService.TruststoreSummary#scope} of a truststore of the management namespace. */
    public static final String SCOPE_SHARED = "shared";
    /** {@link KubernetesService.TruststoreSummary#scope} of a truststore of a workload project. */
    public static final String SCOPE_PROJECT = "project";

    /** A Kubernetes namespace name: a DNS-1123 label. */
    private static final java.util.regex.Pattern NAMESPACE_NAME =
            java.util.regex.Pattern.compile("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?");

    private final ViewContext viewContext;
    private final KubernetesService kubernetesService;
    private final PkiNamespaces namespaces;

    public TruststoreRegistryService(ViewContext viewContext, KubernetesService kubernetesService) {
        this(viewContext, kubernetesService, new PkiNamespaces(viewContext));
    }

    TruststoreRegistryService(ViewContext viewContext, KubernetesService kubernetesService, PkiNamespaces namespaces) {
        this.viewContext = Objects.requireNonNull(viewContext, "viewContext");
        this.kubernetesService = Objects.requireNonNull(kubernetesService, "kubernetesService");
        this.namespaces = Objects.requireNonNull(namespaces, "namespaces");
    }

    /** Management namespace where truststores created in the Truststores tab are written. */
    public String managementNamespace() {
        return namespaces.truststoreNamespace();
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

    /** Whether a namespace holds the shared truststores: the management namespace. */
    public boolean isShared(String namespace) {
        return namespace == null || namespace.isBlank() || managementNamespace().equals(namespace.trim());
    }

    /**
     * Create (or replace) a shared truststore from a public certificate PEM.
     *
     * @see #create(String, String, String, boolean, String)
     */
    public Map<String, Object> create(String name, String caCertPem, boolean makeDefault, String description) {
        return create(null, name, caCertPem, makeDefault, description);
    }

    /**
     * Create (or replace) a truststore from a public certificate PEM.
     *
     * @param namespace   the project of a project truststore; {@code null} or blank, or the management namespace, for a
     *                    shared one
     * @param name        operator-chosen short id
     * @param caCertPem   one or more PEM certificates (trust anchors, no private key)
     * @param makeDefault whether the truststore should be trusted by every release (of its project, for a project one)
     * @param description optional free text
     * @return summary of the created truststore
     */
    public Map<String, Object> create(String namespace, String name, String caCertPem, boolean makeDefault,
                                      String description) {
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

        boolean shared = isShared(namespace);
        String targetNamespace = shared ? managementNamespace() : validNamespace(namespace);
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

        // Only the management namespace is KDPS's own to create; a project is the platform team's.
        if (shared) {
            ensureNamespace(targetNamespace);
        }
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName)
                .withNamespace(targetNamespace)
                .withLabels(labels)
                .withAnnotations(annotations)
                .endMetadata()
                .withType("Opaque")
                .withData(data)
                .build();

        Secret existing = kubernetesService.getClient().secrets().inNamespace(targetNamespace).withName(secretName).get();
        // A release keeps its own truststore under the same naming (<release>-truststore): never replace one.
        if (existing != null && !isManaged(existing)) {
            throw new IllegalArgumentException("Project " + targetNamespace + " already has a Secret " + secretName
                    + " that was not created in the Truststores tab (a release named " + secretName.substring(0,
                    secretName.length() - SUFFIX.length()) + " keeps its own truststore under that name). Choose "
                    + "another name.");
        }
        if (existing == null) {
            kubernetesService.getClient().secrets().inNamespace(targetNamespace).resource(secret).create();
            LOG.info("truststore-registry: CREATED {}/{} certs={} default={} by={}",
                    targetNamespace, secretName, certs.size(), makeDefault, annotations.get(ANNOTATION_UPLOADED_BY));
        } else {
            kubernetesService.getClient().secrets().inNamespace(targetNamespace).resource(secret).update();
            LOG.info("truststore-registry: REPLACED {}/{} certs={} default={} by={}",
                    targetNamespace, secretName, certs.size(), makeDefault, annotations.get(ANNOTATION_UPLOADED_BY));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("namespace", targetNamespace);
        summary.put("name", secretName);
        summary.put("caCount", certs.size());
        summary.put("isDefault", makeDefault);
        summary.put("managed", true);
        summary.put("scope", shared ? SCOPE_SHARED : SCOPE_PROJECT);
        return summary;
    }

    /**
     * Flip the default flag on a managed truststore. Multiple truststores may be default. A shared one flagged default
     * is trusted by every release, a project one by every release of its project.
     */
    public Map<String, Object> setDefault(String namespace, String name, boolean value) {
        String ns = namespaceOf(namespace);
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
        String ns = namespaceOf(namespace);
        Secret secret = kubernetesService.getClient().secrets().inNamespace(ns).withName(name).get();
        if (secret == null) {
            throw new IllegalArgumentException("Truststore not found: " + ns + "/" + name);
        }
        requireManaged(secret);
        kubernetesService.getClient().secrets().inNamespace(ns).withName(name).delete();
        LOG.info("truststore-registry: DELETED {}/{}", ns, name);
    }

    /**
     * The {@code ca.crt} PEM of a shared truststore, for what is not deployed to a project (a chart repository).
     *
     * @see #caPemByName(String, String)
     */
    public String caPemByName(String ref) {
        return caPemByName(ref, null);
    }

    /**
     * The {@code ca.crt} PEM of a truststore created in the Truststores tab.
     *
     * @param ref              {@code <namespace>/<name>}, as the deploy wizard sends it from the listing, or a bare
     *                         name (as deploys recorded by earlier versions and chart repositories name it), read from the
     *                         management namespace; the name with or without its {@value #SUFFIX} suffix
     * @param releaseNamespace the project of the release that trusts it, whose project truststores it may use; {@code null}
     *                         for shared truststores only
     * @return the PEM bundle
     * @throws IllegalArgumentException when the namespace is neither the management namespace nor the release's, or the
     *                                  Secret is missing, was not created in the Truststores tab, or holds no
     *                                  {@code ca.crt}
     */
    public String caPemByName(String ref, String releaseNamespace) {
        Objects.requireNonNull(ref, "ref");
        String namespace = managementNamespace();
        String name = ref.trim();
        int slash = name.indexOf('/');
        if (slash >= 0) {
            String given = name.substring(0, slash).trim();
            name = name.substring(slash + 1).trim();
            namespace = usableNamespace(given, name, releaseNamespace);
        }
        String secretName = secretNameFor(name);
        Secret secret = kubernetesService.getSecret(namespace, secretName);
        if (secret == null) {
            throw new IllegalArgumentException("Truststore not found: " + namespace + "/" + secretName);
        }
        // Only truststores created in the Truststores tab: any other Secret of a reachable project, a release's own
        // truststore for one, is not a trust anchor the operator chose.
        if (!isManaged(secret)) {
            throw new IllegalArgumentException("Secret " + namespace + "/" + secretName
                    + " is not a truststore created in the Truststores tab");
        }
        String encoded = secret.getData() == null ? null : secret.getData().get("ca.crt");
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("Truststore " + namespace + "/" + secretName + " has no ca.crt");
        }
        try {
            return new String(Base64.getDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException("Truststore " + namespace + "/" + secretName + " has an unreadable ca.crt");
        }
    }

    /**
     * The {@code ca.crt} PEM bundles of every shared truststore flagged default.
     *
     * @return the PEM bundles
     * @throws RuntimeException when they cannot be listed
     */
    public List<String> defaultTruststorePems() {
        return defaultPemsIn(managementNamespace());
    }

    /**
     * The {@code ca.crt} PEM bundles of the truststores flagged default that a release of a project trusts: the shared
     * ones, and those of its project.
     *
     * @param releaseNamespace the project of the release; {@code null} for the shared ones only
     * @return the PEM bundles
     * @throws RuntimeException when they cannot be listed
     */
    public List<String> defaultTruststorePems(String releaseNamespace) {
        List<String> out = defaultPemsIn(managementNamespace());
        if (!isShared(releaseNamespace)) {
            out.addAll(defaultPemsIn(releaseNamespace.trim()));
        }
        return out;
    }

    private List<String> defaultPemsIn(String ns) {
        List<String> out = new ArrayList<>();
        for (Secret s : kubernetesService.listViewSecretsWithLabel(ns, LABEL_RESOURCE_TYPE, RESOURCE_TYPE_TRUSTSTORE)) {
            Map<String, String> ann = s.getMetadata() != null ? s.getMetadata().getAnnotations() : null;
            boolean isDefault = ann != null && Boolean.parseBoolean(ann.getOrDefault(ANNOTATION_TRUST_DEFAULT, "false"));
            if (!isDefault) continue;
            Map<String, String> data = s.getData();
            if (data != null && data.get("ca.crt") != null) {
                try {
                    out.add(new String(Base64.getDecoder().decode(data.get("ca.crt").trim()), StandardCharsets.UTF_8));
                } catch (IllegalArgumentException notBase64) {
                    LOG.warn("truststore-registry: default truststore {}/{} has an unreadable ca.crt; skipped",
                            s.getMetadata().getNamespace(), s.getMetadata().getName());
                }
            }
        }
        return out;
    }

    /**
     * Marks the scope of the listed truststores created in the Truststores tab: shared in the management namespace,
     * project anywhere else. Release truststores and other Secrets have none.
     *
     * @param listed the Secrets named {@code *}{@value #SUFFIX} the account can read; changed in place
     * @return the same list
     */
    public List<KubernetesService.TruststoreSummary> markManaged(List<KubernetesService.TruststoreSummary> listed) {
        String ns = managementNamespace();
        for (KubernetesService.TruststoreSummary t : listed) {
            t.scope = !t.managed ? null : ns.equals(t.namespace) ? SCOPE_SHARED : SCOPE_PROJECT;
        }
        return listed;
    }

    /** The namespace of a truststore to change: the one given, or the management namespace when blank. */
    private String namespaceOf(String namespace) {
        return isShared(namespace) ? managementNamespace() : validNamespace(namespace);
    }

    /**
     * The namespace a release may read a selected truststore from.
     *
     * @param namespace        the namespace of the reference; {@code null} or blank for the management namespace
     * @param name             the truststore, for the message
     * @param releaseNamespace the project of the release; {@code null} when only shared truststores may be used
     * @return the namespace
     * @throws IllegalArgumentException when it is neither the management namespace nor the release's project
     */
    private String usableNamespace(String namespace, String name, String releaseNamespace) {
        if (isShared(namespace)) {
            return managementNamespace();
        }
        String ns = namespace.trim();
        if (releaseNamespace != null && ns.equals(releaseNamespace.trim())) {
            return ns;
        }
        String shared = "the shared truststores of project " + managementNamespace() + " (set in Cluster Settings)";
        if (releaseNamespace == null || releaseNamespace.isBlank()) {
            throw new IllegalArgumentException("Truststore " + ns + "/" + name + " belongs to project " + ns
                    + "; only " + shared + " can be used here.");
        }
        throw new IllegalArgumentException("Truststore " + ns + "/" + name + " belongs to project " + ns
                + ", so only the releases of that project can use it. A release of project " + releaseNamespace.trim()
                + " can use the truststores of its own project and " + shared + ".");
    }

    private static String validNamespace(String namespace) {
        String ns = namespace.trim();
        if (!NAMESPACE_NAME.matcher(ns).matches()) {
            throw new IllegalArgumentException("'" + ns + "' is not a valid project name");
        }
        return ns;
    }

    /** Whether a Secret is a truststore created in the Truststores tab. */
    static boolean isManaged(Secret secret) {
        Map<String, String> labels = secret.getMetadata() != null ? secret.getMetadata().getLabels() : null;
        return labels != null && RESOURCE_TYPE_TRUSTSTORE.equals(labels.get(LABEL_RESOURCE_TYPE));
    }

    private void requireManaged(Secret secret) {
        if (!isManaged(secret)) {
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
