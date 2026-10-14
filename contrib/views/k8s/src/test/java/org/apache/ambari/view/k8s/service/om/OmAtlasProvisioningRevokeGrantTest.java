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

package org.apache.ambari.view.k8s.service.om;

import org.apache.ambari.view.k8s.utils.TlsPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.ambari.view.k8s.service.om.FakeRangerAdmin.grantees;
import static org.apache.ambari.view.k8s.service.om.FakeRangerAdmin.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the uninstall of a release takes back in a Ranger KDPS reaches directly: the access it granted, from the
 * repository's default policy too when the grant landed there, without touching the access a shared service user holds
 * of its own; and, when asked, the release's own repository.
 */
class OmAtlasProvisioningRevokeGrantTest {

    private static final String HIVE = "cdp_hive";
    private static final Map<String, List<String>> ALL_TABLES =
            Map.of("database", List.of("*"), "table", List.of("*"), "column", List.of("*"));

    private FakeRangerAdmin ranger;

    @BeforeEach
    void startRanger() throws Exception {
        ranger = new FakeRangerAdmin();
    }

    @AfterEach
    void stopRanger() {
        ranger.close();
    }

    private int revoke(String user, String access, String owned, boolean ownedPolicyOnly) throws Exception {
        return OmAtlasProvisioning.revokeGrant(TlsPolicy.JVM_DEFAULT, ranger.url(), "admin", "pw", HIVE, ALL_TABLES,
                user, Set.of(access), owned, ownedPolicyOnly);
    }

    @Test
    void anAccountKdpsMadeLosesTheAccessTheDefaultPolicyWasGivenForIt() throws Exception {
        // The grant went to Hive's default policy, which already covered every table.
        long defaultId = ranger.add(HIVE, "all - database, table, column", ALL_TABLES,
                item(List.of("hive"), "select", "update"), item(List.of("oma-ing-team-a"), "select"));

        assertEquals(1, revoke("oma-ing-team-a", "select", "kdps-openmetadata-om-hive-read", false));

        assertEquals(List.of(List.of("hive")), grantees(ranger.policies.get(defaultId), "select"));
        assertEquals(0, revoke("oma-ing-team-a", "select", "kdps-openmetadata-om-hive-read", false),
                "a second uninstall finds nothing left to take back");
    }

    @Test
    void thePolicyNamedForTheReleaseIsDeletedOnceItGrantsNothing() throws Exception {
        ranger.add(HIVE, "kdps-openmetadata-om-hive-read", ALL_TABLES, item(List.of("oma-ing-team-a"), "select"));

        assertEquals(1, revoke("oma-ing-team-a", "select", "kdps-openmetadata-om-hive-read", false));

        assertNull(ranger.byName(HIVE, "kdps-openmetadata-om-hive-read"));
    }

    @Test
    void aSharedServiceUserKeepsWhatItHoldsOutsideThePolicyNamedForTheRelease() throws Exception {
        long defaultId = ranger.add(HIVE, "all - database, table, column", ALL_TABLES, item(List.of("trino"), "select"));
        ranger.add(HIVE, "kdps-om-trino-read", ALL_TABLES, item(List.of("trino", "etl"), "select"));

        assertEquals(1, revoke("trino", "select", "kdps-om-trino-read", true));

        assertEquals(List.of(List.of("trino")), grantees(ranger.policies.get(defaultId), "select"));
        assertEquals(List.of(List.of("etl")), grantees(ranger.byName(HIVE, "kdps-om-trino-read"), "select"),
                "the policy keeps what another account was given in it");
    }

    @Test
    void theRepositoryOfTheReleaseIsDeletedWithItsPolicies() throws Exception {
        ranger.services.put("trino-team-a", 900L);
        ranger.add("trino-team-a", "operators", Map.of("catalog", List.of("*")), item(List.of("bob"), "select"));

        assertTrue(OmAtlasProvisioning.deleteService(TlsPolicy.JVM_DEFAULT, ranger.url(), "admin", "pw", "trino-team-a"));

        assertFalse(ranger.services.containsKey("trino-team-a"));
        assertTrue(ranger.policies.isEmpty());
        assertFalse(OmAtlasProvisioning.deleteService(TlsPolicy.JVM_DEFAULT, ranger.url(), "admin", "pw", "trino-team-a"));
    }
}
