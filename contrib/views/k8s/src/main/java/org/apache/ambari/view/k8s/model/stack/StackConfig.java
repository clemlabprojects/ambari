package org.apache.ambari.view.k8s.model.stack;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class StackConfig {
    public String name;          // "superset-env"
    public String label;         // optional display label
    public String description;
    public List<StackProperty> properties;
}
