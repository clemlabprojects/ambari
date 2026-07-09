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
    // CDP: like REMOTE but the remote cluster is Cloudera-managed — discovery runs against its
    // Cloudera Manager REST API instead of Ambari. Config: cmUrl, cmUsername, verifySsl, clusterName;
    // secret: cmPassword.
    public static final String KIND_CDP      = "CDP";

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
        KdpsContextEntity saved = repo.upsert(entity);
        // Deterministic security-profile derivation: (re)derive this context's OIDC profile now — on
        // create/update only, never lazily — so the Step-1 picker is stable and reproducible. EXTERNAL/
        // REMOTE resolve without a local Ambari (REMOTE builds its own remote client).
        reconcileDerivedOidcProfile(saved.getId(), null, null);
        return saved;
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
        pruneDerivedOidcProfile(id);
    }

    // ----- Derived OIDC security profiles (one per OIDC-bearing context, deterministic on CRUD) -----

    private static String derivedOidcProfileKey(String contextId) { return "ctx-oidc-" + contextId; }

    /**
     * (Re)derive the OIDC security profile for a persisted context — invoked on create/update only.
     * A profile keyed {@code ctx-oidc-<id>} and named after the context is upserted when the context
     * carries OIDC (an issuer) + client-registration admin creds + a compatible backend (for REMOTE,
     * Ambari &gt;= 2.8.2.0 with OIDC enabled); otherwise any previously-derived profile for this context
     * is pruned. Operator-authored profiles ({@code derivedFromContext == null}) are never touched.
     * Best-effort — never fails the context save.
     */
    public void reconcileDerivedOidcProfile(String contextId, AmbariActionClient ambari, String cluster) {
        try {
            ResolvedContext rc = resolve(contextId, ambari, cluster);
            SecurityProfileService sps = new SecurityProfileService(viewContext);
            org.apache.ambari.view.k8s.dto.security.SecurityProfilesDTO profiles = sps.loadProfiles();
            if (profiles.profiles == null) profiles.profiles = new java.util.HashMap<>();
            String key = derivedOidcProfileKey(contextId);
            boolean isDefault = DEFAULT_CONTEXT_ID.equals(contextId);
            // A profile is derivable when the context exposes OIDC (an issuer) on a compatible backend
            // AND KDPS can register clients for it — either via the context's own admin creds
            // (EXTERNAL/REMOTE) or, for the default local context, via the local Ambari oidc-env.
            boolean canRegister = rc != null && (rc.hasContextOidcAdminCreds() || isDefault);
            boolean derive = rc != null && rc.hasOidc() && oidcVersionSupported(rc) && canRegister;
            if (derive) {
                profiles.profiles.put(key, buildOidcProfileFromContext(rc));
                // Migrate the legacy bespoke auto-profile (keyed "keycloak") the old bootstrap created
                // for the local cluster — it is now superseded by the unified per-context derivation.
                if (isDefault) {
                    if (profiles.profiles.remove("keycloak") != null) {
                        LOG.info("Migrated legacy auto OIDC profile 'keycloak' -> '{}'", key);
                    }
                    if ("keycloak".equals(profiles.defaultProfile)) profiles.defaultProfile = key;
                }
                if (profiles.defaultProfile == null || profiles.defaultProfile.isBlank()) {
                    profiles.defaultProfile = key;
                }
                LOG.info("Derived OIDC security profile '{}' (source=internal) from context '{}' (issuer={})",
                        key, rc.getName(), rc.getOidcIssuerUrl());
            } else if (profiles.profiles.remove(key) != null) {
                LOG.info("Pruned derived OIDC security profile '{}' — context '{}' no longer exposes usable OIDC",
                        key, contextId);
            }
            sps.saveProfiles(profiles);
        } catch (Exception e) {
            LOG.warn("reconcileDerivedOidcProfile: could not derive OIDC profile for context {}: {}", contextId, e.toString());
        }
    }

    private void pruneDerivedOidcProfile(String contextId) {
        try {
            SecurityProfileService sps = new SecurityProfileService(viewContext);
            org.apache.ambari.view.k8s.dto.security.SecurityProfilesDTO profiles = sps.loadProfiles();
            if (profiles.profiles != null && profiles.profiles.remove(derivedOidcProfileKey(contextId)) != null) {
                sps.saveProfiles(profiles);
                LOG.info("Pruned derived OIDC security profile for deleted context {}", contextId);
            }
        } catch (Exception e) {
            LOG.warn("pruneDerivedOidcProfile: {}", e.toString());
        }
    }

    private org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO buildOidcProfileFromContext(ResolvedContext rc) {
        org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO cfg =
                new org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO();
        cfg.mode = "oidc";
        cfg.derivedFromContext = rc.getId();
        // Human title for the Step-1 picker: the context's own title + the auth type it carries, e.g.
        // "Ambari-managed cluster (OIDC)" or "CDP prod (OIDC)". The map key stays the stable
        // ctx-oidc-<id> identifier; operators see this title, not the key.
        String ctxTitle = (rc.getName() != null && !rc.getName().isBlank()) ? rc.getName() : rc.getId();
        cfg.displayName = ctxTitle + " (OIDC)";
        org.apache.ambari.view.k8s.dto.security.OidcConfig o =
                new org.apache.ambari.view.k8s.dto.security.OidcConfig();
        o.source = "internal"; // context carries admin creds → KDPS registers each service's client itself
        o.issuerUrl = rc.getOidcIssuerUrl();
        o.scopes = "openid email profile";
        o.userClaim = "preferred_username";
        o.groupsClaim = "groups";
        if (rc.getOidcPrincipalDomain() != null && !rc.getOidcPrincipalDomain().isBlank()) {
            o.principalDomain = rc.getOidcPrincipalDomain().trim();
        }
        if ("false".equalsIgnoreCase(rc.getOidcVerifyTls())) o.skipTlsVerify = Boolean.TRUE;
        cfg.oidc = o;
        return cfg;
    }

    /** REMOTE contexts require Ambari &gt;= 2.8.2.0 for OIDC; EXTERNAL (non-Ambari) has no version gate. */
    private boolean oidcVersionSupported(ResolvedContext rc) {
        if (!KIND_REMOTE.equals(rc.getKind())) return true;
        String v = rc.getAmbariVersion();
        if (v == null || v.isBlank()) {
            LOG.info("OIDC derive: remote context '{}' reported no Ambari version — treating as incompatible (need >= 2.8.2.0)", rc.getId());
            return false;
        }
        boolean ok = compareDottedVersions(v, "2.8.2.0") >= 0;
        if (!ok) LOG.info("OIDC derive: remote Ambari {} < 2.8.2.0 — no OIDC profile derived for context '{}'", v, rc.getId());
        return ok;
    }

    /** Numeric dotted-version compare; strips any trailing build suffix (e.g. "2.8.2.0.1-315"). */
    private static int compareDottedVersions(String a, String b) {
        String[] pa = a.replaceAll("[^0-9.].*$", "").split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length && !pa[i].isBlank() ? Integer.parseInt(pa[i]) : 0;
            int y = i < pb.length && !pb[i].isBlank() ? Integer.parseInt(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
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
        if (!KIND_EXTERNAL.equals(req.kind) && !KIND_REMOTE.equals(req.kind) && !KIND_CDP.equals(req.kind)) {
            // MANAGED is virtual (rejected earlier); only EXTERNAL/REMOTE/CDP are persistable.
            throw new IllegalArgumentException("kind must be EXTERNAL, REMOTE or CDP");
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
        if (KIND_CDP.equals(req.kind)) {
            Map<String, Object> cfg = req.config != null ? req.config : new LinkedHashMap<>();
            if (isBlank(str(cfg.get("cmUrl")))) {
                throw new IllegalArgumentException("CDP context requires cmUrl (the Cloudera Manager URL)");
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

        boolean cdp = entity != null && KIND_CDP.equals(entity.getKind());
        boolean discover = entity == null || KIND_MANAGED.equals(entity.getKind()) || remote;
        ResolvedContext rc;
        if (cdp) {
            // Cloudera-managed remote cluster: discover via the Cloudera Manager REST API.
            rc = resolveCdp(entity);
        } else if (discover) {
            rc = resolveManaged(entity, effectiveAmbari, effectiveCluster);
            if (remote) {
                rc.setKind(KIND_REMOTE);
                // Privileged operations cannot be delegated to a remote Ambari server, so the view
                // would perform Ranger ops itself rather than hand them to the (local) server.
                rc.setRangerManaged(false);
                rc.setRemoteAmbariUrl(str(parseConfig(entity.getConfigJson()).get("remoteAmbariUrl")));
                // Operator opt-out of auto-configuring the remote cluster's Ranger/Atlas (writes only).
                rc.setAutoConfigureRemote(!"false".equalsIgnoreCase(
                        str(parseConfig(entity.getConfigJson()).get("autoConfigureRemote"))));
                // Discover remote-cluster info (Ambari version, running stack) for display, stamp the
                // last-contact time, and cache it into the context config so the list/detail can show
                // it without another live call. Best-effort: never fails resolution.
                if (effectiveAmbari != null) {
                    try {
                        Map<String, String> info = effectiveAmbari.getServerAndStackInfo(effectiveCluster);
                        // Only treat it as a successful contact when we actually got something back
                        // (ambariVersion is the no-cluster-needed signal that the remote answered).
                        if (info != null && info.get("ambariVersion") != null) {
                            rc.setAmbariVersion(info.get("ambariVersion"));
                            rc.setStackName(info.get("stackName"));
                            rc.setStackVersion(info.get("stackVersion"));
                            String now = Instant.now().toString();
                            rc.setLastContactAt(now);
                            persistRemoteInfo(entity, info, now);
                        }
                    } catch (Exception e) {
                        LOG.debug("ContextService.resolve: remote info fetch failed for {}: {}",
                                entity.getId(), e.toString());
                    }
                }
            }
        } else {
            rc = resolveExternal(entity);
        }
        // Schema-driven generic field view (what the UI renders) on top of the typed accessors.
        populateResolvedFields(rc, entity, effectiveAmbari, effectiveCluster, discover);

        // Populate the typed OIDC admin-credential accessors used by the OIDC client-registration
        // step and by the derived-profile logic — for EXTERNAL (typed config + decrypted secret) and
        // REMOTE (remote oidc-env, via the remote client) contexts ONLY. The default local MANAGED
        // context deliberately leaves these null so registration keeps reading the local Ambari
        // oidc-env exactly as before (no regression). The non-secret fields are already in the
        // resolvedFields map (config for EXTERNAL, remote oidc-env for REMOTE); the admin client
        // secret is not (secrets are never mirrored into the map), so it is read explicitly.
        {
            Map<String, String> rf = rc.getResolvedFields();
            String issuer = rf.get("oidc.issuerUrl");
            // OIDC coordinates (issuer/realm/verifyTls/principalDomain) are populated for EVERY kind —
            // incl. the default local MANAGED context — so an OIDC security profile can be derived from
            // any context that exposes an issuer.
            if (issuer != null && !issuer.isBlank()) {
                rc.setOidcIssuerUrl(issuer);
                rc.setOidcRealm(rf.get("oidc.realm"));
                rc.setOidcVerifyTls(rf.get("oidc.verifyTls"));
                rc.setOidcPrincipalDomain(rf.get("oidc.principalDomain"));
            }
            // Admin (client-registration) creds are populated ONLY for EXTERNAL/REMOTE. The default
            // local MANAGED context deliberately leaves these null so the OIDC registration step keeps
            // reading the local Ambari oidc-env (with credential-store alias resolution) unchanged.
            if (KIND_EXTERNAL.equals(rc.getKind()) || KIND_REMOTE.equals(rc.getKind())) {
                rc.setOidcAdminRealm(defaultStr(rf.get("oidc.adminRealm"), "master"));
                rc.setOidcAdminClientId(rf.get("oidc.adminClientId"));
                if (issuer != null && !issuer.isBlank()) {
                    // Keycloak realm issuer is <adminBase>/realms/<realm>; strip to get the admin base URL.
                    rc.setOidcAdminUrl(issuer.replaceAll("/realms/.*$", ""));
                }
                // Admin client secret: prefer an operator-typed context secret; for REMOTE fall back to
                // the remote oidc-env value (may be a credential-store alias the view cannot dereference
                // — the operator then supplies it on the context instead).
                String secret = (entity != null) ? readSecret(entity.getId(), "adminClientSecret") : null;
                if ((secret == null || secret.isBlank()) && KIND_REMOTE.equals(rc.getKind())
                        && effectiveAmbari != null && effectiveCluster != null) {
                    try {
                        secret = effectiveAmbari.getDesiredConfigProperty(effectiveCluster, "oidc-env", "oidc_admin_client_secret");
                    } catch (Exception ignored) { }
                }
                if (secret != null && !secret.isBlank()) rc.setOidcAdminClientSecret(secret);
            }
        }
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
            // verifySsl defaults to true; a context that opted into "ignore self-signed" stores false.
            boolean verifySsl = !"false".equalsIgnoreCase(str(cfg.get("verifySsl")));
            return buildClient(url, user, pass, entity.getClusterName(), verifySsl);
        } catch (Exception e) {
            LOG.warn("ContextService: failed to build remote Ambari client for {}: {}",
                    entity.getId(), e.toString());
            return null;
        }
    }

    /**
     * Build an {@link AmbariActionClient} for a remote Ambari from raw connection details (used by
     * both {@link #buildRemoteClient} and {@link #probeRemote}). Normalises the URL to end in
     * {@code /api/v1}, sets a Basic auth header, and toggles TLS verification per {@code verifySsl}.
     */
    private AmbariActionClient buildClient(String url, String user, String pass, String clusterName, boolean verifySsl) {
        String base = url.trim().replaceAll("/+$", "");
        String apiBase = base.endsWith("/api/v1") ? base : base + "/api/v1";
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AmbariActionClient client = (clusterName != null && !clusterName.isBlank())
                ? new AmbariActionClient(viewContext, apiBase, clusterName,
                        java.util.Collections.singletonMap("Authorization", basic))
                : new AmbariActionClient(viewContext, apiBase,
                        java.util.Collections.singletonMap("Authorization", basic));
        return client.verifySsl(verifySsl);
    }

    /**
     * Live "test connection &amp; discover" for a remote Ambari, used by the Contexts UI before a
     * REMOTE context is saved. Connects with the given credentials (honouring {@code verifySsl}),
     * lists the clusters the remote Ambari manages, and reads the Ambari server version. The result
     * doubles as the connection test: {@code ok=false} with a human error message on any failure.
     * Never persists anything and never echoes the password.
     */
    public Map<String, Object> probeRemote(String url, String user, String pass, boolean verifySsl) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (isBlank(url) || isBlank(user) || isBlank(pass)) {
            out.put("ok", false);
            out.put("error", "remoteAmbariUrl, remoteUsername and remotePassword are required");
            return out;
        }
        try {
            AmbariActionClient client = buildClient(url, user, pass, null, verifySsl);
            List<String> clusters = client.listClusters();
            Map<String, String> info = client.getServerAndStackInfo(null); // ambari version (no cluster yet)
            out.put("ok", true);
            out.put("clusters", clusters != null ? clusters : new ArrayList<>());
            if (info.get("ambariVersion") != null) out.put("ambariVersion", info.get("ambariVersion"));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", humanizeConnError(e));
            LOG.info("ContextService.probeRemote failed for {}: {}", url, e.toString());
        }
        return out;
    }

    /**
     * Live "test connection &amp; list clusters" for a Cloudera Manager, used by the Contexts UI before
     * a CDP context is saved (analogue of {@link #probeRemote}). Returns {@code {ok, clusters[],
     * cmVersion, apiVersion}} or {@code {ok:false, error}}. The password authenticates only this
     * probe — never persisted nor echoed.
     */
    public Map<String, Object> probeCdp(String url, String user, String pass, boolean verifySsl) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (isBlank(url) || isBlank(user) || isBlank(pass)) {
            out.put("ok", false);
            out.put("error", "cmUrl, cmUsername and cmPassword are required");
            return out;
        }
        try {
            Map<String, Object> probe = new org.apache.ambari.view.k8s.utils.CmActionClient(url, user, pass, verifySsl).probe();
            if (!Boolean.TRUE.equals(probe.get("ok"))) {
                out.put("ok", false);
                out.put("error", humanizeConnError(new IllegalStateException(String.valueOf(probe.get("error")))));
                return out;
            }
            out.putAll(probe);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", humanizeConnError(e));
            LOG.info("ContextService.probeCdp failed for {}: {}", url, e.toString());
        }
        return out;
    }

    /** Map a connection exception to a short, operator-friendly hint. */
    private static String humanizeConnError(Exception e) {
        String msg = String.valueOf(e.getMessage());
        if (msg.contains("401") || msg.contains("403")) {
            return "Authentication failed — check the username and password.";
        }
        if (e instanceof javax.net.ssl.SSLException || msg.contains("PKIX") || msg.contains("certificate")
                || msg.contains("SSL") || msg.contains("unable to find valid certification path")) {
            return "TLS error reaching the Ambari URL — for a self-signed certificate, enable \"Ignore SSL\".";
        }
        if (e instanceof java.net.UnknownHostException || msg.contains("UnknownHost")) {
            return "Host not found — check the Ambari URL.";
        }
        if (e instanceof java.net.ConnectException || msg.contains("Connection refused")
                || e instanceof java.net.http.HttpConnectTimeoutException || msg.contains("timed out")) {
            return "Cannot reach the Ambari URL (connection refused or timed out) — check host/port and network.";
        }
        return "Could not connect to the remote Ambari: " + msg;
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
        r.setRangerManaged(true); // default: privileged Ranger ops delegated to the Ambari server

        // Operator-supplied Ranger admin credentials are optional even for a MANAGED context. When a
        // saved context carries rangerUrl + rangerAdminUsername + rangerAdminPassword, KDPS performs
        // Ranger repository/policy operations DIRECTLY over REST against that URL (rangerManaged=false)
        // instead of delegating to the Ambari server — this is what lets a managed/remote context
        // behave like an external one for privileged Ranger ops. The default managed context (no
        // entity/config) keeps delegating to Ambari, so existing deploys are unaffected.
        if (entity != null) {
            Map<String, Object> cfg = parseConfig(entity.getConfigJson());
            String cfgRangerUrl  = str(cfg.get("rangerUrl"));
            String cfgRangerUser = str(cfg.get("rangerAdminUsername"));
            String cfgRangerPass = readSecret(entity.getId(), "rangerAdminPassword");
            if (!isBlank(cfgRangerUrl))  r.setRangerUrl(cfgRangerUrl);
            if (!isBlank(cfgRangerUser)) r.setRangerAdminUsername(cfgRangerUser);
            if (!isBlank(cfgRangerPass)) r.setRangerAdminPassword(cfgRangerPass);
            if (!isBlank(cfgRangerUrl) && !isBlank(cfgRangerUser) && !isBlank(cfgRangerPass)) {
                r.setRangerManaged(false);
                LOG.info("ContextService.resolveManaged: Ranger admin credentials supplied on managed context '{}' — "
                        + "Ranger repository/policy ops will run directly over REST against {}", r.getId(), cfgRangerUrl);
            }
        }

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
        // Operator opt-out of KDPS auto-configuring the remote Ranger/Atlas (WRITE/push paths only).
        // Default true; only an explicit "false" disables. Property resolution (reads) is never gated.
        r.setAutoConfigureRemote(!"false".equalsIgnoreCase(str(config.get("autoConfigureRemote"))));
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

    /**
     * Resolve a CDP (Cloudera Manager) context by live-discovering Hive/Ranger/Atlas endpoints + the
     * Kerberos realm from CM's REST API — the CDP analogue of {@link #resolveManaged}, so an external
     * Cloudera-managed cluster can be a KDPS context source without hand-entering every property. The
     * discovery is dynamic (fresh each resolve). Ranger/Atlas admin credentials are NOT exposed by CM,
     * so those remain operator-supplied (stored config + decrypted secret). Populates both the typed
     * getters (rangerUrl/atlasUrl/kerberosRealm) and the generic {@code resolvedFields} map the deploy
     * reads Hive props from ({@code hive.*}). Best-effort: a CM read failure leaves the field unset and
     * logs — it never throws out of resolve (the context still resolves with whatever was reachable).
     */
    private ResolvedContext resolveCdp(KdpsContextEntity entity) {
        ResolvedContext r = new ResolvedContext();
        r.setId(entity.getId());
        r.setName(entity.getName());
        r.setKind(KIND_CDP);
        r.setAtlasManaged(false);
        r.setRangerManaged(false);

        Map<String, Object> config = parseConfig(entity.getConfigJson());
        r.setClusterName(str(config.get("clusterName")));
        r.setAutoConfigureRemote(!"false".equalsIgnoreCase(str(config.get("autoConfigureRemote"))));
        // CM does not expose Ranger/Atlas admin creds; those stay operator-supplied.
        r.setRangerAdminUsername(defaultStr(str(config.get("rangerAdminUsername")), "admin"));
        r.setRangerAdminPassword(readSecret(entity.getId(), "rangerAdminPassword"));
        r.setAtlasAuthMode(defaultStr(str(config.get("atlasAuthMode")), "basic"));
        r.setAtlasAclMode(defaultStr(str(config.get("atlasAclMode")), "ranger"));
        r.setAtlasFederationUser(str(config.get("federationUser")));
        r.setAtlasFederationPassword(readSecret(entity.getId(), "federationPassword"));

        String cmUrl = str(config.get("cmUrl"));
        Map<String, String> rf = r.getResolvedFields();
        if (cmUrl == null || cmUrl.isBlank()) {
            LOG.warn("CDP context {} has no cmUrl — nothing to discover", entity.getId());
            return r;
        }
        boolean verifySsl = "true".equalsIgnoreCase(defaultStr(str(config.get("verifySsl")), "false"));
        String cmUser = str(config.get("cmUsername"));
        String cmPass = readSecret(entity.getId(), "cmPassword");
        try {
            org.apache.ambari.view.k8s.utils.CmActionClient cm =
                    new org.apache.ambari.view.k8s.utils.CmActionClient(cmUrl, cmUser, cmPass, verifySsl);

            String cluster = str(config.get("clusterName"));
            if (cluster == null || cluster.isBlank()) {
                List<Map<String, Object>> cl = cm.listClusters();
                if (!cl.isEmpty()) { cluster = str(cl.get(0).get("name")); r.setClusterName(cluster); }
            }
            if (cluster == null || cluster.isBlank()) {
                LOG.warn("CDP context {}: CM has no clusters", entity.getId());
                return r;
            }

            // Kerberos realm (drives hive.authMode).
            String realm = null;
            try { realm = cm.cmConfig().get("SECURITY_REALM"); } catch (Exception ignore) {}
            if (realm != null && !realm.isBlank()) {
                r.setKerberosRealm(realm);
                rf.put("kerberos.realm", realm);
            }
            rf.put("hive.authMode", (realm != null && !realm.isBlank()) ? "kerberos" : "none");

            for (Map<String, Object> svc : cm.listServices(cluster)) {
                String type = str(svc.get("type"));
                String name = str(svc.get("name"));
                if (type == null) continue;
                switch (type) {
                    case "HIVE_ON_TEZ": {   // HiveServer2 lives here in CDP 7.x
                        String host = cm.firstRoleHost(cluster, name, "HIVESERVER2");
                        Map<String, String> cfg = cm.configForRoleType(cluster, name, "HIVESERVER2");
                        String transport = defaultStr(pick(cfg, "hive_server2_transport_mode",
                                "hive.server2.transport.mode"), "binary");
                        boolean ssl = truthy(pick(cfg, "hiveserver2_enable_ssl", "hive.server2.use.SSL", "ssl_enabled"));
                        String port = "http".equalsIgnoreCase(transport)
                                ? defaultStr(pick(cfg, "hive_server2_thrift_http_port", "hive.server2.thrift.http.port"), "10001")
                                : defaultStr(pick(cfg, "hs2_thrift_address_port", "hive.server2.thrift.port"), "10000");
                        if (host != null) {
                            rf.put("hive.hs2HostPort", host + ":" + port);
                            rf.put("hive.transportMode", transport.toLowerCase());
                            rf.put("hive.scheme", ssl ? "https" : "http");
                        }
                        break;
                    }
                    case "HIVE": {          // Metastore (and, on older CDP, HS2 too)
                        String msHost = cm.firstRoleHost(cluster, name, "HIVEMETASTORE");
                        if (msHost != null) {
                            Map<String, String> cfg = cm.configForRoleType(cluster, name, "HIVEMETASTORE");
                            String port = defaultStr(pick(cfg, "hive_metastore_port", "hive.metastore.port"), "9083");
                            rf.put("hive.metastoreUri", "thrift://" + msHost + ":" + port);
                        }
                        if (!rf.containsKey("hive.hs2HostPort")) {
                            String hs2 = cm.firstRoleHost(cluster, name, "HIVESERVER2");
                            if (hs2 != null) {
                                Map<String, String> cfg = cm.configForRoleType(cluster, name, "HIVESERVER2");
                                String transport = defaultStr(pick(cfg, "hive_server2_transport_mode"), "binary");
                                boolean ssl = truthy(pick(cfg, "hiveserver2_enable_ssl", "ssl_enabled"));
                                String port = "http".equalsIgnoreCase(transport)
                                        ? defaultStr(pick(cfg, "hive_server2_thrift_http_port"), "10001")
                                        : defaultStr(pick(cfg, "hs2_thrift_address_port"), "10000");
                                rf.put("hive.hs2HostPort", hs2 + ":" + port);
                                rf.put("hive.transportMode", transport.toLowerCase());
                                rf.put("hive.scheme", ssl ? "https" : "http");
                            }
                        }
                        break;
                    }
                    case "RANGER": {
                        String host = cm.firstRoleHost(cluster, name, "RANGER_ADMIN");
                        Map<String, String> cfg = cm.configForRoleType(cluster, name, "RANGER_ADMIN");
                        String httpsPort = pick(cfg, "ranger_service_https_port");
                        boolean ssl = httpsPort != null && !httpsPort.isBlank()
                                && truthy(pick(cfg, "ssl_enabled", "ranger.service.https.attrib.ssl.enabled"));
                        String port = ssl ? httpsPort : defaultStr(pick(cfg, "ranger_service_http_port"), "6080");
                        if (host != null) {
                            String url = (ssl ? "https" : "http") + "://" + host + ":" + port;
                            r.setRangerUrl(url);
                            rf.put("ranger.rangerUrl", url);
                        }
                        // CDP Ranger repo naming convention: cm_<component>.
                        rf.put("hive.rangerServiceName", defaultStr(str(config.get("rangerHiveService")), "cm_hive"));
                        r.setAtlasRangerServiceName(defaultStr(str(config.get("atlasRangerServiceName")), "cm_atlas"));
                        break;
                    }
                    case "ATLAS": {
                        String host = cm.firstRoleHost(cluster, name, "ATLAS_SERVER");
                        Map<String, String> cfg = cm.configForRoleType(cluster, name, "ATLAS_SERVER");
                        boolean ssl = truthy(pick(cfg, "ssl_enabled", "atlas.enableTLS"));
                        String port = ssl ? defaultStr(pick(cfg, "atlas_server_https_port"), "21443")
                                          : defaultStr(pick(cfg, "atlas_server_http_port"), "21000");
                        if (host != null) {
                            String url = (ssl ? "https" : "http") + "://" + host + ":" + port;
                            r.setAtlasUrl(url);
                            rf.put("atlas.atlasUrl", url);
                        }
                        break;
                    }
                    default:
                        // ignore other service types
                }
            }
        } catch (Exception e) {
            LOG.warn("CDP discovery failed for context {} ({}): {}", entity.getId(), cmUrl, e.toString());
        }
        return r;
    }

    /** First non-blank value among {@code keys} in {@code m}, or null. */
    private static String pick(Map<String, String> m, String... keys) {
        if (m == null) return null;
        for (String k : keys) {
            String v = m.get(k);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static boolean truthy(String v) {
        return v != null && ("true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v));
    }

    /**
     * Preflight check for a context's remote Ranger/Atlas — used when the operator disabled
     * {@code autoConfigureRemote} and configured those components themselves. Probes reachability +
     * admin auth (and, for Ranger, lists the service repos so the operator can confirm the expected
     * one exists). Read-only; never mutates. Returns {@code {context, autoConfigureRemote, ok,
     * checks:[{component, ok, skipped, message, detail}]}}.
     */
    public Map<String, Object> preflight(String contextId, AmbariActionClient ambari, String clusterName) {
        ResolvedContext rc = resolve(contextId, ambari, clusterName);
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(probeRangerComponent(rc));
        checks.add(probeAtlasComponent(rc));
        boolean ok = checks.stream().allMatch(c ->
                Boolean.TRUE.equals(c.get("ok")) || Boolean.TRUE.equals(c.get("skipped")));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("context", rc.getName());
        out.put("kind", rc.getKind());
        out.put("autoConfigureRemote", rc.isAutoConfigureRemote());
        out.put("ok", ok);
        out.put("checks", checks);
        return out;
    }

    private Map<String, Object> probeRangerComponent(ResolvedContext rc) {
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("component", "Ranger");
        String url = str(rc.getRangerUrl());
        if (url == null || url.isBlank()) { c.put("skipped", true); c.put("message", "No Ranger URL in context"); return c; }
        try {
            String body = httpGet(url.replaceAll("/+$", "") + "/service/public/v2/api/service",
                    str(rc.getRangerAdminUsername()), rc.getRangerAdminPassword());
            java.util.List<String> repos = new ArrayList<>();
            try { for (Object s : (List<?>) new com.google.gson.Gson().fromJson(body, List.class)) {
                Object n = ((Map<?, ?>) s).get("name"); if (n != null) repos.add(String.valueOf(n)); } } catch (Exception ignore) {}
            c.put("ok", true);
            c.put("message", "Ranger reachable + admin auth OK");
            c.put("detail", "service repos: " + repos);
        } catch (Exception e) {
            c.put("ok", false);
            c.put("message", "Ranger check failed: " + trimMsg(e));
        }
        return c;
    }

    private Map<String, Object> probeAtlasComponent(ResolvedContext rc) {
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("component", "Atlas");
        String url = str(rc.getAtlasUrl());
        if (url == null || url.isBlank()) { c.put("skipped", true); c.put("message", "No Atlas URL in context"); return c; }
        try {
            String body = httpGet(url.replaceAll("/+$", "") + "/api/atlas/admin/status",
                    str(rc.getAtlasFederationUser()), rc.getAtlasFederationPassword());
            boolean active = body != null && body.contains("ACTIVE");
            c.put("ok", active);
            c.put("message", active ? "Atlas reachable + ACTIVE" : "Atlas reachable but not ACTIVE");
            c.put("detail", body == null ? "" : body.trim());
        } catch (Exception e) {
            c.put("ok", false);
            c.put("message", "Atlas check failed: " + trimMsg(e));
        }
        return c;
    }

    /** Minimal trust-all HTTPS GET with optional basic auth — for preflight probes of self-signed
     * external Ranger/Atlas (mirrors the insecure read path used for remote-Ambari probes). */
    private String httpGet(String url, String user, String password) throws Exception {
        // Trust-all also implies skipping HTTPS endpoint-identity (hostname/SAN) checks — java.net.http
        // enforces those independently of the TrustManager and only relaxes them via this global
        // property (it ignores SSLParameters' endpointIdentificationAlgorithm). Self-signed Ranger/
        // Atlas on an IP or mismatched SAN would otherwise fail preflight with a hostname error.
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
        sc.init(null, new javax.net.ssl.TrustManager[]{ new javax.net.ssl.X509TrustManager() {
            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
        }}, new java.security.SecureRandom());
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .sslContext(sc).connectTimeout(java.time.Duration.ofSeconds(8)).build();
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(12)).GET();
        if (user != null && !user.isBlank() && password != null) {
            String tok = java.util.Base64.getEncoder().encodeToString((user + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            b.header("Authorization", "Basic " + tok);
        }
        java.net.http.HttpResponse<String> resp = client.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private static String trimMsg(Throwable t) {
        String m = t == null ? "" : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        return m.length() > 160 ? m.substring(0, 160) : m;
    }

    /**
     * Cache the discovered remote-cluster info + last-contact time into the context's non-secret
     * {@code configJson} so the list/detail views can show it without another live call. Best-effort:
     * any failure (oversized config, persistence hiccup) is swallowed — it must never break resolve.
     */
    private void persistRemoteInfo(KdpsContextEntity entity, Map<String, String> info, String now) {
        try {
            Map<String, Object> cfg = parseConfig(entity.getConfigJson());
            if (info.get("ambariVersion") != null) cfg.put("ambariVersion", info.get("ambariVersion"));
            if (info.get("stackName") != null) cfg.put("stackName", info.get("stackName"));
            if (info.get("stackVersion") != null) cfg.put("stackVersion", info.get("stackVersion"));
            cfg.put("lastContactAt", now);
            String json = GSON.toJson(cfg);
            if (json.length() <= MAX_CONFIG_JSON_LENGTH) {
                entity.setConfigJson(json);
                repo.upsert(entity);
            }
        } catch (Exception e) {
            LOG.debug("ContextService.persistRemoteInfo failed for {}: {}", entity.getId(), e.toString());
        }
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
