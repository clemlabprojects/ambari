package org.apache.ambari.view.k8s.model;

// org.apache.ambari.view.k8s.model.ReleaseEndpointDTO

public class ReleaseEndpointDTO {
    private String id;          // e.g. "ui", "api", "metrics"
    private String label;       // nice label: "Web UI", "API", "Prometheus"
    private String url;         // fully resolved URL
    private String description; // optional, can be null
    private String kind;        // "http", "metrics", etc. if you want

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
}