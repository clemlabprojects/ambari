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
package org.apache.ambari.view.k8s.service.trino;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure helpers of the live catalog editor: SQL building, SHOW CREATE CATALOG parsing, provenance merge. */
class TrinoCatalogRuntimeServiceTest {

    @Test
    void buildsCreateCatalogWithQuotedProperties() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("connector.name", "postgresql");
        p.put("connection-url", "jdbc:postgresql://db:5432/x?a=1");
        p.put("connection-user", "o'neil");
        String sql = TrinoCatalogRuntimeService.buildCreateCatalog("pg", "postgresql", p);
        assertEquals("CREATE CATALOG pg USING postgresql WITH (\"connection-url\" = 'jdbc:postgresql://db:5432/x?a=1', \"connection-user\" = 'o''neil')", sql);
        assertEquals("CREATE CATALOG m USING memory", TrinoCatalogRuntimeService.buildCreateCatalog("m", "memory", Map.of("connector.name", "memory")));
    }

    @Test
    void parsesPropertiesTextKeepingValuesWithEquals() {
        Map<String, String> m = TrinoCatalogRuntimeService.parseProperties("# c\nconnector.name=postgresql\n\nconnection-url = jdbc:x?y=z\nbad line\n");
        assertEquals(2, m.size());
        assertEquals("jdbc:x?y=z", m.get("connection-url"));
    }

    @Test
    void showCreateCatalogRoundTripsToProperties() {
        String ddl = "CREATE CATALOG pg USING postgresql\nWITH (\n   \"connection-url\" = 'jdbc:postgresql://db:5432/x',\n   \"connection-user\" = 'o''neil'\n)";
        String text = TrinoCatalogRuntimeService.propertiesFromShowCreate(ddl);
        assertEquals("connector.name=postgresql\nconnection-url=jdbc:postgresql://db:5432/x\nconnection-user=o'neil", text);
        assertEquals("postgresql", TrinoCatalogRuntimeService.connectorOf(text));
        assertNull(TrinoCatalogRuntimeService.connectorOf("x=y"));
    }

    @Test
    void mergeClassifiesProvenance() {
        Map<String, String> persisted = new LinkedHashMap<>();
        persisted.put("hive", "connector.name=hive\nhive.metastore.uri=thrift://m:9083");
        persisted.put("pg", "connector.name=postgresql");
        persisted.put("staged", "connector.name=memory");           // persisted but not live yet (restart pending)
        Map<String, Map<String, String>> refs = Map.of("pg", Map.of("id", "abc", "hash", "h"));
        List<TrinoCatalogRuntimeService.CatalogView> out = TrinoCatalogRuntimeService.merge(
                List.of("system", "hive", "pg", "probe_x"), persisted, refs);
        Map<String, TrinoCatalogRuntimeService.CatalogView> by = new LinkedHashMap<>();
        for (TrinoCatalogRuntimeService.CatalogView v : out) by.put(v.name, v);
        assertEquals("builtin", by.get("system").source);
        assertEquals("managed", by.get("hive").source);
        assertEquals("hive", by.get("hive").connector);
        assertEquals("reusable", by.get("pg").source);
        assertEquals("abc", by.get("pg").reusableId);
        assertEquals("unmanaged", by.get("probe_x").source);
        assertTrue(by.get("probe_x").live && !by.get("probe_x").persisted);
        assertEquals("inline", by.get("staged").source);
        assertTrue(by.get("staged").persisted && !by.get("staged").live);
    }

    @Test
    void shellQuotingSurvivesSingleQuotes() {
        assertEquals("'a'\\''b'", TrinoStatementClient.sq("a'b"));
    }
}
