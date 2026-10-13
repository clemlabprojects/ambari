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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.requests.KeytabRequest;
import org.apache.ambari.view.k8s.security.KeytabWebhookProofs;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.ws.rs.core.MultivaluedHashMap;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The keytab webhook holds no Ambari permission: its keytab requests are issued with the Ambari credentials the
 * release's deploy was made with, and only for releases KDPS deployed. What the webhook says about the deploy is not
 * trusted.
 */
class CommandServiceWebhookKeytabTest {

  private static final String NS = "analytics";
  private static final URI BASE_URI = URI.create("http://ambari.test:8080/api/v1/");
  private static final Map<String, Object> DEPLOYER = Map.of("authorization", List.of("Basic b3BzOnNlY3JldA=="));

  @TempDir
  Path workDir;

  private final Gson gson = new Gson();
  private PerUserViewContext view;
  private CommandService service;

  /** Steps run with slf4j's MDC; see CommandServiceHelmStepTest for why it is loaded this way. */
  @BeforeAll
  static void loadMdc() {
    String javaVersion = System.getProperty("java.version");
    System.setProperty("java.version", "1.8");
    try {
      org.slf4j.MDC.getMDCAdapter();
    } finally {
      System.setProperty("java.version", javaVersion);
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    view = new PerUserViewContext("webhook-keytab-" + Long.toHexString(System.nanoTime()), workDir);
    service = new CommandService(view.context, mock(HelmService.class), mock(KubernetesService.class));
    service.startupSweep.get(30, TimeUnit.SECONDS);
    service.keytabLogin = (keytab, principal) -> {
      throw new AssertionError("no KDC login expected");
    };
  }

  @AfterEach
  void tearDown() {
    service.shutdown();
  }

  @Test
  void theWebhookKeytabIsIssuedWithTheCredentialsOfTheReleaseDeploy() {
    release("trino", "deploy-1");
    deploy("deploy-1", "trino", DEPLOYER);
    KeytabRequest req = KeytabWebhookProofs.trinoPodRequest();
    req.setParentCommandId("an-operation-the-webhook-made-up");

    String id = service.submitWebhookKeytabRequest(req, BASE_URI);

    Map<String, Object> issue = paramsOf(step(id, CommandType.KEYTAB_ISSUE_PRINCIPAL));
    assertEquals(DEPLOYER, issue.get("_callerHeaders"));
    assertEquals("trino-analytics@EXAMPLE.COM", issue.get("principalFqdn"));
    assertEquals("deploy-1", issue.get("deployCommandId"));
    // Not added to the deploy's steps, which would run it again, nor anywhere the webhook said.
    assertNull(issue.get("parentCommandId"));
    assertFalse(view.dataStore.find(CommandEntity.class, "deploy-1").getChildListJson() != null
        && view.dataStore.find(CommandEntity.class, "deploy-1").getChildListJson().contains(id));
  }

  @Test
  void aDeployOfAnotherReleaseLendsNoCredentials() {
    // The release record names a deploy whose parameters are those of another release: its credentials are not used.
    release("trino", "deploy-2");
    deploy("deploy-2", "superset", DEPLOYER);

    String id = service.submitWebhookKeytabRequest(KeytabWebhookProofs.trinoPodRequest(), BASE_URI);

    Map<String, Object> issue = paramsOf(step(id, CommandType.KEYTAB_ISSUE_PRINCIPAL));
    assertEquals(Map.of(), issue.get("_callerHeaders"));
    assertNull(issue.get("deployCommandId"));
  }

  @Test
  void aPodCreatedAfterTheDeployEndedFailsSayingWhichReleaseToRedeploy() throws Exception {
    // The deploy's credentials are gone (purged once it succeeded) and no other Secret holds the keytab: the operation
    // must say so, not fail on an anonymous call to Ambari.
    release("trino", "deploy-1");

    String id = service.submitWebhookKeytabRequest(KeytabWebhookProofs.trinoPodRequest(), BASE_URI);

    CommandStatusEntity issue = endOf(step(id, CommandType.KEYTAB_ISSUE_PRINCIPAL));
    assertEquals(CommandState.FAILED.name(), issue.getState());
    assertTrue(issue.getMessage().contains("release analytics/trino"), issue.getMessage());
    assertTrue(issue.getMessage().contains("Redeploy the release"), issue.getMessage());
  }

  @Test
  void anUnknownPrincipalIsRefused() {
    release("trino", "deploy-1");
    deploy("deploy-1", "trino", DEPLOYER);
    KeytabRequest req = KeytabWebhookProofs.trinoPodRequest();
    req.setPrincipal("hdfs@EXAMPLE.COM");

    assertThrows(KeytabRequestPolicy.RefusedException.class, () -> service.submitWebhookKeytabRequest(req, BASE_URI));
    assertTrue(view.dataStore.findAll(CommandEntity.class, null).stream()
        .noneMatch(c -> CommandType.KEYTAB_ISSUE_PRINCIPAL.name().equals(c.getType())));
  }

  @Test
  void aReleaseKdpsDidNotDeployGetsNoKeytab() {
    release("superset", "deploy-1");

    assertThrows(KeytabRequestPolicy.RefusedException.class,
        () -> service.submitWebhookKeytabRequest(KeytabWebhookProofs.trinoPodRequest(), BASE_URI));
    KeytabRequest operator = KeytabWebhookProofs.trinoPodRequest();
    operator.setNamespace("finance");
    operator.setPrincipal("trino-finance@EXAMPLE.COM");
    assertThrows(KeytabRequestPolicy.RefusedException.class,
        () -> service.submitKeytabRequest(operator, new MultivaluedHashMap<>(), BASE_URI));
  }

  // ------------------------------------------------------------------------------------------------------------

  private void release(String name, String deploymentId) {
    K8sReleaseEntity release = new K8sReleaseEntity();
    release.setId(K8sReleaseEntity.idOf(NS, name));
    release.setNamespace(NS);
    release.setReleaseName(name);
    release.setDeploymentId(deploymentId);
    release.setManagedByUi(true);
    view.dataStore.store(release);
  }

  private void deploy(String id, String releaseName, Map<String, Object> callerHeaders) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("releaseName", releaseName);
    params.put("namespace", NS);
    params.put("_callerHeaders", callerHeaders);
    CommandEntity root = new CommandEntity();
    root.setId(id);
    root.setType(CommandType.K8S_MANAGER_RELEASE_DEPLOY.name());
    root.setTitle("deploy " + releaseName);
    root.setCommandStatusId(id + "-status");
    root.setParamsJson(gson.toJson(params));
    view.dataStore.store(root);
  }

  private CommandEntity step(String rootId, CommandType type) {
    CommandEntity root = view.dataStore.find(CommandEntity.class, rootId);
    List<String> children = gson.fromJson(root.getChildListJson(), new TypeToken<List<String>>() {}.getType());
    return children.stream().map(id -> view.dataStore.find(CommandEntity.class, id))
        .filter(c -> type.name().equals(c.getType())).findFirst()
        .orElseThrow(() -> new AssertionError("no " + type + " step in " + rootId));
  }

  /** Waits until the step ends: the operation runs on the service's workers. */
  private CommandStatusEntity endOf(CommandEntity step) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (true) {
      CommandStatusEntity status = view.dataStore.find(CommandStatusEntity.class, step.getCommandStatusId());
      if (CommandState.FAILED.name().equals(status.getState())
          || CommandState.SUCCEEDED.name().equals(status.getState())) {
        return status;
      }
      if (System.nanoTime() > deadline) {
        throw new AssertionError("step " + step.getId() + " did not end: " + status.getState());
      }
      Thread.sleep(50);
    }
  }

  private Map<String, Object> paramsOf(CommandEntity step) {
    Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
    return gson.fromJson(CommandParamsStore.read(step.getParamsJson()), mapType);
  }
}
