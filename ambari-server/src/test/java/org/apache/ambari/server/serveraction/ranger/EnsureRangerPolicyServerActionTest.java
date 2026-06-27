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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Unit tests for the pure (Ranger-free) logic of {@link EnsureRangerPolicyServerAction}:
 * policy JSON construction, the idempotency check, and parsing the conflicting-policy
 * name out of Ranger's duplicate-resource validation error.
 */
public class EnsureRangerPolicyServerActionTest {

  @Test
  public void parseConflictingPolicyName_extractsNameFromCode3010Error() {
    String body = "{\"statusCode\":1,\"msgDesc\":\"(0) Validation failure: error code[3010], "
        + "reason[Another policy already exists for matching resource: "
        + "policy-name=[all - entity-type, entity-classification, entity], "
        + "service=[clemlabtest_atlas]], field[resources], subfield[null], "
        + "type[semantically incorrect] \"}";
    assertEquals("all - entity-type, entity-classification, entity",
        EnsureRangerPolicyServerAction.parseConflictingPolicyName(body));
  }

  @Test
  public void parseConflictingPolicyName_returnsNullWhenAbsent() {
    assertNull(EnsureRangerPolicyServerAction.parseConflictingPolicyName(
        "{\"statusCode\":1,\"msgDesc\":\"Invalid access type\"}"));
    assertNull(EnsureRangerPolicyServerAction.parseConflictingPolicyName(null));
  }

  @Test
  public void buildPolicy_producesValidRangerStructure() {
    String resources = "{\"entity-type\":[\"*\"],\"entity-classification\":[\"*\"],\"entity\":[\"*\"]}";
    JsonObject p = EnsureRangerPolicyServerAction.buildPolicy(
        "clemlabtest_atlas", "kdps-om-read-entities", "desc",
        "openmetadata-federation", "entity-read", resources);

    assertEquals("clemlabtest_atlas", p.get("service").getAsString());
    assertEquals("kdps-om-read-entities", p.get("name").getAsString());
    assertEquals(0, p.get("policyType").getAsInt());
    assertTrue(p.get("isEnabled").getAsBoolean());

    JsonObject res = p.getAsJsonObject("resources");
    for (String key : new String[]{"entity-type", "entity-classification", "entity"}) {
      JsonObject r = res.getAsJsonObject(key);
      assertEquals("*", r.getAsJsonArray("values").get(0).getAsString());
      assertFalse(r.get("isExcludes").getAsBoolean());
      assertFalse(r.get("isRecursive").getAsBoolean());
    }

    JsonArray items = p.getAsJsonArray("policyItems");
    assertEquals(1, items.size());
    JsonObject item = items.get(0).getAsJsonObject();
    assertEquals("openmetadata-federation", item.getAsJsonArray("users").get(0).getAsString());
    assertFalse(item.get("delegateAdmin").getAsBoolean());
    JsonObject access = item.getAsJsonArray("accesses").get(0).getAsJsonObject();
    assertEquals("entity-read", access.get("type").getAsString());
    assertTrue(access.get("isAllowed").getAsBoolean());
  }

  @Test
  public void buildPolicy_splitsCommaSeparatedAccessTypes() {
    JsonObject p = EnsureRangerPolicyServerAction.buildPolicy(
        "svc", "name", null, "user", "type-read, entity-read", "{\"type\":[\"*\"]}");
    JsonArray accesses = p.getAsJsonArray("policyItems").get(0).getAsJsonObject().getAsJsonArray("accesses");
    assertEquals(2, accesses.size());
    assertEquals("type-read", accesses.get(0).getAsJsonObject().get("type").getAsString());
    assertEquals("entity-read", accesses.get(1).getAsJsonObject().get("type").getAsString());
  }

  @Test
  public void userHasAllAccesses_trueWhenUserAlreadyGranted() {
    JsonArray items = JsonParser.parseString(
        "[{\"users\":[\"openmetadata-federation\"],"
        + "\"accesses\":[{\"type\":\"entity-read\",\"isAllowed\":true}]}]").getAsJsonArray();
    assertTrue(EnsureRangerPolicyServerAction.userHasAllAccesses(
        items, "openmetadata-federation", new String[]{"entity-read"}));
  }

  @Test
  public void userHasAllAccesses_falseWhenUserAbsent() {
    JsonArray items = JsonParser.parseString(
        "[{\"users\":[\"admin\"],\"accesses\":[{\"type\":\"entity-read\",\"isAllowed\":true}]}]")
        .getAsJsonArray();
    assertFalse(EnsureRangerPolicyServerAction.userHasAllAccesses(
        items, "openmetadata-federation", new String[]{"entity-read"}));
  }

  @Test
  public void userHasAllAccesses_falseWhenAccessMissing() {
    JsonArray items = JsonParser.parseString(
        "[{\"users\":[\"openmetadata-federation\"],"
        + "\"accesses\":[{\"type\":\"entity-read\",\"isAllowed\":true}]}]").getAsJsonArray();
    // user present but lacks type-read
    assertFalse(EnsureRangerPolicyServerAction.userHasAllAccesses(
        items, "openmetadata-federation", new String[]{"entity-read", "type-read"}));
  }
}
