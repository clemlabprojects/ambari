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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.ambari.view.k8s.service.om.FakeRangerAdmin.grantees;
import static org.apache.ambari.view.k8s.service.om.FakeRangerAdmin.item;
import static org.apache.ambari.view.k8s.service.om.FakeRangerAdmin.values;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who may act as whom on a Trino repository KDPS configures directly over Ranger's REST API.
 *
 * <p>In Ranger, the {@code {USER}} macro named as a policy user matches every user, and the
 * {@code trinouser} resource is the user being impersonated. Earlier versions granted {@code {USER}}
 * {@code impersonate} on {@code trinouser=*}, so anyone could run queries as anyone. A redeploy must
 * take that back without touching the service principals that legitimately impersonate, and a user
 * may only act as themselves ({@code trinouser={USER}}, which Ranger resolves to the requester).
 */
class OmAtlasProvisioningTrinoImpersonationTest {

    private static final String REPO = "trino-analytics";
    private static final String ALL_USERS_DEFAULT = "all - trinouser";
    private static final Map<String, List<String>> EVERY_USER = Map.of("trinouser", List.of("*"));
    private static final Map<String, List<String>> REQUESTER = Map.of("trinouser", List.of("{USER}"));

    private FakeRangerAdmin ranger;

    @BeforeEach
    void startRanger() throws Exception {
        ranger = new FakeRangerAdmin();
    }

    @AfterEach
    void stopRanger() {
        ranger.close();
    }

    private int revoke() throws Exception {
        return OmAtlasProvisioning.revokeTrinoImpersonationOfEveryone(ranger.url(), "admin", "pw", REPO);
    }

    private long ensureSelf() throws Exception {
        return OmAtlasProvisioning.ensureTrinoSelfImpersonationPolicy(ranger.url(), "admin", "pw", REPO, 5_000);
    }

    @Test
    void aRedeployTakesBackEveryUserImpersonationAndKeepsTheServicePrincipals() throws Exception {
        // What an earlier version left on a repository whose default policy existed.
        long defaultId = ranger.add(REPO, ALL_USERS_DEFAULT, EVERY_USER,
                item(List.of("trino"), "impersonate"),
                item(List.of("superset-svc"), "impersonate"),
                item(List.of("{USER}"), "impersonate"));

        assertEquals(1, revoke());

        JsonObject after = ranger.policies.get(defaultId);
        assertEquals(List.of(List.of("trino"), List.of("superset-svc")), grantees(after, "impersonate"),
                "only the every-user grant goes; the service principals still impersonate");
        assertEquals(List.of("*"), values(after, "trinouser"));
    }

    @Test
    void theLegacyKdpsPolicyIsDeletedOnceItGrantsNothing() throws Exception {
        // Without a default policy, the earlier version created its own policy over every Trino user.
        ranger.add(REPO, OmAtlasProvisioning.LEGACY_TRINO_SELF_IMPERSONATION_POLICY, EVERY_USER,
                item(List.of("{USER}"), "impersonate"));

        assertEquals(1, revoke());

        assertNull(ranger.byName(REPO, OmAtlasProvisioning.LEGACY_TRINO_SELF_IMPERSONATION_POLICY));
    }

    @Test
    void anOperatorPolicyLeftEmptyIsKeptNotDeleted() throws Exception {
        long operatorId = ranger.add(REPO, "operators impersonation", EVERY_USER,
                item(List.of("{USER}"), "impersonate"));

        assertEquals(1, revoke());

        JsonObject after = ranger.policies.get(operatorId);
        assertNotNull(after, "only the policy KDPS named itself may be removed");
        assertEquals(0, after.getAsJsonArray("policyItems").size());
    }

    @Test
    void aSharedItemLosesOnlyTheMacro() throws Exception {
        long id = ranger.add(REPO, ALL_USERS_DEFAULT, EVERY_USER,
                item(List.of("trino", "{USER}"), "impersonate"));

        revoke();

        assertEquals(List.of(List.of("trino")), grantees(ranger.policies.get(id), "impersonate"));
    }

    @Test
    void policiesOverOtherTrinoUsersAreLeftAlone() throws Exception {
        long self = ranger.add(REPO, "each user as themselves", REQUESTER, item(List.of("{USER}"), "impersonate"));
        long named = ranger.add(REPO, "support desk", Map.of("trinouser", List.of("alice")),
                item(List.of("{USER}"), "impersonate"));
        String selfBefore = ranger.policies.get(self).toString();
        String namedBefore = ranger.policies.get(named).toString();

        assertEquals(0, revoke());

        assertEquals(selfBefore, ranger.policies.get(self).toString());
        assertEquals(namedBefore, ranger.policies.get(named).toString());
        assertEquals(0, ranger.writes(), "nothing to take back means nothing is written");
    }

    @Test
    void aFreshRepositoryIsNotWritten() throws Exception {
        ranger.add(REPO, ALL_USERS_DEFAULT, EVERY_USER, item(List.of("trino"), "impersonate"));

        assertEquals(0, revoke());
        assertEquals(0, ranger.writes());
    }

    @Test
    void theRevokeReadsEveryPageOfPolicies() throws Exception {
        for (int i = 0; i < 150; i++) {
            ranger.add(REPO, "table policy " + i, Map.of("catalog", List.of("c" + i)), item(List.of("bob"), "select"));
        }
        long defaultId = ranger.add(REPO, ALL_USERS_DEFAULT, EVERY_USER, item(List.of("{USER}"), "impersonate"));

        assertEquals(1, revoke());

        assertTrue(grantees(ranger.policies.get(defaultId), "impersonate").isEmpty(),
                "a policy past the first page is reached too");
    }

    @Test
    void eachUserMayImpersonateOnlyThemselves() throws Exception {
        ranger.add(REPO, ALL_USERS_DEFAULT, EVERY_USER, item(List.of("trino"), "impersonate"));

        long id = ensureSelf();

        JsonObject self = ranger.policies.get(id);
        assertEquals(OmAtlasProvisioning.TRINO_SELF_IMPERSONATION_POLICY, self.get("name").getAsString());
        assertEquals(List.of("{USER}"), values(self, "trinouser"),
                "the impersonated user is the requester, never every user");
        assertEquals(List.of(List.of("{USER}")), grantees(self, "impersonate"));
        assertFalse(ranger.requests.stream().anyMatch(r -> r.contains("xusers/secure")),
                "the macro is not an account to create");
    }

    @Test
    void theSelfPolicyIsCreatedOnce() throws Exception {
        long first = ensureSelf();
        long writes = ranger.writes();

        assertEquals(first, ensureSelf());
        assertEquals(writes, ranger.writes(), "a second deploy finds the policy as it is");
        assertEquals(1, ranger.policies.size());
    }

    @Test
    void anOperatorPolicyOverTheRequesterReceivesTheGrant() throws Exception {
        long operator = ranger.add(REPO, "be yourself", REQUESTER, item(List.of("etl"), "impersonate"));

        assertEquals(operator, ensureSelf());

        assertEquals(List.of(List.of("etl"), List.of("{USER}")), grantees(ranger.policies.get(operator), "impersonate"));
        assertEquals(1, ranger.policies.size());
    }

    @Test
    void aSelfPolicyWidenedToEveryUserIsNotGrantedTo() throws Exception {
        long widened = ranger.add(REPO, OmAtlasProvisioning.TRINO_SELF_IMPERSONATION_POLICY, EVERY_USER,
                item(List.of("etl"), "impersonate"));

        assertThrows(IllegalStateException.class, this::ensureSelf);

        assertEquals(List.of(List.of("etl")), grantees(ranger.policies.get(widened), "impersonate"));
    }

    @Test
    void anUpgradedRepositoryEndsWithEachUserAsThemselvesOnly() throws Exception {
        long defaultId = ranger.add(REPO, ALL_USERS_DEFAULT, EVERY_USER,
                item(List.of("trino"), "impersonate"), item(List.of("{USER}"), "impersonate"));

        revoke();
        long self = ensureSelf();
        // A second deploy changes nothing.
        long writes = ranger.writes();
        assertEquals(0, revoke());
        assertEquals(self, ensureSelf());
        assertEquals(writes, ranger.writes());

        for (JsonObject p : ranger.policies.values()) {
            if (values(p, "trinouser").contains("*")) {
                for (List<String> users : grantees(p, "impersonate")) {
                    assertFalse(users.contains("{USER}"), "no policy lets every user impersonate every user: " + p);
                }
            }
        }
        assertEquals(List.of(List.of("trino")), grantees(ranger.policies.get(defaultId), "impersonate"));
    }

    @Test
    void theEveryUserGrantHelperRefusesTheMacro() {
        assertThrows(IllegalArgumentException.class, () -> OmAtlasProvisioning.createOrFindTrinoImpersonatePolicy(
                ranger.url(), "admin", "pw", REPO, "kdps-x-trino-impersonate", "{USER}", 5_000));
        assertEquals(0, ranger.requests.size());
    }

    @Test
    void theEveryUserGrantHelperStillGrantsAServicePrincipal() throws Exception {
        long defaultId = ranger.add(REPO, ALL_USERS_DEFAULT, EVERY_USER, item(List.of("trino"), "impersonate"));

        OmAtlasProvisioning.createOrFindTrinoImpersonatePolicy(
                ranger.url(), "admin", "pw", REPO, "kdps-bi-trino-impersonate", "superset-svc", 5_000);

        assertEquals(List.of(List.of("trino"), List.of("superset-svc")),
                grantees(ranger.policies.get(defaultId), "impersonate"));
    }

    // ---- the item and scope rules on their own ----

    @Test
    void withoutAccessKeepsTheMacrosOtherAccesses() {
        JsonArray items = JsonParser.parseString("[" + item(List.of("{USER}", "etl"), "impersonate", "select") + "]")
                .getAsJsonArray();

        JsonArray kept = OmAtlasProvisioning.withoutAccess(items, "{USER}", Set.of("impersonate"));

        assertEquals(2, kept.size());
        JsonObject others = kept.get(0).getAsJsonObject();
        assertEquals("[\"etl\"]", others.getAsJsonArray("users").toString());
        assertEquals(2, others.getAsJsonArray("accesses").size(), "the other principals keep everything");
        JsonObject macro = kept.get(1).getAsJsonObject();
        assertEquals("[\"{USER}\"]", macro.getAsJsonArray("users").toString());
        assertEquals("select", macro.getAsJsonArray("accesses").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(1, macro.getAsJsonArray("accesses").size());
    }

    @Test
    void withoutAccessSaysWhenThereIsNothingToTakeBack() {
        JsonArray items = JsonParser.parseString("[" + item(List.of("{USER}"), "select") + ","
                + item(List.of("trino"), "impersonate") + "]").getAsJsonArray();

        assertNull(OmAtlasProvisioning.withoutAccess(items, "{USER}", Set.of("impersonate")));
    }

    @Test
    void withoutAccessKeepsAnItemThatStillNamesAGroup() {
        JsonObject it = JsonParser.parseString(item(List.of("{USER}"), "impersonate")).getAsJsonObject();
        JsonArray groups = new JsonArray();
        groups.add("bi-services");
        it.add("groups", groups);
        JsonArray items = new JsonArray();
        items.add(it);

        JsonArray kept = OmAtlasProvisioning.withoutAccess(items, "{USER}", Set.of("impersonate"));

        assertEquals(1, kept.size());
        assertEquals(0, kept.get(0).getAsJsonObject().getAsJsonArray("users").size());
        assertEquals("[\"bi-services\"]", kept.get(0).getAsJsonObject().getAsJsonArray("groups").toString());
    }

    @Test
    void coversExactlyMatchesOnlyTheSameValues() {
        assertTrue(OmAtlasProvisioning.coversExactly(policy("{\"trinouser\":{\"values\":[\"*\"]}}"), EVERY_USER));
        assertFalse(OmAtlasProvisioning.coversExactly(policy("{\"trinouser\":{\"values\":[\"*\",\"alice\"]}}"), EVERY_USER));
        assertFalse(OmAtlasProvisioning.coversExactly(policy("{\"trinouser\":{\"values\":[\"{USER}\"]}}"), EVERY_USER));
        assertFalse(OmAtlasProvisioning.coversExactly(
                policy("{\"trinouser\":{\"values\":[\"*\"],\"isExcludes\":true}}"), EVERY_USER));
        assertFalse(OmAtlasProvisioning.coversExactly(
                policy("{\"catalog\":{\"values\":[\"*\"]},\"schema\":{\"values\":[\"*\"]}}"), Map.of("catalog", List.of("*"))));
    }

    @Test
    void grantsNothingLooksAtEveryKindOfItem() {
        assertTrue(OmAtlasProvisioning.grantsNothing(JsonParser.parseString(
                "{\"policyItems\":[],\"denyPolicyItems\":[]}").getAsJsonObject()));
        assertFalse(OmAtlasProvisioning.grantsNothing(JsonParser.parseString(
                "{\"policyItems\":[],\"denyPolicyItems\":[{\"users\":[\"bob\"]}]}").getAsJsonObject()));
    }

    private static JsonObject policy(String resources) {
        JsonObject p = new JsonObject();
        p.add("resources", JsonParser.parseString(resources));
        return p;
    }
}
