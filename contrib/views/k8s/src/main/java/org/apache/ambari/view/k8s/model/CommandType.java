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
    // High-level composite
    K8S_MANAGER_RELEASE_DEPLOY,

    K8S_MOUNTS_DIR_EXEC,
    // Leaf steps used by the orchestrator

    CLUSTER_STACK_CONFIGURATION_REQUIREMENTS,

    HELM_REPO_VALIDATE,
    HELM_REPO_ADD,
    HELM_REPO_REMOVE,
    HELM_REPO_LOGIN,

    HELM_DEPLOY_DRY_RUN,
    CONFIG_MATERIALIZE,
    HELM_DEPLOY,
    HELM_UPGRADE,
    HELM_UNINSTALL,

    // Optional: dependency resolution & verification
    HELM_DEPLOY_DEPENDENCY_CRD,
    HELM_DEPLOY_DRY_RUN_DEPENDENCY_RELEASE,
    HELM_DEPLOY_DEPENDENCY_RELEASE,
    DEPENDENCY_SATISFIED,
    POST_VERIFY,

    // keytabs
    KEYTAB_REQUEST_ROOT,
    KEYTAB_ISSUE_PRINCIPAL,   // create-if-missing principal + keytab (idempotent)
    KEYTAB_CREATE_SECRET,
    REGENERATE_KEYTABS,

    // Ranger related commands
    RANGER_REPOSITORY_CREATION,
    RANGER_USER_CREATION,
    RANGER_REPOSITORY_REAPPLY,
}
