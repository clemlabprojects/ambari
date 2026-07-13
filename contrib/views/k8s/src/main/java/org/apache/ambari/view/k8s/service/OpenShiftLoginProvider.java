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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.OAuthTokenProvider;
import org.apache.ambari.view.k8s.utils.CompositeTrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * Obtains and renews an OpenShift API token from a username/password using the OAuth server's
 * "challenging client" flow — the same mechanism as {@code oc login -u <user> -p <pass>}. This lets the
 * view talk to an OpenShift cluster where operators can only log in through the console (so a static
 * kubeconfig token would expire): the provider re-authenticates whenever the cached token nears expiry.
 *
 * <p>Implements fabric8's {@link OAuthTokenProvider} so it can be plugged straight into a client Config —
 * the client then fetches a fresh token per request. Requires the cluster's internal OAuth to permit the
 * password grant (i.e. {@code oc login -u -p} works); a browser-only external IdP will not.
 */
public class OpenShiftLoginProvider implements OAuthTokenProvider {

    private static final Logger LOG = LoggerFactory.getLogger(OpenShiftLoginProvider.class);
    private static final String CHALLENGING_CLIENT_ID = "openshift-challenging-client";
    private static final long RENEW_BUFFER_MS = 60_000L; // renew ~1 min before expiry

    private final String apiUrl;
    private final String username;
    private final String password;
    private final String caData; // base64 (kubeconfig-style) or PEM; may be blank
    private final boolean insecure;

    private final Object lock = new Object();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile String token;
    private volatile long expiresAtMs;

    public OpenShiftLoginProvider(String apiUrl, String username, String password, String caData, boolean insecure) {
        this.apiUrl = apiUrl != null ? apiUrl.replaceAll("/+$", "") : null;
        this.username = username;
        this.password = password;
        this.caData = caData;
        this.insecure = insecure;
    }

    @Override
    public String getToken() {
        long now = System.currentTimeMillis();
        if (token != null && expiresAtMs - now > RENEW_BUFFER_MS) {
            return token;
        }
        synchronized (lock) {
            now = System.currentTimeMillis();
            if (token != null && expiresAtMs - now > RENEW_BUFFER_MS) {
                return token;
            }
            try {
                mint();
            } catch (Exception e) {
                LOG.error("Failed to obtain an OpenShift token for user '{}' at {}: {}", username, apiUrl, e.toString());
            }
            return token;
        }
    }

    /** Force a fresh authentication (e.g. after a 401). */
    public String refresh() {
        synchronized (lock) {
            try {
                mint();
            } catch (Exception e) {
                LOG.error("Failed to refresh OpenShift token: {}", e.toString());
            }
            return token;
        }
    }

    private void mint() throws Exception {
        HttpClient http = buildHttpClient();
        String authorizeEndpoint = discoverAuthorizeEndpoint(http);

        String url = authorizeEndpoint
                + (authorizeEndpoint.contains("?") ? "&" : "?")
                + "client_id=" + CHALLENGING_CLIENT_ID + "&response_type=token";
        String basic = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Basic " + basic)
                .header("X-CSRF-Token", "1")
                .build();
        // The token comes back in the redirect's Location fragment, so we must NOT follow it.
        HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
        int code = resp.statusCode();
        if (code == 401) {
            throw new IllegalStateException("OpenShift rejected the username/password (401). Check credentials, "
                    + "or the cluster may not allow the password grant (browser-only IdP).");
        }
        Optional<String> location = resp.headers().firstValue("Location");
        if (location.isEmpty()) {
            throw new IllegalStateException("OAuth challenge did not return a Location header (HTTP " + code
                    + "). The cluster's OAuth server may not permit the password grant for this identity provider.");
        }
        String frag = location.get();
        int hash = frag.indexOf('#');
        String params = hash >= 0 ? frag.substring(hash + 1) : frag;
        String accessToken = null;
        long expiresInSec = 86_400L; // OpenShift default access-token max age
        for (String kv : params.split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            String k = kv.substring(0, eq);
            String v = kv.substring(eq + 1);
            if ("access_token".equals(k)) accessToken = v;
            else if ("expires_in".equals(k)) {
                try { expiresInSec = Long.parseLong(v); } catch (NumberFormatException ignored) { /* keep default */ }
            }
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("OAuth redirect did not contain an access_token.");
        }
        this.token = accessToken;
        this.expiresAtMs = System.currentTimeMillis() + (expiresInSec * 1000L);
        LOG.info("Obtained OpenShift token for user '{}' (expires in {}s).", username, expiresInSec);
    }

    /** Resolve the OAuth authorize endpoint from the API server's well-known metadata. */
    private String discoverAuthorizeEndpoint(HttpClient http) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(apiUrl + "/.well-known/oauth-authorization-server"))
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException("Could not read OAuth metadata from " + apiUrl
                    + "/.well-known/oauth-authorization-server (HTTP " + resp.statusCode() + ")");
        }
        JsonNode root = objectMapper.readTree(resp.body());
        String endpoint = root.path("authorization_endpoint").asText(null);
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("OAuth metadata did not contain authorization_endpoint");
        }
        return endpoint;
    }

    private HttpClient buildHttpClient() throws Exception {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1);
        b.sslContext(buildSslContext());
        return b.build();
    }

    private SSLContext buildSslContext() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        X509TrustManager tm;
        if (insecure || caData == null || caData.isBlank()) {
            if (insecure) {
                LOG.warn("OpenShift login configured with insecure TLS (no CA verification).");
            }
            tm = trustAll();
        } else {
            tm = trustManagerFor(caData);
        }
        ctx.init(null, new TrustManager[]{ tm }, null);
        return ctx;
    }

    private X509TrustManager trustManagerFor(String ca) throws Exception {
        byte[] bytes;
        String trimmed = ca.trim();
        if (trimmed.startsWith("-----BEGIN")) {
            bytes = trimmed.getBytes(StandardCharsets.UTF_8);
        } else {
            bytes = Base64.getMimeDecoder().decode(trimmed);
        }
        KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
        ts.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        int i = 0;
        for (Certificate c : cf.generateCertificates(new ByteArrayInputStream(bytes))) {
            ts.setCertificateEntry("os-ca-" + (i++), c);
        }
        TrustManagerFactory custom = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        custom.init(ts);
        X509TrustManager customTm = (X509TrustManager) custom.getTrustManagers()[0];
        // Compose with the JVM default trust store so public CAs still validate. But the Ambari
        // server JVM's default trust store may be locked down / unreadable from the view context
        // (KeyStoreException: "problem accessing trust store"). If so, trust ONLY the provided CA —
        // the operator supplied it precisely so this endpoint is trusted.
        try {
            TrustManagerFactory def = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            def.init((KeyStore) null);
            return new CompositeTrustManager((X509TrustManager) def.getTrustManagers()[0], customTm);
        } catch (Exception e) {
            LOG.warn("Default trust store unavailable ({}); trusting only the provided OpenShift CA.", e.toString());
            return customTm;
        }
    }

    private X509TrustManager trustAll() {
        return new X509TrustManager() {
            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
        };
    }
}
