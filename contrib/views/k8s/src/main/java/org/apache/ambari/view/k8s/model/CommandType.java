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

package org.apache.ambari.view.k8s.model;

/**
 * Represents the type of Helm command being executed
 */
public enum CommandType {

    /** Top-level composite command that orchestrates a full release deploy pipeline. */
    K8S_MANAGER_RELEASE_DEPLOY,

    /** Executes scripts or binaries from a mounted directory inside a pod. */
    K8S_MOUNTS_DIR_EXEC,

    /** Validates that all required Ambari stack configuration properties are present before deployment. */
    CLUSTER_STACK_CONFIGURATION_REQUIREMENTS,

    /** Checks whether a chart exists in a Helm repository without modifying state. */
    HELM_REPO_VALIDATE,

    /** Registers a new Helm repository in the local repository index. */
    HELM_REPO_ADD,

    /** Removes a previously registered Helm repository from the local index. */
    HELM_REPO_REMOVE,

    /** Authenticates against an OCI or HTTP Helm repository. */
    HELM_REPO_LOGIN,

    /** Runs {@code helm install --dry-run} to validate rendering without applying changes. */
    HELM_DEPLOY_DRY_RUN,

    /** Materialises Ambari configuration into Kubernetes ConfigMaps or Secrets before install. */
    CONFIG_MATERIALIZE,

    /** Installs a new Helm release into a namespace. */
    HELM_DEPLOY,

    /** Upgrades an existing Helm release to a new chart version or values set. */
    HELM_UPGRADE,

    /** Uninstalls an existing Helm release and cleans up its resources. */
    HELM_UNINSTALL,

    /** Installs CRDs required by a dependency chart before the main chart is deployed. */
    HELM_DEPLOY_DEPENDENCY_CRD,

    /** Dry-runs a dependency release to validate its chart template before installation. */
    HELM_DEPLOY_DRY_RUN_DEPENDENCY_RELEASE,

    /** Installs a dependency Helm release that the main chart requires to function. */
    HELM_DEPLOY_DEPENDENCY_RELEASE,

    /** Confirms that a prerequisite dependency is already satisfied in the cluster. */
    DEPENDENCY_SATISFIED,

    /** Runs post-deploy verification checks to confirm the release is healthy. */
    POST_VERIFY,

    /** Root command for a Kerberos keytab provisioning workflow, spawning principal sub-steps. */
    KEYTAB_REQUEST_ROOT,

    /** Creates a Kerberos principal if it does not exist and generates its keytab (idempotent). */
    KEYTAB_ISSUE_PRINCIPAL,

    /** Writes a generated Kerberos keytab into a Kubernetes Opaque Secret. */
    KEYTAB_CREATE_SECRET,

    /** Root command for re-running keytab generation for an already-deployed release. */
    REGENERATE_KEYTABS,

    /** Registers or updates an OIDC client in Keycloak, returning the client id and secret. */
    OIDC_REGISTER_CLIENT,

    /** Writes OIDC client credentials into a Kubernetes Secret and optionally into Vault. */
    OIDC_CREATE_SECRET,

    /** Root command for a standalone OIDC client re-registration, mirroring {@link #REGENERATE_KEYTABS}. */
    REGISTER_OIDC_CLIENT,

    /** Creates or updates a Ranger service repository for the deployed application. */
    RANGER_REPOSITORY_CREATION,

    /** Creates the service account user in Ranger required by the deployed application. */
    RANGER_USER_CREATION,

    /** Re-applies an existing Ranger repository configuration without a full Helm upgrade. */
    RANGER_REPOSITORY_REAPPLY,

    /** Automatically provisions a linked Ambari view instance after a successful deploy. */
    AMBARI_VIEW_PROVISION,
}
