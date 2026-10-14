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
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.deployment.DeploymentBackend;
import org.apache.ambari.view.k8s.service.deployment.DeploymentContext;
import org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend;
import org.apache.ambari.view.k8s.service.deployment.HelmDirectBackend;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.HelmRepoRepo;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.SecurityProfileEntity;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What {@link CommandService#submitDeploy} hands the deployment backends when a release is redeployed without the
 * wizard (resync, security refresh, autoscaling change) or with its chart source only in the query parameters: the
 * chart repository, chart version, platform context, external Kerberos keytab and reusable catalogs the release runs
 * with, and never Kerberos turned off on a release that runs with it.
 */
class CommandServiceRedeployDefaultsTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "trino";
    private static final String KEYTAB_SECRET = "trino-cdp-keytab";
    private static final String PRINCIPAL = "trino/coordinator@CDP.EXAMPLE.COM";

    @TempDir
    Path tmp;

    private ViewContext ctx;
    private DataStore ds;
    private KubernetesService k8s;
    private DeploymentBackend direct;
    private final Map<String, K8sReleaseEntity> releases = new ConcurrentHashMap<>();
    private final AtomicReference<HelmDeployRequest> sentRequest = new AtomicReference<>();
    private final AtomicReference<DeploymentContext> sentContext = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", tmp.resolve("work").toString()));
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getInstanceName()).thenReturn("kdps");
        when(ctx.getInstanceData(anyString())).thenReturn(null);
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
        ds = mock(DataStore.class);
        when(ctx.getDataStore()).thenReturn(ds);
        doAnswer(inv -> {
            K8sReleaseEntity entity = inv.getArgument(0);
            releases.put(entity.getId(), entity);
            return null;
        }).when(ds).store(any(K8sReleaseEntity.class));
        when(ds.find(eq(K8sReleaseEntity.class), anyString())).thenAnswer(inv -> releases.get((String) inv.getArgument(1)));

        k8s = mock(KubernetesService.class);
        direct = mock(DeploymentBackend.class);
        when(direct.apply(any(), any())).thenAnswer(inv -> {
            sentRequest.set(inv.getArgument(0));
            sentContext.set(inv.getArgument(1));
            return "command-1";
        });
    }

    /** A CommandService wired to the mocks, without the executors and singletons its constructor starts. */
    private CommandService service(DeploymentBackend gitOps) throws Exception {
        CommandService service = mock(CommandService.class, CALLS_REAL_METHODS);
        set(service, "ctx", ctx);
        set(service, "kubernetesService", k8s);
        Map<String, DeploymentBackend> backends = new ConcurrentHashMap<>();
        backends.put("DIRECT_HELM", direct);
        if (gitOps != null) {
            backends.put("FLUX_GITOPS", gitOps);
        }
        set(service, "deploymentBackends", backends);
        return service;
    }

    private static void set(CommandService service, String field, Object value) throws Exception {
        Field f = CommandService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    private String submit(HelmDeployRequest request, String repoId, String version) throws Exception {
        return service(null).submitDeploy(request, repoId, version, null, null, null, null, null);
    }

    /** The Trino release as KDPS recorded it at install time. */
    private K8sReleaseEntity recordRelease(String platformContextId, String version) {
        return recordRelease("TRINO", "trinodb/trino", platformContextId, version);
    }

    /** The release as KDPS recorded it at install time. */
    private K8sReleaseEntity recordRelease(String serviceKey, String chartRef, String platformContextId, String version) {
        K8sReleaseEntity entity = new K8sReleaseEntity();
        entity.setId(K8sReleaseEntity.idOf(NS, RELEASE));
        entity.setNamespace(NS);
        entity.setReleaseName(RELEASE);
        entity.setServiceKey(serviceKey);
        entity.setChartRef(chartRef);
        entity.setRepoId("charts");
        entity.setVersion(version);
        entity.setPlatformContextId(platformContextId);
        releases.put(entity.getId(), entity);
        return entity;
    }

    /** The values of a Trino release deployed against an external context with the operator's keytab. */
    private static Map<String, Object> kerberizedValues(String keytabSecret) {
        Map<String, Object> kerberos = new LinkedHashMap<>();
        kerberos.put("enabled", true);
        kerberos.put("injectionMode", "PRE_PROVISIONED");
        kerberos.put("configMapName", "trino-krb5-conf");
        if (keytabSecret != null) {
            kerberos.put("keytab", new LinkedHashMap<>(Map.of("secretName", keytabSecret, "secretDataKey", "service.keytab")));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("global", new LinkedHashMap<>(Map.of("security", new LinkedHashMap<>(Map.of("kerberos", kerberos)))));
        values.put("server", new LinkedHashMap<>(Map.of("workers", 2)));
        return values;
    }

    /** Trino's Hive catalog as the deploy renders it for a Kerberized metastore, on the coordinator and workers. */
    private static void putHiveCatalog(Map<String, Object> values, String principal) {
        String catalog = "connector.name=hive\n"
                + "hive.metastore.uri=thrift://metastore.cdp.example.com:9083\n"
                + "hive.metastore.authentication.type=KERBEROS\n"
                + "hive.metastore.client.principal=" + principal + "\n"
                + "hive.metastore.client.keytab=/etc/security/keytabs/service.keytab\n";
        CommandService.putDotted(values, "coordinator.additionalCatalogs.hive", catalog);
        CommandService.putDotted(values, "worker.additionalCatalogs.hive", catalog);
    }

    private static Secret keytabSecret(boolean withPrincipal) {
        SecretBuilder secret = new SecretBuilder()
                .withNewMetadata().withName(KEYTAB_SECRET).withNamespace(NS).endMetadata()
                .addToData("service.keytab", base64("keytab-bytes"));
        if (withPrincipal) {
            secret.addToData("principal", base64(PRINCIPAL));
        }
        return secret.build();
    }

    private static String base64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A redeploy that names neither a chart version nor a form, as an API caller or the Releases page of an earlier
     * build sends it for "Resync / restart" or "Refresh security profile".
     */
    private static HelmDeployRequest resyncRequest(Map<String, Object> currentValues) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setChart("trinodb/trino");
        request.setReleaseName(RELEASE);
        request.setNamespace(NS);
        request.setValues(currentValues);
        request.setServiceKey("TRINO");
        request.setRepoId("charts");
        request.setDeploymentMode("DIRECT_HELM");
        return request;
    }

    @Test
    void aResyncOfAnExternalContextReleaseKeepsItsVersionContextAndKeytab() throws Exception {
        recordRelease("cdp-prod", "1.43.12");
        when(k8s.deployedChartVersion(NS, RELEASE)).thenReturn("1.43.14");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(kerberizedValues(KEYTAB_SECRET));
        when(k8s.getSecret(NS, KEYTAB_SECRET)).thenReturn(keytabSecret(true));

        submit(resyncRequest(kerberizedValues(KEYTAB_SECRET)), "charts", null);

        HelmDeployRequest sent = sentRequest.get();
        assertEquals("1.43.14", sentContext.get().getVersion(), "the chart version running on the cluster, not the newest");
        assertEquals("1.43.14", sent.getVersion());
        assertEquals("charts", sentContext.get().getRepoId());
        assertEquals("cdp-prod", sent.getPlatformContextId(), "the release stays on its platform context");
        assertTrue(CommandService.deployUsesExternalKeytab(sent.getFormValues()),
                "with the keytab, a direct deploy on an external context turns Kerberos on, never off");
        assertEquals(KEYTAB_SECRET, ConfigResolutionService.getByDottedPath(sent.getFormValues(), "hive.externalKeytabSecret"));
        assertEquals(PRINCIPAL, ConfigResolutionService.getByDottedPath(sent.getFormValues(), "hive.externalKeytabPrincipal"),
                "the principal comes from the Secret's principal key");
    }

    /**
     * What the Releases page sends for "Resync / restart", "Refresh security profile" and a worker autoscaling change:
     * the release's platform context and no chart version, which the page may show out of date.
     */
    private static HelmDeployRequest pageRedeployRequest(Map<String, Object> currentValues, String platformContextId) {
        HelmDeployRequest request = resyncRequest(currentValues);
        request.setFormValues(new LinkedHashMap<>(Map.of("platformContextId", platformContextId)));
        return request;
    }

    @Test
    void aRedeployFromTheReleasesPageRunsTheChartVersionOnTheClusterNotTheRecordedOne() throws Exception {
        // Installed with Helm at 1.43.12, then moved to 1.43.14 by a resync that recorded no version.
        recordRelease("cdp-prod", "1.43.12").setDeploymentMode("DIRECT_HELM");
        when(k8s.deployedChartVersion(NS, RELEASE)).thenReturn("1.43.14");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(kerberizedValues(KEYTAB_SECRET));
        when(k8s.getSecret(NS, KEYTAB_SECRET)).thenReturn(keytabSecret(true));

        submit(pageRedeployRequest(kerberizedValues(KEYTAB_SECRET), "cdp-prod"), null, null);

        HelmDeployRequest sent = sentRequest.get();
        assertEquals("1.43.14", sentContext.get().getVersion(), "no silent downgrade to the recorded chart");
        assertEquals("1.43.14", sent.getVersion());
        assertEquals("charts", sentContext.get().getRepoId());
        assertEquals("cdp-prod", sent.getPlatformContextId());
        assertEquals(KEYTAB_SECRET, ConfigResolutionService.getByDottedPath(sent.getFormValues(), "hive.externalKeytabSecret"),
                "a form that only names the context still keeps the release's keytab");
    }

    @Test
    void anUpgradeCarryingTheHiddenValuesOfTheReleaseKeepsItsDeployedPasswords() throws Exception {
        recordRelease(null, "1.43.12");
        Map<String, Object> deployed = kerberizedValues(null);
        CommandService.putDotted(deployed, "auth.adminPassword", "Deployed-Pass-764");
        CommandService.putDotted(deployed, "server.config.https.keystore.password", "Keystore-Pass-764");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(deployed);
        // What GET .../values gave the upgrade wizard, sent back with one password retyped and one value changed.
        Map<String, Object> sentBack = HiddenReleaseValues.hide(deployed, java.util.Set.of());
        CommandService.putDotted(sentBack, "server.config.https.keystore.password", "Keystore-Pass-Retyped");
        CommandService.putDotted(sentBack, "server.workers", 3);

        submit(resyncRequest(sentBack), null, null);

        Map<String, Object> values = sentRequest.get().getValues();
        assertEquals("Deployed-Pass-764", ConfigResolutionService.getByDottedPath(values, "auth.adminPassword"),
                "the password nobody retyped is the deployed one, not the placeholder");
        assertEquals("Keystore-Pass-Retyped",
                ConfigResolutionService.getByDottedPath(values, "server.config.https.keystore.password"));
        assertEquals(3, ConfigResolutionService.getByDottedPath(values, "server.workers"));
        assertFalse(HiddenReleaseValues.containsPlaceholder(values));
    }

    @Test
    void aHiddenValueTheReleaseCannotGiveBackIsRefusedBeforeAnythingIsDeployed() throws Exception {
        recordRelease(null, "1.43.12");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(kerberizedValues(null));
        Map<String, Object> values = kerberizedValues(null);
        CommandService.putDotted(values, "auth.adminPassword", HiddenReleaseValues.PLACEHOLDER);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> submit(resyncRequest(values), null, null));

        assertTrue(refused.getMessage().contains("'auth.adminPassword'"), refused.getMessage());
        verify(direct, never()).apply(any(), any());
    }

    @Test
    void aRedeployAfterARollbackKeepsTheRolledBackChartVersion() throws Exception {
        // Recorded at 1.43.14, then rolled back with Helm to 1.43.12, which KDPS does not record.
        recordRelease(null, "1.43.14");
        when(k8s.deployedChartVersion(NS, RELEASE)).thenReturn("1.43.12");

        submit(resyncRequest(new LinkedHashMap<>()), null, null);

        assertEquals("1.43.12", sentContext.get().getVersion(), "the chart the operator rolled back from is not re-applied");
        assertEquals("1.43.12", sentRequest.get().getVersion());
    }

    @Test
    void aResyncWithoutAVersionReusesTheRecordedOneWhenHelmReportsNone() throws Exception {
        recordRelease(null, "1.43.12");

        submit(resyncRequest(new LinkedHashMap<>()), null, null);

        assertEquals("1.43.12", sentContext.get().getVersion());
        assertEquals("1.43.12", sentRequest.get().getVersion());
        assertEquals("charts", sentContext.get().getRepoId(), "the repository the release was installed from");
    }

    @Test
    void latestIsNotAVersionToKeep() throws Exception {
        recordRelease(null, "latest");
        when(k8s.deployedChartVersion(NS, RELEASE)).thenReturn("1.43.14");
        HelmDeployRequest request = resyncRequest(new LinkedHashMap<>());
        request.setVersion("latest");

        submit(request, null, "latest");

        assertEquals("1.43.14", sentContext.get().getVersion());
        assertEquals("1.43.14", sentRequest.get().getVersion());
        assertNull(CommandService.pinnedChartVersion(" Latest "));
        assertNull(CommandService.pinnedChartVersion(" "));
        assertEquals("1.43.14", CommandService.pinnedChartVersion(" 1.43.14 "));
    }

    @Test
    void theQueryParametersOfAFirstInstallGoIntoTheRequest() throws Exception {
        HelmDeployRequest request = resyncRequest(new LinkedHashMap<>());
        request.setRepoId(null);

        submit(request, "charts", "1.43.14");

        assertEquals("charts", sentRequest.get().getRepoId());
        assertEquals("1.43.14", sentRequest.get().getVersion());
        assertEquals("charts", sentContext.get().getRepoId());
        assertEquals("1.43.14", sentContext.get().getVersion());
        assertNull(sentRequest.get().getFormValues(), "a first install has no release to inherit from");
    }

    @Test
    void anUpgradeKeepsTheVersionItAsksFor() throws Exception {
        recordRelease("cdp-prod", "1.43.12");
        when(k8s.deployedChartVersion(NS, RELEASE)).thenReturn("1.43.12");

        submit(resyncRequest(new LinkedHashMap<>()), "charts", "1.43.14");

        assertEquals("1.43.14", sentContext.get().getVersion());
        assertEquals("1.43.14", sentRequest.get().getVersion());
    }

    @Test
    void theChartVersionCheckRunsOnTheKeptVersion() throws Exception {
        recordRelease(null, "1.42.0");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> submit(resyncRequest(new LinkedHashMap<>()), null, null));

        assertTrue(refused.getMessage().contains("Chart version 1.42.0 does not match"), refused.getMessage());
        assertNull(sentRequest.get());
    }

    @Test
    void anExternalKeytabThatNoLongerExistsStopsTheRedeploy() throws Exception {
        recordRelease("cdp-prod", "1.43.14");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(kerberizedValues(KEYTAB_SECRET));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> submit(resyncRequest(kerberizedValues(KEYTAB_SECRET)), null, null));

        assertTrue(refused.getMessage().contains("'" + NS + "/" + KEYTAB_SECRET + "', which no longer exists"),
                refused.getMessage());
        assertNull(sentRequest.get(), "nothing is deployed");
        verify(k8s, never()).createNamespace(anyString());
    }

    @Test
    void aRedeployThatCannotReadTheReleaseValuesStopsRatherThanDropTheKeytab() throws Exception {
        // A 5xx, or a 401 the token renewal could not fix: whether the release runs with a keytab is unknown.
        recordRelease("cdp-prod", "1.43.14");
        when(k8s.getHelmReleaseValues(NS, RELEASE))
                .thenThrow(new RuntimeException("Could not retrieve configuration: Internal Server Error"));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> submit(pageRedeployRequest(kerberizedValues(KEYTAB_SECRET), "cdp-prod"), null, null));

        assertTrue(refused.getMessage().startsWith("Could not read the values of " + RELEASE
                + " to keep its Kerberos keytab: Could not retrieve configuration: Internal Server Error"), refused.getMessage());
        assertTrue(refused.getMessage().endsWith("Try again."), refused.getMessage());
        assertNull(sentRequest.get(), "nothing is deployed, so Kerberos is not turned off");
        verify(k8s, never()).createNamespace(anyString());
    }

    @Test
    void aReleaseWithNoValuesOnItsExternalContextIsNotRefused() throws Exception {
        // No Helm Secret is an empty map, not a failed read: there is no keytab to keep.
        recordRelease("cdp-prod", "1.43.14");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(Map.of());

        submit(pageRedeployRequest(new LinkedHashMap<>(), "cdp-prod"), null, null);

        assertNotNull(sentRequest.get());
        assertFalse(CommandService.deployUsesExternalKeytab(sentRequest.get().getFormValues()));
    }

    @Test
    void aKerberizedReleaseWithoutAKeytabInItsValuesStopsTheRedeploy() throws Exception {
        recordRelease("cdp-prod", "1.43.14");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(kerberizedValues(null));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> submit(resyncRequest(kerberizedValues(null)), null, null));

        assertTrue(refused.getMessage().contains("would turn Kerberos off"), refused.getMessage());
        assertTrue(refused.getMessage().contains("platform context 'cdp-prod'"), refused.getMessage());
        assertNull(sentRequest.get());
    }

    @Test
    void aKeytabSecretWithoutAPrincipalKeepsThePrincipalTheCatalogsRunWith() throws Exception {
        recordRelease("cdp-prod", "1.43.14");
        Map<String, Object> deployed = kerberizedValues(KEYTAB_SECRET);
        putHiveCatalog(deployed, PRINCIPAL);
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(deployed);
        when(k8s.getSecret(NS, KEYTAB_SECRET)).thenReturn(keytabSecret(false));

        submit(resyncRequest(deployed), null, null);

        Map<String, Object> form = sentRequest.get().getFormValues();
        assertEquals(KEYTAB_SECRET, ConfigResolutionService.getByDottedPath(form, "hive.externalKeytabSecret"));
        assertEquals(PRINCIPAL, ConfigResolutionService.getByDottedPath(form, "hive.externalKeytabPrincipal"),
                "the Hive catalog's principal, typed by the operator at install time, wins over every fallback");
    }

    @Test
    void anUnrenderedPrincipalTemplateIsNotKept() throws Exception {
        recordRelease("cdp-prod", "1.43.14");
        Map<String, Object> deployed = kerberizedValues(KEYTAB_SECRET);
        putHiveCatalog(deployed, "{{kerberos.entries.coordinator.principal}}");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(deployed);
        when(k8s.getSecret(NS, KEYTAB_SECRET)).thenReturn(keytabSecret(false));

        submit(resyncRequest(deployed), null, null);

        assertNull(ConfigResolutionService.getByDottedPath(sentRequest.get().getFormValues(), "hive.externalKeytabPrincipal"));
    }

    @Test
    void aReleaseThatNamesNoPrincipalAnywhereIsKeptWithoutOne() throws Exception {
        // No principal key in the Secret and no Kerberized catalog: nothing the release runs with depends on one.
        recordRelease("cdp-prod", "1.43.14");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(kerberizedValues(KEYTAB_SECRET));
        when(k8s.getSecret(NS, KEYTAB_SECRET)).thenReturn(keytabSecret(false));

        submit(resyncRequest(kerberizedValues(KEYTAB_SECRET)), null, null);

        Map<String, Object> form = sentRequest.get().getFormValues();
        assertEquals(KEYTAB_SECRET, ConfigResolutionService.getByDottedPath(form, "hive.externalKeytabSecret"));
        assertNull(ConfigResolutionService.getByDottedPath(form, "hive.externalKeytabPrincipal"));
    }

    @Test
    void anAmbariKeytabIsNotCarriedOverToAnExternalContext() throws Exception {
        // Deployed on the Kerberized managed context: Ambari minted the keytab, and the values look like an operator's.
        recordRelease(ContextService.DEFAULT_CONTEXT_ID, "1.43.14");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(kerberizedValues("trino-keytab"));
        when(k8s.getSecret(NS, "trino-keytab")).thenReturn(keytabSecret(true));
        HelmDeployRequest request = resyncRequest(kerberizedValues("trino-keytab"));
        request.setFormValues(new LinkedHashMap<>(Map.of("platformContextId", "cdp-prod")));

        submit(request, null, null);

        HelmDeployRequest sent = sentRequest.get();
        assertEquals("cdp-prod", sent.getPlatformContextId());
        assertFalse(CommandService.deployUsesExternalKeytab(sent.getFormValues()),
                "a keytab from the local realm is not the new context's: the deploy asks for one itself");
        verify(k8s, never()).getHelmReleaseValues(anyString(), anyString());
    }

    @Test
    void aKeytabIsNotCarriedOverToAnotherExternalContext() throws Exception {
        recordRelease("cdp-prod", "1.43.14");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(kerberizedValues(KEYTAB_SECRET));
        when(k8s.getSecret(NS, KEYTAB_SECRET)).thenReturn(keytabSecret(true));
        HelmDeployRequest request = resyncRequest(kerberizedValues(KEYTAB_SECRET));
        request.setFormValues(new LinkedHashMap<>(Map.of("platformContextId", "cdp-dr")));

        submit(request, null, null);

        HelmDeployRequest sent = sentRequest.get();
        assertEquals("cdp-dr", sent.getPlatformContextId());
        assertFalse(CommandService.deployUsesExternalKeytab(sent.getFormValues()),
                "cdp-prod's keytab belongs to another realm: the deploy asks for cdp-dr's itself");
        verify(k8s, never()).getHelmReleaseValues(anyString(), anyString());
    }

    @Test
    void aServiceWithoutAnOperatorKeytabIsNotRefused() throws Exception {
        // A service without a Kerberos keytab field to restore or select, whatever its values say.
        recordRelease("ZZTESTKUBEONLY", "gitlab", "cdp-prod", "1.0.0");
        Map<String, Object> values = kerberizedValues(null);
        HelmDeployRequest request = resyncRequest(values);
        request.setChart("gitlab");
        request.setServiceKey("ZZTESTKUBEONLY");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(values);

        submit(request, null, null);

        HelmDeployRequest sent = sentRequest.get();
        assertNotNull(sent, "the redeploy goes through as before");
        assertEquals("cdp-prod", sent.getPlatformContextId());
        assertFalse(CommandService.deployUsesExternalKeytab(sent.getFormValues()));
        assertEquals(true, ConfigResolutionService.getByDottedPath(sent.getValues(), "global.security.kerberos.enabled"));
        verify(k8s, never()).getHelmReleaseValues(anyString(), anyString());
    }

    @Test
    void turningKerberosOffOnPurposeIsNotRefused() throws Exception {
        recordRelease("cdp-prod", "1.43.14");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(kerberizedValues(null));
        Map<String, Object> values = kerberizedValues(null);
        CommandService.putDotted(values, "global.security.kerberos.enabled", false);

        submit(resyncRequest(values), null, null);

        assertNotNull(sentRequest.get());
        assertFalse(CommandService.deployUsesExternalKeytab(sentRequest.get().getFormValues()));
    }

    @Test
    void aKeytabFieldClearedInTheFormIsNotRefilled() throws Exception {
        recordRelease("cdp-prod", "1.43.14");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(kerberizedValues(KEYTAB_SECRET));
        when(k8s.getSecret(NS, KEYTAB_SECRET)).thenReturn(keytabSecret(true));
        HelmDeployRequest request = resyncRequest(kerberizedValues(KEYTAB_SECRET));
        request.setFormValues(new LinkedHashMap<>(Map.of("platformContextId", "cdp-prod",
                "hive", new LinkedHashMap<>(Map.of("externalKeytabSecret", "")))));

        submit(request, null, null);

        assertEquals("", ConfigResolutionService.getByDottedPath(sentRequest.get().getFormValues(), "hive.externalKeytabSecret"));
    }

    @Test
    void aReleaseOnTheManagedContextKeepsFollowingTheAmbariCluster() throws Exception {
        recordRelease(ContextService.DEFAULT_CONTEXT_ID, "1.43.14");

        submit(resyncRequest(kerberizedValues(KEYTAB_SECRET)), null, null);

        assertEquals(ContextService.DEFAULT_CONTEXT_ID, sentRequest.get().getPlatformContextId());
        assertFalse(CommandService.deployUsesExternalKeytab(sentRequest.get().getFormValues()));
        verify(k8s, never()).getHelmReleaseValues(anyString(), anyString());
    }

    /** A bare Git repository Flux reads, seeded with one commit on main; returns its URL. */
    private String gitOpsRepository() throws Exception {
        Path bareRepo = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bareRepo.toFile()).call().close();
        // No background gc after a push: it would still hold gc.log.lock when the temp directory is removed.
        try (Git bare = Git.open(bareRepo.toFile())) {
            org.eclipse.jgit.lib.StoredConfig bareConfig = bare.getRepository().getConfig();
            bareConfig.setInt("gc", null, "auto", 0);
            bareConfig.setBoolean("receive", null, "autogc", false);
            bareConfig.save();
        }
        String repoUrl = bareRepo.toUri().toString();
        Path seed = Files.createDirectories(tmp.resolve("seed"));
        try (Git g = Git.cloneRepository().setURI(repoUrl).setDirectory(seed.toFile()).call()) {
            Files.writeString(seed.resolve("README.md"), "seed");
            g.add().addFilepattern(".").call();
            g.commit().setMessage("init").setAuthor("seed", "seed@example.com").call();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
        }
        HelmRepoEntity charts = new HelmRepoEntity();
        charts.setId("charts");
        charts.setType("HTTP");
        charts.setUrl("https://charts.example.com");
        charts.setAuthMode("anonymous");
        when(ds.find(eq(HelmRepoEntity.class), eq("charts"))).thenReturn(charts);
        return repoUrl;
    }

    /** A Trino deploy in GitOps mode, committed straight to main. */
    private static HelmDeployRequest gitOpsRequest(String repoUrl, Map<String, Object> values) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setChart("trinodb/trino");
        request.setReleaseName(RELEASE);
        request.setNamespace(NS);
        request.setServiceKey("TRINO");
        request.setDeploymentMode("FLUX_GITOPS");
        request.setValues(values);
        HelmDeployRequest.GitOptions git = new HelmDeployRequest.GitOptions();
        git.setRepoUrl(repoUrl);
        git.setBaseBranch("main");
        git.setBranch("main");
        git.setCommitMode("DIRECT_COMMIT");
        request.setGit(git);
        return request;
    }

    /** A file as published on the main branch of the GitOps repository, or null. */
    private String published(String repoUrl, String path) throws Exception {
        Path clone = tmp.resolve("verify-" + System.nanoTime());
        try (Git ignored = org.apache.ambari.view.k8s.utils.TestGitClones.clone(repoUrl, "main", clone)) {
            Path file = clone.resolve(path);
            return Files.exists(file) ? Files.readString(file) : null;
        }
    }

    /** Ambari runs KDPS only (OpenShift): it manages no cluster, so there is no Kerberos to inherit from it. */
    private static CommandUtils ambariManagingNoCluster() throws Exception {
        CommandUtils commandUtils = mock(CommandUtils.class);
        when(commandUtils.resolveClusterName(anyString(), any())).thenThrow(new CommandUtils.NoAmbariClusterException());
        return commandUtils;
    }

    private static void setField(Object target, Class<?> owner, String field, Object value) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void aGitOpsSubmitWithTheRepositoryAndVersionOnlyAsQueryParametersPinsThem() throws Exception {
        String repoUrl = gitOpsRepository();
        FluxGitOpsBackend flux = new FluxGitOpsBackend(Files.createDirectories(tmp.resolve("flux")), k8s, ctx);

        // As the service wizard sends it: the repository and version only as query parameters.
        HelmDeployRequest request = gitOpsRequest(repoUrl,
                new LinkedHashMap<>(Map.of("server", new LinkedHashMap<>(Map.of("workers", 2)))));

        String sha = service(flux).submitDeploy(request, "charts", "1.43.14", null, null, null, null, null);

        assertEquals(40, sha.length());
        String helmRelease = published(repoUrl, "clusters/default/analytics/trino/helmrelease.yaml");
        assertTrue(helmRelease.contains("      chart: trino\n      version: 1.43.14\n"), helmRelease);
        assertTrue(helmRelease.contains("        kind: HelmRepository\n        name: trino-charts\n"), helmRelease);
        assertEquals("1.43.14", releases.get(K8sReleaseEntity.idOf(NS, RELEASE)).getVersion());
    }

    @Test
    void aGitOpsRedeployKeepsTheVersionCommittedToGitNotTheOneHelmStillRuns() throws Exception {
        // Upgraded to 1.43.14 through Git; Flux has not reconciled yet (or the pull request is still open), so Helm
        // still reports 1.43.12. A resync, security refresh or autoscaling change must not commit 1.43.12 back.
        String repoUrl = gitOpsRepository();
        recordRelease(null, "1.43.14").setDeploymentMode("FLUX_GITOPS");
        when(k8s.deployedChartVersion(NS, RELEASE)).thenReturn("1.43.12");
        FluxGitOpsBackend flux = new FluxGitOpsBackend(Files.createDirectories(tmp.resolve("flux")), k8s, ctx);

        // As the Releases page sends it: the release's repository and no chart version.
        HelmDeployRequest request = gitOpsRequest(repoUrl,
                new LinkedHashMap<>(Map.of("server", new LinkedHashMap<>(Map.of("workers", 3)))));
        request.setRepoId("charts");
        String sha = service(flux).submitDeploy(request, "charts", null, null, null, null, null, null);

        assertEquals(40, sha.length());
        String helmRelease = published(repoUrl, "clusters/default/analytics/trino/helmrelease.yaml");
        assertTrue(helmRelease.contains("      chart: trino\n      version: 1.43.14\n"), helmRelease);
        assertEquals("1.43.14", releases.get(K8sReleaseEntity.idOf(NS, RELEASE)).getVersion());
        verify(k8s, never()).deployedChartVersion(anyString(), anyString());
    }

    @Test
    void aGitOpsRedeployThatCannotReadTheReleaseValuesCommitsNothing() throws Exception {
        String repoUrl = gitOpsRepository();
        recordRelease(null, "1.43.14").setDeploymentMode("FLUX_GITOPS");
        when(k8s.getHelmReleaseValues(NS, RELEASE))
                .thenThrow(new RuntimeException("Could not retrieve configuration: Service Unavailable"));
        FluxGitOpsBackend flux = new FluxGitOpsBackend(Files.createDirectories(tmp.resolve("flux")), k8s, ctx);
        setField(flux, FluxGitOpsBackend.class, "commandUtils", ambariManagingNoCluster());

        RuntimeException refused = assertThrows(RuntimeException.class, () -> service(flux).submitDeploy(
                gitOpsRequest(repoUrl, kerberizedValues(KEYTAB_SECRET)), "charts", null, null, null, null, ambariApi(), null));

        Throwable cause = refused.getCause();
        assertTrue(cause instanceof IllegalStateException, String.valueOf(cause));
        assertTrue(cause.getMessage().contains("to check whether it runs with Kerberos"), cause.getMessage());
        assertNull(published(repoUrl, "clusters/default/analytics/trino/helmrelease.yaml"), "nothing is committed");
    }

    @Test
    void aReleaseInstalledFromAChartUrlOrOciReferenceIsRedeployedDirectlyButNeverWrittenToGit() throws Exception {
        DeploymentBackend gitOps = mock(DeploymentBackend.class);
        for (String chart : List.of("oci://registry.example.com/charts/trino",
                "https://charts.example.com/trino-1.43.15.tgz", "kdps/trino:1.43.15", "org/charts/trino")) {
            recordRelease("TRINO", chart, ContextService.DEFAULT_CONTEXT_ID, "1.43.14");
            HelmDeployRequest resync = resyncRequest(new LinkedHashMap<>());
            resync.setChart(chart);

            service(gitOps).submitDeploy(resync, "charts", "1.43.14", null, null, null, null, null);

            assertEquals(chart, sentRequest.get().getChart(), "Helm resolves the reference as before");

            // The same chart in a GitOps deploy: a HelmRelease names a chart of its chart repository, not a URL.
            HelmDeployRequest gitOpsResync = resyncRequest(new LinkedHashMap<>());
            gitOpsResync.setChart(chart);
            gitOpsResync.setDeploymentMode("FLUX_GITOPS");
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service(gitOps)
                    .submitDeploy(gitOpsResync, "charts", "1.43.14", null, null, null, null, null));
            assertTrue(refused.getMessage().startsWith("Chart '" + chart + "' is not valid"), refused.getMessage());
        }
        verify(gitOps, never()).apply(any(), any());
        verify(k8s, org.mockito.Mockito.times(4)).createNamespace(NS);
    }

    @Test
    void aRedeployWithoutTheCatalogSectionKeepsTheRecordedReusableCatalogs() throws Exception {
        // Installed from the wizard with a reusable catalog; the resync's form only carries the recorded context.
        K8sReleaseEntity recorded = recordRelease(ContextService.DEFAULT_CONTEXT_ID, "1.43.14");
        recorded.setCatalogRefsJson("{\"postgres_prod\":{\"id\":\"cat-1\",\"hash\":\"abc\"}}");

        submit(resyncRequest(new LinkedHashMap<>()), null, null);

        Map<String, Object> form = sentRequest.get().getFormValues();
        assertEquals(ContextService.DEFAULT_CONTEXT_ID, form.get("platformContextId"));
        assertFalse(form.containsKey("_catalogRefsJson"),
                "a redeploy that names no catalogs leaves the recorded ones alone: " + form);
    }

    @Test
    void deselectingEveryReusableCatalogInTheWizardStillClearsThem() throws Exception {
        K8sReleaseEntity recorded = recordRelease(ContextService.DEFAULT_CONTEXT_ID, "1.43.14");
        recorded.setCatalogRefsJson("{\"postgres_prod\":{\"id\":\"cat-1\",\"hash\":\"abc\"}}");
        HelmDeployRequest request = resyncRequest(new LinkedHashMap<>());
        request.setFormValues(new LinkedHashMap<>(Map.of("platformContextId", ContextService.DEFAULT_CONTEXT_ID,
                "customCatalogs", new LinkedHashMap<>(Map.of("refs", List.of(), "text", "")))));

        submit(request, "charts", "1.43.14");

        Map<String, Object> form = sentRequest.get().getFormValues();
        assertTrue(form.containsKey("_catalogRefsJson"), form.toString());
        assertNull(form.get("_catalogRefsJson"), "an empty selection clears the recorded catalogs");
    }

    @Test
    void aDirectRedeployOfAKerberizedReleaseWithNoRecordedContextIsRefused() throws Exception {
        // Deployed against an external context with the operator's keytab before KDPS recorded the context on the
        // release; Ambari manages no cluster, so the deploy would otherwise follow it and turn Kerberos off.
        recordRelease(null, "1.43.14");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(kerberizedValues(KEYTAB_SECRET));
        CommandService service = service(null);
        set(service, "gson", new Gson());
        set(service, "repositoryDao", new HelmRepoRepo(ds));
        CommandUtils noCluster = ambariManagingNoCluster();
        set(service, "commandUtils", noCluster);
        set(service, "dynamicValuesResolver", new DynamicValuesResolver(ctx, noCluster));
        when(k8s.getConfigurationService()).thenReturn(mock(ViewConfigurationService.class));
        backendsOf(service).put("DIRECT_HELM", new HelmDirectBackend(service));

        RuntimeException refused = assertThrows(RuntimeException.class, () -> service.submitDeploy(
                resyncRequest(kerberizedValues(KEYTAB_SECRET)), null, null, null, null, null, ambariApi(), null));

        Throwable cause = refused.getCause();
        assertTrue(cause instanceof IllegalStateException, String.valueOf(cause));
        assertTrue(cause.getMessage().contains("no platform context is recorded for it"), cause.getMessage());
        assertTrue(cause.getMessage().contains("set global.security.kerberos.enabled=false"), cause.getMessage());
    }

    @Test
    void theDirectDeployOnlyTurnsKerberosOffWhenNothingRunsWithIt() throws Exception {
        CommandService service = service(null);

        // A release with no recorded context that runs with Kerberos is refused before anything is overridden.
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(kerberizedValues(KEYTAB_SECRET));
        Map<String, Object> refusedParams = new LinkedHashMap<>();
        assertThrows(IllegalStateException.class, () -> service.overrideKerberosOff(
                resyncRequest(kerberizedValues(KEYTAB_SECRET)), refusedParams, null));
        assertNull(refusedParams.get("_ov"), "Kerberos is not turned off");

        // Values that turn Kerberos off say so on purpose.
        Map<String, Object> offOnPurpose = kerberizedValues(KEYTAB_SECRET);
        CommandService.putDotted(offOnPurpose, "global.security.kerberos.enabled", false);
        assertEquals("false", kerberosOverride(service, resyncRequest(offOnPurpose)));

        // A release that does not run with Kerberos has nothing to lose.
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(new LinkedHashMap<>(Map.of("server", Map.of("workers", 2))));
        assertEquals("false", kerberosOverride(service, resyncRequest(new LinkedHashMap<>())));
    }

    @Test
    void theDirectDeployDoesNotTurnKerberosOffWhenItCannotReadTheReleaseValues() throws Exception {
        CommandService service = service(null);
        when(k8s.getHelmReleaseValues(NS, RELEASE))
                .thenThrow(new RuntimeException("Could not retrieve configuration: Unauthorized"));
        Map<String, Object> params = new LinkedHashMap<>();

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> service.overrideKerberosOff(
                resyncRequest(kerberizedValues(KEYTAB_SECRET)), params, null));

        assertTrue(refused.getMessage().startsWith("Could not read the values of " + RELEASE
                + " to check whether it runs with Kerberos"), refused.getMessage());
        assertTrue(refused.getMessage().contains("Unauthorized"), refused.getMessage());
        assertNull(params.get("_ov"), "Kerberos is not turned off");
    }

    @Test
    void aReleaseOnTheManagedContextStillFollowsTheAmbariClusterOffKerberos() throws Exception {
        // The managed context follows its cluster: one taken off Kerberos takes the release with it.
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(kerberizedValues(KEYTAB_SECRET));
        HelmDeployRequest request = resyncRequest(kerberizedValues(KEYTAB_SECRET));
        request.setFormValues(new LinkedHashMap<>(Map.of("platformContextId", ContextService.DEFAULT_CONTEXT_ID)));

        assertEquals("false", kerberosOverride(service(null), request));
        verify(k8s, never()).getHelmReleaseValues(anyString(), anyString());
    }

    @Test
    void aGitOpsRedeployOfAKerberizedReleaseWithNoRecordedContextCommitsNothing() throws Exception {
        String repoUrl = gitOpsRepository();
        recordRelease(null, "1.43.14");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(kerberizedValues(KEYTAB_SECRET));
        FluxGitOpsBackend flux = new FluxGitOpsBackend(Files.createDirectories(tmp.resolve("flux")), k8s, ctx);
        setField(flux, FluxGitOpsBackend.class, "commandUtils", ambariManagingNoCluster());

        RuntimeException refused = assertThrows(RuntimeException.class, () -> service(flux).submitDeploy(
                gitOpsRequest(repoUrl, kerberizedValues(KEYTAB_SECRET)), "charts", null, null, null, null, ambariApi(), null));

        Throwable cause = refused.getCause();
        assertTrue(cause instanceof IllegalStateException, String.valueOf(cause));
        assertTrue(cause.getMessage().contains("no platform context is recorded for it"), cause.getMessage());
        assertNull(published(repoUrl, "clusters/default/analytics/trino/helmrelease.yaml"), "nothing is committed");
    }

    @Test
    void aGitOpsRedeployThatTurnsKerberosOffOnPurposeIsCommitted() throws Exception {
        String repoUrl = gitOpsRepository();
        recordRelease(null, "1.43.14");
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(kerberizedValues(KEYTAB_SECRET));
        FluxGitOpsBackend flux = new FluxGitOpsBackend(Files.createDirectories(tmp.resolve("flux")), k8s, ctx);
        setField(flux, FluxGitOpsBackend.class, "commandUtils", ambariManagingNoCluster());
        Map<String, Object> values = kerberizedValues(KEYTAB_SECRET);
        CommandService.putDotted(values, "global.security.kerberos.enabled", false);

        String sha = service(flux).submitDeploy(gitOpsRequest(repoUrl, values), "charts", null, null, null, null,
                ambariApi(), null);

        assertEquals(40, sha.length());
        assertNotNull(published(repoUrl, "clusters/default/analytics/trino/helmrelease.yaml"));
    }

    /**
     * A redeploy re-submits the release's values. Those of a release deployed by an earlier version of the definition
     * may hold secrets it no longer writes (the Trino internal shared secret and HTTPS keystore password as server
     * configuration lines, which land in a ConfigMap): every backend must get the values without them.
     */
    @Test
    void aResyncSendsNoneOfTheSecretsAnEarlierDefinitionWroteIntoTheTrinoConfiguration() throws Exception {
        recordRelease(null, "1.43.15");
        for (String mode : List.of("DIRECT_HELM", "FLUX_GITOPS")) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("internalSharedSecret", "Typed-Shared-Secret-1");
            values.put("additionalConfigProperties", new java.util.ArrayList<>(List.of(
                    "internal-communication.shared-secret=Typed-Shared-Secret-1", "http-server.process-forwarded=true")));
            CommandService.putDotted(values, "coordinator.additionalConfigProperties", new java.util.ArrayList<>(List.of(
                    "http-server.https.keystore.key=Typed-Keystore-Pass-2", "query.max-history=500")));
            HelmDeployRequest request = resyncRequest(values);
            request.setDeploymentMode(mode);

            service(direct).submitDeploy(request, "charts", null, null, null, null, null, null);

            Map<String, Object> sent = sentRequest.get().getValues();
            assertFalse(new Gson().toJson(sent).contains("Typed-Shared-Secret-1"), mode);
            assertFalse(new Gson().toJson(sent).contains("Typed-Keystore-Pass-2"), mode);
            assertEquals(List.of("http-server.process-forwarded=true"), sent.get("additionalConfigProperties"), mode);
            assertEquals(List.of("query.max-history=500"),
                    ConfigResolutionService.getByDottedPath(sent, "coordinator.additionalConfigProperties"), mode);
        }
    }

    /**
     * A Superset release deployed before the SECRET_KEY moved to extraSecretEnv holds it in extraEnv, which the chart
     * writes into every pod spec. A redeploy without the wizard moves it, unchanged: a new key would leave the
     * database passwords Superset stored undecryptable.
     */
    @Test
    void aResyncOfAnEarlierSupersetReleaseMovesTheSecretKeyToTheEnvSecret() throws Exception {
        recordRelease("SUPERSET", "apache/superset", null, "0.12.48");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("extraEnv", new LinkedHashMap<>(Map.of("SUPERSET_SECRET_KEY", "Key-In-Use-3",
                "OPENSTREETMAP_SERVER_URL", "https://tile.example.com")));
        HelmDeployRequest request = resyncRequest(values);
        request.setChart("apache/superset");
        request.setServiceKey("SUPERSET");

        submit(request, "charts", null);

        Map<String, Object> sent = sentRequest.get().getValues();
        assertEquals("Key-In-Use-3", ConfigResolutionService.getByDottedPath(sent, "extraSecretEnv.SUPERSET_SECRET_KEY"));
        assertEquals(Map.of("OPENSTREETMAP_SERVER_URL", "https://tile.example.com"), sent.get("extraEnv"));
    }

    /**
     * The upgrade wizard of a Superset release deployed before the SECRET_KEY moved reads it hidden from extraEnv and
     * sends the placeholder where the key goes now, extraSecretEnv. The deployed key is put back there: the operator
     * cannot see it to retype it, and a new key would leave the database passwords Superset stored undecryptable.
     */
    @Test
    void anUpgradeOfAnEarlierSupersetReleaseKeepsTheSecretKeyItReadHiddenFromItsOldPlace() throws Exception {
        recordRelease("SUPERSET", "apache/superset", null, "0.12.48");
        Map<String, Object> deployed = new LinkedHashMap<>();
        deployed.put("extraEnv", new LinkedHashMap<>(Map.of("SUPERSET_SECRET_KEY", "Key-In-Use-764",
                "OPENSTREETMAP_SERVER_URL", "https://tile.example.com")));
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(deployed);
        Map<String, Object> sentBack = HiddenReleaseValues.hide(deployed, SensitiveFields.valuePathsOfService("SUPERSET"));
        assertEquals(HiddenReleaseValues.PLACEHOLDER,
                ConfigResolutionService.getByDottedPath(sentBack, "extraEnv.SUPERSET_SECRET_KEY"));
        // What the wizard sends: the key from its seed, written by its binding to extraSecretEnv; extraEnv without it.
        ((Map<?, ?>) sentBack.get("extraEnv")).remove("SUPERSET_SECRET_KEY");
        CommandService.putDotted(sentBack, "extraSecretEnv.SUPERSET_SECRET_KEY", HiddenReleaseValues.PLACEHOLDER);
        HelmDeployRequest request = resyncRequest(sentBack);
        request.setChart("apache/superset");
        request.setServiceKey("SUPERSET");

        submit(request, "charts", null);

        Map<String, Object> sent = sentRequest.get().getValues();
        assertEquals("Key-In-Use-764", ConfigResolutionService.getByDottedPath(sent, "extraSecretEnv.SUPERSET_SECRET_KEY"));
        assertEquals(Map.of("OPENSTREETMAP_SERVER_URL", "https://tile.example.com"), sent.get("extraEnv"));
        assertFalse(HiddenReleaseValues.containsPlaceholder(sent));
    }

    /** The operation log the deploy writes its warnings into. */
    private CommandService serviceLoggingTo(CommandLogService log) throws Exception {
        CommandService service = service(null);
        set(service, "commandLogService", log);
        return service;
    }

    /** A deploy from the wizard: the release's form, here with the given fields. */
    private static HelmDeployRequest wizardRequest(String serviceKey, String chart, Map<String, Object> fields) {
        HelmDeployRequest request = resyncRequest(new LinkedHashMap<>());
        request.setServiceKey(serviceKey);
        request.setChart(chart);
        Map<String, Object> form = new LinkedHashMap<>(Map.of("releaseName", RELEASE, "namespace", NS));
        form.putAll(fields);
        request.setFormValues(form);
        return request;
    }

    /**
     * A resync re-submits the release's values, catalogs included. One an earlier deploy wrote with a password still
     * goes to the catalog ConfigMap, so the operation log says which, without the password; the resync itself is not
     * refused, or the release could not be resynced until someone edits it.
     */
    @Test
    void aResyncOfACatalogWithACredentialWrittenOutDeploysAndSaysSoInTheOperationLog() throws Exception {
        recordRelease(null, "1.43.16");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("catalogs", new LinkedHashMap<>(Map.of("pg", "connector.name=postgresql\nconnection-password=hunter2\n")));
        CommandLogService log = mock(CommandLogService.class);

        serviceLoggingTo(log).submitDeploy(resyncRequest(values), null, null, null, null, null, null, null);

        assertNotNull(sentRequest.get(), "the resync is not refused");
        ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
        verify(log).append(eq("command-1"), line.capture());
        assertTrue(line.getValue().startsWith("[WARN] Catalog credentials written out: 'pg' writes connection-password out"),
                line.getValue());
        assertFalse(line.getValue().contains("hunter2"), line.getValue());
    }

    /**
     * Superset before chart 0.12.49 keeps the datasource import files in a ConfigMap. An Upgrade/Config that keeps the
     * release's older chart (the wizard fills 'Version (override)' with it) and carries the Trino service-account
     * password is refused with the chart to pick, rather than leaving the password readable.
     */
    @Test
    void aWizardDeployOfADatasourcePasswordOnAnOlderSupersetChartIsRefused() throws Exception {
        recordRelease("SUPERSET", "apache/superset", null, "0.12.47");
        HelmDeployRequest request = wizardRequest("SUPERSET", "apache/superset",
                Map.of("ui_trino_auth_mode", "ldap", "ui_trino_svc_password", "Svc-Pass-7"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> submit(request, "charts", "0.12.47"));

        assertTrue(refused.getMessage().startsWith("With chart 0.12.47, the Trino service-account password of the"
                + " datasource import file would not be kept in a Kubernetes Secret"), refused.getMessage());
        assertTrue(refused.getMessage().contains("Chart >=0.12.49 keeps it in a Secret. Pick such a chart version under"
                + " 'Version (override)'."), refused.getMessage());
        assertFalse(refused.getMessage().contains("Svc-Pass-7"), refused.getMessage());
        assertNull(sentRequest.get(), "nothing is deployed");

        submit(wizardRequest("SUPERSET", "apache/superset",
                Map.of("ui_trino_auth_mode", "ldap", "ui_trino_svc_password", "Svc-Pass-7")), "charts", "0.12.49");
        assertNotNull(sentRequest.get(), "chart 0.12.49 keeps the import files in a Secret");
    }

    /** Stores the LDAP security profile 'corp-ldap', with its bind password. */
    private void ldapSecurityProfile() throws Exception {
        SecurityProfileEntity profile = new SecurityProfileEntity();
        profile.setId("corp-ldap");
        profile.setViewInstance("kdps");
        profile.setProfileJson("{\"mode\":\"ldap\",\"ldap\":{\"url\":\"ldaps://ldap.example.com\","
                + "\"bindDn\":\"cn=trino,ou=services\",\"bindPassword\":\"Bind-Pass-9\"}}");
        when(ds.findAll(eq(SecurityProfileEntity.class), any()))
                .thenReturn(List.of(profile));
    }

    /**
     * Trino before chart 1.43.16 writes the security profile's bind password into the coordinator ConfigMap. A security
     * refresh of a release on such a chart still runs (refusing it would leave the release without one) and its
     * operation log says what to do; a deploy from the wizard is refused until a later chart is picked.
     */
    @Test
    void theBindPasswordOnAnOlderTrinoChartIsRefusedFromTheWizardAndWarnedOtherwise() throws Exception {
        recordRelease(null, "1.43.15");
        ldapSecurityProfile();
        HelmDeployRequest refresh = resyncRequest(new LinkedHashMap<>());
        refresh.setSecurityProfile("corp-ldap");
        CommandLogService log = mock(CommandLogService.class);

        serviceLoggingTo(log).submitDeploy(refresh, null, null, null, null, null, null, null);

        assertNotNull(sentRequest.get(), "the security refresh is not refused");
        ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
        verify(log).append(eq("command-1"), line.capture());
        assertTrue(line.getValue().startsWith("[WARN] With chart 1.43.15, the security profile's LDAP bind password would"
                + " not be kept in a Kubernetes Secret"), line.getValue());
        assertTrue(line.getValue().contains("Upgrade the release to such a chart version"), line.getValue());
        assertFalse(line.getValue().contains("Bind-Pass-9"), line.getValue());

        sentRequest.set(null);
        HelmDeployRequest wizard = wizardRequest("TRINO", "trinodb/trino", Map.of("securityProfile", "corp-ldap"));
        wizard.setSecurityProfile("corp-ldap");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> submit(wizard, "charts", "1.43.15"));
        assertTrue(refused.getMessage().contains("Chart >=1.43.16 keeps it in a Secret"), refused.getMessage());
        assertNull(sentRequest.get());

        HelmDeployRequest upgraded = wizardRequest("TRINO", "trinodb/trino", Map.of("securityProfile", "corp-ldap"));
        upgraded.setSecurityProfile("corp-ldap");
        submit(upgraded, "charts", "1.43.16");
        assertNotNull(sentRequest.get());
    }

    @Test
    void onlyAFormBeyondThePlatformContextIsTheWizards() {
        HelmDeployRequest request = resyncRequest(new LinkedHashMap<>());
        assertFalse(CommandService.carriesServiceForm(request));
        request.setFormValues(new LinkedHashMap<>(Map.of("platformContextId", "cdp-prod", "_catalogRefsJson", "{}")));
        assertFalse(CommandService.carriesServiceForm(request), "what the Releases page and the server add");
        request.getFormValues().put("releaseName", RELEASE);
        assertTrue(CommandService.carriesServiceForm(request));
    }

    @Test
    void retiredValuesAreRemovedByPathOrByLineAndCommentsAreSkipped() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("_comment", "kept");
        values.put("top", "gone");
        values.put("lines", new java.util.ArrayList<>(List.of("a=1", " secret=x", "b=2")));
        values.put("notALine", "secret=y");
        Map<String, Object> retired = new LinkedHashMap<>();
        retired.put("_comment", "a comment, not a path");
        retired.put("top", "retired");
        retired.put("lines", Map.of("linesStartingWith", List.of("secret=")));
        retired.put("notALine", Map.of("linesStartingWith", List.of("secret=")));
        retired.put("absent.path", "retired");

        CommandService.removeRetiredValues(values, retired);

        assertEquals("kept", values.get("_comment"));
        assertFalse(values.containsKey("top"));
        assertEquals(List.of("a=1", "b=2"), values.get("lines"));
        assertEquals("secret=y", values.get("notALine"), "only a list is filtered by line");
        assertFalse(values.containsKey("absent"));
    }

    /** The Ambari API a deploy loops back to. */
    private static URI ambariApi() {
        return URI.create("http://ambari.example.com:8080/api/v1");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, DeploymentBackend> backendsOf(CommandService service) throws Exception {
        Field f = CommandService.class.getDeclaredField("deploymentBackends");
        f.setAccessible(true);
        return (Map<String, DeploymentBackend>) f.get(service);
    }

    /** The global.security.kerberos.enabled override a direct deploy writes when Ambari provides no Kerberos. */
    @SuppressWarnings("unchecked")
    private static String kerberosOverride(CommandService service, HelmDeployRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        service.overrideKerberosOff(request, params, null);
        Map<String, String> overrides = (Map<String, String>) params.get("_ov");
        return overrides == null ? null : overrides.get("global.security.kerberos.enabled");
    }
}
