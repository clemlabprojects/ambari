package org.apache.ambari.view.k8s.resources;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandStatus;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.CommandService;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class CommandResource {

  private final ViewContext viewContext;

  public CommandResource(ViewContext vc) {
    this.viewContext = vc;
  }

  @POST
  @Path("/helm/deploy")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response submitDeploy(HelmDeployRequest req,
                               @QueryParam("repoId") String repoId,
                               @QueryParam("version") String version,
                               @QueryParam("kubeContext") String kubeContext) {
    String id = CommandService.get(viewContext).submitHelmDeploy(req, repoId, version, kubeContext);
    return Response.status(Response.Status.ACCEPTED)
        .entity(Map.of("id", id))
        .build();
  }

  @GET
  @Path("/{id}")
  public Response getStatus(@PathParam("id") String id) {
    CommandStatus st = CommandService.get(viewContext).getStatus(id);
    if (st == null) return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error","unknown id")).build();
    return Response.ok(st).build();
  }
}
