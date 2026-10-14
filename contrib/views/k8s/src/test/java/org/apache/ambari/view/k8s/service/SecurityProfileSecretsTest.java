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
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.dto.security.LdapConfig;
import org.apache.ambari.view.k8s.dto.security.OidcConfig;
import org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO;
import org.apache.ambari.view.k8s.dto.security.SecurityProfilesDTO;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.KdpsSettingEntity;
import org.apache.ambari.view.k8s.store.SecurityProfileEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * The bind passwords and client secrets of the security profiles are kept encrypted apart from the profile rows:
 * no row holds them, a read for the browser only says which are set, the mask sent back keeps them, and deploys still
 * get them. Rows earlier versions wrote with them in clear are moved on the first read.
 */
class SecurityProfileSecretsTest {

    private static final String INSTANCE = "kdps";
    private static final String BIND = "Bind-Pass-9";
    private static final String AD_BIND = "Ad-Bind-Pass-4";
    private static final String CLIENT_SECRET = "Client-Secret-3";

    private InMemoryDataStore rows;
    private ViewContext ctx;

    @BeforeEach
    void setUp() {
        rows = new InMemoryDataStore();
        ctx = mock(ViewContext.class);
        when(ctx.getInstanceName()).thenReturn(INSTANCE);
        when(ctx.getDataStore()).thenReturn(rows);
    }

    private SecurityProfileService service() {
        return new SecurityProfileService(ctx);
    }

    private static SecurityConfigDTO ad() {
        SecurityConfigDTO cfg = new SecurityConfigDTO();
        cfg.mode = "ad";
        cfg.ldap = new LdapConfig();
        cfg.ldap.adUrl = "ldaps://ad.example.com:636";
        cfg.ldap.adBindDn = "cn=kdps,ou=services";
        cfg.ldap.bindPassword = BIND;
        cfg.ldap.adBindPassword = AD_BIND;
        cfg.oidc = new OidcConfig();
        cfg.oidc.source = "external";
        cfg.oidc.clientId = "trino";
        cfg.oidc.clientSecret = CLIENT_SECRET;
        return cfg;
    }

    private void save(Map<String, SecurityConfigDTO> profiles) {
        SecurityProfilesDTO dto = new SecurityProfilesDTO();
        dto.defaultProfile = profiles.keySet().iterator().next();
        dto.profiles = new LinkedHashMap<>(profiles);
        service().saveProfiles(dto);
    }

    /** What the Global Security page sends back for a profile it read: the masks, the flags, the rest as read. */
    private SecurityConfigDTO asReadByThePage(String id) {
        SecurityConfigDTO read = service().loadMaskedProfiles().profiles.get(id);
        return new ObjectMapper().convertValue(read, SecurityConfigDTO.class);
    }

    private String row(String id) {
        return rows.find(SecurityProfileEntity.class, id).getProfileJson();
    }

    /** Every value the view database holds, rows and settings alike. */
    private String everythingStored() throws Exception {
        StringBuilder all = new StringBuilder();
        for (SecurityProfileEntity e : rows.findAll(SecurityProfileEntity.class, null)) all.append(e.getProfileJson());
        for (KdpsSettingEntity e : rows.findAll(KdpsSettingEntity.class, null)) {
            all.append(e.getId()).append(e.getValue());
        }
        return all.toString();
    }

    @Test
    void noSecretIsStoredInClear() throws Exception {
        save(Map.of("corp-ad", ad()));

        String stored = everythingStored();
        for (String secret : List.of(BIND, AD_BIND, CLIENT_SECRET)) {
            assertFalse(stored.contains(secret), "in clear in the view database: " + stored);
        }
        assertTrue(row("corp-ad").contains("ldaps://ad.example.com:636"), "the rest of the profile is in its row");
        assertFalse(row("corp-ad").contains("Set\""), "the flags of a read are never stored: " + row("corp-ad"));
        assertNotNull(rows.find(KdpsSettingEntity.class, "secprofile.corp-ad.ldap.bindPassword"));
    }

    @Test
    void aReadForTheBrowserOnlySaysWhichSecretsAreSet() {
        SecurityConfigDTO ldapOnly = ad();
        ldapOnly.ldap.adBindPassword = null;
        save(Map.of("corp-ad", ldapOnly));

        SecurityConfigDTO read = service().loadMaskedProfiles().profiles.get("corp-ad");

        assertEquals(SecurityProfileService.MASKED_SECRET, read.ldap.bindPassword);
        assertEquals(Boolean.TRUE, read.ldap.bindPasswordSet);
        assertNull(read.ldap.adBindPassword);
        assertEquals(Boolean.FALSE, read.ldap.adBindPasswordSet);
        assertEquals(SecurityProfileService.MASKED_SECRET, read.oidc.clientSecret);
        assertEquals(Boolean.TRUE, read.oidc.clientSecretSet);
        assertNull(service().loadProfiles().profiles.get("corp-ad").ldap.bindPassword, "nor do other reads");
    }

    @Test
    void deploysGetTheSecrets() {
        save(Map.of("corp-ad", ad()));

        SecurityConfigDTO deployed = service().requireProfile("corp-ad");
        assertEquals(BIND, deployed.ldap.bindPassword);
        assertEquals(AD_BIND, deployed.ldap.adBindPassword);
        assertEquals(CLIENT_SECRET, deployed.oidc.clientSecret);
        assertEquals(CLIENT_SECRET, service().resolveProfile("corp-ad").oidc.clientSecret);
        assertEquals(CLIENT_SECRET, service().resolveProfile(null).oidc.clientSecret, "the default profile too");
    }

    @Test
    void theMaskOrABlankSentBackKeepsTheStoredSecretAndANewValueReplacesIt() {
        save(Map.of("corp-ad", ad()));

        SecurityConfigDTO back = asReadByThePage("corp-ad");
        back.ldap.adBindPassword = "";
        back.ldap.adBindDn = "cn=kdps2,ou=services";
        save(Map.of("corp-ad", back));

        SecurityConfigDTO deployed = service().requireProfile("corp-ad");
        assertEquals(BIND, deployed.ldap.bindPassword, "sent back masked");
        assertEquals(AD_BIND, deployed.ldap.adBindPassword, "sent back blank");
        assertEquals(CLIENT_SECRET, deployed.oidc.clientSecret);
        assertEquals("cn=kdps2,ou=services", deployed.ldap.adBindDn, "the rest is saved");

        SecurityConfigDTO rotated = asReadByThePage("corp-ad");
        rotated.ldap.bindPassword = "Bind-Pass-10";
        save(Map.of("corp-ad", rotated));
        assertEquals("Bind-Pass-10", service().requireProfile("corp-ad").ldap.bindPassword);
        assertEquals(AD_BIND, service().requireProfile("corp-ad").ldap.adBindPassword);
    }

    @Test
    void aSectionTheProfileNoLongerHasLosesItsSecrets() {
        save(Map.of("corp-ad", ad()));

        SecurityConfigDTO oidcOnly = asReadByThePage("corp-ad");
        oidcOnly.mode = "oidc";
        oidcOnly.ldap = null;
        save(Map.of("corp-ad", oidcOnly));

        assertNull(rows.find(KdpsSettingEntity.class, "secprofile.corp-ad.ldap.bindPassword"));
        assertEquals(CLIENT_SECRET, service().requireProfile("corp-ad").oidc.clientSecret);
    }

    @Test
    void aNewProfileSentTheMaskHasNoSecretNotEvenOneLeftOver() {
        // Left by a save whose profile row then failed to be stored.
        new org.apache.ambari.view.k8s.store.InstanceSettings(ctx)
                .putSecret("secprofile.copy.ldap.bindPassword", "Left-Over-1");
        SecurityConfigDTO copy = ad();
        copy.ldap.bindPassword = SecurityProfileService.MASKED_SECRET;
        copy.ldap.adBindPassword = null;
        copy.oidc.clientSecret = "";

        save(Map.of("copy", copy));

        SecurityConfigDTO deployed = service().requireProfile("copy");
        assertNull(deployed.ldap.bindPassword);
        assertNull(deployed.ldap.adBindPassword);
        assertNull(deployed.oidc.clientSecret);
    }

    @Test
    void deletingAProfileDeletesItsSecrets() {
        save(Map.of("corp-ad", ad()));

        service().deleteProfile("corp-ad");

        assertTrue(rows.findAll(KdpsSettingEntity.class, null).isEmpty(), "no setting, not even an empty one");
    }

    @Test
    void aRowAnEarlierVersionWroteHasItsSecretsMovedOnTheFirstReadAndKeepsItsFingerprint() throws Exception {
        SecurityConfigDTO legacy = ad();
        SecurityProfileEntity entity = new SecurityProfileEntity();
        entity.setId("corp-ad");
        entity.setViewInstance(INSTANCE);
        entity.setDefault(true);
        entity.setProfileJson(new ObjectMapper().writeValueAsString(legacy));
        rows.store(entity);
        String fingerprintAtDeploy = service().fingerprint(legacy);

        assertEquals(List.of("corp-ad"), List.copyOf(service().loadProfiles().profiles.keySet()));

        String stored = everythingStored();
        for (String secret : List.of(BIND, AD_BIND, CLIENT_SECRET)) {
            assertFalse(stored.contains(secret), "still in clear: " + stored);
        }
        assertTrue(rows.find(SecurityProfileEntity.class, "corp-ad").isDefault(), "the default flag is kept");
        SecurityConfigDTO deployed = service().requireProfile("corp-ad");
        assertEquals(BIND, deployed.ldap.bindPassword);
        assertEquals(CLIENT_SECRET, deployed.oidc.clientSecret);
        // The releases deployed with it are not reported as deployed with an older profile.
        assertEquals(fingerprintAtDeploy, service().fingerprint(service().resolveProfile("corp-ad")));
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theFingerprintChangesWithASecretYetIsNoDigestAGuessCanBeCheckedAgainst() throws Exception {
        save(Map.of("corp-ad", ad()));
        SecurityConfigDTO deployed = service().requireProfile("corp-ad");
        String atDeploy = service().fingerprint(deployed);

        assertNotNull(atDeploy);
        assertEquals(atDeploy, service().fingerprint(service().requireProfile("corp-ad")), "stable");
        // What someone reading the database could compute for a guessed password: the profile row with it filled in.
        assertNotEquals(sha256(new ObjectMapper().writeValueAsString(deployed)), atDeploy);
        assertFalse(everythingStored().contains(BIND), "the key it is computed with is encrypted too");
        // Keyed per view instance: the same profile elsewhere gives another fingerprint.
        ViewContext other = mock(ViewContext.class);
        when(other.getInstanceName()).thenReturn("other");
        when(other.getDataStore()).thenReturn(new InMemoryDataStore());
        assertNotEquals(atDeploy, new SecurityProfileService(other).fingerprint(ad()));

        SecurityConfigDTO rotated = asReadByThePage("corp-ad");
        rotated.ldap.adBindPassword = "Ad-Bind-Pass-Rotated";
        save(Map.of("corp-ad", rotated));

        SecurityConfigDTO now = service().requireProfile("corp-ad");
        assertNotEquals(atDeploy, service().fingerprint(now), "only a secret changed");
        assertFalse(service().matchesFingerprint(now, atDeploy), "the release is reported as deployed with an older profile");
        assertTrue(service().matchesFingerprint(now, service().fingerprint(now)));
    }

    @Test
    void aReleaseAnEarlierVersionFingerprintedIsNotReportedAsDeployedWithAnOlderProfile() throws Exception {
        save(Map.of("corp-ad", ad()));
        SecurityConfigDTO current = service().requireProfile("corp-ad");
        // As earlier versions computed it, over the profile with its secrets in clear.
        String earlier = sha256(new ObjectMapper().writeValueAsString(ad()));

        assertTrue(service().matchesFingerprint(current, earlier));
        assertFalse(service().matchesFingerprint(current, sha256("another profile")));
        assertTrue(service().matchesFingerprint(null, earlier), "no profile now: nothing to compare");
        assertTrue(service().matchesFingerprint(current, null), "nothing recorded: nothing to compare");
    }

    @Test
    void aRowWhoseSecretsCannotBeMovedYetStillServesDeploysAndNeverTheBrowser() throws Exception {
        SecurityProfileEntity entity = new SecurityProfileEntity();
        entity.setId("corp-ad");
        entity.setViewInstance(INSTANCE);
        entity.setProfileJson(new ObjectMapper().writeValueAsString(ad()));
        rows.store(entity);
        DataStore failing = spy(rows);
        doThrow(new PersistenceException("connection reset")).when(failing).store(any(KdpsSettingEntity.class));
        when(ctx.getDataStore()).thenReturn(failing);

        assertEquals(BIND, service().requireProfile("corp-ad").ldap.bindPassword, "the deploy goes on");
        SecurityConfigDTO read = service().loadMaskedProfiles().profiles.get("corp-ad");
        assertEquals(SecurityProfileService.MASKED_SECRET, read.ldap.bindPassword);
        assertEquals(Boolean.TRUE, read.ldap.bindPasswordSet);
        assertTrue(row("corp-ad").contains(BIND), "left as it was, to be moved by a later read");

        when(ctx.getDataStore()).thenReturn(rows);
        service().loadProfiles();
        assertFalse(row("corp-ad").contains(BIND), row("corp-ad"));
        assertEquals(BIND, service().requireProfile("corp-ad").ldap.bindPassword);
    }
}
