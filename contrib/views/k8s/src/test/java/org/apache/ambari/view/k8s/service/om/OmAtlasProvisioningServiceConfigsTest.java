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

import org.apache.ambari.view.k8s.utils.TlsPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A Ranger service a deploy finds already there (created by an earlier deploy, or by hand) gets the configs the deploy
 * would create it with, as the Ambari server's repository action does for the managed cluster: a plugin whose download
 * users were never set is otherwise refused its policies on every redeploy.
 */
class OmAtlasProvisioningServiceConfigsTest {

    private static final String REPO = "trino-analytics";

    private FakeRangerAdmin ranger;

    @BeforeEach
    void startRanger() throws Exception {
        ranger = new FakeRangerAdmin();
    }

    @AfterEach
    void stopRanger() {
        ranger.close();
    }

    /** The configs a Kerberized Trino deploy gives its repository. */
    private static Map<String, String> deployConfigs() {
        Map<String, String> configs = new LinkedHashMap<>();
        configs.put("jdbc.driverClassName", "io.trino.jdbc.TrinoDriver");
        configs.put("jdbc.url", "jdbc:trino://trino-analytics-coordinator.analytics.svc.cluster.local:8443?SSL=true");
        configs.put("policy.download.auth.users", "trino-analytics");
        configs.put("tag.download.auth.users", "trino-analytics");
        configs.put("username", "admin-of-the-context");
        return configs;
    }

    private long ensure() throws Exception {
        return OmAtlasProvisioning.createOrFindService(TlsPolicy.JVM_DEFAULT, ranger.url(), "admin", "pw", REPO, "trino",
                deployConfigs());
    }

    /** A repository an earlier deploy created without the download users, with lookup credentials set by hand. */
    private long earlierRepository(String downloadUsers) {
        ranger.services.put(REPO, 7L);
        Map<String, String> configs = new LinkedHashMap<>();
        configs.put("username", "lookup-user");
        configs.put("password", "lookup-secret");
        configs.put("jdbc.url", "jdbc:trino://old-host:8080?SSL=false");
        if (downloadUsers != null) configs.put("policy.download.auth.users", downloadUsers);
        ranger.serviceConfigs.put(REPO, configs);
        return 7L;
    }

    @Test
    void aRepositoryAnEarlierDeployCreatedGetsTheDownloadUsersAndKeepsItsCredentials() throws Exception {
        long id = earlierRepository(null);

        assertEquals(id, ensure());

        Map<String, String> after = ranger.serviceConfigs.get(REPO);
        assertEquals("trino-analytics", after.get("policy.download.auth.users"));
        assertEquals("trino-analytics", after.get("tag.download.auth.users"));
        assertEquals(deployConfigs().get("jdbc.url"), after.get("jdbc.url"));
        assertEquals("lookup-user", after.get("username"), "credentials an administrator set are kept");
        assertEquals("lookup-secret", after.get("password"), "the masked password Ranger answers is not written back");
    }

    @Test
    void usersAnAdministratorAllowedToDownloadAreKept() throws Exception {
        earlierRepository("hive, ops");

        ensure();

        assertEquals("hive,ops,trino-analytics", ranger.serviceConfigs.get(REPO).get("policy.download.auth.users"));
    }

    @Test
    void aRepositoryThatAlreadyHasTheConfigsIsNotWritten() throws Exception {
        earlierRepository(null);
        ensure();
        long writes = ranger.writes();

        ensure();

        assertEquals(writes, ranger.writes(), "a second deploy finds nothing to change");
    }

    @Test
    void aNewRepositoryIsCreatedWithTheConfigs() throws Exception {
        ensure();

        assertEquals(deployConfigs(), ranger.serviceConfigs.get(REPO));
    }

    @Test
    void theMergeAddsOnlyWhatIsMissing() {
        Map<String, String> current = new LinkedHashMap<>();
        current.put("policy.download.auth.users", "trino-analytics");
        current.put("password", "*****");
        current.put("custom", "kept");

        Map<String, String> merged = OmAtlasProvisioning.mergeServiceConfigs(current,
                Map.of("policy.download.auth.users", "trino-analytics", "password", "new", "jdbc.url", "u"));

        assertEquals("trino-analytics", merged.get("policy.download.auth.users"));
        assertEquals("*****", merged.get("password"));
        assertEquals("kept", merged.get("custom"));
        assertEquals("u", merged.get("jdbc.url"));
    }
}
