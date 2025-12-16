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
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;

import org.apache.ambari.view.k8s.utils.AmbariAliasResolver;
import org.apache.ambari.view.k8s.utils.WebHookBootstrap;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;

import java.util.Map;
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
        // new AuthHelper(viewContext).checkConfigurationPermission();
        
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
                        headers.getRequestHeaders(),
                        ui.getBaseUri()
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

    @GET
    @Path("/cluster/configured")
    @Produces(MediaType.APPLICATION_JSON)
    public Response checkIsViewConfigured(InputStream fileInputStream) {
        // new AuthHelper(viewContext).checkConfigurationPermission();
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
     * Sub-resource for Configuration Management
     * URL: /api/v1/.../resources/api/configurations
     */
    @Path("/configurations")
    public ConfigurationResource configuration() {
        // Pass dependencies
        return new ConfigurationResource(viewContext, getKubernetesService());
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
