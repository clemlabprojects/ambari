package org.apache.ambari.view.k8s.requests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * DTO (Data Transfer Object) for the deployment request.
 * This class maps the JSON payload sent by the frontend.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HelmDeployRequest {
    private String chart;
    private String releaseName;
    private String namespace;
    private Map<String, Object> values;

    private String serviceKey;
    // Getters and Setters
    public String getChart() { return chart; }
    public void setChart(String chart) { this.chart = chart; }
    public String getReleaseName() { return releaseName; }
    public void setReleaseName(String releaseName) { this.releaseName = releaseName; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public Map<String, Object> getValues() { return values; }
    public void setValues(Map<String, Object> values) { this.values = values; }

    public String getServiceKey() { return serviceKey; }
    public void setServiceKey(String serviceKey) { this.serviceKey = serviceKey; }
}