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
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.apache.ambari.view.k8s.model.MonitoringDiscoveryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.*;

public class DiscoveryResource {

    private static final Logger LOG = LoggerFactory.getLogger(DiscoveryResource.class);

    @Inject
    private final ViewContext viewContext;

    private final KubernetesService kubernetesService;

    public DiscoveryResource(ViewContext viewContext, KubernetesService kubernetesService) {
        this.viewContext = viewContext;
        this.kubernetesService = kubernetesService;
    }

    @GET
    @Path("/ambari")
    @Produces(MediaType.APPLICATION_JSON)
    public Response discoverAmbariServices(@QueryParam("serviceType") String serviceType,
                                           @Context HttpHeaders headers,
                                           @Context UriInfo ui) {
        LOG.info("Discovering Ambari services for type: {}", serviceType);
        List<Map<String, String>> results = new ArrayList<>();

        if ("POLARIS".equals(serviceType)) {
            try {
                String ambariBase = resolveAmbariBaseUrl(ui.getBaseUri());
                String clusterName = resolveClusterName(ambariBase, headers);
                AmbariActionClient client = new AmbariActionClient(
                        viewContext, ambariBase, clusterName,
                        AmbariActionClient.toAuthHeaders(headers.getRequestHeaders())
                );

                List<String> hosts = client.getComponentHosts(clusterName, "POLARIS", "POLARIS_SERVER");

                // Port: read from polaris-application-properties, fall back to 8181
                String portStr = null;
                try {
                    portStr = client.getDesiredConfigProperty(clusterName, "polaris-application-properties", "quarkus.http.port");
                } catch (Exception ignored) {}
                String port = (portStr != null && !portStr.isBlank()) ? portStr.trim() : "8181";

                for (String host : hosts) {
                    String url = "http://" + host + ":" + port;
                    Map<String, String> item = new HashMap<>();
                    item.put("label", "Polaris (" + host + ":" + port + ")");
                    item.put("value", url);
                    results.add(item);
                }
                if (hosts.isEmpty()) {
                    LOG.warn("No POLARIS_SERVER hosts found in cluster {}", clusterName);
                }
            } catch (Exception e) {
                LOG.error("Failed to discover Polaris", e);
            }
        } else if ("HIVE_SERVER".equals(serviceType)) {
            try {
                String ambariBase = resolveAmbariBaseUrl(ui.getBaseUri());
                String clusterName = resolveClusterName(ambariBase, headers);
                AmbariActionClient client = new AmbariActionClient(
                        viewContext, ambariBase, clusterName,
                        AmbariActionClient.toAuthHeaders(headers.getRequestHeaders())
                );

                List<String> hosts = client.getComponentHosts(clusterName, "HIVE", "HIVE_SERVER");

                String portStr = null;
                try {
                    portStr = client.getDesiredConfigProperty(clusterName, "hive-site", "hive.server2.thrift.port");
                } catch (Exception ignored) {}
                String port = (portStr != null && !portStr.isBlank()) ? portStr.trim() : "10000";

                for (String host : hosts) {
                    Map<String, String> item = new HashMap<>();
                    item.put("label", "HiveServer2 (" + host + ":" + port + ")");
                    item.put("value", host);
                    item.put("port", port);
                    results.add(item);
                }
                if (hosts.isEmpty()) {
                    LOG.warn("No HIVE_SERVER hosts found in cluster {}", clusterName);
                }
            } catch (Exception e) {
                LOG.error("Failed to discover HiveServer2", e);
            }
        } else if ("HIVE_METASTORE".equals(serviceType)) {
            try {
                // 1. Resolve correct Ambari Base URL
                String ambariBase = resolveAmbariBaseUrl(ui.getBaseUri());
                LOG.info("Using Ambari API Base: {}", ambariBase);

                // 2. Resolve Cluster Name
                String clusterName = resolveClusterName(ambariBase, headers);
                LOG.info("Target Cluster Name: {}", clusterName);

                // 3. Setup Client
                AmbariActionClient client = new AmbariActionClient(
                        viewContext,
                        ambariBase,
                        clusterName,
                        AmbariActionClient.toAuthHeaders(headers.getRequestHeaders())
                );

                // 4. Fetch Hive URIs
                String uris = client.getDesiredConfigProperty(clusterName, "hive-site", "hive.metastore.uris");

                if (uris != null && !uris.isBlank()) {
                    String[] uriList = uris.split(",");
                    for (int i = 0; i < uriList.length; i++) {
                        Map<String, String> item = new HashMap<>();
                        String uri = uriList[i].trim();
                        item.put("label", "Hive Metastore (" + uri + ")");
                        item.put("value", uri);
                        results.add(item);
                    }
                } else {
                    LOG.warn("No hive.metastore.uris found in hive-site for cluster {}", clusterName);
                }
            } catch (Exception e) {
                LOG.error("Failed to discover Hive Metastore", e);
                // Return empty list on failure so UI doesn't crash
            }
        }

        return Response.ok(results).build();
    }

    @GET
    @Path("/k8s")
    @Produces(MediaType.APPLICATION_JSON)
    public Response discoverK8sServices(@QueryParam("label") String label) {
        try {
            List<Map<String, String>> services = kubernetesService.listServicesByLabel(label);
            return Response.ok(services).build();
        } catch (Exception e) {
            LOG.error("Failed to discover K8s services", e);
            return Response.serverError().entity(Collections.singletonMap("error", e.getMessage())).build();
        }
    }

    /**
     * Discover monitoring stack (kube-prometheus-stack) deployment to provide namespace/release info to charts.
     * This endpoint only reads cluster state — it never triggers an install.
     * If the service is found healthy after a prior FAILED bootstrap, the state is self-healed to COMPLETED.
     * If the service is not yet found, the stored bootstrap state (RUNNING/FAILED/SKIPPED) is returned so
     * the UI can show meaningful progress rather than a generic 404.
     */
    @GET
    @Path("/monitoring/prometheus")
    @Produces(MediaType.APPLICATION_JSON)
    public Response discoverMonitoringPrometheus() {
        try {
            var info = kubernetesService.discoverAndHealMonitoringState();
            String state = viewContext.getInstanceData("monitoring.bootstrap.state");
            String message = viewContext.getInstanceData("monitoring.bootstrap.message");
            if (info == null) {
                // Bootstrap in progress or not started — return stored state so UI shows RUNNING/FAILED badge.
                return Response.ok(new MonitoringDiscoveryResponse(null, null, null, state, message)).build();
            }
            return Response.ok(new MonitoringDiscoveryResponse(info.namespace(), info.release(), info.url(), state, message)).build();
        } catch (Exception e) {
            LOG.error("Failed to discover monitoring stack", e);
            return Response.serverError().entity(Collections.singletonMap("error", e.getMessage())).build();
        }
    }

    /**
     * Advertise backend feature flags to the UI so it can enable/disable flows (e.g., Flux GitOps).
     * FLUX_GITOPS is enabled when Flux controllers are detected in the cluster (flux-system namespace).
     */
    @GET
    @Path("/features")
    @Produces(MediaType.APPLICATION_JSON)
    public Response discoverFeatures() {
        Map<String, Object> features = new LinkedHashMap<>();
        boolean fluxPresent = isFluxInstalled();
        if (fluxPresent) {
            features.put("supportedDeploymentModes", List.of("DIRECT_HELM", "FLUX_GITOPS"));
            features.put("defaultDeploymentMode", "DIRECT_HELM");
        } else {
            features.put("supportedDeploymentModes", List.of("DIRECT_HELM"));
            features.put("defaultDeploymentMode", "DIRECT_HELM");
        }
        features.put("fluxGitopsSupported", fluxPresent);
        return Response.ok(features).build();
    }

    /**
     * Detect whether Flux is installed by checking for the helm-controller Deployment in flux-system.
     */
    private boolean isFluxInstalled() {
        try {
            var deploy = kubernetesService.getClient()
                    .apps().deployments()
                    .inNamespace("flux-system")
                    .withName("helm-controller")
                    .get();
            return deploy != null;
        } catch (Exception e) {
            LOG.debug("Flux detection failed (cluster may be unconfigured): {}", e.getMessage());
            return false;
        }
    }

    // -- Private Helpers --

    /**
     * Robustly resolves the Ambari API URL.
     * Priority:
     * 1. View Context Property "client.api.url" (Standard Ambari injection)
     * 2. Infer from current Request URI (Strip /views/...)
     * 3. Fallback to localhost
     */
    private String resolveAmbariBaseUrl(URI requestUri) {
        // 1. Use injected Ambari base if available (properly formed)
        String fromConfig = viewContext.getAmbariProperty("client.api.url");
        if (fromConfig != null && !fromConfig.isBlank()) {
            return fromConfig.endsWith("/") ? fromConfig : fromConfig + "/";
        }
        // 2. Derive from current request like other parts of the code do
        // Example in-ambari URI: https://host:8442/api/v1/views/K8S-VIEW/versions/1.0.0.1/...
        String base = requestUri.toString();
        int i = base.indexOf("/api/v1/views/");
        if (i > 0) {
            return base.substring(0, i + "/api/v1".length());
        }
        // 3. As last resort, return the request base (no localhost fallback)
        return base;
    }

    private String resolveClusterName(String ambariApiBase, HttpHeaders headers) throws Exception {
        if (viewContext.getCluster() != null) {
            return viewContext.getCluster().getName();
        }

        // Auto-discovery
        AmbariActionClient client = new AmbariActionClient(
                viewContext,
                ambariApiBase,
                AmbariActionClient.toAuthHeaders(headers.getRequestHeaders())
        );

        List<String> clusters = client.listClusters();
        if (clusters.isEmpty()) throw new IllegalStateException("No clusters found");

        // Prefer "ambari.cluster.preferred" if set
        String preferred = viewContext.getProperties().get("ambari.cluster.preferred");
        if (preferred != null && !preferred.isBlank()) {
            for(String c : clusters) {
                if (c.equalsIgnoreCase(preferred)) return c;
            }
        }

        return clusters.get(0);
    }
}
