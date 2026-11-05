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

package org.apache.ambari.view.k8s.service.deployment;

import org.apache.ambari.view.k8s.model.HelmReleaseDTO;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A release written with a GitOps profile and applied without Helm (Argo CD renders the chart and applies it): the
 * Releases page tells from its workloads whether it runs, the chart version it runs against the one in Git, and which
 * Argo CD application applied it.
 */
class FluxGitOpsBackendObservedReleaseTest {

    private static K8sReleaseEntity committed(String chartRef, String version) {
        K8sReleaseEntity meta = new K8sReleaseEntity();
        meta.setChartRef(chartRef);
        meta.setVersion(version);
        return meta;
    }

    private static Map<String, Object> workload(int replicas, int ready, Map<String, String> labels,
                                                Map<String, String> annotations) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("kind", "Deployment");
        w.put("name", "superset");
        w.put("replicas", replicas);
        w.put("readyReplicas", ready);
        w.put("labels", labels);
        w.put("annotations", annotations);
        return w;
    }

    @Test
    void allWorkloadsReadyIsRunningWithTheVersionAndTheArgoCdApplication() {
        List<Map<String, Object>> workloads = List.of(
                workload(1, 1, Map.of("chart", "superset-0.12.54", "app.kubernetes.io/instance", "superset-prod"), Map.of()),
                workload(2, 2, Map.of(), Map.of("argocd.argoproj.io/tracking-id", "superset-prod:apps/Deployment:bi/x")));

        HelmReleaseDTO dto = FluxGitOpsBackend.observedRelease("bi", "superset", committed("superset", "0.12.54"), workloads);

        assertEquals("deployed", dto.status);
        assertEquals(Boolean.TRUE, dto.installed);
        assertEquals("0.12.54", dto.runningVersion);
        assertEquals("Argo CD application superset-prod", dto.appliedBy);
        assertFalse(dto.message.contains("not applied yet"), dto.message);
    }

    @Test
    void anOlderChartOnTheClusterIsReportedAsNotAppliedYet() {
        List<Map<String, Object>> workloads = List.of(
                workload(1, 0, Map.of("helm.sh/chart", "clemlab-trino-1.43.20"), Map.of()));

        HelmReleaseDTO dto = FluxGitOpsBackend.observedRelease("bi", "trino", committed("charts/clemlab-trino", "1.43.21"),
                workloads);

        assertEquals("pending_upgrade", dto.status, "a workload is not ready");
        assertEquals("1.43.20", dto.runningVersion);
        assertTrue(dto.message.contains("Git holds chart 1.43.21, the cluster runs 1.43.20"), dto.message);
        assertNull(dto.appliedBy, "nothing says what applied it");
    }

    @Test
    void aSubChartWorkloadDoesNotGiveTheReleaseItsVersion() {
        List<Map<String, Object>> workloads = List.of(
                workload(1, 1, Map.of("helm.sh/chart", "postgresql-17.0.1", "app.kubernetes.io/instance", "superset"), Map.of()),
                workload(1, 1, Map.of("chart", "superset-0.12.53", "release", "superset"), Map.of()));

        assertEquals("0.12.53", FluxGitOpsBackend.runningChartVersion("superset", workloads));
        assertNull(FluxGitOpsBackend.runningChartVersion("superset", workloads.subList(0, 1)));
    }

    @Test
    void theReleaseNameInTheInstanceLabelIsNotAnArgoCdApplication() {
        assertNull(FluxGitOpsBackend.appliedBy("superset",
                List.of(workload(1, 1, Map.of("app.kubernetes.io/instance", "superset"), Map.of()))));
    }

    @Test
    void theCommittedValuesNameTheReleaseInTheirCommonLabels() {
        Map<String, Object> values = new LinkedHashMap<>(Map.of("commonLabels", Map.of("team", "bi")));

        FluxGitOpsBackend.addGitOpsReleaseLabel(values, "superset");

        assertEquals(Map.of("team", "bi", KubernetesService.GITOPS_RELEASE_LABEL, "superset"), values.get("commonLabels"));
    }
}
