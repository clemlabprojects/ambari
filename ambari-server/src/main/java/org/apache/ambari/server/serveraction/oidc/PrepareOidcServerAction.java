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

import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.security.credential.Credential;
import org.apache.ambari.server.security.credential.PrincipalKeyCredential;
import org.apache.ambari.server.security.encryption.CredentialStoreService;
import org.apache.ambari.server.serveraction.AbstractServerAction;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

/**
 * Pre-flight validation before OIDC client provisioning begins.
 * <p>
 * Checks that {@code oidc-env} is present, the admin URL and realm are non-empty, and that
 * admin credentials exist in the credential store. A failure here aborts the provisioning
 * request before any provider API calls are made.
 * </p>
 */
public class PrepareOidcServerAction extends AbstractServerAction {

  private static final Logger LOG = LoggerFactory.getLogger(PrepareOidcServerAction.class);

  @Inject
  private AmbariManagementController controller;

  @Inject
  private ConfigHelper configHelper;

  @Inject
  private CredentialStoreService credentialStoreService;

  @Override
  public CommandReport execute(ConcurrentMap<String, Object> requestSharedDataContext)
      throws AmbariException, InterruptedException {

    String clusterName = getExecutionCommand().getClusterName();
    Cluster cluster = controller.getClusters().getCluster(clusterName);

    Map<String, Map<String, String>> configs =
        configHelper.calculateExistingConfigurations(controller, cluster, null, null);

    Map<String, String> oidcEnv = (configs == null) ? null : configs.get(ConfigureOidcServerAction.OIDC_ENV);
    if (oidcEnv == null) {
      String msg = "OIDC pre-flight validation failed: oidc-env configuration type is not present. " +
          "Install the OIDC service or create the oidc-env config type before running provisioning.";
      LOG.warn(msg);
      actionLog.writeStdErr(msg);
      return createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    String adminUrl = oidcEnv.get(ConfigureOidcServerAction.OIDC_ADMIN_URL);
    String realm = oidcEnv.get(ConfigureOidcServerAction.OIDC_REALM);
    if (StringUtils.isEmpty(adminUrl)) {
      String msg = "OIDC pre-flight validation failed: oidc_admin_url is not configured in oidc-env.";
      LOG.warn(msg);
      actionLog.writeStdErr(msg);
      return createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }
    if (StringUtils.isEmpty(realm)) {
      String msg = "OIDC pre-flight validation failed: oidc_realm is not configured in oidc-env.";
      LOG.warn(msg);
      actionLog.writeStdErr(msg);
      return createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    Credential credential = credentialStoreService.getCredential(
        clusterName, ConfigureOidcServerAction.OIDC_ADMIN_CREDENTIAL_ALIAS);
    if (!(credential instanceof PrincipalKeyCredential)) {
      String msg = "OIDC pre-flight validation failed: admin credentials are not stored under alias '" +
          ConfigureOidcServerAction.OIDC_ADMIN_CREDENTIAL_ALIAS +
          "'. Use the Manage OIDC Credentials action to store them before running provisioning.";
      LOG.warn(msg);
      actionLog.writeStdErr(msg);
      return createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    String msg = String.format(
        "OIDC pre-flight validation passed: adminUrl=%s, realm=%s, credentials present",
        adminUrl, realm);
    LOG.info(msg);
    actionLog.writeStdOut(msg);
    return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
  }
}
