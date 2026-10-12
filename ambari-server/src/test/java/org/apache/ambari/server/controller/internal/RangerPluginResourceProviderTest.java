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

package org.apache.ambari.server.controller.internal;

import static org.apache.ambari.server.controller.internal.ServerActionProviderTestFixture.CLUSTER_NAME;
import static org.apache.ambari.server.controller.internal.ServerActionProviderTestFixture.CLUSTER_RESOURCE_ID;
import static org.apache.ambari.server.controller.internal.ServerActionProviderTestFixture.createRequest;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;

import org.apache.ambari.server.controller.spi.RequestStatus;
import org.apache.ambari.server.security.TestAuthenticationFactory;
import org.apache.ambari.server.security.authorization.AuthorizationException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@code POST /clusters/{cluster}/ranger_plugin_repository} creates Ranger repositories and users
 * with the Ranger administrator credentials Ambari holds, so only cluster administrators of the
 * named cluster may call it. A refusal surfaces as an {@link AuthorizationException}, which the API
 * answers with 403 (see {@code CreateHandlerTest#testHandleRequest__AuthorizationFailure}); nothing
 * is scheduled.
 */
public class RangerPluginResourceProviderTest {

  private ServerActionProviderTestFixture fixture;

  @Before
  public void setUp() throws Exception {
    fixture = new ServerActionProviderTestFixture();
  }

  @After
  public void clearAuthentication() {
    SecurityContextHolder.getContext().setAuthentication(null);
  }

  @Test
  public void testClusterAdministratorCanCreateRepository() throws Exception {
    fixture.expectOneRequestScheduled();
    fixture.replayAndInject(RangerPluginResourceProvider.class, null);
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createClusterAdministrator());

    RequestStatus status = new RangerPluginResourceProvider(fixture.controller)
        .createResources(createRequest(repository()));

    assertEquals(42L, status.getRequestResource().getPropertyValue("Requests/id"));
    fixture.verifyScheduling();
  }

  @Test
  public void testClusterUserIsRefused() throws Exception {
    assertRefused(TestAuthenticationFactory.createClusterUser(), repository());
  }

  @Test
  public void testClusterOperatorIsRefused() throws Exception {
    assertRefused(TestAuthenticationFactory.createClusterOperator(), repository());
  }

  @Test
  public void testAdministratorOfAnotherClusterIsRefused() throws Exception {
    assertRefused(TestAuthenticationFactory.createClusterAdministrator("otherAdmin", CLUSTER_RESOURCE_ID + 1),
        repository());
  }

  @Test
  public void testUnauthenticatedCallerIsRefused() throws Exception {
    assertRefused(null, repository());
  }

  @Test
  public void testUserOnlyModeIsRefusedToClusterUser() throws Exception {
    // The user-only mode creates a Ranger account with roles, which is no less sensitive.
    Map<String, Object> properties = new HashMap<>();
    properties.put("Clusters/cluster_name", CLUSTER_NAME);
    properties.put("RangerPlugin/userOnly", "true");
    properties.put("RangerPlugin/pluginUserName", "tagsync-writer");
    properties.put("RangerPlugin/pluginUserPassword", "secret");
    properties.put("RangerPlugin/userRoles", "ROLE_SYS_ADMIN");
    assertRefused(TestAuthenticationFactory.createClusterUser(), properties);
  }

  private void assertRefused(Authentication authentication, Map<String, Object> properties) throws Exception {
    fixture.replayAndInject(RangerPluginResourceProvider.class, null);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    try {
      new RangerPluginResourceProvider(fixture.controller).createResources(createRequest(properties));
      fail("Expected the Ranger repository request to be refused");
    } catch (AuthorizationException expected) {
      // refused before anything is scheduled
    }
    fixture.verifyScheduling();
  }

  private static Map<String, Object> repository() {
    Map<String, Object> properties = new HashMap<>();
    properties.put("Clusters/cluster_name", CLUSTER_NAME);
    properties.put("RangerPlugin/rangerRepositoryName", "trino-analytics");
    properties.put("RangerPlugin/serviceType", "trino");
    properties.put("RangerPlugin/pluginUserName", "trino-analytics");
    properties.put("RangerPlugin/pluginUserPassword", "secret");
    return properties;
  }
}
