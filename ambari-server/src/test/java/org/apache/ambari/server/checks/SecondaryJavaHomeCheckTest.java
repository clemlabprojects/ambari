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
package org.apache.ambari.server.checks;

import java.util.Collections;

import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.PrereqCheckRequest;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.RepositoryType;
import org.apache.ambari.server.state.UpgradeJavaRuntime;
import org.apache.ambari.server.state.stack.PrereqCheckStatus;
import org.apache.ambari.server.state.stack.PrerequisiteCheck;
import org.apache.ambari.server.state.stack.UpgradePack;
import org.apache.ambari.server.state.stack.upgrade.UpgradeType;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Provider;

/**
 * Tests {@link SecondaryJavaHomeCheck}.
 */
@RunWith(MockitoJUnitRunner.class)
public class SecondaryJavaHomeCheckTest {

  private final Clusters m_clusters = Mockito.mock(Clusters.class);
  private final SecondaryJavaHomeCheck m_check = new SecondaryJavaHomeCheck();
  private Configuration m_configuration;
  private UpgradeJavaRuntime runtimes;
  private final Cluster cluster = Mockito.mock(Cluster.class);

  @Mock
  private RepositoryVersionEntity m_repositoryVersion;

  @Before
  public void setup() throws Exception {
    m_check.clustersProvider = new Provider<Clusters>() {
      @Override
      public Clusters get() {
        return m_clusters;
      }
    };

    m_configuration = Mockito.mock(Configuration.class);
    m_check.config = m_configuration;
    m_check.gson = new Gson();
    runtimes = Mockito.mock(UpgradeJavaRuntime.class);
    java.lang.reflect.Field field = SecondaryJavaHomeCheck.class.getDeclaredField("runtimes");
    field.setAccessible(true);
    field.set(m_check, runtimes);
    JsonObject requirements = new JsonObject();
    requirements.addProperty("primary_java_major", 17);
    requirements.addProperty("secondary_java_major", 21);
    Mockito.when(runtimes.requirements(m_repositoryVersion)).thenReturn(requirements);
    Mockito.when(m_clusters.getCluster("cluster")).thenReturn(cluster);
    Mockito.when(runtimes.transitionRequirements(cluster, m_repositoryVersion)).thenReturn(requirements);

    // a rolling upgrade also requires a primary Java home the target accepts (JDK 17 here)
    Mockito.when(m_configuration.getJavaHome()).thenReturn(jdk("17.0.13").getAbsolutePath());
    Mockito.when(m_repositoryVersion.getVersion()).thenReturn("1.3.2.0-72");

    // STANDARD repo so the orchestration qualification applies.
    Mockito.when(m_repositoryVersion.getType()).thenReturn(RepositoryType.STANDARD);
  }

  private PrereqCheckRequest request(String targetVersion) {
    PrereqCheckRequest request = new PrereqCheckRequest("cluster");
    request.setTargetRepositoryVersion(m_repositoryVersion);
    return request;
  }

  @Test
  public void testExpressUpgradePackIncludesJavaSelectionWithoutExplicitEntry() {
    UpgradePack pack = Mockito.mock(UpgradePack.class);
    Mockito.when(pack.getType()).thenReturn(UpgradeType.NON_ROLLING);
    Mockito.when(pack.getPrerequisiteChecks()).thenReturn(Collections.emptyList());
    UpgradeCheckRegistry registry = new UpgradeCheckRegistry();
    registry.register(m_check);

    Assert.assertEquals(Collections.singletonList(m_check), registry.getFilteredUpgradeChecks(pack));
  }

  /**
   * Not applicable when upgrading to a version older than 1.3.2.0.
   */
  @Test
  public void testNotApplicableWhenTargetBelowMinVersion() throws Exception {
    Mockito.when(runtimes.requirements(m_repositoryVersion)).thenReturn(null);
    Assert.assertFalse(m_check.isApplicable(request("1.3.1.0-3")));
  }

  /**
   * Applicable for any upgrade into 1.3.2.0+, regardless of which services are
   * installed (the secondary JDK is a cluster-wide prerequisite).
   */
  @Test
  public void testApplicableForTargetAtMinVersion() throws Exception {
    Assert.assertTrue(m_check.isApplicable(request("1.3.2.0-25")));
  }

  /**
   * Applicable for a later target as well.
   */
  @Test
  public void testApplicableForTargetAboveMinVersion() throws Exception {
    Assert.assertTrue(m_check.isApplicable(request("1.3.3.0-1")));
  }

  /**
   * Fails (cluster-level) when secondary.java.home is not configured.
   */
  @Test
  public void testFailWhenSecondaryJavaHomeUnset() throws Exception {
    Mockito.when(m_configuration.getSecondaryJavaHome()).thenReturn(null);

    PrerequisiteCheck check = new PrerequisiteCheck(CheckDescription.SECONDARY_JAVA_HOME, "cluster");
    m_check.perform(check, request("1.3.2.0-25"));

    Assert.assertEquals(PrereqCheckStatus.FAIL, check.getStatus());
    Assert.assertTrue(check.getFailedOn().contains("cluster"));
  }

  /**
   * Blank (whitespace) secondary.java.home is treated as unset.
   */
  @Test
  public void testFailWhenSecondaryJavaHomeBlank() throws Exception {
    Mockito.when(m_configuration.getSecondaryJavaHome()).thenReturn("   ");

    PrerequisiteCheck check = new PrerequisiteCheck(CheckDescription.SECONDARY_JAVA_HOME, "cluster");
    m_check.perform(check, request("1.3.2.0-25"));

    Assert.assertEquals(PrereqCheckStatus.FAIL, check.getStatus());
  }

  /**
   * Passes when secondary.java.home is configured.
   */
  @Test
  public void testPassWhenSecondaryJavaHomeSet() throws Exception {
    Mockito.when(m_configuration.getSecondaryJavaHome()).thenReturn("/usr/lib/jvm/java-21-openjdk");

    PrerequisiteCheck check = new PrerequisiteCheck(CheckDescription.SECONDARY_JAVA_HOME, "cluster");
    m_check.perform(check, request("1.3.2.0-25"));

    Assert.assertEquals(PrereqCheckStatus.PASS, check.getStatus());
  }

  @Test
  public void testExpressUpgradeAdvertisesSelectionWithoutChangingRuntime() throws Exception {
    PrereqCheckRequest request = new PrereqCheckRequest("cluster", UpgradeType.NON_ROLLING);
    request.setTargetRepositoryVersion(m_repositoryVersion);
    PrerequisiteCheck check = new PrerequisiteCheck(CheckDescription.SECONDARY_JAVA_HOME, "cluster");
    m_check.perform(check, request);
    Assert.assertEquals(PrereqCheckStatus.PASS, check.getStatus());
    Assert.assertEquals(1, check.getFailedDetail().size());
    Assert.assertTrue(new Gson().toJson(check.getFailedDetail()).contains("secondary_java_major"));
    Mockito.verify(m_configuration, Mockito.never()).updateStackJavaHomes(Mockito.anyMap());
  }

  @Test
  public void testExpressUpgradeWithinRuntimeRangeDoesNotAdvertiseSelector() throws Exception {
    Mockito.when(runtimes.transitionRequirements(cluster, m_repositoryVersion)).thenReturn(null);
    PrereqCheckRequest request = new PrereqCheckRequest("cluster", UpgradeType.NON_ROLLING);
    request.setTargetRepositoryVersion(m_repositoryVersion);
    Assert.assertFalse(m_check.isApplicable(request));
    PrerequisiteCheck check = new PrerequisiteCheck(CheckDescription.SECONDARY_JAVA_HOME, "cluster");
    m_check.perform(check, request);
    Assert.assertTrue(check.getFailedDetail().isEmpty());
    Assert.assertEquals(PrereqCheckStatus.PASS, check.getStatus());
  }

  @Test
  public void testExpressCrossingAdvertisesSelectorButRollingKeepsExistingPrerequisite() throws Exception {
    PrereqCheckRequest request = new PrereqCheckRequest("cluster", UpgradeType.NON_ROLLING);
    request.setTargetRepositoryVersion(m_repositoryVersion);
    Assert.assertTrue(m_check.isApplicable(request));
    Mockito.when(runtimes.transitionRequirements(cluster, m_repositoryVersion)).thenReturn(null);
    Assert.assertFalse(m_check.isApplicable(request));
    Assert.assertTrue(m_check.isApplicable(request("1.3.2.0-36")));
  }

  private java.io.File jdk(String version) throws Exception {
    java.io.File home = java.nio.file.Files.createTempDirectory("jdk").toFile();
    home.deleteOnExit();
    if (version != null) {
      java.io.File release = new java.io.File(home, "release");
      java.nio.file.Files.write(release.toPath(),
          ("IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"" + version + "\"\nOS_NAME=\"Linux\"\n").getBytes("UTF-8"));
      release.deleteOnExit();
    }
    return home;
  }

  private PrerequisiteCheck rolling(String primaryJavaHome) throws Exception {
    JsonObject crossing = new JsonObject();
    crossing.addProperty("primary_java_major", 21);
    crossing.addProperty("primary_java_major_fallback", 17);
    crossing.addProperty("secondary_java_major", 21);
    Mockito.when(runtimes.transitionRequirements(cluster, m_repositoryVersion)).thenReturn(crossing);
    Mockito.when(m_configuration.getJavaHome()).thenReturn(primaryJavaHome);
    Mockito.when(m_configuration.getSecondaryJavaHome()).thenReturn("/usr/lib/jvm/java-21-openjdk");
    Mockito.when(m_repositoryVersion.getVersion()).thenReturn("1.3.2.0-72");
    PrereqCheckRequest request = new PrereqCheckRequest("cluster", UpgradeType.ROLLING);
    request.setTargetRepositoryVersion(m_repositoryVersion);
    PrerequisiteCheck check = new PrerequisiteCheck(CheckDescription.SECONDARY_JAVA_HOME, "cluster");
    m_check.perform(check, request);
    return check;
  }

  @Test
  public void testRollingUpgradePackRunsTheCheckWithoutExplicitEntry() {
    UpgradePack pack = Mockito.mock(UpgradePack.class);
    Mockito.when(pack.getType()).thenReturn(UpgradeType.ROLLING);
    Mockito.when(pack.getPrerequisiteChecks()).thenReturn(Collections.emptyList());
    UpgradeCheckRegistry registry = new UpgradeCheckRegistry();
    registry.register(m_check);

    Assert.assertEquals(Collections.singletonList(m_check), registry.getFilteredUpgradeChecks(pack));
  }

  @Test
  public void testRollingUpgradeRefusedWithJdk8PrimaryJavaHome() throws Exception {
    PrerequisiteCheck check = rolling(jdk("1.8.0_412").getAbsolutePath());
    Assert.assertEquals(PrereqCheckStatus.FAIL, check.getStatus());
    Assert.assertTrue(check.getFailReason(), check.getFailReason().contains("JDK 8"));
    Assert.assertTrue(check.getFailReason(), check.getFailReason().contains("Express"));
    Assert.assertTrue(check.getFailReason(), check.getFailReason().contains("1.3.2.0-72"));
  }

  @Test
  public void testRollingUpgradeRefusedWithJdk11PrimaryJavaHome() throws Exception {
    Assert.assertEquals(PrereqCheckStatus.FAIL, rolling(jdk("11.0.25").getAbsolutePath()).getStatus());
  }

  @Test
  public void testRollingUpgradeAllowedWithJdk17PrimaryJavaHome() throws Exception {
    Assert.assertEquals(PrereqCheckStatus.PASS, rolling(jdk("17.0.13").getAbsolutePath()).getStatus());
  }

  @Test
  public void testRollingUpgradeAllowedWithJdk21PrimaryJavaHome() throws Exception {
    Assert.assertEquals(PrereqCheckStatus.PASS, rolling(jdk("21.0.5").getAbsolutePath()).getStatus());
  }

  @Test
  public void testRollingUpgradeRefusedWhenThePrimaryJdkCannotBeRead() throws Exception {
    PrerequisiteCheck check = rolling(jdk(null).getAbsolutePath());
    Assert.assertEquals(PrereqCheckStatus.FAIL, check.getStatus());
    Assert.assertTrue(check.getFailReason(), check.getFailReason().contains("an unknown JDK"));
  }

  @Test
  public void testRollingUpgradeWithinTheRuntimeRangeIgnoresThePrimaryJdk() throws Exception {
    PrerequisiteCheck check = rolling(jdk("1.8.0_412").getAbsolutePath());
    Mockito.when(runtimes.transitionRequirements(cluster, m_repositoryVersion)).thenReturn(null);
    check = new PrerequisiteCheck(CheckDescription.SECONDARY_JAVA_HOME, "cluster");
    PrereqCheckRequest request = new PrereqCheckRequest("cluster", UpgradeType.ROLLING);
    request.setTargetRepositoryVersion(m_repositoryVersion);
    m_check.perform(check, request);
    Assert.assertEquals(PrereqCheckStatus.PASS, check.getStatus());
  }
}
