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

package org.apache.ambari.view.k8s.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.ambari.view.URLStreamProvider;
import org.apache.ambari.view.ViewContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * What the uninstall of a release asks the Ambari server: a Ranger revoke limited to the policy named for the
 * release, for an account that holds access of its own, and the deletion of the view instance KDPS created for it.
 */
class AmbariActionClientReleaseCleanupTest {

  private static final String API = "https://ambari:8443/api/v1";

  private final URLStreamProvider stream = mock(URLStreamProvider.class);
  /** "METHOD url [body]" of every request sent. */
  private final List<String> sent = new CopyOnWriteArrayList<>();

  private AmbariActionClient answering(String answer) throws IOException {
    doAnswer(call -> {
      sent.add(call.getArgument(1) + " " + call.getArgument(0)
          + (call.getArgument(2) == null ? "" : " " + call.getArgument(2)));
      return new ByteArrayInputStream(answer.getBytes(StandardCharsets.UTF_8));
    }).when(stream).readFrom(anyString(), anyString(), nullable(String.class), anyMap());
    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getURLStreamProvider()).thenReturn(stream);
    return new AmbariActionClient(ctx, API, "c1", Map.of("Cookie", "AMBARISESSIONID=admin"));
  }

  private JsonObject rangerPolicyBody() {
    String request = sent.get(0);
    return JsonParser.parseString(request.substring(request.indexOf(' ', request.indexOf(' ') + 1) + 1))
        .getAsJsonObject().getAsJsonObject("RangerPolicy");
  }

  @Test
  void aRevokeLimitedToTheReleasesPolicySaysSo() throws Exception {
    AmbariActionClient ambari = answering("{\"Requests\":{\"id\":31}}");

    assertEquals(31, ambari.submitRangerPolicyRevoke("trino-team-a", "trino", "show,use", "{\"catalog\":[\"*\"]}",
        "kdps-openmetadata-om-trino-catalogs", true, 60, "uninstall"));

    assertTrue(sent.get(0).startsWith("POST " + API + "/clusters/c1/ranger_policy "), sent.get(0));
    JsonObject body = rangerPolicyBody();
    assertTrue(body.get("revoke").getAsBoolean());
    assertTrue(body.get("ownedPolicyOnly").getAsBoolean());
    assertEquals("kdps-openmetadata-om-trino-catalogs", body.get("policyNameHint").getAsString());
  }

  @Test
  void aPlainRevokeDoesNotSendTheFlag() throws Exception {
    AmbariActionClient ambari = answering("{\"Requests\":{\"id\":32}}");

    ambari.submitRangerPolicyRevoke("c1_hive", "oma-ing-team-a", "select", "{}", "kdps-om-hive-read", 60, "uninstall");

    assertFalse(rangerPolicyBody().has("ownedPolicyOnly"), "an Ambari server without it must still accept the revoke");
  }

  @Test
  void theViewInstanceOfTheReleaseIsDeleted() throws Exception {
    AmbariActionClient ambari = answering("");

    assertTrue(ambari.deleteViewInstance("SQL-ASSISTANT-VIEW", "1.0.0.0", "sql assistant"));

    assertEquals(List.of("DELETE " + API + "/views/SQL-ASSISTANT-VIEW/versions/1.0.0.0/instances/sql%20assistant"),
        sent);
  }

  @Test
  void whetherAViewInstanceExistsIsAskedBeforeItIsProvisioned() throws Exception {
    assertTrue(answering("{\"ViewInstanceInfo\":{\"instance_name\":\"om\"}}")
        .viewInstanceExists("SQL-ASSISTANT-VIEW", "1.0.0.0", "om"));
    assertEquals(List.of("GET " + API + "/views/SQL-ASSISTANT-VIEW/versions/1.0.0.0/instances/om"), sent);
    assertFalse(answering("{\"status\":404,\"message\":\"not found\"}")
        .viewInstanceExists("SQL-ASSISTANT-VIEW", "1.0.0.0", "om"));
    assertThrows(IOException.class, () -> answering("{\"status\":403,\"message\":\"Forbidden\"}")
        .viewInstanceExists("SQL-ASSISTANT-VIEW", "1.0.0.0", "om"));
  }

  @Test
  void aViewInstanceAlreadyGoneIsNotAFailure() throws Exception {
    assertFalse(answering("{\"status\":404,\"message\":\"not found\"}")
        .deleteViewInstance("SQL-ASSISTANT-VIEW", "1.0.0.0", "om"));
    IOException refused = assertThrows(IOException.class, () -> answering("{\"status\":403,\"message\":\"Forbidden\"}")
        .deleteViewInstance("SQL-ASSISTANT-VIEW", "1.0.0.0", "om"));
    assertTrue(refused.getMessage().contains("403"));
  }
}
