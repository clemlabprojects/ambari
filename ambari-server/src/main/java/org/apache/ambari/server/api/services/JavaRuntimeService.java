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
package org.apache.ambari.server.api.services;

import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.ambari.annotations.ApiIgnore;
import org.apache.ambari.server.api.resources.ResourceInstance;
import org.apache.ambari.server.controller.spi.Resource;

/** Administrative access to the server-wide stack runtime paths for this cluster. */
public class JavaRuntimeService extends BaseService {
  private final String clusterName;

  JavaRuntimeService(String clusterName) {
    this.clusterName = clusterName;
  }

  @GET
  @ApiIgnore
  @Path("stack")
  @Produces("text/plain")
  public Response getRuntime(@Context HttpHeaders headers, @Context UriInfo uri) {
    return handleRequest(headers, null, uri, Request.Type.GET, runtime());
  }

  @PUT
  @ApiIgnore
  @Path("stack")
  @Produces("text/plain")
  public Response updateRuntime(String body, @Context HttpHeaders headers, @Context UriInfo uri) {
    return handleRequest(headers, body, uri, Request.Type.PUT, runtime());
  }

  private ResourceInstance runtime() {
    Map<Resource.Type, String> ids = new HashMap<>();
    ids.put(Resource.Type.Cluster, clusterName);
    ids.put(Resource.Type.JavaRuntime, "stack");
    return createResource(Resource.Type.JavaRuntime, ids);
  }
}
