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

import java.util.Map;

import org.apache.ambari.server.orm.dao.ArtifactDAO;
import org.apache.ambari.server.orm.entities.ArtifactEntity;
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
}
