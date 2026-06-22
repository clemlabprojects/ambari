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
     * <p>Steps:
     * <ol>
     *   <li>GET policies for the Atlas service repo filtered by policy name —
     *       if a policy with our canonical name already exists, return its id
     *       without touching anything.</li>
     *   <li>Otherwise POST a new policy granting the OM federation principal
     *       read access on {@code entity-type/*}, {@code entity-classification/*},
     *       {@code relationship-type/*}, {@code type-category/*}.</li>
     *   <li>Poll GET-by-id every {@code POLL_INTERVAL} until the policy is
     *       readable (Atlas's Ranger plugin pulls policies on a ~30s interval;
     *       the Admin-side query forces a refresh).</li>
     * </ol>
     *
     * @return the policy id (created or pre-existing)
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

        // 1. Look up by name
        Long existing = lookupAtlasPolicyByName(rangerAdminUrl, basic, atlasServiceName, policyName);
        if (existing != null) {
            LOG.info("OmAtlasProvisioning: Ranger policy '{}' already exists (id={}) — skip create",
                    policyName, existing);
            return existing;
        }

        // 2. POST a new policy
        JsonObject policy = buildAtlasReadPolicy(atlasServiceName, policyName, omPrincipal, isKerberos);
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

        // 3. Poll until the policy is readable back via GET-by-id (proxies "policy persisted + indexed")
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

    private static Long lookupAtlasPolicyByName(String rangerAdminUrl, String authHeader,
                                                String atlasServiceName, String policyName) throws Exception {
        String urlStr = rangerAdminUrl + "/service/public/v2/api/service/"
                + java.net.URLEncoder.encode(atlasServiceName, StandardCharsets.UTF_8)
                + "/policy/" + java.net.URLEncoder.encode(policyName, StandardCharsets.UTF_8);
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
                                                   String omPrincipal, boolean isKerberos) {
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
        // Atlas's Ranger plugin keys the resources off these top-level
        // resource types defined in the atlas-servicedef.
        JsonObject resources = new JsonObject();
        for (String t : new String[]{"entity-type", "entity-classification",
                "relationship-type", "type-category", "atlas-service"}) {
            JsonObject r = new JsonObject();
            r.add("values", arrayOf("*"));
            r.addProperty("isExcludes", false);
            r.addProperty("isRecursive", false);
            resources.add(t, r);
        }
        p.add("resources", resources);

        // Single allow-policy item for the OM principal with read accesses.
        JsonObject item = new JsonObject();
        if (isKerberos) {
            // Kerberos principals look like `<service>@<realm>`; Ranger stores
            // them in `users` (Ranger normalises against the auth-to-local rule).
            item.add("users", arrayOf(omPrincipal));
        } else {
            item.add("users", arrayOf(omPrincipal));
        }
        item.add("groups", new JsonArray());
        item.add("roles", new JsonArray());
        item.add("accesses", arrayOfObjects(new String[]{"entity-read", "type-read", "entity-read-classification"},
                "isAllowed", true));
        item.addProperty("delegateAdmin", false);
        p.add("policyItems", arrayOfObjects(new JsonObject[]{item}));

        // Defaults for the rest
        p.add("denyPolicyItems", new JsonArray());
        p.add("allowExceptions", new JsonArray());
        p.add("denyExceptions", new JsonArray());
        p.add("dataMaskPolicyItems", new JsonArray());
        p.add("rowFilterPolicyItems", new JsonArray());
        return p;
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
