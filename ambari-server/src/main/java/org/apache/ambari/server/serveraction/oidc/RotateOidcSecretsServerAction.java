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

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.orm.dao.OidcClientDAO;
import org.apache.ambari.server.orm.entities.OidcClientEntity;
import org.apache.ambari.server.security.credential.Credential;
import org.apache.ambari.server.security.credential.GenericKeyCredential;
import org.apache.ambari.server.security.credential.PrincipalKeyCredential;
import org.apache.ambari.server.security.encryption.CredentialStoreService;
import org.apache.ambari.server.security.encryption.CredentialStoreType;
import org.apache.ambari.server.serveraction.AbstractServerAction;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.oidc.OidcClientDescriptor;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

/**
 * Rotates OIDC client secrets for all provisioned clients tracked in the Ambari database.
 * <p>
 * For each tracked client, this action calls the provider's {@code ensureClient} operation which
 * triggers secret regeneration where the provider supports it (e.g. Keycloak's regenerateSecret
 * API), then updates the Ambari credential store with the new secret value.
 * </p>
 */
public class RotateOidcSecretsServerAction extends AbstractServerAction {

  private static final Logger LOG = LoggerFactory.getLogger(RotateOidcSecretsServerAction.class);

  @Inject
  private AmbariManagementController controller;

  @Inject
  private ConfigHelper configHelper;

  @Inject
  private CredentialStoreService credentialStoreService;

  @Inject
  private OidcOperationHandlerFactory oidcOperationHandlerFactory;

  @Inject
  private OidcClientDAO oidcClientDAO;

  @Override
  public CommandReport execute(ConcurrentMap<String, Object> requestSharedDataContext)
      throws AmbariException, InterruptedException {

    String clusterName = getExecutionCommand().getClusterName();
    Cluster cluster = controller.getClusters().getCluster(clusterName);

    Map<String, Map<String, String>> configs =
        configHelper.calculateExistingConfigurations(controller, cluster, null, null);
    Map<String, String> oidcEnv = (configs == null) ? null : configs.get(ConfigureOidcServerAction.OIDC_ENV);

    if (oidcEnv == null) {
      String msg = "Secret rotation skipped: oidc-env configuration is not present in cluster " + clusterName;
      LOG.warn(msg);
      actionLog.writeStdErr(msg);
      return createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    String provider = StringUtils.defaultIfEmpty(oidcEnv.get(ConfigureOidcServerAction.OIDC_PROVIDER), "keycloak");
    String adminUrl = oidcEnv.get(ConfigureOidcServerAction.OIDC_ADMIN_URL);
    String realm = oidcEnv.get(ConfigureOidcServerAction.OIDC_REALM);
    if (StringUtils.isEmpty(adminUrl) || StringUtils.isEmpty(realm)) {
      String msg = "Secret rotation failed: oidc_admin_url or oidc_realm missing in oidc-env.";
      LOG.warn(msg);
      actionLog.writeStdErr(msg);
      return createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    Credential rawCredential = credentialStoreService.getCredential(
        clusterName, ConfigureOidcServerAction.OIDC_ADMIN_CREDENTIAL_ALIAS);
    if (!(rawCredential instanceof PrincipalKeyCredential)) {
      String msg = "Secret rotation failed: admin credentials not found under alias '" +
          ConfigureOidcServerAction.OIDC_ADMIN_CREDENTIAL_ALIAS + "'.";
      LOG.warn(msg);
      actionLog.writeStdErr(msg);
      return createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }
    PrincipalKeyCredential adminCredential = (PrincipalKeyCredential) rawCredential;

    OidcProviderConfiguration providerConfig = new OidcProviderConfiguration(
        provider,
        adminUrl,
        realm,
        StringUtils.defaultIfEmpty(oidcEnv.get(ConfigureOidcServerAction.OIDC_ADMIN_REALM), "master"),
        StringUtils.defaultIfEmpty(oidcEnv.get(ConfigureOidcServerAction.OIDC_ADMIN_CLIENT_ID), "admin-cli"),
        oidcEnv.get(ConfigureOidcServerAction.OIDC_ADMIN_CLIENT_SECRET),
        !"false".equalsIgnoreCase(StringUtils.defaultIfEmpty(oidcEnv.get(ConfigureOidcServerAction.OIDC_VERIFY_TLS), "true"))
    );

    List<OidcClientEntity> trackedClients = oidcClientDAO.findByCluster(cluster.getClusterId());
    if (trackedClients == null || trackedClients.isEmpty()) {
      String msg = "No tracked OIDC clients found for cluster " + clusterName + "; nothing to rotate.";
      LOG.info(msg);
      actionLog.writeStdOut(msg);
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    int rotated = 0;
    int failed = 0;

    try (OidcOperationHandler handler = oidcOperationHandlerFactory.getHandler(provider)) {
      handler.open(adminCredential, providerConfig);

      for (OidcClientEntity tracked : trackedClients) {
        String clientId = tracked.getClientId();
        String clientRealm = tracked.getRealm();
        String secretAlias = tracked.getSecretAlias();

        actionLog.writeStdOut(String.format(
            "Rotating secret for service=%s, clientId=%s, realm=%s",
            tracked.getServiceName(), clientId, clientRealm));

        OidcClientDescriptor descriptor = new OidcClientDescriptor(
            tracked.getClientName(), clientId, clientRealm,
            false, false, false, false,
            null, null, null, secretAlias, null);

        try {
          OidcClientResult result = handler.ensureClient(descriptor, clientRealm);
          if (result != null && !StringUtils.isEmpty(secretAlias) && result.getSecret() != null) {
            credentialStoreService.setCredential(clusterName, secretAlias,
                new GenericKeyCredential(result.getSecret().toCharArray()),
                CredentialStoreType.PERSISTED);
            actionLog.writeStdOut(String.format(
                "Updated credential store for alias=%s (clientId=%s)", secretAlias, clientId));
          }
          tracked.setInternalId(result != null ? result.getInternalId() : tracked.getInternalId());
          tracked.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
          oidcClientDAO.merge(tracked);
          rotated++;
        } catch (OidcOperationException e) {
          LOG.warn("Failed to rotate secret for clientId={}: {}", clientId, e.getMessage());
          actionLog.writeStdErr(String.format(
              "Failed to rotate secret for clientId=%s: %s", clientId, e.getMessage()));
          failed++;
        }
      }
    } catch (OidcOperationException e) {
      String msg = "Secret rotation failed: unable to open OIDC handler: " + e.getMessage();
      LOG.error(msg, e);
      actionLog.writeStdErr(msg);
      return createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    String summary = String.format(
        "OIDC secret rotation complete: %d rotated, %d failed.", rotated, failed);
    LOG.info(summary);
    actionLog.writeStdOut(summary);

    HostRoleStatus status = (failed > 0) ? HostRoleStatus.FAILED : HostRoleStatus.COMPLETED;
    int exitCode = (failed > 0) ? 1 : 0;
    return createCommandReport(exitCode, status, "{}", actionLog.getStdOut(), actionLog.getStdErr());
  }
}
