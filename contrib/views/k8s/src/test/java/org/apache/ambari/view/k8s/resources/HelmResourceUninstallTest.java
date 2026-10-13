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
 * refuses or cannot take the removal, or another GitOps operation on the release holds its workspace.
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

        assertEquals(500, response.getStatus());
        String error = String.valueOf(((Map<?, ?>) response.getEntity()).get("error"));
        assertTrue(error.contains("Not allowed to push to protected branch main"), "Git's own message: " + error);
        // Flux keeps running the release from Git: it keeps its catalog credential, its SCCs and its record.
        verify(commandService, never()).revokePolarisForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).removeSccGrantsForRelease(anyString(), anyString(), any());
        verify(commandService, never()).revokeTagSyncRangerAccountForRelease(anyString(), anyString(), any(), any());
        verify(commandService, never()).revokeOidcForRelease(anyString(), anyString(), any(), any());
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
        verify(commandService, never()).revokePolarisForRelease(anyString(), anyString(), any(), any());
        verify(releaseMetadata, never()).delete(anyString(), anyString());
    }
}
