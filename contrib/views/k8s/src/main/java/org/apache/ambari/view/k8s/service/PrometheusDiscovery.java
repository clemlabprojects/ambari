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

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the Prometheus a service should report to among the {@code Prometheus} resources of the Prometheus
 * operator (monitoring.coreos.com/v1), whatever namespace or release name it was installed under.
 *
 * <p>From the resource it derives what a deploy needs: the query address (the operator's
 * {@code prometheus-operated} Service, which exists for every instance), the labels a ServiceMonitor must carry to
 * be picked up ({@code spec.serviceMonitorSelector.matchLabels}), and whether ServiceMonitors in other namespaces
 * are read at all.
 */
public final class PrometheusDiscovery {

    /** Port of the operator-created {@code prometheus-operated} Service ("web"). */
    static final int PROMETHEUS_PORT = 9090;

    private PrometheusDiscovery() {
    }

    /**
     * One Prometheus instance, as a deploy uses it.
     *
     * @param namespace             namespace of the Prometheus resource
     * @param name                  its name
     * @param release               value for the {@code release} label of ServiceMonitors: the selector's own
     *                              {@code release} label when it has one, else the resource's release/instance
     *                              label, else its name
     * @param url                   query address
     * @param serviceMonitorLabels  labels a ServiceMonitor must carry to be selected (empty when it selects all)
     * @param selectsServiceMonitors {@code false} when the instance has no ServiceMonitor selector (it reads none)
     * @param readsOtherNamespaces  {@code false} when it reads ServiceMonitors from its own namespace only
     */
    public record Instance(String namespace, String name, String release, String url,
                           Map<String, String> serviceMonitorLabels, boolean selectsServiceMonitors,
                           boolean readsOtherNamespaces) {

        /** Why a service deployed elsewhere would not be scraped by this instance, or {@code null}. */
        public String scrapeWarning() {
            if (!selectsServiceMonitors) {
                return "Prometheus " + namespace + "/" + name + " has no ServiceMonitor selector, so it scrapes no "
                        + "ServiceMonitor: the service's metrics will not reach it.";
            }
            if (!readsOtherNamespaces) {
                return "Prometheus " + namespace + "/" + name + " only reads ServiceMonitors in namespace " + namespace
                        + ": a service deployed in another namespace will not be scraped.";
            }
            return null;
        }
    }

    /**
     * Reads one {@code Prometheus} resource.
     *
     * @param resource the resource as listed from the cluster
     * @return the instance, or empty when it has no name or namespace
     */
    @SuppressWarnings("unchecked")
    public static Optional<Instance> fromResource(GenericKubernetesResource resource) {
        if (resource == null || resource.getMetadata() == null) {
            return Optional.empty();
        }
        String namespace = resource.getMetadata().getNamespace();
        String name = resource.getMetadata().getName();
        if (namespace == null || name == null) {
            return Optional.empty();
        }
        Map<String, Object> spec = resource.getAdditionalProperties() == null ? Map.of()
                : asMap(resource.getAdditionalProperties().get("spec"));

        Object selector = spec.get("serviceMonitorSelector");
        Map<String, String> labels = new LinkedHashMap<>();
        asMap(asMap(selector).get("matchLabels")).forEach((k, v) -> {
            if (k != null && v != null) {
                labels.put(k, String.valueOf(v));
            }
        });

        Map<String, String> ownLabels = resource.getMetadata().getLabels() == null ? Map.of()
                : resource.getMetadata().getLabels();
        String release = firstNonBlank(labels.get("release"), ownLabels.get("release"),
                ownLabels.get("app.kubernetes.io/instance"), name);

        String routePrefix = spec.get("routePrefix") == null ? "" : String.valueOf(spec.get("routePrefix")).trim();
        if (routePrefix.equals("/")) {
            routePrefix = "";
        } else if (!routePrefix.isEmpty() && !routePrefix.startsWith("/")) {
            routePrefix = "/" + routePrefix;
        }
        String url = "http://prometheus-operated." + namespace + ".svc:" + PROMETHEUS_PORT + routePrefix;

        return Optional.of(new Instance(namespace, name, release, url, labels, selector != null,
                spec.get("serviceMonitorNamespaceSelector") != null));
    }

    /**
     * Picks the instance a deploy should use: one in the preferred namespace first, then one that reads
     * ServiceMonitors from every namespace, then any, by namespace and name for a stable choice.
     *
     * @param instances          the instances found
     * @param preferredNamespace namespace configured for monitoring, may be {@code null}
     * @return the chosen instance, or empty when there is none
     */
    public static Optional<Instance> pick(List<Instance> instances, String preferredNamespace) {
        if (instances == null || instances.isEmpty()) {
            return Optional.empty();
        }
        Comparator<Instance> order = Comparator
                .comparing((Instance i) -> !i.namespace().equals(preferredNamespace))
                .thenComparing(i -> i.scrapeWarning() != null)
                .thenComparing(Instance::namespace)
                .thenComparing(Instance::name);
        return instances.stream().sorted(order).findFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
