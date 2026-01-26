package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.WebHookConfigurationService.ServerKeystoreMaterial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
            String password = valueOrDefault(tlsEntrySpec.get("password"), randomPassword());

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
                generateKeystoreSecret(request.getNamespace(), secretName, keystoreKey, passwordKey, keystoreType, password, dnsNames, validityDays);
                generatePasswordSecret(request.getNamespace(), passwordSecretName, passwordKey, password);
                LOG.info("TLS entry '{}' secrets prepared: keystoreSecret={}, passwordSecret={}, dnsSans={}",
                        tlsKey,
                        secretName,
                        passwordSecretName,
                        String.join(",", dnsNames));
            } else {
                LOG.info("Auto-generation disabled for TLS entry '{}'; expecting Secret {} to exist", tlsKey, secretName);
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

    private void generateKeystoreSecret(String namespace,
                                        String secretName,
                                        String keystoreKey,
                                        String passwordKey,
                                        String keystoreType,
                                        String password,
                                        List<String> dnsNames,
                                        int validityDays) {
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

            kubernetesService.createOrUpdateOpaqueSecret(namespace, secretName, data);
            LOG.info("Provisioned TLS keystore Secret {}/{} with dnsSans={}", namespace, secretName, String.join(",", dnsNames));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate TLS keystore secret " + namespace + "/" + secretName + ": " + e.getMessage(), e);
        }
    }

    private void generatePasswordSecret(String namespace,
                                        String passwordSecretName,
                                        String passwordKey,
                                        String password) {
        try {
            Map<String, byte[]> data = new LinkedHashMap<>();
            data.put(passwordKey, password.getBytes());
            kubernetesService.createOrUpdateOpaqueSecret(namespace, passwordSecretName, data);
            LOG.info("Provisioned TLS password Secret {}/{} (key={})", namespace, passwordSecretName, passwordKey);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate TLS password secret " + namespace + "/" + passwordSecretName + ": " + e.getMessage(), e);
        }
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
