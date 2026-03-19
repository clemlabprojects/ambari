package org.apache.ambari.view.k8s.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.store.VaultMappingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * Provides mapping of Vault profile fields -> Helm override paths.
 */
public class VaultMappingService {

    private static final Logger LOG = LoggerFactory.getLogger(VaultMappingService.class);
    private static final String RESOURCE_PATH = "KDPS/globals/vault.json";
    private final ViewContext viewContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VaultMappingService(ViewContext viewContext) {
        this.viewContext = viewContext;
    }

    /**
     * Load merged mapping (defaults + overrides) for all auth methods.
     */
    public Map<String, Map<String, String>> loadMergedMapping() {
        VaultMappingEntity entity;
        try {
            entity = ensureSeeded();
        } catch (PersistenceException ex) {
            throw new RuntimeException("Failed to load Vault mapping", ex);
        }
        Map<String, Map<String, String>> defaults = buildDefaultsFromVaultConfig();
        Map<String, Map<String, String>> overrides = readJson(entity != null ? entity.getOverridesJson() : null);
        if (defaults == null) defaults = Collections.emptyMap();
        if (overrides == null) overrides = Collections.emptyMap();

        Map<String, Map<String, String>> merged = new HashMap<>();
        for (String mode : defaults.keySet()) {
            merged.put(mode, new HashMap<>(defaults.get(mode)));
        }
        for (Map.Entry<String, Map<String, String>> entry : overrides.entrySet()) {
            merged.computeIfAbsent(entry.getKey(), k -> new HashMap<>());
            merged.get(entry.getKey()).putAll(entry.getValue());
        }
        return merged;
    }

    /**
     * Get mapping for a specific auth method.
     */
    public Map<String, String> mappingForMode(String mode) {
        if (mode == null) return Collections.emptyMap();
        Map<String, Map<String, String>> merged = loadMergedMapping();
        return merged.getOrDefault(mode.toLowerCase(), Collections.emptyMap());
    }

    private Map<String, Map<String, String>> readJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Map<String, String>>>() {});
        } catch (Exception ex) {
            LOG.warn("Failed to parse Vault mapping JSON: {}", ex.toString());
            return null;
        }
    }

    private Map<String, Map<String, String>> buildDefaultsFromVaultConfig() {
        Map<String, Map<String, String>> mapping = new HashMap<>();
        try (InputStream sec = getClass().getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (sec == null) return mapping;
            JsonNode root = objectMapper.readTree(sec);
            JsonNode props = root.get("properties");
            if (props != null && props.isArray()) {
                for (JsonNode prop : props) {
                    JsonNode nameNode = prop.get("name");
                    JsonNode helmNode = prop.get("helmPath");
                    if (nameNode == null || helmNode == null) {
                        continue;
                    }
                    String sourcePath = nameNode.asText();
                    if (sourcePath == null || sourcePath.isBlank()) {
                        continue;
                    }
                    JsonNode modesNode = prop.get("modes");
                    List<String> modes;
                    if (modesNode != null && modesNode.isArray()) {
                        modes = new ArrayList<>();
                        for (JsonNode m : modesNode) {
                            if (m != null && !m.asText().isBlank()) {
                                modes.add(m.asText().toLowerCase());
                            }
                        }
                    } else {
                        modes = List.of("kubernetes", "approle");
                    }

                    for (String mode : modes) {
                        String helmPath = null;
                        if (helmNode.isObject()) {
                            JsonNode perMode = helmNode.get(mode);
                            helmPath = perMode != null ? perMode.asText() : null;
                        } else {
                            helmPath = helmNode.asText();
                        }
                        if (helmPath == null || helmPath.isBlank()) {
                            continue;
                        }
                        mapping.computeIfAbsent(mode, k -> new HashMap<>()).put(sourcePath, helmPath);
                    }
                }
            }
        } catch (Exception ex) {
            LOG.warn("Failed to build mapping from vault.json: {}", ex.toString());
        }
        return mapping;
    }

    private VaultMappingEntity ensureSeeded() throws PersistenceException {
        VaultMappingEntity entity = null;
        try {
            entity = viewContext.getDataStore().find(VaultMappingEntity.class, VaultMappingEntity.SINGLETON_ID);
        } catch (PersistenceException ex) {
            LOG.warn("Failed to check Vault mapping seed: {}", ex.toString());
        }

        if (entity == null) {
            entity = new VaultMappingEntity();
            entity.setId(VaultMappingEntity.SINGLETON_ID);
            entity.setCreatedAt(Instant.now().toString());
            entity.setUpdatedAt(entity.getCreatedAt());
        }

        try {
            entity.setDefaultJson(objectMapper.writeValueAsString(buildDefaultsFromVaultConfig()));
            entity.setUpdatedAt(Instant.now().toString());
            viewContext.getDataStore().store(entity);
        } catch (Exception ex) {
            throw new PersistenceException("Failed to seed Vault mapping", ex);
        }
        return entity;
    }
}
