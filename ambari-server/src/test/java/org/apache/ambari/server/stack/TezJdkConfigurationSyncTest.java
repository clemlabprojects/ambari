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

package org.apache.ambari.server.stack;

import static org.apache.ambari.server.stack.TezJdkConfigurationSync.HEAP_DUMP_PLACEHOLDER;
import static org.apache.ambari.server.stack.TezJdkConfigurationSync.TEZ_AM_BASE_OPTS;
import static org.apache.ambari.server.stack.TezJdkConfigurationSync.TEZ_ENV;
import static org.apache.ambari.server.stack.TezJdkConfigurationSync.TEZ_TASK_BASE_OPTS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.StackId;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.junit.Test;

public class TezJdkConfigurationSyncTest extends EasyMockSupport {

  @Test
  public void updatesWhenManagedDefaultsAndJavaChanges() throws Exception {
    Clusters clusters = createMock(Clusters.class);
    Cluster cluster = createMock(Cluster.class);
    Config tezConfig = createMock(Config.class);
    Service tezService = createMock(Service.class);
    AmbariManagementController controller = createMock(AmbariManagementController.class);
    Configuration configuration = createMock(Configuration.class);
    Config newConfig = createMock(Config.class);

    Map<String, Cluster> clusterMap = Collections.singletonMap("c1", cluster);
    EasyMock.expect(clusters.getClusters()).andReturn(clusterMap);

    EasyMock.expect(configuration.getStackJavaVersion()).andReturn("11");

    EasyMock.expect(cluster.getServices()).andReturn(Collections.singletonMap("TEZ", tezService));
    EasyMock.expect(cluster.getDesiredConfigByType(TEZ_ENV)).andReturn(tezConfig);
    EasyMock.expect(cluster.getClusterName()).andReturn("c1").anyTimes();

    String oldDefault = TezJdkConfigurationSync.buildTezOptsForJavaMajor(8, "");
    Map<String, String> currentProps = tezProps(oldDefault);
    EasyMock.expect(tezConfig.getProperties()).andReturn(currentProps).anyTimes();
    EasyMock.expect(tezConfig.getPropertiesAttributes()).andReturn(Collections.emptyMap()).anyTimes();

    StackId stackId = new StackId("ODP", "1.0");
    EasyMock.expect(cluster.getDesiredStackVersion()).andReturn(stackId);

    Capture<Map<String, String>> propsCapture = EasyMock.newCapture();
    EasyMock.expect(controller.createConfig(EasyMock.eq(cluster), EasyMock.eq(stackId), EasyMock.eq(TEZ_ENV),
        EasyMock.capture(propsCapture), EasyMock.anyString(), EasyMock.anyObject(Map.class))).andReturn(newConfig);

    EasyMock.expect(cluster.addDesiredConfig(EasyMock.anyString(),
        EasyMock.<Set<Config>>eq(Collections.singleton(newConfig)), EasyMock.anyString())).andReturn(null);

    replayAll();

    new TezJdkConfigurationSync(clusters, controller, configuration).process();

    verifyAll();

    String desired = TezJdkConfigurationSync.buildTezOptsForJavaMajor(11, "");
    assertEquals(desired, propsCapture.getValue().get(TEZ_AM_BASE_OPTS));
    assertTrue(propsCapture.getValue().containsKey(TEZ_TASK_BASE_OPTS));
  }

  @Test
  public void noUpdateWhenAlreadyAligned() throws Exception {
    Clusters clusters = createMock(Clusters.class);
    Cluster cluster = createMock(Cluster.class);
    Config tezConfig = createMock(Config.class);
    Service tezService = createMock(Service.class);
    AmbariManagementController controller = createMock(AmbariManagementController.class);
    Configuration configuration = createMock(Configuration.class);

    Map<String, Cluster> clusterMap = Collections.singletonMap("c1", cluster);
    EasyMock.expect(clusters.getClusters()).andReturn(clusterMap);

    EasyMock.expect(configuration.getStackJavaVersion()).andReturn("8");

    EasyMock.expect(cluster.getServices()).andReturn(Collections.singletonMap("TEZ", tezService));
    EasyMock.expect(cluster.getDesiredConfigByType(TEZ_ENV)).andReturn(tezConfig);
    EasyMock.expect(cluster.getClusterName()).andReturn("c1").anyTimes();

    String desired = TezJdkConfigurationSync.buildTezOptsForJavaMajor(8, "");
    Map<String, String> currentProps = tezProps(desired);
    EasyMock.expect(tezConfig.getProperties()).andReturn(currentProps).anyTimes();
    EasyMock.expect(tezConfig.getPropertiesAttributes()).andStubReturn(Collections.emptyMap());

    replayAll();

    new TezJdkConfigurationSync(clusters, controller, configuration).process();

    verifyAll();
  }

  private Map<String, String> tezProps(String value) {
    Map<String, String> props = new HashMap<>();
    props.put(TEZ_AM_BASE_OPTS, value);
    props.put(TEZ_TASK_BASE_OPTS, value);
    return props;
  }
}
