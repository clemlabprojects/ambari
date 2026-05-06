/*
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
package org.apache.ambari.server.security.authentication.jwt;

import static org.apache.ambari.server.configuration.AmbariServerConfigurationKey.SSO_AUTHENTICATION_ENABLED;
import static org.apache.ambari.server.configuration.AmbariServerConfigurationKey.SSO_JWT_AUDIENCES;
import static org.apache.ambari.server.configuration.AmbariServerConfigurationKey.SSO_JWT_COOKIE_NAME;
import static org.apache.ambari.server.configuration.AmbariServerConfigurationKey.SSO_JWT_USERNAME_CLAIM;
import static org.apache.ambari.server.configuration.AmbariServerConfigurationKey.SSO_OIDC_AUTHENTICATION_ENABLED;
import static org.apache.ambari.server.configuration.AmbariServerConfigurationKey.SSO_OIDC_CALLBACK_URL;
import static org.apache.ambari.server.configuration.AmbariServerConfigurationKey.SSO_OIDC_CLIENT_ID;
import static org.apache.ambari.server.configuration.AmbariServerConfigurationKey.SSO_OIDC_CLIENT_SECRET;
import static org.apache.ambari.server.configuration.AmbariServerConfigurationKey.SSO_OIDC_ENABLED_SERVICES;
import static org.apache.ambari.server.configuration.AmbariServerConfigurationKey.SSO_OIDC_MANAGE_SERVICES;
import static org.apache.ambari.server.configuration.AmbariServerConfigurationKey.SSO_OIDC_PROVIDER_CERTIFICATE;
import static org.apache.ambari.server.configuration.AmbariServerConfigurationKey.SSO_OIDC_PROVIDER_URL;
import static org.apache.ambari.server.configuration.AmbariServerConfigurationKey.SSO_PROVIDER_CERTIFICATE;
import static org.apache.ambari.server.configuration.AmbariServerConfigurationKey.SSO_PROVIDER_ORIGINAL_URL_PARAM_NAME;
import static org.apache.ambari.server.configuration.AmbariServerConfigurationKey.SSO_PROVIDER_URL;

import java.io.UnsupportedEncodingException;
import java.security.cert.CertificateException;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.ambari.server.configuration.AmbariServerConfiguration;
import org.apache.ambari.server.security.encryption.CertificateUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class describes parameters of external JWT authentication provider
 */
public class JwtAuthenticationProperties extends AmbariServerConfiguration {
  private static final Logger LOG = LoggerFactory.getLogger(JwtAuthenticationPropertiesProvider.class);

  private static final String PEM_CERTIFICATE_HEADER = "-----BEGIN CERTIFICATE-----";
  private static final String PEM_CERTIFICATE_FOOTER = "-----END CERTIFICATE-----";

  private String authenticationProviderUrl = null;
  private RSAPublicKey publicKey = null;
  private List<String> audiences = null;
  private String cookieName = "hadoop-jwt";
  private String originalUrlQueryParam = null;
  private boolean enabledForAmbari;

  /**
   * JWT claim used to derive the Ambari username.  Empty string means: try {@code preferred_username}
   * first, fall back to {@code sub}.  Set to {@code sub} to force Knox SSO legacy behavior.
   */
  private String jwtUsernameClaim = "";

  /** OIDC client ID used by the server-side callback flow — empty string means callback flow is disabled. */
  private String oidcClientId = "";

  /** OIDC client secret for the server-side token exchange POST — never sent to the browser. */
  private String oidcClientSecret = "";

  /**
   * Absolute callback URL registered in Keycloak as the {@code redirect_uri} for the Ambari
   * OIDC client.  Must match the load-balancer public URL when Ambari is behind a reverse proxy
   * (e.g. {@code https://ambari.corp.example.com:8442/oidc/callback}).
   */
  private String oidcCallbackUrl = "";

  /**
   * Dedicated Keycloak authorization endpoint URL for the OIDC browser flow.  When non-empty,
   * takes precedence over {@link #authenticationProviderUrl} for OIDC redirect and token-exchange,
   * allowing Knox SSO ({@code provider.url}) and OIDC SSO ({@code oidc.providerUrl}) to coexist.
   */
  private String oidcProviderUrl = "";

  /**
   * Public key from the Keycloak realm signing certificate, used to verify OIDC-issued JWT
   * signatures.  When set, tokens signed by either this key or {@link #publicKey} (Knox cert) are
   * accepted, enabling mixed Knox-SSO / OIDC-SSO deployments.
   */
  private RSAPublicKey oidcPublicKey = null;

  /**
   * Explicit on/off flag for the OIDC browser-flow authentication.  Empty string means: derive
   * from {@link #isOidcClientConfigured()} for backward compatibility with deployments that set
   * only {@code clientId}/{@code clientSecret} without the explicit flag.
   */
  private String oidcAuthenticationEnabledFlag = "";

  /** Whether Ambari should push OIDC-based SSO configuration to cluster services. */
  private boolean oidcManageServices = false;

  /** Services to configure with OIDC SSO; comma-delimited, {@code *} = all.  {@code null} means none. */
  private String oidcEnabledServices = null;

  JwtAuthenticationProperties(Map<String, String> configurationMap) {
    setEnabledForAmbari(Boolean.valueOf(getValue(SSO_AUTHENTICATION_ENABLED, configurationMap)));
    setAudiencesString(getValue(SSO_JWT_AUDIENCES, configurationMap));
    setAuthenticationProviderUrl(getValue(SSO_PROVIDER_URL, configurationMap));
    setCookieName(getValue(SSO_JWT_COOKIE_NAME, configurationMap));
    setOriginalUrlQueryParam(getValue(SSO_PROVIDER_ORIGINAL_URL_PARAM_NAME, configurationMap));
    setPublicKey(getValue(SSO_PROVIDER_CERTIFICATE, configurationMap));
    setJwtUsernameClaim(getValue(SSO_JWT_USERNAME_CLAIM, configurationMap));
    setOidcClientId(getValue(SSO_OIDC_CLIENT_ID, configurationMap));
    setOidcClientSecret(getValue(SSO_OIDC_CLIENT_SECRET, configurationMap));
    setOidcCallbackUrl(getValue(SSO_OIDC_CALLBACK_URL, configurationMap));
    setOidcProviderUrl(getValue(SSO_OIDC_PROVIDER_URL, configurationMap));
    setOidcPublicKey(getValue(SSO_OIDC_PROVIDER_CERTIFICATE, configurationMap));
    setOidcAuthenticationEnabledFlag(getValue(SSO_OIDC_AUTHENTICATION_ENABLED, configurationMap));
    setOidcManageServices(Boolean.parseBoolean(getValue(SSO_OIDC_MANAGE_SERVICES, configurationMap)));
    setOidcEnabledServices(getValue(SSO_OIDC_ENABLED_SERVICES, configurationMap));
  }

  public String getAuthenticationProviderUrl() {
    return authenticationProviderUrl;
  }

  public void setAuthenticationProviderUrl(String authenticationProviderUrl) {
    this.authenticationProviderUrl = authenticationProviderUrl;
  }

  public RSAPublicKey getPublicKey() {
    return publicKey;
  }

  public void setPublicKey(String publicKey) {
    setPublicKey(createPublicKey(publicKey));
  }

  public void setPublicKey(RSAPublicKey publicKey) {
    this.publicKey = publicKey;
  }

  public List<String> getAudiences() {
    return audiences;
  }

  public void setAudiences(List<String> audiences) {
    this.audiences = audiences;
  }

  public void setAudiencesString(String audiencesString) {
    if (StringUtils.isNotEmpty(audiencesString)) {
      // parse into the list
      String[] audArray = audiencesString.split(",");
      audiences = new ArrayList<>();
      Collections.addAll(audiences, audArray);
    } else {
      audiences = null;
    }
  }

  public String getCookieName() {
    return cookieName;
  }

  public void setCookieName(String cookieName) {
    this.cookieName = cookieName;
  }

  public String getOriginalUrlQueryParam() {
    return originalUrlQueryParam;
  }

  public void setOriginalUrlQueryParam(String originalUrlQueryParam) {
    this.originalUrlQueryParam = originalUrlQueryParam;
  }

  public boolean isEnabledForAmbari() {
    return enabledForAmbari;
  }

  public void setEnabledForAmbari(boolean enabledForAmbari) {
    this.enabledForAmbari = enabledForAmbari;
  }

  public String getJwtUsernameClaim() {
    return jwtUsernameClaim;
  }

  public void setJwtUsernameClaim(String jwtUsernameClaim) {
    this.jwtUsernameClaim = (jwtUsernameClaim == null) ? "" : jwtUsernameClaim.trim();
  }

  public String getOidcClientId() {
    return oidcClientId;
  }

  public void setOidcClientId(String oidcClientId) {
    this.oidcClientId = (oidcClientId == null) ? "" : oidcClientId.trim();
  }

  public String getOidcClientSecret() {
    return oidcClientSecret;
  }

  public void setOidcClientSecret(String oidcClientSecret) {
    this.oidcClientSecret = (oidcClientSecret == null) ? "" : oidcClientSecret;
  }

  public String getOidcCallbackUrl() {
    return oidcCallbackUrl;
  }

  public void setOidcCallbackUrl(String oidcCallbackUrl) {
    this.oidcCallbackUrl = (oidcCallbackUrl == null) ? "" : oidcCallbackUrl.trim();
  }

  /**
   * Returns {@code true} when both {@link #oidcClientId} and {@link #oidcClientSecret} are
   * non-empty, indicating that the server-side OIDC callback flow is fully configured.
   *
   * @return {@code true} if the callback flow should be activated
   */
  public boolean isOidcClientConfigured() {
    return !oidcClientId.isEmpty() && !oidcClientSecret.isEmpty();
  }

  /**
   * Returns whether the OIDC browser-flow authentication is active for Ambari login.
   *
   * <p>Resolution order:
   * <ol>
   *   <li>If {@code ambari.sso.oidc.authentication.enabled} is explicitly {@code "true"} or
   *       {@code "false"}, that value is returned.</li>
   *   <li>Otherwise, falls back to {@link #isOidcClientConfigured()} for backward compatibility
   *       with deployments that only set {@code clientId}/{@code clientSecret}.</li>
   * </ol>
   */
  public boolean isOidcEnabledForAmbari() {
    if (!oidcAuthenticationEnabledFlag.isEmpty()) {
      return Boolean.parseBoolean(oidcAuthenticationEnabledFlag);
    }
    return isOidcClientConfigured();
  }

  public String getOidcProviderUrl() {
    return oidcProviderUrl;
  }

  public void setOidcProviderUrl(String oidcProviderUrl) {
    this.oidcProviderUrl = (oidcProviderUrl == null) ? "" : oidcProviderUrl.trim();
  }

  /**
   * Returns the effective OIDC authorization endpoint URL.  When {@link #oidcProviderUrl} is set,
   * it is returned so that the OIDC flow uses its own dedicated Keycloak URL independently of the
   * Knox SSO {@code provider.url}.  Falls back to {@link #authenticationProviderUrl} for
   * backward compatibility with single-path deployments.
   */
  public String getEffectiveOidcProviderUrl() {
    return StringUtils.isNotEmpty(oidcProviderUrl) ? oidcProviderUrl : authenticationProviderUrl;
  }

  public RSAPublicKey getOidcPublicKey() {
    return oidcPublicKey;
  }

  public void setOidcPublicKey(String certificate) {
    this.oidcPublicKey = createPublicKey(certificate);
  }

  public void setOidcPublicKey(RSAPublicKey oidcPublicKey) {
    this.oidcPublicKey = oidcPublicKey;
  }

  public String getOidcAuthenticationEnabledFlag() {
    return oidcAuthenticationEnabledFlag;
  }

  public void setOidcAuthenticationEnabledFlag(String flag) {
    this.oidcAuthenticationEnabledFlag = (flag == null) ? "" : flag.trim();
  }

  public boolean isOidcManageServices() {
    return oidcManageServices;
  }

  public void setOidcManageServices(boolean oidcManageServices) {
    this.oidcManageServices = oidcManageServices;
  }

  public String getOidcEnabledServices() {
    return oidcEnabledServices;
  }

  public void setOidcEnabledServices(String oidcEnabledServices) {
    this.oidcEnabledServices = (oidcEnabledServices == null || oidcEnabledServices.trim().isEmpty())
        ? null : oidcEnabledServices.trim();
  }

  /**
   * Derives the Keycloak token endpoint from the effective OIDC provider URL by replacing the
   * trailing {@code /auth} path segment with {@code /token}.  Uses {@link #getEffectiveOidcProviderUrl()}
   * so that a dedicated {@code oidc.providerUrl} takes precedence over the Knox {@code provider.url}.
   * <pre>
   *   authorization: .../realms/{realm}/protocol/openid-connect/auth
   *   token:         .../realms/{realm}/protocol/openid-connect/token
   * </pre>
   *
   * @return the Keycloak token endpoint URL; never {@code null}
   */
  public String getOidcTokenEndpoint() {
    String baseUrl = getEffectiveOidcProviderUrl();
    if (baseUrl == null || baseUrl.isEmpty()) {
      return "";
    }
    if (baseUrl.endsWith("/auth")) {
      return baseUrl.substring(0, baseUrl.length() - 5) + "/token";
    }
    return baseUrl + "/token";
  }

  /**
   * Given a String containing PEM-encode x509 certificate, an {@link RSAPublicKey} is created and
   * returned.
   * <p>
   * If the certificate data is does not contain the proper PEM-encoded x509 digital certificate
   * header and footer, they will be added.
   *
   * @param certificate a PEM-encode x509 certificate
   * @return an {@link RSAPublicKey}
   */
  private RSAPublicKey createPublicKey(String certificate) {
    RSAPublicKey publicKey = null;
    if (certificate != null) {
      certificate = certificate.trim();
    }
    if (!StringUtils.isEmpty(certificate)) {
      // Ensure the PEM data is properly formatted
      if (!certificate.startsWith(PEM_CERTIFICATE_HEADER)) {
        certificate = PEM_CERTIFICATE_HEADER + "\n" + certificate;
      }
      if (!certificate.endsWith(PEM_CERTIFICATE_FOOTER)) {
        certificate = certificate + "\n" + PEM_CERTIFICATE_FOOTER;
      }

      try {
        publicKey = CertificateUtils.getPublicKeyFromString(certificate);
      } catch (CertificateException | UnsupportedEncodingException e) {
        LOG.error("Unable to parse public certificate file. JTW authentication will fail.", e);
      }
    }

    return publicKey;
  }
}
