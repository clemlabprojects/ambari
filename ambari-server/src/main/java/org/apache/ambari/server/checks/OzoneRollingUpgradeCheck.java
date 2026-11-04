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

import java.util.Set;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.controller.PrereqCheckRequest;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.stack.PrereqCheckStatus;
import org.apache.ambari.server.state.stack.PrerequisiteCheck;
import org.apache.ambari.server.state.stack.upgrade.UpgradeType;

import com.google.common.collect.Sets;
import com.google.inject.Singleton;

/**
 * ODP (clemlab): fails a rolling upgrade of a cluster with Ozone. Apache Ozone only supports non-rolling
 * upgrades (all components stopped, the Ozone Managers restarted with --upgrade, then finalized); its rolling
 * "Zero Downtime Upgrade" (HDDS-14496) is unreleased and will only apply to clusters already on a ZDU-ready
 * Ozone. The operator chooses the Express upgrade. Listed in the rolling upgrade pack only.
 */
@Singleton
@UpgradeCheck(group = UpgradeCheckGroup.DEFAULT)
public class OzoneRollingUpgradeCheck extends AbstractCheckDescriptor {

  static final String SERVICE = "OZONE";

  public OzoneRollingUpgradeCheck() {
    super(CheckDescription.OZONE_ROLLING_UPGRADE);
  }

  @Override
  public Set<String> getApplicableServices() {
    return Sets.newHashSet(SERVICE);
  }

  @Override
  public void perform(PrerequisiteCheck prerequisiteCheck, PrereqCheckRequest request) throws AmbariException {
    if (request.getUpgradeType() != null && request.getUpgradeType() != UpgradeType.ROLLING) {
      return;
    }
    Cluster cluster = clustersProvider.get().getCluster(request.getClusterName());
    if (!cluster.getServices().containsKey(SERVICE)) {
      return;
    }
    prerequisiteCheck.getFailedOn().add(SERVICE);
    prerequisiteCheck.setStatus(PrereqCheckStatus.FAIL);
    prerequisiteCheck.setFailReason(getFailReason(prerequisiteCheck, request));
  }
}
