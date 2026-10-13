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
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.apache.ambari.view.URLStreamProvider;
import org.apache.ambari.view.cluster.Cluster;
import org.apache.ambari.view.k8s.dto.security.OidcConfig;
import org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.model.ContextRequest;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.store.SecurityProfileEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.ws.rs.core.MultivaluedHashMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Regenerate keytabs", "Re-register OIDC client" and "Reapply Ranger repository" run against the platform context a
 * release was deployed on, not the cluster this Ambari manages; they refuse, with a reason the Releases page shows,
 * what the context cannot do. Re-registering an OIDC client plans what a deploy plans (flow flags, GitLab's OmniAuth
 * provider, the redirect URI from the deployed values), and a regeneration really issues a new keytab, which every
 * Secret of the shared principal then gets.
 */
class CommandServiceReleaseRepairActionsTest {

    private static final String NS = "analytics";
    private static final String CONTEXT = "cdp-prod";
    private static final URI BASE_URI = URI.create("http://ambari.test:8080/api/v1/");
    private static final String TRINO_PRINCIPAL = "trino-analytics@EXAMPLE.COM";

    @TempDir
    Path workDir;

    private final Gson gson = new Gson();
    private PerUserViewContext view;
    private KubernetesService k8s;
    private CommandService service;
    private FakeAmbari ambari;
    private FakeKeycloak keycloak;
    private FakeSecrets secrets;
    /** Deployed Helm values by release name. */
    private final Map<String, Map<String, Object>> deployedValues = new ConcurrentHashMap<>();

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

    @BeforeEach
    void setUp() throws Exception {
        view = new PerUserViewContext("repair-" + Long.toHexString(System.nanoTime()), workDir);
        ambari = new FakeAmbari();
        when(view.context.getURLStreamProvider()).thenReturn(ambari.stream);
        keycloak = new FakeKeycloak();
        secrets = new FakeSecrets();

        k8s = mock(KubernetesService.class);
        ViewConfigurationService config = mock(ViewConfigurationService.class);
        Map<String, String> cacheFiles = new ConcurrentHashMap<>();
        doAnswer(inv -> {
            String path = workDir.resolve(inv.getArgument(0, String.class)).toString();
            cacheFiles.put(path, inv.getArgument(1, String.class));
            return path;
        }).when(config).writeCacheFile(anyString(), anyString());
        doAnswer(inv -> cacheFiles.get(inv.getArgument(0, String.class))).when(config).readCacheFile(anyString());
        doAnswer(inv -> config).when(k8s).getConfigurationService();
        doAnswer(inv -> deployedValues.getOrDefault(inv.getArgument(1, String.class), Map.of()))
                .when(k8s).getHelmReleaseValues(anyString(), anyString());
        secrets.wire(k8s);

        service = new CommandService(view.context, mock(HelmService.class), k8s);
        service.startupSweep.get(30, TimeUnit.SECONDS);
        service.keytabLogin = (keytab, principal) -> {
            throw new AssertionError("no KDC login expected");
        };
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        keycloak.close();
    }

    // ------------------------------------------------------------------ OIDC

    @Test
    void openMetadataIsRegisteredWithItsImplicitFlowInTheKeycloakOfItsContext() throws Exception {
        externalContext(true, false);
        release("openmetadata", "OPENMETADATA", CONTEXT, null);
        deployedValues.put("openmetadata", values(
                "global", Map.of("security", Map.of("auth", Map.of("mode", "oidc"))),
                "ingress", Map.of("enabled", true,
                        "hosts", List.of(Map.of("host", "om.apps.example.com")),
                        "tls", List.of(Map.of("secretName", "openmetadata-ingress-tls")))));
        secrets.put(NS, "openmetadata-oidc-client", "client_secret", "kept-secret".getBytes(StandardCharsets.UTF_8));

        String id = service.planReleaseOidcRegistration(NS, "openmetadata", new MultivaluedHashMap<>(), BASE_URI);

        Map<String, Object> register = paramsOf(step(id, CommandType.OIDC_REGISTER_CLIENT));
        assertEquals(true, register.get("implicitFlowEnabled"));
        assertEquals(true, register.get("standardFlowEnabled"));
        assertEquals(CONTEXT, register.get("_platformContextId"));
        assertEquals("https://om.apps.example.com/callback", register.get("oidcRedirectUri"));

        // Run: the TLS pre-flight sees the deployed ingress TLS, and Keycloak keeps the flow and redirect URIs.
        service.runNextStep(id);
        awaitState(id, CommandState.SUCCEEDED);
        JsonObject client = keycloak.lastClient;
        assertNotNull(client, "the client was written to the context's Keycloak");
        assertTrue(client.get("implicitFlowEnabled").getAsBoolean());
        assertEquals("https://om.apps.example.com/callback", client.getAsJsonArray("redirectUris").get(0).getAsString());
        assertEquals("kept-secret", new String(secrets.get(NS, "openmetadata-oidc-client", "client_secret"),
                StandardCharsets.UTF_8));
        // The managed cluster's oidc-env is never read, and the unchanged secret needs no restart.
        assertTrue(ambari.requests.stream().noneMatch(url -> url.contains("oidc-env")), ambari.requests.toString());
        assertFalse(ReleaseMetadataService.restartMarked(new ReleaseMetadataService(view.context).find(NS, "openmetadata")));
    }

    @Test
    void reRegistrationKeepsTheAudiencesAndStoresNoClientSecretInTheResult() throws Exception {
        externalContext(true, false);
        release("openmetadata", "OPENMETADATA", CONTEXT, null);
        deployedValues.put("openmetadata", values(
                "global", Map.of("security", Map.of("auth", Map.of("mode", "oidc"))),
                "ingress", Map.of("hosts", List.of(Map.of("host", "om.apps.example.com")),
                        "tls", List.of(Map.of("secretName", "openmetadata-ingress-tls")))));

        String id = service.planReleaseOidcRegistration(NS, "openmetadata", new MultivaluedHashMap<>(), BASE_URI);
        CommandEntity register = step(id, CommandType.OIDC_REGISTER_CLIENT);
        // A re-registration plans no audiences (the registration keeps the client's) and touches no other client.
        assertFalse(paramsOf(register).containsKey("oidcAudiences"), paramsOf(register).toString());
        assertFalse(paramsOf(register).containsKey("oidcAudienceCallers"), paramsOf(register).toString());

        service.runNextStep(id);
        awaitState(id, CommandState.SUCCEEDED);

        // The audience mapper the client had is sent back with the update, which Keycloak applies as the whole set.
        JsonObject client = keycloak.lastClient;
        assertNotNull(client, "the client was written to the context's Keycloak");
        List<String> audiences = new ArrayList<>();
        for (var mapper : client.getAsJsonArray("protocolMappers")) {
            JsonObject m = mapper.getAsJsonObject();
            if (!"oidc-audience-mapper".equals(m.get("protocolMapper").getAsString())) continue;
            audiences.add(m.getAsJsonObject("config").get("included.custom.audience").getAsString());
        }
        assertEquals(List.of("trino-analytics"), audiences);

        // The secret reached the release's Secret; no step result, nor the view's settings, keep it.
        assertEquals("kept-secret", new String(secrets.get(NS, "openmetadata-oidc-client", "client_secret"),
                StandardCharsets.UTF_8));
        for (CommandEntity s : steps(id)) {
            CommandStatusEntity st = view.dataStore.find(CommandStatusEntity.class, s.getCommandStatusId());
            String result = st == null ? null : st.getResultJson();
            assertFalse(result != null && result.contains("kept-secret"), s.getType() + " result: " + result);
            assertFalse(result != null && result.contains(OidcClientSecrets.RESULT_KEY), s.getType() + " result: " + result);
        }
        CommandStatusEntity registered = view.dataStore.find(CommandStatusEntity.class, register.getCommandStatusId());
        assertEquals("openmetadata-analytics",
                gson.fromJson(registered.getResultJson(), JsonObject.class).get("clientId").getAsString());
        assertNull(service.oidcClientSecrets().read(register.getId()), "the kept secret is deleted once written");
    }

    @Test
    void aNewClientSecretFlagsTheReleaseForARestart() throws Exception {
        externalContext(true, false);
        release("openmetadata", "OPENMETADATA", CONTEXT, null);
        deployedValues.put("openmetadata", values(
                "global", Map.of("security", Map.of("auth", Map.of("mode", "oidc"))),
                "ingress", Map.of("hosts", List.of(Map.of("host", "om.apps.example.com")),
                        "tls", List.of(Map.of("secretName", "openmetadata-ingress-tls")))));
        secrets.put(NS, "openmetadata-oidc-client", "client_secret", "secret-of-a-deleted-client".getBytes(StandardCharsets.UTF_8));

        String id = service.planReleaseOidcRegistration(NS, "openmetadata", new MultivaluedHashMap<>(), BASE_URI);
        service.runNextStep(id);
        awaitState(id, CommandState.SUCCEEDED);

        assertTrue(ReleaseMetadataService.restartMarked(new ReleaseMetadataService(view.context).find(NS, "openmetadata")));
    }

    @Test
    void gitLabGetsItsOmniauthProviderSecretAndACompleteCallback() {
        externalContext(true, false);
        release("gitlab", "GITLAB", CONTEXT, null);
        deployedValues.put("gitlab", values("gitlab", Map.of("global", Map.of(
                "hosts", Map.of("domain", "example.com"),
                "appConfig", Map.of("omniauth", Map.of("enabled", "true"))))));

        String id = service.planReleaseOidcRegistration(NS, "gitlab", new MultivaluedHashMap<>(), BASE_URI);

        Map<String, Object> register = paramsOf(step(id, CommandType.OIDC_REGISTER_CLIENT));
        assertEquals("true", register.get("oidcGitlabOmniauth"));
        assertEquals("gitlab-omniauth-provider", register.get("oidcProviderSecretName"));
        assertEquals("https://gitlab.example.com/users/auth/openid_connect/callback", register.get("oidcRedirectUri"));
        assertEquals(register.get("oidcRedirectUri"), register.get("oidcGitlabCallbackUrl"));
        assertFalse(String.valueOf(register.get("oidcRedirectUri")).contains("{{"));
    }

    @Test
    void jupyterHubRedirectIsReadFromTheDeployedIngressHost() {
        externalContext(true, false);
        release("jupyterhub", "JUPYTERHUB", CONTEXT, null);
        deployedValues.put("jupyterhub", values(
                "global", Map.of("security", Map.of("auth", Map.of("mode", "oidc"))),
                "ingress", Map.of("hosts", List.of("jupyter.apps.example.com"))));

        String id = service.planReleaseOidcRegistration(NS, "jupyterhub", new MultivaluedHashMap<>(), BASE_URI);

        assertEquals("https://jupyter.apps.example.com/hub/oauth_callback",
                paramsOf(step(id, CommandType.OIDC_REGISTER_CLIENT)).get("oidcRedirectUri"));
    }

    @Test
    void aRedirectUriMissingAHostIsRefusedAndKeycloakIsLeftAlone() {
        externalContext(true, false);
        release("jupyterhub", "JUPYTERHUB", CONTEXT, null);
        deployedValues.put("jupyterhub", values("global", Map.of("security", Map.of("auth", Map.of("mode", "oidc")))));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.planReleaseOidcRegistration(NS, "jupyterhub", new MultivaluedHashMap<>(), BASE_URI));

        assertTrue(refused.getMessage().contains("{{jupyterHost}}"), refused.getMessage());
        assertTrue(view.dataStore.findAll(CommandEntity.class, null).isEmpty(), "nothing is planned");
    }

    @Test
    void aContextWithoutKeycloakCredentialsIsRefusedOnAnAmbariWithoutCluster() {
        externalContext(false, false);
        release("superset", "SUPERSET", CONTEXT, null);
        deployedValues.put("superset", values("global", Map.of("security", Map.of("auth", Map.of("mode", "oidc")))));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.planReleaseOidcRegistration(NS, "superset", new MultivaluedHashMap<>(), BASE_URI));

        assertTrue(refused.getMessage().contains("no Keycloak administrator credentials"), refused.getMessage());
        assertTrue(ambari.requests.isEmpty(), "the managed cluster is not asked: " + ambari.requests);
    }

    @Test
    void aReleaseDeployedWithoutOidcIsRefused() {
        externalContext(true, false);
        release("superset", "SUPERSET", CONTEXT, null);
        deployedValues.put("superset", values("global", Map.of("security", Map.of("auth", Map.of("mode", "ldap")))));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.planReleaseOidcRegistration(NS, "superset", new MultivaluedHashMap<>(), BASE_URI));

        assertTrue(refused.getMessage().contains("deployed without OIDC login"), refused.getMessage());
    }

    @Test
    void anExternalOidcProfilePlansOnlyTheStepWritingItsCredentials() throws Exception {
        externalContext(false, false);
        externalOidcProfile("corp-sso");
        release("superset", "SUPERSET", CONTEXT, "corp-sso");
        deployedValues.put("superset", values("global", Map.of("security", Map.of("auth", Map.of("mode", "oidc")))));

        String id = service.planReleaseOidcRegistration(NS, "superset", new MultivaluedHashMap<>(), BASE_URI);

        List<CommandEntity> steps = steps(id);
        assertEquals(1, steps.size(), "no Keycloak registration");
        assertEquals(CommandType.OIDC_CREATE_SECRET.name(), steps.get(0).getType());
        Map<String, Object> params = paramsOf(steps.get(0));
        assertEquals("superset-client", params.get("oidcExternalClientId"));
        assertEquals("corp-sso", params.get(CommandService.OIDC_EXTERNAL_PROFILE_PARAM));
        assertEquals(CONTEXT, params.get("_platformContextId"));
    }

    // ------------------------------------------------------------------ Ranger

    @Test
    void rangerReapplyOnAnExternalContextUsesItsRangerWithoutAnAmbariCluster() {
        externalContext(false, true);
        release("trino", "TRINO", CONTEXT, null);

        String id = service.planReleaseRangerRepositoryReapply(NS, "trino", new MultivaluedHashMap<>(), BASE_URI);

        Map<String, Object> params = paramsOf(step(id, CommandType.RANGER_REPOSITORY_CREATION));
        assertEquals(CONTEXT, params.get("_platformContextId"));
        assertNull(params.get("_cluster"));
        assertTrue(ambari.requests.isEmpty(), "the managed cluster is not asked: " + ambari.requests);
    }

    @Test
    void rangerReapplyIsRefusedForAContextWithoutRangerCredentials() {
        externalContext(true, false);
        release("trino", "TRINO", CONTEXT, null);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.planReleaseRangerRepositoryReapply(NS, "trino", new MultivaluedHashMap<>(), BASE_URI));

        assertTrue(refused.getMessage().contains("no Ranger administrator credentials"), refused.getMessage());
    }

    // ------------------------------------------------------------------ keytabs

    @Test
    void keytabRegenerationIsRefusedForAReleaseOnAnotherContext() {
        externalContext(true, true);
        release("trino", "TRINO", CONTEXT, null);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.planReleaseKeytabRegeneration(NS, "trino", new MultivaluedHashMap<>(), BASE_URI));

        assertTrue(refused.getMessage().contains("KDPS does not issue its keytabs"), refused.getMessage());
        assertTrue(ambari.requests.isEmpty(), "no keytab is issued: " + ambari.requests);
    }

    @Test
    void aRegenerationIssuesANewKeytabAndRewritesTheOtherReleasesOfThePrincipal() throws Exception {
        managedKerberizedCluster();
        release("trino", "TRINO", null, null);
        release("trino-etl", "TRINO", null, null);
        byte[] old = keytab(TRINO_PRINCIPAL, 4);
        secrets.putKeytab(NS, "trino-keytab", "trino", TRINO_PRINCIPAL, old);
        secrets.putKeytab(NS, "trino-etl-keytab", "trino-etl", TRINO_PRINCIPAL, old);
        byte[] issued = keytab(TRINO_PRINCIPAL, 5);
        ambari.issues(issued);

        String id = service.planReleaseKeytabRegeneration(NS, "trino", new MultivaluedHashMap<>(), BASE_URI);
        assertEquals(2, steps(id).size(), "the coordinator and the workers share one keytab, issued once");
        service.runNextStep(id);
        awaitState(id, CommandState.SUCCEEDED);

        assertEquals(1, ambari.issued.get(), "issued by the KDC although the Secret held a keytab");
        assertArrayEquals(issued, secrets.get(NS, "trino-keytab", "service.keytab"));
        assertArrayEquals(issued, secrets.get(NS, "trino-etl-keytab", "service.keytab"),
                "the other release of the principal gets the new key");
        ReleaseMetadataService records = new ReleaseMetadataService(view.context);
        assertTrue(ReleaseMetadataService.restartMarked(records.find(NS, "trino")));
        assertTrue(ReleaseMetadataService.restartMarked(records.find(NS, "trino-etl")));
        assertEquals(CommandService.keytabPrincipalLabel(TRINO_PRINCIPAL),
                secrets.labels(NS, "trino-keytab").get(CommandService.KEYTAB_PRINCIPAL_LABEL));
    }

    @Test
    void aStoredKeytabTheKdcRejectsIsIssuedAgain() throws Exception {
        managedKerberizedCluster();
        secrets.putKeytab(NS, "trino-keytab", "trino", TRINO_PRINCIPAL, keytab(TRINO_PRINCIPAL, 4));
        ambari.issues(keytab(TRINO_PRINCIPAL, 5));
        AtomicInteger logins = new AtomicInteger();
        service.keytabLogin = (keytab, principal) -> {
            logins.incrementAndGet();
            return false;
        };

        String id = keytabSteps("trino");
        service.runNextStep(id);
        awaitState(id, CommandState.SUCCEEDED);

        assertEquals(1, logins.get(), "one login only");
        assertEquals(1, ambari.issued.get());
        assertEquals(5, CommandService.keytabKeyVersion(secrets.get(NS, "trino-keytab", "service.keytab"), TRINO_PRINCIPAL));
        // The running pods hold the rejected keytab: the deploy restarts them once Helm has applied it.
        assertTrue(ReleaseMetadataService.restartMarked(new ReleaseMetadataService(view.context).find(NS, "trino")));
    }

    @Test
    void aRegenerationWhoseFirstAcknowledgementFailsStillFlagsTheReleases() throws Exception {
        managedKerberizedCluster();
        release("trino", "TRINO", null, null);
        release("trino-etl", "TRINO", null, null);
        byte[] old = keytab(TRINO_PRINCIPAL, 4);
        secrets.putKeytab(NS, "trino-keytab", "trino", TRINO_PRINCIPAL, old);
        secrets.putKeytab(NS, "trino-etl-keytab", "trino-etl", TRINO_PRINCIPAL, old);
        byte[] issued = keytab(TRINO_PRINCIPAL, 5);
        ambari.issues(issued);
        ambari.failedAcks.set(1);

        String id = service.planReleaseKeytabRegeneration(NS, "trino", new MultivaluedHashMap<>(), BASE_URI);
        service.runNextStep(id);
        awaitState(id, CommandState.SUCCEEDED);

        assertEquals(0, ambari.failedAcks.get(), "the first acknowledgement failed and the step was retried");
        assertArrayEquals(issued, secrets.get(NS, "trino-keytab", "service.keytab"));
        assertArrayEquals(issued, secrets.get(NS, "trino-etl-keytab", "service.keytab"));
        ReleaseMetadataService records = new ReleaseMetadataService(view.context);
        assertTrue(ReleaseMetadataService.restartMarked(records.find(NS, "trino")),
                "the retry finds the new keytab in the Secret, yet knows it replaced the old one");
        assertTrue(ReleaseMetadataService.restartMarked(records.find(NS, "trino-etl")));
    }

    @Test
    void aDeployThatCannotAcknowledgeTheKeytabStillFlagsTheReleaseItRekeyed() throws Exception {
        managedKerberizedCluster();
        secrets.putKeytab(NS, "trino-keytab", "trino", TRINO_PRINCIPAL, keytab(TRINO_PRINCIPAL, 4));
        ambari.issues(keytab(TRINO_PRINCIPAL, 5));
        ambari.failedAcks.set(Integer.MAX_VALUE);
        service.keytabLogin = (keytab, principal) -> false;

        String id = keytabSteps("trino");
        service.runNextStep(id);
        awaitRetry(id + "-create");

        assertEquals(5, CommandService.keytabKeyVersion(secrets.get(NS, "trino-keytab", "service.keytab"), TRINO_PRINCIPAL));
        assertTrue(ReleaseMetadataService.restartMarked(new ReleaseMetadataService(view.context).find(NS, "trino")),
                "the KDC changed the key and the Secret holds the new one: the running pods must be restarted, even"
                        + " if the operation fails");
    }

    @Test
    void aRetryThatFindsItsOwnKeytabInTheSecretStillFlagsTheRelease() throws Exception {
        managedKerberizedCluster();
        secrets.putKeytab(NS, "trino-keytab", "trino", TRINO_PRINCIPAL, keytab(TRINO_PRINCIPAL, 4));
        ambari.issues(keytab(TRINO_PRINCIPAL, 5));
        service.keytabLogin = (keytab, principal) -> false;
        // The Secret is written, then reading it back fails: the step is retried with the new keytab in place.
        secrets.failedReadsAfterWrite.set(1);

        String id = keytabSteps("trino");
        service.runNextStep(id);
        awaitState(id, CommandState.SUCCEEDED);

        assertEquals(0, secrets.failedReadsAfterWrite.get(), "the step was retried");
        assertTrue(ReleaseMetadataService.restartMarked(new ReleaseMetadataService(view.context).find(NS, "trino")),
                "the issuer recorded the keytab the Secret held before, so the retry knows it was replaced");
    }

    @Test
    void aKeytabAnotherReleaseOfTheNamespaceHoldsIsCopiedNotIssued() throws Exception {
        managedKerberizedCluster();
        byte[] shared = keytab(TRINO_PRINCIPAL, 7);
        secrets.putKeytab(NS, "trino-keytab", "trino", TRINO_PRINCIPAL, shared);
        service.keytabLogin = (keytab, principal) -> true;

        String id = keytabSteps("trino-etl");
        service.runNextStep(id);
        awaitState(id, CommandState.SUCCEEDED);

        assertEquals(0, ambari.issued.get(), "the KDC is not asked: the key stays the one trino uses");
        assertArrayEquals(shared, secrets.get(NS, "trino-etl-keytab", "service.keytab"));
        assertEquals("trino-etl", secrets.labels(NS, "trino-etl-keytab").get(CommandService.KEYTAB_RELEASE_LABEL));
        assertFalse(ReleaseMetadataService.restartMarked(new ReleaseMetadataService(view.context).find(NS, "trino-etl")),
                "a keytab written where there was none needs no restart");
    }

    @Test
    void aRegenerationAlsoRewritesTheKeytabsOfReleasesDeployedBeforeSecretsWereLabelled() throws Exception {
        managedKerberizedCluster();
        release("trino", "TRINO", null, null);
        release("trino-etl", "TRINO", null, null);
        byte[] old = keytab(TRINO_PRINCIPAL, 4);
        secrets.putUnlabelledKeytab(NS, "trino-keytab", old);
        secrets.putUnlabelledKeytab(NS, "trino-etl-keytab", old);
        byte[] issued = keytab(TRINO_PRINCIPAL, 5);
        ambari.issues(issued);

        String id = service.planReleaseKeytabRegeneration(NS, "trino", new MultivaluedHashMap<>(), BASE_URI);
        service.runNextStep(id);
        awaitState(id, CommandState.SUCCEEDED);

        assertArrayEquals(issued, secrets.get(NS, "trino-keytab", "service.keytab"));
        assertArrayEquals(issued, secrets.get(NS, "trino-etl-keytab", "service.keytab"),
                "the unlabelled Secret of the other release gets the new key");
        assertTrue(ReleaseMetadataService.restartMarked(new ReleaseMetadataService(view.context).find(NS, "trino-etl")),
                "the other release is found from its record and flagged");
        assertEquals(CommandService.keytabPrincipalLabel(TRINO_PRINCIPAL),
                secrets.labels(NS, "trino-etl-keytab").get(CommandService.KEYTAB_PRINCIPAL_LABEL));
        assertEquals("trino-etl", secrets.labels(NS, "trino-etl-keytab").get(CommandService.KEYTAB_RELEASE_LABEL));
    }

    @Test
    void aKeytabInAnUnlabelledSecretOfAnotherReleaseIsCopiedNotIssued() throws Exception {
        managedKerberizedCluster();
        byte[] shared = keytab(TRINO_PRINCIPAL, 7);
        secrets.putUnlabelledKeytab(NS, "trino-keytab", shared);
        secrets.put(NS, "trino-oidc-client", "client_secret", "not a keytab".getBytes(StandardCharsets.UTF_8));
        service.keytabLogin = (keytab, principal) -> true;

        String id = keytabSteps("trino-etl");
        service.runNextStep(id);
        awaitState(id, CommandState.SUCCEEDED);

        assertEquals(0, ambari.issued.get(), "the KDC is not asked: the key stays the one trino uses");
        assertArrayEquals(shared, secrets.get(NS, "trino-etl-keytab", "service.keytab"));
        assertTrue(secrets.labels(NS, "trino-oidc-client").isEmpty(), "a Secret holding no keytab is left alone");
    }

    @Test
    void aPrincipalTheKdcNoLongerKnowsIsIssuedAgainNotCopiedFromAnotherRelease() throws Exception {
        managedKerberizedCluster();
        // Both releases hold the same key of a principal the KDC dropped (rebuilt KDC, cleanup after an uninstall).
        secrets.putKeytab(NS, "trino-keytab", "trino", TRINO_PRINCIPAL, keytab(TRINO_PRINCIPAL, 4));
        secrets.putKeytab(NS, "trino-etl-keytab", "trino-etl", TRINO_PRINCIPAL, keytab(TRINO_PRINCIPAL, 4));
        ambari.issues(keytab(TRINO_PRINCIPAL, 1));
        AtomicInteger logins = new AtomicInteger();
        service.keytabLogin = (keytab, principal) -> {
            logins.incrementAndGet();
            return CommandService.failedKeytabLoginVerdict(kdcFailure("Client not found in Kerberos database (6)"));
        };

        String id = keytabSteps("trino");
        service.runNextStep(id);
        awaitState(id, CommandState.SUCCEEDED);

        assertEquals(1, logins.get(), "one login only");
        assertEquals(1, ambari.issued.get(), "the KDC is asked to create the principal again");
        assertEquals(1, CommandService.keytabKeyVersion(secrets.get(NS, "trino-keytab", "service.keytab"), TRINO_PRINCIPAL));
    }

    @Test
    void aFailedKeytabLoginIsRejectedOnlyWhenTheKdcAnswersAgainstTheKeytab() {
        for (String rejected : new String[]{
                "Pre-authentication information was invalid (24)",
                "Integrity check on decrypted field failed (31)",
                "Checksum failed",
                "Specified version of key is not available (44)",
                "Client not found in Kerberos database (6)",
                "KDC_ERR_C_PRINCIPAL_UNKNOWN"}) {
            assertEquals(Boolean.FALSE, CommandService.failedKeytabLoginVerdict(kdcFailure(rejected)), rejected);
        }
        for (String unknown : new String[]{
                "Cannot locate default realm",
                "Receive timed out",
                "Clock skew too great (37)",
                null}) {
            assertNull(CommandService.failedKeytabLoginVerdict(kdcFailure(unknown)), String.valueOf(unknown));
        }
    }

    /** A login failure as Krb5LoginModule reports it: the KDC's error is the cause of the LoginException. */
    private static javax.security.auth.login.LoginException kdcFailure(String kdcError) {
        javax.security.auth.login.LoginException failure = new javax.security.auth.login.LoginException("Login failed");
        failure.initCause(new Exception(kdcError));
        return failure;
    }

    @Test
    void aKeytabThatCannotBeReadDoesNotMatchAnyPrincipal() {
        assertFalse(CommandService.keytabContainsPrincipal("not a keytab".getBytes(StandardCharsets.UTF_8),
                TRINO_PRINCIPAL));
        byte[] truncated = java.util.Arrays.copyOf(keytab(TRINO_PRINCIPAL, 3), 20);
        assertFalse(CommandService.keytabContainsPrincipal(truncated, TRINO_PRINCIPAL));
        assertNull(CommandService.keytabKeyVersion(truncated, TRINO_PRINCIPAL));
        assertTrue(CommandService.keytabContainsPrincipal(keytab(TRINO_PRINCIPAL, 3), TRINO_PRINCIPAL));
        assertEquals(-1, CommandService.keytabKeyVersion(keytab(TRINO_PRINCIPAL, 3), "other-analytics@EXAMPLE.COM"));
        assertEquals(300, CommandService.keytabKeyVersion(keytab(TRINO_PRINCIPAL, 300), TRINO_PRINCIPAL),
                "the 32-bit key version wins over the 8-bit one");
    }

    // ------------------------------------------------------------------ availability

    @Test
    void theReleasesPageIsToldWhichActionsTheContextCannotPerformAndWhy() {
        externalContext(false, false);
        release("trino", "TRINO", CONTEXT, null);
        deployedValues.put("trino", values("global", Map.of("security", Map.of("auth", Map.of("mode", "oidc")))));

        Map<String, String> refusals = service.releaseActionRefusals(NS, "trino", new MultivaluedHashMap<>(), BASE_URI);

        assertEquals(List.of("keytabs", "oidc", "ranger"), new ArrayList<>(refusals.keySet()));
        assertTrue(refusals.get("keytabs").contains("KDPS does not issue its keytabs"));
        assertTrue(refusals.get("oidc").contains("no Keycloak administrator credentials"));
        assertTrue(refusals.get("ranger").contains("no Ranger administrator credentials"));
    }

    @Test
    void aContextThatCanPerformAnActionLeavesItAvailable() {
        externalContext(true, true);
        release("trino", "TRINO", CONTEXT, null);
        deployedValues.put("trino", values("global", Map.of("security", Map.of("auth", Map.of("mode", "oidc")))));

        Map<String, String> refusals = service.releaseActionRefusals(NS, "trino", new MultivaluedHashMap<>(), BASE_URI);

        assertEquals(List.of("keytabs"), new ArrayList<>(refusals.keySet()));
    }

    // ------------------------------------------------------------------ fixtures

    /** An external platform context, with the Keycloak and Ranger administrator credentials asked for. */
    private void externalContext(boolean keycloakAdmin, boolean rangerAdmin) {
        view.as("admin");
        ContextRequest context = new ContextRequest();
        context.id = CONTEXT;
        context.name = "CDP production";
        context.kind = ContextService.KIND_EXTERNAL;
        context.config = new LinkedHashMap<>();
        context.secrets = new LinkedHashMap<>();
        context.config.put("issuerUrl", keycloak.url() + "/realms/odp");
        context.config.put("realm", "odp");
        if (keycloakAdmin) {
            context.config.put("adminClientId", "kdps-admin");
            context.secrets.put("adminClientSecret", "Keycloak-Admin-Secret");
        }
        if (rangerAdmin) {
            context.config.put("rangerUrl", "http://ranger.cdp.example.com:6080");
            context.config.put("rangerAdminUsername", "admin");
            context.secrets.put("rangerAdminPassword", "Ranger-Pass");
        }
        new ContextService(view.context).save(context);
        view.as(null);
    }

    /** A security profile whose OIDC client was registered by the operator, outside KDPS. */
    private void externalOidcProfile(String name) throws Exception {
        SecurityConfigDTO cfg = new SecurityConfigDTO();
        cfg.mode = "oidc";
        cfg.oidc = new OidcConfig();
        cfg.oidc.source = "external";
        cfg.oidc.clientId = "superset-client";
        cfg.oidc.clientSecret = "Operator-Secret";
        cfg.oidc.issuerUrl = "https://sso.example.com/realms/corp";
        SecurityProfileEntity entity = new SecurityProfileEntity();
        entity.setId(name);
        entity.setViewInstance(view.context.getInstanceName());
        entity.setProfileJson(new ObjectMapper().writeValueAsString(cfg));
        view.dataStore.store(entity);
    }

    /** Ambari manages cluster c1, Kerberized in realm EXAMPLE.COM. */
    private void managedKerberizedCluster() {
        Cluster cluster = mock(Cluster.class);
        when(cluster.getName()).thenReturn("c1");
        when(view.context.getCluster()).thenReturn(cluster);
        ambari.configs.put("cluster-env", Map.of("security_enabled", "true"));
        ambari.configs.put("kerberos-env", Map.of("realm", "EXAMPLE.COM"));
    }

    private void release(String name, String serviceKey, String contextId, String securityProfile) {
        K8sReleaseEntity release = new K8sReleaseEntity();
        release.setId(K8sReleaseEntity.idOf(NS, name));
        release.setNamespace(NS);
        release.setReleaseName(name);
        release.setServiceKey(serviceKey);
        release.setManagedByUi(true);
        release.setGlobalConfigVersion("fingerprint");
        if (contextId != null) release.setPlatformContextId(contextId);
        if (securityProfile != null) release.setSecurityProfile(securityProfile);
        view.dataStore.store(release);
    }

    /** Stores the keytab steps a deploy plans for Trino release {@code name} (no regeneration asked) and returns the root. */
    private String keytabSteps(String name) {
        release(name, "TRINO", null, null);
        String id = "deploy-" + Long.toHexString(System.nanoTime());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("releaseName", name);
        params.put("namespace", NS);
        params.put("serviceKey", "TRINO");
        params.put("_cluster", "c1");
        params.put("_baseUri", BASE_URI.toString());
        params.put("_callerHeaders", Map.of());
        Map<String, Object> issue = new LinkedHashMap<>(params);
        issue.put("principalFqdn", TRINO_PRINCIPAL);
        issue.put("secretName", name + "-keytab");
        issue.put("keyNameInSecret", "service.keytab");
        Map<String, Object> create = new LinkedHashMap<>(issue);
        create.put("keytabIssuerId", id + "-issue");
        view.dataStore.store(command(id, CommandType.K8S_MANAGER_RELEASE_DEPLOY, params, List.of(id + "-issue", id + "-create")));
        view.dataStore.store(status(id, CommandState.RUNNING));
        view.dataStore.store(command(id + "-issue", CommandType.KEYTAB_ISSUE_PRINCIPAL, issue, null));
        view.dataStore.store(status(id + "-issue", CommandState.PENDING));
        view.dataStore.store(command(id + "-create", CommandType.KEYTAB_CREATE_SECRET, create, null));
        view.dataStore.store(status(id + "-create", CommandState.PENDING));
        return id;
    }

    private CommandEntity command(String id, CommandType type, Map<String, Object> params, List<String> children) {
        CommandEntity c = new CommandEntity();
        c.setId(id);
        c.setType(type.name());
        c.setTitle(type.name());
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

    private static Map<String, Object> values(Object... keysAndValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return values;
    }

    private List<CommandEntity> steps(String rootId) {
        CommandEntity root = view.dataStore.find(CommandEntity.class, rootId);
        assertNotNull(root, "operation " + rootId + " is stored");
        List<String> ids = gson.fromJson(root.getChildListJson(), new TypeToken<List<String>>() {}.getType());
        List<CommandEntity> steps = new ArrayList<>();
        for (String id : ids) steps.add(view.dataStore.find(CommandEntity.class, id));
        return steps;
    }

    private CommandEntity step(String rootId, CommandType type) {
        return steps(rootId).stream().filter(s -> type.name().equals(s.getType())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " step in " + rootId));
    }

    private Map<String, Object> paramsOf(CommandEntity step) {
        return gson.fromJson(CommandParamsStore.read(step.getParamsJson()),
                new TypeToken<Map<String, Object>>() {}.getType());
    }

    /** Waits until step {@code stepId} failed with an error it retries. */
    private void awaitRetry(String stepId) throws InterruptedException {
        CommandStatusEntity st = null;
        for (int i = 0; i < 300; i++) {
            st = view.dataStore.find(CommandStatusEntity.class, stepId + "-status");
            if (st != null && st.getAttempt() != null && st.getAttempt() > 0) return;
            Thread.sleep(50);
        }
        fail("step " + stepId + " is " + (st == null ? null : st.getState()) + ": " + (st == null ? null : st.getMessage()));
    }

    private void awaitState(String id, CommandState expected) throws InterruptedException {
        CommandStatusEntity st = null;
        for (int i = 0; i < 300; i++) {
            st = view.dataStore.find(CommandStatusEntity.class, id + "-status");
            if (st != null && expected.name().equals(st.getState())) return;
            if (st != null && CommandState.FAILED.name().equals(st.getState())) break;
            Thread.sleep(50);
        }
        fail("operation " + id + " is " + (st == null ? null : st.getState()) + ": " + (st == null ? null : st.getMessage()));
    }

    /** A keytab in the 0x0502 format holding one AES-256 key of {@code principal} at key version {@code kvno}. */
    static byte[] keytab(String principal, int kvno) {
        String primary = principal.substring(0, principal.indexOf('@'));
        String realm = principal.substring(principal.indexOf('@') + 1);
        String[] components = primary.split("/");
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) kvno);
        ByteBuffer entry = ByteBuffer.allocate(512);
        entry.putShort((short) components.length);
        putCounted(entry, realm);
        for (String c : components) putCounted(entry, c);
        entry.putInt(1);                      // name type
        entry.putInt(1_700_000_000);          // timestamp
        entry.put((byte) (kvno & 0xff));      // 8-bit key version
        entry.putShort((short) 18);           // aes256-cts-hmac-sha1-96
        entry.putShort((short) key.length);
        entry.put(key);
        entry.putInt(kvno);                   // 32-bit key version
        entry.flip();
        ByteBuffer out = ByteBuffer.allocate(2 + 4 + entry.remaining());
        out.put((byte) 0x05).put((byte) 0x02).putInt(entry.remaining()).put(entry);
        return out.array();
    }

    private static void putCounted(ByteBuffer buf, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        buf.putShort((short) b.length);
        buf.put(b);
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** The Secrets of the cluster, as the view writes and reads them. */
    static final class FakeSecrets {
        private final Map<String, Secret> byName = new ConcurrentHashMap<>();
        /** How many reads fail, as a transient error of the API server, right after a Secret was written. */
        final AtomicInteger failedReadsAfterWrite = new AtomicInteger();
        private volatile boolean justWritten;

        void wire(KubernetesService k8s) {
            doAnswer(inv -> {
                if (justWritten && failedReadsAfterWrite.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                    justWritten = false;
                    throw new IllegalStateException("connection reset by the API server");
                }
                justWritten = false;
                return Optional.ofNullable(get(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
            }).when(k8s).readOpaqueSecretKeyAsBytes(anyString(), anyString(), anyString());
            doAnswer(inv -> {
                Map<String, String> labels = inv.getArgument(4);
                write(inv.getArgument(0), inv.getArgument(1), Map.of(inv.getArgument(2), inv.getArgument(3)), labels);
                justWritten = true;
                return null;
            }).when(k8s).createOrUpdateOpaqueSecret(anyString(), anyString(), anyString(), any(byte[].class),
                    nullable(Map.class), nullable(Map.class), nullable(Boolean.class));
            doAnswer(inv -> {
                write(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), null);
                return null;
            }).when(k8s).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap());
            doAnswer(inv -> byName.get(inv.getArgument(0) + "/" + inv.getArgument(1)))
                    .when(k8s).getSecret(anyString(), anyString());
            doAnswer(inv -> {
                String ns = inv.getArgument(0);
                String key = inv.getArgument(1);
                String value = inv.getArgument(2);
                List<Secret> found = new ArrayList<>();
                byName.forEach((id, s) -> {
                    Map<String, String> labels = s.getMetadata().getLabels();
                    if (id.startsWith(ns + "/") && labels != null && value.equals(labels.get(key))) found.add(s);
                });
                return found;
            }).when(k8s).listSecretsWithLabel(anyString(), anyString(), anyString());
            doAnswer(inv -> {
                String ns = inv.getArgument(0);
                String key = inv.getArgument(1);
                List<Secret> found = new ArrayList<>();
                byName.forEach((id, s) -> {
                    Map<String, String> labels = s.getMetadata().getLabels();
                    if (id.startsWith(ns + "/") && (labels == null || !labels.containsKey(key))) found.add(s);
                });
                return found;
            }).when(k8s).listViewManagedSecretsWithoutLabel(anyString(), anyString());
        }

        void put(String ns, String name, String key, byte[] data) {
            write(ns, name, Map.of(key, data), null);
        }

        void putKeytab(String ns, String name, String release, String principal, byte[] keytab) {
            write(ns, name, Map.of("service.keytab", keytab), CommandService.keytabSecretLabels(release, principal));
        }

        /** A keytab Secret as an earlier version of the view wrote it: without labels. */
        void putUnlabelledKeytab(String ns, String name, byte[] keytab) {
            write(ns, name, Map.of("service.keytab", keytab), null);
        }

        byte[] get(String ns, String name, String key) {
            Secret s = byName.get(ns + "/" + name);
            String b64 = s == null || s.getData() == null ? null : s.getData().get(key);
            return b64 == null ? null : Base64.getDecoder().decode(b64);
        }

        Map<String, String> labels(String ns, String name) {
            Secret s = byName.get(ns + "/" + name);
            return s == null || s.getMetadata().getLabels() == null ? Map.of() : s.getMetadata().getLabels();
        }

        private void write(String ns, String name, Map<String, byte[]> data, Map<String, String> labels) {
            Map<String, String> encoded = new LinkedHashMap<>();
            data.forEach((k, v) -> encoded.put(k, Base64.getEncoder().encodeToString(v)));
            byName.put(ns + "/" + name, new SecretBuilder().withNewMetadata().withName(name).withNamespace(ns)
                    .withAnnotations(Map.of("managed-by", "ambari-k8s-view"))
                    .withLabels(labels == null ? new HashMap<>() : new HashMap<>(labels)).endMetadata()
                    .withData(encoded).build());
        }
    }

    /** The Ambari REST API the view reaches through the view context's URL stream provider. */
    static final class FakeAmbari {
        private static final Pattern DESIRED = Pattern.compile("desired_configs(?:%2F|/)([\\w-]+)");
        private static final Pattern CONFIG = Pattern.compile("/configurations\\?type=([\\w-]+)");

        final List<String> requests = new CopyOnWriteArrayList<>();
        final Map<String, Map<String, String>> configs = new ConcurrentHashMap<>();
        final AtomicInteger issued = new AtomicInteger();
        /** How many acknowledgements of the keytab payload fail, as a transient error of Ambari, before one succeeds. */
        final AtomicInteger failedAcks = new AtomicInteger();
        private volatile byte[] keytab;
        final URLStreamProvider stream = mock(URLStreamProvider.class, inv -> {
            if (!inv.getMethod().getName().startsWith("read")) return null;
            return answer(inv.getArgument(0, String.class), inv.getArgument(1, String.class));
        });

        void issues(byte[] keytab) {
            this.keytab = keytab;
        }

        private InputStream answer(String url, String method) throws Exception {
            requests.add(method + " " + url);
            String body;
            Matcher desired = DESIRED.matcher(url);
            Matcher config = CONFIG.matcher(url);
            if (url.endsWith("/adhoc_keytab") && "POST".equals(method)) {
                issued.incrementAndGet();
                body = "{\"Requests\":{\"id\":7}}";
            } else if (url.contains("/requests/7/tasks/1")) {
                body = "{\"Tasks\":{\"structured_out\":{\"payload_ref\":\"p1\",\"payload_sha256\":\""
                        + sha256(keytab) + "\"}}}";
            } else if (url.contains("/requests/7")) {
                body = "{\"Requests\":{\"request_status\":\"COMPLETED\"},\"tasks\":[{\"Tasks\":{\"id\":1}}]}";
            } else if (url.endsWith("/payloads/p1/read")) {
                body = Base64.getEncoder().encodeToString(keytab);
            } else if (url.endsWith("/payloads/p1/ack")) {
                if (failedAcks.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                    throw new IOException("HTTP 503 for " + method + " " + url);
                }
                body = "";
            } else if (desired.find()) {
                String type = desired.group(1);
                body = configs.containsKey(type)
                        ? "{\"Clusters\":{\"desired_configs\":{\"" + type + "\":{\"tag\":\"t1\"}}}}"
                        : "{\"Clusters\":{\"desired_configs\":{}}}";
            } else if (config.find()) {
                body = "{\"items\":[{\"properties\":" + new Gson().toJson(configs.getOrDefault(config.group(1), Map.of()))
                        + "}]}";
            } else {
                throw new IOException("HTTP 404 for " + method + " " + url);
            }
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The administration API of a Keycloak: one realm, clients created or updated, their secret read back. */
    static final class FakeKeycloak implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, JsonObject> clients = new ConcurrentHashMap<>();
        volatile JsonObject lastClient;

        FakeKeycloak() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String url() {
            return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.endsWith("/protocol/openid-connect/token")) {
                respond(exchange, 200, "{\"access_token\":\"token\"}");
            } else if (path.endsWith("/clients") && "GET".equals(method)) {
                // Every client exists already: a re-registration updates it.
                respond(exchange, 200, "[{\"id\":\"uuid-1\"}]");
            } else if (path.endsWith("/clients/uuid-1") && "PUT".equals(method)) {
                lastClient = JsonParser.parseString(body).getAsJsonObject();
                clients.put("uuid-1", lastClient);
                respond(exchange, 204, null);
            } else if (path.endsWith("/clients/uuid-1/client-secret")) {
                respond(exchange, 200, "{\"value\":\"kept-secret\"}");
            } else if (path.endsWith("/clients/uuid-1/protocol-mappers/models") && "GET".equals(method)) {
                // The audience its last deploy set (a Superset calling a Trino), and a mapper of another kind.
                respond(exchange, 200, "[{\"id\":\"m-1\",\"name\":\"audience trino-analytics\","
                        + "\"protocol\":\"openid-connect\",\"protocolMapper\":\"oidc-audience-mapper\","
                        + "\"config\":{\"included.custom.audience\":\"trino-analytics\","
                        + "\"access.token.claim\":\"true\"}},"
                        + "{\"id\":\"m-2\",\"name\":\"groups\",\"protocol\":\"openid-connect\","
                        + "\"protocolMapper\":\"oidc-group-membership-mapper\",\"config\":{}}]");
            } else {
                respond(exchange, 404, "{}");
            }
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body == null ? -1 : bytes.length);
            if (body != null) exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
