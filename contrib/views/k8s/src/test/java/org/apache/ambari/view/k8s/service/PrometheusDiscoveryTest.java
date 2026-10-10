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
}
