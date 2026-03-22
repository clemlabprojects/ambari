/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.controller;

import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.HostState;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.State;
import org.junit.Test;

public class AmbariManagementControllerImplImpalaTest {

  @Test
  public void testGetHiveMetastoresToRestartForInitialImpalaStartReturnsSortedStartedHosts() throws Exception {
    Cluster cluster = createNiceMock(Cluster.class);
    Service impala = createNiceMock(Service.class);
    Service hive = createNiceMock(Service.class);
    ServiceComponentHost catalog = createNiceMock(ServiceComponentHost.class);
    ServiceComponentHost metastore1 = createNiceMock(ServiceComponentHost.class);
    ServiceComponentHost metastore2 = createNiceMock(ServiceComponentHost.class);

    Map<String, String> requestProperties = new HashMap<>();
    requestProperties.put("phase", "INITIAL_START");

    Map<String, Map<State, List<ServiceComponentHost>>> changedHosts = new HashMap<>();
    changedHosts.put("IMPALA_CATALOG_SERVICE",
        Collections.singletonMap(State.STARTED, Collections.singletonList(catalog)));

    Map<String, Service> services = new HashMap<>();
    services.put("IMPALA", impala);
    services.put("HIVE", hive);

    expect(cluster.getServices()).andReturn(services);
    expect(cluster.getServiceComponentHosts("IMPALA", "IMPALA_CATALOG_SERVICE"))
        .andReturn(Collections.singletonList(catalog));
    expect(catalog.getState()).andReturn(State.INSTALLED).anyTimes();

    expect(cluster.getServiceComponentHosts("HIVE", "HIVE_METASTORE"))
        .andReturn(Arrays.asList(metastore2, metastore1));

    expect(metastore1.getState()).andReturn(State.STARTED).anyTimes();
    expect(metastore1.getHostState()).andReturn(HostState.HEALTHY).anyTimes();
    expect(metastore1.getHostName()).andReturn("host-a").anyTimes();

    expect(metastore2.getState()).andReturn(State.STARTED).anyTimes();
    expect(metastore2.getHostState()).andReturn(HostState.HEALTHY).anyTimes();
    expect(metastore2.getHostName()).andReturn("host-b").anyTimes();

    replay(cluster, impala, hive, catalog, metastore1, metastore2);

    List<String> hosts = AmbariManagementControllerImpl
        .getHiveMetastoresToRestartForInitialImpalaStart(cluster, requestProperties, changedHosts)
        .stream()
        .map(ServiceComponentHost::getHostName)
        .collect(Collectors.toList());

    assertEquals(Arrays.asList("host-a", "host-b"), hosts);

    verify(cluster, impala, hive, catalog, metastore1, metastore2);
  }

  @Test
  public void testGetHiveMetastoresToRestartForInitialImpalaStartSkipsWhenCatalogAlreadyStarted() throws Exception {
    Cluster cluster = createNiceMock(Cluster.class);
    Service impala = createNiceMock(Service.class);
    Service hive = createNiceMock(Service.class);
    ServiceComponentHost catalog = createNiceMock(ServiceComponentHost.class);

    Map<String, String> requestProperties = new HashMap<>();
    requestProperties.put("phase", "INITIAL_START");

    Map<String, Map<State, List<ServiceComponentHost>>> changedHosts = new HashMap<>();
    changedHosts.put("IMPALA_CATALOG_SERVICE",
        Collections.singletonMap(State.STARTED, Collections.singletonList(catalog)));

    Map<String, Service> services = new HashMap<>();
    services.put("IMPALA", impala);
    services.put("HIVE", hive);

    expect(cluster.getServices()).andReturn(services);
    expect(cluster.getServiceComponentHosts("IMPALA", "IMPALA_CATALOG_SERVICE"))
        .andReturn(Collections.singletonList(catalog));
    expect(catalog.getState()).andReturn(State.STARTED).anyTimes();

    replay(cluster, impala, hive, catalog);

    assertTrue(AmbariManagementControllerImpl
        .getHiveMetastoresToRestartForInitialImpalaStart(cluster, requestProperties, changedHosts)
        .isEmpty());

    verify(cluster, impala, hive, catalog);
  }
}
