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
import org.apache.ambari.view.k8s.store.KdpsContextEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * A platform context offers three realms: the Kerberos realm its services' principals live in, the single sign-on
 * (OIDC) realm their clients are registered in, and the Polaris realm of the Iceberg catalog. They used to be stored
 * under one key, so the last one entered replaced the other two: Trino got the SSO realm as its Kerberos realm and
 * kinit failed, or a Polaris realm typed on a remote cluster replaced its live Kerberos and SSO realms. Each realm is
 * now stored and resolved on its own, and contexts saved the old way are read as safely as they can be.
 */
class ContextServiceRealmsTest {

    private static final String ISSUER = "https://keycloak.example.com/realms/kdps";

    @TempDir
    Path workDir;

    private PerUserViewContext view;
    private AmbariActionClient remoteAmbari;
    private ContextService contexts;

    @BeforeEach
    void setUp() throws Exception {
        view = new PerUserViewContext("kdps", workDir);
        view.as("admin");
        // The remote cluster's Ambari, as a REMOTE context reaches it with its stored credentials.
        remoteAmbari = mock(AmbariActionClient.class);
        doReturn("REMOTE.EXAMPLE").when(remoteAmbari).getDesiredConfigProperty("prod", "kerberos-env", "realm");
        doReturn("odp").when(remoteAmbari).getDesiredConfigProperty("prod", "oidc-env", "oidc_realm");
        doReturn(List.of("polaris.example.com")).when(remoteAmbari).getComponentHosts("prod", "POLARIS", "POLARIS_SERVER");
        doReturn("CATALOG").when(remoteAmbari)
                .getDesiredConfigProperty("prod", "polaris-application-properties", "polaris.realm-context.realms");
        contexts = new ContextService(view.context) {
            @Override
            AmbariActionClient buildRemoteClient(KdpsContextEntity entity) {
                return remoteAmbari;
            }
        };
    }

    @Test
    void anExternalContextResolvesItsThreeRealmsApart() {
        contexts.save(context("ext", ContextService.KIND_EXTERNAL, Map.of(
                "realm", "BANK.LOCAL", "issuerUrl", ISSUER, "oidcRealm", "kdps", "polarisRealm", "POLARIS")));

        ResolvedContext rc = contexts.resolve("ext", null, null);

        Map<String, String> fields = rc.getResolvedFields();
        assertEquals("BANK.LOCAL", fields.get("kerberos.realm"));
        assertEquals("kdps", fields.get("oidc.realm"));
        assertEquals("POLARIS", fields.get("polaris.realm"));
        assertEquals("BANK.LOCAL", rc.getKerberosRealm(), "the realm the charts' principals are built with");
        assertEquals("kdps", rc.getOidcRealm(), "the realm OIDC clients are registered in");
        assertEquals("true", fields.get("kerberos.enabled"));
        assertTrue(rc.getNotices().isEmpty(), "nothing to review: " + rc.getNotices());
    }

    @Test
    void aCdpContextKeepsItsKerberosRealmApartFromItsOtherRealms() {
        // Cloudera Manager is not reachable here: the Kerberos realm is the one typed on the context.
        contexts.save(context("cdp", ContextService.KIND_CDP, Map.of("cmUrl", "https://cm.invalid:7183",
                "realm", "BANK.LOCAL", "issuerUrl", ISSUER, "oidcRealm", "kdps", "polarisRealm", "POLARIS")));

        Map<String, String> fields = contexts.resolve("cdp", null, null).getResolvedFields();

        assertEquals("BANK.LOCAL", fields.get("kerberos.realm"));
        assertEquals("kdps", fields.get("oidc.realm"));
        assertEquals("POLARIS", fields.get("polaris.realm"));
    }

    @Test
    void anSsoOrPolarisRealmAloneDoesNotMakeAContextKerberized() {
        contexts.save(context("ext", ContextService.KIND_EXTERNAL, Map.of(
                "issuerUrl", ISSUER, "oidcRealm", "kdps", "polarisRealm", "POLARIS")));

        ResolvedContext rc = contexts.resolve("ext", null, null);

        assertNull(rc.getResolvedFields().get("kerberos.realm"));
        assertNull(rc.getKerberosRealm());
        assertEquals("false", rc.getResolvedFields().get("kerberos.enabled"),
                "no Kerberos realm, so no external keytab is asked for");
    }

    @Test
    void aPolarisRealmOverrideOnARemoteContextLeavesTheLiveKerberosAndSsoRealms() {
        contexts.save(remote(Map.of("polarisRealm", "POLARIS")));

        Map<String, String> fields = contexts.resolve("remote", null, null).getResolvedFields();

        assertEquals("POLARIS", fields.get("polaris.realm"));
        assertEquals("REMOTE.EXAMPLE", fields.get("kerberos.realm"));
        assertEquals("odp", fields.get("oidc.realm"));
    }

    @Test
    void aRemoteContextIgnoresStoredValuesOfFieldsItDoesNotOffer() {
        // Written by hand or by an older client: a Kerberos realm and an SSO realm are not overrides on REMOTE.
        store("remote", ContextService.KIND_REMOTE, "{\"remoteAmbariUrl\":\"https://ambari.invalid:8443\","
                + "\"oidcRealm\":\"kdps\",\"kdcHost\":\"kdc.invalid\"}");

        Map<String, String> fields = contexts.resolve("remote", null, null).getResolvedFields();

        assertEquals("REMOTE.EXAMPLE", fields.get("kerberos.realm"));
        assertEquals("odp", fields.get("oidc.realm"));
        assertNull(fields.get("kerberos.kdcHost"));
        assertEquals("CATALOG", fields.get("polaris.realm"), "no override: the live Polaris realm");
    }

    @Test
    void aRemoteContextSavedBeforeReadsItsRealmAsThePolarisRealm() {
        // Only the Polaris realm was offered on a REMOTE context, so the one stored realm was that one.
        store("remote", ContextService.KIND_REMOTE,
                "{\"remoteAmbariUrl\":\"https://ambari.invalid:8443\",\"realm\":\"POLARIS\"}");

        ResolvedContext rc = contexts.resolve("remote", null, null);

        assertEquals("POLARIS", rc.getResolvedFields().get("polaris.realm"));
        assertEquals("REMOTE.EXAMPLE", rc.getResolvedFields().get("kerberos.realm"));
        assertEquals("odp", rc.getResolvedFields().get("oidc.realm"));
        assertTrue(rc.getNotices().isEmpty(), "nothing was lost on a REMOTE context: " + rc.getNotices());
    }

    @Test
    void aRealmSentTheOldWayForARemoteContextIsSavedAsItsPolarisRealm() {
        KdpsContextEntity saved = contexts.save(remote(Map.of("realm", "POLARIS")));

        Map<String, Object> config = new com.google.gson.Gson().fromJson(saved.getConfigJson(), Map.class);
        assertEquals("POLARIS", config.get("polarisRealm"));
        assertFalse(config.containsKey("realm"));
        assertEquals("REMOTE.EXAMPLE", contexts.resolve("remote", null, null).getResolvedFields().get("kerberos.realm"));
    }

    @Test
    void anExternalContextSavedBeforeKeepsItsRealmForKerberosAndAsksForAReview() {
        store("ext", ContextService.KIND_EXTERNAL, "{\"realm\":\"kdps\",\"issuerUrl\":\"" + ISSUER + "\"}");

        ResolvedContext rc = contexts.resolve("ext", null, null);

        assertEquals("kdps", rc.getResolvedFields().get("kerberos.realm"));
        assertNull(rc.getResolvedFields().get("oidc.realm"), "the SSO realm was never stored on its own");
        assertNull(rc.getResolvedFields().get("polaris.realm"));
        assertEquals(List.of(ContextService.SHARED_REALM_NOTICE), rc.getNotices());
        assertEquals(List.of(ContextService.SHARED_REALM_NOTICE), rc.toClientView().notices);
    }

    @Test
    void savingTheContextAgainClearsTheReview() {
        store("ext", ContextService.KIND_EXTERNAL, "{\"realm\":\"BANK.LOCAL\",\"issuerUrl\":\"" + ISSUER + "\"}");

        contexts.save(context("ext", ContextService.KIND_EXTERNAL,
                Map.of("realm", "BANK.LOCAL", "issuerUrl", ISSUER, "oidcRealm", "kdps")));

        ResolvedContext rc = contexts.resolve("ext", null, null);
        assertTrue(rc.getNotices().isEmpty(), "reviewed and saved: " + rc.getNotices());
        assertEquals("kdps", rc.getOidcRealm());
    }

    @Test
    void anOldContextWithKerberosOnlyHasNothingToReview() {
        store("ext", ContextService.KIND_EXTERNAL, "{\"realm\":\"BANK.LOCAL\",\"kdcHost\":\"kdc.bank.local\"}");

        ResolvedContext rc = contexts.resolve("ext", null, null);

        assertEquals("BANK.LOCAL", rc.getKerberosRealm());
        assertTrue(rc.getNotices().isEmpty(), "no SSO nor Polaris whose realm could have been lost: " + rc.getNotices());
    }

    @Test
    void anOldCdpContextWithPolarisAsksForAReview() {
        store("cdp", ContextService.KIND_CDP, "{\"cmUrl\":\"https://cm.invalid:7183\",\"realm\":\"BANK.LOCAL\","
                + "\"restUri\":\"https://polaris.bank.local:8181/api/catalog\"}");

        assertEquals(List.of(ContextService.SHARED_REALM_NOTICE), contexts.resolve("cdp", null, null).getNotices());
    }

    // ------------------------------------------------------------------ fixtures

    private static ContextRequest context(String id, String kind, Map<String, Object> config) {
        ContextRequest request = new ContextRequest();
        request.id = id;
        request.name = id;
        request.kind = kind;
        request.config = new LinkedHashMap<>(config);
        return request;
    }

    private static ContextRequest remote(Map<String, Object> overrides) {
        ContextRequest request = context("remote", ContextService.KIND_REMOTE, Map.of(
                "remoteAmbariUrl", "https://ambari.invalid:8443", "remoteUsername", "admin"));
        request.clusterName = "prod";
        request.config.putAll(overrides);
        return request;
    }

    /** A context as an older version stored it, written straight to the store. */
    private void store(String id, String kind, String configJson) {
        KdpsContextEntity entity = new KdpsContextEntity();
        entity.setId(id);
        entity.setName(id);
        entity.setKind(kind);
        entity.setClusterName(ContextService.KIND_REMOTE.equals(kind) ? "prod" : null);
        entity.setConfigJson(configJson);
        view.dataStore.store(entity);
    }
}
