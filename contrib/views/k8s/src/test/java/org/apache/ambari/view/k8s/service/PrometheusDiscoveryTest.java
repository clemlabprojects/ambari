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
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Prometheus installed under any name or namespace is found from its {@code Prometheus} resource, with the address
 * and ServiceMonitor labels a deploy needs.
 */
class PrometheusDiscoveryTest {

    private static GenericKubernetesResource prometheus(String namespace, String name, Map<String, String> labels,
                                                        Map<String, Object> spec) {
        GenericKubernetesResource r = new GenericKubernetesResource();
        r.setMetadata(new ObjectMetaBuilder().withNamespace(namespace).withName(name).withLabels(labels).build());
        r.setAdditionalProperty("spec", spec);
        return r;
    }

    /** What kube-prometheus-stack creates for release "obs" in namespace "observability". */
    private static GenericKubernetesResource kubePrometheusStack() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("serviceMonitorSelector", Map.of("matchLabels", Map.of("release", "obs")));
        spec.put("serviceMonitorNamespaceSelector", Map.of());
        return prometheus("observability", "obs-kube-prometheus-prometheus", Map.of("release", "obs"), spec);
    }

    @Test
    void aKubePrometheusStackUnderAnotherNameIsFound() {
        PrometheusDiscovery.Instance i = PrometheusDiscovery.fromResource(kubePrometheusStack()).orElseThrow();

        assertEquals("observability", i.namespace());
        assertEquals("obs", i.release());
        assertEquals("http://prometheus-operated.observability.svc:9090", i.url());
        assertEquals(Map.of("release", "obs"), i.serviceMonitorLabels());
        assertNull(i.scrapeWarning());
    }

    @Test
    void otherSelectorLabelsAndARoutePrefixAreKept() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("serviceMonitorSelector", Map.of("matchLabels", Map.of("team", "data", "prometheus", "main")));
        spec.put("serviceMonitorNamespaceSelector", Map.of());
        spec.put("routePrefix", "prom");
        PrometheusDiscovery.Instance i = PrometheusDiscovery.fromResource(
                prometheus("mon", "main", Map.of("app.kubernetes.io/instance", "platform"), spec)).orElseThrow();

        assertEquals(Map.of("team", "data", "prometheus", "main"), i.serviceMonitorLabels());
        assertEquals("platform", i.release(), "no release label in the selector: the resource's instance label");
        assertEquals("http://prometheus-operated.mon.svc:9090/prom", i.url());
    }

    @Test
    void anInstanceThatCannotScrapeANewServiceSaysWhy() {
        PrometheusDiscovery.Instance noSelector = PrometheusDiscovery.fromResource(
                prometheus("mon", "bare", null, Map.of())).orElseThrow();
        assertFalse(noSelector.selectsServiceMonitors());
        assertTrue(noSelector.scrapeWarning().contains("scrapes no ServiceMonitor"));
        assertEquals("bare", noSelector.release());

        PrometheusDiscovery.Instance ownNamespace = PrometheusDiscovery.fromResource(prometheus("mon", "local", null,
                Map.of("serviceMonitorSelector", Map.of())))
                .orElseThrow();
        assertTrue(ownNamespace.serviceMonitorLabels().isEmpty(), "an empty selector selects every ServiceMonitor");
        assertTrue(ownNamespace.scrapeWarning().contains("only reads ServiceMonitors in namespace mon"));
    }

    @Test
    void theConfiguredNamespaceWinsThenAnInstanceThatScrapesEverywhere() {
        PrometheusDiscovery.Instance local = PrometheusDiscovery.fromResource(prometheus("a-mon", "local", null,
                Map.of("serviceMonitorSelector", Map.of()))).orElseThrow();
        PrometheusDiscovery.Instance shared = PrometheusDiscovery.fromResource(kubePrometheusStack()).orElseThrow();

        assertEquals("observability", PrometheusDiscovery.pick(List.of(local, shared), "monitoring").orElseThrow().namespace(),
                "the one that scrapes every namespace, even though a-mon sorts first");
        assertEquals("a-mon", PrometheusDiscovery.pick(List.of(shared, local), "a-mon").orElseThrow().namespace());
        assertTrue(PrometheusDiscovery.pick(List.of(), "monitoring").isEmpty());
    }

    @Test
    void aResourceWithoutNamespaceIsIgnored() {
        GenericKubernetesResource r = new GenericKubernetesResource();
        r.setMetadata(new ObjectMetaBuilder().withName("x").build());
        assertTrue(PrometheusDiscovery.fromResource(r).isEmpty());
        assertTrue(PrometheusDiscovery.fromResource(null).isEmpty());
    }

    private static Service service(String name, String clusterIp, Map<String, String> selector, String portName, int port) {
        return new ServiceBuilder().withNewMetadata().withName(name).endMetadata()
                .withNewSpec().withClusterIP(clusterIp).withSelector(selector)
                .addNewPort().withName(portName).withPort(port).endPort().endSpec().build();
    }

    @Test
    void theInstanceIsQueriedThroughItsOwnService() {
        GenericKubernetesResource r = kubePrometheusStack();
        PrometheusDiscovery.Instance i = PrometheusDiscovery.fromResource(r).orElseThrow();
        List<Service> services = List.of(
                service("prometheus-operated", "None", Map.of("app.kubernetes.io/name", "prometheus"), "web", 9090),
                service("other-prometheus", "10.0.0.2", Map.of("operator.prometheus.io/name", "other"), "web", 9090),
                service("obs-kube-prometheus-prometheus", "10.0.0.1",
                        Map.of("operator.prometheus.io/name", "obs-kube-prometheus-prometheus"), "http-web", 9090));

        assertEquals("http://obs-kube-prometheus-prometheus.observability.svc:9090",
                PrometheusDiscovery.withOwnService(i, r, services).url());
        assertEquals(i.url(), PrometheusDiscovery.withOwnService(i, r, List.of()).url(),
                "no Service of its own: prometheus-operated");
        assertEquals("http://obs-svc.observability.svc:9090", PrometheusDiscovery.withOwnService(i, r, List.of(
                service("obs-svc", "10.0.0.4", Map.of("app.kubernetes.io/name", "prometheus",
                        "app.kubernetes.io/instance", "obs-kube-prometheus-prometheus"), "web", 9090))).url(),
                "pods labelled by instance name, as newer operators do");
    }

    @Test
    void tlsMeansHttps() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("serviceMonitorSelector", Map.of());
        spec.put("serviceMonitorNamespaceSelector", Map.of());
        spec.put("web", Map.of("tlsConfig", Map.of("keySecret", Map.of("name", "tls"))));
        GenericKubernetesResource r = prometheus("mon", "secure", null, spec);
        PrometheusDiscovery.Instance i = PrometheusDiscovery.fromResource(r).orElseThrow();

        assertEquals("https://prometheus-operated.mon.svc:9090", i.url());
        assertEquals("https://secure-svc.mon.svc:8443", PrometheusDiscovery.withOwnService(i, r,
                List.of(service("secure-svc", "10.0.0.3", Map.of("operator.prometheus.io/name", "secure"), "web", 8443))).url());
    }

    @Test
    void namespaceSelectorsAndExpressionsAreWarnedAbout() {
        Map<String, Object> labelled = new LinkedHashMap<>();
        labelled.put("serviceMonitorSelector", Map.of("matchLabels", Map.of("release", "x")));
        labelled.put("serviceMonitorNamespaceSelector", Map.of("matchLabels", Map.of("monitoring", "on")));
        assertTrue(PrometheusDiscovery.fromResource(prometheus("mon", "a", null, labelled)).orElseThrow()
                .scrapeWarning().contains("serviceMonitorNamespaceSelector"));

        Map<String, Object> expressions = new LinkedHashMap<>();
        expressions.put("serviceMonitorSelector", Map.of("matchExpressions",
                List.of(Map.of("key", "team", "operator", "Exists"))));
        expressions.put("serviceMonitorNamespaceSelector", Map.of());
        assertTrue(PrometheusDiscovery.fromResource(prometheus("mon", "b", null, expressions)).orElseThrow()
                .scrapeWarning().contains("match expressions"));
    }

    @Test
    void theStackIsReusedOnlyWhenTheDeployQueriesARunningPrometheus() {
        GenericKubernetesResource r = kubePrometheusStack();
        PrometheusDiscovery.Instance i = PrometheusDiscovery.withOwnService(
                PrometheusDiscovery.fromResource(r).orElseThrow(), r,
                List.of(service("obs-kube-prometheus-prometheus", "10.0.0.1",
                        Map.of("operator.prometheus.io/name", "obs-kube-prometheus-prometheus"), "http-web", 9090)));
        List<PrometheusDiscovery.Instance> running = List.of(i);

        assertTrue(PrometheusDiscovery.reusableFor(running,
                "http://obs-kube-prometheus-prometheus.observability.svc.cluster.local:9090").isPresent());
        assertTrue(PrometheusDiscovery.reusableFor(running,
                "http://kube-prometheus-stack-prometheus.monitoring.svc:9090").isEmpty(),
                "still pointed at the default address: install the stack, as before");
        assertTrue(PrometheusDiscovery.reusableFor(running, null).isPresent(), "no autoscaler: any healthy instance");

        PrometheusDiscovery.Instance ownNamespaceOnly = PrometheusDiscovery.fromResource(prometheus("mon", "local", null,
                Map.of("serviceMonitorSelector", Map.of()))).orElseThrow();
        assertTrue(PrometheusDiscovery.reusableFor(List.of(ownNamespaceOnly), "").isEmpty());
    }

    @Test
    void addressesCompareOnServiceNamespaceAndPort() {
        assertEquals("p.mon:9090", PrometheusDiscovery.hostPort("http://p.mon.svc.cluster.local:9090/prefix"));
        assertEquals("p.mon:443", PrometheusDiscovery.hostPort("https://p.mon.svc"));
        assertNull(PrometheusDiscovery.hostPort("not a url"));
        assertNull(PrometheusDiscovery.hostPort(""));
    }
}
