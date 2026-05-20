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

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import javax.servlet.FilterChain;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.ambari.server.security.authentication.AmbariAuthenticationEventHandler;
import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.web.AuthenticationEntryPoint;

public class OidcCallbackFilterTest extends EasyMockSupport {

  private static final String CLIENT_ID     = "ambari";
  private static final String CLIENT_SECRET = "test-secret-value";
  private static final String AUTH_URL      =
      "https://keycloak.corp.example.com/realms/odp/protocol/openid-connect/auth";

  private JwtAuthenticationPropertiesProvider propertiesProvider;
  private AmbariAuthenticationEventHandler    eventHandler;
  private AuthenticationEntryPoint            entryPoint;

  @Before
  public void setUp() {
    propertiesProvider = createMock(JwtAuthenticationPropertiesProvider.class);
    eventHandler       = createNiceMock(AmbariAuthenticationEventHandler.class);
    entryPoint         = createNiceMock(AuthenticationEntryPoint.class);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private OidcCallbackFilter newFilter() {
    return new OidcCallbackFilter(propertiesProvider, eventHandler, entryPoint);
  }

  private JwtAuthenticationProperties oidcEnabledProps() {
    JwtAuthenticationProperties props = new JwtAuthenticationProperties(java.util.Collections.emptyMap());
    props.setEnabledForAmbari(true);
    props.setOidcClientId(CLIENT_ID);
    props.setOidcClientSecret(CLIENT_SECRET);
    props.setAuthenticationProviderUrl(AUTH_URL);
    props.setCookieName("hadoop-jwt");
    return props;
  }

  private JwtAuthenticationProperties ssoEnabledNoOidcProps() {
    JwtAuthenticationProperties props = new JwtAuthenticationProperties(java.util.Collections.emptyMap());
    props.setEnabledForAmbari(true);
    // oidcClientId / oidcClientSecret left empty
    return props;
  }

  // ── shouldApply tests ─────────────────────────────────────────────────────

  @Test
  public void shouldApply_returnsFalse_whenPropertiesNull() {
    expect(propertiesProvider.get()).andReturn(null).anyTimes();
    HttpServletRequest request = createNiceMock(HttpServletRequest.class);
    replayAll();

    assertFalse(newFilter().shouldApply(request));
    verifyAll();
  }

  @Test
  public void shouldApply_returnsFalse_whenSsoDisabled() {
    JwtAuthenticationProperties props = new JwtAuthenticationProperties(java.util.Collections.emptyMap());
    props.setEnabledForAmbari(false);
    props.setOidcClientId(CLIENT_ID);
    props.setOidcClientSecret(CLIENT_SECRET);

    expect(propertiesProvider.get()).andReturn(props).anyTimes();
    HttpServletRequest request = createNiceMock(HttpServletRequest.class);
    replayAll();

    assertFalse(newFilter().shouldApply(request));
    verifyAll();
  }

  @Test
  public void shouldApply_returnsFalse_whenOidcNotConfigured() {
    expect(propertiesProvider.get()).andReturn(ssoEnabledNoOidcProps()).anyTimes();
    HttpServletRequest request = createNiceMock(HttpServletRequest.class);
    replayAll();

    assertFalse(newFilter().shouldApply(request));
    verifyAll();
  }

  @Test
  public void shouldApply_returnsFalse_whenOidcClientSecretMissing() {
    JwtAuthenticationProperties props = new JwtAuthenticationProperties(java.util.Collections.emptyMap());
    props.setEnabledForAmbari(true);
    props.setOidcClientId(CLIENT_ID);
    // no client secret

    expect(propertiesProvider.get()).andReturn(props).anyTimes();
    HttpServletRequest request = createNiceMock(HttpServletRequest.class);
    replayAll();

    assertFalse(newFilter().shouldApply(request));
    verifyAll();
  }

  @Test
  public void shouldApply_returnsTrue_forCallbackPath() {
    expect(propertiesProvider.get()).andReturn(oidcEnabledProps()).anyTimes();

    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getServletPath()).andReturn(OidcCallbackFilter.CALLBACK_PATH).anyTimes();
    replayAll();

    assertTrue(newFilter().shouldApply(request));
    verifyAll();
  }

  @Test
  public void shouldApply_returnsTrue_forBrowserGetWithoutJwtCookie() {
    expect(propertiesProvider.get()).andReturn(oidcEnabledProps()).anyTimes();

    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getServletPath()).andReturn("/").anyTimes();
    expect(request.getRequestURI()).andReturn("/").anyTimes();
    expect(request.getMethod()).andReturn("GET").anyTimes();
    expect(request.getHeader("X-Requested-With")).andReturn(null).anyTimes();
    expect(request.getHeader("Accept")).andReturn("text/html,application/xhtml+xml").anyTimes();
    expect(request.getCookies()).andReturn(null).anyTimes();
    replayAll();

    assertTrue(newFilter().shouldApply(request));
    verifyAll();
  }

  @Test
  public void shouldApply_returnsFalse_forBrowserGetWithJwtCookie() {
    expect(propertiesProvider.get()).andReturn(oidcEnabledProps()).anyTimes();

    Cookie jwtCookie = createMock(Cookie.class);
    expect(jwtCookie.getName()).andReturn("hadoop-jwt").anyTimes();
    expect(jwtCookie.getValue()).andReturn("some.jwt.token").anyTimes();

    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getServletPath()).andReturn("/").anyTimes();
    expect(request.getRequestURI()).andReturn("/").anyTimes();
    expect(request.getMethod()).andReturn("GET").anyTimes();
    expect(request.getHeader("X-Requested-With")).andReturn(null).anyTimes();
    expect(request.getHeader("Accept")).andReturn("text/html,application/xhtml+xml").anyTimes();
    expect(request.getCookies()).andReturn(new Cookie[]{jwtCookie}).anyTimes();
    replayAll();

    assertFalse(newFilter().shouldApply(request));
    verifyAll();
  }

  @Test
  public void shouldApply_returnsFalse_forApiPath() {
    expect(propertiesProvider.get()).andReturn(oidcEnabledProps()).anyTimes();

    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getServletPath()).andReturn("/api/v1/clusters").anyTimes();
    expect(request.getRequestURI()).andReturn("/api/v1/clusters").anyTimes();
    expect(request.getMethod()).andReturn("GET").anyTimes();
    expect(request.getHeader("X-Requested-With")).andReturn(null).anyTimes();
    expect(request.getHeader("Accept")).andReturn("text/html").anyTimes();
    replayAll();

    assertFalse(newFilter().shouldApply(request));
    verifyAll();
  }

  // ── isBrowserGetRequest tests ─────────────────────────────────────────────

  @Test
  public void isBrowserGetRequest_true_forPlainBrowserNavigationGet() {
    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getMethod()).andReturn("GET").anyTimes();
    expect(request.getRequestURI()).andReturn("/").anyTimes();
    expect(request.getHeader("X-Requested-With")).andReturn(null).anyTimes();
    expect(request.getHeader("Accept")).andReturn("text/html,application/xhtml+xml,*/*").anyTimes();
    replayAll();

    assertTrue(newFilter().isBrowserGetRequest(request));
    verifyAll();
  }

  @Test
  public void isBrowserGetRequest_false_forPostMethod() {
    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getMethod()).andReturn("POST").anyTimes();
    replayAll();

    assertFalse(newFilter().isBrowserGetRequest(request));
    verifyAll();
  }

  @Test
  public void isBrowserGetRequest_false_forApiPath() {
    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getMethod()).andReturn("GET").anyTimes();
    expect(request.getRequestURI()).andReturn("/api/v1/clusters").anyTimes();
    replayAll();

    assertFalse(newFilter().isBrowserGetRequest(request));
    verifyAll();
  }

  @Test
  public void isBrowserGetRequest_false_forViewsPath() {
    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getMethod()).andReturn("GET").anyTimes();
    expect(request.getRequestURI()).andReturn("/views/SQL-ASSISTANT/1.0.0/AUTO_SQL_ASSISTANT/").anyTimes();
    replayAll();

    assertFalse(newFilter().isBrowserGetRequest(request));
    verifyAll();
  }

  /**
   * AMBARI-432 regression: inside an Ambari View's WebAppContext, {@code getServletPath()}
   * returns the path RELATIVE to the view's context (e.g. "/") rather than the absolute
   * {@code /views/...} URI.  This test asserts that the exclusion now relies on
   * {@code getRequestURI()} (the absolute path) so the view request is still excluded
   * regardless of how the servlet container resolves the servlet path.
   */
  @Test
  public void isBrowserGetRequest_false_forViewsRequestWithEmptyServletPath() {
    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getMethod()).andReturn("GET").anyTimes();
    // Simulate the request inside a view's WebAppContext: servletPath="" but requestURI="/views/..."
    expect(request.getRequestURI()).andReturn("/views/SQL-ASSISTANT/1.0.0/AUTO_SQL_ASSISTANT/").anyTimes();
    replayAll();

    assertFalse(newFilter().isBrowserGetRequest(request));
    verifyAll();
  }

  @Test
  public void isBrowserGetRequest_false_forXmlHttpRequestHeader() {
    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getMethod()).andReturn("GET").anyTimes();
    expect(request.getRequestURI()).andReturn("/").anyTimes();
    expect(request.getHeader("X-Requested-With")).andReturn("XMLHttpRequest").anyTimes();
    replayAll();

    assertFalse(newFilter().isBrowserGetRequest(request));
    verifyAll();
  }

  @Test
  public void isBrowserGetRequest_false_whenAcceptHeaderMissingTextHtml() {
    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getMethod()).andReturn("GET").anyTimes();
    expect(request.getRequestURI()).andReturn("/").anyTimes();
    expect(request.getHeader("X-Requested-With")).andReturn(null).anyTimes();
    expect(request.getHeader("Accept")).andReturn("application/json").anyTimes();
    replayAll();

    assertFalse(newFilter().isBrowserGetRequest(request));
    verifyAll();
  }

  @Test
  public void isBrowserGetRequest_false_whenAcceptHeaderNull() {
    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getMethod()).andReturn("GET").anyTimes();
    expect(request.getRequestURI()).andReturn("/").anyTimes();
    expect(request.getHeader("X-Requested-With")).andReturn(null).anyTimes();
    expect(request.getHeader("Accept")).andReturn(null).anyTimes();
    replayAll();

    assertFalse(newFilter().isBrowserGetRequest(request));
    verifyAll();
  }

  // ── buildState / validateStateAndExtractOriginalPath round-trip ───────────

  @Test
  public void stateRoundTrip_validToken_returnsOriginalPath()
      throws OidcCallbackFilter.OidcStateException {
    replayAll();
    OidcCallbackFilter filter = newFilter();

    String state = filter.buildState("/original/path?foo=bar", CLIENT_SECRET);
    assertNotNull(state);
    assertTrue(state.contains("."));

    String extracted = filter.validateStateAndExtractOriginalPath(state, CLIENT_SECRET);
    assertEquals("/original/path?foo=bar", extracted);
    verifyAll();
  }

  @Test(expected = OidcCallbackFilter.OidcStateException.class)
  public void stateValidation_throwsOnTamperedHmac()
      throws OidcCallbackFilter.OidcStateException {
    replayAll();
    OidcCallbackFilter filter = newFilter();

    String state       = filter.buildState("/some/path", CLIENT_SECRET);
    String tampered    = state.substring(0, state.lastIndexOf('.') + 1) + "AAABBBCCC";
    filter.validateStateAndExtractOriginalPath(tampered, CLIENT_SECRET);
  }

  @Test(expected = OidcCallbackFilter.OidcStateException.class)
  public void stateValidation_throwsOnWrongSecret()
      throws OidcCallbackFilter.OidcStateException {
    replayAll();
    OidcCallbackFilter filter = newFilter();

    String state = filter.buildState("/some/path", CLIENT_SECRET);
    filter.validateStateAndExtractOriginalPath(state, "wrong-secret");
  }

  @Test(expected = OidcCallbackFilter.OidcStateException.class)
  public void stateValidation_throwsOnMissingDotSeparator()
      throws OidcCallbackFilter.OidcStateException {
    replayAll();
    newFilter().validateStateAndExtractOriginalPath("nodotintoken", CLIENT_SECRET);
  }

  @Test(expected = OidcCallbackFilter.OidcStateException.class)
  public void stateValidation_throwsOnAbsoluteUrl()
      throws OidcCallbackFilter.OidcStateException {
    // Craft a state token where the url field does not start with '/'
    // We can do this by building a valid-HMAC token manually using the filter's own buildState
    // but with a path that starts with 'http' — that shouldn't happen in normal flow because
    // buildOriginalPath always returns a path starting with '/'. We therefore test the guard
    // directly by verifying that a state whose 'url' value was tampered to an absolute URL is
    // rejected even if (hypothetically) the HMAC still matched.
    //
    // The simplest way to hit this code path deterministically: build a valid state for a path
    // that starts with '/', then manually alter the payload to include "http://". Since the HMAC
    // would then fail, we instead verify via a unit-test-only white-box route: we build a state
    // with a path starting with "/", decode it, alter the url field in the JSON, re-encode with
    // the correct HMAC, and replay it.  Rather than replicating that complexity here, we rely on
    // the HMAC-tampered test above and a second approach: directly test the guard by passing a
    // state that decodes to a non-relative URL.
    //
    // Simplest approach: we can pass a raw (crafted) state token here to trigger the 'url'
    // validation path. Build one manually.
    replayAll();
    OidcCallbackFilter filter = newFilter();

    // Build a valid token for a relative path, then verify it passes the guard.
    // The guard "url must start with '/'" is exercised by manually creating a state
    // whose url field starts with 'http', signed with the correct key.
    // We achieve this by building a state for a path without the leading '/'.
    // Since buildState requires us to supply any string, supply "http://evil.com".
    String state = filter.buildState("http://evil.com/steal", CLIENT_SECRET);
    filter.validateStateAndExtractOriginalPath(state, CLIENT_SECRET);
  }

  // ── doFilter redirect leg ─────────────────────────────────────────────────

  @Test
  public void doFilter_redirectsToKeycloak_forUnauthenticatedBrowserGet() throws Exception {
    expect(propertiesProvider.get()).andReturn(oidcEnabledProps()).anyTimes();

    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getServletPath()).andReturn("/").anyTimes();
    expect(request.getRequestURI()).andReturn("/").anyTimes();
    expect(request.getMethod()).andReturn("GET").anyTimes();
    expect(request.getHeader("X-Requested-With")).andReturn(null).anyTimes();
    expect(request.getHeader("Accept")).andReturn("text/html").anyTimes();
    expect(request.getCookies()).andReturn(null).anyTimes();
    expect(request.getParameter("returnUrl")).andReturn(null).anyTimes();
    expect(request.getQueryString()).andReturn(null).anyTimes();
    // For resolveCallbackUrl when no oidcCallbackUrl is configured
    expect(request.getScheme()).andReturn("https").anyTimes();
    expect(request.getServerName()).andReturn("ambari.corp.example.com").anyTimes();
    expect(request.getServerPort()).andReturn(8442).anyTimes();

    HttpServletResponse response = createMock(HttpServletResponse.class);
    // Capture the redirect URL — just verify sendRedirect is called once
    response.sendRedirect(org.easymock.EasyMock.anyString());
    expectLastCall().once();

    FilterChain chain = createMock(FilterChain.class);
    // chain.doFilter must NOT be called
    replayAll();

    newFilter().doFilter(request, response, chain);
    verifyAll();
  }

  @Test
  public void doFilter_redirectsToKeycloak_andEmbedsDerivedPathFromReturnUrl() throws Exception {
    // When Angular sends /oidc/begin?returnUrl=https://ambari:8442/#/dashboard the state
    // should embed "/" (path extracted from the absolute URL) rather than the raw query string,
    // preventing a redirect loop back to /oidc/begin.
    expect(propertiesProvider.get()).andReturn(oidcEnabledProps()).anyTimes();

    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getServletPath()).andReturn("/oidc/begin").anyTimes();
    expect(request.getRequestURI()).andReturn("/oidc/begin").anyTimes();
    expect(request.getMethod()).andReturn("GET").anyTimes();
    expect(request.getHeader("X-Requested-With")).andReturn(null).anyTimes();
    expect(request.getHeader("Accept")).andReturn("text/html").anyTimes();
    expect(request.getCookies()).andReturn(null).anyTimes();
    expect(request.getParameter("returnUrl"))
        .andReturn("https://ambari.corp.example.com:8442/#/dashboard").anyTimes();
    expect(request.getQueryString())
        .andReturn("returnUrl=https%3A%2F%2Fambari.corp.example.com%3A8442%2F%23%2Fdashboard").anyTimes();
    expect(request.getScheme()).andReturn("https").anyTimes();
    expect(request.getServerName()).andReturn("ambari.corp.example.com").anyTimes();
    expect(request.getServerPort()).andReturn(8442).anyTimes();

    org.easymock.Capture<String> urlCapture = org.easymock.EasyMock.newCapture();
    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.sendRedirect(org.easymock.EasyMock.capture(urlCapture));
    expectLastCall().once();

    FilterChain chain = createMock(FilterChain.class);
    replayAll();

    newFilter().doFilter(request, response, chain);
    verifyAll();

    // Decode the state parameter from the captured redirect URL and verify url="/"
    String encodedState = urlCapture.getValue().replaceAll(".*[?&]state=([^&]+).*", "$1");
    String statePayload = new String(java.util.Base64.getUrlDecoder().decode(
        encodedState.substring(0, encodedState.lastIndexOf('.'))), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue("State should embed '/' not /oidc/begin", statePayload.contains("\"url\":\"/\""));
  }

  @Test
  public void doFilter_redirectsToKeycloak_usesSlash_whenOidcBeginHasNoReturnUrl() throws Exception {
    // /oidc/begin with no returnUrl parameter should still embed "/" (not /oidc/begin) to
    // prevent a redirect loop.
    expect(propertiesProvider.get()).andReturn(oidcEnabledProps()).anyTimes();

    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getServletPath()).andReturn("/oidc/begin").anyTimes();
    expect(request.getRequestURI()).andReturn("/oidc/begin").anyTimes();
    expect(request.getMethod()).andReturn("GET").anyTimes();
    expect(request.getHeader("X-Requested-With")).andReturn(null).anyTimes();
    expect(request.getHeader("Accept")).andReturn("text/html").anyTimes();
    expect(request.getCookies()).andReturn(null).anyTimes();
    expect(request.getParameter("returnUrl")).andReturn(null).anyTimes();
    expect(request.getQueryString()).andReturn(null).anyTimes();
    expect(request.getScheme()).andReturn("https").anyTimes();
    expect(request.getServerName()).andReturn("ambari.corp.example.com").anyTimes();
    expect(request.getServerPort()).andReturn(8442).anyTimes();

    org.easymock.Capture<String> urlCapture = org.easymock.EasyMock.newCapture();
    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.sendRedirect(org.easymock.EasyMock.capture(urlCapture));
    expectLastCall().once();

    FilterChain chain = createMock(FilterChain.class);
    replayAll();

    newFilter().doFilter(request, response, chain);
    verifyAll();

    String encodedState = urlCapture.getValue().replaceAll(".*[?&]state=([^&]+).*", "$1");
    String statePayload = new String(java.util.Base64.getUrlDecoder().decode(
        encodedState.substring(0, encodedState.lastIndexOf('.'))), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue("State should embed '/' not /oidc/begin", statePayload.contains("\"url\":\"/\""));
  }

  // ── doFilter callback leg — missing parameters ────────────────────────────

  @Test
  public void doFilter_callbackPath_sendsBadRequest_whenCodeMissing() throws Exception {
    expect(propertiesProvider.get()).andReturn(oidcEnabledProps()).anyTimes();

    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getServletPath()).andReturn(OidcCallbackFilter.CALLBACK_PATH).anyTimes();
    expect(request.getParameter("code")).andReturn(null).anyTimes();
    expect(request.getParameter("state")).andReturn("somestate").anyTimes();

    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.sendError(HttpServletResponse.SC_BAD_REQUEST,
        "Missing OIDC callback parameters");
    expectLastCall().once();

    FilterChain chain = createMock(FilterChain.class);
    replayAll();

    newFilter().doFilter(request, response, chain);
    verifyAll();
  }

  @Test
  public void doFilter_callbackPath_sendsBadRequest_whenStateMissing() throws Exception {
    expect(propertiesProvider.get()).andReturn(oidcEnabledProps()).anyTimes();

    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getServletPath()).andReturn(OidcCallbackFilter.CALLBACK_PATH).anyTimes();
    expect(request.getParameter("code")).andReturn("authcode123").anyTimes();
    expect(request.getParameter("state")).andReturn(null).anyTimes();

    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.sendError(HttpServletResponse.SC_BAD_REQUEST,
        "Missing OIDC callback parameters");
    expectLastCall().once();

    FilterChain chain = createMock(FilterChain.class);
    replayAll();

    newFilter().doFilter(request, response, chain);
    verifyAll();
  }

  @Test
  public void doFilter_callbackPath_sendsBadRequest_whenStateInvalid() throws Exception {
    expect(propertiesProvider.get()).andReturn(oidcEnabledProps()).anyTimes();

    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getServletPath()).andReturn(OidcCallbackFilter.CALLBACK_PATH).anyTimes();
    expect(request.getParameter("code")).andReturn("authcode123").anyTimes();
    expect(request.getParameter("state")).andReturn("tampered.garbage").anyTimes();

    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.sendError(HttpServletResponse.SC_BAD_REQUEST,
        "Invalid or expired SSO state");
    expectLastCall().once();

    FilterChain chain = createMock(FilterChain.class);
    replayAll();

    newFilter().doFilter(request, response, chain);
    verifyAll();
  }

  // ─────────────────────────────────────────────────────────────────────────
  // AMBARI-433: extractUsernameFromAccessToken
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  public void extractUsername_preferredUsernameClaim_winsOverSub() throws Exception {
    // Mint a self-signed HS256 token that mimics Keycloak's access-token shape (we only
    // care about claim extraction here, NOT signature verification — extractUsername
    // doesn't verify).
    byte[] key = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
        .subject("uuid-1234")
        .claim("preferred_username", "alice")
        .build();
    com.nimbusds.jwt.SignedJWT jwt =
        new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), claims);
    jwt.sign(new com.nimbusds.jose.crypto.MACSigner(key));

    replayAll();
    String username = newFilter().extractUsernameFromAccessToken(jwt.serialize());
    assertEquals("alice", username);
    verifyAll();
  }

  @Test
  public void extractUsername_fallsBackToSub_whenPreferredUsernameAbsent() throws Exception {
    byte[] key = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
        .subject("uuid-only-no-username")
        .build();
    com.nimbusds.jwt.SignedJWT jwt =
        new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), claims);
    jwt.sign(new com.nimbusds.jose.crypto.MACSigner(key));

    replayAll();
    String username = newFilter().extractUsernameFromAccessToken(jwt.serialize());
    assertEquals("uuid-only-no-username", username);
    verifyAll();
  }

  @Test
  public void extractUsername_returnsNull_whenNeitherClaimPresent() throws Exception {
    byte[] key = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().build();
    com.nimbusds.jwt.SignedJWT jwt =
        new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), claims);
    jwt.sign(new com.nimbusds.jose.crypto.MACSigner(key));

    replayAll();
    String username = newFilter().extractUsernameFromAccessToken(jwt.serialize());
    org.junit.Assert.assertNull(username);
    verifyAll();
  }

  @Test(expected = java.text.ParseException.class)
  public void extractUsername_throwsOnGarbageInput() throws Exception {
    replayAll();
    newFilter().extractUsernameFromAccessToken("not.a.jwt");
  }

  @Test
  public void extractUsername_configuredClaim_returnsThatClaim() throws Exception {
    // Operator set usernameClaim=email; Keycloak put the username under email.
    byte[] key = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
        .subject("uuid-fallback")
        .claim("preferred_username", "should-be-ignored")
        .claim("email", "alice@corp.example.com")
        .build();
    com.nimbusds.jwt.SignedJWT jwt =
        new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), claims);
    jwt.sign(new com.nimbusds.jose.crypto.MACSigner(key));

    JwtAuthenticationProperties props = new JwtAuthenticationProperties(java.util.Collections.emptyMap());
    props.setJwtUsernameClaim("email");

    replayAll();
    String username = newFilter().extractUsernameFromAccessToken(jwt.serialize(), props);
    assertEquals("alice@corp.example.com", username);
    verifyAll();
  }

  @Test
  public void extractUsername_configuredClaim_returnsNull_whenAbsent() throws Exception {
    // Operator misconfigured the claim — token has preferred_username but no 'email'.
    byte[] key = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
        .subject("uuid")
        .claim("preferred_username", "alice")
        .build();
    com.nimbusds.jwt.SignedJWT jwt =
        new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), claims);
    jwt.sign(new com.nimbusds.jose.crypto.MACSigner(key));

    JwtAuthenticationProperties props = new JwtAuthenticationProperties(java.util.Collections.emptyMap());
    props.setJwtUsernameClaim("email");

    replayAll();
    String username = newFilter().extractUsernameFromAccessToken(jwt.serialize(), props);
    org.junit.Assert.assertNull("Misconfigured claim must surface as null, not a silent fallback",
        username);
    verifyAll();
  }

  /**
   * AMBARI-433 regression — without claim forwarding, AMBARI-419 JIT / AMBARI-423 allowed-groups
   * checks fail because the session token has no {@code groups} claim.  This test asserts the
   * upstream {@code groups} array is propagated into the minted cookie.
   */
  @Test
  public void doFilter_callbackPath_forwardsGroupsClaimFromAccessToken() throws Exception {
    byte[] kcKey = "kc-stub-key-thirty-two-bytes-min".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    com.nimbusds.jwt.JWTClaimsSet kcClaims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
        .subject("uuid")
        .claim("preferred_username", "alice")
        .claim("groups", java.util.Arrays.asList("hadoop_admins", "hadoop_users"))
        .build();
    com.nimbusds.jwt.SignedJWT kcJwt = new com.nimbusds.jwt.SignedJWT(
        new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), kcClaims);
    kcJwt.sign(new com.nimbusds.jose.crypto.MACSigner(kcKey));
    String fakeAccessToken = kcJwt.serialize();

    JwtAuthenticationProperties props = oidcEnabledProps();
    props.setSessionTokenLifespanSeconds(3600L);
    props.setOidcGroupsClaim("groups");
    props.setOidcCallbackUrl("https://ambari.test:8442/oidc/callback");

    expect(propertiesProvider.get()).andReturn(props).anyTimes();

    OidcCallbackFilter signer = new OidcCallbackFilter(propertiesProvider, eventHandler, entryPoint);

    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getServletPath()).andReturn(OidcCallbackFilter.CALLBACK_PATH).anyTimes();
    expect(request.getParameter("code")).andReturn("authcode-groups").anyTimes();
    expect(request.getScheme()).andReturn("https").anyTimes();
    expect(request.getServerName()).andReturn("ambari.test").anyTimes();
    expect(request.getServerPort()).andReturn(8442).anyTimes();

    org.easymock.Capture<String> cookieCapture = org.easymock.EasyMock.newCapture();
    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.addHeader(org.easymock.EasyMock.eq("Set-Cookie"), org.easymock.EasyMock.capture(cookieCapture));
    expectLastCall().once();
    response.sendRedirect(org.easymock.EasyMock.anyString());
    expectLastCall().once();

    String validState = signer.buildState("/landing", CLIENT_SECRET);
    expect(request.getParameter("state")).andReturn(validState).anyTimes();

    FilterChain chain = createMock(FilterChain.class);
    replayAll();

    new StubbedExchangeFilter(propertiesProvider, eventHandler, entryPoint, fakeAccessToken)
        .doFilter(request, response, chain);
    verifyAll();

    String setCookie = cookieCapture.getValue();
    String cookieValue = setCookie.substring("hadoop-jwt=".length(), setCookie.indexOf(';'));
    com.nimbusds.jwt.SignedJWT minted = com.nimbusds.jwt.SignedJWT.parse(cookieValue);

    Object groups = minted.getJWTClaimsSet().getClaim("groups");
    assertNotNull("groups claim must be propagated from access token to session token", groups);
    java.util.List<?> groupList = (java.util.List<?>) groups;
    assertEquals(2, groupList.size());
    assertTrue("Must contain hadoop_admins", groupList.contains("hadoop_admins"));
    assertTrue("Must contain hadoop_users", groupList.contains("hadoop_users"));
  }

  /**
   * When the operator sets {@code groups.claim} but the upstream access token has no such
   * claim, the session token simply doesn't get one — no NPE, no crash, no empty array forged.
   */
  @Test
  public void doFilter_callbackPath_groupsClaimMissing_isHandledQuietly() throws Exception {
    byte[] kcKey = "kc-stub-key-thirty-two-bytes-min".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    com.nimbusds.jwt.JWTClaimsSet kcClaims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
        .subject("uuid")
        .claim("preferred_username", "alice")
        .build();
    com.nimbusds.jwt.SignedJWT kcJwt = new com.nimbusds.jwt.SignedJWT(
        new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), kcClaims);
    kcJwt.sign(new com.nimbusds.jose.crypto.MACSigner(kcKey));

    JwtAuthenticationProperties props = oidcEnabledProps();
    props.setOidcGroupsClaim("groups");
    props.setOidcCallbackUrl("https://ambari.test:8442/oidc/callback");

    expect(propertiesProvider.get()).andReturn(props).anyTimes();

    OidcCallbackFilter signer = new OidcCallbackFilter(propertiesProvider, eventHandler, entryPoint);

    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getServletPath()).andReturn(OidcCallbackFilter.CALLBACK_PATH).anyTimes();
    expect(request.getParameter("code")).andReturn("c").anyTimes();
    expect(request.getScheme()).andReturn("https").anyTimes();
    expect(request.getServerName()).andReturn("ambari.test").anyTimes();
    expect(request.getServerPort()).andReturn(8442).anyTimes();

    org.easymock.Capture<String> cookieCapture = org.easymock.EasyMock.newCapture();
    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.addHeader(org.easymock.EasyMock.eq("Set-Cookie"), org.easymock.EasyMock.capture(cookieCapture));
    expectLastCall().once();
    response.sendRedirect(org.easymock.EasyMock.anyString());
    expectLastCall().once();

    expect(request.getParameter("state"))
        .andReturn(signer.buildState("/landing", CLIENT_SECRET)).anyTimes();

    FilterChain chain = createMock(FilterChain.class);
    replayAll();

    new StubbedExchangeFilter(propertiesProvider, eventHandler, entryPoint, kcJwt.serialize())
        .doFilter(request, response, chain);
    verifyAll();

    String cookieValue = cookieCapture.getValue();
    cookieValue = cookieValue.substring("hadoop-jwt=".length(), cookieValue.indexOf(';'));
    com.nimbusds.jwt.SignedJWT minted = com.nimbusds.jwt.SignedJWT.parse(cookieValue);
    org.junit.Assert.assertNull("No groups claim should be stamped when upstream has none",
        minted.getJWTClaimsSet().getClaim("groups"));
  }

  /**
   * End-to-end check that an operator who sets {@code usernameClaim=email} sees their
   * configuration honored through both legs of the flow: identity extracted from the upstream
   * access token's {@code email} claim, AND the minted session token carrying the resolved
   * username under {@code email} so request-time {@code resolveUsername} keeps working.
   */
  @Test
  public void doFilter_callbackPath_customUsernameClaim_endToEnd() throws Exception {
    // Build a Keycloak-like access token that uses an operator-custom claim for the username.
    byte[] kcKey = "kc-stub-key-thirty-two-bytes-min".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    com.nimbusds.jwt.JWTClaimsSet kcClaims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
        .subject("uuid")
        .claim("preferred_username", "uuid-should-not-be-used")
        .claim("email", "alice@corp.example.com")
        .build();
    com.nimbusds.jwt.SignedJWT kcJwt = new com.nimbusds.jwt.SignedJWT(
        new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), kcClaims);
    kcJwt.sign(new com.nimbusds.jose.crypto.MACSigner(kcKey));
    String fakeAccessToken = kcJwt.serialize();

    JwtAuthenticationProperties props = oidcEnabledProps();
    props.setSessionTokenLifespanSeconds(3600L);
    props.setJwtUsernameClaim("email");
    props.setOidcCallbackUrl("https://ambari.test:8442/oidc/callback");

    expect(propertiesProvider.get()).andReturn(props).anyTimes();

    OidcCallbackFilter signer = new OidcCallbackFilter(propertiesProvider, eventHandler, entryPoint);

    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getServletPath()).andReturn(OidcCallbackFilter.CALLBACK_PATH).anyTimes();
    expect(request.getParameter("code")).andReturn("authcode-custom").anyTimes();
    expect(request.getScheme()).andReturn("https").anyTimes();
    expect(request.getServerName()).andReturn("ambari.test").anyTimes();
    expect(request.getServerPort()).andReturn(8442).anyTimes();

    org.easymock.Capture<String> cookieCapture = org.easymock.EasyMock.newCapture();
    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.addHeader(org.easymock.EasyMock.eq("Set-Cookie"), org.easymock.EasyMock.capture(cookieCapture));
    expectLastCall().once();
    response.sendRedirect(org.easymock.EasyMock.anyString());
    expectLastCall().once();

    String validState = signer.buildState("/landing-with-custom-claim", CLIENT_SECRET);
    expect(request.getParameter("state")).andReturn(validState).anyTimes();

    FilterChain chain = createMock(FilterChain.class);
    replayAll();

    new StubbedExchangeFilter(propertiesProvider, eventHandler, entryPoint, fakeAccessToken)
        .doFilter(request, response, chain);
    verifyAll();

    // Decode the minted session token and assert the resolved identity threaded through end-to-end.
    String setCookie = cookieCapture.getValue();
    String cookieValue = setCookie.substring("hadoop-jwt=".length(), setCookie.indexOf(';'));
    com.nimbusds.jwt.SignedJWT minted = com.nimbusds.jwt.SignedJWT.parse(cookieValue);

    assertEquals(AmbariSessionTokenService.ISSUER, minted.getJWTClaimsSet().getIssuer());
    assertEquals("alice@corp.example.com", minted.getJWTClaimsSet().getSubject());
    assertEquals("alice@corp.example.com",
        minted.getJWTClaimsSet().getStringClaim("preferred_username"));
    assertEquals("Custom 'email' claim must be stamped into the session token "
            + "so request-time resolveUsername finds it",
        "alice@corp.example.com", minted.getJWTClaimsSet().getStringClaim("email"));

    // Round-trip: resolveUsername reading the session token with the same operator config
    // must return the original identity.
    assertEquals("alice@corp.example.com",
        AmbariSessionTokenService.resolveUsername(minted, props));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // AMBARI-433: full callback end-to-end uses a subclass that stubs the
  // network round-trip to Keycloak so we can deterministically test the
  // session-token branch without spinning up an HTTP server.
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Test double that bypasses {@link OidcCallbackFilter#exchangeCodeForToken} so unit tests
   * don't need a live Keycloak.  The override returns a pre-built TokenResponse containing
   * the stub access token; everything downstream of the network call runs unmodified.
   */
  private static final class StubbedExchangeFilter extends OidcCallbackFilter {
    private final String pretendAccessToken;

    StubbedExchangeFilter(JwtAuthenticationPropertiesProvider provider,
                          AmbariAuthenticationEventHandler eventHandler,
                          AuthenticationEntryPoint entryPoint,
                          String pretendAccessToken) {
      super(provider, eventHandler, entryPoint);
      this.pretendAccessToken = pretendAccessToken;
    }

    @Override
    TokenResponse exchangeCodeForToken(String code, String callbackUrl,
                                       JwtAuthenticationProperties props) {
      // expires_in is irrelevant for AMBARI-433: cookie Max-Age now comes from session
      // lifespan, not from the upstream token's expires_in.  Returning 0 confirms we no
      // longer leak that value into the cookie.
      return new TokenResponse(pretendAccessToken, 0);
    }
  }

  @Test
  public void doFilter_callbackPath_mintsSessionToken_andRedirects() throws Exception {
    // Build a "Keycloak-like" access token claiming preferred_username=alice.
    byte[] kcKey = "kc-stub-key-thirty-two-bytes-min".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    com.nimbusds.jwt.JWTClaimsSet kcClaims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
        .subject("uuid")
        .claim("preferred_username", "alice")
        .build();
    com.nimbusds.jwt.SignedJWT kcJwt = new com.nimbusds.jwt.SignedJWT(
        new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), kcClaims);
    kcJwt.sign(new com.nimbusds.jose.crypto.MACSigner(kcKey));
    String fakeAccessToken = kcJwt.serialize();

    // Use a filter that skips exchangeCodeForToken but otherwise runs the full handleOidcCallback.
    JwtAuthenticationProperties props = oidcEnabledProps();
    // Make the session token easy to verify in this test: lifespan=1h.
    props.setSessionTokenLifespanSeconds(3600L);

    expect(propertiesProvider.get()).andReturn(props).anyTimes();

    // Build a valid state by hand so the state validation passes; we'll use a real filter
    // instance to do so first, then capture the value.
    OidcCallbackFilter signer = new OidcCallbackFilter(propertiesProvider, eventHandler, entryPoint);
    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getServletPath()).andReturn(OidcCallbackFilter.CALLBACK_PATH).anyTimes();
    expect(request.getParameter("code")).andReturn("authcode-xyz").anyTimes();
    // buildRedirectTarget reads these to reconstruct the absolute return URL.
    expect(request.getScheme()).andReturn("https").anyTimes();
    expect(request.getServerName()).andReturn("ambari.test").anyTimes();
    expect(request.getServerPort()).andReturn(8442).anyTimes();

    // We need a valid state - generate one using the same secret the filter will validate against.
    org.easymock.Capture<String> cookieCapture = org.easymock.EasyMock.newCapture();
    org.easymock.Capture<String> redirectCapture = org.easymock.EasyMock.newCapture();

    HttpServletResponse response = createMock(HttpServletResponse.class);
    response.addHeader(org.easymock.EasyMock.eq("Set-Cookie"), org.easymock.EasyMock.capture(cookieCapture));
    expectLastCall().once();
    response.sendRedirect(org.easymock.EasyMock.capture(redirectCapture));
    expectLastCall().once();

    // Resolve callback URL: the filter prefers props.getOidcCallbackUrl() if set; otherwise
    // it reconstructs from scheme/server/port - we set the property to avoid mocking those.
    props.setOidcCallbackUrl("https://ambari.test:8442/oidc/callback");

    String validState = signer.buildState("/original/landing", CLIENT_SECRET);
    expect(request.getParameter("state")).andReturn(validState).anyTimes();

    FilterChain chain = createMock(FilterChain.class);
    replayAll();

    StubbedExchangeFilter testFilter = new StubbedExchangeFilter(
        propertiesProvider, eventHandler, entryPoint, fakeAccessToken);
    testFilter.doFilter(request, response, chain);
    verifyAll();

    // Verify the cookie carries an HS256 Ambari-signed JWT (NOT the raw access token).
    String setCookie = cookieCapture.getValue();
    assertTrue("Set-Cookie should include hadoop-jwt=", setCookie.startsWith("hadoop-jwt="));
    String cookieValue = setCookie.substring("hadoop-jwt=".length(), setCookie.indexOf(';'));
    assertFalse("Cookie value MUST NOT be the raw Keycloak access token",
        cookieValue.equals(fakeAccessToken));

    com.nimbusds.jwt.SignedJWT minted = com.nimbusds.jwt.SignedJWT.parse(cookieValue);
    assertEquals(com.nimbusds.jose.JWSAlgorithm.HS256, minted.getHeader().getAlgorithm());
    assertEquals(AmbariSessionTokenService.ISSUER, minted.getJWTClaimsSet().getIssuer());
    assertEquals("alice", minted.getJWTClaimsSet().getSubject());

    // Verify the cookie verifies against the same key the auth filter would resolve.
    byte[] expectedKey = AmbariSessionTokenService.resolveSigningKey(props);
    assertTrue(AmbariSessionTokenService.verify(minted, expectedKey));

    // Verify the redirect goes to the originalPath embedded in state.
    assertTrue("Redirect should target /original/landing",
        redirectCapture.getValue().endsWith("/original/landing"));

    // Verify Max-Age in the cookie matches the configured session lifespan.
    assertTrue("Cookie Max-Age should match configured 3600s lifespan",
        setCookie.contains("Max-Age=3600"));
  }
}
