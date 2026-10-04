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
package org.apache.ambari.server.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.ExecutionCommand;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.orm.dao.HostRoleCommandDAO;
import org.apache.ambari.server.orm.dao.RequestDAO;
import org.apache.ambari.server.orm.dao.SettingDAO;
import org.apache.ambari.server.orm.dao.UpgradeDAO;
import org.apache.ambari.server.orm.entities.ExecutionCommandEntity;
import org.apache.ambari.server.orm.entities.HostRoleCommandEntity;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.orm.entities.RequestEntity;
import org.apache.ambari.server.orm.entities.UpgradeEntity;
import org.apache.ambari.server.security.TestAuthenticationFactory;
import org.apache.ambari.server.state.UpgradeHelper.UpgradeGroupHolder;
import org.apache.ambari.server.state.stack.upgrade.Direction;
import org.apache.ambari.server.state.stack.upgrade.Grouping;
import org.apache.ambari.server.state.stack.upgrade.ServerSideActionTask;
import org.apache.ambari.server.state.stack.upgrade.UpdateStackGrouping;
import org.apache.ambari.server.state.stack.upgrade.UpgradeType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.inject.Provider;

public class UpgradeJavaRuntimeTest {
  private UpgradeJavaRuntime runtime;
  private AmbariMetaInfo meta;
  private HostRoleCommandDAO commands;
  private RequestDAO requests;
  private UpgradeDAO upgrades;
  private SettingDAO settings;
  private Cluster cluster;
  private RepositoryVersionEntity repository;
  private RepositoryVersionEntity source;
  private Service service;
  private PropertyInfo features;
  private UpgradeContext context;
  private Gson gson = new GsonBuilder().serializeNulls().create();
  private UpgradeJavaRuntime.Plan plan;

  private void inject(String field, Object value) throws Exception {
    Field target = UpgradeJavaRuntime.class.getDeclaredField(field);
    target.setAccessible(true);
    target.set(runtime, value);
  }

  @Before
  public void setup() throws Exception {
    runtime = new UpgradeJavaRuntime();
    meta = mock(AmbariMetaInfo.class);
    commands = mock(HostRoleCommandDAO.class);
    requests = mock(RequestDAO.class);
    upgrades = mock(UpgradeDAO.class);
    settings = mock(SettingDAO.class);
    cluster = mock(Cluster.class);
    repository = mock(RepositoryVersionEntity.class);
    context = mock(UpgradeContext.class);
    Configuration configuration = mock(Configuration.class);
    Clusters clusters = mock(Clusters.class);
    inject("metaInfo", (Provider<AmbariMetaInfo>) () -> meta);
    inject("commands", commands);
    inject("requests", requests);
    inject("upgrades", upgrades);
    inject("settings", settings);
    inject("gson", gson);
    inject("configuration", configuration);
    inject("clusters", clusters);
    when(configuration.getJavaHome()).thenReturn("/jdk8");
    when(configuration.getStackJavaHome()).thenReturn("/jdk8");
    when(clusters.getClusters()).thenReturn(Collections.singletonMap("cluster", cluster));
    when(cluster.getClusterId()).thenReturn(1L);
    when(cluster.getClusterName()).thenReturn("cluster");
    when(cluster.getHostNames()).thenReturn(new HashSet<>(Arrays.asList("host1", "host2")));
    when(context.getCluster()).thenReturn(cluster);
    when(context.getType()).thenReturn(UpgradeType.NON_ROLLING);
    when(context.getDirection()).thenReturn(Direction.UPGRADE);
    when(context.getRepositoryVersion()).thenReturn(repository);
    when(repository.getStackId()).thenReturn(new StackId("ODP", "1.3"));
    when(repository.getVersion()).thenReturn("1.3.2.0-36");
    when(repository.getId()).thenReturn(123L);
    source = mock(RepositoryVersionEntity.class);
    when(source.getVersion()).thenReturn("1.2.4.0-1");
    service = mock(Service.class);
    ServiceComponent component = mock(ServiceComponent.class);
    when(component.isVersionAdvertised()).thenReturn(true);
    when(service.getServiceComponents()).thenReturn(Collections.singletonMap("NAMENODE", component));
    when(service.getDesiredRepositoryVersion()).thenReturn(source);
    when(cluster.getServices()).thenReturn(Collections.singletonMap("HDFS", service));
    features = new PropertyInfo();
    features.setName("stack_features");
    features.setValue(new String(Files.readAllBytes(Paths.get(
        "src/main/resources/stacks/ODP/1.3/properties/stack_features.json")), StandardCharsets.UTF_8));
    when(meta.getStackProperties("ODP", "1.3")).thenReturn(Collections.singleton(features));
    RequestEntity request = mock(RequestEntity.class);
    when(request.getClusterId()).thenReturn(1L);
    when(requests.findByPK(10L)).thenReturn(request);
    plan = new UpgradeJavaRuntime.Plan();
    plan.validationRequest = 10L;
    plan.primaryMajor = 17;
    plan.secondaryMajor = 21;
    plan.before = runtime.currentHomes();
    plan.after = new HashMap<>();
    plan.after.put("java.home", "/jdk17");
    plan.after.put("stack.java.home", "/jdk17");
    plan.after.put("secondary.java.home", "/jdk21");
    List<HostRoleCommandEntity> checks = Arrays.asList(check("host1"), check("host2"));
    when(commands.findByRequest(10L)).thenReturn(checks);
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());
  }

  @After
  public void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  private HostRoleCommandEntity check(String host) {
    HostRoleCommandEntity task = mock(HostRoleCommandEntity.class);
    when(task.getHostName()).thenReturn(host);
    when(task.getStatus()).thenReturn(HostRoleStatus.COMPLETED);
    when(task.getEndTime()).thenReturn(System.currentTimeMillis());
    when(task.getStructuredOut()).thenReturn(("{\"upgrade_java\":{"
        + "\"primary\":{\"home\":\"/jdk17\",\"major\":17,\"valid\":true},"
        + "\"secondary\":{\"home\":\"/jdk21\",\"major\":21,\"valid\":true}}}").getBytes(StandardCharsets.UTF_8));
    ExecutionCommand command = new ExecutionCommand();
    command.setRole("check_upgrade_java");
    ExecutionCommandEntity stored = new ExecutionCommandEntity();
    stored.setCommand(gson.toJson(command).getBytes(StandardCharsets.UTF_8));
    when(task.getExecutionCommand()).thenReturn(stored);
    return task;
  }

  @Test
  public void targetFeatureControlsVersions() throws Exception {
    for (String version : Arrays.asList("1.2.4.0-1", "1.3.1.0-304")) {
      when(repository.getVersion()).thenReturn(version);
      assertNull(runtime.requirements(repository));
    }
    when(repository.getVersion()).thenReturn("1.3.2.0-36");
    assertEquals(21, runtime.requirements(repository).get("secondary_java_major").getAsInt());
  }

  @Test
  public void olderSourcesSelectJdksForAnyTargetInTheNewRuntimeRange() throws Exception {
    for (String sourceVersion : Arrays.asList("1.2.4.0-1", "1.3.1.0-304")) {
      when(source.getVersion()).thenReturn(sourceVersion);
      for (String targetVersion : Arrays.asList("1.3.2.0-36", "1.3.2.1-1", "1.3.3.0-1")) {
        when(repository.getVersion()).thenReturn(targetVersion);
        assertEquals(17, runtime.transitionRequirements(cluster, repository).get("primary_java_major").getAsInt());
      }
    }
  }

  @Test
  public void upgradesWithinTheRuntimeRangeNeedNeitherSelectionNorSwitch() throws Exception {
    for (String sourceVersion : Arrays.asList("1.3.2.0-1", "1.3.2.1-2", "1.3.3.0-1")) {
      when(source.getVersion()).thenReturn(sourceVersion);
      when(repository.getVersion()).thenReturn("1.3.3.0-2");
      assertNull(runtime.transitionRequirements(cluster, repository));
      UpgradeJavaRuntime.Plan saved = runtime.prepare(context, Collections.emptyMap());
      assertNull(saved);
      List<UpgradeGroupHolder> groups = new ArrayList<>();
      runtime.addStage(groups, saved);
      assertTrue(groups.isEmpty());
    }
    verifyZeroInteractions(commands, requests);
  }

  @Test
  public void oldNonVersionedServiceDoesNotForceAnotherSwitch() throws Exception {
    when(source.getVersion()).thenReturn("1.3.2.0-36");
    Service metrics = mock(Service.class);
    ServiceComponent component = mock(ServiceComponent.class);
    when(metrics.getServiceComponents()).thenReturn(Collections.singletonMap("METRICS_COLLECTOR", component));
    Map<String, Service> services = new HashMap<>();
    services.put("HDFS", service);
    services.put("AMBARI_METRICS", metrics);
    when(cluster.getServices()).thenReturn(services);
    assertNull(runtime.transitionRequirements(cluster, repository));
    verify(metrics, never()).getDesiredRepositoryVersion();
  }

  @Test
  public void disabledFeatureDoesNotRequireValidationOrPlanASwitch() throws Exception {
    JsonObject document = gson.fromJson(features.getValue(), JsonObject.class);
    document.getAsJsonObject("ODP").getAsJsonArray("stack_features").get(0).getAsJsonObject()
        .addProperty("enabled", false);
    features.setValue(gson.toJson(document));
    assertNull(runtime.requirements(repository));
    assertNull(runtime.prepare(context, Collections.emptyMap()));
    verifyZeroInteractions(commands, requests);
  }

  @Test
  public void absentFeatureLeavesUpgradeUnchanged() throws Exception {
    when(meta.getStackProperties("ODP", "1.3")).thenReturn(Collections.emptySet());
    assertNull(runtime.prepare(context, Collections.emptyMap()));
    verifyZeroInteractions(commands, requests);
  }

  @Test
  public void futureTransitionComesEntirelyFromTargetMetadata() throws Exception {
    JsonObject document = gson.fromJson(features.getValue(), JsonObject.class);
    JsonObject oldRuntime = document.getAsJsonObject("ODP").getAsJsonArray("stack_features").get(0).getAsJsonObject();
    JsonObject futureRuntime = oldRuntime.deepCopy();
    oldRuntime.addProperty("max_version", "1.4.0.0");
    futureRuntime.addProperty("min_version", "1.4.0.0");
    futureRuntime.addProperty("primary_java_major", 21);
    futureRuntime.addProperty("secondary_java_major", 25);
    document.getAsJsonObject("ODP").getAsJsonArray("stack_features").add(futureRuntime);
    features.setValue(gson.toJson(document));

    when(source.getVersion()).thenReturn("1.3.2.0-36");
    assertNull(runtime.transitionRequirements(cluster, repository));
    when(repository.getVersion()).thenReturn("1.4.0.0-1");
    assertEquals(25, runtime.transitionRequirements(cluster, repository).get("secondary_java_major").getAsInt());
    when(source.getVersion()).thenReturn("1.4.0.0-1");
    when(repository.getVersion()).thenReturn("1.4.0.0-2");
    assertNull(runtime.transitionRequirements(cluster, repository));
  }

  @Test(expected = AmbariException.class)
  public void sourceVersionMustBeKnownForVersionedServices() throws Exception {
    when(source.getVersion()).thenReturn(null);
    runtime.prepare(context, Collections.emptyMap());
  }

  @Test(expected = AmbariException.class)
  public void selectionCannotForceAnUnneededRuntimeChange() throws Exception {
    when(source.getVersion()).thenReturn("1.3.2.0-1");
    runtime.prepare(context, Collections.singletonMap(UpgradeJavaRuntime.PRIMARY, "/jdk17"));
  }

  @Test
  public void validSelectionSavesBothSourceAndTarget() throws Exception {
    Map<String, Object> input = new HashMap<>();
    input.put(UpgradeJavaRuntime.PRIMARY, "/jdk17");
    input.put(UpgradeJavaRuntime.SECONDARY, "/jdk21");
    input.put(UpgradeJavaRuntime.VALIDATION, 10L);
    UpgradeJavaRuntime.Plan saved = runtime.prepare(context, input);
    assertEquals(plan.before, saved.before);
    assertEquals(plan.after, saved.after);
    verifyZeroInteractions(settings);
  }

  @Test
  public void finalizeRetiresOnlyTheMatchingClustersTargetDraft() {
    runtime.retireDraft(cluster, repository);
    verify(settings).removeByName("upgrade-java-cluster-123");
    verifyZeroInteractions(commands, requests, upgrades);
  }

  @Test
  public void draftCleanupFailureDoesNotInvalidateCompletedUpgrade() {
    doThrow(new IllegalStateException("database unavailable")).when(settings).removeByName(anyString());
    runtime.retireDraft(cluster, repository);
    verifyZeroInteractions(commands, requests, upgrades);
  }

  @Test(expected = AmbariException.class)
  public void cannotStartWithoutSelection() throws Exception {
    runtime.prepare(context, Collections.emptyMap());
  }

  @Test
  public void completeProofPasses() throws Exception {
    runtime.validate(cluster, plan, true);
  }

  @Test
  public void administrationUsesInstalledPolicyWithoutCrossingAnUpgradeBoundary() throws Exception {
    when(service.getDesiredRepositoryVersion()).thenReturn(repository);
    assertEquals(plan.after, runtime.administrationPlan(cluster, "/jdk17", "/jdk21", 10L).after);
    verifyZeroInteractions(settings, upgrades);
  }

  @Test(expected = AmbariException.class)
  public void administrationRejectsAnActiveOrPausedUpgrade() throws Exception {
    when(cluster.getUpgradeInProgress()).thenReturn(mock(UpgradeEntity.class));
    runtime.administrationRequirements(cluster);
  }

  @Test(expected = AmbariException.class)
  public void administrationRequiresGlobalAdministrator() throws Exception {
    SecurityContextHolder.clearContext();
    runtime.administrationRequirements(cluster);
  }

  @Test(expected = AmbariException.class)
  public void administrationRejectsOlderStacksWithoutPolicy() throws Exception {
    when(service.getDesiredRepositoryVersion()).thenReturn(repository);
    when(repository.getVersion()).thenReturn("1.3.1.0-304");
    runtime.administrationRequirements(cluster);
  }

  @Test(expected = AmbariException.class)
  public void administrationRejectsStaleHostChecks() throws Exception {
    when(service.getDesiredRepositoryVersion()).thenReturn(repository);
    when(commands.findByRequest(10L).get(0).getEndTime()).thenReturn(1L);
    runtime.administrationPlan(cluster, "/jdk17", "/jdk21", 10L);
  }

  @Test(expected = AmbariException.class)
  public void administrationCannotDisableStackPolicyFromTheClient() throws Exception {
    when(service.getDesiredRepositoryVersion()).thenReturn(repository);
    runtime.administrationPlan(cluster, "/jdk17", "/jdk25", 10L);
  }

  @Test(expected = AmbariException.class)
  public void missingHostFails() throws Exception {
    HostRoleCommandEntity task = check("host1");
    when(commands.findByRequest(10L)).thenReturn(Collections.singletonList(task));
    runtime.validate(cluster, plan, true);
  }

  @Test(expected = AmbariException.class)
  public void changedPathFails() throws Exception {
    plan.after.put("java.home", "/different17");
    runtime.validate(cluster, plan, true);
  }

  @Test(expected = AmbariException.class)
  public void wrongMajorFails() throws Exception {
    plan.secondaryMajor = 25;
    runtime.validate(cluster, plan, true);
  }

  @Test(expected = AmbariException.class)
  public void failedHostFails() throws Exception {
    when(commands.findByRequest(10L).get(0).getStatus()).thenReturn(HostRoleStatus.FAILED);
    runtime.validate(cluster, plan, true);
  }

  @Test(expected = AmbariException.class)
  public void staleProofFails() throws Exception {
    when(commands.findByRequest(10L).get(0).getEndTime()).thenReturn(1L);
    runtime.validate(cluster, plan, true);
  }

  @Test
  public void longRunningUpgradeDoesNotExpireAcceptedProof() throws Exception {
    when(commands.findByRequest(10L).get(0).getEndTime()).thenReturn(1L);
    runtime.validate(cluster, plan, false);
  }

  @Test(expected = AmbariException.class)
  public void anotherClustersRequestFails() throws Exception {
    when(requests.findByPK(10L).getClusterId()).thenReturn(2L);
    runtime.validate(cluster, plan, true);
  }

  @Test
  public void transitionIsPlannedBeforeRepositorySwitchAndCannotBeSkipped() throws Exception {
    UpgradeGroupHolder stops = new UpgradeGroupHolder();
    stops.groupClass = Grouping.class;
    UpgradeGroupHolder repositorySwitch = new UpgradeGroupHolder();
    repositorySwitch.groupClass = UpdateStackGrouping.class;
    List<UpgradeGroupHolder> groups = new ArrayList<>(Arrays.asList(stops, repositorySwitch));
    runtime.addStage(groups, plan);
    assertSame(stops, groups.get(0));
    assertEquals("SWITCH_STACK_JAVA", groups.get(1).name);
    assertSame(repositorySwitch, groups.get(2));
    assertFalse(groups.get(1).skippable);
    assertFalse(groups.get(1).supportsAutoSkipOnFailure);
    ServerSideActionTask task = (ServerSideActionTask) groups.get(1).items.get(0).getTasks().get(0).getTasks().get(0);
    UpgradeJavaRuntime.Plan restored = gson.fromJson(task.getParameters().get(UpgradeJavaRuntime.PLAN), UpgradeJavaRuntime.Plan.class);
    assertTrue(restored.before.containsKey("secondary.java.home"));
    assertNull(restored.before.get("secondary.java.home"));
  }

  @Test
  public void downgradeRecoversOriginalPlanFromPersistedCommand() throws Exception {
    UpgradeEntity original = mock(UpgradeEntity.class);
    when(original.getRequestId()).thenReturn(20L);
    when(upgrades.findLastUpgradeForCluster(1L, Direction.UPGRADE)).thenReturn(original);
    HostRoleCommandEntity task = mock(HostRoleCommandEntity.class);
    ExecutionCommand command = new ExecutionCommand();
    command.setCommandParams(Collections.singletonMap(UpgradeJavaRuntime.PLAN, gson.toJson(plan)));
    ExecutionCommandEntity stored = new ExecutionCommandEntity();
    stored.setCommand(gson.toJson(command).getBytes(StandardCharsets.UTF_8));
    when(task.getExecutionCommand()).thenReturn(stored);
    when(commands.findByRequest(20L)).thenReturn(Collections.singletonList(task));
    when(context.getDirection()).thenReturn(Direction.DOWNGRADE);
    UpgradeJavaRuntime.Plan restored = runtime.prepare(context, Collections.emptyMap());
    assertTrue(restored.restore);
    assertEquals(plan.before, restored.before);
  }

  @Test(expected = AmbariException.class)
  public void missingSafeBoundaryFails() throws Exception {
    runtime.addStage(new ArrayList<>(), plan);
  }
}
