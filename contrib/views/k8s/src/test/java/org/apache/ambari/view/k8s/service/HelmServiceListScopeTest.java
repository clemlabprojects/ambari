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
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.NamespaceScope.Resource;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Listing Helm releases in all namespaces needs "list secrets" across the cluster (Helm stores each release in a
 * Secret). When the account is refused, {@link HelmService} lists namespace by namespace instead.
 */
class HelmServiceListScopeTest {

  /** Helm's text for an RBAC refusal: helm-java raises it as an IllegalStateException with no status code. */
  private static final String HELM_REFUSAL = "list: failed to list: secrets is forbidden: User \"MYUSER\" "
      + "cannot list resource \"secrets\" in API group \"\" at the cluster scope";

  HelmClient helm;
  NamespaceScope scope;
  HelmService service;

  @BeforeEach
  void setUp() {
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
    helm = mock(HelmClient.class);
    scope = mock(NamespaceScope.class);
    when(scope.namespacesToWalk()).thenReturn(List.of("team-a", "team-b", "team-c"));
    service = new HelmService(ctx, helm, () -> scope);
  }

  @Test
  void clusterWideListingIsUsedWhenAllowed() {
    Release r = mock(Release.class);
    when(helm.list(null, "KC", false)).thenReturn(List.of(r));

    assertEquals(List.of(r), service.list(null, "KC"));
    verify(scope, never()).recordRefusal(any(), any());
    verify(scope, never()).namespacesToWalk();
  }

  @Test
  void refusedClusterListingIsRecordedAndReplacedByAWalkThatSkipsRefusedNamespaces() {
    Release a = mock(Release.class);
    Release c = mock(Release.class);
    when(helm.list(null, "KC", false)).thenThrow(new IllegalStateException(HELM_REFUSAL));
    when(helm.list("team-a", "KC", false)).thenReturn(List.of(a));
    when(helm.list("team-b", "KC", false)).thenThrow(new IllegalStateException(HELM_REFUSAL));
    when(helm.list("team-c", "KC", false)).thenReturn(List.of(c));

    assertEquals(List.of(a, c), service.list(null, "KC"), "namespace order is kept");
    verify(scope).recordRefusal(eq(Resource.SECRETS), any());
  }

  @Test
  void rememberedRefusalSkipsTheClusterWideCall() {
    when(scope.isRefused(Resource.SECRETS)).thenReturn(true);
    when(helm.list(anyString(), eq("KC"), eq(false))).thenReturn(List.of());

    assertEquals(List.of(), service.list(null, "KC"));
    verify(helm, never()).list(isNull(), anyString(), anyBoolean());
  }

  @Test
  void otherClusterWideFailuresPropagate() {
    when(helm.list(null, "KC", false)).thenThrow(new IllegalStateException("Kubernetes cluster unreachable"));

    assertThrows(IllegalStateException.class, () -> service.list(null, "KC"));
    verify(scope, never()).recordRefusal(any(), any());
    verify(scope, never()).namespacesToWalk();
  }

  @Test
  void otherPerNamespaceFailuresPropagate() {
    when(scope.isRefused(Resource.SECRETS)).thenReturn(true);
    when(helm.list("team-a", "KC", false)).thenReturn(List.of());
    when(helm.list("team-b", "KC", false)).thenThrow(new IllegalStateException("connection refused"));
    when(helm.list("team-c", "KC", false)).thenReturn(List.of());

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.list(null, "KC"));
    assertEquals("connection refused", e.getMessage());
  }

  @Test
  void namedNamespaceListingNeverTouchesTheScope() {
    when(helm.list("apps", "KC", false)).thenReturn(List.of());

    service.list("apps", "KC");
    verifyNoInteractions(scope);
  }

  @Test
  void onlyKubernetesRbacWordingCountsAsARefusal() {
    assertTrue(HelmService.isRbacRefusal(new IllegalStateException(HELM_REFUSAL)));
    assertTrue(HelmService.isRbacRefusal(new RuntimeException("wrapped", new IllegalStateException(HELM_REFUSAL))));
    assertTrue(HelmService.isRbacRefusal(new KubernetesClientException("x", 403, null)));
    assertFalse(HelmService.isRbacRefusal(new IllegalStateException("Forbidden")));
    assertFalse(HelmService.isRbacRefusal(new IllegalStateException("release name is forbidden by policy")));
  }
}
