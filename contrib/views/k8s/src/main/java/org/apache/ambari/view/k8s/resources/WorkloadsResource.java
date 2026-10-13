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
import org.apache.ambari.view.k8s.model.kube.KubeNamespace;
import org.apache.ambari.view.k8s.model.kube.KubePod;
import org.apache.ambari.view.k8s.model.kube.KubeServiceDTO;
import org.apache.ambari.view.k8s.model.kube.KubeEventDTO;
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.DELETE;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lightweight endpoints to browse cluster workloads (namespaces, pods, services, logs).
 *
 * <p>Operators and admins browse every namespace the view's Kubernetes account can reach. Read-only users only
 * see the namespaces that hold a release deployed from this view, and cannot read pod logs or descriptions, which
 * often show environment values and mounted configuration.
 */
@Path("/workloads")
@Produces(MediaType.APPLICATION_JSON)
public class WorkloadsResource {

    private static final Logger LOG = LoggerFactory.getLogger(WorkloadsResource.class);

    private final ViewContext ctx;
    /** Headers of the request this resource serves: the role check asks Ambari with the caller's credentials. */
    private final HttpHeaders callerHeaders;
    private AuthHelper authHelper;

    /**
     * Creates the resource for one request.
     *
     * @param ctx           active Ambari view context
     * @param callerHeaders headers of the request; {@code null} when unknown
     */
    public WorkloadsResource(ViewContext ctx, HttpHeaders callerHeaders) {
        this.ctx = ctx;
        this.callerHeaders = callerHeaders;
    }

    /** The caller's KDPS role for this request, looked up at most once. */
    private AuthHelper auth() {
        if (authHelper == null) {
            authHelper = new AuthHelper(ctx, callerHeaders);
        }
        return authHelper;
    }

    private KubernetesService kube() {
        return KubernetesService.get(ctx);
    }

    /**
     * The namespaces the current user may browse, or {@code null} when every namespace is allowed. Read-only users
     * get the namespaces of the releases deployed from this view.
     */
    private Set<String> readableNamespaces() {
        if (auth().canWrite()) {
            return null;
        }
        Set<String> namespaces = new HashSet<>();
        for (K8sReleaseEntity release : new ReleaseMetadataService(ctx).findAll()) {
            if (release != null && release.getNamespace() != null) {
                namespaces.add(release.getNamespace());
            }
        }
        return namespaces;
    }

    /** Refuses a namespace the current user may not browse. */
    private void requireReadable(String namespace) {
        requireReadable(namespace, readableNamespaces());
    }

    private static void requireReadable(String namespace, Set<String> readable) {
        if (readable != null && !readable.contains(namespace)) {
            throw AuthHelper.forbidden("Your account has read-only access to KDPS and can only browse the namespaces "
                    + "of releases deployed from KDPS.");
        }
    }

    /**
     * Applies the namespace restriction to a listing: a single namespace must be readable, and an all-namespaces
     * listing only keeps the readable rows.
     */
    private <T> List<T> readableOnly(String namespace, Function<String, List<T>> list, Function<T, String> namespaceOf) {
        Set<String> readable = readableNamespaces();
        if (readable == null) {
            return list.apply(namespace);
        }
        if (namespace != null) {
            requireReadable(namespace, readable);
            return list.apply(namespace);
        }
        return list.apply(null).stream()
                .filter(item -> readable.contains(namespaceOf.apply(item)))
                .collect(Collectors.toList());
    }

    @GET
    @Path("/namespaces")
    public List<KubeNamespace> listNamespaces() {
        Set<String> readable = readableNamespaces();
        List<KubeNamespace> namespaces = kube().listNamespaces();
        if (readable == null) {
            return namespaces;
        }
        return namespaces.stream().filter(n -> readable.contains(n.name)).collect(Collectors.toList());
    }

    @GET
    @Path("/namespaces/{namespace}/pods")
    public List<KubePod> listPods(@PathParam("namespace") String namespace,
                                  @QueryParam("labelSelector") String labelSelector) {
        requireReadable(namespace);
        return kube().listPods(namespace, labelSelector);
    }

    @GET
    @Path("/namespaces/{namespace}/services")
    public List<KubeServiceDTO> listServices(@PathParam("namespace") String namespace,
                                             @QueryParam("labelSelector") String labelSelector) {
        requireReadable(namespace);
        return kube().listServices(namespace, labelSelector);
    }

    @GET
    @Path("/namespaces/{namespace}/events")
    public List<KubeEventDTO> listEvents(@PathParam("namespace") String namespace,
                                         @QueryParam("labelSelector") String labelSelector) {
        requireReadable(namespace);
        return kube().listEvents(namespace, labelSelector);
    }

    @GET
    @Path("/namespaces/{namespace}/pods/{pod}/events")
    public List<KubeEventDTO> listPodEvents(@PathParam("namespace") String namespace,
                                            @PathParam("pod") String pod) {
        requireReadable(namespace);
        return kube().listPodEvents(namespace, pod);
    }

    @GET
    @Path("/namespaces/{namespace}/pods/{pod}/describe")
    public String describePod(@PathParam("namespace") String namespace,
                              @PathParam("pod") String pod) {
        auth().checkWritePermission();
        return kube().describePod(namespace, pod);
    }

    @GET
    @Path("/namespaces/{namespace}/services/{service}/describe")
    public String describeService(@PathParam("namespace") String namespace,
                                  @PathParam("service") String service) {
        auth().checkWritePermission();
        return kube().describeService(namespace, service);
    }

    @POST
    @Path("/namespaces/{namespace}/pods/{pod}/restart")
    public void restartPod(@PathParam("namespace") String namespace,
                           @PathParam("pod") String pod) {
        auth().checkWritePermission();
        LOG.info("Restarting pod {}/{} for user {}", namespace, pod, ctx.getUsername());
        kube().restartPod(namespace, pod);
    }

    /**
     * Deletes a pod. Without {@code graceSeconds} the pod shuts down within its own grace period. A grace period of
     * 0 force-deletes it, which skips the preStop hooks and can leave a StatefulSet pod running twice, so it is
     * refused unless {@code force=true} is sent too.
     */
    @DELETE
    @Path("/namespaces/{namespace}/pods/{pod}")
    public void deletePod(@PathParam("namespace") String namespace,
                          @PathParam("pod") String pod,
                          @QueryParam("graceSeconds") Integer graceSeconds,
                          @QueryParam("force") boolean force) {
        auth().checkWritePermission();
        if (graceSeconds != null && graceSeconds == 0 && !force) {
            String message = "A grace period of 0 force-deletes the pod without letting it shut down. "
                    + "Send force=true to confirm.";
            throw new BadRequestException(message, Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", message))
                    .build());
        }
        LOG.info("Deleting pod {}/{} for user {} (grace period: {})", namespace, pod, ctx.getUsername(),
                graceSeconds == null ? "pod default" : graceSeconds + "s");
        kube().deletePod(namespace, pod, graceSeconds);
    }

    @GET
    @Path("/namespaces/{namespace}/pods/{pod}/log")
    public String getPodLog(@PathParam("namespace") String namespace,
                            @PathParam("pod") String pod,
                            @QueryParam("container") String container,
                            @QueryParam("tailLines") Integer tailLines) {
        auth().checkWritePermission();
        try {
            return kube().tailPodLog(namespace, pod, container, tailLines == null ? 200 : tailLines);
        } catch (Exception ex) {
            // A container that has not started yet (pod Pending / Init / ContainerCreating) or is
            // crash-looping has no readable log stream, and the Kubernetes API errors — the common case
            // while a Helm release is stuck in 'pending-upgrade'. Return a clear, human-readable message
            // (HTTP 200, shown verbatim in the log panel) instead of a raw HTTP 500, so an operator
            // debugging a stuck deploy sees WHY there are no logs rather than an opaque failure.
            String reason = (ex.getMessage() == null || ex.getMessage().isBlank()) ? ex.toString() : ex.getMessage();
            LOG.warn("Pod log fetch failed for {}/{} (container={}): {}", namespace, pod, container, reason);
            return "— No logs available —\n\n"
                    + "Could not read logs for container '" + (container == null || container.isBlank() ? "(default)" : container)
                    + "' in pod " + namespace + "/" + pod + ".\n\n"
                    + "Reason: " + reason + "\n\n"
                    + "The container has most likely not started yet (pod Pending / Init / ContainerCreating) "
                    + "or is crash-looping — often the case while a Helm release is in 'pending-upgrade'. "
                    + "Check the pod's status and events (Describe) to see why it isn't running; "
                    + "logs become available once the container starts.";
        }
    }

    // ---- browse-view listings used by the Workloads & Networking tabs ----

    /**
     * Cluster- or namespace-scoped Deployment list. When the special namespace
     * {@code _all} is given, returns deployments across every namespace —
     * cheaper than asking the UI to fan out one call per namespace and
     * matches the namespace selector's "All namespaces" choice.
     */
    @GET
    @Path("/deployments")
    public List<Map<String, Object>> listDeployments(@QueryParam("namespace") String namespace) {
        return readableOnly(unscope(namespace), ns -> kube().listDeployments(ns), WorkloadsResource::namespaceOf);
    }

    /**
     * ConfigMap browse list. Only name + key count + total bytes (not values)
     * to keep the response slim for namespaces with hundreds of ConfigMaps.
     */
    @GET
    @Path("/configmaps")
    public List<Map<String, Object>> listConfigMaps(@QueryParam("namespace") String namespace) {
        return readableOnly(unscope(namespace), ns -> kube().listConfigMaps(ns), WorkloadsResource::namespaceOf);
    }

    /**
     * Ingress browse list. One row per Ingress with hosts[], ingressClass,
     * tlsSecrets count, and address. Used by the Networking tab.
     */
    @GET
    @Path("/ingresses")
    public List<Map<String, Object>> listIngresses(@QueryParam("namespace") String namespace) {
        return readableOnly(unscope(namespace), ns -> kube().listIngresses(ns), WorkloadsResource::namespaceOf);
    }

    /**
     * OpenShift Route browse list. One row per Route with host, reachable URL, target service,
     * TLS termination and admitted state. Empty on non-OpenShift clusters. On OpenShift charts
     * emit Routes (not Ingresses), so this is where operators find a deployed UI's URL.
     */
    @GET
    @Path("/routes")
    public List<Map<String, Object>> listRoutes(@QueryParam("namespace") String namespace) {
        return readableOnly(unscope(namespace), ns -> kube().listRoutes(ns), WorkloadsResource::namespaceOf);
    }

    private static String namespaceOf(Map<String, Object> row) {
        Object namespace = row == null ? null : row.get("namespace");
        return namespace == null ? null : String.valueOf(namespace);
    }

    /**
     * Normalize the UI's "all namespaces" marker ("*") and empty strings into
     * null so the service-layer helpers do a cluster-wide list. Keeps the URL
     * shape clean (?namespace=* is more readable than omitting the param).
     */
    private String unscope(String ns) {
        if (ns == null || ns.isBlank() || "*".equals(ns)) return null;
        return ns;
    }
}
