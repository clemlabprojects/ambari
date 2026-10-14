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
package org.apache.ambari.server.controller;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Properties;

import javax.annotation.Priority;
import javax.ws.rs.Priorities;
import javax.ws.rs.client.Entity;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.Response;

import org.apache.ambari.server.RandomPortJerseyTest;
import org.apache.ambari.server.api.AmbariCsrfProtectionFilter;
import org.apache.ambari.server.api.services.BaseService;
import org.apache.ambari.server.api.services.users.UserService;
import org.apache.ambari.server.audit.request.RequestAuditLogger;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.internal.RequestStatusImpl;
import org.apache.ambari.server.controller.spi.ClusterController;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.Schema;
import org.apache.ambari.server.controller.utilities.ClusterControllerHelper;
import org.apache.ambari.server.security.TestAuthenticationFactory;
import org.easymock.Capture;
import org.easymock.CaptureType;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.TestProperties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Checks the CSRF protection of the REST API as the server wires it: the Jersey application that
 * {@link AmbariServer} serves under /api/v1, called over HTTP the way a browser would call it.
 */
public class AmbariServerCsrfProtectionTest extends RandomPortJerseyTest {

  private static final String CSRF_HEADER = "X-Requested-By";
  private static final String CSRF_ERROR = "CSRF protection is turned on";
  private static final String USER_NAME_PROPERTY = "Users/user_name";
  private static final String NEW_USER = "csrf-check";
  private static final String CREATE_USER_BODY =
      "{\"Users\": {\"user_name\": \"" + NEW_USER + "\", \"password\": \"secret\"}}";

  private Capture<Request> createUserRequests;
  private ClusterController previousClusterController;

  @Override
  protected ResourceConfig configure() {
    forceSet(TestProperties.CONTAINER_PORT, String.valueOf(getPort(0)));
    return AmbariServer.createApiResourceConfig(configuration(true))
        .register(AdministratorAuthenticationFilter.class);
  }

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();

    ClusterController clusterController = createNiceMock(ClusterController.class);
    Schema userSchema = createNiceMock(Schema.class);
    createUserRequests = newCapture(CaptureType.ALL);
    expect(clusterController.getSchema(Resource.Type.User)).andReturn(userSchema).anyTimes();
    expect(userSchema.getKeyPropertyId(Resource.Type.User)).andReturn(USER_NAME_PROPERTY).anyTimes();
    expect(clusterController.createResources(eq(Resource.Type.User), capture(createUserRequests)))
        .andReturn(new RequestStatusImpl(null)).anyTimes();
    RequestAuditLogger auditLogger = createNiceMock(RequestAuditLogger.class);
    replay(clusterController, userSchema, auditLogger);

    previousClusterController = swapClusterController(clusterController);
    BaseService.init(auditLogger);
  }

  @Override
  @After
  public void tearDown() throws Exception {
    swapClusterController(previousClusterController);
    SecurityContextHolder.clearContext();
    super.tearDown();
  }

  @Test
  public void testCreateUserWithoutHeaderIsRejected() {
    Response response = target("users").request().post(Entity.text(CREATE_USER_BODY));

    assertCsrfRejection(response);
    assertFalse("no user may be created", createUserRequests.hasCaptured());
  }

  @Test
  public void testCreateUserWithHeaderSucceeds() {
    Response response = target("users").request()
        .header(CSRF_HEADER, "ambari")
        .post(Entity.text(CREATE_USER_BODY));

    assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
    assertEquals(1, createUserRequests.getValues().size());
    Map<String, Object> user = createUserRequests.getValue().getProperties().iterator().next();
    assertEquals(NEW_USER, user.get(USER_NAME_PROPERTY));
  }

  @Test
  public void testHeaderNameIsNotCaseSensitive() {
    Response response = target("users").request()
        .header(CSRF_HEADER.toLowerCase(), "ambari")
        .post(Entity.text(CREATE_USER_BODY));

    assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
  }

  @Test
  public void testCrossSiteFormPostsAreRejected() {
    // The three encodings an HTML form can send from another site without a CORS preflight.
    assertCsrfRejection(target("users").request()
        .post(Entity.entity(CREATE_USER_BODY, "text/plain")));
    assertCsrfRejection(target("users").request()
        .post(Entity.entity("Users%2Fuser_name=" + NEW_USER, "application/x-www-form-urlencoded")));
    assertCsrfRejection(target("users").request()
        .post(Entity.entity("--b\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\ny\r\n--b--\r\n",
            "multipart/form-data; boundary=b")));

    assertFalse("no user may be created", createUserRequests.hasCaptured());
  }

  @Test
  public void testOtherStateChangingMethodsWithoutHeaderAreRejected() {
    assertCsrfRejection(target("users/admin").request().put(Entity.text("{\"Users\": {\"active\": false}}")));
    assertCsrfRejection(target("users/admin").request().delete());
  }

  @Test
  public void testViewAndClusterActionsWithoutHeaderAreRejected() {
    // A view action that takes only query parameters, such as a release rollback, is the easiest one
    // to forge with a plain HTML form.
    assertCsrfRejection(target("views/K8S-VIEW/versions/1.0.0.8/instances/K8S_VIEW_INSTANCE/resources/api/helm/rollback")
        .queryParam("namespace", "prod").queryParam("name", "trino").queryParam("revision", "1")
        .request().post(Entity.text("")));
    assertCsrfRejection(target("clusters/c1/ranger_policy").request().post(Entity.text("{}")));
    assertCsrfRejection(target("clusters/c1/requests").request().post(Entity.text("{}")));
  }

  @Test
  public void testFilterIsRegisteredOnlyWhenProtectionIsEnabled() {
    assertTrue(AmbariServer.createApiResourceConfig(configuration(true))
        .isRegistered(AmbariCsrfProtectionFilter.class));
    assertFalse(AmbariServer.createApiResourceConfig(configuration(false))
        .isRegistered(AmbariCsrfProtectionFilter.class));
  }

  @Test
  public void testPackageScanningDoesNotPickUpTheFilter() {
    // The agent and certificate servlets scan this package, and their callers send no X-Requested-By
    // header: the filter must only ever be added explicitly to the REST API servlet.
    ResourceConfig scanned = new ResourceConfig().packages("org.apache.ambari.server.api");

    assertTrue(scanned.getClasses().contains(UserService.class));
    assertFalse(scanned.getClasses().contains(AmbariCsrfProtectionFilter.class));
  }

  private static void assertCsrfRejection(Response response) {
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    assertTrue(response.readEntity(String.class).contains(CSRF_ERROR));
  }

  private static Configuration configuration(boolean csrfProtectionEnabled) {
    Properties properties = new Properties();
    properties.setProperty(Configuration.API_CSRF_PREVENTION.getKey(), String.valueOf(csrfProtectionEnabled));
    return new Configuration(properties);
  }

  /**
   * Replaces the cluster controller that the API handlers obtain from {@link ClusterControllerHelper}.
   *
   * @return the controller it replaced
   */
  private static ClusterController swapClusterController(ClusterController replacement) throws Exception {
    Field field = ClusterControllerHelper.class.getDeclaredField("controller");
    field.setAccessible(true);
    synchronized (ClusterControllerHelper.class) {
      ClusterController previous = (ClusterController) field.get(null);
      field.set(null, replacement);
      return previous;
    }
  }

  /**
   * Stands in for the Spring Security filter chain, which authenticates every API call before
   * Jersey sees it.
   */
  @PreMatching
  @Priority(Priorities.AUTHENTICATION)
  public static class AdministratorAuthenticationFilter implements ContainerRequestFilter, ContainerResponseFilter {
    @Override
    public void filter(ContainerRequestContext requestContext) {
      SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
      SecurityContextHolder.clearContext();
    }
  }
}
