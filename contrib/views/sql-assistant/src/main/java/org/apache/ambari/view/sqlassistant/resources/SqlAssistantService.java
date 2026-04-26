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
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.util.HashMap;
import java.util.List;
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

    /**
     * Translates a natural-language question into a SQL statement by forwarding
     * the request body to the semantic service's {@code /api/v1/nl-to-sql} endpoint.
     *
     * @param body    JSON request payload containing the natural-language query and
     *                any dialect/context hints
     * @param headers HTTP request headers; the {@code X-Request-ID} header is
     *                forwarded to the semantic service (generated if absent)
     * @return a 200 response whose entity is the JSON body returned by the semantic
     *         service, containing the generated SQL and metadata
     */
    @POST
    @Path("nl-to-sql")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response nlToSql(String body, @Context HttpHeaders headers,
                            @Context HttpServletRequest request) {
        String requestId = requestId(headers);
        LOG.debug("nl-to-sql requestId={}", requestId);
        String result = proxy(request).post("/api/v1/nl-to-sql", body, requestId);
        return ok(result);
    }

    /**
     * Executes a SQL statement by forwarding the request body to the semantic
     * service's {@code /api/v1/execute} endpoint and returning the query results.
     *
     * @param body    JSON request payload containing the SQL to execute and any
     *                execution options (catalog, namespace, row limit, etc.)
     * @param headers HTTP request headers; the {@code X-Request-ID} header is
     *                forwarded to the semantic service (generated if absent)
     * @return a 200 response whose entity is the JSON body returned by the semantic
     *         service, containing the result set and execution metadata
     */
    @POST
    @Path("execute")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response execute(String body, @Context HttpHeaders headers,
                            @Context HttpServletRequest request) {
        String requestId = requestId(headers);
        String result = proxy(request).post("/api/v1/execute", body, requestId);
        return ok(result);
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    /**
     * Lists all catalogs available in the configured data source by proxying
     * to the semantic service's {@code GET /api/v1/schema/catalogs} endpoint.
     *
     * @param headers HTTP request headers; the {@code X-Request-ID} header is
     *                forwarded to the semantic service (generated if absent)
     * @return a 200 response whose entity is the JSON array of catalog names
     */
    @GET
    @Path("schema/catalogs")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listCatalogs(@Context HttpHeaders headers,
                                 @Context HttpServletRequest request) {
        String result = proxy(request).get("/api/v1/schema/catalogs", null, requestId(headers));
        return ok(result);
    }

    /**
     * Lists all namespaces (databases/schemas) within the specified catalog by
     * proxying to {@code GET /api/v1/schema/catalogs/{catalog}/namespaces}.
     *
     * @param catalog the catalog name whose namespaces should be listed
     * @param headers HTTP request headers; the {@code X-Request-ID} header is
     *                forwarded to the semantic service (generated if absent)
     * @return a 200 response whose entity is the JSON array of namespace names
     */
    @GET
    @Path("schema/catalogs/{catalog}/namespaces")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listNamespaces(@PathParam("catalog") String catalog,
                                   @Context HttpHeaders headers,
                                   @Context HttpServletRequest request) {
        String result = proxy(request).get(
                "/api/v1/schema/catalogs/" + catalog + "/namespaces",
                null, requestId(headers));
        return ok(result);
    }

    /**
     * Lists all tables within the specified catalog and namespace by proxying to
     * {@code GET /api/v1/schema/catalogs/{catalog}/namespaces/{namespace}/tables}.
     *
     * @param catalog   the catalog that contains the target namespace
     * @param namespace the namespace (database/schema) whose tables should be listed
     * @param headers   HTTP request headers; the {@code X-Request-ID} header is
     *                  forwarded to the semantic service (generated if absent)
     * @return a 200 response whose entity is the JSON array of table names
     */
    @GET
    @Path("schema/catalogs/{catalog}/namespaces/{namespace}/tables")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listTables(@PathParam("catalog") String catalog,
                               @PathParam("namespace") String namespace,
                               @Context HttpHeaders headers,
                               @Context HttpServletRequest request) {
        String result = proxy(request).get(
                "/api/v1/schema/catalogs/" + catalog + "/namespaces/" + namespace + "/tables",
                null, requestId(headers));
        return ok(result);
    }

    /**
     * Retrieves the column schema for a specific table by proxying to
     * {@code GET /api/v1/schema/catalogs/{catalog}/namespaces/{namespace}/tables/{table}}.
     *
     * @param catalog   the catalog that contains the target namespace
     * @param namespace the namespace (database/schema) that contains the target table
     * @param table     the table whose column definitions should be returned
     * @param headers   HTTP request headers; the {@code X-Request-ID} header is
     *                  forwarded to the semantic service (generated if absent)
     * @return a 200 response whose entity is a JSON object describing the table's columns
     */
    @GET
    @Path("schema/catalogs/{catalog}/namespaces/{namespace}/tables/{table}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTableSchema(@PathParam("catalog") String catalog,
                                   @PathParam("namespace") String namespace,
                                   @PathParam("table") String table,
                                   @Context HttpHeaders headers,
                                   @Context HttpServletRequest request) {
        String result = proxy(request).get(
                "/api/v1/schema/catalogs/" + catalog + "/namespaces/" + namespace + "/tables/" + table,
                null, requestId(headers));
        return ok(result);
    }

    /**
     * Triggers a full schema refresh on the semantic service by posting to
     * {@code /api/v1/schema/refresh}, causing it to re-introspect the data source.
     *
     * @param headers HTTP request headers; the {@code X-Request-ID} header is
     *                forwarded to the semantic service (generated if absent)
     * @return a 200 response whose entity is the JSON confirmation from the semantic service
     */
    @POST
    @Path("schema/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    public Response refreshSchema(@Context HttpHeaders headers,
                                  @Context HttpServletRequest request) {
        String result = proxy(request).post("/api/v1/schema/refresh", "{}", requestId(headers));
        return ok(result);
    }

    // ── History ───────────────────────────────────────────────────────────────

    /**
     * Returns a paginated, optionally filtered list of past NL-to-SQL requests by
     * proxying to the semantic service's {@code GET /api/v1/history} endpoint.
     * Only non-null query parameters are forwarded.
     *
     * @param limit   maximum number of history entries to return; omit for service default
     * @param offset  zero-based index of the first entry to return; omit to start from the beginning
     * @param search  optional free-text filter applied to history entry content
     * @param dialect optional SQL dialect filter (e.g. {@code hive}, {@code spark})
     * @param headers HTTP request headers; the {@code X-Request-ID} header is
     *                forwarded to the semantic service (generated if absent)
     * @return a 200 response whose entity is a JSON object containing the history entries
     *         and total-count metadata
     */
    @GET
    @Path("history")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listHistory(@QueryParam("limit") Integer limit,
                                @QueryParam("offset") Integer offset,
                                @QueryParam("search") String search,
                                @QueryParam("dialect") String dialect,
                                @Context HttpHeaders headers,
                                @Context HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        if (limit != null)  params.put("limit", String.valueOf(limit));
        if (offset != null) params.put("offset", String.valueOf(offset));
        if (search != null) params.put("search", search);
        if (dialect != null) params.put("dialect", dialect);
        String result = proxy(request).get("/api/v1/history", params, requestId(headers));
        return ok(result);
    }

    /**
     * Deletes a single query history entry identified by its ID by proxying to
     * the semantic service's {@code DELETE /api/v1/history/{id}} endpoint.
     *
     * @param id      the unique identifier of the history entry to delete
     * @param headers HTTP request headers; the {@code X-Request-ID} header is
     *                forwarded to the semantic service (generated if absent)
     * @return a 200 response whose entity is the JSON confirmation from the semantic service
     */
    @DELETE
    @Path("history/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteHistoryEntry(@PathParam("id") String id,
                                       @Context HttpHeaders headers,
                                       @Context HttpServletRequest request) {
        String result = proxy(request).delete("/api/v1/history/" + id, requestId(headers));
        return ok(result);
    }

    /**
     * Removes all query history entries by proxying to the semantic service's
     * {@code DELETE /api/v1/history} endpoint.
     *
     * @param headers HTTP request headers; the {@code X-Request-ID} header is
     *                forwarded to the semantic service (generated if absent)
     * @return a 200 response whose entity is the JSON confirmation from the semantic service
     */
    @DELETE
    @Path("history")
    @Produces(MediaType.APPLICATION_JSON)
    public Response clearHistory(@Context HttpHeaders headers,
                                 @Context HttpServletRequest request) {
        String result = proxy(request).delete("/api/v1/history", requestId(headers));
        return ok(result);
    }

    // ── Health / Config ───────────────────────────────────────────────────────

    /**
     * Checks the liveness of the semantic service by proxying to its
     * {@code GET /health} endpoint.
     *
     * @param headers HTTP request headers; the {@code X-Request-ID} header is
     *                forwarded to the semantic service (generated if absent)
     * @return a 200 response whose entity is the health-check JSON from the semantic
     *         service; a non-2xx status from the service is re-raised as an HTTP error
     */
    @GET
    @Path("health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health(@Context HttpHeaders headers,
                           @Context HttpServletRequest request) {
        String result = proxy(request).get("/health", null, requestId(headers));
        return ok(result);
    }

    /**
     * Returns the runtime configuration of the semantic service by proxying to
     * its {@code GET /api/v1/config} endpoint.
     *
     * @param headers HTTP request headers; the {@code X-Request-ID} header is
     *                forwarded to the semantic service (generated if absent)
     * @return a 200 response whose entity is the JSON configuration object from
     *         the semantic service
     */
    @GET
    @Path("config")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfig(@Context HttpHeaders headers,
                              @Context HttpServletRequest request) {
        String result = proxy(request).get("/api/v1/config", null, requestId(headers));
        return ok(result);
    }

    /**
     * Returns the Ambari view instance configuration as seen by this Java backend,
     * including the resolved semantic service URL and a boolean indicating whether
     * the view has been configured with a non-default URL.
     *
     * <p>This endpoint reads from Ambari's {@link ViewContext} directly and does
     * not make any outbound call to the semantic service.
     *
     * @return a 200 response with a JSON body of the form
     *         {@code {"serviceUrl":"...","configured":true|false}}
     */
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

    /**
     * Builds a {@link SemanticServiceProxy} for the current request, extracting the Ambari
     * JWT cookie and the authenticated username so they can be forwarded to the Python service.
     *
     * <p>The JWT value (if present) is sent as {@code Authorization: Bearer <token>}, enabling
     * the Python service to pass it on to Polaris and Trino for per-user identity enforcement.
     * When no cookie is present — e.g. during a non-SSO session — both token and user are
     * {@code null} and the Python service falls back to its configured service credentials.
     *
     * @param request the current servlet request, used to locate the JWT cookie and username
     * @return a configured proxy instance for this request
     */
    private SemanticServiceProxy proxy(HttpServletRequest request) {
        ViewConfigurationService cfg = new ViewConfigurationService(viewContext);
        String jwtCookieName = cfg.getJwtCookieName();
        String bearerToken = null;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (jwtCookieName.equals(cookie.getName())) {
                    bearerToken = cookie.getValue();
                    break;
                }
            }
        }
        String ambariUser = request.getRemoteUser();
        return new SemanticServiceProxy(
                cfg.getSemanticServiceUrl(),
                cfg.getConnectTimeout(),
                cfg.getReadTimeout(),
                bearerToken,
                ambariUser
        );
    }

    private static Response ok(String jsonBody) {
        return Response.ok(jsonBody, MediaType.APPLICATION_JSON).build();
    }

    private static String requestId(HttpHeaders headers) {
        List<String> vals = headers.getRequestHeader("X-Request-ID");
        String id = (vals != null && !vals.isEmpty()) ? vals.get(0) : null;
        return (id != null && !id.isEmpty()) ? id : UUID.randomUUID().toString();
    }
}
