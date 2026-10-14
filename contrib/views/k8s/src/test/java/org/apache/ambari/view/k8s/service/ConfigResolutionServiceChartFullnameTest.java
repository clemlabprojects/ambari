/**
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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The endpoint of a release must name the Service the chart really creates, which the chart's fullname helper
 * decides: the release name when it contains the chart's name, else {@code <release>-<chart>}.
 */
class ConfigResolutionServiceChartFullnameTest {

    @Test
    void followsTheChartsFullnameRule() {
        assertEquals("superset", ConfigResolutionService.chartFullname("superset", "superset", null));
        assertEquals("my-superset-1", ConfigResolutionService.chartFullname("superset", "my-superset-1", Map.of()));
        assertEquals("bi-superset", ConfigResolutionService.chartFullname("superset", "bi", Map.of()));
    }

    @Test
    void fullnameOverrideAndNameOverrideWin() {
        assertEquals("pinned", ConfigResolutionService.chartFullname("superset", "bi", Map.of("fullnameOverride", "pinned")));
        assertEquals("bi-analytics", ConfigResolutionService.chartFullname("superset", "bi", Map.of("nameOverride", "analytics")));
        assertEquals("bi", ConfigResolutionService.chartFullname("superset", "bi", Map.of("nameOverride", "b")));
    }

    @Test
    void isCutToSixtyThreeCharactersWithoutATrailingDash() {
        assertEquals(63, ConfigResolutionService.chartFullname("superset", "a".repeat(60), null).length());
        // 62 letters and a dash fit before the cut, so the dash that ends the name is dropped.
        assertEquals("c".repeat(62), ConfigResolutionService.chartFullname("superset", "c".repeat(62) + "-d", null));
        assertNull(ConfigResolutionService.chartFullname(null, "bi", null));
    }

    @Test
    void theSupersetEndpointNamesTheServiceTheChartCreates() throws Exception {
        StackServiceDef def;
        try (InputStream in = getClass().getResourceAsStream("/KDPS/services/SUPERSET/service.json")) {
            assertNotNull(in);
            def = new ObjectMapper().readValue(in, StackServiceDef.class);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("service", Map.of("port", 8088));
        assertEquals("http://superset.bi.svc.cluster.local:8088/", urlOf(def, "superset", "bi", values));
        assertEquals("http://bi-superset.bi.svc.cluster.local:8088/", urlOf(def, "bi", "bi", values));
    }

    private static String urlOf(StackServiceDef def, String release, String namespace, Map<String, Object> values) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("releaseName", release);
        params.put("namespace", namespace);
        List<Map<String, Object>> endpoints = new ConfigResolutionService()
                .computeEndpointsForRelease(def.endpoints, params, values, null, null);
        assertNotNull(endpoints);
        return String.valueOf(endpoints.get(0).get("url"));
    }
}
