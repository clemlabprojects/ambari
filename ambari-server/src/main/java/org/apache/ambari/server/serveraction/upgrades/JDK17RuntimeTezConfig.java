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
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.UpgradeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Migrates Tez JVM options into the new tez-env base/extra properties and clears the legacy tez-site
 * AM/task opts so they can be composed at runtime. This preserves admin values while enabling
 * JDK-aware defaults going forward.
 */
public class JDK17RuntimeTezConfig extends AbstractUpgradeServerAction {

  private static final Logger LOG = LoggerFactory.getLogger(JDK17RuntimeTezConfig.class);

  private static final String TEZ_SITE = "tez-site";
  private static final String TEZ_ENV = "tez-env";
  private static final String TEZ_AM_OPTS = "tez.am.launch.cmd-opts";
  private static final String TEZ_TASK_OPTS = "tez.task.launch.cmd-opts";
  private static final String TEZ_AM_BASE = "tez_am_base_java_opts";
  private static final String TEZ_AM_EXTRA = "tez_am_extra_java_opts";
  private static final String TEZ_TASK_BASE = "tez_task_base_java_opts";
  private static final String TEZ_TASK_EXTRA = "tez_task_extra_java_opts";
  private static final String HEAP_DUMP_PLACEHOLDER = "{{heap_dump_opts}}";

  private static final String TARGET_STACK_VERSION = "1.3";

  @Override
  public CommandReport execute(ConcurrentMap<String, Object> requestSharedDataContext)
      throws AmbariException, InterruptedException {

    String clusterName = getExecutionCommand().getClusterName();
    Cluster cluster = getClusters().getCluster(clusterName);
    UpgradeContext upgradeContext = getUpgradeContext(cluster);
    StackId targetStackId = getTargetStackId(cluster, upgradeContext);

    if (!TARGET_STACK_VERSION.equals(targetStackId.getStackVersion())) {
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}",
          String.format("Target Stack is not %s, skipping Tez JVM config migration", TARGET_STACK_VERSION), "");
    }

    Config tezSite = cluster.getDesiredConfigByType(TEZ_SITE);
    Config tezEnv = cluster.getDesiredConfigByType(TEZ_ENV);
    if (tezSite == null || tezEnv == null) {
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}",
          "Tez configuration not found; skipping Tez JVM config migration", "");
    }

    Map<String, String> tezSiteProps = new HashMap<>(tezSite.getProperties());
    Map<String, String> tezEnvProps = new HashMap<>(tezEnv.getProperties());

    boolean updated = migrateTezJvmOpts(tezSiteProps, tezEnvProps);

    if (!updated) {
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}",
          "Tez JVM opts already empty or migrated; no changes made", "");
    }

    tezSite.setProperties(tezSiteProps);
    tezSite.save();

    tezEnv.setProperties(tezEnvProps);
    tezEnv.save();

    agentConfigsHolder.updateData(cluster.getClusterId(),
        cluster.getHosts().stream().map(Host::getHostId).collect(Collectors.toList()));

    LOG.info("Migrated Tez JVM opts to tez-env for cluster {}", clusterName);
    return createCommandReport(0, HostRoleStatus.COMPLETED, "{}",
        "Migrated Tez JVM opts to tez-env base/extra properties", "");
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

  private String safe(String value) {
    return value == null ? "" : value.trim();
  }

  /**
   * Migrates Tez JVM opts from tez-site into tez-env base/extra fields.
   *
   * @return true if any change was made.
   */
  boolean migrateTezJvmOpts(Map<String, String> tezSiteProps, Map<String, String> tezEnvProps) {
    String amOpts = safe(tezSiteProps.get(TEZ_AM_OPTS));
    String taskOpts = safe(tezSiteProps.get(TEZ_TASK_OPTS));

    boolean updated = false;
    if (!amOpts.isEmpty()) {
      tezEnvProps.put(TEZ_AM_BASE, amOpts);
      if (safe(tezEnvProps.get(TEZ_AM_EXTRA)).isEmpty()) {
        tezEnvProps.put(TEZ_AM_EXTRA, HEAP_DUMP_PLACEHOLDER);
      }
      tezSiteProps.put(TEZ_AM_OPTS, "");
      updated = true;
    }

    if (!taskOpts.isEmpty()) {
      tezEnvProps.put(TEZ_TASK_BASE, taskOpts);
      if (safe(tezEnvProps.get(TEZ_TASK_EXTRA)).isEmpty()) {
        tezEnvProps.put(TEZ_TASK_EXTRA, HEAP_DUMP_PLACEHOLDER);
      }
      tezSiteProps.put(TEZ_TASK_OPTS, "");
      updated = true;
    }
    return updated;
  }
}
