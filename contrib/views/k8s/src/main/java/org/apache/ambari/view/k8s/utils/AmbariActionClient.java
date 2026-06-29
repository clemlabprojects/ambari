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

package org.apache.ambari.view.k8s.utils;

import com.google.gson.*;
import org.apache.ambari.view.URLStreamProvider;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.EncryptionService;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.apache.ambari.view.k8s.service.CommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MultivaluedMap;

/**
 * Tiny client around Ambari Server REST needed for:
 *  - POST /api/v1/clusters/{cluster}/requests  (submit action)
 *  - GET  /api/v1/clusters/{cluster}/requests/{id} (poll status)
 *  - GET  /api/v1/clusters/{cluster}/requests/{id}/tasks?fields=structured_out (fetch keytab payload metadata)
 *  - POST /api/v1/clusters/{cluster}/adhoc_keytab/payloads/{payloadRef}/read (read keytab payload)
 *  - POST /api/v1/clusters/{cluster}/adhoc_keytab/payloads/{payloadRef}/ack (acknowledge/delete keytab payload)
 *
 * Authentication/headers:
 *  - Uses the View's URLStreamProvider so we don't hand-roll cookies/auth.
 */
public class AmbariActionClient {

    private final ViewContext ctx;
    private final URLStreamProvider stream;
    private final String ambariApiBase; // e.g. "http://localhost:8080/api/v1"
    private final String clusterName;
    private final Map<String,String> defaultHeaders;
    private final String permissionName = "AMBARI.ADMINISTRATOR";
    private Map<String,AmbariConfigPropertiesTypes> defaultConfigTypes;
    public record WebhookCreds(String username, String password) {}

    /**
     * When false, the read-path methods used against a REMOTE context's Ambari
     * (listClusters / getDesiredConfigPropert* / getComponentHosts / getServerAndStackInfo)
     * bypass the View's truststore-backed URLStreamProvider and use a trust-all HTTPS client
     * instead — the "ignore self-signed cert" toggle for a remote Ambari. Defaults to true
     * (verify), so the local-Ambari paths and every write path are unaffected.
     */
    private boolean verifySsl = true;

    /** Trust manager that accepts any server certificate — used ONLY when {@link #verifySsl} is false. */
    private static final javax.net.ssl.TrustManager[] TRUST_ALL = new javax.net.ssl.TrustManager[] {
        new javax.net.ssl.X509TrustManager() {
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
        }
    };

    /** Fluent toggle for TLS verification on the remote read-path (see {@link #verifySsl}). */
    public AmbariActionClient verifySsl(boolean verify) {
        this.verifySsl = verify;
        return this;
    }


    private static final Logger LOG = LoggerFactory.getLogger(AmbariActionClient.class);
    private static final Gson GSON = new Gson();
    private static final EncryptionService HEADER_ENCRYPTION = new EncryptionService();
    private static final String PERSISTED_HEADERS_FORMAT_KEY = "_format";
    private static final String PERSISTED_HEADERS_FORMAT_ENCRYPTED = "encrypted-v1";
    private static final String PERSISTED_HEADERS_PAYLOAD_KEY = "payload";
    public record AdhocKeytabPayloadMetadata(String payloadRef, String payloadSha256, Integer kvno, Boolean created) {}

    public AmbariActionClient(ViewContext ctx, String ambariApiBase, String clusterName, Map<String,String> defaultHeaders) {
        this.ctx = ctx;
        this.stream = ctx.getURLStreamProvider();
        this.ambariApiBase = ambariApiBase.endsWith("/") ? ambariApiBase.substring(0, ambariApiBase.length()-1) : ambariApiBase;
        this.clusterName = clusterName;
        this.defaultHeaders = defaultHeaders == null ? new LinkedHashMap<>() : new LinkedHashMap<>(defaultHeaders);
        LOG.debug("AmbariActionClient initialized with header keys: {}", this.defaultHeaders.keySet());
        this.initializeMainConfigurations();
    }

    public AmbariActionClient(ViewContext ctx, String ambariApiBase, Map<String,String> defaultHeaders) {
        this.ctx = ctx;
        this.stream = ctx.getURLStreamProvider();
        this.ambariApiBase = ambariApiBase.endsWith("/")
                ? ambariApiBase.substring(0, ambariApiBase.length() - 1)
                : ambariApiBase;
        this.clusterName = null; // will be resolved later by the caller
        this.defaultHeaders = defaultHeaders == null ? new LinkedHashMap<>() : new LinkedHashMap<>(defaultHeaders);
        LOG.debug("AmbariActionClient initialized with header keys: {}", this.defaultHeaders.keySet());
        this.initializeMainConfigurations();
    }
    public AmbariActionClient(ViewContext ctx, String ambariApiBase, String clusterName) {
        this(ctx, ambariApiBase, clusterName, null);
        this.initializeMainConfigurations();
    }

    public int submitGenerateAdhocKeytab(
            String principal,
            String kdcType,           // e.g. "IPA" or null
            String defaultRealm,      // e.g. "DEV21.HADOOP.CLEMLAB.COM" or null
            Integer timeoutSeconds,   // e.g. 600 or null
            String context            // e.g. "Generate ad-hoc keytab ..." or null
    ) throws Exception {
        JsonObject root = new JsonObject();

        // RequestInfo block
        JsonObject requestInfo = new JsonObject();
        requestInfo.addProperty("context",
                (context == null || context.isEmpty())
                        ? "Generate ad-hoc keytab for " + principal
                        : context);

        // operation_level helps Ambari set up the request context
        JsonObject opLevel = new JsonObject();
        opLevel.addProperty("level", "CLUSTER");
        opLevel.addProperty("cluster_name", clusterName);
        requestInfo.add("operation_level", opLevel);

        root.add("RequestInfo", requestInfo);

        // Body block (namespaced properties, as our provider expects)
        JsonObject body = new JsonObject();
        body.addProperty("Clusters/cluster_name", clusterName);
        body.addProperty("AdhocKeytab/principal", principal);
        if (kdcType != null)      body.addProperty("AdhocKeytab/kdc_type", kdcType);
        if (defaultRealm != null) body.addProperty("AdhocKeytab/default_realm", defaultRealm);
        if (timeoutSeconds != null) body.addProperty("AdhocKeytab/timeout", timeoutSeconds);
        root.add("Body", body);

        String url = ambariApiBase + "/clusters/" + encode(clusterName) + "/adhoc_keytab";
        String payload = root.toString();

        try (InputStream is = stream.readFrom(
                url,
                "POST",
                payload,
                withStdHeaders(Map.of("Content-Type", "application/json"))
        )) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            JsonObject ro = o.getAsJsonObject("Requests");
            if (ro == null || !ro.has("id")) {
                throw new IllegalStateException("Unexpected response for adhoc keytab submission: " + json);
            }
            return ro.get("id").getAsInt();
        }
    }

    public boolean waitUntilComplete(int requestId, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);

        while (System.nanoTime() < deadline) {
            String status = getRequestStatus(requestId);
            if ("COMPLETED".equalsIgnoreCase(status)) return true;
            if ("FAILED".equalsIgnoreCase(status) || "ABORTED".equalsIgnoreCase(status) || "TIMEDOUT".equalsIgnoreCase(status)) {
                return false;
            }
            Thread.sleep(1000L);
        }
        return false;
    }

    public String getRequestStatus(int requestId) throws Exception {
        String url = ambariApiBase + "/clusters/" + encode(clusterName) + "/requests/" + requestId;
        try (InputStream is = stream.readFrom(url, "GET", (InputStream) null, withStdHeaders(null))) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            JsonObject r = o.getAsJsonObject("Requests");
            return (r != null && r.has("request_status")) ? r.get("request_status").getAsString() : "UNKNOWN";
        }
    }

    /** Request status + progress for UI polling (request_status, progress_percent, task counts). */
    public java.util.Map<String, Object> getRequestProgress(int requestId) throws Exception {
        String url = ambariApiBase + "/clusters/" + encode(clusterName) + "/requests/" + requestId
                + "?fields=Requests/request_status,Requests/progress_percent,Requests/completed_task_count,Requests/task_count";
        try (InputStream is = stream.readFrom(url, "GET", (InputStream) null, withStdHeaders(null))) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            JsonObject r = o.getAsJsonObject("Requests");
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("requestId", requestId);
            m.put("status", (r != null && r.has("request_status")) ? r.get("request_status").getAsString() : "UNKNOWN");
            m.put("progressPercent", (r != null && r.has("progress_percent")) ? r.get("progress_percent").getAsDouble() : 0.0);
            if (r != null && r.has("completed_task_count")) m.put("completedTasks", r.get("completed_task_count").getAsInt());
            if (r != null && r.has("task_count")) m.put("totalTasks", r.get("task_count").getAsInt());
            return m;
        }
    }

    /**
     * Read issued keytab metadata from structured_out of the latest task for this request.
     * The keytab bytes themselves are kept in Ambari's temporary credential store and exposed
     * through a temporary read/ack endpoint pair.
     */
    public Optional<AdhocKeytabPayloadMetadata> fetchIssuedKeytabPayloadMetadataFromTasks(int requestId) throws Exception {
        // 1) List tasks of the request
        //    We only need the task ids to follow the links.
        String reqUrl = ambariApiBase
                + "/clusters/" + encode(clusterName)
                + "/requests/" + requestId
                + "?fields=Requests/request_status,tasks/Tasks/id";
        String reqJson;
        try (InputStream is = stream.readFrom(reqUrl, "GET", (String) null, withStdHeaders(null))) {
            reqJson = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        JsonObject root = com.google.gson.JsonParser.parseString(reqJson).getAsJsonObject();

        // Ensure request is completed (defensive; your wait() should guarantee this)
        String status = null;
        if (root.has("Requests") && root.getAsJsonObject("Requests").has("request_status")) {
            status = root.getAsJsonObject("Requests").get("request_status").getAsString();
        }
        if (status != null && !"COMPLETED".equalsIgnoreCase(status)) {
            return Optional.empty();
        }

        // Extract task ids
        List<Integer> taskIds = new java.util.ArrayList<>();
        if (root.has("tasks") && root.get("tasks").isJsonArray()) {
            for (var el : root.getAsJsonArray("tasks")) {
                var t = el.getAsJsonObject().getAsJsonObject("Tasks");
                if (t != null && t.has("id")) taskIds.add(t.get("id").getAsInt());
            }
        }
        if (taskIds.isEmpty()) {
            return Optional.empty();
        }

        // Pick a stable choice: highest task id (usually the only one for a server action)
        int taskId = taskIds.stream().max(Integer::compareTo).get();

        // 2) Read that task and pull structured_out.payload_ref
        String taskUrl = ambariApiBase
                + "/clusters/" + encode(clusterName)
                + "/requests/" + requestId
                + "/tasks/" + taskId
                + "?fields=Tasks/structured_out";
        String taskJson;
        try (InputStream is = stream.readFrom(taskUrl, "GET", (String) null, withStdHeaders(null))){
            taskJson = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        var taskRoot = com.google.gson.JsonParser.parseString(taskJson).getAsJsonObject();
        if (taskRoot.has("Tasks")) {
            var tasksObj = taskRoot.getAsJsonObject("Tasks");
            if (tasksObj.has("structured_out")) {
                var so = tasksObj.getAsJsonObject("structured_out");
                if (so.has("payload_ref") && !so.get("payload_ref").isJsonNull()) {
                    String payloadRef = so.get("payload_ref").getAsString();
                    if (payloadRef != null && !payloadRef.isBlank()) {
                        String payloadSha256 = (so.has("payload_sha256") && !so.get("payload_sha256").isJsonNull())
                                ? so.get("payload_sha256").getAsString()
                                : null;
                        Integer kvno = (so.has("kvno") && !so.get("kvno").isJsonNull()) ? so.get("kvno").getAsInt() : null;
                        Boolean created = (so.has("created") && !so.get("created").isJsonNull()) ? so.get("created").getAsBoolean() : null;
                        return Optional.of(new AdhocKeytabPayloadMetadata(payloadRef, payloadSha256, kvno, created));
                    }
                }
            }
        }
        return Optional.empty();
    }

    public String readAdhocKeytabPayload(String payloadRef) throws Exception {
        if (payloadRef == null || payloadRef.isBlank()) {
            throw new IllegalArgumentException("payloadRef cannot be null or blank");
        }

        String url = ambariApiBase
                + "/clusters/" + encode(clusterName)
                + "/adhoc_keytab/payloads/" + encode(payloadRef)
                + "/read";
        try (InputStream is = stream.readFrom(
                url,
                "POST",
                "",
                withStdHeaders(Map.of("Content-Type", "text/plain")))) {
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (body == null || body.isBlank()) {
                throw new IllegalStateException("Ad-hoc keytab read endpoint returned an empty payload");
            }
            return body.trim();
        }
    }

    public void ackAdhocKeytabPayload(String payloadRef) throws Exception {
        if (payloadRef == null || payloadRef.isBlank()) {
            throw new IllegalArgumentException("payloadRef cannot be null or blank");
        }

        String url = ambariApiBase
                + "/clusters/" + encode(clusterName)
                + "/adhoc_keytab/payloads/" + encode(payloadRef)
                + "/ack";
        try (InputStream is = stream.readFrom(
                url,
                "POST",
                "",
                withStdHeaders(Map.of("Content-Type", "text/plain")))) {
            is.readAllBytes();
        }
    }

    /** this method call the clusters api path on ambari server in order to help discover existing cluster
     * the view is not linked to a cluster
     * @return
     * @throws Exception
     */
    public List<String> listClusters() throws Exception {
        String url = ambariApiBase + "/clusters?fields=Clusters/cluster_name";
        LOG.info("Listing clusters from {}", url);
        LOG.debug("Using header keys: {}", this.defaultHeaders.keySet());
        try (InputStream is = readVia(url, "GET", null, withStdHeaders(null))) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            List<String> names = new ArrayList<>();
            if (root.has("items")) {
                JsonArray items = root.getAsJsonArray("items");
                for (JsonElement el : items) {
                    JsonObject item = el.getAsJsonObject();
                    JsonObject clusters = item.getAsJsonObject("Clusters");
                    if (clusters != null && clusters.has("cluster_name")) {
                        String name = clusters.get("cluster_name").getAsString();
                        if (name != null && !name.isBlank()) {
                            names.add(name);
                        }
                    }
                }
            }
            return names;
        }
    }


    /**
     * Ensures that an Ambari local user exists, sets its password, and optionally grants
     * AMBARI.ADMINISTRATOR privileges. All calls rely on the caller-provided cookie session.
     *
     * @param username      Ambari local username
     * @param password      password to set
     * @param requireAdmin  true to ensure the user has AMBARI.ADMINISTRATOR
     */
    public void createWebhookAmbariUser(
            String username,
            String password,
            boolean requireAdmin
    ) throws Exception {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        if (!userExists(username)) {
            LOG.info("User '{}' not found; creating.", username);
            createUser(username, password);
        } else {
            LOG.info("User '{}' exists; reconciling password.", username);
            if (credentialsWork(username, password)){
                LOG.info("Store password for user {} is ok. Skipping action", username);
            }else{
                deleteLocalAuthentication(username);
                createUser(username, password);
            }
        }

        if (requireAdmin) {
            boolean isAdmin = hasAmbariAdminPrivilege(username);
            if (!isAdmin) {
                LOG.info("User '{}' lacks {}; granting.", username, permissionName);
                grantAmbariAdminPrivilege(username);
            } else {
                LOG.info("User '{}' already has {}.", username, permissionName);
            }
        }
    }

    /** helper methods for ambari client call **/
    /**
     * encode the name a cluster
     * @param s
     * @return
     */
    private String encode(String s) {
        return URLEncoder.encode(s,StandardCharsets.UTF_8);
    }

    private Map<String,String> withStdHeaders(Map<String,String> extra) {
        Map<String,String> h = new LinkedHashMap<>();
        if (this.defaultHeaders != null) h.putAll(this.defaultHeaders); // Cookie/Authorization
        if (extra != null) h.putAll(extra);                              // e.g., Content-Type
        h.putIfAbsent("X-Requested-By", "ambari");
        return h;
    }

    /**
     * Read-path dispatch that honours {@link #verifySsl}. When verification is on (default), reads
     * go through the View's {@link URLStreamProvider} exactly as before (server-wide truststore).
     * When off, reads go through a trust-all HTTPS client — used only for the remote-Ambari
     * discovery path of a REMOTE context that opted into "ignore self-signed". GET bodies are null.
     */
    private InputStream readVia(String url, String method, String body, Map<String,String> headers) throws Exception {
        if (verifySsl) {
            return stream.readFrom(url, method, body, headers);
        }
        return insecureReadFrom(url, method, body, headers);
    }

    /** Trust-all HTTPS read (no cert/hostname verification). Throws with the HTTP status in the
     * message on non-2xx, so callers' existing 401/403/404 string checks keep working. */
    private InputStream insecureReadFrom(String url, String method, String body, Map<String,String> headers) throws Exception {
        javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
        sc.init(null, TRUST_ALL, new java.security.SecureRandom());
        javax.net.ssl.SSLParameters sp = new javax.net.ssl.SSLParameters();
        sp.setEndpointIdentificationAlgorithm(null); // also skip hostname verification (self-signed/IP hosts)
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .sslContext(sc).sslParameters(sp)
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();
        java.net.http.HttpRequest.BodyPublisher pub = (body == null)
                ? java.net.http.HttpRequest.BodyPublishers.noBody()
                : java.net.http.HttpRequest.BodyPublishers.ofString(body);
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(30))
                .method(method == null ? "GET" : method, pub);
        if (headers != null) {
            for (Map.Entry<String,String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                try { b.header(e.getKey(), e.getValue()); } catch (IllegalArgumentException ignore) { /* restricted header */ }
            }
        }
        java.net.http.HttpResponse<byte[]> resp =
                client.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        int code = resp.statusCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " from " + url);
        }
        return new java.io.ByteArrayInputStream(resp.body());
    }

    /**
     * Best-effort remote-cluster info for display: the Ambari server version (root service, no
     * cluster needed) plus the running stack name + current repository version for {@code cluster}
     * (when non-null). Never throws — missing pieces are simply omitted from the returned map.
     * Honours {@link #verifySsl}. Keys: {@code ambariVersion}, {@code stackName}, {@code stackVersion}.
     */
    public Map<String,String> getServerAndStackInfo(String cluster) {
        Map<String,String> info = new LinkedHashMap<>();
        // Ambari server version — RootServiceComponents, independent of any cluster.
        try {
            String url = ambariApiBase + "/services/AMBARI/components/AMBARI_SERVER"
                    + "?fields=RootServiceComponents/component_version";
            try (InputStream is = readVia(url, "GET", null, withStdHeaders(null))) {
                JsonObject root = JsonParser.parseString(new String(is.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject rsc = root.getAsJsonObject("RootServiceComponents");
                if (rsc != null && rsc.has("component_version") && !rsc.get("component_version").isJsonNull()) {
                    info.put("ambariVersion", rsc.get("component_version").getAsString());
                }
            }
        } catch (Exception e) {
            LOG.debug("getServerAndStackInfo: Ambari version fetch failed: {}", e.toString());
        }
        if (cluster != null && !cluster.isBlank()) {
            // Stack name, e.g. "ODP-1.3"
            try {
                String url = ambariApiBase + "/clusters/" + encode(cluster) + "?fields=Clusters/version";
                try (InputStream is = readVia(url, "GET", null, withStdHeaders(null))) {
                    JsonObject root = JsonParser.parseString(new String(is.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonObject c = root.getAsJsonObject("Clusters");
                    if (c != null && c.has("version") && !c.get("version").isJsonNull()) {
                        info.put("stackName", c.get("version").getAsString());
                    }
                }
            } catch (Exception e) {
                LOG.debug("getServerAndStackInfo: stack name fetch failed: {}", e.toString());
            }
            // Current stack repository version, e.g. "1.3.2.0-25". NOTE: ClusterStackVersions/
            // repository_version is the internal repo-version ID (e.g. 151); the human-readable
            // version string lives on the nested repository_versions/RepositoryVersions resource.
            try {
                String url = ambariApiBase + "/clusters/" + encode(cluster)
                        + "/stack_versions?ClusterStackVersions/state=CURRENT"
                        + "&fields=repository_versions/RepositoryVersions/repository_version";
                try (InputStream is = readVia(url, "GET", null, withStdHeaders(null))) {
                    JsonObject root = JsonParser.parseString(new String(is.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonArray items = root.getAsJsonArray("items");
                    if (items != null && items.size() > 0) {
                        JsonArray rvs = items.get(0).getAsJsonObject().getAsJsonArray("repository_versions");
                        if (rvs != null && rvs.size() > 0) {
                            JsonObject rv = rvs.get(0).getAsJsonObject().getAsJsonObject("RepositoryVersions");
                            if (rv != null && rv.has("repository_version") && !rv.get("repository_version").isJsonNull()) {
                                info.put("stackVersion", rv.get("repository_version").getAsString());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.debug("getServerAndStackInfo: stack version fetch failed: {}", e.toString());
            }
        }
        return info;
    }

    /**
     * Deletes the user's LOCAL authentication source (admin-level operation).
     * Safe to call when LOCAL does not exist (404 → ignored).
     */
    public void deleteLocalAuthentication(String username) throws Exception {
        final String url = ambariApiBase + "/users/" + encode(username) + "/authentications/LOCAL";
        try (InputStream is = stream.readFrom(
                url, "DELETE", (String) null, withStdHeaders(Map.of("X-Requested-By", "ambari")))) {
            // consume response
            is.readAllBytes();
        } catch (Exception e) {
            final String msg = String.valueOf(e.getMessage());
            // Normalize "not found" to success → nothing to delete
            if (msg.contains("404")) return;
            throw e;
        }
    }
    /**
     * convert a persisteObj of headers to a Map<String,String> usable by other methods
     * @param persistedHeadersObj
     * @return
     */
    @SuppressWarnings("unchecked")
    public static Map<String,String> toAuthHeaders(Object persistedHeadersObj) {
        if (persistedHeadersObj == null) return Map.of();

        Map<String,Object> persisted =
                (persistedHeadersObj instanceof Map) ? (Map<String,Object>) persistedHeadersObj : Map.of();
        persisted = decodePersistedHeaders(persisted);

        // normalize keys to lowercase for lookup
        Map<String,Object> lc = new LinkedHashMap<>();
        for (Map.Entry<String,Object> e : persisted.entrySet()) {
            lc.put(e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }

        String cookie = null, authorization = null;

        Object c = lc.get("cookie");
        if (c instanceof List<?> l && !l.isEmpty()) {
            cookie = l.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("; "));
        } else if (c instanceof String s) {
            cookie = s;
        }

        Object a = lc.get("authorization");
        if (a instanceof List<?> l && !l.isEmpty()) {
            authorization = String.valueOf(l.get(0));
        } else if (a instanceof String s) {
            authorization = s;
        }

        Map<String,String> h = new LinkedHashMap<>();
        if (cookie != null && !cookie.isBlank())        h.put("Cookie", cookie);
        if (authorization != null && !authorization.isBlank()) h.put("Authorization", authorization);
        return h;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decodePersistedHeaders(Map<String, Object> persisted) {
        Object format = persisted.get(PERSISTED_HEADERS_FORMAT_KEY);
        Object payload = persisted.get(PERSISTED_HEADERS_PAYLOAD_KEY);
        if (!PERSISTED_HEADERS_FORMAT_ENCRYPTED.equals(format) || !(payload instanceof String payloadString) || payloadString.isBlank()) {
            return persisted;
        }

        try {
            byte[] encrypted = Base64.getDecoder().decode(payloadString);
            String decrypted = new String(HEADER_ENCRYPTION.decrypt(encrypted), StandardCharsets.UTF_8);
            return GSON.fromJson(decrypted, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode persisted Ambari auth headers", e);
        }
    }

    public static String describePersistedHeaders(Object persistedHeadersObj) {
        try {
            return toAuthHeaders(persistedHeadersObj).keySet().toString();
        } catch (Exception e) {
            return "[unreadable]";
        }
    }

    /**
     * helper method to check if a user does already exist in ambari's server internal database
     * @param username the username to check existence for
     * @return true or false
     */
    public boolean userExists(String username) throws Exception {
        final String url = ambariApiBase + "/users/" + encode(username);
        try (InputStream is = stream.readFrom(url, "GET", (String) null, withStdHeaders(null))) {
            final String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            // Success shape:
            // { "href": ".../api/v1/users/<user>", "Users": { "user_name": "<user>", ... } }
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("Users")) {
                JsonObject users = root.getAsJsonObject("Users");
                String name = users.has("user_name") ? users.get("user_name").getAsString() : null;
                return name != null && name.equals(username);
            }
            // Error shape often is: { "status":404, "message":"..." }
            if (root.has("status") && root.get("status").getAsInt() == 404) {
                return false;
            }
            // Defensive: if neither shape matched, treat as not found.
            return false;
        } catch (Exception e) {
            // Some Ambari builds throw on 404; normalize that to "not exists".
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("HTTP 404") || msg.contains("404")) {
                return false;
            }
            throw e;
        }
    }

    /**
     * tries to authenticate using the given credentials
     * this method is used to check if the webhook user's stored password is correct
     * if administrator to change the password, the ambari view backend will override it
     * @param username
     * @param password
     * @return
     * @throws Exception
     */
    public boolean credentialsWork(String username, String password) throws Exception {
        final String url = ambariApiBase + "/users/" + encode(username);

        // Build a one-off Basic header; withStdHeaders will merge it for this call only.
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));

        try (InputStream is = stream.readFrom(url, "GET", (String) null,
                withStdHeaders(Map.of("Authorization", basic)))) {
            // 200 → the credentials are valid
            is.readAllBytes();
            return true;
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            // Ambari tends to return 401/403 on wrong creds; treat both as "invalid"
            if (msg.contains("401") || msg.contains("403")) return false;
            // If Ambari returns 404 here it usually means the user exists check was wrong,
            // but keep it as "invalid" to be safe (we will reconcile below).
            if (msg.contains("404")) return false;
            throw e;
        }
    }

    /**
     * check is a user has do exist in the ambari internal databases
     * @param username
     * @return
     * @throws Exception
     */
    public boolean userHasLocalAuthentication(String username) throws Exception {
        final String url = ambariApiBase
                + "/users/" + encode(username)
                + "/authentications?fields=UserAuthenticationSource/authentication_type";

        try (InputStream is = stream.readFrom(url, "GET", (String) null, withStdHeaders(null))) {
            final String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();

            // 404 style payload?
            if (root.has("status") && root.get("status").getAsInt() == 404) {
                return false;
            }

            if (root.has("items") && root.get("items").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("items")) {
                    JsonObject item = el.getAsJsonObject();
                    if (!item.has("UserAuthenticationSource")) continue;
                    JsonObject uas = item.getAsJsonObject("UserAuthenticationSource");
                    String t = uas.has("authentication_type") ? uas.get("authentication_type").getAsString() : null;
                    if ("LOCAL".equalsIgnoreCase(t)) return true;
                }
            }
            return false;
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("404")) return false;
            throw e;
        }
    }
    /**
     * Creates a local Ambari user via POST /api/v1/users.
     * @param username
     * @param password
     * @throws Exception
     */
    public void createUser(String username, String password) throws Exception {
        JsonObject root  = new JsonObject();
        JsonObject users = new JsonObject();
        users.addProperty("user_name", username);
        users.addProperty("password",  password);
        users.addProperty("active",    true);
        root.add("Users", users);

        String url = ambariApiBase + "/users";

        try (InputStream is = stream.readFrom(
                url,
                "POST",
                root.toString(),
                withStdHeaders(Map.of("Content-Type", "application/json"))
        )) {
            String resp = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            LOG.debug("createUser('{}') response: {}", username, resp);
        }
    }

    /**
     * Sets (or resets) a user's password via PUT /api/v1/users/{user}.
     * @param username
     * @param newPassword
     * @throws Exception
     */
    public void setUserPassword(String username, String newPassword) throws Exception {
        // tries to check first is the given password does work
        JsonObject root  = new JsonObject();
        JsonObject users = new JsonObject();
        users.addProperty("password", newPassword);
        root.add("Users", users);
        String url = ambariApiBase + "/users/" + encode(username);
        try (InputStream is = stream.readFrom(
                url,
                "PUT",
                root.toString(),
                withStdHeaders(Map.of("Content-Type", "application/json"))
        )) {
            String resp = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            LOG.debug("setUserPassword('{}') response: {}", username, resp);
        }
    }
    /**
     * Checks if the user has AMBARI.ADMINISTRATOR privilege.
     * helper method to check the privileges attributed to a user to check if we need to add privileges
     * @param username the
     * @return
     */
    private boolean hasAmbariAdminPrivilege(String username) throws IOException {
        String url = ambariApiBase
                + "/users/" + encode(username)
                + "/privileges?fields=PrivilegeInfo/permission_name,PrivilegeInfo/type";

        try (InputStream is = stream.readFrom(
                url,
                "GET",
                (String) null,
                withStdHeaders(null)
        )) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (!root.has("items") || !root.get("items").isJsonArray()) return false;

            for (JsonElement el : root.getAsJsonArray("items")) {
                JsonObject item = el.getAsJsonObject();
                JsonObject pi   = item.getAsJsonObject("PrivilegeInfo");
                if (pi == null) continue;
                String type = pi.has("type") ? pi.get("type").getAsString() : null;
                String perm = pi.has("permission_name") ? pi.get("permission_name").getAsString() : null;
                if ("AMBARI".equalsIgnoreCase(type) && permissionName.equalsIgnoreCase(perm)) {
                    return true;
                }
            }
            return false;
        }
    }
    /**
     * Grants AMBARI.ADMINISTRATOR to the user via POST /api/v1/privileges.
     * @param username
     * @throws Exception
     */
    public void grantAmbariAdminPrivilege(String username) throws Exception {
        String url = ambariApiBase + "/privileges";

        JsonObject root  = new JsonObject();
        JsonObject info  = new JsonObject();
        info.addProperty("permission_name", "AMBARI.ADMINISTRATOR");
        info.addProperty("principal_name",  username);
        info.addProperty("principal_type",  "USER");
        info.addProperty("type",            "AMBARI");
        root.add("PrivilegeInfo", info);

        try (InputStream is = stream.readFrom(
                url,
                "POST",
                root.toString(),
                withStdHeaders(Map.of("Content-Type", "application/json"))
        )) {
            String resp = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            LOG.debug("grantAmbariAdminPrivilege('{}') response: {}", username, resp);
        }
    }

    /**
     * static method to convert MultiValueMap from javax rs core to Classical MAP
     * @param h
     * @return
     */
    public static Map<String, Object> headersToPersistableMap(MultivaluedMap<String,String> h) {
        if (h == null) return Map.of();

        Map<String, Object> out = new LinkedHashMap<>();

        // only keep the stuff Ambari actually needs
        Set<String> allowed = Set.of(
                "cookie",
                "authorization",
                "x-requested-by"
        );

        h.forEach((k, v) -> {
            if (k == null) return;
            String key = k.toLowerCase(Locale.ROOT);
            if (!allowed.contains(key)) {
                return; // ignore everything else
            }
            List<String> vals = (v == null) ? List.of() : new ArrayList<>(v);
            out.put(key, vals);
        });

        if (out.isEmpty()) {
            return Map.of();
        }

        String payloadJson = GSON.toJson(out);
        String encryptedPayload = Base64.getEncoder().encodeToString(
                HEADER_ENCRYPTION.encrypt(payloadJson.getBytes(StandardCharsets.UTF_8))
        );

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(PERSISTED_HEADERS_FORMAT_KEY, PERSISTED_HEADERS_FORMAT_ENCRYPTED);
        envelope.put(PERSISTED_HEADERS_PAYLOAD_KEY, encryptedPayload);

        try {
            LoggerFactory.getLogger(CommandService.class)
                    .info("Persisted caller headers (filtered): {}", out.keySet());
        } catch (Exception ignore) {}

        return envelope;
    }

    /**
     * resolve non blank values
     * @param vals
     * @return
     */
    public static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    /**
     * Returns the hostnames running a given Ambari service component.
     * Uses: GET /api/v1/clusters/{cluster}/services/{service}/components/{component}
     *             ?fields=host_components/HostRoles/host_name
     */
    public List<String> getComponentHosts(String cluster, String service, String component) throws Exception {
        String url = ambariApiBase + "/clusters/" + encode(cluster)
                + "/services/" + encode(service)
                + "/components/" + encode(component)
                + "?fields=host_components/HostRoles/host_name";
        try (InputStream is = readVia(url, "GET", null, withStdHeaders(null))) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            List<String> hosts = new ArrayList<>();
            JsonArray hostComponents = root.getAsJsonArray("host_components");
            if (hostComponents != null) {
                for (JsonElement el : hostComponents) {
                    JsonObject hostRoles = el.getAsJsonObject().getAsJsonObject("HostRoles");
                    if (hostRoles != null && hostRoles.has("host_name")) {
                        hosts.add(hostRoles.get("host_name").getAsString());
                    }
                }
            }
            return hosts;
        }
    }

    /**
     * Returns true if Ambari reports any host-component instance of {@code component} has STALE
     * configs — i.e. a config it depends on changed since the component last (re)started, so an
     * Ambari "Restart required" is pending. Lets callers restart only when genuinely needed (e.g. a
     * prior run wrote the config but never restarted) instead of unconditionally. Fails safe:
     * returns false on any query error so the caller can fall back to its own change signal.
     */
    public boolean componentRestartRequired(String cluster, String service, String component) {
        try {
            String url = ambariApiBase + "/clusters/" + encode(cluster)
                    + "/host_components?HostRoles/component_name=" + encode(component)
                    + "&HostRoles/stale_configs=true&fields=HostRoles/stale_configs";
            try (InputStream is = stream.readFrom(url, "GET", (String) null, withStdHeaders(null))) {
                JsonObject root = JsonParser.parseString(
                        new String(is.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray items = root.getAsJsonArray("items");
                boolean stale = items != null && items.size() > 0;
                LOG.info("componentRestartRequired({} {}/{}): staleConfigs={}", cluster, service, component, stale);
                return stale;
            }
        } catch (Exception e) {
            LOG.warn("componentRestartRequired({}/{}) query failed: {} — assuming not stale", service, component, e.toString());
            return false;
        }
    }

    public String getDesiredConfigProperty(String cluster, String type, String key) throws Exception {
        Objects.requireNonNull(cluster, "cluster");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");

        // 1) get desired tag for this config type
        //    /api/v1/clusters/{cluster}?fields=Clusters/desired_configs/{type}
        String url1 = ambariApiBase + "/clusters/" + encode(cluster)
                + "?fields=" + encode("Clusters/desired_configs/" + type);
        String json1;
        try (InputStream is = readVia(url1, "GET", null, withStdHeaders(null))) {
            json1 = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonObject root1 = JsonParser.parseString(json1).getAsJsonObject();
        JsonObject desiredCfgs = root1.getAsJsonObject("Clusters").getAsJsonObject("desired_configs");
        if (desiredCfgs == null || !desiredCfgs.has(type)) return null;
        String tag = desiredCfgs.getAsJsonObject(type).get("tag").getAsString();

        // 2) read the configuration for that type+tag
        //    /api/v1/clusters/{cluster}/configurations?type={type}&tag={tag}
        String url2 = ambariApiBase + "/clusters/" + encode(cluster)
                + "/configurations?type=" + encode(type) + "&tag=" + encode(tag);
        String json2;
        try (InputStream is = readVia(url2, "GET", null, withStdHeaders(null))) {
            json2 = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonObject root2 = JsonParser.parseString(json2).getAsJsonObject();
        JsonArray items = root2.getAsJsonArray("items");
        if (items == null || items.size() == 0) return null;

        JsonObject cfg = items.get(0).getAsJsonObject();
        JsonObject props = cfg.getAsJsonObject("properties");

        if (props == null || !props.has(key)) return null;

        return props.get(key).getAsString();
    }

    /**
     * Submit a Ranger plugin repository configuration request via:
     *
     *   POST /api/v1/clusters/{cluster}/ranger_plugin_repository
     *
     * Expected JSON:
     *
     * {
     *   "RequestInfo": {
     *     "context": "Configure Ranger plugin for Trino"
     *   },
     *   "RangerPlugin": {
     *     "rangerRepositoryName": "...",
     *     "serviceType": "trino",
     *     "pluginUserName": "trino_plugin",
     *     "pluginUserPassword": "SuperSecret123!",
     *     "timeoutSeconds": 600,
     *     "repositoryDescription": "optional"
     *   }
     * }
     */
    /**
     * Submit a Ranger policy grant to the Ambari server (POST {@code /clusters/{c}/ranger_policy}).
     * The server-side {@code EnsureRangerPolicyServerAction} grants {@code userName} the given
     * {@code accessTypes} on {@code rangerServiceName} for the given {@code resourcesJson},
     * using the Ranger admin credentials it holds — so the caller never needs the password.
     * Returns the Ambari request id (poll with {@link #waitUntilComplete}).
     *
     * @param rangerServiceName target Ranger service repo (e.g. {@code <cluster>_atlas})
     * @param userName          user to grant
     * @param accessTypes       comma-separated access types (e.g. {@code entity-read})
     * @param resourcesJson     JSON object of resourceKey → array of values
     * @param policyNameHint    policy name to use if a new policy must be created
     * @param policyDescription optional description
     * @param timeoutSeconds    propagation-poll budget passed to the server action
     * @param context           Ambari request context label
     */
    public int submitRangerPolicyGrant(
            String rangerServiceName,
            String userName,
            String accessTypes,
            String resourcesJson,
            String policyNameHint,
            String policyDescription,
            Integer timeoutSeconds,
            String context
    ) throws Exception {
        Objects.requireNonNull(clusterName, "clusterName must not be null for Ranger policy grant");
        Objects.requireNonNull(rangerServiceName, "rangerServiceName");
        Objects.requireNonNull(userName, "userName");

        JsonObject root = new JsonObject();

        JsonObject requestInfo = new JsonObject();
        requestInfo.addProperty("context",
                (context == null || context.isBlank()) ? "Ensure Ranger policy" : context);
        root.add("RequestInfo", requestInfo);

        JsonObject rangerPolicy = new JsonObject();
        rangerPolicy.addProperty("rangerServiceName", rangerServiceName);
        rangerPolicy.addProperty("userName", userName);
        rangerPolicy.addProperty("accessTypes", accessTypes);
        rangerPolicy.addProperty("resourcesJson", resourcesJson);
        rangerPolicy.addProperty("policyNameHint", policyNameHint);
        if (policyDescription != null && !policyDescription.isBlank()) {
            rangerPolicy.addProperty("policyDescription", policyDescription);
        }
        if (timeoutSeconds != null) {
            rangerPolicy.addProperty("timeoutSeconds", timeoutSeconds);
        }
        root.add("RangerPolicy", rangerPolicy);

        String url = ambariApiBase + "/clusters/" + encode(clusterName) + "/ranger_policy";
        String payload = root.toString();

        LOG.info("Submitting Ranger policy grant to {} for service='{}', user='{}', access='{}', policyNameHint='{}'",
                url, rangerServiceName, userName, accessTypes, policyNameHint);

        try (InputStream is = stream.readFrom(
                url, "POST", payload, withStdHeaders(Map.of("Content-Type", "application/json")))) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            JsonObject ro = o.getAsJsonObject("Requests");
            if (ro == null || !ro.has("id")) {
                LOG.error("Unexpected response for Ranger policy grant submission: {}", json);
                throw new IllegalStateException("Unexpected response for Ranger policy grant submission: " + json);
            }
            int id = ro.get("id").getAsInt();
            LOG.info("Ranger policy grant request accepted by Ambari, request id={}", id);
            return id;
        }
    }

    public int submitRangerPluginRepository(
            String rangerRepositoryName,
            String serviceType,
            String pluginUserName,
            String pluginUserPassword,
            Integer timeoutSeconds,
            String context,
            String repositoryDescription
    ) throws Exception {
        Objects.requireNonNull(clusterName, "clusterName must not be null for Ranger repository creation");
        Objects.requireNonNull(rangerRepositoryName, "rangerRepositoryName");

        JsonObject root = new JsonObject();

        // ----- RequestInfo -----
        JsonObject requestInfo = new JsonObject();
        String ctxText = (context == null || context.isBlank())
                ? ("Configure Ranger plugin for repository " + rangerRepositoryName)
                : context;
        requestInfo.addProperty("context", ctxText);
        root.add("RequestInfo", requestInfo);

        // ----- RangerPlugin block -----
        JsonObject rangerPlugin = new JsonObject();
        rangerPlugin.addProperty("rangerRepositoryName", rangerRepositoryName);

        if (serviceType != null && !serviceType.isBlank()) {
            rangerPlugin.addProperty("serviceType", serviceType);
        }
        if (pluginUserName != null && !pluginUserName.isBlank()) {
            rangerPlugin.addProperty("pluginUserName", pluginUserName);
        }
        if (pluginUserPassword != null && !pluginUserPassword.isBlank()) {
            rangerPlugin.addProperty("pluginUserPassword", pluginUserPassword);
        }
        if (timeoutSeconds != null) {
            rangerPlugin.addProperty("timeoutSeconds", timeoutSeconds);
        }
        if (repositoryDescription != null && !repositoryDescription.isBlank()) {
            rangerPlugin.addProperty("repositoryDescription", repositoryDescription);
        }

        root.add("RangerPlugin", rangerPlugin);

        String url = ambariApiBase
                + "/clusters/" + encode(clusterName)
                + "/ranger_plugin_repository";

        String payload = root.toString();

        LOG.info("Submitting Ranger plugin repository config to {} for repo='{}', serviceType='{}', pluginUser='{}'",
                url, rangerRepositoryName, serviceType, pluginUserName);

        try (InputStream is = stream.readFrom(
                url,
                "POST",
                payload,
                withStdHeaders(Map.of("Content-Type", "application/json"))
        )) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            JsonObject ro = o.getAsJsonObject("Requests");
            if (ro == null || !ro.has("id")) {
                LOG.error("Unexpected response for Ranger plugin repository submission: {}", json);
                throw new IllegalStateException("Unexpected response for Ranger plugin repository submission: " + json);
            }
            int id = ro.get("id").getAsInt();
            LOG.info("Ranger plugin repository request accepted by Ambari, request id={}", id);
            return id;
        }
    }

    public int submitRangerPluginRepository(
            String rangerRepositoryName,
            String serviceType,
            String pluginUserName,
            String pluginUserPassword,
            Integer timeoutSeconds,
            String context,
            String repositoryDescription,
            Map<String, String> rangerServiceConfigs   // <--- NEW ARG
    ) throws Exception {
        Objects.requireNonNull(clusterName, "clusterName must not be null for Ranger repository creation");
        Objects.requireNonNull(rangerRepositoryName, "rangerRepositoryName");

        JsonObject root = new JsonObject();

        // ----- RequestInfo -----
        JsonObject requestInfo = new JsonObject();
        String ctxText = (context == null || context.isBlank())
                ? ("Configure Ranger plugin for repository " + rangerRepositoryName)
                : context;
        requestInfo.addProperty("context", ctxText);
        root.add("RequestInfo", requestInfo);

        // ----- RangerPlugin block -----
        JsonObject rangerPlugin = new JsonObject();
        rangerPlugin.addProperty("rangerRepositoryName", rangerRepositoryName);

        if (serviceType != null && !serviceType.isBlank()) {
            rangerPlugin.addProperty("serviceType", serviceType);
        }
        if (pluginUserName != null && !pluginUserName.isBlank()) {
            rangerPlugin.addProperty("pluginUserName", pluginUserName);
        }
        if (pluginUserPassword != null && !pluginUserPassword.isBlank()) {
            rangerPlugin.addProperty("pluginUserPassword", pluginUserPassword);
        }
        if (timeoutSeconds != null) {
            rangerPlugin.addProperty("timeoutSeconds", timeoutSeconds);
        }
        if (repositoryDescription != null && !repositoryDescription.isBlank()) {
            rangerPlugin.addProperty("repositoryDescription", repositoryDescription);
        }

        // ----- NEW: extra service configs from backend (jdbc.url, driver, etc.) -----
        if (rangerServiceConfigs != null && !rangerServiceConfigs.isEmpty()) {
            JsonObject cfg = new JsonObject();
            for (Map.Entry<String, String> e : rangerServiceConfigs.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k == null || k.isBlank() || v == null) {
                    continue;
                }
                cfg.addProperty(k, v);
            }
            rangerPlugin.addProperty("serviceConfigs", cfg.toString());
        }

        root.add("RangerPlugin", rangerPlugin);

        String url = ambariApiBase
                + "/clusters/" + encode(clusterName)
                + "/ranger_plugin_repository";

        String payload = root.toString();

        LOG.info("Submitting Ranger plugin repository config to {} for repo='{}', serviceType='{}', pluginUser='{}'",
                url, rangerRepositoryName, serviceType, pluginUserName);

        try (InputStream is = stream.readFrom(
                url,
                "POST",
                payload,
                withStdHeaders(Map.of("Content-Type", "application/json"))
        )) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            JsonObject ro = o.getAsJsonObject("Requests");
            if (ro == null || !ro.has("id")) {
                LOG.error("Unexpected response for Ranger plugin repository submission: {}", json);
                throw new IllegalStateException("Unexpected response for Ranger plugin repository submission: " + json);
            }
            int id = ro.get("id").getAsInt();
            LOG.info("Ranger plugin repository request accepted by Ambari, request id={}", id);
            return id;
        }
    }
    /**
     * Fetches the full properties map for the current desired configuration
     * of the given Ambari config {@code type} (e.g., "core-site", "hdfs-site").
     *
     * <p>Flow:
     * 1) GET /api/v1/clusters/{cluster}?fields=Clusters/desired_configs/{type} → resolve desired tag
     * 2) GET /api/v1/clusters/{cluster}/configurations?type={type}&tag={tag} → read properties
     *
     * @param cluster the Ambari cluster name
     * @param type the Ambari config type (e.g., "core-site")
     * @return an immutable Map of key→value properties (empty if none found)
     * @throws Exception on transport or parsing errors
     */
    public Map<String, String> getDesiredConfigProperties(String cluster, String type) throws Exception {
        Objects.requireNonNull(cluster, "cluster");
        Objects.requireNonNull(type, "type");

        // 1) desired tag
        final String urlDesired = ambariApiBase + "/clusters/" + encode(cluster)
                + "?fields=" + encode("Clusters/desired_configs/" + type);
        String jsonDesired;
        try (InputStream is = readVia(urlDesired, "GET", null, withStdHeaders(null))) {
            jsonDesired = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        JsonObject rootDesired = JsonParser.parseString(jsonDesired).getAsJsonObject();
        JsonObject desiredCfgs = rootDesired.getAsJsonObject("Clusters") != null
                ? rootDesired.getAsJsonObject("Clusters").getAsJsonObject("desired_configs")
                : null;

        if (desiredCfgs == null || !desiredCfgs.has(type)) {
            LOG.info("No desired config found for type '{}' on cluster '{}'", type, cluster);
            return Collections.emptyMap();
        }

        String tag = desiredCfgs.getAsJsonObject(type).get("tag").getAsString();

        // 2) config with that tag
        final String urlCfg = ambariApiBase + "/clusters/" + encode(cluster)
                + "/configurations?type=" + encode(type) + "&tag=" + encode(tag);
        String jsonCfg;
        try (InputStream is = readVia(urlCfg, "GET", null, withStdHeaders(null))) {
            jsonCfg = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        JsonObject rootCfg = JsonParser.parseString(jsonCfg).getAsJsonObject();
        JsonArray items = rootCfg.getAsJsonArray("items");
        if (items == null || items.size() == 0) {
            LOG.info("Configuration items empty for type '{}' (tag='{}') on cluster '{}'", type, tag, cluster);
            return Collections.emptyMap();
        }

        JsonObject cfg0 = items.get(0).getAsJsonObject();
        JsonObject props = cfg0.getAsJsonObject("properties");
        if (props == null || props.entrySet().isEmpty()) {
            LOG.info("No properties present for type '{}' (tag='{}') on cluster '{}'", type, tag, cluster);
            return Collections.emptyMap();
        }

        Map<String, String> result = new LinkedHashMap<>(props.entrySet().size());
        for (Map.Entry<String, com.google.gson.JsonElement> e : props.entrySet()) {
            result.put(e.getKey(), e.getValue() == null || e.getValue().isJsonNull() ? null : e.getValue().getAsString());
        }

        LOG.debug("Loaded {} properties for type '{}' (tag='{}') on cluster '{}'", result.size(), type, tag, cluster);
        return Collections.unmodifiableMap(result);
    }

    /**
     * Merges {@code propsToSet} into the current properties of the Ambari config type
     * {@code type} and writes a new tag via {@code PUT /clusters/{cluster}}.
     *
     * <p>Idempotent: if every key/value in {@code propsToSet} is already present at the
     * current desired tag, no write happens and the method returns {@code false}.
     *
     * <p>Tag scheme: {@code "version-" + System.currentTimeMillis() + "-" + (suffix or "kdps")}.
     * Ambari treats each tag write as a new config version, so re-running KDPS deploys (which
     * fire this method) bumps the version only on real diffs.
     *
     * @param cluster     Ambari cluster name
     * @param type        config type, e.g. {@code ranger-tagsync-site}
     * @param propsToSet  keys to merge in (null/empty values cause the key to be REMOVED;
     *                    non-null values overwrite existing values). Never null; empty map
     *                    is a no-op.
     * @param tagSuffix   optional suffix for the new tag (e.g. {@code "om-tagsync"}); may be null
     * @return {@code true} when a new tag was written, {@code false} when no diff
     * @throws Exception on transport or parsing errors
     */
    public boolean updateClusterConfig(String cluster,
                                       String type,
                                       Map<String, String> propsToSet,
                                       String tagSuffix) throws Exception {
        Objects.requireNonNull(cluster, "cluster");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(propsToSet, "propsToSet");
        if (propsToSet.isEmpty()) {
            LOG.debug("updateClusterConfig: empty propsToSet for type '{}', no-op", type);
            return false;
        }

        Map<String, String> current = new LinkedHashMap<>(getDesiredConfigProperties(cluster, type));
        // Detect a real diff. An empty/null value is a delete request.
        boolean changed = false;
        for (Map.Entry<String, String> e : propsToSet.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || k.isBlank()) continue;
            if (v == null || v.isEmpty()) {
                if (current.containsKey(k)) {
                    current.remove(k);
                    changed = true;
                }
            } else if (!v.equals(current.get(k))) {
                current.put(k, v);
                changed = true;
            }
        }
        if (!changed) {
            LOG.info("updateClusterConfig: no changes for type '{}' on cluster '{}' (idempotent skip)", type, cluster);
            return false;
        }

        String safeSuffix = (tagSuffix == null || tagSuffix.isBlank()) ? "kdps" : tagSuffix.replaceAll("[^A-Za-z0-9._-]", "-");
        String newTag = "version-" + System.currentTimeMillis() + "-" + safeSuffix;

        // Build the desired PUT body: {"Clusters":{"desired_config":{"type":"...","tag":"...","properties":{...}}}}
        JsonObject propsJson = new JsonObject();
        for (Map.Entry<String, String> e : current.entrySet()) {
            propsJson.addProperty(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        JsonObject desired = new JsonObject();
        desired.addProperty("type", type);
        desired.addProperty("tag", newTag);
        desired.add("properties", propsJson);
        JsonObject clusters = new JsonObject();
        clusters.add("desired_config", desired);
        JsonObject root = new JsonObject();
        root.add("Clusters", clusters);

        String url = ambariApiBase + "/clusters/" + encode(cluster);
        String payload = root.toString();
        LOG.info("updateClusterConfig: PUT {} type='{}' tag='{}' propCount={}", url, type, newTag, current.size());

        try (InputStream is = stream.readFrom(
                url,
                "PUT",
                payload,
                withStdHeaders(Map.of("Content-Type", "application/json"))
        )) {
            String resp = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            LOG.debug("updateClusterConfig response ({}/{}): {}", cluster, type, resp.length() > 200 ? resp.substring(0, 200) + "..." : resp);
        }
        return true;
    }

    /**
     * Submits a component-level RESTART command to Ambari via
     * {@code POST /clusters/{cluster}/requests}. Returns the Ambari request id which the
     * caller can pass to {@link #waitUntilComplete(int, long, java.util.concurrent.TimeUnit)}
     * for synchronous wait.
     *
     * <p>The restart is scoped to all hosts running the given component
     * (Ambari resolves the host list internally from {@code resource_filters}).
     *
     * @param cluster       Ambari cluster name
     * @param serviceName   service the component belongs to (e.g. {@code RANGER})
     * @param componentName component to restart (e.g. {@code RANGER_TAGSYNC})
     * @param context       human-readable context shown in Ambari's "Background Operations" panel
     * @return Ambari request id
     */
    public int submitComponentRestart(String cluster,
                                      String serviceName,
                                      String componentName,
                                      String context) throws Exception {
        Objects.requireNonNull(cluster, "cluster");
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(componentName, "componentName");

        // Resolve the host(s) running this component and pass them EXPLICITLY — exactly as the
        // Ambari UI does. Without an explicit host list, Ambari's RESTART custom-command builder
        // runs its own host-eligibility filter and rejects with "no healthy eligible hosts"
        // whenever the host has ANY unhealthy/stopped component (common after reboots). The UI
        // never hits this because it always names the target host.
        java.util.List<String> compHosts = getComponentHosts(cluster, serviceName, componentName);
        if (compHosts == null || compHosts.isEmpty()) {
            throw new IllegalStateException("No hosts found running " + serviceName + "/" + componentName
                    + " — cannot build a RESTART request.");
        }

        JsonObject requestInfo = new JsonObject();
        requestInfo.addProperty("context", (context == null || context.isBlank())
                ? ("Restart " + serviceName + "/" + componentName)
                : context);
        requestInfo.addProperty("command", "RESTART");
        JsonObject operationLevel = new JsonObject();
        operationLevel.addProperty("level", "HOST_COMPONENT");
        operationLevel.addProperty("cluster_name", cluster);
        operationLevel.addProperty("service_name", serviceName);
        if (compHosts.size() == 1) {
            operationLevel.addProperty("host_name", compHosts.get(0));
        }
        requestInfo.add("operation_level", operationLevel);

        JsonObject resourceFilter = new JsonObject();
        resourceFilter.addProperty("service_name", serviceName);
        resourceFilter.addProperty("component_name", componentName);
        resourceFilter.addProperty("hosts", String.join(",", compHosts));   // explicit target host(s) — UI-style
        JsonArray filters = new JsonArray();
        filters.add(resourceFilter);

        JsonObject root = new JsonObject();
        root.add("RequestInfo", requestInfo);
        root.add("Requests/resource_filters", filters);

        String url = ambariApiBase + "/clusters/" + encode(cluster) + "/requests";
        String payload = root.toString();
        LOG.info("submitComponentRestart: POST {} service='{}' component='{}'", url, serviceName, componentName);

        try (InputStream is = stream.readFrom(
                url,
                "POST",
                payload,
                withStdHeaders(Map.of("Content-Type", "application/json"))
        )) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            JsonObject ro = o.getAsJsonObject("Requests");
            if (ro == null || !ro.has("id")) {
                throw new IllegalStateException("Unexpected response for component restart submission: " + json);
            }
            int id = ro.get("id").getAsInt();
            LOG.info("Component restart accepted by Ambari, request id={}", id);
            return id;
        }
    }

    /**
     * Creates or updates an Ambari view instance via PUT (idempotent create-or-update).
     *
     * <p>After a successful SQL Assistant Helm deploy the K8s view calls this to wire
     * the deployed service URL into a new view instance automatically, removing the need
     * for manual post-deploy configuration.
     *
     * @param viewName     Ambari view name, e.g. "SQL-ASSISTANT-VIEW"
     * @param viewVersion  View version string, e.g. "1.0.0.0"
     * @param instanceName Instance identifier (use the Helm release name for 1:1 mapping)
     * @param label        Human-readable label shown in the Ambari top menu
     * @param description  Optional description (may be null)
     * @param properties   View instance parameter map (e.g. sql.assistant.semantic.service.url → value)
     */
    public void createOrUpdateViewInstance(
            String viewName,
            String viewVersion,
            String instanceName,
            String label,
            String description,
            Map<String, String> properties
    ) throws Exception {
        String url = ambariApiBase
                + "/views/" + encode(viewName)
                + "/versions/" + encode(viewVersion)
                + "/instances/" + encode(instanceName);

        JsonObject info = new JsonObject();
        if (label != null && !label.isBlank()) {
            info.addProperty("label", label);
        }
        if (description != null && !description.isBlank()) {
            info.addProperty("description", description);
        }
        if (properties != null && !properties.isEmpty()) {
            JsonObject props = new JsonObject();
            properties.forEach(props::addProperty);
            info.add("properties", props);
        }
        JsonObject body = new JsonObject();
        body.add("ViewInstanceInfo", info);

        LOG.info("Provisioning Ambari view instance: PUT {} label='{}' properties={}",
                url, label, properties == null ? "none" : properties.keySet());

        try (InputStream is = stream.readFrom(
                url,
                "PUT",
                body.toString(),
                withStdHeaders(Map.of("Content-Type", "application/json"))
        )) {
            String resp = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            LOG.info("View instance provisioned ({}): status response length={}", instanceName, resp.length());
        }
    }

    private void initializeMainConfigurations(){
        if (this.defaultConfigTypes == null){
            this.defaultConfigTypes = new HashMap<>();
        }
        this.defaultConfigTypes.put("core-site", new AmbariConfigPropertiesTypes(
                "core-site", "xml", "xml"
        ));
        this.defaultConfigTypes.put("hdfs-site", new AmbariConfigPropertiesTypes(
                "hdfs-site", "xml", "xml"
        ));
        this.defaultConfigTypes.put("hive-site", new AmbariConfigPropertiesTypes(
                "hive-site", "xml", "xml"
        ));
        this.defaultConfigTypes.put("hbase-site", new AmbariConfigPropertiesTypes(
                "hive-site", "xml", "xml"
        ));
        this.defaultConfigTypes.put("ranger-admin-site", new AmbariConfigPropertiesTypes(
                "ranger-admin-site", "xml", "xml"
        ));
        this.defaultConfigTypes.put("hadoop-env", new AmbariConfigPropertiesTypes(
                "hadoop-env", "env", "env"
        ));
        this.defaultConfigTypes.put("ranger-env", new AmbariConfigPropertiesTypes(
                "ranger-env", "env", "env"
        ));
    }
    public Map<String, AmbariConfigPropertiesTypes> getDefaultConfigTypes(){
        return defaultConfigTypes;
    }
    public record AmbariConfigPropertiesTypes(String name, String type, String parser) {
    }
}
