package org.apache.ambari.view.k8s.utils;
import org.apache.ambari.view.k8s.model.RangerConfigKind;
import org.apache.ambari.view.k8s.model.RangerTrinoConfigDefaults;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Applies resolved tokens (e.g., values fetched from Ambari configs) to the generated defaults.
 * <p>
 * Keep this intentionally small and explicit so it stays obvious where each token lands.
 */
public final class RangerOverrides {

    private RangerOverrides() {
    }

    /**
     * Apply a set of resolved tokens to the Trino defaults.
     *
     * @param serviceName    usually "trino"
     * @param byKind         kind -> (propertyName -> value) as returned by {@link RangerTrinoConfigDefaults#buildDefaultRangerPluginConfig(String)}
     * @param resolvedTokens a flat map of token -> value after your Ambari lookups
     */
    public static void applyResolvedTokens(String serviceName,
                                           EnumMap<RangerConfigKind, Map<String, String>> byKind,
                                           Map<String, String> resolvedTokens) {
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(byKind, "byKind");
        if (resolvedTokens == null || resolvedTokens.isEmpty()) {
            return;
        }

        // Common tokens you likely resolve with your DYNAMIC_SOURCE_MAP
        String rangerUrl = resolvedTokens.getOrDefault("RANGER_ADMIN_URL", null);
        String kerberosRealm = resolvedTokens.getOrDefault("AMBARI_KERBEROS_REALM", null);
        String rangerServiceName = resolvedTokens.getOrDefault("RANGER_SERVICE_NAME", serviceName);

        // 1) plugin-properties: rest url + service name
        Map<String, String> plugin = byKind.get(RangerConfigKind.PLUGIN_PROPERTIES);
        if (plugin != null) {
            String prefix = "ranger.plugin." + serviceName + ".";
            if (rangerUrl != null && !rangerUrl.isBlank()) {
                plugin.put(prefix + "policy.rest.url", rangerUrl.trim());
            }
            if (rangerServiceName != null && !rangerServiceName.isBlank()) {
                plugin.put(prefix + "service.name", rangerServiceName.trim());
            }
        }

        // 2) security: principal if we have the realm and/or the full principal token
        Map<String, String> security = byKind.get(RangerConfigKind.SECURITY);
        if (security != null) {
            String prefix = "ranger.plugin." + serviceName + ".";
            String explicitPrincipal = resolvedTokens.get("RANGER_PLUGIN_PRINCIPAL"); // optional direct override
            if (explicitPrincipal != null && !explicitPrincipal.isBlank()) {
                security.put(prefix + "ugi.keytab.principal", explicitPrincipal.trim());
                security.put(prefix + "ugi.initialize", "true");
            } else if (kerberosRealm != null && !kerberosRealm.isBlank()) {
                // If you prefer to build the principal here deterministically, do it.
                // Otherwise leave it empty for the caller to set later.
                // Example (service headless style): trino-coordinator-<ns>@<realm> – up to your existing convention.
            }
        }

        // 3) policymgr-ssl: you typically override only the truststore password (from a Secret)
        Map<String, String> ssl = byKind.get(RangerConfigKind.POLICYMGR_SSL);
        if (ssl != null) {
            String tsPassword = resolvedTokens.get("RANGER_TRUSTSTORE_PASSWORD");
            String tsType = resolvedTokens.get("RANGER_TRUSTSTORE_TYPE");
            String tsPath = resolvedTokens.get("RANGER_TRUSTSTORE_PATH");
            if (tsPassword != null) ssl.put("xasecure.policymgr.clientssl.truststore.password", tsPassword);
            if (tsType != null) ssl.put("xasecure.policymgr.clientssl.truststore.type", tsType);
            if (tsPath != null) ssl.put("xasecure.policymgr.clientssl.truststore", tsPath);

            // Optional mTLS (client cert to Ranger Admin)
            String ksPassword = resolvedTokens.get("RANGER_KEYSTORE_PASSWORD");
            String ksType = resolvedTokens.get("RANGER_KEYSTORE_TYPE");
            String ksPath = resolvedTokens.get("RANGER_KEYSTORE_PATH");
            if (ksPassword != null) ssl.put("xasecure.policymgr.clientssl.keystore.password", ksPassword);
            if (ksType != null) ssl.put("xasecure.policymgr.clientssl.keystore.type", ksType);
            if (ksPath != null) ssl.put("xasecure.policymgr.clientssl.keystore", ksPath);
        }

        // 4) audit: toggle Solr/HDFS if you resolved cluster capabilities
        Map<String, String> audit = byKind.get(RangerConfigKind.AUDIT);
        if (audit != null) {
            String enableSolr = resolvedTokens.get("RANGER_AUDIT_SOLR_ENABLED");
            String solrZk = resolvedTokens.get("RANGER_AUDIT_SOLR_ZK");
            String enableHdfs = resolvedTokens.get("RANGER_AUDIT_HDFS_ENABLED");
            String hdfsDir = resolvedTokens.get("RANGER_AUDIT_HDFS_DIR");

            if (enableSolr != null) audit.put("xasecure.audit.solr.is.enabled", enableSolr);
            if (solrZk != null) audit.put("xasecure.audit.solr.zookeepers", solrZk);
            if (enableHdfs != null) audit.put("xasecure.audit.hdfs.is.enabled", enableHdfs);
            if (hdfsDir != null) audit.put("xasecure.audit.hdfs.dir", hdfsDir);
        }
    }
}