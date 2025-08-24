package org.apache.ambari.view.k8s.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a Helm release deployed in the Kubernetes cluster
 */
public class HelmRelease {
    
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("namespace")
    private String namespace;
    
    @JsonProperty("chart")
    private String chart;
    
    @JsonProperty("version")
    private String version;
    
    @JsonProperty("status")
    private String status;

    // Default constructor for JSON deserialization
    public HelmRelease() {
    }

    public HelmRelease(String id, String name, String namespace, String chart, String version, String status) {
        this.id = id;
        this.name = name;
        this.namespace = namespace;
        this.chart = chart;
        this.version = version;
        this.status = status;
    }
    
    public String getId() { return id; }
    public String getName() { return name; }
    public String getNamespace() { return namespace; }
    public String getChart() { return chart; }
    public String getVersion() { return version; }
    public String getStatus() { return status; }
    
    @Override
    public String toString() {
        return "HelmRelease{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", namespace='" + namespace + '\'' +
                ", chart='" + chart + '\'' +
                ", version='" + version + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}