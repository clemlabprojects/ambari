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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * What the connected account may list, and how to list across namespaces when it may not list the cluster.
 *
 * <p>On sites where the platform team hands out projects, the account KDPS connects with only holds rights inside
 * its own projects: every cluster-wide listing (namespaces, secrets for Helm, pods, events, CRDs) answers 403. Asking
 * for cluster-wide rights is not the answer: "list secrets" across the cluster would let the account read every
 * secret. Instead, each listing tries the cluster first and, when refused, walks the namespaces the account can use.
 *
 * <p>The namespaces walked are, in order of preference: on OpenShift, the Project API, which returns exactly the
 * projects the caller may use and needs no cluster permission; the cluster's namespaces, when the account may list
 * them; otherwise the namespaces KDPS can name without listing (supplied by the owner).
 *
 * <p>Refusals are remembered for {@link #REFUSAL_TTL_MS}, for the cluster-wide call and for each namespace, so a
 * restricted account does not repeat refused calls on every page refresh while a right granted later is picked up.
 * Behaviour follows the account's rights, not the platform type: an account allowed to list the cluster keeps the
 * single cluster-wide call and never walks.
 *
 * <p>Thread-safe. One instance per Kubernetes connection; the owner calls {@link #reset()} whenever the connection
 * or its credentials change. Results of calls that started before a reset are not remembered.
 */
public class NamespaceScope {

    private static final Logger LOG = LoggerFactory.getLogger(NamespaceScope.class);

    /** How long a refused listing is remembered before it is tried again. */
    static final long REFUSAL_TTL_MS = 10 * 60_000L;

    /** How long the namespaces to walk are reused; a single page can trigger several walks in a row. */
    static final long WALK_NAMESPACES_TTL_MS = 30_000L;

    /** Cause chains deeper than this are not inspected (guards against cycles that skip a level). */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** OpenShift Project API (cluster-scoped): lists only the projects the caller may use. */
    private static final ResourceDefinitionContext PROJECT_RDC = new ResourceDefinitionContext.Builder()
            .withGroup("project.openshift.io").withVersion("v1")
            .withKind("Project").withPlural("projects").withNamespaced(false).build();

    /** Listings whose refusals are remembered. */
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
     * Implemented by a method reference such as {@code service::executeWithAuthRetry}.
     */
    @FunctionalInterface
    public interface ApiCall {
        <T> T run(String operation, Supplier<T> call);
    }

    /**
     * Runs a walk's per-namespace calls in parallel on {@code executor}. Namespaces that have not answered within
     * {@code timeout} of the start of the walk (including time queued behind other walks on a shared executor) are
     * left out with a warning, so one slow namespace yields a partial listing, not a failure. When none answered,
     * the listing fails rather than look empty.
     * A call blocked inside native code cannot be interrupted and keeps its thread until it returns.
     */
    public static final class Parallel {
        final ExecutorService executor;
        final Duration timeout;

        public Parallel(ExecutorService executor, Duration timeout) {
            this.executor = Objects.requireNonNull(executor, "executor");
            this.timeout = Objects.requireNonNull(timeout, "timeout");
        }
    }

    /** A remembered refusal: of the cluster-wide call when {@code namespace} is null, else of one namespace. */
    private record RefusalKey(Resource resource, String namespace) {
    }

    private record CachedNamespaces(List<String> names, long fetchedAt) {
    }

    private final Supplier<KubernetesClient> client;
    private final BooleanSupplier openShift;
    private final Supplier<Collection<String>> knownNamespaces;
    private final ApiCall apiCall;
    private final LongSupplier clock;

    private final Map<RefusalKey, Long> refusedAt = new ConcurrentHashMap<>();
    private final AtomicReference<CachedNamespaces> walkNamespaces = new AtomicReference<>(null);
    /** Incremented by {@link #reset()}; state computed under an older generation is discarded. */
    private final AtomicLong generation = new AtomicLong();

    /**
     * @param client          the current Kubernetes client, {@code null} while the view is not configured (read on
     *                        every call: the owner may rebuild it)
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
     * True when a Kubernetes API call was refused for lack of rights: a 403 from the Kubernetes client anywhere in
     * the cause chain. A 403 for {@code system:anonymous} is not a lack of rights but credentials that were not sent
     * or not accepted, and is left to surface as a connection problem. Helm reports refusals as plain text and is
     * recognised by {@link HelmService} instead.
     *
     * @param e any failure, possibly wrapped
     * @return {@code true} for a rights refusal
     */
    public static boolean isForbidden(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof KubernetesClientException kce && kce.getCode() == 403) {
                return !isAnonymous(kce.getMessage());
            }
        }
        return false;
    }

    /** Whether an API error message names the anonymous user, i.e. the request carried no accepted credentials. */
    static boolean isAnonymous(String message) {
        return message != null && message.contains("User \"system:anonymous\"");
    }

    private static boolean isNotFound(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof KubernetesClientException kce && kce.getCode() == 404) {
                return true;
            }
        }
        return false;
    }

    /** Forgets remembered refusals and cached namespaces. Call when the connection or its credentials change. */
    public void reset() {
        generation.incrementAndGet();
        refusedAt.clear();
        walkNamespaces.set(null);
    }

    /** Forgets the cached namespaces to walk, e.g. after creating a namespace. */
    public void invalidateNamespaces() {
        walkNamespaces.set(null);
    }

    /** Whether a cluster-wide listing of {@code resource} was refused within the last {@link #REFUSAL_TTL_MS}. */
    public boolean isRefused(Resource resource) {
        return isRefused(new RefusalKey(resource, null));
    }

    private boolean isRefused(RefusalKey key) {
        Long at = refusedAt.get(key);
        if (at == null) {
            return false;
        }
        if (clock.getAsLong() - at > REFUSAL_TTL_MS) {
            refusedAt.remove(key, at);
            return false;
        }
        return true;
    }

    private void recordRefusal(long gen, RefusalKey key, Throwable cause) {
        if (gen != generation.get()) {
            return; // started before a reset: belongs to the previous connection
        }
        if (refusedAt.put(key, clock.getAsLong()) == null) {
            if (key.namespace() == null) {
                LOG.info("This account may not list {} across the cluster; listing them namespace by namespace instead ({})",
                        key.resource().apiName(), cause == null ? "403" : cause.getMessage());
            } else {
                LOG.debug("Listing {} in namespace {} refused; skipping it", key.resource().apiName(), key.namespace());
            }
        }
    }

    /**
     * Lists {@code resource} across the whole cluster when the account may, otherwise namespace by namespace over
     * {@link #namespacesToWalk()}, one namespace at a time. Refused namespaces are skipped; other errors propagate.
     *
     * @param resource     what is listed (keys the remembered refusals)
     * @param clusterWide  the cluster-wide listing
     * @param perNamespace the same listing for one namespace
     * @return the combined items, cluster order or namespace order
     */
    public <T> List<T> listAcrossNamespaces(Resource resource, Supplier<List<T>> clusterWide,
                                            Function<String, List<T>> perNamespace) {
        return listAcrossNamespaces(resource, clusterWide, perNamespace, NamespaceScope::isForbidden, null);
    }

    /**
     * Same as {@link #listAcrossNamespaces(Resource, Supplier, Function)}, with the caller's own way of recognising
     * a refusal (e.g. Helm's text errors) and, optionally, parallel per-namespace calls with a deadline.
     *
     * @param resource     what is listed (keys the remembered refusals)
     * @param clusterWide  the cluster-wide listing
     * @param perNamespace the same listing for one namespace
     * @param isRefusal    whether a failure is a refusal for lack of rights
     * @param parallel     how to run the walk in parallel, or {@code null} to walk one namespace at a time
     * @return the combined items, cluster order or namespace order; with {@code parallel}, without the namespaces
     *         that did not answer before the deadline
     */
    public <T> List<T> listAcrossNamespaces(Resource resource, Supplier<List<T>> clusterWide,
                                            Function<String, List<T>> perNamespace,
                                            Predicate<Throwable> isRefusal, Parallel parallel) {
        final long gen = generation.get();
        RefusalKey clusterKey = new RefusalKey(resource, null);
        if (!isRefused(clusterKey)) {
            try {
                return apiCall.run("list " + resource.apiName() + " in all namespaces", clusterWide);
            } catch (RuntimeException e) {
                if (!isRefusal.test(e)) {
                    throw e;
                }
                recordRefusal(gen, clusterKey, e);
            }
        }
        List<String> namespaces = namespacesToWalk().stream()
                .filter(ns -> !isRefused(new RefusalKey(resource, ns)))
                .collect(Collectors.toList());
        Function<String, List<T>> guarded = ns -> {
            try {
                List<T> items = apiCall.run("list " + resource.apiName() + " in " + ns, () -> perNamespace.apply(ns));
                return items == null ? List.of() : items;
            } catch (RuntimeException e) {
                if (!isRefusal.test(e)) {
                    throw e;
                }
                recordRefusal(gen, new RefusalKey(resource, ns), e);
                return List.of();
            }
        };
        return parallel == null ? walkSequentially(namespaces, guarded) : walkInParallel(resource, namespaces, guarded, parallel);
    }

    private static <T> List<T> walkSequentially(List<String> namespaces, Function<String, List<T>> perNamespace) {
        List<T> out = new ArrayList<>();
        for (String ns : namespaces) {
            out.addAll(perNamespace.apply(ns));
        }
        return out;
    }

    private static <T> List<T> walkInParallel(Resource resource, List<String> namespaces,
                                              Function<String, List<T>> perNamespace, Parallel parallel) {
        List<Future<List<T>>> futures = new ArrayList<>(namespaces.size());
        for (String ns : namespaces) {
            futures.add(parallel.executor.submit(() -> perNamespace.apply(ns)));
        }
        long deadline = System.nanoTime() + parallel.timeout.toNanos();
        List<T> out = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try {
            for (int i = 0; i < futures.size(); i++) {
                Future<List<T>> f = futures.get(i);
                try {
                    // Past the deadline, get(0) still returns a result that is already there.
                    out.addAll(f.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (TimeoutException e) {
                    f.cancel(true); // frees the pool if the call has not started yet
                    skipped.add(namespaces.get(i));
                }
            }
        } catch (ExecutionException e) {
            futures.forEach(f -> f.cancel(true));
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(cause);
        } catch (CancellationException e) {
            throw new IllegalStateException("Listing " + resource.apiName() + " was cancelled", e);
        } catch (InterruptedException e) {
            futures.forEach(f -> f.cancel(true));
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while listing " + resource.apiName(), e);
        }
        if (!namespaces.isEmpty() && skipped.size() == namespaces.size()) {
            // Nothing answered: an empty list would read as "nothing there". Usually the shared executor is busy
            // or blocked (calls stuck in native code keep their threads), which must show as an error.
            throw new IllegalStateException("Listing " + resource.apiName() + ": none of " + namespaces.size()
                    + " namespace(s) answered within " + parallel.timeout.getSeconds() + " s");
        }
        if (!skipped.isEmpty()) {
            LOG.warn("Listing {}: {} of {} namespace(s) did not answer within {} s and are left out: {}",
                    resource.apiName(), skipped.size(), namespaces.size(), parallel.timeout.getSeconds(),
                    skipped.size() <= 20 ? skipped : skipped.subList(0, 20) + " ...");
        }
        return out;
    }

    /**
     * Namespace names to walk when a cluster-wide listing is refused, sorted: on OpenShift the projects the account
     * may use, else the cluster's namespaces, else the namespaces the owner knows. Reused for
     * {@link #WALK_NAMESPACES_TTL_MS}.
     *
     * @return namespace names; empty when none are known
     */
    public List<String> namespacesToWalk() {
        final long gen = generation.get();
        long now = clock.getAsLong();
        CachedNamespaces cached = walkNamespaces.get();
        if (cached != null && now - cached.fetchedAt() < WALK_NAMESPACES_TTL_MS) {
            return cached.names();
        }
        List<KubeNamespace> source = null;
        if (openShift.getAsBoolean()) {
            source = listProjectsOrNull();
        }
        if (source == null) {
            source = listNamespaces(false);
        }
        List<String> names = Collections.unmodifiableList(source.stream()
                .map(n -> n.name)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList()));
        if (gen == generation.get()) {
            walkNamespaces.set(new CachedNamespaces(names, now));
        }
        return names;
    }

    /**
     * Namespaces this account can work in, with lightweight metadata, read live and sorted by name: the cluster's
     * namespaces when the account may list them, else the OpenShift projects it may use, else the namespaces the
     * owner knows.
     *
     * @return namespace descriptions
     */
    public List<KubeNamespace> listNamespaces() {
        return listNamespaces(true);
    }

    private List<KubeNamespace> listNamespaces(boolean tryProjects) {
        final long gen = generation.get();
        RefusalKey key = new RefusalKey(Resource.NAMESPACES, null);
        if (!isRefused(key)) {
            try {
                return sortedByName(apiCall.run("list namespaces", () -> client().namespaces().list())
                        .getItems().stream()
                        .map(NamespaceScope::toKubeNamespace));
            } catch (RuntimeException e) {
                if (!isForbidden(e)) {
                    throw e;
                }
                recordRefusal(gen, key, e);
            }
        }
        if (tryProjects && openShift.getAsBoolean()) {
            List<KubeNamespace> projects = listProjectsOrNull();
            if (projects != null) {
                return projects;
            }
        }
        return sortedByName(new TreeSet<>(knownNamespaces.get()).stream()
                .filter(n -> n != null && !n.isBlank())
                .map(n -> {
                    KubeNamespace dto = new KubeNamespace();
                    dto.name = n;
                    dto.labels = Collections.emptyMap();
                    return dto;
                }));
    }

    /**
     * The OpenShift projects this account may use, or {@code null} when the Project API refuses (403) or is not
     * served (404). Any other failure propagates.
     */
    private List<KubeNamespace> listProjectsOrNull() {
        try {
            List<GenericKubernetesResource> projects = apiCall.run("list projects",
                    () -> client().genericKubernetesResources(PROJECT_RDC).list().getItems());
            return sortedByName(projects.stream().map(NamespaceScope::projectToKubeNamespace).filter(Objects::nonNull));
        } catch (RuntimeException e) {
            if (isForbidden(e) || isNotFound(e)) {
                LOG.debug("OpenShift projects are not listable here: {}", e.getMessage());
                return null;
            }
            throw e;
        }
    }

    /**
     * Whether {@code namespace} needs to be created: {@code true} only when the API server says it does not exist.
     * A namespace this account may not even read is a pre-provisioned project (OpenShift sites where the platform
     * team hands out projects) and is reported as existing. Any other failure answers {@code true}, so callers keep
     * their previous behaviour of letting Helm create it.
     *
     * @param namespace namespace name
     * @return {@code true} when the namespace is missing or its existence could not be established
     */
    public boolean namespaceMissing(String namespace) {
        try {
            return apiCall.run("get namespace " + namespace, () -> client().namespaces().withName(namespace).get()) == null;
        } catch (RuntimeException e) {
            if (isForbidden(e)) {
                return false;
            }
            LOG.debug("Could not check whether namespace {} exists: {}", namespace, e.toString());
            return true;
        }
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
        KubernetesClient c = client();
        APIGroup apiGroup = c.getApiGroup(group);
        if (apiGroup == null || apiGroup.getVersions() == null) {
            return false;
        }
        for (var version : apiGroup.getVersions()) {
            APIResourceList resources = c.getApiResources(version.getGroupVersion());
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
        APIGroup apiGroup = client().getApiGroup(group);
        if (apiGroup == null || apiGroup.getVersions() == null) {
            return Collections.emptyList();
        }
        return apiGroup.getVersions().stream()
                .map(v -> v.getVersion())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private KubernetesClient client() {
        KubernetesClient c = client.get();
        if (c == null) {
            throw new IllegalStateException("The view is not configured with a kubeconfig.");
        }
        return c;
    }

    private static List<KubeNamespace> sortedByName(java.util.stream.Stream<KubeNamespace> namespaces) {
        return namespaces
                .sorted(Comparator.comparing((KubeNamespace n) -> n.name, Comparator.nullsLast(Comparator.naturalOrder())))
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
