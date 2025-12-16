package org.apache.ambari.view.k8s.resources;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandStatus;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.requests.KeytabRequest;
import org.apache.ambari.view.k8s.service.CommandService;
import org.apache.ambari.view.k8s.service.CommandLogService;
import org.apache.ambari.view.k8s.utils.AmbariAliasResolver;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.net.URI;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class CommandResource {

  private final ViewContext viewContext;

  private final AmbariAliasResolver ambariAliasResolver;
  private final CommandLogService commandLogService;

  @Context
  private UriInfo uriInfo;

  @Context
  private HttpServletRequest request;

  public CommandResource(ViewContext vc) {

    this.viewContext = vc;
    this.ambariAliasResolver = new AmbariAliasResolver(this.viewContext);
    this.commandLogService = new CommandLogService(vc);
  }

  public CommandResource(ViewContext vc, AmbariAliasResolver ambariAliasResolver) {
    this.viewContext = vc;
    this.ambariAliasResolver = ambariAliasResolver;
    this.commandLogService = new CommandLogService(vc);
  }

  @POST
  @Path("/helm/deploy")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response submitDeploy(HelmDeployRequest req,
                               @QueryParam("repoId") String repoId,
                               @QueryParam("version") String version,
                               @QueryParam("kubeContext") String kubeContext,
                               @Context HttpHeaders headers, @Context UriInfo ui) {
    try {
      String id = CommandService.get(viewContext).submitDeploy(req, repoId, version, kubeContext, this.getCommandsUrl(ui), headers.getRequestHeaders(), ui.getBaseUri(), this.ambariAliasResolver);
      return Response.status(Response.Status.ACCEPTED)
              .entity(Map.of("id", id))
              .build();
    } catch (IllegalArgumentException iae) {
      return Response.status(Response.Status.BAD_REQUEST)
              .entity(Map.of("error", iae.getMessage()))
              .build();
    }
  }

  @GET
  @Path("/")
  public Response listCommands(@QueryParam("limit") @DefaultValue("10") int limit,
                               @QueryParam("offset") @DefaultValue("0") int offset) {
    try {
      return Response.ok(CommandService.get(viewContext).listCommands(limit, offset)).build();
    } catch (Exception ex) {
      return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
    }
  }

  @GET
  @Path("/{id}/children")
  public Response listChildren(@PathParam("id") String id) {
    try {
      return Response.ok(CommandService.get(viewContext).listChildren(id)).build();
    } catch (Exception ex) {
      return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
    }
  }

  @GET
  @Path("/{id}")
  public Response getStatus(@PathParam("id") String id) {
    CommandStatus st = CommandService.get(viewContext).getStatus(id);
    if (st == null) return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error","unknown id")).build();
    return Response.ok(st).build();
  }

  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") String id) {
    CommandService.get(viewContext).cancel(id);
    return Response.status(Response.Status.ACCEPTED)
            .entity(Map.of("id", id, "state", "CANCELLED"))
            .build();
  }

  @GET
  @Path("/{id}/logs")
  public Response getLogs(@PathParam("id") String id,
                          @QueryParam("offset") @DefaultValue("0") long offset,
                          @QueryParam("limit") @DefaultValue("65536") int limit) {
    CommandLogService.LogChunk chunk = commandLogService.read(id, offset, limit);
    return Response.ok(Map.of(
            "content", chunk.content,
            "nextOffset", chunk.nextOffset,
            "eof", chunk.eof,
            "size", chunk.size
    )).build();
  }

  /**
   * Trigger a refresh/restart of shared dependencies (e.g., mutating webhooks) so they pull the latest image.
   */
  @POST
  @Path("/dependencies/refresh")
  public Response refreshDependencies(@Context UriInfo ui) {
    try {
      CommandService.get(viewContext).refreshSharedDependencies();
      return Response.ok(Map.of("status", "ok")).build();
    } catch (Exception ex) {
      return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
    }
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
