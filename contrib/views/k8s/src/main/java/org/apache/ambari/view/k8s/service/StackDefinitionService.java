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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.k8s.model.stack.StackConfig;
import org.apache.ambari.view.k8s.model.stack.StackProperty;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class StackDefinitionService {
    private static final Logger LOG = LoggerFactory.getLogger(StackDefinitionService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String BASE_PATH = "KDPS/services";

    private final String resourceRoot;

    public StackDefinitionService() {
        this.resourceRoot = null;
    }

    public StackDefinitionService(org.apache.ambari.view.ViewContext ctx) {
        String root = null;
        try {
            if (ctx != null) {
                ViewConfigurationService cfg = new ViewConfigurationService(ctx);
                root = cfg.getViewResourcePath();
            }
        } catch (Exception e) {
            LOG.warn("Unable to resolve view resource path for KDPS lookup: {}", e.toString());
        }
        this.resourceRoot = root;
    }

    // simple caches
    private final Map<String, StackServiceDef> serviceCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<StackConfig>> configCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile List<String> discoveredServices;

    /**
     * Lists all available service definitions under KDPS/services.
     * Keys are the directory names (uppercased for consistency).
     * Results are cached for the lifetime of the service.
     * @return map of serviceName -> StackServiceDef
     */
    public Map<String, StackServiceDef> listServiceDefinitions() {
        Map<String, StackServiceDef> result = new LinkedHashMap<>();

        for (String serviceName : discoverServiceDirectories()) {
            try {
                StackServiceDef def = getServiceDefinition(serviceName);
                if (def.name == null || def.name.isBlank()) {
                    def.name = serviceName.toUpperCase(Locale.ROOT);
                }
                LOG.info("Discovered service definition: {}", def.name);
                result.put(serviceName, def);
            } catch (Exception e) {
                LOG.warn("Skipping service {} due to error: {}", serviceName, e.toString());
            }
        }
        return result;
    }

    private List<String> discoverServiceDirectories() {
        if (discoveredServices != null && !discoveredServices.isEmpty()) {
            return discoveredServices;
        }
        List<String> names = new ArrayList<>();

        // 1) If view is exploded on disk, list from resourceRoot/KDPS/services
        LOG.info("Resource root for KDPS service discovery: {}", resourceRoot);
        Path dir = Paths.get(resourceRoot, "KDPS", "services");
        LOG.info("Looking for KDPS services in {}", dir.toString());
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .forEach(names::add);
                if (!names.isEmpty()) return names;
            } catch (Exception e) {
                LOG.warn("Failed listing KDPS/services from {}: {}", dir, e.toString());
            }
        }

        discoveredServices = names;
        return names;
    }

    /**
     * Reads definition: KDPS/services/{serviceName}/service.json
     * This replaces your old charts.json lookup for specific services.
     * @param serviceName logical service key (e.g. SUPERSET)
     * @return parsed StackServiceDef
     */
    public StackServiceDef getServiceDefinition(String serviceName) {
        StackServiceDef cached = serviceCache.get(serviceName.toUpperCase(Locale.ROOT));
        if (cached != null) return cached;
        String path = BASE_PATH + "/" + serviceName.toUpperCase() + "/service.json";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) throw new IllegalArgumentException("Service definition not found: " + path);
            StackServiceDef def = mapper.readValue(is, StackServiceDef.class);
            serviceCache.put(serviceName.toUpperCase(Locale.ROOT), def);
            return def;
        } catch (Exception e) {
            LOG.error("Failed to load service definition for " + serviceName, e);
            throw new RuntimeException("Could not load service definition", e);
        }
    }

    /**
     * Reads all configs: KDPS/services/{serviceName}/configurations/*.json
     * @param serviceName logical service key (e.g. SUPERSET)
     * @return list of StackConfig objects for this service
     */
    public List<StackConfig> getServiceConfigurations(String serviceName) {
        List<StackConfig> cached = configCache.get(serviceName.toUpperCase(Locale.ROOT));
        if (cached != null) return cached;
        List<StackConfig> configs = new ArrayList<>();
        String configPath = BASE_PATH + "/" + serviceName.toUpperCase() + "/configurations";

        // In a real jar, we cannot list directories. We hardcode the lookup list for now.
        List<String> files = List.of(
                serviceName.toLowerCase() + "-env.json",   // e.g. superset-env.json
                serviceName.toLowerCase() + "-files.json"  // e.g. superset-files.json
        );
        loadConfigsInto(configs, configPath, files, configPath + "/templates");
        configCache.put(serviceName.toUpperCase(Locale.ROOT), configs);
        return configs;
    }

    private void loadConfigsInto(List<StackConfig> target, String basePath, List<String> files, String templateBase) {
        for (String file : files) {
            String fullPath = basePath + "/" + file;
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(fullPath)) {
                if (is == null) continue;

                StackConfig config = mapper.readValue(is, StackConfig.class);

                if (config.properties != null) {
                    for (StackProperty prop : config.properties) {
                        if (prop.valueSourceFile != null) {
                            String templatePath = templateBase + "/" + prop.valueSourceFile;
                            prop.value = loadResourceString(templatePath);
                        }
                    }
                }
                target.add(config);
            } catch (Exception e) {
                LOG.warn("Error loading config file {}", fullPath, e);
            }
        }
    }

    private String loadResourceString(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) return "";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("Failed to read template: {}", path);
            return "";
        }
    }
}
