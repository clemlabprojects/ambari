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

import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.apache.ambari.view.k8s.model.ConnectionHealth;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** A cluster that is down slows nothing else: each cluster has its own client, and says itself it is unreachable. */
@EnableKubernetesMockClient(crud = true)
class ClusterIndependenceTest {

  KubernetesClient client;

  @TempDir
  Path workDir;

  @Test
  void aClusterThatIsDownDoesNotHoldBackAnother() throws Exception {
    PerUserViewContext view = new PerUserViewContext("independence", workDir).as("lead");
    KubernetesService up = new KubernetesService(view.context, client, mock(HelmClient.class), true);
    // Nothing listens there: the cluster is down.
    KubernetesClient downClient = new KubernetesClientBuilder().withConfig(new ConfigBuilder()
        .withMasterUrl("https://127.0.0.1:1").withTrustCerts(true).withConnectionTimeout(3000).withRequestTimeout(3000)
        .build()).build();
    KubernetesService down = new KubernetesService(view.context, downClient, mock(HelmClient.class), true);

    CompletableFuture<ConnectionHealth> downPing = CompletableFuture.supplyAsync(down::pingCluster);
    long start = System.nanoTime();
    ConnectionHealth upHealth = up.pingCluster();
    long upMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertEquals(ConnectionHealth.State.CONNECTED, upHealth.getState());
    assertTrue(upMillis < 2000, "the cluster that is up answered in " + upMillis + " ms");
    ConnectionHealth downHealth = downPing.get(30, TimeUnit.SECONDS);
    assertEquals(ConnectionHealth.State.UNREACHABLE, downHealth.getState(), "and the one that is down says so");
    downClient.close();
  }
}
