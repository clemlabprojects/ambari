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
package org.apache.ambari.view.k8s.service.deployment;

import org.apache.ambari.view.k8s.model.HelmReleaseDTO;
import org.apache.ambari.view.k8s.service.FluxApiVersions;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The status of a GitOps release whose HelmRelease the cluster does not return: what it waits for, or that the cluster
 * does not serve the Flux HelmRelease API at all, which it answers as it answers for a release not applied yet.
 */
class FluxGitOpsBackendNotOnClusterTest {

    private static final FluxApiVersions.Selection SERVED =
            new FluxApiVersions.Selection(FluxApiVersions.Kind.HELM_RELEASE, "v2", null);

    private static K8sReleaseEntity recorded() {
        K8sReleaseEntity meta = new K8sReleaseEntity();
        meta.setNamespace("analytics");
        meta.setReleaseName("trino");
        meta.setDeploymentMode("FLUX_GITOPS");
        meta.setGitCommitMode("PR_MODE");
        meta.setGitBaseBranch("production");
        return meta;
    }

    private static HelmReleaseDTO status(String prUrl, String prState, String status) {
        HelmReleaseDTO dto = new HelmReleaseDTO();
        dto.namespace = "analytics";
        dto.name = "trino";
        dto.status = status;
        dto.gitPrUrl = prUrl;
        dto.gitPrState = prState;
        return dto;
    }

    @Test
    void aReleaseWhosePullRequestIsOpenWaitsForItsMerge() {
        HelmReleaseDTO dto = status("https://git.example.org/platform/gitops/pulls/7", "open", "UNKNOWN");

        FluxGitOpsBackend.notOnCluster(dto, recorded(), SERVED);

        assertEquals("PENDING_PR", dto.status);
        assertEquals("Waiting for the pull request https://git.example.org/platform/gitops/pulls/7 to be merged into"
                + " production: Flux installs the release once it is merged", dto.message);
        assertEquals(dto.message, dto.reconcileMessage);
    }

    @Test
    void aMergedChangeKeepsTheStatusItsPullRequestGave() {
        HelmReleaseDTO dto = status("https://git.example.org/platform/gitops/pulls/7", "merged", "PROGRESSING");
        dto.message = "PR merged; waiting for Flux reconciliation";

        FluxGitOpsBackend.notOnCluster(dto, recorded(), SERVED);

        assertEquals("PROGRESSING", dto.status);
        assertTrue(dto.message.endsWith("HelmRelease not on the cluster yet"), dto.message);
    }

    @Test
    void aReleaseWithoutAPullRequestWaitsForFlux() {
        HelmReleaseDTO dto = status(null, null, "UNKNOWN");

        FluxGitOpsBackend.notOnCluster(dto, recorded(), SERVED);

        assertEquals("NOT_FOUND", dto.status);
        assertEquals("HelmRelease not found; waiting for Flux to reconcile", dto.message);
    }

    @Test
    void aClusterThatDoesNotServeTheFluxApiIsNotTakenForAReleaseNotAppliedYet() {
        HelmReleaseDTO dto = status("https://git.example.org/platform/gitops/pulls/7", "open", "UNKNOWN");
        FluxApiVersions.Selection notServed = new FluxApiVersions.Selection(FluxApiVersions.Kind.HELM_RELEASE, "v2",
                "The cluster serves the Flux HelmRelease API only at helm.toolkit.fluxcd.io v3");

        FluxGitOpsBackend.notOnCluster(dto, recorded(), notServed);

        assertEquals("UNKNOWN", dto.status);
        assertEquals("The cluster serves the Flux HelmRelease API only at helm.toolkit.fluxcd.io v3", dto.message);
        assertFalse(List.of("NOT_FOUND", "PENDING_PR").contains(dto.status));
    }
}
