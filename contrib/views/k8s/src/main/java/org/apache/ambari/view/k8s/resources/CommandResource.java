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

  /**
   * Creates a CommandResource bound to the given view context with default alias resolution.
   *
   * @param vc active Ambari view context
   */
  public CommandResource(ViewContext vc) {

    this.viewContext = vc;
    this.ambariAliasResolver = new AmbariAliasResolver(this.viewContext);
    this.commandLogService = new CommandLogService(vc);
  }

  /**
   * Creates a CommandResource with a custom alias resolver (primarily for testing).
   *
   * @param vc                  active Ambari view context
   * @param ambariAliasResolver resolver to use for Ambari host/port alias substitution
   */
  public CommandResource(ViewContext vc, AmbariAliasResolver ambariAliasResolver) {
    this.viewContext = vc;
    this.ambariAliasResolver = ambariAliasResolver;
    this.commandLogService = new CommandLogService(vc);
  }

  /**
   * Submit an asynchronous Helm deploy command and return its tracking id immediately.
   *
   * @param req        chart, release name, namespace, and values for the deploy
   * @param repoId     optional repository id from which to fetch the chart
   * @param version    optional chart version to install
   * @param kubeContext optional kubeconfig context override
   * @param headers    incoming request headers forwarded for Ambari authentication
   * @param ui         request URI info used to construct the command status URL
   * @return HTTP 202 with the command {@code id}, or 400 on invalid input
   */
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

  /**
   * Return a paginated list of all background commands, most recent first.
   *
   * @param limit  maximum number of commands to return
   * @param offset zero-based index of the first result
   * @return paginated command list, or an error response on failure
   */
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

  /**
   * Return all child (sub-step) commands for a given parent command id.
   *
   * @param id parent command id
   * @return list of child command statuses, or an error response on failure
   */
  @GET
  @Path("/{id}/children")
  public Response listChildren(@PathParam("id") String id) {
    try {
      return Response.ok(CommandService.get(viewContext).listChildren(id)).build();
    } catch (Exception ex) {
      return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
    }
  }

  /**
   * Retrieve the current status of a single command by its id.
   *
   * @param id command id to look up
   * @return the command status, or HTTP 404 if the id is unknown
   */
  @GET
  @Path("/{id}")
  public Response getStatus(@PathParam("id") String id) {
    CommandStatus st = CommandService.get(viewContext).getStatus(id);
    if (st == null) return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error","unknown id")).build();
    return Response.ok(st).build();
  }

  /**
   * Request cancellation of a running command.
   *
   * @param id id of the command to cancel
   * @return HTTP 202 with the command id and state {@code CANCELLED}
   */
  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") String id) {
    CommandService.get(viewContext).cancel(id);
    return Response.status(Response.Status.ACCEPTED)
            .entity(Map.of("id", id, "state", "CANCELLED"))
            .build();
  }

  /**
   * Stream a chunk of the log output produced by a command.
   *
   * @param id     command id whose log to read
   * @param offset byte offset within the log file to start reading from
   * @param limit  maximum number of bytes to return in this chunk
   * @return JSON containing {@code content}, {@code nextOffset}, {@code eof}, and {@code size} fields
   */
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
