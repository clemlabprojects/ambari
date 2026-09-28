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
 * DTO for KEDA discovery response. Advertises whether a KEDA operator is already present on the
 * cluster (so the Trino deploy can reuse it rather than installing a conflicting one), where it
 * lives, and how it was installed (Helm release vs an OLM/OpenShift operator such as the OpenShift
 * Custom Metrics Autoscaler). {@code present=false} means no KEDA was detected and the operator may
 * choose to let KDPS install one.
 */
public class KedaDiscoveryResponse {
    public boolean present;
    public String namespace;   // namespace the KEDA operator runs in (e.g. "keda", "openshift-keda")
    public String release;     // Helm release name when Helm-installed; null for OLM/operator installs
    public String source;      // "helm" | "olm" | "unknown"
    public String message;

    public KedaDiscoveryResponse() {
    }

    public KedaDiscoveryResponse(boolean present, String namespace, String release, String source, String message) {
        this.present = present;
        this.namespace = namespace;
        this.release = release;
        this.source = source;
        this.message = message;
    }
}
