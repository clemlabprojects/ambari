package org.apache.ambari.view.k8s.dto.security;

/**
 * Global security configuration profile.
 */
public class SecurityConfigDTO {
    public String mode; // none | ldap | ad | oidc
    public LdapConfig ldap;
    public OidcConfig oidc;
    public TlsConfig tls;
    public java.util.Map<String,Object> extraProperties;
}
