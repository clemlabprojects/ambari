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
import java.util.Map;

/**
 * Represents the status of a command execution in the Kubernetes cluster
 */
public class CommandStatus {
    
    @JsonProperty("id")
    public String id;
    @JsonProperty("hasChildren")
    public boolean hasChildren;
    
    @JsonProperty("type")
    public CommandType type;
    
    @JsonProperty("state")
    public CommandState state;
    
    @JsonProperty("percent")
    public int percent;           // Progress percentage (0-100)
    
    @JsonProperty("step")
    public int step;              // Current execution step (0-N)
    
    @JsonProperty("message")
    public String message;        // Status message like "Validating...", "Syncing repository...", etc.

    @JsonProperty("createdBy")
    public String createdBy;      // Ambari user that initiated the command

    @JsonProperty("createdAt")
    public String createdAt;      // ISO-8601 timestamp

    @JsonProperty("updatedAt")
    public String updatedAt;      // ISO-8601 timestamp
    
    @JsonProperty("error")
    public String error;          // Error message or stack trace
    
    @JsonProperty("result")
    public Map<String,Object> result; // Command result data (e.g., release DTO)

    public static CommandStatus pending(String id, CommandType t, String now) {
        CommandStatus s = new CommandStatus();
        s.id = id;
        s.type = t;
        s.state = CommandState.PENDING;
        s.percent = 0;
        s.step = 0;
        s.message = "Queued...";
        s.createdAt = now;
        s.updatedAt = now;
        return s;
    }
}
