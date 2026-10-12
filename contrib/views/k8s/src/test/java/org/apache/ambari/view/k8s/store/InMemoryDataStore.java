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

package org.apache.ambari.view.k8s.store;

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.k8s.migration.AccessorCopyConverter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A view DataStore kept in memory that behaves like Ambari's: entities are keyed by class and id, callers get
 * copies, and storing an entity whose id exists only overwrites its non-null properties.
 */
public class InMemoryDataStore implements DataStore {

    private static final AccessorCopyConverter COPY = new AccessorCopyConverter();

    private final Map<Class<?>, Map<Object, Object>> byClass = new ConcurrentHashMap<>();

    @Override
    public synchronized void store(Object entity) {
        Map<Object, Object> rows = byClass.computeIfAbsent(entity.getClass(), k -> new ConcurrentHashMap<>());
        Object id = idOf(entity);
        Object existing = rows.get(id);
        if (existing != null) {
            COPY.convert(entity, existing);
        } else {
            rows.put(id, copyOf(entity));
        }
    }

    @Override
    public synchronized void remove(Object entity) {
        Map<Object, Object> rows = byClass.get(entity.getClass());
        if (rows != null) {
            rows.remove(idOf(entity));
        }
    }

    @Override
    public synchronized <T> T find(Class<T> clazz, Object primaryKey) {
        Map<Object, Object> rows = byClass.get(clazz);
        Object row = rows == null || primaryKey == null ? null : rows.get(primaryKey);
        return row == null ? null : clazz.cast(copyOf(row));
    }

    @Override
    public synchronized <T> Collection<T> findAll(Class<T> clazz, String whereClause) {
        Map<Object, Object> rows = byClass.get(clazz);
        List<T> out = new ArrayList<>();
        if (rows != null) {
            rows.values().forEach(row -> out.add(clazz.cast(copyOf(row))));
        }
        return out;
    }

    private static Object copyOf(Object entity) {
        try {
            Object copy = entity.getClass().getDeclaredConstructor().newInstance();
            COPY.convert(entity, copy);
            return copy;
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Entity without a public no-argument constructor: " + entity.getClass(), e);
        }
    }

    private static Object idOf(Object entity) {
        try {
            return entity.getClass().getMethod("getId").invoke(entity);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Entity without getId(): " + entity.getClass(), e);
        }
    }
}
