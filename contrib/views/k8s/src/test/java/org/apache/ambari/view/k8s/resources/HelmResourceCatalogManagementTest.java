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

package org.apache.ambari.view.k8s.resources;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Releases page offers the Catalogs action only on a Trino release where it can run: one deployed with 'Let KDPS
 * manage catalogs' and 'Enable internal HTTPS'. A release deployed with internal HTTPS off leaves the HTTPS value at
 * the chart's default (off) and has the option turned off, so the action is left out there.
 */
class HelmResourceCatalogManagementTest {

    /** The release values with the option and internal HTTPS as given, or left out when {@code null}. */
    private static Map<String, Object> values(Boolean serviceUser, Boolean https) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (serviceUser != null) {
            values.put("kdps", Map.of("serviceUser", Map.of("enabled", serviceUser)));
        }
        if (https != null) {
            values.put("server", Map.of("config", Map.of("https", Map.of("enabled", https))));
        }
        return values;
    }

    private static Boolean catalogManagement(String serviceKey, Supplier<Map<String, Object>> values) {
        return HelmResource.catalogManagement("analytics", "trino", serviceKey, values);
    }

    @Test
    void aTrinoReleaseWithTheServiceUserAndInternalHttpsOffersTheAction() {
        assertEquals(Boolean.TRUE, catalogManagement("TRINO", () -> values(true, true)));
    }

    @Test
    void aTrinoReleaseDeployedWithInternalHttpsOffDoesNot() {
        assertEquals(Boolean.FALSE, catalogManagement("TRINO", () -> values(false, null)));
        assertEquals(Boolean.FALSE, catalogManagement("TRINO", () -> values(true, false)));
    }

    @Test
    void aTrinoReleaseDeployedWithoutTheServiceUserDoesNot() {
        assertEquals(Boolean.FALSE, catalogManagement("trino", () -> values(null, true)));
    }

    @Test
    void anotherServiceIsNotRead() {
        AtomicInteger reads = new AtomicInteger();
        Supplier<Map<String, Object>> counted = () -> {
            reads.incrementAndGet();
            return values(true, true);
        };

        assertNull(catalogManagement("SUPERSET", counted));
        assertNull(catalogManagement(null, counted));
        assertEquals(0, reads.get());
    }

    @Test
    void unreadableValuesLeaveItUnknown() {
        // The page then still offers the action, which says what is missing.
        assertNull(catalogManagement("TRINO", () -> {
            throw new RuntimeException("Could not retrieve configuration: forbidden");
        }));
    }
}
