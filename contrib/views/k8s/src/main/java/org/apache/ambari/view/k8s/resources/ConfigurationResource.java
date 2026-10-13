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
import org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO;
import org.apache.ambari.view.k8s.dto.security.SecurityProfilesDTO;
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.service.ConfigurationBootstrapService;
import org.apache.ambari.view.k8s.service.ContextService;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.service.SecurityProfileService;
import org.apache.ambari.view.k8s.service.ViewSettingsService;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.apache.ambari.view.k8s.utils.AmbariLoopbackUrlResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.core.Context;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 * REST Endpoint for managing Global Configuration Profiles (Kubernetes Secrets).
 *
 * <p>Everything here is view configuration and can hold secrets (managed configuration files, the
 * monitoring object-store key, LDAP and OIDC credentials), so every endpoint requires the KDPS admin
 * role. The one exception is reading the security profiles, which the deploy wizard needs: operators
 * get them without their passwords and client secrets. Nobody gets those back: a read only says which
 * are set.
 */
@Path("/configurations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConfigurationResource {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationResource.class);
    private static final String MANAGED_LABEL = KubernetesService.MANAGED_CONFIG_LABEL;

    /** Kubernetes object name rules (RFC 1123), checked before any call reaches the cluster. */
    private static final Pattern NAMESPACE_NAME = Pattern.compile("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?");
    private static final Pattern SECRET_NAME =
            Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*");
    private static final Pattern SECRET_KEY = Pattern.compile("[-._a-zA-Z0-9]+");
    private static final String SECURITY_SCHEMA = "KDPS/globals/security.json";

    @Inject
    private ViewContext viewContext;

    @Inject
    private KubernetesService kubernetesService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** Headers of the request this resource serves: the role check asks Ambari with the caller's credentials. */
    private final HttpHeaders callerHeaders;
    private AuthHelper authHelper;

    /**
     * Creates the resource for one request.
     *
     * @param ctx               active Ambari view context
     * @param kubernetesService Kubernetes access of the view
     * @param callerHeaders     headers of the request; {@code null} when unknown
     */
    public ConfigurationResource(ViewContext ctx, KubernetesService kubernetesService, HttpHeaders callerHeaders){
        this.viewContext = ctx;
        this.kubernetesService = kubernetesService;
        this.callerHeaders = callerHeaders;
    }

    /** The caller's KDPS role for this request, looked up at most once. */
    private AuthHelper auth() {
        if (authHelper == null) {
            authHelper = new AuthHelper(viewContext, callerHeaders);
        }
        return authHelper;
    }

    public static class ManagedConfigDTO {
        public String name;
        public String namespace;
        public String type;
        public String filename;
        public String description;
        public String language;
        public String content;
        public boolean isDefault;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response listConfigs() {
        auth().checkConfigurationPermission();
        try {
            // Lazy Bootstrap: Ensure defaults exist when listing
            new ConfigurationBootstrapService(viewContext, kubernetesService).ensureDefaults();

            // List secrets with the managed label
            return Response.ok(kubernetesService.listSecretsByLabel(MANAGED_LABEL, "true")).build();
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to list configurations", e);
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/{namespace}/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfig(@PathParam("namespace") String namespace,
                              @PathParam("name") String name,
                              @QueryParam("filename") String filename) {
        auth().checkConfigurationPermission();
        String invalid = filename == null ? "The file name (filename) is required." : firstInvalid(namespace, name, filename);
        if (invalid != null) {
            return badRequest(invalid);
        }
        try {
            String content = kubernetesService.getManagedConfigContent(namespace, name, filename);
            return Response.ok(Map.of("content", content)).build();
        } catch (Exception e) {
            return errorResponse("read", namespace, name, e);
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveConfig(ManagedConfigDTO dto) {
        auth().checkConfigurationPermission();
        if (dto == null) {
            return badRequest("A configuration (namespace, name, filename and content) is required.");
        }
        String invalid = dto.filename == null ? "The file name (filename) is required."
                : firstInvalid(dto.namespace, dto.name, dto.filename);
        if (invalid == null && dto.content == null) {
            invalid = "The configuration content is required.";
        }
        if (invalid != null) {
            return badRequest(invalid);
        }
        try {
            // A new configuration may take a free name, but never replace a Secret the view does not manage.
            kubernetesService.requireWritableManagedConfig(dto.namespace, dto.name);

            Map<String, String> labels = new HashMap<>();
            labels.put("managed-by", "ambari-k8s-view");
            labels.put(MANAGED_LABEL, "true");
            if (dto.type != null) {
                labels.put("ambari.clemlab.com/config-type", dto.type);
            }

            Map<String, String> annotations = new HashMap<>();
            annotations.put("ambari.clemlab.com/description", dto.description);
            annotations.put("ambari.clemlab.com/filename", dto.filename);
            annotations.put("ambari.clemlab.com/language", dto.language);
            annotations.put("ambari.clemlab.com/last-updated", java.time.Instant.now().toString());

            // Create/Update Opaque Secret
            kubernetesService.createOrUpdateOpaqueSecret(
                    dto.namespace,
                    dto.name,
                    dto.filename, // data key
                    dto.content.getBytes(StandardCharsets.UTF_8),
                    labels,
                    annotations,
                    false // mutable
            );

            return Response.ok().build();
        } catch (Exception e) {
            return errorResponse("save", dto.namespace, dto.name, e);
        }
    }

    @DELETE
    @Path("/{namespace}/{name}")
    public Response deleteConfig(@PathParam("namespace") String namespace, @PathParam("name") String name) {
        auth().checkConfigurationPermission();
        String invalid = firstInvalid(namespace, name, null);
        if (invalid != null) {
            return badRequest(invalid);
        }
        try {
            kubernetesService.deleteManagedConfig(namespace, name);
            return Response.ok().build();
        } catch (Exception e) {
            return errorResponse("delete", namespace, name, e);
        }
    }

    /**
     * The first problem with the given Secret coordinates, or {@code null} when they are valid.
     *
     * @param filename the Secret key, or {@code null} when the caller does not use one
     */
    private static String firstInvalid(String namespace, String name, String filename) {
        if (namespace == null || !NAMESPACE_NAME.matcher(namespace).matches()) {
            return "The namespace must be a valid Kubernetes namespace name.";
        }
        if (name == null || name.length() > 253 || !SECRET_NAME.matcher(name).matches()) {
            return "The configuration name must be a valid Kubernetes Secret name "
                    + "(lower-case letters, digits, '-' and '.').";
        }
        if (filename != null && (filename.length() > 253 || !SECRET_KEY.matcher(filename).matches()
                || ".".equals(filename) || "..".equals(filename))) {
            return "The file name may only contain letters, digits, '-', '_' and '.'.";
        }
        return null;
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", message)).build();
    }

    /** Maps a managed-configuration failure to its HTTP status; refusals are never reported as server errors. */
    private static Response errorResponse(String action, String namespace, String name, Exception e) {
        if (e instanceof WebApplicationException) {
            throw (WebApplicationException) e;
        }
        if (e instanceof NoSuchElementException) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        }
        if (e instanceof KubernetesService.ManagedConfigConflictException) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of("error", e.getMessage())).build();
        }
        if (e instanceof SecurityException) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", e.getMessage())).build();
        }
        LOG.error("Failed to {} configuration {}/{}", action, namespace, name, e);
        return Response.serverError().entity(Map.of("error", String.valueOf(e.getMessage()))).build();
    }

    // -------- View Settings (instance-level, shared by every user of the instance) --------

    /**
     * The view settings, with the Thanos object store credentials masked: only whether each one is set is returned.
     */
    @GET
    @Path("/settings")
    public Response getSettings() {
        auth().checkConfigurationPermission();
        try {
            return Response.ok(new ViewSettingsService(viewContext).readMasked()).build();
        } catch (Exception e) {
            LOG.error("Failed to load settings", e);
            return Response.serverError().entity(Map.of("error", String.valueOf(e.getMessage()))).build();
        }
    }

    /**
     * Saves the view settings. A Thanos credential sent back masked or blank keeps the stored one.
     *
     * @return 200; 400 when the settings are too large to be saved
     */
    @POST
    @Path("/settings")
    public Response saveSettings(Map<String, Object> settings) {
        auth().checkConfigurationPermission();
        try {
            new ViewSettingsService(viewContext).save(settings);
            return Response.ok().build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            LOG.error("Failed to save settings", e);
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/security")
    public Response getSecurityConfig(@Context HttpHeaders headers, @Context UriInfo ui) {
        // Operators read the profiles to deploy (the wizard's profile picker); only admins see their secrets.
        AuthHelper auth = auth();
        auth.checkWritePermission();
        try {
            // Derive the default (local managed) context's OIDC security profile through the SAME
            // per-context flow used for EXTERNAL/REMOTE contexts — one profile named after the context,
            // marked derivedFromContext. The default context is virtual (no create/update event), so
            // this GET is its deterministic trigger; the reconcile is idempotent and writes only that
            // profile, and only when it changed, so the wizard and this page can read as often as they like.
            // The loopback uses the local FQDN — not the inbound baseUri — because a public proxy in front
            // of Ambari would PKIX-fail here and the catch below silently swallows it.
            try {
                String ambariBase = AmbariLoopbackUrlResolver.resolveApiBase(viewContext);
                String cluster = resolveClusterName(ambariBase, headers);
                AmbariActionClient ambariClient = new AmbariActionClient(
                        viewContext, ambariBase, cluster,
                        AmbariActionClient.toAuthHeaders(headers.getRequestHeaders()));
                new ContextService(viewContext)
                        .reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, ambariClient, cluster);
            } catch (Exception ignored) {
                // Best-effort — never block the GET if Ambari is unreachable
            }

            // Never a stored secret, whoever asks: only whether each one is set. Saving the mask back keeps it.
            SecurityProfilesDTO cfg = new SecurityProfileService(viewContext).loadMaskedProfiles();
            return Response.ok(auth.canConfigure() ? cfg : withoutSecrets(cfg)).build();
        } catch (Exception e) {
            LOG.error("Failed to load security config", e);
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    /**
     * Saves the security profiles: each profile sent is stored, and the stored ones it no longer lists are removed,
     * only among those the sender read ({@code loadedProfiles}) and never one derived from a platform context.
     *
     * @return 200; 400 naming a profile that cannot be stored (too large, no name, no settings); 409 when a profile
     *         left out is still used by releases. Nothing is changed on a 400 or a 409.
     */
    @POST
    @Path("/security")
    public Response saveSecurityConfig(SecurityProfilesDTO cfg) {
        auth().checkConfigurationPermission();
        try {
            SecurityProfilesDTO toSave = cfg != null ? cfg : new SecurityProfilesDTO();
            if (toSave.profiles == null) toSave.profiles = new HashMap<>();
            new SecurityProfileService(viewContext).saveProfiles(toSave);
            return Response.ok().build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (SecurityProfileService.ProfilesInUseException e) {
            List<String> releases = new ArrayList<>();
            e.releasesByProfile().values().forEach(releases::addAll);
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage(), "releases", releases))
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to save security config", e);
            return Response.serverError().entity(Map.of("error", String.valueOf(e.getMessage()))).build();
        }
    }

    /**
     * Return namespace/release identifiers for the given security profile.
     * This helps the UI warn before deleting a profile that is still in active use.
     */
    @GET
    @Path("/security/{profile}/usage")
    public Response getSecurityProfileUsage(@PathParam("profile") String profile) {
        auth().checkConfigurationPermission();
        if (profile == null || profile.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Profile name is required")).build();
        }

        SecurityProfileService securityProfileService = new SecurityProfileService(viewContext);
        SecurityProfilesDTO profiles;
        try {
            profiles = securityProfileService.loadProfiles();
        } catch (IllegalStateException e) {
            LOG.error("Failed to load security profiles", e);
            return Response.serverError().entity(Map.of("error", String.valueOf(e.getMessage()))).build();
        }
        if (profiles == null || profiles.profiles == null || !profiles.profiles.containsKey(profile)) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Profile not found")).build();
        }

        ReleaseMetadataService releaseMetadataService = new ReleaseMetadataService(viewContext);
        List<String> releases = releaseMetadataService.findReleasesUsingProfile(profile);
        return Response.ok(Map.of("releases", releases)).build();
    }

    /**
     * Delete a security profile if no releases are currently using it.
     */
    @DELETE
    @Path("/security/{profile}")
    public Response deleteSecurityProfile(@PathParam("profile") String profile) {
        auth().checkConfigurationPermission();
        if (profile == null || profile.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Profile name is required")).build();
        }

        SecurityProfileService securityProfileService = new SecurityProfileService(viewContext);
        SecurityProfilesDTO profiles;
        try {
            profiles = securityProfileService.loadProfiles();
        } catch (IllegalStateException e) {
            LOG.error("Failed to load security profiles", e);
            return Response.serverError().entity(Map.of("error", String.valueOf(e.getMessage()))).build();
        }
        if (profiles == null || profiles.profiles == null || !profiles.profiles.containsKey(profile)) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Profile not found")).build();
        }

        ReleaseMetadataService releaseMetadataService = new ReleaseMetadataService(viewContext);
        List<String> releases = releaseMetadataService.findReleasesUsingProfile(profile);
        if (!releases.isEmpty()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Profile is referenced by active releases", "releases", releases))
                    .build();
        }

        try {
            securityProfileService.deleteProfile(profile);
            return Response.ok().build();
        } catch (Exception e) {
            LOG.error("Failed to delete security profile {}", profile, e);
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }

    }

    @GET
    @Path("/security/schema")
    public Response getSecuritySchema() {
        auth().checkConfigurationPermission();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(SECURITY_SCHEMA)) {
            if (is == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "security schema not found")).build();
            }
            Object schema = objectMapper.readValue(is, Object.class);
            return Response.ok(schema).build();
        } catch (Exception ex) {
            LOG.error("Failed to load security schema", ex);
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    /**
     * A copy of the profiles without even the mask of the LDAP bind passwords and OIDC client secrets, nor the
     * free-form extra properties (which may hold credentials too), for users who may deploy with a profile but not
     * edit it.
     */
    static SecurityProfilesDTO withoutSecrets(SecurityProfilesDTO profiles) {
        if (profiles == null) {
            return null;
        }
        SecurityProfilesDTO copy = new ObjectMapper().convertValue(profiles, SecurityProfilesDTO.class);
        if (copy.profiles != null) {
            for (SecurityConfigDTO profile : copy.profiles.values()) {
                if (profile == null) {
                    continue;
                }
                if (profile.ldap != null) {
                    profile.ldap.bindPassword = null;
                    profile.ldap.adBindPassword = null;
                }
                if (profile.oidc != null) {
                    profile.oidc.clientSecret = null;
                }
                profile.extraProperties = null;
            }
        }
        return copy;
    }

    private String resolveClusterName(String ambariApiBase, HttpHeaders headers) throws Exception {
        if (viewContext.getCluster() != null) {
            return viewContext.getCluster().getName();
        }
        AmbariActionClient client = new AmbariActionClient(
                viewContext, ambariApiBase,
                AmbariActionClient.toAuthHeaders(headers.getRequestHeaders()));
        List<String> clusters = client.listClusters();
        if (clusters.isEmpty()) throw new IllegalStateException("No clusters found");
        String preferred = viewContext.getProperties().get("ambari.cluster.preferred");
        if (preferred != null && !preferred.isBlank()) {
            for (String c : clusters) {
                if (c.equalsIgnoreCase(preferred)) return c;
            }
        }
        return clusters.get(0);
    }
}
