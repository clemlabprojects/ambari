package org.apache.ambari.view.k8s.model;

import java.util.List;

public class ClusterNode {
    private String id;
    private String name;
    private String status;
    private List<String> roles;
    private double cpuUsage;
    private double memoryUsage;

    public ClusterNode(String id, String name, String status, List<String> roles, double cpuUsage, double memoryUsage) {
        this.id = id;
        this.name = name;
        this.status = status;
        this.roles = roles;
        this.cpuUsage = cpuUsage;
        this.memoryUsage = memoryUsage;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public List<String> getRoles() {
        return roles;
    }

    public double getCpuUsage() {
        return cpuUsage;
    }

    public double getMemoryUsage() {
        return memoryUsage;
    }
}