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
import com.marcnuri.helm.Release;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.service.helm.HelmOperationRegistry;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import javax.ws.rs.core.MultivaluedHashMap;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The chart-upgrade and deploy steps of an operation, run against a mocked Helm client: a failed upgrade fails the
 * step and the operation (it is never passed off as a deploy), a release with a Helm call still running is
 * refused (a deploy as soon as it is submitted), an upgrade cut short earlier is recovered with a rollback the
 * operator can read in the operation's log, and the release metadata of a known release only takes a deploy's
 * configuration once Helm has applied it.
 */
class CommandServiceHelmStepTest {

    private static final String NS = "analytics";
    private static final String YAML_ERROR = "UPGRADE FAILED: YAML parse error on superset/templates/configmap.yaml: "
            + "error converting YAML to JSON: yaml: line 3: mapping values are not allowed in this context";

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Gson gson = new Gson();
    private InMemoryDataStore dataStore;
    private HelmClient helm;
    private KubernetesService k8s;
    private ViewContext ctx;
    private CommandService service;
    private String rel;

    /**
     * Steps run with slf4j's MDC. On the test classpath its log4j binding (brought by Hadoop) patches log4j's MDC
     * for Java 9 and later through a field reload4j no longer has, so MDC cannot load; reload4j needs no patch,
     * and the binding skips it when MDC is first loaded under a Java 8 version string.
     */
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
        Path workDir = Files.createTempDirectory("kdps-helm-step");
        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getInstanceName()).thenReturn("helm-step-" + Long.toHexString(System.nanoTime()));
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", workDir.toString()));
        dataStore = new InMemoryDataStore();
        when(ctx.getDataStore()).thenReturn(dataStore);

        helm = mock(HelmClient.class);
        k8s = mock(KubernetesService.class, RETURNS_DEEP_STUBS);
        when(k8s.getConfigurationService().getKubeconfigContents()).thenReturn("KC");
        service = new CommandService(ctx, new HelmService(ctx, helm, () -> mock(NamespaceScope.class)), k8s);

        // A release of its own per test: the in-flight registry is shared by the whole JVM.
        rel = "superset-" + Long.toHexString(System.nanoTime());
        Release deployed = mock(Release.class);
        when(deployed.getName()).thenReturn(rel);
        when(helm.list(NS, "KC", false)).thenReturn(List.of(deployed));
        // The previous revision is still deployed, which is what fooled the removed "parse warning" recovery.
        when(helm.list(NS, "KC", true)).thenReturn(List.of(deployed));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        pool.shutdownNow();
    }

    /** Stores an operation made of one chart-upgrade step, as submitReleaseUpgrade plans it, and returns its id. */
    private String upgradeOperation() {
        String id = "op-" + rel;
        Map<String, Object> rootParams = new LinkedHashMap<>();
        rootParams.put("chart", "superset");
        rootParams.put("releaseName", rel);
        rootParams.put("namespace", NS);
        rootParams.put("version", "0.12.43");

        Map<String, Object> stepParams = new LinkedHashMap<>(rootParams);
        stepParams.put("values", Map.of("replicaCount", 1));

        dataStore.store(command(id, CommandType.K8S_MANAGER_RELEASE_DEPLOY, "Upgrade release " + rel, rootParams,
                List.of(id + "-step")));
        dataStore.store(status(id, CommandState.RUNNING));
        dataStore.store(command(id + "-step", CommandType.HELM_UPGRADE, "Upgrade Helm release", stepParams, null));
        dataStore.store(status(id + "-step", CommandState.PENDING));
        return id;
    }

    /**
     * Stores an operation made of one deploy step, as directHelmDeploy plans a redeploy (the step carries the
     * release metadata to record once Helm has applied it), and returns its id.
     */
    private String deployOperation() {
        HelmRepoEntity repo = new HelmRepoEntity();
        repo.setId("charts");
        repo.setName("charts");
        repo.setType("OCI");
        repo.setUrl("registry.example.com/charts");
        repo.setAuthMode("anonymous");
        dataStore.store(repo);

        String id = "deploy-" + rel;
        Map<String, Object> rootParams = new LinkedHashMap<>();
        rootParams.put("chart", "superset");
        rootParams.put("releaseName", rel);
        rootParams.put("namespace", NS);
        rootParams.put("version", "0.12.43");
        rootParams.put("repoId", "charts");
        rootParams.put(CommandService.RELEASE_METADATA_PARAM, plannedMetadata());

        Map<String, Object> stepParams = new LinkedHashMap<>(rootParams);
        stepParams.put("values", Map.of("replicaCount", 1));

        dataStore.store(command(id, CommandType.K8S_MANAGER_RELEASE_DEPLOY, "Deploy release " + rel, rootParams,
                List.of(id + "-step")));
        dataStore.store(status(id, CommandState.RUNNING));
        dataStore.store(command(id + "-step", CommandType.HELM_DEPLOY, "Helm: install chart superset", stepParams, null));
        dataStore.store(status(id + "-step", CommandState.PENDING));
        return id;
    }

    /** The metadata KDPS holds for the release from its previous deploy. */
    private void knownRelease() {
        K8sReleaseEntity e = new K8sReleaseEntity();
        e.setId(K8sReleaseEntity.idOf(NS, rel));
        e.setNamespace(NS);
        e.setReleaseName(rel);
        e.setManagedByUi(true);
        e.setChartRef("superset");
        e.setVersion("0.12.42");
        e.setSecurityProfile("default");
        e.setSecurityProfileHash("profile-before");
        e.setGlobalConfigVersion("globals-before");
        e.setTruststoreRefs(List.of("corporate-ca"));
        dataStore.store(e);
    }

    /** What a deploy records: a new chart version, security profile, global configuration and truststores. */
    private static Map<String, Object> plannedMetadata() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chartRef", "superset");
        m.put("version", "0.12.43");
        m.put("securityProfile", "hardened");
        m.put("securityProfileHash", "profile-after");
        m.put("globalConfigVersion", "globals-after");
        m.put("truststoreRefs", List.of("corporate-ca", "partner-ca"));
        m.put("catalogRefsSet", true);
        m.put("catalogRefsJson", "{\"hive\":{\"id\":\"c1\",\"hash\":\"h1\"}}");
        return m;
    }

    private K8sReleaseEntity metadata() {
        return dataStore.find(K8sReleaseEntity.class, K8sReleaseEntity.idOf(NS, rel));
    }

    private void assertMetadataUnchanged() {
        K8sReleaseEntity e = metadata();
        assertEquals("0.12.42", e.getVersion());
        assertEquals("default", e.getSecurityProfile());
        assertEquals("profile-before", e.getSecurityProfileHash());
        assertEquals("globals-before", e.getGlobalConfigVersion());
        assertEquals(List.of("corporate-ca"), e.getTruststoreRefs());
        assertNull(e.getCatalogRefsJson());
    }

    private CommandEntity command(String id, CommandType type, String title, Map<String, Object> params,
                                  List<String> children) {
        CommandEntity c = new CommandEntity();
        c.setId(id);
        c.setType(type.name());
        c.setTitle(title);
        c.setCommandStatusId(id + "-status");
        c.setParamsJson(gson.toJson(params));
        if (children != null) c.setChildListJson(gson.toJson(children));
        return c;
    }

    private static CommandStatusEntity status(String commandId, CommandState state) {
        CommandStatusEntity s = new CommandStatusEntity();
        s.setId(commandId + "-status");
        s.setState(state.name());
        s.setAttempt(0);
        return s;
    }

    private CommandStatusEntity statusOf(String commandId) {
        return dataStore.find(CommandStatusEntity.class, commandId + "-status");
    }

    private Release upgradeCall() {
        return helm.upgrade(anyString(), any(), anyBoolean(), eq(rel), eq(NS), any(Path.class), eq("KC"),
                ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    void aTemplateThatRendersInvalidYamlFailsTheStepAndTheOperation() {
        when(upgradeCall()).thenThrow(new IllegalStateException(YAML_ERROR));
        String id = upgradeOperation();

        service.runNextStep(id);

        CommandStatusEntity step = statusOf(id + "-step");
        assertEquals(CommandState.FAILED.name(), step.getState(), step.getMessage());
        assertTrue(step.getMessage().contains("YAML parse error"), step.getMessage());
        CommandStatusEntity root = statusOf(id);
        assertEquals(CommandState.FAILED.name(), root.getState(), root.getMessage());
        assertTrue(root.getMessage().contains("Failed at step 'Upgrade Helm release'"), root.getMessage());
        verify(helm, never()).list(anyString(), anyString(), eq(true));
        verify(helm, never()).uninstall(anyString(), anyString(), anyString());
    }

    @Test
    void anUpgradeOfAReleaseWithAHelmCallStillRunningIsRefused() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch helmReturns = new CountDownLatch(1);
        // An earlier upgrade, canceled or not, whose native Helm call has not returned.
        Future<?> earlier = HelmOperationRegistry.submit(NS, rel, pool, () -> {
            running.countDown();
            return helmReturns.await(30, TimeUnit.SECONDS);
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        String id = upgradeOperation();
        try {
            service.runNextStep(id);
        } finally {
            helmReturns.countDown();
            earlier.get(5, TimeUnit.SECONDS);
        }

        CommandStatusEntity step = statusOf(id + "-step");
        assertEquals(CommandState.FAILED.name(), step.getState());
        assertTrue(step.getMessage().contains("is still running"), step.getMessage());
        assertEquals(CommandState.FAILED.name(), statusOf(id).getState());
        verify(helm, never()).upgrade(anyString(), any(), anyBoolean(), anyString(), anyString(), any(Path.class),
                anyString(), ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(),
                anyBoolean());
    }

    @Test
    void aDeploySubmittedWhileAHelmCallOnTheReleaseRunsIsRefusedBeforeAnythingIsDone() throws Exception {
        knownRelease();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch helmReturns = new CountDownLatch(1);
        // An earlier upgrade the operator canceled, whose native Helm call has not returned.
        Future<?> earlier = HelmOperationRegistry.submit(NS, rel, pool, () -> {
            running.countDown();
            return helmReturns.await(30, TimeUnit.SECONDS);
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        HelmDeployRequest redeploy = new HelmDeployRequest();
        redeploy.setChart("superset");
        redeploy.setReleaseName(rel);
        redeploy.setNamespace(NS);
        redeploy.setValues(new LinkedHashMap<>(Map.of("replicaCount", 2)));
        HelmOperationRegistry.ReleaseBusyException refused;
        try {
            refused = assertThrows(HelmOperationRegistry.ReleaseBusyException.class,
                    () -> service.submitDeploy(redeploy, "charts", "0.12.43", null, "http://ambari/commands",
                            new MultivaluedHashMap<>(), URI.create("http://ambari/api/v1"), null));
        } finally {
            helmReturns.countDown();
            earlier.get(5, TimeUnit.SECONDS);
        }

        assertTrue(refused.getMessage().contains("is still running"), refused.getMessage());
        // Nothing was written to the cluster, planned or recorded.
        verify(k8s, never()).createNamespace(anyString());
        verifyNoInteractions(helm);
        assertTrue(dataStore.findAll(CommandEntity.class, null).isEmpty(), "no operation was planned");
        assertMetadataUnchanged();
    }

    @Test
    void anUpgradeCutShortEarlierIsRolledBackAndTheOperatorIsTold() throws Exception {
        when(upgradeCall())
                .thenThrow(new IllegalStateException("UPGRADE FAILED: another operation (install/upgrade/rollback) is in progress"))
                .thenReturn(mock(Release.class));
        Map<String, Object> deployed = new HashMap<>(Map.of("revision", 2, "status", "deployed"));
        Map<String, Object> pending = new HashMap<>(Map.of("revision", 3, "status", "pending-upgrade"));
        when(helm.history(rel, NS, "KC")).thenReturn(List.of(deployed, pending));
        String id = upgradeOperation();

        service.runNextStep(id);

        assertEquals(CommandState.SUCCEEDED.name(), statusOf(id + "-step").getState(), statusOf(id + "-step").getMessage());
        verify(helm).rollback(rel, NS, 2, "KC");
        verify(helm, never()).uninstall(anyString(), anyString(), anyString());
        String operationLog = awaitLog(id, "Rolled back release " + NS + "/" + rel + " to revision 2");
        assertTrue(operationLog.contains("rolling back to revision 2"), operationLog);
        assertTrue(awaitLog(id + "-step", "Rolled back release").contains("revision 2"),
                "the step's own log carries it too");
    }

    @Test
    void aCanceledUpgradeSaysHelmIsStillRunningAndKeepsTheReleaseUntilItReturns() throws Exception {
        CountDownLatch upgradeStarted = new CountDownLatch(1);
        CountDownLatch helmReturns = new CountDownLatch(1);
        when(upgradeCall()).thenAnswer(invocation -> {
            upgradeStarted.countDown();
            // Like the native Helm call: the interrupt sent by the cancel does not stop it.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                try {
                    if (helmReturns.await(1, TimeUnit.SECONDS)) break;
                } catch (InterruptedException ignored) {
                    // keep going
                }
            }
            return mock(Release.class);
        });
        String id = upgradeOperation();
        Future<?> step = pool.submit(() -> service.runNextStep(id));
        try {
            assertTrue(upgradeStarted.await(10, TimeUnit.SECONDS));

            service.cancel(id);
            step.get(10, TimeUnit.SECONDS);

            CommandStatusEntity stepStatus = statusOf(id + "-step");
            assertEquals(CommandState.CANCELED.name(), stepStatus.getState());
            assertTrue(stepStatus.getMessage().contains("is still running and cannot be interrupted"), stepStatus.getMessage());
            CommandStatusEntity root = statusOf(id);
            assertEquals(CommandState.CANCELED.name(), root.getState());
            assertTrue(root.getMessage().contains("still running"), root.getMessage());
            assertTrue(HelmOperationRegistry.isRunning(NS, rel), "the release stays held while Helm runs");
            assertThrows(HelmOperationRegistry.ReleaseBusyException.class, () -> HelmOperationRegistry.requireIdle(NS, rel));
        } finally {
            helmReturns.countDown();
        }
        for (int i = 0; i < 500 && HelmOperationRegistry.isRunning(NS, rel); i++) {
            Thread.sleep(10);
        }
        assertFalse(HelmOperationRegistry.isRunning(NS, rel), "the release is free once Helm returns");
    }

    @Test
    void aRedeployThatFailsToRenderLeavesTheReleaseMetadataUnchanged() {
        knownRelease();
        when(upgradeCall()).thenThrow(new IllegalStateException(YAML_ERROR));
        String id = deployOperation();

        service.runNextStep(id);

        CommandStatusEntity step = statusOf(id + "-step");
        assertEquals(CommandState.FAILED.name(), step.getState(), step.getMessage());
        assertTrue(step.getMessage().contains("YAML parse error"), step.getMessage());
        assertEquals(CommandState.FAILED.name(), statusOf(id).getState());
        assertMetadataUnchanged();
    }

    @Test
    void aRedeployRecordsItsReleaseMetadataOnceHelmHasAppliedIt() {
        knownRelease();
        when(upgradeCall()).thenReturn(mock(Release.class));
        String id = deployOperation();

        service.runNextStep(id);

        assertEquals(CommandState.SUCCEEDED.name(), statusOf(id + "-step").getState(), statusOf(id + "-step").getMessage());
        K8sReleaseEntity e = metadata();
        assertEquals("0.12.43", e.getVersion());
        assertEquals("hardened", e.getSecurityProfile());
        assertEquals("profile-after", e.getSecurityProfileHash());
        assertEquals("globals-after", e.getGlobalConfigVersion());
        assertEquals(List.of("corporate-ca", "partner-ca"), e.getTruststoreRefs());
        assertEquals("{\"hive\":{\"id\":\"c1\",\"hash\":\"h1\"}}", e.getCatalogRefsJson());
        verify(k8s, never()).restartReleaseWorkloads(anyString(), anyString());
    }

    @Test
    void aResyncOfAReleaseFlaggedForARestartRestartsItsPodsAndOnlyThenClearsTheFlag() {
        knownRelease();
        new ReleaseMetadataService(ctx).markRestartRequired(NS, rel);
        when(upgradeCall()).thenReturn(mock(Release.class));
        when(k8s.restartReleaseWorkloads(NS, rel)).thenReturn(List.of("Deployment " + rel + "-web"));
        String id = deployOperation();

        service.runNextStep(id);

        assertEquals(CommandState.SUCCEEDED.name(), statusOf(id + "-step").getState(), statusOf(id + "-step").getMessage());
        verify(k8s).restartReleaseWorkloads(NS, rel);
        assertFalse(ReleaseMetadataService.restartMarked(metadata()));
        assertEquals("globals-after", metadata().getGlobalConfigVersion());
    }

    @Test
    void aReleaseWhosePodsCouldNotBeRestartedStaysFlagged() {
        knownRelease();
        new ReleaseMetadataService(ctx).markRestartRequired(NS, rel);
        when(upgradeCall()).thenReturn(mock(Release.class));
        when(k8s.restartReleaseWorkloads(NS, rel)).thenThrow(new IllegalStateException("deployments is forbidden"));
        String id = deployOperation();

        service.runNextStep(id);

        assertEquals(CommandState.SUCCEEDED.name(), statusOf(id + "-step").getState(), statusOf(id + "-step").getMessage());
        assertTrue(ReleaseMetadataService.restartMarked(metadata()), "the pods still hold the old keytab");
    }

    @Test
    void aDeployOfAKnownReleaseRecordsNothingWhenItIsSubmitted() {
        knownRelease();
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> planned = plannedMetadata();

        service.planReleaseMetadata(params, NS, rel, planned, Map.of());

        assertSame(planned, params.get(CommandService.RELEASE_METADATA_PARAM), "carried to the Helm step");
        assertMetadataUnchanged();
    }

    @Test
    void aNewReleaseIsRecordedAsSoonAsItsDeployIsSubmitted() {
        Map<String, Object> params = new LinkedHashMap<>();

        service.planReleaseMetadata(params, NS, rel, plannedMetadata(), Map.of());

        K8sReleaseEntity e = metadata();
        assertNotNull(e, "a release being installed is known to KDPS");
        assertTrue(e.isManagedByUi());
        assertEquals("0.12.43", e.getVersion());
        assertEquals("profile-after", e.getSecurityProfileHash());
        assertEquals(List.of("corporate-ca", "partner-ca"), e.getTruststoreRefs());
        assertNotNull(params.get(CommandService.RELEASE_METADATA_PARAM));
    }

    /**
     * "Upgrade chart to X" on the Releases page sends the release's values back to Helm without the wizard. Those of a
     * Superset release deployed before the SECRET_KEY moved to extraSecretEnv hold it in extraEnv, which the chart
     * writes as a plain value into every pod spec: the upgrade moves it to the env Secret, unchanged (a new key would
     * leave the database passwords Superset stored undecryptable).
     */
    @Test
    @SuppressWarnings("unchecked")
    void aChartUpgradeFromTheReleasesPageMovesTheSupersetSecretKeyOutOfThePodSpecs() {
        K8sReleaseEntity e = new K8sReleaseEntity();
        e.setId(K8sReleaseEntity.idOf(NS, rel));
        e.setNamespace(NS);
        e.setReleaseName(rel);
        e.setManagedByUi(true);
        e.setServiceKey("SUPERSET");
        e.setChartRef("superset");
        e.setRepoId("charts");
        e.setVersion("0.12.47");
        dataStore.store(e);
        Map<String, Object> deployed = new LinkedHashMap<>();
        deployed.put("extraEnv", new LinkedHashMap<>(Map.of("SUPERSET_SECRET_KEY", "Key-In-Use-4",
                "OPENSTREETMAP_SERVER_URL", "https://tile.example.com")));
        when(k8s.getHelmReleaseValues(NS, rel)).thenReturn(deployed);
        List<String> valuesFiles = new ArrayList<>();
        when(k8s.getConfigurationService().writeCacheFile(anyString(), anyString())).thenAnswer(inv -> {
            valuesFiles.add(inv.getArgument(1));
            return "/cache/values.json";
        });

        service.submitReleaseUpgrade(NS, rel, "0.12.49", new MultivaluedHashMap<>(),
                URI.create("http://ambari.example.com:8080/api/v1"));

        assertEquals(1, valuesFiles.size());
        Map<String, Object> sent = gson.fromJson(valuesFiles.get(0), Map.class);
        assertEquals("Key-In-Use-4", ConfigResolutionService.getByDottedPath(sent, "extraSecretEnv.SUPERSET_SECRET_KEY"));
        assertEquals(Map.of("OPENSTREETMAP_SERVER_URL", "https://tile.example.com"), sent.get("extraEnv"));
    }

    /**
     * "Upgrade chart to X" sends the release's values back without the wizard, so nothing else tells the operator that
     * a Trino catalog of the release still writes its password into the catalog ConfigMap: the operation log does,
     * naming the catalog and never the password.
     */
    @Test
    void aChartUpgradeFromTheReleasesPageWarnsAboutACatalogCredentialWrittenOut() throws Exception {
        K8sReleaseEntity e = new K8sReleaseEntity();
        e.setId(K8sReleaseEntity.idOf(NS, rel));
        e.setNamespace(NS);
        e.setReleaseName(rel);
        e.setManagedByUi(true);
        e.setServiceKey("TRINO");
        e.setChartRef("trino");
        e.setRepoId("charts");
        e.setVersion("1.43.15");
        dataStore.store(e);
        Map<String, Object> deployed = new LinkedHashMap<>();
        deployed.put("catalogs", new LinkedHashMap<>(Map.of("pg", "connector.name=postgresql\nconnection-password=hunter2\n")));
        when(k8s.getHelmReleaseValues(NS, rel)).thenReturn(deployed);
        when(k8s.getConfigurationService().writeCacheFile(anyString(), anyString())).thenReturn("/cache/values.json");

        String id = service.submitReleaseUpgrade(NS, rel, "1.43.16", new MultivaluedHashMap<>(),
                URI.create("http://ambari.example.com:8080/api/v1"));

        String log = awaitLog(id, "[WARN] Catalog credentials written out: 'pg' writes connection-password out");
        assertFalse(log.contains("hunter2"), log);
    }

    /** The command log is written asynchronously; waits for a line to show up. */
    private String awaitLog(String commandId, String expected) throws InterruptedException {
        String content = "";
        for (int i = 0; i < 200; i++) {
            content = CommandLogService.get(ctx).read(commandId, 0, 1 << 16).content;
            if (content.contains(expected)) return content;
            Thread.sleep(25);
        }
        fail("'" + expected + "' never reached the log of " + commandId + ": " + content);
        return content;
    }

    /** Keeps entities by class and id, like the view's data store. */
    private static final class InMemoryDataStore implements DataStore {
        private final Map<Class<?>, Map<Object, Object>> byClass = new ConcurrentHashMap<>();

        @Override
        public void store(Object entity) {
            byClass.computeIfAbsent(entity.getClass(), k -> new ConcurrentHashMap<>()).put(idOf(entity), entity);
        }

        @Override
        public void remove(Object entity) {
            Map<Object, Object> m = byClass.get(entity.getClass());
            if (m != null) m.remove(idOf(entity));
        }

        @Override
        public <T> T find(Class<T> clazz, Object primaryKey) {
            Map<Object, Object> m = byClass.get(clazz);
            return m == null || primaryKey == null ? null : clazz.cast(m.get(primaryKey));
        }

        @Override
        public <T> Collection<T> findAll(Class<T> clazz, String whereClause) {
            Map<Object, Object> m = byClass.get(clazz);
            List<T> out = new ArrayList<>();
            if (m != null) m.values().forEach(v -> out.add(clazz.cast(v)));
            return out;
        }

        private static Object idOf(Object entity) {
            try {
                return entity.getClass().getMethod("getId").invoke(entity);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Entity without getId(): " + entity.getClass(), e);
            }
        }
    }
}
