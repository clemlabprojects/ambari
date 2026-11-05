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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.ambari.view.k8s.service.CommandService;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The secret values of a GitOps profile deploy, moved into Secrets of the release namespace as the REAL service
 * definitions say, the committed values naming those Secrets: what Argo CD applies then needs nothing but the files.
 */
class GitOpsSecretReferencesTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rules(String service) throws Exception {
        try (var in = GitOpsSecretReferencesTest.class.getClassLoader()
                .getResourceAsStream("KDPS/services/" + service + "/service.json")) {
            Map<String, Object> def = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Object>>() {}.getType());
            return (List<Map<String, Object>>) def.get("gitOpsSecretReferences");
        }
    }

    private static GitOpsSecretReferences.Applied apply(Map<String, Object> values, String service, String chart,
                                                        Set<String> existing) throws Exception {
        return GitOpsSecretReferences.apply(values, rules(service), "bi", chart, existing::contains,
                CommandService::isChartVersionCompatible);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Map<String, String>> byName(GitOpsSecretReferences.Applied applied) {
        return applied.secrets().stream().collect(Collectors.toMap(GitOpsSecretReferences.SecretToWrite::name,
                GitOpsSecretReferences.SecretToWrite::data));
    }

    private static Map<String, Object> ldapAuth(String password) {
        return map("mode", "ldap", "ldap", map("bindDn", "cn=bind", "bindPassword", password));
    }

    @Test
    void supersetCommitsOnlyTheNamesOfItsSecrets() throws Exception {
        Map<String, Object> values = map(
                "global", map("security", map("auth", ldapAuth("Bind-Pass-1"))),
                "extraSecretEnv", map("SUPERSET_SECRET_KEY", "Secret-Key-2"),
                "extraConfigs", map("import_datasources_trino.yaml", "password: Datasource-Pass-3"),
                "envFromSecrets", List.of("operator-env"));

        GitOpsSecretReferences.Applied applied = apply(values, "SUPERSET", "0.12.53", Set.of());

        String committed = new Gson().toJson(values);
        for (String secret : List.of("Bind-Pass-1", "Secret-Key-2", "Datasource-Pass-3")) {
            assertFalse(committed.contains(secret), secret + " left in " + committed);
        }
        assertEquals("bi-kdps-auth", GitOpsSecretReferences.get(values, "global.security.auth.existingSecret"));
        assertEquals("cn=bind", GitOpsSecretReferences.get(values, "global.security.auth.ldap.bindDn"));
        assertEquals(List.of("operator-env", "bi-kdps-env"), values.get("envFromSecrets"));
        assertEquals("bi-kdps-extra-config", values.get("extraConfigsExistingSecret"));
        Map<String, Map<String, String>> secrets = byName(applied);
        assertEquals(Map.of("bind-password", "Bind-Pass-1"), secrets.get("bi-kdps-auth"));
        assertEquals(Map.of("SUPERSET_SECRET_KEY", "Secret-Key-2"), secrets.get("bi-kdps-env"));
        assertEquals(Map.of("import_datasources_trino.yaml", "password: Datasource-Pass-3"),
                secrets.get("bi-kdps-extra-config"));
    }

    @Test
    void aChartThatCannotReadTheSecretIsRefused() {
        Map<String, Object> values = map("global", map("security", map("auth", ldapAuth("Bind-Pass-1"))));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> apply(values, "SUPERSET", "0.12.52", Set.of()));

        assertTrue(refused.getMessage().contains(">=0.12.53"), refused.getMessage());
        assertTrue(refused.getMessage().contains("bind password"), refused.getMessage());
    }

    @Test
    void aResyncWithoutTheSecretKeepsNamingTheSecretItWrote() throws Exception {
        Map<String, Object> values = map("global", map("security", map("auth", map("mode", "ldap",
                "ldap", map("bindDn", "cn=bind")))));

        GitOpsSecretReferences.Applied applied = apply(values, "SUPERSET", "0.12.53", Set.of("bi-kdps-auth"));

        assertEquals("bi-kdps-auth", GitOpsSecretReferences.get(values, "global.security.auth.existingSecret"));
        assertTrue(applied.secrets().isEmpty(), "the Secret is left as it is: " + applied.secrets());
    }

    @Test
    void theBindPasswordOfTheSecurityModeWins() throws Exception {
        Map<String, Object> values = map("global", map("security", map("auth", map("mode", "ad",
                "ldap", map("bindPassword", "Stale-Ldap"), "ad", map("bindPassword", "Ad-Pass")))));

        GitOpsSecretReferences.Applied applied = apply(values, "TRINO", "1.43.21", Set.of());

        assertEquals(Map.of("bind-password", "Ad-Pass"), byName(applied).get("bi-kdps-auth"));
    }

    @Test
    void trinoKeepsBothPolarisCredentialsInTheirSecret() throws Exception {
        Map<String, Object> values = map("polarisCredential", map("clientId", "trino-bi", "clientSecret", "Polaris-4",
                "secretRef", map("name", "")));

        GitOpsSecretReferences.Applied applied = apply(values, "TRINO", "1.43.21", Set.of());

        // The chart reads both from the Secret once secretRef names one.
        assertEquals(Map.of("client_id", "trino-bi", "client_secret", "Polaris-4"), byName(applied).get("bi-kdps-polaris"));
        assertEquals("bi-kdps-polaris", GitOpsSecretReferences.get(values, "polarisCredential.secretRef.name"));
        assertNull(GitOpsSecretReferences.get(values, "polarisCredential.clientSecret"));
    }

    @Test
    void theHiveIngestionPasswordOfOpenMetadataOnlyLeavesTheValues() throws Exception {
        Map<String, Object> values = map("baseIngestion", map("hiveUsername", "om", "hivePassword", "Hive-5"));

        GitOpsSecretReferences.Applied applied = apply(values, "OPENMETADATA", "1.13.36", Set.of());

        assertNull(GitOpsSecretReferences.get(values, "baseIngestion.hivePassword"));
        assertEquals("om", GitOpsSecretReferences.get(values, "baseIngestion.hiveUsername"));
        assertTrue(applied.secrets().isEmpty());
    }

    @Test
    void valuesWithoutSecretsAreLeftAlone() throws Exception {
        Map<String, Object> values = map("supersetWorker", map("concurrency", 4));

        GitOpsSecretReferences.Applied applied = apply(values, "SUPERSET", "0.12.53", Set.of());

        assertEquals(map("supersetWorker", map("concurrency", 4)), values);
        assertTrue(applied.referenced().isEmpty());
    }
}
