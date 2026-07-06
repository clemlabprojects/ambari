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

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * View-wide outbound HTTP(S) proxy for reaching internet Helm/Git repositories from behind a corporate
 * proxy. Applied SELECTIVELY: a custom {@link ProxySelector} routes only the hosts that should be
 * proxied through the proxy and returns everything else (crucially the Kubernetes API server, which must
 * stay direct) to the previous selector. This avoids the danger of a blanket JVM proxy that would tunnel
 * the k8s API — see the class-level reasoning in the KDPS proxy design notes.
 *
 * <p>The proxy host is always kept out of its own no-proxy set; the caller passes additional always-direct
 * hosts (e.g. the k8s API host) via {@link #from}. Pure decision logic ({@link #shouldProxy}) is unit-tested.
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
     * Install a selective default {@link ProxySelector} (and a proxy {@link Authenticator} when
     * credentials are set). Safe to call repeatedly; wraps the current selector so non-proxied hosts fall
     * through unchanged. When disabled, this is a no-op (leaves the current selector in place).
     */
    // The original (non-KDPS) selector, captured once so repeated install() calls rebuild from it instead
    // of stacking wrappers, and so disabling the proxy restores the pristine selector.
    private static volatile ProxySelector baseSelector;

    public synchronized void install() {
        if (baseSelector == null) {
            baseSelector = ProxySelector.getDefault();
        }
        if (!enabled) {
            ProxySelector.setDefault(baseSelector);
            LOG.info("Outbound proxy disabled; restored the base JVM proxy selector.");
            return;
        }
        final ProxySelector previous = baseSelector;
        final Proxy proxy = toProxy();
        ProxySelector.setDefault(new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                if (uri != null && shouldProxy(uri.getHost())) {
                    return List.of(proxy);
                }
                return previous != null ? previous.select(uri) : List.of(Proxy.NO_PROXY);
            }
            @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                if (previous != null) previous.connectFailed(uri, sa, ioe);
            }
        });
        if (username != null && !username.isBlank()) {
            final String u = username;
            final char[] pw = (password == null ? "" : password).toCharArray();
            final String proxyHost = host;
            Authenticator.setDefault(new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY && proxyHost.equalsIgnoreCase(getRequestingHost())) {
                        return new PasswordAuthentication(u, pw);
                    }
                    return null;
                }
            });
            // The JDK disables Basic auth for HTTPS CONNECT tunneling by default; allow it so proxied
            // HTTPS git/helm works with a username/password proxy.
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
            System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
        }
        LOG.info("Outbound proxy installed: {}:{} (no-proxy: {})", host, port, noProxy);
    }

    @Override public String toString() {
        return "ProxySupport{enabled=" + enabled + ", host=" + host + ":" + port
                + ", auth=" + (username != null && !username.isBlank()) + ", noProxy=" + noProxy + "}";
    }
}
