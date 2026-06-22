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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 *   - JWT lands at the per-source .token path (Ambari's credential store handles redaction)
 *   - dedup-append on ranger.tagsync.sources is idempotent across re-runs
 *   - service-name mapper is emitted only when both trino fields are present
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
        assertEquals("trino-trino-ns",
                props.get("ranger.tagsync.atlas.openmetadata.servicename.mapper.trino-clemlab.ranger.service"),
                "mapper line should be emitted when both trinoIngestionServiceFqn + rangerTrinoServiceName are set");

        // Drop one of the two → mapper line must be omitted (we don't want a half-mapping
        // that confuses Ranger TagSync's parser).
        spec.remove("rangerTrinoServiceName");
        Map<String, String> partial = CommandService.buildTagSyncRangerSiteOverrides(
                spec, "openmetadatarest", "https://om", "jwt", null);
        assertFalse(
                partial.keySet().stream().anyMatch(k -> k.contains("servicename.mapper")),
                "Partial trino mapping should not produce a mapper line");
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
