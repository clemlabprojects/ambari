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
package org.apache.ambari.view.k8s.service.om;

import org.apache.ambari.view.k8s.utils.SelfSignedHttpsServer;
import org.apache.ambari.view.k8s.utils.TlsPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Ranger and Atlas calls the deploys make carry an administrator's credentials. Against a server whose certificate
 * is not trusted, or is issued for another name, they must fail before anything is sent, unless the platform context
 * turned certificate verification off.
 */
class OmAtlasProvisioningTlsTest {

    private static final String REPO = "trino-analytics";

    private SelfSignedHttpsServer ranger;

    @BeforeEach
    void start() throws Exception {
        ranger = new SelfSignedHttpsServer(true, "localhost");
        answer(ranger);
    }

    @AfterEach
    void stop() {
        ranger.close();
    }

    private static void answer(SelfSignedHttpsServer server) {
        server.answers.put("/service/public/v2/api/service/name/" + REPO, "{\"id\":7,\"name\":\"" + REPO + "\"}");
        server.answers.put("/service/public/v2/api/service", "[]");
        server.answers.put("/api/atlas/admin/version", "{\"Version\":\"2.4.0\"}");
    }

    private long createOrFindService(TlsPolicy tls, String url) throws Exception {
        return OmAtlasProvisioning.createOrFindService(tls, url, "admin", "admin-pw", REPO, "trino", Map.of());
    }

    @Test
    void anUntrustedRangerIsRefusedBeforeTheAdminCredentialsAreSent() {
        Exception refused = assertThrows(SSLException.class, () -> createOrFindService(
                TlsPolicy.verifying(List.of(), "Ranger of platform context prod"), ranger.ipUrl()));

        assertTrue(refused.getMessage().contains("not trusted for Ranger of platform context prod"), refused.getMessage());
        assertTrue(ranger.requests.isEmpty(), "credentials reached the server: " + ranger.requests);
    }

    @Test
    void theCallsOfAStepWithoutAPolicyVerifyAgainstTheJvmTrust() {
        assertThrows(SSLException.class, () -> createOrFindService(null, ranger.ipUrl()));
        assertTrue(ranger.requests.isEmpty());
    }

    @Test
    void aRangerWhoseCaTheContextTrustsIsCalled() throws Exception {
        assertEquals(7, createOrFindService(TlsPolicy.verifying(List.of(ranger.certificate()), "Ranger"), ranger.ipUrl()));
        assertTrue(ranger.requests.get(0).startsWith("GET /service/public/v2/api/service/name/" + REPO + " Basic "),
                ranger.requests.toString());
    }

    @Test
    void aTrustedCertificateForAnotherHostIsRefused() throws Exception {
        try (SelfSignedHttpsServer impostor = new SelfSignedHttpsServer(false, "ranger.other.example")) {
            answer(impostor);
            assertThrows(SSLException.class, () -> createOrFindService(
                    TlsPolicy.verifying(List.of(impostor.certificate()), "Ranger"), impostor.localhostUrl()));
            assertTrue(impostor.requests.isEmpty());
        }
    }

    @Test
    void verificationTurnedOffOnTheContextAcceptsTheCertificate() throws Exception {
        try (SelfSignedHttpsServer selfSigned = new SelfSignedHttpsServer(false, "ranger.other.example")) {
            answer(selfSigned);
            assertEquals(7, createOrFindService(TlsPolicy.trustingAnyCertificate("Ranger of platform context lab"),
                    selfSigned.localhostUrl()));
        }
    }

    @Test
    void theAtlasProbeOfAnUntrustedServerIsInconclusiveAndSendsNothing() throws Exception {
        assertEquals(OmAtlasProvisioning.AtlasAuthProbe.UNKNOWN, OmAtlasProvisioning.probeFederationUser(
                TlsPolicy.JVM_DEFAULT, ranger.ipUrl(), "om-federation", "secret"));
        assertTrue(ranger.requests.isEmpty());

        assertEquals(OmAtlasProvisioning.AtlasAuthProbe.LOADED, OmAtlasProvisioning.probeFederationUser(
                TlsPolicy.verifying(List.of(ranger.certificate()), "Atlas"), ranger.ipUrl(), "om-federation", "secret"));
    }
}
