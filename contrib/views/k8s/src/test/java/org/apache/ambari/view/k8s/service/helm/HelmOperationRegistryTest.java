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

package org.apache.ambari.view.k8s.service.helm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One Helm operation per release at a time, held until the native Helm call returns: a canceled operation keeps
 * the release until Helm is done, because Helm cannot be interrupted.
 */
class HelmOperationRegistryTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void aSecondOperationOnTheSameReleaseIsRefusedWhileTheFirstRuns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<String> first = HelmOperationRegistry.submit("apps", "busy-1", pool, () -> {
            started.countDown();
            release.await();
            return "done";
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        HelmOperationRegistry.ReleaseBusyException refused = assertThrows(HelmOperationRegistry.ReleaseBusyException.class,
                () -> HelmOperationRegistry.submit("apps", "busy-1", pool, () -> "second"));
        assertTrue(refused.getMessage().contains("apps/busy-1"), refused.getMessage());
        assertThrows(HelmOperationRegistry.ReleaseBusyException.class,
                () -> HelmOperationRegistry.run("apps", "busy-1", () -> "synchronous"));
        assertThrows(HelmOperationRegistry.ReleaseBusyException.class,
                () -> HelmOperationRegistry.requireIdle("apps", "busy-1"));

        // Another release is not affected.
        assertEquals("other", HelmOperationRegistry.run("apps", "busy-2", () -> "other"));

        release.countDown();
        assertEquals("done", first.get(5, TimeUnit.SECONDS));
        awaitIdle("apps", "busy-1");
        assertEquals("again", HelmOperationRegistry.run("apps", "busy-1", () -> "again"));
    }

    @Test
    void aCanceledOperationKeepsTheReleaseUntilHelmReturns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch helmReturns = new CountDownLatch(1);
        Future<String> op = HelmOperationRegistry.submit("apps", "canceled-1", pool, () -> {
            started.countDown();
            // Like the native Helm call: ignores the interrupt sent by cancel(true).
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                try {
                    if (helmReturns.await(1, TimeUnit.SECONDS)) return "returned";
                } catch (InterruptedException ignored) {
                    // keep going
                }
            }
            return "gave up";
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        op.cancel(true);
        assertTrue(op.isCancelled());
        assertTrue(HelmOperationRegistry.isRunning("apps", "canceled-1"), "Helm is still running after the cancel");
        assertThrows(HelmOperationRegistry.ReleaseBusyException.class,
                () -> HelmOperationRegistry.submit("apps", "canceled-1", pool, () -> "retry"));

        helmReturns.countDown();
        awaitIdle("apps", "canceled-1");
        Future<String> retry = HelmOperationRegistry.submit("apps", "canceled-1", pool, () -> "retry");
        assertEquals("retry", retry.get(5, TimeUnit.SECONDS));
    }

    @Test
    void anOperationCanceledBeforeItStartedFreesTheReleaseAtOnce() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        Future<String> op = HelmOperationRegistry.submit("apps", "queued-1", queued::add, () -> "never");
        assertTrue(HelmOperationRegistry.isRunning("apps", "queued-1"));

        op.cancel(true);
        assertFalse(HelmOperationRegistry.isRunning("apps", "queued-1"));
        queued.forEach(Runnable::run); // the executor getting to it later runs nothing
        assertFalse(HelmOperationRegistry.isRunning("apps", "queued-1"));
    }

    @Test
    void theOperationItselfMayCallBackForItsOwnRelease() throws Exception {
        Future<String> op = HelmOperationRegistry.submit("apps", "nested-1", pool, () -> {
            assertFalse(HelmOperationRegistry.isRunningElsewhere("apps", "nested-1"));
            HelmOperationRegistry.requireIdle("apps", "nested-1");
            return HelmOperationRegistry.run("apps", "nested-1", () -> "nested");
        });
        assertEquals("nested", op.get(5, TimeUnit.SECONDS));
        awaitIdle("apps", "nested-1");
    }

    @Test
    void aRejectedSubmissionDoesNotHoldTheRelease() {
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> HelmOperationRegistry.submit("apps", "rejected-1", r -> {
                    throw new java.util.concurrent.RejectedExecutionException("pool closed");
                }, () -> "never"));
        assertFalse(HelmOperationRegistry.isRunning("apps", "rejected-1"));
    }

    @Test
    void aFailingOperationFreesTheRelease() {
        assertThrows(IllegalStateException.class, () -> HelmOperationRegistry.run("apps", "failing-1", () -> {
            throw new IllegalStateException("helm failed");
        }));
        assertFalse(HelmOperationRegistry.isRunning("apps", "failing-1"));
    }

    /** Waits for a task that was not awaited (a canceled one) to return and free its release. */
    private static void awaitIdle(String namespace, String release) throws InterruptedException {
        for (int i = 0; i < 500 && HelmOperationRegistry.isRunning(namespace, release); i++) {
            Thread.sleep(10);
        }
        assertFalse(HelmOperationRegistry.isRunning(namespace, release), namespace + "/" + release + " still held");
    }
}
