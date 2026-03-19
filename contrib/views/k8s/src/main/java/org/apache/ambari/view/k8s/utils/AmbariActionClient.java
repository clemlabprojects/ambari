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
        try (InputStream is = stream.readFrom(url, "GET", (String) null, withStdHeaders(null))) {
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

    public String getDesiredConfigProperty(String cluster, String type, String key) throws Exception {
        Objects.requireNonNull(cluster, "cluster");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");

        // 1) get desired tag for this config type
        //    /api/v1/clusters/{cluster}?fields=Clusters/desired_configs/{type}
        String url1 = ambariApiBase + "/clusters/" + encode(cluster)
                + "?fields=" + encode("Clusters/desired_configs/" + type);
        String json1;
        try (InputStream is = stream.readFrom(url1, "GET", (String) null, withStdHeaders(null))) {
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
        try (InputStream is = stream.readFrom(url2, "GET", (String) null, withStdHeaders(null))) {
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
        try (InputStream is = stream.readFrom(urlDesired, "GET", (String) null, withStdHeaders(null))) {
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
        try (InputStream is = stream.readFrom(urlCfg, "GET", (String) null, withStdHeaders(null))) {
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
