package org.apache.ambari.view.k8s.model;

/**
 * Represents the type of Helm command being executed
 */
public enum CommandType {
    HELM_DEPLOY,
    HELM_UPGRADE,
    HELM_UNINSTALL
}