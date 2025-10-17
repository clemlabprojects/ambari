package org.apache.ambari.view.k8s.requests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * DTO (Data Transfer Object) for the deployment request.
 * This class maps the JSON payload sent by the frontend.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HelmDeployRequest {
    private static final ObjectMapper OM = new ObjectMapper();
    private String chart;
    private String releaseName;
    private String namespace;
    private Map<String, Object> values;
    private String serviceKey;
    private String version;
    private String secretName;
    private LinkedHashMap<String,Object> dependencies;
    private Map<String, Object> mounts;

    // Getters and Setters
    public String getChart() { 
        return chart; 
    }
    
    public void setChart(String chart) { 
        this.chart = chart; 
    }
    
    public String getReleaseName() { 
        return releaseName; 
    }
    
    public void setReleaseName(String releaseName) { 
        this.releaseName = releaseName; 
    }
    
    public String getNamespace() { 
        return namespace; 
    }
    
    public void setNamespace(String namespace) { 
        this.namespace = namespace; 
    }
    
    public Map<String, Object> getValues() { 
        return values; 
    }
    
    public void setValues(Map<String, Object> values) { 
        this.values = values; 
    }

    public String getServiceKey() { 
        return serviceKey; 
    }
    
    public void setServiceKey(String serviceKey) { 
        this.serviceKey = serviceKey; 
    }

    public void setDependencies(LinkedHashMap<String, Object> dependencies) {
        this.dependencies = dependencies;
    }

    public LinkedHashMap<String, Object> getDependencies(){
        return this.dependencies;
    }

    public void setVersion(String version){
        this.version = version;
    }
    public String getVersion(){
        return this.version;
    }

    public void setSecretName(String secretName){
        this.secretName = secretName;
    }
    public String getSecretName(){ return this.secretName ;}

    public Map<String, Object> getMounts() { return mounts; }

    // Accept both object and string (for safety)
    @JsonSetter("mounts")
    public void setMounts(Object raw) {
        try {
            if (raw == null) {
                this.mounts = null;
            } else if (raw instanceof Map) {
                // already the right shape
                //noinspection unchecked
                this.mounts = (Map<String, Object>) raw;
            } else if (raw instanceof String s) {
                if (s.isBlank()) {
                    this.mounts = null;
                } else {
                    // parse JSON string into map
                    //noinspection unchecked
                    this.mounts = OM.readValue(s, LinkedHashMap.class);
                }
            } else {
                // unknown shape (array/spec) -> ignore or convert as you like
                this.mounts = null;
            }
        } catch (Exception e) {
            // be tolerant; log upstream if needed
            this.mounts = null;
        }
    }
}