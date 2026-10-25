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

public class LegacyLivyCheckTest {

  private final Clusters clusters = Mockito.mock(Clusters.class);
  private final Cluster cluster = Mockito.mock(Cluster.class);
  private final LegacyLivyCheck check = new LegacyLivyCheck();

  @Before
  public void setup() throws Exception {
    check.clustersProvider = () -> clusters;
    Mockito.when(clusters.getCluster("c1")).thenReturn(cluster);
  }

  private Service spark(String name, Map<String, ServiceComponent> components) {
    Service service = Mockito.mock(Service.class);
    Mockito.when(service.getServiceComponents()).thenReturn(components);
    return service;
  }

  @Test
  public void testFailsWhileLegacyLivyIsInstalled() throws Exception {
    ServiceComponent legacy = Mockito.mock(ServiceComponent.class);
    Map<String, ServiceComponentHost> hosts = new HashMap<>();
    hosts.put("host1", Mockito.mock(ServiceComponentHost.class));
    Mockito.when(legacy.getServiceComponentHosts()).thenReturn(hosts);
    Map<String, ServiceComponent> components = new HashMap<>();
    components.put("LIVY2_SERVER", legacy);
    Map<String, Service> services = new HashMap<>();
    services.put("SPARK3", spark("SPARK3", components));
    services.put("SPARK2", spark("SPARK2", Collections.emptyMap()));
    Mockito.when(cluster.getServices()).thenReturn(services);

    PrerequisiteCheck result = new PrerequisiteCheck(null, null);
    check.perform(result, new PrereqCheckRequest("c1"));

    Assert.assertEquals(PrereqCheckStatus.FAIL, result.getStatus());
    Assert.assertTrue(result.getFailedOn().contains("SPARK3/LIVY2_SERVER on host1"));
    Assert.assertTrue(result.getFailReason().contains("SPARK3/LIVY2_SERVER on host1"));
    Assert.assertTrue(result.getFailReason().contains("migrate_legacy_livy.py --check"));
  }

  @Test
  public void testPassesWithoutLegacyLivy() throws Exception {
    Map<String, Service> services = new HashMap<>();
    services.put("SPARK3", spark("SPARK3", Collections.emptyMap()));
    services.put("HDFS", spark("HDFS", Collections.emptyMap()));
    Mockito.when(cluster.getServices()).thenReturn(services);

    PrerequisiteCheck result = new PrerequisiteCheck(null, null);
    check.perform(result, new PrereqCheckRequest("c1"));

    Assert.assertEquals(PrereqCheckStatus.PASS, result.getStatus());
    Assert.assertTrue(result.getFailedOn().isEmpty());
  }
}
