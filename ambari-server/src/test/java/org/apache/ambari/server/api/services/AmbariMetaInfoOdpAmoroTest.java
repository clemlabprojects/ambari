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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import org.apache.ambari.server.state.ComponentInfo;
import org.apache.ambari.server.state.PropertyInfo;
import org.apache.ambari.server.state.ServiceInfo;
import org.apache.ambari.server.state.alert.AlertDefinitionFactory;
import org.apache.ambari.server.state.kerberos.KerberosDescriptorFactory;
import org.apache.ambari.server.state.kerberos.KerberosServiceDescriptorFactory;
import org.apache.ambari.server.state.oidc.OidcDescriptorFactory;
import org.apache.ambari.server.state.stack.OsFamily;
import org.apache.ambari.server.state.stack.UpgradePack;
import org.apache.ambari.server.state.stack.upgrade.Direction;
import org.apache.ambari.server.state.stack.upgrade.Grouping;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Modules;

public class AmbariMetaInfoOdpAmoroTest {
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
  public void testAmoroService() throws Exception {
    ServiceInfo serviceInfo = metaInfo.getService("ODP", "1.3", "AMORO");
    assertNotNull(serviceInfo);
    assertEquals("0.9.0", serviceInfo.getVersion());
    for (String type : new String[]{"amoro-env", "amoro-site", "amoro-db-properties", "amoro-log4j"}) {
      assertTrue(type, serviceInfo.hasConfigDependency(type));
    }
    assertEquals("scripts/service_check.py", serviceInfo.getCommandScript().getScript());
    assertNotNull(serviceInfo.getKerberosDescriptorFile());
    assertNotNull(serviceInfo.getOidcDescriptorFile());
    assertTrue(serviceInfo.getThemesMap().containsKey("credentials.json"));
    assertTrue(serviceInfo.getThemesMap().containsKey("database.json"));

    ComponentInfo server = findComponent(serviceInfo.getComponents(), "AMORO_SERVER");
    assertNotNull(server);
    assertEquals("MASTER", server.getCategory());
    assertEquals("1", server.getCardinality());
    assertEquals("scripts/amoro_server.py", server.getCommandScript().getScript());
    for (String command : new String[]{"REGISTER_CATALOGS", "SETUP_ACCESS", "PAUSE_OPTIMIZING", "RESUME_OPTIMIZING"}) {
      assertNotNull(command, server.getCustomCommandByName(command));
    }
  }

  @Test
  public void testUpgradePacksRequirePausedOptimizing() throws Exception {
    Map<String, UpgradePack> packs = metaInfo.getUpgradePacks("ODP", "1.3");
    for (String name : new String[]{"upgrade-odp-1.3", "nonrolling-upgrade-odp-1.3"}) {
      UpgradePack pack = packs.get(name);
      assertNotNull(name, pack);
      assertTrue(name, pack.getPrerequisiteChecks().contains("org.apache.ambari.server.checks.AmoroOptimizingPausedCheck"));
      assertNotNull(name, pack.getTasks().get("AMORO"));
    }
  }

  @Test
  public void testOnlyTheRollingUpgradePackRejectsOzone() throws Exception {
    Map<String, UpgradePack> packs = metaInfo.getUpgradePacks("ODP", "1.3");
    String check = "org.apache.ambari.server.checks.OzoneRollingUpgradeCheck";
    assertTrue(packs.get("upgrade-odp-1.3").getPrerequisiteChecks().contains(check));
    assertTrue(!packs.get("nonrolling-upgrade-odp-1.3").getPrerequisiteChecks().contains(check));
  }

  /**
   * Services added in ODP 1.3.2.0 must be upgraded between two ODP 1.3 builds: every component is restarted (and
   * switched to the target version) by both packs, and the servers report their version.
   */
  @Test
  public void testOdp1320ServicesAreUpgradedByTheOdp13Packs() throws Exception {
    Map<String, String[]> services = new LinkedHashMap<>();
    services.put("POLARIS", new String[]{"POLARIS_SERVER", "POLARIS_MCP_SERVER", "POLARIS_CONSOLE", "POLARIS_CLIENT"});
    services.put("KUDU", new String[]{"KUDU_MASTER", "KUDU_TSERVER", "KUDU_CLIENT"});
    services.put("IMPALA", new String[]{"IMPALA_STATE_STORE", "IMPALA_CATALOG_SERVICE", "IMPALA_DAEMON",
        "IMPALA_ATLAS_EXTRACTOR", "IMPALA_CLIENT"});
    services.put("DBT", new String[]{"DBT_CLIENT"});

    Map<String, UpgradePack> packs = metaInfo.getUpgradePacks("ODP", "1.3");
    for (String name : new String[]{"upgrade-odp-1.3", "nonrolling-upgrade-odp-1.3"}) {
      UpgradePack pack = packs.get(name);
      Set<String> restarted = new HashSet<>();
      for (Grouping group :
          pack.getGroups(Direction.UPGRADE)) {
        for (UpgradePack.OrderService service : group.services) {
          for (String component : service.components) {
            restarted.add(service.serviceName + "/" + component);
          }
        }
      }
      for (Map.Entry<String, String[]> service : services.entrySet()) {
        for (String component : service.getValue()) {
          String key = name + " " + service.getKey() + "/" + component;
          assertNotNull(key, pack.getTasks().get(service.getKey()));
          assertNotNull(key, pack.getTasks().get(service.getKey()).get(component));
          assertTrue(key, restarted.contains(service.getKey() + "/" + component));
        }
      }
    }

    for (Map.Entry<String, String[]> service : services.entrySet()) {
      ServiceInfo serviceInfo = metaInfo.getService("ODP", "1.3", service.getKey());
      for (String component : service.getValue()) {
        assertTrue(component, findComponent(serviceInfo.getComponents(), component).isVersionAdvertised());
      }
    }
  }

  @Test
  public void testNifiShowsTheSensitivePropsKeyOnlyOnce() throws Exception {
    // the scripts write nifi-ambari-config's key into nifi.properties: a nifi-properties copy would be ignored
    for (String version : new String[]{"1.2", "1.3"}) {
      Set<String> files = new HashSet<>();
      for (PropertyInfo property : metaInfo.getService("ODP", version, "NIFI").getProperties()) {
        if ("nifi.sensitive.props.key".equals(property.getName())) {
          files.add(property.getFilename());
        }
      }
      assertEquals(version, Collections.singleton("nifi-ambari-config.xml"), files);
    }
  }

  private static ComponentInfo findComponent(List<ComponentInfo> components, String name) {
    for (ComponentInfo component : components) {
      if (name.equals(component.getName())) {
        return component;
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
