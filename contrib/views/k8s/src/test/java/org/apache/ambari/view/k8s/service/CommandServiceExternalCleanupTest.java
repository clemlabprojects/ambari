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

package org.apache.ambari.view.k8s.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.model.ContextRequest;
import org.apache.ambari.view.k8s.service.om.FakeRangerAdmin;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.store.ProvisioningLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.apache.ambari.view.k8s.service.om.FakeRangerAdmin.grantees;
import static org.apache.ambari.view.k8s.service.om.FakeRangerAdmin.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * The uninstall of a release takes back what KDPS registered for it outside Kubernetes, as its ledger records it,
 * against the Keycloak and Ranger of an external platform context (both in memory): the client of a release whose
 * sign-in Secrets are already gone, the Ranger access KDPS granted, and, when asked, the release's repository. The
 * client secrets kept for its registration steps are forgotten, and what a deploy step registers is recorded with the
 * release.
 */
class CommandServiceExternalCleanupTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "bi";
    private static final String CONTEXT = "ext";
    private static final String CLIENT_ID = "bi-analytics";
    private static final String CLIENT_UUID = "6a1f0c2e-0000-4000-8000-000000000804";

    @TempDir
    Path workDir;

    private PerUserViewContext view;
    private CommandService service;
    private FakeRangerAdmin ranger;
    private HttpServer keycloak;
    private final List<String> keycloakRequests = new CopyOnWriteArrayList<>();
    private volatile String client;

    @BeforeEach
    void setUp() throws Exception {
        view = new PerUserViewContext("external-cleanup", workDir);
        KubernetesService k8s = mock(KubernetesService.class);
        service = new CommandService(view.context, mock(HelmService.class), k8s);
        service.startupSweep.get(30, TimeUnit.SECONDS);
        service.tagSyncInstance = () -> "instance-a";
        doReturn(List.of()).when(k8s).listSecretsWithLabel(anyString(), anyString(), anyString());
        ranger = new FakeRangerAdmin();
        keycloak = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        keycloak.createContext("/", this::handleKeycloak);
        keycloak.start();
        view.as("admin");
        ContextRequest context = new ContextRequest();
        context.id = CONTEXT;
        context.name = "External cluster";
        context.kind = ContextService.KIND_EXTERNAL;
        context.config = new LinkedHashMap<>(Map.of(
                "issuerUrl", "http://127.0.0.1:" + keycloak.getAddress().getPort() + "/realms/odp",
                "adminClientId", "kdps-admin",
                "rangerUrl", ranger.url(),
                "rangerAdminUsername", "admin"));
        context.secrets = new LinkedHashMap<>(Map.of("adminClientSecret", "Admin-Secret-804",
                "rangerAdminPassword", "Ranger-Pass"));
        new ContextService(view.context).save(context);
        view.as(null);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        keycloak.stop(0);
        ranger.close();
    }

    /** Steps run with slf4j's MDC; see CommandServiceHelmStepTest for why it is loaded this way. */
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

    private void handleKeycloak(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        keycloakRequests.add(method + " " + path);
        ex.getRequestBody().readAllBytes();
        String one = "/admin/realms/odp/clients/" + CLIENT_UUID;
        if (path.endsWith("/protocol/openid-connect/token")) {
            reply(ex, 200, "{\"access_token\":\"admin-token\"}");
        } else if ("GET".equals(method) && path.equals("/admin/realms/odp/clients")) {
            reply(ex, 200, client == null ? "[]" : "[" + client + "]");
        } else if ("GET".equals(method) && path.equals(one)) {
            reply(ex, client == null ? 404 : 200, client == null ? "{}" : client);
        } else if ("DELETE".equals(method) && path.equals(one)) {
            client = null;
            reply(ex, 204, null);
        } else {
            reply(ex, 404, "{}");
        }
    }

    private static void reply(HttpExchange ex, int code, String body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(code, -1);
        } else {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    @SuppressWarnings("unchecked")
    private static List<String> removed(Map<String, Object> report) {
        return (List<String>) report.get("removed");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> left(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("left");
    }

    @Test
    void theKeycloakClientOfAReleaseWhoseSecretsAreGoneIsDeletedFromItsLedger() {
        client = "{\"id\":\"" + CLIENT_UUID + "\",\"clientId\":\"" + CLIENT_ID + "\",\"attributes\":{\"kdps.release\":\""
                + NS + "/" + RELEASE + "\",\"kdps.instance\":\"instance-a\"}}";
        ProvisioningLedger ledger = new ProvisioningLedger();
        ledger.addOidcClient(new ProvisioningLedger.OidcClient(CLIENT_ID, "odp", CONTEXT));

        Map<String, Object> report = service.revokeExternalForRelease(NS, RELEASE, ledger, "credentials", false, null);

        assertTrue(keycloakRequests.contains("DELETE /admin/realms/odp/clients/" + CLIENT_UUID), keycloakRequests::toString);
        assertEquals(List.of("Keycloak client 'bi-analytics' in realm 'odp'"), removed(report));
    }

    @Test
    void theRangerAccessKdpsGrantedIsTakenBackInTheContextsRanger() throws Exception {
        long defaultId = ranger.add("cdp_hive", "all - database, table, column",
                Map.of("database", List.of("*"), "table", List.of("*"), "column", List.of("*")),
                item(List.of("hive"), "select"), item(List.of("oma-ing-analytics"), "select"));
        ranger.services.put("bi-analytics", 77L);
        ProvisioningLedger ledger = new ProvisioningLedger();
        ledger.addRangerGrant(new ProvisioningLedger.RangerGrant(CONTEXT, "cdp_hive", "oma-ing-analytics", "select",
                CommandService.HIVE_ALL_RESOURCES, "kdps-openmetadata-bi-hive-read", true));
        ledger.addRangerRepository(new ProvisioningLedger.RangerRepository(CONTEXT, "bi-analytics"));

        Map<String, Object> kept = service.revokeExternalForRelease(NS, RELEASE, ledger, "credentials", true, null);

        assertEquals(List.of(List.of("hive")), grantees(ranger.policies.get(defaultId), "select"));
        assertTrue(ranger.services.containsKey("bi-analytics"), "the repository stays unless everything is asked for");
        assertTrue(kept.get("note").toString().contains("Ranger repository 'bi-analytics'"));

        service.revokeExternalForRelease(NS, RELEASE, ledger, "all", true, null);

        assertFalse(ranger.services.containsKey("bi-analytics"));
    }

    @Test
    void aPlatformContextKdpsNoLongerHasIsReported() {
        ProvisioningLedger ledger = new ProvisioningLedger();
        ledger.addRangerGrant(new ProvisioningLedger.RangerGrant("gone", "cdp_hive", "oma-ing-analytics", "select",
                CommandService.HIVE_ALL_RESOURCES, "kdps-openmetadata-bi-hive-read", true));

        Map<String, Object> report = service.revokeExternalForRelease(NS, RELEASE, ledger, null, true, null);

        assertTrue(left(report).get(0).get("reason").toString().contains("'gone' no longer exists"), report::toString);
        assertTrue(ranger.requests.isEmpty());
    }

    @Test
    void theClientSecretsKeptForTheReleasesRegistrationStepsAreForgotten() throws Exception {
        registrationStep("op-1-oidc-register", NS, RELEASE, "{\"clientId\":\"bi\",\"clientSecret\":\"Old-Secret\"}");
        registrationStep("op-2-oidc-register", NS, "other", "{\"clientId\":\"other\",\"clientSecret\":\"Kept\"}");
        service.oidcClientSecrets().keep("op-1-oidc-register", "Failed-Deploy-Secret");
        service.oidcClientSecrets().keep("op-2-oidc-register", "Other-Secret");

        assertNull(service.revokeExternalForRelease(NS, RELEASE, new ProvisioningLedger(), "none", false, null),
                "nothing recorded, nothing to report");

        assertNull(service.oidcClientSecrets().read("op-1-oidc-register"));
        assertEquals("{\"clientId\":\"bi\"}",
                view.dataStore.find(CommandStatusEntity.class, "op-1-oidc-register-status").getResultJson());
        assertEquals("Other-Secret", service.oidcClientSecrets().read("op-2-oidc-register"), "another release's stays");
    }

    @Test
    void aGrantADeployStepMakesIsRecordedWithTheRelease() {
        ReleaseMetadataService metadata = new ReleaseMetadataService(view.context);
        metadata.recordInstallOrUpgrade(NS, RELEASE, "OPENMETADATA", CONTEXT, "om", null, "1.0", null, null, null,
                null, null, null, "DIRECT_HELM", null, null, null, null, null, null, null, null, null);
        Map<String, Object> stepParams = Map.of("namespace", NS, "releaseName", RELEASE, "_platformContextId", CONTEXT);

        service.recordRangerGrant(stepParams, "cdp_hive", "oma-ing-analytics", "select",
                CommandService.HIVE_ALL_RESOURCES, "kdps-openmetadata-bi-hive-read", true);

        assertEquals(List.of(new ProvisioningLedger.RangerGrant(CONTEXT, "cdp_hive", "oma-ing-analytics", "select",
                        CommandService.HIVE_ALL_RESOURCES, "kdps-openmetadata-bi-hive-read", true)),
                metadata.provisioningOf(NS, RELEASE).rangerGrants());
        @SuppressWarnings("unchecked")
        List<String> preview = (List<String>) service.externalRegistrationsOfRelease(NS, RELEASE, null).get("removed");
        assertEquals(1, preview.size());
    }

    @Test
    void aReleaseThatRegisteredNothingIsNotTakenForOneDeployedByAnEarlierVersion() throws Exception {
        ReleaseMetadataService metadata = recordedRelease();
        assertEquals(true, service.externalRegistrationsOfRelease(NS, RELEASE, null).get("recorded"),
                "recorded since ledgers exist, with nothing registered");

        K8sReleaseEntity earlier = metadata.find(NS, RELEASE);
        earlier.setProvisioningJson(""); // the DataStore keeps a value an update sets to null
        view.dataStore.store(earlier);
        assertEquals(false, service.externalRegistrationsOfRelease(NS, RELEASE, null).get("recorded"),
                "deployed before KDPS kept ledgers");

        assertEquals(true, service.externalRegistrationsOfRelease(NS, "not-deployed-by-kdps", null).get("recorded"),
                "KDPS registered nothing for a release it has no record of");
    }

    private ReleaseMetadataService recordedRelease() {
        ReleaseMetadataService metadata = new ReleaseMetadataService(view.context);
        metadata.recordInstallOrUpgrade(NS, RELEASE, "SUPERSET", CONTEXT, "superset", null, "1.0", null, null, null,
                null, null, null, "DIRECT_HELM", null, null, null, null, null, null, null, null, null);
        return metadata;
    }

    private Map<String, Object> impersonateStep(String principal, boolean minted) {
        Map<String, Object> params = new LinkedHashMap<>(Map.of("namespace", NS, "releaseName", RELEASE,
                "_platformContextId", CONTEXT, "_principal", principal, "_trinoRangerServiceName", "lake-trino"));
        if (minted) params.put(CommandService.PRINCIPAL_MINTED_BY_KDPS, "true");
        return params;
    }

    @Test
    void theImpersonationOfAnOperatorsTrinoAccountIsTakenBackFromTheReleasesPolicyOnly() throws Exception {
        // The platform's shared service user, which the repository's default policy already lets impersonate.
        long defaultId = ranger.add("lake-trino", "all - trinouser", Map.of("trinouser", List.of("*")),
                item(List.of("svc-bi"), "impersonate"));
        ReleaseMetadataService metadata = recordedRelease();

        service.grantRangerTrinoImpersonate(impersonateStep("svc-bi", false));
        ProvisioningLedger ledger = metadata.provisioningOf(NS, RELEASE);
        service.revokeExternalForRelease(NS, RELEASE, ledger, "credentials", true, null);

        assertFalse(ledger.rangerGrants().get(0).exclusive(), "an account the operator named is not KDPS's");
        assertEquals(List.of(List.of("svc-bi")), grantees(ranger.policies.get(defaultId), "impersonate"),
                "every other client of the account keeps its access");
        assertNull(ranger.byName("lake-trino", "kdps-" + RELEASE + "-trino-impersonate"));
    }

    @Test
    void theImpersonationOfAnAccountKdpsMintedIsTakenBackWherever() throws Exception {
        long defaultId = ranger.add("lake-trino", "all - trinouser", Map.of("trinouser", List.of("*")),
                item(List.of("trino"), "impersonate"));
        ReleaseMetadataService metadata = recordedRelease();

        service.grantRangerTrinoImpersonate(impersonateStep("superset-analytics", true));
        ProvisioningLedger ledger = metadata.provisioningOf(NS, RELEASE);
        service.revokeExternalForRelease(NS, RELEASE, ledger, "credentials", true, null);

        assertTrue(ledger.rangerGrants().get(0).exclusive());
        assertEquals(List.of(List.of("trino")), grantees(ranger.policies.get(defaultId), "impersonate"));
        assertNull(ranger.byName("lake-trino", "kdps-" + RELEASE + "-trino-impersonate"));
    }

    @Test
    void anAccountMintedForTheNamespaceIsRecordedAsSuchOnlyWhenTheStepSaysSo() throws Exception {
        ReleaseMetadataService metadata = recordedRelease();
        Map<String, Object> step = impersonateStep("superset-analytics", true);
        step.put(CommandService.PRINCIPAL_PER_NAMESPACE, "true");

        service.grantRangerTrinoImpersonate(step);

        assertTrue(metadata.provisioningOf(NS, RELEASE).rangerGrants().get(0).perNamespace());
        service.grantRangerTrinoImpersonate(impersonateStep("superset-analytics", true));
        assertFalse(metadata.provisioningOf(NS, RELEASE).rangerGrants().get(0).perNamespace(),
                "a step that does not say so is taken as shared by every namespace");
    }

    @Test
    void thePrincipalKdpsIssuesIsPerNamespaceOnlyWhenItsNameHoldsTheNamespace() {
        assertTrue(CommandService.issuedPrincipalNamesNamespace(null));
        org.apache.ambari.view.k8s.model.stack.StackServiceDef def = new org.apache.ambari.view.k8s.model.stack.StackServiceDef();
        def.kerberos = List.of(Map.of("principalTemplate", "{{service}}@{{realm}}"));
        assertFalse(CommandService.issuedPrincipalNamesNamespace(def));
        def.kerberos = List.of(Map.of("principalTemplate", "{{service}}-{{namespace}}@{{realm}}"));
        assertTrue(CommandService.issuedPrincipalNamesNamespace(def));
    }

    @Test
    void aRepositoryRangerAlreadyHadIsNotRecordedAsTheReleases() throws Exception {
        ReleaseMetadataService metadata = recordedRelease();
        ranger.services.put("bi-analytics", 77L);
        Map<String, Object> step = Map.of("namespace", NS, "releaseName", RELEASE, "_platformContextId", CONTEXT);
        var rc = service.resolvePlatformContextForStep(new LinkedHashMap<>(step));

        assertEquals(77L, service.ensureRangerRepositoryInContext(step, rc, "bi-analytics", "trino", Map.of()));
        assertTrue(metadata.provisioningOf(NS, RELEASE).rangerRepositories().isEmpty(), "adopted, not created");

        ranger.services.clear();
        service.ensureRangerRepositoryInContext(step, rc, "bi-analytics", "trino", Map.of());
        assertEquals(List.of(new ProvisioningLedger.RangerRepository(CONTEXT, "bi-analytics")),
                metadata.provisioningOf(NS, RELEASE).rangerRepositories());
    }

    @Test
    void onlyTheRepositoryNameKdpsGivesAReleaseIsItsOwn() {
        Map<String, Object> step = Map.of("namespace", NS, "releaseName", "trino");

        assertTrue(CommandService.isReleaseOwnRangerRepository(step, "trino-analytics"));
        // A name the operator chose that merely contains the release name may be a shared repository.
        assertFalse(CommandService.isReleaseOwnRangerRepository(step, "cdp_trino"));
        assertFalse(CommandService.isReleaseOwnRangerRepository(step, "trino"));
    }

    @Test
    void theVaultCopyIsDeletedWithEveryVersion() {
        assertEquals("https://vault.example.org/v1/secret/metadata/oidc/bi",
                CommandService.vaultDeleteUrl("https://vault.example.org/", "secret/data/oidc/bi"));
        assertEquals("https://vault.example.org/v1/kv1/oidc/bi",
                CommandService.vaultDeleteUrl("https://vault.example.org", "/kv1/oidc/bi"));
    }

    /** Stores an OIDC registration step of the release, with the result it ended with. */
    private void registrationStep(String id, String namespace, String release, String result) throws Exception {
        CommandStatusEntity status = new CommandStatusEntity();
        status.setId(id + "-status");
        status.setState("SUCCEEDED");
        status.setResultJson(result);
        view.dataStore.store(status);
        CommandEntity step = new CommandEntity();
        step.setId(id);
        step.setType(CommandType.OIDC_REGISTER_CLIENT.name());
        step.setCommandStatusId(status.getId());
        step.setParamsJson(new com.google.gson.Gson().toJson(Map.of("namespace", namespace, "releaseName", release)));
        view.dataStore.store(step);
    }
}
