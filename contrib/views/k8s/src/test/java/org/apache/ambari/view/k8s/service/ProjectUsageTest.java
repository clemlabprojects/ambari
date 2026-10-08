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

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.api.model.ResourceQuotaListBuilder;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Usage of an account's projects, from the pod metrics API, against their quotas or requests. */
@EnableKubernetesMockClient
class ProjectUsageTest {

  KubernetesMockServer server;
  KubernetesClient client;

  private static final double GIB = 1024.0 * 1024 * 1024;

  /** Pod metrics of one namespace: one pod per (cpu, memory) pair, e.g. "250m", "1Gi". */
  private void podMetrics(String ns, String... cpuMem) {
    StringBuilder items = new StringBuilder();
    for (int i = 0; i < cpuMem.length; i += 2) {
      if (items.length() > 0) items.append(',');
      items.append("{\"metadata\":{\"name\":\"p").append(i).append("\",\"namespace\":\"").append(ns).append("\"},")
          .append("\"timestamp\":\"2026-10-08T13:00:00Z\",\"window\":\"15s\",\"containers\":[{\"name\":\"c\",\"usage\":{")
          .append("\"cpu\":\"").append(cpuMem[i]).append("\",\"memory\":\"").append(cpuMem[i + 1]).append("\"}}]}");
    }
    server.expect().get().withPath("/apis/metrics.k8s.io/v1beta1/namespaces/" + ns + "/pods")
        .andReturn(200, "{\"kind\":\"PodMetricsList\",\"apiVersion\":\"metrics.k8s.io/v1beta1\",\"metadata\":{},\"items\":[" + items + "]}")
        .always();
  }

  private void forbiddenMetrics(String ns) {
    server.expect().get().withPath("/apis/metrics.k8s.io/v1beta1/namespaces/" + ns + "/pods").andReturn(403,
        new StatusBuilder().withCode(403).withMessage("pods.metrics.k8s.io is forbidden").build()).always();
  }

  private void quotas(String ns, Map<String, String>... hards) {
    ResourceQuotaListBuilder list = new ResourceQuotaListBuilder();
    int i = 0;
    for (Map<String, String> hard : hards) {
      ResourceQuotaBuilder q = new ResourceQuotaBuilder().withNewMetadata().withName("q" + i++).withNamespace(ns).endMetadata().withNewSpec().endSpec();
      hard.forEach((k, v) -> q.editSpec().addToHard(k, new Quantity(v)).endSpec());
      list.addToItems(q.build());
    }
    server.expect().get().withPath("/api/v1/namespaces/" + ns + "/resourcequotas").andReturn(200, list.build()).always();
  }

  private static Pod podRequesting(String ns, String cpu, String memory) {
    return new PodBuilder().withNewMetadata().withName("p").withNamespace(ns).endMetadata()
        .withNewSpec().addNewContainer().withName("c").withNewResources()
        .addToRequests("cpu", new Quantity(cpu)).addToRequests("memory", new Quantity(memory))
        .endResources().endContainer().endSpec().build();
  }

  private static Pod bestEffortPod(String ns) {
    return new PodBuilder().withNewMetadata().withName("be").withNamespace(ns).endMetadata()
        .withNewSpec().addNewContainer().withName("c").endContainer().endSpec().build();
  }

  @Test
  void usageIsMeasuredAgainstTheProjectsQuotasPreferringLimits() {
    podMetrics("team-a", "250m", "1Gi", "250m", "1Gi");
    podMetrics("team-b", "500m", "2Gi");
    quotas("team-a", Map.of("requests.cpu", "2", "limits.cpu", "4", "limits.memory", "8Gi", "pods", "20"));
    quotas("team-b", Map.of("limits.cpu", "4", "limits.memory", "8Gi", "pods", "20"),
        Map.of("limits.cpu", "2")); // two quotas: the stricter one applies

    ProjectUsage.Result r = ProjectUsage.measure(client, List.of("team-a", "team-b"),
        List.of(podRequesting("team-a", "1", "1Gi"), podRequesting("team-b", "1", "1Gi")));

    assertEquals(ProjectUsage.Basis.QUOTA, r.cpu().basis());
    assertEquals(1.0, r.cpu().used(), 1e-9);
    assertEquals(6.0, r.cpu().total(), 1e-9, "4 (limits beat requests) + 2 (stricter of two quotas)");
    assertEquals(ProjectUsage.Basis.QUOTA, r.memory().basis());
    assertEquals(4.0, r.memory().used(), 1e-9);
    assertEquals(16.0, r.memory().total(), 1e-9);
    assertEquals(new ProjectUsage.Figure(2, 40, ProjectUsage.Basis.QUOTA), r.pods());
    assertEquals(2, r.projectsMeasured());
  }

  @Test
  void aCpuOnlyQuotaLeavesMemoryMeasuredAgainstRequests() {
    podMetrics("team-a", "500m", "1Gi");
    quotas("team-a", Map.of("limits.cpu", "4"));

    ProjectUsage.Result r = ProjectUsage.measure(client, List.of("team-a"), List.of(podRequesting("team-a", "1", "2Gi")));

    assertEquals(new ProjectUsage.Figure(0.5, 4.0, ProjectUsage.Basis.QUOTA), r.cpu());
    assertEquals(ProjectUsage.Basis.REQUESTS, r.memory().basis());
    assertEquals(2.0, r.memory().total(), 1e-9, "no absurd memory total from the missing key");
    assertEquals(ProjectUsage.Basis.NONE, r.pods().basis());
  }

  @Test
  void anObjectCountOnlyQuotaGivesNoCpuOrMemoryQuota() {
    podMetrics("team-a", "100m", "512Mi");
    quotas("team-a", Map.of("pods", "10", "count/secrets", "50"));

    ProjectUsage.Result r = ProjectUsage.measure(client, List.of("team-a"), List.of(podRequesting("team-a", "200m", "1Gi")));

    assertEquals(ProjectUsage.Basis.REQUESTS, r.cpu().basis());
    assertEquals(0.2, r.cpu().total(), 1e-9);
    assertEquals(ProjectUsage.Basis.REQUESTS, r.memory().basis());
    assertEquals(new ProjectUsage.Figure(1, 10, ProjectUsage.Basis.QUOTA), r.pods());
  }

  @Test
  void quotasOfDifferentKindsAcrossProjectsAreNotMixed() {
    podMetrics("team-a", "500m", "1Gi");
    podMetrics("team-b", "500m", "1Gi");
    quotas("team-a", Map.of("limits.cpu", "4", "requests.cpu", "2"));
    quotas("team-b", Map.of("requests.cpu", "1"));     // no limits.cpu here

    ProjectUsage.Result r = ProjectUsage.measure(client, List.of("team-a", "team-b"), List.of());

    assertEquals(new ProjectUsage.Figure(1.0, 3.0, ProjectUsage.Basis.QUOTA), r.cpu(), "requests in both: 2 + 1");
  }

  @Test
  void aProjectWithoutQuotaMeansUsageIsMeasuredAgainstRequests() {
    podMetrics("team-a", "500m", "1Gi");
    podMetrics("team-b", "500m", "1Gi");
    quotas("team-a", Map.of("limits.cpu", "4", "limits.memory", "8Gi"));
    quotas("team-b"); // no quota

    ProjectUsage.Result r = ProjectUsage.measure(client, List.of("team-a", "team-b"),
        List.of(podRequesting("team-a", "1", "2Gi"), podRequesting("team-b", "500m", "1Gi"), bestEffortPod("team-b")));

    assertEquals(new ProjectUsage.Figure(1.0, 1.5, ProjectUsage.Basis.REQUESTS), r.cpu());
    assertEquals(new ProjectUsage.Figure(2.0, 3.0, ProjectUsage.Basis.REQUESTS), r.memory());
    assertEquals(3.0, r.pods().used(), "best-effort pods count as running pods");
  }

  @Test
  void noQuotaAndNoRequestsLeavesOnlyTheUsage() {
    podMetrics("team-a", "100m", "512Mi");
    quotas("team-a");

    ProjectUsage.Result r = ProjectUsage.measure(client, List.of("team-a"), List.of(bestEffortPod("team-a")));

    assertEquals(new ProjectUsage.Figure(0.1, 0, ProjectUsage.Basis.NONE), r.cpu());
    assertEquals(new ProjectUsage.Figure(0.5, 0, ProjectUsage.Basis.NONE), r.memory());
  }

  @Test
  void projectsWhoseMetricsAreRefusedLeaveTheUsageAndTheTotals() {
    podMetrics("team-a", "250m", "1Gi");
    forbiddenMetrics("team-b");
    quotas("team-a", Map.of("limits.cpu", "1"));
    quotas("team-b", Map.of("limits.cpu", "100"));

    ProjectUsage.Result r = ProjectUsage.measure(client, List.of("team-a", "team-b"),
        List.of(podRequesting("team-a", "1", "1Gi"), podRequesting("team-b", "50", "50Gi")));

    assertEquals(1, r.projectsMeasured());
    assertEquals(new ProjectUsage.Figure(0.25, 1.0, ProjectUsage.Basis.QUOTA), r.cpu(), "team-b's quota is not counted");
    assertEquals(1.0, r.memory().total(), 1e-9, "team-b's pods are not counted either");
    assertEquals(1.0, r.pods().used());
  }

  @Test
  void nothingMeasurableGivesNoResult() {
    forbiddenMetrics("team-a");
    assertNull(ProjectUsage.measure(client, List.of("team-a"), List.of()));
    assertNull(ProjectUsage.measure(client, List.of(), List.of()));
  }
}
