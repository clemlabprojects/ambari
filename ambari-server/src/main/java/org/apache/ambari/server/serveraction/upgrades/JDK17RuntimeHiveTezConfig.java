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

package org.apache.ambari.server.serveraction.upgrades;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.stack.HiveJdkConfigurationSync;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.UpgradeContext;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

/**
 * Migrates Hive Tez JVM options to JDK-safe defaults for ODP 1.3 upgrades
 * when the cluster still carries managed legacy values from older stack defaults.
 */
public class JDK17RuntimeHiveTezConfig extends AbstractUpgradeServerAction {

  private static final Logger LOG = LoggerFactory.getLogger(JDK17RuntimeHiveTezConfig.class);

  private static final String HIVE_SITE = "hive-site";
  private static final String HIVE_TEZ_JAVA_OPTS = "hive.tez.java.opts";
  private static final String TARGET_STACK_VERSION = "1.3";

  @Inject
  private Configuration configuration;

  @Override
  public CommandReport execute(ConcurrentMap<String, Object> requestSharedDataContext)
      throws AmbariException, InterruptedException {

    String clusterName = getExecutionCommand().getClusterName();
    Cluster cluster = getClusters().getCluster(clusterName);
    UpgradeContext upgradeContext = getUpgradeContext(cluster);
    StackId targetStackId = getTargetStackId(cluster, upgradeContext);

    if (!TARGET_STACK_VERSION.equals(targetStackId.getStackVersion())) {
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}",
          String.format("Target Stack is not %s, skipping Hive Tez JVM config migration", TARGET_STACK_VERSION), "");
    }

    Config hiveSite = cluster.getDesiredConfigByType(HIVE_SITE);
    if (hiveSite == null) {
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}",
          "Hive configuration not found; skipping Hive Tez JVM config migration", "");
    }

    Integer javaMajor = resolveJavaMajor();
    if (javaMajor == null || javaMajor <= 0) {
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}",
          "Unable to determine stack Java version; skipping Hive Tez JVM config migration", "");
    }

    Map<String, String> hiveSiteProps = new HashMap<>(hiveSite.getProperties());
    String desiredOpts = HiveJdkConfigurationSync.buildHiveTezOptsForJavaMajor(javaMajor);
    String currentOpts = safe(hiveSiteProps.get(HIVE_TEZ_JAVA_OPTS));

    if (!shouldUpdate(currentOpts, desiredOpts)) {
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}",
          "Hive Tez JVM opts already aligned or customized; no changes made", "");
    }

    hiveSiteProps.put(HIVE_TEZ_JAVA_OPTS, desiredOpts);
    hiveSite.setProperties(hiveSiteProps);
    hiveSite.save();

    agentConfigsHolder.updateData(cluster.getClusterId(),
        cluster.getHosts().stream().map(Host::getHostId).collect(Collectors.toList()));

    LOG.info("Migrated Hive Tez JVM opts for cluster {} to JDK {} defaults", clusterName, javaMajor);
    return createCommandReport(0, HostRoleStatus.COMPLETED, "{}",
        "Migrated Hive Tez JVM opts to JDK-aware defaults", "");
  }

  private StackId getTargetStackId(Cluster cluster, UpgradeContext upgradeContext) throws AmbariException {
    Set<StackId> stackIds = cluster.getServices().values().stream().map(service -> {
      RepositoryVersionEntity targetRepoVersion = upgradeContext.getTargetRepositoryVersion(service.getName());
      return targetRepoVersion.getStackId();
    }).collect(Collectors.toSet());

    if (stackIds.size() != 1) {
      throw new AmbariException("Services are deployed from multiple stacks and cannot determine a unique one.");
    }

    return stackIds.iterator().next();
  }

  private Integer resolveJavaMajor() {
    String stackJavaVersion = StringUtils.trimToNull(configuration.getStackJavaVersion());
    if (stackJavaVersion != null) {
      return HiveJdkConfigurationSync.parseJavaMajor(stackJavaVersion);
    }

    int javaVersion = configuration.getJavaVersion();
    if (javaVersion > 0) {
      return javaVersion;
    }

    return null;
  }

  private String safe(String value) {
    return value == null ? "" : value.trim();
  }

  private boolean shouldUpdate(String current, String desired) {
    String normalizedCurrent = normalize(current);
    String normalizedDesired = normalize(desired);

    if (normalizedDesired.equals(normalizedCurrent)) {
      return false;
    }

    return HiveJdkConfigurationSync.shouldManage(current);
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().replaceAll("\\s+", " ");
  }
}
