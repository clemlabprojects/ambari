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

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.ambari.annotations.ApiIgnore;
import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.StaticallyInject;
import org.apache.ambari.server.api.resources.ResourceInstance;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.security.authorization.AuthorizationException;
import org.apache.ambari.server.security.authorization.AuthorizationHelper;
import org.apache.ambari.server.security.authorization.ResourceType;
import org.apache.ambari.server.security.authorization.RoleAuthorization;
import org.apache.ambari.server.serveraction.kerberos.AdhocKeytabPayloadStore;
import org.apache.ambari.server.state.Clusters;
import org.apache.commons.lang.StringUtils;

import com.google.inject.Inject;

/**
 * POST /api/v1/clusters/{cluster}/adhoc_keytab
 * Enqueues a pollable Ambari Request that runs GenerateAdhocKeytabServerAction.
 * No custom-action XML is used; the action is referenced by CLASS name.
 */
@StaticallyInject
public class AdhocKeytabService extends BaseService {
  private final String clusterName;

  @Inject
  private static Clusters clusters;

  @Inject
  private static AdhocKeytabPayloadStore adhocKeytabPayloadStore;

  public AdhocKeytabService() {
    this(null);
  }

  public AdhocKeytabService(String clusterName) {
    this.clusterName = clusterName;
  }

  @POST
  @ApiIgnore
  @Produces("text/plain")
  public Response create(String body, @Context HttpHeaders headers, @Context UriInfo ui) {
    return handleRequest(headers, body, ui, Request.Type.POST, createResource());
  }

  @POST
  @ApiIgnore
  @Path("payloads/{payloadRef}/read")
  @Produces(MediaType.TEXT_PLAIN)
  public Response readPayload(@PathParam("payloadRef") String payloadRef)
      throws AmbariException, AuthorizationException {
    if (StringUtils.isBlank(clusterName)) {
      return Response.status(Response.Status.NOT_FOUND)
          .type(MediaType.TEXT_PLAIN_TYPE)
          .entity("Cluster not found")
          .build();
    }

    Long clusterResourceId = clusters.getCluster(clusterName).getResourceId();
    AuthorizationHelper.verifyAuthorization(
        ResourceType.CLUSTER,
        clusterResourceId,
        EnumSet.of(RoleAuthorization.CLUSTER_TOGGLE_KERBEROS));

    Optional<String> payloadBase64 = adhocKeytabPayloadStore.readPayload(clusterName, payloadRef);
    if (payloadBase64.isEmpty()) {
      return Response.status(Response.Status.GONE)
          .type(MediaType.TEXT_PLAIN_TYPE)
          .entity("Ad-hoc keytab payload not found, expired, or already consumed")
          .build();
    }

    return Response.ok(payloadBase64.get(), MediaType.TEXT_PLAIN_TYPE).build();
  }

  @POST
  @ApiIgnore
  @Path("payloads/{payloadRef}/ack")
  public Response ackPayload(@PathParam("payloadRef") String payloadRef)
      throws AmbariException, AuthorizationException {
    if (StringUtils.isBlank(clusterName)) {
      return Response.status(Response.Status.NOT_FOUND)
          .type(MediaType.TEXT_PLAIN_TYPE)
          .entity("Cluster not found")
          .build();
    }

    Long clusterResourceId = clusters.getCluster(clusterName).getResourceId();
    AuthorizationHelper.verifyAuthorization(
        ResourceType.CLUSTER,
        clusterResourceId,
        EnumSet.of(RoleAuthorization.CLUSTER_TOGGLE_KERBEROS));

    adhocKeytabPayloadStore.ackPayload(clusterName, payloadRef);
    return Response.noContent().build();
  }

  private ResourceInstance createResource() {
    Map<Resource.Type, String> ids = new HashMap<>();
    ids.put(Resource.Type.Cluster, clusterName);
    return createResource(Resource.Type.ADHOC_KEYTAB, ids);
  }
}
