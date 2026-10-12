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
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        // DBT turns Kerberos on from its own values and has no keytab field to restore or select.
        recordRelease("DBT", "dbt", "cdp-prod", "0.1.8");
        Map<String, Object> values = kerberizedValues(null);
        HelmDeployRequest request = resyncRequest(values);
        request.setChart("dbt");
        request.setServiceKey("DBT");
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
        try (Git ignored = Git.cloneRepository().setURI(repoUrl).setBranch("main").setDirectory(clone.toFile()).call()) {
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
        assertTrue(helmRelease.contains("        kind: HelmRepository\n        name: charts\n"), helmRelease);
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
        set(service, "commandUtils", ambariManagingNoCluster());
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
