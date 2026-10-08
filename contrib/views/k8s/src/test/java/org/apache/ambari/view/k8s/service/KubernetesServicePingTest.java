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

import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.ConnectionHealth;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The connection check used after a new kubeconfig or context: a 403 means connected, unless it is anonymous. */
@EnableKubernetesMockClient
class KubernetesServicePingTest {

  KubernetesMockServer server;
  KubernetesClient client;
  KubernetesService svc;

  @BeforeEach
  void setUp() {
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getInstanceName()).thenReturn("ping-test");
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
    svc = new KubernetesService(ctx, client, mock(HelmClient.class), /*isConfigured=*/true);
  }

  private void namespacesAnswer403(String user) {
    server.expect().get().withPath("/api/v1/namespaces?limit=1").andReturn(403, new StatusBuilder()
        .withCode(403).withReason("Forbidden")
        .withMessage("namespaces is forbidden: User \"" + user + "\" cannot list resource \"namespaces\" at the cluster scope")
        .build()).once();
  }

  @Test
  void projectLimitedAccountIsConnected() {
    namespacesAnswer403("system:serviceaccount:team-a:kdps");
    assertEquals(ConnectionHealth.State.CONNECTED, svc.pingCluster().getState());
  }

  @Test
  void anonymousRequestIsNotConnected() {
    namespacesAnswer403("system:anonymous");
    assertEquals(ConnectionHealth.State.UNAUTHENTICATED, svc.pingCluster().getState());
  }
}
