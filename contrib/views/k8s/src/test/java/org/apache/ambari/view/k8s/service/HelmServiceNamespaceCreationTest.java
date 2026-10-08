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

import com.marcnuri.helm.Release;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Helm is asked to create the release namespace only when it does not exist: an account limited to its own
 * (pre-provisioned) projects is refused a namespace creation even for an existing namespace.
 */
@EnableKubernetesMockClient
class HelmServiceNamespaceCreationTest {

  KubernetesMockServer server;
  KubernetesClient client;
  NamespaceScope scope;
  HelmClient helm;
  HelmService service;

  @BeforeEach
  void setUp() {
    scope = new NamespaceScope(() -> client, () -> true, List::of, NamespaceScope::runOnce, System::currentTimeMillis);
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
    helm = mock(HelmClient.class);
    service = new HelmService(ctx, helm, () -> scope);
  }

  private void namespaceAnswers(int code) {
    if (code == 200) {
      server.expect().get().withPath("/api/v1/namespaces/apps").andReturn(200,
          new NamespaceBuilder().withNewMetadata().withName("apps").endMetadata().build()).always();
    } else {
      server.expect().get().withPath("/api/v1/namespaces/apps").andReturn(code,
          new StatusBuilder().withCode(code).withMessage("namespaces \"apps\" is forbidden or missing").build()).always();
    }
  }

  @Test
  void namespaceCheckSaysWhetherItMustBeCreated() {
    namespaceAnswers(200);
    assertFalse(scope.namespaceMissing("apps"), "exists");
  }

  @Test
  void aMissingNamespaceMustBeCreated() {
    namespaceAnswers(404);
    assertTrue(scope.namespaceMissing("apps"));
  }

  @Test
  void aNamespaceTheAccountMayNotReadIsPreProvisioned() {
    namespaceAnswers(403);
    assertFalse(scope.namespaceMissing("apps"));
  }

  @Test
  void anUnexpectedAnswerKeepsThePreviousBehaviour() {
    namespaceAnswers(400);
    assertTrue(scope.namespaceMissing("apps"));
  }

  private boolean createNamespacePassedToHelm() throws Exception {
    HelmDeployRequest req = new HelmDeployRequest();
    req.setChart("prometheus");
    req.setReleaseName("prom");
    req.setNamespace("apps");
    req.setValues(Map.of("replicaCount", 1));
    when(helm.list("apps", "KC", false)).thenReturn(List.of());
    when(helm.install(anyString(), any(), anyBoolean(), anyString(), anyString(), any(Path.class), anyString(),
        ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean()))
        .thenReturn(mock(Release.class));

    service.deployOrUpgrade(req, "KC", null, null);

    var created = org.mockito.ArgumentCaptor.forClass(Boolean.class);
    verify(helm).install(anyString(), any(), anyBoolean(), eq("prom"), eq("apps"), any(Path.class), eq("KC"),
        ArgumentMatchers.<Map<String, Object>>any(), anyInt(), created.capture(), anyBoolean(), anyBoolean(), anyBoolean());
    return created.getValue();
  }

  @Test
  void installIntoAnExistingProjectDoesNotAskHelmToCreateIt() throws Exception {
    namespaceAnswers(403); // pre-provisioned project the account may not read
    assertFalse(createNamespacePassedToHelm());
  }

  @Test
  void installIntoAMissingNamespaceStillCreatesIt() throws Exception {
    namespaceAnswers(404);
    assertTrue(createNamespacePassedToHelm());
  }
}
