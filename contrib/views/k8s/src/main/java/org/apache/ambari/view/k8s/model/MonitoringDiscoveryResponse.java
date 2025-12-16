package org.apache.ambari.view.k8s.model;

/**
 * DTO for monitoring discovery response.
 */
public class MonitoringDiscoveryResponse {
    public String namespace;
    public String release;
    public String url;
    public String state;
    public String message;

    public MonitoringDiscoveryResponse(String namespace, String release, String url) {
        this(namespace, release, url, null, null);
    }

    public MonitoringDiscoveryResponse(String namespace, String release, String url, String state, String message) {
        this.namespace = namespace;
        this.release = release;
        this.url = url;
        this.state = state;
        this.message = message;
    }
}
