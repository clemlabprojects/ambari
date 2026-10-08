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

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CPU and memory used by the projects an account may use, for accounts that cannot read cluster-wide usage.
 *
 * <p>Usage comes from the pod metrics API ({@code metrics.k8s.io}, what {@code oc adm top pods} shows), which any
 * account with a role in a project may read for that project. Only projects whose metrics could be read count, for
 * the usage and for what it is measured against.
 *
 * <p>Each resource (CPU, memory, pods) is measured against the projects' ResourceQuotas when every measured project
 * limits that resource with the same kind of quota (all {@code limits.*}, or all {@code requests.*}); otherwise CPU
 * and memory are measured against what the running pods of those projects request, and pods against nothing.
 * Pods that request nothing (best-effort) count in the usage but add nothing to the requests.
 *
 * <p>Approximations: the pod count is of running pods while a {@code pods} quota also counts pending ones; and a
 * quota restricted to some pods ({@code scopes} or {@code scopeSelector}, e.g. BestEffort or a priority class) is
 * treated as covering the whole project. Project templates rarely use either.
 */
public final class ProjectUsage {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectUsage.class);
    private static final double GIB = 1024.0 * 1024.0 * 1024.0;

    /** What one resource is measured against. */
    public enum Basis {
        /** The projects' ResourceQuotas. */
        QUOTA("quota"),
        /** The resources requested by the running pods. */
        REQUESTS("requests"),
        /** Nothing to measure against. */
        NONE("none");

        private final String label;

        Basis(String label) {
            this.label = label;
        }

        /** Name used in the dashboard response. */
        public String label() {
            return label;
        }
    }

    /** Used and total of one resource, and what the total is. {@code total} is 0 for {@link Basis#NONE}. */
    public record Figure(double used, double total, Basis basis) {
    }

    /** The measured usage: CPU in cores, memory in GiB, pods (used = running pods of the measured projects). */
    public record Result(Figure cpu, Figure memory, Figure pods, int projectsMeasured) {
    }

    private ProjectUsage() {
    }

    /**
     * Measures the usage of {@code namespaces}.
     *
     * @param client      the view's Kubernetes client
     * @param namespaces  the projects the account may use
     * @param runningPods the running pods of those projects
     * @return the usage, or {@code null} when no project's metrics could be read
     */
    public static Result measure(KubernetesClient client, Collection<String> namespaces, List<Pod> runningPods) {
        Objects.requireNonNull(client, "client");
        double cpuUsed = 0;
        double memUsed = 0;
        java.util.Set<String> measured = new java.util.TreeSet<>();
        Map<String, Map<String, Double>> quotaByNamespace = new java.util.HashMap<>();
        for (String ns : namespaces) {
            List<PodMetrics> metrics;
            try {
                metrics = client.top().pods().metrics(ns).getItems();
            } catch (RuntimeException e) {
                LOG.debug("Pod metrics of namespace {} not readable: {}", ns, e.getMessage());
                continue;
            }
            for (PodMetrics pm : metrics) {
                if (pm.getContainers() == null) continue;
                for (ContainerMetrics cm : pm.getContainers()) {
                    cpuUsed += amount(cm.getUsage(), "cpu");
                    memUsed += amount(cm.getUsage(), "memory");
                }
            }
            measured.add(ns);
            quotaByNamespace.put(ns, hardLimits(client, ns));
        }
        if (measured.isEmpty()) {
            return null;
        }
        List<Pod> measuredPods = runningPods.stream()
                .filter(p -> p.getMetadata() != null && measured.contains(p.getMetadata().getNamespace()))
                .collect(java.util.stream.Collectors.toList());

        Figure cpu = figure(cpuUsed, quotaTotal(quotaByNamespace, "cpu"), requested(measuredPods, "cpu"), 1);
        Figure memory = figure(memUsed / GIB, quotaTotal(quotaByNamespace, "memory"), requested(measuredPods, "memory"), GIB);
        Double podQuota = sumIfEveryone(quotaByNamespace, "pods");
        Figure pods = podQuota != null
                ? new Figure(measuredPods.size(), podQuota, Basis.QUOTA)
                : new Figure(measuredPods.size(), 0, Basis.NONE);
        return new Result(cpu, memory, pods, measured.size());
    }

    private static Figure figure(double used, Double quota, double requested, double unit) {
        if (quota != null) {
            return new Figure(used, quota / unit, Basis.QUOTA);
        }
        if (requested > 0) {
            return new Figure(used, requested / unit, Basis.REQUESTS);
        }
        return new Figure(used, 0, Basis.NONE);
    }

    /**
     * Sum of one resource's quota over every measured namespace, or {@code null} when some namespace does not limit
     * it with the same kind of quota as the others. {@code limits.*} is preferred; {@code requests.*} (and the bare
     * name, which Kubernetes treats as requests) is used when every namespace has it.
     */
    private static Double quotaTotal(Map<String, Map<String, Double>> quotaByNamespace, String resource) {
        Double limits = sumIfEveryone(quotaByNamespace, "limits." + resource);
        if (limits != null) {
            return limits;
        }
        double sum = 0;
        for (Map<String, Double> hard : quotaByNamespace.values()) {
            Double v = hard.containsKey("requests." + resource) ? hard.get("requests." + resource) : hard.get(resource);
            if (v == null) return null;
            sum += v;
        }
        return quotaByNamespace.isEmpty() ? null : sum;
    }

    private static Double sumIfEveryone(Map<String, Map<String, Double>> quotaByNamespace, String key) {
        double sum = 0;
        for (Map<String, Double> hard : quotaByNamespace.values()) {
            Double v = hard.get(key);
            if (v == null) return null;
            sum += v;
        }
        return quotaByNamespace.isEmpty() ? null : sum;
    }

    private static double requested(List<Pod> pods, String resource) {
        double sum = 0;
        for (Pod pod : pods) {
            if (pod.getSpec() == null || pod.getSpec().getContainers() == null) continue;
            for (Container c : pod.getSpec().getContainers()) {
                if (c.getResources() != null) sum += amount(c.getResources().getRequests(), resource);
            }
        }
        return sum;
    }

    /**
     * The hard limits of a namespace's quotas, in base units (cores, bytes, counts), keyed by quota name such as
     * {@code limits.cpu}. With several quotas the strictest value of each key applies. Empty when the namespace has
     * no quota or its quotas cannot be read.
     */
    private static Map<String, Double> hardLimits(KubernetesClient client, String ns) {
        Map<String, Double> hard = new java.util.HashMap<>();
        List<ResourceQuota> quotas;
        try {
            quotas = client.resourceQuotas().inNamespace(ns).list().getItems();
        } catch (RuntimeException e) {
            LOG.debug("Quotas of namespace {} not readable: {}", ns, e.getMessage());
            return hard;
        }
        for (ResourceQuota q : quotas == null ? List.<ResourceQuota>of() : quotas) {
            if (q.getSpec() == null || q.getSpec().getHard() == null) continue;
            q.getSpec().getHard().forEach((k, v) -> {
                if (v != null) hard.merge(k, v.getNumericalAmount().doubleValue(), Math::min);
            });
        }
        return hard;
    }

    private static double amount(Map<String, Quantity> values, String key) {
        if (values == null) return 0;
        Quantity q = values.get(key);
        return q == null ? 0 : q.getNumericalAmount().doubleValue();
    }
}
