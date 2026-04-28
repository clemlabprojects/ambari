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

package org.apache.ambari.server.serveraction.oidc;

import java.util.List;
import java.util.concurrent.ConcurrentMap;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.orm.dao.OidcClientDAO;
import org.apache.ambari.server.orm.entities.OidcClientEntity;
import org.apache.ambari.server.serveraction.AbstractServerAction;
import org.apache.ambari.server.state.Cluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

/**
 * Post-provisioning finalization action.
 * <p>
 * Logs a summary of OIDC clients recorded in the Ambari database for this cluster and
 * marks the provisioning operation as complete. This is always the last stage in an OIDC
 * provisioning request.
 * </p>
 */
public class FinalizeOidcServerAction extends AbstractServerAction {

  private static final Logger LOG = LoggerFactory.getLogger(FinalizeOidcServerAction.class);

  @Inject
  private AmbariManagementController controller;

  @Inject
  private OidcClientDAO oidcClientDAO;

  @Override
  public CommandReport execute(ConcurrentMap<String, Object> requestSharedDataContext)
      throws AmbariException, InterruptedException {

    String clusterName = getExecutionCommand().getClusterName();
    Cluster cluster = controller.getClusters().getCluster(clusterName);

    List<OidcClientEntity> clients = oidcClientDAO.findByCluster(cluster.getClusterId());
    int count = (clients == null) ? 0 : clients.size();

    LOG.info("OIDC provisioning finalized for cluster {}. {} OIDC client(s) tracked in Ambari DB.",
        clusterName, count);
    actionLog.writeStdOut(String.format(
        "OIDC provisioning complete. %d OIDC client(s) are tracked in Ambari for cluster %s.",
        count, clusterName));

    if (clients != null) {
      for (OidcClientEntity client : clients) {
        actionLog.writeStdOut(String.format(
            "  service=%s, descriptor=%s, clientId=%s, realm=%s",
            client.getServiceName(), client.getClientName(),
            client.getClientId(), client.getRealm()));
      }
    }

    return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
  }
}
