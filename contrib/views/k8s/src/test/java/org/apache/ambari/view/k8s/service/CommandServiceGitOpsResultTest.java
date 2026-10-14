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

import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.model.GitOpsDeployResult;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a GitOps deploy tells the operator of what it does in Git: the branch when it is submitted, then the commit,
 * the pull request and whether Git already held the change; and the redeploy a change of a release's values runs.
 */
class CommandServiceGitOpsResultTest {

    private static FluxGitOpsBackend.CommitResult commit(String id, String sha, boolean committed, String branch,
                                                         String prUrl, String prState) {
        return new FluxGitOpsBackend.CommitResult(id, sha, committed, "https://git.example.org/platform/gitops.git",
                branch, "main", prUrl == null, prUrl, prState, "digest");
    }

    @Test
    void aDirectCommitNamesItsCommitAndTheBranchFluxReads() {
        GitOpsDeployResult result = CommandService.gitOpsDeployResult(
                commit("3f0c9a5", "3f0c9a5", true, "main", null, null));

        assertEquals("FLUX_GITOPS", result.mode);
        assertEquals("3f0c9a5", result.commit);
        assertEquals("main", result.branch);
        assertNull(result.prUrl);
        assertNull(result.prState);
        assertEquals(Boolean.FALSE, result.noChanges);
    }

    @Test
    void aPullRequestIsNamedWithItsState() {
        GitOpsDeployResult result = CommandService.gitOpsDeployResult(commit("PR-STUB:9b1e2d4", "PR-STUB:9b1e2d4", true,
                "flux-1", "https://git.example.org/platform/gitops/pulls/7", "open"));

        assertEquals("9b1e2d4", result.commit, "a commit, not the marker of one left in the view's workspace");
        assertEquals("flux-1", result.branch);
        assertEquals("https://git.example.org/platform/gitops/pulls/7", result.prUrl);
        assertEquals("open", result.prState);
    }

    @Test
    void aChangeGitAlreadyHeldCommitsNothing() {
        assertEquals(Boolean.TRUE, CommandService.gitOpsDeployResult(
                commit("NO_CHANGES", "3f0c9a5", false, "main", null, null)).noChanges);
    }

    @Test
    void theAnswerToASubmittedDeployNamesTheBranchTheRequestNames() {
        HelmDeployRequest direct = new HelmDeployRequest();
        direct.setDeploymentMode("FLUX_GITOPS");
        HelmDeployRequest.GitOptions git = new HelmDeployRequest.GitOptions();
        git.setBaseBranch("production");
        git.setBranch("ignored-for-a-direct-commit");
        git.setCommitMode("DIRECT_COMMIT");
        direct.setGit(git);
        assertEquals("production", CommandService.gitOpsSubmitted(direct).branch);

        git.setCommitMode("PR_MODE");
        git.setBranch("flux-trino");
        assertEquals("flux-trino", CommandService.gitOpsSubmitted(direct).branch, "the pull request's branch");

        git.setBranch(null);
        GitOpsDeployResult picked = CommandService.gitOpsSubmitted(direct);
        assertEquals("FLUX_GITOPS", picked.mode);
        assertNull(picked.branch, "picked by the commit step");
        assertNull(picked.commit);
        assertNull(picked.noChanges);

        HelmDeployRequest helm = new HelmDeployRequest();
        helm.setDeploymentMode("DIRECT_HELM");
        assertNull(CommandService.gitOpsSubmitted(helm));
        assertNull(CommandService.gitOpsSubmitted(new HelmDeployRequest()), "a deploy with Helm by default");
    }

    @Test
    void aRedeployOfAReleaseDeployedWithHelmGoesThroughHelm() {
        K8sReleaseEntity meta = new K8sReleaseEntity();
        meta.setNamespace("analytics");
        meta.setReleaseName("trino");
        meta.setChartRef("clemlab-trino");
        meta.setDeploymentMode("DIRECT_HELM");

        HelmDeployRequest request = CommandService.releaseRedeployRequest(meta, Map.of("a", 1));

        assertEquals("DIRECT_HELM", request.getDeploymentMode());
        assertNull(request.getGit());
        assertNull(request.getFormValues());
        assertEquals(Map.of("a", 1), request.getValues());
    }

    @Test
    void aRedeployOfAGitOpsReleaseSendsNoBaseBranchItsRecordOnlyAssumes() {
        K8sReleaseEntity meta = new K8sReleaseEntity();
        meta.setNamespace("analytics");
        meta.setReleaseName("trino");
        meta.setChartRef("clemlab-trino");
        meta.setDeploymentMode("FLUX_GITOPS");
        meta.setGitRepoUrl("https://git.example.org/platform/gitops.git");
        meta.setGitBranch("flux-3f2a1c4e-0b5d-4c6e-9f7a-8b9c0d1e2f3a");

        HelmDeployRequest request = CommandService.releaseRedeployRequest(meta, Map.of());

        assertEquals("FLUX_GITOPS", request.getDeploymentMode());
        assertNull(request.getGit().getBaseBranch(), "main is only assumed for it");
        assertEquals("DIRECT_COMMIT", request.getGit().getCommitMode());
        assertNull(request.getGit().getBranch(), "the backend picks the branch of a pull request");
    }
    private static CommandEntity deploy(String id, String state, String params, Map<String, CommandStatusEntity> statuses) {
        CommandEntity root = new CommandEntity();
        root.setId(id);
        root.setType(CommandType.K8S_MANAGER_RELEASE_DEPLOY.name());
        root.setChildListJson("[\"" + id + "-step\"]");
        root.setCommandStatusId(id + "-status");
        root.setParamsJson(params);
        CommandStatusEntity status = new CommandStatusEntity();
        status.setId(id + "-status");
        status.setState(state);
        statuses.put(status.getId(), status);
        return root;
    }

    @Test
    void onlyAGitOpsDeployOfTheReleaseThatHasNotFinishedIsRunning() {
        Map<String, CommandStatusEntity> statuses = new HashMap<>();
        String gitOps = "{\"namespace\":\"trino\",\"releaseName\":\"trino-flux\",\"_gitOpsRelease\":{}}";
        List<CommandEntity> roots = new ArrayList<>(List.of(
                deploy("done", "SUCCEEDED", gitOps, statuses),
                deploy("failed", "FAILED", gitOps, statuses),
                deploy("helm", "RUNNING", "{\"namespace\":\"trino\",\"releaseName\":\"trino-flux\"}", statuses),
                deploy("other", "RUNNING",
                        "{\"namespace\":\"trino\",\"releaseName\":\"trino-b\",\"_gitOpsRelease\":{}}", statuses)));

        assertNull(CommandService.runningGitOpsDeploy(roots, statuses::get, "trino", "trino-flux"));

        roots.add(deploy("queued", "PENDING", gitOps, statuses));
        assertEquals("queued", CommandService.runningGitOpsDeploy(roots, statuses::get, "trino", "trino-flux"));
        statuses.get("queued-status").setState("RUNNING");
        assertEquals("queued", CommandService.runningGitOpsDeploy(roots, statuses::get, "trino", "trino-flux"),
                "until Flux has applied what it committed");
        assertEquals("other", CommandService.runningGitOpsDeploy(roots, statuses::get, "trino", "trino-b"));
    }
}
