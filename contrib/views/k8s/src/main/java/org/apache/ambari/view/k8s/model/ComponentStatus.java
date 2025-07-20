package org.apache.ambari.view.k8s.model;

public class ComponentStatus {
    private String name;
    private String status;

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
}
