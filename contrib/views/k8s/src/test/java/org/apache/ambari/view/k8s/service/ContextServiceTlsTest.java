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
package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.k8s.client.KeycloakAdminClient;
import org.apache.ambari.view.k8s.model.ContextRequest;
import org.apache.ambari.view.k8s.model.ResolvedContext;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.apache.ambari.view.k8s.utils.CmActionClient;
import org.apache.ambari.view.k8s.utils.SelfSignedHttpsServer;
import org.apache.ambari.view.k8s.utils.TlsPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The context preflight and the Cloudera Manager client follow the TLS choice of the platform context: verified by
 * default, trusting the CA stored on the context, and accepting any certificate only when the context says so, for
 * their own connections only. Once such a call has run, the other java.net.http clients of the server (Keycloak,
 * Vault, Polaris) still check certificate and host name. A Keycloak or OpenShift login whose verification the operator
 * turned off skips the checks, the host name included, for its own client only.
 */
class ContextServiceTlsTest {

    @TempDir
    Path workDir;

    private SelfSignedHttpsServer server;

    @BeforeEach
    void start() throws Exception {
        // Issued for another host than the one called, like a self-signed certificate reached by IP.
        server = new SelfSignedHttpsServer(false, "ranger.other.example");
        server.answers.put("/service/public/v2/api/service", "[{\"name\":\"cl_hive\"}]");
        server.answers.put("/api/version", "\"v54\"");
        server.answers.put("/api/v54/cm/version", "{\"version\":\"7.11.3\"}");
        server.answers.put("/api/v54/clusters", "{\"items\":[{\"name\":\"cdp1\"}]}");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static ResolvedContext context(boolean verifySsl, String caPem) {
        ResolvedContext rc = new ResolvedContext();
        rc.setId("prod");
        rc.setName("prod");
        rc.setKind(ContextService.KIND_EXTERNAL);
        rc.setVerifySsl(verifySsl);
        if (caPem != null) {
            rc.getResolvedFields().put("ranger.caCert", caPem);
        }
        return rc;
    }

    private String rangerServices(ResolvedContext rc) throws Exception {
        return ContextService.httpGet(server.localhostUrl() + "/service/public/v2/api/service", "admin", "pw",
                ContextService.tlsPolicyOf(null, rc, "Ranger"));
    }

    /** A java.net.http client as KeycloakAdminClient or the Vault write builds one: verifying, trusting the CA. */
    private int verifyingClientStatus(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .sslContext(TlsPolicy.verifying(List.of(server.certificate()), "Keycloak").sslContext(null)).build();
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void thePreflightRefusesAnUntrustedRangerByDefault() {
        assertThrows(SSLException.class, () -> rangerServices(context(true, null)));
        assertThrows(SSLException.class, () -> rangerServices(null));
        assertTrue(server.requests.isEmpty(), "the admin password reached the server: " + server.requests);
    }

    @Test
    void thePreflightChecksTheHostNameEvenWithTheContextCa() throws Exception {
        assertThrows(SSLException.class, () -> rangerServices(context(true, server.certificatePem())));
    }

    @Test
    void thePreflightTrustsTheContextCaForTheRightHost() throws Exception {
        try (SelfSignedHttpsServer ranger = new SelfSignedHttpsServer(true, "localhost")) {
            ranger.answers.put("/service/public/v2/api/service", "[]");
            assertEquals("[]", ContextService.httpGet(ranger.ipUrl() + "/service/public/v2/api/service", "admin", "pw",
                    ContextService.tlsPolicyOf(null, context(true, ranger.certificatePem()), "Ranger")));
        }
    }

    @Test
    void aPreflightWithVerificationOffLeavesTheOtherClientsVerifying() throws Exception {
        assertTrue(rangerServices(context(false, null)).contains("cl_hive"));

        assertNull(System.getProperty("jdk.internal.httpclient." + "disableHostnameVerification"));
        assertThrows(SSLException.class, () -> verifyingClientStatus(server.localhostUrl() + "/api/version"));
    }

    @Test
    void clouderaManagerIsVerifiedUnlessTheContextTurnsItOff() throws Exception {
        Map<String, Object> refused = new CmActionClient(server.localhostUrl(), "admin", "pw", true).probe();
        assertEquals(false, refused.get("ok"));
        assertTrue(server.requests.isEmpty(), "the CM password reached the server: " + server.requests);

        Map<String, Object> wrongName = new CmActionClient(server.localhostUrl(), "admin", "pw",
                TlsPolicy.verifying(List.of(server.certificate()), "Cloudera Manager")).probe();
        assertEquals(false, wrongName.get("ok"));

        Map<String, Object> accepted = new CmActionClient(server.localhostUrl(), "admin", "pw", false).probe();
        assertEquals(true, accepted.get("ok"), String.valueOf(accepted.get("error")));
        assertEquals(List.of("cdp1"), accepted.get("clusters"));

        // Only that client skipped the checks.
        assertNull(System.getProperty("jdk.internal.httpclient." + "disableHostnameVerification"));
        assertThrows(SSLException.class, () -> verifyingClientStatus(server.localhostUrl() + "/api/version"));
    }

    @Test
    void aKeycloakWithVerificationOffIsReachedDespiteAnotherNameAndOnlyThatClientSkipsTheCheck() throws Exception {
        server.answers.put("/realms/master/protocol/openid-connect/token", "{\"access_token\":\"t\"}");
        server.answers.put("/admin/realms/data/clients", "[]");

        KeycloakAdminClient verifying = new KeycloakAdminClient(server.localhostUrl(), "master", "data", "admin-cli",
                "", "admin", "pw", true);
        assertThrows(SSLException.class, () -> verifying.addAudience("superset", "trino"));
        assertTrue(server.requests.isEmpty(), "the Keycloak password reached the server: " + server.requests);

        KeycloakAdminClient skipping = new KeycloakAdminClient(server.localhostUrl(), "master", "data", "admin-cli",
                "", "admin", "pw", false);
        assertFalse(skipping.addAudience("superset", "trino"), "the realm has no such client");
        assertTrue(server.requests.contains("GET /admin/realms/data/clients Bearer t"), server.requests.toString());

        assertNull(System.getProperty("jdk.internal.httpclient." + "disableHostnameVerification"));
        assertThrows(SSLException.class, () -> verifyingClientStatus(server.localhostUrl() + "/api/version"));
    }

    @Test
    void anInsecureOpenShiftLoginIsReachedDespiteAnotherNameAndOnlyThatClientSkipsTheCheck() throws Exception {
        server.answers.put("/.well-known/oauth-authorization-server",
                "{\"authorization_endpoint\":\"" + server.localhostUrl() + "/oauth/authorize\"}");
        server.answers.put("/oauth/authorize", "{}");

        // With a CA given, the certificate and host name are checked: nothing reaches the server.
        assertNull(new OpenShiftLoginProvider(server.localhostUrl(), "admin", "pw", server.certificatePem(), false)
                .getToken());
        assertTrue(server.requests.isEmpty(), "the OpenShift password reached the server: " + server.requests);

        // The OAuth server of the test answers without a token, but the password got there over TLS.
        assertNull(new OpenShiftLoginProvider(server.localhostUrl(), "admin", "pw", null, true).getToken());
        assertTrue(server.requests.stream().anyMatch(r -> r.startsWith("GET /oauth/authorize Basic ")),
                server.requests.toString());

        assertNull(System.getProperty("jdk.internal.httpclient." + "disableHostnameVerification"));
        assertThrows(SSLException.class, () -> verifyingClientStatus(server.localhostUrl() + "/api/version"));
    }

    @Test
    void contextsVerifyCertificatesUnlessTheyStoreFalse() {
        assertTrue(ContextService.verifiesCertificates(null));
        assertTrue(ContextService.verifiesCertificates(new HashMap<>()));
        assertTrue(ContextService.verifiesCertificates(Map.of("verifySsl", true)));
        assertFalse(ContextService.verifiesCertificates(Map.of("verifySsl", false)));
        assertFalse(ContextService.verifiesCertificates(Map.of("verifySsl", "false")));
        assertTrue(new ResolvedContext().isVerifySsl(), "a resolved context verifies unless told otherwise");
    }

    private ContextService savedContext(String kind, Map<String, Object> config, Map<String, String> secrets) {
        PerUserViewContext view = new PerUserViewContext("kdps", workDir);
        view.as("admin");
        ContextService contexts = new ContextService(view.context);
        ContextRequest request = new ContextRequest();
        request.id = "ctx";
        request.name = "Partner cluster";
        request.kind = kind;
        request.config = new LinkedHashMap<>(config);
        request.secrets = new LinkedHashMap<>(secrets);
        contexts.save(request);
        return contexts;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rangerCheck(Map<String, Object> preflight) {
        return ((List<Map<String, Object>>) preflight.get("checks")).get(0);
    }

    @Test
    void theExternalContextPreflightGivesAClearTlsErrorUnlessVerificationIsOff() {
        Map<String, Object> config = Map.of("rangerUrl", server.localhostUrl(), "rangerAdminUsername", "admin");
        ContextService verifying = savedContext(ContextService.KIND_EXTERNAL, config, Map.of("rangerAdminPassword", "pw"));
        assertTrue(verifying.resolve("ctx", null, null).isVerifySsl(), "an external context verifies by default");

        Map<String, Object> refused = rangerCheck(verifying.preflight("ctx", mock(AmbariActionClient.class), "c1"));
        assertEquals(false, refused.get("ok"));
        assertTrue(String.valueOf(refused.get("message")).contains("not trusted"), String.valueOf(refused.get("message")));
        assertTrue(String.valueOf(refused.get("message")).contains("turn certificate verification off"),
                String.valueOf(refused.get("message")));
        assertTrue(server.requests.isEmpty(), "the admin password reached the server: " + server.requests);

        Map<String, Object> off = new HashMap<>(config);
        off.put("verifySsl", false);
        ContextService insecure = savedContext(ContextService.KIND_EXTERNAL, off, Map.of("rangerAdminPassword", "pw"));
        assertFalse(insecure.resolve("ctx", null, null).isVerifySsl());
        assertEquals(true, rangerCheck(insecure.preflight("ctx", mock(AmbariActionClient.class), "c1")).get("ok"));
    }

    @Test
    void aCdpContextVerifiesClouderaManagerUnlessItStoresFalse() {
        ContextService verifying = savedContext(ContextService.KIND_CDP,
                Map.of("cmUrl", server.localhostUrl(), "cmUsername", "admin"), Map.of("cmPassword", "pw"));
        ResolvedContext refused = verifying.resolve("ctx", null, null);
        assertTrue(refused.isVerifySsl());
        assertNull(refused.getClusterName(), "nothing was discovered from an untrusted Cloudera Manager");
        assertTrue(server.requests.isEmpty(), "the CM password reached the server: " + server.requests);

        ContextService insecure = savedContext(ContextService.KIND_CDP,
                Map.of("cmUrl", server.localhostUrl(), "cmUsername", "admin", "verifySsl", false),
                Map.of("cmPassword", "pw"));
        ResolvedContext discovered = insecure.resolve("ctx", null, null);
        assertFalse(discovered.isVerifySsl());
        assertEquals("cdp1", discovered.getClusterName());
    }
}
