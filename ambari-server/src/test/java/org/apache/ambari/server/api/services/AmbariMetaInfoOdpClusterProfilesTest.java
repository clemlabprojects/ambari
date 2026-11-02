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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

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
import org.apache.ambari.server.state.PropertyInfo;
import org.apache.ambari.server.state.alert.AlertDefinitionFactory;
import org.apache.ambari.server.state.kerberos.KerberosDescriptorFactory;
import org.apache.ambari.server.state.kerberos.KerberosServiceDescriptorFactory;
import org.apache.ambari.server.state.oidc.OidcDescriptorFactory;
import org.apache.ambari.server.state.stack.OsFamily;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Modules;

public class AmbariMetaInfoOdpClusterProfilesTest {
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
  public void testOdp13ServesClusterProfiles() throws Exception {
    PropertyInfo property = findProperty(metaInfo.getStackProperties("ODP", "1.3"), "cluster_profiles");
    assertNotNull(property);
    assertEquals("cluster-env.xml", property.getFilename());
    Map<?, ?> profiles = new Gson().fromJson(property.getValue(), Map.class);
    Set<String> names = new HashSet<>();
    for (Object profile : (List<?>) profiles.get("profiles")) {
      Map<?, ?> map = (Map<?, ?>) profile;
      names.add((String) map.get("name"));
      for (Object service : (List<?>) map.get("services")) {
        assertNotNull("profile " + map.get("name") + " names an unknown service " + service,
          metaInfo.getService("ODP", "1.3", (String) service));
      }
      for (Object service : (List<?>) map.get("security_services")) {
        assertNotNull(metaInfo.getService("ODP", "1.3", (String) service));
      }
      if (map.containsKey("governance_services")) {
        for (Object service : (List<?>) map.get("governance_services")) {
          assertNotNull(metaInfo.getService("ODP", "1.3", (String) service));
        }
      }
    }
    assertEquals(new HashSet<>(Arrays.asList("data_warehouse", "open_lakehouse", "hybrid")), names);
  }

  @Test
  public void testOlderStacksHaveNoClusterProfiles() throws Exception {
    assertNull(findProperty(metaInfo.getStackProperties("ODP", "1.2"), "cluster_profiles"));
  }

  private static PropertyInfo findProperty(Collection<PropertyInfo> properties, String name) {
    for (PropertyInfo property : properties) {
      if (name.equals(property.getName())) {
        return property;
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
