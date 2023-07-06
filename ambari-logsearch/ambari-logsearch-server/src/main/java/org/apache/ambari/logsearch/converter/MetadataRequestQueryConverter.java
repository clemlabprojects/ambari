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
package org.apache.ambari.logsearch.converter;

import org.apache.ambari.logsearch.model.request.impl.MetadataRequest;
import org.apache.ambari.logsearch.util.SolrUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.solr.client.solrj.SolrQuery;

import javax.inject.Named;

import java.util.ArrayList;
import java.util.List;

import static org.apache.ambari.logsearch.solr.SolrConstants.CommonLogConstants.CLUSTER;
import static org.apache.ambari.logsearch.solr.SolrConstants.MetadataConstants.NAME;
import static org.apache.ambari.logsearch.solr.SolrConstants.MetadataConstants.TYPE;

@Named
public class MetadataRequestQueryConverter extends AbstractConverterAware<MetadataRequest, SolrQuery> {

  @Override
  public SolrQuery convert(MetadataRequest metadataRequest) {
    SolrQuery metadataQuery = new SolrQuery();
    metadataQuery.setQuery("*:*");
    metadataQuery.addFilterQuery(String.format("%s:%s", TYPE, metadataRequest.getType()));
    if (StringUtils.isNotBlank(metadataRequest.getName())) {
      metadataQuery.addFilterQuery(String.format("%s:%s", NAME, metadataRequest.getName()));
    }

    SolrQuery.SortClause sortOrder = SolrQuery.SortClause.create(NAME, SolrQuery.ORDER.asc);
    List<SolrQuery.SortClause> sort = new ArrayList<>();
    sort.add(sortOrder);
    metadataQuery.setRows(10000);
    metadataQuery.setSorts(sort);

    SolrUtil.addListFilterToSolrQuery(metadataQuery, CLUSTER + "_string", metadataRequest.getClusters());

    return metadataQuery;
  }
}
