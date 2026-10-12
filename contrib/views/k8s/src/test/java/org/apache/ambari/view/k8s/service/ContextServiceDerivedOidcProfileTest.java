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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.k8s.dto.security.LdapConfig;
import org.apache.ambari.view.k8s.dto.security.OidcConfig;
import org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO;
import org.apache.ambari.view.k8s.dto.security.TlsConfig;
import org.apache.ambari.view.k8s.model.ContextRequest;
import org.apache.ambari.view.k8s.model.ResolvedContext;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.store.SecurityProfileEntity;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Every read of the security profiles (the Global Security page, the deploy wizard) and every context save re-derives
 * the context's OIDC profile. That must never touch a profile the operator wrote: only the context's own
 * {@code ctx-oidc-<id>} row is written, and only when it changes. The OIDC profile earlier versions created under the
 * name {@code keycloak} is replaced once, and only when it is still theirs and no release deploys with it.
 */
class ContextServiceDerivedOidcProfileTest {

    private static final String ISSUER = "https://keycloak.example.com/realms/odp";
    private static final String DERIVED = "ctx-oidc-default";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workDir;

    private PerUserViewContext view;
    /** What the view calls: the instance's rows, with every call recorded and any of them made to fail. */
    private DataStore store;

    @BeforeEach
    void setUp() {
        view = new PerUserViewContext("kdps", workDir);
        store = mock(DataStore.class, delegatesTo(view.dataStore));
        when(view.context.getDataStore()).thenReturn(store);
    }

    private ContextService contexts() {
        return new ContextService(view.context);
    }

    /** The local cluster, with OIDC enabled in Ambari. */
    private static ResolvedContext managedWithOidc() {
        ResolvedContext rc = new ResolvedContext();
        rc.setId(ContextService.DEFAULT_CONTEXT_ID);
        rc.setName("Ambari-managed cluster (c1)");
        rc.setKind(ContextService.KIND_MANAGED);
        rc.setOidcIssuerUrl(ISSUER);
        return rc;
    }

    private static SecurityConfigDTO ldap() {
        SecurityConfigDTO cfg = new SecurityConfigDTO();
        cfg.mode = "ldap";
        cfg.ldap = new LdapConfig();
        cfg.ldap.url = "ldaps://ldap.example.com";
        cfg.ldap.bindPassword = "Bind-Pass-9";
        return cfg;
    }

    /** The OIDC profile earlier versions created for the local cluster. */
    private static SecurityConfigDTO legacyKeycloak() {
        SecurityConfigDTO cfg = new SecurityConfigDTO();
        cfg.mode = "oidc";
        cfg.oidc = new OidcConfig();
        cfg.oidc.source = "internal";
        cfg.oidc.issuerUrl = ISSUER;
        cfg.oidc.scopes = "openid email profile";
        cfg.oidc.userClaim = "preferred_username";
        cfg.oidc.groupsClaim = "groups";
        cfg.oidc.principalDomain = "example.com";
        return cfg;
    }

    private void storeProfile(String id, SecurityConfigDTO cfg, boolean isDefault) throws Exception {
        SecurityProfileEntity entity = new SecurityProfileEntity();
        entity.setId(id);
        entity.setViewInstance("kdps");
        entity.setDefault(isDefault);
        entity.setProfileJson(JSON.writeValueAsString(cfg));
        view.dataStore.store(entity);
    }

    private SecurityProfileEntity row(String id) {
        return view.dataStore.find(SecurityProfileEntity.class, id);
    }

    private void releaseUsing(String profile) {
        release("trino", profile);
    }

    private void release(String name, String profile) {
        K8sReleaseEntity release = new K8sReleaseEntity();
        release.setId(K8sReleaseEntity.idOf("analytics", name));
        release.setNamespace("analytics");
        release.setReleaseName(name);
        release.setSecurityProfile(profile);
        view.dataStore.store(release);
    }

    private String profileOf(String release) {
        return view.dataStore.find(K8sReleaseEntity.class, K8sReleaseEntity.idOf("analytics", release))
                .getSecurityProfile();
    }

    private void noLongerUsed(String release) {
        view.dataStore.remove(view.dataStore.find(K8sReleaseEntity.class, K8sReleaseEntity.idOf("analytics", release)));
    }

    /** The profile the local cluster's OIDC derives, as stored. */
    private void storeDerivedDefault(boolean isDefault) throws Exception {
        SecurityConfigDTO derived = legacyKeycloak();
        derived.derivedFromContext = ContextService.DEFAULT_CONTEXT_ID;
        storeProfile(DERIVED, derived, isDefault);
    }

    /** The local cluster, resolved, with no OIDC configured in Ambari. */
    private static ResolvedContext managedWithoutOidc() {
        ResolvedContext rc = managedWithOidc();
        rc.setOidcIssuerUrl(null);
        return rc;
    }

    @Test
    void readingTheProfilesAgainAndAgainWritesOnlyTheDerivedProfileAndOnlyOnce() throws Exception {
        storeProfile("corp-ldap", ldap(), true);
        SecurityConfigDTO operatorKeycloak = ldap();
        operatorKeycloak.ldap.url = "ldaps://sso-directory.example.com";
        storeProfile("keycloak", operatorKeycloak, false);
        releaseUsing("keycloak");
        String ldapBefore = row("corp-ldap").getProfileJson();
        String keycloakBefore = row("keycloak").getProfileJson();

        for (int i = 0; i < 3; i++) {
            contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, managedWithOidc());
        }

        verify(store, times(1)).store(any(SecurityProfileEntity.class));
        verify(store).store(argThat(e -> e instanceof SecurityProfileEntity p && DERIVED.equals(p.getId())));
        verify(store, never()).remove(any(SecurityProfileEntity.class));
        assertEquals(ldapBefore, row("corp-ldap").getProfileJson());
        assertEquals(keycloakBefore, row("keycloak").getProfileJson());
        assertTrue(row("corp-ldap").isDefault(), "the operator's default stays the default");
        assertFalse(row(DERIVED).isDefault());
        SecurityConfigDTO derived = new SecurityProfileService(view.context).loadProfiles().profiles.get(DERIVED);
        assertEquals(ISSUER, derived.oidc.issuerUrl);
        assertEquals(ContextService.DEFAULT_CONTEXT_ID, derived.derivedFromContext);
    }

    @Test
    void theDerivedProfileIsOnlyMadeTheDefaultWhileNoProfileIs() throws Exception {
        storeProfile("corp-ldap", ldap(), false);

        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, managedWithOidc());

        assertTrue(row(DERIVED).isDefault());
        assertFalse(row("corp-ldap").isDefault());
        assertEquals(DERIVED, new SecurityProfileService(view.context).loadProfiles().defaultProfile);
    }

    @Test
    void theDerivedProfileIsRewrittenWhenTheContextChanges() throws Exception {
        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, managedWithOidc());
        ResolvedContext moved = managedWithOidc();
        moved.setOidcIssuerUrl("https://sso.example.com/realms/odp");

        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, moved);

        assertTrue(row(DERIVED).getProfileJson().contains("https://sso.example.com/realms/odp"));
        assertTrue(row(DERIVED).isDefault(), "it keeps the default flag it had");
    }

    @Test
    void aLegacyKeycloakProfileThatAReleaseUsesIsKept() throws Exception {
        storeProfile("keycloak", legacyKeycloak(), true);
        releaseUsing("keycloak");

        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, managedWithOidc());

        assertNotNull(row("keycloak"), "the release deploys with it");
        assertEquals("keycloak", profileOf("trino"), "the release keeps the profile it deploys with");
        assertTrue(row("keycloak").isDefault());
        assertFalse(row(DERIVED).isDefault());

        // Looked at once: a later read leaves it alone even when no release uses it any more.
        view.dataStore.remove(view.dataStore.find(K8sReleaseEntity.class, K8sReleaseEntity.idOf("analytics", "trino")));
        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, managedWithOidc());
        assertNotNull(row("keycloak"));
    }

    @Test
    void anUnusedLegacyKeycloakProfileIsReplacedOnceByTheDerivedOne() throws Exception {
        storeProfile("keycloak", legacyKeycloak(), true);
        storeProfile("corp-ldap", ldap(), false);

        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, managedWithOidc());

        assertNull(row("keycloak"));
        assertTrue(row(DERIVED).isDefault(), "the default moves to the profile that replaces it");
        assertNotNull(row("corp-ldap"));

        // An operator who creates a profile of that name afterwards keeps it.
        storeProfile("keycloak", legacyKeycloak(), false);
        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, managedWithOidc());
        assertNotNull(row("keycloak"));
    }

    @Test
    void releasesLeftWithTheKeycloakProfileEarlierVersionsRemovedMoveOnceToTheDerivedOne() throws Exception {
        // Earlier versions removed the profile but left the releases deployed with it asking for it.
        releaseUsing("keycloak");
        storeProfile("corp-ldap", ldap(), true);
        release("superset", "corp-ldap");
        SecurityProfileService profiles = new SecurityProfileService(view.context);
        assertThrows(IllegalArgumentException.class, () -> profiles.requireProfile(profileOf("trino")),
                "a deploy of the release is refused while it asks for a profile that does not exist");

        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, managedWithOidc());

        assertEquals(DERIVED, profileOf("trino"));
        assertEquals(ISSUER, profiles.requireProfile(profileOf("trino")).oidc.issuerUrl,
                "its next deploy applies the profile that replaced the removed one");
        assertEquals("corp-ldap", profileOf("superset"), "a release deployed with another profile is left alone");
        assertNull(row("keycloak"));

        // Done once: a profile of that name the operator creates afterwards, and its releases, are theirs.
        storeProfile("keycloak", ldap(), false);
        release("hive", "keycloak");
        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, managedWithOidc());
        assertEquals("keycloak", profileOf("hive"));
        assertNotNull(row("keycloak"));
    }

    @Test
    void aKeycloakProfileTheOperatorWroteIsKept() throws Exception {
        SecurityConfigDTO external = legacyKeycloak();
        external.oidc.source = "external";
        external.oidc.clientId = "trino";
        external.oidc.clientSecret = "Client-Secret-3";
        storeProfile("keycloak", external, true);

        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, managedWithOidc());

        assertNotNull(row("keycloak"));
        assertTrue(row("keycloak").getProfileJson().contains("Client-Secret-3"));
    }

    @Test
    void onlyTheProfileEarlierVersionsCreatedCountsAsTheirs() {
        assertTrue(ContextService.isLegacyBootstrapProfile(legacyKeycloak(), ISSUER));
        assertTrue(ContextService.isLegacyBootstrapProfile(legacyKeycloak(), ISSUER + "/"));

        // Saved once from the Global Security page, unchanged: empty sections and unset switches.
        SecurityConfigDTO savedByThePage = legacyKeycloak();
        savedByThePage.tls = new TlsConfig();
        savedByThePage.extraProperties = new LinkedHashMap<>();
        savedByThePage.oidc.skipTlsVerify = Boolean.FALSE;
        savedByThePage.oidc.clientId = "";
        assertTrue(ContextService.isLegacyBootstrapProfile(savedByThePage, ISSUER));

        SecurityConfigDTO otherIssuer = legacyKeycloak();
        assertFalse(ContextService.isLegacyBootstrapProfile(otherIssuer, "https://other.example.com/realms/odp"));
        SecurityConfigDTO withTruststore = legacyKeycloak();
        withTruststore.tls = new TlsConfig();
        withTruststore.tls.truststoreSecret = "corp-ca";
        assertFalse(ContextService.isLegacyBootstrapProfile(withTruststore, ISSUER));
        SecurityConfigDTO withExtras = legacyKeycloak();
        withExtras.extraProperties = Map.of("http-server.authentication.oauth2.principal-field", "email");
        assertFalse(ContextService.isLegacyBootstrapProfile(withExtras, ISSUER));
        SecurityConfigDTO otherScopes = legacyKeycloak();
        otherScopes.oidc.scopes = "openid";
        assertFalse(ContextService.isLegacyBootstrapProfile(otherScopes, ISSUER));
        assertFalse(ContextService.isLegacyBootstrapProfile(ldap(), ISSUER));
        assertFalse(ContextService.isLegacyBootstrapProfile(null, ISSUER));
    }

    @Test
    void whenTheProfilesCannotBeReadNothingIsWritten() throws Exception {
        storeProfile("corp-ldap", ldap(), true);
        doThrow(new PersistenceException("connection reset")).when(store).findAll(eq(SecurityProfileEntity.class), any());

        assertThrows(IllegalStateException.class, () ->
                contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, managedWithOidc()));
        // As the security page runs it: logged, never failing the page.
        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, null, null);

        doThrow(new PersistenceException("connection reset")).when(store).find(eq(SecurityProfileEntity.class), any());
        assertThrows(IllegalStateException.class, () ->
                contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, managedWithOidc()));
        assertThrows(IllegalStateException.class, () ->
                contexts().reconcileDerivedOidcProfile("ext", null));

        verify(store, never()).store(any(SecurityProfileEntity.class));
        verify(store, never()).remove(any());
        assertNotNull(row("corp-ldap"));
    }

    @Test
    void aContextThatLosesOidcPrunesItsOwnProfileOnly() throws Exception {
        storeProfile("corp-ldap", ldap(), true);
        SecurityConfigDTO derived = legacyKeycloak();
        derived.derivedFromContext = "ext";
        storeProfile("ctx-oidc-ext", derived, false);
        ResolvedContext withoutOidc = new ResolvedContext();
        withoutOidc.setId("ext");
        withoutOidc.setKind(ContextService.KIND_EXTERNAL);

        contexts().reconcileDerivedOidcProfile("ext", withoutOidc);

        assertNull(row("ctx-oidc-ext"));
        assertNotNull(row("corp-ldap"));
        verify(store, times(1)).remove(any());
        verify(store, never()).store(any(SecurityProfileEntity.class));
    }

    @Test
    void aContextThatLosesOidcKeepsItsProfileWhileReleasesUseIt() throws Exception {
        storeDerivedDefault(true);
        releaseUsing(DERIVED);

        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, managedWithoutOidc());

        assertNotNull(row(DERIVED), "the release deploys with it");
        assertTrue(row(DERIVED).isDefault());
        assertEquals(DERIVED, profileOf("trino"));
        verify(store, never()).remove(any());

        noLongerUsed("trino");
        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, managedWithoutOidc());
        assertNull(row(DERIVED), "pruned once no release uses it");
    }

    @Test
    void aProfileIsKeptWhileTheContextsOidcSettingsCannotBeRead() throws Exception {
        storeDerivedDefault(true);
        ResolvedContext ambariFailed = managedWithoutOidc();
        ambariFailed.getUnreadFields().add("oidc.issuerUrl");

        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, ambariFailed);
        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, (ResolvedContext) null);

        assertNotNull(row(DERIVED));
        assertTrue(row(DERIVED).isDefault());

        // A remote cluster whose Ambari did not answer reports no version: its OIDC is unknown, not off.
        SecurityConfigDTO remoteDerived = legacyKeycloak();
        remoteDerived.derivedFromContext = "remote";
        storeProfile("ctx-oidc-remote", remoteDerived, false);
        ResolvedContext unreachable = new ResolvedContext();
        unreachable.setId("remote");
        unreachable.setKind(ContextService.KIND_REMOTE);
        contexts().reconcileDerivedOidcProfile("remote", unreachable);
        assertNotNull(row("ctx-oidc-remote"));
        verify(store, never()).remove(any());

        // One that answers with a version too old for OIDC no longer offers it.
        ResolvedContext tooOld = new ResolvedContext();
        tooOld.setId("remote");
        tooOld.setKind(ContextService.KIND_REMOTE);
        tooOld.setAmbariVersion("2.7.9.0");
        contexts().reconcileDerivedOidcProfile("remote", tooOld);
        assertNull(row("ctx-oidc-remote"));
        assertNotNull(row(DERIVED));
    }

    @Test
    void theLocalClustersProfileIsKeptWhenAmbariFailsToAnswerForItsOidcSettings() throws Exception {
        storeDerivedDefault(true);
        AmbariActionClient ambari = mock(AmbariActionClient.class);
        when(ambari.getDesiredConfigProperty(anyString(), eq("oidc-env"), anyString()))
                .thenThrow(new IOException("Server returned HTTP response code: 503"));

        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, ambari, "c1");

        assertNotNull(row(DERIVED), "Ambari could not be asked: that is not OIDC turned off");
        verify(store, never()).remove(any());

        // Ambari answers, and OIDC is no longer configured.
        reset(ambari);
        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, ambari, "c1");

        assertNull(row(DERIVED));
    }

    @Test
    void aProfileIsNeitherRewrittenNorCreatedFromAReadThatMissedSomeOidcSettings() throws Exception {
        SecurityConfigDTO full = legacyKeycloak();
        full.derivedFromContext = ContextService.DEFAULT_CONTEXT_ID;
        full.displayName = "Ambari-managed cluster (c1) (OIDC)";
        full.oidc.skipTlsVerify = Boolean.TRUE;
        storeProfile(DERIVED, full, true);
        String before = row(DERIVED).getProfileJson();
        // The issuer was read; the TLS setting was not, so the context reports it unset.
        ResolvedContext partial = managedWithOidc();
        partial.getUnreadFields().add("oidc.verifyTls");

        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, partial);

        assertEquals(before, row(DERIVED).getProfileJson(), "releases keep deploying with the TLS and domain settings");
        assertTrue(row(DERIVED).isDefault());
        verify(store, never()).store(any(SecurityProfileEntity.class));
        verify(store, never()).remove(any());

        // A context that has no profile yet does not get one built from half its settings.
        ResolvedContext external = new ResolvedContext();
        external.setId("ext");
        external.setName("External cluster");
        external.setKind(ContextService.KIND_EXTERNAL);
        external.setOidcIssuerUrl(ISSUER);
        external.setOidcAdminUrl("https://keycloak.example.com");
        external.setOidcAdminClientId("kdps-admin");
        external.setOidcAdminClientSecret("Admin-Secret-5");
        external.getUnreadFields().add("oidc.principalDomain");
        contexts().reconcileDerivedOidcProfile("ext", external);

        assertNull(row("ctx-oidc-ext"));
        verify(store, never()).store(any(SecurityProfileEntity.class));
    }

    @Test
    void theLocalClustersProfileIsLeftAsItIsWhenAmbariFailsToAnswerForOneOfItsOidcSettings() throws Exception {
        SecurityConfigDTO full = legacyKeycloak();
        full.derivedFromContext = ContextService.DEFAULT_CONTEXT_ID;
        full.oidc.skipTlsVerify = Boolean.TRUE;
        storeProfile(DERIVED, full, true);
        String before = row(DERIVED).getProfileJson();
        AmbariActionClient ambari = mock(AmbariActionClient.class);
        when(ambari.getDesiredConfigProperty("c1", "oidc-env", "oidc_issuer_url")).thenReturn(ISSUER);
        when(ambari.getDesiredConfigProperty("c1", "oidc-env", "oidc_principal_domain")).thenReturn("example.com");
        when(ambari.getDesiredConfigProperty("c1", "oidc-env", "oidc_verify_tls"))
                .thenThrow(new IOException("Server returned HTTP response code: 503"));

        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, ambari, "c1");

        assertEquals(before, row(DERIVED).getProfileJson());
        verify(store, never()).store(any(SecurityProfileEntity.class));

        // Ambari answers again: the profile is derived from everything it reads.
        doReturn("false").when(ambari).getDesiredConfigProperty("c1", "oidc-env", "oidc_verify_tls");
        contexts().reconcileDerivedOidcProfile(ContextService.DEFAULT_CONTEXT_ID, ambari, "c1");

        SecurityConfigDTO derived = new SecurityProfileService(view.context).loadProfiles().profiles.get(DERIVED);
        assertEquals(Boolean.TRUE, derived.oidc.skipTlsVerify);
        assertEquals("example.com", derived.oidc.principalDomain);
        assertTrue(row(DERIVED).isDefault());
    }

    @Test
    void savingAndDeletingAContextChangesOnlyItsOwnProfile() throws Exception {
        storeProfile("corp-ldap", ldap(), true);
        storeProfile("keycloak", legacyKeycloak(), false);
        String ldapBefore = row("corp-ldap").getProfileJson();
        view.as("admin");
        ContextRequest context = new ContextRequest();
        context.id = "ext";
        context.name = "External cluster";
        context.kind = ContextService.KIND_EXTERNAL;
        context.config = new LinkedHashMap<>(Map.of("issuerUrl", "https://sso.ext.example.com/realms/ext",
                "adminClientId", "kdps-admin"));
        context.secrets = new LinkedHashMap<>(Map.of("adminClientSecret", "Admin-Secret-5"));

        contexts().save(context);

        assertNotNull(row("ctx-oidc-ext"), "the context's own profile is derived");
        assertEquals(ldapBefore, row("corp-ldap").getProfileJson());
        assertNotNull(row("keycloak"), "only the local cluster's reconcile looks at the legacy profile");

        contexts().delete("ext");

        assertNull(row("ctx-oidc-ext"));
        assertEquals(List.of("corp-ldap", "keycloak"),
                new SecurityProfileService(view.context).loadProfiles().profiles.keySet().stream().sorted().toList());
        verify(store, times(1)).remove(any(SecurityProfileEntity.class));
    }

    @Test
    void deletingAContextKeepsItsProfileWhileReleasesUseIt() throws Exception {
        SecurityConfigDTO derived = legacyKeycloak();
        derived.derivedFromContext = "ext";
        storeProfile("ctx-oidc-ext", derived, false);
        releaseUsing("ctx-oidc-ext");
        view.as("admin");

        contexts().delete("ext");

        assertNotNull(row("ctx-oidc-ext"), "the release deploys with it");
        verify(store, never()).remove(any(SecurityProfileEntity.class));
    }
}
