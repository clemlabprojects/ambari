package org.apache.ambari.view.k8s.resources;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.dto.argocd.ArgoApplicationRequest;
import org.apache.ambari.view.k8s.service.ArgoApplicationService;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Map;

/**
 * Minimal Argo CD integration endpoints.
 * Currently supports building an Application manifest for Helm sources (preview/export).
 */
@Path("/argocd")
@Produces(MediaType.APPLICATION_JSON)
public class ArgoResource {

    private final ViewContext viewContext;
    private final ArgoApplicationService argoApplicationService;

    public ArgoResource(ViewContext viewContext) {
        this.viewContext = viewContext;
        this.argoApplicationService = new ArgoApplicationService();
    }

    @POST
    @Path("/application/preview")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response previewApplication(ArgoApplicationRequest request) {
        try {
            String yaml = argoApplicationService.buildHelmApplication(request);
            return Response.ok(Map.of("application", yaml)).build();
        } catch (Exception ex) {
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }
}
