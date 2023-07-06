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

package org.apache.ambari.logsearch.dao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;

import org.apache.ambari.logsearch.common.LogSearchContext;
import org.apache.ambari.logsearch.common.LogType;
import org.apache.ambari.logsearch.conf.SolrClientsHolder;
import org.apache.ambari.logsearch.conf.SolrPropsConfig;
import org.apache.ambari.logsearch.conf.SolrMetadataPropsConfig;
import org.apache.ambari.logsearch.conf.global.SolrCollectionState;
import org.apache.ambari.logsearch.configurer.SolrCollectionConfigurer;
import org.apache.curator.shaded.com.google.common.collect.ImmutableList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;

import org.springframework.data.solr.core.SolrTemplate;

@Named
public class MetadataSolrDao extends SolrDaoBase {

  private static final Logger logger = LogManager.getLogger(MetadataSolrDao.class);

  private static final Logger LOG_PERFORMANCE = LogManager.getLogger("org.apache.ambari.logsearch.performance");

  @Inject
  private SolrMetadataPropsConfig solrMetadataPropsConfig;

  private SolrTemplate metadataSolrTemplate;

  @Inject
  @Named("solrMetadataState")
  private SolrCollectionState solrMetadataState;

  @Inject
  private SolrClientsHolder solrClientsHolder;

  public MetadataSolrDao() {
    super(LogType.SERVICE);
  }

  @Override
  public SolrTemplate getSolrTemplate() {
    return metadataSolrTemplate;
  }

  @Override
  public void setSolrTemplate(SolrTemplate solrTemplate) {
    this.metadataSolrTemplate = solrTemplate;
  }

  @PostConstruct
  public void postConstructor() {
    String solrUrl = solrMetadataPropsConfig.getSolrUrl();
    String zkConnectString = solrMetadataPropsConfig.getZkConnectString();
    String collection = solrMetadataPropsConfig.getCollection();

    try {
      new SolrCollectionConfigurer(this, false, solrClientsHolder, SolrClientsHolder.CollectionType.HISTORY).start();
    } catch (Exception e) {
      logger.error("error while connecting to Solr for metadata collection : solrUrl=" + solrUrl + ", zkConnectString=" + zkConnectString +
          ", collection=" + collection, e);
    }
  }

  public UpdateResponse deleteMetadata(String name, String type, String userName) {
    return removeDoc(String.format("name:%s AND type:%s AND username:%s", name, type, userName));
  }

  private UpdateResponse removeDoc(String query) {
    try {
      UpdateResponse updateResoponse = getSolrClient().deleteByQuery(query);
      getSolrClient().commit();
      LOG_PERFORMANCE.info("Username :- " + LogSearchContext.getCurrentUsername() +
              " Remove Time Execution :- " + updateResoponse.getQTime() + " Total Time Elapsed is :- " + updateResoponse.getElapsedTime());
      return updateResoponse;
    } catch (SolrServerException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public UpdateResponse addDoc(SolrInputDocument doc) {
    return addDocs(ImmutableList.of(doc));
  }

  public UpdateResponse addDocs(List<SolrInputDocument> docs) {
    try {
      UpdateResponse updateResoponse = getSolrClient().add(docs);
      LOG_PERFORMANCE.info("Username :- " + LogSearchContext.getCurrentUsername() +
              " Update Time Execution :- " + updateResoponse.getQTime() + " Total Time Elapsed is :- " + updateResoponse.getElapsedTime());
      getSolrClient().commit();
      return updateResoponse;
    } catch (SolrServerException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public SolrCollectionState getSolrCollectionState() {
    return solrMetadataState;
  }

  @Override
  public SolrPropsConfig getSolrPropsConfig() {
    return solrMetadataPropsConfig;
  }
}
