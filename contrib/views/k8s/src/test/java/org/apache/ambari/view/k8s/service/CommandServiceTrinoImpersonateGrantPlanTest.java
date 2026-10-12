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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A release that connects to Trino as a service account and runs queries as the logged-in user needs
 * that account allowed to impersonate in the Trino Ranger repository. The deploy plans the grant when
 * it knows the account; over OIDC without a Trino service principal it cannot, and since nothing else
 * lets the account impersonate every user any more, the deploy log warns that its queries will be
 * refused rather than leaving the operator to find out from Trino.
 */
class CommandServiceTrinoImpersonateGrantPlanTest {

    private static final String NS = "analytics";
    private static final String TRINO_HOST = "trino.analytics.svc.cluster.local";

    @TempDir
    Path tmp;

    private final Gson gson = new Gson();
    private InMemoryDataStore dataStore;
    private ViewContext ctx;
    private CommandService service;
    private String rel;

    @BeforeEach
    void setUp() throws Exception {
        String instance = "trino-grant-plan-" + Long.toHexString(System.nanoTime());
        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getInstanceName()).thenReturn(instance);
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", Files.createDirectories(tmp.resolve("work")).toString()));
        when(ctx.getInstanceData(anyString())).thenReturn(null);
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
        dataStore = new InMemoryDataStore();
        when(ctx.getDataStore()).thenReturn(dataStore);

        HelmRepoEntity charts = new HelmRepoEntity();
        charts.setId("charts");
        charts.setName("charts");
        charts.setType("HTTP");
        charts.setUrl("https://charts.example.org");
        charts.setAuthMode("anonymous");
        dataStore.store(charts);

        Map<String, String> cache = new ConcurrentHashMap<>();
        ViewConfigurationService configuration = mock(ViewConfigurationService.class);
        when(configuration.writeCacheFile(anyString(), anyString())).thenAnswer(inv -> {
            String path = "cache/" + inv.getArgument(0);
            cache.put(path, inv.getArgument(1));
            return path;
        });
        when(configuration.readCacheFile(anyString())).thenAnswer(inv -> cache.get((String) inv.getArgument(0)));
        KubernetesService k8s = mock(KubernetesService.class);
        when(k8s.getConfigurationService()).thenReturn(configuration);

        HelmService helm = mock(HelmService.class, RETURNS_DEEP_STUBS);
        when(helm.getRepositoryService().showValuesAsMap(any(), any(), any())).thenReturn(new LinkedHashMap<>());
        when(helm.getRepositoryService().getEffectiveImageRegistry(any())).thenReturn("registry.example.org");

        service = new CommandService(ctx, helm, k8s);
        rel = "bi-" + Long.toHexString(System.nanoTime());
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void oidcWithoutATrinoServicePrincipalWarnsThatQueriesWillBeRefused() throws InterruptedException {
        String rootId = plan(Map.of("ui_trino_host", TRINO_HOST, "ui_trino_auth_mode", "oidc"));

        assertFalse(stepTypes(rootId).contains(CommandType.RANGER_POLICY_GRANT_TRINO_IMPERSONATE.name()),
                "there is no account to grant: " + stepTypes(rootId));
        String log = awaitLog(rootId, "WARNING: Release '" + rel + "' connects to Trino with an OIDC service token, "
                + "but no Trino service principal is set");
        assertTrue(log.contains("every query from this release is refused"), log);
        assertTrue(log.contains("'impersonate' policy on Trino user '*'"), "names the policy to add: " + log);
        assertTrue(log.contains("Trino service principal (for the impersonation grant)"), "names the field to set: " + log);
    }

    @Test
    void oidcWithATrinoServicePrincipalPlansTheGrantAndDoesNotWarn() throws InterruptedException {
        String rootId = plan(Map.of("ui_trino_host", TRINO_HOST, "ui_trino_auth_mode", "oidc",
                "ui_trino_oidc_principal", "service-account-superset"));

        CommandEntity grant = step(rootId, CommandType.RANGER_POLICY_GRANT_TRINO_IMPERSONATE);
        assertEquals("service-account-superset", paramsOf(grant).get("_principal"));
        assertNoWarning(rootId);
    }

    @Test
    void aModeThatSetsTheSessionUserDirectlyNeedsNoGrantAndDoesNotWarn() throws InterruptedException {
        String rootId = plan(Map.of("ui_trino_host", TRINO_HOST, "ui_trino_auth_mode", "tls"));

        assertFalse(stepTypes(rootId).contains(CommandType.RANGER_POLICY_GRANT_TRINO_IMPERSONATE.name()),
                stepTypes(rootId)::toString);
        assertNoWarning(rootId);
    }

    /** Plans a direct deploy of the test Trino client with the given form values and returns the operation id. */
    private String plan(Map<String, Object> formValues) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setChart("example/zz-test-trino-client");
        request.setReleaseName(rel);
        request.setNamespace(NS);
        request.setServiceKey("ZZTESTTRINOCLIENT");
        request.setRepoId("charts");
        request.setVersion("0.0.1");
        request.setDeploymentMode("DIRECT_HELM");
        request.setValues(new LinkedHashMap<>(Map.of("replicaCount", 1)));
        request.setFormValues(new LinkedHashMap<>(formValues));
        return service.planReleaseDeploy(request, "charts", "0.0.1", null, null, null, null, false);
    }

    private List<String> stepTypes(String rootId) {
        Type listType = new TypeToken<ArrayList<String>>() {}.getType();
        List<String> ids = gson.fromJson(dataStore.find(CommandEntity.class, rootId).getChildListJson(), listType);
        List<String> types = new ArrayList<>();
        for (String id : ids) types.add(dataStore.find(CommandEntity.class, id).getType());
        return types;
    }

    private CommandEntity step(String rootId, CommandType type) {
        Type listType = new TypeToken<ArrayList<String>>() {}.getType();
        for (String id : gson.<List<String>>fromJson(dataStore.find(CommandEntity.class, rootId).getChildListJson(), listType)) {
            CommandEntity c = dataStore.find(CommandEntity.class, id);
            if (type.name().equals(c.getType())) return c;
        }
        throw new AssertionError("no " + type + " step in " + stepTypes(rootId));
    }

    private Map<String, Object> paramsOf(CommandEntity command) {
        Type mapType = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();
        return gson.fromJson(CommandUtils.resolveParamsPayload(command.getParamsJson()), mapType);
    }

    /** The command log is written asynchronously; waits for a line to show up. */
    private String awaitLog(String commandId, String expected) throws InterruptedException {
        String content = "";
        for (int i = 0; i < 200; i++) {
            content = CommandLogService.get(ctx).read(commandId, 0, 1 << 16).content;
            if (content.contains(expected)) return content;
            Thread.sleep(25);
        }
        throw new AssertionError("'" + expected + "' never reached the log of " + commandId + ": " + content);
    }

    /**
     * Asserts the deploy log carries no impersonation warning. The log is written asynchronously, so a
     * marker line is appended after planning and awaited first: everything logged before it is then in.
     */
    private void assertNoWarning(String rootId) throws InterruptedException {
        CommandLogService.get(ctx).append(rootId, "end of plan");
        String log = awaitLog(rootId, "end of plan");
        assertFalse(log.contains("cannot impersonate"), log);
    }
}
