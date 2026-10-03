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
import org.apache.ambari.view.k8s.model.TrinoCatalogDTO;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseRepo;
import org.apache.ambari.view.k8s.store.TrinoCatalogEntity;
import org.apache.ambari.view.k8s.store.TrinoCatalogRepo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reusable Trino catalogs: CRUD + the validation rules shared with the wizard's free-text
 * "Additional Trino catalogs" block, so a catalog saved here is exactly what the deploy would
 * have accepted inline. Deleting a catalog that a release still snapshots is refused (the release
 * keeps working — the refusal is about not losing the object the operator will want to re-apply).
 */
public class TrinoCatalogService {

    /** Hard DataStore limit: every String property is persisted as 3000 chars. */
    static final int MAX_PROPERTIES_LENGTH = 3000;
    private static final Pattern CONNECTOR_LINE = Pattern.compile("(?m)^\\s*connector\\.name\\s*=\\s*(\\S+)\\s*$");
    private static final Gson GSON = new Gson();

    private final ViewContext ctx;
    private final TrinoCatalogRepo repo;

    public TrinoCatalogService(ViewContext ctx) {
        this.ctx = ctx;
        this.repo = new TrinoCatalogRepo(ctx.getDataStore());
    }

    public List<TrinoCatalogDTO> list() {
        Map<String, List<String>> usage = usageByCatalogId();
        List<TrinoCatalogDTO> out = new ArrayList<>();
        Collection<TrinoCatalogEntity> all = repo.findAll();
        if (all != null) {
            for (TrinoCatalogEntity e : all) {
                TrinoCatalogDTO d = TrinoCatalogDTO.fromEntity(e);
                d.usedBy = usage.getOrDefault(e.getId(), new ArrayList<>());
                out.add(d);
            }
        }
        out.sort((a, b) -> String.valueOf(a.name).compareTo(String.valueOf(b.name)));
        return out;
    }

    public TrinoCatalogDTO get(String id) {
        TrinoCatalogEntity e = repo.findById(id);
        if (e == null) return null;
        TrinoCatalogDTO d = TrinoCatalogDTO.fromEntity(e);
        d.usedBy = usageByCatalogId().getOrDefault(id, new ArrayList<>());
        return d;
    }

    public TrinoCatalogEntity findEntity(String id) {
        return repo.findById(id);
    }

    public TrinoCatalogDTO save(TrinoCatalogDTO request) {
        Objects.requireNonNull(request, "catalog must not be null");
        String name = request.name == null ? "" : request.name.trim();
        String text = request.propertiesText == null ? "" : request.propertiesText.replace("\r\n", "\n").trim();
        String connector = validateCatalog(name, text);

        // Name must be unique across reusable catalogs (it is the key in the release's catalogs map).
        Collection<TrinoCatalogEntity> all = repo.findAll();
        if (all != null) {
            for (TrinoCatalogEntity other : all) {
                if (name.equals(other.getName()) && (request.id == null || !request.id.equals(other.getId()))) {
                    throw new IllegalArgumentException("A reusable catalog named '" + name + "' already exists."
                            + " Catalog names must be unique because they become the catalog's name in every"
                            + " Trino release that uses them.");
                }
            }
        }
        String now = Instant.now().toString();
        TrinoCatalogEntity entity = request.id == null || request.id.isBlank() ? null : repo.findById(request.id);
        if (entity == null) {
            entity = new TrinoCatalogEntity();
            entity.setId(request.id == null || request.id.isBlank() ? UUID.randomUUID().toString() : request.id);
            entity.setCreatedAt(now);
            entity.setCreatedBy(ctx.getUsername());
        }
        entity.setName(name);
        entity.setConnectorName(connector);
        entity.setDescription(request.description == null ? null : request.description.trim());
        entity.setPropertiesText(text);
        entity.setUpdatedAt(now);
        TrinoCatalogEntity saved = repo.upsert(entity);
        TrinoCatalogDTO d = TrinoCatalogDTO.fromEntity(saved);
        d.usedBy = usageByCatalogId().getOrDefault(saved.getId(), new ArrayList<>());
        return d;
    }

    public void delete(String id) {
        TrinoCatalogEntity e = repo.findById(id);
        if (e == null) return;
        List<String> users = usageByCatalogId().getOrDefault(id, new ArrayList<>());
        if (!users.isEmpty()) {
            throw new IllegalStateException("Catalog '" + e.getName() + "' is attached to "
                    + users.size() + " release(s): " + String.join(", ", users)
                    + ". Detach it from those releases (Upgrade/Config) before deleting it.");
        }
        repo.deleteById(id);
    }

    /**
     * Validate one catalog definition and return its {@code connector.name}. Same rules as the
     * wizard's inline block ({@code CommandService.parseCustomCatalogs}): usable catalog name, not a
     * KDPS-managed catalog, a single catalog (no [section] headers), has connector.name, fits the
     * DataStore column.
     *
     * @throws IllegalArgumentException with an operator-readable message
     */
    public static String validateCatalog(String name, String propertiesText) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Give the catalog a name: lower-case letters, digits and underscores,"
                    + " starting with a letter (e.g. postgres_prod).");
        }
        if (!CommandService.CATALOG_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("'" + name + "' is not a usable Trino catalog name. Use lower-case"
                    + " letters, digits and underscores, starting with a letter.");
        }
        if (CommandService.KDPS_MANAGED_CATALOGS.contains(name)) {
            throw new IllegalArgumentException("'" + name + "' is built by KDPS from the platform context and cannot"
                    + " be a reusable catalog. Pick another name.");
        }
        if (propertiesText == null || propertiesText.isBlank()) {
            throw new IllegalArgumentException("The catalog needs its .properties lines (at least connector.name).");
        }
        if (propertiesText.length() > MAX_PROPERTIES_LENGTH) {
            throw new IllegalArgumentException("The catalog properties are " + propertiesText.length()
                    + " characters; the maximum is " + MAX_PROPERTIES_LENGTH + ". Move credentials and long"
                    + " values into a Secret referenced with ${ENV:VAR}.");
        }
        for (String line : propertiesText.split("\n")) {
            String t = line.trim();
            if (t.startsWith("[") && t.endsWith("]")) {
                throw new IllegalArgumentException("A reusable catalog holds ONE catalog's properties — drop the '"
                        + t + "' header; the name is the field above.");
            }
            if (!t.isEmpty() && !t.startsWith("#") && !t.contains("=")) {
                throw new IllegalArgumentException("'" + t + "' is not a key=value line.");
            }
        }
        Matcher m = CONNECTOR_LINE.matcher(propertiesText);
        if (!m.find()) {
            throw new IllegalArgumentException("No connector.name line — Trino would refuse to start this catalog."
                    + " Add the connector it should use (e.g. connector.name=postgresql).");
        }
        return m.group(1);
    }

    /** Short content fingerprint stored with a release so a drifted catalog can be spotted later. */
    static String contentHash(String propertiesText) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((propertiesText == null ? "" : propertiesText).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** catalogId -> ["namespace/release", ...] from every release's persisted catalogRefsJson. */
    Map<String, List<String>> usageByCatalogId() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Collection<K8sReleaseEntity> releases = new K8sReleaseRepo(ctx).findAll();
        if (releases == null) return out;
        for (K8sReleaseEntity r : releases) {
            Map<String, Map<String, String>> refs = parseRefs(r.getCatalogRefsJson());
            for (Map<String, String> ref : refs.values()) {
                String id = ref.get("id");
                if (id == null) continue;
                out.computeIfAbsent(id, k -> new ArrayList<>()).add(r.getNamespace() + "/" + r.getReleaseName());
            }
        }
        return out;
    }

    /** Parse a release's {@code catalogRefsJson} ({@code name -> {id, hash}}); tolerant of null/garbage. */
    public static Map<String, Map<String, String>> parseRefs(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Map<String, String>> m = GSON.fromJson(json,
                    new TypeToken<Map<String, Map<String, String>>>() { }.getType());
            return m == null ? new LinkedHashMap<>() : m;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public static String toRefsJson(Map<String, Map<String, String>> refs) {
        return refs == null || refs.isEmpty() ? null : GSON.toJson(refs);
    }
}
