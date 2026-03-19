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
 * Represents cluster statistics and resource usage information
 */
public class ClusterStats {

    @JsonProperty("cpu")
    private ResourceStat cpu;
    
    @JsonProperty("memory")
    private ResourceStat memory;
    
    @JsonProperty("pods")
    private ResourceStat pods;
    
    @JsonProperty("nodes")
    private ResourceStat nodes;
    
    @JsonProperty("helm")
    private HelmStat helm;

    @JsonProperty("source")
    private String source; // metrics source identifier (prometheus, metrics-server, unknown)

    // Default constructor for JSON deserialization
    public ClusterStats() {
    }

    public ClusterStats(ResourceStat cpu, ResourceStat memory, ResourceStat pods, ResourceStat nodes, HelmStat helm) {
        this.cpu = cpu;
        this.memory = memory;
        this.pods = pods;
        this.nodes = nodes;
        this.helm = helm;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public static class ResourceStat {
        @JsonProperty("used")
        private double used;
        
        @JsonProperty("total")
        private double total;

        public ResourceStat() {
        }

        public ResourceStat(double used, double total) {
            this.used = used;
            this.total = total;
        }

        public double getUsed() { return used; }
        public double getTotal() { return total; }
    }

    public static class HelmStat {
        @JsonProperty("deployed")
        private int deployed;
        
        @JsonProperty("pending")
        private int pending;
        
        @JsonProperty("failed")
        private int failed;
        
        @JsonProperty("total")
        private int total;

        public HelmStat() {
        }

        public HelmStat(int deployed, int pending, int failed, int total) {
            this.deployed = deployed;
            this.pending = pending;
            this.failed = failed;
            this.total = total;
        }

        public int getDeployed() { return deployed; }
        public int getPending() { return pending; }
        public int getFailed() { return failed; }
        public int getTotal() { return total; }
    }
    
    // Getters for each stat
    public ResourceStat getCpu() { return cpu; }
    public ResourceStat getMemory() { return memory; }
    public ResourceStat getPods() { return pods; }
    public ResourceStat getNodes() { return nodes; }
    public HelmStat getHelm() { return helm; }
    public String getSource() { return source; }
}
