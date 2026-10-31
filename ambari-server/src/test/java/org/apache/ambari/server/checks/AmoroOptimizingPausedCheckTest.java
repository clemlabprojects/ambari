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
import java.util.HashMap;
import java.util.Map;

import org.apache.ambari.server.controller.PrereqCheckRequest;
import org.apache.ambari.server.orm.dao.AlertsDAO;
import org.apache.ambari.server.orm.entities.AlertCurrentEntity;
import org.apache.ambari.server.orm.entities.AlertHistoryEntity;
import org.apache.ambari.server.state.AlertState;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.stack.PrereqCheckStatus;
import org.apache.ambari.server.state.stack.PrerequisiteCheck;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class AmoroOptimizingPausedCheckTest {

  private final Clusters clusters = Mockito.mock(Clusters.class);
  private final Cluster cluster = Mockito.mock(Cluster.class);
  private final AlertsDAO alertsDAO = Mockito.mock(AlertsDAO.class);
  private final AmoroOptimizingPausedCheck check = new AmoroOptimizingPausedCheck();

  @Before
  public void setup() throws Exception {
    check.clustersProvider = () -> clusters;
    check.alertsDAOProvider = () -> alertsDAO;
    Mockito.when(clusters.getCluster("c1")).thenReturn(cluster);
    Mockito.when(cluster.getClusterId()).thenReturn(1L);

    ServiceComponent server = Mockito.mock(ServiceComponent.class);
    Map<String, ServiceComponentHost> hosts = new HashMap<>();
    hosts.put("host1", Mockito.mock(ServiceComponentHost.class));
    Mockito.when(server.getServiceComponentHosts()).thenReturn(hosts);
    Service amoro = Mockito.mock(Service.class);
    Mockito.when(amoro.getServiceComponents()).thenReturn(Collections.singletonMap("AMORO_SERVER", server));
    Mockito.when(cluster.getServices()).thenReturn(Collections.singletonMap("AMORO", amoro));
  }

  private void alert(AlertState state) {
    AlertHistoryEntity history = Mockito.mock(AlertHistoryEntity.class);
    Mockito.when(history.getAlertState()).thenReturn(state);
    AlertCurrentEntity current = Mockito.mock(AlertCurrentEntity.class);
    Mockito.when(current.getAlertHistory()).thenReturn(history);
    Mockito.when(alertsDAO.findCurrentByHostAndName(1L, "host1", "amoro_optimizing_paused")).thenReturn(current);
  }

  private PrerequisiteCheck perform() throws Exception {
    PrerequisiteCheck result = new PrerequisiteCheck(null, null);
    check.perform(result, new PrereqCheckRequest("c1"));
    return result;
  }

  @Test
  public void testFailsWhileOptimizingRuns() throws Exception {
    alert(AlertState.OK);
    PrerequisiteCheck result = perform();
    Assert.assertEquals(PrereqCheckStatus.FAIL, result.getStatus());
    Assert.assertTrue(result.getFailedOn().contains("host1"));
    Assert.assertTrue(result.getFailReason().contains("Pause Optimizing"));
  }

  @Test
  public void testFailsBeforeTheAlertRan() throws Exception {
    Assert.assertEquals(PrereqCheckStatus.FAIL, perform().getStatus());
  }

  @Test
  public void testPassesOncePaused() throws Exception {
    alert(AlertState.WARNING);
    Assert.assertEquals(PrereqCheckStatus.PASS, perform().getStatus());
  }

  @Test
  public void testPassesWithoutAmoro() throws Exception {
    Mockito.when(cluster.getServices()).thenReturn(Collections.emptyMap());
    Assert.assertEquals(PrereqCheckStatus.PASS, perform().getStatus());
  }
}
