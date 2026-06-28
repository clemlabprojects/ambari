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
import java.util.List;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.controller.PrereqCheckRequest;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.state.stack.PrereqCheckStatus;
import org.apache.ambari.server.state.stack.PrerequisiteCheck;
import org.apache.ambari.server.utils.VersionUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Singleton;

/**
 * ODP 1.3.2.0 introduces a dual-JDK runtime: Hadoop/YARN/HBase and most of the
 * stack run the primary JDK (Java 17), while Hive 4, NiFi 2, NiFi-Registry and
 * Polaris must run a secondary JDK (Java 21) - delivered via the
 * {@code secondary_java_home} selector and the Hive-private hadoop conf dir
 * ({@code hive_isolated_hadoop_conf}). The secondary JDK is read from the
 * Ambari server property {@code secondary.java.home}.
 * <p>
 * This pre-upgrade check fails an upgrade <b>into ODP 1.3.2.0 or later</b> when
 * {@code secondary.java.home} is not configured. The requirement is cluster-wide
 * and not conditioned on the secondary-JDK services being installed yet: an
 * operator may add Hive/NiFi/Polaris after the upgrade, at which point the
 * secondary JDK must already be provisioned. Failing early forces the operator
 * to install Java 21 and set the property before any service restarts onto the
 * new (Java-21) binaries.
 * <p>
 * Note: a server-side check can only confirm that the value is set; whether the
 * configured path actually points at a Java 21 runtime on every host is an
 * operator responsibility (documented in the upgrade prerequisites).
 */
@Singleton
@UpgradeCheck(group = UpgradeCheckGroup.CONFIGURATION_WARNING, order = 1.0f)
public class SecondaryJavaHomeCheck extends AbstractCheckDescriptor {

  private static final Logger LOG = LoggerFactory.getLogger(SecondaryJavaHomeCheck.class);

  /**
   * The first ODP version that runs a dual-JDK stack and therefore requires a
   * secondary JDK to be configured.
   */
  static final String SECONDARY_JAVA_HOME_MIN_VERSION = "1.3.2.0";

  /**
   * Default constructor.
   */
  public SecondaryJavaHomeCheck() {
    super(CheckDescription.SECONDARY_JAVA_HOME);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<CheckQualification> getQualifications() {
    return Collections.singletonList(new TargetVersionQualification());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void perform(PrerequisiteCheck prerequisiteCheck, PrereqCheckRequest request)
      throws AmbariException {
    // The secondary JDK is a single server-level property, not a cluster config.
    if (StringUtils.isNotBlank(config.getSecondaryJavaHome())) {
      return;
    }

    LOG.info("secondary.java.home is not set while upgrading to a dual-JDK ODP version (>= {})",
        SECONDARY_JAVA_HOME_MIN_VERSION);

    prerequisiteCheck.getFailedOn().add(request.getClusterName());
    prerequisiteCheck.setStatus(PrereqCheckStatus.FAIL);
    prerequisiteCheck.setFailReason(getFailReason(prerequisiteCheck, request));
  }

  /**
   * Restricts the check to upgrades whose target is ODP 1.3.2.0 or later - the
   * first version that splits the stack across a primary and a secondary JDK.
   */
  final class TargetVersionQualification implements CheckQualification {
    @Override
    public boolean isApplicable(PrereqCheckRequest request) throws AmbariException {
      RepositoryVersionEntity targetRepositoryVersion = request.getTargetRepositoryVersion();
      if (null == targetRepositoryVersion) {
        return false;
      }

      // Strip any build suffix, e.g. "1.3.2.0-25" -> "1.3.2.0".
      String targetVersion = StringUtils.substringBefore(targetRepositoryVersion.getVersion(), "-");
      return VersionUtils.compareVersions(targetVersion, SECONDARY_JAVA_HOME_MIN_VERSION) >= 0;
    }
  }
}
