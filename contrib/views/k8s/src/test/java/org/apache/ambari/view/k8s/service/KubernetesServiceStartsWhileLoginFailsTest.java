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

import com.sun.net.httpserver.HttpExchange;
import org.apache.ambari.view.k8s.model.ConnectionHealth;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.utils.SelfSignedHttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ambari starts while the OpenShift login saved for the view instance cannot get a token: the account's password was
 * changed outside KDPS (the OAuth server refuses it), or the OAuth server fails. The first request builds the
 * instance's Kubernetes service. It must be kept although it could not connect, so that the requests of every user
 * that follow share its connection attempts instead of each logging in with the saved password again. A refused
 * password is not tried again until the login is saved again, and a login that could not be made is tried again less
 * and less often: directories lock an account after a few refused logins.
 */
class KubernetesServiceStartsWhileLoginFailsTest {

    @TempDir
    Path workDir;

    PerUserViewContext view;
    String apiUrl;
    /** The CA the saved login names, as the operator pastes it: the cluster certificate is verified. */
    String caPem;
    /** An OpenShift API and OAuth server; it refuses the saved password unless a test changes {@link #loginAnswer}. */
    SelfSignedHttpsServer oauth;
    final AtomicInteger passwordLogins = new AtomicInteger();
    final CountDownLatch loginReceived = new CountDownLatch(1);
    /** Holds the OAuth server's answer to a login until counted down; answers at once unless a test replaces it. */
    volatile CountDownLatch answerLogins = new CountDownLatch(0);
    /** The OAuth server's answer to a login: 401 refuses the password, 302 grants a token, anything else fails. */
    volatile int loginAnswer = 401;

    @BeforeEach
    void setUp() throws Exception {
        oauth = new SelfSignedHttpsServer(true, "localhost");
        caPem = oauth.certificatePem();
        apiUrl = oauth.ipUrl();
        oauth.handle("/.well-known/oauth-authorization-server", exchange ->
                respond(exchange, 200, "{\"authorization_endpoint\":\"" + apiUrl + "/oauth/authorize\"}"));
        oauth.handle("/oauth/authorize", exchange -> {
            passwordLogins.incrementAndGet();
            loginReceived.countDown();
            try {
                answerLogins.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (loginAnswer == 302) {
                exchange.getResponseHeaders().set("Location",
                        apiUrl + "/oauth/token/implicit#access_token=sha256~granted&expires_in=86400&token_type=Bearer");
            }
            respond(exchange, loginAnswer, "");
        });
        // The API server no longer accepts the token it gave (e.g. revoked when the password was changed).
        oauth.handle("/api", exchange -> respond(exchange, 401,
                "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"status\":\"Failure\",\"message\":\"Unauthorized\","
                        + "\"reason\":\"Unauthorized\",\"code\":401}"));

        // Each test is its own view instance: services are kept per instance name for the life of the JVM.
        view = new PerUserViewContext("login-fails-" + UUID.randomUUID(), workDir);
        view.as("alice");
        new ViewConfigurationService(view.context).saveOpenShiftLogin(apiUrl, "kdps-deployer", "changed-since", caPem, false);
    }

    @AfterEach
    void tearDown() {
        answerLogins.countDown();
        oauth.close();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void everyUsersRequestsShareTheServiceAndItsSingleLoginAttempt() {
        view.as("ops1");
        KubernetesService first = assertDoesNotThrow(() -> KubernetesService.get(view.context),
                "the request that builds the service is answered, not failed");
        assertEquals(1, passwordLogins.get(), "the saved login was tried once");

        for (String user : List.of("ops1", "bob", "alice", "ops1")) {
            view.as(user);
            KubernetesService svc = assertDoesNotThrow(() -> KubernetesService.get(view.context));
            assertSame(first, svc, user + " gets the service built by the first request");
            assertEquals(ConnectionHealth.State.UNAUTHENTICATED, svc.pingCluster().getState(),
                    "configured, but the password was refused: the UI shows the view disconnected, not unconfigured");
            assertNull(svc.getClient());
            assertThrows(KubernetesService.NotConnectedException.class, svc::countNodes, "an action is refused, not retried");
        }
        assertEquals(1, passwordLogins.get(), "no request logs in again with the refused password");
    }

    @Test
    void aRefusedPasswordIsNotTriedAgainUntilTheLoginIsSavedAgain() {
        view.as("ops1");
        KubernetesService svc = KubernetesService.get(view.context);
        svc.retiredClientGraceSeconds = 0;
        svc.connectClock = () -> System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1);
        assertEquals(1, passwordLogins.get());

        for (String user : Arrays.asList("ops1", "bob", "alice", null)) {
            view.as(user);
            assertEquals(ConnectionHealth.State.UNAUTHENTICATED, svc.pingCluster().getState());
            assertNull(svc.getClient());
            assertThrows(KubernetesService.NotConnectedException.class, svc::countNodes);
        }
        assertEquals(1, passwordLogins.get(), "however long after, the refused password is not tried again");

        // An administrator saves the login again: it is tried at once, and once more refused.
        view.as("alice");
        new ViewConfigurationService(view.context).saveOpenShiftLogin(apiUrl, "kdps-deployer", "still-wrong", caPem, false);
        assertFalse(svc.forceReloadClient());
        assertEquals(2, passwordLogins.get());
        view.as("ops1");
        assertEquals(ConnectionHealth.State.UNAUTHENTICATED, svc.pingCluster().getState());
        assertEquals(2, passwordLogins.get(), "and not tried again either");

        // Saved with the right password, the view connects.
        loginAnswer = 302;
        view.as("alice");
        new ViewConfigurationService(view.context).saveOpenShiftLogin(apiUrl, "kdps-deployer", "the-new-one", caPem, false);
        assertTrue(svc.forceReloadClient());
        assertEquals(3, passwordLogins.get());
        view.as("ops1");
        assertNotNull(svc.getClient());
    }

    @Test
    void aLoginThatCouldNotBeMadeIsTriedAgainLessAndLessOften() {
        loginAnswer = 503; // the OAuth server fails: the password was not refused
        view.as("ops1");
        KubernetesService svc = KubernetesService.get(view.context);
        assertEquals(1, passwordLogins.get());
        AtomicLong now = new AtomicLong(System.currentTimeMillis() + KubernetesService.CONNECT_RETRY_MS);
        svc.connectClock = now::get;

        assertEquals(ConnectionHealth.State.UNREACHABLE, svc.pingCluster().getState(), "configured, not connected yet");
        assertEquals(2, passwordLogins.get(), "tried again once the first wait is over");
        int logins = 2;
        for (long wait : new long[] {60_000L, 120_000L, 240_000L}) {
            now.addAndGet(wait - 1);
            assertEquals(ConnectionHealth.State.UNREACHABLE, svc.pingCluster().getState());
            assertThrows(KubernetesService.NotConnectedException.class, svc::countNodes);
            assertEquals(logins, passwordLogins.get(), "not tried again " + (wait - 1) + " ms after the previous attempt");
            now.addAndGet(1);
            svc.pingCluster();
            assertEquals(++logins, passwordLogins.get(), "tried again " + wait + " ms after the previous attempt");
        }
    }

    @Test
    void aPasswordChangedWhileConnectedIsTriedOnceToRenewTheTokenThenNoMore() {
        loginAnswer = 302;
        view.as("alice");
        // Built directly rather than through get(), so that no background monitoring task uses the connection.
        KubernetesService svc = new KubernetesService(view.context);
        svc.retiredClientGraceSeconds = 0;
        assertNotNull(svc.getClient());
        assertEquals(1, passwordLogins.get());

        // The password is changed in the directory, and the API server no longer accepts the token.
        loginAnswer = 401;
        view.as("ops1");
        assertEquals(ConnectionHealth.State.UNAUTHENTICATED, svc.pingCluster().getState());
        assertEquals(2, passwordLogins.get(), "one login to renew the token, refused");
        assertNull(svc.getClient());

        svc.connectClock = () -> System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1);
        for (String user : Arrays.asList("ops1", "bob", "alice", null)) {
            view.as(user);
            assertEquals(ConnectionHealth.State.UNAUTHENTICATED, svc.pingCluster().getState());
            assertThrows(KubernetesService.NotConnectedException.class, () -> svc.getNodes(10, 0));
        }
        assertEquals(2, passwordLogins.get(), "the refused password is not tried again");
    }

    @Test
    void requestsSentTogetherAfterARestartLogInOnce() throws Exception {
        answerLogins = new CountDownLatch(1); // a slow OAuth server
        int count = 5;
        view.as("ops1");
        Queue<KubernetesService> services = new ConcurrentLinkedQueue<>();
        Queue<ConnectionHealth.State> answers = new ConcurrentLinkedQueue<>();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> requests = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Thread t = new Thread(() -> {
                try {
                    KubernetesService svc = KubernetesService.get(view.context);
                    services.add(svc);
                    answers.add(svc.pingCluster().getState());
                } catch (Throwable e) {
                    failures.add(e);
                }
            }, "dashboard-request-" + i);
            requests.add(t);
            t.start();
        }
        try {
            assertTrue(loginReceived.await(30, TimeUnit.SECONDS), "a request started logging in");
            awaitWaitingForTheService(requests, count - 1);
        } finally {
            answerLogins.countDown();
        }
        for (Thread t : requests) {
            t.join(30_000);
            assertFalse(t.isAlive(), t.getName() + " finished");
        }

        assertTrue(failures.isEmpty(), () -> "requests failed: " + failures);
        assertEquals(1, passwordLogins.get(), "the requests that waited used the outcome of the login they waited for");
        assertEquals(count, services.size());
        assertEquals(1, services.stream().distinct().count(), "they all got the same service");
        answers.forEach(state -> assertEquals(ConnectionHealth.State.UNAUTHENTICATED, state));
    }

    /** Waits until {@code expected} of {@code threads} are blocked while the service of the view instance is built. */
    private static void awaitWaitingForTheService(List<Thread> threads, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        int waiting = 0;
        while (System.currentTimeMillis() < deadline) {
            waiting = 0;
            for (Thread t : threads) {
                ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(t.getId(), Integer.MAX_VALUE);
                if (info != null && info.getThreadState() == Thread.State.BLOCKED && Arrays.stream(info.getStackTrace())
                        .anyMatch(frame -> "get".equals(frame.getMethodName())
                                && KubernetesService.class.getName().equals(frame.getClassName()))) {
                    waiting++;
                }
            }
            if (waiting >= expected) {
                return;
            }
            Thread.sleep(10);
        }
        fail("only " + waiting + " of " + expected + " requests reached the service while it was being built");
    }
}
