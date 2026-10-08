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
 * account with a role in a project may read for that project. It is measured against what the projects are
 * allotted: their ResourceQuotas when every project has one, otherwise the resources their running pods request.
 */
public final class ProjectUsage {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectUsage.class);
    private static final double GIB = 1024.0 * 1024.0 * 1024.0;

    /** What the usage is measured against. */
    public enum Basis {
        /** The projects' ResourceQuotas (limits preferred over requests). */
        QUOTA("quota"),
        /** The resources requested by the running pods (some project has no quota). */
        REQUESTS("requests"),
        /** Nothing to measure against: no quota and no requests. */
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

    /**
     * The measured usage. {@code cpuTotal}, {@code memoryTotalGiB} and {@code podsTotal} are 0 when the basis is
     * {@link Basis#NONE}; {@code podsTotal} is 0 when no quota limits the number of pods.
     */
    public record Result(double cpuUsedCores, double memoryUsedGiB, double cpuTotalCores, double memoryTotalGiB,
                         int podsTotal, Basis basis, int projectsMeasured) {
    }

    private ProjectUsage() {
    }

    /**
     * Measures the usage of {@code namespaces}. A project whose metrics the account may not read is left out of the
     * usage; when none can be read the result is {@code null}.
     *
     * @param client      the view's Kubernetes client
     * @param namespaces  the projects the account may use
     * @param runningPods the running pods of those projects (for the requests basis)
     * @return the usage, or {@code null} when no project's metrics could be read
     */
    public static Result measure(KubernetesClient client, Collection<String> namespaces, List<Pod> runningPods) {
        Objects.requireNonNull(client, "client");
        double cpuUsed = 0;
        double memUsed = 0;
        int measured = 0;
        double quotaCpu = 0;
        double quotaMem = 0;
        int quotaPods = 0;
        boolean everyProjectHasQuota = !namespaces.isEmpty();
        for (String ns : namespaces) {
            try {
                for (PodMetrics pm : client.top().pods().metrics(ns).getItems()) {
                    if (pm.getContainers() == null) continue;
                    for (ContainerMetrics cm : pm.getContainers()) {
                        cpuUsed += amount(cm.getUsage(), "cpu");
                        memUsed += amount(cm.getUsage(), "memory") / GIB;
                    }
                }
                measured++;
            } catch (RuntimeException e) {
                LOG.debug("Pod metrics of namespace {} not readable: {}", ns, e.getMessage());
            }
            double[] quota = quotaOf(client, ns);
            if (quota == null) {
                everyProjectHasQuota = false;
            } else {
                quotaCpu += quota[0];
                quotaMem += quota[1];
                quotaPods += (int) quota[2];
            }
        }
        if (measured == 0) {
            return null;
        }
        if (everyProjectHasQuota && (quotaCpu > 0 || quotaMem > 0)) {
            return new Result(cpuUsed, memUsed, quotaCpu, quotaMem, quotaPods, Basis.QUOTA, measured);
        }
        double reqCpu = 0;
        double reqMem = 0;
        for (Pod pod : runningPods) {
            if (pod.getSpec() == null || pod.getSpec().getContainers() == null) continue;
            for (Container c : pod.getSpec().getContainers()) {
                if (c.getResources() == null) continue;
                reqCpu += amount(c.getResources().getRequests(), "cpu");
                reqMem += amount(c.getResources().getRequests(), "memory") / GIB;
            }
        }
        Basis basis = reqCpu > 0 || reqMem > 0 ? Basis.REQUESTS : Basis.NONE;
        return new Result(cpuUsed, memUsed, reqCpu, reqMem, 0, basis, measured);
    }

    /**
     * The quota of one namespace as {cpu cores, memory GiB, pods}, or {@code null} when it has none or it cannot be
     * read. With several quotas, the most restrictive value of each resource applies.
     */
    private static double[] quotaOf(KubernetesClient client, String ns) {
        List<ResourceQuota> quotas;
        try {
            quotas = client.resourceQuotas().inNamespace(ns).list().getItems();
        } catch (RuntimeException e) {
            LOG.debug("Quotas of namespace {} not readable: {}", ns, e.getMessage());
            return null;
        }
        if (quotas == null || quotas.isEmpty()) {
            return null;
        }
        double cpu = Double.MAX_VALUE;
        double mem = Double.MAX_VALUE;
        double pods = Double.MAX_VALUE;
        for (ResourceQuota q : quotas) {
            Map<String, Quantity> hard = q.getSpec() == null ? null : q.getSpec().getHard();
            if (hard == null) continue;
            cpu = Math.min(cpu, first(hard, "limits.cpu", "requests.cpu", "cpu"));
            mem = Math.min(mem, first(hard, "limits.memory", "requests.memory", "memory") / GIB);
            pods = Math.min(pods, first(hard, "pods"));
        }
        return new double[]{cpu == Double.MAX_VALUE ? 0 : cpu, mem == Double.MAX_VALUE ? 0 : mem,
                pods == Double.MAX_VALUE ? 0 : pods};
    }

    /** The first of {@code keys} present in {@code values}, or {@link Double#MAX_VALUE} (no limit). */
    private static double first(Map<String, Quantity> values, String... keys) {
        for (String k : keys) {
            Quantity q = values.get(k);
            if (q != null) {
                return q.getNumericalAmount().doubleValue();
            }
        }
        return Double.MAX_VALUE;
    }

    private static double amount(Map<String, Quantity> values, String key) {
        if (values == null) return 0;
        Quantity q = values.get(key);
        return q == null ? 0 : q.getNumericalAmount().doubleValue();
    }
}
