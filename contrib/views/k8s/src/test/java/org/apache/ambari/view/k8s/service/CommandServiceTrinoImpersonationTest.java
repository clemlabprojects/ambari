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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.ambari.view.URLStreamProvider;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trino repository impersonation rules on a managed context, where the Ambari server holds the
 * Ranger admin password and makes the Ranger calls ({@code POST /clusters/{c}/ranger_policy}).
 *
 * <p>Earlier versions granted the {@code {USER}} macro {@code impersonate} on every Trino user, so
 * any user could run queries as any other. The step lets each user act as themselves, then asks
 * Ambari to take the every-user rule back — last, so nothing granted before can leave it in place —
 * and fails if Ambari cannot. A self grant that fails leaves users refused rather than over-granted:
 * the step carries on, with a warning saying so.
 */
class CommandServiceTrinoImpersonationTest {

    private static final String REPO = "trino-analytics";

    /** Ambari's answers: the bodies POSTed to {@code ranger_policy}, and how each request ends. */
    static final class FakeAmbari implements URLStreamProvider {
        final List<JsonObject> rangerPolicyRequests = new ArrayList<>();
        /** How each submitted request ends, in order; {@code COMPLETED} once empty. */
        final Deque<String> outcomes = new ArrayDeque<>();
        /** A body Ambari answers to a revoke instead of accepting it, as a server without the option would. */
        String revokeRefusal;
        /** A body Ambari answers to a grant instead of accepting it. */
        String grantRefusal;
        /** How many policies a revoke reports it changed; null for a task that reports nothing. */
        Integer changedPolicies = 0;
        /** What the task of a failed request wrote to stderr: Ranger's answer, as the server action logs it. */
        String failedTaskStderr = "Ranger answered HTTP 403: User doesn't have permission";
        private final Map<Integer, String> status = new HashMap<>();
        private final Map<Integer, Boolean> revokes = new HashMap<>();
        private int nextRequest = 100;

        AmbariActionClient client() {
            ViewContext ctx = Mockito.mock(ViewContext.class);
            Mockito.when(ctx.getURLStreamProvider()).thenReturn(this);
            return new AmbariActionClient(ctx, "http://ambari:8080/api/v1", "c1", Map.of());
        }

        @Override
        public InputStream readFrom(String url, String method, String body, Map<String, String> headers) {
            if ("POST".equals(method) && url.endsWith("/clusters/c1/ranger_policy")) {
                JsonObject request = JsonParser.parseString(body).getAsJsonObject();
                boolean revoke = request.getAsJsonObject("RangerPolicy").has("revoke");
                if (revoke && revokeRefusal != null) {
                    return json(revokeRefusal);
                }
                if (!revoke && grantRefusal != null) {
                    return json(grantRefusal);
                }
                rangerPolicyRequests.add(request);
                int id = accept();
                revokes.put(id, revoke);
                return json("{\"Requests\":{\"id\":" + id + ",\"status\":\"Accepted\"}}");
            }
            if ("POST".equals(method) && url.endsWith("/clusters/c1/ranger_plugin_repository")) {
                return json("{\"Requests\":{\"id\":" + accept() + ",\"status\":\"Accepted\"}}");
            }
            Matcher tasks = Pattern.compile(".*/requests/(\\d+)/tasks(\\?.*)?$").matcher(url);
            if ("GET".equals(method) && tasks.matches()) {
                return json(tasksOf(Integer.parseInt(tasks.group(1))));
            }
            return json("{}");
        }

        private int accept() {
            int id = nextRequest++;
            status.put(id, outcomes.isEmpty() ? "COMPLETED" : outcomes.poll());
            return id;
        }

        /** The one task of a server-action request, as {@code GET .../requests/{id}/tasks} lists it. */
        private String tasksOf(int requestId) {
            JsonObject task = new JsonObject();
            task.addProperty("id", requestId * 10);
            task.addProperty("status", status.get(requestId));
            if ("FAILED".equals(status.get(requestId))) {
                task.addProperty("stderr", failedTaskStderr);
            } else if (Boolean.TRUE.equals(revokes.get(requestId)) && changedPolicies != null) {
                JsonObject out = new JsonObject();
                out.addProperty("changedPolicies", changedPolicies);
                task.add("structured_out", out);
            }
            JsonObject item = new JsonObject();
            item.add("Tasks", task);
            JsonArray items = new JsonArray();
            items.add(item);
            JsonObject root = new JsonObject();
            root.add("items", items);
            return root.toString();
        }

        @Override
        public InputStream readFrom(String url, String method, InputStream body, Map<String, String> headers) {
            Matcher request = Pattern.compile(".*/requests/(\\d+)$").matcher(url);
            if ("GET".equals(method) && request.matches()) {
                return json("{\"Requests\":{\"request_status\":\"" + status.get(Integer.parseInt(request.group(1))) + "\"}}");
            }
            return json("{}");
        }

        @Override
        public InputStream readAs(String url, String method, String body, Map<String, String> headers, String user) {
            return readFrom(url, method, body, headers);
        }

        @Override
        public InputStream readAs(String url, String method, InputStream body, Map<String, String> headers, String user) {
            return readFrom(url, method, body, headers);
        }

        @Override
        public InputStream readAsCurrent(String url, String method, String body, Map<String, String> headers) {
            return readFrom(url, method, body, headers);
        }

        @Override
        public InputStream readAsCurrent(String url, String method, InputStream body, Map<String, String> headers) {
            return readFrom(url, method, body, headers);
        }

        private static InputStream json(String s) {
            return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
        }

        JsonObject policy(int index) {
            return rangerPolicyRequests.get(index).getAsJsonObject("RangerPolicy");
        }
    }

    @Test
    void eachUserMayBeThemselvesAndTheEveryUserRuleIsTakenBackLast() {
        FakeAmbari ambari = new FakeAmbari();

        CommandService.restrictTrinoImpersonationViaAmbari(ambari.client(), REPO, 30);

        assertEquals(2, ambari.rangerPolicyRequests.size());
        JsonObject grant = ambari.policy(0);
        assertFalse(grant.has("revoke"));
        assertEquals(REPO, grant.get("rangerServiceName").getAsString());
        assertEquals("{USER}", grant.get("userName").getAsString());
        assertEquals("impersonate", grant.get("accessTypes").getAsString());
        assertEquals(List.of("{USER}"), trinoUsers(grant), "a user may only impersonate themselves");
        assertEquals(OmAtlasProvisioning.TRINO_SELF_IMPERSONATION_POLICY, grant.get("policyNameHint").getAsString());

        JsonObject revoke = ambari.policy(1);
        assertTrue(revoke.get("revoke").getAsBoolean(), "the last request takes access back");
        assertEquals(REPO, revoke.get("rangerServiceName").getAsString());
        assertEquals("{USER}", revoke.get("userName").getAsString());
        assertEquals("impersonate", revoke.get("accessTypes").getAsString());
        assertEquals(List.of("*"), trinoUsers(revoke), "it targets the grant over every Trino user");
        assertEquals(OmAtlasProvisioning.LEGACY_TRINO_SELF_IMPERSONATION_POLICY, revoke.get("policyNameHint").getAsString(),
                "the policy earlier versions created may be removed once empty");
    }

    @Test
    void noRequestEverLetsTheMacroImpersonateEveryUser() {
        FakeAmbari ambari = new FakeAmbari();

        CommandService.restrictTrinoImpersonationViaAmbari(ambari.client(), REPO, 30);

        for (JsonObject request : ambari.rangerPolicyRequests) {
            JsonObject p = request.getAsJsonObject("RangerPolicy");
            boolean grant = !p.has("revoke") || !p.get("revoke").getAsBoolean();
            if (grant && "{USER}".equals(p.get("userName").getAsString())) {
                assertFalse(trinoUsers(p).contains("*"), "granted {USER} on every Trino user: " + p);
            }
        }
    }

    @Test
    void anAmbariServerThatCannotRevokeStopsTheStep() {
        // An Ambari server without the revoke option refuses the unknown property: the step must fail,
        // not carry on as if the repository were safe.
        FakeAmbari ambari = new FakeAmbari();
        ambari.revokeRefusal = "{\"status\":400,\"message\":\"The properties [RangerPolicy/revoke] specified in the "
                + "request or predicate are not supported for the resource type RangerPolicy.\"}";

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CommandService.restrictTrinoImpersonationViaAmbari(ambari.client(), REPO, 30));

        assertTrue(failure.getMessage().contains(REPO), failure.getMessage());
        assertTrue(failure.getMessage().contains("RangerPolicy/revoke"), "Ambari's reason is passed on: " + failure.getMessage());
    }

    @Test
    void aFailedRevokeFailsTheStep() {
        FakeAmbari ambari = new FakeAmbari();
        ambari.outcomes.add("COMPLETED");
        ambari.outcomes.add("FAILED");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CommandService.restrictTrinoImpersonationViaAmbari(ambari.client(), REPO, 30));

        assertTrue(failure.getMessage().contains(REPO), failure.getMessage());
    }

    @Test
    void aFailedSelfGrantDoesNotFailTheStepButIsReported() {
        // Without the self policy users are refused, never over-granted, so the step carries on; the
        // every-user rule is gone though, so the operator must hear that users will be refused.
        FakeAmbari ambari = new FakeAmbari();
        ambari.outcomes.add("FAILED");
        ambari.outcomes.add("COMPLETED");

        CommandService.TrinoImpersonationRules rules =
                CommandService.restrictTrinoImpersonationViaAmbari(ambari.client(), REPO, 30);

        assertEquals(2, ambari.rangerPolicyRequests.size(), "the revoke is still sent");
        assertTrue(ambari.policy(1).get("revoke").getAsBoolean());
        String warning = rules.selfGrantWarning();
        assertNotNull(warning, "a self grant that did not complete must not pass silently");
        assertTrue(warning.contains(REPO), warning);
        assertTrue(warning.contains("Ambari request 100 did not complete"), warning);
        assertTrue(warning.contains("User doesn't have permission"), "Ranger's answer is passed on: " + warning);
        assertTrue(warning.contains("Trino user '{USER}' granted to '{USER}'"), "names the policy to add: " + warning);
    }

    @Test
    void aSelfGrantAmbariRefusesIsReported() {
        FakeAmbari ambari = new FakeAmbari();
        ambari.grantRefusal = "{\"status\":403,\"message\":\"You do not have permissions to access this resource.\"}";

        CommandService.TrinoImpersonationRules rules =
                CommandService.restrictTrinoImpersonationViaAmbari(ambari.client(), REPO, 30);

        assertEquals(1, ambari.rangerPolicyRequests.size());
        assertTrue(ambari.policy(0).get("revoke").getAsBoolean(), "the revoke is still sent");
        assertNotNull(rules.selfGrantWarning());
        assertTrue(rules.selfGrantWarning().contains("You do not have permissions"), rules.selfGrantWarning());
    }

    @Test
    void aSuccessfulRunWarnsOfNothingAndSaysHowManyPoliciesLostTheRule() {
        FakeAmbari ambari = new FakeAmbari();
        ambari.changedPolicies = 2;

        CommandService.TrinoImpersonationRules rules =
                CommandService.restrictTrinoImpersonationViaAmbari(ambari.client(), REPO, 30);

        assertNull(rules.selfGrantWarning());
        assertEquals(2, rules.revokedPolicies(), "taken from what the revoke task reported");
    }

    @Test
    void aRevokeThatReportsNothingStillPasses() {
        FakeAmbari ambari = new FakeAmbari();
        ambari.changedPolicies = null;

        CommandService.TrinoImpersonationRules rules =
                CommandService.restrictTrinoImpersonationViaAmbari(ambari.client(), REPO, 30);

        assertEquals(-1, rules.revokedPolicies(), "unknown, not zero");
    }

    @Test
    void theFailureTellsTheOperatorWhatIsStillOpen() {
        String message = CommandService.trinoImpersonationRevokeFailure(REPO, new IOException("HTTP 401"));

        assertTrue(message.contains(REPO));
        assertTrue(message.contains("HTTP 401"));
        assertTrue(message.contains("{USER}"), "names the item to remove by hand: " + message);
    }

    @Test
    void aServiceGrantToTheMacroIsRefused() {
        CommandService cs = Mockito.mock(CommandService.class, Mockito.CALLS_REAL_METHODS);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("releaseName", "bi");
        params.put("_principal", "{USER}");
        params.put("_trinoRangerServiceName", REPO);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> cs.grantRangerTrinoImpersonate(params));

        assertTrue(refused.getMessage().contains("{USER}"), refused.getMessage());
    }

    private static List<String> trinoUsers(JsonObject rangerPolicy) {
        JsonObject resources = JsonParser.parseString(rangerPolicy.get("resourcesJson").getAsString()).getAsJsonObject();
        assertEquals(1, resources.size(), "only the trinouser resource: " + resources);
        List<String> out = new ArrayList<>();
        resources.getAsJsonArray("trinouser").forEach(v -> out.add(v.getAsString()));
        return out;
    }
}
