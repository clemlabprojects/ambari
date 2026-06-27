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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link CommandService#computeHiveSchemeFromTransport(String, boolean)} — the
 * scheme derivation used for an EXTERNAL platform context (no backing Ambari): the operator
 * supplies the raw HiveServer2 transport + TLS and KDPS derives the OM connector scheme, the
 * same rule the MANAGED resolver applies to hive-site.
 */
class CommandServiceHiveSchemeTest {

    @Test
    void httpWithTls_isHiveHttps() {
        assertEquals("hive+https", CommandService.computeHiveSchemeFromTransport("http", true));
    }

    @Test
    void httpWithoutTls_isHiveHttp() {
        assertEquals("hive+http", CommandService.computeHiveSchemeFromTransport("http", false));
    }

    @Test
    void binaryTransport_isPlainHive() {
        assertEquals("hive", CommandService.computeHiveSchemeFromTransport("binary", true));
        assertEquals("hive", CommandService.computeHiveSchemeFromTransport("binary", false));
    }

    @Test
    void transportIsCaseAndWhitespaceInsensitive() {
        assertEquals("hive+https", CommandService.computeHiveSchemeFromTransport("HTTP", true));
        assertEquals("hive+https", CommandService.computeHiveSchemeFromTransport("  http ", true));
    }

    @Test
    void nullOrBlankTransport_fallsBackToPlainHive() {
        assertEquals("hive", CommandService.computeHiveSchemeFromTransport(null, true));
        assertEquals("hive", CommandService.computeHiveSchemeFromTransport("", true));
    }
}
