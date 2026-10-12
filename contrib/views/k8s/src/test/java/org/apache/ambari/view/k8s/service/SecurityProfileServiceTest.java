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

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.dto.security.LdapConfig;
import org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO;
import org.apache.ambari.view.k8s.dto.security.SecurityProfilesDTO;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.SecurityProfileEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Saving the security profiles stores each profile and removes only those the request leaves out, once everything
 * else is stored and only when no release uses them; reading them never mistakes a failed read for "no profiles".
 * Every profile lives in its own row, so losing one write must never cost the others.
 */
class SecurityProfileServiceTest {

    private static final String INSTANCE = "kdps";
    /** A profile derived from the platform context {@code ext}, as stored. */
    private static final String DERIVED_EXT = "{\"mode\":\"oidc\",\"derivedFromContext\":\"ext\"}";

    /** The rows, as Ambari's DataStore keeps them. */
    private InMemoryDataStore rows;
    /** What the view calls: the rows, with every call recorded and any of them made to fail. */
    private DataStore store;
    private ViewContext ctx;

    @BeforeEach
    void setUp() {
        rows = new InMemoryDataStore();
        store = mock(DataStore.class, delegatesTo(rows));
        ctx = mock(ViewContext.class);
        when(ctx.getInstanceName()).thenReturn(INSTANCE);
        when(ctx.getDataStore()).thenReturn(store);
    }

    private SecurityProfileService service() {
        return new SecurityProfileService(ctx);
    }

    private static SecurityConfigDTO ldap(String url) {
        SecurityConfigDTO cfg = new SecurityConfigDTO();
        cfg.mode = "ldap";
        cfg.ldap = new LdapConfig();
        cfg.ldap.url = url;
        cfg.ldap.bindDn = "cn=kdps,ou=services";
        cfg.ldap.bindPassword = "Bind-Pass-9";
        return cfg;
    }

    private void storeRow(String id, String json, boolean isDefault) {
        SecurityProfileEntity entity = new SecurityProfileEntity();
        entity.setId(id);
        entity.setViewInstance(INSTANCE);
        entity.setDefault(isDefault);
        entity.setProfileJson(json);
        rows.store(entity);
    }

    private void storeProfiles(String defaultProfile, Map<String, SecurityConfigDTO> profiles) {
        SecurityProfilesDTO dto = new SecurityProfilesDTO();
        dto.defaultProfile = defaultProfile;
        dto.profiles = new LinkedHashMap<>(profiles);
        service().saveProfiles(dto);
    }

    private void releaseUsing(String profile, String namespace, String release) {
        K8sReleaseEntity entity = new K8sReleaseEntity();
        entity.setId(K8sReleaseEntity.idOf(namespace, release));
        entity.setNamespace(namespace);
        entity.setReleaseName(release);
        entity.setSecurityProfile(profile);
        rows.store(entity);
    }

    private static SecurityProfilesDTO payload(String defaultProfile, Map<String, SecurityConfigDTO> profiles) {
        SecurityProfilesDTO dto = new SecurityProfilesDTO();
        dto.defaultProfile = defaultProfile;
        dto.profiles = new LinkedHashMap<>(profiles);
        return dto;
    }

    @Test
    void anOversizeProfileIsRefusedByNameAndEveryProfileStaysAsItWas() throws Exception {
        storeProfiles("corp-ldap", Map.of("corp-ldap", ldap("ldaps://ldap.example.com"),
                "keycloak", ldap("ldaps://other.example.com")));
        SecurityConfigDTO big = ldap("ldaps://big.example.com");
        big.extraProperties = Map.of("caPem", "x".repeat(4096));
        clearInvocations(store);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service().saveProfiles(
                payload("corp-ldap", Map.of("corp-ldap", ldap("ldaps://changed.example.com"), "big", big))));

        assertTrue(refused.getMessage().contains("'big'"), refused.getMessage());
        assertTrue(refused.getMessage().contains("at most 3000"), refused.getMessage());
        assertFalse(refused.getMessage().contains("Bind-Pass-9"), "a profile is never echoed: " + refused.getMessage());
        verify(store, never()).store(any());
        verify(store, never()).remove(any());
        SecurityProfilesDTO after = service().loadProfiles();
        assertEquals("ldaps://ldap.example.com", after.profiles.get("corp-ldap").ldap.url);
        assertEquals("ldaps://other.example.com", after.profiles.get("keycloak").ldap.url);
        assertEquals("corp-ldap", after.defaultProfile);
    }

    @Test
    void savingStoresEachProfileInPlaceAndRemovesOnlyThoseLeftOut() throws Exception {
        storeProfiles("corp-ldap", Map.of("corp-ldap", ldap("ldaps://ldap.example.com"),
                "old", ldap("ldaps://old.example.com")));
        clearInvocations(store);

        service().saveProfiles(payload("new", Map.of("corp-ldap", ldap("ldaps://ldap2.example.com"),
                "new", ldap("ldaps://new.example.com"))));

        SecurityProfilesDTO after = service().loadProfiles();
        assertEquals(List.of("corp-ldap", "new"), after.profiles.keySet().stream().sorted().toList());
        assertEquals("ldaps://ldap2.example.com", after.profiles.get("corp-ldap").ldap.url);
        assertEquals("new", after.defaultProfile);
        verify(store, never()).remove(argThat(e -> e instanceof SecurityProfileEntity p && "corp-ldap".equals(p.getId())));
        verify(store).remove(argThat(e -> e instanceof SecurityProfileEntity p && "old".equals(p.getId())));
    }

    @Test
    void aProfileLeftOutIsKeptWhileAReleaseUsesItAndNothingIsSaved() throws Exception {
        storeProfiles("corp-ldap", Map.of("corp-ldap", ldap("ldaps://ldap.example.com"),
                "keycloak", ldap("ldaps://sso.example.com")));
        releaseUsing("keycloak", "analytics", "trino");
        clearInvocations(store);

        SecurityProfileService.ProfilesInUseException refused = assertThrows(
                SecurityProfileService.ProfilesInUseException.class,
                () -> service().saveProfiles(payload("corp-ldap", Map.of("corp-ldap", ldap("ldaps://changed.example.com")))));

        assertEquals(Map.of("keycloak", List.of("analytics/trino")), refused.releasesByProfile());
        assertTrue(refused.getMessage().contains("'keycloak' is still used by analytics/trino"), refused.getMessage());
        verify(store, never()).store(any(SecurityProfileEntity.class));
        verify(store, never()).remove(any());
        assertEquals("ldaps://ldap.example.com", service().loadProfiles().profiles.get("corp-ldap").ldap.url);
    }

    @Test
    void onlyTheProfilesTheSenderReadAreRemovedForBeingLeftOut() throws Exception {
        storeProfiles("corp-ldap", Map.of("corp-ldap", ldap("ldaps://ldap.example.com"),
                "old", ldap("ldaps://old.example.com"), "staging-ldap", ldap("ldaps://staging.example.com")));
        // Derived from a context saved after the page read the profiles.
        storeRow("ctx-oidc-ext", DERIVED_EXT, false);
        clearInvocations(store);

        // The page read corp-ldap and old, and removed old; staging-ldap was created after it read them.
        SecurityProfilesDTO save = payload("corp-ldap", Map.of("corp-ldap", ldap("ldaps://ldap2.example.com")));
        save.loadedProfiles = List.of("corp-ldap", "old");
        service().saveProfiles(save);

        assertEquals(List.of("corp-ldap", "ctx-oidc-ext", "staging-ldap"),
                service().loadProfiles().profiles.keySet().stream().sorted().toList());
        verify(store, times(1)).remove(any());

        // A page whose read failed showed nothing, so its save removes nothing.
        SecurityProfilesDTO blind = payload("default", Map.of("default", ldap("ldaps://ldap3.example.com")));
        blind.loadedProfiles = List.of();
        service().saveProfiles(blind);
        assertEquals(List.of("corp-ldap", "ctx-oidc-ext", "default", "staging-ldap"),
                service().loadProfiles().profiles.keySet().stream().sorted().toList());
    }

    @Test
    void aProfileDerivedFromAContextIsNeverRemovedForBeingLeftOut() throws Exception {
        storeRow("ctx-oidc-ext", DERIVED_EXT, false);

        service().saveProfiles(payload("corp-ldap", Map.of("corp-ldap", ldap("ldaps://ldap.example.com"))));

        assertNotNull(rows.find(SecurityProfileEntity.class, "ctx-oidc-ext"), "its context's reconcile owns it");
        service().deleteProfile("ctx-oidc-ext");
        assertNull(rows.find(SecurityProfileEntity.class, "ctx-oidc-ext"), "deleting it by name still works");
    }

    @Test
    void theDefaultTheSenderChoseIsTheOnlyDefaultEvenAmongProfilesItNeverSaw() throws Exception {
        storeRow("ctx-oidc-ext", DERIVED_EXT, true);

        SecurityProfilesDTO save = payload("corp-ldap", Map.of("corp-ldap", ldap("ldaps://ldap.example.com")));
        save.loadedProfiles = List.of();
        service().saveProfiles(save);

        assertTrue(rows.find(SecurityProfileEntity.class, "corp-ldap").isDefault());
        SecurityProfileEntity unseen = rows.find(SecurityProfileEntity.class, "ctx-oidc-ext");
        assertFalse(unseen.isDefault());
        assertEquals(DERIVED_EXT, unseen.getProfileJson(), "only the flag changes");
        assertEquals("corp-ldap", service().loadProfiles().defaultProfile);

        // A sender that names no profile it saves as the default leaves the flag where it is.
        SecurityProfilesDTO noDefault = payload("missing", Map.of("other", ldap("ldaps://other.example.com")));
        noDefault.loadedProfiles = List.of();
        service().saveProfiles(noDefault);
        assertTrue(rows.find(SecurityProfileEntity.class, "corp-ldap").isDefault());
        assertFalse(rows.find(SecurityProfileEntity.class, "other").isDefault());
    }

    @Test
    void profilesLeftOutAreRemovedOnlyOnceEveryProfileIsStored() throws Exception {
        storeProfiles("corp-ldap", Map.of("corp-ldap", ldap("ldaps://ldap.example.com"),
                "old", ldap("ldaps://old.example.com")));
        doThrow(new IllegalStateException("connection reset")).when(store)
                .store(argThat(e -> e instanceof SecurityProfileEntity p && "second".equals(p.getId())));

        assertThrows(IllegalStateException.class, () -> service().saveProfiles(payload("corp-ldap",
                new LinkedHashMap<>(Map.of("corp-ldap", ldap("ldaps://ldap.example.com"),
                        "second", ldap("ldaps://second.example.com"))))));

        SecurityProfilesDTO after = service().loadProfiles();
        assertTrue(after.profiles.containsKey("old"), "nothing is removed before every profile is stored");
        assertTrue(after.profiles.containsKey("corp-ldap"));
    }

    @Test
    void aFailedReadIsAnErrorNotAnEmptySetAndNothingIsWritten() throws Exception {
        storeProfiles("corp-ldap", Map.of("corp-ldap", ldap("ldaps://ldap.example.com")));
        doThrow(new PersistenceException("connection reset")).when(store).findAll(eq(SecurityProfileEntity.class), any());
        clearInvocations(store);

        IllegalStateException read = assertThrows(IllegalStateException.class, () -> service().loadProfiles());
        assertTrue(read.getMessage().contains("could not be read"), read.getMessage());
        assertThrows(IllegalStateException.class, () -> service().saveProfiles(
                payload("other", Map.of("other", ldap("ldaps://other.example.com")))));
        IllegalStateException deploy = assertThrows(IllegalStateException.class,
                () -> service().requireProfile("corp-ldap"));
        assertTrue(deploy.getMessage().contains("'corp-ldap'"), deploy.getMessage());

        verify(store, never()).store(any());
        verify(store, never()).remove(any());
    }

    @Test
    void anUnreadableRowIsLeftOutFlaggedAndNeverRemovedBySaving() throws Exception {
        storeRow("corp-ldap", "{\"mode\":\"ldap\",\"ldap\":{\"url\":\"ldaps://ldap.example.com\"},"
                + "\"fieldOfALaterVersion\":{\"x\":1}}", true);
        storeRow("broken", "{\"mode\":", false);

        SecurityProfilesDTO loaded = service().loadProfiles();
        assertEquals(List.of("corp-ldap"), List.copyOf(loaded.profiles.keySet()),
                "a field this version does not know is ignored; a row it cannot parse is left out");
        assertTrue(loaded.incomplete);
        assertEquals("corp-ldap", loaded.defaultProfile);

        service().saveProfiles(payload("corp-ldap", Map.of("corp-ldap", ldap("ldaps://ldap2.example.com"))));

        assertNotNull(rows.find(SecurityProfileEntity.class, "broken"), "a profile the page never showed is kept");
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service().requireProfile("broken"));
        assertTrue(missing.getMessage().contains("could be read"), missing.getMessage());
    }

    @Test
    void aDeployNamingAProfileThatIsGoneIsToldSo() throws Exception {
        storeProfiles("corp-ldap", Map.of("corp-ldap", ldap("ldaps://ldap.example.com")));

        assertEquals("ldaps://ldap.example.com", service().requireProfile("corp-ldap").ldap.url);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service().requireProfile("keycloak"));
        assertTrue(missing.getMessage().startsWith("The security profile 'keycloak' that this deploy asks for does not"
                + " exist"), missing.getMessage());
        assertTrue(missing.getMessage().contains("nothing was deployed"), missing.getMessage());
    }

    @Test
    void deletingAProfileRemovesThatRowOnly() throws Exception {
        storeProfiles("corp-ldap", Map.of("corp-ldap", ldap("ldaps://ldap.example.com"),
                "old", ldap("ldaps://old.example.com")));

        service().deleteProfile("old");
        service().deleteProfile("never-existed");

        assertEquals(List.of("corp-ldap"), List.copyOf(service().loadProfiles().profiles.keySet()));
    }
}
