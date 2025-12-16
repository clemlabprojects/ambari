package org.apache.ambari.view.k8s.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.ambari.view.ViewContext;
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

    private String loadResourceContent(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}