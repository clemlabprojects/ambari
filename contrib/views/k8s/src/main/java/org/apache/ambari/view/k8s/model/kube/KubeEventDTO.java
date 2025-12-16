package org.apache.ambari.view.k8s.model.kube;

/**
 * Lightweight event representation for UI consumption.
 */
public class KubeEventDTO {
    public String reason;
    public String message;
    public String type;
    public String lastTimestamp;
    public String involvedKind;
    public String involvedName;
}
