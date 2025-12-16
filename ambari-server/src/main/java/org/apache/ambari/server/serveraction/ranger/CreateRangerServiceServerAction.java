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

import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;

/**
 * this class represent ambari server logic to talk with apache ranger api to create a repository for a plugin type
 */

public class CreateRangerServiceServerAction extends AbstractServerAction {

    private static final Logger LOG = LoggerFactory.getLogger(CreateRangerServiceServerAction.class);

    @Inject
    private AmbariManagementController managementController;

    private static final Gson GSON = new Gson();
    /**
     * main method implementing the execution when a the command is received
     * @param requestSharedDataContext a Map to be used a shared data among all ServerActions related
     *                                 to a given request
     * @return
     * @throws AmbariException
     */
    @Override
    public CommandReport execute(ConcurrentMap<String, Object> requestSharedDataContext)
            throws AmbariException {

        Map<String, String> commandParameters = getCommandParameters();
        if (commandParameters == null) {
            commandParameters = Map.of();
        }

        // Log incoming parameters (with password masked)
        LOG.info("CreateRangerServiceServerAction called with parameters: clusterName='{}', rangerRepositoryName='{}', serviceType='{}', pluginUserName='{}'",
                commandParameters.get("clusterName"),
                commandParameters.get("rangerRepositoryName"),
                commandParameters.get("serviceType"),
                commandParameters.get("pluginUserName"));

        String clusterName            = commandParameters.get("clusterName");
        String rangerRepositoryName   = commandParameters.get("rangerRepositoryName");
        String serviceType            = defaultIfBlank(commandParameters.get("serviceType"), "trino");
        String pluginUserName         = trimToNull(commandParameters.get("pluginUserName"));
        String pluginUserPassword     = trimToNull(commandParameters.get("pluginUserPassword"));
        String repositoryDescription  = commandParameters.get("repositoryDescription");
        String serviceConfigsJson     = trimToNull(commandParameters.get("serviceConfigsJson"));

        actionLog.writeStdOut("Starting CreateRangerServiceServerAction with:");
        actionLog.writeStdOut("  clusterName          = " + clusterName);
        actionLog.writeStdOut("  rangerRepositoryName = " + rangerRepositoryName);
        actionLog.writeStdOut("  serviceType          = " + serviceType);
        actionLog.writeStdOut("  serviceConfigsJson   = " + serviceConfigsJson);
        if (pluginUserName != null) {
            actionLog.writeStdOut("  pluginUserName       = " + pluginUserName);
        }

        /**
         * cluster name is required
         */
        if (isBlank(clusterName)) {
            String message = "Required parameter 'clusterName' is missing or empty";
            actionLog.writeStdErr(message);
            LOG.error(message);
            return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), message);
        }
        /**
         * repository name is required
         */
        if (isBlank(rangerRepositoryName)) {
            String message = "Required parameter 'rangerRepositoryName' is missing or empty";
            actionLog.writeStdErr(message);
            LOG.error(message);
            return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), message);
        }

        Map<String, Object> serviceConfigs = null;

        if (serviceConfigsJson != null) {
            try {
                Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
                serviceConfigs = GSON.fromJson(serviceConfigsJson, mapType);
                LOG.info("Parsed serviceConfigsJson for '{}', keys={}",
                        rangerRepositoryName, serviceConfigs.keySet());
            } catch (Exception e) {
                String msg = "Failed to parse serviceConfigsJson for repository '" +
                        rangerRepositoryName + "': " + e.getMessage();
                actionLog.writeStdErr(msg);
                LOG.error(msg, e);
            }
        }
        try {

            Clusters clusters = managementController.getClusters();
            Cluster cluster   = clusters.getCluster(clusterName);
            LOG.info("Loaded cluster '{}' for Ranger service creation", clusterName);

            //Gathering ranger related properties
            /**
             * admin-properties
             */
            Config adminProperties = cluster.getDesiredConfigByType("admin-properties");
            if (adminProperties == null) {
                String message = "Missing 'admin-properties' config for cluster " + clusterName;
                actionLog.writeStdErr(message);
                LOG.error(message);
                return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), message);
            }
            LOG.info("Found 'admin-properties' config for cluster '{}'", clusterName);

            /**
             * ranger admin policy mgr external url
             */
            String policymgrExternalUrl = trimToNull(
                    adminProperties.getProperties().get("policymgr_external_url"));
            if (policymgrExternalUrl == null) {
                String message = "admin-properties/policymgr_external_url is not set for cluster " + clusterName;
                actionLog.writeStdErr(message);
                LOG.error(message);
                return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), message);
            }

            LOG.info("Using Ranger URL '{}' for cluster '{}'", policymgrExternalUrl, clusterName);

            /**
             * ranger env for getting password
             */
            Config rangerEnv = cluster.getDesiredConfigByType("ranger-env");
            if (rangerEnv == null) {
                String message = "Missing 'ranger-env' config for cluster " + clusterName;
                actionLog.writeStdErr(message);
                LOG.error(message);
                return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), message);
            }
            LOG.info("Found 'ranger-env' config for cluster '{}'", clusterName);

            /**
             * ranger env for getting user name
             */
            String rangerAdminUserName = trimToNull(
                    rangerEnv.getProperties().get("ranger_admin_username"));
            if (rangerAdminUserName == null) {
                String message = "ranger-env/ranger_admin_username is not set for cluster " + clusterName;
                actionLog.writeStdErr(message);
                LOG.error(message);
                return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), message);
            }

            LOG.info("Ranger admin username for cluster '{}': '{}'", clusterName, rangerAdminUserName);
            actionLog.writeStdOut("Ranger admin username for cluster '" + clusterName + "': '" + rangerAdminUserName + "'");
            // Resolve via SecretReference / CredentialStore
            String rangerAdminPassword;
            Map<String,String> rangerEnvProperties = rangerEnv.getProperties();
            SecretReference.replaceReferencesWithPasswords(rangerEnvProperties, cluster);
            rangerAdminPassword = rangerEnv.getProperties().get("ranger_admin_password");

            if (rangerAdminPassword == null) {
                String message = "Could not resolve/decrypt ranger-env/ranger_admin_password for cluster " + clusterName;
                actionLog.writeStdErr(message);
                LOG.error(message);
                return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), message);
            }

            actionLog.writeStdOut("Using Ranger at " + policymgrExternalUrl +
                    " with admin user " + rangerAdminUserName);
            LOG.info("Prepared to ensure Ranger service '{}' (type='{}') on '{}'", rangerRepositoryName, serviceType, policymgrExternalUrl);
            actionLog.writeStdOut("Preparing to ensure Ranger service '" +
                    rangerRepositoryName + "' of type '" + serviceType + "'");
            /**
             * creating ranger Service
             */
            ensureRangerService(
                    policymgrExternalUrl,
                    rangerAdminUserName,
                    rangerAdminPassword,
                    rangerRepositoryName,
                    serviceType,
                    pluginUserName,
                    pluginUserPassword,
                    serviceConfigs,
                    repositoryDescription
            );

            actionLog.writeStdOut("Ranger service '" + rangerRepositoryName +
                    "' ensured on " + policymgrExternalUrl);
            LOG.info("Ranger service '{}' ensured on '{}'", rangerRepositoryName, policymgrExternalUrl);
            Map<String, Object> structuredOutMap = new HashMap<>();
            structuredOutMap.put("clusterName", clusterName);
            structuredOutMap.put("rangerRepositoryName", rangerRepositoryName);
            structuredOutMap.put("serviceType", serviceType);
            structuredOutMap.put("policymgrExternalUrl", policymgrExternalUrl);
            if (pluginUserName != null) {
                structuredOutMap.put("pluginUserName", pluginUserName);
            }

            String structuredOut = toJson(structuredOutMap);
            String stdoutMessage = "Ranger service '" + rangerRepositoryName + "' configured successfully";
            actionLog.writeStdOut(stdoutMessage);

            LOG.info("Ranger service '{}' configured successfully for cluster '{}'", rangerRepositoryName, clusterName);

            return createCommandReport(0, HostRoleStatus.COMPLETED,
                    structuredOut, actionLog.getStdOut(), actionLog.getStdErr());

        } catch (Exception exception) {
            String error = "Failed to configure Ranger service: " + exception.getMessage();
            LOG.error(error, exception);
            actionLog.writeStdErr(error);
            return createCommandReport(0, HostRoleStatus.FAILED,
                    "{}", actionLog.getStdOut(), actionLog.getStdErr());
        }
    }

    private void ensureRangerService(
            String baseUrl,
            String adminUserName,
            String adminPassword,
            String rangerRepositoryName,
            String serviceType,
            String pluginUserName,
            String pluginUserPassword,
            Map<String, Object> extraConfigs,
            String repositoryDescription) throws Exception {

        String authorizationHeader = buildBasicAuth(adminUserName, adminPassword);

        // -----------------------------------------------------------------------
        // 1. PREPARE DESIRED STATE (Construct body first, so we can use it for Create OR Update)
        // -----------------------------------------------------------------------
        Map<String, Object> configs = new HashMap<>();
        configs.put("username", pluginUserName != null ? pluginUserName : adminUserName);
        if (pluginUserPassword != null) {
            configs.put("password", pluginUserPassword);
        }

        // Merge nested serviceConfigs
        if (extraConfigs != null && !extraConfigs.isEmpty()) {
            configs.putAll(extraConfigs);
        }

        // Build service body structure
        Map<String, Object> body = new HashMap<>();
        body.put("name", rangerRepositoryName);
        body.put("type", serviceType);
        body.put("isEnabled", true);
        if (!isBlank(repositoryDescription)) {
            body.put("description", repositoryDescription);
        }
        body.put("configs", configs);

        // -----------------------------------------------------------------------
        // 2. CHECK EXISTENCE
        // -----------------------------------------------------------------------
        String queryUrl = baseUrl + "/service/public/v2/api/service?serviceName=" +
                urlEncode(rangerRepositoryName);

        actionLog.writeStdOut("Checking Ranger service via URL: " + queryUrl);
        LOG.info("Checking existence of Ranger service '{}' using URL '{}'", rangerRepositoryName, queryUrl);

        HttpResponse getResponse = httpGetWithBody(queryUrl, authorizationHeader);
        int getStatus = getResponse.statusCode;
        String bodyRaw = getResponse.body != null ? getResponse.body.trim() : "";

        LOG.info("Ranger service '{}' lookup returned HTTP {} with body='{}'", rangerRepositoryName, getStatus, bodyRaw);

        boolean serviceExists = false;
        Long existingServiceId = null;

        if (getStatus == 200) {
            // Ranger returns an array: [{"id":1, "name":"..."}, ...]
            if (!"[]".equals(bodyRaw) && bodyRaw.length() > 2 && bodyRaw.contains("\"name\":\"" + rangerRepositoryName + "\"")) {
                serviceExists = true;
                try {
                    // Extract ID to perform the Update (PUT) later
                    java.util.List<Map<String, Object>> services = GSON.fromJson(bodyRaw, new com.google.gson.reflect.TypeToken<java.util.List<Map<String, Object>>>(){}.getType());
                    if (services != null && !services.isEmpty()) {
                        Map<String, Object> existing = services.get(0);
                        // Gson usually parses numbers as Doubles, handle safely
                        Object idObj = existing.get("id");
                        if (idObj instanceof Number) {
                            existingServiceId = ((Number) idObj).longValue();
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Service exists but failed to parse ID from body: " + e.getMessage());
                    // If we can't parse ID, we can't update. We might have to fail or skip.
                }
            }
        }

        // -----------------------------------------------------------------------
        // 3. CREATE (POST) OR UPDATE (PUT)
        // -----------------------------------------------------------------------
        String targetUrl;
        String httpMethod;
        String actionLabel;

        if (serviceExists && existingServiceId != null) {
            // --- UPDATE PATH ---
            actionLabel = "Update";
            httpMethod = "PUT";
            // Ranger requires the ID to be in the body for updates
            body.put("id", existingServiceId);
            // Append ID to URL: /service/public/v2/api/service/{id}
            targetUrl = baseUrl + "/service/public/v2/api/service/" + existingServiceId;

            actionLog.writeStdOut("Ranger service '" + rangerRepositoryName + "' exists (id=" + existingServiceId + "). Updating configuration.");
            LOG.info("Updating existing Ranger service '{}' (id={})", rangerRepositoryName, existingServiceId);

        } else {
            // --- CREATE PATH ---
            actionLabel = "Create";
            httpMethod = "POST";
            targetUrl = baseUrl + "/service/public/v2/api/service";

            actionLog.writeStdOut("Ranger service '" + rangerRepositoryName + "' does not exist. Creating.");
            LOG.info("Creating new Ranger service '{}'", rangerRepositoryName);
        }

        // Serialize final body
        String jsonBody = toJson(body);
        String maskedBody = maskPassword(jsonBody);
        LOG.debug("{} payload (masked): {}", actionLabel, maskedBody);

        // Perform Request (Refactored helper to accept Method)
        HttpResponse response = sendJsonRequest(targetUrl, httpMethod, authorizationHeader, jsonBody);

        int respStatus = response.statusCode;
        String respBody = response.body != null ? response.body.trim() : "";

        actionLog.writeStdOut(actionLabel + " service HTTP status: " + respStatus);
        LOG.info("{} Ranger service '{}' returned HTTP {}", actionLabel, rangerRepositoryName, respStatus);

        // Success check (200 for OK, 201 for Created, 204 for No Content)
        if (respStatus < 200 || respStatus >= 300) {
            String msg = "Failed to " + actionLabel + " Ranger service '" +
                    rangerRepositoryName + "': HTTP " + respStatus + ", body='" + respBody + "'";
            actionLog.writeStdErr(msg);
            LOG.error(msg);
            throw new IllegalStateException(msg);
        }

        String stdoutMessage = "Ranger service '" + rangerRepositoryName + "' " + actionLabel.toLowerCase() + "d successfully";
        actionLog.writeStdOut(stdoutMessage);
        LOG.info(stdoutMessage);
    }

    // ---------- HTTP + small helpers ----------

    /**
     * Get request used for testing if ranger service repository does already exist
     * @param urlAsString the service url to test
     * @param authorizationHeader
     * @return
     * @throws Exception
     */
    private int httpGetStatus(String urlAsString, String authorizationHeader) throws Exception {
        HttpURLConnection connection = null;
        try {
            LOG.debug("HTTP GET to Ranger: {}", urlAsString);
            URL url = new URL(urlAsString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/json");
            if (authorizationHeader != null) {
                connection.setRequestProperty("Authorization", authorizationHeader);
            }
            int code = connection.getResponseCode();
            LOG.debug("HTTP GET '{}' returned {}", urlAsString, code);
            return code;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Generic helper for JSON POST or PUT requests
     */
    private HttpResponse sendJsonRequest(String urlAsString, String method, String authorizationHeader, String jsonBody) throws Exception {
        HttpURLConnection connection = null;
        try {
            LOG.debug("HTTP {} to Ranger: {}", method, urlAsString);
            URL url = new URL(urlAsString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method); // <--- Uses the method passed in
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json");
            if (authorizationHeader != null) {
                connection.setRequestProperty("Authorization", authorizationHeader);
            }

            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            connection.getOutputStream().write(bytes);
            connection.getOutputStream().flush();

            int code = connection.getResponseCode();

            String body;
            try (var in = (code >= 200 && code < 400)
                    ? connection.getInputStream()
                    : connection.getErrorStream()) {
                if (in != null) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } else {
                    body = "";
                }
            }

            LOG.debug("HTTP {} '{}' returned {}, body='{}'", method, urlAsString, code, body);
            return new HttpResponse(code, body);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    /**
     * Post request to create the service name repository in ranger admin ui
     * @param urlAsString
     * @param authorizationHeader
     * @param jsonBody
     * @return
     * @throws Exception
     */
    private int httpPostJsonStatus(String urlAsString, String authorizationHeader, String jsonBody) throws Exception {
        HttpURLConnection connection = null;
        try {
            LOG.debug("HTTP POST to Ranger: {}", urlAsString);
            URL url = new URL(urlAsString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json");
            if (authorizationHeader != null) {
                connection.setRequestProperty("Authorization", authorizationHeader);
            }
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            connection.getOutputStream().write(bytes);
            connection.getOutputStream().flush();
            int code = connection.getResponseCode();
            LOG.debug("HTTP POST '{}' returned {}", urlAsString, code);
            return code;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpResponse httpPostJsonWithBody(String urlAsString, String authorizationHeader, String jsonBody) throws Exception {
        HttpURLConnection connection = null;
        try {
            LOG.debug("HTTP POST to Ranger: {}", urlAsString);
            URL url = new URL(urlAsString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json");
            if (authorizationHeader != null) {
                connection.setRequestProperty("Authorization", authorizationHeader);
            }

            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            connection.getOutputStream().write(bytes);
            connection.getOutputStream().flush();

            int code = connection.getResponseCode();

            String body;
            try (var in = (code >= 200 && code < 400)
                    ? connection.getInputStream()
                    : connection.getErrorStream()) {
                if (in != null) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } else {
                    body = "";
                }
            }

            LOG.debug("HTTP POST '{}' returned {}, body='{}'", urlAsString, code, body);
            return new HttpResponse(code, body);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * helper to transform user/password to basic auth headers
     * @param userName
     * @param password
     * @return
     */
    private static String buildBasicAuth(String userName, String password) {
        String raw = userName + ":" + password;
        String base64 = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return "Basic " + base64;
    }

    /**
     * helper to encode the url
     * @param value
     * @return
     */
    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception exception) {
            return value;
        }
    }

    /**
     * helper to check if a string is empty or blank
     * @param value
     * @return
     */
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

    private static String defaultIfBlank(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    /**
     * convert a Map object to JSON to use it in a HTTP body as JSON content-type
     * @param map
     * @return
     */
    @SuppressWarnings("unchecked")
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
            } else if (value instanceof Map) {
                // recursively serialize nested map
                builder.append(toJson((Map<String, Object>) value));
            } else {
                // everything else as string
                builder.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        builder.append('}');
        return builder.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * naive password masker for JSON payloads with "password":"..."
     */
    private static String maskPassword(String json) {
        if (json == null || !json.contains("\"password\"")) {
            return json;
        }
        return json.replaceAll(
                "(\"password\"\\s*:\\s*\")([^\"\\\\]*)(\")",
                "$1*****$3"
        );
    }

    private static class HttpResponse {
        final int statusCode;
        final String body;

        HttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private HttpResponse httpGetWithBody(String urlAsString, String authorizationHeader) throws Exception {
        HttpURLConnection connection = null;
        try {
            LOG.debug("HTTP GET to Ranger: {}", urlAsString);
            URL url = new URL(urlAsString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/json");
            if (authorizationHeader != null) {
                connection.setRequestProperty("Authorization", authorizationHeader);
            }

            int code = connection.getResponseCode();

            String body;
            try (var in = (code >= 200 && code < 400)
                    ? connection.getInputStream()
                    : connection.getErrorStream()) {
                if (in != null) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } else {
                    body = "";
                }
            }

            LOG.debug("HTTP GET '{}' returned {}, body='{}'", urlAsString, code, body);
            return new HttpResponse(code, body);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}