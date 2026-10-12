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

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.kube.KubeNamespace;
import org.apache.ambari.view.k8s.model.kube.KubePod;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.ws.rs.ForbiddenException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Read-only users browse only the namespaces of releases deployed from KDPS and cannot read pod logs or descriptions;
 * operators and admins keep the whole view.
 */
class WorkloadsResourceRoleTest {

  private static final String INSTANCE = "workloads-role-test";

  private final KubernetesService k8s = mock(KubernetesService.class);

  @BeforeEach
  void registerKubernetesService() throws Exception {
    instances().put(INSTANCE, k8s);
  }

  @AfterEach
  void unregisterKubernetesService() throws Exception {
    instances().remove(INSTANCE);
  }

  @SuppressWarnings("unchecked")
  private static ConcurrentMap<String, KubernetesService> instances() throws Exception {
    Field f = KubernetesService.class.getDeclaredField("INSTANCES");
    f.setAccessible(true);
    return (ConcurrentMap<String, KubernetesService>) f.get(null);
  }

  private static WorkloadsResource as(String user) throws Exception {
    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getProperties()).thenReturn(Map.of("view.admin.users", "lead", "view.operator.users", "ops"));
    when(ctx.getUsername()).thenReturn(user);
    when(ctx.getLoggedinUser()).thenReturn(user);
    when(ctx.getInstanceName()).thenReturn(INSTANCE);
    K8sReleaseEntity trino = new K8sReleaseEntity();
    trino.setNamespace("analytics");
    DataStore store = mock(DataStore.class);
    when(store.findAll(eq(K8sReleaseEntity.class), any())).thenReturn(List.of(trino));
    when(ctx.getDataStore()).thenReturn(store);
    return new WorkloadsResource(ctx, null);
  }

  private static KubeNamespace namespace(String name) {
    KubeNamespace n = new KubeNamespace();
    n.name = name;
    return n;
  }

  @Test
  void aReadOnlyUserOnlySeesTheNamespacesOfKdpsReleases() throws Exception {
    when(k8s.listNamespaces()).thenReturn(List.of(namespace("analytics"), namespace("kube-system"), namespace("ambari")));

    assertEquals(List.of("analytics"),
        as("audit").listNamespaces().stream().map(n -> n.name).collect(Collectors.toList()));
    assertEquals(3, as("ops").listNamespaces().size());
  }

  @Test
  void aReadOnlyUserCannotBrowseAnotherNamespace() throws Exception {
    KubePod pod = new KubePod();
    when(k8s.listPods("analytics", null)).thenReturn(List.of(pod));
    WorkloadsResource audit = as("audit");

    assertEquals(1, audit.listPods("analytics", null).size());
    assertThrows(ForbiddenException.class, () -> audit.listPods("ambari", null));
    assertThrows(ForbiddenException.class, () -> audit.listEvents("ambari", null));
    assertThrows(ForbiddenException.class, () -> audit.listConfigMaps("ambari"));
    verify(k8s, never()).listPods(eq("ambari"), any());
    verify(k8s, never()).listConfigMaps("ambari");
  }

  @Test
  void anAllNamespacesListingKeepsOnlyTheReadableRows() throws Exception {
    when(k8s.listConfigMaps(null)).thenReturn(List.of(
        Map.of("name", "trino-catalog", "namespace", "analytics"),
        Map.of("name", "ambari-db", "namespace", "ambari")));

    assertEquals(List.of("trino-catalog"),
        as("audit").listConfigMaps("*").stream().map(r -> r.get("name")).collect(Collectors.toList()));
    assertEquals(2, as("lead").listConfigMaps("*").size());
  }

  @Test
  void logsAndDescriptionsNeedTheOperatorRole() throws Exception {
    WorkloadsResource audit = as("audit");
    assertThrows(ForbiddenException.class, () -> audit.getPodLog("analytics", "trino-0", null, null));
    assertThrows(ForbiddenException.class, () -> audit.describePod("analytics", "trino-0"));
    assertThrows(ForbiddenException.class, () -> audit.describeService("analytics", "trino"));
    assertThrows(ForbiddenException.class, () -> audit.restartPod("analytics", "trino-0"));
    assertThrows(ForbiddenException.class, () -> audit.deletePod("analytics", "trino-0", 0));
    verifyNoInteractions(k8s);

    when(k8s.tailPodLog("ambari", "db-0", null, 200)).thenReturn("log line");
    assertEquals("log line", as("ops").getPodLog("ambari", "db-0", null, null));
    as("ops").restartPod("ambari", "db-0");
    verify(k8s).restartPod("ambari", "db-0");
  }
}
