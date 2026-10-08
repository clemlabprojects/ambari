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

package org.apache.ambari.view.k8s.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Helm follows a kubeconfig's current-context, so the one handed out must carry the context selected in KDPS. */
class KubeconfigSelectedContextTest {

  private static final String MULTI = String.join("\n",
      "apiVersion: v1",
      "kind: Config",
      "current-context: kind-local",
      "clusters:",
      "- name: kind-local",
      "  cluster: {server: 'https://127.0.0.1:6443'}",
      "- name: prod",
      "  cluster: {server: 'https://api.prod:6443'}",
      "contexts:",
      "- name: kind-local",
      "  context: {cluster: kind-local, user: dev}",
      "- name: prod-openshift",
      "  context: {cluster: prod, user: svc, namespace: team-a}",
      "users:",
      "- name: dev",
      "  user:",
      "    exec: {apiVersion: client.authentication.k8s.io/v1, command: kubelogin, args: [get-token]}",
      "- name: svc",
      "  user: {token: abc}",
      "extensions:",
      "- name: client.example/settings",
      "  extension: {keep: me}",
      "");

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(String yaml) throws Exception {
    return new ObjectMapper(new YAMLFactory()).readValue(yaml, Map.class);
  }

  @Test
  void theSelectedContextBecomesCurrentAndNothingElseChanges() throws Exception {
    String out = ViewConfigurationService.withCurrentContext(MULTI, "prod-openshift");
    Map<String, Object> before = parse(MULTI);
    Map<String, Object> after = parse(out);

    assertEquals("prod-openshift", after.get("current-context"));
    before.remove("current-context");
    after.remove("current-context");
    assertEquals(before, after, "exec plugins, extensions and everything else are kept");
  }

  @Test
  void unchangedWhenNothingToDo() {
    assertSame(MULTI, ViewConfigurationService.withCurrentContext(MULTI, null));
    assertSame(MULTI, ViewConfigurationService.withCurrentContext(MULTI, " "));
    assertSame(MULTI, ViewConfigurationService.withCurrentContext(MULTI, "kind-local"), "already current");
    assertSame(MULTI, ViewConfigurationService.withCurrentContext(MULTI, "not-in-the-file"), "unknown context");
    assertSame("not: [yaml", ViewConfigurationService.withCurrentContext("not: [yaml", "prod-openshift"));
    assertNull(ViewConfigurationService.withCurrentContext(null, "prod-openshift"));
  }

  @Test
  void jsonKubeconfigsWorkToo() throws Exception {
    String json = "{\"apiVersion\":\"v1\",\"kind\":\"Config\",\"current-context\":\"a\","
        + "\"contexts\":[{\"name\":\"a\",\"context\":{\"cluster\":\"a\"}},{\"name\":\"b\",\"context\":{\"cluster\":\"b\"}}],"
        + "\"clusters\":[],\"users\":[]}";
    Map<String, Object> after = parse(ViewConfigurationService.withCurrentContext(json, "b"));
    assertEquals("b", after.get("current-context"));
    assertEquals(2, ((List<?>) after.get("contexts")).size());
  }
}
