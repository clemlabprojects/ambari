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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CommandUtils#hardenKrb5ForInCluster(String)} — the krb5.conf
 * transform that disables DNS hostname canonicalization (forward + reverse) before the
 * cluster krb5.conf is staged into a ConfigMap for in-cluster pods. Without it, the k8s
 * pod resolver canonicalizes external HiveServer2 hostnames to *.svc.cluster.local names
 * and SPNEGO fails with "krbtgt/<svc>.SVC.CLUSTER.LOCAL not found in Kerberos database".
 */
class CommandUtilsKrb5Test {

    /** Count of non-overlapping occurrences of {@code needle} in {@code haystack}. */
    private static int count(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    private static boolean hasKeyFalse(String out, String key) {
        return out.lines().anyMatch(l -> {
            String t = l.trim();
            return t.startsWith(key) && t.replaceAll("\\s", "").equalsIgnoreCase(key + "=false");
        });
    }

    @Test
    void libdefaultsWithNeitherKey_insertsBoth() {
        String in = "[libdefaults]\n    default_realm = EXAMPLE.COM\n    dns_lookup_kdc = false\n";
        String out = CommandUtils.hardenKrb5ForInCluster(in);
        assertTrue(hasKeyFalse(out, "dns_canonicalize_hostname"), out);
        assertTrue(hasKeyFalse(out, "rdns"), out);
        assertTrue(out.contains("default_realm = EXAMPLE.COM"), "preserves existing keys");
        assertTrue(out.contains("dns_lookup_kdc = false"), "preserves existing keys");
    }

    @Test
    void rdnsTrue_isForcedToFalse() {
        String in = "[libdefaults]\n    rdns = true\n    default_realm = X\n";
        String out = CommandUtils.hardenKrb5ForInCluster(in);
        assertTrue(hasKeyFalse(out, "rdns"), out);
        assertFalse(out.contains("rdns = true"), "must not leave rdns = true");
        assertEquals(1, count(out, "rdns ="), "rdns not duplicated");
        assertTrue(hasKeyFalse(out, "dns_canonicalize_hostname"), "canonicalize still added");
    }

    @Test
    void dnsCanonicalizeTrue_isForcedToFalse() {
        String in = "[libdefaults]\n    dns_canonicalize_hostname = true\n    rdns = false\n";
        String out = CommandUtils.hardenKrb5ForInCluster(in);
        assertTrue(hasKeyFalse(out, "dns_canonicalize_hostname"), out);
        assertFalse(out.contains("dns_canonicalize_hostname = true"));
        assertEquals(1, count(out, "dns_canonicalize_hostname"), "not duplicated");
        assertEquals(1, count(out, "rdns ="), "rdns not duplicated");
    }

    @Test
    void noLibdefaultsSection_prependsOne() {
        String in = "[realms]\n  EXAMPLE.COM = {\n    kdc = kdc.example.com\n  }\n";
        String out = CommandUtils.hardenKrb5ForInCluster(in);
        assertTrue(out.trim().startsWith("[libdefaults]"), "prepends [libdefaults]: " + out);
        assertTrue(hasKeyFalse(out, "dns_canonicalize_hostname"));
        assertTrue(hasKeyFalse(out, "rdns"));
        assertTrue(out.contains("[realms]") && out.contains("kdc = kdc.example.com"),
                "preserves the original [realms] section");
    }

    @Test
    void keysEmittedBeforeNextSection_notLeakedIntoRealms() {
        String in = "[libdefaults]\n    default_realm = EX.COM\n[realms]\n    EX.COM = {\n        kdc = k1\n    }\n";
        String out = CommandUtils.hardenKrb5ForInCluster(in);
        int libIdx = out.indexOf("[libdefaults]");
        int realmsIdx = out.indexOf("[realms]");
        int canonIdx = out.indexOf("dns_canonicalize_hostname");
        int rdnsIdx = out.indexOf("rdns =");
        assertTrue(libIdx >= 0 && realmsIdx > libIdx);
        assertTrue(canonIdx > libIdx && canonIdx < realmsIdx, "canon key stays in [libdefaults]");
        assertTrue(rdnsIdx > libIdx && rdnsIdx < realmsIdx, "rdns key stays in [libdefaults]");
    }

    @Test
    void nullOrBlankContent_returnsDefaultLibdefaults() {
        for (String in : new String[]{null, "", "   \n  "}) {
            String out = CommandUtils.hardenKrb5ForInCluster(in);
            assertTrue(out.contains("[libdefaults]"), "for input <" + in + ">");
            assertTrue(hasKeyFalse(out, "dns_canonicalize_hostname"));
            assertTrue(hasKeyFalse(out, "rdns"));
        }
    }

    @Test
    void sectionHeaderCaseInsensitive() {
        String in = "[LibDefaults]\n    default_realm = X\n";
        String out = CommandUtils.hardenKrb5ForInCluster(in);
        assertTrue(hasKeyFalse(out, "dns_canonicalize_hostname"), out);
        assertTrue(hasKeyFalse(out, "rdns"), out);
    }

    @Test
    void isIdempotent() {
        String in = "[libdefaults]\n    rdns = true\n    default_realm = X\n[realms]\n    X = { kdc = k }\n";
        String once = CommandUtils.hardenKrb5ForInCluster(in);
        String twice = CommandUtils.hardenKrb5ForInCluster(once);
        assertEquals(once, twice, "applying the transform twice is a no-op");
        assertEquals(1, count(twice, "dns_canonicalize_hostname"));
        assertEquals(1, count(twice, "rdns ="));
    }
}
