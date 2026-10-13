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
import com.google.gson.reflect.TypeToken;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandStatus;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.resources.CommandResource;
import org.apache.ambari.view.k8s.service.deployment.FakePullRequests;
import org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.SecurityProfileEntity;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GitOps deploys run the command plan of a direct deploy: the same preparation steps, the HelmRelease committed to Git
 * where the Helm install would run, then a wait for Flux before the post-deploy steps. Driven against a local bare
 * repository and mocked Kubernetes and Helm services.
 */
class CommandServiceGitOpsDeployTest {

    private static final String NS = "analytics";
    private static final String BIND_PASSWORD = "Bind-Pass-42";

    @TempDir
    Path tmp;

    private final Gson gson = new Gson();
    private InMemoryDataStore dataStore;
    private ViewContext ctx;
    private KubernetesService k8s;
    private HelmService helm;
    private CommandService service;
    private String rel;
    private String repoUrl;
    private Path bareRepo;
    private String seedCommit;
    private final Map<String, String> secretPayloads = new ConcurrentHashMap<>();

    /** Steps run with slf4j's MDC; see CommandServiceHelmStepTest for why it is loaded this way. */
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

        String instance = "gitops-" + Long.toHexString(System.nanoTime());
        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getInstanceName()).thenReturn(instance);
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", Files.createDirectories(tmp.resolve("work")).toString()));
        when(ctx.getInstanceData(anyString())).thenReturn(null);
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
        dataStore = new InMemoryDataStore();
        when(ctx.getDataStore()).thenReturn(dataStore);

        HelmRepoEntity charts = new HelmRepoEntity();
        charts.setId("charts");
        charts.setName("charts");
        charts.setType("HTTP");
        charts.setUrl("https://charts.example.org");
        charts.setAuthMode("anonymous");
        dataStore.store(charts);
        // The login of the release: an LDAP directory whose bind password must never reach Git.
        SecurityProfileEntity profile = new SecurityProfileEntity();
        profile.setId("corp-ldap");
        profile.setViewInstance(instance);
        profile.setDefault(true);
        profile.setProfileJson("{\"mode\":\"ldap\",\"ldap\":{\"url\":\"ldap://dir.example.org\","
                + "\"bindDn\":\"cn=kdps,dc=example,dc=org\",\"bindPassword\":\"" + BIND_PASSWORD + "\"}}");
        dataStore.store(profile);

        Map<String, String> cache = new ConcurrentHashMap<>();
        ViewConfigurationService configuration = mock(ViewConfigurationService.class);
        when(configuration.writeCacheFile(anyString(), anyString())).thenAnswer(inv -> {
            String path = "cache/" + inv.getArgument(0);
            cache.put(path, inv.getArgument(1));
            return path;
        });
        when(configuration.readCacheFile(anyString())).thenAnswer(inv -> cache.get((String) inv.getArgument(0)));
        k8s = mock(KubernetesService.class);
        when(k8s.getConfigurationService()).thenReturn(configuration);
        when(k8s.readOpaqueSecretKeyAsString(anyString(), anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(secretPayloads.get(inv.getArgument(0) + "/" + inv.getArgument(1))));
        doAnswer(inv -> {
            secretPayloads.put(inv.getArgument(0) + "/" + inv.getArgument(1),
                    new String((byte[]) inv.getArgument(3), StandardCharsets.UTF_8));
            return null;
        }).when(k8s).createOrUpdateOpaqueSecret(anyString(), anyString(), anyString(), any(byte[].class), anyMap(), any(), any());

        helm = mock(HelmService.class, RETURNS_DEEP_STUBS);
        when(helm.getRepositoryService().showValuesAsMap(any(), any(), any())).thenReturn(new LinkedHashMap<>());
        when(helm.getRepositoryService().getEffectiveImageRegistry(any())).thenReturn("registry.example.org");

        service = new CommandService(ctx, helm, k8s);
        // Its background sweep of operation files reads ctx: let it finish before the tests stub ctx further.
        service.startupSweep.get(30, java.util.concurrent.TimeUnit.SECONDS);
        rel = "zz-" + Long.toHexString(System.nanoTime());
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    /** A deploy of the test service, committed to {@code branch} of the repository Flux reads on main. */
    private HelmDeployRequest request(String deploymentMode, String branch) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setChart("example/zz-test-gitops");
        request.setReleaseName(rel);
        request.setNamespace(NS);
        request.setServiceKey("ZZTESTGITOPS");
        request.setRepoId("charts");
        request.setVersion("0.0.1");
        request.setDeploymentMode(deploymentMode);
        request.setSecretName("regcred");
        request.setSecurityProfile("corp-ldap");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("replicaCount", 2);
        values.put("ingress", new LinkedHashMap<>(Map.of("host", "zz.example.org")));
        request.setValues(values);
        // An uploaded ingress certificate: the plan stores it in a Secret and points the chart at it.
        request.setIngressTlsUpload(new LinkedHashMap<>(Map.of("secretName", rel + "-ingress-tls",
                "certPem", "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----",
                "keyPem", "-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----")));
        HelmDeployRequest.GitOptions git = new HelmDeployRequest.GitOptions();
        git.setRepoUrl(repoUrl);
        git.setBaseBranch("main");
        git.setBranch(branch);
        git.setCommitMode("DIRECT_COMMIT");
        request.setGit(git);
        return request;
    }

    /** Every preparation a deploy may plan, as steps: certificates, mounts, Polaris, the Airflow DAG, a dependency. */
    private HelmDeployRequest fullRequest(String deploymentMode) {
        HelmDeployRequest request = request(deploymentMode, "main");
        request.setMounts(new LinkedHashMap<>(Map.of("data", new LinkedHashMap<>(Map.of("size", "1Gi")))));
        request.setIngressTlsSelfSign(new LinkedHashMap<>(Map.of("mode", "ambariInternal", "ingressHost", "zz.example.org")));
        request.setIngressTlsCertManager(new LinkedHashMap<>(Map.of("issuerName", "corp-ca", "ingressHost", "zz.example.org")));
        request.setFormValues(new LinkedHashMap<>(Map.of(
                "icebergCatalog", new LinkedHashMap<>(Map.of("enabled", true, "warehouse", "lake")),
                "airflow", new LinkedHashMap<>(Map.of("enabled", true, "dagRepo", "https://git.example.org/dags.git")))));
        LinkedHashMap<String, Object> dependencies = new LinkedHashMap<>();
        dependencies.put("ldap-proxy", new LinkedHashMap<>(Map.of("chart", "ldap-proxy", "chartVersion", "0.1.0")));
        request.setDependencies(dependencies);
        return request;
    }

    private String plan(HelmDeployRequest request, boolean gitOps) {
        return service.planReleaseDeploy(request, "charts", "0.0.1", null, null, null, null, gitOps);
    }

    private List<String> stepIds(String rootId) {
        Type listType = new TypeToken<ArrayList<String>>() {}.getType();
        return gson.fromJson(dataStore.find(CommandEntity.class, rootId).getChildListJson(), listType);
    }

    private List<String> stepTypes(String rootId) {
        List<String> types = new ArrayList<>();
        for (String id : stepIds(rootId)) types.add(dataStore.find(CommandEntity.class, id).getType());
        return types;
    }

    private CommandEntity step(String rootId, CommandType type) {
        for (String id : stepIds(rootId)) {
            CommandEntity c = dataStore.find(CommandEntity.class, id);
            if (type.name().equals(c.getType())) return c;
        }
        fail("no " + type + " step in " + stepTypes(rootId));
        return null;
    }

    private CommandStatusEntity statusOf(CommandEntity command) {
        return dataStore.find(CommandStatusEntity.class, command.getCommandStatusId());
    }

    private String stateOf(String rootId, CommandType type) {
        return statusOf(step(rootId, type)).getState();
    }

    private Map<String, Object> paramsOf(CommandEntity command) {
        Type mapType = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();
        return gson.fromJson(CommandUtils.resolveParamsPayload(command.getParamsJson()), mapType);
    }

    /** Runs the operation until it ends, as the scheduler does, and returns its status. */
    private CommandStatusEntity run(String rootId) throws InterruptedException {
        service.runNextStep(rootId);
        return awaitEnd(rootId);
    }

    private CommandStatusEntity awaitEnd(String rootId) throws InterruptedException {
        CommandEntity root = dataStore.find(CommandEntity.class, rootId);
        for (int i = 0; i < 600; i++) {
            CommandStatusEntity st = statusOf(root);
            if (!CommandState.PENDING.name().equals(st.getState()) && !CommandState.RUNNING.name().equals(st.getState())) {
                Thread.sleep(100); // let the step that ended the operation finish storing its own state
                return statusOf(root);
            }
            Thread.sleep(50);
        }
        fail("operation " + rootId + " did not end: " + stepTypes(rootId));
        return null;
    }

    /**
     * What Flux reports of the HelmRelease the operation's commit wrote: Ready, or given up on (Stalled) with the
     * cause on its Ready condition, and the reset it last handled.
     */
    private record FluxReport(String ready, String reason, String message, String handledReset) {}

    private volatile FluxReport flux;

    /**
     * Flux applies the HelmRelease that the operation's commit wrote and reports it Ready ({@code "True"}), or gives
     * up on it ({@code "False"}) after an install that failed for the given reason.
     */
    private void fluxAppliesTheCommit(String readyCondition, String reason, String message) {
        flux = new FluxReport(readyCondition, reason, message, null);
        when(k8s.getFluxHelmRelease(NS, rel)).thenAnswer(inv -> helmReleaseAsFluxReportsIt(flux));
    }

    /** Asked to try the release again, Flux installs it: its cause was fixed on the cluster meanwhile. */
    private void fluxInstallsTheReleaseWhenAskedToTryAgain() {
        when(k8s.requestFluxReset(NS, rel)).thenAnswer(inv -> {
            String resetAt = "2026-10-12T09:00:00Z";
            flux = new FluxReport("True", "InstallSucceeded", "Helm install succeeded", resetAt);
            return resetAt;
        });
    }

    /**
     * The HelmRelease the operation's commit wrote, as Flux writes it: each condition with the generation it was set
     * for, and {@code status.observedGeneration}, which Flux moves once the release is Ready or Stalled.
     */
    private GenericKubernetesResource helmReleaseAsFluxReportsIt(FluxReport report) {
        String digest = null;
        for (CommandEntity c : dataStore.findAll(CommandEntity.class, null)) {
            if (!CommandType.GITOPS_COMMIT.name().equals(c.getType())) continue;
            CommandStatusEntity st = statusOf(c);
            if (st != null && st.getResultJson() != null) {
                digest = String.valueOf(gson.fromJson(st.getResultJson(), Map.class).get("specDigest"));
            }
        }
        return helmReleaseAsFluxReportsIt(NS, rel, report, digest);
    }

    /**
     * A HelmRelease as Flux writes it. One that waits for a HelmRelease it depends on (DependencyNotReady) is never
     * Stalled: Flux does not try to install it meanwhile.
     */
    private GenericKubernetesResource helmReleaseAsFluxReportsIt(String namespace, String name, FluxReport report,
                                                                 String digest) {
        Map<String, String> annotations = new LinkedHashMap<>();
        if (digest != null) {
            annotations.put(FluxGitOpsBackend.SPEC_DIGEST_ANNOTATION, digest);
        }
        Map<String, Object> status = new LinkedHashMap<>();
        if (report.handledReset() != null) {
            annotations.put("reconcile.fluxcd.io/requestedAt", report.handledReset());
            annotations.put("reconcile.fluxcd.io/resetAt", report.handledReset());
            status.put("lastHandledReconcileAt", report.handledReset());
            status.put("lastHandledResetAt", report.handledReset());
        }
        GenericKubernetesResource hr = new GenericKubernetesResource();
        hr.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(namespace).withGeneration(1L)
                .withAnnotations(annotations).build());
        List<Map<String, Object>> conditions = new ArrayList<>();
        conditions.add(Map.of("type", "Ready", "status", report.ready(), "reason", report.reason(),
                "message", report.message(), "observedGeneration", 1));
        if ("False".equals(report.ready()) && !"DependencyNotReady".equals(report.reason())) {
            conditions.add(Map.of("type", "Stalled", "status", "True", "reason", "RetriesExceeded",
                    "message", "Failed to install after 1 attempt(s)", "observedGeneration", 1));
        }
        status.put("observedGeneration", 1);
        status.put("conditions", conditions);
        hr.setAdditionalProperty("status", status);
        if (NS.equals(namespace) && rel.equals(name) && !dependsOn.isEmpty()) {
            hr.setAdditionalProperty("spec", Map.of("dependsOn", dependsOn));
        }
        return hr;
    }

    /** The HelmReleases the release's HelmRelease depends on, as the commit wrote them into it. */
    private volatile List<Map<String, Object>> dependsOn = List.of();

    private volatile FluxReport keda;

    /**
     * Flux applies the commit, but cannot install KEDA, which the commit wrote next to the release, and gives up on it:
     * it then holds the release back, waiting for KEDA.
     */
    private void fluxGivesUpOnKeda(String cause) {
        dependsOn = List.of(Map.of("name", "keda", "namespace", "keda"));
        keda = new FluxReport("False", "InstallFailed", cause, null);
        fluxAppliesTheCommit("False", "DependencyNotReady", "dependency 'keda/keda' is not ready");
        when(k8s.getFluxHelmRelease("keda", "keda")).thenAnswer(inv ->
                helmReleaseAsFluxReportsIt("keda", "keda", keda, null));
    }

    /** Asked to try KEDA again, Flux installs it, then the release that waited for it. */
    private void fluxInstallsKedaThenTheReleaseWhenAskedToTryAgain() {
        when(k8s.requestFluxReset("keda", "keda")).thenAnswer(inv -> {
            String resetAt = "2026-10-12T09:00:00Z";
            keda = new FluxReport("True", "InstallSucceeded", "Helm install succeeded", resetAt);
            flux = new FluxReport("True", "InstallSucceeded", "Helm install succeeded", null);
            return resetAt;
        });
    }

    private String published(String branch, String path) throws Exception {
        Path clone = tmp.resolve("verify-" + System.nanoTime());
        try (Git ignored = Git.cloneRepository().setURI(repoUrl).setBranch(branch).setDirectory(clone.toFile()).call()) {
            Path file = clone.resolve(path);
            return Files.exists(file) ? Files.readString(file) : null;
        }
    }

    private List<String> commitsOn(String branch) throws Exception {
        List<String> commits = new ArrayList<>();
        try (Git g = Git.open(bareRepo.toFile())) {
            for (RevCommit c : g.log().add(g.getRepository().resolve("refs/heads/" + branch)).call()) {
                commits.add(c.getName());
            }
        }
        return commits;
    }

    private String releaseDir() {
        return "clusters/default/" + NS + "/" + rel;
    }

    // ------------------------------------------------------------------------------------------------ plan shape

    @Test
    void aGitOpsPlanPreparesTheReleaseLikeADirectDeployAndCommitsWhereTheHelmInstallWouldRun() {
        List<String> direct = stepTypes(plan(fullRequest("DIRECT_HELM"), false));
        String gitOpsId = plan(fullRequest("FLUX_GITOPS"), true);
        List<String> gitOps = stepTypes(gitOpsId);

        // The direct plan with its Helm steps for the dependencies left out (Flux installs those from the commit) and
        // the Helm dry run and install replaced by the commit and the wait for Flux.
        List<String> expected = new ArrayList<>();
        for (String type : direct) {
            if (type.startsWith("HELM_DEPLOY_DRY_RUN_DEPENDENCY") || type.startsWith("HELM_DEPLOY_DEPENDENCY")
                    || type.equals(CommandType.DEPENDENCY_SATISFIED.name()) || type.equals(CommandType.HELM_DEPLOY_DRY_RUN.name())) {
                continue;
            }
            if (type.equals(CommandType.HELM_DEPLOY.name())) {
                expected.add(CommandType.GITOPS_COMMIT.name());
                expected.add(CommandType.FLUX_WAIT_READY.name());
                continue;
            }
            expected.add(type);
        }
        assertEquals(expected, gitOps);
        for (CommandType preparation : List.of(CommandType.INGRESS_TLS_SELFSIGN, CommandType.INGRESS_TLS_CERTMANAGER,
                CommandType.K8S_MOUNTS_DIR_EXEC, CommandType.HELM_REPO_LOGIN, CommandType.DBT_PUBLISH_AIRFLOW_DAG,
                CommandType.POLARIS_PROVISION_CATALOG, CommandType.CONFIG_MATERIALIZE)) {
            assertTrue(gitOps.indexOf(preparation.name()) >= 0
                            && gitOps.indexOf(preparation.name()) < gitOps.indexOf(CommandType.GITOPS_COMMIT.name()),
                    preparation + " runs before the commit: " + gitOps);
        }
        assertTrue(direct.contains(CommandType.HELM_DEPLOY_DEPENDENCY_RELEASE.name()), direct.toString());
        assertEquals(gitOps.indexOf(CommandType.GITOPS_COMMIT.name()) + 1, gitOps.indexOf(CommandType.FLUX_WAIT_READY.name()));
        assertEquals(gitOps.indexOf(CommandType.FLUX_WAIT_READY.name()) + 1, gitOps.indexOf(CommandType.AMBARI_VIEW_PROVISION.name()),
                "the post-deploy steps wait for Flux: " + gitOps);
        assertEquals("Install release " + rel + " with GitOps", dataStore.find(CommandEntity.class, gitOpsId).getTitle());

        // The commit carries what it writes besides the values: the dependency, as a HelmRelease of its own.
        Map<String, Object> commitParams = paramsOf(step(gitOpsId, CommandType.GITOPS_COMMIT));
        assertTrue(String.valueOf(commitParams.get(CommandService.GITOPS_RELEASE_PARAM)).contains("ldap-proxy"),
                String.valueOf(commitParams));
        assertEquals(rel + "-ingress-tls", ((Map<?, ?>) commitParams.get("_ov")).get("ingress.tls[0].secretName"),
                "the plan's overrides reach the commit step");
    }

    @Test
    void aNewGitOpsReleaseIsRecordedByItsCommitNotWhenItIsPlanned() {
        plan(request("FLUX_GITOPS", "main"), true);

        assertNull(dataStore.find(K8sReleaseEntity.class, K8sReleaseEntity.idOf(NS, rel)),
                "nothing is in Git or running yet");
    }

    // ------------------------------------------------------------------------------------------------ execution

    @Test
    void aGitOpsDeployCommitsWhatTheHelmInstallWouldReceiveThenWaitsForFluxBeforeThePostDeploySteps() throws Exception {
        fluxAppliesTheCommit("True", "InstallSucceeded", "Helm install succeeded");
        HelmDeployRequest request = request("FLUX_GITOPS", "main");
        // As a wizard binding writes it: a dotted path, which Helm gets as nested values.
        request.getValues().put("certificates.customCAs[0].secret", "corp-ca");
        String id = plan(request, true);

        CommandStatusEntity end = run(id);

        assertEquals(CommandState.SUCCEEDED.name(), stateOf(id, CommandType.GITOPS_COMMIT));
        assertEquals(CommandState.SUCCEEDED.name(), stateOf(id, CommandType.FLUX_WAIT_READY),
                statusOf(step(id, CommandType.FLUX_WAIT_READY)).getMessage());
        assertTrue(statusOf(step(id, CommandType.FLUX_WAIT_READY)).getMessage().contains("InstallSucceeded"));
        // The post-deploy step ran once Flux reported the release Ready (it then fails: no Ambari in this test).
        assertNotEquals(CommandState.PENDING.name(), stateOf(id, CommandType.AMBARI_VIEW_PROVISION));
        assertTrue(end.getMessage().contains("Ambari: provision view instance"), end.getMessage());

        String helmRelease = published("main", releaseDir() + "/helmrelease.yaml");
        assertNotNull(helmRelease);
        for (String expected : List.of("replicaCount: 2\n", "injectionMode: \"PRE_PROVISIONED\"",
                "secretName: \"" + rel + "-ingress-tls\"", "- \"zz.example.org\"", "imageRegistry: \"registry.example.org\"",
                "name: \"regcred\"", "ldap://dir.example.org", "chart: zz-test-gitops", "version: 0.0.1",
                "certificates:\n      customCAs:\n      - secret: \"corp-ca\"")) {
            assertTrue(helmRelease.contains(expected), expected + " in\n" + helmRelease);
        }
        assertFalse(helmRelease.contains(BIND_PASSWORD), "no password in Git");
        assertTrue(secretPayloads.get(NS + "/" + rel + "-kdps-values").contains(BIND_PASSWORD),
                "the password reaches the release through its values Secret");
        verify(k8s).createOrUpdateTlsSecret(eq(NS), eq(rel + "-ingress-tls"), any(), any(), any(), any());

        String commitMessage = statusOf(step(id, CommandType.GITOPS_COMMIT)).getMessage();
        assertTrue(commitMessage.startsWith("Committed " + commitsOn("main").get(0) + " to branch main"), commitMessage);
        K8sReleaseEntity recorded = dataStore.find(K8sReleaseEntity.class, K8sReleaseEntity.idOf(NS, rel));
        assertEquals("FLUX_GITOPS", recorded.getDeploymentMode());
        assertEquals(repoUrl, recorded.getGitRepoUrl());
        assertEquals(commitsOn("main").get(0), recorded.getGitCommitSha());
    }

    @Test
    void nothingIsCommittedWhenAPreparationStepFails() throws Exception {
        doThrow(new IllegalStateException("exceeded quota: storage")).when(k8s).createMounts(anyString(), anyString(), anyMap());
        HelmDeployRequest request = request("FLUX_GITOPS", "main");
        request.setMounts(new LinkedHashMap<>(Map.of("data", new LinkedHashMap<>(Map.of("size", "1Gi")))));
        String id = plan(request, true);

        CommandStatusEntity end = run(id);

        assertEquals(CommandState.FAILED.name(), end.getState());
        assertTrue(end.getMessage().contains("exceeded quota"), end.getMessage());
        assertEquals(CommandState.PENDING.name(), stateOf(id, CommandType.GITOPS_COMMIT));
        assertEquals(List.of(seedCommit), commitsOn("main"), "nothing reached Git");
        assertTrue(secretPayloads.isEmpty(), "no values Secret either");
        verify(k8s, never()).getFluxHelmRelease(anyString(), anyString());
    }

    @Test
    void aFailedWaitIsResumedWithoutCommittingAgainAndARerunCommitCommitsNothing() throws Exception {
        fluxAppliesTheCommit("False", "InstallFailed", "Helm install failed: secret \"zz-oidc\" not found");
        String id = plan(request("FLUX_GITOPS", "main"), true);

        CommandStatusEntity end = run(id);

        assertEquals(CommandState.FAILED.name(), end.getState());
        String waitMessage = statusOf(step(id, CommandType.FLUX_WAIT_READY)).getMessage();
        assertTrue(waitMessage.contains("Flux could not apply HelmRelease " + NS + "/" + rel
                + ": InstallFailed: Helm install failed: secret \"zz-oidc\" not found"
                + " (RetriesExceeded: Failed to install after 1 attempt(s))"), waitMessage);
        assertEquals(CommandState.PENDING.name(), stateOf(id, CommandType.AMBARI_VIEW_PROVISION),
                "no post-deploy step without a ready release");
        verify(k8s, never()).requestFluxReset(anyString(), anyString());
        List<String> afterFirstRun = commitsOn("main");
        assertEquals(2, afterFirstRun.size());

        // Fixed on the cluster; the operator resumes: the wait asks Flux to try the release again (Flux does not on
        // its own once it gave up), and the commit is not repeated.
        fluxInstallsTheReleaseWhenAskedToTryAgain();
        service.resume(id, null);
        awaitEnd(id);
        assertEquals(CommandState.SUCCEEDED.name(), stateOf(id, CommandType.FLUX_WAIT_READY),
                statusOf(step(id, CommandType.FLUX_WAIT_READY)).getMessage());
        verify(k8s, times(1)).requestFluxReset(NS, rel);
        assertEquals(afterFirstRun, commitsOn("main"));

        // The commit step run again, as after a restart that cut it short after the push: nothing new is committed.
        CommandStatusEntity commitStatus = statusOf(step(id, CommandType.GITOPS_COMMIT));
        commitStatus.setState(CommandState.FAILED.name());
        dataStore.store(commitStatus);
        service.resume(id, null);
        awaitEnd(id);
        assertEquals(CommandState.SUCCEEDED.name(), stateOf(id, CommandType.GITOPS_COMMIT));
        assertTrue(statusOf(step(id, CommandType.GITOPS_COMMIT)).getMessage().contains("nothing committed"),
                statusOf(step(id, CommandType.GITOPS_COMMIT)).getMessage());
        assertEquals(afterFirstRun, commitsOn("main"));
    }

    @Test
    void aReleaseFluxGaveUpOnIsTriedAgainWhenItIsDeployedAgainWithNothingNewToCommit() throws Exception {
        fluxAppliesTheCommit("False", "InstallFailed",
                "Helm install failed: pods \"zz-0\" is forbidden: exceeded quota: compute-resources");
        String first = plan(request("FLUX_GITOPS", "main"), true);
        assertEquals(CommandState.FAILED.name(), run(first).getState());
        List<String> committed = commitsOn("main");

        // The quota is raised; the operator deploys the release again, as after a direct deploy that failed.
        fluxInstallsTheReleaseWhenAskedToTryAgain();
        String again = plan(request("FLUX_GITOPS", "main"), true);
        run(again);

        assertTrue(statusOf(step(again, CommandType.GITOPS_COMMIT)).getMessage().contains("nothing committed"));
        verify(k8s, times(1)).requestFluxReset(NS, rel);
        assertEquals(CommandState.SUCCEEDED.name(), stateOf(again, CommandType.FLUX_WAIT_READY),
                statusOf(step(again, CommandType.FLUX_WAIT_READY)).getMessage());
        assertNotEquals(CommandState.PENDING.name(), stateOf(again, CommandType.AMBARI_VIEW_PROVISION),
                "the post-deploy steps run once the release is ready");
        assertEquals(committed, commitsOn("main"));
    }

    /**
     * A deploy proposed in a pull request from {@code flux-work}, with the token that opens it on the Git server, whose
     * pull requests the returned fake holds.
     */
    private HelmDeployRequest proposedOnFluxWork(FakePullRequests pullRequests) throws Exception {
        if (gitCredentials == null) {
            credentialStore(true);
        }
        Field backendField = CommandService.class.getDeclaredField("fluxGitOpsBackend");
        backendField.setAccessible(true);
        Field pullRequestsField = FluxGitOpsBackend.class.getDeclaredField("pullRequests");
        pullRequestsField.setAccessible(true);
        pullRequestsField.set(backendField.get(service), pullRequests);
        HelmDeployRequest request = request("FLUX_GITOPS", "flux-work");
        request.getGit().setCommitMode("PR_MODE");
        request.getGit().setAuthToken(TOKEN);
        return request;
    }

    @Test
    void aChangeWaitingForAMergeHasNotSetAGeneratedPasswordYet() throws Exception {
        String id = plan(proposedOnFluxWork(new FakePullRequests()), true);
        // As a deploy of a service with a generated administrator password plans it (see
        // CommandServiceGeneratedSecretsTest): the chart is asked to set it on the account.
        CommandEntity root = dataStore.find(CommandEntity.class, id);
        Map<String, Object> rootParams = paramsOf(root);
        rootParams.put(CommandService.PENDING_CREDENTIALS_PARAM,
                List.of(Map.of("namespace", NS, "secretName", rel + "-zz-admin")));
        root.setParamsJson(gson.toJson(rootParams));
        dataStore.store(root);

        CommandStatusEntity end = run(id);

        assertEquals(CommandState.SUCCEEDED.name(), end.getState(), end.getMessage());
        assertEquals(Boolean.TRUE, gson.fromJson(end.getResultJson(), Map.class).get("waitingForMerge"));
        verify(k8s, never()).removeSecretAnnotation(anyString(), anyString(), anyString());
    }

    @Test
    void aChangeWaitingForAMergeEndsTheOperationAfterTheCommitAndRunsNothingThatNeedsIt() throws Exception {
        FakePullRequests pullRequests = new FakePullRequests();
        String id = plan(proposedOnFluxWork(pullRequests), true);

        CommandStatusEntity end = run(id);

        assertEquals(CommandState.SUCCEEDED.name(), end.getState(), end.getMessage());
        assertTrue(end.getMessage().startsWith("Waiting for the pull request " + FakePullRequests.url("1")
                + " to be merged into branch main: Flux applies the change once it is merged."), end.getMessage());
        assertEquals("flux-work", pullRequests.last().head());
        assertEquals("main", pullRequests.last().base());
        assertTrue(end.getMessage().contains("Not run until then: Flux: wait for HelmRelease " + rel
                + " to be ready, Ambari: provision view instance for " + rel), end.getMessage());
        assertEquals(CommandState.CANCELED.name(), stateOf(id, CommandType.FLUX_WAIT_READY));
        assertEquals(CommandState.CANCELED.name(), stateOf(id, CommandType.AMBARI_VIEW_PROVISION));
        assertTrue(statusOf(step(id, CommandType.AMBARI_VIEW_PROVISION)).getMessage().startsWith("Not run: Waiting for"));
        Map<?, ?> result = gson.fromJson(end.getResultJson(), Map.class);
        assertEquals(Boolean.TRUE, result.get("waitingForMerge"));
        assertEquals("flux-work", result.get("branch"));
        // What the operator's screens read: the operation's status, and the list of operations.
        CommandStatus polled = service.getStatus(id);
        assertEquals(CommandState.SUCCEEDED, polled.state);
        assertNotNull(polled.result, "the outcome is returned with the status");
        assertEquals(Boolean.TRUE, polled.result.get("waitingForMerge"));
        assertEquals(end.getMessage(), polled.result.get("message"));
        assertEquals(List.of("Flux: wait for HelmRelease " + rel + " to be ready",
                "Ambari: provision view instance for " + rel), polled.result.get("notRun"));
        CommandStatus listed = service.listCommands(10, 0).stream().filter(c -> id.equals(c.id)).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, listed.result.get("waitingForMerge"));
        assertNull(service.getStatus(step(id, CommandType.GITOPS_COMMIT).getId()).result,
                "the results of steps stay internal");
        verify(k8s, never()).getFluxHelmRelease(anyString(), anyString());
        assertNull(published("main", releaseDir() + "/helmrelease.yaml"), "main, which Flux reads, is unchanged");
        assertNotNull(published("flux-work", releaseDir() + "/helmrelease.yaml"));

        // Once the branch is merged, the same deploy finds Git up to date and runs the rest.
        Path merger = tmp.resolve("merger");
        try (Git g = Git.cloneRepository().setURI(repoUrl).setDirectory(merger.toFile()).call()) {
            g.merge().include(g.getRepository().resolve("refs/remotes/origin/flux-work")).call();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
        }
        pullRequests.merge("1");
        fluxAppliesTheCommit("True", "InstallSucceeded", "Helm install succeeded");
        String again = plan(proposedOnFluxWork(pullRequests), true);

        run(again);

        assertTrue(statusOf(step(again, CommandType.GITOPS_COMMIT)).getMessage().contains("nothing committed"));
        assertEquals(CommandState.SUCCEEDED.name(), stateOf(again, CommandType.FLUX_WAIT_READY));
        assertNotEquals(CommandState.PENDING.name(), stateOf(again, CommandType.AMBARI_VIEW_PROVISION));
    }

    @Test
    void anUploadOverACertificateKdpsDidNotIssueIsRefusedBeforeAnythingIsWrittenUnlessConfirmed() {
        // The release's Secret holds a certificate the operator brought: no label of a leaf KDPS minted.
        io.fabric8.kubernetes.api.model.Secret operatorOwn = new io.fabric8.kubernetes.api.model.SecretBuilder()
                .withNewMetadata().withName(rel + "-ingress-tls").withNamespace(NS).endMetadata()
                .withType("kubernetes.io/tls").addToData("tls.crt", "Y29ycA==").addToData("tls.key", "a2V5")
                .build();
        when(k8s.getSecret(NS, rel + "-ingress-tls")).thenReturn(operatorOwn);

        for (boolean gitOps : new boolean[]{false, true}) {
            HelmDeployRequest request = request(gitOps ? "FLUX_GITOPS" : "DIRECT_HELM", "main");
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> plan(request, gitOps));
            assertTrue(refused.getMessage().contains(NS + "/" + rel + "-ingress-tls"), refused.getMessage());
        }
        verify(k8s, never()).createOrUpdateTlsSecret(anyString(), anyString(), any(), any(), any(), any());

        HelmDeployRequest confirmed = request("DIRECT_HELM", "main");
        confirmed.setReplaceExistingTls(true);
        plan(confirmed, false);
        verify(k8s).createOrUpdateTlsSecret(eq(NS), eq(rel + "-ingress-tls"), any(), any(), any(), any());
    }

    @Test
    void aGitOpsDeployThatCouldNotBeCommittedIsRefusedBeforeAnythingIsPrepared() {
        HelmDeployRequest noCredentials = request("FLUX_GITOPS", "main");
        noCredentials.getGit().setRepoUrl("https://git.example.org/platform/gitops.git");
        HelmDeployRequest noRepository = request("FLUX_GITOPS", "main");
        noRepository.getGit().setRepoUrl(null);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.gitOpsDeploy(noCredentials, "charts", "0.0.1", null, null, null, null));
        assertEquals("GitOps over HTTPS requires authToken or sshKey", refused.getMessage());
        refused = assertThrows(IllegalArgumentException.class,
                () -> service.gitOpsDeploy(noRepository, "charts", "0.0.1", null, null, null, null));
        assertEquals("Flux GitOps requires git.repoUrl", refused.getMessage());

        assertTrue(dataStore.findAll(CommandEntity.class, null).isEmpty(), "nothing was planned");
        verify(k8s, never()).createOrUpdateTlsSecret(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void aDeployNamingASecurityProfileThatIsGoneFailsBeforeAnythingIsPrepared() throws Exception {
        for (String mode : List.of("DIRECT_HELM", "FLUX_GITOPS")) {
            HelmDeployRequest request = request(mode, "main");
            request.setSecurityProfile("retired-ldap");

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> service.submitDeploy(request, "charts", "0.0.1", null, null, null, null, null), mode);
            assertTrue(refused.getMessage().startsWith("The security profile 'retired-ldap' that this deploy asks for"
                    + " does not exist"), refused.getMessage());
            assertTrue(refused.getMessage().contains("nothing was deployed"), refused.getMessage());
            // Planned straight away, as a resumed or scripted deploy is, it stops too rather than going without it.
            assertThrows(IllegalArgumentException.class, () -> plan(request, "FLUX_GITOPS".equals(mode)), mode);
        }

        assertTrue(dataStore.findAll(CommandEntity.class, null).isEmpty(), "nothing was planned");
        verify(k8s, never()).createNamespace(anyString());
        verify(k8s, never()).createOrUpdateTlsSecret(anyString(), anyString(), any(), any(), any(), any());
        assertEquals(List.of(seedCommit), commitsOn("main"));
    }

    @Test
    void aDeployWhoseSecurityProfilesCannotBeReadFailsInsteadOfDeployingWithoutOne() throws Exception {
        DataStore failing = mock(DataStore.class, org.mockito.AdditionalAnswers.delegatesTo(dataStore));
        doThrow(new org.apache.ambari.view.PersistenceException("connection reset"))
                .when(failing).findAll(eq(SecurityProfileEntity.class), any());
        doReturn(failing).when(ctx).getDataStore();
        HelmDeployRequest request = request("DIRECT_HELM", "main");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.submitDeploy(request, "charts", "0.0.1", null, null, null, null, null));

        assertTrue(refused.getMessage().contains("'corp-ldap'"), refused.getMessage());
        assertTrue(refused.getMessage().contains("could not be read"), refused.getMessage());
        assertTrue(dataStore.findAll(CommandEntity.class, null).isEmpty(), "nothing was planned");
    }

    @Test
    void aPullRequestIsNamedInWhatTheDeployWaitsFor() {
        FluxGitOpsBackend.CommitResult proposed = new FluxGitOpsBackend.CommitResult("1a2b3c", "1a2b3c", true,
                "https://git.example.org/platform/gitops.git", "flux-zz", "main", false,
                "https://git.example.org/platform/gitops/-/merge_requests/7", "opened", "d1g");

        assertEquals("Waiting for the pull request https://git.example.org/platform/gitops/-/merge_requests/7 to be "
                + "merged into branch main: Flux applies the change once it is merged.", CommandService.gitOpsMergeWait(proposed));
        assertEquals("Committed 1a2b3c to branch flux-zz of https://git.example.org/platform/gitops.git (pull request "
                + "https://git.example.org/platform/gitops/-/merge_requests/7)", CommandService.describeGitOpsCommit(proposed));
    }

    // ------------------------------------------------------------------------------------------------ dependencies

    private static final String COMMANDS_URL = "https://ambari.example.org/api/v1/views/K8S-VIEW/versions/1.0.0/"
            + "instances/kdps/resources/api/commands";
    private static final String WEBHOOK = "kerberos-keytab-mutating-webhook";

    /** The keytab webhook and KEDA, declared as the Trino service definition declares them. */
    private HelmDeployRequest requestWithDependencies() {
        HelmDeployRequest request = request("FLUX_GITOPS", "main");
        LinkedHashMap<String, Object> dependencies = new LinkedHashMap<>();
        LinkedHashMap<String, Object> webhook = new LinkedHashMap<>();
        webhook.put("fullnameOverride", "keytab-webhook");
        webhook.put("secretName", "regcred");
        webhook.put("chart", WEBHOOK);
        webhook.put("chartVersion", "0.1.0");
        webhook.put("imageTag", "1.0.0.1");
        webhook.put("imageRepository", "registry.clemlab.com/clemlabprojects");
        webhook.put("namespace", "ambari-mutating-webhooks");
        webhook.put("imageGlobalRegistryProperty", "global.imageRegistry");
        webhook.put("dynamicValues", new ArrayList<>(List.of("AMBARI_KERBEROS_REALM:backend.krbRealm")));
        webhook.put("isWebhook", true);
        webhook.put("serviceAccounts", new ArrayList<>(List.of(WEBHOOK)));
        dependencies.put(WEBHOOK, webhook);
        LinkedHashMap<String, Object> keda = new LinkedHashMap<>();
        keda.put("secretName", "regcred");
        keda.put("chart", "keda");
        keda.put("chartVersion", "2.18.0");
        keda.put("imageTag", "2.18.0");
        keda.put("imageRepository", "registry.clemlab.com/clemlabprojects");
        keda.put("imageGlobalRegistryProperty", "global.image.registry");
        keda.put("namespace", "keda");
        keda.put("serviceAccounts", new ArrayList<>(List.of("keda-operator")));
        dependencies.put("keda", keda);
        request.setDependencies(dependencies);
        return request;
    }

    private void kerberosInjectionMode(String mode) {
        // View settings are shared by every user of the instance (AMBARI-746).
        new org.apache.ambari.view.k8s.store.InstanceSettings(ctx)
                .put("view.settings.json", "{\"kerberos\":{\"injectionMode\":\"" + mode + "\"}}");
    }

    private String planWithCommandsUrl(HelmDeployRequest request) {
        return service.planReleaseDeploy(request, "charts", "0.0.1", COMMANDS_URL, null, null, null, true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dependencyStepsOf(Map<String, Object> commitParams) {
        return (Map<String, Object>) ((Map<String, Object>) commitParams.get(CommandService.GITOPS_RELEASE_PARAM))
                .get(CommandService.GITOPS_DEPENDENCY_STEPS);
    }

    @Test
    void eachDependencyIsCommittedWithWhatItsHelmInstallWouldReceiveInWebhookMode() throws Exception {
        kerberosInjectionMode("WEBHOOK");
        List<String> webhookPrepared = new ArrayList<>();
        service.keytabWebhookPreparation = headers -> webhookPrepared.add("keytab-webhook");
        fluxAppliesTheCommit("True", "InstallSucceeded", "Helm install succeeded");
        String id = planWithCommandsUrl(requestWithDependencies());
        assertEquals(List.of("keytab-webhook"), webhookPrepared,
                "the webhook's namespace and certificates are prepared when the deploy is planned, as for a direct deploy");
        // The realm, as Ambari resolved it for the webhook when the deploy was planned (no Ambari in this test).
        CommandEntity commitStep = step(id, CommandType.GITOPS_COMMIT);
        Map<String, Object> commitParams = paramsOf(commitStep);
        @SuppressWarnings("unchecked")
        Map<String, Object> webhookStep = (Map<String, Object>) dependencyStepsOf(commitParams).get(WEBHOOK);
        assertEquals(COMMANDS_URL, webhookStep.get("backend.url"));
        webhookStep.put("_dynamicOverrides", new LinkedHashMap<>(Map.of("backend.krbRealm", "EXAMPLE.ORG")));
        commitStep.setParamsJson(gson.toJson(commitParams));
        dataStore.store(commitStep);

        CommandStatusEntity end = run(id);

        assertEquals(CommandState.SUCCEEDED.name(), stateOf(id, CommandType.GITOPS_COMMIT),
                statusOf(step(id, CommandType.GITOPS_COMMIT)).getMessage());
        String webhook = published("main", releaseDir() + "/dependencies/" + WEBHOOK + "-helmrelease.yaml");
        assertNotNull(webhook, "the webhook is committed next to the release");
        for (String expected : List.of("timeout: 15m\n", "url: \"" + COMMANDS_URL + "\"", "krbRealm: \"EXAMPLE.ORG\"",
                "fullnameOverride: \"keytab-webhook\"", "imageRegistry: \"registry.example.org\"", "name: \"regcred\"",
                "caBundle: \"" + new WebHookConfigurationService(ctx, k8s).currentCaBundleBase64() + "\"")) {
            assertTrue(webhook.contains(expected), expected + " in\n" + webhook);
        }
        String keda = published("main", releaseDir() + "/dependencies/keda-helmrelease.yaml");
        assertTrue(keda.contains("    global:\n      image:\n        registry: \"registry.example.org\"\n"),
                "KEDA pulls from the repository's image registry, as with a direct deploy:\n" + keda);
        assertTrue(keda.contains("name: \"regcred\""), keda);
        // Each dependency's pull Secret, created in its own namespace as before its Helm install.
        verify(helm).ensureImagePullSecretFromRepo("charts", "ambari-mutating-webhooks", "regcred", List.of(WEBHOOK));
        verify(helm).ensureImagePullSecretFromRepo("charts", "keda", "regcred", List.of("keda-operator"));
        String helmRelease = published("main", releaseDir() + "/helmrelease.yaml");
        assertTrue(helmRelease.contains("injectionMode: \"WEBHOOK\""), helmRelease);
        assertTrue(helmRelease.contains("dependsOn:"), helmRelease);
        assertEquals(CommandState.SUCCEEDED.name(), stateOf(id, CommandType.FLUX_WAIT_READY), end.getMessage());
    }

    @Test
    void aDependencyFluxGaveUpOnFailsTheWaitAtOnceAndIsTriedAgainWhenTheReleaseIsDeployedAgain() throws Exception {
        fluxGivesUpOnKeda("Helm install failed for release keda/keda with chart keda@2.18.0: CustomResourceDefinition "
                + "\"scaledobjects.keda.sh\" exists and cannot be imported into the current release");
        String first = planWithCommandsUrl(requestWithDependencies());

        // Within the time awaitEnd allows, far short of the wait's timeout: the wait does not outlast what Flux gave up.
        CommandStatusEntity end = run(first);

        assertEquals(CommandState.FAILED.name(), end.getState());
        String waitMessage = statusOf(step(first, CommandType.FLUX_WAIT_READY)).getMessage();
        assertTrue(waitMessage.contains("Flux could not apply dependency keda/keda of HelmRelease " + NS + "/" + rel
                + ": InstallFailed: Helm install failed for release keda/keda with chart keda@2.18.0: "
                + "CustomResourceDefinition \"scaledobjects.keda.sh\" exists and cannot be imported into the current "
                + "release (RetriesExceeded: Failed to install after 1 attempt(s))"), waitMessage);
        assertEquals(CommandState.PENDING.name(), stateOf(first, CommandType.AMBARI_VIEW_PROVISION),
                "no post-deploy step without a ready release");
        verify(k8s, never()).requestFluxReset(anyString(), anyString());
        assertNotNull(published("main", releaseDir() + "/dependencies/keda-helmrelease.yaml"));
        List<String> committed = commitsOn("main");

        // The operator removes the conflicting CRD and deploys the release again. Nothing new is committed, so Flux,
        // which retries neither KEDA nor the release on its own, is asked to try KEDA again: the release itself it
        // never gave up on.
        fluxInstallsKedaThenTheReleaseWhenAskedToTryAgain();
        String again = planWithCommandsUrl(requestWithDependencies());
        run(again);

        assertTrue(statusOf(step(again, CommandType.GITOPS_COMMIT)).getMessage().contains("nothing committed"));
        verify(k8s, times(1)).requestFluxReset("keda", "keda");
        verify(k8s, never()).requestFluxReset(NS, rel);
        assertEquals(CommandState.SUCCEEDED.name(), stateOf(again, CommandType.FLUX_WAIT_READY),
                statusOf(step(again, CommandType.FLUX_WAIT_READY)).getMessage());
        assertNotEquals(CommandState.PENDING.name(), stateOf(again, CommandType.AMBARI_VIEW_PROVISION),
                "the post-deploy steps run once the release is ready");
        assertEquals(committed, commitsOn("main"));
    }

    @Test
    void theKeytabWebhookIsNeitherPreparedNorPlannedWhenTheCommitLeavesItOut() {
        List<String> webhookPrepared = new ArrayList<>();
        service.keytabWebhookPreparation = headers -> webhookPrepared.add("keytab-webhook");

        String id = planWithCommandsUrl(requestWithDependencies());

        assertTrue(webhookPrepared.isEmpty(), "the default PRE_PROVISIONED mode uses no webhook");
        Map<String, Object> steps = dependencyStepsOf(paramsOf(step(id, CommandType.GITOPS_COMMIT)));
        assertEquals(List.of("keda"), new ArrayList<>(steps.keySet()));
    }

    @Test
    void nothingIsCommittedWhenADependencyCannotBePrepared() throws Exception {
        doThrow(new IllegalStateException("secrets is forbidden in namespace keda"))
                .when(helm).ensureImagePullSecretFromRepo(eq("charts"), eq("keda"), anyString(), any());
        String id = planWithCommandsUrl(requestWithDependencies());

        CommandStatusEntity end = run(id);

        assertEquals(CommandState.FAILED.name(), end.getState());
        String commitMessage = statusOf(step(id, CommandType.GITOPS_COMMIT)).getMessage();
        assertTrue(commitMessage.contains("dependency keda could not be prepared in namespace keda, so nothing was "
                + "committed to Git: secrets is forbidden in namespace keda"), commitMessage);
        assertEquals(List.of(seedCommit), commitsOn("main"));
        verify(k8s, never()).getFluxHelmRelease(anyString(), anyString());
    }

    // ------------------------------------------------------------------------------------------------ Git credentials

    private static final String TOKEN = "glpat-Token-Of-The-Deploy-77";
    private static final String SSH_KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAKEY\n"
            + "-----END OPENSSH PRIVATE KEY-----\n";

    private GitCredentialService gitCredentials;

    /** A credential store of its own, shared by the plan and the commit as Ambari's is. */
    private Map<String, String> credentialStore(boolean accepts) throws Exception {
        Map<String, String> stored = new LinkedHashMap<>();
        Map<String, String> hosts = new LinkedHashMap<>();
        GitCredentialService credentials = mock(GitCredentialService.class);
        gitCredentials = credentials;
        when(credentials.storeSecret(anyString(), anyString(), any())).thenAnswer(inv -> {
            if (!accepts) return null;
            String alias = inv.getArgument(0);
            stored.put(alias, inv.getArgument(1));
            hosts.put(alias, GitCredentialService.hostOf(inv.getArgument(2)));
            return alias;
        });
        when(credentials.resolveSecret(anyString())).thenAnswer(inv -> stored.get((String) inv.getArgument(0)));
        when(credentials.boundHost(anyString())).thenAnswer(inv -> hosts.get((String) inv.getArgument(0)));
        when(credentials.removeSecret(anyString())).thenAnswer(inv -> {
            stored.remove((String) inv.getArgument(0));
            hosts.remove((String) inv.getArgument(0));
            return true;
        });
        when(credentials.promote(anyString(), anyString())).thenAnswer(inv -> {
            String pending = inv.getArgument(0);
            String alias = inv.getArgument(1);
            if (!accepts || !stored.containsKey(pending)) return null;
            stored.put(alias, stored.remove(pending));
            hosts.put(alias, hosts.remove(pending));
            return alias;
        });
        service.gitCredentialService = credentials;
        Field backendField = CommandService.class.getDeclaredField("fluxGitOpsBackend");
        backendField.setAccessible(true);
        Object backend = backendField.get(service);
        Field credentialsField = FluxGitOpsBackend.class.getDeclaredField("gitCredentialService");
        credentialsField.setAccessible(true);
        credentialsField.set(backend, credentials);
        return stored;
    }

    @Test
    void theGitTokenAndSshKeyAreKeptInTheCredentialStoreAndNeverWithTheDeploy() throws Exception {
        Map<String, String> stored = credentialStore(true);
        fluxAppliesTheCommit("True", "InstallSucceeded", "Helm install succeeded");
        HelmDeployRequest request = request("FLUX_GITOPS", "main");
        request.getGit().setAuthToken(TOKEN);
        request.getGit().setSshKey(SSH_KEY);

        String id = service.gitOpsDeploy(request, "charts", "0.0.1", null, null, null, null);
        awaitEnd(id);

        String alias = GitCredentialService.releaseAlias(ctx.getInstanceName(), NS, rel);
        String pending = GitCredentialService.pendingAlias(ctx.getInstanceName(), NS, rel);
        String tokenAlias = GitCredentialService.pendingTokenAlias(ctx.getInstanceName(), NS, rel);
        verify(gitCredentials).storeSecret(pending, SSH_KEY, repoUrl);
        verify(gitCredentials).storeSecret(tokenAlias, TOKEN, repoUrl);
        // Once the commit reached Git, the SSH key is the release's; the token served that commit only.
        assertEquals(Map.of(alias, SSH_KEY), stored, "one copy, under the release's alias");
        List<String> persisted = new ArrayList<>();
        persisted.add(CommandUtils.resolveParamsPayload(dataStore.find(CommandEntity.class, id).getParamsJson()));
        for (String stepId : stepIds(id)) {
            persisted.add(CommandUtils.resolveParamsPayload(dataStore.find(CommandEntity.class, stepId).getParamsJson()));
        }
        for (String params : persisted) {
            if (params == null) continue;
            assertFalse(params.contains(TOKEN) || params.contains("KEY-----"), "no Git secret in " + params);
            assertFalse(HelmSetValues.redactJson(params).contains(TOKEN), "nor in what is logged of it");
        }
        Map<String, Object> commitParams = paramsOf(step(id, CommandType.GITOPS_COMMIT));
        @SuppressWarnings("unchecked")
        Map<String, Object> git = (Map<String, Object>) commitParams.get("git");
        assertEquals(pending, git.get("credentialAlias"));
        assertEquals(tokenAlias, git.get("authTokenAlias"));
        // The commit pushed with them, then made the SSH key the release's.
        assertEquals(CommandState.SUCCEEDED.name(), stateOf(id, CommandType.GITOPS_COMMIT),
                statusOf(step(id, CommandType.GITOPS_COMMIT)).getMessage());
        assertNotNull(published("main", releaseDir() + "/helmrelease.yaml"));
        assertEquals(alias, dataStore.find(K8sReleaseEntity.class, K8sReleaseEntity.idOf(NS, rel)).getGitCredentialAlias());
    }

    @Test
    void everyDeployOfAReleaseKeepsItsNewGitCredentialInPlaceUntilItReachesGit() throws Exception {
        Map<String, String> stored = credentialStore(true);
        String pending = GitCredentialService.pendingAlias(ctx.getInstanceName(), NS, rel);
        String tokenAlias = GitCredentialService.pendingTokenAlias(ctx.getInstanceName(), NS, rel);
        HelmDeployRequest keyAndToken = request("FLUX_GITOPS", "main");
        keyAndToken.getGit().setSshKey(SSH_KEY);
        keyAndToken.getGit().setAuthToken(TOKEN);
        plan(keyAndToken, true);
        assertEquals(Map.of(pending, SSH_KEY, tokenAlias, TOKEN), stored);

        HelmDeployRequest rotated = request("FLUX_GITOPS", "main");
        rotated.getGit().setAuthToken("glpat-Rotated-Token-78");
        String id = plan(rotated, true);
        plan(request("FLUX_GITOPS", "main"), true);

        assertEquals(Map.of(pending, "glpat-Rotated-Token-78"), stored, "one copy for the release, the latest");
        @SuppressWarnings("unchecked")
        Map<String, Object> git = (Map<String, Object>) paramsOf(step(id, CommandType.GITOPS_COMMIT)).get("git");
        assertEquals(pending, git.get("credentialAlias"));
        verify(gitCredentials).storeSecret(pending, "glpat-Rotated-Token-78", repoUrl);
        verify(gitCredentials, times(3)).storeSecret(anyString(), anyString(), any());
    }

    @Test
    void aDeployWithANewTokenWhoseCommitFailsLeavesTheReleaseWithItsWorkingToken() throws Exception {
        Map<String, String> stored = credentialStore(true);
        fluxAppliesTheCommit("True", "InstallSucceeded", "Helm install succeeded");
        String alias = GitCredentialService.releaseAlias(ctx.getInstanceName(), NS, rel);
        HelmDeployRequest first = request("FLUX_GITOPS", "main");
        first.getGit().setAuthToken(TOKEN);
        String id = service.gitOpsDeploy(first, "charts", "0.0.1", null, null, null, null);
        awaitEnd(id);
        assertEquals(CommandState.SUCCEEDED.name(), stateOf(id, CommandType.GITOPS_COMMIT),
                statusOf(step(id, CommandType.GITOPS_COMMIT)).getMessage());
        assertEquals(Map.of(alias, TOKEN), stored);

        // Moved to a Git server the commit cannot reach, with a new token: as with a mistyped one, the commit fails.
        HelmDeployRequest moved = request("FLUX_GITOPS", "main");
        moved.getGit().setRepoUrl("https://127.0.0.1:9/platform/gitops.git");
        moved.getGit().setAuthToken("glpat-Mistyped-Token-79");
        id = service.gitOpsDeploy(moved, "charts", "0.0.1", null, null, null, null);
        awaitEnd(id);
        assertEquals(CommandState.FAILED.name(), stateOf(id, CommandType.GITOPS_COMMIT));

        assertEquals(TOKEN, stored.get(alias), "the token the release pushes with is kept");
        assertEquals("", gitCredentials.boundHost(alias), "for the repository the release is deployed from");
        K8sReleaseEntity recorded = dataStore.find(K8sReleaseEntity.class, K8sReleaseEntity.idOf(NS, rel));
        assertEquals(alias, recorded.getGitCredentialAlias());
        assertEquals(repoUrl, recorded.getGitRepoUrl());
        // So the release can still be redeployed without its token, and removed from Git.
        id = service.gitOpsDeploy(request("FLUX_GITOPS", "main"), "charts", "0.0.1", null, null, null, null);
        awaitEnd(id);
        assertEquals(CommandState.SUCCEEDED.name(), stateOf(id, CommandType.GITOPS_COMMIT),
                statusOf(step(id, CommandType.GITOPS_COMMIT)).getMessage());
        Field backendField = CommandService.class.getDeclaredField("fluxGitOpsBackend");
        backendField.setAccessible(true);
        ((FluxGitOpsBackend) backendField.get(service)).checkRemovable(recorded);
    }

    @Test
    void aGitOpsDeployWhoseGitCredentialCannotBeStoredIsRefused() throws Exception {
        credentialStore(false);
        HelmDeployRequest request = request("FLUX_GITOPS", "main");
        request.getGit().setAuthToken(TOKEN);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.gitOpsDeploy(request, "charts", "0.0.1", null, null, null, null));

        assertTrue(refused.getMessage().startsWith("The Git access token of this GitOps deploy could not be stored in "
                + "Ambari's credential store, so the deploy was not started"), refused.getMessage());
        assertTrue(dataStore.findAll(CommandEntity.class, null).isEmpty(), "nothing was planned");
    }

    @Test
    void aGitOpsDeployCannotNameACredentialOfAmbariThatIsNotAGitCredential() throws Exception {
        Map<String, String> stored = credentialStore(true);
        // Ambari's credential store holds more than the view's Git credentials.
        stored.put("ambari.db.password", "Db-Pass-1");
        fluxAppliesTheCommit("True", "InstallSucceeded", "Helm install succeeded");

        HelmDeployRequest foreignAlias = request("FLUX_GITOPS", "main");
        foreignAlias.getGit().setRepoUrl("https://git.example.org/platform/gitops.git");
        foreignAlias.getGit().setCredentialAlias("ambari.db.password");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.gitOpsDeploy(foreignAlias, "charts", "0.0.1", null, null, null, null));
        assertTrue(refused.getMessage().startsWith("The credential alias 'ambari.db.password' does not hold a Git "
                + "credential saved by KDPS"), refused.getMessage());

        // The token alias is the view's own: one sent with the deploy is not used.
        HelmDeployRequest foreignTokenAlias = request("FLUX_GITOPS", "main");
        foreignTokenAlias.getGit().setRepoUrl("https://git.example.org/platform/gitops.git");
        foreignTokenAlias.getGit().setAuthTokenAlias("ambari.db.password");
        refused = assertThrows(IllegalArgumentException.class,
                () -> service.gitOpsDeploy(foreignTokenAlias, "charts", "0.0.1", null, null, null, null));
        assertEquals("GitOps over HTTPS requires authToken or sshKey", refused.getMessage());
        assertTrue(dataStore.findAll(CommandEntity.class, null).isEmpty(), "nothing was planned");

        HelmDeployRequest withToken = request("FLUX_GITOPS", "main");
        withToken.getGit().setAuthToken(TOKEN);
        withToken.getGit().setAuthTokenAlias("ambari.db.password");
        String id = service.gitOpsDeploy(withToken, "charts", "0.0.1", null, null, null, null);
        awaitEnd(id);
        assertEquals(CommandState.SUCCEEDED.name(), stateOf(id, CommandType.GITOPS_COMMIT),
                statusOf(step(id, CommandType.GITOPS_COMMIT)).getMessage());
        @SuppressWarnings("unchecked")
        Map<String, Object> git = (Map<String, Object>) paramsOf(step(id, CommandType.GITOPS_COMMIT)).get("git");
        assertEquals(GitCredentialService.pendingAlias(ctx.getInstanceName(), NS, rel), git.get("credentialAlias"));
        assertEquals(TOKEN, stored.get(GitCredentialService.releaseAlias(ctx.getInstanceName(), NS, rel)),
                "the token, stored by the view");
        assertNull(git.get("authTokenAlias"), "the alias the request named is not kept");

        verify(gitCredentials, never()).resolveSecret("ambari.db.password");
    }

    // ------------------------------------------------------------------------------------------------ status

    @Test
    void theStatusOfAnUnknownCommandIsNotFound() throws Exception {
        assertNull(service.getStatus("3f0c9a52e7d14b6c"), "a commit SHA, as earlier GitOps deploys returned");

        Field instances = CommandService.class.getDeclaredField("INSTANCES");
        instances.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentMap<String, CommandService> registry = (ConcurrentMap<String, CommandService>) instances.get(null);
        registry.put(ctx.getInstanceName(), service);
        try {
            Response response = new CommandResource(ctx).getStatus("3f0c9a52e7d14b6c");
            assertEquals(404, response.getStatus());
        } finally {
            registry.remove(ctx.getInstanceName());
        }
    }

    @Test
    void aSubmittedGitOpsDeployReturnsAnOperationPolledLikeAnyOther() throws Exception {
        fluxAppliesTheCommit("True", "InstallSucceeded", "Helm install succeeded");

        String id = service.submitDeploy(request("FLUX_GITOPS", "main"), "charts", "0.0.1", null, null, null, null, null);

        CommandStatus status = service.getStatus(id);
        assertNotNull(status, "a command id, not a commit");
        assertEquals(CommandType.K8S_MANAGER_RELEASE_DEPLOY, status.type);
        assertEquals("Install release " + rel + " with GitOps", status.message);
        awaitEnd(id);
        assertEquals(CommandState.SUCCEEDED.name(), stateOf(id, CommandType.FLUX_WAIT_READY));
        assertNotNull(published("main", releaseDir() + "/helmrelease.yaml"));
    }

    // ------------------------------------------------------------------------------------------------ secrets at rest

    private static final String APP_SECRET = "App-Secret-Key-751";
    private static final String FORM_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----\nMIIE-form-751\n-----END PRIVATE KEY-----";
    private static final String ACCESS_KEY = "AKIA-751";
    private static final String OIDC_SECRET = "Oidc-Client-Secret-751";

    /** The wizard's form of the test service holding secrets: a password, a pasted private key, a sensitive text. */
    private Map<String, Object> secretsForm() {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("app", new LinkedHashMap<>(Map.of("secretKey", APP_SECRET)));
        form.put("ingress", new LinkedHashMap<>(Map.of("host", "zz.example.org",
                "tlsUpload", new LinkedHashMap<>(Map.of("key", FORM_PRIVATE_KEY)))));
        form.put("storage", new LinkedHashMap<>(Map.of("accessKey", ACCESS_KEY)));
        return form;
    }

    /** The operation and its steps. */
    private List<String> commandIds(String rootId) {
        List<String> ids = new ArrayList<>(List.of(rootId));
        ids.addAll(stepIds(rootId));
        return ids;
    }

    private Path paramsFile(String commandId) {
        String stored = dataStore.find(CommandEntity.class, commandId).getParamsJson();
        assertTrue(stored.startsWith("@@file:"), commandId + ": " + stored);
        return Path.of(stored.substring("@@file:".length()));
    }

    @Test
    void theParametersOfAPlannedDeployAreOwnerOnlyEncryptedAndKeepNoFormSecretThatNoStepReads() throws Exception {
        HelmDeployRequest request = request("DIRECT_HELM", "main");
        request.setServiceKey("ZZTESTSECRETS");
        request.setFormValues(secretsForm());

        String id = plan(request, false);

        for (String commandId : commandIds(id)) {
            Path file = paramsFile(commandId);
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
            String onDisk = Files.readString(file);
            for (String secret : List.of(BIND_PASSWORD, APP_SECRET, "MIIE-form-751", ACCESS_KEY)) {
                assertFalse(onDisk.contains(secret), secret + " on disk for " + commandId);
            }
            String params = CommandUtils.resolveParamsPayload(dataStore.find(CommandEntity.class, commandId).getParamsJson());
            for (String secret : List.of(APP_SECRET, "MIIE-form-751", ACCESS_KEY)) {
                assertFalse(params.contains(secret), secret + " kept for " + commandId);
            }
        }
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(paramsFile(id).getParent())));
        Map<String, Object> root = paramsOf(dataStore.find(CommandEntity.class, id));
        assertTrue(String.valueOf(root.get("_ov")).contains(BIND_PASSWORD), "the Helm install still gets the profile's password");
        assertEquals("zz.example.org", ((Map<?, ?>) ((Map<?, ?>) root.get("formValues")).get("ingress")).get("host"));
    }

    @Test
    void aFailedOperationWithEncryptedParametersIsResumedAndItsFilesGoOnceItSucceeds() throws Exception {
        SecurityProfileEntity sso = new SecurityProfileEntity();
        sso.setId("corp-sso");
        sso.setViewInstance(ctx.getInstanceName());
        sso.setProfileJson("{\"mode\":\"oidc\",\"oidc\":{\"source\":\"external\",\"clientId\":\"zz-client\","
                + "\"clientSecret\":\"" + OIDC_SECRET + "\",\"issuerUrl\":\"https://sso.example.org/realms/corp\"}}");
        dataStore.store(sso);
        fluxAppliesTheCommit("False", "InstallFailed", "Helm install failed: exceeded quota");
        HelmDeployRequest request = request("FLUX_GITOPS", "main");
        request.setServiceKey("ZZTESTSECRETS");
        request.setSecurityProfile("corp-sso");
        request.setFormValues(secretsForm());
        String id = plan(request, true);
        assertFalse(paramsOf(step(id, CommandType.OIDC_CREATE_SECRET)).containsKey("oidcExternalClientSecret"),
                "the step reads the client secret from the profile when it runs");

        assertEquals(CommandState.FAILED.name(), run(id).getState());

        verify(k8s).createOrUpdateOpaqueSecret(eq(NS), eq(rel + "-oidc-client"), argThat((Map<String, byte[]> data) ->
                OIDC_SECRET.equals(new String(data.get("client_secret"), StandardCharsets.UTF_8))));
        List<Path> files = new ArrayList<>();
        for (String commandId : commandIds(id)) {
            Path file = paramsFile(commandId);
            files.add(file);
            String onDisk = Files.readString(file);
            assertFalse(onDisk.contains(OIDC_SECRET) || onDisk.contains(APP_SECRET), "encrypted: " + commandId);
        }

        fluxInstallsTheReleaseWhenAskedToTryAgain();
        service.resume(id, null);
        CommandStatusEntity end = awaitEnd(id);

        assertEquals(CommandState.SUCCEEDED.name(), end.getState(), end.getMessage());
        for (Path file : files) {
            assertFalse(Files.exists(file), "deleted once the operation succeeded: " + file);
        }
        for (String commandId : commandIds(id)) {
            assertEquals(Map.of("_removed", true), paramsOf(dataStore.find(CommandEntity.class, commandId)), commandId);
        }
        assertEquals(100, service.getStatus(id).percent);
    }

    @Test
    void anOperationWhoseParametersWereDeletedIsNotResumed() throws Exception {
        fluxAppliesTheCommit("False", "InstallFailed", "Helm install failed: exceeded quota");
        String id = plan(request("FLUX_GITOPS", "main"), true);
        assertEquals(CommandState.FAILED.name(), run(id).getState());
        CommandEntity root = dataStore.find(CommandEntity.class, id);
        root.setParamsJson("{\"_removed\":true}");
        dataStore.store(root);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> service.resume(id, null));

        assertTrue(refused.getMessage().startsWith("The saved settings of this operation are no longer available"),
                refused.getMessage());
    }

    // ------------------------------------------------------------------------------------------------ names

    /** Asserts that a deploy is refused with that message before anything is planned, created or committed. */
    private void assertRefusedOnSubmit(String expectedMessage, HelmDeployRequest request) throws Exception {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.submitDeploy(request, "charts", "0.0.1", null, null, null, null, null));

        assertTrue(refused.getMessage().startsWith(expectedMessage), refused.getMessage());
        assertEquals(List.of(seedCommit), commitsOn("main"), "nothing reached Git");
        assertTrue(dataStore.findAll(CommandEntity.class, null).isEmpty(), "nothing was planned");
        verify(k8s, never()).createNamespace(anyString());
        assertTrue(secretPayloads.isEmpty(), "no values Secret either");
    }

    @Test
    void aGitOpsDeployWithANameHelmWouldRefuseChangesNothing() throws Exception {
        HelmDeployRequest uppercase = request("FLUX_GITOPS", "main");
        uppercase.setReleaseName("Trino_prod");
        assertRefusedOnSubmit("Release name 'Trino_prod' is not valid", uppercase);

        HelmDeployRequest traversal = request("FLUX_GITOPS", "main");
        traversal.setReleaseName("../x");
        assertRefusedOnSubmit("Release name '../x' is not valid", traversal);

        HelmDeployRequest namespace = request("FLUX_GITOPS", "main");
        namespace.setNamespace("../team-b");
        assertRefusedOnSubmit("Namespace '../team-b' is not valid", namespace);
    }

    @Test
    void aGitOpsDeployWhoseChartVersionOrDependencyWouldAddToTheManifestsChangesNothing() throws Exception {
        HelmDeployRequest chart = request("FLUX_GITOPS", "main");
        chart.setChart("example/zz-test-gitops\n---\napiVersion: rbac.authorization.k8s.io/v1\n"
                + "kind: ClusterRoleBinding");
        assertRefusedOnSubmit("Chart 'example/zz-test-gitops?---?", chart);

        // A reference only a direct deploy resolves: the HelmRelease names a chart of its chart repository.
        HelmDeployRequest oci = request("FLUX_GITOPS", "main");
        oci.setChart("oci://registry.example.org/charts/zz-test-gitops");
        assertRefusedOnSubmit("Chart 'oci://registry.example.org/charts/zz-test-gitops' is not valid", oci);

        HelmDeployRequest version = request("FLUX_GITOPS", "main");
        version.setVersion("0.0.1\nkind: Secret");
        assertRefusedOnSubmit("Chart version '0.0.1?kind: Secret' is not valid", version);

        HelmDeployRequest colon = request("FLUX_GITOPS", "main");
        colon.setVersion("a: b");
        assertRefusedOnSubmit("Chart version 'a: b' is not valid", colon);
        // (given as the query parameter the wizard uses)
        IllegalArgumentException queried = assertThrows(IllegalArgumentException.class,
                () -> service.submitDeploy(request("FLUX_GITOPS", "main"), "charts", "a: b", null, null, null, null,
                        null));
        assertTrue(queried.getMessage().startsWith("Chart version 'a: b' is not valid"), queried.getMessage());

        HelmDeployRequest dependency = fullRequest("FLUX_GITOPS");
        dependency.getDependencies().put("ldap-proxy", new LinkedHashMap<>(Map.of("chart", "ldap-proxy",
                "releaseName", "../../team-b/trino")));
        assertRefusedOnSubmit("Dependency 'ldap-proxy': release name '../../team-b/trino' is not valid", dependency);
    }

    @Test
    void aDirectDeployWithAnInvalidNameIsRefusedBeforeHelmRuns() throws Exception {
        HelmDeployRequest request = request("DIRECT_HELM", "main");
        request.setReleaseName("Trino_prod");

        // Nothing planned: no Helm step either, which would have refused the name only once the release was prepared.
        assertRefusedOnSubmit("Release name 'Trino_prod' is not valid", request);
    }

    @Test
    void theDeployEndpointAnswersAnInvalidNameWithA400AndItsRule() throws Exception {
        // doReturn, not when(...): the service's background sweep of operation files reads the same context while
        // this test stubs it, and when(...) would stub whatever that thread called last.
        doReturn(Map.of("k8s.view.working.dir", tmp.resolve("work").toString(), "view.admin.users", "admin"))
                .when(ctx).getProperties();
        doReturn("K8S-VIEW").when(ctx).getViewName();
        org.apache.ambari.view.ViewDefinition definition = ctx.getViewDefinition();
        doReturn("1.0.0").when(definition).getVersion();
        doReturn("ambari.test").when(ctx)
                .getAmbariProperty(org.apache.ambari.view.k8s.utils.AmbariLoopbackUrlResolver.LOOPBACK_HOST_PROPERTY);
        javax.ws.rs.core.HttpHeaders headers = mock(javax.ws.rs.core.HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new javax.ws.rs.core.MultivaluedHashMap<>());
        javax.ws.rs.core.UriInfo uriInfo = mock(javax.ws.rs.core.UriInfo.class);
        when(uriInfo.getBaseUri()).thenReturn(java.net.URI.create("http://ambari.test:8080/api/v1/"));
        HelmDeployRequest request = request("FLUX_GITOPS", "main");
        request.setReleaseName("Trino_prod");

        Field instances = CommandService.class.getDeclaredField("INSTANCES");
        instances.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentMap<String, CommandService> registry = (ConcurrentMap<String, CommandService>) instances.get(null);
        registry.put(ctx.getInstanceName(), service);
        try {
            Response response = new CommandResource(ctx, null, headers)
                    .submitDeploy(request, "charts", "0.0.1", null, headers, uriInfo);

            assertEquals(400, response.getStatus());
            String error = String.valueOf(((Map<?, ?>) response.getEntity()).get("error"));
            assertTrue(error.startsWith("Release name 'Trino_prod' is not valid: Helm accepts at most 53"), error);
        } finally {
            registry.remove(ctx.getInstanceName());
        }
        assertEquals(List.of(seedCommit), commitsOn("main"));
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
