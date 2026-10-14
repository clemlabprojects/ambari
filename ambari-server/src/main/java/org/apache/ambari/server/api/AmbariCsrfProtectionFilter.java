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
package org.apache.ambari.server.api;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.ambari.server.api.services.ResultStatus;
import org.apache.ambari.server.api.services.serializers.JsonSerializer;

/**
 * Rejects state-changing REST API calls that do not carry the X-Requested-By header.
 * <p/>
 * A web page on another site can make a browser send a form POST with the Ambari session cookie,
 * but it cannot add a custom header without a CORS preflight, and Ambari allows no cross-origin
 * requests. Requiring the header therefore keeps such forged requests out.
 * <p/>
 * The check runs before resource matching so that a rejected request never reaches any resource
 * code, including the sub-resource locators that hand requests over to views.
 * <p/>
 * This class is deliberately not annotated as a provider: package scanning would then also apply it
 * to the agent and certificate servlets, whose callers do not send the header. The REST API servlet
 * registers it explicitly when {@code api.csrfPrevention.enabled} is true.
 */
@PreMatching
public class AmbariCsrfProtectionFilter implements ContainerRequestFilter {
  private static final Set<String> IGNORED_METHODS;
  private static final String CSRF_HEADER = "X-Requested-By";
  private static final String ERROR_MESSAGE = "CSRF protection is turned on. " + CSRF_HEADER + " HTTP header is required.";
  private static final JsonSerializer JSON_SERIALIZER = new JsonSerializer();

  static {
    HashSet<String> methods = new HashSet<>();
    methods.add("GET");
    methods.add("OPTIONS");
    methods.add("HEAD");

    IGNORED_METHODS = Collections.unmodifiableSet(methods);

  }

  @Override
  public void filter(ContainerRequestContext containerRequestContext) throws IOException {
    if (!IGNORED_METHODS.contains(containerRequestContext.getMethod()) &&
            !containerRequestContext.getHeaders().containsKey(CSRF_HEADER)) {
      throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity(
              JSON_SERIALIZER.serializeError(new ResultStatus(ResultStatus.STATUS.BAD_REQUEST, ERROR_MESSAGE))
      ).type(MediaType.TEXT_PLAIN_TYPE).build());
    }
  }
}
