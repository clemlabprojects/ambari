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

package org.apache.ambari.view.k8s.utils;

import org.apache.ambari.view.ViewContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Resolves the Ambari REST API base URL for in-process loopback calls from inside the K8S view.
 *
 * <p>When Ambari sits behind a public reverse proxy (e.g. {@code ambari.example.com}), the inbound
 * request's {@code UriInfo.getBaseUri()} reflects the public hostname. Using it to build a loopback
 * URL routes the call through that proxy, whose TLS certificate is typically not in Ambari's
 * configured truststore ({@code ssl.trustStore.path} in {@code ambari.properties}) — the call then
 * fails with {@code PKIX path building failed}. This resolver builds the loopback URL from the
 * local Ambari listener's identity instead, so the cert presented matches the cluster CA that
 * <em>is</em> in the truststore.
 *
 * <p>Resolution order for the host portion of the URL:
 * <ol>
 *   <li>The {@value #LOOPBACK_HOST_PROPERTY} property in {@code ambari.properties} (escape hatch
 *       for environments where {@code getCanonicalHostName()} returns the wrong value, e.g.
 *       containers with broken {@code /etc/hosts}, missing PTR records, short hostnames).</li>
 *   <li>{@link InetAddress#getLocalHost()} with {@link InetAddress#getCanonicalHostName()}.</li>
 *   <li>{@code localhost} (with a WARN log; TLS hostname verification will likely fail and the
 *       admin should set the escape-hatch property).</li>
 * </ol>
 *
 * <p>Scheme and port are derived from {@code api.ssl} and either {@code client.api.ssl.port} or
 * {@code client.api.port} in {@code ambari.properties}, with defaults {@code 8442}/{@code 8080}.
 */
public final class AmbariLoopbackUrlResolver {

    private static final Logger LOG = LoggerFactory.getLogger(AmbariLoopbackUrlResolver.class);

    /** Per-view override property to bypass hostname auto-detection. */
    public static final String LOOPBACK_HOST_PROPERTY = "k8s.view.ambari.loopback.host";

    /**
     * Test seam — replaces the canonical-hostname lookup. Default implementation uses
     * {@link InetAddress#getLocalHost()}. Tests can substitute their own to exercise the failure
     * path without resorting to static-method mocking. Restore the default in {@code @AfterEach}.
     */
    static Callable<String> CANONICAL_HOST_SUPPLIER = AmbariLoopbackUrlResolver::defaultCanonicalHost;

    private AmbariLoopbackUrlResolver() {}

    /**
     * Resolve the Ambari REST API base URL (up to and including {@code /api/v1}).
     *
     * @param ctx the K8S view context — must not be {@code null}
     * @return URL like {@code https://master01.example.com:8442/api/v1}
     */
    public static String resolveApiBase(ViewContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("ViewContext must not be null");
        }
        boolean sslEnabled = Boolean.parseBoolean(
                Optional.ofNullable(ctx.getAmbariProperty("api.ssl")).orElse("false"));
        String scheme = sslEnabled ? "https" : "http";
        String portProp = sslEnabled ? "client.api.ssl.port" : "client.api.port";
        String port = ctx.getAmbariProperty(portProp);
        if (port == null || port.isBlank()) {
            port = sslEnabled ? "8442" : "8080";
        }
        return scheme + "://" + resolveHost(ctx) + ":" + port + "/api/v1";
    }

    /** Convenience overload returning a {@link URI}. */
    public static URI resolveApiBaseUri(ViewContext ctx) {
        return URI.create(resolveApiBase(ctx));
    }

    private static String resolveHost(ViewContext ctx) {
        String override = ctx.getAmbariProperty(LOOPBACK_HOST_PROPERTY);
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        try {
            return CANONICAL_HOST_SUPPLIER.call();
        } catch (Exception e) {
            LOG.warn("Unable to resolve canonical local hostname ({}); falling back to 'localhost' "
                    + "which may fail TLS hostname verification. Set '{}' in ambari.properties to override.",
                    e.toString(), LOOPBACK_HOST_PROPERTY);
            return "localhost";
        }
    }

    private static String defaultCanonicalHost() throws UnknownHostException {
        return InetAddress.getLocalHost().getCanonicalHostName();
    }
}
