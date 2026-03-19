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
}
