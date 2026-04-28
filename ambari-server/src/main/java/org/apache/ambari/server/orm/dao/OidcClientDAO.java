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

package org.apache.ambari.server.orm.dao;

import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;

import org.apache.ambari.server.orm.RequiresSession;
import org.apache.ambari.server.orm.entities.OidcClientEntity;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;

/**
 * Data access object for {@link OidcClientEntity}.
 */
@Singleton
public class OidcClientDAO {

  @Inject
  Provider<EntityManager> entityManagerProvider;

  @Transactional
  public void create(OidcClientEntity entity) {
    entityManagerProvider.get().persist(entity);
  }

  @Transactional
  public OidcClientEntity merge(OidcClientEntity entity) {
    return entityManagerProvider.get().merge(entity);
  }

  @Transactional
  public void remove(OidcClientEntity entity) {
    if (entity != null) {
      EntityManager em = entityManagerProvider.get();
      em.remove(em.merge(entity));
    }
  }

  @RequiresSession
  public OidcClientEntity findById(Long id) {
    return entityManagerProvider.get().find(OidcClientEntity.class, id);
  }

  @RequiresSession
  public List<OidcClientEntity> findAll() {
    TypedQuery<OidcClientEntity> query = entityManagerProvider.get()
        .createNamedQuery("OidcClientEntityFindAll", OidcClientEntity.class);
    return query.getResultList();
  }

  @RequiresSession
  public List<OidcClientEntity> findByCluster(Long clusterId) {
    TypedQuery<OidcClientEntity> query = entityManagerProvider.get()
        .createNamedQuery("OidcClientEntityFindByCluster", OidcClientEntity.class);
    query.setParameter("clusterId", clusterId);
    return query.getResultList();
  }

  @RequiresSession
  public List<OidcClientEntity> findByClusterAndService(Long clusterId, String serviceName) {
    TypedQuery<OidcClientEntity> query = entityManagerProvider.get()
        .createNamedQuery("OidcClientEntityFindByClusterAndService", OidcClientEntity.class);
    query.setParameter("clusterId", clusterId);
    query.setParameter("serviceName", serviceName);
    return query.getResultList();
  }

  @RequiresSession
  public List<OidcClientEntity> findByClusterAndClientId(Long clusterId, String clientId) {
    TypedQuery<OidcClientEntity> query = entityManagerProvider.get()
        .createNamedQuery("OidcClientEntityFindByClusterAndClientId", OidcClientEntity.class);
    query.setParameter("clusterId", clusterId);
    query.setParameter("clientId", clientId);
    return query.getResultList();
  }

  @Transactional
  public int deleteByCluster(Long clusterId) {
    return entityManagerProvider.get()
        .createNamedQuery("OidcClientEntityDeleteByCluster")
        .setParameter("clusterId", clusterId)
        .executeUpdate();
  }

  @Transactional
  public void deleteByClusterAndClientId(Long clusterId, String clientId) {
    List<OidcClientEntity> entities = findByClusterAndClientId(clusterId, clientId);
    for (OidcClientEntity entity : entities) {
      remove(entity);
    }
  }
}
