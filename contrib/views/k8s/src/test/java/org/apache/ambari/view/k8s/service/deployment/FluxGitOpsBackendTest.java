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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class FluxGitOpsBackendTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void applyOverrides_setsNestedValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("global.security.kerberos.enabled", "true");
        overrides.put("global.security.kerberos.configMapName", "krb5-cm");

        FluxGitOpsBackend.applyOverridesToValues(values, overrides);

        assertTrue(values.containsKey("global"));
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) values.get("global");
        @SuppressWarnings("unchecked")
        Map<String, Object> sec = (Map<String, Object>) g.get("security");
        assertNotNull(sec);
        @SuppressWarnings("unchecked")
        Map<String, Object> krb = (Map<String, Object>) sec.get("kerberos");
        assertNotNull(krb);
        assertEquals(Boolean.TRUE, krb.get("enabled"));
        assertEquals("krb5-cm", krb.get("configMapName"));

    }

    @Test
    public void dependsOnRendersList() {
        List<FluxGitOpsBackend.DepRef> deps = List.of(
                new FluxGitOpsBackend.DepRef("dep1", "ns1"),
                new FluxGitOpsBackend.DepRef("dep2", "ns2")
        );

        String yaml = FluxManifests.specYaml(FluxManifests.helmReleaseSpec("trino", "1.0.0", "trino-charts", null, deps,
                null, Map.of()));

        assertTrue(yaml, yaml.contains("  dependsOn:\n  - name: dep1\n    namespace: ns1\n"
                + "  - name: dep2\n    namespace: ns2\n"));
    }

    @Test
    public void ociChartRepositoryIsDeclaredAsAnOciHelmRepository() {
        String yaml = FluxGitOpsBackend.renderHelmRepository("trino-clemlab", "analytics", "OCI",
                "registry.example.com/charts", null, null, false);

        assertTrue(yaml, yaml.contains("name: trino-clemlab"));
        assertTrue(yaml, yaml.contains("  type: oci\n"));
        assertTrue(yaml, yaml.contains("  url: oci://registry.example.com/charts\n"));
    }

    @Test
    public void ociUrlWithASchemeKeepsASingleOciScheme() {
        String yaml = FluxGitOpsBackend.renderHelmRepository("r", "analytics", "OCI",
                "oci://registry.example.com/charts", null, null, false);

        assertTrue(yaml, yaml.contains("url: oci://registry.example.com/charts"));
        assertFalse(yaml, yaml.contains("oci://oci://"));
    }

    @Test
    public void httpChartRepositoryKeepsItsUrlAndHasNoType() {
        String yaml = FluxGitOpsBackend.renderHelmRepository("bitnami", "analytics", "HTTP",
                "https://charts.example.com/", null, null, false);

        assertTrue(yaml, yaml.contains("  url: https://charts.example.com/\n"));
        assertFalse(yaml, yaml.contains("type:"));
    }

    @Test
    public void onlyTheManagedContextWithoutAnExternalKeytabFollowsAmbariKerberos() {
        org.apache.ambari.view.k8s.requests.HelmDeployRequest managed = new org.apache.ambari.view.k8s.requests.HelmDeployRequest();
        assertTrue(FluxGitOpsBackend.followsAmbariKerberos(managed));

        org.apache.ambari.view.k8s.requests.HelmDeployRequest external = new org.apache.ambari.view.k8s.requests.HelmDeployRequest();
        external.setFormValues(new LinkedHashMap<>(Map.of("platformContextId", "cdp-prod")));
        assertFalse("an external context must not inherit the local realm", FluxGitOpsBackend.followsAmbariKerberos(external));

        org.apache.ambari.view.k8s.requests.HelmDeployRequest keytab = new org.apache.ambari.view.k8s.requests.HelmDeployRequest();
        keytab.setFormValues(new LinkedHashMap<>(Map.of("hive", Map.of("externalKeytabSecret", "hive-keytab"))));
        assertFalse("an operator keytab decides Kerberos itself", FluxGitOpsBackend.followsAmbariKerberos(keytab));
    }

    @Test
    public void kerberosFollowsTheProfileThenTheAmbariCluster() {
        assertEquals("true", FluxGitOpsBackend.kerberosEnabledValue(true, null));
        assertEquals("true", FluxGitOpsBackend.kerberosEnabledValue(true, Boolean.FALSE));
        assertEquals("true", FluxGitOpsBackend.kerberosEnabledValue(false, Boolean.TRUE));
        assertEquals("a cluster without Kerberos, or no cluster, turns the chart default off",
                "false", FluxGitOpsBackend.kerberosEnabledValue(false, Boolean.FALSE));
        assertNull("unknown state leaves the chart default", FluxGitOpsBackend.kerberosEnabledValue(false, null));
    }

    @Test
    public void overridesAreTypedLikeHelmSet() {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("a.off", "false");
        overrides.put("a.count", "3");
        overrides.put("a.name", "trino-krb5-conf");

        FluxGitOpsBackend.applyOverridesToValues(values, overrides);

        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) values.get("a");
        assertEquals("a string \"false\" would be truthy in the chart templates", Boolean.FALSE, a.get("off"));
        assertEquals(3, a.get("count"));
        assertEquals("trino-krb5-conf", a.get("name"));
    }

    @Test
    public void onlyWhatHelmSetTypesIsTyped() {
        assertEquals(Boolean.FALSE, FluxGitOpsBackend.parseScalar("false"));
        assertEquals(476, FluxGitOpsBackend.parseScalar("476"));
        assertEquals(0, FluxGitOpsBackend.parseScalar("0"));
        assertEquals(-2, FluxGitOpsBackend.parseScalar("-2"));
        assertEquals(12345678901L, FluxGitOpsBackend.parseScalar("12345678901"));
        assertEquals("a version keeps its trailing zero", "1.10", FluxGitOpsBackend.parseScalar("1.10"));
        assertEquals("a code keeps its leading zero", "0123", FluxGitOpsBackend.parseScalar("0123"));
        assertEquals("476.1.3.2.0-37", FluxGitOpsBackend.parseScalar("476.1.3.2.0-37"));
        assertEquals("1.0e3", FluxGitOpsBackend.parseScalar("1.0e3"));
        assertEquals("booleans in any case, like helm --set", Boolean.FALSE, FluxGitOpsBackend.parseScalar("False"));
        assertEquals("99999999999999999999", FluxGitOpsBackend.parseScalar("99999999999999999999"));
    }

    @Test
    public void valuesFromReadsTheReleaseValuesSecret() {
        String yaml = FluxManifests.specYaml(FluxManifests.helmReleaseSpec("trino", "1.0.0", "trino-charts", null,
                List.of(), GitOpsSecretValues.secretName("trino"), Map.of("a", "b")));

        assertTrue(yaml, yaml.contains("  valuesFrom:\n  - kind: Secret\n    name: trino-kdps-values\n"
                + "    valuesKey: values.yaml\n  values:\n"));
    }

    @Test
    public void theValuesSecretsOfTheRemovedHelmReleasesAreReadFromTheirManifests() throws Exception {
        java.nio.file.Path dir = tmp.newFolder("flux-destroy").toPath();
        java.nio.file.Files.writeString(dir.resolve("helmrelease.yaml"), """
                apiVersion: helm.toolkit.fluxcd.io/v2
                kind: HelmRelease
                metadata:
                  name: trino
                  namespace: analytics
                spec:
                  valuesFrom:
                  - kind: Secret
                    name: trino-kdps-values
                    valuesKey: values.yaml
                  - kind: Secret
                    name: operator-managed
                    valuesKey: values.yaml
                """);
        java.nio.file.Files.createDirectories(dir.resolve("dependencies"));
        java.nio.file.Files.writeString(dir.resolve("dependencies/keda-helmrelease.yaml"), """
                kind: HelmRelease
                metadata:
                  name: keda
                  namespace: keda
                """);
        java.nio.file.Files.writeString(dir.resolve("helmrepository.yaml"), """
                kind: HelmRepository
                metadata:
                  name: trino-charts
                  namespace: analytics
                spec:
                  secretRef:
                    name: trino-charts-repo-auth
                  certSecretRef:
                    name: corporate-ca
                """);
        java.nio.file.Files.writeString(dir.resolve("broken.yaml"), "kind: [unclosed");

        List<java.nio.file.Path> manifests = FluxGitOpsBackend.releaseManifests(dir);
        java.util.Set<FluxGitOpsBackend.NamespacedName> secrets = FluxGitOpsBackend.valuesSecretsIn(manifests);

        assertEquals("the release's own files: a dependency is shared, and moved rather than removed",
                List.of(dir.resolve("helmrelease.yaml"), dir.resolve("helmrepository.yaml")), manifests);
        assertEquals("only the Secrets this view names, never one the operator referenced", java.util.Set.of(
                new FluxGitOpsBackend.NamespacedName("analytics", "trino-kdps-values")), secrets);
        assertEquals("the credentials KDPS wrote for the chart source, not a CA Secret the operator named",
                java.util.Set.of(new FluxGitOpsBackend.NamespacedName("analytics", "trino-charts-repo-auth")),
                FluxGitOpsBackend.sourceSecretsIn(manifests));
        assertTrue(FluxGitOpsBackend.releaseManifests(dir.resolve("missing")).isEmpty());
    }

    @Test
    public void aPasswordReachesTheGitOpsValuesAsText() {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("auth.ldap.bindPassword",
                org.apache.ambari.view.k8s.service.HelmSetValues.textIfSecret("ldap.bindPassword", "12345"));

        FluxGitOpsBackend.applyOverridesToValues(values, overrides);

        @SuppressWarnings("unchecked")
        Map<String, Object> ldap = (Map<String, Object>) ((Map<String, Object>) values.get("auth")).get("ldap");
        assertEquals("12345", ldap.get("bindPassword"));
    }
}
