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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reusable Trino catalogs: the validation rules an operator hits on the Trino Catalogs page, the
 * refs JSON a release persists, and the list coercion the wizard's multi-select value goes through.
 */
class TrinoCatalogServiceTest {

    private static final String PG = "connector.name=postgresql\nconnection-url=jdbc:postgresql://db:5432/x\n";

    @Test
    void validCatalogReturnsItsConnector() {
        assertEquals("postgresql", TrinoCatalogService.validateCatalog("postgres_prod", PG));
        assertEquals("kafka", TrinoCatalogService.validateCatalog("events", "# comment\n\nconnector.name = kafka\nkafka.nodes=k:9092"));
    }

    @Test
    void rejectsBadNamesAndKdpsManagedNames() {
        for (String bad : Arrays.asList(null, "", "Postgres", "1pg", "pg-prod", "pg prod")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> TrinoCatalogService.validateCatalog(bad, PG), "name=" + bad);
            assertTrue(e.getMessage().contains("name"), e.getMessage());
        }
        IllegalArgumentException hive = assertThrows(IllegalArgumentException.class,
                () -> TrinoCatalogService.validateCatalog("hive", PG));
        assertTrue(hive.getMessage().contains("platform context"), hive.getMessage());
        assertThrows(IllegalArgumentException.class, () -> TrinoCatalogService.validateCatalog("iceberg", PG));
    }

    @Test
    void rejectsSectionHeadersMissingConnectorAndNonKeyValueLines() {
        IllegalArgumentException section = assertThrows(IllegalArgumentException.class,
                () -> TrinoCatalogService.validateCatalog("pg", "[pg]\n" + PG));
        assertTrue(section.getMessage().contains("ONE catalog"), section.getMessage());
        IllegalArgumentException noConn = assertThrows(IllegalArgumentException.class,
                () -> TrinoCatalogService.validateCatalog("pg", "connection-url=jdbc:x\n"));
        assertTrue(noConn.getMessage().contains("connector.name"), noConn.getMessage());
        IllegalArgumentException junk = assertThrows(IllegalArgumentException.class,
                () -> TrinoCatalogService.validateCatalog("pg", PG + "this is not a property\n"));
        assertTrue(junk.getMessage().contains("key=value"), junk.getMessage());
        assertThrows(IllegalArgumentException.class, () -> TrinoCatalogService.validateCatalog("pg", "   "));
    }

    @Test
    void rejectsTextAboveTheDataStoreColumnLimit() {
        StringBuilder sb = new StringBuilder(PG);
        while (sb.length() <= TrinoCatalogService.MAX_PROPERTIES_LENGTH) sb.append("k").append(sb.length()).append("=v\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TrinoCatalogService.validateCatalog("pg", sb.toString()));
        assertTrue(e.getMessage().contains("3000"), e.getMessage());
    }

    @Test
    void refsJsonRoundTripsAndToleratesGarbage() {
        Map<String, Map<String, String>> refs = new LinkedHashMap<>();
        Map<String, String> r = new LinkedHashMap<>();
        r.put("id", "abc"); r.put("hash", TrinoCatalogService.contentHash(PG));
        refs.put("postgres_prod", r);
        String json = TrinoCatalogService.toRefsJson(refs);
        assertEquals(refs, TrinoCatalogService.parseRefs(json));
        assertEquals(12, r.get("hash").length(), "hash is a short fingerprint");
        assertEquals(TrinoCatalogService.contentHash(PG), TrinoCatalogService.contentHash(PG), "stable");
        assertTrue(TrinoCatalogService.parseRefs(null).isEmpty());
        assertTrue(TrinoCatalogService.parseRefs("not json").isEmpty());
        assertEquals(null, TrinoCatalogService.toRefsJson(new LinkedHashMap<>()), "no refs -> null clears the column");
    }

    @Test
    void wizardValueCoercesToIdList() {
        assertEquals(List.of("a", "b"), CommandService.asStringList(Arrays.asList("a", " b ", "", null)));
        assertEquals(List.of("a", "b"), CommandService.asStringList("[\"a\",\"b\"]"));
        assertEquals(List.of("a", "b"), CommandService.asStringList("a, b"));
        assertTrue(CommandService.asStringList(null).isEmpty());
        assertTrue(CommandService.asStringList("  ").isEmpty());
    }
}
