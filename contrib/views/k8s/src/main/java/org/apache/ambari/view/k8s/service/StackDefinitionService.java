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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The service definitions KDPS offers: those built into the view ({@code KDPS/services} on its classpath) and the
 * add-ons installed on the Ambari server, one folder per service under the add-on directory
 * ({@value #DEFAULT_ADDONS_DIR} unless the view property {@value #ADDONS_DIR_PROPERTY} or the environment variable
 * {@value #ADDONS_DIR_ENV} names another), with the same layout as a built-in service: {@code <KEY>/service.json},
 * {@code <KEY>/configurations/*.json} and their {@code templates/}, and optionally {@code icon.svg} or {@code icon.png}.
 *
 * <p>An add-on is offered only when its folder name is upper case, no built-in service has that name, its files are
 * owned by root or by the account running Ambari and writable by no one else, and its {@code requiresKdps} is not newer
 * than this view. Otherwise it is left out, and {@link #addonStatus()} says why. Add-ons are read again at every use,
 * so installing, upgrading or removing one needs no restart; the built-in definitions are cached.
 */
public class StackDefinitionService {
    private static final Logger LOG = LoggerFactory.getLogger(StackDefinitionService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String BASE_PATH = "KDPS/services";

    /** View instance property naming the add-on directory. */
    public static final String ADDONS_DIR_PROPERTY = "kdps.addons.dir";
    /** Environment variable naming the add-on directory (the container image, where no view property is set). */
    public static final String ADDONS_DIR_ENV = "KDPS_ADDONS_DIR";
    /** Where add-on packages install their service folders by default. */
    public static final String DEFAULT_ADDONS_DIR = "/var/lib/ambari-server/resources/kdps-addons";
    private static final Pattern ADDON_KEY = Pattern.compile("^[A-Z][A-Z0-9_-]*$");
    private static final List<String> ICON_FILES = List.of("icon.svg", "icon.png");

    private final Path addonsRoot;
    private final String viewVersion;

    public StackDefinitionService() {
        this((org.apache.ambari.view.ViewContext) null);
    }

    public StackDefinitionService(org.apache.ambari.view.ViewContext ctx) {
        this(resolveAddonsRoot(ctx), viewVersionOf(ctx));
    }

    /**
     * @param addonsRoot  the add-on directory, or null for none
     * @param viewVersion the running view's version, compared with an add-on's {@code requiresKdps}; null skips the check
     */
    StackDefinitionService(Path addonsRoot, String viewVersion) {
        this.addonsRoot = addonsRoot;
        this.viewVersion = viewVersion;
    }

    // simple caches (built-in definitions only: add-ons are read again at every use)
    private final Map<String, StackServiceDef> serviceCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<StackConfig>> configCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile List<String> discoveredServices;

    private static final String CATALOG_PATH = BASE_PATH + "/catalog.json";

    /**
     * Lists every available service definition: the curated {@code KDPS/services/catalog.json}
     * manifest (which fixes the display order) UNION any drop-in {@code KDPS/services/<KEY>/service.json}
     * found on the classpath but not listed in the manifest, then the add-ons KDPS accepts.
     * Keys are the service directory names.
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
     * The built-in keys (see {@link #discoverBuiltInServiceKeys()}), then the accepted add-ons in name order.
     */
    List<String> discoverServiceKeys() {
        List<String> keys = new ArrayList<>(discoverBuiltInServiceKeys());
        keys.addAll(scanAddons().accepted().keySet());
        return keys;
    }

    /**
     * Resolve the set of built-in service keys. Historically this read ONLY the hardcoded
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
    List<String> discoverBuiltInServiceKeys() {
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

    private boolean isBuiltIn(String serviceName) {
        return getClass().getClassLoader().getResource(
                BASE_PATH + "/" + serviceName.toUpperCase(Locale.ROOT) + "/service.json") != null;
    }

    /**
     * Whether KDPS offers a definition for the service, as opposed to one that cannot be read.
     * @param serviceName logical service key (e.g. SUPERSET)
     * @return true when the view ships KDPS/services/{serviceName}/service.json or an accepted add-on provides it
     */
    public boolean hasServiceDefinition(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) return false;
        return isBuiltIn(serviceName) || addonDir(serviceName) != null;
    }

    /**
     * Reads definition: KDPS/services/{serviceName}/service.json, or the add-on's service.json.
     * @param serviceName logical service key (e.g. SUPERSET)
     * @return parsed StackServiceDef
     */
    public StackServiceDef getServiceDefinition(String serviceName) {
        StackServiceDef cached = serviceCache.get(serviceName.toUpperCase(Locale.ROOT));
        if (cached != null) return cached;
        String path = BASE_PATH + "/" + serviceName.toUpperCase() + "/service.json";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                Path dir = addonDir(serviceName);
                if (dir == null) throw new IllegalArgumentException("Service definition not found: " + path);
                return readAddonDefinition(dir);
            }
            StackServiceDef def = mapper.readValue(is, StackServiceDef.class);
            serviceCache.put(serviceName.toUpperCase(Locale.ROOT), def);
            return def;
        } catch (Exception e) {
            LOG.error("Failed to load service definition for " + serviceName, e);
            throw new RuntimeException("Could not load service definition", e);
        }
    }

    /**
     * Reads all configs: KDPS/services/{serviceName}/configurations/*.json, or the add-on's. The directory is
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
        if (!isBuiltIn(serviceName)) {
            Path dir = addonDir(serviceName);
            if (dir != null) return readAddonConfigurations(dir);
        }
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

    // ------------------------------------------------------------------------------------------------------------
    // Add-ons

    /** What the add-on directory holds: the accepted add-ons by key, and the folders left out with the reason. */
    public record AddonScan(Path root, Map<String, Path> accepted, Map<String, String> refused) {
    }

    /**
     * The add-on directory and what KDPS made of each folder in it, for {@code GET /services/addons}: the accepted
     * add-ons with their chart and version, and the folders left out with the reason.
     */
    public Map<String, Object> addonStatus() {
        AddonScan scan = scanAddons();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("directory", scan.root() == null ? null : scan.root().toString());
        out.put("viewVersion", viewVersion);
        List<Map<String, Object>> accepted = new ArrayList<>();
        for (Map.Entry<String, Path> e : scan.accepted().entrySet()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("key", e.getKey());
            try {
                StackServiceDef def = readAddonDefinition(e.getValue());
                a.put("label", def.label);
                a.put("chart", def.chart);
                a.put("version", def.version);
                a.put("requiresKdps", def.requiresKdps);
            } catch (Exception ex) {
                a.put("error", ex.getMessage());
            }
            accepted.add(a);
        }
        out.put("accepted", accepted);
        List<Map<String, String>> refused = new ArrayList<>();
        scan.refused().forEach((k, why) -> refused.add(Map.of("key", k, "reason", why)));
        out.put("refused", refused);
        return out;
    }

    /**
     * The add-on's icon (icon.svg, else icon.png), or null when the service is not an accepted add-on or ships none.
     */
    public Path addonIcon(String serviceName) {
        Path dir = addonDir(serviceName);
        if (dir == null) return null;
        for (String name : ICON_FILES) {
            Path icon = dir.resolve(name);
            if (Files.isRegularFile(icon, LinkOption.NOFOLLOW_LINKS) && trustProblem(icon) == null) return icon;
        }
        return null;
    }

    /** Reads the add-on folder again and sorts each sub-folder into accepted or refused, with the reason. */
    AddonScan scanAddons() {
        Map<String, Path> accepted = new TreeMap<>();
        Map<String, String> refused = new TreeMap<>();
        if (addonsRoot == null || !Files.isDirectory(addonsRoot, LinkOption.NOFOLLOW_LINKS)) {
            return new AddonScan(addonsRoot, accepted, refused);
        }
        String rootProblem = trustProblem(addonsRoot);
        if (rootProblem != null) {
            LOG.warn("KDPS add-on directory {} is not used: {}", addonsRoot, rootProblem);
            refused.put("*", "the add-on directory " + rootProblem);
            return new AddonScan(addonsRoot, accepted, refused);
        }
        List<Path> children;
        try (Stream<Path> s = Files.list(addonsRoot)) {
            children = s.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            LOG.warn("Cannot list the KDPS add-on directory {}: {}", addonsRoot, e.toString());
            return new AddonScan(addonsRoot, accepted, refused);
        }
        for (Path dir : children) {
            String key = dir.getFileName().toString();
            String problem = addonProblem(key, dir);
            if (problem == null) {
                accepted.put(key, dir);
            } else {
                refused.put(key, problem);
                LOG.warn("KDPS add-on {} is not offered: {}", dir, problem);
            }
        }
        return new AddonScan(addonsRoot, accepted, refused);
    }

    /** Why the add-on folder cannot be offered, or null when it can. */
    private String addonProblem(String key, Path dir) {
        if (!ADDON_KEY.matcher(key).matches()) {
            return "its folder name must be upper case (letters, digits, '_' and '-'), like the service key";
        }
        if (isBuiltIn(key)) {
            return "a service built into KDPS has this name; an add-on cannot replace it";
        }
        String dirProblem = trustProblem(dir);
        if (dirProblem != null) return "its folder " + dirProblem;
        Path serviceJson = dir.resolve("service.json");
        if (!Files.isRegularFile(serviceJson, LinkOption.NOFOLLOW_LINKS)) {
            return "it has no service.json";
        }
        String fileProblem = trustProblem(serviceJson);
        if (fileProblem != null) return "its service.json " + fileProblem;
        StackServiceDef def;
        try {
            def = mapper.readValue(serviceJson.toFile(), StackServiceDef.class);
        } catch (Exception e) {
            return "its service.json cannot be read: " + e.getMessage();
        }
        if (def.requiresKdps != null && !def.requiresKdps.isBlank() && viewVersion != null && !viewVersion.isBlank()
                && StackServiceDef.compareVersions(viewVersion.trim(), def.requiresKdps.trim()) < 0) {
            return "it needs KDPS view " + def.requiresKdps.trim() + " or later, and this one is " + viewVersion;
        }
        return null;
    }

    /** The accepted add-on folder of the service, or null. */
    private Path addonDir(String serviceName) {
        if (serviceName == null || serviceName.isBlank() || addonsRoot == null) return null;
        String key = serviceName.toUpperCase(Locale.ROOT);
        if (isBuiltIn(key)) return null;
        Path dir = addonsRoot.resolve(key);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return null;
        String rootProblem = trustProblem(addonsRoot);
        if (rootProblem != null) return null;
        return addonProblem(key, dir) == null ? dir : null;
    }

    private StackServiceDef readAddonDefinition(Path dir) throws IOException {
        StackServiceDef def = mapper.readValue(readAddonFile(dir, dir.resolve("service.json")), StackServiceDef.class);
        String key = dir.getFileName().toString();
        if (def.name == null || def.name.isBlank()) def.name = key;
        Map<String, Object> addon = new LinkedHashMap<>();
        addon.put("source", "addon");
        addon.put("icon", addonIcon(key) != null);
        def.addon = addon;
        return def;
    }

    private List<StackConfig> readAddonConfigurations(Path dir) {
        List<StackConfig> configs = new ArrayList<>();
        Path configDir = dir.resolve("configurations");
        if (!Files.isDirectory(configDir, LinkOption.NOFOLLOW_LINKS)) return configs;
        List<Path> files;
        try (Stream<Path> s = Files.list(configDir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".json")
                            && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .sorted().collect(Collectors.toList());
        } catch (IOException e) {
            LOG.warn("Cannot list the configurations of add-on {}: {}", dir, e.toString());
            return configs;
        }
        for (Path file : files) {
            try {
                StackConfig config = mapper.readValue(readAddonFile(dir, file), StackConfig.class);
                if (config.properties != null) {
                    for (StackProperty prop : config.properties) {
                        if (prop.valueSourceFile != null) {
                            prop.value = readAddonFile(dir, configDir.resolve("templates").resolve(prop.valueSourceFile));
                        }
                    }
                }
                configs.add(config);
            } catch (Exception e) {
                LOG.warn("Error loading add-on configuration {}: {}", file, e.toString());
            }
        }
        return configs;
    }

    /**
     * Reads a file of an add-on, refusing one outside the add-on's folder (a {@code ../} template name, a link) or
     * one others may write.
     */
    private String readAddonFile(Path dir, Path file) throws IOException {
        Path real = file.toRealPath();
        if (!real.startsWith(dir.toRealPath())) {
            throw new IOException(file + " is outside the add-on folder " + dir);
        }
        String problem = trustProblem(real);
        if (problem != null) throw new IOException(file + " " + problem);
        return Files.readString(real, StandardCharsets.UTF_8);
    }

    /**
     * Why KDPS does not trust the file or folder, or null when it does: it must belong to root or to the account
     * running Ambari, and only its owner may write it. A definition decides what KDPS creates in the cluster and in
     * the KDC, so a file anyone could change is not read.
     */
    static String trustProblem(Path path) {
        PosixFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException e) {
            return "is on a file system without owners and permissions, which KDPS cannot check";
        } catch (IOException e) {
            return "cannot be read: " + e.getMessage();
        }
        if (attrs.isSymbolicLink()) return "is a symbolic link";
        String owner = attrs.owner().getName();
        String self = System.getProperty("user.name");
        if (!"root".equals(owner) && !owner.equals(self)) {
            return "belongs to '" + owner + "', not to root or to the account running Ambari ('" + self + "')";
        }
        Set<PosixFilePermission> perms = attrs.permissions();
        if (perms.contains(PosixFilePermission.GROUP_WRITE) || perms.contains(PosixFilePermission.OTHERS_WRITE)) {
            return "can be written by other accounts than its owner";
        }
        return null;
    }

    private static Path resolveAddonsRoot(org.apache.ambari.view.ViewContext ctx) {
        String dir = null;
        try {
            if (ctx != null && ctx.getProperties() != null) dir = ctx.getProperties().get(ADDONS_DIR_PROPERTY);
        } catch (Exception e) {
            LOG.debug("Cannot read the view property {}: {}", ADDONS_DIR_PROPERTY, e.toString());
        }
        if (dir == null || dir.isBlank()) dir = System.getenv(ADDONS_DIR_ENV);
        if (dir == null || dir.isBlank()) dir = DEFAULT_ADDONS_DIR;
        return Paths.get(dir.trim());
    }

    private static String viewVersionOf(org.apache.ambari.view.ViewContext ctx) {
        try {
            return ctx == null || ctx.getViewDefinition() == null ? null : ctx.getViewDefinition().getVersion();
        } catch (Exception e) {
            return null;
        }
    }
}
