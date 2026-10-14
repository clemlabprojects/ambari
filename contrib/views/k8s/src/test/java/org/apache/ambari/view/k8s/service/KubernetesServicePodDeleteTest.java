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

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.kube.KubePod;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Deleting and restarting a pod lets it shut down within its own grace period; only an explicit grace period is
 * sent to the API server.
 */
@EnableKubernetesMockClient(crud = true)
class KubernetesServicePodDeleteTest {

  private static final String NS = "analytics";

  KubernetesMockServer server;
  KubernetesClient client;
  KubernetesService svc;

  @BeforeEach
  void setUp() {
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getInstanceName()).thenReturn("pod-delete-test");
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
    svc = new KubernetesService(ctx, client, mock(HelmClient.class), /*isConfigured=*/true);
    client.pods().inNamespace(NS).resource(new PodBuilder()
        .withNewMetadata().withName("db-0").withNamespace(NS).endMetadata()
        .withNewSpec().addNewContainer().withName("main").withImage("busybox").endContainer().endSpec().build()).create();
  }

  /** The last DELETE sent to the API server, as method, path and body. */
  private String lastDelete() throws Exception {
    RecordedRequest last = null;
    for (int i = server.getRequestCount(); i > 0; i--) {
      RecordedRequest request = server.takeRequest();
      if ("DELETE".equals(request.getMethod())) {
        last = request;
      }
    }
    assertNotNull(last, "a DELETE was sent");
    return last.getBody().readUtf8();
  }

  @Test
  void aDeleteWithoutGraceSecondsLeavesTheGracePeriodToThePod() throws Exception {
    svc.deletePod(NS, "db-0", null);

    assertFalse(lastDelete().contains("gracePeriodSeconds"));
  }

  @Test
  void aNegativeGracePeriodIsIgnored() throws Exception {
    svc.deletePod(NS, "db-0", -1);

    assertFalse(lastDelete().contains("gracePeriodSeconds"));
  }

  @Test
  void restartingAPodLeavesTheGracePeriodToThePod() throws Exception {
    svc.restartPod(NS, "db-0");

    assertFalse(lastDelete().contains("gracePeriodSeconds"));
  }

  @Test
  void anExplicitGracePeriodIsSent() throws Exception {
    svc.deletePod(NS, "db-0", 0);

    assertTrue(lastDelete().replace(" ", "").contains("\"gracePeriodSeconds\":0"));
  }

  @Test
  void theListingTellsTheControllerKindAndWhetherThePodIsTerminating() {
    Pod terminating = new PodBuilder()
        .withNewMetadata().withName("db-1").withNamespace(NS).withDeletionTimestamp("2026-10-13T10:00:00Z")
        .addNewOwnerReference().withKind("StatefulSet").withName("db").withApiVersion("apps/v1").withUid("u1")
        .withController(true).endOwnerReference().endMetadata().build();
    Pod running = new PodBuilder().withNewMetadata().withName("db-0").withNamespace(NS).endMetadata().build();

    KubePod dto = svc.toKubePod(terminating);
    assertEquals("StatefulSet", dto.ownerKind);
    assertEquals("2026-10-13T10:00:00Z", dto.deletionTimestamp);

    KubePod plain = svc.toKubePod(running);
    assertNull(plain.ownerKind);
    assertNull(plain.deletionTimestamp);
  }
}
