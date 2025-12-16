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

import org.apache.ambari.view.k8s.model.HelmReleaseDTO;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.CommandService;
import org.apache.ambari.view.k8s.service.HelmService;
import org.apache.ambari.view.k8s.service.ViewConfigurationService;

import java.util.List;

/**
 * Adapter for the legacy direct Helm path. Kept as a placeholder while we wire
 * a dispatcher between deployment modes.
 */
public class HelmDirectBackend implements DeploymentBackend {
    private final CommandService commandService;
    private final HelmService helmService;
    private final ViewConfigurationService viewConfigurationService;

    public HelmDirectBackend(CommandService commandService) {
        this.commandService = commandService;
        this.helmService = new HelmService(commandService.getCtx());
        this.viewConfigurationService = new ViewConfigurationService(commandService.getCtx());
    }

    @Override
    public String apply(HelmDeployRequest request, DeploymentContext context) throws Exception {
        // Delegate to CommandService helper to reuse the existing direct Helm pipeline.
        return commandService.directHelmDeploy(request,
                context.getRepoId(),
                context.getVersion(),
                context.getKubeContext(),
                context.getCommandsUrl(),
                context.getCallerHeaders(),
                context.getBaseUri(),
                context.getAmbariAliasResolver());
    }

    @Override
    public HelmReleaseDTO status(String namespace, String releaseName) throws Exception {
        // If available, use HelmService directly to avoid recomputing configs inside CommandService.
        try {
            String kubeconfigContent = viewConfigurationService.getKubeconfigContents();
            List<com.marcnuri.helm.Release> releases = helmService.list(namespace, kubeconfigContent);
            return releases.stream()
                    .filter(r -> releaseName.equals(r.getName()))
                    .findFirst()
                    .map(HelmReleaseDTO::from)
                    .orElse(null);
        } catch (Exception ex) {
            // Fallback to CommandService helper
            return commandService.directHelmStatus(namespace, releaseName);
        }
    }

    @Override
    public void destroy(String namespace, String releaseName) throws Exception {
        // Direct uninstall via HelmService; fallback through CommandService on errors.
        try {
            String kubeconfigContent = viewConfigurationService.getKubeconfigContents();
            helmService.uninstall(namespace, releaseName, kubeconfigContent);
        } catch (Exception ex) {
            commandService.directHelmDestroy(namespace, releaseName);
        }
    }
}
