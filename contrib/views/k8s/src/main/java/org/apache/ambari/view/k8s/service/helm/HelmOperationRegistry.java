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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The Helm operations that change a release and are still running in this server, at most one per release
 * (keyed {@code namespace/release}), shared by every view instance, operation step and REST call.
 *
 * <p>Helm runs inside this JVM through a native library: once a call has started it cannot be interrupted, and
 * {@code Future.cancel(true)} only stops the caller from waiting for it. A release therefore stays registered
 * until the Helm call actually returns, even after its operation was canceled, and any new deploy, upgrade,
 * rollback, uninstall or catalog change of that release is refused meanwhile. Without this, a retry raced the call
 * it replaced and found the release "busy" in Helm's own records.
 */
public final class HelmOperationRegistry {

    private static final ConcurrentMap<String, Future<?>> IN_FLIGHT = new ConcurrentHashMap<>();

    /** Releases whose registered operation runs on the current thread, so nested calls do not refuse themselves. */
    private static final ThreadLocal<Set<String>> HELD_BY_THIS_THREAD = ThreadLocal.withInitial(HashSet::new);

    private static final int QUEUED = 0;
    private static final int STARTED = 1;
    private static final int ABANDONED = 2;

    private HelmOperationRegistry() {
    }

    /**
     * Thrown when a release already has a Helm operation running in this server.
     */
    public static final class ReleaseBusyException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public ReleaseBusyException(String namespace, String release) {
            super("A Helm operation on release " + key(namespace, release) + " is still running. "
                    + "Wait for it to finish before deploying, upgrading or changing this release again.");
        }
    }

    /**
     * Runs {@code task} on {@code executor} with the release registered until the task itself returns.
     * Canceling the returned future does not free the release while the task is running; only a task canceled
     * before it started frees it at once.
     *
     * @param namespace release namespace
     * @param release   release name
     * @param executor  where the task runs
     * @param task      the Helm work; it may call back into this registry for the same release
     * @return the running operation
     * @throws ReleaseBusyException when the release already has an operation running
     */
    public static <T> Future<T> submit(String namespace, String release, Executor executor, Callable<T> task) {
        final String key = key(namespace, release);
        final AtomicInteger phase = new AtomicInteger(QUEUED);
        final AtomicReference<Future<?>> self = new AtomicReference<>();
        FutureTask<T> operation = new FutureTask<T>(() -> {
            if (!phase.compareAndSet(QUEUED, STARTED)) {
                throw new CancellationException("Canceled before it started");
            }
            Set<String> held = HELD_BY_THIS_THREAD.get();
            held.add(key);
            try {
                return task.call();
            } finally {
                held.remove(key);
                IN_FLIGHT.remove(key, self.get());
            }
        }) {
            @Override
            protected void done() {
                // Canceled before it started: no Helm call will run, so the release is free now. Once started,
                // the task frees the release itself when the Helm call returns.
                if (isCancelled() && phase.compareAndSet(QUEUED, ABANDONED)) {
                    IN_FLIGHT.remove(key, this);
                }
            }
        };
        self.set(operation);
        if (IN_FLIGHT.putIfAbsent(key, operation) != null) {
            throw new ReleaseBusyException(namespace, release);
        }
        try {
            executor.execute(operation);
        } catch (RuntimeException rejected) {
            IN_FLIGHT.remove(key, operation);
            throw rejected;
        }
        return operation;
    }

    /**
     * Runs {@code task} on the calling thread with the release registered until it returns. A call made from
     * inside an operation already registered for the same release on this thread simply runs.
     *
     * @param namespace release namespace
     * @param release   release name
     * @param task      the Helm work
     * @return what the task returned
     * @throws ReleaseBusyException when another operation on the release is running
     */
    public static <T> T run(String namespace, String release, Supplier<T> task) {
        final String key = key(namespace, release);
        Set<String> held = HELD_BY_THIS_THREAD.get();
        if (held.contains(key)) {
            return task.get();
        }
        CompletableFuture<Void> operation = new CompletableFuture<>();
        if (IN_FLIGHT.putIfAbsent(key, operation) != null) {
            throw new ReleaseBusyException(namespace, release);
        }
        held.add(key);
        try {
            return task.get();
        } finally {
            held.remove(key);
            IN_FLIGHT.remove(key, operation);
            operation.complete(null);
        }
    }

    /**
     * Refuses up front a change of a release that already has an operation running, before anything else is done.
     *
     * @throws ReleaseBusyException when an operation on the release is running on another thread
     */
    public static void requireIdle(String namespace, String release) {
        if (isRunningElsewhere(namespace, release)) {
            throw new ReleaseBusyException(namespace, release);
        }
    }

    /** True while a Helm operation registered for the release has not returned. */
    public static boolean isRunning(String namespace, String release) {
        return IN_FLIGHT.containsKey(key(namespace, release));
    }

    /** True while a Helm operation for the release runs, other than one registered on the current thread. */
    public static boolean isRunningElsewhere(String namespace, String release) {
        String key = key(namespace, release);
        return IN_FLIGHT.containsKey(key) && !HELD_BY_THIS_THREAD.get().contains(key);
    }

    static String key(String namespace, String release) {
        return (namespace == null ? "" : namespace) + "/" + (release == null ? "" : release);
    }
}
