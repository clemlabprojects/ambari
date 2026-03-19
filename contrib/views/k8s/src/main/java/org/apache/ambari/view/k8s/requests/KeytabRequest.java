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

package org.apache.ambari.view.k8s.requests;

import java.util.Objects;

public class KeytabRequest {
    private String principal;        // e.g. "trino/pod.ns@REALM"
    private String namespace;        // k8s namespace for the Secret
    private String podName;          // used for ownership/labels and default secret name
    private String secretName;       // optional; default "keytab-<pod>"
    private String keyNameInSecret;  // optional; default "service.keytab"

    // Optional: correlation to a parent command (e.g., Helm deploy root)
    private String parentCommandId;

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

    public String getParentCommandId() { return parentCommandId; }
    public void setParentCommandId(String parentCommandId) { this.parentCommandId = parentCommandId; }
}
