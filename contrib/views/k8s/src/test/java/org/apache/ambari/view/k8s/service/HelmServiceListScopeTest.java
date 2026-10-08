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
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Listing Helm releases in all namespaces needs "list secrets" across the cluster (Helm stores each release in a
 * Secret). When the account is refused, {@link HelmService} lists namespace by namespace instead. Uses a real
 * {@link NamespaceScope} on plain Kubernetes whose account may not list namespaces, so the namespaces walked are
 * the ones the view knows.
 */
class HelmServiceListScopeTest {

  /** Helm's text for a cluster-wide RBAC refusal: helm-java raises it as an IllegalStateException with no code. */
  private static final String CLUSTER_REFUSAL = "list: failed to list: secrets is forbidden: User \"MYUSER\" "
      + "cannot list resource \"secrets\" in API group \"\" at the cluster scope";
  /** Helm's text when one namespace refuses. */
  private static final String NAMESPACE_REFUSAL = "list: failed to list: secrets is forbidden: User \"MYUSER\" "
      + "cannot list resource \"secrets\" in API group \"\" in the namespace \"team-b\"";

  private final AtomicLong now = new AtomicLong(1_000_000L);
  HelmClient helm;
  NamespaceScope scope;
  HelmService service;

  @BeforeEach
  void setUp() {
    KubernetesClient k8s = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
    when(k8s.namespaces().list()).thenThrow(new KubernetesClientException("namespaces is forbidden", 403, null));
    scope = new NamespaceScope(() -> k8s, () -> false, () -> List.of("team-a", "team-b", "team-c"),
        NamespaceScope::runOnce, now::get);

    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
    helm = mock(HelmClient.class);
    service = new HelmService(ctx, helm, () -> scope);
  }

  private static Release release(String name, String namespace) {
    Release r = mock(Release.class);
    when(r.getName()).thenReturn(name);
    when(r.getNamespace()).thenReturn(namespace);
    return r;
  }

  private void restrictedAccount() {
    Release zeta = release("zeta", "team-a");
    Release alpha = release("alpha", "team-c");
    when(helm.list(null, "KC", false)).thenThrow(new IllegalStateException(CLUSTER_REFUSAL));
    when(helm.list("team-a", "KC", false)).thenReturn(List.of(zeta));
    when(helm.list("team-b", "KC", false)).thenThrow(new IllegalStateException(NAMESPACE_REFUSAL));
    when(helm.list("team-c", "KC", false)).thenReturn(List.of(alpha));
  }

  private static List<String> names(List<Release> releases) {
    return releases.stream().map(r -> r.getNamespace() + "/" + r.getName()).toList();
  }

  @Test
  void clusterWideListingIsUsedWhenAllowed() {
    Release b = release("b", "ns2");
    Release a = release("a", "ns1");
    when(helm.list(null, "KC", false)).thenReturn(List.of(b, a));

    assertEquals(List.of("ns1/a", "ns2/b"), names(service.list(null, "KC")));
    verify(helm, never()).list(anyString(), anyString(), anyBoolean());
    assertFalse(scope.isRefused(NamespaceScope.Resource.SECRETS));
  }

  @Test
  void refusedAccountGetsTheReleasesOfItsNamespacesInHelmOrder() {
    restrictedAccount();

    assertEquals(List.of("team-c/alpha", "team-a/zeta"), names(service.list(null, "KC")),
        "sorted by release name like 'helm list -A', not by namespace");
    assertTrue(scope.isRefused(NamespaceScope.Resource.SECRETS));
  }

  @Test
  void refusalsAreRememberedForTheClusterAndForEachNamespace() {
    restrictedAccount();

    service.list(null, "KC");
    service.list(null, "KC");
    verify(helm, times(1)).list(isNull(), anyString(), anyBoolean());
    verify(helm, times(1)).list(eq("team-b"), anyString(), anyBoolean());
    verify(helm, times(2)).list(eq("team-a"), anyString(), anyBoolean());
  }

  @Test
  void clusterWideListingIsTriedAgainOnceTheRefusalExpires() {
    restrictedAccount();
    service.list(null, "KC");

    now.addAndGet(NamespaceScope.REFUSAL_TTL_MS + 1);
    service.list(null, "KC");
    verify(helm, times(2)).list(isNull(), anyString(), anyBoolean());
  }

  @Test
  void otherClusterWideFailuresPropagateAndAreNotRemembered() {
    when(helm.list(null, "KC", false)).thenThrow(new IllegalStateException("Kubernetes cluster unreachable"));

    assertThrows(IllegalStateException.class, () -> service.list(null, "KC"));
    assertFalse(scope.isRefused(NamespaceScope.Resource.SECRETS));
    verify(helm, never()).list(anyString(), anyString(), anyBoolean());
  }

  @Test
  void otherPerNamespaceFailuresPropagate() {
    when(helm.list(null, "KC", false)).thenThrow(new IllegalStateException(CLUSTER_REFUSAL));
    when(helm.list("team-a", "KC", false)).thenReturn(List.of());
    when(helm.list("team-b", "KC", false)).thenThrow(new IllegalStateException("connection refused"));
    when(helm.list("team-c", "KC", false)).thenReturn(List.of());

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.list(null, "KC"));
    assertEquals("connection refused", e.getMessage());
  }

  @Test
  void namedOrEmptyNamespaceGoesToHelmAsIs() {
    when(helm.list(anyString(), eq("KC"), eq(false))).thenReturn(List.of());

    service.list("apps", "KC");
    service.list("", "KC");
    verify(helm).list("apps", "KC", false);
    verify(helm).list("", "KC", false);
    verify(helm, never()).list(isNull(), anyString(), anyBoolean());
  }

  @Test
  void onlyKubernetesRbacWordingCountsAsARefusal() {
    assertTrue(HelmService.isRbacRefusal(new IllegalStateException(CLUSTER_REFUSAL)));
    assertTrue(HelmService.isRbacRefusal(new IllegalStateException(NAMESPACE_REFUSAL)));
    assertTrue(HelmService.isRbacRefusal(new RuntimeException("wrapped", new IllegalStateException(CLUSTER_REFUSAL))));
    assertTrue(HelmService.isRbacRefusal(new KubernetesClientException("x", 403, null)));
    assertFalse(HelmService.isRbacRefusal(new IllegalStateException("Forbidden")));
    assertFalse(HelmService.isRbacRefusal(new IllegalStateException("release name is forbidden by policy")));
    assertFalse(HelmService.isRbacRefusal(new IllegalStateException(
        "secrets is forbidden: User \"system:anonymous\" cannot list resource \"secrets\" in API group \"\"")),
        "anonymous means the credentials were not accepted");
  }
}
