/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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