package org.apache.ambari.view.k8s.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.dto.vault.VaultConfigDTO;
import org.apache.ambari.view.k8s.dto.vault.VaultProfilesDTO;
import org.apache.ambari.view.k8s.store.VaultProfileEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Persistence helper for Vault profiles.
 */
public class VaultProfileService {

    private static final Logger LOG = LoggerFactory.getLogger(VaultProfileService.class);
    private final ViewContext viewContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VaultProfileService(ViewContext viewContext) {
        this.viewContext = viewContext;
    }

    /**
     * Load all stored Vault profiles for the current view instance.
     *
     * @return vault profiles DTO containing the default profile name and the map of profiles
     */
    public VaultProfilesDTO loadProfiles() {
        VaultProfilesDTO dto = new VaultProfilesDTO();
        Map<String, VaultConfigDTO> profiles = new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            var found = (java.util.Collection<VaultProfileEntity>) viewContext.getDataStore()
                    .findAll(VaultProfileEntity.class, null);
            for (VaultProfileEntity entity : found) {
                if (!viewContext.getInstanceName().equals(entity.getViewInstance())) {
                    continue;
                }
                VaultConfigDTO cfg = objectMapper.readValue(entity.getProfileJson(), VaultConfigDTO.class);
                profiles.put(entity.getId(), cfg);
                if (entity.isDefault()) {
                    dto.defaultProfile = entity.getId();
                }
            }
        } catch (Exception ex) {
            LOG.warn("Failed to load Vault profiles from datastore: {}", ex.toString());
        }
        dto.profiles = profiles;
        if (dto.defaultProfile == null && !profiles.isEmpty()) {
            dto.defaultProfile = profiles.keySet().iterator().next();
        }
        return dto;
    }

    /**
     * Persist all profiles for the current view instance, replacing previous entries.
     *
     * @param profiles the DTO containing all profiles to write, including the default profile name
     * @throws Exception when serialization or datastore write fails
     */
    public void saveProfiles(VaultProfilesDTO profiles) throws Exception {
        if (profiles == null || profiles.profiles == null) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            var found = (java.util.Collection<VaultProfileEntity>) viewContext.getDataStore()
                    .findAll(VaultProfileEntity.class, null);
            for (VaultProfileEntity entity : found) {
                if (viewContext.getInstanceName().equals(entity.getViewInstance())) {
                    viewContext.getDataStore().remove(entity);
                }
            }
        } catch (Exception ex) {
            LOG.warn("Failed to purge old Vault profiles: {}", ex.toString());
        }

        for (Map.Entry<String, VaultConfigDTO> entry : profiles.profiles.entrySet()) {
            VaultProfileEntity entity = new VaultProfileEntity();
            entity.setId(entry.getKey());
            entity.setViewInstance(viewContext.getInstanceName());
            entity.setDefault(entry.getKey().equals(profiles.defaultProfile));
            entity.setProfileJson(objectMapper.writeValueAsString(entry.getValue()));
            viewContext.getDataStore().store(entity);
        }
        LOG.info("Persisted {} Vault profiles (default={})", profiles.profiles.size(), profiles.defaultProfile);
    }

    /**
     * Resolve a profile by name or default/first.
     *
     * @param requestedProfile profile name requested by caller; may be null/blank to use the default
     * @return the resolved Vault configuration, or null when none exist
     */
    public VaultConfigDTO resolveProfile(String requestedProfile) {
        VaultProfilesDTO profiles = loadProfiles();
        if (profiles == null || profiles.profiles == null || profiles.profiles.isEmpty()) {
            return null;
        }
        String profileName = requestedProfile;
        if (profileName == null || profileName.isBlank()) {
            profileName = profiles.defaultProfile;
        }
        if (profileName == null || profileName.isBlank()) {
            profileName = profiles.profiles.keySet().stream().findFirst().orElse(null);
        }
        VaultConfigDTO cfg = profileName != null ? profiles.profiles.get(profileName) : null;
        LOG.info("Loaded Vault profile '{}' with auth {}", profileName, cfg != null && cfg.auth != null ? cfg.auth.method : "none");
        return cfg;
    }

    /**
     * Compute a stable fingerprint for a Vault profile (canonical JSON SHA-256).
     */
    public String fingerprint(VaultConfigDTO cfg) {
        if (cfg == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(cfg);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            LOG.warn("Failed to fingerprint Vault profile: {}", ex.toString());
            return null;
        }
    }

    /**
     * Remove a profile for the current view instance.
     *
     * @param profileName logical profile identifier
     */
    public void deleteProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return;
        }
        try {
            VaultProfileEntity entity = viewContext.getDataStore().find(VaultProfileEntity.class, profileName);
            if (entity == null || !viewContext.getInstanceName().equals(entity.getViewInstance())) {
                return;
            }
            viewContext.getDataStore().remove(entity);
        } catch (PersistenceException ex) {
            LOG.warn("Failed to delete Vault profile {}: {}", profileName, ex.toString());
            throw new RuntimeException("Unable to delete Vault profile", ex);
        }
    }
}
