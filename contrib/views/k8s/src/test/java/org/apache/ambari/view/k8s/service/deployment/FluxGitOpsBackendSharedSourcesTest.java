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

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Where a GitOps deploy keeps the chart sources and the dependencies its releases share: a chart source of each
 * release's own in its namespace, the dependencies where no release's uninstall removes them, the Secrets of an
 * authenticated chart repository written through the Kubernetes API, and the layout of earlier versions moved on.
 */
public class FluxGitOpsBackendSharedSourcesTest {

    private static final String PREFIX = "clusters/default";
    private static final String SHARED_DIR = PREFIX + "/_shared";
    private static final String TRINO_DIR = PREFIX + "/analytics/trino";
    private static final String SUPERSET_DIR = PREFIX + "/analytics/superset";
    private static final String WEBHOOK = "kerberos-keytab-mutating-webhook";
    private static final Map<String, String> SOURCE_SECRET_LABELS = Map.of("managed-by", "ambari-k8s-view",
            "app.kubernetes.io/managed-by", "kdps");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ViewContext ctx;
    private KubernetesService k8s;
    private String repoUrl;
    private Path bareRepo;
    private Path fluxRoot;
    private String workDir;
    private String seedCommit;
    private final Map<String, HelmRepoEntity> chartRepos = new ConcurrentHashMap<>();
    private final Map<String, K8sReleaseEntity> releases = new ConcurrentHashMap<>();
    private final Map<String, String> repositorySecrets = new ConcurrentHashMap<>();
    /** The objects KDPS marked on the cluster so Flux does not prune them ("plural namespace/name"), in order. */
    private final List<String> marked = new ArrayList<>();
    /** The head of the branch Flux reconciles when each object was marked. */
    private final Map<String, String> headWhenMarked = new ConcurrentHashMap<>();
    /** The objects KDPS cannot mark on the cluster, as a project-limited account could not. */
    private final Set<String> unmarkable = ConcurrentHashMap.newKeySet();

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
            g.add().addFilepattern(".").call();
            seedCommit = g.commit().setMessage("init").setAuthor("seed", "seed@example.com").call().getName();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
        }
        fluxRoot = tmp.newFolder("flux").toPath();
        workDir = tmp.newFolder("work").toString();

        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        viewProperties(Map.of());
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getInstanceName()).thenReturn("kdps");
        when(ctx.getInstanceData(anyString())).thenReturn(null);
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
        DataStore ds = mock(DataStore.class);
        when(ctx.getDataStore()).thenReturn(ds);
        // The view settings (the shared truststores among them) are kept as they would be.
        org.apache.ambari.view.k8s.store.InMemoryDataStore settings = new org.apache.ambari.view.k8s.store.InMemoryDataStore();
        Class<org.apache.ambari.view.k8s.store.KdpsSettingEntity> setting = org.apache.ambari.view.k8s.store.KdpsSettingEntity.class;
        when(ds.find(eq(setting), anyString())).thenAnswer(inv -> settings.find(setting, inv.getArgument(1)));
        doAnswer(inv -> {
            settings.store(inv.getArgument(0));
            return null;
        }).when(ds).store(any(setting));
        doAnswer(inv -> {
            settings.remove(inv.getArgument(0));
            return null;
        }).when(ds).remove(any(setting));

        chartRepos.put("charts", chartRepo("charts", "HTTP", "https://charts.example.com"));
        chartRepos.put("registry", chartRepo("registry", "OCI", "registry.example.com/kdps"));
        when(ds.find(eq(HelmRepoEntity.class), anyString()))
                .thenAnswer(inv -> chartRepos.get((String) inv.getArgument(1)));
        doAnswer(inv -> {
            K8sReleaseEntity entity = inv.getArgument(0);
            releases.put(entity.getId(), entity);
            return null;
        }).when(ds).store(any(K8sReleaseEntity.class));
        when(ds.find(eq(K8sReleaseEntity.class), anyString()))
                .thenAnswer(inv -> releases.get((String) inv.getArgument(1)));
        when(ds.findAll(eq(K8sReleaseEntity.class), any())).thenAnswer(inv -> new ArrayList<>(releases.values()));
        doAnswer(inv -> {
            releases.remove(((K8sReleaseEntity) inv.getArgument(0)).getId());
            return null;
        }).when(ds).remove(any(K8sReleaseEntity.class));

        k8s = mock(KubernetesService.class);
        doAnswer(inv -> {
            String key = ((ResourceDefinitionContext) inv.getArgument(0)).getPlural() + " " + inv.getArgument(1) + "/"
                    + inv.getArgument(2);
            if (unmarkable.contains(key)) {
                return false;
            }
            marked.add(key);
            headWhenMarked.put(key, remoteHead());
            return true;
        }).when(k8s).keepFromFluxPrune(any(), anyString(), anyString());
    }

    private void viewProperties(Map<String, String> extra) {
        Map<String, String> properties = new LinkedHashMap<>(extra);
        properties.put("k8s.view.working.dir", workDir);
        when(ctx.getProperties()).thenReturn(properties);
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
        FluxGitOpsBackend backend = new FluxGitOpsBackend(fluxRoot, k8s, ctx);
        backend.setRepositorySecret(repo -> repositorySecrets.get(repo.getId()));
        return backend;
    }

    private HelmDeployRequest request(String namespace, String release, String chart, String repoId) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace(namespace);
        request.setReleaseName(release);
        request.setChart(chart);
        request.setVersion("1.43.14");
        request.setRepoId(repoId);
        request.setDeploymentMode("FLUX_GITOPS");
        request.setValues(new LinkedHashMap<>(Map.of("replicas", 1)));
        HelmDeployRequest.GitOptions git = new HelmDeployRequest.GitOptions();
        git.setRepoUrl(repoUrl);
        git.setBaseBranch("main");
        git.setCommitMode("DIRECT_COMMIT");
        request.setGit(git);
        return request;
    }

    /** A release that needs KEDA, as the Trino definition declares it. */
    private HelmDeployRequest trinoWithKeda() {
        HelmDeployRequest request = request("analytics", "trino", "trinodb/trino", "charts");
        LinkedHashMap<String, Object> dependencies = new LinkedHashMap<>();
        dependencies.put("keda", new LinkedHashMap<>(Map.of("chart", "keda", "chartVersion", "2.18.0",
                "namespace", "keda", "skipIfCrdExists", "scaledobjects.keda.sh")));
        request.setDependencies(dependencies);
        return request;
    }

    private String remoteHead() throws Exception {
        try (Git g = Git.open(bareRepo.toFile())) {
            return g.getRepository().resolve("refs/heads/main").getName();
        }
    }

    /** The branch Flux reconciles as the server holds it, in a fresh clone. */
    private Path cloneMain() throws Exception {
        Path clone = tmp.getRoot().toPath().resolve("verify-" + System.nanoTime());
        org.apache.ambari.view.k8s.utils.TestGitClones.clone(repoUrl, "main", clone).close();
        return clone;
    }

    /** A file as published on the remote main branch, or null. */
    private String published(String path) throws Exception {
        Path file = cloneMain().resolve(path);
        return Files.exists(file) ? Files.readString(file) : null;
    }

    private void pushByHand(Map<String, String> files) throws Exception {
        Path clone = tmp.getRoot().toPath().resolve("by-hand-" + System.nanoTime());
        try (Git g = org.apache.ambari.view.k8s.utils.TestGitClones.clone(repoUrl, "main", clone)) {
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

    /**
     * Every object the path prefix of the branch Flux reconciles defines, by "kind namespace/name", with the files
     * defining it: what one Flux Kustomization over the prefix applies. kustomize refuses an id defined twice.
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<String>> objectsUnderThePrefix() throws Exception {
        Path clone = cloneMain();
        Map<String, List<String>> objects = new TreeMap<>();
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        List<Path> files;
        try (Stream<Path> walk = Files.walk(clone.resolve(PREFIX))) {
            files = walk.filter(f -> f.toString().endsWith(".yaml")).sorted().toList();
        }
        for (Path file : files) {
            try (MappingIterator<Object> docs = yaml.readerFor(Map.class).readValues(file.toFile())) {
                while (docs.hasNext()) {
                    Map<String, Object> doc = (Map<String, Object>) docs.next();
                    Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");
                    String id = doc.get("kind") + " " + metadata.get("namespace") + "/" + metadata.get("name");
                    objects.computeIfAbsent(id, k -> new ArrayList<>()).add(clone.relativize(file).toString());
                }
            }
        }
        return objects;
    }

    /** The source each HelmRelease under the prefix reads, by "namespace/name" of the HelmRelease. */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> sourceRefs() throws Exception {
        Path clone = cloneMain();
        Map<String, Map<String, Object>> refs = new TreeMap<>();
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        List<Path> files;
        try (Stream<Path> walk = Files.walk(clone.resolve(PREFIX))) {
            files = walk.filter(f -> f.toString().endsWith("helmrelease.yaml")).toList();
        }
        for (Path file : files) {
            Map<String, Object> doc = yaml.readValue(file.toFile(), Map.class);
            Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");
            Map<String, Object> chart = (Map<String, Object>) ((Map<String, Object>) doc.get("spec")).get("chart");
            refs.put(metadata.get("namespace") + "/" + metadata.get("name"),
                    (Map<String, Object>) ((Map<String, Object>) chart.get("spec")).get("sourceRef"));
        }
        return refs;
    }

    private static void assertNoObjectDefinedTwice(Map<String, List<String>> objects) {
        objects.forEach((id, files) -> assertEquals("defined once: " + id + " in " + files, 1, files.size()));
    }

    // ------------------------------------------------------------------------- one chart source per release

    @Test
    public void twoReleasesFromOneRepositoryGetDistinctChartSourcesInTheirOwnNamespace() throws Exception {
        backend().apply(request("analytics", "trino", "trinodb/trino", "charts"), null);
        backend().apply(request("analytics", "superset", "apache/superset", "charts"), null);

        Map<String, List<String>> objects = objectsUnderThePrefix();
        assertNoObjectDefinedTwice(objects);
        assertEquals(List.of(TRINO_DIR + "/helmrepository.yaml"),
                objects.get("HelmRepository analytics/trino-charts"));
        assertEquals(List.of(SUPERSET_DIR + "/helmrepository.yaml"),
                objects.get("HelmRepository analytics/superset-charts"));
        assertEquals(Map.of("kind", "HelmRepository", "name", "trino-charts"), sourceRefs().get("analytics/trino"));
        assertEquals(Map.of("kind", "HelmRepository", "name", "superset-charts"),
                sourceRefs().get("analytics/superset"));
    }

    @Test
    public void noHelmReleaseReadsItsChartFromAnotherNamespace() throws Exception {
        HelmDeployRequest request = trinoWithKeda();
        request.getDependencies().put("exporter", new LinkedHashMap<>(Map.of("chart", "kdps/metrics-exporter",
                "releaseName", "metrics-exporter", "namespace", "monitoring", "repoId", "registry")));

        backend().apply(request, null);

        Map<String, List<String>> objects = objectsUnderThePrefix();
        assertNoObjectDefinedTwice(objects);
        Map<String, Map<String, Object>> refs = sourceRefs();
        assertEquals(List.of("analytics/trino", "keda/keda", "monitoring/metrics-exporter"),
                new ArrayList<>(refs.keySet()));
        refs.forEach((helmRelease, ref) -> {
            assertFalse(helmRelease + " names no namespace: " + ref, ref.containsKey("namespace"));
            String namespace = helmRelease.substring(0, helmRelease.indexOf('/'));
            assertTrue(helmRelease + " finds its source in its own namespace",
                    objects.containsKey("HelmRepository " + namespace + "/" + ref.get("name")));
        });
        assertEquals(List.of(SHARED_DIR + "/monitoring/metrics-exporter-helmrepository.yaml"),
                objects.get("HelmRepository monitoring/metrics-exporter-registry"));
    }

    @Test
    public void theDependencyEveryReleaseSharesIsWrittenOnceAndKeptByFlux() throws Exception {
        backend().apply(trinoWithKeda(), null);
        // KEDA, which Flux installed for trino, now serves the CRD: superset keeps the one Git holds and depends on it.
        when(k8s.crdExists("scaledobjects.keda.sh")).thenReturn(true);
        HelmDeployRequest superset = request("analytics", "superset", "apache/superset", "charts");
        superset.setDependencies(new LinkedHashMap<>(trinoWithKeda().getDependencies()));

        backend().apply(superset, null);

        Map<String, List<String>> objects = objectsUnderThePrefix();
        assertNoObjectDefinedTwice(objects);
        assertEquals(List.of(SHARED_DIR + "/keda/keda-helmrelease.yaml"), objects.get("HelmRelease keda/keda"));
        String keda = published(SHARED_DIR + "/keda/keda-helmrelease.yaml");
        assertTrue(keda, keda.contains("    kustomize.toolkit.fluxcd.io/prune: disabled\n"));
        assertTrue(published(SHARED_DIR + "/keda/keda-helmrepository.yaml")
                .contains("    kustomize.toolkit.fluxcd.io/prune: disabled\n"));
        assertTrue(published(SUPERSET_DIR + "/helmrelease.yaml")
                .contains("dependsOn:\n  - name: keda\n    namespace: keda\n"));
    }

    // ----------------------------------------------------------------------- an uninstall leaves _shared alone

    @Test
    public void anUninstallLeavesWhatTheReleasesShare() throws Exception {
        backend().apply(trinoWithKeda(), null);
        when(k8s.crdExists("scaledobjects.keda.sh")).thenReturn(true);
        HelmDeployRequest superset = request("analytics", "superset", "apache/superset", "charts");
        superset.setDependencies(new LinkedHashMap<>(trinoWithKeda().getDependencies()));
        backend().apply(superset, null);
        String keda = published(SHARED_DIR + "/keda/keda-helmrelease.yaml");

        backend().destroy("analytics", "trino");

        assertNull(published(TRINO_DIR + "/helmrelease.yaml"));
        assertNull("the release's own chart source goes with it", published(TRINO_DIR + "/helmrepository.yaml"));
        assertEquals("KEDA stays for superset, unchanged", keda, published(SHARED_DIR + "/keda/keda-helmrelease.yaml"));
        assertNotNull(published(SHARED_DIR + "/keda/keda-helmrepository.yaml"));
        assertNotNull(published(SUPERSET_DIR + "/helmrelease.yaml"));
        assertNotNull("superset keeps fetching its charts", published(SUPERSET_DIR + "/helmrepository.yaml"));
        assertNull(releases.get(K8sReleaseEntity.idOf("analytics", "trino")));
        verify(k8s, never()).deleteSecret("keda", "keda-kdps-values");
    }

    // ------------------------------------------------------------- chart sources kept in one namespace

    @Test
    public void aPlatformThatKeepsItsSourcesInOneNamespaceGetsOneSourcePerRepositoryThatNoUninstallRemoves()
            throws Exception {
        viewProperties(Map.of(FluxGitOpsBackend.SOURCE_NAMESPACE_PROPERTY, "flux-system"));
        backend().apply(request("analytics", "trino", "trinodb/trino", "charts"), null);
        backend().apply(request("bi", "superset", "apache/superset", "charts"), null);

        Map<String, List<String>> objects = objectsUnderThePrefix();
        assertNoObjectDefinedTwice(objects);
        assertEquals(List.of(SHARED_DIR + "/sources/flux-system/charts-helmrepository.yaml"),
                objects.get("HelmRepository flux-system/charts"));
        assertNull(published(TRINO_DIR + "/helmrepository.yaml"));
        assertEquals(Map.of("kind", "HelmRepository", "name", "charts", "namespace", "flux-system"),
                sourceRefs().get("analytics/trino"));
        assertTrue(published(SHARED_DIR + "/sources/flux-system/charts-helmrepository.yaml")
                .contains("    kustomize.toolkit.fluxcd.io/prune: disabled\n"));

        backend().destroy("analytics", "trino");

        assertNull(published(TRINO_DIR + "/helmrelease.yaml"));
        assertNotNull("superset still reads it",
                published(SHARED_DIR + "/sources/flux-system/charts-helmrepository.yaml"));
    }

    @Test
    public void aSourceNamespaceThatIsNoNamespaceStopsTheDeployBeforeAnything() throws Exception {
        viewProperties(Map.of(FluxGitOpsBackend.SOURCE_NAMESPACE_PROPERTY, "Flux_System"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> backend().apply(request("analytics", "trino", "trinodb/trino", "charts"), null));

        assertTrue(refused.getMessage(), refused.getMessage().contains(FluxGitOpsBackend.SOURCE_NAMESPACE_PROPERTY));
        assertEquals(seedCommit, remoteHead());
        verify(k8s, never()).createNamespace(anyString());
    }

    // ------------------------------------------------------------------ authenticated or private-CA repositories

    @Test
    public void anAuthenticatedRepositoryIsReadWithASecretKdpsWritesNextToTheSource() throws Exception {
        HelmRepoEntity charts = chartRepos.get("charts");
        charts.setAuthMode("basic");
        charts.setUsername("robot");
        repositorySecrets.put("charts", "Repo-Pass-61");

        backend().apply(request("analytics", "trino", "trinodb/trino", "charts"), null);

        String source = published(TRINO_DIR + "/helmrepository.yaml");
        assertTrue(source, source.contains("  secretRef:\n    name: trino-charts-repo-auth\n"));
        assertFalse("never in Git", source.contains("Repo-Pass-61"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, byte[]>> data = ArgumentCaptor.forClass(Map.class);
        verify(k8s).createOrUpdateOpaqueSecret(eq("analytics"), eq("trino-charts-repo-auth"), data.capture(),
                eq(SOURCE_SECRET_LABELS));
        assertEquals("robot", new String(data.getValue().get("username"), StandardCharsets.UTF_8));
        assertEquals("Repo-Pass-61", new String(data.getValue().get("password"), StandardCharsets.UTF_8));

        // Gone with the release: its source no longer reads it.
        when(k8s.getSecret(eq("analytics"), anyString())).thenAnswer(inv ->
                new io.fabric8.kubernetes.api.model.SecretBuilder().withNewMetadata().withName(inv.getArgument(1))
                        .addToLabels("managed-by", "ambari-k8s-view").endMetadata().build());
        backend().destroy("analytics", "trino");
        verify(k8s).deleteSecret("analytics", "trino-charts-repo-auth");
    }

    @Test
    public void anAuthenticatedRegistryIsReadWithARegistryLogin() throws Exception {
        HelmRepoEntity registry = chartRepos.get("registry");
        registry.setUrl("https://harbor.example.com/kdps");
        registry.setAuthMode("token");
        registry.setUsername("robot$kdps");
        repositorySecrets.put("registry", "Robot-Token-7");

        backend().apply(request("analytics", "trino", "trinodb/trino", "registry"), null);

        String source = published(TRINO_DIR + "/helmrepository.yaml");
        assertTrue(source, source.contains("  type: oci\n  url: oci://harbor.example.com/kdps\n  secretRef:\n"
                + "    name: trino-registry-repo-auth\n"));
        verify(k8s).createOrUpdateDockerConfigSecret("analytics", "trino-registry-repo-auth", "harbor.example.com",
                "robot$kdps", "Robot-Token-7", SOURCE_SECRET_LABELS);
    }

    @Test
    public void aPrivateCaComesFromTheKdpsTruststoresTheRepositoryNames() throws Exception {
        chartRepos.get("charts").setCaTruststores("corp-ca, partner-ca");
        org.apache.ambari.view.k8s.service.TruststoreRegistryService truststores =
                new org.apache.ambari.view.k8s.service.TruststoreRegistryService(ctx, k8s);
        truststores.create("corp-ca", org.apache.ambari.view.k8s.utils.TestCertificates.caPem("CN=Corp"), false, null);
        truststores.create("partner-ca", org.apache.ambari.view.k8s.utils.TestCertificates.caPem("CN=Partner"), false,
                null);

        backend().apply(request("analytics", "trino", "trinodb/trino", "charts"), null);

        String source = published(TRINO_DIR + "/helmrepository.yaml");
        assertTrue(source, source.contains("  certSecretRef:\n    name: trino-charts-repo-ca\n"));
        assertFalse("anonymous: no credentials", source.contains("secretRef:\n    name: trino-charts-repo-auth"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, byte[]>> data = ArgumentCaptor.forClass(Map.class);
        verify(k8s).createOrUpdateOpaqueSecret(eq("analytics"), eq("trino-charts-repo-ca"), data.capture(),
                eq(SOURCE_SECRET_LABELS));
        String corp = truststores.caPemByName("corp-ca");
        String partner = truststores.caPemByName("partner-ca");
        assertEquals((corp.endsWith("\n") ? corp : corp + "\n") + (partner.endsWith("\n") ? partner : partner + "\n"),
                new String(data.getValue().get("ca.crt"), StandardCharsets.UTF_8));
    }

    @Test
    public void aTruststoreKdpsCannotReadStopsTheDeployBeforeTheCommit() throws Exception {
        chartRepos.get("charts").setCaTruststores("gone-ca");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> backend().apply(request("analytics", "trino", "trinodb/trino", "charts"), null));

        assertTrue(refused.getMessage(), refused.getMessage().contains("names the truststore 'gone-ca'"));
        assertEquals(seedCommit, remoteHead());
    }

    @Test
    public void secretsTheRepositoryNamesAreReadAsTheyAre() throws Exception {
        HelmRepoEntity charts = chartRepos.get("charts");
        charts.setAuthMode("basic");
        charts.setFluxSecretName("harbor-pull");
        charts.setFluxCertSecretName("corporate-ca");
        charts.setCaTruststores("corp-ca");

        backend().apply(request("analytics", "trino", "trinodb/trino", "charts"), null);

        String source = published(TRINO_DIR + "/helmrepository.yaml");
        assertTrue(source, source.contains("  secretRef:\n    name: harbor-pull\n"
                + "  certSecretRef:\n    name: corporate-ca\n"));
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap(), anyMap());
        verify(k8s, never()).createOrUpdateDockerConfigSecret(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyMap());
    }

    @Test
    public void anAuthenticatedRepositoryWithoutCredentialsStopsTheDeployInsteadOfAWarning() throws Exception {
        HelmRepoEntity charts = chartRepos.get("charts");
        charts.setAuthMode("basic");
        charts.setUsername("robot");
        HelmDeployRequest request = request("analytics", "trino", "trinodb/trino", "charts");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> backend().checkCommittable(request, null));
        assertTrue(refused.getMessage(), refused.getMessage().contains("Chart repository 'charts' needs credentials"));
        assertTrue(refused.getMessage(), refused.getMessage().contains("no password stored"));

        assertThrows(IllegalStateException.class, () -> backend().apply(request, null));
        assertEquals("nothing was committed", seedCommit, remoteHead());
        verify(k8s, never()).createNamespace(anyString());
    }

    // ------------------------------------------------------------------- the layout of earlier versions moves on

    /**
     * A release as earlier versions committed it: its dependencies in its own directory, all chart sources in
     * flux-system.
     */
    private void legacyRelease() throws Exception {
        pushByHand(Map.of(
                TRINO_DIR + "/helmrelease.yaml", FluxManifests.helmRelease("trino", "analytics", null,
                        FluxManifests.helmReleaseSpec("trino", "1.43.14", "charts", "flux-system",
                                List.of(new FluxGitOpsBackend.DepRef("keda", "keda")), null, Map.of())),
                TRINO_DIR + "/helmrepository.yaml", FluxManifests.helmRepository("charts", "flux-system",
                        "https://charts.example.com", false, null, null, false),
                TRINO_DIR + "/dependencies/keda-helmrelease.yaml", FluxManifests.helmRelease("keda", "keda", null,
                        FluxManifests.helmReleaseSpec("keda", "2.18.0", "charts", "flux-system", List.of(),
                                GitOpsSecretValues.secretName("keda"), Map.of("replicas", 1))),
                TRINO_DIR + "/dependencies/" + WEBHOOK + "-helmrelease.yaml", FluxManifests.helmRelease(WEBHOOK,
                        "ambari-mutating-webhooks", null, FluxManifests.helmReleaseSpec(WEBHOOK, "0.1.0", "registry",
                                "flux-system", List.of(), null, Map.of())),
                TRINO_DIR + "/dependencies/registry-helmrepository.yaml", FluxManifests.helmRepository("registry",
                        "flux-system", "oci://registry.example.com/kdps", true, null, null, false)));
        K8sReleaseEntity entity = new K8sReleaseEntity();
        entity.setId(K8sReleaseEntity.idOf("analytics", "trino"));
        entity.setNamespace("analytics");
        entity.setReleaseName("trino");
        entity.setDeploymentMode("FLUX_GITOPS");
        entity.setGitRepoUrl(repoUrl);
        entity.setGitBranch("main");
        entity.setGitBaseBranch("main");
        entity.setGitCommitMode("DIRECT_COMMIT");
        entity.setGitPath(TRINO_DIR);
        entity.setRepoId("charts");
        entity.setVersion("1.43.14");
        releases.put(entity.getId(), entity);
    }

    @Test
    public void theNextDeployMovesTheDependenciesOfAnEarlierVersionToTheSharedDirectory() throws Exception {
        legacyRelease();
        // KEDA, installed by Flux from the release's directory, is on the cluster: still the release's to write.
        when(k8s.crdExists("scaledobjects.keda.sh")).thenReturn(true);

        backend().apply(trinoWithKeda(), null);

        Map<String, List<String>> objects = objectsUnderThePrefix();
        assertNoObjectDefinedTwice(objects);
        assertTrue("nothing is left in the release's directory but its own files: " + objects,
                objects.values().stream().flatMap(List::stream).noneMatch(f -> f.contains("/dependencies/")));
        // Written again by this deploy, with a source of its own.
        assertEquals(List.of(SHARED_DIR + "/keda/keda-helmrelease.yaml"), objects.get("HelmRelease keda/keda"));
        assertEquals(Map.of("kind", "HelmRepository", "name", "keda-charts"), sourceRefs().get("keda/keda"));
        // Moved as it was, with the source it reads, both kept by Flux.
        String webhook = published(SHARED_DIR + "/ambari-mutating-webhooks/" + WEBHOOK + "-helmrelease.yaml");
        assertTrue(webhook, webhook.contains("kustomize.toolkit.fluxcd.io/prune: disabled"));
        assertEquals(Map.of("kind", "HelmRepository", "name", "registry", "namespace", "flux-system"),
                sourceRefs().get("ambari-mutating-webhooks/" + WEBHOOK));
        assertEquals(List.of(SHARED_DIR + "/sources/flux-system/registry-helmrepository.yaml"),
                objects.get("HelmRepository flux-system/registry"));
        assertNull("no copy of the flux-system source of the release is left",
                objects.get("HelmRepository flux-system/charts"));
        assertEquals(List.of(TRINO_DIR + "/helmrepository.yaml"), objects.get("HelmRepository analytics/trino-charts"));
    }

    @Test
    public void theDependenciesOfAnEarlierVersionAreMarkedOnTheClusterBeforeTheirFilesMove() throws Exception {
        legacyRelease();
        String beforeTheMove = remoteHead();
        when(k8s.crdExists("scaledobjects.keda.sh")).thenReturn(true);

        backend().apply(trinoWithKeda(), null);

        // A Flux Kustomization covering the release's directory but not the shared one reads the annotation on the
        // cluster when the files leave it: they are marked there first.
        assertEquals(List.of("helmreleases keda/keda", "helmreleases ambari-mutating-webhooks/" + WEBHOOK,
                "helmrepositories flux-system/registry"), marked);
        marked.forEach(key -> assertEquals("marked before the move was pushed: " + key, beforeTheMove,
                headWhenMarked.get(key)));
        assertNotEquals(beforeTheMove, remoteHead());
    }

    @Test
    public void aDependencyKdpsCannotMarkOnTheClusterStaysInTheReleasesDirectoryUntilItCan() throws Exception {
        legacyRelease();
        unmarkable.add("helmreleases keda/keda");
        when(k8s.crdExists("scaledobjects.keda.sh")).thenReturn(true);

        backend().apply(trinoWithKeda(), null);

        Map<String, List<String>> objects = objectsUnderThePrefix();
        assertNoObjectDefinedTwice(objects);
        // Written again where it was, annotated, with the chart source it now reads next to it.
        assertEquals(List.of(TRINO_DIR + "/dependencies/keda-helmrelease.yaml"), objects.get("HelmRelease keda/keda"));
        assertTrue(published(TRINO_DIR + "/dependencies/keda-helmrelease.yaml")
                .contains("kustomize.toolkit.fluxcd.io/prune: disabled"));
        assertEquals(Map.of("kind", "HelmRepository", "name", "keda-charts"), sourceRefs().get("keda/keda"));
        assertEquals(List.of(TRINO_DIR + "/dependencies/keda-helmrepository.yaml"),
                objects.get("HelmRepository keda/keda-charts"));
        assertNull(published(SHARED_DIR + "/keda/keda-helmrelease.yaml"));
        // The one KDPS could mark moved on.
        assertEquals(List.of(SHARED_DIR + "/ambari-mutating-webhooks/" + WEBHOOK + "-helmrelease.yaml"),
                objects.get("HelmRelease ambari-mutating-webhooks/" + WEBHOOK));

        // Once KDPS can mark it, the next deploy moves it.
        unmarkable.clear();
        backend().apply(trinoWithKeda(), null);

        objects = objectsUnderThePrefix();
        assertNoObjectDefinedTwice(objects);
        assertEquals(List.of(SHARED_DIR + "/keda/keda-helmrelease.yaml"), objects.get("HelmRelease keda/keda"));
        assertTrue("nothing is left in the release's directory but its own files: " + objects,
                objects.values().stream().flatMap(List::stream).noneMatch(f -> f.contains("/dependencies/")));
    }

    @Test
    public void anUninstallLeavesTheDependenciesKdpsCannotMarkWhereTheyAreWithTheSourcesTheyRead() throws Exception {
        legacyRelease();
        unmarkable.add("helmreleases keda/keda");
        unmarkable.add("helmreleases ambari-mutating-webhooks/" + WEBHOOK);

        backend().destroy("analytics", "trino");

        Map<String, List<String>> objects = objectsUnderThePrefix();
        assertNoObjectDefinedTwice(objects);
        assertNull(objects.get("HelmRelease analytics/trino"));
        assertEquals(List.of(TRINO_DIR + "/dependencies/keda-helmrelease.yaml"), objects.get("HelmRelease keda/keda"));
        assertEquals(List.of(TRINO_DIR + "/dependencies/" + WEBHOOK + "-helmrelease.yaml"),
                objects.get("HelmRelease ambari-mutating-webhooks/" + WEBHOOK));
        // The source KEDA reads was the release's own, which goes: a copy stays next to it.
        assertEquals(List.of(TRINO_DIR + "/dependencies/charts-helmrepository.yaml"),
                objects.get("HelmRepository flux-system/charts"));
        assertEquals(List.of(TRINO_DIR + "/dependencies/registry-helmrepository.yaml"),
                objects.get("HelmRepository flux-system/registry"));
        for (String file : List.of("keda-helmrelease.yaml", WEBHOOK + "-helmrelease.yaml",
                "charts-helmrepository.yaml", "registry-helmrepository.yaml")) {
            assertTrue(file, published(TRINO_DIR + "/dependencies/" + file)
                    .contains("kustomize.toolkit.fluxcd.io/prune: disabled"));
        }
        assertTrue("nothing moved to the shared directory: " + objects,
                objects.values().stream().flatMap(List::stream).noneMatch(f -> f.startsWith(SHARED_DIR)));
        assertNull(releases.get(K8sReleaseEntity.idOf("analytics", "trino")));
    }

    @Test
    public void aSourceNamespaceSetWhileAnEarlierVersionsReleaseIsInGitKeepsEachSourceOnce() throws Exception {
        legacyRelease();
        viewProperties(Map.of(FluxGitOpsBackend.SOURCE_NAMESPACE_PROPERTY, "flux-system"));

        // Another release from the same repository: the source it writes is the one trino's directory still holds.
        backend().apply(request("bi", "superset", "apache/superset", "charts"), null);

        Map<String, List<String>> objects = objectsUnderThePrefix();
        assertNoObjectDefinedTwice(objects);
        assertEquals(List.of(SHARED_DIR + "/sources/flux-system/charts-helmrepository.yaml"),
                objects.get("HelmRepository flux-system/charts"));
        assertTrue("marked on the cluster before its copy leaves trino's directory: " + marked,
                marked.contains("helmrepositories flux-system/charts"));
        // Trino goes on reading the same object, as do its dependencies.
        assertEquals(Map.of("kind", "HelmRepository", "name", "charts", "namespace", "flux-system"),
                sourceRefs().get("analytics/trino"));
        assertNotNull(published(TRINO_DIR + "/helmrelease.yaml"));
        assertEquals(List.of(TRINO_DIR + "/dependencies/registry-helmrepository.yaml"),
                objects.get("HelmRepository flux-system/registry"));

        // Its uninstall moves its dependencies on without a second copy of the sources they read.
        backend().destroy("analytics", "trino");

        objects = objectsUnderThePrefix();
        assertNoObjectDefinedTwice(objects);
        assertNull(objects.get("HelmRelease analytics/trino"));
        assertEquals(List.of(SHARED_DIR + "/keda/keda-helmrelease.yaml"), objects.get("HelmRelease keda/keda"));
        assertEquals(List.of(SHARED_DIR + "/sources/flux-system/charts-helmrepository.yaml"),
                objects.get("HelmRepository flux-system/charts"));
        assertEquals(List.of(SHARED_DIR + "/sources/flux-system/registry-helmrepository.yaml"),
                objects.get("HelmRepository flux-system/registry"));
        assertNotNull("superset still reads it", published(SUPERSET_DIR.replace("/analytics/", "/bi/")
                + "/helmrelease.yaml"));
    }

    @Test
    public void aDependencyLeftInPlaceReadsTheSharedSourceInsteadOfACopy() throws Exception {
        legacyRelease();
        viewProperties(Map.of(FluxGitOpsBackend.SOURCE_NAMESPACE_PROPERTY, "flux-system"));
        unmarkable.add("helmreleases ambari-mutating-webhooks/" + WEBHOOK);
        when(k8s.crdExists("scaledobjects.keda.sh")).thenReturn(true);
        HelmDeployRequest trino = trinoWithKeda();
        // A dependency from the registry, written by this deploy: its source goes to the shared directory too.
        trino.getDependencies().put("exporter", new LinkedHashMap<>(Map.of("chart", "kdps/metrics-exporter",
                "releaseName", "metrics-exporter", "namespace", "monitoring", "repoId", "registry")));

        backend().apply(trino, null);

        Map<String, List<String>> objects = objectsUnderThePrefix();
        assertNoObjectDefinedTwice(objects);
        // The webhook KDPS could not mark stays in trino's directory and reads the registry source of sources/.
        assertEquals(List.of(TRINO_DIR + "/dependencies/" + WEBHOOK + "-helmrelease.yaml"),
                objects.get("HelmRelease ambari-mutating-webhooks/" + WEBHOOK));
        assertEquals(List.of(SHARED_DIR + "/sources/flux-system/registry-helmrepository.yaml"),
                objects.get("HelmRepository flux-system/registry"));
        assertEquals(List.of(SHARED_DIR + "/sources/flux-system/charts-helmrepository.yaml"),
                objects.get("HelmRepository flux-system/charts"));
    }

    @Test
    public void anUninstallMovesTheDependenciesOfAnEarlierVersionInsteadOfRemovingThem() throws Exception {
        legacyRelease();

        backend().destroy("analytics", "trino");

        Map<String, List<String>> objects = objectsUnderThePrefix();
        assertNoObjectDefinedTwice(objects);
        assertNull(objects.get("HelmRelease analytics/trino"));
        assertEquals(List.of(SHARED_DIR + "/keda/keda-helmrelease.yaml"), objects.get("HelmRelease keda/keda"));
        assertEquals(List.of(SHARED_DIR + "/ambari-mutating-webhooks/" + WEBHOOK + "-helmrelease.yaml"),
                objects.get("HelmRelease ambari-mutating-webhooks/" + WEBHOOK));
        // The release's own flux-system source, which KEDA still reads, moves along.
        assertEquals(List.of(SHARED_DIR + "/sources/flux-system/charts-helmrepository.yaml"),
                objects.get("HelmRepository flux-system/charts"));
        assertEquals(List.of(SHARED_DIR + "/sources/flux-system/registry-helmrepository.yaml"),
                objects.get("HelmRepository flux-system/registry"));
        assertTrue(published(SHARED_DIR + "/keda/keda-helmrelease.yaml").contains("name: keda-kdps-values"));
        verify(k8s, never()).deleteSecret("keda", "keda-kdps-values");
        assertNull(releases.get(K8sReleaseEntity.idOf("analytics", "trino")));
    }
}
