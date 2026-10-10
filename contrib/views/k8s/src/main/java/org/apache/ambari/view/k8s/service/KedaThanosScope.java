/*
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

package org.apache.ambari.view.k8s.service;

import java.util.List;
import java.util.Map;

/**
 * How KEDA autoscaling triggers read OpenShift's built-in monitoring.
 *
 * <p>The Thanos querier serves two ports. The cluster port ({@value #CLUSTER_PORT}) answers any query, so its
 * token must be bound to the {@code cluster-monitoring-view} ClusterRole, a cluster right. The tenancy port
 * ({@value #TENANCY_PORT}) answers only for the namespace passed with each query, and authorizes the token with a
 * "get pods" check in that namespace, which a Role inside the project grants. KDPS uses the cluster port when the
 * connected account may create ClusterRoleBindings and the tenancy port otherwise; the latter is the setup Red Hat
 * documents for the Custom Metrics Autoscaler.
 */
public final class KedaThanosScope {

    /** Thanos querier port answering any query (needs cluster-monitoring-view). */
    public static final String CLUSTER_PORT = ":9091";
    /** Thanos querier port answering per namespace (needs "get pods" in that namespace). */
    public static final String TENANCY_PORT = ":9092";

    private KedaThanosScope() {
    }

    /**
     * Points the chart's OpenShift KEDA triggers ({@code server.keda.triggers}) at the tenancy port for
     * {@code namespace}: each trigger whose {@code metadata.serverAddress} is the Thanos querier's cluster port gets
     * the tenancy port and {@code metadata.namespace}. Other triggers are left alone.
     *
     * @param values    chart values, modified in place
     * @param namespace the release namespace the queries are restricted to
     * @return how many triggers were changed
     */
    @SuppressWarnings("unchecked")
    public static int scopeTriggersToNamespace(Map<String, Object> values, String namespace) {
        if (values == null || namespace == null || namespace.isBlank()) return 0;
        Object server = values.get("server");
        Object keda = server instanceof Map<?, ?> s ? s.get("keda") : null;
        Object triggers = keda instanceof Map<?, ?> k ? k.get("triggers") : null;
        if (!(triggers instanceof List<?> list)) return 0;
        int changed = 0;
        for (Object t : list) {
            if (!(t instanceof Map<?, ?> trigger) || !(trigger.get("metadata") instanceof Map<?, ?> m)) continue;
            Map<String, Object> metadata = (Map<String, Object>) m;
            Object address = metadata.get("serverAddress");
            if (address instanceof String a && a.contains("thanos-querier") && a.contains(CLUSTER_PORT)) {
                metadata.put("serverAddress", a.replace(CLUSTER_PORT, TENANCY_PORT));
                metadata.put("namespace", namespace);
                changed++;
            }
        }
        return changed;
    }

    /** Whether the chart values turn worker autoscaling on ({@code server.keda.enabled}). */
    public static boolean kedaEnabled(Map<String, Object> values) {
        Object server = values == null ? null : values.get("server");
        Object keda = server instanceof Map<?, ?> s ? s.get("keda") : null;
        return keda instanceof Map<?, ?> k && Boolean.parseBoolean(String.valueOf(k.get("enabled")));
    }

    /**
     * Whether the chart values turn on the KEDA TriggerAuthentication (bearer token read from a Secret), i.e. the
     * triggers read OpenShift monitoring and need a monitoring token.
     */
    public static boolean triggerAuthenticationEnabled(Map<String, Object> values) {
        Object server = values == null ? null : values.get("server");
        Object keda = server instanceof Map<?, ?> s ? s.get("keda") : null;
        Object auth = keda instanceof Map<?, ?> k ? k.get("triggerAuthentication") : null;
        return auth instanceof Map<?, ?> a && Boolean.parseBoolean(String.valueOf(a.get("enabled")));
    }

    /**
     * The Prometheus address the chart's KEDA triggers query ({@code server.keda.triggers[].metadata.serverAddress}).
     *
     * @return the first trigger's address, or {@code null} when there is none
     */
    public static String firstTriggerAddress(Map<String, Object> values) {
        Object server = values == null ? null : values.get("server");
        Object keda = server instanceof Map<?, ?> s ? s.get("keda") : null;
        Object triggers = keda instanceof Map<?, ?> k ? k.get("triggers") : null;
        if (!(triggers instanceof List<?> list)) return null;
        for (Object t : list) {
            if (t instanceof Map<?, ?> trigger && trigger.get("metadata") instanceof Map<?, ?> m
                    && m.get("serverAddress") instanceof String address && !address.isBlank()) {
                return address;
            }
        }
        return null;
    }

    /**
     * Why these values cannot autoscale, or {@code null}: the autoscaler reads the metrics the ServiceMonitors make
     * Prometheus collect, so turning them off (explicitly) while autoscaling is on would leave it blind.
     */
    public static String autoscalingWithoutMetrics(Map<String, Object> values) {
        Object sm = values == null ? null : values.get("serviceMonitor");
        boolean metricsOff = sm instanceof Map<?, ?> m && m.containsKey("enabled") && !serviceMonitorsEnabled(values);
        if (kedaEnabled(values) && metricsOff) {
            return "Worker autoscaling needs the service's metrics, but metrics collection (ServiceMonitor) is off. "
                    + "Turn metrics collection on, or turn worker autoscaling off.";
        }
        return null;
    }

    /**
     * Whether the chart values create Prometheus ServiceMonitors: {@code serviceMonitor.enabled}, overridden per role
     * by {@code serviceMonitor.coordinator.enabled} / {@code serviceMonitor.worker.enabled} (the chart merges the role
     * settings over the top-level ones).
     */
    public static boolean serviceMonitorsEnabled(Map<String, Object> values) {
        Object sm = values == null ? null : values.get("serviceMonitor");
        if (!(sm instanceof Map<?, ?> m)) return false;
        boolean top = Boolean.parseBoolean(String.valueOf(m.get("enabled")));
        for (String role : new String[]{"coordinator", "worker"}) {
            Object r = m.get(role);
            boolean roleOn = r instanceof Map<?, ?> rm && rm.containsKey("enabled")
                    ? Boolean.parseBoolean(String.valueOf(rm.get("enabled"))) : top;
            if (roleOn) return true;
        }
        return false;
    }
}
