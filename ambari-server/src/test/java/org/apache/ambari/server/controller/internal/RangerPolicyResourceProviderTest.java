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
import static org.easymock.EasyMock.anyBoolean;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;

import org.apache.ambari.server.Role;
import org.apache.ambari.server.RoleCommand;
import org.apache.ambari.server.controller.spi.RequestStatus;
import org.apache.ambari.server.controller.spi.SystemException;
import org.apache.ambari.server.security.TestAuthenticationFactory;
import org.apache.ambari.server.security.authorization.AuthorizationException;
import org.apache.ambari.server.serveraction.ranger.EnsureRangerPolicyServerAction;
import org.apache.ambari.server.state.svccomphost.ServiceComponentHostServerActionEvent;
import org.easymock.Capture;
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

  @Test
  public void testRevokeIsPassedToTheServerAction() throws Exception {
    // An Ambari server that did not pass it on would grant the access the caller asked to take back.
    Capture<Map<String, String>> commandParams = expectServerAction();
    fixture.expectOneRequestScheduled();
    fixture.replayAndInject(RangerPolicyResourceProvider.class, null);
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());

    Map<String, Object> properties = policy(CLUSTER_NAME);
    properties.put("RangerPolicy/revoke", "true");
    new RangerPolicyResourceProvider(fixture.controller).createResources(createRequest(properties));

    assertEquals("true", commandParams.getValue().get("revoke"));
    fixture.verifyScheduling();
  }

  @Test
  public void testOwnedPolicyOnlyIsPassedWithTheRevoke() throws Exception {
    // Without it, the server action would also strip the access from every other policy over the same resources.
    Capture<Map<String, String>> commandParams = expectServerAction();
    fixture.expectOneRequestScheduled();
    fixture.replayAndInject(RangerPolicyResourceProvider.class, null);
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());

    Map<String, Object> properties = policy(CLUSTER_NAME);
    properties.put("RangerPolicy/revoke", "true");
    properties.put("RangerPolicy/ownedPolicyOnly", "true");
    new RangerPolicyResourceProvider(fixture.controller).createResources(createRequest(properties));

    assertEquals("true", commandParams.getValue().get("ownedPolicyOnly"));
    fixture.verifyScheduling();
  }

  @Test
  public void testOwnedPolicyOnlyWithoutRevokeIsRefusedWithoutScheduling() throws Exception {
    fixture.replayAndInject(RangerPolicyResourceProvider.class, null);
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());
    Map<String, Object> properties = policy(CLUSTER_NAME);
    properties.put("RangerPolicy/ownedPolicyOnly", "true");

    try {
      new RangerPolicyResourceProvider(fixture.controller).createResources(createRequest(properties));
      fail("Expected ownedPolicyOnly without a revoke to be refused");
    } catch (SystemException expected) {
      assertEquals("ownedPolicyOnly applies to a revoke only", expected.getMessage());
    }
    fixture.verifyScheduling();
  }

  @Test
  public void testAGrantCarriesNoRevoke() throws Exception {
    Capture<Map<String, String>> commandParams = expectServerAction();
    fixture.expectOneRequestScheduled();
    fixture.replayAndInject(RangerPolicyResourceProvider.class, null);
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());

    new RangerPolicyResourceProvider(fixture.controller).createResources(createRequest(policy(CLUSTER_NAME)));

    assertFalse(commandParams.getValue().containsKey("revoke"));
    fixture.verifyScheduling();
  }

  @Test
  public void testRevokeOfAMaskIsRefusedWithoutScheduling() throws Exception {
    fixture.replayAndInject(RangerPolicyResourceProvider.class, null);
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());
    Map<String, Object> properties = policy(CLUSTER_NAME);
    properties.put("RangerPolicy/revoke", "true");
    properties.put("RangerPolicy/policyType", "1");
    properties.put("RangerPolicy/maskType", "MASK");

    try {
      new RangerPolicyResourceProvider(fixture.controller).createResources(createRequest(properties));
      fail("Expected a revoke of a masking policy to be refused");
    } catch (SystemException expected) {
      assertEquals("revoke applies to access policies only (policyType=0)", expected.getMessage());
    }
    fixture.verifyScheduling();
  }

  /** Captures the command parameters the provider hands to {@link EnsureRangerPolicyServerAction}. */
  @SuppressWarnings("unchecked")
  private Capture<Map<String, String>> expectServerAction() {
    Capture<Map<String, String>> commandParams = Capture.newInstance();
    fixture.stage.addServerActionCommand(eq(EnsureRangerPolicyServerAction.class.getName()), anyString(),
        anyObject(Role.class), anyObject(RoleCommand.class), anyString(),
        anyObject(ServiceComponentHostServerActionEvent.class), capture(commandParams), anyString(),
        anyObject(Map.class), anyObject(Integer.class), anyBoolean(), anyBoolean());
    expectLastCall();
    return commandParams;
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
