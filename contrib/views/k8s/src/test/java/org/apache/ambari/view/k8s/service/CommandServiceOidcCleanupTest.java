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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.model.ContextRequest;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * What the uninstall of a release removes of its sign-in (see {@link CommandService#revokeOidcForRelease}): the Secrets
 * holding its client secret in every case, and the Keycloak client KDPS registered for it only when the client's mark
 * names the release and this KDPS instance; and the labels and annotations a deploy writes on those Secrets so that
 * the uninstall finds them once the release's record is gone.
 */
class CommandServiceOidcCleanupTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "bi";
    private static final String CLIENT_ID = "bi-analytics";
    private static final String CLIENT_UUID = "6a1f0c2e-0000-4000-8000-000000000777";

    @TempDir
    Path workDir;

    private PerUserViewContext view;
    private KubernetesService k8s;
    private CommandService service;

    private HttpServer keycloak;
    private final List<String> keycloakRequests = new CopyOnWriteArrayList<>();
    /** The status the token endpoint answers with: 401 once the administrator credential expired. */
    private volatile int tokenStatus = 200;
    /** The client as Keycloak has it, or null when the realm has none. */
    private volatile String client;

    @BeforeEach
    void setUp() throws Exception {
        view = new PerUserViewContext("oidc-cleanup", workDir);
        k8s = mock(KubernetesService.class);
        service = new CommandService(view.context, mock(HelmService.class), k8s);
        service.startupSweep.get(30, TimeUnit.SECONDS);
        service.tagSyncInstance = () -> "instance-a";
        doReturn(List.of()).when(k8s).listSecretsWithLabel(anyString(), anyString(), anyString());
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

    /** Starts the in-memory Keycloak and an EXTERNAL context "ext" whose OIDC admin credentials point at it. */
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
        context.secrets = new LinkedHashMap<>(Map.of("adminClientSecret", "Admin-Secret-777"));
        new ContextService(view.context).save(context);
        view.as(null);
    }

    private void handleKeycloak(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        keycloakRequests.add(method + " " + path);
        ex.getRequestBody().readAllBytes();
        String one = "/admin/realms/odp/clients/" + CLIENT_UUID;
        if (path.endsWith("/protocol/openid-connect/token")) {
            reply(ex, tokenStatus, tokenStatus == 200 ? "{\"access_token\":\"admin-token\"}"
                    : "{\"error\":\"invalid_client\",\"error_description\":\"Invalid client credentials\"}");
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

    private static String clientMarked(String release, String instance) {
        String attributes = release == null ? "{}"
                : "{\"kdps.release\":\"" + release + "\",\"kdps.instance\":\"" + instance + "\"}";
        return "{\"id\":\"" + CLIENT_UUID + "\",\"clientId\":\"" + CLIENT_ID + "\",\"attributes\":" + attributes + "}";
    }

    /** A Secret a deploy of this version wrote: labelled as the release's, annotated with what goes with it. */
    private Secret labelledSecret(String name, String source, String providerSecret, String vaultPath) {
        return new SecretBuilder().withNewMetadata().withName(name).withNamespace(NS)
                .withLabels(CommandService.oidcSecretLabels(RELEASE))
                .withAnnotations(withViewAnnotation(CommandService.oidcSecretAnnotations(CLIENT_ID, "odp", "ext",
                        "external".equals(source), providerSecret, vaultPath)))
                .endMetadata()
                .withData(Map.of("client_id", b64(CLIENT_ID), "client_secret", b64("Old-Secret")))
                .build();
    }

    /** A Secret an earlier version wrote: unlabelled, with only the view's own annotation. */
    private static Secret legacySecret(String name) {
        return new SecretBuilder().withNewMetadata().withName(name).withNamespace(NS)
                .withAnnotations(Map.of("managed-by", "ambari-k8s-view"))
                .endMetadata()
                .withData(Map.of("client_id", b64(CLIENT_ID), "client_secret", b64("Old-Secret"),
                        "issuer_url", b64("https://sso.example.org/realms/odp")))
                .build();
    }

    private static Map<String, String> withViewAnnotation(Map<String, String> annotations) {
        Map<String, String> all = new LinkedHashMap<>(annotations);
        all.put("managed-by", "ambari-k8s-view");
        return all;
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private void labelled(Secret... secrets) {
        doReturn(List.of(secrets)).when(k8s).listSecretsWithLabel(NS, CommandService.OIDC_LABEL_TYPE,
                CommandService.OIDC_TYPE_CLIENT);
    }

    @Test
    void theClientMarkedForTheReleaseAndItsSecretsAreRemoved() throws Exception {
        keycloakContext();
        client = clientMarked(NS + "/" + RELEASE, "instance-a");
        labelled(labelledSecret("bi-oidc-client", "internal", null, null));

        String note = service.revokeOidcForRelease(NS, RELEASE, null, null);

        assertTrue(keycloakRequests.contains("DELETE /admin/realms/odp/clients/" + CLIENT_UUID), keycloakRequests::toString);
        verify(k8s).deleteSecret(NS, "bi-oidc-client");
        assertEquals("Removed the single sign-on client 'bi-analytics' in Keycloak realm 'odp'.\n"
                + "Removed the Secret bi-oidc-client holding the client secret.", note);
    }

    @Test
    void aClientOfAnotherInstanceOrWithoutAMarkIsKeptAndReported() throws Exception {
        keycloakContext();
        labelled(labelledSecret("bi-oidc-client", "internal", null, null));

        client = clientMarked(NS + "/" + RELEASE, "instance-b");
        String foreign = service.revokeOidcForRelease(NS, RELEASE, null, null);
        client = clientMarked(null, null);
        String unmarked = service.revokeOidcForRelease(NS, RELEASE, null, null);

        assertTrue(keycloakRequests.stream().noneMatch(r -> r.startsWith("DELETE ")), keycloakRequests::toString);
        assertTrue(foreign.contains("Kept the single sign-on client 'bi-analytics' in Keycloak realm 'odp': it belongs"
                + " to another release, or to another KDPS sharing the realm."), foreign);
        assertTrue(unmarked.contains("cannot tell that it is this release's"), unmarked);
        assertTrue(unmarked.contains("kcadm.sh delete clients/$(kcadm.sh get clients -r odp -q clientId=bi-analytics"
                + " --fields id --format csv --noquotes) -r odp"), unmarked);
        assertTrue(unmarked.contains("Removed the Secret bi-oidc-client"), "the Secret goes in any case: " + unmarked);
    }

    @Test
    void anExternalProfileRemovesOnlyTheSecrets() throws Exception {
        keycloakContext();
        client = clientMarked(NS + "/" + RELEASE, "instance-a");
        labelled(labelledSecret("bi-oidc-client", "external", null, null));

        String note = service.revokeOidcForRelease(NS, RELEASE, "ext", null);

        assertTrue(keycloakRequests.isEmpty(), "the identity provider is never called: " + keycloakRequests);
        verify(k8s).deleteSecret(NS, "bi-oidc-client");
        assertTrue(note.contains("comes from the security profile's identity provider"), note);
        assertTrue(note.contains("Removed the Secret bi-oidc-client"), note);
    }

    @Test
    void theVaultPathASecretNamesIsReportedNeverDeleted() throws Exception {
        // Anyone who can edit Secrets in the namespace can change the annotation: the view's Vault token must not
        // delete the path it names.
        keycloakContext();
        client = clientMarked(NS + "/" + RELEASE, "instance-a");
        labelled(labelledSecret("bi-oidc-client", "external", null, "sys/mounts/secret"));

        String note = service.revokeOidcForRelease(NS, RELEASE, "ext", null);

        verify(k8s).deleteSecret(NS, "bi-oidc-client");
        assertTrue(note.contains("Vault at 'sys/mounts/secret', it was not removed: delete it in Vault"), note);
    }

    @Test
    void anExpiredAdministratorCredentialIsReportedAndTheSecretsAreStillRemoved() throws Exception {
        keycloakContext();
        tokenStatus = 401;
        labelled(labelledSecret("gitlab-oidc-client", "internal", "bi-omniauth-provider", null),
                labelledSecret("bi-omniauth-provider", "internal", "bi-omniauth-provider", null));

        String note = service.revokeOidcForRelease(NS, RELEASE, null, null);

        verify(k8s).deleteSecret(NS, "gitlab-oidc-client");
        verify(k8s).deleteSecret(NS, "bi-omniauth-provider");
        assertTrue(note.startsWith("The single sign-on client 'bi-analytics' could not be removed from Keycloak"), note);
        assertTrue(note.contains("HTTP 401"), note);
        assertTrue(note.contains("kcadm.sh delete clients/"), note);
        assertTrue(note.contains("Removed the Secrets gitlab-oidc-client, bi-omniauth-provider"), note);
        assertEquals(1, keycloakRequests.size(), "one client, tried once: " + keycloakRequests);
    }

    @Test
    void theSecretsEarlierVersionsWroteAreRemovedByNameButOnlyTheViewsOwn() throws Exception {
        doReturn(legacySecret("bi-oidc-client")).when(k8s).getSecret(NS, "bi-oidc-client");
        // A Secret of the provider's name the operator made: not the view's, so it stays.
        doReturn(new SecretBuilder().withNewMetadata().withName("bi-omniauth-provider").withNamespace(NS).endMetadata()
                .build()).when(k8s).getSecret(NS, "bi-omniauth-provider");

        String note = service.revokeOidcForRelease(NS, RELEASE, null, null);

        verify(k8s).deleteSecret(NS, "bi-oidc-client");
        verify(k8s, never()).deleteSecret(NS, "bi-omniauth-provider");
        assertTrue(note.contains("written by an earlier KDPS version"), note);
        assertTrue(note.contains("-r odp -q clientId=bi-analytics"), "the realm its issuer names: " + note);
    }

    @Test
    void anotherReleasesSecretsAreLeftAlone() {
        Secret other = labelledSecret("bi-2-oidc-client", "internal", null, null);
        other.getMetadata().getLabels().put(CommandService.OIDC_LABEL_INSTANCE, "bi-2");
        labelled(other);

        assertNull(service.revokeOidcForRelease(NS, RELEASE, null, null), "nothing of this release");
        verify(k8s, never()).deleteSecret(anyString(), anyString());
    }

    @Test
    void aSecretThatCannotBeDeletedIsReportedWithTheCommandThatDeletesIt() throws Exception {
        keycloakContext();
        client = clientMarked(NS + "/" + RELEASE, "instance-a");
        labelled(labelledSecret("bi-oidc-client", "internal", null, null));
        doThrow(new RuntimeException("forbidden")).when(k8s).deleteSecret(NS, "bi-oidc-client");

        String note = service.revokeOidcForRelease(NS, RELEASE, null, null);

        assertTrue(note.contains("Removed the single sign-on client 'bi-analytics'"), note);
        assertTrue(note.contains("oc delete secret bi-oidc-client -n analytics"), note);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The labels and annotations OIDC_CREATE_SECRET writes
    // ---------------------------------------------------------------------------------------------------------------

    /** Stores a deploy whose registration step succeeded, with the step writing the Secrets still to run, and runs it. */
    private void runCreateSecretStep(Map<String, Object> extra) throws Exception {
        String id = "deploy-" + Long.toHexString(System.nanoTime());
        String register = id + "-oidc-register";
        String create = id + "-oidc-secret";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("releaseName", "gitlab");
        params.put("namespace", NS);
        params.put("_platformContextId", "ext");
        params.put("oidcSecretName", "gitlab-oidc-client");
        params.put("oidcRegisterId", register);
        params.putAll(extra);
        Gson gson = new Gson();
        CommandEntity root = new CommandEntity();
        root.setId(id);
        root.setType(CommandType.K8S_MANAGER_RELEASE_DEPLOY.name());
        root.setTitle("Deploy release gitlab");
        root.setCommandStatusId(id + "-status");
        root.setParamsJson(gson.toJson(params));
        root.setChildListJson(gson.toJson(List.of(register, create)));
        view.dataStore.store(root);
        for (String[] step : new String[][]{{register, CommandType.OIDC_REGISTER_CLIENT.name()},
                {create, CommandType.OIDC_CREATE_SECRET.name()}}) {
            CommandEntity c = new CommandEntity();
            c.setId(step[0]);
            c.setType(step[1]);
            c.setTitle(step[1]);
            c.setCommandStatusId(step[0] + "-status");
            c.setParamsJson(gson.toJson(params));
            view.dataStore.store(c);
        }
        for (String c : List.of(id, register, create)) {
            CommandStatusEntity st = new CommandStatusEntity();
            st.setId(c + "-status");
            st.setState(c.equals(id) ? CommandState.RUNNING.name()
                    : c.equals(register) ? CommandState.SUCCEEDED.name() : CommandState.PENDING.name());
            st.setAttempt(0);
            if (c.equals(register)) {
                st.setResultJson("{\"clientId\":\"gitlab-analytics\",\"issuerUrl\":\"https://sso.example.org/realms/"
                        + "corp\",\"realm\":\"corp\"}");
            }
            view.dataStore.store(st);
        }
        service.oidcClientSecrets().keep(register, "Client-Secret-777");
        java.lang.reflect.Field timer = CommandService.class.getDeclaredField("timer");
        timer.setAccessible(true);
        timer.set(service, mock(java.util.concurrent.ScheduledExecutorService.class));
        service.runNextStep(id);
        assertEquals(CommandState.SUCCEEDED.name(),
                view.dataStore.find(CommandStatusEntity.class, create + "-status").getState(),
                view.dataStore.find(CommandStatusEntity.class, create + "-status").getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSecretsADeployWritesAreLabelledAsTheReleasesAndSayWhatGoesWithThem() throws Exception {
        runCreateSecretStep(Map.of("oidcGitlabOmniauth", "true", "oidcProviderSecretName", "gitlab-omniauth-provider",
                "oidcGitlabCallbackUrl", "https://gitlab.example.org/users/auth/openid_connect/callback",
                "oidcVaultPath", "secret/data/oidc/gitlab"));

        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, String>> annotations = ArgumentCaptor.forClass(Map.class);
        verify(k8s).createOrUpdateOpaqueSecret(eq(NS), eq("gitlab-oidc-client"), anyMap(), labels.capture(),
                annotations.capture());
        // The release label lists them with the release's other objects; their managed-by value leaves them to this
        // cleanup rather than to the sweep of those objects.
        assertEquals(Map.of("app.kubernetes.io/managed-by", "kdps", "app.kubernetes.io/instance", "gitlab",
                "ambari.clemlab.com/type", "oidc-client", "ambari.clemlab.com/release", "gitlab"), labels.getValue());
        assertEquals(Map.of("kdps.clemlab.com/oidc-client-id", "gitlab-analytics",
                "kdps.clemlab.com/oidc-realm", "corp",
                "kdps.clemlab.com/oidc-context", "ext",
                "kdps.clemlab.com/oidc-source", "internal",
                "kdps.clemlab.com/oidc-provider-secret", "gitlab-omniauth-provider",
                "kdps.clemlab.com/oidc-vault-path", "secret/data/oidc/gitlab"), annotations.getValue());
        // GitLab's provider Secret embeds the client secret too: labelled and annotated the same way.
        verify(k8s).createOrUpdateOpaqueSecret(eq(NS), eq("gitlab-omniauth-provider"), anyMap(),
                eq(labels.getValue()), eq(annotations.getValue()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSecretOfAnExternalProfileSaysSo() throws Exception {
        Map<String, Object> external = new LinkedHashMap<>();
        external.put("oidcExternalClientId", "bi-external");
        external.put("oidcExternalClientSecret", "Profile-Secret");
        external.put("oidcExternalIssuerUrl", "https://login.example.org/tenant/v2.0");
        external.put("_platformContextId", "");
        runCreateSecretStep(external);

        ArgumentCaptor<Map<String, String>> annotations = ArgumentCaptor.forClass(Map.class);
        verify(k8s).createOrUpdateOpaqueSecret(eq(NS), eq("gitlab-oidc-client"), anyMap(), any(),
                annotations.capture());
        assertEquals("external", annotations.getValue().get("kdps.clemlab.com/oidc-source"));
        assertEquals("bi-external", annotations.getValue().get("kdps.clemlab.com/oidc-client-id"));
        assertEquals("default", annotations.getValue().get("kdps.clemlab.com/oidc-context"));
        assertNull(annotations.getValue().get("kdps.clemlab.com/oidc-realm"), "not a Keycloak issuer");
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), eq("gitlab-omniauth-provider"), anyMap(), any(),
                any());
    }

    @Test
    void realmsAreReadFromKeycloakIssuers() {
        assertEquals("odp", CommandService.realmOfIssuer("https://sso.example.org/realms/odp"));
        assertEquals("corp", CommandService.realmOfIssuer("https://sso.example.org/auth/realms/corp/"));
        assertNull(CommandService.realmOfIssuer("https://login.example.org/tenant/v2.0"));
        assertNull(CommandService.realmOfIssuer(null));
        assertTrue(new ArrayList<>(CommandService.oidcSecretLabels("").keySet()).equals(List.of(
                "app.kubernetes.io/managed-by", "ambari.clemlab.com/type")), "no empty instance label");
    }
}
