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
import com.google.gson.reflect.TypeToken;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.client.KeycloakAdminClient;
import org.apache.ambari.view.k8s.dto.security.OidcConfig;
import org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Trino's JWT authenticator used to accept a token of the realm whatever its audience, so a token issued to any
 * other application for a signed-in user authenticated as that user. A service that checks the audience
 * ({@code oidc[].jwtAudiencePath}) now requires its own client id, and the client KDPS registers for it puts that
 * audience in its tokens. A release calling a KDPS Trino with its own service token (Superset) gets that Trino's
 * audience in its tokens, so the connection keeps working.
 */
class CommandServiceOidcAudiencePlanTest {

    private static final String NS = "analytics";
    private static final String AUDIENCE_PATH = "global.security.auth.oidc.jwt.requiredAudience";
    private static final String TRINO_HOST = "trino-coordinator.analytics.svc.cluster.local:8443";

    @TempDir
    Path tmp;

    private final Gson gson = new Gson();
    private InMemoryDataStore dataStore;
    private ViewContext ctx;
    private KubernetesService k8s;
    private KubernetesClient client;
    private CommandService service;
    private String rel;

    @BeforeEach
    void setUp() throws Exception {
        String instance = "oidc-audience-plan-" + Long.toHexString(System.nanoTime());
        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getInstanceName()).thenReturn(instance);
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", Files.createDirectories(tmp.resolve("work")).toString()));
        when(ctx.getInstanceData(anyString())).thenReturn(null);
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
        dataStore = new InMemoryDataStore();
        when(ctx.getDataStore()).thenReturn(dataStore);

        HelmRepoEntity charts = new HelmRepoEntity();
        charts.setId("charts");
        charts.setName("charts");
        charts.setType("HTTP");
        charts.setUrl("https://charts.example.org");
        charts.setAuthMode("anonymous");
        dataStore.store(charts);

        Map<String, String> cache = new ConcurrentHashMap<>();
        ViewConfigurationService configuration = mock(ViewConfigurationService.class);
        when(configuration.writeCacheFile(anyString(), anyString())).thenAnswer(inv -> {
            String path = "cache/" + inv.getArgument(0);
            cache.put(path, inv.getArgument(1));
            return path;
        });
        when(configuration.readCacheFile(anyString())).thenAnswer(inv -> cache.get((String) inv.getArgument(0)));
        k8s = mock(KubernetesService.class);
        when(k8s.getConfigurationService()).thenReturn(configuration);
        client = mock(KubernetesClient.class);
        when(k8s.getClient()).thenReturn(client);

        HelmService helm = mock(HelmService.class, RETURNS_DEEP_STUBS);
        when(helm.getRepositoryService().showValuesAsMap(any(), any(), any())).thenReturn(new LinkedHashMap<>());
        when(helm.getRepositoryService().getEffectiveImageRegistry(any())).thenReturn("registry.example.org");

        service = new CommandService(ctx, helm, k8s);
        rel = "q-" + Long.toHexString(System.nanoTime());
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void aServiceCheckingTheAudienceRequiresItsOwnClientAndItsClientsTokensCarryIt() {
        profile("sso", oidc("internal", null));

        String rootId = plan("ZZTESTJWTAUDIENCE", "sso", Map.of());

        String clientId = rel + "-" + NS;
        assertEquals(clientId, overrides(rootId).get(AUDIENCE_PATH), "Trino accepts only tokens minted for its client");
        assertEquals(List.of(clientId), paramsOf(step(rootId, CommandType.OIDC_REGISTER_CLIENT)).get("oidcAudiences"),
                "the client KDPS registers puts its own id in its tokens");
    }

    @Test
    void anExternalProviderRequiresTheProfilesClientAndTheLogSaysTheProviderMustIssueIt() throws InterruptedException {
        profile("corp", oidc("external", "corp-analytics"));

        String rootId = plan("ZZTESTJWTAUDIENCE", "corp", Map.of());

        assertEquals("corp-analytics", overrides(rootId).get(AUDIENCE_PATH));
        String log = awaitLog(rootId, "accepts only JWTs whose audience is 'corp-analytics'");
        assertTrue(log.contains("the identity provider must put it in the tokens"), log);
        assertTrue(log.contains("set it as the profile's JWT Audience"), "the log says how to change it: " + log);
    }

    @Test
    void aServiceThatDoesNotCheckTheAudienceGetsNoRequiredAudience() {
        profile("sso", oidc("internal", null));

        String rootId = plan("ZZTESTJWTCALLER", "sso", Map.of());

        assertNull(overrides(rootId).get(AUDIENCE_PATH));
        assertEquals(List.of(), paramsOf(step(rootId, CommandType.OIDC_REGISTER_CLIENT)).get("oidcAudiences"),
                "the client's audiences are set, to none");
    }

    @Test
    void aReleaseCallingAKdpsTrinoWithItsServiceTokenCarriesTheAudienceThatTrinoRequires() {
        profile("sso", oidc("internal", null));
        trinoRelease("bi-trino");
        when(k8s.getHelmReleaseValues(NS, "bi-trino"))
                .thenReturn(Map.of("global", Map.of("security", Map.of("auth", Map.of("oidc",
                        Map.of("jwt", Map.of("requiredAudience", "bi-trino-analytics")))))));

        String rootId = plan("ZZTESTJWTCALLER", "sso", Map.of("ui_trino_host", TRINO_HOST, "ui_trino_auth_mode", "oidc"));

        assertEquals(List.of("bi-trino-analytics"),
                paramsOf(step(rootId, CommandType.OIDC_REGISTER_CLIENT)).get("oidcAudiences"));
    }

    @Test
    void aTrinoNotCheckingTheAudienceYetStillGetsItsClientIdSoItsUpgradeKeepsTheConnection() {
        profile("sso", oidc("internal", null));
        trinoRelease("bi-trino");
        when(k8s.getHelmReleaseValues(NS, "bi-trino")).thenReturn(Map.of());

        String rootId = plan("ZZTESTJWTCALLER", "sso", Map.of("ui_trino_host", TRINO_HOST, "ui_trino_auth_mode", "oidc"));

        assertEquals(List.of("bi-trino-" + NS), paramsOf(step(rootId, CommandType.OIDC_REGISTER_CLIENT)).get("oidcAudiences"));
    }

    @Test
    void aTrinoKdpsDidNotDeployKeepsTheAudiencesTheClientHasAndTheLogSaysSo() throws InterruptedException {
        profile("sso", oidc("internal", null));

        String rootId = plan("ZZTESTJWTCALLER", "sso",
                Map.of("ui_trino_host", "trino.example.org:443", "ui_trino_auth_mode", "oidc"));

        assertFalse(paramsOf(step(rootId, CommandType.OIDC_REGISTER_CLIENT)).containsKey("oidcAudiences"),
                "no audience list: the registration keeps the client's own");
        String log = awaitLog(rootId, "is not a KDPS release");
        assertTrue(log.contains("keeps the token audiences it has"), log);
    }

    @Test
    void anotherTrinoAuthenticationNeedsNoTrinoAudience() {
        profile("sso", oidc("internal", null));

        String rootId = plan("ZZTESTJWTCALLER", "sso", Map.of("ui_trino_host", TRINO_HOST, "ui_trino_auth_mode", "ldap"));

        assertEquals(List.of(), paramsOf(step(rootId, CommandType.OIDC_REGISTER_CLIENT)).get("oidcAudiences"));
    }

    @Test
    void anExternalProviderIssuingAnotherAudienceIsRequiredToPutTheProfilesAudience() {
        SecurityConfigDTO cfg = oidc("external", "corp-analytics");
        cfg.oidc.audience = " api://corp-analytics ";
        profile("entra", cfg);

        String rootId = plan("ZZTESTJWTAUDIENCE", "entra", Map.of());

        assertEquals("api://corp-analytics", overrides(rootId).get(AUDIENCE_PATH),
                "the provider puts its application ID URI in the tokens, not the client id");
    }

    @Test
    void anUpgradeWhoseFormNoLongerNamesTheTrinoKeepsTheAudienceOfTheTrinoTheReleaseStillCalls() {
        profile("sso", oidc("internal", null));
        trinoRelease("bi-trino");
        when(k8s.getHelmReleaseValues(NS, "bi-trino"))
                .thenReturn(Map.of("global", Map.of("security", Map.of("auth", Map.of("oidc",
                        Map.of("jwt", Map.of("requiredAudience", "bi-trino-analytics")))))));
        // What the Upgrade/Config wizard sends: the deployed values, the Trino connection and its token sidecar
        // included, and no Trino host in the form (that field is not kept in the values).
        Map<String, Object> deployed = new LinkedHashMap<>();
        deployed.put("trinoJwt", Map.of("enabled", true));
        deployed.put("extraConfigs", Map.of("import_datasources_trino.yaml", jwtDatasource(TRINO_HOST)));

        String rootId = plan("ZZTESTJWTCALLER", "sso", Map.of("ui_trino_auth_mode", "none"), deployed);

        assertEquals(List.of("bi-trino-analytics"),
                paramsOf(step(rootId, CommandType.OIDC_REGISTER_CLIENT)).get("oidcAudiences"),
                "the client keeps the audience the Trino it calls requires");
    }

    @Test
    void anUpgradeRunningTheTokenSidecarForATrinoItCannotNameKeepsTheClientsAudiences() {
        profile("sso", oidc("internal", null));

        String rootId = plan("ZZTESTJWTCALLER", "sso", Map.of(), Map.of("trinoJwt", Map.of("enabled", "true")));

        assertFalse(paramsOf(step(rootId, CommandType.OIDC_REGISTER_CLIENT)).containsKey("oidcAudiences"),
                "no audience list: dropping the client's audiences would break the connection");
    }

    @Test
    void anUpgradeWhoseTrinoConnectionUsesAnotherAuthenticationNeedsNoTrinoAudience() {
        profile("sso", oidc("internal", null));
        Map<String, Object> deployed = new LinkedHashMap<>();
        deployed.put("trinoJwt", Map.of("enabled", true));
        deployed.put("extraConfigs", Map.of("import_datasources_trino.yaml",
                jwtDatasource(TRINO_HOST).replace("kdps_file_jwt", "kerberos")));

        String rootId = plan("ZZTESTJWTCALLER", "sso", Map.of(), deployed);

        assertEquals(List.of(), paramsOf(step(rootId, CommandType.OIDC_REGISTER_CLIENT)).get("oidcAudiences"));
    }

    @Test
    void theTrinoConnectionIsReadFromTheDatasourceFileTheWizardWrites() {
        assertArrayEquals(new String[] {TRINO_HOST, "kdps_file_jwt"},
                CommandService.trinoJwtConnection(Map.of("extraConfigs",
                        Map.of("import_datasources_trino.yaml", jwtDatasource(TRINO_HOST)))));
        assertArrayEquals(new String[] {"trino.example.org:8080", ""},
                CommandService.trinoJwtConnection(Map.of("extraConfigs", Map.of("import_datasources_trino.yaml",
                        "databases:\n- database_name: Platform Trino\n  sqlalchemy_uri: trino://trino.example.org:8080/hive\n"))));
        assertNull(CommandService.trinoJwtConnection(Map.of("extraConfigs", Map.of("other.yaml", "x"))));
        assertNull(CommandService.trinoJwtConnection(Map.of()));
        assertNull(CommandService.trinoJwtConnection(null));
    }

    @Test
    void aTrinoUpgradeAddsItsAudienceToTheTokensOfTheReleasesAlreadyCallingIt() throws InterruptedException {
        profile("sso", oidc("internal", null));
        trinoRelease(rel);
        Map<String, Object> callingIt = new LinkedHashMap<>();
        callingIt.put("trinoJwt", Map.of("enabled", true));
        callingIt.put("extraConfigs", Map.of("import_datasources_trino.yaml", jwtDatasource(TRINO_HOST)));
        recorded("bi", "ZZTESTJWTCALLER", callingIt);
        // The same connection without the token sidecar, or with another authentication: no service token to fix.
        Map<String, Object> noSidecar = new LinkedHashMap<>(callingIt);
        noSidecar.remove("trinoJwt");
        recorded("bi-basic", "ZZTESTJWTCALLER", noSidecar);
        Map<String, Object> kerberos = new LinkedHashMap<>(callingIt);
        kerberos.put("extraConfigs", Map.of("import_datasources_trino.yaml",
                jwtDatasource(TRINO_HOST).replace("kdps_file_jwt", "kerberos")));
        recorded("bi-krb", "ZZTESTJWTCALLER", kerberos);
        // Another Trino's caller, and a release whose service KDPS registers no OIDC client for.
        Map<String, Object> otherTrino = new LinkedHashMap<>(callingIt);
        otherTrino.put("extraConfigs", Map.of("import_datasources_trino.yaml",
                jwtDatasource("other-trino.analytics.svc.cluster.local:8443")));
        recorded("bi-other", "ZZTESTJWTCALLER", otherTrino);
        recorded("no-oidc", "ZZTESTCUSTOM", callingIt);

        String rootId = plan("ZZTESTJWTAUDIENCE", "sso", Map.of());

        String clientId = rel + "-" + NS;
        assertEquals(clientId, overrides(rootId).get(AUDIENCE_PATH));
        assertEquals(List.of(Map.of("namespace", NS, "release", "bi", "clientId", "bi-" + NS)),
                paramsOf(step(rootId, CommandType.OIDC_REGISTER_CLIENT)).get("oidcAudienceCallers"),
                "the registration of Trino's client adds its audience to the tokens of the Superset calling it");
        String log = awaitLog(rootId, "Release(s) analytics/bi call this Trino");
        assertTrue(log.contains("adds the audience '" + clientId + "'"), log);
    }

    @Test
    void aTrinoNobodyCallsWithAServiceTokenPlansNoOtherClient() {
        profile("sso", oidc("internal", null));
        trinoRelease(rel);

        String rootId = plan("ZZTESTJWTAUDIENCE", "sso", Map.of());

        assertFalse(paramsOf(step(rootId, CommandType.OIDC_REGISTER_CLIENT)).containsKey("oidcAudienceCallers"));
    }

    @Test
    void aTrinoOnAnExternalProviderNamesTheReleasesWhoseTokensMustCarryItsAudience() throws InterruptedException {
        profile("corp", oidc("external", "corp-analytics"));
        trinoRelease(rel);
        Map<String, Object> callingIt = new LinkedHashMap<>();
        callingIt.put("trinoJwt", Map.of("enabled", "true"));
        callingIt.put("extraConfigs", Map.of("import_datasources_trino.yaml", jwtDatasource(TRINO_HOST)));
        recorded("bi", "ZZTESTJWTCALLER", callingIt);

        String rootId = plan("ZZTESTJWTAUDIENCE", "corp", Map.of());

        String log = awaitLog(rootId, "WARNING: release(s) analytics/bi call this Trino");
        assertTrue(log.contains("puts the audience 'corp-analytics' in their tokens"), log);
    }

    @Test
    void theRegistrationAddsTheAudienceToEachCallerAndNamesThoseItCouldNot() throws Exception {
        KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
        when(keycloak.addAudience("bi-analytics", "trino-analytics")).thenReturn(true);
        when(keycloak.addAudience("gone-analytics", "trino-analytics")).thenReturn(false);
        when(keycloak.addAudience("down-analytics", "trino-analytics"))
                .thenThrow(new IllegalStateException("Keycloak POST protocol mapper returned HTTP 503"));
        String logId = "register-" + rel;

        service.addAudienceToCallers(keycloak, "trino-analytics", List.of(
                Map.of("namespace", NS, "release", "bi", "clientId", "bi-analytics"),
                Map.of("namespace", NS, "release", "gone", "clientId", "gone-analytics"),
                Map.of("namespace", NS, "release", "down", "clientId", "down-analytics")), logId);

        String log = awaitLog(logId, "analytics/down");
        assertTrue(log.contains("Access tokens of 'bi-analytics' (release analytics/bi) carry the audience"), log);
        assertTrue(log.contains("release analytics/gone (the realm has no client 'gone-analytics')"), log);
        assertTrue(log.contains("release analytics/down (Keycloak POST protocol mapper returned HTTP 503)"), log);
        assertTrue(log.contains("until you redeploy it (Upgrade/Config, no change needed)"), log);
        verify(keycloak).addAudience("down-analytics", "trino-analytics");
    }

    /** A release KDPS deployed, with the values helm holds for it. */
    private void recorded(String release, String serviceKey, Map<String, Object> values) {
        K8sReleaseEntity e = new K8sReleaseEntity();
        e.setId(K8sReleaseEntity.idOf(NS, release));
        e.setNamespace(NS);
        e.setReleaseName(release);
        e.setServiceKey(serviceKey);
        dataStore.store(e);
        when(k8s.getHelmReleaseValues(NS, release)).thenReturn(values);
    }

    /** The Trino database the Superset wizard writes for the OIDC authentication. */
    private static String jwtDatasource(String hostPort) {
        return "databases:\n- database_name: Platform Trino\n  sqlalchemy_uri: trino://" + hostPort + "/hive\n"
                + "  extra: '{\"engine_params\": {\"connect_args\": {\"source\": \"superset-kdps\"}}}'\n"
                + "  encrypted_extra: '{\"auth_method\": \"kdps_file_jwt\", \"auth_params\": {}}'\n"
                + "  impersonate_user: true\n";
    }

    @Test
    void jwtAudiencePathIsTheDeclaredChartValue() {
        assertEquals(AUDIENCE_PATH, CommandService.jwtAudiencePath(Map.of("jwtAudiencePath", " " + AUDIENCE_PATH + " ")));
        assertNull(CommandService.jwtAudiencePath(Map.of("key", "superset")));
        assertNull(CommandService.jwtAudiencePath(Map.of("jwtAudiencePath", "  ")));
        assertNull(CommandService.jwtAudiencePath(null));
    }

    /** The Trino coordinator Service of release {@code release}, as the "Trino Coordinator" discovery field names it. */
    @SuppressWarnings("unchecked")
    private void trinoRelease(String release) {
        Service svc = new Service();
        svc.setMetadata(new ObjectMetaBuilder().withName("trino-coordinator").withNamespace(NS)
                .addToLabels("app.kubernetes.io/instance", release).build());
        MixedOperation<Service, ServiceList, ServiceResource<Service>> services = mock(MixedOperation.class);
        NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> inNs = mock(NonNamespaceOperation.class);
        ServiceResource<Service> resource = mock(ServiceResource.class);
        when(client.services()).thenReturn(services);
        when(services.inNamespace(NS)).thenReturn(inNs);
        when(inNs.withName("trino-coordinator")).thenReturn(resource);
        when(resource.get()).thenReturn(svc);
    }

    private static SecurityConfigDTO oidc(String source, String clientId) {
        SecurityConfigDTO cfg = new SecurityConfigDTO();
        cfg.mode = "oidc";
        cfg.oidc = new OidcConfig();
        cfg.oidc.source = source;
        cfg.oidc.issuerUrl = "https://keycloak.example.org/realms/odp";
        cfg.oidc.clientId = clientId;
        cfg.oidc.clientSecret = clientId == null ? null : "Client-Secret-7";
        return cfg;
    }

    private void profile(String name, SecurityConfigDTO cfg) {
        new SecurityProfileService(ctx).writeProfile(name, cfg, false);
    }

    /** Plans a direct deploy of a test service with the given profile and form values; returns the operation id. */
    private String plan(String serviceKey, String profile, Map<String, Object> formValues) {
        return plan(serviceKey, profile, formValues, Map.of());
    }

    /** {@link #plan(String, String, Map)} with release values on top of the base ones (an upgrade's). */
    private String plan(String serviceKey, String profile, Map<String, Object> formValues, Map<String, Object> values) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setChart("example/zz-test");
        request.setReleaseName(rel);
        request.setNamespace(NS);
        request.setServiceKey(serviceKey);
        request.setRepoId("charts");
        request.setVersion("0.0.1");
        request.setDeploymentMode("DIRECT_HELM");
        request.setSecurityProfile(profile);
        Map<String, Object> releaseValues = new LinkedHashMap<>(Map.of("replicaCount", 1));
        releaseValues.putAll(values);
        request.setValues(releaseValues);
        request.setFormValues(new LinkedHashMap<>(formValues));
        return service.planReleaseDeploy(request, "charts", "0.0.1", null, null, null, null, false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> overrides(String rootId) {
        Object ov = paramsOf(dataStore.find(CommandEntity.class, rootId)).get("_ov");
        return ov instanceof Map ? (Map<String, Object>) ov : Map.of();
    }

    private List<String> stepTypes(String rootId) {
        Type listType = new TypeToken<ArrayList<String>>() {}.getType();
        List<String> ids = gson.fromJson(dataStore.find(CommandEntity.class, rootId).getChildListJson(), listType);
        List<String> types = new ArrayList<>();
        for (String id : ids) types.add(dataStore.find(CommandEntity.class, id).getType());
        return types;
    }

    private CommandEntity step(String rootId, CommandType type) {
        Type listType = new TypeToken<ArrayList<String>>() {}.getType();
        for (String id : gson.<List<String>>fromJson(dataStore.find(CommandEntity.class, rootId).getChildListJson(), listType)) {
            CommandEntity c = dataStore.find(CommandEntity.class, id);
            if (type.name().equals(c.getType())) return c;
        }
        throw new AssertionError("no " + type + " step in " + stepTypes(rootId));
    }

    private Map<String, Object> paramsOf(CommandEntity command) {
        Type mapType = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();
        return gson.fromJson(CommandUtils.resolveParamsPayload(command.getParamsJson()), mapType);
    }

    /** The command log is written asynchronously; waits for a line to show up. */
    private String awaitLog(String commandId, String expected) throws InterruptedException {
        String content = "";
        for (int i = 0; i < 200; i++) {
            content = CommandLogService.get(ctx).read(commandId, 0, 1 << 16).content;
            if (content.contains(expected)) return content;
            Thread.sleep(25);
        }
        throw new AssertionError("'" + expected + "' never reached the log of " + commandId + ": " + content);
    }
}
