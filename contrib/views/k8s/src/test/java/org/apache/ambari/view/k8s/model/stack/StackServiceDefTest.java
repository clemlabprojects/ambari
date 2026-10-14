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
}
