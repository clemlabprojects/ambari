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
package org.apache.ambari.server.serveraction.upgrades;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleCommand;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.agent.ExecutionCommand;
import org.apache.ambari.server.agent.stomp.MetadataHolder;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.AmbariManagementControllerImpl;
import org.apache.ambari.server.events.MetadataUpdateEvent;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.State;
import org.apache.ambari.server.state.UpgradeContext;
import org.apache.ambari.server.state.UpgradeJavaRuntime;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import com.google.gson.GsonBuilder;

public class SwitchJavaRuntimeActionTest {
  private SwitchJavaRuntimeAction action;
  private UpgradeJavaRuntime runtime;
  private Configuration configuration;
  private MetadataHolder metadata;
  private Cluster cluster;
  private ServiceComponentHost host;
  private ServiceComponent component;
  private UpgradeJavaRuntime.Plan plan;
  private MetadataUpdateEvent event;

  private void inject(String field, Object value) throws Exception {
    Field target = SwitchJavaRuntimeAction.class.getDeclaredField(field);
    target.setAccessible(true);
    target.set(action, value);
  }

  @Before
  public void setup() throws Exception {
    action = spy(new SwitchJavaRuntimeAction());
    cluster = mock(Cluster.class);
    Clusters clusters = mock(Clusters.class);
    when(clusters.getCluster("cluster")).thenReturn(cluster);
    doReturn(clusters).when(action).getClusters();
    UpgradeContext context = mock(UpgradeContext.class);
    doReturn(context).when(action).getUpgradeContext(cluster);
    when(context.getSupportedServices()).thenReturn(Collections.singleton("HDFS"));
    Service service = mock(Service.class);
    when(cluster.getService("HDFS")).thenReturn(service);
    component = mock(ServiceComponent.class);
    when(component.isVersionAdvertised()).thenReturn(true);
    when(service.getServiceComponents()).thenReturn(Collections.singletonMap("NAMENODE", component));
    host = mock(ServiceComponentHost.class);
    when(host.getState()).thenReturn(State.INSTALLED);
    when(component.getServiceComponentHosts()).thenReturn(Collections.singletonMap("host1", host));
    runtime = mock(UpgradeJavaRuntime.class);
    configuration = mock(Configuration.class);
    metadata = mock(MetadataHolder.class);
    AmbariManagementControllerImpl controller = mock(AmbariManagementControllerImpl.class);
    event = mock(MetadataUpdateEvent.class);
    when(controller.getClustersMetadata()).thenReturn(event);
    inject("runtimes", runtime);
    inject("configuration", configuration);
    inject("metadata", metadata);
    inject("controller", controller);
    doReturn(new GsonBuilder().serializeNulls().create()).when(action).getGson();
    action.setHostRoleCommand(mock(HostRoleCommand.class));
    plan = new UpgradeJavaRuntime.Plan();
    plan.before = new HashMap<>();
    plan.before.put("java.home", "/jdk8");
    plan.before.put("stack.java.home", "/jdk8");
    plan.before.put("secondary.java.home", null);
    plan.after = new HashMap<>();
    plan.after.put("java.home", "/jdk17");
    plan.after.put("stack.java.home", "/jdk17");
    plan.after.put("secondary.java.home", "/jdk21");
    when(runtime.currentHomes()).thenReturn(plan.before);
  }

  private CommandReport execute() throws Exception {
    ExecutionCommand command = new ExecutionCommand();
    command.setClusterName("cluster");
    command.setCommandParams(Collections.singletonMap(UpgradeJavaRuntime.PLAN,
        new GsonBuilder().serializeNulls().create().toJson(plan)));
    action.setExecutionCommand(command);
    return action.execute(new ConcurrentHashMap<>());
  }

  @Test
  public void persistsBeforePublishingMetadata() throws Exception {
    assertEquals(0, execute().getExitCode());
    InOrder order = inOrder(configuration, metadata);
    order.verify(configuration).updateStackJavaHomes(plan.after);
    order.verify(metadata).updateData(event);
  }

  @Test
  public void stillRunningServiceBlocksTheSwitch() throws Exception {
    when(host.getState()).thenReturn(State.STARTED);
    assertEquals(1, execute().getExitCode());
    verify(configuration, never()).updateStackJavaHomes(anyMap());
    verify(metadata, never()).updateData(any(MetadataUpdateEvent.class));
  }

  @Test
  public void nonVersionedServiceDoesNotBlockTheOdpUpgrade() throws Exception {
    when(component.isVersionAdvertised()).thenReturn(false);
    when(host.getState()).thenReturn(State.STARTED);
    assertEquals(0, execute().getExitCode());
  }

  @Test
  public void externallyChangedPathsBlockTheSwitch() throws Exception {
    when(runtime.currentHomes()).thenReturn(Collections.singletonMap("java.home", "/unexpected"));
    assertEquals(1, execute().getExitCode());
    verify(configuration, never()).updateStackJavaHomes(anyMap());
  }

  @Test
  public void retryAfterPersistenceRepublishesMetadata() throws Exception {
    when(runtime.currentHomes()).thenReturn(plan.after);
    assertEquals(0, execute().getExitCode());
    verify(metadata).updateData(event);
  }

  @Test
  public void persistenceFailureBlocksMetadataPublication() throws Exception {
    doThrow(new AmbariException("read-only configuration")).when(configuration).updateStackJavaHomes(anyMap());
    assertEquals(1, execute().getExitCode());
    verify(metadata, never()).updateData(any(MetadataUpdateEvent.class));
  }

  @Test
  public void downgradeRestoresOriginalPaths() throws Exception {
    plan.restore = true;
    when(runtime.currentHomes()).thenReturn(plan.after);
    assertEquals(0, execute().getExitCode());
    verify(configuration).updateStackJavaHomes(plan.before);
    verify(runtime, never()).validate(any(Cluster.class), any(UpgradeJavaRuntime.Plan.class), anyBoolean());
  }
}
