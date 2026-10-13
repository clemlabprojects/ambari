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
import org.apache.ambari.view.k8s.utils.TlsPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
    /** Keycloak's mapper that adds an audience to the access tokens issued to a client. */
    static final String AUDIENCE_MAPPER = "oidc-audience-mapper";

    private final String adminUrl;        // e.g. https://keycloak.example.com
    private final String adminRealm;      // realm used to obtain admin token (usually "master")
    private final String realm;           // target realm for client operations
    private final String clientId;        // admin client id (e.g. "admin-cli")
    private final String clientSecret;    // admin client secret — blank for public clients
    private final String adminUsername;   // admin username for password grant (public clients)
    private final String adminPassword;   // admin password for password grant (public clients)
    private final boolean verifyTls;
    private final HttpClient http;

    /**
     * @param adminUrl       base URL of Keycloak (no trailing slash)
     * @param adminRealm     realm used to obtain the admin access token (usually "master")
     * @param realm          realm where service clients are registered
     * @param clientId       admin client_id (e.g. "admin-cli")
     * @param clientSecret   admin client_secret — blank for public clients (admin-cli)
     * @param adminUsername  admin username for password grant — used when clientSecret is blank
     * @param adminPassword  admin password for password grant — used when clientSecret is blank
     * @param verifyTls      whether to verify the Keycloak TLS certificate
     */
    public KeycloakAdminClient(String adminUrl,
                               String adminRealm,
                               String realm,
                               String clientId,
                               String clientSecret,
                               String adminUsername,
                               String adminPassword,
                               boolean verifyTls) {
        this.adminUrl      = Objects.requireNonNull(adminUrl, "adminUrl").replaceAll("/$", "");
        this.adminRealm    = Objects.requireNonNull(adminRealm, "adminRealm");
        this.realm         = Objects.requireNonNull(realm, "realm");
        this.clientId      = Objects.requireNonNull(clientId, "clientId");
        this.clientSecret  = clientSecret == null ? "" : clientSecret;
        this.adminUsername = adminUsername == null ? "" : adminUsername;
        this.adminPassword = adminPassword == null ? "" : adminPassword;
        this.verifyTls     = verifyTls;
        this.http          = buildHttpClient(verifyTls, this.adminUrl);
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
     * @param implicitFlowEnabled      whether Implicit flow (response_type=id_token/token) is enabled
     * @param serviceAccountsEnabled   whether service-account (client-credentials) flow is enabled
     * @return OidcClientResult with resolved clientId, clientSecret, and issuerUrl
     */
    public OidcClientResult registerClient(String desiredClientId,
                                           String redirectUri,
                                           boolean publicClient,
                                           boolean standardFlowEnabled,
                                           boolean implicitFlowEnabled,
                                           boolean serviceAccountsEnabled) throws Exception {
        return registerClient(desiredClientId, redirectUri, publicClient, standardFlowEnabled, implicitFlowEnabled,
                serviceAccountsEnabled, null);
    }

    /**
     * {@link #registerClient(String, String, boolean, boolean, boolean, boolean)} that also sets the audiences the
     * client's access tokens carry. A service that checks the audience of the tokens it accepts (Trino's JWT
     * authenticator) only takes those minted for it, so the client of a service calling it with its own token
     * (Superset's service token) must name that service as an audience.
     *
     * <p>Keycloak replaces a client's protocol mappers with the ones an update sends, so the audiences are always
     * sent: {@code null} keeps the audience mappers the client has (a re-registration that does not know them),
     * a list, empty included, makes them exactly that.
     *
     * @param audiences the audiences, or null to keep the client's current ones
     */
    public OidcClientResult registerClient(String desiredClientId,
                                           String redirectUri,
                                           boolean publicClient,
                                           boolean standardFlowEnabled,
                                           boolean implicitFlowEnabled,
                                           boolean serviceAccountsEnabled,
                                           List<String> audiences) throws Exception {
        String token = obtainAdminToken();

        // Check if client already exists
        String existingUuid = findClientUuid(token, desiredClientId);

        List<Map<String, Object>> audienceMappers;
        if (audiences != null) {
            audienceMappers = audienceMappers(audiences);
        } else if (existingUuid != null) {
            audienceMappers = currentAudienceMappers(token, existingUuid);
        } else {
            audienceMappers = List.of();
        }
        Map<String, Object> clientRep = buildClientRepresentation(
                desiredClientId, redirectUri, publicClient, standardFlowEnabled, implicitFlowEnabled, serviceAccountsEnabled,
                audienceMappers);

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

    /**
     * Adds an audience to the access tokens of an existing client, leaving the rest of the client as it is: a service
     * that starts checking the audience of the JWTs it accepts (a Trino redeploy) must not refuse the tokens of a
     * client registered before it did (Superset's), which only its own redeploy would otherwise update. Adding a
     * single protocol mapper, rather than updating the client, keeps whatever that client's own deploy set.
     *
     * @return true when the client's tokens carry the audience (added now, or already there); false when the realm
     *         has no such client
     */
    public boolean addAudience(String targetClientId, String audience) throws Exception {
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("audience is blank");
        }
        String token = obtainAdminToken();
        String uuid = findClientUuid(token, targetClientId);
        if (uuid == null) return false;
        String wanted = audience.trim();
        for (Map<String, Object> m : currentAudienceMappers(token, uuid)) {
            if (m.get("config") instanceof Map<?, ?> config
                    && wanted.equals(String.valueOf(config.get("included.custom.audience")))) {
                LOG.info("KeycloakAdminClient: tokens of client '{}' already carry the audience '{}'",
                        targetClientId, wanted);
                return true;
            }
        }
        String url = adminUrl + "/admin/realms/" + enc(realm) + "/clients/" + uuid + "/protocol-mappers/models";
        HttpRequest req = bearer(token, url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(audienceMappers(List.of(wanted)).get(0))))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 201) {
            throw new IllegalStateException("Keycloak POST protocol mapper returned HTTP " + resp.statusCode()
                    + ": " + trim(resp.body()));
        }
        LOG.info("KeycloakAdminClient: tokens of client '{}' now carry the audience '{}'", targetClientId, wanted);
        return true;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Obtain a short-lived admin access token.
     *
     * Two strategies depending on how the admin client is configured:
     *   - Confidential client (clientSecret non-blank): client_credentials grant
     *   - Public client (admin-cli, blank secret):      password grant with adminUsername/adminPassword
     */
    private String obtainAdminToken() throws Exception {
        String tokenUrl = adminUrl + "/realms/" + enc(adminRealm)
                + "/protocol/openid-connect/token";

        String body;
        if (!adminUsername.isBlank()) {
            // Password grant — preferred when user credentials are available (credential store).
            // Works for both public clients (no secret) and confidential clients (secret included).
            // Requires directAccessGrantsEnabled=true on the client.
            body = "grant_type=password"
                    + "&client_id=" + enc(clientId)
                    + "&username=" + enc(adminUsername)
                    + "&password=" + enc(adminPassword);
            if (!clientSecret.isBlank()) {
                body += "&client_secret=" + enc(clientSecret);
            }
        } else if (!clientSecret.isBlank()) {
            // Client-credentials grant — fallback when no user credential is available.
            // Requires serviceAccountsEnabled=true on the client.
            body = "grant_type=client_credentials"
                    + "&client_id=" + enc(clientId)
                    + "&client_secret=" + enc(clientSecret);
        } else {
            throw new IllegalStateException(
                    "KeycloakAdminClient: no admin credential available. "
                    + "Store a PrincipalKeyCredential at oidc.admin.credential in the Ambari "
                    + "credential store, or set oidc_admin_client_secret for client-credentials flow.");
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

    /**
     * The audience mappers of an existing client, as Keycloak returns them minus their ids, so that an update
     * sending them back keeps them.
     */
    private List<Map<String, Object>> currentAudienceMappers(String token, String uuid) throws Exception {
        String url = adminUrl + "/admin/realms/" + enc(realm) + "/clients/" + uuid + "/protocol-mappers/models";
        HttpResponse<String> resp = http.send(bearer(token, url).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Keycloak GET protocol mappers returned HTTP " + resp.statusCode()
                    + ": " + trim(resp.body()));
        }
        List<Map<String, Object>> mappers = JSON.readValue(resp.body(), new TypeReference<>() {});
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> m : mappers == null ? List.<Map<String, Object>>of() : mappers) {
            if (m == null || !AUDIENCE_MAPPER.equals(m.get("protocolMapper"))) continue;
            Map<String, Object> copy = new java.util.LinkedHashMap<>(m);
            copy.remove("id");
            kept.add(copy);
        }
        return kept;
    }

    /**
     * One audience mapper per distinct, non-blank audience. The audience is a custom one, not a reference to a
     * client of the realm: it is the identifier the calling service checks, and the client it names may be
     * registered after this one.
     */
    static List<Map<String, Object>> audienceMappers(List<String> audiences) {
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String a : audiences == null ? List.<String>of() : audiences) {
            if (a != null && !a.isBlank()) distinct.add(a.trim());
        }
        List<Map<String, Object>> mappers = new ArrayList<>();
        for (String audience : distinct) {
            mappers.add(Map.of(
                    "name", "audience " + audience,
                    "protocol", "openid-connect",
                    "protocolMapper", AUDIENCE_MAPPER,
                    "config", Map.of(
                            "included.custom.audience", audience,
                            "access.token.claim", "true",
                            "id.token.claim", "false",
                            "introspection.token.claim", "true"
                    )
            ));
        }
        return mappers;
    }

    /** The client representation sent to Keycloak on a create and on an update. */
    static Map<String, Object> buildClientRepresentation(String id,
                                                          String redirectUri,
                                                          boolean publicClient,
                                                          boolean standardFlowEnabled,
                                                          boolean implicitFlowEnabled,
                                                          boolean serviceAccountsEnabled,
                                                          List<Map<String, Object>> audienceMappers) {
        java.util.LinkedHashMap<String, Object> rep = new java.util.LinkedHashMap<>();
        rep.put("clientId", id);
        rep.put("enabled", true);
        rep.put("publicClient", publicClient);
        rep.put("standardFlowEnabled", standardFlowEnabled);
        rep.put("implicitFlowEnabled", implicitFlowEnabled);
        rep.put("directAccessGrantsEnabled", false);
        rep.put("serviceAccountsEnabled", serviceAccountsEnabled);
        rep.put("protocol", "openid-connect");
        if (redirectUri != null && !redirectUri.isBlank()) {
            // SPA-style apps (OpenMetadata 1.x) drive an iframe silent token
            // refresh against a SECOND redirect path (/silent-callback). When
            // /callback is the only URI registered, Keycloak rejects the
            // silent-refresh and the user gets intermittent "Invalid redirect
            // uri" pages. We auto-expand a single `…/callback` URI into the
            // canonical SPA set; other apps (Superset's /oauth-authorized/…,
            // JupyterHub's /hub/oauth_callback) won't match this suffix and
            // remain unchanged.
            java.util.LinkedHashSet<String> uris = new java.util.LinkedHashSet<>();
            uris.add(redirectUri);
            if (redirectUri.endsWith("/callback")) {
                String base = redirectUri.substring(0, redirectUri.length() - "/callback".length());
                uris.add(base + "/silent-callback");
                uris.add(base + "/signed-in");
            }
            rep.put("redirectUris", new java.util.ArrayList<>(uris));
            // Whitelist post-logout redirect URIs so RP-Initiated Logout works.
            // Without this Keycloak (18+) silently drops the post_logout_redirect_uri
            // query parameter on /protocol/openid-connect/logout, the SSO session
            // stays alive after the app's /logout, and the next "Sign in" silently
            // re-authenticates the same user. We seed the app's host root + /login/
            // since FAB-based apps land users on /login/ post-logout.
            String postLogoutBase = deriveBaseUrl(redirectUri);
            if (postLogoutBase != null) {
                // Whitelist the app's own origin AND any path beneath it. Different apps land on
                // different post-logout paths (FAB → /login/, OpenMetadata → /signin, Trino → /ui/),
                // so an exact "/" + "/login/" list rejected some with "Invalid redirect uri". A
                // bare-origin + wildcard pair (Keycloak '*' matches any sub-path) covers them all
                // while still confining post-logout redirects to the service's own host.
                rep.put("attributes", new java.util.LinkedHashMap<>(Map.of(
                        "post.logout.redirect.uris",
                        postLogoutBase + "##" + postLogoutBase + "/*"
                )));
            }
        }
        // Ensure the ID token + userinfo response include the "groups" claim so
        // Superset (and any KDPS service relying on AUTH_ROLES_MAPPING) can map
        // group → role. Without this mapper Keycloak emits no groups even when
        // the user has them in FreeIPA/LDAP, so every signed-in user gets the
        // default registration role (Gamma in our charts) and never escalates
        // to Admin no matter what's configured under security.adminGroups.
        List<Map<String, Object>> mappers = new ArrayList<>();
        mappers.add(
                Map.of(
                        "name", "groups",
                        "protocol", "openid-connect",
                        "protocolMapper", "oidc-group-membership-mapper",
                        "config", Map.of(
                                "claim.name", "groups",
                                // full.path=false emits short names ("hadoop_admins")
                                // rather than DN-like paths ("/hadoop_admins"). Our
                                // AUTH_ROLES_MAPPING is built from short names.
                                "full.path", "false",
                                "id.token.claim", "true",
                                "access.token.claim", "true",
                                "userinfo.token.claim", "true"
                        )
                ));
        if (audienceMappers != null) mappers.addAll(audienceMappers);
        rep.put("protocolMappers", mappers);
        return rep;
    }

    /**
     * Extract scheme://authority from a full URL, e.g.
     * "https://superset-test-2.dev.clemlab.com/oauth-authorized/generic"
     *   → "https://superset-test-2.dev.clemlab.com".
     * Used to seed Keycloak's post.logout.redirect.uris attribute from the
     * redirectUri the wizard already validated.
     */
    private static String deriveBaseUrl(String url) {
        try {
            URI u = URI.create(url);
            String authority = u.getAuthority();
            String scheme = u.getScheme();
            if (authority != null && scheme != null) {
                return scheme + "://" + authority;
            }
        } catch (Exception ignore) {}
        return null;
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

    private static HttpClient buildHttpClient(boolean verifyTls, String adminUrl) {
        if (verifyTls) {
            return HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .build();
        }
        // Skip TLS verification (operator-configured, e.g. internal Keycloak with self-signed cert). The policy's
        // trust manager also skips the host-name check, for this client only: java.net.http checks the name
        // around a plain X509TrustManager, which refuses a Keycloak reached by IP or by a name its certificate
        // does not cover.
        try {
            return HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .sslContext(TlsPolicy.trustingAnyCertificate("Keycloak at " + adminUrl)
                            .sslContext(URI.create(adminUrl).getHost()))
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
