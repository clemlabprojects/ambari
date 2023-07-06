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
package org.apache.ambari.logsearch.model.request;

import io.swagger.annotations.ApiParam;
import org.apache.ambari.logsearch.common.LogSearchConstants;

import static org.apache.ambari.logsearch.doc.DocConstants.MetadataDescriptions.NAME_D;
import static org.apache.ambari.logsearch.doc.DocConstants.MetadataDescriptions.TYPE_D;
import static org.apache.ambari.logsearch.doc.DocConstants.MetadataDescriptions.USER_NAME_D;

public interface MetadataParamDefinition {

  String getName();

  @ApiParam(value = NAME_D, name = LogSearchConstants.REQUEST_PARAM_METADATA_NAME)
  void setName(String name);

  String getType();

  @ApiParam(value = TYPE_D, name = LogSearchConstants.REQUEST_PARAM_METADATA_TYPE)
  void setType(String type);

  String getUserName();

  @ApiParam(value = USER_NAME_D, name = LogSearchConstants.REQUEST_PARAM_METADATA_USER_NAME)
  void setUserName(String userName);
}
