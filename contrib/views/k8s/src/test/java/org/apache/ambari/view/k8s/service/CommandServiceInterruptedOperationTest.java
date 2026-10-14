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

import com.google.gson.Gson;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An operation whose orchestration is interrupted by a database error or an unexpected internal error ends FAILED, so
 * that it can be resumed, instead of showing RUNNING for good.
 */
class CommandServiceInterruptedOperationTest {

    private static final String ROOT = "op-1";
    private static final String STEP = "op-1-step";

    private final Gson gson = new Gson();
    private FlakyDataStore dataStore;
    private CommandService service;

    /** A data store whose reads and writes can be made to fail. */
    private static final class FlakyDataStore extends InMemoryDataStore {
        volatile Supplier<Throwable> findFailure;
        volatile String failFindOf;
        final AtomicInteger failingStores = new AtomicInteger();

        /** The DataStore contract throws a checked PersistenceException the in-memory store does not declare. */
        @SuppressWarnings("unchecked")
        private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
            throw (E) t;
        }

        @Override
        public synchronized <T> T find(Class<T> clazz, Object primaryKey) {
            if (findFailure != null && primaryKey.equals(failFindOf)) {
                Throwable t = findFailure.get();
                if (t != null) FlakyDataStore.<RuntimeException>sneakyThrow(t);
            }
            return super.find(clazz, primaryKey);
        }

        @Override
        public synchronized void store(Object entity) {
            if (entity instanceof CommandStatusEntity && failingStores.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                FlakyDataStore.<RuntimeException>sneakyThrow(new PersistenceException("database is down"));
            }
            super.store(entity);
        }
    }

    /** Steps run with slf4j's MDC, which needs the same loading work-around as in {@link CommandServiceHelmStepTest}. */
    @BeforeAll
    static void loadMdc() {
        String javaVersion = System.getProperty("java.version");
        System.setProperty("java.version", "1.8");
        try {
            org.slf4j.MDC.getMDCAdapter();
        } finally {
            System.setProperty("java.version", javaVersion);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Path workDir = Files.createTempDirectory("kdps-interrupted");
        ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getInstanceName()).thenReturn("interrupted-" + Long.toHexString(System.nanoTime()));
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", workDir.toString()));
        dataStore = new FlakyDataStore();
        when(ctx.getDataStore()).thenReturn(dataStore);
        KubernetesService k8s = mock(KubernetesService.class, RETURNS_DEEP_STUBS);
        service = new CommandService(ctx, new HelmService(ctx, mock(org.apache.ambari.view.k8s.service.helm.HelmClient.class),
                () -> mock(NamespaceScope.class)), k8s);
        service.startupSweep.get(30, TimeUnit.SECONDS);
        service.terminalWriteBackoffMillis = 1;

        dataStore.store(command(ROOT, CommandType.K8S_MANAGER_RELEASE_DEPLOY, List.of(STEP)));
        dataStore.store(status(ROOT, CommandState.RUNNING));
        dataStore.store(command(STEP, CommandType.HELM_DEPLOY, null));
        dataStore.store(status(STEP, CommandState.RUNNING));
    }

    /** Retries queued by one test must not run during the next. */
    @AfterEach
    void stopService() {
        service.shutdown();
    }

    @Test
    void databaseErrorWhileScanningStepsFailsTheOperationOnceTheRetriesAreSpent() {
        dataStore.failFindOf = STEP;
        dataStore.findFailure = () -> new PersistenceException("connection reset");

        // Each failed read reschedules the scan; only the last one gives up. The scheduled retries are not awaited.
        for (int i = 0; i < CommandService.MAX_DB_READ_ATTEMPTS; i++) {
            assertEquals(CommandState.RUNNING.name(), stateOf(ROOT), "still retrying after " + i + " failures");
            service.runNextStep(ROOT);
        }

        assertEquals(CommandState.FAILED.name(), stateOf(ROOT));
        assertTrue(statusOf(ROOT).getMessage().contains("Resume"), statusOf(ROOT).getMessage());
    }

    @Test
    void operationIsStillFailedWhenTheDatabaseIsUnreadableAtTheFirstTryToMarkItFailed() throws Exception {
        // Called directly so that no other scheduled scan can move the operation on: only the retry can fail it.
        AtomicInteger reads = new AtomicInteger();
        dataStore.failFindOf = ROOT;
        dataStore.findFailure = () -> reads.incrementAndGet() <= 1 ? new PersistenceException("connection refused") : null;

        service.failRootPersistently(ROOT, new IllegalStateException("boom"), 1);
        assertEquals(CommandState.RUNNING.name(), stateOf(ROOT), "nothing could be written yet");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && !CommandState.FAILED.name().equals(stateOf(ROOT))) {
            Thread.sleep(100);
        }
        assertEquals(CommandState.FAILED.name(), stateOf(ROOT));
        assertTrue(statusOf(ROOT).getMessage().contains("Stopped by an internal error"), statusOf(ROOT).getMessage());
    }

    @Test
    void errorEscapingTheOrchestratorFailsTheOperation() {
        // Only the first read fails: the Error leaves the orchestrator, then the operation can be read to be failed.
        AtomicInteger reads = new AtomicInteger();
        dataStore.failFindOf = STEP;
        dataStore.findFailure = () -> reads.getAndIncrement() == 0 ? new NoClassDefFoundError("org/example/Missing") : null;

        service.runNextStep(ROOT);

        assertEquals(CommandState.FAILED.name(), stateOf(ROOT));
        assertEquals(CommandState.FAILED.name(), stateOf(STEP));
    }

    @Test
    void finalStateWriteIsTriedAgainAfterADatabaseError() {
        dataStore.store(status(STEP, CommandState.SUCCEEDED));
        dataStore.failingStores.set(2);

        service.runNextStep(ROOT);

        assertEquals(CommandState.SUCCEEDED.name(), stateOf(ROOT));
    }

    @Test
    void finalStateIsWrittenByALaterScanWhenTheDatabaseStaysDownForAllTries() {
        dataStore.store(status(STEP, CommandState.SUCCEEDED));
        dataStore.failingStores.set(4);

        service.runNextStep(ROOT);
        assertEquals(CommandState.RUNNING.name(), stateOf(ROOT), "the write could not be made yet");

        // The scan that was scheduled again finds every step done and writes the final state.
        service.runNextStep(ROOT);
        assertEquals(CommandState.SUCCEEDED.name(), stateOf(ROOT));
    }

    private CommandEntity command(String id, CommandType type, List<String> children) {
        CommandEntity c = new CommandEntity();
        c.setId(id);
        c.setType(type.name());
        c.setTitle("Step " + id);
        c.setCommandStatusId(id + "-status");
        c.setParamsJson(gson.toJson(Map.of()));
        if (children != null) c.setChildListJson(gson.toJson(children));
        return c;
    }

    private static CommandStatusEntity status(String commandId, CommandState state) {
        CommandStatusEntity s = new CommandStatusEntity();
        s.setId(commandId + "-status");
        s.setState(state.name());
        s.setAttempt(0);
        return s;
    }

    private CommandStatusEntity statusOf(String commandId) {
        return dataStore.find(CommandStatusEntity.class, commandId + "-status");
    }

    private String stateOf(String commandId) {
        return statusOf(commandId).getState();
    }
}
