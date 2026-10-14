/**
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

package org.apache.ambari.view.k8s.resources;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.HelmRepoDTO;
import org.apache.ambari.view.k8s.requests.HelmRepoRequest;
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.service.HelmRepositoryService;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HelmRepoResource {

  private static final Logger LOG = LoggerFactory.getLogger(HelmRepoResource.class);


  @Inject
  private ViewContext viewContext;

  /** Headers of the request this resource serves: the role check asks Ambari with the caller's credentials. */
  private final HttpHeaders callerHeaders;

  /**
   * Creates the resource for one request.
   *
   * @param context       active Ambari view context
   * @param callerHeaders headers of the request; {@code null} when unknown
   */
  public HelmRepoResource(ViewContext context, HttpHeaders callerHeaders) {
    this.viewContext = context;
    this.callerHeaders = callerHeaders;
  }

  @GET
  public List<HelmRepoDTO> list() {
    var svc = new HelmRepositoryService(viewContext);
    var repos = svc.list().stream().map(HelmRepoDTO::fromEntity).collect(Collectors.toList());
    LOG.info("found repos: {}", repos.size());
    for(HelmRepoDTO helmRepoDTO : repos){
      LOG.info("repoinfo: id: {}, imageProject: {},  url:{}, name:{}, " +
              "", helmRepoDTO.id, helmRepoDTO.imageProject, helmRepoDTO.url, helmRepoDTO.name);
    }
    return svc.list().stream().map(HelmRepoDTO::fromEntity).collect(Collectors.toList());
  }

  /**
   * Creates or updates a repository. The registry secret comes in the body, apart from the repository, and is stored
   * encrypted, never with it. A repository or secret the view cannot keep (a missing field, a token too long) is
   * refused with 400 and the reason.
   */
  @POST
  public Response save(HelmRepoRequest request) {
    // Repositories decide which charts every deploy pulls, so changing one is a configuration task.
    new AuthHelper(viewContext, callerHeaders).checkConfigurationPermission();
    try {
      if (request == null) {
        throw new IllegalArgumentException("A repository is required");
      }
      var svc = new HelmRepositoryService(viewContext);
      HelmRepoEntity out = svc.save(request.toEntity(), request.plainSecret);
      return Response.ok(HelmRepoDTO.fromEntity(out)).build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", String.valueOf(e.getMessage())))
          .build();
    }
  }

  @POST
  @Path("{id}/login")
  public Response login(@PathParam("id") String id) {
    new AuthHelper(viewContext, callerHeaders).checkWritePermission();
    new HelmRepositoryService(viewContext).loginOrSync(id);
    return Response.noContent().build(); // 204
  }

  @DELETE @Path("{id}")
  public Response delete(@PathParam("id") String id) {
    new AuthHelper(viewContext, callerHeaders).checkConfigurationPermission();
    new HelmRepositoryService(viewContext).delete(id);
    return Response.ok().build();
  }
}
