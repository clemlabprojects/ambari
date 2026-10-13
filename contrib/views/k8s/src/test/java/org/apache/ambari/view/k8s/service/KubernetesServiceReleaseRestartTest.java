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

import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Restarting the pods of a release, as kubectl rollout restart does, after a keytab or client secret they read at
 * startup changed; and finding the keytab Secrets an earlier version of the view wrote without labels.
 */
@EnableKubernetesMockClient(crud = true)
class KubernetesServiceReleaseRestartTest {

  private static final String NS = "analytics";

  KubernetesMockServer server;
  KubernetesClient client;
  KubernetesService svc;

  @BeforeEach
  void setUp() {
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getInstanceName()).thenReturn("restart-test");
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
    svc = new KubernetesService(ctx, client, mock(HelmClient.class), /*isConfigured=*/true);
  }

  private static PodTemplateSpec template() {
    return new PodTemplateSpecBuilder().withNewMetadata().addToLabels("app", "x").endMetadata()
        .withNewSpec().addNewContainer().withName("main").withImage("busybox").endContainer().endSpec().build();
  }

  private void deployment(String name, Map<String, String> annotations, Map<String, String> labels) {
    client.apps().deployments().inNamespace(NS).resource(new DeploymentBuilder()
        .withNewMetadata().withName(name).withNamespace(NS).withAnnotations(annotations).withLabels(labels).endMetadata()
        .withNewSpec().withTemplate(template()).endSpec().build()).create();
  }

  private String restartedAt(Deployment deployment) {
    Map<String, String> annotations = deployment.getSpec().getTemplate().getMetadata().getAnnotations();
    return annotations == null ? null : annotations.get(KubernetesService.RESTARTED_AT_ANNOTATION);
  }

  @Test
  void theWorkloadsOfTheReleaseAreRestartedAndNoOthers() {
    deployment("trino-coordinator", Map.of("meta.helm.sh/release-name", "trino",
        "meta.helm.sh/release-namespace", NS), Map.of());
    deployment("trino-etl-coordinator", Map.of("meta.helm.sh/release-name", "trino-etl",
        "meta.helm.sh/release-namespace", NS), Map.of("app.kubernetes.io/instance", "trino"));
    client.apps().statefulSets().inNamespace(NS).resource(new StatefulSetBuilder()
        .withNewMetadata().withName("trino-worker").withNamespace(NS)
        .addToLabels("app.kubernetes.io/instance", "trino").endMetadata()
        .withNewSpec().withServiceName("trino-worker").withTemplate(template()).endSpec().build()).create();

    List<String> restarted = svc.restartReleaseWorkloads(NS, "trino");

    assertEquals(List.of("Deployment trino-coordinator", "StatefulSet trino-worker"), restarted);
    assertNotNull(restartedAt(client.apps().deployments().inNamespace(NS).withName("trino-coordinator").get()));
    assertNull(restartedAt(client.apps().deployments().inNamespace(NS).withName("trino-etl-coordinator").get()),
        "the Helm annotation names another release");
    assertNotNull(client.apps().statefulSets().inNamespace(NS).withName("trino-worker").get()
        .getSpec().getTemplate().getMetadata().getAnnotations().get(KubernetesService.RESTARTED_AT_ANNOTATION));
  }

  @Test
  void aReleaseWithoutWorkloadsRestartsNothing() {
    assertTrue(svc.restartReleaseWorkloads(NS, "superset").isEmpty());
  }

  private void secret(String name, Map<String, String> annotations, Map<String, String> labels) {
    Secret secret = new SecretBuilder().withNewMetadata().withName(name).withNamespace(NS)
        .withAnnotations(annotations).withLabels(labels).endMetadata()
        .withData(Map.of("service.keytab", "AAAA")).build();
    client.secrets().inNamespace(NS).resource(secret).create();
  }

  @Test
  void theUnlabelledSecretsOfTheViewAreListedWithoutHelmHistory() {
    secret("trino-keytab", Map.of("managed-by", "ambari-k8s-view"), Map.of());
    secret("trino-etl-keytab", Map.of("managed-by", "ambari-k8s-view"),
        Map.of(CommandService.KEYTAB_PRINCIPAL_LABEL, "abc"));
    secret("sh.helm.release.v1.trino.v1", Map.of("managed-by", "ambari-k8s-view"), Map.of("owner", "helm"));
    secret("operator-secret", Map.of(), Map.of());

    List<String> names = svc.listViewManagedSecretsWithoutLabel(NS, CommandService.KEYTAB_PRINCIPAL_LABEL).stream()
        .map(s -> s.getMetadata().getName()).toList();

    assertEquals(List.of("trino-keytab"), names);
  }
}
