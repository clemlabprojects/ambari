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

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.server.orm.dao.ArtifactDAO;
import org.apache.ambari.server.orm.entities.ArtifactEntity;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.PropertyInfo;
import org.apache.ambari.server.state.kerberos.KerberosDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosDescriptorFactory;
import org.easymock.Capture;
import org.junit.Test;

import com.google.gson.Gson;
import com.google.inject.Injector;

public class UpgradeCatalog282Test {

  private static final String DESCRIPTOR = "{\"services\": [{\"name\": \"OZONE\", \"components\": ["
      + "{\"name\": \"OZONE_DATANODE\", \"identities\": [{\"name\": \"ozone_datanode_ozone\","
      + " \"principal\": {\"value\": \"%s\", \"type\": \"service\"}}]}]}]}";

  private final UpgradeCatalog282 underTest = new UpgradeCatalog282(createNiceMock(Injector.class));

  @Test
  public void testPreviousStackPrincipalMovesToHdds() throws Exception {
    KerberosDescriptor descriptor = descriptor("dn/_HOST@${realm}");

    assertTrue(underTest.updateOzoneDataNodePrincipal(descriptor));
    assertEquals("hdds/_HOST@${realm}", principal(descriptor));
  }

  @Test
  public void testCustomizedPrincipalIsKept() throws Exception {
    KerberosDescriptor descriptor = descriptor("ozonedn/_HOST@${realm}");

    assertFalse(underTest.updateOzoneDataNodePrincipal(descriptor));
    assertEquals("ozonedn/_HOST@${realm}", principal(descriptor));
  }

  @Test
  public void testDescriptorWithoutOzoneIsUnchanged() throws Exception {
    assertFalse(underTest.updateOzoneDataNodePrincipal(
        new KerberosDescriptorFactory().createInstance("{\"services\": [{\"name\": \"HDFS\"}]}")));
  }

  @Test
  public void testArtifactIsStoredOnlyWhenChanged() throws Exception {
    ArtifactDAO artifactDAO = createStrictMock(ArtifactDAO.class);
    ArtifactEntity changed = new ArtifactEntity();
    changed.setArtifactData(map("dn/_HOST@${realm}"));
    ArtifactEntity unchanged = new ArtifactEntity();
    unchanged.setArtifactData(map("hdds/_HOST@${realm}"));
    Capture<ArtifactEntity> merged = newCapture();
    expect(artifactDAO.merge(capture(merged))).andReturn(changed).once();
    replay(artifactDAO);

    underTest.updateKerberosDescriptorArtifact(artifactDAO, changed);
    underTest.updateKerberosDescriptorArtifact(artifactDAO, unchanged);

    verify(artifactDAO);
    assertEquals("hdds/_HOST@${realm}",
        principal(new KerberosDescriptorFactory().createInstance(merged.getValue().getArtifactData())));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(String principal) {
    return new Gson().fromJson(String.format(DESCRIPTOR, principal), Map.class);
  }

  private static KerberosDescriptor descriptor(String principal) throws Exception {
    return new KerberosDescriptorFactory().createInstance(map(principal));
  }

  private static String principal(KerberosDescriptor descriptor) {
    return descriptor.getService("OZONE").getComponent("OZONE_DATANODE")
        .getIdentity("ozone_datanode_ozone").getPrincipalDescriptor().getValue();
  }

  /** Records the properties removed instead of writing a new configuration. */
  private static class RecordingCatalog extends UpgradeCatalog282 {
    final Set<String> removed = new HashSet<>();
    RecordingCatalog() { super(createNiceMock(Injector.class)); }
    @Override
    protected void removeConfigurationPropertiesFromCluster(Cluster cluster, String configType, Set<String> properties) {
      properties.forEach(p -> removed.add(configType + "/" + p));
    }
  }

  private static Cluster clusterWithNifiProperties(Map<String, String> properties) {
    Config config = createNiceMock(Config.class);
    expect(config.getProperties()).andReturn(properties).anyTimes();
    Cluster cluster = createNiceMock(Cluster.class);
    expect(cluster.getDesiredConfigByType("nifi-properties")).andReturn(config).anyTimes();
    expect(cluster.getClusterName()).andReturn("c1").anyTimes();
    replay(config, cluster);
    return cluster;
  }

  private static PropertyInfo property(String file, String name) {
    PropertyInfo p = new PropertyInfo();
    p.setFilename(file);
    p.setName(name);
    return p;
  }

  @Test
  public void testNifiPropertiesKeyIsRemovedWhenTheStackNoLongerDefinesIt() throws Exception {
    RecordingCatalog catalog = new RecordingCatalog();
    Map<String, String> props = new HashMap<>();
    props.put("nifi.sensitive.props.key", "NifiKey1");
    props.put("nifi.sensitive.props.algorithm", "NIFI_PBKDF2_AES_GCM_256");
    Set<PropertyInfo> stack = new HashSet<>();
    stack.add(property("nifi-ambari-config.xml", "nifi.sensitive.props.key"));
    stack.add(property("nifi-properties.xml", "nifi.sensitive.props.algorithm"));
    assertTrue(catalog.removeUnusedNifiSensitiveKey(clusterWithNifiProperties(props), stack));
    assertEquals(Collections.singleton("nifi-properties/nifi.sensitive.props.key"), catalog.removed);
  }

  @Test
  public void testNifiPropertiesKeyIsKeptWhenTheStackStillDefinesIt() throws Exception {
    RecordingCatalog catalog = new RecordingCatalog();
    Set<PropertyInfo> stack = Collections.singleton(property("nifi-properties.xml", "nifi.sensitive.props.key"));
    assertFalse(catalog.removeUnusedNifiSensitiveKey(
        clusterWithNifiProperties(Collections.singletonMap("nifi.sensitive.props.key", "k")), stack));
    assertTrue(catalog.removed.isEmpty());
  }

  @Test
  public void testNothingToRemoveWithoutAStoredKey() throws Exception {
    RecordingCatalog catalog = new RecordingCatalog();
    assertFalse(catalog.removeUnusedNifiSensitiveKey(
        clusterWithNifiProperties(Collections.singletonMap("nifi.sensitive.props.algorithm", "x")), Collections.emptySet()));
    Cluster withoutNifi = createNiceMock(Cluster.class);
    replay(withoutNifi);
    assertFalse(catalog.removeUnusedNifiSensitiveKey(withoutNifi, Collections.emptySet()));
    assertTrue(catalog.removed.isEmpty());
  }
}
