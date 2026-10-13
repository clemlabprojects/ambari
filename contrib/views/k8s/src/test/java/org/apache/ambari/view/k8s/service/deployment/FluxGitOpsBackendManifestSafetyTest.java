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
import org.apache.ambari.view.k8s.service.HelmSetValues;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * What a GitOps deploy writes to Git against a local bare repository: for valid names, the manifests the view always
 * wrote; for a malformed or injected name, or a directory inside or above another release's, nothing at all; and an
 * uninstall that removes only the manifests the release wrote into its own directory.
 */
public class FluxGitOpsBackendManifestSafetyTest {

    private static final String RELEASE_DIR = "clusters/default/analytics/trino";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private DataStore ds;
    private ViewContext ctx;
    private KubernetesService k8s;
    private Path bareRepo;
    private String repoUrl;
    private Path fluxRoot;
    private String seedCommit;
    private final Map<String, String> secretPayloads = new ConcurrentHashMap<>();
    private final Map<String, K8sReleaseEntity> releases = new ConcurrentHashMap<>();

    @Before
    public void setUp() throws Exception {
        bareRepo = tmp.getRoot().toPath().resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bareRepo.toFile()).call().close();
        // No background gc after a push: it would still hold gc.log.lock when the temp directory is removed.
        try (Git bareGit = Git.open(bareRepo.toFile())) {
            org.eclipse.jgit.lib.StoredConfig bareConfig = bareGit.getRepository().getConfig();
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
        Map<String, HelmRepoEntity> chartRepos = Map.of(
                "charts", chartRepo("charts", "HTTP", "https://charts.example.com"),
                "registry", chartRepo("registry", "OCI", "registry.example.com/kdps"));
        when(ds.find(eq(HelmRepoEntity.class), anyString())).thenAnswer(inv -> chartRepos.get((String) inv.getArgument(1)));
        doAnswer(inv -> {
            K8sReleaseEntity entity = inv.getArgument(0);
            releases.put(entity.getId(), entity);
            return null;
        }).when(ds).store(any(K8sReleaseEntity.class));
        when(ds.find(eq(K8sReleaseEntity.class), anyString())).thenAnswer(inv -> releases.get((String) inv.getArgument(1)));
        when(ds.findAll(eq(K8sReleaseEntity.class), any())).thenAnswer(inv -> new ArrayList<>(releases.values()));
        doAnswer(inv -> {
            releases.remove(((K8sReleaseEntity) inv.getArgument(0)).getId());
            return null;
        }).when(ds).remove(any(K8sReleaseEntity.class));

        k8s = mock(KubernetesService.class);
        when(k8s.readOpaqueSecretKeyAsString(anyString(), anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(secretPayloads.get(inv.getArgument(0) + "/" + inv.getArgument(1))));
        doAnswer(inv -> {
            secretPayloads.put(inv.getArgument(0) + "/" + inv.getArgument(1),
                    new String((byte[]) inv.getArgument(3), StandardCharsets.UTF_8));
            return null;
        }).when(k8s).createOrUpdateOpaqueSecret(anyString(), anyString(), anyString(), any(byte[].class), anyMap(), any(), any());
    }

    private static HelmRepoEntity chartRepo(String id, String type, String url) {
        HelmRepoEntity repo = new HelmRepoEntity();
        repo.setId(id);
        repo.setType(type);
        repo.setUrl(url);
        repo.setAuthMode("anonymous");
        return repo;
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
        try (Git ignored = Git.cloneRepository().setURI(repoUrl).setBranch("main").setDirectory(clone.toFile()).call()) {
            Path file = clone.resolve(path);
            return Files.exists(file) ? Files.readString(file) : null;
        }
    }

    /**
     * Files committed to the main branch by hand: a layout a view could leave before it refused one release's
     * directory inside another's, or other content of the repository.
     */
    private void pushByHand(Map<String, String> files) throws Exception {
        Path clone = tmp.getRoot().toPath().resolve("by-hand-" + System.nanoTime());
        try (Git g = Git.cloneRepository().setURI(repoUrl).setBranch("main").setDirectory(clone.toFile()).call()) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path target = clone.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue());
            }
            g.add().addFilepattern(".").call();
            g.commit().setMessage("by hand").setAuthor("ops", "ops@example.com").call();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
        }
    }

    /** The HelmRelease manifest a deploy writes for {@code namespace/name}, reading its values Secret. */
    private static String helmReleaseManifest(String namespace, String name) {
        return FluxManifests.helmRelease(name, namespace, null, FluxManifests.helmReleaseSpec("trino", "1.43.14",
                "charts", List.of(), GitOpsSecretValues.secretName(name), Map.of()));
    }

    /** The record a GitOps deploy of {@code namespace/release} into {@code gitPath} leaves. */
    private K8sReleaseEntity record(String namespace, String release, String gitPath) {
        K8sReleaseEntity entity = new K8sReleaseEntity();
        entity.setId(K8sReleaseEntity.idOf(namespace, release));
        entity.setNamespace(namespace);
        entity.setReleaseName(release);
        entity.setDeploymentMode("FLUX_GITOPS");
        entity.setGitRepoUrl(repoUrl);
        entity.setGitBranch("main");
        entity.setGitPath(gitPath);
        entity.setRepoId("charts");
        releases.put(entity.getId(), entity);
        return entity;
    }

    private static io.fabric8.kubernetes.api.model.Secret managedSecret(String namespace, String name) {
        return new io.fabric8.kubernetes.api.model.SecretBuilder().withNewMetadata().withName(name)
                .withNamespace(namespace).addToLabels("managed-by", "ambari-k8s-view").endMetadata().build();
    }

    private HelmDeployRequest.GitOptions git() {
        HelmDeployRequest.GitOptions git = new HelmDeployRequest.GitOptions();
        git.setRepoUrl(repoUrl);
        git.setBaseBranch("main");
        git.setBranch("main");
        git.setCommitMode("DIRECT_COMMIT");
        return git;
    }

    /** A plain release, valid in every respect. */
    private HelmDeployRequest request(String namespace, String release) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace(namespace);
        request.setReleaseName(release);
        request.setChart("trinodb/trino");
        request.setVersion("1.43.14");
        request.setRepoId("charts");
        request.setDeploymentMode("FLUX_GITOPS");
        request.setValues(new LinkedHashMap<>(Map.of("server", new LinkedHashMap<>(Map.of("workers", 2)))));
        request.setGit(git());
        return request;
    }

    // ------------------------------------------------------------------------------- valid names: the same manifests

    /** A release with a password, two dependencies (one from an OCI registry) and values of every kind. */
    private HelmDeployRequest goldenRequest() {
        HelmDeployRequest request = request("analytics", "trino");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("server", new LinkedHashMap<>(Map.of("workers", 2)));
        values.put("coordinator", new LinkedHashMap<>(Map.of("jvm", new LinkedHashMap<>(Map.of("maxHeapSize", "8G")))));
        values.put("additionalConfigProperties", new ArrayList<>(List.of("query.max-memory=4GB",
                "http-server.process-forwarded=true")));
        values.put("catalogs", new LinkedHashMap<>(Map.of("hive", "connector.name=hive\nhive.metastore.uri=thrift://hms:9083\n")));
        Map<String, Object> ingress = new LinkedHashMap<>();
        ingress.put("enabled", true);
        ingress.put("hosts", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("host", "trino.example.com")))));
        values.put("ingress", ingress);
        values.put("notes", "a: b # not a comment");
        values.put("ratio", 0.75);
        values.put("labels", new LinkedHashMap<>());
        values.put("tolerations", new ArrayList<>());
        values.put("version", "1.0");
        request.setValues(values);

        Map<String, Object> proxyValues = new LinkedHashMap<>();
        proxyValues.put("upstream", new LinkedHashMap<>(Map.of("bindPassword", "Bind-Pass-42")));
        proxyValues.put("replicas", 1);
        Map<String, Object> proxy = new LinkedHashMap<>();
        proxy.put("chart", "ldap-proxy");
        proxy.put("chartVersion", "0.1.0");
        proxy.put("values", proxyValues);
        Map<String, Object> exporter = new LinkedHashMap<>();
        exporter.put("chart", "kdps/metrics-exporter");
        exporter.put("releaseName", "metrics-exporter");
        exporter.put("namespace", "monitoring");
        exporter.put("repoId", "registry");
        exporter.put("values", new LinkedHashMap<>(Map.of("port", 9100)));
        LinkedHashMap<String, Object> dependencies = new LinkedHashMap<>();
        dependencies.put("ldap-proxy", proxy);
        dependencies.put("exporter", exporter);
        request.setDependencies(dependencies);
        return request;
    }

    /** What the command plan of the golden release prepared, as the GITOPS_COMMIT step passes it. */
    private static FluxGitOpsBackend.PlannedRelease goldenPlan() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("global.security.auth.mode", "ldap");
        overrides.put("global.security.auth.ldap.url", "ldap://dir.example.com");
        overrides.put("global.security.auth.ldap.bindPassword", HelmSetValues.textIfSecret("ldap.bindPassword", "Bind-Pass-42"));
        overrides.put("image.tag", "476");
        overrides.put("server.config.query.maxMemory", "4GB");
        return new FluxGitOpsBackend.PlannedRelease(overrides, null);
    }

    /**
     * The manifests of the golden release, as the view committed them when it still wrote them from text templates:
     * the same text and the same spec digest, so a release deployed before gets no new commit.
     */
    @Test
    public void aValidDeployCommitsExactlyTheManifestsTheTemplatesWrote() throws Exception {
        backend().commit(goldenRequest(), null, goldenPlan());

        assertEquals("""
                apiVersion: helm.toolkit.fluxcd.io/v2
                kind: HelmRelease
                metadata:
                  name: trino
                  namespace: analytics
                  annotations:
                    ambari.clemlab.com/spec-digest: "05e24440d3cfc00a1184cb3f42f242a9bcb1f8cdfe8577a6fd1f97d3bf0476b3"
                spec:
                  interval: 5m
                  timeout: 15m
                  dependsOn:
                  - name: ldap-proxy
                    namespace: analytics
                  - name: metrics-exporter
                    namespace: monitoring
                  chart:
                    spec:
                      chart: trino
                      version: 1.43.14
                      sourceRef:
                        kind: HelmRepository
                        name: charts
                        namespace: flux-system
                  valuesFrom:
                  - kind: Secret
                    name: trino-kdps-values
                    valuesKey: values.yaml
                  values:
                    server:
                      workers: 2
                      config:
                        query:
                          maxMemory: "4GB"
                    coordinator:
                      jvm:
                        maxHeapSize: "8G"
                    additionalConfigProperties:
                    - "query.max-memory=4GB"
                    - "http-server.process-forwarded=true"
                    catalogs:
                      hive: "connector.name=hive\\nhive.metastore.uri=thrift://hms:9083\\n"
                    ingress:
                      enabled: true
                      hosts:
                      - host: "trino.example.com"
                    notes: "a: b # not a comment"
                    ratio: 0.75
                    labels: {}
                    tolerations: []
                    version: "1.0"
                    global:
                      security:
                        auth:
                          mode: "ldap"
                          ldap:
                            url: "ldap://dir.example.com"
                        kerberos:
                          injectionMode: "PRE_PROVISIONED"
                    image:
                      tag: 476
                """, published(RELEASE_DIR + "/helmrelease.yaml"));
        assertEquals("""
                apiVersion: source.toolkit.fluxcd.io/v1
                kind: HelmRepository
                metadata:
                  name: charts
                  namespace: flux-system
                spec:
                  interval: 5m
                  url: https://charts.example.com
                """, published(RELEASE_DIR + "/helmrepository.yaml"));
        assertEquals("""
                apiVersion: helm.toolkit.fluxcd.io/v2
                kind: HelmRelease
                metadata:
                  name: ldap-proxy
                  namespace: analytics
                spec:
                  interval: 5m
                  timeout: 15m
                  chart:
                    spec:
                      chart: ldap-proxy
                      version: 0.1.0
                      sourceRef:
                        kind: HelmRepository
                        name: charts
                        namespace: flux-system
                  valuesFrom:
                  - kind: Secret
                    name: ldap-proxy-kdps-values
                    valuesKey: values.yaml
                  values:
                    replicas: 1
                """, published(RELEASE_DIR + "/dependencies/ldap-proxy-helmrelease.yaml"));
        assertEquals("""
                apiVersion: helm.toolkit.fluxcd.io/v2
                kind: HelmRelease
                metadata:
                  name: metrics-exporter
                  namespace: monitoring
                spec:
                  interval: 5m
                  timeout: 15m
                  chart:
                    spec:
                      chart: kdps/metrics-exporter
                      sourceRef:
                        kind: HelmRepository
                        name: registry
                        namespace: flux-system
                  values:
                    port: 9100
                """, published(RELEASE_DIR + "/dependencies/metrics-exporter-helmrelease.yaml"));
        assertEquals("""
                apiVersion: source.toolkit.fluxcd.io/v1
                kind: HelmRepository
                metadata:
                  name: registry
                  namespace: flux-system
                spec:
                  interval: 5m
                  type: oci
                  url: oci://registry.example.com/kdps
                """, published(RELEASE_DIR + "/dependencies/registry-helmrepository.yaml"));
    }

    @Test
    public void redeployingTheSameReleaseCommitsNothing() throws Exception {
        backend().commit(goldenRequest(), null, goldenPlan());
        String head = remoteHead();

        FluxGitOpsBackend.CommitResult again = backend().commit(goldenRequest(), null, goldenPlan());

        assertFalse(again.committed());
        assertEquals(head, remoteHead());
    }

    // ------------------------------------------------------------------- malformed or injected names: nothing at all

    private void assertRefusedBeforeAnything(String expectedInMessage, Consumer<HelmDeployRequest> change) throws Exception {
        HelmDeployRequest request = goldenRequest();
        change.accept(request);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> backend().commit(request, null, goldenPlan()));

        assertTrue(refused.getMessage(), refused.getMessage().contains(expectedInMessage));
        assertFalse("one readable line: " + refused.getMessage(), refused.getMessage().contains("\n"));
        assertEquals("nothing was committed", seedCommit, remoteHead());
        verify(k8s, never()).createNamespace(anyString());
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyString(), any(byte[].class),
                anyMap(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dependency(HelmDeployRequest request, String name) {
        return (Map<String, Object>) request.getDependencies().get(name);
    }

    @Test
    public void releaseNamesHelmWouldRefuseAreRefused() throws Exception {
        assertRefusedBeforeAnything("Release name 'Trino_prod' is not valid", r -> r.setReleaseName("Trino_prod"));
        assertRefusedBeforeAnything("Release name 'trino: prod' is not valid", r -> r.setReleaseName("trino: prod"));
        assertRefusedBeforeAnything("Release name '../team-b/trino' is not valid", r -> r.setReleaseName("../team-b/trino"));
        assertRefusedBeforeAnything("Release name 'trino?---?kind: Secret' is not valid",
                r -> r.setReleaseName("trino\n---\nkind: Secret"));
        assertRefusedBeforeAnything("Namespace 'Team-A' is not valid", r -> r.setNamespace("Team-A"));
        assertRefusedBeforeAnything("Namespace 'team-a/../team-b' is not valid", r -> r.setNamespace("team-a/../team-b"));
    }

    @Test
    public void aChartOrVersionThatWouldAddToTheManifestIsRefused() throws Exception {
        assertRefusedBeforeAnything("Chart 'trino?---?apiVersion: rbac.authorization.k8s.io/v1?kind: ClusterRoleBinding'",
                r -> r.setChart("trino\n---\napiVersion: rbac.authorization.k8s.io/v1\nkind: ClusterRoleBinding"));
        assertRefusedBeforeAnything("Chart version '1.0.0?---?kind: ClusterRoleBinding' is not valid",
                r -> r.setVersion("1.0.0\n---\nkind: ClusterRoleBinding"));
        assertRefusedBeforeAnything("Chart version '1.0.0 # pinned' is not valid", r -> r.setVersion("1.0.0 # pinned"));
        assertRefusedBeforeAnything("Chart version 'name: x' is not valid", r -> r.setVersion("name: x"));
        assertRefusedBeforeAnything("Chart repository 'charts_repo' is not valid", r -> r.setRepoId("charts_repo"));
    }

    @Test
    public void aDependencyThatWouldAddToTheManifestsOrLeaveTheReleaseDirectoryIsRefused() throws Exception {
        assertRefusedBeforeAnything("Dependency 'ldap-proxy': chart",
                r -> dependency(r, "ldap-proxy").put("chart", "ldap-proxy\n---\nkind: ClusterRoleBinding"));
        assertRefusedBeforeAnything("Dependency 'ldap-proxy': chart version",
                r -> dependency(r, "ldap-proxy").put("chartVersion", "0.1.0\nkind: Secret"));
        assertRefusedBeforeAnything("Dependency 'exporter': release name '../../team-b/trino' is not valid",
                r -> dependency(r, "exporter").put("releaseName", "../../team-b/trino"));
        assertRefusedBeforeAnything("Dependency 'exporter': namespace 'kube-system?---' is not valid",
                r -> dependency(r, "exporter").put("namespace", "kube-system\n---"));
        assertRefusedBeforeAnything("Dependency 'exporter': chart repository 'registry/../x' is not valid",
                r -> dependency(r, "exporter").put("repoId", "registry/../x"));
        assertRefusedBeforeAnything("Dependency name 'Bad_Name' is not valid",
                r -> r.getDependencies().put("Bad_Name", new LinkedHashMap<>(Map.of("chart", "x"))));
    }

    @Test
    public void aDependencyNamespacePickedInTheFormIsCheckedToo() throws Exception {
        assertRefusedBeforeAnything("Dependency 'exporter': namespace 'Monitoring' is not valid", r -> {
            dependency(r, "exporter").put("namespaceFromForm", "monitoring.namespace");
            r.setFormValues(new LinkedHashMap<>(Map.of("monitoring", Map.of("namespace", "Monitoring"))));
        });
    }

    @Test
    public void aPathPrefixThatClimbsOutOfTheRepositoryIsRefused() throws Exception {
        assertRefusedBeforeAnything("cannot traverse outside repo", r -> r.getGit().setPathPrefix("clusters/../../etc"));
    }

    // ---------------------------------------------------------------------------- the release's own directory only

    @Test
    public void aRecordedDirectoryThatIsNotTheReleasesOwnIsNotWrittenTo() throws Exception {
        backend().apply(request("team-b", "trino"), null);
        String head = remoteHead();
        K8sReleaseEntity recorded = releases.get(K8sReleaseEntity.idOf("team-b", "trino"));
        K8sReleaseEntity forged = new K8sReleaseEntity();
        forged.setId(K8sReleaseEntity.idOf("team-a", "trino"));
        forged.setNamespace("team-a");
        forged.setReleaseName("trino");
        forged.setDeploymentMode("FLUX_GITOPS");
        forged.setGitRepoUrl(repoUrl);
        forged.setGitBranch("main");
        forged.setGitPath(recorded.getGitPath());
        releases.put(forged.getId(), forged);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> backend().apply(request("team-a", "trino"), null));

        assertTrue(refused.getMessage(), refused.getMessage().contains("is not the release's own directory"));
        assertEquals(head, remoteHead());
        assertTrue(published("clusters/default/team-b/trino/helmrelease.yaml").contains("namespace: team-b"));
    }

    @Test
    public void anUninstallRemovesOnlyTheReleasesOwnDirectory() throws Exception {
        backend().apply(request("team-a", "trino"), null);
        backend().apply(request("team-b", "trino"), null);
        String head = remoteHead();

        for (String foreign : new String[] {"clusters/default/team-b/trino", "clusters/default", "", "   ",
                "clusters/default/team-a/../team-b/trino", "/clusters/default/team-a/trino"}) {
            assertThrows("refused: '" + foreign + "'", IllegalArgumentException.class, () -> backend().destroy(
                    repoUrl, "main", "main", foreign, null, null, "team-a", "trino"));
        }
        assertEquals("nothing removed", head, remoteHead());

        backend().destroy(repoUrl, "main", "main", "clusters/default/team-a/trino", null, null, "team-a", "trino");

        assertNull(published("clusters/default/team-a/trino/helmrelease.yaml"));
        assertNotNull("the other release keeps its manifests", published("clusters/default/team-b/trino/helmrelease.yaml"));
    }

    @Test
    public void anUninstallOfAReleaseWhoseRecordNamesAnotherDirectoryRemovesNothing() throws Exception {
        backend().apply(request("team-b", "trino"), null);
        String head = remoteHead();
        K8sReleaseEntity forged = new K8sReleaseEntity();
        forged.setId(K8sReleaseEntity.idOf("team-a", "trino"));
        forged.setNamespace("team-a");
        forged.setReleaseName("trino");
        forged.setDeploymentMode("FLUX_GITOPS");
        forged.setGitRepoUrl(repoUrl);
        forged.setGitBranch("main");
        forged.setGitPath("clusters/default/team-b/trino");
        forged.setRepoId("charts");
        releases.put(forged.getId(), forged);
        clearInvocations(k8s);

        RuntimeException refused = assertThrows(RuntimeException.class, () -> backend().destroy("team-a", "trino"));

        assertTrue(refused.getMessage(), refused.getMessage().contains("is not the release's own directory"));
        assertEquals(head, remoteHead());
        assertNotNull(published("clusters/default/team-b/trino/helmrelease.yaml"));
        assertNotNull("kept, so the uninstall can be retried once the record is corrected",
                releases.get(K8sReleaseEntity.idOf("team-a", "trino")));
        // Nor is the HelmRelease or the shared HelmRepository deleted from the cluster: Flux would recreate the one
        // from Git, and the other serves the other releases of the repository.
        verify(k8s, never()).getClient();
    }

    // ------------------------------------------------------------- one release's directory inside another's: refused

    /** A deploy into {@code prefix/namespace/release}, refused with nothing committed nor prepared. */
    private void assertNestedDeployRefused(String prefix, String namespace, String release, String expected)
            throws Exception {
        String head = remoteHead();
        clearInvocations(k8s);
        HelmDeployRequest request = request(namespace, release);
        request.getGit().setPathPrefix(prefix);

        FluxGitOpsBackend.NotOwnDirectoryException refused = assertThrows(FluxGitOpsBackend.NotOwnDirectoryException.class,
                () -> backend().apply(request, null));

        assertTrue(refused.getMessage(), refused.getMessage().contains(expected));
        assertEquals("nothing was committed", head, remoteHead());
        verify(k8s, never()).createNamespace(anyString());
    }

    @Test
    public void aPathPrefixThatPutsTheReleaseAboveOthersIsRefused() throws Exception {
        backend().apply(request("team-a", "trino"), null);
        backend().apply(request("team-b", "trino"), null);

        // Release "default" of namespace "clusters" at the root: the default prefix itself, clusters/default.
        assertNestedDeployRefused(".", "clusters", "default",
                "'clusters/default', holds the directory of release team-a/trino, 'clusters/default/team-a/trino'");
        // Release "team-b" of namespace "default" under "clusters": the namespace directory of team-b's releases.
        assertNestedDeployRefused("clusters", "default", "team-b",
                "'clusters/default/team-b', holds the directory of release team-b/trino");

        // Also for releases KDPS has no record of, deployed to the repository from elsewhere: Git tells.
        releases.clear();
        assertNestedDeployRefused(".", "clusters", "default", "holds the directory of release team-a/trino");
        assertNestedDeployRefused("clusters", "default", "team-b", "holds the directory of release team-b/trino");
        assertNotNull(published("clusters/default/team-b/trino/helmrelease.yaml"));
        assertNull(published("clusters/default/team-b/helmrelease.yaml"));
    }

    @Test
    public void aPathPrefixThatPutsTheReleaseInsideAnotherIsRefused() throws Exception {
        // Release "trino" of namespace "default" under "clusters": clusters/default/trino.
        HelmDeployRequest outer = request("default", "trino");
        outer.getGit().setPathPrefix("clusters");
        backend().apply(outer, null);

        // Release "trino" of namespace "trino" with the default prefix: clusters/default/trino/trino.
        assertNestedDeployRefused(null, "trino", "trino",
                "'clusters/default/trino/trino', is inside the directory of release default/trino, 'clusters/default/trino'");
        // Release "dependencies" of namespace "trino" in that release's dependencies directory.
        assertNestedDeployRefused("clusters/default", "trino", "dependencies",
                "is inside the directory of release default/trino");

        releases.clear();
        assertNestedDeployRefused(null, "trino", "trino", "is inside the directory of release default/trino");
    }

    @Test
    public void aReleaseProposedInAPullRequestStillOpenKeepsItsDirectory() throws Exception {
        // Recorded, but its manifests are on a pull request branch, not on the branch a new release starts from.
        record("team-b", "trino", "clusters/default/team-b/trino");
        HelmDeployRequest request = request("default", "team-b");
        request.getGit().setPathPrefix("clusters");

        FluxGitOpsBackend.NotOwnDirectoryException refused = assertThrows(FluxGitOpsBackend.NotOwnDirectoryException.class,
                () -> backend().checkCommittable(request, null));

        assertTrue(refused.getMessage(), refused.getMessage().contains(
                "holds the directory of release team-b/trino, 'clusters/default/team-b/trino'"));
        assertThrows(FluxGitOpsBackend.NotOwnDirectoryException.class, () -> backend().apply(request, null));
        assertEquals(seedCommit, remoteHead());

        // The same directory in another repository is not that release's.
        releases.get(K8sReleaseEntity.idOf("team-b", "trino")).setGitRepoUrl("https://git.example.org/other.git");
        backend().checkCommittable(request, null);
    }

    @Test
    public void aDirectoryHoldingAnotherHelmReleaseIsNotWrittenTo() throws Exception {
        pushByHand(Map.of("clusters/default/team-a/trino/helmrelease.yaml", helmReleaseManifest("team-b", "trino")));
        String head = remoteHead();

        FluxGitOpsBackend.NotOwnDirectoryException refused = assertThrows(FluxGitOpsBackend.NotOwnDirectoryException.class,
                () -> backend().apply(request("team-a", "trino"), null));

        assertTrue(refused.getMessage(), refused.getMessage().contains("holds the HelmRelease team-b/trino"));
        assertEquals(head, remoteHead());
    }

    // ------------------------------------------------- an uninstall removes only what the release wrote, nothing below

    @Test
    public void anUninstallLeavesAReleaseWhoseDirectoryIsBelowItsOwn() throws Exception {
        // Release trino of namespace trino with the default prefix, with its values Secret.
        backend().apply(request("trino", "trino"), null);
        // Release trino of namespace default under the prefix "clusters", whose directory holds the other's, as a
        // view could leave it before refusing such a deploy.
        pushByHand(Map.of(
                "clusters/default/trino/helmrelease.yaml", helmReleaseManifest("default", "trino"),
                "clusters/default/trino/helmrepository.yaml", FluxManifests.helmRepository("charts",
                        "https://charts.example.com", false),
                "clusters/default/trino/dependencies/ldap-proxy-helmrelease.yaml",
                        helmReleaseManifest("default", "ldap-proxy")));
        record("default", "trino", "clusters/default/trino");
        when(k8s.getSecret(anyString(), anyString())).thenAnswer(inv -> managedSecret(inv.getArgument(0), inv.getArgument(1)));

        backend().destroy(repoUrl, "main", "main", "clusters/default/trino", null, null, "default", "trino");

        assertNull(published("clusters/default/trino/helmrelease.yaml"));
        assertNull(published("clusters/default/trino/helmrepository.yaml"));
        assertNull(published("clusters/default/trino/dependencies/ldap-proxy-helmrelease.yaml"));
        assertNotNull("the release below keeps its manifests", published("clusters/default/trino/trino/helmrelease.yaml"));
        assertNotNull(published("clusters/default/trino/trino/helmrepository.yaml"));
        verify(k8s).deleteSecret("default", "trino-kdps-values");
        verify(k8s).deleteSecret("default", "ldap-proxy-kdps-values");
        verify(k8s, never()).deleteSecret(eq("trino"), anyString());
    }

    @Test
    public void anUninstallOfAReleaseAtTheDefaultPrefixLeavesTheReleasesBelowIt() throws Exception {
        backend().apply(request("team-a", "trino"), null);
        backend().apply(request("team-b", "trino"), null);
        // Release "default" of namespace "clusters" under the prefix ".", and other content of its directory.
        pushByHand(Map.of(
                "clusters/default/helmrelease.yaml", helmReleaseManifest("clusters", "default"),
                "clusters/default/helmrepository.yaml", FluxManifests.helmRepository("charts",
                        "https://charts.example.com", false),
                "clusters/default/flux-system/kustomization.yaml", "resources:\n- gotk-sync.yaml\n",
                "clusters/default/notes.yaml", "owner: platform\n"));
        when(k8s.getSecret(anyString(), anyString())).thenAnswer(inv -> managedSecret(inv.getArgument(0), inv.getArgument(1)));

        backend().destroy(repoUrl, "main", "main", "clusters/default", null, null, "clusters", "default");

        assertNull(published("clusters/default/helmrelease.yaml"));
        assertNull(published("clusters/default/helmrepository.yaml"));
        for (String kept : List.of("clusters/default/team-a/trino/helmrelease.yaml",
                "clusters/default/team-b/trino/helmrelease.yaml", "clusters/default/team-b/trino/helmrepository.yaml",
                "clusters/default/flux-system/kustomization.yaml", "clusters/default/notes.yaml")) {
            assertNotNull(kept, published(kept));
        }
        verify(k8s).deleteSecret("clusters", "default-kdps-values");
        verify(k8s, never()).deleteSecret(eq("team-a"), anyString());
        verify(k8s, never()).deleteSecret(eq("team-b"), anyString());
    }

    @Test
    public void anUninstallOfAReleaseWhoseDirectoryHoldsAnotherHelmReleaseRemovesNothing() throws Exception {
        pushByHand(Map.of("clusters/default/team-a/trino/helmrelease.yaml", helmReleaseManifest("team-b", "trino")));
        record("team-a", "trino", "clusters/default/team-a/trino");
        String head = remoteHead();
        clearInvocations(k8s);

        assertThrows(FluxGitOpsBackend.NotOwnDirectoryException.class, () -> backend().destroy("team-a", "trino"));

        assertEquals(head, remoteHead());
        assertNotNull("kept, so the uninstall can be retried", releases.get(K8sReleaseEntity.idOf("team-a", "trino")));
        verify(k8s, never()).getClient();
        verify(k8s, never()).deleteSecret(anyString(), anyString());
    }
}
