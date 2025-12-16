package org.apache.ambari.view.k8s.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.persistence.Column;
import javax.persistence.Transient;
import java.util.List;
import java.util.Map;

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

    @JsonProperty("imageGlobalRegistryProperty")
    private String imageGlobalRegistryProperty;

    @Column(length = 8192)
    private String endpointsJson;
    // Default constructor for JSON deserialization
    public HelmRelease() {
    }

    public HelmRelease(String id, String name, String namespace, String chart, String version, String status, String imageGlobalRegistryProperty) {
        this.id = id;
        this.name = name;
        this.namespace = namespace;
        this.chart = chart;
        this.version = version;
        this.status = status;
        this.imageGlobalRegistryProperty = imageGlobalRegistryProperty;
    }
    
    public String getId() { return id; }
    public String getName() { return name; }
    public String getNamespace() { return namespace; }
    public String getChart() { return chart; }
    public String getVersion() { return version; }
    public String getStatus() { return status; }
    public String getImageGlobalRegistryProperty() { return imageGlobalRegistryProperty;}
    
    @Override
    public String toString() {
        return "HelmRelease{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", namespace='" + namespace + '\'' +
                ", chart='" + chart + '\'' +
                ", version='" + version + '\'' +
                ", status='" + status + '\'' +
                ", imageGlobalRegistryProperty='" + imageGlobalRegistryProperty + '\'' +
                '}';
    }

    // transient helper if you want structured access
    @Transient
    public List<Map<String, Object>> getEndpoints() {
        if (endpointsJson == null || endpointsJson.isBlank()) return null;
        try {
            ObjectMapper om = new ObjectMapper();
            //noinspection unchecked
            return om.readValue(endpointsJson, List.class);
        } catch (Exception e) {
            return null;
        }
    }

    public void setEndpoints(List<Map<String, Object>> endpoints) {
        if (endpoints == null) {
            this.endpointsJson = null;
            return;
        }
        try {
            ObjectMapper om = new ObjectMapper();
            this.endpointsJson = om.writeValueAsString(endpoints);
        } catch (Exception e) {
            // worst case: drop endpoints if serialization fails
            this.endpointsJson = null;
        }
    }
}