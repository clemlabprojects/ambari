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

import com.marcnuri.helm.Release;
import io.fabric8.kubernetes.api.model.APIGroupBuilder;
import io.fabric8.kubernetes.api.model.APIGroupListBuilder;
import io.fabric8.kubernetes.api.model.APIResourceListBuilder;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.EventListBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NamespaceListBuilder;
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
 * An account limited to its own projects (OpenShift sites where the platform team hands out projects) is refused
 * every cluster-wide listing. KDPS must then work project by project instead of showing nothing.
 */
@EnableKubernetesMockClient
class NamespaceScopeTest {

  KubernetesMockServer server;
  KubernetesClient client;
  KubernetesService svc;

  private static final String PROJECTS_JSON = "{\"apiVersion\":\"project.openshift.io/v1\",\"kind\":\"ProjectList\","
      + "\"metadata\":{},\"items\":["
      + "{\"apiVersion\":\"project.openshift.io/v1\",\"kind\":\"Project\",\"metadata\":{\"name\":\"team-b\"},\"status\":{\"phase\":\"Active\"}},"
      + "{\"apiVersion\":\"project.openshift.io/v1\",\"kind\":\"Project\",\"metadata\":{\"name\":\"team-a\",\"labels\":{\"k\":\"v\"}},\"status\":{\"phase\":\"Active\"}}"
      + "]}";

  @BeforeEach
  void setUp() {
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getInstanceName()).thenReturn("ns-scope-test");
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
    svc = new KubernetesService(ctx, client, mock(HelmClient.class), /*isConfigured=*/true);
  }

  private void forbidden(String path) {
    server.expect().get().withPath(path).andReturn(403, new StatusBuilder()
        .withCode(403).withReason("Forbidden")
        .withMessage("forbidden: User \"MYUSER\" cannot list at the cluster scope").build()).once();
  }

  private void openShiftWithProjects() {
    server.expect().get().withPath("/apis").andReturn(200, new APIGroupListBuilder()
        .addNewGroup().withName("route.openshift.io").endGroup()
        .addNewGroup().withName("project.openshift.io").endGroup().build()).always();
    server.expect().get().withPath("/apis/project.openshift.io/v1/projects").andReturn(200, PROJECTS_JSON).always();
  }

  @Test
  void namespacesComeFromOpenShiftProjectsWhenTheClusterListIsRefused() {
    forbidden("/api/v1/namespaces");
    openShiftWithProjects();

    var namespaces = svc.listNamespaces();
    assertEquals(List.of("team-a", "team-b"), namespaces.stream().map(n -> n.name).collect(Collectors.toList()));
    assertEquals("Active", namespaces.get(0).status);
    assertEquals(Map.of("k", "v"), namespaces.get(0).labels);
    assertTrue(svc.isClusterWideForbidden("namespaces"));
  }

  @Test
  void namespacesComeFromTheClusterWhenAllowed() {
    server.expect().get().withPath("/api/v1/namespaces").andReturn(200, new NamespaceListBuilder()
        .addToItems(new NamespaceBuilder().withNewMetadata().withName("default").endMetadata().build())
        .build()).once();

    assertEquals(List.of("default"), svc.accessibleNamespaceNames());
    // Second call is served from the short-lived cache: the mock answers the list only once.
    assertEquals(List.of("default"), svc.accessibleNamespaceNames());
  }

  @Test
  void refusedClusterListingWalksTheProjectsAndSkipsRefusedOnes() {
    forbidden("/api/v1/namespaces");
    openShiftWithProjects();
    forbidden("/api/v1/events");
    server.expect().get().withPath("/api/v1/namespaces/team-a/events").andReturn(200, new EventListBuilder()
        .addToItems(new EventBuilder().withNewMetadata().withName("e1").withNamespace("team-a").endMetadata().build())
        .build()).times(2);
    forbidden("/api/v1/namespaces/team-b/events");
    forbidden("/api/v1/namespaces/team-b/events");

    var first = svc.listAcrossNamespaces("events",
        () -> client.v1().events().inAnyNamespace().list().getItems(),
        ns -> client.v1().events().inNamespace(ns).list().getItems());
    assertEquals(1, first.size());
    assertEquals("e1", first.get(0).getMetadata().getName());

    // The refusal is remembered: the cluster-wide call (answered once by the mock) is not tried again.
    var second = svc.listAcrossNamespaces("events",
        () -> client.v1().events().inAnyNamespace().list().getItems(),
        ns -> client.v1().events().inNamespace(ns).list().getItems());
    assertEquals(1, second.size());
  }

  @Test
  void allowedClusterListingIsUsedAsIs() {
    server.expect().get().withPath("/api/v1/events").andReturn(200, new EventListBuilder()
        .addToItems(new EventBuilder().withNewMetadata().withName("x").withNamespace("n1").endMetadata().build())
        .addToItems(new EventBuilder().withNewMetadata().withName("y").withNamespace("n2").endMetadata().build())
        .build()).once();

    var events = svc.listAcrossNamespaces("events",
        () -> client.v1().events().inAnyNamespace().list().getItems(),
        ns -> { throw new AssertionError("no per-namespace call expected"); });
    assertEquals(2, events.size());
    assertFalse(svc.isClusterWideForbidden("events"));
  }

  @Test
  void crdCheckFallsBackToApiDiscoveryWhenCrdsAreNotReadable() {
    forbidden("/apis/apiextensions.k8s.io/v1/customresourcedefinitions/certificates.cert-manager.io");
    forbidden("/apis/apiextensions.k8s.io/v1/customresourcedefinitions/clusterissuers.cert-manager.io");
    server.expect().get().withPath("/apis/cert-manager.io").andReturn(200, new APIGroupBuilder()
        .withName("cert-manager.io")
        .addNewVersion().withGroupVersion("cert-manager.io/v1").withVersion("v1").endVersion()
        .build()).always();
    server.expect().get().withPath("/apis/cert-manager.io/v1").andReturn(200, new APIResourceListBuilder()
        .withGroupVersion("cert-manager.io/v1")
        .addNewResource().withName("certificates").withKind("Certificate").withNamespaced(true).endResource()
        .build()).always();

    assertTrue(svc.crdExists("certificates.cert-manager.io"));
    assertFalse(svc.crdExists("clusterissuers.cert-manager.io"));
  }

  @Test
  void crdCheckReportsAbsentGroupThroughDiscovery() {
    forbidden("/apis/apiextensions.k8s.io/v1/customresourcedefinitions/externalsecrets.external-secrets.io");
    // /apis/external-secrets.io is not served: the mock answers 404.
    assertFalse(svc.crdExists("externalsecrets.external-secrets.io"));
  }

  @Test
  void helmForbiddenMessageIsRecognised() {
    assertTrue(KubernetesService.isForbidden(new IllegalStateException(
        "list: failed to list: secrets is forbidden: User \"MYUSER\" cannot list resource \"secrets\" in API group \"\" at the cluster scope")));
    assertTrue(KubernetesService.isForbidden(new RuntimeException("wrapped",
        new io.fabric8.kubernetes.client.KubernetesClientException("x", 403, null))));
    assertFalse(KubernetesService.isForbidden(new IllegalStateException("connection refused")));
  }

  @Test
  void helmPerNamespaceListingSkipsRefusedNamespacesAndKeepsOrder() {
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
    HelmClient helm = mock(HelmClient.class);
    Release a = mock(Release.class);
    Release c = mock(Release.class);
    when(helm.list("team-a", "KC", false)).thenReturn(List.of(a));
    when(helm.list("team-b", "KC", false)).thenThrow(new IllegalStateException("list: secrets is forbidden: User \"u\" cannot list"));
    when(helm.list("team-c", "KC", false)).thenReturn(List.of(c));

    HelmService helmService = new HelmService(ctx, helm);
    assertEquals(List.of(a, c), helmService.listPerNamespace(List.of("team-a", "team-b", "team-c"), "KC"));
  }

  @Test
  void helmPerNamespaceListingPropagatesOtherFailures() {
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
    HelmClient helm = mock(HelmClient.class);
    when(helm.list("team-a", "KC", false)).thenThrow(new IllegalStateException("connection refused"));

    HelmService helmService = new HelmService(ctx, helm);
    IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> helmService.listPerNamespace(List.of("team-a"), "KC"));
    assertEquals("connection refused", e.getMessage());
  }
}
