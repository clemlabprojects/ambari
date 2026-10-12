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
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.GitCredentialService;
import org.apache.ambari.view.k8s.service.HelmSetValues;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.utils.CommandUtils;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The GitOps commit of a planned deploy, against a local bare repository: the planned overrides are what is committed
 * (passwords and secrets going to the values Secret), the backend does not redo what the plan prepared, a commit that
 * changes nothing is not repeated, and a change merged since it was committed counts as applied. Also how a
 * HelmRelease on the cluster is read for the version a deploy committed.
 */
class FluxGitOpsBackendPlannedCommitTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "superset";
    private static final String RELEASE_DIR = "clusters/default/analytics/superset";
    private static final String BIND_PASSWORD = "Bind-Pass-42";

    @TempDir
    Path tmp;

    private ViewContext ctx;
    private KubernetesService k8s;
    private Path bareRepo;
    private String repoUrl;
    private final Map<String, String> secretPayloads = new ConcurrentHashMap<>();
    private final Map<String, K8sReleaseEntity> releases = new ConcurrentHashMap<>();

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
            g.commit().setMessage("init").setAuthor("seed", "seed@example.com").call();
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
        when(ds.find(eq(HelmRepoEntity.class), eq("charts"))).thenReturn(charts);
        doAnswer(inv -> {
            K8sReleaseEntity entity = inv.getArgument(0);
            releases.put(entity.getId(), entity);
            return null;
        }).when(ds).store(any(K8sReleaseEntity.class));
        when(ds.find(eq(K8sReleaseEntity.class), anyString())).thenAnswer(inv -> releases.get((String) inv.getArgument(1)));

        k8s = mock(KubernetesService.class);
        when(k8s.readOpaqueSecretKeyAsString(anyString(), anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(secretPayloads.get(inv.getArgument(0) + "/" + inv.getArgument(1))));
        doAnswer(inv -> {
            secretPayloads.put(inv.getArgument(0) + "/" + inv.getArgument(1),
                    new String((byte[]) inv.getArgument(3), StandardCharsets.UTF_8));
            return null;
        }).when(k8s).createOrUpdateOpaqueSecret(anyString(), anyString(), anyString(), any(byte[].class), anyMap(), any(), any());
    }

    private FluxGitOpsBackend backend() {
        return new FluxGitOpsBackend(tmp.resolve("flux"), k8s, ctx);
    }

    /** A Superset deploy as its GITOPS_COMMIT step rebuilds it, committed to {@code branch}, Flux reading main. */
    private HelmDeployRequest request(String branch) {
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
        git.setBranch(branch);
        git.setCommitMode("DIRECT_COMMIT");
        request.setGit(git);
        return request;
    }

    /** What the command plan of a Kerberized Superset with LDAP login and a self-signed ingress certificate prepared. */
    private static FluxGitOpsBackend.PlannedRelease planned() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("global.security.kerberos.enabled", "true");
        overrides.put("global.security.kerberos.configMapName", "superset-krb5-conf");
        overrides.put("global.security.kerberos.injectionMode", "PRE_PROVISIONED");
        overrides.put("global.security.kerberos.keytab.secretName", "superset-keytab");
        overrides.put("global.security.kerberos.realm", "EXAMPLE.ORG");
        overrides.put("global.security.auth.mode", "oidc");
        overrides.put("global.security.auth.oidc.secretRef.name", "superset-oidc-client");
        overrides.put("global.security.auth.ldap.bindPassword", HelmSetValues.asText(BIND_PASSWORD));
        overrides.put("ingress.tls[0].secretName", "superset-ingress-tls");
        overrides.put("ingress.tls[0].hosts[0]", "superset.example.org");
        overrides.put("global.imageRegistry", "registry.example.org");
        return new FluxGitOpsBackend.PlannedRelease(overrides, Boolean.TRUE);
    }

    /** A file as published on a branch of the remote, or null. */
    private String published(String branch, String path) throws Exception {
        Path clone = tmp.resolve("verify-" + System.nanoTime());
        try (Git ignored = Git.cloneRepository().setURI(repoUrl).setBranch(branch).setDirectory(clone.toFile()).call()) {
            Path file = clone.resolve(path);
            return Files.exists(file) ? Files.readString(file) : null;
        }
    }

    private static String digestOf(String manifest) {
        Matcher m = Pattern.compile(Pattern.quote(FluxGitOpsBackend.SPEC_DIGEST_ANNOTATION) + ": \"([0-9a-f]+)\"")
                .matcher(manifest);
        assertTrue(m.find(), manifest);
        return m.group(1);
    }

    @Test
    void aPlannedCommitWritesWhatTheHelmInstallWouldReceiveAndKeepsPasswordsOutOfGit() throws Exception {
        FluxGitOpsBackend.CommitResult result = backend().commit(request("main"), null, planned());

        assertTrue(result.committed());
        assertTrue(result.appliedOnCommit(), "a direct commit to the branch Flux reads is applied as pushed");
        String helmRelease = published("main", RELEASE_DIR + "/helmrelease.yaml");
        for (String expected : List.of("injectionMode: \"PRE_PROVISIONED\"", "secretName: \"superset-keytab\"",
                "realm: \"EXAMPLE.ORG\"", "mode: \"oidc\"", "name: \"superset-oidc-client\"",
                "configMapName: \"superset-krb5-conf\"", "imageRegistry: \"registry.example.org\"", "replicaCount: 2\n",
                "tls:\n      - secretName: \"superset-ingress-tls\"\n        hosts:\n        - \"superset.example.org\"")) {
            assertTrue(helmRelease.contains(expected), expected + " in\n" + helmRelease);
        }
        // Only what the plan passed: none of the settings a direct deploy does not give Helm either.
        assertFalse(helmRelease.contains("GLOBAL_DOMAIN"), helmRelease);
        assertFalse(helmRelease.contains(BIND_PASSWORD), "no password in Git");
        assertTrue(helmRelease.contains("    name: superset-kdps-values\n"), helmRelease);
        assertTrue(secretPayloads.get(NS + "/superset-kdps-values").contains(BIND_PASSWORD));
        assertEquals(digestOf(helmRelease), result.specDigest());
    }

    @Test
    void fluxGivesHelmTheTimeOfADirectDeploy() throws Exception {
        HelmDeployRequest request = request("main");
        request.setDependencies(new LinkedHashMap<>(Map.of("keda",
                new LinkedHashMap<>(Map.of("chart", "keda", "chartVersion", "2.18.0", "namespace", "keda")))));

        backend().commit(request, null, planned());

        // 900 seconds, as HelmService gives a direct install; Flux's default of 5 minutes fails slow first starts.
        assertTrue(published("main", RELEASE_DIR + "/helmrelease.yaml").contains("spec:\n  interval: 5m\n  timeout: 15m\n"));
        assertTrue(published("main", RELEASE_DIR + "/dependencies/keda-helmrelease.yaml")
                .contains("spec:\n  interval: 5m\n  timeout: 15m\n"));
    }

    @Test
    void aPlannedDependencyGetsWhatItsHelmInstallWouldReceiveAndOnlyOneThatIsWritten() throws Exception {
        HelmDeployRequest request = request("main");
        request.setSecretName("superset-pull");
        LinkedHashMap<String, Object> dependencies = new LinkedHashMap<>();
        dependencies.put("keda", new LinkedHashMap<>(Map.of("chart", "keda", "chartVersion", "2.18.0", "namespace", "keda")));
        // Not used in the default PRE_PROVISIONED Kerberos injection mode.
        dependencies.put("kerberos-keytab-mutating-webhook", new LinkedHashMap<>(Map.of(
                "chart", "kerberos-keytab-mutating-webhook", "namespace", "ambari-mutating-webhooks", "isWebhook", true)));
        request.setDependencies(dependencies);
        List<String> prepared = new java.util.ArrayList<>();
        FluxGitOpsBackend.PlannedRelease planned = new FluxGitOpsBackend.PlannedRelease(planned().overrides(), Boolean.TRUE,
                (name, namespace) -> {
                    prepared.add(name + "@" + namespace);
                    return Map.of("global.image.registry", "registry.example.org", "imagePullSecrets[0].name", "regcred");
                });

        backend().commit(request, null, planned);

        assertEquals(List.of("keda@keda"), prepared);
        String keda = published("main", RELEASE_DIR + "/dependencies/keda-helmrelease.yaml");
        assertTrue(keda.contains("    global:\n      image:\n        registry: \"registry.example.org\"\n"), keda);
        assertTrue(keda.contains("    imagePullSecrets:\n    - name: \"regcred\"\n"), keda);
        assertFalse(keda.contains("superset-pull"), "the release's pull Secret does not exist in the keda namespace: " + keda);
        assertNull(published("main", RELEASE_DIR + "/dependencies/kerberos-keytab-mutating-webhook-helmrelease.yaml"));
    }

    @Test
    void aPlannedDependencyThatCannotBePreparedStopsTheCommit() throws Exception {
        HelmDeployRequest request = request("main");
        request.setDependencies(new LinkedHashMap<>(Map.of("keda",
                new LinkedHashMap<>(Map.of("chart", "keda", "namespace", "keda")))));
        FluxGitOpsBackend.PlannedRelease planned = new FluxGitOpsBackend.PlannedRelease(planned().overrides(), Boolean.TRUE,
                (name, namespace) -> {
                    throw new IllegalStateException("secrets is forbidden");
                });

        IllegalStateException stopped = assertThrows(IllegalStateException.class,
                () -> backend().commit(request, null, planned));

        assertEquals("GitOps deploy of analytics/superset stopped: dependency keda could not be prepared in namespace "
                + "keda, so nothing was committed to Git: secrets is forbidden", stopped.getMessage());
        assertNull(published("main", RELEASE_DIR + "/helmrelease.yaml"));
    }

    @Test
    void aPlannedCommitLeavesKerberosTheTruststoreAndTheImagePullSecretToThePlan() throws Exception {
        CommandUtils commandUtils = mock(CommandUtils.class);
        FluxGitOpsBackend backend = backend();
        Field f = FluxGitOpsBackend.class.getDeclaredField("commandUtils");
        f.setAccessible(true);
        f.set(backend, commandUtils);
        HelmDeployRequest request = request("main");
        request.setSecretName("regcred");
        request.setMounts(Map.of("data", Map.of("size", "1Gi")));

        backend.commit(request, null, planned());

        // Neither asked of Ambari nor prepared again: the plan did it, the way a direct deploy does.
        verify(commandUtils, never()).resolveClusterName(any(), any());
        verify(commandUtils, never()).ensureKrb5ConfConfigMap(anyString(), anyString());
        verify(k8s, never()).createMounts(anyString(), anyString(), anyMap());
        String helmRelease = published("main", RELEASE_DIR + "/helmrelease.yaml");
        assertFalse(helmRelease.contains("regcred"), "the plan's overrides name the pull Secret, not the backend: "
                + helmRelease);
    }

    @Test
    void theInjectionModeIsWrittenEvenWithoutAPlan() throws Exception {
        backend().apply(request("main"), null);

        String helmRelease = published("main", RELEASE_DIR + "/helmrelease.yaml");
        assertTrue(helmRelease.contains("    global:\n      security:\n"), helmRelease);
        assertTrue(helmRelease.contains("        kerberos:\n          injectionMode: \"PRE_PROVISIONED\"\n"), helmRelease);
    }

    @Test
    void theRangerSpecAndHadoopConfigMapsOfTheRequestAreNotWrittenAsTheyCome() throws Exception {
        HelmDeployRequest request = request("main");
        Map<String, Map<String, Object>> ranger = new LinkedHashMap<>();
        ranger.put("ranger-plugin-settings", new LinkedHashMap<>(Map.of("type", "ranger-plugin-settings")));
        request.setRanger(ranger);
        request.setRequiredConfigMaps(List.of(new LinkedHashMap<>(Map.of("cm_name", "superset-hadoop-conf",
                "cm_type", "config-map", "config_properties", List.of("core-site")))));

        backend().commit(request, null, planned());

        String helmRelease = published("main", RELEASE_DIR + "/helmrelease.yaml");
        assertFalse(helmRelease.contains("ranger"), helmRelease);
        verify(k8s, never()).createOrUpdateConfigMap(anyString(), anyString(), anyMap(), anyMap(), anyMap());
    }

    @Test
    void committingTheSameReleaseAgainCommitsNothing() throws Exception {
        FluxGitOpsBackend.CommitResult first = backend().commit(request("main"), null, planned());

        FluxGitOpsBackend.CommitResult again = backend().commit(request("main"), null, planned());

        assertEquals("NO_CHANGES", again.id());
        assertFalse(again.committed());
        assertEquals(first.commitSha(), again.commitSha(), "Git still holds the first commit");
        assertEquals(first.specDigest(), again.specDigest(), "the digest depends on the content only");
        assertTrue(again.appliedOnCommit());
    }

    @Test
    void aChangeMergedSinceItWasCommittedIsAppliedOnTheNextDeploy() throws Exception {
        FluxGitOpsBackend.CommitResult proposed = backend().commit(request("flux-work"), null, planned());
        assertTrue(proposed.committed());
        assertFalse(proposed.appliedOnCommit(), "a working branch waits to be merged into main");
        assertEquals("main", proposed.baseBranch());

        // The operator merges the working branch into main.
        Path merger = tmp.resolve("merger");
        try (Git g = Git.cloneRepository().setURI(repoUrl).setDirectory(merger.toFile()).call()) {
            g.merge().include(g.getRepository().resolve("refs/remotes/origin/flux-work")).call();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
        }

        FluxGitOpsBackend.CommitResult redeployed = backend().commit(request("flux-work"), null, planned());

        assertFalse(redeployed.committed());
        assertTrue(redeployed.appliedOnCommit(), "main now holds the release as planned");
    }

    @Test
    void whetherADeployCanBeCommittedIsCheckedWithoutGitAndWithoutChangingTheRequest() throws Exception {
        HelmDeployRequest request = request("flux-work");
        request.getGit().setBaseBranch(null);

        backend().checkCommittable(request, null);

        assertNull(request.getGit().getBaseBranch(), "the base branch a redeploy names (none) is kept as given");
        if (Files.exists(tmp.resolve("flux"))) {
            try (java.util.stream.Stream<Path> workspaces = Files.list(tmp.resolve("flux"))) {
                assertFalse(workspaces.findAny().isPresent(), "no Git workspace was created");
            }
        }

        HelmDeployRequest unknownRepository = request("main");
        unknownRepository.setRepoId("mirror");
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> backend().checkCommittable(unknownRepository, null));
        assertTrue(refused.getMessage().startsWith("Chart repository 'mirror' is not configured in KDPS"), refused.getMessage());

        HelmDeployRequest pullRequestWithoutToken = request("main");
        pullRequestWithoutToken.getGit().setRepoUrl("https://git.example.org/platform/gitops.git");
        pullRequestWithoutToken.getGit().setCommitMode("PR_MODE");
        pullRequestWithoutToken.getGit().setSshKey("-----BEGIN OPENSSH PRIVATE KEY-----");
        assertEquals("PR mode requires an HTTPS token", assertThrows(IllegalArgumentException.class,
                () -> backend().checkCommittable(pullRequestWithoutToken, null)).getMessage());
    }

    // ---------------------------------------------------------------- reading the HelmRelease on the cluster

    private static GenericKubernetesResource helmRelease(String digest, long generation, Map<String, Object> status,
                                                         Map<String, String> moreAnnotations) {
        Map<String, String> annotations = new LinkedHashMap<>(moreAnnotations);
        if (digest != null) annotations.put(FluxGitOpsBackend.SPEC_DIGEST_ANNOTATION, digest);
        GenericKubernetesResource hr = new GenericKubernetesResource();
        hr.setMetadata(new ObjectMetaBuilder().withName(RELEASE).withNamespace(NS).withGeneration(generation)
                .withAnnotations(annotations).build());
        hr.setAdditionalProperty("status", status);
        return hr;
    }

    /** A condition as Flux sets it: with the generation of the HelmRelease it was set for. */
    private static Map<String, Object> condition(String type, String status, String reason, String message,
                                                 long forGeneration) {
        return Map.of("type", type, "status", status, "reason", reason, "message", message,
                "observedGeneration", forGeneration);
    }

    /**
     * The status Flux writes. {@code status.observedGeneration} moves only once the release is Ready or Stalled;
     * {@code null} leaves it out, as on a release Flux has never brought to either.
     */
    private static Map<String, Object> status(Long observedGeneration, Map<?, ?>... conditions) {
        Map<String, Object> status = new LinkedHashMap<>();
        if (observedGeneration != null) status.put("observedGeneration", observedGeneration);
        status.put("conditions", List.of(conditions));
        return status;
    }

    private static final String INSTALL_ERROR = "Helm install failed for release analytics/superset with chart "
            + "superset@0.12.43: execution error at (superset/templates/deployment.yaml:12): secret is required";

    @Test
    void aHelmReleaseNotOnTheClusterYetIsWaitedFor() {
        FluxGitOpsBackend.Readiness readiness = FluxGitOpsBackend.readiness(null, "abc");

        assertFalse(readiness.ready());
        assertFalse(readiness.failed());
        assertFalse(readiness.applied());
        assertTrue(readiness.message().contains("not on the cluster yet"), readiness.message());
    }

    @Test
    void thePreviousVersionReadyIsNotTakenForTheCommittedOne() {
        GenericKubernetesResource previous = helmRelease("old", 3,
                status(3L, condition("Ready", "True", "UpgradeSucceeded", "Helm upgrade succeeded", 3)), Map.of());

        FluxGitOpsBackend.Readiness readiness = FluxGitOpsBackend.readiness(previous, "new");

        assertFalse(readiness.ready());
        assertFalse(readiness.applied());
        assertTrue(readiness.message().contains("still the previous version"), readiness.message());
    }

    @Test
    void aChangeFluxHasNotReconciledYetIsWaitedFor() {
        GenericKubernetesResource applied = helmRelease("new", 4,
                status(3L, condition("Ready", "True", "UpgradeSucceeded", "Helm upgrade succeeded", 3)), Map.of());

        FluxGitOpsBackend.Readiness readiness = FluxGitOpsBackend.readiness(applied, "new");

        assertFalse(readiness.ready());
        assertTrue(readiness.applied());
        assertEquals("Flux has not reconciled the latest change of the HelmRelease yet", readiness.message());
    }

    @Test
    void whatFluxReportsOfANewReleaseItIsStillWorkingOnIsShown() {
        // Not Ready nor Stalled yet: Flux has not set status.observedGeneration, only its conditions say where it is.
        FluxGitOpsBackend.Readiness installing = FluxGitOpsBackend.readiness(helmRelease("new", 1, status(null,
                condition("Ready", "Unknown", "Progressing", "Running 'install' action with timeout of 15m0s", 1),
                condition("Reconciling", "True", "Progressing", "Running 'install' action with timeout of 15m0s", 1)),
                Map.of()), "new");
        assertFalse(installing.ready());
        assertFalse(installing.failed());
        assertEquals("Progressing: Running 'install' action with timeout of 15m0s", installing.message());

        FluxGitOpsBackend.Readiness noChart = FluxGitOpsBackend.readiness(helmRelease("new", 1, status(null,
                condition("Ready", "False", "ArtifactFailed", "HelmChart 'flux-system/analytics-superset' is not ready", 1)),
                Map.of()), "new");
        assertFalse(noChart.ready());
        assertFalse(noChart.failed(), "Flux keeps trying");
        assertEquals("ArtifactFailed: HelmChart 'flux-system/analytics-superset' is not ready", noChart.message());
    }

    @Test
    void aReconcileRequestedForNewSecretsIsWaitedFor() {
        Map<String, Object> handledBefore = status(4L,
                condition("Ready", "True", "UpgradeSucceeded", "Helm upgrade succeeded", 4));
        handledBefore.put("lastHandledReconcileAt", "2026-10-12T08:00:00Z");
        GenericKubernetesResource requested = helmRelease("new", 4, handledBefore,
                Map.of("reconcile.fluxcd.io/requestedAt", "2026-10-12T09:00:00Z"));

        assertFalse(FluxGitOpsBackend.readiness(requested, "new").ready());

        handledBefore.put("lastHandledReconcileAt", "2026-10-12T09:00:00Z");
        assertTrue(FluxGitOpsBackend.readiness(requested, "new").ready());
    }

    @Test
    void theCommittedVersionReadyIsReady() {
        GenericKubernetesResource ready = helmRelease("new", 4, status(4L, condition("Ready", "True",
                "InstallSucceeded", "Helm install succeeded for release analytics/superset", 4)), Map.of());

        FluxGitOpsBackend.Readiness readiness = FluxGitOpsBackend.readiness(ready, "new");

        assertTrue(readiness.ready());
        assertEquals("InstallSucceeded: Helm install succeeded for release analytics/superset", readiness.message());
    }

    @Test
    void aFailingInstallIsWaitedForWithFluxsMessageUntilFluxGivesUpWithItsCause() {
        // Retrying: neither Ready nor Stalled, so status.observedGeneration is still that of the previous version.
        Map<?, ?> notReady = condition("Ready", "False", "InstallFailed", INSTALL_ERROR, 4);
        FluxGitOpsBackend.Readiness retrying = FluxGitOpsBackend.readiness(
                helmRelease("new", 4, status(3L, notReady), Map.of()), "new");
        assertFalse(retrying.ready());
        assertFalse(retrying.failed(), "Flux retries the install");
        assertEquals("InstallFailed: " + INSTALL_ERROR, retrying.message());

        FluxGitOpsBackend.Readiness stalled = FluxGitOpsBackend.readiness(helmRelease("new", 4, status(4L, notReady,
                condition("Stalled", "True", "RetriesExceeded", "Failed to install after 1 attempt(s)", 4)), Map.of()), "new");
        assertTrue(stalled.failed());
        assertEquals("InstallFailed: " + INSTALL_ERROR + " (RetriesExceeded: Failed to install after 1 attempt(s))",
                stalled.message(), "what failed, and that Flux gave up");
    }

    @Test
    void aStalledReleaseIsReadAgainOnlyOnceFluxHandledTheResetAskedFor() {
        Map<String, Object> gaveUp = status(4L,
                condition("Ready", "False", "InstallFailed", INSTALL_ERROR, 4),
                condition("Stalled", "True", "RetriesExceeded", "Failed to install after 1 attempt(s)", 4));
        gaveUp.put("lastHandledResetAt", "2026-10-12T08:00:00Z");
        Map<String, String> asked = Map.of("reconcile.fluxcd.io/requestedAt", "2026-10-12T09:00:00Z",
                "reconcile.fluxcd.io/resetAt", "2026-10-12T09:00:00Z");

        FluxGitOpsBackend.Readiness notYet = FluxGitOpsBackend.readiness(helmRelease("new", 4, gaveUp, asked), "new",
                "2026-10-12T09:00:00Z");
        assertFalse(notYet.failed(), "the Stalled condition is the one from before the reset");
        assertFalse(notYet.ready());
        assertEquals("Flux has not started its new attempt at the release yet", notYet.message());

        // Flux took the request: its new attempt is under way.
        Map<String, Object> underWay = status(4L,
                condition("Ready", "Unknown", "Progressing", "Running 'install' action with timeout of 15m0s", 4));
        FluxGitOpsBackend.Readiness installing = FluxGitOpsBackend.readiness(helmRelease("new", 4, underWay, asked), "new",
                "2026-10-12T09:00:00Z");
        assertFalse(installing.failed());
        assertEquals("Progressing: Running 'install' action with timeout of 15m0s", installing.message());

        Map<String, Object> installed = status(4L, condition("Ready", "True", "InstallSucceeded",
                "Helm install succeeded for release analytics/superset", 4));
        installed.put("lastHandledResetAt", "2026-10-12T09:00:00Z");
        installed.put("lastHandledReconcileAt", "2026-10-12T09:00:00Z");
        assertTrue(FluxGitOpsBackend.readiness(helmRelease("new", 4, installed, asked), "new", "2026-10-12T09:00:00Z")
                .ready());

        gaveUp.put("lastHandledResetAt", "2026-10-12T09:00:00Z");
        gaveUp.put("lastHandledReconcileAt", "2026-10-12T09:00:00Z");
        assertTrue(FluxGitOpsBackend.readiness(helmRelease("new", 4, gaveUp, asked), "new", "2026-10-12T09:00:00Z")
                .failed(), "Flux gave up on its new attempt too");
    }

    @Test
    void theHelmReleasesAReleaseWaitsForAreThoseItDependsOnWhileFluxReportsItWaitingForThem() {
        GenericKubernetesResource waiting = helmRelease("new", 4, status(3L, condition("Ready", "False",
                "DependencyNotReady", "dependency 'keda/keda' is not ready", 4)), Map.of());
        waiting.setAdditionalProperty("spec", Map.of("dependsOn", List.of(Map.of("name", "keda", "namespace", "keda"),
                Map.of("name", "ldap-proxy"))));

        assertEquals(List.of(new FluxGitOpsBackend.NamespacedName("keda", "keda"),
                        new FluxGitOpsBackend.NamespacedName(NS, "ldap-proxy")),
                FluxGitOpsBackend.awaitedDependencies(waiting), "one without a namespace is in the release's");
        assertFalse(FluxGitOpsBackend.readiness(waiting, "new").failed(), "Flux never gives up on the release itself");

        GenericKubernetesResource installing = helmRelease("new", 4, status(3L, condition("Ready", "Unknown",
                "Progressing", "Running 'install' action with timeout of 15m0s", 4)), Map.of());
        installing.setAdditionalProperty("spec", waiting.get("spec"));
        assertEquals(List.of(), FluxGitOpsBackend.awaitedDependencies(installing), "its dependencies are Ready");
        assertEquals(List.of(), FluxGitOpsBackend.awaitedDependencies(null));
    }

    // ---------------------------------------------------------------- Git credential aliases

    /** A credential store holding a Git token of the view, and secrets of Ambari that are not Git credentials. */
    private GitCredentialService credentialStore(FluxGitOpsBackend backend) throws Exception {
        Map<String, String> stored = Map.of("gitops-7f3a", "glpat-Saved-Token", "corp-git-token", "glpat-Recorded-Token",
                "ambari.db.password", "Db-Pass-1");
        GitCredentialService credentials = mock(GitCredentialService.class);
        when(credentials.resolveSecret(anyString())).thenAnswer(inv -> stored.get((String) inv.getArgument(0)));
        Field field = FluxGitOpsBackend.class.getDeclaredField("gitCredentialService");
        field.setAccessible(true);
        field.set(backend, credentials);
        return credentials;
    }

    @Test
    void onlyGitCredentialsAreResolvedFromTheCredentialStore() throws Exception {
        FluxGitOpsBackend backend = backend();
        GitCredentialService credentials = credentialStore(backend);
        HelmDeployRequest foreign = request("main");
        foreign.getGit().setRepoUrl("https://git.example.org/platform/gitops.git");
        foreign.getGit().setCredentialAlias("ambari.db.password");
        HelmDeployRequest foreignTokenAlias = request("main");
        foreignTokenAlias.getGit().setRepoUrl("https://git.example.org/platform/gitops.git");
        foreignTokenAlias.getGit().setSshKey("-----BEGIN OPENSSH PRIVATE KEY-----");
        foreignTokenAlias.getGit().setAuthTokenAlias("ambari.db.password");

        for (HelmDeployRequest request : List.of(foreign, foreignTokenAlias)) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> backend.checkCommittable(request, null));
            assertTrue(refused.getMessage().startsWith("The credential alias 'ambari.db.password' does not hold a Git "
                    + "credential saved by KDPS"), refused.getMessage());
            assertThrows(IllegalArgumentException.class, () -> backend.commit(request, null, planned()));
        }
        verify(credentials, never()).resolveSecret("ambari.db.password");
        assertTrue(secretPayloads.isEmpty(), "nothing was prepared for the release");

        // A Git token the view saved, and the alias a release already pushes with, are still used.
        HelmDeployRequest saved = request("main");
        saved.getGit().setRepoUrl("https://git.example.org/platform/gitops.git");
        saved.getGit().setCredentialAlias("gitops-7f3a");
        backend.checkCommittable(saved, null);
        K8sReleaseEntity recorded = new K8sReleaseEntity();
        recorded.setId(K8sReleaseEntity.idOf(NS, RELEASE));
        recorded.setNamespace(NS);
        recorded.setReleaseName(RELEASE);
        recorded.setGitCredentialAlias("corp-git-token");
        releases.put(recorded.getId(), recorded);
        HelmDeployRequest redeploy = request("main");
        redeploy.getGit().setRepoUrl("https://git.example.org/platform/gitops.git");
        backend.checkCommittable(redeploy, null);
        verify(credentials).resolveSecret("gitops-7f3a");
        verify(credentials).resolveSecret("corp-git-token");
    }
}
