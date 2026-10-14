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

// cmd/webhook/main.go
package main

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"time"

	admissionv1 "k8s.io/api/admission/v1"
	corev1 "k8s.io/api/core/v1"
	meta "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
)

//
// ---------------------------
// Configuration
// ---------------------------
//

type WebhookConfig struct {
	ListenAddress                    string        // e.g. ":8443"
	KerberosRealm                    string        // e.g. "EXAMPLE.COM"
	ClusterDnsSuffix                 string        // e.g. ".svc.cluster.local"
	BackendBaseUrl                   string        // e.g. "https://ambari-backend.svc:8443"
	BackendRequestTimeout            time.Duration // e.g. 3s
	BackendRetryCount                int           // e.g. 2
	AdmissionRequestTimeout          time.Duration // e.g. 5s
	LogAdmissionFailures             bool
	BackendAuthMode                  string // "none" | "serviceaccount-jwt" | "basic" | "webhook-account" | "mtls" | combos with mtls (e.g. "mtls,webhook-account")
	BackendAuthServiceAccountJwtPath string
	BackendBasicUserPath             string // ambari mutating webhook username
	BackendBasicPassPath             string // ambari mutating webhook password
	BackendAccountUsername           string // Ambari account of "webhook-account" mode; its password comes from the client key

	// Genericization knobs
	NeedsKeytabLabelKey string // label key to force keytab, default: security.clemlab.com/needs-keytab
	ServiceLabelKey     string // label key carrying service name, default: security.clemlab.com/service

	PrincipalTemplate string // default: "{service}/{fqdn}@{realm}"

	SecretPrefix   string // default: "keytab-"
	SecretDataKey  string // default: "service.keytab"
	VolumeName     string // default: "keytab-volume"
	MountPath      string // default: "/etc/security/keytabs"
	ContainerIndex int    // default: 0

	// mTLS to backend (paths mounted from Secret)
	BackendClientCertPath string
	BackendClientKeyPath  string
	BackendCaCertPath     string

	// How to build the {fqdn} part of the principal:
	//   "service" -> <service>.<namespace>.<suffix>
	//   "pod"     -> <pod>[.<subdomain>].<namespace>.<suffix>
	PrincipalFqdnMode string

	// Kerberos identity mode:
	//   "service"  -> service/<fqdn>@REALM  (default)
	//   "headless" -> headless like {service}-{namespace}@REALM
	//   "auto"     -> try service, fallback to headless if FQDN too long
	PrincipalMode       string
	HeadlessTemplate    string // default "{service}-{namespace}@{realm}"
	HeadlessHostTemplate string // NEW: e.g. "{service}-{namespace}.k8s"
	MaxIpaHostnameChars int    // default 64 (IPA host-add cap)
	ClusterTag string
}

func loadWebhookConfig() WebhookConfig {
	return WebhookConfig{
		ListenAddress:                    getEnvOrDefault("LISTEN_ADDRESS", ":8443"),
		KerberosRealm:                    mustGetEnv("KRB_REALM"),
		ClusterDnsSuffix:                 getEnvOrDefault("DNS_SUFFIX", ".k8s"), //was .svc.cluster.local
		BackendBaseUrl:                   mustGetEnv("BACKEND_URL"),
		BackendRequestTimeout:            getEnvDuration("BACKEND_TIMEOUT", 3*time.Second),
		BackendRetryCount:                getEnvInt("BACKEND_RETRIES", 2),
		AdmissionRequestTimeout:          getEnvDuration("WEBHOOK_TIMEOUT", 5*time.Second),
		LogAdmissionFailures:             getEnvBool("FAILURE_POLICY_LOG", true),
		BackendAuthMode:                  getEnvOrDefault("BACKEND_AUTH_MODE", "serviceaccount-jwt"),
		BackendAuthServiceAccountJwtPath: getEnvOrDefault("BACKEND_AUTH_SA_JWT_PATH", "/var/run/secrets/kubernetes.io/serviceaccount/token"),
		BackendBasicUserPath:             getEnvOrDefault("BACKEND_BASIC_USER_PATH", "/ambari-auth/username"),
		BackendBasicPassPath:             getEnvOrDefault("BACKEND_BASIC_PASS_PATH", "/ambari-auth/password"),
		BackendAccountUsername:           getEnvOrDefault("BACKEND_ACCOUNT_USERNAME", "webhook-user"),

		NeedsKeytabLabelKey: getEnvOrDefault("NEEDS_KEYTAB_LABEL_KEY", "security.clemlab.com/needs-keytab"),
		ServiceLabelKey:     getEnvOrDefault("SERVICE_LABEL_KEY", "security.clemlab.com/service"),

		PrincipalTemplate: getEnvOrDefault("PRINCIPAL_TEMPLATE", "{service}/{fqdn}@{realm}"),

		SecretPrefix:   getEnvOrDefault("SECRET_PREFIX", "keytab-"),
		SecretDataKey:  getEnvOrDefault("SECRET_DATA_KEY", "service.keytab"),
		VolumeName:     getEnvOrDefault("VOLUME_NAME", "keytab-volume"),
		MountPath:      getEnvOrDefault("MOUNT_PATH", "/etc/security/keytabs"),
		ContainerIndex: getEnvInt("MOUNT_CONTAINER_INDEX", 0),

		BackendClientCertPath: getEnvOrDefault("BACKEND_CLIENT_CERT_PATH", "/auth/client.crt"),
		BackendClientKeyPath:  getEnvOrDefault("BACKEND_CLIENT_KEY_PATH", "/auth/client.key"),
		BackendCaCertPath:     getEnvOrDefault("BACKEND_CA_CERT_PATH", "/auth/ca.crt"),

		// Defaults
		PrincipalFqdnMode:   getEnvOrDefault("PRINCIPAL_FQDN_MODE", "namespace"),
		PrincipalMode:       getEnvOrDefault("PRINCIPAL_MODE", "headless"),
		HeadlessTemplate:    getEnvOrDefault("HEADLESS_TEMPLATE", "{service}-{namespace}@{realm}"),
		HeadlessHostTemplate:  getEnvOrDefault("HEADLESS_HOST_TEMPLATE", "{service}-{namespace}.k8s"),
		MaxIpaHostnameChars: getEnvInt("IPA_HOSTNAME_MAX", 64),
		// Accept either CLUSTER_TAG or CLUSTER_NAME; first non-empty wins.
    ClusterTag:          getEnvFirstNonEmpty("CLUSTER_TAG", "CLUSTER_NAME"),
	}
}

func mustGetEnv(key string) string {
	value := os.Getenv(key)
	if value == "" {
		log.Fatalf("missing required environment variable %s", key)
	}
	return value
}
func getEnvOrDefault(key, defaultValue string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultValue
}
func getEnvDuration(key string, defaultValue time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
	}
	return defaultValue
}
func getEnvInt(key string, defaultValue int) int {
	if v := os.Getenv(key); v != "" {
		var parsed int
		_, _ = fmt.Sscanf(v, "%d", &parsed)
		if parsed >= 0 {
			return parsed
		}
	}
	return defaultValue
}
func getEnvBool(key string, defaultValue bool) bool {
	switch strings.ToLower(os.Getenv(key)) {
	case "1", "true", "yes", "y":
		return true
	case "0", "false", "no", "n":
		return false
	default:
		return defaultValue
	}
}

//
// ---------------------------
// Globals
// ---------------------------
//

var (
	globalConfig     WebhookConfig
	httpClient       *http.Client
	backendBasicUser string
	backendBasicPass string
)

// Header that Ambari's CSRF protection requires on POST, PUT and DELETE calls. Ambari accepts any
// value; "ambari" is the one its own clients send.
const (
	csrfHeaderName  = "X-Requested-By"
	csrfHeaderValue = "ambari"
)

// Proof that a keytab request comes from this webhook. Ambari's HTTPS port does not ask for client
// certificates, so the view cannot see the client certificate in the TLS handshake: each request
// carries the certificate and a signature made with its private key instead (the view checks both,
// see KeytabWebhookAuthenticator in the KDPS view). Both sides must build the signed text alike.
const (
	certificateHeader         = "X-KDPS-Webhook-Certificate"
	timestampHeader           = "X-KDPS-Webhook-Timestamp"
	signatureHeader           = "X-KDPS-Webhook-Signature"
	keytabRequestSignatureTag = "kdps-keytab-request"
	accountPasswordContext    = "kdps-keytab-webhook-ambari-login\n"
)

//
// ---------------------------
// Validation
// ---------------------------
//

func validateBackendAuthMode(mode string) error {
	m := strings.ToLower(strings.TrimSpace(mode))
	if m == "" || m == "none" {
		return nil
	}
	schemes := 0
	for _, scheme := range []string{"basic", "webhook-account", "serviceaccount-jwt"} {
		if hasAuthMode(m, scheme) {
			schemes++
		}
	}
	if schemes > 1 {
		return fmt.Errorf("BACKEND_AUTH_MODE can name only one of 'basic', 'webhook-account' and 'serviceaccount-jwt'")
	}
	// 'mtls' can be combined with any header scheme.
	return nil
}

// hasAuthMode tells whether the comma-separated BACKEND_AUTH_MODE names a scheme.
func hasAuthMode(mode, scheme string) bool {
	for _, part := range strings.Split(strings.ToLower(mode), ",") {
		if strings.TrimSpace(part) == scheme {
			return true
		}
	}
	return false
}

//
// ---------------------------
// main()
// ---------------------------
//

func main() {
	globalConfig = loadWebhookConfig()

	// Validate auth mode early
	if err := validateBackendAuthMode(globalConfig.BackendAuthMode); err != nil {
		log.Fatalf("invalid BACKEND_AUTH_MODE: %v", err)
	}

	// The webhook needs no access to the Kubernetes API: the pod comes in the AdmissionReview, and
	// the view writes the keytab Secret with its own account.
	httpClient = &http.Client{Timeout: globalConfig.BackendRequestTimeout}
	log.Printf("HTTP client for backend configured with timeout %s", globalConfig.BackendRequestTimeout)

	// Enable mTLS transport when BackendAuthMode contains "mtls"
	if hasAuthMode(globalConfig.BackendAuthMode, "mtls") {
		log.Printf("setting up mTLS HTTP client for backend communication")
		if c, err := buildMtlsHTTPClient(globalConfig); err != nil {
			log.Fatalf("failed to set up mTLS HTTP client for backend: %v", err)
		} else {
			httpClient = c
		}
	} else {
		log.Printf("backend authentication mode (no mTLS): %s", globalConfig.BackendAuthMode)
	}

	// Preload BASIC creds if requested
	if hasAuthMode(globalConfig.BackendAuthMode, "basic") {
		u, uErr := os.ReadFile(globalConfig.BackendBasicUserPath)
		p, pErr := os.ReadFile(globalConfig.BackendBasicPassPath)
		if uErr != nil || pErr != nil {
			log.Fatalf("basic auth requested but credentials not readable (user=%v, pass=%v)", uErr, pErr)
		}
		backendBasicUser = strings.TrimSpace(string(u))
		backendBasicPass = strings.TrimSpace(string(p))
		if backendBasicUser == "" || backendBasicPass == "" {
			log.Fatalf("basic auth requested but username/password are empty")
		}
		log.Printf("loaded basic auth credentials from %s and %s", globalConfig.BackendBasicUserPath, globalConfig.BackendBasicPassPath)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/mutate", withRequestTimeout(handleAdmissionMutate, globalConfig.AdmissionRequestTimeout))
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	mux.HandleFunc("/readyz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })

	server := &http.Server{
		Addr:      globalConfig.ListenAddress,
		Handler:   mux,
		TLSConfig: &tls.Config{MinVersion: tls.VersionTLS12},
	}

	go func() {
		log.Printf("webhook is listening on %s", globalConfig.ListenAddress)
		if err := server.ListenAndServeTLS("/tls/tls.crt", "/tls/tls.key"); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("webhook listener error: %v", err)
		}
	}()

	signalChannel := make(chan os.Signal, 1)
	signal.Notify(signalChannel, syscall.SIGINT, syscall.SIGTERM)
	<-signalChannel
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = server.Shutdown(ctx)
}

// Adds a deadline to every admission call so we never hang the apiserver.
func withRequestTimeout(handler http.HandlerFunc, timeout time.Duration) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), timeout)
		defer cancel()
		handler(w, r.WithContext(ctx))
	}
}

//
// ---------------------------
// Admission handler
// ---------------------------
//

func handleAdmissionMutate(w http.ResponseWriter, r *http.Request) {
	var admissionReview admissionv1.AdmissionReview
	requestBody, _ := io.ReadAll(r.Body)
	if err := json.Unmarshal(requestBody, &admissionReview); err != nil {
		writeAdmissionError(w, &admissionReview, fmt.Errorf("cannot decode AdmissionReview: %w", err))
		return
	}

	request := admissionReview.Request
	if request == nil || request.Kind.Kind != "Pod" {
		writeAdmissionAllow(w, &admissionReview, nil, nil)
		return
	}

	var incomingPod corev1.Pod
	if err := json.Unmarshal(request.Object.Raw, &incomingPod); err != nil {
		writeAdmissionError(w, &admissionReview, fmt.Errorf("cannot decode Pod object: %w", err))
		return
	}

	// Decide if this pod is targeted and what's the service name
	shouldMutate, serviceName := podTargeting(&incomingPod)
	if !shouldMutate {
		writeAdmissionAllow(w, &admissionReview, nil, nil)
		return
	}

	// The view only issues keytabs for the releases it deployed, so the request names the release.
	namespace := firstNonEmpty(incomingPod.Namespace, request.Namespace)
	releaseName := podReleaseName(&incomingPod)

	podName := incomingPod.Name
	log.Printf("Accepted incoming pod name %s", podName)
	// Compute FQDN based on mode, with safety fallbacks
	var fqdn string
	switch strings.ToLower(globalConfig.PrincipalFqdnMode) {
	case "pod":
		fqdn = computePodFqdn(&incomingPod, namespace, globalConfig.ClusterDnsSuffix)
	default:
		fqdn = computeServiceFqdn(serviceName, namespace, globalConfig.ClusterDnsSuffix)
	}

	// Decide principal (service vs headless)
	principal := decidePrincipal(serviceName, namespace, fqdn, globalConfig)
	log.Printf("Principal name for pod is %s", principal)

	secretName := deriveSecretName(&incomingPod, request.UID, globalConfig.SecretPrefix)
	log.Printf("secretName as been derived as %s", secretName)
	
	if err := enqueueKeytabCreation(r.Context(), principal, namespace, podName, secretName, globalConfig.SecretDataKey, releaseName); err != nil {
		writeAdmissionError(w, &admissionReview, fmt.Errorf("cannot enqueue keytab creation: %w", err))
		return
	}

	patchBytes, patchType, err := buildJSONPatchForVolumeAndMount(&incomingPod, secretName)

	if err != nil {
		log.Printf("Error building JSONPatch: %v", err)
		writeAdmissionError(w, &admissionReview, fmt.Errorf("cannot build JSONPatch: %w", err))
		return
	}

	log.Printf("Patch to be applied: %s", string(patchBytes))
	log.Printf("PatchType: %v", patchType)
	writeAdmissionAllow(w, &admissionReview, &patchType, patchBytes)
}

// podReleaseName is the Helm release of the pod, from the labels charts commonly set on their
// pods: the recommended app.kubernetes.io/instance first, then the older helm.sh/release and
// release labels. Some charts (Superset's, for one) only set the last.
func podReleaseName(pod *corev1.Pod) string {
	labels := pod.GetLabels()
	return firstNonEmpty(labels["app.kubernetes.io/instance"], labels["helm.sh/release"], labels["release"])
}

//
// Targeting logic: generic
//
func podTargeting(pod *corev1.Pod) (bool, string) {
	labels := pod.GetLabels()
	if labels == nil {
		return false, ""
	}

	needsKeytab := strings.EqualFold(labels[globalConfig.NeedsKeytabLabelKey], "true")
	serviceName := labels[globalConfig.ServiceLabelKey]

	// Require BOTH labels to be set
	if needsKeytab && serviceName != "" {
		return true, serviceName
	}

	return false, ""
}

func computePodFqdn(pod *corev1.Pod, namespace, dnsSuffix string) string {
	if pod.Spec.Subdomain != "" {
		return fmt.Sprintf("%s.%s.%s%s", pod.Name, pod.Spec.Subdomain, namespace, normalizeSuffix(dnsSuffix))
	}
	return fmt.Sprintf("%s.%s%s", pod.Name, namespace, normalizeSuffix(dnsSuffix))
}

// compute Service FQDN: <service>.<namespace><suffix>
func computeServiceFqdn(service, namespace, dnsSuffix string) string {
	suffix := normalizeSuffix(dnsSuffix)
	return fmt.Sprintf("%s.%s%s", sanitizeName(service), namespace, suffix)
}

func normalizeSuffix(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	s = strings.TrimPrefix(s, ".")
	return "." + s
}

func buildPrincipal(service, fqdn, realm string) string {
	tpl := globalConfig.PrincipalTemplate
	if tpl == "" {
		tpl = "{service}/{fqdn}@{realm}"
	}
	out := strings.ReplaceAll(tpl, "{service}", service)
	out = strings.ReplaceAll(out, "{fqdn}", fqdn)
	out = strings.ReplaceAll(out, "{realm}", realm)
	return out
}

//
// ---------------------------
// Backend and Secret helpers
// ---------------------------
//

// keytabRequest is the body of a keytab request to the view.
type keytabRequest struct {
	Principal       string `json:"principal"`
	Namespace       string `json:"namespace"`
	PodName         string `json:"podName"`
	SecretName      string `json:"secretName"`
	KeyNameInSecret string `json:"keyNameInSecret"`
	ReleaseName     string `json:"releaseName,omitempty"`
}

func enqueueKeytabCreation(ctx context.Context, principal, namespace, podName, secretName, keyNameInSecret, releaseName string) error {
	url := strings.TrimRight(globalConfig.BackendBaseUrl, "/") + "/kerberos/keytab"

	payload := keytabRequest{
		Principal:       principal,
		Namespace:       namespace,
		PodName:         podName,
		SecretName:      secretName,
		KeyNameInSecret: keyNameInSecret,
		ReleaseName:     releaseName,
	}
	body, _ := json.Marshal(payload)

	req, err := newBackendRequest(ctx, http.MethodPost, url, strings.NewReader(string(body)))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if err := attachBackendAuth(req); err != nil {
		return err
	}
	if hasAuthMode(globalConfig.BackendAuthMode, "mtls") {
		identity, err := loadClientIdentity(globalConfig)
		if err != nil {
			return err
		}
		if err := signKeytabRequest(req, identity, payload, time.Now()); err != nil {
			return err
		}
	}

	resp, err := httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusAccepted && resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("backend %s => %d: %s", url, resp.StatusCode, string(b))
	}
	return nil
}

func requestKeytabFromBackend(ctx context.Context, principal string) ([]byte, error) {
	var lastError error
	for attempt := 0; attempt <= globalConfig.BackendRetryCount; attempt++ {
		httpRequest, err := newBackendRequest(ctx, http.MethodPost, globalConfig.BackendBaseUrl+"/keytab", nil)
		if err != nil {
			return nil, err
		}
		query := httpRequest.URL.Query()
		query.Add("principal", principal)
		httpRequest.URL.RawQuery = query.Encode()

		if err := attachBackendAuth(httpRequest); err != nil {
			return nil, err
		}

		httpResponse, err := httpClient.Do(httpRequest)
		if err != nil {
			lastError = err
			continue
		}
		defer httpResponse.Body.Close()

		if httpResponse.StatusCode != http.StatusOK {
			responseBody, _ := io.ReadAll(httpResponse.Body)
			lastError = fmt.Errorf("backend status=%d body=%s", httpResponse.StatusCode, string(responseBody))
			continue
		}
		return io.ReadAll(httpResponse.Body)
	}
	return nil, lastError
}

// newBackendRequest builds a request to the Ambari backend. The backend is the KDPS view, served
// under Ambari's REST API, which refuses state-changing calls without an X-Requested-By header (its
// CSRF protection). Every backend call goes through here so that none of them can miss the header.
func newBackendRequest(ctx context.Context, method, url string, body io.Reader) (*http.Request, error) {
	req, err := http.NewRequestWithContext(ctx, method, url, body)
	if err != nil {
		return nil, err
	}
	req.Header.Set(csrfHeaderName, csrfHeaderValue)
	return req, nil
}

func attachBackendAuth(req *http.Request) error {
	mode := strings.ToLower(strings.TrimSpace(globalConfig.BackendAuthMode))
	if mode == "" || mode == "none" {
		return nil
	}

	// mTLS is transport-level; no header needed here (handled in httpClient)
	if hasAuthMode(mode, "webhook-account") {
		// The view's Ambari account for this webhook: its password is derived from the client key,
		// read on each call so that a renewed certificate is picked up without a restart.
		identity, err := loadClientIdentity(globalConfig)
		if err != nil {
			return err
		}
		creds := globalConfig.BackendAccountUsername + ":" + webhookAccountPassword(identity.keyDER)
		req.Header.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(creds)))
		return nil
	}

	if hasAuthMode(mode, "basic") {
		if backendBasicUser == "" || backendBasicPass == "" {
			return fmt.Errorf("basic auth requested but credentials are empty")
		}
		creds := backendBasicUser + ":" + backendBasicPass
		log.Printf("backend auth: attaching Basic Authorization header")
		req.Header.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(creds)))
		return nil
	}

	if hasAuthMode(mode, "serviceaccount-jwt") {
		tokenBytes, err := os.ReadFile(globalConfig.BackendAuthServiceAccountJwtPath)
		if err != nil {
			return fmt.Errorf("cannot read service account JWT: %w", err)
		}
		log.Printf("backend auth: attaching ServiceAccount JWT Authorization header")
		req.Header.Set("Authorization", "Bearer "+strings.TrimSpace(string(tokenBytes)))
		return nil
	}

	if hasAuthMode(mode, "mtls") {
		// headerless; already configured on the client transport
		return nil
	}

	return fmt.Errorf("unknown BACKEND_AUTH_MODE: %s", globalConfig.BackendAuthMode)
}

// clientIdentity is the client certificate the view issued to the webhook, and its private key.
type clientIdentity struct {
	certDER []byte
	keyDER  []byte
	signer  crypto.Signer
}

// loadClientIdentity reads the client certificate and key the view issued (mounted from its Secret).
func loadClientIdentity(cfg WebhookConfig) (*clientIdentity, error) {
	certPEM, err := os.ReadFile(cfg.BackendClientCertPath)
	if err != nil {
		return nil, fmt.Errorf("read client certificate: %w", err)
	}
	keyPEM, err := os.ReadFile(cfg.BackendClientKeyPath)
	if err != nil {
		return nil, fmt.Errorf("read client key: %w", err)
	}
	certBlock, _ := pem.Decode(certPEM)
	if certBlock == nil {
		return nil, fmt.Errorf("client certificate %s is not PEM", cfg.BackendClientCertPath)
	}
	keyBlock, _ := pem.Decode(keyPEM)
	if keyBlock == nil {
		return nil, fmt.Errorf("client key %s is not PEM", cfg.BackendClientKeyPath)
	}
	key, err := x509.ParsePKCS8PrivateKey(keyBlock.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse client key: %w", err)
	}
	signer, ok := key.(crypto.Signer)
	if !ok {
		return nil, fmt.Errorf("client key cannot sign")
	}
	return &clientIdentity{certDER: certBlock.Bytes, keyDER: keyBlock.Bytes, signer: signer}, nil
}

// keytabRequestSigningInput is the text the webhook signs: a tag, the timestamp and each field of
// the request, one per line, in a fixed order.
func keytabRequestSigningInput(timestamp string, p keytabRequest) []byte {
	return []byte(strings.Join([]string{
		keytabRequestSignatureTag, timestamp,
		p.Principal, p.Namespace, p.PodName, p.SecretName, p.KeyNameInSecret, p.ReleaseName,
	}, "\n"))
}

// signKeytabRequest adds the client certificate, the time and the signature of the request.
func signKeytabRequest(req *http.Request, identity *clientIdentity, p keytabRequest, now time.Time) error {
	timestamp := strconv.FormatInt(now.Unix(), 10)
	digest := sha256.Sum256(keytabRequestSigningInput(timestamp, p))
	signature, err := identity.signer.Sign(rand.Reader, digest[:], crypto.SHA256)
	if err != nil {
		return fmt.Errorf("sign keytab request: %w", err)
	}
	req.Header.Set(certificateHeader, base64.StdEncoding.EncodeToString(identity.certDER))
	req.Header.Set(timestampHeader, timestamp)
	req.Header.Set(signatureHeader, base64.StdEncoding.EncodeToString(signature))
	return nil
}

// webhookAccountPassword derives the password of the webhook's Ambari account from its client key,
// as the view does when it creates the account: nothing but the key is needed, so no password is
// stored in Kubernetes.
func webhookAccountPassword(keyDER []byte) string {
	h := sha256.New()
	h.Write([]byte(accountPasswordContext))
	h.Write(keyDER)
	return base64.RawURLEncoding.EncodeToString(h.Sum(nil))
}

//
// ---------------------------
// JSON Patch builder (RFC6902)
// ---------------------------
//

type jsonPatchOp struct {
	Op    string      `json:"op"`
	Path  string      `json:"path"`
	Value interface{} `json:"value,omitempty"`
}

func buildJSONPatchForVolumeAndMount(pod *corev1.Pod, secretName string) ([]byte, admissionv1.PatchType, error) {
	if len(pod.Spec.Containers) == 0 {
		return nil, "", fmt.Errorf("pod has no containers to mount the keytab")
	}
	if globalConfig.ContainerIndex >= len(pod.Spec.Containers) {
		return nil, "", fmt.Errorf("container index %d out of range", globalConfig.ContainerIndex)
	}

	vol := corev1.Volume{
		Name: globalConfig.VolumeName,
		VolumeSource: corev1.VolumeSource{
			Secret: &corev1.SecretVolumeSource{
				SecretName: secretName,
				Optional:   ptr(false),
			},
		},
	}
	vm := corev1.VolumeMount{
		Name:      globalConfig.VolumeName,
		MountPath: globalConfig.MountPath,
		ReadOnly:  true,
	}

	var ops []jsonPatchOp

	// 1) Volumes: add the secret-backed volume once at pod level
	if pod.Spec.Volumes == nil {
		ops = append(ops, jsonPatchOp{
			Op:   "add",
			Path: "/spec/volumes",
			Value: []corev1.Volume{
				vol,
			},
		})
	} else {
		ops = append(ops, jsonPatchOp{
			Op:   "add",
			Path: "/spec/volumes/-",
			Value: vol,
		})
	}

	// 2) VolumeMounts: mount into ALL containers in the pod
	for idx, c := range pod.Spec.Containers {
		if c.VolumeMounts == nil {
			// no volumeMounts array yet for this container
			ops = append(ops, jsonPatchOp{
				Op:   "add",
				Path: fmt.Sprintf("/spec/containers/%d/volumeMounts", idx),
				Value: []corev1.VolumeMount{
					vm,
				},
			})
		} else {
			// append to existing volumeMounts
			ops = append(ops, jsonPatchOp{
				Op:    "add",
				Path:  fmt.Sprintf("/spec/containers/%d/volumeMounts/-", idx),
				Value: vm,
			})
		}
	}

	// 3) Debug annotations (unchanged)
	if pod.Annotations == nil {
		ops = append(ops, jsonPatchOp{
			Op:   "add",
			Path: "/metadata/annotations",
			Value: map[string]string{
				"clemlab.com/keytab-secret":    secretName,
				"clemlab.com/keytab-volume":    globalConfig.VolumeName,
				"clemlab.com/keytab-mountpath": globalConfig.MountPath,
			},
		})
	} else {
		for k, v := range map[string]string{
			"clemlab.com/keytab-secret":    secretName,
			"clemlab.com/keytab-volume":    globalConfig.VolumeName,
			"clemlab.com/keytab-mountpath": globalConfig.MountPath,
		} {
			key := strings.ReplaceAll(k, "/", "~1")
			ops = append(ops, jsonPatchOp{
				Op:    "add",
				Path:  "/metadata/annotations/" + key,
				Value: v,
			})
		}
	}

	patchBytes, err := json.Marshal(ops)
	if err == nil {
		log.Printf("JSONPatch ops count=%d bytes=%d", len(ops), len(patchBytes))
	}
	return patchBytes, admissionv1.PatchTypeJSONPatch, err
}

//
// ---------------------------
// Admission response helpers
// ---------------------------
//

func writeAdmissionAllow(w http.ResponseWriter, review *admissionv1.AdmissionReview, patchType *admissionv1.PatchType, patch []byte) {
	w.Header().Set("Content-Type", "application/json")
	log.Printf("Writing admission allow response")
	resp := &admissionv1.AdmissionResponse{
			UID:     review.Request.UID,
			Allowed: true,
	}
	log.Printf("Patch length: %d", len(patch))
	if patch != nil && patchType != nil {
			log.Printf("Setting patch in admission response")
			resp.PatchType = patchType
			resp.Patch = patch // []byte -> base64 in JSON automatically
	}
	log.Printf("Encoding admission response struct")
	out := admissionv1.AdmissionReview{
			TypeMeta: meta.TypeMeta{
					APIVersion: "admission.k8s.io/v1",
					Kind:       "AdmissionReview",
			},
			Response: resp,
	}
	log.Printf("Encoding admission response")
	_ = json.NewEncoder(w).Encode(out)

	log.Printf("admission allowed for UID %s", review.Request.UID)
	log.Printf("admission patch: %s", string(patch))
	log.Printf("admission patch type: %v", patchType)
}

func writeAdmissionError(w http.ResponseWriter, review *admissionv1.AdmissionReview, err error) {
	log.Printf("Writing admission error response: %v", err)
	if globalConfig.LogAdmissionFailures {
			log.Printf("admission denied: %v", err)
	}
	w.Header().Set("Content-Type", "application/json")

	resp := &admissionv1.AdmissionResponse{
			UID:     review.Request.UID,
			Allowed: false,
			Result:  &meta.Status{Message: err.Error()},
	}

	out := admissionv1.AdmissionReview{
			TypeMeta: meta.TypeMeta{
					APIVersion: "admission.k8s.io/v1",
					Kind:       "AdmissionReview",
			},
			Response: resp,
	}
	_ = json.NewEncoder(w).Encode(out)

	log.Printf("admission denied for UID %s", review.Request.UID)
}

//
// ---------------------------
// Small utilities
// ---------------------------
//

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

func ptr[T any](v T) *T { return &v }

func sanitizeName(s string) string {
	// very light cleanup for k8s DNS-1123 name constraints
	s = strings.ToLower(s)
	s = strings.ReplaceAll(s, "_", "-")
	s = strings.ReplaceAll(s, "/", "-")
	return s
}

// --- mTLS helper (backend) ---

func buildMtlsHTTPClient(cfg WebhookConfig) (*http.Client, error) {
	cert, err := tls.LoadX509KeyPair(cfg.BackendClientCertPath, cfg.BackendClientKeyPath)
	if err != nil {
		return nil, fmt.Errorf("load client cert/key: %w", err)
	}

	log.Printf("loaded client certificate with %d certs", len(cert.Certificate))
	caPEM, err := os.ReadFile(cfg.BackendCaCertPath)
	log.Printf("read CA cert file %s (%d bytes)", cfg.BackendCaCertPath, len(caPEM))
	if err != nil {
		return nil, fmt.Errorf("read CA cert: %w", err)
	}
	caPool := x509.NewCertPool()
	if ok := caPool.AppendCertsFromPEM(caPEM); !ok {
		return nil, fmt.Errorf("append CA cert: invalid PEM")
	}

	tlsConf := &tls.Config{
		MinVersion:   tls.VersionTLS12,
		Certificates: []tls.Certificate{cert},
		RootCAs:      caPool,
	}
	tr := &http.Transport{
		TLSClientConfig:     tlsConf,
		ForceAttemptHTTP2:   true,
		DisableCompression:  false,
		TLSHandshakeTimeout: 10 * time.Second,
	}
	log.Printf("mTLS HTTP client configured with TLS version >= %d", tlsConf.MinVersion)
	log.Printf("mTLS HTTP client using CA pool with %d certs", len(caPool.Subjects()))
	return &http.Client{
		Timeout:   cfg.BackendRequestTimeout,
		Transport: tr,
	}, nil
}

// Principal decision & builders
func decidePrincipal(service, namespace, fqdn string, cfg WebhookConfig) string {
    mode := strings.ToLower(strings.TrimSpace(cfg.PrincipalMode))
    switch mode {
    case "headless":
        // true headless (no slash) → Ambari won't ensure host; IPA should create a user principal
        return buildHeadlessPrincipal(service, namespace, cfg.ClusterTag, cfg.KerberosRealm, cfg.HeadlessTemplate)
    case "auto":
        if len(fqdn) > cfg.MaxIpaHostnameChars {
            log.Printf("FQDN '%s' length=%d > IPA cap=%d → using HEADLESS principal",
                fqdn, len(fqdn), cfg.MaxIpaHostnameChars)
            return buildHeadlessPrincipal(service, namespace, cfg.ClusterTag, cfg.KerberosRealm, cfg.HeadlessTemplate)
        }
        // service principal (slash)
        return buildPrincipal(service, fqdn, cfg.KerberosRealm)
		case "service":
				return buildPrincipal(service, fqdn, cfg.KerberosRealm)
    default: // "service"
        return buildPrincipal(service, fqdn, cfg.KerberosRealm)
    }
}

func buildSyntheticHost(service, namespace, tpl string) string {
    if tpl == "" { tpl = "{service}-{namespace}.k8s" }
    out := strings.ReplaceAll(tpl, "{service}", sanitizeName(service))
    out = strings.ReplaceAll(out, "{namespace}", sanitizeName(namespace))
    // Make sure final label(s) are DNS-safe and short; last safety trim:
    out = strings.Trim(out, ".")
    // Disallow underscores/slashes already handled by sanitizeName; ensure no spaces:
    out = strings.ReplaceAll(out, " ", "-")
    return out
}

func tidyKerbName(s string) string {
    // collapse multiple dashes
    for strings.Contains(s, "--") {
        s = strings.ReplaceAll(s, "--", "-")
    }
    // trim dangling separators next to '@'
    s = strings.TrimSuffix(s, "-@")
    s = strings.TrimPrefix(s, "@")
    s = strings.Trim(s, "-")
    return s
}

func buildHeadlessPrincipal(service, namespace, cluster, realm, tpl string) string {
    if tpl == "" {
        tpl = "{service}-{namespace}-{cluster}@{realm}"
    }
    out := strings.ReplaceAll(tpl, "{service}", sanitizeName(service))
    out = strings.ReplaceAll(out, "{namespace}", sanitizeName(namespace))
    out = strings.ReplaceAll(out, "{cluster}", sanitizeName(cluster)) // empty ok
    out = strings.ReplaceAll(out, "{realm}", realm)
    return tidyKerbName(out)
}

func getEnvFirstNonEmpty(keys ...string) string {
    for _, k := range keys {
        if v := strings.TrimSpace(os.Getenv(k)); v != "" {
            return v
        }
    }
    return ""
}

// --- helper to ensure start/end are alphanumeric
var rfc1123Trim = regexp.MustCompile(`^[^a-z0-9]+|[^a-z0-9]+$`)

func deriveSecretName(pod *corev1.Pod, reqUID types.UID, prefix string) string {
    base := pod.Name
    if base == "" {
        // Use generateName if present
        base = pod.GenerateName
        if base == "" {
            base = "pod"
        }
        // generateName typically ends with '-', trim it
        base = strings.TrimSuffix(base, "-")

        // Add a short, deterministic suffix from the AdmissionRequest UID
        suf := strings.ToLower(string(reqUID))
        if len(suf) > 8 {
            suf = suf[:8]
        }
        base = base + "-" + suf
    }

    // Compose, sanitize, and enforce RFC1123 start/end
    name := sanitizeName(prefix + base)
    name = rfc1123Trim.ReplaceAllString(name, "") // trim non-alnum at start/end
    if name == "" {
        // absolute fallback — should never happen
        suf := strings.ToLower(string(reqUID))
        if len(suf) > 8 { suf = suf[:8] }
        name = "keytab-" + suf
    }
    return name
}
