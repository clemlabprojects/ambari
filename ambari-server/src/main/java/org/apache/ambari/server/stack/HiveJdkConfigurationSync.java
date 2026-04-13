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
 * Ensures Hive Tez JVM options in {@code hive-site} reflect the stack JDK configured on the server.
 * This keeps ODP 1.3 clusters on JDK-safe defaults even when persisted configs still contain legacy
 * JDK8-only flags from older stack defaults or external deployment tooling.
 */
@Singleton
public class HiveJdkConfigurationSync {
  private static final Logger LOG = LoggerFactory.getLogger(HiveJdkConfigurationSync.class);

  static final String HIVE_SITE = "hive-site";
  static final String HIVE_TEZ_JAVA_OPTS = "hive.tez.java.opts";
  private static final String SYSTEM_USER = "ambari-server";

  private static final String HIVE_TEZ_DEFAULT_OPTS_JDK8 =
      "-server -Djava.net.preferIPv4Stack=true -XX:NewRatio=8 -XX:+UseNUMA "
          + "-XX:+UseG1GC -XX:+ResizeTLAB -XX:+PrintGCDetails -verbose:gc -XX:+PrintGCTimeStamps";

  private static final String HIVE_TEZ_DEFAULT_OPTS_JDK11_PLUS =
      "-server -Djava.net.preferIPv4Stack=true -XX:NewRatio=8 -XX:+UseNUMA "
          + "-Xlog:gc*,gc+heap*=info,gc+age=trace "
          + "--add-opens=java.base/java.lang=ALL-UNNAMED "
          + "--add-opens=java.base/java.net=ALL-UNNAMED "
          + "--add-opens=java.base/java.nio=ALL-UNNAMED "
          + "--add-opens=java.base/java.util=ALL-UNNAMED "
          + "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED "
          + "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED";

  private static final String LEGACY_HIVE_TEZ_STACK_DEFAULT =
      "-server -Xmx545m -Djava.net.preferIPv4Stack=true -XX:NewRatio=8 -XX:+UseNUMA "
          + "-XX:+UseParallelGC -XX:+PrintGCDetails -verbose:gc -XX:+PrintGCTimeStamps";

  private static final String LEGACY_HIVE_TEZ_ANSIBLE =
      "-server -Xmx545m -Djava.net.preferIPv4Stack=true -XX:NewRatio=8 -XX:+UseNUMA "
          + "-XX:+UseG1GC -XX:+PrintGCDetails -verbose:gc -XX:+PrintGCTimeStamps";

  private final Clusters clusters;
  private final AmbariManagementController controller;
  private final Configuration configuration;

  @Inject
  public HiveJdkConfigurationSync(Clusters clusters, AmbariManagementController controller,
      Configuration configuration) {
    this.clusters = clusters;
    this.controller = controller;
    this.configuration = configuration;
  }

  public void process() {
    Integer javaMajor = resolveJavaMajor();
    if (javaMajor == null || javaMajor <= 0) {
      LOG.info("Skipping Hive JVM option sync: unable to determine stack Java version.");
      return;
    }

    Map<String, Cluster> clusterMap = clusters.getClusters();
    if (clusterMap == null || clusterMap.isEmpty()) {
      LOG.debug("No clusters found; skipping Hive JVM option sync.");
      return;
    }

    for (Cluster cluster : clusterMap.values()) {
      try {
        reconcileCluster(cluster, javaMajor);
      } catch (Exception ex) {
        LOG.warn("Unable to synchronize Hive Tez JVM options for cluster {}: {}",
            cluster.getClusterName(), ex.getMessage(), ex);
      }
    }
  }

  private void reconcileCluster(Cluster cluster, int javaMajor) throws AmbariException {
    StackId stackId = cluster.getDesiredStackVersion();
    if (stackId == null || !"ODP".equalsIgnoreCase(stackId.getStackName())
        || !"1.3".equals(stackId.getStackVersion())) {
      return;
    }

    Map<String, Service> services = cluster.getServices();
    if (services == null || !services.containsKey("HIVE")) {
      return;
    }

    Config hiveSite = cluster.getDesiredConfigByType(HIVE_SITE);
    if (hiveSite == null) {
      LOG.debug("Cluster {} has HIVE but no hive-site configuration; skipping Hive JVM option sync.",
          cluster.getClusterName());
      return;
    }

    String desiredOpts = buildHiveTezOptsForJavaMajor(javaMajor);
    String currentOpts = hiveSite.getProperties().get(HIVE_TEZ_JAVA_OPTS);
    if (!shouldUpdate(currentOpts, desiredOpts)) {
      LOG.debug("Hive Tez JVM options already aligned for cluster {}", cluster.getClusterName());
      return;
    }

    Map<String, String> updatedProps = new HashMap<>(hiveSite.getProperties());
    updatedProps.put(HIVE_TEZ_JAVA_OPTS, desiredOpts);

    Map<String, Map<String, String>> attributes = hiveSite.getPropertiesAttributes();
    if (attributes == null) {
      attributes = Collections.emptyMap();
    }

    Config newConfig = controller.createConfig(cluster, stackId, HIVE_SITE, updatedProps,
        "version" + System.currentTimeMillis(), attributes);

    ServiceConfigVersionResponse response = cluster.addDesiredConfig(SYSTEM_USER,
        Collections.singleton(newConfig),
        String.format("Updated Hive Tez JVM options for JDK %s", javaMajor));

    if (response != null) {
      LOG.info("Synchronized Hive Tez JVM options for cluster {} to JDK {} defaults; new config version {}",
          cluster.getClusterName(), javaMajor, response.getVersion());
    } else {
      LOG.info("Synchronized Hive Tez JVM options for cluster {} to JDK {} defaults",
          cluster.getClusterName(), javaMajor);
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

  public static String buildHiveTezOptsForJavaMajor(int javaMajor) {
    return javaMajor >= 11 ? HIVE_TEZ_DEFAULT_OPTS_JDK11_PLUS : HIVE_TEZ_DEFAULT_OPTS_JDK8;
  }

  public static boolean shouldManage(String current) {
    String normalizedCurrent = normalize(current);
    if (StringUtils.isBlank(normalizedCurrent)) {
      return true;
    }

    return managedDefaults().contains(normalizedCurrent);
  }

  private boolean shouldUpdate(String current, String desired) {
    String normalizedCurrent = normalize(current);
    String normalizedDesired = normalize(desired);

    if (normalizedDesired.equals(normalizedCurrent)) {
      return false;
    }

    return shouldManage(current);
  }

  private static Set<String> managedDefaults() {
    Set<String> defaults = new HashSet<>();

    defaults.add(normalize(buildHiveTezOptsForJavaMajor(8)));
    defaults.add(normalize(buildHiveTezOptsForJavaMajor(11)));
    defaults.add(normalize(LEGACY_HIVE_TEZ_STACK_DEFAULT));
    defaults.add(normalize(LEGACY_HIVE_TEZ_ANSIBLE));

    return defaults;
  }

  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    return value.trim().replaceAll("\\s+", " ");
  }

  public static int parseJavaMajor(String version) {
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
