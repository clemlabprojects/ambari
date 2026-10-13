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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.EncryptedEnvelope;
import org.apache.ambari.view.k8s.security.TestMasterKeys;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The parameters of a command are kept in a file only the account running Ambari can read, encrypted, and without the
 * secrets of the wizard's form that no step reads; the steps read them back as they were planned.
 */
class CommandParamsStoreTest {

    private static final String PROFILE_PASSWORD = "Bind-Pass-751";
    private static final String FORM_PASSWORD = "Flask-Secret-751";
    private static final String PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----\nMIIE-751\n-----END PRIVATE KEY-----";

    @TempDir
    Path workDir;

    private ViewContext ctx;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        ctx = mock(ViewContext.class);
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", workDir.toString()));
    }

    /** The parameters of a Superset deploy: the security profile's password for the chart, and the wizard's form. */
    private String supersetDeployParams(boolean gitOps) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("superset", Map.of("secretKey", FORM_PASSWORD));
        form.put("ingress", Map.of("host", "superset.example.org", "tlsMode", "uploadLeaf",
                "tlsUpload", Map.of("key", PRIVATE_KEY)));
        form.put("platformContextId", "default");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("releaseName", "superset");
        params.put("serviceKey", "SUPERSET");
        params.put("timeoutSeconds", 600);
        params.put("_ov", Map.of("global.security.auth.ldap.bindPassword",
                HelmSetValues.textIfSecret("ldap.bindPassword", PROFILE_PASSWORD)));
        params.put("formValues", form);
        if (gitOps) {
            params.put(CommandService.GITOPS_RELEASE_PARAM, Map.of());
        }
        return gson.toJson(params);
    }

    private Map<String, Object> parsed(String json) {
        return gson.fromJson(json, new TypeToken<LinkedHashMap<String, Object>>() {}.getType());
    }

    @Test
    void theFileIsOwnerOnlyEncryptedAndReadBackAsPlanned() throws Exception {
        String stored = CommandParamsStore.persist(ctx, "cmd-1", supersetDeployParams(false));

        assertTrue(CommandParamsStore.isFileReference(stored), stored);
        Path file = CommandParamsStore.fileOf(stored);
        assertEquals(workDir.resolve("params").resolve("cmd-1-params.json"), file);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
        String onDisk = Files.readString(file, StandardCharsets.UTF_8);
        for (String secret : new String[]{PROFILE_PASSWORD, FORM_PASSWORD, "MIIE-751", "superset.example.org"}) {
            assertFalse(onDisk.contains(secret), secret + " on disk");
        }

        Map<String, Object> read = parsed(CommandUtils.resolveParamsPayload(stored));
        assertTrue(String.valueOf(read.get("_ov")).contains(PROFILE_PASSWORD), "the Helm step still gets it: " + read);
        assertEquals(600.0, read.get("timeoutSeconds"));
        @SuppressWarnings("unchecked")
        Map<String, Object> form = (Map<String, Object>) read.get("formValues");
        assertEquals("default", form.get("platformContextId"));
        assertEquals(Map.of("host", "superset.example.org", "tlsMode", "uploadLeaf", "tlsUpload", Map.of()), form.get("ingress"));
        assertEquals(Map.of(), form.get("superset"), "no step reads the form's secret key: it is not kept");
    }

    @Test
    void numbersKeepTheirTextWhenASecretIsLeftOut() {
        String kept = CommandParamsStore.withoutUnreadSecrets(supersetDeployParams(false));

        assertTrue(kept.contains("\"timeoutSeconds\":600,"), kept);
        assertFalse(kept.contains(FORM_PASSWORD) || kept.contains("MIIE-751"), kept);
    }

    @Test
    void aGitOpsDeployKeepsThePasswordsItsCommitLooksForButNotThePastedKey() {
        Map<String, Object> read = parsed(CommandParamsStore.withoutUnreadSecrets(supersetDeployParams(true)));

        String form = String.valueOf(read.get("formValues"));
        assertTrue(form.contains(FORM_PASSWORD), form);
        assertFalse(form.contains("MIIE-751"), form);
    }

    @Test
    void aPrivateKeyIsLeftOutOfTheFormOfAnyService() {
        String params = gson.toJson(Map.of("serviceKey", "UNKNOWN", "formValues",
                Map.of("custom", Map.of("pem", PRIVATE_KEY), "name", "x")));

        String kept = CommandParamsStore.withoutUnreadSecrets(params);

        assertFalse(kept.contains("MIIE-751"), kept);
        assertTrue(kept.contains("\"name\":\"x\""), kept);
    }

    @Test
    void aFileWrittenWithWiderPermissionsIsReplacedOwnerOnly() throws Exception {
        Path dir = Files.createDirectories(workDir.resolve("params"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path legacy = dir.resolve("cmd-2-params.json");
        Files.writeString(legacy, "{\"releaseName\":\"trino\"}");
        Files.setPosixFilePermissions(legacy, PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals("{\"releaseName\":\"trino\"}", CommandParamsStore.read("@@file:" + legacy),
                "a file written in clear by an earlier version is read as it is");

        String params = "{\"releaseName\":\"trino\",\"password\":\"Trino-Pass-751\"}";

        String stored = CommandParamsStore.persist(ctx, "cmd-2", params);

        assertEquals("@@file:" + legacy, stored);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(legacy)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        assertFalse(Files.readString(legacy).contains("Trino-Pass-751"), "encrypted on disk");
        assertEquals(params, CommandParamsStore.read(stored));
    }

    @Test
    void parametersThatCannotBeWrittenToAFileAreKeptEncryptedInline() throws Exception {
        Files.writeString(workDir.resolve("params"), "not a directory");

        String stored = CommandParamsStore.persist(ctx, "cmd-3", supersetDeployParams(false));

        assertFalse(CommandParamsStore.isFileReference(stored));
        assertFalse(stored.contains(PROFILE_PASSWORD), stored);
        assertTrue(CommandParamsStore.read(stored).contains(PROFILE_PASSWORD));
    }

    @Test
    void parametersEncryptedInlineAreNotEncryptedTwice() throws Exception {
        Files.writeString(workDir.resolve("params"), "not a directory");
        String stored = CommandParamsStore.persist(ctx, "cmd-4", "{\"releaseName\":\"trino\"}");

        String again = CommandParamsStore.persist(ctx, "cmd-4", stored);

        assertEquals("{\"releaseName\":\"trino\"}", CommandParamsStore.read(again));
    }

    @Test
    void aMissingFileReadsAsNoParameters() {
        assertNull(CommandParamsStore.read("@@file:" + workDir.resolve("params/gone-params.json")));
        assertNull(CommandParamsStore.read(null));
    }

    @Test
    void onlyTheParameterFileOfTheCommandIsDeleted() throws Exception {
        String stored = CommandParamsStore.persist(ctx, "cmd-5", "{}");
        Path other = Files.writeString(workDir.resolve("kubeconfig.yaml.enc"), "x");

        assertFalse(CommandParamsStore.deleteFile("cmd-5", "@@file:" + other), "not a parameter file of cmd-5");
        assertTrue(Files.exists(other));
        assertTrue(CommandParamsStore.deleteFile("cmd-5", stored));
        assertFalse(Files.exists(CommandParamsStore.fileOf(stored)));
    }

    @Test
    void aFileSealedWithTheBuiltInPassphraseOfEarlierVersionsIsSealedAgainWhenRead() throws Exception {
        // Sealing goes through the master key of this server: AMBARI_SECURITY_MASTER_KEY of the test run.
        Path dir = Files.createDirectories(workDir.resolve("params"));
        String params = "{\"releaseName\":\"superset\"}";
        String legacy = "{\"" + EncryptedEnvelope.FORMAT_KEY + "\":\"" + EncryptedEnvelope.FORMAT + "\",\""
                + EncryptedEnvelope.PAYLOAD_KEY + "\":\""
                + Base64.getEncoder().encodeToString(TestMasterKeys.legacyCiphertext(params)) + "\"}";
        Path file = Files.writeString(dir.resolve("cmd-0" + CommandParamsStore.FILE_SUFFIX), legacy);

        assertEquals(params, CommandParamsStore.read(CommandParamsStore.FILE_REFERENCE + file));

        String now = Files.readString(file);
        assertNotEquals(legacy, now);
        EncryptedEnvelope.Opened reread = EncryptedEnvelope.openStored(now);
        assertEquals(params, reread.text());
        assertFalse(reread.reseal());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
}
