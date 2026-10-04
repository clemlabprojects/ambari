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

import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.agent.stomp.MetadataHolder;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.AmbariManagementControllerImpl;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.State;
import org.apache.ambari.server.state.UpgradeContext;
import org.apache.ambari.server.state.UpgradeJavaRuntime;

import com.google.inject.Inject;

/** Activates saved runtime paths only after the express upgrade has stopped services. */
public class SwitchJavaRuntimeAction extends AbstractUpgradeServerAction {
  @Inject private UpgradeJavaRuntime runtimes;
  @Inject private Configuration configuration;
  @Inject private MetadataHolder metadata;
  @Inject private AmbariManagementControllerImpl controller;

  @Override
  public CommandReport execute(ConcurrentMap<String, Object> shared) throws AmbariException, InterruptedException {
    UpgradeJavaRuntime.Plan plan = getGson().fromJson(
        getExecutionCommand().getCommandParams().get(UpgradeJavaRuntime.PLAN), UpgradeJavaRuntime.Plan.class);
    Cluster cluster = getClusters().getCluster(getExecutionCommand().getClusterName());
    UpgradeContext context = getUpgradeContext(cluster);
    try {
      for (String service : context.getSupportedServices()) {
        for (ServiceComponent component : cluster.getService(service).getServiceComponents().values()) {
          // Ambari-managed services such as Metrics are not part of the ODP
          // binary upgrade and may legitimately remain running.
          if (!component.isClientComponent() && component.isVersionAdvertised()) {
            for (ServiceComponentHost host : component.getServiceComponentHosts().values()) {
              if (host.getState() != State.INSTALLED && host.getState() != State.INIT) {
                throw new AmbariException("Stop " + service + "/" + component.getName()
                    + " on " + host.getHostName() + " before switching stack JDKs");
              }
            }
          }
        }
      }
      if (!plan.restore) {
        runtimes.validate(cluster, plan, false);
      }
      Map<String, String> current = runtimes.currentHomes();
      if (!current.equals(plan.before) && !current.equals(plan.after)) {
        throw new AmbariException("Stack Java homes changed after planning. Restore the planned paths before retrying.");
      }
      Map<String, String> selected = plan.restore ? plan.before : plan.after;
      configuration.updateStackJavaHomes(selected);
      // Publish synchronously before allowing target-version stages to start.
      metadata.updateData(controller.getClustersMetadata());
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}",
          "Stack JDK paths persisted and published to agents: " + selected
              + ". Ambari Server's own runtime is unchanged.", "");
    } catch (Exception e) {
      return createCommandReport(1, HostRoleStatus.FAILED, "{}", "", e.getMessage());
    }
  }
}
