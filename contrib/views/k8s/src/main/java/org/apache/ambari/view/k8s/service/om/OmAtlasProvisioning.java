/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.view.k8s.service.om;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Helpers shared by the Atlas-federation provisioning steps
 * ({@code ATLAS_USER_PROVISION_OM}, {@code ATLAS_SIMPLE_AUTHZ_GRANT_OM},
 * {@code RANGER_POLICY_CREATE_ATLAS_OM_READ}). Kept on its own so
 * {@code CommandService} stays focused on dispatch + composition; the
 * step bodies in CommandService delegate the actual work here.
 *
 * <p>All operations are designed to be idempotent — re-running a step
 * with the same effective inputs produces no externally-visible change.
 * That's load-bearing for the replayable-actions pattern (operator can
 * re-trigger from the UI without worrying about duplicate users/policies).
 */
public final class OmAtlasProvisioning {

    private static final Logger LOG = LoggerFactory.getLogger(OmAtlasProvisioning.class);
    private static final Gson GSON = new Gson();

    /**
     * Ranger's "the user making the request" macro. Named as a policy user it matches EVERY user;
     * used as a resource value it is replaced by the requesting user, so it matches only them.
     */
    public static final String RANGER_USER_MACRO = "{USER}";

    /** The KDPS policy that lets each Trino user run queries as themselves, and only as themselves. */
    public static final String TRINO_SELF_IMPERSONATION_POLICY = "kdps - users impersonate only themselves";

    /**
     * The policy earlier KDPS versions created for self-impersonation. It granted {@link #RANGER_USER_MACRO}
     * impersonation of every Trino user ({@code trinouser=*}), i.e. anyone could act as anyone.
     */
    public static final String LEGACY_TRINO_SELF_IMPERSONATION_POLICY = "kdps - users impersonate themselves";

    /**
     * Policies asked for per call when listing a service. Kept under Ranger's default page size
     * ({@code ranger.db.maxrows.default}, 200) so a short page reliably means the last one.
     */
    private static final int POLICY_PAGE_SIZE = 100;

    /** Every list of policy items a Ranger policy carries; a policy with all of them empty grants nothing. */
    private static final String[] ALL_ITEM_KEYS = {"policyItems", "denyPolicyItems", "allowExceptions",
            "denyExceptions", "dataMaskPolicyItems", "rowFilterPolicyItems"};

    /** Holds the resolved OM federation credentials (one per release). */
    public static final class Credentials {
        public final String username;
        public final String plaintextPassword;  // populated on all paths (generated, or read back from the Secret) — used for the Atlas auth-probe
        public final String passwordSha256Hex;
        public final boolean preExisting;       // true when read from existing Secret
        Credentials(String u, String pw, String hash, boolean pre) {
            this.username = u; this.plaintextPassword = pw;
            this.passwordSha256Hex = hash; this.preExisting = pre;
        }
    }

    /**
     * Resolve (or generate) the OM federation credentials for this release.
     * The plaintext password lives ONLY in a K8s Secret in the release
     * namespace ({@code <release>-atlas-federation}); the password hash
     * lives in Ambari's {@code atlas-env} config. Re-runs reuse the existing
     * Secret so the password is stable across reapply cycles.
     *
     * @param k8s         fabric8 client
     * @param namespace   release namespace (where the K8s Secret lives)
     * @param releaseName release name (used to derive Secret + username)
     * @param explicitUsername operator-supplied username override, or null/blank for default
     */
    public static Credentials resolveOrCreate(KubernetesClient k8s,
                                              String namespace,
                                              String releaseName,
                                              String explicitUsername) {
        return resolveOrCreate(k8s, namespace, releaseName, explicitUsername, null);
    }

    /**
     * As {@link #resolveOrCreate(KubernetesClient, String, String, String)} but with an optional
     * operator-provided Secret name. When set and that Secret carries a {@code password} (and
     * optionally {@code username}), it is the SOURCE OF TRUTH — typically synced from Hashicorp
     * Vault by the external-secrets operator, or pre-created by the operator — and KDPS does NOT
     * generate a password. The same Secret is read by the OM-side connection
     * ({@code atlasFederation.existingSecret}), keeping the Atlas-side hash and the OM-side
     * plaintext password in sync. The operator's Secret is never mutated.
     */
    public static Credentials resolveOrCreate(KubernetesClient k8s,
                                              String namespace,
                                              String releaseName,
                                              String explicitUsername,
                                              String operatorSecretName) {
        String secretName = releaseName + "-atlas-federation";
        String defaultUser = (explicitUsername != null && !explicitUsername.isBlank())
                ? explicitUsername.trim() : "openmetadata-federation";

        if (operatorSecretName != null && !operatorSecretName.isBlank()
                && !operatorSecretName.trim().equals(secretName)) {
            Secret op = k8s.secrets().inNamespace(namespace).withName(operatorSecretName.trim()).get();
            Map<String, String> od = (op != null) ? op.getData() : null;
            if (od != null && od.containsKey("password")) {
                String user = od.containsKey("username")
                        ? new String(Base64.getDecoder().decode(od.get("username")), StandardCharsets.UTF_8)
                        : defaultUser;
                String pw = new String(Base64.getDecoder().decode(od.get("password")), StandardCharsets.UTF_8);
                LOG.info("OmAtlasProvisioning: using operator-provided Secret '{}/{}' (e.g. Vault via external-secrets) "
                                + "for OM Atlas federation user '{}' — KDPS will not generate a password.",
                        namespace, operatorSecretName.trim(), user);
                return new Credentials(user, pw, sha256Hex(pw), true);
            }
            LOG.warn("OmAtlasProvisioning: operator secret '{}/{}' missing or has no 'password' key — falling back to the "
                    + "KDPS-managed Secret '{}'.", namespace, operatorSecretName.trim(), secretName);
        }

        Secret existing = k8s.secrets().inNamespace(namespace).withName(secretName).get();
        if (existing != null && existing.getData() != null) {
            Map<String, String> data = existing.getData();
            byte[] userBytes = data.containsKey("username")
                    ? Base64.getDecoder().decode(data.get("username")) : defaultUser.getBytes(StandardCharsets.UTF_8);
            byte[] pwBytes = data.containsKey("password")
                    ? Base64.getDecoder().decode(data.get("password")) : null;
            if (pwBytes != null) {
                String user = new String(userBytes, StandardCharsets.UTF_8);
                String pw = new String(pwBytes, StandardCharsets.UTF_8);
                String hash = sha256Hex(pw);
                LOG.info("OmAtlasProvisioning: reusing existing Secret '{}/{}' for OM Atlas federation user '{}'",
                        namespace, secretName, user);
                return new Credentials(user, pw, hash, true);
            }
        }

        // Generate a fresh password (32 base64url chars; ~192 bits entropy)
        byte[] rand = new byte[24];
        new SecureRandom().nextBytes(rand);
        String pw = Base64.getUrlEncoder().withoutPadding().encodeToString(rand);
        String hash = sha256Hex(pw);

        Map<String, String> secretData = new LinkedHashMap<>();
        secretData.put("username", Base64.getEncoder().encodeToString(defaultUser.getBytes(StandardCharsets.UTF_8)));
        secretData.put("password", Base64.getEncoder().encodeToString(pw.getBytes(StandardCharsets.UTF_8)));
        secretData.put("password_sha256", Base64.getEncoder().encodeToString(hash.getBytes(StandardCharsets.UTF_8)));

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app.kubernetes.io/managed-by", "ambari-k8s-view");
        labels.put("app.kubernetes.io/component", "atlas-federation");
        labels.put("app.kubernetes.io/instance", releaseName);

        Secret built = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName)
                .withNamespace(namespace)
                .withLabels(labels)
                .endMetadata()
                .withType("Opaque")
                .withData(secretData)
                .build();
        k8s.secrets().inNamespace(namespace).resource(built).serverSideApply();
        LOG.info("OmAtlasProvisioning: created fresh Secret '{}/{}' with new OM Atlas federation user '{}'",
                namespace, secretName, defaultUser);
        return new Credentials(defaultUser, pw, hash, false);
    }

    /**
     * SHA-256 hex digest in the same format Atlas's
     * {@code users-credentials.properties} expects (lowercase hex, no prefix).
     */
    public static String sha256Hex(String plaintext) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Writes the three {@code openmetadata.federation.*} keys into Ambari's
     * {@code atlas-env} config. Returns true when the config actually changed
     * (signals that an Atlas restart is now required). Idempotent.
     */
    public static boolean writeAtlasEnvConfig(AmbariActionClient ambari,
                                              String cluster,
                                              String username,
                                              String passwordSha256Hex,
                                              String role) throws Exception {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("openmetadata.federation.username", username);
        props.put("openmetadata.federation.password_hash", passwordSha256Hex);
        props.put("openmetadata.federation.role", role != null && !role.isBlank() ? role : "ROLE_USER");
        return ambari.updateClusterConfig(cluster, "atlas-env", props,
                "kdps-om-atlas-federation-" + System.currentTimeMillis());
    }

    /**
     * Idempotently ensure Atlas accepts the file-based (basic-auth) user store the OM federation
     * user lives in: asserts {@code application-properties: atlas.authentication.method.file=true}
     * and sets the user-store filename ONLY if currently unset (so an operator's custom filename is
     * preserved). {@code updateClusterConfig} merges + diffs, so this is a true no-op (no new config
     * version, no Atlas restart) when file auth is already enabled.
     *
     * <p>Non-breaking by design: Atlas runs file/kerberos/ldap auth simultaneously, so enabling file
     * auth never disables the others, and KDPS only ever asserts {@code true} (never flips to false).
     * OM's Atlas connector only speaks HTTP Basic, so file auth is a hard requirement for federation.
     *
     * @return {@code true} iff the config actually changed (i.e. an Atlas restart is now warranted).
     */
    public static boolean ensureFileAuthEnabled(AmbariActionClient ambari, String cluster) throws Exception {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("atlas.authentication.method.file", "true");
        String curFilename = ambari.getDesiredConfigProperty(
                cluster, "application-properties", "atlas.authentication.method.file.filename");
        if (curFilename == null || curFilename.isBlank()) {
            props.put("atlas.authentication.method.file.filename", "{{conf_dir}}/users-credentials.properties");
        }
        boolean changed = ambari.updateClusterConfig(cluster, "application-properties", props,
                "kdps-om-atlas-fileauth");
        LOG.info("OmAtlasProvisioning: ensured Atlas file-based auth is enabled for OM federation "
                + "(method.file=true; existing filename preserved); configChanged={}", changed);
        return changed;
    }

    /** Result of a live federation-user auth probe against Atlas. */
    public enum AtlasAuthProbe {
        /** HTTP 200/403 — the user authenticates (it IS in Atlas's running user store; 403 = exists, not authorized). */
        LOADED,
        /** HTTP 401 — the user is NOT in the running store (given KDPS keeps hash==Secret, "not materialized yet"). */
        NOT_LOADED,
        /** Could not determine (no URL/creds, network error, 5xx) — caller should fall back to the config heuristic. */
        UNKNOWN
    }

    /**
     * Resolve the Atlas REST base URL from cluster config: prefer {@code atlas.rest.address}
     * (the canonical advertised URL), else build {@code scheme://<ATLAS_SERVER host>:<port>}
     * from {@code atlas.enableTLS} + the http/https port. Returns {@code null} if unresolvable.
     */
    public static String resolveAtlasRestUrl(AmbariActionClient ambari, String cluster) {
        try {
            String addr = ambari.getDesiredConfigProperty(cluster, "application-properties", "atlas.rest.address");
            if (addr != null && !addr.isBlank()) {
                String first = addr.split(",")[0].trim();
                if (!first.isEmpty()) return first.replaceAll("/+$", "");
            }
            java.util.List<String> hosts = ambari.getComponentHosts(cluster, "ATLAS", "ATLAS_SERVER");
            if (hosts != null && !hosts.isEmpty()) {
                String tls = ambari.getDesiredConfigProperty(cluster, "application-properties", "atlas.enableTLS");
                boolean https = "true".equalsIgnoreCase(tls != null ? tls.trim() : "");
                String port = ambari.getDesiredConfigProperty(cluster, "application-properties",
                        https ? "atlas.server.https.port" : "atlas.server.http.port");
                if (port == null || port.isBlank()) port = https ? "21443" : "21000";
                return (https ? "https://" : "http://") + hosts.get(0) + ":" + port.trim();
            }
        } catch (Exception e) {
            LOG.warn("OmAtlasProvisioning: could not resolve Atlas REST URL for cluster '{}' ({})", cluster, e.toString());
        }
        return null;
    }

    /**
     * Probe whether the OM federation user is ACTUALLY loaded in Atlas's running user store —
     * the decisive signal for "does Atlas need a restart".
     *
     * <p>Atlas's {@code users-credentials.properties} is a DERIVED file the ODP {@code metadata.py}
     * hook regenerates ONLY on Atlas START/RESTART — never on a plain config push. Ambari's
     * {@code stale_configs} flag tracks {@code atlas-env} (the managed config), NOT that derived file,
     * so it can read {@code false} even when the federation user was never materialized into the live
     * store. A direct auth probe is the only reliable check.
     *
     * <p>Given KDPS keeps the {@code atlas-env} password hash equal to the release Secret password
     * (invariant), the outcome is unambiguous: 200/403 → {@link AtlasAuthProbe#LOADED};
     * 401 → {@link AtlasAuthProbe#NOT_LOADED} (a restart materializes it); anything else / error →
     * {@link AtlasAuthProbe#UNKNOWN} (fail-safe — caller keeps current behaviour, no forced restart).
     * TLS trust matches the existing Ranger calls ({@link #configureSsl}, trust-all in dev).
     */
    public static AtlasAuthProbe probeFederationUser(String atlasRestUrl, String username, String plaintextPassword) {
        if (atlasRestUrl == null || atlasRestUrl.isBlank() || username == null || username.isBlank()
                || plaintextPassword == null || plaintextPassword.isEmpty()) {
            return AtlasAuthProbe.UNKNOWN;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(atlasRestUrl.trim().replaceAll("/+$", "") + "/api/atlas/admin/version");
            conn = (HttpURLConnection) url.openConnection();
            configureSsl(conn);                 // trust-all (dev) + no-op on plain http
            conn.setConnectTimeout(5_000);      // tighter than configureSsl's defaults — a probe must not stall a deploy
            conn.setReadTimeout(8_000);
            conn.setRequestMethod("GET");
            String basic = Base64.getEncoder().encodeToString(
                    (username + ":" + plaintextPassword).getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + basic);
            int code = conn.getResponseCode();
            if (code == 200 || code == 403) return AtlasAuthProbe.LOADED;
            if (code == 401) return AtlasAuthProbe.NOT_LOADED;
            LOG.warn("OmAtlasProvisioning: Atlas federation auth-probe to {} returned HTTP {} — treating as UNKNOWN.", url, code);
            return AtlasAuthProbe.UNKNOWN;
        } catch (Exception e) {
            LOG.warn("OmAtlasProvisioning: Atlas federation auth-probe to {} failed ({}) — UNKNOWN (no forced restart).",
                    atlasRestUrl, e.toString());
            return AtlasAuthProbe.UNKNOWN;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Ranger Admin REST policy create + propagation poll. Idempotent.
     *
     * <p>Atlas's Ranger service-def validates resource keys against a fixed list
     * of allowed sets. A single policy can NOT mix resources from different
     * sets (e.g. {@code entity-type+type-category}). To grant the OM federation
     * user both {@code entity-read} and {@code type-read} we create TWO
     * separate policies:
     * <ul>
     *   <li>{@code <name>-entities} — resources {@code (entity-type, entity-classification, entity)},
     *       access {@code entity-read}</li>
     *   <li>{@code <name>-types} — resources {@code (type-category, type)},
     *       access {@code type-read}</li>
     * </ul>
     *
     * <p>Each policy go through: 1) GET-by-name (skip if exists), 2) POST,
     * 3) GET-by-id poll until readable (proxy for "persisted + indexed").
     *
     * @return the entity-policy id (the most important of the two; types
     *         policy id is logged separately)
     */
    public static long createOrFindAtlasReadPolicy(String rangerAdminUrl,
                                                   String rangerUser,
                                                   String rangerPassword,
                                                   String atlasServiceName,
                                                   String policyName,
                                                   String omPrincipal,
                                                   boolean isKerberos,
                                                   long timeoutMs) throws Exception {
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                (rangerUser + ":" + rangerPassword).getBytes(StandardCharsets.UTF_8));

        // Ranger Admin enforces that any user/group referenced in a policy MUST
        // exist in its own xuser table — Atlas-side user provisioning is a
        // separate identity store. Register first (idempotent), then grant.
        ensureRangerUserExists(rangerAdminUrl, basic, omPrincipal);

        // Per-policy timeout: split the budget so a slow first poll doesn't
        // starve the second create.
        long perPolicyMs = Math.max(5_000L, timeoutMs / 2);

        long entityPolicyId = createOrFindOnePolicy(rangerAdminUrl, basic, atlasServiceName,
                policyName + "-entities", omPrincipal, isKerberos,
                AtlasResourceSet.ENTITIES, perPolicyMs);
        long typePolicyId = createOrFindOnePolicy(rangerAdminUrl, basic, atlasServiceName,
                policyName + "-types", omPrincipal, isKerberos,
                AtlasResourceSet.TYPES, perPolicyMs);
        LOG.info("OmAtlasProvisioning: Atlas read policies provisioned — entities={} types={}",
                entityPolicyId, typePolicyId);
        return entityPolicyId;
    }

    /** Which of the Atlas-Ranger servicedef resource sets a policy targets. */
    private enum AtlasResourceSet {
        ENTITIES("entity-type", "entity-classification", "entity"),  // → entity-read
        TYPES("type-category", "type");                              // → type-read
        final String[] keys;
        AtlasResourceSet(String... keys) { this.keys = keys; }
        String accessType() {
            return this == ENTITIES ? "entity-read" : "type-read";
        }
        /**
         * Name of the Atlas-Ranger plugin's auto-created default policy for this
         * resource set. Ranger refuses two policies with identical resources
         * (code 3010), so we GRANT-by-appending a policyItem to the default
         * policy instead of creating a new one with the same resource scope.
         */
        String defaultPolicyName() {
            return this == ENTITIES
                    ? "all - entity-type, entity-classification, entity"
                    : "all - type-category, type";
        }
    }

    /**
     * Grant the OM federation principal read access for one Atlas resource set.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Look up the Atlas-Ranger plugin's auto-created DEFAULT policy for
     *       this resource set (e.g. "all - entity-type, entity-classification, entity").
     *       If found, GET its current JSON, append a new policyItem granting
     *       {@code (entity-read | type-read)} to our principal (idempotent — skip if
     *       already present), PUT the updated policy back. This avoids Ranger's
     *       "duplicate resource" rejection (code 3010) that fires when two
     *       policies share the same resource scope.</li>
     *   <li>If the default policy isn't there (rare — operator deleted it),
     *       POST a fresh policy with our canonical name as a fallback.</li>
     * </ol>
     */
    private static long createOrFindOnePolicy(String rangerAdminUrl, String basic,
                                              String atlasServiceName, String policyName,
                                              String omPrincipal, boolean isKerberos,
                                              AtlasResourceSet set, long timeoutMs) throws Exception {
        // Path A: append to the default Atlas policy (preferred — matches how
        // operators normally grant in the Ranger UI).
        Long defaultId = lookupAtlasPolicyByName(rangerAdminUrl, basic, atlasServiceName,
                set.defaultPolicyName());
        if (defaultId != null) {
            return appendPolicyItemToExisting(rangerAdminUrl, basic, atlasServiceName,
                    set.defaultPolicyName(), defaultId, omPrincipal, set, timeoutMs);
        }

        // Path B: default policy missing — fall back to creating our own.
        Long existing = lookupAtlasPolicyByName(rangerAdminUrl, basic, atlasServiceName, policyName);
        if (existing != null) {
            LOG.info("OmAtlasProvisioning: KDPS-created Ranger policy '{}' already exists (id={}) — skip create",
                    policyName, existing);
            return existing;
        }
        JsonObject policy = buildAtlasReadPolicy(atlasServiceName, policyName, omPrincipal, isKerberos, set);
        HttpURLConnection conn = (HttpURLConnection) new URL(
                rangerAdminUrl + "/service/public/v2/api/policy").openConnection();
        configureSsl(conn);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", basic);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (var os = conn.getOutputStream()) {
            os.write(GSON.toJson(policy).getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            String err = readBody(conn, true);
            throw new IllegalStateException("Ranger policy create failed: HTTP " + code + " — " + err);
        }
        JsonObject created = JsonParser.parseString(readBody(conn, false)).getAsJsonObject();
        long policyId = created.get("id").getAsLong();
        LOG.info("OmAtlasProvisioning: Ranger policy '{}' created (id={})", policyName, policyId);

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Exception lastErr = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpURLConnection get = (HttpURLConnection) new URL(
                        rangerAdminUrl + "/service/public/v2/api/policy/" + policyId).openConnection();
                configureSsl(get);
                get.setRequestProperty("Authorization", basic);
                int gc = get.getResponseCode();
                if (gc == 200) {
                    LOG.info("OmAtlasProvisioning: Ranger policy id={} verified readable", policyId);
                    return policyId;
                }
                lastErr = new IllegalStateException("policy GET returned HTTP " + gc);
            } catch (Exception e) {
                lastErr = e;
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException(
                "Ranger policy id=" + policyId + " created but did not become readable within "
                        + timeoutMs + "ms — last error: " + (lastErr == null ? "n/a" : lastErr.getMessage()));
    }

    /**
     * Ensure the OM federation principal is registered in Ranger Admin's xuser table.
     * Ranger rejects PUTs to a policy that reference an unknown user
     * ("Operation denied. User name: X specified in policy does not exist in ranger admin").
     *
     * <p>Idempotent — uses GET-by-name then POST.
     */
    private static void ensureRangerUserExists(String rangerAdminUrl, String basic,
                                                String userName) throws Exception {
        // Ranger macros ({USER}, {OWNER}) are resolved by Ranger at evaluation time; there is no
        // account to create for them.
        if (userName == null || userName.startsWith("{")) return;
        // Check existence first via the v2 xusers list filtered by name.
        HttpURLConnection check = (HttpURLConnection) new URL(
                rangerAdminUrl + "/service/xusers/users?name=" + pathSegmentEncode(userName)).openConnection();
        configureSsl(check);
        check.setRequestProperty("Authorization", basic);
        int cc = check.getResponseCode();
        if (cc == 200) {
            String body = readBody(check, false);
            // Response is { vXUsers: [ ... ] } — present if user exists
            try {
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                if (root.has("vXUsers") && root.getAsJsonArray("vXUsers").size() > 0) {
                    for (int i = 0; i < root.getAsJsonArray("vXUsers").size(); i++) {
                        JsonObject u = root.getAsJsonArray("vXUsers").get(i).getAsJsonObject();
                        if (userName.equals(u.has("name") ? u.get("name").getAsString() : null)) {
                            LOG.info("OmAtlasProvisioning: Ranger user '{}' already exists (id={}) — no-op",
                                    userName, u.has("id") ? u.get("id").getAsString() : "?");
                            return;
                        }
                    }
                }
            } catch (Exception ignore) { /* fall through to create */ }
        }

        // POST a new external user. Use a long random password — Ranger requires
        // a value but for an external user authenticated by Atlas (basic) or
        // Kerberos, this password is never used to log in to Ranger directly.
        JsonObject xuser = new JsonObject();
        xuser.addProperty("name", userName);
        xuser.addProperty("password", java.util.UUID.randomUUID().toString() + "Aa1!");
        xuser.addProperty("firstName", userName);
        xuser.addProperty("lastName", "(KDPS)");
        xuser.addProperty("emailAddress", "");
        xuser.addProperty("description", "Provisioned by Ambari KDPS for OpenMetadata Atlas federation");
        xuser.addProperty("status", 1);
        xuser.addProperty("isVisible", 1);
        xuser.addProperty("userSource", 0);
        JsonArray roles = new JsonArray(); roles.add("ROLE_USER");
        xuser.add("userRoleList", roles);

        HttpURLConnection post = (HttpURLConnection) new URL(
                rangerAdminUrl + "/service/xusers/secure/users").openConnection();
        configureSsl(post);
        post.setRequestMethod("POST");
        post.setRequestProperty("Authorization", basic);
        post.setRequestProperty("Content-Type", "application/json");
        post.setDoOutput(true);
        try (var os = post.getOutputStream()) {
            os.write(GSON.toJson(xuser).getBytes(StandardCharsets.UTF_8));
        }
        int pc = post.getResponseCode();
        if (pc >= 200 && pc < 300) {
            LOG.info("OmAtlasProvisioning: created Ranger xuser '{}'", userName);
            return;
        }
        // 400 with "already exists" is benign (raced with an external sync) — treat as success.
        String err = readBody(post, true);
        if (pc == 400 && err.toLowerCase().contains("already exists")) {
            LOG.info("OmAtlasProvisioning: Ranger xuser '{}' already exists (raced) — continuing", userName);
            return;
        }
        throw new IllegalStateException("Ranger xuser create failed for '" + userName
                + "': HTTP " + pc + " — " + err);
    }

    /**
     * Fetch the policy by id, append a new policyItem granting the OM principal
     * the resource-set's access type (idempotent — skip if already present),
     * PUT back. Returns the policy id.
     */
    private static long appendPolicyItemToExisting(String rangerAdminUrl, String basic,
                                                   String atlasServiceName, String policyName,
                                                   long policyId, String omPrincipal,
                                                   AtlasResourceSet set, long timeoutMs) throws Exception {
        // 1) GET the policy
        HttpURLConnection get = (HttpURLConnection) new URL(
                rangerAdminUrl + "/service/public/v2/api/policy/" + policyId).openConnection();
        configureSsl(get);
        get.setRequestProperty("Authorization", basic);
        int gc = get.getResponseCode();
        if (gc != 200) {
            throw new IllegalStateException("Ranger policy GET by id=" + policyId
                    + " returned HTTP " + gc + ": " + readBody(get, true));
        }
        JsonObject policy = JsonParser.parseString(readBody(get, false)).getAsJsonObject();

        // 2) Check if our principal already has the required access — idempotency
        JsonArray policyItems = policy.has("policyItems") && policy.get("policyItems").isJsonArray()
                ? policy.getAsJsonArray("policyItems")
                : new JsonArray();
        for (int i = 0; i < policyItems.size(); i++) {
            JsonObject existing = policyItems.get(i).getAsJsonObject();
            JsonArray users = existing.has("users") ? existing.getAsJsonArray("users") : new JsonArray();
            boolean hasUser = false;
            for (int j = 0; j < users.size(); j++) {
                if (omPrincipal.equals(users.get(j).getAsString())) { hasUser = true; break; }
            }
            if (!hasUser) continue;
            JsonArray accesses = existing.has("accesses") ? existing.getAsJsonArray("accesses") : new JsonArray();
            for (int k = 0; k < accesses.size(); k++) {
                JsonObject a = accesses.get(k).getAsJsonObject();
                if (set.accessType().equals(a.has("type") ? a.get("type").getAsString() : null)
                        && a.has("isAllowed") && a.get("isAllowed").getAsBoolean()) {
                    LOG.info("OmAtlasProvisioning: Atlas policy '{}' (id={}) already grants '{}' to '{}' — no-op",
                            policyName, policyId, set.accessType(), omPrincipal);
                    return policyId;
                }
            }
        }

        // 3) Append a new policyItem
        JsonObject newItem = new JsonObject();
        newItem.add("users", arrayOf(omPrincipal));
        newItem.add("groups", new JsonArray());
        newItem.add("roles", new JsonArray());
        newItem.add("accesses", arrayOfObjects(new String[]{set.accessType()}, "isAllowed", true));
        newItem.addProperty("delegateAdmin", false);
        policyItems.add(newItem);
        policy.add("policyItems", policyItems);

        // 4) PUT back
        HttpURLConnection put = (HttpURLConnection) new URL(
                rangerAdminUrl + "/service/public/v2/api/policy/" + policyId).openConnection();
        configureSsl(put);
        put.setRequestMethod("PUT");
        put.setRequestProperty("Authorization", basic);
        put.setRequestProperty("Content-Type", "application/json");
        put.setDoOutput(true);
        try (var os = put.getOutputStream()) {
            os.write(GSON.toJson(policy).getBytes(StandardCharsets.UTF_8));
        }
        int pc = put.getResponseCode();
        if (pc < 200 || pc >= 300) {
            throw new IllegalStateException("Ranger policy update failed: HTTP " + pc + " — " + readBody(put, true));
        }
        LOG.info("OmAtlasProvisioning: appended policyItem (user='{}', access='{}') to Atlas policy '{}' (id={})",
                omPrincipal, set.accessType(), policyName, policyId);

        // 5) Poll — confirm the update is queryable back.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Exception lastErr = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpURLConnection verify = (HttpURLConnection) new URL(
                        rangerAdminUrl + "/service/public/v2/api/policy/" + policyId).openConnection();
                configureSsl(verify);
                verify.setRequestProperty("Authorization", basic);
                if (verify.getResponseCode() == 200) {
                    LOG.info("OmAtlasProvisioning: Ranger policy id={} update verified readable", policyId);
                    return policyId;
                }
            } catch (Exception e) {
                lastErr = e;
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("Ranger policy id=" + policyId
                + " updated but did not become readable within " + timeoutMs
                + "ms — last error: " + (lastErr == null ? "n/a" : lastErr.getMessage()));
    }

    /**
     * EXTERNAL-context path: grant {@code omUser} the Hive {@code select} access OpenMetadata
     * metadata ingestion needs, in the given Hive Ranger service. Appends a policyItem to the
     * plugin's default "all - database, table, column" policy (avoids Ranger's duplicate-resource
     * rejection 3010); falls back to creating a dedicated policy if the default is missing.
     * Idempotent. (MANAGED contexts grant via the Ambari server instead.)
     */
    public static long createOrFindHiveReadPolicy(String rangerAdminUrl, String rangerUser,
                                                  String rangerPassword, String hiveServiceName,
                                                  String policyName, String omUser,
                                                  long timeoutMs) throws Exception {
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                (rangerUser + ":" + rangerPassword).getBytes(StandardCharsets.UTF_8));
        ensureRangerUserExists(rangerAdminUrl, basic, omUser);

        final String DEFAULT_POLICY = "all - database, table, column";
        Long defaultId = lookupAtlasPolicyByName(rangerAdminUrl, basic, hiveServiceName, DEFAULT_POLICY);
        if (defaultId != null) {
            return appendHiveSelectToPolicy(rangerAdminUrl, basic, DEFAULT_POLICY, defaultId, omUser, timeoutMs);
        }

        // Default policy missing (operator deleted it) — create a dedicated one.
        Long existing = lookupAtlasPolicyByName(rangerAdminUrl, basic, hiveServiceName, policyName);
        if (existing != null) return existing;

        JsonObject p = new JsonObject();
        p.addProperty("service", hiveServiceName);
        p.addProperty("name", policyName);
        p.addProperty("description", "KDPS-managed: read access for the OpenMetadata Hive metadata ingestion.");
        p.addProperty("isAuditEnabled", true);
        p.addProperty("isEnabled", true);
        p.addProperty("policyType", 0);
        p.addProperty("policyPriority", 0);
        JsonObject resources = new JsonObject();
        for (String t : new String[]{"database", "table", "column"}) {
            JsonObject r = new JsonObject();
            r.add("values", arrayOf("*"));
            r.addProperty("isExcludes", false);
            r.addProperty("isRecursive", false);
            resources.add(t, r);
        }
        p.add("resources", resources);
        JsonObject item = new JsonObject();
        item.add("users", arrayOf(omUser));
        item.add("groups", new JsonArray());
        item.add("roles", new JsonArray());
        item.add("accesses", arrayOfObjects(new String[]{"select"}, "isAllowed", true));
        item.addProperty("delegateAdmin", false);
        JsonArray items = new JsonArray();
        items.add(item);
        p.add("policyItems", items);

        HttpURLConnection conn = (HttpURLConnection) new URL(
                rangerAdminUrl + "/service/public/v2/api/policy").openConnection();
        configureSsl(conn);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", basic);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (var os = conn.getOutputStream()) {
            os.write(GSON.toJson(p).getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Ranger Hive policy create failed: HTTP " + code + " — " + readBody(conn, true));
        }
        long id = JsonParser.parseString(readBody(conn, false)).getAsJsonObject().get("id").getAsLong();
        LOG.info("OmAtlasProvisioning: Ranger Hive read policy '{}' created (id={})", policyName, id);
        return id;
    }

    /**
     * EXTERNAL-context path: grant {@code trinoUser} the Trino {@code impersonate} access on the
     * {@code trinouser=*} resource in the given Trino Ranger service, so a Superset-style Trino
     * database connection (authenticating as {@code trinoUser}) can run every user's queries as
     * that user (the Trino session user). Create-or-find a dedicated policy; idempotent — if the
     * policy already exists its id is returned unchanged (Ranger append semantics are unnecessary
     * here because the resource set is a single {@code trinouser=*}). (MANAGED contexts grant via
     * the Ambari server instead.)
     */
    public static long createOrFindTrinoImpersonatePolicy(String rangerAdminUrl, String rangerUser,
                                                          String rangerPassword, String trinoServiceName,
                                                          String policyName, String trinoUser,
                                                          long timeoutMs) throws Exception {
        // This grant covers every Trino user; given to a Ranger macro such as {USER}, which stands for
        // every user, it would let anyone act as anyone.
        if (trinoUser == null || trinoUser.isBlank() || trinoUser.startsWith("{")) {
            throw new IllegalArgumentException("Trino impersonation of every user can only be granted to a named "
                    + "service identity, not '" + trinoUser + "'");
        }
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                (rangerUser + ":" + rangerPassword).getBytes(StandardCharsets.UTF_8));
        ensureRangerUserExists(rangerAdminUrl, basic, trinoUser);
        // Ranger allows one policy per resource set, and a new Trino repository already owns
        // trinouser=* through its default 'all - trinouser' policy. Append to that one when present.
        final String DEFAULT_TRINOUSER_POLICY = "all - trinouser";
        Long defaultId = lookupAtlasPolicyByName(rangerAdminUrl, basic, trinoServiceName, DEFAULT_TRINOUSER_POLICY);
        if (defaultId != null) {
            return appendAccessToPolicy(rangerAdminUrl, basic, DEFAULT_TRINOUSER_POLICY, defaultId, trinoUser, "impersonate", timeoutMs);
        }

        Long existing = lookupAtlasPolicyByName(rangerAdminUrl, basic, trinoServiceName, policyName);
        if (existing != null) {
            LOG.info("OmAtlasProvisioning: Ranger Trino impersonate policy '{}' already exists (id={}) — left unchanged",
                    policyName, existing);
            return existing;
        }

        JsonObject p = new JsonObject();
        p.addProperty("service", trinoServiceName);
        p.addProperty("name", policyName);
        p.addProperty("description", "KDPS-managed: allow the Superset service user to impersonate end users on Trino.");
        p.addProperty("isAuditEnabled", true);
        p.addProperty("isEnabled", true);
        p.addProperty("policyType", 0);
        p.addProperty("policyPriority", 0);
        JsonObject resources = new JsonObject();
        JsonObject r = new JsonObject();
        r.add("values", arrayOf("*"));
        r.addProperty("isExcludes", false);
        r.addProperty("isRecursive", false);
        resources.add("trinouser", r);
        p.add("resources", resources);
        JsonObject item = new JsonObject();
        item.add("users", arrayOf(trinoUser));
        item.add("groups", new JsonArray());
        item.add("roles", new JsonArray());
        item.add("accesses", arrayOfObjects(new String[]{"impersonate"}, "isAllowed", true));
        item.addProperty("delegateAdmin", false);
        JsonArray items = new JsonArray();
        items.add(item);
        p.add("policyItems", items);

        HttpURLConnection conn = (HttpURLConnection) new URL(
                rangerAdminUrl + "/service/public/v2/api/policy").openConnection();
        configureSsl(conn);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", basic);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (var os = conn.getOutputStream()) {
            os.write(GSON.toJson(p).getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Ranger Trino impersonate policy create failed: HTTP " + code + " — " + readBody(conn, true));
        }
        long id = JsonParser.parseString(readBody(conn, false)).getAsJsonObject().get("id").getAsLong();
        LOG.info("OmAtlasProvisioning: Ranger Trino impersonate policy '{}' created (id={})", policyName, id);
        return id;
    }

    /**
     * Takes back the rule earlier KDPS versions added to a Trino repository: {@link #RANGER_USER_MACRO}
     * granted {@code impersonate} on every Trino user ({@code trinouser=*}), which let any authenticated
     * user run queries as any other user. The grant is removed from every access policy over exactly
     * {@code trinouser=*} — the repository's default {@code all - trinouser} policy keeps its other
     * items, such as the service principals that legitimately impersonate — and the
     * {@link #LEGACY_TRINO_SELF_IMPERSONATION_POLICY} is deleted if that leaves it granting nothing.
     *
     * @return the number of policies changed; 0 on a repository that never carried the rule
     */
    public static int revokeTrinoImpersonationOfEveryone(String rangerAdminUrl, String rangerUser,
                                                         String rangerPassword, String trinoServiceName) throws Exception {
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                (rangerUser + ":" + rangerPassword).getBytes(StandardCharsets.UTF_8));
        return revokeAccessOnScope(rangerAdminUrl, basic, trinoServiceName, Map.of("trinouser", List.of("*")),
                RANGER_USER_MACRO, "impersonate", LEGACY_TRINO_SELF_IMPERSONATION_POLICY);
    }

    /**
     * Lets each Trino user act as themselves and nobody else: {@link #RANGER_USER_MACRO} is granted
     * {@code impersonate} on {@code trinouser={USER}}, which Ranger resolves to the requesting user, so
     * a Trino session whose user is the authenticated principal is authorized while a request to act
     * as someone else is not. Table access stays governed by the operator's policies.
     *
     * <p>Idempotent: an existing {@link #TRINO_SELF_IMPERSONATION_POLICY} only gains the grant if it
     * lacks it, and an operator policy that already owns {@code trinouser={USER}} receives the grant
     * instead of a duplicate (Ranger allows one policy per resource set). A policy of that name over
     * any other Trino users is refused rather than widened.
     */
    public static long ensureTrinoSelfImpersonationPolicy(String rangerAdminUrl, String rangerUser,
                                                         String rangerPassword, String trinoServiceName,
                                                         long timeoutMs) throws Exception {
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                (rangerUser + ":" + rangerPassword).getBytes(StandardCharsets.UTF_8));
        Map<String, List<String>> scope = Map.of("trinouser", List.of(RANGER_USER_MACRO));

        Long existing = lookupAtlasPolicyByName(rangerAdminUrl, basic, trinoServiceName, TRINO_SELF_IMPERSONATION_POLICY);
        if (existing != null) {
            RangerAnswer current = rangerCall("GET", rangerAdminUrl + "/service/public/v2/api/policy/" + existing, basic, null);
            if (current.code() != 200) {
                throw new IllegalStateException("Ranger policy GET id=" + existing + " failed: HTTP " + current.code()
                        + " — " + current.body());
            }
            if (!coversExactly(JsonParser.parseString(current.body()).getAsJsonObject(), scope)) {
                throw new IllegalStateException("Ranger policy '" + TRINO_SELF_IMPERSONATION_POLICY + "' on '"
                        + trinoServiceName + "' covers other Trino users than each user themselves; it was left unchanged");
            }
            return appendAccessToPolicy(rangerAdminUrl, basic, TRINO_SELF_IMPERSONATION_POLICY, existing,
                    RANGER_USER_MACRO, "impersonate", timeoutMs);
        }

        JsonObject p = new JsonObject();
        p.addProperty("service", trinoServiceName);
        p.addProperty("name", TRINO_SELF_IMPERSONATION_POLICY);
        p.addProperty("description", "KDPS-managed: lets every authenticated user run Trino queries as themselves, "
                + "and only as themselves.");
        p.addProperty("isAuditEnabled", true);
        p.addProperty("isEnabled", true);
        p.addProperty("policyType", 0);
        p.addProperty("policyPriority", 0);
        JsonObject resources = new JsonObject();
        JsonObject r = new JsonObject();
        r.add("values", arrayOf(RANGER_USER_MACRO));
        r.addProperty("isExcludes", false);
        r.addProperty("isRecursive", false);
        resources.add("trinouser", r);
        p.add("resources", resources);
        JsonObject item = new JsonObject();
        item.add("users", arrayOf(RANGER_USER_MACRO));
        item.add("groups", new JsonArray());
        item.add("roles", new JsonArray());
        item.add("accesses", arrayOfObjects(new String[]{"impersonate"}, "isAllowed", true));
        item.addProperty("delegateAdmin", false);
        JsonArray items = new JsonArray();
        items.add(item);
        p.add("policyItems", items);

        RangerAnswer created = rangerCall("POST", rangerAdminUrl + "/service/public/v2/api/policy", basic, p);
        if (created.code() >= 200 && created.code() < 300) {
            long id = JsonParser.parseString(created.body()).getAsJsonObject().get("id").getAsLong();
            LOG.info("OmAtlasProvisioning: Ranger Trino self-impersonation policy '{}' created on '{}' (id={})",
                    TRINO_SELF_IMPERSONATION_POLICY, trinoServiceName, id);
            return id;
        }
        // Ranger refuses a second policy over the same resources (error 3010) and names the one that
        // owns them; that policy covers exactly trinouser={USER}, so the grant goes there.
        java.util.regex.Matcher owner = java.util.regex.Pattern.compile("policy-name=\\[(.*?)\\]").matcher(created.body());
        if (created.code() == 400 && owner.find()) {
            Long ownerId = lookupAtlasPolicyByName(rangerAdminUrl, basic, trinoServiceName, owner.group(1));
            if (ownerId != null) {
                return appendAccessToPolicy(rangerAdminUrl, basic, owner.group(1), ownerId,
                        RANGER_USER_MACRO, "impersonate", timeoutMs);
            }
        }
        throw new IllegalStateException("Ranger Trino self-impersonation policy create failed: HTTP " + created.code()
                + " — " + created.body());
    }

    /**
     * Removes {@code accessType} from {@code user} in every access policy of the service whose
     * resources are exactly {@code scope}, and returns how many policies changed. A policy named
     * {@code ownedPolicyName} that grants nothing afterwards is deleted rather than left empty.
     */
    private static int revokeAccessOnScope(String rangerAdminUrl, String basic, String serviceName,
                                           Map<String, List<String>> scope, String user, String accessType,
                                           String ownedPolicyName) throws Exception {
        int changed = 0;
        for (JsonObject policy : listServicePolicies(rangerAdminUrl, basic, serviceName)) {
            if (!isAccessPolicy(policy) || !coversExactly(policy, scope)) continue;
            JsonArray items = policy.has("policyItems") && policy.get("policyItems").isJsonArray()
                    ? policy.getAsJsonArray("policyItems") : new JsonArray();
            JsonArray kept = withoutAccess(items, user, Set.of(accessType));
            if (kept == null) continue;
            policy.add("policyItems", kept);
            long policyId = policy.get("id").getAsLong();
            String policyName = policy.has("name") ? policy.get("name").getAsString() : "";
            String url = rangerAdminUrl + "/service/public/v2/api/policy/" + policyId;
            if (policyName.equals(ownedPolicyName) && grantsNothing(policy)) {
                RangerAnswer deleted = rangerCall("DELETE", url, basic, null);
                if ((deleted.code() < 200 || deleted.code() >= 300) && deleted.code() != 404) {
                    throw new IllegalStateException("Ranger policy delete (id=" + policyId + ") failed: HTTP "
                            + deleted.code() + " — " + deleted.body());
                }
                LOG.info("OmAtlasProvisioning: deleted Ranger policy '{}' (id={}) on '{}': it granted nothing once '{}' of '{}' was removed",
                        policyName, policyId, serviceName, accessType, user);
            } else {
                RangerAnswer updated = rangerCall("PUT", url, basic, policy);
                if (updated.code() < 200 || updated.code() >= 300) {
                    throw new IllegalStateException("Ranger policy update (id=" + policyId + ") failed: HTTP "
                            + updated.code() + " — " + updated.body());
                }
                LOG.info("OmAtlasProvisioning: removed '{}' of '{}' from Ranger policy '{}' (id={}) on '{}'",
                        accessType, user, policyName, policyId, serviceName);
            }
            changed++;
        }
        return changed;
    }

    /**
     * Every policy of the service, read page by page. Pages are merged by policy id, so a Ranger that
     * ignores the paging parameters and repeats a page still ends the listing.
     */
    private static List<JsonObject> listServicePolicies(String rangerAdminUrl, String basic, String serviceName) throws Exception {
        Map<Long, JsonObject> byId = new LinkedHashMap<>();
        for (int start = 0; ; start += POLICY_PAGE_SIZE) {
            RangerAnswer page = rangerCall("GET", rangerAdminUrl + "/service/public/v2/api/service/"
                    + pathSegmentEncode(serviceName) + "/policy?startIndex=" + start + "&pageSize=" + POLICY_PAGE_SIZE,
                    basic, null);
            if (page.code() != 200) {
                throw new IllegalStateException("Listing the policies of Ranger service '" + serviceName
                        + "' returned HTTP " + page.code() + ": " + page.body());
            }
            JsonArray policies = JsonParser.parseString(page.body()).getAsJsonArray();
            int before = byId.size();
            for (int i = 0; i < policies.size(); i++) {
                JsonObject policy = policies.get(i).getAsJsonObject();
                if (policy.has("id")) byId.putIfAbsent(policy.get("id").getAsLong(), policy);
            }
            if (policies.size() < POLICY_PAGE_SIZE || byId.size() == before) {
                return new ArrayList<>(byId.values());
            }
        }
    }

    /** True for an access policy (policyType 0 or absent), the only kind a revoke edits. */
    static boolean isAccessPolicy(JsonObject policy) {
        return !policy.has("policyType") || policy.get("policyType").isJsonNull()
                || policy.get("policyType").getAsInt() == 0;
    }

    /**
     * True when the policy's resources are exactly {@code scope}: the same resource names, each with
     * the same set of values and no exclusion. A policy over a narrower or wider set is left alone.
     */
    static boolean coversExactly(JsonObject policy, Map<String, List<String>> scope) {
        if (!policy.has("resources") || !policy.get("resources").isJsonObject()) return false;
        JsonObject resources = policy.getAsJsonObject("resources");
        if (!resources.keySet().equals(scope.keySet())) return false;
        for (Map.Entry<String, List<String>> e : scope.entrySet()) {
            if (!resources.get(e.getKey()).isJsonObject()) return false;
            JsonObject resource = resources.getAsJsonObject(e.getKey());
            if (resource.has("isExcludes") && resource.get("isExcludes").getAsBoolean()) return false;
            if (!stringSet(resource.get("values")).equals(new LinkedHashSet<>(e.getValue()))) return false;
        }
        return true;
    }

    /**
     * The policy items once {@code revoked} accesses are taken from {@code user}, or null when no item
     * gave that user any of them. An item shared with other principals keeps them with everything it
     * had; the user keeps, in an item of their own, only the accesses not revoked. An item left with
     * no principal or no access is dropped.
     */
    static JsonArray withoutAccess(JsonArray items, String user, Set<String> revoked) {
        JsonArray out = new JsonArray();
        boolean changed = false;
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.get(i).getAsJsonObject();
            Set<String> users = stringSet(item.get("users"));
            JsonArray accesses = item.has("accesses") && item.get("accesses").isJsonArray()
                    ? item.getAsJsonArray("accesses") : new JsonArray();
            JsonArray rest = new JsonArray();
            boolean grantsRevoked = false;
            for (int k = 0; k < accesses.size(); k++) {
                JsonObject access = accesses.get(k).getAsJsonObject();
                if (revoked.contains(access.has("type") ? access.get("type").getAsString() : null)) {
                    grantsRevoked = true;
                } else {
                    rest.add(access);
                }
            }
            if (!users.contains(user) || !grantsRevoked) {
                out.add(item);
                continue;
            }
            changed = true;

            Set<String> otherUsers = new LinkedHashSet<>(users);
            otherUsers.remove(user);
            if (!otherUsers.isEmpty() || !stringSet(item.get("groups")).isEmpty() || !stringSet(item.get("roles")).isEmpty()) {
                JsonObject others = item.deepCopy();
                others.add("users", arrayOf(otherUsers.toArray(new String[0])));
                out.add(others);
            }
            if (rest.size() > 0) {
                JsonObject theirs = item.deepCopy();
                theirs.add("users", arrayOf(user));
                theirs.add("groups", new JsonArray());
                theirs.add("roles", new JsonArray());
                theirs.add("accesses", rest);
                out.add(theirs);
            }
        }
        return changed ? out : null;
    }

    /** True when the policy carries no item of any kind, i.e. grants and denies nothing. */
    static boolean grantsNothing(JsonObject policy) {
        for (String key : ALL_ITEM_KEYS) {
            if (policy.has(key) && policy.get(key).isJsonArray() && policy.getAsJsonArray(key).size() > 0) return false;
        }
        return true;
    }

    private static Set<String> stringSet(com.google.gson.JsonElement array) {
        Set<String> out = new LinkedHashSet<>();
        if (array != null && array.isJsonArray()) {
            JsonArray a = array.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                if (!a.get(i).isJsonNull()) out.add(a.get(i).getAsString());
            }
        }
        return out;
    }

    /** A Ranger Admin REST answer: the status and the body (the error body on a failure). */
    private record RangerAnswer(int code, String body) {}

    private static RangerAnswer rangerCall(String method, String url, String basic, JsonObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        configureSsl(conn);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", basic);
        conn.setRequestProperty("Accept", "application/json");
        if (body != null) {
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (var os = conn.getOutputStream()) {
                os.write(GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        return new RangerAnswer(code, readBody(conn, code < 200 || code >= 300));
    }

    /**
     * Creates, or finds, a policy granting {@code accessTypes} to one user on an arbitrary resource
     * scope of any Ranger service. The Trino and Hive helpers above each speak one service's
     * resource shape; this one takes the scope as given, so a caller that knows its service
     * definition — Polaris, say, whose hierarchy is {@code root → catalog → namespace → table} —
     * can grant at whichever level it needs without a helper per service.
     *
     * <p>Idempotent by policy name: an existing policy of that name is left exactly as it is and
     * its id returned, so re-running an install never edits a policy an operator has since adjusted.
     *
     * @param resources   resource level → values, e.g. {@code {"root": ["*"], "catalog": ["c1"]}};
     *                    Ranger validates these against the service definition and rejects unknown
     *                    levels, so a wrong name fails the call rather than granting nothing
     * @param accessTypes access type names as the service definition spells them
     */
        public static long createOrFindPolicy(String rangerAdminUrl, String rangerUser, String rangerPassword,
                                          String serviceName, String policyName, String description,
                                          java.util.Map<String, java.util.List<String>> resources,
                                          java.util.List<String> accessTypes, String grantee,
                                          long timeoutMs) throws Exception {
        return createOrFindPolicy(rangerAdminUrl, rangerUser, rangerPassword, serviceName, policyName, description,
                resources, accessTypes, grantee, null, 0, null, null, null, null, timeoutMs);
    }

    /**
     * {@link #createOrFindPolicy} with the full policy shape: {@code policyType} 0 = access, 1 = data
     * masking ({@code maskType}, optional {@code maskValueExpr}/{@code maskConditionExpr}), 2 = row
     * filtering ({@code rowFilterExpr}); {@code groups} may replace or complement the user. On a tag
     * service the component-prefixed spellings apply ({@code trino:select}, {@code trino:MASK_HASH}).
     */
    public static long createOrFindPolicy(String rangerAdminUrl, String rangerUser, String rangerPassword,
                                          String serviceName, String policyName, String description,
                                          java.util.Map<String, java.util.List<String>> resources,
                                          java.util.List<String> accessTypes, String grantee,
                                          java.util.List<String> groups, int policyType, String maskType,
                                          String maskValueExpr, String maskConditionExpr, String rowFilterExpr,
                                          long timeoutMs) throws Exception {
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                (rangerUser + ":" + rangerPassword).getBytes(StandardCharsets.UTF_8));
                if (grantee != null && !grantee.isBlank()) {
            ensureRangerUserExists(rangerAdminUrl, basic, grantee);
        }
        if (groups != null) {
            for (String g : groups) if (g != null && !g.isBlank()) ensureRangerGroupExists(rangerAdminUrl, basic, g.trim());
        }

        Long existing = lookupAtlasPolicyByName(rangerAdminUrl, basic, serviceName, policyName);
        if (existing != null) {
            LOG.info("OmAtlasProvisioning: Ranger policy '{}' on service '{}' already exists (id={}) — left unchanged",
                    policyName, serviceName, existing);
            return existing;
        }

        JsonObject p = new JsonObject();
        p.addProperty("service", serviceName);
        p.addProperty("name", policyName);
        p.addProperty("description", description);
                p.addProperty("isAuditEnabled", true);
        p.addProperty("isEnabled", true);
        p.addProperty("policyType", policyType);
        p.addProperty("policyPriority", 0);
        JsonObject resourceObj = new JsonObject();
        for (java.util.Map.Entry<String, java.util.List<String>> e : resources.entrySet()) {
            JsonObject r = new JsonObject();
            JsonArray values = new JsonArray();
            for (String v : e.getValue()) values.add(v);
            r.add("values", values);
            r.addProperty("isExcludes", false);
            r.addProperty("isRecursive", false);
            resourceObj.add(e.getKey(), r);
        }
                p.add("resources", resourceObj);
        JsonObject item = new JsonObject();
        item.add("users", grantee == null || grantee.isBlank() ? new JsonArray() : arrayOf(grantee));
        JsonArray groupArray = new JsonArray();
        if (groups != null) for (String g : groups) if (g != null && !g.isBlank()) groupArray.add(g.trim());
        item.add("groups", groupArray);
        item.add("roles", new JsonArray());
        item.add("accesses", arrayOfObjects(accessTypes.toArray(new String[0]), "isAllowed", true));
        item.addProperty("delegateAdmin", false);
        if (policyType == 1) {
            JsonObject mask = new JsonObject();
            mask.addProperty("dataMaskType", maskType);
            if (maskConditionExpr != null && !maskConditionExpr.isBlank()) mask.addProperty("conditionExpr", maskConditionExpr);
            if (maskValueExpr != null && !maskValueExpr.isBlank()) mask.addProperty("valueExpr", maskValueExpr);
            item.add("dataMaskInfo", mask);
        } else if (policyType == 2) {
            JsonObject filter = new JsonObject();
            filter.addProperty("filterExpr", rowFilterExpr);
            item.add("rowFilterInfo", filter);
        }
        JsonArray items = new JsonArray();
        items.add(item);
        p.add("policyItems", policyType == 0 ? items : new JsonArray());
        p.add("dataMaskPolicyItems", policyType == 1 ? items : new JsonArray());
        p.add("rowFilterPolicyItems", policyType == 2 ? items : new JsonArray());

        HttpURLConnection conn = (HttpURLConnection) new URL(
                rangerAdminUrl + "/service/public/v2/api/policy").openConnection();
        configureSsl(conn);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", basic);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (var os = conn.getOutputStream()) {
            os.write(GSON.toJson(p).getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Ranger policy create failed for '" + policyName
                    + "' on service '" + serviceName + "': HTTP " + code + " — " + readBody(conn, true));
        }
        long id = JsonParser.parseString(readBody(conn, false)).getAsJsonObject().get("id").getAsLong();
        LOG.info("OmAtlasProvisioning: Ranger policy '{}' created on service '{}' (id={})", policyName, serviceName, id);
        return id;
    }

    /** Append a Hive {@code select} grant for {@code omUser} to an existing policy (idempotent). */
    private static long appendHiveSelectToPolicy(String rangerAdminUrl, String basic, String policyName,
                                                 long policyId, String omUser, long timeoutMs) throws Exception {
        return appendAccessToPolicy(rangerAdminUrl, basic, policyName, policyId, omUser, "select", timeoutMs);
    }

    private static long appendAccessToPolicy(String rangerAdminUrl, String basic, String policyName,
                                             long policyId, String omUser, String accessType, long timeoutMs) throws Exception {
        HttpURLConnection get = (HttpURLConnection) new URL(
                rangerAdminUrl + "/service/public/v2/api/policy/" + policyId).openConnection();
        configureSsl(get);
        get.setRequestProperty("Authorization", basic);
        if (get.getResponseCode() != 200) {
            throw new IllegalStateException("Ranger policy GET id=" + policyId + " failed: " + readBody(get, true));
        }
        JsonObject policy = JsonParser.parseString(readBody(get, false)).getAsJsonObject();
        JsonArray policyItems = policy.has("policyItems") && policy.get("policyItems").isJsonArray()
                ? policy.getAsJsonArray("policyItems") : new JsonArray();
        for (int i = 0; i < policyItems.size(); i++) {
            JsonObject ex = policyItems.get(i).getAsJsonObject();
            JsonArray users = ex.has("users") ? ex.getAsJsonArray("users") : new JsonArray();
            boolean hasUser = false;
            for (int j = 0; j < users.size(); j++) if (omUser.equals(users.get(j).getAsString())) { hasUser = true; break; }
            if (!hasUser) continue;
            JsonArray accesses = ex.has("accesses") ? ex.getAsJsonArray("accesses") : new JsonArray();
            for (int k = 0; k < accesses.size(); k++) {
                JsonObject a = accesses.get(k).getAsJsonObject();
                if (accessType.equals(a.has("type") ? a.get("type").getAsString() : null)
                        && a.has("isAllowed") && a.get("isAllowed").getAsBoolean()) {
                    LOG.info("OmAtlasProvisioning: Hive policy '{}' (id={}) already grants '{}' to '{}' — no-op",
                            policyName, policyId, accessType, omUser);
                    return policyId;
                }
            }
        }
        JsonObject newItem = new JsonObject();
        newItem.add("users", arrayOf(omUser));
        newItem.add("groups", new JsonArray());
        newItem.add("roles", new JsonArray());
        newItem.add("accesses", arrayOfObjects(new String[]{accessType}, "isAllowed", true));
        newItem.addProperty("delegateAdmin", false);
        policyItems.add(newItem);
        policy.add("policyItems", policyItems);

        HttpURLConnection put = (HttpURLConnection) new URL(
                rangerAdminUrl + "/service/public/v2/api/policy/" + policyId).openConnection();
        configureSsl(put);
        put.setRequestMethod("PUT");
        put.setRequestProperty("Authorization", basic);
        put.setRequestProperty("Content-Type", "application/json");
        put.setDoOutput(true);
        try (var os = put.getOutputStream()) {
            os.write(GSON.toJson(policy).getBytes(StandardCharsets.UTF_8));
        }
        int pc = put.getResponseCode();
        if (pc < 200 || pc >= 300) {
            throw new IllegalStateException("Ranger Hive policy update failed: HTTP " + pc + " — " + readBody(put, true));
        }
        LOG.info("OmAtlasProvisioning: appended '{}' for '{}' to policy '{}' (id={})",
                accessType, omUser, policyName, policyId);
        return policyId;
    }

    private static Long lookupAtlasPolicyByName(String rangerAdminUrl, String authHeader,
                                                String atlasServiceName, String policyName) throws Exception {
        // Ranger expects RFC-3986 path-segment encoding (' ' → %20). Java's
        // URLEncoder.encode does form encoding (' ' → '+'), which Ranger 404s on
        // for policy names containing spaces (e.g. "all - entity-type, entity-classification, entity").
        // Build the path with %20 by post-processing URLEncoder output.
        String urlStr = rangerAdminUrl + "/service/public/v2/api/service/"
                + pathSegmentEncode(atlasServiceName)
                + "/policy/" + pathSegmentEncode(policyName);
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        configureSsl(conn);
        conn.setRequestProperty("Authorization", authHeader);
        int code = conn.getResponseCode();
        if (code == 200) {
            JsonObject found = JsonParser.parseString(readBody(conn, false)).getAsJsonObject();
            return found.has("id") ? found.get("id").getAsLong() : null;
        }
        if (code == 404) return null;
        throw new IllegalStateException("Ranger policy lookup-by-name returned HTTP " + code
                + ": " + readBody(conn, true));
    }

    /**
     * Create-or-find a Ranger SERVICE (a.k.a. plugin "repository") over the Admin REST API.
     * Idempotent: GET-by-name first; if absent, POST. This is the direct-REST equivalent of the
     * Ambari-delegated {@code submitRangerPluginRepository}, used when the platform context carries
     * its own Ranger admin credentials (external, or a managed/remote context with creds entered).
     *
     * @param serviceName the repository name (e.g. {@code <release>_trino})
     * @param serviceType Ranger service-def type (e.g. {@code trino}, {@code hive}, {@code hdfs})
     * @param configs     service configs (jdbc.url, username, resource-lookup creds, tag.download.auth.users …);
     *                    may be empty — Ranger accepts a minimal repo and configs can be refined later
     * @return the Ranger service id
     */
    public static long createOrFindService(String rangerAdminUrl, String rangerUser, String rangerPassword,
                                           String serviceName, String serviceType,
                                           Map<String, String> configs) throws Exception {
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                (rangerUser + ":" + rangerPassword).getBytes(StandardCharsets.UTF_8));

                Long existing = lookupServiceByName(rangerAdminUrl, basic, serviceName);
        if (existing != null) {
            LOG.info("OmAtlasProvisioning: Ranger service '{}' already exists (id={}) — keeping it, checking its tag service",
                    serviceName, existing);
            relinkTagServiceIfNeeded(rangerAdminUrl, basic, existing, serviceName);
            return existing;
        }

        JsonObject svc = new JsonObject();
        svc.addProperty("name", serviceName);
        svc.addProperty("type", serviceType);
        svc.addProperty("isEnabled", true);
        JsonObject cfg = new JsonObject();
        if (configs != null) {
            for (Map.Entry<String, String> e : configs.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) cfg.addProperty(e.getKey(), e.getValue());
            }
        }
                svc.add("configs", cfg);
        // Tags flow Atlas/OpenMetadata -> tag service -> every repo linked to it, so the new repo must
        // sit on the SAME tag service as the context's Hive repo for Hive-side tags to apply to it
        // (Ranger would otherwise auto-link a default 'tag' service nothing else uses).
        String tagService = deriveTagServiceFromHive(rangerAdminUrl, basic);
        if (tagService != null) {
            svc.addProperty("tagService", tagService);
            LOG.info("OmAtlasProvisioning: linking Ranger service '{}' to tag service '{}' (from the Hive repo)", serviceName, tagService);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(
                rangerAdminUrl + "/service/public/v2/api/service").openConnection();
        configureSsl(conn);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", basic);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (var os = conn.getOutputStream()) {
            os.write(GSON.toJson(svc).getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Ranger service create failed for '" + serviceName
                    + "' (type=" + serviceType + "): HTTP " + code + " — " + readBody(conn, true));
        }
        long id = JsonParser.parseString(readBody(conn, false)).getAsJsonObject().get("id").getAsLong();
        LOG.info("OmAtlasProvisioning: Ranger service '{}' (type={}) created (id={})", serviceName, serviceType, id);
        return id;
    }

            /**
     * An existing repo created before KDPS set tag services sits on Ranger's default {@code tag}
     * service (or none). Move it to the Hive repo's tag service so Hive-side tags apply to it; a repo
     * already on a non-default tag service is left alone. Best effort — never fails the caller.
     */
    static void relinkTagServiceIfNeeded(String rangerAdminUrl, String basic, long serviceId, String serviceName) {
        try {
            String want = deriveTagServiceFromHive(rangerAdminUrl, basic);
            if (want == null) return;
            HttpURLConnection get = (HttpURLConnection) new URL(
                    rangerAdminUrl + "/service/public/v2/api/service/" + serviceId).openConnection();
            configureSsl(get);
            get.setRequestMethod("GET");
            get.setRequestProperty("Authorization", basic);
            get.setRequestProperty("Accept", "application/json");
            if (get.getResponseCode() != 200) return;
            JsonObject svc = JsonParser.parseString(readBody(get, false)).getAsJsonObject();
            String current = svc.has("tagService") && !svc.get("tagService").isJsonNull() ? svc.get("tagService").getAsString() : "";
            if (want.equals(current) || (!current.isBlank() && !"tag".equals(current))) return;
            svc.addProperty("tagService", want);
            HttpURLConnection put = (HttpURLConnection) new URL(
                    rangerAdminUrl + "/service/public/v2/api/service/" + serviceId).openConnection();
            configureSsl(put);
            put.setRequestMethod("PUT");
            put.setRequestProperty("Authorization", basic);
            put.setRequestProperty("Content-Type", "application/json");
            put.setDoOutput(true);
            try (var os = put.getOutputStream()) {
                os.write(GSON.toJson(svc).getBytes(StandardCharsets.UTF_8));
            }
            int code = put.getResponseCode();
            LOG.info("OmAtlasProvisioning: relinked Ranger service '{}' from tag service '{}' to '{}' (HTTP {})",
                    serviceName, current, want, code);
        } catch (Exception e) {
            LOG.warn("OmAtlasProvisioning: could not relink the tag service of '{}': {}", serviceName, e.toString());
        }
    }

        /** Ranger refuses a policy naming an unknown group: create it as an internal group (idempotent). */
    static void ensureRangerGroupExists(String rangerAdminUrl, String basic, String groupName) throws Exception {
        HttpURLConnection get = (HttpURLConnection) new URL(rangerAdminUrl + "/service/xusers/groups?name="
                + java.net.URLEncoder.encode(groupName, StandardCharsets.UTF_8)).openConnection();
        configureSsl(get);
        get.setRequestMethod("GET");
        get.setRequestProperty("Authorization", basic);
        get.setRequestProperty("Accept", "application/json");
        if (get.getResponseCode() == 200 && readBody(get, false).contains("\"name\":\"" + groupName + "\"")) return;
        JsonObject group = new JsonObject();
        group.addProperty("name", groupName);
        group.addProperty("description", "Provisioned by KDPS for Ranger policies");
        HttpURLConnection post = (HttpURLConnection) new URL(rangerAdminUrl + "/service/xusers/secure/groups").openConnection();
        configureSsl(post);
        post.setRequestMethod("POST");
        post.setRequestProperty("Authorization", basic);
        post.setRequestProperty("Content-Type", "application/json");
        post.setDoOutput(true);
        try (var os = post.getOutputStream()) {
            os.write(GSON.toJson(group).getBytes(StandardCharsets.UTF_8));
        }
        int code = post.getResponseCode();
        if (code / 100 != 2 && !(code == 400 && readBody(post, true).toLowerCase().contains("already exists"))) {
            throw new IllegalStateException("Ranger group '" + groupName + "' could not be created: HTTP " + code + " — " + readBody(post, true));
        }
        LOG.info("OmAtlasProvisioning: created Ranger group '{}'", groupName);
    }

    /**
     * Creates an internal Ranger user with {@code roles} and {@code password}, or, when Ranger already has an internal
     * user of that name created for the same owner, resets its password and roles; either way the user ends up
     * enabled. The view uses it for the account of a Trino release's Atlas tag projector on a context whose Ranger it
     * reaches directly, with a password generated for every deploy. A user of that name whose description is not
     * {@code owner}, and an external (synced) one, are refused rather than taken over: KDPS did not create them for
     * this owner.
     *
     * @param owner the user's description, naming what it belongs to
     */
    public static void ensureInternalRangerUser(String rangerAdminUrl, String adminUser, String adminPassword,
                                                String userName, String password, List<String> roles,
                                                String owner) throws Exception {
        String basic = basicAuth(adminUser, adminPassword);
        JsonArray roleList = new JsonArray();
        roles.forEach(roleList::add);
        JsonObject user = findRangerUser(rangerAdminUrl, basic, userName);
        if (user == null) {
            JsonObject xuser = new JsonObject();
            xuser.addProperty("name", userName);
            xuser.addProperty("password", password);
            xuser.addProperty("firstName", userName);
            xuser.addProperty("lastName", "(KDPS)");
            xuser.addProperty("emailAddress", "");
            xuser.addProperty("description", owner);
            xuser.addProperty("status", 1);
            xuser.addProperty("isVisible", 1);
            xuser.addProperty("userSource", 0);
            xuser.add("userRoleList", roleList);
            RangerAnswer created = rangerCall("POST", rangerAdminUrl + "/service/xusers/secure/users", basic, xuser);
            if (created.code() / 100 != 2) {
                throw new IllegalStateException("Ranger user '" + userName + "' could not be created: HTTP "
                        + created.code() + " — " + created.body());
            }
            LOG.info("OmAtlasProvisioning: created Ranger user '{}' with roles {}", userName, roles);
            return;
        }
        refuseExternalUser(user, userName);
        refuseOtherOwner(user, userName, owner);
        user.addProperty("password", password);
        user.add("userRoleList", roleList);
        user.addProperty("status", 1);
        RangerAnswer updated = rangerCall("PUT",
                rangerAdminUrl + "/service/xusers/secure/users/" + user.get("id").getAsLong(), basic, user);
        if (updated.code() / 100 != 2) {
            throw new IllegalStateException("Ranger user '" + userName + "' could not be updated: HTTP "
                    + updated.code() + " — " + updated.body());
        }
        LOG.info("OmAtlasProvisioning: reset the password and roles {} of Ranger user '{}'", roles, userName);
    }

    /**
     * Disables an internal Ranger user: status 0, only {@code ROLE_USER} and a random password nobody is told, so a
     * copy of the old password, wherever it was kept, no longer logs in, and the account would hold no administrator
     * role should someone enable it again. Ranger keeps the user, so its audit trail still names it. The administrator
     * account the call is made with, external (synced) users, and a user whose description is not {@code owner}, are
     * refused.
     *
     * @param owner the description the user must have, or null to disable it whatever its description
     * @return false when Ranger has no user of that name
     */
    public static boolean disableInternalRangerUser(String rangerAdminUrl, String adminUser, String adminPassword,
                                                    String userName, String owner) throws Exception {
        if (userName.trim().equalsIgnoreCase(adminUser == null ? "" : adminUser.trim())) {
            throw new IllegalArgumentException("Refusing to disable '" + userName
                    + "': it is the Ranger administrator account of the platform context");
        }
        String basic = basicAuth(adminUser, adminPassword);
        JsonObject user = findRangerUser(rangerAdminUrl, basic, userName);
        if (user == null) {
            return false;
        }
        refuseExternalUser(user, userName);
        refuseOtherOwner(user, userName, owner);
        JsonArray roleList = new JsonArray();
        roleList.add("ROLE_USER");
        user.addProperty("password", java.util.UUID.randomUUID().toString().replace("-", "") + "Kd9");
        user.add("userRoleList", roleList);
        user.addProperty("status", 0);
        RangerAnswer updated = rangerCall("PUT",
                rangerAdminUrl + "/service/xusers/secure/users/" + user.get("id").getAsLong(), basic, user);
        if (updated.code() / 100 != 2) {
            throw new IllegalStateException("Ranger user '" + userName + "' could not be disabled: HTTP "
                    + updated.code() + " — " + updated.body());
        }
        LOG.info("OmAtlasProvisioning: disabled Ranger user '{}'", userName);
        return true;
    }

    /**
     * The Ranger user of exactly that name, or null when Ranger has none. Ranger's lookup matches names partially, so
     * the exact name is picked out of what it answers; a lookup Ranger refuses throws rather than reading as no user.
     */
    private static JsonObject findRangerUser(String rangerAdminUrl, String basic, String userName) throws Exception {
        RangerAnswer found = rangerCall("GET", rangerAdminUrl + "/service/xusers/users?name="
                + java.net.URLEncoder.encode(userName, StandardCharsets.UTF_8), basic, null);
        if (found.code() != 200) {
            throw new IllegalStateException("Ranger user lookup for '" + userName + "' failed: HTTP " + found.code()
                    + " — " + found.body());
        }
        JsonObject root = JsonParser.parseString(found.body()).getAsJsonObject();
        if (!root.has("vXUsers") || !root.get("vXUsers").isJsonArray()) {
            return null;
        }
        for (com.google.gson.JsonElement e : root.getAsJsonArray("vXUsers")) {
            JsonObject u = e.getAsJsonObject();
            if (u.has("name") && userName.equals(u.get("name").getAsString())) {
                return u;
            }
        }
        return null;
    }

    private static void refuseExternalUser(JsonObject user, String userName) {
        int userSource = user.has("userSource") && !user.get("userSource").isJsonNull()
                ? user.get("userSource").getAsInt() : 0;
        if (userSource != 0) {
            throw new IllegalStateException("Ranger user '" + userName + "' is an external (synced) user, which KDPS"
                    + " did not create; it was left unchanged");
        }
    }

    /** Refuses a user whose description does not name {@code owner}: another release's account, or not KDPS's. */
    private static void refuseOtherOwner(JsonObject user, String userName, String owner) {
        if (owner == null) {
            return;
        }
        String description = user.has("description") && user.get("description").isJsonPrimitive()
                ? user.get("description").getAsString() : "";
        if (!owner.equals(description)) {
            throw new IllegalStateException("Ranger user '" + userName + "' is not the one KDPS created: its"
                    + " description is '" + description + "', not '" + owner + "'. It was left unchanged.");
        }
    }

    private static String basicAuth(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** The tag service of a Ranger repo by name, or null. */
    public static String getTagServiceOf(String rangerAdminUrl, String rangerUser, String rangerPassword, String serviceName) {
        try {
            String basic = "Basic " + Base64.getEncoder().encodeToString(
                    (rangerUser + ":" + rangerPassword).getBytes(StandardCharsets.UTF_8));
            HttpURLConnection get = (HttpURLConnection) new URL(
                    rangerAdminUrl + "/service/public/v2/api/service/name/" + java.net.URLEncoder.encode(serviceName, StandardCharsets.UTF_8)).openConnection();
            configureSsl(get);
            get.setRequestMethod("GET");
            get.setRequestProperty("Authorization", basic);
            get.setRequestProperty("Accept", "application/json");
            if (get.getResponseCode() != 200) return null;
            JsonObject svc = JsonParser.parseString(readBody(get, false)).getAsJsonObject();
            return svc.has("tagService") && !svc.get("tagService").isJsonNull() ? svc.get("tagService").getAsString() : null;
        } catch (Exception e) {
            LOG.warn("OmAtlasProvisioning: could not read the tag service of '{}': {}", serviceName, e.toString());
            return null;
        }
    }

    /** The tag service of the first Hive repo that has one, or null (best effort — never fails the caller). */
    static String deriveTagServiceFromHive(String rangerAdminUrl, String basic) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    rangerAdminUrl + "/service/public/v2/api/service?serviceType=hive").openConnection();
            configureSsl(conn);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", basic);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return null;
            com.google.gson.JsonElement root = JsonParser.parseString(readBody(conn, false));
            if (!root.isJsonArray()) return null;
            for (com.google.gson.JsonElement e : root.getAsJsonArray()) {
                JsonObject svc = e.getAsJsonObject();
                if (svc.has("tagService") && !svc.get("tagService").isJsonNull()
                        && !svc.get("tagService").getAsString().isBlank()) {
                    return svc.get("tagService").getAsString().trim();
                }
            }
        } catch (Exception e) {
            LOG.warn("OmAtlasProvisioning: could not derive the tag service from the Hive repo: {}", e.toString());
        }
        return null;
    }

    /** GET a Ranger service by name; returns its id, or null on 404. */
    private static Long lookupServiceByName(String rangerAdminUrl, String basic, String serviceName) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                rangerAdminUrl + "/service/public/v2/api/service/name/" + pathSegmentEncode(serviceName)).openConnection();
        configureSsl(conn);
        conn.setRequestProperty("Authorization", basic);
        int code = conn.getResponseCode();
        if (code == 200) {
            JsonObject found = JsonParser.parseString(readBody(conn, false)).getAsJsonObject();
            return found.has("id") ? found.get("id").getAsLong() : null;
        }
        if (code == 404) return null;
        throw new IllegalStateException("Ranger service lookup-by-name '" + serviceName + "' returned HTTP " + code
                + ": " + readBody(conn, true));
    }

    private static JsonObject buildAtlasReadPolicy(String atlasServiceName, String policyName,
                                                   String omPrincipal, boolean isKerberos,
                                                   AtlasResourceSet set) {
        JsonObject p = new JsonObject();
        p.addProperty("service", atlasServiceName);
        p.addProperty("name", policyName);
        p.addProperty("description",
                "KDPS-managed: read-only Atlas access for the OpenMetadata federation user. "
                        + "Generated by ATLAS_FEDERATION post-deploy step.");
        p.addProperty("isAuditEnabled", true);
        p.addProperty("isEnabled", true);
        p.addProperty("policyType", 0);  // 0=Access (vs 1=Masking, 2=Row-level)
        p.addProperty("policyPriority", 0);

        // Atlas Ranger resources — `*` matches every entity/classification.
        // The set of keys MUST match one of the servicedef-declared combinations
        // (see AtlasResourceSet for the two we use).
        JsonObject resources = new JsonObject();
        for (String t : set.keys) {
            JsonObject r = new JsonObject();
            r.add("values", arrayOf("*"));
            r.addProperty("isExcludes", false);
            r.addProperty("isRecursive", false);
            resources.add(t, r);
        }
        p.add("resources", resources);

        JsonObject item = new JsonObject();
        // Kerberos principals look like `<service>@<realm>`; Ranger stores them
        // in `users` (Ranger normalises against the auth-to-local rule).
        item.add("users", arrayOf(omPrincipal));
        item.add("groups", new JsonArray());
        item.add("roles", new JsonArray());
        // Single access type per policy — entity-read for the ENTITIES set,
        // type-read for the TYPES set (see AtlasResourceSet.accessType).
        item.add("accesses", arrayOfObjects(new String[]{set.accessType()}, "isAllowed", true));
        item.addProperty("delegateAdmin", false);
        p.add("policyItems", arrayOfObjects(new JsonObject[]{item}));

        p.add("denyPolicyItems", new JsonArray());
        p.add("allowExceptions", new JsonArray());
        p.add("denyExceptions", new JsonArray());
        p.add("dataMaskPolicyItems", new JsonArray());
        p.add("rowFilterPolicyItems", new JsonArray());
        return p;
    }

    private static String pathSegmentEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static JsonArray arrayOf(String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) a.add(v);
        return a;
    }

    private static JsonArray arrayOfObjects(JsonObject[] items) {
        JsonArray a = new JsonArray();
        for (JsonObject o : items) a.add(o);
        return a;
    }

    /** Helper: build a JsonArray of {type: X, key: value} objects. */
    private static JsonArray arrayOfObjects(String[] types, String key, Object value) {
        JsonArray a = new JsonArray();
        for (String t : types) {
            JsonObject o = new JsonObject();
            o.addProperty("type", t);
            if (value instanceof Boolean) o.addProperty(key, (Boolean) value);
            else if (value instanceof Number) o.addProperty(key, (Number) value);
            else o.addProperty(key, value == null ? null : value.toString());
            a.add(o);
        }
        return a;
    }

    /** Disable hostname + cert verification — Ranger Admin in dev uses self-signed certs.
     *  Production deployments should provide a real trust chain. */
    private static void configureSsl(HttpURLConnection conn) throws Exception {
        if (conn instanceof HttpsURLConnection https) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            }}, new SecureRandom());
            https.setSSLSocketFactory(ctx.getSocketFactory());
            https.setHostnameVerifier((h, s) -> true);
        }
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);
    }

    private static String readBody(HttpURLConnection conn, boolean errorStream) throws Exception {
        var is = errorStream ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return "";
        try (is) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private OmAtlasProvisioning() { /* utility */ }
}
