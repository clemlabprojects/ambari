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

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.service.CommandService;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend;
import org.apache.ambari.view.k8s.service.deployment.GitPushRejectedException;
import org.apache.ambari.view.k8s.service.helm.HelmOperationRegistry;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import javax.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The uninstall of a GitOps release from the Releases page: when the Git directory recorded for the release is not
 * its own, or holds another release's HelmRelease, nothing is removed. Its manifests stay in Git and Flux keeps running it, so it keeps its Polaris catalog
 * credential and its record, from which the uninstall is retried once the record is corrected. The same when Git
 * refuses or cannot take the removal, or another GitOps operation on the release holds its workspace. A direct Helm
 * release whose uninstall failed keeps them too while Helm still holds it, and is cleaned up once it is gone.
 */
class HelmResourceUninstallTest {

    private static final String NS = "team-a";
    private static final String RELEASE = "trino";

    private final FluxGitOpsBackend flux = mock(FluxGitOpsBackend.class);
    private final CommandService commandService = mock(CommandService.class);
    private final ReleaseMetadataService releaseMetadata = mock(ReleaseMetadataService.class);
    private HelmResource resource;

    @BeforeEach
    void setUp() throws Exception {
        resource = new ObjenesisStd().newInstance(HelmResource.class);
        set("viewContext", mock(ViewContext.class));
        set("fluxGitOpsBackend", flux);
        set("commandService", commandService);
        set("releaseMetadataService", releaseMetadata);
        set("authHelper", mock(AuthHelper.class));
    }

    private void set(String field, Object value) throws Exception {
        Field f = HelmResource.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(resource, value);
    }

    /** The release as a GitOps deploy recorded it, with the Git directory {@code gitPath}. */
    private K8sReleaseEntity recorded(String gitPath) {
        K8sReleaseEntity meta = new K8sReleaseEntity();
        meta.setId(K8sReleaseEntity.idOf(NS, RELEASE));
        meta.setNamespace(NS);
        meta.setReleaseName(RELEASE);
        meta.setDeploymentMode("FLUX_GITOPS");
        meta.setGitRepoUrl("https://git.example.org/platform/gitops.git");
        meta.setGitBranch("main");
        meta.setGitPath(gitPath);
        when(releaseMetadata.find(NS, RELEASE)).thenReturn(meta);
        return meta;
    }

    /** The release as a direct Helm deploy recorded it. */
    private K8sReleaseEntity recordedDirect() {
        K8sReleaseEntity meta = new K8sReleaseEntity();
        meta.setId(K8sReleaseEntity.idOf(NS, RELEASE));
        meta.setNamespace(NS);
        meta.setReleaseName(RELEASE);
        meta.setDeploymentMode("DIRECT_HELM");
        when(releaseMetadata.find(NS, RELEASE)).thenReturn(meta);
        return meta;
    }

    /** What the direct backend throws when Helm fails: the Helm error, wrapped twice on the way up. */
    private static RuntimeException helmFailure(String helmMessage) {
        return new RuntimeException(new RuntimeException(new IllegalStateException(helmMessage)));
    }

    private void assertNothingTakenBack() {
        verify(commandService, never()).revokePolarisForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).removeSccGrantsForRelease(anyString(), anyString(), any());
        verify(commandService, never()).revokeTagSyncRangerAccountForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).revokeOidcForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).revokeKdpsObjectsForRelease(anyString(), anyString(), any());
        verify(releaseMetadata, never()).delete(anyString(), anyString());
    }

    private void assertEverythingTakenBack() {
        verify(commandService).revokePolarisForRelease(eq(NS), eq(RELEASE), any(), any());
        verify(commandService).removeSccGrantsForRelease(eq(NS), eq(RELEASE), any());
        verify(commandService).revokeTagSyncRangerAccountForRelease(eq(NS), eq(RELEASE), any(), any());
        verify(commandService).revokeOidcForRelease(eq(NS), eq(RELEASE), any(), any());
        verify(commandService).revokeKdpsObjectsForRelease(eq(NS), eq(RELEASE), any());
        verify(releaseMetadata).delete(NS, RELEASE);
    }

    private Response uninstall() {
        // No Git parameter: the release is removed from Git with what its record names (see
        // HelmResourceUninstallGitOpsTest).
        return resource.uninstall(NS, RELEASE, null, null, null, null);
    }

    @Test
    void aRecordedDirectoryThatIsNotTheReleasesOwnRemovesNothing() throws Exception {
        for (String foreign : Arrays.asList("clusters/default/team-b/trino", "clusters/default", "", null,
                "clusters/default/team-a/../team-b/trino")) {
            clearInvocations(flux, commandService, releaseMetadata);
            recorded(foreign);

            Response response = uninstall();

            assertEquals(400, response.getStatus(), "refused: '" + foreign + "'");
            String error = String.valueOf(((Map<?, ?>) response.getEntity()).get("error"));
            assertTrue(error.contains("team-a/trino") || error.contains(String.valueOf(foreign)),
                    "names the release or the recorded directory: " + error);
            verify(flux, never()).checkRemovable(any());
            verify(flux, never()).destroy(anyString(), anyString());
            verify(commandService, never()).destroyViaBackend(anyString(), anyString(), any());
            verify(commandService, never()).revokePolarisForRelease(anyString(), anyString(), any(), any());
            verify(commandService, never()).revokeTagSyncRangerAccountForRelease(anyString(), anyString(), any(), any());
            verify(commandService, never()).revokeOidcForRelease(anyString(), anyString(), any(), any());
            verify(commandService, never()).revokeKdpsObjectsForRelease(anyString(), anyString(), any());
            verify(releaseMetadata, never()).delete(anyString(), anyString());
        }
    }

    @Test
    void aDirectoryFoundNotToBeTheReleasesOwnOnceGitIsReadRemovesNothing() throws Exception {
        K8sReleaseEntity meta = recorded("clusters/default/team-a/trino");
        doThrow(new FluxGitOpsBackend.NotOwnDirectoryException("The Git directory of release team-a/trino holds the"
                + " HelmRelease team-b/trino"))
                .when(flux).destroy(NS, RELEASE);

        Response response = uninstall();

        assertEquals(400, response.getStatus());
        assertTrue(String.valueOf(((Map<?, ?>) response.getEntity()).get("error")).contains("team-b/trino"));
        verify(commandService, never()).revokePolarisForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).revokeTagSyncRangerAccountForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).revokeOidcForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).revokeKdpsObjectsForRelease(anyString(), anyString(), any());
        verify(releaseMetadata, never()).delete(anyString(), anyString());
    }

    @Test
    void aRemovalGitRefusesKeepsTheReleasesCredentialAndRecord() throws Exception {
        K8sReleaseEntity meta = recorded("clusters/default/team-a/trino");
        doThrow(new GitPushRejectedException("https://git.example.org/platform/gitops.git", "main",
                RemoteRefUpdate.Status.REJECTED_OTHER_REASON, "pre-receive hook declined",
                "Not allowed to push to protected branch main"))
                .when(flux).destroy(NS, RELEASE);

        Response response = uninstall();

        assertEquals(502, response.getStatus());
        Map<?, ?> answer = (Map<?, ?>) response.getEntity();
        String error = String.valueOf(answer.get("error"));
        assertTrue(error.contains("Not allowed to push to protected branch main"), "Git's own message: " + error);
        assertEquals(RELEASE, answer.get("release"));
        assertEquals(NS, answer.get("namespace"));
        // Flux keeps running the release from Git: it keeps its catalog credential, its SCCs and its record.
        verify(commandService, never()).revokePolarisForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).removeSccGrantsForRelease(anyString(), anyString(), any());
        verify(commandService, never()).revokeTagSyncRangerAccountForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).revokeOidcForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).revokeKdpsObjectsForRelease(anyString(), anyString(), any());
        verify(releaseMetadata, never()).delete(anyString(), anyString());
    }

    @Test
    void anUninstallWhileAnotherGitOpsOperationHoldsTheReleaseIsRefusedWith409() throws Exception {
        K8sReleaseEntity meta = recorded("clusters/default/team-a/trino");
        doThrow(new FluxGitOpsBackend.GitOpsBusyException(NS, RELEASE)).when(flux).destroy(NS, RELEASE);

        Response response = uninstall();

        assertEquals(409, response.getStatus());
        assertEquals("Another GitOps operation on team-a/trino is in progress. Try again once it has finished.",
                ((Map<?, ?>) response.getEntity()).get("error"));
        verify(commandService, never()).revokePolarisForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).revokeTagSyncRangerAccountForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).revokeOidcForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).revokeKdpsObjectsForRelease(anyString(), anyString(), any());
        verify(releaseMetadata, never()).delete(anyString(), anyString());
    }

    @Test
    void theReleasesOwnDirectoryIsRemovedThenItsCredentialAndRecord() throws Exception {
        K8sReleaseEntity meta = recorded("clusters/default/team-a/trino");

        Response response = uninstall();

        assertEquals(200, response.getStatus());
        verify(flux).checkRemovable(meta);
        verify(flux).destroy(NS, RELEASE);
        verify(commandService).revokePolarisForRelease(eq(NS), eq(RELEASE), any(), any());
        verify(commandService).removeSccGrantsForRelease(eq(NS), eq(RELEASE), any());
        verify(commandService).revokeTagSyncRangerAccountForRelease(eq(NS), eq(RELEASE), any(), any());
        verify(commandService).revokeOidcForRelease(eq(NS), eq(RELEASE), any(), any());
        verify(commandService).revokeKdpsObjectsForRelease(eq(NS), eq(RELEASE), any());
        verify(releaseMetadata).delete(NS, RELEASE);
    }

    @Test
    void theRangerAccountOfTheReleasesTagSyncIsTakenBackOnTheRecordedContext() throws Exception {
        K8sReleaseEntity meta = new K8sReleaseEntity();
        meta.setId(K8sReleaseEntity.idOf(NS, RELEASE));
        meta.setNamespace(NS);
        meta.setReleaseName(RELEASE);
        meta.setPlatformContextId("cdp-prod");
        when(releaseMetadata.find(NS, RELEASE)).thenReturn(meta);
        String note = "The Ranger account 'kdps-tagsync-team-a_trino' that synced Atlas tags for this release could"
                + " not be disabled (HTTP 503). It holds the Ranger administrator role: disable it in Ranger.";
        when(commandService.revokeTagSyncRangerAccountForRelease(NS, RELEASE, "cdp-prod", null)).thenReturn(note);

        Response response = uninstall();

        assertEquals(200, response.getStatus());
        verify(commandService).destroyViaBackend(eq(NS), eq(RELEASE), any());
        assertEquals(note, ((Map<?, ?>) response.getEntity()).get("tagSyncCleanup"));
    }

    @Test
    void aTagSyncCleanupThatThrowsDoesNotFailTheUninstall() throws Exception {
        when(commandService.revokeTagSyncRangerAccountForRelease(anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("unexpected"));

        Response response = uninstall();

        assertEquals(200, response.getStatus());
    }

    @Test
    void aRemovalProposedInAPullRequestKeepsTheReleaseUntilItIsMerged() throws Exception {
        K8sReleaseEntity meta = recorded("clusters/default/team-a/trino");
        meta.setGitCommitMode("PR_MODE");
        meta.setGitBaseBranch("production");
        // What the backend records once it has opened the pull request removing the release.
        K8sReleaseEntity pending = recorded("clusters/default/team-a/trino");
        pending.setGitCommitMode("PR_MODE");
        pending.setGitBaseBranch("production");
        pending.setGitBranch("flux-remove-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
        pending.setGitPrUrl("https://git.example.org/platform/gitops/pulls/7");
        pending.setGitPrNumber("7");
        pending.setGitPendingDelete(true);
        when(releaseMetadata.find(NS, RELEASE)).thenReturn(meta, pending);

        Response response = uninstall();

        assertEquals(202, response.getStatus());
        Map<?, ?> answer = (Map<?, ?>) response.getEntity();
        assertEquals(Boolean.TRUE, answer.get("pendingDelete"));
        assertEquals("https://git.example.org/platform/gitops/pulls/7", answer.get("pullRequestUrl"));
        assertEquals("The removal of team-a/trino is proposed in pull request "
                + "https://git.example.org/platform/gitops/pulls/7 into production. The release keeps running until it"
                + " is merged; uninstall it again then to finish removing it from the cluster and KDPS.",
                answer.get("message"));
        verify(flux).destroy(NS, RELEASE);
        // Flux keeps running the release: it keeps its catalog credential, its SCCs and its record.
        verify(commandService, never()).revokePolarisForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).removeSccGrantsForRelease(anyString(), anyString(), any());
        verify(commandService, never()).revokeTagSyncRangerAccountForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).revokeOidcForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).revokeKdpsObjectsForRelease(anyString(), anyString(), any());
        verify(releaseMetadata, never()).delete(anyString(), anyString());
        verify(commandService, never()).destroyViaBackend(anyString(), anyString(), any());
    }

    @Test
    void anUninstallRunAgainWhileTheRemovalPullRequestIsOpenStillCleansNothing() throws Exception {
        K8sReleaseEntity pending = recorded("clusters/default/team-a/trino");
        pending.setGitCommitMode("PR_MODE");
        pending.setGitBaseBranch("production");
        pending.setGitBranch("flux-remove-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
        pending.setGitPrUrl("https://git.example.org/platform/gitops/pulls/7");
        pending.setGitPrNumber("7");
        pending.setGitPendingDelete(true);

        Response first = uninstall();
        Response second = uninstall();

        assertEquals(202, first.getStatus());
        assertEquals(202, second.getStatus());
        verify(commandService, never()).revokePolarisForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).removeSccGrantsForRelease(anyString(), anyString(), any());
        verify(commandService, never()).revokeTagSyncRangerAccountForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).revokeOidcForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).revokeKdpsObjectsForRelease(anyString(), anyString(), any());
        verify(releaseMetadata, never()).delete(anyString(), anyString());
    }

    @Test
    void finishingAPendingRemovalRunsEveryCleanupOnce() throws Exception {
        // The removal pull request was merged: the base branch no longer holds the release, so the backend finishes
        // the removal and deletes the record (and the Git credentials) itself.
        K8sReleaseEntity pending = recorded("clusters/default/team-a/trino");
        pending.setGitCommitMode("PR_MODE");
        pending.setGitBaseBranch("production");
        pending.setGitBranch("flux-remove-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
        pending.setGitPrUrl("https://git.example.org/platform/gitops/pulls/7");
        pending.setGitPrNumber("7");
        pending.setGitPendingDelete(true);
        pending.setPlatformContextId("cdp-prod");
        when(releaseMetadata.find(NS, RELEASE)).thenReturn(pending, (K8sReleaseEntity) null);

        Response response = uninstall();

        assertEquals(200, response.getStatus());
        verify(flux, times(1)).checkRemovable(pending);
        verify(flux, times(1)).destroy(NS, RELEASE);
        verify(commandService, times(1)).revokePolarisForRelease(eq(NS), eq(RELEASE), any(), any());
        verify(commandService, times(1)).removeSccGrantsForRelease(eq(NS), eq(RELEASE), any());
        verify(commandService, times(1)).revokeTagSyncRangerAccountForRelease(eq(NS), eq(RELEASE), eq("cdp-prod"),
                any());
        verify(commandService, times(1)).revokeOidcForRelease(eq(NS), eq(RELEASE), eq("cdp-prod"), any());
        verify(commandService, times(1)).revokeKdpsObjectsForRelease(eq(NS), eq(RELEASE), any());
        verify(releaseMetadata, times(1)).delete(NS, RELEASE);
        verify(commandService, never()).destroyViaBackend(anyString(), anyString(), any());
    }

    @Test
    void theSignInOfTheReleaseIsRemovedOnTheRecordedContextAndSaysWhatWasDone() throws Exception {
        K8sReleaseEntity meta = new K8sReleaseEntity();
        meta.setId(K8sReleaseEntity.idOf(NS, RELEASE));
        meta.setNamespace(NS);
        meta.setReleaseName(RELEASE);
        meta.setPlatformContextId("cdp-prod");
        when(releaseMetadata.find(NS, RELEASE)).thenReturn(meta);
        String note = "Removed the single sign-on client 'trino-team-a' in Keycloak realm 'odp'.\n"
                + "Removed the Secret trino-oidc-client holding the client secret.";
        when(commandService.revokeOidcForRelease(NS, RELEASE, "cdp-prod", null)).thenReturn(note);

        Response response = uninstall();

        assertEquals(200, response.getStatus());
        assertEquals(note, ((Map<?, ?>) response.getEntity()).get("oidcCleanup"));
        // Taken back once the release is gone, from the record read before it is deleted.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(commandService, releaseMetadata);
        order.verify(commandService).destroyViaBackend(eq(NS), eq(RELEASE), any());
        order.verify(releaseMetadata).delete(NS, RELEASE);
        order.verify(commandService).revokeOidcForRelease(NS, RELEASE, "cdp-prod", null);
    }

    @Test
    void anUninstallAskingToKeepTheSignInLeavesIt() throws Exception {
        Response response = resource.uninstall(NS, RELEASE, null, " None ", null, null);

        assertEquals(200, response.getStatus());
        verify(commandService).destroyViaBackend(eq(NS), eq(RELEASE), any());
        verify(commandService, never()).revokeOidcForRelease(anyString(), anyString(), any(), any());
        // The release's other objects still go; its sign-in Secrets are not among them (their own managed-by value).
        verify(commandService).revokeKdpsObjectsForRelease(eq(NS), eq(RELEASE), any());
    }

    @Test
    void anOidcCleanupThatThrowsDoesNotFailTheUninstall() throws Exception {
        when(commandService.revokeOidcForRelease(anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("unexpected"));

        Response response = uninstall();

        assertEquals(200, response.getStatus());
        verify(commandService).revokeTagSyncRangerAccountForRelease(eq(NS), eq(RELEASE), any(), any());
    }

    @Test
    void anUninstallRefusedBecauseAHelmOperationIsRunningKeepsTheSignIn() throws Exception {
        doThrow(new HelmOperationRegistry.ReleaseBusyException(NS, RELEASE))
                .when(commandService).destroyViaBackend(eq(NS), eq(RELEASE), any());

        Response response = uninstall();

        assertEquals(409, response.getStatus());
        verify(commandService, never()).revokeOidcForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).revokeKdpsObjectsForRelease(anyString(), anyString(), any());
        verify(commandService, never()).revokePolarisForRelease(anyString(), anyString(), any(), any());
        verify(releaseMetadata, never()).delete(anyString(), anyString());
    }

    @Test
    void aDirectReleaseHelmCannotUninstallKeepsItsCatalogAccessAndRecord() throws Exception {
        recordedDirect();
        doThrow(helmFailure("uninstall: secrets \"sh.helm.release.v1.trino.v3\" is forbidden: User"
                + " \"system:serviceaccount:kdps:ambari\" cannot delete resource \"secrets\""))
                .when(commandService).destroyViaBackend(NS, RELEASE, "DIRECT_HELM");
        when(commandService.directHelmReleaseExists(NS, RELEASE)).thenReturn(true);

        Response response = uninstall();

        assertEquals(502, response.getStatus());
        Map<?, ?> answer = (Map<?, ?>) response.getEntity();
        String error = String.valueOf(answer.get("error"));
        assertTrue(error.startsWith("Helm could not uninstall team-a/trino: uninstall: secrets"), error);
        assertTrue(error.contains("cannot delete resource \"secrets\""), "Helm's own reason: " + error);
        assertTrue(error.contains("still installed"), error);
        assertTrue(!error.contains("RuntimeException") && !error.contains("IllegalStateException"),
                "no exception class names: " + error);
        assertEquals(RELEASE, answer.get("release"));
        assertEquals(NS, answer.get("namespace"));
        // Trino keeps running: its Polaris principal, its SCCs, its sign-in and its record stay.
        assertNothingTakenBack();
    }

    @Test
    void aDirectReleaseWhoseStateCannotBeCheckedIsKept() throws Exception {
        recordedDirect();
        doThrow(helmFailure("Kubernetes cluster unreachable: context deadline exceeded"))
                .when(commandService).destroyViaBackend(NS, RELEASE, "DIRECT_HELM");
        when(commandService.directHelmReleaseExists(NS, RELEASE))
                .thenThrow(new IllegalStateException("Kubernetes cluster unreachable"));

        Response response = uninstall();

        assertEquals(502, response.getStatus());
        assertTrue(String.valueOf(((Map<?, ?>) response.getEntity()).get("error"))
                .contains("context deadline exceeded"));
        assertNothingTakenBack();
    }

    @Test
    void aDirectReleaseThatIsUninstalledIsCleanedUp() throws Exception {
        recordedDirect();

        Response response = uninstall();

        assertEquals(200, response.getStatus());
        verify(commandService).destroyViaBackend(NS, RELEASE, "DIRECT_HELM");
        assertEverythingTakenBack();
        verify(commandService, never()).directHelmReleaseExists(anyString(), anyString());
    }

    @Test
    void aDirectReleaseGoneAlthoughHelmFailedIsCleanedUp() throws Exception {
        // Such as a partial uninstall: Helm deleted the release record, then failed on one of its resources.
        recordedDirect();
        doThrow(helmFailure("uninstallation completed with 1 error(s): timed out waiting for the condition"))
                .when(commandService).destroyViaBackend(NS, RELEASE, "DIRECT_HELM");
        when(commandService.directHelmReleaseExists(NS, RELEASE)).thenReturn(false);

        Response response = uninstall();

        assertEquals(200, response.getStatus());
        assertEverythingTakenBack();
    }

    @Test
    void aStaleRecordOfAReleaseHelmDoesNotKnowIsCleanedUp() throws Exception {
        // Helm cannot list the namespace, but its own answer says the release is not there.
        recordedDirect();
        doThrow(helmFailure("uninstall: Release not loaded: trino: release: not found"))
                .when(commandService).destroyViaBackend(NS, RELEASE, "DIRECT_HELM");
        when(commandService.directHelmReleaseExists(NS, RELEASE))
                .thenThrow(new IllegalStateException("secrets is forbidden: cannot list resource \"secrets\""));

        Response response = uninstall();

        assertEquals(200, response.getStatus());
        assertEverythingTakenBack();
    }
    @Test
    void theObjectsKdpsCreatedForTheReleaseAreRemovedLastAndReported() throws Exception {
        K8sReleaseEntity meta = recordedDirect();
        meta.setKdpsObjects(java.util.List.of("Secret/trino-keytab",
                "ClusterRoleBinding/kdps-monview-team-a-trino-thanos-token"));
        Map<String, Object> report = Map.of("removed", java.util.List.of("Secret/trino-keytab"), "left",
                java.util.List.of(), "note", "Removed what KDPS created for the release outside its chart: "
                        + "Secret/trino-keytab.");
        when(commandService.revokeKdpsObjectsForRelease(NS, RELEASE, meta.getKdpsObjects())).thenReturn(report);

        Response response = uninstall();

        assertEquals(200, response.getStatus());
        assertEquals(report, ((Map<?, ?>) response.getEntity()).get("kdpsObjectsCleanup"));
        // Once the release is gone, and after the steps that read their own Secrets before removing them.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(commandService);
        order.verify(commandService).destroyViaBackend(eq(NS), eq(RELEASE), any());
        order.verify(commandService).revokeTagSyncRangerAccountForRelease(eq(NS), eq(RELEASE), any(), any());
        order.verify(commandService).revokeOidcForRelease(eq(NS), eq(RELEASE), any(), any());
        order.verify(commandService).revokeKdpsObjectsForRelease(NS, RELEASE, meta.getKdpsObjects());
    }

    @Test
    void aGitOpsReleaseHasItsObjectsRemovedToo() throws Exception {
        // Flux only removes what is in Git: KDPS created these objects outside it.
        recorded("clusters/default/team-a/trino");

        Response response = uninstall();

        assertEquals(200, response.getStatus());
        verify(flux).destroy(NS, RELEASE);
        verify(commandService).revokeKdpsObjectsForRelease(eq(NS), eq(RELEASE), any());
    }

    @Test
    void anObjectsCleanupThatThrowsDoesNotFailTheUninstall() throws Exception {
        recordedDirect();
        when(commandService.revokeKdpsObjectsForRelease(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("unexpected"));

        Response response = uninstall();

        assertEquals(200, response.getStatus());
        verify(releaseMetadata).delete(NS, RELEASE);
    }

    @Test
    void theConfirmationListsTheObjectsTheUninstallRemoves() throws Exception {
        K8sReleaseEntity meta = recordedDirect();
        meta.setKdpsObjects(java.util.List.of("ClusterRoleBinding/kdps-monview-team-a-trino-thanos-token"));
        when(commandService.kdpsObjectsOfRelease(NS, RELEASE, meta.getKdpsObjects()))
                .thenReturn(java.util.List.of("Secret/trino-keytab", "ConfigMap/trino-krb5-conf"));

        Response response = resource.kdpsObjectsOfRelease(NS, RELEASE);

        assertEquals(200, response.getStatus());
        assertEquals(Map.of("objects", java.util.List.of("Secret/trino-keytab", "ConfigMap/trino-krb5-conf")),
                response.getEntity());
    }

    @Test
    void aConfirmationThatCannotListTheObjectsListsNone() throws Exception {
        when(commandService.kdpsObjectsOfRelease(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("cluster unreachable"));

        Response response = resource.kdpsObjectsOfRelease(NS, RELEASE);

        assertEquals(200, response.getStatus());
        assertEquals(Map.of("objects", java.util.List.of()), response.getEntity());
    }
}
