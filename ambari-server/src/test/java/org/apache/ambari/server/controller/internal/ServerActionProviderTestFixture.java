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

package org.apache.ambari.server.controller.internal;

import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.server.ClusterNotFoundException;
import org.apache.ambari.server.actionmanager.ActionManager;
import org.apache.ambari.server.actionmanager.RequestFactory;
import org.apache.ambari.server.actionmanager.Stage;
import org.apache.ambari.server.actionmanager.StageFactory;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.ExecuteActionRequest;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.topology.TopologyManager;
import org.apache.ambari.server.utils.StageUtils;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;

/**
 * Mocks what a resource provider scheduling a single Ambari server action touches: the cluster
 * {@value #CLUSTER_NAME}, the action manager, the stage factory and the static helpers used to
 * describe the cluster's hosts.
 * <p/>
 * The action manager is a strict mock: a test that does not call {@link #expectOneRequestScheduled()}
 * fails if anything is scheduled.
 */
class ServerActionProviderTestFixture {

  static final String CLUSTER_NAME = "c1";

  /**
   * The cluster resource id {@code TestAuthenticationFactory} grants its cluster-level roles on.
   */
  static final long CLUSTER_RESOURCE_ID = 4L;

  final AmbariManagementController controller = createNiceMock(AmbariManagementController.class);
  final Clusters clusters = createNiceMock(Clusters.class);
  final Cluster cluster = createNiceMock(Cluster.class);
  final ActionManager actionManager = createMock(ActionManager.class);
  final StageFactory stageFactory = createNiceMock(StageFactory.class);
  final Stage stage = createNiceMock(Stage.class);
  private final TopologyManager topologyManager = createNiceMock(TopologyManager.class);
  private final Configuration configuration = createNiceMock(Configuration.class);

  ServerActionProviderTestFixture() throws Exception {
    expect(controller.getClusters()).andReturn(clusters).anyTimes();
    expect(controller.getActionManager()).andReturn(actionManager).anyTimes();

    expect(clusters.getCluster(CLUSTER_NAME)).andReturn(cluster).anyTimes();
    expect(clusters.getCluster("unknown")).andThrow(new ClusterNotFoundException("unknown")).anyTimes();

    expect(cluster.getClusterName()).andReturn(CLUSTER_NAME).anyTimes();
    expect(cluster.getClusterId()).andReturn(2L).anyTimes();
    expect(cluster.getResourceId()).andReturn(CLUSTER_RESOURCE_ID).anyTimes();
    expect(cluster.getHosts()).andReturn(Collections.emptyList()).anyTimes();
    expect(cluster.getServices()).andReturn(Collections.emptyMap()).anyTimes();

    expect(stageFactory.createNew(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString()))
        .andReturn(stage).anyTimes();

    expect(topologyManager.getPendingHostComponents()).andReturn(Collections.emptyMap()).anyTimes();
    expect(configuration.getApiSSLAuthentication()).andReturn(false).anyTimes();
    expect(configuration.getClientApiPort()).andReturn(8080).anyTimes();
  }

  /**
   * Expects exactly one Ambari request to be scheduled.
   */
  @SuppressWarnings("unchecked")
  void expectOneRequestScheduled() throws Exception {
    expect(actionManager.getNextRequestId()).andReturn(42L);
    actionManager.sendActions((List<Stage>) anyObject(), anyString(), anyObject(ExecuteActionRequest.class));
    expectLastCall();
  }

  /**
   * Replays the mocks and injects the statics of the provider class (and of {@link StageUtils}).
   *
   * @param providerClass the {@code @StaticallyInject} provider class
   * @param extraModule   bindings for further static members of the provider, may be null
   */
  void replayAndInject(Class<?> providerClass, AbstractModule extraModule) {
    replay(controller, clusters, cluster, actionManager, stageFactory, stage, topologyManager, configuration);
    StageUtils.setTopologyManager(topologyManager);
    StageUtils.setConfiguration(configuration);
    Guice.createInjector(new AbstractModule() {
      @Override
      protected void configure() {
        bind(StageFactory.class).toInstance(stageFactory);
        bind(RequestFactory.class).toInstance(createNiceMock(RequestFactory.class));
        if (extraModule != null) {
          install(extraModule);
        }
        requestStaticInjection(providerClass);
      }
    });
  }

  void verifyScheduling() {
    verify(actionManager);
  }

  /**
   * A create request carrying one item.
   */
  static Request createRequest(Map<String, Object> properties) {
    Set<Map<String, Object>> items = new HashSet<>();
    items.add(properties);
    return PropertyHelper.getCreateRequest(items, null);
  }
}
