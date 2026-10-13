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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audiences a registered client's access tokens carry. Keycloak replaces a client's protocol mappers with those an
 * update sends, so a deploy sets them exactly (its own audience for a service checking it, the called Trino's for
 * Superset), and a re-registration, which does not know them, sends back the ones the client has.
 */
class KeycloakAdminClientAudienceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLIENT_UUID = "6a1f0c2e-0000-4000-8000-000000000001";

    private HttpServer server;
    /** The client representations Keycloak received, by method ("POST" create, "PUT" update). */
    private final List<Map.Entry<String, Map<String, Object>>> received = new CopyOnWriteArrayList<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    /** Whether the client already exists in the realm. */
    private volatile boolean exists;
    /** The protocol mappers the existing client has, as Keycloak lists them. */
    private volatile String currentMappers = "[]";

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

    @Test
    void aDeployPutsEachDistinctAudienceInTheTokensBesideTheGroupsClaim() throws Exception {
        exists = true;

        keycloak().registerClient("superset-analytics", "https://bi.example.org/oauth-authorized/generic",
                false, true, false, true, List.of("trino-analytics", " ", "trino-analytics", "other-analytics"));

        List<Map<String, Object>> mappers = mappers(sent("PUT"));
        assertEquals(List.of("groups", "audience trino-analytics", "audience other-analytics"), names(mappers));
        Map<String, Object> audience = mappers.get(1);
        assertEquals("oidc-audience-mapper", audience.get("protocolMapper"));
        assertEquals(Map.of("included.custom.audience", "trino-analytics", "access.token.claim", "true",
                "id.token.claim", "false", "introspection.token.claim", "true"), audience.get("config"));
        assertFalse(requests.stream().anyMatch(r -> r.contains("/protocol-mappers/")),
                "a deploy knows the audiences: nothing to read back");
    }

    @Test
    void anEmptyListRemovesTheAudiences() throws Exception {
        exists = true;
        currentMappers = "[{\"id\":\"m1\",\"name\":\"audience old\",\"protocol\":\"openid-connect\","
                + "\"protocolMapper\":\"oidc-audience-mapper\",\"config\":{\"included.custom.audience\":\"old\"}}]";

        keycloak().registerClient("superset-analytics", null, false, true, false, true, List.of());

        assertEquals(List.of("groups"), names(mappers(sent("PUT"))));
    }

    @Test
    void aReregistrationKeepsTheAudiencesTheClientHas() throws Exception {
        exists = true;
        currentMappers = "[{\"id\":\"m0\",\"name\":\"groups\",\"protocol\":\"openid-connect\","
                + "\"protocolMapper\":\"oidc-group-membership-mapper\",\"config\":{\"claim.name\":\"groups\"}},"
                + "{\"id\":\"m1\",\"name\":\"audience trino-analytics\",\"protocol\":\"openid-connect\","
                + "\"protocolMapper\":\"oidc-audience-mapper\",\"config\":{\"included.custom.audience\":\"trino-analytics\","
                + "\"access.token.claim\":\"true\"}}]";

        keycloak().registerClient("superset-analytics", null, false, true, false, true);

        List<Map<String, Object>> mappers = mappers(sent("PUT"));
        assertEquals(List.of("groups", "audience trino-analytics"), names(mappers));
        assertFalse(mappers.get(1).containsKey("id"), "sent back as a representation, not by id");
        assertEquals("trino-analytics", ((Map<?, ?>) mappers.get(1).get("config")).get("included.custom.audience"));
    }

    @Test
    void aNewClientRegisteredWithoutAudiencesHasNone() throws Exception {
        exists = false;

        keycloak().registerClient("trino-analytics", null, false, true, false, true);

        assertEquals(List.of("groups"), names(mappers(sent("POST"))));
        assertTrue(requests.stream().noneMatch(r -> r.contains("/protocol-mappers/")), requests::toString);
    }

    @Test
    void addingAnAudienceToAnExistingClientPostsOneMapperAndLeavesTheClientAsItIs() throws Exception {
        exists = true;

        assertTrue(keycloak().addAudience("superset-analytics", " trino-analytics "));

        Map<String, Object> mapper = sent("POST mapper");
        assertEquals("audience trino-analytics", mapper.get("name"));
        assertEquals("oidc-audience-mapper", mapper.get("protocolMapper"));
        assertEquals("trino-analytics", ((Map<?, ?>) mapper.get("config")).get("included.custom.audience"));
        assertTrue(requests.stream().noneMatch(r -> r.startsWith("PUT ")), "the client is not updated: " + requests);
    }

    @Test
    void anAudienceTheClientsTokensAlreadyCarryIsNotAddedTwice() throws Exception {
        exists = true;
        currentMappers = "[{\"id\":\"m1\",\"name\":\"audience trino-analytics\",\"protocol\":\"openid-connect\","
                + "\"protocolMapper\":\"oidc-audience-mapper\",\"config\":{\"included.custom.audience\":\"trino-analytics\"}}]";

        assertTrue(keycloak().addAudience("superset-analytics", "trino-analytics"));

        assertTrue(requests.stream().noneMatch(r -> r.startsWith("POST ") && r.contains("/protocol-mappers/")),
                requests::toString);
    }

    @Test
    void aClientTheRealmDoesNotHaveGetsNoAudience() throws Exception {
        exists = false;

        assertFalse(keycloak().addAudience("superset-analytics", "trino-analytics"));

        assertTrue(requests.stream().noneMatch(r -> r.contains("/protocol-mappers/")), requests::toString);
    }

    @Test
    void audienceMappersSkipBlankAndRepeatedAudiences() {
        assertEquals(List.of("audience a", "audience b"),
                names(KeycloakAdminClient.audienceMappers(java.util.Arrays.asList("a", null, " b ", "a", ""))));
        assertTrue(KeycloakAdminClient.audienceMappers(null).isEmpty());
    }

    private KeycloakAdminClient keycloak() {
        return new KeycloakAdminClient("http://127.0.0.1:" + server.getAddress().getPort(), "master", "odp",
                "kdps-admin", "Admin-Secret-1", "", "", true);
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        requests.add(method + " " + ex.getRequestURI());
        byte[] body = ex.getRequestBody().readAllBytes();
        if (path.endsWith("/protocol/openid-connect/token")) {
            reply(ex, 200, "{\"access_token\":\"admin-token\"}");
        } else if ("GET".equals(method) && path.equals("/admin/realms/odp/clients")) {
            reply(ex, 200, exists ? "[{\"id\":\"" + CLIENT_UUID + "\"}]" : "[]");
        } else if ("GET".equals(method) && path.endsWith("/protocol-mappers/models")) {
            reply(ex, 200, currentMappers);
        } else if ("GET".equals(method) && path.endsWith("/client-secret")) {
            reply(ex, 200, "{\"value\":\"Client-Secret-2\"}");
        } else if ("POST".equals(method) && path.equals("/admin/realms/odp/clients")) {
            received.add(Map.entry("POST", JSON.readValue(body, new TypeReference<Map<String, Object>>() {})));
            ex.getResponseHeaders().add("Location", "http://keycloak/admin/realms/odp/clients/" + CLIENT_UUID);
            reply(ex, 201, "");
        } else if ("POST".equals(method) && path.equals("/admin/realms/odp/clients/" + CLIENT_UUID
                + "/protocol-mappers/models")) {
            received.add(Map.entry("POST mapper", JSON.readValue(body, new TypeReference<Map<String, Object>>() {})));
            reply(ex, 201, "");
        } else if ("PUT".equals(method) && path.equals("/admin/realms/odp/clients/" + CLIENT_UUID)) {
            received.add(Map.entry("PUT", JSON.readValue(body, new TypeReference<Map<String, Object>>() {})));
            reply(ex, 204, null);
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mappers(Map<String, Object> clientRep) {
        return (List<Map<String, Object>>) clientRep.get("protocolMappers");
    }

    private static List<String> names(List<Map<String, Object>> mappers) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> m : mappers) names.add(String.valueOf(m.get("name")));
        return names;
    }
}
