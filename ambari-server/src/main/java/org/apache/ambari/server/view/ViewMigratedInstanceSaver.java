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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.ambari.server.orm.dao.ViewInstanceDAO;
import org.apache.ambari.server.orm.entities.ViewEntity;
import org.apache.ambari.server.orm.entities.ViewInstanceDataEntity;
import org.apache.ambari.server.orm.entities.ViewInstanceEntity;
import org.apache.ambari.server.orm.entities.ViewInstancePropertyEntity;
import org.apache.ambari.server.view.validation.ValidationException;
import org.apache.ambari.view.validation.Validator;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;

/**
 * Saves the data and properties of a view instance at the end of a view data migration
 * (clemlab, AMBARI-1145). Used by {@link ViewDataMigrationUtility#persistTargetInstance}.
 */
@Singleton
public class ViewMigratedInstanceSaver {

  @Inject
  private ViewInstanceDAO instanceDAO;

  /**
   * Saves the instance data and properties of the migrated instance into the instance as the
   * database holds it (clemlab, AMBARI-1145).
   *
   * The instance migrated is the registry's copy, which shares its data and property collections
   * with the instance loaded from the database (ViewRegistry.syncViewInstance), and the rows the
   * migration added point at that copy. Merging the copy (ViewRegistry.updateViewInstance, upstream)
   * can make EclipseLink move a saved row from one instance object to the other, which it refuses
   * for a relationship that is part of the primary key (EclipseLink-7251, seen when the KDPS view
   * moved from 1.0.0.7 to 1.0.0.10). Here the copy is never merged: the loaded instance is re-read,
   * its existing rows are updated, missing rows are added with their complete key, nothing is
   * removed; the copy is then pointed at what was saved, as the registry does after an update.
   *
   * @param targetInstanceDefinition the migrated instance (the registry's copy)
   * @return false when the database holds no such instance (nothing was saved)
   * @throws ValidationException if the migrated instance fails the validation checks
   */
  @Transactional
  public boolean save(ViewInstanceEntity targetInstanceDefinition) throws ValidationException {
    ViewInstanceEntity saved =
        instanceDAO.findByName(targetInstanceDefinition.getViewName(), targetInstanceDefinition.getName());
    if (saved == null) {
      return false;
    }
    ViewEntity viewEntity = targetInstanceDefinition.getViewEntity();
    if (viewEntity != null) {
      targetInstanceDefinition.validate(viewEntity, Validator.ValidationContext.PRE_UPDATE);
    }

    // The values to save, read before the refresh below resets the shared collections.
    Map<String, String[]> data = new LinkedHashMap<>();
    for (ViewInstanceDataEntity row : new ArrayList<>(targetInstanceDefinition.getData())) {
      data.put(row.getUser() + '\u0000' + row.getName(), new String[] {row.getUser(), row.getName(), row.getValue()});
    }
    Map<String, String> properties = new LinkedHashMap<>();
    for (ViewInstancePropertyEntity property : new ArrayList<>(targetInstanceDefinition.getProperties())) {
      properties.put(property.getName(), property.getValue());
    }

    // Forget the unsaved rows: what follows starts from what the database holds.
    instanceDAO.refresh(saved);

    Map<String, ViewInstanceDataEntity> savedData = new HashMap<>();
    for (ViewInstanceDataEntity row : saved.getData()) {
      savedData.put(row.getUser() + '\u0000' + row.getName(), row);
    }
    for (Map.Entry<String, String[]> entry : data.entrySet()) {
      String[] wanted = entry.getValue();
      ViewInstanceDataEntity existing = savedData.get(entry.getKey());
      if (existing == null) {
        ViewInstanceDataEntity row = new ViewInstanceDataEntity();
        row.setViewInstanceId(saved.getViewInstanceId());
        row.setViewName(saved.getViewName());
        row.setViewInstanceName(saved.getName());
        row.setUser(wanted[0]);
        row.setName(wanted[1]);
        row.setValue(wanted[2]);
        row.setViewInstanceEntity(saved);
        saved.getData().add(row);
      } else if (!Objects.equals(existing.getValue(), wanted[2])) {
        existing.setValue(wanted[2]);
      }
    }

    Map<String, ViewInstancePropertyEntity> savedProperties = new HashMap<>();
    for (ViewInstancePropertyEntity property : saved.getProperties()) {
      savedProperties.put(property.getName(), property);
    }
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      ViewInstancePropertyEntity existing = savedProperties.get(entry.getKey());
      if (existing == null) {
        ViewInstancePropertyEntity property = new ViewInstancePropertyEntity();
        property.setViewName(saved.getViewName());
        property.setViewInstanceName(saved.getName());
        property.setName(entry.getKey());
        property.setValue(entry.getValue());
        property.setViewInstanceEntity(saved);
        saved.getProperties().add(property);
      } else if (!Objects.equals(existing.getValue(), entry.getValue())) {
        existing.setValue(entry.getValue());
      }
    }

    ViewInstanceEntity merged = instanceDAO.merge(saved);
    targetInstanceDefinition.setData(merged.getData());
    targetInstanceDefinition.setProperties(merged.getProperties());
    return true;
  }
}
