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

package org.apache.ambari.server.serveraction.ozone;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentMap;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.security.credential.Credential;
import org.apache.ambari.server.security.credential.GenericKeyCredential;
import org.apache.ambari.server.security.encryption.CredentialStoreService;
import org.apache.ambari.server.security.encryption.CredentialStoreType;
import org.apache.ambari.server.serveraction.AbstractServerAction;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.PropertyInfo;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

/**
 * Gives every installed service that consumes the Ozone S3 gateway a secret managed by Ambari.
 * <p>
 * A consumer declares the property holding its secret with the {@code OZONE_S3_SECRET} property
 * type. This action generates the missing secrets (ENSURE) or new ones for all of them (ROTATE),
 * keeps a copy in the persisted Ambari credential store, and writes them into the configurations.
 * The consumers apply their secret on Ozone ({@code ozone s3 setsecret}) when they start, so the
 * restart Ambari then asks for completes a rotation.
 * </p>
 * <p>
 * ENSURE keeps a secret already set (by an operator, or a previous run), and restores the stored
 * copy when a service was removed and added again: Ozone still holds that secret.
 * </p>
 */
public class OzoneS3SecretsServerAction extends AbstractServerAction {
  private static final Logger LOG = LoggerFactory.getLogger(OzoneS3SecretsServerAction.class);

  /**
   * Cluster update directive requesting this action: {@code ?ozone_s3_secrets=ENSURE|ROTATE}.
   */
  public static final String DIRECTIVE = "ozone_s3_secrets";
  public static final String OPERATION = "ozone_s3_secrets_operation";
  public static final String OPERATION_ENSURE = "ENSURE";
  public static final String OPERATION_ROTATE = "ROTATE";
  public static final String AUTHENTICATED_USER_NAME = "authenticated_user_name";
  public static final String OZONE_SERVICE = "OZONE";

  static final String ALIAS_PREFIX = "ozone.s3.secret.";
  private static final int SECRET_BYTES = 30;

  private static final SecureRandom RANDOM = new SecureRandom();

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
    boolean rotate = OPERATION_ROTATE.equalsIgnoreCase(getCommandParameterValue(OPERATION));

    if (!cluster.getServices().containsKey(OZONE_SERVICE)) {
      actionLog.writeStdOut("OZONE is not installed: no Ozone S3 secret to manage.");
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    boolean storeAvailable = credentialStoreService.isInitialized(CredentialStoreType.PERSISTED);
    if (!storeAvailable) {
      actionLog.writeStdOut("The persisted Ambari credential store is not set up: the configurations hold the " +
        "only copy of the secrets.");
    }

    Map<PropertyInfo, String> secretProperties = configHelper.getPropertiesWithPropertyType(
      cluster.getDesiredStackVersion(), PropertyInfo.PropertyType.OZONE_S3_SECRET, cluster, cluster.getDesiredConfigs());

    Map<String, Map<String, String>> propertiesToSet = new HashMap<>();
    for (Map.Entry<PropertyInfo, String> entry : secretProperties.entrySet()) {
      String configType = ConfigHelper.fileNameToConfigType(entry.getKey().getFilename());
      String propertyName = entry.getKey().getName();
      String current = StringUtils.defaultString(entry.getValue());
      String alias = alias(configType, propertyName);

      String secret = null;
      String origin = null;
      if (rotate) {
        origin = "rotated";
      } else if (!current.isEmpty()) {
        secret = current;
      } else if (storeAvailable) {
        secret = storedSecret(clusterName, alias);
        origin = (secret == null) ? null : "restored from the Ambari credential store";
      }
      if (secret == null) {
        secret = generateSecret();
        origin = (origin == null) ? "generated" : origin;
      }

      if (storeAvailable) {
        credentialStoreService.setCredential(clusterName, alias,
          new GenericKeyCredential(secret.toCharArray()), CredentialStoreType.PERSISTED);
      }
      if (secret.equals(current)) {
        actionLog.writeStdOut(String.format("%s/%s: secret already set, kept.", configType, propertyName));
        continue;
      }
      propertiesToSet.computeIfAbsent(configType, type -> new HashMap<>()).put(propertyName, secret);
      actionLog.writeStdOut(String.format("%s/%s: secret %s (value hidden).", configType, propertyName, origin));
    }

    if (propertiesToSet.isEmpty()) {
      actionLog.writeStdOut("No configuration to update.");
      return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
    }

    String note = rotate ? "Rotate the Ozone S3 secrets" : "Generate the Ozone S3 secrets";
    configHelper.updateBulkConfigType(cluster, cluster.getDesiredStackVersion(), controller,
      new TreeSet<>(propertiesToSet.keySet()), propertiesToSet, Collections.emptyMap(),
      getCommandParameterValue(AUTHENTICATED_USER_NAME), note);
    LOG.info("{} for cluster {}: updated {}", note, clusterName, propertiesToSet.keySet());
    actionLog.writeStdOut(String.format("Updated %s. Restart the services Ambari marks as needing a restart: " +
      "they apply their new secret on Ozone when they start.", String.join(", ", new TreeSet<>(propertiesToSet.keySet()))));
    return createCommandReport(0, HostRoleStatus.COMPLETED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
  }

  static String alias(String configType, String propertyName) {
    return ALIAS_PREFIX + configType + "." + propertyName;
  }

  static String generateSecret() {
    byte[] bytes = new byte[SECRET_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String storedSecret(String clusterName, String alias) throws AmbariException {
    Credential credential = credentialStoreService.getCredential(clusterName, alias, CredentialStoreType.PERSISTED);
    if (credential instanceof GenericKeyCredential) {
      char[] key = ((GenericKeyCredential) credential).getKey();
      if (key != null && key.length > 0) {
        return new String(key);
      }
    }
    return null;
  }
}
