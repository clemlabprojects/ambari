package org.apache.ambari.view.k8s.dto.security;

/**
 * TLS truststore configuration.
 */
public class TlsConfig {
    public String truststoreSecret;
    public String truststorePasswordKey;
    public String truststoreKey;
}
