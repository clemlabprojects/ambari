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
package org.apache.ambari.logsearch.conf;

import org.apache.ambari.logsearch.config.api.LogSearchPropertyDescription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import static org.apache.ambari.logsearch.common.LogSearchConstants.LOGSEARCH_PROPERTIES_FILE;

@Configuration
public class SolrMetadataPropsConfig extends SolrConnectionPropsConfig {

  @Value("${logsearch.solr.metadata.collection:logsearch_metadata}")
  @LogSearchPropertyDescription(
    name = "logsearch.solr.metadata",
    description = "Name of Log Search metadata collection.",
    examples = {"logsearch_metadata"},
    defaultValue = "logsearch_metadata",
    sources = {LOGSEARCH_PROPERTIES_FILE}
  )
  private String collection;

  @Value("${logsearch.solr.metadata.config.name:logsearch_metadata}")
  @LogSearchPropertyDescription(
    name = "logsearch.solr.metadata.config.name",
    description = "Solr configuration name of the logsearch metadata collection.",
    examples = {"logsearch_metadata"},
    defaultValue = "logsearch_metadata",
    sources = {LOGSEARCH_PROPERTIES_FILE}
  )
  private String configName;

  @Value("${logsearch.solr.metadata.numshards:2}")
  @LogSearchPropertyDescription(
    name = "logsearch.solr.metadata.numshards",
    description = "Number of Solr shards for logsearch metadta collection (bootstrapping).",
    examples = {"3"},
    defaultValue = "2",
    sources = {LOGSEARCH_PROPERTIES_FILE}
  )
  private Integer numberOfShards;

  @Value("${logsearch.solr.metadata.replication.factor:2}")
  @LogSearchPropertyDescription(
    name = "logsearch.solr.metadata.replication.factor",
    description = "Solr replication factor for event metadata collection (bootstrapping).",
    examples = {"3"},
    defaultValue = "2",
    sources = {LOGSEARCH_PROPERTIES_FILE}
  )
  private Integer replicationFactor;

  @Value("${logsearch.solr.metadata.schema.fields.populate.interval.mins:1}")
  @LogSearchPropertyDescription(
    name = "logsearch.solr.metadata.schema.fields.populate.interval.mins",
    description = "Interval in minutes for populating schema fiels for metadata collections.",
    examples = {"10"},
    defaultValue = "1",
    sources = {LOGSEARCH_PROPERTIES_FILE}
  )
  private Integer populateIntervalMins;
  
  @Override
  public String getCollection() {
    return collection;
  }

  @Override
  public void setCollection(String collection) {
    this.collection = collection;
  }

  @Override
  public String getConfigName() {
    return configName;
  }

  @Override
  public void setConfigName(String configName) {
    this.configName = configName;
  }

  @Override
  public Integer getNumberOfShards() {
    return numberOfShards;
  }

  @Override
  public void setNumberOfShards(Integer numberOfShards) {
    this.numberOfShards = numberOfShards;
  }

  @Override
  public Integer getReplicationFactor() {
    return replicationFactor;
  }

  @Override
  public void setReplicationFactor(Integer replicationFactor) {
    this.replicationFactor = replicationFactor;
  }

  public Integer getPopulateIntervalMins() {
    return populateIntervalMins;
  }
  
  void setPopulateIntervalMins(Integer populateIntervalMins) {
    this.populateIntervalMins = populateIntervalMins;
  }

  @Override
  public String getLogType() {
    return null;
  }
}
