package org.apache.ambari.view.k8s.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents the status of a Kubernetes component
 */
public class ComponentStatus {
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("status")
    private String status;

    // Default constructor for JSON deserialization
    public ComponentStatus() {
    }

    public ComponentStatus(String name, String status) {
        this.name = name;
        this.status = status;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }
    
    @Override
    public String toString() {
        return "ComponentStatus{" +
                "name='" + name + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}