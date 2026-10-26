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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A cluster KDPS knows, as an administrator described it. How KDPS connects to it is kept apart, with the other
 * connection settings ({@code ViewConfigurationService}), so that this description never holds a credential.
 *
 * @param id               the cluster's id ({@link ClusterRef})
 * @param name             what the cluster is called in the view, such as "Production OpenShift"
 * @param description      what the cluster is for; may be empty
 * @param protectedCluster whether removing or changing a release there asks the operator to type the cluster's name
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KubernetesCluster(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("protected") boolean protectedCluster) {

    /** Longest name of a cluster. */
    public static final int MAX_NAME_LENGTH = 64;
    /** Longest description of a cluster. */
    public static final int MAX_DESCRIPTION_LENGTH = 512;

    /** The default cluster as it is described until an administrator describes it. */
    public static KubernetesCluster defaultCluster() {
        return new KubernetesCluster(ClusterRef.DEFAULT_ID, "Default cluster",
                "The cluster KDPS connected to before it knew several clusters.", false);
    }

    /** The cluster this description is of. */
    public ClusterRef ref() {
        return ClusterRef.of(id);
    }
}
