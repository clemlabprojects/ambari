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

import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** OpenShift monitoring setup shared by the direct Helm and GitOps deployment modes. */
class OpenShiftMonitoringSetupTest {

  KubernetesService k8s;
  HelmDeployRequest req;

  @BeforeEach
  void setUp() {
    k8s = mock(KubernetesService.class);
    Map<String, Object> trigger = new LinkedHashMap<>();
    trigger.put("metadata", new LinkedHashMap<>(Map.of("serverAddress", "https://thanos-querier.openshift-monitoring.svc:9091")));
    Map<String, Object> keda = new LinkedHashMap<>();
    keda.put("enabled", true);
    keda.put("triggers", new ArrayList<>(List.of(trigger)));
    keda.put("triggerAuthentication", Map.of("enabled", true, "secretName", "trino-thanos-token"));
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("server", new LinkedHashMap<>(Map.of("keda", keda)));
    values.put("serviceMonitor", Map.of("enabled", true));
    req = new HelmDeployRequest();
    req.setReleaseName("trino");
    req.setNamespace("team-a");
    req.setValues(values);
  }

  @SuppressWarnings("unchecked")
  private String serverAddress() {
    var keda = (Map<String, Object>) ((Map<String, Object>) req.getValues().get("server")).get("keda");
    var t = (Map<String, Object>) ((List<Object>) keda.get("triggers")).get(0);
    return String.valueOf(((Map<String, Object>) t.get("metadata")).get("serverAddress"));
  }

  @Test
  void nothingHappensOutsideOpenShift() {
    when(k8s.isOpenShiftCluster()).thenReturn(false);
    assertFalse(OpenShiftMonitoringSetup.scopeAutoscalingToProject(k8s, req));
    OpenShiftMonitoringSetup.prepare(k8s, req);
    assertTrue(serverAddress().endsWith(":9091"));
    verify(k8s, never()).ensureKedaThanosTokenSecret(any(), any(), any(), anyBoolean());
  }

  @Test
  void projectLimitedAccountGetsTheProjectScopeEndToEnd() {
    when(k8s.isOpenShiftCluster()).thenReturn(true);
    when(k8s.canBindClusterRoles()).thenReturn(false);
    when(k8s.canI(eq("create"), eq("monitoring.coreos.com"), eq("servicemonitors"), isNull(), eq("team-a"))).thenReturn(true);

    assertTrue(OpenShiftMonitoringSetup.scopeAutoscalingToProject(k8s, req));
    assertTrue(serverAddress().endsWith(":9092"));
    OpenShiftMonitoringSetup.prepare(k8s, req);
    verify(k8s).ensureKedaThanosTokenSecret("team-a", "trino-thanos-token", "trino-thanos-token", true);
  }

  @Test
  void clusterAdminKeepsTheClusterPort() {
    when(k8s.isOpenShiftCluster()).thenReturn(true);
    when(k8s.canBindClusterRoles()).thenReturn(true);
    when(k8s.canI(any(), any(), any(), any(), any())).thenReturn(true);

    assertFalse(OpenShiftMonitoringSetup.scopeAutoscalingToProject(k8s, req));
    assertTrue(serverAddress().endsWith(":9091"));
    OpenShiftMonitoringSetup.prepare(k8s, req);
    verify(k8s).ensureKedaThanosTokenSecret("team-a", "trino-thanos-token", "trino-thanos-token", false);
  }

  @Test
  void missingServiceMonitorRightStopsBeforeAnythingIsProvisioned() {
    when(k8s.isOpenShiftCluster()).thenReturn(true);
    when(k8s.canI(any(), any(), any(), any(), any())).thenReturn(false);

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> OpenShiftMonitoringSetup.prepare(k8s, req));
    assertTrue(e.getMessage().contains("oc policy add-role-to-user monitoring-edit"), e.getMessage());
    verify(k8s, never()).ensureKedaThanosTokenSecret(any(), any(), any(), anyBoolean());
  }

  @Test
  void tokenProblemsStopTheDeployWithTheInstructions() {
    when(k8s.isOpenShiftCluster()).thenReturn(true);
    when(k8s.canI(any(), any(), any(), any(), any())).thenReturn(true);
    when(k8s.ensureKedaThanosTokenSecret(any(), any(), any(), anyBoolean())).thenReturn("ask an admin to run: ...");

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> OpenShiftMonitoringSetup.prepare(k8s, req));
    assertTrue(e.getMessage().contains("ask an admin to run"), e.getMessage());
  }

  @Test
  @SuppressWarnings("unchecked")
  void autoscalingOffNeedsNoMonitoringTokenButServiceMonitorsStillNeedTheGrant() {
    ((Map<String, Object>) ((Map<String, Object>) req.getValues().get("server")).get("keda")).put("enabled", false);
    when(k8s.isOpenShiftCluster()).thenReturn(true);
    when(k8s.canBindClusterRoles()).thenReturn(false);
    when(k8s.canI(eq("create"), eq("monitoring.coreos.com"), eq("servicemonitors"), isNull(), eq("team-a"))).thenReturn(true);

    assertFalse(OpenShiftMonitoringSetup.scopeAutoscalingToProject(k8s, req));
    OpenShiftMonitoringSetup.prepare(k8s, req);
    verify(k8s, never()).ensureKedaThanosTokenSecret(any(), any(), any(), anyBoolean());

    when(k8s.canI(eq("create"), eq("monitoring.coreos.com"), eq("servicemonitors"), isNull(), eq("team-a"))).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> OpenShiftMonitoringSetup.prepare(k8s, req));
  }
}
