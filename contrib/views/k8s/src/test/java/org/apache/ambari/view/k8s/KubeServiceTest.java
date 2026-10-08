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

  @Test
  void permissionsEndpointUsesAuthHelper() throws Exception {
    when(ctx.getProperties()).thenReturn(Map.of());
    when(ctx.getUsername()).thenReturn("dave");

    KubeService api = new KubeService();
    api.setKubernetesService(k8s);
    Field f = KubeService.class.getDeclaredField("viewContext");
    f.setAccessible(true);
    f.set(api, ctx);

    Response r = api.getCurrentUserPermissions();
    assertEquals(200, r.getStatus());
    assertNotNull(r.getEntity());
  }

  private KubeService apiAsViewAdmin(java.nio.file.Path workDir) throws Exception {
    when(ctx.getUsername()).thenReturn("dave");
    when(ctx.getInstanceName()).thenReturn("reload-test");
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

  private static final String KUBECONFIG = "apiVersion: v1\nkind: Config\nclusters: []\ncontexts: []\nusers: []\n";

  private Response upload(KubeService api) {
    return api.uploadKubeconfig(null, null,
        new java.io.ByteArrayInputStream(KUBECONFIG.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  @Test
  void uploadingAKubeconfigRebuildsAnExistingConnection(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(k8s.forceReloadClient()).thenReturn(true);

    assertEquals(200, upload(api).getStatus());
    verify(k8s).forceReloadClient();
    verify(k8s, never()).reloadClientIfConfigured();
  }

  @Test
  void uploadThatCannotConnectSaysSo(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(k8s.forceReloadClient()).thenReturn(false);

    Response r = upload(api);
    assertEquals(400, r.getStatus());
    assertTrue(String.valueOf(((Map<?, ?>) r.getEntity()).get("error")).contains("could not connect"));
  }

  @Test
  void uploadDropsASelectedContextTheNewKubeconfigLacks(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(ctx.getInstanceData(anyString())).thenReturn("old-cluster");
    when(k8s.listAvailableContexts()).thenReturn(java.util.List.of(Map.of("name", "new-cluster")));
    when(k8s.forceReloadClient()).thenReturn(true);

    assertEquals(200, upload(api).getStatus());
    verify(ctx).removeInstanceData(anyString());
  }

  @Test
  void uploadKeepsASelectedContextTheNewKubeconfigHas(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(ctx.getInstanceData(anyString())).thenReturn("same-cluster");
    when(k8s.listAvailableContexts()).thenReturn(java.util.List.of(Map.of("name", "same-cluster")));
    when(k8s.forceReloadClient()).thenReturn(true);

    assertEquals(200, upload(api).getStatus());
    verify(ctx, never()).removeInstanceData(anyString());
  }

  @Test
  void selectingAContextRebuildsAnExistingConnection(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(k8s.forceReloadClient()).thenReturn(true);

    Response r = api.selectKubeconfigContext(Map.of("context", "other-cluster"));

    assertEquals(200, r.getStatus());
    verify(ctx).putInstanceData(anyString(), eq("other-cluster"));
    verify(k8s).forceReloadClient();
    verify(k8s, never()).reloadClientIfConfigured();
  }

  @Test
  void selectingAContextThatCannotConnectSaysSo(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
    KubeService api = apiAsViewAdmin(workDir);
    when(k8s.forceReloadClient()).thenReturn(false);

    Response r = api.selectKubeconfigContext(Map.of("context", "missing"));
    assertEquals(400, r.getStatus());
    assertTrue(String.valueOf(((Map<?, ?>) r.getEntity()).get("error")).contains("'missing' was saved"));
  }
}
