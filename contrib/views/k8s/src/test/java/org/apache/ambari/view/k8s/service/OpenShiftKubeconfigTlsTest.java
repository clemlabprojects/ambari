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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the kubeconfig of an OpenShift username/password login says the API certificate is checked. */
class OpenShiftKubeconfigTlsTest {

    private static final String PEM = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n";

    @Test
    void aBlankCaWithVerificationOnWritesNeitherKey() {
        for (String ca : new String[]{null, "", "  \n"}) {
            String kubeconfig = ViewConfigurationService.openShiftKubeconfig("https://api.example:6443", ca, false, "tok");
            assertFalse(kubeconfig.contains("insecure-skip-tls-verify"), kubeconfig);
            assertFalse(kubeconfig.contains("certificate-authority-data"), kubeconfig);
            assertTrue(kubeconfig.contains("server: https://api.example:6443"), kubeconfig);
            assertTrue(kubeconfig.contains("token: tok"), kubeconfig);
        }
    }

    @Test
    void aPastedCaIsWrittenAsCertificateAuthorityData() {
        String kubeconfig = ViewConfigurationService.openShiftKubeconfig("https://api.example:6443", PEM, false, "tok");
        assertTrue(kubeconfig.contains("certificate-authority-data: "
                + java.util.Base64.getEncoder().encodeToString(PEM.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                kubeconfig);
        assertFalse(kubeconfig.contains("insecure-skip-tls-verify"), kubeconfig);
    }

    @Test
    void skipTlsVerificationTickedWritesTheInsecureKeyOnly() {
        for (String ca : new String[]{null, PEM}) {
            String kubeconfig = ViewConfigurationService.openShiftKubeconfig("https://api.example:6443", ca, true, "tok");
            assertTrue(kubeconfig.contains("insecure-skip-tls-verify: true"), kubeconfig);
            assertFalse(kubeconfig.contains("certificate-authority-data"), kubeconfig);
        }
    }
}
