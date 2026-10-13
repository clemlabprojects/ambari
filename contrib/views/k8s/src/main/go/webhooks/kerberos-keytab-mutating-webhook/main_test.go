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
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	admissionv1 "k8s.io/api/admission/v1"
	corev1 "k8s.io/api/core/v1"
	meta "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
)

// fakeAmbariBackend answers like the KDPS view behind Ambari's REST API with CSRF protection on:
// a POST without the X-Requested-By header is refused with 400 before it reaches the view.
func fakeAmbariBackend(t *testing.T, okStatus int, okBody string) (*httptest.Server, *[]*http.Request) {
	t.Helper()
	var received []*http.Request
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		clone := r.Clone(context.Background())
		clone.Body = io.NopCloser(bytes.NewReader(body))
		received = append(received, clone)
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

	err := enqueueKeytabCreation(context.Background(), "trino/trino.ns.k8s@EXAMPLE.COM", "ns", "trino-0", "keytab-trino-0", "service.keytab", "trino")
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

// writeClientIdentity issues a client certificate from a throwaway CA, as the view does, writes the
// certificate and key where the webhook reads them, and returns the CA.
func writeClientIdentity(t *testing.T) (*x509.Certificate, string, string) {
	t.Helper()
	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	caTemplate := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "Ambari Internal CA"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		IsCA:                  true,
		BasicConstraintsValid: true,
		KeyUsage:              x509.KeyUsageCertSign,
	}
	caDER, err := x509.CreateCertificate(rand.Reader, caTemplate, caTemplate, &caKey.PublicKey, caKey)
	if err != nil {
		t.Fatal(err)
	}
	ca, _ := x509.ParseCertificate(caDER)

	clientKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	clientTemplate := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: "ambari-webhook-client"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	}
	clientDER, err := x509.CreateCertificate(rand.Reader, clientTemplate, ca, &clientKey.PublicKey, caKey)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalPKCS8PrivateKey(clientKey)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	certPath, keyPath := filepath.Join(dir, "client.crt"), filepath.Join(dir, "client.key")
	if err := os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: clientDER}), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER}), 0o600); err != nil {
		t.Fatal(err)
	}
	return ca, certPath, keyPath
}

func TestKeytabRequestIsSignedWithTheClientCertificateAndSignsInWithTheDerivedPassword(t *testing.T) {
	server, received := fakeAmbariBackend(t, http.StatusAccepted, `{"id":"cmd-1"}`)
	useBackend(t, server)
	ca, certPath, keyPath := writeClientIdentity(t)
	globalConfig.BackendAuthMode = "mtls,webhook-account"
	globalConfig.BackendAccountUsername = "webhook-user"
	globalConfig.BackendClientCertPath, globalConfig.BackendClientKeyPath = certPath, keyPath

	err := enqueueKeytabCreation(context.Background(), "trino-analytics@EXAMPLE.COM", "analytics", "trino-0", "keytab-trino-0", "service.keytab", "trino")
	if err != nil {
		t.Fatalf("keytab request was refused: %v", err)
	}
	call := (*received)[0]

	// Signed in as the view's account, with the password derived from the key it mounts.
	keyBlock, _ := pem.Decode(mustRead(t, keyPath))
	if user, pass, ok := call.BasicAuth(); !ok || user != "webhook-user" || pass != webhookAccountPassword(keyBlock.Bytes) {
		t.Errorf("not signed in as the webhook account with its derived password (user=%q)", user)
	}

	// The certificate is the one the CA issued, and the signature covers every field of the request.
	certDER, err := base64.StdEncoding.DecodeString(call.Header.Get(certificateHeader))
	if err != nil {
		t.Fatalf("certificate header: %v", err)
	}
	cert, err := x509.ParseCertificate(certDER)
	if err != nil {
		t.Fatalf("certificate: %v", err)
	}
	if err := cert.CheckSignatureFrom(ca); err != nil {
		t.Errorf("the certificate sent is not the one the CA issued: %v", err)
	}
	signature, err := base64.StdEncoding.DecodeString(call.Header.Get(signatureHeader))
	if err != nil {
		t.Fatalf("signature header: %v", err)
	}
	var sent keytabRequest
	if err := json.Unmarshal(mustBody(t, call), &sent); err != nil {
		t.Fatalf("body: %v", err)
	}
	if sent.ReleaseName != "trino" || sent.Namespace != "analytics" {
		t.Errorf("unexpected body %+v", sent)
	}
	digest := sha256.Sum256(keytabRequestSigningInput(call.Header.Get(timestampHeader), sent))
	if !ecdsa.VerifyASN1(cert.PublicKey.(*ecdsa.PublicKey), digest[:], signature) {
		t.Errorf("the signature does not verify against the request sent")
	}
	tampered := sent
	tampered.SecretName = "trino-tls"
	digest = sha256.Sum256(keytabRequestSigningInput(call.Header.Get(timestampHeader), tampered))
	if ecdsa.VerifyASN1(cert.PublicKey.(*ecdsa.PublicKey), digest[:], signature) {
		t.Errorf("the signature also covers a request naming another Secret")
	}
}

// The view builds the same text and the same password; these values are asserted on its side too
// (KeytabWebhookAuthenticatorTest), so the two cannot drift apart.
func TestSigningInputAndAccountPasswordMatchTheView(t *testing.T) {
	input := keytabRequestSigningInput("1700000000", keytabRequest{
		Principal: "trino-analytics@EXAMPLE.COM", Namespace: "analytics", PodName: "trino-0",
		SecretName: "keytab-trino-0", KeyNameInSecret: "service.keytab", ReleaseName: "trino",
	})
	want := "kdps-keytab-request\n1700000000\ntrino-analytics@EXAMPLE.COM\nanalytics\ntrino-0\nkeytab-trino-0\nservice.keytab\ntrino"
	if string(input) != want {
		t.Errorf("signing input = %q, want %q", input, want)
	}
	if got := webhookAccountPassword([]byte("key-der-bytes")); got != "sfW3tc4mdQP4scL0FaKsgGbBmG5Jk-XjPfUxZx9zd0Y" {
		t.Errorf("account password = %q", got)
	}
}

func TestOnlyOneHeaderSchemeMayBeNamed(t *testing.T) {
	if err := validateBackendAuthMode("mtls,webhook-account"); err != nil {
		t.Errorf("mtls,webhook-account refused: %v", err)
	}
	if err := validateBackendAuthMode("basic,webhook-account"); err == nil {
		t.Errorf("basic and webhook-account accepted together")
	}
	if !hasAuthMode("mtls, webhook-account", "webhook-account") || hasAuthMode("mtls", "basic") {
		t.Errorf("auth mode parsing is wrong")
	}
}

// admitPod sends the pod through the admission handler, as the Kubernetes API server does, and
// returns the webhook's answer.
func admitPod(t *testing.T, pod corev1.Pod) *admissionv1.AdmissionResponse {
	t.Helper()
	raw, err := json.Marshal(pod)
	if err != nil {
		t.Fatal(err)
	}
	review, err := json.Marshal(admissionv1.AdmissionReview{
		TypeMeta: meta.TypeMeta{APIVersion: "admission.k8s.io/v1", Kind: "AdmissionReview"},
		Request: &admissionv1.AdmissionRequest{
			UID:       "uid-1",
			Kind:      meta.GroupVersionKind{Version: "v1", Kind: "Pod"},
			Namespace: pod.Namespace,
			Object:    runtime.RawExtension{Raw: raw},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	recorder := httptest.NewRecorder()
	handleAdmissionMutate(recorder, httptest.NewRequest(http.MethodPost, "/mutate", bytes.NewReader(review)))
	var answer admissionv1.AdmissionReview
	if err := json.Unmarshal(recorder.Body.Bytes(), &answer); err != nil || answer.Response == nil {
		t.Fatalf("no admission answer: %v (%s)", err, recorder.Body.String())
	}
	return answer.Response
}

// The view refuses a keytab request that names no release, so every chart that asks for a keytab
// must have its release found on the pod: Trino and OpenMetadata label their pods with
// app.kubernetes.io/instance, Superset only with the older release label.
func TestAdmittedPodNamesItsReleaseFromTheHelmLabels(t *testing.T) {
	cases := []struct {
		chart  string
		labels map[string]string
	}{
		{"trino", map[string]string{"app.kubernetes.io/instance": "analytics-trino", "release": "ignored"}},
		{"legacy helm label", map[string]string{"helm.sh/release": "analytics-trino"}},
		{"superset", map[string]string{"app": "superset", "release": "analytics-trino"}},
	}
	for _, c := range cases {
		t.Run(c.chart, func(t *testing.T) {
			server, received := fakeAmbariBackend(t, http.StatusAccepted, `{"id":"cmd-1"}`)
			useBackend(t, server)
			globalConfig.NeedsKeytabLabelKey = "security.clemlab.com/needs-keytab"
			globalConfig.ServiceLabelKey = "security.clemlab.com/service"
			globalConfig.KerberosRealm = "EXAMPLE.COM"
			globalConfig.PrincipalMode = "headless"
			globalConfig.HeadlessTemplate = "{service}-{namespace}@{realm}"
			globalConfig.SecretPrefix = "keytab-"
			globalConfig.SecretDataKey = "service.keytab"
			globalConfig.VolumeName = "keytab-volume"
			globalConfig.MountPath = "/etc/security/keytabs"

			labels := map[string]string{
				"security.clemlab.com/needs-keytab": "true",
				"security.clemlab.com/service":      "dashboard",
			}
			for k, v := range c.labels {
				labels[k] = v
			}
			response := admitPod(t, corev1.Pod{
				ObjectMeta: meta.ObjectMeta{Name: "pod-0", Namespace: "analytics", Labels: labels},
				Spec:       corev1.PodSpec{Containers: []corev1.Container{{Name: "main", Image: "image"}}},
			})
			if !response.Allowed {
				t.Fatalf("pod refused: %+v", response.Result)
			}
			if len(*received) != 1 {
				t.Fatalf("expected one keytab request, got %d", len(*received))
			}
			var sent keytabRequest
			if err := json.Unmarshal(mustBody(t, (*received)[0]), &sent); err != nil {
				t.Fatalf("body: %v", err)
			}
			if sent.ReleaseName != "analytics-trino" || sent.Namespace != "analytics" {
				t.Errorf("keytab request names release %q in %q, want analytics-trino in analytics",
					sent.ReleaseName, sent.Namespace)
			}
		})
	}
}

func mustRead(t *testing.T, path string) []byte {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func mustBody(t *testing.T, r *http.Request) []byte {
	t.Helper()
	b, err := io.ReadAll(r.Body)
	if err != nil {
		t.Fatal(err)
	}
	return b
}
