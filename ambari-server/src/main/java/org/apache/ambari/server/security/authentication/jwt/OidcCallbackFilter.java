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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.ambari.server.security.authentication.AmbariAuthenticationEventHandler;
import org.apache.ambari.server.security.authentication.AmbariAuthenticationFilter;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * OidcCallbackFilter implements the server-side OIDC authorization-code flow for Ambari JWT SSO.
 *
 * <p>It serves two responsibilities within a single filter, differentiated by the incoming path:
 *
 * <ol>
 *   <li><b>Redirect</b> — when JWT SSO is enabled, the OIDC client is configured, and an
 *       unauthenticated browser GET request arrives without a valid JWT cookie, this filter
 *       issues a {@code 302} redirect to the Keycloak authorization endpoint.  Non-browser
 *       requests (REST API, AJAX, non-GET methods) fall through to the next filter and receive
 *       a {@code 401} as before.</li>
 *   <li><b>Callback</b> — when the browser returns to {@value #CALLBACK_PATH} with the
 *       authorization {@code code} and {@code state} parameters, this filter validates the
 *       {@code state} HMAC, exchanges the authorization code for an access token via a
 *       server-to-server POST to the Keycloak token endpoint, and writes the token as an
 *       {@code HttpOnly; Secure; SameSite=Lax} cookie.  The browser is then redirected to the
 *       original URL that was encoded in the {@code state} payload.</li>
 * </ol>
 *
 * <h3>State parameter design</h3>
 * The CSRF {@code state} value is a compact, self-contained, HMAC-signed token with the form:
 * <pre>
 *   state  = BASE64URL(payload) + "." + BASE64URL(HMAC-SHA256(payload, signingKey))
 *   payload = UTF-8 JSON: {"nonce":"&lt;32-byte hex&gt;","url":"&lt;original path+query&gt;","exp":&lt;epoch-seconds&gt;}
 * </pre>
 * The signing key is derived deterministically from the OIDC client secret:
 * <pre>
 *   signingKey = HMAC-SHA256(clientSecret.getBytes(UTF-8), {@value #STATE_KEY_CONTEXT})
 * </pre>
 * Because the client secret is the same value in {@code ambari.properties} on every Ambari node,
 * the {@code state} token produced on one node can be verified on any other node — no shared
 * session storage or sticky load-balancer affinity is required.
 *
 * <h3>HttpOnly cookie</h3>
 * The {@code hadoop-jwt} cookie is emitted via a manually constructed {@code Set-Cookie} header
 * rather than {@link javax.servlet.http.Cookie} because the Servlet 3.1 API does not expose a
 * {@code SameSite} setter.  The header always carries {@code HttpOnly; Secure; SameSite=Lax}.
 *
 * <h3>Open-redirect prevention</h3>
 * The original URL embedded in the {@code state} payload is restricted to path-and-query form
 * (it must start with {@code /} and must not contain a scheme or authority component).  On
 * redirect the scheme, host, and port are taken from the current request, not the payload.
 *
 * <h3>Required configuration</h3>
 * The filter is inactive (falls through) unless both of the following Ambari properties are set:
 * <ul>
 *   <li>{@code ambari.sso.oidc.clientId} — OIDC client ID registered in Keycloak</li>
 *   <li>{@code ambari.sso.oidc.clientSecret} — corresponding client secret (stored encrypted)</li>
 * </ul>
 * {@code ambari.sso.oidc.callbackUrl} must be set to the load-balancer public URL when Ambari
 * runs behind a reverse proxy; otherwise the callback URL is derived automatically from the
 * incoming request.
 *
 * @see AmbariJwtAuthenticationFilter
 * @see JwtAuthenticationProperties
 */
@Component
@Order(0)
public class OidcCallbackFilter implements AmbariAuthenticationFilter {

  private static final Logger LOG = LoggerFactory.getLogger(OidcCallbackFilter.class);

  /** Servlet path that Keycloak redirects back to after user authentication. */
  public static final String CALLBACK_PATH = "/oidc/callback";

  /**
   * HMAC context string used when deriving the state-signing key from the client secret.
   * Changing this value invalidates all in-flight state tokens.
   */
  static final String STATE_KEY_CONTEXT = "ambari-oidc-state-v1";

  /** Maximum lifetime of a state token in seconds.  Covers the Keycloak login interaction. */
  static final long STATE_TTL_SECONDS = 300L;

  /** Jackson mapper reused across calls — thread-safe after construction. */
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final JwtAuthenticationPropertiesProvider propertiesProvider;
  private final AmbariAuthenticationEventHandler eventHandler;
  private final AuthenticationEntryPoint entryPoint;

  OidcCallbackFilter(JwtAuthenticationPropertiesProvider propertiesProvider,
                     AmbariAuthenticationEventHandler eventHandler,
                     AuthenticationEntryPoint entryPoint) {
    this.propertiesProvider = propertiesProvider;
    this.eventHandler       = eventHandler;
    this.entryPoint         = entryPoint;
  }

  // ── AmbariAuthenticationFilter ────────────────────────────────────────────

  /**
   * Returns {@code false}: OIDC is a remote authentication source so a failed
   * token exchange or state validation should not count towards the consecutive
   * local-login failure counter.
   */
  @Override
  public boolean shouldIncrementFailureCount() {
    return false;
  }

  /**
   * Decides whether this filter should claim the current request.
   *
   * <p>Returns {@code true} when all of the following are satisfied:
   * <ol>
   *   <li>JWT SSO is enabled ({@code ambari.sso.authentication.enabled=true}).</li>
   *   <li>The OIDC client is configured (both {@code clientId} and {@code clientSecret} are set).</li>
   *   <li>Either: the request path is exactly {@value #CALLBACK_PATH}, or the request is a
   *       browser GET that does not carry a valid JWT cookie.</li>
   * </ol>
   *
   * @param httpServletRequest the current HTTP request
   * @return {@code true} if this filter should handle the request
   */
  @Override
  public boolean shouldApply(HttpServletRequest httpServletRequest) {
    JwtAuthenticationProperties props = propertiesProvider.get();
    if (props == null || !props.isEnabledForAmbari() || !props.isOidcClientConfigured()) {
      return false;
    }
    if (CALLBACK_PATH.equals(httpServletRequest.getServletPath())) {
      return true;
    }
    return isBrowserGetRequest(httpServletRequest) && !hasJwtCookie(httpServletRequest, props);
  }

  /**
   * Handles the request by dispatching to either the callback handler or the Keycloak redirect.
   * This method always commits the response — it never passes the request to the downstream chain.
   *
   * @param servletRequest  the current request
   * @param servletResponse the current response
   * @param chain           the filter chain (never invoked by this filter)
   * @throws IOException      if a network or I/O error occurs
   * @throws ServletException if a servlet error occurs
   */
  @Override
  public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                       FilterChain chain) throws IOException, ServletException {
    HttpServletRequest  request  = (HttpServletRequest)  servletRequest;
    HttpServletResponse response = (HttpServletResponse) servletResponse;
    JwtAuthenticationProperties props = propertiesProvider.get();

    if (CALLBACK_PATH.equals(request.getServletPath())) {
      handleOidcCallback(request, response, props);
    } else {
      redirectToKeycloak(request, response, props);
    }
  }

  @Override
  public void init(FilterConfig filterConfig) {
    // no initialisation required
  }

  @Override
  public void destroy() {
    // no resources to release
  }

  // ── Redirect leg ──────────────────────────────────────────────────────────

  /**
   * Issues a {@code 302} redirect to the Keycloak authorization endpoint, embedding a
   * HMAC-signed {@code state} token that encodes the original request path.
   *
   * @param request  the unauthenticated browser request
   * @param response the response to write the redirect into
   * @param props    the current JWT/OIDC configuration
   * @throws IOException if the redirect cannot be written
   */
  private void redirectToKeycloak(HttpServletRequest request, HttpServletResponse response,
                                   JwtAuthenticationProperties props) throws IOException {
    String originalPath = buildOriginalPath(request);
    String state;
    try {
      state = buildState(originalPath, props.getOidcClientSecret());
    } catch (OidcStateException e) {
      LOG.error("Failed to build OIDC state token", e);
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "SSO state generation failed");
      return;
    }

    String callbackUrl  = resolveCallbackUrl(request, props);
    String authEndpoint = props.getAuthenticationProviderUrl();

    String redirectUrl = authEndpoint
        + "?response_type=code"
        + "&client_id="    + urlEncode(props.getOidcClientId())
        + "&redirect_uri=" + urlEncode(callbackUrl)
        + "&scope=openid"
        + "&state="        + urlEncode(state);

    LOG.debug("Redirecting unauthenticated browser to Keycloak: {}", authEndpoint);
    response.sendRedirect(redirectUrl);
  }

  // ── Callback leg ──────────────────────────────────────────────────────────

  /**
   * Handles the Keycloak authorization callback: validates the {@code state} HMAC, exchanges
   * the authorization code for a JWT, writes the token as an {@code HttpOnly} cookie, and
   * redirects the browser to the original URL.
   *
   * @param request  the callback request carrying {@code code} and {@code state}
   * @param response the response to write the cookie and redirect into
   * @param props    the current JWT/OIDC configuration
   * @throws IOException if the response cannot be written
   */
  private void handleOidcCallback(HttpServletRequest request, HttpServletResponse response,
                                   JwtAuthenticationProperties props) throws IOException {
    String code  = request.getParameter("code");
    String state = request.getParameter("state");

    if (StringUtils.isEmpty(code) || StringUtils.isEmpty(state)) {
      LOG.warn("OIDC callback missing 'code' or 'state' parameter");
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing OIDC callback parameters");
      return;
    }

    String originalPath;
    try {
      originalPath = validateStateAndExtractOriginalPath(state, props.getOidcClientSecret());
    } catch (OidcStateException e) {
      LOG.warn("OIDC state validation failed: {}", e.getMessage());
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid or expired SSO state");
      return;
    }

    TokenResponse tokenResponse;
    try {
      String callbackUrl = resolveCallbackUrl(request, props);
      tokenResponse = exchangeCodeForToken(code, callbackUrl, props);
    } catch (IOException e) {
      LOG.error("Token exchange with Keycloak failed", e);
      response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Token exchange failed");
      return;
    }

    writeJwtCookie(response, props.getCookieName(), tokenResponse.accessToken,
        tokenResponse.expiresIn);

    String redirectTarget = buildRedirectTarget(request, originalPath);
    LOG.debug("OIDC callback succeeded; redirecting to {}", redirectTarget);
    response.sendRedirect(redirectTarget);
  }

  // ── State token ───────────────────────────────────────────────────────────

  /**
   * Builds a HMAC-signed state token encoding the original request path and an expiry timestamp.
   *
   * <p>Format: {@code BASE64URL(payload) + "." + BASE64URL(HMAC-SHA256(payload, signingKey))}
   * where {@code payload} is the UTF-8 JSON bytes of
   * {@code {"nonce":"<hex>","url":"<path>","exp":<epoch>}}.
   *
   * @param originalPath   the request path to embed in the token
   * @param clientSecret   the OIDC client secret used to derive the signing key
   * @return the opaque state string safe to include in a redirect URL
   * @throws OidcStateException if HMAC computation fails
   */
  String buildState(String originalPath, String clientSecret) throws OidcStateException {
    byte[] nonceBytes = new byte[16];
    SECURE_RANDOM.nextBytes(nonceBytes);
    String nonce = bytesToHex(nonceBytes);

    long exp = System.currentTimeMillis() / 1000L + STATE_TTL_SECONDS;

    String payloadJson = "{\"nonce\":\"" + nonce + "\","
        + "\"url\":\"" + jsonEscape(originalPath) + "\","
        + "\"exp\":" + exp + "}";

    byte[] payloadBytes = payloadJson.getBytes(StandardCharsets.UTF_8);
    String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes);

    byte[] signingKey = deriveStateSigningKey(clientSecret);
    byte[] hmac = computeHmac(payloadBytes, signingKey);
    String encodedHmac = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);

    return encodedPayload + "." + encodedHmac;
  }

  /**
   * Validates the HMAC on the state token and, if valid and unexpired, returns the original
   * request path embedded in the payload.
   *
   * @param state        the state string received from Keycloak
   * @param clientSecret the OIDC client secret used to derive the signing key
   * @return the original request path
   * @throws OidcStateException if the HMAC is invalid, the token is expired, or the URL is unsafe
   */
  String validateStateAndExtractOriginalPath(String state, String clientSecret)
      throws OidcStateException {
    int dot = state.lastIndexOf('.');
    if (dot < 1) {
      throw new OidcStateException("Malformed state token: missing dot separator");
    }

    String encodedPayload = state.substring(0, dot);
    String encodedHmac    = state.substring(dot + 1);

    byte[] payloadBytes;
    byte[] receivedHmac;
    try {
      payloadBytes = Base64.getUrlDecoder().decode(encodedPayload);
      receivedHmac = Base64.getUrlDecoder().decode(encodedHmac);
    } catch (IllegalArgumentException e) {
      throw new OidcStateException("Malformed state token: bad base64 encoding");
    }

    byte[] signingKey    = deriveStateSigningKey(clientSecret);
    byte[] expectedHmac  = computeHmac(payloadBytes, signingKey);

    if (!MessageDigest.isEqual(expectedHmac, receivedHmac)) {
      throw new OidcStateException("State HMAC verification failed");
    }

    JsonNode payload;
    try {
      payload = OBJECT_MAPPER.readTree(payloadBytes);
    } catch (IOException e) {
      throw new OidcStateException("State payload is not valid JSON");
    }

    long exp = payload.path("exp").asLong(0L);
    if (exp == 0L || System.currentTimeMillis() / 1000L > exp) {
      throw new OidcStateException("State token has expired");
    }

    String originalUrl = payload.path("url").asText("");
    if (!originalUrl.startsWith("/")) {
      throw new OidcStateException("State url is not a safe relative path: " + originalUrl);
    }

    return originalUrl;
  }

  // ── Token exchange ────────────────────────────────────────────────────────

  /**
   * Performs the server-side authorization-code token exchange via a POST to the Keycloak
   * token endpoint.  Uses {@link HttpURLConnection} to avoid additional dependencies.
   *
   * @param code        the authorization code received from Keycloak
   * @param callbackUrl the {@code redirect_uri} value — must exactly match the one used in
   *                    the initial authorization request
   * @param props       OIDC configuration properties
   * @return a {@link TokenResponse} containing the access token and its lifetime
   * @throws IOException if the token endpoint is unreachable or returns an error
   */
  private TokenResponse exchangeCodeForToken(String code, String callbackUrl,
                                              JwtAuthenticationProperties props) throws IOException {
    String tokenEndpoint = props.getOidcTokenEndpoint();
    String requestBody = "grant_type=authorization_code"
        + "&code="          + urlEncode(code)
        + "&redirect_uri="  + urlEncode(callbackUrl)
        + "&client_id="     + urlEncode(props.getOidcClientId())
        + "&client_secret=" + urlEncode(props.getOidcClientSecret());

    byte[] requestBodyBytes = requestBody.getBytes(StandardCharsets.UTF_8);

    HttpURLConnection conn = (HttpURLConnection) new URL(tokenEndpoint).openConnection();
    conn.setDoOutput(true);
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    conn.setRequestProperty("Content-Length", String.valueOf(requestBodyBytes.length));
    conn.setConnectTimeout(10_000);
    conn.setReadTimeout(15_000);

    try (OutputStream out = conn.getOutputStream()) {
      out.write(requestBodyBytes);
    }

    int statusCode = conn.getResponseCode();
    InputStream responseStream = (statusCode < 400)
        ? conn.getInputStream()
        : conn.getErrorStream();

    String responseBody;
    try (InputStream is = responseStream) {
      responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    if (statusCode != HttpURLConnection.HTTP_OK) {
      throw new IOException("Keycloak token endpoint returned HTTP " + statusCode
          + ": " + responseBody);
    }

    JsonNode json = OBJECT_MAPPER.readTree(responseBody);
    String accessToken = json.path("access_token").asText(null);
    if (StringUtils.isEmpty(accessToken)) {
      throw new IOException("Keycloak token response did not contain access_token");
    }

    int expiresIn = json.path("expires_in").asInt(3600);
    return new TokenResponse(accessToken, expiresIn);
  }

  // ── Cookie writing ────────────────────────────────────────────────────────

  /**
   * Writes the JWT access token as an {@code HttpOnly; Secure; SameSite=Lax} cookie.
   *
   * <p>The {@link javax.servlet.http.Cookie} API does not expose a {@code SameSite} setter in
   * Servlet 3.1, so the {@code Set-Cookie} header is constructed manually.
   *
   * @param response   the HTTP response to write the cookie header to
   * @param cookieName the name of the JWT cookie (e.g. {@code hadoop-jwt})
   * @param token      the JWT access token value
   * @param maxAge     the cookie lifetime in seconds, as reported by the token endpoint
   */
  private void writeJwtCookie(HttpServletResponse response, String cookieName,
                               String token, int maxAge) {
    String cookieHeader = cookieName + "=" + token
        + "; Path=/"
        + "; HttpOnly"
        + "; Secure"
        + "; SameSite=Lax"
        + "; Max-Age=" + maxAge;
    response.addHeader("Set-Cookie", cookieHeader);
  }

  // ── Utility methods ───────────────────────────────────────────────────────

  /**
   * Returns {@code true} when the request looks like a browser navigation (as opposed to an
   * API or AJAX call).  This prevents redirect loops for programmatic clients.
   *
   * <p>A request is classified as a browser GET when all of the following hold:
   * <ul>
   *   <li>The HTTP method is {@code GET}.</li>
   *   <li>The path does not start with {@code /api/} or {@code /views/}.</li>
   *   <li>The {@code X-Requested-With: XMLHttpRequest} header is absent.</li>
   *   <li>The {@code Accept} header contains {@code text/html}.</li>
   * </ul>
   *
   * @param request the HTTP request to classify
   * @return {@code true} if the request originates from a browser navigation
   */
  boolean isBrowserGetRequest(HttpServletRequest request) {
    if (!"GET".equalsIgnoreCase(request.getMethod())) {
      return false;
    }
    String path = request.getServletPath();
    if (path != null && (path.startsWith("/api/") || path.startsWith("/views/"))) {
      return false;
    }
    if ("XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"))) {
      return false;
    }
    String accept = request.getHeader("Accept");
    return accept != null && accept.contains("text/html");
  }

  /**
   * Returns {@code true} if the request carries a non-empty JWT cookie with the name
   * configured in {@code ambari.sso.jwt.cookieName}.
   *
   * @param request the HTTP request to inspect
   * @param props   the current JWT/OIDC configuration
   * @return {@code true} if a JWT cookie is present
   */
  private boolean hasJwtCookie(HttpServletRequest request, JwtAuthenticationProperties props) {
    String cookieName = props.getCookieName();
    if (request.getCookies() == null || StringUtils.isEmpty(cookieName)) {
      return false;
    }
    for (javax.servlet.http.Cookie cookie : request.getCookies()) {
      if (cookieName.equals(cookie.getName()) && !StringUtils.isEmpty(cookie.getValue())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Resolves the {@code redirect_uri} for the current request.
   *
   * <p>When {@code ambari.sso.oidc.callbackUrl} is configured (required for load-balancer
   * deployments), that value is returned unchanged.  Otherwise the callback URL is derived
   * from the incoming request scheme, server name, and port so that single-node deployments
   * work without any explicit configuration.
   *
   * @param request the current HTTP request
   * @param props   OIDC configuration
   * @return the absolute callback URL to register as {@code redirect_uri}
   */
  private String resolveCallbackUrl(HttpServletRequest request, JwtAuthenticationProperties props) {
    String configured = props.getOidcCallbackUrl();
    if (!StringUtils.isEmpty(configured)) {
      return configured;
    }
    StringBuilder sb = new StringBuilder();
    sb.append(request.getScheme()).append("://").append(request.getServerName());
    int port = request.getServerPort();
    if (needsExplicitPort(request.getScheme(), port)) {
      sb.append(":").append(port);
    }
    sb.append(CALLBACK_PATH);
    return sb.toString();
  }

  /**
   * Returns {@code true} when the port must be included in the URL because it differs from
   * the scheme default (80 for HTTP, 443 for HTTPS).
   */
  private boolean needsExplicitPort(String scheme, int port) {
    if ("https".equalsIgnoreCase(scheme) && port == 443) return false;
    if ("http".equalsIgnoreCase(scheme)  && port == 80)  return false;
    return true;
  }

  /**
   * Builds the path-and-query string of the original request for storage in the state token.
   * Only the path and query string are stored — the scheme, host, and port are deliberately
   * omitted to prevent open-redirect exploitation.
   *
   * @param request the original request
   * @return a relative URL starting with {@code /}
   */
  private String buildOriginalPath(HttpServletRequest request) {
    String path = request.getServletPath();
    if (StringUtils.isEmpty(path)) {
      path = "/";
    }
    String query = request.getQueryString();
    return StringUtils.isEmpty(query) ? path : (path + "?" + query);
  }

  /**
   * Reconstructs the absolute redirect target from the current request origin and the
   * relative path extracted from the validated state token.
   *
   * @param request      the current HTTP request (provides scheme, host, port)
   * @param originalPath the relative path extracted from the state token
   * @return the absolute URL to redirect the browser to
   */
  private String buildRedirectTarget(HttpServletRequest request, String originalPath) {
    StringBuilder sb = new StringBuilder();
    sb.append(request.getScheme()).append("://").append(request.getServerName());
    int port = request.getServerPort();
    if (needsExplicitPort(request.getScheme(), port)) {
      sb.append(":").append(port);
    }
    sb.append(originalPath);
    return sb.toString();
  }

  /**
   * Derives the HMAC signing key for state tokens from the OIDC client secret.
   *
   * <p>The derivation is: {@code HMAC-SHA256(STATE_KEY_CONTEXT.getBytes(UTF-8), clientSecret.getBytes(UTF-8))}.
   * Using a fixed context string ensures that the state signing key is domain-separated from
   * the raw client secret even if the same secret is reused for other purposes.  Because the
   * client secret is identical on all Ambari nodes (it is stored in {@code ambari.properties}),
   * the derived key is also identical, enabling cross-node state verification.
   *
   * @param clientSecret the OIDC client secret
   * @return the derived signing key bytes
   * @throws OidcStateException if the underlying HMAC algorithm is unavailable
   */
  private byte[] deriveStateSigningKey(String clientSecret) throws OidcStateException {
    return computeHmac(STATE_KEY_CONTEXT.getBytes(StandardCharsets.UTF_8),
        clientSecret.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Computes {@code HMAC-SHA256(data, key)}.
   *
   * @param data the data to authenticate
   * @param key  the raw key bytes
   * @return the 32-byte HMAC digest
   * @throws OidcStateException if {@code HmacSHA256} is unavailable on this JVM
   */
  private byte[] computeHmac(byte[] data, byte[] key) throws OidcStateException {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new OidcStateException("HMAC-SHA256 computation failed: " + e.getMessage());
    }
  }

  /** URL-encodes a string using UTF-8, delegating to {@link java.net.URLEncoder}. */
  private static String urlEncode(String value) {
    try {
      return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException("UTF-8 not supported", e);
    }
  }

  /** Converts a byte array to a lower-case hex string. */
  private static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  /** Escapes characters that are unsafe inside a JSON string literal. */
  private static String jsonEscape(String value) {
    return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
  }

  // ── Inner types ───────────────────────────────────────────────────────────

  /** Value object carrying the fields extracted from the Keycloak token endpoint response. */
  private static final class TokenResponse {
    final String accessToken;
    final int    expiresIn;

    TokenResponse(String accessToken, int expiresIn) {
      this.accessToken = accessToken;
      this.expiresIn   = expiresIn;
    }
  }

  /** Thrown when state token construction or validation fails for any reason. */
  static final class OidcStateException extends Exception {
    OidcStateException(String message) {
      super(message);
    }
  }
}
