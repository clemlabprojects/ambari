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
import org.apache.ambari.server.controller.spi.SystemException;
import org.apache.ambari.server.security.TestAuthenticationFactory;
import org.apache.ambari.server.security.authorization.AuthorizationException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@code POST /clusters/{cluster}/ranger_policy} grants Ranger access with the Ranger administrator
 * credentials Ambari holds, so only cluster administrators of the named cluster may call it. A
 * refusal surfaces as an {@link AuthorizationException}, which the API answers with 403 (see
 * {@code CreateHandlerTest#testHandleRequest__AuthorizationFailure}); nothing is scheduled.
 */
public class RangerPolicyResourceProviderTest {

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
  public void testAmbariAdministratorCanGrantPolicy() throws Exception {
    assertPolicyScheduled(TestAuthenticationFactory.createAdministrator());
  }

  @Test
  public void testClusterAdministratorCanGrantPolicy() throws Exception {
    assertPolicyScheduled(TestAuthenticationFactory.createClusterAdministrator());
  }

  @Test
  public void testClusterUserIsRefused() throws Exception {
    assertRefused(TestAuthenticationFactory.createClusterUser(), policy(CLUSTER_NAME));
  }

  @Test
  public void testClusterOperatorIsRefused() throws Exception {
    assertRefused(TestAuthenticationFactory.createClusterOperator(), policy(CLUSTER_NAME));
  }

  @Test
  public void testServiceAdministratorIsRefused() throws Exception {
    assertRefused(TestAuthenticationFactory.createServiceAdministrator(), policy(CLUSTER_NAME));
  }

  @Test
  public void testAdministratorOfAnotherClusterIsRefused() throws Exception {
    assertRefused(TestAuthenticationFactory.createClusterAdministrator("otherAdmin", CLUSTER_RESOURCE_ID + 1),
        policy(CLUSTER_NAME));
  }

  @Test
  public void testUnauthenticatedCallerIsRefused() throws Exception {
    assertRefused(null, policy(CLUSTER_NAME));
  }

  @Test
  public void testClusterNamedInTheBodyIsTheOneChecked() throws Exception {
    // The provider acts on the cluster named by cluster_name, so that is the cluster whose
    // administrators it must check, even when the URL names another one.
    Map<String, Object> properties = policy("unknown");
    properties.put("cluster_name", CLUSTER_NAME);
    properties.put("Clusters/cluster_name", "unknown");
    assertRefused(TestAuthenticationFactory.createClusterAdministrator("otherAdmin", CLUSTER_RESOURCE_ID + 1),
        properties);
  }

  @Test
  public void testUnknownClusterIsReportedWithoutScheduling() throws Exception {
    fixture.replayAndInject(RangerPolicyResourceProvider.class, null);
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());

    try {
      new RangerPolicyResourceProvider(fixture.controller).createResources(createRequest(policy("unknown")));
      fail("Expected the unknown cluster to be reported");
    } catch (AuthorizationException e) {
      fail("An administrator must get the unknown cluster error, not a refusal");
    } catch (SystemException e) {
      assertEquals("Cluster not found: unknown", e.getMessage());
    }
    fixture.verifyScheduling();
  }

  private void assertPolicyScheduled(Authentication authentication) throws Exception {
    fixture.expectOneRequestScheduled();
    fixture.replayAndInject(RangerPolicyResourceProvider.class, null);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    RequestStatus status = new RangerPolicyResourceProvider(fixture.controller)
        .createResources(createRequest(policy(CLUSTER_NAME)));

    assertEquals(42L, status.getRequestResource().getPropertyValue("Requests/id"));
    fixture.verifyScheduling();
  }

  private void assertRefused(Authentication authentication, Map<String, Object> properties) throws Exception {
    fixture.replayAndInject(RangerPolicyResourceProvider.class, null);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    try {
      new RangerPolicyResourceProvider(fixture.controller).createResources(createRequest(properties));
      fail("Expected the Ranger policy grant to be refused");
    } catch (AuthorizationException expected) {
      // refused before anything is scheduled
    }
    fixture.verifyScheduling();
  }

  private static Map<String, Object> policy(String clusterName) {
    Map<String, Object> properties = new HashMap<>();
    properties.put("Clusters/cluster_name", clusterName);
    properties.put("RangerPolicy/rangerServiceName", "hive-c1");
    properties.put("RangerPolicy/userName", "trino-analytics");
    properties.put("RangerPolicy/accessTypes", "select");
    properties.put("RangerPolicy/resourcesJson", "{\"database\":[\"*\"]}");
    properties.put("RangerPolicy/policyNameHint", "kdps-trino-analytics");
    return properties;
  }
}
