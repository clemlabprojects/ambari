// cmd/webhook/main.go
package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
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
	ListenAddress                     string        // e.g. ":8443"
	KerberosRealm                     string        // e.g. "EXAMPLE.COM"
	ClusterDnsSuffix                  string        // e.g. ".svc.cluster.local"
	BackendBaseUrl                    string        // e.g. "https://ambari-backend.svc:8443"
	BackendRequestTimeout             time.Duration // e.g. 3s
	BackendRetryCount                 int           // e.g. 2
	AdmissionRequestTimeout           time.Duration // e.g. 5s
	LogAdmissionFailures              bool
	BackendAuthMode                   string // "none" | "serviceaccount-jwt" | "mtls"
	BackendAuthServiceAccountJwtPath  string

	// Genericization knobs
	NeedsKeytabLabelKey string // label key to force keytab, default: security.clemlab.com/needs-keytab
	ServiceLabelKey     string // label key carrying service name, default: clemlab-webhook-service

	PrincipalTemplate string // default: "{service}/{fqdn}@{realm}"

	SecretPrefix string // default: "keytab-"
	SecretDataKey string // default: "service.keytab"
	VolumeName string     // default: "keytab-volume"
	MountPath string      // default: "/etc/security/keytabs"
	ContainerIndex int    // default: 0
}

func loadWebhookConfig() WebhookConfig {
	return WebhookConfig{
		ListenAddress:                    getEnvOrDefault("LISTEN_ADDRESS", ":8443"),
		KerberosRealm:                    mustGetEnv("KRB_REALM"),
		ClusterDnsSuffix:                 getEnvOrDefault("DNS_SUFFIX", ".svc.cluster.local"),
		BackendBaseUrl:                   mustGetEnv("BACKEND_URL"),
		BackendRequestTimeout:            getEnvDuration("BACKEND_TIMEOUT", 3*time.Second),
		BackendRetryCount:                getEnvInt("BACKEND_RETRIES", 2),
		AdmissionRequestTimeout:          getEnvDuration("WEBHOOK_TIMEOUT", 5*time.Second),
		LogAdmissionFailures:             getEnvBool("FAILURE_POLICY_LOG", true),
		BackendAuthMode:                  getEnvOrDefault("BACKEND_AUTH_MODE", "serviceaccount-jwt"),
		BackendAuthServiceAccountJwtPath: getEnvOrDefault("BACKEND_AUTH_SA_JWT_PATH", "/var/run/secrets/kubernetes.io/serviceaccount/token"),

		NeedsKeytabLabelKey: getEnvOrDefault("NEEDS_KEYTAB_LABEL_KEY", "security.clemlab.com/needs-keytab"),
		ServiceLabelKey:     getEnvOrDefault("SERVICE_LABEL_KEY", "security.clemlab.com/webhooks-enabled"),

		PrincipalTemplate: getEnvOrDefault("PRINCIPAL_TEMPLATE", "{service}/{fqdn}@{realm}"),

		SecretPrefix:   getEnvOrDefault("SECRET_PREFIX", "keytab-"),
		SecretDataKey:  getEnvOrDefault("SECRET_DATA_KEY", "service.keytab"),
		VolumeName:     getEnvOrDefault("VOLUME_NAME", "keytab-volume"),
		MountPath:      getEnvOrDefault("MOUNT_PATH", "/etc/security/keytabs"),
		ContainerIndex: getEnvInt("MOUNT_CONTAINER_INDEX", 0),
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
	globalConfig WebhookConfig
	kubeClient   *kubernetes.Clientset
	httpClient   *http.Client
)

//
// ---------------------------
// main()
// ---------------------------
//

func main() {
	globalConfig = loadWebhookConfig()

	inClusterConfig, err := rest.InClusterConfig()
	if err != nil {
		log.Fatalf("cannot load in-cluster Kubernetes config: %v", err)
	}
	kubeClient, err = kubernetes.NewForConfig(inClusterConfig)
	if err != nil {
		log.Fatalf("cannot create Kubernetes client: %v", err)
	}

	httpClient = &http.Client{Timeout: globalConfig.BackendRequestTimeout}

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
/* Admission handler */
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

	namespace := firstNonEmpty(incomingPod.Namespace, request.Namespace)
	podName := incomingPod.Name
	fullQualifiedDomainName := computePodFqdn(&incomingPod, namespace, globalConfig.ClusterDnsSuffix)

	// Principal like "{service}/{fqdn}@{realm}"
	principal := buildPrincipal(serviceName, fullQualifiedDomainName, globalConfig.KerberosRealm)

	secretName := sanitizeName(globalConfig.SecretPrefix + podName)

	if err := enqueueKeytabCreation(r.Context(), principal, namespace, podName, secretName, globalConfig.SecretDataKey); err != nil {
		writeAdmissionError(w, &admissionReview, fmt.Errorf("cannot enqueue keytab creation: %w", err))
		return
	}

	patchBytes, patchType, err := buildJSONPatchForVolumeAndMount(&incomingPod, secretName)
	if err != nil {
		writeAdmissionError(w, &admissionReview, fmt.Errorf("cannot build JSONPatch: %w", err))
		return
	}


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
		return fmt.Sprintf("%s.%s.%s%s", pod.Name, pod.Spec.Subdomain, namespace, dnsSuffix)
	}
	return fmt.Sprintf("%s.%s%s", pod.Name, namespace, dnsSuffix)
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

func enqueueKeytabCreation(ctx context.Context, principal, namespace, podName, secretName, keyNameInSecret string) error {
	// globalConfig.BackendBaseUrl should already be your ".../resources/api/commands" base
	// e.g. https://.../api/v1/views/.../resources/api/commands
	url := strings.TrimRight(globalConfig.BackendBaseUrl, "/") + "/kerberos/keytab"

	// Send as JSON so the backend can grow without re-parsing query strings
	payload := map[string]string{
		"principal":       principal,
		"namespace":       namespace,
		"podName":         podName,
		"secretName":      secretName,
		"keyNameInSecret": keyNameInSecret, // e.g. "service.keytab"
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
	switch strings.ToLower(globalConfig.BackendAuthMode) {
	case "none":
		return nil
	case "serviceaccount-jwt":
		tokenBytes, err := os.ReadFile(globalConfig.BackendAuthServiceAccountJwtPath)
		if err != nil {
			return fmt.Errorf("cannot read service account JWT: %w", err)
		}
		req.Header.Set("Authorization", "Bearer "+strings.TrimSpace(string(tokenBytes)))
		return nil
	case "mtls":
		// For mTLS, set up a custom Transport at process start (omitted here for brevity).
		return nil
	default:
		return fmt.Errorf("unknown BACKEND_AUTH_MODE: %s", globalConfig.BackendAuthMode)
	}
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
		Immutable: ptr(true), // <-- correct placement
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

	// Add or append volume
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
			Op:    "add",
			Path:  "/spec/volumes/-",
			Value: vol,
		})
	}

	// Add or append volumeMount to selected container
	cIdx := globalConfig.ContainerIndex
	if pod.Spec.Containers[cIdx].VolumeMounts == nil {
		ops = append(ops, jsonPatchOp{
			Op:   "add",
			Path: fmt.Sprintf("/spec/containers/%d/volumeMounts", cIdx),
			Value: []corev1.VolumeMount{
				vm,
			},
		})
	} else {
		ops = append(ops, jsonPatchOp{
			Op:    "add",
			Path:  fmt.Sprintf("/spec/containers/%d/volumeMounts/-", cIdx),
			Value: vm,
		})
	}

	patchBytes, err := json.Marshal(ops)
	return patchBytes, admissionv1.PatchTypeJSONPatch, err
}

//
// ---------------------------
// Admission response helpers
// ---------------------------
//

func writeAdmissionAllow(w http.ResponseWriter, review *admissionv1.AdmissionReview, patchType *admissionv1.PatchType, patch []byte) {
	response := &admissionv1.AdmissionResponse{
		UID:     review.Request.UID,
		Allowed: true,
	}
	if patch != nil && patchType != nil {
		response.PatchType = patchType
		response.Patch = patch
	}
	_ = json.NewEncoder(w).Encode(admissionv1.AdmissionReview{Response: response})
}

func writeAdmissionError(w http.ResponseWriter, review *admissionv1.AdmissionReview, err error) {
	if globalConfig.LogAdmissionFailures {
		log.Printf("admission denied: %v", err)
	}
	response := &admissionv1.AdmissionResponse{
		UID:     review.Request.UID,
		Allowed: false,
		Result:  &meta.Status{Message: err.Error()},
	}
	_ = json.NewEncoder(w).Encode(admissionv1.AdmissionReview{Response: response})
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
