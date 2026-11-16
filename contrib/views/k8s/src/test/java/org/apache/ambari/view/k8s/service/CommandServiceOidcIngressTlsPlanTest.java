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
}
