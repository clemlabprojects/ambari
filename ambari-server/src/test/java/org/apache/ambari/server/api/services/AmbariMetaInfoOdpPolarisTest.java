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

package org.apache.ambari.server.api.services;

import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.persistence.EntityManager;

import org.apache.ambari.server.H2DatabaseCleaner;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.events.publishers.AmbariEventPublisher;
import org.apache.ambari.server.metadata.ActionMetadata;
import org.apache.ambari.server.orm.GuiceJpaInitializer;
import org.apache.ambari.server.orm.InMemoryDefaultTestModule;
import org.apache.ambari.server.orm.dao.AlertDefinitionDAO;
import org.apache.ambari.server.orm.dao.MetainfoDAO;
import org.apache.ambari.server.stack.StackManager;
import org.apache.ambari.server.stack.StackManagerFactory;
import org.apache.ambari.server.state.CommandScriptDefinition;
import org.apache.ambari.server.state.ComponentInfo;
import org.apache.ambari.server.state.ServiceInfo;
import org.apache.ambari.server.state.alert.AlertDefinitionFactory;
import org.apache.ambari.server.state.kerberos.KerberosDescriptorFactory;
import org.apache.ambari.server.state.kerberos.KerberosServiceDescriptorFactory;
import org.apache.ambari.server.state.oidc.OidcClientDescriptor;
import org.apache.ambari.server.state.oidc.OidcDescriptor;
import org.apache.ambari.server.state.oidc.OidcDescriptorFactory;
import org.apache.ambari.server.state.oidc.OidcServiceDescriptor;
import org.apache.ambari.server.state.stack.OsFamily;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Modules;

public class AmbariMetaInfoOdpPolarisTest {
  private static TestAmbariMetaInfo metaInfo;

  @BeforeClass
  public static void beforeClass() throws Exception {
    Properties properties = new Properties();
    properties.setProperty(Configuration.METADATA_DIR_PATH.getKey(),
        pathFromBasedir("src/main/resources/stacks"));
    properties.setProperty(Configuration.COMMON_SERVICES_DIR_PATH.getKey(),
        pathFromBasedir("src/main/resources/common-services"));
    properties.setProperty(Configuration.RESOURCES_DIR.getKey(),
        pathFromBasedir("src/main/resources"));
    properties.setProperty(Configuration.SERVER_VERSION_FILE.getKey(),
        pathFromBasedir("src/test/resources/version"));
    properties.setProperty(Configuration.CUSTOM_ACTION_DEFINITION.getKey(),
        pathFromBasedir("src/main/resources/custom_action_definitions"));
    properties.setProperty(Configuration.SHARED_RESOURCES_DIR.getKey(),
        pathFromBasedir("src/test/resources"));

    Configuration configuration = new Configuration(properties);
    metaInfo = new TestAmbariMetaInfo(configuration);
    metaInfo.replayAllMocks();
    metaInfo.init();
    waitForAllReposToBeResolved(metaInfo);
  }

  @AfterClass
  public static void afterClass() throws Exception {
    if (metaInfo != null) {
      H2DatabaseCleaner.clearDatabase(metaInfo.injector.getProvider(EntityManager.class).get());
    }
  }

  @Test
  public void testPolarisServiceMergedFromCommonServices() throws Exception {
    ServiceInfo serviceInfo = metaInfo.getService("ODP", "1.3", "POLARIS");
    assertNotNull(serviceInfo);
    assertEquals("1.1.0", serviceInfo.getVersion());
    assertTrue(serviceInfo.hasConfigDependency("polaris-application-properties"));
    assertTrue(serviceInfo.hasConfigDependency("polaris-env"));

    List<ComponentInfo> components = serviceInfo.getComponents();
    assertNotNull(components);
    ComponentInfo server = findComponent(components, "POLARIS_SERVER");
    assertNotNull(server);
    ComponentInfo client = findComponent(components, "POLARIS_CLIENT");
    assertNotNull(client);

    assertEquals("scripts/polaris_server.py", server.getCommandScript().getScript());
    assertEquals("scripts/polaris_client.py", client.getCommandScript().getScript());

    CommandScriptDefinition serviceCommand = serviceInfo.getCommandScript();
    assertNotNull(serviceCommand);
    assertEquals("scripts/service_check.py", serviceCommand.getScript());
  }

  @Test
  public void testOidcServiceConfigDependency() throws Exception {
    ServiceInfo serviceInfo = metaInfo.getService("ODP", "1.3", "OIDC");
    assertNotNull(serviceInfo);
    assertTrue(serviceInfo.hasConfigDependency("oidc-env"));

    List<ComponentInfo> components = serviceInfo.getComponents();
    assertNotNull(components);
    ComponentInfo client = findComponent(components, "OIDC_CLIENT");
    assertNotNull(client);
  }

  @Test
  public void testPolarisOidcDescriptorMerged() throws Exception {
    OidcDescriptor descriptor = metaInfo.getOidcDescriptor("ODP", "1.3");
    assertNotNull(descriptor);

    OidcServiceDescriptor polaris = descriptor.getService("POLARIS");
    assertNotNull(polaris);

    List<OidcClientDescriptor> clients = polaris.getClients();
    assertNotNull(clients);

    OidcClientDescriptor client = findClient(clients, "polaris");
    assertNotNull(client);
    assertEquals("polaris.oidc.client.secret", client.getSecretAlias());

    String applicationType = findConfigurationValue(client.getConfigurations(),
        "polaris-application-properties", "quarkus.oidc.application-type");
    assertEquals("service", applicationType);
  }

  private static ComponentInfo findComponent(List<ComponentInfo> components, String name) {
    for (ComponentInfo component : components) {
      if (name.equals(component.getName())) {
        return component;
      }
    }
    return null;
  }

  private static OidcClientDescriptor findClient(List<OidcClientDescriptor> clients, String name) {
    for (OidcClientDescriptor client : clients) {
      if (name.equals(client.getName())) {
        return client;
      }
    }
    return null;
  }

  private static String findConfigurationValue(List<Map<String, Map<String, String>>> configurations,
      String configType, String key) {
    if (configurations == null) {
      return null;
    }
    for (Map<String, Map<String, String>> entry : configurations) {
      if (entry != null && entry.containsKey(configType)) {
        Map<String, String> values = entry.get(configType);
        if (values != null) {
          return values.get(key);
        }
      }
    }
    return null;
  }

  private static void waitForAllReposToBeResolved(AmbariMetaInfo metaInfo) throws Exception {
    int maxWaitMs = 45000;
    int waitTimeMs = 0;
    StackManager stackManager = metaInfo.getStackManager();
    while (waitTimeMs < maxWaitMs && !stackManager.haveAllRepoUrlsBeenResolved()) {
      Thread.sleep(5);
      waitTimeMs += 5;
    }

    if (waitTimeMs >= maxWaitMs) {
      fail("Latest Repo tasks did not complete");
    }
  }

  private static String pathFromBasedir(String relativePath) {
    return new File(System.getProperty("basedir", "."), relativePath).getAbsolutePath();
  }

  private static class TestAmbariMetaInfo extends AmbariMetaInfo {
    private final MetainfoDAO metaInfoDAO;
    private final AlertDefinitionDAO alertDefinitionDAO;
    private final AlertDefinitionFactory alertDefinitionFactory;
    private final OsFamily osFamily;
    private final Injector injector;

    TestAmbariMetaInfo(Configuration configuration) throws Exception {
      super(configuration);

      injector = Guice.createInjector(Modules.override(new InMemoryDefaultTestModule())
          .with(new MockModule()));

      injector.getInstance(GuiceJpaInitializer.class);
      injector.getInstance(EntityManager.class);

      Class<?> c = getClass().getSuperclass();

      metaInfoDAO = injector.getInstance(MetainfoDAO.class);
      Field f = c.getDeclaredField("metaInfoDAO");
      f.setAccessible(true);
      f.set(this, metaInfoDAO);

      StackManagerFactory stackManagerFactory = injector.getInstance(StackManagerFactory.class);
      f = c.getDeclaredField("stackManagerFactory");
      f.setAccessible(true);
      f.set(this, stackManagerFactory);

      alertDefinitionDAO = createNiceMock(AlertDefinitionDAO.class);
      f = c.getDeclaredField("alertDefinitionDao");
      f.setAccessible(true);
      f.set(this, alertDefinitionDAO);

      alertDefinitionFactory = new AlertDefinitionFactory();
      f = c.getDeclaredField("alertDefinitionFactory");
      f.setAccessible(true);
      f.set(this, alertDefinitionFactory);

      AmbariEventPublisher ambariEventPublisher = new AmbariEventPublisher();
      f = c.getDeclaredField("eventPublisher");
      f.setAccessible(true);
      f.set(this, ambariEventPublisher);

      KerberosDescriptorFactory kerberosDescriptorFactory = new KerberosDescriptorFactory();
      f = c.getDeclaredField("kerberosDescriptorFactory");
      f.setAccessible(true);
      f.set(this, kerberosDescriptorFactory);

      KerberosServiceDescriptorFactory kerberosServiceDescriptorFactory = new KerberosServiceDescriptorFactory();
      f = c.getDeclaredField("kerberosServiceDescriptorFactory");
      f.setAccessible(true);
      f.set(this, kerberosServiceDescriptorFactory);

      OidcDescriptorFactory oidcDescriptorFactory = new OidcDescriptorFactory();
      f = c.getDeclaredField("oidcDescriptorFactory");
      f.setAccessible(true);
      f.set(this, oidcDescriptorFactory);

      osFamily = new OsFamily(configuration);
      f = c.getDeclaredField("osFamily");
      f.setAccessible(true);
      f.set(this, osFamily);
    }

    void replayAllMocks() {
      replay(metaInfoDAO, alertDefinitionDAO);
    }

    private static class MockModule extends AbstractModule {
      @Override
      protected void configure() {
        bind(ActionMetadata.class);
        bind(MetainfoDAO.class).toInstance(createNiceMock(MetainfoDAO.class));
      }
    }
  }
}
