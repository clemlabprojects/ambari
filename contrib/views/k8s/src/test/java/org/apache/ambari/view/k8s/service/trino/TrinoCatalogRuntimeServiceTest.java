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

import org.apache.ambari.view.k8s.service.helm.HelmOperationRegistry;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        Map<String, String> rendered = new LinkedHashMap<>(persisted);
        rendered.put("tpch", "connector.name=tpch");                 // chart default: rendered, not in values
        List<TrinoCatalogRuntimeService.CatalogView> out = TrinoCatalogRuntimeService.merge(
                List.of("system", "hive", "pg", "probe_x", "tpch"), rendered, persisted, refs);
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
        assertEquals("default", by.get("tpch").source);
        assertTrue(by.get("tpch").persisted && by.get("tpch").live);
        assertEquals("tpch", by.get("tpch").connector);
    }

    @Test
    void readOnlyRowsKeepTheirProvenanceButNotTheirCredentials() {
        Map<String, String> persisted = new LinkedHashMap<>();
        persisted.put("pg", "connector.name=postgresql\nconnection-user=app\nconnection-password=s3cret");
        List<TrinoCatalogRuntimeService.CatalogView> rows = TrinoCatalogRuntimeService.withoutSecrets(
                TrinoCatalogRuntimeService.merge(List.of("system", "pg", "probe_x"), persisted, persisted, Map.of()));

        TrinoCatalogRuntimeService.CatalogView pg = rows.get(1);
        assertEquals("pg", pg.name);
        assertEquals("inline", pg.source);
        assertEquals("postgresql", pg.connector);
        assertEquals("connector.name=postgresql\nconnection-user=app\nconnection-password=********", pg.properties);
        assertNull(rows.get(2).properties, "a catalog only Trino knows has no properties to mask");
    }

    @Test
    void storeFileBecomesPropertiesTextWithConnectorFirst() {
        String raw = "#Sat Oct 03 09:07:21 UTC 2026\nmemory.max-data-per-node=16MB\nconnector.name=memory\nconnection-url=jdbc\\:postgresql\\://db\\:5432/x\n";
        assertEquals("connector.name=memory\nmemory.max-data-per-node=16MB\nconnection-url=jdbc:postgresql://db:5432/x",
                TrinoCatalogRuntimeService.propertiesFromStoreFile(raw));
    }

    @Test
    void shellQuotingSurvivesSingleQuotes() {
        assertEquals("'a'\\''b'", TrinoStatementClient.sq("a'b"));
    }

    /**
     * Saving a catalog upgrades the release with its values. Those of a release deployed by an earlier definition may
     * hold secrets it wrote into the server configuration, a ConfigMap: the upgrade must not render them again.
     */
    @Test
    void aSavedCatalogUpgradesTheReleaseWithoutTheSecretsAnEarlierDefinitionWroteIntoItsConfiguration() {
        TrinoCatalogRuntimeService.Target t = target("trino", "DIRECT_HELM");
        t.metadata.setServiceKey("TRINO");
        t.values = new LinkedHashMap<>();
        t.values.put("internalSharedSecret", "Typed-Shared-Secret-1");
        t.values.put("additionalConfigProperties", new ArrayList<>(List.of(
                "internal-communication.shared-secret=Typed-Shared-Secret-1", "http-server.process-forwarded=true")));
        Map<String, Object> coordinator = new LinkedHashMap<>();
        coordinator.put("additionalConfigProperties", new ArrayList<>(List.of(
                "http-server.https.keystore.key=Typed-Keystore-Pass-2", "query.max-history=500")));
        t.values.put("coordinator", coordinator);
        t.values.put("catalogs", new LinkedHashMap<>(Map.of("tpch", "connector.name=tpch")));

        Map<String, Object> values = TrinoCatalogRuntimeService.valuesWithCatalog(null, t, "pg",
                "connector.name=postgresql\nconnection-password=${ENV:PG_PASSWORD}");

        assertEquals(Map.of("tpch", "connector.name=tpch",
                "pg", "connector.name=postgresql\nconnection-password=${ENV:PG_PASSWORD}"), values.get("catalogs"));
        assertFalse(values.containsKey("internalSharedSecret"));
        assertEquals(List.of("http-server.process-forwarded=true"), values.get("additionalConfigProperties"));
        assertEquals(List.of("query.max-history=500"),
                ((Map<?, ?>) values.get("coordinator")).get("additionalConfigProperties"));
    }

    private static TrinoCatalogRuntimeService.Target target(String release, String deploymentMode) {
        TrinoCatalogRuntimeService.Target t = new TrinoCatalogRuntimeService.Target();
        t.namespace = "trino";
        t.release = release;
        t.metadata = new K8sReleaseEntity();
        t.metadata.setDeploymentMode(deploymentMode);
        return t;
    }

    @Test
    void catalogsOfAFluxManagedReleaseAreNotSavedByADirectHelmUpgrade() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TrinoCatalogRuntimeService.requirePersistable(target("trino-flux", "FLUX_GITOPS")));
        assertTrue(e.getMessage().contains("Flux GitOps"), e.getMessage());
        assertDoesNotThrow(() -> TrinoCatalogRuntimeService.requirePersistable(target("trino-direct", "DIRECT_HELM")));
        assertDoesNotThrow(() -> TrinoCatalogRuntimeService.requirePersistable(target("trino-legacy", null)),
                "releases recorded before deployment modes existed were deployed directly");
    }

    @Test
    void catalogsAreNotSavedWhileAHelmOperationOnTheReleaseRuns() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch helmReturns = new CountDownLatch(1);
        try {
            Future<?> upgrade = HelmOperationRegistry.submit("trino", "trino-busy", pool, () -> {
                running.countDown();
                return helmReturns.await(30, TimeUnit.SECONDS);
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            assertThrows(HelmOperationRegistry.ReleaseBusyException.class,
                    () -> TrinoCatalogRuntimeService.requirePersistable(target("trino-busy", "DIRECT_HELM")));
            helmReturns.countDown();
            upgrade.get(5, TimeUnit.SECONDS);
        } finally {
            helmReturns.countDown();
            pool.shutdownNow();
        }
    }
}
