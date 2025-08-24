package org.apache.ambari.view.k8s;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.google.common.annotations.VisibleForTesting;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.UserPermissions;
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ViewConfigurationService;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;

import org.apache.ambari.view.k8s.resources.HelmResource;
import org.apache.ambari.view.k8s.resources.HelmRepoResource;
import org.apache.ambari.view.k8s.resources.CommandResource;

import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
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

    private KubernetesService getKubernetesService() {
        if (kubernetesService == null) {
            this.kubernetesService = new KubernetesService(viewContext);
        }
        return this.kubernetesService;
    }
    
    private ViewConfigurationService getConfigService() {
        if (configService == null) {
            this.configService = new ViewConfigurationService(this.viewContext);
        }
        return this.configService;
    }

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
     * Endpoint to trigger deployment of a Helm chart.
     * Handles response status through the Response object.
     * @param requestInputStream The input stream of the chart file to deploy.
     * @return A map with success or error message.
     */
    @POST
    @Path("/charts/deploy")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deployChart(InputStream requestInputStream) throws IOException {
        try {
            // Manually deserialize the JSON input stream into our DTO
            HelmDeployRequest deployRequest = objectMapper.readValue(requestInputStream, HelmDeployRequest.class);
            kubernetesService.deployHelmChart(deployRequest);
            return Response.ok(Collections.singletonMap("message", "Deployment for '" + deployRequest.getReleaseName() + "' initiated successfully.")).build();
        } catch (Exception e) {
            e.printStackTrace(); // Log the error
            return Response.serverError().entity(Collections.singletonMap("message", "Error during deployment: " + e.getMessage())).build();
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
    public Response uploadKubeconfig(InputStream fileInputStream) {
        // new AuthHelper(viewContext).checkConfigurationPermission();
        
        try {
            ViewConfigurationService configurationService = this.getConfigService();
            LOG.info("/cluster/config: Received kubeconfig upload request.");
            // Use a static filename since it's no longer provided by the request
            File configurationFile = configurationService.saveKubeconfigFile(fileInputStream, "kubeconfig.yaml");

            LOG.info("Kubeconfig successfully saved to {}", configurationFile.getAbsolutePath());
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
    public Response getClusterStats() {
        try {
            return Response.ok(getKubernetesService().getClusterStats()).build();
        } catch (Exception e) {
            return handleError(e);
        }
    }
    
    @GET
    @Path("/nodes")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getNodes() {
        try {
            return Response.ok(getKubernetesService().getNodes()).build();
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
            return Response.ok(getKubernetesService().getClusterEvents()).build();
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
        return new CommandResource(viewContext);
    }
}
