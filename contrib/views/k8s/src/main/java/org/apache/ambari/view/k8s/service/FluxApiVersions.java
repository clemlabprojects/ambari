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
import io.fabric8.kubernetes.api.model.GroupVersionForDiscovery;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The versions at which KDPS reads and changes the Flux objects on the cluster (HelmRelease, HelmRepository). Flux 2.3
 * and later serve {@code helm.toolkit.fluxcd.io/v2} and {@code source.toolkit.fluxcd.io/v1}, the versions KDPS writes
 * to Git; an older Flux serves only {@code v2beta2} and {@code v1beta2}, which later Flux releases no longer serve. The
 * version used is the one API discovery says the cluster serves, the current one first.
 *
 * <p>Reading an object at a version the cluster does not serve answers 404, which the Kubernetes client takes for an
 * object that does not exist. Discovery tells the two apart: a HelmRelease "not found" while Flux does not serve its
 * API at all is reported as that ({@link Selection#problem}), not as a release Flux has not applied yet.</p>
 */
public final class FluxApiVersions {
    private static final Logger LOG = LoggerFactory.getLogger(FluxApiVersions.class);

    /** How long what discovery said is reused: the Releases page reads every GitOps release's status at once. */
    private static final long CACHE_MILLIS = 60_000L;

    /** What discovery said of an API group, by client: {@code null} served versions for a group not served. */
    private static final Map<KubernetesClient, Map<String, Discovered>> DISCOVERED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private record Discovered(List<String> versions, long at) {}

    private FluxApiVersions() {
    }

    /** A kind of Flux object KDPS reads on the cluster, with the versions it knows, the current one first. */
    public enum Kind {
        HELM_RELEASE("HelmRelease", "helmreleases", "helm.toolkit.fluxcd.io", "v2", "v2beta2"),
        HELM_REPOSITORY("HelmRepository", "helmrepositories", "source.toolkit.fluxcd.io", "v1", "v1beta2");

        private final String kind;
        private final String plural;
        private final String group;
        private final String current;
        private final String beforeFlux23;

        Kind(String kind, String plural, String group, String current, String beforeFlux23) {
            this.kind = kind;
            this.plural = plural;
            this.group = group;
            this.current = current;
            this.beforeFlux23 = beforeFlux23;
        }

        public String group() {
            return group;
        }

        /** The version Flux 2.3 and later serve, which KDPS writes to Git. */
        public String current() {
            return current;
        }

        /** The version Flux before 2.3 serves. */
        public String beforeFlux23() {
            return beforeFlux23;
        }
    }

    /**
     * The version to read a kind of Flux object at.
     *
     * @param kind    the kind of object
     * @param version the version to use
     * @param problem why the cluster cannot be asked for that kind at all (Flux does not serve its API), or
     *                {@code null} when it can, or when discovery could not tell
     */
    public record Selection(Kind kind, String version, String problem) {

        /** Whether the cluster serves the kind at {@link #version}, as far as discovery tells. */
        public boolean served() {
            return problem == null;
        }

        /** The context the Kubernetes client reads the kind with. */
        public CustomResourceDefinitionContext context() {
            return new CustomResourceDefinitionContext.Builder()
                    .withGroup(kind.group)
                    .withVersion(version)
                    .withKind(kind.kind)
                    .withScope("Namespaced")
                    .withPlural(kind.plural)
                    .build();
        }
    }

    /**
     * The version to use, from what discovery said of the kind's group.
     *
     * @param served the versions the cluster serves; {@code null} when it does not serve the group, empty when
     *               discovery could not tell
     */
    static Selection select(Kind kind, List<String> served) {
        if (served == null) {
            return new Selection(kind, kind.current, "The cluster does not serve the Flux " + kind.kind + " API ("
                    + kind.group + "): Flux is not installed, or its API cannot be seen with the view's account. Check"
                    + " that Flux 2.3 or later runs on the cluster.");
        }
        if (served.isEmpty() || served.contains(kind.current)) {
            return new Selection(kind, kind.current, null);
        }
        if (served.contains(kind.beforeFlux23)) {
            return new Selection(kind, kind.beforeFlux23, null);
        }
        return new Selection(kind, kind.current, "The cluster serves the Flux " + kind.kind + " API only at "
                + kind.group + " " + String.join(", ", served) + ", not at " + kind.current + " (Flux 2.3 or later) nor "
                + kind.beforeFlux23 + ": KDPS cannot read its " + kind.kind + " objects. Run a Flux release that serves "
                + kind.group + "/" + kind.current + ".");
    }

    /**
     * The version to read a kind of Flux object at on the cluster of the client.
     *
     * @param client the client; {@code null} gives the current version
     */
    public static Selection resolve(KubernetesClient client, Kind kind) {
        if (client == null) {
            return new Selection(kind, kind.current, null);
        }
        long now = System.currentTimeMillis();
        Discovered known;
        synchronized (DISCOVERED) {
            known = DISCOVERED.getOrDefault(client, Map.of()).get(kind.group);
        }
        if (known == null || now - known.at() > CACHE_MILLIS) {
            List<String> served;
            try {
                served = servedVersions(client, kind.group);
            } catch (RuntimeException e) {
                // Discovery is open to every account; when it still fails, the current version is the best guess.
                LOG.debug("API discovery of {} failed: {}", kind.group, e.toString());
                return new Selection(kind, kind.current, null);
            }
            known = new Discovered(served, now);
            synchronized (DISCOVERED) {
                DISCOVERED.computeIfAbsent(client, c -> new HashMap<>()).put(kind.group, known);
            }
        }
        return select(kind, known.versions());
    }

    /** The versions of the group the cluster serves, or {@code null} when it serves none. */
    private static List<String> servedVersions(KubernetesClient client, String group) {
        APIGroup apiGroup = client.getApiGroup(group);
        if (apiGroup == null) {
            return null;
        }
        List<String> versions = new ArrayList<>();
        if (apiGroup.getVersions() != null) {
            for (GroupVersionForDiscovery version : apiGroup.getVersions()) {
                if (version != null && version.getVersion() != null) {
                    versions.add(version.getVersion());
                }
            }
        }
        return versions;
    }
}
