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

import org.apache.ambari.view.k8s.service.StackDefinitionService;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/stack")
public class StackResource {

    // Inject your service (Ensure you bind it in your Application/Context listener)
    private final StackDefinitionService stackService;

    public StackResource() {
        this.stackService = new StackDefinitionService();
    }

    @Inject
    public StackResource(javax.inject.Provider<org.apache.ambari.view.ViewContext> ctxProvider) {
        this.stackService = new StackDefinitionService(ctxProvider != null ? ctxProvider.get() : null);
    }

    @GET
    @Path("/services")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listServices() {
        try {
            
            return Response.ok(stackService.listServiceDefinitions()).build();
        } catch (Exception e) {
            return Response.status(500).entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("/services/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getService(@PathParam("name") String name) {
        try {
            return Response.ok(stackService.getServiceDefinition(name)).build();
        } catch (Exception e) {
            return Response.status(404).entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("/services/{name}/configurations")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfigs(@PathParam("name") String name) {
        return Response.ok(stackService.getServiceConfigurations(name)).build();
    }
}
