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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each service identity may do in Hive. The OpenMetadata ingestion keeps {@code select},
 * because Ranger's Hive plugin authorizes describing a table and reading a view's definition as
 * {@code select}; Superset's Hive connection impersonates the end user and its service identity gets
 * no Hive grant at all; Trino's Hive catalog reads as its own identity, and keeps {@code select}.
 */
class CommandServiceHiveIngestionAccessTest {

    private static final String SERVICES = "src/main/resources/KDPS/services/";

    @Test
    void theOpenMetadataIngestionIsGivenSelectThroughAmbari() throws Exception {
        CommandServiceTrinoImpersonationTest.FakeAmbari ambari = new CommandServiceTrinoImpersonationTest.FakeAmbari();

        boolean done = CommandService.grantOmIngestionHiveAccessViaAmbari(
                ambari.client(), "lake_hive", "oma-ing-om", "kdps-openmetadata-om-hive-read");

        assertTrue(done);
        assertEquals(1, ambari.rangerPolicyRequests.size());
        JsonObject grant = ambari.policy(0);
        assertFalse(grant.has("revoke"));
        assertEquals("lake_hive", grant.get("rangerServiceName").getAsString());
        assertEquals("oma-ing-om", grant.get("userName").getAsString());
        assertEquals("select", grant.get("accessTypes").getAsString());
        assertEquals("kdps-openmetadata-om-hive-read", grant.get("policyNameHint").getAsString());
        JsonObject resources = JsonParser.parseString(grant.get("resourcesJson").getAsString()).getAsJsonObject();
        assertEquals(List.of("database", "table", "column"), List.copyOf(resources.keySet()));
    }

    @Test
    void theIngestionKeepsSelectSoColumnsViewDefinitionsAndLineageAreIngested() {
        assertEquals("select", CommandService.OM_HIVE_INGESTION_ACCESS);
        assertEquals("select", CommandService.HIVE_SERVICE_DATA_ACCESS);
    }

    @Test
    void aGrantAmbariCouldNotCompleteIsReportedNotThrown() throws Exception {
        CommandServiceTrinoImpersonationTest.FakeAmbari ambari = new CommandServiceTrinoImpersonationTest.FakeAmbari();
        ambari.outcomes.add("FAILED");

        boolean done = CommandService.grantOmIngestionHiveAccessViaAmbari(
                ambari.client(), "lake_hive", "oma-ing-om", "kdps-openmetadata-om-hive-read");

        assertFalse(done);
    }

    @Test
    void onlyTrinosHiveCatalogDeclaresAHiveDataGrant() throws IOException {
        assertEquals(List.of("ranger.hiveGrant"), hiveGrantOps("TRINO"));
        assertEquals(List.of(), hiveGrantOps("SUPERSET"), "Superset impersonates the user, so its identity needs no Hive access");
        assertEquals(List.of(), hiveGrantOps("OPENMETADATA"), "the ingestion is granted by its own base-ingestion step, not by ranger.hiveGrant");
    }

    @Test
    void theDocExplainsHowToNarrowTheIngestionGrantWithTheRealRangerPluginOption() throws IOException {
        String doc = Files.readString(Path.of("docs/RANGER_POLICIES_AND_TAGS.md"));

        // The key Ranger's Hive plugin reads (RangerHadoopConstants.HIVE_DESCRIBE_TABLE_SHOW_COLUMNS_AUTH_OPTION_PROP).
        assertTrue(doc.contains("xasecure.hive.describetable.showcolumns.authorization.option=show-all"));
        assertTrue(doc.contains("Narrowing the OpenMetadata grant"));
        assertTrue(doc.contains("| OpenMetadata ingestion (`oma-ing-<namespace>`) | `select` on every database, table and column |"));
        assertTrue(doc.contains("privileged service account"));
        // show-all restores the columns only; a view's definition still needs select.
        assertTrue(doc.contains("View definitions"));
    }

    @Test
    void stepWarningsAccumulateInsteadOfReplacingEachOther() {
        Map<String, Object> result = new LinkedHashMap<>();

        CommandService.addStepWarning(result, "first");
        CommandService.addStepWarning(result, "second");

        assertEquals(new ArrayList<>(List.of("first", "second")), result.get("warnings"));
    }

    private static List<String> hiveGrantOps(String service) throws IOException {
        JsonObject def = JsonParser.parseString(Files.readString(Path.of(SERVICES + service + "/service.json")))
                .getAsJsonObject();
        if (!def.has("platformOps")) return List.of();
        return StreamSupport.stream(def.getAsJsonArray("platformOps").spliterator(), false)
                .map(e -> e.getAsJsonObject().get("op").getAsString())
                .filter("ranger.hiveGrant"::equals)
                .collect(Collectors.toList());
    }
}
