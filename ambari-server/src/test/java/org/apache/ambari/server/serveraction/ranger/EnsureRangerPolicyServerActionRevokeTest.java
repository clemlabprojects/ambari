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

package org.apache.ambari.server.serveraction.ranger;

import static org.apache.ambari.server.serveraction.ranger.FakeRangerAdmin.grantees;
import static org.apache.ambari.server.serveraction.ranger.FakeRangerAdmin.item;
import static org.apache.ambari.server.serveraction.ranger.FakeRangerAdmin.values;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ambari.server.actionmanager.HostRoleCommand;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.agent.ExecutionCommand;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * {@link EnsureRangerPolicyServerAction} run against an in-memory Ranger, in the shape the KDPS view uses it for
 * a Trino repository. Earlier versions granted the {@code {USER}} macro, which Ranger matches against every user,
 * {@code impersonate} on {@code trinouser=*}: anyone could run queries as anyone. A revoke must take exactly
 * that back — the service principals sharing the policy keep their grant — and the self grant the view sends with
 * it must only let each user act as themselves ({@code trinouser={USER}}). The view sends the revoke last.
 */
public class EnsureRangerPolicyServerActionRevokeTest {

  private static final String REPO = "trino-analytics";
  private static final String LEGACY = "kdps - users impersonate themselves";
  private static final String SELF = "kdps - users impersonate only themselves";
  private static final Map<String, List<String>> EVERY_USER = Map.of("trinouser", List.of("*"));

  private FakeRangerAdmin ranger;

  @Before
  public void startRanger() throws Exception {
    ranger = new FakeRangerAdmin();
  }

  @After
  public void stopRanger() {
    ranger.close();
  }

  @Test
  public void revokeTakesBackTheEveryUserGrantAndKeepsTheServicePrincipals() throws Exception {
    long defaultId = ranger.add(REPO, "all - trinouser", EVERY_USER,
        item(List.of("trino"), "impersonate"),
        item(List.of("superset-svc", "{USER}"), "impersonate"));

    CommandReport report = run(revoke());

    assertEquals("COMPLETED", report.getStatus());
    assertEquals(1, JsonParser.parseString(report.getStructuredOut()).getAsJsonObject()
        .get("changedPolicies").getAsInt());
    assertEquals(List.of(List.of("trino"), List.of("superset-svc")),
        grantees(ranger.policies.get(defaultId), "impersonate"));
  }

  @Test
  public void revokeDeletesTheNamedPolicyOnceItGrantsNothing() throws Exception {
    ranger.add(REPO, LEGACY, EVERY_USER, item(List.of("{USER}"), "impersonate"));
    long operatorId = ranger.add(REPO + "-other", "all - trinouser", EVERY_USER, item(List.of("{USER}"), "impersonate"));

    assertEquals("COMPLETED", run(revoke()).getStatus());

    assertNull(ranger.byName(REPO, LEGACY));
    assertEquals("another repository is not touched",
        List.of(List.of("{USER}")), grantees(ranger.policies.get(operatorId), "impersonate"));
  }

  @Test
  public void revokeKeepsAPolicyOfAnotherNameEvenWhenEmpty() throws Exception {
    long id = ranger.add(REPO, "operators impersonation", EVERY_USER, item(List.of("{USER}"), "impersonate"));

    assertEquals("COMPLETED", run(revoke()).getStatus());

    assertNotNull(ranger.policies.get(id));
    assertTrue(grantees(ranger.policies.get(id), "impersonate").isEmpty());
  }

  @Test
  public void revokeLeavesOtherScopesAloneAndCreatesNoUser() throws Exception {
    long self = ranger.add(REPO, SELF, Map.of("trinouser", List.of("{USER}")), item(List.of("{USER}"), "impersonate"));
    String before = ranger.policies.get(self).toString();

    CommandReport report = run(revoke());

    assertEquals("COMPLETED", report.getStatus());
    assertEquals(before, ranger.policies.get(self).toString());
    assertEquals(0, ranger.writes());
    assertFalse("a revoke looks nothing up in xusers",
        ranger.requests.stream().anyMatch(r -> r.contains("/xusers/")));
  }

  @Test
  public void revokeIsRefusedForMaskingPolicies() throws Exception {
    Map<String, String> params = revoke();
    params.put("policyType", "1");
    params.put("maskType", "MASK");

    CommandReport report = run(params);

    assertEquals("FAILED", report.getStatus());
    assertEquals(0, ranger.requests.size());
  }

  @Test
  public void anUpgradedRepositoryEndsWithEachUserAsThemselvesOnly() throws Exception {
    long defaultId = ranger.add(REPO, "all - trinouser", EVERY_USER,
        item(List.of("trino"), "impersonate"), item(List.of("{USER}"), "impersonate"));

    assertEquals("COMPLETED", run(selfGrant()).getStatus());
    assertEquals("COMPLETED", run(revoke()).getStatus());

    JsonObject self = ranger.byName(REPO, SELF);
    assertNotNull(self);
    assertEquals(List.of("{USER}"), values(self, "trinouser"));
    assertEquals(List.of(List.of("{USER}")), grantees(self, "impersonate"));
    assertEquals(List.of(List.of("trino")), grantees(ranger.policies.get(defaultId), "impersonate"));

    // A second deploy finds everything in place.
    long writes = ranger.writes();
    assertEquals("COMPLETED", run(selfGrant()).getStatus());
    assertEquals("COMPLETED", run(revoke()).getStatus());
    assertEquals(writes, ranger.writes());
  }

  @Test
  public void theRevokeSentLastUndoesASelfGrantThatLandedOnEveryUser() throws Exception {
    // An operator widened the self policy to every Trino user: the grant appends to it by name, and only the
    // revoke that follows keeps the macro from impersonating everyone.
    long widened = ranger.add(REPO, SELF, EVERY_USER, item(List.of("etl"), "impersonate"));

    assertEquals("COMPLETED", run(selfGrant()).getStatus());
    assertEquals("COMPLETED", run(revoke()).getStatus());

    assertEquals(List.of(List.of("etl")), grantees(ranger.policies.get(widened), "impersonate"));
  }

  @Test
  public void aGrantWithoutRevokeStillGrants() throws Exception {
    long defaultId = ranger.add(REPO, "all - trinouser", EVERY_USER, item(List.of("trino"), "impersonate"));
    Map<String, String> params = revoke();
    params.remove("revoke");
    params.put("userName", "superset-svc");
    params.put("policyNameHint", "kdps-bi-trino-impersonate");

    assertEquals("COMPLETED", run(params).getStatus());

    assertEquals(List.of(List.of("trino"), List.of("superset-svc")),
        grantees(ranger.policies.get(defaultId), "impersonate"));
  }

  @Test
  public void anOwnedPolicyOnlyRevokeKeepsTheSameAccessInOtherPolicies() throws Exception {
    // A service user listed in the repository's default policy keeps that access: whether it came from the
    // release being removed cannot be told. Only the policy named after the release is trimmed or deleted.
    long defaultId = ranger.add(REPO, "all - trinouser", EVERY_USER,
        item(List.of("trino"), "impersonate"), item(List.of("superset-svc"), "impersonate"));
    ranger.add(REPO, "kdps-bi-trino-impersonate", EVERY_USER, item(List.of("superset-svc"), "impersonate"));
    Map<String, String> params = revoke();
    params.put("userName", "superset-svc");
    params.put("policyNameHint", "kdps-bi-trino-impersonate");
    params.put("ownedPolicyOnly", "true");

    CommandReport report = run(params);

    assertEquals("COMPLETED", report.getStatus());
    assertEquals(1, JsonParser.parseString(report.getStructuredOut()).getAsJsonObject()
        .get("changedPolicies").getAsInt());
    assertNull(ranger.byName(REPO, "kdps-bi-trino-impersonate"));
    assertEquals(List.of(List.of("trino"), List.of("superset-svc")),
        grantees(ranger.policies.get(defaultId), "impersonate"));
  }

  @Test
  public void anOwnedPolicyOnlyRevokeWithoutThatPolicyChangesNothing() throws Exception {
    long defaultId = ranger.add(REPO, "all - trinouser", EVERY_USER, item(List.of("superset-svc"), "impersonate"));
    String before = ranger.policies.get(defaultId).toString();
    Map<String, String> params = revoke();
    params.put("userName", "superset-svc");
    params.put("policyNameHint", "kdps-bi-trino-impersonate");
    params.put("ownedPolicyOnly", "true");

    CommandReport report = run(params);

    assertEquals("COMPLETED", report.getStatus());
    assertEquals(0, JsonParser.parseString(report.getStructuredOut()).getAsJsonObject()
        .get("changedPolicies").getAsInt());
    assertEquals(before, ranger.policies.get(defaultId).toString());
    assertEquals(0, ranger.writes());
  }

  @Test
  public void ownedPolicyOnlyIsRefusedForAGrant() throws Exception {
    Map<String, String> params = revoke();
    params.remove("revoke");
    params.put("ownedPolicyOnly", "true");

    CommandReport report = run(params);

    assertEquals("FAILED", report.getStatus());
    assertEquals(0, ranger.requests.size());
  }

  private Map<String, String> revoke() {
    Map<String, String> params = new HashMap<>();
    params.put("clusterName", "c1");
    params.put("rangerServiceName", REPO);
    params.put("userName", "{USER}");
    params.put("accessTypes", "impersonate");
    params.put("resourcesJson", "{\"trinouser\":[\"*\"]}");
    params.put("policyNameHint", LEGACY);
    params.put("timeoutSeconds", "5");
    params.put("revoke", "true");
    return params;
  }

  private Map<String, String> selfGrant() {
    Map<String, String> params = revoke();
    params.remove("revoke");
    params.put("resourcesJson", "{\"trinouser\":[\"{USER}\"]}");
    params.put("policyNameHint", SELF);
    return params;
  }

  /** Runs the action for cluster c1, whose Ranger is the in-memory one. */
  private CommandReport run(Map<String, String> commandParams) throws Exception {
    Config adminProperties = createNiceMock(Config.class);
    expect(adminProperties.getProperties()).andReturn(Map.of("policymgr_external_url", ranger.url())).anyTimes();
    Config rangerEnv = createNiceMock(Config.class);
    Map<String, String> env = new HashMap<>();
    env.put("ranger_admin_username", "admin");
    env.put("ranger_admin_password", "admin-pw");
    expect(rangerEnv.getProperties()).andReturn(env).anyTimes();
    Cluster cluster = createNiceMock(Cluster.class);
    expect(cluster.getDesiredConfigByType("admin-properties")).andReturn(adminProperties).anyTimes();
    expect(cluster.getDesiredConfigByType("ranger-env")).andReturn(rangerEnv).anyTimes();
    Clusters clusters = createNiceMock(Clusters.class);
    expect(clusters.getCluster("c1")).andReturn(cluster).anyTimes();
    AmbariManagementController controller = createNiceMock(AmbariManagementController.class);
    expect(controller.getClusters()).andReturn(clusters).anyTimes();
    HostRoleCommand hostRoleCommand = createNiceMock(HostRoleCommand.class);
    replay(adminProperties, rangerEnv, cluster, clusters, controller, hostRoleCommand);

    EnsureRangerPolicyServerAction action = new EnsureRangerPolicyServerAction();
    Field field = EnsureRangerPolicyServerAction.class.getDeclaredField("managementController");
    field.setAccessible(true);
    field.set(action, controller);
    ExecutionCommand executionCommand = new ExecutionCommand();
    executionCommand.setClusterName("c1");
    executionCommand.setCommandParams(commandParams);
    action.setExecutionCommand(executionCommand);
    action.setHostRoleCommand(hostRoleCommand);
    return action.execute(null);
  }
}
