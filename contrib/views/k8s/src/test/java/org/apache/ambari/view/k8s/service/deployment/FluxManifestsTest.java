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

import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * The Flux manifests built as maps: the same text the templates wrote for valid names, and a single YAML document
 * holding each name as one value whatever it contains.
 */
public class FluxManifestsTest {

    /** What the templates wrote for {@link #richValues()}: the expected text of the golden tests below. */
    private static final String RELEASE_AS_THE_TEMPLATES_WROTE_IT = """
            apiVersion: helm.toolkit.fluxcd.io/v2
            kind: HelmRelease
            metadata:
              name: trino
              namespace: analytics
              annotations:
                ambari.clemlab.com/spec-digest: "3271cc28fd8c92fbbef6077c1599fa985a08ea00deea0333b8394e55e5794336"
            spec:
              interval: 5m
              timeout: 15m
              install:
                remediation:
                  retries: 3
              dependsOn:
              - name: ldap-proxy
                namespace: analytics
              - name: keda
                namespace: keda
              chart:
                spec:
                  chart: trino
                  version: 1.43.15
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
                  workers: 3
                  autoscaling:
                    enabled: true
                image:
                  tag: "476"
                additionalConfigProperties:
                - "query.max-memory=4GB"
                - "internal-communication.https.required=true"
                catalogs:
                  hive: "connector.name=hive\\nhive.metastore.uri=thrift://hms:9083\\n"
                ingress:
                  hosts:
                  - host: "trino.example.com"
                    paths:
                    - "/"
                note: "key: value # not a comment"
                ratio: 0.75
                enabled: false
                nothing: null
                labels: {}
                tolerations: []
                appVersion: "1.0"
                flag: "true"
                greeting: "Grüße, ça va ?"
                matrix:
                - - 1
                  - 2
                - - "a"
                global.dotted: "kept"
            """;

    private static final String DEPENDENCY_AS_THE_TEMPLATES_WROTE_IT = """
            apiVersion: helm.toolkit.fluxcd.io/v2
            kind: HelmRelease
            metadata:
              name: keda
              namespace: keda
            spec:
              interval: 5m
              timeout: 15m
              install:
                remediation:
                  retries: 3
              chart:
                spec:
                  chart: keda
                  sourceRef:
                    kind: HelmRepository
                    name: kedacore
                    namespace: flux-system
              values:
                watchNamespace: ""
                replicas: 2
            """;

    private static Map<String, Object> richValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("workers", 3);
        server.put("autoscaling", new LinkedHashMap<>(Map.of("enabled", true)));
        values.put("server", server);
        values.put("image", new LinkedHashMap<>(Map.of("tag", "476")));
        values.put("additionalConfigProperties", new ArrayList<>(List.of("query.max-memory=4GB",
                "internal-communication.https.required=true")));
        values.put("catalogs", new LinkedHashMap<>(Map.of("hive",
                "connector.name=hive\nhive.metastore.uri=thrift://hms:9083\n")));
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("host", "trino.example.com");
        host.put("paths", new ArrayList<>(List.of("/")));
        values.put("ingress", new LinkedHashMap<>(Map.of("hosts", new ArrayList<>(List.of(host)))));
        values.put("note", "key: value # not a comment");
        values.put("ratio", 0.75);
        values.put("enabled", false);
        values.put("nothing", null);
        values.put("labels", new LinkedHashMap<>());
        values.put("tolerations", new ArrayList<>());
        values.put("appVersion", "1.0");
        values.put("flag", "true");
        values.put("greeting", "Grüße, ça va ?");
        values.put("matrix", new ArrayList<>(List.of(new ArrayList<>(List.of(1, 2)), new ArrayList<>(List.of("a")))));
        values.put("global.dotted", "kept");
        return values;
    }

    private static Map<String, Object> releaseSpec(String chart, String version, List<FluxGitOpsBackend.DepRef> deps,
                                                   Map<String, Object> values) {
        return FluxManifests.helmReleaseSpec(chart, version, "charts", "flux-system", deps, "trino-kdps-values",
                values);
    }

    private static String release(String name, String namespace, Map<String, Object> spec) {
        return FluxManifests.helmRelease(name, namespace,
                FluxGitOpsBackend.specDigest(FluxManifests.specYaml(spec)), spec);
    }

    /** The manifest as Flux reads it: exactly one YAML document. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyDocument(String yaml) {
        List<Object> documents = new ArrayList<>();
        new Yaml().loadAll(yaml).forEach(documents::add);
        assertEquals("one manifest, one document:\n" + yaml, 1, documents.size());
        return (Map<String, Object>) documents.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Object at(Map<String, Object> document, String... path) {
        Object node = document;
        for (String key : path) {
            node = ((Map<String, Object>) node).get(key);
        }
        return node;
    }

    // ------------------------------------------------------------------------------------------- the same text

    @Test
    public void aReleaseIsWrittenExactlyAsTheTemplatesWroteItWithTheSameSpecDigest() {
        Map<String, Object> spec = releaseSpec("trino", "1.43.15", List.of(
                new FluxGitOpsBackend.DepRef("ldap-proxy", "analytics"), new FluxGitOpsBackend.DepRef("keda", "keda")),
                richValues());

        assertEquals(RELEASE_AS_THE_TEMPLATES_WROTE_IT, release("trino", "analytics", spec));
    }

    @Test
    public void aDependencyIsWrittenExactlyAsTheTemplatesWroteIt() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("watchNamespace", "");
        values.put("replicas", 2);

        assertEquals(DEPENDENCY_AS_THE_TEMPLATES_WROTE_IT, FluxManifests.helmRelease("keda", "keda", null,
                FluxManifests.helmReleaseSpec("keda", null, "kedacore", "flux-system", List.of(), null, values)));
    }

    @Test
    public void chartRepositoriesAreWrittenExactlyAsTheTemplatesWroteThem() {
        assertEquals("""
                apiVersion: source.toolkit.fluxcd.io/v1
                kind: HelmRepository
                metadata:
                  name: charts
                  namespace: flux-system
                spec:
                  interval: 5m
                  url: https://charts.example.com/
                """, FluxGitOpsBackend.renderHelmRepository("charts", "flux-system", "HTTP",
                "https://charts.example.com/", null, null, false));
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
                """, FluxGitOpsBackend.renderHelmRepository("registry", "flux-system", "OCI",
                "registry.example.com/kdps", null, null, false));
    }

    // ------------------------------------------------------------ chart sources of a release's own, shared objects

    @Test
    public void aSourceInTheReleaseNamespaceIsReferencedWithoutANamespace() {
        String yaml = FluxManifests.helmRelease("trino", "analytics", null,
                FluxManifests.helmReleaseSpec("trino", "1.43.15", "trino-charts", null, List.of(), null, Map.of()));

        assertEquals(Map.of("kind", "HelmRepository", "name", "trino-charts"),
                at(onlyDocument(yaml), "spec", "chart", "spec", "sourceRef"));
    }

    @Test
    public void aChartSourceNamesTheSecretsOfItsCredentialsAndOfItsCa() {
        assertEquals("""
                apiVersion: source.toolkit.fluxcd.io/v1
                kind: HelmRepository
                metadata:
                  name: trino-registry
                  namespace: analytics
                spec:
                  interval: 5m
                  type: oci
                  url: oci://harbor.example.com/kdps
                  secretRef:
                    name: trino-registry-repo-auth
                  certSecretRef:
                    name: trino-registry-repo-ca
                """, FluxGitOpsBackend.renderHelmRepository("trino-registry", "analytics", "OCI",
                "https://harbor.example.com/kdps", "trino-registry-repo-auth", "trino-registry-repo-ca", false));
    }

    @Test
    public void whatSeveralReleasesShareIsNeverPrunedByFlux() {
        Map<String, Object> source = onlyDocument(FluxGitOpsBackend.renderHelmRepository("keda-charts", "keda", "HTTP",
                "https://charts.example.com", null, null, true));
        Map<String, Object> dependency = onlyDocument(FluxManifests.helmRelease("keda", "keda", null,
                FluxManifests.helmReleaseSpec("keda", "2.18.0", "keda-charts", null, List.of(), null, Map.of()), true));
        Map<String, Object> release = onlyDocument(FluxManifests.helmRelease("trino", "analytics", "abc",
                FluxManifests.helmReleaseSpec("trino", "1.0.0", "trino-charts", null, List.of(), null, Map.of())));

        assertEquals(Map.of("kustomize.toolkit.fluxcd.io/prune", "disabled"), at(source, "metadata", "annotations"));
        assertEquals(Map.of("kustomize.toolkit.fluxcd.io/prune", "disabled"),
                at(dependency, "metadata", "annotations"));
        assertEquals("a release's own HelmRelease goes with its directory",
                Map.of("ambari.clemlab.com/spec-digest", "abc"), at(release, "metadata", "annotations"));
    }

    @Test
    public void aManifestMovedOutOfAReleaseDirectoryIsKeptByFlux() {
        String moved = FluxManifests.keptByFlux(DEPENDENCY_AS_THE_TEMPLATES_WROTE_IT);

        Map<String, Object> document = onlyDocument(moved);
        assertEquals(Map.of("kustomize.toolkit.fluxcd.io/prune", "disabled"), at(document, "metadata", "annotations"));
        assertEquals(onlyDocument(DEPENDENCY_AS_THE_TEMPLATES_WROTE_IT).get("spec"), document.get("spec"));
        assertEquals("not one readable document: left as it is", "kind: [unclosed",
                FluxManifests.keptByFlux("kind: [unclosed"));
    }

    @Test
    public void releaseWithoutValuesIsStillValidYaml() {
        // The templates wrote "values:" followed by "{}" at the start of the next line, which no YAML parser reads.
        String yaml = FluxManifests.helmRelease("keda", "keda", null,
                FluxManifests.helmReleaseSpec("keda", "2.18.0", "kedacore", null, List.of(), null, Map.of()));

        assertTrue(yaml, yaml.endsWith("  values: {}\n"));
        assertEquals(Map.of(), at(onlyDocument(yaml), "spec", "values"));
    }

    // -------------------------------------------------------------------------------------- one value per name

    @Test
    public void aChartThatTriesToStartAnotherDocumentStaysTheChartName() {
        String chart = "trino\n---\napiVersion: rbac.authorization.k8s.io/v1\nkind: ClusterRoleBinding";

        Map<String, Object> document = onlyDocument(release("trino", "analytics",
                releaseSpec(chart, "1.0.0", List.of(), Map.of())));

        assertEquals(chart, at(document, "spec", "chart", "spec", "chart"));
        assertEquals("HelmRelease", document.get("kind"));
    }

    @Test
    public void aVersionThatTriesToAddAKeyStaysTheVersion() {
        String version = "1.0.0\n  sourceRef: {kind: GitRepository, name: attacker}";

        Map<String, Object> document = onlyDocument(release("trino", "analytics",
                releaseSpec("trino", version, List.of(), Map.of())));

        assertEquals(version, at(document, "spec", "chart", "spec", "version"));
        assertEquals("charts", at(document, "spec", "chart", "spec", "sourceRef", "name"));
    }

    @Test
    public void namesWithYamlSyntaxStayOneValue() {
        String name = "trino: prod # comment";
        String namespace = "team-a\n---\nkind: Namespace";

        Map<String, Object> document = onlyDocument(release(name, namespace, releaseSpec("trino", "1.0.0",
                List.of(new FluxGitOpsBackend.DepRef("keda\n- name: other", "keda: x")), Map.of())));

        assertEquals(name, at(document, "metadata", "name"));
        assertEquals(namespace, at(document, "metadata", "namespace"));
        List<?> dependsOn = (List<?>) at(document, "spec", "dependsOn");
        assertEquals(1, dependsOn.size());
        assertEquals(Map.of("name", "keda\n- name: other", "namespace", "keda: x"), dependsOn.get(0));
    }

    @Test
    public void aRepositoryUrlStaysOneValue() {
        String url = "https://charts.example.com\n---\nkind: Secret";

        Map<String, Object> document = onlyDocument(FluxManifests.helmRepository("charts", "flux-system", url, false,
                null, null, false));

        assertEquals(url, at(document, "spec", "url"));
    }

    @Test
    public void namesThatReadAsOtherTypesStayText() {
        Map<String, Object> document = onlyDocument(release("trino", "analytics",
                releaseSpec("true", "1.0", List.of(), Map.of())));

        assertEquals("a version \"1.0\" must not reach Flux as a number", "1.0",
                at(document, "spec", "chart", "spec", "version"));
        assertEquals("true", at(document, "spec", "chart", "spec", "chart"));
        for (String typed : List.of("null", "~", "yes", "on", "0x1f", "1e3", "2026-10-12", "")) {
            Object chart = at(onlyDocument(release("trino", "analytics", releaseSpec(typed, "1.0.0", List.of(),
                    Map.of()))), "spec", "chart", "spec", "chart");
            assertEquals(typed, chart);
        }
    }

    @Test
    public void aReleaseGetsTheDirectInstallTimeoutAndRetriesAFailedFirstInstallOnly() {
        Map<String, Object> spec = releaseSpec("trino", "1.43.15", List.of(), Map.of());

        assertEquals("15m", spec.get("timeout"));
        assertEquals("a failed first install is tried again", Map.of("remediation", Map.of("retries", 3)),
                spec.get("install"));
        assertFalse("an upgrade is never retried or rolled back after a forward-only migration ran",
                spec.containsKey("upgrade"));
    }

    @Test
    public void aDependencyGetsTheSameTimeoutAndInstallRetries() {
        Map<String, Object> document = onlyDocument(FluxManifests.helmRelease("keda", "keda", null,
                FluxManifests.helmReleaseSpec("keda", "2.18.0", "kedacore", null, List.of(), null, Map.of()), true));

        assertEquals("15m", at(document, "spec", "timeout"));
        assertEquals(3, at(document, "spec", "install", "remediation", "retries"));
        assertNull(at(document, "spec", "upgrade"));
    }

    @Test
    public void aServiceCanAskForALongerTimeout() {
        Map<String, Object> document = onlyDocument(release("gitlab", "dev", FluxManifests.helmReleaseSpec("gitlab",
                "8.11.2", "charts", null, List.of(), null, Map.of(), "20m")));

        assertEquals("20m", at(document, "spec", "timeout"));
        assertEquals(3, at(document, "spec", "install", "remediation", "retries"));
    }
}
