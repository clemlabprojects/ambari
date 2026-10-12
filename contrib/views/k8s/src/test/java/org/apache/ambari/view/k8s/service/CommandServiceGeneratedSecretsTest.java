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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.model.stack.GeneratedSecret;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A deploy of a service that declares {@code generatedSecrets}: the Secret is created before the Helm or GitOps steps
 * are planned, those steps get its name and never its value, the operator is told where to read the password, and
 * every deploy sets the password on the account until one of them succeeds.
 */
class CommandServiceGeneratedSecretsTest {

    private static final String NS = "analytics";
    private static final String SERVICE = "ZZTESTGENSECRETS";

    @TempDir
    Path tmp;

    private final Gson gson = new Gson();
    private InMemoryDataStore dataStore;
    private KubernetesService k8s;
    private CommandService service;
    private String rel;
    /** The Secrets of the namespace, by name, as the mocked cluster keeps them. */
    private final Map<String, Map<String, String>> secrets = new ConcurrentHashMap<>();
    /** Their annotations, by Secret name. */
    private final Map<String, Map<String, String>> annotations = new ConcurrentHashMap<>();

    /** Steps run with slf4j's MDC once an operation is scheduled: see CommandServiceGitOpsDeployTest. */
    @BeforeAll
    static void loadMdc() {
        String javaVersion = System.getProperty("java.version");
        System.setProperty("java.version", "1.8");
        try {
            org.slf4j.MDC.getMDCAdapter();
        } finally {
            System.setProperty("java.version", javaVersion);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        String instance = "gensecrets-" + Long.toHexString(System.nanoTime());
        ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getInstanceName()).thenReturn(instance);
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir",
                Files.createDirectories(tmp.resolve("work")).toString()));
        when(ctx.getInstanceData(anyString())).thenReturn(null);
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
        dataStore = new InMemoryDataStore();
        when(ctx.getDataStore()).thenReturn(dataStore);

        HelmRepoEntity charts = new HelmRepoEntity();
        charts.setId("charts");
        charts.setName("charts");
        charts.setType("HTTP");
        charts.setUrl("https://charts.example.org");
        charts.setAuthMode("anonymous");
        dataStore.store(charts);

        Map<String, String> cache = new ConcurrentHashMap<>();
        ViewConfigurationService configuration = mock(ViewConfigurationService.class);
        when(configuration.writeCacheFile(anyString(), anyString())).thenAnswer(inv -> {
            String path = "cache/" + inv.getArgument(0);
            cache.put(path, inv.getArgument(1));
            return path;
        });
        when(configuration.readCacheFile(anyString())).thenAnswer(inv -> cache.get((String) inv.getArgument(0)));
        k8s = mock(KubernetesService.class);
        when(k8s.getConfigurationService()).thenReturn(configuration);
        when(k8s.getSecret(eq(NS), anyString())).thenAnswer(inv -> secret(inv.getArgument(1)));
        doAnswer(inv -> {
            Map<String, String> data = inv.getArgument(2);
            Map<String, String> added = inv.getArgument(4);
            secrets.computeIfAbsent(inv.getArgument(1), k -> new LinkedHashMap<>()).putAll(data);
            annotations.computeIfAbsent(inv.getArgument(1), k -> new LinkedHashMap<>()).putAll(added);
            return null;
        }).when(k8s).writeOpaqueSecretKeys(eq(NS), anyString(), anyMap(), anyMap(), anyMap());
        doAnswer(inv -> {
            Map<String, String> current = annotations.get((String) inv.getArgument(1));
            if (current != null) current.remove((String) inv.getArgument(2));
            return null;
        }).when(k8s).removeSecretAnnotation(eq(NS), anyString(), anyString());
        when(k8s.persistentVolumeClaimExists(anyString(), anyString())).thenReturn(false);

        HelmService helm = mock(HelmService.class, RETURNS_DEEP_STUBS);
        when(helm.getRepositoryService().showValuesAsMap(any(), any(), any())).thenReturn(new LinkedHashMap<>());
        when(helm.getRepositoryService().getEffectiveImageRegistry(any())).thenReturn("registry.example.org");

        service = new CommandService(ctx, helm, k8s);
        rel = "zz-" + Long.toHexString(System.nanoTime());
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private Secret secret(String name) {
        Map<String, String> data = secrets.get(name);
        if (data == null) return null;
        Map<String, String> encoded = new LinkedHashMap<>();
        data.forEach((k, v) -> encoded.put(k, Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8))));
        return new SecretBuilder().withNewMetadata().withName(name).withNamespace(NS)
                .withAnnotations(new LinkedHashMap<>(annotations.getOrDefault(name, Map.of()))).endMetadata()
                .withData(encoded).build();
    }

    /**
     * Ends the operation as the orchestrator does once its last step is done: every step succeeded, or the Helm
     * install failed (e.g. its --wait timed out before the chart's post-upgrade hook ran).
     */
    private CommandStatusEntity end(String rootId, boolean helmSucceeds) {
        for (CommandEntity step : steps(rootId)) {
            CommandStatusEntity status = dataStore.find(CommandStatusEntity.class, step.getCommandStatusId());
            boolean failed = !helmSucceeds && CommandType.HELM_DEPLOY.name().equals(step.getType());
            status.setState((failed ? CommandState.FAILED : CommandState.SUCCEEDED).name());
            dataStore.store(status);
        }
        service.runNextStep(rootId);
        CommandEntity root = dataStore.find(CommandEntity.class, rootId);
        return dataStore.find(CommandStatusEntity.class, root.getCommandStatusId());
    }

    private HelmDeployRequest request() {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setChart("example/zz-test-gensecrets");
        request.setReleaseName(rel);
        request.setNamespace(NS);
        request.setServiceKey(SERVICE);
        request.setRepoId("charts");
        request.setValues(new LinkedHashMap<>(Map.of("replicaCount", 1)));
        return request;
    }

    private String plan(String chartVersion, boolean gitOps) {
        return service.planReleaseDeploy(request(), "charts", chartVersion, null, null, null, null, gitOps);
    }

    private Map<String, Object> paramsOf(CommandEntity command) {
        Type mapType = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();
        return gson.fromJson(CommandUtils.resolveParamsPayload(command.getParamsJson()), mapType);
    }

    private List<CommandEntity> steps(String rootId) {
        Type listType = new TypeToken<ArrayList<String>>() {}.getType();
        List<String> ids = gson.fromJson(dataStore.find(CommandEntity.class, rootId).getChildListJson(), listType);
        List<CommandEntity> out = new ArrayList<>();
        for (String id : ids) out.add(dataStore.find(CommandEntity.class, id));
        return out;
    }

    private CommandEntity step(String rootId, CommandType type) {
        return steps(rootId).stream().filter(s -> type.name().equals(s.getType())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " step"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> overridesOf(CommandEntity command) {
        Object ov = paramsOf(command).get("_ov");
        return ov instanceof Map ? (Map<String, String>) ov : Map.of();
    }

    /** Every parameter the operation keeps, as stored: none may hold a generated password. */
    private String storedParams(String rootId) {
        StringBuilder all = new StringBuilder(CommandUtils.resolveParamsPayload(
                dataStore.find(CommandEntity.class, rootId).getParamsJson()));
        for (CommandEntity step : steps(rootId)) all.append(CommandUtils.resolveParamsPayload(step.getParamsJson()));
        return all.toString();
    }

    @Test
    void theHelmStepsGetTheGeneratedSecretsNameNeverItsPassword() {
        String id = plan("1.0.0", false);

        String password = secrets.get(rel + "-zz-admin").get("password");
        assertTrue(password.matches("[A-Za-z0-9]{32}"), password);
        for (CommandType type : List.of(CommandType.HELM_DEPLOY_DRY_RUN, CommandType.HELM_DEPLOY)) {
            Map<String, String> overrides = overridesOf(step(id, type));
            assertEquals(rel + "-zz-admin", overrides.get("admin.existingSecret"), type.name());
            assertEquals("true", overrides.get("admin.resetPassword"), type.name());
        }
        assertFalse(storedParams(id).contains(password), "the password is kept in the Secret only");
    }

    @Test
    void theOperatorIsToldWhereToReadThePassword() {
        String id = plan("1.0.0", false);

        Map<String, Object> rootParams = paramsOf(dataStore.find(CommandEntity.class, id));
        Object notices = rootParams.get(CommandService.NOTICES_PARAM);
        assertEquals(List.of("ZZ administrator: its password is in the Secret " + NS + "/" + rel + "-zz-admin. Read it"
                + " with: kubectl -n " + NS + " get secret " + rel + "-zz-admin -o jsonpath='{.data.password}'"
                + " | base64 -d"), notices);
        Map<String, Object> result = CommandService.withNotices(new LinkedHashMap<>(Map.of("message", "done")), rootParams);
        assertEquals(notices, result.get("notices"));
        assertEquals("done", result.get("message"), "the operation's other results are kept");
        assertNull(CommandService.withNotices(null, Map.of()), "no notices, no result");
    }

    @Test
    void aRedeployAfterADeployThatSucceededKeepsThePasswordAndLeavesTheAccountAlone() {
        String first = plan("1.0.0", false);
        String password = secrets.get(rel + "-zz-admin").get("password");
        assertEquals(List.of(Map.of("namespace", NS, "secretName", rel + "-zz-admin")),
                paramsOf(dataStore.find(CommandEntity.class, first)).get(CommandService.PENDING_CREDENTIALS_PARAM));
        assertEquals(CommandState.SUCCEEDED.name(), end(first, true).getState());

        String id = plan("1.0.0", false);

        assertEquals(password, secrets.get(rel + "-zz-admin").get("password"));
        assertEquals("false", overridesOf(step(id, CommandType.HELM_DEPLOY)).get("admin.resetPassword"));
        assertEquals(rel + "-zz-admin", overridesOf(step(id, CommandType.HELM_DEPLOY)).get("admin.existingSecret"));
        assertNull(paramsOf(dataStore.find(CommandEntity.class, id)).get(CommandService.PENDING_CREDENTIALS_PARAM));
    }

    @Test
    void aDeployThatFailedLeavesItToTheNextOneToSetThePassword() {
        String first = plan("1.0.0", false);
        String password = secrets.get(rel + "-zz-admin").get("password");
        assertEquals(CommandState.FAILED.name(), end(first, false).getState());

        String retry = plan("1.0.0", false);

        assertEquals(password, secrets.get(rel + "-zz-admin").get("password"), "the password is not generated again");
        assertEquals("true", overridesOf(step(retry, CommandType.HELM_DEPLOY)).get("admin.resetPassword"),
                "the administrator still has the chart's default: the retry sets the generated password on it");
        assertEquals(CommandState.SUCCEEDED.name(), end(retry, true).getState());

        String later = plan("1.0.0", false);

        assertEquals("false", overridesOf(step(later, CommandType.HELM_DEPLOY)).get("admin.resetPassword"));
    }

    @Test
    void aGitOpsCommitGetsTheSecretsNameOnly() {
        String id = plan("1.0.0", true);

        String password = secrets.get(rel + "-zz-admin").get("password");
        assertEquals(rel + "-zz-admin", overridesOf(step(id, CommandType.GITOPS_COMMIT)).get("admin.existingSecret"));
        assertFalse(storedParams(id).contains(password));
    }

    @Test
    void aChartTooOldToReadTheSecretKeepsItsOwnCredentials() {
        String id = plan("0.9.0", false);

        assertNull(secrets.get(rel + "-zz-admin"), "nothing is generated for a chart that cannot read it");
        assertFalse(overridesOf(step(id, CommandType.HELM_DEPLOY)).containsKey("admin.existingSecret"));
        assertNull(paramsOf(dataStore.find(CommandEntity.class, id)).get(CommandService.NOTICES_PARAM));
    }

    /** The deploy's operation, found in the store: a refused deploy returns no id. */
    private CommandEntity onlyDeploy() {
        List<CommandEntity> roots = dataStore.findAll(CommandEntity.class, null).stream()
                .filter(c -> CommandType.K8S_MANAGER_RELEASE_DEPLOY.name().equals(c.getType())).toList();
        assertEquals(1, roots.size(), "one deploy operation");
        return roots.get(0);
    }

    /** The statuses of the operation's steps that would still run: none, once it is refused. */
    private List<String> stepsLeftToRun(CommandEntity root) {
        return dataStore.findAll(CommandStatusEntity.class, null).stream()
                .filter(st -> !st.getId().equals(root.getCommandStatusId()))
                .filter(st -> !CommandState.CANCELED.name().equals(st.getState())
                        && !CommandState.FAILED.name().equals(st.getState()))
                .map(st -> st.getId() + "=" + st.getState()).toList();
    }

    /**
     * Asserts that a refused deploy ended as the permission refusal ends one: failed with the reason, none of its steps
     * left to run, and resume refused, so it is not listed as running for good.
     */
    private void assertClosedAsRefused(String reason) {
        CommandEntity root = onlyDeploy();
        assertFalse(steps(root.getId()).isEmpty(), "the plan was stored before the refusal");
        CommandStatusEntity rootStatus = dataStore.find(CommandStatusEntity.class, root.getCommandStatusId());
        assertEquals(CommandState.FAILED.name(), rootStatus.getState());
        assertEquals(reason, rootStatus.getMessage(), "the operation says why nothing ran");
        assertEquals(List.of(), stepsLeftToRun(root), "no step of a refused deploy may be left to run");

        IllegalStateException notResumed = assertThrows(IllegalStateException.class,
                () -> service.resume(root.getId(), null));

        assertTrue(notResumed.getMessage().contains("refused before it started"), notResumed.getMessage());
        assertEquals(List.of(), stepsLeftToRun(root), "a refused resume leaves every step as it was");
        assertTrue(dataStore.findAll(K8sReleaseEntity.class, null).isEmpty(), "a refused deploy leaves no release record");
    }

    @Test
    void aDeployRefusedForADatabasePasswordItCannotFindEndsFailed() {
        when(k8s.persistentVolumeClaimExists(NS, "data-" + rel + "-db-0")).thenReturn(true);
        HelmDeployRequest request = request();
        request.getValues().put("database", new LinkedHashMap<>(Map.of("enabled", true)));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.planReleaseDeploy(request, "charts", "1.0.0", null, null, null, null, false));

        assertTrue(refused.getMessage().contains("data-" + rel + "-db-0"), refused.getMessage());
        assertNull(secrets.get(rel + "-zz-db"), "no password the database would reject");
        assertClosedAsRefused(refused.getMessage());
    }

    @Test
    void aDeployWhoseCredentialsSecretCannotBeWrittenEndsFailed() {
        doThrow(new RuntimeException("Failed to write Secret " + NS + "/" + rel + "-zz-admin: forbidden"))
                .when(k8s).writeOpaqueSecretKeys(eq(NS), eq(rel + "-zz-admin"), anyMap(), anyMap(), anyMap());

        RuntimeException refused = assertThrows(RuntimeException.class, () -> plan("1.0.0", false));

        assertClosedAsRefused(refused.getMessage());
    }

    @Test
    void aChartUpgradeFromTheReleasesPageGetsTheSecretToo() {
        K8sReleaseEntity release = new K8sReleaseEntity();
        release.setId(K8sReleaseEntity.idOf(NS, rel));
        release.setNamespace(NS);
        release.setReleaseName(rel);
        release.setManagedByUi(true);
        release.setServiceKey(SERVICE);
        release.setChartRef("example/zz-test-gensecrets");
        release.setRepoId("charts");
        release.setVersion("0.9.0");
        dataStore.store(release);
        when(k8s.getHelmReleaseValues(NS, rel)).thenReturn(new LinkedHashMap<>(Map.of("replicaCount", 1)));

        String id = service.submitReleaseUpgrade(NS, rel, "1.0.0", null, URI.create("http://ambari:8080/api"));

        String password = secrets.get(rel + "-zz-admin").get("password");
        Map<String, String> overrides = overridesOf(step(id, CommandType.HELM_UPGRADE));
        assertEquals(rel + "-zz-admin", overrides.get("admin.existingSecret"));
        assertEquals("true", overrides.get("admin.resetPassword"),
                "the administrator the release had before gets the generated password");
        assertFalse(storedParams(id).contains(password));
    }

    /** A deploy of the OpenMetadata definition KDPS ships, with the given authentication mode. */
    private String planOpenMetadata(String authMode) {
        HelmDeployRequest request = request();
        request.setChart("openmetadata");
        request.setServiceKey("OPENMETADATA");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("global", new LinkedHashMap<>(Map.of("security",
                new LinkedHashMap<>(Map.of("auth", new LinkedHashMap<>(Map.of("mode", authMode)))))));
        request.setValues(values);
        return service.planReleaseDeploy(request, "charts", "1.13.35", null, null, null, null, false);
    }

    /**
     * OpenMetadata clears its administrators' passwords while it runs with an OIDC or LDAP profile, and gives them its
     * default 'admin' at its first start in basic authentication, where only the chart's Job sets the generated one. A
     * deploy that succeeded in another mode never ran that Job, so the deploy that brings basic authentication back
     * must still ask for it.
     */
    @Test
    void openMetadataAsksForItsAdministratorPasswordAfterADeployInAnotherModeSucceeded() {
        String resetOnUpgrade = "openmetadata.config.authorization.adminPassword.resetOnUpgrade";
        String oidc = planOpenMetadata("oidc");
        String password = secrets.get(rel + "-openmetadata-admin").get("password");
        assertNull(paramsOf(dataStore.find(CommandEntity.class, oidc)).get(CommandService.PENDING_CREDENTIALS_PARAM),
                "nothing waits for a deploy to succeed: every deploy asks");
        assertEquals(CommandState.SUCCEEDED.name(), end(oidc, true).getState());

        String basic = planOpenMetadata("basic");

        Map<String, String> overrides = overridesOf(step(basic, CommandType.HELM_DEPLOY));
        assertEquals(rel + "-openmetadata-admin",
                overrides.get("openmetadata.config.authorization.adminPassword.existingSecret"));
        assertEquals("true", overrides.get(resetOnUpgrade),
                "the Job sets the generated password on the administrators OpenMetadata gave 'admin'");
        assertEquals(password, secrets.get(rel + "-openmetadata-admin").get("password"), "the same password");
        assertFalse(annotations.getOrDefault(rel + "-openmetadata-admin", Map.of())
                .containsKey(GeneratedSecretService.PENDING_ANNOTATION));
        assertEquals(CommandState.SUCCEEDED.name(), end(basic, true).getState());

        assertEquals("true", overridesOf(step(planOpenMetadata("basic"), CommandType.HELM_DEPLOY)).get(resetOnUpgrade),
                "and every later deploy, so the Secret stays the password the administrators sign in with");
    }

    /** The definitions KDPS ships: each generated Secret is read by the chart version they pin. */
    @Test
    void theShippedDefinitionsPinAChartThatReadsTheirGeneratedSecrets() throws Exception {
        StackDefinitionService definitions = new StackDefinitionService();
        for (String key : List.of("SUPERSET", "OPENMETADATA")) {
            StackServiceDef def = definitions.getServiceDefinition(key);
            assertNotNull(def.generatedSecrets, key);
            for (GeneratedSecret secret : def.generatedSecrets) {
                assertTrue(CommandService.isChartVersionCompatible(def.version, secret.chartVersion),
                        key + " pins " + def.version + ", " + secret.key + " needs " + secret.chartVersion);
                assertTrue(secret.values.containsValue("{{secretName}}"), key + "/" + secret.key);
                assertTrue(secret.secretNameTemplate.startsWith("{{releaseName}}-"), key + "/" + secret.key);
            }
        }
        StackServiceDef superset = definitions.getServiceDefinition("SUPERSET");
        Map<String, String> supersetValues = new LinkedHashMap<>();
        superset.generatedSecrets.forEach(s -> supersetValues.putAll(s.values));
        assertEquals("{{secretName}}", supersetValues.get("init.adminUser.existingSecret"));
        assertEquals("{{generated}}", supersetValues.get("init.adminUser.resetPassword"));
        assertEquals("{{secretName}}", supersetValues.get("postgresql.auth.existingSecret"));
        assertEquals("{{secretName}}", supersetValues.get("redis.auth.existingSecret"));
    }
}
