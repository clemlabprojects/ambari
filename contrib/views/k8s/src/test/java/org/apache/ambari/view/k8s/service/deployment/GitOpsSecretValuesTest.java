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

import org.apache.ambari.view.k8s.model.FormField;
import org.apache.ambari.view.k8s.model.stack.StackConfig;
import org.apache.ambari.view.k8s.model.stack.StackProperty;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.apache.ambari.view.k8s.service.HelmSetValues;
import org.apache.ambari.view.k8s.service.StackDefinitionService;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class GitOpsSecretValuesTest {

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static List<Object> list(Object... items) {
        return new ArrayList<>(List.of(items));
    }

    @Test
    public void aSecretPathMovesToTheSecretWithItsNesting() {
        Map<String, Object> values = map("global", map("security", map("auth", map("ldap",
                map("url", "ldap://dir.example.com", "bindPassword", "Bind-Pass-42")))));
        GitOpsSecretValues secrets = new GitOpsSecretValues();
        secrets.addPath("global.security.auth.ldap.bindPassword");

        GitOpsSecretValues.Split split = secrets.split(values);

        assertEquals(map("global", map("security", map("auth", map("ldap", map("url", "ldap://dir.example.com"))))),
                split.publicValues());
        assertEquals("removed, not blanked: Flux merges spec.values over valuesFrom",
                map("global", map("security", map("auth", map("ldap", map("bindPassword", "Bind-Pass-42"))))),
                split.secretValues());
    }

    @Test
    public void parentsLeftEmptyAreRemovedButOtherEmptyMapsStay() {
        Map<String, Object> values = map("oidc", map("client", map("secret", "Client-Secret-1")),
                "podAnnotations", map());
        GitOpsSecretValues secrets = new GitOpsSecretValues();
        secrets.addPath("oidc.client.secret");

        GitOpsSecretValues.Split split = secrets.split(values);

        assertEquals(map("podAnnotations", map()), split.publicValues());
        assertEquals(map("oidc", map("client", map("secret", "Client-Secret-1"))), split.secretValues());
    }

    @Test
    public void aPathThroughAListMovesTheWholeList() {
        Map<String, Object> values = map("auth", map("users", list(
                map("name", "alice", "password", "Alice-Pass-1"),
                map("name", "bob", "password", "Bob-Pass-22"))), "replicas", 2);
        GitOpsSecretValues secrets = new GitOpsSecretValues();
        secrets.addPath("auth.users[1].password");

        GitOpsSecretValues.Split split = secrets.split(values);

        assertEquals("Flux replaces lists: half a list in each place would lose entries",
                map("replicas", 2), split.publicValues());
        assertEquals(2, ((List<?>) ((Map<?, ?>) split.secretValues().get("auth")).get("users")).size());
    }

    @Test
    public void aSecretCopiedByABindingMovesWithTheValueThatHoldsIt() {
        Map<String, Object> values = map(
                "internalSharedSecret", "Shared-Secret-123",
                "additionalConfigProperties", list("internal-communication.shared-secret=Shared-Secret-123",
                        "query.max-memory=4GB"),
                "extraEnv", map("SUPERSET_SECRET_KEY", "Shared-Secret-123", "LOG_LEVEL", "INFO"),
                "extraConfigs", map("import.yaml", "uri: trino://svc:Shared-Secret-123@trino:8443/hive"),
                "server", map("workers", 2));
        GitOpsSecretValues secrets = new GitOpsSecretValues();
        secrets.addPath("internalSharedSecret");
        secrets.addValuesAtPaths(values);

        GitOpsSecretValues.Split split = secrets.split(values);

        assertEquals(map("extraEnv", map("LOG_LEVEL", "INFO"), "server", map("workers", 2)), split.publicValues());
        assertEquals("Shared-Secret-123", split.secretValues().get("internalSharedSecret"));
        assertEquals(list("internal-communication.shared-secret=Shared-Secret-123", "query.max-memory=4GB"),
                split.secretValues().get("additionalConfigProperties"));
        assertEquals(map("SUPERSET_SECRET_KEY", "Shared-Secret-123"), split.secretValues().get("extraEnv"));
        assertNotNull(((Map<?, ?>) split.secretValues().get("extraConfigs")).get("import.yaml"));
    }

    @Test
    public void namesOfSecretsAndKeysStayReadable() {
        Map<String, Object> values = map("global", map("security", map("tls", map(
                "truststoreSecret", "trino-truststore",
                "truststorePasswordKey", "truststore.password",
                "truststoreKey", "truststore.jks"),
                "auth", map("oidc", map("caSecret", "company-ca", "clientSecret", "Client-Secret-77")))),
                "existingSecret", "superset-env", "secretName", "superset-config");
        GitOpsSecretValues secrets = new GitOpsSecretValues();
        secrets.addPath("global.security.auth.oidc.clientSecret");
        secrets.addValuesAtPaths(values);

        GitOpsSecretValues.Split split = secrets.split(values);

        Map<?, ?> security = (Map<?, ?>) ((Map<?, ?>) split.publicValues().get("global")).get("security");
        assertEquals("trino-truststore", ((Map<?, ?>) security.get("tls")).get("truststoreSecret"));
        assertEquals("truststore.password", ((Map<?, ?>) security.get("tls")).get("truststorePasswordKey"));
        assertEquals(map("caSecret", "company-ca"), ((Map<?, ?>) security.get("auth")).get("oidc"));
        assertEquals("superset-env", split.publicValues().get("existingSecret"));
        assertEquals("superset-config", split.publicValues().get("secretName"));
    }

    @Test
    public void withoutSecretsNothingMoves() {
        Map<String, Object> values = map("truststoreSecret", "x-truststore", "password", "not-a-definition");
        GitOpsSecretValues.Split split = new GitOpsSecretValues().split(values);

        assertFalse("no definition says it is a password: key names are never used", split.hasSecrets());
        assertEquals(values, split.publicValues());
    }

    @Test
    public void shortSecretsMoveAtTheirPathButAreNotSearchedFor() {
        Map<String, Object> values = map("db", map("password", "abc"), "user", "abcdef");
        GitOpsSecretValues secrets = new GitOpsSecretValues();
        secrets.addPath("db.password");
        secrets.addValuesAtPaths(values);

        GitOpsSecretValues.Split split = secrets.split(values);

        assertEquals(map("user", "abcdef"), split.publicValues());
        assertEquals(map("db", map("password", "abc")), split.secretValues());
        assertFalse(secrets.leaksInto("user: abcdef"));
    }

    @Test
    public void theCallersValuesAreNotModified() {
        Map<String, Object> values = map("a", map("password", "Secret-Value-1"));
        GitOpsSecretValues secrets = new GitOpsSecretValues();
        secrets.addPath("a.password");

        secrets.split(values);

        assertEquals(map("a", map("password", "Secret-Value-1")), values);
    }

    @Test
    public void aDependencyLosesOnlyTheValuesHoldingASecret() {
        GitOpsSecretValues secrets = new GitOpsSecretValues();
        secrets.addPath("bindPassword");
        secrets.addValue("Bind-Pass-42");

        GitOpsSecretValues.Split split = secrets.splitContaining(map("bindPassword", "other", "proxy",
                map("bind", "Bind-Pass-42", "port", 389)), List.of());

        assertEquals("the release's paths do not apply to a dependency",
                map("bindPassword", "other", "proxy", map("port", 389)), split.publicValues());
        assertEquals(map("proxy", map("bind", "Bind-Pass-42")), split.secretValues());
    }

    @Test
    public void whatTheValuesSecretHeldStaysSecretOnARedeployWithoutTheForm() {
        // The values Secret of the last deploy, and the release's values as Flux merged them back (a resync sends no
        // form, so the excluded Polaris client secret is known from nothing else).
        Map<String, Object> stored = map(
                "polarisCredential", map("clientSecret", "Polaris-Secret-9"),
                "additionalConfigProperties", list("internal-communication.shared-secret=Shared-Secret-123",
                        "query.max-memory=4GB"),
                "extraConfigs", map("import.yaml", "uri: trino://svc:Trino-Pass-9@trino:8443/hive"));
        Map<String, Object> values = map(
                "polarisCredential", map("clientSecret", "Polaris-Secret-9", "clientId", "trino"),
                "additionalConfigProperties", list("internal-communication.shared-secret=Shared-Secret-123",
                        "query.max-memory=8GB"),
                "extraConfigs", map("import.yaml", "uri: trino://svc:Trino-Pass-9@trino:8443/hive", "other.yaml", "a: b"),
                "catalog", map("uri", "https://polaris:8181", "credential", "trino:Polaris-Secret-9"),
                "server", map("workers", 3));
        GitOpsSecretValues secrets = new GitOpsSecretValues();
        secrets.addStoredPaths(stored);
        secrets.addStoredValues(stored);
        secrets.addValuesAtPaths(values);

        GitOpsSecretValues.Split split = secrets.split(values);

        assertEquals(map("polarisCredential", map("clientId", "trino"),
                "extraConfigs", map("other.yaml", "a: b"),
                "catalog", map("uri", "https://polaris:8181"),
                "server", map("workers", 3)), split.publicValues());
        assertEquals("a list moves again as a whole, with its edited entries",
                list("internal-communication.shared-secret=Shared-Secret-123", "query.max-memory=8GB"),
                split.secretValues().get("additionalConfigProperties"));
        assertEquals("a key with dots is taken as one key", map("import.yaml", "uri: trino://svc:Trino-Pass-9@trino:8443/hive"),
                split.secretValues().get("extraConfigs"));
        assertEquals("a copy elsewhere is found by the stored value", map("credential", "trino:Polaris-Secret-9"),
                split.secretValues().get("catalog"));
        assertFalse("the other entries of a list are not secrets",
                secrets.secrets().stream().anyMatch(s -> s.startsWith("query.max-memory")));
        assertTrue(secrets.leaksInto("clientSecret: Polaris-Secret-9"));
    }

    @Test
    public void aDependencyKeepsWhatItsOwnValuesSecretHeld() {
        GitOpsSecretValues secrets = new GitOpsSecretValues();
        Map<String, Object> stored = map("upstream", map("bindPassword", "Bind-Pass-42"));
        secrets.addStoredValues(stored);

        GitOpsSecretValues.Split split = secrets.splitContaining(map("upstream", map("bindPassword", "Rotated-77",
                "host", "dir.example.com")), List.of(stored));

        assertEquals("its own stored path moves, whatever the value now",
                map("upstream", map("host", "dir.example.com")), split.publicValues());
        assertEquals(map("upstream", map("bindPassword", "Rotated-77")), split.secretValues());
    }

    @Test
    public void stackConfigOverridesAreFoundInTheValuesTheyWereWrittenTo() {
        Map<String, Object> values = map(
                "superset-env/SUPERSET_ADMIN_PASSWORD", "Admin-Pass-5",
                "ranger-env/ranger", map("admin", map("password", "Ranger-Pass-6")),
                "superset", map("replicas", 1));

        assertEquals(map("superset-env/SUPERSET_ADMIN_PASSWORD", "Admin-Pass-5",
                "ranger-env/ranger.admin.password", "Ranger-Pass-6"), GitOpsSecretValues.configOverridesIn(values));

        StackConfig ranger = new StackConfig();
        ranger.name = "ranger-env";
        ranger.properties = List.of(property("ranger.admin.password", "password"));
        GitOpsSecretValues secrets = new GitOpsSecretValues();
        secrets.addPasswordConfigs(List.of(ranger), GitOpsSecretValues.configOverridesIn(values));
        GitOpsSecretValues.Split split = secrets.split(values);

        assertEquals(map("superset-env/SUPERSET_ADMIN_PASSWORD", "Admin-Pass-5", "superset", map("replicas", 1)),
                split.publicValues());
        assertEquals(map("ranger-env/ranger", map("admin", map("password", "Ranger-Pass-6"))), split.secretValues());
    }

    @Test
    public void theValuesSecretsOfAHelmReleaseAreNamedAfterIt() throws Exception {
        String change = GitOpsSecretValues.changeSecretName("trino");

        assertTrue(change, change.matches("trino-kdps-values-[0-9a-f]{8}"));
        assertNotEquals("a new name for each change", change, GitOpsSecretValues.changeSecretName("trino"));
        assertTrue(GitOpsSecretValues.isValuesSecretOf("trino", "trino-kdps-values"));
        assertTrue(GitOpsSecretValues.isValuesSecretOf("trino", change));
        assertFalse(GitOpsSecretValues.isValuesSecretOf("trino", "trino-worker-kdps-values"));
        assertFalse(GitOpsSecretValues.isValuesSecretOf("trino", "operator-managed"));

        assertEquals(map("a", map("b", "c")), GitOpsSecretValues.readValuesYaml("a:\n  b: c\n"));
        assertTrue(GitOpsSecretValues.readValuesYaml("").isEmpty());
        try {
            GitOpsSecretValues.readValuesYaml("- not\n- a map\n");
            fail("a values document is a map");
        } catch (java.io.IOException expected) {
            // fails closed in the deploy
        }
    }

    @Test
    public void aLeakIsFoundAsWrittenAndAsEscapedInYaml() {
        GitOpsSecretValues secrets = new GitOpsSecretValues();
        secrets.addValue("pa\"ss\\wo rd");
        secrets.addValue("plain-secret");

        assertTrue(secrets.leaksInto("token: \"plain-secret\""));
        assertTrue(secrets.leaksInto("password: \"pa\\\"ss\\\\wo rd\""));
        assertFalse(secrets.leaksInto("password: \"other\""));
        assertFalse(new GitOpsSecretValues().leaksInto("anything"));
    }

    @Test
    public void theSecurityProfilePasswordsAreTheMarkedOverrides() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("global.security.auth.ldap.bindPassword",
                HelmSetValues.textIfSecret("ldap.bindPassword", "Bind-Pass-42"));
        overrides.put("global.security.auth.ldap.bindDn", HelmSetValues.textIfSecret("ldap.bindDn", "cn=admin"));
        GitOpsSecretValues secrets = new GitOpsSecretValues();

        secrets.addMarkedOverrides(overrides);

        assertEquals(java.util.Set.of("global.security.auth.ldap.bindPassword"), secrets.paths());
        assertEquals(java.util.Set.of("Bind-Pass-42"), secrets.secrets());
    }

    @Test
    public void passwordFormFieldsAreFoundInGroups() {
        FormField sent = field("superset.secretKey", "password", false);
        FormField excluded = field("ui_trino_svc_password", "password", true);
        FormField plain = field("superset.adminUser", "string", false);
        FormField group = field("auth", "group", false);
        group.fields = List.of(excluded, plain);
        Map<String, Object> formValues = map("superset", map("secretKey", "Secret-Key-123", "adminUser", "admin"),
                "ui_trino_svc_password", "Trino-Pass-9");
        GitOpsSecretValues secrets = new GitOpsSecretValues();

        secrets.addPasswordFields(List.of(sent, group), formValues);

        assertEquals("a field kept out of the values has no path", java.util.Set.of("superset.secretKey"), secrets.paths());
        assertEquals("but its password is still searched for: bindings copy it",
                java.util.Set.of("Secret-Key-123", "Trino-Pass-9"), secrets.secrets());
    }

    @Test
    public void passwordConfigPropertiesAreFoundByTheirOverrideKey() {
        StackConfig env = new StackConfig();
        env.name = "superset-env";
        env.properties = List.of(property("SUPERSET_ADMIN_PASSWORD", "password"), property("SUPERSET_LOG_LEVEL", "string"));
        Map<String, Object> overrides = map("superset-env/SUPERSET_ADMIN_PASSWORD", "Admin-Pass-5",
                "superset-env/SUPERSET_LOG_LEVEL", "INFO", "other-env/SUPERSET_ADMIN_PASSWORD", "x-y-z-w");
        GitOpsSecretValues secrets = new GitOpsSecretValues();

        secrets.addPasswordConfigs(List.of(env), overrides);
        secrets.addPasswordProperty("GLOBAL_LOG_LEVEL", property("GLOBAL_LOG_LEVEL", "string"));

        assertEquals(java.util.Set.of("superset-env/SUPERSET_ADMIN_PASSWORD"), secrets.paths());
        assertEquals(java.util.Set.of("Admin-Pass-5"), secrets.secrets());
    }

    @Test
    public void aPropertyItsConfigurationNoLongerDeclaresIsKeptOutOfGit() {
        // The administrator password an earlier definition asked for, still in the values of a release it deployed.
        StackConfig env = new StackConfig();
        env.name = "superset-env";
        env.properties = List.of(property("ROW_LIMIT", "int"));
        Map<String, Object> overrides = map("superset-env/SUPERSET_ADMIN_PASSWORD", "Old-Admin-Pass-6",
                "superset-env/ROW_LIMIT", 5000, "other-env/SOME_SETTING", "kept");
        GitOpsSecretValues secrets = new GitOpsSecretValues();

        secrets.addPasswordConfigs(List.of(env), overrides);

        assertEquals(java.util.Set.of("superset-env/SUPERSET_ADMIN_PASSWORD"), secrets.paths());
        assertEquals(java.util.Set.of("Old-Admin-Pass-6"), secrets.secrets());
    }

    @Test
    public void theBundledServiceDefinitionsDeclareTheirSecretsAsPasswords() {
        StackDefinitionService definitions = new StackDefinitionService();

        GitOpsSecretValues trino = new GitOpsSecretValues();
        StackServiceDef trinoDef = definitions.getServiceDefinition("TRINO");
        trino.addPasswordFields(trinoDef.form, map("icebergCatalog", map("clientSecret", "Polaris-Secret-1",
                "s3SecretKey", "S3-Secret-Key-2")));
        assertFalse("the chart generates the internal shared secret; the form no longer asks for it",
                trino.paths().contains("internalSharedSecret"));
        assertTrue("the Polaris client secret and the S3 secret key are passwords",
                trino.secrets().containsAll(List.of("Polaris-Secret-1", "S3-Secret-Key-2")));

        GitOpsSecretValues openMetadata = new GitOpsSecretValues();
        openMetadata.addPasswordFields(definitions.getServiceDefinition("OPENMETADATA").form,
                map("rangerTagSyncToken", "Tag-Sync-Token-4"));
        assertTrue(openMetadata.paths().containsAll(List.of("global.security.auth.ldap.bindPassword",
                "baseIngestion.hivePassword")));
        assertTrue("the TagSync token is kept out of the values, and still a secret",
                openMetadata.secrets().contains("Tag-Sync-Token-4"));

        GitOpsSecretValues superset = new GitOpsSecretValues();
        superset.addPasswordFields(definitions.getServiceDefinition("SUPERSET").form,
                map("superset", map("secretKey", "Secret-Key-3")));
        assertFalse("the SECRET_KEY is kept out of the values", superset.paths().contains("superset.secretKey"));
        assertTrue("but it is still a secret wherever a binding copies it", superset.secrets().contains("Secret-Key-3"));
    }

    @Test
    public void theValuesBindingsCopyAPasswordFieldIntoAreSecretWithoutTheForm() {
        FormField password = field("db.password", "password", true);
        FormField token = field("api.token", "password", false);
        FormField host = field("db.host", "string", false);
        List<Map<String, Object>> variables = List.of(
                map("name", "dbPass", "from", map("type", "form", "field", "db.password")),
                map("name", "dbHost", "from", map("type", "form", "field", "db.host")),
                map("name", "dbUrl", "template", "jdbc://svc:${dbPass}@${dbHost}/db"),
                map("name", "dbUrlEnabled", "from", map("type", "equals", "var", "dbUrl", "value", "x")));
        List<Map<String, Object>> bindings = List.of(
                map("targets", List.of(
                        map("path", "extraEnv.DB_PASSWORD", "from", map("type", "var", "name", "dbPass")),
                        map("path", "additionalConfigProperties[]", "from", map("type", "template",
                                "template", "connection-url=${dbUrl}")),
                        map("path", "extraConfigs", "op", "merge", "from", map("type", "template", "template",
                                map("import.yaml", "uri: ${dbUrl}", "logging.yaml", "level: ${dbHost}"))),
                        map("path", "conn.direct", "from", map("type", "form", "field", "db.password")),
                        map("path", "conn.inline", "from", map("type", "template", "template", "${ api.token }")),
                        map("path", "conf\\.d.file", "from", map("type", "template", "template", "${dbPass}")),
                        map("path", "conn.host", "from", map("type", "var", "name", "dbHost")),
                        map("path", "conn.enabled", "from", map("type", "var", "name", "dbUrlEnabled")),
                        map("path", "conn.mode", "value", "basic"))));
        GitOpsSecretValues secrets = new GitOpsSecretValues();

        secrets.addPasswordCopies(List.of(password, host, token), variables, bindings);

        assertEquals(java.util.Set.of(List.of("extraEnv", "DB_PASSWORD"), List.of("additionalConfigProperties"),
                List.of("extraConfigs", "import.yaml"), List.of("conn", "direct"), List.of("conn", "inline"),
                List.of("conf.d", "file")), secrets.keyPaths());
        assertTrue("known from the definition: no value is needed", secrets.secrets().isEmpty());

        Map<String, Object> values = map(
                "extraEnv", map("DB_PASSWORD", "Db-Pass-77", "LOG_LEVEL", "INFO"),
                "additionalConfigProperties", list("connection-url=jdbc://svc:Db-Pass-77@db/db", "query.max-memory=4GB"),
                "extraConfigs", map("import.yaml", "uri: jdbc://svc:Db-Pass-77@db/db", "logging.yaml", "level: db"),
                "conn", map("direct", "Db-Pass-77", "host", "db", "enabled", "true", "mode", "basic"));
        secrets.addValuesAtPaths(values);
        GitOpsSecretValues.Split split = secrets.split(values);

        assertEquals(map("extraEnv", map("LOG_LEVEL", "INFO"), "extraConfigs", map("logging.yaml", "level: db"),
                "conn", map("host", "db", "enabled", "true", "mode", "basic")), split.publicValues());
        assertEquals("a list a binding appends to moves as a whole",
                list("connection-url=jdbc://svc:Db-Pass-77@db/db", "query.max-memory=4GB"),
                split.secretValues().get("additionalConfigProperties"));
        assertFalse("the other entries of that list are not secrets", secrets.secrets().contains("query.max-memory=4GB"));
    }

    @Test
    public void theBundledServiceDefinitionsDeclareWhereTheirPasswordsAreCopied() {
        StackDefinitionService definitions = new StackDefinitionService();

        StackServiceDef trinoDef = definitions.getServiceDefinition("TRINO");
        GitOpsSecretValues trino = new GitOpsSecretValues();
        trino.addPasswordCopies(trinoDef.form, trinoDef.variables, trinoDef.bindings);
        assertTrue(trino.keyPaths().toString(), trino.keyPaths().containsAll(List.of(
                List.of("polarisCredential", "clientSecret"), List.of("s3Credential", "secretKey"))));
        assertFalse("no password is written into the server configuration any more",
                trino.keyPaths().contains(List.of("coordinator", "additionalConfigProperties")));
        assertFalse(trino.keyPaths().contains(List.of("additionalConfigProperties")));
        assertFalse("the name of a credential Secret stays readable",
                trino.keyPaths().contains(List.of("polarisCredential", "secretRef", "name")));
        assertFalse(trino.keyPaths().contains(List.of("s3Credential", "accessKey")));

        StackServiceDef supersetDef = definitions.getServiceDefinition("SUPERSET");
        GitOpsSecretValues superset = new GitOpsSecretValues();
        superset.addPasswordCopies(supersetDef.form, supersetDef.variables, supersetDef.bindings);
        assertTrue(superset.keyPaths().toString(), superset.keyPaths().containsAll(List.of(
                List.of("extraSecretEnv", "SUPERSET_SECRET_KEY"), List.of("extraConfigs", "import_datasources_trino.yaml"),
                List.of("extraConfigs", "import_datasources.yaml"))));
        assertFalse("the merged map is secret entry by entry", superset.keyPaths().contains(List.of("extraConfigs")));
    }

    @Test
    public void bindingPathsAreReadDownToTheirFirstList() {
        assertEquals(List.of("coordinator", "additionalConfigProperties"),
                GitOpsSecretValues.bindingKeys("coordinator.additionalConfigProperties[]"));
        assertEquals(List.of("customCAs"), GitOpsSecretValues.bindingKeys("customCAs[0].secret"));
        assertEquals(List.of("conf.d", "file"), GitOpsSecretValues.bindingKeys("conf\\.d.file"));
        assertEquals(List.of(), GitOpsSecretValues.bindingKeys("[0].secret"));
        assertEquals(List.of(), GitOpsSecretValues.bindingKeys("a..b"));
    }

    @Test
    public void aValueIsReadAtAHelmPathWithListIndices() {
        Map<String, Object> values = map("a", map("b", list(map("c", "first"), map("c", "second"))));

        assertEquals("second", GitOpsSecretValues.valueAt(values, "a.b[1].c"));
        assertNull(GitOpsSecretValues.valueAt(values, "a.b[5].c"));
        assertNull(GitOpsSecretValues.valueAt(values, "a.x.c"));
        assertNull("a path naming nothing is not the whole map", GitOpsSecretValues.valueAt(values, "[0]"));
    }

    private static FormField field(String name, String type, boolean excluded) {
        FormField f = new FormField();
        f.name = name;
        f.type = type;
        f.excludeFromValues = excluded;
        return f;
    }

    private static StackProperty property(String name, String type) {
        StackProperty p = new StackProperty();
        p.name = name;
        p.type = type;
        return p;
    }
}
