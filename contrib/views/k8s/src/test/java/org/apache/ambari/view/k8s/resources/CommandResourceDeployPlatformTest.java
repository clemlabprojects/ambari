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
import org.apache.ambari.view.k8s.service.deployment.DeploymentBackend;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A GitLab deploy sent straight to the API of a view connected to OpenShift is refused with a 400 that says why. */
class CommandResourceDeployPlatformTest {

  private static final String INSTANCE = "deploy-platform-test";

  @TempDir
  Path workDir;

  private final KubernetesService k8s = mock(KubernetesService.class);
  private final DeploymentBackend direct = mock(DeploymentBackend.class);

  @BeforeEach
  void registerCommandService() throws Exception {
    // The real submitDeploy, without the executors and singletons the constructor starts.
    CommandService commands = mock(CommandService.class, CALLS_REAL_METHODS);
    set(commands, "kubernetesService", k8s);
    Map<String, DeploymentBackend> backends = new ConcurrentHashMap<>();
    backends.put("DIRECT_HELM", direct);
    set(commands, "deploymentBackends", backends);
    registry(CommandService.class).put(INSTANCE, commands);
  }

  @AfterEach
  void unregister() throws Exception {
    registry(CommandService.class).remove(INSTANCE);
    registry(CommandLogService.class).remove(INSTANCE);
  }

  @Test
  void gitLabOnOpenShiftIsABadRequest() throws Exception {
    when(k8s.isOpenShiftCluster()).thenReturn(true);

    Response refused = resource().submitDeploy(gitLab(), null, null, null, headers(), uriInfo());

    assertEquals(400, refused.getStatus());
    assertEquals(Map.of("error", "GitLab is not supported on OpenShift in this release; register your existing Git"
        + " server under Git Repositories."), refused.getEntity());
    verify(k8s, never()).createNamespace(anyString());
    verify(direct, never()).apply(any(), any());
  }

  // ------------------------------------------------------------------------------------------------------------

  /** The resource as served to an operator. */
  private CommandResource resource() {
    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getProperties()).thenReturn(Map.of(
        "view.admin.users", "lead",
        "view.operator.users", "ops",
        "k8s.view.working.dir", workDir.toString()));
    when(ctx.getUsername()).thenReturn("ops");
    when(ctx.getLoggedinUser()).thenReturn("ops");
    when(ctx.getInstanceName()).thenReturn(INSTANCE);
    when(ctx.getViewName()).thenReturn("K8S-VIEW");
    ViewDefinition definition = mock(ViewDefinition.class);
    when(definition.getVersion()).thenReturn("1.0.0");
    when(ctx.getViewDefinition()).thenReturn(definition);
    when(ctx.getAmbariProperty(AmbariLoopbackUrlResolver.LOOPBACK_HOST_PROPERTY)).thenReturn("ambari.test");
    return new CommandResource(ctx, null, null);
  }

  private static HelmDeployRequest gitLab() {
    HelmDeployRequest request = new HelmDeployRequest();
    request.setChart("gitlab");
    request.setReleaseName("gitlab");
    request.setNamespace("gitlab");
    request.setServiceKey("GITLAB");
    request.setRepoId("charts");
    request.setValues(new LinkedHashMap<>());
    return request;
  }

  private static HttpHeaders headers() {
    HttpHeaders headers = mock(HttpHeaders.class);
    when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
    return headers;
  }

  private static UriInfo uriInfo() {
    UriInfo ui = mock(UriInfo.class);
    when(ui.getBaseUri()).thenReturn(URI.create("http://ambari.test:8080/api/v1/"));
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
