package org.apache.ambari.view.k8s.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;

/**
 * Represents the execution state of a command in the Kubernetes cluster
 */
public enum CommandState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED;

}