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

package org.apache.ambari.view.k8s.utils;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

/**
 * A composite TrustManager that delegates to a list of other TrustManagers.
 * This allows combining the default JVM TrustManager with a custom one.
 */
public class CompositeTrustManager implements X509TrustManager {

    private final List<X509TrustManager> trustManagers;

    public CompositeTrustManager(X509TrustManager... trustManagers) {
        this.trustManagers = Arrays.asList(trustManagers);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        // Not typically used for client-side validation
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        for (X509TrustManager tm : trustManagers) {
            try {
                tm.checkServerTrusted(chain, authType);
                // If we get here, one of the trust managers trusts the server, so we are done.
                return;
            } catch (CertificateException e) {
                // This trust manager did not trust the cert, so we try the next one.
            }
        }
        // If no trust manager in the list trusted the certificate, we throw.
        throw new CertificateException("None of the trust managers trust this certificate chain.");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        // Combine the issuers from all trust managers
        return trustManagers.stream()
                .flatMap(tm -> Arrays.stream(tm.getAcceptedIssuers()))
                .toArray(X509Certificate[]::new);
    }
}