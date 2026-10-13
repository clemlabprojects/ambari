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
  void selectingAContextOfASavedKubeconfigThatCannotBeUsedSaysWhatToChange(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(k8s.listAvailableContexts()).thenReturn(java.util.List.of(Map.of("name", "eks")));
    when(k8s.forceReloadClient()).thenReturn(false);
    String reason = "The saved kubeconfig cannot be used. User 'eks-admin' gets its credentials by running a command "
        + "(exec). KDPS does not run commands on the Ambari server: use a kubeconfig with a token or a client "
        + "certificate. Upload a new kubeconfig.";
    when(k8s.unusableConfiguration()).thenReturn(reason);

    Response r = api.selectKubeconfigContext(null, Map.of("context", "eks"));
    assertEquals(400, r.getStatus());
    assertEquals("Context 'eks' was saved, but KDPS could not build a connection from it. " + reason, error(r));
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
  void aKubeconfigWhoseUserRunsACommandIsRefusedAndNothingIsSaved(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    String withExec = KUBECONFIG.replace("    token: t\n",
        "    exec: {apiVersion: client.authentication.k8s.io/v1, command: /bin/sh, args: [-c, id]}\n");
    assertNotEquals(KUBECONFIG, withExec);

    Response r = upload(api, withExec);
    assertEquals(400, r.getStatus());
    assertTrue(error(r).contains("running a command (exec)"), error(r));
    assertTrue(error(r).contains("User 'u'"), "the user is named: " + error(r));
    assertTrue(error(r).contains("previous configuration is unchanged"), error(r));
    verifyNoInteractions(k8s);
    try (var files = java.nio.file.Files.walk(workDir)) {
      assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("kubeconfig")), "nothing was saved");
    }
  }

  @Test
  void aKubeconfigWhoseUserHasAnAuthProviderIsRefused(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    String withProvider = KUBECONFIG.replace("    token: t\n",
        "    auth-provider: {name: gcp, config: {cmd-path: /bin/sh}}\n");

    Response r = upload(api, withProvider);
    assertEquals(400, r.getStatus());
    assertTrue(error(r).contains("auth-provider"), error(r));
    verifyNoInteractions(k8s);
  }

  @Test
  void aKubeconfigWhoseUserReadsATokenFileIsRefusedAndNothingIsSaved(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    String withTokenFile = KUBECONFIG.replace("    token: t\n", "    tokenFile: /etc/ambari-server/conf/password.dat\n");
    assertNotEquals(KUBECONFIG, withTokenFile);

    Response r = upload(api, withTokenFile);
    assertEquals(400, r.getStatus());
    assertTrue(error(r).contains("tokenFile"), error(r));
    assertTrue(error(r).contains("token itself"), error(r));
    assertTrue(error(r).contains("previous configuration is unchanged"), error(r));
    verifyNoInteractions(k8s);
    try (var files = java.nio.file.Files.walk(workDir)) {
      assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("kubeconfig")), "nothing was saved");
    }
  }

  @Test
  void aKubeconfigWhoseUserReadsItsClientKeyFromAFileIsRefused(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    String withKeyFile = KUBECONFIG.replace("    token: t\n",
        "    client-certificate-data: Y2VydA==\n    client-key: /etc/ambari-server/keys/ca.key\n");
    assertNotEquals(KUBECONFIG, withKeyFile);

    Response r = upload(api, withKeyFile);
    assertEquals(400, r.getStatus());
    assertTrue(error(r).contains("User 'u'") && error(r).contains("(client-key)"), error(r));
    assertTrue(error(r).contains("client-key-data"), "says what to use instead: " + error(r));
    verifyNoInteractions(k8s);
  }

  @Test
  void aKubeconfigThatHidesACommandBehindAnAliasMergeKeyOrTagIsRefused(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    String exec = "{exec: {command: /bin/sh, args: [-c, id]}}";
    String noUsers = KUBECONFIG.substring(0, KUBECONFIG.indexOf("users:"));
    java.util.List<String> crafted = java.util.List.of(
        "x-hidden: &e " + exec + "\n" + noUsers + "users:\n- name: u\n  user: *e\n",
        "x-hidden: &e " + exec + "\n" + noUsers + "users:\n- name: u\n  user: {<<: *e}\n",
        // base64 of "users" and "user": Go's YAML decodes a !!binary key, so Helm would read them as those sections.
        noUsers + "!!binary dXNlcnM=:\n- name: u\n  user: " + exec + "\n",
        noUsers + "users:\n- name: u\n  !!binary dXNlcg==: " + exec + "\n",
        // a field the kubeconfig model does not know, whatever it resembles
        noUsers + "users:\n- name: u\n  user: {Exec: {command: /bin/sh}}\n");
    for (String kubeconfig : crafted) {
      Response r = upload(api, kubeconfig);
      assertEquals(400, r.getStatus(), kubeconfig);
      assertTrue(error(r).contains("(exec)") || error(r).contains("does not recognise"), error(r));
      assertTrue(error(r).contains("previous configuration is unchanged"), error(r));
    }
    verifyNoInteractions(k8s);
    try (var files = java.nio.file.Files.walk(workDir)) {
      assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("kubeconfig")), "nothing was saved");
    }
  }

  @Test
  void aTabIndentedJsonKubeconfigIsAccepted(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    // What jq --tab writes: the clients read it, although a YAML parser refuses a tab.
    KubeService api = apiAsViewAdmin(workDir);
    reconnects();
    String json = "{\n\t\"apiVersion\": \"v1\",\n\t\"kind\": \"Config\",\n\t\"current-context\": \"a\",\n"
        + "\t\"clusters\": [{\"name\": \"a\", \"cluster\": {\"server\": \"https://a:6443\"}}],\n"
        + "\t\"contexts\": [{\"name\": \"a\", \"context\": {\"cluster\": \"a\", \"user\": \"u\"}}],\n"
        + "\t\"users\": [{\"name\": \"u\", \"user\": {\"token\": \"t\"}}]\n}\n";

    Response r = upload(api, json);
    assertEquals(200, r.getStatus(), String.valueOf(r.getEntity()));
    verify(k8s).forceReloadClient();
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

  /** A refusal of the Kubernetes API for lack of rights, as an account limited to its own projects receives it. */
  private static io.fabric8.kubernetes.client.KubernetesClientException forbidden() {
    return new io.fabric8.kubernetes.client.KubernetesClientException(
        "secrets is forbidden: User \"kdps\" cannot get resource \"secrets\" in API group \"\" in the namespace "
            + "\"ambari-pki\"", 403, null);
  }

  @Test
  void aTruststoreChangeKdpsMayNotMakeIsRefusedWithWhatToDo(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir)
      throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(k8s.getClient()).thenThrow(forbidden());

    Response created = api.createTruststore(null, Map.of("name", "corp-ca",
        "caCertPem", org.apache.ambari.view.k8s.utils.TestCertificates.caPem("CN=Corporate Root CA")));
    assertEquals(403, created.getStatus());
    assertEquals(org.apache.ambari.view.k8s.service.PkiNamespaces.forbiddenMessage("ambari-pki"), error(created));

    Response flipped = api.setTruststoreDefault(null, "ambari-pki", "corp-ca-truststore", Map.of("default", true));
    assertEquals(403, flipped.getStatus());
    assertEquals(org.apache.ambari.view.k8s.service.PkiNamespaces.forbiddenMessage("ambari-pki"), error(flipped));

    Response deleted = api.deleteTruststore(null, "ambari-pki", "corp-ca-truststore");
    assertEquals(403, deleted.getStatus());
    assertEquals(org.apache.ambari.view.k8s.service.PkiNamespaces.forbiddenMessage("ambari-pki"), error(deleted));
  }

  @Test
  void aTruststoreOutsideTheTruststoreProjectCannotBeMadeDefaultNorDeleted(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);

    Response flipped = api.setTruststoreDefault(null, "team-a", "corp-ca-truststore", Map.of("default", true));
    assertEquals(400, flipped.getStatus());
    assertTrue(error(flipped).contains("is not in the truststore project ambari-pki"), error(flipped));

    Response deleted = api.deleteTruststore(null, "team-a", "corp-ca-truststore");
    assertEquals(400, deleted.getStatus());
    verify(k8s, never()).getClient();
  }

  @Test
  @SuppressWarnings("unchecked")
  void onlyTheTruststoresOfTheTruststoreProjectAreListedAsManaged() throws Exception {
    // A Secret labelled as a truststore in a workload project is neither selectable nor default: a deploy would not
    // trust it, so the Truststores tab and the wizard do not offer it as one.
    KubernetesService.TruststoreSummary registered = summary("ambari-pki", "corp-ca-truststore");
    KubernetesService.TruststoreSummary planted = summary("team-b", "partner-ca-truststore");
    when(k8s.listTruststores(null)).thenReturn(new java.util.ArrayList<>(java.util.List.of(registered, planted)));

    java.util.List<KubernetesService.TruststoreSummary> listed =
        (java.util.List<KubernetesService.TruststoreSummary>) api().listTruststores(null).getEntity();

    assertEquals(2, listed.size());
    assertTrue(listed.get(0).managed);
    assertTrue(listed.get(0).isDefault);
    assertFalse(listed.get(1).managed);
    assertFalse(listed.get(1).isDefault);
  }

  /** A truststore created in the Truststores tab and flagged default, as the listing reports it. */
  private static KubernetesService.TruststoreSummary summary(String namespace, String name) {
    KubernetesService.TruststoreSummary t = new KubernetesService.TruststoreSummary();
    t.namespace = namespace;
    t.name = name;
    t.managed = true;
    t.isDefault = true;
    return t;
  }

  @Test
  void theTruststoresPageLearnsWhereTruststoresAreWrittenAndWhetherKdpsMayWriteThere() throws Exception {
    when(k8s.canI("create", "", "secrets", null, null, "ambari-pki")).thenReturn(false);

    Response refused = api().truststoreRegistryStatus();
    assertEquals(200, refused.getStatus());
    Map<?, ?> body = (Map<?, ?>) refused.getEntity();
    assertEquals("ambari-pki", body.get("namespace"));
    assertEquals(false, body.get("canWrite"));
    assertEquals(org.apache.ambari.view.k8s.service.PkiNamespaces.forbiddenMessage("ambari-pki"), body.get("message"));

    when(k8s.canI("create", "", "secrets", null, null, "ambari-pki")).thenReturn(true);
    body = (Map<?, ?>) api().truststoreRegistryStatus().getEntity();
    assertEquals(true, body.get("canWrite"));
    assertFalse(body.containsKey("message"));
  }
}
