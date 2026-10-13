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

package org.apache.ambari.view.k8s.service.deployment;

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.StackDefinitionService;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Which chart a GitOps deploy writes into the HelmRelease: the chart repository and version the deploy names, else
 * the release's, never a guessed repository or "latest", and the chart name as the Flux source indexes it.
 */
public class FluxGitOpsBackendChartSourceTest {

    private static final String RELEASE_DIR = "clusters/default/analytics/trino";
    private static final String SHARED_DIR = "clusters/default/_shared";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ViewContext ctx;
    private DataStore ds;
    private KubernetesService k8s;
    private String repoUrl;
    private Path bareRepo;
    private Path fluxRoot;
    private String seedCommit;
    private final Map<String, HelmRepoEntity> chartRepos = new ConcurrentHashMap<>();
    private final Map<String, K8sReleaseEntity> releases = new ConcurrentHashMap<>();

    @Before
    public void setUp() throws Exception {
        bareRepo = tmp.getRoot().toPath().resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bareRepo.toFile()).call().close();
        // No background gc after a push: it would still hold gc.log.lock when the temp directory is removed.
        try (Git bare = Git.open(bareRepo.toFile())) {
            org.eclipse.jgit.lib.StoredConfig bareConfig = bare.getRepository().getConfig();
            bareConfig.setInt("gc", null, "auto", 0);
            bareConfig.setBoolean("receive", null, "autogc", false);
            bareConfig.save();
        }
        repoUrl = bareRepo.toUri().toString();
        Path seed = tmp.newFolder("seed").toPath();
        try (Git g = Git.cloneRepository().setURI(repoUrl).setDirectory(seed.toFile()).call()) {
            Files.writeString(seed.resolve("README.md"), "seed");
            g.add().addFilepattern(".").call();
            seedCommit = g.commit().setMessage("init").setAuthor("seed", "seed@example.com").call().getName();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
        }
        fluxRoot = tmp.newFolder("flux").toPath();

        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", tmp.newFolder("work").toString()));
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getInstanceName()).thenReturn("kdps");
        when(ctx.getInstanceData(anyString())).thenReturn(null);
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
        ds = mock(DataStore.class);
        when(ctx.getDataStore()).thenReturn(ds);

        chartRepos.put("charts", chartRepo("charts", "HTTP", "https://charts.example.com"));
        chartRepos.put("registry", chartRepo("registry", "OCI", "registry.example.com/kdps"));
        when(ds.find(eq(HelmRepoEntity.class), anyString())).thenAnswer(inv -> chartRepos.get((String) inv.getArgument(1)));
        doAnswer(inv -> {
            K8sReleaseEntity entity = inv.getArgument(0);
            releases.put(entity.getId(), entity);
            return null;
        }).when(ds).store(any(K8sReleaseEntity.class));
        when(ds.find(eq(K8sReleaseEntity.class), anyString())).thenAnswer(inv -> releases.get((String) inv.getArgument(1)));

        k8s = mock(KubernetesService.class);
    }

    private static HelmRepoEntity chartRepo(String id, String type, String url) {
        HelmRepoEntity repo = new HelmRepoEntity();
        repo.setId(id);
        repo.setType(type);
        repo.setUrl(url);
        repo.setAuthMode("anonymous");
        return repo;
    }

    /** A Trino deploy as the wizard builds it: the chart reference of the service definition, no repository, no version. */
    private HelmDeployRequest wizardRequest() {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace("analytics");
        request.setReleaseName("trino");
        request.setChart("trinodb/trino");
        request.setServiceKey("TRINO");
        request.setDeploymentMode("FLUX_GITOPS");
        request.setValues(new LinkedHashMap<>(Map.of("server", new LinkedHashMap<>(Map.of("workers", 2)))));
        HelmDeployRequest.GitOptions git = new HelmDeployRequest.GitOptions();
        git.setRepoUrl(repoUrl);
        git.setBaseBranch("main");
        git.setBranch("main");
        git.setCommitMode("DIRECT_COMMIT");
        request.setGit(git);
        return request;
    }

    /** What the Releases page sends to resync a release: its values, chart and git target, no version. */
    private HelmDeployRequest resyncRequest(int workers) {
        HelmDeployRequest request = wizardRequest();
        request.getValues().put("server", new LinkedHashMap<>(Map.of("workers", workers)));
        return request;
    }

    private static DeploymentContext queryParameters(String repoId, String version) {
        return new DeploymentContext(repoId, version, null, null, null, null, null);
    }

    private FluxGitOpsBackend backend() {
        return new FluxGitOpsBackend(fluxRoot, k8s, ctx);
    }

    private String remoteHead() throws Exception {
        try (Git g = Git.open(bareRepo.toFile())) {
            return g.getRepository().resolve("refs/heads/main").getName();
        }
    }

    /** A file as published on the remote main branch, or null. */
    private String published(String path) throws Exception {
        Path clone = tmp.getRoot().toPath().resolve("verify-" + System.nanoTime());
        try (Git ignored = org.apache.ambari.view.k8s.utils.TestGitClones.clone(repoUrl, "main", clone)) {
            Path file = clone.resolve(path);
            return Files.exists(file) ? Files.readString(file) : null;
        }
    }

    @Test
    public void theRepositoryAndVersionTheWizardSendsAsQueryParametersAreWrittenToTheHelmRelease() throws Exception {
        backend().apply(wizardRequest(), queryParameters("charts", "1.43.14"));

        String helmRelease = published(RELEASE_DIR + "/helmrelease.yaml");
        assertTrue(helmRelease, helmRelease.contains("      chart: trino\n      version: 1.43.14\n"));
        assertTrue(helmRelease, helmRelease.contains("        kind: HelmRepository\n        name: trino-charts\n"));
        assertTrue(published(RELEASE_DIR + "/helmrepository.yaml").contains("url: https://charts.example.com"));
        K8sReleaseEntity recorded = releases.get(K8sReleaseEntity.idOf("analytics", "trino"));
        assertEquals("charts", recorded.getRepoId());
        assertEquals("1.43.14", recorded.getVersion());
        assertEquals("the release keeps the chart reference it was deployed with", "trinodb/trino", recorded.getChartRef());
    }

    @Test
    public void aResyncWithoutAVersionKeepsTheRecordedRepositoryAndVersion() throws Exception {
        backend().apply(wizardRequest(), queryParameters("charts", "1.43.14"));

        backend().apply(resyncRequest(3), null);

        String helmRelease = published(RELEASE_DIR + "/helmrelease.yaml");
        assertTrue("the resync is committed: " + helmRelease, helmRelease.contains("workers: 3"));
        assertTrue(helmRelease, helmRelease.contains("version: 1.43.14\n"));
        assertTrue(helmRelease, helmRelease.contains("name: trino-charts\n"));
        assertFalse(helmRelease, helmRelease.contains("latest"));
        assertEquals("1.43.14", releases.get(K8sReleaseEntity.idOf("analytics", "trino")).getVersion());
    }

    @Test
    public void aVersionTheDeployNamesWinsOverTheRecordedOne() throws Exception {
        backend().apply(wizardRequest(), queryParameters("charts", "1.43.13"));

        backend().apply(wizardRequest(), queryParameters(null, "1.43.14"));

        assertTrue(published(RELEASE_DIR + "/helmrelease.yaml").contains("version: 1.43.14\n"));
    }

    @Test
    public void aRecordedLatestIsNeverWrittenToGit() throws Exception {
        K8sReleaseEntity old = new K8sReleaseEntity();
        old.setId(K8sReleaseEntity.idOf("analytics", "trino"));
        old.setNamespace("analytics");
        old.setReleaseName("trino");
        old.setRepoId("charts");
        old.setVersion("latest");
        releases.put(old.getId(), old);

        try {
            backend().apply(resyncRequest(3), queryParameters(null, "latest"));
            fail("a release without a real version must not be committed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith("Select a chart version for trino"));
        }
        assertEquals("nothing was committed", seedCommit, remoteHead());
    }

    @Test
    public void aFirstInstallWithoutAVersionUsesTheServiceDefinitionVersion() throws Exception {
        String catalogVersion = new StackDefinitionService().getServiceDefinition("TRINO").version;

        backend().apply(wizardRequest(), queryParameters("charts", null));

        assertTrue(published(RELEASE_DIR + "/helmrelease.yaml").contains("version: " + catalogVersion + "\n"));
    }

    @Test
    public void aDeployWithoutAChartRepositoryIsRefusedBeforeAnythingIsCommitted() throws Exception {
        try {
            backend().apply(wizardRequest(), queryParameters(null, "1.43.14"));
            fail("there is no repository to write");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith("Select a chart repository"));
            assertFalse(expected.getMessage().contains("default-repo"));
        }
        assertEquals(seedCommit, remoteHead());
        verify(k8s, never()).createNamespace(anyString());
    }

    @Test
    public void anOciRegistryKeepsTheChartPathADirectDeployPulls() throws Exception {
        backend().apply(wizardRequest(), queryParameters("registry", "1.43.14"));

        String helmRelease = published(RELEASE_DIR + "/helmrelease.yaml");
        assertTrue(helmRelease, helmRelease.contains("      chart: trinodb/trino\n"));
        assertTrue(helmRelease, helmRelease.contains("name: trino-registry\n"));
    }

    @Test
    public void aDependencyNeverInheritsTheMainChartVersion() throws Exception {
        HelmDeployRequest request = wizardRequest();
        LinkedHashMap<String, Object> dependencies = new LinkedHashMap<>();
        dependencies.put("ldap-proxy", new LinkedHashMap<>(Map.of("chart", "kdps/ldap-proxy", "releaseName", "ldap-proxy")));
        dependencies.put("pinned", new LinkedHashMap<>(Map.of("chart", "pinned", "releaseName", "pinned",
                "chartVersion", "0.3.1")));
        dependencies.put("legacy", new LinkedHashMap<>(Map.of("chart", "legacy", "releaseName", "legacy",
                "version", "latest")));
        request.setDependencies(dependencies);

        backend().apply(request, queryParameters("charts", "1.43.14"));

        String unpinned = published(SHARED_DIR + "/analytics/ldap-proxy-helmrelease.yaml");
        assertTrue(unpinned, unpinned.contains("      chart: ldap-proxy\n      sourceRef:\n"));
        assertFalse(unpinned, unpinned.contains("version:"));
        String pinned = published(SHARED_DIR + "/analytics/pinned-helmrelease.yaml");
        assertTrue(pinned, pinned.contains("      chart: pinned\n      version: 0.3.1\n      sourceRef:\n"));
        String legacy = published(SHARED_DIR + "/analytics/legacy-helmrelease.yaml");
        assertFalse(legacy, legacy.contains("version:"));
    }

    @Test
    public void aRedeployWithoutAContextStaysOnTheRecordedOne() throws Exception {
        HelmDeployRequest install = wizardRequest();
        install.setFormValues(new LinkedHashMap<>(Map.of("platformContextId", "cdp-prod")));
        backend().apply(install, queryParameters("charts", "1.43.14"));

        HelmDeployRequest resync = resyncRequest(3);
        backend().apply(resync, null);

        assertEquals("cdp-prod", resync.getPlatformContextId());
        assertFalse("an external context does not inherit the local realm", FluxGitOpsBackend.followsAmbariKerberos(resync));
    }

    @Test
    public void anHttpRepositoryIndexesChartsByBareName() {
        assertEquals("trino", FluxGitOpsBackend.chartNameInSource("trinodb/trino", false));
        assertEquals("superset", FluxGitOpsBackend.chartNameInSource(" apache/superset ", false));
        assertEquals("keda", FluxGitOpsBackend.chartNameInSource("keda", false));
        assertEquals("trinodb/trino", FluxGitOpsBackend.chartNameInSource("trinodb/trino", true));
        assertEquals("the version is pinned on its own", "charts/trino", FluxGitOpsBackend.chartNameInSource("charts/trino:1.43.14", true));
    }
}
