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

package org.apache.ambari.view.k8s.service.deployment;

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A GitOps deploy on OpenShift checks the SCCs the service's pods need as a direct deploy does, never grants them,
 * and stops before anything is committed when one is missing.
 */
class FluxGitOpsBackendSccTest {

    @TempDir
    Path tmp;

    private ViewContext ctx;
    private KubernetesService k8s;
    private Path bareRepo;
    private String repoUrl;
    private String seedCommit;
    private final Map<String, K8sReleaseEntity> releases = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        bareRepo = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bareRepo.toFile()).call().close();
        repoUrl = bareRepo.toUri().toString();
        Path seed = Files.createDirectories(tmp.resolve("seed"));
        try (Git g = Git.cloneRepository().setURI(repoUrl).setDirectory(seed.toFile()).call()) {
            Files.writeString(seed.resolve("README.md"), "seed");
            g.add().addFilepattern(".").call();
            seedCommit = g.commit().setMessage("init").setAuthor("seed", "seed@example.com").call().getName();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
        }

        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", Files.createDirectories(tmp.resolve("work")).toString()));
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getInstanceName()).thenReturn("kdps");
        when(ctx.getInstanceData(anyString())).thenReturn(null);
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
        DataStore ds = mock(DataStore.class);
        when(ctx.getDataStore()).thenReturn(ds);
        HelmRepoEntity charts = new HelmRepoEntity();
        charts.setId("charts");
        charts.setType("HTTP");
        charts.setUrl("https://charts.example.com");
        charts.setAuthMode("anonymous");
        when(ds.find(eq(HelmRepoEntity.class), anyString())).thenAnswer(inv -> "charts".equals(inv.getArgument(1)) ? charts : null);
        doAnswer(inv -> {
            K8sReleaseEntity entity = inv.getArgument(0);
            releases.put(entity.getId(), entity);
            return null;
        }).when(ds).store(any(K8sReleaseEntity.class));
        when(ds.find(eq(K8sReleaseEntity.class), anyString())).thenAnswer(inv -> releases.get((String) inv.getArgument(1)));

        k8s = mock(KubernetesService.class);
        when(k8s.isOpenShiftCluster()).thenReturn(true);
    }

    private HelmDeployRequest openMetadataRequest() {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace("data");
        request.setReleaseName("om");
        request.setChart("openmetadata/openmetadata");
        request.setServiceKey("OPENMETADATA");
        request.setDeploymentMode("FLUX_GITOPS");
        request.setValues(new LinkedHashMap<>());
        HelmDeployRequest.GitOptions git = new HelmDeployRequest.GitOptions();
        git.setRepoUrl(repoUrl);
        git.setBaseBranch("main");
        git.setBranch("main");
        git.setCommitMode("DIRECT_COMMIT");
        request.setGit(git);
        return request;
    }

    private String remoteHead() throws Exception {
        try (Git g = Git.open(bareRepo.toFile())) {
            return g.getRepository().resolve("refs/heads/main").getName();
        }
    }

    private FluxGitOpsBackend backend() {
        return new FluxGitOpsBackend(tmp.resolve("flux"), k8s, ctx);
    }

    @Test
    void aMissingGrantStopsTheDeployBeforeAnythingIsCommitted() throws Exception {
        when(k8s.checkSccGrants(eq("data"), eq("om"), any(), any()))
                .thenReturn("Ask the platform team to grant them:\n  oc adm policy add-scc-to-user anyuid -z default -n data");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> backend().apply(openMetadataRequest(), new DeploymentContext("charts", "1.13.34", null, null, null, null, null)));

        assertTrue(refused.getMessage().contains("oc adm policy add-scc-to-user anyuid -z default -n data"), refused.getMessage());
        assertEquals(seedCommit, remoteHead(), "nothing was committed");
        verify(k8s, never()).ensureSccGrants(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aGrantedReleaseIsCommittedWithoutAnyGrantFromKdps() throws Exception {
        when(k8s.checkSccGrants(any(), any(), any(), any())).thenReturn(null);

        backend().apply(openMetadataRequest(), new DeploymentContext("charts", "1.13.34", null, null, null, null, null));

        assertNotEquals(seedCommit, remoteHead());
        var declared = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(k8s).checkSccGrants(eq("data"), eq("om"), declared.capture(), any());
        assertEquals("anyuid", ((Map<String, Object>) declared.getValue().get(0)).get("scc"),
                "the service definition's openshiftScc is what is checked");
        verify(k8s, never()).ensureSccGrants(any(), any(), any(), any());
    }

    @Test
    void plainKubernetesIsNotChecked() throws Exception {
        when(k8s.isOpenShiftCluster()).thenReturn(false);

        backend().apply(openMetadataRequest(), new DeploymentContext("charts", "1.13.34", null, null, null, null, null));

        verify(k8s, never()).checkSccGrants(any(), any(), any(), any());
    }
}
