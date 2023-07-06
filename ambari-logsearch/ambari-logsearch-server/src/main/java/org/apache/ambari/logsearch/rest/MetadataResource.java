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

package org.apache.ambari.logsearch.rest;

import java.util.Collection;

import javax.inject.Inject;
import javax.inject.Named;
import javax.validation.Valid;
import javax.ws.rs.BeanParam;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import org.apache.ambari.logsearch.manager.MetadataManager;
import org.apache.ambari.logsearch.model.request.impl.query.MetadataQueryRequest;
import org.apache.ambari.logsearch.model.response.LogsearchMetaData;
import org.springframework.context.annotation.Scope;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;

import static org.apache.ambari.logsearch.doc.DocConstants.MetadataOperationDescriptions.DELETE_METADATA_OD;
import static org.apache.ambari.logsearch.doc.DocConstants.MetadataOperationDescriptions.DELETE_METADATA_LIST_OD;
import static org.apache.ambari.logsearch.doc.DocConstants.MetadataOperationDescriptions.GET_METADATA_OD;
import static org.apache.ambari.logsearch.doc.DocConstants.MetadataOperationDescriptions.GET_METADATA_LIST_OD;
import static org.apache.ambari.logsearch.doc.DocConstants.MetadataOperationDescriptions.SAVE_METADATA_LIST_OD;
import static org.apache.ambari.logsearch.doc.DocConstants.MetadataOperationDescriptions.SAVE_METADATA_OD;

@Api(value = "metadata", description = "Log Search metadata operations", authorizations = {@Authorization(value = "basicAuth")})
@Path("metadata")
@Named
@Scope("request")
public class MetadataResource {

  @Inject
  private MetadataManager metadataManager;

  @GET
  @Produces({"application/json"})
  @ApiOperation(GET_METADATA_OD)
  public LogsearchMetaData getMetadata(@Valid @BeanParam MetadataQueryRequest request) {
    return metadataManager.getMetadata(request);
  }

  @GET
  @Path("/list")
  @Produces({"application/json"})
  @ApiOperation(GET_METADATA_LIST_OD)
  public Collection<LogsearchMetaData> getMetadataList(@Valid @BeanParam MetadataQueryRequest request) {
    return metadataManager.getMetadataList(request);
  }

  @POST
  @Produces({"application/json"})
  @ApiOperation(SAVE_METADATA_OD)
  public String saveMetadata(LogsearchMetaData metadata) {
    return metadataManager.saveMetadata(metadata);
  }

  @POST
  @Path("/list")
  @Produces({"application/json"})
  @ApiOperation(SAVE_METADATA_LIST_OD)
  public String saveMetadataList(Collection<LogsearchMetaData> metadata) {
    return metadataManager.saveMetadata(metadata);
  }

  @DELETE
  @ApiOperation(DELETE_METADATA_OD)
  public void deleteMetadata(LogsearchMetaData metadata) {
    metadataManager.deleteMetadata(metadata);
  }

  @DELETE
  @Path("/list")
  @ApiOperation(DELETE_METADATA_LIST_OD)
  public void deleteMetadataList(Collection<LogsearchMetaData> metadata) {
    metadataManager.deleteMetadata(metadata);
  }

}
