/*
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

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.utils.TestCertificates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The TLS state of a release OpenShift exposes with a Route rather than an Ingress. */
class ReleaseTlsServiceRouteTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "trino";
    private static final String HOST = "trino.apps.example.org";

    private KubernetesService k8s;
    private ReleaseTlsService service;

    @BeforeEach
    void setUp() {
        k8s = mock(KubernetesService.class);
        when(k8s.listIngressesForRelease(NS, RELEASE)).thenReturn(List.of());
        service = new ReleaseTlsService(mock(ViewContext.class), k8s);
    }

    /** A Route of the release, as {@link KubernetesService#listRoutesForRelease} gives it. */
    private static Map<String, Object> route(Map<String, Object> tls) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("host", HOST);
        if (tls != null) spec.put("tls", tls);
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("name", "trino-coordinator");
        route.put("spec", spec);
        return route;
    }

    /** A certificate Secret KDPS minted with a Company CA. */
    private static Secret minted(String name, String pem) {
        return new SecretBuilder().withNewMetadata().withName(name).withNamespace(NS)
                .withLabels(Map.of(IngressTlsSecretGuard.ISSUER_LABEL, "k8s-view", "ambari.clemlab.com/ca-mode",
                        "companyUploaded", "ambari.clemlab.com/ca-name", "corp"))
                .endMetadata().withType("kubernetes.io/tls")
                .withData(Map.of("tls.crt", Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8)),
                        "tls.key", "a2V5"))
                .build();
    }

    @Test
    void aRouteReadingItsCertificateFromASecretIsReportedFromThatSecretAndRenewedLikeAnIngresss() {
        String pem = TestCertificates.caPem("CN=" + HOST);
        when(k8s.listRoutesForRelease(NS, RELEASE)).thenReturn(List.of(route(Map.of("termination", "reencrypt",
                "externalCertificate", Map.of("name", "trino-ingress-tls")))));
        when(k8s.getSecret(NS, "trino-ingress-tls")).thenReturn(minted("trino-ingress-tls", pem));

        Map<String, Object> entry = service.describe(NS, RELEASE).get(0);

        assertEquals("Route", entry.get("kind"));
        assertEquals("trino-coordinator", entry.get("routeName"));
        assertEquals("trino-ingress-tls", entry.get("secretName"));
        assertEquals(List.of(HOST), entry.get("hosts"));
        // What renewal dispatches on: re-minted with the Company CA it was minted with.
        assertEquals("k8s-view-self-signed", entry.get("source"));
        assertEquals("corp", entry.get("caName"));
        assertEquals("expiring-soon", entry.get("status"), "the test certificate is valid for a day");
        assertNull(entry.get("copiedToRoute"));
    }

    @Test
    void aRouteHoldingACopyOfTheCertificateIsReportedFromItAndLinkedToTheSecretItCameFrom() {
        String pem = TestCertificates.caPem("CN=" + HOST);
        when(k8s.listRoutesForRelease(NS, RELEASE)).thenReturn(List.of(route(Map.of("termination", "edge",
                "certificate", pem, "key", "-----BEGIN PRIVATE KEY-----\nx\n-----END PRIVATE KEY-----"))));
        when(k8s.listCertificateSecrets(NS)).thenReturn(List.of(
                minted("other-tls", TestCertificates.caPem("CN=other.apps.example.org")),
                minted("trino-ingress-tls", pem)));

        Map<String, Object> entry = service.describe(NS, RELEASE).get(0);

        assertEquals(true, entry.get("copiedToRoute"));
        assertEquals("trino-ingress-tls", entry.get("secretName"));
        assertEquals("k8s-view-self-signed", entry.get("source"));
        assertEquals("CN=" + HOST, entry.get("subject"));
        assertEquals("expiring-soon", entry.get("status"), "the test certificate is valid for a day");
    }

    @Test
    void aCopyNoSecretHoldsIsReportedWithoutASecretSoNothingIsRenewed() {
        when(k8s.listRoutesForRelease(NS, RELEASE)).thenReturn(List.of(route(Map.of("termination", "edge",
                "certificate", TestCertificates.caPem("CN=" + HOST)))));
        when(k8s.listCertificateSecrets(NS)).thenReturn(List.of());

        Map<String, Object> entry = service.describe(NS, RELEASE).get(0);

        assertNull(entry.get("secretName"));
        assertNull(entry.get("source"));
        assertEquals("expiring-soon", entry.get("status"), "the test certificate is valid for a day");
    }

    @Test
    void aPassthroughRouteServesTheServicesOwnCertificate() {
        when(k8s.listRoutesForRelease(NS, RELEASE)).thenReturn(List.of(route(Map.of("termination", "passthrough"))));

        Map<String, Object> entry = service.describe(NS, RELEASE).get(0);

        assertEquals("passthrough", entry.get("status"));
        assertNull(entry.get("secretName"));
        verify(k8s, never()).getSecret(anyString(), anyString());
    }

    @Test
    void aRouteWithoutTlsSaysSo() {
        when(k8s.listRoutesForRelease(NS, RELEASE)).thenReturn(List.of(route(null)));

        assertEquals("no-tls", service.describe(NS, RELEASE).get(0).get("status"));
    }

    @Test
    void aRouteThatCannotBeListedLeavesTheIngressesReported() {
        when(k8s.listRoutesForRelease(NS, RELEASE)).thenThrow(new IllegalStateException("forbidden"));

        assertEquals(List.of(), service.describe(NS, RELEASE));
    }
}
