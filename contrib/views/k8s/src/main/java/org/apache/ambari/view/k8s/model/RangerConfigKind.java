package org.apache.ambari.view.k8s.model;

/**
 * Enumerates Ranger configuration "kinds" used for service plugins.
 * For Trino we commonly create one of each kind.
 */
public enum RangerConfigKind {
    /** Properties controlling audit destinations (log4j / Solr / HDFS / DB). */
    AUDIT,

    /** Core plugin bootstrap properties (REST URL, service name, polling). */
    PLUGIN_PROPERTIES,

    /** TLS settings for the Policy Manager client (truststore/keystore). */
    POLICYMGR_SSL,

    /** Security/UGI settings (e.g., Kerberos keytab login for the plugin). */
    SECURITY
}