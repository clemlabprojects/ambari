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
import java.util.TreeSet;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.controller.PrereqCheckRequest;
import org.apache.ambari.server.orm.dao.AlertsDAO;
import org.apache.ambari.server.orm.entities.AlertCurrentEntity;
import org.apache.ambari.server.state.AlertState;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.stack.PrereqCheckStatus;
import org.apache.ambari.server.state.stack.PrerequisiteCheck;

import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

/**
 * ODP (clemlab): fails until the optimizing of Amoro is paused. The optimizing processes Amoro persists hold
 * Java-serialized Iceberg objects which the Iceberg of the target stack may not read: the operator runs the
 * AMORO service action Pause Optimizing, which leaves a flag the amoro_optimizing_paused alert reports
 * (WARNING while paused) on each AMORO_SERVER host, then re-runs the checks.
 */
@Singleton
@UpgradeCheck(group = UpgradeCheckGroup.DEFAULT)
public class AmoroOptimizingPausedCheck extends AbstractCheckDescriptor {

  static final String SERVICE = "AMORO";
  static final String COMPONENT = "AMORO_SERVER";
  static final String ALERT = "amoro_optimizing_paused";

  @Inject
  Provider<AlertsDAO> alertsDAOProvider;

  public AmoroOptimizingPausedCheck() {
    super(CheckDescription.AMORO_OPTIMIZING_PAUSED);
  }

  @Override
  public Set<String> getApplicableServices() {
    return Sets.newHashSet(SERVICE);
  }

  @Override
  public void perform(PrerequisiteCheck prerequisiteCheck, PrereqCheckRequest request) throws AmbariException {
    Cluster cluster = clustersProvider.get().getCluster(request.getClusterName());
    Service service = cluster.getServices().get(SERVICE);
    ServiceComponent server = service == null ? null : service.getServiceComponents().get(COMPONENT);
    if (server == null) {
      return;
    }
    AlertsDAO alertsDAO = alertsDAOProvider.get();
    TreeSet<String> notPaused = new TreeSet<>();
    for (String host : server.getServiceComponentHosts().keySet()) {
      AlertCurrentEntity alert = alertsDAO.findCurrentByHostAndName(cluster.getClusterId(), host, ALERT);
      if (alert == null || alert.getAlertHistory().getAlertState() != AlertState.WARNING) {
        notPaused.add(host);
      }
    }
    if (!notPaused.isEmpty()) {
      prerequisiteCheck.getFailedOn().addAll(notPaused);
      prerequisiteCheck.setStatus(PrereqCheckStatus.FAIL);
      prerequisiteCheck.setFailReason(String.format(getFailReason(prerequisiteCheck, request),
          String.join(", ", notPaused)));
    }
  }
}
