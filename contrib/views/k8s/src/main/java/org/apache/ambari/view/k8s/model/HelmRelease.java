package org.apache.ambari.view.k8s.model;

public class HelmRelease {
    private String id;
    private String name;
    private String namespace;
    private String chart;
    private String version;
    private String status;

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
}