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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.ambari.view.k8s.client.KeycloakAdminClient.ClientMarker;
import org.apache.ambari.view.k8s.client.KeycloakAdminClient.DeleteOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mark KDPS writes on the Keycloak client of a release (the release and the KDPS instance), which decides whether
 * the uninstall of the release deletes the client, and whether a deploy may update a client of the same id: several
 * clusters or views may share a realm, and the client id of a release is the same in each.
 */
class KeycloakAdminClientMarkerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLIENT_UUID = "6a1f0c2e-0000-4000-8000-000000000777";
    private static final ClientMarker OURS = ClientMarker.of("analytics", "trino", "instance-a");

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    /** The client representations Keycloak received, by method ("POST" create, "PUT" update). */
    private final List<Map.Entry<String, Map<String, Object>>> received = new CopyOnWriteArrayList<>();
    /** The client as Keycloak has it, or null when the realm has none. */
    private volatile String client;
    /** The status Keycloak answers a DELETE of the client with. */
    private volatile int deleteStatus = 204;

    @BeforeEach
    void startKeycloak() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopKeycloak() {
        server.stop(0);
    }

    /** A client of the realm marked with {@code release} and {@code instance}; neither when both are null. */
    private static String clientMarked(String release, String instance) {
        StringBuilder attributes = new StringBuilder("\"post.logout.redirect.uris\":\"https://trino.example.org\"");
        if (release != null) attributes.append(",\"kdps.release\":\"").append(release).append('"');
        if (instance != null) attributes.append(",\"kdps.instance\":\"").append(instance).append('"');
        return "{\"id\":\"" + CLIENT_UUID + "\",\"clientId\":\"trino-analytics\",\"attributes\":{" + attributes + "}}";
    }

    @Test
    void theUninstallDeletesTheClientMarkedForItsReleaseAndInstance() throws Exception {
        client = clientMarked("analytics/trino", "instance-a");

        assertEquals(DeleteOutcome.DELETED, keycloak().deleteClient("trino-analytics", OURS));

        assertTrue(requests.contains("DELETE /admin/realms/odp/clients/" + CLIENT_UUID), requests::toString);
    }

    @Test
    void aClientMarkedForAnotherReleaseOrInstanceIsKept() throws Exception {
        for (String[] mark : new String[][]{{"analytics-2/trino", "instance-a"}, {"analytics/trino", "instance-b"},
                {"analytics/trino", null}, {null, "instance-a"}}) {
            requests.clear();
            client = clientMarked(mark[0], mark[1]);

            assertEquals(DeleteOutcome.MARKED_FOR_ANOTHER, keycloak().deleteClient("trino-analytics", OURS),
                    String.join(",", String.valueOf(mark[0]), String.valueOf(mark[1])));

            assertTrue(requests.stream().noneMatch(r -> r.startsWith("DELETE ")), requests::toString);
        }
    }

    @Test
    void aClientWithoutAMarkIsKept() throws Exception {
        client = clientMarked(null, null);

        assertEquals(DeleteOutcome.NOT_MARKED, keycloak().deleteClient("trino-analytics", OURS));

        assertTrue(requests.stream().noneMatch(r -> r.startsWith("DELETE ")), requests::toString);
    }

    @Test
    void aClientTheRealmDoesNotHaveIsAlreadyGone() throws Exception {
        client = null;

        assertEquals(DeleteOutcome.NOT_FOUND, keycloak().deleteClient("trino-analytics", OURS));

        assertTrue(requests.stream().noneMatch(r -> r.startsWith("DELETE ")), requests::toString);
    }

    @Test
    void aClientGoneBeforeItsDeleteIsNoFailure() throws Exception {
        client = clientMarked("analytics/trino", "instance-a");
        deleteStatus = 404;

        assertEquals(DeleteOutcome.NOT_FOUND, keycloak().deleteClient("trino-analytics", OURS));
    }

    @Test
    void aDeleteKeycloakRefusesIsReported() {
        client = clientMarked("analytics/trino", "instance-a");
        deleteStatus = 403;

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> keycloak().deleteClient("trino-analytics", OURS));
        assertTrue(refused.getMessage().contains("HTTP 403"), refused.getMessage());
    }

    @Test
    void aDeployRefusesAClientMarkedForAnotherRelease() {
        client = clientMarked("analytics/trino", "instance-b");

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> keycloak().registerClient(
                "trino-analytics", "https://trino.example.org/oauth2/callback", false, true, false, true, null, OURS));

        assertTrue(refused.getMessage().contains("belongs to release analytics/trino of another KDPS instance"
                + " (instance-b)"), refused.getMessage());
        assertTrue(received.isEmpty(), "the client is left as it is: " + requests);
    }

    @Test
    void aNewClientIsMarkedBesideItsPostLogoutRedirects() throws Exception {
        client = null;

        keycloak().registerClient("trino-analytics", "https://trino.example.org/oauth2/callback", false, true, false,
                true, null, OURS);

        Map<?, ?> attributes = (Map<?, ?>) sent("POST").get("attributes");
        assertEquals("analytics/trino", attributes.get("kdps.release"));
        assertEquals("instance-a", attributes.get("kdps.instance"));
        assertEquals("https://trino.example.org##https://trino.example.org/*",
                attributes.get("post.logout.redirect.uris"));
    }

    @Test
    void aClientMarkedForTheReleaseIsUpdatedAndKeepsItsMark() throws Exception {
        client = clientMarked("analytics/trino", "instance-a");

        var result = keycloak().registerClient("trino-analytics", null, false, true, false, true, null, OURS);

        Map<?, ?> attributes = (Map<?, ?>) sent("PUT").get("attributes");
        assertEquals("analytics/trino", attributes.get("kdps.release"));
        assertEquals("instance-a", attributes.get("kdps.instance"));
        assertEquals("odp", result.realm());
    }

    @Test
    void aClientWithoutAMarkIsUpdatedButNotMarked() throws Exception {
        client = clientMarked(null, null);

        keycloak().registerClient("trino-analytics", null, false, true, false, true, null, OURS);

        Map<String, Object> update = sent("PUT");
        assertFalse(update.containsKey("attributes") && ((Map<?, ?>) update.get("attributes")).containsKey(
                "kdps.release"), "nothing tells whose it is: " + update);
    }

    private KeycloakAdminClient keycloak() {
        return new KeycloakAdminClient("http://127.0.0.1:" + server.getAddress().getPort(), "master", "odp",
                "kdps-admin", "Admin-Secret-1", "", "", true);
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        requests.add(method + " " + path);
        byte[] body = ex.getRequestBody().readAllBytes();
        String one = "/admin/realms/odp/clients/" + CLIENT_UUID;
        if (path.endsWith("/protocol/openid-connect/token")) {
            reply(ex, 200, "{\"access_token\":\"admin-token\"}");
        } else if ("GET".equals(method) && path.equals("/admin/realms/odp/clients")) {
            reply(ex, 200, client == null ? "[]" : "[" + client + "]");
        } else if ("GET".equals(method) && path.equals(one)) {
            reply(ex, client == null ? 404 : 200, client == null ? "{}" : client);
        } else if ("GET".equals(method) && path.endsWith("/protocol-mappers/models")) {
            reply(ex, 200, "[]");
        } else if ("GET".equals(method) && path.endsWith("/client-secret")) {
            reply(ex, 200, "{\"value\":\"Client-Secret-2\"}");
        } else if ("POST".equals(method) && path.equals("/admin/realms/odp/clients")) {
            received.add(Map.entry("POST", JSON.readValue(body, new TypeReference<Map<String, Object>>() {})));
            ex.getResponseHeaders().add("Location", "http://keycloak" + one);
            reply(ex, 201, "");
        } else if ("PUT".equals(method) && path.equals(one)) {
            received.add(Map.entry("PUT", JSON.readValue(body, new TypeReference<Map<String, Object>>() {})));
            reply(ex, 204, null);
        } else if ("DELETE".equals(method) && path.equals(one)) {
            reply(ex, deleteStatus, deleteStatus == 204 ? null : "{}");
        } else {
            reply(ex, 404, "{}");
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }

    private Map<String, Object> sent(String method) {
        return received.stream().filter(e -> e.getKey().equals(method)).map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new AssertionError("no " + method + " of the client: " + requests));
    }
}
