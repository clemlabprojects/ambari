package org.apache.ambari.view.k8s.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Represents a Kubernetes cluster node with basic information
 */
public class ClusterNode {
    
    @JsonProperty("id")
    private String nodeId;
    
    @JsonProperty("name")
    private String nodeName;
    
    @JsonProperty("status")
    private String nodeStatus;
    
    @JsonProperty("roles")
    private List<String> nodeRoles;
    
    @JsonProperty("cpuUsage")
    private double cpuUsagePercent;
    
    @JsonProperty("memoryUsage")
    private double memoryUsagePercent;

    // Default constructor for JSON deserialization
    public ClusterNode() {
    }

    public ClusterNode(String nodeId, String nodeName, String nodeStatus, 
                      List<String> nodeRoles, double cpuUsagePercent, double memoryUsagePercent) {
        this.nodeId = nodeId;
        this.nodeName = nodeName;
        this.nodeStatus = nodeStatus;
        this.nodeRoles = nodeRoles;
        this.cpuUsagePercent = cpuUsagePercent;
        this.memoryUsagePercent = memoryUsagePercent;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getNodeStatus() {
        return nodeStatus;
    }

    public List<String> getNodeRoles() {
        return nodeRoles;
    }

    public double getCpuUsagePercent() {
        return cpuUsagePercent;
    }

    public double getMemoryUsagePercent() {
        return memoryUsagePercent;
    }
    
    @Override
    public String toString() {
        return "ClusterNode{" +
                "nodeId='" + nodeId + '\'' +
                ", nodeName='" + nodeName + '\'' +
                ", nodeStatus='" + nodeStatus + '\'' +
                ", nodeRoles=" + nodeRoles +
                ", cpuUsagePercent=" + cpuUsagePercent +
                ", memoryUsagePercent=" + memoryUsagePercent +
                '}';
    }
}