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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import java.util.Optional;

import javax.ws.rs.core.Response;

import org.apache.ambari.server.ClusterNotFoundException;
import org.apache.ambari.server.security.TestAuthenticationFactory;
import org.apache.ambari.server.serveraction.kerberos.AdhocKeytabPayloadStore;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;

/**
 * Reading or acknowledging an issued keytab requires the same authorization as issuing it, and a
 * refusal must answer 403 without touching the stored keytab.
 */
public class AdhocKeytabServiceTest {

  private static final String CLUSTER_NAME = "c1";
  private static final String PAYLOAD_REF = "0b6a6a5e-2a43-4c7a-9e4f-1d2c3b4a5f60";

  private AdhocKeytabPayloadStore payloadStore;

  @Before
  public void setUp() throws Exception {
    Cluster cluster = createNiceMock(Cluster.class);
    expect(cluster.getResourceId()).andReturn(4L).anyTimes();
    Clusters clusters = createNiceMock(Clusters.class);
    expect(clusters.getCluster(CLUSTER_NAME)).andReturn(cluster).anyTimes();
    expect(clusters.getCluster("unknown")).andThrow(new ClusterNotFoundException("unknown")).anyTimes();
    replay(cluster, clusters);

    // Strict: a refused caller must not reach the store at all.
    payloadStore = createMock(AdhocKeytabPayloadStore.class);

    Guice.createInjector(new AbstractModule() {
      @Override
      protected void configure() {
        bind(Clusters.class).toInstance(clusters);
        bind(AdhocKeytabPayloadStore.class).toInstance(payloadStore);
        requestStaticInjection(AdhocKeytabService.class);
      }
    });
  }

  @After
  public void clearAuthentication() {
    SecurityContextHolder.getContext().setAuthentication(null);
  }

  @Test
  public void testClusterAdministratorReadsAndAcknowledgesPayload() throws Exception {
    expect(payloadStore.readPayload(CLUSTER_NAME, PAYLOAD_REF)).andReturn(Optional.of("a2V5dGFi"));
    expect(payloadStore.ackPayload(CLUSTER_NAME, PAYLOAD_REF)).andReturn(true);
    replay(payloadStore);
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createClusterAdministrator());

    AdhocKeytabService service = new AdhocKeytabService(CLUSTER_NAME);
    Response read = service.readPayload(PAYLOAD_REF);
    Response ack = service.ackPayload(PAYLOAD_REF);

    assertEquals(200, read.getStatus());
    assertEquals("a2V5dGFi", read.getEntity());
    assertEquals(204, ack.getStatus());
    verify(payloadStore);
  }

  @Test
  public void testClusterUserIsRefused() throws Exception {
    assertRefused(TestAuthenticationFactory.createClusterUser());
  }

  @Test
  public void testClusterOperatorIsRefused() throws Exception {
    assertRefused(TestAuthenticationFactory.createClusterOperator());
  }

  @Test
  public void testAdministratorOfAnotherClusterIsRefused() throws Exception {
    assertRefused(TestAuthenticationFactory.createClusterAdministrator("otherAdmin", 5L));
  }

  @Test
  public void testUnauthenticatedCallerIsRefused() throws Exception {
    assertRefused(null);
  }

  @Test
  public void testUnknownClusterIsNotFound() throws Exception {
    replay(payloadStore);
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());

    AdhocKeytabService service = new AdhocKeytabService("unknown");
    assertEquals(404, service.readPayload(PAYLOAD_REF).getStatus());
    assertEquals(404, service.ackPayload(PAYLOAD_REF).getStatus());
    verify(payloadStore);
  }

  private void assertRefused(Authentication authentication) throws Exception {
    replay(payloadStore);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    AdhocKeytabService service = new AdhocKeytabService(CLUSTER_NAME);
    assertEquals(403, service.readPayload(PAYLOAD_REF).getStatus());
    assertEquals(403, service.ackPayload(PAYLOAD_REF).getStatus());
    verify(payloadStore);
  }
}
