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

package org.apache.ambari.server.stack;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.ServiceConfigVersionResponse;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.StackId;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Ensures Tez JVM options in {@code tez-site} reflect the stack JDK configured on the server.
 * This helps avoid drift when {@code ambari-server setup -java-home} changes the stack JDK
 * without re-running the stack advisor.
 */
@Singleton
public class TezJdkConfigurationSync {
  private static final Logger LOG = LoggerFactory.getLogger(TezJdkConfigurationSync.class);

  static final String TEZ_SITE = "tez-site";
  static final String TEZ_ENV = "tez-env";
  static final String TEZ_AM_LAUNCH_OPTS = "tez.am.launch.cmd-opts";
  static final String TEZ_TASK_LAUNCH_OPTS = "tez.task.launch.cmd-opts";
  static final String TEZ_AM_BASE_OPTS = "tez_am_base_java_opts";
  static final String TEZ_AM_EXTRA_OPTS = "tez_am_extra_java_opts";
  static final String TEZ_TASK_BASE_OPTS = "tez_task_base_java_opts";
  static final String TEZ_TASK_EXTRA_OPTS = "tez_task_extra_java_opts";
  static final String HEAP_DUMP_PLACEHOLDER = "{{heap_dump_opts}}";
  private static final String SYSTEM_USER = "ambari-server";

  private static final String TEZ_AM_DEFAULT_OPTS_JDK8 =
      "-XX:+PrintGCDetails -verbose:gc -XX:+PrintGCTimeStamps -XX:+UseNUMA -XX:+UseG1GC";

  private static final String TEZ_AM_DEFAULT_OPTS_JDK9_PLUS =
      "-Xlog:gc*,gc+heap*=info,gc+age=trace -XX:+UseNUMA "
          + "--add-opens=java.base/java.lang=ALL-UNNAMED "
          + "--add-opens=java.base/java.net=ALL-UNNAMED "
          + "--add-opens=java.base/java.nio=ALL-UNNAMED "
          + "--add-opens=java.base/java.util=ALL-UNNAMED "
          + "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED "
          + "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED";

  // Legacy defaults (pre ODP 1.3) that included ResizeTLAB/ExplicitGC flags.
  private static final String LEGACY_TEZ_OPTS_JDK8 =
      "-XX:+PrintGCDetails -verbose:gc -XX:+PrintGCTimeStamps -XX:+UseNUMA -XX:+UseG1GC -XX:+ResizeTLAB";
  private static final String LEGACY_TEZ_OPTS_JDK11 =
      "-Xlog:gc*,gc+heap*=info,gc+age=trace -XX:+UseNUMA --add-opens=java.base/java.lang=ALL-UNNAMED "
          + "-XX:+UseG1GC -XX:+ResizeTLAB -XX:+ExplicitGCInvokesConcurrent";

  private final Clusters clusters;
  private final AmbariManagementController controller;
  private final Configuration configuration;

  @Inject
  public TezJdkConfigurationSync(Clusters clusters, AmbariManagementController controller,
      Configuration configuration) {
    this.clusters = clusters;
    this.controller = controller;
    this.configuration = configuration;
  }

  public void process() {
    Integer javaMajor = resolveJavaMajor();
    if (javaMajor == null || javaMajor <= 0) {
      LOG.info("Skipping Tez JVM option sync: unable to determine stack Java version.");
      return;
    }

    Map<String, Cluster> clusterMap = clusters.getClusters();
    if (clusterMap == null || clusterMap.isEmpty()) {
      LOG.debug("No clusters found; skipping Tez JVM option sync.");
      return;
    }

    for (Cluster cluster : clusterMap.values()) {
      try {
        reconcileCluster(cluster, javaMajor);
      } catch (Exception ex) {
        LOG.warn("Unable to synchronize Tez JVM options for cluster {}: {}", cluster.getClusterName(), ex.getMessage(), ex);
      }
    }
  }

  private void reconcileCluster(Cluster cluster, int javaMajor) throws AmbariException {
    Map<String, Service> services = cluster.getServices();
    if (services == null || !services.containsKey("TEZ")) {
      return;
    }

    Config tezEnv = cluster.getDesiredConfigByType(TEZ_ENV);
    if (tezEnv == null) {
      LOG.debug("Cluster {} has TEZ but no tez-env configuration; skipping Tez JVM option sync.", cluster.getClusterName());
      return;
    }

    String desiredBaseOpts = buildTezOptsForJavaMajor(javaMajor, "");
    String amCurrent = tezEnv.getProperties().get(TEZ_AM_BASE_OPTS);
    String taskCurrent = tezEnv.getProperties().get(TEZ_TASK_BASE_OPTS);

    boolean updateAm = shouldUpdate(amCurrent, desiredBaseOpts);
    boolean updateTask = shouldUpdate(taskCurrent, desiredBaseOpts);

    if (!updateAm && !updateTask) {
      LOG.debug("Tez JVM options already aligned for cluster {}", cluster.getClusterName());
      return;
    }

    Map<String, String> updatedProps = new HashMap<>(tezEnv.getProperties());
    if (updateAm) {
      updatedProps.put(TEZ_AM_BASE_OPTS, desiredBaseOpts);
    }
    if (updateTask) {
      updatedProps.put(TEZ_TASK_BASE_OPTS, desiredBaseOpts);
    }

    Map<String, Map<String, String>> attributes = tezEnv.getPropertiesAttributes();
    if (attributes == null) {
      attributes = Collections.emptyMap();
    }

    StackId stackId = cluster.getDesiredStackVersion();
    Config newConfig = controller.createConfig(cluster, stackId, TEZ_ENV, updatedProps,
        "version" + System.currentTimeMillis(), attributes);

    ServiceConfigVersionResponse response = cluster.addDesiredConfig(SYSTEM_USER,
        Collections.singleton(newConfig),
        String.format("Updated Tez base JVM options for JDK %s", javaMajor));

    if (response != null) {
      LOG.info("Synchronized Tez JVM base options for cluster {} to JDK {} defaults; new config version {}",
          cluster.getClusterName(), javaMajor, response.getVersion());
    } else {
      LOG.info("Synchronized Tez JVM base options for cluster {} to JDK {} defaults", cluster.getClusterName(), javaMajor);
    }
  }

  private Integer resolveJavaMajor() {
    String stackJavaVersion = StringUtils.trimToNull(configuration.getStackJavaVersion());
    if (stackJavaVersion != null) {
      return parseJavaMajor(stackJavaVersion);
    }

    int javaVersion = configuration.getJavaVersion();
    if (javaVersion > 0) {
      return javaVersion;
    }

    return null;
  }

  static String buildTezOptsForJavaMajor(int javaMajor, String heapDumpOpts) {
    String base = javaMajor >= 9 ? TEZ_AM_DEFAULT_OPTS_JDK9_PLUS : TEZ_AM_DEFAULT_OPTS_JDK8;
    if (StringUtils.isNotBlank(heapDumpOpts)) {
      return (base + " " + heapDumpOpts).trim();
    }
    return base;
  }

  private boolean shouldUpdate(String current, String desired) {
    String normalizedCurrent = normalize(current);
    String normalizedDesired = normalize(desired);

    if (normalizedDesired.equals(normalizedCurrent)) {
      return false;
    }

    if (StringUtils.isBlank(current)) {
      return true;
    }

    return managedDefaults().contains(normalizedCurrent);
  }

  private Set<String> managedDefaults() {
    Set<String> defaults = new HashSet<>();

    defaults.add(normalize(buildTezOptsForJavaMajor(8, HEAP_DUMP_PLACEHOLDER)));
    defaults.add(normalize(buildTezOptsForJavaMajor(8, "")));
    defaults.add(normalize(buildTezOptsForJavaMajor(11, HEAP_DUMP_PLACEHOLDER)));
    defaults.add(normalize(buildTezOptsForJavaMajor(11, "")));
    defaults.add(normalize(buildLegacyTezOpts(8, "")));
    defaults.add(normalize(buildLegacyTezOpts(11, "")));

    return defaults;
  }

  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    return value.trim().replaceAll("\\s+", " ");
  }

  private static String buildLegacyTezOpts(int javaMajor, String heapDumpOpts) {
    String base = javaMajor >= 11 ? LEGACY_TEZ_OPTS_JDK11 : LEGACY_TEZ_OPTS_JDK8;
    if (StringUtils.isNotBlank(heapDumpOpts)) {
      return (base + " " + heapDumpOpts).trim();
    }
    return base;
  }

  static int parseJavaMajor(String version) {
    if (StringUtils.isBlank(version)) {
      return -1;
    }

    String trimmed = version.trim().replace("\"", "");
    if (trimmed.startsWith("1.")) {
      String[] parts = trimmed.split("\\.");
      if (parts.length > 1) {
        return Integer.parseInt(parts[1]);
      }
    }

    String[] parts = trimmed.split("\\.");
    try {
      return Integer.parseInt(parts[0]);
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
