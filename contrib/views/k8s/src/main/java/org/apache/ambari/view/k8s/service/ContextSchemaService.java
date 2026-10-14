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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.ambari.view.k8s.model.ContextCapabilitySchema;
import org.apache.ambari.view.k8s.model.ContextCapabilitySchema.ContextFieldDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * Loads + merges the platform-context schema from per-capability fragments under
 * {@code KDPS/contexts/capabilities/*.json}. The merge is the union of every fragment found,
 * so a downstream company adds a capability simply by dropping a new fragment file into the
 * view — no code change.
 *
 * <p>Fragment discovery works whether the resources live on the filesystem (the extracted
 * view work directory, {@code file:} URL) or inside the view jar ({@code jar:} URL).
 */
public class ContextSchemaService {

    private static final Logger LOG = LoggerFactory.getLogger(ContextSchemaService.class);
    private static final String DIR = "KDPS/contexts/capabilities";
    private static final Gson GSON = new Gson();

    /**
     * Returns the merged capability schemas, ordered by their {@code order} then capability name, without
     * any field whose storage key another field already uses (see {@link #rejectDuplicateStorageKeys}).
     */
    public List<ContextCapabilitySchema> loadSchema() {
        List<ContextCapabilitySchema> out = new ArrayList<>();
        for (String fragment : listFragments()) {
            String path = DIR + "/" + fragment;
            try (InputStream is = cl().getResourceAsStream(path)) {
                if (is == null) {
                    continue;
                }
                ContextCapabilitySchema schema = GSON.fromJson(
                        new InputStreamReader(is, StandardCharsets.UTF_8), ContextCapabilitySchema.class);
                if (schema != null && schema.capability != null && !schema.capability.isBlank()) {
                    out.add(schema);
                }
            } catch (Exception e) {
                LOG.warn("ContextSchemaService: failed to load capability fragment '{}': {}", path, e.toString());
            }
        }
        out.sort(Comparator.<ContextCapabilitySchema>comparingInt(s -> s.order)
                .thenComparing(s -> s.capability == null ? "" : s.capability));
        rejectDuplicateStorageKeys(out);
        return out;
    }

    /**
     * Drops every field whose storage key ({@link ContextFieldDef#storageKey()}) an earlier field already
     * uses. A context keeps all its values in one flat config map, so two fields sharing a key would
     * silently overwrite each other on save; the first field in schema order keeps the key, and the
     * fragment author is told to give the other one a {@code configKey}.
     */
    static void rejectDuplicateStorageKeys(List<ContextCapabilitySchema> schema) {
        Map<String, String> owners = new HashMap<>();
        for (ContextCapabilitySchema cap : schema) {
            if (cap.fields == null) {
                continue;
            }
            Iterator<ContextFieldDef> it = cap.fields.iterator();
            while (it.hasNext()) {
                ContextFieldDef f = it.next();
                String key = f == null ? null : f.storageKey();
                if (key == null || key.isBlank()) {
                    continue;
                }
                String field = cap.capability + "." + f.name;
                String owner = owners.putIfAbsent(key, field);
                if (owner != null) {
                    LOG.error("ContextSchemaService: field '{}' is ignored: it would be stored under the config key '{}',"
                            + " which '{}' already uses. Give it a distinct \"configKey\" in its capability fragment.",
                            field, key, owner);
                    it.remove();
                }
            }
        }
    }

    /** Enumerate the {@code *.json} fragment filenames under {@link #DIR} (file: and jar: aware). */
    private Set<String> listFragments() {
        Set<String> names = new LinkedHashSet<>();
        try {
            Enumeration<URL> roots = cl().getResources(DIR);
            while (roots.hasMoreElements()) {
                URL url = roots.nextElement();
                String protocol = url.getProtocol();
                if ("file".equals(protocol)) {
                    java.io.File dir = new java.io.File(url.toURI());
                    java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
                    if (files != null) {
                        for (java.io.File f : files) {
                            names.add(f.getName());
                        }
                    }
                } else if ("jar".equals(protocol)) {
                    JarURLConnection conn = (JarURLConnection) url.openConnection();
                    try (JarFile jar = conn.getJarFile()) {
                        String prefix = DIR + "/";
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            String name = entries.nextElement().getName();
                            if (name.startsWith(prefix) && name.endsWith(".json")
                                    && name.indexOf('/', prefix.length()) < 0) {
                                names.add(name.substring(prefix.length()));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("ContextSchemaService: failed to enumerate capability fragments under {}: {}", DIR, e.toString());
        }
        if (names.isEmpty()) {
            LOG.warn("ContextSchemaService: no capability fragments found under {} (classpath)", DIR);
        }
        return names;
    }

    private static ClassLoader cl() {
        return ContextSchemaService.class.getClassLoader();
    }
}
