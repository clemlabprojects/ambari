/*
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

package org.apache.ambari.server.serveraction.ranger;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.serveraction.AbstractServerAction;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.utils.SecretReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;

/**
 * Ambari server-side action that grants a user read/other access on a Ranger
 * service repository, idempotently. It mirrors {@link CreateRangerServiceServerAction}'s
 * mechanism: the action runs inside the Ambari server JVM where {@code ranger-env}
 * holds the (decrypted) Ranger admin credentials, so callers (e.g. the KDPS k8s view)
 * never need to know the Ranger admin password.
 *
 * <p>Generic by design — it is NOT specific to Atlas. The caller supplies the target
 * service repo, the user, the access types, and the resource map; this action:
 * <ol>
 *   <li>ensures the user exists in Ranger (xusers) — Ranger rejects policies that
 *       reference unknown users;</li>
 *   <li>grants the access by either (a) appending a policy item to an existing policy
 *       that already owns the same resource scope, or (b) creating a new policy. Ranger
 *       refuses two policies with identical resources (error code 3010); when that
 *       happens on create, the conflicting policy name is parsed from the error and the
 *       grant is appended to it instead. Idempotent: re-running is a no-op when the user
 *       already has the access.</li>
 * </ol>
 *
 * <p>Command parameters (all strings):
 * <ul>
 *   <li>{@code clusterName} (required) — Ambari cluster name</li>
 *   <li>{@code rangerServiceName} (required) — target Ranger service repo (e.g. {@code <cluster>_atlas})</li>
 *   <li>{@code userName} (required) — user to grant</li>
 *   <li>{@code accessTypes} (required) — comma-separated access types (e.g. {@code entity-read})</li>
 *   <li>{@code resourcesJson} (required) — JSON object of resourceKey → array of values
 *       (e.g. {@code {"entity-type":["*"],"entity-classification":["*"],"entity":["*"]}})</li>
 *   <li>{@code policyNameHint} (required) — name to use if a new policy must be created</li>
 *   <li>{@code policyDescription} (optional)</li>
 *   <li>{@code timeoutSeconds} (optional, default 45) — propagation-poll budget</li>
 * </ul>
 */
public class EnsureRangerPolicyServerAction extends AbstractServerAction {

    private static final Logger LOG = LoggerFactory.getLogger(EnsureRangerPolicyServerAction.class);

    /** Parses the conflicting policy name out of Ranger's "matching resource" (code 3010) error. */
    private static final Pattern POLICY_NAME_IN_ERROR =
            Pattern.compile("policy-name=\\[(.*?)\\]");

    @Inject
    private AmbariManagementController managementController;

    @Override
    public CommandReport execute(ConcurrentMap<String, Object> requestSharedDataContext)
            throws AmbariException {

        Map<String, String> commandParameters = getCommandParameters();
        if (commandParameters == null) {
            commandParameters = Map.of();
        }

        String clusterName       = trimToNull(commandParameters.get("clusterName"));
        String rangerServiceName = trimToNull(commandParameters.get("rangerServiceName"));
        String userName          = trimToNull(commandParameters.get("userName"));
        String accessTypes       = trimToNull(commandParameters.get("accessTypes"));
        String resourcesJson     = trimToNull(commandParameters.get("resourcesJson"));
        String policyNameHint    = trimToNull(commandParameters.get("policyNameHint"));
        String policyDescription = trimToNull(commandParameters.get("policyDescription"));
        long timeoutMs           = parseLong(commandParameters.get("timeoutSeconds"), 45L) * 1000L;

        LOG.info("EnsureRangerPolicyServerAction called: clusterName='{}', rangerServiceName='{}', "
                        + "userName='{}', accessTypes='{}', policyNameHint='{}'",
                clusterName, rangerServiceName, userName, accessTypes, policyNameHint);

        actionLog.writeStdOut("Starting EnsureRangerPolicyServerAction with:");
        actionLog.writeStdOut("  clusterName       = " + clusterName);
        actionLog.writeStdOut("  rangerServiceName = " + rangerServiceName);
        actionLog.writeStdOut("  userName          = " + userName);
        actionLog.writeStdOut("  accessTypes       = " + accessTypes);
        actionLog.writeStdOut("  policyNameHint    = " + policyNameHint);

        String missing = firstMissing(
                "clusterName", clusterName,
                "rangerServiceName", rangerServiceName,
                "userName", userName,
                "accessTypes", accessTypes,
                "resourcesJson", resourcesJson,
                "policyNameHint", policyNameHint);
        if (missing != null) {
            String message = "Required parameter '" + missing + "' is missing or empty";
            actionLog.writeStdErr(message);
            LOG.error(message);
            return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), message);
        }

        try {
            Clusters clusters = managementController.getClusters();
            Cluster cluster   = clusters.getCluster(clusterName);

            Config adminProperties = cluster.getDesiredConfigByType("admin-properties");
            if (adminProperties == null) {
                return fail("Missing 'admin-properties' config for cluster " + clusterName);
            }
            String policymgrExternalUrl = trimToNull(
                    adminProperties.getProperties().get("policymgr_external_url"));
            if (policymgrExternalUrl == null) {
                return fail("admin-properties/policymgr_external_url is not set for cluster " + clusterName);
            }

            Config rangerEnv = cluster.getDesiredConfigByType("ranger-env");
            if (rangerEnv == null) {
                return fail("Missing 'ranger-env' config for cluster " + clusterName);
            }
            String rangerAdminUserName = trimToNull(
                    rangerEnv.getProperties().get("ranger_admin_username"));
            if (rangerAdminUserName == null) {
                return fail("ranger-env/ranger_admin_username is not set for cluster " + clusterName);
            }
            // Resolve the (possibly encrypted) password the same way CreateRangerServiceServerAction does.
            Map<String, String> rangerEnvProperties = rangerEnv.getProperties();
            SecretReference.replaceReferencesWithPasswords(rangerEnvProperties, cluster);
            String rangerAdminPassword = rangerEnv.getProperties().get("ranger_admin_password");
            if (rangerAdminPassword == null) {
                return fail("Could not resolve/decrypt ranger-env/ranger_admin_password for cluster " + clusterName);
            }

            String auth = buildBasicAuth(rangerAdminUserName, rangerAdminPassword);
            actionLog.writeStdOut("Using Ranger at " + policymgrExternalUrl
                    + " with admin user " + rangerAdminUserName);

            // 1) Ensure the user exists (Ranger rejects policy items that reference unknown users).
            ensureUserExists(policymgrExternalUrl, auth, userName);

            // 2) Grant the access (append-to-existing or create), then poll for readability.
            long policyId = ensureGrant(policymgrExternalUrl, auth, rangerServiceName, userName,
                    accessTypes, resourcesJson, policyNameHint, policyDescription, timeoutMs);

            Map<String, Object> structuredOutMap = new HashMap<>();
            structuredOutMap.put("clusterName", clusterName);
            structuredOutMap.put("rangerServiceName", rangerServiceName);
            structuredOutMap.put("userName", userName);
            structuredOutMap.put("policyId", policyId);
            String structuredOut = toJson(structuredOutMap);

            String done = "Granted '" + accessTypes + "' on Ranger service '" + rangerServiceName
                    + "' to user '" + userName + "' (policyId=" + policyId + ")";
            actionLog.writeStdOut(done);
            LOG.info(done);
            return createCommandReport(0, HostRoleStatus.COMPLETED,
                    structuredOut, actionLog.getStdOut(), actionLog.getStdErr());

        } catch (Exception exception) {
            String error = "Failed to ensure Ranger policy: " + exception.getMessage();
            LOG.error(error, exception);
            actionLog.writeStdErr(error);
            return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
        }
    }

    private CommandReport fail(String message) {
        actionLog.writeStdErr(message);
        LOG.error(message);
        return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), message);
    }

    // ------------------------------------------------------------------
    // Ranger user
    // ------------------------------------------------------------------

    /** Idempotently ensures {@code userName} exists in Ranger's xusers store. */
    private void ensureUserExists(String baseUrl, String auth, String userName) throws Exception {
        String lookupUrl = baseUrl + "/service/xusers/users?name=" + urlEncode(userName);
        HttpResponse lookup = http(lookupUrl, "GET", auth, null);
        if (lookup.statusCode == 200 && lookup.body != null
                && lookup.body.contains("\"name\":\"" + escape(userName) + "\"")) {
            actionLog.writeStdOut("Ranger user '" + userName + "' already exists; skipping creation");
            LOG.info("Ranger user '{}' already exists", userName);
            return;
        }

        // Create as an internal secure user. The password is never used to authenticate
        // to Ranger (the user authenticates to the protected service, e.g. Atlas), so a
        // strong random value is fine.
        JsonObject xuser = new JsonObject();
        xuser.addProperty("status", 1);
        JsonArray roles = new JsonArray();
        roles.add("ROLE_USER");
        xuser.add("userRoleList", roles);
        xuser.addProperty("name", userName);
        xuser.addProperty("firstName", userName);
        xuser.addProperty("lastName", "(KDPS)");
        xuser.addProperty("emailAddress", "");
        xuser.addProperty("password", UUID.randomUUID().toString() + "Aa1!");
        xuser.addProperty("description", "Provisioned by Ambari for KDPS service integration");

        HttpResponse created = http(baseUrl + "/service/xusers/secure/users", "POST", auth, xuser.toString());
        if (created.statusCode / 100 == 2) {
            actionLog.writeStdOut("Created Ranger user '" + userName + "'");
            LOG.info("Created Ranger user '{}'", userName);
            return;
        }
        // Benign race: another sync created it.
        if (created.statusCode == 400 && created.body != null
                && created.body.toLowerCase().contains("already exists")) {
            actionLog.writeStdOut("Ranger user '" + userName + "' already exists (raced); continuing");
            return;
        }
        throw new IllegalStateException("Failed to create Ranger user '" + userName
                + "': HTTP " + created.statusCode + ", body=" + created.body);
    }

    // ------------------------------------------------------------------
    // Ranger policy grant
    // ------------------------------------------------------------------

    private long ensureGrant(String baseUrl, String auth, String serviceName, String userName,
                             String accessTypes, String resourcesJson, String policyNameHint,
                             String policyDescription, long timeoutMs) throws Exception {

        // (a) If a policy with our hinted name already exists, append to it.
        Long existing = lookupPolicyIdByName(baseUrl, auth, serviceName, policyNameHint);
        if (existing != null) {
            appendGrant(baseUrl, auth, existing, userName, accessTypes);
            return pollReadable(baseUrl, auth, existing, timeoutMs);
        }

        // (b) Try to create a fresh policy.
        JsonObject policy = buildPolicy(serviceName, policyNameHint, policyDescription,
                userName, accessTypes, resourcesJson);
        HttpResponse create = http(baseUrl + "/service/public/v2/api/policy", "POST", auth, policy.toString());
        if (create.statusCode / 100 == 2) {
            long id = JsonParser.parseString(create.body).getAsJsonObject().get("id").getAsLong();
            actionLog.writeStdOut("Created Ranger policy '" + policyNameHint + "' (id=" + id + ")");
            LOG.info("Created Ranger policy '{}' (id={})", policyNameHint, id);
            return pollReadable(baseUrl, auth, id, timeoutMs);
        }

        // (c) Ranger rejects duplicate resource scope (code 3010). Parse the owning policy
        // name from the error and append our grant to it instead.
        if (create.statusCode == 400 && create.body != null) {
            String ownerName = parseConflictingPolicyName(create.body);
            if (ownerName != null) {
                actionLog.writeStdOut("Resource scope already owned by policy '" + ownerName
                        + "'; appending grant to it");
                LOG.info("Resource scope owned by policy '{}'; appending grant", ownerName);
                Long ownerId = lookupPolicyIdByName(baseUrl, auth, serviceName, ownerName);
                if (ownerId == null) {
                    throw new IllegalStateException("Ranger reported conflicting policy '" + ownerName
                            + "' but it could not be looked up by name");
                }
                appendGrant(baseUrl, auth, ownerId, userName, accessTypes);
                return pollReadable(baseUrl, auth, ownerId, timeoutMs);
            }
        }
        throw new IllegalStateException("Ranger policy create failed: HTTP " + create.statusCode
                + " — " + create.body);
    }

    /** GET a policy by (service, name); returns its id or null if 404. */
    private Long lookupPolicyIdByName(String baseUrl, String auth, String serviceName, String policyName)
            throws Exception {
        String url = baseUrl + "/service/public/v2/api/service/" + pathSegmentEncode(serviceName)
                + "/policy/" + pathSegmentEncode(policyName);
        HttpResponse r = http(url, "GET", auth, null);
        if (r.statusCode == 200 && r.body != null && !r.body.isBlank()) {
            JsonObject o = JsonParser.parseString(r.body).getAsJsonObject();
            return o.has("id") ? o.get("id").getAsLong() : null;
        }
        if (r.statusCode == 404) {
            return null;
        }
        throw new IllegalStateException("Ranger policy lookup-by-name returned HTTP " + r.statusCode
                + ": " + r.body);
    }

    /** GET policy by id, append an allow policyItem for (user, accessTypes), PUT back. Idempotent. */
    private void appendGrant(String baseUrl, String auth, long policyId, String userName, String accessTypes)
            throws Exception {
        HttpResponse get = http(baseUrl + "/service/public/v2/api/policy/" + policyId, "GET", auth, null);
        if (get.statusCode != 200) {
            throw new IllegalStateException("Ranger policy GET by id=" + policyId + " returned HTTP "
                    + get.statusCode + ": " + get.body);
        }
        JsonObject policy = JsonParser.parseString(get.body).getAsJsonObject();
        JsonArray policyItems = policy.has("policyItems") && policy.get("policyItems").isJsonArray()
                ? policy.getAsJsonArray("policyItems")
                : new JsonArray();

        String[] wanted = accessTypes.split(",");
        // Idempotency: skip if the user already has all requested accesses in some item.
        if (userHasAllAccesses(policyItems, userName, wanted)) {
            actionLog.writeStdOut("Ranger policy id=" + policyId + " already grants requested access to '"
                    + userName + "'; no-op");
            LOG.info("Ranger policy id={} already grants '{}' to '{}'; no-op", policyId, accessTypes, userName);
            return;
        }

        JsonObject item = new JsonObject();
        JsonArray users = new JsonArray();
        users.add(userName);
        item.add("users", users);
        item.add("groups", new JsonArray());
        item.add("roles", new JsonArray());
        JsonArray accesses = new JsonArray();
        for (String a : wanted) {
            String t = a.trim();
            if (t.isEmpty()) {
                continue;
            }
            JsonObject acc = new JsonObject();
            acc.addProperty("type", t);
            acc.addProperty("isAllowed", true);
            accesses.add(acc);
        }
        item.add("accesses", accesses);
        item.addProperty("delegateAdmin", false);
        policyItems.add(item);
        policy.add("policyItems", policyItems);

        HttpResponse put = http(baseUrl + "/service/public/v2/api/policy/" + policyId, "PUT", auth,
                policy.toString());
        if (put.statusCode / 100 != 2) {
            throw new IllegalStateException("Ranger policy update (id=" + policyId + ") failed: HTTP "
                    + put.statusCode + " — " + put.body);
        }
        actionLog.writeStdOut("Appended grant (user='" + userName + "', access='" + accessTypes
                + "') to Ranger policy id=" + policyId);
        LOG.info("Appended grant (user='{}', access='{}') to Ranger policy id={}", userName, accessTypes, policyId);
    }

    /**
     * Extracts the conflicting policy name from Ranger's "matching resource" (code 3010)
     * validation error, e.g. {@code policy-name=[all - entity-type, entity-classification, entity]}.
     * Returns null when the error body does not carry a policy name.
     * Package-private for unit testing.
     */
    static String parseConflictingPolicyName(String errorBody) {
        if (errorBody == null) {
            return null;
        }
        Matcher m = POLICY_NAME_IN_ERROR.matcher(errorBody);
        return m.find() ? m.group(1) : null;
    }

    static boolean userHasAllAccesses(JsonArray policyItems, String userName, String[] wanted) {
        for (JsonElement e : policyItems) {
            JsonObject it = e.getAsJsonObject();
            JsonArray users = it.has("users") ? it.getAsJsonArray("users") : new JsonArray();
            boolean hasUser = false;
            for (JsonElement u : users) {
                if (userName.equals(u.getAsString())) {
                    hasUser = true;
                    break;
                }
            }
            if (!hasUser) {
                continue;
            }
            JsonArray accesses = it.has("accesses") ? it.getAsJsonArray("accesses") : new JsonArray();
            boolean allPresent = true;
            for (String w : wanted) {
                String want = w.trim();
                if (want.isEmpty()) {
                    continue;
                }
                boolean found = false;
                for (JsonElement ae : accesses) {
                    JsonObject acc = ae.getAsJsonObject();
                    if (want.equals(acc.has("type") ? acc.get("type").getAsString() : null)
                            && acc.has("isAllowed") && acc.get("isAllowed").getAsBoolean()) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    allPresent = false;
                    break;
                }
            }
            if (allPresent) {
                return true;
            }
        }
        return false;
    }

    /** Build a brand-new allow policy for the given resource map + access. Package-private for testing. */
    static JsonObject buildPolicy(String serviceName, String policyName, String description,
                                  String userName, String accessTypes, String resourcesJson) {
        JsonObject p = new JsonObject();
        p.addProperty("service", serviceName);
        p.addProperty("name", policyName);
        if (description != null) {
            p.addProperty("description", description);
        }
        p.addProperty("isAuditEnabled", true);
        p.addProperty("isEnabled", true);
        p.addProperty("policyType", 0);
        p.addProperty("policyPriority", 0);

        JsonObject resources = new JsonObject();
        JsonObject parsed = JsonParser.parseString(resourcesJson).getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : parsed.entrySet()) {
            JsonObject r = new JsonObject();
            JsonArray values = e.getValue().isJsonArray() ? e.getValue().getAsJsonArray() : new JsonArray();
            r.add("values", values);
            r.addProperty("isExcludes", false);
            r.addProperty("isRecursive", false);
            resources.add(e.getKey(), r);
        }
        p.add("resources", resources);

        JsonObject item = new JsonObject();
        JsonArray users = new JsonArray();
        users.add(userName);
        item.add("users", users);
        item.add("groups", new JsonArray());
        item.add("roles", new JsonArray());
        JsonArray accesses = new JsonArray();
        for (String a : accessTypes.split(",")) {
            String t = a.trim();
            if (t.isEmpty()) {
                continue;
            }
            JsonObject acc = new JsonObject();
            acc.addProperty("type", t);
            acc.addProperty("isAllowed", true);
            accesses.add(acc);
        }
        item.add("accesses", accesses);
        item.addProperty("delegateAdmin", false);
        JsonArray policyItems = new JsonArray();
        policyItems.add(item);
        p.add("policyItems", policyItems);

        p.add("denyPolicyItems", new JsonArray());
        p.add("allowExceptions", new JsonArray());
        p.add("denyExceptions", new JsonArray());
        p.add("dataMaskPolicyItems", new JsonArray());
        p.add("rowFilterPolicyItems", new JsonArray());
        return p;
    }

    /** Poll GET-by-id until the policy is readable (proxy for "persisted + indexed"). */
    private long pollReadable(String baseUrl, String auth, long policyId, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        Exception lastErr = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse r = http(baseUrl + "/service/public/v2/api/policy/" + policyId, "GET", auth, null);
                if (r.statusCode == 200) {
                    LOG.info("Ranger policy id={} verified readable", policyId);
                    return policyId;
                }
                lastErr = new IllegalStateException("policy GET returned HTTP " + r.statusCode);
            } catch (Exception e) {
                lastErr = e;
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("Ranger policy id=" + policyId
                + " not readable within " + timeoutMs + "ms — last error: "
                + (lastErr == null ? "n/a" : lastErr.getMessage()));
    }

    // ------------------------------------------------------------------
    // HTTP + small helpers (mirrors CreateRangerServiceServerAction)
    // ------------------------------------------------------------------

    private HttpResponse http(String urlAsString, String method, String auth, String jsonBody) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlAsString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Accept", "application/json");
            if (auth != null) {
                connection.setRequestProperty("Authorization", auth);
            }
            if (jsonBody != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                connection.getOutputStream().write(bytes);
                connection.getOutputStream().flush();
            }
            int code = connection.getResponseCode();
            String body;
            try (var in = (code >= 200 && code < 400)
                    ? connection.getInputStream()
                    : connection.getErrorStream()) {
                body = (in != null) ? new String(in.readAllBytes(), StandardCharsets.UTF_8) : "";
            }
            LOG.debug("HTTP {} '{}' returned {} body='{}'", method, urlAsString, code, body);
            return new HttpResponse(code, body);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String buildBasicAuth(String userName, String password) {
        String raw = userName + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Query-string encoding (form rules: space → '+'). */
    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    /** Path-segment encoding (RFC-3986: space → %20). Ranger 404s on '+' in policy-name path segments. */
    private static String pathSegmentEncode(String value) {
        return urlEncode(value).replace("+", "%20");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long parseLong(String value, long defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Returns the name of the first blank required param (name,value pairs), or null if all present. */
    private static String firstMissing(String... namesAndValues) {
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            if (isBlank(namesAndValues[i + 1])) {
                return namesAndValues[i];
            }
        }
        return null;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(escape(entry.getKey())).append('"').append(':');
            Object value = entry.getValue();
            if (value == null) {
                builder.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                builder.append(value.toString());
            } else {
                builder.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        builder.append('}');
        return builder.toString();
    }

    private static class HttpResponse {
        final int statusCode;
        final String body;

        HttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }
}
