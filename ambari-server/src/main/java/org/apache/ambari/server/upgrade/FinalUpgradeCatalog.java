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

package org.apache.ambari.server.upgrade;

import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.PropertyInfo;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.StackInfo;
import org.apache.ambari.server.state.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Injector;

/**
 * Final upgrade catalog which simply updates database version (in case if no db changes between releases)
 */
public class FinalUpgradeCatalog extends AbstractFinalUpgradeCatalog {
  private static final String ODP_STACK_NAME = "ODP";
  private static final String HDFS_SERVICE_NAME = "HDFS";
  private static final String OZONE_SERVICE_NAME = "OZONE";
  private static final String SPARK2_SERVICE_NAME = "SPARK2";
  private static final String SPARK3_SERVICE_NAME = "SPARK3";
  private static final String HIVE_SERVICE_NAME = "HIVE";
  private static final String FLINK_SERVICE_NAME = "FLINK";
  private static final String RANGER_SERVICE_NAME = "RANGER";
  private static final String SQOOP_SERVICE_NAME = "SQOOP";
  private static final String POLARIS_SERVICE_NAME = "POLARIS";
  private static final String CORE_SERVICE_NAME = "CORE";
  private static final String CORE_ENV_CONFIG = "core-env";
  private static final String CORE_SITE_CONFIG = "core-site";
  private static final String CORE_FILESYSTEM_TYPE_PROPERTY = "core_filesystem_type";
  private static final String CORE_EXTERNAL_DEFAULT_FS_PROPERTY = "core_external_default_fs";
  private static final String FS_DEFAULTFS_PROPERTY = "fs.defaultFS";
  private static final String CORE_CLIENT_COMPONENT_NAME = "CORE_CLIENT";
  private static final String CORE_UPGRADE_NOTE = "Migrate CORE service for legacy ODP cluster during Ambari upgrade";
  private static final String HDFS_FILESYSTEM_TYPE = "HDFS";
  private static final String OZONE_FILESYSTEM_TYPE = "OZONE";
  private static final String EXTERNAL_FILESYSTEM_TYPE = "EXTERNAL";
  private static final SelectorProperty[] LEGACY_FILESYSTEM_SELECTOR_PROPERTIES = new SelectorProperty[] {
      new SelectorProperty(SPARK2_SERVICE_NAME, "spark2-env", "spark2_filesystem_type"),
      new SelectorProperty(SPARK3_SERVICE_NAME, "spark3-env", "spark3_filesystem_type"),
      new SelectorProperty(HIVE_SERVICE_NAME, "hive-env", "hive_filesystem_type"),
      new SelectorProperty(FLINK_SERVICE_NAME, "flink-env", "flink_filesystem_type"),
      new SelectorProperty(RANGER_SERVICE_NAME, "ranger-env", "ranger_audit_filesystem_type"),
      new SelectorProperty(SQOOP_SERVICE_NAME, "sqoop-env", "sqoop_filesystem_type"),
      new SelectorProperty(POLARIS_SERVICE_NAME, "polaris-env", "polaris_filesystem_type")
  };

  /**
   * Logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(FinalUpgradeCatalog.class);

  @Inject
  public FinalUpgradeCatalog(Injector injector) {
    super(injector);
  }

  @Override
  protected void executeDMLUpdates() throws AmbariException, SQLException {
    updateClusterEnv();
    ensureCoreServiceForLegacyOdpClusters();
    ensureFilesystemSelectorDefaultsForLegacyOdpClusters();
  }

  /**
   * Updates {@code cluster-env} in the following ways:
   * <ul>
   * <li>Adds/Updates {@link ConfigHelper#CLUSTER_ENV_STACK_FEATURES_PROPERTY} from stack</li>
   * <li>Adds/Updates {@link ConfigHelper#CLUSTER_ENV_STACK_TOOLS_PROPERTY} from stack</li>
   * <li>Adds/Updates {@link ConfigHelper#CLUSTER_ENV_STACK_PACKAGES_PROPERTY} from stack</li>
   * </ul>
   *
   * Note: Config properties stack_features and stack_tools should always be updated to latest values as defined
   * in the stack on an Ambari upgrade.
   */
  protected void updateClusterEnv() throws AmbariException {

    AmbariManagementController ambariManagementController = injector.getInstance(
        AmbariManagementController.class);
    AmbariMetaInfo ambariMetaInfo = injector.getInstance(AmbariMetaInfo.class);

    LOG.info("Updating stack_features and stack_tools config properties.");
    Clusters clusters = ambariManagementController.getClusters();
    Map<String, Cluster> clusterMap = getCheckedClusterMap(clusters);
    for (final Cluster cluster : clusterMap.values()) {

      Set<StackId> stackIds = new HashSet<>();
      for (Service service : cluster.getServices().values()) {
        stackIds.add(service.getDesiredStackId());
      }

      for (StackId stackId : stackIds) {
        Map<String, String> propertyMap = new HashMap<>();
        StackInfo stackInfo = ambariMetaInfo.getStack(stackId.getStackName(), stackId.getStackVersion());
        List<PropertyInfo> properties = stackInfo.getProperties();
        for(PropertyInfo property : properties) {
          if(property.getName().equals(ConfigHelper.CLUSTER_ENV_STACK_FEATURES_PROPERTY) ||
              property.getName().equals(ConfigHelper.CLUSTER_ENV_STACK_TOOLS_PROPERTY) ||
              property.getName().equals(ConfigHelper.CLUSTER_ENV_STACK_PACKAGES_PROPERTY)) {
            propertyMap.put(property.getName(), property.getValue());
          }
        }
        updateConfigurationPropertiesForCluster(cluster, ConfigHelper.CLUSTER_ENV, propertyMap, true, true);
      }
    }
  }

  /**
   * Backfills the logical CORE service for clusters that were created before
   * {@code core-site} ownership was split out of HDFS.
   * <p>
   * Legacy clusters may already have a valid selected {@code core-site}
   * configuration, but without the CORE service they cannot manage that
   * configuration through the service model and later add-service flows can
   * conflict on config ownership. This migration keeps the existing
   * {@code core-site} values intact, adds CORE if needed, installs
   * {@code CORE_CLIENT} on every host, creates any missing CORE-owned config
   * types (for example {@code core-env}), and records a service config version
   * for CORE so Ambari treats it as a first-class managed service after the
   * upgrade.
   */
  protected void ensureCoreServiceForLegacyOdpClusters() throws AmbariException {
    AmbariManagementController ambariManagementController = injector.getInstance(
        AmbariManagementController.class);
    ConfigHelper configHelper = injector.getInstance(ConfigHelper.class);
    Clusters clusters = ambariManagementController.getClusters();

    for (final Cluster cluster : getCheckedClusterMap(clusters).values()) {
      StackId stackId = cluster.getDesiredStackVersion();
      if (stackId == null || !ODP_STACK_NAME.equals(stackId.getStackName())) {
        continue;
      }

      Map<String, Service> installedServices = cluster.getServices();
      Service hdfsService = installedServices.get(HDFS_SERVICE_NAME);
      if (hdfsService == null) {
        continue;
      }

      RepositoryVersionEntity desiredRepositoryVersion = hdfsService.getDesiredRepositoryVersion();
      if (desiredRepositoryVersion == null) {
        LOG.warn("Skipping CORE backfill for cluster {} because HDFS has no desired repository version",
            cluster.getClusterName());
        continue;
      }

      boolean changed = false;
      Service coreService = installedServices.get(CORE_SERVICE_NAME);
      if (coreService == null) {
        LOG.info("Adding missing CORE service to legacy ODP cluster {}", cluster.getClusterName());
        coreService = cluster.addService(CORE_SERVICE_NAME, desiredRepositoryVersion);
        changed = true;
      }

      if (!Objects.equals(coreService.getDesiredRepositoryVersion(), desiredRepositoryVersion)) {
        coreService.setDesiredRepositoryVersion(desiredRepositoryVersion);
        changed = true;
      }

      if (coreService.getDesiredState() != State.INSTALLED) {
        coreService.setDesiredState(State.INSTALLED);
        changed = true;
      }

      ServiceComponent coreClient = coreService.getServiceComponents().get(CORE_CLIENT_COMPONENT_NAME);
      if (coreClient == null) {
        LOG.info("Adding missing CORE_CLIENT component to cluster {}", cluster.getClusterName());
        coreClient = coreService.addServiceComponent(CORE_CLIENT_COMPONENT_NAME);
        changed = true;
      }

      if (!Objects.equals(coreClient.getDesiredRepositoryVersion(), desiredRepositoryVersion)) {
        coreClient.setDesiredRepositoryVersion(desiredRepositoryVersion);
        changed = true;
      }

      if (coreClient.getDesiredState() != State.INSTALLED) {
        coreClient.setDesiredState(State.INSTALLED);
        changed = true;
      }

      String desiredVersion = coreClient.getDesiredVersion();
      for (Host host : cluster.getHosts()) {
        String hostName = host.getHostName();
        ServiceComponentHost coreClientHost = coreClient.getServiceComponentHosts().get(hostName);
        if (coreClientHost == null) {
          LOG.info("Adding CORE_CLIENT to host {} in cluster {}", hostName, cluster.getClusterName());
          coreClientHost = coreClient.addServiceComponentHost(hostName);
          changed = true;
        }

        if (coreClientHost.getDesiredState() != State.INSTALLED) {
          coreClientHost.setDesiredState(State.INSTALLED);
          changed = true;
        }

        if (coreClientHost.getState() != State.INSTALLED) {
          coreClientHost.setState(State.INSTALLED);
          changed = true;
        }

        if (desiredVersion != null && !Objects.equals(coreClientHost.getVersion(), desiredVersion)) {
          coreClientHost.setVersion(desiredVersion);
          changed = true;
        }
      }

      Map<String, Map<String, String>> coreDefaults = configHelper.getDefaultProperties(stackId, CORE_SERVICE_NAME);
      Map<String, Map<String, String>> missingCoreConfigTypes = new HashMap<>();
      if (coreDefaults != null) {
        for (Map.Entry<String, Map<String, String>> entry : coreDefaults.entrySet()) {
          if (!cluster.isConfigTypeExists(entry.getKey())) {
            missingCoreConfigTypes.put(entry.getKey(), entry.getValue());
          }
        }
      }

      if (!missingCoreConfigTypes.isEmpty()) {
        LOG.info("Creating missing CORE config types {} on cluster {}", missingCoreConfigTypes.keySet(),
            cluster.getClusterName());
        configHelper.createConfigTypes(cluster, stackId, ambariManagementController, missingCoreConfigTypes,
            AUTHENTICATED_USER_NAME, CORE_UPGRADE_NOTE);
        changed = true;
      }

      if (changed) {
        cluster.createServiceConfigVersion(CORE_SERVICE_NAME, AUTHENTICATED_USER_NAME, CORE_UPGRADE_NOTE, null);
      }
    }
  }

  /**
   * Backfills the service-level filesystem selector properties introduced in
   * ODP service env configs so legacy clusters upgraded in-place through
   * Ambari have deterministic defaults before any later stack advisor pass.
   * <p>
   * This is intentionally add-only: if an administrator already set a
   * selector, upgrade must not overwrite it. When {@code core-env} is still
   * missing the selector values, this helper infers the effective cluster
   * filesystem from the current {@code core-site/fs.defaultFS}.
   */
  protected void ensureFilesystemSelectorDefaultsForLegacyOdpClusters() throws AmbariException {
    AmbariManagementController ambariManagementController = injector.getInstance(
        AmbariManagementController.class);
    Clusters clusters = ambariManagementController.getClusters();

    for (final Cluster cluster : getCheckedClusterMap(clusters).values()) {
      StackId stackId = cluster.getDesiredStackVersion();
      if (stackId == null || !ODP_STACK_NAME.equals(stackId.getStackName())) {
        continue;
      }

      Map<String, Service> installedServices = cluster.getServices();
      String clusterFilesystemType = resolveClusterFilesystemType(cluster, installedServices);
      String currentDefaultFs = getDesiredConfigProperty(cluster, CORE_SITE_CONFIG, FS_DEFAULTFS_PROPERTY);

      Map<String, String> coreEnvUpdates = new HashMap<>();
      if (!hasText(getDesiredConfigProperty(cluster, CORE_ENV_CONFIG, CORE_FILESYSTEM_TYPE_PROPERTY))) {
        coreEnvUpdates.put(CORE_FILESYSTEM_TYPE_PROPERTY, clusterFilesystemType);
      }
      if (EXTERNAL_FILESYSTEM_TYPE.equals(clusterFilesystemType)
          && hasText(currentDefaultFs)
          && !hasText(getDesiredConfigProperty(cluster, CORE_ENV_CONFIG, CORE_EXTERNAL_DEFAULT_FS_PROPERTY))) {
        coreEnvUpdates.put(CORE_EXTERNAL_DEFAULT_FS_PROPERTY, currentDefaultFs);
      }
      if (!coreEnvUpdates.isEmpty()) {
        LOG.info("Backfilling CORE filesystem selector defaults {} for cluster {}", coreEnvUpdates.keySet(),
            cluster.getClusterName());
        updateConfigurationPropertiesForCluster(cluster, CORE_ENV_CONFIG, coreEnvUpdates, false, false);
      }

      for (SelectorProperty selectorProperty : LEGACY_FILESYSTEM_SELECTOR_PROPERTIES) {
        if (!installedServices.containsKey(selectorProperty.serviceName)) {
          continue;
        }
        updateConfigurationPropertiesForCluster(
            cluster,
            selectorProperty.configType,
            Collections.singletonMap(selectorProperty.propertyName, clusterFilesystemType),
            false,
            false);
      }
    }
  }

  private String resolveClusterFilesystemType(Cluster cluster, Map<String, Service> installedServices) {
    String configuredCoreFilesystemType = getDesiredConfigProperty(cluster, CORE_ENV_CONFIG, CORE_FILESYSTEM_TYPE_PROPERTY);
    if (hasText(configuredCoreFilesystemType)) {
      String normalized = configuredCoreFilesystemType.trim().toUpperCase(Locale.ENGLISH);
      if (HDFS_FILESYSTEM_TYPE.equals(normalized)
          || OZONE_FILESYSTEM_TYPE.equals(normalized)
          || EXTERNAL_FILESYSTEM_TYPE.equals(normalized)) {
        return normalized;
      }
    }

    String defaultFs = getDesiredConfigProperty(cluster, CORE_SITE_CONFIG, FS_DEFAULTFS_PROPERTY);
    if (hasText(defaultFs)) {
      return inferFilesystemTypeFromDefaultFs(defaultFs, installedServices);
    }

    return getPreferredFilesystemType(installedServices);
  }

  private String inferFilesystemTypeFromDefaultFs(String defaultFs, Map<String, Service> installedServices) {
    String normalizedDefaultFs = defaultFs.trim().toLowerCase(Locale.ENGLISH);
    if (normalizedDefaultFs.startsWith("ofs://") || normalizedDefaultFs.startsWith("o3fs://")) {
      return installedServices.containsKey(OZONE_SERVICE_NAME) ? OZONE_FILESYSTEM_TYPE : EXTERNAL_FILESYSTEM_TYPE;
    }

    if (normalizedDefaultFs.startsWith("hdfs://")
        || normalizedDefaultFs.startsWith("viewfs://")
        || normalizedDefaultFs.startsWith("webhdfs://")) {
      return installedServices.containsKey(HDFS_SERVICE_NAME) ? HDFS_FILESYSTEM_TYPE : EXTERNAL_FILESYSTEM_TYPE;
    }

    if (normalizedDefaultFs.matches("^[a-z][a-z0-9+\\-.]*://.*$")) {
      return EXTERNAL_FILESYSTEM_TYPE;
    }

    return getPreferredFilesystemType(installedServices);
  }

  private String getPreferredFilesystemType(Map<String, Service> installedServices) {
    if (installedServices.containsKey(HDFS_SERVICE_NAME)) {
      return HDFS_FILESYSTEM_TYPE;
    }
    if (installedServices.containsKey(OZONE_SERVICE_NAME)) {
      return OZONE_FILESYSTEM_TYPE;
    }
    return EXTERNAL_FILESYSTEM_TYPE;
  }

  private String getDesiredConfigProperty(Cluster cluster, String configType, String propertyName) {
    if (cluster == null || propertyName == null) {
      return null;
    }

    org.apache.ambari.server.state.Config config = cluster.getDesiredConfigByType(configType);
    if (config == null || config.getProperties() == null) {
      return null;
    }
    return config.getProperties().get(propertyName);
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private static final class SelectorProperty {
    private final String serviceName;
    private final String configType;
    private final String propertyName;

    private SelectorProperty(String serviceName, String configType, String propertyName) {
      this.serviceName = serviceName;
      this.configType = configType;
      this.propertyName = propertyName;
    }
  }

}
