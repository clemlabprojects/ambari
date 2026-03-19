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
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"regexp"
	"strings"
	"syscall"
	"time"

	admissionv1 "k8s.io/api/admission/v1"
	corev1 "k8s.io/api/core/v1"
	meta "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
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
	BackendAuthMode                  string // "none" | "serviceaccount-jwt" | "basic" | "mtls" | combos with mtls (e.g. "mtls,basic")
	BackendAuthServiceAccountJwtPath string
	BackendBasicUserPath             string // ambari mutating webhook username
	BackendBasicPassPath             string // ambari mutating webhook password

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

	CorrelationConfigMap string // name of ConfigMap holding <release>.commandId (default: k8s-view-metadata)

	parentCommandId string // derived per request
	releaseName     string // derived per request
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

		CorrelationConfigMap: getEnvOrDefault("CORRELATION_CONFIGMAP", "k8s-view-metadata"),
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
	kubeClient       *kubernetes.Clientset
	httpClient       *http.Client
	backendBasicUser string
	backendBasicPass string
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
	hasBasic := strings.Contains(m, "basic")
	hasJWT := strings.Contains(m, "serviceaccount-jwt")
	if hasBasic && hasJWT {
		return fmt.Errorf("BACKEND_AUTH_MODE cannot combine 'basic' and 'serviceaccount-jwt'")
	}
	// 'mtls' can be combined with either header scheme.
	return nil
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

	inClusterConfig, err := rest.InClusterConfig()
	if err != nil {
		log.Fatalf("cannot load in-cluster Kubernetes config: %v", err)
	}
	kubeClient, err = kubernetes.NewForConfig(inClusterConfig)
	log.Printf("Kubernetes client configured for host %s", inClusterConfig.Host)
	if err != nil {
		log.Fatalf("cannot create Kubernetes client: %v", err)
	}

	httpClient = &http.Client{Timeout: globalConfig.BackendRequestTimeout}
	log.Printf("HTTP client for backend configured with timeout %s", globalConfig.BackendRequestTimeout)

	// Enable mTLS transport when BackendAuthMode contains "mtls"
	if strings.Contains(strings.ToLower(globalConfig.BackendAuthMode), "mtls") {
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
	if strings.Contains(strings.ToLower(globalConfig.BackendAuthMode), "basic") {
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

	// Derive release name from common Helm labels and try to fetch parent command id from ConfigMap
	namespace := firstNonEmpty(incomingPod.Namespace, request.Namespace)
	globalConfig.releaseName = firstNonEmpty(incomingPod.Labels["app.kubernetes.io/instance"], incomingPod.Labels["helm.sh/release"])
	globalConfig.parentCommandId = ""
	if globalConfig.releaseName != "" {
		if cmdId, err := lookupCommandCorrelation(r.Context(), namespace, globalConfig.releaseName); err == nil {
			globalConfig.parentCommandId = cmdId
		} else {
			log.Printf("Could not find correlation for release %s in namespace %s: %v", globalConfig.releaseName, namespace, err)
		}
	}

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
	
	if err := enqueueKeytabCreation(r.Context(), principal, namespace, podName, secretName, globalConfig.SecretDataKey); err != nil {
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

// lookupCommandCorrelation reads a ConfigMap (default k8s-view-metadata) in the namespace
// and returns the commandId stored under "<releaseName>.commandId".
func lookupCommandCorrelation(ctx context.Context, namespace, releaseName string) (string, error) {
	cmName := firstNonEmpty(globalConfig.CorrelationConfigMap, "k8s-view-metadata")
	cm, err := kubeClient.CoreV1().ConfigMaps(namespace).Get(ctx, cmName, meta.GetOptions{})
	if err != nil {
		return "", err
	}
	key := fmt.Sprintf("%s.commandId", releaseName)
	if v, ok := cm.Data[key]; ok && strings.TrimSpace(v) != "" {
		return strings.TrimSpace(v), nil
	}
	return "", fmt.Errorf("key %s not found in ConfigMap %s", key, cmName)
}

//
// ---------------------------
// Backend and Secret helpers
// ---------------------------
//

func enqueueKeytabCreation(ctx context.Context, principal, namespace, podName, secretName, keyNameInSecret string) error {
	url := strings.TrimRight(globalConfig.BackendBaseUrl, "/") + "/kerberos/keytab"

	payload := map[string]string{
		"principal":       principal,
		"namespace":       namespace,
		"podName":         podName,
		"secretName":      secretName,
		"keyNameInSecret": keyNameInSecret,
	}
	// Optional correlation to a Helm deploy root command so the backend can nest steps properly.
	if globalConfig.parentCommandId != "" {
		payload["parentCommandId"] = globalConfig.parentCommandId
	}
	if globalConfig.releaseName != "" {
		payload["releaseName"] = globalConfig.releaseName
	}
	body, _ := json.Marshal(payload)

	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, url, strings.NewReader(string(body)))
	req.Header.Set("Content-Type", "application/json")
	if err := attachBackendAuth(req); err != nil {
		return err
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
		httpRequest, _ := http.NewRequestWithContext(ctx, http.MethodPost, globalConfig.BackendBaseUrl+"/keytab", nil)
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

func attachBackendAuth(req *http.Request) error {
	mode := strings.ToLower(strings.TrimSpace(globalConfig.BackendAuthMode))
	if mode == "" || mode == "none" {
		return nil
	}

	// mTLS is transport-level; no header needed here (handled in httpClient)
	if strings.Contains(mode, "basic") {
		if backendBasicUser == "" || backendBasicPass == "" {
			return fmt.Errorf("basic auth requested but credentials are empty")
		}
		creds := backendBasicUser + ":" + backendBasicPass
		log.Printf("backend auth: attaching Basic Authorization header")
		req.Header.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(creds)))
		return nil
	}

	if strings.Contains(mode, "serviceaccount-jwt") {
		tokenBytes, err := os.ReadFile(globalConfig.BackendAuthServiceAccountJwtPath)
		if err != nil {
			return fmt.Errorf("cannot read service account JWT: %w", err)
		}
		log.Printf("backend auth: attaching ServiceAccount JWT Authorization header")
		req.Header.Set("Authorization", "Bearer "+strings.TrimSpace(string(tokenBytes)))
		return nil
	}

	if strings.Contains(mode, "mtls") {
		// headerless; already configured on the client transport
		return nil
	}

	return fmt.Errorf("unknown BACKEND_AUTH_MODE: %s", globalConfig.BackendAuthMode)
}

func createSecretOwnedByPod(ctx context.Context, namespace, podName string, podUid types.UID, secretName string, keytabBytes []byte) error {
	secret := &corev1.Secret{
		ObjectMeta: meta.ObjectMeta{
			Name:      secretName,
			Namespace: namespace,
			Labels: map[string]string{
				"managed-by": "clemlab-keytab-webhook",
				"pod-name":   podName,
			},
			OwnerReferences: []meta.OwnerReference{{
				APIVersion: "v1",
				Kind:       "Pod",
				Name:       podName,
				UID:        podUid,
			}},
		},
		Immutable: ptr(true),
		Type:      corev1.SecretTypeOpaque,
		Data:      map[string][]byte{globalConfig.SecretDataKey: keytabBytes},
	}
	_, err := kubeClient.CoreV1().Secrets(namespace).Create(ctx, secret, meta.CreateOptions{})
	if err != nil && !strings.Contains(err.Error(), "already exists") {
		return err
	}
	return nil
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
