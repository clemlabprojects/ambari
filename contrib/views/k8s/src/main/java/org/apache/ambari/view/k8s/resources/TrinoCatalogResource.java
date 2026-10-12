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
import org.apache.ambari.view.k8s.security.AuthHelper;
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
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * Reusable Trino catalogs: {@code GET /trino-catalogs}, {@code GET|DELETE /trino-catalogs/{id}},
 * {@code POST /trino-catalogs} (create or update — body carries {@code id} for an update). Read-only users see the
 * catalogs with their credentials masked.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TrinoCatalogResource {
    private static final Logger LOG = LoggerFactory.getLogger(TrinoCatalogResource.class);
    private final ViewContext viewContext;
    /** Headers of the request this resource serves: the role check asks Ambari with the caller's credentials. */
    private final HttpHeaders callerHeaders;

    /**
     * Creates the resource for one request.
     *
     * @param viewContext   active Ambari view context
     * @param callerHeaders headers of the request; {@code null} when unknown
     */
    public TrinoCatalogResource(ViewContext viewContext, HttpHeaders callerHeaders) {
        this.viewContext = viewContext;
        this.callerHeaders = callerHeaders;
    }

    @GET
    public Response list() {
        try {
            List<TrinoCatalogDTO> catalogs = new TrinoCatalogService(viewContext).list();
            if (!new AuthHelper(viewContext, callerHeaders).canWrite()) {
                catalogs.forEach(TrinoCatalogResource::maskSecrets);
            }
            return Response.ok(catalogs).build();
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
        if (!new AuthHelper(viewContext, callerHeaders).canWrite()) {
            maskSecrets(d);
        }
        return Response.ok(d).build();
    }

    /**
     * Hides the catalog's credentials from a read-only user, who still sees its name, connector and other settings.
     * Only those who may attach the catalog to a release need them.
     */
    private static void maskSecrets(TrinoCatalogDTO catalog) {
        catalog.propertiesText = TrinoCatalogService.withoutSecrets(catalog.propertiesText);
    }

    @POST
    public Response save(TrinoCatalogDTO request) {
        new AuthHelper(viewContext, callerHeaders).checkWritePermission();
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
        new AuthHelper(viewContext, callerHeaders).checkWritePermission();
        try {
            new TrinoCatalogService(viewContext).delete(id);
            return Response.ok().build();
        } catch (IllegalStateException ise) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(java.util.Map.of("error", ise.getMessage())).build();
        }
    }
}
