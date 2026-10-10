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
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** OpenShift is detected once and remembered, but only from an answer: a failed check is asked again. */
class KubernetesServiceOpenShiftDetectionTest {

  KubernetesClient client;
  KubernetesService svc;

  @BeforeEach
  void setUp() {
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getInstanceName()).thenReturn("ocp-detect-test");
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
    client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
    svc = new KubernetesService(ctx, client, mock(HelmClient.class), /*isConfigured=*/true);
  }

  private static io.fabric8.kubernetes.api.model.APIGroupList openShiftGroups() {
    return new APIGroupListBuilder()
        .addToGroups(new APIGroupBuilder().withName("apps").build())
        .addToGroups(new APIGroupBuilder().withName("route.openshift.io").build())
        .build();
  }

  @Test
  void aFailedCheckIsNotRememberedAsPlainKubernetes() {
    when(client.getApiGroups())
        .thenThrow(new KubernetesClientException("Unauthorized", 401, null))
        .thenReturn(openShiftGroups());

    assertFalse(svc.isOpenShiftCluster(), "no answer: treated as plain Kubernetes for now");
    assertFalse(svc.isOpenShiftCluster(), "within the retry window: no new request");
    verify(client, times(1)).getApiGroups();

    svc.expireOpenShiftDetectionRetryForTest();
    assertTrue(svc.isOpenShiftCluster(), "asked again after the window, and OpenShift is found");
    assertTrue(svc.isOpenShiftCluster());
    verify(client, times(2)).getApiGroups();
  }

  @Test
  void anAnswerIsRemembered() {
    when(client.getApiGroups()).thenReturn(new APIGroupListBuilder()
        .addToGroups(new APIGroupBuilder().withName("apps").build()).build());

    assertFalse(svc.isOpenShiftCluster());
    assertFalse(svc.isOpenShiftCluster());
    verify(client, times(1)).getApiGroups();
  }
}
