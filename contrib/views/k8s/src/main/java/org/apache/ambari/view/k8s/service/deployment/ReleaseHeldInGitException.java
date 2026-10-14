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

import org.apache.ambari.view.k8s.model.DeploymentMode;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;

/**
 * A change KDPS refuses to make with Helm on a release Flux reconciles from Git (a rollback, a chart upgrade, a deploy
 * asking for a direct Helm upgrade): Flux installs what Git holds and would undo it at its next reconcile, leaving Git
 * and the cluster at odds until then. The REST resources answer it with 409.
 */
public class ReleaseHeldInGitException extends RuntimeException {

    public ReleaseHeldInGitException(String message) {
        super(message);
    }

    /**
     * Refuses a change made with Helm on a release recorded as deployed with Flux GitOps.
     *
     * @param metadata  the release's record; {@code null} for a release KDPS did not record, which is not refused
     * @param namespace the release namespace
     * @param release   the release name
     * @param change    the change refused, as the sentence names it (e.g. "a Helm rollback")
     * @throws ReleaseHeldInGitException when the release is deployed with Flux GitOps
     */
    public static void refuseIfHeldInGit(K8sReleaseEntity metadata, String namespace, String release, String change) {
        if (metadata != null && DeploymentMode.FLUX_GITOPS.name().equalsIgnoreCase(metadata.getDeploymentMode())) {
            throw new ReleaseHeldInGitException("Release " + namespace + "/" + release + " is deployed with Flux GitOps:"
                    + " Flux installs what Git holds and would undo " + change + " at its next reconcile. Revert or"
                    + " change the release in Git instead: revert its commit in the Git repository, or use"
                    + " Upgrade/Config, which commits the change to Git.");
        }
    }
}
