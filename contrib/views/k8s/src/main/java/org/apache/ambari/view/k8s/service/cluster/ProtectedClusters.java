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

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A protected cluster (a production one) takes an uninstall, a rollback or an upgrade of a release only when the
 * request carries the cluster's name, as the operator typed it ({@value #PARAM}). Checked on the server, whatever the
 * client: the page asks for the name, a script must send it.
 */
public final class ProtectedClusters {

    private static final Logger LOG = LoggerFactory.getLogger(ProtectedClusters.class);

    /** The query parameter carrying the name the operator typed. */
    public static final String PARAM = "confirmCluster";

    private ProtectedClusters() {
    }

    /** Whether an uninstall, a rollback or an upgrade of a release on {@code cluster} needs its name. */
    public static boolean isProtected(ViewContext ctx, ClusterRef cluster) {
        return new ClusterRegistry(new InstanceSettings(ctx)).find(cluster.id())
                .map(KubernetesCluster::protectedCluster).orElse(false);
    }

    /**
     * Refuses an action on a protected cluster that does not carry its name.
     *
     * @param ctx     the view instance
     * @param cluster the cluster the action changes a release on
     * @param typed   what the operator typed: the cluster's name, or its id; {@code null} when nothing was asked
     * @param action  the action, as the refusal names it ("the uninstall of analytics/trino")
     * @throws NotConfirmedException when the cluster is protected and {@code typed} is neither its name nor its id
     */
    public static void require(ViewContext ctx, ClusterRef cluster, String typed, String action) {
        KubernetesCluster known = new ClusterRegistry(new InstanceSettings(ctx)).find(cluster.id()).orElse(null);
        if (known == null || !known.protectedCluster()) {
            return;
        }
        String given = typed == null ? "" : typed.trim();
        if (!given.isEmpty() && (given.equals(known.name()) || given.equals(known.id()))) {
            LOG.info("Protected cluster '{}': {} confirmed by {}", known.id(), action, ctx.getUsername());
            return;
        }
        throw new NotConfirmedException(known, action, !given.isEmpty());
    }

    /** An action on a protected cluster without its name, or with another one. */
    public static final class NotConfirmedException extends RuntimeException {
        /** The code the endpoints answer this refusal with (409), which the pages recognise. */
        public static final String CODE = "PROTECTED_CLUSTER_NOT_CONFIRMED";

        private final KubernetesCluster cluster;

        NotConfirmedException(KubernetesCluster cluster, String action, boolean mistyped) {
            super((mistyped ? "The name typed is not the cluster's. " : "")
                    + "The Kubernetes cluster '" + cluster.name() + "' is protected: type its name to confirm "
                    + action + ". Nothing was changed.");
            this.cluster = cluster;
        }

        public KubernetesCluster getCluster() {
            return cluster;
        }

        /** The answer to the request: 409, with the code, the message and the cluster to name. */
        public Response response() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", getMessage());
            body.put("code", CODE);
            body.put("cluster", Map.of("id", cluster.id(), "name", cluster.name()));
            return Response.status(Response.Status.CONFLICT).type(MediaType.APPLICATION_JSON).entity(body).build();
        }
    }
}
