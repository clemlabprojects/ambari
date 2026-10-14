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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.model.ContextRequest;
import org.apache.ambari.view.k8s.service.om.FakeRangerAdmin;
import org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.apache.ambari.view.k8s.service.om.FakeRangerAdmin.grantees;
import static org.apache.ambari.view.k8s.service.om.FakeRangerAdmin.item;
import static org.apache.ambari.view.k8s.service.om.FakeRangerAdmin.values;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The "Ranger: create plugin repository" step of a Trino deploy, run against a platform context that
 * carries its own Ranger admin credentials, so the view talks to Ranger directly (here an in-memory
 * Ranger), and against the managed context, where the Ambari server makes the Ranger calls. A
 * redeploy over a repository an earlier version configured must leave no rule letting a user act as
 * any other Trino user, and must fail rather than pass when it cannot make sure of that. The step
 * warns when it removed that rule, since service accounts that relied on it are refused from then on,
 * and when users could not be allowed to act as themselves.
 */
class CommandServiceTrinoRangerRepositoryStepTest {

    private static final String REPO = "trino-analytics";
    private static final Map<String, List<String>> EVERY_USER = Map.of("trinouser", List.of("*"));

    @TempDir
    Path workDir;

    private final Gson gson = new Gson();
    private PerUserViewContext view;
    private CommandService service;
    private FakeRangerAdmin ranger;

    /** Steps run with slf4j's MDC; see CommandServiceHelmStepTest for why it is loaded this way. */
    @BeforeAll
    static void loadMdc() {
        String javaVersion = System.getProperty("java.version");
        System.setProperty("java.version", "1.8");
        try {
            org.slf4j.MDC.getMDCAdapter();
        } finally {
            System.setProperty("java.version", javaVersion);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        ranger = new FakeRangerAdmin();
        view = new PerUserViewContext("trino-repo-" + Long.toHexString(System.nanoTime()), workDir);
        service = new CommandService(view.context, mock(HelmService.class), mock(KubernetesService.class));
        // The background sweep reads the view context: stubbing it while that thread runs races with Mockito.
        service.startupSweep.get(30, java.util.concurrent.TimeUnit.SECONDS);

        view.as("admin");
        ContextRequest context = new ContextRequest();
        context.id = "ext";
        context.name = "External cluster";
        context.kind = ContextService.KIND_EXTERNAL;
        context.config = new LinkedHashMap<>(Map.of("rangerUrl", ranger.url(), "rangerAdminUsername", "admin"));
        context.secrets = new LinkedHashMap<>(Map.of("rangerAdminPassword", "Ranger-Pass"));
        new ContextService(view.context).save(context);
        view.as(null);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        ranger.close();
    }

    /** Stores a deploy whose only step creates the Trino Ranger repository in the context Ranger, and runs it. */
    private String repositoryStep() {
        return repositoryStep("ext");
    }

    /**
     * Stores a deploy whose only step creates the Trino Ranger repository, runs it and returns the step
     * id. A null {@code platformContextId} selects the managed context, served through Ambari.
     */
    private String repositoryStep(String platformContextId) {
        String id = "deploy-" + Long.toHexString(System.nanoTime());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chart", "trino");
        params.put("releaseName", "analytics");
        params.put("namespace", "analytics");
        params.put("_cluster", "c1");
        params.put("_baseUri", "http://ambari:8080/");
        params.put("_rangerRepositoryName", REPO);
        params.put("_rangerServiceType", "trino");
        if (platformContextId != null) params.put("_platformContextId", platformContextId);
        params.put("timeout", 30);

        view.dataStore.store(command(id, CommandType.K8S_MANAGER_RELEASE_DEPLOY, "Deploy release analytics", params,
                List.of(id + "-step")));
        view.dataStore.store(status(id, CommandState.RUNNING));
        view.dataStore.store(command(id + "-step", CommandType.RANGER_REPOSITORY_CREATION,
                "Ranger: create plugin repository", params, null));
        view.dataStore.store(status(id + "-step", CommandState.PENDING));
        service.runNextStep(id);
        return id + "-step";
    }

    @Test
    void aRedeployLeavesEachUserImpersonatingOnlyThemselves() {
        ranger.services.put(REPO, 3L);
        long defaultId = ranger.add(REPO, "all - trinouser", EVERY_USER,
                item(List.of("trino"), "impersonate"), item(List.of("{USER}"), "impersonate"));

        String step = repositoryStep();

        assertEquals(CommandState.SUCCEEDED.name(), statusOf(step).getState(), statusOf(step).getMessage());
        assertEquals(List.of(List.of("trino")), grantees(ranger.policies.get(defaultId), "impersonate"),
                "the every-user grant is gone, the service principal keeps its own");
        JsonObject self = ranger.byName(REPO, OmAtlasProvisioning.TRINO_SELF_IMPERSONATION_POLICY);
        assertNotNull(self);
        assertEquals(List.of("{USER}"), values(self, "trinouser"));
        assertEquals(List.of(List.of("{USER}")), grantees(self, "impersonate"));
    }

    @Test
    void theRemovedRuleIsAWarningThatNamesTheAccountsLeftWithoutAGrant() throws InterruptedException {
        // Service accounts KDPS never grants, such as Superset over OIDC without a Trino service
        // principal or a dbt project running queries as another user, only worked through the rule
        // that was removed: the operator must hear that they now need a policy of their own.
        ranger.services.put(REPO, 3L);
        ranger.add(REPO, "all - trinouser", EVERY_USER,
                item(List.of("trino"), "impersonate"), item(List.of("{USER}"), "impersonate"));

        String step = repositoryStep();

        CommandStatusEntity st = statusOf(step);
        assertEquals(CommandState.SUCCEEDED.name(), st.getState(), st.getMessage());
        String log = awaitLog(step, "WARNING: Ranger repository '" + REPO + "': removed the rule that let any user "
                + "act as any other Trino user (1 policy updated).");
        assertTrue(log.contains("needs its own 'impersonate' policy on Trino user '*'"), log);
        assertTrue(log.contains("Superset that connects with OIDC and has no Trino service principal"), log);
        assertTrue(log.contains("dbt project that sets 'Run queries as'"), log);
        List<?> warnings = (List<?>) gson.fromJson(st.getResultJson(), Map.class).get("warnings");
        assertNotNull(warnings, "the warning is in the step result too: " + st.getResultJson());
        assertEquals(1, warnings.size(), warnings::toString);
    }

    @Test
    void aRepositoryThatNeverHadTheRuleWarnsOfNothing() {
        ranger.services.put(REPO, 3L);
        ranger.add(REPO, "all - trinouser", EVERY_USER, item(List.of("trino"), "impersonate"));

        String step = repositoryStep();

        CommandStatusEntity st = statusOf(step);
        assertEquals(CommandState.SUCCEEDED.name(), st.getState(), st.getMessage());
        assertNull(gson.fromJson(st.getResultJson(), Map.class).get("warnings"), st.getResultJson());
    }

    @Test
    void aRefusedSelfPolicyPassesTheStepWithAWarning() throws InterruptedException {
        // The every-user rule must still go; without the self policy users are refused rather than
        // over-granted, and the operator is told so instead of finding out from Trino.
        ranger.services.put(REPO, 3L);
        long defaultId = ranger.add(REPO, "all - trinouser", EVERY_USER,
                item(List.of("trino"), "impersonate"), item(List.of("{USER}"), "impersonate"));
        ranger.refusePolicyCreate = true;

        String step = repositoryStep();

        CommandStatusEntity st = statusOf(step);
        assertEquals(CommandState.SUCCEEDED.name(), st.getState(), st.getMessage());
        assertEquals(List.of(List.of("trino")), grantees(ranger.policies.get(defaultId), "impersonate"),
                "the every-user grant is removed all the same");
        assertNull(ranger.byName(REPO, OmAtlasProvisioning.TRINO_SELF_IMPERSONATION_POLICY));

        String log = awaitLog(step, "WARNING: Ranger repository '" + REPO + "': could not add the policy that "
                + "lets each user run Trino queries as themselves");
        assertTrue(log.contains("User doesn't have permission"), "Ranger's answer is passed on: " + log);
        assertTrue(log.contains("'impersonate' policy on Trino user '{USER}' granted to '{USER}'"), log);
        List<?> warnings = (List<?>) gson.fromJson(st.getResultJson(), Map.class).get("warnings");
        assertNotNull(warnings, "the warning is in the step result too: " + st.getResultJson());
        assertEquals(2, warnings.size(), "the removed rule, then the missing self policy: " + warnings);
        assertTrue(String.valueOf(warnings.get(0)).contains("removed the rule"), warnings::toString);
        assertTrue(String.valueOf(warnings.get(1)).contains("could not add the policy"), warnings::toString);
    }

    @Test
    void theManagedPathReportsARefusedSelfGrantAndTheRemovedRule() throws InterruptedException {
        CommandServiceTrinoImpersonationTest.FakeAmbari ambari = new CommandServiceTrinoImpersonationTest.FakeAmbari();
        doReturn(ambari).when(view.context).getURLStreamProvider();
        ambari.outcomes.add("COMPLETED");   // the repository
        ambari.outcomes.add("FAILED");      // the self-impersonation grant
        ambari.outcomes.add("COMPLETED");   // the revoke
        ambari.changedPolicies = 1;

        String step = repositoryStep(null);

        CommandStatusEntity st = statusOf(step);
        assertEquals(CommandState.SUCCEEDED.name(), st.getState(), st.getMessage());
        assertEquals(2, ambari.rangerPolicyRequests.size());
        assertTrue(ambari.policy(1).get("revoke").getAsBoolean(), "the every-user rule is still taken back");
        awaitLog(step, "WARNING: Ranger repository '" + REPO + "': removed the rule that let any user act as any "
                + "other Trino user (1 policy updated).");
        String log = awaitLog(step, "WARNING: Ranger repository '" + REPO + "': could not add the policy that "
                + "lets each user run Trino queries as themselves");
        assertTrue(log.contains("User doesn't have permission"), "Ranger's answer is passed on: " + log);
        assertNotNull(gson.fromJson(st.getResultJson(), Map.class).get("warnings"), st.getResultJson());
    }

    @Test
    void theLegacyPolicyOfAnEarlierVersionIsReplaced() {
        ranger.services.put(REPO, 3L);
        ranger.add(REPO, OmAtlasProvisioning.LEGACY_TRINO_SELF_IMPERSONATION_POLICY, EVERY_USER,
                item(List.of("{USER}"), "impersonate"));

        String step = repositoryStep();

        assertEquals(CommandState.SUCCEEDED.name(), statusOf(step).getState(), statusOf(step).getMessage());
        assertNull(ranger.byName(REPO, OmAtlasProvisioning.LEGACY_TRINO_SELF_IMPERSONATION_POLICY));
        for (JsonObject p : ranger.policies.values()) {
            assertFalse(values(p, "trinouser").contains("*") && grantees(p, "impersonate").stream()
                    .anyMatch(users -> users.contains("{USER}")), "every user may still impersonate every user: " + p);
        }
    }

    @Test
    void aFreshRepositoryGetsOnlyTheSelfPolicy() {
        String step = repositoryStep();

        assertEquals(CommandState.SUCCEEDED.name(), statusOf(step).getState(), statusOf(step).getMessage());
        assertTrue(ranger.services.containsKey(REPO));
        assertEquals(1, ranger.policies.size());
        assertEquals(List.of("{USER}"), values(ranger.byName(REPO, OmAtlasProvisioning.TRINO_SELF_IMPERSONATION_POLICY),
                "trinouser"));
    }

    @Test
    void theStepFailsWhenItCannotCheckTheRepository() {
        ranger.services.put(REPO, 3L);
        ranger.add(REPO, "all - trinouser", EVERY_USER, item(List.of("{USER}"), "impersonate"));
        ranger.failListing = true;

        String step = repositoryStep();

        CommandStatusEntity st = statusOf(step);
        assertEquals(CommandState.FAILED.name(), st.getState(), "a repository that may keep the rule is not passed");
        assertTrue(st.getMessage().contains("Could not remove the Ranger rule on repository '" + REPO + "'"),
                st.getMessage());
    }

    private CommandEntity command(String id, CommandType type, String title, Map<String, Object> params,
                                  List<String> children) {
        CommandEntity c = new CommandEntity();
        c.setId(id);
        c.setType(type.name());
        c.setTitle(title);
        c.setCommandStatusId(id + "-status");
        c.setParamsJson(gson.toJson(params));
        if (children != null) c.setChildListJson(gson.toJson(children));
        return c;
    }

    private static CommandStatusEntity status(String commandId, CommandState state) {
        CommandStatusEntity s = new CommandStatusEntity();
        s.setId(commandId + "-status");
        s.setState(state.name());
        s.setAttempt(0);
        return s;
    }

    private CommandStatusEntity statusOf(String commandId) {
        return view.dataStore.find(CommandStatusEntity.class, commandId + "-status");
    }

    /** The command log is written asynchronously; waits for a line to show up. */
    private String awaitLog(String commandId, String expected) throws InterruptedException {
        String content = "";
        for (int i = 0; i < 200; i++) {
            content = CommandLogService.get(view.context).read(commandId, 0, 1 << 16).content;
            if (content.contains(expected)) return content;
            Thread.sleep(25);
        }
        fail("'" + expected + "' never reached the log of " + commandId + ": " + content);
        return content;
    }
}
