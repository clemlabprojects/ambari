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

import org.apache.ambari.view.AmbariStreamProvider;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.ViewDefinition;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.resources.CommandResource;
import org.apache.ambari.view.k8s.service.deployment.DeploymentBackend;
import org.apache.ambari.view.k8s.utils.AmbariLoopbackUrlResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 'Let KDPS manage catalogs' (kdps.serviceUser.enabled) is a password login, which Trino only accepts over HTTPS, so
 * the Trino chart refuses it without 'Enable internal HTTPS' (server.config.https.enabled). The option is on by default
 * and internal HTTPS can be turned off, so a deploy that reaches Helm with both would only fail at the dry run. The
 * deploy is checked when it is submitted: the wizard's default is turned off and said so in the operation log, and a
 * request that asks for both is refused (HTTP 400) with the two options named as the wizard shows them.
 */
class CommandServiceTrinoServiceUserHttpsTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "trino";
    private static final String INSTANCE = "trino-https-test";

    @TempDir
    Path tmp;

    private ViewContext ctx;
    private KubernetesService k8s;
    private DeploymentBackend direct;
    private final AtomicReference<HelmDeployRequest> sentRequest = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", tmp.resolve("work").toString()));
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getInstanceName()).thenReturn("kdps");
        when(ctx.getInstanceData(anyString())).thenReturn(null);
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
        DataStore ds = mock(DataStore.class);
        when(ctx.getDataStore()).thenReturn(ds);

        k8s = mock(KubernetesService.class);
        direct = mock(DeploymentBackend.class);
        when(direct.apply(any(), any())).thenAnswer(inv -> {
            sentRequest.set(inv.getArgument(0));
            return "command-1";
        });
    }

    @AfterEach
    void unregister() throws Exception {
        registry(CommandService.class).remove(INSTANCE);
        registry(CommandLogService.class).remove(INSTANCE);
    }

    /** A CommandService wired to the mocks, without the executors and singletons its constructor starts. */
    private CommandService service(CommandLogService log) throws Exception {
        CommandService service = mock(CommandService.class, CALLS_REAL_METHODS);
        set(service, "ctx", ctx);
        set(service, "kubernetesService", k8s);
        set(service, "commandLogService", log);
        Map<String, DeploymentBackend> backends = new ConcurrentHashMap<>();
        backends.put("DIRECT_HELM", direct);
        set(service, "deploymentBackends", backends);
        return service;
    }

    private static void set(CommandService service, String field, Object value) throws Exception {
        Field f = CommandService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    private String submit(HelmDeployRequest request, String version) throws Exception {
        return service(mock(CommandLogService.class)).submitDeploy(request, "charts", version, null, null, null, null, null);
    }

    /** Trino chart values with the option and, when not {@code null}, internal HTTPS set as given. */
    private static Map<String, Object> values(boolean serviceUser, Boolean https) {
        Map<String, Object> values = new LinkedHashMap<>();
        CommandService.putDotted(values, CommandService.KDPS_SERVICE_USER_ENABLED, serviceUser);
        if (https != null) {
            CommandService.putDotted(values, CommandService.TRINO_HTTPS_ENABLED, https);
        }
        return values;
    }

    /** A deploy sent straight to the API: chart values only, no wizard form. */
    private static HelmDeployRequest apiRequest(Map<String, Object> values) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setChart("trinodb/trino");
        request.setReleaseName(RELEASE);
        request.setNamespace(NS);
        request.setServiceKey("TRINO");
        request.setDeploymentMode("DIRECT_HELM");
        request.setValues(values);
        return request;
    }

    /** A deploy from the wizard: its form holds the option as the operator left it. */
    private static HelmDeployRequest wizardRequest(Map<String, Object> values, boolean optionInForm) {
        HelmDeployRequest request = apiRequest(values);
        Map<String, Object> form = new LinkedHashMap<>(Map.of("releaseName", RELEASE, "namespace", NS));
        CommandService.putDotted(form, CommandService.KDPS_SERVICE_USER_ENABLED, optionInForm);
        request.setFormValues(form);
        return request;
    }

    private static void assertNamesBothOptions(String message) {
        assertTrue(message.contains("'Let KDPS manage catalogs'"), message);
        assertTrue(message.contains("'Enable internal HTTPS'"), message);
    }

    @Test
    void anApiRequestAskingForTheOptionWithHttpsOffGetsA400NamingBothOptions() throws Exception {
        registry(CommandService.class).put(INSTANCE, service(mock(CommandLogService.class)));

        Response response = operatorResource().submitDeploy(apiRequest(values(true, false)), "charts", "1.43.16", null,
                headers(), uriInfo());

        assertEquals(400, response.getStatus());
        String error = String.valueOf(((Map<?, ?>) response.getEntity()).get("error"));
        assertNamesBothOptions(error);
        assertTrue(error.contains("Turn internal HTTPS on, or turn 'Let KDPS manage catalogs' off"), error);
        verifyNoInteractions(direct);
    }

    @Test
    void anApiRequestAskingForTheOptionWithoutHttpsIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> submit(apiRequest(values(true, null)), "1.43.16"));

        assertNamesBothOptions(refused.getMessage());
        assertNull(sentRequest.get(), "nothing is deployed");
    }

    @Test
    void theWizardDefaultIsTurnedOffWhenInternalHttpsIsOff() throws Exception {
        // As a page without the wizard binding sends it: internal HTTPS unticked, so the chart value is left at the
        // chart's default (off), and the hidden checkbox still holds its default.
        CommandLogService log = mock(CommandLogService.class);

        service(log).submitDeploy(wizardRequest(values(true, null), true), "charts", "1.43.16", null, null, null, null, null);

        HelmDeployRequest sent = sentRequest.get();
        assertNotNull(sent, "the deploy goes ahead");
        assertEquals(false, ConfigResolutionService.getByDottedPath(sent.getValues(), CommandService.KDPS_SERVICE_USER_ENABLED));
        assertEquals(false, ConfigResolutionService.getByDottedPath(sent.getFormValues(), CommandService.KDPS_SERVICE_USER_ENABLED));
        assertNull(ConfigResolutionService.getByDottedPath(sent.getValues(), CommandService.TRINO_HTTPS_ENABLED));
        verify(log).append(eq("command-1"), contains("'Let KDPS manage catalogs' was turned off"));
    }

    @Test
    void bothOptionsSetInTheRequestAreRefusedEvenWithTheWizardForm() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> submit(wizardRequest(values(true, false), true), "1.43.16"));

        assertNamesBothOptions(refused.getMessage());
        assertNull(sentRequest.get());
    }

    @Test
    void theOptionInTheValuesButNotInTheFormIsRefused() {
        // The form left the option off: the true in the values was asked for, not a default.
        assertThrows(IllegalArgumentException.class, () -> submit(wizardRequest(values(true, null), false), "1.43.16"));
    }

    @Test
    void withInternalHttpsTheOptionStaysOn() throws Exception {
        CommandLogService log = mock(CommandLogService.class);

        service(log).submitDeploy(wizardRequest(values(true, true), true), "charts", "1.43.16", null, null, null, null, null);

        assertEquals(true, ConfigResolutionService.getByDottedPath(sentRequest.get().getValues(),
                CommandService.KDPS_SERVICE_USER_ENABLED));
        verify(log, never()).append(anyString(), contains("Let KDPS manage catalogs"));
    }

    @Test
    void theOptionTurnedOffByTheWizardNeedsNoParticularChartVersion() {
        // The wizard writes the option off into the values while its hidden checkbox keeps the default: the chart
        // reads the values, so an older chart without the option is not refused for it.
        assertDoesNotThrow(() -> submit(wizardRequest(values(false, null), true), "1.43.12"));
        assertNotNull(sentRequest.get());

        // With internal HTTPS the option is on, and still needs a chart that has it.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> submit(wizardRequest(values(true, true), true), "1.43.12"));
        assertTrue(refused.getMessage().contains("'kdps.serviceUser.enabled' is enabled, which needs chart version >=1.43.13"),
                refused.getMessage());
    }

    @Test
    void onlyAServiceThatHasTheOptionIsChecked() {
        StackServiceDef superset = new StackDefinitionService().getServiceDefinition("SUPERSET");
        HelmDeployRequest request = apiRequest(values(true, null));
        request.setServiceKey("SUPERSET");

        assertNull(CommandService.checkServiceUserNeedsHttps(request, superset));
        assertEquals(true, ConfigResolutionService.getByDottedPath(request.getValues(), CommandService.KDPS_SERVICE_USER_ENABLED));
    }

    @Test
    void theOpenMetadataTrinoIngestionOnATrinoReleaseWithInternalHttpsOffAsksForInternalHttpsFirst() throws Exception {
        // The Trino release was deployed with internal HTTPS unticked: the HTTPS value is left at the chart's default
        // (off) and 'Let KDPS manage catalogs' is off. The wizard only shows that option once internal HTTPS is on.
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(values(false, null));
        CommandService service = service(mock(CommandLogService.class));
        doReturn(new String[] {NS, RELEASE}).when(service).trinoReleaseFromServiceHost(anyString());
        HelmDeployRequest openMetadata = new HelmDeployRequest();
        openMetadata.setServiceKey("OPENMETADATA");
        openMetadata.setReleaseName("openmetadata");
        openMetadata.setNamespace(NS);
        Map<String, Object> omValues = new LinkedHashMap<>();
        CommandService.putDotted(omValues, "baseIngestion.trinoEnabled", true);
        openMetadata.setValues(omValues);
        openMetadata.setFormValues(new LinkedHashMap<>(Map.of("ui_trino_host", "trino.analytics.svc.cluster.local:8443")));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.applyTrinoBaseIngestion(openMetadata));

        assertTrue(refused.getMessage().startsWith("Trino base ingestion needs 'Enable internal HTTPS' and 'Let KDPS"
                + " manage catalogs' on Trino release analytics/trino"), refused.getMessage());
        assertTrue(refused.getMessage().contains("turn 'Enable internal HTTPS' on, then 'Let KDPS manage catalogs'"),
                refused.getMessage());
    }

    // ------------------------------------------------------------------------------------------------------------

    /** The deploy endpoint as an operator calls it. */
    private CommandResource operatorResource() {
        ViewContext resourceCtx = mock(ViewContext.class);
        when(resourceCtx.getProperties()).thenReturn(Map.of(
                "view.operator.users", "ops",
                "k8s.view.working.dir", tmp.resolve("resource").toString()));
        when(resourceCtx.getUsername()).thenReturn("ops");
        when(resourceCtx.getLoggedinUser()).thenReturn("ops");
        when(resourceCtx.getInstanceName()).thenReturn(INSTANCE);
        when(resourceCtx.getViewName()).thenReturn("K8S-VIEW");
        ViewDefinition definition = mock(ViewDefinition.class);
        when(definition.getVersion()).thenReturn("1.0.0");
        when(resourceCtx.getViewDefinition()).thenReturn(definition);
        when(resourceCtx.getAmbariProperty(AmbariLoopbackUrlResolver.LOOPBACK_HOST_PROPERTY)).thenReturn("ambari.test");
        AmbariStreamProvider ambari = mock(AmbariStreamProvider.class);
        when(resourceCtx.getAmbariStreamProvider()).thenReturn(ambari);
        try {
            when(ambari.readFrom(anyString(), eq("GET"), (String) isNull(), anyMap()))
                    .thenAnswer(call -> new ByteArrayInputStream("{\"items\":[]}".getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return new CommandResource(resourceCtx, null, null);
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        return headers;
    }

    private static UriInfo uriInfo() {
        UriInfo ui = mock(UriInfo.class);
        when(ui.getBaseUri()).thenReturn(URI.create("http://ambari.test:8080/api/v1/"));
        return ui;
    }

    @SuppressWarnings("unchecked")
    private static <T> ConcurrentMap<String, T> registry(Class<T> type) throws Exception {
        Field f = type.getDeclaredField("INSTANCES");
        f.setAccessible(true);
        return (ConcurrentMap<String, T>) f.get(null);
    }
}
