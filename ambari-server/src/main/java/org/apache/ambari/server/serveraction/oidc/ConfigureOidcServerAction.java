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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.security.credential.Credential;
import org.apache.ambari.server.security.credential.GenericKeyCredential;
import org.apache.ambari.server.security.credential.PrincipalKeyCredential;
import org.apache.ambari.server.security.encryption.CredentialStoreService;
import org.apache.ambari.server.security.encryption.CredentialStoreType;
import org.apache.ambari.server.serveraction.AbstractServerAction;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.kerberos.VariableReplacementHelper;
import org.apache.ambari.server.state.oidc.OidcClientDescriptor;
import org.apache.ambari.server.state.oidc.OidcDescriptor;
import org.apache.ambari.server.state.oidc.OidcServiceDescriptor;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

public class ConfigureOidcServerAction extends AbstractServerAction {
  private static final Logger LOG = LoggerFactory.getLogger(ConfigureOidcServerAction.class);

  public static final String AUTHENTICATED_USER_NAME = "authenticated_user_name";
  public static final String UPDATE_CONFIGURATION_NOTE = "update_configuration_note";

  public static final String OIDC_ENV = "oidc-env";
  public static final String OIDC_ADMIN_CREDENTIAL_ALIAS = "oidc.admin.credential";
  public static final String OIDC_PROVIDER = "oidc_provider";
  public static final String OIDC_ADMIN_URL = "oidc_admin_url";
  public static final String OIDC_ADMIN_REALM = "oidc_admin_realm";
  public static final String OIDC_REALM = "oidc_realm";
  public static final String OIDC_ADMIN_CLIENT_ID = "oidc_admin_client_id";
  public static final String OIDC_ADMIN_CLIENT_SECRET = "oidc_admin_client_secret";
  public static final String OIDC_VERIFY_TLS = "oidc_verify_tls";

  private static final String DEFAULT_ADMIN_REALM = "master";
  private static final String DEFAULT_ADMIN_CLIENT_ID = "admin-cli";

  @Inject
  private AmbariManagementController controller;

  @Inject
  private ConfigHelper configHelper;

  @Inject
  private CredentialStoreService credentialStoreService;

  @Inject
  private OidcOperationHandlerFactory oidcOperationHandlerFactory;

  @Inject
  private VariableReplacementHelper variableReplacementHelper;

  @Override
  public CommandReport execute(ConcurrentMap<String, Object> requestSharedDataContext)
    throws AmbariException, InterruptedException {

    String clusterName = getExecutionCommand().getClusterName();
    Cluster cluster = controller.getClusters().getCluster(clusterName);

    Map<String, Map<String, String>> existingConfigurations =
      configHelper.calculateExistingConfigurations(controller, cluster, null, null);

    Map<String, String> oidcEnv = (existingConfigurations == null) ? null : existingConfigurations.get(OIDC_ENV);
    if (oidcEnv == null) {
      actionLog.writeStdOut("OIDC configuration not found; skipping OIDC provisioning.");
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    String provider = defaultIfEmpty(oidcEnv.get(OIDC_PROVIDER), "keycloak");
    String adminUrl = oidcEnv.get(OIDC_ADMIN_URL);
    String realm = oidcEnv.get(OIDC_REALM);
    if (StringUtils.isEmpty(adminUrl) || StringUtils.isEmpty(realm)) {
      actionLog.writeStdErr("OIDC admin URL or realm is not configured; skipping OIDC provisioning.");
      return createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    PrincipalKeyCredential adminCredential = getAdminCredential(clusterName);
    if (adminCredential == null) {
      String message = "Missing OIDC administrator credentials. " +
        "Store credentials using the /api/v1/clusters/:clusterName/credentials/oidc.admin.credential endpoint.";
      actionLog.writeStdErr(message);
      return createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    StackId stackId = cluster.getDesiredStackVersion();
    OidcDescriptor oidcDescriptor = controller.getAmbariMetaInfo()
      .getOidcDescriptor(stackId.getStackName(), stackId.getStackVersion());

    if (oidcDescriptor == null || oidcDescriptor.getServices() == null || oidcDescriptor.getServices().isEmpty()) {
      actionLog.writeStdOut("No OIDC descriptor content found; skipping OIDC provisioning.");
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    Map<String, Service> installedServices = cluster.getServices();
    if (installedServices == null || installedServices.isEmpty()) {
      actionLog.writeStdOut("No installed services found; skipping OIDC provisioning.");
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    String authenticatedUserName = getCommandParameterValue(getCommandParameters(), AUTHENTICATED_USER_NAME);
    if (authenticatedUserName == null) {
      authenticatedUserName = controller.getAuthName();
    }

    HashMap<String, Map<String, String>> propertiesToSet = new HashMap<>();
    HashMap<String, Collection<String>> propertiesToRemove = new HashMap<>();
    Set<String> configTypes = new HashSet<>();

    OidcProviderConfiguration providerConfiguration = new OidcProviderConfiguration(
      provider,
      adminUrl,
      realm,
      defaultIfEmpty(oidcEnv.get(OIDC_ADMIN_REALM), DEFAULT_ADMIN_REALM),
      defaultIfEmpty(oidcEnv.get(OIDC_ADMIN_CLIENT_ID), DEFAULT_ADMIN_CLIENT_ID),
      oidcEnv.get(OIDC_ADMIN_CLIENT_SECRET),
      !"false".equalsIgnoreCase(defaultIfEmpty(oidcEnv.get(OIDC_VERIFY_TLS), "true"))
    );

    try (OidcOperationHandler handler = oidcOperationHandlerFactory.getHandler(provider)) {
      handler.open(adminCredential, providerConfiguration);

      for (OidcServiceDescriptor serviceDescriptor : oidcDescriptor.getServices().values()) {
        if (serviceDescriptor == null || serviceDescriptor.getName() == null) {
          continue;
        }

        if (!installedServices.containsKey(serviceDescriptor.getName())) {
          continue;
        }

        if (!isServiceOidcEnabled(serviceDescriptor, existingConfigurations)) {
          continue;
        }

        if (serviceDescriptor.getConfigurations() != null) {
          applyConfigurations(serviceDescriptor.getConfigurations(), propertiesToSet, configTypes,
            buildReplacementMap(cluster, existingConfigurations, providerConfiguration, null, null));
        }

        if (serviceDescriptor.getClients() == null) {
          continue;
        }

        for (OidcClientDescriptor clientDescriptor : serviceDescriptor.getClients()) {
          if (clientDescriptor == null) {
            continue;
          }

          String resolvedClientId = resolveClientId(cluster, clientDescriptor, existingConfigurations, providerConfiguration);
          if (resolvedClientId == null) {
            continue;
          }

          String resolvedRealm = resolveRealm(cluster, clientDescriptor, providerConfiguration, existingConfigurations);
          OidcClientDescriptor resolvedDescriptor = new OidcClientDescriptor(
            clientDescriptor.getName(),
            resolvedClientId,
            resolvedRealm,
            clientDescriptor.isPublicClient(),
            clientDescriptor.isServiceAccountsEnabled(),
            clientDescriptor.isDirectAccessGrantsEnabled(),
            clientDescriptor.isStandardFlowEnabled(),
            clientDescriptor.getRedirectUris(),
            clientDescriptor.getAttributes(),
            clientDescriptor.getConfigurations(),
            clientDescriptor.getSecretAlias());

          OidcClientResult result = handler.ensureClient(resolvedDescriptor, resolvedRealm);
          if (result == null) {
            continue;
          }

          if (!StringUtils.isEmpty(clientDescriptor.getSecretAlias()) && result.getSecret() != null) {
            try {
              credentialStoreService.setCredential(clusterName, clientDescriptor.getSecretAlias(),
                new GenericKeyCredential(result.getSecret().toCharArray()), CredentialStoreType.PERSISTED);
            } catch (AmbariException e) {
              throw new OidcOperationException("Failed to store OIDC client secret in credential store", e);
            }
          }

          Map<String, Map<String, String>> replacements = buildReplacementMap(
            cluster, existingConfigurations, providerConfiguration, result, resolvedRealm);
          if (clientDescriptor.getConfigurations() != null) {
            applyConfigurations(clientDescriptor.getConfigurations(), propertiesToSet, configTypes, replacements);
          }
        }
      }
    } catch (OidcOperationException e) {
      String message = "OIDC provisioning failed";
      LOG.error(message, e);
      actionLog.writeStdErr(message + ": " + e.getMessage());
      return createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    if (!configTypes.isEmpty()) {
      String configNote = getCommandParameterValue(getCommandParameters(), UPDATE_CONFIGURATION_NOTE);
      if (StringUtils.isEmpty(configNote)) {
        configNote = "Configuring OIDC";
      }

      configHelper.updateBulkConfigType(cluster, cluster.getDesiredStackVersion(), controller,
        configTypes, propertiesToSet, propertiesToRemove,
        authenticatedUserName, configNote);
    }

    return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
  }

  private PrincipalKeyCredential getAdminCredential(String clusterName) throws AmbariException {
    Credential credential = credentialStoreService.getCredential(clusterName, OIDC_ADMIN_CREDENTIAL_ALIAS);
    if (credential instanceof PrincipalKeyCredential) {
      return (PrincipalKeyCredential) credential;
    }
    return null;
  }

  private boolean isServiceOidcEnabled(OidcServiceDescriptor serviceDescriptor,
                                       Map<String, Map<String, String>> configurations) {
    if (serviceDescriptor.isEnabled() != null) {
      return serviceDescriptor.isEnabled();
    }

    if ("POLARIS".equalsIgnoreCase(serviceDescriptor.getName())) {
      Map<String, String> appProps = null;
      if (configurations != null) {
        appProps = configurations.get("polaris-application-properties");
        if (appProps == null) {
          appProps = configurations.get("application-properties");
        }
      }
      if (appProps == null) {
        return false;
      }

      if (isExternalOrMixed(appProps.get("polaris.authentication.type"))) {
        return true;
      }

      for (Map.Entry<String, String> entry : appProps.entrySet()) {
        String key = entry.getKey();
        if (key == null || !key.startsWith("polaris.authentication.") || !key.endsWith(".type")) {
          continue;
        }
        if (key.equals("polaris.authentication.authenticator.type")
          || key.equals("polaris.authentication.active-roles-provider.type")) {
          continue;
        }
        if (isExternalOrMixed(entry.getValue())) {
          return true;
        }
      }

      return false;
    }

    return true;
  }

  private boolean isExternalOrMixed(String value) {
    if (value == null) {
      return false;
    }
    String normalized = value.trim().toLowerCase();
    return "external".equals(normalized) || "mixed".equals(normalized);
  }

  private String resolveClientId(Cluster cluster, OidcClientDescriptor clientDescriptor,
                                 Map<String, Map<String, String>> configurations,
                                 OidcProviderConfiguration providerConfiguration) throws AmbariException {
    if (clientDescriptor.getClientId() == null) {
      return null;
    }
    return variableReplacementHelper.replaceVariables(clientDescriptor.getClientId(),
      buildReplacementMap(cluster, configurations, providerConfiguration, null, null));
  }

  private String resolveRealm(Cluster cluster, OidcClientDescriptor clientDescriptor, OidcProviderConfiguration configuration,
                              Map<String, Map<String, String>> configurations) throws AmbariException {
    String realm = clientDescriptor.getRealm();
    if (realm == null) {
      return configuration.getRealm();
    }
    return variableReplacementHelper.replaceVariables(realm, buildReplacementMap(cluster, configurations, configuration, null, null));
  }

  private Map<String, Map<String, String>> buildReplacementMap(Cluster cluster,
                                                               Map<String, Map<String, String>> configurations,
                                                               OidcProviderConfiguration providerConfiguration,
                                                               OidcClientResult clientResult,
                                                               String realmOverride) {
    Map<String, Map<String, String>> replacements = new HashMap<>();
    if (configurations != null) {
      replacements.putAll(configurations);
    }

    Map<String, String> globals = new HashMap<>();
    if (cluster != null) {
      globals.put("cluster_name", cluster.getClusterName());
    }
    if (providerConfiguration != null) {
      String realm = (realmOverride == null) ? providerConfiguration.getRealm() : realmOverride;
      globals.put("oidc_realm", realm);
      globals.put("auth_server_url", buildAuthServerUrl(providerConfiguration.getBaseUrl(), realm));
    }
    if (clientResult != null) {
      globals.put("client_id", clientResult.getClientId());
      globals.put("client_secret", clientResult.getSecret());
    }
    replacements.put("", globals);

    return replacements;
  }

  private void applyConfigurations(Collection<Map<String, Map<String, String>>> configurations,
                                   Map<String, Map<String, String>> propertiesToSet,
                                   Set<String> configTypes,
                                   Map<String, Map<String, String>> replacements) throws AmbariException {
    if (configurations == null) {
      return;
    }

    for (Map<String, Map<String, String>> configuration : configurations) {
      if (configuration == null) {
        continue;
      }

      for (Map.Entry<String, Map<String, String>> entry : configuration.entrySet()) {
        String configType = entry.getKey();
        Map<String, String> properties = entry.getValue();
        if (configType == null || properties == null) {
          continue;
        }

        configTypes.add(configType);
        Map<String, String> target = propertiesToSet.computeIfAbsent(configType, k -> new HashMap<>());

        for (Map.Entry<String, String> property : properties.entrySet()) {
          String value = property.getValue();
          if (value != null) {
            value = variableReplacementHelper.replaceVariables(value, replacements);
          }
          target.put(property.getKey(), value);
        }
      }
    }
  }

  private String buildAuthServerUrl(String baseUrl, String realm) {
    if (StringUtils.isEmpty(baseUrl) || StringUtils.isEmpty(realm)) {
      return null;
    }
    String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    return trimmed + "/realms/" + realm;
  }

  private String defaultIfEmpty(String value, String defaultValue) {
    return StringUtils.isEmpty(value) ? defaultValue : value;
  }

  private static String getCommandParameterValue(Map<String, String> commandParameters, String propertyName) {
    return ((commandParameters == null) || (propertyName == null)) ? null : commandParameters.get(propertyName);
  }
}
