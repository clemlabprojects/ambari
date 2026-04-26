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

package org.apache.ambari.server.controller.internal;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.StaticallyInject;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.spi.NoSuchParentResourceException;
import org.apache.ambari.server.controller.spi.NoSuchResourceException;
import org.apache.ambari.server.controller.spi.Predicate;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.Resource.Type;
import org.apache.ambari.server.controller.spi.SystemException;
import org.apache.ambari.server.controller.spi.UnsupportedPropertyException;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.ambari.server.orm.dao.ArtifactDAO;
import org.apache.ambari.server.orm.entities.ArtifactEntity;
import org.apache.ambari.server.security.authorization.AuthorizationHelper;
import org.apache.ambari.server.security.authorization.ResourceType;
import org.apache.ambari.server.security.authorization.RoleAuthorization;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.oidc.OidcDescriptor;
import org.apache.ambari.server.state.oidc.OidcDescriptorFactory;
import org.apache.commons.lang.StringUtils;

import com.google.inject.Inject;

@StaticallyInject
public class ClusterOidcDescriptorResourceProvider extends ReadOnlyResourceProvider {

  public static final String CLUSTER_OIDC_DESCRIPTOR_CLUSTER_NAME_PROPERTY_ID =
    PropertyHelper.getPropertyId("OidcDescriptor", "cluster_name");
  public static final String CLUSTER_OIDC_DESCRIPTOR_TYPE_PROPERTY_ID =
    PropertyHelper.getPropertyId("OidcDescriptor", "type");
  public static final String CLUSTER_OIDC_DESCRIPTOR_DESCRIPTOR_PROPERTY_ID =
    PropertyHelper.getPropertyId("OidcDescriptor", "oidc_descriptor");

  static final String OIDC_DESCRIPTOR_ARTIFACT_NAME = "oidc_descriptor";

  private static final Set<String> PK_PROPERTY_IDS;
  private static final Set<String> PROPERTY_IDS;
  private static final Map<Type, String> KEY_PROPERTY_IDS;

  private static final Set<RoleAuthorization> REQUIRED_GET_AUTHORIZATIONS = EnumSet.of(
    RoleAuthorization.CLUSTER_TOGGLE_KERBEROS,
    RoleAuthorization.CLUSTER_VIEW_CONFIGS,
    RoleAuthorization.HOST_VIEW_CONFIGS,
    RoleAuthorization.SERVICE_VIEW_CONFIGS);

  @Inject
  private static ArtifactDAO artifactDAO;

  @Inject
  private static OidcDescriptorFactory oidcDescriptorFactory;

  static {
    Set<String> set = new HashSet<>();
    set.add(CLUSTER_OIDC_DESCRIPTOR_CLUSTER_NAME_PROPERTY_ID);
    set.add(CLUSTER_OIDC_DESCRIPTOR_TYPE_PROPERTY_ID);
    PK_PROPERTY_IDS = Collections.unmodifiableSet(set);

    set = new HashSet<>();
    set.add(CLUSTER_OIDC_DESCRIPTOR_CLUSTER_NAME_PROPERTY_ID);
    set.add(CLUSTER_OIDC_DESCRIPTOR_TYPE_PROPERTY_ID);
    set.add(CLUSTER_OIDC_DESCRIPTOR_DESCRIPTOR_PROPERTY_ID);
    PROPERTY_IDS = Collections.unmodifiableSet(set);

    HashMap<Type, String> map = new HashMap<>();
    map.put(Type.Cluster, CLUSTER_OIDC_DESCRIPTOR_CLUSTER_NAME_PROPERTY_ID);
    map.put(Type.ClusterOidcDescriptor, CLUSTER_OIDC_DESCRIPTOR_TYPE_PROPERTY_ID);
    KEY_PROPERTY_IDS = Collections.unmodifiableMap(map);
  }

  public ClusterOidcDescriptorResourceProvider(AmbariManagementController managementController) {
    super(Type.ClusterOidcDescriptor, PROPERTY_IDS, KEY_PROPERTY_IDS, managementController);
  }

  @Override
  public Set<Resource> getResources(Request request, Predicate predicate)
    throws SystemException, UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException {

    AuthorizationHelper.verifyAuthorization(ResourceType.CLUSTER, null, REQUIRED_GET_AUTHORIZATIONS);

    Set<String> requestedIds = getRequestPropertyIds(request, predicate);
    Set<Resource> resources = new HashSet<>();

    AmbariManagementController managementController = getManagementController();
    Clusters clusters = managementController.getClusters();

    for (Map<String, Object> propertyMap : getPropertyMaps(predicate)) {
      String clusterName = getClusterName(propertyMap);

      Cluster cluster;
      try {
        cluster = clusters.getCluster(clusterName);
        if (cluster == null) {
          throw new NoSuchParentResourceException(String.format("A cluster with the name %s does not exist.", clusterName));
        }
      } catch (AmbariException e) {
        throw new NoSuchParentResourceException(String.format("A cluster with the name %s does not exist.", clusterName));
      }

      AuthorizationHelper.verifyAuthorization(ResourceType.CLUSTER, cluster.getResourceId(), REQUIRED_GET_AUTHORIZATIONS);

      OidcDescriptorType descriptorType = getOidcDescriptorType(propertyMap);
      if (descriptorType == null) {
        for (OidcDescriptorType type : OidcDescriptorType.values()) {
          resources.add(toResource(clusterName, type, cluster, requestedIds));
        }
      } else {
        resources.add(toResource(clusterName, descriptorType, cluster, requestedIds));
      }
    }

    return resources;
  }

  @Override
  protected Set<String> getPKPropertyIds() {
    return PK_PROPERTY_IDS;
  }

  private String getClusterName(Map<String, Object> propertyMap) {
    String clusterName = (String) propertyMap.get(CLUSTER_OIDC_DESCRIPTOR_CLUSTER_NAME_PROPERTY_ID);
    if (StringUtils.isEmpty(clusterName)) {
      throw new IllegalArgumentException("Invalid argument, cluster name is required");
    }
    return clusterName;
  }

  private OidcDescriptorType getOidcDescriptorType(Map<String, Object> propertyMap) {
    String type = (String) propertyMap.get(CLUSTER_OIDC_DESCRIPTOR_TYPE_PROPERTY_ID);
    if (StringUtils.isEmpty(type)) {
      return null;
    }
    return OidcDescriptorType.fromString(type);
  }

  private Resource toResource(String clusterName, OidcDescriptorType type, Cluster cluster, Set<String> requestedIds)
    throws SystemException {
    Resource resource = new ResourceImpl(Type.ClusterOidcDescriptor);
    setResourceProperty(resource, CLUSTER_OIDC_DESCRIPTOR_CLUSTER_NAME_PROPERTY_ID, clusterName, requestedIds);
    setResourceProperty(resource, CLUSTER_OIDC_DESCRIPTOR_TYPE_PROPERTY_ID, type.name(), requestedIds);

    if (cluster != null) {
      try {
        OidcDescriptor descriptor = buildDescriptor(type, cluster);
        if (descriptor != null) {
          setResourceProperty(resource, CLUSTER_OIDC_DESCRIPTOR_DESCRIPTOR_PROPERTY_ID, descriptor.toMap(), requestedIds);
        }
      } catch (Exception e) {
        throw new SystemException("An error occurred building the cluster's OIDC descriptor", e);
      }
    }

    return resource;
  }

  private OidcDescriptor buildDescriptor(OidcDescriptorType type, Cluster cluster) throws Exception {
    switch (type) {
      case STACK:
        return getStackDescriptor(cluster);
      case USER:
        return getUserDescriptor(cluster);
      case COMPOSITE: {
        OidcDescriptor stack = getStackDescriptor(cluster);
        OidcDescriptor user = getUserDescriptor(cluster);
        if (stack == null) {
          return user;
        }
        if (user != null) {
          stack.update(user);
        }
        return stack;
      }
      default:
        return null;
    }
  }

  private OidcDescriptor getStackDescriptor(Cluster cluster) throws Exception {
    StackId stackId = cluster.getDesiredStackVersion();
    return getManagementController().getAmbariMetaInfo()
      .getOidcDescriptor(stackId.getStackName(), stackId.getStackVersion());
  }

  private OidcDescriptor getUserDescriptor(Cluster cluster) {
    TreeMap<String, String> foreignKeys = new TreeMap<>();
    foreignKeys.put("cluster", String.valueOf(cluster.getClusterId()));
    ArtifactEntity entity = artifactDAO.findByNameAndForeignKeys(OIDC_DESCRIPTOR_ARTIFACT_NAME, foreignKeys);
    if (entity == null) {
      return null;
    }
    return oidcDescriptorFactory.createInstance(entity.getArtifactData());
  }

  private enum OidcDescriptorType {
    STACK,
    USER,
    COMPOSITE;

    static OidcDescriptorType fromString(String value) {
      try {
        return OidcDescriptorType.valueOf(value.toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(String.format("Invalid OIDC descriptor type: %s", value));
      }
    }
  }
}
