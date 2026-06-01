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

import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.core.Response;

import org.apache.ambari.server.audit.AuditLogger;
import org.apache.ambari.server.security.authentication.jwt.AmbariSessionTokenService;
import org.apache.ambari.server.security.authentication.jwt.JwtAuthenticationProperties;
import org.apache.ambari.server.security.authentication.jwt.JwtAuthenticationPropertiesProvider;
import org.easymock.EasyMockSupport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Verifies that {@link LogoutService} returns the Keycloak end-session URL ONLY when the
 * inbound request actually originated from an OIDC session — i.e. carries the JWT cookie
 * laid down by {@code OidcCallbackFilter}.  A locally-authenticated user (no cookie) must
 * not be redirected to the IdP on logout, since on private/offline-IdP demo setups that
 * just hangs the browser.
 */
public class LogoutServiceTest extends EasyMockSupport {

  private static final String COOKIE_NAME  = "hadoop-jwt";
  private static final String CLIENT_ID    = "ambari";
  private static final String END_SESSION  =
      "https://keycloak.corp.example.com/realms/odp/protocol/openid-connect/logout";

  // 32-byte HMAC key, satisfies AmbariSessionTokenService.MIN_HMAC_KEY_BYTES.
  private static final byte[] HMAC_KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

  private JwtAuthenticationPropertiesProvider propertiesProvider;
  private AuditLogger                          auditLogger;

  @Before
  public void setUp() throws Exception {
    propertiesProvider = createMock(JwtAuthenticationPropertiesProvider.class);
    auditLogger        = createNiceMock(AuditLogger.class);
    setStaticField("jwtPropertiesProvider", propertiesProvider);
    setStaticField("auditLogger",           auditLogger);
  }

  @After
  public void tearDown() throws Exception {
    setStaticField("jwtPropertiesProvider", null);
    setStaticField("auditLogger",           null);
  }

  @Test
  public void oidcSession_logoutUrlReturned() throws Exception {
    expect(propertiesProvider.get()).andReturn(oidcEnabledProps()).anyTimes();
    HttpServletRequest  request  = requestWithJwtCookie(mintAmbariSessionToken());
    HttpServletResponse response = createNiceMock(HttpServletResponse.class);
    replayAll();

    Response r = new LogoutService().performLogout(request, response);
    String body = (String) r.getEntity();

    assertTrue("response body should include the IdP end-session URL: " + body,
        body.contains("\"logout_url\""));
    assertTrue("URL should hit the configured end-session endpoint: " + body,
        body.contains(END_SESSION));
    // Gson HTML-escapes '=' and '&' when serializing strings, so check the unescaped substring
    // and the client id separately rather than the literal "client_id=ambari" form.
    assertTrue("URL should carry the client id: " + body,
        body.contains("client_id") && body.contains(CLIENT_ID));
    verifyAll();
  }

  @Test
  public void knoxSession_logoutUrlOmitted() throws Exception {
    // A Knox-SSO'd user lands on Ambari with a hadoop-jwt cookie minted by Knox — different
    // issuer (and in practice RS256, though iss alone is enough to distinguish).  We must
    // NOT redirect them to the Keycloak end-session endpoint — that is the wrong IdP.
    expect(propertiesProvider.get()).andReturn(oidcEnabledProps()).anyTimes();
    HttpServletRequest  request  = requestWithJwtCookie(mintNonAmbariToken());
    HttpServletResponse response = createNiceMock(HttpServletResponse.class);
    replayAll();

    Response r = new LogoutService().performLogout(request, response);
    String body = (String) r.getEntity();

    assertFalse("Knox-SSO session must NOT trigger Keycloak RP-initiated logout: " + body,
        body.contains("\"logout_url\""));
    verifyAll();
  }

  @Test
  public void localSession_noCookie_logoutUrlOmitted() throws Exception {
    expect(propertiesProvider.get()).andReturn(oidcEnabledProps()).anyTimes();
    HttpServletRequest  request  = requestWithoutJwtCookie();
    HttpServletResponse response = createNiceMock(HttpServletResponse.class);
    replayAll();

    Response r = new LogoutService().performLogout(request, response);
    String body = (String) r.getEntity();

    assertFalse("local-only logout must NOT redirect to the IdP: " + body,
        body.contains("\"logout_url\""));
    verifyAll();
  }

  @Test
  public void localSession_emptyCookieValue_logoutUrlOmitted() throws Exception {
    // After a prior logout the browser may still hold an empty-valued hadoop-jwt cookie
    // (Set-Cookie ...=; Max-Age=0).  That is not a live OIDC session — treat as local.
    expect(propertiesProvider.get()).andReturn(oidcEnabledProps()).anyTimes();
    HttpServletRequest  request  = requestWithJwtCookie("");
    HttpServletResponse response = createNiceMock(HttpServletResponse.class);
    replayAll();

    Response r = new LogoutService().performLogout(request, response);
    String body = (String) r.getEntity();

    assertFalse("empty cookie value must NOT be treated as an OIDC session: " + body,
        body.contains("\"logout_url\""));
    verifyAll();
  }

  @Test
  public void localSession_unparseableCookie_logoutUrlOmitted() throws Exception {
    // Defensive: a garbage cookie value (man-in-the-middle, stale browser state, etc.) must
    // not surface as a stack trace at logout — it should just behave like "no OIDC session".
    expect(propertiesProvider.get()).andReturn(oidcEnabledProps()).anyTimes();
    HttpServletRequest  request  = requestWithJwtCookie("this-is-not-a-jwt");
    HttpServletResponse response = createNiceMock(HttpServletResponse.class);
    replayAll();

    Response r = new LogoutService().performLogout(request, response);
    String body = (String) r.getEntity();

    assertFalse("unparseable cookie value must NOT redirect to the IdP: " + body,
        body.contains("\"logout_url\""));
    verifyAll();
  }

  @Test
  public void oidcDisabled_logoutUrlOmitted() throws Exception {
    JwtAuthenticationProperties props = createMock(JwtAuthenticationProperties.class);
    expect(props.isOidcEnabledForAmbari()).andReturn(false).anyTimes();
    expect(props.getCookieName()).andReturn(COOKIE_NAME).anyTimes();
    expect(propertiesProvider.get()).andReturn(props).anyTimes();
    HttpServletRequest  request  = requestWithJwtCookie(mintAmbariSessionToken());
    HttpServletResponse response = createNiceMock(HttpServletResponse.class);
    replayAll();

    Response r = new LogoutService().performLogout(request, response);
    String body = (String) r.getEntity();

    assertFalse("with OIDC disabled the response must never include logout_url: " + body,
        body.contains("\"logout_url\""));
    verifyAll();
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private JwtAuthenticationProperties oidcEnabledProps() {
    // JwtAuthenticationProperties' Map constructor is package-private; mock instead so the
    // test stays in its natural package.
    JwtAuthenticationProperties props = createMock(JwtAuthenticationProperties.class);
    expect(props.isOidcEnabledForAmbari()).andReturn(true).anyTimes();
    expect(props.getCookieName()).andReturn(COOKIE_NAME).anyTimes();
    expect(props.getOidcEndSessionEndpoint()).andReturn(END_SESSION).anyTimes();
    expect(props.getOidcClientId()).andReturn(CLIENT_ID).anyTimes();
    return props;
  }

  private HttpServletRequest requestWithJwtCookie(String value) {
    HttpServletRequest request = createNiceMock(HttpServletRequest.class);
    HttpSession session = createNiceMock(HttpSession.class);
    expect(request.getCookies()).andReturn(new Cookie[]{new Cookie(COOKIE_NAME, value)}).anyTimes();
    expect(request.getSession()).andReturn(session).anyTimes();
    expect(request.getScheme()).andReturn("https").anyTimes();
    expect(request.getServerName()).andReturn("ambari.corp.example.com").anyTimes();
    expect(request.getServerPort()).andReturn(443).anyTimes();
    return request;
  }

  private HttpServletRequest requestWithoutJwtCookie() {
    HttpServletRequest request = createNiceMock(HttpServletRequest.class);
    HttpSession session = createNiceMock(HttpSession.class);
    expect(request.getCookies()).andReturn(null).anyTimes();
    expect(request.getSession()).andReturn(session).anyTimes();
    return request;
  }

  /** Produces a real Ambari session JWT (HS256, iss=ambari). */
  private static String mintAmbariSessionToken() throws Exception {
    return AmbariSessionTokenService.mint("alice", 3600L, HMAC_KEY);
  }

  /**
   * Produces a valid SignedJWT whose claims/header look like a Knox SSO token: HMAC signed
   * but with a non-{@code ambari} issuer.  Enough to make {@code isAmbariSessionToken}
   * return {@code false} via the iss check, which is the only thing LogoutService cares
   * about for distinguishing IdPs.  Signing key is arbitrary — we never verify it here.
   */
  private static String mintNonAmbariToken() throws Exception {
    JWSHeader header = new JWSHeader(JWSAlgorithm.HS256);
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer("knox-sso")
        .subject("alice")
        .build();
    SignedJWT jwt = new SignedJWT(header, claims);
    jwt.sign(new MACSigner(HMAC_KEY));
    return jwt.serialize();
  }

  /** {@link LogoutService} uses {@code @StaticallyInject}; tests have to set the static fields manually. */
  private static void setStaticField(String name, Object value) throws Exception {
    Field f = LogoutService.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(null, value);
  }
}
