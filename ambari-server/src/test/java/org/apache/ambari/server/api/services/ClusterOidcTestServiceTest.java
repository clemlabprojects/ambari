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

import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.ws.rs.core.Response;

import org.apache.ambari.server.ClusterNotFoundException;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.security.TestAuthenticationFactory;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The OIDC connection test makes the Ambari server post Keycloak administrator credentials to a
 * URL. It must refuse callers who are not cluster administrators (403), only let cluster
 * administrators reach the identity providers configured for the cluster (refusing other hosts
 * with 400, since the Ambari web UI signs the user out on a 403), still let Ambari
 * administrators test an identity provider before it is saved (the OIDC wizard does so on a first
 * enable), and never be turned into a request to another path or host.
 */
public class ClusterOidcTestServiceTest {

  private static final String CLUSTER_NAME = "c1";

  /**
   * The cluster resource id {@code TestAuthenticationFactory} grants its cluster-level roles on.
   */
  private static final long CLUSTER_RESOURCE_ID = 4L;

  private HttpServer keycloak;
  private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
  private int tokenStatus = 200;
  private String tokenBody = "{\"access_token\":\"token\",\"token_type\":\"Bearer\"}";

  private Map<String, String> oidcEnv;
  private boolean oidcEnvSaved = true;
  private Properties ambariProperties;
  private Clusters clusters;

  @Before
  public void setUp() throws Exception {
    keycloak = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    keycloak.createContext("/", this::answer);
    keycloak.start();

    oidcEnv = new HashMap<>();
    oidcEnv.put("oidc_admin_url", "https://keycloak.example.com:8443");
    ambariProperties = new Properties();

    Config config = createNiceMock(Config.class);
    expect(config.getProperties()).andAnswer(() -> oidcEnv).anyTimes();
    Cluster cluster = createNiceMock(Cluster.class);
    expect(cluster.getResourceId()).andReturn(CLUSTER_RESOURCE_ID).anyTimes();
    expect(cluster.getDesiredConfigByType("oidc-env")).andAnswer(() -> oidcEnvSaved ? config : null).anyTimes();
    clusters = createNiceMock(Clusters.class);
    expect(clusters.getCluster(CLUSTER_NAME)).andReturn(cluster).anyTimes();
    expect(clusters.getCluster("unknown")).andThrow(new ClusterNotFoundException("unknown")).anyTimes();
    replay(config, cluster, clusters);
  }

  @After
  public void tearDown() {
    SecurityContextHolder.getContext().setAuthentication(null);
    keycloak.stop(0);
  }

  @Test
  public void testClusterUserIsRefused() throws Exception {
    allowLocalKeycloakInOidcEnv();
    assertRefusedWithoutContact(TestAuthenticationFactory.createClusterUser());
  }

  @Test
  public void testClusterOperatorIsRefused() throws Exception {
    allowLocalKeycloakInOidcEnv();
    assertRefusedWithoutContact(TestAuthenticationFactory.createClusterOperator());
  }

  @Test
  public void testServiceAdministratorIsRefused() throws Exception {
    allowLocalKeycloakInOidcEnv();
    assertRefusedWithoutContact(TestAuthenticationFactory.createServiceAdministrator());
  }

  @Test
  public void testAdministratorOfAnotherClusterIsRefused() throws Exception {
    allowLocalKeycloakInOidcEnv();
    assertRefusedWithoutContact(TestAuthenticationFactory.createClusterAdministrator("otherAdmin", CLUSTER_RESOURCE_ID + 1));
  }

  @Test
  public void testUnauthenticatedCallerIsRefused() throws Exception {
    allowLocalKeycloakInOidcEnv();
    assertRefusedWithoutContact(null);
  }

  @Test
  public void testUnknownCluster() throws Exception {
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());
    Response response = new ClusterOidcTestService("unknown", clusters, new Configuration(ambariProperties))
        .testOidcConnection(null, requestBody(localKeycloakUrl(), "master"));
    assertEquals(404, response.getStatus());
  }

  @Test
  public void testClusterAdministratorReachesKeycloakSavedInOidcEnv() throws Exception {
    allowLocalKeycloakInOidcEnv();
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createClusterAdministrator());

    Response response = service().testOidcConnection(null, requestBody(localKeycloakUrl(), "master"));

    assertEquals(200, response.getStatus());
    assertEquals("OK", json(response).get("status").getAsString());
    assertEquals(Arrays.asList("/realms/master/protocol/openid-connect/token"), requestedPaths);
  }

  @Test
  public void testKeycloakAllowedInAmbariPropertiesCanBeTestedBeforeSaving() throws Exception {
    ambariProperties.setProperty(Configuration.OIDC_CONNECTION_TEST_ALLOWED_HOSTS.getKey(), "keycloak.example.org, 127.0.0.1");
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createClusterAdministrator());

    Response response = service().testOidcConnection(null, requestBody(localKeycloakUrl() + "/auth/", "master"));

    assertEquals(200, response.getStatus());
    assertEquals("OK", json(response).get("status").getAsString());
    assertEquals(Arrays.asList("/auth/realms/master/protocol/openid-connect/token"), requestedPaths);
  }

  @Test
  public void testClusterAdministratorCannotTestHostThatIsNotAConfiguredIdentityProvider() throws Exception {
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createClusterAdministrator());

    Response response = service().testOidcConnection(null, requestBody(localKeycloakUrl(), "master"));

    assertRefusedWithoutSigningOut(response);
    String message = json(response).get("message").getAsString();
    assertTrue(message, message.contains("'127.0.0.1' is not an identity provider configured for cluster 'c1'"));
    assertTrue(message, message.contains("only an Ambari administrator can test it before it is saved"));
    assertTrue(message, message.contains(Configuration.OIDC_CONNECTION_TEST_ALLOWED_HOSTS.getKey()));
    assertTrue(requestedPaths.isEmpty());
  }

  @Test
  public void testAmbariAdministratorCanTestKeycloakOnFirstEnable() throws Exception {
    // The OIDC wizard tests the admin URL before it saves oidc-env for the first time.
    oidcEnvSaved = false;
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());

    Response response = service().testOidcConnection(null, requestBody(localKeycloakUrl(), "master"));

    assertEquals(200, response.getStatus());
    assertEquals("OK", json(response).get("status").getAsString());
    assertEquals(Arrays.asList("/realms/master/protocol/openid-connect/token"), requestedPaths);
  }

  @Test
  public void testAmbariAdministratorCanTestNewKeycloakHostBeforeSavingIt() throws Exception {
    // oidc-env still points at the previous identity provider.
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());

    Response response = service().testOidcConnection(null, requestBody(localKeycloakUrl(), "master"));

    assertEquals(200, response.getStatus());
    assertEquals("OK", json(response).get("status").getAsString());
  }

  @Test
  public void testClusterAdministratorCannotTestKeycloakOnFirstEnable() throws Exception {
    oidcEnvSaved = false;
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createClusterAdministrator());

    Response response = service().testOidcConnection(null, requestBody(localKeycloakUrl(), "master"));

    assertRefusedWithoutSigningOut(response);
    assertTrue(json(response).get("message").getAsString(),
        json(response).get("message").getAsString().contains("only an Ambari administrator can test it before it is saved"));
    assertTrue(requestedPaths.isEmpty());
  }

  @Test
  public void testRealmCannotRedirectTheRequestToAnotherPath() throws Exception {
    allowLocalKeycloakInOidcEnv();
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());

    for (String adminRealm : Arrays.asList("../../admin", "master?x=", "master#", "mas/ter", "master%2F..")) {
      Response response = service().testOidcConnection(null, requestBody(localKeycloakUrl(), adminRealm));
      assertEquals(adminRealm, 400, response.getStatus());
    }
    assertTrue(requestedPaths.isEmpty());
  }

  @Test
  public void testAdminUrlMustBeAPlainBaseUrl() throws Exception {
    allowLocalKeycloakInOidcEnv();
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());

    String base = localKeycloakUrl();
    for (String adminUrl : Arrays.asList(base + "/?next=", base + "/#fragment", base.replace("http://", "http://user:pw@"),
        base.replace("http://", "ftp://"), base + "/auth/../internal", base + "/a%2Fb", "file:///etc/passwd", "not a url")) {
      Response response = service().testOidcConnection(null, requestBody(adminUrl, "master"));
      assertEquals(adminUrl, 400, response.getStatus());
    }
    assertTrue(requestedPaths.isEmpty());
  }

  @Test
  public void testOnlyKeycloakErrorFieldsAreReported() throws Exception {
    allowLocalKeycloakInOidcEnv();
    tokenStatus = 401;
    tokenBody = "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\",\"internal\":\"do-not-leak\"}";
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());

    Response response = service().testOidcConnection(null, requestBody(localKeycloakUrl(), "master"));

    JsonObject json = json(response);
    assertEquals("ERROR", json.get("status").getAsString());
    String message = json.get("message").getAsString();
    assertTrue(message, message.contains("HTTP 401: Invalid user credentials"));
    assertFalse(message, message.contains("do-not-leak"));
  }

  @Test
  public void testRedirectsAreNotFollowed() throws Exception {
    allowLocalKeycloakInOidcEnv();
    tokenStatus = 302;
    tokenBody = "";
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());

    Response response = service().testOidcConnection(null, requestBody(localKeycloakUrl(), "master"));

    JsonObject json = json(response);
    assertEquals("ERROR", json.get("status").getAsString());
    assertTrue(json.get("message").getAsString(), json.get("message").getAsString().contains("HTTP 302"));
    assertEquals(Arrays.asList("/realms/master/protocol/openid-connect/token"), requestedPaths);
  }

  @Test
  public void testAllowedHostMatching() {
    HashSet<String> allowed = new HashSet<>(Arrays.asList("keycloak.example.com", "*.idp.example.org"));
    assertTrue(ClusterOidcTestService.isAllowedHost("keycloak.example.com", allowed));
    assertTrue(ClusterOidcTestService.isAllowedHost("KEYCLOAK.example.com", allowed));
    assertTrue(ClusterOidcTestService.isAllowedHost("sso.idp.example.org", allowed));
    assertFalse(ClusterOidcTestService.isAllowedHost("idp.example.org", allowed));
    assertFalse(ClusterOidcTestService.isAllowedHost("evilidp.example.org", allowed));
    assertFalse(ClusterOidcTestService.isAllowedHost("keycloak.example.com.evil.net", allowed));
    assertFalse(ClusterOidcTestService.isAllowedHost(null, allowed));
  }

  @Test
  public void testParseAdminUrlKeepsContextPath() {
    assertEquals("/auth", ClusterOidcTestService.parseAdminUrl("https://keycloak.example.com:8443/auth").getRawPath());
    try {
      ClusterOidcTestService.parseAdminUrl("https:///auth");
      fail("A URL without host must be rejected");
    } catch (IllegalArgumentException expected) {
      // rejected
    }
  }

  private void assertRefusedWithoutContact(Authentication authentication) throws Exception {
    SecurityContextHolder.getContext().setAuthentication(authentication);

    Response response = service().testOidcConnection(null, requestBody(localKeycloakUrl(), "master"));

    assertEquals(403, response.getStatus());
    assertEquals("ERROR", json(response).get("status").getAsString());
    assertTrue("Keycloak must not be contacted for an unauthorized caller", requestedPaths.isEmpty());
  }

  /**
   * A cluster administrator may open the OIDC wizard, and the Ambari web UI signs the user out on
   * any 403. So a host they may not test is refused with a status the wizard shows as a message.
   */
  private static void assertRefusedWithoutSigningOut(Response response) {
    assertEquals(400, response.getStatus());
    assertEquals("ERROR", json(response).get("status").getAsString());
  }

  private ClusterOidcTestService service() {
    return new ClusterOidcTestService(CLUSTER_NAME, clusters, new Configuration(ambariProperties));
  }

  private void allowLocalKeycloakInOidcEnv() {
    oidcEnv.put("oidc_admin_url", localKeycloakUrl());
  }

  private String localKeycloakUrl() {
    return "http://127.0.0.1:" + keycloak.getAddress().getPort();
  }

  private static String requestBody(String adminUrl, String adminRealm) {
    JsonObject body = new JsonObject();
    body.addProperty("admin_url", adminUrl);
    body.addProperty("admin_realm", adminRealm);
    body.addProperty("admin_username", "admin");
    body.addProperty("admin_password", "secret");
    body.addProperty("realm", "odp");
    return body.toString();
  }

  private static JsonObject json(Response response) {
    return new JsonParser().parse(String.valueOf(response.getEntity())).getAsJsonObject();
  }

  private void answer(HttpExchange exchange) throws IOException {
    requestedPaths.add(exchange.getRequestURI().getRawPath());
    byte[] bytes = tokenBody.getBytes(StandardCharsets.UTF_8);
    if (tokenStatus == 302) {
      exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + keycloak.getAddress().getPort() + "/elsewhere");
    }
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(tokenStatus, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
    exchange.close();
  }
}
