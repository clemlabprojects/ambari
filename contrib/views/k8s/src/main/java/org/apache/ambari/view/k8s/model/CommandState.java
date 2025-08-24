package org.apache.ambari.view.k8s.model;

/**
 * Represents the execution state of a command in the Kubernetes cluster
 */
public enum CommandState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}