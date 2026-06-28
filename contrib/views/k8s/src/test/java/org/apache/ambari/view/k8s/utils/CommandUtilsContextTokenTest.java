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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CommandUtils#contextValueForToken(String, Map)} — the mapping that lets a
 * non-managed platform context override a dynamic-value token (Kerberos realm / OIDC issuer/realm)
 * during a deploy. Tokens not mapped to a context capability must fall through to the normal Ambari
 * resolution (returning null here), and a missing/empty context map must never override.
 */
public class CommandUtilsContextTokenTest {

    private Map<String, String> resolved() {
        Map<String, String> m = new HashMap<>();
        m.put("kerberos.realm", "EXAMPLE.COM");
        m.put("oidc.issuerUrl", "https://kc.example/realms/odp");
        m.put("oidc.realm", "odp");
        return m;
    }

    @Test
    void mapsKerberosRealmToken() {
        assertEquals("EXAMPLE.COM", CommandUtils.contextValueForToken("AMBARI_KERBEROS_REALM", resolved()));
    }

    @Test
    void mapsOidcIssuerAndRealmTokens() {
        assertEquals("https://kc.example/realms/odp", CommandUtils.contextValueForToken("AMBARI_OIDC_ISSUER_URL", resolved()));
        assertEquals("odp", CommandUtils.contextValueForToken("AMBARI_OIDC_REALM", resolved()));
    }

    @Test
    void unmappedTokenFallsThroughToNull() {
        // AMBARI_OIDC_ADMIN_URL is a valid dynamic token but has no context capability mapping,
        // so it must resolve from Ambari (null here) rather than from the context.
        assertNull(CommandUtils.contextValueForToken("AMBARI_OIDC_ADMIN_URL", resolved()));
        assertNull(CommandUtils.contextValueForToken("AMBARI_OIDC_VERIFY_TLS", resolved()));
    }

    @Test
    void emptyOrNullContextNeverOverrides() {
        assertNull(CommandUtils.contextValueForToken("AMBARI_KERBEROS_REALM", null));
        assertNull(CommandUtils.contextValueForToken("AMBARI_KERBEROS_REALM", Collections.emptyMap()));
    }

    @Test
    void mappedButAbsentFromContextReturnsNull() {
        // Token is mapped, but the context didn't resolve a value for it → no override.
        Map<String, String> partial = new HashMap<>();
        partial.put("kerberos.realm", "EXAMPLE.COM");
        assertNull(CommandUtils.contextValueForToken("AMBARI_OIDC_ISSUER_URL", partial));
    }
}
