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

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.UserPermissions;
import org.apache.ambari.view.k8s.resources.*;
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.security.MasterKeyUnavailableException;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.TruststoreRegistryService;
import org.apache.ambari.view.k8s.service.NamespaceScope;
import org.apache.ambari.view.k8s.service.PkiNamespaces;
import org.apache.ambari.view.k8s.service.cluster.ProtectedClusters;
import org.apache.ambari.view.k8s.service.ViewConfigurationService;
import org.apache.ambari.view.k8s.service.cluster.ClusterRef;
import org.apache.ambari.view.k8s.service.cluster.ClusterRegistry;
import org.apache.ambari.view.k8s.service.cluster.KubernetesCluster;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.service.StackDefinitionService;
import org.apache.ambari.view.k8s.service.GlobalConfigService;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;

import org.apache.ambari.view.k8s.utils.AmbariAliasResolver;
import org.apache.ambari.view.k8s.utils.KubeconfigGuard;
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

    /** The Kubernetes service of a cluster: the one tests set for the default cluster. */
    private KubernetesService kubernetesService(ClusterRef cluster) {
        return cluster.isDefault() ? getKubernetesService() : KubernetesService.get(viewContext, cluster);
    }

    /** The connection configuration of a cluster: the one tests set for the default cluster. */
    private ViewConfigurationService configService(ClusterRef cluster) {
        if (cluster.isDefault()) {
            return getConfigService();
        }
        ViewConfigurationService configured = clusterConfigServices.get(cluster);
        return configured != null ? configured : new ViewConfigurationService(viewContext, cluster);
    }

    private ClusterRegistry clusterRegistry() {
        return new ClusterRegistry(new InstanceSettings(viewContext));
    }

    /**
     * The cluster a request names in its path.
     *
     * @throws NotFoundException when the view knows no cluster of that id
     */
    private ClusterRef knownCluster(String id) {
        if (id != null && clusterRegistry().find(id).isPresent()) {
            return ClusterRef.of(id);
        }
        throw new NotFoundException(Response.status(Response.Status.NOT_FOUND).type(MediaType.APPLICATION_JSON)
                .entity(Collections.singletonMap("error", "KDPS knows no cluster '" + id + "'.")).build());
    }

    private AmbariAliasResolver getAmbariAliasResolver(){
        if (ambariAliasResolver == null){
            this.ambariAliasResolver = new AmbariAliasResolver(this.viewContext);
        }
        return this.ambariAliasResolver;
    };




    /** Replaces the Kubernetes service; for tests. */
    void setKubernetesService(KubernetesService kubernetesService) {
        this.kubernetesService = kubernetesService;
    }

    /** Replaces the view configuration service; for tests. */
    void setConfigService(ViewConfigurationService configService) {
        this.configService = configService;
    }

    /** Connection configurations of clusters other than the default one, set by tests. */
    private final Map<ClusterRef, ViewConfigurationService> clusterConfigServices = new java.util.HashMap<>();

    /** Replaces the connection configuration of a cluster other than the default one; for tests. */
    void setConfigService(ClusterRef cluster, ViewConfigurationService configService) {
        this.clusterConfigServices.put(cluster, configService);
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
    public Response getCurrentUserPermissions(@Context HttpHeaders headers) {
        AuthHelper authHelper = new AuthHelper(viewContext, headers);
        return Response.ok(authHelper.getPermissions()).build();
    }

    @POST
    @Path("/cluster/config")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response uploadKubeconfig(@Context HttpHeaders headers,
                                     @Context UriInfo ui, InputStream fileInputStream) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        return uploadKubeconfig(ClusterRef.DEFAULT, headers, fileInputStream);
    }

    /** {@link #uploadKubeconfig(HttpHeaders, UriInfo, InputStream)} for a cluster, once the caller's role was checked. */
    private Response uploadKubeconfig(ClusterRef cluster, HttpHeaders headers, InputStream fileInputStream) {
        try {
            ViewConfigurationService configurationService = configService(cluster);
            // The kubeconfig is only ever stored encrypted with the master key: without it, refuse before reading it.
            if (!configurationService.isMasterKeyAvailable()) {
                LOG.warn("/cluster/config: kubeconfig upload refused, the Ambari master key was not found");
                return MasterKeyUnavailableException.response();
            }

            LOG.info("/cluster/config: Received kubeconfig upload request.");
            // Check the file before it replaces the saved one: an unreadable upload must not break a working view.
            byte[] uploaded = fileInputStream.readNBytes(MAX_KUBECONFIG_BYTES + 1);
            if (uploaded.length > MAX_KUBECONFIG_BYTES) {
                return Response.status(Response.Status.BAD_REQUEST).entity(Collections.singletonMap("error",
                        "This file is too large to be a kubeconfig; the previous configuration is unchanged.")).build();
            }
            io.fabric8.kubernetes.api.model.Config kubeconfig = parseKubeconfig(uploaded);
            if (kubeconfig == null) {
                return Response.status(Response.Status.BAD_REQUEST).entity(Collections.singletonMap("error",
                        "This file is not a valid kubeconfig; the previous configuration is unchanged.")).build();
            }
            // A command (exec, auth-provider) would run as the Ambari account, and a file path would make the clients read
            // the Ambari server's disk. A field the kubeconfig model does not know is refused too: KDPS would not pass it
            // on, so the file saved would not be the one used.
            java.util.List<String> problems = KubeconfigGuard.problems(kubeconfig);
            if (!problems.isEmpty()) {
                LOG.warn("/cluster/config: kubeconfig refused: {}", problems);
                return Response.status(Response.Status.BAD_REQUEST).entity(Collections.singletonMap("error",
                        String.join(" ", problems) + " The previous configuration is unchanged.")).build();
            }
            java.util.List<String> contextNames = kubeconfig.getContexts() == null ? java.util.List.of()
                    : kubeconfig.getContexts().stream().map(io.fabric8.kubernetes.api.model.NamedContext::getName)
                        .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toList());
            // Use a static filename since it's no longer provided by the request
            File configurationFile = configurationService.saveKubeconfigFile(new java.io.ByteArrayInputStream(uploaded), "kubeconfig.yaml");

            LOG.info("Kubeconfig successfully saved to {}", configurationFile.getAbsolutePath());
            LOG.info("Configuring Apache Ambari View Backend CA bundle");
            final String webhookName = WebHookBootstrap.KEYTAB_WEBHOOK; // must match your Helm values prefix

            // A context chosen for the previous kubeconfig may not exist in this one: fall back to the new file's
            // current-context (the operator is then offered the context list) instead of failing to connect.
            String selectedContext = configurationService.getSelectedContext();
            if (selectedContext != null && !selectedContext.isBlank() && !contextNames.contains(selectedContext)) {
                LOG.info("/cluster/config: selected context '{}' is not in the new kubeconfig; using its current-context",
                        selectedContext);
                configurationService.saveSelectedContext(null);
                selectedContext = null;
            }
            // Rebuild the client from the kubeconfig just saved. A plain reload keeps an existing client, so a
            // view that was already connected would go on using the previous account until Ambari restarts.
            Response notConnected = reconnect(cluster, "The kubeconfig was saved");
            if (notConnected != null) {
                boolean contextNotChosenYet = selectedContext == null || selectedContext.isBlank();
                if (contextNotChosenYet && contextNames.size() > 1
                        && notConnected.getStatus() == Response.Status.SERVICE_UNAVAILABLE.getStatusCode()) {
                    // A kubeconfig with several contexts often has a current-context pointing elsewhere (another
                    // cluster, a local one). Keep it and let the operator choose the context; that choice reconnects.
                    Object reason = ((Map<?, ?>) notConnected.getEntity()).get("error");
                    return Response.ok(Map.of(
                            "message", "Configuration saved.",
                            "warning", reason + " The kubeconfig has " + contextNames.size()
                                    + " contexts: choose the one to use.")).build();
                }
                return notConnected;
            }
            if (!cluster.isDefault()) {
                // The keytab webhook of another cluster is prepared with the deploys to that cluster.
                return Response.ok(Collections.singletonMap("message", "Configuration saved.")).build();
            }
            // The keytab webhook prerequisites (its namespace, mTLS Secrets, CA bundle) only matter in the
            // WEBHOOK Kerberos injection mode. In the default PRE_PROVISIONED mode nothing uses them, and on
            // OpenShift sites where namespaces cannot be created on the fly preparing them made the upload
            // fail with a 500 although the kubeconfig was saved. Deploys in WEBHOOK mode prepare them on
            // demand (CommandService.directHelmDeploy); here a failure is reported, not fatal.
            String injectionMode = org.apache.ambari.view.k8s.service.CommandService.resolveKerberosInjectionMode(this.viewContext);
            if ("WEBHOOK".equals(injectionMode)) {
                try {
                    WebHookBootstrap.prepareWebhookPrereqs(
                            this.viewContext,
                            kubernetesService(cluster),
                            webhookName,
                            headers.getRequestHeaders()
                    );
                } catch (Exception ex) {
                    LOG.warn("/cluster/config: kubeconfig saved, but the keytab webhook prerequisites could not be prepared: {}", ex.toString());
                    return Response.ok(Map.of(
                            "message", "Configuration saved.",
                            "warning", "Kerberos injection mode is WEBHOOK but the keytab webhook prerequisites could not be "
                                    + "prepared (" + ex.getMessage() + "). Pre-create the webhook namespace or switch the "
                                    + "injection mode to PRE_PROVISIONED.")).build();
                }
            } else {
                LOG.info("/cluster/config: Kerberos injection mode {}; keytab webhook prerequisites not needed", injectionMode);
                // An account an earlier WEBHOOK setup left behind is withdrawn while an administrator is at hand;
                // kept, with a warning in the log, while the webhook is still installed.
                WebHookBootstrap.retireWebhookAccess(this.viewContext, kubernetesService(cluster),
                        headers == null ? null : headers.getRequestHeaders());
            }
            return Response.ok(Collections.singletonMap("message", "Configuration saved.")).build();

        } catch (MasterKeyUnavailableException e) {
            return e.getResponse();
        } catch (IOException e) {
            LOG.error("Error while saving uploaded kubeconfig file", e);
            return Response.serverError().entity("Error while saving the file.").build();
        }
    }

    /** Upper bound for an uploaded kubeconfig; real ones are a few kilobytes. */
    private static final int MAX_KUBECONFIG_BYTES = 1024 * 1024;

    /**
     * The uploaded bytes as a kubeconfig, or {@code null} when they are not one. Any YAML maps onto the Config model,
     * so the document must also declare {@code kind: Config} and at least one cluster.
     */
    private static io.fabric8.kubernetes.api.model.Config parseKubeconfig(byte[] bytes) {
        try {
            io.fabric8.kubernetes.api.model.Config c =
                    KubeconfigGuard.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (!"Config".equals(c.getKind()) || c.getClusters() == null || c.getClusters().isEmpty()) {
                return null;
            }
            return c;
        } catch (RuntimeException e) {
            LOG.warn("/cluster/config: uploaded file is not a kubeconfig: {}", e.toString());
            return null;
        }
    }

    /**
     * Rebuilds the Kubernetes client from the saved configuration and checks, with one API call, that it reaches the
     * cluster. Building a client never contacts the cluster, so without the check a kubeconfig pointing nowhere or
     * holding rejected credentials would be reported as working.
     *
     * @param saved what was saved, to start the error message with (e.g. "The kubeconfig was saved")
     * @return {@code null} when connected; otherwise the response to return: 400 when no client can be built from the
     *         configuration, 503 when the cluster cannot be reached or rejects the credentials
     */
    private Response reconnect(ClusterRef cluster, String saved) {
        return reconnect(cluster, saved, "Check that the kubeconfig is valid (details in the Ambari server log).");
    }

    /**
     * {@link #reconnect(ClusterRef, String)} with what to check when no client can be built.
     *
     * @param saved       what was saved, to start the error message with
     * @param whatToCheck the advice that follows when no client can be built from the configuration
     */
    private Response reconnect(ClusterRef cluster, String saved, String whatToCheck) {
        boolean built;
        // When the saved configuration itself is the problem (a kubeconfig whose user only runs a command, for one),
        // the operator is told what to change rather than pointed at the server log.
        String unusable = null;
        try {
            KubernetesService service = kubernetesService(cluster);
            built = service.forceReloadClient();
            unusable = built ? null : service.unusableConfiguration();
        } catch (Exception e) {
            LOG.warn("Kubernetes client rebuild failed: {}", e.toString());
            built = false;
        }
        if (!built) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Collections.singletonMap("error",
                    saved + ", but KDPS could not build a connection from it. "
                            + (unusable != null ? unusable : whatToCheck))).build();
        }
        org.apache.ambari.view.k8s.model.ConnectionHealth health = kubernetesService(cluster).pingCluster();
        if (!health.isConnected()) {
            LOG.warn("{}, but the cluster check failed: {}", saved, health.getMessage());
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(Collections.singletonMap("error",
                    saved + ", but KDPS could not connect with it: " + health.getMessage())).build();
        }
        return null;
    }

    /**
     * List the contexts available in the uploaded kubeconfig so the operator can choose which
     * cluster/context this view instance targets.
     */
    @GET
    @Path("/cluster/contexts")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listKubeconfigContexts(@Context HttpHeaders headers) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        return listKubeconfigContexts(ClusterRef.DEFAULT);
    }

    /** {@link #listKubeconfigContexts(HttpHeaders)} for a cluster, once the caller's role was checked. */
    private Response listKubeconfigContexts(ClusterRef cluster) {
        try {
            return Response.ok(kubernetesService(cluster).listAvailableContexts()).build();
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
    public Response selectKubeconfigContext(@Context HttpHeaders headers, java.util.Map<String, String> body) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        return selectKubeconfigContext(ClusterRef.DEFAULT, body);
    }

    /** {@link #selectKubeconfigContext(HttpHeaders, Map)} for a cluster, once the caller's role was checked. */
    private Response selectKubeconfigContext(ClusterRef cluster, java.util.Map<String, String> body) {
        String context = body == null ? null : body.get("context");
        LOG.info("/cluster/context: selecting kubeconfig context '{}'.", context);
        java.util.List<java.util.Map<String, Object>> available;
        try {
            available = kubernetesService(cluster).listAvailableContexts();
        } catch (RuntimeException e) {
            LOG.warn("/cluster/context: the saved kubeconfig cannot be read: {}", e.toString());
            return Response.status(Response.Status.BAD_REQUEST).entity(Collections.singletonMap("error",
                    "The saved kubeconfig cannot be read; upload it again.")).build();
        }
        if (context != null && !context.isBlank() && available.stream().noneMatch(c -> context.equals(c.get("name")))) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Collections.singletonMap("error",
                    "Context '" + context + "' is not in the uploaded kubeconfig.")).build();
        }
        configService(cluster).saveSelectedContext(context);
        Response notConnected = reconnect(cluster, 
                "Context '" + (context == null || context.isBlank() ? "current-context" : context) + "' was saved");
        if (notConnected != null) {
            return notConnected;
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
    public Response saveOpenShiftLogin(@Context HttpHeaders headers, java.util.Map<String, Object> body) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        return saveOpenShiftLogin(ClusterRef.DEFAULT, body);
    }

    /** {@link #saveOpenShiftLogin(HttpHeaders, Map)} for a cluster, once the caller's role was checked. */
    private Response saveOpenShiftLogin(ClusterRef cluster, java.util.Map<String, Object> body) {
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
        if (!cluster.isDefault() && (caData == null || caData.isBlank()) && !insecure) {
            // Without its CA, the API server would be checked against the trust of the whole Ambari server, which
            // holds the default cluster's CA: a certificate that CA signed would be taken for this cluster's.
            return Response.status(Response.Status.BAD_REQUEST).entity(Collections.singletonMap("error",
                    "Give the CA certificate of this cluster's API server, so that KDPS checks it against that CA "
                            + "only. Nothing was saved.")).build();
        }
        if (!configService(cluster).isMasterKeyAvailable()) {
            LOG.warn("/cluster/openshift-login: refused, the Ambari master key was not found to encrypt the password");
            return MasterKeyUnavailableException.response();
        }
        LOG.info("/cluster/openshift-login: configuring OpenShift login for user '{}' at {}", username, apiUrl);
        try {
            configService(cluster).saveOpenShiftLogin(apiUrl, username, password, caData, insecure);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Collections.singletonMap("error", e.getMessage())).build();
        }
        try {
            boolean ok = kubernetesService(cluster).forceReloadClient();
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
    public Response testOpenShiftLogin(@Context HttpHeaders headers, java.util.Map<String, Object> body) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
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
                // The provider knows why (a refused certificate comes with what to do about it).
                String why = provider.getLastFailure();
                return Response.ok(Map.of("ok", false, "error", why != null && !why.isBlank() ? why
                        : "Could not obtain a token. Check the URL/credentials, and that the cluster allows password login (oc login -u -p).")).build();
            }
            return Response.ok(Map.of("ok", true, "message", "Authenticated to OpenShift as " + username + ".")).build();
        } catch (Exception e) {
            return Response.ok(Map.of("ok", false, "error", e.getMessage() == null ? e.toString() : e.getMessage())).build();
        }
    }

    /**
     * RBAC preflight (backed by SelfSubjectAccessReview, i.e. {@code oc auth can-i}) for the connected
     * account against a target namespace: reports whether it can perform the core deploy actions and —
     * on OpenShift — the KEDA autoscaling + monitoring actions. Lets the wizard warn BEFORE deploying
     * when the account lacks a permission (e.g. can't create KEDA ScaledObjects or the objects of their monitoring token).
     */
    @GET
    @Path("/cluster/preflight")
    @Produces(MediaType.APPLICATION_JSON)
    public Response preflight(@Context HttpHeaders headers,
                              @QueryParam("namespace") @DefaultValue("default") String namespace) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        return preflight(ClusterRef.DEFAULT, namespace);
    }

    /** {@link #preflight(HttpHeaders, String)} for a cluster, once the caller's role was checked. */
    private Response preflight(ClusterRef cluster, String namespace) {
        KubernetesService k = kubernetesService(cluster);
        boolean ocp = k.isOpenShiftCluster();
        List<Map<String, Object>> checks = new java.util.ArrayList<>();
        // Every deploy keeps its parameters encrypted with the Ambari master key.
        Map<String, Object> masterKey = preflightCheck("Encrypt the credentials KDPS keeps (Ambari master key)", "KDPS",
                configService(cluster).isMasterKeyAvailable());
        if (!Boolean.TRUE.equals(masterKey.get("allowed"))) {
            masterKey.put("remediation", MasterKeyUnavailableException.MESSAGE);
        }
        checks.add(masterKey);
        // Core deploy
        checks.add(preflightCheck("Create Deployments", "Core", k.canI("create", "apps", "deployments", null, null, namespace)));
        checks.add(preflightCheck("Create Services", "Core", k.canI("create", "", "services", null, null, namespace)));
        checks.add(preflightCheck("Create Secrets", "Core", k.canI("create", "", "secrets", null, null, namespace)));
        checks.add(preflightCheck("Create ConfigMaps", "Core", k.canI("create", "", "configmaps", null, null, namespace)));
        // Cluster-wide RBAC (advisory): an account limited to its own projects cannot create these, and the KDPS
        // services do not need them with their defaults. A chart that renders them anyway fails at install with
        // "forbidden ... at the cluster scope".
        boolean clusterRbac = k.canI("create", "rbac.authorization.k8s.io", "clusterroles", null, null, null)
                && k.canI("create", "rbac.authorization.k8s.io", "clusterrolebindings", null, null, null);
        Map<String, Object> clusterRbacCheck = preflightCheck("Create ClusterRoles/ClusterRoleBindings",
                "Cluster-wide RBAC", clusterRbac);
        clusterRbacCheck.put("advisory", true);
        clusterRbacCheck.put("remediation", "With their default settings, the KDPS services do not need it. If a chart"
                + " you configured does, a cluster administrator lets this account create ClusterRoles and"
                + " ClusterRoleBindings.");
        clusterRbacCheck.put("note", "Charts that grant cluster-wide permissions create these objects at install."
                + " Without the right, such an install is refused by the cluster and leaves a failed release.");
        checks.add(clusterRbacCheck);
        // KEDA autoscaling
        checks.add(preflightCheck("Create KEDA ScaledObjects", "Autoscaling (KEDA)", k.canI("create", "keda.sh", "scaledobjects", null, null, namespace)));
        checks.add(preflightCheck("Create KEDA TriggerAuthentications", "Autoscaling (KEDA)", k.canI("create", "keda.sh", "triggerauthentications", null, null, namespace)));
        // Monitoring (ServiceMonitor so Prometheus scrapes the workload)
        checks.add(preflightCheck("Create ServiceMonitors", "Monitoring", k.canI("create", "monitoring.coreos.com", "servicemonitors", null, null, namespace)));
        if (ocp) {
            // KEDA reads the platform's monitoring with a token KDPS sets up in the project: the service account,
            // its Role and RoleBinding, and the Secret checked above. It does not go through the API server's proxy
            // of the monitoring services, so no right in openshift-monitoring is asked for.
            checks.add(preflightCheck("Create ServiceAccounts", "Autoscaling (KEDA)",
                    k.canI("create", "", "serviceaccounts", null, null, namespace)));
            checks.add(preflightCheck("Create Roles", "Autoscaling (KEDA)",
                    k.canI("create", "rbac.authorization.k8s.io", "roles", null, null, namespace)));
            checks.add(preflightCheck("Create RoleBindings", "Autoscaling (KEDA)",
                    k.canI("create", "rbac.authorization.k8s.io", "rolebindings", null, null, namespace)));
            if (k.canBindClusterRoles()) {
                // With it, autoscaling may read the monitoring of the whole cluster (Thanos port 9091).
                checks.add(preflightCheck("Create ClusterRoleBindings", "Autoscaling (KEDA)", true));
            } else {
                Map<String, Object> projectPort = preflightCheck("Autoscaling reads the monitoring of the project",
                        "Autoscaling (KEDA)", false);
                projectPort.put("advisory", true);
                projectPort.put("note", "This account may not create ClusterRoleBindings, so autoscaling reads the "
                        + "metrics of the project only, through the per-project Thanos port (9092). Nothing to do: "
                        + "autoscaling on the metrics of the project works without any right on the cluster.");
                checks.add(projectPort);
            }

            // SCC (advisory): some services (OpenMetadata) run pods that the default restricted-v2 SCC
            // refuses. Their deploy grants the SCCs to the release's own service accounts when this account may in
            // this project (bind on the SCC's ClusterRole, or use of the SCC); otherwise it stops with the commands
            // for the platform team, and finds their grant on the next attempt. Advisory: it does not fail the
            // overall preflight.
            List<String> sccs = KubernetesService.SERVICE_SCCS;
            boolean canGrantScc = sccs.stream().allMatch(scc -> k.canGrantScc(namespace, scc));
            Map<String, Object> sccCheck = preflightCheck("Grant the " + String.join(" and ", sccs)
                    + " SCCs to the service accounts of a release (OpenMetadata)", "Security (SCC)",
                    canGrantScc);
            sccCheck.put("advisory", true);
            sccCheck.put("remediation", "Once per project, the platform team lets this account grant them:\n"
                    + KubernetesService.sccGrantRightCommands(namespace, sccs, "  ")
                    + "\nOr it grants them to each release's service accounts, as the deploy prints:\n"
                    + "  oc adm policy add-scc-to-user <scc> -z <serviceaccount> -n " + namespace);
            sccCheck.put("note", "Only the services that need them ask for these SCCs, and only for the "
                    + "service accounts that need them. A deploy without the right stops with both commands; "
                    + "nothing is granted to the whole project.");
            checks.add(sccCheck);
        }
        // Advisory checks (e.g. SCC) don't count against the overall readiness gate.
        boolean allOk = checks.stream()
                .filter(c -> !Boolean.TRUE.equals(c.get("advisory")))
                .allMatch(c -> Boolean.TRUE.equals(c.get("allowed")));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("platform", ocp ? "openshift" : "kubernetes");
        out.put("namespace", namespace);
        out.put("allOk", allOk);
        out.put("checks", checks);
        return Response.ok(out).build();
    }

    private Map<String, Object> preflightCheck(String name, String group, boolean allowed) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name);
        m.put("group", group);
        m.put("allowed", allowed);
        return m;
    }

    /** Current view-wide outbound proxy settings (password never returned — only whether one is set). */
    @GET
    @Path("/cluster/proxy")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getProxySettings(@Context HttpHeaders headers) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
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
     * Save the view-wide outbound proxy (for reaching internet Git repos from behind a corporate
     * proxy); the next Git connection uses it. The Kubernetes API always stays direct. Body:
     * {enabled, url, username?, password?, noProxy?}.
     */
    @POST
    @Path("/cluster/proxy")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveProxySettings(@Context HttpHeaders headers, java.util.Map<String, Object> body) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
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
        if (password != null && !password.isEmpty() && !this.getConfigService().isMasterKeyAvailable()) {
            LOG.warn("/cluster/proxy: refused, the Ambari master key was not found to encrypt the proxy password");
            return MasterKeyUnavailableException.response();
        }
        // Saved only: the Git clients read the proxy when they connect, nothing JVM-wide is installed.
        try {
            this.getConfigService().saveProxySettings(enabled, url, username, password, noProxy);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Collections.singletonMap("error", e.getMessage())).build();
        }
        return Response.ok(Collections.singletonMap("message", enabled ? "Outbound proxy enabled" : "Outbound proxy disabled")).build();
    }

    /**
     * Whether KDPS can connect with the ServiceAccount of the pod Ambari runs in, as whom, and whether it does now.
     * Answers {@code available} (and {@code reason} when not), {@code active}, {@code authMode}, {@code username}
     * ({@code system:serviceaccount:<namespace>:<name>}), {@code serviceAccount}, {@code namespace} and
     * {@code apiServer}; never the token.
     */
    @GET
    @Path("/cluster/in-cluster")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getInClusterServiceAccount(@Context HttpHeaders headers) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        return getInClusterServiceAccount(ClusterRef.DEFAULT);
    }

    /** {@link #getInClusterServiceAccount(HttpHeaders)} for a cluster, once the caller's role was checked. */
    private Response getInClusterServiceAccount(ClusterRef cluster) {
        ViewConfigurationService configuration = configService(cluster);
        org.apache.ambari.view.k8s.service.InClusterServiceAccount pod = configuration.getInClusterServiceAccount();
        String reason = pod.unavailableReason();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("available", reason == null);
        out.put("reason", reason);
        out.put("active", configuration.isConfigured() && configuration.isInClusterServiceAccount());
        out.put("authMode", configuration.getAuthMode());
        if (reason == null) {
            org.apache.ambari.view.k8s.service.InClusterServiceAccount.Identity identity = pod.identity();
            out.put("username", identity.username());
            out.put("serviceAccount", identity.name());
            out.put("namespace", identity.namespace());
            out.put("apiServer", pod.apiServerUrl());
        }
        return Response.ok(out).build();
    }

    /**
     * Connect with the ServiceAccount of the pod Ambari runs in (the ambari chart with
     * {@code inClusterServiceAccount.enabled=true}): no kubeconfig to upload and no password to keep, for clusters whose
     * console only signs in through SSO. The mode is saved for every user of the view instance, the client is rebuilt
     * and checked against the cluster, as for the other modes. Refused, with the previous configuration unchanged,
     * when the pod has no ServiceAccount token or Ambari does not run in a pod.
     */
    @POST
    @Path("/cluster/in-cluster")
    @Produces(MediaType.APPLICATION_JSON)
    public Response useInClusterServiceAccount(@Context HttpHeaders headers) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        return connectWithPodServiceAccount(ClusterRef.DEFAULT);
    }

    /** {@link #useInClusterServiceAccount}, once the caller's role was checked. */
    private Response connectWithPodServiceAccount(ClusterRef cluster) {
        ViewConfigurationService configuration = configService(cluster);
        org.apache.ambari.view.k8s.service.InClusterServiceAccount pod = configuration.getInClusterServiceAccount();
        String unavailable = pod.unavailableReason();
        if (unavailable != null) {
            LOG.warn("/cluster/in-cluster: refused, the pod's ServiceAccount cannot be used: {}", unavailable);
            return Response.status(Response.Status.BAD_REQUEST).entity(Collections.singletonMap("error",
                    unavailable + " The previous configuration is unchanged.")).build();
        }
        // The pod's ServiceAccount belongs to the one cluster the pod runs in.
        for (KubernetesCluster other : clusterRegistry().list()) {
            if (!other.ref().equals(cluster)) {
                ViewConfigurationService otherConfiguration = configService(other.ref());
                if (otherConfiguration.isConfigured() && otherConfiguration.isInClusterServiceAccount()) {
                    return Response.status(Response.Status.CONFLICT).entity(Collections.singletonMap("error",
                            "The cluster '" + other.name() + "' already connects with the ServiceAccount of the Ambari "
                                    + "pod, which belongs to the one cluster the pod runs in. The previous "
                                    + "configuration is unchanged.")).build();
                }
            }
        }
        String username = pod.identity().username();
        String account = username == null ? "the ServiceAccount of the Ambari pod" : "the ServiceAccount " + username;
        LOG.info("/cluster/in-cluster: connecting with {}", account);
        configuration.saveInClusterServiceAccount();
        Response notConnected = reconnect(cluster, "The connection with " + account + " was saved",
                "Check the ServiceAccount token and CA certificate mounted in the Ambari pod (details in the Ambari "
                        + "server log).");
        if (notConnected != null) {
            return notConnected;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("message", "KDPS now connects with " + account + ".");
        out.put("username", username);
        out.put("note", "Grant it the rights to deploy in each project KDPS deploys to (on OpenShift, the admin and "
                + "monitoring-edit roles); Cluster prerequisites checks them.");
        return Response.ok(out).build();
    }

    /**
     * Switch the auth mode (e.g. "kubeconfig" to go back to a previously-uploaded kubeconfig). "in-cluster" connects
     * with the pod's ServiceAccount, checked as {@link #useInClusterServiceAccount} does.
     */
    @POST
    @Path("/cluster/auth-mode")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setAuthMode(@Context HttpHeaders headers, java.util.Map<String, String> body) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        return setAuthMode(ClusterRef.DEFAULT, body);
    }

    /** {@link #setAuthMode(HttpHeaders, Map)} for a cluster, once the caller's role was checked. */
    private Response setAuthMode(ClusterRef cluster, java.util.Map<String, String> body) {
        String mode = body == null ? null : body.get("mode");
        if (ViewConfigurationService.AUTH_MODE_IN_CLUSTER.equals(mode)) {
            return connectWithPodServiceAccount(cluster);
        }
        configService(cluster).setAuthMode(mode);
        try { kubernetesService(cluster).forceReloadClient(); } catch (Exception e) {
            LOG.warn("/cluster/auth-mode: reload failed: {}", e.toString());
        }
        return Response.ok(Collections.singletonMap("message", "Auth mode set to " + configService(cluster).getAuthMode())).build();
    }

    /**
     * Whether KDPS found the Ambari master key it encrypts the kubeconfig and every credential with. The Configuration
     * page asks first, so that an administrator learns what to do before uploading anything.
     *
     * @return 200 with {@code available}, and when it is {@code false} the {@code message} and {@code code} of the
     *         412 that saving a credential would get
     */
    @GET
    @Path("/cluster/master-key")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMasterKeyStatus(@Context HttpHeaders headers) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        boolean available = this.getConfigService().isMasterKeyAvailable();
        out.put("available", available);
        if (!available) {
            out.put("message", MasterKeyUnavailableException.MESSAGE);
            out.put("code", MasterKeyUnavailableException.CODE);
        }
        return Response.ok(out).build();
    }

    @GET
    @Path("/cluster/configured")
    @Produces(MediaType.APPLICATION_JSON)
    public Response checkIsViewConfigured(InputStream fileInputStream) {
        return checkIsViewConfigured(ClusterRef.DEFAULT);
    }

    /** {@link #checkIsViewConfigured(InputStream)} for a cluster. */
    private Response checkIsViewConfigured(ClusterRef cluster) {
        ViewConfigurationService configurationService = configService(cluster);
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
    
    /**
     * Live connectivity probe against the target cluster's API server.
     *
     * <p>Distinct from {@link #checkIsViewConfigured(InputStream)} (which only reports whether
     * credentials were ever stored): this actually calls the cluster and reflects whether those
     * credentials still authenticate. The UI uses it to keep the connection badge honest — so a token
     * that was revoked out-of-band (e.g. the operator logged into the OpenShift console directly)
     * flips the badge to "disconnected" instead of leaving a stale "connected" while requests 401.
     *
     * <p>Always returns HTTP 200 with a {@link org.apache.ambari.view.k8s.model.ConnectionHealth}
     * payload whose {@code state} the frontend switches on; the probe's own failures are reported in
     * the body, not as an HTTP error, so the poll itself never throws in the client.
     *
     * @return 200 with the {@code ConnectionHealth} JSON
     */
    @GET
    @Path("/cluster/liveness")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getClusterLiveness() {
        return getClusterLiveness(ClusterRef.DEFAULT);
    }

    /** {@link #getClusterLiveness()} for a cluster. */
    private Response getClusterLiveness(ClusterRef cluster) {
        org.apache.ambari.view.k8s.model.ConnectionHealth health = kubernetesService(cluster).pingCluster();
        LOG.info("/cluster/liveness: probe result state={}", health.getState());
        return Response.ok(health).build();
    }

    // ----- the clusters KDPS connects to -----

    /**
     * The clusters KDPS knows, the default one first, each with how it connects and whether a connection was saved for
     * it; never a credential. Every user of the view may read them.
     */
    @GET
    @Path("/clusters")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listClusters() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (KubernetesCluster cluster : clusterRegistry().list()) {
            out.add(clusterView(cluster));
        }
        return Response.ok(out).build();
    }

    /**
     * Adds a cluster, or describes again one KDPS knows. Body: {@code {name, description?, protected?}}. Its connection
     * is then saved with the {@code /clusters/{cluster}/...} endpoints. Administrators only.
     */
    @PUT
    @Path("/clusters/{cluster}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveCluster(@Context HttpHeaders headers, @PathParam("cluster") String id,
                                java.util.Map<String, Object> body) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        if (body == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Collections.singletonMap("error", "missing body")).build();
        }
        try {
            KubernetesCluster saved = clusterRegistry().save(new KubernetesCluster(id,
                    body.get("name") == null ? null : String.valueOf(body.get("name")),
                    body.get("description") == null ? null : String.valueOf(body.get("description")),
                    Boolean.parseBoolean(String.valueOf(body.get("protected")))));
            LOG.info("/clusters: cluster '{}' saved as '{}'", saved.id(), saved.name());
            return Response.ok(clusterView(saved)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Collections.singletonMap("error", e.getMessage())).build();
        }
    }

    /**
     * Removes a cluster: its connection is forgotten (settings and files) and closed. The default cluster cannot be
     * removed. Administrators only.
     */
    @DELETE
    @Path("/clusters/{cluster}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeCluster(@Context HttpHeaders headers, @PathParam("cluster") String id) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        ClusterRef cluster = knownCluster(id);
        if (cluster.isDefault()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Collections.singletonMap("error", "The default cluster cannot be removed.")).build();
        }
        // Its releases would be left without a cluster to upgrade or uninstall them on.
        List<String> releases = new org.apache.ambari.view.k8s.service.ReleaseMetadataService(viewContext, cluster)
                .findAll().stream()
                .filter(r -> cluster.equals(r.clusterRef()))
                .map(r -> r.getNamespace() + "/" + r.getReleaseName())
                .sorted()
                .toList();
        if (!releases.isEmpty()) {
            return Response.status(Response.Status.CONFLICT).entity(Collections.singletonMap("error",
                    "KDPS still has releases on the cluster '" + id + "': " + String.join(", ", releases)
                            + ". Uninstall them first; the cluster was not removed.")).build();
        }
        KubernetesService.forget(viewContext, cluster);
        configService(cluster).forgetConnection();
        clusterRegistry().remove(cluster.id());
        LOG.info("/clusters: cluster '{}' removed", cluster);
        return Response.ok(Collections.singletonMap("message", "The cluster '" + id + "' was removed.")).build();
    }

    /** A cluster as the API shows it: its description, how it connects and whether a connection was saved. */
    private Map<String, Object> clusterView(KubernetesCluster cluster) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", cluster.id());
        view.put("name", cluster.name());
        view.put("description", cluster.description());
        view.put("protected", cluster.protectedCluster());
        view.put("default", cluster.ref().isDefault());
        try {
            ViewConfigurationService configuration = configService(cluster.ref());
            view.put("configured", configuration.isConfigured());
            view.put("authMode", configuration.getAuthMode());
            // Shown on the cluster: its API server's certificate is not verified.
            view.put("insecure", configuration.isConfigured() && configuration.skipsTlsVerification());
        } catch (RuntimeException e) {
            LOG.warn("/clusters: the connection of cluster '{}' cannot be read: {}", cluster.id(), e.toString());
            view.put("configured", false);
        }
        return view;
    }

    @POST
    @Path("/clusters/{cluster}/config")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response uploadClusterKubeconfig(@Context HttpHeaders headers, @PathParam("cluster") String id,
                                            InputStream fileInputStream) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        return uploadKubeconfig(knownCluster(id), headers, fileInputStream);
    }

    @GET
    @Path("/clusters/{cluster}/contexts")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listClusterKubeconfigContexts(@Context HttpHeaders headers, @PathParam("cluster") String id) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        return listKubeconfigContexts(knownCluster(id));
    }

    @POST
    @Path("/clusters/{cluster}/context")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response selectClusterKubeconfigContext(@Context HttpHeaders headers, @PathParam("cluster") String id,
                                                   java.util.Map<String, String> body) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        return selectKubeconfigContext(knownCluster(id), body);
    }

    @POST
    @Path("/clusters/{cluster}/openshift-login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveClusterOpenShiftLogin(@Context HttpHeaders headers, @PathParam("cluster") String id,
                                              java.util.Map<String, Object> body) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        return saveOpenShiftLogin(knownCluster(id), body);
    }

    @GET
    @Path("/clusters/{cluster}/preflight")
    @Produces(MediaType.APPLICATION_JSON)
    public Response clusterPreflight(@Context HttpHeaders headers, @PathParam("cluster") String id,
                                     @QueryParam("namespace") @DefaultValue("default") String namespace) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        return preflight(knownCluster(id), namespace);
    }

    @GET
    @Path("/clusters/{cluster}/in-cluster")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getClusterInClusterServiceAccount(@Context HttpHeaders headers, @PathParam("cluster") String id) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        return getInClusterServiceAccount(knownCluster(id));
    }

    @POST
    @Path("/clusters/{cluster}/in-cluster")
    @Produces(MediaType.APPLICATION_JSON)
    public Response useClusterInClusterServiceAccount(@Context HttpHeaders headers, @PathParam("cluster") String id) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        return connectWithPodServiceAccount(knownCluster(id));
    }

    @POST
    @Path("/clusters/{cluster}/auth-mode")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setClusterAuthMode(@Context HttpHeaders headers, @PathParam("cluster") String id,
                                       java.util.Map<String, String> body) {
        new AuthHelper(viewContext, headers).checkConfigurationPermission();
        return setAuthMode(knownCluster(id), body);
    }

    @GET
    @Path("/clusters/{cluster}/configured")
    @Produces(MediaType.APPLICATION_JSON)
    public Response checkIsClusterConfigured(@PathParam("cluster") String id) {
        return checkIsViewConfigured(knownCluster(id));
    }

    @GET
    @Path("/clusters/{cluster}/liveness")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getNamedClusterLiveness(@PathParam("cluster") String id) {
        return getClusterLiveness(knownCluster(id));
    }

    @GET
    @Path("/cluster/stats")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getClusterStats(@QueryParam("forceRefresh") @DefaultValue("false") boolean forceRefresh,
                                    @QueryParam("cluster") String cluster) {
        ClusterRef target = requestedCluster(cluster);
        try {
            return Response.ok(kubernetesService(target).getClusterStats(forceRefresh)).build();
        } catch (Exception e) {
            return handleError(e);
        }
    }
    
    @GET
    @Path("/nodes")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getNodes(@QueryParam("limit") @DefaultValue("200") int limit,
                             @QueryParam("offset") @DefaultValue("0") int offset,
                             @QueryParam("cluster") String cluster) {
        ClusterRef target = requestedCluster(cluster);
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var page = kubernetesService(target).getNodes(limit, offset);
            // An account limited to its own projects may not read nodes: an empty list the page can explain, not an error.
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("items", page.items());
            body.put("total", page.total());
            if (page.restricted()) {
                body.put("restricted", true);
            }
            return Response.ok(mapper.writeValueAsString(body)).build();
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
    public Response getClusterEvents(@Context HttpHeaders headers, @QueryParam("cluster") String cluster) {
        ClusterRef target = requestedCluster(cluster);
        try {
            // Read-only users only see the events of the namespaces of releases deployed from KDPS on the cluster, as
            // on the Workloads page.
            java.util.Set<String> onlyNamespaces = null;
            if (!new AuthHelper(viewContext, headers).canWrite()) {
                onlyNamespaces = new java.util.HashSet<>();
                for (var release : new org.apache.ambari.view.k8s.service.ReleaseMetadataService(viewContext, target)
                        .findAll()) {
                    if (release != null && release.getNamespace() != null && target.equals(release.clusterRef())) {
                        onlyNamespaces.add(release.getNamespace());
                    }
                }
            }
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return Response.ok(mapper.writeValueAsString(kubernetesService(target).getClusterEvents(onlyNamespaces)))
                    .build();
        } catch (Exception e) {
            return handleError(e);
        }
    }

    @GET
    @Path("/cluster/componentstatuses")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getComponentStatuses(@QueryParam("cluster") String cluster) {
        ClusterRef target = requestedCluster(cluster);
        try {
            return Response.ok(kubernetesService(target).getComponentStatuses()).build();
        } catch (Exception e) {
            return handleError(e);
        }
    }

    /**
     * Workloads sub-resource: namespaces/pods/services/logs.
     */
    @Path("/workloads")
    public WorkloadsResource getWorkloads(@Context HttpHeaders headers, @QueryParam("cluster") String cluster) {
        ClusterRef target = requestedCluster(cluster);
        return new WorkloadsResource(viewContext, headers, target, kubernetesService(target));
    }

    /**
     * @see org.apache.ambari.view.k8s.resources.HelmRepoResource
     * @return service
     */
    @Path("/helm/repos")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public HelmRepoResource helmRepos(@Context HttpHeaders headers) {
        return new HelmRepoResource(viewContext, headers);
    }

    private Response handleError(Exception e) {
        if (e instanceof MasterKeyUnavailableException noKey) {
            return noKey.getResponse();
        }
        // Configured but not connected (the last attempt to connect failed): not the 412 "unconfigured", which makes
        // the UI show the first-run configuration page and stop probing the connection.
        if (e instanceof org.apache.ambari.view.k8s.service.KubernetesService.NotConnectedException) {
            LOG.warn("Cluster call while not connected: {}", e.getMessage());
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(e.getMessage()).build();
        }
        if (e instanceof IllegalStateException) {
            LOG.warn("Attempt to access resource without configuration: {}", e.getMessage());
            return Response.status(Response.Status.PRECONDITION_FAILED).entity(e.getMessage()).build();
        }
        // Surface a rejected cluster token as a real 401 instead of collapsing it into a generic 500.
        // This lets the frontend distinguish "your stored credentials are no longer valid — re-login"
        // from an unexpected server error, and drives the connection badge to "disconnected".
        if (e instanceof io.fabric8.kubernetes.client.KubernetesClientException) {
            io.fabric8.kubernetes.client.KubernetesClientException kce =
                    (io.fabric8.kubernetes.client.KubernetesClientException) e;
            if (kce.getCode() == 401) {
                LOG.warn("Kubernetes API returned 401 Unauthorized — the stored cluster credentials are no longer "
                        + "valid (token revoked or expired): {}", kce.getMessage());
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity("K8S_UNAUTHORIZED: " + kce.getMessage()).build();
            }
        }
        // Authenticated but not allowed: the account's limit, not a server fault. One line, no stack trace.
        // The marker tells the UI this 403 comes from the cluster: the refusal text often mentions a token or
        // authentication, which the UI would otherwise take for an expired Ambari session.
        if (NamespaceScope.isForbidden(e)) {
            LOG.warn("Kubernetes API refused the request for lack of rights: {}", e.getMessage());
            return Response.status(Response.Status.FORBIDDEN).entity("K8S_FORBIDDEN: " + e.getMessage()).build();
        }
        LOG.error("Unexpected Kubernetes API error", e);
        return Response.serverError().entity(e.getMessage()).build();
    }
    
    /**
     * Sub-resource for /helm/* under /resources/api: the releases of the cluster the request names in its
     * {@code cluster} query parameter, the default one when it names none.
     */
    @Path("/helm")
    public HelmResource helm(@Context HttpHeaders headers, @QueryParam("cluster") String cluster,
                             @QueryParam(ProtectedClusters.PARAM) String confirmCluster) {
        return new HelmResource(viewContext, headers, requestedCluster(cluster), confirmCluster);
    }

    @Path("commands")
    public CommandResource commands(@Context HttpHeaders headers) {
        return new CommandResource(viewContext, this.getAmbariAliasResolver(), headers);
    }


    /**
     * Sub-resource for Service Discovery
     * URL: /api/v1/.../resources/api/discovery
     */
    @Path("/discovery")
    public DiscoveryResource discovery(@Context HttpHeaders headers, @QueryParam("cluster") String cluster) {
        // What the deploy wizard discovers is on the cluster it deploys to.
        return new DiscoveryResource(viewContext, kubernetesService(requestedCluster(cluster)), headers);
    }

    /**
     * The cluster a request names in its {@code cluster} query parameter: the default one when it names none.
     *
     * @throws NotFoundException when the view knows no cluster of that id
     */
    private ClusterRef requestedCluster(String id) {
        return id == null || id.isBlank() ? ClusterRef.DEFAULT : knownCluster(id.trim());
    }

    /**
     * GitOps output profiles: how a GitOps deploy writes a release into Git, and the repository preview.
     * URL: /api/v1/.../resources/api/gitops/profiles
     */
    @Path("/gitops/profiles")
    public GitOpsProfileResource gitOpsProfiles(@Context HttpHeaders headers) {
        return new GitOpsProfileResource(viewContext, getKubernetesService(), headers);
    }

    /**
     * Sub-resource for Cluster Capabilities (cert-manager / external-secrets / OpenShift detection).
     * URL: /api/v1/.../resources/api/cluster/capabilities
     */
    @Path("/cluster/capabilities")
    public ClusterCapabilitiesResource clusterCapabilities(@QueryParam("cluster") String cluster) {
        // The operators a service needs (cert-manager, external-secrets...) are looked for on the cluster it goes to.
        return new ClusterCapabilitiesResource(kubernetesService(requestedCluster(cluster)));
    }

    /**
     * Sub-resource for Configuration Management
     * URL: /api/v1/.../resources/api/configurations
     */
    @Path("/configurations")
    public ConfigurationResource configuration(@Context HttpHeaders headers) {
        // Pass dependencies
        return new ConfigurationResource(viewContext, getKubernetesService(), headers);
    }

    /**
     * Sub-resource for the Company Issuing CA registry (PKI). Backs the
     * {@code signedByCompanyCA} ingress-TLS mode by holding admin-uploaded CAs
     * as K8s Secrets in the {@code ambari-pki} namespace.
     * URL: /api/v1/.../resources/api/pki/cas
     */
    @Path("/pki/cas")
    public org.apache.ambari.view.k8s.resources.CaRegistryResource caRegistry(@Context HttpHeaders headers) {
        return new org.apache.ambari.view.k8s.resources.CaRegistryResource(viewContext, getKubernetesService(), headers);
    }

    /**
     * Platform contexts: named ODP environments (Atlas/Ranger/Kerberos endpoints + auth)
     * that KDPS services integrate against. The managed default is auto-seeded from the
     * Ambari-managed cluster; external contexts are operator-defined.
     * URL: /api/v1/.../resources/api/contexts
     */
    @Path("/contexts")
    public org.apache.ambari.view.k8s.resources.ContextResource contexts(@Context HttpHeaders headers) {
        return new org.apache.ambari.view.k8s.resources.ContextResource(viewContext, headers);
    }
    /**
     * Reusable Trino catalogs: catalog .properties the operator defines once and attaches to
     * Trino releases from the deploy wizard (snapshot into the release values + reference kept).
     * URL: /api/v1/.../resources/api/trino-catalogs
     */
    @Path("/trino-catalogs")
    public org.apache.ambari.view.k8s.resources.TrinoCatalogResource trinoCatalogs(@Context HttpHeaders headers) {
        return new org.apache.ambari.view.k8s.resources.TrinoCatalogResource(viewContext, headers);
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
     * Lists the shared truststores the view keeps, and the Secrets matching the {@code *-truststore} convention on the
     * cluster the request names, with parsed {@code ca.crt} summaries. {@code namespace} is optional; null =
     * cluster-wide. Managed truststores are selectable in the wizard: the shared ones by every release, a project one
     * by the releases of its project.
     */
    @GET
    @Path("/truststores")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listTruststores(@QueryParam("namespace") String namespace, @QueryParam("cluster") String cluster) {
        try {
            return Response.ok(truststoreRegistry(cluster).list(namespace)).build();
        } catch (Exception e) {
            return handleError(e);
        }
    }

    /** The truststores of the view, with the project ones of the cluster a request names (the default one if none). */
    private TruststoreRegistryService truststoreRegistry(String cluster) {
        return new TruststoreRegistryService(viewContext, kubernetesService(requestedCluster(cluster)));
    }

    /**
     * The namespace that names the shared truststores, and whether the caller may change them: the page warns before
     * the operator tries. Body: {namespace, canWrite, message?}.
     */
    @GET
    @Path("/truststores/registry")
    @Produces(MediaType.APPLICATION_JSON)
    public Response truststoreRegistryStatus(@Context HttpHeaders headers) {
        try {
            String namespace = new PkiNamespaces(viewContext).truststoreNamespace();
            // The view keeps the shared truststores: who may change them is the view's configuration role.
            boolean canWrite = new AuthHelper(viewContext, headers).canConfigure();
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("namespace", namespace);
            out.put("canWrite", canWrite);
            if (!canWrite) {
                out.put("message", "Only an administrator of the view can change them.");
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return handleError(e);
        }
    }

    /**
     * A truststore change refused because KDPS may not write Secrets in the namespace: 403 with what to do. An expected
     * situation for an account limited to its own projects, so a warning, not an unexpected error.
     */
    private Response truststoreRefused(String namespace, Exception e) {
        String message = PkiNamespaces.forbiddenMessage(namespace);
        LOG.warn("{} ({})", message, e.getMessage());
        return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", message)).build();
    }

    /**
     * Checks who may change a truststore: a shared one may be trusted by every deploy, so changing it is a configuration
     * task; a project one reaches only the releases of its project, which deploying operators already change.
     */
    private void checkTruststorePermission(HttpHeaders headers, String namespace) {
        AuthHelper auth = new AuthHelper(viewContext, headers);
        // Decided from the view settings alone: the role is checked before anything talks to the cluster.
        String shared = new PkiNamespaces(viewContext).truststoreNamespace();
        if (namespace == null || namespace.isBlank() || shared.equals(namespace.trim())) {
            auth.checkConfigurationPermission();
        } else {
            auth.checkWritePermission();
        }
    }

    /**
     * Create a truststore from a public certificate PEM. Body: {name, caCertPem, default?, description?, namespace?};
     * without a namespace, or with the truststore project of Cluster Settings, a shared truststore.
     */
    @POST
    @Path("/truststores")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createTruststore(@Context HttpHeaders headers, @QueryParam("cluster") String cluster,
                                     Map<String, Object> body) {
        String namespace = body == null || body.get("namespace") == null ? null : String.valueOf(body.get("namespace"));
        checkTruststorePermission(headers, namespace);
        try {
            if (body == null) {
                return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "request body is required")).build();
            }
            String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
            String caCertPem = body.get("caCertPem") == null ? null : String.valueOf(body.get("caCertPem"));
            boolean makeDefault = Boolean.parseBoolean(String.valueOf(body.getOrDefault("default", false)));
            String description = body.get("description") == null ? null : String.valueOf(body.get("description"));
            return Response.ok(truststoreRegistry(cluster).create(namespace, name, caCertPem, makeDefault, description))
                    .build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", iae.getMessage())).build();
        } catch (Exception e) {
            if (namespace == null || namespace.isBlank()) {
                return handleError(e);
            }
            String target = namespace.trim();
            if (NamespaceScope.isForbidden(e)) {
                return truststoreRefused(target, e);
            }
            if (e instanceof io.fabric8.kubernetes.client.KubernetesClientException kce && kce.getCode() == 404) {
                return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error",
                        "Project " + target + " does not exist, or KDPS cannot see it.")).build();
            }
            return handleError(e);
        }
    }

    /** Toggle the default flag on a managed truststore. Body: {default: bool}. */
    @PUT
    @Path("/truststores/{namespace}/{name}/default")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setTruststoreDefault(@Context HttpHeaders headers,
                                         @QueryParam("cluster") String cluster,
                                         @PathParam("namespace") String namespace,
                                         @PathParam("name") String name,
                                         Map<String, Object> body) {
        checkTruststorePermission(headers, namespace);
        try {
            boolean value = body == null || !body.containsKey("default")
                    ? true : Boolean.parseBoolean(String.valueOf(body.get("default")));
            return Response.ok(truststoreRegistry(cluster).setDefault(namespace, name, value)).build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", iae.getMessage())).build();
        } catch (Exception e) {
            if (NamespaceScope.isForbidden(e)) {
                return truststoreRefused(namespace, e);
            }
            return handleError(e);
        }
    }

    /** Delete a managed truststore (refuses non view-managed / per-release truststores). */
    @DELETE
    @Path("/truststores/{namespace}/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteTruststore(@Context HttpHeaders headers,
                                     @QueryParam("cluster") String cluster,
                                     @PathParam("namespace") String namespace,
                                     @PathParam("name") String name) {
        checkTruststorePermission(headers, namespace);
        try {
            truststoreRegistry(cluster).delete(namespace, name);
            return Response.ok(Map.of("deleted", true, "namespace", namespace, "name", name)).build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", iae.getMessage())).build();
        } catch (Exception e) {
            if (NamespaceScope.isForbidden(e)) {
                return truststoreRefused(namespace, e);
            }
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
