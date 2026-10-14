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

import static org.apache.ambari.server.serveraction.ranger.FakeRangerAdmin.roles;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * {@link UpsertRangerUserServerAction} run against an in-memory Ranger, in the shape the KDPS view uses it for the
 * account a Trino release's Atlas tag projector writes tags with: created or reset with the roles asked for on every
 * deploy, and disabled when the release is uninstalled, so the password left in the release's project stops working
 * and the account holds no administrator role any more.
 */
public class UpsertRangerUserServerActionTest {

  private static final String WRITER = "kdps-tagsync-analytics_trino";
  private static final String OWNER = "KDPS Atlas tag sync account of Trino release analytics/trino";

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
  public void disablingTakesAwayTheRolesAndThePassword() throws Exception {
    ranger.addUser(WRITER, 0, "ROLE_SYS_ADMIN");

    CommandReport report = run(disable(WRITER));

    assertEquals("COMPLETED", report.getStatus());
    JsonObject out = JsonParser.parseString(report.getStructuredOut()).getAsJsonObject();
    assertTrue(out.get("found").getAsBoolean());
    assertTrue(out.get("disabled").getAsBoolean());
    JsonObject user = ranger.users.get(WRITER);
    assertEquals(0, user.get("status").getAsInt());
    assertEquals(List.of("ROLE_USER"), roles(user));
    String password = user.get("password").getAsString();
    assertFalse("the password the release's Secret held no longer logs in", "initial-password".equals(password));
    assertFalse("*****".equals(password));
    assertFalse("the new password is nowhere in the task output",
        report.getStdOut().contains(password) || report.getStdErr().contains(password));
  }

  @Test
  public void aUserRangerDoesNotHaveIsNothingToDisable() throws Exception {
    ranger.userDirectory = true;

    CommandReport report = run(disable(WRITER));

    assertEquals("COMPLETED", report.getStatus());
    JsonObject out = JsonParser.parseString(report.getStructuredOut()).getAsJsonObject();
    assertFalse(out.get("found").getAsBoolean());
    assertFalse(out.get("disabled").getAsBoolean());
    assertEquals(0, ranger.writes());
  }

  @Test
  public void aLookupRangerRefusesIsAFailureNotAnAbsentUser() throws Exception {
    ranger.addUser(WRITER, 0, "ROLE_SYS_ADMIN");
    ranger.userLookupStatus = 401;

    CommandReport report = run(disable(WRITER));

    assertEquals("FAILED", report.getStatus());
    assertTrue(report.getStdErr(), report.getStdErr().contains("401"));
    assertEquals(1, ranger.users.get(WRITER).get("status").getAsInt());
  }

  @Test
  public void anExternalUserIsLeftAlone() throws Exception {
    ranger.addUser(WRITER, 1, "ROLE_SYS_ADMIN");

    CommandReport report = run(disable(WRITER));

    assertEquals("FAILED", report.getStatus());
    assertEquals(0, ranger.writes());
    assertEquals(1, ranger.users.get(WRITER).get("status").getAsInt());
  }

  @Test
  public void theAdministratorAmbariUsesIsNeverDisabled() throws Exception {
    ranger.addUser("admin", 0, "ROLE_SYS_ADMIN");

    CommandReport report = run(disable("Admin"));

    assertEquals("FAILED", report.getStatus());
    assertTrue(ranger.requests.isEmpty());
  }

  @Test
  public void aDisabledAccountComesBackWhenTheReleaseIsDeployedAgain() throws Exception {
    // The same release name installed again: the deploy resets the account it had, which enables it again.
    ranger.addUser(WRITER, 0, "ROLE_SYS_ADMIN");
    assertEquals("COMPLETED", run(disable(WRITER)).getStatus());

    assertEquals("COMPLETED", run(upsert(WRITER, "Deploy2Password", "ROLE_SYS_ADMIN")).getStatus());

    JsonObject user = ranger.users.get(WRITER);
    assertEquals(1, user.get("status").getAsInt());
    assertEquals(List.of("ROLE_SYS_ADMIN"), roles(user));
    assertEquals("Deploy2Password", user.get("password").getAsString());
  }

  @Test
  public void aMissingUserIsCreatedWithTheRolesAskedFor() throws Exception {
    ranger.userDirectory = true;

    assertEquals("COMPLETED", run(upsert(WRITER, "Deploy1Password", "ROLE_SYS_ADMIN")).getStatus());

    JsonObject user = ranger.users.get(WRITER);
    assertEquals(1, user.get("status").getAsInt());
    assertEquals(List.of("ROLE_SYS_ADMIN"), roles(user));
    assertEquals("Deploy1Password", user.get("password").getAsString());
  }

  @Test
  public void aCreatedUserIsDescribedAsBelongingToItsOwner() throws Exception {
    ranger.userDirectory = true;
    Map<String, String> params = upsert(WRITER, "Deploy1Password", "ROLE_SYS_ADMIN");
    params.put("userDescription", OWNER);

    assertEquals("COMPLETED", run(params).getStatus());

    assertEquals(OWNER, ranger.users.get(WRITER).get("description").getAsString());
  }

  @Test
  public void aUserOfThatNameBelongingToSomethingElseIsNotTakenOver() throws Exception {
    JsonObject other = ranger.addUser(WRITER, 0, "ROLE_USER");
    other.addProperty("description", "KDPS Atlas tag sync account of Trino release analytics/trino on Kubernetes"
        + " https://api.other.example:6443");
    Map<String, String> params = upsert(WRITER, "Deploy1Password", "ROLE_SYS_ADMIN");
    params.put("userDescription", OWNER);

    CommandReport report = run(params);

    assertEquals("FAILED", report.getStatus());
    assertTrue(report.getStdErr(), report.getStdErr().contains("left unchanged"));
    assertEquals(0, ranger.writes());
    assertEquals("initial-password", ranger.users.get(WRITER).get("password").getAsString());
    assertEquals(List.of("ROLE_USER"), roles(ranger.users.get(WRITER)));
  }

  @Test
  public void aUserBelongingToSomethingElseIsNotDisabled() throws Exception {
    ranger.addUser(WRITER, 0, "ROLE_SYS_ADMIN").addProperty("description", "an operator's own account");
    Map<String, String> params = disable(WRITER);
    params.put("userDescription", OWNER);

    CommandReport report = run(params);

    assertEquals("FAILED", report.getStatus());
    assertEquals(0, ranger.writes());
    assertEquals(1, ranger.users.get(WRITER).get("status").getAsInt());
  }

  @Test
  public void theOwnersAccountIsDisabledWhenItsDescriptionNamesIt() throws Exception {
    ranger.addUser(WRITER, 0, "ROLE_SYS_ADMIN").addProperty("description", OWNER);
    Map<String, String> params = disable(WRITER);
    params.put("userDescription", OWNER);

    assertEquals("COMPLETED", run(params).getStatus());

    assertEquals(0, ranger.users.get(WRITER).get("status").getAsInt());
  }

  @Test
  public void aGeneratedPasswordIsOneRangerAccepts() {
    for (int i = 0; i < 50; i++) {
      String password = UpsertRangerUserServerAction.randomPassword();
      assertTrue(password, password.length() >= 8 && password.matches(".*[A-Za-z].*") && password.matches(".*\\d.*"));
    }
  }

  private static Map<String, String> disable(String user) {
    Map<String, String> params = new HashMap<>();
    params.put("clusterName", "c1");
    params.put("pluginUserName", user);
    params.put("disableUser", "true");
    return params;
  }

  private static Map<String, String> upsert(String user, String password, String roles) {
    Map<String, String> params = new HashMap<>();
    params.put("clusterName", "c1");
    params.put("pluginUserName", user);
    params.put("pluginUserPassword", password);
    params.put("userRoles", roles);
    params.put("resetPassword", "true");
    return params;
  }

  /** Runs the action for cluster c1, whose Ranger is the in-memory one, administered by "admin". */
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

    UpsertRangerUserServerAction action = new UpsertRangerUserServerAction();
    Field field = UpsertRangerUserServerAction.class.getDeclaredField("managementController");
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
