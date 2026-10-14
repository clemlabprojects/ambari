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
package org.apache.ambari.view.k8s.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GET /contexts/{id}/resolved returns a resolved context to every user of the view, read-only ones included, so no
 * credential the context holds may leave it: not even masked, only the fact that it is set.
 */
class ResolvedContextMaskTest {

  private static boolean isCredential(Field f) {
    String name = f.getName().toLowerCase(Locale.ROOT);
    return name.contains("password") || name.contains("secret") || name.contains("token");
  }

  @Test
  void noCredentialIsInTheJsonTheBrowserGets() throws Exception {
    ResolvedContext rc = new ResolvedContext();
    rc.setId("ext");
    rc.setRangerAdminUsername("admin");
    List<Field> credentials = new ArrayList<>();
    for (Field f : ResolvedContext.class.getDeclaredFields()) {
      if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class || !isCredential(f)) continue;
      f.setAccessible(true);
      f.set(rc, "plain-" + f.getName());
      credentials.add(f);
    }
    assertFalse(credentials.isEmpty(), "the context holds credentials, so the test must find some");

    ResolvedContext.ClientView view = rc.toClientView();

    for (String json : List.of(new ObjectMapper().writeValueAsString(view), new Gson().toJson(view))) {
      for (Field f : credentials) {
        assertFalse(json.contains("plain-" + f.getName()), f.getName() + " must not leave the view: " + json);
        assertFalse(json.contains("\"" + f.getName() + "\""), f.getName() + " is not even named: " + json);
      }
      assertTrue(json.contains("\"rangerAdminUsername\":\"admin\""), "user names are not secrets: " + json);
    }
  }

  @Test
  void theViewDeclaresNoCredentialField() {
    for (Field f : ResolvedContext.ClientView.class.getDeclaredFields()) {
      if (f.getName().equals("secretFieldsSet")) continue;
      assertFalse(isCredential(f), f.getName() + " would be sent to every user of the view");
    }
  }

  @Test
  void whichCredentialsAreSetStillShows() {
    ResolvedContext rc = new ResolvedContext();
    rc.getSecretFieldsSet().add("polaris.adminPassword");
    rc.setRangerAdminPassword("ranger-pass");
    rc.setOidcAdminClientSecret("keycloak-secret");

    ResolvedContext.ClientView view = rc.toClientView();

    assertEquals(List.of("polaris.adminPassword", "ranger.rangerAdminPassword", "oidc.adminClientSecret"),
        view.secretFieldsSet);
    assertEquals("ranger-pass", rc.getRangerAdminPassword(), "the context deploy steps use is unchanged");
  }

  @Test
  void everyOtherFieldIsCopied() throws Exception {
    ResolvedContext rc = new ResolvedContext();
    rc.setId("ext");
    rc.setKind("EXTERNAL");
    rc.setRangerManaged(true);
    rc.setAtlasManaged(true);
    rc.setAutoConfigureRemote(false);
    rc.setOidcIssuerUrl("https://sso.example.com/realms/odp");
    rc.getResolvedFields().put("hive.metastoreUri", "thrift://hms.example.com:9083");
    rc.getUnreadFields().add("oidc.realm");

    Map<?, ?> json = new ObjectMapper().convertValue(rc.toClientView(), Map.class);

    assertEquals("ext", json.get("id"));
    assertEquals("EXTERNAL", json.get("kind"));
    assertEquals(true, json.get("rangerManaged"));
    assertEquals(true, json.get("atlasManaged"));
    assertEquals(false, json.get("autoConfigureRemote"));
    assertEquals("https://sso.example.com/realms/odp", json.get("oidcIssuerUrl"));
    assertEquals(Map.of("hive.metastoreUri", "thrift://hms.example.com:9083"), json.get("resolvedFields"));
    assertEquals(List.of("oidc.realm"), json.get("unreadFields"));
    // Every non-credential property of the context is in the view: a new one must be added to it on purpose.
    for (Field f : ResolvedContext.class.getDeclaredFields()) {
      if (Modifier.isStatic(f.getModifiers()) || isCredential(f)) continue;
      assertTrue(json.containsKey(f.getName()), f.getName() + " is missing from the client view");
    }
  }
}
