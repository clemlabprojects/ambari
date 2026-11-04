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
package org.apache.ambari.server.checks;

import java.util.Collections;

import org.apache.ambari.server.controller.PrereqCheckRequest;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.stack.PrereqCheckStatus;
import org.apache.ambari.server.state.stack.PrerequisiteCheck;
import org.apache.ambari.server.state.stack.upgrade.UpgradeType;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class OzoneRollingUpgradeCheckTest {

  private final Clusters clusters = Mockito.mock(Clusters.class);
  private final Cluster cluster = Mockito.mock(Cluster.class);
  private final OzoneRollingUpgradeCheck check = new OzoneRollingUpgradeCheck();

  @Before
  public void setup() throws Exception {
    check.clustersProvider = () -> clusters;
    Mockito.when(clusters.getCluster("c1")).thenReturn(cluster);
    Mockito.when(cluster.getServices()).thenReturn(Collections.singletonMap("OZONE", Mockito.mock(Service.class)));
  }

  private PrerequisiteCheck perform(UpgradeType upgradeType) throws Exception {
    PrerequisiteCheck result = new PrerequisiteCheck(null, null);
    check.perform(result, new PrereqCheckRequest("c1", upgradeType));
    return result;
  }

  @Test
  public void testFailsARollingUpgradeWithOzone() throws Exception {
    PrerequisiteCheck result = perform(UpgradeType.ROLLING);
    Assert.assertEquals(PrereqCheckStatus.FAIL, result.getStatus());
    Assert.assertTrue(result.getFailedOn().contains("OZONE"));
    Assert.assertTrue(result.getFailReason().contains("Express upgrade"));
  }

  @Test
  public void testPassesAnExpressUpgrade() throws Exception {
    Assert.assertEquals(PrereqCheckStatus.PASS, perform(UpgradeType.NON_ROLLING).getStatus());
  }

  @Test
  public void testPassesWithoutOzone() throws Exception {
    Mockito.when(cluster.getServices()).thenReturn(Collections.emptyMap());
    Assert.assertEquals(PrereqCheckStatus.PASS, perform(UpgradeType.ROLLING).getStatus());
  }
}
