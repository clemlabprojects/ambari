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
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The deploy steps that write objects outside the chart give them the release's labels, so its uninstall finds and
 * removes them: the materialized service configuration, the ingress certificate's cert-manager Certificate, and the
 * keytab Secret a redeploy reuses. Run against a Kubernetes API that stores what it is sent, then uninstalled.
 */
@EnableKubernetesMockClient(crud = true)
class CommandServiceReleaseObjectLabelsTest {

    static KubernetesClient client;

    private static final String NS = "analytics";
    private static final String REL = "superset";
    private static final ResourceDefinitionContext CERTIFICATES = new ResourceDefinitionContext.Builder()
            .withGroup("cert-manager.io").withVersion("v1").withKind("Certificate").withPlural("certificates")
            .withNamespaced(true).build();

    private final Gson gson = new Gson();
    private InMemoryDataStore dataStore;
    private KubernetesService kubernetes;
    private CommandService service;
    private CommandPlanFactory planFactory;

    /** Steps run with slf4j's MDC; see CommandServiceHelmStepTest for why it is loaded this way. */
    @BeforeAll
    static void loadMdc() {
        String javaVersion = System.getProperty("java.version");
        System.setProperty("java.version", "1.8");
        try {
            org.slf4j.MDC.getMDCAdapter();
        } finally {
            System.setProperty("java.version", javaVersion);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        client.secrets().inNamespace(NS).delete();
        if (client.namespaces().withName(NS).get() == null) {
            client.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName(NS).endMetadata().build())
                    .create();
        }
        Path workDir = Files.createTempDirectory("kdps-release-labels");
        ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getInstanceName()).thenReturn("release-labels-" + Long.toHexString(System.nanoTime()));
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", workDir.toString()));
        dataStore = new InMemoryDataStore();
        when(ctx.getDataStore()).thenReturn(dataStore);

        kubernetes = new KubernetesService(ctx, client, mock(HelmClient.class), true);
        service = new CommandService(ctx, mock(HelmService.class, RETURNS_DEEP_STUBS), kubernetes);
        // Its background sweep of operation files reads ctx: let it finish before the tests go on.
        service.startupSweep.get(30, TimeUnit.SECONDS);
        planFactory = new CommandPlanFactory(ctx, gson, new WebHookConfigurationService(ctx, kubernetes),
                new CommandUtils(ctx, kubernetes));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private CommandEntity root() {
        String id = "op-" + UUID.randomUUID();
        CommandEntity root = new CommandEntity();
        root.setId(id);
        root.setType(CommandType.K8S_MANAGER_RELEASE_DEPLOY.name());
        root.setTitle("Deploy release " + REL);
        root.setCommandStatusId(id + "-status");
        root.setParamsJson(gson.toJson(Map.of("releaseName", REL, "namespace", NS)));
        CommandStatusEntity rootStatus = new CommandStatusEntity();
        rootStatus.setId(id + "-status");
        rootStatus.setState(CommandState.RUNNING.name());
        rootStatus.setAttempt(0);
        dataStore.store(rootStatus);
        dataStore.store(root);
        return root;
    }

    private CommandStatusEntity run(CommandEntity root, String stepId) {
        root.setChildListJson(gson.toJson(List.of(stepId)));
        dataStore.store(root);
        service.runNextStep(root.getId());
        return dataStore.find(CommandStatusEntity.class, stepId + "-status");
    }

    /** A step as the deploy plan writes it. */
    private String step(CommandEntity root, CommandType type, Map<String, Object> params) {
        String stepId = root.getId() + "-" + UUID.randomUUID();
        CommandEntity step = new CommandEntity();
        step.setId(stepId);
        step.setType(type.name());
        step.setTitle(type.name());
        step.setParamsJson(gson.toJson(params));
        CommandStatusEntity status = new CommandStatusEntity();
        status.setId(stepId + "-status");
        status.setState(CommandState.PENDING.name());
        status.setAttempt(0);
        step.setCommandStatusId(status.getId());
        dataStore.store(status);
        dataStore.store(step);
        return stepId;
    }

    @Test
    void theMaterializedConfigurationIsTheReleasesAndGoesWithIt() {
        CommandEntity root = root();
        String stepId = planFactory.createConfigMaterializeCommand(root, "SUPERSET", REL, NS, Map.of());

        CommandStatusEntity step = run(root, stepId);

        assertEquals(CommandState.SUCCEEDED.name(), step.getState(), step.getMessage());
        List<Secret> written = client.secrets().inNamespace(NS).withLabel("ambari.clemlab.com/service", "SUPERSET")
                .list().getItems();
        assertFalse(written.isEmpty(), "the service's configuration is materialized");
        for (Secret s : written) {
            assertTrue(KdpsReleaseObjects.ofRelease(s.getMetadata(), NS, REL), s.getMetadata().toString());
            assertTrue(s.getMetadata().getName().startsWith(REL + "-"), s.getMetadata().getName());
        }

        KdpsReleaseObjects.Cleanup cleanup = kubernetes.revokeKdpsObjectsForRelease(NS, REL, null);

        assertEquals(written.size(), cleanup.removed().size(), cleanup.removed().toString());
        assertTrue(client.secrets().inNamespace(NS).withLabel("ambari.clemlab.com/service", "SUPERSET").list()
                .getItems().isEmpty());
    }

    @Test
    void theIngressCertificateKdpsAsksCertManagerForIsTheReleases() {
        CommandEntity root = root();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operation", "create");
        params.put("namespace", NS);
        params.put("releaseName", REL);
        params.put("certName", REL + "-ingress-tls");
        params.put("secretName", REL + "-ingress-tls");
        params.put("issuerName", "corp-issuer");
        params.put("issuerKind", "ClusterIssuer");
        params.put("dnsNames", List.of("superset.apps.example.org"));
        String stepId = step(root, CommandType.INGRESS_TLS_CERTMANAGER, params);

        CommandStatusEntity step = run(root, stepId);

        assertEquals(CommandState.SUCCEEDED.name(), step.getState(), step.getMessage());
        var cert = client.genericKubernetesResources(CERTIFICATES).inNamespace(NS).withName(REL + "-ingress-tls").get();
        assertTrue(KdpsReleaseObjects.ofRelease(cert.getMetadata(), NS, REL), cert.getMetadata().toString());
    }

    /**
     * Runs the KEYTAB_CREATE_SECRET step of a redeploy whose issuer found the keytab already in the Secret, and
     * returns its final status.
     */
    private CommandStatusEntity reuseKeytab(String secretName) {
        CommandEntity root = root();
        String issuerId = step(root, CommandType.KEYTAB_ISSUE_PRINCIPAL, Map.of());
        CommandStatusEntity issued = dataStore.find(CommandStatusEntity.class, issuerId + "-status");
        issued.setState(CommandState.SUCCEEDED.name());
        issued.setResultJson(gson.toJson(Map.of("reused", true)));
        dataStore.store(issued);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("namespace", NS);
        params.put("secretName", secretName);
        params.put("keyNameInSecret", "service.keytab");
        params.put("keytabIssuerId", issuerId);
        params.put("principalFqdn", "superset/superset.analytics.svc@EXAMPLE.ORG");
        params.put("releaseName", REL);
        return run(root, step(root, CommandType.KEYTAB_CREATE_SECRET, params));
    }

    private Secret keytabSecret(String name, Map<String, String> labels, Map<String, String> annotations) {
        return client.secrets().inNamespace(NS).resource(new io.fabric8.kubernetes.api.model.SecretBuilder()
                .withNewMetadata().withName(name).withNamespace(NS).withLabels(labels).withAnnotations(annotations)
                .endMetadata().withType("Opaque").withImmutable(true)
                .addToData("service.keytab", "a2V5dGFi").addToData("krb5.conf", "W2xpYmRlZmF1bHRzXQ==").build())
                .create();
    }

    /**
     * A keytab Secret KDPS wrote with its principal's labels, before it labelled what it creates for a release: a
     * redeploy reuses the keytab and writes nothing, and still gives the Secret the release labels, so the release's
     * uninstall removes it. Its keys are left as they are.
     */
    @Test
    void aKeytabSecretARedeployReusesBecomesTheReleases() {
        Map<String, String> earlierLabels = new LinkedHashMap<>(CommandService.keytabSecretLabels(REL,
                "superset/superset.analytics.svc@EXAMPLE.ORG"));
        earlierLabels.remove(KdpsReleaseObjects.LABEL_RELEASE);
        Secret before = keytabSecret(REL + "-keytab", earlierLabels, Map.of("managed-by", "ambari-k8s-view"));

        CommandStatusEntity step = reuseKeytab(REL + "-keytab");

        assertEquals(CommandState.SUCCEEDED.name(), step.getState(), step.getMessage());
        Secret after = client.secrets().inNamespace(NS).withName(REL + "-keytab").get();
        assertTrue(KdpsReleaseObjects.ofRelease(after.getMetadata(), NS, REL), after.getMetadata().toString());
        assertEquals(before.getData(), after.getData(), "every key of the Secret is kept");
        assertEquals(List.of("Secret/" + REL + "-keytab"),
                kubernetes.revokeKdpsObjectsForRelease(NS, REL, null).removed());
    }

    /** One an administrator provided, or one another release claims, stays out of the release's uninstall. */
    @Test
    void aReusedKeytabSecretKdpsDidNotWriteForTheReleaseIsNotTakenAsTheReleases() {
        String principal = "superset/superset.analytics.svc@EXAMPLE.ORG";
        Map<String, String> principalOnly = Map.of(CommandService.KEYTAB_PRINCIPAL_LABEL,
                CommandService.keytabPrincipalLabel(principal));
        keytabSecret("admin-keytab", principalOnly, Map.of());
        Map<String, String> anotherRelease = new LinkedHashMap<>(principalOnly);
        anotherRelease.put(CommandService.KEYTAB_RELEASE_LABEL, "superset-b");
        keytabSecret("shared-keytab", anotherRelease, Map.of("managed-by", "ambari-k8s-view"));

        for (String name : List.of("admin-keytab", "shared-keytab")) {
            CommandStatusEntity step = reuseKeytab(name);

            assertEquals(CommandState.SUCCEEDED.name(), step.getState(), step.getMessage());
            Secret after = client.secrets().inNamespace(NS).withName(name).get();
            assertFalse(after.getMetadata().getLabels().containsKey(KdpsReleaseObjects.LABEL_RELEASE),
                    after.getMetadata().toString());
        }
        assertTrue(kubernetes.revokeKdpsObjectsForRelease(NS, REL, null).isEmpty());
    }
}
