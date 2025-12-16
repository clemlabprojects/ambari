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

import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.model.HelmReleaseDTO;

/**
 * Abstraction over deployment backends (Direct Helm vs GitOps/Flux).
 * Implementations must be stateless or thread-safe.
 */
public interface DeploymentBackend {

    /**
     * Plan or apply a deployment/upgrade.
     *
     * @param request normalized Helm deploy request (values already resolved).
     * @return an identifier that can be used to poll status (e.g., commandId, commit SHA).
     * @throws Exception if the backend cannot start the operation.
     */
    String apply(HelmDeployRequest request, DeploymentContext context) throws Exception;

    /**
     * Fetch status for a deployment.
     *
     * @param namespace   release namespace.
     * @param releaseName release name.
     * @return status DTO describing the release.
     * @throws Exception if status cannot be fetched.
     */
    HelmReleaseDTO status(String namespace, String releaseName) throws Exception;

    /**
     * @return true if this backend should be used for the given deploymentMode flag.
     */
    default boolean supports(String deploymentMode) {
        return deploymentMode != null && deploymentMode.equalsIgnoreCase("DIRECT_HELM");
    }

    /**
     * Destroy/uninstall a release.
     *
     * @param namespace   release namespace.
     * @param releaseName release name.
     * @throws Exception if deletion fails.
     */
    void destroy(String namespace, String releaseName) throws Exception;
}
