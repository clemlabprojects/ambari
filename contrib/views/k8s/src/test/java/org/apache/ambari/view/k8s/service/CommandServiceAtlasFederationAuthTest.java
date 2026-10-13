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
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.FederationAuthUnavailableException;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the Atlas federation of an OpenMetadata deploy plans on a cluster. KDPS must not turn on Atlas file
 * authentication by itself (the ODP Atlas scripts then add a local administrator to the user store): on a
 * Kerberos or LDAP Atlas it plans nothing and the deploy fails with the reason, unless the operator allowed it
 * or gave an LDAP service account.
 */
class CommandServiceAtlasFederationAuthTest {

    private static final String CLUSTER = "c1";

    @TempDir
    Path workDir;

    private final Gson gson = new Gson();
    private PerUserViewContext view;
    private CommandService service;
    private AmbariActionClient ambari;
    private KubernetesService kube;

    @BeforeEach
    void setUp() throws Exception {
        view = new PerUserViewContext("atlas-fed-" + Long.toHexString(System.nanoTime()), workDir);
        kube = mock(KubernetesService.class);
        service = new CommandService(view.context, mock(HelmService.class), kube);
        // The background sweep reads the context; wait for it before the context is stubbed again.
        service.startupSweep.get(30, TimeUnit.SECONDS);
        ambari = mock(AmbariActionClient.class);
        doReturn(List.of("atlas1.example.com")).when(ambari).getComponentHosts(CLUSTER, "ATLAS", "ATLAS_SERVER");
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private void atlasAuth(boolean file, boolean ldap, boolean kerberos) throws Exception {
        doReturn(String.valueOf(file)).when(ambari)
                .getDesiredConfigProperty(CLUSTER, "application-properties", "atlas.authentication.method.file");
        doReturn(String.valueOf(ldap)).when(ambari)
                .getDesiredConfigProperty(CLUSTER, "application-properties", "atlas.authentication.method.ldap");
        doReturn(String.valueOf(kerberos)).when(ambari)
                .getDesiredConfigProperty(CLUSTER, "application-properties", "atlas.authentication.method.kerberos");
    }

    /** Makes the operator's Secret exist in the deploy namespace with the given (plain text) keys. */
    private void secret(String name, Map<String, String> plain) {
        Map<String, String> data = new LinkedHashMap<>();
        plain.forEach((k, v) -> data.put(k, java.util.Base64.getEncoder()
                .encodeToString(v.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        io.fabric8.kubernetes.api.model.Secret sec = new io.fabric8.kubernetes.api.model.SecretBuilder()
                .withNewMetadata().withName(name).withNamespace("om").endMetadata()
                .withData(data).build();
        doReturn(sec).when(kube).getSecret("om", name);
    }

    /** Plans the federation steps and returns the planned steps by type, with their params. */
    private List<CommandEntity> plan(Map<String, Object> federation) throws Throwable {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace("om");
        request.setReleaseName("om1");
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("atlasFederation", federation);
        request.setFormValues(form);
        CommandEntity root = new CommandEntity();
        root.setId("root-" + Long.toHexString(System.nanoTime()));
        Method m = CommandService.class.getDeclaredMethod("queueAtlasFederationProvisioningSteps",
                CommandEntity.class, HelmDeployRequest.class, Map.class, String.class, AmbariActionClient.class);
        m.setAccessible(true);
        try {
            m.invoke(service, root, request, new LinkedHashMap<String, Object>(), CLUSTER, ambari);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
        List<String> ids = gson.fromJson(root.getChildListJson(), new TypeToken<ArrayList<String>>() { }.getType());
        List<CommandEntity> steps = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) steps.add(view.dataStore.find(CommandEntity.class, id));
        }
        return steps;
    }

    /** The params a step was stored with (kept in an encrypted file, not in the entity). */
    private Map<?, ?> paramsOf(CommandEntity step) {
        return gson.fromJson(CommandUtils.resolveParamsPayload(step.getParamsJson()), Map.class);
    }

    /** The user a planned Ranger grant step names, resolved as the step resolves it when it runs. */
    @SuppressWarnings("unchecked")
    private String grantedPrincipal(CommandEntity grant) {
        return service.federationPrincipalForStep((Map<String, Object>) paramsOf(grant));
    }

    private void atlasOnRanger() throws Exception {
        doReturn("org.apache.ranger.authorization.atlas.authorizer.RangerAtlasAuthorizer").when(ambari)
                .getDesiredConfigProperty(CLUSTER, "application-properties", "atlas.authorizer.impl");
    }

    private static CommandEntity step(List<CommandEntity> steps, CommandType type) {
        return steps.stream().filter(s -> type.name().equals(s.getType())).findFirst().orElse(null);
    }

    /** A plan root, as submitDeploy and the reapply action store it before steps are added. */
    private static CommandEntity newRoot() {
        CommandEntity root = new CommandEntity();
        root.setId("root-" + Long.toHexString(System.nanoTime()));
        root.setChildListJson("[]");
        return root;
    }

    /** The steps planned under a root, in order. */
    private List<CommandEntity> stepsOf(CommandEntity root) {
        List<String> ids = gson.fromJson(root.getChildListJson(), new TypeToken<ArrayList<String>>() { }.getType());
        List<CommandEntity> steps = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) steps.add(view.dataStore.find(CommandEntity.class, id));
        }
        return steps;
    }

    /** The deployed values of release om/om1 name the given Secret for the federation. */
    private void deployedWithSecret(String existingSecret) {
        Map<String, Object> federation = new LinkedHashMap<>();
        federation.put("enabled", true);
        if (existingSecret != null) federation.put("existingSecret", existingSecret);
        doReturn(Map.of("atlasFederation", federation)).when(kube).getHelmReleaseValues("om", "om1");
    }

    /** Points the Atlas REST address at a local server that admits only the given user and password. */
    private com.sun.net.httpserver.HttpServer atlasAdmitting(String user, String password) throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        String expected = "Basic " + java.util.Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        server.createContext("/api/atlas/admin/version", exchange -> {
            int code = expected.equals(exchange.getRequestHeaders().getFirst("Authorization")) ? 200 : 401;
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        server.start();
        doReturn("http://127.0.0.1:" + server.getAddress().getPort()).when(ambari)
                .getDesiredConfigProperty(CLUSTER, "application-properties", "atlas.rest.address");
        return server;
    }

    @Test
    void aKerberosAtlasWithoutTheOperatorsChoiceIsNotGivenFileAuthentication() throws Exception {
        atlasAuth(false, false, true);

        FederationAuthUnavailableException e = assertThrows(FederationAuthUnavailableException.class,
                () -> plan(new LinkedHashMap<>(Map.of("enabled", true))));

        assertTrue(e.getMessage().contains("Allow Atlas file authentication"), e.getMessage());
        verify(ambari, never()).updateClusterConfig(anyString(), anyString(), any(Map.class), anyString());
    }

    @Test
    void aKerberosAtlasGetsAFileUserOnlyWhenTheOperatorAllowedIt() throws Throwable {
        atlasAuth(false, false, true);

        List<CommandEntity> steps = plan(new LinkedHashMap<>(Map.of("enabled", true, "allowFileAuth", true)));

        CommandEntity provision = step(steps, CommandType.ATLAS_USER_PROVISION_OM);
        assertNotNull(provision, "the federation user is provisioned");
        Map<?, ?> params = paramsOf(provision);
        assertEquals("true", params.get("_atlasEnableFileAuth"));
        assertEquals("ROLE_USER", params.get("_atlasFederationRole"));
    }

    @Test
    void anAtlasWithFileAuthenticationOnIsNotAskedToTurnItOnAgain() throws Throwable {
        atlasAuth(true, false, true);

        List<CommandEntity> steps = plan(new LinkedHashMap<>(Map.of("enabled", true)));

        CommandEntity provision = step(steps, CommandType.ATLAS_USER_PROVISION_OM);
        assertNotNull(provision);
        assertEquals("false", paramsOf(provision).get("_atlasEnableFileAuth"));
    }

    @Test
    void anLdapAtlasWithAServiceAccountSecretProvisionsNothingInAtlas() throws Throwable {
        atlasAuth(false, true, false);
        secret("om-ldap-account", Map.of("username", "svc-om", "password", "pw"));

        List<CommandEntity> steps = plan(new LinkedHashMap<>(Map.of("enabled", true, "existingSecret", "om-ldap-account")));

        assertNull(step(steps, CommandType.ATLAS_USER_PROVISION_OM), "no user is written to Atlas's user store");
        assertNull(step(steps, CommandType.ATLAS_SIMPLE_AUTHZ_GRANT_OM));
    }

    @Test
    void anLdapAtlasWithFileAuthenticationAlsoOnNeverCopiesTheLdapAccountIntoTheUserStore() throws Throwable {
        atlasAuth(true, true, false);
        secret("om-ldap-account", Map.of("username", "svc-om", "password", "pw"));

        List<CommandEntity> steps = plan(new LinkedHashMap<>(Map.of("enabled", true, "existingSecret", "om-ldap-account")));

        assertNull(step(steps, CommandType.ATLAS_USER_PROVISION_OM), "the directory account is not written to the file user store");
    }

    @Test
    void anLdapAtlasWithRangerGrantsTheAccountInTheOperatorsSecret() throws Throwable {
        atlasAuth(false, true, false);
        doReturn("org.apache.ranger.authorization.atlas.authorizer.RangerAtlasAuthorizer").when(ambari)
                .getDesiredConfigProperty(CLUSTER, "application-properties", "atlas.authorizer.impl");
        secret("om-ldap-account", Map.of("username", " svc-om ", "password", "pw"));

        List<CommandEntity> steps = plan(new LinkedHashMap<>(Map.of("enabled", true, "existingSecret", "om-ldap-account")));

        CommandEntity grant = step(steps, CommandType.RANGER_POLICY_GRANT_VIA_AMBARI);
        assertNotNull(grant, "the account is granted read on Atlas");
        assertEquals("svc-om", grantedPrincipal(grant));
    }

    @Test
    void aFileUserStoreWithRangerGrantsTheUserNamedInTheOperatorsSecret() throws Throwable {
        atlasAuth(true, false, false);
        doReturn("org.apache.ranger.authorization.atlas.authorizer.RangerAtlasAuthorizer").when(ambari)
                .getDesiredConfigProperty(CLUSTER, "application-properties", "atlas.authorizer.impl");
        secret("om-file-account", Map.of("username", "svc-om\n", "password", "pw"));

        List<CommandEntity> steps = plan(new LinkedHashMap<>(Map.of("enabled", true, "existingSecret", "om-file-account")));

        CommandEntity grant = step(steps, CommandType.RANGER_POLICY_GRANT_VIA_AMBARI);
        assertNotNull(grant, "the federation user is granted read on Atlas");
        assertEquals("svc-om", grantedPrincipal(grant));
    }

    @Test
    void aFileUserStoreWithRangerAndNoOperatorSecretGrantsTheDefaultUser() throws Throwable {
        atlasAuth(true, false, false);
        doReturn("org.apache.ranger.authorization.atlas.authorizer.RangerAtlasAuthorizer").when(ambari)
                .getDesiredConfigProperty(CLUSTER, "application-properties", "atlas.authorizer.impl");

        List<CommandEntity> steps = plan(new LinkedHashMap<>(Map.of("enabled", true)));

        assertEquals("openmetadata-federation",
                paramsOf(step(steps, CommandType.RANGER_POLICY_GRANT_VIA_AMBARI)).get("_omPrincipal"));
        assertEquals("openmetadata-federation", grantedPrincipal(step(steps, CommandType.RANGER_POLICY_GRANT_VIA_AMBARI)));
    }

    @Test
    void anLdapAtlasWhoseSecretHasNoUsernameFailsAtTheGrantStepWithoutGuessingAnAccount() throws Throwable {
        atlasAuth(false, true, false);
        atlasOnRanger();
        secret("om-ldap-account", Map.of("password", "pw"));

        List<CommandEntity> steps = plan(new LinkedHashMap<>(Map.of("enabled", true, "existingSecret", "om-ldap-account", "username", "someone")));

        CommandEntity grant = step(steps, CommandType.RANGER_POLICY_GRANT_VIA_AMBARI);
        assertNotNull(grant);
        assertNull(paramsOf(grant).get("_omPrincipal"), "no account is guessed when planning");
        FederationAuthUnavailableException e = assertThrows(FederationAuthUnavailableException.class,
                () -> grantedPrincipal(grant));
        assertTrue(e.getMessage().contains("'username' and 'password'"), e.getMessage());
    }

    @Test
    void anLdapAtlasWhoseSecretHasNoPasswordFailsAtTheRegistrationStep() throws Exception {
        atlasAuth(false, true, false);
        secret("om-ldap-account", Map.of("username", "svc-om"));
        Map<String, Object> federation = new LinkedHashMap<>();
        federation.put("enabled", true);
        federation.put("existingSecret", "om-ldap-account");
        federation.put("hostPort", "http://atlas1.example.com:21000");
        doReturn(Map.of("atlasFederation", federation)).when(kube).getHelmReleaseValues("om", "om1");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("releaseName", "om1");
        params.put("namespace", "om");
        params.put("_atlasAuthMode", "ldap");

        FederationAuthUnavailableException e = assertThrows(FederationAuthUnavailableException.class,
                () -> service.registerOmAtlasFederation(params));

        assertTrue(e.getMessage().contains("'username' and 'password'"), e.getMessage());
    }

    @Test
    void anLdapAtlasWhoseSecretIsCreatedByTheInstallIsPlannedAndGrantedOnceItExists() throws Throwable {
        // The Secret is not there when the deploy is planned (external-secrets syncs it after the install).
        atlasAuth(false, true, false);
        atlasOnRanger();

        List<CommandEntity> steps = plan(new LinkedHashMap<>(Map.of("enabled", true, "existingSecret", "om-ldap-account")));

        assertNull(step(steps, CommandType.ATLAS_USER_PROVISION_OM), "nothing is written to Atlas's user store");
        CommandEntity grant = step(steps, CommandType.RANGER_POLICY_GRANT_VIA_AMBARI);
        assertNotNull(grant, "the grant is planned without the Secret");
        FederationAuthUnavailableException missing = assertThrows(FederationAuthUnavailableException.class,
                () -> grantedPrincipal(grant));
        assertTrue(missing.getMessage().contains("does not exist"), missing.getMessage());

        secret("om-ldap-account", Map.of("username", "svc-om", "password", "pw"));
        assertEquals("svc-om", grantedPrincipal(grant), "the grant names the Secret's account once it exists");
    }

    @Test
    void aFileUserStoreWhoseSecretIsCreatedByTheInstallGrantsTheSecretsUser() throws Throwable {
        atlasAuth(true, false, false);
        atlasOnRanger();

        List<CommandEntity> steps = plan(new LinkedHashMap<>(Map.of("enabled", true, "existingSecret", "om-file-account")));
        CommandEntity grant = step(steps, CommandType.RANGER_POLICY_GRANT_VIA_AMBARI);
        secret("om-file-account", Map.of("username", "svc-om", "password", "pw"));

        assertEquals("svc-om", grantedPrincipal(grant), "the grant names the user the provision step writes");
    }

    @Test
    void anLdapAtlasWhoseUserStoreHoldsTheSecretsAccountFromAnEarlierDeployKeepsItInStep() throws Throwable {
        // File and LDAP sign-in are both on, and an earlier version put the Secret's account in the file user store.
        atlasAuth(true, true, false);
        atlasOnRanger();
        secret("om-vault-account", Map.of("username", "svc-om", "password", "rotated"));
        doReturn("svc-om").when(ambari).getDesiredConfigProperty(CLUSTER, "atlas-env", "openmetadata.federation.username");

        List<CommandEntity> steps = plan(new LinkedHashMap<>(Map.of("enabled", true, "existingSecret", "om-vault-account")));

        CommandEntity provision = step(steps, CommandType.ATLAS_USER_PROVISION_OM);
        assertNotNull(provision, "the local account is re-synced with the Secret, so a rotated password still signs in");
        assertEquals("om-vault-account", paramsOf(provision).get("_atlasFederationSecret"));
        assertEquals("false", paramsOf(provision).get("_atlasEnableFileAuth"));
        assertEquals("svc-om", grantedPrincipal(step(steps, CommandType.RANGER_POLICY_GRANT_VIA_AMBARI)));
    }

    @Test
    void theConfigCheckOfAnEarlierFileReleaseOnAnLdapAtlasReportsThePasswordDriftAndOffersTheFix() throws Exception {
        atlasAuth(true, true, false);
        secret("om-vault-account", Map.of("username", "svc-om", "password", "rotated"));
        deployedWithSecret("om-vault-account");
        doReturn("svc-om").when(ambari).getDesiredConfigProperty(CLUSTER, "atlas-env", "openmetadata.federation.username");
        doReturn(org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.sha256Hex("before-rotation")).when(ambari)
                .getDesiredConfigProperty(CLUSTER, "atlas-env", "openmetadata.federation.password_hash");

        Map<String, Object> report = service.checkAtlasFederationConfig("om", "om1", CLUSTER, ambari);

        assertEquals("file", report.get("federationAuth"));
        assertEquals(true, report.get("fixRestartAvailable"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) report.get("checks");
        assertTrue(checks.stream().anyMatch(c -> "atlas.federation.passwordHash".equals(c.get("id"))
                && Boolean.FALSE.equals(c.get("ok"))), String.valueOf(checks));
        assertTrue(checks.stream().noneMatch(c -> "atlas.method.ldap".equals(c.get("id"))));
    }

    @Test
    void theRegistrationStepFailsWithTheReasonWhenFederationWasBlocked() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("releaseName", "om1");
        params.put("namespace", "om");
        params.put("_atlasFederationBlocked", "Atlas offers OpenMetadata no way to sign in.");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.registerOmAtlasFederation(params));

        assertEquals("Atlas offers OpenMetadata no way to sign in.", e.getMessage());
    }

    @Test
    void aDeployOnAKerberosAtlasQueuesOnlyTheRegistrationAndItCarriesTheReason() throws Exception {
        atlasAuth(false, false, true);
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace("om");
        request.setReleaseName("om1");
        request.setFormValues(new LinkedHashMap<>(Map.of("atlasFederation", Map.of("enabled", true))));
        CommandEntity root = newRoot();

        service.queueAtlasFederationSteps(root, request, new LinkedHashMap<>(), CLUSTER, ambari);

        List<CommandEntity> steps = stepsOf(root);
        assertEquals(1, steps.size(), "nothing is planned against Atlas");
        CommandEntity register = step(steps, CommandType.OM_ATLAS_FEDERATION_REGISTER);
        assertNotNull(register);
        assertTrue(String.valueOf(paramsOf(register).get("_atlasFederationBlocked")).contains("Allow Atlas file authentication"));
    }

    @Test
    void aDeployOnAnAtlasWithFileAuthenticationRegistersWithoutAReason() throws Exception {
        atlasAuth(true, false, false);
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace("om");
        request.setReleaseName("om1");
        request.setFormValues(new LinkedHashMap<>(Map.of("atlasFederation", Map.of("enabled", true))));
        CommandEntity root = newRoot();

        service.queueAtlasFederationSteps(root, request, new LinkedHashMap<>(), CLUSTER, ambari);

        List<CommandEntity> steps = stepsOf(root);
        assertNotNull(step(steps, CommandType.ATLAS_USER_PROVISION_OM));
        assertEquals(CommandType.OM_ATLAS_FEDERATION_REGISTER.name(), steps.get(steps.size() - 1).getType(), "registration runs last");
        assertNull(paramsOf(step(steps, CommandType.OM_ATLAS_FEDERATION_REGISTER)).get("_atlasFederationBlocked"));
    }

    @Test
    void aReapplyOnAnLdapAtlasUsesTheServiceAccountSecretOfTheDeployedRelease() throws Exception {
        atlasAuth(true, true, false);
        secret("om-ldap-account", Map.of("username", "svc-om", "password", "pw"));
        deployedWithSecret("om-ldap-account");
        CommandEntity root = newRoot();
        Map<String, Object> rootParams = new LinkedHashMap<>(Map.of("releaseName", "om1", "namespace", "om"));

        service.queueAtlasFederationReapplySteps(root, rootParams, "om", "om1", CLUSTER, ambari);

        List<CommandEntity> steps = stepsOf(root);
        assertNull(step(steps, CommandType.ATLAS_USER_PROVISION_OM), "the LDAP account is not written to Atlas's user store");
        CommandEntity register = step(steps, CommandType.OM_ATLAS_FEDERATION_REGISTER);
        assertNotNull(register);
        assertNull(paramsOf(register).get("_atlasFederationBlocked"));
    }

    @Test
    void aReapplyOnAKerberosAtlasRegistersWithTheReasonAndChangesNothingInAtlas() throws Exception {
        atlasAuth(false, false, true);
        deployedWithSecret(null);
        CommandEntity root = newRoot();
        Map<String, Object> rootParams = new LinkedHashMap<>(Map.of("releaseName", "om1", "namespace", "om"));

        service.queueAtlasFederationReapplySteps(root, rootParams, "om", "om1", CLUSTER, ambari);

        List<CommandEntity> steps = stepsOf(root);
        assertNull(step(steps, CommandType.ATLAS_USER_PROVISION_OM));
        CommandEntity register = step(steps, CommandType.OM_ATLAS_FEDERATION_REGISTER);
        assertNotNull(register);
        assertTrue(String.valueOf(paramsOf(register).get("_atlasFederationBlocked")).contains("Allow Atlas file authentication"));
    }

    @Test
    void aProvisionStepWithoutTheOperatorsChoiceRefusesBeforeWritingAnything() throws Exception {
        atlasAuth(false, false, true);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("releaseName", "om1");
        params.put("namespace", "om");
        params.put("_cluster", CLUSTER);
        // A step planned by an earlier view version carries no _atlasEnableFileAuth at all.

        assertThrows(FederationAuthUnavailableException.class, () -> service.provisionAtlasUserForOm(params, ambari));

        verify(ambari, never()).updateClusterConfig(anyString(), anyString(), any(Map.class), anyString());
        verify(kube, never()).getClient();
    }

    @Test
    void theConfigCheckOfAnLdapAccountReleaseProbesThatAccountAndOffersNoFix() throws Exception {
        // File authentication is on too, and a KDPS-managed user is left over from an earlier file-based deploy.
        atlasAuth(true, true, false);
        secret("om-ldap-account", Map.of("username", "svc-om\n", "password", "ldap-pw"));
        secret("om1-atlas-federation", Map.of("username", "openmetadata-federation", "password", "old"));
        deployedWithSecret("om-ldap-account");
        com.sun.net.httpserver.HttpServer atlas = atlasAdmitting("svc-om", "ldap-pw");
        try {
            Map<String, Object> report = service.checkAtlasFederationConfig("om", "om1", CLUSTER, ambari);

            assertEquals(true, report.get("federationConfigured"));
            assertEquals("ldap", report.get("federationAuth"));
            assertEquals(false, report.get("fixRestartAvailable"));
            assertEquals(true, report.get("federationUserActive"), "the probe signs in as the LDAP account");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> checks = (List<Map<String, Object>>) report.get("checks");
            assertTrue(checks.stream().noneMatch(c -> "atlas.federation.user".equals(c.get("id"))),
                    "nothing in atlas-env is expected for an LDAP account");
            assertTrue(checks.stream().anyMatch(c -> "atlas.method.ldap".equals(c.get("id")) && Boolean.TRUE.equals(c.get("ok"))));
            assertEquals(true, report.get("ok"));
        } finally {
            atlas.stop(0);
        }
    }

    @Test
    void theConfigCheckOfAnLdapAccountReleaseWhoseSecretHasNoPasswordSaysSo() throws Exception {
        atlasAuth(false, true, false);
        secret("om-ldap-account", Map.of("username", "svc-om"));
        deployedWithSecret("om-ldap-account");

        Map<String, Object> report = service.checkAtlasFederationConfig("om", "om1", CLUSTER, ambari);

        assertEquals(true, report.get("federationConfigured"), "the broken Secret is reported, not hidden");
        assertEquals(false, report.get("ok"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) report.get("checks");
        assertTrue(checks.stream().anyMatch(c -> "atlas.method.ldap".equals(c.get("id")) && Boolean.FALSE.equals(c.get("ok"))));
    }

    @Test
    void theConfigCheckOfAFileUserStoreReleaseExpectsTheUserOfTheOperatorsSecret() throws Exception {
        atlasAuth(true, false, false);
        secret("om-file-account", Map.of("username", "svc-om", "password", "pw"));
        deployedWithSecret("om-file-account");
        doReturn("svc-om").when(ambari).getDesiredConfigProperty(CLUSTER, "atlas-env", "openmetadata.federation.username");
        doReturn(org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.sha256Hex("pw")).when(ambari)
                .getDesiredConfigProperty(CLUSTER, "atlas-env", "openmetadata.federation.password_hash");
        com.sun.net.httpserver.HttpServer atlas = atlasAdmitting("svc-om", "pw");
        try {
            Map<String, Object> report = service.checkAtlasFederationConfig("om", "om1", CLUSTER, ambari);

            assertEquals("file", report.get("federationAuth"));
            assertEquals(true, report.get("fixRestartAvailable"));
            assertEquals(true, report.get("ok"), String.valueOf(report.get("checks")));
            assertEquals(true, report.get("federationUserActive"));
        } finally {
            atlas.stop(0);
        }
    }

    @Test
    void fixAndRestartIsRefusedForAnLdapAccountRelease() throws Exception {
        atlasAuth(true, true, false);
        secret("om-ldap-account", Map.of("username", "svc-om", "password", "pw"));
        deployedWithSecret("om-ldap-account");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.fixAndRestartAtlasFederation("om", "om1", CLUSTER, ambari));

        assertTrue(e.getMessage().contains("LDAP"), e.getMessage());
        verify(ambari, never()).updateClusterConfig(anyString(), anyString(), any(Map.class), anyString());
        verify(ambari, never()).submitComponentRestart(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void fixAndRestartReassertsTheUserOfTheOperatorsSecret() throws Exception {
        atlasAuth(true, false, false);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("username", java.util.Base64.getEncoder().encodeToString("svc-om".getBytes(StandardCharsets.UTF_8)));
        data.put("password", java.util.Base64.getEncoder().encodeToString("pw".getBytes(StandardCharsets.UTF_8)));
        io.fabric8.kubernetes.api.model.Secret sec = new io.fabric8.kubernetes.api.model.SecretBuilder()
                .withNewMetadata().withName("om-file-account").withNamespace("om").endMetadata().withData(data).build();
        io.fabric8.kubernetes.client.KubernetesClient k8s = mock(io.fabric8.kubernetes.client.KubernetesClient.class);
        io.fabric8.kubernetes.client.dsl.MixedOperation secrets = mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        io.fabric8.kubernetes.client.dsl.NonNamespaceOperation inNs =
                mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        io.fabric8.kubernetes.client.dsl.Resource named = mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(k8s.secrets()).thenReturn(secrets);
        when(secrets.inNamespace("om")).thenReturn(inNs);
        when(inNs.withName("om-file-account")).thenReturn(named);
        when(named.get()).thenReturn(sec);
        doReturn(k8s).when(kube).getClient();
        deployedWithSecret("om-file-account");

        Map<String, Object> result = service.fixAndRestartAtlasFederation("om", "om1", CLUSTER, ambari);

        assertEquals("svc-om", result.get("username"));
        verify(ambari).updateClusterConfig(eq(CLUSTER), eq("atlas-env"),
                argThat(m -> "svc-om".equals(m.get("openmetadata.federation.username"))), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDeployOnAnLdapAtlasWithTheSimpleAuthorizerTellsTheOperatorToGrantTheSecretsAccount() throws Exception {
        atlasAuth(false, true, false);
        secret("om-ldap-account", Map.of("username", "svc-om", "password", "pw"));
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace("om");
        request.setReleaseName("om1");
        request.setFormValues(new LinkedHashMap<>(Map.of("atlasFederation",
                Map.of("enabled", true, "existingSecret", "om-ldap-account"))));
        CommandEntity root = newRoot();

        service.queueAtlasFederationSteps(root, request, new LinkedHashMap<>(), CLUSTER, ambari);

        List<CommandEntity> steps = stepsOf(root);
        assertNull(step(steps, CommandType.ATLAS_USER_PROVISION_OM));
        assertNull(step(steps, CommandType.ATLAS_SIMPLE_AUTHZ_GRANT_OM), "the policy file is not granted for an LDAP account");
        Map<String, Object> register = (Map<String, Object>) paramsOf(step(steps, CommandType.OM_ATLAS_FEDERATION_REGISTER));
        assertNull(register.get("_atlasFederationBlocked"), "the deploy goes ahead: the account may be granted through a group");
        String notice = CommandService.federationAuthzNotice(register, "svc-om");
        assertNotNull(notice, "the registration step tells the operator what is left to do");
        assertTrue(notice.contains("'svc-om'") && notice.contains("atlas-simple-authz-policy.json"), notice);
        assertFalse(notice.contains("'openmetadata'"), notice);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReapplyOnAnLdapAtlasWithTheSimpleAuthorizerCarriesTheNoticeToo() throws Exception {
        atlasAuth(false, true, false);
        secret("om-ldap-account", Map.of("username", "svc-om", "password", "pw"));
        deployedWithSecret("om-ldap-account");
        CommandEntity root = newRoot();
        Map<String, Object> rootParams = new LinkedHashMap<>(Map.of("releaseName", "om1", "namespace", "om"));

        service.queueAtlasFederationReapplySteps(root, rootParams, "om", "om1", CLUSTER, ambari);

        Map<String, Object> register = (Map<String, Object>) paramsOf(step(stepsOf(root), CommandType.OM_ATLAS_FEDERATION_REGISTER));
        assertNotNull(CommandService.federationAuthzNotice(register, "svc-om"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anLdapAtlasWithRangerOrAFileUserStoreNeedsNoManualGrant() throws Exception {
        atlasAuth(false, true, false);
        atlasOnRanger();
        secret("om-ldap-account", Map.of("username", "svc-om", "password", "pw"));
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace("om");
        request.setReleaseName("om1");
        request.setFormValues(new LinkedHashMap<>(Map.of("atlasFederation",
                Map.of("enabled", true, "existingSecret", "om-ldap-account"))));
        CommandEntity root = newRoot();

        service.queueAtlasFederationSteps(root, request, new LinkedHashMap<>(), CLUSTER, ambari);

        Map<String, Object> register = (Map<String, Object>) paramsOf(step(stepsOf(root), CommandType.OM_ATLAS_FEDERATION_REGISTER));
        assertNull(CommandService.federationAuthzNotice(register, "svc-om"), "Ranger grants the account itself");
        assertNull(CommandService.federationAuthzNotice(
                new LinkedHashMap<>(Map.of("_atlasAuthMode", "basic", "_atlasAclMode", "simple")), "openmetadata-federation"),
                "the file-store user is granted by the simple-authz step");
    }

    @Test
    void theConfigCheckOfAnLdapAccountOnTheSimpleAuthorizerWarnsThatTheAccountMustBeGrantedByHand() throws Exception {
        atlasAuth(false, true, false);
        secret("om-ldap-account", Map.of("username", "svc-om", "password", "ldap-pw"));
        deployedWithSecret("om-ldap-account");
        com.sun.net.httpserver.HttpServer atlas = atlasAdmitting("svc-om", "ldap-pw");
        try {
            Map<String, Object> report = service.checkAtlasFederationConfig("om", "om1", CLUSTER, ambari);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> checks = (List<Map<String, Object>>) report.get("checks");
            Map<String, Object> authz = checks.stream().filter(c -> "atlas.authz.simple".equals(c.get("id")))
                    .findFirst().orElse(null);
            assertNotNull(authz, String.valueOf(checks));
            assertEquals(false, authz.get("ok"));
            assertEquals("warning", authz.get("severity"));
            assertTrue(String.valueOf(authz.get("remediation")).contains("'svc-om'"), String.valueOf(authz));
            assertTrue(String.valueOf(authz.get("remediation")).contains("atlas-simple-authz-policy.json"));
            Map<String, Object> probe = checks.stream().filter(c -> "atlas.federation.authProbe".equals(c.get("id")))
                    .findFirst().orElseThrow();
            assertFalse(String.valueOf(probe.get("detail")).contains("LIVE"),
                    "signing in does not prove the account may read: " + probe.get("detail"));
        } finally {
            atlas.stop(0);
        }
    }

    @Test
    void theConfigCheckOfAnLdapAccountOnRangerHasNoManualGrantWarning() throws Exception {
        atlasAuth(false, true, false);
        atlasOnRanger();
        secret("om-ldap-account", Map.of("username", "svc-om", "password", "ldap-pw"));
        deployedWithSecret("om-ldap-account");

        Map<String, Object> report = service.checkAtlasFederationConfig("om", "om1", CLUSTER, ambari);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) report.get("checks");
        assertTrue(checks.stream().noneMatch(c -> "atlas.authz.simple".equals(c.get("id"))), String.valueOf(checks));
    }

    @Test
    void theWizardIsToldAtlasSignsInAgainstLdapEvenWhenFileAuthenticationIsReportedFirst() throws Exception {
        atlasAuth(true, true, false);

        org.apache.ambari.view.k8s.model.ResolvedContext rc = new ContextService(view.context).resolve("", ambari, CLUSTER);

        assertEquals("basic", rc.getAtlasAuthMode());
        assertTrue(rc.toClientView().atlasLdapAuth, "the wizard can tell the LDAP service account is used: no Atlas restart");
        atlasAuth(true, false, false);
        assertFalse(new ContextService(view.context).resolve("", ambari, CLUSTER).toClientView().atlasLdapAuth);
    }
}
