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

package org.apache.ambari.view.sqlassistant.resources;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.sqlassistant.service.SemanticServiceProxy;
import org.apache.ambari.view.sqlassistant.service.ViewConfigurationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Root JAX-RS resource for the SQL Assistant Ambari View.
 *
 * <p>This class is intentionally thin: it resolves configuration from Ambari's
 * ViewContext, builds a SemanticServiceProxy, and delegates every API call to
 * the Python FastAPI semantic service.  All business logic lives in Python.
 *
 * <p>URL pattern (Ambari view resource naming):
 * <pre>
 *   /api/v1/views/SQL-ASSISTANT-VIEW/versions/{v}/instances/{i}/resources/api/**
 * </pre>
 */
@Path("/")
public class SqlAssistantService {

    private static final Logger LOG = LoggerFactory.getLogger(SqlAssistantService.class);

    @Inject
    private ViewContext viewContext;

    // ── NL-to-SQL ─────────────────────────────────────────────────────────────

    @POST
    @Path("nl-to-sql")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response nlToSql(String body, @Context HttpHeaders headers) {
        String requestId = requestId(headers);
        LOG.debug("nl-to-sql requestId={}", requestId);
        String result = proxy().post("/api/v1/nl-to-sql", body, requestId);
        return ok(result);
    }

    @POST
    @Path("execute")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response execute(String body, @Context HttpHeaders headers) {
        String requestId = requestId(headers);
        String result = proxy().post("/api/v1/execute", body, requestId);
        return ok(result);
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    @GET
    @Path("schema/catalogs")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listCatalogs(@Context HttpHeaders headers) {
        String result = proxy().get("/api/v1/schema/catalogs", null, requestId(headers));
        return ok(result);
    }

    @GET
    @Path("schema/catalogs/{catalog}/namespaces")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listNamespaces(@PathParam("catalog") String catalog,
                                   @Context HttpHeaders headers) {
        String result = proxy().get(
                "/api/v1/schema/catalogs/" + catalog + "/namespaces",
                null, requestId(headers));
        return ok(result);
    }

    @GET
    @Path("schema/catalogs/{catalog}/namespaces/{namespace}/tables")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listTables(@PathParam("catalog") String catalog,
                               @PathParam("namespace") String namespace,
                               @Context HttpHeaders headers) {
        String result = proxy().get(
                "/api/v1/schema/catalogs/" + catalog + "/namespaces/" + namespace + "/tables",
                null, requestId(headers));
        return ok(result);
    }

    @GET
    @Path("schema/catalogs/{catalog}/namespaces/{namespace}/tables/{table}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTableSchema(@PathParam("catalog") String catalog,
                                   @PathParam("namespace") String namespace,
                                   @PathParam("table") String table,
                                   @Context HttpHeaders headers) {
        String result = proxy().get(
                "/api/v1/schema/catalogs/" + catalog + "/namespaces/" + namespace + "/tables/" + table,
                null, requestId(headers));
        return ok(result);
    }

    @POST
    @Path("schema/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    public Response refreshSchema(@Context HttpHeaders headers) {
        String result = proxy().post("/api/v1/schema/refresh", "{}", requestId(headers));
        return ok(result);
    }

    // ── History ───────────────────────────────────────────────────────────────

    @GET
    @Path("history")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listHistory(@QueryParam("limit") Integer limit,
                                @QueryParam("offset") Integer offset,
                                @QueryParam("search") String search,
                                @QueryParam("dialect") String dialect,
                                @Context HttpHeaders headers) {
        Map<String, String> params = new HashMap<>();
        if (limit != null)  params.put("limit", String.valueOf(limit));
        if (offset != null) params.put("offset", String.valueOf(offset));
        if (search != null) params.put("search", search);
        if (dialect != null) params.put("dialect", dialect);
        String result = proxy().get("/api/v1/history", params, requestId(headers));
        return ok(result);
    }

    @DELETE
    @Path("history/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteHistoryEntry(@PathParam("id") String id,
                                       @Context HttpHeaders headers) {
        String result = proxy().delete("/api/v1/history/" + id, requestId(headers));
        return ok(result);
    }

    @DELETE
    @Path("history")
    @Produces(MediaType.APPLICATION_JSON)
    public Response clearHistory(@Context HttpHeaders headers) {
        String result = proxy().delete("/api/v1/history", requestId(headers));
        return ok(result);
    }

    // ── Health / Config ───────────────────────────────────────────────────────

    @GET
    @Path("health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health(@Context HttpHeaders headers) {
        String result = proxy().get("/health", null, requestId(headers));
        return ok(result);
    }

    @GET
    @Path("config")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfig(@Context HttpHeaders headers) {
        String result = proxy().get("/api/v1/config", null, requestId(headers));
        return ok(result);
    }

    @GET
    @Path("view-config")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getViewConfig() {
        ViewConfigurationService cfg = new ViewConfigurationService(viewContext);
        String json = String.format(
                "{\"serviceUrl\":\"%s\",\"configured\":%b}",
                cfg.getSemanticServiceUrl(),
                cfg.isConfigured()
        );
        return ok(json);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private SemanticServiceProxy proxy() {
        ViewConfigurationService cfg = new ViewConfigurationService(viewContext);
        return new SemanticServiceProxy(
                cfg.getSemanticServiceUrl(),
                cfg.getConnectTimeout(),
                cfg.getReadTimeout()
        );
    }

    private static Response ok(String jsonBody) {
        return Response.ok(jsonBody, MediaType.APPLICATION_JSON).build();
    }

    private static String requestId(HttpHeaders headers) {
        String id = headers.getHeaderString("X-Request-ID");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }
}
