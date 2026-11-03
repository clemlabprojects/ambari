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
package org.apache.ambari.server.serveraction.ozone;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import org.apache.ambari.server.actionmanager.HostRoleCommand;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.agent.ExecutionCommand;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.security.credential.Credential;
import org.apache.ambari.server.security.credential.GenericKeyCredential;
import org.apache.ambari.server.security.encryption.CredentialStoreService;
import org.apache.ambari.server.security.encryption.CredentialStoreType;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.DesiredConfig;
import org.apache.ambari.server.state.PropertyInfo;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.StackId;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class OzoneS3SecretsServerActionTest extends EasyMockSupport {
  private static final String CLUSTER_NAME = "c1";
  private static final StackId STACK_ID = new StackId("ODP-1.3");
  private static final String ALIAS = "ozone.s3.secret.polaris-env.polaris_ozone_principal_secret";

  private OzoneS3SecretsServerAction action;
  private ConfigHelper configHelper;
  private CredentialStoreService credentialStoreService;
  private Cluster cluster;
  private Map<String, Service> services;
  private Map<String, DesiredConfig> desiredConfigs;
  private Capture<Map<String, Map<String, String>>> updates;
  private HostRoleCommand hostRoleCommand;

  @Before
  public void setUp() throws Exception {
    AmbariManagementController controller = createNiceMock(AmbariManagementController.class);
    Clusters clusters = createNiceMock(Clusters.class);
    configHelper = createMock(ConfigHelper.class);
    credentialStoreService = createMock(CredentialStoreService.class);
    cluster = createNiceMock(Cluster.class);
    services = new HashMap<>();
    services.put("POLARIS", createNiceMock(Service.class));
    desiredConfigs = new HashMap<>();
    updates = Capture.newInstance();
    hostRoleCommand = createNiceMock(HostRoleCommand.class);

    expect(controller.getClusters()).andReturn(clusters).anyTimes();
    expect(clusters.getCluster(CLUSTER_NAME)).andReturn(cluster).anyTimes();
    expect(cluster.getServices()).andReturn(services).anyTimes();
    expect(cluster.getDesiredStackVersion()).andReturn(STACK_ID).anyTimes();
    expect(cluster.getDesiredConfigs()).andReturn(desiredConfigs).anyTimes();

    action = new OzoneS3SecretsServerAction();
    setField(action, "controller", controller);
    setField(action, "configHelper", configHelper);
    setField(action, "credentialStoreService", credentialStoreService);
  }

  @Test
  public void testEnsureGeneratesAMissingSecret() throws Exception {
    services.put("OZONE", createNiceMock(Service.class));
    secretProperty("");
    expect(credentialStoreService.isInitialized(CredentialStoreType.PERSISTED)).andReturn(true).once();
    expect(credentialStoreService.getCredential(CLUSTER_NAME, ALIAS, CredentialStoreType.PERSISTED)).andReturn(null).once();
    Capture<Credential> stored = Capture.newInstance();
    credentialStoreService.setCredential(eq(CLUSTER_NAME), eq(ALIAS), capture(stored), eq(CredentialStoreType.PERSISTED));
    expectLastCall().once();
    expectConfigUpdate();
    replayAll();

    Assert.assertEquals(HostRoleStatus.COMPLETED.name(), execute(OzoneS3SecretsServerAction.OPERATION_ENSURE).getStatus());
    verifyAll();

    String secret = updates.getValue().get("polaris-env").get("polaris_ozone_principal_secret");
    Assert.assertEquals(40, secret.length());
    Assert.assertEquals(secret, new String(((GenericKeyCredential) stored.getValue()).getKey()));
  }

  @Test
  public void testEnsureKeepsASecretAlreadySet() throws Exception {
    services.put("OZONE", createNiceMock(Service.class));
    secretProperty("operator-secret");
    expect(credentialStoreService.isInitialized(CredentialStoreType.PERSISTED)).andReturn(true).once();
    Capture<Credential> stored = Capture.newInstance();
    credentialStoreService.setCredential(eq(CLUSTER_NAME), eq(ALIAS), capture(stored), eq(CredentialStoreType.PERSISTED));
    expectLastCall().once();
    replayAll();

    execute(OzoneS3SecretsServerAction.OPERATION_ENSURE);
    verifyAll();

    Assert.assertEquals("operator-secret", new String(((GenericKeyCredential) stored.getValue()).getKey()));
  }

  @Test
  public void testEnsureRestoresTheStoredSecret() throws Exception {
    services.put("OZONE", createNiceMock(Service.class));
    secretProperty("");
    expect(credentialStoreService.isInitialized(CredentialStoreType.PERSISTED)).andReturn(true).once();
    expect(credentialStoreService.getCredential(CLUSTER_NAME, ALIAS, CredentialStoreType.PERSISTED))
      .andReturn(new GenericKeyCredential("stored-secret".toCharArray())).once();
    credentialStoreService.setCredential(eq(CLUSTER_NAME), eq(ALIAS), anyObject(Credential.class), eq(CredentialStoreType.PERSISTED));
    expectLastCall().once();
    expectConfigUpdate();
    replayAll();

    execute(OzoneS3SecretsServerAction.OPERATION_ENSURE);
    verifyAll();

    Assert.assertEquals("stored-secret", updates.getValue().get("polaris-env").get("polaris_ozone_principal_secret"));
  }

  @Test
  public void testRotateReplacesTheSecretWithoutCredentialStore() throws Exception {
    services.put("OZONE", createNiceMock(Service.class));
    secretProperty("old-secret");
    expect(credentialStoreService.isInitialized(CredentialStoreType.PERSISTED)).andReturn(false).once();
    expectConfigUpdate();
    replayAll();

    execute(OzoneS3SecretsServerAction.OPERATION_ROTATE);
    verifyAll();

    String secret = updates.getValue().get("polaris-env").get("polaris_ozone_principal_secret");
    Assert.assertFalse("old-secret".equals(secret));
    Assert.assertEquals(40, secret.length());
  }

  @Test
  public void testNothingWithoutOzone() throws Exception {
    replayAll();

    Assert.assertEquals(HostRoleStatus.COMPLETED.name(), execute(OzoneS3SecretsServerAction.OPERATION_ROTATE).getStatus());
    verifyAll();
  }

  private void secretProperty(String value) throws Exception {
    PropertyInfo property = new PropertyInfo();
    property.setName("polaris_ozone_principal_secret");
    property.setFilename("polaris-env.xml");
    Map<PropertyInfo, String> properties = new HashMap<>();
    properties.put(property, value);
    expect(configHelper.getPropertiesWithPropertyType(STACK_ID, PropertyInfo.PropertyType.OZONE_S3_SECRET, cluster,
      desiredConfigs)).andReturn(properties).once();
  }

  @SuppressWarnings("unchecked")
  private void expectConfigUpdate() throws Exception {
    configHelper.updateBulkConfigType(eq(cluster), eq(STACK_ID), anyObject(AmbariManagementController.class),
      anyObject(Iterable.class), capture(updates), anyObject(Map.class), anyString(), anyString());
    expectLastCall().once();
  }

  private CommandReport execute(String operation) throws Exception {
    Map<String, String> commandParams = new HashMap<>();
    commandParams.put(OzoneS3SecretsServerAction.OPERATION, operation);
    commandParams.put(OzoneS3SecretsServerAction.AUTHENTICATED_USER_NAME, "admin");
    ExecutionCommand executionCommand = new ExecutionCommand();
    executionCommand.setClusterName(CLUSTER_NAME);
    executionCommand.setCommandParams(commandParams);
    action.setExecutionCommand(executionCommand);
    action.setHostRoleCommand(hostRoleCommand);
    return action.execute(null);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
