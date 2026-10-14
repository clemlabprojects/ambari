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

package org.apache.ambari.view.k8s.service;

import io.fabric8.kubernetes.api.model.APIGroupListBuilder;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.ambari.view.k8s.model.ClusterStats;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * An OpenShift account limited to its own projects opens the view without calls the platform refuses: no node
 * listing, no cluster-wide monitoring and no token for a service account outside its projects, and what was refused
 * once is not asked again straight away.
 */
@EnableKubernetesMockClient
class ProjectLimitedMonitoringTest {

  KubernetesMockServer server;
  KubernetesClient client;
  ViewContext ctx;
  KubernetesService svc;
  /** Stands for the cluster's Thanos Querier, for the tests that name it in openshift.thanos.url. */
  MockWebServer thanos;
  /** A client with another configuration than the mock server's, closed after the test. */
  KubernetesClient otherClient;

  @BeforeEach
  void setUp() {
    ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getInstanceName()).thenReturn("project-limited-monitoring-test");
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
    svc = new KubernetesService(ctx, client, mock(HelmClient.class), /*isConfigured=*/true);
    server.expect().get().withPath("/apis").andReturn(200, new APIGroupListBuilder()
        .addNewGroup().withName("route.openshift.io").endGroup()
        .addNewGroup().withName("project.openshift.io").endGroup().build()).always();
    server.expect().get().withPath("/apis/project.openshift.io/v1/projects").andReturn(200,
        "{\"apiVersion\":\"project.openshift.io/v1\",\"kind\":\"ProjectList\",\"metadata\":{},\"items\":["
            + "{\"apiVersion\":\"project.openshift.io/v1\",\"kind\":\"Project\",\"metadata\":{\"name\":\"team-a\"}}]}").always();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (thanos != null) thanos.shutdown();
    if (otherClient != null) otherClient.close();
  }

  /**
   * Starts a Thanos Querier that answers a query carrying {@code acceptedToken} with a value and refuses any other,
   * names it in openshift.thanos.url, and returns a service connected with {@code kubeconfigToken} (null for none).
   */
  private KubernetesService withThanosAccepting(String acceptedToken, String kubeconfigToken) throws Exception {
    thanos = new MockWebServer();
    thanos.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        return thanosAnswer(acceptedToken, request);
      }
    });
    thanos.start();
    doReturn("http://" + thanos.getHostName() + ":" + thanos.getPort() + "/thanos")
        .when(ctx).getAmbariProperty("openshift.thanos.url");
    KubernetesClient connected = client;
    if (kubeconfigToken != null) {
      otherClient = new KubernetesClientBuilder().withConfig(
          new ConfigBuilder(client.getConfiguration()).withOauthToken(kubeconfigToken).build()).build();
      connected = otherClient;
    }
    return new KubernetesService(ctx, connected, mock(HelmClient.class), /*isConfigured=*/true);
  }

  /** What the Thanos Querier answers a query when it accepts only {@code acceptedToken}. */
  private static MockResponse thanosAnswer(String acceptedToken, RecordedRequest request) {
    if (!("Bearer " + acceptedToken).equals(request.getHeader("Authorization"))) {
      return new MockResponse().setResponseCode(403).setBody("Forbidden");
    }
    return new MockResponse().setResponseCode(200).setBody(
        "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{},"
            + "\"value\":[1700000000,\"3\"]}]}}");
  }

  /** Stands for the view connecting again with another configuration while a call is under way. */
  private void rebuildClient(KubernetesService service) {
    try {
      Method m = KubernetesService.class.getDeclaredMethod("forgetAnswersAboutPreviousClient");
      m.setAccessible(true);
      m.invoke(service);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String forbiddenStatus() {
    return "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"status\":\"Failure\",\"code\":403,\"reason\":\"Forbidden\","
        + "\"message\":\"forbidden: User \\\"MYUSER\\\" cannot list at the cluster scope\"}";
  }

  private void forbidden(String path) {
    server.expect().get().withPath(path).andReturn(403, new StatusBuilder().withCode(403).withReason("Forbidden")
        .withMessage("forbidden: User \"MYUSER\" cannot list at the cluster scope").build()).always();
  }

  /** Answers every SelfSubjectAccessReview with {@code allowed}. */
  private void accessReviewsAnswer(boolean allowed) {
    accessReviewsAllowAllBut(allowed ? null : "");
  }

  /**
   * Allows every SelfSubjectAccessReview except those about {@code deniedResource} (null: none, empty: all), as the
   * API server answers for an account granted some rights only.
   */
  private void accessReviewsAllowAllBut(String deniedResource) {
    server.expect().post().withPath("/apis/authorization.k8s.io/v1/selfsubjectaccessreviews")
        .andReply(201, req -> {
          String body = req.getBody().clone().readUtf8();
          boolean allowed = deniedResource == null
              || !deniedResource.isEmpty() && !body.contains("\"resource\":\"" + deniedResource + "\"");
          return body.substring(0, body.lastIndexOf('}')) + ",\"status\":{\"allowed\":" + allowed + "}}";
        }).always();
  }

  private List<String> requests() throws InterruptedException {
    List<String> out = new ArrayList<>();
    RecordedRequest r;
    while ((r = server.takeRequest(100, TimeUnit.MILLISECONDS)) != null) {
      out.add(r.getMethod() + " " + r.getPath());
    }
    return out;
  }

  private static long count(List<String> calls, String fragment) {
    return calls.stream().filter(c -> c.contains(fragment)).count();
  }

  private String mintToken() throws Exception {
    Method m = KubernetesService.class.getDeclaredMethod("getMonitoringBearerToken");
    m.setAccessible(true);
    return (String) m.invoke(svc);
  }

  private void projectsOnly() {
    forbidden("/api/v1/nodes");
    forbidden("/api/v1/pods");
    forbidden("/apis/metrics.k8s.io/v1beta1/nodes");
    server.expect().get().withPath("/api/v1/namespaces/team-a/pods").andReturn(200,
        "{\"kind\":\"PodList\",\"apiVersion\":\"v1\",\"metadata\":{},\"items\":[]}").always();
  }

  @Test
  void aRefusedNodeListIsAnEmptyRestrictedPageAndIsNotAskedForAgain() throws Exception {
    forbidden("/api/v1/nodes");

    KubernetesService.NodePage first = svc.getNodes(50, 0);
    assertTrue(first.restricted());
    assertEquals(0, first.total());
    assertTrue(first.items().isEmpty());
    assertEquals(1, count(requests(), "/api/v1/nodes"));

    assertTrue(svc.getNodes(50, 0).restricted());
    assertEquals(0, count(requests(), "/api/v1/nodes"), "remembered, so the platform is not asked again");
  }

  @Test
  void theNodeTotalComesFromTheListThatWasFetched() throws Exception {
    server.expect().get().withPath("/api/v1/nodes").andReturn(200,
        "{\"kind\":\"NodeList\",\"apiVersion\":\"v1\",\"metadata\":{},\"items\":["
            + "{\"metadata\":{\"name\":\"n1\"},\"status\":{\"capacity\":{\"cpu\":\"4\",\"memory\":\"8Gi\"}}},"
            + "{\"metadata\":{\"name\":\"n2\"},\"status\":{\"capacity\":{\"cpu\":\"4\",\"memory\":\"8Gi\"}}}]}").always();
    forbidden("/apis/metrics.k8s.io/v1beta1/nodes");

    KubernetesService.NodePage page = svc.getNodes(1, 0);

    assertFalse(page.restricted());
    assertEquals(1, page.items().size());
    assertEquals(2, page.total());
    assertEquals(1, count(requests(), "GET /api/v1/nodes"), "one listing, not a second one for the total");
  }

  @Test
  void theDashboardOfAProjectLimitedAccountMakesNoRefusedClusterWideCalls() throws Exception {
    projectsOnly();
    accessReviewsAnswer(false);

    svc.getClusterStats(true);
    List<String> first = requests();

    assertEquals(1, count(first, "GET /api/v1/nodes"), first::toString);
    assertEquals(0, count(first, "/serviceaccounts/"), "no read of a service account outside the projects: " + first);
    assertEquals(0, count(first, "/token"), "no TokenRequest that would be refused: " + first);
    assertEquals(0, count(first, "clusterrolebindings"), first::toString);
    assertEquals(0, count(first, "thanos"), "no cluster monitoring query without a token to show it: " + first);

    svc.getClusterStats(true);
    List<String> second = requests();

    assertEquals(0, count(second, "GET /api/v1/nodes"), "nodes were refused a moment ago: " + second);
    assertEquals(0, count(second, "selfsubjectaccessreviews"), "the refused token is remembered: " + second);
    assertEquals(0, count(second, "/token"), second::toString);
    assertEquals(0, count(second, "clusterrolebindings"), second::toString);
    assertEquals(0, count(second, "thanos"), second::toString);
  }

  @Test
  void aProjectLimitedTokenKubeconfigIsNotSentToClusterMonitoringItMayNotRead() throws Exception {
    KubernetesService service = withThanosAccepting("x", "project-token");
    projectsOnly();
    accessReviewsAnswer(false);

    service.getClusterStats(true);
    List<String> first = requests();

    assertEquals(0, thanos.getRequestCount(), "the API server said ahead of time that it would be refused");
    assertEquals(0, count(first, "/token"), first::toString);

    service.getClusterStats(true);
    List<String> second = requests();

    assertEquals(0, thanos.getRequestCount(), second::toString);
    assertEquals(0, count(second, "selfsubjectaccessreviews"), "the answer is remembered: " + second);
  }

  @Test
  void aProjectLimitedTokenKubeconfigDoesNotUseTheServiceProxyOfClusterMonitoringItMayNotUse() throws Exception {
    KubernetesService service = withThanosAccepting("x", "project-token");
    doReturn(null).when(ctx).getAmbariProperty("openshift.thanos.url"); // the in-cluster Thanos Querier
    projectsOnly();
    accessReviewsAnswer(false);

    service.getClusterStats(true);
    service.getClusterStats(true);

    List<String> calls = requests();
    assertEquals(0, count(calls, "/proxy"), "no refused call through the API server's service proxy: " + calls);
    assertEquals(0, count(calls, "thanos"), calls::toString);
    assertEquals(0, thanos.getRequestCount());
    assertEquals(1, count(calls, "selfsubjectaccessreviews"), "asked once, then remembered: " + calls);
  }

  @Test
  void aProjectLimitedTokenKubeconfigAllowedToReadClusterMonitoringGetsClusterStats() throws Exception {
    KubernetesService service = withThanosAccepting("monitoring-viewer-token", "monitoring-viewer-token");
    projectsOnly();
    accessReviewsAllowAllBut("serviceaccounts"); // granted cluster-monitoring-view, but no token minting

    ClusterStats stats = service.getClusterStats(true);

    assertEquals("openshift-thanos", stats.getSource());
    assertEquals(2, thanos.getRequestCount());
    assertEquals(0, count(requests(), "/token"));
  }

  @Test
  void aKubeconfigCredentialThatMayReadMonitoringButNotBindClusterRolesStillGetsClusterStats() throws Exception {
    KubernetesService service = withThanosAccepting("monitoring-viewer-token", "monitoring-viewer-token");
    server.expect().get().withPath("/api/v1/nodes").andReturn(200,
        "{\"kind\":\"NodeList\",\"apiVersion\":\"v1\",\"metadata\":{},\"items\":["
            + "{\"metadata\":{\"name\":\"n1\"},\"status\":{\"capacity\":{\"cpu\":\"8\",\"memory\":\"16Gi\"}}}]}").always();
    server.expect().get().withPath("/api/v1/pods").andReturn(200,
        "{\"kind\":\"PodList\",\"apiVersion\":\"v1\",\"metadata\":{},\"items\":[]}").always();
    // Its credential may read monitoring (get namespaces cluster-wide), but it may not create ClusterRoleBindings.
    accessReviewsAllowAllBut("clusterrolebindings");

    ClusterStats stats = service.getClusterStats(true);

    assertEquals("openshift-thanos", stats.getSource());
    assertEquals(3.0, stats.getCpu().getUsed(), 0.0001);
    assertEquals(2, thanos.getRequestCount(), "one query for CPU and one for memory");
  }

  @Test
  void aProjectLimitedAccountWithAMonitoringServiceAccountOfItsOwnStillGetsClusterStats() throws Exception {
    KubernetesService service = withThanosAccepting("minted-token", null);
    doReturn("team-a/kdps-monitoring").when(ctx).getAmbariProperty("openshift.monitoring.serviceaccount");
    projectsOnly();
    accessReviewsAllowAllBut("clusterrolebindings"); // its own project's token, but no cluster-wide RBAC
    server.expect().get().withPath("/api/v1/namespaces/team-a/serviceaccounts/kdps-monitoring").andReturn(200,
        "{\"apiVersion\":\"v1\",\"kind\":\"ServiceAccount\",\"metadata\":{\"name\":\"kdps-monitoring\","
            + "\"namespace\":\"team-a\"}}").always();
    forbidden("/apis/rbac.authorization.k8s.io/v1/clusterrolebindings/ambari-monitoring-viewer");
    server.expect().post().withPath("/api/v1/namespaces/team-a/serviceaccounts/kdps-monitoring/token").andReturn(201,
        "{\"apiVersion\":\"authentication.k8s.io/v1\",\"kind\":\"TokenRequest\",\"spec\":{},"
            + "\"status\":{\"token\":\"minted-token\",\"expirationTimestamp\":\"2099-01-01T00:00:00Z\"}}").always();

    ClusterStats stats = service.getClusterStats(true);

    assertEquals("openshift-thanos", stats.getSource());
    assertEquals(2, thanos.getRequestCount());
    List<String> calls = requests();
    assertEquals(1, count(calls, "/kdps-monitoring/token"), calls::toString);
    assertEquals(0, count(calls, "/clusterrolebindings"), "the binding is left to the platform team: " + calls);
  }

  @Test
  void anExpiredCredentialIsNotRememberedAsARefusalOfClusterMonitoring() throws Exception {
    KubernetesService service = withThanosAccepting("monitoring-viewer-token", "monitoring-viewer-token");
    AtomicInteger queries = new AtomicInteger();
    thanos.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        // The first query meets the token just before the login renews it.
        return queries.getAndIncrement() == 0 ? new MockResponse().setResponseCode(401).setBody("Unauthorized")
            : thanosAnswer("monitoring-viewer-token", request);
      }
    });
    projectsOnly();
    accessReviewsAllowAllBut("serviceaccounts"); // may read monitoring, may not mint a token

    service.getClusterStats(true);
    assertEquals(1, thanos.getRequestCount());

    ClusterStats stats = service.getClusterStats(true);
    assertEquals(3, thanos.getRequestCount(), "a 401 says nothing about the account's rights, so it is asked again");
    assertEquals("openshift-thanos", stats.getSource());
  }

  @Test
  void aTokenMintedWhileTheClientWasRebuiltIsNeitherKeptNorSent() throws Exception {
    accessReviewsAnswer(true);
    server.expect().post().withPath("/api/v1/namespaces/default/serviceaccounts").andReply(201, req -> req.getBody().clone().readUtf8()).always();
    server.expect().post().withPath("/apis/rbac.authorization.k8s.io/v1/clusterrolebindings").andReply(201, req -> req.getBody().clone().readUtf8()).always();
    AtomicInteger mints = new AtomicInteger();
    server.expect().post().withPath("/api/v1/namespaces/default/serviceaccounts/ambari-monitoring-viewer/token")
        .andReply(201, req -> {
          if (mints.getAndIncrement() == 0) {
            rebuildClient(svc); // a kubeconfig for another cluster is saved while the token is minted
          }
          return "{\"apiVersion\":\"authentication.k8s.io/v1\",\"kind\":\"TokenRequest\",\"spec\":{},"
              + "\"status\":{\"token\":\"token-" + mints.get() + "\",\"expirationTimestamp\":\"2099-01-01T00:00:00Z\"}}";
        }).always();

    assertNull(mintToken(), "the token may belong to the previous cluster");
    assertEquals(1, count(requests(), "/ambari-monitoring-viewer/token"));

    assertEquals("token-2", mintToken(), "minted again for the client in use");
    assertEquals(1, count(requests(), "/ambari-monitoring-viewer/token"));
    assertEquals("token-2", mintToken(), "and kept");
    assertEquals(0, count(requests(), "/ambari-monitoring-viewer/token"));
  }

  @Test
  void aRefusedMonitoringAnswerIsNotAskedForAgainWithinTheRefusalWindow() throws Exception {
    KubernetesService service = withThanosAccepting("monitoring-viewer-token", "a-project-token");
    projectsOnly();
    // The API server lets the credential through, yet Thanos refuses it (its rights changed in between).
    accessReviewsAllowAllBut("serviceaccounts");

    service.getClusterStats(true);
    assertEquals(1, thanos.getRequestCount(), "the memory query is not sent with a credential the CPU one was refused");
    assertEquals(0, count(requests(), "/token"));

    service.getClusterStats(true);
    assertEquals(1, thanos.getRequestCount(), "the refusal is remembered");
  }

  @Test
  void thereIsNoCallForTheMonitoringServiceAccountWhenTheAccountMayNotMintItsToken() throws Exception {
    accessReviewsAnswer(false);

    assertNull(mintToken());

    List<String> calls = requests();
    assertEquals(0, count(calls, "/serviceaccounts/"), "no read of a service account outside the projects: " + calls);
    assertEquals(0, count(calls, "clusterrolebindings"), calls::toString);
    assertEquals(0, count(calls, "/token"), "no TokenRequest that would be refused: " + calls);
  }

  @Test
  void anAccessReviewThatCouldNotBeMadeIsNotRememberedAsARefusal() throws Exception {
    server.expect().post().withPath("/apis/authorization.k8s.io/v1/selfsubjectaccessreviews")
        .andReturn(500, "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"status\":\"Failure\",\"code\":500,"
            + "\"message\":\"etcdserver: request timed out\"}").always();

    // A client that does not retry a server error itself, so that each check is one request.
    otherClient = new KubernetesClientBuilder().withConfig(
        new ConfigBuilder(client.getConfiguration()).withRequestRetryBackoffLimit(0).build()).build();
    svc = new KubernetesService(ctx, otherClient, mock(HelmClient.class), /*isConfigured=*/true);

    assertNull(mintToken());
    assertEquals(1, count(requests(), "selfsubjectaccessreviews"));

    assertNull(mintToken());
    List<String> later = requests();
    assertEquals(1, count(later, "selfsubjectaccessreviews"), "asked again, nothing was refused: " + later);
    assertEquals(0, count(later, "/token"), later::toString);
  }

  @Test
  void aRefusalMetByACallThatStartedBeforeTheClientWasRebuiltIsNotRemembered() throws Exception {
    server.expect().get().withPath("/api/v1/nodes").andReply(403, req -> {
      rebuildClient(svc); // a better kubeconfig is saved while the listing is under way
      return forbiddenStatus();
    }).always();

    assertTrue(svc.getNodes(50, 0).restricted());
    assertEquals(1, count(requests(), "/api/v1/nodes"));

    assertTrue(svc.getNodes(50, 0).restricted());
    assertEquals(1, count(requests(), "/api/v1/nodes"), "the refusal belonged to the previous configuration");
  }

  @Test
  void rebuildingTheClientForgetsTheRefusalsOfThePreviousOne() throws Exception {
    forbidden("/api/v1/nodes");
    accessReviewsAnswer(false);
    svc.getNodes(50, 0);
    assertNull(mintToken());
    requests();

    rebuildClient(svc);
    svc.getNodes(50, 0);
    assertNull(mintToken());

    List<String> calls = requests();
    assertEquals(1, count(calls, "/api/v1/nodes"), calls::toString);
    assertEquals(1, count(calls, "selfsubjectaccessreviews"), calls::toString);
  }

  @Test
  void aFailedTokenMintIsNotRetriedWithinTheRefusalWindow() throws Exception {
    accessReviewsAnswer(true);
    server.expect().post().withPath("/api/v1/namespaces/default/serviceaccounts").andReply(201, req -> req.getBody().clone().readUtf8()).always();
    server.expect().post().withPath("/apis/rbac.authorization.k8s.io/v1/clusterrolebindings").andReply(201, req -> req.getBody().clone().readUtf8()).always();
    server.expect().post().withPath("/api/v1/namespaces/default/serviceaccounts/ambari-monitoring-viewer/token")
        .andReturn(403, new StatusBuilder().withCode(403).withReason("Forbidden")
            .withMessage("forbidden: cannot create resource \"serviceaccounts/token\"").build()).always();

    assertNull(mintToken());
    assertEquals(1, count(requests(), "/ambari-monitoring-viewer/token"));

    assertNull(mintToken());
    assertNull(mintToken());
    List<String> later = requests();
    assertEquals(0, count(later, "/token"), "the refusal is remembered: " + later);
    assertEquals(0, count(later, "selfsubjectaccessreviews"), later::toString);
  }
}
