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

import com.google.gson.Gson;
import com.marcnuri.helm.Release;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.model.ResolvedContext;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.service.helm.HelmOperationRegistry;
import org.apache.ambari.view.k8s.service.om.FakeRangerAdmin;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.utils.TlsPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A deploy that gives the Atlas tag projector of a release its first Ranger account, then fails or is canceled before
 * the release is installed, leaves no release on the Releases page to uninstall: the account, a Ranger administrator,
 * is taken back when the deploy ends, and given again if the deploy is resumed. A release Helm has, or that a GitOps
 * commit recorded, keeps its account for its uninstall.
 */
class CommandServiceTagSyncUninstalledReleaseTest {

    private static final String NS = "analytics";
    private static final String INSTANCE = "5c2d7e10-kdps";
    private static final String YAML_ERROR = "INSTALL FAILED: YAML parse error on trino/templates/coordinator.yaml";

    private final Gson gson = new Gson();
    private InMemoryDataStore dataStore;
    private HelmClient helm;
    private KubernetesService k8s;
    private ViewContext ctx;
    private CommandService service;
    private FakeRangerAdmin ranger;
    private TagSyncRangerAccountTest.MemoryRecords records;
    private String rel;
    private String account;

    /** Steps run with slf4j's MDC, which needs the same loading work-around as in {@link CommandServiceHelmStepTest}. */
    @BeforeAll
    static void loadMdc() {
        String javaVersion = System.getProperty("java.version");
        System.setProperty("java.version", "1.8");
        try {
            org.slf4j.MDC.getMDCAdapter();
        } finally {
            System.setProperty("java.version", javaVersion);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Path workDir = Files.createTempDirectory("kdps-tagsync-uninstalled");
        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getInstanceName()).thenReturn("tagsync-uninstalled-" + Long.toHexString(System.nanoTime()));
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", workDir.toString()));
        dataStore = new InMemoryDataStore();
        when(ctx.getDataStore()).thenReturn(dataStore);
        helm = mock(HelmClient.class);
        k8s = mock(KubernetesService.class, RETURNS_DEEP_STUBS);
        when(k8s.getConfigurationService().getKubeconfigContents()).thenReturn("KC");
        service = new CommandService(ctx, new HelmService(ctx, helm, () -> mock(NamespaceScope.class)), k8s);
        service.startupSweep.get(30, TimeUnit.SECONDS);

        ranger = new FakeRangerAdmin();
        ranger.addUser("admin", 0, "admin-pw", "ROLE_SYS_ADMIN");
        records = new TagSyncRangerAccountTest.MemoryRecords();
        service.tagSyncAccounts = records;
        service.tagSyncRangers = access -> contextId -> TagSyncRangerAccount.direct(TlsPolicy.JVM_DEFAULT, ranger.url(), "admin", "admin-pw");
        service.tagSyncInstance = () -> INSTANCE;

        // A release of its own per test: the in-flight Helm registry is shared by the whole JVM.
        rel = "trino-" + Long.toHexString(System.nanoTime());
        account = TagSyncRangerAccount.userName(rel, NS, INSTANCE, null, "cdp-prod");
        HelmRepoEntity repo = new HelmRepoEntity();
        repo.setId("charts");
        repo.setName("charts");
        repo.setType("OCI");
        repo.setUrl("registry.example.com/charts");
        repo.setAuthMode("anonymous");
        dataStore.store(repo);
    }

    @AfterEach
    void tearDown() {
        ranger.close();
        service.shutdown();
    }

    /** The account the plan gave the release, and the Secret it wrote, as {@code wireTagSyncRangerAccount} does. */
    private void accountGivenByThePlan() {
        accountGivenByThePlanOf(grant());
    }

    /** The account the plan of the deploy {@code grant} gave the release, and the Secret it wrote. */
    private void accountGivenByThePlanOf(String grant) {
        doReturn(null).when(k8s).getSecret(NS, TagSyncRangerAccount.secretName(rel));
        TagSyncRangerAccount.provision(k8s, records, TagSyncRangerAccount.direct(TlsPolicy.JVM_DEFAULT, ranger.url(), "admin", "admin-pw"),
                contextId -> TagSyncRangerAccount.direct(TlsPolicy.JVM_DEFAULT, ranger.url(), "admin", "admin-pw"), NS, rel, "cdp-prod",
                null, INSTANCE, null, grant);
        doReturn(kdpsSecret()).when(k8s).getSecret(NS, TagSyncRangerAccount.secretName(rel));
    }

    /** How the record of the account names the deploy {@link #deployOperation} stores. */
    private String grant() {
        return "grant-" + rel;
    }

    private Secret kdpsSecret() {
        Map<String, String> data = Map.of(
                "user", Base64.getEncoder().encodeToString(account.getBytes(StandardCharsets.UTF_8)),
                "password", Base64.getEncoder().encodeToString("pw".getBytes(StandardCharsets.UTF_8)));
        return new SecretBuilder().withNewMetadata().withName(TagSyncRangerAccount.secretName(rel)).withNamespace(NS)
                .withAnnotations(Map.of("managed-by", "ambari-k8s-view")).endMetadata().withData(data).build();
    }

    /** Helm has the release, in whatever state, or has nothing in the namespace. */
    private void helmHasTheRelease(boolean has) {
        Release installed = mock(Release.class);
        when(installed.getName()).thenReturn(rel);
        when(helm.list(NS, "KC", false)).thenReturn(has ? List.of(installed) : List.of());
    }

    /**
     * Stores a deploy made of one Helm step, as the plan leaves it once the projector was given its account.
     *
     * @param newAccount whether the plan gave the release its first account
     */
    private String deployOperation(boolean newAccount) {
        return deployOperation(newAccount, CommandState.RUNNING);
    }

    /** {@link #deployOperation(boolean)}, its root left in {@code state}. */
    private String deployOperation(boolean newAccount, CommandState state) {
        String id = "deploy-" + rel;
        Map<String, Object> rootParams = new LinkedHashMap<>();
        rootParams.put("chart", "trino");
        rootParams.put("releaseName", rel);
        rootParams.put("namespace", NS);
        rootParams.put("version", "1.43.11");
        rootParams.put("repoId", "charts");
        rootParams.put("_platformContextId", "cdp-prod");
        if (newAccount) {
            rootParams.put(CommandService.TAG_SYNC_NEW_ACCOUNT_PARAM, "cdp-prod");
            rootParams.put(CommandService.TAG_SYNC_GRANT_PARAM, grant());
        }

        Map<String, Object> stepParams = new LinkedHashMap<>(rootParams);
        stepParams.put("values", Map.of("replicaCount", 1));

        dataStore.store(command(id, CommandType.K8S_MANAGER_RELEASE_DEPLOY, "Deploy release " + rel, rootParams,
                List.of(id + "-step")));
        dataStore.store(status(id, state));
        dataStore.store(command(id + "-step", CommandType.HELM_DEPLOY, "Helm: install chart trino", stepParams, null));
        dataStore.store(status(id + "-step", CommandState.PENDING));
        return id;
    }

    private CommandEntity command(String id, CommandType type, String title, Map<String, Object> params,
                                  List<String> children) {
        CommandEntity c = new CommandEntity();
        c.setId(id);
        c.setType(type.name());
        c.setTitle(title);
        c.setCommandStatusId(id + "-status");
        c.setParamsJson(gson.toJson(params));
        if (children != null) c.setChildListJson(gson.toJson(children));
        return c;
    }

    private static CommandStatusEntity status(String commandId, CommandState state) {
        CommandStatusEntity s = new CommandStatusEntity();
        s.setId(commandId + "-status");
        s.setState(state.name());
        s.setAttempt(0);
        return s;
    }

    private CommandStatusEntity statusOf(String commandId) {
        return dataStore.find(CommandStatusEntity.class, commandId + "-status");
    }

    /** The Helm install of a release Helm does not have yet. */
    private Release installCall() {
        return helm.install(anyString(), any(), anyBoolean(), eq(rel), eq(NS), any(Path.class), eq("KC"),
                ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyBoolean());
    }

    /** The Helm upgrade of a release Helm already has, in whatever state. */
    private Release upgradeCall() {
        return helm.upgrade(anyString(), any(), anyBoolean(), eq(rel), eq(NS), any(Path.class), eq("KC"),
                ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    private int status() {
        return ranger.users.get(account).get("status").getAsInt();
    }

    /** The operation's log once it holds {@code expected}; the log is written in the background. */
    private String awaitLog(String id, String expected) throws InterruptedException {
        String content = "";
        for (int i = 0; i < 200; i++) {
            content = CommandLogService.get(ctx).read(id, 0, 1 << 16).content;
            if (content.contains(expected)) return content;
            Thread.sleep(25);
        }
        fail("'" + expected + "' never reached the log of " + id + ": " + content);
        return content;
    }

    @Test
    void aFirstInstallWhoseHelmStepFailsTakesTheAccountBack() throws Exception {
        accountGivenByThePlan();
        helmHasTheRelease(false);
        when(installCall()).thenThrow(new IllegalStateException(YAML_ERROR));
        String id = deployOperation(true);

        service.runNextStep(id);

        assertEquals(CommandState.FAILED.name(), statusOf(id).getState(), statusOf(id).getMessage());
        assertEquals(0, status(), "the account is disabled");
        verify(k8s).deleteSecret(NS, TagSyncRangerAccount.secretName(rel));
        assertNull(records.find(NS, rel));
        String log = awaitLog(id, "did not install release " + NS + "/" + rel);
        assertTrue(log.contains("disabled the Ranger account '" + account + "'"), log);
    }

    @Test
    void aFailedDeployOfAReleaseHelmHasKeepsTheAccountForItsUninstall() {
        accountGivenByThePlan();
        // A failed install leaves a failed release in Helm, which the Releases page lists and can uninstall.
        helmHasTheRelease(true);
        when(upgradeCall()).thenThrow(new IllegalStateException(YAML_ERROR));
        String id = deployOperation(true);

        service.runNextStep(id);

        assertEquals(CommandState.FAILED.name(), statusOf(id).getState());
        assertEquals(1, status());
        verify(k8s, never()).deleteSecret(anyString(), anyString());
        assertNotNull(records.find(NS, rel));
    }

    @Test
    void aFailedRedeployOfAReleaseThatAlreadyHadItsAccountKeepsIt() {
        accountGivenByThePlan();
        helmHasTheRelease(false);
        when(installCall()).thenThrow(new IllegalStateException(YAML_ERROR));
        String id = deployOperation(false);

        service.runNextStep(id);

        assertEquals(CommandState.FAILED.name(), statusOf(id).getState());
        assertEquals(1, status());
        verify(k8s, never()).deleteSecret(anyString(), anyString());
        assertNotNull(records.find(NS, rel));
    }

    @Test
    void resumingTheFailedInstallGivesTheAccountBack() throws Exception {
        accountGivenByThePlan();
        helmHasTheRelease(false);
        when(installCall()).thenThrow(new IllegalStateException(YAML_ERROR)).thenReturn(mock(Release.class));
        String id = deployOperation(true);
        service.runNextStep(id);
        assertEquals(0, status());
        String passwordBefore = ranger.users.get(account).get("password").getAsString();
        doReturn(null).when(k8s).getSecret(NS, TagSyncRangerAccount.secretName(rel));

        service.resume(id, null);

        assertEquals(1, status(), "enabled again before the steps run");
        assertNotEquals(passwordBefore, ranger.users.get(account).get("password").getAsString());
        assertEquals(new TagSyncRangerAccount.Recorded(account, "cdp-prod",
                TagSyncRangerAccount.owner(NS, rel, INSTANCE, null), grant()), records.find(NS, rel),
                "given again to the resumed deploy, which takes it back should it fail again");
        verify(k8s, times(2)).createOrUpdateOpaqueSecret(eq(NS), eq(TagSyncRangerAccount.secretName(rel)), anyMap(),
                any());
        awaitState(id, CommandState.SUCCEEDED);
        assertEquals(1, status());
    }

    @Test
    void aFirstInstallCanceledBeforeHelmRanTakesTheAccountBack() throws Exception {
        accountGivenByThePlan();
        helmHasTheRelease(false);
        String id = deployOperation(true);

        service.cancel(id);

        for (int i = 0; i < 200 && status() != 0; i++) {
            Thread.sleep(100);
        }
        assertEquals(CommandState.CANCELED.name(), statusOf(id).getState());
        assertEquals(0, status(), "the account is disabled once the canceled deploy is known not to install anything");
        // Deleted right after the account is disabled, on the thread taking it back.
        verify(k8s, timeout(5000)).deleteSecret(NS, TagSyncRangerAccount.secretName(rel));
    }

    @Test
    void aGitOpsReleaseItsCommitRecordedKeepsTheAccountAndOneNeverCommittedDoesNot() {
        accountGivenByThePlan();
        helmHasTheRelease(false);
        String id = deployOperation(true);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("namespace", NS);
        params.put("releaseName", rel);
        params.put(CommandService.TAG_SYNC_NEW_ACCOUNT_PARAM, "cdp-prod");
        params.put(CommandService.TAG_SYNC_GRANT_PARAM, grant());
        params.put(CommandService.GITOPS_RELEASE_PARAM, Map.of());

        // Committed to Git: Flux installs it, and it is uninstalled from the Releases page.
        K8sReleaseEntity committed = new K8sReleaseEntity();
        committed.setId(K8sReleaseEntity.idOf(NS, rel));
        committed.setNamespace(NS);
        committed.setReleaseName(rel);
        dataStore.store(committed);
        assertTrue(service.takeBackTagSyncAccountOfUninstalledRelease(id, params, false));
        assertEquals(1, status());

        // The commit failed (push rejected): nothing in Git, nothing running.
        dataStore.remove(committed);
        assertTrue(service.takeBackTagSyncAccountOfUninstalledRelease(id, params, false));
        assertEquals(0, status());
    }

    @Test
    void aFirstInstallInterruptedByARestartHasItsAccountTakenBackOnceTheViewStartsAgain() throws Exception {
        accountGivenByThePlan();
        helmHasTheRelease(false);
        // The view stopped while the deploy ran its steps before Helm (keytab, Ranger repository, OIDC client).
        String id = deployOperation(true, CommandState.RUNNING);

        CommandService restarted = new CommandService(ctx, new HelmService(ctx, helm, () -> mock(NamespaceScope.class)),
                k8s);
        try {
            restarted.tagSyncAccounts = records;
            restarted.tagSyncRangers = service.tagSyncRangers;
            restarted.tagSyncInstance = () -> INSTANCE;
            restarted.startupSweep.get(30, TimeUnit.SECONDS);

            assertEquals(CommandState.FAILED.name(), statusOf(id).getState());
            for (int i = 0; i < 200 && status() != 0; i++) {
                Thread.sleep(100);
            }
            assertEquals(0, status(), "the account is disabled");
            verify(k8s, timeout(5000)).deleteSecret(NS, TagSyncRangerAccount.secretName(rel));
            awaitLog(id, "did not install release " + NS + "/" + rel);
            assertNull(records.find(NS, rel));
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void aTakeBackStillWaitingForAHelmCallLeavesTheAccountALaterDeployOfTheReleaseWasGiven() throws Exception {
        accountGivenByThePlan();
        helmHasTheRelease(false);
        String failed = deployOperation(true, CommandState.FAILED);
        Map<String, Object> failedParams = new LinkedHashMap<>();
        failedParams.put("namespace", NS);
        failedParams.put("releaseName", rel);
        failedParams.put(CommandService.TAG_SYNC_NEW_ACCOUNT_PARAM, "cdp-prod");
        failedParams.put(CommandService.TAG_SYNC_GRANT_PARAM, grant());

        java.util.concurrent.CountDownLatch helmCall = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService helmThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Object> running = HelmOperationRegistry.submit(NS, rel, helmThread, () -> {
                helmCall.await(30, TimeUnit.SECONDS);
                return null;
            });
            for (int i = 0; i < 200 && !HelmOperationRegistry.isRunning(NS, rel); i++) {
                Thread.sleep(10);
            }
            assertFalse(service.takeBackTagSyncAccountOfUninstalledRelease(failed, failedParams, true),
                    "the failed deploy's Helm call may yet install the release");

            // Its Helm call over, the release is deployed again, whose plan resets the account.
            helmCall.countDown();
            running.get(30, TimeUnit.SECONDS);
            accountGivenByThePlanOf("grant-of-the-next-deploy");

            assertTrue(service.takeBackTagSyncAccountOfUninstalledRelease(failed, failedParams, true));
        } finally {
            helmThread.shutdownNow();
        }
        assertEquals(1, status(), "the account of the next deploy stays enabled");
        verify(k8s, never()).deleteSecret(anyString(), anyString());
        assertEquals("grant-of-the-next-deploy", records.find(NS, rel).grant());
    }

    @Test
    void aDeployOfAReleaseThatHasAnAccountButIsNotInstalledTakesItOverAndOneThatIsInstalledDoesNot()
            throws Exception {
        accountGivenByThePlan();
        helmHasTheRelease(false);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("_platformContextId", "cdp-prod");

        service.wireTagSyncRangerAccount(wizardRequest(), params, tagSyncValues(), externalContext(), ranger.url(),
                null, null);

        assertEquals("cdp-prod", params.get(CommandService.TAG_SYNC_NEW_ACCOUNT_PARAM),
                "should this deploy end without installing the release, it takes the account back");
        Object grant = params.get(CommandService.TAG_SYNC_GRANT_PARAM);
        assertNotNull(grant);
        assertNotEquals(grant(), grant);
        assertEquals(grant, records.find(NS, rel).grant());

        helmHasTheRelease(true);
        Map<String, Object> upgrade = new LinkedHashMap<>();
        upgrade.put("_platformContextId", "cdp-prod");
        service.wireTagSyncRangerAccount(wizardRequest(), upgrade, tagSyncValues(), externalContext(), ranger.url(),
                null, null);
        assertFalse(upgrade.containsKey(CommandService.TAG_SYNC_NEW_ACCOUNT_PARAM),
                "the release keeps its account for its uninstall");
    }

    private HelmDeployRequest wizardRequest() {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace(NS);
        request.setReleaseName(rel);
        return request;
    }

    /** The merged values of a release that syncs Atlas tags with the Secret KDPS writes. */
    private Map<String, Object> tagSyncValues() {
        Map<String, Object> tagSync = new LinkedHashMap<>();
        tagSync.put("enabled", true);
        tagSync.put("ranger", new LinkedHashMap<>(Map.of("existingSecret", TagSyncRangerAccount.secretName(rel))));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tagSync", tagSync);
        values.put("accessControl", new LinkedHashMap<>(Map.of("type", "ranger")));
        return values;
    }

    /** An external platform context whose Ranger the view reaches with stored administrator credentials. */
    private ResolvedContext externalContext() {
        ResolvedContext rc = new ResolvedContext();
        rc.setId("cdp-prod");
        rc.setName("CDP production");
        rc.setKind("EXTERNAL");
        rc.setRangerUrl(ranger.url());
        rc.setRangerAdminUsername("admin");
        rc.setRangerAdminPassword("admin-pw");
        rc.setAutoConfigureRemote(true);
        return rc;
    }

    private void awaitState(String id, CommandState expected) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (expected.name().equals(statusOf(id).getState())) return;
            Thread.sleep(50);
        }
        fail("operation " + id + " is " + statusOf(id).getState() + ": " + statusOf(id).getMessage());
    }
}
