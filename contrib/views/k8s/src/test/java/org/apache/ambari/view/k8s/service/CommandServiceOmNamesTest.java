/*
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Derived OpenMetadata service names, dotted-path writes and the Ranger password generator (AMBARI-672/674/676). */
class CommandServiceOmNamesTest {

    @Test
    void trinoServiceNameFollowsTheRelease() {
        assertEquals("trino-sec", CommandService.defaultOmTrinoServiceName("trino-sec"));
        assertEquals("trino-analytics", CommandService.defaultOmTrinoServiceName("Analytics"));
        assertEquals("trino-kdps", CommandService.defaultOmTrinoServiceName(""));
        assertEquals("trino-kdps", CommandService.defaultOmTrinoServiceName(null));
    }

    @Test
    void hiveServiceNameFollowsTheCluster() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("_cluster", "ClemlabTest");
        assertEquals("hive-clemlabtest", CommandService.defaultOmHiveServiceName(params, null));
        assertEquals("hive-odp", CommandService.defaultOmHiveServiceName(new LinkedHashMap<>(), null));
        assertEquals("hive-odp", CommandService.defaultOmHiveServiceName(null, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void putDottedCreatesIntermediateMaps() {
        Map<String, Object> root = new LinkedHashMap<>();
        CommandService.putDotted(root, "ranger.tagSync.trinoIngestionServiceFqn", "trino-sec");
        CommandService.putDotted(root, "ranger.tagSync.rangerTrinoServiceName", "trino-sec-trino-sec");
        Map<String, Object> tagSync = (Map<String, Object>) ((Map<String, Object>) root.get("ranger")).get("tagSync");
        assertEquals("trino-sec", tagSync.get("trinoIngestionServiceFqn"));
        assertEquals("trino-sec-trino-sec", tagSync.get("rangerTrinoServiceName"));
        // a scalar in the way is replaced by a map rather than failing
        root.put("x", "scalar");
        CommandService.putDotted(root, "x.y", 1);
        assertEquals(1, ((Map<String, Object>) root.get("x")).get("y"));
    }

    @Test
    void rangerPasswordIsLongRandomAndMixed() {
        String a = CommandService.generateRangerPassword();
        String b = CommandService.generateRangerPassword();
        assertEquals(28, a.length());
        assertNotEquals(a, b);
        assertTrue(a.matches("[A-Za-z0-9]+"));
        assertTrue(a.matches(".*[0-9].*") && a.matches(".*[A-Za-z].*"));
    }
}
