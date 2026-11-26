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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.ambari.view.k8s.utils.TlsPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.ambari.view.k8s.service.om.FakeRangerAdmin.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The administrators of a Trino repository on a Ranger KDPS reaches directly: the users and groups named get, with
 * delegated administration, every access of each service-wide policy, as Ranger gives its lookup user; a policy over
 * named resources is left alone. Same rules as the Ambari server's action for the managed cluster.
 */
class OmAtlasProvisioningServiceAdminsTest {

    private static final String REPO = "trino-analytics";

    private FakeRangerAdmin ranger;
    private long tables;
    private long queries;
    private long hiveOnly;

    @BeforeEach
    void startRanger() throws Exception {
        ranger = new FakeRangerAdmin();
        tables = ranger.add(REPO, "all - catalog, schema, table",
                Map.of("catalog", List.of("*"), "schema", List.of("*"), "table", List.of("*")),
                admin("rangerlookup", "select", "show", "drop"));
        queries = ranger.add(REPO, "all - queryid", Map.of("queryid", List.of("*")), admin("rangerlookup", "execute"));
        hiveOnly = ranger.add(REPO, "hive readers", Map.of("catalog", List.of("hive"), "schema", List.of("*")),
                item(List.of("analysts"), "select"));
        ranger.userDirectory = true;
    }

    @AfterEach
    void stopRanger() {
        ranger.close();
    }

    private OmAtlasProvisioning.ServiceAdminsResult ensure(Set<String> users, Set<String> groups, Set<String> revokedUsers)
            throws Exception {
        return OmAtlasProvisioning.ensureServiceAdmins(TlsPolicy.JVM_DEFAULT, ranger.url(), "admin", "pw", REPO,
                users, groups, revokedUsers, Set.of());
    }

    @Test
    void theAdminsGetEveryAccessOfEachServiceWidePolicy() throws Exception {
        OmAtlasProvisioning.ServiceAdminsResult result = ensure(ordered("system", "trino-analytics"),
                ordered("trino-admins"), Set.of());

        assertEquals(List.of("system", "trino-analytics"), new ArrayList<>(result.added().users()));
        assertEquals(List.of("trino-admins"), new ArrayList<>(result.added().groups()));
        assertEquals(2, result.serviceWidePolicies());
        JsonObject added = lastItem(tables);
        assertEquals(List.of("system", "trino-analytics"), strings(added.getAsJsonArray("users")));
        assertEquals(List.of("trino-admins"), strings(added.getAsJsonArray("groups")));
        assertEquals(List.of("drop", "select", "show"), accesses(added));
        assertTrue(added.get("delegateAdmin").getAsBoolean());
        assertEquals(List.of("execute"), accesses(lastItem(queries)));
        assertEquals(1, ranger.policies.get(hiveOnly).getAsJsonArray("policyItems").size(),
                "a policy over named resources is not an admin policy");
        assertTrue(ranger.users.containsKey("system") && ranger.groups.containsKey("trino-admins"),
                "Ranger has the user and the group the policies name");
    }

    @Test
    void aSecondDeployWritesNothing() throws Exception {
        ensure(ordered("system"), ordered("trino-admins"), Set.of());
        long writes = ranger.writes();

        OmAtlasProvisioning.ServiceAdminsResult again = ensure(ordered("system"), ordered("trino-admins"), Set.of());

        assertEquals(writes, ranger.writes());
        assertTrue(again.added().isEmpty());
    }

    @Test
    void aUserThatIsAlreadyAnAdminIsNeitherAddedNorReported() throws Exception {
        OmAtlasProvisioning.ServiceAdminsResult result = ensure(ordered("rangerlookup", "system"), Set.of(), Set.of());

        assertEquals(List.of("system"), new ArrayList<>(result.added().users()));
        assertEquals(List.of("system"), strings(lastItem(tables).getAsJsonArray("users")));
    }

    @Test
    void aRevokedAdminLeavesThePoliciesAndTheOthersStay() throws Exception {
        ensure(ordered("system", "alice"), Set.of(), Set.of());

        ensure(ordered("system"), Set.of(), Set.of("alice"));

        for (long id : new long[]{tables, queries}) {
            assertEquals(2, ranger.policies.get(id).getAsJsonArray("policyItems").size());
            assertEquals(List.of("system"), strings(lastItem(id).getAsJsonArray("users")));
        }
    }

    @Test
    void aRevokeDoesNotTakeBackANameThatIsStillWanted() throws Exception {
        ensure(ordered("system"), Set.of(), Set.of());

        ensure(ordered("system"), Set.of(), Set.of("system"));

        assertEquals(List.of("system"), strings(lastItem(queries).getAsJsonArray("users")));
    }

    private static Set<String> ordered(String... names) {
        return new LinkedHashSet<>(List.of(names));
    }

    /** An allow item with delegated administration, as Ranger gives its lookup user. */
    private static String admin(String user, String... accesses) {
        JsonObject item = JsonParser.parseString(item(List.of(user), accesses)).getAsJsonObject();
        item.addProperty("delegateAdmin", true);
        return item.toString();
    }

    private JsonObject lastItem(long policyId) {
        JsonArray items = ranger.policies.get(policyId).getAsJsonArray("policyItems");
        return items.get(items.size() - 1).getAsJsonObject();
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : array) out.add(e.getAsString());
        return out;
    }

    private static List<String> accesses(JsonObject item) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : item.getAsJsonArray("accesses")) out.add(e.getAsJsonObject().get("type").getAsString());
        return out;
    }
}
