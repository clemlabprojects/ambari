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
import org.apache.ambari.view.k8s.model.DeploymentMode;
import org.apache.ambari.view.k8s.model.GitOpsDeployResult;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.requests.KeytabRequest;
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.apache.ambari.view.k8s.security.KeytabWebhookAuthenticator;
import org.apache.ambari.view.k8s.security.MasterKeyUnavailableException;
import org.apache.ambari.view.k8s.service.CommandService;
import org.apache.ambari.view.k8s.service.CommandLogService;
import org.apache.ambari.view.k8s.service.IngressTlsSecretGuard;
import org.apache.ambari.view.k8s.service.KeytabRequestPolicy;
import org.apache.ambari.view.k8s.service.WebHookConfigurationService;
import org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend;
import org.apache.ambari.view.k8s.service.deployment.ReleaseHeldInGitException;
import org.apache.ambari.view.k8s.service.helm.HelmOperationRegistry;
import org.apache.ambari.view.k8s.utils.AmbariAliasResolver;
import org.apache.ambari.view.k8s.utils.AmbariLoopbackUrlResolver;
import org.apache.ambari.view.k8s.utils.WebHookBootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.net.URI;
import java.util.LinkedHashMap;
import java.time.Instant;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class CommandResource {

  private static final Logger LOG = LoggerFactory.getLogger(CommandResource.class);

  /** The answer to a keytab request whose webhook proof does not check out; the reason goes to the view's log. */
  static final String WEBHOOK_NOT_AUTHENTICATED = "This keytab request claims to come from the KDPS keytab webhook, "
          + "but it is not signed with the client certificate KDPS issued to the webhook.";

  private final ViewContext viewContext;

  private final AmbariAliasResolver ambariAliasResolver;
  private final CommandLogService commandLogService;
  /** Headers of the request this resource serves: the role check asks Ambari with the caller's credentials. */
  private final HttpHeaders callerHeaders;
  private AuthHelper authHelper;

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
    this.commandLogService = CommandLogService.get(vc);
    this.callerHeaders = null;
  }

  /**
   * Creates a CommandResource with a custom alias resolver (primarily for testing).
   *
   * @param vc                  active Ambari view context
   * @param ambariAliasResolver resolver to use for Ambari host/port alias substitution
   * @param callerHeaders       headers of the request; the role check asks Ambari with the caller's credentials
   */
  public CommandResource(ViewContext vc, AmbariAliasResolver ambariAliasResolver, HttpHeaders callerHeaders) {
    this.viewContext = vc;
    this.ambariAliasResolver = ambariAliasResolver;
    this.commandLogService = CommandLogService.get(vc);
    this.callerHeaders = callerHeaders;
  }

  /** The caller's KDPS role for this request, looked up at most once. */
  private AuthHelper auth() {
    if (authHelper == null) {
      authHelper = new AuthHelper(viewContext, callerHeaders);
    }
    return authHelper;
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
   * @return HTTP 202 with the command {@code id} and the deploy's {@code mode}, plus, for a GitOps deploy, what it does
   *         in Git ({@code gitOps}, a {@link GitOpsDeployResult}: the branch now, the commit and pull request in the
   *         operation's result once committed), 400 on invalid input, or 409 while a Helm operation or a GitOps
   *         operation on the release is still running, when the deploy asks for a direct Helm upgrade of a release
   *         deployed with Flux GitOps, or when the deploy's ingress certificate would replace an existing Secret KDPS
   *         did not issue and {@code replaceExistingTls} is not set (code and Secret in the body)
   */
  @POST
  @Path("/helm/deploy")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response submitDeploy(HelmDeployRequest req,
                               @QueryParam("repoId") String repoId,
                               @QueryParam("version") String version,
                               @QueryParam("kubeContext") String kubeContext,
                               @Context HttpHeaders headers, @Context UriInfo ui) {
    auth().checkWritePermission();
    // An operation keeps its parameters and the caller's Ambari session encrypted with the master key.
    if (!EncryptionService.forServer(viewContext).isMasterKeyAvailable()) {
      LOG.warn("submitDeploy refused (412): the Ambari master key was not found");
      return MasterKeyUnavailableException.response();
    }
    try {
      // Pass the LOCAL Ambari API base URI (not the inbound request's baseUri) so background workers
      // resuming this command later loop back to ambari-server directly rather than through whatever
      // reverse proxy the user happened to be on. See AmbariLoopbackUrlResolver.
      String id = CommandService.get(viewContext).submitDeploy(req, repoId, version, kubeContext, this.getCommandsUrl(ui), headers.getRequestHeaders(), AmbariLoopbackUrlResolver.resolveApiBaseUri(viewContext), this.ambariAliasResolver);
      return Response.status(Response.Status.ACCEPTED)
              .entity(deployAccepted(id, req))
              .build();
    } catch (HelmOperationRegistry.ReleaseBusyException | FluxGitOpsBackend.GitOpsBusyException
             | ReleaseHeldInGitException busy) {
      LOG.warn("submitDeploy refused (409): {}", busy.getMessage());
      return Response.status(Response.Status.CONFLICT)
              .entity(Map.of("error", busy.getMessage()))
              .build();
    } catch (IngressTlsSecretGuard.ReplacementNotConfirmedException unconfirmed) {
      return tlsReplacementNotConfirmed(unconfirmed);
    } catch (IllegalArgumentException | IllegalStateException validation) {
      LOG.warn("submitDeploy rejected (400): {}", validation.getMessage());
      return Response.status(Response.Status.BAD_REQUEST)
              .entity(Map.of("error", validation.getMessage()))
              .build();
    } catch (RuntimeException re) {
      // submitDeploy wraps backend.apply(...) exceptions in RuntimeException for logging; unwrap so
      // the operator gets a 400 carrying the validation message (which names the field to fill in)
      // instead of a 500 "Request failed." Both kinds are deliberate pre-flight rejections: bad
      // payload (IllegalArgumentException) and an incomplete deploy such as a Kerberized platform
      // context with no keytab selected (IllegalStateException).
      Throwable cause = re.getCause();
      if (cause instanceof MasterKeyUnavailableException noKey) {
        return noKey.getResponse();
      }
      if (cause instanceof HelmOperationRegistry.ReleaseBusyException
              || cause instanceof FluxGitOpsBackend.GitOpsBusyException
              || cause instanceof ReleaseHeldInGitException) {
        LOG.warn("submitDeploy refused (409, unwrapped): {}", cause.getMessage());
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", cause.getMessage()))
                .build();
      }
      if (cause instanceof IngressTlsSecretGuard.ReplacementNotConfirmedException unconfirmed) {
        return tlsReplacementNotConfirmed(unconfirmed);
      }
      if (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException) {
        LOG.warn("submitDeploy rejected (400, unwrapped): {}", cause.getMessage());
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", cause.getMessage()))
                .build();
      }
      throw re;
    }
  }

  /**
   * The answer to a deploy accepted: its operation, its mode and, for a GitOps deploy, what it does in Git, so the page
   * says where the change goes rather than only that a deploy started.
   */
  static Map<String, Object> deployAccepted(String id, HelmDeployRequest req) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", id);
    GitOpsDeployResult gitOps = CommandService.gitOpsSubmitted(req);
    body.put("mode", gitOps != null ? DeploymentMode.FLUX_GITOPS.name() : DeploymentMode.DIRECT_HELM.name());
    if (gitOps != null) {
      body.put(GitOpsDeployResult.KEY, gitOps);
    }
    return body;
  }

  /**
   * The answer to a deploy refused because its ingress certificate would replace an existing Secret KDPS did not
   * issue: 409, with a code and the Secret, so the wizard can ask the operator and deploy again with
   * {@code replaceExistingTls}.
   */
  private static Response tlsReplacementNotConfirmed(IngressTlsSecretGuard.ReplacementNotConfirmedException e) {
    LOG.warn("submitDeploy refused (409, ingress TLS Secret replacement not confirmed): {}", e.getMessage());
    return Response.status(Response.Status.CONFLICT)
            .entity(Map.of(
                    "error", e.getMessage(),
                    "code", IngressTlsSecretGuard.ReplacementNotConfirmedException.CODE,
                    "namespace", e.getNamespace(),
                    "secretName", e.getSecretName()))
            .build();
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
    auth().checkWritePermission();
    CommandService.get(viewContext).cancel(id);
    return Response.status(Response.Status.ACCEPTED)
            .entity(Map.of("id", id, "state", "CANCELLED"))
            .build();
  }

  /**
   * Resume a FAILED operation from the step that failed (re-runs that sub-step and continues,
   * skipping the already-succeeded steps) — for recovering from transient sub-step failures
   * without re-running the whole KDPS wizard flow.
   *
   * @param id id of the failed root command to resume
   * @return HTTP 202 with the command id and state {@code RUNNING}; 409 if it is not resumable
   */
  @POST
  @Path("/{id}/resume")
  public Response resume(@PathParam("id") String id, @Context HttpHeaders headers) {
    auth().checkWritePermission();
    try {
      // Pass the resume request's (fresh, authenticated) headers so replayed Ambari-callback steps
      // re-authenticate with a live session instead of the invalidated one captured at submit time.
      CommandService.get(viewContext).resume(id, headers.getRequestHeaders());
      return Response.status(Response.Status.ACCEPTED)
              .entity(Map.of("id", id, "state", "RUNNING"))
              .build();
    } catch (IllegalStateException ise) {
      return Response.status(Response.Status.CONFLICT)
              .entity(Map.of("id", id, "error", ise.getMessage()))
              .build();
    } catch (IllegalArgumentException iae) {
      return Response.status(Response.Status.NOT_FOUND)
              .entity(Map.of("id", id, "error", iae.getMessage()))
              .build();
    }
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
    auth().checkWritePermission();
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
   * <p>Two callers are accepted. The view's keytab webhook proves on each request that it holds the client
   * certificate the view issued to it ({@link KeytabWebhookAuthenticator}); its keytab is issued with the Ambari
   * credentials of the release's deploy. An operator's keytab is issued with the operator's own credentials. Either
   * way the keytab must be for a release KDPS deployed ({@link KeytabRequestPolicy}).
   *
   * @return 202 with a Location header to poll the command status; 403 when the caller is neither the webhook nor an
   *         operator, or the request is not for a release KDPS deployed
   */
  @POST
  @Path("/kerberos/keytab")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response requestKeytab(@Context HttpHeaders headers,
                                @Context UriInfo ui,
                                KeytabRequest req) {
    MultivaluedMap<String, String> requestHeaders = headers == null ? null : headers.getRequestHeaders();
    // A request claiming to come from the webhook is proven by its certificate or refused, never taken as the caller's.
    boolean fromWebhook = KeytabWebhookAuthenticator.claimsWebhook(requestHeaders);
    if (!fromWebhook) {
      auth().checkWritePermission();
    }
    if (req == null) {
      return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "No keytab request.")).build();
    }
    // The URI is the LOCAL Ambari API base (see AmbariLoopbackUrlResolver — required when Ambari sits behind a
    // reverse proxy).
    URI ambariApi = AmbariLoopbackUrlResolver.resolveApiBaseUri(viewContext);
    final String commandId;
    try {
      if (fromWebhook) {
        try {
          new KeytabWebhookAuthenticator(WebHookConfigurationService.certificateAuthorityPem(viewContext),
                  WebHookBootstrap.CLIENT_CERTIFICATE_CN).authenticate(requestHeaders, req, Instant.now());
        } catch (KeytabWebhookAuthenticator.RefusedException refused) {
          LOG.warn("Keytab request for {} in namespace {} REFUSED: it claims to come from the keytab webhook, but {}",
                  req.getPrincipal(), req.getNamespace(), refused.getMessage());
          throw AuthHelper.forbidden(WEBHOOK_NOT_AUTHENTICATED);
        }
        commandId = CommandService.get(viewContext).submitWebhookKeytabRequest(req, ambariApi);
      } else {
        // The operator's own Ambari credentials issue the keytab.
        commandId = CommandService.get(viewContext).submitKeytabRequest(req, requestHeaders, ambariApi);
      }
    } catch (KeytabRequestPolicy.RefusedException refused) {
      LOG.warn("Keytab request for {} in namespace {} REFUSED: {}", req.getPrincipal(), req.getNamespace(),
              refused.getMessage());
      throw AuthHelper.forbidden(refused.getMessage());
    }

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
