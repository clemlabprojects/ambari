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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import okhttp3.mockwebserver.MockResponse;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.model.ContextRequest;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.utils.FakeAmbariServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The keytab steps of a release whose context holds a KDC account: Ambari server issues the keytab from the context's
 * KDC and answers with it, and the issue step stores it in the Secret at once, without any Ambari cluster; the Secret
 * is its only copy. A new key is asked for when the keytab is regenerated, or when the context's KDC refuses the stored
 * one.
 */
@EnableKubernetesMockClient(crud = true)
class CommandServiceContextKdcKeytabTest {

    static KubernetesClient client;

    private static final String NS = "analytics";
    private static final String REL = "trino";
    private static final String CONTEXT = "cdp-prod";
    private static final String PRINCIPAL = "trino-analytics@CORP.EXAMPLE.COM";
    private static final byte[] KEYTAB = "issued-keytab".getBytes(StandardCharsets.UTF_8);
    private static final String KRB5_CONF = "[libdefaults]\n  default_realm = CORP.EXAMPLE.COM\n";

    private final Gson gson = new Gson();
    private final List<String> issueRequests = new CopyOnWriteArrayList<>();
    private FakeAmbariServer ambari;
    private InMemoryDataStore dataStore;
    private CommandService service;
    private ViewContext viewContext;

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
        // No cluster: a standalone Ambari, or one whose cluster is not the release's.
        ambari = new FakeAmbariServer("c1");
        Path workDir = Files.createTempDirectory("kdps-context-kdc");
        ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        viewContext = ctx;
        when(ctx.getInstanceName()).thenReturn("context-kdc-" + Long.toHexString(System.nanoTime()));
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", workDir.toString()));
        when(ctx.getCluster()).thenReturn(null);
        when(ctx.getURLStreamProvider()).thenReturn(ambari.streamProvider());
        dataStore = new InMemoryDataStore();
        when(ctx.getDataStore()).thenReturn(dataStore);

        KubernetesService kubernetes = new KubernetesService(ctx, client, mock(HelmClient.class), true);
        service = new CommandService(ctx, mock(HelmService.class, RETURNS_DEEP_STUBS), kubernetes);
        service.startupSweep.get(30, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.shutdown();
        ambari.close();
    }

    private void kdcIssues() {
        ambari.answer("POST /api/v1/kerberos/adhoc_keytab", request -> {
            issueRequests.add(request.getBody().readUtf8());
            JsonObject answer = new JsonObject();
            answer.addProperty("principal", PRINCIPAL);
            answer.addProperty("keytab", Base64.getEncoder().encodeToString(KEYTAB));
            answer.addProperty("kvno", 1);
            answer.addProperty("created", true);
            return new MockResponse().setHeader("Content-Type", "application/json").setBody(answer.toString());
        });
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

    private CommandStatusEntity run(CommandEntity root, List<String> done, String stepId) {
        List<String> children = new ArrayList<>(done);
        children.add(stepId);
        root.setChildListJson(gson.toJson(children));
        dataStore.store(root);
        // The operation ended with its last step: the next one is added as if it had been planned with it.
        CommandStatusEntity rootStatus = dataStore.find(CommandStatusEntity.class, root.getCommandStatusId());
        rootStatus.setState(CommandState.RUNNING.name());
        dataStore.store(rootStatus);
        service.runNextStep(root.getId());
        return dataStore.find(CommandStatusEntity.class, stepId + "-status");
    }

    private Map<String, Object> keytabParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("principalFqdn", PRINCIPAL);
        params.put("namespace", NS);
        params.put("releaseName", REL);
        params.put("secretName", REL + "-keytab");
        params.put("keyNameInSecret", "service.keytab");
        params.put("_baseUri", ambari.baseUri());
        params.put("_callerHeaders", Map.of("Cookie", List.of("AMBARISESSIONID=admin-session")));
        params.put(CommandService.KDC_CONTEXT_PARAM, CONTEXT);
        return params;
    }

    @Test
    void theContextKdcIssuesTheKeytabAndTheSecretGetsIt() {
        kdcIssues();
        CommandEntity root = root();
        String issueId = step(root, CommandType.KEYTAB_ISSUE_PRINCIPAL, keytabParams());

        CommandStatusEntity issued = run(root, List.of(), issueId);

        assertEquals(CommandState.SUCCEEDED.name(), issued.getState(), issued.getMessage());
        assertFalse(issued.getResultJson().contains(Base64.getEncoder().encodeToString(KEYTAB)),
                "the keytab never goes to the view's database");
        JsonObject sent = JsonParser.parseString(issueRequests.get(0)).getAsJsonObject();
        assertEquals(ContextKdcAccount.aliasOf(CONTEXT), sent.get("credential_alias").getAsString());
        assertEquals(PRINCIPAL, sent.get("principal").getAsString());
        assertEquals(NS, sent.get("namespace").getAsString());
        assertEquals(3, sent.size(), "only the alias names the KDC: " + sent);
        Secret issuedSecret = client.secrets().inNamespace(NS).withName(REL + "-keytab").get();
        assertArrayEquals(KEYTAB, Base64.getDecoder().decode(issuedSecret.getData().get("service.keytab")),
                "the issue step stores the keytab itself: nothing keeps it in between");

        Map<String, Object> createParams = keytabParams();
        createParams.put("keytabIssuerId", issueId);
        CommandStatusEntity stored = run(root, List.of(issueId),
                step(root, CommandType.KEYTAB_CREATE_SECRET, createParams));

        assertEquals(CommandState.SUCCEEDED.name(), stored.getState(), stored.getMessage());
        Secret secret = client.secrets().inNamespace(NS).withName(REL + "-keytab").get();
        assertArrayEquals(KEYTAB, Base64.getDecoder().decode(secret.getData().get("service.keytab")));
        assertTrue(ambari.requests.stream().noneMatch(r -> r.contains("/clusters")),
                "no cluster is asked: " + ambari.requests);
    }

    private JsonObject issueOnce(Map<String, Object> params) {
        CommandEntity root = root();
        CommandStatusEntity issued = run(root, List.of(), step(root, CommandType.KEYTAB_ISSUE_PRINCIPAL, params));
        assertEquals(CommandState.SUCCEEDED.name(), issued.getState(), issued.getMessage());
        return JsonParser.parseString(issueRequests.get(issueRequests.size() - 1)).getAsJsonObject();
    }

    @Test
    void aRegenerationAsksForANewKey() {
        kdcIssues();
        Map<String, Object> params = keytabParams();
        params.put(CommandService.FORCE_REISSUE_PARAM, true);

        JsonObject sent = issueOnce(params);

        assertTrue(sent.get("new_key").getAsBoolean(), sent.toString());
    }

    @Test
    void aStoredKeytabTheContextKdcRefusesGetsANewKey() {
        kdcIssues();
        storedKeytab();
        List<String> checkedWith = new ArrayList<>();
        service.contextKeytabLogin = (krb5Conf, keytab, principal) -> {
            checkedWith.add(krb5Conf.strip());
            return Boolean.FALSE;
        };

        JsonObject sent = issueOnce(keytabParams());

        assertEquals(List.of(KRB5_CONF.strip()), checkedWith, "checked with the context's krb5.conf");
        assertTrue(sent.get("new_key").getAsBoolean(), sent.toString());
    }

    @Test
    void aStoredKeytabTheContextKdcAcceptsIsReused() {
        kdcIssues();
        storedKeytab();
        service.contextKeytabLogin = (krb5Conf, keytab, principal) -> Boolean.TRUE;

        CommandEntity root = root();
        CommandStatusEntity issued = run(root, List.of(), step(root, CommandType.KEYTAB_ISSUE_PRINCIPAL, keytabParams()));

        assertEquals(CommandState.SUCCEEDED.name(), issued.getState(), issued.getMessage());
        assertTrue(issueRequests.isEmpty(), "the KDC is not asked");
    }

    @Test
    void aKdcNoOneAnswersForTellsNothingAboutTheKeytab() {
        // No KDC listens there; without kinit on the machine, nothing can be told either.
        assertNull(CommandService.kinitWithKeytab("[libdefaults]\n  default_realm = CORP.EXAMPLE.COM\n"
                + "[realms]\n  CORP.EXAMPLE.COM = {\n    kdc = 127.0.0.1:1\n  }\n", KEYTAB, PRINCIPAL));
    }

    /** The context, with its krb5.conf, and a keytab of the principal already in the release's Secret. */
    private void storedKeytab() {
        ContextRequest context = new ContextRequest();
        context.id = CONTEXT;
        context.name = "CDP production";
        context.kind = ContextService.KIND_EXTERNAL;
        context.config = new LinkedHashMap<>(Map.of("realm", "CORP.EXAMPLE.COM", "krb5Conf", KRB5_CONF));
        context.secrets = new LinkedHashMap<>();
        new ContextService(viewContext).save(context);
        client.secrets().inNamespace(NS).resource(new io.fabric8.kubernetes.api.model.SecretBuilder()
                .withNewMetadata().withName(REL + "-keytab").withNamespace(NS).endMetadata().withType("Opaque")
                .addToData("service.keytab", Base64.getEncoder().encodeToString(
                        CommandServiceReleaseRepairActionsTest.keytab(PRINCIPAL, 2))).build()).create();
    }

    @Test
    void aPrincipalTheKdcRefusesFailsTheStepWithItsReason() {
        ambari.answer("POST /api/v1/kerberos/adhoc_keytab", request -> new MockResponse().setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":409,\"message\":\"Principal 'trino-analytics@CORP.EXAMPLE.COM' already exists\"}"));
        CommandEntity root = root();

        CommandStatusEntity issued = run(root, List.of(), step(root, CommandType.KEYTAB_ISSUE_PRINCIPAL, keytabParams()));

        assertEquals(CommandState.FAILED.name(), issued.getState());
        assertTrue(issued.getMessage().contains("already exists"), issued.getMessage());
        assertTrue(issued.getMessage().contains(CONTEXT), issued.getMessage());
    }
}
