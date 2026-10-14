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

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.apache.ambari.view.k8s.service.DeployPlanningFixture.overrides;
import static org.apache.ambari.view.k8s.service.DeployPlanningFixture.step;
import static org.apache.ambari.view.k8s.service.DeployPlanningFixture.types;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The plan a deploy of TRINO or SUPERSET stores, with the cluster's Kerberos, Ranger and OIDC on and off, against the
 * checked-in snapshots under {@code golden-plans/}: the ordered step types and the parameters that say what each step
 * does (the principal of a keytab, the Keycloak client and its redirect URI, the Ranger repository, the chart values
 * that wire them). A change to a service definition or to the planning code that drops, reorders or rewires a step
 * shows here, not on a customer's cluster.
 *
 * <p>The deploy is planned as the wizard submits it, from the shipped service definition, against a fake Ambari
 * reached through the deploy's base URI and a mock Kubernetes API. No secret the plan handles may be stored in clear:
 * the view's database keeps only references to encrypted parameter files.</p>
 *
 * <p>When a plan changes on purpose, replace the plan in the snapshot with the one the failure message prints.</p>
 */
@EnableKubernetesMockClient(crud = true)
class CommandServiceGoldenPlanTest {

    /** Root parameters printed with the release's Helm steps: the chart values that wire Kerberos, Ranger and OIDC. */
    private static final List<String> WIRING_PREFIXES = List.of("global.security.kerberos.", "global.security.auth.",
            "ranger.", "hadoopConf.");
    /** The parameters that say what a step does, by step type. */
    private static final Map<String, List<String>> KEY_PARAMS = Map.ofEntries(
            Map.entry("KEYTAB_ISSUE_PRINCIPAL", List.of("principalFqdn", "secretName", "keyNameInSecret", "kerberosEntryKey")),
            Map.entry("KEYTAB_CREATE_SECRET", List.of("principalFqdn", "secretName", "keyNameInSecret", "kerberosEntryKey")),
            Map.entry("OIDC_REGISTER_CLIENT", List.of("oidcClientId", "oidcRedirectUri", "oidcSecretName", "oidcEntryKey",
                    "publicClient", "standardFlowEnabled", "implicitFlowEnabled", "oidcAudiences")),
            Map.entry("OIDC_CREATE_SECRET", List.of("oidcClientId", "oidcSecretName", "oidcEntryKey")),
            Map.entry("RANGER_REPOSITORY_CREATION", List.of("_rangerRepositoryName", "_rangerServiceType",
                    "_rangerPluginUserName", "_rangerServiceConfigs")),
            Map.entry("HELM_REPO_LOGIN", List.of("repoId")),
            Map.entry("HELM_DEPLOY_DRY_RUN", List.of("chart", "_dynamicOverrides", "_ov")),
            Map.entry("HELM_DEPLOY", List.of("chart", "_dynamicOverrides", "_ov")),
            Map.entry("HELM_DEPLOY_DRY_RUN_DEPENDENCY_RELEASE", List.of("chart", "namespace", "_dynamicOverrides")),
            Map.entry("HELM_DEPLOY_DEPENDENCY_RELEASE", List.of("chart", "namespace", "_dynamicOverrides")),
            Map.entry("HELM_DEPLOY_DEPENDENCY_CRD", List.of("crd", "namespace")),
            Map.entry("CONFIG_MATERIALIZE", List.of("serviceKey")));

    @TempDir
    Path workDir;

    KubernetesMockServer server;
    KubernetesClient client;

    private DeployPlanningFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new DeployPlanningFixture(workDir, ctx -> new KubernetesService(ctx, client, true));
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.close();
    }

    // ------------------------------------------------------------------------------------------------- TRINO

    @Test
    void trinoWithKerberosRangerAndOidc() throws Exception {
        fixture.secureCluster();

        assertPlan("TRINO-secure", fixture.plan(fixture.trino(true)));
    }

    @Test
    void trinoWithoutKerberosRangerOrOidc() throws Exception {
        fixture.plainCluster();

        assertPlan("TRINO-plain", fixture.plan(fixture.trino(false)));
    }

    // ---------------------------------------------------------------------------------------------- SUPERSET

    @Test
    void supersetWithKerberosAndOidc() throws Exception {
        fixture.secureCluster();

        assertPlan("SUPERSET-secure", fixture.plan(fixture.superset()));
    }

    @Test
    void supersetWithoutKerberosOrOidc() throws Exception {
        fixture.plainCluster();

        assertPlan("SUPERSET-plain", fixture.plan(fixture.superset()));
    }

    @Test
    void supersetOnAClusterWithoutKeycloakAddressGetsNoOidcAndNoHalfBuiltIssuer() throws Exception {
        fixture.secureCluster();
        // oidc-env is there, but with neither the issuer nor the Keycloak address: there is no provider to register a
        // client with, and an issuer built from the realm alone ("/realms/odp") would loop every sign-in.
        fixture.ambari.config("oidc-env", Map.of("oidc_issuer_url", "", "oidc_admin_url", "", "oidc_realm", "odp"));

        String rootId = fixture.plan(fixture.superset());

        List<CommandEntity> steps = fixture.steps(rootId);
        assertTrue(steps.stream().noneMatch(s -> s.getType().startsWith("OIDC_")), types(steps)::toString);
        for (CommandEntity step : steps) {
            String params = CommandUtils.resolveParamsPayload(step.getParamsJson());
            assertFalse(params.contains("\"/realms/"), step.getType() + " carries a half-built issuer: " + params);
        }
        Map<String, Object> helm = fixture.params(step(steps, "HELM_DEPLOY"));
        assertFalse(overrides(helm).containsKey("global.security.auth.oidc.issuerUrl"), overrides(helm)::toString);
        assertFalse(overrides(helm).containsKey("global.security.auth.mode"), "deployed without OIDC sign-in");
    }

    // ----------------------------------------------------------------------------------------------- helpers

    private void assertPlan(String name, String rootId) throws IOException {
        List<CommandEntity> steps = fixture.steps(rootId);
        String actual = render(steps);
        assertNoSecretStoredInClear();
        String resource = "golden-plans/" + name + ".txt";
        String expected;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "No snapshot " + resource + "; the plan is:\n" + actual);
            // The license header and the comments of the snapshot are not part of the plan.
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.startsWith("#")).collect(Collectors.joining("\n"));
        }
        assertEquals(expected.strip(), actual.strip(), "The " + name + " plan changed. If the change is intended,"
                + " replace the plan in src/test/resources/" + resource + " (below its header) with:\n" + actual);
        assertEquals(List.of(), fixture.ambari.unanswered, "Ambari calls the plan made that the fake Ambari does not answer");
    }

    /** One line per step, in plan order: its type, then its key parameters, sorted. */
    private String render(List<CommandEntity> steps) {
        StringBuilder out = new StringBuilder();
        for (CommandEntity step : steps) {
            Map<String, Object> params = fixture.params(step);
            out.append(step.getType());
            for (String key : KEY_PARAMS.getOrDefault(step.getType(), List.of())) {
                Object value = params.get(key);
                if ("_ov".equals(key)) {
                    value = wiring(overrides(params));
                } else if (value instanceof Map<?, ?> map) {
                    value = new TreeMap<>(map);
                }
                if (value != null && !(value instanceof Map<?, ?> m && m.isEmpty())) {
                    out.append("\n    ").append(key).append(" = ").append(value);
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** The chart values under {@link #WIRING_PREFIXES}, sorted. */
    private static Map<String, Object> wiring(Map<String, Object> overrides) {
        Map<String, Object> wiring = new TreeMap<>();
        overrides.forEach((path, value) -> {
            if (WIRING_PREFIXES.stream().anyMatch(path::startsWith)) wiring.put(path, value);
        });
        return wiring;
    }

    /**
     * The view's database keeps only references to the parameter files, those files are encrypted, and no step result
     * or message holds a password of the cluster or a Secret value the plan wrote to Kubernetes.
     */
    private void assertNoSecretStoredInClear() throws IOException {
        Set<String> secrets = new LinkedHashSet<>(List.of(DeployPlanningFixture.KEYCLOAK_ADMIN_SECRET,
                DeployPlanningFixture.RANGER_ADMIN_PASSWORD, DeployPlanningFixture.KDC_ADMIN_PASSWORD));
        for (Secret secret : client.secrets().inAnyNamespace().list().getItems()) {
            if (secret.getData() == null) continue;
            for (String encoded : secret.getData().values()) {
                String value = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                // Long enough not to match by chance; a certificate or keystore is not a password.
                if (value.length() >= 12 && !value.contains("\n")) secrets.add(value);
            }
        }
        List<String> stored = new ArrayList<>();
        for (CommandEntity command : fixture.view.dataStore.findAll(CommandEntity.class, null)) {
            assertTrue(command.getParamsJson() == null
                            || command.getParamsJson().startsWith(CommandParamsStore.FILE_REFERENCE),
                    "the parameters of " + command.getType() + " are kept in the database: " + command.getParamsJson());
            stored.add(command.getParamsJson());
            stored.add(command.getTitle());
            if (command.getParamsJson() != null) {
                stored.add(Files.readString(Path.of(command.getParamsJson()
                        .substring(CommandParamsStore.FILE_REFERENCE.length())), StandardCharsets.UTF_8));
            }
        }
        for (CommandStatusEntity status : fixture.view.dataStore.findAll(CommandStatusEntity.class, null)) {
            stored.add(status.getResultJson());
            stored.add(status.getMessage());
        }
        for (String secret : secrets) {
            for (String text : stored) {
                assertFalse(text != null && text.contains(secret), "a secret is stored in clear: " + text);
            }
        }
    }
}
