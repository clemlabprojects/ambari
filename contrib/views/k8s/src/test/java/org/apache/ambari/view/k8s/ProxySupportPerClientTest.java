/*
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
package org.apache.ambari.view.k8s;

import com.sun.net.httpserver.HttpServer;
import org.apache.ambari.view.k8s.service.ProxySupport;
import org.apache.ambari.view.k8s.service.deployment.GitClient;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outbound proxy is handed to the clients the view builds, one by one, and never installed JVM-wide: the default
 * proxy selector and authenticator of the Ambari server are the same before and after, and the proxy credentials go
 * to the proxy only.
 */
class ProxySupportPerClientTest {

    private static final String PROXY_AUTH = "Basic " + Base64.getEncoder()
            .encodeToString("proxyuser:proxypw".getBytes(StandardCharsets.UTF_8));

    @TempDir
    Path tmp;

    private HttpServer proxy;
    /** "request-target Proxy-Authorization" of every request the proxy received. */
    private final List<String> proxied = new CopyOnWriteArrayList<>();
    private ProxySelector jvmSelector;
    private Authenticator jvmAuthenticator;

    @BeforeEach
    void startProxy() throws Exception {
        jvmSelector = ProxySelector.getDefault();
        jvmAuthenticator = Authenticator.getDefault();
        // Answers like a proxy that asks for credentials, then like the internet host behind it.
        proxy = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        proxy.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Proxy-Authorization");
            proxied.add(exchange.getRequestURI() + " " + auth);
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            if (!PROXY_AUTH.equals(auth)) {
                exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"corp\"");
                exchange.sendResponseHeaders(407, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        proxy.start();
    }

    @AfterEach
    void stopProxy() {
        proxy.stop(0);
    }

    private ProxySupport settings(String noProxy) {
        return ProxySupport.from("http://127.0.0.1:" + proxy.getAddress().getPort(), "proxyuser", "proxypw",
                noProxy, true);
    }

    private void assertJvmDefaultsUntouched() {
        assertSame(jvmSelector, ProxySelector.getDefault(), "the JVM default proxy selector was replaced");
        assertSame(jvmAuthenticator, Authenticator.getDefault(), "the JVM default authenticator was replaced");
        assertNull(System.getProperty("jdk.http.auth.tunneling.disabledSchemes"));
        assertNull(System.getProperty("jdk.http.auth.proxying.disabledSchemes"));
    }

    @Test
    void aConnectionToAProxiedHostGoesThroughTheProxyWithItsCredentials() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) settings(null)
                .open(URI.create("http://charts.example.test/index.yaml").toURL());

        assertEquals(200, conn.getResponseCode());
        assertTrue(proxied.contains("http://charts.example.test/index.yaml " + PROXY_AUTH), proxied.toString());
        assertJvmDefaultsUntouched();
    }

    @Test
    void aNoProxyHostConnectsDirectly() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) settings(".corp.local")
                .open(URI.create("http://git.corp.local:9/repo.git").toURL());

        assertThrows(java.io.IOException.class, conn::getResponseCode);
        assertTrue(proxied.isEmpty(), "went through the proxy: " + proxied);
    }

    @Test
    void theJavaNetHttpClientFollowsTheSettingsOfTheMomentPerRequest() throws Exception {
        AtomicReference<ProxySupport> current = new AtomicReference<>(ProxySupport.from(null, null, null, null, false));
        Supplier<HttpClient> clients = ProxySupport.clients(HttpClient::newBuilder, current::get);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://api.github.example.test/repos")).GET().build();

        // No proxy yet: the request goes nowhere near it.
        assertThrows(java.io.IOException.class, () -> clients.get().send(request, HttpResponse.BodyHandlers.ofString()));
        assertTrue(proxied.isEmpty());

        current.set(settings(null));
        assertEquals(200, clients.get().send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertTrue(proxied.contains("http://api.github.example.test/repos " + PROXY_AUTH), proxied.toString());
        assertJvmDefaultsUntouched();
    }

    /**
     * A java.net.http client with an authenticator fails a request the server answers 401 instead of returning the
     * 401, so an expired Git access token would read as a server that cannot be reached. Without proxy credentials the
     * client has no authenticator, and the server's 401 comes back as it came, whether it names a scheme or not.
     */
    @Test
    void aServerRefusingTheRequestIsAnsweredWith401WhenTheProxyHasNoCredentials() throws Exception {
        HttpServer origin = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        origin.createContext("/basic", exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"git\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        origin.createContext("/bare", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        origin.start();
        try {
            String base = "http://127.0.0.1:" + origin.getAddress().getPort();
            for (ProxySupport withoutCredentials : List.of(ProxySupport.from(null, null, null, null, false),
                    ProxySupport.from("http://127.0.0.1:" + proxy.getAddress().getPort(), "", "", null, true),
                    ProxySupport.from("http://127.0.0.1:" + proxy.getAddress().getPort(), "u", "p", null, false))) {
                HttpClient client = ProxySupport.clients(HttpClient::newBuilder, () -> withoutCredentials).get();

                assertTrue(client.authenticator().isEmpty(), withoutCredentials.toString());
                for (String path : List.of("/basic", "/bare")) {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).GET().build();
                    assertEquals(401, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode(),
                            path + " with " + withoutCredentials);
                }
            }
        } finally {
            origin.stop(0);
        }
    }

    @Test
    void theClientCarriesTheProxyCredentialsOnlyWhileTheProxyHasAUserName() {
        AtomicReference<ProxySupport> current = new AtomicReference<>(ProxySupport.from(null, null, null, null, false));
        Supplier<HttpClient> clients = ProxySupport.clients(HttpClient::newBuilder, current::get);

        HttpClient direct = clients.get();
        assertTrue(direct.authenticator().isEmpty());
        current.set(settings(null));
        HttpClient withCredentials = clients.get();
        assertTrue(withCredentials.authenticator().isPresent());
        current.set(ProxySupport.from(null, null, null, null, false));
        assertSame(direct, clients.get(), "each client is built once and kept");
        current.set(settings(null));
        assertSame(withCredentials, clients.get(), "each client is built once and kept");
        assertJvmDefaultsUntouched();
    }

    @Test
    void jgitConnectionsUseTheProxyWhateverProxyJgitPicked() throws Exception {
        HttpConnection conn = settings(null).gitConnectionFactory()
                .create(URI.create("http://git.example.test/team/repo.git/info/refs").toURL(), Proxy.NO_PROXY);

        assertEquals(200, conn.getResponseCode());
        assertTrue(proxied.contains("http://git.example.test/team/repo.git/info/refs " + PROXY_AUTH), proxied.toString());
        assertJvmDefaultsUntouched();
    }

    @Test
    void aGitCloneWithTheProxyAsksTheProxy() {
        GitClient git = new GitClient(tmp.resolve("ws"), "http://git.example.test/team/repo.git", "main", "t0k")
                .withTimeout(5)
                .withProxy(settings(null));

        // The proxy answers {} rather than a Git advertisement, so the clone fails; what matters is where it went.
        assertThrows(Exception.class, git::sync);
        assertTrue(proxied.stream().anyMatch(r -> r.startsWith("http://git.example.test/team/repo.git/info/refs")
                && r.endsWith(PROXY_AUTH)), proxied.toString());
        assertJvmDefaultsUntouched();
    }

    @Test
    void theCredentialsGoToTheProxyHostOnly() {
        int port = proxy.getAddress().getPort();
        Authenticator credentials = settings(null).authenticator();

        PasswordAuthentication forTheProxy = credentials.requestPasswordAuthenticationInstance("127.0.0.1", null, port,
                "http", "corp", "basic", null, Authenticator.RequestorType.PROXY);
        assertEquals("proxyuser", forTheProxy.getUserName());
        assertNull(credentials.requestPasswordAuthenticationInstance("git.example.test", null, 443,
                "https", "git", "basic", null, Authenticator.RequestorType.SERVER), "not for a server");
        assertNull(credentials.requestPasswordAuthenticationInstance("other-proxy.example.test", null, port,
                "http", "corp", "basic", null, Authenticator.RequestorType.PROXY), "not for another proxy");
        assertNull(ProxySupport.from("http://127.0.0.1:3128", "", "", null, true).authenticator());
        assertNull(ProxySupport.from("http://127.0.0.1:3128", "u", "p", null, false).authenticator());
    }
}
