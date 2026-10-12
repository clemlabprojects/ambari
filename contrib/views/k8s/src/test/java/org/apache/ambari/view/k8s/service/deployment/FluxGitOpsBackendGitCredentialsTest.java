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

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.GitCredentialService;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.security.FileBasedCredentialStore;
import org.apache.ambari.view.k8s.security.MasterKeyService;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.utils.AmbariAliasResolver;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The Git token of a GitOps release in a credential store like Ambari's credentials.jceks: one entry per release,
 * replaced in place by each deploy that gives a new token, and deleted once the release is removed from Git.
 */
class FluxGitOpsBackendGitCredentialsTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "superset";

    @TempDir
    Path tmp;

    private ViewContext ctx;
    private InMemoryDataStore dataStore;
    private String repoUrl;
    private AmbariAliasResolver store;
    private GitCredentialService credentials;
    private final Map<String, String> secretPayloads = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        Path bareRepo = tmp.resolve("remote.git");
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
            g.commit().setMessage("init").setAuthor("seed", "seed@example.com").call();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
        }

        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", Files.createDirectories(tmp.resolve("work")).toString()));
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getInstanceName()).thenReturn("kdps");
        when(ctx.getInstanceData(anyString())).thenReturn(null);
        when(ctx.getInstanceData()).thenReturn(Map.of());
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
        dataStore = new InMemoryDataStore();
        when(ctx.getDataStore()).thenReturn(dataStore);
        HelmRepoEntity charts = new HelmRepoEntity();
        charts.setId("charts");
        charts.setName("charts");
        charts.setType("HTTP");
        charts.setUrl("https://charts.example.com");
        charts.setAuthMode("anonymous");
        dataStore.store(charts);

        FileBasedCredentialStore jceks = new FileBasedCredentialStore(tmp.resolve("credentials.jceks").toFile());
        jceks.setMasterKeyService(new MasterKeyService() {
            @Override
            public char[] getMasterSecret() {
                return "test-master-key".toCharArray();
            }

            @Override
            public boolean isMasterKeyInitialized() {
                return true;
            }
        });
        store = new AmbariAliasResolver(ctx, jceks);
        credentials = new GitCredentialService(ctx, store);
    }

    private FluxGitOpsBackend backend() throws Exception {
        KubernetesService k8s = mock(KubernetesService.class);
        when(k8s.readOpaqueSecretKeyAsString(anyString(), anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(secretPayloads.get(inv.getArgument(0) + "/" + inv.getArgument(1))));
        doAnswer(inv -> {
            secretPayloads.put(inv.getArgument(0) + "/" + inv.getArgument(1),
                    new String((byte[]) inv.getArgument(3), StandardCharsets.UTF_8));
            return null;
        }).when(k8s).createOrUpdateOpaqueSecret(anyString(), anyString(), anyString(), any(byte[].class), anyMap(), any(), any());
        FluxGitOpsBackend backend = new FluxGitOpsBackend(tmp.resolve("flux"), k8s, ctx);
        Field field = FluxGitOpsBackend.class.getDeclaredField("gitCredentialService");
        field.setAccessible(true);
        field.set(backend, credentials);
        return backend;
    }

    /** A Superset deploy that gives its Git token, or none ({@code null}). */
    private HelmDeployRequest deploy(int replicas, String token) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace(NS);
        request.setReleaseName(RELEASE);
        request.setChart("superset");
        request.setVersion("0.12.43");
        request.setRepoId("charts");
        request.setDeploymentMode("FLUX_GITOPS");
        request.setValues(new LinkedHashMap<>(Map.of("replicaCount", replicas)));
        HelmDeployRequest.GitOptions git = new HelmDeployRequest.GitOptions();
        git.setRepoUrl(repoUrl);
        git.setBaseBranch("main");
        git.setBranch("main");
        git.setCommitMode("DIRECT_COMMIT");
        git.setAuthToken(token);
        request.setGit(git);
        return request;
    }

    private K8sReleaseEntity recorded() {
        return dataStore.find(K8sReleaseEntity.class, K8sReleaseEntity.idOf(NS, RELEASE));
    }

    @Test
    void deploysOfAReleaseKeepOneCopyOfItsTokenAndTheUninstallDeletesIt() throws Exception {
        String alias = GitCredentialService.releaseAlias("kdps", NS, RELEASE);

        backend().apply(deploy(1, "glpat-First"), null);
        backend().apply(deploy(2, "glpat-Rotated"), null);

        assertEquals(Set.of(alias), store.listAliases(), "one entry for the release, whatever the number of deploys");
        assertEquals("glpat-Rotated", credentials.resolveSecret(alias));
        assertEquals(alias, recorded().getGitCredentialAlias());

        // A deploy without a token pushes with the stored one and stores nothing.
        backend().apply(deploy(3, null), null);
        assertEquals(Set.of(alias), store.listAliases());

        backend().removeFromGit(recorded());
        assertEquals(Set.of(), store.listAliases(), "nothing is left once the release is removed from Git");
        assertNull(credentials.boundHost(alias));
    }

    private String remoteHead() throws Exception {
        try (Git bare = Git.open(tmp.resolve("remote.git").toFile())) {
            return bare.getRepository().resolve("refs/heads/main").getName();
        }
    }

    @Test
    void aReleaseWhoseRecordedDirectoryIsNotItsOwnIsNotRemovedAndKeepsItsCredential() throws Exception {
        String alias = GitCredentialService.releaseAlias("kdps", NS, RELEASE);
        backend().apply(deploy(1, "glpat-First"), null);
        String ownDir = recorded().getGitPath();

        // A record naming another release's directory: refused before the credential is read or Git is changed.
        String head = remoteHead();
        K8sReleaseEntity forged = recorded();
        forged.setGitPath("clusters/default/" + NS + "/trino");
        assertThrows(FluxGitOpsBackend.NotOwnDirectoryException.class, () -> backend().removeFromGit(forged));
        assertEquals(head, remoteHead());
        assertEquals(Set.of(alias), store.listAliases());

        // The release's own directory, found to hold another release's HelmRelease once the branch is read.
        Path byHand = tmp.resolve("by-hand");
        try (Git g = Git.cloneRepository().setURI(repoUrl).setBranch("main").setDirectory(byHand.toFile()).call()) {
            Files.writeString(byHand.resolve(ownDir).resolve(FluxGitOpsBackend.HELM_RELEASE_FILE),
                    FluxManifests.helmRelease("trino", NS, null, FluxManifests.helmReleaseSpec("trino", "1.43.14",
                            "charts", List.of(), GitOpsSecretValues.secretName("trino"), Map.of())));
            g.add().addFilepattern(".").call();
            g.commit().setMessage("by hand").setAuthor("ops", "ops@example.com").call();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
        }
        head = remoteHead();
        FluxGitOpsBackend.NotOwnDirectoryException refused = assertThrows(FluxGitOpsBackend.NotOwnDirectoryException.class,
                () -> backend().removeFromGit(recorded()));
        assertTrue(refused.getMessage().contains("holds the HelmRelease " + NS + "/trino"), refused.getMessage());
        assertEquals(head, remoteHead(), "nothing removed from Git");
        assertEquals(Set.of(alias), store.listAliases(), "kept, so the uninstall can be retried");
        assertEquals("glpat-First", credentials.resolveSecret(alias));
    }

    @Test
    void aRemovalGitRefusesKeepsTheReleasesCredentialUntilARetryIsPushed() throws Exception {
        String alias = GitCredentialService.releaseAlias("kdps", NS, RELEASE);
        try (GitTestServers.GitDaemon server = new GitTestServers.GitDaemon(tmp.resolve("remote.git"))) {
            repoUrl = server.url();
            backend().apply(deploy(1, "glpat-First"), null);
            String head = remoteHead();
            server.refusePushes("pre-receive hook declined", "Not allowed to push to protected branch main");

            GitPushRejectedException refused = assertThrows(GitPushRejectedException.class,
                    () -> backend().removeFromGit(recorded()));
            assertTrue(refused.getMessage().contains("Not allowed to push to protected branch main"),
                    refused.getMessage());
            assertEquals(head, remoteHead(), "Git still holds the release");
            assertEquals(Set.of(alias), store.listAliases(), "kept, so the uninstall can be retried");
            assertEquals("glpat-First", credentials.resolveSecret(alias));
            assertEquals("127.0.0.1", credentials.boundHost(alias));

            server.acceptPushes();
            backend().removeFromGit(recorded());
            assertNotEquals(head, remoteHead(), "the retry removed the release from Git");
            assertEquals(Set.of(), store.listAliases(), "deleted once the removal is pushed");
        }
    }

    /** A Git server the deploy cannot reach: the commit fails, as with a mistyped token. */
    private static final String UNREACHABLE = "https://127.0.0.1:9/platform/gitops.git";

    @Test
    void aDeployWhoseCommitFailsLeavesTheReleaseWithTheCredentialItPushedWith() throws Exception {
        String alias = GitCredentialService.releaseAlias("kdps", NS, RELEASE);
        backend().apply(deploy(1, "glpat-First"), null);

        // A new token, for another Git server, that never reaches Git.
        HelmDeployRequest moved = deploy(2, "glpat-Mistyped");
        moved.getGit().setRepoUrl(UNREACHABLE);
        assertThrows(Exception.class, () -> backend().apply(moved, null));

        assertEquals("glpat-First", credentials.resolveSecret(alias));
        assertEquals("", credentials.boundHost(alias), "still bound to the repository the release is deployed from");
        assertEquals(repoUrl, recorded().getGitRepoUrl());
        assertEquals(alias, recorded().getGitCredentialAlias());
        assertEquals(Set.of(alias), store.listAliases());
        // Every later operation still has it: a redeploy without a token, then the uninstall.
        backend().apply(deploy(3, null), null);
        backend().checkRemovable(recorded());
        backend().removeFromGit(recorded());
        assertEquals(Set.of(), store.listAliases());
    }

    @Test
    void aPlannedDeploysCredentialBecomesTheReleasesOnceItReachedGit() throws Exception {
        String alias = GitCredentialService.releaseAlias("kdps", NS, RELEASE);
        String pending = GitCredentialService.pendingAlias("kdps", NS, RELEASE);
        backend().apply(deploy(1, "glpat-First"), null);

        // Kept under the pending alias when the deploy was submitted; its commit fails.
        credentials.storeSecret(pending, "glpat-Mistyped", UNREACHABLE);
        HelmDeployRequest failing = deploy(2, null);
        failing.getGit().setRepoUrl(UNREACHABLE);
        failing.getGit().setCredentialAlias(pending);
        assertThrows(Exception.class, () -> backend().apply(failing, null));
        assertEquals("glpat-First", credentials.resolveSecret(alias));
        assertEquals("", credentials.boundHost(alias));
        assertEquals(Set.of(alias, pending), store.listAliases(), "the pending one stays for a resume of the deploy");

        // Submitted again with the right token, the commit reaches Git: the token is the release's from now on.
        credentials.storeSecret(pending, "glpat-Rotated", repoUrl);
        HelmDeployRequest planned = deploy(2, null);
        planned.getGit().setCredentialAlias(pending);
        backend().apply(planned, null);
        assertEquals("glpat-Rotated", credentials.resolveSecret(alias));
        assertEquals(Set.of(alias), store.listAliases());
        assertEquals(alias, recorded().getGitCredentialAlias());
    }

    @Test
    void aReleaseWhoseRecordedCredentialCannotBeUsedIsNotRemoved() throws Exception {
        backend().apply(deploy(1, "glpat-First"), null);
        K8sReleaseEntity meta = recorded();

        // An alias KDPS did not create, as an administrator could name in earlier versions.
        store.addAliasToCredentialStore("corp-git-token", "glpat-Corp");
        meta.setGitCredentialAlias("corp-git-token");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> backend().checkRemovable(meta));
        assertTrue(refused.getMessage().startsWith("KDPS cannot remove analytics/superset from Git with the Git "
                + "credential recorded for it, so nothing was removed. The credential alias 'corp-git-token' does not "
                + "hold"), refused.getMessage());
        assertTrue(refused.getMessage().endsWith("Deploy the release again once, giving its Git access token or SSH "
                + "key, then uninstall it."), refused.getMessage());

        // The release's alias, bound to another host than the repository recorded.
        String alias = GitCredentialService.releaseAlias("kdps", NS, RELEASE);
        meta.setGitCredentialAlias(alias);
        meta.setGitRepoUrl("https://git.example.org/platform/gitops.git");
        refused = assertThrows(IllegalStateException.class, () -> backend().checkRemovable(meta));
        assertTrue(refused.getMessage().contains("was entered for another repository, not for git.example.org"),
                refused.getMessage());

        // The release's alias, gone from the credential store.
        meta.setGitRepoUrl(repoUrl);
        backend().checkRemovable(meta);
        credentials.removeSecret(alias);
        refused = assertThrows(IllegalStateException.class, () -> backend().checkRemovable(meta));
        assertTrue(refused.getMessage().contains("no longer in Ambari's credential store"), refused.getMessage());

        // A release without a credential sends none: nothing to check.
        meta.setGitCredentialAlias(null);
        backend().checkRemovable(meta);
    }

    @Test
    void aNewTokenReplacesTheCopyAnEarlierVersionStored() throws Exception {
        backend().apply(deploy(1, null), null);
        // As an earlier version recorded it: a copy under an alias of its own.
        store.addAliasToCredentialStore("gitops-1b2c", "glpat-Old");
        K8sReleaseEntity meta = recorded();
        meta.setGitCredentialAlias("gitops-1b2c");
        dataStore.store(meta);

        backend().apply(deploy(1, "glpat-New"), null);

        String alias = GitCredentialService.releaseAlias("kdps", NS, RELEASE);
        assertEquals(alias, recorded().getGitCredentialAlias(), "recorded although Git did not change");
        assertEquals(Set.of(alias), store.listAliases(), "the old copy is deleted once replaced");
    }

    @Test
    void aCopyAnotherReleaseStillUsesIsKept() throws Exception {
        backend().apply(deploy(1, null), null);
        store.addAliasToCredentialStore("gitops-1b2c", "glpat-Shared");
        K8sReleaseEntity meta = recorded();
        meta.setGitCredentialAlias("gitops-1b2c");
        dataStore.store(meta);
        K8sReleaseEntity trino = new K8sReleaseEntity();
        trino.setId(K8sReleaseEntity.idOf(NS, "trino"));
        trino.setNamespace(NS);
        trino.setReleaseName("trino");
        trino.setGitCredentialAlias("gitops-1b2c");
        dataStore.store(trino);

        backend().removeFromGit(recorded());

        assertEquals(Set.of("gitops-1b2c"), store.listAliases());
    }
}
