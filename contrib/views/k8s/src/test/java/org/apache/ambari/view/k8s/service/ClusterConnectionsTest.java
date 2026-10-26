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

import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.ambari.view.k8s.service.cluster.ClusterRef;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.KdpsSettingEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.utils.SelfSignedHttpsServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each cluster KDPS knows has its own connection: the default cluster's is the one the view had before (same settings,
 * same files), another cluster's is kept apart, protected as the default one is, and never used for another cluster.
 */
class ClusterConnectionsTest {

  @TempDir
  Path workDir;
  @TempDir
  Path mount;

  PerUserViewContext view;
  InClusterServiceAccount pod;

  static final ClusterRef PROD = ClusterRef.of("prod");

  static String kubeconfig(String server, String token) {
    return kubeconfig(server, token, InClusterServiceAccountTest.CA_PEM);
  }

  static String kubeconfig(String server, String token, String caPem) {
    return String.join("\n",
        "apiVersion: v1",
        "kind: Config",
        "current-context: c",
        "clusters:",
        "- name: c",
        "  cluster:",
        "    server: '" + server + "'",
        "    certificate-authority-data: "
            + Base64.getEncoder().encodeToString(caPem.getBytes(StandardCharsets.UTF_8)),
        "contexts:",
        "- name: c",
        "  context: {cluster: c, user: u}",
        "users:",
        "- name: u",
        "  user: {token: " + token + "}",
        "");
  }

  @BeforeEach
  void setUp() throws Exception {
    view = new PerUserViewContext("clusters-test", workDir).as("lead");
    Files.writeString(mount.resolve("token"), InClusterServiceAccountTest.boundToken("a"));
    Files.writeString(mount.resolve("ca.crt"), InClusterServiceAccountTest.CA_PEM);
    Files.writeString(mount.resolve("namespace"), "kdps");
    pod = new InClusterServiceAccount(mount,
        Map.of("KUBERNETES_SERVICE_HOST", "172.30.0.1", "KUBERNETES_SERVICE_PORT", "443")::get);
  }

  private ViewConfigurationService config(ClusterRef cluster) {
    return new ViewConfigurationService(view.context, pod, cluster);
  }

  private void upload(ClusterRef cluster, String server, String token) throws Exception {
    upload(cluster, kubeconfig(server, token));
  }

  private void upload(ClusterRef cluster, String kubeconfig) throws Exception {
    config(cluster).saveKubeconfigFile(
        new ByteArrayInputStream(kubeconfig.getBytes(StandardCharsets.UTF_8)), "kubeconfig.yaml");
  }

  /** Every setting of the view instance, by key. */
  private Map<String, String> allSettings() {
    return view.dataStore.findAll(KdpsSettingEntity.class, null).stream()
        .filter(e -> e.getValue() != null)
        .collect(Collectors.toMap(KdpsSettingEntity::getId, KdpsSettingEntity::getValue));
  }

  @Test
  void theDefaultClusterKeepsTheSettingsAndFileItHadBefore() throws Exception {
    upload(ClusterRef.DEFAULT, "https://ambari-oc:6443", "default-token");

    InstanceSettings settings = new InstanceSettings(view.context);
    assertEquals("true", settings.get(InstanceSettings.CONFIGURED_KEY));
    assertEquals("kubeconfig", settings.get("auth.mode"));
    assertEquals(workDir.resolve("kubeconfig.yaml.enc").toString(), settings.get("kubeconfig.path"));
    assertTrue(allSettings().keySet().stream().noneMatch(k -> k.startsWith("cluster.")));
    assertTrue(new ViewConfigurationService(view.context, pod).isConfigured(), "read as before, without a cluster");
  }

  @Test
  void anotherClustersConnectionIsKeptApartAndEncrypted() throws Exception {
    upload(ClusterRef.DEFAULT, "https://ambari-oc:6443", "default-token");
    upload(PROD, "https://prod-oc:6443", "prod-token");
    config(ClusterRef.of("dev")).saveOpenShiftLogin("https://dev-oc:6443", "kdps", "dev-password", null, false);

    assertTrue(config(PROD).getKubeconfigContents().contains("prod-oc"));
    assertFalse(config(PROD).getKubeconfigContents().contains("ambari-oc"));
    assertTrue(config(ClusterRef.DEFAULT).getKubeconfigContents().contains("ambari-oc"));
    assertTrue(config(ClusterRef.of("dev")).isOpenShiftLogin());
    assertFalse(config(ClusterRef.DEFAULT).isOpenShiftLogin(), "the default cluster's mode is its own");

    Path prodFile = workDir.resolve("clusters").resolve("prod").resolve("kubeconfig.yaml.enc");
    assertEquals(prodFile.toString(), new InstanceSettings(view.context).get("cluster.prod.kubeconfig.path"));
    String onDisk = Files.readString(prodFile);
    assertFalse(onDisk.contains("prod-token") || onDisk.contains("prod-oc"), "the file is encrypted");
    Set<PosixFilePermission> ownerOnlyDir = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE);
    assertEquals(ownerOnlyDir, Files.getPosixFilePermissions(workDir.resolve("clusters")));
    assertEquals(ownerOnlyDir, Files.getPosixFilePermissions(prodFile.getParent()));
    assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(prodFile));
    assertTrue(allSettings().values().stream().noneMatch(v -> v.contains("dev-password")),
        "the OpenShift password is not kept in clear");
  }

  @Test
  void theOutboundProxyIsTheViewsWhateverTheCluster() {
    config(PROD).saveProxySettings(true, "http://proxy:3128", "", null, "");

    assertTrue(config(ClusterRef.DEFAULT).isProxyEnabled());
    assertEquals("http://proxy:3128", new InstanceSettings(view.context).get("proxy.url"));
  }

  @Test
  void aRemovedClustersConnectionIsForgotten() throws Exception {
    upload(ClusterRef.DEFAULT, "https://ambari-oc:6443", "default-token");
    upload(PROD, "https://prod-oc:6443", "prod-token");
    config(PROD).saveSelectedContext("c");

    config(PROD).forgetConnection();

    assertFalse(config(PROD).isConfigured());
    assertTrue(allSettings().keySet().stream().noneMatch(k -> k.startsWith("cluster.prod.")));
    assertFalse(Files.exists(workDir.resolve("clusters").resolve("prod")));
    assertTrue(config(ClusterRef.DEFAULT).isConfigured(), "the default cluster's connection is untouched");
    assertThrows(IllegalStateException.class, () -> config(ClusterRef.DEFAULT).forgetConnection());
  }

  @Test
  void eachClusterHasItsOwnServiceAndClient() throws Exception {
    // Built before anything is saved, so that it does not try to connect.
    KubernetesService defaultCluster = new KubernetesService(view.context, ClusterRef.DEFAULT);
    KubernetesService prod = new KubernetesService(view.context, PROD);
    String ca;
    try (SelfSignedHttpsServer server = new SelfSignedHttpsServer(false, "localhost")) {
      ca = server.certificatePem();
    }
    upload(ClusterRef.DEFAULT, kubeconfig("https://ambari-oc:6443", "default-token", ca));
    upload(PROD, kubeconfig("https://prod-oc:6443", "prod-token", ca));

    SSLContext before = SSLContext.getDefault();
    try (KubernetesClient prodClient = prod.buildClientFromSavedConfiguration()) {
      assertEquals("https://prod-oc:6443/", prodClient.getConfiguration().getMasterUrl());
      assertEquals("prod-token", prodClient.getConfiguration().getAutoOAuthToken());
      assertSame(before, SSLContext.getDefault(), "another cluster's CA is not trusted by the whole server");
    }
    try (KubernetesClient defaultClient = defaultCluster.buildClientFromSavedConfiguration()) {
      assertEquals("https://ambari-oc:6443/", defaultClient.getConfiguration().getMasterUrl());
      assertEquals("default-token", defaultClient.getConfiguration().getAutoOAuthToken());
      assertNotSame(before, SSLContext.getDefault(), "the default cluster's CA is, as before");
    } finally {
      SSLContext.setDefault(before);
    }
    assertEquals(PROD, prod.cluster());
    assertEquals(ClusterRef.DEFAULT, defaultCluster.cluster());
  }

  @Test
  void theSharedServiceIsOnePerClusterAndForgottenWithIt() {
    KubernetesService prod = KubernetesService.get(view.context, PROD);
    try {
      assertSame(prod, KubernetesService.get(view.context, PROD));
      assertNotSame(prod, KubernetesService.get(view.context, ClusterRef.of("dev")));

      KubernetesService.forget(view.context, PROD);

      assertNotSame(prod, KubernetesService.get(view.context, PROD));
    } finally {
      KubernetesService.forget(view.context, PROD);
      KubernetesService.forget(view.context, ClusterRef.of("dev"));
    }
  }

  @Test
  void nothingOfAClusterIsWrittenOutsideItsFolder() throws Exception {
    upload(PROD, "https://prod-oc:6443", "prod-token");
    config(ClusterRef.of("dev")).saveOpenShiftLogin("https://dev-oc:6443", "kdps", "pw", InClusterServiceAccountTest.CA_PEM,
        false);

    try (Stream<Path> files = Files.walk(workDir)) {
      Set<Path> written = files.filter(Files::isRegularFile).map(workDir::relativize).collect(Collectors.toSet());
      assertEquals(Set.of(Path.of("clusters/prod/kubeconfig.yaml.enc"), Path.of("clusters/dev/openshift-ca.pem")),
          written.stream().filter(p -> !p.startsWith("helm-native")).collect(Collectors.toSet()));
    }
  }
}
