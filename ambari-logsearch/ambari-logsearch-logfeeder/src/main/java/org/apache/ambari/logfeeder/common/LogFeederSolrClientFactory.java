/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ambari.logfeeder.common;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.LBHttpSolrClient;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.ZkStateReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Factory for creating specific Solr clients based on provided configurations (simple / LB or cloud Solr client)
 */
public class LogFeederSolrClientFactory {

  private static final Logger logger = LogManager.getLogger(LogFeederSolrClientFactory.class);

  /**
   * Creates a new Solr client. If solr urls are provided create a LB client (Use simple Http client if only 1 provided),
   * otherwise create a cloud client. That means at least providing zookeeper connection string or Solr urls are required.
   * @param zkConnectionString zookeeper connection string, e.g.: localhost1:2181,localhost2:2181/solr
   * @param solrUrls list of solr urls
   * @param collection name of the Solr collection
   * @param discover use cloud solr client to discover solr nodes, then uses LB client
   * @return created client
   */
  public SolrClient createSolrClient(String zkConnectionString, String[] solrUrls, String collection, boolean discover) {
    logger.info("Creating solr client ...");
    logger.info("Using collection=" + collection);
    if (discover && zkConnectionString.length() > 0) {
      final CloudSolrClient discoverNodesClient = createSolrCloudClient(zkConnectionString, collection);
      return createLBClientsWithDiscoverNodes(discoverNodesClient, collection);
    }
    else if (solrUrls != null && solrUrls.length > 0) {
      logger.info("Using lbHttpSolrClient with urls: {}",
        StringUtils.join(appendTo("/" + collection, solrUrls), ","));
      LBHttpSolrClient.Builder builder = new LBHttpSolrClient.Builder();
      builder.withBaseSolrUrls(solrUrls);
      return builder.build();
    } else {
      return createSolrCloudClient(zkConnectionString, collection);
    }
  }

  @VisibleForTesting
  ZkConnection createZKConnection(String zkConnectionString) {
    String split[] = zkConnectionString.split("/", 2);
    String zkChroot = null;
    final List<String> zkHosts;
    if (split.length == 1) {
      zkHosts = Arrays.asList(split[0].split(","));
    } else {
      zkHosts = Arrays.asList(split[0].split(","));
      zkChroot = ("/" + split[1]).replaceAll("/+", "/");
      if (zkChroot.endsWith("/")) {
        zkChroot = zkChroot.substring(0, zkChroot.lastIndexOf("/"));
      }
    }
    return new ZkConnection(zkHosts, zkChroot);
  }

  @VisibleForTesting
  String[] appendTo(String toAppend, String... appendees) {
    for (int i = 0; i < appendees.length; i++) {
      appendees[i] = appendees[i] + toAppend;
    }
    return appendees;
  }

  private CloudSolrClient createSolrCloudClient(String zkConnectionString, String collection) {
    logger.info("Using zookeepr. zkConnectString=" + zkConnectionString);
    final ZkConnection zkConnection = createZKConnection(zkConnectionString);
    final CloudSolrClient.Builder builder =
      new CloudSolrClient.Builder(zkConnection.getZkHosts(), Optional.ofNullable(zkConnection.getZkChroot()));
    CloudSolrClient solrClient = builder.build();
    solrClient.setDefaultCollection(collection);
    return solrClient;
  }

  private LBHttpSolrClient createLBClientsWithDiscoverNodes(CloudSolrClient discoverClient, String collection) {
    final List<String> baseUrls = waitUntilAvailableBaseUrls(discoverClient, collection);
    final String[] finalBaseUrls = appendTo("/" + collection, baseUrls.toArray(new String[0]));
    logger.info("Following URLs will be used for LB Solr client (collection: '{}'): {}", collection, StringUtils.join(finalBaseUrls));
    return new LBHttpSolrClient.Builder()
      .withBaseSolrUrls(finalBaseUrls)
      .build();
  }

  private List<String> waitUntilAvailableBaseUrls(CloudSolrClient discoverClient, String collection) {
    final List<String> baseUrls = new ArrayList<>();
    while(true) {
      try {
        ZkStateReader zkStateReader = discoverClient.getZkStateReader();
        ClusterState clusterState = zkStateReader.getClusterState();
        if (clusterState != null) {
          DocCollection docCollection = clusterState.getCollection(collection);
          if (docCollection != null) {
            List<Replica> replicas = docCollection.getReplicas();
            if (replicas != null && !replicas.isEmpty()) {
              for (Replica replica : replicas) {
                String baseUrl = replica.getBaseUrl();
                if (!baseUrls.contains(baseUrl)) {
                  baseUrls.add(baseUrl);
                }
              }
            }
          }
        }
      } catch (Exception e) {
        logger.error("Error during getting Solr node data by discovery solr loud client", e);
      }
      if (baseUrls.isEmpty()) {
        logger.info("Not found any base urls yet for '{}' collection. Retrying ...", collection);
        try {
          Thread.sleep(5000);
        } catch (InterruptedException e) {
          logger.info("Discovery solr cloud client was interrupted", e);
          Thread.currentThread().interrupt();
          break;
        }
      } else {
        try {
          logger.info("Closing discovery solr client for '{}' collection", collection);
          discoverClient.close();
        } catch (IOException e) {
          logger.error("Error during closing solr cloud client for discovering hosts", e);
        }
        break;
      }
    }
    return baseUrls;
  }

  final class ZkConnection {
    private final List<String> zkHosts;
    private final String zkChroot;

    ZkConnection(List<String> zkHosts, String zkChroot) {
      this.zkHosts = zkHosts;
      this.zkChroot = zkChroot;
    }

    List<String> getZkHosts() {
      return zkHosts;
    }

    String getZkChroot() {
      return zkChroot;
    }
  }

}
