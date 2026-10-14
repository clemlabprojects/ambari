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
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The in-cluster connection mode is saved for the view instance like the other modes: every user and every background
 * task then connects with the Ambari pod's ServiceAccount, until an administrator uploads a kubeconfig or saves an
 * OpenShift login.
 */
class InClusterConnectionModeTest {

  @TempDir
  Path workDir;
  @TempDir
  Path mount;

  PerUserViewContext view;
  InClusterServiceAccount pod;

  /** Two contexts, the second selected in KDPS: connecting with the upload targets it. */
  static final String UPLOADED = String.join("\n",
      "apiVersion: v1",
      "kind: Config",
      "current-context: dev",
      "clusters:",
      "- name: dev",
      "  cluster: {server: 'https://dev:6443'}",
      "- name: prod",
      "  cluster: {server: 'https://prod:6443'}",
      "contexts:",
      "- name: dev",
      "  context: {cluster: dev, user: u}",
      "- name: prod",
      "  context: {cluster: prod, user: u}",
      "users:",
      "- name: u",
      "  user: {token: uploaded-token}",
      "");

  @BeforeEach
  void setUp() throws Exception {
    view = new PerUserViewContext("in-cluster-test", workDir);
    Files.writeString(mount.resolve("token"), InClusterServiceAccountTest.boundToken("a"));
    Files.writeString(mount.resolve("ca.crt"), InClusterServiceAccountTest.CA_PEM);
    Files.writeString(mount.resolve("namespace"), "kdps");
    pod = new InClusterServiceAccount(mount,
        Map.of("KUBERNETES_SERVICE_HOST", "172.30.0.1", "KUBERNETES_SERVICE_PORT", "443")::get);
  }

  private ViewConfigurationService config(String user) {
    view.as(user);
    return new ViewConfigurationService(view.context, pod);
  }

  private void uploadKubeconfigAndSelectProd(String user) throws Exception {
    ViewConfigurationService admin = config(user);
    admin.saveKubeconfigFile(new ByteArrayInputStream(UPLOADED.getBytes(StandardCharsets.UTF_8)), "kubeconfig.yaml");
    admin.saveSelectedContext("prod");
  }

  @Test
  void theModeIsSavedForEveryUserOfTheInstance() {
    assertFalse(config("ops1").isConfigured());

    config("alice").saveInClusterServiceAccount();

    for (String user : new String[] {"alice", "ops1", null}) {
      ViewConfigurationService seen = config(user);
      assertTrue(seen.isConfigured(), "configured for " + user);
      assertTrue(seen.isInClusterServiceAccount(), "in-cluster for " + user);
      assertEquals(ViewConfigurationService.AUTH_MODE_IN_CLUSTER, seen.getAuthMode());
      assertFalse(seen.usesUploadedKubeconfig());
      assertFalse(seen.isOpenShiftLogin());
    }
    InstanceSettings shared = new InstanceSettings(view.context);
    assertEquals("in-cluster", shared.get("auth.mode"));
    assertEquals("true", shared.get(InstanceSettings.CONFIGURED_KEY));
    assertTrue(view.instanceDataOf("alice").isEmpty(), "nothing kept in the administrator's own data");
  }

  @Test
  void consumersGetAKubeconfigOfThePodServiceAccount() throws Exception {
    // A context selected for an earlier upload is not looked for in the pod's kubeconfig.
    uploadKubeconfigAndSelectProd("alice");
    config("alice").saveInClusterServiceAccount();

    ViewConfigurationService seen = config("ops1");
    assertNull(seen.getContextToConnectWith());
    assertEquals("prod", seen.getSelectedContext(), "kept for when the upload is used again");
    Config connected = Config.fromKubeconfig(seen.getKubeconfigContents());
    assertEquals("https://172.30.0.1:443/", connected.getMasterUrl());
    assertEquals(InClusterServiceAccountTest.boundToken("a"), connected.getAutoOAuthToken());
    assertEquals("kdps", connected.getNamespace());

    Files.writeString(mount.resolve("token"), InClusterServiceAccountTest.boundToken("renewed"));
    assertEquals(InClusterServiceAccountTest.boundToken("renewed"),
        Config.fromKubeconfig(seen.getKubeconfigContents()).getAutoOAuthToken(), "written again with the renewed token");
  }

  @Test
  void uploadingAKubeconfigLeavesTheInClusterMode() throws Exception {
    config("alice").saveInClusterServiceAccount();

    uploadKubeconfigAndSelectProd("alice");

    ViewConfigurationService seen = config("ops1");
    assertEquals(ViewConfigurationService.AUTH_MODE_KUBECONFIG, seen.getAuthMode());
    assertTrue(seen.usesUploadedKubeconfig());
    assertEquals("prod", seen.getContextToConnectWith());
    Config connected = Config.fromKubeconfig(seen.getKubeconfigContents());
    assertEquals("https://prod:6443/", connected.getMasterUrl());
    assertEquals("uploaded-token", connected.getAutoOAuthToken());
  }

  @Test
  void savingAnOpenShiftLoginLeavesTheInClusterMode() {
    config("alice").saveInClusterServiceAccount();

    config("alice").saveOpenShiftLogin("https://api.ocp:6443", "kdps-admin", "secret", null, true);

    ViewConfigurationService seen = config("ops1");
    assertEquals(ViewConfigurationService.AUTH_MODE_OPENSHIFT_LOGIN, seen.getAuthMode());
    assertFalse(seen.isInClusterServiceAccount());
    assertTrue(seen.isOpenShiftLogin());
  }

  @Test
  void theAuthModeSwitchSelectsTheInClusterModeLikeItsOwnSave() throws Exception {
    uploadKubeconfigAndSelectProd("alice");

    config("alice").setAuthMode("in-cluster");
    assertTrue(config("ops1").isInClusterServiceAccount());
    assertTrue(config("ops1").isConfigured());

    config("alice").setAuthMode("kubeconfig");
    assertTrue(config("ops1").usesUploadedKubeconfig());
    assertEquals("https://prod:6443/", Config.fromKubeconfig(config("ops1").getKubeconfigContents()).getMasterUrl(),
        "the upload is used again, with its selected context, without a new upload");

    config("alice").setAuthMode("something-else");
    assertEquals(ViewConfigurationService.AUTH_MODE_KUBECONFIG, config("ops1").getAuthMode());
  }

  @Test
  void aKubeconfigFileLostSinceAnEarlierUploadDoesNotUnconfigureTheInClusterMode() throws Exception {
    uploadKubeconfigAndSelectProd("alice");
    config("alice").saveInClusterServiceAccount();
    Files.delete(Path.of(config("alice").getKubeconfigPath()));

    // Building the service checks the uploaded file only when the view connects with it.
    assertTrue(config("ops1").isConfigured());
    assertTrue(config(null).isInClusterServiceAccount());
  }

  @Test
  void aLostKubeconfigFileStillUnconfiguresAViewThatConnectsWithIt() throws Exception {
    uploadKubeconfigAndSelectProd("alice");
    Files.delete(Path.of(config("alice").getKubeconfigPath()));

    assertFalse(config("ops1").isConfigured());
  }
}
