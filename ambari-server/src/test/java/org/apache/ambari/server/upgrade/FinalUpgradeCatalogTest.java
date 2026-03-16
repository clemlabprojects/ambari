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
package org.apache.ambari.server.upgrade;

import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isNull;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.ServiceConfigVersionResponse;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.State;
import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Test;

import com.google.inject.Injector;

public class FinalUpgradeCatalogTest {
  private static final String CORE_UPGRADE_NOTE =
      "Migrate CORE service for legacy ODP cluster during Ambari upgrade";

  private Injector injector;
  private AmbariManagementController ambariManagementController;
  private Clusters clusters;
  private Cluster cluster;
  private Service hdfsService;
  private Service coreService;
  private ServiceComponent coreClient;
  private ServiceComponentHost coreClientHost1;
  private ServiceComponentHost coreClientHost2;
  private RepositoryVersionEntity repositoryVersion;
  private ConfigHelper configHelper;
  private Host host1;
  private Host host2;

  private static class TestFinalUpgradeCatalog extends FinalUpgradeCatalog {
    TestFinalUpgradeCatalog(Injector injector) {
      super(injector);
    }

    @Override
    public String getTargetVersion() {
      return "test";
    }
  }

  @Before
  public void init() {
    EasyMockSupport easyMockSupport = new EasyMockSupport();
    injector = easyMockSupport.createNiceMock(Injector.class);
    ambariManagementController = easyMockSupport.createNiceMock(AmbariManagementController.class);
    clusters = easyMockSupport.createNiceMock(Clusters.class);
    cluster = easyMockSupport.createNiceMock(Cluster.class);
    hdfsService = easyMockSupport.createNiceMock(Service.class);
    coreService = easyMockSupport.createNiceMock(Service.class);
    coreClient = easyMockSupport.createNiceMock(ServiceComponent.class);
    coreClientHost1 = easyMockSupport.createNiceMock(ServiceComponentHost.class);
    coreClientHost2 = easyMockSupport.createNiceMock(ServiceComponentHost.class);
    repositoryVersion = easyMockSupport.createNiceMock(RepositoryVersionEntity.class);
    configHelper = easyMockSupport.createNiceMock(ConfigHelper.class);
    host1 = easyMockSupport.createNiceMock(Host.class);
    host2 = easyMockSupport.createNiceMock(Host.class);
  }

  @Test
  public void testEnsureCoreServiceForLegacyOdpCluster() throws Exception {
    StackId stackId = new StackId("ODP", "1.3");

    expect(injector.getInstance(AmbariManagementController.class)).andReturn(ambariManagementController).anyTimes();
    expect(injector.getInstance(ConfigHelper.class)).andReturn(configHelper).anyTimes();
    expect(ambariManagementController.getClusters()).andReturn(clusters).anyTimes();
    expect(clusters.getClusters()).andReturn(Collections.singletonMap("c1", cluster)).anyTimes();

    expect(cluster.getClusterName()).andReturn("c1").anyTimes();
    expect(cluster.getDesiredStackVersion()).andReturn(stackId).anyTimes();
    expect(cluster.getServices()).andReturn(Collections.singletonMap("HDFS", hdfsService)).anyTimes();
    expect(hdfsService.getDesiredRepositoryVersion()).andReturn(repositoryVersion).anyTimes();

    expect(cluster.addService(eq("CORE"), eq(repositoryVersion))).andReturn(coreService).once();
    expect(coreService.getDesiredRepositoryVersion()).andReturn(null).once();
    coreService.setDesiredRepositoryVersion(repositoryVersion);
    expectLastCall().once();
    expect(coreService.getDesiredState()).andReturn(State.INIT).once();
    coreService.setDesiredState(State.INSTALLED);
    expectLastCall().once();
    expect(coreService.getServiceComponents()).andReturn(Collections.emptyMap()).once();
    expect(coreService.addServiceComponent("CORE_CLIENT")).andReturn(coreClient).once();

    expect(coreClient.getDesiredRepositoryVersion()).andReturn(null).once();
    coreClient.setDesiredRepositoryVersion(repositoryVersion);
    expectLastCall().once();
    expect(coreClient.getDesiredState()).andReturn(State.INIT).once();
    coreClient.setDesiredState(State.INSTALLED);
    expectLastCall().once();
    expect(coreClient.getDesiredVersion()).andReturn("1.3.2.0").once();
    expect(coreClient.getServiceComponentHosts()).andReturn(Collections.emptyMap()).times(2);

    expect(cluster.getHosts()).andReturn(Arrays.asList(host1, host2)).once();
    expect(host1.getHostName()).andReturn("host1").once();
    expect(host2.getHostName()).andReturn("host2").once();

    expect(coreClient.addServiceComponentHost("host1")).andReturn(coreClientHost1).once();
    expect(coreClientHost1.getDesiredState()).andReturn(State.INIT).once();
    coreClientHost1.setDesiredState(State.INSTALLED);
    expectLastCall().once();
    expect(coreClientHost1.getState()).andReturn(State.INIT).once();
    coreClientHost1.setState(State.INSTALLED);
    expectLastCall().once();
    expect(coreClientHost1.getVersion()).andReturn(State.UNKNOWN.toString()).once();
    coreClientHost1.setVersion("1.3.2.0");
    expectLastCall().once();

    expect(coreClient.addServiceComponentHost("host2")).andReturn(coreClientHost2).once();
    expect(coreClientHost2.getDesiredState()).andReturn(State.INIT).once();
    coreClientHost2.setDesiredState(State.INSTALLED);
    expectLastCall().once();
    expect(coreClientHost2.getState()).andReturn(State.INIT).once();
    coreClientHost2.setState(State.INSTALLED);
    expectLastCall().once();
    expect(coreClientHost2.getVersion()).andReturn(State.UNKNOWN.toString()).once();
    coreClientHost2.setVersion("1.3.2.0");
    expectLastCall().once();

    Map<String, Map<String, String>> defaultConfigs = new LinkedHashMap<>();
    defaultConfigs.put("core-site", Collections.singletonMap("fs.defaultFS", "hdfs://legacy:8020"));
    defaultConfigs.put("core-env", Collections.singletonMap("core_filesystem_type", "HDFS"));
    expect(configHelper.getDefaultProperties(stackId, "CORE")).andReturn(defaultConfigs).once();
    expect(cluster.isConfigTypeExists("core-site")).andReturn(true).once();
    expect(cluster.isConfigTypeExists("core-env")).andReturn(false).once();

    Map<String, Map<String, String>> missingConfigs = Collections.singletonMap(
        "core-env", Collections.singletonMap("core_filesystem_type", "HDFS"));
    expect(configHelper.createConfigTypes(cluster, stackId, ambariManagementController, missingConfigs,
        "ambari-upgrade", CORE_UPGRADE_NOTE)).andReturn(true).once();

    expect(cluster.createServiceConfigVersion(eq("CORE"), eq("ambari-upgrade"), eq(CORE_UPGRADE_NOTE),
        isNull())).andReturn((ServiceConfigVersionResponse) null).once();

    replay(injector, ambariManagementController, clusters, cluster, hdfsService, coreService,
        coreClient, coreClientHost1, coreClientHost2, repositoryVersion, configHelper, host1, host2);

    FinalUpgradeCatalog finalUpgradeCatalog = new TestFinalUpgradeCatalog(injector);
    finalUpgradeCatalog.ensureCoreServiceForLegacyOdpClusters();

    verify(injector, ambariManagementController, clusters, cluster, hdfsService, coreService,
        coreClient, coreClientHost1, coreClientHost2, repositoryVersion, configHelper, host1, host2);
  }
}
