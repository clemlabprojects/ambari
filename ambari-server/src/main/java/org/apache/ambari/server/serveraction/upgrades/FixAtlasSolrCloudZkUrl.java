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

import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.Host;
import org.apache.commons.lang.StringUtils;

/**
 * Normalizes the Atlas SolrCloud ZooKeeper URL so chroot is appended only once
 * at the end of the quorum string (host1:2181,host2:2181/chroot).
 */
public class FixAtlasSolrCloudZkUrl extends AbstractUpgradeServerAction {
  private static final String SERVICE_ATLAS = "ATLAS";
  private static final String APPLICATION_PROPERTIES = "application-properties";

  private static final String SOLR_BACKEND_PROPERTY = "atlas.graph.index.search.backend";
  private static final String SOLR_MODE_PROPERTY = "atlas.graph.index.search.solr.mode";
  private static final String SOLR_ZK_URL_PROPERTY = "atlas.graph.index.search.solr.zookeeper-url";

  @Override
  public CommandReport execute(ConcurrentMap<String, Object> requestSharedDataContext)
      throws AmbariException, InterruptedException {

    Cluster cluster = getClusters().getCluster(getExecutionCommand().getClusterName());
    if (!cluster.getServices().containsKey(SERVICE_ATLAS)) {
      return completed("Skipping Atlas SolrCloud ZooKeeper URL normalization because ATLAS is not installed.");
    }

    Config appPropertiesConfig = cluster.getDesiredConfigByType(APPLICATION_PROPERTIES);
    if (appPropertiesConfig == null || appPropertiesConfig.getProperties() == null) {
      return completed(
          "Skipping Atlas SolrCloud ZooKeeper URL normalization because application-properties is missing.");
    }

    Map<String, String> appProperties = appPropertiesConfig.getProperties();
    String searchBackend = StringUtils.trimToEmpty(appProperties.get(SOLR_BACKEND_PROPERTY));
    if (!"solr".equalsIgnoreCase(searchBackend)) {
      return completed(String.format("Skipping Atlas SolrCloud ZooKeeper URL normalization because %s is %s.",
          SOLR_BACKEND_PROPERTY, searchBackend));
    }

    String solrMode = StringUtils.trimToEmpty(appProperties.get(SOLR_MODE_PROPERTY));
    if (!"cloud".equalsIgnoreCase(solrMode)) {
      return completed(String.format("Skipping Atlas SolrCloud ZooKeeper URL normalization because %s is %s.",
          SOLR_MODE_PROPERTY, solrMode));
    }

    String currentValue = appProperties.get(SOLR_ZK_URL_PROPERTY);
    String normalizedValue = normalizeSolrCloudZookeeperUrl(currentValue);

    if (normalizedValue == null) {
      return completed(String.format(
          "Skipping Atlas SolrCloud ZooKeeper URL normalization because %s has an unsupported format: %s",
          SOLR_ZK_URL_PROPERTY, currentValue));
    }

    if (normalizedValue.equals(currentValue)) {
      return completed(String.format("No Atlas SolrCloud ZooKeeper URL normalization needed for %s.",
          SOLR_ZK_URL_PROPERTY));
    }

    Map<String, String> updatedProperties = new HashMap<>();
    updatedProperties.put(SOLR_ZK_URL_PROPERTY, normalizedValue);
    appPropertiesConfig.updateProperties(updatedProperties);
    appPropertiesConfig.save();

    agentConfigsHolder.updateData(cluster.getClusterId(),
        cluster.getHosts().stream().map(Host::getHostId).collect(toList()));

    return completed(String.format("Updated %s from [%s] to [%s].",
        SOLR_ZK_URL_PROPERTY, currentValue, normalizedValue));
  }

  static String normalizeSolrCloudZookeeperUrl(String url) {
    if (StringUtils.isBlank(url)) {
      return null;
    }

    String[] tokens = StringUtils.split(url, ',');
    if (tokens == null || tokens.length == 0) {
      return null;
    }

    List<String> hostPorts = new ArrayList<>();
    String chroot = null;

    for (String token : tokens) {
      String tokenTrimmed = StringUtils.trimToEmpty(token);
      if (tokenTrimmed.isEmpty()) {
        continue;
      }

      int slashIndex = tokenTrimmed.indexOf('/');
      String hostPort = slashIndex >= 0 ? tokenTrimmed.substring(0, slashIndex).trim() : tokenTrimmed;
      if (StringUtils.isBlank(hostPort)) {
        return null;
      }

      hostPorts.add(hostPort);

      if (slashIndex >= 0) {
        String tokenChroot = normalizeChroot(tokenTrimmed.substring(slashIndex));
        if (StringUtils.isNotBlank(tokenChroot)) {
          if (chroot == null) {
            chroot = tokenChroot;
          } else if (!chroot.equals(tokenChroot)) {
            return null;
          }
        }
      }
    }

    if (hostPorts.isEmpty()) {
      return null;
    }

    String normalized = StringUtils.join(hostPorts, ",");
    if (StringUtils.isNotBlank(chroot)) {
      normalized = normalized + chroot;
    }

    return normalized;
  }

  private static String normalizeChroot(String chroot) {
    if (StringUtils.isBlank(chroot)) {
      return "";
    }

    String normalized = chroot.trim();
    if (!normalized.startsWith("/")) {
      normalized = "/" + normalized;
    }

    while (normalized.endsWith("/") && normalized.length() > 1) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }

    if ("/".equals(normalized)) {
      return "";
    }

    return normalized;
  }

  private CommandReport completed(String message) {
    return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", message, "");
  }
}
