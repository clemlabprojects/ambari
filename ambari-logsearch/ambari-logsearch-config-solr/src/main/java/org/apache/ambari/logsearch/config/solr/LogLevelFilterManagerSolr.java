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
package org.apache.ambari.logsearch.config.solr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.ambari.logsearch.config.api.LogLevelFilterManager;
import org.apache.ambari.logsearch.config.api.model.loglevelfilter.LogLevelFilter;
import org.apache.ambari.logsearch.config.api.model.loglevelfilter.LogLevelFilterMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SolrPingResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;

import java.util.Map;
import java.util.TreeMap;

/**
 * Gather and store log level filters from/in a Solr collection.
 */
public class LogLevelFilterManagerSolr implements LogLevelFilterManager {

  private static final Logger logger = LogManager.getLogger(LogLevelFilterManagerSolr.class);

  private static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS";
  private final SolrClient solrClient;
  private Gson gson;
  private boolean useClusterParam = true;

  public LogLevelFilterManagerSolr(SolrClient solrClient) {
    this.solrClient = solrClient;
    waitForSolr(solrClient);
    gson = new GsonBuilder().setDateFormat(DATE_FORMAT).setPrettyPrinting().create();
  }

  @Override
  public void createLogLevelFilter(String clusterName, String logId, LogLevelFilter filter) throws Exception {
    final SolrInputDocument doc = new SolrInputDocument();
    int hashCode = useClusterParam ? (clusterName + logId).hashCode() : logId.hashCode();
    doc.addField("id", String.valueOf(hashCode));
    if (useClusterParam) {
      doc.addField("cluster_string", clusterName);
    }
    doc.addField("name", logId);
    doc.addField("type", "log_level_filter");
    doc.addField("value", gson.toJson(filter));
    doc.addField("username", "none");
    logger.debug("Creating log level filter - logid: {}, cluster: {}", logId, clusterName);
    solrClient.add(doc);
  }

  @Override
  public void setLogLevelFilters(String clusterName, LogLevelFilterMap filters) throws Exception {
    TreeMap<String, LogLevelFilter> logLevelFilterTreeMap = filters.getFilter();
    if (!logLevelFilterTreeMap.isEmpty()) {
      LogLevelFilterMap actualFiltersMap = getLogLevelFilters(clusterName);
      if (actualFiltersMap.getFilter().isEmpty()) {
        if (!filters.getFilter().isEmpty()) {
          for (Map.Entry<String, LogLevelFilter> entry : filters.getFilter().entrySet()) {
            createLogLevelFilter(clusterName, entry.getKey(), entry.getValue());
          }
        }
      } else {
        TreeMap<String, LogLevelFilter> mapToSet = filters.getFilter();
        TreeMap<String, LogLevelFilter> finalMapToSet = new TreeMap<>();
        for (Map.Entry<String, LogLevelFilter> entry : actualFiltersMap.getFilter().entrySet()) {
          if (mapToSet.containsKey(entry.getKey())) {
            String actualValue = gson.toJson(entry.getValue());
            String newValue = gson.toJson(mapToSet.get(entry.getKey()));
            if (!newValue.equals(actualValue)) {
              finalMapToSet.put(entry.getKey(), mapToSet.get(entry.getKey()));
            }
          } else {
            finalMapToSet.put(entry.getKey(), mapToSet.get(entry.getKey()));
          }
        }
        for (Map.Entry<String, LogLevelFilter> entry : finalMapToSet.entrySet()) {
          createLogLevelFilter(clusterName, entry.getKey(), entry.getValue());
        }
      }
    }
  }

  @Override
  public LogLevelFilterMap getLogLevelFilters(String clusterName) {
    LogLevelFilterMap logLevelFilterMap = new LogLevelFilterMap();
    TreeMap<String, LogLevelFilter> logLevelFilterTreeMap = new TreeMap<>();
    try {
      SolrQuery solrQuery = new SolrQuery();
      solrQuery.setQuery("*:*");
      if (useClusterParam) {
        solrQuery.addFilterQuery("cluster_string:" + clusterName);
      }
      solrQuery.addFilterQuery("type:log_level_filter");
      solrQuery.setFields("value", "name");

      final QueryResponse response = solrClient.query(solrQuery);
      if (response != null) {
        final SolrDocumentList documents = response.getResults();
        if (documents != null && !documents.isEmpty()) {
          for(SolrDocument document : documents) {
            String jsons = (String) document.getFieldValue("value");
            String logId = (String) document.getFieldValue("name");
            if (jsons != null) {
              LogLevelFilter logLevelFilter = gson.fromJson(jsons, LogLevelFilter.class);
              logLevelFilterTreeMap.put(logId,logLevelFilter);
            }
          }
        }
      }
    } catch (Exception e) {
      logger.error("Error during getting log level filters: {}", e.getMessage());
    }
    logLevelFilterMap.setFilter(logLevelFilterTreeMap);
    return logLevelFilterMap;
  }

  public boolean isUseClusterParam() {
    return useClusterParam;
  }

  public void setUseClusterParam(boolean useClusterParam) {
    this.useClusterParam = useClusterParam;
  }

  public Gson getGson() {
    return gson;
  }

  private void waitForSolr(SolrClient solrClient) {
    while (true) {
      try {
        logger.debug("Start solr ping for log level filter collection");
        SolrPingResponse pingResponse = solrClient.ping();
        if (pingResponse.getStatus() == 0) {
          break;
        }
      } catch (Exception e) {
        logger.error("{}", e);
      }
      logger.info("Solr (collection for log level filters) is not available yet. Sleeping 10 sec. Retrying...");
      try {
        Thread.sleep(10000);
      } catch (InterruptedException e) {
        logger.error("{}", e);
      }
    }
  }
}
