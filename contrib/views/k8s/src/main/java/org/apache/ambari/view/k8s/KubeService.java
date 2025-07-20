package org.apache.ambari.view.k8s;

import com.google.common.annotations.VisibleForTesting;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.UserPermissions;
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ViewConfigurationService;
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

@Path("/")
public class KubeService {

    private static final Logger LOG = LoggerFactory.getLogger(KubeService.class);

    @Inject
    private ViewContext viewContext;

    private KubernetesService kubernetesService;

    private KubernetesService getKubernetesService() {
        if (kubernetesService == null) {
            this.kubernetesService = new KubernetesService(viewContext);
        }
        return this.kubernetesService;
    }

    @VisibleForTesting
    void setKubernetesService(KubernetesService kubernetesService) {
        this.kubernetesService = kubernetesService;
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
    @Consumes(MediaType.APPLICATION_OCTET_STREAM) // On attend maintenant un flux de données brutes
    public Response uploadKubeconfig(InputStream fileInputStream) {
        // new AuthHelper(viewContext).checkConfigurationPermission();
        
        try {
            ViewConfigurationService configService = new ViewConfigurationService(viewContext);
            
            // On utilise un nom de fichier statique car il n'est plus fourni par la requête
            File configFile = configService.saveKubeconfigFile(fileInputStream, "kubeconfig.yaml");
            
            configService.saveKubeconfigPath(configFile.getAbsolutePath());

            LOG.info("Kubeconfig sauvegardé avec succès dans {}", configFile.getAbsolutePath());
            return Response.ok(Collections.singletonMap("message", "Configuration sauvegardée.")).build();

        } catch (IOException e) {
            LOG.error("Erreur lors de la sauvegarde du fichier kubeconfig uploadé", e);
            return Response.serverError().entity("Erreur lors de la sauvegarde du fichier.").build();
        }
    }

    @GET
    @Path("/cluster/configured")
    @Produces(MediaType.APPLICATION_JSON) // On attend maintenant un flux de données brutes
    public Response checkIsViewConfigured(InputStream fileInputStream) {
        // new AuthHelper(viewContext).checkConfigurationPermission();
        ViewConfigurationService configService = new ViewConfigurationService(viewContext);
        boolean isConfigured = configService.isConfigured();

        LOG.info("Received request to check if the view is configured : {}", isConfigured);
        if (!isConfigured) {
            LOG.warn("View is not configured. Returning 'unconfigured' status.");
            return Response.status(Response.Status.PRECONDITION_FAILED).entity("UNCONFIGURED").build();
        }else{
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

    @GET
    @Path("/helm/releases")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getHelmReleases() {
        try {
            return Response.ok(getKubernetesService().getHelmReleases()).build();
        } catch (Exception e) {
            return handleError(e);
        }
    }

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

    private Response handleError(Exception e) {
        if (e instanceof IllegalStateException) {
            LOG.warn("Tentative d'accès à une ressource sans configuration: {}", e.getMessage());
            return Response.status(Response.Status.PRECONDITION_FAILED).entity(e.getMessage()).build();
        }
        LOG.error("Erreur inattendue de l'API Kubernetes", e);
        return Response.serverError().entity(e.getMessage()).build();
    }
}
