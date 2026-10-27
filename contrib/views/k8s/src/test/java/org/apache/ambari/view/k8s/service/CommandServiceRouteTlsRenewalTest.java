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

import com.google.gson.Gson;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.utils.TestCertificates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.ws.rs.core.MultivaluedHashMap;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Renew TLS on a release OpenShift exposes with a Route. */
class CommandServiceRouteTlsRenewalTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "trino";
    private static final String HOST = "trino.apps.example.org";
    private static final String SECRET = "trino-ingress-tls";

    @TempDir
    Path workDir;

    private PerUserViewContext view;
    private KubernetesService k8s;
    private CommandService service;

    @BeforeEach
    void setUp() {
        // Its own instance per test: the command log of an instance stays in the folder of the test that first wrote it.
        view = new PerUserViewContext("route-renewal-" + System.nanoTime(), workDir);
        k8s = mock(KubernetesService.class);
        when(k8s.listIngressesForRelease(NS, RELEASE)).thenReturn(List.of());
        service = new CommandService(view.context, mock(HelmService.class), k8s);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private static Map<String, Object> route(Map<String, Object> tls) {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("name", "trino-coordinator");
        route.put("spec", Map.of("host", HOST, "tls", tls));
        return route;
    }

    private static Secret minted(String pem) {
        return new SecretBuilder().withNewMetadata().withName(SECRET).withNamespace(NS)
                .withLabels(Map.of(IngressTlsSecretGuard.ISSUER_LABEL, "k8s-view")).endMetadata()
                .withType("kubernetes.io/tls")
                .withData(Map.of("tls.crt", Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8))))
                .build();
    }

    private String renew() {
        return service.submitReleaseTlsRenewal(NS, RELEASE, new MultivaluedHashMap<>(), URI.create("http://ambari/"));
    }

    /** The operation's log once it holds {@code expected}, or as it is after a few seconds: lines are written apart. */
    private String log(String id, String expected) throws InterruptedException {
        String log = "";
        for (int i = 0; i < 50 && !log.contains(expected); i++) {
            Thread.sleep(100);
            log = CommandLogService.get(view.context).read(id, 0, 100_000).content;
        }
        return log;
    }

    @SuppressWarnings("unchecked")
    private List<String> steps(String id) {
        CommandEntity root = view.dataStore.find(CommandEntity.class, id);
        return new Gson().fromJson(root.getChildListJson(), List.class);
    }

    @Test
    void aSecretAnIngressAndARouteBothReadIsRenewedOnce() throws Exception {
        String pem = TestCertificates.caPem("CN=" + HOST);
        when(k8s.getSecret(NS, SECRET)).thenReturn(minted(pem));
        when(k8s.listIngressesForRelease(NS, RELEASE)).thenReturn(List.of(new IngressBuilder()
                .withNewMetadata().withName("trino").endMetadata()
                .withNewSpec().addNewTl().withSecretName(SECRET).withHosts(HOST).endTl().endSpec().build()));
        when(k8s.listRoutesForRelease(NS, RELEASE)).thenReturn(List.of(route(Map.of("termination", "reencrypt",
                "externalCertificate", Map.of("name", SECRET)))));

        String id = renew();

        assertEquals(1, steps(id).size());
        assertFalse(log(id, "Resync").contains("Resync"));
    }

    @Test
    void aRouteHoldingACopyOfTheCertificateIsRenewedAtItsSecretAndTheOperatorIsToldToResync() throws Exception {
        String pem = TestCertificates.caPem("CN=" + HOST);
        when(k8s.listRoutesForRelease(NS, RELEASE)).thenReturn(List.of(route(Map.of("termination", "edge",
                "certificate", pem))));
        when(k8s.listCertificateSecrets(NS)).thenReturn(List.of(minted(pem)));
        when(k8s.getSecret(NS, SECRET)).thenReturn(minted(pem));

        String id = renew();

        assertEquals(1, steps(id).size());
        String log = log(id, "Resync");
        assertTrue(log.contains("trino-coordinator") && log.contains("Resync " + RELEASE), log);
    }

    @Test
    void aPassthroughRouteHasNothingToRenewAndSaysWhy() {
        when(k8s.listRoutesForRelease(NS, RELEASE)).thenReturn(List.of(route(Map.of("termination", "passthrough"))));

        IllegalStateException nothing = assertThrows(IllegalStateException.class, this::renew);

        assertTrue(nothing.getMessage().contains("passes TLS through"), nothing.getMessage());
    }

    @Test
    void aReleaseWithNeitherIngressNorRouteNamesBoth() {
        when(k8s.listRoutesForRelease(NS, RELEASE)).thenReturn(List.of());

        IllegalArgumentException none = assertThrows(IllegalArgumentException.class, this::renew);

        assertTrue(none.getMessage().contains("No Ingress or OpenShift Route"), none.getMessage());
    }
}
