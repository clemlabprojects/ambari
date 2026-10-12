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

package org.apache.ambari.view.k8s.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.dto.security.LdapConfig;
import org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO;
import org.apache.ambari.view.k8s.dto.security.SecurityProfilesDTO;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.SecurityProfileEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * POST /configurations/security answers a profile that cannot be stored, or the removal of one a release uses, with
 * a refusal naming it and leaves every profile as it was, and removes no profile the page never loaded; a failed read
 * of the profiles is an error, never an empty list the page would then save back.
 */
class ConfigurationResourceSecurityProfilesTest {

    private static final String INSTANCE = "security-profiles-test";

    private InMemoryDataStore rows;
    private DataStore store;
    private ViewContext ctx;

    @BeforeEach
    void setUp() {
        rows = new InMemoryDataStore();
        store = mock(DataStore.class, delegatesTo(rows));
        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        Map<String, String> props = new HashMap<>();
        props.put("view.admin.users", "lead");
        props.put("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut");
        when(ctx.getProperties()).thenReturn(props);
        when(ctx.getUsername()).thenReturn("lead");
        when(ctx.getLoggedinUser()).thenReturn("lead");
        when(ctx.getInstanceName()).thenReturn(INSTANCE);
        when(ctx.getDataStore()).thenReturn(store);
    }

    private ConfigurationResource api() {
        return new ConfigurationResource(ctx, mock(KubernetesService.class), null);
    }

    private static SecurityConfigDTO ldap(String url) {
        SecurityConfigDTO cfg = new SecurityConfigDTO();
        cfg.mode = "ldap";
        cfg.ldap = new LdapConfig();
        cfg.ldap.url = url;
        cfg.ldap.bindPassword = "Bind-Pass-9";
        return cfg;
    }

    private static SecurityProfilesDTO profiles(String defaultProfile, Map<String, SecurityConfigDTO> profiles) {
        SecurityProfilesDTO dto = new SecurityProfilesDTO();
        dto.defaultProfile = defaultProfile;
        dto.profiles = new LinkedHashMap<>(profiles);
        return dto;
    }

    private SecurityProfilesDTO read() {
        Response r = api().getSecurityConfig(null, null);
        assertEquals(200, r.getStatus());
        return (SecurityProfilesDTO) r.getEntity();
    }

    private static String error(Response r) {
        return String.valueOf(((Map<?, ?>) r.getEntity()).get("error"));
    }

    @Test
    void anOversizeProfileIsA400NamingItAndTheOtherProfilesAreIntact() throws Exception {
        assertEquals(200, api().saveSecurityConfig(profiles("corp-ldap", Map.of(
                "corp-ldap", ldap("ldaps://ldap.example.com"), "keycloak", ldap("ldaps://sso.example.com")))).getStatus());
        SecurityConfigDTO big = ldap("ldaps://big.example.com");
        big.extraProperties = Map.of("ldap.user-search-filter", "(|" + "(memberOf=cn=team,ou=groups)".repeat(150) + ")");
        clearInvocations(store);

        Response r = api().saveSecurityConfig(profiles("corp-ldap", Map.of(
                "corp-ldap", ldap("ldaps://ldap.example.com"), "keycloak", ldap("ldaps://sso.example.com"), "big", big)));

        assertEquals(400, r.getStatus());
        assertTrue(error(r).contains("'big' is too large"), error(r));
        verify(store, never()).store(any());
        verify(store, never()).remove(any());
        SecurityProfilesDTO after = read();
        assertEquals(List.of("corp-ldap", "keycloak"), after.profiles.keySet().stream().sorted().toList());
        assertEquals("corp-ldap", after.defaultProfile);
    }

    @Test
    void leavingOutAProfileAReleaseUsesIsA409AndNothingChanges() throws Exception {
        api().saveSecurityConfig(profiles("corp-ldap", Map.of(
                "corp-ldap", ldap("ldaps://ldap.example.com"), "keycloak", ldap("ldaps://sso.example.com"))));
        K8sReleaseEntity trino = new K8sReleaseEntity();
        trino.setId(K8sReleaseEntity.idOf("analytics", "trino"));
        trino.setNamespace("analytics");
        trino.setReleaseName("trino");
        trino.setSecurityProfile("keycloak");
        rows.store(trino);

        Response r = api().saveSecurityConfig(profiles("corp-ldap", Map.of("corp-ldap", ldap("ldaps://changed.example.com"))));

        assertEquals(409, r.getStatus());
        assertEquals(List.of("analytics/trino"), ((Map<?, ?>) r.getEntity()).get("releases"));
        assertTrue(error(r).contains("'keycloak'"), error(r));
        SecurityProfilesDTO after = read();
        assertEquals("ldaps://ldap.example.com", after.profiles.get("corp-ldap").ldap.url);
        assertTrue(after.profiles.containsKey("keycloak"));
    }

    @Test
    void aSaveFromAPageThatNeverLoadedAProfileKeepsIt() throws Exception {
        api().saveSecurityConfig(profiles("corp-ldap", Map.of("corp-ldap", ldap("ldaps://ldap.example.com"))));
        // Saved by a context after the page read the profiles.
        SecurityProfileEntity derived = new SecurityProfileEntity();
        derived.setId("ctx-oidc-ext");
        derived.setViewInstance(INSTANCE);
        derived.setProfileJson("{\"mode\":\"oidc\",\"derivedFromContext\":\"ext\"}");
        rows.store(derived);
        SecurityProfileEntity prepared = new SecurityProfileEntity();
        prepared.setId("staging-ldap");
        prepared.setViewInstance(INSTANCE);
        prepared.setProfileJson("{\"mode\":\"ldap\",\"ldap\":{\"url\":\"ldaps://staging.example.com\"}}");
        rows.store(prepared);
        // What the Global Security page sends.
        SecurityProfilesDTO sent = new ObjectMapper().readValue("{\"defaultProfile\":\"corp-ldap\","
                + "\"profiles\":{\"corp-ldap\":{\"mode\":\"ldap\",\"ldap\":{\"url\":\"ldaps://ldap2.example.com\"}}},"
                + "\"loadedProfiles\":[\"corp-ldap\"]}", SecurityProfilesDTO.class);

        assertEquals(200, api().saveSecurityConfig(sent).getStatus());

        SecurityProfilesDTO after = read();
        assertEquals(List.of("corp-ldap", "ctx-oidc-ext", "staging-ldap"),
                after.profiles.keySet().stream().sorted().toList());
        assertEquals("ldaps://ldap2.example.com", after.profiles.get("corp-ldap").ldap.url);
        assertNull(after.loadedProfiles, "a read never carries it");
    }

    @Test
    void aFailedReadIsAnErrorAndSavingWritesNothing() throws Exception {
        api().saveSecurityConfig(profiles("corp-ldap", Map.of("corp-ldap", ldap("ldaps://ldap.example.com"))));
        doThrow(new PersistenceException("connection reset")).when(store).findAll(eq(SecurityProfileEntity.class), any());
        clearInvocations(store);

        Response get = api().getSecurityConfig(null, null);
        assertEquals(500, get.getStatus(), "never an empty list the page would save back");
        assertTrue(error(get).contains("could not be read"), error(get));
        assertEquals(500, api().getSecurityProfileUsage("corp-ldap").getStatus());
        assertEquals(500, api().deleteSecurityProfile("corp-ldap").getStatus());
        assertEquals(500, api().saveSecurityConfig(profiles("other", Map.of("other", ldap("ldaps://other.example.com"))))
                .getStatus());

        verify(store, never()).store(any());
        verify(store, never()).remove(any());
        assertNotNull(rows.find(SecurityProfileEntity.class, "corp-ldap"));
    }
}
