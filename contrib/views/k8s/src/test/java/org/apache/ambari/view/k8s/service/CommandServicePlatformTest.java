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

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.deployment.DeploymentBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A service whose definition lists the platforms it supports is refused on any other platform, whatever the deploy
 * mode, before anything is prepared for it: GitLab on OpenShift, whose pods the platform's default security policy
 * would never admit.
 */
class CommandServicePlatformTest {

    private static final String GITLAB_REFUSED = "GitLab is not supported on OpenShift in this release; register your"
            + " existing Git server under Git Repositories.";

    @TempDir
    Path tmp;

    private ViewContext ctx;
    private KubernetesService k8s;
    private DeploymentBackend direct;
    private DeploymentBackend gitOps;

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

    private static HelmDeployRequest gitLab(String deploymentMode) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setChart("gitlab");
        request.setReleaseName("gitlab");
        request.setNamespace("gitlab");
        request.setServiceKey("GITLAB");
        request.setRepoId("charts");
        request.setValues(new LinkedHashMap<>());
        request.setDeploymentMode(deploymentMode);
        return request;
    }

    private String submit(HelmDeployRequest request) throws Exception {
        return service().submitDeploy(request, null, null, null, null, null, null, null);
    }

    @Test
    void gitLabIsRefusedOnOpenShift() throws Exception {
        when(k8s.isOpenShiftCluster()).thenReturn(true);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> submit(gitLab("DIRECT_HELM")));

        assertEquals(GITLAB_REFUSED, refused.getMessage());
        // Nothing was prepared for it: no namespace, no deploy plan.
        verify(k8s, never()).createNamespace(anyString());
        verify(direct, never()).apply(any(), any());
    }

    @Test
    void aGitOpsDeployOfGitLabIsRefusedOnOpenShiftToo() throws Exception {
        when(k8s.isOpenShiftCluster()).thenReturn(true);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> submit(gitLab("FLUX_GITOPS")));

        assertEquals(GITLAB_REFUSED, refused.getMessage());
        verify(gitOps, never()).apply(any(), any());
    }

    @Test
    void gitLabIsDeployedOnPlainKubernetes() throws Exception {
        when(k8s.isOpenShiftCluster()).thenReturn(false);

        assertEquals("command-1", submit(gitLab("DIRECT_HELM")));

        verify(direct).apply(any(), any());
    }

    @Test
    void aServiceThatNamesNoPlatformIsNeverRefusedForIt() {
        StackServiceDef trino = new StackDefinitionService().getServiceDefinition("TRINO");

        assertNull(trino.platforms, "Trino runs on both platforms");
        assertNull(CommandService.unsupportedPlatformProblem(trino, "TRINO", true));
        assertNull(CommandService.unsupportedPlatformProblem(trino, "TRINO", false));
        assertNull(CommandService.unsupportedPlatformProblem(null, "CUSTOM", true));
    }

    @Test
    void withoutItsOwnMessageTheRefusalNamesTheServiceAndThePlatform() {
        StackServiceDef openShiftOnly = new StackServiceDef();
        openShiftOnly.label = "Example Service";
        openShiftOnly.platforms = List.of("openshift");

        assertEquals("Example Service is not supported on plain Kubernetes in this release.",
                CommandService.unsupportedPlatformProblem(openShiftOnly, "EXAMPLE", false));
        assertNull(CommandService.unsupportedPlatformProblem(openShiftOnly, "EXAMPLE", true));

        StackServiceDef unlabelled = new StackServiceDef();
        unlabelled.platforms = List.of(" Kubernetes ");
        assertEquals("EXAMPLE is not supported on OpenShift in this release.",
                CommandService.unsupportedPlatformProblem(unlabelled, "EXAMPLE", true));
        assertNull(CommandService.unsupportedPlatformProblem(unlabelled, "EXAMPLE", false));
    }

    @Test
    void aServiceTheReleaseNoLongerShipsIsRefused() throws Exception {
        HelmDeployRequest request = gitLab("DIRECT_HELM");
        request.setServiceKey("JUPYTERHUB");
        request.setReleaseName("jupyterhub");

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> submit(request));

        assertEquals("JUPYTERHUB is not part of this release; a release of it deployed by an earlier build can only"
                + " be uninstalled.", refused.getMessage());
        verify(k8s, never()).createNamespace(anyString());
        verify(direct, never()).apply(any(), any());
    }
}
