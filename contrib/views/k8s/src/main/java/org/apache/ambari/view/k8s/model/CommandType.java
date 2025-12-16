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
    POST_VERIFY,

    // keytabs
    KEYTAB_REQUEST_ROOT,
    KEYTAB_ISSUE_PRINCIPAL,   // create-if-missing principal + keytab (idempotent)
    KEYTAB_CREATE_SECRET,

    // Ranger related commands
    RANGER_REPOSITORY_CREATION,
    RANGER_USER_CREATION,
}