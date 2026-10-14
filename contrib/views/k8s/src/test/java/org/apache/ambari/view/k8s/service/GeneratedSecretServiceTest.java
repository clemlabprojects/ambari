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

import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.stack.GeneratedSecret;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The credentials KDPS generates for a release: random once, then read back on every deploy (the Secret is reused,
 * never regenerated), adopted from where the chart kept a database password, never replaced by one the database
 * would reject, and set on the account by every deploy until one gets through.
 */
@EnableKubernetesMockClient(crud = true)
class GeneratedSecretServiceTest {

    static KubernetesClient client;

    private static final String NS = "analytics";
    private static final String REL = "bi";

    private KubernetesService kubernetes;
    private GeneratedSecretService service;

    @BeforeEach
    void setUp() {
        client.secrets().inNamespace(NS).delete();
        client.persistentVolumeClaims().inNamespace(NS).delete();
        ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getInstanceName()).thenReturn("test-instance");
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir",
                System.getProperty("java.io.tmpdir") + "/k8s-generated-secrets-ut"));
        kubernetes = spy(new KubernetesService(ctx, client, mock(HelmClient.class), true));
        service = new GeneratedSecretService(kubernetes);
    }

    private static GeneratedSecret admin() {
        GeneratedSecret spec = new GeneratedSecret();
        spec.key = "admin";
        spec.secretNameTemplate = "{{releaseName}}-superset-admin";
        spec.label = "the Superset administrator password";
        spec.chartVersion = ">=0.12.50";
        spec.keys = new LinkedHashMap<>(Map.of("password", new GeneratedSecret.GeneratedKey()));
        spec.values = new LinkedHashMap<>();
        spec.values.put("init.adminUser.existingSecret", "{{secretName}}");
        spec.values.put("init.adminUser.resetPassword", "{{generated}}");
        spec.notice = "Its password is in the Secret {{namespace}}/{{secretName}}. Read it with: {{readCommand}}";
        return spec;
    }

    private static GeneratedSecret database() {
        GeneratedSecret spec = new GeneratedSecret();
        spec.key = "database";
        spec.secretNameTemplate = "{{releaseName}}-superset-db";
        spec.label = "the passwords of the Superset PostgreSQL database";
        spec.dataVolume = "data-{{releaseName}}-postgresql-0";
        spec.keys = new LinkedHashMap<>();
        GeneratedSecret.GeneratedKey password = adopting("password");
        password.keptByVolume = true;
        spec.keys.put("password", password);
        spec.keys.put("postgres-password", adopting("postgres-password"));
        GeneratedSecret.GeneratedKey connection = new GeneratedSecret.GeneratedKey();
        connection.template = "postgresql://superset:${password}@{{releaseName}}-postgresql:5432/superset";
        spec.keys.put("connection", connection);
        spec.values = new LinkedHashMap<>(Map.of("postgresql.auth.existingSecret", "{{secretName}}"));
        return spec;
    }

    private static GeneratedSecret.GeneratedKey adopting(String key) {
        GeneratedSecret.GeneratedKey k = new GeneratedSecret.GeneratedKey();
        k.adoptFrom = List.of(Map.of("secret", "{{releaseName}}-postgresql", "key", key));
        return k;
    }

    private List<GeneratedSecretService.Outcome> ensure(GeneratedSecret... specs) {
        return service.ensure(List.of(specs), NS, REL, "0.12.50", Map.of(), null);
    }

    private Map<String, String> data(String name) {
        Secret secret = client.secrets().inNamespace(NS).withName(name).get();
        assertNotNull(secret, "Secret " + name + " exists");
        Map<String, String> out = new LinkedHashMap<>();
        secret.getData().forEach((k, v) -> out.put(k, new String(Base64.getDecoder().decode(v), StandardCharsets.UTF_8)));
        return out;
    }

    private void chartSecret(String name, Map<String, String> data) {
        Map<String, String> encoded = new LinkedHashMap<>();
        data.forEach((k, v) -> encoded.put(k, Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8))));
        client.secrets().inNamespace(NS).resource(new SecretBuilder().withNewMetadata().withName(name)
                .withNamespace(NS).endMetadata().withData(encoded).build()).create();
    }

    private Map<String, String> annotations(String name) {
        Map<String, String> annotations = client.secrets().inNamespace(NS).withName(name).get().getMetadata()
                .getAnnotations();
        return annotations == null ? Map.of() : annotations;
    }

    private void volume(String name) {
        client.persistentVolumeClaims().inNamespace(NS).resource(new PersistentVolumeClaimBuilder()
                .withNewMetadata().withName(name).withNamespace(NS).endMetadata().build()).create();
    }

    @Test
    void generatesARandomPasswordOnceThenReusesTheExistingSecret() {
        GeneratedSecretService.Outcome first = ensure(admin()).get(0);

        assertTrue(first.applied());
        assertEquals("bi-superset-admin", first.secretName());
        assertEquals(List.of("password"), first.generated());
        String password = data("bi-superset-admin").get("password");
        assertTrue(password.matches("[A-Za-z0-9]{32}"), "32 random letters and digits: " + password);
        assertNotEquals("admin", password);
        assertEquals("bi-superset-admin", first.values().get("init.adminUser.existingSecret"),
                "the chart gets the Secret's name");
        assertEquals("true", first.values().get("init.adminUser.resetPassword"),
                "the deploy that generates the password sets it on an administrator created before");
        String version = client.secrets().inNamespace(NS).withName("bi-superset-admin").get()
                .getMetadata().getResourceVersion();

        GeneratedSecretService.Outcome second = ensure(admin()).get(0);

        assertEquals(password, data("bi-superset-admin").get("password"), "an upgrade keeps the password");
        assertTrue(second.generated().isEmpty());
        assertEquals(version, client.secrets().inNamespace(NS).withName("bi-superset-admin").get()
                .getMetadata().getResourceVersion(), "the Secret is reused as it is, not written again");
    }

    @Test
    void theAccountIsGivenThePasswordUntilADeployThatCarriedItSucceeds() {
        GeneratedSecretService.Outcome first = ensure(admin()).get(0);
        assertTrue(first.pending());
        assertEquals("true", annotations("bi-superset-admin").get(GeneratedSecretService.PENDING_ANNOTATION));

        // The first deploy never ran the chart's hook: refused, timed out in Helm, or a GitOps change not merged.
        GeneratedSecretService.Outcome retry = ensure(admin()).get(0);

        assertTrue(retry.generated().isEmpty(), "the password is not generated again");
        assertTrue(retry.pending());
        assertEquals("true", retry.values().get("init.adminUser.resetPassword"),
                "the next deploy still sets the password on the administrator that has the chart's default");

        service.markApplied(NS, "bi-superset-admin");
        GeneratedSecretService.Outcome later = ensure(admin()).get(0);

        assertFalse(annotations("bi-superset-admin").containsKey(GeneratedSecretService.PENDING_ANNOTATION));
        assertEquals("The Superset administrator password",
                annotations("bi-superset-admin").get(GeneratedSecretService.DESCRIPTION_ANNOTATION),
                "the Secret's other annotations are kept");
        assertFalse(later.pending());
        assertEquals("false", later.values().get("init.adminUser.resetPassword"),
                "once a deploy set it, a password the operator changed in Superset is left alone");
    }

    @Test
    void onlyAValueTheChartSetsOnAnAccountWaitsForADeployToSucceed() {
        GeneratedSecretService.Outcome outcome = ensure(database()).get(0);

        assertFalse(outcome.pending(), "PostgreSQL takes its passwords when it initializes its volume");
        assertFalse(annotations("bi-superset-db").containsKey(GeneratedSecretService.PENDING_ANNOTATION));
    }

    @Test
    void theChartValuesAndTheNoticeCarryTheSecretNameNeverItsValue() {
        GeneratedSecretService.Outcome outcome = ensure(admin()).get(0);
        String password = data("bi-superset-admin").get("password");

        assertFalse(outcome.values().values().stream().anyMatch(v -> v.contains(password)), outcome.values().toString());
        assertEquals("Its password is in the Secret analytics/bi-superset-admin. Read it with: kubectl -n analytics get"
                + " secret bi-superset-admin -o jsonpath='{.data.password}' | base64 -d", outcome.notice());
        assertFalse(outcome.notice().contains(password));
    }

    @Test
    void theSecretIsNotPartOfTheHelmReleaseSoAnUninstallKeepsIt() {
        ensure(admin());

        Secret secret = client.secrets().inNamespace(NS).withName("bi-superset-admin").get();
        Map<String, String> labels = secret.getMetadata().getLabels();
        assertEquals(REL, labels.get(GeneratedSecretService.RELEASE_LABEL));
        assertEquals(GeneratedSecretService.TYPE_VALUE, labels.get(GeneratedSecretService.TYPE_LABEL));
        assertNotEquals("Helm", labels.get("app.kubernetes.io/managed-by"));
        Map<String, String> annotations = secret.getMetadata().getAnnotations();
        assertFalse(annotations.containsKey("meta.helm.sh/release-name"), annotations.toString());
        assertEquals("The Superset administrator password",
                annotations.get(GeneratedSecretService.DESCRIPTION_ANNOTATION));
        assertTrue(secret.getMetadata().getOwnerReferences() == null
                || secret.getMetadata().getOwnerReferences().isEmpty());
    }

    @Test
    void eachReleaseGetsItsOwnPasswords() {
        ensure(admin());
        service.ensure(List.of(admin()), NS, "sales", "0.12.50", Map.of(), null);

        assertNotEquals(data("bi-superset-admin").get("password"), data("sales-superset-admin").get("password"));
    }

    @Test
    void aReleaseInstalledBeforeKeepsThePasswordItsDatabaseRunsWith() {
        // The PostgreSQL chart's own Secret of a release deployed with the chart's fixed default.
        chartSecret("bi-postgresql", Map.of("password", "superset", "postgres-password", "Pg-Admin-1"));
        volume("data-bi-postgresql-0");

        GeneratedSecretService.Outcome outcome = ensure(database()).get(0);

        Map<String, String> data = data("bi-superset-db");
        assertEquals("superset", data.get("password"));
        assertEquals("Pg-Admin-1", data.get("postgres-password"));
        assertEquals(List.of("password", "postgres-password"), outcome.adopted());
        assertTrue(outcome.generated().isEmpty());
        assertEquals("postgresql://superset:superset@bi-postgresql:5432/superset", data.get("connection"));
        assertEquals(Map.of("postgresql.auth.existingSecret", "bi-superset-db"), outcome.values());
    }

    @Test
    void aFreshInstallGetsRandomDatabasePasswords() {
        GeneratedSecretService.Outcome outcome = ensure(database()).get(0);

        Map<String, String> data = data("bi-superset-db");
        assertTrue(data.get("password").matches("[A-Za-z0-9]{32}"));
        assertNotEquals(data.get("password"), data.get("postgres-password"));
        assertEquals(List.of("password", "postgres-password"), outcome.generated());
    }

    @Test
    void aDatabaseVolumeWhosePasswordIsNowhereRefusesTheDeploy() {
        volume("data-bi-postgresql-0");

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> ensure(database()));

        String message = refused.getMessage();
        assertTrue(message.contains("data-bi-postgresql-0"), message);
        assertTrue(message.contains("bi-superset-db"), message);
        assertTrue(message.contains("bi-postgresql"), message);
        assertTrue(message.contains("key 'password'"), message);
        assertFalse(message.contains("'postgres-password'"), "only the password the release signs in with: " + message);
        assertTrue(message.indexOf("Create the Secret") < message.indexOf("Deleting the volume"),
                "deleting the volume is not the first remedy: " + message);
        assertTrue(message.contains("lost for good"), "deleting the volume is said to lose the data: " + message);
        assertNull(client.secrets().inNamespace(NS).withName("bi-superset-db").get(),
                "no password the database would reject is written");
    }

    @Test
    void aVolumeClaimThatCannotBeReadRefusesTheDeployRatherThanGuessNoVolume() {
        // The account cannot get volume claims (403), or the API did not answer: the claim may hold a database.
        doReturn(null).when(kubernetes).persistentVolumeClaimExists(NS, "data-bi-postgresql-0");
        GeneratedSecret spec = database();
        spec.keys.get("password").formerDefault = "superset";

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> ensure(spec));

        String message = refused.getMessage();
        assertTrue(message.contains("could not read the volume claim data-bi-postgresql-0"), message);
        assertTrue(message.contains("get persistentvolumeclaims in namespace analytics"), message);
        assertTrue(message.contains("create the Secret bi-superset-db in namespace analytics with key 'password'"),
                message);
        assertFalse(message.contains("'superset'"), "no password in the message: " + message);
        assertNull(client.secrets().inNamespace(NS).withName("bi-superset-db").get(),
                "neither a random password nor the chart's former one is written");
    }

    @Test
    void aVolumeClaimThatCannotBeReadIsReadOnceAndAnyOtherKeyIsGeneratedWithAWarning() {
        doReturn(null).when(kubernetes).persistentVolumeClaimExists(NS, "data-bi-postgresql-0");
        // The password the release signs in with is known: only the administrator password is missing.
        chartSecret("bi-superset-db", Map.of("password", "Known-App-Pass-1"));
        GeneratedSecret spec = database();
        GeneratedSecret.GeneratedKey replication = new GeneratedSecret.GeneratedKey();
        spec.keys.put("replication-password", replication);

        GeneratedSecretService.Outcome outcome = ensure(spec).get(0);

        assertEquals("Known-App-Pass-1", data("bi-superset-db").get("password"));
        assertEquals(List.of("postgres-password", "replication-password"), outcome.generated());
        assertEquals(2, outcome.warnings().size(), outcome.warnings().toString());
        assertTrue(outcome.warnings().get(0).contains("could not read the volume claim analytics/data-bi-postgresql-0")
                && outcome.warnings().get(0).contains("'postgres-password'"), outcome.warnings().get(0));
        verify(kubernetes, times(1)).persistentVolumeClaimExists(NS, "data-bi-postgresql-0");
    }

    @Test
    void aPasswordNothingSignsInWithIsGeneratedOnAKeptVolume() {
        // The operator followed the refusal and stored the password the release signs in with; the administrator
        // password PostgreSQL generated at the first install went with the chart's Secret.
        chartSecret("bi-superset-db", Map.of("password", "Known-App-Pass-1"));
        volume("data-bi-postgresql-0");

        GeneratedSecretService.Outcome outcome = ensure(database()).get(0);

        Map<String, String> data = data("bi-superset-db");
        assertEquals("Known-App-Pass-1", data.get("password"));
        assertTrue(data.get("postgres-password").matches("[A-Za-z0-9]{32}"));
        assertEquals(List.of("postgres-password"), outcome.generated());
        assertEquals(1, outcome.warnings().size(), outcome.warnings().toString());
        assertTrue(outcome.warnings().get(0).contains("'postgres-password'")
                && outcome.warnings().get(0).contains("does not use"), outcome.warnings().get(0));
        assertEquals("postgresql://superset:Known-App-Pass-1@bi-postgresql:5432/superset", data.get("connection"));
    }

    @Test
    void aReleaseInstalledBeforeAndReinstalledKeepsThePasswordItsChartUsedToSet() {
        // Installed with the chart's fixed password, then uninstalled: Helm deleted bi-postgresql, the volume stayed.
        volume("data-bi-postgresql-0");
        GeneratedSecret spec = database();
        spec.keys.get("password").formerDefault = "superset";

        GeneratedSecretService.Outcome outcome = ensure(spec).get(0);

        Map<String, String> data = data("bi-superset-db");
        assertEquals("superset", data.get("password"), "the password the database was initialized with");
        assertEquals("postgresql://superset:superset@bi-postgresql:5432/superset", data.get("connection"));
        assertEquals(List.of("password"), outcome.adopted());
        assertEquals(List.of("postgres-password"), outcome.generated());
        String rotate = outcome.warnings().stream().filter(w -> w.contains("key 'password'")).findFirst()
                .orElseThrow(() -> new AssertionError(outcome.warnings().toString()));
        assertTrue(rotate.contains("Anyone who reads the chart knows it") && rotate.contains("ALTER USER"), rotate);
        assertFalse(rotate.contains("superset@") || rotate.contains("'superset'"), "no password in a log: " + rotate);
    }

    @Test
    void aDatabaseStillAtItsChartsFormerPasswordIsWarnedOnEveryDeploy() {
        chartSecret("bi-postgresql", Map.of("password", "superset", "postgres-password", "Pg-Admin-1"));
        volume("data-bi-postgresql-0");
        GeneratedSecret spec = database();
        spec.keys.get("password").formerDefault = "superset";

        GeneratedSecretService.Outcome upgrade = ensure(spec).get(0);
        GeneratedSecretService.Outcome next = ensure(spec).get(0);

        assertEquals(1, upgrade.warnings().size(), upgrade.warnings().toString());
        assertTrue(upgrade.warnings().get(0).contains("still uses the password its chart used to set"),
                upgrade.warnings().get(0));
        assertEquals(upgrade.warnings(), next.warnings(), "until the password is changed");

        chartSecret("sales-superset-db", Map.of("password", "Rotated-1", "postgres-password", "Pg-Admin-2"));
        assertTrue(service.ensure(List.of(spec), NS, "sales", "0.12.50", Map.of(), null).get(0).warnings().isEmpty(),
                "a password changed since is not warned about");
    }

    @Test
    void aReinstallOnTheKeptVolumeFindsTheGeneratedPasswords() {
        ensure(database());
        String password = data("bi-superset-db").get("password");
        // helm uninstall: the chart's Secrets go, the database volume and the generated Secret stay.
        volume("data-bi-postgresql-0");

        GeneratedSecretService.Outcome outcome = ensure(database()).get(0);

        assertEquals(password, data("bi-superset-db").get("password"));
        assertTrue(outcome.generated().isEmpty() && outcome.adopted().isEmpty());
    }

    @Test
    void aKeyAddedLaterIsGeneratedAloneAndTheTemplateFollowsThePassword() {
        Map<String, String> encoded = Map.of(
                "password", Base64.getEncoder().encodeToString("p@ss/w:rd".getBytes(StandardCharsets.UTF_8)),
                "connection", Base64.getEncoder().encodeToString("stale".getBytes(StandardCharsets.UTF_8)));
        client.secrets().inNamespace(NS).resource(new SecretBuilder().withNewMetadata().withName("bi-superset-db")
                .withNamespace(NS).addToLabels("owner", "operator").endMetadata().withData(encoded).build()).create();
        volume("data-bi-postgresql-0");
        GeneratedSecret spec = database();
        spec.keys.get("postgres-password").adoptFrom = null;
        spec.dataVolume = null;

        GeneratedSecretService.Outcome outcome = ensure(spec).get(0);

        Map<String, String> data = data("bi-superset-db");
        assertEquals("p@ss/w:rd", data.get("password"), "a value already in the Secret is kept");
        assertEquals(List.of("postgres-password"), outcome.generated());
        assertEquals("postgresql://superset:p%40ss%2Fw%3Ard@bi-postgresql:5432/superset", data.get("connection"),
                "the connection is made again from the password, URL-encoded");
        assertEquals("operator", client.secrets().inNamespace(NS).withName("bi-superset-db").get()
                .getMetadata().getLabels().get("owner"), "the Secret's own labels are kept");
    }

    @Test
    void aChartTooOldToReadTheSecretIsWarnedAndNothingIsCreated() {
        List<GeneratedSecretService.Outcome> outcomes =
                service.ensure(List.of(admin()), NS, REL, "0.12.49", Map.of(), null);

        GeneratedSecretService.Outcome outcome = outcomes.get(0);
        assertFalse(outcome.applied());
        assertTrue(outcome.values().isEmpty());
        assertEquals(1, outcome.warnings().size());
        assertTrue(outcome.warnings().get(0).contains("0.12.49") && outcome.warnings().get(0).contains(">=0.12.50"),
                outcome.warnings().get(0));
        assertNull(client.secrets().inNamespace(NS).withName("bi-superset-admin").get());
    }

    @Test
    void aSecretForAComponentThatIsOffIsNotCreated() {
        GeneratedSecret airflow = admin();
        airflow.secretNameTemplate = "{{releaseName}}-airflow-admin";
        airflow.when = "dependencies.enabled";

        assertTrue(service.ensure(List.of(airflow), NS, REL, null, Map.of("dependencies", Map.of("enabled", false)),
                null).isEmpty());
        assertNull(client.secrets().inNamespace(NS).withName("bi-airflow-admin").get());

        List<GeneratedSecretService.Outcome> on = service.ensure(List.of(airflow), NS, REL, null, Map.of(),
                Map.of("dependencies", Map.of("enabled", true)));
        assertEquals(1, on.size());
        assertNotNull(client.secrets().inNamespace(NS).withName("bi-airflow-admin").get());
    }
}
