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

import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.apache.ambari.view.k8s.utils.FakeAmbariConfigs;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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

/**
 * Unit tests for {@link CommandService#buildTagSyncRangerSiteOverrides} — the pure
 * transform that converts a service.json {@code ranger-tagsync-source} entry into the
 * property delta written to Ambari's {@code ranger-tagsync-site} config.
 *
 * <p>These tests cover the contract the OM_RANGER_TAGSYNC_REGISTER step depends on:
 *   - keys are derived from the entry name (the operator's chosen source key)
 *   - source.class is set when declared, omitted otherwise
 *   - JWT lands at the per-source .token path, stored as an Ambari password property
 *   - dedup-append on ranger.tagsync.sources is idempotent across re-runs
 *   - service-name mapper is emitted only when both trino fields are present
 *   - the cluster's Atlas tag source is never touched
 *   - an unchanged registration writes no new config version (so no TagSync restart)
 *   - Ranger TagSync is restarted only right after KDPS changes its configuration, never for a
 *     restart Ambari has pending otherwise (an earlier failed restart is reported instead)
 *   - a token still valid in clear in the config history, and an Atlas source turned off, are reported
 *   - bad inputs raise IllegalArgumentException early instead of writing partial config
 */
class CommandServiceTagSyncRegisterTest {

    private static Map<String, Object> baseSpec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", "ranger-tagsync-source");
        spec.put("cm_name", "ranger-openmetadata-tagsync");
        spec.put("source_class",
                "org.apache.ranger.tagsync.source.openmetadatarest.OpenmetadataRESTTagSource");
        return spec;
    }

    @Test
    void rejectsWrongType() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", "ranger-plugin-settings");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CommandService.buildTagSyncRangerSiteOverrides(spec,
                        "openmetadatarest", "https://om", "jwt", null));
        assertTrue(ex.getMessage().contains("ranger-tagsync-source"),
                "Error must name the expected type so misuse is obvious in logs");
    }

    @Test
    void requiresEndpointEntryKeyAndJwt() {
        Map<String, Object> spec = baseSpec();
        assertThrows(IllegalArgumentException.class,
                () -> CommandService.buildTagSyncRangerSiteOverrides(spec, "", "https://om", "jwt", null),
                "blank entryKey should be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> CommandService.buildTagSyncRangerSiteOverrides(spec, "k", "", "jwt", null),
                "blank omEndpoint should be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> CommandService.buildTagSyncRangerSiteOverrides(spec, "k", "https://om", "", null),
                "blank jwt should be rejected");
    }

    @Test
    void writesEndpointTokenClassUnderSourceKey() {
        Map<String, String> props = CommandService.buildTagSyncRangerSiteOverrides(
                baseSpec(), "openmetadatarest", "https://om.example/api", "eyJabc.def.ghi", null);

        assertEquals("https://om.example/api",
                props.get("ranger.tagsync.source.openmetadatarest.endpoint"));
        assertEquals("eyJabc.def.ghi",
                props.get("ranger.tagsync.source.openmetadatarest.token"));
        assertEquals("org.apache.ranger.tagsync.source.openmetadatarest.OpenmetadataRESTTagSource",
                props.get("ranger.tagsync.source.openmetadatarest.class"));
        // Ranger's polling interval default — only set when not already present at the cluster
        assertEquals("30000",
                props.get("ranger.tagsync.source.openmetadatarest.cookies.timeout.millis"));
    }

    @Test
    void omitsClassWhenSpecHasNone() {
        Map<String, Object> spec = baseSpec();
        spec.remove("source_class");
        Map<String, String> props = CommandService.buildTagSyncRangerSiteOverrides(
                spec, "openmetadatarest", "https://om", "jwt", null);
        assertFalse(props.containsKey("ranger.tagsync.source.openmetadatarest.class"),
                "class entry should be skipped when not declared, not emitted as empty");
    }

    @Test
    void appendsSourceKeyToEmptySourcesList() {
        Map<String, String> props = CommandService.buildTagSyncRangerSiteOverrides(
                baseSpec(), "openmetadatarest", "https://om", "jwt", null);
        assertEquals("openmetadatarest", props.get("ranger.tagsync.sources"));
    }

    @Test
    void appendsSourceKeyToExistingSourcesListIdempotently() {
        // First run: no current sources → adds ours
        Map<String, String> first = CommandService.buildTagSyncRangerSiteOverrides(
                baseSpec(), "openmetadatarest", "https://om", "jwt", "atlas,file");
        assertEquals("atlas,file,openmetadatarest", first.get("ranger.tagsync.sources"));

        // Second run with current that ALREADY includes ours: no duplicate
        Map<String, String> second = CommandService.buildTagSyncRangerSiteOverrides(
                baseSpec(), "openmetadatarest", "https://om", "jwt", "atlas,file,openmetadatarest");
        assertEquals("atlas,file,openmetadatarest", second.get("ranger.tagsync.sources"),
                "Re-running must not duplicate the source key — updateClusterConfig will then no-op");
    }

    @Test
    void handlesWhitespaceAndEmptyTokensInCurrentSourcesCsv() {
        // Ambari config UIs sometimes round-trip CSVs with spaces around commas, or trailing
        // commas. The dedup logic must trim and skip empty tokens so we don't end up with
        // a leading comma or "atlas, " entries.
        Map<String, String> props = CommandService.buildTagSyncRangerSiteOverrides(
                baseSpec(), "openmetadatarest", "https://om", "jwt", "  atlas , ,file ,");
        assertEquals("atlas,file,openmetadatarest", props.get("ranger.tagsync.sources"));
    }

    @Test
    void writesServiceNameMapperOnlyWhenBothTrinoFieldsPresent() {
        Map<String, Object> spec = baseSpec();
        spec.put("trinoIngestionServiceFqn", "trino-clemlab");
        spec.put("rangerTrinoServiceName", "trino-trino-ns");
        Map<String, String> props = CommandService.buildTagSyncRangerSiteOverrides(
                spec, "openmetadatarest", "https://om", "jwt", null);
        // Key format = what OpenmetadataResourceMapper.getCustomRangerServiceName() reads
        // (RANGER-4978): ranger.tagsync.openmetadata.<component>.instance.<OM FQN>.ranger.service
        assertEquals("trino-trino-ns",
                props.get("ranger.tagsync.openmetadata.trino.instance.trino-clemlab.ranger.service"),
                "mapper line should be emitted when both trinoIngestionServiceFqn + rangerTrinoServiceName are set");
        assertFalse(props.containsKey("ranger.tagsync.source.openmetadatarest.component.tabletype"),
                "component tabletype is only pushed when the spec sets it");

        // Non-default component → key uses it AND the source's tabletype is pushed so the mapper
        // builds resources for that service type.
        spec.put("componentTableType", "hive");
        Map<String, String> hiveProps = CommandService.buildTagSyncRangerSiteOverrides(
                spec, "openmetadatarest", "https://om", "jwt", null);
        assertEquals("trino-trino-ns",
                hiveProps.get("ranger.tagsync.openmetadata.hive.instance.trino-clemlab.ranger.service"));
        assertEquals("hive", hiveProps.get("ranger.tagsync.source.openmetadatarest.component.tabletype"));
        spec.remove("componentTableType");

        // Drop one of the two → mapper line must be omitted (we don't want a half-mapping
        // that confuses Ranger TagSync's parser).
        spec.remove("rangerTrinoServiceName");
        Map<String, String> partial = CommandService.buildTagSyncRangerSiteOverrides(
                spec, "openmetadatarest", "https://om", "jwt", null);
        assertFalse(
                partial.keySet().stream().anyMatch(k -> k.contains(".instance.") && k.endsWith(".ranger.service")),
                "Partial trino mapping should not produce a mapper line");
    }

    @Test
    void copyHelmValueIntoSpecHonorsSettingsOverrideAndSkipsBlanks() {
        Map<String, Object> values = new java.util.HashMap<>();
        values.put("ranger", new java.util.HashMap<>(Map.of("tagSync",
                new java.util.HashMap<>(Map.of("trinoIngestionServiceFqn", " hive-clemlab ",
                                               "rangerTrinoServiceName", "")))));
        values.put("custom", new java.util.HashMap<>(Map.of("svc", "trino-trino-ns")));
        Map<String, Object> spec = baseSpec();
        CommandService.copyHelmValueIntoSpec(values, Collections.emptyMap(), "trino_fqn_helm_prop",
                "ranger.tagSync.trinoIngestionServiceFqn", spec, "trinoIngestionServiceFqn");
        CommandService.copyHelmValueIntoSpec(values, Collections.emptyMap(), "ranger_trino_service_helm_prop",
                "ranger.tagSync.rangerTrinoServiceName", spec, "rangerTrinoServiceName");
        assertEquals("hive-clemlab", spec.get("trinoIngestionServiceFqn"), "value is trimmed");
        assertFalse(spec.containsKey("rangerTrinoServiceName"), "blank helm value must not be copied");
        // settings override the helm path
        CommandService.copyHelmValueIntoSpec(values, Map.of("ranger_trino_service_helm_prop", "custom.svc"),
                "ranger_trino_service_helm_prop", "ranger.tagSync.rangerTrinoServiceName", spec, "rangerTrinoServiceName");
        assertEquals("trino-trino-ns", spec.get("rangerTrinoServiceName"));
        // null/empty values → no-op
        CommandService.copyHelmValueIntoSpec(null, null, "x", "a.b", spec, "zzz");
        assertFalse(spec.containsKey("zzz"));
    }

    @Test
    void neverTurnsOffTheAtlasTagSource() {
        // Forcing ranger.tagsync.source.atlas=false cut Atlas classifications off from Ranger on
        // every cluster that relies on them. Whatever the inputs, the delta must not mention it.
        Map<String, Object> spec = baseSpec();
        spec.put("trinoIngestionServiceFqn", "trino-sec");
        spec.put("rangerTrinoServiceName", "trino-sec-trino-ns");
        spec.put("componentTableType", "hive");
        for (String current : new String[] {null, "", "atlas", "atlas,openmetadatarest"}) {
            Map<String, String> props = CommandService.buildTagSyncRangerSiteOverrides(
                    spec, "openmetadatarest", "https://om", "jwt", current);
            assertFalse(props.containsKey("ranger.tagsync.source.atlas"),
                    "the Atlas source must be left as the cluster has it, got " + props);
            assertFalse(props.containsValue("false"), "nothing is turned off: " + props);
        }
    }

    // -------------------------------------------------------------------------
    // Token storage: password property + digest, so an unchanged registration
    // writes nothing although Ambari masks the stored token on every read.
    // -------------------------------------------------------------------------

    private static final String TOKEN_KEY = "ranger.tagsync.source.openmetadatarest.token";

    @Test
    void addsTheTokenDigestAndKeepsTheTokenWhenTheClusterHasAnother() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put(TOKEN_KEY, "eyJ.new");
        Map<String, String> current = Map.of(TOKEN_KEY, "SECRET:ranger-tagsync-site:3:" + TOKEN_KEY,
                TOKEN_KEY + ".sha256", "digest-of-the-old-token");

        Map<String, String> out = CommandService.withTagSyncTokenDigest(props, TOKEN_KEY, current);
        assertEquals("eyJ.new", out.get(TOKEN_KEY));
        assertEquals(64, out.get(TOKEN_KEY + ".sha256").length());
        assertNotEquals("digest-of-the-old-token", out.get(TOKEN_KEY + ".sha256"));
        assertEquals("eyJ.new", props.get(TOKEN_KEY), "the input map is not modified");
    }

    @Test
    void dropsTheTokenWhenTheClusterAlreadyHasThatSameOne() {
        Map<String, String> first = CommandService.withTagSyncTokenDigest(
                Map.of(TOKEN_KEY, "eyJ.same"), TOKEN_KEY, Map.of());
        Map<String, String> current = Map.of(TOKEN_KEY, "SECRET:ranger-tagsync-site:3:" + TOKEN_KEY,
                TOKEN_KEY + ".sha256", first.get(TOKEN_KEY + ".sha256"));

        Map<String, String> out = CommandService.withTagSyncTokenDigest(Map.of(TOKEN_KEY, "eyJ.same"), TOKEN_KEY, current);
        assertFalse(out.containsKey(TOKEN_KEY), "an unchanged token is not re-sent: its masked value cannot be compared");
        assertEquals(first.get(TOKEN_KEY + ".sha256"), out.get(TOKEN_KEY + ".sha256"));
    }

    @Test
    void theSameRegistrationTwiceWritesOneConfigVersion() throws Exception {
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        ambari.seed(CommandService.TAGSYNC_SITE, Map.of(
                "ranger.tagsync.source.atlas", "true",
                "ranger.tagsync.dest.ranger.endpoint", "https://ranger:6182"));
        Map<String, Object> spec = baseSpec();
        spec.put("source_key", "openmetadatarest");
        spec.put("trinoIngestionServiceFqn", "trino-sec");
        spec.put("rangerTrinoServiceName", "trino-sec-trino-ns");

        assertTrue(CommandService.writeTagSyncSite(ambari.client(), "c1", spec, "ranger-openmetadata-tagsync",
                "https://om.example/api", "eyJ.bot-token", "om-tagsync-om"), "the first registration writes");
        assertFalse(CommandService.writeTagSyncSite(ambari.client(), "c1", spec, "ranger-openmetadata-tagsync",
                "https://om.example/api", "eyJ.bot-token", "om-tagsync-om"),
                "an unchanged registration must write nothing, so Ranger TagSync is not restarted");
        assertEquals(1, ambari.puts.size());

        FakeAmbariConfigs.Stored stored = ambari.stored(CommandService.TAGSYNC_SITE);
        assertEquals("eyJ.bot-token", stored.properties.get(TOKEN_KEY));
        assertEquals("true", stored.attributes.get("password").get(TOKEN_KEY),
                "the token is a password property, masked in Ambari");
        assertFalse(ambari.puts.get(0).contains("\"ranger.tagsync.source.atlas\":\"false\""));
        assertEquals("true", stored.properties.get("ranger.tagsync.source.atlas"), "the Atlas source keeps running");
        assertEquals("trino-sec-trino-ns",
                stored.properties.get("ranger.tagsync.openmetadata.trino.instance.trino-sec.ranger.service"));
    }

    @Test
    void aNewTokenIsWrittenOnce() throws Exception {
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        ambari.seed(CommandService.TAGSYNC_SITE, Map.of("ranger.tagsync.source.atlas", "true"));
        CommandService.writeTagSyncSite(ambari.client(), "c1", baseSpec(), "e", "https://om/api", "eyJ.one", "t");

        assertTrue(CommandService.writeTagSyncSite(ambari.client(), "c1", baseSpec(), "e", "https://om/api", "eyJ.two", "t"),
                "a token regenerated in OpenMetadata reaches Ranger TagSync");
        assertFalse(CommandService.writeTagSyncSite(ambari.client(), "c1", baseSpec(), "e", "https://om/api", "eyJ.two", "t"));
        assertEquals(2, ambari.puts.size());
        assertEquals("eyJ.two", ambari.stored(CommandService.TAGSYNC_SITE).properties.get(TOKEN_KEY));
    }

    @Test
    void aTokenStoredInClearByAnEarlierVersionIsMaskedOnceAndReported() throws Exception {
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        ambari.seed(CommandService.TAGSYNC_SITE, new LinkedHashMap<>(CommandService.buildTagSyncRangerSiteOverrides(
                baseSpec(), "e", "https://om/api", "eyJ.one", null)));

        // Masking it now does not reach the earlier versions in Ambari's history: the operator must regenerate it.
        String warning = CommandService.clearTokenWarning(
                ambari.client().getDesiredConfig("c1", CommandService.TAGSYNC_SITE), TOKEN_KEY, "eyJ.one");
        assertNotNull(warning);
        assertTrue(warning.contains("in clear") && warning.contains("Settings > Bots > ingestion-bot"), warning);
        assertTrue(warning.contains("Re-register Hive ingestion"), "the pipelines need the new token too: " + warning);
        assertFalse(warning.contains("eyJ"), "the token never reaches a warning");

        assertTrue(CommandService.writeTagSyncSite(ambari.client(), "c1", baseSpec(), "e", "https://om/api", "eyJ.one", "t"));
        AmbariActionClient.DesiredConfig masked = ambari.client().getDesiredConfig("c1", CommandService.TAGSYNC_SITE);
        assertEquals("true", masked.attributes().get("password").get(TOKEN_KEY));
        assertNull(CommandService.clearTokenWarning(masked, TOKEN_KEY, "eyJ.one"), "said once, while it is in clear");
        assertFalse(CommandService.writeTagSyncSite(ambari.client(), "c1", baseSpec(), "e", "https://om/api", "eyJ.one", "t"));
        assertEquals(1, ambari.puts.size());
    }

    @Test
    void aTokenInClearThatIsNoLongerValidIsNotReported() throws Exception {
        // OpenMetadata only accepts the bot's current token: an old one in clear is harmless.
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        ambari.seed(CommandService.TAGSYNC_SITE, new LinkedHashMap<>(CommandService.buildTagSyncRangerSiteOverrides(
                baseSpec(), "e", "https://om/api", "eyJ.old", null)));

        assertNull(CommandService.clearTokenWarning(
                ambari.client().getDesiredConfig("c1", CommandService.TAGSYNC_SITE), TOKEN_KEY, "eyJ.new"));
    }

    // -------------------------------------------------------------------------
    // The Atlas tag source that earlier KDPS versions turned off.
    // -------------------------------------------------------------------------

    @Test
    void reportsTheAtlasTagSourceTurnedOffWhileAtlasIsInstalledAndLeavesItAlone() throws Exception {
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        ambari.seed(CommandService.TAGSYNC_SITE, Map.of("ranger.tagsync.source.atlas", "false"));
        ambari.componentHosts.put("ATLAS/ATLAS_SERVER", List.of("master02"));

        String warning = CommandService.atlasSourceOffWarning(ambari.client(), "c1",
                ambari.client().getDesiredConfig("c1", CommandService.TAGSYNC_SITE).properties());
        assertNotNull(warning);
        assertTrue(warning.contains("ranger.tagsync.source.atlas"), warning);
        assertTrue(warning.contains("do not reach Ranger"), warning);

        CommandService.writeTagSyncSite(ambari.client(), "c1", baseSpec(), "e", "https://om/api", "eyJ.one", "t");
        assertEquals("false", ambari.stored(CommandService.TAGSYNC_SITE).properties.get("ranger.tagsync.source.atlas"),
                "KDPS does not change the Atlas source either way");
    }

    @Test
    void saysNothingAboutTheAtlasTagSourceWithoutAtlasOrWhenItIsOn() throws Exception {
        FakeAmbariConfigs noAtlas = new FakeAmbariConfigs();
        assertNull(CommandService.atlasSourceOffWarning(noAtlas.client(), "c1", Map.of("ranger.tagsync.source.atlas", "false")),
                "no Atlas on the cluster: nothing is lost");

        FakeAmbariConfigs atlasOn = new FakeAmbariConfigs();
        atlasOn.componentHosts.put("ATLAS/ATLAS_SERVER", List.of("master02"));
        assertNull(CommandService.atlasSourceOffWarning(atlasOn.client(), "c1", Map.of("ranger.tagsync.source.atlas", "true")));
        assertNull(CommandService.atlasSourceOffWarning(atlasOn.client(), "c1", Map.of()), "Ranger's default is on");
    }

    // -------------------------------------------------------------------------
    // Ranger TagSync restart: right after KDPS changes its configuration, and
    // never for a restart Ambari has pending otherwise.
    // -------------------------------------------------------------------------

    private static final Duration RESTART_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void aWriteRestartsOnceAndAnUnchangedRunNever() throws Exception {
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        ambari.seed(CommandService.TAGSYNC_SITE, Map.of("ranger.tagsync.source.atlas", "true"));
        ambari.componentHosts.put("RANGER/RANGER_TAGSYNC", List.of("master03"));
        AmbariActionClient client = ambari.client();
        List<String> warnings = new ArrayList<>();

        for (int run = 0; run < 3; run++) {
            boolean wrote = CommandService.writeTagSyncSite(client, "c1", baseSpec(), "e", "https://om/api", "eyJ.one",
                    "om-tagsync-om");
            CommandService.restartTagSyncIfNeeded(client, "c1", "e", wrote, "om-tagsync-om", warnings, RESTART_TIMEOUT);
        }
        assertEquals(1, ambari.puts.size());
        assertEquals(1, ambari.restarts.size());
        assertTrue(warnings.isEmpty(), String.valueOf(warnings));
    }

    @Test
    void anAdministratorsChangeToAnotherTagSyncConfigIsNotRestarted() throws Exception {
        // Ambari flags Ranger TagSync for a restart after a change to any config type it reads, and does
        // not say which one: a pending restart found by a run that wrote nothing may be the
        // administrator's, left for a maintenance window.
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        ambari.seed(CommandService.TAGSYNC_SITE, Map.of("ranger.tagsync.source.atlas", "true"));
        ambari.componentHosts.put("RANGER/RANGER_TAGSYNC", List.of("master03"));
        AmbariActionClient client = ambari.client();
        List<String> warnings = new ArrayList<>();
        boolean wrote = CommandService.writeTagSyncSite(client, "c1", baseSpec(), "e", "https://om/api", "eyJ.one",
                "om-tagsync-om");
        CommandService.restartTagSyncIfNeeded(client, "c1", "e", wrote, "om-tagsync-om", warnings, RESTART_TIMEOUT);
        assertEquals(1, ambari.restarts.size());

        client.updateClusterConfig("c1", "atlas-tagsync-ssl",
                Map.of("xasecure.policymgr.clientssl.truststore.password", "changed"), "admin");
        assertTrue(ambari.staleComponents.contains("RANGER_TAGSYNC"), "the fake models Ambari's restart flag");
        // An OpenMetadata upgrade with nothing changed.
        boolean wroteAgain = CommandService.writeTagSyncSite(client, "c1", baseSpec(), "e", "https://om/api", "eyJ.one",
                "om-tagsync-om");
        assertFalse(wroteAgain);
        assertNull(CommandService.restartTagSyncIfNeeded(client, "c1", "e", wroteAgain, "om-tagsync-om", warnings,
                RESTART_TIMEOUT));

        assertEquals(1, ambari.restarts.size(), "the administrator's change waits for the administrator");
        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).contains("waiting for a restart"), warnings.get(0));
    }

    @Test
    void aRestartThatFailedIsReportedAndLeftToAnAdministrator() throws Exception {
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        ambari.seed(CommandService.TAGSYNC_SITE, Map.of("ranger.tagsync.source.atlas", "true"));
        ambari.componentHosts.put("RANGER/RANGER_TAGSYNC", List.of("master03"));
        ambari.restartOutcomes.add("FAILED");
        AmbariActionClient client = ambari.client();
        List<String> warnings = new ArrayList<>();

        boolean wrote = CommandService.writeTagSyncSite(client, "c1", baseSpec(), "e", "https://om/api", "eyJ.one",
                "om-tagsync-om");
        assertTrue(wrote);
        IllegalStateException failed = assertThrows(IllegalStateException.class, () -> CommandService.restartTagSyncIfNeeded(
                client, "c1", "e", true, "om-tagsync-om", warnings, RESTART_TIMEOUT));
        assertTrue(failed.getMessage().contains("restart Ranger TagSync in Ambari"), failed.getMessage());

        // The re-run has nothing left to write. Ambari cannot tell whose changes the pending restart is
        // for, so it is reported, not made: Ranger TagSync may still run without the source.
        boolean wroteAgain = CommandService.writeTagSyncSite(client, "c1", baseSpec(), "e", "https://om/api", "eyJ.one",
                "om-tagsync-om");
        assertFalse(wroteAgain);
        assertNull(CommandService.restartTagSyncIfNeeded(client, "c1", "e", false, "om-tagsync-om", warnings, RESTART_TIMEOUT));
        assertEquals(List.of("RANGER_TAGSYNC"), ambari.restarts);
        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).contains("does not run the OpenMetadata source yet"), warnings.get(0));
        assertTrue(warnings.get(0).contains("Restart Ranger TagSync in Ambari"), warnings.get(0));
    }

    @Test
    void aRestartPendingForSomeoneElsesChangeIsReportedNotMade() throws Exception {
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        // The desired version is not one KDPS wrote: an administrator changed the config in Ambari.
        ambari.seed(CommandService.TAGSYNC_SITE, Map.of("ranger.tagsync.source.atlas", "true"));
        ambari.componentHosts.put("RANGER/RANGER_TAGSYNC", List.of("master03"));
        ambari.staleComponents.add("RANGER_TAGSYNC");
        List<String> warnings = new ArrayList<>();

        assertNull(CommandService.restartTagSyncIfNeeded(ambari.client(), "c1", "e", false, "om-tagsync-om", warnings,
                RESTART_TIMEOUT));
        assertTrue(ambari.restarts.isEmpty());
        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).contains("waiting for a restart"), warnings.get(0));
    }

    @Test
    void everyOpenMetadataStepReportsATokenDueForRenewal() {
        // The Hive, Trino and Atlas federation pipelines run with the same bot token as Ranger TagSync.
        Map<String, Object> due = new LinkedHashMap<>();
        CommandService.putBotTokenLifetimeWarning(due, new org.apache.ambari.view.k8s.service.om.OmBotJwtClient.BotToken(
                "eyJ.x.y", java.time.Instant.now().plus(Duration.ofDays(10))));
        List<?> warnings = (List<?>) due.get("warnings");
        assertNotNull(warnings);
        assertTrue(String.valueOf(warnings.get(0)).contains("every OpenMetadata ingestion pipeline"), String.valueOf(warnings));

        Map<String, Object> notDue = new LinkedHashMap<>();
        CommandService.putBotTokenLifetimeWarning(notDue, new org.apache.ambari.view.k8s.service.om.OmBotJwtClient.BotToken(
                "eyJ.x.y", java.time.Instant.now().plus(Duration.ofDays(80))));
        CommandService.putBotTokenLifetimeWarning(notDue, new org.apache.ambari.view.k8s.service.om.OmBotJwtClient.BotToken(
                "eyJ.x.y", null));
        assertFalse(notDue.containsKey("warnings"), String.valueOf(notDue));
    }

    // -------------------------------------------------------------------------
    // renderTagSyncXmlSnippet — the snippet operators paste into an external
    // Ranger TagSync host's ranger-tagsync-site.xml. The test bar here is
    // structural correctness + redaction of sensitive values.
    // -------------------------------------------------------------------------

    @Test
    void rendersXmlSnippetWithEveryProperty() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("ranger.tagsync.source.openmetadatarest.endpoint", "https://om.example/api");
        props.put("ranger.tagsync.sources", "atlas,openmetadatarest");

        String xml = CommandService.renderTagSyncXmlSnippet(props);
        assertTrue(xml.contains("<configuration>"));
        assertTrue(xml.contains("</configuration>"));
        assertTrue(xml.contains("<name>ranger.tagsync.source.openmetadatarest.endpoint</name>"));
        assertTrue(xml.contains("<value>https://om.example/api</value>"));
        assertTrue(xml.contains("<name>ranger.tagsync.sources</name>"));
        assertTrue(xml.contains("<value>atlas,openmetadatarest</value>"));
    }

    @Test
    void redactsTokenAndPasswordValuesInRenderedXml() {
        // The snippet is appended to the deploy log + saved on the command status,
        // both of which can leak to operators who shouldn't see the raw JWT. Tokens
        // and password-typed values must be redacted; the real value still flows
        // to Ranger via the K8s Secret KDPS provisioned, the snippet just shows the
        // structure to paste.
        Map<String, String> props = new LinkedHashMap<>();
        props.put("ranger.tagsync.source.openmetadatarest.endpoint", "https://om.example/api");
        props.put("ranger.tagsync.source.openmetadatarest.token", "eyJabc.def.ghi-real-jwt");
        props.put("ranger.tagsync.source.openmetadatarest.password", "s3cret");

        String xml = CommandService.renderTagSyncXmlSnippet(props);
        assertFalse(xml.contains("eyJabc.def.ghi-real-jwt"),
                "JWT must not appear in the rendered snippet — it is captured in deploy logs");
        assertFalse(xml.contains("s3cret"),
                "Password must not appear in the rendered snippet");
        assertTrue(xml.contains("REDACTED"),
                "Redaction marker should make it obvious to operators that the value lives in the K8s Secret");
        // The endpoint is safe to render verbatim — operators need to see it
        assertTrue(xml.contains("https://om.example/api"));
    }

    @Test
    void rendersEmptyConfigurationBlockForEmptyPropsMap() {
        // Defensive: an empty map shouldn't crash the renderer — it just produces
        // an empty <configuration> block. The step body should never call us with
        // an empty map (it always builds at least 3-4 props), but be tolerant.
        String xml = CommandService.renderTagSyncXmlSnippet(new LinkedHashMap<>());
        assertTrue(xml.contains("<configuration>"));
        assertTrue(xml.contains("</configuration>"));
    }

    @Test
    void resultMapPreservesInsertionOrderForReproducibleConfigs() {
        // Ambari's config-diff UI is line-oriented; a stable property order keeps the
        // operator's `kdps-deploy → restart` rounds visually identical when nothing
        // material changed. LinkedHashMap is the contract — assert it.
        Map<String, String> props = CommandService.buildTagSyncRangerSiteOverrides(
                baseSpec(), "openmetadatarest", "https://om", "jwt", null);
        assertNotNull(props);
        // First few keys should appear in the same canonical order
        java.util.Iterator<String> it = props.keySet().iterator();
        assertEquals("ranger.tagsync.source.openmetadatarest.endpoint", it.next());
        assertEquals("ranger.tagsync.source.openmetadatarest.token", it.next());
        assertEquals("ranger.tagsync.source.openmetadatarest.class", it.next());
        // ...class default-fields next, then sources last
    }
}
