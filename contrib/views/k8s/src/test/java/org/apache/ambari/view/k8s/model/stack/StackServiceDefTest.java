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

package org.apache.ambari.view.k8s.model.stack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service definition the UI receives is this class serialised, and properties it does not declare
 * are dropped when service.json is read. The replayable actions (the "Re-register ..." entries of a
 * release row) must survive, with the confirmation the TagSync one asks for.
 */
class StackServiceDefTest {

    @Test
    void openMetadataKeepsItsReplayableActions() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/KDPS/services/OPENMETADATA/service.json")) {
            assertNotNull(in, "OPENMETADATA/service.json must be on the classpath");
            StackServiceDef def = new ObjectMapper().readValue(in, StackServiceDef.class);

            assertNotNull(def.replayableActions, "the Releases page builds its Re-register entries from these");
            Map<String, Object> tagSync = def.replayableActions.stream()
                    .filter(a -> "om-ranger-tagsync".equals(a.get("key")))
                    .findFirst().orElseThrow();
            assertEquals("actions/om-ranger-tagsync", tagSync.get("endpoint"));
            @SuppressWarnings("unchecked")
            Map<String, Object> warn = (Map<String, Object>) tagSync.get("warnUnlessValueTrue");
            assertEquals("ranger.tagSync.enabled", warn.get("path"));
        }
    }

    /**
     * Trino's chart was published as clemlab-trino until 1.43.x and as trino from 1.44.0: a release installed from
     * clemlab-trino is upgraded with trino, and redeployed at its own version with clemlab-trino, the only name that
     * version exists under.
     */
    @Test
    void trinoReleasesInstalledAsClemlabTrinoMoveToTheTrinoChartFrom1440() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/KDPS/services/TRINO/service.json")) {
            assertNotNull(in, "TRINO/service.json must be on the classpath");
            StackServiceDef def = new ObjectMapper().readValue(in, StackServiceDef.class);

            assertEquals("trino", def.currentChart("clemlab-trino", def.version), "the catalog version");
            assertEquals("trino", def.currentChart("clemlab-trino", "1.44.0"));
            assertEquals("trino", def.currentChart("clemlab-trino", null), "no version: Helm takes the newest");
            assertEquals("clemlab-trino", def.currentChart("clemlab-trino", "1.43.21"), "a redeploy at its version");
            assertEquals("trino", def.currentChart("trino", "1.44.1"));
            assertEquals("my-trino", def.currentChart("my-trino", "1.44.1"), "a chart the operator named keeps its name");
        }
    }

    @Test
    void chartVersionsCompareNumerically() {
        assertTrue(StackServiceDef.compareVersions("1.44.0", "1.43.21") > 0);
        assertTrue(StackServiceDef.compareVersions("1.9.0", "1.10.0") < 0);
        assertEquals(0, StackServiceDef.compareVersions("1.44", "1.44.0"));
    }
}
