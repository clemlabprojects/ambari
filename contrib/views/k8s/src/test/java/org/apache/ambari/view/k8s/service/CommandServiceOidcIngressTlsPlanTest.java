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

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A deploy with OIDC whose https callback no Ingress certificate serves is refused once it is planned, before any step
 * runs: not at the OIDC step, once the keytab, the Ranger repository and the rest are made.
 */
@EnableKubernetesMockClient(crud = true)
class CommandServiceOidcIngressTlsPlanTest {

    @TempDir
    Path workDir;

    KubernetesMockServer server;
    KubernetesClient client;

    private DeployPlanningFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new DeployPlanningFixture(workDir, ctx -> new KubernetesService(ctx, client, true));
        fixture.secureCluster();
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.close();
    }

    @Test
    void oidcWithoutAnIngressCertificateIsRefusedBeforeAnythingRuns() throws Exception {
        HelmDeployRequest request = fixture.trino(true);
        request.setIngressTlsSelfSign(null);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> fixture.plan(request));

        assertEquals(CommandService.OIDC_NEEDS_INGRESS_TLS, refused.getMessage());
        // The stored plan is closed: left pending, it would show as running for good.
        Collection<CommandStatusEntity> statuses = fixture.view.dataStore.findAll(CommandStatusEntity.class, null);
        assertTrue(statuses.stream().noneMatch(s -> List.of("PENDING", "RUNNING").contains(s.getState())),
                "nothing left to run");
        assertTrue(statuses.stream().anyMatch(s -> "FAILED".equals(s.getState())
                && CommandService.OIDC_NEEDS_INGRESS_TLS.equals(s.getMessage())), "the operation says why");
    }

    @Test
    void theCertificateTheWizardPreselectsLetsTheDeployThrough() throws Exception {
        List<String> types = DeployPlanningFixture.types(fixture.steps(fixture.plan(fixture.trino(true))));

        assertTrue(types.contains("INGRESS_TLS_SELFSIGN"), types.toString());
        assertTrue(types.contains("OIDC_REGISTER_CLIENT"), types.toString());
    }

    /**
     * A company certificate pasted in the wizard (uploadLeaf): its Secret is created while the deploy is planned and the
     * Ingress gets it through a Helm override, which serves the https callback as well as a minted certificate.
     */
    @Test
    void anUploadedCompanyCertificateLetsTheDeployThrough() throws Exception {
        HelmDeployRequest request = fixture.trino(true);
        request.setIngressTlsSelfSign(null);
        String[] company = CaRegistryServiceTest.issuingCa("trino.example.com");
        request.setIngressTlsUpload(new java.util.LinkedHashMap<>(java.util.Map.of("certPem", company[0], "keyPem", company[1])));

        List<String> types = DeployPlanningFixture.types(fixture.steps(fixture.plan(request)));

        assertTrue(types.contains("OIDC_REGISTER_CLIENT"), types.toString());
        assertFalse(types.contains("INGRESS_TLS_SELFSIGN"), types.toString());
    }

    @Test
    void onlyAnOverrideNamingAnIngressSecretCounts() {
        assertTrue(CommandService.ingressTlsOverridden(java.util.Map.of("_ov",
                java.util.Map.of("ingress.tls[0].secretName", "trino-ingress-tls"))));
        assertFalse(CommandService.ingressTlsOverridden(java.util.Map.of("_ov",
                java.util.Map.of("ingress.tls[0].secretName", " "))));
        assertFalse(CommandService.ingressTlsOverridden(java.util.Map.of("_ov",
                java.util.Map.of("server.https.keystore.secretName", "x"))));
        assertFalse(CommandService.ingressTlsOverridden(java.util.Map.of()));
    }

    /**
     * A certificate pasted where its line breaks were lost (a single-line field) reads as no certificate at all: the
     * deploy is refused before its Secret is written, rather than the Ingress serving its default certificate.
     */
    @Test
    void anUploadedCertificateIsRefusedUnlessItReadsAsPem() throws Exception {
        String[] company = CaRegistryServiceTest.issuingCa("trino.example.com");
        String cert = company[0];
        String key = company[1];

        assertNull(CommandService.uploadedIngressTlsProblem(cert, key));
        assertNull(CommandService.uploadedIngressTlsProblem(cert.replace("\n", "\r\n"), key.replace("\n", "\r\n")),
                "a file saved on Windows");
        assertNotNull(CommandService.uploadedIngressTlsProblem(cert.replace("\n", " "), key));
        assertNotNull(CommandService.uploadedIngressTlsProblem(cert, key.replace("\n", " ")));
        assertNotNull(CommandService.uploadedIngressTlsProblem(cert.substring(0, cert.length() / 2) + "\n-----END CERTIFICATE-----\n", key));
        assertNotNull(CommandService.uploadedIngressTlsProblem(cert, cert), "a certificate pasted as the key");
    }
}
