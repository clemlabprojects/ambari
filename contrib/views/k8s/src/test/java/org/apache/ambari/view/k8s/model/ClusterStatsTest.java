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

package org.apache.ambari.view.k8s.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClusterStatsTest {

  @Test
  void resourceAndHelmStats_areReported() {
    ClusterStats.ResourceStat cpu   = new ClusterStats.ResourceStat(2.5, 8.0);
    ClusterStats.ResourceStat mem   = new ClusterStats.ResourceStat(6.0, 16.0);
    ClusterStats.ResourceStat pods  = new ClusterStats.ResourceStat(42, 110);
    ClusterStats.ResourceStat nodes = new ClusterStats.ResourceStat(1, 3);
    ClusterStats.HelmStat helm      = new ClusterStats.HelmStat(3, 1, 0, 4);

    ClusterStats stats = new ClusterStats(cpu, mem, pods, nodes, helm);

    assertEquals(2.5, stats.getCpu().getUsed(), 0.0001);
    assertEquals(8.0, stats.getCpu().getTotal(), 0.0001);

    assertEquals(6.0, stats.getMemory().getUsed(), 0.0001);
    assertEquals(16.0, stats.getMemory().getTotal(), 0.0001);

    assertEquals(42.0, stats.getPods().getUsed(), 0.0001);
    assertEquals(110.0, stats.getPods().getTotal(), 0.0001);

    assertEquals(1.0, stats.getNodes().getUsed(), 0.0001);
    assertEquals(3.0, stats.getNodes().getTotal(), 0.0001);

    assertEquals(3, stats.getHelm().getDeployed());
    assertEquals(1, stats.getHelm().getPending());
    assertEquals(0, stats.getHelm().getFailed());
    assertEquals(4, stats.getHelm().getTotal());
  }
}
