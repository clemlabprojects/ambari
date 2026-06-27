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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.ContextRequest;
import org.apache.ambari.view.k8s.model.ResolvedContext;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.apache.ambari.view.k8s.store.KdpsContextEntity;
import org.apache.ambari.view.k8s.store.KdpsContextRepo;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * CRUD + resolution for {@link KdpsContextEntity} platform contexts.
 *
 * <p>Secrets (e.g. an external Ranger admin password) are encrypted with
 * {@link EncryptionService} and stored in the view instance data under
 * {@code context.<id>.<name>} — never in the DataStore, never echoed back.
 *
 * <p>{@link #resolve} turns a stored context into a {@link ResolvedContext}: for MANAGED
 * contexts it discovers Atlas/Ranger live from Ambari (mirroring DiscoveryResource); for
 * EXTERNAL contexts it reads stored config + decrypts the needed secrets.
 */
public class ContextService {

    private static final Logger LOG = LoggerFactory.getLogger(ContextService.class);

    /** Stable id of the auto-seeded context bound to the Ambari-managed cluster. */
    public static final String DEFAULT_CONTEXT_ID = "default";

    public static final String KIND_MANAGED  = "MANAGED";
    public static final String KIND_EXTERNAL = "EXTERNAL";
    // REMOTE: like MANAGED but discovery runs against a DIFFERENT cluster's Ambari, reached over
    // the network with stored credentials (the "connect to remote cluster" context).
    public static final String KIND_REMOTE   = "REMOTE";

    private static final String SECRET_PREFIX = "context.";
    private static final Gson GSON = new Gson();

    /**
     * Max serialized length of {@code configJson}. Matches Ambari view persistence's per-String
     * column cap ({@code DataStoreImpl.MAX_ENTITY_STRING_FIELD_LENGTH}); a context config is a
     * handful of URLs/usernames, so this is never reached in practice.
     */
    private static final int MAX_CONFIG_JSON_LENGTH = 3000;

    private final ViewContext viewContext;
    private final KdpsContextRepo repo;
    private final EncryptionService encryptionService = new EncryptionService();

    public ContextService(ViewContext ctx) {
        this.viewContext = ctx;
        this.repo = new KdpsContextRepo(ctx.getDataStore());
    }

    // ------------------------------------------------------------------ CRUD

    /**
     * List contexts: the (virtual) MANAGED default followed by any persisted EXTERNAL
     * contexts. The managed default is synthesized live from the Ambari cluster and is
     * never stored — it is a pure projection of what Ambari already knows, so there is
     * nothing to persist and no risk of drift.
     */
    public Collection<KdpsContextEntity> list(String clusterName) {
        List<KdpsContextEntity> out = new ArrayList<>();
        out.add(managedDefault(clusterName));
        try {
            for (KdpsContextEntity e : repo.findAll()) {
                if (!DEFAULT_CONTEXT_ID.equals(e.getId())) {
                    out.add(e);
                }
            }
        } catch (Exception ex) {
            // EXTERNAL contexts require the k8s_context entity table, which only exists
            // once the view version that introduced it has been (re)registered. Tolerate
            // its absence so the managed default still lists cleanly.
            LOG.warn("ContextService.list: could not read external contexts ({})", ex.toString());
        }
        return out;
    }

    public KdpsContextEntity get(String id) {
        if (id == null || id.isBlank() || DEFAULT_CONTEXT_ID.equals(id)) {
            return managedDefault(null);
        }
        return repo.findById(id);
    }

    /** Synthesize the virtual MANAGED default context (never persisted). */
    private KdpsContextEntity managedDefault(String clusterName) {
        KdpsContextEntity ctx = new KdpsContextEntity();
        ctx.setId(DEFAULT_CONTEXT_ID);
        ctx.setName("Ambari-managed cluster" + (clusterName != null && !clusterName.isBlank() ? " (" + clusterName + ")" : ""));
        ctx.setKind(KIND_MANAGED);
        ctx.setClusterName(clusterName);
        ctx.setDescription("The ODP cluster managed by this Ambari. Connection details and "
                + "credentials are resolved live from Ambari; Ranger operations are delegated to "
                + "the Ambari server (no admin password handled by KDPS).");
        return ctx;
    }

    /**
     * Create or update a context from a {@link ContextRequest}. The non-secret {@link
     * ContextRequest#config} map is serialized into the entity's {@code configJson}; each plaintext
     * secret in {@link ContextRequest#secrets} is encrypted into the view instance data and its key
     * name recorded in {@code secretKeys}. Plaintext secrets are never persisted to the DataStore
     * nor returned. The persisted {@link KdpsContextEntity} carries only scalar columns (see that
     * class's persistence contract).
     */
    public KdpsContextEntity save(ContextRequest request) {
        Objects.requireNonNull(request, "context must not be null");
        if (DEFAULT_CONTEXT_ID.equals(request.id) || KIND_MANAGED.equals(request.kind)) {
            throw new IllegalArgumentException(
                    "The managed default context is virtual and cannot be created or edited; "
                    + "only EXTERNAL and REMOTE contexts are persisted.");
        }
        validate(request);

        KdpsContextEntity entity = new KdpsContextEntity();
        entity.setId(request.id);
        entity.setName(request.name);
        entity.setKind(request.kind);
        entity.setClusterName(request.clusterName);
        entity.setDescription(request.description);

        // Serialize non-secret config. Ambari stores String columns up to 3000 chars; guard with a
        // clear error rather than letting the persistence layer throw an opaque one.
        Map<String, Object> config = request.config != null ? request.config : new LinkedHashMap<>();
        String configJson = GSON.toJson(config);
        if (configJson.length() > MAX_CONFIG_JSON_LENGTH) {
            throw new IllegalArgumentException("Context configuration is too large ("
                    + configJson.length() + " chars; max " + MAX_CONFIG_JSON_LENGTH + ").");
        }
        entity.setConfigJson(configJson);

        // Encrypt + store provided secrets; track key names. Merge with existing keys so a
        // partial update (only some secrets re-entered) does not drop previously stored ones.
        KdpsContextEntity prior = repo.findById(request.id);
        List<String> keys = new ArrayList<>();
        if (prior != null && prior.getSecretKeys() != null && !prior.getSecretKeys().isBlank()) {
            for (String k : prior.getSecretKeys().split(",")) {
                if (!k.isBlank()) keys.add(k.trim());
            }
        }
        if (request.secrets != null) {
            for (Map.Entry<String, String> e : request.secrets.entrySet()) {
                String name = e.getKey();
                String plain = e.getValue();
                if (name == null || name.isBlank() || plain == null || plain.isBlank()) {
                    continue;
                }
                String ref = SECRET_PREFIX + request.id + "." + name;
                String enc = Base64.getEncoder().encodeToString(encryptionService.encrypt(plain.getBytes()));
                viewContext.putInstanceData(ref, enc);
                if (!keys.contains(name)) {
                    keys.add(name);
                }
            }
        }
        entity.setSecretKeys(String.join(",", keys));

        String now = Instant.now().toString();
        entity.setCreatedAt(prior != null && prior.getCreatedAt() != null ? prior.getCreatedAt() : now);
        entity.setUpdatedAt(now);
        return repo.upsert(entity);
    }

    public void delete(String id) {
        if (DEFAULT_CONTEXT_ID.equals(id)) {
            throw new IllegalStateException("The default (managed) context cannot be deleted.");
        }
        KdpsContextEntity entity = repo.findById(id);
        if (entity != null && entity.getSecretKeys() != null) {
            for (String name : entity.getSecretKeys().split(",")) {
                if (!name.isBlank()) {
                    viewContext.removeInstanceData(SECRET_PREFIX + id + "." + name.trim());
                }
            }
        }
        repo.deleteById(id);
    }

    private void validate(ContextRequest req) {
        if (req.id == null || req.id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (req.name == null || req.name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (req.kind == null || req.kind.isBlank()) {
            req.kind = KIND_EXTERNAL;
        }
        if (!KIND_EXTERNAL.equals(req.kind) && !KIND_REMOTE.equals(req.kind)) {
            // MANAGED is virtual (rejected earlier); only EXTERNAL/REMOTE are persistable.
            throw new IllegalArgumentException("kind must be EXTERNAL or REMOTE");
        }
        if (KIND_REMOTE.equals(req.kind)) {
            Map<String, Object> cfg = req.config != null ? req.config : new LinkedHashMap<>();
            if (isBlank(str(cfg.get("remoteAmbariUrl")))) {
                throw new IllegalArgumentException("REMOTE context requires remoteAmbariUrl");
            }
            if (req.clusterName == null || req.clusterName.isBlank()) {
                throw new IllegalArgumentException("REMOTE context requires clusterName (the remote cluster)");
            }
        }
    }

    /** Decrypt a stored secret for a context, or null if not present. */
    public String readSecret(String contextId, String name) {
        String ref = SECRET_PREFIX + contextId + "." + name;
        String enc = viewContext.getInstanceData(ref);
        if (enc == null) {
            return null;
        }
        return new String(encryptionService.decrypt(Base64.getDecoder().decode(enc)));
    }

    // ------------------------------------------------------------- resolution

    /**
     * Resolve a context id (blank/"default" → managed cluster) into a {@link ResolvedContext}.
     *
     * @param contextId   the requested context id, or null/blank for the managed default
     * @param ambari      an Ambari client (required to resolve a MANAGED context); may be null
     *                    for purely EXTERNAL resolution
     * @param clusterName the Ambari cluster name (for MANAGED resolution)
     */
    public ResolvedContext resolve(String contextId, AmbariActionClient ambari, String clusterName) {
        KdpsContextEntity entity = (contextId == null || contextId.isBlank())
                ? null
                : repo.findById(contextId);

        // REMOTE behaves like MANAGED but discovers against a remote cluster's Ambari, reached
        // with stored credentials. We build a client pointed at that Ambari and run the same
        // managed-style resolution through it.
        boolean remote = entity != null && KIND_REMOTE.equals(entity.getKind());
        AmbariActionClient effectiveAmbari = ambari;
        String effectiveCluster = clusterName;
        if (remote) {
            effectiveAmbari = buildRemoteClient(entity);
            effectiveCluster = entity.getClusterName();
        }

        boolean discover = entity == null || KIND_MANAGED.equals(entity.getKind()) || remote;
        ResolvedContext rc;
        if (discover) {
            rc = resolveManaged(entity, effectiveAmbari, effectiveCluster);
            if (remote) {
                rc.setKind(KIND_REMOTE);
                // Privileged operations cannot be delegated to a remote Ambari server, so the view
                // would perform Ranger ops itself rather than hand them to the (local) server.
                rc.setRangerManaged(false);
                rc.setRemoteAmbariUrl(str(parseConfig(entity.getConfigJson()).get("remoteAmbariUrl")));
            }
        } else {
            rc = resolveExternal(entity);
        }
        // Schema-driven generic field view (what the UI renders) on top of the typed accessors.
        populateResolvedFields(rc, entity, effectiveAmbari, effectiveCluster, discover);
        return rc;
    }

    /**
     * Build an {@link AmbariActionClient} pointed at a REMOTE context's Ambari, authenticated with
     * the stored credentials. Returns {@code null} when the context is missing the URL, username,
     * or password, or on any error — resolution then degrades to a skeleton (no capabilities)
     * rather than failing. The password is read from the encrypted secret store only to build the
     * request's Basic header; it is never logged nor placed on the {@link ResolvedContext}.
     */
    private AmbariActionClient buildRemoteClient(KdpsContextEntity entity) {
        try {
            Map<String, Object> cfg = parseConfig(entity.getConfigJson());
            String url = str(cfg.get("remoteAmbariUrl"));
            String user = str(cfg.get("remoteUsername"));
            String pass = readSecret(entity.getId(), "remotePassword");
            if (isBlank(url) || isBlank(user) || isBlank(pass)) {
                LOG.warn("ContextService: REMOTE context {} is missing remote Ambari url/username/password; "
                        + "cannot connect", entity.getId());
                return null;
            }
            String base = url.trim().replaceAll("/+$", "");
            String apiBase = base.endsWith("/api/v1") ? base : base + "/api/v1";
            String basic = "Basic " + Base64.getEncoder().encodeToString(
                    (user + ":" + pass).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new AmbariActionClient(viewContext, apiBase, entity.getClusterName(),
                    java.util.Collections.singletonMap("Authorization", basic));
        } catch (Exception e) {
            LOG.warn("ContextService: failed to build remote Ambari client for {}: {}",
                    entity.getId(), e.toString());
            return null;
        }
    }

    /**
     * Fill {@link ResolvedContext#getResolvedFields()} for every non-secret schema field:
     * MANAGED → run each field's {@code managedResolver} live; EXTERNAL → read stored config.
     * Records which secret fields have a value in {@link ResolvedContext#getSecretFieldsSet()}.
     */
    private void populateResolvedFields(ResolvedContext rc, KdpsContextEntity entity,
                                        AmbariActionClient ambari, String clusterName, boolean managed) {
        Map<String, Object> config = (entity != null) ? parseConfig(entity.getConfigJson()) : new LinkedHashMap<>();
        ManagedContextResolver resolver = managed
                ? new ManagedContextResolver(ambari, rc.getClusterName() != null ? rc.getClusterName() : clusterName)
                : null;
        List<org.apache.ambari.view.k8s.model.ContextCapabilitySchema> schema =
                new ContextSchemaService().loadSchema();
        for (org.apache.ambari.view.k8s.model.ContextCapabilitySchema cap : schema) {
            for (org.apache.ambari.view.k8s.model.ContextCapabilitySchema.ContextFieldDef f : cap.fields) {
                String key = cap.capability + "." + f.name;
                if (f.secret) {
                    boolean set = !managed && entity != null && entity.getSecretKeys() != null
                            && java.util.Arrays.asList(entity.getSecretKeys().split(",")).contains(f.name);
                    if (set) rc.getSecretFieldsSet().add(key);
                    continue;
                }
                String value = null;
                if (managed) {
                    if (f.managedResolver != null && resolver != null) {
                        value = resolver.resolve(f.managedResolver);
                    }
                } else {
                    value = str(config.get(f.name));
                }
                if (value != null && !value.isBlank()) {
                    rc.getResolvedFields().put(key, value);
                }
            }
        }
    }

    private ResolvedContext resolveManaged(KdpsContextEntity entity, AmbariActionClient ambari,
                                           String clusterName) {
        ResolvedContext r = new ResolvedContext();
        r.setId(entity != null ? entity.getId() : DEFAULT_CONTEXT_ID);
        r.setName(entity != null ? entity.getName() : "Ambari-managed cluster");
        r.setKind(KIND_MANAGED);
        String cluster = (entity != null && entity.getClusterName() != null && !entity.getClusterName().isBlank())
                ? entity.getClusterName() : clusterName;
        r.setClusterName(cluster);
        r.setRangerManaged(true); // privileged Ranger ops delegated to the Ambari server

        if (ambari == null || cluster == null) {
            LOG.warn("ContextService.resolveManaged: no Ambari client/cluster — returning skeleton managed context");
            return r;
        }
        try {
            List<String> atlasHosts = ambari.getComponentHosts(cluster, "ATLAS", "ATLAS_SERVER");
            boolean atlasManaged = atlasHosts != null && !atlasHosts.isEmpty();
            r.setAtlasManaged(atlasManaged);
            if (atlasManaged) {
                String httpsPort = ambari.getDesiredConfigProperty(cluster, "application-properties", "atlas.server.https.port");
                String httpPort  = ambari.getDesiredConfigProperty(cluster, "application-properties", "atlas.server.http.port");
                String tlsEnabled = ambari.getDesiredConfigProperty(cluster, "application-properties", "atlas.enableTLS");
                boolean tls = "true".equalsIgnoreCase(tlsEnabled == null ? "" : tlsEnabled.trim());
                String port = tls
                        ? (isBlank(httpsPort) ? "21443" : httpsPort.trim())
                        : (isBlank(httpPort) ? "21000" : httpPort.trim());
                r.setAtlasUrl((tls ? "https://" : "http://") + atlasHosts.get(0) + ":" + port);

                String fileOn     = ambari.getDesiredConfigProperty(cluster, "application-properties", "atlas.authentication.method.file");
                String ldapOn     = ambari.getDesiredConfigProperty(cluster, "application-properties", "atlas.authentication.method.ldap");
                String kerberosOn = ambari.getDesiredConfigProperty(cluster, "application-properties", "atlas.authentication.method.kerberos");
                String authMode;
                if ("true".equalsIgnoreCase(trim(fileOn))) {
                    authMode = "basic";
                } else if ("true".equalsIgnoreCase(trim(ldapOn))) {
                    authMode = "ldap";
                } else if ("true".equalsIgnoreCase(trim(kerberosOn))) {
                    authMode = "kerberos";
                } else {
                    authMode = "none";
                }
                r.setAtlasAuthMode(authMode);

                String authzImpl = ambari.getDesiredConfigProperty(cluster, "application-properties", "atlas.authorizer.impl");
                r.setAtlasAclMode(authzImpl != null && authzImpl.trim().toLowerCase().contains("ranger") ? "ranger" : "simple");
                r.setAtlasRangerServiceName(cluster + "_atlas");

                String realm = ambari.getDesiredConfigProperty(cluster, "kerberos-env", "realm");
                r.setKerberosRealm(realm);
            }
        } catch (Exception e) {
            LOG.warn("ContextService.resolveManaged: Atlas/Ranger discovery failed: {}", e.toString());
        }
        return r;
    }

    private ResolvedContext resolveExternal(KdpsContextEntity entity) {
        ResolvedContext r = new ResolvedContext();
        r.setId(entity.getId());
        r.setName(entity.getName());
        r.setKind(KIND_EXTERNAL);
        r.setClusterName(entity.getClusterName());
        r.setAtlasManaged(false);
        r.setRangerManaged(false); // view performs Ranger REST itself with stored creds

        Map<String, Object> config = parseConfig(entity.getConfigJson());
        r.setAtlasUrl(str(config.get("atlasUrl")));
        r.setAtlasAuthMode(defaultStr(str(config.get("atlasAuthMode")), "basic"));
        r.setAtlasAclMode(defaultStr(str(config.get("atlasAclMode")), "ranger"));
        r.setAtlasRangerServiceName(str(config.get("atlasRangerServiceName")));
        r.setRangerUrl(str(config.get("rangerUrl")));
        r.setRangerAdminUsername(defaultStr(str(config.get("rangerAdminUsername")), "admin"));
        r.setRangerAdminPassword(readSecret(entity.getId(), "rangerAdminPassword"));
        r.setAtlasFederationUser(str(config.get("federationUser")));
        r.setAtlasFederationPassword(readSecret(entity.getId(), "federationPassword"));
        return r;
    }

    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> m = GSON.fromJson(json, new TypeToken<LinkedHashMap<String, Object>>(){}.getType());
            return m != null ? m : new LinkedHashMap<>();
        } catch (Exception e) {
            LOG.warn("ContextService: failed to parse configJson: {}", e.toString());
            return new LinkedHashMap<>();
        }
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static String defaultStr(String v, String def) { return isBlank(v) ? def : v; }
}
