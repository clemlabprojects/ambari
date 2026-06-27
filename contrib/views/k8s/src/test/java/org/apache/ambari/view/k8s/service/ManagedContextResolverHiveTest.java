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

import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Hive branch of {@link ManagedContextResolver} — the logic that lets KDPS
 * deliver the OM Hive connector's host:port, scheme and auth from Ambari hive-site (the
 * "blue" context-resolved fields), instead of the operator typing them. Exercised through the
 * public {@code resolve(key)} dispatcher so the private derivation methods are covered too.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManagedContextResolverHiveTest {

    private static final String CLUSTER = "c1";
    private static final String HS2 = "master02.example.com";

    @Mock AmbariActionClient ambari;

    /** Stub a complete hive-site so each test asserts on the subset it cares about. */
    private void stubHiveSite(String transport, String useSsl, String httpPort,
                              String thriftPort, String auth) throws Exception {
        lenient().when(ambari.getComponentHosts(CLUSTER, "HIVE", "HIVE_SERVER"))
                .thenReturn(List.of(HS2));
        lenient().when(ambari.getDesiredConfigProperty(CLUSTER, "hive-site", "hive.server2.transport.mode")).thenReturn(transport);
        lenient().when(ambari.getDesiredConfigProperty(CLUSTER, "hive-site", "hive.server2.use.SSL")).thenReturn(useSsl);
        lenient().when(ambari.getDesiredConfigProperty(CLUSTER, "hive-site", "hive.server2.thrift.http.port")).thenReturn(httpPort);
        lenient().when(ambari.getDesiredConfigProperty(CLUSTER, "hive-site", "hive.server2.thrift.port")).thenReturn(thriftPort);
        lenient().when(ambari.getDesiredConfigProperty(CLUSTER, "hive-site", "hive.server2.authentication")).thenReturn(auth);
    }

    private ManagedContextResolver resolver() {
        return new ManagedContextResolver(ambari, CLUSTER);
    }

    @Test
    void httpTransportWithTls_yieldsHiveHttpsAndHttpPortAndKerberos() throws Exception {
        stubHiveSite("http", "true", "10001", "10000", "KERBEROS");
        ManagedContextResolver r = resolver();
        assertEquals("hive+https", r.resolve("hive.scheme"));
        assertEquals(HS2 + ":10001", r.resolve("hive.hs2HostPort"));
        assertEquals("kerberos", r.resolve("hive.authMode"));
    }

    @Test
    void httpTransportNoTls_yieldsHiveHttp() throws Exception {
        stubHiveSite("http", "false", "10001", "10000", "NONE");
        assertEquals("hive+http", resolver().resolve("hive.scheme"));
    }

    @Test
    void binaryTransport_yieldsPlainHiveAndThriftPort() throws Exception {
        stubHiveSite("binary", "false", "10001", "10000", "KERBEROS");
        ManagedContextResolver r = resolver();
        assertEquals("hive", r.resolve("hive.scheme"));
        assertEquals(HS2 + ":10000", r.resolve("hive.hs2HostPort"), "binary uses the thrift port, not http");
    }

    @Test
    void blankHttpPort_defaultsTo10001() throws Exception {
        stubHiveSite("http", "true", "  ", "10000", "KERBEROS");
        assertEquals(HS2 + ":10001", resolver().resolve("hive.hs2HostPort"));
    }

    @Test
    void blankThriftPort_defaultsTo10000() throws Exception {
        stubHiveSite("binary", "false", null, null, "NONE");
        assertEquals(HS2 + ":10000", resolver().resolve("hive.hs2HostPort"));
    }

    @Test
    void authModeMapping() throws Exception {
        stubHiveSite("http", "true", "10001", "10000", "LDAP");
        assertEquals("ldap", resolver().resolve("hive.authMode"));
        stubHiveSite("http", "true", "10001", "10000", "NOSASL");
        assertEquals("none", resolver().resolve("hive.authMode"));
    }

    @Test
    void noHiveServerComponent_resolvesNull() throws Exception {
        lenient().when(ambari.getComponentHosts(CLUSTER, "HIVE", "HIVE_SERVER")).thenReturn(List.of());
        ManagedContextResolver r = resolver();
        assertNull(r.resolve("hive.scheme"));
        assertNull(r.resolve("hive.hs2HostPort"));
        assertNull(r.resolve("hive.authMode"));
    }

    @Test
    void unavailableWhenClusterBlank() {
        ManagedContextResolver r = new ManagedContextResolver(ambari, "");
        assertFalse(r.available());
        assertNull(r.resolve("hive.scheme"));
    }

    @Test
    void unknownKey_resolvesNull() throws Exception {
        stubHiveSite("http", "true", "10001", "10000", "KERBEROS");
        assertNull(resolver().resolve("hive.bogusField"));
    }
}
