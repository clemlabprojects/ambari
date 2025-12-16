package org.apache.ambari.view.k8s.dto.security;

/**
 * OIDC configuration.
 */
public class OidcConfig {
    public String issuerUrl;
    public String clientId;
    public String clientSecret;
    public String scopes;
    public String redirectUri;
    public String userClaim;
    public String groupsClaim;
    public Boolean skipTlsVerify;
    public String caSecret;
}
