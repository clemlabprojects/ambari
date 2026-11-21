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

package org.apache.ambari.server.view;

import static org.junit.Assert.assertEquals;

import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import javax.persistence.EntityManager;

import org.apache.ambari.server.H2DatabaseCleaner;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.orm.GuiceJpaInitializer;
import org.apache.ambari.server.orm.InMemoryDefaultTestModule;
import org.apache.ambari.server.orm.dao.ViewDAO;
import org.apache.ambari.server.orm.dao.ViewInstanceDAO;
import org.apache.ambari.server.orm.entities.ResourceEntity;
import org.apache.ambari.server.orm.entities.ResourceTypeEntity;
import org.apache.ambari.server.orm.entities.ViewEntity;
import org.apache.ambari.server.orm.entities.ViewInstanceDataEntity;
import org.apache.ambari.server.orm.entities.ViewInstanceEntity;
import org.apache.ambari.server.view.configuration.ViewConfig;
import org.apache.ambari.server.view.configuration.ViewConfigTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.inject.Guice;
import com.google.inject.Injector;

/**
 * ViewDataMigrationUtility.persistTargetInstance, the save that ends a view data migration, against the real
 * persistence layer (H2 and EclipseLink) instead of a mocked registry. The instance migrated is the
 * registry's copy, which shares its data collection with the instance loaded from the database, as
 * at server start: the copied values, the "upgradedFrom" marker and the properties must reach the
 * database, rows the new instance already holds are updated in place and never duplicated.
 */
public class ViewDataMigrationSaveTest {

  private static final String VIEW_XML =
      "<view>\n" +
      "  <name>MIGRATED_VIEW</name>\n" +
      "  <label>Migrated view</label>\n" +
      "  <version>%s</version>\n" +
      "  <data-version>%s</data-version>\n" +
      "</view>";

  private Injector injector;
  private ViewInstanceDAO instanceDAO;

  @Before
  public void setUp() throws Exception {
    injector = Guice.createInjector(new InMemoryDefaultTestModule())
        .createChildInjector(binder -> binder.requestStaticInjection(ViewDataMigrationUtility.class));
    injector.getInstance(GuiceJpaInitializer.class);
    instanceDAO = injector.getInstance(ViewInstanceDAO.class);
  }

  @After
  public void tearDown() throws Exception {
    H2DatabaseCleaner.clearDatabaseAndStopPersistenceService(injector);
  }

  /** Settings of the previous version reach the new instance, which held nothing yet. */
  @Test
  public void migratedDataIsSavedIntoAnEmptyInstance() throws Exception {
    ViewInstanceEntity origin = createInstance("1.0.0", 7);
    putAndSave(origin, "admin", "kubeconfig.context", "portable51");
    putAndSave(origin, "admin", "auth.mode", "kerberos");
    ViewInstanceEntity target = createInstance("2.0.0", 10);

    migrateAndSave(registryCopy(origin), registryCopy(target));

    Map<String, String> saved = savedData(target);
    assertEquals("portable51", saved.get("admin/kubeconfig.context"));
    assertEquals("kerberos", saved.get("admin/auth.mode"));
    assertEquals("1.0.0", saved.get("upgrade/upgradedFrom"));
    assertEquals(3, saved.size());
  }

  /**
   * The new instance already holds some of the keys (written by the view on its first start, or by
   * an earlier attempt of this migration): the copied values replace them.
   */
  @Test
  public void migratedDataReplacesKeysTheInstanceAlreadyHolds() throws Exception {
    ViewInstanceEntity origin = createInstance("1.0.0", 7);
    putAndSave(origin, "admin", "kubeconfig.context", "portable51");
    putAndSave(origin, "admin", "auth.mode", "kerberos");
    ViewInstanceEntity target = createInstance("2.0.0", 10);
    putAndSave(target, "admin", "kubeconfig.context", "default");
    putAndSave(target, "admin", "only.on.the.new.version", "kept");

    migrateAndSave(registryCopy(origin), registryCopy(target));

    Map<String, String> saved = savedData(target);
    assertEquals("portable51", saved.get("admin/kubeconfig.context"));
    assertEquals("kerberos", saved.get("admin/auth.mode"));
    assertEquals("kept", saved.get("admin/only.on.the.new.version"));
    assertEquals("1.0.0", saved.get("upgrade/upgradedFrom"));
    assertEquals(4, saved.size());
  }

  /** A property the migration set on the registry's copy (copyDefaultedProperties) is saved too. */
  @Test
  public void migratedPropertiesAreSaved() throws Exception {
    ViewInstanceEntity origin = createInstance("1.0.0", 7);
    putAndSave(origin, "admin", "auth.mode", "kerberos");
    ViewInstanceEntity target = createInstance("2.0.0", 10);
    ViewInstanceEntity copy = registryCopy(target);
    copy.putProperty("view.admin.users", "lbakalian");

    migrateAndSave(registryCopy(origin), copy);

    EntityManager entityManager = injector.getProvider(EntityManager.class).get();
    entityManager.clear();
    ViewInstanceEntity reloaded = instanceDAO.findByName(target.getViewName(), target.getName());
    assertEquals("lbakalian", reloaded.getPropertyMap().get("view.admin.users"));
    assertEquals("kerberos", savedData(target).get("admin/auth.mode"));
  }

  /**
   * As at server start: the registry does not migrate the instances it loaded from the database,
   * but its own copies built from view.xml, which ViewRegistry.syncViewInstance points at the loaded
   * instances' id, resource, properties and data collections. Saved twice.
   */
  @Test
  public void migratedDataIsSavedFromTheRegistryCopiesOfTheInstancesTwice() throws Exception {
    ViewInstanceEntity originSaved = createInstance("1.0.0", 7);
    putAndSave(originSaved, "admin", "kubeconfig.context", "portable51");
    putAndSave(originSaved, "admin", "auth.mode", "kerberos");
    ViewInstanceEntity targetSaved = createInstance("2.0.0", 10);

    ViewInstanceEntity origin = registryCopy(originSaved);
    ViewInstanceEntity target = registryCopy(targetSaved);

    migrateAndSave(origin, target);
    // Server start can reach the save twice for one view (archive extraction, then the loop over
    // deployed views): the second save finds the rows and must neither fail nor duplicate them.
    new ViewDataMigrationUtility(injector.getInstance(ViewRegistry.class)).persistTargetInstance(target);

    Map<String, String> saved = savedData(target);
    assertEquals("portable51", saved.get("admin/kubeconfig.context"));
    assertEquals("kerberos", saved.get("admin/auth.mode"));
    assertEquals("1.0.0", saved.get("upgrade/upgradedFrom"));
    assertEquals(3, saved.size());
  }

  /** The registry's copy of an instance, synchronised the way ViewRegistry.syncViewInstance does it. */
  private ViewInstanceEntity registryCopy(ViewInstanceEntity saved) {
    ViewInstanceEntity loaded = instanceDAO.findByName(saved.getViewName(), saved.getName());
    ViewInstanceEntity copy = new ViewInstanceEntity(saved.getViewEntity(), saved.getName());
    copy.setResource(loaded.getResource());
    copy.setViewInstanceId(loaded.getViewInstanceId());
    copy.setData(loaded.getData());
    copy.setProperties(loaded.getProperties());
    return copy;
  }

  /** What ViewDataMigrationUtility does with the instance data, then the save it ends with. */
  private void migrateAndSave(ViewInstanceEntity origin, ViewInstanceEntity target) throws Exception {
    ViewDataMigrationContextImpl context = new ViewDataMigrationContextImpl(origin, target);
    context.copyAllInstanceData();
    context.putCurrentInstanceData("upgrade", "upgradedFrom", origin.getViewEntity().getVersion());
    new ViewDataMigrationUtility(injector.getInstance(ViewRegistry.class)).persistTargetInstance(target);
  }

  private ViewInstanceEntity createInstance(String version, int dataVersion) throws Exception {
    ViewConfig config = ViewConfigTest.getConfig(String.format(VIEW_XML, version, dataVersion));
    ViewEntity view = new ViewEntity(config, new Configuration(new Properties()), "view.jar");

    // Saved together with the view (cascade), as ViewRegistry does when it installs a view.
    ResourceTypeEntity resourceType = new ResourceTypeEntity();
    resourceType.setName(view.getName());
    view.setResourceType(resourceType);
    injector.getInstance(ViewDAO.class).create(view);

    ViewInstanceEntity instance = new ViewInstanceEntity(view, "INSTANCE");
    ResourceEntity resource = new ResourceEntity();
    resource.setResourceType(resourceType);
    instance.setResource(resource);
    instanceDAO.create(instance);
    return instance;
  }

  /** A value written the way a running view writes it (ViewContextImpl.putInstanceData). */
  private void putAndSave(ViewInstanceEntity instance, String user, String key, String value) {
    ViewInstanceDataEntity data = new ViewInstanceDataEntity();
    data.setViewName(instance.getViewName());
    data.setViewInstanceName(instance.getName());
    data.setUser(user);
    data.setName(key);
    data.setValue(value);
    data.setViewInstanceEntity(instance);
    instance.getData().add(data);
    instanceDAO.merge(instance);
  }

  /** What the database holds for the instance, read back outside the objects the test used. */
  private Map<String, String> savedData(ViewInstanceEntity instance) {
    EntityManager entityManager = injector.getProvider(EntityManager.class).get();
    entityManager.clear();
    Map<String, String> saved = new TreeMap<>();
    for (ViewInstanceDataEntity data : instanceDAO.findByName(instance.getViewName(), instance.getName()).getData()) {
      saved.put(data.getUser() + "/" + data.getName(), data.getValue());
    }
    return saved;
  }
}
