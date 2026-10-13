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

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.GitCredentialService;
import org.apache.ambari.view.k8s.service.HelmSetValues;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Where GitOps deploys and uninstalls land in Git, against a local repository whose {@code main} branch Flux
 * reconciles: a direct commit on the base branch, never on a branch of its own; a pull request on a branch of its own,
 * opened into the base branch recorded for the release, reused only while it is open and started again from the base
 * branch once it is merged or closed; an uninstall removing the release by a direct commit, or through a pull request
 * whose merge the release's record waits for.
 */
class FluxGitOpsBackendBranchTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "superset";
    private static final String RELEASE_DIR = "clusters/default/analytics/superset";
    private static final String MANIFEST = RELEASE_DIR + "/helmrelease.yaml";
    private static final String VALUES_SECRET = NS + "/superset-kdps-values";
    private static final String TOKEN = "t0k";

    @TempDir
    Path tmp;

    private ViewContext ctx;
    private KubernetesService k8s;
    private KubernetesClient client;
    private GitCredentialService credentials;
    private DataStore ds;
    private Path bareRepo;
    private String repoUrl;
    private String seed;
    private final FakePullRequests pullRequests = new FakePullRequests();
    private final Map<String, String> secretPayloads = new ConcurrentHashMap<>();
    private final Map<String, K8sReleaseEntity> releases = new ConcurrentHashMap<>();
    private final Map<String, String> stored = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        bareRepo = GitTestServers.seededBareRepo(tmp);
        repoUrl = bareRepo.toUri().toString();
        seed = GitTestServers.head(bareRepo, "main");

        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir",
                Files.createDirectories(tmp.resolve("work")).toString()));
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getInstanceName()).thenReturn("kdps");
        when(ctx.getInstanceData(anyString())).thenReturn(null);
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
        ds = mock(DataStore.class);
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
        when(ds.findAll(eq(K8sReleaseEntity.class), any())).thenAnswer(inv -> List.copyOf(releases.values()));
        doAnswer(inv -> releases.remove(((K8sReleaseEntity) inv.getArgument(0)).getId()))
                .when(ds).remove(any(K8sReleaseEntity.class));

        k8s = mock(KubernetesService.class);
        when(k8s.readOpaqueSecretKeyAsString(anyString(), anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(secretPayloads.get(inv.getArgument(0) + "/" + inv.getArgument(1))));
        doAnswer(inv -> {
            secretPayloads.put(inv.getArgument(0) + "/" + inv.getArgument(1),
                    new String((byte[]) inv.getArgument(3), StandardCharsets.UTF_8));
            return null;
        }).when(k8s).createOrUpdateOpaqueSecret(anyString(), anyString(), anyString(), any(byte[].class), anyMap(), any(), any());
        doAnswer(inv -> secretPayloads.remove(inv.getArgument(0) + "/" + inv.getArgument(1)))
                .when(k8s).deleteSecret(anyString(), anyString());
        when(k8s.getSecret(anyString(), anyString())).thenAnswer(inv ->
                secretPayloads.containsKey(inv.getArgument(0) + "/" + inv.getArgument(1))
                        ? new io.fabric8.kubernetes.api.model.SecretBuilder().withNewMetadata()
                                .withName(inv.getArgument(1)).addToLabels(FluxGitOpsBackend.MANAGED_BY_LABEL,
                                        FluxGitOpsBackend.MANAGED_BY_VALUE).endMetadata().build()
                        : null);
        client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);

        // Ambari's credential store: what a deploy keeps for the release, bound to the host it was entered for.
        credentials = mock(GitCredentialService.class);
        Map<String, String> hosts = new ConcurrentHashMap<>();
        when(credentials.storeSecret(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            stored.put(inv.getArgument(0), inv.getArgument(1));
            hosts.put(inv.getArgument(0), GitCredentialService.hostOf(inv.getArgument(2)));
            return inv.getArgument(0);
        });
        when(credentials.resolveSecret(anyString())).thenAnswer(inv -> stored.get((String) inv.getArgument(0)));
        when(credentials.boundHost(anyString())).thenAnswer(inv -> hosts.get((String) inv.getArgument(0)));
        when(credentials.removeSecret(anyString())).thenAnswer(inv -> stored.remove((String) inv.getArgument(0)) != null);
    }

    private FluxGitOpsBackend backend() throws Exception {
        FluxGitOpsBackend backend = new FluxGitOpsBackend(tmp.resolve("flux"), k8s, ctx);
        Field field = FluxGitOpsBackend.class.getDeclaredField("gitCredentialService");
        field.setAccessible(true);
        field.set(backend, credentials);
        backend.setPullRequests(pullRequests);
        return backend;
    }

    /** A Superset deploy as its GITOPS_COMMIT step rebuilds it, with the Git options of a redeploy: none of its own. */
    private HelmDeployRequest deploy(int replicas) {
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
        request.setGit(git);
        return request;
    }

    /** The first deploy of the release by direct commit, as the wizard submits it with its defaults. */
    private HelmDeployRequest directCommit(int replicas) {
        HelmDeployRequest request = deploy(replicas);
        request.getGit().setCommitMode("DIRECT_COMMIT");
        request.getGit().setAuthToken(TOKEN);
        return request;
    }

    /** A deploy proposed in a pull request, the Git server's token given. */
    private HelmDeployRequest pullRequest(int replicas) {
        HelmDeployRequest request = deploy(replicas);
        request.getGit().setCommitMode("PR_MODE");
        request.getGit().setAuthToken(TOKEN);
        return request;
    }

    /** What the command plan prepared: an LDAP bind password, which goes to the values Secret. */
    private static FluxGitOpsBackend.PlannedRelease planned() {
        return new FluxGitOpsBackend.PlannedRelease(Map.of("global.security.auth.ldap.bindPassword",
                HelmSetValues.asText("Bind-Pass-42")), Boolean.FALSE);
    }

    /** What the command plan prepared for a release without passwords: nothing goes to a values Secret. */
    private static FluxGitOpsBackend.PlannedRelease withoutSecrets() {
        return new FluxGitOpsBackend.PlannedRelease(Map.of(), Boolean.FALSE);
    }

    private K8sReleaseEntity recorded() {
        return releases.get(K8sReleaseEntity.idOf(NS, RELEASE));
    }

    /** A file as a branch of the server holds it, or null. */
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

    private List<String> branchesOnServer() throws Exception {
        try (Git g = Git.open(bareRepo.toFile())) {
            return g.branchList().call().stream().map(ref -> Repository.shortenRefName(ref.getName())).sorted().toList();
        }
    }

    private RevCommit commitOnServer(String sha) throws Exception {
        try (Git g = Git.open(bareRepo.toFile()); RevWalk walk = new RevWalk(g.getRepository())) {
            return walk.parseCommit(g.getRepository().resolve(sha));
        }
    }

    /** Merges a branch into another with a merge commit, the merged branch kept, as a pull request merged by default. */
    private String mergeOnServer(String branch, String into) throws Exception {
        Path clone = Files.createTempDirectory(tmp, "merger-");
        try (Git g = Git.cloneRepository().setURI(repoUrl).setBranch(into).setDirectory(clone.toFile()).call()) {
            g.getRepository().getConfig().setBoolean("gc", null, "autoDetach", false);
            g.merge().include(g.getRepository().resolve("refs/remotes/origin/" + branch)).setMessage("Merge")
                    .setCommit(true).call();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/" + into)).call();
            return g.getRepository().resolve("HEAD").getName();
        }
    }

    /**
     * Leaves the server with a single branch, {@code master}, holding what {@code main} held: a repository whose
     * default branch is master.
     */
    private void onlyMasterOnServer() throws Exception {
        GitTestServers.createBranch(bareRepo, "master", "main");
        try (Git g = Git.open(bareRepo.toFile())) {
            RefUpdate head = g.getRepository().updateRef(Constants.HEAD);
            head.disableRefLog();
            head.link("refs/heads/master");
        }
        GitTestServers.deleteBranch(bareRepo, "main");
    }

    /**
     * The cluster, holding the release's HelmRelease or not: what Flux runs, whatever branch it reads.
     *
     * @return the HelmRelease as the backend reaches it, to tell whether it was deleted
     */
    @SuppressWarnings("unchecked")
    private Resource<GenericKubernetesResource> helmReleaseOnCluster(boolean present) {
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>>
                resources = mock(MixedOperation.class);
        NonNamespaceOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> inNamespace = mock(NonNamespaceOperation.class);
        Resource<GenericKubernetesResource> helmRelease = mock(Resource.class);
        when(client.genericKubernetesResources(any(CustomResourceDefinitionContext.class))).thenReturn(resources);
        when(resources.inNamespace(anyString())).thenReturn(inNamespace);
        when(inNamespace.withName(anyString())).thenReturn(helmRelease);
        when(helmRelease.get()).thenReturn(present ? new GenericKubernetesResource() : null);
        when(k8s.getClient()).thenReturn(client);
        return helmRelease;
    }

    /**
     * Has each read of a release's record return a copy, as Ambari's data store does: a record read before another
     * operation changed it no longer follows that change.
     */
    private void readCopies() throws Exception {
        when(ds.find(eq(K8sReleaseEntity.class), anyString())).thenAnswer(inv -> {
            K8sReleaseEntity record = releases.get((String) inv.getArgument(1));
            if (record == null) {
                return null;
            }
            K8sReleaseEntity copy = new K8sReleaseEntity();
            // The id and the other fields every entity has are declared on its superclasses. What is not persisted
            // is not copied: a parsed cache shared with the stored record would follow its later changes.
            for (Class<?> type = K8sReleaseEntity.class; type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                            && !field.isAnnotationPresent(javax.persistence.Transient.class)) {
                        field.setAccessible(true);
                        field.set(copy, field.get(record));
                    }
                }
            }
            return copy;
        });
    }

    /**
     * Waits until a thread is parked waiting, as an operation is for the lock of the release's workspace while another
     * one holds it.
     */
    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (thread.getState() != Thread.State.TIMED_WAITING && thread.getState() != Thread.State.TERMINATED) {
            assertTrue(System.nanoTime() < deadline, thread.getName() + " never waited");
            Thread.sleep(10);
        }
    }

    /**
     * Waits until the operation run by {@code task} is opening its pull request on {@code server}; fails with the
     * operation's own error when it ended before that.
     */
    private static void awaitOpening(HeldPullRequests server, FutureTask<?> task, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!server.opening.await(10, TimeUnit.MILLISECONDS)) {
            if (task.isDone()) {
                task.get();
                fail(what + " ended without opening a pull request");
            }
            assertTrue(System.nanoTime() < deadline, what + " never reached its pull request");
        }
    }

    /**
     * A Git server whose next pull request opened, once {@link #holdNextOpen} is called, is held until the test lets
     * it through: the operation opening it holds the release's workspace meanwhile.
     */
    private static final class HeldPullRequests extends FakePullRequests {
        final CountDownLatch opening = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean hold = new AtomicBoolean();

        void holdNextOpen() {
            hold.set(true);
        }

        @Override
        public PullRequestInfo open(String repoUrl, String base, String head, String title, String token) {
            if (hold.compareAndSet(true, false)) {
                opening.countDown();
                try {
                    assertTrue(release.await(30, TimeUnit.SECONDS), "the test let the pull request through");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return super.open(repoUrl, base, head, title, token);
        }
    }

    // ---------------------------------------------------------------- direct commits

    @Test
    void aDirectCommitWithTheWizardsDefaultsLandsOnMain() throws Exception {
        FluxGitOpsBackend.CommitResult result = backend().commit(directCommit(2), null, planned());

        assertTrue(result.committed());
        assertEquals("main", result.branch());
        assertEquals("main", result.baseBranch());
        assertTrue(result.appliedOnCommit(), "Flux reads main: the change is applied as pushed");
        assertEquals(result.commitSha(), GitTestServers.head(bareRepo, "main"));
        assertEquals(List.of("main"), branchesOnServer(), "no branch of its own");
        assertTrue(published("main", MANIFEST).contains("replicaCount: 2"));
        assertTrue(pullRequests.opened.isEmpty());
        assertEquals("main", recorded().getGitBranch());
        assertEquals("main", recorded().getGitBaseBranch());
        assertEquals("DIRECT_COMMIT", recorded().getGitCommitMode());
        assertNull(recorded().getGitPrUrl());

        // A resync, a security refresh, an autoscaling redeploy: nothing but the repository named.
        FluxGitOpsBackend.CommitResult redeployed = backend().commit(deploy(3), null, planned());
        assertEquals("main", redeployed.branch());
        assertEquals(redeployed.commitSha(), GitTestServers.head(bareRepo, "main"));
        assertTrue(published("main", MANIFEST).contains("replicaCount: 3"));
        assertEquals(List.of("main"), branchesOnServer());
    }

    @Test
    void aDirectCommitGoesToTheBaseBranchRecordedForTheRelease() throws Exception {
        GitTestServers.createBranch(bareRepo, "production", "main");
        HelmDeployRequest first = directCommit(2);
        first.getGit().setBaseBranch("production");
        backend().commit(first, null, planned());

        FluxGitOpsBackend.CommitResult redeployed = backend().commit(deploy(3), null, planned());

        assertEquals("production", redeployed.branch());
        assertTrue(published("production", MANIFEST).contains("replicaCount: 3"));
        assertNull(published("main", MANIFEST), "main is not the branch Flux reads");
        assertEquals(seed, GitTestServers.head(bareRepo, "main"));
    }

    @Test
    void aDirectCommitToAnotherBranchThanTheBaseBranchIsRefusedAndPushesNothing() throws Exception {
        HelmDeployRequest request = directCommit(2);
        request.getGit().setBranch("feature/ambari-flux");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> backend().commit(request, null, planned()));

        assertEquals("Direct commit pushes the change to the base branch main, which Flux reconciles, so the working"
                + " branch 'feature/ambari-flux' cannot be used: a working branch is only the branch of a pull request."
                + " Leave it empty, or choose to open a pull request.", refused.getMessage());
        assertEquals(List.of("main"), branchesOnServer());
        assertEquals(seed, GitTestServers.head(bareRepo, "main"));
        assertTrue(releases.isEmpty());
        assertTrue(secretPayloads.isEmpty(), "nothing was prepared");
    }

    @Test
    void aReleaseCommittedToAGeneratedBranchByAnEarlierVersionIsCommittedToMainFromNowOn() throws Exception {
        // As earlier versions recorded a direct commit deployed with an empty working branch: on a branch of its own,
        // which Flux never read.
        backend().commit(directCommit(2), null, planned());
        K8sReleaseEntity legacy = recorded();
        legacy.setGitBranch("flux-3f2a1c4e-0b5d-4c6e-9f7a-8b9c0d1e2f3a");
        legacy.setGitBaseBranch(null);
        HelmDeployRequest echoed = deploy(3);
        echoed.getGit().setBranch(legacy.getGitBranch());

        FluxGitOpsBackend.CommitResult redeployed = backend().commit(echoed, null, planned());

        assertEquals("main", redeployed.branch());
        assertTrue(published("main", MANIFEST).contains("replicaCount: 3"));
        assertEquals("main", recorded().getGitBranch());
        assertEquals("main", recorded().getGitBaseBranch());
    }

    @Test
    void aDirectCommitToABaseBranchTheServerDoesNotHaveIsRefusedAndCreatesNoBranch() throws Exception {
        onlyMasterOnServer();
        String master = GitTestServers.head(bareRepo, "master");

        java.io.IOException refused = assertThrows(java.io.IOException.class,
                () -> backend().commit(directCommit(2), null, planned()));

        assertEquals("Branch main is not on " + repoUrl + ": a direct commit goes to the branch Flux reconciles, and"
                + " KDPS does not create it. Create it, or choose another base branch.", refused.getMessage());
        assertEquals(List.of("master"), branchesOnServer(), "no main, which Flux does not read");
        assertEquals(master, GitTestServers.head(bareRepo, "master"));
        assertTrue(releases.isEmpty());
        assertTrue(secretPayloads.isEmpty(), "nothing was prepared");

        // Named, the branch Flux reads takes the commit.
        HelmDeployRequest onMaster = directCommit(2);
        onMaster.getGit().setBaseBranch("master");
        FluxGitOpsBackend.CommitResult committed = backend().commit(onMaster, null, planned());
        assertEquals("master", committed.branch());
        assertTrue(published("master", MANIFEST).contains("replicaCount: 2"));
    }

    @Test
    void theFirstDirectCommitToAnEmptyRepositoryCreatesTheBaseBranch() throws Exception {
        Path empty = tmp.resolve("empty.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(empty.toFile()).call().close();
        // No background gc after a push: it would still hold gc.log.lock when the temp directory is removed.
        try (Git bareGit = Git.open(empty.toFile())) {
            org.eclipse.jgit.lib.StoredConfig bareConfig = bareGit.getRepository().getConfig();
            bareConfig.setInt("gc", null, "auto", 0);
            bareConfig.setBoolean("receive", null, "autogc", false);
            bareConfig.save();
        }
        bareRepo = empty;
        HelmDeployRequest request = directCommit(2);
        request.getGit().setRepoUrl(empty.toUri().toString());

        FluxGitOpsBackend.CommitResult committed = backend().commit(request, null, planned());

        assertEquals("main", committed.branch());
        assertEquals(List.of("main"), branchesOnServer());
        assertTrue(published("main", MANIFEST).contains("replicaCount: 2"));
    }

    @Test
    void aSwitchToDirectCommitWithNothingNewIsRecordedAndTheNextChangeIsCommittedToMain() throws Exception {
        FluxGitOpsBackend.CommitResult proposed = backend().commit(pullRequest(2), null, withoutSecrets());
        GitTestServers.squashMergeAndDelete(bareRepo, proposed.branch(), "main");
        pullRequests.merge("1");

        FluxGitOpsBackend.CommitResult switched = backend().commit(directCommit(2), null, withoutSecrets());

        assertEquals("NO_CHANGES", switched.id());
        assertTrue(switched.appliedOnCommit());
        assertEquals("main", switched.branch());
        assertNull(switched.pullRequestUrl());
        K8sReleaseEntity record = recorded();
        assertEquals("DIRECT_COMMIT", record.getGitCommitMode());
        assertEquals("main", record.getGitBranch());
        assertEquals("main", record.getGitBaseBranch());
        assertNull(record.getGitPrNumber(), "a direct commit forgets the merged pull request");

        // A redeploy from the Releases page, which sends the recorded commit mode and base branch back.
        HelmDeployRequest redeploy = deploy(3);
        redeploy.getGit().setCommitMode(record.getGitCommitMode());
        redeploy.getGit().setBaseBranch(record.getGitBaseBranch());
        FluxGitOpsBackend.CommitResult committed = backend().commit(redeploy, null, withoutSecrets());

        assertTrue(committed.committed());
        assertEquals(committed.commitSha(), GitTestServers.head(bareRepo, "main"));
        assertTrue(published("main", MANIFEST).contains("replicaCount: 3"));
        assertEquals(1, pullRequests.opened.size(), "no pull request");
    }

    // ---------------------------------------------------------------- pull requests

    @Test
    void aPullRequestFromTheBaseBranchItselfIsRefusedAndPushesNothing() throws Exception {
        HelmDeployRequest request = pullRequest(2);
        request.getGit().setBranch("main");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> backend().commit(request, null, planned()));

        assertEquals("A pull request is opened from its working branch into the base branch main, which Flux"
                + " reconciles, so the working branch cannot be main as well: the change would reach main without"
                + " review. Leave the working branch empty, name another branch, or choose Direct commit.",
                refused.getMessage());
        assertEquals(List.of("main"), branchesOnServer());
        assertEquals(seed, GitTestServers.head(bareRepo, "main"), "nothing reached the branch Flux reads");
        assertTrue(pullRequests.opened.isEmpty());
        assertTrue(releases.isEmpty());
        assertTrue(secretPayloads.isEmpty(), "nothing was prepared");
    }

    @Test
    void aSwitchToPullRequestsThatSendsTheRecordedBranchBackStillOpensOneFromABranchOfItsOwn() throws Exception {
        backend().commit(directCommit(2), null, planned());
        // A client sending the release's record back: the branch of a direct commit is the base branch.
        HelmDeployRequest echoed = pullRequest(3);
        echoed.getGit().setBranch(recorded().getGitBranch());

        FluxGitOpsBackend.CommitResult proposed = backend().commit(echoed, null, planned());

        assertTrue(proposed.branch().matches("flux-[0-9a-f-]{36}"), proposed.branch());
        assertEquals(new FakePullRequests.Opened("1", "main", proposed.branch(), "Flux GitOps (PR) apply " + NS + "/"
                + RELEASE, TOKEN), pullRequests.last());
        assertTrue(published("main", MANIFEST).contains("replicaCount: 2"), "main waits for the merge");
    }

    @Test
    void aSwitchToPullRequestsWithNothingNewIsRecordedAndTheNextChangeIsProposed() throws Exception {
        backend().commit(directCommit(2), null, planned());

        FluxGitOpsBackend.CommitResult switched = backend().commit(pullRequest(2), null, planned());

        assertEquals("NO_CHANGES", switched.id());
        assertTrue(pullRequests.opened.isEmpty(), "nothing to propose");
        assertEquals("PR_MODE", recorded().getGitCommitMode());

        // A security refresh, which sends the recorded commit mode back: reviewed, not committed to main.
        String main = GitTestServers.head(bareRepo, "main");
        HelmDeployRequest refresh = deploy(3);
        refresh.getGit().setCommitMode(recorded().getGitCommitMode());
        refresh.getGit().setBaseBranch(recorded().getGitBaseBranch());
        FluxGitOpsBackend.CommitResult proposed = backend().commit(refresh, null, planned());

        assertEquals(main, GitTestServers.head(bareRepo, "main"), "main is not changed before the review");
        assertEquals(new FakePullRequests.Opened("1", "main", proposed.branch(), "Flux GitOps (PR) apply " + NS + "/"
                + RELEASE, TOKEN), pullRequests.last());
    }

    @Test
    void aPullRequestLandsOnlyOnItsBranchAndIsOpenedIntoTheBaseBranch() throws Exception {
        GitTestServers.createBranch(bareRepo, "production", "main");
        HelmDeployRequest request = pullRequest(2);
        request.getGit().setBaseBranch("production");

        FluxGitOpsBackend.CommitResult proposed = backend().commit(request, null, planned());

        String branch = proposed.branch();
        assertTrue(branch.matches("flux-[0-9a-f-]{36}"), branch);
        assertTrue(proposed.committed());
        assertFalse(proposed.appliedOnCommit(), "Flux applies it once it is merged");
        assertEquals("production", proposed.baseBranch());
        assertEquals(proposed.commitSha(), GitTestServers.head(bareRepo, branch));
        assertEquals(seed, commitOnServer(proposed.commitSha()).getParent(0).getName(), "started from production");
        assertEquals(seed, GitTestServers.head(bareRepo, "production"), "the branch Flux reads is untouched");
        assertEquals(seed, GitTestServers.head(bareRepo, "main"));
        assertEquals(new FakePullRequests.Opened("1", "production", branch, "Flux GitOps (PR) apply " + NS + "/"
                + RELEASE, TOKEN), pullRequests.last());
        assertEquals(FakePullRequests.url("1"), proposed.pullRequestUrl());
        assertEquals("open", proposed.pullRequestState());
        assertNull(secretPayloads.get(VALUES_SECRET), "the running release's Secret is not changed before the merge");

        K8sReleaseEntity record = recorded();
        assertEquals(branch, record.getGitBranch());
        assertEquals("production", record.getGitBaseBranch());
        assertEquals("PR_MODE", record.getGitCommitMode());
        assertEquals(FakePullRequests.url("1"), record.getGitPrUrl());
        assertEquals("1", record.getGitPrNumber());
        assertEquals("open", record.getGitPrState());
    }

    @Test
    void aChangeWhileThePullRequestIsOpenJoinsIt() throws Exception {
        FluxGitOpsBackend.CommitResult proposed = backend().commit(pullRequest(2), null, planned());

        // A security refresh from the Releases page, which sends the release's base branch and commit mode back.
        HelmDeployRequest refresh = pullRequest(3);
        refresh.getGit().setBaseBranch("main");
        FluxGitOpsBackend.CommitResult joined = backend().commit(refresh, null, planned());

        assertEquals(proposed.branch(), joined.branch());
        assertEquals(proposed.commitSha(), commitOnServer(joined.commitSha()).getParent(0).getName(),
                "on top of the pull request's commit");
        assertEquals(joined.commitSha(), GitTestServers.head(bareRepo, proposed.branch()));
        assertEquals(1, pullRequests.opened.size(), "no second pull request");
        assertEquals(FakePullRequests.url("1"), joined.pullRequestUrl());
        assertEquals(proposed.branch(), recorded().getGitBranch());
        assertEquals("1", recorded().getGitPrNumber());
        assertNull(published("main", MANIFEST));
    }

    @Test
    void aRedeployAfterTheMergeBranchesFromTheNewMainAndOpensANewPullRequest() throws Exception {
        FluxGitOpsBackend.CommitResult first = backend().commit(pullRequest(2), null, planned());
        // Merged with a merge commit; the branch stays on the server.
        String merged = mergeOnServer(first.branch(), "main");
        pullRequests.merge("1");

        FluxGitOpsBackend.CommitResult second = backend().commit(pullRequest(3), null, planned());

        assertNotEquals(first.branch(), second.branch(), "never the merged pull request's branch");
        assertEquals(merged, commitOnServer(second.commitSha()).getParent(0).getName(), "started from the new main");
        assertEquals(first.commitSha(), GitTestServers.head(bareRepo, first.branch()), "the merged branch is untouched");
        assertEquals(new FakePullRequests.Opened("2", "main", second.branch(), "Flux GitOps (PR) apply " + NS + "/"
                + RELEASE, TOKEN), pullRequests.last());
        assertEquals("2", recorded().getGitPrNumber());
        assertEquals(second.branch(), recorded().getGitBranch());
        assertTrue(published(second.branch(), MANIFEST).contains("replicaCount: 3"));
        assertTrue(published("main", MANIFEST).contains("replicaCount: 2"));
    }

    @Test
    void aPullRequestTheGitServerDoesNotOpenFailsTheDeployWithItsReason() throws Exception {
        FakePullRequests refusing = new FakePullRequests() {
            @Override
            public PullRequestInfo open(String repoUrl, String base, String head, String title, String token) {
                throw new IllegalStateException("Gitea did not open the pull request of branch " + head + " into "
                        + base + " of platform/gitops (HTTP 403): token does not have at least one of required scope");
            }
        };
        FluxGitOpsBackend backend = backend();
        backend.setPullRequests(refusing);

        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> backend.commit(pullRequest(2), null, planned()));

        assertTrue(failed.getMessage().startsWith("The change of analytics/superset was pushed to branch flux-"),
                failed.getMessage());
        assertTrue(failed.getMessage().contains("but its pull request into main could not be opened, so Flux will not"
                + " apply it: Gitea did not open the pull request"), failed.getMessage());
        assertTrue(failed.getMessage().contains("(HTTP 403): token does not have at least one of required scope"),
                failed.getMessage());
        assertTrue(releases.isEmpty(), "never recorded as deployed");
    }

    @Test
    void aPullRequestOnAGitServerKdpsCannotOpenOneOnIsRefusedBeforeAnythingIsPushed() throws Exception {
        FluxGitOpsBackend backend = backend();
        backend.setPullRequests(new GitPullRequests(null));

        GitPullRequests.UnsupportedGitServerException refused = assertThrows(
                GitPullRequests.UnsupportedGitServerException.class, () -> backend.commit(pullRequest(2), null, planned()));

        assertTrue(refused.getMessage().startsWith("KDPS opens pull requests on GitHub, GitLab and Gitea servers only"),
                refused.getMessage());
        assertEquals(List.of("main"), branchesOnServer());
        assertTrue(releases.isEmpty());
        assertTrue(secretPayloads.isEmpty());
    }

    @Test
    void aPullRequestWithoutAnAccessTokenIsRefused() throws Exception {
        HelmDeployRequest request = pullRequest(2);
        request.getGit().setAuthToken(null);
        request.getGit().setSshKey("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\n");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> backend().commit(request, null, planned()));

        assertTrue(refused.getMessage().startsWith("Pull request mode needs an access token of the Git server"),
                refused.getMessage());
        assertEquals(List.of("main"), branchesOnServer());
    }

    @Test
    void theStateOfThePreviousPullRequestMustBeReadable() throws Exception {
        backend().commit(pullRequest(2), null, planned());
        FakePullRequests forgetful = new FakePullRequests();
        FluxGitOpsBackend backend = backend();
        backend.setPullRequests(forgetful);
        String head = GitTestServers.head(bareRepo, recorded().getGitBranch());

        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> backend.commit(pullRequest(3), null, planned()));

        assertTrue(failed.getMessage().startsWith("KDPS could not read the state of pull request "
                + FakePullRequests.url("1") + " of analytics/superset, so nothing was committed"), failed.getMessage());
        assertEquals(head, GitTestServers.head(bareRepo, recorded().getGitBranch()));
        assertTrue(forgetful.opened.isEmpty());
    }

    @Test
    void aRedeployIntoAnotherBaseBranchOpensAPullRequestIntoItAndClosesTheOneIntoTheOldBase() throws Exception {
        FluxGitOpsBackend.CommitResult intoMain = backend().commit(pullRequest(2), null, planned());
        // Flux moved to production; the upgrade names it as the base branch.
        GitTestServers.createBranch(bareRepo, "production", "main");
        HelmDeployRequest upgrade = pullRequest(3);
        upgrade.getGit().setBaseBranch("production");

        FluxGitOpsBackend.CommitResult intoProduction = backend().commit(upgrade, null, planned());

        assertNotEquals(intoMain.branch(), intoProduction.branch(), "never the branch of a pull request into main");
        assertEquals(seed, commitOnServer(intoProduction.commitSha()).getParent(0).getName(), "started from production");
        assertEquals(new FakePullRequests.Opened("2", "production", intoProduction.branch(), "Flux GitOps (PR) apply "
                + NS + "/" + RELEASE, TOKEN), pullRequests.last());
        assertEquals(List.of("1"), pullRequests.closedByKdps, "merged into main, Flux would never read it");
        assertEquals(intoMain.commitSha(), GitTestServers.head(bareRepo, intoMain.branch()), "nothing added to it");
        assertEquals(FakePullRequests.url("2"), intoProduction.pullRequestUrl());
        K8sReleaseEntity record = recorded();
        assertEquals("production", record.getGitBaseBranch());
        assertEquals(intoProduction.branch(), record.getGitBranch());
        assertEquals("2", record.getGitPrNumber());
        assertEquals("open", record.getGitPrState());
    }

    @Test
    void aSwitchToDirectCommitWhileThePullRequestIsOpenClosesItSoNoUninstallLeavesItBehind() throws Exception {
        FluxGitOpsBackend.CommitResult proposed = backend().commit(pullRequest(2), null, planned());

        FluxGitOpsBackend.CommitResult committed = backend().commit(directCommit(3), null, planned());

        assertEquals(committed.commitSha(), GitTestServers.head(bareRepo, "main"));
        assertTrue(published("main", MANIFEST).contains("replicaCount: 3"));
        assertEquals("closed", pullRequests.state("1"), "merged later, it would bring back what it proposes");
        assertEquals(List.of("1"), pullRequests.closedByKdps);
        assertEquals(1, pullRequests.opened.size());
        assertNull(recorded().getGitPrNumber());
        assertEquals("DIRECT_COMMIT", recorded().getGitCommitMode());
        assertNotNull(published(proposed.branch(), MANIFEST), "its branch is left as the server holds it");

        backend().destroy(NS, RELEASE);

        assertNull(published("main", MANIFEST));
        assertNull(recorded());
        assertEquals("closed", pullRequests.state("1"), "nothing left to merge the release back with");
    }

    @Test
    void aSwitchToDirectCommitWithNothingNewWhileThePullRequestIsOpenClosesIt() throws Exception {
        backend().commit(directCommit(2), null, withoutSecrets());
        backend().commit(pullRequest(3), null, withoutSecrets());
        assertEquals("open", pullRequests.state("1"));

        // Back to the values main holds, by direct commit: nothing to commit, and the pull request is not wanted.
        FluxGitOpsBackend.CommitResult switched = backend().commit(directCommit(2), null, withoutSecrets());

        assertEquals("NO_CHANGES", switched.id());
        assertEquals(List.of("1"), pullRequests.closedByKdps);
        assertNull(switched.pullRequestUrl());
        assertNull(recorded().getGitPrNumber());
        assertEquals("main", recorded().getGitBranch());
    }

    @Test
    void aNewlyNamedWorkingBranchWhileThePullRequestIsOpenClosesItAndOpensOneFromThatBranch() throws Exception {
        FluxGitOpsBackend.CommitResult first = backend().commit(pullRequest(2), null, planned());
        HelmDeployRequest named = pullRequest(3);
        named.getGit().setBranch("feature/superset-replicas");

        FluxGitOpsBackend.CommitResult second = backend().commit(named, null, planned());

        assertEquals("feature/superset-replicas", second.branch());
        assertEquals(new FakePullRequests.Opened("2", "main", "feature/superset-replicas", "Flux GitOps (PR) apply "
                + NS + "/" + RELEASE, TOKEN), pullRequests.last());
        assertEquals(List.of("1"), pullRequests.closedByKdps);
        assertEquals(first.commitSha(), GitTestServers.head(bareRepo, first.branch()));
        assertEquals("2", recorded().getGitPrNumber());
        assertEquals("feature/superset-replicas", recorded().getGitBranch());
    }

    @Test
    void aPullRequestThatCannotBeClosedStopsTheDeployBeforeAnythingIsCommitted() throws Exception {
        backend().commit(pullRequest(2), null, planned());
        FakePullRequests refusing = new FakePullRequests() {
            @Override
            public PullRequestInfo close(String repoUrl, String number, String token) throws IOException {
                throw new IOException("Gitea refused to close pull request " + number + " (HTTP 403)");
            }
        };
        refusing.open(repoUrl, "main", recorded().getGitBranch(), "Flux GitOps (PR) apply " + NS + "/" + RELEASE,
                TOKEN);
        FluxGitOpsBackend backend = backend();
        backend.setPullRequests(refusing);
        Map<String, String> secretsBefore = Map.copyOf(secretPayloads);

        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> backend.commit(directCommit(3), null, planned()));

        assertEquals("KDPS could not close pull request " + FakePullRequests.url("1") + " of " + NS + "/" + RELEASE
                + ", still open, which this deploy no longer goes through, so nothing was committed: merged later, it"
                + " would bring back what it proposes. Gitea refused to close pull request 1 (HTTP 403) Close it"
                + " without merging it, then deploy again.", failed.getMessage());
        assertEquals(seed, GitTestServers.head(bareRepo, "main"));
        assertEquals(secretsBefore, secretPayloads, "the running release's Secret is not changed");
        assertEquals("PR_MODE", recorded().getGitCommitMode());
        assertEquals("1", recorded().getGitPrNumber(), "the record still names it");
    }

    // ---------------------------------------------------------------- uninstalls

    @Test
    void anUninstallOfADirectCommitReleaseRemovesItFromMainThenFromTheClusterAndKdps() throws Exception {
        when(k8s.getClient()).thenReturn(client);
        backend().commit(directCommit(2), null, planned());
        assertNotNull(secretPayloads.get(VALUES_SECRET));

        backend().destroy(NS, RELEASE);

        assertNull(published("main", MANIFEST), "removed from the branch Flux reads");
        assertEquals(List.of("main"), branchesOnServer());
        assertTrue(pullRequests.opened.isEmpty());
        assertNull(recorded(), "the record is deleted");
        assertNull(secretPayloads.get(VALUES_SECRET));
        assertTrue(stored.isEmpty(), "the Git credential is deleted");
        ArgumentCaptor<CustomResourceDefinitionContext> deleted =
                ArgumentCaptor.forClass(CustomResourceDefinitionContext.class);
        verify(client, atLeastOnce()).genericKubernetesResources(deleted.capture());
        assertEquals(List.of("helm.toolkit.fluxcd.io/v2 helmreleases", "source.toolkit.fluxcd.io/v1 helmrepositories"),
                deleted.getAllValues().stream().map(c -> c.getGroup() + "/" + c.getVersion() + " " + c.getPlural())
                        .distinct().toList());
    }

    @Test
    void theHelmRepositoryAnotherGitOpsReleaseReadsIsKept() throws Exception {
        when(k8s.getClient()).thenReturn(client);
        backend().commit(directCommit(2), null, planned());
        K8sReleaseEntity other = new K8sReleaseEntity();
        other.setId(K8sReleaseEntity.idOf("team-b", "trino"));
        other.setNamespace("team-b");
        other.setReleaseName("trino");
        other.setDeploymentMode("FLUX_GITOPS");
        other.setRepoId("charts");
        releases.put(other.getId(), other);

        backend().destroy(NS, RELEASE);

        ArgumentCaptor<CustomResourceDefinitionContext> deleted =
                ArgumentCaptor.forClass(CustomResourceDefinitionContext.class);
        verify(client, atLeastOnce()).genericKubernetesResources(deleted.capture());
        assertTrue(deleted.getAllValues().stream().noneMatch(c -> "helmrepositories".equals(c.getPlural())),
                "team-b/trino still reads the HelmRepository");
    }

    @Test
    void anUninstallOfAPullRequestReleaseOpensARemovalPullRequestAndKeepsTheReleaseUntilItIsMerged() throws Exception {
        FluxGitOpsBackend.CommitResult deployed = backend().commit(pullRequest(2), null, planned());
        GitTestServers.squashMergeAndDelete(bareRepo, deployed.branch(), "main");
        pullRequests.merge("1");
        // Applied once merged: Flux reads the values Secret.
        secretPayloads.put(VALUES_SECRET, "global: {}");
        String main = GitTestServers.head(bareRepo, "main");

        backend().destroy(NS, RELEASE);

        FakePullRequests.Opened removal = pullRequests.last();
        assertEquals("2", removal.number());
        assertEquals("main", removal.base());
        assertTrue(removal.head().matches("flux-remove-[0-9a-f-]{36}"), removal.head());
        assertEquals("Flux GitOps (PR) delete " + RELEASE_DIR, removal.title());
        assertEquals(main, commitOnServer(GitTestServers.head(bareRepo, removal.head())).getParent(0).getName(),
                "started from main");
        assertNull(published(removal.head(), MANIFEST), "the removal is on its branch");
        assertNotNull(published("main", MANIFEST), "Flux keeps running the release until the merge");
        assertEquals(main, GitTestServers.head(bareRepo, "main"));
        K8sReleaseEntity record = recorded();
        assertNotNull(record, "the record waits for the merge");
        assertTrue(record.isGitPendingDelete());
        assertEquals(FakePullRequests.url("2"), record.getGitPrUrl());
        assertEquals("2", record.getGitPrNumber());
        assertEquals(removal.head(), record.getGitBranch());
        assertEquals(List.of(NS + "/" + RELEASE), record.getGitRemovedHelmReleases());
        assertNotNull(secretPayloads.get(VALUES_SECRET), "Flux still reads it");
        assertFalse(stored.isEmpty(), "the Git credential is kept");
        verify(k8s, never()).getClient();

        // Run again while the pull request is open: nothing new.
        backend().destroy(NS, RELEASE);
        assertEquals(2, pullRequests.opened.size());
        assertTrue(recorded().isGitPendingDelete());
        assertEquals("PENDING_DELETE", backend().status(NS, RELEASE).status);

        // Merged: the uninstall run once more finishes the removal.
        when(k8s.getClient()).thenReturn(client);
        GitTestServers.squashMergeAndDelete(bareRepo, removal.head(), "main");
        pullRequests.merge("2");
        backend().destroy(NS, RELEASE);

        assertNull(recorded());
        assertNull(secretPayloads.get(VALUES_SECRET));
        assertTrue(stored.isEmpty());
        assertEquals(2, pullRequests.opened.size());
        verify(client, atLeastOnce()).genericKubernetesResources(any(CustomResourceDefinitionContext.class));
    }

    @Test
    void aRemovalPullRequestClosedWithoutItsMergeIsProposedAgain() throws Exception {
        FluxGitOpsBackend.CommitResult deployed = backend().commit(pullRequest(2), null, planned());
        GitTestServers.squashMergeAndDelete(bareRepo, deployed.branch(), "main");
        pullRequests.merge("1");
        backend().destroy(NS, RELEASE);
        pullRequests.close("2");

        backend().destroy(NS, RELEASE);

        assertEquals("3", pullRequests.last().number());
        assertEquals("main", pullRequests.last().base());
        assertEquals("3", recorded().getGitPrNumber());
        assertTrue(recorded().isGitPendingDelete());
        assertNotNull(published("main", MANIFEST));
    }

    @Test
    void aDeployWhileTheRemovalPullRequestIsOpenIsRefusedAndOneAfterItIsClosedKeepsTheRelease() throws Exception {
        FluxGitOpsBackend.CommitResult deployed = backend().commit(pullRequest(2), null, planned());
        GitTestServers.squashMergeAndDelete(bareRepo, deployed.branch(), "main");
        pullRequests.merge("1");
        backend().destroy(NS, RELEASE);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> backend().commit(pullRequest(3), null, planned()));
        assertTrue(refused.getMessage().startsWith("The removal of analytics/superset is proposed in pull request "
                + FakePullRequests.url("2") + ", still open, so nothing was deployed"), refused.getMessage());

        pullRequests.close("2");
        FluxGitOpsBackend.CommitResult kept = backend().commit(pullRequest(3), null, planned());

        assertTrue(kept.committed());
        assertEquals("3", pullRequests.last().number());
        assertFalse(recorded().isGitPendingDelete(), "the release no longer waits for its removal");
        assertNull(recorded().getGitRemovedHelmReleases());
        assertEquals("3", recorded().getGitPrNumber());
    }

    @Test
    void aPullRequestReleaseNeverMergedIsRemovedAtOnceAndItsPullRequestClosed() throws Exception {
        backend().commit(pullRequest(2), null, planned());

        backend().destroy(NS, RELEASE);

        assertEquals(1, pullRequests.opened.size(), "main never held it: nothing to propose");
        assertEquals("closed", pullRequests.state("1"), "merged later, it would install the release again");
        assertEquals(List.of("1"), pullRequests.closedByKdps);
        assertNull(recorded());
        assertTrue(stored.isEmpty());
    }

    @Test
    void aPullRequestReleaseDeployedOverSshAndNeverMergedIsRemovedFromItsPullRequestBranch() throws Exception {
        // The token given next to the SSH key served the deploy's pull request only: none is left to close it with.
        HelmDeployRequest overSsh = pullRequest(2);
        overSsh.getGit().setSshKey("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\n");
        FluxGitOpsBackend.CommitResult deployed = backend().commit(overSsh, null, planned());
        assertNotNull(published(deployed.branch(), MANIFEST));

        backend().destroy(NS, RELEASE);

        assertNull(published(deployed.branch(), MANIFEST), "merged later, the pull request brings nothing back");
        assertNull(published("main", MANIFEST));
        assertEquals(seed, GitTestServers.head(bareRepo, "main"));
        assertTrue(pullRequests.closedByKdps.isEmpty());
        assertNull(recorded());
    }

    @Test
    void anUninstallClosesTheReleasesChangeStillOpenBeforeProposingItsRemoval() throws Exception {
        FluxGitOpsBackend.CommitResult deployed = backend().commit(pullRequest(2), null, planned());
        GitTestServers.squashMergeAndDelete(bareRepo, deployed.branch(), "main");
        pullRequests.merge("1");
        backend().commit(pullRequest(3), null, planned());
        assertEquals("open", pullRequests.state("2"));

        backend().destroy(NS, RELEASE);

        assertEquals("closed", pullRequests.state("2"), "merged after the removal, it would bring the release back");
        assertEquals(List.of("2"), pullRequests.closedByKdps);
        assertEquals("3", pullRequests.last().number());
        assertTrue(recorded().isGitPendingDelete());
        assertEquals("3", recorded().getGitPrNumber());
    }

    @Test
    void anUninstallWaitingForADeployClosesThePullRequestThatDeployOpened() throws Exception {
        readCopies();
        HeldPullRequests held = new HeldPullRequests();
        FluxGitOpsBackend backend = backend();
        backend.setPullRequests(held);
        FluxGitOpsBackend.CommitResult deployed = backend.commit(pullRequest(2), null, planned());
        GitTestServers.squashMergeAndDelete(bareRepo, deployed.branch(), "main");
        held.merge("1");

        // A security refresh pushes its change and is opening pull request #2 when the operator uninstalls.
        held.holdNextOpen();
        FutureTask<FluxGitOpsBackend.CommitResult> refresh = new FutureTask<>(
                () -> backend.commit(pullRequest(3), null, planned()));
        new Thread(refresh, "refresh").start();
        awaitOpening(held, refresh, "the refresh");
        FutureTask<Void> uninstall = new FutureTask<>(() -> { backend.destroy(NS, RELEASE); return null; });
        Thread uninstalling = new Thread(uninstall, "uninstall");
        uninstalling.start();
        awaitWaiting(uninstalling);
        held.release.countDown();
        refresh.get(30, TimeUnit.SECONDS);
        uninstall.get(30, TimeUnit.SECONDS);

        assertEquals("closed", held.state("2"), "merged after the removal, it would bring the release back");
        assertEquals(List.of("2"), held.closedByKdps);
        assertEquals("3", held.last().number());
        assertTrue(held.last().head().startsWith("flux-remove-"), held.last().head());
        assertTrue(recorded().isGitPendingDelete());
        assertEquals("3", recorded().getGitPrNumber());
    }

    @Test
    void aRedeployWaitingForAnotherJoinsThePullRequestThatOneOpened() throws Exception {
        readCopies();
        HeldPullRequests held = new HeldPullRequests();
        FluxGitOpsBackend backend = backend();
        backend.setPullRequests(held);
        FluxGitOpsBackend.CommitResult deployed = backend.commit(pullRequest(2), null, planned());
        GitTestServers.squashMergeAndDelete(bareRepo, deployed.branch(), "main");
        held.merge("1");

        // A resync is opening pull request #2 when an autoscaling redeploy of the same release starts.
        held.holdNextOpen();
        FutureTask<FluxGitOpsBackend.CommitResult> resync = new FutureTask<>(
                () -> backend.commit(pullRequest(3), null, planned()));
        new Thread(resync, "resync").start();
        awaitOpening(held, resync, "the resync");
        FutureTask<FluxGitOpsBackend.CommitResult> autoscaling = new FutureTask<>(
                () -> backend.commit(pullRequest(4), null, planned()));
        Thread redeploying = new Thread(autoscaling, "autoscaling");
        redeploying.start();
        awaitWaiting(redeploying);
        held.release.countDown();
        FluxGitOpsBackend.CommitResult first = resync.get(30, TimeUnit.SECONDS);
        FluxGitOpsBackend.CommitResult second = autoscaling.get(30, TimeUnit.SECONDS);

        assertEquals(2, held.opened.size(), "no pull request of its own, left open behind the recorded one");
        assertEquals(first.branch(), second.branch(), "joined the open pull request");
        assertEquals("open", held.state("2"));
        assertEquals("2", recorded().getGitPrNumber());
        assertTrue(published(first.branch(), MANIFEST).contains("replicaCount: 4"));
    }

    @Test
    void anOldPullRequestReleaseOnARepositoryWhoseOnlyBranchIsMasterKeepsEverythingOnUninstall() throws Exception {
        onlyMasterOnServer();
        HelmDeployRequest request = pullRequest(2);
        request.getGit().setBaseBranch("master");
        FluxGitOpsBackend.CommitResult deployed = backend().commit(request, null, planned());
        GitTestServers.squashMergeAndDelete(bareRepo, deployed.branch(), "master");
        pullRequests.merge("1");
        // As an earlier version recorded it: without its base branch, which KDPS then takes for main.
        recorded().setGitBaseBranch(null);
        Resource<GenericKubernetesResource> running = helmReleaseOnCluster(true);
        Map<String, String> secretsBefore = Map.copyOf(secretPayloads);
        String master = GitTestServers.head(bareRepo, "master");

        RuntimeException refused = assertThrows(RuntimeException.class, () -> backend().destroy(NS, RELEASE));

        assertTrue(refused.getMessage().contains("Branch main is not on " + repoUrl + ", and the HelmRelease of "
                + NS + "/" + RELEASE + " is still on the cluster (or KDPS could not check it), so nothing was removed:"
                + " Flux may reconcile the release from another branch. Deploy the release again once, naming the"
                + " branch Flux reconciles as its base branch, then uninstall it."), refused.getMessage());
        assertEquals(List.of("master"), branchesOnServer(), "no main created");
        assertEquals(master, GitTestServers.head(bareRepo, "master"));
        assertEquals(secretsBefore, secretPayloads, "Flux still reads the values Secrets");
        assertNotNull(recorded(), "the record is kept");
        assertFalse(recorded().isGitPendingDelete());
        assertFalse(stored.isEmpty(), "the Git credential is kept");
        verify(running, never()).delete();

        // As the message says: deployed again naming the branch Flux reconciles, its removal is proposed there.
        HelmDeployRequest named = pullRequest(2);
        named.getGit().setBaseBranch("master");
        assertEquals("NO_CHANGES", backend().commit(named, null, planned()).id());
        assertEquals("master", recorded().getGitBaseBranch());
        backend().destroy(NS, RELEASE);
        assertEquals("master", pullRequests.last().base());
        assertTrue(recorded().isGitPendingDelete());
    }

    @Test
    void anOldPullRequestReleaseThatMainDoesNotHoldWhileFluxRunsItKeepsEverythingOnUninstall() throws Exception {
        GitTestServers.createBranch(bareRepo, "production", "main");
        HelmDeployRequest request = pullRequest(2);
        request.getGit().setBaseBranch("production");
        FluxGitOpsBackend.CommitResult deployed = backend().commit(request, null, planned());
        GitTestServers.squashMergeAndDelete(bareRepo, deployed.branch(), "production");
        pullRequests.merge("1");
        recorded().setGitBaseBranch(null);
        Resource<GenericKubernetesResource> running = helmReleaseOnCluster(true);
        Map<String, String> secretsBefore = Map.copyOf(secretPayloads);

        RuntimeException refused = assertThrows(RuntimeException.class, () -> backend().destroy(NS, RELEASE));

        assertTrue(refused.getMessage().contains("Branch main, which KDPS takes for the base branch of " + NS + "/"
                + RELEASE + " because none is recorded for it, does not hold the release, and the HelmRelease of "
                + NS + "/" + RELEASE + " is still on the cluster"), refused.getMessage());
        assertNotNull(published("production", MANIFEST));
        assertEquals(secretsBefore, secretPayloads);
        assertNotNull(recorded());
        assertFalse(stored.isEmpty());
        assertEquals(1, pullRequests.opened.size());
        verify(running, never()).delete();
    }

    @Test
    void aRedeployNamingNoBaseBranchNeverRecordsTheAssumedMainForAnOldPullRequestRelease() throws Exception {
        GitTestServers.createBranch(bareRepo, "production", "main");
        HelmDeployRequest request = pullRequest(2);
        request.getGit().setBaseBranch("production");
        FluxGitOpsBackend.CommitResult deployed = backend().commit(request, null, planned());
        GitTestServers.squashMergeAndDelete(bareRepo, deployed.branch(), "production");
        pullRequests.merge("1");
        recorded().setGitBaseBranch(null);
        // A security refresh from the Releases page, which sends no base branch for a release that records none.
        backend().commit(pullRequest(3), null, planned());
        assertEquals("main", pullRequests.last().base());
        assertNull(recorded().getGitBaseBranch(), "main is only assumed");
        Resource<GenericKubernetesResource> running = helmReleaseOnCluster(true);
        Map<String, String> secretsBefore = Map.copyOf(secretPayloads);

        RuntimeException refused = assertThrows(RuntimeException.class, () -> backend().destroy(NS, RELEASE));

        assertTrue(refused.getMessage().contains("Branch main, which KDPS takes for the base branch of " + NS + "/"
                + RELEASE + " because none is recorded for it, does not hold the release"), refused.getMessage());
        assertNotNull(published("production", MANIFEST));
        assertEquals(secretsBefore, secretPayloads, "Flux still reads the values Secrets");
        assertNotNull(recorded());
        assertFalse(stored.isEmpty(), "the Git credential is kept");
        verify(running, never()).delete();
    }

    @Test
    void anOldPullRequestReleaseThatNeverReachedFluxIsRemovedAndItsPullRequestClosed() throws Exception {
        backend().commit(pullRequest(2), null, planned());
        recorded().setGitBaseBranch(null);
        helmReleaseOnCluster(false);

        backend().destroy(NS, RELEASE);

        assertEquals("closed", pullRequests.state("1"));
        assertNull(recorded());
        assertTrue(stored.isEmpty());
    }

    @Test
    void aPullRequestReleaseWhoseBaseBranchWasDeletedOnTheServerKeepsEverythingOnUninstall() throws Exception {
        GitTestServers.createBranch(bareRepo, "production", "main");
        HelmDeployRequest request = pullRequest(2);
        request.getGit().setBaseBranch("production");
        FluxGitOpsBackend.CommitResult deployed = backend().commit(request, null, planned());
        GitTestServers.squashMergeAndDelete(bareRepo, deployed.branch(), "production");
        pullRequests.merge("1");
        GitTestServers.deleteBranch(bareRepo, "production");
        Resource<GenericKubernetesResource> running = helmReleaseOnCluster(true);
        Map<String, String> secretsBefore = Map.copyOf(secretPayloads);

        RuntimeException refused = assertThrows(RuntimeException.class, () -> backend().destroy(NS, RELEASE));

        assertTrue(refused.getMessage().contains("Branch production is not on " + repoUrl + ", and the HelmRelease of "
                + NS + "/" + RELEASE + " is still on the cluster"), refused.getMessage());
        assertEquals(List.of("main"), branchesOnServer(), "no production recreated");
        assertEquals(seed, GitTestServers.head(bareRepo, "main"));
        assertEquals(secretsBefore, secretPayloads);
        assertNotNull(recorded());
        assertFalse(recorded().isGitPendingDelete());
        assertFalse(stored.isEmpty());
        assertEquals(1, pullRequests.opened.size());
        verify(running, never()).delete();
    }

    @Test
    void aDirectCommitReleaseWhoseBaseBranchWasDeletedOnTheServerKeepsEverythingOnUninstall() throws Exception {
        GitTestServers.createBranch(bareRepo, "production", "main");
        HelmDeployRequest request = directCommit(2);
        request.getGit().setBaseBranch("production");
        backend().commit(request, null, planned());
        GitTestServers.deleteBranch(bareRepo, "production");
        Resource<GenericKubernetesResource> running = helmReleaseOnCluster(true);

        RuntimeException refused = assertThrows(RuntimeException.class, () -> backend().destroy(NS, RELEASE));

        assertTrue(refused.getMessage().contains("Branch production is not on " + repoUrl + ", and the HelmRelease of "
                + NS + "/" + RELEASE + " is still on the cluster"), refused.getMessage());
        assertEquals(List.of("main"), branchesOnServer(), "no production recreated");
        assertNotNull(secretPayloads.get(VALUES_SECRET), "Flux still reads the values Secret");
        assertNotNull(recorded());
        assertFalse(stored.isEmpty());
        verify(running, never()).delete();
    }

    @Test
    void aRedeployWithNothingNewAfterTheRemovalPullRequestIsClosedEndsTheWaitForTheRemoval() throws Exception {
        FluxGitOpsBackend.CommitResult deployed = backend().commit(pullRequest(2), null, planned());
        GitTestServers.squashMergeAndDelete(bareRepo, deployed.branch(), "main");
        pullRequests.merge("1");
        backend().destroy(NS, RELEASE);
        pullRequests.close("2");

        // Closed without its merge: the release no longer shows as waiting for its removal.
        org.apache.ambari.view.k8s.model.HelmReleaseDTO closed = backend().status(NS, RELEASE);
        assertNotEquals("PENDING_DELETE", closed.status);
        assertEquals(Boolean.FALSE, closed.gitPendingDelete);
        assertTrue(closed.message.startsWith("The pull request " + FakePullRequests.url("2") + " removing the release"
                + " was closed without being merged into main: the release keeps running. Uninstall it again to"
                + " propose its removal once more."), closed.message);

        // Deployed again as the refusal of a deploy during the removal advises, with nothing new.
        FluxGitOpsBackend.CommitResult kept = backend().commit(pullRequest(2), null, planned());

        assertEquals("NO_CHANGES", kept.id());
        assertEquals(2, pullRequests.opened.size(), "nothing to propose");
        K8sReleaseEntity record = recorded();
        assertFalse(record.isGitPendingDelete(), "the release no longer waits for its removal");
        assertNull(record.getGitRemovedHelmReleases());
        assertNull(record.getGitPrNumber(), "the closed removal is not the release's pull request");
        assertEquals("main", record.getGitBranch());
        assertEquals("PR_MODE", record.getGitCommitMode());
        org.apache.ambari.view.k8s.model.HelmReleaseDTO status = backend().status(NS, RELEASE);
        assertNotEquals("PENDING_DELETE", status.status);
        assertNotEquals(Boolean.TRUE, status.gitPendingDelete);
    }

    @Test
    void anUninstallThroughAPullRequestNeedsTheAccessTokenOfTheGitServer() throws Exception {
        // Deployed over SSH: the token given next to the SSH key served the deploy's pull request only.
        HelmDeployRequest overSsh = pullRequest(2);
        overSsh.getGit().setSshKey("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\n");
        FluxGitOpsBackend.CommitResult deployed = backend().commit(overSsh, null, planned());
        GitTestServers.squashMergeAndDelete(bareRepo, deployed.branch(), "main");
        String main = GitTestServers.head(bareRepo, "main");

        RuntimeException failed = assertThrows(RuntimeException.class, () -> backend().destroy(NS, RELEASE));

        assertTrue(failed.getMessage().contains("KDPS removes analytics/superset through a pull request into main, which"
                + " it opens with the access token of the Git server, and none is saved for the release"),
                failed.getMessage());
        assertTrue(failed.getMessage().contains("Remove " + RELEASE_DIR + " from main by hand"), failed.getMessage());
        assertEquals(main, GitTestServers.head(bareRepo, "main"));
        assertNotNull(recorded());
        assertFalse(recorded().isGitPendingDelete());

        // Removed by hand: the next uninstall finishes the removal.
        Path byHand = Files.createTempDirectory(tmp, "by-hand-");
        try (Git g = Git.cloneRepository().setURI(repoUrl).setBranch("main").setDirectory(byHand.toFile()).call()) {
            g.rm().addFilepattern(RELEASE_DIR).call();
            g.commit().setMessage("remove superset").setAuthor("ops", "ops@example.com").call();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
        }
        backend().destroy(NS, RELEASE);
        assertNull(recorded());
    }

    @Test
    void theStatusOfAReleaseWaitingForItsRemovalSaysWhatFinishesIt() throws Exception {
        FluxGitOpsBackend.CommitResult deployed = backend().commit(pullRequest(2), null, planned());
        GitTestServers.squashMergeAndDelete(bareRepo, deployed.branch(), "main");
        pullRequests.merge("1");
        backend().destroy(NS, RELEASE);

        org.apache.ambari.view.k8s.model.HelmReleaseDTO waiting = backend().status(NS, RELEASE);
        assertEquals("PENDING_DELETE", waiting.status);
        assertEquals(Boolean.TRUE, waiting.gitPendingDelete);
        assertTrue(waiting.message.startsWith("The removal of the release is proposed in pull request "
                + FakePullRequests.url("2") + " into main: the release keeps running until it is merged."),
                waiting.message);

        pullRequests.merge("2");
        org.apache.ambari.view.k8s.model.HelmReleaseDTO merged = backend().status(NS, RELEASE);
        assertEquals("PENDING_DELETE", merged.status);
        assertTrue(merged.message.startsWith("The pull request " + FakePullRequests.url("2") + " removing the release"
                + " is merged into main: uninstall the release again to finish removing it from the cluster and KDPS."),
                merged.message);
    }

    @Test
    void aWorkspaceBranchLeftFromAnEarlierChangeNeverLeaksIntoANewPullRequest() throws Exception {
        HelmDeployRequest first = pullRequest(2);
        first.getGit().setBranch("flux-a");
        FluxGitOpsBackend.CommitResult onA = backend().commit(first, null, planned());
        HelmDeployRequest second = pullRequest(3);
        second.getGit().setBranch("flux-b");
        backend().commit(second, null, planned());
        // The first pull request is closed and its branch deleted on the server; the workspace still has flux-a.
        GitTestServers.deleteBranch(bareRepo, "flux-a");
        pullRequests.close("1");
        HelmDeployRequest again = pullRequest(4);
        again.getGit().setBranch("flux-a");

        FluxGitOpsBackend.CommitResult proposed = backend().commit(again, null, planned());

        assertEquals("flux-a", proposed.branch());
        assertEquals(seed, commitOnServer(proposed.commitSha()).getParent(0).getName(),
                "started from main, without the closed pull request's commit " + onA.commitSha());
        assertEquals(new FakePullRequests.Opened("3", "main", "flux-a", "Flux GitOps (PR) apply " + NS + "/" + RELEASE,
                TOKEN), pullRequests.last());
    }
}
