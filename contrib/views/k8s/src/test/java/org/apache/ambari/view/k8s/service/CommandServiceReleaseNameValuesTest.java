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
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OpenMetadata's Service must carry the release's name whatever the release is called: the post-install steps
 * (base ingestion, Atlas federation, Ranger TagSync) call it by that name, and the chart only names its Service
 * that way when the release name already contains "openmetadata". The server sets the pin on every set of values
 * it sends to Helm, so a deploy that skips the wizard's bindings gets it too.
 */
class CommandServiceReleaseNameValuesTest {

    private static StackServiceDef openMetadata() throws Exception {
        try (InputStream in = CommandServiceReleaseNameValuesTest.class
                .getResourceAsStream("/KDPS/services/OPENMETADATA/service.json")) {
            assertNotNull(in, "OPENMETADATA/service.json must be on the classpath");
            return new ObjectMapper().readValue(in, StackServiceDef.class);
        }
    }

    @Test
    void openMetadataPinsTheChartNameToTheRelease() throws Exception {
        for (String release : List.of("datacatalog", "om-prod", "openmetadata", "my-openmetadata")) {
            Map<String, Object> values = new LinkedHashMap<>();
            CommandService.applyDefinitionValueRewrites(openMetadata(), "OPENMETADATA", release, values);
            assertEquals(release, values.get("fullnameOverride"), release);
        }
    }

    @Test
    void anOperatorsOwnNameIsReplacedAndOtherValuesAreKept() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fullnameOverride", "something-else");
        values.put("ranger", new LinkedHashMap<>(Map.of("tagSync", Map.of("enabled", true))));
        CommandService.applyDefinitionValueRewrites(openMetadata(), "OPENMETADATA", "datacatalog", values);
        assertEquals("datacatalog", values.get("fullnameOverride"));
        assertTrue(values.containsKey("ranger"));
    }

    @Test
    void aDefinitionWithoutPinsLeavesTheValuesAlone() {
        Map<String, Object> values = new LinkedHashMap<>();
        CommandService.applyDefinitionValueRewrites(new StackServiceDef(), "TRINO", "trino", values);
        assertFalse(values.containsKey("fullnameOverride"));
    }

    @Test
    void nothingIsSetWithoutAReleaseName() {
        Map<String, Object> values = new LinkedHashMap<>();
        assertTrue(CommandService.applyReleaseNameValues(values, List.of("fullnameOverride"), null).isEmpty());
        assertTrue(CommandService.applyReleaseNameValues(values, List.of("fullnameOverride"), " ").isEmpty());
        assertTrue(values.isEmpty());
    }

    @Test
    void anyDottedPathCanBePinned() {
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> applied = CommandService.applyReleaseNameValues(values, List.of("a.b", "c"), "rel");
        assertEquals(List.of("a.b", "c"), applied);
        assertEquals("rel", ConfigResolutionService.getByDottedPath(values, "a.b"));
        assertEquals("rel", values.get("c"));
    }

    @Test
    void endpointAndCertificateNamesUseTheReleaseName() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/KDPS/services/OPENMETADATA/service.json")) {
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(json.contains("{{releaseName}}-openmetadata.{{namespace}}"),
                    "the Service is named like the release, so no '<release>-openmetadata' host may be built");
            assertTrue(json.contains("\"{{releaseName}}.{{namespace}}.svc.cluster.local\""));
            assertTrue(json.contains("\"{{releaseName}}.{{namespace}}.svc\""));
        }
    }

    @Test
    void theEndpointOfAReleaseNamedWithoutOpenmetadataResolvesToItsService() throws Exception {
        StackServiceDef def = openMetadata();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("releaseName", "datacatalog");
        params.put("namespace", "data");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fullnameOverride", "datacatalog");
        values.put("openmetadata", Map.of("config", Map.of("port", 8585, "tls", Map.of("enabled", false))));
        List<Map<String, Object>> endpoints = new ConfigResolutionService()
                .computeEndpointsForRelease(def.endpoints, params, values, null, null);
        assertNotNull(endpoints);
        assertEquals("http://datacatalog.data.svc.cluster.local:8585", endpoints.get(0).get("url"));
    }
    /** A CommandService whose Kubernetes access answers the deployed values given. */
    private static CommandService withDeployedValues(Map<String, Object> deployed) throws Exception {
        CommandService service = mock(CommandService.class, CALLS_REAL_METHODS);
        KubernetesService kubernetes = mock(KubernetesService.class);
        when(kubernetes.getHelmReleaseValues("data", "datacatalog")).thenReturn(deployed);
        java.lang.reflect.Field field = CommandService.class.getDeclaredField("kubernetesService");
        field.setAccessible(true);
        field.set(service, kubernetes);
        return service;
    }

    private static HelmDeployRequest deployOf(String release) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace("data");
        request.setReleaseName(release);
        request.setServiceKey("OPENMETADATA");
        return request;
    }

    @Test
    void theOperatorIsToldWhenAReleaseOfAnEarlierVersionIsRenamed() throws Exception {
        // Deployed before the pin: the chart named its Service datacatalog-openmetadata.
        Map<String, Object> earlier = new LinkedHashMap<>(Map.of("replicaCount", 1));
        String warning = withDeployedValues(earlier).renamedByReleaseNamePin(openMetadata(), deployOf("datacatalog"));
        assertNotNull(warning);
        assertTrue(warning.contains("'datacatalog-openmetadata' to 'datacatalog'"), warning);
        assertTrue(warning.contains("redeploy the ingestion pipelines"), warning);
    }

    @Test
    void nothingIsSaidForAFirstInstallOrAReleaseAlreadyPinned() throws Exception {
        assertNull(withDeployedValues(Map.of()).renamedByReleaseNamePin(openMetadata(), deployOf("datacatalog")));
        assertNull(withDeployedValues(new LinkedHashMap<>(Map.of("fullnameOverride", "datacatalog")))
                .renamedByReleaseNamePin(openMetadata(), deployOf("datacatalog")));
    }
}
