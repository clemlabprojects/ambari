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

package org.apache.ambari.view.k8s.service.om;

import com.google.gson.JsonObject;
import org.apache.ambari.view.k8s.utils.TlsPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.apache.ambari.view.k8s.service.om.FakeRangerAdmin.grantees;
import static org.apache.ambari.view.k8s.service.om.FakeRangerAdmin.item;
import static org.apache.ambari.view.k8s.service.om.FakeRangerAdmin.values;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which Hive access a service identity receives on a context whose Ranger KDPS reaches directly over
 * REST. The caller names the access type, and only that access type is granted: a grant of
 * {@code read} adds no {@code select}, and a grant of {@code select} adds no {@code read}.
 */
class OmAtlasProvisioningHiveAccessTest {

    private static final String HIVE = "hive-lake";
    private static final String DEFAULT_POLICY = "all - database, table, column";
    private static final Map<String, List<String>> EVERYTHING = Map.of(
            "database", List.of("*"), "table", List.of("*"), "column", List.of("*"));

    private FakeRangerAdmin ranger;

    @BeforeEach
    void startRanger() throws Exception {
        ranger = new FakeRangerAdmin();
    }

    @AfterEach
    void stopRanger() {
        ranger.close();
    }

    private long grant(String user, String access) throws Exception {
        return OmAtlasProvisioning.createOrFindHiveReadPolicy(TlsPolicy.JVM_DEFAULT, ranger.url(), "admin", "pw",
                HIVE, "kdps-lake-hive-read", user, access, 5_000);
    }

    @Test
    void aMetadataReaderIsAddedToTheDefaultPolicyWithReadAndNotSelect() throws Exception {
        long defaultId = ranger.add(HIVE, DEFAULT_POLICY, EVERYTHING, item(List.of("hive"), "all"));

        assertEquals(defaultId, grant("svc-reader", "read"));

        JsonObject after = ranger.policies.get(defaultId);
        assertEquals(List.of(List.of("svc-reader")), grantees(after, "read"));
        assertTrue(grantees(after, "select").isEmpty(), "only the named access type is granted");
        assertEquals(List.of("hive"), grantees(after, "all").get(0), "the plugin's own item is untouched");
    }

    @Test
    void aServiceIdentityThatReadsDataAsItselfIsAddedWithSelect() throws Exception {
        long defaultId = ranger.add(HIVE, DEFAULT_POLICY, EVERYTHING, item(List.of("hive"), "all"));

        grant("trino-ns", "select");

        JsonObject after = ranger.policies.get(defaultId);
        assertEquals(List.of(List.of("trino-ns")), grantees(after, "select"));
        assertTrue(grantees(after, "read").isEmpty());
    }

    @Test
    void withoutADefaultPolicyADedicatedOneIsCreatedWithOnlyTheNamedAccess() throws Exception {
        grant("svc-reader", "read");

        JsonObject created = ranger.byName(HIVE, "kdps-lake-hive-read");
        assertNotNull(created);
        assertEquals(List.of("*"), values(created, "database"));
        assertEquals(List.of("*"), values(created, "table"));
        assertEquals(List.of("*"), values(created, "column"));
        assertEquals(List.of(List.of("svc-reader")), grantees(created, "read"));
        assertTrue(grantees(created, "select").isEmpty());
    }

    @Test
    void grantingTwiceDoesNotAddASecondItem() throws Exception {
        long defaultId = ranger.add(HIVE, DEFAULT_POLICY, EVERYTHING, item(List.of("hive"), "all"));

        grant("svc-reader", "read");
        grant("svc-reader", "read");

        assertEquals(2, ranger.policies.get(defaultId).getAsJsonArray("policyItems").size());
    }

    @Test
    void readIsAddedBesideASelectAnEarlierVersionGrantedAndTheSelectIsNotTakenBack() throws Exception {
        long defaultId = ranger.add(HIVE, DEFAULT_POLICY, EVERYTHING, item(List.of("hive"), "all"),
                item(List.of("svc-reader"), "select"));

        assertEquals(defaultId, grant("svc-reader", "read"));

        JsonObject after = ranger.policies.get(defaultId);
        assertEquals(List.of(List.of("svc-reader")), grantees(after, "read"));
        assertEquals(List.of(List.of("svc-reader")), grantees(after, "select"),
                "a select the operator or an earlier version granted is left for an administrator to remove");
    }
}
