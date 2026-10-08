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

import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Reconnecting (new kubeconfig, another context, OpenShift login, token renewal) builds the new client before
 * replacing the previous one, and closes the previous one only after a grace period.
 */
class KubernetesServiceClientSwapTest {

  ViewContext ctx;
  KubernetesClient previous;
  KubernetesClient fresh;

  @BeforeEach
  void setUp() {
    ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getInstanceName()).thenReturn("swap-test");
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
    previous = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
    fresh = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
  }

  /** A service whose saved configuration yields {@code built} (or throws {@code failure}). */
  private KubernetesService service(KubernetesClient built, Exception failure, AtomicReference<KubernetesClient> seenDuringBuild) {
    KubernetesService svc = new KubernetesService(ctx, previous, mock(HelmClient.class), /*isConfigured=*/true) {
      @Override
      KubernetesClient buildClientFromSavedConfiguration() throws Exception {
        if (seenDuringBuild != null) {
          seenDuringBuild.set(getClient()); // what any page reads while the new client is being built
        }
        if (failure != null) {
          throw failure;
        }
        return built;
      }
    };
    svc.retiredClientGraceSeconds = 0;
    return svc;
  }

  @Test
  void theNewClientReplacesThePreviousOneWhichIsClosedAfterItsGracePeriod() {
    KubernetesService svc = service(fresh, null, null);

    assertTrue(svc.forceReloadClient());
    assertSame(fresh, svc.getClient());
    verify(previous, timeout(2_000)).close();
    verify(fresh, never()).close();
  }

  @Test
  void pagesKeepThePreviousClientWhileTheNewOneIsBuilt() {
    AtomicReference<KubernetesClient> seen = new AtomicReference<>();
    KubernetesService svc = service(fresh, null, seen);

    svc.forceReloadClient();
    assertSame(previous, seen.get(), "no gap: the view never reads a missing client during a reconnection");
  }

  @Test
  void thePreviousClientStaysOpenDuringTheGracePeriod() {
    KubernetesService svc = service(fresh, null, null);
    svc.retiredClientGraceSeconds = 60;

    svc.forceReloadClient();
    verify(previous, after(300).never()).close();
  }

  @Test
  void aFailedRebuildLeavesTheViewUnconfiguredAndRetiresThePreviousClient() {
    KubernetesService svc = service(null, new IllegalStateException("bad kubeconfig"), null);

    assertFalse(svc.forceReloadClient());
    assertNull(svc.getClient());
    assertThrows(IllegalStateException.class, svc::listNamespaces, "the view reports it is not configured");
    verify(previous, timeout(2_000)).close();
  }

  @Test
  void noSavedKubeconfigLeavesTheViewUnconfigured() {
    KubernetesService svc = service(null, null, null);

    assertFalse(svc.forceReloadClient());
    assertNull(svc.getClient());
  }

  @Test
  void reloadIfConfiguredKeepsAWorkingClient() {
    AtomicReference<KubernetesClient> seen = new AtomicReference<>();
    KubernetesService svc = service(fresh, null, seen);

    assertTrue(svc.reloadClientIfConfigured());
    assertSame(previous, svc.getClient());
    assertNull(seen.get(), "nothing was rebuilt");
    verify(previous, after(200).never()).close();
  }

  @Test
  void webhookCertificatesUseTheCurrentClientNotTheOneSeenFirst() {
    KubernetesService ks = mock(KubernetesService.class);
    when(ks.getClient()).thenReturn(previous);
    WebHookConfigurationService webhooks = new WebHookConfigurationService(ctx, ks);
    when(ks.getClient()).thenReturn(fresh); // reconnected after the service was created

    // The mocked client returns nothing useful; what matters is which client the call went to.
    assertThrows(RuntimeException.class, () -> webhooks.loadUploadedCertificateAuthority("ambari-pki", "company-ca"));
    verify(fresh).secrets();
    verify(previous, never()).secrets();
  }
}
