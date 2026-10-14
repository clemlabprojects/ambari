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
package org.apache.ambari.view.k8s.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;

import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * View-wide outbound HTTP(S) proxy for reaching internet Git repositories (and their pull-request API) from behind a
 * corporate proxy. Applied per client, never JVM-wide: the Ambari server and every view in it share the JVM default
 * {@link ProxySelector}, {@link Authenticator} and the {@code jdk.http.auth.*} settings, so the view leaves them as
 * they are and hands its proxy only to the clients it builds ({@link #gitConnectionFactory} for JGit,
 * {@link #clients} for java.net.http). Only the hosts that should be proxied go through it; everything else, the
 * Kubernetes API and the platform's Ranger and Atlas included, connects as the JVM would.
 *
 * <p>The proxy host is always kept out of its own no-proxy set; the caller may pass additional always-direct hosts via
 * {@link #from}. Pure decision logic ({@link #shouldProxy}) is unit-tested.
 */
public final class ProxySupport {

    private static final Logger LOG = LoggerFactory.getLogger(ProxySupport.class);

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final List<String> noProxy;

    ProxySupport(boolean enabled, String host, int port, String username, String password, List<String> noProxy) {
        this.enabled = enabled && host != null && !host.isBlank();
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.noProxy = noProxy == null ? List.of() : noProxy;
    }

    /**
     * Build from stored settings. {@code url} is like {@code http://proxy.corp:3128} (scheme optional).
     * {@code noProxyCsv} is a comma/space separated list of hosts/domains ({@code .corp.local}, {@code *},
     * exact host). {@code alwaysDirect} hosts are appended to the no-proxy set (e.g. the k8s API host).
     */
    public static ProxySupport from(String url, String username, String password, String noProxyCsv,
                                     boolean enabled, String... alwaysDirect) {
        String host = null;
        int port = 3128;
        if (url != null && !url.isBlank()) {
            String u = url.trim();
            if (!u.contains("://")) {
                u = "http://" + u;
            }
            try {
                URI parsed = URI.create(u);
                host = parsed.getHost();
                port = parsed.getPort() > 0 ? parsed.getPort() : ("https".equalsIgnoreCase(parsed.getScheme()) ? 443 : 3128);
            } catch (Exception e) {
                LOG.warn("Invalid proxy URL '{}': {}", url, e.toString());
            }
        }
        List<String> np = new ArrayList<>();
        if (noProxyCsv != null) {
            for (String tok : noProxyCsv.split("[,\\s]+")) {
                if (!tok.isBlank()) np.add(tok.trim());
            }
        }
        if (alwaysDirect != null) {
            for (String d : alwaysDirect) {
                if (d != null && !d.isBlank()) np.add(d.trim());
            }
        }
        return new ProxySupport(enabled, host, port, username, password, np);
    }

    public boolean isEnabled() { return enabled; }
    public String host() { return host; }
    public int port() { return port; }

    /** Whether requests to {@code targetHost} should go through the proxy. */
    public boolean shouldProxy(String targetHost) {
        if (!enabled || targetHost == null || targetHost.isBlank()) {
            return false;
        }
        String h = targetHost.toLowerCase(Locale.ROOT);
        // Never proxy the proxy host itself, or loopback.
        if (h.equalsIgnoreCase(host) || h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1")) {
            return false;
        }
        for (String pattern : noProxy) {
            if (matches(pattern, h)) {
                return false;
            }
        }
        return true;
    }

    /** no-proxy pattern match: {@code *} (all), {@code .suffix}/{@code *.suffix} (domain), or exact host. */
    public static boolean matches(String pattern, String host) {
        if (pattern == null) return false;
        String p = pattern.trim().toLowerCase(Locale.ROOT);
        String h = host.toLowerCase(Locale.ROOT);
        if (p.isEmpty()) return false;
        if (p.equals("*")) return true;
        if (p.startsWith("*.")) p = p.substring(1);          // "*.corp" -> ".corp"
        if (p.startsWith(".")) {
            return h.equals(p.substring(1)) || h.endsWith(p); // ".corp" matches host "corp" and "a.corp"
        }
        return h.equals(p) || h.endsWith("." + p);            // "corp" matches "corp" and "a.corp"
    }

    public Proxy toProxy() {
        return enabled && host != null ? new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port)) : Proxy.NO_PROXY;
    }

    /**
     * Opens a connection to {@code url}: through the proxy, with the proxy credentials set on this connection only,
     * when the host should be proxied; as the JVM would otherwise.
     */
    public URLConnection open(URL url) throws IOException {
        if (!shouldProxy(url.getHost())) {
            return url.openConnection();
        }
        URLConnection connection = url.openConnection(toProxy());
        Authenticator credentials = authenticator();
        if (credentials != null && connection instanceof HttpURLConnection http) {
            http.setAuthenticator(credentials);
        }
        return connection;
    }

    /**
     * Connections of JGit's HTTP transport that go through {@link #open}. JGit otherwise picks its proxy from the JVM
     * default selector and its credentials from the JVM default authenticator, which belong to the whole server.
     */
    public HttpConnectionFactory gitConnectionFactory() {
        URLStreamHandler handler = new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL u) throws IOException {
                return open(URI.create(u.toExternalForm()).toURL());
            }

            @Override
            protected URLConnection openConnection(URL u, Proxy ignored) throws IOException {
                return openConnection(u);
            }
        };
        return new JDKHttpConnectionFactory() {
            @Override
            public HttpConnection create(URL url) throws IOException {
                return super.create(new URL(null, url.toExternalForm(), handler));
            }

            @Override
            public HttpConnection create(URL url, Proxy ignored) throws IOException {
                return create(url);
            }
        };
    }

    /** The proxy credentials, for the proxy host only, or null when the proxy has no user name. */
    public Authenticator authenticator() {
        if (!hasCredentials()) {
            return null;
        }
        warnIfHttpsTunnelRefusesBasic();
        return authenticator(() -> this);
    }

    PasswordAuthentication credentialsFor(Authenticator.RequestorType type, String requestingHost) {
        if (!hasCredentials() || type != Authenticator.RequestorType.PROXY
                || requestingHost == null || !requestingHost.equalsIgnoreCase(host)) {
            return null;
        }
        return new PasswordAuthentication(username, (password == null ? "" : password).toCharArray());
    }

    /** Whether the proxy asks for credentials the view has. */
    boolean hasCredentials() {
        return enabled && username != null && !username.isBlank();
    }

    /**
     * java.net.http clients that go through the proxy for the hosts it proxies. The settings are read at each request,
     * so clients kept for the life of the view follow a proxy changed later.
     *
     * <p>The client handed out carries the proxy credentials only while the proxy has a user name. A java.net.http
     * client with an authenticator no longer returns a 401 of the server it calls: it fails the request instead, so an
     * expired or wrong access token would read as a server that cannot be reached. Without proxy credentials the
     * caller therefore gets a client without an authenticator, which hands the 401 back as it came.
     *
     * @param builder a new builder for each client, with the caller's own settings (timeouts, ...)
     * @param current the proxy settings as they are now
     */
    public static Supplier<HttpClient> clients(Supplier<HttpClient.Builder> builder, Supplier<ProxySupport> current) {
        ProxySelector selector = selector(current);
        Supplier<HttpClient> direct = once(() -> builder.get().proxy(selector).build());
        Supplier<HttpClient> withCredentials = once(() ->
                builder.get().proxy(selector).authenticator(authenticator(current)).build());
        return () -> {
            ProxySupport p = current.get();
            if (p != null && p.hasCredentials()) {
                warnIfHttpsTunnelRefusesBasic();
                return withCredentials.get();
            }
            return direct.get();
        };
    }

    /** Builds a client the first time it is asked for, then hands out that one: each holds a selector thread. */
    private static Supplier<HttpClient> once(Supplier<HttpClient> build) {
        AtomicReference<HttpClient> built = new AtomicReference<>();
        return () -> {
            HttpClient client = built.get();
            if (client == null) {
                synchronized (built) {
                    client = built.get();
                    if (client == null) {
                        client = build.get();
                        built.set(client);
                    }
                }
            }
            return client;
        };
    }

    /** Proxies the hosts {@code current} says to proxy, and leaves every other host to the JVM default selector. */
    static ProxySelector selector(Supplier<ProxySupport> current) {
        return new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                ProxySupport p = current.get();
                if (p != null && uri != null && p.shouldProxy(uri.getHost())) {
                    // java.net.http connects to the address as given, so it is resolved here.
                    return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(p.host, p.port)));
                }
                ProxySelector jvm = ProxySelector.getDefault();
                return jvm != null ? jvm.select(uri) : List.of(Proxy.NO_PROXY);
            }
            @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                ProxySelector jvm = ProxySelector.getDefault();
                if (jvm != null) jvm.connectFailed(uri, sa, ioe);
            }
        };
    }

    private static Authenticator authenticator(Supplier<ProxySupport> current) {
        return new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                ProxySupport p = current.get();
                return p == null ? null : p.credentialsFor(getRequestorType(), getRequestingHost());
            }
        };
    }

    private static final AtomicBoolean TUNNEL_WARNED = new AtomicBoolean();

    /**
     * The JDK refuses Basic authentication to a proxy for HTTPS connections unless the JVM is started with
     * {@code jdk.http.auth.tunneling.disabledSchemes} cleared. That setting covers the whole Ambari server, so the view
     * leaves it to the administrator and says so once.
     */
    private static void warnIfHttpsTunnelRefusesBasic() {
        String disabled = System.getProperty("jdk.http.auth.tunneling.disabledSchemes");
        if ((disabled == null || disabled.toLowerCase(Locale.ROOT).contains("basic")) && TUNNEL_WARNED.compareAndSet(false, true)) {
            LOG.warn("The outbound proxy has a user name. HTTPS connections through a proxy that asks for Basic "
                    + "authentication need the Ambari server started with -Djdk.http.auth.tunneling.disabledSchemes= "
                    + "(the JDK refuses Basic for HTTPS tunnels by default); plain HTTP works as is.");
        }
    }

    @Override public String toString() {
        return "ProxySupport{enabled=" + enabled + ", host=" + host + ":" + port
                + ", auth=" + (username != null && !username.isBlank()) + ", noProxy=" + noProxy + "}";
    }
}
