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

import org.apache.ambari.view.k8s.model.ContextRequest;
import org.apache.ambari.view.k8s.model.ResolvedContext;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * The deploy wizard warns before publishing a service without sign-in, unless the platform context signs users in
 * anyway: the deploy registers the service's OIDC client with the context's identity provider even without a security
 * profile. The context says so in {@code oidc.clientRegistration}, with the rule the deploy applies; an OIDC issuer
 * alone (Ambari's own single sign-on, or an oidc-env without a Keycloak administration URL) registers nothing.
 */
class ContextServiceOidcClientRegistrationTest {

    private static final String ISSUER = "https://keycloak.example.com/realms/odp";
    private static final String FLAG = "oidc.clientRegistration";

    @TempDir
    Path workDir;

    private PerUserViewContext view;
    private AmbariActionClient ambari;

    @BeforeEach
    void setUp() throws Exception {
        view = new PerUserViewContext("kdps", workDir);
        ambari = mock(AmbariActionClient.class);
    }

    private Map<String, String> localFields() {
        return new ContextService(view.context).resolve(null, ambari, "c1").getResolvedFields();
    }

    @Test
    void theLocalClusterRegistersClientsWhenItsOidcSettingsNameAKeycloakAdministrationUrl() throws Exception {
        doReturn("https://keycloak.example.com").when(ambari).getDesiredConfigProperty("c1", "oidc-env", "oidc_admin_url");
        doReturn("odp").when(ambari).getDesiredConfigProperty("c1", "oidc-env", "oidc_realm");

        Map<String, String> fields = localFields();

        assertEquals(ISSUER, fields.get("oidc.issuerUrl"));
        assertEquals("true", fields.get(FLAG));
    }

    @Test
    void anIssuerAloneRegistersNothing() throws Exception {
        doReturn(ISSUER).when(ambari).getDesiredConfigProperty("c1", "oidc-env", "oidc_issuer_url");
        doReturn("kdps-admin").when(ambari).getDesiredConfigProperty("c1", "oidc-env", "oidc_admin_client_id");

        Map<String, String> fields = localFields();

        assertEquals(ISSUER, fields.get("oidc.issuerUrl"));
        assertFalse(fields.containsKey(FLAG), "no oidc_admin_url: the deploy registers no client");
    }

    @Test
    void ambariSingleSignOnRegistersNothing() throws Exception {
        doReturn(ISSUER).when(ambari).getDesiredConfigProperty("c1", "sso-configuration", "ambari.sso.provider.originalUrl");

        Map<String, String> fields = localFields();

        assertEquals(ISSUER, fields.get("oidc.issuerUrl"));
        assertFalse(fields.containsKey(FLAG));
    }

    @Test
    void anUnreadableOidcSettingIsNotTakenForRegistration() throws Exception {
        doReturn(ISSUER).when(ambari).getDesiredConfigProperty("c1", "oidc-env", "oidc_issuer_url");
        doThrow(new IOException("Server returned HTTP response code: 503"))
                .when(ambari).getDesiredConfigProperty("c1", "oidc-env", "oidc_admin_url");

        assertFalse(localFields().containsKey(FLAG));
    }

    @Test
    void anotherContextRegistersClientsOnlyWithTheKeycloakAdministratorCredentials() throws Exception {
        view.as("admin");
        ContextService contexts = new ContextService(view.context);
        ContextRequest context = new ContextRequest();
        context.id = "ext";
        context.name = "External cluster";
        context.kind = ContextService.KIND_EXTERNAL;
        context.config = new LinkedHashMap<>(Map.of("issuerUrl", ISSUER, "adminClientId", "kdps-admin"));
        contexts.save(context);

        ResolvedContext withoutSecret = contexts.resolve("ext", ambari, "c1");
        assertEquals(ISSUER, withoutSecret.getResolvedFields().get("oidc.issuerUrl"));
        assertFalse(withoutSecret.getResolvedFields().containsKey(FLAG));

        context.secrets = new LinkedHashMap<>(Map.of("adminClientSecret", "Admin-Secret-5"));
        contexts.save(context);

        assertEquals("true", contexts.resolve("ext", ambari, "c1").getResolvedFields().get(FLAG));
    }
}
