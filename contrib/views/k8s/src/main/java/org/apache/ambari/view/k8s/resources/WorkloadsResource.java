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
import org.apache.ambari.view.k8s.service.KubernetesService;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.DELETE;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

/**
 * Lightweight endpoints to browse cluster workloads (namespaces, pods, services, logs).
 */
@Path("/workloads")
@Produces(MediaType.APPLICATION_JSON)
public class WorkloadsResource {

    private final ViewContext ctx;

    public WorkloadsResource(ViewContext ctx) {
        this.ctx = ctx;
    }

    private KubernetesService kube() {
        return KubernetesService.get(ctx);
    }

    @GET
    @Path("/namespaces")
    public List<KubeNamespace> listNamespaces() {
        return kube().listNamespaces();
    }

    @GET
    @Path("/namespaces/{namespace}/pods")
    public List<KubePod> listPods(@PathParam("namespace") String namespace,
                                  @QueryParam("labelSelector") String labelSelector) {
        return kube().listPods(namespace, labelSelector);
    }

    @GET
    @Path("/namespaces/{namespace}/services")
    public List<KubeServiceDTO> listServices(@PathParam("namespace") String namespace,
                                             @QueryParam("labelSelector") String labelSelector) {
        return kube().listServices(namespace, labelSelector);
    }

    @GET
    @Path("/namespaces/{namespace}/events")
    public List<KubeEventDTO> listEvents(@PathParam("namespace") String namespace,
                                         @QueryParam("labelSelector") String labelSelector) {
        return kube().listEvents(namespace, labelSelector);
    }

    @GET
    @Path("/namespaces/{namespace}/pods/{pod}/events")
    public List<KubeEventDTO> listPodEvents(@PathParam("namespace") String namespace,
                                            @PathParam("pod") String pod) {
        return kube().listPodEvents(namespace, pod);
    }

    @GET
    @Path("/namespaces/{namespace}/pods/{pod}/describe")
    public String describePod(@PathParam("namespace") String namespace,
                              @PathParam("pod") String pod) {
        return kube().describePod(namespace, pod);
    }

    @GET
    @Path("/namespaces/{namespace}/services/{service}/describe")
    public String describeService(@PathParam("namespace") String namespace,
                                  @PathParam("service") String service) {
        return kube().describeService(namespace, service);
    }

    @POST
    @Path("/namespaces/{namespace}/pods/{pod}/restart")
    public void restartPod(@PathParam("namespace") String namespace,
                           @PathParam("pod") String pod) {
        kube().restartPod(namespace, pod);
    }

    @DELETE
    @Path("/namespaces/{namespace}/pods/{pod}")
    public void deletePod(@PathParam("namespace") String namespace,
                          @PathParam("pod") String pod,
                          @QueryParam("graceSeconds") Integer graceSeconds) {
        kube().deletePod(namespace, pod, graceSeconds);
    }

    @GET
    @Path("/namespaces/{namespace}/pods/{pod}/log")
    public String getPodLog(@PathParam("namespace") String namespace,
                            @PathParam("pod") String pod,
                            @QueryParam("container") String container,
                            @QueryParam("tailLines") Integer tailLines) {
        return kube().tailPodLog(namespace, pod, container, tailLines == null ? 200 : tailLines);
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
        return kube().listDeployments(unscope(namespace));
    }

    /**
     * ConfigMap browse list. Only name + key count + total bytes (not values)
     * to keep the response slim for namespaces with hundreds of ConfigMaps.
     */
    @GET
    @Path("/configmaps")
    public List<Map<String, Object>> listConfigMaps(@QueryParam("namespace") String namespace) {
        return kube().listConfigMaps(unscope(namespace));
    }

    /**
     * Ingress browse list. One row per Ingress with hosts[], ingressClass,
     * tlsSecrets count, and address. Used by the Networking tab.
     */
    @GET
    @Path("/ingresses")
    public List<Map<String, Object>> listIngresses(@QueryParam("namespace") String namespace) {
        return kube().listIngresses(unscope(namespace));
    }

    /**
     * OpenShift Route browse list. One row per Route with host, reachable URL, target service,
     * TLS termination and admitted state. Empty on non-OpenShift clusters. On OpenShift charts
     * emit Routes (not Ingresses), so this is where operators find a deployed UI's URL.
     */
    @GET
    @Path("/routes")
    public List<Map<String, Object>> listRoutes(@QueryParam("namespace") String namespace) {
        return kube().listRoutes(unscope(namespace));
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
