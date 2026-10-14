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

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gives a live-cluster test class a {@link KubernetesClient} with cluster-admin rights on a cluster it may change.
 *
 * <p>The cluster is the one named by {@link #KUBECONFIG_ENV} when that variable is set, otherwise a k3s cluster
 * started in Docker for the test class and removed after it. The class is skipped when neither is available, as
 * Testcontainers' {@code disabledWithoutDocker} does, so the integration build still passes on a machine without
 * Docker. {@code KUBECONFIG} is deliberately not used: on a build agent it often names a shared cluster, which these
 * tests would change.
 *
 * <p>Inject the client as a {@code KubernetesClient} parameter of a {@code @BeforeAll} or test method.
 */
public class LiveClusterExtension implements ExecutionCondition, BeforeAllCallback, AfterAllCallback, ParameterResolver {

  /** Kubeconfig (path) of a cluster to run against instead of k3s; it needs cluster-admin rights. */
  public static final String KUBECONFIG_ENV = "KDPS_IT_KUBECONFIG";

  static final String K3S_IMAGE = "rancher/k3s:v1.30.1-k3s1";

  private KubernetesClient client;
  private K3sContainer k3s;

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    if (externalKubeconfig() != null) {
      return ConditionEvaluationResult.enabled("Runs against the cluster named by " + KUBECONFIG_ENV);
    }
    if (DockerClientFactory.instance().isDockerAvailable()) {
      return ConditionEvaluationResult.enabled("Runs against a k3s cluster started in Docker");
    }
    return ConditionEvaluationResult.disabled("Docker is not available and " + KUBECONFIG_ENV + " is not set");
  }

  @Override
  public void beforeAll(ExtensionContext context) throws IOException {
    Path external = externalKubeconfig();
    Config config;
    if (external != null) {
      config = Config.fromKubeconfig(null, Files.readString(external), external.toString());
    } else {
      k3s = new K3sContainer(DockerImageName.parse(K3S_IMAGE));
      k3s.start();
      config = Config.fromKubeconfig(k3s.getKubeConfigYaml());
    }
    client = new KubernetesClientBuilder().withConfig(config).build();
  }

  @Override
  public void afterAll(ExtensionContext context) {
    if (client != null) {
      client.close();
    }
    if (k3s != null) {
      k3s.stop();
    }
  }

  @Override
  public boolean supportsParameter(ParameterContext pc, ExtensionContext ec) {
    return pc.getParameter().getType() == KubernetesClient.class;
  }

  @Override
  public Object resolveParameter(ParameterContext pc, ExtensionContext ec) {
    return client;
  }

  private static Path externalKubeconfig() {
    String path = System.getenv(KUBECONFIG_ENV);
    return path == null || path.isBlank() ? null : Path.of(path);
  }
}
