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

import io.fabric8.kubernetes.api.model.APIGroupBuilder;
import io.fabric8.kubernetes.api.model.APIGroupListBuilder;
import io.fabric8.kubernetes.api.model.APIResourceListBuilder;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.EventListBuilder;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link KubernetesService} pages keep working for an account limited to its own OpenShift projects: the wiring
 * between the service's listings and its {@link NamespaceScope}.
 */
@EnableKubernetesMockClient
class KubernetesServiceNamespaceScopeTest {

  KubernetesMockServer server;
  KubernetesClient client;
  KubernetesService svc;

  @BeforeEach
  void setUp() {
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getInstanceName()).thenReturn("ns-scope-wiring-test");
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
    svc = new KubernetesService(ctx, client, mock(HelmClient.class), /*isConfigured=*/true);
    server.expect().get().withPath("/apis").andReturn(200, new APIGroupListBuilder()
        .addNewGroup().withName("route.openshift.io").endGroup()
        .addNewGroup().withName("project.openshift.io").endGroup().build()).always();
    server.expect().get().withPath("/apis/project.openshift.io/v1/projects").andReturn(200,
        "{\"apiVersion\":\"project.openshift.io/v1\",\"kind\":\"ProjectList\",\"metadata\":{},\"items\":["
            + "{\"apiVersion\":\"project.openshift.io/v1\",\"kind\":\"Project\",\"metadata\":{\"name\":\"team-a\"}}]}").always();
  }

  private void forbidden(String path) {
    server.expect().get().withPath(path).andReturn(403, new StatusBuilder().withCode(403).withReason("Forbidden")
        .withMessage("forbidden: User \"MYUSER\" cannot list at the cluster scope").build()).always();
  }

  @Test
  void namespacePickerShowsTheProjectsOfARestrictedAccount() {
    forbidden("/api/v1/namespaces");
    assertEquals(List.of("team-a"), svc.listNamespaces().stream().map(n -> n.name).collect(Collectors.toList()));
  }

  @Test
  void clusterEventsAreReadFromTheProjects() {
    forbidden("/api/v1/events");
    server.expect().get().withPath("/api/v1/namespaces/team-a/events").andReturn(200, new EventListBuilder()
        .addToItems(new EventBuilder().withNewMetadata().withName("e1").withNamespace("team-a")
            .withCreationTimestamp("2026-10-08T10:00:00Z").endMetadata().withMessage("pulled").build())
        .build()).always();

    assertEquals(1, svc.getClusterEvents().size());
  }

  @Test
  void crdCheckFallsBackToApiDiscoveryWhenCrdsAreNotReadable() {
    forbidden("/apis/apiextensions.k8s.io/v1/customresourcedefinitions/scaledobjects.keda.sh");
    server.expect().get().withPath("/apis/keda.sh").andReturn(200, new APIGroupBuilder()
        .withName("keda.sh")
        .addNewVersion().withGroupVersion("keda.sh/v1alpha1").withVersion("v1alpha1").endVersion()
        .build()).always();
    server.expect().get().withPath("/apis/keda.sh/v1alpha1").andReturn(200, new APIResourceListBuilder()
        .withGroupVersion("keda.sh/v1alpha1")
        .addNewResource().withName("scaledobjects").withKind("ScaledObject").withNamespaced(true).endResource()
        .build()).always();

    assertTrue(svc.crdExists("scaledobjects.keda.sh"));
  }

  @Test
  void dashboardShowsTheUsageOfTheAccountsProjectsWhenClusterMetricsAreRefused() {
    forbidden("/api/v1/nodes");
    forbidden("/api/v1/pods");
    forbidden("/apis/metrics.k8s.io/v1beta1/nodes");
    server.expect().get().withPath("/api/v1/namespaces/team-a/pods").andReturn(200,
        "{\"kind\":\"PodList\",\"apiVersion\":\"v1\",\"metadata\":{},\"items\":[{\"metadata\":{\"name\":\"p\",\"namespace\":\"team-a\"},"
            + "\"spec\":{\"containers\":[{\"name\":\"c\"}]},\"status\":{\"phase\":\"Running\"}}]}").always();
    server.expect().get().withPath("/apis/metrics.k8s.io/v1beta1/namespaces/team-a/pods").andReturn(200,
        "{\"kind\":\"PodMetricsList\",\"apiVersion\":\"metrics.k8s.io/v1beta1\",\"metadata\":{},\"items\":[{\"metadata\":{\"name\":\"p\",\"namespace\":\"team-a\"},"
            + "\"timestamp\":\"2026-10-08T13:00:00Z\",\"window\":\"15s\",\"containers\":[{\"name\":\"c\",\"usage\":{\"cpu\":\"500m\",\"memory\":\"1Gi\"}}]}]}").always();
    server.expect().get().withPath("/api/v1/namespaces/team-a/resourcequotas").andReturn(200,
        "{\"kind\":\"ResourceQuotaList\",\"apiVersion\":\"v1\",\"metadata\":{},\"items\":[{\"metadata\":{\"name\":\"q\"},"
            + "\"spec\":{\"hard\":{\"limits.cpu\":\"2\",\"limits.memory\":\"4Gi\",\"pods\":\"10\"}}}]}").always();

    org.apache.ambari.view.k8s.model.ClusterStats stats = svc.getClusterStats(true);

    assertEquals("projects", stats.getScope());
    assertEquals(1, stats.getProjects());
    assertEquals(0.5, stats.getCpu().getUsed(), 1e-9);
    assertEquals(2.0, stats.getCpu().getTotal(), 1e-9);
    assertEquals("quota", stats.getCpu().getBasis());
    assertEquals(1.0, stats.getMemory().getUsed(), 1e-9);
    assertEquals(4.0, stats.getMemory().getTotal(), 1e-9);
    assertEquals("quota", stats.getMemory().getBasis());
    assertEquals(1.0, stats.getPods().getUsed());
    assertEquals(10.0, stats.getPods().getTotal(), "pod quota, not node capacity");
  }
}
