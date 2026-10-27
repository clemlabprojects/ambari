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

import org.apache.ambari.view.k8s.model.CommandStatus;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.cluster.ClusterRef;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.store.ProvisioningLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * A release is identified by its cluster, namespace and name: the same name in the same namespace on two clusters is
 * two releases. The default cluster's releases keep the ids they had, and the operations say where they ran.
 */
class ReleaseClusterTest {

  private static final String NS = "analytics";
  private static final String RELEASE = "trino";
  private static final ClusterRef PROD = ClusterRef.of("prod");

  @TempDir
  Path workDir;

  private PerUserViewContext view;
  private CommandService service;

  @BeforeEach
  void setUp() throws Exception {
    view = new PerUserViewContext("release-cluster", workDir);
    service = new CommandService(view.context, mock(HelmService.class), mock(KubernetesService.class));
    service.startupSweep.get(30, TimeUnit.SECONDS);
  }

  @AfterEach
  void tearDown() {
    service.shutdown();
  }

  private static void record(ReleaseMetadataService metadata, String chart) {
    metadata.recordInstallOrUpgrade(NS, RELEASE, "TRINO", null, chart, null, "1.0", null, null, null, null, null,
        null, "DIRECT_HELM", null, null, null, null, null, null, null, null, null);
  }

  @Test
  void theDefaultClustersReleaseIdsAreTheOnesTheyHadAndAnotherClustersCarryIt() {
    assertEquals("analytics:trino", K8sReleaseEntity.idOf(NS, RELEASE));
    assertEquals("analytics:trino", K8sReleaseEntity.idOf(ClusterRef.DEFAULT, NS, RELEASE));
    assertEquals("@prod:analytics:trino", K8sReleaseEntity.idOf(PROD, NS, RELEASE));

    assertEquals(ClusterRef.DEFAULT, K8sReleaseEntity.clusterOfId("analytics:trino"));
    assertEquals(PROD, K8sReleaseEntity.clusterOfId("@prod:analytics:trino"));
    assertEquals(ClusterRef.DEFAULT, K8sReleaseEntity.clusterOfId(null));
  }

  @Test
  void theSameReleaseOnTwoClustersIsTwoRecords() {
    ReleaseMetadataService onDefault = new ReleaseMetadataService(view.context);
    ReleaseMetadataService onProd = new ReleaseMetadataService(view.context, PROD);
    record(onDefault, "clemlab-trino-dev");
    record(onProd, "clemlab-trino-prod");

    assertEquals("clemlab-trino-dev", onDefault.find(NS, RELEASE).getChartRef());
    assertEquals("clemlab-trino-prod", onProd.find(NS, RELEASE).getChartRef());
    assertEquals(PROD, onProd.find(NS, RELEASE).clusterRef());
    assertEquals(ClusterRef.DEFAULT, onDefault.find(NS, RELEASE).clusterRef());
    assertEquals(Set.of("analytics:trino", "@prod:analytics:trino"),
        Set.copyOf(onDefault.findAll().stream().map(K8sReleaseEntity::getId).toList()), "every cluster's releases");

    onProd.delete(NS, RELEASE);

    assertNull(onProd.find(NS, RELEASE));
    assertNotNull(onDefault.find(NS, RELEASE), "the default cluster's release is untouched");
  }

  @Test
  void aReleaseRecordedBeforeSeveralClustersIsTheDefaultClusters() throws Exception {
    K8sReleaseEntity earlier = new K8sReleaseEntity();
    earlier.setId("analytics:trino");
    earlier.setNamespace(NS);
    earlier.setReleaseName(RELEASE);
    earlier.setChartRef("clemlab-trino");
    view.dataStore.store(earlier);

    assertEquals("clemlab-trino", new ReleaseMetadataService(view.context).find(NS, RELEASE).getChartRef());
    assertEquals(ClusterRef.DEFAULT, new ReleaseMetadataService(view.context).find(NS, RELEASE).clusterRef());
    assertNull(new ReleaseMetadataService(view.context, PROD).find(NS, RELEASE));
  }

  @Test
  void theSameReleaseOnAnotherClusterMayShareWhatKdpsRegisteredOutsideKubernetes() {
    ReleaseMetadataService onDefault = new ReleaseMetadataService(view.context);
    ReleaseMetadataService onProd = new ReleaseMetadataService(view.context, PROD);
    record(onDefault, "clemlab-trino");
    record(onProd, "clemlab-trino");
    ProvisioningLedger ledger = new ProvisioningLedger();
    ledger.addRangerGrant(new ProvisioningLedger.RangerGrant("ctx", "cdp_hive", "trino-analytics", "select",
        CommandService.HIVE_ALL_RESOURCES, "kdps-trino-analytics", true));
    onProd.recordProvisioning(NS, RELEASE, l -> l.merge(ledger));

    Map<String, ProvisioningLedger> others = onDefault.otherReleasesProvisioning(NS, RELEASE);

    assertEquals(Set.of("analytics/trino@prod"), others.keySet(), "the release on prod is another one");
    assertTrue(others.keySet().iterator().next().startsWith(NS + "/"), "and one of the same namespace");
    assertEquals(Set.of(), onProd.otherReleasesProvisioning(NS, RELEASE).keySet(),
        "the default cluster's release registered nothing");
  }

  @Test
  void aDeployNamesItsClusterAndOnlyThatClustersOperationsRunIt() {
    HelmDeployRequest request = new HelmDeployRequest();
    assertEquals(ClusterRef.DEFAULT, CommandService.kubernetesClusterOf(request));
    request.setKubernetesCluster("prod");
    assertEquals(PROD, CommandService.kubernetesClusterOf(request));
    request.setKubernetesCluster("Prod!");
    assertThrows(IllegalArgumentException.class, () -> CommandService.kubernetesClusterOf(request));

    request.setKubernetesCluster("prod");
    request.setNamespace(NS);
    request.setReleaseName(RELEASE);
    IllegalStateException elsewhere = assertThrows(IllegalStateException.class, () -> service.planReleaseDeploy(request,
        "repo", "1.0", null, null, null, null, false));
    assertTrue(elsewhere.getMessage().contains("reached the operations of the cluster 'default'"), elsewhere.getMessage());
    IllegalStateException gitRefused = assertThrows(IllegalStateException.class, () -> service.gitOpsDeploy(request,
        "repo", "1.0", null, null, null, null));
    assertTrue(gitRefused.getMessage().contains("Nothing was deployed"), "refused before the Git server is contacted");
    assertTrue(view.dataStore.findAll(CommandEntity.class, null).isEmpty(), "nothing was planned");
  }

  private CommandService prodOperations(boolean connected) {
    KubernetesService prodCluster = mock(KubernetesService.class);
    ViewConfigurationService connection = mock(ViewConfigurationService.class);
    org.mockito.Mockito.when(connection.isConfigured()).thenReturn(connected);
    org.mockito.Mockito.when(prodCluster.getConfigurationService()).thenReturn(connection);
    return new CommandService(view.context, mock(HelmService.class), prodCluster, PROD);
  }

  @Test
  void aReleaseActionRunsOnTheReleasesClusterAndSaysSo() throws Exception {
    view.as("lead");
    new ReleaseMetadataService(view.context, PROD).recordInstallOrUpgrade(NS, RELEASE, "TRINO", null, "clemlab/trino",
        "charts", "1.0", null, null, null, null, null, null, "DIRECT_HELM", null, null, null, null, null, null, null,
        null, null);
    CommandService prod = prodOperations(true);
    try {
      String id = prod.submitReleaseUpgrade(NS, RELEASE, "2.0", null, java.net.URI.create("http://ambari:8080/"));

      assertEquals("prod", view.dataStore.find(CommandEntity.class, id).getKubernetesCluster());
      assertEquals("prod", prod.getStatus(id).kubernetesCluster);
      // The default cluster has no such release: its operations do not reach the one on prod.
      assertThrows(IllegalArgumentException.class, () -> service.submitReleaseUpgrade(NS, RELEASE, "2.0", null,
          java.net.URI.create("http://ambari:8080/")));
    } finally {
      prod.shutdown();
    }
  }

  private HelmDeployRequest prodRequest() {
    HelmDeployRequest request = new HelmDeployRequest();
    request.setKubernetesCluster("prod");
    request.setNamespace(NS);
    request.setReleaseName(RELEASE);
    return request;
  }

  @Test
  void aDeployToAClusterTheViewDoesNotKnowOrCannotReachIsRefused() {
    CommandService unknown = prodOperations(true);
    try {
      IllegalStateException refused = assertThrows(IllegalStateException.class,
          () -> unknown.checkDeployCluster(prodRequest()));
      assertTrue(refused.getMessage().contains("knows no Kubernetes cluster 'prod'"), refused.getMessage());
    } finally {
      unknown.shutdown();
    }

    view.as("lead");
    new org.apache.ambari.view.k8s.service.cluster.ClusterRegistry(new org.apache.ambari.view.k8s.store.InstanceSettings(
        view.context)).save(new org.apache.ambari.view.k8s.service.cluster.KubernetesCluster("prod", "Production", "",
        true));
    view.as(null);
    CommandService unreachable = prodOperations(false);
    try {
      IllegalStateException refused = assertThrows(IllegalStateException.class,
          () -> unreachable.checkDeployCluster(prodRequest()));
      assertTrue(refused.getMessage().contains("has no connection yet"), refused.getMessage());
    } finally {
      unreachable.shutdown();
    }
  }

  @Test
  void noOperationsAreBuiltForAClusterTheViewDoesNotKnow() {
    IllegalStateException refused = assertThrows(IllegalStateException.class,
        () -> CommandService.forDeploy(view.context, prodRequest()));
    assertTrue(refused.getMessage().contains("knows no Kubernetes cluster 'prod'"), refused.getMessage());
  }

  @Test
  void aDeployToAClusterWithoutAConnectionIsRefusedBeforeAnythingIsReadThere() {
    view.as("lead");
    new org.apache.ambari.view.k8s.service.cluster.ClusterRegistry(new org.apache.ambari.view.k8s.store.InstanceSettings(
        view.context)).save(new org.apache.ambari.view.k8s.service.cluster.KubernetesCluster("prod", "Production", "",
        true));
    view.as(null);
    CommandService unreachable = prodOperations(false);
    try {
      IllegalStateException refused = assertThrows(IllegalStateException.class, () -> unreachable.submitDeploy(
          prodRequest(), "repo", "1.0", null, null, null, null, null));
      assertTrue(refused.getMessage().contains("has no connection yet"), refused.getMessage());
    } finally {
      unreachable.shutdown();
    }
  }

  @Test
  void aDeployToAnotherClusterMayUseTheCompanyCaAndTheManagedTruststores() {
    view.as("lead");
    new org.apache.ambari.view.k8s.service.cluster.ClusterRegistry(new org.apache.ambari.view.k8s.store.InstanceSettings(
        view.context)).save(new org.apache.ambari.view.k8s.service.cluster.KubernetesCluster("prod", "Production", "",
        true));
    view.as(null);
    CommandService prod = prodOperations(true);
    try {
      prod.checkDeployCluster(prodRequest());

      // The view keeps the Company CAs and signs the leaf itself, whatever the cluster.
      HelmDeployRequest companyCa = prodRequest();
      companyCa.setIngressTlsSelfSign(Map.of("mode", "companyUploaded", "caName", "corp"));
      prod.checkDeployCluster(companyCa);

      // The view keeps the shared truststores; a project one is read on the cluster of its project.
      HelmDeployRequest truststores = prodRequest();
      truststores.setTruststoreRefs(List.of("ambari-pki/corp-trust", "analytics/partner-trust"));
      prod.checkDeployCluster(truststores);
    } finally {
      prod.shutdown();
    }
  }

  private void storeOperation(String id, String kubernetesCluster) throws Exception {
    CommandStatusEntity status = new CommandStatusEntity();
    status.setId(id + "-status");
    status.setState("SUCCEEDED");
    status.setCreatedAt("2026-10-26T10:00:00Z");
    status.setUpdatedAt("2026-10-26T10:00:00Z");
    view.dataStore.store(status);
    CommandEntity root = new CommandEntity();
    root.setId(id);
    root.setViewInstance("release-cluster");
    root.setType(CommandType.K8S_MANAGER_RELEASE_DEPLOY.name());
    root.setTitle("Install release " + RELEASE);
    root.setKubernetesCluster(kubernetesCluster);
    root.setCommandStatusId(status.getId());
    root.setChildListJson("[]");
    root.setParamsJson("{}");
    view.dataStore.store(root);
  }

  @Test
  void anOperationSaysWhichClusterItRanOn() throws Exception {
    storeOperation("op-before", null);
    storeOperation("op-prod", "prod");

    assertEquals("default", service.getStatus("op-before").kubernetesCluster, "planned before several clusters");
    assertEquals("prod", service.getStatus("op-prod").kubernetesCluster);
    assertEquals(ClusterRef.DEFAULT, CommandService.kubernetesClusterOf(Map.<String, Object>of()));
    assertEquals(PROD, CommandService.kubernetesClusterOf(Map.<String, Object>of(
        CommandService.KUBERNETES_CLUSTER_PARAM, "prod")));
  }
}
