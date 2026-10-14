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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.apache.ambari.view.k8s.service.DeployPlanningFixture.NS;
import static org.apache.ambari.view.k8s.service.DeployPlanningFixture.adminHeaders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Releases → "Ranger policy…" on a Trino release of the managed cluster: the policy is handed to the Ambari server
 * ({@code ranger_policy}), which holds the Ranger administrator's password, on the release's own Ranger repository or
 * on the cluster's tag service, and the call returns once Ambari has applied it.
 */
class CommandServiceReleaseRangerPolicyTest {

    @TempDir
    Path workDir;

    private final KubernetesService k8s = mock(KubernetesService.class);
    private final List<JsonObject> posted = new CopyOnWriteArrayList<>();
    private Map<String, Object> deployed;
    private volatile String requestStatus = "COMPLETED";
    private DeployPlanningFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        deployed = new LinkedHashMap<>();
        deployed.put("accessControl", Map.of("type", "ranger"));
        deployed.put("ranger", Map.of("serviceName", "trino-analytics"));
        when(k8s.getHelmReleaseValues(NS, "trino")).thenAnswer(inv -> deployed);
        fixture = new DeployPlanningFixture(workDir, ctx -> k8s);
        fixture.kerberos(true);
        fixture.ranger();
        fixture.ambari.answer("POST /api/v1/clusters/c1/ranger_policy", request -> {
            posted.add(JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject());
            return json("{\"Requests\":{\"id\":7,\"status\":\"Accepted\"}}");
        });
        fixture.ambari.answer("GET /api/v1/clusters/c1/requests/7",
                request -> json("{\"Requests\":{\"id\":7,\"request_status\":\"" + requestStatus + "\"}}"));
        new ReleaseMetadataService(fixture.view.context).recordInstallOrUpgrade(NS, "trino", "TRINO", null,
                "trinodb/trino", "charts", "1.43.0", null, "op-install", null, null, null, null, "DIRECT_HELM",
                null, null, null, null, null, null, null, null, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.close();
    }

    private Map<String, Object> createPolicy(Map<String, Object> body) throws Exception {
        return fixture.service.createReleaseRangerPolicy(NS, "trino", body, adminHeaders(),
                URI.create(fixture.ambari.baseUri()));
    }

    @Test
    void aMaskingPolicyGoesToTheReleaseRepositoryThroughAmbari() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("target", "resource");
        // In the order the dialog sends the levels, which the policy name follows.
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("catalog", List.of("hive"));
        resources.put("schema", List.of("sales"));
        resources.put("table", List.of("customers"));
        resources.put("column", List.of("email"));
        body.put("resources", resources);
        body.put("policyType", 1);
        body.put("maskType", "MASK_HASH");
        body.put("users", "alice");

        Map<String, Object> result = createPolicy(body);

        assertEquals("trino-analytics", result.get("rangerServiceName"));
        assertEquals("kdps-trino-mask-hive.sales.customers.email", result.get("policyName"));
        assertEquals("ambari-server-action", result.get("via"));
        assertEquals(7, result.get("requestId"));
        assertEquals(1, posted.size(), fixture.ambari.requests::toString);
        JsonObject policy = posted.get(0).getAsJsonObject("RangerPolicy");
        assertEquals("trino-analytics", policy.get("rangerServiceName").getAsString());
        assertEquals("alice", policy.get("userName").getAsString());
        assertEquals("select", policy.get("accessTypes").getAsString(), "a mask applies to what is selected");
        assertEquals(1, policy.get("policyType").getAsInt());
        assertEquals("MASK_HASH", policy.get("maskType").getAsString());
        assertEquals(JsonParser.parseString("{\"catalog\":[\"hive\"],\"schema\":[\"sales\"],"
                        + "\"table\":[\"customers\"],\"column\":[\"email\"]}"),
                JsonParser.parseString(policy.get("resourcesJson").getAsString()));
        assertFalse(posted.get(0).toString().contains(DeployPlanningFixture.RANGER_ADMIN_PASSWORD),
                "Ambari applies it with the administrator password it holds");
    }

    @Test
    void aTagPolicyGoesToTheClusterTagServiceWithComponentPrefixedAccesses() throws Exception {
        Map<String, Object> result = createPolicy(new LinkedHashMap<>(Map.of("target", "tag", "tag", "PII",
                "groups", "analysts", "accessTypes", "select")));

        assertEquals("c1_tag", result.get("rangerServiceName"));
        assertEquals("trino:select", result.get("accessTypes"));
        JsonObject policy = posted.get(0).getAsJsonObject("RangerPolicy");
        assertEquals("c1_tag", policy.get("rangerServiceName").getAsString());
        assertEquals("analysts", policy.get("groups").getAsString());
        assertFalse(policy.has("userName"));
        assertEquals(JsonParser.parseString("{\"tag\":[\"PII\"]}"),
                JsonParser.parseString(policy.get("resourcesJson").getAsString()));
    }

    @Test
    void aPolicyAmbariFailsToApplyIsReported() {
        requestStatus = "FAILED";

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> createPolicy(
                new LinkedHashMap<>(Map.of("resources", Map.of("catalog", List.of("hive")), "users", "alice",
                        "accessTypes", "select"))));

        assertTrue(refused.getMessage().contains("request 7"), refused.getMessage());
    }

    @Test
    void aReleaseNotAuthorizedByRangerIsRefusedBeforeAmbariIsAsked() {
        deployed.put("accessControl", Map.of("type", "file"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> createPolicy(
                new LinkedHashMap<>(Map.of("resources", Map.of("catalog", List.of("hive")), "users", "alice"))));

        assertTrue(refused.getMessage().contains("is not authorized by Ranger"), refused.getMessage());
        assertTrue(posted.isEmpty());
    }

    @Test
    void aMaskOnAWildcardColumnIsRefusedBeforeAmbariIsAsked() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> createPolicy(
                new LinkedHashMap<>(Map.of("resources", Map.of("catalog", List.of("hive"), "column", List.of("*")),
                        "policyType", 1, "maskType", "MASK_HASH", "users", "alice"))));

        assertTrue(refused.getMessage().contains("exactly ONE column"), refused.getMessage());
        assertTrue(posted.isEmpty());
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body);
    }
}
