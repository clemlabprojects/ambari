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

import org.apache.ambari.view.k8s.model.GitOpsDeployResult;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The answer to an accepted deploy: its operation and mode, and for a GitOps deploy what it does in Git. */
class CommandResourceDeployAnswerTest {

    @Test
    void aGitOpsDeployIsAnsweredWithWhatItDoesInGit() {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setDeploymentMode("FLUX_GITOPS");
        HelmDeployRequest.GitOptions git = new HelmDeployRequest.GitOptions();
        git.setBaseBranch("main");
        git.setCommitMode("DIRECT_COMMIT");
        request.setGit(git);

        Map<String, Object> body = CommandResource.deployAccepted("op-1", request);

        assertEquals("op-1", body.get("id"), "an operation polled like any other");
        assertEquals("FLUX_GITOPS", body.get("mode"));
        GitOpsDeployResult gitOps = (GitOpsDeployResult) body.get("gitOps");
        assertEquals("FLUX_GITOPS", gitOps.mode);
        assertEquals("main", gitOps.branch);
    }

    @Test
    void aHelmDeployIsAnsweredWithItsOperationOnly() {
        HelmDeployRequest request = new HelmDeployRequest();

        Map<String, Object> body = CommandResource.deployAccepted("op-2", request);

        assertEquals(Map.of("id", "op-2", "mode", "DIRECT_HELM"), body);
    }
}
