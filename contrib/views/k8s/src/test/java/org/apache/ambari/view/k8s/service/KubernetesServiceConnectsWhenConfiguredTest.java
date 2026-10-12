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

import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.ambari.view.k8s.model.ConnectionHealth;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The view instance's Kubernetes service is built once, by whichever request comes first after an Ambari restart.
 * When that request could not see a configuration, the service connects as soon as the instance is configured,
 * for every user, instead of staying unconfigured until someone saves the configuration again.
 */
class KubernetesServiceConnectsWhenConfiguredTest {

    @TempDir
    Path workDir;

    PerUserViewContext view;
    Path kubeconfigFile;
    final KubernetesClient built = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
    final AtomicInteger builds = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        view = new PerUserViewContext("connect-test", workDir);
        kubeconfigFile = Files.writeString(workDir.resolve("kubeconfig.yaml.enc"), "encrypted");
    }

    /** The service as built by the first request; connecting from the saved configuration yields {@code client}. */
    private KubernetesService serviceBuiltBy(String user, KubernetesClient client) {
        return serviceConnectingWith(user, () -> client);
    }

    /** The service as built by the first request; each attempt to connect from the saved configuration runs {@code attempt}. */
    private KubernetesService serviceConnectingWith(String user, Supplier<KubernetesClient> attempt) {
        view.as(user);
        KubernetesService svc = new KubernetesService(view.context) {
            @Override
            KubernetesClient buildClientFromSavedConfiguration() {
                builds.incrementAndGet();
                return attempt.get();
            }
        };
        svc.retiredClientGraceSeconds = 0;
        return svc;
    }

    /** An administrator saves the configuration; the following calls are ops1's. */
    private void configuredByAlice() {
        view.as("alice");
        new ViewConfigurationService(view.context).saveKubeconfigPath(kubeconfigFile.toString());
        view.as("ops1");
    }

    /**
     * Sends {@code count} requests at once, as the dashboard does, while the first attempt to connect is held open
     * until every other request has reached the service and waits; then lets the attempt end.
     *
     * @return what each request got
     */
    private <T> List<T> concurrentRequests(KubernetesService svc, int count, Supplier<T> request,
                                           CountDownLatch attemptStarted, CountDownLatch endAttempt) throws Exception {
        Queue<T> results = new ConcurrentLinkedQueue<>();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> requests = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Thread t = new Thread(() -> {
                try {
                    results.add(request.get());
                } catch (Throwable e) {
                    failures.add(e);
                }
            }, "dashboard-request-" + i);
            requests.add(t);
            t.start();
        }
        try {
            assertTrue(attemptStarted.await(10, TimeUnit.SECONDS), "a request started connecting");
            awaitWaitingOn(svc, requests, count - 1);
        } finally {
            endAttempt.countDown();
        }
        for (Thread t : requests) {
            t.join(10_000);
            assertFalse(t.isAlive(), t.getName() + " finished");
        }
        assertTrue(failures.isEmpty(), () -> "requests failed: " + failures);
        assertEquals(count, results.size());
        return new ArrayList<>(results);
    }

    /** Waits until {@code expected} of {@code threads} are waiting for the service's lock. */
    private static void awaitWaitingOn(Object lock, List<Thread> threads, int expected) throws InterruptedException {
        int lockId = System.identityHashCode(lock);
        long deadline = System.currentTimeMillis() + 10_000;
        int waiting = 0;
        while (System.currentTimeMillis() < deadline) {
            waiting = 0;
            for (Thread t : threads) {
                ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(t.getId());
                if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
                        && info.getLockInfo().getIdentityHashCode() == lockId) {
                    waiting++;
                }
            }
            if (waiting >= expected) {
                return;
            }
            Thread.sleep(10);
        }
        fail("only " + waiting + " of " + expected + " requests reached the service while it was connecting");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anOperatorWhoOpensTheViewFirstDoesNotLeaveItUnconfiguredForEveryone() {
        // Settings an administrator saved for herself before settings were shared, not carried over yet.
        view.instanceDataOf("alice").put("kubeconfig.uploaded", "true");
        view.instanceDataOf("alice").put("kubeconfig.path", kubeconfigFile.toString());
        view.instanceDataOf("alice").put("auth.mode", "kubeconfig");

        KubernetesService svc = serviceBuiltBy("ops1", built);
        assertNull(svc.getClient(), "ops1 saw no configuration");
        assertEquals(ConnectionHealth.State.UNCONFIGURED, svc.pingCluster().getState());
        assertEquals(0, builds.get(), "nothing to connect with yet");

        view.as("alice");
        assertTrue(new ViewConfigurationService(view.context).isConfigured());

        view.as("ops1");
        assertEquals(ConnectionHealth.State.CONNECTED, svc.pingCluster().getState(), "ops1 is connected too");
        assertSame(built, svc.getClient());
        view.as("alice");
        assertSame(built, svc.getClient(), "and so is alice, on the same client");
        assertEquals(1, builds.get());
    }

    @Test
    void backgroundWorkConnectsFromTheSharedConfiguration() {
        KubernetesService svc = serviceBuiltBy(null, built);
        assertNull(svc.getClient());

        view.as("alice");
        new ViewConfigurationService(view.context).saveKubeconfigPath(kubeconfigFile.toString());

        when(built.nodes().list()).thenReturn(new NodeList());
        view.as(null);
        assertEquals(0, svc.countNodes(), "a background step finds the view configured");
        assertSame(built, svc.getClient());
    }

    @Test
    void aServiceBuiltWhileTheClusterCannotBeReachedIsKeptAndConnectsAfterTheWait() {
        configuredByAlice();
        java.util.concurrent.atomic.AtomicBoolean reachable = new java.util.concurrent.atomic.AtomicBoolean();
        KubernetesService svc = assertDoesNotThrow(() -> serviceConnectingWith("ops1", () -> {
            if (!reachable.get()) {
                throw new IllegalStateException("connect timed out");
            }
            return built;
        }), "building the service does not fail with the connection");
        assertEquals(1, builds.get(), "the service tried the saved configuration when it was built");

        assertNull(svc.getClient());
        assertEquals(ConnectionHealth.State.UNREACHABLE, svc.pingCluster().getState(), "configured, not connected yet");
        assertEquals(1, builds.get(), "the wait after that attempt applies to the following requests");

        reachable.set(true);
        svc.connectClock = () -> System.currentTimeMillis() + KubernetesService.CONNECT_RETRY_MS;
        assertSame(built, svc.getClient(), "connected once the wait is over");
        assertEquals(2, builds.get());
    }

    @Test
    void anUnconfiguredViewStaysUnconfiguredWithoutAttemptingToConnect() {
        KubernetesService svc = serviceBuiltBy("ops1", built);

        assertNull(svc.getClient());
        IllegalStateException refused = assertThrows(IllegalStateException.class, svc::countNodes);
        assertFalse(refused instanceof KubernetesService.NotConnectedException);
        assertEquals("The view is not configured with a kubeconfig.", refused.getMessage());
        assertEquals(ConnectionHealth.State.UNCONFIGURED, svc.pingCluster().getState());
        assertEquals(0, builds.get());
    }

    @Test
    void aFailedConnectionIsNotRetriedOnEveryRequest() {
        KubernetesService svc = serviceBuiltBy("ops1", null);
        view.as("alice");
        new ViewConfigurationService(view.context).saveKubeconfigPath(kubeconfigFile.toString());

        view.as("ops1");
        assertNull(svc.getClient());
        assertEquals(ConnectionHealth.State.UNREACHABLE, svc.pingCluster().getState());
        assertNull(svc.getClient());
        assertEquals(1, builds.get(), "tried once, then waits before trying again");
    }

    @Test
    void requestsSentTogetherWhileTheLoginFailsLogInOnlyOnce() throws Exception {
        CountDownLatch attemptStarted = new CountDownLatch(1);
        CountDownLatch endAttempt = new CountDownLatch(1);
        KubernetesService svc = serviceConnectingWith("ops1", () -> {
            attemptStarted.countDown();
            await(endAttempt); // a slow login that is refused in the end
            return null;
        });
        configuredByAlice();

        List<ConnectionHealth> answers = concurrentRequests(svc, 5, svc::pingCluster, attemptStarted, endAttempt);

        assertEquals(1, builds.get(), "the requests that waited used the outcome of the attempt they waited for");
        answers.forEach(h -> assertEquals(ConnectionHealth.State.UNREACHABLE, h.getState()));
        assertNull(svc.getClient());
        assertEquals(1, builds.get(), "and the next request waits before trying again");
    }

    @Test
    void requestsSentTogetherWhileConnectingAllGetTheConnection() throws Exception {
        CountDownLatch attemptStarted = new CountDownLatch(1);
        CountDownLatch endAttempt = new CountDownLatch(1);
        KubernetesService svc = serviceConnectingWith("ops1", () -> {
            attemptStarted.countDown();
            await(endAttempt);
            return built;
        });
        configuredByAlice();

        List<KubernetesClient> clients = concurrentRequests(svc, 5, svc::getClient, attemptStarted, endAttempt);

        assertEquals(1, builds.get());
        clients.forEach(c -> assertSame(built, c, "no request reports the view unconfigured while it connects"));
    }

    @Test
    void theWaitAfterAFailedAttemptCountsFromWhenTheAttemptEnded() {
        AtomicLong now = new AtomicLong(1_000_000L);
        KubernetesService svc = serviceConnectingWith("ops1", () -> {
            now.addAndGet(25_000L); // a login that times out
            return null;
        });
        svc.connectClock = now::get;
        configuredByAlice();

        assertNull(svc.getClient());
        now.addAndGet(KubernetesService.CONNECT_RETRY_MS - 1);
        assertNull(svc.getClient());
        assertEquals(1, builds.get(), "the full wait applies after a slow attempt");

        now.addAndGet(1);
        assertNull(svc.getClient());
        assertEquals(2, builds.get(), "tried again once the wait is over");
    }

    @Test
    void aConfiguredViewThatCouldNotConnectIsReportedDisconnectedNotUnconfigured() {
        KubernetesService svc = serviceConnectingWith("ops1", () -> {
            throw new IllegalStateException("OAuth server unreachable");
        });
        configuredByAlice();

        for (String user : new String[] {"ops1", "alice", null}) {
            view.as(user);
            ConnectionHealth health = svc.pingCluster();
            assertEquals(ConnectionHealth.State.UNREACHABLE, health.getState(),
                    "the UI shows the view disconnected and keeps probing, instead of the first-run configuration page");
            KubernetesService.NotConnectedException refused =
                    assertThrows(KubernetesService.NotConnectedException.class, svc::countNodes);
            assertFalse(refused.getMessage().contains("not configured with a kubeconfig"),
                    "the UI takes that text for a view to configure: " + refused.getMessage());
        }
        assertEquals(1, builds.get());
    }

    @Test
    void theWaitDoublesAfterEachFailureUpToFifteenMinutes() {
        long[] expected = {30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L};
        for (int failures = 1; failures <= expected.length; failures++) {
            assertEquals(expected[failures - 1], KubernetesService.connectRetryDelayMs(failures), failures + " failures");
        }
        assertEquals(KubernetesService.CONNECT_RETRY_MAX_MS, KubernetesService.connectRetryDelayMs(Integer.MAX_VALUE));
    }

    @Test
    void consecutiveFailedAttemptsAreSpacedFurtherApartUntilTheConfigurationIsSavedAgain() {
        AtomicLong now = new AtomicLong(1_000_000L);
        KubernetesService svc = serviceConnectingWith("ops1", () -> null);
        svc.connectClock = now::get;
        configuredByAlice();

        assertNull(svc.getClient());
        int attempts = 1;
        assertEquals(attempts, builds.get());
        for (long wait : new long[] {30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L}) {
            now.addAndGet(wait - 1);
            assertNull(svc.getClient());
            assertEquals(attempts, builds.get(), "no attempt " + (wait - 1) + " ms after the previous one");
            now.addAndGet(1);
            assertNull(svc.getClient());
            assertEquals(++attempts, builds.get(), "the next attempt " + wait + " ms after the previous one");
        }

        // An administrator saves the configuration again: it is tried at once, and the wait starts again from 30 s.
        view.as("alice");
        assertFalse(svc.forceReloadClient());
        assertEquals(++attempts, builds.get());
        view.as("ops1");
        now.addAndGet(KubernetesService.CONNECT_RETRY_MS - 1);
        assertNull(svc.getClient());
        assertEquals(attempts, builds.get());
        now.addAndGet(1);
        assertNull(svc.getClient());
        assertEquals(++attempts, builds.get(), "30 s after the failed attempt that followed the save");
    }
}
