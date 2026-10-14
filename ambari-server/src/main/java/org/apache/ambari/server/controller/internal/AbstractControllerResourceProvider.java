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

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.ResourceProviderFactory;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.ResourceProvider;
import org.apache.ambari.server.controller.utilities.ClusterControllerHelper;
import org.apache.ambari.server.security.authorization.AuthorizationHelper;
import org.apache.ambari.server.security.authorization.ResourceType;
import org.apache.ambari.server.security.authorization.RoleAuthorization;
import org.apache.ambari.server.state.Cluster;
import org.apache.commons.lang.StringUtils;
import org.springframework.security.core.Authentication;

/**
 * Abstract resource provider implementation that maps to an Ambari management controller.
 */
public abstract class AbstractControllerResourceProvider extends AbstractAuthorizedResourceProvider {

  private static ResourceProviderFactory resourceProviderFactory;
  /**
   * The management controller to delegate to.
   */
  private final AmbariManagementController managementController;


  // ----- Constructors ------------------------------------------------------

  /**
   * Create a new resource provider for the given management controller. This
   * constructor will initialize the specified {@link Resource.Type} with the
   * provided keys. It should be used in cases where the provider declares its
   * own keys instead of reading them from a JSON file.
   *
   * @param type
   *          the type to set the properties for (not {@code null}).
   * @param propertyIds
   *          the property ids
   * @param keyPropertyIds
   *          the key property ids
   * @param managementController
   *          the management controller
   */
  AbstractControllerResourceProvider(Resource.Type type, Set<String> propertyIds,
      Map<Resource.Type, String> keyPropertyIds, AmbariManagementController managementController) {
    super(type, propertyIds, keyPropertyIds);
    this.managementController = managementController;
  }

  public static void init(ResourceProviderFactory factory) {
    resourceProviderFactory = factory;
  }


  // ----- accessors ---------------------------------------------------------

  /**
   * Get the associated management controller.
   *
   * @return the associated management controller
   */
  protected AmbariManagementController getManagementController() {
    return managementController;
  }


  // ----- utility methods ---------------------------------------------------

  /**
   * Gets the resource id for the named cluster
   *
   * @param clusterName the name of the relevant cluster
   * @return the resource id or null if not found
   * @throws AmbariException if the named cluster does not exist
   */
  protected Long getClusterId(String clusterName) throws AmbariException {
    Cluster cluster = (clusterName == null) ? null : managementController.getClusters().getCluster(clusterName);
    return (cluster == null) ? null : cluster.getClusterId();
  }

  /**
   * Gets the resource id for the named cluster
   *
   * @param clusterName the name of the relevant cluster
   * @return the resource id or null if not found
   * @throws AmbariException if the named cluster does not exist
   */
  protected Long getClusterResourceId(String clusterName) throws AmbariException {
    Cluster cluster = (clusterName == null) ? null : managementController.getClusters().getCluster(clusterName);
    return (cluster == null) ? null : cluster.getResourceId();
  }

  /**
   * Gets the resource id for the cluster with the specified id
   *
   * @param clusterId the id of the relevant cluster
   * @return the resource id or null if not found
   * @throws AmbariException if the cluster does not exist
   */
  protected Long getClusterResourceId(Long clusterId) throws AmbariException {
    Cluster cluster = (clusterId == null) ? null : managementController.getClusters().getClusterById(clusterId);
    return (cluster == null) ? null : cluster.getResourceId();
  }

  /**
   * Tests whether the authenticated user holds one of the required authorizations on every cluster
   * named by the items of a request.
   * <p/>
   * Providers whose request body names the cluster to act on use this so that the check is made on
   * the cluster the operation really touches, rather than on any cluster. A name that matches no
   * cluster is checked against any cluster: the operation then fails on the unknown name, so nothing
   * is done on its behalf.
   *
   * @param authentication         the authenticated user and associated access privileges
   * @param request                the request whose items name the clusters
   * @param clusterNameOf          resolves the cluster name of one request item, exactly as the operation does
   * @param requiredAuthorizations a set of authorizations for which one match is needed on every cluster
   * @return true if authorized on every named cluster; otherwise false
   */
  protected boolean isAuthorizedOnRequestedClusters(Authentication authentication, Request request,
                                                    Function<Map<String, Object>, String> clusterNameOf,
                                                    Set<RoleAuthorization> requiredAuthorizations) {
    Set<Map<String, Object>> items = (request == null) ? null : request.getProperties();
    if (items == null || items.isEmpty()) {
      return AuthorizationHelper.isAuthorized(authentication, ResourceType.CLUSTER, null, requiredAuthorizations);
    }

    for (Map<String, Object> properties : items) {
      String clusterName = clusterNameOf.apply(properties);
      Long resourceId = null;
      if (StringUtils.isNotBlank(clusterName)) {
        try {
          resourceId = getClusterResourceId(clusterName);
        } catch (AmbariException e) {
          // Unknown cluster: the operation reports it once authorization is settled.
        }
      }
      if (!AuthorizationHelper.isAuthorized(authentication, ResourceType.CLUSTER, resourceId, requiredAuthorizations)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Factory method for obtaining a resource provider based on a given type and management controller.
   *
   * @param type                  the resource type
   * @param managementController  the management controller
   *
   * @return a new resource provider
   */
  public static ResourceProvider getResourceProvider(Resource.Type type,
                                                     AmbariManagementController managementController) {

    switch (type.getInternalType()) {
      case ADHOC_KEYTAB:
        return new AdhocKeytabResourceProvider(managementController);
      case RANGER_PLUGIN_REPOSITORY:
        return new RangerPluginResourceProvider(managementController);
      case RANGER_POLICY:
        return new RangerPolicyResourceProvider(managementController);
      case Cluster:
        return new ClusterResourceProvider(managementController);
      case Service:
        return resourceProviderFactory.getServiceResourceProvider(managementController);
      case Component:
        return resourceProviderFactory.getComponentResourceProvider(managementController);
      case Host:
        return resourceProviderFactory.getHostResourceProvider(managementController);
      case HostComponent:
        return resourceProviderFactory.getHostComponentResourceProvider(managementController);
      case Configuration:
        return new ConfigurationResourceProvider(managementController);
      case ServiceConfigVersion:
        return new ServiceConfigVersionResourceProvider(managementController);
      case Action:
        return new ActionResourceProvider(managementController);
      case Request:
        return new RequestResourceProvider(managementController);
      case Task:
        return new TaskResourceProvider(managementController);
      case User:
        return resourceProviderFactory.getUserResourceProvider(managementController);
      case UserAuthenticationSource:
        return resourceProviderFactory.getUserAuthenticationSourceResourceProvider();
      case Group:
        return new GroupResourceProvider(managementController);
      case Member:
        return resourceProviderFactory.getMemberResourceProvider(managementController);
      case Upgrade:
        return resourceProviderFactory.getUpgradeResourceProvider(managementController);
      case Stack:
        return new StackResourceProvider(managementController);
      case StackVersion:
        return new StackVersionResourceProvider(managementController);
      case ClusterStackVersion:
        return resourceProviderFactory.getClusterStackVersionResourceProvider(managementController);
      case HostStackVersion:
        return new HostStackVersionResourceProvider(managementController);
      case StackService:
        return new StackServiceResourceProvider(managementController);
      case StackServiceComponent:
        return new StackServiceComponentResourceProvider(managementController);
      case StackConfiguration:
        return new StackConfigurationResourceProvider(managementController);
      case StackConfigurationDependency:
        return new StackConfigurationDependencyResourceProvider(managementController);
      case StackLevelConfiguration:
        return new StackLevelConfigurationResourceProvider(managementController);
      case ExtensionLink:
        return new ExtensionLinkResourceProvider(managementController);
      case Extension:
        return new ExtensionResourceProvider(managementController);
      case ExtensionVersion:
        return new ExtensionVersionResourceProvider(managementController);
      case RootService:
        return new RootServiceResourceProvider(managementController);
      case RootServiceComponent:
        return new RootServiceComponentResourceProvider(managementController);
      case RootServiceComponentConfiguration:
        return resourceProviderFactory.getRootServiceHostComponentConfigurationResourceProvider();
      case RootServiceHostComponent:
        return new RootServiceHostComponentResourceProvider(managementController);
      case ConfigGroup:
        return new ConfigGroupResourceProvider(managementController);
      case RequestSchedule:
        return new RequestScheduleResourceProvider(managementController);
      case HostComponentProcess:
        return new HostComponentProcessResourceProvider(managementController);
      case Blueprint:
        return new BlueprintResourceProvider(managementController);
      case KerberosDescriptor:
        return resourceProviderFactory.getKerberosDescriptorResourceProvider(managementController);
      case Recommendation:
        return new RecommendationResourceProvider(managementController);
      case Validation:
        return new ValidationResourceProvider(managementController);
      case ClientConfig:
        return new ClientConfigResourceProvider(managementController);
      case RepositoryVersion:
        return resourceProviderFactory.getRepositoryVersionResourceProvider();
      case CompatibleRepositoryVersion:
        return new CompatibleRepositoryVersionResourceProvider(managementController);
      case StackArtifact:
        return new StackArtifactResourceProvider(managementController);
      case Theme:
        return new ThemeArtifactResourceProvider(managementController);
      case QuickLink:
        return new QuickLinkArtifactResourceProvider(managementController);
      case ActiveWidgetLayout:
        return new ActiveWidgetLayoutResourceProvider(managementController);
      case WidgetLayout:
        return new WidgetLayoutResourceProvider(managementController);
      case Widget:
        return new WidgetResourceProvider(managementController);
      case HostKerberosIdentity:
        return resourceProviderFactory.getHostKerberosIdentityResourceProvider(managementController);
      case Credential:
        return resourceProviderFactory.getCredentialResourceProvider(managementController);
      case RoleAuthorization:
        return new RoleAuthorizationResourceProvider(managementController);
      case UserAuthorization:
        return new UserAuthorizationResourceProvider(managementController);
      case VersionDefinition:
        return new VersionDefinitionResourceProvider();
      case ClusterKerberosDescriptor:
        return new ClusterKerberosDescriptorResourceProvider(managementController);
      case ClusterOidcDescriptor:
        return new ClusterOidcDescriptorResourceProvider(managementController);
      case LoggingQuery:
        return new LoggingResourceProvider(managementController);
      case AlertTarget:
        return resourceProviderFactory.getAlertTargetResourceProvider();
      case ViewInstance:
        return resourceProviderFactory.getViewInstanceResourceProvider();
      case Auth:
        return resourceProviderFactory.getAuthResourceProvider(managementController);
      default:
        throw new IllegalArgumentException("Unknown type " + type);
    }
  }

  /**
   * Obtain a resource provider based on type.
   *
   * @param type  resource provider type
   *
   * @return resource provider for the specified type
   */
  public static ResourceProvider getResourceProvider(Resource.Type type) {
    return ((ClusterControllerImpl) ClusterControllerHelper.getClusterController()).
        ensureResourceProvider(type);
  }

}
