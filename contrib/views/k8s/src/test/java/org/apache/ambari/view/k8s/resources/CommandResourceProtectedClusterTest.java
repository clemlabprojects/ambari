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

package org.apache.ambari.view.k8s.resources;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.ViewDefinition;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.CommandLogService;
import org.apache.ambari.view.k8s.service.CommandService;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.service.cluster.ClusterRegistry;
import org.apache.ambari.view.k8s.service.cluster.KubernetesCluster;
import org.apache.ambari.view.k8s.service.cluster.ProtectedClusters;
import org.apache.ambari.view.k8s.service.deployment.DeploymentBackend;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.utils.AmbariLoopbackUrlResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A deploy of a release a protected cluster already runs is an upgrade there: it needs the cluster's name, as the
 * Releases page and the wizard ask the operator for it. A new release asks for nothing.
 */
class CommandResourceProtectedClusterTest {

  private static final String INSTANCE = "protected-deploy-test";

  @TempDir
  Path workDir;

  private final KubernetesService k8s = mock(KubernetesService.class);
  private final DeploymentBackend direct = mock(DeploymentBackend.class);
  private final InMemoryDataStore dataStore = new InMemoryDataStore();
  private ViewContext ctx;

  @BeforeEach
  void setUp() throws Exception {
    ctx = mock(ViewContext.class);
    when(ctx.getProperties()).thenReturn(Map.of(
        "view.admin.users", "lead",
        "view.operator.users", "ops",
        "k8s.view.working.dir", workDir.toString()));
    when(ctx.getUsername()).thenReturn("ops");
    when(ctx.getLoggedinUser()).thenReturn("ops");
    when(ctx.getInstanceName()).thenReturn(INSTANCE);
    when(ctx.getViewName()).thenReturn("K8S-VIEW");
    when(ctx.getDataStore()).thenReturn(dataStore);
    ViewDefinition definition = mock(ViewDefinition.class);
    when(definition.getVersion()).thenReturn("1.0.0");
    when(ctx.getViewDefinition()).thenReturn(definition);
    when(ctx.getAmbariProperty(AmbariLoopbackUrlResolver.LOOPBACK_HOST_PROPERTY)).thenReturn("ambari.test");

    CommandService commands = mock(CommandService.class, CALLS_REAL_METHODS);
    set(commands, "kubernetesService", k8s);
    Map<String, DeploymentBackend> backends = new ConcurrentHashMap<>();
    backends.put("DIRECT_HELM", direct);
    set(commands, "deploymentBackends", backends);
    registry(CommandService.class).put(INSTANCE, commands);

    new ClusterRegistry(new InstanceSettings(ctx)).save(new KubernetesCluster("default", "Production", "", true));
    new ReleaseMetadataService(ctx).recordInstallOrUpgrade("analytics", "trino", "TRINO", null, "clemlab-trino",
        "charts", "1.0", null, null, null, null, null, null, "DIRECT_HELM", null, null, null, null, null, null, null,
        null, null);
  }

  @AfterEach
  void unregister() throws Exception {
    registry(CommandService.class).remove(INSTANCE);
    registry(CommandLogService.class).remove(INSTANCE);
  }

  @Test
  void anUpgradeOnAProtectedClusterNeedsItsName() throws Exception {
    for (String typed : new String[] {null, "prod"}) {
      Response refused = new CommandResource(ctx, null, null).submitDeploy(request("trino"), "charts", null, null,
          headers(), uriInfo(typed));

      assertEquals(409, refused.getStatus());
      assertEquals(ProtectedClusters.NotConfirmedException.CODE, ((Map<?, ?>) refused.getEntity()).get("code"));
    }
    verify(direct, never()).apply(any(), any());

    Response confirmed = new CommandResource(ctx, null, null).submitDeploy(request("trino"), "charts", null, null,
        headers(), uriInfo("Production"));
    assertNotEquals(ProtectedClusters.NotConfirmedException.CODE, codeOf(confirmed));
  }

  @Test
  void aNewReleaseOnAProtectedClusterAsksForNothing() throws Exception {
    Response submitted = new CommandResource(ctx, null, null).submitDeploy(request("superset"), "charts", null, null,
        headers(), uriInfo(null));

    assertNotEquals(ProtectedClusters.NotConfirmedException.CODE, codeOf(submitted));
  }

  private static Object codeOf(Response response) {
    return response.getEntity() instanceof Map<?, ?> body ? body.get("code") : null;
  }

  private static HelmDeployRequest request(String release) {
    HelmDeployRequest request = new HelmDeployRequest();
    request.setChart("clemlab-trino");
    request.setReleaseName(release);
    request.setNamespace("analytics");
    request.setServiceKey("TRINO");
    request.setRepoId("charts");
    request.setValues(new LinkedHashMap<>());
    return request;
  }

  private static HttpHeaders headers() {
    HttpHeaders headers = mock(HttpHeaders.class);
    when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
    return headers;
  }

  private static UriInfo uriInfo(String confirmCluster) {
    UriInfo ui = mock(UriInfo.class);
    when(ui.getBaseUri()).thenReturn(URI.create("http://ambari.test:8080/api/v1/"));
    MultivaluedHashMap<String, String> query = new MultivaluedHashMap<>();
    if (confirmCluster != null) {
      query.add(ProtectedClusters.PARAM, confirmCluster);
    }
    when(ui.getQueryParameters()).thenReturn(query);
    return ui;
  }

  private static void set(CommandService service, String field, Object value) throws Exception {
    Field f = CommandService.class.getDeclaredField(field);
    f.setAccessible(true);
    f.set(service, value);
  }

  @SuppressWarnings("unchecked")
  private static <T> ConcurrentMap<String, T> registry(Class<T> type) throws Exception {
    Field f = type.getDeclaredField("INSTANCES");
    f.setAccessible(true);
    return (ConcurrentMap<String, T>) f.get(null);
  }
}
