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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.agent.stomp.MetadataHolder;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.AmbariManagementControllerImpl;
import org.apache.ambari.server.controller.spi.Predicate;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.SystemException;
import org.apache.ambari.server.controller.utilities.PredicateBuilder;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.UpgradeJavaRuntime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;

public class JavaRuntimeResourceProviderTest {
  private final Map<Field, Object> originalInjections = new HashMap<>();
  private JavaRuntimeResourceProvider provider;
  private UpgradeJavaRuntime runtimes;
  private Configuration configuration;
  private MetadataHolder metadata;
  private Cluster cluster;
  private ServiceComponentHost host;
  private Predicate predicate;
  private Map<String, Object> input;
  private UpgradeJavaRuntime.Plan plan;

  private void inject(String name, Object value) throws Exception {
    Field field = JavaRuntimeResourceProvider.class.getDeclaredField(name);
    field.setAccessible(true);
    originalInjections.put(field, field.get(null));
    field.set(null, value);
  }

  @Before
  public void setup() throws Exception {
    AmbariManagementControllerImpl controller = mock(AmbariManagementControllerImpl.class);
    Clusters clusters = mock(Clusters.class);
    cluster = mock(Cluster.class);
    configuration = mock(Configuration.class);
    runtimes = mock(UpgradeJavaRuntime.class);
    metadata = mock(MetadataHolder.class);
    inject("runtimes", runtimes);
    inject("configuration", configuration);
    inject("metadata", (com.google.inject.Provider<MetadataHolder>) () -> metadata);
    when(controller.getClusters()).thenReturn(clusters);
    when(clusters.getCluster("cluster")).thenReturn(cluster);
    when(cluster.getClusterName()).thenReturn("cluster");
    provider = new JavaRuntimeResourceProvider(controller);
    predicate = new PredicateBuilder().property("JavaRuntime/cluster_name").equals("cluster")
        .and().property("JavaRuntime/name").equals("stack").toPredicate();

    Service service = mock(Service.class);
    ServiceComponent component = mock(ServiceComponent.class);
    host = mock(ServiceComponentHost.class);
    when(cluster.getServices()).thenReturn(Collections.singletonMap("HDFS", service));
    when(service.getServiceComponents()).thenReturn(Collections.singletonMap("NAMENODE", component));
    when(component.isVersionAdvertised()).thenReturn(true);
    when(component.getServiceComponentHosts()).thenReturn(Collections.singletonMap("host", host));
    JsonObject required = new JsonObject();
    required.addProperty("min_version", "1.3.2.0");
    required.addProperty("primary_java_major", 17);
    required.addProperty("secondary_java_major", 21);
    when(runtimes.administrationRequirements(cluster)).thenReturn(required);
    Map<String, String> current = new HashMap<>();
    current.put("java.home", "/old17");
    current.put("stack.java.home", "/old17");
    current.put("secondary.java.home", "/old21");
    when(runtimes.currentHomes()).thenReturn(current);
    when(configuration.getJavaHome()).thenReturn("/old17");
    when(configuration.getStackJavaHome()).thenReturn("/old17");
    when(configuration.getSecondaryJavaHome()).thenReturn("/old21");
    plan = new UpgradeJavaRuntime.Plan();
    plan.after = new HashMap<>();
    plan.after.put("java.home", "/jdk17");
    plan.after.put("stack.java.home", "/jdk17");
    plan.after.put("secondary.java.home", "/jdk21");
    when(runtimes.administrationPlan(cluster, "/jdk17", "/jdk21", 10L)).thenReturn(plan);
    input = new HashMap<>();
    input.put("JavaRuntime/primary_java_home", "/jdk17");
    input.put("JavaRuntime/secondary_java_home", "/jdk21");
    input.put("JavaRuntime/validation_request_id", 10L);
    input.put("JavaRuntime/expected_java_home", "/old17");
    input.put("JavaRuntime/expected_primary_java_home", "/old17");
    input.put("JavaRuntime/expected_secondary_java_home", "/old21");
  }

  @After
  public void restoreInjections() throws Exception {
    for (Map.Entry<Field, Object> entry : originalInjections.entrySet()) {
      entry.getKey().set(null, entry.getValue());
    }
  }

  @Test
  public void readRequiresGlobalAccessAndReturnsCurrentPathsAndStackPolicy() throws Exception {
    Resource resource = provider.getResources(PropertyHelper.getReadRequest(), predicate).iterator().next();
    verify(runtimes).requireGlobalAccess();
    assertEquals("/old17", resource.getPropertyValue("JavaRuntime/primary_java_home"));
    assertEquals(21, resource.getPropertyValue("JavaRuntime/secondary_java_major"));
    verify(configuration, never()).updateStackJavaHomes(any());
  }

  @Test
  public void savePersistsOnlyStackHomesAndMarksComponentsForRestart() throws Exception {
    provider.updateResources(PropertyHelper.getUpdateRequest(input, null), predicate);
    verify(runtimes).administrationPlan(cluster, "/jdk17", "/jdk21", 10L);
    verify(configuration).updateStackJavaHomes(plan.after);
    assertEquals(3, plan.after.size());
    assertTrue(!plan.after.containsKey("ambari.java.home"));
    verify(metadata).updateData(any());
    verify(host).setRestartRequired(true);
  }

  @Test
  public void staleDialogCannotOverwriteNewerPaths() throws Exception {
    input.put("JavaRuntime/expected_primary_java_home", "/other17");
    assertSaveRejected();
    verify(configuration, never()).updateStackJavaHomes(any());
    verify(host, never()).setRestartRequired(true);
  }

  @Test
  public void failedValidationCannotWriteConfiguration() throws Exception {
    when(runtimes.administrationPlan(cluster, "/jdk17", "/jdk21", 10L))
        .thenThrow(new AmbariException("Active upgrade or failed host validation"));
    assertSaveRejected();
    verify(configuration, never()).updateStackJavaHomes(any());
    verify(metadata, never()).updateData(any());
  }

  @Test
  public void deniedAdministratorCannotReadOrWrite() throws Exception {
    doThrow(new AmbariException("Not an administrator")).when(runtimes).requireGlobalAccess();
    assertSaveRejected();
    try {
      provider.getResources(PropertyHelper.getReadRequest(), predicate);
      fail("Read should be denied");
    } catch (SystemException expected) {
      verify(configuration, never()).updateStackJavaHomes(any());
    }
  }

  @Test
  public void unchangedPathsDoNotMarkComponentsForRestart() throws Exception {
    plan.after = runtimes.currentHomes();
    provider.updateResources(PropertyHelper.getUpdateRequest(input, null), predicate);
    verify(host, never()).setRestartRequired(true);
    verify(metadata).updateData(any());
  }

  private void assertSaveRejected() throws Exception {
    try {
      provider.updateResources(PropertyHelper.getUpdateRequest(input, null), predicate);
      fail("Save should be rejected");
    } catch (SystemException expected) {
      // No fallback to an unvalidated save.
    }
  }
}
