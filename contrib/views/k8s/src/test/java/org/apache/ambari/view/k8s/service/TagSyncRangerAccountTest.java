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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.apache.ambari.view.URLStreamProvider;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.om.FakeRangerAdmin;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.apache.ambari.view.k8s.utils.TlsPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.ambari.view.k8s.service.om.FakeRangerAdmin.roles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The Ranger account the Atlas tag projector of a Trino release writes tags with. Earlier versions put the platform
 * context's own Ranger administrator, user and password, into the release's project (external context), or a
 * {@code ROLE_SYS_ADMIN} account named after the release alone that nothing ever disabled (managed context). Each
 * release now gets an account of its own with a new password on every deploy, only that account reaches the project,
 * and it is disabled, and its Secret deleted, when the release no longer uses it. KDPS records the account in the
 * view, so what is disabled, and where, never depends on the Secret, which project users can edit or delete.
 */
class TagSyncRangerAccountTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "trino";
    private static final String CLUSTER = "https://api.ocp.example:6443";
    private static final String INSTANCE = "0d6f3c2e-kdps";
    private static final String CONTEXT = "cdp-prod";
    private static final String ACCOUNT = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, CLUSTER, CONTEXT);
    private static final String OWNER = TagSyncRangerAccount.owner(NS, RELEASE, INSTANCE, CLUSTER);
    private static final String SECRET = "trino-tagsync-ranger";

    private KubernetesService k8s;
    private FakeRangerAdmin ranger;
    private MemoryRecords records;

    @BeforeEach
    void setUp() throws Exception {
        k8s = mock(KubernetesService.class);
        ranger = new FakeRangerAdmin();
        ranger.addUser("admin", 0, "admin-pw", "ROLE_SYS_ADMIN");
        records = new MemoryRecords();
    }

    @AfterEach
    void tearDown() {
        ranger.close();
    }

    private TagSyncRangerAccount.RangerUsers directRanger() {
        return TagSyncRangerAccount.direct(TlsPolicy.JVM_DEFAULT, ranger.url(), "admin", "admin-pw");
    }

    private TagSyncRangerAccount.Provisioned provision(TagSyncRangerAccount.RangerUsers users, String contextId) {
        return TagSyncRangerAccount.provision(k8s, records, users, contextId2 -> directRanger(), NS, RELEASE,
                contextId, null, INSTANCE, CLUSTER, null);
    }

    /**
     * A Secret KDPS wrote, as the cluster holds it: values base64-encoded under {@code data}, and the annotation every
     * Secret KDPS writes carries, which earlier versions set too.
     */
    private static Secret secret(String user, String password, Map<String, String> annotations) {
        Map<String, String> marked = new HashMap<>(annotations);
        marked.put("managed-by", "ambari-k8s-view");
        return operatorSecret(user, password, marked);
    }

    /** A Secret named like KDPS's own that the operator created: no KDPS annotation, no KDPS label. */
    private static Secret operatorSecret(String user, String password, Map<String, String> annotations) {
        Map<String, String> data = new HashMap<>();
        data.put("user", Base64.getEncoder().encodeToString(user.getBytes(StandardCharsets.UTF_8)));
        data.put("password", Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
        return new SecretBuilder().withNewMetadata().withName(SECRET).withNamespace(NS)
                .withAnnotations(annotations).endMetadata().withData(data).build();
    }

    /** What the deploy wrote into the release's Secret: its data and labels. */
    private record Written(Map<String, String> data, Map<String, String> labels) {
    }

    @SuppressWarnings("unchecked")
    private Written written() {
        ArgumentCaptor<Map<String, byte[]>> data = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);
        verify(k8s).createOrUpdateOpaqueSecret(eq(NS), eq(SECRET), data.capture(), labels.capture());
        Map<String, String> text = new LinkedHashMap<>();
        data.getValue().forEach((k, v) -> text.put(k, new String(v, StandardCharsets.UTF_8)));
        return new Written(text, labels.getValue());
    }

    /** The release's account as an earlier deploy left it in Ranger, with the description it is created with. */
    private JsonObject existingAccount(String user, String owner) {
        JsonObject account = ranger.addUser(user, 0, "old-pw", "ROLE_SYS_ADMIN");
        account.addProperty("description", owner);
        return account;
    }

    @Test
    void theReleaseGetsItsOwnAccountAndTheAdministratorNeverReachesTheProject() {
        TagSyncRangerAccount.Provisioned account = provision(directRanger(), "cdp-prod");

        assertEquals(ACCOUNT, account.userName());
        assertTrue(account.warnings().isEmpty());
        Written secret = written();
        assertEquals(ACCOUNT, secret.data().get("user"));
        assertNotEquals("admin", secret.data().get("user"));
        assertNotEquals("admin-pw", secret.data().get("password"));
        JsonObject created = ranger.users.get(ACCOUNT);
        assertNotNull(created, "the account is created in the context's Ranger");
        assertEquals(List.of("ROLE_SYS_ADMIN"), roles(created), "Ranger lets only this role import tags");
        assertEquals(1, created.get("status").getAsInt());
        assertEquals(OWNER, created.get("description").getAsString(), "its description names the release");
        assertEquals(secret.data().get("password"), created.get("password").getAsString(),
                "the projector logs in with the password the Secret holds");
        assertEquals(new TagSyncRangerAccount.Recorded(ACCOUNT, "cdp-prod", OWNER), records.find(NS, RELEASE),
                "the view records the account and where it is");
        assertEquals(RELEASE, secret.labels().get("app.kubernetes.io/instance"));
        // Listed with the release's other objects, but left to revoke, which disables the account first.
        assertEquals(RELEASE, secret.labels().get(KdpsReleaseObjects.LABEL_RELEASE));
        assertEquals("kdps", secret.labels().get(KdpsReleaseObjects.LABEL_MANAGED_BY));
        assertEquals("admin-pw", ranger.users.get("admin").get("password").getAsString(), "the administrator is untouched");
    }

    @Test
    void everyDeployGivesTheAccountANewPassword() {
        provision(directRanger(), CONTEXT);
        String first = ranger.users.get(ACCOUNT).get("password").getAsString();
        doReturn(secret(ACCOUNT, first, Map.of())).when(k8s).getSecret(NS, SECRET);

        TagSyncRangerAccount.Provisioned again = provision(directRanger(), CONTEXT);

        String second = ranger.users.get(ACCOUNT).get("password").getAsString();
        assertNotEquals(first, second);
        assertTrue(again.warnings().isEmpty(), "the release's own account is nothing to report");
        assertEquals(1, ranger.users.get(ACCOUNT).get("status").getAsInt());
    }

    @Test
    void noTwoReleasesShareAnAccount() {
        assertEquals("kdps-tagsync-analytics_trino", TagSyncRangerAccount.userName("trino", "analytics", null, null, null));
        // A hyphen in the release or in the namespace cannot move the boundary between the two.
        assertNotEquals(TagSyncRangerAccount.userName("trino", "team-a", INSTANCE, CLUSTER, null),
                TagSyncRangerAccount.userName("trino-team", "a", INSTANCE, CLUSTER, null));
        // Nor can any account be named like one an earlier version named after a release alone.
        assertNotEquals(TagSyncRangerAccount.legacyUserName("prod-trino"),
                TagSyncRangerAccount.userName("trino", "prod", null, null, null));
        assertNotEquals(TagSyncRangerAccount.legacyUserName("trino-prod"),
                TagSyncRangerAccount.userName("prod", "trino", null, null, null));
        assertEquals("kdps-tagsync-trino", TagSyncRangerAccount.legacyUserName("Trino"));
        // The same release on another Kubernetes cluster whose KDPS uses the same Ranger.
        assertNotEquals(TagSyncRangerAccount.userName("trino", "analytics", INSTANCE, "https://api.ocp-a.example:6443", null),
                TagSyncRangerAccount.userName("trino", "analytics", INSTANCE, "https://api.ocp-b.example:6443", null));
        // The same release on another platform context, which may reach the same Ranger.
        assertNotEquals(TagSyncRangerAccount.userName("trino", "analytics", INSTANCE, CLUSTER, "cdp-a"),
                TagSyncRangerAccount.userName("trino", "analytics", INSTANCE, CLUSTER, "cdp-b"));
        assertNotEquals(TagSyncRangerAccount.userName("trino", "analytics", INSTANCE, CLUSTER, null),
                TagSyncRangerAccount.userName("trino", "analytics", INSTANCE, CLUSTER, CONTEXT));
        // The managed cluster, whether the deploy names the default context or none.
        assertEquals(TagSyncRangerAccount.userName("trino", "analytics", INSTANCE, CLUSTER, null),
                TagSyncRangerAccount.userName("trino", "analytics", INSTANCE, CLUSTER, ContextService.DEFAULT_CONTEXT_ID));
        assertEquals(TagSyncRangerAccount.userName("trino", "analytics", INSTANCE, CLUSTER, CONTEXT), ACCOUNT, "and a stable one");
        assertTrue(ACCOUNT.matches("kdps-tagsync-analytics_trino_[0-9a-f]{10}"), ACCOUNT);
    }

    @Test
    void anAccountOfThatNameBelongingToAnotherReleaseIsNotTakenOver() {
        existingAccount(ACCOUNT, TagSyncRangerAccount.owner(NS, RELEASE, INSTANCE, "https://api.other.example:6443"));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> provision(directRanger(), CONTEXT));

        assertTrue(refused.getMessage().contains("left unchanged"), refused.getMessage());
        assertEquals("old-pw", ranger.users.get(ACCOUNT).get("password").getAsString());
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap(), any());
        assertNull(records.find(NS, RELEASE));
    }

    @Test
    void theAdministratorAnEarlierVersionCopiedIsReplacedAndReported() {
        doReturn(secret("admin", "admin-pw", Map.of())).when(k8s).getSecret(NS, SECRET);

        TagSyncRangerAccount.Provisioned account = provision(directRanger(), "cdp-prod");

        assertEquals(ACCOUNT, written().data().get("user"));
        assertEquals(1, account.warnings().size());
        String warning = account.warnings().get(0);
        assertTrue(warning.contains("'admin'"), warning);
        assertTrue(warning.contains("Change the password"), warning);
        assertFalse(warning.contains("admin-pw"), "the password itself is never repeated: " + warning);
        JsonObject admin = ranger.users.get("admin");
        assertEquals(1, admin.get("status").getAsInt(), "the administrator account is not disabled by KDPS");
        assertEquals(List.of("ROLE_SYS_ADMIN"), roles(admin));
    }

    @Test
    void theAccountEarlierVersionsCreatedForTheReleaseIsDisabled() {
        ranger.addUser("kdps-tagsync-trino", 0, "old-pw", "ROLE_SYS_ADMIN");
        doReturn(secret("kdps-tagsync-trino", "old-pw", Map.of())).when(k8s).getSecret(NS, SECRET);

        TagSyncRangerAccount.Provisioned account = provision(directRanger(), CONTEXT);

        assertTrue(account.warnings().isEmpty(), String.valueOf(account.warnings()));
        JsonObject legacy = ranger.users.get("kdps-tagsync-trino");
        assertEquals(0, legacy.get("status").getAsInt());
        assertEquals(List.of("ROLE_USER"), roles(legacy));
        assertNotEquals("old-pw", legacy.get("password").getAsString());
        assertEquals(1, ranger.users.get(ACCOUNT).get("status").getAsInt());
    }

    @Test
    void anOldAccountThatCannotBeDisabledIsReported() {
        doReturn(secret("kdps-tagsync-trino", "old-pw", Map.of())).when(k8s).getSecret(NS, SECRET);
        RecordingRanger recording = new RecordingRanger();
        recording.disableFailure = new IllegalStateException("HTTP 503");

        TagSyncRangerAccount.Provisioned account = TagSyncRangerAccount.provision(k8s, records, recording,
                contextId -> recording, NS, RELEASE, null, null, INSTANCE, CLUSTER, null);

        assertEquals(1, account.warnings().size());
        assertTrue(account.warnings().get(0).contains("kdps-tagsync-trino"), account.warnings().get(0));
        assertTrue(account.warnings().get(0).contains("HTTP 503"), account.warnings().get(0));
        assertTrue(account.warnings().get(0).contains("tries again"), account.warnings().get(0));
        assertEquals(List.of(new TagSyncRangerAccount.Recorded("kdps-tagsync-trino", null, null)),
                records.toDisable(NS, RELEASE), "the Secret no longer names it, so the view remembers it");
    }

    @Test
    void theAccountEarlierVersionsCreatedIsDisabledWhereTheReleaseWasNotWhereItMoves() {
        // Deployed by an earlier version on the managed cluster, then moved to an external context by Upgrade / Config.
        doReturn(secret("kdps-tagsync-trino", "old-pw", Map.of())).when(k8s).getSecret(NS, SECRET);
        RecordingRanger external = new RecordingRanger();
        external.found = false;
        RecordingRanger managed = new RecordingRanger();
        List<String> located = new ArrayList<>();

        TagSyncRangerAccount.Provisioned account = TagSyncRangerAccount.provision(k8s, records, external,
                contextId -> { located.add(contextId); return contextId == null ? managed : external; }, NS, RELEASE,
                CONTEXT, ContextService.DEFAULT_CONTEXT_ID, INSTANCE, CLUSTER, null);

        assertTrue(account.warnings().isEmpty(), String.valueOf(account.warnings()));
        assertEquals(java.util.Collections.singletonList(null), located, "the managed cluster's Ranger");
        assertEquals(List.of("kdps-tagsync-trino of null"), managed.disabled);
        assertEquals(List.of(), external.disabled);
        assertEquals(List.of(), records.toDisable(NS, RELEASE));
    }

    @Test
    void anAccountEarlierVersionsCreatedThatIsNotWhereTheReleaseWasIsReported() {
        doReturn(secret("kdps-tagsync-trino", "old-pw", Map.of())).when(k8s).getSecret(NS, SECRET);
        RecordingRanger elsewhere = new RecordingRanger();
        elsewhere.found = false;

        TagSyncRangerAccount.Provisioned account = TagSyncRangerAccount.provision(k8s, records, elsewhere,
                contextId -> elsewhere, NS, RELEASE, CONTEXT, "cdp-old", INSTANCE, CLUSTER, null);

        assertEquals(1, account.warnings().size(), String.valueOf(account.warnings()));
        String warning = account.warnings().get(0);
        assertTrue(warning.contains("'kdps-tagsync-trino'") && warning.contains("was not found"), warning);
        assertTrue(warning.contains("platform context 'cdp-old'"), warning);
    }

    @Test
    void twoKdpsInstancesReachingTheirClustersAtTheSameUrlGetDifferentAccounts() {
        // Two in-cluster KDPS, with the same context id, release and namespace, using one Ranger.
        String inCluster = "https://kubernetes.default.svc";
        String staging = TagSyncRangerAccount.userName(RELEASE, NS, "kdps-staging", inCluster, CONTEXT);
        String production = TagSyncRangerAccount.userName(RELEASE, NS, "kdps-production", inCluster, CONTEXT);
        assertNotEquals(staging, production);
        assertNotEquals(TagSyncRangerAccount.owner(NS, RELEASE, "kdps-staging", inCluster),
                TagSyncRangerAccount.owner(NS, RELEASE, "kdps-production", inCluster));

        TagSyncRangerAccount.provision(k8s, records, directRanger(), contextId -> directRanger(), NS, RELEASE, CONTEXT,
                null, "kdps-production", inCluster, null);
        String productionPassword = ranger.users.get(production).get("password").getAsString();
        TagSyncRangerAccount.provision(k8s, new MemoryRecords(), directRanger(), contextId -> directRanger(), NS,
                RELEASE, CONTEXT, null, "kdps-staging", inCluster, null);

        assertEquals(productionPassword, ranger.users.get(production).get("password").getAsString(),
                "the other instance's deploy does not reset it");
        assertEquals(1, ranger.users.get(production).get("status").getAsInt());
        assertEquals(1, ranger.users.get(staging).get("status").getAsInt());
    }

    @Test
    void theInstanceIdIsChosenOnceAndKeptInTheViewSettings() {
        ViewContext ctx = mock(ViewContext.class);
        doReturn(new InMemoryDataStore()).when(ctx).getDataStore();
        ViewContext other = mock(ViewContext.class);
        doReturn(new InMemoryDataStore()).when(other).getDataStore();

        String id = TagSyncRangerAccount.instanceId(new InstanceSettings(ctx));

        assertFalse(id.isBlank());
        assertEquals(id, TagSyncRangerAccount.instanceId(new InstanceSettings(ctx)));
        assertEquals(id, new InstanceSettings(ctx).get(TagSyncRangerAccount.INSTANCE_ID_KEY));
        assertNotEquals(id, TagSyncRangerAccount.instanceId(new InstanceSettings(other)), "another KDPS instance");
    }

    @Test
    void aSecretOfThatNameTheOperatorCreatedIsNotReplaced() {
        doReturn(operatorSecret("team-tag-writer", "team-pw", Map.of())).when(k8s).getSecret(NS, SECRET);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> provision(directRanger(), CONTEXT));

        assertTrue(refused.getMessage().contains("not written by KDPS"), refused.getMessage());
        assertEquals(0, ranger.writes());
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap(), any());
        assertNull(records.find(NS, RELEASE));
    }

    @Test
    void aSecretOfThatNameTheOperatorCreatedIsNeitherDeletedNorReportedOnUninstall() {
        provision(directRanger(), CONTEXT);
        doReturn(operatorSecret("team-tag-writer", "team-pw", Map.of())).when(k8s).getSecret(NS, SECRET);

        TagSyncRangerAccount.Revoked revoked = TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, null,
                contextId -> directRanger());

        assertNull(revoked.note(), "no advice about an account KDPS knows nothing of");
        assertEquals(List.of("disabled the Ranger account '" + ACCOUNT + "'"), revoked.done());
        verify(k8s, never()).deleteSecret(anyString(), anyString());
    }

    @Test
    void aDeployWithoutRangerWiringSaysNothingOfASecretTheOperatorCreated() {
        doReturn(operatorSecret("team-tag-writer", "team-pw", Map.of())).when(k8s).getSecret(NS, SECRET);

        assertEquals(List.of(), TagSyncRangerAccount.kept(k8s, records, contextId -> directRanger(), NS, RELEASE));
    }

    @Test
    void kdpsKnowsItsSecretsByItsAnnotationOrItsLabel() {
        assertTrue(TagSyncRangerAccount.writtenByKdps(secret("u", "p", Map.of())));
        assertFalse(TagSyncRangerAccount.writtenByKdps(operatorSecret("u", "p", Map.of())));
        Secret labelled = operatorSecret("u", "p", Map.of());
        labelled.getMetadata().setLabels(Map.of("app.kubernetes.io/managed-by", "kdps"));
        assertTrue(TagSyncRangerAccount.writtenByKdps(labelled));
        assertFalse(TagSyncRangerAccount.writtenByKdps(null));
    }

    @Test
    void theAccountAnEarlierDeployGaveTheReleaseOnAnotherContextIsDisabledThere() {
        String oldAccount = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, CLUSTER, "old-context");
        String newAccount = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, CLUSTER, "new-context");
        records.put(NS, RELEASE, new TagSyncRangerAccount.Recorded(oldAccount, "old-context", OWNER));
        RecordingRanger current = new RecordingRanger();
        RecordingRanger previous = new RecordingRanger();
        List<String> located = new ArrayList<>();

        TagSyncRangerAccount.Provisioned account = TagSyncRangerAccount.provision(k8s, records, current,
                contextId -> { located.add(contextId); return previous; }, NS, RELEASE, "new-context", null,
                INSTANCE, CLUSTER, null);

        assertTrue(account.warnings().isEmpty(), String.valueOf(account.warnings()));
        assertEquals(List.of(newAccount), current.ensured);
        assertEquals(List.of("old-context"), located);
        assertEquals(List.of(oldAccount + " of " + OWNER), previous.disabled);
        assertEquals("new-context", records.find(NS, RELEASE).contextId());
    }

    @Test
    void aRangerThatRefusesTheAccountStopsTheDeployBeforeTheSecretIsWritten() {
        RecordingRanger recording = new RecordingRanger();
        recording.ensureFailure = new IllegalStateException("HTTP 403 — User is not allowed to access the API");

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> provision(recording, CONTEXT));

        assertTrue(refused.getMessage().contains(ACCOUNT), refused.getMessage());
        assertTrue(refused.getMessage().contains("HTTP 403"), refused.getMessage());
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap(), any());
        assertNull(records.find(NS, RELEASE));
    }

    @Test
    void anAccountKdpsCannotRecordStopsTheDeployBeforeTheSecretIsWritten() {
        records.failure = new IllegalStateException("Could not save the view setting");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> provision(new RecordingRanger(), null));

        assertTrue(refused.getMessage().contains("disable the account on uninstall"), refused.getMessage());
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap(), any());
    }

    @Test
    void aSecretThatCannotBeWrittenStopsTheDeployAndTheAccountStaysRecorded() {
        doThrow(new RuntimeException("secrets is forbidden")).when(k8s)
                .createOrUpdateOpaqueSecret(eq(NS), eq(SECRET), anyMap(), any());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> provision(new RecordingRanger(), CONTEXT));

        assertTrue(refused.getMessage().contains("secrets is forbidden"), refused.getMessage());
        assertEquals(ACCOUNT, records.find(NS, RELEASE).userName(), "an uninstall still disables it");
    }

    @Test
    void uninstallDisablesTheAccountAndDeletesTheSecret() {
        provision(directRanger(), "cdp-prod");
        Written w = written();
        doReturn(secret(w.data().get("user"), w.data().get("password"), Map.of())).when(k8s).getSecret(NS, SECRET);
        List<String> located = new ArrayList<>();

        TagSyncRangerAccount.Revoked revoked = TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE,
                "release-record-context", contextId -> { located.add(contextId); return directRanger(); });

        assertNull(revoked.note(), "nothing is left for an administrator");
        assertEquals(List.of("disabled the Ranger account '" + ACCOUNT + "'", "deleted the Secret " + NS + "/" + SECRET),
                revoked.done());
        assertEquals(List.of("cdp-prod"), located, "the context KDPS recorded is the one the account is on");
        JsonObject account = ranger.users.get(ACCOUNT);
        assertEquals(0, account.get("status").getAsInt());
        assertEquals(List.of("ROLE_USER"), roles(account));
        assertNotEquals(w.data().get("password"), account.get("password").getAsString(),
                "a copy of the Secret no longer logs in");
        verify(k8s).deleteSecret(NS, SECRET);
        assertNull(records.find(NS, RELEASE));
    }

    @Test
    void aDeletedSecretDoesNotKeepTheAccountEnabled() {
        // A project user copies the password, then deletes the Secret: the uninstall still finds the account.
        provision(directRanger(), "cdp-prod");
        doReturn(null).when(k8s).getSecret(NS, SECRET);

        TagSyncRangerAccount.Revoked revoked = TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, null,
                contextId -> directRanger());

        assertNull(revoked.note());
        assertEquals(0, ranger.users.get(ACCOUNT).get("status").getAsInt());
        verify(k8s, never()).deleteSecret(anyString(), anyString());
    }

    @Test
    void anEditedSecretChoosesNeitherTheAccountNorItsRanger() {
        provision(directRanger(), "cdp-prod");
        ranger.addUser("ops-admin", 0, "ops-pw", "ROLE_SYS_ADMIN");
        doReturn(secret("ops-admin", "x", Map.of("kdps.clemlab.com/ranger-tag-writer", "ops-admin",
                "kdps.clemlab.com/ranger-tag-writer-context", "another-context"))).when(k8s).getSecret(NS, SECRET);
        List<String> located = new ArrayList<>();

        TagSyncRangerAccount.Revoked revoked = TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, null,
                contextId -> { located.add(contextId); return directRanger(); });

        assertEquals(List.of("cdp-prod"), located);
        assertEquals(0, ranger.users.get(ACCOUNT).get("status").getAsInt(), "the release's own account is disabled");
        assertEquals(1, ranger.users.get("ops-admin").get("status").getAsInt(), "the one the Secret names is not");
        assertTrue(revoked.note().contains("'ops-admin'") && revoked.note().contains("Change the password"),
                revoked.note());
        verify(k8s).deleteSecret(NS, SECRET);
    }

    @Test
    void aPlatformContextThatNoLongerExistsIsReportedNotMistakenForTheManagedCluster() {
        provision(directRanger(), "cdp-prod");

        TagSyncRangerAccount.Revoked revoked = TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, null,
                contextId -> {
                    throw new IllegalStateException("its platform context '" + contextId + "' no longer exists in KDPS");
                });

        assertNotNull(revoked.note());
        assertTrue(revoked.note().contains(ACCOUNT), revoked.note());
        assertTrue(revoked.note().contains("no longer exists"), revoked.note());
        assertTrue(revoked.note().contains("disable it in Ranger"), revoked.note());
        assertEquals(1, ranger.users.get(ACCOUNT).get("status").getAsInt());
    }

    @Test
    void anAccountNotFoundWhereKdpsCreatedItIsReported() {
        records.put(NS, RELEASE, new TagSyncRangerAccount.Recorded(ACCOUNT, "cdp-prod", OWNER));

        TagSyncRangerAccount.Revoked revoked = TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, null,
                contextId -> directRanger());

        assertNotNull(revoked.note());
        assertTrue(revoked.note().contains(ACCOUNT) && revoked.note().contains("was not found"), revoked.note());
        assertTrue(revoked.note().contains("platform context 'cdp-prod'"), revoked.note());
    }

    @Test
    void anAccountWhoseDescriptionNamesAnotherReleaseIsNotDisabled() {
        records.put(NS, RELEASE, new TagSyncRangerAccount.Recorded(ACCOUNT, null, OWNER));
        existingAccount(ACCOUNT, "an operator's own account");

        TagSyncRangerAccount.Revoked revoked = TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, null,
                contextId -> directRanger());

        assertNotNull(revoked.note());
        assertTrue(revoked.note().contains("could not be disabled"), revoked.note());
        assertEquals(1, ranger.users.get(ACCOUNT).get("status").getAsInt());
        assertEquals(List.of("ROLE_SYS_ADMIN"), roles(ranger.users.get(ACCOUNT)));
    }

    @Test
    void aReleaseInstalledAgainUnderTheSameNameGetsItsAccountBack() {
        provision(directRanger(), CONTEXT);
        assertNull(TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, null, contextId -> directRanger()).note());
        assertEquals(0, ranger.users.get(ACCOUNT).get("status").getAsInt());

        provision(directRanger(), CONTEXT);

        JsonObject account = ranger.users.get(ACCOUNT);
        assertEquals(1, account.get("status").getAsInt());
        assertEquals(List.of("ROLE_SYS_ADMIN"), roles(account));
    }

    @Test
    void aReleaseThatNeverSyncedTagsHasNothingToTakeBack() {
        List<String> located = new ArrayList<>();

        TagSyncRangerAccount.Revoked revoked = TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, null,
                contextId -> { located.add(contextId); return directRanger(); });

        assertNull(revoked.note());
        assertTrue(revoked.done().isEmpty());
        assertTrue(located.isEmpty(), "Ranger is not asked anything");
        verify(k8s, never()).deleteSecret(anyString(), anyString());
    }

    @Test
    void anAccountThatCannotBeDisabledIsLeftForAnAdministratorAndTheSecretStillGoes() {
        records.put(NS, RELEASE, new TagSyncRangerAccount.Recorded(ACCOUNT, "cdp-prod", OWNER));
        doReturn(secret(ACCOUNT, "pw", Map.of())).when(k8s).getSecret(NS, SECRET);

        String note = TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, null, contextId -> {
            throw new IllegalStateException("KDPS cannot reach the Ranger of platform context 'cdp-prod' as an administrator");
        }).note();

        assertNotNull(note);
        assertTrue(note.contains(ACCOUNT), note);
        assertTrue(note.contains("cannot reach the Ranger"), note);
        assertTrue(note.contains("disable it in Ranger"), note);
        verify(k8s).deleteSecret(NS, SECRET);
    }

    @Test
    void aSecretThatCannotBeDeletedIsReported() {
        doReturn(secret("admin", "admin-pw", Map.of())).when(k8s).getSecret(NS, SECRET);
        doThrow(new RuntimeException("secrets is forbidden")).when(k8s).deleteSecret(NS, SECRET);

        String note = TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, null, contextId -> directRanger()).note();

        assertTrue(note.contains("could not be deleted") && note.contains("secrets is forbidden"), note);
    }

    @Test
    void anAdministratorAnEarlierVersionCopiedIsDeletedNotDisabledAndReported() {
        doReturn(secret("admin", "admin-pw", Map.of())).when(k8s).getSecret(NS, SECRET);
        List<String> located = new ArrayList<>();

        String note = TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, null,
                contextId -> { located.add(contextId); return directRanger(); }).note();

        assertNotNull(note);
        assertTrue(note.contains("'admin'") && note.contains("Change the password"), note);
        assertTrue(located.isEmpty(), "Ranger is not asked to touch the administrator");
        assertEquals(1, ranger.users.get("admin").get("status").getAsInt());
        verify(k8s).deleteSecret(NS, SECRET);
    }

    @Test
    void theAccountEarlierVersionsCreatedIsDisabledOnTheReleasesRecordedContext() {
        ranger.addUser("kdps-tagsync-trino", 0, "old-pw", "ROLE_SYS_ADMIN");
        doReturn(secret("kdps-tagsync-trino", "old-pw", Map.of())).when(k8s).getSecret(NS, SECRET);
        List<String> located = new ArrayList<>();

        assertNull(TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, "recorded-context",
                contextId -> { located.add(contextId); return directRanger(); }).note());

        assertEquals(List.of("recorded-context"), located);
        assertEquals(0, ranger.users.get("kdps-tagsync-trino").get("status").getAsInt());
        verify(k8s).deleteSecret(NS, SECRET);
    }

    @Test
    void aSecretEditedToNameAnotherAccountDisablesNothing() {
        // Whoever can edit Secrets in the project must not be able to have the uninstall disable someone else.
        ranger.addUser("ops-admin", 0, "ops-pw", "ROLE_SYS_ADMIN");
        doReturn(secret("ops-admin", "x", Map.of())).when(k8s).getSecret(NS, SECRET);

        String note = TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, null, contextId -> directRanger()).note();

        assertNotNull(note);
        assertEquals(1, ranger.users.get("ops-admin").get("status").getAsInt());
        assertEquals(0, ranger.writes());
        verify(k8s).deleteSecret(NS, SECRET);
    }

    @Test
    void theContextsOwnAdministratorIsNeverDisabled() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> directRanger().disable("Admin", null));

        assertTrue(refused.getMessage().contains("administrator"), refused.getMessage());
        assertEquals(1, ranger.users.get("admin").get("status").getAsInt());
    }

    @Test
    void anExternalUserOfThatNameIsNeitherTakenOverNorDisabled() {
        ranger.addUser(ACCOUNT, 1, "ldap-pw", "ROLE_USER");

        assertThrows(IllegalStateException.class, () -> directRanger().ensure(ACCOUNT, "new-pw", OWNER));
        assertThrows(IllegalStateException.class, () -> directRanger().disable(ACCOUNT, OWNER));

        assertEquals("ldap-pw", ranger.users.get(ACCOUNT).get("password").getAsString());
        assertEquals(List.of("ROLE_USER"), roles(ranger.users.get(ACCOUNT)));
        assertEquals(1, ranger.users.get(ACCOUNT).get("status").getAsInt());
    }

    @Test
    void theRecordsAreKeptInTheViewSettings() {
        ViewContext ctx = mock(ViewContext.class);
        doReturn(new InMemoryDataStore()).when(ctx).getDataStore();
        TagSyncRangerAccount.Records stored = TagSyncRangerAccount.recordsIn(new InstanceSettings(ctx));

        stored.put(NS, RELEASE, new TagSyncRangerAccount.Recorded(ACCOUNT, "cdp-prod", OWNER));
        stored.put("finance", RELEASE, new TagSyncRangerAccount.Recorded("kdps-tagsync-finance_trino", null, null));

        assertEquals(new TagSyncRangerAccount.Recorded(ACCOUNT, "cdp-prod", OWNER), stored.find(NS, RELEASE));
        assertEquals(new TagSyncRangerAccount.Recorded("kdps-tagsync-finance_trino", null, null),
                stored.find("finance", RELEASE));
        stored.remove(NS, RELEASE);
        assertNull(stored.find(NS, RELEASE));
        assertNotNull(stored.find("finance", RELEASE));

        // The managed cluster is recorded the same way whether the deploy named the default context or none.
        stored.put("ops", RELEASE, new TagSyncRangerAccount.Recorded("kdps-tagsync-ops_trino", "default", null));
        assertNull(stored.find("ops", RELEASE).contextId());

        List<TagSyncRangerAccount.Recorded> toDisable = List.of(
                new TagSyncRangerAccount.Recorded(ACCOUNT, "cdp-prod", OWNER),
                new TagSyncRangerAccount.Recorded("kdps-tagsync-trino", null, null));
        assertEquals(List.of(), stored.toDisable(NS, RELEASE));
        stored.putToDisable(NS, RELEASE, toDisable);
        assertEquals(toDisable, stored.toDisable(NS, RELEASE));
        assertEquals(List.of(), stored.toDisable("finance", RELEASE));
        stored.putToDisable(NS, RELEASE, List.of());
        assertEquals(List.of(), stored.toDisable(NS, RELEASE));

        // The deploy that gave the account is kept with it.
        stored.put(NS, RELEASE, new TagSyncRangerAccount.Recorded(ACCOUNT, "cdp-prod", OWNER, "deploy-1"));
        assertEquals("deploy-1", stored.find(NS, RELEASE).grant());
    }

    @Test
    void eachDeployGivingTheAccountIsRecordedWithItAndTheEarlierAccountIsKeptToDisableWithoutIt() {
        String oldAccount = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, CLUSTER, "old-context");
        records.put(NS, RELEASE, new TagSyncRangerAccount.Recorded(oldAccount, "old-context", OWNER, "deploy-1"));
        RecordingRanger previous = new RecordingRanger();
        previous.disableFailure = new IllegalStateException("Ranger is down");

        TagSyncRangerAccount.provision(k8s, records, new RecordingRanger(), contextId -> previous, NS, RELEASE,
                "new-context", null, INSTANCE, CLUSTER, "deploy-2");

        assertEquals("deploy-2", records.find(NS, RELEASE).grant());
        assertEquals(List.of(new TagSyncRangerAccount.Recorded(oldAccount, "old-context", OWNER)),
                records.toDisable(NS, RELEASE));
    }

    @Test
    void theManagedClustersRangerIsReachedThroughTheAmbariServer() throws Exception {
        FakeAmbari ambari = new FakeAmbari();
        TagSyncRangerAccount.RangerUsers managed = TagSyncRangerAccount.viaAmbari(ambari.client(), 90);

        managed.ensure(ACCOUNT, "Deploy1Password", OWNER);

        JsonObject upsert = ambari.requests.get(0).getAsJsonObject("RangerPlugin");
        assertEquals("true", upsert.get("userOnly").getAsString());
        assertEquals(ACCOUNT, upsert.get("pluginUserName").getAsString());
        assertEquals("Deploy1Password", upsert.get("pluginUserPassword").getAsString());
        assertEquals("ROLE_SYS_ADMIN", upsert.get("userRoles").getAsString());
        assertEquals("true", upsert.get("resetPassword").getAsString());
        assertEquals(OWNER, upsert.get("userDescription").getAsString());
        assertFalse(upsert.has("disableUser"));

        assertTrue(managed.disable(ACCOUNT, OWNER));

        JsonObject disable = ambari.requests.get(1).getAsJsonObject("RangerPlugin");
        assertEquals("true", disable.get("disableUser").getAsString());
        assertEquals(ACCOUNT, disable.get("pluginUserName").getAsString());
        assertEquals(OWNER, disable.get("userDescription").getAsString());
        assertFalse(disable.has("pluginUserPassword"), "the server makes up the password of a disabled account");

        managed.disable("kdps-tagsync-trino", null);
        assertFalse(ambari.requests.get(2).getAsJsonObject("RangerPlugin").has("userDescription"),
                "an account earlier versions created has no owner to check");
    }

    @Test
    void theAmbariServerSaysWhenRangerHasNoSuchAccount() throws Exception {
        FakeAmbari ambari = new FakeAmbari();
        ambari.found = false;

        assertFalse(TagSyncRangerAccount.viaAmbari(ambari.client(), 90).disable(ACCOUNT, OWNER));
    }

    @Test
    void aFailedAmbariRequestFailsWithWhatRangerAnswered() {
        FakeAmbari ambari = new FakeAmbari();
        ambari.status = "FAILED";

        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> TagSyncRangerAccount.viaAmbari(ambari.client(), 90).disable(ACCOUNT, OWNER));

        assertTrue(failed.getMessage().contains("Ranger answered HTTP 401"), failed.getMessage());
    }

    @Test
    void theManagedClusterKeepsItsAccountWhetherADeployNamesTheDefaultContextOrNone() {
        // Installed through the API, which names no context, then redeployed from the wizard, which names the default.
        List<String> located = new ArrayList<>();
        TagSyncRangerAccount.provision(k8s, records, directRanger(),
                contextId -> { located.add(contextId); return directRanger(); }, NS, RELEASE, null, null,
                INSTANCE, CLUSTER, null);

        TagSyncRangerAccount.Provisioned again = TagSyncRangerAccount.provision(k8s, records, directRanger(),
                contextId -> { located.add(contextId); return directRanger(); }, NS, RELEASE,
                ContextService.DEFAULT_CONTEXT_ID, null, INSTANCE, CLUSTER, null);

        String managed = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, CLUSTER, null);
        assertEquals(managed, again.userName());
        assertTrue(again.warnings().isEmpty(), String.valueOf(again.warnings()));
        assertTrue(located.isEmpty(), "nothing is disabled: " + located);
        assertEquals(1, ranger.users.get(managed).get("status").getAsInt());
        assertEquals(List.of("ROLE_SYS_ADMIN"), roles(ranger.users.get(managed)));
        assertEquals(new TagSyncRangerAccount.Recorded(managed, null, OWNER), records.find(NS, RELEASE));
    }

    @Test
    void aReleaseMovedBetweenTwoContextsOfTheSameRangerKeepsTheAccountItWasJustGiven() {
        TagSyncRangerAccount.provision(k8s, records, directRanger(), contextId -> directRanger(), NS, RELEASE,
                "cdp-a", null, INSTANCE, CLUSTER, null);

        TagSyncRangerAccount.Provisioned moved = TagSyncRangerAccount.provision(k8s, records, directRanger(),
                contextId -> directRanger(), NS, RELEASE, "cdp-b", null, INSTANCE, CLUSTER, null);

        String onA = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, CLUSTER, "cdp-a");
        String onB = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, CLUSTER, "cdp-b");
        assertEquals(onB, moved.userName());
        assertEquals(1, ranger.users.get(onB).get("status").getAsInt(), "the account the release now has");
        assertEquals(0, ranger.users.get(onA).get("status").getAsInt(), "the account it had on the other context");
        assertEquals(List.of(), records.toDisable(NS, RELEASE));
    }

    @Test
    void anEarlierAccountThatCannotBeDisabledOnADeployIsTriedAgainOnTheNext() {
        String oldAccount = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, CLUSTER, "old-context");
        TagSyncRangerAccount.Recorded old = new TagSyncRangerAccount.Recorded(oldAccount, "old-context", OWNER);
        records.put(NS, RELEASE, old);
        RecordingRanger unreachable = new RecordingRanger();
        unreachable.disableFailure = new IllegalStateException("Ambari request 7 did not complete");

        TagSyncRangerAccount.Provisioned first = provisionWithPrevious(unreachable);

        assertEquals(1, first.warnings().size(), String.valueOf(first.warnings()));
        assertTrue(first.warnings().get(0).contains(oldAccount) && first.warnings().get(0).contains("tries again"),
                first.warnings().get(0));
        assertEquals(List.of(old), records.toDisable(NS, RELEASE), "the account is still to be disabled");
        assertEquals(ACCOUNT, records.find(NS, RELEASE).userName());

        RecordingRanger reachable = new RecordingRanger();
        TagSyncRangerAccount.Provisioned second = provisionWithPrevious(reachable);

        assertTrue(second.warnings().isEmpty(), String.valueOf(second.warnings()));
        assertEquals(List.of(oldAccount + " of " + OWNER), reachable.disabled);
        assertEquals(List.of(), records.toDisable(NS, RELEASE));
    }

    @Test
    void aDeployStoppedBeforeItsSecretIsWrittenStillLeavesTheEarlierAccountToDisable() {
        String oldAccount = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, CLUSTER, "old-context");
        TagSyncRangerAccount.Recorded old = new TagSyncRangerAccount.Recorded(oldAccount, "old-context", OWNER);
        records.put(NS, RELEASE, old);
        doReturn(secret(oldAccount, "old-pw", Map.of())).when(k8s).getSecret(NS, SECRET);
        doThrow(new RuntimeException("secrets is forbidden")).when(k8s)
                .createOrUpdateOpaqueSecret(eq(NS), eq(SECRET), anyMap(), any());
        RecordingRanger previous = new RecordingRanger();

        assertThrows(IllegalStateException.class, () -> provisionWithPrevious(previous));

        assertEquals(ACCOUNT, records.find(NS, RELEASE).userName());
        assertEquals(List.of(old), records.toDisable(NS, RELEASE), "the record no longer names it, the list does");
        assertTrue(previous.disabled.isEmpty(), "the release still runs with it");

        doNothing().when(k8s).createOrUpdateOpaqueSecret(eq(NS), eq(SECRET), anyMap(), any());
        TagSyncRangerAccount.Provisioned next = provisionWithPrevious(previous);

        assertTrue(next.warnings().isEmpty(), "an account KDPS gave the release is no copied administrator: "
                + next.warnings());
        assertEquals(List.of(oldAccount + " of " + OWNER), previous.disabled);
        assertEquals(List.of(), records.toDisable(NS, RELEASE));
    }

    @Test
    void anUninstallAfterADeployStoppedBeforeItsSecretWasWrittenDisablesBothAccounts() {
        String oldAccount = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, CLUSTER, "old-context");
        records.put(NS, RELEASE, new TagSyncRangerAccount.Recorded(oldAccount, "old-context", OWNER));
        doThrow(new RuntimeException("secrets is forbidden")).when(k8s)
                .createOrUpdateOpaqueSecret(eq(NS), eq(SECRET), anyMap(), any());
        assertThrows(IllegalStateException.class, () -> provisionWithPrevious(new RecordingRanger()));
        RecordingRanger everywhere = new RecordingRanger();

        TagSyncRangerAccount.Revoked revoked = TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, null,
                contextId -> everywhere);

        assertNull(revoked.note());
        assertEquals(List.of(ACCOUNT + " of " + OWNER, oldAccount + " of " + OWNER), everywhere.disabled);
        assertNull(records.find(NS, RELEASE));
        assertEquals(List.of(), records.toDisable(NS, RELEASE));
    }

    @Test
    void aReleaseMovedBackToAContextWhoseAccountWasStillToDisableKeepsIt() {
        TagSyncRangerAccount.Recorded onContext = new TagSyncRangerAccount.Recorded(ACCOUNT, CONTEXT, OWNER);
        String otherAccount = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, CLUSTER, "other-context");
        records.put(NS, RELEASE, new TagSyncRangerAccount.Recorded(otherAccount, "other-context", OWNER));
        records.putToDisable(NS, RELEASE, List.of(onContext));
        RecordingRanger other = new RecordingRanger();

        TagSyncRangerAccount.Provisioned back = provisionWithPrevious(other);

        assertEquals(ACCOUNT, back.userName());
        assertEquals(List.of(otherAccount + " of " + OWNER), other.disabled);
        assertEquals(1, ranger.users.get(ACCOUNT).get("status").getAsInt());
        assertEquals(List.of(), records.toDisable(NS, RELEASE));
    }

    @Test
    void aDeployWithoutRangerWiringTriesAgainTheAccountsStillToDisable() {
        // The release runs with ACCOUNT; an account it had on another context could not be disabled, and the Secret
        // of a deploy that stopped before writing it still holds another one.
        records.put(NS, RELEASE, new TagSyncRangerAccount.Recorded(ACCOUNT, CONTEXT, OWNER));
        String oldAccount = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, CLUSTER, "old-context");
        String inSecret = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, CLUSTER, "secret-context");
        TagSyncRangerAccount.Recorded stillInSecret =
                new TagSyncRangerAccount.Recorded(inSecret, "secret-context", OWNER);
        records.putToDisable(NS, RELEASE, List.of(
                new TagSyncRangerAccount.Recorded(oldAccount, "old-context", OWNER), stillInSecret));
        doReturn(secret(inSecret, "pw", Map.of())).when(k8s).getSecret(NS, SECRET);
        RecordingRanger elsewhere = new RecordingRanger();
        List<String> located = new ArrayList<>();

        List<String> notes = TagSyncRangerAccount.kept(k8s, records,
                contextId -> { located.add(contextId); return elsewhere; }, NS, RELEASE);

        assertTrue(notes.isEmpty(), String.valueOf(notes));
        assertEquals(List.of("old-context"), located);
        assertEquals(List.of(oldAccount + " of " + OWNER), elsewhere.disabled);
        assertEquals(List.of(stillInSecret), records.toDisable(NS, RELEASE), "the release still runs with it");
        verify(k8s, never()).deleteSecret(anyString(), anyString());
    }

    @Test
    void aDeployWithoutRangerWiringSaysAgainWhatItStillCannotDisable() {
        records.put(NS, RELEASE, new TagSyncRangerAccount.Recorded(ACCOUNT, CONTEXT, OWNER));
        String oldAccount = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, CLUSTER, "old-context");
        TagSyncRangerAccount.Recorded old = new TagSyncRangerAccount.Recorded(oldAccount, "old-context", OWNER);
        records.putToDisable(NS, RELEASE, List.of(old));
        doReturn(secret(ACCOUNT, "pw", Map.of())).when(k8s).getSecret(NS, SECRET);

        List<String> notes = TagSyncRangerAccount.kept(k8s, records, contextId -> {
            throw new IllegalStateException("Ranger answered HTTP 503");
        }, NS, RELEASE);

        assertEquals(1, notes.size(), String.valueOf(notes));
        assertTrue(notes.get(0).contains(oldAccount) && notes.get(0).contains("tries again"), notes.get(0));
        assertEquals(List.of(old), records.toDisable(NS, RELEASE));
    }

    /** Provisions the release on {@link #CONTEXT}, with {@code previous} as the Ranger of any other context. */
    private TagSyncRangerAccount.Provisioned provisionWithPrevious(RecordingRanger previous) {
        return TagSyncRangerAccount.provision(k8s, records, directRanger(),
                contextId -> CONTEXT.equals(contextId) ? directRanger() : previous, NS, RELEASE, CONTEXT, null,
                INSTANCE, CLUSTER, null);
    }

    @Test
    void anAccountThatCannotBeDisabledOnUninstallStaysRecordedAndALaterUninstallDisablesIt() {
        provision(directRanger(), CONTEXT);
        doReturn(null).when(k8s).getSecret(NS, SECRET);

        String note = TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, null, contextId -> {
            throw new IllegalStateException("Ranger answered HTTP 503");
        }).note();

        assertNotNull(note);
        assertTrue(note.contains(ACCOUNT) && note.contains("tries again"), note);
        assertEquals(1, ranger.users.get(ACCOUNT).get("status").getAsInt());
        assertNull(records.find(NS, RELEASE), "the release no longer uses it");
        assertEquals(List.of(new TagSyncRangerAccount.Recorded(ACCOUNT, CONTEXT, OWNER)),
                records.toDisable(NS, RELEASE), "but it is still to be disabled");

        TagSyncRangerAccount.Revoked again = TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, null,
                contextId -> directRanger());

        assertNull(again.note());
        assertEquals(List.of("disabled the Ranger account '" + ACCOUNT + "'"), again.done());
        assertEquals(0, ranger.users.get(ACCOUNT).get("status").getAsInt());
        assertEquals(List.of(), records.toDisable(NS, RELEASE));
    }

    @Test
    void anAccountOnAPlatformContextKdpsNoLongerHasIsReportedOnceNotKept() {
        provision(directRanger(), CONTEXT);

        String note = TagSyncRangerAccount.revoke(k8s, records, NS, RELEASE, null, contextId -> {
            throw new TagSyncRangerAccount.ContextGone(contextId);
        }).note();

        assertTrue(note.contains(ACCOUNT) && note.contains("no longer exists") && note.contains("disable it in Ranger"),
                note);
        assertFalse(note.contains("tries again"), note);
        assertEquals(List.of(), records.toDisable(NS, RELEASE), "KDPS can never reach it");
    }

    /** Records kept in memory, which can be told to fail. */
    static final class MemoryRecords implements TagSyncRangerAccount.Records {
        final Map<String, TagSyncRangerAccount.Recorded> byRelease = new LinkedHashMap<>();
        final Map<String, List<TagSyncRangerAccount.Recorded>> toDisableByRelease = new LinkedHashMap<>();
        RuntimeException failure;

        @Override
        public TagSyncRangerAccount.Recorded find(String namespace, String release) {
            return byRelease.get(namespace + "/" + release);
        }

        @Override
        public void put(String namespace, String release, TagSyncRangerAccount.Recorded account) {
            if (failure != null) throw failure;
            byRelease.put(namespace + "/" + release, account);
        }

        @Override
        public void remove(String namespace, String release) {
            byRelease.remove(namespace + "/" + release);
        }

        @Override
        public List<TagSyncRangerAccount.Recorded> toDisable(String namespace, String release) {
            return toDisableByRelease.getOrDefault(namespace + "/" + release, List.of());
        }

        @Override
        public void putToDisable(String namespace, String release, List<TagSyncRangerAccount.Recorded> accounts) {
            if (accounts.isEmpty()) {
                toDisableByRelease.remove(namespace + "/" + release);
            } else {
                toDisableByRelease.put(namespace + "/" + release, List.copyOf(accounts));
            }
        }
    }

    /** A Ranger that records what it is asked, and fails when told to. */
    static final class RecordingRanger implements TagSyncRangerAccount.RangerUsers {
        final List<String> ensured = new ArrayList<>();
        /** "user of owner" of every account disabled. */
        final List<String> disabled = new ArrayList<>();
        RuntimeException ensureFailure;
        RuntimeException disableFailure;
        /** What {@link #disable} answers: false for a Ranger that has no such account. */
        boolean found = true;

        @Override
        public void ensure(String userName, String password, String owner) {
            if (ensureFailure != null) throw ensureFailure;
            ensured.add(userName);
        }

        @Override
        public boolean disable(String userName, String owner) {
            if (disableFailure != null) throw disableFailure;
            if (!found) return false;
            disabled.add(userName + " of " + owner);
            return true;
        }
    }

    /** The Ambari server's {@code ranger_plugin_repository} endpoint and the requests it starts. */
    static final class FakeAmbari implements URLStreamProvider {
        final List<JsonObject> requests = new ArrayList<>();
        String status = "COMPLETED";
        boolean found = true;
        private int nextRequest = 100;

        AmbariActionClient client() {
            ViewContext ctx = mock(ViewContext.class);
            doReturn(this).when(ctx).getURLStreamProvider();
            return new AmbariActionClient(ctx, "http://ambari:8080/api/v1", "c1", Map.of());
        }

        @Override
        public InputStream readFrom(String url, String method, String body, Map<String, String> headers) {
            if ("POST".equals(method) && url.endsWith("/clusters/c1/ranger_plugin_repository")) {
                requests.add(JsonParser.parseString(body).getAsJsonObject());
                return json("{\"Requests\":{\"id\":" + nextRequest++ + ",\"status\":\"Accepted\"}}");
            }
            Matcher tasks = Pattern.compile(".*/requests/(\\d+)/tasks(\\?.*)?$").matcher(url);
            if ("GET".equals(method) && tasks.matches()) {
                JsonObject task = new JsonObject();
                task.addProperty("id", 1);
                task.addProperty("status", status);
                if ("FAILED".equals(status)) {
                    task.addProperty("stderr", "Failed to disable Ranger user: Ranger answered HTTP 401");
                } else {
                    JsonObject out = new JsonObject();
                    out.addProperty("found", found);
                    task.add("structured_out", out);
                }
                return json("{\"items\":[{\"Tasks\":" + task + "}]}");
            }
            return json("{}");
        }

        @Override
        public InputStream readFrom(String url, String method, InputStream body, Map<String, String> headers) {
            if ("GET".equals(method) && url.matches(".*/requests/\\d+$")) {
                return json("{\"Requests\":{\"request_status\":\"" + status + "\"}}");
            }
            return json("{}");
        }

        @Override
        public InputStream readAs(String url, String method, String body, Map<String, String> headers, String user) {
            return readFrom(url, method, body, headers);
        }

        @Override
        public InputStream readAs(String url, String method, InputStream body, Map<String, String> headers, String user) {
            return readFrom(url, method, body, headers);
        }

        @Override
        public InputStream readAsCurrent(String url, String method, String body, Map<String, String> headers) {
            return readFrom(url, method, body, headers);
        }

        @Override
        public InputStream readAsCurrent(String url, String method, InputStream body, Map<String, String> headers) {
            return readFrom(url, method, body, headers);
        }

        private static InputStream json(String s) {
            return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
