/*
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

import org.apache.ambari.view.k8s.model.HelmReleaseDTO;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What the Releases page gets of a GitOps release's Git record: the base branch Flux reconciles and the commit mode,
 * which its redeploys send back, and whether its removal waits for a pull request.
 */
class HelmResourceGitRecordTest {

    private static K8sReleaseEntity gitOps(String commitMode, String branch, String baseBranch) {
        K8sReleaseEntity meta = new K8sReleaseEntity();
        meta.setNamespace("analytics");
        meta.setReleaseName("trino");
        meta.setDeploymentMode("FLUX_GITOPS");
        meta.setGitRepoUrl("https://git.example.org/platform/gitops.git");
        meta.setGitCommitMode(commitMode);
        meta.setGitBranch(branch);
        meta.setGitBaseBranch(baseBranch);
        return meta;
    }

    @Test
    void aPullRequestReleaseShowsTheBaseBranchItsChangesAreMergedInto() {
        K8sReleaseEntity meta = gitOps("PR_MODE", "flux-3f2a1c4e-0b5d-4c6e-9f7a-8b9c0d1e2f3a", "production");
        meta.setGitPrUrl("https://git.example.org/platform/gitops/pulls/4");
        meta.setGitPendingDelete(true);
        HelmReleaseDTO dto = new HelmReleaseDTO();

        HelmResource.copyGitRecord(meta, dto);

        assertEquals("production", dto.gitBaseBranch);
        assertEquals(Boolean.FALSE, dto.gitBaseBranchAssumed);
        assertEquals("PR_MODE", dto.gitCommitMode);
        assertEquals("flux-3f2a1c4e-0b5d-4c6e-9f7a-8b9c0d1e2f3a", dto.gitBranch);
        assertEquals("https://git.example.org/platform/gitops/pulls/4", dto.gitPrUrl);
        assertEquals(Boolean.TRUE, dto.gitPendingDelete);
    }

    @Test
    void aReleaseRecordedBeforeItsBaseBranchWasShowsTheOneFluxReads() {
        HelmReleaseDTO generated = new HelmReleaseDTO();
        HelmResource.copyGitRecord(gitOps(null, "flux-3f2a1c4e-0b5d-4c6e-9f7a-8b9c0d1e2f3a", null), generated);
        assertEquals("main", generated.gitBaseBranch, "never a branch KDPS generated");
        assertEquals(Boolean.TRUE, generated.gitBaseBranchAssumed, "a redeploy does not send it back as recorded");
        assertEquals("DIRECT_COMMIT", generated.gitCommitMode);
        assertEquals(Boolean.FALSE, generated.gitPendingDelete);

        HelmReleaseDTO named = new HelmReleaseDTO();
        HelmResource.copyGitRecord(gitOps("DIRECT_COMMIT", "production", null), named);
        assertEquals("production", named.gitBaseBranch, "the branch the operator committed to");
        assertEquals(Boolean.FALSE, named.gitBaseBranchAssumed);

        HelmReleaseDTO pullRequest = new HelmReleaseDTO();
        HelmResource.copyGitRecord(gitOps("PR_MODE", "feature/trino", null), pullRequest);
        assertEquals("main", pullRequest.gitBaseBranch, "never the branch of a pull request");
        assertEquals(Boolean.TRUE, pullRequest.gitBaseBranchAssumed);
    }

    @Test
    void aHelmReleaseHasNoGitOptions() {
        K8sReleaseEntity helm = new K8sReleaseEntity();
        helm.setDeploymentMode("DIRECT_HELM");
        HelmReleaseDTO dto = new HelmReleaseDTO();

        HelmResource.copyGitRecord(helm, dto);

        assertNull(dto.gitBaseBranch);
        assertNull(dto.gitBaseBranchAssumed);
        assertNull(dto.gitCommitMode);
        assertNull(dto.gitPendingDelete);
    }
}
