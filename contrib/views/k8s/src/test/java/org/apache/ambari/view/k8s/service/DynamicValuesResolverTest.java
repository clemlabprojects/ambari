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

import org.apache.ambari.view.cluster.Cluster;
import org.apache.ambari.view.k8s.store.KdpsContextEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.apache.ambari.view.k8s.utils.FakeAmbariServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The dynamic values of a service definition become Helm overrides read from the managed cluster's Ambari, or from
 * the platform context the deploy selected. A token that resolves to nothing is left out: an OIDC issuer built from
 * a blank Keycloak address would be {@code /realms/<realm>}, and every sign-in would loop back to the login page.
 */
class DynamicValuesResolverTest {

    private static final String ISSUER = "https://sso.example.com/realms/odp";
    private static final List<String> OIDC_TOKENS = List.of(
            "AMBARI_KERBEROS_REALM:global.security.kerberos.realm",
            "AMBARI_OIDC_REALM:global.security.auth.oidc.realm",
            "AMBARI_OIDC_ISSUER_URL:global.security.auth.oidc.issuerUrl",
            "AMBARI_OIDC_ISSUER_URL[/protocol/openid-connect/auth]:auth.authorizeUrl");

    @TempDir
    Path workDir;

    private FakeAmbariServer ambari;
    private PerUserViewContext view;

    @BeforeEach
    void setUp() throws Exception {
        ambari = new FakeAmbariServer("c1");
        ambari.config("kerberos-env", Map.of("realm", "EXAMPLE.COM"));
        ambari.config("oidc-env", Map.of("oidc_issuer_url", ISSUER + "/", "oidc_realm", "odp",
                "oidc_admin_url", "https://sso.example.com"));
        view = new PerUserViewContext("dynamic-values", workDir).as("admin");
        when(view.context.getURLStreamProvider()).thenReturn(ambari.streamProvider());
        Cluster cluster = mock(Cluster.class);
        when(cluster.getName()).thenReturn("c1");
        when(view.context.getCluster()).thenReturn(cluster);
    }

    @AfterEach
    void tearDown() throws Exception {
        ambari.close();
    }

    private DynamicValuesResolver resolver() {
        return new DynamicValuesResolver(view.context, new CommandUtils(view.context, mock(KubernetesService.class)));
    }

    private Map<String, String> resolve(Object tokens, Map<String, Object> formValues) {
        return resolver().resolve(tokens, formValues, ambari.baseUri(), Map.of("Cookie", "AMBARISESSIONID=s"),
                "the release chart");
    }

    @Test
    void tokensAreReadFromTheManagedClusterAndASuffixIsAppendedWithoutADoubleSlash() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("global.security.kerberos.realm", "EXAMPLE.COM");
        expected.put("global.security.auth.oidc.realm", "odp");
        expected.put("global.security.auth.oidc.issuerUrl", ISSUER + "/");
        expected.put("auth.authorizeUrl", ISSUER + "/protocol/openid-connect/auth");

        Map<String, String> resolved = resolve(OIDC_TOKENS, null);

        assertEquals(expected, resolved);
        assertEquals(List.copyOf(expected.keySet()), List.copyOf(resolved.keySet()), "in the order of the list");
    }

    @Test
    void anUnsetIssuerIsBuiltFromTheKeycloakAddressAndRealm() {
        ambari.config("oidc-env", Map.of("oidc_issuer_url", "", "oidc_realm", "odp",
                "oidc_admin_url", "https://sso.example.com/"));

        Map<String, String> resolved = resolve(OIDC_TOKENS, null);

        assertEquals(ISSUER, resolved.get("global.security.auth.oidc.issuerUrl"));
        assertEquals(ISSUER + "/protocol/openid-connect/auth", resolved.get("auth.authorizeUrl"));
    }

    @Test
    void anIssuerThatCannotBeBuiltIsLeftOutRatherThanSetToRealmsAlone() {
        for (Map<String, String> oidcEnv : List.of(
                Map.of("oidc_issuer_url", "", "oidc_realm", "odp", "oidc_admin_url", ""),
                Map.of("oidc_issuer_url", "", "oidc_realm", "", "oidc_admin_url", "https://sso.example.com"),
                Map.of("oidc_issuer_url", " ", "oidc_realm", " ", "oidc_admin_url", " "))) {
            ambari.config("oidc-env", oidcEnv);

            Map<String, String> resolved = resolve(OIDC_TOKENS, null);

            assertFalse(resolved.containsKey("global.security.auth.oidc.issuerUrl"), oidcEnv + " -> " + resolved);
            assertFalse(resolved.containsKey("auth.authorizeUrl"), oidcEnv + " -> " + resolved);
            assertTrue(resolved.values().stream().noneMatch(v -> v.contains("/realms/")), resolved::toString);
            assertEquals("EXAMPLE.COM", resolved.get("global.security.kerberos.realm"), "the other tokens still apply");
            AmbariActionClient client = new AmbariActionClient(view.context, ambari.baseUri(), "c1", Map.of());
            assertNull(DynamicValuesResolver.oidcIssuerUrl(client, "c1"), oidcEnv.toString());
        }
    }

    @Test
    void aClusterWithoutOidcGivesNoOidcValue() {
        ambari.withoutConfig("oidc-env");

        assertEquals(Map.of("global.security.kerberos.realm", "EXAMPLE.COM"), resolve(OIDC_TOKENS, null));
    }

    @Test
    void aNonManagedContextGivesItsOwnValues() throws Exception {
        storeExternalContext("corp", "{\"realm\":\"CORP.EXAMPLE\","
                + "\"issuerUrl\":\"https://login.corp.example/realms/corp\"}");

        Map<String, String> resolved = resolve(OIDC_TOKENS, Map.of("platformContextId", "corp"));

        assertEquals("CORP.EXAMPLE", resolved.get("global.security.kerberos.realm"));
        assertEquals("https://login.corp.example/realms/corp", resolved.get("global.security.auth.oidc.issuerUrl"));
        assertEquals("https://login.corp.example/realms/corp/protocol/openid-connect/auth",
                resolved.get("auth.authorizeUrl"));
    }

    @Test
    void theManagedContextNamedExplicitlyReadsTheCluster() {
        assertEquals(resolve(OIDC_TOKENS, null),
                resolve(OIDC_TOKENS, Map.of("platformContextId", ContextService.DEFAULT_CONTEXT_ID)));
    }

    @Test
    void anAmbariWithoutClusterStillAppliesTheValuesOfASelectedContext() throws Exception {
        when(view.context.getCluster()).thenReturn(null);
        storeExternalContext("corp", "{\"realm\":\"CORP.EXAMPLE\"}");

        assertEquals(Map.of("global.security.kerberos.realm", "CORP.EXAMPLE", "global.security.auth.oidc.realm",
                "CORP.EXAMPLE"), resolve(OIDC_TOKENS, Map.of("platformContextId", "corp")));
        assertEquals(Map.of(), resolve(OIDC_TOKENS, null), "nothing to read on the managed side");
    }

    @Test
    void malformedEntriesAreSkippedAndTheOthersResolved() {
        Map<String, String> resolved = resolve(List.of("NO_HELM_PATH", "AMBARI_KERBEROS_REALM:", "UNKNOWN_TOKEN:a.b",
                "  AMBARI_KERBEROS_REALM : backend.krbRealm  ", "", 42), null);

        assertEquals(Map.of("backend.krbRealm", "EXAMPLE.COM"), resolved);
    }

    @Test
    void nothingIsAskedWithoutEntriesOrWithoutAnAmbariAddress() {
        assertEquals(Map.of(), resolve(null, null));
        assertEquals(Map.of(), resolve("AMBARI_KERBEROS_REALM:a", null), "not a list");
        assertEquals(Map.of(), resolver().resolve(OIDC_TOKENS, null, null, Map.of(), "dependency keda"));
        assertEquals(List.of(), ambari.requests);
    }

    @Test
    void anIssuerIsComposedOnlyFromBothParts() {
        assertEquals(ISSUER, DynamicValuesResolver.composeOidcIssuerUrl("https://sso.example.com//", "odp"));
        assertNull(DynamicValuesResolver.composeOidcIssuerUrl("", "odp"));
        assertNull(DynamicValuesResolver.composeOidcIssuerUrl("https://sso.example.com", null));
    }

    private void storeExternalContext(String id, String configJson) {
        KdpsContextEntity context = new KdpsContextEntity();
        context.setId(id);
        context.setName(id);
        context.setKind(ContextService.KIND_EXTERNAL);
        context.setConfigJson(configJson);
        view.dataStore.store(context);
    }
}
