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
import org.apache.ambari.view.k8s.model.ContextRequest;
import org.apache.ambari.view.k8s.model.ResolvedContext;
import org.apache.ambari.view.k8s.service.ContextService;
import org.apache.ambari.view.k8s.service.ServiceAdvisorService;
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
            // The local cluster name is only needed to resolve the MANAGED default; EXTERNAL and
            // REMOTE contexts carry their own. Don't let a local-cluster lookup failure (e.g. a
            // view instance that isn't bound to a cluster) block resolving those.
            String cluster = null;
            try {
                cluster = resolveClusterName(headers);
            } catch (Exception ce) {
                LOG.warn("resolved({}): could not resolve local cluster name (fine for EXTERNAL/REMOTE): {}", id, ce.toString());
            }
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

    /**
     * Recommend on/off defaults for a service's advisor-tagged toggles, given the capabilities
     * the selected context resolves. Body: {@code {"service": "...", "fields": [{"name": "...",
     * "advisor": "..."}]}}. Capabilities are detected server-side from the resolved context (the
     * caller cannot spoof them). Best-effort: always returns 200 with a (possibly empty)
     * {@code recommendations} list — advice never blocks the wizard.
     */
    @POST
    @Path("{id}/advice")
    public Response advice(@PathParam("id") String id, java.util.Map<String, Object> body,
                           @Context HttpHeaders headers) {
        try {
            ContextService svc = new ContextService(viewContext);
            String cluster = resolveClusterName(headers);
            AmbariActionClient client = ambariClient(headers);
            ResolvedContext rc = svc.resolve(id, client, cluster);

            String service = body == null ? null : java.util.Objects.toString(body.get("service"), null);
            List<java.util.Map<String, String>> fields = new ArrayList<>();
            Object rawFields = body == null ? null : body.get("fields");
            if (rawFields instanceof List) {
                for (Object o : (List<?>) rawFields) {
                    if (!(o instanceof java.util.Map)) {
                        continue;
                    }
                    java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
                    Object name = m.get("name");
                    Object advisor = m.get("advisor");
                    if (name != null && advisor != null) {
                        java.util.Map<String, String> f = new java.util.LinkedHashMap<>();
                        f.put("name", String.valueOf(name));
                        f.put("advisor", String.valueOf(advisor));
                        fields.add(f);
                    }
                }
            }
            List<ServiceAdvisorService.Recommendation> recs =
                    new ServiceAdvisorService().advise(service, rc, fields);
            return Response.ok(java.util.Map.of("recommendations", recs)).build();
        } catch (Exception e) {
            LOG.warn("Advice failed for context {}: {}", id, e.toString());
            return Response.ok(java.util.Map.of("recommendations", java.util.Collections.emptyList())).build();
        }
    }

    /**
     * Preflight check for a context's remote Ranger/Atlas. Used when the operator disabled automatic
     * configuration ({@code autoConfigureRemote=false}) and set those components up themselves — it
     * verifies reachability + admin auth (and lists Ranger service repos) so they can confirm it's
     * correct. Read-only; never mutates. Returns {@code {ok, checks:[{component, ok, message, ...}]}}.
     */
    @POST
    @Path("{id}/preflight")
    public Response preflight(@PathParam("id") String id, @Context HttpHeaders headers) {
        try {
            ContextService svc = new ContextService(viewContext);
            String cluster = resolveClusterName(headers);
            AmbariActionClient client = ambariClient(headers);
            return Response.ok(svc.preflight(id, client, cluster)).build();
        } catch (Exception e) {
            LOG.warn("Preflight failed for context {}: {}", id, e.toString());
            return Response.ok(java.util.Map.of("ok", false, "error", String.valueOf(e.getMessage()))).build();
        }
    }

    /**
     * Live "test connection &amp; list clusters" for a remote Ambari, used by the Contexts UI before
     * a REMOTE context is saved. Body: {@code {"remoteAmbariUrl": "...", "remoteUsername": "...",
     * "remotePassword": "...", "verifySsl": true|false}}. Returns {@code {ok, clusters[],
     * ambariVersion}} on success or {@code {ok:false, error}} with a friendly message. The password
     * is used only to authenticate this probe — never persisted nor echoed.
     */
    @POST
    @Path("probe-remote")
    public Response probeRemote(java.util.Map<String, Object> body) {
        try {
            String url = body == null ? null : java.util.Objects.toString(body.get("remoteAmbariUrl"), null);
            String user = body == null ? null : java.util.Objects.toString(body.get("remoteUsername"), null);
            String pass = body == null ? null : java.util.Objects.toString(body.get("remotePassword"), null);
            // verifySsl defaults to true unless explicitly false.
            boolean verifySsl = body == null || !"false".equalsIgnoreCase(
                    java.util.Objects.toString(body.get("verifySsl"), "true"));
            java.util.Map<String, Object> result =
                    new ContextService(viewContext).probeRemote(url, user, pass, verifySsl);
            return Response.ok(result).build();
        } catch (Exception e) {
            LOG.warn("probe-remote failed: {}", e.toString());
            return Response.ok(java.util.Map.of("ok", false, "error", String.valueOf(e.getMessage()))).build();
        }
    }

    /**
     * Live "test connection &amp; list clusters" for a Cloudera Manager, used by the Contexts UI before
     * a CDP context is saved. Body: {@code {"cmUrl": "...", "cmUsername": "...", "cmPassword": "...",
     * "verifySsl": true|false}}. Returns {@code {ok, clusters[], cmVersion, apiVersion}} or
     * {@code {ok:false, error}}. The password authenticates only this probe — never persisted.
     */
    @POST
    @Path("probe-cdp")
    public Response probeCdp(java.util.Map<String, Object> body) {
        try {
            String url = body == null ? null : java.util.Objects.toString(body.get("cmUrl"), null);
            String user = body == null ? null : java.util.Objects.toString(body.get("cmUsername"), null);
            String pass = body == null ? null : java.util.Objects.toString(body.get("cmPassword"), null);
            boolean verifySsl = body != null && "true".equalsIgnoreCase(
                    java.util.Objects.toString(body.get("verifySsl"), "false"));
            java.util.Map<String, Object> result =
                    new ContextService(viewContext).probeCdp(url, user, pass, verifySsl);
            return Response.ok(result).build();
        } catch (Exception e) {
            LOG.warn("probe-cdp failed: {}", e.toString());
            return Response.ok(java.util.Map.of("ok", false, "error", String.valueOf(e.getMessage()))).build();
        }
    }

    @POST
    public Response save(ContextRequest request) {
        try {
            KdpsContextEntity saved = new ContextService(viewContext).save(request);
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
