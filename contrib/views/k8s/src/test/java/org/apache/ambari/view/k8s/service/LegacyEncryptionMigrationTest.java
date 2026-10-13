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

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.EncryptedEnvelope;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.apache.ambari.view.k8s.security.TestMasterKeys;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.utils.OwnerOnlyFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Once the master key is found, what an earlier version encrypted with its built-in passphrase is encrypted again with
 * it on a start, whether anything reads it or not: the passphrase is public, and its reading goes in a later release.
 */
class LegacyEncryptionMigrationTest {

    /** The key the test run gives this "Ambari server" (AMBARI_SECURITY_MASTER_KEY, see the surefire setup). */
    private static final String SERVER_KEY = "kdps-unit-test-master-key";

    @TempDir
    Path workDir;

    private ViewContext ctx;
    private InMemoryDataStore dataStore;
    private final EncryptionService masterKey = TestMasterKeys.encryptionWith(SERVER_KEY);
    private final Instant now = Instant.parse("2026-10-13T10:00:00Z");

    @BeforeEach
    void setUp() {
        ctx = mock(ViewContext.class);
        dataStore = new InMemoryDataStore();
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", workDir.toString()));
        when(ctx.getDataStore()).thenReturn(dataStore);
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
    }

    private InstanceSettings settings() {
        return new InstanceSettings(dataStore, masterKey);
    }

    private static String legacySecret(String plain) {
        return Base64.getEncoder().encodeToString(TestMasterKeys.legacyCiphertext(plain));
    }

    private static String legacyEnvelope(String plain) {
        return "{\"" + EncryptedEnvelope.FORMAT_KEY + "\":\"" + EncryptedEnvelope.FORMAT + "\",\""
                + EncryptedEnvelope.PAYLOAD_KEY + "\":\""
                + Base64.getEncoder().encodeToString(TestMasterKeys.legacyCiphertext(plain)) + "\"}";
    }

    /** Decrypts a stored secret with the master key alone: the built-in passphrase would say it must be redone. */
    private String withMasterKeyOnly(String stored) {
        EncryptionService.Decrypted decrypted = masterKey.decryptStored(Base64.getDecoder().decode(stored));
        assertFalse(decrypted.reencrypt(), "encrypted with the master key");
        return new String(decrypted.plain(), StandardCharsets.UTF_8);
    }

    @Test
    void secretsAndFilesNobodyReadsAreEncryptedAgainWithTheMasterKey() throws Exception {
        String[] keys = {"context.secret.cdp.cmPassword", "oidc.client-secret.op-1-step-2",
                "secprofile-fingerprint.key", "helm.repo.secret.idle"};
        for (String key : keys) {
            settings().put(key, legacySecret("plain-" + key));
        }
        settings().put("cluster.name", "not-a-secret");

        Path kubeconfig = Files.writeString(Files.createDirectories(workDir.resolve("kube")).resolve("project.enc"),
                new String(TestMasterKeys.legacyCiphertext("apiVersion: v1\nkind: Config\n"), StandardCharsets.UTF_8));
        settings().put(ViewConfigurationService.KUBECONFIG_PATH_KEY, kubeconfig.toString());

        Path params = OwnerOnlyFiles.directory(workDir.resolve("params")).resolve("op-1-params.json");
        Files.writeString(params, legacyEnvelope("{\"releaseName\":\"trino\"}"));
        Path values = OwnerOnlyFiles.directory(workDir.resolve("cache")).resolve("values-op-1-1.json");
        Files.writeString(values, legacyEnvelope("{\"s3SecretKey\":\"s3\"}"));
        CommandEntity inline = new CommandEntity();
        inline.setId("op-0");
        inline.setParamsJson(legacyEnvelope("{\"password\":\"inline\"}"));
        dataStore.store(inline);

        LegacyEncryptionMigration.Result result = new LegacyEncryptionMigration(ctx, dataStore, masterKey).run(now);

        assertTrue(result.done(), () -> "unreadable: " + result.unreadable());
        assertEquals(keys.length + 4, result.encryptedAgain());
        for (String key : keys) {
            assertEquals("plain-" + key, withMasterKeyOnly(settings().get(key)), key);
        }
        assertEquals("not-a-secret", settings().get("cluster.name"));
        assertEquals("apiVersion: v1\nkind: Config\n", new String(masterKey.decryptStored(
                Files.readAllBytes(kubeconfig)).plain(), StandardCharsets.UTF_8));
        assertFalse(masterKey.decryptStored(Files.readAllBytes(kubeconfig)).reencrypt());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(kubeconfig)));
        for (Path file : List.of(params, values)) {
            EncryptedEnvelope.Opened opened = EncryptedEnvelope.openStored(Files.readString(file));
            assertFalse(opened.reseal(), file.toString());
        }
        EncryptedEnvelope.Opened inlineNow = EncryptedEnvelope.openStored(
                dataStore.find(CommandEntity.class, "op-0").getParamsJson());
        assertEquals("{\"password\":\"inline\"}", inlineNow.text());
        assertFalse(inlineNow.reseal());
        assertEquals(now.toString(), settings().get(LegacyEncryptionMigration.MARKER_KEY));

        // Done once: a later start does not go through everything again.
        settings().put("helm.repo.secret.late", legacySecret("late"));
        assertEquals(0, new LegacyEncryptionMigration(ctx, dataStore, masterKey).run(now).encryptedAgain());
    }

    @Test
    void aKubeconfigInASingleByteCharsetIsEncryptedAgain() throws Exception {
        byte[] windows1252 = ("apiVersion: v1\nkind: Config\n# Soci\u00e9t\u00e9 project\n"
                + "clusters:\n- name: openshift\n  cluster:\n    server: https://api.example.com:6443\n"
                + "contexts: []\nusers: []\n").getBytes(java.nio.charset.Charset.forName("windows-1252"));
        Path kubeconfig = Files.write(Files.createDirectories(workDir.resolve("kube")).resolve("project.enc"),
                TestMasterKeys.legacyCiphertext(windows1252));
        settings().put(ViewConfigurationService.KUBECONFIG_PATH_KEY, kubeconfig.toString());

        LegacyEncryptionMigration.Result result = new LegacyEncryptionMigration(ctx, dataStore, masterKey).run(now);

        assertTrue(result.done(), () -> "unreadable: " + result.unreadable());
        assertEquals(1, result.encryptedAgain());
        EncryptionService.Decrypted now = masterKey.decryptStored(Files.readAllBytes(kubeconfig));
        assertArrayEquals(windows1252, now.plain());
        assertFalse(now.reencrypt());
    }

    @Test
    void whatAnotherKeyEncryptedIsReportedAndLookedAtAgainOnTheNextStart() {
        String other = Base64.getEncoder().encodeToString(TestMasterKeys.ciphertext("another-server-key", "pw"));
        settings().put("context.secret.cdp.remotePassword", other);
        settings().put("context.secret.cdp.cmPassword", legacySecret("cm"));

        LegacyEncryptionMigration.Result result = new LegacyEncryptionMigration(ctx, dataStore, masterKey).run(now);

        assertFalse(result.done());
        assertEquals(List.of("context.secret.cdp.remotePassword"), result.unreadable());
        assertEquals("cm", withMasterKeyOnly(settings().get("context.secret.cdp.cmPassword")));
        assertEquals(other, settings().get("context.secret.cdp.remotePassword"), "left as it was");
        assertNull(settings().get(LegacyEncryptionMigration.MARKER_KEY), "looked at again on the next start");
    }

    @Test
    void withoutAMasterKeyNothingIsTouched() {
        String legacy = legacySecret("cm");
        settings().put("context.secret.cdp.cmPassword", legacy);

        LegacyEncryptionMigration.Result result = new LegacyEncryptionMigration(ctx, dataStore,
                TestMasterKeys.encryptionWithoutKey()).run(now);

        assertFalse(result.done());
        assertEquals(0, result.encryptedAgain());
        assertEquals(legacy, settings().get("context.secret.cdp.cmPassword"));
        assertNull(settings().get(LegacyEncryptionMigration.MARKER_KEY));
    }
}
