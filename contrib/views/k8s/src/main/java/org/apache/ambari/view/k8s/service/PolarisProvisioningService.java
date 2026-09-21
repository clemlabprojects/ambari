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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Provisions what a KDPS-deployed engine needs to use an Apache Polaris catalog: an identity of
 * its own, optionally the catalog and its bucket, and the credential delivered as a Kubernetes
 * Secret the chart mounts.
 *
 * <p>Every step is <em>replayable</em>. Names are derived from the release and namespace, never
 * generated, so re-installing the same release with the same inputs converges on the same objects
 * instead of creating a second catalog or a second bucket. Each call either finds what it needs or
 * creates it, and reports which of the two happened.
 *
 * <p>The identity is the exception that cannot be idempotent: Polaris returns a principal's secret
 * only when the principal is created, so on a replay the credential is rotated and the Secret is
 * rewritten. The engine is being redeployed at that point anyway, so it picks the new value up.
 *
 * <p>Creating the catalog and the bucket is a choice the operator makes per release. With creation
 * turned off, this only mints the identity, grants it on the catalog the operator named, and writes
 * the Secret, which is how an engine is attached to a catalog somebody else owns.
 */
public class PolarisProvisioningService {

    private static final Logger LOG = LoggerFactory.getLogger(PolarisProvisioningService.class);
    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;

    public PolarisProvisioningService(HttpClient http) {
        this.http = http;
    }

    /** What the caller asks for. All names are inputs, so the same request always targets the same objects. */
    public static class Request {
        public String restUri;            // <base>/api/catalog
        public String managementUri;      // <base>/api/management/v1
        public String realm;
        public String realmHeaderName;    // only sent when the server requires it
        public boolean realmHeaderRequired;
        public String adminClientId;
        public String adminClientSecret;

        public String principalName;      // deterministic, see deterministicPrincipalName
        public String principalRoleName;
        public String catalogName;
        public String catalogRoleName;

        public boolean createCatalog;     // false: attach to a catalog the operator already owns
        public String baseLocation;       // s3://<bucket>/<prefix>/
        public List<String> allowedLocations;
        public String s3Endpoint;
        public String s3Region;
        public boolean s3PathStyleAccess;
        public boolean stsUnavailable;    // true for the Ozone S3 gateway: no token service
        public String s3AccessKeyId;      // admin S3 credential, used for the catalog and the bucket
        public String s3SecretAccessKey;
    }

    /** What happened, so the caller can log it and put the credential where the chart expects it. */
    public static class Result {
        public String principalName;
        public String clientId;
        public String clientSecret;
        public String catalogName;
        public boolean principalCreated;
        public boolean catalogCreated;
        public boolean bucketCreated;
        public boolean grantsApplied;
        public String warning;
    }

    // ---------------------------------------------------------------- naming

    /**
     * Names derived from the release and the namespace, so a re-install of the same release targets
     * the objects it created last time instead of making new ones.
     *
     * <p>The formula is deliberately simple — {@code <release>-<namespace>}, lower-cased, anything
     * outside {@code [a-z0-9-]} folded to a dash, and truncated — because the install wizard has to
     * arrive at exactly the same string when it renders the engine's catalog file. Two releases
     * whose combined name exceeds the limit and shares a prefix would collide, which is why the
     * limit is the loosest of the three (63 characters, an object store bucket).
     */
    public static String deterministicName(String releaseName, String namespace, int max) {
        String v = (safe(releaseName) + "-" + safe(namespace)).replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (v.length() > max) v = v.substring(0, max).replaceAll("-+$", "");
        return v;
    }

    public static String deterministicPrincipalName(String releaseName, String namespace) {
        return deterministicName(releaseName, namespace, 63);
    }

    public static String deterministicCatalogName(String releaseName, String namespace) {
        return deterministicName(releaseName, namespace, 63);
    }

    /** Bucket names are the strictest: lower case, no underscores, at most 63 characters. */
    public static String deterministicBucketName(String releaseName, String namespace) {
        return deterministicName(releaseName, namespace, 63).replace('_', '-');
    }

    private static String safe(String s) {
        String v = (s == null ? "" : s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        return v.replaceAll("-+", "-").replaceAll("^-|-$", "");
    }

    // ---------------------------------------------------------------- flow

    public Result ensure(Request req) throws Exception {
        Result out = new Result();
        out.principalName = req.principalName;
        out.catalogName = req.catalogName;

        String token = adminToken(req);

        if (req.createCatalog) {
            out.bucketCreated = ensureBucket(req);
            out.catalogCreated = ensureCatalog(req, token);
        } else {
            LOG.info("Polaris provisioning: catalog creation disabled; attaching to the existing catalog '{}'.",
                    req.catalogName);
        }

        ensurePrincipal(req, token, out);
        out.grantsApplied = ensureGrants(req, token, out);
        return out;
    }

    /**
     * Undoes what {@link #ensure} handed out when a release goes away: the principal, and with it
     * the credential the release was using. Once the principal is gone the client id and secret in
     * its Secret authenticate as nobody, which is the point — a deleted release must not leave a
     * working key behind.
     *
     * <p>What it deliberately does not touch is the data. The catalog is dropped only when
     * {@code dropCatalog} is asked for, and the bucket never: an uninstall is routinely a step in a
     * reinstall, and tables that took hours to write should not disappear because a release was
     * removed. Dropping the bucket is an object-store operation an operator can do deliberately,
     * with the catalog already gone.
     *
     * <p>Missing objects are success, not failure: a release uninstalled twice, or one whose
     * principal an operator already removed, must still finish cleanly.
     */
    public Result revoke(Request req, boolean dropCatalog) throws Exception {
        Result out = new Result();
        out.principalName = req.principalName;
        out.catalogName = req.catalogName;

        String token = adminToken(req);

        out.principalCreated = deleteIgnoringMissing(req, token,
                "/principals/" + enc(req.principalName), "principal " + req.principalName);
        // The roles exist only to carry this release's grants, so they go with it.
        deleteIgnoringMissing(req, token,
                "/principal-roles/" + enc(req.principalRoleName), "principal role " + req.principalRoleName);

        if (dropCatalog) {
            deleteIgnoringMissing(req, token,
                    "/catalogs/" + enc(req.catalogName) + "/catalog-roles/" + enc(req.catalogRoleName),
                    "catalog role " + req.catalogRoleName);
            out.catalogCreated = deleteIgnoringMissing(req, token,
                    "/catalogs/" + enc(req.catalogName), "catalog " + req.catalogName);
            if (!out.catalogCreated) {
                out.warning = "The Polaris catalog '" + req.catalogName + "' could not be dropped; it usually still "
                        + "holds namespaces or tables. The data is untouched — drop them first, or leave the catalog "
                        + "in place and reuse it.";
            }
        }
        LOG.info("Polaris revoke for principal '{}': principal removed={}, catalog dropped={} (bucket left alone).",
                req.principalName, out.principalCreated, dropCatalog && out.catalogCreated);
        return out;
    }

    /** DELETE that treats 404 as done. Returns whether something was actually removed. */
    private boolean deleteIgnoringMissing(Request req, String token, String path, String what) throws Exception {
        HttpResponse<String> r = send(req, token, "DELETE", path, null);
        if (r.statusCode() / 100 == 2) return true;
        if (r.statusCode() == 404) {
            LOG.info("Polaris revoke: {} was already gone.", what);
            return false;
        }
        LOG.warn("Polaris revoke: removing {} failed with HTTP {} — {}", what, r.statusCode(), brief(r.body()));
        return false;
    }

    /** Client-credentials token for the Polaris administrator. */
    private String adminToken(Request req) throws Exception {
        String body = "grant_type=client_credentials"
                + "&client_id=" + enc(req.adminClientId)
                + "&client_secret=" + enc(req.adminClientSecret)
                + "&scope=PRINCIPAL_ROLE:ALL";
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(req.restUri + "/v1/oauth/tokens"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        realmHeader(b, req);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("Polaris rejected the administrator credentials (HTTP "
                    + r.statusCode() + "). Check the Polaris administrator user and password on the platform context.");
        }
        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        return o.get("access_token").getAsString();
    }

    /**
     * Creates the principal, or rotates its credential when it already exists. Polaris only ever
     * discloses a secret at creation or rotation, so a replay has to rotate: there is no way to
     * read back the value a previous install received.
     */
    private void ensurePrincipal(Request req, String token, Result out) throws Exception {
        JsonObject principal = new JsonObject();
        principal.addProperty("name", req.principalName);
        principal.addProperty("type", "SERVICE");
        JsonObject body = new JsonObject();
        body.add("principal", principal);

        HttpResponse<String> r = send(req, token, "POST", "/principals", body.toString());
        if (r.statusCode() / 100 == 2) {
            out.principalCreated = true;
            readCredentials(r.body(), out);
            LOG.info("Polaris provisioning: created principal '{}'.", req.principalName);
            return;
        }
        if (r.statusCode() != 409) {
            throw new IllegalStateException("Could not create the Polaris principal '" + req.principalName
                    + "' (HTTP " + r.statusCode() + "): " + brief(r.body()));
        }
        HttpResponse<String> rot = send(req, token, "POST",
                "/principals/" + enc(req.principalName) + "/rotate", "");
        if (rot.statusCode() / 100 != 2) {
            throw new IllegalStateException("The Polaris principal '" + req.principalName
                    + "' already exists but its credential could not be rotated (HTTP " + rot.statusCode()
                    + "): " + brief(rot.body()));
        }
        readCredentials(rot.body(), out);
        LOG.info("Polaris provisioning: principal '{}' already existed; rotated its credential.", req.principalName);
    }

    private void readCredentials(String body, Result out) {
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        JsonObject creds = o.has("credentials") ? o.getAsJsonObject("credentials") : o;
        if (creds.has("clientId")) out.clientId = creds.get("clientId").getAsString();
        if (creds.has("clientSecret")) out.clientSecret = creds.get("clientSecret").getAsString();
    }

    /** Creates the catalog when it is missing. An existing catalog is left exactly as it is. */
    private boolean ensureCatalog(Request req, String token) throws Exception {
        HttpResponse<String> existing = send(req, token, "GET", "/catalogs/" + enc(req.catalogName), null);
        if (existing.statusCode() / 100 == 2) {
            LOG.info("Polaris provisioning: catalog '{}' already exists; leaving it unchanged.", req.catalogName);
            return false;
        }

        JsonObject storage = new JsonObject();
        storage.addProperty("storageType", "S3");
        storage.addProperty("endpoint", req.s3Endpoint);
        storage.addProperty("region", req.s3Region);
        storage.addProperty("pathStyleAccess", req.s3PathStyleAccess);
        storage.addProperty("stsUnavailable", req.stsUnavailable);
        storage.add("allowedLocations", GSON.toJsonTree(req.allowedLocations));

        JsonObject props = new JsonObject();
        props.addProperty("default-base-location", req.baseLocation);
        if (req.s3AccessKeyId != null && !req.s3AccessKeyId.isBlank()) {
            // A store with no token service cannot vend, so the catalog carries the key itself.
            props.addProperty("s3.access-key-id", req.s3AccessKeyId);
            props.addProperty("s3.secret-access-key", req.s3SecretAccessKey);
        }

        JsonObject catalog = new JsonObject();
        catalog.addProperty("type", "INTERNAL");
        catalog.addProperty("name", req.catalogName);
        catalog.add("properties", props);
        catalog.add("storageConfigInfo", storage);
        JsonObject body = new JsonObject();
        body.add("catalog", catalog);

        HttpResponse<String> r = send(req, token, "POST", "/catalogs", body.toString());
        if (r.statusCode() / 100 == 2) {
            LOG.info("Polaris provisioning: created catalog '{}' at {}.", req.catalogName, req.baseLocation);
            return true;
        }
        if (r.statusCode() == 409) return false;
        throw new IllegalStateException("Could not create the Polaris catalog '" + req.catalogName
                + "' (HTTP " + r.statusCode() + "): " + brief(r.body()));
    }

    /**
     * Grants the principal full content access on the catalog through Polaris roles.
     *
     * <p>When Polaris delegates authorization to Ranger, role management is refused even for the
     * administrator; that is expected, and the caller then applies the equivalent Ranger policy.
     * The failure is reported rather than thrown so one refused grant does not abort an install
     * whose catalog and identity are already in place.
     */
    private boolean ensureGrants(Request req, String token, Result out) {
        try {
            postIgnoringConflict(req, token, "/principal-roles",
                    "{\"principalRole\":{\"name\":\"" + req.principalRoleName + "\"}}");
            putIgnoringConflict(req, token,
                    "/principals/" + enc(req.principalName) + "/principal-roles",
                    "{\"principalRole\":{\"name\":\"" + req.principalRoleName + "\"}}");
            postIgnoringConflict(req, token,
                    "/catalogs/" + enc(req.catalogName) + "/catalog-roles",
                    "{\"catalogRole\":{\"name\":\"" + req.catalogRoleName + "\"}}");
            putIgnoringConflict(req, token,
                    "/principal-roles/" + enc(req.principalRoleName) + "/catalog-roles/" + enc(req.catalogName),
                    "{\"catalogRole\":{\"name\":\"" + req.catalogRoleName + "\"}}");
            putIgnoringConflict(req, token,
                    "/catalogs/" + enc(req.catalogName) + "/catalog-roles/" + enc(req.catalogRoleName) + "/grants",
                    "{\"grant\":{\"type\":\"catalog\",\"privilege\":\"CATALOG_MANAGE_CONTENT\"}}");
            LOG.info("Polaris provisioning: granted '{}' full content access on catalog '{}'.",
                    req.principalName, req.catalogName);
            return true;
        } catch (Exception ex) {
            out.warning = "Polaris role grants were refused (" + ex.getMessage()
                    + "). This is expected when Polaris delegates authorization to Ranger; grant the principal there instead.";
            LOG.warn("Polaris provisioning: {}", out.warning);
            return false;
        }
    }

    // ---------------------------------------------------------------- S3

    /**
     * Creates the bucket behind the catalog when it is missing, with a signed request against the
     * S3 endpoint. Re-running is safe: a bucket that already belongs to us answers with a conflict,
     * which is treated as success, so repeated installs of a release never produce a second bucket.
     */
    private boolean ensureBucket(Request req) throws Exception {
        String bucket = bucketOf(req.baseLocation);
        if (bucket == null || req.s3Endpoint == null || req.s3Endpoint.isBlank()) return false;
        if (req.s3AccessKeyId == null || req.s3AccessKeyId.isBlank()) return false;

        HttpResponse<String> head = signedS3(req, "HEAD", bucket, "", null);
        if (head.statusCode() / 100 == 2) {
            LOG.info("Polaris provisioning: bucket '{}' already exists.", bucket);
            return false;
        }
        HttpResponse<String> put = signedS3(req, "PUT", bucket, "", "");
        if (put.statusCode() / 100 == 2) {
            LOG.info("Polaris provisioning: created bucket '{}'.", bucket);
            return true;
        }
        if (put.statusCode() == 409) {
            LOG.info("Polaris provisioning: bucket '{}' already exists.", bucket);
            return false;
        }
        throw new IllegalStateException("Could not create the object store bucket '" + bucket
                + "' (HTTP " + put.statusCode() + "): " + brief(put.body()));
    }

    static String bucketOf(String baseLocation) {
        if (baseLocation == null) return null;
        String v = baseLocation.trim();
        int i = v.indexOf("://");
        if (i < 0) return null;
        String rest = v.substring(i + 3);
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        return bucket.isBlank() ? null : bucket;
    }

    /** Minimal AWS SigV4 for the two bucket calls; the object store is reached directly, not through a client library. */
    private HttpResponse<String> signedS3(Request req, String method, String bucket, String query, String body)
            throws Exception {
        URI endpoint = URI.create(req.s3Endpoint);
        String host = endpoint.getAuthority();
        String region = (req.s3Region == null || req.s3Region.isBlank()) ? "us-east-1" : req.s3Region;
        String payload = body == null ? "" : body;
        String payloadHash = hex(sha256(payload));

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String amzDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
        String dateStamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        Map<String, String> headers = new TreeMap<>();
        headers.put("host", host);
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);

        String canonicalUri = "/" + bucket;
        StringBuilder ch = new StringBuilder();
        for (Map.Entry<String, String> e : headers.entrySet()) ch.append(e.getKey()).append(':').append(e.getValue()).append('\n');
        String signedHeaders = String.join(";", headers.keySet());
        String canonicalRequest = method + "\n" + canonicalUri + "\n" + query + "\n" + ch + "\n" + signedHeaders + "\n" + payloadHash;

        String scope = dateStamp + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonicalRequest));

        byte[] key = ("AWS4" + req.s3SecretAccessKey).getBytes(StandardCharsets.UTF_8);
        for (String part : new String[]{dateStamp, region, "s3", "aws4_request"}) key = hmac(key, part);
        String signature = hex(hmac(key, stringToSign));

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(req.s3Endpoint.replaceAll("/$", "") + canonicalUri
                        + (query.isEmpty() ? "" : "?" + query)))
                .timeout(TIMEOUT)
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + req.s3AccessKeyId + "/" + scope
                        + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        if ("PUT".equals(method)) b.PUT(HttpRequest.BodyPublishers.ofString(payload));
        else if ("HEAD".equals(method)) b.method("HEAD", HttpRequest.BodyPublishers.noBody());
        else b.method(method, HttpRequest.BodyPublishers.noBody());
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---------------------------------------------------------------- http helpers

    private HttpResponse<String> send(Request req, String token, String method, String path, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(req.managementUri + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        realmHeader(b, req);
        if ("DELETE".equals(method)) b.DELETE();
        else if (body == null) b.GET();
        else if ("PUT".equals(method)) b.PUT(HttpRequest.BodyPublishers.ofString(body));
        else b.POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void postIgnoringConflict(Request req, String token, String path, String body) throws Exception {
        HttpResponse<String> r = send(req, token, "POST", path, body);
        if (r.statusCode() / 100 != 2 && r.statusCode() != 409) {
            throw new IllegalStateException("HTTP " + r.statusCode() + " on " + path + ": " + brief(r.body()));
        }
    }

    private void putIgnoringConflict(Request req, String token, String path, String body) throws Exception {
        HttpResponse<String> r = send(req, token, "PUT", path, body);
        if (r.statusCode() / 100 != 2 && r.statusCode() != 409) {
            throw new IllegalStateException("HTTP " + r.statusCode() + " on " + path + ": " + brief(r.body()));
        }
    }

    /**
     * The realm header is only sent when the server demands it. Sending it unconditionally would
     * be harmless for Polaris but it is also what the engines cannot do, so the provisioning path
     * mirrors what the engine will experience.
     */
    private void realmHeader(HttpRequest.Builder b, Request req) {
        if (req.realmHeaderRequired && req.realmHeaderName != null && !req.realmHeaderName.isBlank()
                && req.realm != null && !req.realm.isBlank()) {
            b.header(req.realmHeaderName, req.realm);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String brief(String body) {
        if (body == null) return "";
        String v = body.trim().replaceAll("\\s+", " ");
        return v.length() > 300 ? v.substring(0, 300) + "…" : v;
    }

    private static byte[] sha256(String s) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Credential payload for the Kubernetes Secret the chart mounts. */
    public static Map<String, String> secretData(Result r, String s3AccessKeyId, String s3SecretAccessKey) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("client_id", r.clientId == null ? "" : r.clientId);
        data.put("client_secret", r.clientSecret == null ? "" : r.clientSecret);
        if (s3AccessKeyId != null && !s3AccessKeyId.isBlank()) {
            data.put("access_key", s3AccessKeyId);
            data.put("secret_key", s3SecretAccessKey == null ? "" : s3SecretAccessKey);
        }
        return data;
    }
}
