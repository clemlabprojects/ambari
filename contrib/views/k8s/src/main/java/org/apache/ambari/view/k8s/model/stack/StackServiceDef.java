package org.apache.ambari.view.k8s.model.stack;

import org.apache.ambari.view.k8s.model.FormField;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class StackServiceDef {
    public String name;          // "SUPERSET"
    public String label;         // "Apache Superset"
    public String chart;         // "apache/superset"
    public String description;
    public String version;
    public List<FormField> form; // Reuses your existing dynamic form definitions

    // Additional fields used by bindings/variables/etc.
    public String pattern;
    public String secretName;
    public Map<String, Object> dependencies;
    public List<Map<String, Object>> endpoints;
    public List<Map<String, Object>> mounts;
    public List<Map<String, Object>> variables;
    public List<Map<String, Object>> bindings;
    /**
     * Optional catalog enrichment specs. These are applied by the backend to augment
     * catalog content (e.g., Trino Hive catalog) with dynamic values such as
     * Kerberos principals from Ambari, without hardcoding service logic.
     */
    public List<Map<String, Object>> catalogEnrichments;
    public Map<String, Map<String, Object>> ranger;
    public List<Map<String, Object>> requiredConfigMaps;
    public List<String> dynamicValues;
    public List<Map<String, Object>> tls;
    public List<Map<String, Object>> kerberos;
}
