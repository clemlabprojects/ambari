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

import org.apache.ambari.view.k8s.service.ProxySupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the selective outbound-proxy decision logic (no network). */
class ProxySupportTest {

    @Test
    void parsesUrlWithAndWithoutScheme() {
        assertEquals("proxy.corp", ProxySupport.from("http://proxy.corp:3128", null, null, null, true).host());
        assertEquals(3128, ProxySupport.from("http://proxy.corp:3128", null, null, null, true).port());
        // scheme optional
        assertEquals("proxy.corp", ProxySupport.from("proxy.corp:8080", null, null, null, true).host());
        assertEquals(8080, ProxySupport.from("proxy.corp:8080", null, null, null, true).port());
    }

    @Test
    void disabledNeverProxies() {
        ProxySupport p = ProxySupport.from("http://proxy.corp:3128", null, null, null, false);
        assertFalse(p.isEnabled());
        assertFalse(p.shouldProxy("charts.example.com"));
    }

    @Test
    void proxiesInternetHostsWhenEnabled() {
        ProxySupport p = ProxySupport.from("http://proxy.corp:3128", null, null, null, true);
        assertTrue(p.shouldProxy("charts.helm.sh"));
        assertTrue(p.shouldProxy("github.com"));
    }

    @Test
    void neverProxiesLoopbackOrTheProxyItself() {
        ProxySupport p = ProxySupport.from("http://proxy.corp:3128", null, null, null, true);
        assertFalse(p.shouldProxy("localhost"));
        assertFalse(p.shouldProxy("127.0.0.1"));
        assertFalse(p.shouldProxy("proxy.corp")); // the proxy host itself
    }

    @Test
    void alwaysDirectHostsBypass_soK8sApiStaysDirect() {
        // The k8s API host is passed as always-direct; it must never be proxied.
        ProxySupport p = ProxySupport.from("http://proxy.corp:3128", null, null, null, true,
                "api.crc.testing");
        assertFalse(p.shouldProxy("api.crc.testing"));
        assertTrue(p.shouldProxy("charts.example.com"));
    }

    @Test
    void noProxyCsvSupportsExactDomainAndWildcard() {
        ProxySupport p = ProxySupport.from("http://proxy.corp:3128", null, null,
                "internal.example.com, .corp.local, 10.0.0.5", true);
        assertFalse(p.shouldProxy("internal.example.com"));     // exact
        assertFalse(p.shouldProxy("git.corp.local"));           // domain suffix
        assertFalse(p.shouldProxy("corp.local"));               // bare domain
        assertFalse(p.shouldProxy("10.0.0.5"));                 // ip exact
        assertTrue(p.shouldProxy("example.com"));               // not internal.*
        assertTrue(p.shouldProxy("other.local"));
    }

    @Test
    void matchesPatternForms() {
        assertTrue(ProxySupport.matches("*", "anything.com"));
        assertTrue(ProxySupport.matches(".corp", "a.corp"));
        assertTrue(ProxySupport.matches(".corp", "corp"));
        assertTrue(ProxySupport.matches("*.corp", "a.b.corp"));
        assertTrue(ProxySupport.matches("host.corp", "host.corp"));
        assertFalse(ProxySupport.matches("host.corp", "otherhost.corp"));
        assertFalse(ProxySupport.matches("corp", "corporate.com")); // suffix must be on a dot boundary
    }
}
