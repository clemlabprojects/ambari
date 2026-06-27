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

import org.apache.ambari.view.k8s.model.ResolvedContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link ServiceAdvisorService}: capability detection (pure) and — when a
 * {@code python3} runtime is present — the end-to-end run of the bundled advisor script.
 */
class ServiceAdvisorServiceTest {

    private final ServiceAdvisorService advisor = new ServiceAdvisorService();

    private static boolean python3Available() {
        try {
            return new ProcessBuilder("python3", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void detectCapabilities_fromContext_flagsWhatIsPresent() {
        ResolvedContext ctx = new ResolvedContext();
        ctx.setAtlasManaged(true);              // atlas present
        ctx.setRangerUrl("https://ranger:6182"); // ranger present (external-style url)
        ctx.setKerberosRealm("EXAMPLE.COM");      // kerberos present
        ctx.getResolvedFields().put("hive.hs2HostPort", "hs2.example.com:10001"); // hive present
        // no oidc.issuerUrl -> oidc absent

        Map<String, Boolean> caps = advisor.detectCapabilities(ctx);
        assertTrue(caps.get("atlas"));
        assertTrue(caps.get("ranger"));
        assertTrue(caps.get("hive"));
        assertTrue(caps.get("kerberos"));
        assertFalse(caps.get("oidc"));
        assertFalse(caps.get("trino"));
    }

    @Test
    void detectCapabilities_emptyContext_allFalse() {
        Map<String, Boolean> caps = advisor.detectCapabilities(new ResolvedContext());
        assertFalse(caps.get("atlas"));
        assertFalse(caps.get("ranger"));
        assertFalse(caps.get("hive"));
        assertFalse(caps.get("kerberos"));
    }

    @Test
    void detectCapabilities_nullContext_isEmptyNotNull() {
        assertNotNull(advisor.detectCapabilities(null));
        assertTrue(advisor.detectCapabilities(null).isEmpty());
    }

    @Test
    void advise_emptyFields_returnsEmptyWithoutInvokingPython() {
        assertTrue(advisor.advise("OPENMETADATA", new ResolvedContext(), List.of()).isEmpty());
        assertTrue(advisor.advise("OPENMETADATA", new ResolvedContext(), null).isEmpty());
    }

    @Test
    void advise_runsBundledAdvisor_andMapsRecommendationsToCapabilities() {
        assumeTrue(python3Available(), "python3 not available — skipping end-to-end advisor run");

        ResolvedContext ctx = new ResolvedContext();
        ctx.setAtlasManaged(true); // atlas present, hive absent
        List<Map<String, String>> fields = List.of(
                Map.of("name", "atlasFederation.enabled", "advisor", "recommend_atlas"),
                Map.of("name", "baseIngestion.hiveEnabled", "advisor", "recommend_hive"));

        List<ServiceAdvisorService.Recommendation> recs = advisor.advise("OPENMETADATA", ctx, fields);
        assertEquals(2, recs.size(), "one recommendation per advisor field");

        ServiceAdvisorService.Recommendation atlas = recs.stream()
                .filter(r -> "atlasFederation.enabled".equals(r.field)).findFirst().orElseThrow();
        ServiceAdvisorService.Recommendation hive = recs.stream()
                .filter(r -> "baseIngestion.hiveEnabled".equals(r.field)).findFirst().orElseThrow();

        assertTrue(atlas.recommend, "Atlas present -> recommended ON");
        assertFalse(hive.recommend, "Hive absent -> recommended OFF");
        assertNotNull(atlas.reason);
        assertFalse(atlas.reason.isBlank(), "each recommendation carries a human reason");
    }

    @Test
    void advise_unknownAdvisorName_isIgnored() {
        assumeTrue(python3Available(), "python3 not available — skipping end-to-end advisor run");
        List<Map<String, String>> fields = List.of(
                Map.of("name", "x.toggle", "advisor", "recommend_does_not_exist"));
        assertTrue(advisor.advise("OPENMETADATA", new ResolvedContext(), fields).isEmpty(),
                "an unknown advisor function yields no recommendation");
    }
}
