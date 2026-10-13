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
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.security.EncryptedEnvelope;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.utils.OwnerOnlyFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The parameter and values files of an operation, which hold its secrets, go when nothing can use them any more: at
 * once when it succeeded, after a retention period when it failed or was canceled (Resume needs them meanwhile).
 * Files written in clear by an earlier version are encrypted on the first start.
 */
class OperationFilesRetentionTest {

    private static final String SECRET = "S3-Secret-751";

    @TempDir
    Path workDir;

    private final Gson gson = new Gson();
    private final Map<String, String> ambariProperties = new ConcurrentHashMap<>();
    private ViewContext ctx;
    private InMemoryDataStore dataStore;
    private OperationFilesRetention retention;
    private final Instant now = Instant.parse("2026-10-12T10:00:00Z");

    @BeforeEach
    void setUp() {
        ctx = mock(ViewContext.class);
        dataStore = new InMemoryDataStore();
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", workDir.toString()));
        when(ctx.getDataStore()).thenReturn(dataStore);
        when(ctx.getAmbariProperty(anyString())).thenAnswer(inv -> ambariProperties.get(inv.getArgument(0, String.class)));
        retention = new OperationFilesRetention(ctx, dataStore);
    }

    /** An operation that ended {@code endedAt}, its steps in the given states, its chart values cached. */
    private String operation(String id, String rootState, Instant endedAt, String... stepStates) throws Exception {
        Path cache = OwnerOnlyFiles.directory(workDir.resolve("cache"));
        Path values = cache.resolve("values-" + id + "-1.json");
        OwnerOnlyFiles.write(values, EncryptedEnvelope.sealToJson("{\"s3SecretKey\":\"" + SECRET + "\"}"));
        String params = gson.toJson(Map.of("releaseName", id, "valuesPath", values.toString(), "password", SECRET));
        List<String> steps = new ArrayList<>();
        for (int i = 0; i < stepStates.length; i++) {
            String stepId = id + "-step-" + i;
            command(stepId, null, params, stepStates[i], endedAt);
            steps.add(stepId);
        }
        command(id, gson.toJson(steps), params, rootState, endedAt);
        return id;
    }

    private void command(String id, String childListJson, String params, String state, Instant at) {
        CommandStatusEntity st = new CommandStatusEntity();
        st.setId(id + "-status");
        st.setState(state);
        st.setCreatedAt(at.toString());
        st.setUpdatedAt(at.toString());
        dataStore.store(st);
        CommandEntity cmd = new CommandEntity();
        cmd.setId(id);
        cmd.setType("K8S_MANAGER_RELEASE_DEPLOY");
        cmd.setCommandStatusId(st.getId());
        cmd.setChildListJson(childListJson);
        cmd.setParamsJson(CommandParamsStore.persist(ctx, id, params));
        dataStore.store(cmd);
    }

    private String stored(String id) {
        return dataStore.find(CommandEntity.class, id).getParamsJson();
    }

    private Path paramsFile(String id) {
        return workDir.resolve("params").resolve(id + "-params.json");
    }

    private Path valuesFile(String id) {
        return workDir.resolve("cache").resolve("values-" + id + "-1.json");
    }

    @Test
    void anOperationWhoseStepsAllSucceededLosesItsFilesWhenItEnds() throws Exception {
        String id = operation("op-ok", "SUCCEEDED", now, "SUCCEEDED", "SUCCEEDED");

        assertTrue(retention.purgeIfSucceeded(id));

        for (String cmd : List.of(id, id + "-step-0", id + "-step-1")) {
            assertFalse(Files.exists(paramsFile(cmd)), cmd);
            assertEquals(CommandParamsStore.REMOVED, stored(cmd));
            assertTrue(CommandParamsStore.wasRemoved(CommandParamsStore.read(stored(cmd))));
        }
        assertFalse(Files.exists(valuesFile(id)));
    }

    @Test
    void aFailedOrCanceledOperationKeepsItsFilesForResume() throws Exception {
        String failed = operation("op-failed", "FAILED", now, "SUCCEEDED", "FAILED");
        // A GitOps change waiting for a merge: the operation succeeded, the steps that need the change were not run.
        String waiting = operation("op-waiting", "SUCCEEDED", now, "SUCCEEDED", "CANCELED");

        assertFalse(retention.purgeIfSucceeded(failed));
        assertFalse(retention.purgeIfSucceeded(waiting));

        for (String id : List.of(failed, waiting)) {
            assertTrue(Files.exists(paramsFile(id)) && Files.exists(paramsFile(id + "-step-1")), id);
            assertTrue(Files.exists(valuesFile(id)), id);
            assertTrue(CommandParamsStore.read(stored(id)).contains(SECRET), id);
        }
    }

    @Test
    void theSweepPurgesWhatSucceededAndWhatFailedLongerAgoThanTheRetention() throws Exception {
        String succeeded = operation("op-ok", "SUCCEEDED", now.minus(Duration.ofDays(400)), "SUCCEEDED");
        String old = operation("op-old", "FAILED", now.minus(Duration.ofDays(31)), "FAILED");
        String recent = operation("op-recent", "CANCELED", now.minus(Duration.ofDays(1)), "CANCELED");
        String running = operation("op-running", "RUNNING", now.minus(Duration.ofDays(90)), "SUCCEEDED", "RUNNING");

        OperationFilesRetention.SweepResult result = retention.sweep(now);

        assertEquals(2, result.operationsPurged());
        for (String purged : List.of(succeeded, old)) {
            assertFalse(Files.exists(paramsFile(purged)) || Files.exists(valuesFile(purged)), purged);
            assertEquals(CommandParamsStore.REMOVED, stored(purged));
        }
        for (String kept : List.of(recent, running)) {
            assertTrue(Files.exists(paramsFile(kept)) && Files.exists(valuesFile(kept)), kept);
            assertTrue(CommandParamsStore.read(stored(kept + "-step-0")).contains(SECRET), kept);
        }
    }

    @Test
    void theOidcClientSecretAFailedOperationKeptGoesWithItsFiles() throws Exception {
        String old = operation("op-old", "FAILED", now.minus(Duration.ofDays(31)), "SUCCEEDED", "FAILED");
        String recent = operation("op-recent", "FAILED", now.minus(Duration.ofDays(1)), "SUCCEEDED", "FAILED");
        OidcClientSecrets secrets = new OidcClientSecrets(new InstanceSettings(ctx));
        for (String id : List.of(old, recent)) {
            // Its Keycloak registration step succeeded; the step writing the secret did not.
            CommandEntity register = dataStore.find(CommandEntity.class, id + "-step-0");
            register.setType(CommandType.OIDC_REGISTER_CLIENT.name());
            dataStore.store(register);
            secrets.keep(id + "-step-0", SECRET);
        }

        retention.sweep(now);

        assertNull(secrets.read(old + "-step-0"), "no step of the operation can run again to write it");
        assertEquals(SECRET, secrets.read(recent + "-step-0"), "still kept for Resume");
    }

    @Test
    void beyondTheCountLimitOnlyTheMostRecentFailedOperationsKeepTheirFiles() throws Exception {
        ambariProperties.put(OperationFilesRetention.RETENTION_COUNT_PROPERTY, "1");
        String older = operation("op-older", "FAILED", now.minus(Duration.ofHours(5)), "FAILED");
        String newer = operation("op-newer", "FAILED", now.minus(Duration.ofHours(1)), "FAILED");

        retention.sweep(now);

        assertFalse(Files.exists(paramsFile(older)));
        assertTrue(Files.exists(paramsFile(newer)));
    }

    @Test
    void theFirstSweepEncryptsWhatAnEarlierVersionWroteInClear() throws Exception {
        String id = operation("op-legacy", "FAILED", now.minus(Duration.ofHours(2)), "FAILED");
        // As an earlier version left them: in clear, readable by every local account.
        String plain = gson.toJson(Map.of("releaseName", id, "valuesPath", valuesFile(id).toString(), "password", SECRET));
        for (Path file : List.of(paramsFile(id), paramsFile(id + "-step-0"))) {
            Files.writeString(file, plain);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }
        Files.writeString(valuesFile(id), "{\"s3SecretKey\":\"" + SECRET + "\"}");
        Files.setPosixFilePermissions(valuesFile(id), PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(workDir.resolve("params"), PosixFilePermissions.fromString("rwxr-xr-x"));
        // Kept in the database, in clear, by a version older still.
        CommandEntity inline = dataStore.find(CommandEntity.class, id + "-step-0");
        Files.delete(paramsFile(id + "-step-0"));
        inline.setParamsJson(plain);
        dataStore.store(inline);

        OperationFilesRetention.SweepResult first = retention.sweep(now);

        assertEquals(3, first.filesEncrypted());
        for (Path file : List.of(paramsFile(id), paramsFile(id + "-step-0"), valuesFile(id))) {
            assertFalse(Files.readString(file).contains(SECRET), file + " is encrypted");
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), file.toString());
        }
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(workDir.resolve("params"))));
        assertEquals("@@file:" + paramsFile(id + "-step-0"), stored(id + "-step-0"), "moved out of the database");
        assertTrue(CommandParamsStore.read(stored(id + "-step-0")).contains(SECRET), "still read by Resume");
        assertTrue(Files.exists(workDir.resolve(OperationFilesRetention.ENCRYPTED_MARKER)));

        assertEquals(0, retention.sweep(now).filesEncrypted(), "only once");
    }

    @Test
    void filesNoOperationRefersToAreDeletedAfterAGracePeriod() throws Exception {
        String kept = operation("op-kept", "FAILED", now, "FAILED");
        Path cache = workDir.resolve("cache");
        // The values of a deploy refused after they were cached, and a file of a deleted operation.
        Path refused = Files.writeString(cache.resolve("values-refused-1.json"), SECRET);
        Path orphan = Files.writeString(workDir.resolve("params").resolve("gone-params.json"), SECRET);
        Path planning = Files.writeString(cache.resolve("values-planning-1.json"), SECRET);
        FileTime twoHoursAgo = FileTime.from(now.minus(Duration.ofHours(2)));
        for (Path old : List.of(refused, orphan, paramsFile(kept), valuesFile(kept))) {
            Files.setLastModifiedTime(old, twoHoursAgo);
        }
        Files.setLastModifiedTime(planning, FileTime.from(now.minus(Duration.ofMinutes(5))));

        retention.sweep(now);

        assertFalse(Files.exists(refused) || Files.exists(orphan));
        assertTrue(Files.exists(planning), "may belong to an operation being planned");
        assertTrue(Files.exists(paramsFile(kept)) && Files.exists(valuesFile(kept)));
    }
}
