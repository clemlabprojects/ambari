package org.apache.ambari.view.k8s.requests;

import java.util.Objects;

public class KeytabRequest {
    private String principal;        // e.g. "trino/pod.ns@REALM"
    private String namespace;        // k8s namespace for the Secret
    private String podName;          // used for ownership/labels and default secret name
    private String secretName;       // optional; default "keytab-<pod>"
    private String keyNameInSecret;  // optional; default "service.keytab"

    public void validate() {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(podName, "podName");

        if (keyNameInSecret == null || keyNameInSecret.isBlank()) {
            keyNameInSecret = "service.keytab";
        }
        if (secretName == null || secretName.isBlank()) {
            secretName = "keytab-" + podName;
        }
    }

    // getters/setters
    public String getPrincipal() { return principal; }
    public void setPrincipal(String principal) { this.principal = principal; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getPodName() { return podName; }
    public void setPodName(String podName) { this.podName = podName; }

    public String getSecretName() { return secretName; }
    public void setSecretName(String secretName) { this.secretName = secretName; }

    public String getKeyNameInSecret() { return keyNameInSecret; }
    public void setKeyNameInSecret(String keyNameInSecret) { this.keyNameInSecret = keyNameInSecret; }
}
