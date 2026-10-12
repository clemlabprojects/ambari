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
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.KdpsSettingEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
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

    private ViewDataMigrationContext context() {
        return (ViewDataMigrationContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ViewDataMigrationContext.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "copyAllObjects":
                            calls.add("copy:" + ((Class<?>) args[1]).getSimpleName());
                            assertEquals(3, args.length, "the converter overload must be used");
                            assertInstanceOf(AccessorCopyConverter.class, args[2]);
                            assertTrue(args[2] instanceof EntityConverter);
                            return null;
                        case "copyAllInstanceData":
                            calls.add("instanceData");
                            return null;
                        case "getOriginInstanceDataByUser":
                            return originInstanceData;
                        case "getCurrentDataStore":
                            return currentDataStore;
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
}
