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
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.deployment.DeploymentBackend;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import javax.ws.rs.core.MultivaluedHashMap;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JupyterHub's user scheduler is off on OpenShift for every deploy, not only those from the wizard: the chart gives it a
 * ClusterRole and a ClusterRoleBinding, which an account limited to its own projects cannot create, and which a
 * cluster-admin Flux would create silently, handing a project service account cluster-wide pod rights.
 */
class CommandServiceOpenShiftValueDefaultsTest {

    private static final String USER_SCHEDULER = "scheduling.userScheduler.enabled";

    @TempDir
    Path tmp;

    private ViewContext ctx;
    private KubernetesService k8s;
    private DeploymentBackend direct;
    private DeploymentBackend gitOps;

    /** A chart upgrade runs its steps with slf4j's MDC: see CommandServiceHelmStepTest. */
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
        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", tmp.resolve("work").toString()));
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getInstanceName()).thenReturn("kdps");
        when(ctx.getInstanceData(anyString())).thenReturn(null);
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
        when(ctx.getDataStore()).thenReturn(mock(DataStore.class));
        k8s = mock(KubernetesService.class);
        direct = mock(DeploymentBackend.class);
        gitOps = mock(DeploymentBackend.class);
        when(direct.apply(any(), any())).thenReturn("command-1");
        when(gitOps.apply(any(), any())).thenReturn("commit-1");
    }

    /** A CommandService wired to the mocks, without the executors and singletons its constructor starts. */
    private CommandService service() throws Exception {
        CommandService service = mock(CommandService.class, CALLS_REAL_METHODS);
        set(service, "ctx", ctx);
        set(service, "kubernetesService", k8s);
        Map<String, DeploymentBackend> backends = new ConcurrentHashMap<>();
        backends.put("DIRECT_HELM", direct);
        backends.put("FLUX_GITOPS", gitOps);
        set(service, "deploymentBackends", backends);
        return service;
    }

    private static void set(CommandService service, String field, Object value) throws Exception {
        Field f = CommandService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    /** A JupyterHub deploy sent straight to the API: no wizard bindings ran on its values. */
    private static HelmDeployRequest jupyterHub(String deploymentMode, Map<String, Object> values) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setChart("jupyterhub");
        request.setReleaseName("jupyterhub");
        request.setNamespace("data");
        request.setServiceKey("JUPYTERHUB");
        request.setRepoId("charts");
        request.setValues(values);
        request.setDeploymentMode(deploymentMode);
        return request;
    }

    /** The values the deployment backend was handed. */
    private Map<String, Object> deployedValues(DeploymentBackend backend, HelmDeployRequest request) throws Exception {
        service().submitDeploy(request, null, null, null, null, null, null, null);
        ArgumentCaptor<HelmDeployRequest> sent = ArgumentCaptor.forClass(HelmDeployRequest.class);
        verify(backend).apply(sent.capture(), any());
        return sent.getValue().getValues();
    }

    @Test
    void anApiDeployOnOpenShiftTurnsTheUserSchedulerOff() throws Exception {
        when(k8s.isOpenShiftCluster()).thenReturn(true);

        Map<String, Object> values = deployedValues(direct, jupyterHub("DIRECT_HELM", new LinkedHashMap<>()));

        assertEquals(false, ConfigResolutionService.getByDottedPath(values, USER_SCHEDULER));
    }

    @Test
    void aGitOpsDeployOnOpenShiftTurnsTheUserSchedulerOffToo() throws Exception {
        when(k8s.isOpenShiftCluster()).thenReturn(true);

        Map<String, Object> values = deployedValues(gitOps, jupyterHub("FLUX_GITOPS", null));

        assertEquals(false, ConfigResolutionService.getByDottedPath(values, USER_SCHEDULER),
                "the HelmRelease Flux applies must not render the user scheduler's ClusterRole");
    }

    @Test
    void theUserSchedulerKeepsTheChartDefaultOnPlainKubernetes() throws Exception {
        when(k8s.isOpenShiftCluster()).thenReturn(false);

        Map<String, Object> values = deployedValues(direct, jupyterHub("DIRECT_HELM", new LinkedHashMap<>()));

        assertNull(ConfigResolutionService.getByDottedPath(values, USER_SCHEDULER));
    }

    @Test
    void anOperatorWhoTurnsTheUserSchedulerOnKeepsIt() throws Exception {
        when(k8s.isOpenShiftCluster()).thenReturn(true);
        Map<String, Object> requested = new LinkedHashMap<>();
        CommandService.putDotted(requested, USER_SCHEDULER, true);
        CommandService.putDotted(requested, "scheduling.userScheduler.replicas", 1);

        Map<String, Object> values = deployedValues(direct, jupyterHub("DIRECT_HELM", requested));

        assertEquals(true, ConfigResolutionService.getByDottedPath(values, USER_SCHEDULER));
        assertEquals(1, ConfigResolutionService.getByDottedPath(values, "scheduling.userScheduler.replicas"));
    }

    /**
     * "Upgrade chart" on the Releases page sends the release's own values back to Helm without the wizard or
     * submitDeploy: a JupyterHub installed on OpenShift before the default existed loses its user scheduler there too.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aChartUpgradeOnOpenShiftTurnsTheUserSchedulerOffForAReleaseInstalledWithIt() throws Exception {
        ViewContext upgradeCtx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(upgradeCtx.getInstanceName()).thenReturn("kdps-" + Long.toHexString(System.nanoTime()));
        when(upgradeCtx.getUsername()).thenReturn("admin");
        when(upgradeCtx.getProperties()).thenReturn(Map.of("k8s.view.working.dir",
                Files.createDirectories(tmp.resolve("upgrade")).toString()));
        InMemoryDataStore dataStore = new InMemoryDataStore();
        when(upgradeCtx.getDataStore()).thenReturn(dataStore);
        KubernetesService cluster = mock(KubernetesService.class, RETURNS_DEEP_STUBS);
        List<String> valuesFiles = new ArrayList<>();
        when(cluster.getConfigurationService().writeCacheFile(anyString(), anyString())).thenAnswer(inv -> {
            valuesFiles.add(inv.getArgument(1));
            return "/cache/values.json";
        });
        // A release of its own: the in-flight registry is shared by the whole JVM.
        String rel = "jupyterhub-" + Long.toHexString(System.nanoTime());
        Map<String, Object> deployed = new LinkedHashMap<>();
        CommandService.putDotted(deployed, "hub.config.JupyterHub.admin_access", true);
        when(cluster.getHelmReleaseValues("data", rel)).thenReturn(deployed);
        CommandService upgrades = new CommandService(upgradeCtx,
                new HelmService(upgradeCtx, mock(HelmClient.class), () -> mock(NamespaceScope.class)), cluster);
        try {
            upgrades.startupSweep.get(30, TimeUnit.SECONDS);
            doReturn(true).when(cluster).isOpenShiftCluster();
            K8sReleaseEntity release = new K8sReleaseEntity();
            release.setId(K8sReleaseEntity.idOf("data", rel));
            release.setNamespace("data");
            release.setReleaseName(rel);
            release.setManagedByUi(true);
            release.setServiceKey("JUPYTERHUB");
            release.setChartRef("jupyterhub");
            release.setRepoId("charts");
            release.setVersion("4.3.4");
            dataStore.store(release);

            upgrades.submitReleaseUpgrade("data", rel, "4.3.5", new MultivaluedHashMap<>(),
                    URI.create("http://ambari.example.com:8080/api/v1"));

            assertEquals(1, valuesFiles.size());
            Map<String, Object> sent = new Gson().fromJson(valuesFiles.get(0), Map.class);
            assertEquals(false, ConfigResolutionService.getByDottedPath(sent, USER_SCHEDULER),
                    "the upgrade must not keep the user scheduler's ClusterRole: " + sent);
            assertEquals(true, ConfigResolutionService.getByDottedPath(sent, "hub.config.JupyterHub.admin_access"),
                    "the release's own values are kept");
        } finally {
            upgrades.shutdown();
        }
    }

    @Test
    void theDefaultIsMergedBesideTheOtherSchedulingValues() {
        StackServiceDef def = new StackServiceDef();
        def.openshiftValueDefaults = new LinkedHashMap<>();
        def.openshiftValueDefaults.put("_comment", "documentation, not a value");
        def.openshiftValueDefaults.put(USER_SCHEDULER, false);
        Map<String, Object> values = new LinkedHashMap<>();
        CommandService.putDotted(values, "scheduling.userPlaceholder.replicas", 0);

        assertEquals(List.of(USER_SCHEDULER), CommandService.applyOpenShiftValueDefaults(def, values));

        assertEquals(false, ConfigResolutionService.getByDottedPath(values, USER_SCHEDULER));
        assertEquals(0, ConfigResolutionService.getByDottedPath(values, "scheduling.userPlaceholder.replicas"));
        assertFalse(values.containsKey("_comment"));
    }

    @Test
    void jupyterHubDeclaresTheDefaultAndNoLongerAsksForAnSccForTheUserScheduler() {
        StackServiceDef def = new StackDefinitionService().getServiceDefinition("JUPYTERHUB");

        assertEquals(false, def.openshiftValueDefaults.get(USER_SCHEDULER));
        List<String> accounts = KubernetesService.sccNeeds("jupyterhub", def.openshiftScc).stream()
                .flatMap(need -> need.serviceAccounts().stream()).toList();
        assertFalse(accounts.contains("user-scheduler"), accounts::toString);
        assertTrue(accounts.contains("hub"), accounts::toString);
    }

    @Test
    void aServiceWithoutOpenShiftDefaultsNeverProbesThePlatformForThem() throws Exception {
        HelmDeployRequest trino = new HelmDeployRequest();
        trino.setServiceKey("TRINO");
        trino.setValues(new LinkedHashMap<>());

        service().applyOpenShiftValueDefaults(trino);

        verify(k8s, org.mockito.Mockito.never()).isOpenShiftCluster();
        assertTrue(trino.getValues().isEmpty());
    }
}
