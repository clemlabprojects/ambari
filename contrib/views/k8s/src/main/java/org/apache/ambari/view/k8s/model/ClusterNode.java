package org.apache.ambari.view.k8s.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import java.util.List;

/**
 * Represents a Kubernetes cluster node with basic information
 */
@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.ANY,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE
)
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


    @JsonProperty("id")
    public String getNodeId() { return nodeId; }

    @JsonProperty("name")
    public String getNodeName() { return nodeName; }

    @JsonProperty("status")
    public String getNodeStatus() { return nodeStatus; }

    @JsonProperty("roles")
    public List<String> getNodeRoles() { return nodeRoles; }

    @JsonProperty("cpuUsage")
    public double getCpuUsagePercent() { return cpuUsagePercent; }

    @JsonProperty("memoryUsage")
    public double getMemoryUsagePercent() { return memoryUsagePercent; }

    public void setCpuUsagePercent(double cpuUsagePercent) {
        this.cpuUsagePercent = cpuUsagePercent;
    }

    public void setMemoryUsagePercent(double memoryUsagePercent) {
        this.memoryUsagePercent = memoryUsagePercent;
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
