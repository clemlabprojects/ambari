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