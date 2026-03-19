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

package org.apache.ambari.view.k8s.model;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds default Ranger configuration maps for the Trino plugin, one Map per {@link RangerConfigKind}.
 * <p>
 * These defaults are intentionally conservative:
 * <ul>
 *   <li>Audit enabled only for log4j (file) by default; Solr/HDFS/DB disabled.</li>
 *   <li>Policy Manager SSL truststore path present but with placeholder password.</li>
 *   <li>Kerberos UGI keys present but off by default to avoid breaking non-Kerberos clusters.</li>
 * </ul>
 * <p>
 * You can override any key after obtaining these maps (see {@link RangerOverrides}).
 */
public final class RangerTrinoConfigDefaults {

    private RangerTrinoConfigDefaults() {}

    /**
     * Build all four config maps for the given service name (usually "trino").
     *
     * @param serviceName lower-case service name used in property prefixes, e.g. "trino"
     * @return kind -> (propertyName -> value)
     */
    public static EnumMap<RangerConfigKind, Map<String, String>> buildDefaultRangerPluginConfig(String serviceName) {
        EnumMap<RangerConfigKind, Map<String, String>> rangerConfigKindMapEnumMap = new EnumMap<>(RangerConfigKind.class);
        rangerConfigKindMapEnumMap.put(RangerConfigKind.PLUGIN_PROPERTIES, pluginProperties(serviceName));
        rangerConfigKindMapEnumMap.put(RangerConfigKind.POLICYMGR_SSL, policymgrSsl());
        rangerConfigKindMapEnumMap.put(RangerConfigKind.SECURITY, security(serviceName));
        rangerConfigKindMapEnumMap.put(RangerConfigKind.AUDIT, audit());
        return rangerConfigKindMapEnumMap;
    }

    /**
     * Core plugin settings: REST URL, service name, enable flags, polling and cache directory.
     * @param serviceName usually "trino"
     */
    public static Map<String, String> pluginProperties(String serviceName) {
        String propPrefix = "ranger.plugin." + serviceName + ".";
        Map<String, String> rangerPluginProperties = new LinkedHashMap<>();
        rangerPluginProperties.put(propPrefix + "enabled", "true");
        rangerPluginProperties.put(propPrefix + "service.name", serviceName);                       // can be overridden per-cluster
        rangerPluginProperties.put(propPrefix + "policy.rest.url", "");                             // override from Ambari: admin-properties/policymgr_external_url
        rangerPluginProperties.put(propPrefix + "policy.source.impl", "org.apache.ranger.admin.client.RangerAdminRESTClient");
        rangerPluginProperties.put(propPrefix + "policy.pollIntervalMs", "30000");
        rangerPluginProperties.put(propPrefix + "policy.cache.dir", "/var/lib/ranger/" + serviceName + "/policycache");
        // Optional: small timeouts sane defaults
        rangerPluginProperties.put(propPrefix + "policy.rest.client.connection.timeout.ms", "5000");
        rangerPluginProperties.put(propPrefix + "policy.rest.client.read.timeout.ms", "10000");
        return rangerPluginProperties;
    }

    /**
     * TLS settings used by the plugin to reach Ranger Admin over HTTPS.
     * Defaults assume you mount the truststore at /etc/trino/ranger/ssl/.
     */
    public static Map<String, String> policymgrSsl() {
        Map<String, String> rangerPolicyManagerSSLProperties = new LinkedHashMap<>();
        rangerPolicyManagerSSLProperties.put("xasecure.policymgr.clientssl.truststore", "/etc/trino/ranger/ssl/truststore.jks");
        rangerPolicyManagerSSLProperties.put("xasecure.policymgr.clientssl.truststore.password", "changeit"); // override from Secret
        rangerPolicyManagerSSLProperties.put("xasecure.policymgr.clientssl.truststore.type", "pkcs12");

        // Keystore is optional; only needed if you do client mTLS to Ranger Admin
        rangerPolicyManagerSSLProperties.put("xasecure.policymgr.clientssl.keystore", "");
        rangerPolicyManagerSSLProperties.put("xasecure.policymgr.clientssl.keystore.password", "");
        rangerPolicyManagerSSLProperties.put("xasecure.policymgr.clientssl.keystore.type", "jks");

        // Reasonable protocol/ciphers defaults; override if your Ranger Admin is stricter
        rangerPolicyManagerSSLProperties.put("xasecure.policymgr.clientssl.protocol", "TLSv1.2");
        rangerPolicyManagerSSLProperties.put("xasecure.policymgr.clientssl.ciphersuites", "");
        return rangerPolicyManagerSSLProperties;
    }

    /**
     * Kerberos/UGI related knobs for the plugin process. Defaults keep UGI disabled
     * unless you flip {@code ranger.plugin.trino.ugi.initialize=true}.
     * @param serviceName usually "trino"
     */
    public static Map<String, String> security(String serviceName) {
        String p = "ranger.plugin." + serviceName + ".";
        Map<String, String> rangerPluginSecurityProperties = new LinkedHashMap<>();
        rangerPluginSecurityProperties.put(p + "ugi.initialize", "false");                 // set true when Kerberos is enabled
        rangerPluginSecurityProperties.put(p + "ugi.login.type", "keytab");                // keytab | password (keytab recommended)
        rangerPluginSecurityProperties.put(p + "ugi.keytab.file", "/etc/security/keytabs/service.keytab");
        rangerPluginSecurityProperties.put(p + "ugi.keytab.principal", "");                // override with principal
        // Optional helpers when you mount Hadoop conf for audits
        rangerPluginSecurityProperties.put(p + "hadoop.conf.dir", "/etc/hadoop/conf");
        return rangerPluginSecurityProperties;
    }

    /**
     * Basic audit defaults: enable log4j/file audit only.
     * Turn on Solr/HDFS/DB here when your cluster supports it.
     */
    public static Map<String, String> audit() {
        Map<String, String> rangerPluginAuditProperties = new LinkedHashMap<>();
        rangerPluginAuditProperties.put("xasecure.audit.is.enabled", "true");
        rangerPluginAuditProperties.put("xasecure.audit.log4j.is.enabled", "true");

        // Solr off by default
        rangerPluginAuditProperties.put("xasecure.audit.solr.is.enabled", "false");
        rangerPluginAuditProperties.put("xasecure.audit.solr.zookeepers", "");
        rangerPluginAuditProperties.put("xasecure.audit.solr.collection.name", "ranger_audits"); // typical default

        // HDFS off by default
        rangerPluginAuditProperties.put("xasecure.audit.hdfs.is.enabled", "false");
        rangerPluginAuditProperties.put("xasecure.audit.hdfs.dir", "/ranger/audit");

        // DB off by default
        rangerPluginAuditProperties.put("xasecure.audit.destination.db", "false");
        return rangerPluginAuditProperties;
    }
}
