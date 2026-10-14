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
package org.apache.ambari.view.k8s.service.deployment;

import org.apache.ambari.view.k8s.model.DeploymentMode;
import org.apache.ambari.view.k8s.model.HelmReleaseDTO;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.CommandService;

/**
 * Flux GitOps deploys as the view runs them: the command plan of a direct Helm deploy, which prepares the release
 * (keytab, SSO client, Ranger, Hadoop configuration, certificates) and runs its integrations once it is up, with the
 * Helm install replaced by a commit to Git and a wait for Flux. Status and removal go to {@link FluxGitOpsBackend}.
 */
public class GitOpsPlanBackend implements DeploymentBackend {
    private final CommandService commandService;
    private final FluxGitOpsBackend fluxGitOpsBackend;

    public GitOpsPlanBackend(CommandService commandService, FluxGitOpsBackend fluxGitOpsBackend) {
        this.commandService = commandService;
        this.fluxGitOpsBackend = fluxGitOpsBackend;
    }

    /**
     * Plans the deploy and starts it.
     *
     * @return the command id, polled like that of a direct deploy
     */
    @Override
    public String apply(HelmDeployRequest request, DeploymentContext context) {
        return commandService.gitOpsDeploy(request,
                context.getRepoId(),
                context.getVersion(),
                context.getCommandsUrl(),
                context.getCallerHeaders(),
                context.getBaseUri(),
                context.getAmbariAliasResolver());
    }

    @Override
    public HelmReleaseDTO status(String namespace, String releaseName) throws Exception {
        return fluxGitOpsBackend.status(namespace, releaseName);
    }

    @Override
    public boolean supports(String deploymentMode) {
        return DeploymentMode.FLUX_GITOPS.name().equalsIgnoreCase(deploymentMode);
    }

    @Override
    public void destroy(String namespace, String releaseName) throws Exception {
        fluxGitOpsBackend.destroy(namespace, releaseName);
    }
}
