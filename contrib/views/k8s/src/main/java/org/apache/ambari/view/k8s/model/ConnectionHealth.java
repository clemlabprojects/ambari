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

package org.apache.ambari.view.k8s.model;

/**
 * Result of a LIVE connectivity probe against the target Kubernetes/OpenShift API server.
 *
 * <p>This is deliberately distinct from "is the view configured?" (which only reports whether
 * credentials were ever stored). A {@code ConnectionHealth} reflects whether those stored
 * credentials <em>currently</em> authenticate against the cluster — so the UI can show an honest
 * connection badge instead of assuming "configured == connected". The common failure it captures
 * is an OpenShift bearer token that was revoked early (e.g. the operator logged into the console
 * directly, rotating the token), which leaves the view "configured" but no longer authenticated.
 *
 * <p>Serialized to JSON for the {@code /cluster/liveness} endpoint; the {@code state} name is what
 * the frontend switches on to drive the badge (connected / disconnected / unconfigured).
 */
public class ConnectionHealth {

    /** Coarse connectivity outcome, consumed by the UI badge. */
    public enum State {
        /** The API server answered and the stored credentials authenticated. */
        CONNECTED,
        /** The API server answered but rejected the credentials (HTTP 401) — a re-login is required. */
        UNAUTHENTICATED,
        /** The API server could not be reached (network/TLS/timeout) or returned an unexpected error. */
        UNREACHABLE,
        /** No credentials have been stored yet — the view has never been configured. */
        UNCONFIGURED
    }

    private final State state;
    private final String message;

    private ConnectionHealth(State state, String message) {
        this.state = state;
        this.message = message;
    }

    /** The cluster answered and the credentials are valid. */
    public static ConnectionHealth connected() {
        return new ConnectionHealth(State.CONNECTED, "Cluster reachable and credentials valid.");
    }

    /** The cluster rejected the stored credentials with HTTP 401 — the operator must re-authenticate. */
    public static ConnectionHealth unauthenticated(String detail) {
        return new ConnectionHealth(State.UNAUTHENTICATED,
                "Stored credentials were rejected (401). Re-login on the Cluster settings page. " + safe(detail));
    }

    /** The cluster could not be reached, or returned an error other than 401. */
    public static ConnectionHealth unreachable(String detail) {
        return new ConnectionHealth(State.UNREACHABLE, "Cluster API is unreachable. " + safe(detail));
    }

    /** No credentials have been stored — the view has never been configured. */
    public static ConnectionHealth unconfigured() {
        return new ConnectionHealth(State.UNCONFIGURED, "The view is not configured with any cluster credentials.");
    }

    /** @return {@code true} only when the cluster answered and the credentials authenticated. */
    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    public State getState() {
        return state;
    }

    public String getMessage() {
        return message;
    }

    private static String safe(String detail) {
        return (detail == null || detail.isBlank()) ? "" : detail.trim();
    }
}
