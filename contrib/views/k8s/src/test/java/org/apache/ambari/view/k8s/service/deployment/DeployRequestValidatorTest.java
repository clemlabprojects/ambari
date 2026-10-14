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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The names a deploy may give: what Helm and Kubernetes accept and, for a GitOps deploy, nothing that could end a YAML
 * value or name another directory in Git. A direct deploy keeps every chart reference Helm resolves.
 */
public class DeployRequestValidatorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static HelmDeployRequest request() {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setReleaseName("trino-prod");
        request.setNamespace("analytics");
        request.setChart("trinodb/trino");
        request.setRepoId("charts");
        request.setVersion("1.43.15");
        request.setDeploymentMode("FLUX_GITOPS");
        LinkedHashMap<String, Object> dependencies = new LinkedHashMap<>();
        Map<String, Object> keda = new LinkedHashMap<>();
        keda.put("chart", "keda");
        keda.put("namespace", "keda");
        keda.put("chartVersion", "2.18.0");
        keda.put("namespaceFromForm", "keda.namespace");
        dependencies.put("keda", keda);
        dependencies.put("kerberos-keytab-mutating-webhook", new LinkedHashMap<>(Map.of(
                "chart", "kerberos-keytab-mutating-webhook", "namespace", "ambari-mutating-webhooks")));
        request.setDependencies(dependencies);
        return request;
    }

    private static String refusal(Consumer<HelmDeployRequest> change) {
        HelmDeployRequest request = request();
        change.accept(request);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> DeployRequestValidator.validate(request, null, null));
        assertFalse("one line: " + refused.getMessage(), refused.getMessage().contains("\n"));
        return refused.getMessage();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> keda(HelmDeployRequest request) {
        return (Map<String, Object>) request.getDependencies().get("keda");
    }

    @Test
    public void theNamesOfTheServiceDefinitionsAreAccepted() {
        DeployRequestValidator.validate(request(), "charts", "1.43.15");
        for (String release : List.of("trino", "trino-prod", "a", "trino.analytics", "1trino", "a".repeat(53))) {
            DeployRequestValidator.checkReleaseName(release);
        }
        for (String chart : List.of("trino", "trinodb/trino", "apache/superset", " apache/superset ", "kube-prometheus-stack",
                "my_chart.v2")) {
            DeployRequestValidator.checkChart("Chart", chart);
        }
        for (String version : List.of("1.43.15", "0.1.0-rc.1+build.5", "^1.43.0", "~1.43", ">=1.0.0 <2.0.0", "1.x",
                "*", "1.0.0 || 2.0.0", "latest")) {
            DeployRequestValidator.checkChartVersionIfGiven("Chart version", version);
        }
    }

    @Test
    public void releaseNamesFollowHelmsRule() {
        assertEquals("Release name 'Trino_prod' is not valid: Helm accepts at most 53 lower-case letters, digits, '-'"
                + " and '.', starting and ending with a letter or digit, such as trino-prod.",
                refusal(r -> r.setReleaseName("Trino_prod")));
        for (String bad : List.of("Trino", "trino_prod", "trino prod", "trino: prod", "-trino", "trino-", "trino.",
                "../team-b/trino", "team-a/trino", "trino\n", "trino\n---\nkind: Secret", "a".repeat(54))) {
            assertTrue(bad, refusal(r -> r.setReleaseName(bad)).startsWith("Release name '"));
        }
        assertTrue(refusal(r -> r.setReleaseName(null)).startsWith("Enter a release name"));
        assertTrue(refusal(r -> r.setReleaseName("")).startsWith("Enter a release name"));
    }

    @Test
    public void namespacesAreDnsLabels() {
        for (String bad : List.of("Analytics", "team_a", "team.a", "team-a/../team-b", "a".repeat(64), "ns\n")) {
            assertTrue(bad, refusal(r -> r.setNamespace(bad)).startsWith("Namespace '"));
        }
        assertTrue(refusal(r -> r.setNamespace(null)).startsWith("Namespace is missing"));
    }

    @Test
    public void theChartOfAGitOpsDeployIsANameAfterAtMostOneRepository() {
        for (String bad : List.of("a/b/c", "trino:1.43.15", "oci://registry.example.com/trino", "../trino", "/trino",
                "trino/", "trino\n---\nkind: Secret", "trino #x", "-trino")) {
            assertTrue(bad, refusal(r -> r.setChart(bad)).startsWith("Chart '"));
        }
        assertTrue(refusal(r -> r.setChart("https://charts.example.com/trino-1.43.15.tgz"))
                .endsWith("a chart URL or an oci:// reference can only be deployed with Direct Helm."));
        assertTrue(refusal(r -> r.setChart(null)).startsWith("Chart is missing"));
    }

    /** As the Direct reference mode of the install dialog, an API caller or a resync of a release installed so. */
    private static HelmDeployRequest direct(String chart) {
        HelmDeployRequest request = request();
        request.setDeploymentMode("DIRECT_HELM");
        request.setChart(chart);
        return request;
    }

    @Test
    public void aDirectDeployAcceptsTheChartReferencesHelmResolves() {
        for (String chart : List.of("oci://registry.example.com/charts/trino", "oci://registry.example.com/kdps/trino",
                "https://charts.example.com/trino-1.43.15.tgz", "kdps/trino:1.2.3", "team/charts/trino",
                " trinodb/trino ", "trino")) {
            DeployRequestValidator.validate(direct(chart), "charts", "1.43.15");
        }
        // Helm reads the version as a constraint and checks it; the repository is only looked up by its id.
        HelmDeployRequest repository = direct("oci://registry.example.com/charts/trino");
        repository.setRepoId("repo-");
        DeployRequestValidator.validate(repository, "Charts", "!=1.2.3");
        HelmDeployRequest dependency = direct("trinodb/trino");
        keda(dependency).put("chart", "oci://ghcr.io/kedacore/charts/keda");
        keda(dependency).put("repoId", "-kedacore");
        DeployRequestValidator.validate(dependency, null, null);
    }

    @Test
    public void aDirectDeployRefusesAChartOrVersionThatIsNotOneLine() {
        for (String bad : List.of("trino\n---\nkind: Secret", "oci://registry.example.com/charts/trino\r",
                "trinodb/trino chart", "trino\tchart")) {
            IllegalArgumentException refused = assertThrows(bad, IllegalArgumentException.class,
                    () -> DeployRequestValidator.validate(direct(bad), null, null));
            assertTrue(refused.getMessage(), refused.getMessage().startsWith("Chart '"));
            assertFalse(refused.getMessage().contains("\n"));
        }
        for (String missing : new String[] {null, "", "  "}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> DeployRequestValidator.validate(direct(missing), null, null));
            assertTrue(refused.getMessage(), refused.getMessage().startsWith("Chart is missing"));
        }
        IllegalArgumentException version = assertThrows(IllegalArgumentException.class,
                () -> DeployRequestValidator.validate(direct("trinodb/trino"), null, "1.0.0\nkind: Secret"));
        assertTrue(version.getMessage(), version.getMessage().startsWith("Chart version '1.0.0?kind: Secret'"));
        HelmDeployRequest dependency = direct("trinodb/trino");
        keda(dependency).put("chart", "keda\n---\nkind: ClusterRoleBinding");
        IllegalArgumentException dependencyChart = assertThrows(IllegalArgumentException.class,
                () -> DeployRequestValidator.validate(dependency, null, null));
        assertTrue(dependencyChart.getMessage(), dependencyChart.getMessage().startsWith("Dependency 'keda': chart '"));
    }

    @Test
    public void aDirectDeployStillFollowsHelmsAndKubernetesNamingRules() {
        HelmDeployRequest release = direct("oci://registry.example.com/charts/trino");
        release.setReleaseName("Trino_prod");
        assertThrows(IllegalArgumentException.class, () -> DeployRequestValidator.validate(release, null, null));
        HelmDeployRequest namespace = direct("oci://registry.example.com/charts/trino");
        namespace.setNamespace("../team-b");
        assertThrows(IllegalArgumentException.class, () -> DeployRequestValidator.validate(namespace, null, null));
        HelmDeployRequest dependency = direct("trinodb/trino");
        keda(dependency).put("releaseName", "../../team-b/trino");
        assertThrows(IllegalArgumentException.class, () -> DeployRequestValidator.validate(dependency, null, null));
    }

    @Test
    public void whatIsCommittedToGitFollowsTheGitOpsRulesWhateverModeTheRequestNames() {
        HelmDeployRequest request = direct("oci://registry.example.com/charts/trino");
        DeployRequestValidator.validate(request, null, null);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> DeployRequestValidator.validateForGitOps(request, null, null));
        assertTrue(refused.getMessage(), refused.getMessage().startsWith("Chart 'oci://registry.example.com/charts/trino'"));
        HelmDeployRequest repository = direct("trinodb/trino");
        assertThrows(IllegalArgumentException.class,
                () -> DeployRequestValidator.validateForGitOps(repository, "repo-", null));
    }

    @Test
    public void aVersionHoldsOnlyTheCharactersOfAVersionRange() {
        for (String bad : List.of("1.0.0\n---", "1.0.0 # pinned", "1.0:latest", "1.0.0\r", "{1.0}", "1".repeat(65)) ) {
            assertTrue(bad, refusal(r -> r.setVersion(bad)).startsWith("Chart version '"));
        }
        HelmDeployRequest request = request();
        IllegalArgumentException queried = assertThrows(IllegalArgumentException.class,
                () -> DeployRequestValidator.validate(request, null, "1.0.0\nkind: Secret"));
        assertTrue("the query parameter too", queried.getMessage().startsWith("Chart version '1.0.0?kind: Secret'"));
    }

    @Test
    public void chartRepositoriesAreDnsLabels() {
        assertTrue(refusal(r -> r.setRepoId("Charts")).startsWith("Chart repository 'Charts' is not valid"));
        HelmDeployRequest request = request();
        assertThrows(IllegalArgumentException.class, () -> DeployRequestValidator.validate(request, "../x", null));
    }

    @Test
    public void everyNameOfADependencyIsChecked() {
        assertTrue(refusal(r -> r.getDependencies().put("Bad_Name", Map.of("chart", "x")))
                .startsWith("Dependency name 'Bad_Name' is not valid"));
        assertTrue(refusal(r -> keda(r).put("releaseName", "../../team-b/trino"))
                .startsWith("Dependency 'keda': release name '../../team-b/trino' is not valid"));
        assertTrue(refusal(r -> keda(r).put("namespace", "Keda"))
                .startsWith("Dependency 'keda': namespace 'Keda' is not valid"));
        assertTrue(refusal(r -> keda(r).put("repoId", "kedacore/x"))
                .startsWith("Dependency 'keda': chart repository 'kedacore/x' is not valid"));
        assertTrue(refusal(r -> keda(r).put("chart", "keda\n---\nkind: ClusterRoleBinding"))
                .startsWith("Dependency 'keda': chart 'keda?---?kind: ClusterRoleBinding' is not valid"));
        assertTrue(refusal(r -> keda(r).put("chartVersion", "2.18.0\nkind: Secret"))
                .startsWith("Dependency 'keda': chart version"));
        assertTrue(refusal(r -> keda(r).put("version", "2.18.0#x")).startsWith("Dependency 'keda': chart version"));
    }

    @Test
    public void theDependencyNamespacePickedInTheFormIsChecked() {
        String refused = refusal(r -> r.setFormValues(new LinkedHashMap<>(Map.of("keda", Map.of("namespace", "KEDA system")))));

        assertTrue(refused, refused.startsWith("Dependency 'keda': namespace 'KEDA system' is not valid"));
        HelmDeployRequest valid = request();
        valid.setFormValues(new LinkedHashMap<>(Map.of("keda", Map.of("namespace", "autoscaling"))));
        DeployRequestValidator.validate(valid, null, null);
        assertEquals("the request is not changed", "keda", keda(valid).get("namespace"));
    }

    @Test
    public void theGitPathPrefixOfAGitOpsDeployStaysInTheRepository() {
        assertTrue(refusal(r -> {
            r.setGit(new HelmDeployRequest.GitOptions());
            r.getGit().setPathPrefix("clusters/../../team-b");
        }).contains("cannot traverse outside repo"));
        assertTrue(refusal(r -> {
            r.setGit(new HelmDeployRequest.GitOptions());
            r.getGit().setPathPrefix("/etc/flux");
        }).contains("must be relative"));

        HelmDeployRequest direct = request();
        direct.setDeploymentMode("DIRECT_HELM");
        direct.setGit(new HelmDeployRequest.GitOptions());
        direct.getGit().setPathPrefix("../ignored");
        DeployRequestValidator.validate(direct, null, null);
    }

    // ------------------------------------------------------------------------------------------------ Git paths

    @Test
    public void aPathIsCheckedForTraversalBeforeItIsNormalised() {
        assertThrows(IllegalArgumentException.class,
                () -> FluxGitOpsBackend.validateRelative(Path.of("clusters/default/team-a/../team-b/trino")));
        assertThrows(IllegalArgumentException.class,
                () -> FluxGitOpsBackend.validateRelative(Path.of("clusters/default\n/trino")));
        assertEquals(Path.of("clusters/default/team-a/trino"),
                FluxGitOpsBackend.validateRelative(Path.of("clusters/./default//team-a/trino")));
    }

    @Test
    public void aReleaseDirectoryIsBuiltFromSingleSegments() throws Exception {
        ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", tmp.newFolder("work").toString()));
        FluxGitOpsBackend backend = new FluxGitOpsBackend(tmp.newFolder("flux").toPath(), null, ctx);
        assertEquals(Path.of("clusters/default/team-a/trino"), backend.targetPath("clusters/default", "team-a", "trino"));
        for (String[] bad : new String[][] {{"team-a", "../team-b/trino"}, {"team-a/../team-b", "trino"},
                {"team-a", ".."}, {"..", "trino"}, {"team-a", "a\\b"}, {"team-a", "trino\n"}, {"team-a", ""}}) {
            assertThrows(bad[0] + "/" + bad[1], IllegalArgumentException.class,
                    () -> backend.targetPath("clusters/default", bad[0], bad[1]));
        }
    }

    @Test
    public void aDependencyFileStaysInTheDependenciesDirectory() {
        Path dependencies = Path.of("clusters/default/team-a/trino/dependencies");
        assertEquals(dependencies.resolve("keda-helmrelease.yaml"),
                FluxGitOpsBackend.dependencyFile(dependencies, "keda", "-helmrelease.yaml"));
        for (String bad : List.of("../../../team-b/trino", "../x", "..", ".", "a/b", "")) {
            assertThrows(bad, IllegalArgumentException.class,
                    () -> FluxGitOpsBackend.dependencyFile(dependencies, bad, "-helmrelease.yaml"));
        }
    }

    @Test
    public void onlyTheReleasesOwnRecordedDirectoryIsUsed() {
        assertEquals(Path.of("clusters/default/team-a/trino"),
                FluxGitOpsBackend.releaseDirectory("clusters/default/team-a/trino", "team-a", "trino"));
        assertEquals(Path.of("team-a/trino"), FluxGitOpsBackend.releaseDirectory("team-a/trino", "team-a", "trino"));
        for (String bad : new String[] {null, "", " ", "clusters/default", "clusters/default/team-b/trino",
                "clusters/default/team-a", "clusters/default/team-a/trino-other", "clusters/default/team-a/../team-a/trino",
                "/clusters/default/team-a/trino"}) {
            IllegalArgumentException refused = assertThrows(String.valueOf(bad), IllegalArgumentException.class,
                    () -> FluxGitOpsBackend.releaseDirectory(bad, "team-a", "trino"));
            assertTrue(refused.getMessage(), refused.getMessage().contains("team-a/trino"));
        }
    }
}
