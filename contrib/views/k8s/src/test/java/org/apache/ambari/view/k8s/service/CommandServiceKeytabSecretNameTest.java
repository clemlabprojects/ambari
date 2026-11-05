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

import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.apache.ambari.view.k8s.service.DeployPlanningFixture.NS;
import static org.apache.ambari.view.k8s.service.DeployPlanningFixture.REALM;
import static org.apache.ambari.view.k8s.service.DeployPlanningFixture.step;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A redeploy keeps the keytab Secret its pods mount: the wizard named it after the release name it proposed first
 * ('trino-keytab' for a release 'trino'), and a redeploy naming it after the release must not switch to another Secret.
 */
@EnableKubernetesMockClient(crud = true)
class CommandServiceKeytabSecretNameTest {

    private static final String PRINCIPAL = "trino-" + NS + "@" + REALM;

    @TempDir
    Path workDir;

    KubernetesMockServer server;
    KubernetesClient client;

    private DeployPlanningFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new DeployPlanningFixture(workDir, ctx -> new KubernetesService(ctx, client, true));
        fixture.kerberos(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.close();
    }

    private void keytabSecret(String name, String release) {
        client.secrets().inNamespace(NS).resource(new SecretBuilder().withNewMetadata().withName(name).withNamespace(NS)
                .withLabels(CommandService.keytabSecretLabels(release, PRINCIPAL)).endMetadata().withType("Opaque")
                .addToData("service.keytab", "a2V5dGFi").build()).create();
    }

    private Map<String, Object> issueStep() throws Exception {
        List<CommandEntity> steps = fixture.steps(fixture.plan(fixture.trino(false)));
        return fixture.params(step(steps, CommandType.KEYTAB_ISSUE_PRINCIPAL.name()));
    }

    @Test
    void aRedeployKeepsTheKeytabSecretTheReleaseMounts() throws Exception {
        keytabSecret("legacy-trino-keytab", "trino");

        assertEquals("legacy-trino-keytab", issueStep().get("secretName"));
    }

    @Test
    void anotherReleasesSecretIsNotTaken() throws Exception {
        keytabSecret("legacy-trino-keytab", "trino-etl");

        assertEquals("trino-keytab", issueStep().get("secretName"));
    }
}
