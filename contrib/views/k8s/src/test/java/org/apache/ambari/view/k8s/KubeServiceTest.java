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
import org.apache.ambari.view.k8s.model.ClusterStats;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KubeServiceTest {

  @Mock KubernetesService k8s;
  @Mock ViewContext ctx;

  @Test
  void getClusterStatsReturns200AndBody() throws Exception {
    ClusterStats stats = new ClusterStats(null,null,null,null,null);
        when(k8s.getClusterStats(false)).thenReturn(stats);

    KubeService api = new KubeService();
    api.setKubernetesService(k8s);

    // inject mocked ViewContext via reflection (field is @Inject but no setter)
    Field f = KubeService.class.getDeclaredField("viewContext");
    f.setAccessible(true);
    f.set(api, ctx);

        Response r = api.getClusterStats(false);
    assertEquals(200, r.getStatus());
    assertEquals(stats, r.getEntity());

        verify(k8s).getClusterStats(false);
  }

  private KubeService api() throws Exception {
    KubeService api = new KubeService();
    api.setKubernetesService(k8s);
    Field f = KubeService.class.getDeclaredField("viewContext");
    f.setAccessible(true);
    f.set(api, ctx);
    return api;
  }

  @Test
  void aConfiguredViewThatIsNotConnectedIsNotAnsweredAsUnconfigured() throws Exception {
    when(k8s.getClusterStats(false)).thenThrow(new KubernetesService.NotConnectedException(
        "KDPS is not connected to the cluster: it could not connect with the saved configuration and tries again in 30 s."));

    Response r = api().getClusterStats(false);
    // 412, or that text, makes the UI show the first-run configuration page and stop probing the connection.
    assertEquals(503, r.getStatus());
    assertFalse(String.valueOf(r.getEntity()).contains("not configured with a kubeconfig"));
  }

  @Test
  void aViewThatWasNeverConfiguredIsStillAnsweredAsUnconfigured() throws Exception {
    when(k8s.getClusterStats(false)).thenThrow(new IllegalStateException("The view is not configured with a kubeconfig."));

    assertEquals(412, api().getClusterStats(false).getStatus());
  }

  @Test
  void permissionsEndpointUsesAuthHelper() throws Exception {
    when(ctx.getProperties()).thenReturn(Map.of());
    when(ctx.getUsername()).thenReturn("dave");

    KubeService api = new KubeService();
    api.setKubernetesService(k8s);
    Field f = KubeService.class.getDeclaredField("viewContext");
    f.setAccessible(true);
    f.set(api, ctx);

    Response r = api.getCurrentUserPermissions(null);
    assertEquals(200, r.getStatus());
    assertNotNull(r.getEntity());
  }

  /** The kubeconfig context selected for the view instance, as every user reads it. */
  private static final String SELECTED_CONTEXT = "kubeconfig.context";

  private InstanceSettings settings() {
    return new InstanceSettings(ctx);
  }

  private KubeService apiAsViewAdmin(java.nio.file.Path workDir) throws Exception {
    when(ctx.getUsername()).thenReturn("dave");
    when(ctx.getInstanceName()).thenReturn("reload-test");
    when(ctx.getDataStore()).thenReturn(new InMemoryDataStore());
    when(ctx.getProperties()).thenReturn(Map.of(
        "view.admin.users", "dave",
        "k8s.view.working.dir", workDir.toString()));
    KubeService api = new KubeService();
    api.setKubernetesService(k8s);
    Field f = KubeService.class.getDeclaredField("viewContext");
    f.setAccessible(true);
    f.set(api, ctx);
    return api;
  }

  private static final String KUBECONFIG = kubeconfigWithContexts("only");

  private Response upload(KubeService api) {
    return upload(api, KUBECONFIG);
  }

  private Response upload(KubeService api, String content) {
    return api.uploadKubeconfig(null, null,
        new java.io.ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  /** A kubeconfig declaring the given contexts (current-context = the first). */
  private static String kubeconfigWithContexts(String... names) {
    StringBuilder b = new StringBuilder("apiVersion: v1\nkind: Config\ncurrent-context: " + names[0] + "\nclusters:\n");
    for (String n : names) b.append("- name: ").append(n).append("\n  cluster:\n    server: https://").append(n).append(":6443\n");
    b.append("contexts:\n");
    for (String n : names) b.append("- name: ").append(n).append("\n  context:\n    cluster: ").append(n).append("\n    user: u\n");
    b.append("users:\n- name: u\n  user:\n    token: t\n");
    return b.toString();
  }

  private void reconnects() {
    when(k8s.forceReloadClient()).thenReturn(true);
    when(k8s.pingCluster()).thenReturn(org.apache.ambari.view.k8s.model.ConnectionHealth.connected());
  }

  private static String error(Response r) {
    return String.valueOf(((Map<?, ?>) r.getEntity()).get("error"));
  }

  @Test
  void uploadingAKubeconfigRebuildsAnExistingConnection(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    reconnects();

    assertEquals(200, upload(api).getStatus());
    verify(k8s).forceReloadClient();
    verify(k8s, never()).reloadClientIfConfigured();
  }

  @Test
  void uploadFromWhichNoClientCanBeBuiltSaysSo(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(k8s.forceReloadClient()).thenReturn(false);

    Response r = upload(api);
    assertEquals(400, r.getStatus());
    assertTrue(error(r).contains("could not build a connection"), error(r));
  }

  @Test
  void uploadThatCannotReachTheClusterSaysWhy(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(k8s.forceReloadClient()).thenReturn(true);
    when(k8s.pingCluster()).thenReturn(org.apache.ambari.view.k8s.model.ConnectionHealth.unauthenticated("token expired"));

    Response r = upload(api);
    assertEquals(503, r.getStatus());
    assertTrue(error(r).startsWith("The kubeconfig was saved, but KDPS could not connect with it"), error(r));
  }

  @Test
  void uploadDropsASelectedContextTheNewKubeconfigLacks(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    settings().put(SELECTED_CONTEXT, "old-cluster");
    reconnects();

    assertEquals(200, upload(api, kubeconfigWithContexts("new-cluster")).getStatus());
    assertNull(settings().get(SELECTED_CONTEXT));
  }

  @Test
  void uploadKeepsASelectedContextTheNewKubeconfigHas(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    settings().put(SELECTED_CONTEXT, "same-cluster");
    reconnects();

    assertEquals(200, upload(api, kubeconfigWithContexts("same-cluster", "other")).getStatus());
    assertEquals("same-cluster", settings().get(SELECTED_CONTEXT));
  }

  @Test
  void selectingAContextRebuildsAnExistingConnection(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(k8s.listAvailableContexts()).thenReturn(java.util.List.of(Map.of("name", "other-cluster")));
    reconnects();

    Response r = api.selectKubeconfigContext(null, Map.of("context", "other-cluster"));

    assertEquals(200, r.getStatus());
    assertEquals("other-cluster", settings().get(SELECTED_CONTEXT));
    verify(k8s).forceReloadClient();
    verify(k8s, never()).reloadClientIfConfigured();
  }

  @Test
  void selectingAContextMissingFromTheKubeconfigIsRefusedAndNotSaved(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(k8s.listAvailableContexts()).thenReturn(java.util.List.of(Map.of("name", "other-cluster")));

    Response r = api.selectKubeconfigContext(null, Map.of("context", "missing"));
    assertEquals(400, r.getStatus());
    assertEquals("Context 'missing' is not in the uploaded kubeconfig.", error(r));
    assertNull(settings().get(SELECTED_CONTEXT));
    verify(k8s, never()).forceReloadClient();
  }

  @Test
  void selectingAContextThatCannotReachTheClusterSaysWhy(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(k8s.listAvailableContexts()).thenReturn(java.util.List.of(Map.of("name", "other-cluster")));
    when(k8s.forceReloadClient()).thenReturn(true);
    when(k8s.pingCluster()).thenReturn(org.apache.ambari.view.k8s.model.ConnectionHealth.unreachable("connection refused"));

    Response r = api.selectKubeconfigContext(null, Map.of("context", "other-cluster"));
    assertEquals(503, r.getStatus());
    assertTrue(error(r).startsWith("Context 'other-cluster' was saved, but KDPS could not connect with it"), error(r));
  }

  @Test
  void aFileThatIsNotAKubeconfigIsRefusedBeforeReplacingTheSavedOne(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);

    Response r = upload(api, "this: [is not: a kubeconfig");
    assertEquals(400, r.getStatus());
    assertTrue(error(r).contains("not a valid kubeconfig"), error(r));
    verifyNoInteractions(k8s);
    try (var files = java.nio.file.Files.walk(workDir)) {
      assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("kubeconfig")), "nothing was saved");
    }
  }

  @Test
  void multiContextKubeconfigWhoseCurrentContextIsUnreachableStillLetsTheOperatorChoose(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(k8s.forceReloadClient()).thenReturn(true);
    when(k8s.pingCluster()).thenReturn(org.apache.ambari.view.k8s.model.ConnectionHealth.unreachable("connection refused"));

    Response r = upload(api, kubeconfigWithContexts("kind-local", "prod-openshift"));
    assertEquals(200, r.getStatus(), "saved: the context picker follows");
    String warning = String.valueOf(((Map<?, ?>) r.getEntity()).get("warning"));
    assertTrue(warning.contains("2 contexts: choose the one to use"), warning);
  }

  @Test
  void multiContextKubeconfigWithAChosenContextThatIsUnreachableIsReported(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    settings().put(SELECTED_CONTEXT, "prod-openshift");
    when(k8s.forceReloadClient()).thenReturn(true);
    when(k8s.pingCluster()).thenReturn(org.apache.ambari.view.k8s.model.ConnectionHealth.unreachable("connection refused"));

    assertEquals(503, upload(api, kubeconfigWithContexts("kind-local", "prod-openshift")).getStatus());
  }

  @Test
  void selectingAContextWhenTheSavedKubeconfigIsUnreadableSaysSo(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(k8s.listAvailableContexts()).thenThrow(new IllegalStateException("bad yaml"));

    Response r = api.selectKubeconfigContext(null, Map.of("context", "x"));
    assertEquals(400, r.getStatus());
    assertEquals("The saved kubeconfig cannot be read; upload it again.", error(r));
  }

  @Test
  void otherYamlIsNotTakenForAKubeconfig(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);

    Response values = upload(api, "replicaCount: 2\nimage:\n  tag: latest\n");
    Response secret = upload(api, "apiVersion: v1\nkind: Secret\nmetadata:\n  name: x\n");
    Response noCluster = upload(api, "apiVersion: v1\nkind: Config\nclusters: []\ncontexts: []\nusers: []\n");
    assertEquals(400, values.getStatus());
    assertEquals(400, secret.getStatus());
    assertEquals(400, noCluster.getStatus());
    verifyNoInteractions(k8s);
  }

  @Test
  void oversizedUploadIsRefused(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);

    Response r = upload(api, "# " + "x".repeat(1024 * 1024 + 10));
    assertEquals(400, r.getStatus());
    assertTrue(error(r).contains("too large"), error(r));
    verifyNoInteractions(k8s);
  }

  @Test
  void anOperatorFindsTheViewConfiguredByAnAdministrator(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    org.apache.ambari.view.k8s.store.PerUserViewContext view =
        new org.apache.ambari.view.k8s.store.PerUserViewContext("configured-test", workDir);
    java.nio.file.Path kubeconfig = java.nio.file.Files.writeString(workDir.resolve("kubeconfig.yaml.enc"), "encrypted");
    view.as("alice");
    new org.apache.ambari.view.k8s.service.ViewConfigurationService(view.context).saveKubeconfigPath(kubeconfig.toString());

    view.as("ops1");
    KubeService api = new KubeService();
    api.setKubernetesService(k8s);
    Field f = KubeService.class.getDeclaredField("viewContext");
    f.setAccessible(true);
    f.set(api, view.context);

    assertEquals(200, api.checkIsViewConfigured(null).getStatus(), "ops1 is not sent to the configuration page");
  }

  // ---- connecting with the Ambari pod's own ServiceAccount ----

  /** The ServiceAccount kdps/ambari mounted into a pod, with the API server address in its environment. */
  private static org.apache.ambari.view.k8s.service.InClusterServiceAccount podServiceAccount(java.nio.file.Path mount)
      throws Exception {
    java.nio.file.Files.writeString(mount.resolve("token"), POD_TOKEN);
    java.nio.file.Files.writeString(mount.resolve("ca.crt"), "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n");
    java.nio.file.Files.writeString(mount.resolve("namespace"), "kdps");
    return new org.apache.ambari.view.k8s.service.InClusterServiceAccount(mount,
        Map.of("KUBERNETES_SERVICE_HOST", "172.30.0.1", "KUBERNETES_SERVICE_PORT", "443")::get);
  }

  /** A bound token of system:serviceaccount:kdps:ambari (unsigned: only the API server checks signatures). */
  private static final String POD_TOKEN = jwtWithClaims("{\"sub\":\"system:serviceaccount:kdps:ambari\","
      + "\"kubernetes.io\":{\"namespace\":\"kdps\",\"serviceaccount\":{\"name\":\"ambari\"}}}");

  private static String jwtWithClaims(String claims) {
    java.util.Base64.Encoder url = java.util.Base64.getUrlEncoder().withoutPadding();
    return url.encodeToString("{\"alg\":\"RS256\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "."
        + url.encodeToString(claims.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".c2ln";
  }

  private KubeService apiInPod(java.nio.file.Path workDir, org.apache.ambari.view.k8s.service.InClusterServiceAccount pod)
      throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    api.setConfigService(new org.apache.ambari.view.k8s.service.ViewConfigurationService(ctx, pod));
    return api;
  }

  @Test
  void connectingWithThePodServiceAccountSavesTheModeForEveryoneAndReconnects(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir,
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path mount) throws Exception {
    KubeService api = apiInPod(workDir, podServiceAccount(mount));
    reconnects();

    Response r = api.useInClusterServiceAccount(null);

    assertEquals(200, r.getStatus());
    Map<?, ?> body = (Map<?, ?>) r.getEntity();
    assertEquals("KDPS now connects with the ServiceAccount system:serviceaccount:kdps:ambari.", body.get("message"));
    assertEquals("system:serviceaccount:kdps:ambari", body.get("username"));
    assertTrue(String.valueOf(body.get("note")).contains("admin and monitoring-edit"), String.valueOf(body.get("note")));
    assertEquals("in-cluster", settings().get("auth.mode"));
    assertEquals("true", settings().get(InstanceSettings.CONFIGURED_KEY));
    verify(k8s).forceReloadClient();
    verify(k8s).pingCluster();
  }

  @Test
  void outsideAPodTheInClusterModeIsRefusedAndTheConfigurationIsUnchanged(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir,
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path mount) throws Exception {
    KubeService api = apiInPod(workDir, new org.apache.ambari.view.k8s.service.InClusterServiceAccount(mount, name -> null));
    settings().put("auth.mode", "openshift-login");

    Response r = api.useInClusterServiceAccount(null);

    assertEquals(400, r.getStatus());
    assertTrue(error(r).contains("not running in a Kubernetes or OpenShift pod"), error(r));
    assertTrue(error(r).endsWith("The previous configuration is unchanged."), error(r));
    assertEquals("openshift-login", settings().get("auth.mode"));
    assertNull(settings().get(InstanceSettings.CONFIGURED_KEY));
    verifyNoInteractions(k8s);
  }

  @Test
  void aPodWithoutItsTokenIsRefusedWithTheChartValueToSet(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir,
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path mount) throws Exception {
    org.apache.ambari.view.k8s.service.InClusterServiceAccount pod = podServiceAccount(mount);
    java.nio.file.Files.delete(mount.resolve("token")); // automountServiceAccountToken: false
    KubeService api = apiInPod(workDir, pod);

    Response r = api.useInClusterServiceAccount(null);

    assertEquals(400, r.getStatus());
    assertTrue(error(r).contains("inClusterServiceAccount.enabled=true"), error(r));
    assertNull(settings().get("auth.mode"));
    verifyNoInteractions(k8s);
  }

  @Test
  void inClusterModeThatCannotReachTheClusterSaysWhy(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir,
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path mount) throws Exception {
    KubeService api = apiInPod(workDir, podServiceAccount(mount));
    when(k8s.forceReloadClient()).thenReturn(true);
    when(k8s.pingCluster()).thenReturn(org.apache.ambari.view.k8s.model.ConnectionHealth.unauthenticated("token rejected"));

    Response r = api.useInClusterServiceAccount(null);

    assertEquals(503, r.getStatus());
    assertTrue(error(r).startsWith("The connection with the ServiceAccount system:serviceaccount:kdps:ambari was saved, "
        + "but KDPS could not connect with it"), error(r));
    assertEquals("in-cluster", settings().get("auth.mode"), "saved, as for the other modes");
  }

  @Test
  void inClusterModeFromWhichNoClientCanBeBuiltSaysWhatToCheck(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir,
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path mount) throws Exception {
    KubeService api = apiInPod(workDir, podServiceAccount(mount));
    when(k8s.forceReloadClient()).thenReturn(false);

    Response r = api.useInClusterServiceAccount(null);

    assertEquals(400, r.getStatus());
    assertTrue(error(r).contains("Check the ServiceAccount token and CA certificate mounted in the Ambari pod"), error(r));
    assertFalse(error(r).contains("kubeconfig"), error(r));
  }

  @Test
  void theStatusShowsWhoKdpsWouldActAsAndWhetherItDoes(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir,
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path mount) throws Exception {
    KubeService api = apiInPod(workDir, podServiceAccount(mount));

    Map<?, ?> before = (Map<?, ?>) api.getInClusterServiceAccount(null).getEntity();
    assertEquals(true, before.get("available"));
    assertNull(before.get("reason"));
    assertEquals(false, before.get("active"));
    assertEquals("kubeconfig", before.get("authMode"));
    assertEquals("system:serviceaccount:kdps:ambari", before.get("username"));
    assertEquals("ambari", before.get("serviceAccount"));
    assertEquals("kdps", before.get("namespace"));
    assertEquals("https://172.30.0.1:443", before.get("apiServer"));
    assertFalse(before.values().stream().anyMatch(v -> String.valueOf(v).contains(POD_TOKEN)), "never the token");

    reconnects();
    assertEquals(200, api.useInClusterServiceAccount(null).getStatus());
    Map<?, ?> after = (Map<?, ?>) api.getInClusterServiceAccount(null).getEntity();
    assertEquals(true, after.get("active"));
    assertEquals("in-cluster", after.get("authMode"));
  }

  @Test
  void theStatusOutsideAPodSaysWhyAndNamesNoOne(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir,
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path mount) throws Exception {
    KubeService api = apiInPod(workDir, new org.apache.ambari.view.k8s.service.InClusterServiceAccount(mount, name -> null));

    Map<?, ?> status = (Map<?, ?>) api.getInClusterServiceAccount(null).getEntity();
    assertEquals(false, status.get("available"));
    assertTrue(String.valueOf(status.get("reason")).contains("not running in a Kubernetes or OpenShift pod"));
    assertEquals(false, status.get("active"));
    assertFalse(status.containsKey("username"));
  }

  @Test
  void theAuthModeSwitchChecksThePodLikeTheConnectButton(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir,
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path mount) throws Exception {
    KubeService outside = apiInPod(workDir, new org.apache.ambari.view.k8s.service.InClusterServiceAccount(mount, name -> null));
    assertEquals(400, outside.setAuthMode(null, Map.of("mode", "in-cluster")).getStatus());
    assertNull(settings().get("auth.mode"));

    KubeService inPod = apiInPod(workDir, podServiceAccount(mount));
    reconnects();
    assertEquals(200, inPod.setAuthMode(null, Map.of("mode", "in-cluster")).getStatus());
    assertEquals("in-cluster", settings().get("auth.mode"));
    assertEquals("true", settings().get(InstanceSettings.CONFIGURED_KEY));
  }

  @Test
  @SuppressWarnings("unchecked")
  void preflightAsksWhetherTheAccountMayGrantEachServiceSccInTheTargetProject(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(k8s.isOpenShiftCluster()).thenReturn(true);
    when(k8s.canGrantScc("data", "anyuid")).thenReturn(true);
    when(k8s.canGrantScc("data", "nonroot-v2")).thenReturn(false);

    Map<String, Object> out = (Map<String, Object>) api.preflight(null, "data").getEntity();

    Map<String, Object> scc = ((java.util.List<Map<String, Object>>) out.get("checks")).stream()
        .filter(c -> "Security (SCC)".equals(c.get("group"))).findFirst().orElseThrow();
    assertEquals(false, scc.get("allowed"), "nonroot-v2 may not be granted");
    assertEquals(true, scc.get("advisory"));
    String remediation = (String) scc.get("remediation");
    assertTrue(remediation.contains("oc -n data create rolebinding kdps-grant-scc"), remediation);
    assertTrue(remediation.contains("--resource-name=system:openshift:scc:nonroot-v2"), remediation);
    assertTrue(remediation.contains("oc adm policy add-scc-to-user <scc> -z <serviceaccount> -n data"), remediation);
    assertFalse(String.valueOf(scc.get("note")).contains("does not grant"), String.valueOf(scc.get("note")));
    verify(k8s).canGrantScc("data", "anyuid");
    verify(k8s).canGrantScc("data", "nonroot-v2");
  }

  @Test
  @SuppressWarnings("unchecked")
  void preflightReportsCreatingClusterRolesAsAdvisoryForAProjectLimitedAccount(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    // A project admin: everything in its own project, nothing at the cluster scope.
    when(k8s.canI(eq("create"), anyString(), anyString(), isNull(), isNull(), eq("data"))).thenReturn(true);
    when(k8s.canI("create", "rbac.authorization.k8s.io", "clusterroles", null, null, null)).thenReturn(false);

    Map<String, Object> out = (Map<String, Object>) api.preflight(null, "data").getEntity();

    Map<String, Object> clusterRbac = ((java.util.List<Map<String, Object>>) out.get("checks")).stream()
        .filter(c -> "Create ClusterRoles/ClusterRoleBindings".equals(c.get("name"))).findFirst().orElseThrow();
    assertEquals(false, clusterRbac.get("allowed"));
    assertEquals(true, clusterRbac.get("advisory"));
    assertTrue(String.valueOf(clusterRbac.get("remediation")).contains("scheduling.userScheduler.enabled"),
        String.valueOf(clusterRbac.get("remediation")));
    assertEquals(true, out.get("allOk"), "advisory: a project-limited account can still deploy the KDPS services");
  }

  @Test
  @SuppressWarnings("unchecked")
  void preflightAsksForBothClusterRolesAndClusterRoleBindings(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(k8s.canI("create", "rbac.authorization.k8s.io", "clusterroles", null, null, null)).thenReturn(true);
    when(k8s.canI("create", "rbac.authorization.k8s.io", "clusterrolebindings", null, null, null)).thenReturn(true);

    Map<String, Object> out = (Map<String, Object>) api.preflight(null, "data").getEntity();

    Map<String, Object> clusterRbac = ((java.util.List<Map<String, Object>>) out.get("checks")).stream()
        .filter(c -> "Cluster-wide RBAC".equals(c.get("group"))).findFirst().orElseThrow();
    assertEquals(true, clusterRbac.get("allowed"));
    verify(k8s).canI("create", "rbac.authorization.k8s.io", "clusterrolebindings", null, null, null);
  }
}
