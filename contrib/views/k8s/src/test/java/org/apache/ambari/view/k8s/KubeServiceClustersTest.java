/**
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

package org.apache.ambari.view.k8s;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.InClusterServiceAccount;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ViewConfigurationService;
import org.apache.ambari.view.k8s.service.cluster.ClusterRef;
import org.apache.ambari.view.k8s.service.cluster.ClusterRegistry;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.KdpsSettingEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The clusters endpoints: every user reads the clusters, only administrators add, change and remove them and their
 * connections, a cluster the view does not know is answered 404, and no credential is ever returned.
 */
class KubeServiceClustersTest {

  @TempDir
  Path workDir;
  @TempDir
  Path mount;

  ViewContext ctx;
  InMemoryDataStore dataStore;
  InClusterServiceAccount pod;

  static final ClusterRef PROD = ClusterRef.of("prod");

  static final String KUBECONFIG = String.join("\n",
      "apiVersion: v1",
      "kind: Config",
      "current-context: c",
      "clusters:",
      "- name: c",
      "  cluster: {server: 'https://prod-oc:6443'}",
      "contexts:",
      "- name: c",
      "  context: {cluster: c, user: u}",
      "users:",
      "- name: u",
      "  user: {token: prod-secret-token}",
      "");

  /** A token of the ServiceAccount kdps/ambari, as the kubelet mounts it. */
  static String serviceAccountToken() {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String claims = "{\"aud\":[\"https://kubernetes.default.svc\"],\"exp\":1999999999,"
        + "\"iss\":\"https://kubernetes.default.svc\",\"kubernetes.io\":{\"namespace\":\"kdps\","
        + "\"serviceaccount\":{\"name\":\"ambari\",\"uid\":\"u-1\"}},\"sub\":\"system:serviceaccount:kdps:ambari\"}";
    return b64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
        + b64.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".c2ln";
  }

  @BeforeEach
  void setUp() throws Exception {
    dataStore = new InMemoryDataStore();
    ctx = mock(ViewContext.class);
    when(ctx.getInstanceName()).thenReturn("clusters-endpoints-test");
    when(ctx.getDataStore()).thenReturn(dataStore);
    when(ctx.getProperties()).thenReturn(Map.of(
        "view.admin.users", "lead",
        "view.operator.users", "ops",
        "k8s.view.working.dir", workDir.toString()));
    Files.writeString(mount.resolve("token"), serviceAccountToken());
    Files.writeString(mount.resolve("ca.crt"), "-----BEGIN CERTIFICATE-----\nMIIBfake\n-----END CERTIFICATE-----\n");
    Files.writeString(mount.resolve("namespace"), "kdps");
    pod = new InClusterServiceAccount(mount,
        Map.of("KUBERNETES_SERVICE_HOST", "172.30.0.1", "KUBERNETES_SERVICE_PORT", "443")::get);
  }

  @AfterEach
  void tearDown() {
    KubernetesService.forget(ctx, PROD);
  }

  private KubeService api(String user) throws Exception {
    when(ctx.getUsername()).thenReturn(user);
    KubeService api = new KubeService();
    Field f = KubeService.class.getDeclaredField("viewContext");
    f.setAccessible(true);
    f.set(api, ctx);
    api.setConfigService(new ViewConfigurationService(ctx, pod, ClusterRef.DEFAULT));
    api.setConfigService(PROD, new ViewConfigurationService(ctx, pod, PROD));
    return api;
  }

  private void addProd() {
    new ClusterRegistry(new InstanceSettings(ctx)).save(
        new org.apache.ambari.view.k8s.service.cluster.KubernetesCluster("prod", "Production", "", true));
  }

  private void uploadToProd() throws Exception {
    new ViewConfigurationService(ctx, pod, PROD).saveKubeconfigFile(
        new ByteArrayInputStream(KUBECONFIG.getBytes(StandardCharsets.UTF_8)), "kubeconfig.yaml");
  }

  private static void assertForbidden(Supplier<Response> call) {
    WebApplicationException e = assertThrows(WebApplicationException.class, call::get);
    assertInstanceOf(ForbiddenException.class, e);
  }

  private static void assertNotFound(Supplier<Response> call) {
    WebApplicationException e = assertThrows(WebApplicationException.class, call::get);
    assertInstanceOf(NotFoundException.class, e);
    assertEquals(404, e.getResponse().getStatus());
  }

  @Test
  @SuppressWarnings("unchecked")
  void everyUserReadsTheClustersAndNeverACredential() throws Exception {
    addProd();
    uploadToProd();

    for (String user : List.of("lead", "ops")) {
      Response r = api(user).listClusters();
      assertEquals(200, r.getStatus());
      List<Map<String, Object>> clusters = (List<Map<String, Object>>) r.getEntity();
      assertEquals(List.of("default", "prod"), clusters.stream().map(c -> c.get("id")).toList());
      Map<String, Object> prod = clusters.get(1);
      assertEquals(Set.of("id", "name", "description", "protected", "default", "configured", "authMode"), prod.keySet());
      assertEquals(true, prod.get("configured"));
      assertEquals(true, prod.get("protected"));
      assertEquals(false, clusters.get(0).get("configured"), "the default cluster has no connection yet");
      assertFalse(String.valueOf(clusters).contains("prod-secret-token"));
      assertFalse(String.valueOf(clusters).contains("prod-oc"));
    }
  }

  @Test
  void onlyAdministratorsChangeTheClustersAndTheirConnections() throws Exception {
    addProd();
    KubeService ops = api("ops");

    assertForbidden(() -> ops.saveCluster(null, "dev", Map.of("name", "Dev")));
    assertForbidden(() -> ops.removeCluster(null, "prod"));
    assertForbidden(() -> ops.uploadClusterKubeconfig(null, "prod",
        new ByteArrayInputStream(KUBECONFIG.getBytes(StandardCharsets.UTF_8))));
    assertForbidden(() -> ops.saveClusterOpenShiftLogin(null, "prod",
        Map.of("apiUrl", "https://prod-oc:6443", "username", "u", "password", "p")));
    assertForbidden(() -> ops.selectClusterKubeconfigContext(null, "prod", Map.of("context", "c")));
    assertForbidden(() -> ops.useClusterInClusterServiceAccount(null, "prod"));
    assertForbidden(() -> ops.setClusterAuthMode(null, "prod", Map.of("mode", "kubeconfig")));
    assertForbidden(() -> ops.listClusterKubeconfigContexts(null, "prod"));
    assertForbidden(() -> ops.clusterPreflight(null, "prod", "default"));
    assertTrue(new ClusterRegistry(new InstanceSettings(ctx)).find("dev").isEmpty());
    assertFalse(new ViewConfigurationService(ctx, pod, PROD).isConfigured());

    Response saved = api("lead").saveCluster(null, "dev", Map.of("name", "Dev", "protected", true));
    assertEquals(200, saved.getStatus());
    assertTrue(new ClusterRegistry(new InstanceSettings(ctx)).find("dev").orElseThrow().protectedCluster());
  }

  @Test
  void aClusterTheViewDoesNotKnowIsNotFound() throws Exception {
    KubeService lead = api("lead");

    assertNotFound(() -> lead.uploadClusterKubeconfig(null, "prod",
        new ByteArrayInputStream(KUBECONFIG.getBytes(StandardCharsets.UTF_8))));
    assertNotFound(() -> lead.getNamedClusterLiveness("prod"));
    assertNotFound(() -> lead.checkIsClusterConfigured("../default"));
    assertNotFound(() -> lead.removeCluster(null, "prod"));
    assertFalse(new ViewConfigurationService(ctx, pod, PROD).isConfigured(), "nothing saved for an unknown cluster");
    assertEquals(400, lead.saveCluster(null, "Prod", Map.of("name", "x")).getStatus());
  }

  @Test
  void theDefaultClusterCannotBeRemovedAndAnotherOneIsForgottenWithItsConnection() throws Exception {
    addProd();
    uploadToProd();
    KubeService lead = api("lead");

    assertEquals(400, lead.removeCluster(null, "default").getStatus());

    assertEquals(200, lead.removeCluster(null, "prod").getStatus());
    assertTrue(new ClusterRegistry(new InstanceSettings(ctx)).find("prod").isEmpty());
    assertTrue(dataStore.findAll(KdpsSettingEntity.class, null).stream()
        .noneMatch(e -> e.getId().startsWith("cluster.prod.")));
    assertFalse(Files.exists(workDir.resolve("clusters").resolve("prod")));
  }

  @Test
  void anotherClustersOpenShiftLoginNeedsTheCaOfItsApiServer() throws Exception {
    addProd();

    Response refused = api("lead").saveClusterOpenShiftLogin(null, "prod",
        Map.of("apiUrl", "https://prod-oc:6443", "username", "kdps", "password", "secret"));

    assertEquals(400, refused.getStatus());
    assertTrue(String.valueOf(refused.getEntity()).contains("CA certificate"));
    assertFalse(new ViewConfigurationService(ctx, pod, PROD).isConfigured(), "nothing saved");
  }

  @Test
  void aClusterWithReleasesCannotBeRemoved() throws Exception {
    addProd();
    uploadToProd();
    new org.apache.ambari.view.k8s.service.ReleaseMetadataService(ctx, PROD).recordInstallOrUpgrade("analytics",
        "trino", "TRINO", null, "clemlab-trino", null, "1.0", null, null, null, null, null, null, "DIRECT_HELM", null,
        null, null, null, null, null, null, null, null);

    Response refused = api("lead").removeCluster(null, "prod");

    assertEquals(409, refused.getStatus());
    assertTrue(String.valueOf(refused.getEntity()).contains("analytics/trino"));
    assertTrue(new ClusterRegistry(new InstanceSettings(ctx)).find("prod").isPresent());
    assertTrue(new ViewConfigurationService(ctx, pod, PROD).isConfigured(), "its connection is kept");
  }

  @Test
  void onlyOneClusterConnectsWithTheAmbariPodsServiceAccount() throws Exception {
    addProd();
    new ViewConfigurationService(ctx, pod, ClusterRef.DEFAULT).saveInClusterServiceAccount();

    Response refused = api("lead").useClusterInClusterServiceAccount(null, "prod");

    assertEquals(409, refused.getStatus());
    assertTrue(String.valueOf(refused.getEntity()).contains("Default cluster"));
    assertFalse(new ViewConfigurationService(ctx, pod, PROD).isConfigured(), "the previous configuration is unchanged");
  }
}
