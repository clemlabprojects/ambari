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

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.TrinoCatalogDTO;
import org.apache.ambari.view.k8s.store.TrinoCatalogEntity;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    /**
     * A reusable catalog lives in KDPS's database and is copied into the catalog ConfigMap of every release that uses
     * it, so a credential must be read from the environment (a Secret of the release), never written out.
     */
    @Test
    void rejectsCredentialsWrittenOut() {
        for (String line : List.of("connection-password=hunter2", "s3.aws-secret-key=abc", "s3.aws-access-key=AKIA",
                "iceberg.rest-catalog.oauth2.credential=client:secret", "iceberg.rest-catalog.oauth2.token=eyJ",
                "kafka.ssl.truststore.password = changeit", "gcs.json-key={\"type\":\"service_account\"}")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> TrinoCatalogService.validateCatalog("pg", PG + line + "\n"), line);
            String key = line.substring(0, line.indexOf('=')).trim();
            assertTrue(e.getMessage().contains(key + "=${ENV:"), e.getMessage());
            assertTrue(e.getMessage().contains("Secrets with catalog credentials"), e.getMessage());
            assertFalse(e.getMessage().contains(line.substring(line.indexOf('=') + 1).trim()),
                    "the message must not repeat the credential: " + e.getMessage());
        }
    }

    @Test
    void acceptsCredentialsReadFromTheEnvironmentAndPropertiesThatOnlyNameThem() {
        assertEquals("postgresql", TrinoCatalogService.validateCatalog("pg",
                PG + "connection-user=${ENV:PG_USER}\nconnection-password=${ENV:PG_PASSWORD}\n"));
        assertEquals("postgresql", TrinoCatalogService.validateCatalog("pg", PG + "connection-password=\n"));
        assertEquals("bigquery", TrinoCatalogService.validateCatalog("bq",
                "connector.name=bigquery\nbigquery.credentials-file=/etc/secrets/bq.json\n"
                        + "# connection-password=commented-out\niceberg.rest-catalog.oauth2.token-refresh-enabled=true\n"));
        assertEquals(List.of("connection-password", "s3.aws-secret-key"), TrinoCatalogService.literalCredentialProperties(
                PG + "connection-password=x\ns3.aws-secret-key=y\ns3.aws-access-key=${ENV:K}\n"));
    }

    /**
     * A catalog saved before KDPS refused credentials written out still holds one; the Trino Catalogs page shows which
     * properties, so the operator can find it and edit it before the next Upgrade/Config of a release using it.
     */
    @Test
    void theListNamesThePropertiesACatalogSavedEarlierWritesOut() throws Exception {
        TrinoCatalogEntity legacy = new TrinoCatalogEntity();
        legacy.setId("cat-1");
        legacy.setName("legacy");
        legacy.setPropertiesText(PG + "connection-password=old-literal\n");
        TrinoCatalogEntity fromEnv = new TrinoCatalogEntity();
        fromEnv.setId("cat-2");
        fromEnv.setName("pg");
        fromEnv.setPropertiesText(PG + "connection-password=${ENV:PG_PASSWORD}\n");
        ViewContext ctx = mock(ViewContext.class);
        DataStore ds = mock(DataStore.class);
        when(ctx.getDataStore()).thenReturn(ds);
        when(ds.findAll(TrinoCatalogEntity.class, null)).thenReturn(List.of(legacy, fromEnv));
        when(ds.find(TrinoCatalogEntity.class, "cat-1")).thenReturn(legacy);

        List<TrinoCatalogDTO> listed = new TrinoCatalogService(ctx).list();

        assertEquals(List.of("legacy", "pg"), listed.stream().map(d -> d.name).toList());
        assertEquals(List.of("connection-password"), listed.get(0).literalCredentials);
        assertEquals(List.of(), listed.get(1).literalCredentials);
        assertEquals(List.of("connection-password"), new TrinoCatalogService(ctx).get("cat-1").literalCredentials);
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
    void readOnlyUsersSeeTheSettingsButNotTheCredentials() {
        String text = "connector.name=postgresql\n"
                + "connection-url=jdbc:postgresql://db:5432/x\n"
                + "connection-user=app\n"
                + "connection-password=s3cret\n"
                + "hive.s3.aws-secret-key = AbCd/123\n"
                + "iceberg.rest-catalog.oauth2.credential: client:pa55\n"
                + "kafka.token s3cret-token\n"
                + "# connection-password=old-s3cret\n"
                + "case-insensitive-name-matching.PASSWORD=x";

        assertEquals("connector.name=postgresql\n"
                        + "connection-url=jdbc:postgresql://db:5432/x\n"
                        + "connection-user=app\n"
                        + "connection-password=********\n"
                        + "hive.s3.aws-secret-key=********\n"
                        + "iceberg.rest-catalog.oauth2.credential=********\n"
                        + "kafka.token=********\n"
                        + "# connection-password=********\n"
                        + "case-insensitive-name-matching.PASSWORD=********",
                TrinoCatalogService.withoutSecrets(text));
    }

    @Test
    void aValueWrittenOverSeveralLinesIsMaskedWhole() {
        assertEquals("connection-password=********\nconnection-user=app",
                TrinoCatalogService.withoutSecrets("connection-password=part1\\\n  part2\\\n  part3\nconnection-user=app"));
        // An escaped backslash ends the value.
        assertEquals("connection-password=********\nconnection-user=app",
                TrinoCatalogService.withoutSecrets("connection-password=a\\\\\nconnection-user=app"));
    }

    @Test
    void maskingKeepsWhatHoldsNoCredential() {
        assertEquals(PG.trim(), TrinoCatalogService.withoutSecrets(PG.trim()));
        assertEquals("connection-password=\n# a comment\n\nconnector.name=memory",
                TrinoCatalogService.withoutSecrets("connection-password=\n# a comment\n\nconnector.name=memory"));
        assertEquals("connection-password=********\nconnection-user=app\r",
                TrinoCatalogService.withoutSecrets("connection-password=s3cret\r\nconnection-user=app\r"),
                "a Windows line end does not hide the line from the mask");
        assertEquals(null, TrinoCatalogService.withoutSecrets(null));
        assertEquals("", TrinoCatalogService.withoutSecrets(""));
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
