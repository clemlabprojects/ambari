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
package org.apache.ambari.view.k8s.migration;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ambari.view.k8s.service.ViewConfigurationService;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.KdpsSettingEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.store.SecurityProfileEntity;
import org.apache.ambari.view.migration.EntityConverter;
import org.apache.ambari.view.migration.ViewDataMigrationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KdpsDataMigrator delegates to the migration context with the accessor-based copy, and seeds the settings shared
 * by every user from the per-user instance data of the previous version.
 */
public class KdpsDataMigratorTest {

    private final List<String> calls = new ArrayList<>();
    private Map<String, Map<String, String>> originInstanceData =
            Map.of("admin", Map.of("kubeconfig.path", "/x", "proxy.url", "http://p"));
    private InMemoryDataStore currentDataStore = new InMemoryDataStore();
    /** The instance data of the new version, by user, as Ambari's copy and the migrator's writes leave it. */
    private final Map<String, Map<String, String>> currentInstanceData = new LinkedHashMap<>();
    /** The rows of the previous version, which stay in Ambari's database after the upgrade. */
    private final InMemoryDataStore originDataStore = new InMemoryDataStore();

    private ViewDataMigrationContext context() {
        return (ViewDataMigrationContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ViewDataMigrationContext.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "copyAllObjects":
                            calls.add("copy:" + ((Class<?>) args[1]).getSimpleName());
                            assertEquals(3, args.length, "the converter overload must be used");
                            assertInstanceOf(AccessorCopyConverter.class, args[2]);
                            assertTrue(args[2] instanceof EntityConverter);
                            // The rows as the new version gets them, before the previous version's are cleared.
                            for (Object row : originDataStore.findAll((Class<?>) args[0], null)) {
                                currentDataStore.store(row);
                            }
                            return null;
                        case "copyAllInstanceData":
                            calls.add("instanceData");
                            originInstanceData.forEach((user, data) ->
                                    currentInstanceData.computeIfAbsent(user, u -> new LinkedHashMap<>()).putAll(data));
                            return null;
                        case "getOriginInstanceDataByUser":
                            return originInstanceData;
                        case "getCurrentInstanceDataByUser":
                            return currentInstanceData;
                        case "putCurrentInstanceData":
                            currentInstanceData.computeIfAbsent((String) args[0], u -> new LinkedHashMap<>())
                                    .put((String) args[1], (String) args[2]);
                            return null;
                        case "putOriginInstanceData":
                            Map<String, Map<String, String>> origin = new LinkedHashMap<>();
                            originInstanceData.forEach((user, data) -> origin.put(user, new LinkedHashMap<>(data)));
                            origin.computeIfAbsent((String) args[0], u -> new LinkedHashMap<>())
                                    .put((String) args[1], (String) args[2]);
                            originInstanceData = origin;
                            return null;
                        case "getCurrentDataStore":
                            return currentDataStore;
                        case "getOriginDataStore":
                            return originDataStore;
                        case "getOriginDataVersion":
                            return 0;
                        case "getCurrentDataVersion":
                            return 9;
                        default:
                            return null;
                    }
                });
    }

    @Test
    void copiesDeclaredEntitiesWithTheAccessorConverterAndSkipsDroppedOnes() throws Exception {
        KdpsDataMigrator migrator = new KdpsDataMigrator(context());
        assertTrue(migrator.beforeMigration());
        migrator.migrateEntity(AccessorCopyConverterTest.LegacyRelease.class, String.class);
        migrator.migrateEntity(AccessorCopyConverterTest.LegacyRelease.class, null);
        migrator.migrateInstanceData();
        migrator.afterMigration();
        assertEquals(List.of("copy:String", "instanceData"), calls);
    }

    private static Map<String, String> data(String... keyValues) {
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            data.put(keyValues[i], keyValues[i + 1]);
        }
        return data;
    }

    private String shared(String key) throws Exception {
        KdpsSettingEntity row = currentDataStore.find(KdpsSettingEntity.class, key);
        return row == null ? null : row.getValue();
    }

    @Test
    void seedsTheSharedSettingsFromThePerUserRowsWithTheConnectionOfTheUserWhoConfiguredTheView() throws Exception {
        originInstanceData = Map.of(
                "bob", data("kubeconfig.uploaded", "false", "kubeconfig.context", "bobs-cluster",
                        "helm.repo.secret.bob-repo", "enc-bob", "view.settings.json", "{\"bob\":true}"),
                "alice", data("kubeconfig.uploaded", "true", "kubeconfig.path", "/var/lib/kdps/kubeconfig.yaml.enc",
                        "auth.mode", "openshift-login", "openshift.apiUrl", "https://api.ocp:6443",
                        "openshift.password.enc", "enc-os", "proxy.url", "http://proxy:3128",
                        "helm.repo.secret.harbor", "enc-harbor", "context.ext.rangerAdminPassword", "enc-ranger",
                        "view.settings.json", "{\"alice\":true}", "monitoring.repoId", "prom"),
                "upgrade", data("upgradedFrom", "1.0.0.7"));

        new KdpsDataMigrator(context()).migrateInstanceData();

        assertEquals("true", shared("kubeconfig.uploaded"));
        assertEquals("/var/lib/kdps/kubeconfig.yaml.enc", shared("kubeconfig.path"));
        assertEquals("openshift-login", shared("auth.mode"));
        assertEquals("enc-os", shared("openshift.password.enc"));
        assertEquals("http://proxy:3128", shared("proxy.url"));
        assertNull(shared("kubeconfig.context"), "the connection is never mixed with another user's");
        assertEquals("enc-harbor", shared("helm.repo.secret.harbor"));
        assertEquals("enc-bob", shared("helm.repo.secret.bob-repo"), "every user's secrets are kept");
        assertEquals("enc-ranger", shared("context.ext.rangerAdminPassword"));
        assertEquals("{\"alice\":true}", shared("view.settings.json"), "the configuring user's value comes first");
        assertEquals("prom", shared("monitoring.repoId"));
        assertNull(shared("upgradedFrom"), "Ambari's own bookkeeping is not a setting");
        assertTrue(calls.contains("instanceData"), "the per-user rows are still copied for the carry-over");
    }

    @Test
    void theFirstConfiguredUserByNameProvidesTheConnectionWhenSeveralDid() {
        Map<String, String> shared = KdpsDataMigrator.sharedSettingsFrom(Map.of(
                "zoe", data("kubeconfig.uploaded", "true", "kubeconfig.path", "/z"),
                "bob", data("kubeconfig.uploaded", "true", "kubeconfig.path", "/b"),
                "alice", data("helm.repo.secret.a", "enc-a")));

        assertEquals("/b", shared.get("kubeconfig.path"));
        assertEquals("enc-a", shared.get("helm.repo.secret.a"));
    }

    @Test
    void noConnectionIsSeededWhenNobodyHadConfiguredTheView() {
        Map<String, String> shared = KdpsDataMigrator.sharedSettingsFrom(Map.of(
                "admin", data("kubeconfig.path", "/x", "proxy.url", "http://p", "view.settings.json", "{}")));

        assertFalse(shared.containsKey("kubeconfig.path"));
        assertFalse(shared.containsKey("proxy.url"));
        assertEquals("{}", shared.get("view.settings.json"));
    }

    @Test
    void settingsTheNewInstanceAlreadyHoldsAreKept() throws Exception {
        currentDataStore.store(new KdpsSettingEntity("view.settings.json", "{\"current\":true}"));
        originInstanceData = Map.of("alice", data("kubeconfig.uploaded", "true", "view.settings.json", "{\"old\":true}"));

        new KdpsDataMigrator(context()).migrateInstanceData();

        assertEquals("{\"current\":true}", shared("view.settings.json"));
        assertEquals("true", shared("kubeconfig.uploaded"));
    }

    @Test
    void aUserWhoHadNothingSavedFindsTheViewConfiguredAfterTheUpgrade(@TempDir Path workDir) throws Exception {
        Path kubeconfig = workDir.resolve("kubeconfig.yaml.enc");
        Files.write(kubeconfig, "encrypted".getBytes(StandardCharsets.UTF_8));
        PerUserViewContext upgraded = new PerUserViewContext("upgraded", workDir);
        currentDataStore = upgraded.dataStore;
        originInstanceData = Map.of("alice", data("kubeconfig.uploaded", "true", "kubeconfig.path", kubeconfig.toString(),
                "auth.mode", "kubeconfig"));

        new KdpsDataMigrator(context()).migrateInstanceData();

        upgraded.as("ops1");
        ViewConfigurationService configuration = new ViewConfigurationService(upgraded.context);
        assertTrue(configuration.isConfigured());
        assertEquals(kubeconfig.toString(), configuration.getKubeconfigPath());
        assertEquals("true", new InstanceSettings(upgraded.context).get("kubeconfig.uploaded"));
    }

    /** A repository row of a version whose repository still had the property the registry secret was kept in. */
    public static class HelmRepoEntity {
        private String id;
        private String url;
        private String plainSecret;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getPlainSecret() { return plainSecret; }
        public void setPlainSecret(String plainSecret) { this.plainSecret = plainSecret; }
    }

    private static HelmRepoEntity legacyRepo(String id, String plainSecret) {
        HelmRepoEntity repo = new HelmRepoEntity();
        repo.setId(id);
        repo.setUrl("harbor.example.com/" + id);
        repo.setPlainSecret(plainSecret);
        return repo;
    }

    private static CommandStatusEntity status(String id, String resultJson) {
        CommandStatusEntity st = new CommandStatusEntity();
        st.setId(id);
        st.setState("SUCCEEDED");
        st.setResultJson(resultJson);
        return st;
    }

    @Test
    void theRegistrySecretsThePreviousVersionKeptInItsRepositoryRowsAreBlankedAfterTheCopy() throws Exception {
        originDataStore.store(legacyRepo("harbor", "Registry-Token-3"));
        originDataStore.store(legacyRepo("charts", null));

        new KdpsDataMigrator(context()).migrateEntity(HelmRepoEntity.class,
                org.apache.ambari.view.k8s.store.HelmRepoEntity.class);

        assertEquals(List.of("copy:HelmRepoEntity"), calls, "copied first");
        // Blank, not null: the DataStore leaves a property it is given as null unchanged.
        assertEquals("", originDataStore.find(HelmRepoEntity.class, "harbor").getPlainSecret());
        assertEquals("harbor.example.com/harbor", originDataStore.find(HelmRepoEntity.class, "harbor").getUrl());
        assertNull(originDataStore.find(HelmRepoEntity.class, "charts").getPlainSecret(), "nothing to blank");
    }

    @Test
    void theOidcClientSecretsThePreviousVersionKeptInStepResultsAreCleared() throws Exception {
        originDataStore.store(status("register-status",
                "{\"clientId\":\"trino\",\"issuerUrl\":\"https://sso/realms/odp\",\"clientSecret\":\"Kc-Secret-1\"}"));
        originDataStore.store(status("helm-status", "{\"release\":\"trino\"}"));

        new KdpsDataMigrator(context()).migrateEntity(CommandStatusEntity.class, CommandStatusEntity.class);

        String cleared = originDataStore.find(CommandStatusEntity.class, "register-status").getResultJson();
        assertFalse(cleared.contains("Kc-Secret-1"), cleared);
        assertTrue(cleared.contains("\"clientId\":\"trino\""), cleared);
        assertEquals("{\"release\":\"trino\"}",
                originDataStore.find(CommandStatusEntity.class, "helm-status").getResultJson());
    }

    @Test
    void theSecretsThePreviousVersionKeptInItsSecurityProfilesAreClearedAfterTheCopy() throws Exception {
        SecurityProfileEntity legacy = new SecurityProfileEntity();
        legacy.setId("corp-ad");
        legacy.setViewInstance("kdps");
        legacy.setDefault(true);
        legacy.setProfileJson("{\"mode\":\"ldap\",\"ldap\":{\"url\":\"ldaps://ad.example.com:636\","
                + "\"bindDn\":\"cn=svc-kdps\",\"bindPassword\":\"Ad-Bind-Pass-1\",\"adBindPassword\":\"Ad-Bind-Pass-2\"},"
                + "\"oidc\":{\"clientId\":\"kdps\",\"clientSecret\":\"Oidc-Secret-3\"},\"fromAnotherVersion\":7}");
        originDataStore.store(legacy);
        SecurityProfileEntity none = new SecurityProfileEntity();
        none.setId("plain");
        none.setViewInstance("kdps");
        none.setProfileJson("{\"mode\":\"ldap\",\"ldap\":{\"url\":\"ldaps://ad\",\"bindPassword\":null}}");
        originDataStore.store(none);

        new KdpsDataMigrator(context()).migrateEntity(SecurityProfileEntity.class, SecurityProfileEntity.class);

        SecurityProfileEntity origin = originDataStore.find(SecurityProfileEntity.class, "corp-ad");
        for (String secret : List.of("Ad-Bind-Pass-1", "Ad-Bind-Pass-2", "Oidc-Secret-3")) {
            assertFalse(origin.getProfileJson().contains(secret), origin.getProfileJson());
        }
        assertTrue(origin.getProfileJson().contains("\"bindDn\":\"cn=svc-kdps\""), origin.getProfileJson());
        assertTrue(origin.getProfileJson().contains("\"fromAnotherVersion\":7"), "every other field is kept");
        assertTrue(origin.isDefault());
        assertEquals("{\"mode\":\"ldap\",\"ldap\":{\"url\":\"ldaps://ad\",\"bindPassword\":null}}",
                originDataStore.find(SecurityProfileEntity.class, "plain").getProfileJson(), "nothing to clear");
        // This version still gets them, and moves them to its encrypted settings the first time it loads the profile.
        String copied = currentDataStore.find(SecurityProfileEntity.class, "corp-ad").getProfileJson();
        assertTrue(copied.contains("Ad-Bind-Pass-1") && copied.contains("Oidc-Secret-3"), copied);
    }

    @Test
    void otherEntitiesOfThePreviousVersionAreLeftAsTheyWere() throws Exception {
        originDataStore.store(status("register-status", "{\"clientSecret\":\"Kc-Secret-1\"}"));

        new KdpsDataMigrator(context()).migrateEntity(KdpsSettingEntity.class, KdpsSettingEntity.class);

        assertTrue(originDataStore.find(CommandStatusEntity.class, "register-status").getResultJson()
                .contains("Kc-Secret-1"), "only the entity being migrated is looked at");
    }

    @Test
    void theThanosCredentialsUsersKeptInClearAreEncryptedInTheSharedSettingsAndRemovedFromEveryCopy() throws Exception {
        String access = "AKIA-OLD-ACCESS";
        String secret = "S3-Old-Secret-Key";
        String aliceSettings = "{\"monitoring\":{\"autoBootstrap\":true,\"thanos\":{\"enabled\":true,"
                + "\"bucket\":\"metrics\",\"accessKey\":\"" + access + "\",\"secretKey\":\"" + secret + "\"}}}";
        // A user who saved without the monitoring wrapper, with keys of their own that never took effect.
        String bobSettings = "{\"thanos\":{\"bucket\":\"other\",\"secretKey\":\"Bob-Unused-Key\"}}";
        originInstanceData = Map.of(
                "alice", data("kubeconfig.uploaded", "true", "view.settings.json", aliceSettings),
                "bob", data("view.settings.json", bobSettings),
                "carol", data("view.settings.json", "{\"kerberos\":{\"injectionMode\":\"WEBHOOK\"}}"));

        new KdpsDataMigrator(context()).migrateInstanceData();

        StringBuilder sharedRows = new StringBuilder();
        currentDataStore.findAll(KdpsSettingEntity.class, null)
                .forEach(r -> sharedRows.append(r.getId()).append('=').append(r.getValue()).append('\n'));
        for (String clear : List.of(access, secret, "Bob-Unused-Key")) {
            assertFalse(sharedRows.toString().contains(clear), sharedRows.toString());
            assertFalse(currentInstanceData.toString().contains(clear), currentInstanceData.toString());
            assertFalse(originInstanceData.toString().contains(clear), originInstanceData.toString());
        }
        assertTrue(currentInstanceData.get("alice").get("view.settings.json").contains("\"bucket\":\"metrics\""),
                "every other setting of the copies is kept");
        assertTrue(originInstanceData.get("bob").get("view.settings.json").contains("\"bucket\":\"other\""));
        assertEquals("{\"kerberos\":{\"injectionMode\":\"WEBHOOK\"}}",
                originInstanceData.get("carol").get("view.settings.json"), "a copy without credentials is left alone");

        // The view still gets the configuring user's credentials, decrypted.
        InstanceSettings settings = new InstanceSettings(currentDataStore, null);
        assertEquals(secret, settings.getSecret("monitoring.thanos.secretKey"));
        assertEquals(access, settings.getSecret("monitoring.thanos.accessKey"));
        assertTrue(settings.get("view.settings.json").contains("\"bucket\":\"metrics\""));
    }

    @Test
    void theUsersCopiesKeepTheirThanosCredentialsWhileNoSharedSettingsHoldThem() throws Exception {
        currentDataStore = new InMemoryDataStore() {
            @Override
            public void store(Object entity) {
                throw new IllegalStateException("database unavailable");
            }
        };
        String settings = "{\"monitoring\":{\"thanos\":{\"secretKey\":\"S3-Kept-Key\"}}}";
        originInstanceData = Map.of("alice", data("view.settings.json", settings));

        new KdpsDataMigrator(context()).migrateInstanceData();

        assertEquals(settings, currentInstanceData.get("alice").get("view.settings.json"),
                "the first read still carries the settings over from the user's own copy");
        assertEquals(settings, originInstanceData.get("alice").get("view.settings.json"));
    }
}
