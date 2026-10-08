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

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * KEDA autoscaling on OpenShift for an account limited to its own projects: triggers read the project's monitoring
 * through the Thanos tenancy port, and the monitoring token needs nothing outside the project.
 */
@EnableKubernetesMockClient
class KedaThanosScopeTest {

  KubernetesMockServer server;
  KubernetesClient client;

  private static Map<String, Object> trigger(String serverAddress) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("serverAddress", serverAddress);
    metadata.put("authModes", "bearer");
    Map<String, Object> t = new LinkedHashMap<>();
    t.put("type", "prometheus");
    t.put("metadata", metadata);
    return t;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> metadata(Map<String, Object> values, int i) {
    var triggers = (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>) values.get("server")).get("keda")).get("triggers");
    return (Map<String, Object>) triggers.get(i).get("metadata");
  }

  private static Map<String, Object> values(Map<String, Object>... triggers) {
    Map<String, Object> keda = new LinkedHashMap<>();
    keda.put("triggers", new ArrayList<>(List.of(triggers)));
    keda.put("triggerAuthentication", Map.of("enabled", true, "secretName", "trino-thanos-token"));
    Map<String, Object> server = new LinkedHashMap<>();
    server.put("keda", keda);
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("server", server);
    v.put("serviceMonitor", Map.of("enabled", true));
    return v;
  }

  @Test
  void thanosTriggersMoveToTheTenancyPortOfTheProject() {
    Map<String, Object> v = values(
        trigger("https://thanos-querier.openshift-monitoring.svc:9091"),
        trigger("https://thanos-querier.openshift-monitoring.svc:9091"),
        trigger("http://prometheus.monitoring.svc:9090"));

    assertEquals(2, KedaThanosScope.scopeTriggersToNamespace(v, "team-a"));
    assertEquals("https://thanos-querier.openshift-monitoring.svc:9092", metadata(v, 0).get("serverAddress"));
    assertEquals("team-a", metadata(v, 0).get("namespace"));
    assertEquals("bearer", metadata(v, 0).get("authModes"));
    assertEquals("http://prometheus.monitoring.svc:9090", metadata(v, 2).get("serverAddress"), "other Prometheus left alone");
    assertNull(metadata(v, 2).get("namespace"));
    assertEquals(0, KedaThanosScope.scopeTriggersToNamespace(v, "team-a"), "running it again changes nothing");
  }

  @Test
  void valuesWithoutKedaAreUntouched() {
    assertEquals(0, KedaThanosScope.scopeTriggersToNamespace(new LinkedHashMap<>(), "team-a"));
    assertEquals(0, KedaThanosScope.scopeTriggersToNamespace(null, "team-a"));
    assertFalse(KedaThanosScope.triggerAuthenticationEnabled(Map.of()));
    assertFalse(KedaThanosScope.serviceMonitorsEnabled(Map.of("serviceMonitor", Map.of("enabled", false))));
    assertTrue(KedaThanosScope.triggerAuthenticationEnabled(values()));
    assertTrue(KedaThanosScope.serviceMonitorsEnabled(values()));
  }

  /** The posted SelfSubjectAccessReview with its answer added. */
  private static String answered(String review, boolean allowed) {
    return review.substring(0, review.lastIndexOf('}')) + ",\"status\":{\"allowed\":" + allowed + "}}";
  }

  /** Answers every SelfSubjectAccessReview: allowed unless it asks to create ClusterRoleBindings. */
  private void projectLimitedAccount() {
    server.expect().post().withPath("/apis/authorization.k8s.io/v1/selfsubjectaccessreviews")
        .andReply(201, req -> {
          String body = req.getBody().readUtf8();
          boolean allowed = !body.contains("clusterrolebindings");
          return answered(body, allowed);
        }).always();
  }

  private void namespacedObjectsCanBeCreated() {
    for (String p : List.of("/api/v1/namespaces/team-a/serviceaccounts", "/apis/rbac.authorization.k8s.io/v1/namespaces/team-a/roles",
        "/apis/rbac.authorization.k8s.io/v1/namespaces/team-a/rolebindings", "/api/v1/namespaces/team-a/secrets",
        "/apis/rbac.authorization.k8s.io/v1/clusterrolebindings")) {
      server.expect().post().withPath(p).andReply(201, req -> req.getBody().readUtf8()).always();
    }
  }

  private List<String> requests() throws InterruptedException {
    List<String> out = new ArrayList<>();
    RecordedRequest r;
    while ((r = server.takeRequest(100, TimeUnit.MILLISECONDS)) != null) out.add(r.getMethod() + " " + r.getPath());
    return out;
  }

  private KubernetesService service() {
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getInstanceName()).thenReturn("keda-scope-test");
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
    return new KubernetesService(ctx, client, mock(HelmClient.class), true);
  }

  @Test
  void projectScopedTokenNeedsNothingOutsideTheProject() throws Exception {
    projectLimitedAccount();
    namespacedObjectsCanBeCreated();
    KubernetesService svc = service();

    assertFalse(svc.canBindClusterRoles());
    assertNull(svc.ensureKedaThanosTokenSecret("team-a", "trino-thanos-token", "trino-thanos-token", true));

    List<String> calls = requests();
    assertTrue(calls.contains("POST /api/v1/namespaces/team-a/serviceaccounts"), calls::toString);
    assertTrue(calls.contains("POST /apis/rbac.authorization.k8s.io/v1/namespaces/team-a/roles"), calls::toString);
    assertTrue(calls.contains("POST /apis/rbac.authorization.k8s.io/v1/namespaces/team-a/rolebindings"), calls::toString);
    assertTrue(calls.contains("POST /api/v1/namespaces/team-a/secrets"), calls::toString);
    assertTrue(calls.stream().noneMatch(c -> c.contains("clusterrolebindings") && !c.contains("selfsubjectaccessreviews")), calls::toString);
    assertTrue(calls.stream().noneMatch(c -> c.contains("/clusterroles/")), "no cluster-wide read either: " + calls);
  }

  @Test
  void clusterScopedTokenStillRequiresTheClusterBinding() {
    projectLimitedAccount();
    KubernetesService svc = service();
    server.expect().get().withPath("/apis/rbac.authorization.k8s.io/v1/clusterroles/cluster-monitoring-view")
        .andReturn(200, "{\"kind\":\"ClusterRole\",\"apiVersion\":\"rbac.authorization.k8s.io/v1\",\"metadata\":{\"name\":\"cluster-monitoring-view\"}}").always();

    String problem = svc.ensureKedaThanosTokenSecret("team-a", "trino-thanos-token", "trino-thanos-token", false);
    assertNotNull(problem);
    assertTrue(problem.contains("oc create clusterrolebinding"), problem);
  }

  @Test
  void projectScopedInstructionsNeverAskForAClusterBinding() {
    // Nothing allowed at all: the instructions must still only list namespaced objects.
    server.expect().post().withPath("/apis/authorization.k8s.io/v1/selfsubjectaccessreviews")
        .andReply(201, req -> answered(req.getBody().readUtf8(), false)).always();
    String problem = service().ensureKedaThanosTokenSecret("team-a", "trino-thanos-token", "trino-thanos-token", true);
    assertNotNull(problem);
    assertFalse(problem.contains("clusterrolebinding"), problem);
    assertTrue(problem.contains("create role"), problem);
  }
}
