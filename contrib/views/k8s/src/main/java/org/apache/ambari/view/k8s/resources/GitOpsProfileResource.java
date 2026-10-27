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
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.service.CommandService;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.service.cluster.ClusterRef;
import org.apache.ambari.view.k8s.service.cluster.ClusterRegistry;
import org.apache.ambari.view.k8s.service.deployment.DeploymentContext;
import org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend;
import org.apache.ambari.view.k8s.service.gitops.GitOpsProfile;
import org.apache.ambari.view.k8s.service.gitops.GitOpsProfileStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.service.PathConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitOps output profiles: how a GitOps deploy writes a release into Git. Mounted at {@code /gitops/profiles}.
 *
 * <ul>
 *   <li>{@code GET    /gitops/profiles}         — the built-in and saved profiles</li>
 *   <li>{@code GET    /gitops/profiles/{name}}  — one profile</li>
 *   <li>{@code PUT    /gitops/profiles/{name}}  — save a profile (KDPS admins)</li>
 *   <li>{@code DELETE /gitops/profiles/{name}}  — delete a profile no release uses (KDPS admins)</li>
 *   <li>{@code POST   /gitops/profiles/preview} — the repository as a deploy would leave it, written nowhere
 *       (operators): body {@code {request, profile?}}, the deploy request as the wizard submits it and a profile not
 *       saved yet</li>
 * </ul>
 */
public class GitOpsProfileResource {

    private static final Logger LOG = LoggerFactory.getLogger(GitOpsProfileResource.class);

    private final ViewContext viewContext;
    private final KubernetesService kubernetesService;
    private final AuthHelper authHelper;
    private final HttpHeaders callerHeaders;

    /** The body of a preview. */
    public static class PreviewBody {
        public HelmDeployRequest request;
        public GitOpsProfile profile;
    }

    /**
     * @param callerHeaders headers of the request; the role check asks Ambari with the caller's credentials
     */
    public GitOpsProfileResource(ViewContext viewContext, KubernetesService kubernetesService,
                                 HttpHeaders callerHeaders) {
        this.viewContext = viewContext;
        this.kubernetesService = kubernetesService;
        this.authHelper = new AuthHelper(viewContext, callerHeaders);
        this.callerHeaders = callerHeaders;
    }

    private GitOpsProfileStore store() {
        return new GitOpsProfileStore(new InstanceSettings(viewContext));
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", String.valueOf(message))).build();
    }

    private static Response failure(String action, Exception ex) {
        LOG.warn("GitOps profile {} failed: {}", action, ex.toString());
        return Response.serverError().entity(Map.of("error", String.valueOf(ex.getMessage()))).build();
    }

    /** The profiles, built-in ones first. Body: {items, canConfigure}. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", store().list());
            out.put("canConfigure", authHelper.canConfigure());
            return Response.ok(out).build();
        } catch (Exception ex) {
            return failure("list", ex);
        }
    }

    @GET
    @Path("{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("name") String name) {
        try {
            GitOpsProfile profile = store().get(name);
            if (profile == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "No GitOps profile named " + name + ".")).build();
            }
            return Response.ok(profile).build();
        } catch (Exception ex) {
            return failure("get " + name, ex);
        }
    }

    /** Saves a profile under the name of the path; its version goes up. Answers the profile as saved. */
    @PUT
    @Path("{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response save(@PathParam("name") String name, GitOpsProfile profile) {
        // A profile decides what every deploy with it writes to Git: configuration.
        authHelper.checkConfigurationPermission();
        if (profile == null) {
            return badRequest("Empty request body.");
        }
        try {
            profile.name = name;
            return Response.ok(store().save(profile)).build();
        } catch (IllegalArgumentException iae) {
            return badRequest(iae.getMessage());
        } catch (Exception ex) {
            return failure("save " + name, ex);
        }
    }

    /** Deletes a saved profile. Refused while a release is recorded with it: its next deploy or uninstall needs it. */
    @DELETE
    @Path("{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("name") String name) {
        authHelper.checkConfigurationPermission();
        try {
            List<String> users = new ReleaseMetadataService(viewContext).findAll().stream()
                    .filter(r -> name.equals(r.getGitProfile()))
                    .map(r -> r.getNamespace() + "/" + r.getReleaseName())
                    .toList();
            if (!users.isEmpty()) {
                return Response.status(Response.Status.CONFLICT).entity(Map.of("error", "The GitOps profile " + name
                        + " is used by " + String.join(", ", users) + ": deploy them with another profile, or "
                        + "uninstall them, before deleting it.")).build();
            }
            store().delete(name);
            return Response.noContent().build();
        } catch (IllegalArgumentException iae) {
            return badRequest(iae.getMessage());
        } catch (Exception ex) {
            return failure("delete " + name, ex);
        }
    }

    /**
     * The repository as the deploy would leave it, with nothing written: Git is read with the credentials of the
     * request, and the chart downloaded as the deploy downloads it.
     */
    @POST
    @Path("preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response preview(PreviewBody body, @Context UriInfo uriInfo) {
        // Reads Git with the deploy's credentials and logs in to the chart registry, as deploying does.
        authHelper.checkWritePermission();
        if (body == null || body.request == null) {
            return badRequest("The preview needs the deploy request.");
        }
        try {
            // Rendered for the cluster the deploy goes to: its id fills {{ cluster }}, and its releases are its own.
            ClusterRef cluster = CommandService.kubernetesClusterOf(body.request);
            if (!cluster.isDefault() && new ClusterRegistry(new InstanceSettings(viewContext)).find(cluster.id()).isEmpty()) {
                return badRequest("KDPS knows no Kubernetes cluster '" + cluster + "'.");
            }
            FluxGitOpsBackend backend = new FluxGitOpsBackend(new PathConfig(viewContext).workDir(),
                    cluster.isDefault() ? kubernetesService : KubernetesService.get(viewContext, cluster), viewContext,
                    cluster);
            // The caller's Ambari server, as for a deploy: the cluster's Kerberos state decides a value.
            DeploymentContext context = new DeploymentContext(body.request.getRepoId(), body.request.getVersion(), null,
                    null, callerHeaders != null ? callerHeaders.getRequestHeaders() : null,
                    uriInfo != null ? uriInfo.getBaseUri() : null, null);
            return Response.ok(backend.previewProfile(body.request, body.profile, context)).build();
        } catch (IllegalArgumentException | IllegalStateException | java.io.IOException ex) {
            // A template, a path or a secret refused, a chart or a branch not found: what the operator can fix.
            return badRequest(ex.getMessage());
        } catch (Exception ex) {
            return failure("preview", ex);
        }
    }
}
