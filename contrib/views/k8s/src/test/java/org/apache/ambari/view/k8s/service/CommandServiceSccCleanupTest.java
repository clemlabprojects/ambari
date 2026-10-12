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

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The uninstall of a release takes back the OpenShift SCCs its service declares, as KDPS granted them, knowing which
 * SCCs the other releases of the project still need.
 */
class CommandServiceSccCleanupTest {

  private KubernetesService k8s;
  private CommandService service;
  private final List<K8sReleaseEntity> releases = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getInstanceName()).thenReturn("scc-cleanup-" + Long.toHexString(System.nanoTime()));
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", Files.createTempDirectory("kdps-scc").toString()));
    DataStore ds = mock(DataStore.class);
    when(ds.findAll(eq(K8sReleaseEntity.class), any())).thenAnswer(inv -> releases);
    when(ctx.getDataStore()).thenReturn(ds);
    k8s = mock(KubernetesService.class);
    service = new CommandService(ctx, mock(HelmService.class), k8s);
  }

  @AfterEach
  void tearDown() {
    service.shutdown();
  }

  private void recorded(String namespace, String release, String serviceKey) {
    K8sReleaseEntity e = new K8sReleaseEntity();
    e.setId(K8sReleaseEntity.idOf(namespace, release));
    e.setNamespace(namespace);
    e.setReleaseName(release);
    e.setServiceKey(serviceKey);
    releases.add(e);
  }

  @Test
  @SuppressWarnings("unchecked")
  void theServiceDefinitionSaysWhichSccsTheReleaseHad() {
    when(k8s.isOpenShiftCluster()).thenReturn(true);
    when(k8s.removeSccGrants(eq("data"), eq("om"), any(), any())).thenReturn("oc adm policy remove-scc-from-user ...");

    assertEquals("oc adm policy remove-scc-from-user ...", service.removeSccGrantsForRelease("data", "om", "OPENMETADATA"));

    ArgumentCaptor<List<Map<String, Object>>> declared = ArgumentCaptor.forClass(List.class);
    verify(k8s).removeSccGrants(eq("data"), eq("om"), declared.capture(), eq(Map.of()));
    assertEquals("anyuid", declared.getValue().get(0).get("scc"));
  }

  @Test
  void anUnknownServiceStillRemovesWhatKdpsGranted() {
    when(k8s.isOpenShiftCluster()).thenReturn(true);

    assertNull(service.removeSccGrantsForRelease("data", "om", "NO-SUCH-SERVICE"));
    assertNull(service.removeSccGrantsForRelease("data", "om2", null));

    verify(k8s).removeSccGrants("data", "om", null, Map.of());
    verify(k8s).removeSccGrants("data", "om2", null, Map.of());
  }

  @Test
  void nothingToDoOnPlainKubernetes() {
    when(k8s.isOpenShiftCluster()).thenReturn(false);

    assertNull(service.removeSccGrantsForRelease("data", "om", "OPENMETADATA"));

    verify(k8s, never()).removeSccGrants(any(), any(), any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void theOtherReleasesOfTheProjectSayWhatMustStay() {
    when(k8s.isOpenShiftCluster()).thenReturn(true);
    recorded("data", "jhub", "JUPYTERHUB");
    recorded("data", "om2", "OPENMETADATA");
    recorded("data", "trino", "TRINO");            // declares no SCC
    recorded("analytics", "om3", "OPENMETADATA");  // another project
    recorded("data", "om", "OPENMETADATA");        // the release itself, should its record outlive the uninstall

    service.removeSccGrantsForRelease("data", "om", "OPENMETADATA");

    ArgumentCaptor<Map<String, List<Map<String, Object>>>> others = ArgumentCaptor.forClass(Map.class);
    verify(k8s).removeSccGrants(eq("data"), eq("om"), any(), others.capture());
    assertEquals(List.of("jhub", "om2"), List.copyOf(others.getValue().keySet()));
    assertEquals("nonroot-v2", others.getValue().get("jhub").get(0).get("scc"));
    assertEquals("anyuid", others.getValue().get("om2").get(0).get("scc"));
  }

  @Test
  void unreadableReleaseRecordsAreReportedAsUnknown() throws Exception {
    when(k8s.isOpenShiftCluster()).thenReturn(true);
    DataStore failing = mock(DataStore.class);
    when(failing.findAll(eq(K8sReleaseEntity.class), any()))
        .thenThrow(new org.apache.ambari.view.PersistenceException("store unavailable"));
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getInstanceName()).thenReturn("scc-cleanup-" + Long.toHexString(System.nanoTime()));
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", Files.createTempDirectory("kdps-scc").toString()));
    when(ctx.getDataStore()).thenReturn(failing);
    CommandService failingService = new CommandService(ctx, mock(HelmService.class), k8s);
    try {
      assertDoesNotThrow(() -> failingService.removeSccGrantsForRelease("data", "om", "OPENMETADATA"));
      verify(k8s).removeSccGrants(eq("data"), eq("om"), any(), isNull());
    } finally {
      failingService.shutdown();
    }
  }
}
