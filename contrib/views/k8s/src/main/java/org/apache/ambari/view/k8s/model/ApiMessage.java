package org.apache.ambari.view.k8s.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Represents a standard API response message for the Kubernetes view.
 * This class is used to wrap simple text messages in API responses,
 * providing a consistent structure for client communication.
 * 
 * @author Ambari K8s View Team
 * @version 1.0
 */
public class ApiMessage {
    
    @JsonProperty("message")
    private String message;
    
    /**
     * Default constructor for JSON deserialization.
     */
    public ApiMessage() {
    }
    
    /**
     * Creates an ApiMessage with the specified message content.
     * 
     * @param message the message content to be included in the API response
     */
    public ApiMessage(String message) {
        this.message = message;
    }
    
    /**
     * Gets the message content.
     * 
     * @return the message string
     */
    public String getMessage() {
        return message;
    }
    
    /**
     * Sets the message content.
     * 
     * @param message the message string to set
     */
    public void setMessage(String message) {
        this.message = message;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ApiMessage that = (ApiMessage) obj;
        return Objects.equals(message, that.message);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(message);
    }
    
    @Override
    public String toString() {
        return String.format("ApiMessage{message='%s'}", message);
    }
}