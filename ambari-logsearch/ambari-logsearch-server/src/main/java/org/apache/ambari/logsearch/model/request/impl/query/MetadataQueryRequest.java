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
package org.apache.ambari.logsearch.model.request.impl.query;

import org.apache.ambari.logsearch.common.LogSearchConstants;
import org.apache.ambari.logsearch.model.request.impl.MetadataRequest;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import javax.ws.rs.QueryParam;

public class MetadataQueryRequest implements MetadataRequest {

  @QueryParam(LogSearchConstants.REQUEST_PARAM_METADATA_NAME)
  private String name;

  @QueryParam(LogSearchConstants.REQUEST_PARAM_METADATA_TYPE)
  @NotNull
  private String type;

  @QueryParam(LogSearchConstants.REQUEST_PARAM_METADATA_USER_NAME)
  private String userName;

  @Nullable
  @QueryParam(LogSearchConstants.REQUEST_PARAM_CLUSTER_NAMES)
  private String clusters;

  @Override
  public String getName() {
    return name;
  }

  @Override
  public void setName(String name) {
    this.name = name;
  }

  @Override
  public String getType() {
    return type;
  }

  @Override
  public void setType(String type) {
    this.type = type;
  }

  @Override
  public String getUserName() {
    return userName;
  }

  @Override
  public void setUserName(String userName) {
    this.userName = userName;
  }

  @Override
  @Nullable
  public String getClusters() {
    return clusters;
  }

  @Override
  public void setClusters(@Nullable String clusters) {
    this.clusters = clusters;
  }
}
