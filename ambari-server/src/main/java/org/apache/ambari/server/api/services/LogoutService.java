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
package org.apache.ambari.server.api.services;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.ambari.annotations.ApiIgnore;
import org.apache.ambari.server.StaticallyInject;
import org.apache.ambari.server.audit.AuditLogger;
import org.apache.ambari.server.audit.event.LogoutAuditEvent;
import org.apache.ambari.server.security.authentication.jwt.AmbariSessionTokenService;
import org.apache.ambari.server.security.authentication.jwt.JwtAuthenticationProperties;
import org.apache.ambari.server.security.authentication.jwt.JwtAuthenticationPropertiesProvider;
import org.apache.ambari.server.security.authorization.AuthorizationHelper;
import org.apache.ambari.server.utils.RequestUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.nimbusds.jwt.SignedJWT;

/**
 * Service performing logout of current user.
 *
 * <p>Three things happen here:
 * <ol>
 *   <li>Clear the Spring SecurityContext and invalidate the HTTP session — local Ambari sign-out.</li>
 *   <li>Delete the {@code hadoop-jwt} cookie (Max-Age=0).  Without this, the next request would
 *       arrive carrying a still-valid JWT and {@code AmbariJwtAuthenticationFilter} would happily
 *       re-authenticate the user.</li>
 *   <li>If OIDC is enabled, return the Keycloak end-session URL in the JSON response so the
 *       browser can perform RP-initiated logout (otherwise Keycloak's session cookie survives
 *       and the next visit silently re-authenticates via SSO).</li>
 * </ol>
 */
@StaticallyInject
@Path("/logout")
public class LogoutService {

  private static final Logger LOG = LoggerFactory.getLogger(LogoutService.class);

  @Inject
  private static AuditLogger auditLogger;

  @Inject
  private static JwtAuthenticationPropertiesProvider jwtPropertiesProvider;

  private static final Gson GSON = new Gson();

  @GET @ApiIgnore // until documented
  @Produces(MediaType.APPLICATION_JSON)
  public Response performLogout(@Context HttpServletRequest servletRequest,
                                 @Context HttpServletResponse servletResponse) {
    auditLog(servletRequest);
    SecurityContextHolder.clearContext();
    servletRequest.getSession().invalidate();

    JwtAuthenticationProperties props = (jwtPropertiesProvider == null) ? null : jwtPropertiesProvider.get();

    // Delete the JWT cookie so a subsequent request doesn't re-auth via the filter.
    // Mirror the attributes used in OidcCallbackFilter#writeJwtCookie.
    if (props != null && StringUtils.isNotEmpty(props.getCookieName())) {
      servletResponse.addHeader("Set-Cookie",
          props.getCookieName() + "=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0");
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "ok");

    String logoutUrl = buildOidcLogoutUrl(props, servletRequest);
    if (StringUtils.isNotEmpty(logoutUrl)) {
      body.put("logout_url", logoutUrl);
    }

    return Response.ok(GSON.toJson(body)).build();
  }

  /**
   * Builds the Keycloak RP-initiated logout URL ({@code end_session_endpoint}) or returns an
   * empty string when the current request did not come in via the OIDC browser flow.
   *
   * <p>The {@code hadoop-jwt} cookie name is shared by three potential token issuers:
   * <ul>
   *   <li><b>OIDC (AMBARI-438)</b> — HS256, {@code iss=ambari}.  Minted by
   *       {@code OidcCallbackFilter} after a successful OIDC handshake.  These are the only
   *       sessions where RP-initiated logout against Keycloak is appropriate.</li>
   *   <li><b>Knox SSO</b> — RS256, signed by the Knox realm cert.  The user terminated their
   *       auth at Knox, not at Keycloak — sending them to {@code .../keycloak/.../logout} would
   *       hit the wrong IdP.</li>
   *   <li><b>Local login</b> — no cookie at all.  Sending the user to Keycloak just hangs the
   *       browser on private/offline-IdP demo setups.</li>
   * </ul>
   *
   * <p>Only the first case should redirect.  We differentiate by parsing the cookie value and
   * checking {@link AmbariSessionTokenService#isAmbariSessionToken} (which inspects both
   * {@code alg} and {@code iss}).
   *
   * <p>Keycloak (post-18) requires either {@code id_token_hint} OR ({@code client_id} +
   * {@code post_logout_redirect_uri} that matches a configured value).  We don't currently
   * persist {@code id_token}, so we use {@code client_id} together with the Ambari home URL.
   * The Keycloak client must therefore have the Ambari URL listed under
   * {@code Valid post logout redirect URIs} (typically inherited via {@code +} from
   * {@code Valid Redirect URIs}).
   */
  private String buildOidcLogoutUrl(JwtAuthenticationProperties props, HttpServletRequest request) {
    if (props == null || !props.isOidcEnabledForAmbari()) {
      return "";
    }
    if (!hasAmbariOidcSessionCookie(request, props.getCookieName())) {
      return "";
    }
    String endSession = props.getOidcEndSessionEndpoint();
    String clientId   = props.getOidcClientId();
    if (StringUtils.isEmpty(endSession) || StringUtils.isEmpty(clientId)) {
      return "";
    }
    String postLogoutRedirect = buildAmbariBaseUrl(request);
    return endSession
        + "?client_id="                + urlEncode(clientId)
        + "&post_logout_redirect_uri=" + urlEncode(postLogoutRedirect);
  }

  /**
   * Returns {@code true} when the inbound request carries a JWT cookie whose payload
   * was minted by {@code OidcCallbackFilter} (i.e. HS256 with {@code iss=ambari}).  A Knox
   * SSO cookie (RS256) or an absent cookie both return {@code false} so the caller will
   * skip the Keycloak-only RP-initiated logout flow.
   *
   * <p>This deliberately does NOT re-verify the HMAC signature: signature trust was
   * already established by {@code AmbariJwtAuthenticationFilter} when admitting the
   * request.  We're only using the iss/alg pair to disambiguate which IdP the session
   * originated from.
   */
  private static boolean hasAmbariOidcSessionCookie(HttpServletRequest request, String cookieName) {
    if (StringUtils.isEmpty(cookieName) || request.getCookies() == null) {
      return false;
    }
    for (Cookie cookie : request.getCookies()) {
      if (!cookieName.equals(cookie.getName()) || StringUtils.isEmpty(cookie.getValue())) {
        continue;
      }
      try {
        SignedJWT jwt = SignedJWT.parse(cookie.getValue());
        if (AmbariSessionTokenService.isAmbariSessionToken(jwt)) {
          return true;
        }
      } catch (ParseException e) {
        LOG.debug("Ignoring unparseable {} cookie at logout: {}", cookieName, e.getMessage());
      }
    }
    return false;
  }

  /** Builds {@code scheme://host[:port]/} from the inbound request. */
  private String buildAmbariBaseUrl(HttpServletRequest request) {
    StringBuilder sb = new StringBuilder()
        .append(request.getScheme()).append("://").append(request.getServerName());
    int port = request.getServerPort();
    boolean isDefaultPort = ("http".equals(request.getScheme()) && port == 80)
        || ("https".equals(request.getScheme()) && port == 443);
    if (!isDefaultPort) {
      sb.append(':').append(port);
    }
    sb.append('/');
    return sb.toString();
  }

  private static String urlEncode(String value) {
    try {
      return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      // UTF-8 is always supported; this is defensive.
      return value;
    }
  }

  /**
   * Creates and send and audit log event that the user has successfully logged out
   * @param servletRequest
   */
  private void auditLog(HttpServletRequest servletRequest) {
    if(!auditLogger.isEnabled()) {
      return;
    }
    LogoutAuditEvent logoutEvent = LogoutAuditEvent.builder()
      .withTimestamp(System.currentTimeMillis())
      .withRemoteIp(RequestUtils.getRemoteAddress(servletRequest))
      .withUserName(AuthorizationHelper.getAuthenticatedName())
      .withProxyUserName(AuthorizationHelper.getProxyUserName())
      .build();
    auditLogger.log(logoutEvent);
  }
}
