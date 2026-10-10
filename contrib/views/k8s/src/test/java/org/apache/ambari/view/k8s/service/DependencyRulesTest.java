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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The dependencies a deploy installs, the same in direct Helm and GitOps mode. */
class DependencyRulesTest {

    KubernetesService k8s;
    HelmDeployRequest request;

    @BeforeEach
    void setUp() {
        k8s = mock(KubernetesService.class);
        request = new HelmDeployRequest();
        Map<String, Object> keda = new LinkedHashMap<>(Map.of("enabled", true));
        request.setValues(new LinkedHashMap<>(Map.of("server", new LinkedHashMap<>(Map.of("keda", keda)))));
    }

    private static Map<String, Object> spec(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void openShiftProvidesWhatIsFlaggedSkipOnOpenShift() {
        when(k8s.isOpenShiftCluster()).thenReturn(true);
        assertNotNull(DependencyRules.skipReason("keda", spec("skipOnOpenShift", true), request, k8s, (n, r) -> false));
        when(k8s.isOpenShiftCluster()).thenReturn(false);
        assertNull(DependencyRules.skipReason("keda", spec("skipOnOpenShift", true), request, k8s, (n, r) -> false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void kedaOnlyWhenAutoscalingIsOn() {
        Map<String, Object> keda = spec("onlyWhenValueTrue", "server.keda.enabled");
        assertNull(DependencyRules.skipReason("keda", keda, request, k8s, (n, r) -> false));
        ((Map<String, Object>) ((Map<String, Object>) request.getValues().get("server")).get("keda")).put("enabled", false);
        assertTrue(DependencyRules.skipReason("keda", keda, request, k8s, (n, r) -> false).contains("is off"));
    }

    @Test
    void anOperatorAlreadyServingTheCrdIsReused() {
        when(k8s.crdExists("scaledobjects.keda.sh")).thenReturn(true);
        assertNotNull(DependencyRules.skipReason("keda", spec("skipIfCrdExists", "scaledobjects.keda.sh"), request, k8s, (n, r) -> false));
        when(k8s.crdExists("scaledobjects.keda.sh")).thenThrow(new RuntimeException("boom"));
        assertNull(DependencyRules.skipReason("keda", spec("skipIfCrdExists", "scaledobjects.keda.sh"), request, k8s, (n, r) -> false),
                "a failed check installs, as before");
    }

    @Test
    void anExistingReleaseInTheChosenNamespaceIsReused() {
        Map<String, Object> keda = spec("skipIfReleaseExists", true, "namespace", "keda", "namespaceFromForm", "keda.namespace");
        DependencyRules.applyNamespaceFromForm(keda, Map.of("keda", Map.of("namespace", "autoscaling")));
        assertEquals("autoscaling", keda.get("namespace"));
        assertNotNull(DependencyRules.skipReason("keda", keda, request, k8s, (ns, name) -> ns.equals("autoscaling") && name.equals("keda")));
        assertNull(DependencyRules.skipReason("keda", keda, request, k8s, (ns, name) -> false));
    }

    @Test
    void aBlankFormNamespaceKeepsTheDefault() {
        Map<String, Object> keda = spec("namespace", "keda", "namespaceFromForm", "keda.namespace");
        DependencyRules.applyNamespaceFromForm(keda, Map.of("keda", Map.of("namespace", " ")));
        assertEquals("keda", keda.get("namespace"));
        DependencyRules.applyNamespaceFromForm(keda, null);
        assertEquals("keda", keda.get("namespace"));
    }

    @Test
    void noPrometheusRunningMeansInstall() {
        when(k8s.listPrometheusInstances()).thenReturn(List.of());
        assertNull(DependencyRules.skipReason("kube-prometheus-stack", spec("skipIfPrometheusPresent", true), request, k8s, (n, r) -> false));
    }
}
