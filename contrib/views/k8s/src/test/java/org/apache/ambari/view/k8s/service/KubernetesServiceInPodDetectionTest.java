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

package org.apache.ambari.view.k8s.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether Ambari takes itself to run inside the cluster depends on the variables Kubernetes injects into every pod,
 * and not on the ServiceAccount token: a pod that does not mount the token is still in the cluster.
 */
class KubernetesServiceInPodDetectionTest {

    @Test
    void podWithServiceHostAndPortIsInsideTheCluster() {
        Map<String, String> env = Map.of("KUBERNETES_SERVICE_HOST", "172.30.0.1", "KUBERNETES_SERVICE_PORT", "443");
        assertTrue(KubernetesService.hasInClusterEnvironment(env::get));
    }

    @Test
    void missingOrBlankVariablesAreOutsideTheCluster() {
        assertFalse(KubernetesService.hasInClusterEnvironment(name -> null));
        assertFalse(KubernetesService.hasInClusterEnvironment(
                Map.of("KUBERNETES_SERVICE_HOST", "172.30.0.1")::get));
        assertFalse(KubernetesService.hasInClusterEnvironment(
                Map.of("KUBERNETES_SERVICE_HOST", " ", "KUBERNETES_SERVICE_PORT", "443")::get));
    }
}
