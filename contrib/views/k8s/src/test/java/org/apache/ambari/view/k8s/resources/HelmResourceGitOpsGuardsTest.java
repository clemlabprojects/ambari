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
package org.apache.ambari.view.k8s.resources;

import com.marcnuri.helm.Release;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.ViewDefinition;
import org.apache.ambari.view.k8s.model.HelmReleaseDTO;
import org.apache.ambari.view.k8s.model.HelmReleasesResponse;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.requests.HelmUpgradeRequest;
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.security.MasterKeyUnavailableException;
import org.apache.ambari.view.k8s.service.CommandService;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend;
import org.apache.ambari.view.k8s.service.deployment.ReleaseHeldInGitException;
import org.apache.ambari.view.k8s.service.trino.TrinoCatalogEditors;
import org.apache.ambari.view.k8s.service.trino.TrinoCatalogRuntimeService;
import org.apache.ambari.view.k8s.service.trino.TrinoStatementClient;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A release deployed with Flux GitOps is changed through Git only: a Helm rollback or a chart upgrade made with Helm
 * would be undone by Flux at its next reconcile, so both are refused with 409. Its record also lists it on the
 * Releases page while Helm does not list it yet.
 */
class HelmResourceGitOpsGuardsTest {

    private final ReleaseMetadataService metadata = mock(ReleaseMetadataService.class);
    private final CommandService commands = mock(CommandService.class);
    private final KubernetesService kubernetes = mock(KubernetesService.class);
    private HelmResource resource;

    @BeforeEach
    void setUp() throws Exception {
        ViewContext ctx = mock(ViewContext.class);
        when(ctx.getProperties()).thenReturn(Map.of(AuthHelper.ADMIN_USERS_PROPERTY, "lead",
                AuthHelper.OPERATOR_USERS_PROPERTY, "ops"));
        when(ctx.getUsername()).thenReturn("ops");
        when(ctx.getLoggedinUser()).thenReturn("ops");
        when(ctx.getViewName()).thenReturn("K8S-VIEW");
        when(ctx.getInstanceName()).thenReturn("kdps");
        ViewDefinition definition = mock(ViewDefinition.class);
        when(definition.getVersion()).thenReturn("1.0.0.8");
        when(ctx.getViewDefinition()).thenReturn(definition);
        resource = new ObjenesisStd().newInstance(HelmResource.class);
        set("viewContext", ctx);
        set("authHelper", new AuthHelper(ctx));
        set("releaseMetadataService", metadata);
        set("commandService", commands);
        set("kubernetesService", kubernetes);
    }

    private void set(String name, Object value) throws Exception {
        Field field = HelmResource.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(resource, value);
    }

    private static K8sReleaseEntity recorded(String namespace, String release, String deploymentMode) {
        K8sReleaseEntity entity = new K8sReleaseEntity();
        entity.setNamespace(namespace);
        entity.setReleaseName(release);
        entity.setDeploymentMode(deploymentMode);
        entity.setChartRef("clemlab-trino");
        entity.setVersion("1.43.15");
        return entity;
    }

    @Test
    void aRollbackOfAGitOpsReleaseIsRefusedWithAConflict() {
        when(metadata.find("analytics", "trino")).thenReturn(recorded("analytics", "trino", "FLUX_GITOPS"));

        Response response = resource.rollbackReleaseToRevision("analytics", "trino", 5);

        assertEquals(409, response.getStatus());
        String error = (String) ((Map<?, ?>) response.getEntity()).get("error");
        assertTrue(error.startsWith("Release analytics/trino is deployed with Flux GitOps: Flux installs what Git holds"
                + " and would undo a Helm rollback at its next reconcile."), error);
        assertTrue(error.contains("Revert or change the release in Git"), error);
    }

    @Test
    void theLegacyRollbackEndpointRefusesAGitOpsReleaseToo() {
        when(metadata.find("analytics", "trino")).thenReturn(recorded("analytics", "trino", "FLUX_GITOPS"));

        Response response = resource.rollback("analytics", "trino", 5);

        assertEquals(409, response.getStatus());
        assertTrue(((String) ((Map<?, ?>) response.getEntity()).get("error")).contains("Revert or change the release"
                + " in Git"));
    }

    @Test
    void aChartUpgradeOfAGitOpsReleaseIsRefusedWithAConflict() {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getBaseUri()).thenReturn(URI.create("http://ambari:8080/api/v1/"));
        when(commands.submitReleaseUpgrade(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new ReleaseHeldInGitException("Release analytics/trino is deployed with Flux GitOps"));

        Response response = resource.upgradeReleaseChart("analytics", "trino", "1.43.16", headers, uriInfo);

        assertEquals(409, response.getStatus());
        assertEquals("Release analytics/trino is deployed with Flux GitOps",
                ((Map<?, ?>) response.getEntity()).get("error"));
    }

    @Test
    void onlyAReleaseRecordedAsGitOpsIsHeldInGit() {
        assertThrows(ReleaseHeldInGitException.class, () -> ReleaseHeldInGitException.refuseIfHeldInGit(
                recorded("analytics", "trino", "flux_gitops"), "analytics", "trino", "a Helm rollback"));
        assertDoesNotThrow(() -> ReleaseHeldInGitException.refuseIfHeldInGit(
                recorded("analytics", "trino", "DIRECT_HELM"), "analytics", "trino", "a Helm rollback"));
        assertDoesNotThrow(() -> ReleaseHeldInGitException.refuseIfHeldInGit(
                recorded("analytics", "trino", null), "analytics", "trino", "a Helm rollback"));
        assertDoesNotThrow(() -> ReleaseHeldInGitException.refuseIfHeldInGit(null, "analytics", "trino",
                "a Helm rollback"), "a release KDPS did not record");
    }

    @Test
    void aSynchronousHelmUpgradeOrDeployOfAGitOpsReleaseIsRefusedWithAConflict() {
        when(metadata.find("analytics", "trino")).thenReturn(recorded("analytics", "trino", "FLUX_GITOPS"));
        HelmUpgradeRequest upgrade = new HelmUpgradeRequest();
        upgrade.setNamespace("analytics");
        upgrade.setReleaseName("trino");
        upgrade.setChartRef("clemlab-trino");
        upgrade.setVersion("1.43.16");

        WebApplicationException refused = assertThrows(WebApplicationException.class, () -> resource.upgrade(upgrade));
        assertEquals(409, refused.getResponse().getStatus());
        assertTrue(((String) ((Map<?, ?>) refused.getResponse().getEntity()).get("error"))
                .contains("would undo a Helm upgrade"));

        HelmDeployRequest deploy = new HelmDeployRequest();
        deploy.setNamespace("analytics");
        deploy.setReleaseName("trino");
        deploy.setChart("clemlab-trino");
        Response response = resource.deploy(deploy, "charts", "1.43.16", "kubeconfig", 900, true, false);
        assertEquals(409, response.getStatus());
        assertTrue(((String) ((Map<?, ?>) response.getEntity()).get("error")).contains("Revert or change the release"
                + " in Git"));
        verifyNoInteractions(kubernetes);
    }

    // ---------------------------------------------------------------- catalogs saved to a GitOps release

    /** A Trino release Flux runs from Git, with catalogs managed live and no change of it pending in Git. */
    private static TrinoCatalogRuntimeService.Target gitOpsTrino() {
        TrinoCatalogRuntimeService.Target t = new TrinoCatalogRuntimeService.Target();
        t.namespace = "analytics";
        t.release = "trino";
        t.dynamicCatalogs = true;
        t.metadata = recorded("analytics", "trino", "FLUX_GITOPS");
        t.metadata.setRepoId("charts");
        t.metadata.setServiceKey("TRINO");
        t.metadata.setGitRepoUrl("https://git.example.org/platform/gitops.git");
        t.metadata.setGitBaseBranch("production");
        t.metadata.setGitCommitMode("DIRECT_COMMIT");
        t.values = new LinkedHashMap<>();
        t.values.put("catalogs", new LinkedHashMap<>(Map.of("tpch", "connector.name=tpch")));
        return t;
    }

    private static TrinoStatementClient.Result ok() {
        return new TrinoStatementClient.Result();
    }

    /** The resource as a request reaches it, its catalog editor on {@code target} and statements sent to {@code trino}. */
    private HelmResource catalogsOn(TrinoCatalogRuntimeService.Target target, TrinoStatementClient trino) {
        HelmResource spied = spy(resource);
        doAnswer(inv -> TrinoCatalogEditors.on(target, trino, inv.getArgument(0))).when(spied).catalogEditor(any());
        doNothing().when(spied).requireDeploySubmittable(any());
        return spied;
    }

    private static HttpHeaders callerHeaders(MultivaluedMap<String, String> values) {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(values);
        return headers;
    }

    private static UriInfo uriInfo() {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getBaseUri()).thenReturn(URI.create("http://ambari:8080/api/v1/"));
        return uriInfo;
    }

    @Test
    void aCatalogSavedToAGitOpsReleaseIsSubmittedAsAGitOpsDeployWithTheCallersSession() {
        TrinoStatementClient trino = mock(TrinoStatementClient.class);
        when(trino.execute(anyString(), anyString())).thenReturn(ok());
        MultivaluedMap<String, String> session = new MultivaluedHashMap<>();
        session.putSingle("Cookie", "AMBARISESSIONID=abc");
        List<HelmDeployRequest> submitted = new ArrayList<>();
        when(commands.submitDeploy(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            submitted.add(inv.getArgument(0));
            assertSame(session, inv.getArgument(5), "the caller's Ambari session");
            assertTrue(String.valueOf((Object) inv.getArgument(4)).contains("/instances/kdps"), "the commands URL");
            return "op-7";
        });

        Response response = catalogsOn(gitOpsTrino(), trino).createTrinoCatalog("analytics", "trino",
                Map.of("name", "pg", "properties", "connector.name=postgresql\nconnection-url=jdbc:postgresql://db/x"),
                callerHeaders(session), uriInfo());

        assertEquals(200, response.getStatus(), String.valueOf(response.getEntity()));
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals(Boolean.TRUE, body.get("committedToGit"));
        assertEquals("op-7", body.get("commandId"));
        assertEquals(1, submitted.size());
        assertEquals("FLUX_GITOPS", submitted.get(0).getDeploymentMode());
        assertEquals("production", submitted.get(0).getGit().getBaseBranch());
        assertEquals("charts", submitted.get(0).getRepoId());
    }

    @Test
    void aGitOpsOperationStillRunningRefusesTheCatalogWithAConflictAndDropsItAgain() {
        TrinoStatementClient trino = mock(TrinoStatementClient.class);
        when(trino.execute(anyString(), anyString())).thenReturn(ok());
        // As the backend refuses it: wrapped by the deploy's submission.
        when(commands.submitDeploy(any(), any(), any(), any(), any(), any(), any(), any())).thenThrow(
                new RuntimeException(new FluxGitOpsBackend.GitOpsBusyException("analytics", "trino")));

        Response response = catalogsOn(gitOpsTrino(), trino).createTrinoCatalog("analytics", "trino",
                Map.of("name", "pg", "properties", "connector.name=postgresql"), callerHeaders(new MultivaluedHashMap<>()),
                uriInfo());

        assertEquals(409, response.getStatus());
        assertTrue(((String) ((Map<?, ?>) response.getEntity()).get("error")).contains("Another GitOps operation"));
        verify(trino).execute("DROP CATALOG pg", "ops");
    }

    @Test
    void aDeployRefusedAtItsSubmissionAnswersBadRequestAndLeavesNoCatalogLive() {
        TrinoStatementClient trino = mock(TrinoStatementClient.class);
        when(trino.execute(anyString(), anyString())).thenReturn(ok());
        when(commands.submitDeploy(any(), any(), any(), any(), any(), any(), any(), any())).thenThrow(
                new RuntimeException(new IllegalArgumentException("The security profile 'ldap' does not exist")));

        Response response = catalogsOn(gitOpsTrino(), trino).createTrinoCatalog("analytics", "trino",
                Map.of("name", "pg", "properties", "connector.name=postgresql"), callerHeaders(new MultivaluedHashMap<>()),
                uriInfo());

        assertEquals(400, response.getStatus());
        assertEquals("The security profile 'ldap' does not exist", ((Map<?, ?>) response.getEntity()).get("error"));
        verify(trino).execute("DROP CATALOG pg", "ops");
    }

    @Test
    void withoutTheMasterKeyNothingIsChangedLive() {
        TrinoStatementClient trino = mock(TrinoStatementClient.class);
        HelmResource spied = catalogsOn(gitOpsTrino(), trino);
        doThrow(new MasterKeyUnavailableException()).when(spied).requireDeploySubmittable(any());

        Response created = spied.createTrinoCatalog("analytics", "trino",
                Map.of("name", "pg", "properties", "connector.name=postgresql"), callerHeaders(new MultivaluedHashMap<>()),
                uriInfo());
        Response dropped = spied.dropTrinoCatalog("analytics", "trino", "tpch", true,
                callerHeaders(new MultivaluedHashMap<>()), uriInfo());
        Response adopted = spied.adoptTrinoCatalog("analytics", "trino", "probe_x",
                callerHeaders(new MultivaluedHashMap<>()), uriInfo());

        assertEquals(412, created.getStatus());
        assertEquals(412, dropped.getStatus());
        assertEquals(412, adopted.getStatus());
        verifyNoInteractions(trino);
        verify(commands, never()).submitDeploy(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aCatalogDroppedLiveWhoseRemovalCouldNotBeCommittedSaysSo() {
        TrinoStatementClient trino = mock(TrinoStatementClient.class);
        when(trino.execute(anyString(), anyString())).thenReturn(ok());
        when(commands.submitDeploy(any(), any(), any(), any(), any(), any(), any(), any())).thenThrow(
                new RuntimeException(new IllegalStateException("No deployment backend available")));

        Response response = catalogsOn(gitOpsTrino(), trino).dropTrinoCatalog("analytics", "trino", "tpch", true,
                callerHeaders(new MultivaluedHashMap<>()), uriInfo());

        assertEquals(400, response.getStatus());
        String error = (String) ((Map<?, ?>) response.getEntity()).get("error");
        assertTrue(error.startsWith("Catalog 'tpch' was dropped from Trino, but its removal could not be committed to"
                + " Git (No deployment backend available)."), error);
        assertTrue(error.contains("comes back at the next restart"), error);
    }

    // ---------------------------------------------------------------- the Releases list

    @Test
    void aGitOpsReleaseHelmDoesNotListYetIsListedFromItsRecord() {
        K8sReleaseEntity pending = recorded("analytics", "trino", "FLUX_GITOPS");
        K8sReleaseEntity installed = recorded("analytics", "superset", "FLUX_GITOPS");
        K8sReleaseEntity direct = recorded("analytics", "jupyterhub", "DIRECT_HELM");

        List<HelmReleaseDTO> rows = HelmResource.gitOpsReleasesNotListed(null, Set.of("analytics/superset"),
                List.of(pending, installed, direct));

        assertEquals(1, rows.size(), "Helm lists the installed one, and a release deployed with Helm is Helm's");
        HelmReleaseDTO row = rows.get(0);
        assertEquals("trino", row.name);
        assertEquals("analytics", row.namespace);
        assertEquals("clemlab-trino", row.chart);
        assertEquals("1.43.15", row.version);
        assertEquals("NOT_FOUND", row.status, "until Flux's status says what it waits for");
        assertEquals(Boolean.FALSE, row.installed, "Helm has no values to redeploy it from");
    }

    @Test
    void aGitOpsReleaseWhoseRemovalWaitsForItsMergeIsListedAsSuch() {
        K8sReleaseEntity removing = recorded("analytics", "trino", "FLUX_GITOPS");
        removing.setGitPendingDelete(true);

        List<HelmReleaseDTO> rows = HelmResource.gitOpsReleasesNotListed("analytics", Set.of(), List.of(removing));

        assertEquals(1, rows.size());
        assertEquals("PENDING_DELETE", rows.get(0).status);
        assertEquals(Boolean.FALSE, rows.get(0).installed);
    }

    private static Release helmListed(String namespace, String name) {
        Release release = mock(Release.class);
        when(release.getNamespace()).thenReturn(namespace);
        when(release.getName()).thenReturn(name);
        when(release.getChart()).thenReturn("clemlab-superset-0.12.42");
        return release;
    }

    @Test
    void theReleasesListShowsAGitOpsReleaseHelmDoesNotListAmongTheManagedOnesAndPagesIt() {
        K8sReleaseEntity superset = recorded("analytics", "superset", "DIRECT_HELM");
        superset.setManagedByUi(true);
        K8sReleaseEntity trino = recorded("analytics", "trino", "FLUX_GITOPS");
        trino.setServiceKey("TRINO");
        when(metadata.find("analytics", "superset")).thenReturn(superset);
        when(metadata.find("analytics", "trino")).thenReturn(trino);
        when(metadata.findAll()).thenReturn(List.of(superset, trino));
        when(kubernetes.listKdpsManagedReleaseKeys(any())).thenReturn(Set.of());
        HelmReleaseDTO waiting = new HelmReleaseDTO();
        waiting.status = "PENDING_PR";
        waiting.gitPrUrl = "https://git.example.org/platform/gitops/pulls/12";
        when(commands.statusViaBackend("analytics", "trino", "FLUX_GITOPS")).thenReturn(waiting);
        HelmResource listing = spy(resource);
        doReturn(List.of(helmListed("analytics", "superset"), helmListed("analytics", "unmanaged")))
                .when(listing).helmReleases("analytics");

        HelmReleasesResponse first = listing.list("analytics", 1, 0, true);
        HelmReleasesResponse second = listing.list("analytics", 1, 1, true);

        assertEquals(2, first.total, "the managed release Helm lists and the GitOps one it does not");
        assertEquals(List.of("superset"), first.items.stream().map(r -> r.name).toList());
        assertEquals(2, second.total);
        assertEquals(List.of("trino"), second.items.stream().map(r -> r.name).toList());
        HelmReleaseDTO row = second.items.get(0);
        assertEquals("PENDING_PR", row.status, "what Flux's status says it waits for");
        assertEquals("https://git.example.org/platform/gitops/pulls/12", row.gitPrUrl);
        assertEquals("FLUX_GITOPS", row.deploymentMode);
        assertEquals(Boolean.FALSE, row.catalogManagement, "no coordinator to run the Catalogs action on yet");
        assertEquals(3, listing.list("analytics", 50, 0, false).total, "with the release KDPS did not deploy");
        assertEquals(0, listing.list("analytics", 50, 5, true).items.size());
    }

    @Test
    void theRecordedGitOpsReleasesFollowTheNamespaceListed() {
        List<HelmReleaseDTO> rows = HelmResource.gitOpsReleasesNotListed("bi", Set.of(), List.of(
                recorded("analytics", "trino", "FLUX_GITOPS"), recorded("bi", "superset", "FLUX_GITOPS")));

        assertEquals(List.of("bi/superset"), rows.stream().map(r -> r.namespace + "/" + r.name).toList());
        assertEquals(2, HelmResource.gitOpsReleasesNotListed("", Set.of(), List.of(
                recorded("analytics", "trino", "FLUX_GITOPS"), recorded("bi", "superset", "FLUX_GITOPS"))).size(),
                "all namespaces");
    }
}
