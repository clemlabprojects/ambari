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

package org.apache.ambari.view.k8s.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.dto.security.OidcConfig;
import org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO;
import org.apache.ambari.view.k8s.dto.security.SecurityProfilesDTO;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic Bootstrapper.
 * Reads 'templates/bootstrap-manifest.json' and seeds Kubernetes Secrets.
 */
public class ConfigurationBootstrapService {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationBootstrapService.class);
    private static final String MANIFEST_PATH = "templates/bootstrap-manifest.json";

    private final ViewContext context;
    private final KubernetesService k8s;
    private final Gson gson;

    public ConfigurationBootstrapService(ViewContext context, KubernetesService k8s) {
        this.context = context;
        this.k8s = k8s;
        this.gson = new Gson();
    }

    // DTO for the manifest JSON
    private static class BootstrapEntry {
        String name;
        String filename;     // key in the secret
        String resourcePath; // path in classpath to read content from
        String type;         // ambari.clemlab.com/config-type
        String language;     // for UI highlighting
        String description;
    }

    public void ensureDefaults() {
        String targetNs = "dashboarding"; // Or context.getProperties().get("k8s.default.namespace");

        try {
            // 1. Ensure Namespace
            k8s.createNamespace(targetNs);

            // 2. Load Manifest
            List<BootstrapEntry> entries = loadManifest();
            if (entries == null || entries.isEmpty()) {
                LOG.warn("No bootstrap entries found in {}", MANIFEST_PATH);
                return;
            }

            // 3. Process Entries
            for (BootstrapEntry entry : entries) {
                processEntry(targetNs, entry);
            }

        } catch (Exception e) {
            LOG.warn("Failed to bootstrap configuration defaults: {}", e.getMessage());
        }
    }

    private List<BootstrapEntry> loadManifest() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(MANIFEST_PATH)) {
            if (is == null) return null;
            return gson.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), new TypeToken<List<BootstrapEntry>>(){}.getType());
        } catch (Exception e) {
            LOG.error("Failed to parse bootstrap manifest", e);
            return null;
        }
    }

    private void processEntry(String namespace, BootstrapEntry entry) {
        try {
            // Check if secret exists (lightweight check)
            boolean exists = k8s.getClient().secrets().inNamespace(namespace).withName(entry.name).get() != null;
            if (exists) {
                return; // Do not overwrite existing defaults, user might have customized them
            }

            // Load content from classpath
            String content = loadResourceContent(entry.resourcePath);
            if (content == null) {
                LOG.error("Bootstrap content not found for {}", entry.name);
                return;
            }

            LOG.info("Seeding default configuration: {}/{}", namespace, entry.name);

            Map<String, String> labels = new HashMap<>();
            labels.put("managed-by", "ambari-k8s-view");
            labels.put("ambari.clemlab.com/managed-config", "true");
            labels.put("ambari.clemlab.com/is-default", "true");
            if (entry.type != null) labels.put("ambari.clemlab.com/config-type", entry.type);

            Map<String, String> annotations = new HashMap<>();
            if (entry.description != null) annotations.put("ambari.clemlab.com/description", entry.description);
            annotations.put("ambari.clemlab.com/filename", entry.filename);
            annotations.put("ambari.clemlab.com/language", entry.language);

            k8s.createOrUpdateOpaqueSecret(
                    namespace,
                    entry.name,
                    entry.filename,
                    content.getBytes(StandardCharsets.UTF_8),
                    labels,
                    annotations,
                    false // Mutable
            );

        } catch (Exception e) {
            LOG.error("Failed to process bootstrap entry " + entry.name, e);
        }
    }

    /**
     * Auto-creates a default OIDC security profile when Keycloak is configured in Ambari
     * and no OIDC profile exists yet for this view instance.
     * Called lazily on first GET /security so no Ambari credentials are needed at deploy time.
     *
     * @param ambariClient pre-authenticated Ambari client
     * @param cluster      Ambari cluster name
     */
    public void ensureDefaultOidcProfile(AmbariActionClient ambariClient, String cluster) {
        if (ambariClient == null || cluster == null || cluster.isBlank()) return;
        try {
            SecurityProfileService profileService = new SecurityProfileService(context);
            SecurityProfilesDTO existing = profileService.loadProfiles();

            // Skip if any OIDC profile already exists
            if (existing.profiles != null) {
                boolean hasOidc = existing.profiles.values().stream()
                        .anyMatch(cfg -> "oidc".equalsIgnoreCase(cfg.mode));
                if (hasOidc) return;
            }

            // Read OIDC realm config from Ambari
            String issuerUrl = null;
            String realm = null;
            try {
                issuerUrl = ambariClient.getDesiredConfigProperty(cluster, "oidc-env", "oidc_issuer_url");
            } catch (Exception ignored) {}
            try {
                realm = ambariClient.getDesiredConfigProperty(cluster, "oidc-env", "oidc_realm");
            } catch (Exception ignored) {}

            if ((issuerUrl == null || issuerUrl.isBlank()) && (realm == null || realm.isBlank())) {
                LOG.debug("No OIDC realm configured in Ambari cluster {} — skipping default OIDC profile creation", cluster);
                return;
            }

            // Compute issuer URL from admin URL + realm if not set directly
            if (issuerUrl == null || issuerUrl.isBlank()) {
                try {
                    String adminUrl = ambariClient.getDesiredConfigProperty(cluster, "oidc-env", "oidc_admin_url");
                    if (adminUrl != null && !adminUrl.isBlank() && realm != null && !realm.isBlank()) {
                        issuerUrl = adminUrl.replaceAll("/$", "") + "/realms/" + realm;
                    }
                } catch (Exception ignored) {}
            }

            if (issuerUrl == null || issuerUrl.isBlank()) return;

            // Build the default internal OIDC profile
            SecurityConfigDTO profileCfg = new SecurityConfigDTO();
            profileCfg.mode = "oidc";
            OidcConfig oidcCfg = new OidcConfig();
            oidcCfg.source = "internal";
            oidcCfg.issuerUrl = issuerUrl;
            oidcCfg.scopes = "openid email profile";
            oidcCfg.userClaim = "preferred_username";
            oidcCfg.groupsClaim = "groups";
            profileCfg.oidc = oidcCfg;

            String profileKey = "keycloak";
            SecurityProfilesDTO updated = existing != null ? existing : new SecurityProfilesDTO();
            if (updated.profiles == null) updated.profiles = new HashMap<>();
            updated.profiles.put(profileKey, profileCfg);
            if (updated.defaultProfile == null || updated.defaultProfile.isBlank()) {
                updated.defaultProfile = profileKey;
            }

            profileService.saveProfiles(updated);
            LOG.info("Auto-created default OIDC security profile '{}' with issuerUrl={}", profileKey, issuerUrl);

        } catch (Exception ex) {
            LOG.warn("Failed to auto-create default OIDC security profile: {}", ex.toString());
        }
    }

    private String loadResourceContent(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}