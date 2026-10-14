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

package org.apache.ambari.view.k8s;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.apache.ambari.view.k8s.security.MasterKeyUnavailableException;
import org.apache.ambari.view.k8s.security.TestMasterKeys;
import org.apache.ambari.view.k8s.service.InClusterServiceAccount;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ViewConfigurationService;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** On an Ambari server without a master key, KDPS stores nothing and says what to do: HTTP 412. */
class KubeServiceMasterKeyTest {

  @TempDir
  Path workDir;

  private final KubernetesService k8s = mock(KubernetesService.class);
  private final InMemoryDataStore store = new InMemoryDataStore();

  private KubeService api(EncryptionService encryption) throws Exception {
    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getUsername()).thenReturn("dave");
    when(ctx.getInstanceName()).thenReturn("master-key-test");
    when(ctx.getDataStore()).thenReturn(store);
    when(ctx.getProperties()).thenReturn(Map.of(
        "view.admin.users", "dave",
        "k8s.view.working.dir", workDir.resolve("instance").toString()));
    KubeService api = new KubeService();
    api.setKubernetesService(k8s);
    Field f = KubeService.class.getDeclaredField("viewContext");
    f.setAccessible(true);
    f.set(api, ctx);
    api.setConfigService(new ViewConfigurationService(ctx,
        new InClusterServiceAccount(workDir.resolve("no-sa"), name -> null), encryption));
    return api;
  }

  private KubeService withoutMasterKey() throws Exception {
    return api(TestMasterKeys.encryptionWithoutKey());
  }

  private InstanceSettings settings() {
    return new InstanceSettings(store, TestMasterKeys.encryptionWithoutKey());
  }

  private static void assertMasterKeyRefusal(Response r) {
    assertEquals(412, r.getStatus());
    String body = String.valueOf(r.getEntity());
    assertTrue(body.contains(MasterKeyUnavailableException.CODE), body);
    assertTrue(body.contains("ambari-server setup-security"), body);
  }

  @Test
  void aKubeconfigUploadIsAnswered412AndNothingIsStored() throws Exception {
    String kubeconfig = "apiVersion: v1\nkind: Config\nclusters:\n- name: c\n  cluster:\n    server: https://c:6443\n";

    Response r = withoutMasterKey().uploadKubeconfig(null, null,
        new ByteArrayInputStream(kubeconfig.getBytes(StandardCharsets.UTF_8)));

    assertMasterKeyRefusal(r);
    assertFalse(Files.exists(workDir.resolve("instance")), "no file written");
    assertNull(settings().get(InstanceSettings.CONFIGURED_KEY));
    assertNull(settings().get("kubeconfig.path"));
    verifyNoInteractions(k8s);
  }

  @Test
  void anOpenShiftLoginIsAnswered412AndNothingIsStored() throws Exception {
    Response r = withoutMasterKey().saveOpenShiftLogin(null, Map.of(
        "apiUrl", "https://api.example.com:6443", "username", "admin", "password", "pw"));

    assertMasterKeyRefusal(r);
    assertNull(settings().get("openshift.username"));
    assertNull(settings().get(InstanceSettings.CONFIGURED_KEY));
  }

  @Test
  void aProxyPasswordIsAnswered412AndNothingIsStored() throws Exception {
    Response r = withoutMasterKey().saveProxySettings(null, Map.of(
        "enabled", true, "url", "http://proxy:3128", "username", "u", "password", "pw"));

    assertMasterKeyRefusal(r);
    assertNull(settings().get("proxy.url"));
  }

  @Test
  void aClusterCallThatNeedsTheMissingKeyIsAnswered412NotAsADisconnectedCluster() throws Exception {
    // A kubeconfig saved before the upgrade cannot be decrypted, so the service has no connection.
    when(k8s.getClusterStats(false)).thenThrow(new MasterKeyUnavailableException());

    assertMasterKeyRefusal(withoutMasterKey().getClusterStats(false));
  }

  @Test
  void theConfigurationPageLearnsAboutTheMissingKeyBeforeUploadingAnything() throws Exception {
    Response r = withoutMasterKey().getMasterKeyStatus(null);

    assertEquals(200, r.getStatus());
    Map<?, ?> body = (Map<?, ?>) r.getEntity();
    assertEquals(false, body.get("available"));
    assertEquals(MasterKeyUnavailableException.MESSAGE, body.get("message"));
    assertEquals(MasterKeyUnavailableException.CODE, body.get("code"));
  }

  @Test
  void withAMasterKeyTheStatusSaysSo() throws Exception {
    Response r = api(TestMasterKeys.encryptionWith("a-master-key")).getMasterKeyStatus(null);

    assertEquals(Map.of("available", true), r.getEntity());
  }

  @Test
  void thePreflightListsTheMissingKeyWithWhatToDo() throws Exception {
    Response r = withoutMasterKey().preflight(null, "team-a");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> checks = (List<Map<String, Object>>) ((Map<?, ?>) r.getEntity()).get("checks");
    Map<String, Object> masterKey = checks.stream().filter(c -> "KDPS".equals(c.get("group"))).findFirst().orElseThrow();
    assertEquals(false, masterKey.get("allowed"));
    assertEquals(MasterKeyUnavailableException.MESSAGE, masterKey.get("remediation"));
    assertEquals(false, ((Map<?, ?>) r.getEntity()).get("allOk"));
  }
}
