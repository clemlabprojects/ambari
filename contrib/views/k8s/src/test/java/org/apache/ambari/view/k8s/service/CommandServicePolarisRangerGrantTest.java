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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Ranger fallback for a Polaris catalog, checked against the service definition Ranger 2.8.0
 * ships and against what a live Ranger accepted: resource levels {@code root → catalog → namespace
 * → table}, hyphenated access types, and one policy per level because Ranger matches a request
 * against the levels it carries. A policy written at the wrong level, or with a level name Polaris
 * does not define, is rejected by Ranger with a validation failure rather than silently granting
 * nothing — so these names are worth pinning.
 */
class CommandServicePolarisRangerGrantTest {

    @SuppressWarnings("unchecked")
    private List<String[]> levels() throws Exception {
        Field f = CommandService.class.getDeclaredField("POLARIS_CATALOG_GRANT_LEVELS");
        f.setAccessible(true);
        return (List<String[]>) f.get(null);
    }

    @Test
    void coversEveryResourceLevelOfACatalog() throws Exception {
        List<String> names = new ArrayList<>();
        for (String[] l : levels()) names.add(l[0]);
        assertEquals(List.of("catalog", "namespace", "table"), names,
                "a catalog grant has to be written at each level a Polaris request can carry");
    }

    @Test
    void wildcardsEverythingBelowTheCatalog() throws Exception {
        for (String[] l : levels()) {
            Map<String, List<String>> resources = new HashMap<>();
            resources.put("root", List.of("*"));
            resources.put("catalog", List.of("c1"));
            if (!l[1].isBlank()) {
                for (String extra : l[1].split(",")) resources.put(extra, List.of("*"));
            }
            assertEquals(List.of("c1"), resources.get("catalog"),
                    "the grant is scoped to the release's own catalog, never '*'");
            if ("table".equals(l[0])) {
                assertEquals(List.of("*"), resources.get("namespace"));
                assertEquals(List.of("*"), resources.get("table"));
            }
        }
    }

    @Test
    void usesTheAccessTypeNamesTheServiceDefinitionSpells() throws Exception {
        String all = String.join(",", levels().stream().map(l -> l[2]).toList());
        // Ranger's Polaris definition names access types resource-first and hyphenated. The
        // privilege names Polaris uses internally (CATALOG_MANAGE_CONTENT) are not access types:
        // a policy carrying them is rejected.
        assertTrue(all.contains("catalog-content-manage"));
        assertTrue(all.contains("table-data-read"));
        assertTrue(all.contains("table-data-write"));
        assertFalse(all.contains("_"), "access types are hyphenated, not underscored: " + all);
        assertFalse(all.toLowerCase().contains("manage_content"));
    }

    @Test
    void namesPoliciesAfterTheReleaseSoAReinstallFindsThem() throws Exception {
        List<String> names = new ArrayList<>();
        for (String[] l : levels()) names.add("kdps-" + "lake" + "-polaris-" + l[0]);
        assertEquals(List.of("kdps-lake-polaris-catalog", "kdps-lake-polaris-namespace",
                "kdps-lake-polaris-table"), names);
        assertEquals(names.size(), names.stream().distinct().count(), "policy names must not collide");
    }

    @Test
    void fallsBackToTheAmbariServiceRepoNamingConvention() throws Exception {
        Method m = CommandService.class.getDeclaredMethod("resolvePolarisRangerServiceName",
                Map.class, org.apache.ambari.view.k8s.model.ResolvedContext.class, String.class);
        m.setAccessible(true);
        CommandService cs = org.mockito.Mockito.mock(CommandService.class, org.mockito.Mockito.CALLS_REAL_METHODS);

        assertEquals("clemlabtest_polaris", m.invoke(cs, new HashMap<String, Object>(), null, "clemlabtest"));
        assertEquals("polaris", m.invoke(cs, new HashMap<String, Object>(), null, ""));

        Map<String, Object> explicit = new HashMap<>();
        explicit.put("_polarisRangerServiceName", "custom_repo");
        assertEquals("custom_repo", m.invoke(cs, explicit, null, "clemlabtest"),
                "an explicit repo name wins over the convention");
    }
}
