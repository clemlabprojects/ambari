package org.apache.ambari.view.k8s.resources;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandStatus;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.requests.KeytabRequest;
import org.apache.ambari.view.k8s.service.CommandService;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.net.URI;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class CommandResource {

  private final ViewContext viewContext;

  @Context
  private UriInfo uriInfo;

  @Context
  private HttpServletRequest request;

  public CommandResource(ViewContext vc) {
    this.viewContext = vc;
  }

  @POST
  @Path("/helm/deploy")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response submitDeploy(HelmDeployRequest req,
                               @QueryParam("repoId") String repoId,
                               @QueryParam("version") String version,
                               @QueryParam("kubeContext") String kubeContext,
                               @Context HttpHeaders headers, @Context UriInfo ui) {
    String id = CommandService.get(viewContext).submitDeploy(req, repoId, version, kubeContext, this.getCommandsUrl(ui));
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

  /**
   * Submit an async request to generate a keytab via Ambari Server action, then
   * create/update a Kubernetes Opaque Secret with the resulting keytab.
   *
   * Returns 202 with a Location header to poll the command status.
   */
  @POST
  @Path("/kerberos/keytab")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response requestKeytab(@Context HttpHeaders headers,
                                @Context UriInfo ui,
                                KeytabRequest req) {
    // Delegate to the scheduler in CommandService
    // here we need to pass headers because we will do a request on apache ambari server
    final String commandId = CommandService.get(viewContext).submitKeytabRequest(
            req,
            headers.getRequestHeaders(),
            ui.getBaseUri());

    // Build a stable href to this command’s status endpoint within the View
    final String base = getCommandsUrl(ui);
    final URI location = UriBuilder.fromUri(base).path(commandId).build();

    return Response.status(Response.Status.ACCEPTED)
            .entity(Map.of("id", commandId,"href", location.toString()))
            .location(location)
            .build();
  }

  private String getCommandsUrl() {
    // Ambari host/port is taken from ambari.properties
    // usually configured keys: "client.api.url" or "ambari.server.url"
    String ambariBase = viewContext.getAmbariProperty("client.api.url");
    if (ambariBase == null) {
      // Fallback: build from common defaults
      ambariBase = "http://localhost:8080/api/v1";
    }

    if (!ambariBase.endsWith("/")) {
      ambariBase = ambariBase + "/";
    }

    return ambariBase
            + "views/" + viewContext.getViewName()
            + "/versions/" + viewContext.getViewDefinition().getVersion()
            + "/instances/" + viewContext.getInstanceName()
            + "/resources/api/commands";
  }
  private String getCommandsUrl(UriInfo uriInfo) {
    String base = javax.ws.rs.core.UriBuilder.fromUri(uriInfo.getBaseUri())
            .path("views")
            .path(viewContext.getViewName())
            .path("versions")
            .path(viewContext.getViewDefinition().getVersion())
            .path("instances")
            .path(viewContext.getInstanceName())
            .build()
            .toString();

    // Ensure trailing slash, then append your suffix
    if (!base.endsWith("/")) {
      base = base + "/";
    }
    return base + "resources/api/commands";
  }
}
