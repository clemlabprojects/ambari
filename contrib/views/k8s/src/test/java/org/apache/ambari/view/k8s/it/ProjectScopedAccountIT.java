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

package org.apache.ambari.view.k8s.it;

import com.marcnuri.helm.Release;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.authentication.TokenRequestBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.http.BasicBuilder;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.Interceptor;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.HelmService;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.NamespaceScope;
import org.apache.ambari.view.k8s.service.helm.HelmClientDefault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * KDPS against a real Kubernetes API server with an account limited to its own namespace: a ServiceAccount bound to
 * the built-in {@code admin} ClusterRole through a RoleBinding in that namespace only, the Kubernetes counterpart of
 * an OpenShift account that holds rights in its own projects. The unit tests script the 403 answers; here the
 * cluster's own authorizer decides, so a new cluster-scoped call (by KDPS or by Helm) fails these tests.
 *
 * <p>Only Kubernetes RBAC is covered. The OpenShift-only behaviour (Project API listing, SCCs, user-workload
 * monitoring) is checked by hand on OpenShift: see {@code docs/RELEASE_CHECKLIST.md}.
 *
 * <p>Run with {@code mvn -Pintegration verify}; skipped when Docker is not available and no cluster is named (see
 * {@link LiveClusterExtension}).
 */
@Tag("livecluster")
@ExtendWith(LiveClusterExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectScopedAccountIT {

  private static final String ACCOUNT = "kdps";
  private static final String RELEASE = "probe";

  private final String namespace = "kdps-it-" + UUID.randomUUID().toString().substring(0, 8);
  /** "METHOD path" of every request KDPS sends with the restricted account. */
  private final List<String> requests = new CopyOnWriteArrayList<>();

  private KubernetesClient admin;
  private KubernetesClient restricted;
  private String kubeconfig;
  private KubernetesService kubernetes;
  private HelmService helm;

  @BeforeAll
  void setUpRestrictedAccount(KubernetesClient admin, @TempDir Path workDir) throws Exception {
    this.admin = admin;
    admin.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build())
        .create();
    admin.serviceAccounts().inNamespace(namespace)
        .resource(new ServiceAccountBuilder().withNewMetadata().withName(ACCOUNT).endMetadata().build()).create();
    admin.rbac().roleBindings().inNamespace(namespace).resource(new RoleBindingBuilder()
        .withNewMetadata().withName(ACCOUNT + "-admin").endMetadata()
        .withNewRoleRef().withApiGroup("rbac.authorization.k8s.io").withKind("ClusterRole").withName("admin").endRoleRef()
        .addNewSubject().withKind("ServiceAccount").withName(ACCOUNT).withNamespace(namespace).endSubject()
        .build()).create();
    String token = admin.serviceAccounts().inNamespace(namespace).withName(ACCOUNT)
        .tokenRequest(new TokenRequestBuilder().withNewSpec().withExpirationSeconds(3600L).endSpec().build())
        .getStatus().getToken();

    kubeconfig = restrictedKubeconfig(admin.getConfiguration(), token);
    restricted = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(kubeconfig))
        .withHttpClientBuilderConsumer(b -> b.addOrReplaceInterceptor("kdps-it-requests", new Interceptor() {
          @Override
          public void before(BasicBuilder builder, HttpRequest request, RequestTags tags) {
            requests.add(request.method() + " " + request.uri().getPath());
          }
        }))
        .build();

    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getInstanceName()).thenReturn("project-scoped-it");
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", workDir.toString()));
    kubernetes = new KubernetesService(ctx, restricted, new HelmClientDefault(), /*isConfigured=*/true);
    helm = new HelmService(ctx, new HelmClientDefault(), kubernetes::namespaceScope);
  }

  @AfterAll
  void removeNamespace() {
    if (restricted != null) {
      restricted.close();
    }
    if (admin != null) {
      admin.namespaces().withName(namespace).delete();
    }
  }

  /** A kubeconfig for the restricted account, on the same cluster as the admin client, set to its namespace. */
  private String restrictedKubeconfig(Config cluster, String token) throws Exception {
    Map<String, Object> clusterEntry = new java.util.LinkedHashMap<>();
    clusterEntry.put("server", cluster.getMasterUrl());
    if (cluster.getCaCertData() != null) {
      clusterEntry.put("certificate-authority-data", cluster.getCaCertData());
    } else if (cluster.getCaCertFile() != null) {
      clusterEntry.put("certificate-authority-data",
          Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(cluster.getCaCertFile()))));
    } else if (cluster.isTrustCerts()) {
      clusterEntry.put("insecure-skip-tls-verify", true);
    }
    return Serialization.asYaml(Map.of(
        "apiVersion", "v1",
        "kind", "Config",
        "clusters", List.of(Map.of("name", "it", "cluster", clusterEntry)),
        "users", List.of(Map.of("name", ACCOUNT, "user", Map.of("token", token))),
        "contexts", List.of(Map.of("name", ACCOUNT, "context",
            Map.of("cluster", "it", "user", ACCOUNT, "namespace", namespace))),
        "current-context", ACCOUNT));
  }

  /**
   * The account may read the cluster (it is refused, then KDPS falls back), but every change it makes is inside its
   * namespace: a write anywhere else would need a right the account lacks.
   */
  private void assertNothingChangedOutsideTheNamespace() {
    assertFalse(requests.isEmpty(), "the account's requests are recorded");
    List<String> outside = requests.stream()
        .filter(r -> !r.startsWith("GET "))
        .filter(r -> !r.contains("/namespaces/" + namespace + "/"))
        .collect(Collectors.toList());
    assertEquals(List.of(), outside, "requests that change something outside " + namespace);
  }

  @Test
  void listingsFallBackToTheAccountsNamespaceOnARealRefusal() {
    restricted.configMaps().inNamespace(namespace).resource(new ConfigMapBuilder()
        .withNewMetadata().withName("listed").endMetadata().addToData("k", "v").build()).create();

    assertEquals(List.of(namespace),
        kubernetes.listNamespaces().stream().map(n -> n.name).collect(Collectors.toList()),
        "the namespace of the account's kubeconfig");
    assertTrue(kubernetes.namespaceScope().isRefused(NamespaceScope.Resource.NAMESPACES));

    assertTrue(kubernetes.listConfigMaps(null).stream()
            .anyMatch(r -> namespace.equals(r.get("namespace")) && "listed".equals(r.get("name"))),
        "config maps listed namespace by namespace");
    assertTrue(kubernetes.namespaceScope().isRefused(NamespaceScope.Resource.CONFIGMAPS));

    assertNotNull(kubernetes.getClusterEvents());
    assertTrue(kubernetes.namespaceScope().isRefused(NamespaceScope.Resource.EVENTS));

    assertEquals(List.of(), kubernetes.listDeployments(null));
    assertTrue(kubernetes.namespaceScope().isRefused(NamespaceScope.Resource.DEPLOYMENTS));

    assertNothingChangedOutsideTheNamespace();
  }

  @Test
  void helmInstallsUpgradesListsAndUninstallsInsideTheNamespace() throws Exception {
    String chart = Path.of(getClass().getResource("/charts/kdps-it-probe").toURI()).toString();

    Release installed = helm.deployOrUpgrade(chart, RELEASE, namespace, Map.of("message", "first"), Map.of(),
        kubeconfig, null, null, 120, true, false, false);
    assertEquals("deployed", installed.getStatus());
    assertEquals("first", probeMessage());

    Release upgraded = helm.deployOrUpgrade(chart, RELEASE, namespace, Map.of("message", "second"), Map.of(),
        kubeconfig, null, null, 120, true, false, false);
    assertEquals("2", upgraded.getRevision(), "an existing release is upgraded, not installed again");
    assertEquals("second", probeMessage());

    assertEquals(List.of(RELEASE), releaseNames(helm.list(namespace, kubeconfig)));
    List<Release> everywhere = helm.list(null, kubeconfig);
    assertEquals(List.of(RELEASE), releaseNames(everywhere), "all namespaces, walked one by one");
    assertEquals(namespace, everywhere.get(0).getNamespace());
    assertTrue(kubernetes.namespaceScope().isRefused(NamespaceScope.Resource.SECRETS),
        "Helm's cluster-wide listing was refused by the cluster and recognised as such");

    helm.uninstall(namespace, RELEASE, kubeconfig);
    assertEquals(List.of(), releaseNames(helm.list(namespace, kubeconfig)));
    assertNull(admin.configMaps().inNamespace(namespace).withName(RELEASE + "-probe").get(), "chart objects removed");
    assertEquals(List.of(), admin.secrets().inNamespace(namespace)
        .withLabels(Map.of("owner", "helm", "name", RELEASE)).list().getItems(), "Helm's release records removed");

    assertNothingChangedOutsideTheNamespace();
  }

  private String probeMessage() {
    ConfigMap cm = restricted.configMaps().inNamespace(namespace).withName(RELEASE + "-probe").get();
    assertNotNull(cm, "the chart's ConfigMap");
    return cm.getData().get("message");
  }

  private static List<String> releaseNames(List<Release> releases) {
    return releases.stream().map(Release::getName).collect(Collectors.toList());
  }
}
