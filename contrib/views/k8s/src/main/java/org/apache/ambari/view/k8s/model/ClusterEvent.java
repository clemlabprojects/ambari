package org.apache.ambari.view.k8s.model;

public class ClusterEvent {
    private String id;
    private String type;
    private String message;
    private String timestamp;

    public ClusterEvent(String id, String type, String message, String timestamp) {
        this.id = id;
        this.type = type;
        this.message = message;
        this.timestamp = timestamp;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public String getTimestamp() {
        return timestamp;
    }
}