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

import org.apache.ambari.view.ViewContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.UnknownHostException;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class AmbariLoopbackUrlResolverTest {

    private Callable<String> originalHostSupplier;

    @BeforeEach
    void rememberDefaults() {
        originalHostSupplier = AmbariLoopbackUrlResolver.CANONICAL_HOST_SUPPLIER;
    }

    @AfterEach
    void restoreDefaults() {
        AmbariLoopbackUrlResolver.CANONICAL_HOST_SUPPLIER = originalHostSupplier;
    }

    @Test
    void propertyOverride_takesPrecedence_overCanonicalHostLookup() {
        // Even when the canonical host supplier returns a value, the explicit override wins.
        AmbariLoopbackUrlResolver.CANONICAL_HOST_SUPPLIER = () -> "should-not-be-used.example.com";

        ViewContext ctx = Mockito.mock(ViewContext.class);
        when(ctx.getAmbariProperty(AmbariLoopbackUrlResolver.LOOPBACK_HOST_PROPERTY))
                .thenReturn("ambari-loopback.example.com");
        when(ctx.getAmbariProperty("api.ssl")).thenReturn("true");
        when(ctx.getAmbariProperty("client.api.ssl.port")).thenReturn("8442");

        assertEquals(
                "https://ambari-loopback.example.com:8442/api/v1",
                AmbariLoopbackUrlResolver.resolveApiBase(ctx));
    }

    @Test
    void noOverride_usesCanonicalHostSupplier_andRespectsApiSslPort() {
        AmbariLoopbackUrlResolver.CANONICAL_HOST_SUPPLIER = () -> "master01.dev01.example.com";

        ViewContext ctx = Mockito.mock(ViewContext.class);
        // override property explicitly unset / blank should fall through
        when(ctx.getAmbariProperty(AmbariLoopbackUrlResolver.LOOPBACK_HOST_PROPERTY)).thenReturn("  ");
        when(ctx.getAmbariProperty("api.ssl")).thenReturn("true");
        when(ctx.getAmbariProperty("client.api.ssl.port")).thenReturn("8442");

        assertEquals(
                "https://master01.dev01.example.com:8442/api/v1",
                AmbariLoopbackUrlResolver.resolveApiBase(ctx));
    }

    @Test
    void canonicalHostFailure_fallsBackToLocalhost_withConfiguredPlainHttpPort() {
        AmbariLoopbackUrlResolver.CANONICAL_HOST_SUPPLIER = () -> {
            throw new UnknownHostException("dns broken in test");
        };

        ViewContext ctx = Mockito.mock(ViewContext.class);
        when(ctx.getAmbariProperty(AmbariLoopbackUrlResolver.LOOPBACK_HOST_PROPERTY)).thenReturn(null);
        // api.ssl=false -> http + client.api.port path
        when(ctx.getAmbariProperty("api.ssl")).thenReturn("false");
        when(ctx.getAmbariProperty("client.api.port")).thenReturn("8080");

        assertEquals(
                "http://localhost:8080/api/v1",
                AmbariLoopbackUrlResolver.resolveApiBase(ctx));
    }

    @Test
    void resolveApiBaseUri_returnsParseableUri() {
        AmbariLoopbackUrlResolver.CANONICAL_HOST_SUPPLIER = () -> "host.example.com";

        ViewContext ctx = Mockito.mock(ViewContext.class);
        when(ctx.getAmbariProperty(AmbariLoopbackUrlResolver.LOOPBACK_HOST_PROPERTY)).thenReturn(null);
        when(ctx.getAmbariProperty("api.ssl")).thenReturn("true");
        when(ctx.getAmbariProperty("client.api.ssl.port")).thenReturn("8442");

        java.net.URI uri = AmbariLoopbackUrlResolver.resolveApiBaseUri(ctx);
        assertEquals("https", uri.getScheme());
        assertEquals("host.example.com", uri.getHost());
        assertEquals(8442, uri.getPort());
        assertTrue(uri.getPath().endsWith("/api/v1"));
    }
}
