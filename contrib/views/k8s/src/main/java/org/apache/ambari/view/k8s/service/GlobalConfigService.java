package org.apache.ambari.view.k8s.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.k8s.model.stack.StackConfig;
import org.apache.ambari.view.k8s.model.stack.StackProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class GlobalConfigService {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalConfigService.class);
    private static final String GLOBAL_BASE = "KDPS/globals";
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String cachedFingerprint;
    private volatile long fingerprintTs;
    private final long fingerprintTtlMs = 30_000; // recompute at most every 30s

    /**
     * Load bundled global configuration profiles from KDPS/globals.
     * @return list of global StackConfig objects
     */
    public List<StackConfig> listGlobalConfigs() {
        List<StackConfig> out = new ArrayList<>();
        List<String> files = List.of("global-env.json", "security-env.json", "security.json");
        for (String f : files) {
            String full = GLOBAL_BASE + "/" + f;
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(full)) {
                if (is == null) continue;
                StackConfig cfg = mapper.readValue(is, StackConfig.class);
                if (cfg.properties != null) {
                    for (StackProperty p : cfg.properties) {
                        if (p.valueSourceFile != null) {
                            String tpl = GLOBAL_BASE + "/" + p.valueSourceFile;
                            p.value = load(tpl);
                        }
                    }
                }
                out.add(cfg);
            } catch (Exception e) {
                LOG.warn("Failed to load global config {}: {}", full, e.toString());
            }
        }
        return out;
    }

    private String load(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) return "";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Computes a fingerprint of all global configs (stable ordering + canonical JSON). */
    public String fingerprint() {
        long now = System.currentTimeMillis();
        if (cachedFingerprint != null && (now - fingerprintTs) < fingerprintTtlMs) {
            return cachedFingerprint;
        }
        synchronized (this) {
            if (cachedFingerprint != null && (now - fingerprintTs) < fingerprintTtlMs) {
                return cachedFingerprint;
            }
            try {
                List<StackConfig> configs = listGlobalConfigs();
                String json = mapper.writeValueAsString(configs);
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(json.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) sb.append(String.format("%02x", b));
                cachedFingerprint = sb.toString();
                fingerprintTs = now;
                return cachedFingerprint;
            } catch (Exception e) {
                LOG.warn("Failed to fingerprint global configs: {}", e.toString());
                return null;
            }
        }
    }
}
