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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The values of a release read for a redeploy hide its secrets, and the deployed ones are put back where the redeploy
 * sends the placeholder: a secret nobody retyped is kept, a retyped one replaces it, and the placeholder is never
 * deployed.
 */
class HiddenReleaseValuesTest {

    private static final String PEM = "-----BEGIN PRIVATE KEY-----\nMIIEv\n-----END PRIVATE KEY-----\n";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Values as a Superset release runs with them. */
    private static Map<String, Object> deployed() throws Exception {
        return JSON.readValue("{"
                + "\"extraSecretEnv\":{\"SUPERSET_SECRET_KEY\":\"Superset-Key-764\"},"
                + "\"hiveDb\":{\"ldapPassword\":\"Ldap-Pass-764\",\"user\":\"hive\"},"
                + "\"global\":{\"security\":{\"auth\":{\"oidc\":{\"clientSecret\":\"Oidc-764\","
                + "\"secretName\":\"sso\"}}}},"
                + "\"redis\":{\"auth\":{\"token\":12345,\"enabled\":true},\"existingSecret\":\"\"},"
                + "\"ingress\":{\"tls\":[{\"secretName\":\"superset-tls\",\"hosts\":[\"superset.example.com\"]}],"
                + "\"tlsUpload\":{\"cert\":\"-----BEGIN CERTIFICATE-----\","
                + "\"key\":\"" + PEM.replace("\n", "\\n") + "\"}},"
                + "\"bootstrapScript\":\"" + PEM.replace("\n", "\\n") + "\","
                + "\"replicaCount\":2}", Map.class);
    }

    @Test
    void secretsAreHiddenByTheirNameTheServiceDefinitionOrTheirContent() throws Exception {
        Map<String, Object> hidden = HiddenReleaseValues.hide(deployed(),
                Set.of("extraSecretEnv.SUPERSET_SECRET_KEY"));
        String json = JSON.writeValueAsString(hidden);

        for (String secret : List.of("Superset-Key-764", "Ldap-Pass-764", "Oidc-764", "12345", "PRIVATE KEY")) {
            assertFalse(json.contains(secret), secret + " in " + json);
        }
        assertTrue(json.contains("\"secretName\":\"sso\""), "a Secret's name is not a secret: " + json);
        assertTrue(json.contains("\"user\":\"hive\""), json);
        assertTrue(json.contains("\"enabled\":true"), json);
        assertTrue(json.contains("\"replicaCount\":2"), json);
        assertTrue(json.contains("\"existingSecret\":\"\""), "an empty value hides nothing: " + json);
        assertTrue(json.contains("superset.example.com"), json);
    }

    @Test
    void aRedeployThatSendsThePlaceholderBackKeepsTheDeployedSecrets() throws Exception {
        Map<String, Object> sentBack = JSON.readValue(JSON.writeValueAsString(
                HiddenReleaseValues.hide(deployed(), Set.of("extraSecretEnv.SUPERSET_SECRET_KEY"))), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> hiveDb = (Map<String, Object>) sentBack.get("hiveDb");
        hiveDb.put("ldapPassword", "Ldap-Pass-Retyped");
        sentBack.put("replicaCount", 3);

        HiddenReleaseValues.restore(sentBack, () -> {
            try {
                return deployed();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        Map<String, Object> expected = deployed();
        @SuppressWarnings("unchecked")
        Map<String, Object> expectedHive = (Map<String, Object>) expected.get("hiveDb");
        expectedHive.put("ldapPassword", "Ldap-Pass-Retyped");
        expected.put("replicaCount", 3);
        assertEquals(expected, sentBack, "every hidden value is the deployed one again, numbers included");
        assertFalse(HiddenReleaseValues.containsPlaceholder(sentBack));
    }

    @Test
    void thePasswordsSupersetBindingsCopyIntoItsDatasourceFilesAreHidden() throws Exception {
        String hiveFile = "databases:\n- database_name: Hive\n  sqlalchemy_uri: hive://svc-superset:S3cret-Ldap-764@hs2"
                + ".example.com:10001/default?auth=LDAP\n";
        String trinoFile = "databases:\n- database_name: Trino\n  sqlalchemy_uri: trino://kdps:Trino-Svc-764@trino:8443\n";
        Map<String, Object> values = deployed();
        values.put("extraConfigs", new java.util.LinkedHashMap<>(Map.of("import_datasources.yaml", hiveFile,
                "import_datasources_trino.yaml", trinoFile, "custom_logging.yaml", "level: INFO")));

        Map<String, Object> hidden = HiddenReleaseValues.hide(values, SensitiveFields.valuePathsOfService("SUPERSET"));
        String json = JSON.writeValueAsString(hidden);

        assertFalse(json.contains("S3cret-Ldap-764"), json);
        assertFalse(json.contains("Trino-Svc-764"), json);
        assertTrue(json.contains("\"custom_logging.yaml\":\"level: INFO\""), "a file no binding writes a password into"
                + " stays readable: " + json);

        Map<String, Object> sentBack = JSON.readValue(json, Map.class);
        HiddenReleaseValues.restore(sentBack, () -> values);
        assertEquals(hiveFile, ((Map<?, ?>) sentBack.get("extraConfigs")).get("import_datasources.yaml"));
    }

    @Test
    void everythingUnderAListOrMapABindingWritesAPasswordIntoIsHidden() throws Exception {
        Map<String, Object> values = JSON.readValue("{\"additionalConfigProperties\":"
                + "[\"connection-url=jdbc://svc:Db-Pass-764@db/db\",\"query.max-memory=4GB\"],"
                + "\"conn\":{\"file\":{\"user\":\"svc\",\"password\":\"Db-Pass-764\",\"port\":5432,\"tls\":true}},"
                + "\"replicaCount\":2}", Map.class);

        Map<String, Object> hidden = HiddenReleaseValues.hide(values, Set.of("additionalConfigProperties", "conn.file"));

        assertEquals(List.of(HiddenReleaseValues.PLACEHOLDER, HiddenReleaseValues.PLACEHOLDER),
                hidden.get("additionalConfigProperties"));
        assertEquals(Map.of("user", HiddenReleaseValues.PLACEHOLDER, "password", HiddenReleaseValues.PLACEHOLDER,
                "port", HiddenReleaseValues.PLACEHOLDER, "tls", true), ((Map<?, ?>) hidden.get("conn")).get("file"));
        assertEquals(2, hidden.get("replicaCount"));

        Map<String, Object> sentBack = JSON.readValue(JSON.writeValueAsString(hidden), Map.class);
        HiddenReleaseValues.restore(sentBack, () -> values);
        assertEquals(values, sentBack);
    }

    @Test
    void aValueTheDefinitionNowPlacesElsewhereIsPutBackFromWhereTheReleaseHasIt() throws Exception {
        // Deployed before the SECRET_KEY moved: only the old place holds it.
        Map<String, Object> deployed = JSON.readValue(
                "{\"extraEnv\":{\"SUPERSET_SECRET_KEY\":\"Old-Place-Key-764\",\"TZ\":\"UTC\"}}", Map.class);
        Map<String, String> aliases = Map.of("extraEnv.SUPERSET_SECRET_KEY", "extraSecretEnv.SUPERSET_SECRET_KEY");
        Map<String, Object> sentBack = JSON.readValue("{\"extraEnv\":{\"TZ\":\"UTC\"},"
                + "\"extraSecretEnv\":{\"SUPERSET_SECRET_KEY\":\"********\"}}", Map.class);

        HiddenReleaseValues.restore(sentBack, () -> HiddenReleaseValues.withAliasTargets(deployed, aliases));

        assertEquals("Old-Place-Key-764", ((Map<?, ?>) sentBack.get("extraSecretEnv")).get("SUPERSET_SECRET_KEY"));
        assertFalse(deployed.containsKey("extraSecretEnv"), "the deployed values are left unchanged");
        Map<String, Object> both = JSON.readValue("{\"extraEnv\":{\"SUPERSET_SECRET_KEY\":\"Old\"},"
                + "\"extraSecretEnv\":{\"SUPERSET_SECRET_KEY\":\"New\"}}", Map.class);
        assertEquals("New", ((Map<?, ?>) HiddenReleaseValues.withAliasTargets(both, aliases).get("extraSecretEnv"))
                .get("SUPERSET_SECRET_KEY"), "a value the release has at the new place wins");
    }

    @Test
    void valuesWithoutThePlaceholderAreLeftAloneAndTheReleaseIsNotRead() throws Exception {
        Map<String, Object> values = deployed();
        HiddenReleaseValues.restore(values, () -> fail("nothing to put back, so nothing to read"));
        assertEquals(deployed(), values);
        HiddenReleaseValues.restore(null, () -> fail("nothing to put back"));
    }

    @Test
    void aPlaceholderWithNoDeployedValueToPutBackIsRefused() throws Exception {
        Map<String, Object> values = JSON.readValue("{\"auth\":{\"newPassword\":\"********\"}}", Map.class);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> HiddenReleaseValues.restore(values, Map::of));

        assertTrue(refused.getMessage().contains("'auth.newPassword'"), refused.getMessage());
        assertTrue(refused.getMessage().contains("Type the value again"), refused.getMessage());
    }

    @Test
    void aPlaceholderCopiedIntoALongerValueIsRefused() throws Exception {
        Map<String, Object> values = JSON.readValue(
                "{\"db\":{\"url\":\"jdbc:postgresql://db/superset?password=********\"}}", Map.class);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> HiddenReleaseValues.restore(values, Map::of));

        assertTrue(refused.getMessage().contains("'db.url'"), refused.getMessage());
    }

    /** Values with the lists a chart takes for scheduling and the environment of its pods. */
    private static Map<String, Object> scheduledRelease() throws Exception {
        String encodedKey = Base64.getEncoder().encodeToString(PEM.getBytes(StandardCharsets.UTF_8));
        return JSON.readValue("{"
                + "\"tolerations\":[{\"key\":\"dedicated\",\"operator\":\"Equal\",\"value\":\"kdps\"},"
                + "{\"key\":\"gpu\",\"operator\":\"Exists\"}],"
                + "\"affinity\":{\"nodeAffinity\":{\"requiredDuringSchedulingIgnoredDuringExecution\":"
                + "{\"nodeSelectorTerms\":[{\"matchExpressions\":"
                + "[{\"key\":\"zone\",\"operator\":\"In\",\"values\":[\"a\"]}]}]}}},"
                + "\"env\":[{\"name\":\"DB_USER\","
                + "\"valueFrom\":{\"secretKeyRef\":{\"name\":\"db\",\"key\":\"username\"}}},"
                + "{\"name\":\"DB_PASSWORD\",\"value\":\"Db-Pass-764\"}],"
                + "\"apiToken\":\"Api-Token-764\",\"ingress\":{\"tlsSecret\":\"superset-tls\"},"
                + "\"auth\":{\"existingSecret\":\"superset-auth\"},"
                + "\"webhook\":{\"key\":\"" + encodedKey + "\"}}", Map.class);
    }

    private static Map<String, Object> sentBack(Map<String, Object> values) throws Exception {
        return JSON.readValue(JSON.writeValueAsString(HiddenReleaseValues.hide(values, Set.of())), Map.class);
    }

    @Test
    void keysOfSchedulingRulesSecretReferencesAndNamesOfSecretsAreNotHidden() throws Exception {
        String json = JSON.writeValueAsString(HiddenReleaseValues.hide(scheduledRelease(), Set.of()));

        for (String shown : List.of("\"key\":\"dedicated\"", "\"key\":\"gpu\"", "\"key\":\"zone\"",
                "\"key\":\"username\"", "\"tlsSecret\":\"superset-tls\"", "\"existingSecret\":\"superset-auth\"")) {
            assertTrue(json.contains(shown), shown + " in " + json);
        }
        assertFalse(json.contains("Api-Token-764"), json);
        assertFalse(json.contains("LS0tLS1CRUdJTi"), "a Base64 private key is still found by its content: " + json);
        assertTrue(HiddenReleaseValues.isSecretKey("clientSecret"));
        assertTrue(HiddenReleaseValues.isSecretKey("apiKey"));
        assertFalse(HiddenReleaseValues.isSecretKey("key"));
        assertFalse(HiddenReleaseValues.isSecretKey("keytabSecret"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aHiddenValueFollowsItsNamedEntryWhenAnEntryAheadOfItIsRemoved() throws Exception {
        Map<String, Object> deployed = scheduledRelease();
        deployed.put("env", JSON.readValue("[{\"name\":\"TZ\",\"value\":\"UTC\"},"
                + "{\"name\":\"LDAP_PASSWORD\",\"value\":\"Ldap-Pass-764\"},"
                + "{\"name\":\"DB_PASSWORD\",\"value\":\"Db-Pass-764\"}]", List.class));
        Map<String, Object> values = sentBack(deployed);
        // As the page sends the list back when the definition hides it, with the TZ entry removed.
        values.put("env", JSON.readValue("[{\"name\":\"LDAP_PASSWORD\",\"value\":\"********\"},"
                + "{\"name\":\"DB_PASSWORD\",\"value\":\"********\"}]", List.class));

        HiddenReleaseValues.restore(values, () -> deployed);

        List<Map<String, Object>> env = (List<Map<String, Object>>) values.get("env");
        assertEquals("Ldap-Pass-764", env.get(0).get("value"), "not the TZ entry now at its position");
        assertEquals("Db-Pass-764", env.get(1).get("value"));
    }

    @Test
    void aHiddenValueOfAListWithAnEntryAddedOrRemovedAheadOfItIsRefused() throws Exception {
        Map<String, Object> deployed = JSON.readValue("{\"auth\":{\"tokens\":[\"Token-A-764\",\"Token-B-764\"]},"
                + "\"sidecars\":[{\"image\":\"proxy\"},{\"image\":\"agent\",\"password\":\"Agent-764\"}]}", Map.class);
        Map<String, Object> values = JSON.readValue(JSON.writeValueAsString(
                HiddenReleaseValues.hide(deployed, Set.of("auth.tokens"))), Map.class);
        values.put("sidecars", JSON.readValue("[{\"image\":\"agent\",\"password\":\"********\"}]", List.class));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> HiddenReleaseValues.restore(values, () -> deployed));
        assertTrue(refused.getMessage().contains("'sidecars'"), refused.getMessage());

        Map<String, Object> fewerTokens = JSON.readValue("{\"auth\":{\"tokens\":[\"********\"]}}", Map.class);
        refused = assertThrows(IllegalArgumentException.class,
                () -> HiddenReleaseValues.restore(fewerTokens, () -> deployed));
        assertTrue(refused.getMessage().contains("'auth.tokens'"), refused.getMessage());

        Map<String, Object> unchangedLength = JSON.readValue(JSON.writeValueAsString(
                HiddenReleaseValues.hide(deployed, Set.of("auth.tokens"))), Map.class);
        HiddenReleaseValues.restore(unchangedLength, () -> deployed);
        assertEquals(deployed, unchangedLength, "a list left as it was gets its values back by position");
    }

    @Test
    void aNamedEntryTheReleaseDoesNotHaveIsRefused() throws Exception {
        Map<String, Object> deployed = JSON.readValue(
                "{\"env\":[{\"name\":\"DB_PASSWORD\",\"value\":\"Db-Pass-764\"}]}", Map.class);
        Map<String, Object> values = JSON.readValue(
                "{\"env\":[{\"name\":\"LDAP_PASSWORD\",\"value\":\"********\"}]}", Map.class);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> HiddenReleaseValues.restore(values, () -> deployed));
        assertTrue(refused.getMessage().contains("'env[0].value'"), refused.getMessage());
    }
}
