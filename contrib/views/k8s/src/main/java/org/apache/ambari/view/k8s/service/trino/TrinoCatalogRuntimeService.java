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
package org.apache.ambari.view.k8s.service.trino;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.ConfigResolutionService;
import org.apache.ambari.view.k8s.service.HelmService;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.service.TrinoCatalogService;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live catalog management on a deployed Trino release, as the operator (see
 * {@link TrinoStatementClient}). Model: the running coordinator's catalog store is the live source;
 * the release's helm values ({@code catalogs.<name>}) are the durable truth re-seeded on restart.
 * "Apply live, then persist": a created/dropped catalog is written back to the values (a helm
 * upgrade that, with {@code dynamicCatalogs.enabled}, no longer rolls the pods). Catalogs that exist
 * in Trino but not in the values are reported as <em>unmanaged</em> (they vanish at the next
 * restart) and can be adopted on demand.
 */
public class TrinoCatalogRuntimeService {
    private static final Logger LOG = LoggerFactory.getLogger(TrinoCatalogRuntimeService.class);
    static final Set<String> KDPS_MANAGED = Set.of("hive", "iceberg");
    static final Set<String> BUILTIN = Set.of("system");
    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]*");

    /** One row of the catalog list. */
    public static final class CatalogView {
        public String name;
        /** managed (KDPS-built hive/iceberg) | reusable | inline | unmanaged | builtin */
        public String source;
        public String connector;
        public boolean live;        // present in SHOW CATALOGS
        public boolean persisted;   // present in the release values
        public String reusableId;   // when source == reusable
        public String properties;   // persisted properties text (never for unmanaged)
    }

    public static final class Target {
        public String namespace, release, podName, container, serviceUser, servicePassword, rangerService;
        public int httpsPort;
        public boolean dynamicCatalogs;
        public String storeDir;
        /** name -> properties text as RENDERED in the catalog ConfigMap (chart defaults + values + KDPS-managed). */
        public Map<String, String> renderedCatalogs = new LinkedHashMap<>();
        public Map<String, Object> values;
        public K8sReleaseEntity metadata;
    }

    private final ViewContext ctx;
    private final KubernetesService kubernetesService;

    public TrinoCatalogRuntimeService(ViewContext ctx) {
        this.ctx = ctx;
        this.kubernetesService = KubernetesService.get(ctx);
    }

    // ---------------------------------------------------------------- resolution

    /** Locate the coordinator, the KDPS service user credentials and the release values. */
    public Target resolve(String namespace, String release) {
        Target t = new Target();
        t.namespace = namespace; t.release = release;
        t.values = kubernetesService.getHelmReleaseValues(namespace, release);
        if (t.values == null) t.values = Collections.emptyMap();
        t.metadata = new ReleaseMetadataService(ctx).find(namespace, release);
        Object https = ConfigResolutionService.getByDottedPath(t.values, "server.config.https.enabled");
        if (https != null && !"true".equalsIgnoreCase(String.valueOf(https))) {
            throw new IllegalStateException("Catalog management needs Trino HTTPS (server.config.https.enabled) — "
                    + "Trino refuses password authentication over plain HTTP.");
        }
        Object port = ConfigResolutionService.getByDottedPath(t.values, "server.config.https.port");
        t.httpsPort = port == null ? 8443 : Integer.parseInt(String.valueOf(port));
        Object dyn = ConfigResolutionService.getByDottedPath(t.values, "dynamicCatalogs.enabled");
        t.dynamicCatalogs = dyn != null && "true".equalsIgnoreCase(String.valueOf(dyn));
        Object sd = ConfigResolutionService.getByDottedPath(t.values, "dynamicCatalogs.storeDir");
        t.storeDir = sd == null || String.valueOf(sd).isBlank() ? "/opt/trino/catalog-store" : String.valueOf(sd);
        Object rs = ConfigResolutionService.getByDottedPath(t.values, "ranger.serviceName");
        t.rangerService = rs == null ? null : String.valueOf(rs);
        Object su = ConfigResolutionService.getByDottedPath(t.values, "kdps.serviceUser.enabled");
        if (su == null || !"true".equalsIgnoreCase(String.valueOf(su))) {
            throw new IllegalStateException("This release was deployed without the KDPS service user "
                    + "(kdps.serviceUser.enabled). Run Upgrade/Config with 'Let KDPS manage catalogs' on, "
                    + "chart >= 1.43.13.");
        }
        KubernetesClient client = kubernetesService.getClient();
        // coordinator pod (Running, not terminating)
        List<Pod> pods = client.pods().inNamespace(namespace)
                .withLabel("app.kubernetes.io/instance", release)
                .withLabel("app.kubernetes.io/component", "coordinator").list().getItems();
        Pod coord = null;
        for (Pod p : pods) {
            if (p.getMetadata().getDeletionTimestamp() == null && p.getStatus() != null
                    && "Running".equals(p.getStatus().getPhase())) { coord = p; break; }
        }
        if (coord == null) throw new IllegalStateException("No running Trino coordinator pod found for " + namespace + "/" + release);
        t.podName = coord.getMetadata().getName();
        // The rendered catalog ConfigMap is the durable truth (chart defaults + values + KDPS-managed catalogs).
        for (io.fabric8.kubernetes.api.model.ConfigMap cm : client.configMaps().inNamespace(namespace)
                .withLabel("app.kubernetes.io/instance", release).list().getItems()) {
            if (cm.getMetadata().getName().endsWith("-catalog-coordinator") && cm.getData() != null) {
                for (Map.Entry<String, String> e : cm.getData().entrySet()) {
                    if (e.getKey().endsWith(".properties")) t.renderedCatalogs.put(e.getKey().substring(0, e.getKey().length() - ".properties".length()), e.getValue());
                }
            }
        }
        List<Container> cs = coord.getSpec().getContainers();
        t.container = cs.isEmpty() ? null : cs.get(0).getName();
        // service user secret
        Object existing = ConfigResolutionService.getByDottedPath(t.values, "kdps.serviceUser.existingSecret");
        Secret sec = null;
        if (existing != null && !String.valueOf(existing).isBlank()) {
            sec = client.secrets().inNamespace(namespace).withName(String.valueOf(existing)).get();
        } else {
            for (Secret s : client.secrets().inNamespace(namespace).withLabel("app.kubernetes.io/instance", release).list().getItems()) {
                if (s.getMetadata().getName().endsWith("-kdps-service-user")) { sec = s; break; }
            }
        }
        if (sec == null || sec.getData() == null || !sec.getData().containsKey("password")) {
            throw new IllegalStateException("The KDPS service user Secret (<release>-kdps-service-user) was not found in "
                    + namespace + " — is the release on chart >= 1.43.13 with kdps.serviceUser.enabled?");
        }
        t.servicePassword = new String(Base64.getDecoder().decode(sec.getData().get("password")), StandardCharsets.UTF_8);
        t.serviceUser = sec.getData().containsKey("username")
                ? new String(Base64.getDecoder().decode(sec.getData().get("username")), StandardCharsets.UTF_8) : "kdps";
        return t;
    }

    private TrinoStatementClient clientFor(Target t) {
        return new TrinoStatementClient(kubernetesService.getClient(), t.namespace, t.podName, t.container,
                t.httpsPort, t.serviceUser, t.servicePassword);
    }

    // ---------------------------------------------------------------- operations

    public List<CatalogView> list(String namespace, String release, String operator) {
        Target t = resolve(namespace, release);
        TrinoStatementClient.Result r = clientFor(t).execute("SHOW CATALOGS", operator);
        if (!r.ok()) throw accessOrState(r, t, "list catalogs");
        List<String> live = new ArrayList<>();
        for (List<Object> row : r.rows) if (!row.isEmpty() && row.get(0) != null) live.add(String.valueOf(row.get(0)));
        return merge(live, t.renderedCatalogs.isEmpty() ? valuesCatalogs(t.values) : t.renderedCatalogs, valuesCatalogs(t.values), refsOf(t.metadata));
    }

    /**
     * Pure merge (unit-tested) of SHOW CATALOGS with what is persisted. {@code rendered} = the catalog
     * ConfigMap (chart defaults + values + KDPS-managed), {@code inValues} = the release's own
     * {@code catalogs} values, {@code refs} = reusable-catalog references.
     * Sources: builtin, managed (KDPS hive/iceberg), reusable, inline (in values), default (rendered by
     * the chart but not in the values), unmanaged (live only — lost at restart).
     */
    static List<CatalogView> merge(List<String> live, Map<String, String> rendered, Map<String, String> inValues,
                                   Map<String, Map<String, String>> refs) {
        Set<String> names = new LinkedHashSet<>(live);
        names.addAll(rendered.keySet());
        names.addAll(inValues.keySet());
        List<CatalogView> out = new ArrayList<>();
        for (String n : names) {
            CatalogView v = new CatalogView();
            v.name = n; v.live = live.contains(n); v.persisted = rendered.containsKey(n) || inValues.containsKey(n);
            String text = inValues.containsKey(n) ? inValues.get(n) : rendered.get(n);
            if (BUILTIN.contains(n)) v.source = "builtin";
            else if (KDPS_MANAGED.contains(n) && v.persisted) v.source = "managed";
            else if (refs.containsKey(n)) { v.source = "reusable"; v.reusableId = refs.get(n).get("id"); }
            else if (inValues.containsKey(n)) v.source = "inline";
            else if (v.persisted) v.source = "default";
            else v.source = "unmanaged";
            if (v.persisted) { v.properties = text; v.connector = connectorOf(text); }
            out.add(v);
        }
        return out;
    }

    public Map<String, Object> create(String namespace, String release, String name, String propertiesText, boolean persist, String operator) {
        Target t = resolve(namespace, release);
        String connector = TrinoCatalogService.validateCatalog(name, propertiesText);
        Map<String, String> props = parseProperties(propertiesText);
        if (!t.dynamicCatalogs) {
            throw new IllegalStateException("Runtime catalog creation is off for this release (dynamicCatalogs.enabled=false)."
                    + " Turn it on in Upgrade/Config, or add the catalog there to apply it at the next restart.");
        }
        TrinoStatementClient.Result r = clientFor(t).execute(buildCreateCatalog(name, connector, props), operator);
        if (!r.ok()) throw accessOrState(r, t, "create catalog '" + name + "'");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name); out.put("connector", connector); out.put("live", true);
        if (persist) { persistCatalog(t, name, propertiesText.trim()); out.put("persisted", true); }
        return out;
    }

    public Map<String, Object> drop(String namespace, String release, String name, boolean forget, String operator) {
        Target t = resolve(namespace, release);
        if (!IDENT.matcher(name).matches()) throw new IllegalArgumentException("'" + name + "' is not a catalog name.");
        if (KDPS_MANAGED.contains(name) && valuesCatalogs(t.values).containsKey(name)) {
            throw new IllegalArgumentException("'" + name + "' is built by KDPS from the platform context; turn its toggle off in Upgrade/Config instead.");
        }
        TrinoStatementClient.Result r = clientFor(t).execute("DROP CATALOG " + name, operator);
        if (!r.ok() && !(r.errorName != null && r.errorName.contains("NOT_FOUND"))) throw accessOrState(r, t, "drop catalog '" + name + "'");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name); out.put("live", false);
        if (forget && valuesCatalogs(t.values).containsKey(name)) { persistCatalog(t, name, null); out.put("persisted", false); }
        return out;
    }

    public List<String> test(String namespace, String release, String name, String operator) {
        Target t = resolve(namespace, release);
        if (!IDENT.matcher(name).matches()) throw new IllegalArgumentException("'" + name + "' is not a catalog name.");
        TrinoStatementClient.Result r = clientFor(t).execute("SHOW SCHEMAS FROM " + name, operator);
        if (!r.ok()) throw accessOrState(r, t, "read schemas of '" + name + "'");
        List<String> out = new ArrayList<>();
        for (List<Object> row : r.rows) if (!row.isEmpty() && row.get(0) != null) out.add(String.valueOf(row.get(0)));
        return out;
    }

    /** Adopt an unmanaged (runtime-only) catalog into the release values via SHOW CREATE CATALOG. */
    public Map<String, Object> adopt(String namespace, String release, String name, String operator) {
        Target t = resolve(namespace, release);
        if (!IDENT.matcher(name).matches()) throw new IllegalArgumentException("'" + name + "' is not a catalog name.");
        // Trino 476 has no SHOW CREATE CATALOG; under dynamic management the file catalog store inside the
        // coordinator holds exactly what was created at runtime, so read it there (as a sanity gate the
        // operator must still be allowed to SHOW SCHEMAS on it — i.e. Ranger lets them use the catalog).
        TrinoStatementClient.Result r = clientFor(t).execute("SHOW SCHEMAS FROM " + name, operator);
        if (!r.ok()) throw accessOrState(r, t, "read catalog '" + name + "'");
        String raw = clientFor(t).readFile(t.storeDir + "/" + name + ".properties");
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("'" + name + "' has no file in the catalog store (" + t.storeDir + ") — only catalogs"
                    + " created at runtime under dynamic management can be adopted.");
        }
        String text = propertiesFromStoreFile(raw);
        TrinoCatalogService.validateCatalog(name, text);
        persistCatalog(t, name, text);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name); out.put("persisted", true); out.put("properties", text);
        return out;
    }

    // ---------------------------------------------------------------- helpers (pure, unit-tested)

    /** CREATE CATALOG name USING connector WITH ("k" = 'v', ...) — connector.name is the USING clause. */
    static String buildCreateCatalog(String name, String connector, Map<String, String> props) {
        StringBuilder sb = new StringBuilder("CREATE CATALOG ").append(name).append(" USING ").append(connector);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            if ("connector.name".equals(e.getKey())) continue;
            parts.add("\"" + e.getKey().replace("\"", "\"\"") + "\" = '" + e.getValue().replace("'", "''") + "'");
        }
        if (!parts.isEmpty()) sb.append(" WITH (").append(String.join(", ", parts)).append(")");
        return sb.toString();
    }

    /** key=value lines (comments/blank skipped) → ordered map; values keep everything after the first '='. */
    static Map<String, String> parseProperties(String text) {
        Map<String, String> m = new LinkedHashMap<>();
        if (text == null) return m;
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue;
            int i = t.indexOf('=');
            m.put(t.substring(0, i).trim(), t.substring(i + 1).trim());
        }
        return m;
    }

    /** Inverse of {@link #buildCreateCatalog}: SHOW CREATE CATALOG text → .properties text (connector.name first). */
    static String propertiesFromShowCreate(String ddl) {
        Matcher u = Pattern.compile("USING\\s+([A-Za-z0-9_\\-]+)").matcher(ddl);
        StringBuilder sb = new StringBuilder();
        if (u.find()) sb.append("connector.name=").append(u.group(1)).append('\n');
        Matcher p = Pattern.compile("\"((?:[^\"]|\"\")+)\"\\s*=\\s*'((?:[^']|'')*)'").matcher(ddl);
        while (p.find()) {
            String k = p.group(1).replace("\"\"", "\""); String v = p.group(2).replace("''", "'");
            if (!"connector.name".equals(k)) sb.append(k).append('=').append(v).append('\n');
        }
        return sb.toString().trim();
    }

    /** The store file is a java.util.Properties dump: drop the "#date" comment line, keep key=value lines. */
    static String propertiesFromStoreFile(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.replace("\r\n", "\n").split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
            int i = t.indexOf('='); if (i < 0) i = t.indexOf(':');
            if (i < 0) continue;
            String k = t.substring(0, i).trim().replace("\\", ""); String v = t.substring(i + 1).trim().replace("\\:", ":").replace("\\=", "=");
            if ("connector.name".equals(k)) sb.insert(0, "connector.name=" + v + "\n"); else sb.append(k).append('=').append(v).append('\n');
        }
        return sb.toString().trim();
    }

    static String connectorOf(String propertiesText) {
        Matcher m = Pattern.compile("(?m)^\\s*connector\\.name\\s*=\\s*(\\S+)\\s*$").matcher(propertiesText == null ? "" : propertiesText);
        return m.find() ? m.group(1) : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> valuesCatalogs(Map<String, Object> values) {
        Map<String, String> out = new LinkedHashMap<>();
        Object c = values == null ? null : values.get("catalogs");
        if (c instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) c).entrySet()) out.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
        return out;
    }

    private static Map<String, Map<String, String>> refsOf(K8sReleaseEntity meta) {
        return meta == null ? new LinkedHashMap<>() : TrinoCatalogService.parseRefs(meta.getCatalogRefsJson());
    }

    private RuntimeException accessOrState(TrinoStatementClient.Result r, Target t, String action) {
        if ("PERMISSION_DENIED".equalsIgnoreCase(r.errorName) || (r.error != null && r.error.contains("Access Denied"))) {
            return new SecurityException("Ranger denied you the right to " + action + " on this Trino"
                    + (t.rangerService != null ? " (Ranger service '" + t.rangerService + "')" : "") + ": " + r.error
                    + ". Ask for the matching catalog permission (create/drop/alter) in Ranger.");
        }
        if (r.error != null && r.error.contains("not supported by the static catalog store")) {
            return new IllegalStateException("This Trino runs with static catalogs — turn on 'Allow catalogs to be created at runtime' "
                    + "(dynamicCatalogs.enabled) in Upgrade/Config first.");
        }
        return new IllegalStateException("Trino refused to " + action + ": " + r.error);
    }

    /** Write the catalog into the release values (null text = remove) through a helm upgrade of the same chart/version. */
    @SuppressWarnings("unchecked")
    private void persistCatalog(Target t, String name, String text) {
        if (t.metadata == null || t.metadata.getChartRef() == null) {
            throw new IllegalStateException("Release metadata is missing (not deployed through KDPS?) — catalog applied live only, not persisted.");
        }
        Map<String, Object> values = new LinkedHashMap<>(t.values);
        Map<String, Object> catalogs = values.get("catalogs") instanceof Map ? new LinkedHashMap<>((Map<String, Object>) values.get("catalogs")) : new LinkedHashMap<>();
        if (text == null) catalogs.remove(name); else catalogs.put(name, text);
        values.put("catalogs", catalogs);
        HelmService helm = new HelmService(ctx);
        helm.deployOrUpgrade(t.metadata.getChartRef(), t.release, t.namespace, values, Collections.emptyMap(),
                kubernetesService.getConfigurationService().getKubeconfigContents(),
                t.metadata.getRepoId(), t.metadata.getVersion(), 600, true, false, false);
        LOG.info("Catalog '{}' {} in release {}/{} values", name, text == null ? "removed from" : "persisted into", t.namespace, t.release);
    }
}
