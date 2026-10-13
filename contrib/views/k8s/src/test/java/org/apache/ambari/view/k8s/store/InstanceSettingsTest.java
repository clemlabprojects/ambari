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

package org.apache.ambari.view.k8s.store;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.apache.ambari.view.k8s.security.MasterKeyUnavailableException;
import org.apache.ambari.view.k8s.security.TestMasterKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The view instance's settings are one set for every user: what one user saves, every other user and every
 * background task reads. Values kept per user by earlier versions are carried over once.
 */
class InstanceSettingsTest {

    @TempDir
    Path workDir;

    PerUserViewContext view;

    @BeforeEach
    void setUp() {
        view = new PerUserViewContext("settings-test", workDir);
    }

    private InstanceSettings settings() {
        return new InstanceSettings(view.context);
    }

    @Test
    void aValueSavedByOneUserIsReadByEveryUserAndByBackgroundWork() {
        view.as("alice");
        settings().put("view.settings.json", "{\"kerberos\":{\"injectionMode\":\"WEBHOOK\"}}");

        assertEquals("{\"kerberos\":{\"injectionMode\":\"WEBHOOK\"}}", settings().get("view.settings.json"));
        view.as("ops1");
        assertEquals("{\"kerberos\":{\"injectionMode\":\"WEBHOOK\"}}", settings().get("view.settings.json"));
        view.as(null);
        assertEquals("{\"kerberos\":{\"injectionMode\":\"WEBHOOK\"}}", settings().get("view.settings.json"));
        assertTrue(view.instanceDataByUser.values().stream().allMatch(java.util.Map::isEmpty),
                "nothing is written to Ambari's per-user instance data any more");
    }

    @Test
    void aSettingOverwrittenByAnotherUserChangesForEveryone() {
        view.as("alice");
        settings().put("monitoring.repoId", "a");
        view.as("bob");
        settings().put("monitoring.repoId", "b");

        view.as("alice");
        assertEquals("b", settings().get("monitoring.repoId"));
    }

    @Test
    void secretsAreEncryptedAtRestAndReadableByEveryUser() {
        view.as("alice");
        settings().putSecret("helm.repo.secret.harbor", "registry-token");

        String stored = view.dataStore.find(KdpsSettingEntity.class, "helm.repo.secret.harbor").getValue();
        assertNotNull(stored);
        assertFalse(stored.contains("registry-token"), "the stored value is encrypted");
        view.as("bob");
        assertEquals("registry-token", settings().getSecret("helm.repo.secret.harbor"));
        assertNull(settings().getSecret("helm.repo.secret.unknown"));
    }

    @Test
    void nullRemovesTheSetting() {
        view.as("alice");
        settings().put("kubeconfig.context", "prod");
        settings().put("kubeconfig.context", null);

        assertNull(settings().get("kubeconfig.context"));
    }

    @Test
    void aValueTooLongForTheDataStoreIsRefusedWithoutEchoingIt() {
        view.as("alice");
        String tooLong = "s".repeat(InstanceSettings.MAX_VALUE_LENGTH + 1);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> settings().put("k", tooLong));
        assertFalse(e.getMessage().contains(tooLong));
        assertTrue(e.getMessage().contains("too long"), e.getMessage());
        assertNull(settings().get("k"));
    }

    @Test
    void aContextWithoutAnInstanceHasNoSettingsAndCannotSaveAny() {
        InstanceSettings detached = new InstanceSettings(mock(ViewContext.class));

        assertNull(detached.get("kubeconfig.uploaded"));
        assertThrows(IllegalStateException.class, () -> detached.put("kubeconfig.uploaded", "true"));
    }

    // ----- carry-over of the values earlier versions kept per user -----

    @Test
    void aValueTheCallerKeptForThemselvesIsSharedOnceAndThenReadFromTheSharedSettings() {
        view.instanceDataOf("alice").put("helm.repo.secret.harbor", "enc-token");
        view.instanceDataOf("alice").put("context.ext.rangerAdminPassword", "enc-ranger");

        view.as("alice");
        assertEquals("enc-token", settings().get("helm.repo.secret.harbor"));
        assertNotNull(view.dataStore.find(KdpsSettingEntity.class, "context.ext.rangerAdminPassword"),
                "the caller's other values were shared at the same time");

        view.as("ops1");
        assertEquals("enc-token", settings().get("helm.repo.secret.harbor"));
        assertEquals("enc-ranger", settings().get("context.ext.rangerAdminPassword"));
    }

    @Test
    void aSharedValueWinsOverTheCallersOwnOldValue() {
        view.as("alice");
        settings().put("view.settings.json", "{\"new\":true}");
        view.instanceDataOf("bob").put("view.settings.json", "{\"old\":true}");

        view.as("bob");
        assertEquals("{\"new\":true}", settings().get("view.settings.json"));
    }

    @Test
    void aRemovedSettingIsNotBroughtBackFromAUsersOwnOldValue() {
        view.instanceDataOf("bob").put("monitoring.prometheus.url", "http://old-prometheus:9090");
        view.as("alice");
        settings().put("monitoring.prometheus.url", "http://prometheus:9090");
        settings().remove("monitoring.prometheus.url");

        view.as("bob");
        assertNull(settings().get("monitoring.prometheus.url"));
    }

    @Test
    void theClusterConnectionIsOnlyTakenFromAUserWhoHadConfiguredTheView() {
        view.instanceDataOf("bob").put("kubeconfig.uploaded", "false");
        view.instanceDataOf("bob").put("kubeconfig.context", "bobs-cluster");
        view.instanceDataOf("bob").put("helm.repo.secret.bob", "enc-bob");

        view.as("bob");
        assertNull(settings().get("kubeconfig.context"), "bob never configured the view");
        assertNull(settings().get("kubeconfig.uploaded"));
        assertEquals("enc-bob", settings().get("helm.repo.secret.bob"), "his other values are still carried over");
        assertNull(view.dataStore.find(KdpsSettingEntity.class, "kubeconfig.context"));
    }

    @Test
    void theClusterConnectionIsTakenAsAWholeAndNeverMixedWithAnotherUsersOne() {
        view.instanceDataOf("alice").put("kubeconfig.uploaded", "true");
        view.instanceDataOf("alice").put("auth.mode", "openshift-login");
        view.instanceDataOf("alice").put("openshift.apiUrl", "https://api.ocp:6443");
        view.instanceDataOf("alice").put("openshift.username", "kdps");
        view.instanceDataOf("bob").put("kubeconfig.uploaded", "true");
        view.instanceDataOf("bob").put("kubeconfig.path", "/var/lib/kdps/kubeconfig.yaml.enc");
        view.instanceDataOf("bob").put("kubeconfig.context", "bobs-cluster");

        view.as("alice");
        assertEquals("true", settings().get("kubeconfig.uploaded"));
        assertEquals("https://api.ocp:6443", settings().get("openshift.apiUrl"), "shared with the flag, in one go");

        view.as("bob");
        assertNull(settings().get("kubeconfig.context"), "a connection is already shared: bob's is not mixed in");
        assertNull(settings().get("kubeconfig.path"));
        assertEquals("openshift-login", settings().get("auth.mode"));
    }

    @Test
    void ambarisOwnUpgradeBookkeepingIsNotCarriedOver() {
        view.instanceDataOf("upgrade").put("upgradedFrom", "1.0.0.8");

        view.as("upgrade");
        assertNull(settings().get("upgradedFrom"));
    }

    @Test
    void aSecretEncryptedWithTheBuiltInPassphraseOfEarlierVersionsIsEncryptedAgainWithTheMasterKeyOnFirstRead() {
        EncryptionService masterKey = TestMasterKeys.encryptionWith("a-master-key");
        InMemoryDataStore store = new InMemoryDataStore();
        InstanceSettings settings = new InstanceSettings(store, masterKey);
        String legacy = Base64.getEncoder().encodeToString(TestMasterKeys.legacyCiphertext("ranger-admin-pw"));
        settings.put("context.secret.ext.rangerAdminPassword", legacy);

        assertEquals("ranger-admin-pw", settings.getSecret("context.secret.ext.rangerAdminPassword"));

        String stored = settings.get("context.secret.ext.rangerAdminPassword");
        assertNotEquals(legacy, stored);
        EncryptionService.Decrypted now = masterKey.decryptStored(Base64.getDecoder().decode(stored));
        assertEquals("ranger-admin-pw", new String(now.plain(), StandardCharsets.UTF_8));
        assertFalse(now.reencrypt());
        assertThrows(IllegalStateException.class, () -> TestMasterKeys.encryptionWith("another-master-key")
                .decrypt(Base64.getDecoder().decode(stored)), "no longer readable with the built-in passphrase");
    }

    @Test
    void withoutAMasterKeyNoSecretIsSaved() {
        InMemoryDataStore store = new InMemoryDataStore();
        InstanceSettings settings = new InstanceSettings(store, TestMasterKeys.encryptionWithoutKey());

        assertThrows(MasterKeyUnavailableException.class, () -> settings.putSecret("helm.repo.secret.harbor", "token"));
        assertThrows(MasterKeyUnavailableException.class, settings::requireMasterKey);

        assertNull(settings.get("helm.repo.secret.harbor"));
    }
}
