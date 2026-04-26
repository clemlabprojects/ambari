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
    expect(request.getServletPath()).andReturn("/").anyTimes();
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
    expect(request.getServletPath()).andReturn("/api/v1/clusters").anyTimes();
    replayAll();

    assertFalse(newFilter().isBrowserGetRequest(request));
    verifyAll();
  }

  @Test
  public void isBrowserGetRequest_false_forViewsPath() {
    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getMethod()).andReturn("GET").anyTimes();
    expect(request.getServletPath()).andReturn("/views/SQL-ASSISTANT/1.0.0/AUTO_SQL_ASSISTANT/").anyTimes();
    replayAll();

    assertFalse(newFilter().isBrowserGetRequest(request));
    verifyAll();
  }

  @Test
  public void isBrowserGetRequest_false_forXmlHttpRequestHeader() {
    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getMethod()).andReturn("GET").anyTimes();
    expect(request.getServletPath()).andReturn("/").anyTimes();
    expect(request.getHeader("X-Requested-With")).andReturn("XMLHttpRequest").anyTimes();
    replayAll();

    assertFalse(newFilter().isBrowserGetRequest(request));
    verifyAll();
  }

  @Test
  public void isBrowserGetRequest_false_whenAcceptHeaderMissingTextHtml() {
    HttpServletRequest request = createMock(HttpServletRequest.class);
    expect(request.getMethod()).andReturn("GET").anyTimes();
    expect(request.getServletPath()).andReturn("/").anyTimes();
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
    expect(request.getServletPath()).andReturn("/").anyTimes();
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
    expect(request.getMethod()).andReturn("GET").anyTimes();
    expect(request.getHeader("X-Requested-With")).andReturn(null).anyTimes();
    expect(request.getHeader("Accept")).andReturn("text/html").anyTimes();
    expect(request.getCookies()).andReturn(null).anyTimes();
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
}
