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

package org.apache.ambari.view.k8s;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.google.common.annotations.VisibleForTesting;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.UserPermissions;
import org.apache.ambari.view.k8s.resources.*;
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ViewConfigurationService;
import org.apache.ambari.view.k8s.service.StackDefinitionService;
import org.apache.ambari.view.k8s.service.GlobalConfigService;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;

import org.apache.ambari.view.k8s.utils.AmbariAliasResolver;
import org.apache.ambari.view.k8s.utils.WebHookBootstrap;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;

import java.util.List;
import java.util.Map;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

/**
 * Main REST service for the K8s view providing cluster operations and configuration management
 */
@Path("/")
public class KubeService {

    private static final Logger LOG = LoggerFactory.getLogger(KubeService.class);

    @Inject
    private ViewContext viewContext;

    private KubernetesService kubernetesService;
    private ViewConfigurationService configService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private AmbariAliasResolver ambariAliasResolver;
    private StackDefinitionService stackDefinitionService;
    private GlobalConfigService globalConfigService = new GlobalConfigService();

    private KubernetesService getKubernetesService() {
        if (kubernetesService == null) {
            this.kubernetesService = KubernetesService.get(viewContext);
        }
        return this.kubernetesService;
    }
    
    private ViewConfigurationService getConfigService() {
        if (configService == null) {
            this.configService = new ViewConfigurationService(this.viewContext);
        }
        return this.configService;
    }

    private AmbariAliasResolver getAmbariAliasResolver(){
        if (ambariAliasResolver == null){
            this.ambariAliasResolver = new AmbariAliasResolver(this.viewContext);
        }
        return this.ambariAliasResolver;
    };




    @VisibleForTesting
    void setKubernetesService(KubernetesService kubernetesService) {
        this.kubernetesService = kubernetesService;
    }

    /**
     * Endpoint to get the list of available charts.
     * Returns the chart data directly as JSON.
     * @return A map representing the available charts.
     * @throws IOException if the charts.json file cannot be read.
     */
    @GET
    @Path("/charts/available")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAvailableCharts() throws IOException {
        String availableCharts = getKubernetesService().loadAvailableCharts();
        return Response.ok(availableCharts).build();
    }

    /**
     * Alias endpoint to list stack services discovered in KDPS/services.
     * Kept at /services for UI compatibility.
     */
    @GET
    @Path("/services")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listStackServices() {
        return Response.ok(getStackDefinitionService().listServiceDefinitions()).build();
    }

    @GET
    @Path("/services/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStackService(@PathParam("name") String name) {
        try {
            return Response.ok(getStackDefinitionService().getServiceDefinition(name)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("/services/{name}/configurations")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStackServiceConfigs(@PathParam("name") String name) {
        try {
            return Response.ok(getStackDefinitionService().getServiceConfigurations(name)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("/users/me/permissions")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCurrentUserPermissions() {
        AuthHelper authHelper = new AuthHelper(viewContext);
        return Response.ok(authHelper.getPermissions()).build();
    }

    @POST
    @Path("/cluster/config")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response uploadKubeconfig(@Context HttpHeaders headers,
                                     @Context UriInfo ui, InputStream fileInputStream) {
        new AuthHelper(viewContext).checkConfigurationPermission();

        try {
            ViewConfigurationService configurationService = this.getConfigService();

            LOG.info("/cluster/config: Received kubeconfig upload request.");
            // Use a static filename since it's no longer provided by the request
            File configurationFile = configurationService.saveKubeconfigFile(fileInputStream, "kubeconfig.yaml");

            LOG.info("Kubeconfig successfully saved to {}", configurationFile.getAbsolutePath());
            LOG.info("Configuring Apache Ambari View Backend CA bundle");
            final String webhookName = "keytab-webhook"; // must match your Helm values prefix

            try {
                // Reinitialize K8s client now that kubeconfig is saved
                this.getKubernetesService().reloadClientIfConfigured();
                WebHookBootstrap.prepareWebhookPrereqs(
                        this.viewContext,
                        this.getKubernetesService(),
                        webhookName,
                        headers.getRequestHeaders()
                );
                // We NOT install the chart here. This only prepares secrets + namespace + caBundle.
                // Later, when we deploy the webhook chart, collect all ambari.properties overrides
                // under k8s.view.webhooks.<webhookName>.* and pass them straight to Helm.
            } catch (Exception ex) {
                // Decide your policy: either fail fast or keep the View up and show an actionable error.
                // Failing fast is often better so the admin knows to fix TLS prerequisites.
                throw new IllegalStateException("Failed preparing webhook prerequisites", ex);
            }
            return Response.ok(Collections.singletonMap("message", "Configuration saved.")).build();

        } catch (IOException e) {
            LOG.error("Error while saving uploaded kubeconfig file", e);
            return Response.serverError().entity("Error while saving the file.").build();
        }
    }

    /**
     * List the contexts available in the uploaded kubeconfig so the operator can choose which
     * cluster/context this view instance targets.
     */
    @GET
    @Path("/cluster/contexts")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listKubeconfigContexts() {
        new AuthHelper(viewContext).checkConfigurationPermission();
        try {
            return Response.ok(this.getKubernetesService().listAvailableContexts()).build();
        } catch (Exception e) {
            LOG.warn("/cluster/contexts: failed to list kubeconfig contexts: {}", e.toString());
            return Response.ok(Collections.emptyList()).build();
        }
    }

    /**
     * Persist the operator's chosen kubeconfig context for this view instance and rebuild the
     * Kubernetes client against it. Body: {@code {"context": "<name>"}} (null/blank ⇒ current-context).
     */
    @POST
    @Path("/cluster/context")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response selectKubeconfigContext(java.util.Map<String, String> body) {
        new AuthHelper(viewContext).checkConfigurationPermission();
        String context = body == null ? null : body.get("context");
        LOG.info("/cluster/context: selecting kubeconfig context '{}'.", context);
        this.getConfigService().saveSelectedContext(context);
        try {
            this.getKubernetesService().reloadClientIfConfigured();
        } catch (Exception e) {
            LOG.warn("/cluster/context: client reload after context selection failed: {}", e.toString());
        }
        return Response.ok(Collections.singletonMap(
                "message", "Context set to: " + (context == null || context.isBlank() ? "current-context" : context))).build();
    }

    /**
     * Configure the view to connect to OpenShift with a username/password (for clusters where only
     * console login is available and terminal kubeconfig tokens expire). The view mints and auto-renews
     * an API token from these credentials. Body: {apiUrl, username, password, caData?, insecure?}.
     */
    @POST
    @Path("/cluster/openshift-login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveOpenShiftLogin(java.util.Map<String, Object> body) {
        new AuthHelper(viewContext).checkConfigurationPermission();
        if (body == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Collections.singletonMap("error", "missing body")).build();
        }
        String apiUrl = body.get("apiUrl") == null ? null : String.valueOf(body.get("apiUrl")).trim();
        String username = body.get("username") == null ? null : String.valueOf(body.get("username")).trim();
        String password = body.get("password") == null ? null : String.valueOf(body.get("password"));
        String caData = body.get("caData") == null ? null : String.valueOf(body.get("caData"));
        boolean insecure = Boolean.parseBoolean(String.valueOf(body.get("insecure")));
        if (apiUrl == null || apiUrl.isBlank() || username == null || username.isBlank() || password == null || password.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Collections.singletonMap("error", "apiUrl, username and password are required")).build();
        }
        LOG.info("/cluster/openshift-login: configuring OpenShift login for user '{}' at {}", username, apiUrl);
        this.getConfigService().saveOpenShiftLogin(apiUrl, username, password, caData, insecure);
        try {
            boolean ok = this.getKubernetesService().forceReloadClient();
            if (!ok) {
                return Response.status(502)
                        .entity(Collections.singletonMap("error", "Saved, but could not connect — check URL/credentials and that the cluster allows password login (oc login -u -p).")).build();
            }
        } catch (Exception e) {
            LOG.warn("/cluster/openshift-login: client reload failed: {}", e.toString());
            return Response.status(502).entity(Collections.singletonMap("error", e.getMessage())).build();
        }
        return Response.ok(Collections.singletonMap("message", "OpenShift login configured for " + username)).build();
    }

    /**
     * Dry-run test of an OpenShift username/password login WITHOUT persisting anything: mints a token
     * from the OAuth server and confirms it works. Lets the UI gate "Connect" on a successful test.
     * Body: {apiUrl, username, password, caData?, insecure?}. Returns {ok, message|error}.
     */
    @POST
    @Path("/cluster/openshift-login/test")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response testOpenShiftLogin(java.util.Map<String, Object> body) {
        new AuthHelper(viewContext).checkConfigurationPermission();
        if (body == null) {
            return Response.ok(Map.of("ok", false, "error", "missing body")).build();
        }
        String apiUrl = body.get("apiUrl") == null ? null : String.valueOf(body.get("apiUrl")).trim();
        String username = body.get("username") == null ? null : String.valueOf(body.get("username")).trim();
        String password = body.get("password") == null ? null : String.valueOf(body.get("password"));
        String caData = body.get("caData") == null ? null : String.valueOf(body.get("caData"));
        boolean insecure = Boolean.parseBoolean(String.valueOf(body.get("insecure")));
        if (apiUrl == null || apiUrl.isBlank() || username == null || username.isBlank() || password == null || password.isEmpty()) {
            return Response.ok(Map.of("ok", false, "error", "apiUrl, username and password are required")).build();
        }
        try {
            org.apache.ambari.view.k8s.service.OpenShiftLoginProvider provider =
                    new org.apache.ambari.view.k8s.service.OpenShiftLoginProvider(apiUrl, username, password, caData, insecure);
            String token = provider.refresh();
            if (token == null || token.isBlank()) {
                return Response.ok(Map.of("ok", false, "error",
                        "Could not obtain a token. Check the URL/credentials, and that the cluster allows password login (oc login -u -p).")).build();
            }
            return Response.ok(Map.of("ok", true, "message", "Authenticated to OpenShift as " + username + ".")).build();
        } catch (Exception e) {
            return Response.ok(Map.of("ok", false, "error", e.getMessage() == null ? e.toString() : e.getMessage())).build();
        }
    }

    /** Current view-wide outbound proxy settings (password never returned — only whether one is set). */
    @GET
    @Path("/cluster/proxy")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getProxySettings() {
        new AuthHelper(viewContext).checkConfigurationPermission();
        ViewConfigurationService cfg = getConfigService();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("enabled", cfg.isProxyEnabled());
        out.put("url", cfg.getProxyUrl() == null ? "" : cfg.getProxyUrl());
        out.put("username", cfg.getProxyUsername() == null ? "" : cfg.getProxyUsername());
        out.put("noProxy", cfg.getProxyNoProxy() == null ? "" : cfg.getProxyNoProxy());
        out.put("passwordSet", cfg.getProxyUsername() != null && !cfg.getProxyUsername().isBlank());
        return Response.ok(out).build();
    }

    /**
     * Save the view-wide outbound proxy (for reaching internet Helm/Git repos from behind a corporate
     * proxy) and apply it immediately. The Kubernetes API always stays direct. Body:
     * {enabled, url, username?, password?, noProxy?}.
     */
    @POST
    @Path("/cluster/proxy")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveProxySettings(java.util.Map<String, Object> body) {
        new AuthHelper(viewContext).checkConfigurationPermission();
        if (body == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Collections.singletonMap("error", "missing body")).build();
        }
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        String url = body.get("url") == null ? null : String.valueOf(body.get("url")).trim();
        String username = body.get("username") == null ? null : String.valueOf(body.get("username")).trim();
        String password = body.get("password") == null ? null : String.valueOf(body.get("password"));
        String noProxy = body.get("noProxy") == null ? null : String.valueOf(body.get("noProxy"));
        if (enabled && (url == null || url.isBlank())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Collections.singletonMap("error", "Proxy URL is required when the proxy is enabled")).build();
        }
        this.getConfigService().saveProxySettings(enabled, url, username, password, noProxy);
        try { this.getKubernetesService().applyProxySettings(); } catch (Exception e) {
            LOG.warn("/cluster/proxy: failed to apply proxy: {}", e.toString());
        }
        return Response.ok(Collections.singletonMap("message", enabled ? "Outbound proxy enabled" : "Outbound proxy disabled")).build();
    }

    /** Switch the auth mode (e.g. "kubeconfig" to go back to a previously-uploaded kubeconfig). */
    @POST
    @Path("/cluster/auth-mode")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setAuthMode(java.util.Map<String, String> body) {
        new AuthHelper(viewContext).checkConfigurationPermission();
        String mode = body == null ? null : body.get("mode");
        this.getConfigService().setAuthMode(mode);
        try { this.getKubernetesService().forceReloadClient(); } catch (Exception e) {
            LOG.warn("/cluster/auth-mode: reload failed: {}", e.toString());
        }
        return Response.ok(Collections.singletonMap("message", "Auth mode set to " + this.getConfigService().getAuthMode())).build();
    }

    @GET
    @Path("/cluster/configured")
    @Produces(MediaType.APPLICATION_JSON)
    public Response checkIsViewConfigured(InputStream fileInputStream) {
        ViewConfigurationService configurationService = this.getConfigService();
        boolean isConfigured = configurationService.isConfigured();
        LOG.info("/cluster/configured: Received request to check if the view is configured: {}", isConfigured);
        if (!isConfigured) {
            LOG.warn("View is not configured. Returning 'unconfigured' status.");
            return Response.status(Response.Status.PRECONDITION_FAILED).entity("UNCONFIGURED").build();
        } else {
            LOG.info("View is configured. Returning 'OK' status.");
            return Response.ok(Collections.singletonMap("message", "CONFIGURED")).build();
        }
    }
    
    @GET
    @Path("/cluster/stats")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getClusterStats(@QueryParam("forceRefresh") @DefaultValue("false") boolean forceRefresh) {
        try {
            return Response.ok(getKubernetesService().getClusterStats(forceRefresh)).build();
        } catch (Exception e) {
            return handleError(e);
        }
    }
    
    @GET
    @Path("/nodes")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getNodes(@QueryParam("limit") @DefaultValue("200") int limit,
                             @QueryParam("offset") @DefaultValue("0") int offset) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var ks = getKubernetesService();
            var nodes = ks.getNodes(limit, offset);
            int total = ks.countNodes();
            return Response.ok(mapper.writeValueAsString(Map.of("items", nodes, "total", total))).build();
        } catch (Exception e) {
            return handleError(e);
        }
    }

    // @GET
    // @Path("/helm/releases")
    // @Produces(MediaType.APPLICATION_JSON)
    // public Response getHelmReleases() {
    //     try {
    //         return Response.ok(getKubernetesService().getHelmReleases()).build();
    //     } catch (Exception e) {
    //         return handleError(e);
    //     }
    // }

    @GET
    @Path("/cluster/events")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getClusterEvents() {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return Response.ok(mapper.writeValueAsString(getKubernetesService().getClusterEvents())).build();
        } catch (Exception e) {
            return handleError(e);
        }
    }

    @GET
    @Path("/cluster/componentstatuses")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getComponentStatuses() {
        try {
            return Response.ok(getKubernetesService().getComponentStatuses()).build();
        } catch (Exception e) {
            return handleError(e);
        }
    }

    /**
     * Workloads sub-resource: namespaces/pods/services/logs.
     */
    @Path("/workloads")
    public WorkloadsResource getWorkloads() {
        return new WorkloadsResource(viewContext);
    }

    /**
     * @see org.apache.ambari.view.k8s.resources.HelmRepoResource
     * @return service
     */
    @Path("/helm/repos")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public HelmRepoResource helmRepos() {
        return new HelmRepoResource(viewContext);
    }

    private Response handleError(Exception e) {
        if (e instanceof IllegalStateException) {
            LOG.warn("Attempt to access resource without configuration: {}", e.getMessage());
            return Response.status(Response.Status.PRECONDITION_FAILED).entity(e.getMessage()).build();
        }
        LOG.error("Unexpected Kubernetes API error", e);
        return Response.serverError().entity(e.getMessage()).build();
    }
    
    /** Sub-resource for /helm/* under /resources/api */
    @Path("/helm")
    public HelmResource helm() {
        // Pass the ViewContext so HelmResource can access DataStore, properties, etc.
        return new HelmResource(viewContext);
    }

    @Path("commands")
    public CommandResource commands() {
        return new CommandResource(viewContext, this.getAmbariAliasResolver());
    }


    /**
     * Sub-resource for Service Discovery
     * URL: /api/v1/.../resources/api/discovery
     */
    @Path("/discovery")
    public DiscoveryResource discovery() {
        // Pass dependencies
        return new DiscoveryResource(viewContext, getKubernetesService());
    }

    /**
     * Sub-resource for Cluster Capabilities (cert-manager / external-secrets / OpenShift detection).
     * URL: /api/v1/.../resources/api/cluster/capabilities
     */
    @Path("/cluster/capabilities")
    public ClusterCapabilitiesResource clusterCapabilities() {
        return new ClusterCapabilitiesResource(getKubernetesService());
    }

    /**
     * Sub-resource for Configuration Management
     * URL: /api/v1/.../resources/api/configurations
     */
    @Path("/configurations")
    public ConfigurationResource configuration() {
        // Pass dependencies
        return new ConfigurationResource(viewContext, getKubernetesService());
    }

    /**
     * Sub-resource for the Company Issuing CA registry (PKI). Backs the
     * {@code signedByCompanyCA} ingress-TLS mode by holding admin-uploaded CAs
     * as K8s Secrets in the {@code ambari-pki} namespace.
     * URL: /api/v1/.../resources/api/pki/cas
     */
    @Path("/pki/cas")
    public org.apache.ambari.view.k8s.resources.CaRegistryResource caRegistry() {
        return new org.apache.ambari.view.k8s.resources.CaRegistryResource(viewContext, getKubernetesService());
    }

    /**
     * Platform contexts: named ODP environments (Atlas/Ranger/Kerberos endpoints + auth)
     * that KDPS services integrate against. The managed default is auto-seeded from the
     * Ambari-managed cluster; external contexts are operator-defined.
     * URL: /api/v1/.../resources/api/contexts
     */
    @Path("/contexts")
    public org.apache.ambari.view.k8s.resources.ContextResource contexts() {
        return new org.apache.ambari.view.k8s.resources.ContextResource(viewContext);
    }

    /**
     * Resolve {@code appVersion} (the component's own version — e.g. GitLab
     * 17.11.2 inside chart 8.11.2) for every catalog service in parallel and
     * return a {@code serviceName -> appVersion} map. Best-effort: a service
     * whose chart cannot be reached just gets {@code null} so the UI can show
     * a dash for that card without the whole page failing.
     *
     * Cached for 10 min inside HelmService so subsequent page navigations are
     * instant. Slow on cold start (one {@code helm show chart} per service).
     */
    @GET
    @Path("/catalog/app-versions")
    @Produces(MediaType.APPLICATION_JSON)
    public Response catalogAppVersions() {
        try {
            Map<String, StackServiceDef> defs = getStackDefinitionService().listServiceDefinitions();
            org.apache.ambari.view.k8s.service.HelmService helmService =
                    new org.apache.ambari.view.k8s.service.HelmService(viewContext);
            // Parallel resolve. Pool size capped so a 30-service catalog doesn't fan out
            // to a thread per service. Each task is a 'helm show chart' invocation.
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(Math.min(8, Math.max(1, defs.size())));
            try {
                List<java.util.concurrent.Future<Map.Entry<String, String>>> futures = new java.util.ArrayList<>();
                for (Map.Entry<String, StackServiceDef> e : defs.entrySet()) {
                    final String svcName = e.getKey();
                    final StackServiceDef def = e.getValue();
                    if (def == null || def.chart == null || def.chart.isBlank()) {
                        futures.add(java.util.concurrent.CompletableFuture.completedFuture(Map.entry(svcName, "")));
                        continue;
                    }
                    futures.add(pool.submit(() -> {
                        String av = helmService.resolveChartAppVersion(def.chart, def.defaultRepo, def.version);
                        return Map.entry(svcName, av == null ? "" : av);
                    }));
                }
                Map<String, String> result = new java.util.LinkedHashMap<>();
                for (var f : futures) {
                    try {
                        Map.Entry<String, String> entry = f.get(20, java.util.concurrent.TimeUnit.SECONDS);
                        result.put(entry.getKey(), entry.getValue());
                    } catch (Exception ex) {
                        // skip; client will render a dash
                    }
                }
                return Response.ok(result).build();
            } finally {
                pool.shutdownNow();
            }
        } catch (Exception e) {
            return handleError(e);
        }
    }

    /**
     * Cluster-wide CRD listing. Used by the Operators page to render
     * "extra CRDs the operator brings" alongside the curated capability probe.
     */
    @GET
    @Path("/crds")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listCrds() {
        try {
            return Response.ok(getKubernetesService().listCrds()).build();
        } catch (Exception e) {
            return handleError(e);
        }
    }

    /**
     * Lists Secrets matching the {@code *-truststore} convention with parsed
     * {@code ca.crt} summaries. {@code namespace} is optional; null = cluster-wide.
     */
    @GET
    @Path("/truststores")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listTruststores(@javax.ws.rs.QueryParam("namespace") String namespace) {
        try {
            return Response.ok(getKubernetesService().listTruststores(namespace)).build();
        } catch (Exception e) {
            return handleError(e);
        }
    }

    @GET
    @Path("/globals/configurations")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listGlobalConfigs() {
        return Response.ok(globalConfigService.listGlobalConfigs()).build();
    }

    private StackDefinitionService getStackDefinitionService() {
        if (stackDefinitionService == null) {
            stackDefinitionService = new StackDefinitionService(viewContext);
        }
        return stackDefinitionService;
    }
}
