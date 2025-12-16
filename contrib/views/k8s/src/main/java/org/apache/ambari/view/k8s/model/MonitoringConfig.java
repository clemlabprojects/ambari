package org.apache.ambari.view.k8s.model;

/**
 * Simple POJO holding monitoring bootstrap configuration.
 */
public class MonitoringConfig {
    public boolean enabled = true;
    public String release = "kube-prometheus-stack";
    public String namespace = "monitoring";
    public String repoId;
    public String chart = "kube-prometheus-stack";
    public String version;
}
