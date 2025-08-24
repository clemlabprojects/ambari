package org.apache.ambari.view.k8s.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a cluster event from Kubernetes
 */
public class ClusterEvent {
    
    @JsonProperty("id")
    private String eventId;
    
    @JsonProperty("type")
    private String eventType;
    
    @JsonProperty("message") 
    private String eventMessage;
    
    @JsonProperty("timestamp")
    private String eventTimestamp;

    // Default constructor for Jackson
    public ClusterEvent() {
    }

    public ClusterEvent(String eventId, String eventType, String eventMessage, String eventTimestamp) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.eventMessage = eventMessage;
        this.eventTimestamp = eventTimestamp;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getEventMessage() {
        return eventMessage;
    }

    public String getEventTimestamp() {
        return eventTimestamp;
    }
    
    @Override
    public String toString() {
        return "ClusterEvent{" +
                "eventId='" + eventId + '\'' +
                ", eventType='" + eventType + '\'' +
                ", eventMessage='" + eventMessage + '\'' +
                ", eventTimestamp='" + eventTimestamp + '\'' +
                '}';
    }
}