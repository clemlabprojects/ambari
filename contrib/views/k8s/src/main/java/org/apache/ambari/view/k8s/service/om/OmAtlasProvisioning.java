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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /** Holds the resolved OM federation credentials (one per release). */
    public static final class Credentials {
        public final String username;
        public final String plaintextPassword;  // only set on freshly-generated creds
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
        String secretName = releaseName + "-atlas-federation";
        String defaultUser = (explicitUsername != null && !explicitUsername.isBlank())
                ? explicitUsername.trim() : "openmetadata-federation";

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
