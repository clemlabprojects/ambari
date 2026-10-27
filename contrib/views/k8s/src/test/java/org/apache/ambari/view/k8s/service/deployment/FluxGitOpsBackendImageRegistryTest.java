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
import org.apache.ambari.view.k8s.service.HelmService;
import org.apache.ambari.view.k8s.service.cluster.ClusterRef;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A GitOps commit without a command plan pulls its images as a direct deploy does: from the image registry of the
 * chart repository (its registry host override), and with the image pull Secret as plain names and in the paths the
 * service definition adds. Each dependency gets the registry of its repository and its own pull Secret, created in its
 * namespace. What the operator set explicitly wins. Against a local bare repository.
 */
class FluxGitOpsBackendImageRegistryTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "trino";
    private static final String RELEASE_DIR = "clusters/default/analytics/trino";
    private static final String SHARED_DIR = "clusters/default/_shared";
    private static final String MIRROR = "harbor.bank/x";

    @TempDir
    Path tmp;

    private ViewContext ctx;
    private KubernetesService k8s;
    private HelmService helm;
    private String repoUrl;
    private final Map<String, String> secretPayloads = new ConcurrentHashMap<>();
    private final Map<String, K8sReleaseEntity> releases = new ConcurrentHashMap<>();

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
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
        DataStore ds = mock(DataStore.class);
        when(ctx.getDataStore()).thenReturn(ds);
        // The chart repository of the bank, whose images are mirrored on its Harbor.
        HelmRepoEntity charts = new HelmRepoEntity();
        charts.setId("charts");
        charts.setType("HTTP");
        charts.setUrl("https://charts.example.com");
        charts.setAuthMode("anonymous");
        charts.setImageRegistryHostOverride(MIRROR);
        when(ds.find(eq(HelmRepoEntity.class), eq("charts"))).thenReturn(charts);
        // Another chart repository, whose images are on its own host and project.
        HelmRepoEntity operators = new HelmRepoEntity();
        operators.setId("operators");
        operators.setType("HTTP");
        operators.setUrl("https://operators.example.com/charts");
        operators.setAuthMode("anonymous");
        operators.setImageProject("platform");
        when(ds.find(eq(HelmRepoEntity.class), eq("operators"))).thenReturn(operators);
        doAnswer(inv -> {
            K8sReleaseEntity entity = inv.getArgument(0);
            releases.put(entity.getId(), entity);
            return null;
        }).when(ds).store(any(K8sReleaseEntity.class));
        when(ds.find(eq(K8sReleaseEntity.class), anyString())).thenAnswer(inv -> releases.get((String) inv.getArgument(1)));
        when(ds.findAll(eq(K8sReleaseEntity.class), isNull())).thenAnswer(inv -> new java.util.ArrayList<>(releases.values()));

        k8s = mock(KubernetesService.class);
        when(k8s.readOpaqueSecretKeyAsString(anyString(), anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(secretPayloads.get(inv.getArgument(0) + "/" + inv.getArgument(1))));
        doAnswer(inv -> {
            secretPayloads.put(inv.getArgument(0) + "/" + inv.getArgument(1),
                    new String((byte[]) inv.getArgument(3), StandardCharsets.UTF_8));
            return null;
        }).when(k8s).createOrUpdateOpaqueSecret(anyString(), anyString(), anyString(), any(byte[].class), anyMap(), any(), any());
        helm = mock(HelmService.class);
        // The chart repositories have credentials: KDPS creates each pull Secret.
        when(helm.ensureImagePullSecretFromRepo(any(), any(), any(), any())).thenReturn(true);
    }

    private FluxGitOpsBackend backend() throws Exception {
        return backend(ClusterRef.DEFAULT);
    }

    /** The backend of the releases of a Kubernetes cluster. */
    private FluxGitOpsBackend backend(ClusterRef cluster) throws Exception {
        FluxGitOpsBackend backend = new FluxGitOpsBackend(tmp.resolve("flux"), k8s, ctx, cluster);
        backend.setPullRequests(new FakePullRequests());
        Field f = FluxGitOpsBackend.class.getDeclaredField("helmService");
        f.setAccessible(true);
        f.set(backend, helm);
        return backend;
    }

    /** A Trino deploy from the bank's chart repository, committed straight to main, which Flux reads. */
    private HelmDeployRequest request(String serviceKey) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace(NS);
        request.setReleaseName(RELEASE);
        request.setChart("trino");
        request.setVersion("1.43.9");
        request.setRepoId("charts");
        request.setServiceKey(serviceKey);
        request.setDeploymentMode("FLUX_GITOPS");
        request.setSecretName("registry-credentials");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("coordinator", new LinkedHashMap<>(Map.of("replicas", 1)));
        request.setValues(values);
        HelmDeployRequest.GitOptions git = new HelmDeployRequest.GitOptions();
        git.setRepoUrl(repoUrl);
        git.setBaseBranch("main");
        git.setCommitMode("DIRECT_COMMIT");
        request.setGit(git);
        return request;
    }

    private String published(String path) throws Exception {
        Path clone = tmp.resolve("verify-" + System.nanoTime());
        try (Git ignored = org.apache.ambari.view.k8s.utils.TestGitClones.clone(repoUrl, "main", clone)) {
            Path file = clone.resolve(path);
            return Files.exists(file) ? Files.readString(file) : null;
        }
    }

    private static void assertContains(String manifest, String... expected) {
        for (String e : expected) {
            assertTrue(manifest.contains(e), e + " in\n" + manifest);
        }
    }

    @Test
    void theReleasePullsFromTheRegistryOfItsChartRepositoryWithItsPullSecret() throws Exception {
        backend().apply(request("TRINO"), null);

        String helmRelease = published(RELEASE_DIR + "/helmrelease.yaml");
        assertContains(helmRelease,
                "      imageRegistry: \"" + MIRROR + "\"\n",
                // As a direct deploy passes it: a plain name in global.imagePullSecrets, never a {name: ...} map.
                "      imagePullSecrets:\n      - \"registry-credentials\"\n",
                "    imagePullSecrets:\n    - name: \"registry-credentials\"\n");
        assertFalse(helmRelease.contains("quay.io"), helmRelease);
        assertFalse(helmRelease.contains("name: \"registry.clemlab.com"), helmRelease);
        verify(helm).ensureImagePullSecretFromRepo("charts", NS, "registry-credentials", null);
    }

    @Test
    void withoutAPullSecretOnTheClusterTheReleaseAndItsDependenciesNameNone() throws Exception {
        // A chart repository without credentials, and nobody created the Secrets: nothing to name.
        when(helm.ensureImagePullSecretFromRepo(any(), any(), any(), any())).thenReturn(false);
        HelmDeployRequest request = request("TRINO");
        LinkedHashMap<String, Object> dependencies = new LinkedHashMap<>();
        dependencies.put("keda", new LinkedHashMap<>(Map.of("chart", "keda", "chartVersion", "2.18.0",
                "namespace", "keda", "secretName", "regcred",
                "imageRepository", "registry.clemlab.com/clemlabprojects",
                "imageGlobalRegistryProperty", "global.image.registry")));
        request.setDependencies(dependencies);

        backend().apply(request, null);

        String helmRelease = published(RELEASE_DIR + "/helmrelease.yaml");
        assertContains(helmRelease, "      imageRegistry: \"" + MIRROR + "\"\n");
        assertFalse(helmRelease.contains("imagePullSecrets"), helmRelease);
        String keda = published(SHARED_DIR + "/keda/keda-helmrelease.yaml");
        assertContains(keda, "    global:\n      image:\n        registry: \"" + MIRROR + "\"\n");
        assertFalse(keda.contains("imagePullSecrets"), keda);
        assertEquals("registry-credentials", request.getSecretName(), "the request keeps its Secret name for later deploys");
    }

    @Test
    void theSameReleaseOnTwoClustersGoesToTheFolderOfEachClusterWhenNoPrefixIsGiven() throws Exception {
        backend().apply(request("TRINO"), null);
        backend(ClusterRef.of("k8s21")).apply(request("TRINO"), null);

        // The default cluster's folder, as before clusters were named, and one for k8s21, as Flux bootstrap lays them out.
        String onDefault = published(RELEASE_DIR + "/helmrelease.yaml");
        String onK8s21 = published("clusters/k8s21/" + NS + "/" + RELEASE + "/helmrelease.yaml");
        assertNotNull(onDefault);
        assertEquals(onDefault, onK8s21);
        assertEquals("clusters/k8s21/" + NS + "/" + RELEASE,
                releases.get(K8sReleaseEntity.idOf(ClusterRef.of("k8s21"), NS, RELEASE)).getGitPath());
    }

    @Test
    void thePullSecretGoesToThePathsTheServiceDefinitionAdds() throws Exception {
        HelmDeployRequest request = request("OPENMETADATA");
        request.setChart("openmetadata");
        request.setValues(new LinkedHashMap<>());

        backend().apply(request, null);

        String helmRelease = published(RELEASE_DIR + "/helmrelease.yaml");
        // The Airflow and OpenSearch sub-charts of OpenMetadata read the pull Secret elsewhere.
        assertContains(helmRelease,
                "    dependencies:\n      airflow:\n        registry:\n          secretName: \"registry-credentials\"\n",
                "    opensearch:\n      imagePullSecrets:\n      - name: \"registry-credentials\"\n");
    }

    @Test
    void eachDependencyPullsFromTheRegistryOfItsRepositoryWithItsOwnPullSecret() throws Exception {
        HelmDeployRequest request = request("TRINO");
        LinkedHashMap<String, Object> dependencies = new LinkedHashMap<>();
        // As the service definitions declare them.
        dependencies.put("keda", new LinkedHashMap<>(Map.of("chart", "keda", "chartVersion", "2.18.0",
                "namespace", "keda", "secretName", "regcred",
                "imageRepository", "registry.clemlab.com/clemlabprojects",
                "imageGlobalRegistryProperty", "global.image.registry",
                "serviceAccounts", List.of("keda-operator", "keda-admission-webhooks"))));
        dependencies.put("kube-prometheus-stack", new LinkedHashMap<>(Map.of("chart", "kube-prometheus-stack",
                "chartVersion", "75.0.0", "namespace", "monitoring", "repoId", "operators", "secretName", "regcred",
                "imageRepository", "registry.clemlab.com/clemlabprojects",
                "imageGlobalRegistryProperty", "global.imageRegistry")));
        request.setDependencies(dependencies);

        backend().apply(request, null);

        String keda = published(SHARED_DIR + "/keda/keda-helmrelease.yaml");
        assertContains(keda, "    global:\n      image:\n        registry: \"" + MIRROR + "\"\n",
                "    imagePullSecrets:\n    - name: \"regcred\"\n");
        assertFalse(keda.contains("registry-credentials"),
                "the release's pull Secret does not exist in the keda namespace: " + keda);
        String prometheus = published(SHARED_DIR + "/monitoring/kube-prometheus-stack-helmrelease.yaml");
        assertContains(prometheus, "    global:\n      imageRegistry: \"operators.example.com/platform\"\n",
                "    imagePullSecrets:\n    - name: \"regcred\"\n");
        // Each dependency's pull Secret, created in its own namespace from the credentials of its repository.
        verify(helm).ensureImagePullSecretFromRepo("charts", "keda", "regcred",
                List.of("keda-operator", "keda-admission-webhooks"));
        verify(helm).ensureImagePullSecretFromRepo("operators", "monitoring", "regcred", List.of());
        verify(helm, never()).ensureImagePullSecretFromRepo(anyString(), eq("keda"), eq("registry-credentials"), any());
    }

    @Test
    void whatTheOperatorSetExplicitlyWins() throws Exception {
        HelmDeployRequest request = request("TRINO");
        request.getValues().put("_ov", new LinkedHashMap<>(Map.of(
                "global.imageRegistry", "nexus.bank/approved",
                "global.imagePullSecrets[0]", "bank-pull")));
        LinkedHashMap<String, Object> dependencies = new LinkedHashMap<>();
        dependencies.put("keda", new LinkedHashMap<>(Map.of("chart", "keda", "chartVersion", "2.18.0",
                "namespace", "keda", "secretName", "regcred",
                "imageGlobalRegistryProperty", "global.image.registry",
                "values", new LinkedHashMap<>(Map.of("global",
                        new LinkedHashMap<>(Map.of("image", new LinkedHashMap<>(Map.of("registry", "nexus.bank/keda")))))))));
        request.setDependencies(dependencies);

        backend().apply(request, null);

        String helmRelease = published(RELEASE_DIR + "/helmrelease.yaml");
        assertContains(helmRelease,
                "      imageRegistry: \"nexus.bank/approved\"\n",
                "      imagePullSecrets:\n      - \"bank-pull\"\n",
                "    imagePullSecrets:\n    - name: \"registry-credentials\"\n");
        assertFalse(helmRelease.contains(MIRROR), helmRelease);
        String keda = published(SHARED_DIR + "/keda/keda-helmrelease.yaml");
        assertContains(keda, "        registry: \"nexus.bank/keda\"\n", "    - name: \"regcred\"\n");
    }
}
