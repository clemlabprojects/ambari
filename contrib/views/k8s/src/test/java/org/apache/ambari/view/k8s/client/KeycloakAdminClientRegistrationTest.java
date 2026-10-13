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
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.ambari.view.k8s.client.KeycloakAdminClient.ClientMarker;
import org.apache.ambari.view.k8s.client.KeycloakAdminClient.OidcClientResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The registration of the OIDC client of a release against a Keycloak realm kept in memory: the client is created
 * once and updated by every later deploy, with the redirect URIs the release signs users in through, and the
 * administrator logs in with the credential Ambari holds.
 */
class KeycloakAdminClientRegistrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_SECRET = "Kc-Admin-Secret-31";
    private static final String CLIENT_SECRET = "Kc-Client-Secret-58";
    private static final ClientMarker MARK = ClientMarker.of("analytics", "superset", "instance-a");

    private final MockWebServer keycloak = new MockWebServer();
    /** The clients of realm "odp", by uuid. */
    private final Map<String, Map<String, Object>> clients = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> tokenRequests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startKeycloak() throws Exception {
        Logger.getLogger(MockWebServer.class.getName()).setLevel(Level.WARNING);
        keycloak.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    return answer(request);
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500).setBody(e.toString());
                }
            }
        });
        keycloak.start();
    }

    @AfterEach
    void stopKeycloak() throws Exception {
        keycloak.shutdown();
    }

    @Test
    void theClientIsCreatedOnceThenUpdated() throws Exception {
        OidcClientResult first = admin().registerClient("superset-analytics",
                "https://superset.example.org/oauth-authorized/generic", false, true, false, true, List.of(), MARK);
        OidcClientResult second = admin().registerClient("superset-analytics",
                "https://superset.example.org/oauth-authorized/generic", false, true, false, true, List.of(), MARK);

        assertEquals(1, clients.size(), "a redeploy updates the client: " + requests);
        assertEquals(1, requests.stream().filter(r -> r.equals("POST /admin/realms/odp/clients")).count(), requests::toString);
        assertEquals(1, requests.stream().filter(r -> r.startsWith("PUT /admin/realms/odp/clients/")).count(), requests::toString);
        for (OidcClientResult result : List.of(first, second)) {
            assertEquals("superset-analytics", result.clientId());
            assertEquals(CLIENT_SECRET, result.clientSecret());
            assertEquals(baseUrl() + "/realms/odp", result.issuerUrl());
            assertEquals("odp", result.realm());
        }
    }

    @Test
    void theClientSignsUsersInThroughTheReleaseOnly() throws Exception {
        admin().registerClient("superset-analytics", "https://superset.example.org/oauth-authorized/generic", false,
                true, false, true, List.of(), MARK);

        Map<String, Object> client = clients.values().iterator().next();
        assertEquals(List.of("https://superset.example.org/oauth-authorized/generic"), client.get("redirectUris"));
        assertEquals(Boolean.FALSE, client.get("publicClient"));
        assertEquals(Boolean.TRUE, client.get("standardFlowEnabled"));
        assertEquals(Boolean.FALSE, client.get("implicitFlowEnabled"));
        assertEquals(Boolean.FALSE, client.get("directAccessGrantsEnabled"), "no password grant for a release");
        Map<?, ?> attributes = (Map<?, ?>) client.get("attributes");
        assertEquals("https://superset.example.org##https://superset.example.org/*",
                attributes.get("post.logout.redirect.uris"));
        assertEquals("analytics/superset", attributes.get("kdps.release"));
    }

    @Test
    void aSinglePageAppAlsoGetsItsSilentRefreshRedirects() throws Exception {
        admin().registerClient("openmetadata-analytics", "https://om.example.org/callback", true, true, false, false,
                null, null);

        Map<String, Object> client = clients.values().iterator().next();
        assertEquals(List.of("https://om.example.org/callback", "https://om.example.org/silent-callback",
                "https://om.example.org/signed-in"), client.get("redirectUris"));
    }

    @Test
    void aPublicClientHasNoSecretToRead() throws Exception {
        OidcClientResult result = admin().registerClient("jupyterhub-analytics",
                "https://jupyter.example.org/hub/oauth_callback", true, true, false, false, null, null);

        assertEquals("", result.clientSecret());
        assertTrue(requests.stream().noneMatch(r -> r.endsWith("/client-secret")), requests::toString);
    }

    @Test
    void theAdministratorLogsInWithTheClientCredentialAmbariHolds() throws Exception {
        admin().registerClient("superset-analytics", null, false, true, false, true, null, null);

        assertFalse(tokenRequests.isEmpty());
        for (String form : tokenRequests) {
            Map<String, String> fields = form(form);
            assertEquals("client_credentials", fields.get("grant_type"));
            assertEquals("kdps-admin", fields.get("client_id"));
            assertEquals(ADMIN_SECRET, fields.get("client_secret"));
        }
        assertTrue(requests.contains("POST /realms/master/protocol/openid-connect/token"), requests::toString);
    }

    @Test
    void aRefusedAdministratorFailsTheRegistrationWithKeycloaksAnswer() {
        KeycloakAdminClient wrongSecret = new KeycloakAdminClient(baseUrl(), "master", "odp", "kdps-admin",
                "not-the-secret", "", "", true);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> wrongSecret.registerClient(
                "superset-analytics", null, false, true, false, true, null, null));

        assertTrue(refused.getMessage().contains("HTTP 401"), refused.getMessage());
        assertTrue(clients.isEmpty());
    }

    // ------------------------------------------------------------------------------------------------------------

    private KeycloakAdminClient admin() {
        return new KeycloakAdminClient(baseUrl(), "master", "odp", "kdps-admin", ADMIN_SECRET, "", "", true);
    }

    private String baseUrl() {
        String url = keycloak.url("/").toString();
        return url.substring(0, url.length() - 1);
    }

    /** The part of Keycloak's admin API the registration uses, on realm "odp". */
    private MockResponse answer(RecordedRequest request) throws Exception {
        String method = request.getMethod();
        String path = request.getPath();
        String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String body = request.getBody().readUtf8();
        requests.add(method + " " + bare);
        if ("POST".equals(method) && bare.equals("/realms/master/protocol/openid-connect/token")) {
            tokenRequests.add(body);
            if (!ADMIN_SECRET.equals(form(body).get("client_secret"))) {
                return json(401, "{\"error\":\"unauthorized_client\"}");
            }
            return json(200, "{\"access_token\":\"admin-token\"}");
        }
        if (!"Bearer admin-token".equals(request.getHeader("Authorization"))) {
            return json(401, "{}");
        }
        String clientsPath = "/admin/realms/odp/clients";
        if ("GET".equals(method) && bare.equals(clientsPath)) {
            String wanted = form(path.substring(path.indexOf('?') + 1)).get("clientId");
            List<Map<String, Object>> found = new ArrayList<>();
            clients.values().stream().filter(c -> wanted.equals(c.get("clientId"))).forEach(found::add);
            return json(200, JSON.writeValueAsString(found));
        }
        if ("POST".equals(method) && bare.equals(clientsPath)) {
            Map<String, Object> client = JSON.readValue(body, new TypeReference<LinkedHashMap<String, Object>>() {});
            String uuid = "uuid-" + (clients.size() + 1);
            client.put("id", uuid);
            clients.put(uuid, client);
            return new MockResponse().setResponseCode(201).setHeader("Location", baseUrl() + clientsPath + "/" + uuid);
        }
        if (bare.startsWith(clientsPath + "/")) {
            String rest = bare.substring(clientsPath.length() + 1);
            String uuid = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
            if (!clients.containsKey(uuid)) {
                return json(404, "{}");
            }
            if ("PUT".equals(method) && rest.equals(uuid)) {
                Map<String, Object> client = JSON.readValue(body, new TypeReference<LinkedHashMap<String, Object>>() {});
                client.put("id", uuid);
                clients.put(uuid, client);
                return new MockResponse().setResponseCode(204);
            }
            if ("GET".equals(method) && rest.equals(uuid + "/client-secret")) {
                return json(200, "{\"type\":\"secret\",\"value\":\"" + CLIENT_SECRET + "\"}");
            }
            if ("GET".equals(method) && rest.equals(uuid + "/protocol-mappers/models")) {
                return json(200, JSON.writeValueAsString(clients.get(uuid).getOrDefault("protocolMappers", List.of())));
            }
        }
        return json(404, "{}");
    }

    private static Map<String, String> form(String encoded) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                fields.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return fields;
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
    }
}
