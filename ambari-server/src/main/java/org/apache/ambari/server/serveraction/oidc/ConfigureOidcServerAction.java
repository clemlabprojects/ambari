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

/**
 * Server action responsible for provisioning OIDC clients and applying OIDC-driven
 * service configuration updates for a cluster.
 * <p>
 * This action is intentionally descriptor-driven (via {@code oidc.json}) and does not
 * hardcode service-specific behavior. The execution flow is:
 * </p>
 * <ol>
 *   <li>Load effective cluster configurations and validate {@code oidc-env}.</li>
 *   <li>Load the stack OIDC descriptor for the desired stack version.</li>
 *   <li>Filter descriptor services to installed services and descriptor-enabled entries.</li>
 *   <li>Provision declared OIDC clients in the configured provider.</li>
 *   <li>Store generated client secrets in the Ambari credential store when requested.</li>
 *   <li>Apply descriptor configuration overlays with variable replacement.</li>
 * </ol>
 * <p>
 * The action mirrors Kerberos descriptor-oriented design: service metadata declares
 * desired provisioning behavior, while this action orchestrates provider calls and
 * config updates in a generic way.
 * </p>
 */
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

  /**
   * Executes OIDC provisioning for the target cluster.
   *
   * @param requestSharedDataContext shared request context (not used by this action)
   * @return command report with success/failure details
   * @throws AmbariException      if Ambari state cannot be resolved
   * @throws InterruptedException if execution is interrupted
   */
  @Override
  public CommandReport execute(ConcurrentMap<String, Object> requestSharedDataContext)
    throws AmbariException, InterruptedException {

    String clusterName = getExecutionCommand().getClusterName();
    Cluster cluster = controller.getClusters().getCluster(clusterName);
    LOG.info("Starting OIDC provisioning for cluster {}", clusterName);

    // Calculate the effective configuration map so variable replacement and descriptor
    // enablement logic operate on current desired values.
    Map<String, Map<String, String>> existingConfigurations =
      configHelper.calculateExistingConfigurations(controller, cluster, null, null);
    LOG.debug("Loaded {} effective config type(s) for OIDC provisioning in cluster {}",
      existingConfigurations == null ? 0 : existingConfigurations.size(), clusterName);

    Map<String, String> oidcEnv = (existingConfigurations == null) ? null : existingConfigurations.get(OIDC_ENV);
    if (oidcEnv == null) {
      LOG.info("OIDC provisioning skipped for cluster {} because {} is not present", clusterName, OIDC_ENV);
      actionLog.writeStdOut("OIDC configuration not found; skipping OIDC provisioning.");
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    String provider = defaultIfEmpty(oidcEnv.get(OIDC_PROVIDER), "keycloak");
    String adminUrl = oidcEnv.get(OIDC_ADMIN_URL);
    String realm = oidcEnv.get(OIDC_REALM);
    if (StringUtils.isEmpty(adminUrl) || StringUtils.isEmpty(realm)) {
      LOG.warn("OIDC provisioning failed for cluster {}: missing admin URL or realm in {}",
        clusterName, OIDC_ENV);
      actionLog.writeStdErr("OIDC admin URL or realm is not configured; skipping OIDC provisioning.");
      return createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }
    LOG.info("Using OIDC provider '{}' for cluster {} and target realm '{}'", provider, clusterName, realm);

    // Administrator credential is mandatory to call provider admin APIs.
    PrincipalKeyCredential adminCredential = getAdminCredential(clusterName);
    if (adminCredential == null) {
      String message = "Missing OIDC administrator credentials. " +
        "Store credentials using the /api/v1/clusters/:clusterName/credentials/oidc.admin.credential endpoint.";
      LOG.warn("OIDC provisioning failed for cluster {}: admin credential alias '{}' is not available",
        clusterName, OIDC_ADMIN_CREDENTIAL_ALIAS);
      actionLog.writeStdErr(message);
      return createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    // Load descriptor that defines OIDC service/client/config semantics for this stack version.
    StackId stackId = cluster.getDesiredStackVersion();
    OidcDescriptor oidcDescriptor = controller.getAmbariMetaInfo()
      .getOidcDescriptor(stackId.getStackName(), stackId.getStackVersion());

    if (oidcDescriptor == null || oidcDescriptor.getServices() == null || oidcDescriptor.getServices().isEmpty()) {
      LOG.info("OIDC provisioning skipped for cluster {} because descriptor has no services for stack {}-{}",
        clusterName, stackId.getStackName(), stackId.getStackVersion());
      actionLog.writeStdOut("No OIDC descriptor content found; skipping OIDC provisioning.");
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }
    LOG.debug("OIDC descriptor contains {} service definition(s) for stack {}-{}",
      oidcDescriptor.getServices().size(), stackId.getStackName(), stackId.getStackVersion());

    Map<String, Service> installedServices = cluster.getServices();
    if (installedServices == null || installedServices.isEmpty()) {
      LOG.info("OIDC provisioning skipped for cluster {} because there are no installed services", clusterName);
      actionLog.writeStdOut("No installed services found; skipping OIDC provisioning.");
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }
    LOG.debug("Cluster {} has {} installed service(s)", clusterName, installedServices.size());

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
    LOG.debug("OIDC provider configuration prepared (provider={}, verifyTls={}, adminRealm={}, adminClientId={})",
      providerConfiguration.getProvider(), providerConfiguration.isVerifyTls(),
      providerConfiguration.getAdminRealm(), providerConfiguration.getAdminClientId());

    try (OidcOperationHandler handler = oidcOperationHandlerFactory.getHandler(provider)) {
      LOG.debug("Opening OIDC operation handler '{}' for cluster {}", handler.getClass().getSimpleName(), clusterName);
      handler.open(adminCredential, providerConfiguration);

      for (OidcServiceDescriptor serviceDescriptor : oidcDescriptor.getServices().values()) {
        if (serviceDescriptor == null || serviceDescriptor.getName() == null) {
          LOG.debug("Skipping null/unnamed OIDC service descriptor entry");
          continue;
        }
        LOG.debug("Evaluating OIDC descriptor service '{}'", serviceDescriptor.getName());

        if (!installedServices.containsKey(serviceDescriptor.getName())) {
          LOG.debug("Skipping OIDC descriptor service '{}' because it is not installed in cluster {}",
            serviceDescriptor.getName(), clusterName);
          continue;
        }

        if (!isServiceOidcEnabled(serviceDescriptor)) {
          LOG.info("Skipping OIDC descriptor service '{}' because descriptor marks it disabled",
            serviceDescriptor.getName());
          continue;
        }

        if (serviceDescriptor.getConfigurations() != null) {
          LOG.debug("Applying service-level OIDC config overlays for '{}'", serviceDescriptor.getName());
          applyConfigurations(serviceDescriptor.getConfigurations(), propertiesToSet, configTypes,
            buildReplacementMap(cluster, existingConfigurations, providerConfiguration, null, null));
        }

        if (serviceDescriptor.getClients() == null) {
          LOG.debug("OIDC descriptor service '{}' has no client declarations", serviceDescriptor.getName());
          continue;
        }

        for (OidcClientDescriptor clientDescriptor : serviceDescriptor.getClients()) {
          if (clientDescriptor == null) {
            LOG.debug("Skipping null OIDC client descriptor in service '{}'", serviceDescriptor.getName());
            continue;
          }
          LOG.debug("Provisioning OIDC client '{}' for service '{}'",
            clientDescriptor.getName(), serviceDescriptor.getName());

          String resolvedClientId = resolveClientId(cluster, clientDescriptor, existingConfigurations, providerConfiguration);
          if (resolvedClientId == null) {
            LOG.debug("Skipping OIDC client '{}' because resolved client id is null",
              clientDescriptor.getName());
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
          LOG.debug("Ensuring OIDC client '{}' in realm '{}'", resolvedClientId, resolvedRealm);

          OidcClientResult result = handler.ensureClient(resolvedDescriptor, resolvedRealm);
          if (result == null) {
            LOG.warn("OIDC provider returned null result for client '{}' in realm '{}'; skipping config updates",
              resolvedClientId, resolvedRealm);
            continue;
          }
          LOG.info("OIDC client '{}' ensured in realm '{}' for service '{}'",
            resolvedClientId, resolvedRealm, serviceDescriptor.getName());

          if (!StringUtils.isEmpty(clientDescriptor.getSecretAlias()) && result.getSecret() != null) {
            try {
              LOG.debug("Persisting OIDC client secret for alias '{}' in cluster '{}'",
                clientDescriptor.getSecretAlias(), clusterName);
              credentialStoreService.setCredential(clusterName, clientDescriptor.getSecretAlias(),
                new GenericKeyCredential(result.getSecret().toCharArray()), CredentialStoreType.PERSISTED);
            } catch (AmbariException e) {
              throw new OidcOperationException("Failed to store OIDC client secret in credential store", e);
            }
          }

          // Apply client-level configuration overlays with replacement values such as:
          // ${client_id}, ${client_secret}, ${auth_server_url}, ${oidc_realm}.
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
      LOG.info("Applying OIDC config updates for cluster {} ({} config type(s), note='{}')",
        clusterName, configTypes.size(), configNote);

      configHelper.updateBulkConfigType(cluster, cluster.getDesiredStackVersion(), controller,
        configTypes, propertiesToSet, propertiesToRemove,
        authenticatedUserName, configNote);
    } else {
      LOG.info("OIDC provisioning completed for cluster {} with no configuration updates required", clusterName);
    }

    LOG.info("OIDC provisioning completed successfully for cluster {}", clusterName);
    return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
  }

  /**
   * Reads the persisted OIDC administrator credential used to authenticate against
   * the external OIDC provider admin API.
   *
   * @param clusterName target cluster name
   * @return principal/key credential or {@code null} if missing/wrong type
   * @throws AmbariException if credential store access fails
   */
  private PrincipalKeyCredential getAdminCredential(String clusterName) throws AmbariException {
    Credential credential = credentialStoreService.getCredential(clusterName, OIDC_ADMIN_CREDENTIAL_ALIAS);
    if (credential instanceof PrincipalKeyCredential) {
      return (PrincipalKeyCredential) credential;
    }
    return null;
  }

  /**
   * Determines whether a descriptor service should be processed for OIDC provisioning.
   * <p>
   * Policy:
   * </p>
   * <ul>
   *   <li>If descriptor explicitly sets {@code enabled}, honor that value.</li>
   *   <li>If descriptor does not declare {@code enabled}, default to {@code true}.</li>
   * </ul>
   *
   * @param serviceDescriptor service descriptor from stack OIDC descriptor
   * @return {@code true} if provisioning should process this service
   */
  private boolean isServiceOidcEnabled(OidcServiceDescriptor serviceDescriptor) {
    if (serviceDescriptor.isEnabled() != null) {
      return serviceDescriptor.isEnabled();
    }
    return true;
  }

  /**
   * Resolves the effective client id for a descriptor client by applying variable
   * replacement against cluster/global OIDC context.
   *
   * @param cluster               cluster context
   * @param clientDescriptor      descriptor client definition
   * @param configurations        effective cluster configurations
   * @param providerConfiguration provider settings
   * @return resolved client id, or {@code null} if descriptor does not define one
   * @throws AmbariException if replacement fails
   */
  private String resolveClientId(Cluster cluster, OidcClientDescriptor clientDescriptor,
                                 Map<String, Map<String, String>> configurations,
                                 OidcProviderConfiguration providerConfiguration) throws AmbariException {
    if (clientDescriptor.getClientId() == null) {
      return null;
    }
    return variableReplacementHelper.replaceVariables(clientDescriptor.getClientId(),
      buildReplacementMap(cluster, configurations, providerConfiguration, null, null));
  }

  /**
   * Resolves the effective realm for a descriptor client.
   * <p>
   * If a client-specific realm is absent, provider default realm is used.
   * </p>
   *
   * @param cluster        cluster context
   * @param clientDescriptor descriptor client definition
   * @param configuration  provider configuration
   * @param configurations effective cluster configurations
   * @return resolved realm name
   * @throws AmbariException if replacement fails
   */
  private String resolveRealm(Cluster cluster, OidcClientDescriptor clientDescriptor, OidcProviderConfiguration configuration,
                              Map<String, Map<String, String>> configurations) throws AmbariException {
    String realm = clientDescriptor.getRealm();
    if (realm == null) {
      return configuration.getRealm();
    }
    return variableReplacementHelper.replaceVariables(realm, buildReplacementMap(cluster, configurations, configuration, null, null));
  }

  /**
   * Builds replacement data used by {@link VariableReplacementHelper}.
   * <p>
   * The returned map contains:
   * </p>
   * <ul>
   *   <li>All effective configurations (for cross-config substitutions).</li>
   *   <li>Global replacement bucket ({@code ""}) containing cluster/provider/client variables.</li>
   * </ul>
   *
   * @param cluster               cluster context
   * @param configurations        effective configuration map
   * @param providerConfiguration provider details
   * @param clientResult          resolved provider client result
   * @param realmOverride         optional realm override
   * @return replacement map for variable resolution
   */
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

  /**
   * Merges descriptor configuration overlays into the accumulated update map.
   * <p>
   * Each property value is processed through variable replacement before being stored.
   * </p>
   *
   * @param configurations configuration overlays from descriptor
   * @param propertiesToSet target map of config-type -&gt; property updates
   * @param configTypes accumulator of config types touched in this action
   * @param replacements replacement map for variable resolution
   * @throws AmbariException if variable replacement fails
   */
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
        LOG.debug("Applying {} OIDC property update(s) to config type '{}'", properties.size(), configType);

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

  /**
   * Builds an issuer URL in the form {@code <baseUrl>/realms/<realm>}.
   *
   * @param baseUrl provider base URL
   * @param realm realm name
   * @return issuer URL or {@code null} when input is incomplete
   */
  private String buildAuthServerUrl(String baseUrl, String realm) {
    if (StringUtils.isEmpty(baseUrl) || StringUtils.isEmpty(realm)) {
      return null;
    }
    String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    return trimmed + "/realms/" + realm;
  }

  /**
   * Returns {@code defaultValue} when {@code value} is empty.
   *
   * @param value source value
   * @param defaultValue fallback value
   * @return normalized value
   */
  private String defaultIfEmpty(String value, String defaultValue) {
    return StringUtils.isEmpty(value) ? defaultValue : value;
  }

  /**
   * Safe utility to read a single command parameter.
   *
   * @param commandParameters command parameter map
   * @param propertyName parameter key
   * @return parameter value or {@code null}
   */
  private static String getCommandParameterValue(Map<String, String> commandParameters, String propertyName) {
    return ((commandParameters == null) || (propertyName == null)) ? null : commandParameters.get(propertyName);
  }
}
