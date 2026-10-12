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
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.ResolvedContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.om.FakeRangerAdmin;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A Trino deploy with "Sync Atlas tags to Trino" on points the chart's tag projector at a Ranger account of the
 * release's own ({@code tagSync.ranger.url}, {@code tagSync.ranger.existingSecret}), created on the platform context's
 * Ranger. The context's Ranger administrator, whose credentials earlier versions copied into the release's project,
 * stays in the view. A context with auto-configuration off gets no account from KDPS, and an account the operator
 * brings is used as it is. A deploy that gives the projector no account from KDPS takes back the one an earlier deploy
 * gave it, and whatever an earlier version left in its Secret.
 */
class CommandServiceTagSyncAccountTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "trino";
    /** The KDPS instance the tests deploy from. */
    private static final String INSTANCE = "3b1f0c9a-kdps";
    /** The release's account on platform context cdp-prod; the tests read no Kubernetes API server URL. */
    private static final String ACCOUNT = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, null, "cdp-prod");
    /** The release's account on the managed cluster. */
    private static final String MANAGED_ACCOUNT = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, null, null);
    private static final String OWNER = "KDPS Atlas tag sync account of Trino release analytics/trino (KDPS instance "
            + INSTANCE + ")";
    private static final String SECRET = "trino-tagsync-ranger";

    private KubernetesService k8s;
    private CommandService service;
    private FakeRangerAdmin ranger;
    private TagSyncRangerAccountTest.MemoryRecords records;
    /** The platform contexts the Ranger of a tag sync account was looked up for. */
    private final List<String> located = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getInstanceName()).thenReturn("tagsync-account-" + Long.toHexString(System.nanoTime()));
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir",
                Files.createTempDirectory("kdps-tagsync").toString()));
        DataStore ds = mock(DataStore.class);
        when(ds.findAll(eq(K8sReleaseEntity.class), any())).thenAnswer(inv -> new ArrayList<>());
        when(ctx.getDataStore()).thenReturn(ds);
        k8s = mock(KubernetesService.class);
        service = new CommandService(ctx, mock(HelmService.class), k8s);
        service.startupSweep.get(30, TimeUnit.SECONDS);
        ranger = new FakeRangerAdmin();
        ranger.addUser("admin", 0, "admin-pw", "ROLE_SYS_ADMIN");
        records = new TagSyncRangerAccountTest.MemoryRecords();
        service.tagSyncAccounts = records;
        service.tagSyncRangers = access -> contextId -> {
            located.add(contextId);
            return TagSyncRangerAccount.direct(ranger.url(), "admin", "admin-pw");
        };
        service.tagSyncInstance = () -> INSTANCE;
    }

    @AfterEach
    void tearDown() {
        ranger.close();
        service.shutdown();
    }

    private static HelmDeployRequest request() {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setReleaseName(RELEASE);
        request.setNamespace(NS);
        return request;
    }

    /**
     * The merged values of a release with tag sync on and Ranger access control, naming {@code existingSecret} and
     * {@code url}.
     */
    private static Map<String, Object> values(String existingSecret, String url) {
        return values(existingSecret, url, "ranger");
    }

    /** The merged values of a release with tag sync on and the given access control. */
    private static Map<String, Object> values(String existingSecret, String url, String accessControl) {
        Map<String, Object> rangerValues = new LinkedHashMap<>();
        rangerValues.put("existingSecret", existingSecret);
        rangerValues.put("url", url);
        Map<String, Object> tagSync = new LinkedHashMap<>();
        tagSync.put("enabled", true);
        tagSync.put("ranger", rangerValues);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tagSync", tagSync);
        values.put("accessControl", new LinkedHashMap<>(Map.of("type", accessControl)));
        return values;
    }

    /** The merged values of a release with tag sync off, still naming {@code existingSecret}. */
    private static Map<String, Object> tagSyncOff(String existingSecret) {
        Map<String, Object> values = values(existingSecret, "");
        Map<String, Object> tagSync = new LinkedHashMap<>();
        tagSync.put("enabled", false);
        tagSync.put("ranger", Map.of("existingSecret", existingSecret));
        values.put("tagSync", tagSync);
        return values;
    }

    /** An external platform context whose Ranger the view reaches with stored administrator credentials. */
    private ResolvedContext external(boolean autoConfigure) {
        ResolvedContext rc = new ResolvedContext();
        rc.setId("cdp-prod");
        rc.setName("CDP production");
        rc.setKind("EXTERNAL");
        rc.setRangerUrl(ranger.url());
        rc.setRangerAdminUsername("admin");
        rc.setRangerAdminPassword("admin-pw");
        rc.setAutoConfigureRemote(autoConfigure);
        return rc;
    }

    private static Map<String, Object> params() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("_platformContextId", "cdp-prod");
        return params;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> overrides(Map<String, Object> params) {
        Object ov = params.get("_ov");
        return ov == null ? Map.of() : (Map<String, String>) ov;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> writtenSecret() {
        ArgumentCaptor<Map<String, byte[]>> data = ArgumentCaptor.forClass(Map.class);
        verify(k8s).createOrUpdateOpaqueSecret(eq(NS), eq(SECRET), data.capture(), any());
        Map<String, String> text = new LinkedHashMap<>();
        data.getValue().forEach((k, v) -> text.put(k, new String(v, StandardCharsets.UTF_8)));
        return text;
    }

    @Test
    void anExternalContextGivesTheReleaseItsOwnAccountNotItsAdministrator() throws Exception {
        Map<String, Object> params = params();

        List<String> warnings = service.wireTagSyncRangerAccount(request(), params, values("", ""), external(true),
                ranger.url(), null, null);

        assertTrue(warnings.isEmpty(), String.valueOf(warnings));
        Map<String, String> secret = writtenSecret();
        assertEquals(ACCOUNT, secret.get("user"));
        assertNotEquals("admin-pw", secret.get("password"));
        assertEquals(ranger.url(), overrides(params).get("tagSync.ranger.url"));
        assertEquals(SECRET, overrides(params).get("tagSync.ranger.existingSecret"));
        JsonObject account = ranger.users.get(ACCOUNT);
        assertEquals(List.of("ROLE_SYS_ADMIN"), roles(account));
        assertEquals(secret.get("password"), account.get("password").getAsString());
        assertEquals(OWNER, account.get("description").getAsString());
        assertEquals(new TagSyncRangerAccount.Recorded(ACCOUNT, "cdp-prod", OWNER),
                records.find(NS, RELEASE).withoutGrant());
        assertNotNull(records.find(NS, RELEASE).grant(), "the record names the deploy that gave the account");
    }

    @Test
    void theAccountNamesTheKubernetesClusterOfTheRelease() throws Exception {
        io.fabric8.kubernetes.client.KubernetesClient client =
                mock(io.fabric8.kubernetes.client.KubernetesClient.class, RETURNS_DEEP_STUBS);
        when(client.getConfiguration().getMasterUrl()).thenReturn("https://API.ocp.example:6443/");
        doReturn(client).when(k8s).getClient();

        service.wireTagSyncRangerAccount(request(), params(), values("", ""), external(true), ranger.url(), null, null);

        String cluster = "https://api.ocp.example:6443";
        String account = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, cluster, "cdp-prod");
        assertEquals(account, writtenSecret().get("user"));
        assertEquals(TagSyncRangerAccount.owner(NS, RELEASE, INSTANCE, cluster),
                ranger.users.get(account).get("description").getAsString());
    }

    @Test
    void anUpgradeReplacesTheAdministratorAnEarlierVersionCopiedAndSaysToChangeItsPassword() throws Exception {
        doReturn(administratorSecret()).when(k8s).getSecret(NS, SECRET);

        // On an upgrade the deployed values already name KDPS's own Secret.
        List<String> warnings = service.wireTagSyncRangerAccount(request(), params(), values(SECRET, ranger.url()),
                external(true), ranger.url(), null, null);

        assertEquals(ACCOUNT, writtenSecret().get("user"));
        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).contains("'admin'") && warnings.get(0).contains("Change the password"), warnings.get(0));
    }

    @Test
    void autoConfigurationOffTakesBackWhatAnEarlierVersionLeftInTheProjectAndSaysSo() {
        doReturn(administratorSecret()).when(k8s).getSecret(NS, SECRET);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.wireTagSyncRangerAccount(request(), params(), values(SECRET, ranger.url()),
                        external(false), ranger.url(), null, null));

        assertTrue(refused.getMessage().contains("tagSync.ranger.existingSecret"), refused.getMessage());
        assertTrue(refused.getMessage().contains("deleted the Secret " + NS + "/" + SECRET), refused.getMessage());
        assertTrue(refused.getMessage().contains("'admin'") && refused.getMessage().contains("Change the password"),
                refused.getMessage());
        verify(k8s).deleteSecret(NS, SECRET);
        assertEquals(1, ranger.users.get("admin").get("status").getAsInt(), "the administrator is not disabled");
    }

    @Test
    void autoConfigurationOffCreatesNoAccountAndSaysHowToBringOne() {
        Map<String, Object> params = params();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.wireTagSyncRangerAccount(request(), params, values("", ""), external(false),
                        ranger.url(), null, null));

        assertTrue(refused.getMessage().contains("tagSync.ranger.existingSecret"), refused.getMessage());
        assertTrue(refused.getMessage().contains("CDP production"), refused.getMessage());
        assertTrue(refused.getMessage().contains("whose name is not '" + SECRET + "'"), refused.getMessage());
        assertEquals(0, ranger.writes());
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap(), any());
        assertTrue(overrides(params).isEmpty());
    }

    @Test
    void anAccountTheOperatorBringsIsUsedAsItIs() throws Exception {
        Map<String, Object> params = params();

        List<String> warnings = service.wireTagSyncRangerAccount(request(), params, values("team-tag-writer", ""),
                external(false), ranger.url(), null, null);

        assertTrue(warnings.isEmpty());
        assertTrue(ranger.requests.isEmpty(), "KDPS creates no account: " + ranger.requests);
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap(), any());
        assertEquals(Map.of("tagSync.ranger.url", ranger.url()), overrides(params),
                "only the missing Ranger URL is filled in");
    }

    @Test
    void anAccountTheOperatorBringsTakesBackTheOneKdpsGaveTheRelease() throws Exception {
        provisionedEarlier();
        Map<String, Object> params = params();

        List<String> warnings = service.wireTagSyncRangerAccount(request(), params, values("team-tag-writer", ""),
                external(true), ranger.url(), null, null);

        assertEquals(0, ranger.users.get(ACCOUNT).get("status").getAsInt(), "the account is disabled");
        verify(k8s).deleteSecret(NS, SECRET);
        assertNull(records.find(NS, RELEASE));
        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).contains("disabled the Ranger account '" + ACCOUNT + "'"), warnings.get(0));
        assertEquals(List.of("cdp-prod"), located, "on the Ranger it was created on");
        assertEquals(Map.of("tagSync.ranger.url", ranger.url()), overrides(params));
    }

    @Test
    void anAccountTheOperatorBringsRemovesACopiedAdministratorAndSaysToChangeItsPassword() throws Exception {
        doReturn(administratorSecret()).when(k8s).getSecret(NS, SECRET);

        List<String> warnings = service.wireTagSyncRangerAccount(request(), params(), values("team-tag-writer", ""),
                external(true), ranger.url(), null, null);

        verify(k8s).deleteSecret(NS, SECRET);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("'admin'") && w.contains("Change the password")),
                String.valueOf(warnings));
        assertEquals(1, ranger.users.get("admin").get("status").getAsInt());
        assertTrue(located.isEmpty(), "Ranger is not asked to touch the administrator");
    }

    @Test
    void turningTagSyncOffTakesBackTheAccountAnEarlierDeployGaveTheRelease() throws Exception {
        provisionedEarlier();

        List<String> warnings = service.applyTagSyncRangerAccount(request(), params(), tagSyncOff(SECRET),
                external(true), ranger.url(), null, null);

        assertEquals(0, ranger.users.get(ACCOUNT).get("status").getAsInt());
        assertEquals(List.of("ROLE_USER"), roles(ranger.users.get(ACCOUNT)));
        verify(k8s).deleteSecret(NS, SECRET);
        assertNull(records.find(NS, RELEASE));
        assertEquals(1, warnings.size(), String.valueOf(warnings));
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap(), any());
    }

    @Test
    void turningTagSyncOffRemovesACopiedAdministrator() throws Exception {
        doReturn(administratorSecret()).when(k8s).getSecret(NS, SECRET);

        List<String> warnings = service.applyTagSyncRangerAccount(request(), params(), tagSyncOff(SECRET),
                external(true), ranger.url(), null, null);

        verify(k8s).deleteSecret(NS, SECRET);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Change the password")), String.valueOf(warnings));
    }

    @Test
    void aReleaseThatNeverSyncedTagsHasNothingTakenBackWhenTagSyncIsOff() throws Exception {
        assertTrue(service.applyTagSyncRangerAccount(request(), params(), tagSyncOff(""), external(true), ranger.url(),
                null, null).isEmpty());

        verify(k8s, never()).deleteSecret(anyString(), anyString());
        assertTrue(located.isEmpty());
    }

    @Test
    void anOperatorAccountWithItsOwnUrlIsLeftEntirelyAlone() throws Exception {
        Map<String, Object> params = params();

        service.wireTagSyncRangerAccount(request(), params, values("team-tag-writer", "https://ranger.internal:6182"),
                external(true), ranger.url(), null, null);

        assertTrue(overrides(params).isEmpty());
        assertTrue(ranger.requests.isEmpty());
    }

    @Test
    void aContextWithoutRangerIsRefused() {
        ResolvedContext rc = new ResolvedContext();
        rc.setName("no-ranger");
        rc.setKind("EXTERNAL");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.wireTagSyncRangerAccount(request(), params(), values("", ""), rc, null, null, null));

        assertTrue(refused.getMessage().contains("no-ranger"), refused.getMessage());
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap(), any());
    }

    @Test
    void theManagedClustersAccountIsCreatedThroughTheAmbariServer() throws Exception {
        TagSyncRangerAccountTest.FakeAmbari ambari = new TagSyncRangerAccountTest.FakeAmbari();
        ResolvedContext rc = new ResolvedContext();
        rc.setKind("MANAGED");
        rc.setRangerManaged(true);
        rc.setRangerUrl("https://ranger.cluster.example:6182");
        Map<String, Object> params = new LinkedHashMap<>();

        List<String> warnings = service.wireTagSyncRangerAccount(request(), params, values("", ""), rc, null,
                ambari.client(), "c1");

        assertTrue(warnings.isEmpty());
        JsonObject upsert = ambari.requests.get(0).getAsJsonObject("RangerPlugin");
        assertEquals(MANAGED_ACCOUNT, upsert.get("pluginUserName").getAsString());
        assertEquals("ROLE_SYS_ADMIN", upsert.get("userRoles").getAsString());
        Map<String, String> secret = writtenSecret();
        assertEquals(MANAGED_ACCOUNT, secret.get("user"));
        assertEquals(upsert.get("pluginUserPassword").getAsString(), secret.get("password"));
        assertEquals("https://ranger.cluster.example:6182", overrides(params).get("tagSync.ranger.url"));
        assertEquals(SECRET, overrides(params).get("tagSync.ranger.existingSecret"));
    }

    @Test
    void aManagedClusterThatDidNotCreateTheAccountStopsTheDeploy() {
        TagSyncRangerAccountTest.FakeAmbari ambari = new TagSyncRangerAccountTest.FakeAmbari();
        ambari.status = "FAILED";
        ResolvedContext rc = new ResolvedContext();
        rc.setKind("MANAGED");
        rc.setRangerManaged(true);
        rc.setRangerUrl("https://ranger.cluster.example:6182");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.wireTagSyncRangerAccount(request(), new LinkedHashMap<>(), values("", ""), rc, null,
                        ambari.client(), "c1"));

        assertTrue(refused.getMessage().contains(MANAGED_ACCOUNT), refused.getMessage());
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap(), any());
    }

    @Test
    void uninstallingAReleaseThatNeverSyncedTagsTouchesNothing() {
        assertNull(service.revokeTagSyncRangerAccountForRelease(NS, RELEASE, null, null));

        verify(k8s, never()).deleteSecret(anyString(), anyString());
        assertFalse(ranger.writes() > 0);
        assertTrue(located.isEmpty());
    }

    @Test
    void uninstallDisablesTheRecordedAccountEvenWhenItsSecretIsGone() {
        provisionedEarlier();
        doReturn(null).when(k8s).getSecret(NS, SECRET);

        assertNull(service.revokeTagSyncRangerAccountForRelease(NS, RELEASE, "release-record-context", null));

        assertEquals(List.of("cdp-prod"), located);
        assertEquals(0, ranger.users.get(ACCOUNT).get("status").getAsInt());
    }

    @Test
    void aPlatformContextKdpsNoLongerHasIsReportedNotReplacedByTheManagedCluster() {
        service.tagSyncRangers = service::tagSyncRangersOf;
        records.put(NS, RELEASE, new TagSyncRangerAccount.Recorded(ACCOUNT, "cdp-gone", OWNER));

        String note = service.revokeTagSyncRangerAccountForRelease(NS, RELEASE, "cdp-gone", null);

        assertNotNull(note);
        assertTrue(note.contains("'cdp-gone' no longer exists in KDPS"), note);
        assertTrue(note.contains(ACCOUNT) && note.contains("disable it in Ranger"), note);
        assertEquals(List.of(), records.toDisable(NS, RELEASE), "KDPS can never reach it: it is reported once");
    }

    @Test
    void aFirstInstallRefusedAfterItsAccountWasCreatedTakesTheAccountBack() {
        ranger.addUser(ACCOUNT, 0, "deploy-pw", "ROLE_SYS_ADMIN").addProperty("description", OWNER);
        doReturn(ownSecret()).when(k8s).getSecret(NS, SECRET);
        // The plan gives the release its account, then is refused.
        when(k8s.getConfigurationService()).thenAnswer(inv -> {
            records.put(NS, RELEASE, new TagSyncRangerAccount.Recorded(ACCOUNT, "cdp-prod", OWNER));
            throw new IllegalStateException("the cache directory is full");
        });

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> plan());

        assertEquals("the cache directory is full", refused.getMessage());
        assertEquals(0, ranger.users.get(ACCOUNT).get("status").getAsInt());
        verify(k8s).deleteSecret(NS, SECRET);
        assertNull(records.find(NS, RELEASE));
    }

    @Test
    void aRedeployThatIsRefusedKeepsTheAccountTheReleaseRunsWith() {
        provisionedEarlier();
        when(k8s.getConfigurationService()).thenThrow(new IllegalStateException("the cache directory is full"));

        assertThrows(IllegalStateException.class, () -> plan());

        assertEquals(1, ranger.users.get(ACCOUNT).get("status").getAsInt());
        verify(k8s, never()).deleteSecret(anyString(), anyString());
        assertNotNull(records.find(NS, RELEASE));
    }

    @Test
    void aResyncKeepsTheAccountTagSyncStillUsesAndItsSecret() {
        // Resync / restart, Refresh security profile and Worker autoscaling send the deployed values and no Ranger
        // wiring: tag sync is still on and reads KDPS's Secret.
        provisionedEarlier();

        List<String> warnings = service.keepOrTakeBackTagSyncRangerAccount(request(), params(),
                values(SECRET, ranger.url()));

        assertTrue(warnings.isEmpty(), String.valueOf(warnings));
        JsonObject account = ranger.users.get(ACCOUNT);
        assertEquals(1, account.get("status").getAsInt());
        assertEquals(List.of("ROLE_SYS_ADMIN"), roles(account));
        assertEquals("deploy-pw", account.get("password").getAsString(), "the Secret still holds its password");
        verify(k8s, never()).deleteSecret(anyString(), anyString());
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap(), any());
        assertNotNull(records.find(NS, RELEASE));
        assertTrue(located.isEmpty(), "Ranger is not asked anything");
    }

    @Test
    void aResyncTriesAgainAnAccountAnEarlierDeployCouldNotDisable() {
        provisionedEarlier();
        String oldAccount = TagSyncRangerAccount.userName(RELEASE, NS, INSTANCE, null, "cdp-old");
        ranger.addUser(oldAccount, 0, "old-pw", "ROLE_SYS_ADMIN").addProperty("description", OWNER);
        records.putToDisable(NS, RELEASE, List.of(new TagSyncRangerAccount.Recorded(oldAccount, "cdp-old", OWNER)));

        List<String> warnings = service.keepOrTakeBackTagSyncRangerAccount(request(), params(),
                values(SECRET, ranger.url()));

        assertTrue(warnings.isEmpty(), String.valueOf(warnings));
        assertEquals(List.of("cdp-old"), located);
        assertEquals(0, ranger.users.get(oldAccount).get("status").getAsInt());
        assertEquals(1, ranger.users.get(ACCOUNT).get("status").getAsInt(), "the account the release runs with");
        assertEquals(List.of(), records.toDisable(NS, RELEASE));
        verify(k8s, never()).deleteSecret(anyString(), anyString());
    }

    @Test
    void aResyncOfAReleaseAnEarlierVersionDeployedLeavesItsSecretAndSaysHowToReplaceIt() {
        doReturn(administratorSecret()).when(k8s).getSecret(NS, SECRET);

        List<String> warnings = service.keepOrTakeBackTagSyncRangerAccount(request(), params(),
                values(SECRET, ranger.url()));

        verify(k8s, never()).deleteSecret(anyString(), anyString());
        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).contains("'admin'") && warnings.get(0).contains("Upgrade / Config"), warnings.get(0));
        assertFalse(warnings.get(0).contains("admin-pw"), warnings.get(0));
    }

    @Test
    void aDeployWithoutRangerWiringTakesTheAccountBackOnceTagSyncIsOff() {
        provisionedEarlier();

        List<String> warnings = service.keepOrTakeBackTagSyncRangerAccount(request(), params(), tagSyncOff(SECRET));

        assertEquals(0, ranger.users.get(ACCOUNT).get("status").getAsInt());
        verify(k8s).deleteSecret(NS, SECRET);
        assertNull(records.find(NS, RELEASE));
        assertEquals(1, warnings.size(), String.valueOf(warnings));
    }

    @Test
    void aDeployWithoutRangerWiringTakesTheAccountBackWhenTheValuesNameAnotherSecret() {
        provisionedEarlier();

        service.keepOrTakeBackTagSyncRangerAccount(request(), params(), values("team-tag-writer", ranger.url()));

        assertEquals(0, ranger.users.get(ACCOUNT).get("status").getAsInt());
        verify(k8s).deleteSecret(NS, SECRET);
    }

    @Test
    void aReleaseWithoutRangerAccessControlGetsNoAccountEvenWithTagSyncOn() throws Exception {
        // The wizard turns tag sync on for a context with Atlas tags, and only hides the toggle when Ranger is not
        // chosen: the chart then runs no projector.
        Map<String, Object> params = params();

        List<String> warnings = service.applyTagSyncRangerAccount(request(), params, values("", "", "properties"),
                external(true), ranger.url(), null, null);

        assertTrue(warnings.isEmpty(), String.valueOf(warnings));
        assertEquals(0, ranger.writes());
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap(), any());
        assertTrue(overrides(params).isEmpty());
        assertNull(records.find(NS, RELEASE));
    }

    @Test
    void turningRangerAccessControlOffTakesBackTheAccount() throws Exception {
        provisionedEarlier();

        List<String> warnings = service.applyTagSyncRangerAccount(request(), params(), values(SECRET, "", "properties"),
                external(true), ranger.url(), null, null);

        assertEquals(0, ranger.users.get(ACCOUNT).get("status").getAsInt());
        verify(k8s).deleteSecret(NS, SECRET);
        assertNull(records.find(NS, RELEASE));
        assertEquals(1, warnings.size(), String.valueOf(warnings));
    }

    @Test
    void aDeployWithoutRangerWiringTakesTheAccountBackOnceRangerAccessControlIsOff() {
        provisionedEarlier();

        service.keepOrTakeBackTagSyncRangerAccount(request(), params(), values(SECRET, ranger.url(), "properties"));

        assertEquals(0, ranger.users.get(ACCOUNT).get("status").getAsInt());
        verify(k8s).deleteSecret(NS, SECRET);
    }

    @Test
    void aDeployNamingADeletedContextRecordsTheAccountOnTheManagedClusterItWasCreatedOn() throws Exception {
        // Reapply / Upgrade of a release whose platform context was deleted: the deploy resolves the managed cluster.
        TagSyncRangerAccountTest.FakeAmbari ambari = new TagSyncRangerAccountTest.FakeAmbari();
        ResolvedContext managed = new ResolvedContext();
        managed.setId(ContextService.DEFAULT_CONTEXT_ID);
        managed.setKind("MANAGED");
        managed.setRangerManaged(true);
        managed.setRangerUrl("https://ranger.cluster.example:6182");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("_platformContextId", "cdp-gone");

        service.wireTagSyncRangerAccount(request(), params, values("", ""), managed, null, ambari.client(), "c1");

        assertEquals(MANAGED_ACCOUNT, ambari.requests.get(0).getAsJsonObject("RangerPlugin")
                .get("pluginUserName").getAsString());
        assertEquals(new TagSyncRangerAccount.Recorded(MANAGED_ACCOUNT, null, OWNER),
                records.find(NS, RELEASE).withoutGrant());

        // The uninstall, whose release record still names the deleted context, disables it where it is.
        service.tagSyncRangers = access -> contextId -> {
            located.add(contextId);
            return TagSyncRangerAccount.viaAmbari(ambari.client(), 90);
        };
        assertNull(service.revokeTagSyncRangerAccountForRelease(NS, RELEASE, "cdp-gone", null));
        assertEquals(java.util.Collections.singletonList(null), located, "the managed cluster's Ranger");
        assertEquals(MANAGED_ACCOUNT, ambari.requests.get(1).getAsJsonObject("RangerPlugin")
                .get("pluginUserName").getAsString(), "the account is disabled through the Ambari server");
    }

    @Test
    void aSecretTheOperatorCreatedUnderTheNameKdpsUsesIsUsedNeitherReplacedNorDeleted() throws Exception {
        // The operator followed the refusal of a context with auto-configuration off, with the name the docs show.
        doReturn(operatorSecret("team-tag-writer", "team-pw")).when(k8s).getSecret(NS, SECRET);
        Map<String, Object> params = params();

        List<String> warnings = service.wireTagSyncRangerAccount(request(), params, values(SECRET, ""),
                external(false), ranger.url(), null, null);

        assertTrue(warnings.isEmpty(), String.valueOf(warnings));
        assertEquals(0, ranger.writes());
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap(), any());
        verify(k8s, never()).deleteSecret(anyString(), anyString());
        assertEquals(Map.of("tagSync.ranger.url", ranger.url()), overrides(params));
    }

    @Test
    void aSecretTheOperatorCreatedUnderTheNameKdpsUsesIsNotOverwrittenWhereKdpsCouldCreateAnAccount()
            throws Exception {
        doReturn(operatorSecret("team-tag-writer", "team-pw")).when(k8s).getSecret(NS, SECRET);
        Map<String, Object> params = params();

        service.wireTagSyncRangerAccount(request(), params, values("", ""), external(true), ranger.url(), null, null);

        assertEquals(0, ranger.writes());
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap(), any());
        assertEquals(SECRET, overrides(params).get("tagSync.ranger.existingSecret"));
        assertNull(service.revokeTagSyncRangerAccountForRelease(NS, RELEASE, "cdp-prod", null),
                "an uninstall gives no advice about it either");
        verify(k8s, never()).deleteSecret(anyString(), anyString());
    }

    @Test
    void aDeployGivingTheReleaseItsFirstAccountMarksItForTakingBackShouldTheReleaseNotBeInstalled() throws Exception {
        Map<String, Object> params = params();

        service.wireTagSyncRangerAccount(request(), params, values("", ""), external(true), ranger.url(), null, null);

        assertEquals("cdp-prod", params.get(CommandService.TAG_SYNC_NEW_ACCOUNT_PARAM));
        assertEquals(records.find(NS, RELEASE).grant(), params.get(CommandService.TAG_SYNC_GRANT_PARAM),
                "the deploy is the one the record names");
    }

    @Test
    void aRedeployOfAReleaseThatHasItsAccountDoesNotMarkIt() throws Exception {
        provisionedEarlier();
        Map<String, Object> params = params();

        service.wireTagSyncRangerAccount(request(), params, values(SECRET, ""), external(true), ranger.url(), null,
                null);

        assertFalse(params.containsKey(CommandService.TAG_SYNC_NEW_ACCOUNT_PARAM));
        assertFalse(params.containsKey(CommandService.TAG_SYNC_GRANT_PARAM));
    }

    /** The release's account as an earlier deploy created and recorded it, on platform context cdp-prod. */
    private void provisionedEarlier() {
        ranger.addUser(ACCOUNT, 0, "deploy-pw", "ROLE_SYS_ADMIN").addProperty("description", OWNER);
        records.put(NS, RELEASE, new TagSyncRangerAccount.Recorded(ACCOUNT, "cdp-prod", OWNER));
        doReturn(ownSecret()).when(k8s).getSecret(NS, SECRET);
    }

    private static Secret ownSecret() {
        return secretHolding(ACCOUNT, "deploy-pw");
    }

    /** The Secret an earlier KDPS version wrote on an external context: the context's own Ranger administrator. */
    private static Secret administratorSecret() {
        return secretHolding("admin", "admin-pw");
    }

    /** A Secret KDPS wrote: it carries the annotation every Secret KDPS writes has, earlier versions' too. */
    private static Secret secretHolding(String user, String password) {
        Secret secret = operatorSecret(user, password);
        secret.getMetadata().setAnnotations(Map.of("managed-by", "ambari-k8s-view"));
        return secret;
    }

    /** A Secret named like KDPS's own that the operator created. */
    private static Secret operatorSecret(String user, String password) {
        Map<String, String> data = Map.of(
                "user", Base64.getEncoder().encodeToString(user.getBytes(StandardCharsets.UTF_8)),
                "password", Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
        return new SecretBuilder().withNewMetadata().withName(SECRET).withNamespace(NS).endMetadata()
                .withData(data).build();
    }

    /** Plans a deploy of the release with values to write, the first thing a plan does. */
    private String plan() {
        HelmDeployRequest request = request();
        request.setValues(new LinkedHashMap<>(Map.of("replicaCount", 1)));
        return service.planReleaseDeploy(request, "charts", "0.0.1", null, null, null, null, false);
    }
}
