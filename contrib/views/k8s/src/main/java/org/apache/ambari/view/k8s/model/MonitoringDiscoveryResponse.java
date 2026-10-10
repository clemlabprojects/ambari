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

/**
 * DTO for monitoring discovery response.
 */
public class MonitoringDiscoveryResponse {
    public String namespace;
    public String release;
    public String url;
    public String state;
    public String message;
    /** Labels a ServiceMonitor must carry for the discovered Prometheus to scrape it. */
    public java.util.Map<String, String> serviceMonitorLabels;
    /** In-cluster address a deployed autoscaler can query, or {@code null} when only an external one is known. */
    public String queryUrl;
    /** Why the discovered Prometheus may not scrape a new service, or {@code null}. */
    public String warning;

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
