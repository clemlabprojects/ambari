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
import io.fabric8.kubernetes.api.model.APIResourceListBuilder;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.EventListBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NamespaceListBuilder;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.apache.ambari.view.k8s.service.NamespaceScope.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An account limited to its own projects (OpenShift sites where the platform team hands out projects) is refused
 * every cluster-wide listing; {@link NamespaceScope} must then work namespace by namespace.
 */
@EnableKubernetesMockClient
class NamespaceScopeTest {

  KubernetesMockServer server;
  KubernetesClient client;

  private final AtomicLong now = new AtomicLong(1_000_000L);
  private final AtomicBoolean openShift = new AtomicBoolean(true);
  private List<String> known = List.of();
  private NamespaceScope scope;

  private static final String PROJECTS_JSON = "{\"apiVersion\":\"project.openshift.io/v1\",\"kind\":\"ProjectList\","
      + "\"metadata\":{},\"items\":["
      + "{\"apiVersion\":\"project.openshift.io/v1\",\"kind\":\"Project\",\"metadata\":{\"name\":\"team-b\"},\"status\":{\"phase\":\"Active\"}},"
      + "{\"apiVersion\":\"project.openshift.io/v1\",\"kind\":\"Project\",\"metadata\":{\"name\":\"team-a\",\"labels\":{\"k\":\"v\"}},\"status\":{\"phase\":\"Active\"}}"
      + "]}";

  @BeforeEach
  void setUp() {
    Supplier<Collection<String>> knownNamespaces = () -> known;
    scope = new NamespaceScope(() -> client, openShift::get, knownNamespaces, NamespaceScope::runOnce, now::get);
  }

  private void forbidden(String path, int times) {
    server.expect().get().withPath(path).andReturn(403, new StatusBuilder()
        .withCode(403).withReason("Forbidden")
        .withMessage("forbidden: User \"MYUSER\" cannot list at the cluster scope").build()).times(times);
  }

  private void projects() {
    server.expect().get().withPath("/apis/project.openshift.io/v1/projects").andReturn(200, PROJECTS_JSON).always();
  }

  private List<io.fabric8.kubernetes.api.model.Event> listEvents() {
    return scope.listAcrossNamespaces(Resource.EVENTS,
        () -> client.v1().events().inAnyNamespace().list().getItems(),
        ns -> client.v1().events().inNamespace(ns).list().getItems());
  }

  private void eventsInTeamA(int times) {
    server.expect().get().withPath("/api/v1/namespaces/team-a/events").andReturn(200, new EventListBuilder()
        .addToItems(new EventBuilder().withNewMetadata().withName("e1").withNamespace("team-a").endMetadata().build())
        .build()).times(times);
  }

  @Test
  void namespacesComeFromOpenShiftProjectsWhenTheClusterListIsRefused() {
    forbidden("/api/v1/namespaces", 1);
    projects();

    var namespaces = scope.listNamespaces();
    assertEquals(List.of("team-a", "team-b"), namespaces.stream().map(n -> n.name).collect(Collectors.toList()));
    assertEquals("Active", namespaces.get(0).status);
    assertEquals(Map.of("k", "v"), namespaces.get(0).labels);
    assertTrue(scope.isRefused(Resource.NAMESPACES));
  }

  @Test
  void namespacesComeFromWhatKdpsKnowsOnPlainKubernetes() {
    openShift.set(false);
    known = List.of("apps", "", "analytics");
    forbidden("/api/v1/namespaces", 1);

    assertEquals(List.of("analytics", "apps"),
        scope.listNamespaces().stream().map(n -> n.name).collect(Collectors.toList()));
  }

  @Test
  void namespaceListIsLiveWhenTheClusterListIsAllowed() {
    server.expect().get().withPath("/api/v1/namespaces").andReturn(200, new NamespaceListBuilder()
        .addToItems(new NamespaceBuilder().withNewMetadata().withName("default").endMetadata().build())
        .build()).once();
    server.expect().get().withPath("/api/v1/namespaces").andReturn(200, new NamespaceListBuilder()
        .addToItems(new NamespaceBuilder().withNewMetadata().withName("default").endMetadata().build())
        .addToItems(new NamespaceBuilder().withNewMetadata().withName("created-since").endMetadata().build())
        .build()).once();

    assertEquals(1, scope.listNamespaces().size());
    assertEquals(2, scope.listNamespaces().size(), "the list shown to users is not cached");
  }

  @Test
  void refusedClusterListingWalksTheProjectsSkipsRefusedOnesAndIsNotRetried() {
    forbidden("/api/v1/namespaces", 1);
    projects();
    forbidden("/api/v1/events", 1);
    eventsInTeamA(2);
    forbidden("/api/v1/namespaces/team-b/events", 2);

    assertEquals(List.of("e1"), listEvents().stream().map(e -> e.getMetadata().getName()).collect(Collectors.toList()));
    // The mock answers the cluster-wide call only once: a second attempt would get 404 and fail the test.
    assertEquals(1, listEvents().size());
  }

  @Test
  void refusalIsForgottenAfterItsTtlSoGrantedRightsArePickedUp() {
    forbidden("/api/v1/namespaces", 1);
    projects();
    forbidden("/api/v1/events", 1);
    eventsInTeamA(1);
    forbidden("/api/v1/namespaces/team-b/events", 1);
    assertEquals(1, listEvents().size());

    now.addAndGet(NamespaceScope.REFUSAL_TTL_MS + 1);
    server.expect().get().withPath("/api/v1/events").andReturn(200, new EventListBuilder()
        .addToItems(new EventBuilder().withNewMetadata().withName("x").withNamespace("n1").endMetadata().build())
        .addToItems(new EventBuilder().withNewMetadata().withName("y").withNamespace("n2").endMetadata().build())
        .build()).once();
    assertEquals(2, listEvents().size());
    assertFalse(scope.isRefused(Resource.EVENTS));
  }

  @Test
  void resetForgetsRefusals() {
    forbidden("/api/v1/namespaces", 1);
    assertFalse(scope.isRefused(Resource.NAMESPACES));
    projects();
    scope.listNamespaces();
    assertTrue(scope.isRefused(Resource.NAMESPACES));
    scope.reset();
    assertFalse(scope.isRefused(Resource.NAMESPACES));
  }

  @Test
  void aRefusalSeenByACallThatStartedBeforeAResetIsNotRemembered() {
    projects();
    scope.listAcrossNamespaces(Resource.SECRETS,
        () -> {
          scope.reset(); // credentials change while the call is in flight
          throw new KubernetesClientException("secrets is forbidden", 403, null);
        },
        ns -> List.of(), NamespaceScope::isForbidden, null);
    assertFalse(scope.isRefused(Resource.SECRETS));
  }

  @Test
  void walkReusesTheNamespaceListForAShortWhile() {
    forbidden("/api/v1/namespaces", 1);
    server.expect().get().withPath("/apis/project.openshift.io/v1/projects").andReturn(200, PROJECTS_JSON).once();

    assertEquals(List.of("team-a", "team-b"), scope.namespacesToWalk());
    // Projects are answered once: the second walk within the TTL must not list them again.
    assertEquals(List.of("team-a", "team-b"), scope.namespacesToWalk());
  }

  @Test
  void allowedClusterListingIsUsedAsIs() {
    server.expect().get().withPath("/api/v1/events").andReturn(200, new EventListBuilder()
        .addToItems(new EventBuilder().withNewMetadata().withName("x").withNamespace("n1").endMetadata().build())
        .build()).once();

    var events = scope.listAcrossNamespaces(Resource.EVENTS,
        () -> client.v1().events().inAnyNamespace().list().getItems(),
        ns -> { throw new AssertionError("no per-namespace call expected"); });
    assertEquals(1, events.size());
    assertFalse(scope.isRefused(Resource.EVENTS));
  }

  @Test
  void otherFailuresAreNotTreatedAsRefusals() {
    server.expect().get().withPath("/api/v1/events").andReturn(500, "boom").once();
    assertThrows(KubernetesClientException.class, this::listEvents);
    assertFalse(scope.isRefused(Resource.EVENTS));
  }

  @Test
  void apiDiscoveryAnswersWhetherAResourceIsServed() {
    server.expect().get().withPath("/apis/cert-manager.io").andReturn(200, new APIGroupBuilder()
        .withName("cert-manager.io")
        .addNewVersion().withGroupVersion("cert-manager.io/v1").withVersion("v1").endVersion()
        .build()).always();
    server.expect().get().withPath("/apis/cert-manager.io/v1").andReturn(200, new APIResourceListBuilder()
        .withGroupVersion("cert-manager.io/v1")
        .addNewResource().withName("certificates").withKind("Certificate").withNamespaced(true).endResource()
        .build()).always();

    assertTrue(scope.servedByApiDiscovery("certificates.cert-manager.io"));
    assertFalse(scope.servedByApiDiscovery("clusterissuers.cert-manager.io"));
    assertFalse(scope.servedByApiDiscovery("externalsecrets.external-secrets.io"), "group not served (404)");
    assertFalse(scope.servedByApiDiscovery("nodot"));
    assertEquals(List.of("v1"), scope.servedApiVersions("cert-manager.io"));
    assertEquals(List.of(), scope.servedApiVersions("external-secrets.io"));
  }

  @Test
  void refusedNamespacesAreRememberedAndNotCalledAgain() {
    forbidden("/api/v1/namespaces", 1);
    projects();
    forbidden("/api/v1/events", 1);
    eventsInTeamA(2);
    forbidden("/api/v1/namespaces/team-b/events", 1); // a second call to team-b would get 404 and fail the test

    assertEquals(1, listEvents().size());
    assertEquals(1, listEvents().size());
  }

  @Test
  void onOpenShiftTheWalkUsesTheProjectsEvenWhenNamespacesAreListable() {
    server.expect().get().withPath("/api/v1/namespaces").andReturn(200, new NamespaceListBuilder()
        .addToItems(new NamespaceBuilder().withNewMetadata().withName("openshift-etcd").endMetadata().build())
        .addToItems(new NamespaceBuilder().withNewMetadata().withName("team-a").endMetadata().build())
        .build()).always();
    projects();

    assertEquals(List.of("team-a", "team-b"), scope.namespacesToWalk());
  }

  @Test
  void projectApiRefusalFallsBackToTheKnownNamespaces() {
    known = List.of("apps");
    forbidden("/api/v1/namespaces", 1);
    forbidden("/apis/project.openshift.io/v1/projects", 1);

    assertEquals(List.of("apps"), scope.listNamespaces().stream().map(n -> n.name).collect(Collectors.toList()));
  }

  @Test
  void projectApiServerErrorsAreNotHidden() {
    forbidden("/api/v1/namespaces", 1);
    server.expect().get().withPath("/apis/project.openshift.io/v1/projects").andReturn(400, "bad request").always();

    assertThrows(KubernetesClientException.class, scope::listNamespaces);
  }

  @Test
  void walkNamespacesAreListedAgainAfterTheirTtlOrAnInvalidation() {
    projects();
    int start = server.getRequestCount();

    scope.namespacesToWalk();
    scope.namespacesToWalk();
    assertEquals(start + 1, server.getRequestCount(), "second walk within the TTL reuses the list");
    now.addAndGet(NamespaceScope.WALK_NAMESPACES_TTL_MS + 1);
    scope.namespacesToWalk();
    assertEquals(start + 2, server.getRequestCount(), "listed again once the TTL has passed");
    scope.invalidateNamespaces();
    scope.namespacesToWalk();
    assertEquals(start + 3, server.getRequestCount(), "listed again after an invalidation");
  }

  @Test
  void anonymousRefusalIsAConnectionProblemNotAScopeLimit() {
    server.expect().get().withPath("/api/v1/events").andReturn(403, new StatusBuilder()
        .withCode(403).withReason("Forbidden")
        .withMessage("events is forbidden: User \"system:anonymous\" cannot list resource \"events\"").build()).once();

    assertThrows(KubernetesClientException.class, this::listEvents);
    assertFalse(scope.isRefused(Resource.EVENTS));
  }

  @Test
  void parallelWalkFailsWhenItExceedsItsDeadline() throws Exception {
    forbidden("/api/v1/namespaces", 1);
    projects();
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      IllegalStateException e = assertThrows(IllegalStateException.class, () -> scope.listAcrossNamespaces(Resource.SECRETS,
          () -> { throw new KubernetesClientException("x", 403, null); },
          ns -> {
            if (ns.equals("team-b")) {
              try { Thread.sleep(5_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            return List.of(ns);
          },
          NamespaceScope::isForbidden, new NamespaceScope.Parallel(pool, java.time.Duration.ofMillis(300))));
      assertTrue(e.getMessage().contains("did not finish within"), e.getMessage());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void parallelWalkRethrowsErrorsUnchanged() {
    forbidden("/api/v1/namespaces", 1);
    projects();
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      assertThrows(UnsatisfiedLinkError.class, () -> scope.listAcrossNamespaces(Resource.SECRETS,
          () -> { throw new KubernetesClientException("x", 403, null); },
          ns -> { throw new UnsatisfiedLinkError("native helm library"); },
          NamespaceScope::isForbidden, new NamespaceScope.Parallel(pool, java.time.Duration.ofSeconds(5))));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void onlyA403FromTheKubernetesClientCountsAsForbidden() {
    assertTrue(NamespaceScope.isForbidden(new KubernetesClientException("x", 403, null)));
    assertTrue(NamespaceScope.isForbidden(new RuntimeException("wrapped", new KubernetesClientException("x", 403, null))));
    assertFalse(NamespaceScope.isForbidden(new KubernetesClientException("x", 401, null)));
    assertFalse(NamespaceScope.isForbidden(new IllegalStateException("Forbidden by a proxy policy")),
        "free text is never trusted here");
    assertFalse(NamespaceScope.isForbidden(new KubernetesClientException(
        "pods is forbidden: User \"system:anonymous\" cannot list resource \"pods\"", 403, null)));
  }
}
