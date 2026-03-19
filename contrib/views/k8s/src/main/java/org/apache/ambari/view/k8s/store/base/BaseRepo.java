/**
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

package org.apache.ambari.view.k8s.store.base;

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.PersistenceException;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.UUID;

/**
 * Generic repository wrapper over Ambari View DataStore.
 * No generic bounds: we set id/timestamps only if entity implements Indexed/When.
 */
public class BaseRepo<T> {

  private final Class<T> clazz;
  protected final DataStore dataStore;

  public BaseRepo(Class<T> clazz, DataStore dataStore) {
    this.clazz = clazz;
    this.dataStore = dataStore;
  }

  protected String now() {
    return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
  }

  protected String generateId() {
    return UUID.randomUUID().toString();
  }

  public T create(T entity) {
    applyCreateDefaults(entity);
    try {
      dataStore.store(entity); // acts as insert
      return entity;
    } catch (PersistenceException e) {
      throw new RuntimeException(e);
    }
  }

  public T update(T entity) {
    applyUpdateDefaults(entity);
    try {
      dataStore.store(entity); // acts as merge
      return entity;
    } catch (PersistenceException e) {
      throw new RuntimeException(e);
    }
  }

  public T upsert(T entity) {
    String id = tryGetId(entity);
    if (id == null || id.isBlank() || findById(id) == null) {
      return create(entity);
    }
    return update(entity);
  }

  public T findById(String id) {
    try {
      return dataStore.find(clazz, id);
    } catch (PersistenceException e) {
      throw new RuntimeException(e);
    }
  }

  public Collection<T> findAll() {
    try {
      return dataStore.findAll(clazz, null);
    } catch (PersistenceException e) {
      throw new RuntimeException(e);
    }
  }

  public void deleteById(String id) {
    try {
      T found = findById(id);
      if (found != null) {
        dataStore.remove(found);
      }
    } catch (PersistenceException e) {
      throw new RuntimeException(e);
    }
  }

  /* ---------- helpers ---------- */

  private String tryGetId(T entity) {
    if (entity instanceof Indexed) {
      return ((Indexed) entity).getId();
    }
    try {
      var m = entity.getClass().getMethod("getId");
      Object v = m.invoke(entity);
      return v != null ? v.toString() : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  private void applyCreateDefaults(T entity) {
    String now = now();
    if (entity instanceof Indexed) {
      Indexed ix = (Indexed) entity;
      if (ix.getId() == null || ix.getId().isBlank()) {
        ix.setId(generateId());
      }
    }
    if (entity instanceof When) {
      When wh = (When) entity;
      if (wh.getCreatedAt() == null) wh.setCreatedAt(now);
      wh.setUpdatedAt(now);
    } else {
      // try reflective fallback
      trySet(entity, "setCreatedAt", String.class, now, true);
      trySet(entity, "setUpdatedAt", String.class, now, false);
    }
  }

  private void applyUpdateDefaults(T entity) {
    String now = now();
    if (entity instanceof When) {
      ((When) entity).setUpdatedAt(now);
    } else {
      trySet(entity, "setUpdatedAt", String.class, now, false);
    }
  }

  private void trySet(T entity, String method, Class<?> argType, Object value, boolean onlyIfNull) {
    try {
      var getterName = method.replace("set", "get");
      var getter = entity.getClass().getMethod(getterName);
      Object cur = getter.invoke(entity);
      if (onlyIfNull && cur != null) return;
      var m = entity.getClass().getMethod(method, argType);
      m.invoke(entity, value);
    } catch (Exception ignored) {
    }
  }
}
