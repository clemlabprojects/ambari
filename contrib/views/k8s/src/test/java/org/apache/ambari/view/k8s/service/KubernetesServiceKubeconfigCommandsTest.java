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

import io.fabric8.kubernetes.client.Config;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Kubernetes client runs the credential plugin of the kubeconfig's user while it builds its configuration. A
 * kubeconfig must not be able to start a command on the Ambari server that way.
 */
class KubernetesServiceKubeconfigCommandsTest {

  @TempDir
  Path dir;

  /** A kubeconfig whose user's credential plugin creates {@code marker} when it runs. */
  private String kubeconfigRunning(Path marker) {
    return String.join("\n",
        "apiVersion: v1",
        "kind: Config",
        "current-context: a",
        "clusters:",
        "- name: a",
        "  cluster: {server: 'https://127.0.0.1:6443', insecure-skip-tls-verify: true}",
        "contexts:",
        "- name: a",
        "  context: {cluster: a, user: u}",
        "users:",
        "- name: u",
        "  user:",
        "    exec:",
        "      apiVersion: client.authentication.k8s.io/v1beta1",
        "      command: /bin/sh",
        "      args: ['-c', 'touch " + marker + "; echo \"{\\\"apiVersion\\\":\\\"client.authentication.k8s.io/v1beta1\\\","
            + "\\\"kind\\\":\\\"ExecCredential\\\",\\\"status\\\":{\\\"token\\\":\\\"from-plugin\\\"}}\"']",
        "      interactiveMode: Never",
        "");
  }

  @Test
  void theUnguardedClientConfigRunsTheCommand() {
    Path marker = dir.resolve("ran-unguarded");
    Config.fromKubeconfig(kubeconfigRunning(marker));

    assertTrue(Files.exists(marker), "the premise: building a client Config from this file runs its command");
  }

  @Test
  void theClientConfigOfKdpsDoesNotRunItAndHasNoPluginToken() {
    Path marker = dir.resolve("ran-guarded");
    Config config = KubernetesService.buildConfig(kubeconfigRunning(marker), null);

    assertFalse(Files.exists(marker), "the command was not run");
    assertNull(config.getOauthToken(), "no token came from the plugin");
    assertNull(config.getAutoOAuthToken(), "no token came from the plugin");
    assertEquals("https://127.0.0.1:6443/", config.getMasterUrl());
  }

  @Test
  void theSelectedContextIsGuardedToo() {
    Path marker = dir.resolve("ran-selected");
    KubernetesService.buildConfig(kubeconfigRunning(marker), "a");

    assertFalse(Files.exists(marker));
  }

  @Test
  void aCommandHiddenBehindAnAliasMergeKeyOrTagIsNeverRun() {
    for (String user : new String[] {"*e", "{<<: *e}"}) {
      Path marker = dir.resolve("ran-" + Math.abs(user.hashCode()));
      String crafted = kubeconfigRunning(marker);
      String execBlock = crafted.substring(crafted.indexOf("    exec:"));
      String hidden = "x-hidden: &e\n  " + execBlock.trim().replace("\n      ", "\n    ") + "\n";
      String viaAnchor = crafted.substring(0, crafted.indexOf("users:")) + hidden + "users:\n- name: u\n  user: " + user + "\n";

      Config config = KubernetesService.buildConfig(viaAnchor, null);
      assertFalse(Files.exists(marker), "the command was not run: " + viaAnchor);
      assertNull(config.getAutoOAuthToken(), "no token came from the plugin");
    }
    // base64 of "users" and "user", which Go's YAML decodes in a key
    for (String key : new String[] {"!!binary dXNlcnM=:\n- name: u\n  user:", "users:\n- name: u\n  !!binary dXNlcg==:"}) {
      Path marker = dir.resolve("ran-" + Math.abs(key.hashCode()));
      String crafted = kubeconfigRunning(marker).replace("users:\n- name: u\n  user:", key);
      assertTrue(crafted.contains("!!binary"), crafted);

      KubernetesService.buildConfig(crafted, null);
      assertFalse(Files.exists(marker), "the command was not run: " + crafted);
    }
  }

  @Test
  void aUserWhoseOnlyCredentialIsACommandIsToldSo() {
    String saved = kubeconfigRunning(dir.resolve("never"));
    Config stripped = KubernetesService.buildConfig(saved, null);
    var model = org.apache.ambari.view.k8s.utils.KubeconfigGuard.parse(saved);

    IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> KubernetesService.requireCredentialOtherThanACommand(model, null, stripped));
    assertTrue(e.getMessage().contains("User 'u'") && e.getMessage().contains("(exec)"), e.getMessage());
    assertTrue(e.getMessage().contains("token or a client certificate"), e.getMessage());
  }

  @Test
  void aUserWithATokenBesideTheCommandKeepsConnecting() {
    String saved = kubeconfigRunning(dir.resolve("never")).replace("    exec:", "    token: t\n    exec:");
    Config stripped = KubernetesService.buildConfig(saved, null);
    var model = org.apache.ambari.view.k8s.utils.KubeconfigGuard.parse(saved);

    assertEquals("t", stripped.getAutoOAuthToken() != null ? stripped.getAutoOAuthToken() : stripped.getOauthToken());
    assertDoesNotThrow(() -> KubernetesService.requireCredentialOtherThanACommand(model, null, stripped));
  }

  @Test
  void aUserWhoseOnlyCredentialIsAFileIsToldSo() {
    for (String field : new String[] {"tokenFile", "client-key"}) {
      String saved = kubeconfigRunning(dir.resolve("never")).replaceAll("(?s)    exec:.*interactiveMode: Never\n",
          "    " + field + ": /etc/ambari-server/conf/password.dat\n");
      Config stripped = KubernetesService.buildConfig(saved, null);
      var model = org.apache.ambari.view.k8s.utils.KubeconfigGuard.parse(saved);

      IllegalStateException e = assertThrows(IllegalStateException.class,
          () -> KubernetesService.requireCredentialOtherThanACommand(model, null, stripped));
      assertTrue(e.getMessage().contains("(" + field + ")"), e.getMessage());
    }
  }

  @Test
  void aClientCertificateWhoseKeyWasAFileIsToldSo() {
    String saved = kubeconfigRunning(dir.resolve("never")).replaceAll("(?s)    exec:.*interactiveMode: Never\n",
        "    client-certificate-data: Y2VydA==\n    client-key: /etc/ambari-server/keys/ca.key\n");
    Config stripped = KubernetesService.buildConfig(saved, null);
    var model = org.apache.ambari.view.k8s.utils.KubeconfigGuard.parse(saved);

    IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> KubernetesService.requireCredentialOtherThanACommand(model, null, stripped));
    assertTrue(e.getMessage().contains("User 'u'") && e.getMessage().contains("(client-key)"), e.getMessage());
    assertTrue(e.getMessage().contains("client-key-data"), "says what to use instead: " + e.getMessage());
  }

  @Test
  void aClientCertificateWithItsKeyKeepsConnecting() {
    String saved = kubeconfigRunning(dir.resolve("never")).replaceAll("(?s)    exec:.*interactiveMode: Never\n",
        "    client-certificate-data: Y2VydA==\n    client-key-data: a2V5\n");
    Config stripped = KubernetesService.buildConfig(saved, null);
    var model = org.apache.ambari.view.k8s.utils.KubeconfigGuard.parse(saved);

    assertDoesNotThrow(() -> KubernetesService.requireCredentialOtherThanACommand(model, null, stripped));
  }

  /** The service of a view instance whose kubeconfig, saved before uploads were checked, is {@code saved}. */
  private KubernetesService serviceWithSavedKubeconfig(PerUserViewContext view, String saved) throws Exception {
    view.as("admin");
    new ViewConfigurationService(view.context).saveKubeconfigFile(
        new ByteArrayInputStream(saved.getBytes(StandardCharsets.UTF_8)), "kubeconfig.yaml");
    view.as("ops1");
    KubernetesService svc = new KubernetesService(view.context);
    svc.retiredClientGraceSeconds = 0;
    return svc;
  }

  @Test
  void anOperatorIsToldWhyASavedKubeconfigWhoseUserRunsACommandCannotConnect() throws Exception {
    Path marker = dir.resolve("ran-saved");
    PerUserViewContext view = new PerUserViewContext("saved-exec", dir.resolve("work"));
    KubernetesService svc = serviceWithSavedKubeconfig(view, kubeconfigRunning(marker));

    KubernetesService.NotConnectedException e = assertThrows(KubernetesService.NotConnectedException.class,
        () -> svc.getNodes(1, 0));
    assertTrue(e.getMessage().contains("User 'u'") && e.getMessage().contains("(exec)"), e.getMessage());
    assertTrue(e.getMessage().contains("Upload a new kubeconfig"), e.getMessage());
    assertFalse(e.getMessage().contains("tries again"), "not a cluster that cannot be reached for now: " + e.getMessage());

    var health = svc.pingCluster();
    assertFalse(health.isConnected());
    assertTrue(health.getMessage().contains("User 'u'") && health.getMessage().contains("(exec)"), health.getMessage());

    assertFalse(svc.forceReloadClient(), "a context chosen again cannot connect either");
    assertTrue(svc.unusableConfiguration().contains("User 'u'") && svc.unusableConfiguration().contains("(exec)"),
        svc.unusableConfiguration());
    assertFalse(Files.exists(marker), "the command was never run");
  }

  @Test
  void theReasonGoesOnceAUsableKubeconfigIsSaved() throws Exception {
    PerUserViewContext view = new PerUserViewContext("saved-exec-replaced", dir.resolve("work"));
    String withCommand = kubeconfigRunning(dir.resolve("never"));
    KubernetesService svc = serviceWithSavedKubeconfig(view, withCommand);
    assertNotNull(svc.unusableConfiguration());

    String withToken = withCommand.replaceAll("(?s)    exec:.*interactiveMode: Never\n", "    token: t\n");
    new ViewConfigurationService(view.context).saveKubeconfigFile(
        new ByteArrayInputStream(withToken.getBytes(StandardCharsets.UTF_8)), "kubeconfig.yaml");

    assertTrue(svc.forceReloadClient());
    assertNull(svc.unusableConfiguration());
  }

  @Test
  void anOperatorIsToldWhenTheSavedKubeconfigCannotBeRead() throws Exception {
    PerUserViewContext view = new PerUserViewContext("saved-unreadable", dir.resolve("work"));
    KubernetesService svc = serviceWithSavedKubeconfig(view, "users: [unclosed");

    KubernetesService.NotConnectedException e = assertThrows(KubernetesService.NotConnectedException.class,
        () -> svc.getNodes(1, 0));
    assertTrue(e.getMessage().contains("The kubeconfig cannot be read"), e.getMessage());
    assertTrue(e.getMessage().contains("Upload a new kubeconfig"), e.getMessage());
  }

  @Test
  void aSavedFileTheClientCannotReadStillFailsWithAReason() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> KubernetesService.buildConfig("users: [unclosed", null));
    assertTrue(e.getMessage().startsWith("The kubeconfig cannot be read"), e.getMessage());
  }
}
