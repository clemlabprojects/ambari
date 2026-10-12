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
import org.apache.ambari.view.k8s.service.GitCredentialService;
import org.apache.ambari.view.k8s.service.HelmSetValues;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GitOps deploys and uninstalls whose push the Git server refuses, drops, races with another one, or never answers.
 * Each ends with the change really on the server, or with an error carrying the server's message: never a commit or
 * "no changes" reported while the server lacks the change, never release metadata recorded for a change that did not
 * land, and never a release locked until Ambari is restarted.
 */
class FluxGitOpsBackendPushFailureTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "superset";
    private static final String RELEASE_DIR = "clusters/default/analytics/superset";
    private static final String BIND_PASSWORD = "Bind-Pass-42";
    private static final String PROTECTED = "Gitea: Not allowed to push to protected branch main";

    @TempDir
    Path tmp;

    private ViewContext ctx;
    private KubernetesService k8s;
    private Path bareRepo;
    private String seed;
    private final Map<String, String> viewProperties = new ConcurrentHashMap<>();
    private final Map<String, String> secretPayloads = new ConcurrentHashMap<>();
    private final Map<String, K8sReleaseEntity> releases = new ConcurrentHashMap<>();
    /** Runs when a values Secret is written: after the deploy read the branch, before it commits and pushes. */
    private volatile Runnable onSecretWrite = () -> { };
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @BeforeEach
    void setUp() throws Exception {
        bareRepo = GitTestServers.seededBareRepo(tmp);
        seed = GitTestServers.head(bareRepo, "main");

        viewProperties.put("k8s.view.working.dir", Files.createDirectories(tmp.resolve("work")).toString());
        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getProperties()).thenReturn(viewProperties);
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
        when(ds.find(eq(HelmRepoEntity.class), eq("charts"))).thenReturn(charts);
        doAnswer(inv -> {
            K8sReleaseEntity entity = inv.getArgument(0);
            releases.put(entity.getId(), entity);
            return null;
        }).when(ds).store(any(K8sReleaseEntity.class));
        when(ds.find(eq(K8sReleaseEntity.class), anyString())).thenAnswer(inv -> releases.get((String) inv.getArgument(1)));
        doAnswer(inv -> releases.remove(((K8sReleaseEntity) inv.getArgument(0)).getId()))
                .when(ds).remove(any(K8sReleaseEntity.class));

        k8s = mock(KubernetesService.class);
        when(k8s.readOpaqueSecretKeyAsString(anyString(), anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(secretPayloads.get(inv.getArgument(0) + "/" + inv.getArgument(1))));
        doAnswer(inv -> {
            secretPayloads.put(inv.getArgument(0) + "/" + inv.getArgument(1),
                    new String((byte[]) inv.getArgument(3), StandardCharsets.UTF_8));
            onSecretWrite.run();
            return null;
        }).when(k8s).createOrUpdateOpaqueSecret(anyString(), anyString(), anyString(), any(byte[].class), anyMap(), any(), any());
        doAnswer(inv -> secretPayloads.remove(inv.getArgument(0) + "/" + inv.getArgument(1)))
                .when(k8s).deleteSecret(anyString(), anyString());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private FluxGitOpsBackend backend() throws Exception {
        FluxGitOpsBackend backend = new FluxGitOpsBackend(tmp.resolve("flux"), k8s, ctx);
        // A token given with a deploy is stored in Ambari's credential store, not reachable from a test.
        GitCredentialService credentials = mock(GitCredentialService.class);
        when(credentials.storeSecret(anyString(), anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        Field field = FluxGitOpsBackend.class.getDeclaredField("gitCredentialService");
        field.setAccessible(true);
        field.set(backend, credentials);
        return backend;
    }

    /** A Superset deploy as its GITOPS_COMMIT step rebuilds it, committed to {@code branch} of {@code repoUrl}. */
    private static HelmDeployRequest request(String repoUrl, String release, String branch) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace(NS);
        request.setReleaseName(release);
        request.setChart("superset");
        request.setVersion("0.12.43");
        request.setRepoId("charts");
        request.setDeploymentMode("FLUX_GITOPS");
        request.setValues(new LinkedHashMap<>(Map.of("replicaCount", 2)));
        HelmDeployRequest.GitOptions git = new HelmDeployRequest.GitOptions();
        git.setRepoUrl(repoUrl);
        git.setBaseBranch("main");
        git.setBranch(branch);
        git.setCommitMode("DIRECT_COMMIT");
        request.setGit(git);
        return request;
    }

    private static HelmDeployRequest request(String repoUrl) {
        return request(repoUrl, RELEASE, "main");
    }

    /** What the command plan prepared: Kerberos, and an LDAP bind password that goes to the values Secret. */
    private static FluxGitOpsBackend.PlannedRelease planned() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("global.security.kerberos.enabled", "true");
        overrides.put("global.security.kerberos.injectionMode", "PRE_PROVISIONED");
        overrides.put("global.security.auth.ldap.bindPassword", HelmSetValues.asText(BIND_PASSWORD));
        return new FluxGitOpsBackend.PlannedRelease(overrides, Boolean.TRUE);
    }

    /**
     * A file as published on a branch of the server, or null. Read in the server's repository itself: a clone made to
     * read it would start JGit's background garbage collection, still running when the test's directory is removed.
     */
    private String published(String branch, String path) throws Exception {
        try (Git g = Git.open(bareRepo.toFile())) {
            Repository repo = g.getRepository();
            ObjectId tree = repo.resolve("refs/heads/" + branch + "^{tree}");
            if (tree == null) {
                return null;
            }
            try (TreeWalk file = TreeWalk.forPath(repo, path, tree)) {
                return file == null ? null : new String(repo.open(file.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private RevCommit commitOnServer(String sha) throws Exception {
        try (Git g = Git.open(bareRepo.toFile()); RevWalk walk = new RevWalk(g.getRepository())) {
            return walk.parseCommit(g.getRepository().resolve(sha));
        }
    }

    // ---------------------------------------------------------------- refused pushes

    @Test
    void aDeployThePushOfWhichGitRefusesFailsWithGitsMessageAndRecordsNothing() throws Exception {
        try (GitTestServers.GitDaemon server = new GitTestServers.GitDaemon(bareRepo)) {
            server.refusePushes("pre-receive hook declined", PROTECTED);

            GitPushRejectedException refused = assertThrows(GitPushRejectedException.class,
                    () -> backend().commit(request(server.url()), null, planned()));

            assertTrue(refused.getMessage().contains(PROTECTED), refused.getMessage());
            assertTrue(refused.getMessage().contains("pre-receive hook declined"), refused.getMessage());
            assertEquals(1, server.pushes(), "a refusal is not pushed again");
            assertEquals(seed, GitTestServers.head(bareRepo, "main"), "nothing landed in Git");
            assertTrue(releases.isEmpty(), "no release metadata for a change Git does not hold");
            assertNull(secretPayloads.get(NS + "/superset-kdps-values"),
                    "the values Secret created for the refused change is deleted again");

            // Retried as it is: the same refusal again, never "no changes".
            GitPushRejectedException again = assertThrows(GitPushRejectedException.class,
                    () -> backend().commit(request(server.url()), null, planned()));
            assertTrue(again.getMessage().contains(PROTECTED));
            assertEquals(2, server.pushes());

            // Once Git accepts it, the same deploy lands and is recorded.
            server.acceptPushes();
            FluxGitOpsBackend.CommitResult landed = backend().commit(request(server.url()), null, planned());
            assertTrue(landed.committed());
            assertEquals(landed.commitSha(), GitTestServers.head(bareRepo, "main"));
            assertEquals(landed.commitSha(), releases.get(K8sReleaseEntity.idOf(NS, RELEASE)).getGitCommitSha());
            assertNotNull(published("main", RELEASE_DIR + "/helmrelease.yaml"));
        }
    }

    @Test
    void aPushThatFailsOnTheWayIsTriedAgainAndTheCommitLandsInGit() throws Exception {
        try (GitTestServers.GitDaemon server = new GitTestServers.GitDaemon(bareRepo)) {
            server.dropNextPushes(1);

            FluxGitOpsBackend.CommitResult result = backend().commit(request(server.url()), null, planned());

            assertTrue(result.committed());
            assertNotEquals("NO_CHANGES", result.id());
            assertEquals(2, server.pushes(), "the dropped push is tried again");
            assertEquals(result.commitSha(), GitTestServers.head(bareRepo, "main"), "a real commit on the server");
            assertEquals(seed, commitOnServer(result.commitSha()).getParent(0).getName());
            assertNotNull(published("main", RELEASE_DIR + "/helmrelease.yaml"));
            assertEquals(result.commitSha(), releases.get(K8sReleaseEntity.idOf(NS, RELEASE)).getGitCommitSha());
        }
    }

    @Test
    void aCommitThatLandsOnTheBranchFirstGetsTheDeployWrittenAgainOnTopOfIt() throws Exception {
        AtomicBoolean once = new AtomicBoolean();
        String[] other = new String[1];
        onSecretWrite = () -> {
            if (once.compareAndSet(false, true)) {
                try {
                    other[0] = GitTestServers.pushFromElsewhere(bareRepo, "main", "clusters/default/team-b/notes.txt",
                            "pushed meanwhile\n");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };

        FluxGitOpsBackend.CommitResult result = backend().commit(request(bareRepo.toUri().toString()), null, planned());

        assertTrue(result.committed());
        assertEquals(result.commitSha(), GitTestServers.head(bareRepo, "main"), "the rewritten push landed");
        assertEquals(other[0], commitOnServer(result.commitSha()).getParent(0).getName(),
                "made on top of the commit that landed first");
        assertNotNull(published("main", RELEASE_DIR + "/helmrelease.yaml"));
        assertEquals("pushed meanwhile\n", published("main", "clusters/default/team-b/notes.txt"),
                "the other commit is kept");
        assertEquals(result.commitSha(), releases.get(K8sReleaseEntity.idOf(NS, RELEASE)).getGitCommitSha());
    }

    @Test
    void twoReleasesDeployedToTheSameBranchAtTheSameTimeBothLand() throws Exception {
        String repoUrl = bareRepo.toUri().toString();
        // Each deploy has read the branch before either pushes: one of the two pushes is refused at first.
        CyclicBarrier bothRead = new CyclicBarrier(2);
        onSecretWrite = () -> {
            try {
                bothRead.await(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        FluxGitOpsBackend backend = backend();

        Future<FluxGitOpsBackend.CommitResult> first = executor.submit(
                () -> backend.commit(request(repoUrl, "superset-a", "main"), null, planned()));
        Future<FluxGitOpsBackend.CommitResult> second = executor.submit(
                () -> backend.commit(request(repoUrl, "superset-b", "main"), null, planned()));

        assertTrue(first.get(60, TimeUnit.SECONDS).committed());
        assertTrue(second.get(60, TimeUnit.SECONDS).committed());
        assertNotNull(published("main", "clusters/default/analytics/superset-a/helmrelease.yaml"));
        assertNotNull(published("main", "clusters/default/analytics/superset-b/helmrelease.yaml"));
        RevCommit head = commitOnServer(GitTestServers.head(bareRepo, "main"));
        RevCommit below = commitOnServer(head.getParent(0).getName());
        assertEquals(seed, below.getParent(0).getName(), "one commit after the other");
    }

    @Test
    void aPushThatLosesARaceInsideTheServerIsMadeAgainOnTopOfTheWinner() throws Exception {
        try (GitTestServers.GitDaemon server = new GitTestServers.GitDaemon(bareRepo)) {
            String[] other = new String[1];
            // Both pushes reach the server from the same branch; the other one updates it first. C git reports the
            // loser as "failed to update ref", not as a lock failure or a non-fast-forward.
            server.loseNextPushToRace(() -> other[0] = GitTestServers.pushFromElsewhere(bareRepo, "main",
                    "clusters/default/team-b/notes.txt", "pushed meanwhile\n"));

            FluxGitOpsBackend.CommitResult result = backend().commit(request(server.url()), null, planned());

            assertTrue(result.committed());
            assertEquals(2, server.pushes());
            assertEquals(result.commitSha(), GitTestServers.head(bareRepo, "main"), "the rewritten push landed");
            assertEquals(other[0], commitOnServer(result.commitSha()).getParent(0).getName(),
                    "made on top of the commit that won the race");
            assertEquals("pushed meanwhile\n", published("main", "clusters/default/team-b/notes.txt"));
            assertNotNull(published("main", RELEASE_DIR + "/helmrelease.yaml"));
            assertEquals(result.commitSha(), releases.get(K8sReleaseEntity.idOf(NS, RELEASE)).getGitCommitSha());
        }
    }

    @Test
    void aDeployToABranchWhoseNameClashesWithAnotherOnTheServerFailsAndRecordsNothing() throws Exception {
        // The server cannot create kdps/trino while a branch kdps exists: JGit-based servers report it as a failure
        // to lock the branch, the words they also use for a push that lost a race.
        GitTestServers.createBranch(bareRepo, "kdps", "main");
        String repoUrl = bareRepo.toUri().toString();

        GitPushRejectedException refused = assertThrows(GitPushRejectedException.class,
                () -> backend().commit(request(repoUrl, RELEASE, "kdps/trino"), null, planned()));
        assertTrue(refused.getMessage().contains("kdps/trino"), refused.getMessage());
        assertTrue(releases.isEmpty(), "no release metadata for a change Git does not hold");
        assertNull(secretPayloads.get(NS + "/superset-kdps-values"));

        // Retried as it is: the same refusal again, never "no changes" for a commit only the workspace holds.
        assertThrows(GitPushRejectedException.class,
                () -> backend().commit(request(repoUrl, RELEASE, "kdps/trino"), null, planned()));
        assertTrue(releases.isEmpty());
        assertEquals(seed, GitTestServers.head(bareRepo, "kdps"));
        assertEquals(seed, GitTestServers.head(bareRepo, "main"));
    }

    @Test
    void aDeployToANewBranchTheServerCannotLockFailsAndLandsOnceItCan() throws Exception {
        // A lock left behind by a server that stopped in the middle of an update of the branch.
        Path lock = Files.createDirectories(bareRepo.resolve("refs/heads")).resolve("flux-work.lock");
        Files.writeString(lock, "");
        String repoUrl = bareRepo.toUri().toString();

        assertThrows(GitPushRejectedException.class,
                () -> backend().commit(request(repoUrl, RELEASE, "flux-work"), null, planned()));
        assertTrue(releases.isEmpty(), "no release metadata for a commit that was never pushed");
        assertThrows(GitPushRejectedException.class,
                () -> backend().commit(request(repoUrl, RELEASE, "flux-work"), null, planned()));
        assertTrue(releases.isEmpty());
        assertNull(GitTestServers.head(bareRepo, "flux-work"));

        Files.delete(lock);
        FluxGitOpsBackend.CommitResult landed = backend().commit(request(repoUrl, RELEASE, "flux-work"), null,
                planned());
        assertTrue(landed.committed());
        assertEquals(landed.commitSha(), GitTestServers.head(bareRepo, "flux-work"));
        assertEquals(landed.commitSha(), releases.get(K8sReleaseEntity.idOf(NS, RELEASE)).getGitCommitSha());
    }

    @Test
    void aCommitTheServerNeverGotIsPushedRatherThanReportedAsNoChanges() throws Exception {
        String repoUrl = bareRepo.toUri().toString();
        // A pull request proposed without credentials stays a commit in the workspace only.
        HelmDeployRequest proposed = request(repoUrl, RELEASE, "kdps-change");
        proposed.getGit().setCommitMode("PR_MODE");
        FluxGitOpsBackend.CommitResult stub = backend().commit(proposed, null, planned());
        assertTrue(stub.id().startsWith("PR-STUB:"), stub.id());
        assertNull(GitTestServers.head(bareRepo, "kdps-change"));

        // The same change committed directly finds nothing new to commit, but Git does not hold it yet.
        FluxGitOpsBackend.CommitResult direct = backend().commit(request(repoUrl, RELEASE, "kdps-change"), null,
                planned());

        assertNotEquals("NO_CHANGES", direct.id());
        assertTrue(direct.committed());
        assertEquals(stub.commitSha().replace("PR-STUB:", ""), GitTestServers.head(bareRepo, "kdps-change"),
                "the commit made for the pull request is what was pushed");
        assertEquals(direct.commitSha(), GitTestServers.head(bareRepo, "kdps-change"));
    }

    // ---------------------------------------------------------------- branches merged and deleted on the server

    /** A deploy proposed as a pull request on {@code kdps-pr}, with the token PR mode needs. */
    private HelmDeployRequest pullRequest(int replicas) {
        HelmDeployRequest request = request(bareRepo.toUri().toString(), RELEASE, "kdps-pr");
        request.getGit().setCommitMode("PR_MODE");
        request.getGit().setAuthToken("t0k");
        request.setValues(new LinkedHashMap<>(Map.of("replicaCount", replicas)));
        return request;
    }

    @Test
    void aPullRequestSquashMergedAndDeletedSinceIsNotProposedAgainByARedeployWithTheSameValues() throws Exception {
        FluxGitOpsBackend.CommitResult proposed = backend().commit(pullRequest(2), null, planned());
        assertTrue(proposed.committed());
        assertFalse(proposed.appliedOnCommit(), "a pull request waits to be merged");
        assertEquals(proposed.commitSha(), GitTestServers.head(bareRepo, "kdps-pr"));

        // Merged as one new commit, and the branch deleted after its merge (the default of several Git servers): the
        // commit proposed is on none of the server's branches, yet main holds its change.
        String merged = GitTestServers.squashMergeAndDelete(bareRepo, "kdps-pr", "main");

        // A redeploy with the same values: a resync, a security refresh.
        FluxGitOpsBackend.CommitResult redeployed = backend().commit(pullRequest(2), null, planned());

        assertEquals("NO_CHANGES", redeployed.id());
        assertFalse(redeployed.committed());
        assertTrue(redeployed.appliedOnCommit(), "main, which Flux reads, holds the release as deployed");
        assertNull(GitTestServers.head(bareRepo, "kdps-pr"), "the merged branch is not pushed again");
        assertEquals(merged, GitTestServers.head(bareRepo, "main"));

        // A later change is proposed on top of main, without the commit merged already.
        FluxGitOpsBackend.CommitResult changed = backend().commit(pullRequest(3), null, planned());
        assertTrue(changed.committed());
        assertFalse(changed.appliedOnCommit());
        assertEquals(changed.commitSha(), GitTestServers.head(bareRepo, "kdps-pr"));
        assertEquals(merged, commitOnServer(changed.commitSha()).getParent(0).getName());
        assertTrue(published("kdps-pr", RELEASE_DIR + "/helmrelease.yaml").contains("replicaCount: 3"));
    }

    @Test
    void aWorkingBranchSquashMergedAndDeletedSinceIsNotPushedAgainByARedeployWithTheSameValues() throws Exception {
        String repoUrl = bareRepo.toUri().toString();
        FluxGitOpsBackend.CommitResult committed = backend().commit(request(repoUrl, RELEASE, "flux-work"), null,
                planned());
        assertTrue(committed.committed());
        assertFalse(committed.appliedOnCommit(), "a working branch waits to be merged into main");
        GitTestServers.squashMergeAndDelete(bareRepo, "flux-work", "main");

        FluxGitOpsBackend.CommitResult redeployed = backend().commit(request(repoUrl, RELEASE, "flux-work"), null,
                planned());

        assertEquals("NO_CHANGES", redeployed.id());
        assertFalse(redeployed.committed());
        assertTrue(redeployed.appliedOnCommit(), "main holds the release as deployed");
        assertNull(GitTestServers.head(bareRepo, "flux-work"), "the merged branch is not pushed again");
    }

    @Test
    void aPullRequestDeletedWithoutItsMergeIsProposedAgainByARedeploy() throws Exception {
        FluxGitOpsBackend.CommitResult proposed = backend().commit(pullRequest(2), null, planned());
        GitTestServers.deleteBranch(bareRepo, "kdps-pr");

        FluxGitOpsBackend.CommitResult redeployed = backend().commit(pullRequest(2), null, planned());

        // Main does not hold the change: it is pushed again rather than reported as already in Git.
        assertTrue(redeployed.committed());
        assertFalse(redeployed.appliedOnCommit());
        assertEquals(proposed.commitSha(), GitTestServers.head(bareRepo, "kdps-pr"));
        assertNull(published("main", RELEASE_DIR + "/helmrelease.yaml"));
    }

    @Test
    void anUninstallOfAPullRequestReleaseMergedAndDeletedSinceDoesNotPushTheMergedCommitAgain() throws Exception {
        backend().commit(pullRequest(2), null, planned());
        String merged = GitTestServers.squashMergeAndDelete(bareRepo, "kdps-pr", "main");
        // The removal, proposed on the branch and merged the same way, then the uninstall retried.
        backend().destroy(bareRepo.toUri().toString(), "kdps-pr", "main", RELEASE_DIR, "t0k", null, NS, RELEASE);
        String removed = GitTestServers.squashMergeAndDelete(bareRepo, "kdps-pr", "main");
        assertNotEquals(merged, removed);
        assertNull(published("main", RELEASE_DIR + "/helmrelease.yaml"));

        backend().destroy(bareRepo.toUri().toString(), "kdps-pr", "main", RELEASE_DIR, "t0k", null, NS, RELEASE);

        assertNull(GitTestServers.head(bareRepo, "kdps-pr"), "the merged removal is not pushed again");
        assertEquals(removed, GitTestServers.head(bareRepo, "main"));
    }

    // ---------------------------------------------------------------- uninstalls

    @Test
    void anUninstallWhoseRemovalGitRefusesFailsAndKeepsTheReleaseInGitAndItsRecord() throws Exception {
        try (GitTestServers.GitDaemon server = new GitTestServers.GitDaemon(bareRepo)) {
            FluxGitOpsBackend.CommitResult deployed = backend().commit(request(server.url()), null, planned());
            server.refusePushes("pre-receive hook declined", PROTECTED);

            GitPushRejectedException refused = assertThrows(GitPushRejectedException.class, () -> backend().destroy(
                    server.url(), "main", "main", RELEASE_DIR, null, null, NS, RELEASE));
            assertTrue(refused.getMessage().contains(PROTECTED), refused.getMessage());

            // Through the backend, as an uninstall that is not run from the Releases page goes.
            RuntimeException failed = assertThrows(RuntimeException.class, () -> backend().destroy(NS, RELEASE));
            assertTrue(failed.getMessage().contains(PROTECTED), failed.getMessage());

            assertEquals(deployed.commitSha(), GitTestServers.head(bareRepo, "main"), "Git still holds the release");
            assertNotNull(published("main", RELEASE_DIR + "/helmrelease.yaml"));
            assertNotNull(releases.get(K8sReleaseEntity.idOf(NS, RELEASE)), "the record is kept for a retry");
            assertNotNull(secretPayloads.get(NS + "/superset-kdps-values"), "Flux still reads the values Secret");
            // Nor is the HelmRelease deleted from the cluster: Flux would recreate it from Git.
            verify(k8s, never()).getClient();

            server.acceptPushes();
            backend().destroy(server.url(), "main", "main", RELEASE_DIR, null, null, NS, RELEASE);
            assertNull(published("main", RELEASE_DIR + "/helmrelease.yaml"), "a retry removes it");
        }
    }

    @Test
    void anUninstallToABranchWhoseNameClashesWithAnotherOnTheServerFailsAndKeepsTheRelease() throws Exception {
        String repoUrl = bareRepo.toUri().toString();
        FluxGitOpsBackend.CommitResult deployed = backend().commit(request(repoUrl), null, planned());
        GitTestServers.createBranch(bareRepo, "kdps", "main");

        // The removal goes to kdps/trino, which the server cannot create: an error, never "no changes".
        assertThrows(GitPushRejectedException.class, () -> backend().destroy(repoUrl, "kdps/trino", "main",
                RELEASE_DIR, null, null, NS, RELEASE));
        assertThrows(GitPushRejectedException.class, () -> backend().destroy(repoUrl, "kdps/trino", "main",
                RELEASE_DIR, null, null, NS, RELEASE));

        assertEquals(deployed.commitSha(), GitTestServers.head(bareRepo, "main"), "Git still holds the release");
        assertNotNull(published("main", RELEASE_DIR + "/helmrelease.yaml"));
        assertNotNull(secretPayloads.get(NS + "/superset-kdps-values"), "Flux still reads the values Secret");
    }

    // ---------------------------------------------------------------- unresponsive servers

    @Test
    void aGitServerThatStopsAnsweringFailsTheDeployWithinTheTimeoutAndTheNextOneIsNotBlocked() throws Exception {
        viewProperties.put(FluxGitOpsBackend.GIT_TIMEOUT_PROPERTY, "1");
        try (GitTestServers.Blackhole server = new GitTestServers.Blackhole()) {
            HelmDeployRequest request = request(server.url());
            request.getGit().setAuthToken("t0k");

            IOException failed = assertTimeoutPreemptively(Duration.ofSeconds(30),
                    () -> assertThrows(IOException.class, () -> backend().commit(request, null, planned())));
            assertTrue(GitClientServerFailureTest.timedOut(failed), failed.toString());
            assertEquals(1, server.connections(), "a timeout is not tried again");
            assertTrue(releases.isEmpty());

            HelmDeployRequest retried = request(server.url());
            retried.getGit().setAuthToken("t0k");
            IOException again = assertTimeoutPreemptively(Duration.ofSeconds(30),
                    () -> assertThrows(IOException.class, () -> backend().commit(retried, null, planned())));
            assertTrue(GitClientServerFailureTest.timedOut(again), again.toString());
            assertEquals(2, server.connections());
        }
    }

    @Test
    void anOperationWaitingOnASilentGitServerHoldsTheReleaseOnlyUntilItTimesOut() throws Exception {
        viewProperties.put(FluxGitOpsBackend.GIT_TIMEOUT_PROPERTY, "3");
        try (GitTestServers.Blackhole server = new GitTestServers.Blackhole()) {
            HelmDeployRequest request = request(server.url());
            request.getGit().setAuthToken("t0k");
            FluxGitOpsBackend stuck = backend();
            Future<FluxGitOpsBackend.CommitResult> deploy = executor.submit(() -> stuck.commit(request, null, planned()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (server.connections() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, server.connections(), "the deploy is waiting on the Git server, holding the release");

            FluxGitOpsBackend other = backend();
            other.setWorkspaceLockWait(Duration.ofMillis(200));
            FluxGitOpsBackend.GitOpsBusyException busy = assertThrows(FluxGitOpsBackend.GitOpsBusyException.class,
                    () -> other.destroy(server.url(), "main", "main", RELEASE_DIR, "t0k", null, NS, RELEASE));
            assertEquals("Another GitOps operation on analytics/superset is in progress. Try again once it has"
                    + " finished.", busy.getMessage());

            ExecutionException timedOut = assertThrows(ExecutionException.class, () -> deploy.get(30, TimeUnit.SECONDS));
            assertTrue(GitClientServerFailureTest.timedOut(timedOut.getCause()), timedOut.getCause().toString());

            // The release is free again: the uninstall now reaches the Git server, and fails on its timeout too.
            IOException uninstall = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertThrows(
                    IOException.class,
                    () -> other.destroy(server.url(), "main", "main", RELEASE_DIR, "t0k", null, NS, RELEASE)));
            assertTrue(GitClientServerFailureTest.timedOut(uninstall), uninstall.toString());
        }
    }

    @Test
    void theGitTimeoutIsAViewProperty() {
        assertEquals(GitClient.DEFAULT_TIMEOUT_SECONDS, FluxGitOpsBackend.gitTimeoutSeconds(ctx));
        viewProperties.put(FluxGitOpsBackend.GIT_TIMEOUT_PROPERTY, " 45 ");
        assertEquals(45, FluxGitOpsBackend.gitTimeoutSeconds(ctx));
        viewProperties.put(FluxGitOpsBackend.GIT_TIMEOUT_PROPERTY, "two minutes");
        assertEquals(GitClient.DEFAULT_TIMEOUT_SECONDS, FluxGitOpsBackend.gitTimeoutSeconds(ctx));
        viewProperties.put(FluxGitOpsBackend.GIT_TIMEOUT_PROPERTY, "0");
        assertEquals(GitClient.DEFAULT_TIMEOUT_SECONDS, FluxGitOpsBackend.gitTimeoutSeconds(ctx));
        assertEquals(GitClient.DEFAULT_TIMEOUT_SECONDS, FluxGitOpsBackend.gitTimeoutSeconds(null));
    }
}
