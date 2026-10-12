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
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isNull;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.same;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.RequestStatus;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.ambari.server.security.TestAuthenticationFactory;
import org.apache.ambari.server.security.authorization.AuthorizationException;
import org.apache.ambari.server.serveraction.kerberos.AdhocKeytabPrincipalPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.google.inject.AbstractModule;

/**
 * {@code POST /clusters/{cluster}/adhoc_keytab} issues keytabs with the KDC administrator
 * credentials Ambari holds: only cluster administrators may call it, and only for principals the
 * {@link AdhocKeytabPrincipalPolicy} accepts for the namespace receiving the keytab. Refusals surface as {@link AuthorizationException}
 * (403, see {@code CreateHandlerTest#testHandleRequest__AuthorizationFailure}) or, for a malformed
 * principal, {@link IllegalArgumentException} (400); nothing is scheduled.
 */
public class AdhocKeytabResourceProviderTest {

  private static final String PRINCIPAL = "trino-analytics@EXAMPLE.COM";
  private static final String NAMESPACE = "analytics";

  private ServerActionProviderTestFixture fixture;
  private AdhocKeytabPrincipalPolicy principalPolicy;

  @Before
  public void setUp() throws Exception {
    fixture = new ServerActionProviderTestFixture();
    principalPolicy = createMock(AdhocKeytabPrincipalPolicy.class);
  }

  @After
  public void clearAuthentication() {
    SecurityContextHolder.getContext().setAuthentication(null);
  }

  @Test
  public void testClusterAdministratorGetsAcceptedPrincipal() throws Exception {
    principalPolicy.verify(same(fixture.cluster), eq(PRINCIPAL), eq(NAMESPACE));
    expectLastCall();
    fixture.expectOneRequestScheduled();
    replayAndInject();
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createClusterAdministrator());

    RequestStatus status = new AdhocKeytabResourceProvider(fixture.controller)
        .createResources(createRequest(keytab(PRINCIPAL)));

    assertEquals(42L, status.getRequestResource().getPropertyValue("Requests/id"));
    fixture.verifyScheduling();
    verify(principalPolicy);
  }

  @Test
  public void testRequestWithoutNamespaceIsRejected() throws Exception {
    principalPolicy.verify(same(fixture.cluster), eq(PRINCIPAL), isNull());
    expectLastCall().andThrow(new IllegalArgumentException("'null' is not a valid Kubernetes namespace"));
    replayAndInject();
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createClusterAdministrator());

    Map<String, Object> properties = keytab(PRINCIPAL);
    properties.remove("AdhocKeytab/namespace");
    try {
      new AdhocKeytabResourceProvider(fixture.controller).createResources(createRequest(properties));
      fail("Expected the request without namespace to be rejected");
    } catch (IllegalArgumentException expected) {
      // answered 400 by the API
    }
    fixture.verifyScheduling();
    verify(principalPolicy);
  }

  @Test
  public void testClusterUserIsRefused() throws Exception {
    assertRefusedBeforePolicy(TestAuthenticationFactory.createClusterUser());
  }

  @Test
  public void testClusterOperatorIsRefused() throws Exception {
    assertRefusedBeforePolicy(TestAuthenticationFactory.createClusterOperator());
  }

  @Test
  public void testAdministratorOfAnotherClusterIsRefused() throws Exception {
    assertRefusedBeforePolicy(TestAuthenticationFactory.createClusterAdministrator("otherAdmin", CLUSTER_RESOURCE_ID + 1));
  }

  @Test
  public void testUnauthenticatedCallerIsRefused() throws Exception {
    assertRefusedBeforePolicy(null);
  }

  @Test
  public void testRefusedPrincipalIsReportedAsForbidden() throws Exception {
    principalPolicy.verify(same(fixture.cluster), eq("hdfs-c1@EXAMPLE.COM"), eq(NAMESPACE));
    expectLastCall().andThrow(new AuthorizationException("Ambari does not issue an ad-hoc keytab for 'hdfs-c1@EXAMPLE.COM'."));
    replayAndInject();
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createClusterAdministrator());

    try {
      new AdhocKeytabResourceProvider(fixture.controller).createResources(createRequest(keytab("hdfs-c1@EXAMPLE.COM")));
      fail("Expected the cluster principal to be refused");
    } catch (AuthorizationException e) {
      // must not be wrapped into a server error
      assertEquals("Ambari does not issue an ad-hoc keytab for 'hdfs-c1@EXAMPLE.COM'.", e.getMessage());
    }
    fixture.verifyScheduling();
  }

  @Test
  public void testMalformedPrincipalIsReportedAsBadRequest() throws Exception {
    principalPolicy.verify(same(fixture.cluster), eq("not a principal"), eq(NAMESPACE));
    expectLastCall().andThrow(new IllegalArgumentException("'not a principal' is not a valid principal"));
    replayAndInject();
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createClusterAdministrator());

    try {
      new AdhocKeytabResourceProvider(fixture.controller).createResources(createRequest(keytab("not a principal")));
      fail("Expected the malformed principal to be rejected");
    } catch (IllegalArgumentException expected) {
      // answered 400 by the API
    }
    fixture.verifyScheduling();
  }

  @Test
  public void testNothingIsScheduledWhenOnePrincipalOfTheRequestIsRefused() throws Exception {
    principalPolicy.verify(same(fixture.cluster), eq(PRINCIPAL), eq(NAMESPACE));
    expectLastCall().anyTimes();
    principalPolicy.verify(same(fixture.cluster), eq("hdfs-c1@EXAMPLE.COM"), eq(NAMESPACE));
    expectLastCall().andThrow(new AuthorizationException("refused")).anyTimes();
    replayAndInject();
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createClusterAdministrator());

    Set<Map<String, Object>> items = new HashSet<>();
    items.add(keytab(PRINCIPAL));
    items.add(keytab("hdfs-c1@EXAMPLE.COM"));
    Request request = PropertyHelper.getCreateRequest(items, null);
    try {
      new AdhocKeytabResourceProvider(fixture.controller).createResources(request);
      fail("Expected the request to be refused");
    } catch (AuthorizationException expected) {
      // whichever item comes first, no keytab is scheduled
    }
    fixture.verifyScheduling();
  }

  private void assertRefusedBeforePolicy(Authentication authentication) throws Exception {
    replayAndInject();
    SecurityContextHolder.getContext().setAuthentication(authentication);

    try {
      new AdhocKeytabResourceProvider(fixture.controller).createResources(createRequest(keytab(PRINCIPAL)));
      fail("Expected the keytab request to be refused");
    } catch (AuthorizationException expected) {
      // refused before the principal is even looked at
    }
    fixture.verifyScheduling();
    verify(principalPolicy);
  }

  private void replayAndInject() {
    replay(principalPolicy);
    fixture.replayAndInject(AdhocKeytabResourceProvider.class, new AbstractModule() {
      @Override
      protected void configure() {
        bind(AdhocKeytabPrincipalPolicy.class).toInstance(principalPolicy);
      }
    });
  }

  private static Map<String, Object> keytab(String principal) {
    Map<String, Object> properties = new HashMap<>();
    properties.put("Clusters/cluster_name", CLUSTER_NAME);
    properties.put("AdhocKeytab/principal", principal);
    properties.put("AdhocKeytab/namespace", NAMESPACE);
    properties.put("AdhocKeytab/default_realm", "EXAMPLE.COM");
    return properties;
  }
}
