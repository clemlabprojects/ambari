// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements.  See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License.  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

// fakeAmbariBackend answers like the KDPS view behind Ambari's REST API with CSRF protection on:
// a POST without the X-Requested-By header is refused with 400 before it reaches the view.
func fakeAmbariBackend(t *testing.T, okStatus int, okBody string) (*httptest.Server, *[]*http.Request) {
	t.Helper()
	var received []*http.Request
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		received = append(received, r.Clone(context.Background()))
		if r.Method != http.MethodGet && r.Header.Get("X-Requested-By") == "" {
			http.Error(w, "CSRF protection is turned on. X-Requested-By HTTP header is required.", http.StatusBadRequest)
			return
		}
		w.WriteHeader(okStatus)
		_, _ = w.Write([]byte(okBody))
	}))
	t.Cleanup(server.Close)
	return server, &received
}

// useBackend points the webhook at the given backend with Basic auth, and restores the previous
// settings when the test ends.
func useBackend(t *testing.T, server *httptest.Server) {
	t.Helper()
	previousConfig, previousClient := globalConfig, httpClient
	previousUser, previousPass := backendBasicUser, backendBasicPass
	t.Cleanup(func() {
		globalConfig, httpClient = previousConfig, previousClient
		backendBasicUser, backendBasicPass = previousUser, previousPass
	})
	globalConfig = WebhookConfig{BackendBaseUrl: server.URL, BackendAuthMode: "basic", BackendRetryCount: 0}
	httpClient = server.Client()
	backendBasicUser, backendBasicPass = "webhook", "secret"
}

func TestEnqueueKeytabCreationPassesAmbariCsrfProtection(t *testing.T) {
	server, received := fakeAmbariBackend(t, http.StatusAccepted, `{"id":"cmd-1"}`)
	useBackend(t, server)

	err := enqueueKeytabCreation(context.Background(), "trino/trino.ns.k8s@EXAMPLE.COM", "ns", "trino-0", "keytab-trino-0", "service.keytab")
	if err != nil {
		t.Fatalf("keytab request was refused: %v", err)
	}
	if len(*received) != 1 {
		t.Fatalf("expected one backend call, got %d", len(*received))
	}
	call := (*received)[0]
	if call.URL.Path != "/kerberos/keytab" {
		t.Errorf("unexpected backend path %q", call.URL.Path)
	}
	if got := call.Header.Get("X-Requested-By"); got != "ambari" {
		t.Errorf("X-Requested-By = %q, want %q", got, "ambari")
	}
	if got := call.Header.Get("Content-Type"); got != "application/json" {
		t.Errorf("Content-Type = %q, want application/json", got)
	}
	if user, pass, ok := call.BasicAuth(); !ok || user != "webhook" || pass != "secret" {
		t.Errorf("Basic credentials were not kept alongside the CSRF header")
	}
}

func TestRequestKeytabFromBackendPassesAmbariCsrfProtection(t *testing.T) {
	server, received := fakeAmbariBackend(t, http.StatusOK, "keytab-bytes")
	useBackend(t, server)

	keytab, err := requestKeytabFromBackend(context.Background(), "trino/trino.ns.k8s@EXAMPLE.COM")
	if err != nil {
		t.Fatalf("keytab request was refused: %v", err)
	}
	if string(keytab) != "keytab-bytes" {
		t.Errorf("unexpected keytab %q", string(keytab))
	}
	if len(*received) != 1 {
		t.Fatalf("expected one backend call, got %d", len(*received))
	}
	call := (*received)[0]
	if got := call.Header.Get("X-Requested-By"); got != "ambari" {
		t.Errorf("X-Requested-By = %q, want %q", got, "ambari")
	}
	if got := call.URL.Query().Get("principal"); got != "trino/trino.ns.k8s@EXAMPLE.COM" {
		t.Errorf("principal = %q", got)
	}
}
