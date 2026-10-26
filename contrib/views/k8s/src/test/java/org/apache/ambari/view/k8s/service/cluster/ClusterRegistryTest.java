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

package org.apache.ambari.view.k8s.service.cluster;

import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The clusters a view instance knows, and the ids they may have. */
class ClusterRegistryTest {

  @TempDir
  Path workDir;

  PerUserViewContext view;

  @BeforeEach
  void setUp() {
    view = new PerUserViewContext("clusters-test", workDir).as("lead");
  }

  private ClusterRegistry registry() {
    return new ClusterRegistry(new InstanceSettings(view.context));
  }

  @Test
  void aClusterIdIsALowercaseDnsLabelOfAtMost32Characters() {
    for (String id : List.of("prod", "dev-oc", "p1", "a", "x".repeat(32))) {
      assertEquals(id, ClusterRef.of(id).id());
    }
    for (String id : List.of("", "Prod", "-prod", "prod-", "pr od", "prod.eu", "../prod", "x".repeat(33))) {
      assertThrows(IllegalArgumentException.class, () -> ClusterRef.of(id), id);
    }
    assertThrows(IllegalArgumentException.class, () -> ClusterRef.of(null));
  }

  @Test
  void theDefaultClusterKeepsItsSettingKeysAndFolderAndAnotherOneHasItsOwn() {
    assertSame(ClusterRef.DEFAULT, ClusterRef.of("default"));
    assertEquals("auth.mode", ClusterRef.DEFAULT.settingKey("auth.mode"));
    assertEquals(workDir, ClusterRef.DEFAULT.folder(workDir));
    assertEquals("cluster.prod.auth.mode", ClusterRef.of("prod").settingKey("auth.mode"));
    assertEquals(workDir.resolve("clusters").resolve("prod"), ClusterRef.of("prod").folder(workDir));
  }

  @Test
  void theDefaultClusterIsAlwaysListedFirstAndTheOthersById() {
    assertEquals(List.of("default"), registry().list().stream().map(KubernetesCluster::id).toList());

    registry().save(new KubernetesCluster("prod", "Production OpenShift", "", true));
    registry().save(new KubernetesCluster("dev", "Development OpenShift", "Team tests", false));

    List<KubernetesCluster> clusters = registry().list();
    assertEquals(List.of("default", "dev", "prod"), clusters.stream().map(KubernetesCluster::id).toList());
    assertTrue(clusters.get(2).protectedCluster());
    assertEquals("Team tests", clusters.get(1).description());
  }

  @Test
  void theDefaultClusterCanBeDescribedButNotRemoved() {
    registry().save(new KubernetesCluster("default", "Ambari's own OpenShift", "", true));

    KubernetesCluster first = registry().list().get(0);
    assertEquals("Ambari's own OpenShift", first.name());
    assertTrue(first.protectedCluster());
    assertThrows(IllegalArgumentException.class, () -> registry().remove("default"));
  }

  @Test
  void aClusterIsDescribedAgainAndRemovedByItsId() {
    registry().save(new KubernetesCluster("prod", "Production", "", false));
    registry().save(new KubernetesCluster("prod", "Production OpenShift", "EU", true));

    assertEquals(2, registry().list().size());
    assertEquals("Production OpenShift", registry().find("prod").orElseThrow().name());
    assertTrue(registry().remove("prod"));
    assertFalse(registry().remove("prod"));
    assertTrue(registry().find("prod").isEmpty());
  }

  @Test
  void aClusterNeedsAValidIdAndAUniqueNameOfReasonableLength() {
    registry().save(new KubernetesCluster("prod", "Production", "", false));

    assertThrows(IllegalArgumentException.class, () -> registry().save(new KubernetesCluster("Prod", "x", "", false)));
    assertThrows(IllegalArgumentException.class, () -> registry().save(new KubernetesCluster("dev", " ", "", false)));
    assertThrows(IllegalArgumentException.class, () -> registry().save(
        new KubernetesCluster("dev", "x".repeat(KubernetesCluster.MAX_NAME_LENGTH + 1), "", false)));
    assertThrows(IllegalArgumentException.class, () -> registry().save(
        new KubernetesCluster("dev", "Dev", "x".repeat(KubernetesCluster.MAX_DESCRIPTION_LENGTH + 1), false)));
    IllegalArgumentException taken = assertThrows(IllegalArgumentException.class,
        () -> registry().save(new KubernetesCluster("dev", "production", "", false)));
    assertTrue(taken.getMessage().contains("already called"));
    assertEquals(2, registry().list().size(), "nothing saved by the refused calls");
  }

  @Test
  void theListIsTheViewInstancesForEveryUser() {
    registry().save(new KubernetesCluster("prod", "Production", "", false));

    view.as("ops");
    assertTrue(registry().find("prod").isPresent());
    assertTrue(view.instanceDataOf("lead").isEmpty(), "nothing kept in the administrator's own data");
  }
}
