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

package org.apache.ambari.view.k8s.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal Keycloak Admin REST client used by OIDC_REGISTER_CLIENT steps.
 * Parallels AmbariActionClient: one instance per operation, no pooling.
 *
 * Supported operations:
 *   - Obtain admin access token (client_credentials or password grant)
 *   - Create or update a Keycloak client in a realm
 *   - Retrieve the client secret
 *   - Optionally delete a client
 *
 * All HTTP calls are synchronous (java.net.http.HttpClient).
 */
public class KeycloakAdminClient {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakAdminClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String adminUrl;       // e.g. https://keycloak.example.com
    private final String adminRealm;     // realm used to obtain admin token (usually "master")
    private final String realm;          // target realm for client operations
    private final String clientId;       // admin client id (e.g. "admin-cli")
    private final String clientSecret;   // admin client secret (empty for public clients)
    private final boolean verifyTls;
    private final HttpClient http;

    /**
     * @param adminUrl      base URL of Keycloak (no trailing slash)
     * @param adminRealm    realm used to obtain the admin access token (usually "master")
     * @param realm         realm where service clients are registered
     * @param clientId      admin client_id
     * @param clientSecret  admin client_secret (may be blank for password-grant)
     * @param verifyTls     whether to verify the Keycloak TLS certificate
     */
    public KeycloakAdminClient(String adminUrl,
                               String adminRealm,
                               String realm,
                               String clientId,
                               String clientSecret,
                               boolean verifyTls) {
        this.adminUrl     = Objects.requireNonNull(adminUrl, "adminUrl").replaceAll("/$", "");
        this.adminRealm   = Objects.requireNonNull(adminRealm, "adminRealm");
        this.realm        = Objects.requireNonNull(realm, "realm");
        this.clientId     = Objects.requireNonNull(clientId, "clientId");
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        this.verifyTls    = verifyTls;
        this.http         = buildHttpClient(verifyTls);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Result returned by registerClient().
     */
    public record OidcClientResult(
            String clientId,
            String clientSecret,
            String issuerUrl
    ) {}

    /**
     * Register or update an OIDC client in the target realm.
     * Idempotent: if a client with {@code desiredClientId} already exists it is updated.
     *
     * @param desiredClientId          the client_id to register
     * @param redirectUri              redirect URI (may be null/blank to omit)
     * @param publicClient             whether this is a public (no-secret) client
     * @param standardFlowEnabled      whether Authorization Code flow is enabled
     * @param serviceAccountsEnabled   whether service-account (client-credentials) flow is enabled
     * @return OidcClientResult with resolved clientId, clientSecret, and issuerUrl
     */
    public OidcClientResult registerClient(String desiredClientId,
                                           String redirectUri,
                                           boolean publicClient,
                                           boolean standardFlowEnabled,
                                           boolean serviceAccountsEnabled) throws Exception {
        String token = obtainAdminToken();

        // Check if client already exists
        String existingUuid = findClientUuid(token, desiredClientId);

        Map<String, Object> clientRep = buildClientRepresentation(
                desiredClientId, redirectUri, publicClient, standardFlowEnabled, serviceAccountsEnabled);

        if (existingUuid != null) {
            LOG.info("KeycloakAdminClient: client '{}' already exists (uuid={}), updating", desiredClientId, existingUuid);
            updateClient(token, existingUuid, clientRep);
        } else {
            LOG.info("KeycloakAdminClient: creating new client '{}'", desiredClientId);
            existingUuid = createClient(token, clientRep);
        }

        String secret = "";
        if (!publicClient) {
            secret = getClientSecret(token, existingUuid);
        }

        String issuerUrl = adminUrl + "/realms/" + enc(realm);
        LOG.info("KeycloakAdminClient: client '{}' registered in realm '{}', issuerUrl={}",
                desiredClientId, realm, issuerUrl);
        return new OidcClientResult(desiredClientId, secret, issuerUrl);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Obtain a short-lived admin access token using client_credentials (or password) grant.
     */
    private String obtainAdminToken() throws Exception {
        String tokenUrl = adminUrl + "/realms/" + enc(adminRealm)
                + "/protocol/openid-connect/token";

        String body;
        if (clientSecret.isBlank()) {
            // password grant fallback — clientId is assumed to be "admin-cli" with empty secret
            body = "grant_type=client_credentials"
                    + "&client_id=" + enc(clientId);
        } else {
            body = "grant_type=client_credentials"
                    + "&client_id=" + enc(clientId)
                    + "&client_secret=" + enc(clientSecret);
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException(
                    "Keycloak token endpoint returned HTTP " + resp.statusCode()
                            + " for realm=" + adminRealm + ": " + trim(resp.body()));
        }

        Map<String, Object> json = JSON.readValue(resp.body(), new TypeReference<>() {});
        String token = (String) json.get("access_token");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Keycloak token response missing access_token");
        }
        LOG.debug("KeycloakAdminClient: obtained admin token for realm '{}'", adminRealm);
        return token;
    }

    /**
     * Returns the internal UUID of an existing client, or null if not found.
     */
    private String findClientUuid(String token, String targetClientId) throws Exception {
        String url = adminUrl + "/admin/realms/" + enc(realm)
                + "/clients?clientId=" + enc(targetClientId) + "&exact=true";

        HttpRequest req = bearer(token, url).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() == 404) return null;
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Keycloak GET clients returned HTTP " + resp.statusCode()
                    + ": " + trim(resp.body()));
        }

        List<Map<String, Object>> list = JSON.readValue(resp.body(), new TypeReference<>() {});
        if (list == null || list.isEmpty()) return null;
        return (String) list.get(0).get("id");
    }

    /**
     * Creates a new client and returns its UUID (extracted from the Location header).
     */
    private String createClient(String token, Map<String, Object> clientRep) throws Exception {
        String url = adminUrl + "/admin/realms/" + enc(realm) + "/clients";
        String body = JSON.writeValueAsString(clientRep);

        HttpRequest req = bearer(token, url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 201) {
            throw new IllegalStateException("Keycloak POST /clients returned HTTP " + resp.statusCode()
                    + ": " + trim(resp.body()));
        }

        // Location: .../clients/{uuid}
        String location = resp.headers().firstValue("Location").orElse(null);
        if (location == null) {
            throw new IllegalStateException("Keycloak POST /clients did not return a Location header");
        }
        String uuid = location.substring(location.lastIndexOf('/') + 1);
        LOG.info("KeycloakAdminClient: created client uuid={}", uuid);
        return uuid;
    }

    /**
     * Updates an existing client by UUID.
     */
    private void updateClient(String token, String uuid, Map<String, Object> clientRep) throws Exception {
        String url = adminUrl + "/admin/realms/" + enc(realm) + "/clients/" + uuid;
        String body = JSON.writeValueAsString(clientRep);

        HttpRequest req = bearer(token, url)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 204) {
            throw new IllegalStateException("Keycloak PUT /clients/" + uuid
                    + " returned HTTP " + resp.statusCode() + ": " + trim(resp.body()));
        }
    }

    /**
     * Retrieves the current client secret from Keycloak.
     */
    private String getClientSecret(String token, String uuid) throws Exception {
        String url = adminUrl + "/admin/realms/" + enc(realm) + "/clients/" + uuid + "/client-secret";
        HttpRequest req = bearer(token, url).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Keycloak GET client-secret returned HTTP "
                    + resp.statusCode() + ": " + trim(resp.body()));
        }

        Map<String, Object> json = JSON.readValue(resp.body(), new TypeReference<>() {});
        String secret = (String) json.get("value");
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Keycloak client-secret response has no 'value' field");
        }
        return secret;
    }

    private Map<String, Object> buildClientRepresentation(String id,
                                                           String redirectUri,
                                                           boolean publicClient,
                                                           boolean standardFlowEnabled,
                                                           boolean serviceAccountsEnabled) {
        java.util.LinkedHashMap<String, Object> rep = new java.util.LinkedHashMap<>();
        rep.put("clientId", id);
        rep.put("enabled", true);
        rep.put("publicClient", publicClient);
        rep.put("standardFlowEnabled", standardFlowEnabled);
        rep.put("implicitFlowEnabled", false);
        rep.put("directAccessGrantsEnabled", false);
        rep.put("serviceAccountsEnabled", serviceAccountsEnabled);
        rep.put("protocol", "openid-connect");
        if (redirectUri != null && !redirectUri.isBlank()) {
            rep.put("redirectUris", List.of(redirectUri));
        }
        return rep;
    }

    // -------------------------------------------------------------------------
    // HTTP / TLS utilities
    // -------------------------------------------------------------------------

    private HttpRequest.Builder bearer(String token, String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token);
    }

    private static HttpClient buildHttpClient(boolean verifyTls) {
        if (verifyTls) {
            return HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .build();
        }
        // Skip TLS verification (operator-configured, e.g. internal Keycloak with self-signed cert)
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new java.security.SecureRandom());
            return HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .sslContext(sslContext)
                    .build();
        } catch (Exception e) {
            LOG.warn("KeycloakAdminClient: failed to build no-verify TLS client, falling back to default: {}", e.toString());
            return HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
