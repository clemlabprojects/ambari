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
import org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.SecurityProfileService;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
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
 * A GitOps deploy without a command plan prepares Kerberos, the truststore and the dependencies itself, as a direct
 * deploy does. When one of them cannot be prepared, the deploy stops with the reason and nothing is committed: no
 * HelmRelease pointing at a krb5.conf ConfigMap, a truststore Secret or a dependency that does not exist.
 */
class FluxGitOpsBackendPreparationTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "superset";
    private static final String RELEASE_DIR = "clusters/default/analytics/superset";

    @TempDir
    Path tmp;

    private ViewContext ctx;
    private KubernetesService k8s;
    private String repoUrl;
    private Path bareRepo;
    private String seedCommit;
    private final Map<String, K8sReleaseEntity> releases = new ConcurrentHashMap<>();
    private final Map<String, HelmRepoEntity> chartRepos = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        bareRepo = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bareRepo.toFile()).call().close();
        // No background gc after a push: it would still hold gc.log.lock when the temp directory is removed.
        try (Git bare = Git.open(bareRepo.toFile())) {
            org.eclipse.jgit.lib.StoredConfig bareConfig = bare.getRepository().getConfig();
            bareConfig.setInt("gc", null, "auto", 0);
            bareConfig.setBoolean("receive", null, "autogc", false);
            bareConfig.save();
        }
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
        chartRepos.put("charts", repo("charts"));
        when(ds.find(eq(HelmRepoEntity.class), anyString())).thenAnswer(inv -> chartRepos.get((String) inv.getArgument(1)));
        doAnswer(inv -> {
            K8sReleaseEntity entity = inv.getArgument(0);
            releases.put(entity.getId(), entity);
            return null;
        }).when(ds).store(any(K8sReleaseEntity.class));
        when(ds.find(eq(K8sReleaseEntity.class), anyString())).thenAnswer(inv -> releases.get((String) inv.getArgument(1)));

        k8s = mock(KubernetesService.class);
    }

    private static HelmRepoEntity repo(String id) {
        HelmRepoEntity repo = new HelmRepoEntity();
        repo.setId(id);
        repo.setType("HTTP");
        repo.setUrl("https://" + id + ".example.com");
        repo.setAuthMode("anonymous");
        return repo;
    }

    private HelmDeployRequest request() {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace(NS);
        request.setReleaseName(RELEASE);
        request.setChart("superset");
        request.setVersion("0.12.43");
        request.setRepoId("charts");
        request.setDeploymentMode("FLUX_GITOPS");
        request.setValues(new LinkedHashMap<>(Map.of("replicaCount", 2)));
        HelmDeployRequest.GitOptions git = new HelmDeployRequest.GitOptions();
        git.setRepoUrl(repoUrl);
        git.setBaseBranch("main");
        git.setBranch("main");
        git.setCommitMode("DIRECT_COMMIT");
        request.setGit(git);
        return request;
    }

    private static void inject(FluxGitOpsBackend backend, String field, Object value) throws Exception {
        Field f = FluxGitOpsBackend.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(backend, value);
    }

    /** A backend whose security profile 'corp-krb' asks for Kerberos, and whose krb5.conf source is {@code found}. */
    private FluxGitOpsBackend kerberosBackend(boolean found) throws Exception {
        FluxGitOpsBackend backend = new FluxGitOpsBackend(tmp.resolve("flux"), k8s, ctx);
        SecurityProfileService profiles = mock(SecurityProfileService.class);
        SecurityConfigDTO kerberos = new SecurityConfigDTO();
        kerberos.mode = "kerberos";
        when(profiles.requireProfile("corp-krb")).thenReturn(kerberos);
        inject(backend, "securityProfileService", profiles);
        CommandUtils commandUtils = mock(CommandUtils.class);
        when(commandUtils.prepareKrb5ConfConfigMap(anyString(), anyString(), any())).thenCallRealMethod();
        when(commandUtils.ensureKrb5ConfConfigMap(anyString(), anyString(), any())).thenReturn(found);
        inject(backend, "commandUtils", commandUtils);
        return backend;
    }

    private String remoteHead() throws Exception {
        try (Git bare = Git.open(bareRepo.toFile())) {
            Ref main = bare.getRepository().exactRef("refs/heads/main");
            return main.getObjectId().getName();
        }
    }

    private String published(String path) throws Exception {
        Path clone = tmp.resolve("verify-" + System.nanoTime());
        try (Git ignored = org.apache.ambari.view.k8s.utils.TestGitClones.clone(repoUrl, "main", clone)) {
            Path file = clone.resolve(path);
            return Files.exists(file) ? Files.readString(file) : null;
        }
    }

    @Test
    void aKerberosDeployWithoutAKrb5ConfIsNotCommitted() throws Exception {
        HelmDeployRequest request = request();
        request.setSecurityProfile("corp-krb");

        IllegalStateException stopped = assertThrows(IllegalStateException.class,
                () -> kerberosBackend(false).apply(request, null));

        assertTrue(stopped.getMessage().startsWith("Kerberos is enabled for this deploy but no krb5.conf could be found"),
                stopped.getMessage());
        assertTrue(stopped.getMessage().contains("superset-krb5-conf in namespace analytics"), stopped.getMessage());
        assertEquals(seedCommit, remoteHead(), "nothing was committed");
    }

    @Test
    void aKerberosDeployPointsTheChartAtTheConfigMapItWrote() throws Exception {
        HelmDeployRequest request = request();
        request.setSecurityProfile("corp-krb");
        FluxGitOpsBackend backend = kerberosBackend(true);

        backend.apply(request, null);

        String helmRelease = published(RELEASE_DIR + "/helmrelease.yaml");
        assertTrue(helmRelease.contains("configMapName: \"superset-krb5-conf\""), helmRelease);
        assertTrue(helmRelease.contains("enabled: \"true\"") || helmRelease.contains("enabled: true"), helmRelease);
    }

    @Test
    void aSelectedTruststoreThatCannotBeReadIsNotCommitted() throws Exception {
        HelmDeployRequest request = request();
        request.setTruststoreRefs(List.of("ambari-pki/gone-ca-truststore"));

        IllegalStateException stopped = assertThrows(IllegalStateException.class,
                () -> new FluxGitOpsBackend(tmp.resolve("flux"), k8s, ctx).apply(request, null));

        assertTrue(stopped.getMessage().startsWith("Could not write truststore Secret analytics/superset-truststore: "),
                stopped.getMessage());
        assertTrue(stopped.getMessage().contains("'ambari-pki/gone-ca-truststore'"), stopped.getMessage());
        verify(k8s, never()).createOrUpdateOpaqueSecret(eq(NS), eq("superset-truststore"), anyMap());
        assertEquals(seedCommit, remoteHead(), "nothing was committed");
    }

    @Test
    void aDependencyThatCannotBePreparedStopsTheDeployBeforeTheCommit() throws Exception {
        HelmDeployRequest request = request();
        request.setDependencies(new LinkedHashMap<>(Map.of("keda",
                new LinkedHashMap<>(Map.of("chart", "kube-prometheus-stack", "namespace", "keda",
                        "skipIfPrometheusPresent", true)))));
        when(k8s.listPrometheusInstances()).thenThrow(new IllegalStateException("connection reset"));

        IllegalStateException stopped = assertThrows(IllegalStateException.class,
                () -> new FluxGitOpsBackend(tmp.resolve("flux"), k8s, ctx).apply(request, null));

        assertEquals("GitOps deploy of analytics/superset stopped: dependency keda could not be prepared in namespace "
                + "keda, so nothing was committed to Git: connection reset", stopped.getMessage());
        assertEquals(seedCommit, remoteHead(), "no HelmRelease waiting on a dependency Git does not hold");
    }

    @Test
    void aDependencyWhoseFilesCannotBeWrittenStopsTheDeployBeforeTheCommit() throws Exception {
        HelmRepoEntity privateRepo = repo("private");
        privateRepo.setCaTruststores("gone-ca");
        chartRepos.put("private", privateRepo);
        HelmDeployRequest request = request();
        request.setDependencies(new LinkedHashMap<>(Map.of("keda",
                new LinkedHashMap<>(Map.of("chart", "keda", "namespace", "keda", "repoId", "private")))));

        assertThrows(IllegalStateException.class,
                () -> new FluxGitOpsBackend(tmp.resolve("flux"), k8s, ctx).apply(request, null));

        assertEquals(seedCommit, remoteHead(), "neither the release nor its dependency reached Git");
    }
}
