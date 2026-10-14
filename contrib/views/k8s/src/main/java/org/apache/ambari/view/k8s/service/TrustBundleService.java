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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.apache.ambari.view.k8s.utils.AmbariAliasResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Single source of truth for the trust bundle mounted into deployed workloads.
 *
 * <p>Assembles the {@code <release>-truststore} Secret (both {@code truststore.jks} for JVM clients
 * and {@code ca.crt} PEM for non-JVM clients such as python-ldap / requests) from up to four
 * sources, deduplicated by SHA-256 fingerprint:
 * <ol>
 *   <li>the Ambari server SSL truststore ({@code ssl.trustStore.*} in ambari.properties), with the
 *       password credential-store/alias resolved — present only when Ambari runs on SSL;</li>
 *   <li>the Ambari Internal CA (always) — so view-signed pod certs stay trusted;</li>
 *   <li>truststores of the Truststores tab flagged default
 *       ({@link TruststoreRegistryService#ANNOTATION_TRUST_DEFAULT}) — trusted by every release;</li>
 *   <li>truststores of the Truststores tab selected for this release in the deploy wizard.</li>
 * </ol>
 *
 * <p>Used by both the orchestrated/direct deploy pipeline and the Flux GitOps backend so their trust
 * wiring can never diverge, and unlike the previous inline blocks it provisions a truststore even
 * when Ambari itself is not on SSL (Internal CA + default/selected CAs are still assembled).
 *
 * <p>The sources are not equal. The first three are trusted by every release, and one that cannot be read is left
 * out with a warning (see {@link TrustMaterial#warnings()}) the deploy writes to its log. A truststore selected for
 * the release is what the operator asked for: one that cannot be read stops the deploy, naming it, rather than
 * deploying a release that then fails its TLS handshakes.
 */
public class TrustBundleService {

    private static final Logger LOG = LoggerFactory.getLogger(TrustBundleService.class);

    /** The template service definitions bind the release truststore Secret with (GitLab). */
    static final String RELEASE_TRUSTSTORE_TEMPLATE = "${releaseName}" + TruststoreRegistryService.SUFFIX;

    private final ViewContext viewContext;
    private final KubernetesService kubernetesService;
    private final WebHookConfigurationService webHookConfigurationService;
    private final TruststoreRegistryService truststoreRegistryService;

    public TrustBundleService(ViewContext viewContext, KubernetesService kubernetesService) {
        this(viewContext, kubernetesService, new WebHookConfigurationService(viewContext, kubernetesService),
                new TruststoreRegistryService(viewContext, kubernetesService));
    }

    TrustBundleService(ViewContext viewContext, KubernetesService kubernetesService,
                       WebHookConfigurationService webHookConfigurationService,
                       TruststoreRegistryService truststoreRegistryService) {
        this.viewContext = Objects.requireNonNull(viewContext, "viewContext");
        this.kubernetesService = Objects.requireNonNull(kubernetesService, "kubernetesService");
        this.webHookConfigurationService = Objects.requireNonNull(webHookConfigurationService, "webHookConfigurationService");
        this.truststoreRegistryService = Objects.requireNonNull(truststoreRegistryService, "truststoreRegistryService");
    }

    /**
     * Assembled trust material.
     *
     * @param warnings the sources trusted by every release that could not be read and were left out, as sentences
     *                 for the operator
     */
    public record TrustMaterial(List<X509Certificate> certs, String caPem, byte[] jks, char[] jksPassword,
                                List<String> sources, List<String> warnings) {}

    /**
     * Outcome of provisioning the release truststore Secret.
     *
     * @param warnings what was left out of it, or why it was not written, as sentences for the operator
     */
    public record ProvisionResult(boolean provisioned, String secretName, Map<String, String> helmOverrides,
                                  int caCount, List<String> warnings) {}

    /**
     * Assemble the deduplicated CA set (and both JKS + PEM encodings) from all sources.
     *
     * @param truststoreRefs truststores selected for this release (may be null/empty), as
     *                       {@link TruststoreRegistryService#caPemByName} reads them; truststores flagged default are
     *                       always included regardless of this list
     * @throws IllegalStateException when a selected truststore cannot be read, naming every one that cannot
     */
    public TrustMaterial assemble(List<String> truststoreRefs) {
        return assemble(truststoreRefs, null);
    }

    /**
     * Same as {@link #assemble(List)}, protecting the JKS with {@code password} when given (a fresh one otherwise).
     */
    TrustMaterial assemble(List<String> truststoreRefs, char[] password) {
        List<X509Certificate> certs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> sources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        java.util.function.BiConsumer<X509Certificate, String> add = (c, src) -> {
            try {
                String fp = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));
                if (seen.add(fp)) {
                    certs.add(c);
                    sources.add(src);
                }
            } catch (Exception ignore) {
                // skip a cert we cannot fingerprint rather than fail the whole deploy
            }
        };

        // 1) Ambari server SSL truststore
        try {
            String path = viewContext.getAmbariProperty("ssl.trustStore.path");
            if (path != null && !path.isBlank()) {
                String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
                if (lower.endsWith("/lib/security/cacerts") || lower.endsWith("/jre/lib/security/cacerts")) {
                    LOG.info("trust-bundle: ssl.trustStore.path is the JDK default cacerts; skipping to avoid bundling public roots");
                } else {
                    String type = Optional.ofNullable(viewContext.getAmbariProperty("ssl.trustStore.type"))
                            .filter(s -> !s.isBlank()).orElse("JKS");
                    String passProp = Optional.ofNullable(viewContext.getAmbariProperty("ssl.trustStore.password")).orElse("");
                    // Created only here: it opens Ambari's credential store, which only this source needs.
                    char[] pass = new AmbariAliasResolver(viewContext).resolve(viewContext, passProp);
                    KeyStore ks = WebHookConfigurationService.loadKeyStore(Paths.get(path), pass, type);
                    for (X509Certificate c : WebHookConfigurationService.extractX509FromTrustStore(ks)) {
                        add.accept(c, "ambari-server-truststore");
                    }
                }
            }
        } catch (Exception e) {
            warn(warnings, "The CAs of the Ambari server truststore are not trusted by this release: it could not be "
                    + "read (" + describe(e) + ").");
        }

        // 2) Ambari Internal CA
        try {
            for (X509Certificate c : WebHookConfigurationService.parsePemCertificates(
                    webHookConfigurationService.ensureAmbariCertificateAuthority().caCertificatePem())) {
                add.accept(c, "ambari-internal-ca");
            }
        } catch (Exception e) {
            warn(warnings, "The Ambari Internal CA is not trusted by this release: it could not be read ("
                    + describe(e) + ").");
        }

        // 3) Default truststores (flagged default in the Truststores tab)
        try {
            for (String pem : truststoreRegistryService.defaultTruststorePems()) {
                for (X509Certificate c : WebHookConfigurationService.parsePemCertificates(pem)) {
                    add.accept(c, "default-truststore");
                }
            }
        } catch (Exception e) {
            warn(warnings, "The default truststores of the Truststores tab are not trusted by this release: they "
                    + "could not be listed (" + describe(e) + ").");
        }

        // 4) Truststores explicitly selected for this release: every one is required, so all that cannot be read
        //    are named together, for the operator to fix them in one go.
        List<String> unreadable = new ArrayList<>();
        if (truststoreRefs != null) {
            for (String ref : truststoreRefs) {
                if (ref == null || ref.isBlank()) {
                    continue;
                }
                String name = ref.trim();
                try {
                    List<X509Certificate> selected = WebHookConfigurationService.parsePemCertificates(
                            truststoreRegistryService.caPemByName(name));
                    if (selected.isEmpty()) {
                        throw new IllegalArgumentException("it holds no certificate");
                    }
                    for (X509Certificate c : selected) {
                        add.accept(c, "selected:" + name);
                    }
                } catch (Exception ex) {
                    LOG.warn("trust-bundle: selected truststore '{}' cannot be read: {}", name, ex.toString());
                    unreadable.add("'" + name + "' (" + describe(ex) + ")");
                }
            }
        }
        if (!unreadable.isEmpty()) {
            throw new IllegalStateException((unreadable.size() == 1 ? "The truststore " : "The truststores ")
                    + String.join(", ", unreadable) + " selected for this release could not be read. Recreate "
                    + (unreadable.size() == 1 ? "it" : "them") + " in the Truststores tab, or remove "
                    + (unreadable.size() == 1 ? "it" : "them") + " from the release, then deploy again.");
        }

        String caPem = WebHookConfigurationService.toPemBundle(certs);
        char[] jksPassword = password != null && password.length > 0 ? password
                : UUID.randomUUID().toString().replace("-", "").toCharArray();
        byte[] jks = WebHookConfigurationService.buildJksFromCerts(certs, jksPassword);
        LOG.info("trust-bundle: assembled {} CA certificate(s) [{}]", certs.size(), String.join(",", sources));
        return new TrustMaterial(certs, caPem, jks, jksPassword, sources, List.copyOf(warnings));
    }

    private static void warn(List<String> warnings, String warning) {
        LOG.warn("trust-bundle: {}", warning);
        warnings.add(warning);
    }

    /** The cause of a failure, for a sentence: its message, else its type. */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private io.fabric8.kubernetes.api.model.Secret secretOrNull(String namespace, String secretName) {
        try {
            return kubernetesService.getSecret(namespace, secretName);
        } catch (Exception e) {
            LOG.debug("trust-bundle: cannot read {}/{}: {}", namespace, secretName, e.toString());
            return null;
        }
    }

    /**
     * The password of a truststore Secret this view wrote for exactly these CAs, or {@code null}: a Secret from
     * elsewhere, or one holding other CAs, gets a fresh password.
     *
     * @param secret the release's existing truststore Secret, may be null
     * @param caPem  the CA bundle about to be written
     */
    static char[] reusablePassword(io.fabric8.kubernetes.api.model.Secret secret, String caPem) {
        if (secret == null || secret.getMetadata() == null || secret.getData() == null
                || secret.getMetadata().getAnnotations() == null
                || !"ambari-k8s-view".equals(secret.getMetadata().getAnnotations().get("managed-by"))) {
            return null;
        }
        String storedCa = secret.getData().get("ca.crt");
        try {
            if (storedCa == null || caPem == null
                    || !new String(Base64.getDecoder().decode(storedCa.trim()), StandardCharsets.UTF_8).equals(caPem)) {
                return null;
            }
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
        return passwordFromSecret(secret);
    }

    /** The {@code truststore.password} of a truststore Secret, or {@code null} when absent, blank or unreadable. */
    static char[] passwordFromSecret(io.fabric8.kubernetes.api.model.Secret secret) {
        String encoded = secret == null || secret.getData() == null ? null : secret.getData().get("truststore.password");
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String password = new String(Base64.getDecoder().decode(encoded.trim()), StandardCharsets.UTF_8).trim();
            return password.isEmpty() ? null : password.toCharArray();
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }

    /** Convenience for callers that only need the PEM bundle (e.g. a per-service ca.crt). */
    public String assembleCaPem(List<String> truststoreRefs) {
        return assemble(truststoreRefs).caPem();
    }

    /**
     * Assemble and write the {@code <release>-truststore} Secret, returning the Helm overrides the
     * caller should apply. Returns {@code provisioned=false} (and writes nothing) when no CA could
     * be assembled at all.
     *
     * @throws IllegalStateException when a selected truststore cannot be read
     * @throws RuntimeException      when the Secret cannot be written
     */
    public ProvisionResult provisionReleaseTruststore(String namespace, String releaseName, List<String> truststoreRefs) {
        String secretName = releaseName + TruststoreRegistryService.SUFFIX;
        TrustMaterial material = assemble(truststoreRefs);
        if (material.certs().isEmpty()) {
            return new ProvisionResult(false, null, Map.of(), 0, material.warnings());
        }
        // Same CAs as the Secret this view wrote last time: keep its password. The password ends up in the service
        // configuration, so a new one restarts every pod; with changed CAs that restart is wanted (the JKS is mounted
        // as a file the pods only read at start), so a fresh password is kept then.
        char[] previous = reusablePassword(secretOrNull(namespace, secretName), material.caPem());
        if (previous != null) {
            material = assemble(truststoreRefs, previous);
        }
        Map<String, byte[]> data = new LinkedHashMap<>();
        data.put("truststore.jks", material.jks());
        data.put("truststore.password", new String(material.jksPassword()).getBytes(StandardCharsets.UTF_8));
        data.put("ca.crt", material.caPem().getBytes(StandardCharsets.UTF_8));
        // The release's own Secret: its uninstall removes it.
        kubernetesService.createOrUpdateOpaqueSecret(namespace, secretName, data,
                KdpsReleaseObjects.labels(releaseName), KdpsReleaseObjects.annotations(namespace));

        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("global.security.tls.enabled", "true");
        overrides.put("global.security.tls.truststore.enabled", "true");
        overrides.put("global.security.tls.truststoreSecret", secretName);
        overrides.put("global.security.tls.truststoreKey", "truststore.jks");
        overrides.put("global.security.tls.truststorePasswordKey", "truststore.password");
        return new ProvisionResult(true, secretName, overrides, material.certs().size(), material.warnings());
    }

    /**
     * Provisions the release truststore for a deploy, before anything is installed or committed to Git. Shared by
     * direct and GitOps deploys.
     *
     * <p>A release needs it when truststores were selected for it, or when its service mounts the Secret itself
     * (see {@link #bindsReleaseTruststore}): its pods would then wait for a Secret that never comes. Such a deploy
     * stops when the Secret cannot be written, never falling back to a deploy without its trust wiring. Otherwise the
     * failure is a warning and the release is deployed without the TLS overrides, as before.</p>
     *
     * @param serviceKey     the service deployed, whose definition may mount the Secret; may be null
     * @param truststoreRefs truststores selected for the release; may be null
     * @return the result, its warnings for the operation log
     * @throws IllegalStateException {@code Could not write truststore Secret <ns>/<release>-truststore: <cause>} when
     *                               the release needs it
     */
    public ProvisionResult provisionForDeploy(String namespace, String releaseName, String serviceKey,
                                              List<String> truststoreRefs) {
        String secretName = releaseName + TruststoreRegistryService.SUFFIX;
        boolean required = hasSelection(truststoreRefs) || serviceBindsReleaseTruststore(serviceKey);
        ProvisionResult result;
        try {
            result = provisionReleaseTruststore(namespace, releaseName, truststoreRefs);
        } catch (RuntimeException e) {
            if (required) {
                throw new IllegalStateException(writeFailure(namespace, secretName, describe(e)), e);
            }
            String warning = "The truststore Secret " + namespace + "/" + secretName + " could not be written, so this "
                    + "release is deployed without the Ambari and company CAs: " + describe(e);
            LOG.warn("trust-bundle: {}", warning);
            return new ProvisionResult(false, null, Map.of(), 0, List.of(warning));
        }
        if (!result.provisioned() && required) {
            String why = "no CA certificate could be assembled";
            if (!result.warnings().isEmpty()) {
                why += " (" + String.join(" ", result.warnings()) + ")";
            }
            throw new IllegalStateException(writeFailure(namespace, secretName, why));
        }
        return result;
    }

    private static String writeFailure(String namespace, String secretName, String cause) {
        return "Could not write truststore Secret " + namespace + "/" + secretName + ": " + cause;
    }

    private static boolean hasSelection(List<String> truststoreRefs) {
        return truststoreRefs != null && truststoreRefs.stream().anyMatch(r -> r != null && !r.isBlank());
    }

    /** Whether the service's definition mounts the release truststore Secret; false when it cannot be read. */
    private boolean serviceBindsReleaseTruststore(String serviceKey) {
        if (serviceKey == null || serviceKey.isBlank()) {
            return false;
        }
        try {
            return bindsReleaseTruststore(new StackDefinitionService(viewContext).getServiceDefinition(serviceKey));
        } catch (RuntimeException e) {
            LOG.debug("trust-bundle: no definition of service {}: {}", serviceKey, e.toString());
            return false;
        }
    }

    /**
     * Whether a service definition binds a chart value to the release truststore Secret
     * ({@value #RELEASE_TRUSTSTORE_TEMPLATE}), so its pods mount it.
     *
     * @param def the service definition; may be null
     */
    static boolean bindsReleaseTruststore(StackServiceDef def) {
        if (def == null || def.bindings == null || def.bindings.isEmpty()) {
            return false;
        }
        try {
            return new ObjectMapper().writeValueAsString(def.bindings).contains(RELEASE_TRUSTSTORE_TEMPLATE);
        } catch (JsonProcessingException e) {
            return false;
        }
    }
}
