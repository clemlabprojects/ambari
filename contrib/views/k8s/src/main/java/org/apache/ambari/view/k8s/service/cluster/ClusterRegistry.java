/**
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

package org.apache.ambari.view.k8s.service.cluster;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.k8s.store.InstanceSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The clusters a view instance knows: the default cluster, always there, and the clusters an administrator added. Only
 * their descriptions are kept here, as one setting of the view instance; how KDPS connects to each is kept with the
 * other connection settings.
 */
public class ClusterRegistry {

    static final String CLUSTERS_KEY = "clusters";

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Serializes the changes of the list: a new registry is made for each request. */
    private static final Object LOCK = new Object();

    private final InstanceSettings settings;

    public ClusterRegistry(InstanceSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Every cluster: the default one first, then the others by id. */
    public List<KubernetesCluster> list() {
        List<KubernetesCluster> saved = read();
        List<KubernetesCluster> out = new ArrayList<>();
        out.add(saved.stream().filter(c -> c.ref().isDefault()).findFirst().orElse(KubernetesCluster.defaultCluster()));
        saved.stream().filter(c -> !c.ref().isDefault())
                .sorted(Comparator.comparing(KubernetesCluster::id))
                .forEach(out::add);
        return out;
    }

    /** A cluster by id, when the view knows it. */
    public Optional<KubernetesCluster> find(String id) {
        return list().stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /**
     * Adds a cluster, or describes again one the view knows (the default cluster included).
     *
     * @return the cluster as saved
     * @throws IllegalArgumentException when its id, name or description cannot be used
     */
    public KubernetesCluster save(KubernetesCluster cluster) {
        ClusterRef ref = ClusterRef.of(cluster.id());
        String name = cluster.name() == null ? "" : cluster.name().trim();
        String description = cluster.description() == null ? "" : cluster.description().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Give the cluster a name, such as 'Production OpenShift'.");
        }
        if (name.length() > KubernetesCluster.MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("The name of a cluster has at most " + KubernetesCluster.MAX_NAME_LENGTH
                    + " characters.");
        }
        if (description.length() > KubernetesCluster.MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("The description of a cluster has at most "
                    + KubernetesCluster.MAX_DESCRIPTION_LENGTH + " characters.");
        }
        KubernetesCluster saved = new KubernetesCluster(ref.id(), name, description, cluster.protectedCluster());
        synchronized (LOCK) {
            List<KubernetesCluster> clusters = read();
            if (clusters.stream().anyMatch(c -> !c.id().equals(saved.id()) && c.name().equalsIgnoreCase(name))) {
                throw new IllegalArgumentException("Another cluster is already called '" + name + "'.");
            }
            clusters.removeIf(c -> c.id().equals(saved.id()));
            clusters.add(saved);
            write(clusters);
        }
        return saved;
    }

    /**
     * Removes a cluster from the list. Forgetting its connection is the caller's.
     *
     * @return whether the view knew it
     * @throws IllegalArgumentException for the default cluster, which is always there
     */
    public boolean remove(String id) {
        if (ClusterRef.DEFAULT_ID.equals(id)) {
            throw new IllegalArgumentException("The default cluster cannot be removed.");
        }
        synchronized (LOCK) {
            List<KubernetesCluster> clusters = read();
            boolean known = clusters.removeIf(c -> c.id().equals(id));
            if (known) {
                write(clusters);
            }
            return known;
        }
    }

    private List<KubernetesCluster> read() {
        String json = settings.getText(CLUSTERS_KEY);
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(JSON.readValue(json, new TypeReference<List<KubernetesCluster>>() { }));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The list of clusters cannot be read: " + e.getOriginalMessage(), e);
        }
    }

    private void write(List<KubernetesCluster> clusters) {
        try {
            settings.putText(CLUSTERS_KEY, JSON.writeValueAsString(clusters));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The list of clusters cannot be written: " + e.getOriginalMessage(), e);
        }
    }
}
