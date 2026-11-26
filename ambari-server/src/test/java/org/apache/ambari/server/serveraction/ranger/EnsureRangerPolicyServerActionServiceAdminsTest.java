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

import static org.apache.ambari.server.serveraction.ranger.FakeRangerAdmin.item;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@code serviceAdmins} mode: the users and groups a Trino release names as the administrators of its Ranger
 * service get, with delegated administration, every access of each service-wide policy ({@code all - catalog},
 * {@code all - queryid}...), as Ranger gives its lookup user; a policy over named resources is left alone.
 */
public class EnsureRangerPolicyServerActionServiceAdminsTest {

  private static final String REPO = "trino-analytics";
  private static final Map<String, List<String>> TABLES =
      Map.of("catalog", List.of("*"), "schema", List.of("*"), "table", List.of("*"));
  private static final Map<String, List<String>> QUERIES = Map.of("queryid", List.of("*"));

  private FakeRangerAdmin ranger;
  private long tables;
  private long queries;
  private long hiveOnly;

  @Before
  public void startRanger() throws Exception {
    ranger = new FakeRangerAdmin();
    // The policies Ranger creates with a Trino service, its lookup user in each, and one an operator added.
    tables = ranger.add(REPO, "all - catalog, schema, table", TABLES, admin("rangerlookup", "select", "show", "drop"));
    queries = ranger.add(REPO, "all - queryid", QUERIES, admin("rangerlookup", "execute"));
    hiveOnly = ranger.add(REPO, "hive readers", Map.of("catalog", List.of("hive"), "schema", List.of("*")),
        item(List.of("analysts"), "select"));
    ranger.userDirectory = true;
  }

  @After
  public void stopRanger() {
    ranger.close();
  }

  @Test
  public void theAdminsGetEveryAccessOfEachServiceWidePolicy() throws Exception {
    CommandReport report = run(admins("system,trino-analytics", "trino-admins", null));

    assertEquals("COMPLETED", report.getStatus());
    JsonObject out = JsonParser.parseString(report.getStructuredOut()).getAsJsonObject();
    assertEquals(List.of("system", "trino-analytics"), strings(out, "addedUsers"));
    assertEquals(List.of("trino-admins"), strings(out, "addedGroups"));
    assertEquals(2, out.get("serviceWidePolicies").getAsInt());

    JsonObject added = lastItem(tables);
    assertEquals(List.of("system", "trino-analytics"), strings(added, "users"));
    assertEquals(List.of("trino-admins"), strings(added, "groups"));
    assertEquals(List.of("drop", "select", "show"), accesses(added));
    assertTrue(added.get("delegateAdmin").getAsBoolean());
    assertEquals(List.of("execute"), accesses(lastItem(queries)));
    assertEquals("a policy over named resources is not an admin policy",
        1, ranger.policies.get(hiveOnly).getAsJsonArray("policyItems").size());
    assertTrue("Ranger has the users and the group the policies name",
        ranger.users.containsKey("system") && ranger.groups.containsKey("trino-admins"));
  }

  @Test
  public void aSecondRunWritesNothing() throws Exception {
    run(admins("system", "trino-admins", null));
    long writes = ranger.writes();

    CommandReport report = run(admins("system", "trino-admins", null));

    assertEquals("COMPLETED", report.getStatus());
    assertEquals(writes, ranger.writes());
    assertEquals(List.of(), strings(JsonParser.parseString(report.getStructuredOut()).getAsJsonObject(), "addedUsers"));
  }

  @Test
  public void aUserThatIsAlreadyAnAdminIsNeitherAddedNorReported() throws Exception {
    CommandReport report = run(admins("rangerlookup,system", null, null));

    assertEquals(List.of("system"), strings(JsonParser.parseString(report.getStructuredOut()).getAsJsonObject(),
        "addedUsers"));
    assertEquals(List.of("system"), strings(lastItem(tables), "users"));
  }

  @Test
  public void aRevokedAdminLeavesThePoliciesAndTheOthersStay() throws Exception {
    run(admins("system,alice", null, null));

    CommandReport report = run(admins("system", null, "alice"));

    assertEquals("COMPLETED", report.getStatus());
    for (long id : new long[]{tables, queries}) {
      JsonObject policy = ranger.policies.get(id);
      assertEquals(2, policy.getAsJsonArray("policyItems").size());
      assertEquals(List.of("system"), strings(lastItem(id), "users"));
    }
  }

  @Test
  public void aRevokeDoesNotTakeBackANameThatIsStillWanted() throws Exception {
    run(admins("system", null, null));

    run(admins("system", null, "system"));

    assertEquals(List.of("system"), strings(lastItem(queries), "users"));
  }

  @Test
  public void theServiceIsRequired() throws Exception {
    Map<String, String> params = admins("system", null, null);
    params.remove("rangerServiceName");

    CommandReport report = run(params);

    assertEquals("FAILED", report.getStatus());
    assertEquals(0, ranger.requests.size());
  }

  /** An allow item with delegated administration, as Ranger gives its lookup user. */
  private static String admin(String user, String... accesses) {
    JsonObject item = JsonParser.parseString(item(List.of(user), accesses)).getAsJsonObject();
    item.addProperty("delegateAdmin", true);
    return item.toString();
  }

  private JsonObject lastItem(long policyId) {
    com.google.gson.JsonArray items = ranger.policies.get(policyId).getAsJsonArray("policyItems");
    return items.get(items.size() - 1).getAsJsonObject();
  }

  private static List<String> strings(JsonObject object, String member) {
    List<String> out = new ArrayList<>();
    for (JsonElement e : object.getAsJsonArray(member)) {
      out.add(e.getAsString());
    }
    return out;
  }

  private static List<String> accesses(JsonObject item) {
    List<String> out = new ArrayList<>();
    for (JsonElement e : item.getAsJsonArray("accesses")) {
      out.add(e.getAsJsonObject().get("type").getAsString());
    }
    return out;
  }

  private static Map<String, String> admins(String users, String groups, String revokedUsers) {
    Map<String, String> params = new HashMap<>();
    params.put("clusterName", "c1");
    params.put("rangerServiceName", REPO);
    params.put("serviceAdmins", "true");
    if (users != null) {
      params.put("userNames", users);
    }
    if (groups != null) {
      params.put("groups", groups);
    }
    if (revokedUsers != null) {
      params.put("revokeUserNames", revokedUsers);
    }
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
