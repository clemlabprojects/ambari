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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StackDefinitionService {
    private static final Logger LOG = LoggerFactory.getLogger(StackDefinitionService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String BASE_PATH = "KDPS/services";

    public StackDefinitionService() {}

    public StackDefinitionService(org.apache.ambari.view.ViewContext ctx) {}

    // simple caches
    private final Map<String, StackServiceDef> serviceCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<StackConfig>> configCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile List<String> discoveredServices;

    private static final String CATALOG_PATH = BASE_PATH + "/catalog.json";

    /**
     * Lists every available service definition: the curated {@code KDPS/services/catalog.json}
     * manifest (which fixes the display order) UNION any drop-in {@code KDPS/services/<KEY>/service.json}
     * found on the classpath but not listed in the manifest. Keys are the service directory names.
     * Results are cached for the lifetime of the service.
     * @return map of serviceName -> StackServiceDef
     */
    public Map<String, StackServiceDef> listServiceDefinitions() {
        Map<String, StackServiceDef> result = new LinkedHashMap<>();

        for (String serviceName : discoverServiceKeys()) {
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

    /**
     * Resolve the set of service keys. Historically this read ONLY the hardcoded
     * {@code catalog.json} manifest (a jar cannot be listed over the classpath), so a service.json
     * dropped into KDPS/services without a manifest entry was silently invisible. Now:
     * <ol>
     *   <li>{@code catalog.json} is still read first and its order preserved (curation / ordering);
     *       every entry is validated to have a {@code service.json}, with a clear WARN when it does not;</li>
     *   <li>every {@code KDPS/services/<KEY>/service.json} actually present on the classpath — whether the
     *       view runs from the expanded work dir ({@code file:}) or straight from the jar
     *       ({@code jar:}) — is auto-discovered and appended, so a custom service is first-class
     *       without touching the manifest.</li>
     * </ol>
     * Service directory names must be UPPERCASE (getServiceDefinition upper-cases the lookup path).
     */
    List<String> discoverServiceKeys() {
        if (discoveredServices != null && !discoveredServices.isEmpty()) {
            return discoveredServices;
        }
        List<String> keys = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. Curated manifest (ordering) — validated, never fatal.
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CATALOG_PATH)) {
            if (is == null) {
                LOG.warn("KDPS catalog not found in classpath ({}) — relying on classpath discovery only.", CATALOG_PATH);
            } else {
                for (String k : mapper.readValue(is, String[].class)) {
                    if (k == null || k.isBlank()) continue;
                    String key = k.trim();
                    if (!seen.add(key.toUpperCase(Locale.ROOT))) continue;
                    if (getClass().getClassLoader().getResource(BASE_PATH + "/" + key.toUpperCase(Locale.ROOT) + "/service.json") == null) {
                        LOG.warn("catalog.json lists '{}' but {}/{}/service.json is missing — it will be skipped.",
                                key, BASE_PATH, key.toUpperCase(Locale.ROOT));
                    }
                    keys.add(key);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed reading {}: {}", CATALOG_PATH, e.toString());
        }

        // 2. Drop-in discovery: any <KEY>/service.json on the classpath not already listed.
        List<String> extra = new ArrayList<>();
        for (String child : listResourceChildren(BASE_PATH)) {
            if (child.isBlank() || child.contains(".")) continue; // skip files (catalog.json) — dirs only
            if (seen.contains(child.toUpperCase(Locale.ROOT))) continue;
            if (getClass().getClassLoader().getResource(BASE_PATH + "/" + child + "/service.json") == null) continue;
            if (!child.equals(child.toUpperCase(Locale.ROOT))) {
                LOG.warn("Ignoring service directory '{}': KDPS service directories must be UPPERCASE.", child);
                continue;
            }
            seen.add(child);
            extra.add(child);
        }
        Collections.sort(extra);
        if (!extra.isEmpty()) {
            LOG.info("Auto-discovered {} service(s) not listed in catalog.json: {}", extra.size(), extra);
        }
        keys.addAll(extra);

        LOG.info("Discovered {} KDPS services: {}", keys.size(), keys);
        discoveredServices = keys;
        return keys;
    }

    /**
     * List the immediate children of a classpath "directory" resource. Works for both the expanded
     * view work dir ({@code file:} URLs — the normal Ambari runtime) and a plain jar ({@code jar:}
     * URLs), and unions every matching classpath root (e.g. main + test resources). Best-effort:
     * returns an empty list when the resource cannot be enumerated.
     */
    List<String> listResourceChildren(String dir) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String prefix = dir.endsWith("/") ? dir : dir + "/";
        try {
            Enumeration<URL> urls = getClass().getClassLoader().getResources(dir);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                String proto = url.getProtocol();
                if ("file".equals(proto)) {
                    Path p = Paths.get(url.toURI());
                    if (Files.isDirectory(p)) {
                        try (Stream<Path> s = Files.list(p)) {
                            s.forEach(c -> out.add(c.getFileName().toString()));
                        }
                    }
                } else if ("jar".equals(proto)) {
                    JarURLConnection conn = (JarURLConnection) url.openConnection();
                    // Open our OWN handle (never close the classloader's cached JarFile).
                    try (JarFile jar = new JarFile(new File(conn.getJarFileURL().toURI()))) {
                        Enumeration<JarEntry> en = jar.entries();
                        while (en.hasMoreElements()) {
                            String n = en.nextElement().getName();
                            if (n.startsWith(prefix) && n.length() > prefix.length()) {
                                String rest = n.substring(prefix.length());
                                int slash = rest.indexOf('/');
                                String child = slash < 0 ? rest : rest.substring(0, slash);
                                if (!child.isEmpty()) out.add(child);
                            }
                        }
                    }
                } else {
                    LOG.debug("Unsupported classpath resource protocol '{}' for {}", proto, url);
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not enumerate classpath resource dir {}: {}", dir, e.toString());
        }
        return new ArrayList<>(out);
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
     * Reads all configs: KDPS/services/{serviceName}/configurations/*.json. The directory is
     * ENUMERATED on the classpath (file: or jar:), so any *.json works — previously only the
     * hardcoded {@code <service>-env.json} / {@code <service>-files.json} names were loaded, which
     * silently ignored a custom service's configuration files. Those legacy names remain the
     * fallback when the directory cannot be enumerated.
     * @param serviceName logical service key (e.g. SUPERSET)
     * @return list of StackConfig objects for this service
     */
    public List<StackConfig> getServiceConfigurations(String serviceName) {
        List<StackConfig> cached = configCache.get(serviceName.toUpperCase(Locale.ROOT));
        if (cached != null) return cached;
        List<StackConfig> configs = new ArrayList<>();
        String configPath = BASE_PATH + "/" + serviceName.toUpperCase() + "/configurations";

        List<String> files = listResourceChildren(configPath).stream()
                .filter(f -> f.endsWith(".json"))
                .sorted()
                .collect(Collectors.toList());
        if (files.isEmpty()) {
            // Enumeration unavailable (or nothing found): legacy hardcoded names.
            files = List.of(
                    serviceName.toLowerCase() + "-env.json",   // e.g. superset-env.json
                    serviceName.toLowerCase() + "-files.json"  // e.g. superset-files.json
            );
        }
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
