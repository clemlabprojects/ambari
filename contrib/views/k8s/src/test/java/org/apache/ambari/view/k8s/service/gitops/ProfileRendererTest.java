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

package org.apache.ambari.view.k8s.service.gitops;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GitOps output profiles: the files a profile writes around the chart, and what it must refuse before anything reaches
 * Git (a path outside the repository or under the chart path, a protected file, an unknown field, a secret value).
 */
class ProfileRendererTest {

    private static final String PASSWORD = "s3cr3t-bind-password";

    private static ProfileRenderer.RenderContext context() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("coordinator", Map.of("resources", Map.of("limits", Map.of("memory", "8Gi"))));
        values.put("version", "1.10");
        return new ProfileRenderer.RenderContext("analytics", "team-a", "TRINO", "clemlab-trino", "1.43.20",
                "oci://registry.example.org/charts", values, List.of("registry.example.org/trino:476"),
                List.of("team-a", "team-b"), "operator1", Instant.parse("2026-10-19T10:00:00Z"), null, "k8s21");
    }

    private static GitOpsProfile.FileRule rule(String path, String mode, String content) {
        GitOpsProfile.FileRule r = new GitOpsProfile.FileRule();
        r.path = path;
        r.mode = mode;
        r.content = content;
        return r;
    }

    /** A chart folder with one values file per environment, a README the team owns, and an index of environments. */
    private static GitOpsProfile environmentsLayout() {
        GitOpsProfile p = new GitOpsProfile();
        p.name = "environments";
        p.chartPath = "chart";
        p.variables.put("regions", "[\"region-a\",\"region-b\"]");
        p.files.add(rule("environments/{{ namespace }}/values.yaml", GitOpsProfile.MODE_ALWAYS, "{{ values | toYaml }}\n"));
        p.files.add(rule("environments/{{ namespace }}/README.md", GitOpsProfile.MODE_CREATE_IF_MISSING,
                "# {{ release }}\nchart: {{ chartPath }}\nimage tag: ${IMAGE_TAG}\n"));
        p.files.add(rule("environments/index.json", GitOpsProfile.MODE_CREATE_IF_MISSING,
                "{\"environments\": {{ instances | toJson }}, \"regions\": {{ vars.regions }}}\n"));
        p.protect.add("docs/**");
        return p;
    }

    @Test
    void aPathAndATemplateCanNameTheClusterDeployedTo() {
        GitOpsProfile p = environmentsLayout();
        p.files.add(rule("clusters/{{ cluster }}/{{ namespace }}/values.yaml", GitOpsProfile.MODE_ALWAYS,
                "cluster: {{ cluster }}\n"));

        ProfileRenderer.RenderedFile file = ProfileRenderer.render(p, context(), List.of()).get(3);

        assertEquals("clusters/k8s21/team-a/values.yaml", file.path());
        assertEquals("cluster: k8s21\n", file.content());
    }

    @Test
    void theFilesWrittenAlikeForEveryClusterAreTheAlwaysOnesNotNamingIt() {
        GitOpsProfile p = environmentsLayout();
        p.files.add(rule("clusters/{{cluster}}/{{ namespace }}/values.yaml", GitOpsProfile.MODE_ALWAYS, "x"));
        p.files.add(rule("clusters/{{ cluster }}/kustomization.yaml", GitOpsProfile.MODE_ALWAYS, "x"));

        // The README and the index are created once, then the team's: two clusters never overwrite them.
        assertEquals(List.of("environments/{{ namespace }}/values.yaml"), ProfileRenderer.pathsNotNamingTheCluster(p));
    }

    @Test
    void aLayoutIsRenderedAroundTheChart() {
        List<ProfileRenderer.RenderedFile> files = ProfileRenderer.render(environmentsLayout(), context(), List.of(PASSWORD));

        assertEquals(List.of("environments/team-a/values.yaml", "environments/team-a/README.md", "environments/index.json"),
                files.stream().map(ProfileRenderer.RenderedFile::path).toList());
        assertTrue(files.get(0).content().contains("memory: \"8Gi\""), files.get(0).content());
        assertTrue(files.get(0).content().contains("version: \"1.10\""), "a version stays text: " + files.get(0).content());
        assertEquals("# analytics\nchart: chart\nimage tag: ${IMAGE_TAG}\n", files.get(1).content(),
                "another tool's placeholder passes through");
        assertEquals("{\"environments\": [\"team-a\",\"team-b\"], \"regions\": [\"region-a\",\"region-b\"]}\n",
                files.get(2).content());
        assertEquals(GitOpsProfile.MODE_CREATE_IF_MISSING, files.get(1).mode());
    }

    @Test
    void theFluxProfileIsWrittenByTheFluxBackendNotByFileRules() {
        // Today's GitOps output comes from the Flux backend unchanged: the built-in profile only names it.
        GitOpsProfile flux = GitOpsProfile.flux();
        ProfileRenderer.validate(flux);
        assertTrue(flux.builtIn);
        assertThrows(IllegalArgumentException.class, () -> ProfileRenderer.render(flux, context(), List.of()));
    }

    @Test
    void noSecretValueReachesAFile() {
        GitOpsProfile p = environmentsLayout();
        p.files.add(rule("leak.yaml", GitOpsProfile.MODE_ALWAYS, "password: {{ values.ldap.bindPassword }}\n"));
        Map<String, Object> leaking = new LinkedHashMap<>(context().values());
        leaking.put("ldap", Map.of("bindPassword", PASSWORD));
        ProfileRenderer.RenderContext ctx = new ProfileRenderer.RenderContext("analytics", "team-a", "TRINO",
                "clemlab-trino", "1.43.20", "oci://x", leaking, List.of(), List.of(), "op", Instant.now(), null, "default");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ProfileRenderer.render(p, ctx, List.of(PASSWORD)));
        // The values file dumps every value, so it is the first file refused.
        assertTrue(refused.getMessage().startsWith("File environments/team-a/values.yaml would hold a password"),
                refused.getMessage());
        assertFalse(refused.getMessage().contains(PASSWORD), "the message never shows the secret");
    }

    @Test
    void aPathOutsideTheRepositoryIsRefusedWhenSavedAndWhenRendered() {
        for (String path : List.of("../outside.yaml", "/etc/passwd", "a/../../b", ".git/config", "a\\b", "C:/x", "a//b")) {
            GitOpsProfile p = environmentsLayout();
            p.files.add(rule(path, GitOpsProfile.MODE_ALWAYS, "x"));
            assertThrows(IllegalArgumentException.class, () -> ProfileRenderer.validate(p), path);
        }
        // A variable cannot smuggle one in either.
        GitOpsProfile p = environmentsLayout();
        p.variables.put("dir", "../..");
        p.files.add(rule("{{ vars.dir }}/x.yaml", GitOpsProfile.MODE_ALWAYS, "x"));
        ProfileRenderer.validate(p);
        assertThrows(IllegalArgumentException.class, () -> ProfileRenderer.render(p, context(), List.of()));
    }

    @Test
    void noRuleWritesUnderTheChartPathNorAProtectedFileNorTheSamePathTwice() {
        GitOpsProfile underChart = environmentsLayout();
        underChart.files.add(rule("{{ chartPath }}/values.yaml", GitOpsProfile.MODE_ALWAYS, "x"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ProfileRenderer.render(underChart, context(), List.of())).getMessage().contains("part of the chart"));
        GitOpsProfile templates = environmentsLayout();
        templates.files.add(rule("chart/templates/extra.yaml", GitOpsProfile.MODE_ALWAYS, "x"));
        assertThrows(IllegalArgumentException.class, () -> ProfileRenderer.render(templates, context(), List.of()));

        // Another folder of the chart folder holds the profile's files.
        GitOpsProfile beside = environmentsLayout();
        beside.files.add(rule("{{ chartPath }}/environments/{{ namespace }}.yaml", GitOpsProfile.MODE_ALWAYS, "x"));
        assertTrue(ProfileRenderer.render(beside, context(), List.of()).stream()
                .anyMatch(f -> f.path().equals("chart/environments/team-a.yaml")));

        GitOpsProfile protectedFile = environmentsLayout();
        protectedFile.files.add(rule("docs/runbook.md", GitOpsProfile.MODE_ALWAYS, "x"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ProfileRenderer.render(protectedFile, context(), List.of())).getMessage().contains("protected"));

        GitOpsProfile twice = environmentsLayout();
        twice.files.add(rule("environments/index.json", GitOpsProfile.MODE_ALWAYS, "x"));
        assertThrows(IllegalArgumentException.class, () -> ProfileRenderer.render(twice, context(), List.of()));
    }

    @Test
    void anUnknownFieldOrFilterFailsWithTheFileAndLine() {
        GitOpsProfile p = environmentsLayout();
        p.files.add(rule("notes.txt", GitOpsProfile.MODE_ALWAYS, "release: {{ release }}\nowner: {{ owner }}\n"));
        FileTemplate.TemplateException unknownField = assertThrows(FileTemplate.TemplateException.class,
                () -> ProfileRenderer.render(p, context(), List.of()));
        assertEquals("notes.txt line 2: unknown field 'owner'", unknownField.getMessage());

        GitOpsProfile q = environmentsLayout();
        q.files.add(rule("notes.txt", GitOpsProfile.MODE_ALWAYS, "{{ release | upper }}"));
        assertTrue(assertThrows(FileTemplate.TemplateException.class,
                () -> ProfileRenderer.render(q, context(), List.of())).getMessage().contains("unknown filter 'upper'"));
    }

    @Test
    void aProfileWithAWrongNameModeOrDeliveryIsRefused() {
        GitOpsProfile badName = environmentsLayout();
        badName.name = "My Profile";
        assertThrows(IllegalArgumentException.class, () -> ProfileRenderer.validate(badName));

        GitOpsProfile badMode = environmentsLayout();
        badMode.files.get(0).mode = "sometimes";
        assertThrows(IllegalArgumentException.class, () -> ProfileRenderer.validate(badMode));

        GitOpsProfile noPrefix = environmentsLayout();
        noPrefix.delivery.branchPrefix = " ";
        assertThrows(IllegalArgumentException.class, () -> ProfileRenderer.validate(noPrefix));
    }

    @Test
    void theFilesOfOneInstanceAreItsAlwaysFilesNamingItsNamespaceOrRelease() {
        GitOpsProfile p = environmentsLayout();
        p.files.add(rule("shared/{{ vars.regions }}.txt", GitOpsProfile.MODE_ALWAYS, "x"));
        p.files.add(rule("releases/{{ release }}.yaml", GitOpsProfile.MODE_ALWAYS, "x"));
        p.files.add(rule("owned/{{ namespace }}.md", GitOpsProfile.MODE_CREATE_IF_MISSING, "x"));

        assertEquals(List.of("environments/team-a/values.yaml", "releases/analytics.yaml"),
                ProfileRenderer.instancePaths(p, context()));
    }

    @Test
    void theInstancesAreReadFromTheRepository(@org.junit.jupiter.api.io.TempDir java.nio.file.Path repo) throws Exception {
        for (String f : List.of("team-b/values.yaml", "team-c/values.yaml", "team-d/notes.txt", "Bad_Name/values.yaml")) {
            java.nio.file.Files.createDirectories(repo.resolve("environments/" + f).getParent());
            java.nio.file.Files.writeString(repo.resolve("environments/" + f), "x");
        }
        assertEquals(List.of("team-a", "team-b", "team-c"), ProfileRenderer.instances(environmentsLayout(), context(), repo));
    }

    @Test
    void imagesAreReadFromTheValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("image", Map.of("registry", "registry.example.org/", "repository", "trino", "tag", "476"));
        values.put("sidecar", Map.of("image", "busybox:1.36"));
        values.put("notAnImage", Map.of("repository", "https://git.example.org/x.git"));
        assertEquals(List.of("busybox:1.36", "registry.example.org/trino:476"), ProfileRenderer.images(values));
    }
}
