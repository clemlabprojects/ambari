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

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.TrinoCatalogDTO;
import org.apache.ambari.view.k8s.service.TrinoCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * Reusable Trino catalogs: {@code GET /trino-catalogs}, {@code GET|DELETE /trino-catalogs/{id}},
 * {@code POST /trino-catalogs} (create or update — body carries {@code id} for an update).
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TrinoCatalogResource {
    private static final Logger LOG = LoggerFactory.getLogger(TrinoCatalogResource.class);
    private final ViewContext viewContext;

    public TrinoCatalogResource(ViewContext viewContext) {
        this.viewContext = viewContext;
    }

    @GET
    public Response list() {
        try {
            return Response.ok(new TrinoCatalogService(viewContext).list()).build();
        } catch (Exception e) {
            LOG.warn("Failed to list Trino catalogs: {}", e.toString());
            return Response.serverError().entity(java.util.Map.of("error", String.valueOf(e.getMessage()))).build();
        }
    }

    @GET
    @Path("{id}")
    public Response get(@PathParam("id") String id) {
        TrinoCatalogDTO d = new TrinoCatalogService(viewContext).get(id);
        if (d == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(java.util.Map.of("error", "No reusable catalog with id " + id)).build();
        }
        return Response.ok(d).build();
    }

    @POST
    public Response save(TrinoCatalogDTO request) {
        try {
            return Response.ok(new TrinoCatalogService(viewContext).save(request)).build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(java.util.Map.of("error", iae.getMessage())).build();
        } catch (Exception e) {
            LOG.warn("Failed to save Trino catalog: {}", e.toString());
            return Response.serverError().entity(java.util.Map.of("error", String.valueOf(e.getMessage()))).build();
        }
    }

    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") String id) {
        try {
            new TrinoCatalogService(viewContext).delete(id);
            return Response.ok().build();
        } catch (IllegalStateException ise) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(java.util.Map.of("error", ise.getMessage())).build();
        }
    }
}
