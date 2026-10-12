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
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.om.OmBotJwtClient;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.KdpsContextEntity;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.apache.ambari.view.k8s.utils.FakeAmbariConfigs;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;

/**
 * The "Enable Ranger TagSync" switch of a release decides whether KDPS touches the platform's Ranger
 * TagSync. The wizard sends the service's Ranger block for every release attached to a platform
 * context, so the service supporting TagSync must never be read as the operator asking for it: with
 * the switch off, the step only reads the cluster's ranger-tagsync-site to report what earlier KDPS
 * versions left there, changes nothing, and nothing is refused. With it on, a deploy that runs the
 * registration (direct Helm, on a platform KDPS may configure) and that the registration would fail
 * on is refused before Helm runs; a bot token that never expires is registered with a warning.
 */
class CommandServiceTagSyncGateTest {

    private static Map<String, Map<String, Object>> rangerSpec() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("type", "ranger-tagsync-settings");
        settings.put("endpoint_helm_prop", "ranger.tagSync.endpoint");
        settings.put("enabled_helm_prop", "ranger.tagSync.enabled");
        settings.put("airflow_enabled_helm_prop", "dependencies.enabled");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "ranger-tagsync-source");
        source.put("source_key", "openmetadatarest");
        Map<String, Map<String, Object>> ranger = new LinkedHashMap<>();
        ranger.put("ranger-tagsync-settings", settings);
        ranger.put("ranger-openmetadata-tagsync", source);
        return ranger;
    }

    private static Map<String, Object> values(Object enabled, boolean airflow, String endpoint) {
        Map<String, Object> tagSync = new HashMap<>();
        if (enabled != null) tagSync.put("enabled", enabled);
        if (endpoint != null) tagSync.put("endpoint", endpoint);
        Map<String, Object> values = new HashMap<>();
        values.put("ranger", new HashMap<>(Map.of("tagSync", tagSync)));
        values.put("dependencies", new HashMap<>(Map.of("enabled", airflow)));
        return values;
    }

    private static HelmDeployRequest request(Map<String, Object> values) {
        HelmDeployRequest r = new HelmDeployRequest();
        r.setReleaseName("om");
        r.setNamespace("om-ns");
        r.setServiceKey("OPENMETADATA");
        r.setRanger(rangerSpec());
        r.setValues(values);
        r.setFormValues(new HashMap<>());
        return r;
    }

    private static void inject(CommandService service, String field, Object value) throws Exception {
        Field f = CommandService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    @Test
    void readsTheSwitchFromTheValuesOnly() {
        Map<String, Object> settings = rangerSpec().get("ranger-tagsync-settings");
        assertTrue(CommandService.isTagSyncSwitchOn(values(true, true, null), settings));
        assertTrue(CommandService.isTagSyncSwitchOn(values("true", true, null), settings));
        assertFalse(CommandService.isTagSyncSwitchOn(values(false, true, null), settings));
        assertFalse(CommandService.isTagSyncSwitchOn(values(null, true, null), settings), "absent means off");
        assertFalse(CommandService.isTagSyncSwitchOn(null, settings));
        assertFalse(CommandService.isTagSyncSwitchOn(values(true, true, null), Map.of("enabled_helm_prop", "other.flag")),
                "the settings name the switch's path");
    }

    @Test
    @SuppressWarnings("unchecked")
    void planQueuesOnlyACheckWhenTheSwitchIsOff() throws Exception {
        // Releases deployed by earlier KDPS versions carry no switch value, so they all deploy with it
        // off; the check is how their operators hear what those versions left in ranger-tagsync-site.
        CommandService service = Mockito.mock(CommandService.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(true).when(service).platformContextWritable("default");
        CommandPlanFactory factory = Mockito.mock(CommandPlanFactory.class);
        inject(service, "commandPlanFactory", factory);
        Map<String, Object> params = new HashMap<>(Map.of("_platformContextId", "default"));

        service.planRangerTagSyncSources(new CommandEntity(), null, request(values(false, true, "https://om/api")), params);

        ArgumentCaptor<Map<String, Object>> stepParams = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(factory, Mockito.times(1)).createRangerTagSyncRegister(any(), anyMap(), stepParams.capture());
        assertEquals(true, stepParams.getValue().get("_tagSyncCheckOnly"));
        assertNotNull(stepParams.getValue().get("_tagSyncSettings"));
        assertFalse(params.containsKey("_tagSyncCheckOnly"), "the root's params, shared with later steps, are left alone");
        assertFalse(params.containsKey("_tagSyncSettings"));
    }

    @Test
    void planQueuesNothingWhenTheSwitchIsOffOnAPlatformKdpsDoesNotConfigure() throws Exception {
        CommandService service = Mockito.mock(CommandService.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(false).when(service).platformContextWritable("cdp-prod");
        CommandPlanFactory factory = Mockito.mock(CommandPlanFactory.class);
        inject(service, "commandPlanFactory", factory);
        Map<String, Object> params = new HashMap<>(Map.of("_platformContextId", "cdp-prod"));

        service.planRangerTagSyncSources(new CommandEntity(), null, request(values(false, true, "https://om/api")), params);

        Mockito.verify(factory, Mockito.never()).createRangerTagSyncRegister(any(), anyMap(), anyMap());
    }

    @Test
    void planQueuesTheRegistrationWhenTheSwitchIsOn() throws Exception {
        CommandService service = Mockito.mock(CommandService.class, Mockito.CALLS_REAL_METHODS);
        CommandPlanFactory factory = Mockito.mock(CommandPlanFactory.class);
        inject(service, "commandPlanFactory", factory);
        Map<String, Object> params = new HashMap<>();

        service.planRangerTagSyncSources(new CommandEntity(), null, request(values(true, true, "https://om/api")), params);

        Mockito.verify(factory, Mockito.times(1)).createRangerTagSyncRegister(any(), anyMap(), anyMap());
        assertNotNull(params.get("_tagSyncSettings"));
        assertFalse(params.containsKey("_tagSyncCheckOnly"));
    }

    @Test
    void submitRefusesNothingWhenTheSwitchIsOff() {
        assertNull(CommandService.describeTagSyncPrerequisiteProblem(request(values(false, false, null))),
                "TagSync off: no Airflow and no endpoint are fine");
        assertNull(CommandService.describeTagSyncPrerequisiteProblem(request(values(null, false, null))));
    }

    @Test
    void submitAcceptsTheSwitchOnWithAirflowAndAnEndpoint() {
        assertNull(CommandService.describeTagSyncPrerequisiteProblem(request(values(true, true, "https://om.example/api"))));
    }

    @Test
    void submitRefusesTheSwitchOnWithoutAirflow() {
        String problem = CommandService.describeTagSyncPrerequisiteProblem(request(values(true, false, "https://om/api")));
        assertNotNull(problem);
        assertTrue(problem.contains("Deploy bundled Airflow"), problem);
        assertTrue(problem.contains("Enable Ranger TagSync"), "names the way out: " + problem);
    }

    @Test
    void submitRefusesTheSwitchOnWithoutAnEndpoint() {
        String problem = CommandService.describeTagSyncPrerequisiteProblem(request(values(true, true, "  ")));
        assertNotNull(problem);
        assertTrue(problem.contains("OM API endpoint as seen from Ranger TagSync"), problem);
        assertFalse(problem.contains("Airflow"), problem);
    }

    private static CommandService serviceWhereContextIsWritable(String contextId, boolean writable) {
        CommandService service = Mockito.mock(CommandService.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(writable).when(service).platformContextWritable(contextId);
        return service;
    }

    private static HelmDeployRequest onContext(HelmDeployRequest r, String contextId) {
        r.setFormValues(new HashMap<>(Map.of("platformContextId", contextId)));
        return r;
    }

    @Test
    void submitRefusesWhenTheRegistrationWillRun() {
        CommandService service = serviceWhereContextIsWritable("default", true);
        assertNotNull(service.tagSyncSubmitProblem(onContext(request(values(true, false, "https://om/api")), "default")),
                "a direct Helm deploy on the managed cluster runs the registration");
    }

    @Test
    void submitDoesNotRefuseAGitOpsDeploy() {
        // A GitOps deploy never plans the registration: nothing would use the Airflow it asks for.
        CommandService service = serviceWhereContextIsWritable("default", true);
        HelmDeployRequest r = onContext(request(values(true, false, null)), "default");
        r.setDeploymentMode("FLUX_GITOPS");
        assertNull(service.tagSyncSubmitProblem(r));
    }

    @Test
    void submitDoesNotRefuseAContextWithAutoConfigurationOff() {
        // The registration is skipped at run time on such a context (remoteConfigGate).
        CommandService service = serviceWhereContextIsWritable("cdp-prod", false);
        assertNull(service.tagSyncSubmitProblem(onContext(request(values(true, false, null)), "cdp-prod")));
    }

    @Test
    void anExternalContextIsWrittenToUnlessAutoConfigurationIsOff() {
        assertTrue(ContextService.writesToPlatform((KdpsContextEntity) null), "the managed default");
        assertTrue(ContextService.writesToPlatform(context(ContextService.KIND_MANAGED, "{\"autoConfigureRemote\":\"false\"}")));
        assertTrue(ContextService.writesToPlatform(context(ContextService.KIND_EXTERNAL, null)), "on by default");
        assertTrue(ContextService.writesToPlatform(context(ContextService.KIND_CDP, "{\"autoConfigureRemote\":true}")));
        assertFalse(ContextService.writesToPlatform(context(ContextService.KIND_EXTERNAL, "{\"autoConfigureRemote\":\"false\"}")));
        assertFalse(ContextService.writesToPlatform(context(ContextService.KIND_REMOTE, "{\"autoConfigureRemote\":false}")));
    }

    private static KdpsContextEntity context(String kind, String configJson) {
        KdpsContextEntity e = new KdpsContextEntity();
        e.setId("ctx");
        e.setKind(kind);
        e.setConfigJson(configJson);
        return e;
    }

    @Test
    void submitLeavesServicesWithoutTagSyncAlone() {
        HelmDeployRequest r = request(values(true, false, null));
        r.setRanger(Map.of("ranger-plugin-settings", Map.of("repository_name_property", "ranger.serviceName")));
        assertNull(CommandService.describeTagSyncPrerequisiteProblem(r));
        r.setRanger(null);
        assertNull(CommandService.describeTagSyncPrerequisiteProblem(r));
    }

    private static Map<String, Object> stepParams(boolean replay) {
        Map<String, Object> params = new HashMap<>();
        params.put("releaseName", "om");
        params.put("namespace", "om-ns");
        params.put("_tagSyncEntryKey", "ranger-openmetadata-tagsync");
        params.put("_tagSyncSpec", rangerSpec().get("ranger-openmetadata-tagsync"));
        params.put("_tagSyncSettings", rangerSpec().get("ranger-tagsync-settings"));
        if (replay) params.put("_tagSyncReplay", true);
        return params;
    }

    @Test
    void aDeployStepWithTheSwitchOffTouchesNothing() throws Exception {
        CommandService service = Mockito.mock(CommandService.class, Mockito.CALLS_REAL_METHODS);
        CommandUtils utils = Mockito.mock(CommandUtils.class);
        Mockito.when(utils.loadValuesFromParams(anyMap())).thenReturn(values(false, false, null));
        inject(service, "commandUtils", utils);
        inject(service, "gson", new Gson());
        // No Kubernetes client and no Ambari: reaching either would throw.

        String result = service.registerRangerTagSyncSource(stepParams(false));

        Map<?, ?> json = new Gson().fromJson(result, Map.class);
        assertEquals(Boolean.TRUE, json.get("skipped"));
        assertTrue(String.valueOf(json.get("reason")).contains("turned off"), result);
    }

    @Test
    void theExplicitReRegisterActionStillRunsWithTheSwitchOff() throws Exception {
        CommandService service = Mockito.mock(CommandService.class, Mockito.CALLS_REAL_METHODS);
        CommandUtils utils = Mockito.mock(CommandUtils.class);
        Mockito.when(utils.loadValuesFromParams(anyMap())).thenReturn(values(false, false, null));
        inject(service, "commandUtils", utils);
        inject(service, "gson", new Gson());

        // Past the switch, the step goes on to resolve the endpoint (absent here) instead of skipping.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.registerRangerTagSyncSource(stepParams(true)));
        assertTrue(ex.getMessage().contains("OM endpoint"), ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // The step against an Ambari-managed cluster (FakeAmbariConfigs), as a deploy
    // and as the explicit "Re-register Ranger TagSync source" action.
    // -------------------------------------------------------------------------

    private static final String TOKEN_KEY = "ranger.tagsync.source.openmetadatarest.token";

    private static String jwtExpiringIn(Duration d) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String claims = "{\"sub\":\"ingestion-bot\",\"isBot\":true,\"exp\":" + Instant.now().plus(d).getEpochSecond() + "}";
        return b64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    /** A token OpenMetadata creates the bot with: no expiry claim. */
    private static final String UNLIMITED_JWT = "eyJhbGciOiJSUzI1NiJ9."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "{\"sub\":\"ingestion-bot\",\"isBot\":true}".getBytes(StandardCharsets.UTF_8)) + ".sig";

    /** The step's service on the managed cluster {@code c1} of {@code ambari}, reading {@code values}. */
    private static CommandService managedService(FakeAmbariConfigs ambari, Map<String, Object> values) throws Exception {
        CommandService service = Mockito.mock(CommandService.class, Mockito.CALLS_REAL_METHODS);
        CommandUtils utils = Mockito.mock(CommandUtils.class);
        Mockito.when(utils.loadValuesFromParams(anyMap())).thenReturn(values);
        inject(service, "commandUtils", utils);
        inject(service, "gson", new Gson());
        ViewContext ctx = Mockito.mock(ViewContext.class);
        Mockito.when(ctx.getURLStreamProvider()).thenReturn(ambari);
        inject(service, "ctx", ctx);
        // The managed default context.
        Mockito.doReturn(null).when(service).resolvePlatformContextForStep(anyMap());
        return service;
    }

    private static Map<String, Object> managedStepParams(boolean replay) {
        Map<String, Object> params = stepParams(replay);
        params.put("_cluster", "c1");
        params.put("_baseUri", "http://ambari:8080");
        return params;
    }

    private static FakeAmbariConfigs clusterWithAtlasSourceTurnedOff() {
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        ambari.seed(CommandService.TAGSYNC_SITE, Map.of("ranger.tagsync.source.atlas", "false"));
        ambari.componentHosts.put("ATLAS/ATLAS_SERVER", List.of("master02"));
        ambari.componentHosts.put("RANGER/RANGER_TAGSYNC", List.of("master03"));
        return ambari;
    }

    @Test
    void aDeployWithTheSwitchOffReportsTheAtlasSourceAnEarlierVersionTurnedOffAndChangesNothing() throws Exception {
        FakeAmbariConfigs ambari = clusterWithAtlasSourceTurnedOff();
        CommandService service = managedService(ambari, values(false, false, null));

        Map<?, ?> json = new Gson().fromJson(service.registerRangerTagSyncSource(managedStepParams(false)), Map.class);

        assertEquals(Boolean.TRUE, json.get("skipped"));
        List<?> warnings = (List<?>) json.get("warnings");
        assertNotNull(warnings, String.valueOf(json));
        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertTrue(String.valueOf(warnings.get(0)).contains("ranger.tagsync.source.atlas"), String.valueOf(warnings));
        assertTrue(ambari.puts.isEmpty(), "nothing is written");
        assertTrue(ambari.restarts.isEmpty(), "Ranger TagSync is not restarted");
        Mockito.verify(service, Mockito.never()).readOmBotToken(any(), any(), anyMap());
    }

    @Test
    void aDeployWithTheSwitchOffReportsATokenAnEarlierVersionLeftInClear() throws Exception {
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        // What earlier KDPS versions wrote on every deploy: the source, a token that never expires, in clear.
        Map<String, Object> spec = new LinkedHashMap<>(rangerSpec().get("ranger-openmetadata-tagsync"));
        ambari.seed(CommandService.TAGSYNC_SITE, new LinkedHashMap<>(CommandService.buildTagSyncRangerSiteOverrides(
                spec, "ranger-openmetadata-tagsync", "https://om.example/api", UNLIMITED_JWT, "atlas")));
        CommandService service = managedService(ambari, values(false, false, null));

        Map<?, ?> json = new Gson().fromJson(service.registerRangerTagSyncSource(managedStepParams(false)), Map.class);

        List<?> warnings = (List<?>) json.get("warnings");
        assertNotNull(warnings, String.valueOf(json));
        String warning = String.valueOf(warnings.get(0));
        assertTrue(warning.contains("https://om.example/api"), "names the registration: " + warning);
        assertTrue(warning.contains("in clear") && warning.contains("never expires"), warning);
        assertTrue(warning.contains("Settings > Bots > ingestion-bot"), warning);
        assertTrue(warning.contains("remove the OpenMetadata source"), "the way out without TagSync: " + warning);
        assertTrue(warning.contains("Enable Ranger TagSync"), "the way out with TagSync: " + warning);
        assertFalse(warning.contains("eyJ"), "the token never reaches a warning");
        assertTrue(ambari.puts.isEmpty());
        assertTrue(ambari.restarts.isEmpty());
    }

    @Test
    void aDeployWithTheSwitchOffOnACleanClusterSaysNothing() throws Exception {
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        ambari.seed(CommandService.TAGSYNC_SITE, Map.of("ranger.tagsync.source.atlas", "true"));
        ambari.componentHosts.put("ATLAS/ATLAS_SERVER", List.of("master02"));
        CommandService service = managedService(ambari, values(false, false, null));

        Map<?, ?> json = new Gson().fromJson(service.registerRangerTagSyncSource(managedStepParams(false)), Map.class);

        assertEquals(Boolean.TRUE, json.get("skipped"));
        assertNull(json.get("warnings"), String.valueOf(json));
    }

    @Test
    void aDeployWithATokenThatNeverExpiresRegistersWithAWarning() throws Exception {
        // OpenMetadata creates the bot that way and KDPS cannot rotate it: TagSync still gets its source.
        FakeAmbariConfigs ambari = clusterWithAtlasSourceTurnedOff();
        CommandService service = managedService(ambari, values(true, true, "https://om.example/api"));
        Mockito.doReturn(new OmBotJwtClient.BotToken(UNLIMITED_JWT, null))
                .when(service).readOmBotToken(any(), any(), anyMap());

        Map<?, ?> json = new Gson().fromJson(service.registerRangerTagSyncSource(managedStepParams(false)), Map.class);

        assertNotEquals(Boolean.TRUE, json.get("skipped"), String.valueOf(json));
        List<?> warnings = (List<?>) json.get("warnings");
        assertTrue(warnings.stream().anyMatch(w -> String.valueOf(w).contains("never expires")), String.valueOf(warnings));
        assertTrue(warnings.stream().anyMatch(w -> String.valueOf(w).contains("ranger.tagsync.source.atlas")),
                "the lost Atlas source is reported whatever happens to the token: " + warnings);
        assertFalse(ambari.puts.isEmpty(), "the source is registered");
    }

    @Test
    void theExplicitActionRegistersATokenThatNeverExpiresAndReportsTheAtlasSource() throws Exception {
        FakeAmbariConfigs ambari = clusterWithAtlasSourceTurnedOff();
        CommandService service = managedService(ambari, values(true, true, "https://om.example/api"));
        Mockito.doReturn(new OmBotJwtClient.BotToken(UNLIMITED_JWT, null))
                .when(service).readOmBotToken(any(), any(), anyMap());

        Map<?, ?> json = new Gson().fromJson(service.registerRangerTagSyncSource(managedStepParams(true)), Map.class);

        List<?> warnings = (List<?>) json.get("warnings");
        assertTrue(warnings.stream().anyMatch(w -> String.valueOf(w).contains("never expires")), String.valueOf(warnings));
        assertTrue(warnings.stream().anyMatch(w -> String.valueOf(w).contains("ranger.tagsync.source.atlas")),
                String.valueOf(warnings));
        assertFalse(ambari.puts.isEmpty(), "the source is registered");
    }

    @Test
    void aDeployWithAnExpiringTokenRegistersOnceAndAnUnchangedUpgradeChangesNothing() throws Exception {
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        ambari.seed(CommandService.TAGSYNC_SITE, Map.of("ranger.tagsync.source.atlas", "true"));
        ambari.componentHosts.put("ATLAS/ATLAS_SERVER", List.of("master02"));
        ambari.componentHosts.put("RANGER/RANGER_TAGSYNC", List.of("master03"));
        CommandService service = managedService(ambari, values(true, true, "https://om.example/api"));
        Mockito.doReturn(new OmBotJwtClient.BotToken(jwtExpiringIn(Duration.ofDays(90)), Instant.now().plus(Duration.ofDays(90))))
                .when(service).readOmBotToken(any(), any(), anyMap());

        Map<?, ?> first = new Gson().fromJson(service.registerRangerTagSyncSource(managedStepParams(false)), Map.class);
        Map<?, ?> again = new Gson().fromJson(service.registerRangerTagSyncSource(managedStepParams(false)), Map.class);

        assertEquals(Boolean.TRUE, first.get("configChanged"));
        assertNotNull(first.get("restartRequestId"));
        assertEquals(Boolean.FALSE, again.get("configChanged"));
        assertNull(again.get("restartRequestId"));
        assertNull(again.get("warnings"), String.valueOf(again));
        assertEquals(1, ambari.puts.size());
        assertEquals(List.of("RANGER_TAGSYNC"), ambari.restarts);
        assertEquals("true", ambari.stored(CommandService.TAGSYNC_SITE).properties.get("ranger.tagsync.source.atlas"));
        assertEquals("true", ambari.stored(CommandService.TAGSYNC_SITE).attributes.get("password").get(TOKEN_KEY));
    }
}
