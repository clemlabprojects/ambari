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

package org.apache.ambari.view.k8s.resources;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.ContextDTO;
import org.apache.ambari.view.k8s.model.ResolvedContext;
import org.apache.ambari.view.k8s.service.ContextService;
import org.apache.ambari.view.k8s.store.KdpsContextEntity;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.apache.ambari.view.k8s.utils.AmbariLoopbackUrlResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST resource for platform contexts at {@code .../resources/api/contexts}.
 *
 * <ul>
 *   <li>{@code GET /contexts} — list (auto-seeds the managed default on first call)</li>
 *   <li>{@code GET /contexts/{id}} — one</li>
 *   <li>{@code GET /contexts/{id}/resolved} — the resolved view (live for MANAGED)</li>
 *   <li>{@code POST /contexts} — create/update</li>
 *   <li>{@code DELETE /contexts/{id}} — delete (the default managed context is protected)</li>
 * </ul>
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ContextResource {

    private static final Logger LOG = LoggerFactory.getLogger(ContextResource.class);

    private final ViewContext viewContext;

    public ContextResource(ViewContext viewContext) {
        this.viewContext = viewContext;
    }

    @GET
    @Path("schema")
    public Response schema() {
        return Response.ok(new org.apache.ambari.view.k8s.service.ContextSchemaService().loadSchema()).build();
    }

    @GET
    public Response list(@Context HttpHeaders headers) {
        ContextService svc = new ContextService(viewContext);
        String cluster = null;
        try {
            cluster = resolveClusterName(headers);
        } catch (Exception e) {
            LOG.warn("Could not resolve cluster name for managed context: {}", e.toString());
        }
        List<ContextDTO> out = svc.list(cluster).stream()
                .map(ContextDTO::fromEntity)
                .collect(Collectors.toList());
        return Response.ok(out).build();
    }

    @GET
    @Path("{id}")
    public Response get(@PathParam("id") String id) {
        KdpsContextEntity e = new ContextService(viewContext).get(id);
        if (e == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(java.util.Map.of("error", "No context with id " + id)).build();
        }
        return Response.ok(ContextDTO.fromEntity(e)).build();
    }

    @GET
    @Path("{id}/resolved")
    public Response resolved(@PathParam("id") String id, @Context HttpHeaders headers) {
        try {
            ContextService svc = new ContextService(viewContext);
            String cluster = resolveClusterName(headers);
            AmbariActionClient client = ambariClient(headers);
            ResolvedContext rc = svc.resolve(id, client, cluster);
            // Never expose the (external) Ranger admin password over REST.
            rc.setRangerAdminPassword(rc.getRangerAdminPassword() == null ? null : "********");
            return Response.ok(rc).build();
        } catch (Exception e) {
            LOG.warn("Failed to resolve context {}: {}", id, e.toString());
            return Response.serverError().entity(java.util.Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    public Response save(KdpsContextEntity entity) {
        try {
            KdpsContextEntity saved = new ContextService(viewContext).save(entity);
            return Response.ok(ContextDTO.fromEntity(saved)).build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(java.util.Map.of("error", iae.getMessage())).build();
        } catch (Exception e) {
            LOG.warn("Failed to save context: {}", e.toString());
            return Response.serverError().entity(java.util.Map.of("error", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") String id) {
        try {
            new ContextService(viewContext).delete(id);
            return Response.ok().build();
        } catch (IllegalStateException ise) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(java.util.Map.of("error", ise.getMessage())).build();
        }
    }

    // ----- helpers (mirror DiscoveryResource) -----

    private AmbariActionClient ambariClient(HttpHeaders headers) {
        String ambariBase = AmbariLoopbackUrlResolver.resolveApiBase(viewContext);
        return new AmbariActionClient(viewContext, ambariBase,
                AmbariActionClient.toAuthHeaders(headers.getRequestHeaders()));
    }

    private String resolveClusterName(HttpHeaders headers) throws Exception {
        if (viewContext.getCluster() != null) {
            return viewContext.getCluster().getName();
        }
        AmbariActionClient client = ambariClient(headers);
        List<String> clusters = client.listClusters();
        if (clusters == null || clusters.isEmpty()) {
            return null;
        }
        String preferred = viewContext.getProperties().get("ambari.cluster.preferred");
        if (preferred != null && !preferred.isBlank()) {
            for (String c : clusters) {
                if (c.equalsIgnoreCase(preferred)) {
                    return c;
                }
            }
        }
        return new ArrayList<>(clusters).get(0);
    }
}
