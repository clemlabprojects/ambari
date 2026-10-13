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
import org.apache.ambari.view.cluster.Cluster;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.KdpsContextEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.apache.ambari.view.k8s.utils.FakeAmbariServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.ws.rs.core.MultivaluedHashMap;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The three places a deploy resolves dynamic values agree: the plan of the release (CommandService), the Helm steps of
 * the release and those of its dependencies (CommandPlanFactory). Each used to keep its own copy of the resolution, and
 * one of them built the OIDC issuer {@code /realms/<realm>} from a blank Keycloak address where the others left it
 * out.
 */
class DynamicValuesCallSitesParityTest {

    private static final List<String> TOKENS = List.of(
            "AMBARI_KERBEROS_REALM:global.security.kerberos.realm",
            "AMBARI_OIDC_REALM:global.security.auth.oidc.realm",
            "AMBARI_OIDC_ISSUER_URL:global.security.auth.oidc.issuerUrl",
            "AMBARI_OIDC_ISSUER_URL[/protocol/openid-connect/token]:auth.tokenUrl");

    @TempDir
    Path workDir;

    private final Gson gson = new Gson();
    private FakeAmbariServer ambari;
    private PerUserViewContext view;
    private CommandPlanFactory planFactory;
    private CommandService service;

    @BeforeEach
    void setUp() throws Exception {
        ambari = new FakeAmbariServer("c1");
        ambari.config("kerberos-env", Map.of("realm", "EXAMPLE.COM"));
        view = new PerUserViewContext("dynamic-values-parity", workDir).as("admin");
        when(view.context.getURLStreamProvider()).thenReturn(ambari.streamProvider());
        Cluster cluster = mock(Cluster.class);
        when(cluster.getName()).thenReturn("c1");
        when(view.context.getCluster()).thenReturn(cluster);
        KubernetesService k8s = mock(KubernetesService.class);
        CommandUtils commandUtils = new CommandUtils(view.context, k8s);
        planFactory = new CommandPlanFactory(view.context, gson, new WebHookConfigurationService(view.context, k8s),
                commandUtils);
        service = new CommandService(view.context, view.dataStore, mock(HelmService.class), k8s, commandUtils,
                planFactory, Executors.newSingleThreadScheduledExecutor(), Executors.newCachedThreadPool());
    }

    @AfterEach
    void tearDown() throws Exception {
        service.shutdown();
        ambari.close();
    }

    @Test
    void aSuffixedIssuerResolvesAlikeEverywhere() {
        ambari.config("oidc-env", Map.of("oidc_issuer_url", "https://sso.example.com/realms/odp/", "oidc_realm", "odp",
                "oidc_admin_url", "https://sso.example.com"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("global.security.kerberos.realm", "EXAMPLE.COM");
        expected.put("global.security.auth.oidc.realm", "odp");
        expected.put("global.security.auth.oidc.issuerUrl", "https://sso.example.com/realms/odp/");
        expected.put("auth.tokenUrl", "https://sso.example.com/realms/odp/protocol/openid-connect/token");
        assertSameEverywhere(expected, null);
    }

    @Test
    void aNonManagedContextResolvesAlikeEverywhere() {
        ambari.config("oidc-env", Map.of("oidc_issuer_url", "https://sso.example.com/realms/odp", "oidc_realm", "odp"));
        KdpsContextEntity context = new KdpsContextEntity();
        context.setId("corp");
        context.setName("corp");
        context.setKind(ContextService.KIND_EXTERNAL);
        context.setConfigJson("{\"realm\":\"CORP.EXAMPLE\",\"issuerUrl\":\"https://login.corp.example/realms/corp\"}");
        view.dataStore.store(context);

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("global.security.kerberos.realm", "CORP.EXAMPLE");
        expected.put("global.security.auth.oidc.realm", "CORP.EXAMPLE");
        expected.put("global.security.auth.oidc.issuerUrl", "https://login.corp.example/realms/corp");
        expected.put("auth.tokenUrl", "https://login.corp.example/realms/corp/protocol/openid-connect/token");
        assertSameEverywhere(expected, Map.of("platformContextId", "corp"));
    }

    @Test
    void aBlankIssuerIsLeftOutEverywhere() {
        // A context without a Keycloak address: the issuer cannot be built, and "/realms/odp" must not be written.
        ambari.config("oidc-env", Map.of("oidc_issuer_url", "", "oidc_realm", "odp", "oidc_admin_url", ""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("global.security.kerberos.realm", "EXAMPLE.COM");
        expected.put("global.security.auth.oidc.realm", "odp");
        assertSameEverywhere(expected, null);
    }

    /** What the plan, the release's Helm step and a dependency's Helm steps resolve from the same entries. */
    private void assertSameEverywhere(Map<String, String> expected, Map<String, Object> formValues) {
        MultivaluedHashMap<String, String> headers = new MultivaluedHashMap<>();
        headers.putSingle("Cookie", "AMBARISESSIONID=ops-session");
        URI baseUri = URI.create(ambari.baseUri());

        Map<String, String> plan = service.resolveDynamicOverrides(TOKENS, formValues, baseUri,
                AmbariActionClient.toAuthHeaders(headers));

        CommandEntity root = new CommandEntity();
        root.setId("parity-" + System.nanoTime());
        Map<String, Object> rootParams = new LinkedHashMap<>();
        rootParams.put("chart", "trino");
        rootParams.put("releaseName", "trino");
        rootParams.put("namespace", "analytics");
        rootParams.put("dynamicValues", TOKENS);
        rootParams.put("_baseUri", baseUri.toString());
        rootParams.put("_callerHeaders", AmbariActionClient.headersToPersistableMap(headers));
        if (formValues != null) rootParams.put("formValues", formValues);
        root.setParamsJson(gson.toJson(rootParams));
        planFactory.createRealChartInstallationCommands(root, "charts", true);
        Map<String, Object> helmStep = stepParams(root, CommandType.HELM_DEPLOY_DRY_RUN);

        Map<String, Object> dependency = new LinkedHashMap<>();
        dependency.put("chart", "kerberos-keytab-mutating-webhook");
        dependency.put("namespace", "ambari-mutating-webhooks");
        dependency.put("dynamicValues", TOKENS);
        Map<String, Object> dependencyParams = planFactory.dependencyReleaseParams(root, dependency,
                "kerberos-keytab-mutating-webhook", "http://ambari.test/commands", headers, baseUri);

        assertEquals(expected, plan, "the plan");
        assertEquals(expected, helmStep.get("_dynamicOverrides"), "the release's Helm step");
        assertEquals(expected, dependencyParams.get("_dynamicOverrides"), "the dependency's Helm steps");
    }

    private Map<String, Object> stepParams(CommandEntity root, CommandType type) {
        List<String> children = gson.fromJson(root.getChildListJson(), new TypeToken<ArrayList<String>>() {}.getType());
        for (String id : children) {
            CommandEntity step = view.dataStore.find(CommandEntity.class, id);
            if (type.name().equals(step.getType())) {
                return gson.fromJson(CommandUtils.resolveParamsPayload(step.getParamsJson()),
                        new TypeToken<Map<String, Object>>() {}.getType());
            }
        }
        throw new AssertionError("no " + type + " step among " + children);
    }
}
