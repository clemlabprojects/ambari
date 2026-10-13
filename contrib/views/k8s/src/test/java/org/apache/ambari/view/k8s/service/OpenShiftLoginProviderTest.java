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

import org.apache.ambari.view.k8s.utils.SelfSignedHttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OpenShift username/password login checks the certificate of the API and of the OAuth route: a blank CA means
 * "the Ambari server's own trust", never "any certificate", and the password is only ever sent over HTTPS.
 */
class OpenShiftLoginProviderTest {

    private SelfSignedHttpsServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    /** A server that behaves like an OpenShift API plus OAuth route: well-known metadata and the challenge redirect. */
    private SelfSignedHttpsServer openShift() throws Exception {
        server = new SelfSignedHttpsServer(true, "localhost");
        server.answers.put("/.well-known/oauth-authorization-server",
                "{\"authorization_endpoint\":\"" + server.ipUrl() + "/oauth/authorize\"}");
        server.redirects.put("/oauth/authorize",
                server.ipUrl() + "/oauth/token/implicit#access_token=sha256~abc&expires_in=3600&token_type=Bearer");
        return server;
    }

    @Test
    void aBlankCaDoesNotAcceptASelfSignedCertificate() throws Exception {
        openShift();
        OpenShiftLoginProvider provider = new OpenShiftLoginProvider(server.ipUrl(), "kdps", "s3cret", "", false);

        assertNull(provider.refresh());
        assertNull(provider.getToken());
        assertNotNull(provider.getLastFailure());
        assertTrue(provider.getLastFailure().contains("Paste the cluster CA bundle"), provider.getLastFailure());
        assertTrue(provider.getLastFailure().contains("Skip TLS verification"), provider.getLastFailure());
        assertTrue(server.requests.isEmpty(), "nothing, the password included, reached the server: " + server.requests);
    }

    @Test
    void aNullCaIsTreatedLikeABlankOne() throws Exception {
        openShift();
        OpenShiftLoginProvider provider = new OpenShiftLoginProvider(server.ipUrl(), "kdps", "s3cret", null, false);

        assertNull(provider.refresh());
        assertTrue(server.requests.isEmpty(), "nothing reached the server: " + server.requests);
    }

    @Test
    void aPastedCaIsAccepted() throws Exception {
        openShift();
        OpenShiftLoginProvider provider =
                new OpenShiftLoginProvider(server.ipUrl(), "kdps", "s3cret", server.certificatePem(), false);

        assertEquals("sha256~abc", provider.refresh());
        assertNull(provider.getLastFailure());
        assertEquals(2, server.requests.size(), server.requests.toString());
        assertTrue(server.requests.get(1).startsWith("GET /oauth/authorize Basic "), server.requests.toString());
    }

    @Test
    void aPastedCaGivenAsBase64IsAccepted() throws Exception {
        openShift();
        String base64 = java.util.Base64.getEncoder().encodeToString(
                server.certificatePem().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        OpenShiftLoginProvider provider = new OpenShiftLoginProvider(server.ipUrl(), "kdps", "s3cret", base64, false);

        assertEquals("sha256~abc", provider.refresh());
    }

    @Test
    void aPastedCaThatDoesNotMatchTheServerIsRefused() throws Exception {
        openShift();
        try (SelfSignedHttpsServer other = new SelfSignedHttpsServer(true, "localhost")) {
            OpenShiftLoginProvider provider =
                    new OpenShiftLoginProvider(server.ipUrl(), "kdps", "s3cret", other.certificatePem(), false);

            assertNull(provider.refresh());
            assertTrue(provider.getLastFailure().contains("Paste the cluster CA bundle"), provider.getLastFailure());
            assertTrue(server.requests.isEmpty(), "nothing reached the server: " + server.requests);
        }
    }

    @Test
    void skipTlsVerificationIsHonouredOnlyWhenTicked() throws Exception {
        openShift();
        OpenShiftLoginProvider provider = new OpenShiftLoginProvider(server.ipUrl(), "kdps", "s3cret", "", true);

        assertEquals("sha256~abc", provider.refresh());
    }

    @Test
    void anAuthorizationEndpointThatIsNotHttpsIsRefusedBeforeTheCredentialsAreSent() throws Exception {
        server = new SelfSignedHttpsServer(true, "localhost");
        server.answers.put("/.well-known/oauth-authorization-server",
                "{\"authorization_endpoint\":\"http://127.0.0.1:9/oauth/authorize\"}");
        OpenShiftLoginProvider provider =
                new OpenShiftLoginProvider(server.ipUrl(), "kdps", "s3cret", server.certificatePem(), false);

        assertNull(provider.refresh());
        assertTrue(provider.getLastFailure().contains("not HTTPS"), provider.getLastFailure());
        assertFalse(provider.isCredentialsRefused());
        assertEquals(1, server.requests.size(), "only the metadata was read: " + server.requests);
        assertTrue(server.requests.get(0).startsWith("GET /.well-known/oauth-authorization-server null"),
                server.requests.toString());
    }

    @Test
    void anAuthorizationEndpointThatIsNotHttpsIsRefusedEvenWhenTlsVerificationIsSkipped() throws Exception {
        server = new SelfSignedHttpsServer(true, "localhost");
        server.answers.put("/.well-known/oauth-authorization-server",
                "{\"authorization_endpoint\":\"http://127.0.0.1:9/oauth/authorize\"}");
        OpenShiftLoginProvider provider = new OpenShiftLoginProvider(server.ipUrl(), "kdps", "s3cret", "", true);

        assertNull(provider.refresh());
        assertTrue(provider.getLastFailure().contains("not HTTPS"), provider.getLastFailure());
    }
}
