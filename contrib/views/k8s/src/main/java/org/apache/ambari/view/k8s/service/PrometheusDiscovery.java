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
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the Prometheus a service should report to among the {@code Prometheus} resources of the Prometheus
 * operator (monitoring.coreos.com/v1), whatever namespace or release name it was installed under.
 *
 * <p>From the resource it derives what a deploy needs: the query address (the instance's own Service when one is
 * found, else the operator's {@code prometheus-operated} Service, which exists for every instance), the labels a
 * ServiceMonitor must carry to be picked up ({@code spec.serviceMonitorSelector.matchLabels}), and whether a service
 * deployed elsewhere would be scraped at all.
 */
public final class PrometheusDiscovery {

    /** Port of the operator-created {@code prometheus-operated} Service ("web"). */
    static final int PROMETHEUS_PORT = 9090;
    /** Selector label the operator puts on an instance's pods, which its Services select. */
    static final String INSTANCE_LABEL = "operator.prometheus.io/name";

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
     * @param selectorHasExpressions the ServiceMonitor selector also uses {@code matchExpressions}, which labels alone
     *                              may not satisfy
     * @param namespaceScope        which namespaces it reads ServiceMonitors from
     */
    public record Instance(String namespace, String name, String release, String url,
                           Map<String, String> serviceMonitorLabels, boolean selectsServiceMonitors,
                           boolean selectorHasExpressions, NamespaceReach namespaceScope) {

        /** Why a service deployed elsewhere might not be scraped by this instance, or {@code null}. */
        public String scrapeWarning() {
            String who = "Prometheus " + namespace + "/" + name;
            if (!selectsServiceMonitors) {
                return who + " has no ServiceMonitor selector, so it scrapes no ServiceMonitor: the service's metrics "
                        + "will not reach it.";
            }
            if (namespaceScope == NamespaceReach.OWN) {
                return who + " only reads ServiceMonitors in namespace " + namespace + ": a service deployed in another "
                        + "namespace will not be scraped.";
            }
            if (namespaceScope == NamespaceReach.SELECTED) {
                return who + " only reads ServiceMonitors from namespaces matching its serviceMonitorNamespaceSelector: "
                        + "label the service's namespace accordingly.";
            }
            if (selectorHasExpressions) {
                return who + " selects ServiceMonitors with match expressions too: check the ServiceMonitor labels "
                        + "satisfy them.";
            }
            return null;
        }

        /** The same instance queried at another address. */
        Instance withUrl(String newUrl) {
            return new Instance(namespace, name, release, newUrl, serviceMonitorLabels, selectsServiceMonitors,
                    selectorHasExpressions, namespaceScope);
        }
    }

    /** Which namespaces an instance reads ServiceMonitors from ({@code spec.serviceMonitorNamespaceSelector}). */
    public enum NamespaceReach {
        /** No namespace selector: its own namespace only. */
        OWN,
        /** An empty selector: every namespace. */
        ALL,
        /** A selector with labels or expressions: the namespaces it matches. */
        SELECTED
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

        String url = scheme(spec) + "://prometheus-operated." + namespace + ".svc:" + PROMETHEUS_PORT + routePrefix(spec);

        Object nsSelector = spec.get("serviceMonitorNamespaceSelector");
        NamespaceReach reach = nsSelector == null ? NamespaceReach.OWN
                : asMap(nsSelector).isEmpty() ? NamespaceReach.ALL : NamespaceReach.SELECTED;
        boolean expressions = asMap(selector).get("matchExpressions") instanceof List<?> l && !l.isEmpty();

        return Optional.of(new Instance(namespace, name, release, url, labels, selector != null, expressions, reach));
    }

    /**
     * Points an instance at its own ClusterIP Service when its namespace has one: {@code prometheus-operated} is
     * shared by every instance (and shard) of a namespace, so queries through it may reach another server.
     *
     * @param instance the instance, addressed through {@code prometheus-operated}
     * @param resource its {@code Prometheus} resource (for the scheme and route prefix)
     * @param services the Services of its namespace
     * @return the instance at its own Service's address, or unchanged when none selects it
     */
    public static Instance withOwnService(Instance instance, GenericKubernetesResource resource, List<Service> services) {
        if (services == null || resource == null) {
            return instance;
        }
        Map<String, Object> spec = resource.getAdditionalProperties() == null ? Map.of()
                : asMap(resource.getAdditionalProperties().get("spec"));
        return services.stream()
                .filter(s -> s.getSpec() != null && s.getMetadata() != null)
                .filter(s -> !"None".equals(s.getSpec().getClusterIP()))
                .filter(s -> selectsInstance(s.getSpec().getSelector(), instance.name()))
                .min(Comparator.comparing((Service s) -> s.getMetadata().getName()))
                .map(s -> instance.withUrl(scheme(spec) + "://" + s.getMetadata().getName() + "." + instance.namespace()
                        + ".svc:" + webPort(s) + routePrefix(spec)))
                .orElse(instance);
    }

    /** Whether a Service selector targets the pods of the named instance, across operator versions' pod labels. */
    private static boolean selectsInstance(Map<String, String> selector, String name) {
        if (selector == null) {
            return false;
        }
        return name.equals(selector.get(INSTANCE_LABEL))
                || name.equals(selector.get("prometheus"))
                || (name.equals(selector.get("app.kubernetes.io/instance"))
                    && "prometheus".equals(selector.get("app.kubernetes.io/name")));
    }

    /** The Service's Prometheus web port: the one named web/http-web, else 9090, else its first port. */
    private static int webPort(Service service) {
        List<ServicePort> ports = service.getSpec().getPorts();
        if (ports == null || ports.isEmpty()) {
            return PROMETHEUS_PORT;
        }
        return ports.stream()
                .filter(p -> "web".equals(p.getName()) || "http-web".equals(p.getName()))
                .findFirst()
                .or(() -> ports.stream().filter(p -> p.getPort() != null && p.getPort() == PROMETHEUS_PORT).findFirst())
                .orElse(ports.get(0))
                .getPort();
    }

    /** https when the instance serves its web endpoint with TLS ({@code spec.web.tlsConfig}). */
    private static String scheme(Map<String, Object> spec) {
        return asMap(asMap(spec.get("web")).get("tlsConfig")).isEmpty() ? "http" : "https";
    }

    private static String routePrefix(Map<String, Object> spec) {
        String prefix = spec.get("routePrefix") == null ? "" : String.valueOf(spec.get("routePrefix")).trim();
        if (prefix.equals("/")) {
            return "";
        }
        return prefix.isEmpty() || prefix.startsWith("/") ? prefix : "/" + prefix;
    }

    /**
     * The running Prometheus a deploy can use instead of installing kube-prometheus-stack: the one at the address the
     * deploy queries, or, when it queries none (no autoscaling), one that scrapes every namespace without caveat.
     *
     * @param instances      the instances found
     * @param deployAddress  the Prometheus address in the deploy's values, may be blank
     * @return the instance to reuse, or empty when the stack must be installed
     */
    public static Optional<Instance> reusableFor(List<Instance> instances, String deployAddress) {
        if (deployAddress != null && !deployAddress.isBlank()) {
            return servedAt(instances, deployAddress);
        }
        return pick(instances, null).filter(i -> i.scrapeWarning() == null);
    }

    /**
     * The instance a deploy queries at {@code address}, matched on its address (host and port, any route prefix).
     *
     * @param instances the instances found
     * @param address   the Prometheus address in the deploy's values
     * @return the instance served at that address, or empty
     */
    public static Optional<Instance> servedAt(List<Instance> instances, String address) {
        String target = hostPort(address);
        if (target == null || instances == null) {
            return Optional.empty();
        }
        return instances.stream().filter(i -> target.equals(hostPort(i.url()))).findFirst();
    }

    /** "host:port" of an http(s) URL, the host reduced to {@code <service>.<namespace>}; null when unparsable. */
    static String hostPort(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            if (uri.getHost() == null) {
                return null;
            }
            String[] parts = uri.getHost().split("\\.");
            String host = parts.length >= 2 ? parts[0] + "." + parts[1] : uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
            return host + ":" + port;
        } catch (IllegalArgumentException e) {
            return null;
        }
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
