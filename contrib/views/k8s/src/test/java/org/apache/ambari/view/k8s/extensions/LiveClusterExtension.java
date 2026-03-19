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

package org.apache.ambari.view.k8s.it;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import org.junit.jupiter.api.extension.*;

import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;

public class LiveClusterExtension implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

  private DefaultKubernetesClient client;
  private K3sContainer k3s;

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    // 1) Prefer a user‑supplied Minikube if available
    String kubeconfigEnv = System.getenv("KUBECONFIG");
    boolean minikubeUp   = kubeconfigEnv != null && Files.exists(Path.of(kubeconfigEnv));

    if (minikubeUp) {
      client = new DefaultKubernetesClient();          // picks up env var
      return;
    }

    // 2) Otherwise start an embedded K3s cluster
    k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.30.1-k3s1"));
    k3s.start();

    Config cfg = Config.fromKubeconfig(k3s.getKubeConfigYaml());
    client     = new DefaultKubernetesClient(cfg);
  }

  @Override
  public void afterAll(ExtensionContext context) {
    if (k3s != null) k3s.stop();
    if (client != null) client.close();
  }

  /* ------- Parameter injection so tests can just declare KubernetesClient arg ------- */

  @Override
  public boolean supportsParameter(ParameterContext pc, ExtensionContext ec) {
    return pc.getParameter().getType().isAssignableFrom(DefaultKubernetesClient.class);
  }

  @Override
  public Object resolveParameter(ParameterContext pc, ExtensionContext ec) {
    return client;
  }
}
