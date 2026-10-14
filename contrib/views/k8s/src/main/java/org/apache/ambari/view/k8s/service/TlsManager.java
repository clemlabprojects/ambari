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

import io.fabric8.kubernetes.api.model.Secret;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.WebHookConfigurationService.ServerKeystoreMaterial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Handles TLS keystore autogeneration for services that opt into it via service.json "tls" section.
 *
 * The UI sends a resolved map under request.tls (keyed by logical TLS entry, e.g. "https") containing:
 *  - enabled / autoGenerate flags
 *  - secretName, keystoreKey, passwordKey, keystorePath, mountPath, keystoreType/format
 *  - dnsNames: List<String> for SANs
 *
 * For each enabled entry:
 *  - generates a PKCS12/JKS keystore OR a PEM bundle signed by the Ambari internal CA
 *  - stores it in an Opaque Secret along with the password (when applicable) and CA certificate
 *  - with autoGenerate off, keeps a typed password in the password Secret (created, or updated when this view wrote it)
 *  - the chart wiring (bindings) is driven from service.json; this class does not mutate Helm values.
 */
public class TlsManager {
    private static final Logger LOG = LoggerFactory.getLogger(TlsManager.class);

    private final KubernetesService kubernetesService;
    private final WebHookConfigurationService webHookConfigurationService;

    public TlsManager(KubernetesService kubernetesService, ViewContext viewContext) {
        this.kubernetesService = Objects.requireNonNull(kubernetesService, "kubernetesService");
        this.webHookConfigurationService = new WebHookConfigurationService(viewContext, kubernetesService);
    }

    TlsManager(KubernetesService kubernetesService, WebHookConfigurationService webHookConfigurationService) {
        this.kubernetesService = Objects.requireNonNull(kubernetesService, "kubernetesService");
        this.webHookConfigurationService = Objects.requireNonNull(webHookConfigurationService, "webHookConfigurationService");
    }

    /**
     * Entry point invoked during submit-time: generate keystore Secrets when enabled.
     *
     * @param tlsSpec map keyed by TLS entry (e.g. "https") coming from the UI payload.
     * @param request deployment request (release/namespace used for defaults).
     * @param params  params map (left untouched; Helm wiring is handled by bindings).
     */
    @SuppressWarnings("unchecked")
    public void applyTls(Map<String, Object> tlsSpec, HelmDeployRequest request, Map<String, Object> params) {
        if (tlsSpec == null || tlsSpec.isEmpty()) {
            LOG.info("applyTls: no TLS entries provided for release {}", request != null ? request.getReleaseName() : "<null>");
            return;
        }

        LOG.info("applyTls: processing {} TLS entries for release {} in namespace {}",
                tlsSpec.size(),
                request.getReleaseName(),
                request.getNamespace());

        for (Map.Entry<String, Object> tlsEntry : tlsSpec.entrySet()) {
            String tlsKey = tlsEntry.getKey();
            if (!(tlsEntry.getValue() instanceof Map<?, ?> rawEntry)) {
                LOG.info("applyTls: skipping TLS entry '{}' because value is not a map", tlsKey);
                continue;
            }
            Map<String, Object> tlsEntrySpec = (Map<String, Object>) rawEntry;
            boolean enabled = asBoolean(tlsEntrySpec.get("enabled"), false);
            if (!enabled) {
                LOG.info("TLS entry '{}' disabled for release {}", tlsKey, request.getReleaseName());
                continue;
            }

            boolean autoGenerate = asBoolean(tlsEntrySpec.get("autoGenerate"), true);
            int validityDays = asInt(tlsEntrySpec.get("validityDays"), 365);
            String secretName = valueOrDefault(tlsEntrySpec.get("secretName"), request.getReleaseName() + "-" + tlsKey + "-tls");
            String passwordSecretName = valueOrDefault(tlsEntrySpec.get("passwordSecretName"), secretName + "-pass");
            String keystoreKey = valueOrDefault(tlsEntrySpec.get("keystoreKey"), "keystore.p12");
            String passwordKey = valueOrDefault(tlsEntrySpec.get("passwordKey"), "truststore.password");
            String keystoreType = valueOrDefault(tlsEntrySpec.get("keystoreType"), "PKCS12");
            String mountPath = valueOrDefault(tlsEntrySpec.get("mountPath"), "/etc/security/tls/https-keystore.p12");
            String keystorePath = valueOrDefault(tlsEntrySpec.get("keystorePath"), mountPath);
            String typedPassword = valueOrDefault(tlsEntrySpec.get("password"), null);
            String password = typedPassword != null ? typedPassword : randomPassword();

            List<String> dnsNames = new ArrayList<>();
            Object dnsRaw = tlsEntrySpec.get("dnsNames");
            if (dnsRaw instanceof List<?> list) {
                for (Object d : list) {
                    if (d != null && !String.valueOf(d).isBlank()) {
                        dnsNames.add(String.valueOf(d).trim());
                    }
                }
            }
            if (dnsNames.isEmpty()) {
                dnsNames.add(request.getReleaseName() + "." + request.getNamespace() + ".svc");
            }

            LOG.info("TLS entry '{}' enabled for release {} (secret={}, autoGenerate={})",
                    tlsKey, request.getReleaseName(), secretName, autoGenerate);

            if (autoGenerate) {
                ensureNamespaceExists(request.getNamespace());
                // Generate and persist a keystore or PEM Secret signed by Ambari's internal CA.
                generateKeystoreSecret(request.getNamespace(), secretName, keystoreKey, passwordKey, keystoreType, password, dnsNames, validityDays,
                        request.getReleaseName());
                generatePasswordSecret(request.getNamespace(), passwordSecretName, passwordKey, password,
                        request.getReleaseName());
                LOG.info("TLS entry '{}' secrets prepared: keystoreSecret={}, passwordSecret={}, dnsSans={}",
                        tlsKey,
                        secretName,
                        passwordSecretName,
                        String.join(",", dnsNames));
            } else {
                LOG.info("Auto-generation disabled for TLS entry '{}'; expecting Secret {} to exist", tlsKey, secretName);
                if (typedPassword != null) {
                    ensurePasswordSecret(request.getNamespace(), passwordSecretName, passwordKey, typedPassword,
                            request.getReleaseName());
                }
            }

            // Helm values are handled via bindings; here we only provision the secret.
        }
    }

    /**
     * Ensure the target namespace exists before provisioning TLS secrets.
     *
     * @param namespace target namespace for secret creation
     */
    private void ensureNamespaceExists(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("Namespace is required for TLS secret provisioning");
        }
        try {
            kubernetesService.createNamespace(namespace);
            LOG.info("TLS provisioning ensured namespace {} exists", namespace);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to ensure namespace " + namespace + " exists for TLS provisioning", e);
        }
    }

    /**
     * Writes the keystore or PEM Secret KDPS generated for the release, which holds the server's private key: it
     * carries the release labels (see {@link #releaseLabels}), so the uninstall of the release removes it.
     */
    private void generateKeystoreSecret(String namespace,
                                        String secretName,
                                        String keystoreKey,
                                        String passwordKey,
                                        String keystoreType,
                                        String password,
                                        List<String> dnsNames,
                                        int validityDays,
                                        String releaseName) {
        try {
            String format = keystoreType == null ? "PKCS12" : keystoreType.trim().toUpperCase(Locale.ROOT);
            ServerKeystoreMaterial material = webHookConfigurationService.issueServerKeystore(dnsNames, format, password.toCharArray(), validityDays);

            Map<String, byte[]> data = new LinkedHashMap<>();
            if ("PEM".equalsIgnoreCase(format)) {
                data.put("ca.crt", material.caCertificatePem().getBytes());
                data.put("tls.crt", material.certificatePem().getBytes());
                data.put("tls.key", material.privateKeyPem().getBytes());
            } else {
                data.put(keystoreKey, material.keystoreBytes());
                data.put(passwordKey, password.getBytes());
                data.put("ca.crt", material.caCertificatePem().getBytes());
                data.put("tls.crt", material.certificatePem().getBytes());
                data.put("tls.key", material.privateKeyPem().getBytes());
            }

            writeSecret(namespace, secretName, data, releaseName);
            LOG.info("Provisioned TLS keystore Secret {}/{} with dnsSans={}", namespace, secretName, String.join(",", dnsNames));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate TLS keystore secret " + namespace + "/" + secretName + ": " + e.getMessage(), e);
        }
    }

    /** Writes the keystore password Secret of the release, with the release labels like the keystore's. */
    private void generatePasswordSecret(String namespace,
                                        String passwordSecretName,
                                        String passwordKey,
                                        String password,
                                        String releaseName) {
        try {
            Map<String, byte[]> data = new LinkedHashMap<>();
            data.put(passwordKey, password.getBytes());
            writeSecret(namespace, passwordSecretName, data, releaseName);
            LOG.info("Provisioned TLS password Secret {}/{} (key={})", namespace, passwordSecretName, passwordKey);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate TLS password secret " + namespace + "/" + passwordSecretName + ": " + e.getMessage(), e);
        }
    }

    /**
     * With the operator's own keystore, keeps the password they typed in the password Secret the chart mounts, so it
     * never has to be written into the server configuration (a ConfigMap anyone allowed to read ConfigMaps can
     * read). The chart reads the keystore password from that Secret only, so it must hold the typed one:
     * <ul>
     *   <li>missing, it is created;</li>
     *   <li>written by this view (an earlier deploy, with a generated keystore or another typed password), it is
     *   updated;</li>
     *   <li>the operator's own, holding another password or none under the key, the deploy is refused: the service
     *   could not open its keystore, and only the operator knows which password is the right one.</li>
     * </ul>
     *
     * @throws IllegalArgumentException with an operator-readable message, for an operator's Secret that does not hold
     *                                  the typed password
     */
    private void ensurePasswordSecret(String namespace,
                                      String passwordSecretName,
                                      String passwordKey,
                                      String typedPassword,
                                      String releaseName) {
        Secret existing;
        try {
            existing = kubernetesService.getSecret(namespace, passwordSecretName);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read TLS password secret " + namespace + "/" + passwordSecretName
                    + ": " + e.getMessage(), e);
        }
        if (existing == null) {
            ensureNamespaceExists(namespace);
            generatePasswordSecret(namespace, passwordSecretName, passwordKey, typedPassword, releaseName);
            return;
        }
        String encoded = existing.getData() == null ? null : existing.getData().get(passwordKey);
        if (encoded != null && typedPassword.equals(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8))) {
            LOG.info("TLS password Secret {}/{} already holds the typed password", namespace, passwordSecretName);
            return;
        }
        if (writtenByThisView(existing)) {
            generatePasswordSecret(namespace, passwordSecretName, passwordKey, typedPassword, releaseName);
            return;
        }
        String problem = encoded == null
                ? "has no key '" + passwordKey + "', from which the release reads its keystore password"
                : "holds a different keystore password (key '" + passwordKey + "') from the one typed under"
                        + " 'Keystore password'. The release reads the password from that Secret, so it could not open"
                        + " its keystore";
        String remedy = encoded == null
                ? "Add the keystore password to the Secret under that key, or delete the Secret: it is then created"
                        + " from the typed password."
                : "Clear 'Keystore password' to keep the password the Secret holds, or put the typed password in the"
                        + " Secret (or delete the Secret: it is then created from the typed password).";
        throw new IllegalArgumentException("Secret '" + passwordSecretName + "' in namespace '" + namespace + "' "
                + problem + ". " + remedy);
    }

    /**
     * Writes a Secret KDPS generates for a release. It carries the release labels unless another release claims the
     * Secret already there (two releases given the same Secret name), so the uninstall of the release removes what
     * it generated and nothing another release uses. A Secret the operator provided is never written here: with
     * their own keystore, only the password Secret is, and only one this view wrote or that is missing.
     */
    private void writeSecret(String namespace, String secretName, Map<String, byte[]> data, String releaseName) {
        Map<String, String> labels = releaseLabels(namespace, secretName, releaseName);
        kubernetesService.createOrUpdateOpaqueSecret(namespace, secretName, data, labels,
                labels.isEmpty() ? null : KdpsReleaseObjects.annotations(namespace));
    }

    /** The release labels a Secret KDPS writes for {@code releaseName} gets (see {@link #writeSecret}). */
    private Map<String, String> releaseLabels(String namespace, String secretName, String releaseName) {
        Secret existing;
        try {
            existing = kubernetesService.getSecret(namespace, secretName);
        } catch (Exception e) {
            existing = null;
        }
        return KdpsReleaseObjects.labelsFor(existing == null ? null : existing.getMetadata(), true, namespace,
                releaseName);
    }

    /** Whether this view wrote the Secret (it marks every Secret it writes), rather than the operator. */
    private static boolean writtenByThisView(Secret secret) {
        return secret.getMetadata() != null && secret.getMetadata().getAnnotations() != null
                && "ambari-k8s-view".equals(secret.getMetadata().getAnnotations().get("managed-by"));
    }

    private static boolean asBoolean(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim().toLowerCase();
        if ("true".equals(s) || "yes".equals(s) || "1".equals(s)) return true;
        if ("false".equals(s) || "no".equals(s) || "0".equals(s)) return false;
        return def;
    }

    private static int asInt(Object o, int def) {
        if (o == null) return def;
        try { return Integer.parseInt(String.valueOf(o)); }
        catch (Exception ignore) { return def; }
    }

    private static String valueOrDefault(Object o, String def) {
        if (o == null) return def;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? def : s;
    }

    private static String randomPassword() {
        // short but stable password for keystore Secret; not security-critical (pod-local)
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
