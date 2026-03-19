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
import org.apache.ambari.view.k8s.dto.security.SecurityProfilesDTO;
import org.apache.ambari.view.k8s.service.ConfigurationBootstrapService;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.service.SecurityProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST Endpoint for managing Global Configuration Profiles (Kubernetes Secrets).
 */
@Path("/configurations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConfigurationResource {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationResource.class);
    private static final String MANAGED_LABEL = "ambari.clemlab.com/managed-config";
    private static final String SECURITY_SCHEMA = "KDPS/globals/security.json";

    @Inject
    private ViewContext viewContext;

    @Inject
    private KubernetesService kubernetesService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConfigurationResource(ViewContext ctx, KubernetesService kubernetesService){
        this.viewContext = ctx;
        this.kubernetesService = kubernetesService;
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
        try {
            // Lazy Bootstrap: Ensure defaults exist when listing
            new ConfigurationBootstrapService(viewContext, kubernetesService).ensureDefaults();

            // List secrets with the managed label
            return Response.ok(kubernetesService.listSecretsByLabel(MANAGED_LABEL, "true")).build();
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
        try {
            String content = kubernetesService.getManagedConfigContent(namespace, name, filename);
            return Response.ok(Map.of("content", content)).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveConfig(ManagedConfigDTO dto) {
        try {
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
            LOG.error("Failed to save configuration", e);
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{namespace}/{name}")
    public Response deleteConfig(@PathParam("namespace") String namespace, @PathParam("name") String name) {
        try {
            kubernetesService.deleteSecret(namespace, name);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    // -------- View Settings (instance-level) --------
    private static final String SETTINGS_KEY = "view.settings.json";

    @GET
    @Path("/settings")
    public Response getSettings() {
        try {
            String json = viewContext.getInstanceData(SETTINGS_KEY);
            if (json == null || json.isBlank()) {
                return Response.ok(Map.of()).build();
            }
            Map<?,?> parsed = objectMapper.readValue(json, Map.class);
            return Response.ok(parsed).build();
        } catch (Exception e) {
            LOG.error("Failed to load settings", e);
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/settings")
    public Response saveSettings(Map<String, Object> settings) {
        try {
            String json = objectMapper.writeValueAsString(settings);
            viewContext.putInstanceData(SETTINGS_KEY, json);
            return Response.ok().build();
        } catch (Exception e) {
            LOG.error("Failed to save settings", e);
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/security")
    public Response getSecurityConfig() {
        try {
            SecurityProfilesDTO cfg = loadProfilesFromStore();
            return Response.ok(cfg).build();
        } catch (Exception e) {
            LOG.error("Failed to load security config", e);
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/security")
    public Response saveSecurityConfig(SecurityProfilesDTO cfg) {
        try {
            SecurityProfilesDTO toSave = cfg != null ? cfg : new SecurityProfilesDTO();
            if (toSave.profiles == null) toSave.profiles = new HashMap<>();
            new SecurityProfileService(viewContext).saveProfiles(toSave);
            return Response.ok().build();
        } catch (Exception e) {
            LOG.error("Failed to save security config", e);
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    /**
     * Return namespace/release identifiers for the given security profile.
     * This helps the UI warn before deleting a profile that is still in active use.
     */
    @GET
    @Path("/security/{profile}/usage")
    public Response getSecurityProfileUsage(@PathParam("profile") String profile) {
        if (profile == null || profile.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Profile name is required")).build();
        }

        SecurityProfileService securityProfileService = new SecurityProfileService(viewContext);
        SecurityProfilesDTO profiles = securityProfileService.loadProfiles();
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
        if (profile == null || profile.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Profile name is required")).build();
        }

        SecurityProfileService securityProfileService = new SecurityProfileService(viewContext);
        SecurityProfilesDTO profiles = securityProfileService.loadProfiles();
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

    private SecurityProfilesDTO loadProfilesFromStore() throws Exception {
        return new SecurityProfileService(viewContext).loadProfiles();
    }
}
