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
package org.apache.ambari.server.serveraction.oidc;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.server.agent.ExecutionCommand;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.security.credential.Credential;
import org.apache.ambari.server.security.credential.GenericKeyCredential;
import org.apache.ambari.server.security.credential.PrincipalKeyCredential;
import org.apache.ambari.server.security.encryption.CredentialStoreService;
import org.apache.ambari.server.security.encryption.CredentialStoreType;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.kerberos.VariableReplacementHelper;
import org.apache.ambari.server.state.oidc.OidcClientDescriptor;
import org.apache.ambari.server.state.oidc.OidcDescriptor;
import org.apache.ambari.server.state.oidc.OidcDescriptorFactory;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Test;

import junit.framework.Assert;

public class ConfigureOidcServerActionTest extends EasyMockSupport {
  private static final String CLUSTER_NAME = "c1";
  private static final StackId STACK_ID = new StackId("ODP-1.3");

  private ConfigureOidcServerAction action;
  private AmbariManagementController controller;
  private ConfigHelper configHelper;
  private CredentialStoreService credentialStoreService;
  private OidcOperationHandlerFactory oidcOperationHandlerFactory;
  private OidcOperationHandler handler;
  private Clusters clusters;
  private Cluster cluster;
  private AmbariMetaInfo metaInfo;

  @Before
  public void setUp() {
    controller = createNiceMock(AmbariManagementController.class);
    configHelper = createMock(ConfigHelper.class);
    credentialStoreService = createMock(CredentialStoreService.class);
    oidcOperationHandlerFactory = createMock(OidcOperationHandlerFactory.class);
    handler = createMock(OidcOperationHandler.class);
    clusters = createNiceMock(Clusters.class);
    cluster = createNiceMock(Cluster.class);
    metaInfo = createNiceMock(AmbariMetaInfo.class);

    action = new ConfigureOidcServerAction();
    setField(action, "controller", controller);
    setField(action, "configHelper", configHelper);
    setField(action, "credentialStoreService", credentialStoreService);
    setField(action, "oidcOperationHandlerFactory", oidcOperationHandlerFactory);
    setField(action, "variableReplacementHelper", new VariableReplacementHelper());
  }

  @Test
  public void testConfigureOidcAppliesPolarisDescriptor() throws Exception {
    Map<String, Map<String, String>> existingConfigurations = new HashMap<>();
    Map<String, String> oidcEnv = new HashMap<>();
    oidcEnv.put(ConfigureOidcServerAction.OIDC_PROVIDER, "keycloak");
    oidcEnv.put(ConfigureOidcServerAction.OIDC_ADMIN_URL, "https://keycloak.example.com");
    oidcEnv.put(ConfigureOidcServerAction.OIDC_REALM, "example");
    oidcEnv.put(ConfigureOidcServerAction.OIDC_ADMIN_CLIENT_SECRET, "admin-secret");
    existingConfigurations.put(ConfigureOidcServerAction.OIDC_ENV, oidcEnv);

    Map<String, String> polarisAppProps = new HashMap<>();
    polarisAppProps.put("polaris.authentication.type", "external");
    existingConfigurations.put("polaris-application-properties", polarisAppProps);

    OidcDescriptor descriptor = new OidcDescriptorFactory().createInstance(
      new File("src/main/resources/stacks/ODP/1.3/services/POLARIS/oidc.json"));

    Map<String, Service> services = new HashMap<>();
    services.put("POLARIS", createNiceMock(Service.class));

    expect(controller.getClusters()).andReturn(clusters).anyTimes();
    expect(clusters.getCluster(CLUSTER_NAME)).andReturn(cluster).anyTimes();
    expect(controller.getAmbariMetaInfo()).andReturn(metaInfo).anyTimes();
    expect(metaInfo.getOidcDescriptor("ODP", "1.3")).andReturn(descriptor).anyTimes();
    expect(controller.getAuthName()).andReturn("admin").anyTimes();

    expect(cluster.getClusterName()).andReturn(CLUSTER_NAME).anyTimes();
    expect(cluster.getDesiredStackVersion()).andReturn(STACK_ID).anyTimes();
    expect(cluster.getServices()).andReturn(services).anyTimes();

    expect(configHelper.calculateExistingConfigurations(controller, cluster, null, null))
      .andReturn(existingConfigurations).once();

    PrincipalKeyCredential adminCredential = new PrincipalKeyCredential("admin", "password");
    expect(credentialStoreService.getCredential(CLUSTER_NAME,
      ConfigureOidcServerAction.OIDC_ADMIN_CREDENTIAL_ALIAS)).andReturn(adminCredential).once();

    expect(oidcOperationHandlerFactory.getHandler("keycloak")).andReturn(handler).once();
    handler.open(eq(adminCredential), org.easymock.EasyMock.anyObject(OidcProviderConfiguration.class));
    expectLastCall().once();

    Capture<OidcClientDescriptor> clientCapture = Capture.newInstance();
    expect(handler.ensureClient(capture(clientCapture), eq("example")))
      .andReturn(new OidcClientResult(CLUSTER_NAME + "-polaris", "internal-id", "secret")).once();
    handler.close();
    expectLastCall().once();

    Capture<Credential> credentialCapture = Capture.newInstance();
    credentialStoreService.setCredential(eq(CLUSTER_NAME), eq("polaris.oidc.client.secret"),
      capture(credentialCapture), eq(CredentialStoreType.PERSISTED));
    expectLastCall().once();

    Capture<Iterable<String>> configTypesCapture = Capture.newInstance();
    Capture<Map<String, Map<String, String>>> updatesCapture = Capture.newInstance();
    Capture<Map<String, Collection<String>>> removalsCapture = Capture.newInstance();
    Capture<String> userCapture = Capture.newInstance();
    Capture<String> noteCapture = Capture.newInstance();

    configHelper.updateBulkConfigType(eq(cluster), eq(STACK_ID), eq(controller),
      capture(configTypesCapture), capture(updatesCapture), capture(removalsCapture),
      capture(userCapture), capture(noteCapture));
    expectLastCall().once();

    replayAll();

    ExecutionCommand executionCommand = new ExecutionCommand();
    executionCommand.setClusterName(CLUSTER_NAME);
    executionCommand.setCommandParams(new HashMap<>());
    action.setExecutionCommand(executionCommand);
    action.execute(null);

    verifyAll();

    OidcClientDescriptor resolvedClient = clientCapture.getValue();
    Assert.assertEquals(CLUSTER_NAME + "-polaris", resolvedClient.getClientId());
    Assert.assertEquals("example", resolvedClient.getRealm());

    Credential storedCredential = credentialCapture.getValue();
    Assert.assertTrue(storedCredential instanceof GenericKeyCredential);
    Assert.assertEquals("secret", new String(((GenericKeyCredential) storedCredential).getKey()));

    Set<String> configTypes = new HashSet<>();
    for (String type : configTypesCapture.getValue()) {
      configTypes.add(type);
    }
    Assert.assertTrue(configTypes.contains("polaris-application-properties"));

    Map<String, String> appUpdates = updatesCapture.getValue().get("polaris-application-properties");
    Assert.assertNotNull(appUpdates);
    Assert.assertEquals(CLUSTER_NAME + "-polaris", appUpdates.get("quarkus.oidc.client-id"));
    Assert.assertEquals("secret", appUpdates.get("quarkus.oidc.credentials.secret"));
    Assert.assertEquals("https://keycloak.example.com/realms/example",
      appUpdates.get("quarkus.oidc.auth-server-url"));
    Assert.assertEquals("service", appUpdates.get("quarkus.oidc.application-type"));
    Assert.assertEquals("true", appUpdates.get("quarkus.oidc.tenant-enabled"));
    Assert.assertEquals("admin", userCapture.getValue());
  }

  @Test
  public void testConfigureOidcSkipsWhenPolarisInternalAuth() throws Exception {
    Map<String, Map<String, String>> existingConfigurations = new HashMap<>();
    Map<String, String> oidcEnv = new HashMap<>();
    oidcEnv.put(ConfigureOidcServerAction.OIDC_PROVIDER, "keycloak");
    oidcEnv.put(ConfigureOidcServerAction.OIDC_ADMIN_URL, "https://keycloak.example.com");
    oidcEnv.put(ConfigureOidcServerAction.OIDC_REALM, "example");
    oidcEnv.put(ConfigureOidcServerAction.OIDC_ADMIN_CLIENT_SECRET, "admin-secret");
    existingConfigurations.put(ConfigureOidcServerAction.OIDC_ENV, oidcEnv);

    Map<String, String> polarisAppProps = new HashMap<>();
    polarisAppProps.put("polaris.authentication.type", "internal");
    existingConfigurations.put("polaris-application-properties", polarisAppProps);

    OidcDescriptor descriptor = new OidcDescriptorFactory().createInstance(
      new File("src/main/resources/stacks/ODP/1.3/services/POLARIS/oidc.json"));

    Map<String, Service> services = new HashMap<>();
    services.put("POLARIS", createNiceMock(Service.class));

    expect(controller.getClusters()).andReturn(clusters).anyTimes();
    expect(clusters.getCluster(CLUSTER_NAME)).andReturn(cluster).anyTimes();
    expect(controller.getAmbariMetaInfo()).andReturn(metaInfo).anyTimes();
    expect(metaInfo.getOidcDescriptor("ODP", "1.3")).andReturn(descriptor).anyTimes();
    expect(controller.getAuthName()).andReturn("admin").anyTimes();

    expect(cluster.getClusterName()).andReturn(CLUSTER_NAME).anyTimes();
    expect(cluster.getDesiredStackVersion()).andReturn(STACK_ID).anyTimes();
    expect(cluster.getServices()).andReturn(services).anyTimes();

    expect(configHelper.calculateExistingConfigurations(controller, cluster, null, null))
      .andReturn(existingConfigurations).once();

    PrincipalKeyCredential adminCredential = new PrincipalKeyCredential("admin", "password");
    expect(credentialStoreService.getCredential(CLUSTER_NAME,
      ConfigureOidcServerAction.OIDC_ADMIN_CREDENTIAL_ALIAS)).andReturn(adminCredential).once();

    expect(oidcOperationHandlerFactory.getHandler("keycloak")).andReturn(handler).once();
    handler.open(eq(adminCredential), org.easymock.EasyMock.anyObject(OidcProviderConfiguration.class));
    expectLastCall().once();
    handler.close();
    expectLastCall().once();

    replayAll();

    ExecutionCommand executionCommand = new ExecutionCommand();
    executionCommand.setClusterName(CLUSTER_NAME);
    executionCommand.setCommandParams(new HashMap<>());
    action.setExecutionCommand(executionCommand);
    action.execute(null);

    verifyAll();
  }

  private static void setField(Object target, String fieldName, Object value) {
    try {
      java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (NoSuchFieldException e) {
      java.lang.reflect.Field field = null;
      Class<?> type = target.getClass().getSuperclass();
      while (type != null && field == null) {
        try {
          field = type.getDeclaredField(fieldName);
        } catch (NoSuchFieldException ignored) {
          type = type.getSuperclass();
        }
      }
      if (field == null) {
        throw new IllegalStateException("Missing field " + fieldName, e);
      }
      field.setAccessible(true);
      try {
        field.set(target, value);
      } catch (IllegalAccessException accessException) {
        throw new IllegalStateException("Unable to set field " + fieldName, accessException);
      }
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Unable to set field " + fieldName, e);
    }
  }
}
