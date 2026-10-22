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
import org.apache.ambari.view.k8s.security.TestMasterKeys;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.gitops.ChartFetcher;
import org.apache.ambari.view.k8s.service.gitops.GitOpsProfile;
import org.apache.ambari.view.k8s.service.gitops.GitOpsProfileStore;
import org.apache.ambari.view.k8s.service.gitops.ProfilePreview;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.SecurityProfileEntity;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GitOps deploys written with a file profile: the chart as published under the profile's chart path, the files the
 * profile renders around it, no Flux manifest, the secret values kept in the cluster, and the profile's delivery.
 */
public class FluxGitOpsBackendProfileTest {

    private static final String BIND_PASSWORD = "Bind-Pass-42";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ViewContext ctx;
    private DataStore ds;
    private KubernetesService k8s;
    private Path bareRepo;
    private String repoUrl;
    private Path fluxRoot;
    private GitOpsProfileStore profiles;
    private final FakePullRequests pullRequests = new FakePullRequests();
    private final Map<String, String> secretPayloads = new ConcurrentHashMap<>();
    private final Map<String, K8sReleaseEntity> releases = new ConcurrentHashMap<>();
    /** Ambari's credential store, as a deploy keeps the release's Git token in it. */
    private final Map<String, String> storedCredentials = new ConcurrentHashMap<>();
    private org.apache.ambari.view.k8s.service.GitCredentialService credentials;
    /** The chart the fake repository publishes, by path inside the chart. */
    private Map<String, byte[]> publishedChart;

    @Before
    public void setUp() throws Exception {
        bareRepo = tmp.getRoot().toPath().resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bareRepo.toFile()).call().close();
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
            Files.createDirectories(seed.resolve("docs"));
            Files.writeString(seed.resolve("docs/runbook.md"), "# Runbook\n");
            g.add().addFilepattern(".").call();
            g.commit().setMessage("init").setAuthor("seed", "seed@example.com").call();
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

        HelmRepoEntity chartRepo = new HelmRepoEntity();
        chartRepo.setId("charts");
        chartRepo.setType("OCI");
        chartRepo.setUrl("registry.example.org/charts");
        chartRepo.setAuthMode("anonymous");
        when(ds.find(eq(HelmRepoEntity.class), eq("charts"))).thenAnswer(inv -> chartRepo);

        SecurityProfileEntity ldap = new SecurityProfileEntity();
        ldap.setId("corp-ldap");
        ldap.setViewInstance("kdps");
        ldap.setDefault(true);
        ldap.setProfileJson("{\"mode\":\"ldap\",\"ldap\":{\"url\":\"ldap://dir.example.com\","
                + "\"bindDn\":\"cn=kdps,dc=example,dc=com\",\"bindPassword\":\"" + BIND_PASSWORD + "\"}}");
        when(ds.findAll(eq(SecurityProfileEntity.class), any())).thenReturn(List.of(ldap));

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
        doAnswer(inv -> {
            secretPayloads.remove(inv.getArgument(0) + "/" + inv.getArgument(1));
            return null;
        }).when(k8s).deleteSecret(anyString(), anyString());

        credentials = mock(org.apache.ambari.view.k8s.service.GitCredentialService.class);
        Map<String, String> hosts = new ConcurrentHashMap<>();
        when(credentials.storeSecret(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            storedCredentials.put(inv.getArgument(0), inv.getArgument(1));
            hosts.put(inv.getArgument(0), org.apache.ambari.view.k8s.service.GitCredentialService.hostOf(inv.getArgument(2)));
            return inv.getArgument(0);
        });
        when(credentials.resolveSecret(anyString())).thenAnswer(inv -> storedCredentials.get((String) inv.getArgument(0)));
        when(credentials.boundHost(anyString())).thenAnswer(inv -> hosts.get((String) inv.getArgument(0)));
        when(credentials.removeSecret(anyString())).thenAnswer(inv -> storedCredentials.remove((String) inv.getArgument(0)) != null);

        profiles = new GitOpsProfileStore(new InstanceSettings(new InMemoryDataStore(),
                TestMasterKeys.encryptionWithoutKey()));
        publishedChart = new LinkedHashMap<>();
        publishedChart.put("Chart.yaml", bytes("name: trino\nversion: 1.43.20\n"));
        publishedChart.put("templates/deployment.yaml", bytes("kind: Deployment\n"));
        // A published chart's own default, which happens to be the deploy's secret: not the deploy's doing.
        publishedChart.put("values.yaml", bytes("example:\n  password: " + BIND_PASSWORD + "\n"));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static GitOpsProfile.FileRule rule(String path, String mode, String content) {
        GitOpsProfile.FileRule r = new GitOpsProfile.FileRule();
        r.path = path;
        r.mode = mode;
        r.content = content;
        return r;
    }

    /** A chart folder, one values file per environment, and a file the team then owns. */
    private GitOpsProfile environmentsProfile(String delivery) {
        GitOpsProfile p = new GitOpsProfile();
        p.name = "environments";
        p.chartPath = "chart";
        p.delivery.mode = delivery;
        p.files.add(rule("environments/{{ namespace }}/values.yaml", GitOpsProfile.MODE_ALWAYS, "{{ values | toYaml }}\n"));
        p.files.add(rule("OWNERS.md", GitOpsProfile.MODE_CREATE_IF_MISSING,
                "# {{ release }}\nimage tag: ${IMAGE_TAG}\n"
                        + "secretValues: {{ secretValues.secretName }}\n"));
        p.protect.add("docs/**");
        return profiles.save(p);
    }

    private FluxGitOpsBackend backend() throws Exception {
        FluxGitOpsBackend backend = new FluxGitOpsBackend(fluxRoot, k8s, ctx);
        java.lang.reflect.Field field = FluxGitOpsBackend.class.getDeclaredField("gitCredentialService");
        field.setAccessible(true);
        field.set(backend, credentials);
        backend.setPullRequests(pullRequests);
        backend.setProfileStore(profiles);
        backend.setChartFetcher(new ChartFetcher(dir -> { }) {
            @Override
            public Map<String, byte[]> fetch(String repositoryUrl, String chart, String version, Path workDir) {
                assertEquals("oci://registry.example.org/charts", repositoryUrl);
                return new LinkedHashMap<>(publishedChart);
            }
        }, repoId -> { });
        return backend;
    }

    private HelmDeployRequest request() {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace("analytics");
        request.setReleaseName("trino");
        request.setChart("trino");
        request.setVersion("1.43.20");
        request.setRepoId("charts");
        request.setServiceKey("TRINO");
        request.setDeploymentMode("FLUX_GITOPS");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("server", new LinkedHashMap<>(Map.of("workers", 2)));
        request.setValues(values);
        HelmDeployRequest.GitOptions git = new HelmDeployRequest.GitOptions();
        git.setRepoUrl(repoUrl);
        git.setBaseBranch("main");
        git.setProfile("environments");
        git.setAuthToken("t0k");
        request.setGit(git);
        return request;
    }

    /** A file as published on a remote branch, or null. */
    private String published(String branch, String path) throws Exception {
        Path clone = tmp.getRoot().toPath().resolve("verify-" + System.nanoTime());
        try (Git ignored = org.apache.ambari.view.k8s.utils.TestGitClones.clone(repoUrl, branch, clone)) {
            Path file = clone.resolve(path);
            return Files.exists(file) ? Files.readString(file) : null;
        }
    }

    private List<String> remoteBranches() throws Exception {
        try (Git g = Git.open(bareRepo.toFile())) {
            return g.branchList().call().stream().map(Ref::getName).map(n -> n.replace("refs/heads/", "")).toList();
        }
    }

    @Test
    public void theChartAndTheFilesAroundItAreWrittenAndNoFluxManifest() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);

        backend().apply(request(), null);

        assertEquals("kind: Deployment\n", published("main", "chart/templates/deployment.yaml"));
        assertNotNull(published("main", "chart/Chart.yaml"));
        String instanceValues = published("main", "environments/analytics/values.yaml");
        assertTrue(instanceValues, instanceValues.contains("workers: 2"));
        assertFalse("no secret in the instance's values", instanceValues.contains(BIND_PASSWORD));
        String owners = published("main", "OWNERS.md");
        assertTrue(owners, owners.startsWith("# trino\nimage tag: ${IMAGE_TAG}\n"));
        assertEquals("# Runbook\n", published("main", "docs/runbook.md"));
        assertNull("no Flux manifest", published("main", "clusters/default/analytics/trino/helmrelease.yaml"));

        // The secret values stay in the cluster, in the Secret the team's file names.
        String secretName = owners.substring(owners.indexOf("secretValues: ") + "secretValues: ".length()).trim();
        assertTrue(secretPayloads.get("analytics/" + secretName).contains(BIND_PASSWORD));
        assertEquals("environments", releases.values().iterator().next().getGitProfile());
    }

    @Test
    public void aFileTheTeamOwnsIsLeftAsItIsAndTheChartIsReplacedAsAWhole() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        backend().apply(request(), null);
        Path edit = tmp.newFolder("edit").toPath();
        try (Git g = org.apache.ambari.view.k8s.utils.TestGitClones.clone(repoUrl, "main", edit)) {
            Files.writeString(edit.resolve("OWNERS.md"), "edited by the team\n");
            g.add().addFilepattern(".").call();
            g.commit().setMessage("team edit").setAuthor("t", "t@example.com").call();
            g.push().call();
        }
        publishedChart.remove("templates/deployment.yaml");
        publishedChart.put("templates/statefulset.yaml", bytes("kind: StatefulSet\n"));
        HelmDeployRequest again = request();
        again.getValues().put("server", new LinkedHashMap<>(Map.of("workers", 3)));

        backend().apply(again, null);

        assertEquals("edited by the team\n", published("main", "OWNERS.md"));
        assertTrue(published("main", "environments/analytics/values.yaml").contains("workers: 3"));
        assertNull(published("main", "chart/templates/deployment.yaml"));
        assertEquals("kind: StatefulSet\n", published("main", "chart/templates/statefulset.yaml"));
    }

    @Test
    public void aPullRequestDeliveryLeavesTheBaseBranchAndUsesTheProfilesBranchPrefix() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_PULL_REQUEST);

        backend().apply(request(), null);

        assertNull("nothing on the base branch before the merge", published("main", "environments/analytics/values.yaml"));
        assertEquals(1, pullRequests.opened.size());
        FakePullRequests.Opened pr = pullRequests.opened.get(0);
        assertEquals("main", pr.base());
        assertTrue(pr.head(), pr.head().startsWith("kdps/trino-"));
        assertTrue(remoteBranches().contains(pr.head()));
        assertNotNull(published(pr.head(), "environments/analytics/values.yaml"));
    }

    @Test
    public void aDependencyTheDeployWouldInstallStopsItBeforeAnythingIsPushed() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        HelmDeployRequest request = request();
        LinkedHashMap<String, Object> dependencies = new LinkedHashMap<>();
        dependencies.put("ldap-proxy", new LinkedHashMap<>(Map.of("chart", "ldap-proxy", "releaseName", "ldap-proxy",
                "chartVersion", "0.1.0")));
        request.setDependencies(dependencies);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> backend().apply(request, null));

        assertTrue(refused.getMessage(), refused.getMessage().contains("writes only the release's own chart"));
        assertNull(published("main", "environments/analytics/values.yaml"));
    }

    @Test
    public void aProfileThatWouldWriteASecretIsRefusedBeforeAnythingIsPushed() throws Exception {
        GitOpsProfile p = environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        p.files.add(rule("leak.txt", GitOpsProfile.MODE_ALWAYS, "password: " + BIND_PASSWORD + "\n"));
        profiles.save(p);

        assertThrows(IllegalStateException.class, () -> backend().apply(request(), null));

        assertNull(published("main", "leak.txt"));
        assertNull(published("main", "chart/Chart.yaml"));
    }

    @Test
    public void anUnknownProfileStopsTheDeploy() {
        HelmDeployRequest request = request();
        request.getGit().setProfile("missing");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> backend().apply(request, null));
        assertTrue(refused.getMessage(), refused.getMessage().contains("'missing' does not exist"));
    }

    /** Merges a branch into another on the server, as a pull request merged by default. */
    private void mergeOnServer(String branch, String into) throws Exception {
        Path clone = tmp.newFolder("merger-" + System.nanoTime()).toPath();
        try (Git g = Git.cloneRepository().setURI(repoUrl).setBranch(into).setDirectory(clone.toFile()).call()) {
            g.merge().include(g.getRepository().resolve("refs/remotes/origin/" + branch)).setMessage("Merge")
                    .setCommit(true).call();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/" + into)).call();
        }
    }

    private HelmDeployRequest requestIn(String namespace) {
        HelmDeployRequest request = request();
        request.setNamespace(namespace);
        return request;
    }

    @Test
    public void aFileListingTheInstancesHasEveryNamespaceTheRepositoryHolds() throws Exception {
        GitOpsProfile p = environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        p.files.add(rule("namespaces.json", GitOpsProfile.MODE_ALWAYS, "{{ instances | toJson }}\n"));
        profiles.save(p);

        backend().apply(requestIn("analytics"), null);
        backend().apply(requestIn("team-b"), null);

        assertEquals("[\"analytics\",\"team-b\"]\n", published("main", "namespaces.json"));
    }

    @Test
    public void anUninstallRemovesOnlyTheFilesOfItsInstance() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        backend().apply(requestIn("analytics"), null);
        backend().apply(requestIn("team-b"), null);
        String analyticsSecret = secretPayloads.keySet().stream().filter(k -> k.startsWith("analytics/")).findFirst()
                .orElseThrow();

        backend().destroy("analytics", "trino");

        assertNull(published("main", "environments/analytics/values.yaml"));
        assertNotNull("another instance keeps its file", published("main", "environments/team-b/values.yaml"));
        assertNotNull("the chart is shared", published("main", "chart/Chart.yaml"));
        assertNotNull("the team's file stays", published("main", "OWNERS.md"));
        assertEquals("# Runbook\n", published("main", "docs/runbook.md"));
        assertFalse("the secret values KDPS kept are gone", secretPayloads.containsKey(analyticsSecret));
    }

    @Test
    public void anUninstallByPullRequestWaitsForItsMergeThenFinishes() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_PULL_REQUEST);
        backend().apply(request(), null);
        FakePullRequests.Opened deploy = pullRequests.last();
        mergeOnServer(deploy.head(), "main");
        pullRequests.merge(deploy.number());

        backend().destroy("analytics", "trino");

        FakePullRequests.Opened removal = pullRequests.last();
        assertNotEquals(deploy.number(), removal.number());
        assertTrue(removal.head(), removal.head().startsWith("kdps/trino-remove-"));
        assertNotNull("the release stays until the removal is merged", published("main", "environments/analytics/values.yaml"));
        assertNull(published(removal.head(), "environments/analytics/values.yaml"));
        K8sReleaseEntity record = releases.values().iterator().next();
        assertTrue(record.isGitPendingDelete());

        mergeOnServer(removal.head(), "main");
        pullRequests.merge(removal.number());
        backend().destroy("analytics", "trino");

        assertNull(published("main", "environments/analytics/values.yaml"));
        assertEquals("no further pull request", removal.number(), pullRequests.last().number());
    }

    @Test
    public void anUninstallBeforeTheDeploysPullRequestIsMergedClosesIt() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_PULL_REQUEST);
        backend().apply(request(), null);
        FakePullRequests.Opened deploy = pullRequests.last();

        backend().destroy("analytics", "trino");

        assertEquals("closed", pullRequests.state(deploy.number()));
        assertEquals("nothing to remove from the base branch", 1, pullRequests.opened.size());
    }

    @Test
    public void theStatusOfAReleaseNotInstalledYetSaysWhoInstallsIt() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        backend().apply(request(), null);

        org.apache.ambari.view.k8s.model.HelmReleaseDTO status = backend().status("analytics", "trino");

        assertEquals("NOT_FOUND", status.status);
        assertTrue(status.message, status.message.contains("GitOps profile environments"));
        assertFalse(status.message, status.message.contains("Flux"));
    }

    /** The files of a branch as published, by path, Git's own directory left out. */
    private Map<String, String> publishedFiles(String branch) throws Exception {
        Path clone = tmp.getRoot().toPath().resolve("verify-" + System.nanoTime());
        Map<String, String> files = new LinkedHashMap<>();
        try (Git ignored = org.apache.ambari.view.k8s.utils.TestGitClones.clone(repoUrl, branch, clone);
             java.util.stream.Stream<Path> walk = Files.walk(clone)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                String path = clone.relativize(f).toString().replace('\\', '/');
                if (!path.startsWith(".git/")) {
                    files.put(path, Files.readString(f));
                }
            }
        }
        return files;
    }

    private static ProfilePreview.PreviewFile file(ProfilePreview.Preview preview, String path) {
        return preview.files().stream().filter(f -> f.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("not in the preview: " + path));
    }

    @Test
    public void thePreviewShowsWhatTheDeployThenWrites() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        Map<String, String> before = publishedFiles("main");

        ProfilePreview.Preview preview = backend().previewProfile(request(), null, null);

        assertEquals("the preview writes nothing", before, publishedFiles("main"));
        backend().apply(request(), null);
        Map<String, String> after = publishedFiles("main");
        // Every file of the repository after the deploy, and nothing else, with the content the preview showed.
        Map<String, String> previewed = new LinkedHashMap<>();
        for (ProfilePreview.PreviewFile f : preview.files()) {
            assertNotEquals(f.path(), ProfilePreview.CHANGE_REMOVED, f.change());
            previewed.put(f.path(), f.content());
        }
        assertEquals(new java.util.TreeMap<>(after), new java.util.TreeMap<>(previewed));

        assertEquals(ProfilePreview.ORIGIN_CHART, file(preview, "chart/Chart.yaml").origin());
        ProfilePreview.PreviewFile values = file(preview, "environments/analytics/values.yaml");
        assertEquals(ProfilePreview.CHANGE_NEW, values.change());
        assertFalse("written again by every deploy", values.editable());
        assertFalse("no secret in a file of the profile", values.content().contains(BIND_PASSWORD));
        assertTrue("created once, so editable", file(preview, "OWNERS.md").editable());
        ProfilePreview.PreviewFile team = file(preview, "docs/runbook.md");
        assertEquals(ProfilePreview.ORIGIN_REPOSITORY, team.origin());
        assertTrue(team.protectedPath());
        assertTrue(preview.notes().stream().anyMatch(n -> n.contains(secretName())));
    }

    /** The values Secret of the test release, as the deploy names it. */
    private static String secretName() {
        return "trino-kdps-values";
    }

    @Test
    public void aSecondPreviewShowsWhatTheNextDeployChanges() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        backend().apply(request(), null);
        publishedChart.remove("templates/deployment.yaml");
        HelmDeployRequest again = request();
        again.getValues().put("server", new LinkedHashMap<>(Map.of("workers", 3)));

        ProfilePreview.Preview preview = backend().previewProfile(again, null, null);

        ProfilePreview.PreviewFile values = file(preview, "environments/analytics/values.yaml");
        assertEquals(ProfilePreview.CHANGE_CHANGED, values.change());
        assertTrue(values.current().contains("workers: 2"));
        assertTrue(values.content().contains("workers: 3"));
        assertEquals(ProfilePreview.CHANGE_KEPT, file(preview, "OWNERS.md").change());
        assertFalse(file(preview, "OWNERS.md").editable());
        assertEquals(ProfilePreview.CHANGE_UNCHANGED, file(preview, "chart/Chart.yaml").change());
        assertEquals(ProfilePreview.CHANGE_REMOVED, file(preview, "chart/templates/deployment.yaml").change());
    }

    @Test
    public void aTemplateErrorFailsThePreview() throws Exception {
        GitOpsProfile p = environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        p.files.get(0).content = "{{ valeus | toYaml }}\n";

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> backend().previewProfile(request(), p, null));

        assertTrue(refused.getMessage(), refused.getMessage().contains("valeus"));
    }

    @Test
    public void anEditToAFileCreatedOnceIsWhatIsCommitted() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        HelmDeployRequest request = request();
        request.getGit().setFileEdits(Map.of("OWNERS.md", "# trino\nOwned by the data team.\n"));

        backend().apply(request, null);

        assertEquals("# trino\nOwned by the data team.\n", published("main", "OWNERS.md"));
    }

    @Test
    public void anEditToAFileWrittenByEveryDeployIsRefusedBeforeAnythingIsPushed() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        HelmDeployRequest request = request();
        request.getGit().setFileEdits(Map.of("environments/analytics/values.yaml", "server:\n  workers: 9\n"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> backend().apply(request, null));

        assertTrue(refused.getMessage(), refused.getMessage().contains("written again by every deploy"));
        assertNull(published("main", "environments/analytics/values.yaml"));
        assertNull(published("main", "chart/Chart.yaml"));
    }

    @Test
    public void anEditHoldingASecretIsRefused() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        HelmDeployRequest request = request();
        request.getGit().setFileEdits(Map.of("OWNERS.md", "bindPassword: " + BIND_PASSWORD + "\n"));

        assertThrows(IllegalStateException.class, () -> backend().apply(request, null));

        assertNull(published("main", "OWNERS.md"));
    }

    @Test
    public void aReleaseKeepsTheProfileItWasWrittenWith() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        backend().apply(request(), null);
        HelmDeployRequest again = request();
        again.getGit().setProfile("flux");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> backend().checkCommittable(again, null));
        assertTrue(refused.getMessage(), refused.getMessage().contains("written to Git with the GitOps profile environments"));
        assertThrows(IllegalArgumentException.class, () -> backend().apply(again, null));

        assertNull("no Flux manifest", published("main", "clusters/default/analytics/trino/helmrelease.yaml"));
        // Named again, or not at all, the release's own profile is used.
        again.getGit().setProfile(null);
        backend().apply(again, null);
    }

    @Test
    public void thePreviewShowsWholeNumbersAsTheCommitStepWritesThem() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        HelmDeployRequest request = request();
        // A request read from JSON carries every number as a double.
        request.getValues().put("server", new LinkedHashMap<>(Map.of("workers", 2.0)));

        String values = file(backend().previewProfile(request, null, null), "environments/analytics/values.yaml").content();

        assertTrue(values, values.contains("workers: 2\n"));
    }

    /** Commits and pushes a change of the main branch, as someone working on the repository would. */
    private void pushToMain(java.util.function.Consumer<Path> change) throws Exception {
        Path edit = tmp.newFolder().toPath();
        try (Git g = org.apache.ambari.view.k8s.utils.TestGitClones.clone(repoUrl, "main", edit)) {
            change.accept(edit);
            g.add().addFilepattern(".").call();
            g.commit().setMessage("change").setAuthor("t", "t@example.com").call();
            g.push().call();
        }
    }

    private static void link(Path link, Path target) {
        try {
            Files.createDirectories(link.getParent());
            Files.createSymbolicLink(link, target);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void aLinkInTheRepositoryNeverLetsTheDeployWriteOutsideIt() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        Path outside = tmp.newFolder("outside").toPath();
        // A folder the profile writes into is a link to a directory of the host.
        pushToMain(repo -> link(repo.resolve("environments"), outside));

        assertThrows(Exception.class, () -> backend().apply(request(), null));

        try (java.util.stream.Stream<Path> written = Files.list(outside)) {
            assertEquals("nothing written outside the repository", 0, written.count());
        }
        assertNull(published("main", "chart/Chart.yaml"));
    }

    @Test
    public void thePreviewNeverShowsWhatALinkPointsTo() throws Exception {
        environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        Path secret = tmp.newFile("host-secret.txt").toPath();
        Files.writeString(secret, "TOP-SECRET-VALUE\n");
        pushToMain(repo -> link(repo.resolve("docs/secret.txt"), secret));

        ProfilePreview.Preview preview = backend().previewProfile(request(), null, null);

        for (ProfilePreview.PreviewFile f : preview.files()) {
            assertFalse(f.path(), String.valueOf(f.content()).contains("TOP-SECRET-VALUE"));
            assertFalse(f.path(), String.valueOf(f.current()).contains("TOP-SECRET-VALUE"));
        }
        // Checked out as a plain file holding the link's target, as Git does with links turned off.
        assertEquals(secret.toString(), file(preview, "docs/secret.txt").content());
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void filesOfTheProfileAndOfTheTeamLiveInTheChartFolderBesideTheChart() throws Exception {
        GitOpsProfile p = environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        p.files.add(rule("chart/environments/{{ namespace }}.yaml", GitOpsProfile.MODE_ALWAYS, "workers: 2\n"));
        profiles.save(p);
        // A file of the team in the chart folder, outside the chart's own entries.
        pushToMain(repo -> write(repo.resolve("chart/environments/NOTES.md"), "kept\n"));

        backend().apply(request(), null);

        assertEquals("workers: 2\n", published("main", "chart/environments/analytics.yaml"));
        assertEquals("kind: Deployment\n", published("main", "chart/templates/deployment.yaml"));

        // The next version of the chart drops a template: it goes, while the folders that are not the chart's stay.
        publishedChart.remove("templates/deployment.yaml");
        publishedChart.put("templates/statefulset.yaml", bytes("kind: StatefulSet\n"));
        backend().apply(request(), null);

        assertNull(published("main", "chart/templates/deployment.yaml"));
        assertEquals("kind: StatefulSet\n", published("main", "chart/templates/statefulset.yaml"));
        assertEquals("workers: 2\n", published("main", "chart/environments/analytics.yaml"));
        assertEquals("kept\n", published("main", "chart/environments/NOTES.md"));
    }

    @Test
    public void aRuleReplacingOneOfTheChartsOwnFilesIsRefusedBeforeAnythingIsPushed() throws Exception {
        GitOpsProfile p = environmentsProfile(GitOpsProfile.DELIVERY_COMMIT);
        // dashboards/ is a folder of this chart's package: the chart's, though Helm gives it no meaning.
        publishedChart.put("dashboards/overview.json", bytes("{}\n"));
        p.files.add(rule("chart/dashboards/extra.json", GitOpsProfile.MODE_ALWAYS, "{}\n"));
        profiles.save(p);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> backend().apply(request(), null));

        assertTrue(refused.getMessage(), refused.getMessage().contains("part of the chart in chart (dashboards)"));
        assertNull(published("main", "chart/Chart.yaml"));
    }
}
