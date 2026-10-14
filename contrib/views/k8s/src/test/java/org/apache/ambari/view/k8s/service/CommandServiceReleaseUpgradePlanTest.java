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

import com.google.gson.reflect.TypeToken;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.service.deployment.ReleaseHeldInGitException;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.ambari.view.k8s.service.DeployPlanningFixture.NS;
import static org.apache.ambari.view.k8s.service.DeployPlanningFixture.adminHeaders;
import static org.apache.ambari.view.k8s.service.DeployPlanningFixture.step;
import static org.apache.ambari.view.k8s.service.DeployPlanningFixture.types;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * A chart upgrade of a release (Releases → Upgrade chart) keeps the values the release runs with: they are read from
 * the deployed release, not rebuilt from the chart's defaults, and the operation only logs in to the chart repository
 * and runs the Helm upgrade to the target version.
 */
class CommandServiceReleaseUpgradePlanTest {

    @TempDir
    Path workDir;

    private final KubernetesService k8s = mock(KubernetesService.class);
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private DeployPlanningFixture fixture;
    private Map<String, Object> deployed;

    @BeforeEach
    void setUp() throws Exception {
        ViewConfigurationService configuration = mock(ViewConfigurationService.class);
        when(configuration.writeCacheFile(anyString(), anyString())).thenAnswer(inv -> {
            String path = "cache/" + inv.getArgument(0);
            cache.put(path, inv.getArgument(1));
            return path;
        });
        when(k8s.getConfigurationService()).thenReturn(configuration);
        // What the release runs with: an operator's change on top of the chart, and the values a deploy wired.
        deployed = new LinkedHashMap<>();
        deployed.put("server", new LinkedHashMap<>(Map.of("workers", 5)));
        deployed.put("ingress", new LinkedHashMap<>(Map.of("enabled", true, "hosts", List.of("trino.example.org"))));
        deployed.put("global", new LinkedHashMap<>(Map.of("security", new LinkedHashMap<>(Map.of(
                "auth", new LinkedHashMap<>(Map.of("mode", "oidc")),
                "kerberos", new LinkedHashMap<>(Map.of("enabled", true, "realm", "EXAMPLE.COM")))))));
        when(k8s.getHelmReleaseValues(NS, "trino")).thenAnswer(inv -> deployed);
        fixture = new DeployPlanningFixture(workDir, ctx -> k8s);
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.close();
    }

    private void recordRelease(String deploymentMode, String version) {
        new ReleaseMetadataService(fixture.view.context).recordInstallOrUpgrade(NS, "trino", "TRINO", null,
                "trinodb/trino", "charts", version, null, "op-install", null, null, null, null, deploymentMode,
                null, null, null, null, null, null, null, null, null);
    }

    /** How many times the operation was handed to the timer, which never runs it. */
    private long scheduled() {
        return mockingDetails(fixture.timer).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("schedule")).count();
    }

    private String upgrade(String targetVersion) {
        return fixture.service.submitReleaseUpgrade(NS, "trino", targetVersion, adminHeaders(),
                URI.create(fixture.ambari.baseUri()));
    }

    @Test
    void theUpgradeLogsInToTheRepositoryThenUpgradesWithTheDeployedValues() throws Exception {
        recordRelease("DIRECT_HELM", "1.43.0");

        String rootId = upgrade("1.44.0");

        List<CommandEntity> steps = fixture.steps(rootId);
        assertEquals(List.of("HELM_REPO_LOGIN", "HELM_UPGRADE"), types(steps));
        assertEquals("charts", fixture.params(step(steps, "HELM_REPO_LOGIN")).get("repoId"));
        Map<String, Object> upgrade = fixture.params(step(steps, "HELM_UPGRADE"));
        assertEquals("trinodb/trino", upgrade.get("chart"));
        assertEquals("1.44.0", upgrade.get("version"));
        assertEquals("charts", upgrade.get("repoId"));
        assertEquals("trino", upgrade.get("releaseName"));
        assertEquals(NS, upgrade.get("namespace"));
        Map<String, Object> seeded = fixture.gson.fromJson(cache.get(String.valueOf(upgrade.get("valuesPath"))),
                new TypeToken<Map<String, Object>>() {}.getType());
        assertEquals(fixture.gson.toJsonTree(deployed), fixture.gson.toJsonTree(seeded),
                "the values the release runs with, not the chart's defaults");
    }

    @Test
    void theUpgradeIsQueuedNotRunWhileItIsSubmitted() throws Exception {
        recordRelease("DIRECT_HELM", "1.43.0");

        String rootId = upgrade("1.44.0");

        assertEquals(1, scheduled(), "submitted for execution once");
        CommandEntity root = fixture.view.dataStore.find(CommandEntity.class, rootId);
        for (String statusId : List.of(root.getCommandStatusId(),
                fixture.steps(rootId).get(0).getCommandStatusId(), fixture.steps(rootId).get(1).getCommandStatusId())) {
            assertEquals(CommandState.PENDING.name(),
                    fixture.view.dataStore.find(CommandStatusEntity.class, statusId).getState());
        }
    }

    @Test
    void anUpgradeToTheRunningVersionIsRefused() {
        recordRelease("DIRECT_HELM", "1.43.0");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> upgrade("1.43.0"));

        assertTrue(refused.getMessage().contains("already at version 1.43.0"), refused.getMessage());
        assertTrue(fixture.view.dataStore.findAll(CommandEntity.class, null).isEmpty(), "nothing planned");
    }

    @Test
    void aReleaseTheViewDoesNotManageIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> upgrade("1.44.0"));

        assertTrue(refused.getMessage().contains("is not managed by the UI"), refused.getMessage());
    }

    @Test
    void aGitOpsReleaseIsUpgradedInGitNotWithHelm() {
        recordRelease("FLUX_GITOPS", "1.43.0");

        assertThrows(ReleaseHeldInGitException.class, () -> upgrade("1.44.0"));
        assertTrue(fixture.view.dataStore.findAll(CommandEntity.class, null).isEmpty(), "nothing planned");
        assertEquals(0, scheduled());
    }
}
