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

import org.apache.ambari.view.ViewContext;
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
 *   <li>CA registry entries flagged as default ({@link CaRegistryService#ANNOTATION_TRUST_DEFAULT}) —
 *       trusted by every release;</li>
 *   <li>registry CAs explicitly selected for this release (e.g. an OpenShift cert-manager issuer).</li>
 * </ol>
 *
 * <p>Used by both the orchestrated/direct deploy pipeline and the Flux GitOps backend so their trust
 * wiring can never diverge, and unlike the previous inline blocks it provisions a truststore even
 * when Ambari itself is not on SSL (Internal CA + default/selected CAs are still assembled).
 */
public class TrustBundleService {

    private static final Logger LOG = LoggerFactory.getLogger(TrustBundleService.class);

    private final ViewContext viewContext;
    private final KubernetesService kubernetesService;
    private final WebHookConfigurationService webHookConfigurationService;
    private final TruststoreRegistryService truststoreRegistryService;
    private final AmbariAliasResolver aliasResolver;

    public TrustBundleService(ViewContext viewContext, KubernetesService kubernetesService) {
        this.viewContext = Objects.requireNonNull(viewContext, "viewContext");
        this.kubernetesService = Objects.requireNonNull(kubernetesService, "kubernetesService");
        this.webHookConfigurationService = new WebHookConfigurationService(viewContext, kubernetesService);
        this.truststoreRegistryService = new TruststoreRegistryService(viewContext, kubernetesService);
        this.aliasResolver = new AmbariAliasResolver(viewContext);
    }

    /** Assembled trust material. */
    public record TrustMaterial(List<X509Certificate> certs, String caPem, byte[] jks, char[] jksPassword,
                                List<String> sources) {}

    /** Outcome of provisioning the release truststore Secret. */
    public record ProvisionResult(boolean provisioned, String secretName, Map<String, String> helmOverrides,
                                  int caCount) {}

    /**
     * Assemble the deduplicated CA set (and both JKS + PEM encodings) from all sources.
     *
     * @param truststoreRefs names of managed truststores selected for this release (may be null/empty);
     *                       truststores flagged default are always included regardless of this list
     */
    public TrustMaterial assemble(List<String> truststoreRefs) {
        List<X509Certificate> certs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> sources = new ArrayList<>();
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
                    char[] pass = aliasResolver.resolve(viewContext, passProp);
                    KeyStore ks = WebHookConfigurationService.loadKeyStore(Paths.get(path), pass, type);
                    for (X509Certificate c : WebHookConfigurationService.extractX509FromTrustStore(ks)) {
                        add.accept(c, "ambari-server-truststore");
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("trust-bundle: Ambari server truststore skipped: {}", e.toString());
        }

        // 2) Ambari Internal CA
        try {
            for (X509Certificate c : WebHookConfigurationService.parsePemCertificates(
                    webHookConfigurationService.ensureAmbariCertificateAuthority().caCertificatePem())) {
                add.accept(c, "ambari-internal-ca");
            }
        } catch (Exception e) {
            LOG.warn("trust-bundle: Ambari Internal CA unavailable: {}", e.toString());
        }

        // 3) Default truststores (flagged default in the Truststores tab)
        try {
            for (String pem : truststoreRegistryService.defaultTruststorePems()) {
                for (X509Certificate c : WebHookConfigurationService.parsePemCertificates(pem)) {
                    add.accept(c, "default-truststore");
                }
            }
        } catch (Exception e) {
            LOG.warn("trust-bundle: default truststore enumeration skipped: {}", e.toString());
        }

        // 4) Truststores explicitly selected for this release
        if (truststoreRefs != null) {
            for (String ref : truststoreRefs) {
                if (ref == null || ref.isBlank()) {
                    continue;
                }
                String name = ref.trim();
                try {
                    for (X509Certificate c : WebHookConfigurationService.parsePemCertificates(
                            truststoreRegistryService.caPemByName(name))) {
                        add.accept(c, "selected:" + name);
                    }
                } catch (Exception ex) {
                    LOG.warn("trust-bundle: selected truststore '{}' skipped: {}", name, ex.toString());
                }
            }
        }

        String caPem = WebHookConfigurationService.toPemBundle(certs);
        char[] jksPassword = UUID.randomUUID().toString().replace("-", "").toCharArray();
        byte[] jks = WebHookConfigurationService.buildJksFromCerts(certs, jksPassword);
        LOG.info("trust-bundle: assembled {} CA certificate(s) [{}]", certs.size(), String.join(",", sources));
        return new TrustMaterial(certs, caPem, jks, jksPassword, sources);
    }

    /** Convenience for callers that only need the PEM bundle (e.g. a per-service ca.crt). */
    public String assembleCaPem(List<String> truststoreRefs) {
        return assemble(truststoreRefs).caPem();
    }

    /**
     * Assemble and write the {@code <release>-truststore} Secret, returning the Helm overrides the
     * caller should apply. Returns {@code provisioned=false} (and writes nothing) when no CA could
     * be assembled at all.
     */
    public ProvisionResult provisionReleaseTruststore(String namespace, String releaseName, List<String> truststoreRefs) {
        TrustMaterial material = assemble(truststoreRefs);
        if (material.certs().isEmpty()) {
            return new ProvisionResult(false, null, Map.of(), 0);
        }
        String secretName = releaseName + "-truststore";
        Map<String, byte[]> data = new LinkedHashMap<>();
        data.put("truststore.jks", material.jks());
        data.put("truststore.password", new String(material.jksPassword()).getBytes(StandardCharsets.UTF_8));
        data.put("ca.crt", material.caPem().getBytes(StandardCharsets.UTF_8));
        kubernetesService.createOrUpdateOpaqueSecret(namespace, secretName, data);

        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("global.security.tls.enabled", "true");
        overrides.put("global.security.tls.truststore.enabled", "true");
        overrides.put("global.security.tls.truststoreSecret", secretName);
        overrides.put("global.security.tls.truststoreKey", "truststore.jks");
        overrides.put("global.security.tls.truststorePasswordKey", "truststore.password");
        return new ProvisionResult(true, secretName, overrides, material.certs().size());
    }
}
