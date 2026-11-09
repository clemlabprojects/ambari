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
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.stack.PrereqCheckStatus;
import org.apache.ambari.server.state.stack.PrerequisiteCheck;
import org.apache.ambari.server.state.stack.upgrade.UpgradeType;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class NifiTwoUpgradeCheckTest {

  private final Clusters clusters = Mockito.mock(Clusters.class);
  private final Cluster cluster = Mockito.mock(Cluster.class);
  private final Service nifi = Mockito.mock(Service.class);
  private final ServiceComponentHost node = Mockito.mock(ServiceComponentHost.class);
  private final Map<String, ServiceComponent> components = new HashMap<>();
  private final Map<String, String> ambariConfig = new HashMap<>();
  private final Map<String, String> nifiProperties = new HashMap<>();
  private final NifiTwoUpgradeCheck check = new NifiTwoUpgradeCheck();

  @Before
  public void setup() throws Exception {
    check.clustersProvider = () -> clusters;
    Mockito.when(clusters.getCluster("c1")).thenReturn(cluster);
    Mockito.when(cluster.getServices()).thenReturn(Collections.singletonMap("NIFI", nifi));
    Mockito.when(nifi.getServiceComponents()).thenReturn(components);
    source("1.3.1.0-306");
    components.put("NIFI_MASTER", component(Collections.singletonMap("worker11", node)));
    Mockito.when(node.getHostName()).thenReturn("worker11");
    Config ambari = Mockito.mock(Config.class);
    Mockito.when(ambari.getProperties()).thenReturn(ambariConfig);
    Mockito.when(cluster.getDesiredConfigByType("nifi-ambari-config")).thenReturn(ambari);
    Config properties = Mockito.mock(Config.class);
    Mockito.when(properties.getProperties()).thenReturn(nifiProperties);
    Mockito.when(cluster.getDesiredConfigByType("nifi-properties")).thenReturn(properties);
    // a cluster prepared for NiFi 2
    ambariConfig.put("nifi.sensitive.props.key", "0123456789abcdef");
    nifiProperties.put("nifi.sensitive.props.algorithm", "NIFI_PBKDF2_AES_GCM_256");
  }

  private ServiceComponent component(Map<String, ServiceComponentHost> hosts) {
    ServiceComponent component = Mockito.mock(ServiceComponent.class);
    Mockito.when(component.getServiceComponentHosts()).thenReturn(hosts);
    return component;
  }

  private void source(String version) {
    RepositoryVersionEntity repository = new RepositoryVersionEntity();
    repository.setVersion(version);
    Mockito.when(nifi.getDesiredRepositoryVersion()).thenReturn(repository);
  }

  private PrerequisiteCheck perform(UpgradeType upgradeType, String target) throws Exception {
    PrereqCheckRequest request = new PrereqCheckRequest("c1", upgradeType);
    RepositoryVersionEntity repository = new RepositoryVersionEntity();
    repository.setVersion(target);
    request.setTargetRepositoryVersion(repository);
    PrerequisiteCheck result = new PrerequisiteCheck(null, null);
    check.perform(result, request);
    return result;
  }

  private PrerequisiteCheck express() throws Exception {
    return perform(UpgradeType.NON_ROLLING, "1.3.2.0-56");
  }

  @Test
  public void testPassesAPreparedCluster() throws Exception {
    Assert.assertEquals(PrereqCheckStatus.PASS, express().getStatus());
  }

  @Test
  public void testIgnoresUpgradesWithoutTheNifi2Switch() throws Exception {
    ambariConfig.put("nifi.sensitive.props.key", "short");
    // NiFi 1 to NiFi 1, and NiFi 2 to NiFi 2
    Assert.assertEquals(PrereqCheckStatus.PASS, perform(UpgradeType.ROLLING, "1.3.1.0-320").getStatus());
    source("1.3.2.0-55");
    Assert.assertEquals(PrereqCheckStatus.PASS, perform(UpgradeType.ROLLING, "1.3.2.0-56").getStatus());
  }

  @Test
  public void testFailsARollingUpgrade() throws Exception {
    PrerequisiteCheck result = perform(UpgradeType.ROLLING, "1.3.2.0-56");
    Assert.assertEquals(PrereqCheckStatus.FAIL, result.getStatus());
    Assert.assertTrue(result.getFailReason().contains("use Express mode"));
  }

  @Test
  public void testFailsANifiOlderThan127() throws Exception {
    source("1.2.4.0-115");
    Assert.assertTrue(express().getFailReason().contains("upgrade ODP 1.2.4.0-115 to ODP 1.3.1.0 first"));
  }

  @Test
  public void testFailsAShortKey() throws Exception {
    ambariConfig.put("nifi.sensitive.props.key", "nifikey");
    PrerequisiteCheck result = express();
    Assert.assertEquals(PrereqCheckStatus.FAIL, result.getStatus());
    Assert.assertTrue(result.getFailReason().contains("at least 12 characters"));
  }

  @Test
  public void testFailsANifi1Algorithm() throws Exception {
    nifiProperties.put("nifi.sensitive.props.algorithm", "PBEWITHMD5AND256BITAES-CBC-OPENSSL");
    Assert.assertTrue(express().getFailReason().contains("does not support PBEWITHMD5AND256BITAES-CBC-OPENSSL"));
    // an empty algorithm is the NiFi 1 default
    nifiProperties.put("nifi.sensitive.props.algorithm", "");
    Assert.assertTrue(express().getFailReason().contains("does not support PBEWITHMD5AND256BITAES-CBC-OPENSSL"));
  }

  @Test
  public void testFailsWithTheCertificateAuthority() throws Exception {
    components.put("NIFI_CA", component(Collections.singletonMap("worker13", Mockito.mock(ServiceComponentHost.class))));
    Assert.assertTrue(express().getFailReason().contains("delete the NiFi Certificate Authority component"));
  }

  @Test
  public void testFailsAPendingRestart() throws Exception {
    Mockito.when(node.isRestartRequired()).thenReturn(true);
    PrerequisiteCheck result = express();
    Assert.assertTrue(result.getFailReason().contains("Restart NiFi on worker11"));
    Assert.assertTrue(result.getFailedOn().contains("worker11"));
  }

  @Test
  public void testPassesWithoutNifi() throws Exception {
    Mockito.when(cluster.getServices()).thenReturn(Collections.emptyMap());
    Assert.assertEquals(PrereqCheckStatus.PASS, perform(UpgradeType.ROLLING, "1.3.2.0-56").getStatus());
  }
}
