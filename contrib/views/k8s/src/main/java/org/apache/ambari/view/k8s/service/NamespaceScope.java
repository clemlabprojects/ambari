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

import io.fabric8.kubernetes.api.model.APIGroup;
import io.fabric8.kubernetes.api.model.APIResourceList;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.apache.ambari.view.k8s.model.kube.KubeNamespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * What the connected account may list, and how to list across namespaces when it may not list the cluster.
 *
 * <p>On sites where the platform team hands out projects, the account KDPS connects with only holds rights inside
 * its own projects: every cluster-wide listing (namespaces, secrets for Helm, pods, events, CRDs) answers 403. Asking
 * for cluster-wide rights is not the answer: "list secrets" across the cluster would let the account read every
 * secret. Instead, each listing tries the cluster first and, when refused, walks the namespaces the account can see.
 *
 * <p>Where that namespace list comes from, in order:
 * <ol>
 *   <li>the cluster's namespaces, when the account may list them;</li>
 *   <li>on OpenShift, the Project API, which returns exactly the projects the caller may use (all of them for a
 *       cluster admin) and needs no cluster permission;</li>
 *   <li>otherwise, the namespaces KDPS can name without listing (supplied by the owner: the kubeconfig namespace and
 *       the namespaces of recorded releases).</li>
 * </ol>
 *
 * <p>A refusal is remembered for {@link #REFUSAL_TTL_MS} so a restricted account does not retry the cluster-wide call
 * on every page refresh, while a right granted later is picked up. Behaviour therefore follows the account's rights,
 * not the platform type: a cluster admin on OpenShift keeps the single cluster-wide call.
 *
 * <p>Thread-safe. One instance per Kubernetes connection; {@link #reset()} when the connection changes.
 */
public class NamespaceScope {

    private static final Logger LOG = LoggerFactory.getLogger(NamespaceScope.class);

    /** How long a refused cluster-wide listing is remembered before it is tried again. */
    static final long REFUSAL_TTL_MS = 10 * 60_000L;

    /** How long the namespace list used for walks is reused; each walk would otherwise re-list projects. */
    static final long WALK_NAMESPACES_TTL_MS = 30_000L;

    /** OpenShift Project API (cluster-scoped): lists only the projects the caller may see. */
    private static final ResourceDefinitionContext PROJECT_RDC = new ResourceDefinitionContext.Builder()
            .withGroup("project.openshift.io").withVersion("v1")
            .withKind("Project").withPlural("projects").withNamespaced(false).build();

    /** Listings whose cluster-wide refusal is remembered. One key per Kubernetes resource listed across namespaces. */
    public enum Resource {
        NAMESPACES("namespaces"),
        /** Includes Helm's release storage: Helm keeps each release in a Secret. */
        SECRETS("secrets"),
        PODS("pods"),
        EVENTS("events"),
        DEPLOYMENTS("deployments"),
        CONFIGMAPS("configmaps"),
        INGRESSES("ingresses"),
        ROUTES("routes"),
        SERVICES("services"),
        SECRET_STORES("secretstores");

        private final String apiName;

        Resource(String apiName) {
            this.apiName = apiName;
        }

        /** Resource name as the Kubernetes API spells it, for logs. */
        public String apiName() {
            return apiName;
        }
    }

    /**
     * Runs one Kubernetes API call. The owner supplies its retry policy (e.g. re-authenticate once on 401).
     */
    @FunctionalInterface
    public interface ApiCall {
        <T> T run(String operation, Supplier<T> call);
    }

    private final Supplier<KubernetesClient> client;
    private final BooleanSupplier openShift;
    private final Supplier<Collection<String>> knownNamespaces;
    private final ApiCall apiCall;
    private final LongSupplier clock;

    private final Map<Resource, Long> refusedAt = new ConcurrentHashMap<>();
    private final AtomicReference<CachedNamespaces> walkNamespaces = new AtomicReference<>(null);

    private static final class CachedNamespaces {
        final List<String> names;
        final long fetchedAt;

        CachedNamespaces(List<String> names, long fetchedAt) {
            this.names = names;
            this.fetchedAt = fetchedAt;
        }
    }

    /**
     * @param client          the current Kubernetes client (read on every call: the owner may rebuild it)
     * @param openShift       whether the cluster is OpenShift
     * @param knownNamespaces namespaces the owner can name without listing; used when nothing can be listed
     * @param apiCall         how to run an API call (retry policy)
     * @param clock           current time in epoch milliseconds
     */
    public NamespaceScope(Supplier<KubernetesClient> client, BooleanSupplier openShift,
                          Supplier<Collection<String>> knownNamespaces, ApiCall apiCall, LongSupplier clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.openShift = Objects.requireNonNull(openShift, "openShift");
        this.knownNamespaces = Objects.requireNonNull(knownNamespaces, "knownNamespaces");
        this.apiCall = Objects.requireNonNull(apiCall, "apiCall");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** {@link ApiCall} that runs the call once, without any retry. */
    public static <T> T runOnce(String operation, Supplier<T> call) {
        return call.get();
    }

    /**
     * True when a Kubernetes API call was refused for lack of rights (HTTP 403), anywhere in the cause chain.
     * Helm reports refusals as plain text and is recognised by {@link HelmService} instead.
     *
     * @param e any failure, possibly wrapped
     * @return {@code true} when some cause is a {@link KubernetesClientException} with code 403
     */
    public static boolean isForbidden(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof KubernetesClientException kce && kce.getCode() == 403) {
                return true;
            }
        }
        return false;
    }

    /** Forgets remembered refusals and cached namespaces. Call when the connection or its credentials change. */
    public void reset() {
        refusedAt.clear();
        walkNamespaces.set(null);
    }

    /** Forgets the cached namespace list, e.g. after creating a namespace. */
    public void invalidateNamespaces() {
        walkNamespaces.set(null);
    }

    /** Whether a cluster-wide listing of {@code resource} was refused within the last {@link #REFUSAL_TTL_MS}. */
    public boolean isRefused(Resource resource) {
        Long at = refusedAt.get(resource);
        if (at == null) {
            return false;
        }
        if (clock.getAsLong() - at > REFUSAL_TTL_MS) {
            refusedAt.remove(resource, at);
            return false;
        }
        return true;
    }

    /** Records that a cluster-wide listing of {@code resource} was refused; logged once per refusal window. */
    public void recordRefusal(Resource resource, Throwable cause) {
        if (refusedAt.put(resource, clock.getAsLong()) == null) {
            LOG.info("This account may not list {} across the cluster; listing them namespace by namespace instead ({})",
                    resource.apiName(), cause == null ? "403" : cause.getMessage());
        }
    }

    /**
     * Runs a listing across the whole cluster when the account may, otherwise namespace by namespace over
     * {@link #namespacesToWalk()}. Namespaces that refuse the call are skipped; other errors propagate.
     *
     * @param resource     what is listed (keys the remembered refusal)
     * @param clusterWide  the cluster-wide listing
     * @param perNamespace the same listing for one namespace
     * @return the combined items
     */
    public <T> List<T> listAcrossNamespaces(Resource resource, Supplier<List<T>> clusterWide,
                                            Function<String, List<T>> perNamespace) {
        if (!isRefused(resource)) {
            try {
                return clusterWide.get();
            } catch (RuntimeException e) {
                if (!isForbidden(e)) {
                    throw e;
                }
                recordRefusal(resource, e);
            }
        }
        List<T> out = new ArrayList<>();
        for (String ns : namespacesToWalk()) {
            try {
                List<T> items = perNamespace.apply(ns);
                if (items != null) {
                    out.addAll(items);
                }
            } catch (RuntimeException e) {
                if (!isForbidden(e)) {
                    throw e;
                }
                LOG.debug("Listing {} in namespace {} refused; skipping it", resource.apiName(), ns);
            }
        }
        return out;
    }

    /**
     * Namespace names to walk when a cluster-wide listing is refused, sorted. Reused for
     * {@link #WALK_NAMESPACES_TTL_MS}, since a page can trigger several walks in a row.
     *
     * @return namespace names; empty when none are known
     */
    public List<String> namespacesToWalk() {
        long now = clock.getAsLong();
        CachedNamespaces cached = walkNamespaces.get();
        if (cached != null && now - cached.fetchedAt < WALK_NAMESPACES_TTL_MS) {
            return cached.names;
        }
        List<String> names = Collections.unmodifiableList(listNamespaces().stream()
                .map(n -> n.name)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList()));
        walkNamespaces.set(new CachedNamespaces(names, now));
        return names;
    }

    /**
     * Namespaces this account can work in, with lightweight metadata, read live: the cluster's namespaces when the
     * account may list them, else the OpenShift projects it may use, else the namespaces the owner knows.
     *
     * @return namespace descriptions
     */
    public List<KubeNamespace> listNamespaces() {
        if (!isRefused(Resource.NAMESPACES)) {
            try {
                return apiCall.run("list namespaces", () -> client.get().namespaces().list()).getItems().stream()
                        .map(NamespaceScope::toKubeNamespace)
                        .collect(Collectors.toList());
            } catch (RuntimeException e) {
                if (!isForbidden(e)) {
                    throw e;
                }
                recordRefusal(Resource.NAMESPACES, e);
            }
        }
        if (openShift.getAsBoolean()) {
            try {
                List<GenericKubernetesResource> projects = apiCall.run("list projects",
                        () -> client.get().genericKubernetesResources(PROJECT_RDC).list().getItems());
                List<KubeNamespace> out = projects.stream()
                        .map(NamespaceScope::projectToKubeNamespace)
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(n -> n.name))
                        .collect(Collectors.toList());
                LOG.debug("{} OpenShift project(s) visible to this account", out.size());
                return out;
            } catch (RuntimeException e) {
                LOG.warn("Listing OpenShift projects failed; using the namespaces KDPS knows: {}", e.toString());
            }
        }
        List<KubeNamespace> out = new ArrayList<>();
        for (String name : new TreeSet<>(knownNamespaces.get())) {
            if (name == null || name.isBlank()) {
                continue;
            }
            KubeNamespace dto = new KubeNamespace();
            dto.name = name;
            dto.labels = Collections.emptyMap();
            out.add(dto);
        }
        return out;
    }

    /**
     * Whether the API server serves {@code <plural>.<group>} (a CRD name), read from API discovery, which every
     * authenticated account may read. Answers the same question as reading the CRD, without the cluster right.
     *
     * @param crdName CRD name, e.g. {@code certificates.cert-manager.io}
     * @return {@code true} when some served version of the group lists that resource
     */
    public boolean servedByApiDiscovery(String crdName) {
        int dot = crdName.indexOf('.');
        if (dot <= 0 || dot == crdName.length() - 1) {
            return false;
        }
        String plural = crdName.substring(0, dot);
        String group = crdName.substring(dot + 1);
        APIGroup apiGroup = client.get().getApiGroup(group);
        if (apiGroup == null || apiGroup.getVersions() == null) {
            return false;
        }
        for (var version : apiGroup.getVersions()) {
            APIResourceList resources = client.get().getApiResources(version.getGroupVersion());
            if (resources != null && resources.getResources() != null
                    && resources.getResources().stream().anyMatch(r -> plural.equals(r.getName()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Versions of an API group the server serves, from API discovery (e.g. {@code [v1, v1beta1]}).
     *
     * @param group API group name
     * @return served version names; empty when the group is not served
     */
    public List<String> servedApiVersions(String group) {
        APIGroup apiGroup = client.get().getApiGroup(group);
        if (apiGroup == null || apiGroup.getVersions() == null) {
            return Collections.emptyList();
        }
        return apiGroup.getVersions().stream()
                .map(v -> v.getVersion())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    static KubeNamespace toKubeNamespace(Namespace ns) {
        KubeNamespace dto = new KubeNamespace();
        dto.name = ns.getMetadata() != null ? ns.getMetadata().getName() : null;
        dto.labels = ns.getMetadata() != null && ns.getMetadata().getLabels() != null
                ? ns.getMetadata().getLabels() : Collections.emptyMap();
        dto.createdAt = ns.getMetadata() != null ? ns.getMetadata().getCreationTimestamp() : null;
        dto.status = ns.getStatus() != null ? ns.getStatus().getPhase() : null;
        return dto;
    }

    private static KubeNamespace projectToKubeNamespace(GenericKubernetesResource project) {
        if (project.getMetadata() == null || project.getMetadata().getName() == null) {
            return null;
        }
        KubeNamespace dto = new KubeNamespace();
        dto.name = project.getMetadata().getName();
        dto.labels = project.getMetadata().getLabels() != null ? project.getMetadata().getLabels() : Collections.emptyMap();
        dto.createdAt = project.getMetadata().getCreationTimestamp();
        Object status = project.getAdditionalProperties() == null ? null : project.getAdditionalProperties().get("status");
        dto.status = status instanceof Map<?, ?> m && m.get("phase") != null ? String.valueOf(m.get("phase")) : null;
        return dto;
    }
}
