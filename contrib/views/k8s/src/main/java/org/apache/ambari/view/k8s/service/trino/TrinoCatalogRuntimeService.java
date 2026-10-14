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
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.DeploymentMode;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.CommandService;
import org.apache.ambari.view.k8s.service.ConfigResolutionService;
import org.apache.ambari.view.k8s.service.HelmService;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.service.TrinoCatalogService;
import org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend;
import org.apache.ambari.view.k8s.service.deployment.GitPullRequests;
import org.apache.ambari.view.k8s.service.deployment.ReleaseHeldInGitException;
import org.apache.ambari.view.k8s.service.helm.HelmOperationRegistry;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
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

    /**
     * Submits a deploy of a release through Git, as a redeploy from the Releases page does, and returns its operation
     * id: how a catalog saved to a release deployed with Flux GitOps gets into its values.
     */
    @FunctionalInterface
    public interface GitOpsRedeploy {
        String submit(HelmDeployRequest request);

        /**
         * The operation of a GitOps deploy of the release that has not finished, or {@code null} when none is
         * running (see {@link CommandService#runningGitOpsDeploy(String, String)}).
         */
        default String runningDeploy(String namespace, String release) {
            return null;
        }

        /**
         * Refuses, before anything is changed live, what the deploy's submission would refuse at once whatever the
         * catalog: no Ambari master key to keep the operation with, or a security profile the release records that
         * is gone (deleted or renamed).
         */
        default void checkSubmittable(K8sReleaseEntity release) {
        }
    }

    private final ViewContext ctx;
    private final KubernetesService kubernetesService;
    private final GitOpsRedeploy gitOpsRedeploy;

    public TrinoCatalogRuntimeService(ViewContext ctx) {
        this(ctx, null);
    }

    /**
     * @param gitOpsRedeploy commits the values of a release deployed with Flux GitOps to Git; without it, a catalog is
     *                       saved to such a release only through Upgrade/Config
     */
    public TrinoCatalogRuntimeService(ViewContext ctx, GitOpsRedeploy gitOpsRedeploy) {
        this.ctx = ctx;
        this.kubernetesService = KubernetesService.get(ctx);
        this.gitOpsRedeploy = gitOpsRedeploy;
    }

    TrinoCatalogRuntimeService(ViewContext ctx, KubernetesService kubernetesService, GitOpsRedeploy gitOpsRedeploy) {
        this.ctx = ctx;
        this.kubernetesService = kubernetesService;
        this.gitOpsRedeploy = gitOpsRedeploy;
    }

    // ---------------------------------------------------------------- resolution

    /** Locate the coordinator, the KDPS service user credentials and the release values. */
    public Target resolve(String namespace, String release) {
        Target t = new Target();
        t.namespace = namespace; t.release = release;
        t.values = kubernetesService.getHelmReleaseValues(namespace, release);
        if (t.values == null) t.values = Collections.emptyMap();
        t.metadata = new ReleaseMetadataService(ctx).find(namespace, release);
        String unavailable = serviceUserUnavailable(t.values, "Catalog management", namespace + "/" + release);
        if (unavailable != null) throw new IllegalStateException(unavailable);
        Object port = ConfigResolutionService.getByDottedPath(t.values, "server.config.https.port");
        t.httpsPort = port == null ? 8443 : Integer.parseInt(String.valueOf(port));
        Object dyn = ConfigResolutionService.getByDottedPath(t.values, "dynamicCatalogs.enabled");
        t.dynamicCatalogs = dyn != null && "true".equalsIgnoreCase(String.valueOf(dyn));
        Object sd = ConfigResolutionService.getByDottedPath(t.values, "dynamicCatalogs.storeDir");
        t.storeDir = sd == null || String.valueOf(sd).isBlank() ? "/opt/trino/catalog-store" : String.valueOf(sd);
        Object rs = ConfigResolutionService.getByDottedPath(t.values, "ranger.serviceName");
        t.rangerService = rs == null ? null : String.valueOf(rs);
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

    /**
     * Whether KDPS can sign in to a Trino release as its service user, which live catalog management and the
     * OpenMetadata Trino ingestion both do. See {@link #serviceUserUnavailable}.
     *
     * @param values the release's values; may be {@code null}
     * @return {@code true} when the release runs with 'Let KDPS manage catalogs' and 'Enable internal HTTPS'
     */
    public static boolean serviceUserAvailable(Map<String, Object> values) {
        return isOn(values, CommandService.TRINO_HTTPS_ENABLED) && isOn(values, CommandService.KDPS_SERVICE_USER_ENABLED);
    }

    /**
     * Why KDPS cannot sign in to a Trino release as its service user. The release needs 'Let KDPS manage catalogs',
     * which is a password login, and 'Enable internal HTTPS', since Trino only accepts passwords over HTTPS; the
     * wizard hides the first until the second is on, so internal HTTPS is checked first. A value the release does not
     * set is the chart's default, which is off for both.
     *
     * @param values  the release's values; may be {@code null}
     * @param feature what needs the login, which opens the sentence (e.g. "Catalog management")
     * @param release the release, as {@code namespace/name}
     * @return the reason, naming the options as the wizard shows them, or {@code null} when KDPS can sign in
     */
    public static String serviceUserUnavailable(Map<String, Object> values, String feature, String release) {
        if (!isOn(values, CommandService.TRINO_HTTPS_ENABLED)) {
            return feature + " needs 'Enable internal HTTPS' and 'Let KDPS manage catalogs' on Trino release " + release
                    + ", which runs with internal HTTPS off: the KDPS login on Trino is a password, which Trino only"
                    + " accepts over HTTPS. In Upgrade/Config of " + release + ", turn 'Enable internal HTTPS' on, then"
                    + " 'Let KDPS manage catalogs' (Custom catalogs).";
        }
        if (!isOn(values, CommandService.KDPS_SERVICE_USER_ENABLED)) {
            return feature + " needs 'Let KDPS manage catalogs' on Trino release " + release + ", which was deployed"
                    + " without it. Turn it on in Upgrade/Config of " + release + " (Custom catalogs, chart 1.43.13 or"
                    + " later).";
        }
        return null;
    }

    private static boolean isOn(Map<String, Object> values, String path) {
        Object v = values == null ? null : ConfigResolutionService.getByDottedPath(values, path);
        return v != null && "true".equalsIgnoreCase(String.valueOf(v));
    }

    TrinoStatementClient clientFor(Target t) {
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
     * The catalog rows with the credentials in their properties masked, for a user who may look at the release but
     * not change it. The properties come from the release values, which hold inline catalogs' passwords as written.
     *
     * @param rows the rows of {@link #list}
     * @return the same rows, masked
     */
    public static List<CatalogView> withoutSecrets(List<CatalogView> rows) {
        for (CatalogView row : rows) {
            row.properties = TrinoCatalogService.withoutSecrets(row.properties);
        }
        return rows;
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
        return aloneInGit(t, persist, () -> {
            if (persist) checkPersistable(t);
            TrinoStatementClient.Result r = clientFor(t).execute(buildCreateCatalog(name, connector, props), operator);
            if (!r.ok()) throw accessOrState(r, t, "create catalog '" + name + "'");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name); out.put("connector", connector); out.put("live", true);
            if (persist) {
                try {
                    out.putAll(persistCatalog(t, name, propertiesText.trim()));
                } catch (RuntimeException notSaved) {
                    throw undoCreateNotCommitted(t, name, operator, notSaved);
                }
                out.put("persisted", true);
            }
            return out;
        });
    }

    /**
     * Runs a catalog change saved to a release deployed with Flux GitOps alone among the changes of the release
     * submitted to Git ({@link CommandService#submittingGitOpsChange}), from its check that Git holds no change of the
     * release Flux has not applied until its own deploy is stored. Two changes passing that check together would each
     * commit the values Helm reports with only their own catalog, the second taking the first's out of Git while it
     * stays live, gone at the next restart. Any other change runs as it is.
     *
     * @param saved whether the change is saved to the release values
     * @throws org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend.GitOpsBusyException when another change
     *         of the release is being submitted to Git; nothing was changed
     */
    private static <T> T aloneInGit(Target t, boolean saved, Supplier<T> change) {
        if (!saved || !heldInGit(t)) return change.get();
        return CommandService.submittingGitOpsChange(t.namespace, t.release, change);
    }

    /**
     * A catalog created live on a release deployed with Flux GitOps whose commit to Git could not be submitted: it is
     * dropped again, so the release is not left with a catalog Git does not hold, gone at the next restart and in the
     * way of a retry ("already exists"); the refusal is then passed on as it came. When it cannot be dropped, the error
     * says the catalog is live but not saved. A release deployed with Helm keeps its refusal as it came.
     *
     * @return the exception to throw
     */
    private RuntimeException undoCreateNotCommitted(Target t, String name, String operator,
                                                    RuntimeException notSaved) {
        if (!heldInGit(t)) return notSaved;
        String undoError;
        try {
            TrinoStatementClient.Result undo = clientFor(t).execute("DROP CATALOG " + name, operator);
            undoError = undo.ok() ? null : undo.error;
        } catch (RuntimeException e) {
            undoError = e.getMessage();
        }
        if (undoError == null) {
            LOG.info("Catalog '{}' dropped again from {}/{}: its commit to Git could not be submitted ({})", name,
                    t.namespace, t.release, notSaved.toString());
            return notSaved;
        }
        LOG.warn("Catalog '{}' stays live on {}/{} without its commit to Git: dropping it again failed: {}", name,
                t.namespace, t.release, undoError);
        return new IllegalStateException("Catalog '" + name + "' was created in Trino, but it could not be committed"
                + " to Git (" + notSaved.getMessage() + "), and dropping it again failed (" + undoError + "). It is"
                + " live only and is gone at the next restart: drop it, or adopt it once the problem is fixed.",
                notSaved);
    }

    public Map<String, Object> drop(String namespace, String release, String name, boolean forget, String operator) {
        Target t = resolve(namespace, release);
        if (!IDENT.matcher(name).matches()) throw new IllegalArgumentException("'" + name + "' is not a catalog name.");
        if (KDPS_MANAGED.contains(name) && valuesCatalogs(t.values).containsKey(name)) {
            throw new IllegalArgumentException("'" + name + "' is built by KDPS from the platform context; turn its toggle off in Upgrade/Config instead.");
        }
        return aloneInGit(t, forget, () -> {
            if (forget && valuesCatalogs(t.values).containsKey(name)) checkPersistable(t);
            else if (forget) refuseDropWhileGitChangePending(t, name);
            TrinoStatementClient.Result r = clientFor(t).execute("DROP CATALOG " + name, operator);
            if (!r.ok() && !(r.errorName != null && r.errorName.contains("NOT_FOUND"))) throw accessOrState(r, t, "drop catalog '" + name + "'");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name); out.put("live", false);
            if (forget && valuesCatalogs(t.values).containsKey(name)) {
                try {
                    out.putAll(persistCatalog(t, name, null));
                } catch (RuntimeException notSaved) {
                    if (!heldInGit(t)) throw notSaved;
                    // Dropped live already: say so, rather than an error that reads as if nothing changed.
                    throw new IllegalStateException("Catalog '" + name + "' was dropped from Trino, but its removal"
                            + " could not be committed to Git (" + notSaved.getMessage() + "). It is still saved to"
                            + " the release and comes back at the next restart: drop it again once the problem is"
                            + " fixed.", notSaved);
                }
                out.put("persisted", false);
            }
            return out;
        });
    }

    /**
     * Refuses, before it is dropped live, a catalog forgotten on a release deployed with Flux GitOps while Git holds a
     * change of the release Flux has not applied: a catalog that change saves is not in the values Helm reports yet,
     * so nothing would remove it from Git, and it would come back once the change is applied.
     *
     * @throws ReleaseHeldInGitException when such a change is pending
     */
    private void refuseDropWhileGitChangePending(Target t, String name) {
        String pending = pendingGitChange(t);
        if (pending != null) {
            throw new ReleaseHeldInGitException("Release " + t.namespace + "/" + t.release + " is deployed with Flux"
                    + " GitOps and Git holds a change of it that is not applied yet: " + pending + ". That change may"
                    + " save catalog '" + name + "', which would then come back once it is applied, so nothing was"
                    + " changed. Drop the catalog once the change is applied.");
        }
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
        return aloneInGit(t, true, () -> {
            checkPersistable(t);
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
            Map<String, Object> saved = persistCatalog(t, name, text);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name); out.put("persisted", true); out.put("properties", text);
            out.putAll(saved);
            return out;
        });
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
            boolean queryRight = r.error != null && (r.error.contains("execute query") || r.error.toLowerCase(Locale.ROOT).contains("queryid"));
            return new SecurityException("Ranger denied you the right to " + action + " on this Trino"
                    + (t.rangerService != null ? " (Ranger service '" + t.rangerService + "')" : "") + ": " + r.error
                    + (queryRight
                        ? ". You first need 'execute' on queryid=* in Ranger (the right to run queries at all)."
                        : ". Ask for the matching catalog permission (create/drop/alter/use/show) on that catalog in Ranger."));
        }
        if (r.error != null && r.error.contains("not supported by the static catalog store")) {
            return new IllegalStateException("This Trino runs with static catalogs — turn on 'Allow catalogs to be created at runtime' "
                    + "(dynamicCatalogs.enabled) in Upgrade/Config first.");
        }
        return new IllegalStateException("Trino refused to " + action + ": " + r.error);
    }

    /**
     * Refuses, before anything is changed live, a catalog change whose persistence cannot run now: a Helm operation on
     * the release is still running. A release deployed with Flux GitOps is not refused: its catalogs are saved through
     * Git ({@link #persistCatalog}).
     */
    static void requirePersistable(Target t) {
        HelmOperationRegistry.requireIdle(t.namespace, t.release);
    }

    /**
     * {@link #requirePersistable}, and for a release deployed with Flux GitOps a way to commit its values, with no
     * change of the release still pending in Git ({@link #refuseWhileGitChangePending}) and nothing its deploy would
     * be refused for at once ({@link GitOpsRedeploy#checkSubmittable}).
     */
    private void checkPersistable(Target t) {
        requirePersistable(t);
        if (heldInGit(t) && gitOpsRedeploy == null) {
            throw new IllegalStateException("Release " + t.namespace + "/" + t.release + " is deployed with Flux"
                    + " GitOps: save the catalog in Upgrade/Config, which commits it to Git.");
        }
        refuseWhileGitChangePending(t);
        if (heldInGit(t)) {
            gitOpsRedeploy.checkSubmittable(t.metadata);
        }
    }

    /**
     * Refuses, before anything is changed live, a catalog change saved to a release deployed with Flux GitOps while
     * Git holds a change of the release Flux has not applied: a pull request not merged yet, a commit not pushed, a
     * removal proposed in a pull request still open, a deploy still running (until Flux has applied what it
     * committed), or a commit Flux has not applied on the cluster, as when the deploy that made it ended before Flux
     * did (its upgrade failed, or the wait timed out). The values committed are built on those Helm reports for the
     * release, which do not hold that change yet: committing them would take it out of Git (out of the pull request,
     * for one not merged). A removal still proposed refuses any deploy of the release, which would only fail once the
     * catalog is changed live.
     *
     * @throws ReleaseHeldInGitException when such a change is pending; the REST resources answer it with 409
     */
    void refuseWhileGitChangePending(Target t) {
        String pending = pendingGitChange(t);
        if (pending != null) {
            throw new ReleaseHeldInGitException("Release " + t.namespace + "/" + t.release + " is deployed with Flux"
                    + " GitOps and Git holds a change of it that is not applied yet: " + pending + ". The catalogs"
                    + " saved to the release cannot be committed to Git without undoing that change, so nothing was"
                    + " changed. Try again once the change is applied; until then, only catalogs not saved to the"
                    + " release can be added.");
        }
    }

    /**
     * The change of a release deployed with Flux GitOps that Git holds and Flux has not applied
     * ({@link #refuseWhileGitChangePending}), or {@code null} when there is none, or the release is deployed with Helm.
     *
     * @throws ReleaseHeldInGitException when its HelmRelease cannot be read
     */
    private String pendingGitChange(Target t) {
        if (!heldInGit(t)) return null;
        String pending = gitChangePending(t.metadata);
        if (pending == null && gitOpsRedeploy != null) {
            String operation = gitOpsRedeploy.runningDeploy(t.namespace, t.release);
            if (operation != null) {
                pending = "a deploy of the release is still running (operation " + operation + "), until Flux has"
                        + " applied what it commits";
            }
        }
        if (pending == null) {
            pending = fluxNotApplied(t);
        }
        return pending;
    }

    /**
     * Why Flux has not applied, on the cluster, the last change of the release committed to Git, read from its
     * HelmRelease ({@link #fluxNotApplied(GenericKubernetesResource, String)}); {@code null} when it has.
     *
     * @throws ReleaseHeldInGitException when the HelmRelease cannot be read: nothing then tells that Helm reports
     *                                   what Git holds
     */
    private String fluxNotApplied(Target t) {
        GenericKubernetesResource helmRelease;
        try {
            helmRelease = kubernetesService.getFluxHelmRelease(t.namespace, t.release);
        } catch (RuntimeException e) {
            LOG.warn("Could not read the Flux HelmRelease of {}/{}: {}", t.namespace, t.release, e.toString());
            throw new ReleaseHeldInGitException("Release " + t.namespace + "/" + t.release + " is deployed with Flux"
                    + " GitOps, and its HelmRelease could not be read to check that Flux has applied what Git holds ("
                    + e.getMessage() + "). The catalogs saved to the release are not committed to Git without that"
                    + " check, so nothing was changed; only catalogs not saved to the release can be added.");
        }
        return fluxNotApplied(helmRelease, digestStillToApply(t.metadata));
    }

    /**
     * The spec digest the last change of the release recorded, while that change can still reach the branch Flux
     * reconciles; {@code null} once its pull request was closed without being merged. The release then runs what the
     * base branch holds, which the HelmRelease on the cluster is taken as: waiting for the closed change would refuse
     * the catalogs saved to the release for good.
     */
    static String digestStillToApply(K8sReleaseEntity meta) {
        if (meta == null) return null;
        if ("PR_MODE".equalsIgnoreCase(meta.getGitCommitMode()) && !meta.isGitPendingDelete()
                && GitPullRequests.isClosed(meta.getGitPrState())) {
            return null;
        }
        return meta.getGitSpecDigest();
    }

    /**
     * Why a HelmRelease on the cluster does not run the last change of its release committed to Git, or {@code null}
     * when it does: Flux has applied the committed version (its spec digest), reports the release Ready for its latest
     * generation, and runs the chart revision it last attempted. Otherwise Helm may still report the values from before
     * that change, as after an upgrade Flux rolled back, or one held by a dependency or a failing chart source.
     *
     * @param helmRelease     the HelmRelease on the cluster, {@code null} when it is not there
     * @param committedDigest the spec digest the last commit recorded; {@code null} when none was recorded (a release
     *                        deployed by an earlier version), which takes the version on the cluster
     */
    static String fluxNotApplied(GenericKubernetesResource helmRelease, String committedDigest) {
        FluxGitOpsBackend.Readiness readiness = FluxGitOpsBackend.readiness(helmRelease, committedDigest);
        if (!readiness.ready()) {
            String reason = "Flux has not applied its last commit (" + readiness.message() + ")";
            Map<String, String> annotations = helmRelease == null || helmRelease.getMetadata() == null
                    || helmRelease.getMetadata().getAnnotations() == null
                    ? Map.of() : helmRelease.getMetadata().getAnnotations();
            if (helmRelease != null && committedDigest != null
                    && !committedDigest.equals(annotations.get(FluxGitOpsBackend.SPEC_DIGEST_ANNOTATION))) {
                // Only a new deploy records another version: a commit reverted or replaced in Git never arrives.
                reason += ". If that commit was reverted or replaced in Git, it will never be applied: redeploy the"
                        + " release with Upgrade/Config, which records the version Git holds now";
            }
            return reason;
        }
        Map<?, ?> status = helmRelease.get("status") instanceof Map<?, ?> m ? m : Map.of();
        Object attempted = status.get("lastAttemptedRevision");
        Object applied = status.get("lastAppliedRevision");
        if (attempted != null && applied != null && !String.valueOf(attempted).equals(String.valueOf(applied))) {
            return "Flux last attempted chart revision " + attempted + " but the release runs " + applied;
        }
        return null;
    }

    /**
     * What change of a release deployed with Flux GitOps its record says is pending in Git: its pull request, not
     * merged yet, a commit for one that could not be pushed, or the pull request proposing its removal, still open (a
     * deploy of the release is refused until that one is merged or closed, so its catalogs could not be committed).
     * {@code null} when the record tells none.
     */
    static String gitChangePending(K8sReleaseEntity meta) {
        if (meta == null || !DeploymentMode.FLUX_GITOPS.name().equalsIgnoreCase(meta.getDeploymentMode())) {
            return null;
        }
        String pullRequest = meta.getGitPrUrl() != null && !meta.getGitPrUrl().isBlank() ? meta.getGitPrUrl()
                : meta.getGitPrNumber();
        boolean pullRequestOpen = pullRequest != null && !pullRequest.isBlank()
                && GitPullRequests.isOpen(meta.getGitPrState());
        if (meta.isGitPendingDelete()) {
            return pullRequestOpen ? "its removal is proposed in pull request " + pullRequest + ", not merged yet"
                    : null;
        }
        if (!"PR_MODE".equalsIgnoreCase(meta.getGitCommitMode())) {
            return null;
        }
        String sha = meta.getGitCommitSha();
        if (sha != null && sha.startsWith("PR-STUB:")) {
            return "its change is committed in the KDPS workspace (" + sha.substring("PR-STUB:".length())
                    + ") but was not pushed for a pull request";
        }
        if (pullRequestOpen) {
            return "its pull request " + pullRequest + " is not merged yet";
        }
        return null;
    }

    /** Whether the release is deployed with Flux GitOps, whose values only change through Git. */
    static boolean heldInGit(Target t) {
        return t.metadata != null && DeploymentMode.FLUX_GITOPS.name().equalsIgnoreCase(t.metadata.getDeploymentMode());
    }

    /**
     * Write the catalog into the release values (null text = remove). A release deployed with Helm gets a helm upgrade
     * of the same chart/version. One deployed with Flux GitOps gets its values committed to Git, by a direct commit or
     * through a pull request as it was deployed: Flux would undo a helm upgrade at its next reconcile, and the catalog
     * would be gone at the next restart.
     *
     * @return for a release deployed with Flux GitOps, the operation that commits the change ({@code commandId}) and
     *         {@code committedToGit}; empty otherwise
     */
    Map<String, Object> persistCatalog(Target t, String name, String text) {
        if (t.metadata == null || t.metadata.getChartRef() == null) {
            throw new IllegalStateException("Release metadata is missing (not deployed through KDPS?) — catalog applied live only, not persisted.");
        }
        checkPersistable(t);
        Map<String, Object> values = valuesWithCatalog(ctx, t, name, text);
        if (heldInGit(t)) {
            String commandId = gitOpsRedeploy.submit(CommandService.releaseRedeployRequest(t.metadata, values));
            LOG.info("Catalog '{}' {} release {}/{} through Git (operation {})", name,
                    text == null ? "removed from" : "committed to", t.namespace, t.release, commandId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("committedToGit", true);
            out.put("commandId", commandId);
            return out;
        }
        HelmService helm = new HelmService(ctx);
        // deployOrUpgrade holds the release for the whole upgrade, refusing it if another operation started since.
        helm.deployOrUpgrade(t.metadata.getChartRef(), t.release, t.namespace, values, Collections.emptyMap(),
                kubernetesService.getConfigurationService().getKubeconfigContents(),
                t.metadata.getRepoId(), t.metadata.getVersion(), 600, true, false, false);
        LOG.info("Catalog '{}' {} in release {}/{} values", name, text == null ? "removed from" : "persisted into", t.namespace, t.release);
        return Collections.emptyMap();
    }

    /**
     * The values a catalog change is saved with: the release's values with the catalog set (null text = removed), in
     * line with the service definition KDPS runs now. The upgrade re-renders the server configuration, which must not
     * carry again a secret an earlier definition wrote into it (the internal shared secret, the keystore password).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> valuesWithCatalog(ViewContext ctx, Target t, String name, String text) {
        Map<String, Object> values = new LinkedHashMap<>(t.values);
        Map<String, Object> catalogs = values.get("catalogs") instanceof Map ? new LinkedHashMap<>((Map<String, Object>) values.get("catalogs")) : new LinkedHashMap<>();
        if (text == null) catalogs.remove(name); else catalogs.put(name, text);
        values.put("catalogs", catalogs);
        CommandService.applyDefinitionValueRewrites(ctx, t.metadata == null ? null : t.metadata.getServiceKey(),
                t.release, values);
        return values;
    }
}
