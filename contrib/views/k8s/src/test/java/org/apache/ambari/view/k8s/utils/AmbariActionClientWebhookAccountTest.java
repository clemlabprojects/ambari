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

package org.apache.ambari.view.k8s.utils;

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
 * The keytab webhook's Ambari account may only open the KDPS view instance: the Ambari Administrator role earlier
 * versions granted it by name is removed, and the account is deleted when the WEBHOOK injection mode is not used.
 */
class AmbariActionClientWebhookAccountTest {

  private static final String API = "https://ambari:8443/api/v1";

  private final URLStreamProvider stream = mock(URLStreamProvider.class);
  /** "METHOD url [body]" of every change sent. */
  private final List<String> changes = new CopyOnWriteArrayList<>();

  private AmbariActionClient answering(Map<String, String> getAnswers) throws IOException {
    doAnswer(call -> {
      String url = call.getArgument(0);
      String method = call.getArgument(1);
      if (!"GET".equals(method)) {
        changes.add(method + " " + url + (call.getArgument(2) == null ? "" : " " + call.getArgument(2)));
        return new ByteArrayInputStream(new byte[0]);
      }
      String answer = getAnswers.entrySet().stream().filter(e -> url.startsWith(e.getKey()))
          .map(Map.Entry::getValue).findFirst().orElse("{\"status\":404,\"message\":\"not found\"}");
      return new ByteArrayInputStream(answer.getBytes(StandardCharsets.UTF_8));
    }).when(stream).readFrom(anyString(), anyString(), nullable(String.class), anyMap());
    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getURLStreamProvider()).thenReturn(stream);
    return new AmbariActionClient(ctx, API, Map.of("Cookie", "AMBARISESSIONID=admin"));
  }

  @Test
  void onlyTheAdministratorRoleGrantedToTheAccountByNameIsRemoved() throws Exception {
    AmbariActionClient ambari = answering(Map.of(API + "/users/webhook-user/privileges", "{\"items\":["
        + privilege(7, "AMBARI.ADMINISTRATOR", "webhook-user", "USER", "AMBARI") + ","
        + privilege(9, "AMBARI.ADMINISTRATOR", "admins", "GROUP", "AMBARI") + ","
        + privilege(8, "VIEW.USER", "webhook-user", "USER", "VIEW") + "]}"));

    assertTrue(ambari.revokeAmbariAdministrator("webhook-user"));
    assertEquals(List.of("DELETE " + API + "/privileges/7"), changes);
  }

  @Test
  void anAccountWithoutTheRoleIsLeftAlone() throws Exception {
    AmbariActionClient ambari = answering(Map.of(API + "/users/webhook-user/privileges", "{\"items\":["
        + privilege(8, "VIEW.USER", "webhook-user", "USER", "VIEW") + "]}"));

    assertFalse(ambari.revokeAmbariAdministrator("webhook-user"));
    assertEquals(List.of(), changes);
  }

  @Test
  void theAccountIsGrantedTheViewInstanceOnlyOnce() throws Exception {
    String privileges = API + "/views/K8S-VIEW/versions/1.0.0/instances/KDPS/privileges";
    AmbariActionClient ambari = answering(Map.of(privileges, "{\"items\":[]}"));

    ambari.grantViewInstanceUser("K8S-VIEW", "1.0.0", "KDPS", "webhook-user");

    assertEquals(1, changes.size(), changes.toString());
    assertTrue(changes.get(0).startsWith("POST " + privileges + " "), changes.get(0));
    assertTrue(changes.get(0).contains("\"permission_name\":\"VIEW.USER\""), changes.get(0));
    assertTrue(changes.get(0).contains("\"principal_name\":\"webhook-user\""), changes.get(0));

    changes.clear();
    answering(Map.of(privileges, "{\"items\":[" + privilege(8, "VIEW.USER", "webhook-user", "USER", "VIEW") + "]}"))
        .grantViewInstanceUser("K8S-VIEW", "1.0.0", "KDPS", "webhook-user");
    assertEquals(List.of(), changes);
  }

  @Test
  void theAccountIsDeletedWhenItExists() throws Exception {
    AmbariActionClient ambari = answering(Map.of(API + "/users/webhook-user",
        "{\"Users\":{\"user_name\":\"webhook-user\"}}"));

    assertTrue(ambari.deleteUser("webhook-user"));
    assertEquals(List.of("DELETE " + API + "/users/webhook-user"), changes);

    changes.clear();
    assertFalse(answering(Map.of()).deleteUser("webhook-user"));
    assertEquals(List.of(), changes);
  }

  @Test
  void aRefusedLookupIsNotTakenForAMissingAccount() throws Exception {
    // A caller who may not read Ambari users gets a 403 body back, not an exception: the account is not "gone".
    AmbariActionClient ambari = answering(Map.of(API + "/users/webhook-user",
        "{\"status\":403,\"message\":\"Forbidden\"}"));

    IOException refused = assertThrows(IOException.class, () -> ambari.deleteUser("webhook-user"));
    assertTrue(refused.getMessage().contains("403"), refused.getMessage());
    assertEquals(List.of(), changes);
    assertThrows(IOException.class, () -> ambari.userExists("webhook-user"));
    assertThrows(IOException.class, () -> answering(Map.of(API + "/users/webhook-user", "{}"))
        .userExists("webhook-user"));
  }

  @Test
  void aRefusedChangeIsAFailure() throws Exception {
    doAnswer(call -> new ByteArrayInputStream("{\"status\":403,\"message\":\"Forbidden\"}"
        .getBytes(StandardCharsets.UTF_8))).when(stream).readFrom(anyString(), eq("DELETE"), nullable(String.class), anyMap());
    doAnswer(call -> new ByteArrayInputStream("{\"Users\":{\"user_name\":\"webhook-user\"}}"
        .getBytes(StandardCharsets.UTF_8))).when(stream).readFrom(anyString(), eq("GET"), nullable(String.class), anyMap());
    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getURLStreamProvider()).thenReturn(stream);

    assertThrows(IOException.class,
        () -> new AmbariActionClient(ctx, API, Map.of()).deleteUser("webhook-user"));
  }

  private static String privilege(int id, String permission, String principal, String principalType, String type) {
    return "{\"PrivilegeInfo\":{\"privilege_id\":" + id + ",\"permission_name\":\"" + permission
        + "\",\"principal_name\":\"" + principal + "\",\"principal_type\":\"" + principalType
        + "\",\"type\":\"" + type + "\"}}";
  }
}
