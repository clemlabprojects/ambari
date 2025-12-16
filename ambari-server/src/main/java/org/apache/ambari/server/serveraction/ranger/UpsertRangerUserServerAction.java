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

import java.io.InputStream;
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
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.serveraction.AbstractServerAction;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.utils.SecretReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

public class UpsertRangerUserServerAction extends AbstractServerAction {

    private static final Logger LOG = LoggerFactory.getLogger(UpsertRangerUserServerAction.class);

    @Inject
    private AmbariManagementController managementController;

    @Inject
    private Configuration configuration;

    @Override
    public CommandReport execute(ConcurrentMap<String, Object> requestSharedDataContext)
            throws AmbariException {

        Map<String, String> commandParameters = getCommandParameters();
        if (commandParameters == null) {
            commandParameters = Map.of();
        }

        String clusterName        = commandParameters.get("clusterName");
        String pluginUserName     = commandParameters.get("pluginUserName");
        String pluginUserPassword = commandParameters.get("pluginUserPassword");
        String repositoryDescription  = commandParameters.get("repositoryDescription");

        LOG.info("UpsertRangerUserServerAction called with parameters: clusterName='{}', pluginUserName='{}', repositoryDescription='{}'",
                clusterName, pluginUserName, repositoryDescription);

        actionLog.writeStdOut("Starting UpsertRangerUserServerAction with:");
        actionLog.writeStdOut("  clusterName      = " + clusterName);
        actionLog.writeStdOut("  pluginUserName   = " + pluginUserName);

        if (isBlank(clusterName)) {
            String message = "Required parameter 'clusterName' is missing or empty";
            actionLog.writeStdErr(message);
            LOG.error(message);
            return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), message);
        }
        if (isBlank(pluginUserName)) {
            String message = "Required parameter 'pluginUserName' is missing or empty";
            actionLog.writeStdErr(message);
            LOG.error(message);
            return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), message);
        }
        if (isBlank(pluginUserPassword)) {
            String message = "Required parameter 'pluginUserPassword' is missing or empty";
            actionLog.writeStdErr(message);
            LOG.error(message);
            return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), message);
        }

        try {
            Clusters clusters = managementController.getClusters();
            Cluster cluster   = clusters.getCluster(clusterName);
            LOG.info("Loaded cluster '{}' for Ranger user upsert", clusterName);

            Config adminProperties = cluster.getDesiredConfigByType("admin-properties");
            if (adminProperties == null) {
                String message = "Missing 'admin-properties' config for cluster " + clusterName;
                actionLog.writeStdErr(message);
                LOG.error(message);
                return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), message);
            }
            LOG.info("Found 'admin-properties' for cluster '{}'", clusterName);

            String policymgrExternalUrl = trimToNull(
                    adminProperties.getProperties().get("policymgr_external_url"));
            if (policymgrExternalUrl == null) {
                String message = "admin-properties/policymgr_external_url is not set for cluster " + clusterName;
                actionLog.writeStdErr(message);
                LOG.error(message);
                return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), message);
            }

            LOG.info("Using Ranger URL '{}' for user upsert", policymgrExternalUrl);

            Config rangerEnv = cluster.getDesiredConfigByType("ranger-env");
            if (rangerEnv == null) {
                String message = "Missing 'ranger-env' config for cluster " + clusterName;
                actionLog.writeStdErr(message);
                LOG.error(message);
                return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), message);
            }
            LOG.info("Found 'ranger-env' for cluster '{}'", clusterName);

            String rangerAdminUserName = trimToNull(
                    rangerEnv.getProperties().get("ranger_admin_username"));
            if (rangerAdminUserName == null) {
                String message = "ranger-env/ranger_admin_username is not set for cluster " + clusterName;
                actionLog.writeStdErr(message);
                LOG.error(message);
                return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), message);
            }

            LOG.info("Ranger admin username for cluster '{}': '{}'", clusterName, rangerAdminUserName);

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
            LOG.info("Upserting Ranger user '{}' via '{}'", pluginUserName, policymgrExternalUrl);

            upsertRangerUser(
                    policymgrExternalUrl,
                    rangerAdminUserName,
                    rangerAdminPassword,
                    pluginUserName,
                    pluginUserPassword
            );

            Map<String, Object> structuredOutMap = new HashMap<>();
            structuredOutMap.put("clusterName", clusterName);
            structuredOutMap.put("pluginUserName", pluginUserName);

            String structuredOut = toJson(structuredOutMap);
            String stdoutMessage = "Ranger user '" + pluginUserName + "' created/updated successfully";
            actionLog.writeStdOut(stdoutMessage);
            LOG.info("Ranger user '{}' created/updated successfully", pluginUserName);

            return createCommandReport(0, HostRoleStatus.COMPLETED,
                    structuredOut, stdoutMessage, actionLog.getStdErr());

        } catch (Exception exception) {
            String error = "Failed to upsert Ranger user: " + exception.getMessage();
            LOG.error(error, exception);
            actionLog.writeStdErr(error);
            return createCommandReport(0, HostRoleStatus.FAILED,
                    "{}", actionLog.getStdOut(), actionLog.getStdErr());
        }
    }

    private void upsertRangerUser(
            String baseUrl,
            String adminUserName,
            String adminPassword,
            String pluginUserName,
            String pluginUserPassword) throws Exception {

        String authorizationHeader = buildBasicAuth(adminUserName, adminPassword);

        // ---------- EXISTENCE CHECK ----------
        boolean exists = rangerUserExists(baseUrl, authorizationHeader, pluginUserName);
        String lookupMsg = "User lookup for '" + pluginUserName + "' returned " +
                (exists ? "EXISTS" : "NOT EXISTS");
        actionLog.writeStdOut(lookupMsg);
        LOG.info(lookupMsg);

        if (exists) {
            String msg = "Ranger user '" + pluginUserName + "' already exists; skipping creation";
            actionLog.writeStdOut(msg);
            LOG.info(msg);
            return;
        }

        actionLog.writeStdOut("Ranger user '" + pluginUserName +
                "' does not exist; will create as internal secure user");
        LOG.info("Ranger user '{}' not found; creating internal secure user", pluginUserName);

        // ---------- CREATE INTERNAL SECURE USER ----------
        String createUrl = baseUrl + "/service/xusers/secure/users";
        actionLog.writeStdOut("Creating internal Ranger user via URL: " + createUrl);
        LOG.info("Creating Ranger secure user via URL '{}'", createUrl);

        // Pre-escaped / safe values
        String safeUser     = escape(pluginUserName);
        String safePassword = escape(pluginUserPassword);
        // Ranger wants a "proper" email with a dot in the domain
        String email        = safeUser + "@odp.com";

        StringBuilder json = new StringBuilder();
        json.append('{');
        json.append("\"status\":1,");
        json.append("\"userRoleList\":[\"ROLE_USER\"],");
        json.append("\"name\":\"").append(safeUser).append("\",");
        json.append("\"firstName\":\"").append(safeUser).append("\",");
        json.append("\"lastName\":\"").append(safeUser).append("\",");
        json.append("\"emailAddress\":\"").append(email).append("\",");
        json.append("\"password\":\"").append(safePassword).append("\",");
        json.append("\"description\":\"").append(safeUser)
                .append(" user needed for repository creation\"");
        json.append('}');

        String jsonBody = json.toString();

        // Mask password in logs
        String maskedJson = jsonBody.replace(
                "\"password\":\"" + safePassword + "\"",
                "\"password\":\"*****\""
        );
        actionLog.writeStdOut("Secure user JSON payload (masked): " + maskedJson);
        LOG.debug("Secure user JSON payload (masked) for '{}': {}", safeUser, maskedJson);

        HttpURLConnection connection = null;
        int status;
        String responseBody = null;
        try {
            URL url = new URL(createUrl);
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

            status = connection.getResponseCode();

            InputStream is =
                    status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (is != null) {
                responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        actionLog.writeStdOut("Create secure user HTTP status: " + status);
        LOG.info("Create secure user HTTP status for '{}': {}", pluginUserName, status);

        if (responseBody != null) {
            actionLog.writeStdOut("Create secure user response body: " + responseBody);
            LOG.info("Ranger secure user response body for '{}': {}", pluginUserName, responseBody);
        } else {
            actionLog.writeStdOut("Create secure user response body: <null>");
            LOG.info("Ranger secure user response body for '{}' is <null>", pluginUserName);
        }

        if (status / 100 == 2) {
            String msg = "Ranger user '" + pluginUserName +
                    "' created successfully as internal user";
            actionLog.writeStdOut(msg);
            LOG.info(msg);
            return;
        }

        String msg = "Failed to create Ranger user '" + pluginUserName +
                "': HTTP " + status +
                (responseBody != null ? (", body: " + responseBody) : "");
        throw new IllegalStateException(msg);
    }

    private int httpGetStatus(String urlAsString, String authorizationHeader) throws Exception {
        HttpURLConnection connection = null;
        try {
            LOG.debug("HTTP GET to Ranger (user check): {}", urlAsString);
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

    private static String buildBasicAuth(String userName, String password) {
        String raw = userName + ":" + password;
        String base64 = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return "Basic " + base64;
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception exception) {
            return value;
        }
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

    private static String escape(String value) {

        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * check if the ranger plugin user does exist in ranger admin v2 path
     * @param baseUrl
     * @return
     * @throws Exception
     */
    private boolean rangerUserExists(String baseUrl, String authHeader, String userName) throws Exception {
        String queryUrl = baseUrl + "/service/xusers/users?name=" + urlEncode(userName);
        actionLog.writeStdOut("Checking Ranger user existence via URL: " + queryUrl);
        LOG.info("Checking Ranger user existence for '{}' via URL '{}'", userName, queryUrl);

        HttpURLConnection connection = null;
        int status;
        String body = null;
        try {
            URL url = new URL(queryUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/json");
            if (authHeader != null) {
                connection.setRequestProperty("Authorization", authHeader);
            }
            status = connection.getResponseCode();
            java.io.InputStream is =
                    status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (is != null) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        String statusMsg = "User lookup HTTP status " + status + " for user '" + userName + "'";
        actionLog.writeStdOut(statusMsg);
        LOG.info(statusMsg);

        if (body != null) {
            actionLog.writeStdOut("User lookup response body: " + body);
            LOG.debug("User lookup response body for '{}': {}", userName, body);
        }

        if (status != 200 || body == null || body.isEmpty()) {
            return false;
        }

        // basic "contains user" check compatible with Ranger's vXUsers payload
        return body.contains("\"name\":\"" + escape(userName) + "\"");
    }
}