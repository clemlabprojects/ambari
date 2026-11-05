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
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.model.ContextRequest;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.apache.ambari.view.k8s.service.DeployPlanningFixture.step;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A deploy on an external context that holds a KDC account: its keytabs are issued from that context's KDC, in the
 * context's realm, even though the Ambari cluster is not Kerberized. Without the account, the deploy still asks for an
 * external keytab Secret.
 */
@EnableKubernetesMockClient(crud = true)
class CommandServiceContextKdcPlanningTest {

    private static final String CONTEXT = "cdp-prod";
    private static final String CDP_REALM = "CORP.EXAMPLE.COM";
    private static final String KRB5_CONF = "[libdefaults]\n  default_realm = " + CDP_REALM + "\n[realms]\n  "
            + CDP_REALM + " = {\n    kdc = dc01.corp.example.com\n  }\n";

    @TempDir
    Path workDir;

    KubernetesMockServer server;
    KubernetesClient client;

    private DeployPlanningFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new DeployPlanningFixture(workDir, ctx -> new KubernetesService(ctx, client, true));
        fixture.plainCluster();
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.close();
    }

    private void externalContext(String krb5Conf) {
        ContextRequest request = new ContextRequest();
        request.id = CONTEXT;
        request.name = "CDP production";
        request.kind = ContextService.KIND_EXTERNAL;
        request.config = new LinkedHashMap<>(Map.of("realm", CDP_REALM));
        if (krb5Conf != null) request.config.put("krb5Conf", krb5Conf);
        request.secrets = new LinkedHashMap<>();
        new ContextService(fixture.view.context).save(request);
    }

    private HelmDeployRequest trinoOnTheContext() throws Exception {
        HelmDeployRequest request = fixture.trino(false);
        request.getFormValues().put("platformContextId", CONTEXT);
        return request;
    }

    @Test
    void theKeytabsAreIssuedFromTheContextKdcInItsRealm() throws Exception {
        externalContext(KRB5_CONF);
        fixture.service.contextHoldsKdcAccount = CONTEXT::equals;

        List<CommandEntity> steps = fixture.steps(fixture.plan(trinoOnTheContext()));

        Map<String, Object> issue = fixture.params(step(steps, CommandType.KEYTAB_ISSUE_PRINCIPAL.name()));
        assertEquals(CONTEXT, issue.get(CommandService.KDC_CONTEXT_PARAM));
        assertTrue(String.valueOf(issue.get("principalFqdn")).endsWith("@" + CDP_REALM), issue.toString());
        step(steps, CommandType.KEYTAB_CREATE_SECRET.name());
    }

    @Test
    void aGitOpsDeployIssuesTheKeytabsInTheClusterToo() throws Exception {
        // Only the chart values go to Git: the keytab Secret is written by KDPS in the release namespace, as for a
        // direct deploy, and the values point the chart at it.
        externalContext(KRB5_CONF);
        fixture.service.contextHoldsKdcAccount = CONTEXT::equals;
        HelmDeployRequest request = trinoOnTheContext();

        String id = fixture.service.planReleaseDeploy(request, "charts", request.getVersion(),
                "http://ambari.test/commands", DeployPlanningFixture.adminHeaders(),
                java.net.URI.create(fixture.ambari.baseUri()), null, true);

        List<CommandEntity> steps = fixture.steps(id);
        Map<String, Object> issue = fixture.params(step(steps, CommandType.KEYTAB_ISSUE_PRINCIPAL.name()));
        assertEquals(CONTEXT, issue.get(CommandService.KDC_CONTEXT_PARAM));
        step(steps, CommandType.KEYTAB_CREATE_SECRET.name());
        List<String> types = DeployPlanningFixture.types(steps);
        assertTrue(types.indexOf(CommandType.KEYTAB_CREATE_SECRET.name()) < types.size() - 1, types.toString());
        assertTrue(types.stream().noneMatch(t -> t.equals("HELM_DEPLOY")), "committed to Git, not installed: " + types);
    }

    @Test
    void withoutAKdcAccountTheDeployStillAsksForAnExternalKeytab() throws Exception {
        externalContext(KRB5_CONF);
        fixture.service.contextHoldsKdcAccount = id -> false;

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> fixture.plan(trinoOnTheContext()));

        assertTrue(refused.getMessage().contains("no external keytab"), refused.getMessage());
    }

    @Test
    void aContextKdcWithoutKrb5ConfIsRefused() throws Exception {
        // The pods could not reach the KDC whose principals they would get.
        externalContext(null);
        fixture.service.contextHoldsKdcAccount = CONTEXT::equals;

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> fixture.plan(trinoOnTheContext()));

        assertTrue(refused.getMessage().contains("holds no krb5.conf"), refused.getMessage());
    }
}
