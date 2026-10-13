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
package org.apache.ambari.view.k8s.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The certificate checks of the calls the view makes with credentials: a self-signed certificate is refused unless
 * the platform context turned verification off, a trusted certificate issued for another name is refused too, and
 * turning verification off for one client changes nothing for the others.
 */
class TlsPolicyTest {

    private SelfSignedHttpsServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private static int status(TlsPolicy tls, String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        tls.apply(conn);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        try {
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    private static int statusOverHttpClient(TlsPolicy tls, String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .sslContext(tls.sslContext(URI.create(url).getHost()))
                .connectTimeout(Duration.ofSeconds(5)).build();
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void aSelfSignedCertificateIsRefusedByDefault() throws Exception {
        server = new SelfSignedHttpsServer(true, "localhost");
        server.answers.put("/ping", "{}");

        SSLException refused = assertThrows(SSLException.class,
                () -> status(TlsPolicy.verifying(List.of(), "Ranger of platform context prod"), server.ipUrl() + "/ping"));
        assertTrue(refused.getMessage().contains("not trusted for Ranger of platform context prod"), refused.getMessage());
        assertTrue(refused.getMessage().contains("turn certificate verification off"), refused.getMessage());
        assertThrows(SSLException.class, () -> status(TlsPolicy.JVM_DEFAULT, server.ipUrl() + "/ping"));
        assertThrows(SSLException.class, () -> statusOverHttpClient(TlsPolicy.JVM_DEFAULT, server.ipUrl() + "/ping"));
        assertTrue(server.requests.isEmpty(), "nothing reached the server: " + server.requests);
    }

    @Test
    void aCertificateTheContextTrustsIsAccepted() throws Exception {
        server = new SelfSignedHttpsServer(true, "localhost");
        server.answers.put("/ping", "{}");
        TlsPolicy tls = TlsPolicy.verifying(List.of(server.certificate()), "Ranger");

        assertEquals(200, status(tls, server.ipUrl() + "/ping"));
        assertEquals(200, status(tls, server.localhostUrl() + "/ping"));
        assertEquals(200, statusOverHttpClient(tls, server.localhostUrl() + "/ping"));
    }

    @Test
    void aTrustedCertificateIssuedForAnotherNameIsRefused() throws Exception {
        server = new SelfSignedHttpsServer(false, "ranger.other.example");
        server.answers.put("/ping", "{}");
        TlsPolicy tls = TlsPolicy.verifying(List.of(server.certificate()), "Ranger");

        assertThrows(SSLException.class, () -> status(tls, server.ipUrl() + "/ping"));
        assertThrows(SSLException.class, () -> status(tls, server.localhostUrl() + "/ping"));
        assertThrows(SSLException.class, () -> statusOverHttpClient(tls, server.localhostUrl() + "/ping"));
        assertTrue(server.requests.isEmpty(), "nothing reached the server: " + server.requests);
    }

    @Test
    void theHostNameIsCheckedEvenWhenTheJvmWideVerifierAcceptsEverything() throws Exception {
        server = new SelfSignedHttpsServer(false, "ranger.other.example");
        server.answers.put("/ping", "{}");
        HostnameVerifier jvmWide = HttpsURLConnection.getDefaultHostnameVerifier();
        HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> true);
        try {
            TlsPolicy tls = TlsPolicy.verifying(List.of(server.certificate()), "Ranger");
            assertThrows(SSLException.class, () -> status(tls, server.localhostUrl() + "/ping"));
        } finally {
            HttpsURLConnection.setDefaultHostnameVerifier(jvmWide);
        }
    }

    @Test
    void verificationTurnedOffAcceptsAnyCertificateForThatConnectionOnly() throws Exception {
        server = new SelfSignedHttpsServer(false, "ranger.other.example");
        server.answers.put("/ping", "{}");
        SSLSocketFactory jvmFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        HostnameVerifier jvmVerifier = HttpsURLConnection.getDefaultHostnameVerifier();

        TlsPolicy insecure = TlsPolicy.trustingAnyCertificate("Ranger of platform context lab");
        assertEquals(200, status(insecure, server.localhostUrl() + "/ping"));
        assertEquals(200, statusOverHttpClient(insecure, server.localhostUrl() + "/ping"));

        // Nothing JVM-wide changed: the next verifying clients, of either kind, still check certificate and name.
        assertSame(jvmFactory, HttpsURLConnection.getDefaultSSLSocketFactory());
        assertSame(jvmVerifier, HttpsURLConnection.getDefaultHostnameVerifier());
        assertNull(System.getProperty("jdk.internal.httpclient." + "disableHostnameVerification"));
        TlsPolicy trustsTheCertificateOnly = TlsPolicy.verifying(List.of(server.certificate()), "Keycloak");
        assertThrows(SSLException.class, () -> statusOverHttpClient(trustsTheCertificateOnly, server.localhostUrl() + "/ping"));
        assertThrows(SSLException.class, () -> status(trustsTheCertificateOnly, server.localhostUrl() + "/ping"));
        assertThrows(SSLException.class, () -> statusOverHttpClient(TlsPolicy.JVM_DEFAULT, server.localhostUrl() + "/ping"));
    }

    @Test
    void aContextCaCertificateIsTrustedAndAnUnreadableOneIgnored() throws Exception {
        server = new SelfSignedHttpsServer(true, "localhost");
        server.answers.put("/ping", "{}");

        assertEquals(200, status(TlsPolicy.forContext(null, true, server.certificatePem(), "Ranger"),
                server.ipUrl() + "/ping"));
        assertThrows(SSLException.class, () -> status(TlsPolicy.forContext(null, true, "not a certificate", "Ranger"),
                server.ipUrl() + "/ping"));
        assertEquals(200, status(TlsPolicy.forContext(null, false, null, "Ranger"), server.ipUrl() + "/ping"));
    }

    @Test
    void plainHttpConnectionsAreLeftAlone() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:9/").toURL().openConnection();
        TlsPolicy.trustingAnyCertificate("Ranger").apply(conn);
        TlsPolicy.JVM_DEFAULT.apply(conn);
        assertTrue(!(conn instanceof HttpsURLConnection));
    }
}
