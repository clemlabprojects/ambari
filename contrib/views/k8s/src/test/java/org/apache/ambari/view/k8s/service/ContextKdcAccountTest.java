/*
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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A platform context's KDC account: one record in Ambari's credential store binding the KDC, the account and its
 * password, which only goes to the KDC it was entered for.
 */
class ContextKdcAccountTest {

    static final class MapStore implements ContextKdcAccount.Store {
        final Map<String, String> records = new HashMap<>();
        boolean failing;

        @Override
        public void put(String alias, String record) throws Exception {
            if (failing) throw new Exception("master key not found");
            records.put(alias, record);
        }

        @Override
        public String get(String alias) {
            return records.get(alias);
        }

        @Override
        public void remove(String alias) {
            records.remove(alias);
        }
    }

    private final MapStore store = new MapStore();
    private final ContextKdcAccount accounts = new ContextKdcAccount(store);
    private static final String ALIAS = "kdps.context.cdp-prod.kdc.admin";

    private static Map<String, Object> activeDirectory() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("realm", "CORP.EXAMPLE.COM");
        config.put("kdcType", "active-directory");
        config.put("kdcAdminPrincipal", "cm-kdc@CORP.EXAMPLE.COM");
        config.put("kdcLdapUrl", "ldaps://dc01.corp.example.com");
        config.put("kdcContainerDn", "OU=cloudera,DC=corp,DC=example,DC=com");
        return config;
    }

    private JsonObject record() {
        return JsonParser.parseString(store.records.get(ALIAS)).getAsJsonObject();
    }

    @Test
    void anActiveDirectoryAccountIsKeptAsOneRecordWithItsPassword() {
        assertTrue(accounts.apply("cdp-prod", activeDirectory(), "s3cret"));

        JsonObject record = record();
        assertEquals("active-directory", record.get("kdc_type").getAsString());
        assertEquals("CORP.EXAMPLE.COM", record.get("realm").getAsString());
        assertEquals("cm-kdc@CORP.EXAMPLE.COM", record.get("admin_principal").getAsString());
        assertEquals("s3cret", record.get("password").getAsString());
        assertEquals("ldaps://dc01.corp.example.com", record.get("ldap_url").getAsString());
        assertEquals("OU=cloudera,DC=corp,DC=example,DC=com", record.get("container_dn").getAsString());
        assertEquals(ContextKdcAccount.DEFAULT_ENCRYPTION_TYPES, record.get("encryption_types").getAsString());
        assertTrue(accounts.exists("cdp-prod"));
    }

    @Test
    void aSaveWithoutThePasswordKeepsItWhileTheKdcIsTheSame() {
        accounts.apply("cdp-prod", activeDirectory(), "s3cret");
        Map<String, Object> config = activeDirectory();
        config.put("kdcEncryptionTypes", "aes256-cts-hmac-sha1-96");

        assertTrue(accounts.apply("cdp-prod", config, ""));

        assertEquals("s3cret", record().get("password").getAsString());
        assertEquals("aes256-cts-hmac-sha1-96", record().get("encryption_types").getAsString());
    }

    @Test
    void settingsTheKdcToolsCouldTakeAsSyntaxAreRefused() {
        for (String[] change : new String[][]{{"kdcAdminPrincipal", "-q delprinc"}, {"kdcLdapUrl", "ldaps://dc01 -x"},
                {"kdcContainerDn", "OU=a\", \"servicePrincipalName\": \"b"}, {"realm", "CORP.EXAMPLE.COM\n[plugins]"}}) {
            Map<String, Object> config = activeDirectory();
            config.put(change[0], change[1]);

            assertThrows(IllegalArgumentException.class, () -> accounts.apply("cdp-prod", config, "s3cret"), change[0]);
        }
        assertFalse(accounts.exists("cdp-prod"));
    }

    @Test
    void theSavedPasswordIsNeverSentToAnotherKdc() {
        accounts.apply("cdp-prod", activeDirectory(), "s3cret");
        String before = store.records.get(ALIAS);
        for (String[] change : new String[][]{{"kdcLdapUrl", "ldaps://attacker.example.com"},
                {"kdcAdminPrincipal", "other@CORP.EXAMPLE.COM"}, {"realm", "OTHER.EXAMPLE.COM"}, {"kdcType", "mit-kdc"},
                // Where the accounts go: an organizational unit may carry rights a context editor should not hand out.
                {"kdcContainerDn", "OU=Domain Admins,DC=corp,DC=example,DC=com"}}) {
            Map<String, Object> config = activeDirectory();
            config.put(change[0], change[1]);
            config.put("kdcHost", "kdc.example.com");

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> accounts.apply("cdp-prod", config, null), change[0]);

            assertTrue(refused.getMessage().contains("Enter the KDC administrator password again"), refused.getMessage());
            assertEquals(before, store.records.get(ALIAS), "nothing changed for " + change[0]);
        }
    }

    @Test
    void anActiveDirectoryAccountNeedsLdapsAndItsOrganizationalUnit() {
        Map<String, Object> plain = activeDirectory();
        plain.put("kdcLdapUrl", "ldap://dc01.corp.example.com");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> accounts.apply("cdp-prod", plain, "s3cret"))
                .getMessage().contains("ldaps://"));

        Map<String, Object> noUnit = activeDirectory();
        noUnit.remove("kdcContainerDn");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> accounts.apply("cdp-prod", noUnit, "s3cret"))
                .getMessage().contains("organizational unit"));
        assertTrue(store.records.isEmpty());
    }

    @Test
    void anAccountNeedsTheRealmItsAdministratorAndAFirstPassword() {
        Map<String, Object> noRealm = activeDirectory();
        noRealm.remove("realm");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> accounts.apply("cdp-prod", noRealm, "s3cret"))
                .getMessage().contains("Kerberos realm"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> accounts.apply("cdp-prod", activeDirectory(), null))
                .getMessage().contains("Enter the KDC administrator password"));
        assertTrue(store.records.isEmpty());
    }

    @Test
    void anIpaAccountUsesTheKdcHostAsItsAdminServerUnlessGivenOne() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("realm", "DEV.EXAMPLE.COM");
        config.put("kdcType", "ipa");
        config.put("kdcAdminPrincipal", "hadoopadmin@DEV.EXAMPLE.COM");
        config.put("kdcHost", "ipa.dev.example.com");

        accounts.apply("dev-ipa", config, "s3cret");

        JsonObject record = JsonParser.parseString(store.records.get("kdps.context.dev-ipa.kdc.admin")).getAsJsonObject();
        assertEquals("ipa.dev.example.com", record.get("kdc_hosts").getAsString());
        assertEquals("ipa.dev.example.com", record.get("admin_server_host").getAsString());
        assertFalse(record.has("ldap_url"));
    }

    @Test
    void noKdcTypeRemovesTheRecord() {
        accounts.apply("cdp-prod", activeDirectory(), "s3cret");
        Map<String, Object> config = activeDirectory();
        config.put("kdcType", "none");

        assertFalse(accounts.apply("cdp-prod", config, null));

        assertFalse(store.records.containsKey(ALIAS));
    }

    @Test
    void aContextIdThatCannotNameTheRecordIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> accounts.apply("CDP_Prod", activeDirectory(), "s3cret"));
        assertTrue(store.records.isEmpty());
    }

    @Test
    void aCredentialStoreThatCannotTakeTheRecordSaysWhatToCheck() {
        store.failing = true;

        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> accounts.apply("cdp-prod", activeDirectory(), "s3cret"));

        assertTrue(failed.getMessage().contains("master key"), failed.getMessage());
        assertFalse(failed.getMessage().contains("s3cret"));
    }
}
