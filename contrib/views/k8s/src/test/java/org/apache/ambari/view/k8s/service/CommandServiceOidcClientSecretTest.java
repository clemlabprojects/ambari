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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.ambari.view.k8s.client.KeycloakAdminClient;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.model.ContextRequest;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.apache.ambari.view.k8s.security.TestMasterKeys;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.KdpsSettingEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * The client secret Keycloak gives a client a deploy registers is never kept in the registration step's result, a
 * plain column kept with the operation history: it waits encrypted for the step that writes it into the release's
 * Secret, and results earlier versions wrote are cleared of it.
 */
class CommandServiceOidcClientSecretTest {

    private static final String SECRET = "Kc-Client-Secret-764";
    private static final String REGISTER_STEP = "op-1-oidc-register";

    @TempDir
    Path workDir;

    private PerUserViewContext view;
    private CommandService service;

    @BeforeEach
    void setUp() throws Exception {
        view = new PerUserViewContext("oidc-secrets", workDir);
        service = new CommandService(view.context, mock(HelmService.class), mock(KubernetesService.class));
        service.startupSweep.get(30, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        if (keycloak != null) keycloak.stop(0);
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

    private static Map<String, Object> createStepParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("oidcClientId", "trino-analytics");
        params.put("oidcRegisterId", REGISTER_STEP);
        return params;
    }

    @Test
    void theRegistrationResultNamesTheClientButNeverHoldsItsSecret() throws Exception {
        CommandStatusEntity status = new CommandStatusEntity();
        status.setId(REGISTER_STEP + "-status");

        service.recordOidcRegistration(REGISTER_STEP, status, new KeycloakAdminClient.OidcClientResult(
                "trino-analytics", SECRET, "https://sso.example.com/realms/odp"));

        assertFalse(status.getResultJson().contains(SECRET), status.getResultJson());
        assertFalse(status.getResultJson().contains("clientSecret"), status.getResultJson());
        assertTrue(status.getResultJson().contains("\"clientId\":\"trino-analytics\""), status.getResultJson());
        KdpsSettingEntity kept = view.dataStore.find(KdpsSettingEntity.class, "oidc.client.secret." + REGISTER_STEP);
        assertNotNull(kept);
        assertFalse(kept.getValue().contains(SECRET), "kept encrypted");

        assertEquals(SECRET, service.clientSecretForCreateStep("op-1-oidc-secret", createStepParams(), REGISTER_STEP,
                Map.of("clientId", "trino-analytics")));
        service.oidcClientSecrets().forget(REGISTER_STEP);
        assertNull(view.dataStore.find(KdpsSettingEntity.class, "oidc.client.secret." + REGISTER_STEP),
                "deleted once written, row included");
    }

    @Test
    void aSecretNoLongerKeptIsReadFromKeycloakAgain() throws Exception {
        CommandService steps = spy(service);
        doReturn(new KeycloakAdminClient.OidcClientResult("trino-analytics", SECRET, "https://sso"))
                .when(steps).registerOidcClient(anyString(), anyMap());

        assertEquals(SECRET, steps.clientSecretForCreateStep("op-1-oidc-secret", createStepParams(), REGISTER_STEP,
                Map.of("clientId", "trino-analytics")));
        verify(steps).registerOidcClient("op-1-oidc-secret", createStepParams());
    }

    @Test
    void aResultAnEarlierVersionWroteStillServesUntilItIsCleared() throws Exception {
        CommandService steps = spy(service);

        assertEquals("planned-earlier", steps.clientSecretForCreateStep("op-1-oidc-secret", createStepParams(),
                REGISTER_STEP, Map.of("clientId", "trino-analytics", "clientSecret", "planned-earlier")));
        Map<String, Object> publicClient = createStepParams();
        publicClient.put("publicClient", "true");
        assertEquals("", steps.clientSecretForCreateStep("op-1-oidc-secret", publicClient, REGISTER_STEP,
                Map.of("clientId", "trino-analytics")), "a public client has none");
        verify(steps, never()).registerOidcClient(anyString(), anyMap());
    }

    /** Stores a command and its status, in the given state. */
    private void command(String id, CommandType type, String state, String... childIds) throws Exception {
        CommandStatusEntity status = new CommandStatusEntity();
        status.setId(id + "-status");
        status.setState(state);
        view.dataStore.store(status);
        CommandEntity command = new CommandEntity();
        command.setId(id);
        command.setType(type.name());
        command.setCommandStatusId(status.getId());
        command.setChildListJson(new com.google.gson.Gson().toJson(java.util.List.of(childIds)));
        view.dataStore.store(command);
    }

    @Test
    void aCanceledOperationKeepsNoClientSecret() throws Exception {
        command("op-1", CommandType.HELM_DEPLOY, "RUNNING", REGISTER_STEP, "op-1-oidc-secret");
        command(REGISTER_STEP, CommandType.OIDC_REGISTER_CLIENT, "SUCCEEDED");
        command("op-1-oidc-secret", CommandType.OIDC_CREATE_SECRET, "PENDING");
        service.oidcClientSecrets().keep(REGISTER_STEP, SECRET);
        service.oidcClientSecrets().keep("op-2-oidc-register", "Another-Operation-Secret");

        service.cancel("op-1");

        assertEquals("CANCELED", view.dataStore.find(CommandStatusEntity.class, "op-1-status").getState());
        assertNull(view.dataStore.find(KdpsSettingEntity.class, "oidc.client.secret." + REGISTER_STEP),
                "no step of a canceled operation will write it; a later deploy reads it from Keycloak again");
        assertEquals("Another-Operation-Secret", service.oidcClientSecrets().read("op-2-oidc-register"),
                "another operation's is kept");
    }

    @Test
    void theResultsEarlierVersionsWroteAreClearedOfTheSecret() throws Exception {
        CommandStatusEntity legacy = new CommandStatusEntity();
        legacy.setId("old-register-status");
        legacy.setState("SUCCEEDED");
        legacy.setResultJson("{\"clientId\":\"trino\",\"issuerUrl\":\"https://sso/realms/odp\",\"clientSecret\":\""
                + SECRET + "\"}");
        view.dataStore.store(legacy);
        CommandStatusEntity other = new CommandStatusEntity();
        other.setId("helm-status");
        other.setState("SUCCEEDED");
        other.setResultJson("{\"release\":\"trino\"}");
        view.dataStore.store(other);

        assertEquals(1, service.clearOidcClientSecretsFromResults());

        String cleared = view.dataStore.find(CommandStatusEntity.class, "old-register-status").getResultJson();
        assertFalse(cleared.contains(SECRET), cleared);
        assertTrue(cleared.contains("\"clientId\":\"trino\""), cleared);
        assertEquals("{\"release\":\"trino\"}", view.dataStore.find(CommandStatusEntity.class, "helm-status")
                .getResultJson());
        assertEquals(0, service.clearOidcClientSecretsFromResults(), "nothing left");
    }

    @Test
    void theStartupClearsThemToo() throws Exception {
        CommandStatusEntity legacy = new CommandStatusEntity();
        legacy.setId("old-register-status");
        legacy.setState("SUCCEEDED");
        legacy.setResultJson("{\"clientId\":\"trino\",\"clientSecret\":\"" + SECRET + "\"}");
        view.dataStore.store(legacy);

        CommandService restarted = new CommandService(view.context, mock(HelmService.class),
                mock(KubernetesService.class));
        try {
            restarted.startupSweep.get(30, TimeUnit.SECONDS);
            assertEquals("{\"clientId\":\"trino\"}", view.dataStore.find(CommandStatusEntity.class,
                    "old-register-status").getResultJson());
        } finally {
            restarted.shutdown();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The registration step itself, against an in-memory Keycloak reached through a context carrying its admin creds
    // ---------------------------------------------------------------------------------------------------------------

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLIENT_UUID = "6a1f0c2e-0000-4000-8000-000000000764";

    private HttpServer keycloak;
    /** Client representations Keycloak received, as "POST"/"PUT" with their body. */
    private final List<Map.Entry<String, Map<String, Object>>> keycloakReceived = new CopyOnWriteArrayList<>();
    private final List<String> keycloakRequests = new CopyOnWriteArrayList<>();
    private volatile boolean clientExists;
    private volatile String currentMappers = "[]";

    /** Starts the in-memory Keycloak and an EXTERNAL context whose OIDC admin credentials point at it. */
    private void keycloakContext() throws Exception {
        keycloak = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        keycloak.createContext("/", this::handleKeycloak);
        keycloak.start();
        view.as("admin");
        ContextRequest context = new ContextRequest();
        context.id = "ext";
        context.name = "External cluster";
        context.kind = ContextService.KIND_EXTERNAL;
        context.config = new LinkedHashMap<>(Map.of(
                "issuerUrl", "http://127.0.0.1:" + keycloak.getAddress().getPort() + "/realms/odp",
                "adminClientId", "kdps-admin"));
        context.secrets = new LinkedHashMap<>(Map.of("adminClientSecret", "Admin-Secret-764"));
        new ContextService(view.context).save(context);
        view.as(null);
    }

    private void handleKeycloak(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        keycloakRequests.add(method + " " + path);
        byte[] body = ex.getRequestBody().readAllBytes();
        if (path.endsWith("/protocol/openid-connect/token")) {
            reply(ex, 200, "{\"access_token\":\"admin-token\"}");
        } else if ("GET".equals(method) && path.equals("/admin/realms/odp/clients")) {
            reply(ex, 200, clientExists ? "[{\"id\":\"" + CLIENT_UUID + "\"}]" : "[]");
        } else if ("GET".equals(method) && path.endsWith("/protocol-mappers/models")) {
            reply(ex, 200, currentMappers);
        } else if ("GET".equals(method) && path.endsWith("/client-secret")) {
            reply(ex, 200, "{\"value\":\"" + SECRET + "\"}");
        } else if ("POST".equals(method) && path.equals("/admin/realms/odp/clients")) {
            keycloakReceived.add(Map.entry("POST", JSON.readValue(body, new TypeReference<Map<String, Object>>() {})));
            ex.getResponseHeaders().add("Location", "http://keycloak/admin/realms/odp/clients/" + CLIENT_UUID);
            reply(ex, 201, "");
        } else if ("PUT".equals(method) && path.equals("/admin/realms/odp/clients/" + CLIENT_UUID)) {
            keycloakReceived.add(Map.entry("PUT", JSON.readValue(body, new TypeReference<Map<String, Object>>() {})));
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

    /** Stores a deploy whose only step is the Keycloak registration with the given parameters, and runs it. */
    private String runRegistrationStep(Map<String, Object> extra) {
        String id = "deploy-" + Long.toHexString(System.nanoTime());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("releaseName", "bi");
        params.put("namespace", "analytics");
        params.put("_baseUri", "http://ambari:8080/");
        params.put("_platformContextId", "ext");
        params.put("oidcClientId", "superset-analytics");
        params.put("oidcRedirectUri", "");
        params.putAll(extra);
        String step = id + "-oidc-register";
        CommandEntity root = new CommandEntity();
        root.setId(id);
        root.setType(CommandType.K8S_MANAGER_RELEASE_DEPLOY.name());
        root.setTitle("Deploy release bi");
        root.setCommandStatusId(id + "-status");
        root.setParamsJson(new com.google.gson.Gson().toJson(params));
        root.setChildListJson(new com.google.gson.Gson().toJson(List.of(step)));
        view.dataStore.store(root);
        CommandEntity register = new CommandEntity();
        register.setId(step);
        register.setType(CommandType.OIDC_REGISTER_CLIENT.name());
        register.setTitle("Keycloak: register OIDC client superset-analytics");
        register.setCommandStatusId(step + "-status");
        register.setParamsJson(new com.google.gson.Gson().toJson(params));
        view.dataStore.store(register);
        for (String c : List.of(id, step)) {
            CommandStatusEntity st = new CommandStatusEntity();
            st.setId(c + "-status");
            st.setState(c.equals(id) ? CommandState.RUNNING.name() : CommandState.PENDING.name());
            st.setAttempt(0);
            view.dataStore.store(st);
        }
        // Only this step runs: the scheduler would otherwise go on at once, end the one-step operation as
        // SUCCEEDED and, rightly, forget the kept secret before the test looks at it.
        try {
            java.lang.reflect.Field timer = CommandService.class.getDeclaredField("timer");
            timer.setAccessible(true);
            timer.set(service, mock(java.util.concurrent.ScheduledExecutorService.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        service.runNextStep(id);
        return step;
    }

    @SuppressWarnings("unchecked")
    private static List<String> audiencesSent(Map<String, Object> client) {
        List<String> audiences = new ArrayList<>();
        for (Object m : (List<Object>) client.getOrDefault("protocolMappers", List.of())) {
            Map<String, Object> mapper = (Map<String, Object>) m;
            if ("oidc-audience-mapper".equals(mapper.get("protocolMapper"))) {
                audiences.add(String.valueOf(((Map<String, Object>) mapper.get("config")).get("included.custom.audience")));
            }
        }
        return audiences;
    }

    @Test
    void theRegistrationStepSetsThePlannedAudiencesAndKeepsNoSecretInItsResult() throws Exception {
        keycloakContext();
        clientExists = false;

        String step = runRegistrationStep(Map.of("oidcAudiences", List.of("trino-analytics", " ")));

        CommandStatusEntity st = view.dataStore.find(CommandStatusEntity.class, step + "-status");
        assertEquals(CommandState.SUCCEEDED.name(), st.getState(), st.getMessage());
        assertEquals(1, keycloakReceived.size(), keycloakRequests::toString);
        assertEquals("POST", keycloakReceived.get(0).getKey());
        assertEquals(List.of("trino-analytics"), audiencesSent(keycloakReceived.get(0).getValue()),
                "the audiences the deploy planned are on the client's tokens");
        assertFalse(st.getResultJson().contains(SECRET), st.getResultJson());
        assertFalse(st.getResultJson().contains("clientSecret"), st.getResultJson());
        assertTrue(st.getResultJson().contains("\"clientId\":\"superset-analytics\""), st.getResultJson());
        KdpsSettingEntity kept = view.dataStore.find(KdpsSettingEntity.class, "oidc.client.secret." + step);
        assertNotNull(kept, "kept for the step that writes the release's Secret");
        assertFalse(kept.getValue().contains(SECRET), "kept encrypted");
        assertEquals(SECRET, service.oidcClientSecrets().read(step));
    }

    @Test
    void aRegistrationPlanningNoAudiencesKeepsThoseTheClientHas() throws Exception {
        keycloakContext();
        clientExists = true;
        currentMappers = "[{\"id\":\"m1\",\"name\":\"audience trino-analytics\",\"protocol\":\"openid-connect\","
                + "\"protocolMapper\":\"oidc-audience-mapper\",\"config\":{\"included.custom.audience\":\"trino-analytics\"}}]";

        String step = runRegistrationStep(Map.of());

        CommandStatusEntity st = view.dataStore.find(CommandStatusEntity.class, step + "-status");
        assertEquals(CommandState.SUCCEEDED.name(), st.getState(), st.getMessage());
        assertEquals("PUT", keycloakReceived.get(0).getKey());
        assertEquals(List.of("trino-analytics"), audiencesSent(keycloakReceived.get(0).getValue()),
                "re-registered with the audience mappers it has");
        assertFalse(st.getResultJson().contains(SECRET), st.getResultJson());
    }

    @Test
    void aSecretAnEarlierVersionEncryptedWithoutTheMasterKeyIsEncryptedAgainOnStartWithoutBeingRead()
            throws Exception {
        PerUserViewContext upgraded = new PerUserViewContext("oidc-legacy", workDir.resolve("upgraded"));
        String key = "oidc.client.secret.op-9-oidc-register";
        String legacy = java.util.Base64.getEncoder().encodeToString(TestMasterKeys.legacyCiphertext(SECRET));
        upgraded.dataStore.store(new KdpsSettingEntity(key, legacy));

        CommandService started = new CommandService(upgraded.context, mock(HelmService.class),
                mock(KubernetesService.class));
        try {
            started.startupSweep.get(30, TimeUnit.SECONDS);
        } finally {
            started.shutdown();
        }

        String stored = upgraded.dataStore.find(KdpsSettingEntity.class, key).getValue();
        assertNotEquals(legacy, stored);
        // Saved again in the encrypted form itself (salt:iv:ciphertext), without an outer Base64.
        EncryptionService.Decrypted decrypted = EncryptionService.forServer(upgraded.context)
                .decryptStored(stored.getBytes(StandardCharsets.UTF_8));
        assertEquals(SECRET, new String(decrypted.plain(), StandardCharsets.UTF_8));
        assertFalse(decrypted.reencrypt(), "encrypted with the Ambari master key");
    }
}
