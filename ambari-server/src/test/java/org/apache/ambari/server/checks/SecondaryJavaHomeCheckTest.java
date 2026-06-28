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

import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.PrereqCheckRequest;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.RepositoryType;
import org.apache.ambari.server.state.stack.PrereqCheckStatus;
import org.apache.ambari.server.state.stack.PrerequisiteCheck;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import com.google.inject.Provider;

/**
 * Tests {@link SecondaryJavaHomeCheck}.
 */
@RunWith(MockitoJUnitRunner.class)
public class SecondaryJavaHomeCheckTest {

  private final Clusters m_clusters = Mockito.mock(Clusters.class);
  private final SecondaryJavaHomeCheck m_check = new SecondaryJavaHomeCheck();
  private Configuration m_configuration;

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

    // STANDARD repo so the orchestration qualification applies.
    Mockito.when(m_repositoryVersion.getType()).thenReturn(RepositoryType.STANDARD);
  }

  private PrereqCheckRequest request(String targetVersion) {
    Mockito.when(m_repositoryVersion.getVersion()).thenReturn(targetVersion);
    PrereqCheckRequest request = new PrereqCheckRequest("cluster");
    request.setTargetRepositoryVersion(m_repositoryVersion);
    return request;
  }

  /**
   * Not applicable when upgrading to a version older than 1.3.2.0.
   */
  @Test
  public void testNotApplicableWhenTargetBelowMinVersion() throws Exception {
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
}
