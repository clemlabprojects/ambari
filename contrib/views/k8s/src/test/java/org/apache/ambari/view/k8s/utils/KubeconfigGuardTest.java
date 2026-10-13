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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.Config;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A kubeconfig must not be able to make the Ambari server run a command or read one of its files: what a client is
 * given is written by KDPS from the fields that can do neither.
 */
class KubeconfigGuardTest {

  private static final String HEAD = String.join("\n",
      "apiVersion: v1",
      "kind: Config",
      "current-context: a",
      "clusters:",
      "- name: a",
      "  cluster: {server: 'https://a:6443', certificate-authority-data: Q0E=, tls-server-name: api.a,"
          + " proxy-url: 'http://proxy:3128'}",
      "contexts:",
      "- name: a",
      "  context: {cluster: a, user: plain, namespace: kdps}",
      "");

  private static final String WITH_EVERYTHING = HEAD + String.join("\n",
      "users:",
      "- name: plugin",
      "  user:",
      "    exec: {apiVersion: client.authentication.k8s.io/v1, command: /bin/sh, args: [-c, id]}",
      "- name: gcp",
      "  user:",
      "    auth-provider: {name: gcp, config: {cmd-path: /bin/sh}}",
      "- name: both",
      "  user:",
      "    token: abc",
      "    exec: {command: kubelogin}",
      "- name: files",
      "  user: {tokenFile: /etc/ambari-server/conf/password.dat, client-certificate: /c.crt, client-key: /c.key}",
      "- name: plain",
      "  user: {token: abc, client-certificate-data: Y2VydA==, client-key-data: a2V5, as: admin, as-groups: [ops]}",
      "- name: basic",
      "  user: {username: me, password: secret}",
      "extensions:",
      "- name: client.example/settings",
      "  extension: {keep: me}",
      "");

  private static Map<String, Object> parse(String yaml) throws Exception {
    @SuppressWarnings("unchecked")
    Map<String, Object> map = new ObjectMapper(new YAMLFactory()).readValue(yaml, Map.class);
    return map;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> user(String yaml, String name) throws Exception {
    for (Object u : (List<Object>) parse(yaml).get("users")) {
      Map<String, Object> named = (Map<String, Object>) u;
      if (name.equals(named.get("name"))) {
        return (Map<String, Object>) named.get("user");
      }
    }
    throw new AssertionError("no user " + name);
  }

  private static String problems(String kubeconfig) {
    return String.join("\n", KubeconfigGuard.problems(KubeconfigGuard.parse(kubeconfig)));
  }

  @Test
  void everyCommandAndFileIsNamedWithItsUser() {
    List<String> problems = KubeconfigGuard.problems(KubeconfigGuard.parse(WITH_EVERYTHING));

    assertEquals(List.of(
        "User 'plugin' gets its credentials by running a command (exec). KDPS does not run commands on the Ambari "
            + "server: use a kubeconfig with a token or a client certificate.",
        "User 'gcp' gets its credentials by running a command (auth-provider). KDPS does not run commands on the "
            + "Ambari server: use a kubeconfig with a token or a client certificate.",
        "User 'both' gets its credentials by running a command (exec). KDPS does not run commands on the Ambari "
            + "server: use a kubeconfig with a token or a client certificate.",
        "User 'files' reads its token from a file on the Ambari server (tokenFile). KDPS does not read files from "
            + "the Ambari server for a kubeconfig: put its token itself in the kubeconfig (token).",
        "User 'files' reads its client certificate from a file on the Ambari server (client-certificate). KDPS does "
            + "not read files from the Ambari server for a kubeconfig: put its client certificate itself in the "
            + "kubeconfig (client-certificate-data).",
        "User 'files' reads its client key from a file on the Ambari server (client-key). KDPS does not read files "
            + "from the Ambari server for a kubeconfig: put its client key itself in the kubeconfig (client-key-data)."),
        problems);
  }

  @Test
  void aClusterCaFileIsRefused() {
    String withCaFile = HEAD.replace("certificate-authority-data: Q0E=", "certificate-authority: /etc/pki/ca.crt")
        + "users:\n- name: plain\n  user: {token: t}\n";

    assertEquals("Cluster 'a' reads its CA certificate from a file on the Ambari server (certificate-authority). KDPS "
        + "does not read files from the Ambari server for a kubeconfig: put its CA certificate itself in the "
        + "kubeconfig (certificate-authority-data).", problems(withCaFile));
    assertFalse(KubeconfigGuard.canonical(withCaFile).contains("/etc/pki"), "and is not passed on");
  }

  @Test
  void aKubeconfigWithInlineCredentialsHasNoProblem() {
    String plain = HEAD + "users:\n- name: plain\n  user: {token: t, client-certificate-data: Y2VydA==, client-key-data: a2V5}\n"
        + "- name: basic\n  user: {username: me, password: pw, as: admin, as-uid: '1', as-groups: [ops],"
        + " as-user-extra: {scopes: [view]}}\n"
        // minikube writes provider details as extensions
        + "extensions:\n- name: cluster_info\n  extension: {provider: minikube.sigs.k8s.io, version: v1.33.0}\n"
        + "preferences: {}\n";

    assertEquals("", problems(plain));
  }

  @Test
  void theCanonicalKubeconfigHasNoCommandNoFileAndKeepsTheConnection() throws Exception {
    String out = KubeconfigGuard.canonical(WITH_EVERYTHING);

    assertFalse(out.contains("/bin/sh") || out.contains("kubelogin") || out.contains("exec")
        || out.contains("auth-provider") || out.contains("cmd-path"), out);
    assertFalse(out.contains("password.dat") || out.contains("/c.crt") || out.contains("/c.key"), out);
    assertTrue(KubeconfigGuard.problems(KubeconfigGuard.parse(out)).isEmpty(), out);

    assertEquals(Map.of(), user(out, "plugin"));
    assertEquals(Map.of(), user(out, "gcp"));
    assertEquals(Map.of(), user(out, "files"));
    assertEquals(Map.of("token", "abc"), user(out, "both"), "the user's other credential is kept");
    assertEquals(Map.of("token", "abc", "client-certificate-data", "Y2VydA==", "client-key-data", "a2V5",
        "as", "admin", "as-groups", List.of("ops")), user(out, "plain"));
    assertEquals(Map.of("username", "me", "password", "secret"), user(out, "basic"));

    Map<String, Object> model = parse(out);
    assertEquals("a", model.get("current-context"));
    assertEquals(List.of(Map.of("name", "a", "cluster", Map.of("server", "https://a:6443",
        "certificate-authority-data", "Q0E=", "tls-server-name", "api.a", "proxy-url", "http://proxy:3128"))),
        model.get("clusters"));
    assertEquals(List.of(Map.of("name", "a", "context", Map.of("cluster", "a", "user", "plain", "namespace", "kdps"))),
        model.get("contexts"));
    assertFalse(model.containsKey("extensions"), "nothing a connection does not need is copied");
  }

  @Test
  void theCanonicalKubeconfigIsTheSameWhenWrittenAgain() {
    String once = KubeconfigGuard.canonical(WITH_EVERYTHING);
    assertEquals(once, KubeconfigGuard.canonical(once));
  }

  @Test
  void everyValueIsWrittenAsAQuotedString() {
    // Go's YAML reads an unquoted yes, on or 0123 as a boolean or a number, which a string field then refuses.
    String out = KubeconfigGuard.canonical(HEAD + "users:\n- name: plain\n  user: {token: 'on', username: 'yes', password: '0123'}\n");
    assertTrue(out.contains("token: \"on\"") && out.contains("username: \"yes\"") && out.contains("password: \"0123\""), out);
  }

  @Test
  void aTaggedKeyIsRefusedAndNeverPassedOn() {
    // Go's YAML library decodes a !!binary key into the text it encodes ("users" / "user"), so the key is a section
    // for Helm while it reads as base64 text for the Kubernetes client.
    String taggedUsers = HEAD + "!!binary dXNlcnM=:\n- name: v\n  user: {exec: {command: /bin/id}}\n";
    String taggedUser = HEAD + "users:\n- name: v\n  !!binary dXNlcg==: {exec: {command: /bin/id}}\n";

    assertTrue(problems(taggedUsers).contains("The kubeconfig has a field KDPS does not recognise (dXNlcnM=)"),
        problems(taggedUsers));
    assertTrue(problems(taggedUser).contains("User 'v' has a field KDPS does not recognise (dXNlcg==)"),
        problems(taggedUser));
    for (String crafted : List.of(taggedUsers, taggedUser)) {
      String out = KubeconfigGuard.canonical(crafted);
      assertFalse(out.contains("/bin/id") || out.contains("dXNlc"), out);
    }
  }

  @Test
  void aCommandBehindAnAliasIsSeenAndNeverPassedOn() {
    String viaAlias = "x-hidden: &x {exec: {command: /bin/id}}\n" + HEAD + "users:\n- name: v\n  user: *x\n";

    String problems = problems(viaAlias);
    assertTrue(problems.contains("User 'v' gets its credentials by running a command (exec)"), problems);
    assertTrue(problems.contains("The kubeconfig has a field KDPS does not recognise (x-hidden)"), problems);
    assertFalse(KubeconfigGuard.canonical(viaAlias).contains("/bin/id"));
  }

  @Test
  void aMergeKeyIsRefusedAndNeverPassedOn() {
    // The Kubernetes client keeps "<<" as a field name; Go's YAML merges the fields in, exec included.
    String viaMerge = "x-hidden: &x {exec: {command: /bin/id}}\n" + HEAD + "users:\n- name: v\n  user: {<<: *x}\n";
    String bare = HEAD + "users:\n- name: v\n  user: {<<: {exec: {command: /bin/id}}}\n";

    for (String crafted : List.of(viaMerge, bare)) {
      assertTrue(problems(crafted).contains("User 'v' has a field KDPS does not recognise (<<)"), problems(crafted));
      String out = KubeconfigGuard.canonical(crafted);
      assertFalse(out.contains("/bin/id") || out.contains("<<"), out);
    }
  }

  @Test
  void aFieldInAnotherLetterCaseIsRefusedAndNeverPassedOn() {
    // A field the kubeconfig model does not know is not guessed at, whatever it resembles.
    String shouted = HEAD + "users:\n- name: v\n  user:\n    Exec: {command: /bin/id}\n    token: t\n";

    assertTrue(problems(shouted).contains("User 'v' has a field KDPS does not recognise (Exec)"), problems(shouted));
    String out = KubeconfigGuard.canonical(shouted);
    assertFalse(out.contains("/bin/id"), out);
    assertEquals("t", KubeconfigGuard.parse(out).getUsers().get(0).getUser().getToken());
  }

  @Test
  void aRepeatedKeyCannotBeRead() {
    // Parsers disagree on which of the two wins, so one of them could be the one with the command.
    String twice = HEAD + "users:\n- name: v\n  user: {exec: {command: x}}\n  user: {token: t}\n";
    assertThrows(IllegalArgumentException.class, () -> KubeconfigGuard.parse(twice));
    assertThrows(IllegalArgumentException.class, () -> KubeconfigGuard.canonical(twice));
  }

  @Test
  void moreThanOneDocumentCannotBeRead() {
    String two = HEAD + "users:\n- name: v\n  user: {token: t}\n---\nusers:\n- name: v\n  user: {exec: {command: x}}\n";
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> KubeconfigGuard.canonical(two));
    assertTrue(e.getMessage().contains("single kubeconfig document"), e.getMessage());
  }

  @Test
  void aTabIndentedJsonKubeconfigIsReadAndWrittenAsYaml() throws Exception {
    // jq --tab and Python's json.dump(indent='\t') write this; the clients read it although a YAML parser would not.
    String json = "{\n\t\"apiVersion\": \"v1\",\n\t\"kind\": \"Config\",\n\t\"current-context\": \"a\",\n"
        + "\t\"clusters\": [{\"name\": \"a\", \"cluster\": {\"server\": \"https://a:6443\"}}],\n"
        + "\t\"contexts\": [{\"name\": \"a\", \"context\": {\"cluster\": \"a\", \"user\": \"p\"}}],\n"
        + "\t\"users\": [\n\t\t{\"name\": \"p\", \"user\": {\"token\": \"t\"}},\n"
        + "\t\t{\"name\": \"e\", \"user\": {\"exec\": {\"command\": \"x\"}}}\n\t]\n}\n";

    assertEquals("User 'e' gets its credentials by running a command (exec). KDPS does not run commands on the Ambari "
        + "server: use a kubeconfig with a token or a client certificate.", problems(json));
    String out = KubeconfigGuard.canonical(json);
    assertFalse(out.contains("\t"), out);
    assertEquals(Map.of("token", "t"), user(out, "p"));
    assertEquals(Map.of(), user(out, "e"));
  }

  @Test
  void textThatIsNotAKubeconfigIsNeverHandedOn() {
    for (String text : List.of("not: [yaml", "users: abc\n", "users:\n- name: v\n  user: [exec]\n")) {
      IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> KubeconfigGuard.canonical(text), text);
      assertTrue(e.getMessage().startsWith("The kubeconfig cannot be read"), e.getMessage());
    }
  }

  @Test
  void theReasonAnUnreadableKubeconfigIsRefusedDoesNotQuoteIt() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> KubeconfigGuard.parse("users:\n- name: v\n  user: {token: s3cr3t, token: s3cr3t}\n"));
    assertFalse(e.getMessage().contains("s3cr3t"), e.getMessage());
  }

  @Test
  void emptyInputIsPassedThroughButNotReadAsAKubeconfig() {
    assertNull(KubeconfigGuard.canonical(null));
    assertEquals(" ", KubeconfigGuard.canonical(" "));
    assertThrows(IllegalArgumentException.class, () -> KubeconfigGuard.parse(" "));
  }

  @Test
  void theFieldsLeftOutOfAUserAreListed() {
    Config model = KubeconfigGuard.parse(WITH_EVERYTHING);
    assertEquals(List.of("exec"), KubeconfigGuard.leftOut(model.getUsers().get(0).getUser()));
    assertEquals(List.of("tokenFile", "client-certificate", "client-key"),
        KubeconfigGuard.leftOut(model.getUsers().get(3).getUser()));
    assertEquals(List.of(), KubeconfigGuard.leftOut(model.getUsers().get(4).getUser()));
    assertEquals(List.of(), KubeconfigGuard.leftOut(null));
  }
}
