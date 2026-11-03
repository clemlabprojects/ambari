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

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.Role;
import org.apache.ambari.server.RoleCommand;
import org.apache.ambari.server.actionmanager.ActionManager;
import org.apache.ambari.server.actionmanager.RequestFactory;
import org.apache.ambari.server.actionmanager.Stage;
import org.apache.ambari.server.actionmanager.StageFactory;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.RootComponent;
import org.apache.ambari.server.controller.internal.RequestStageContainer;
import org.apache.ambari.server.stageplanner.RoleGraph;
import org.apache.ambari.server.stageplanner.RoleGraphFactory;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.PropertyInfo;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceInfo;
import org.apache.ambari.server.state.svccomphost.ServiceComponentHostServerActionEvent;
import org.apache.ambari.server.utils.StageUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Schedules {@link OzoneS3SecretsServerAction}: when services are installed, and on request
 * (the {@code ozone_s3_secrets} cluster update directive).
 */
@Singleton
public class OzoneS3SecretsHelper {
  private static final String STAGE_CONTEXT_ENSURE = "Generate Ozone S3 Secrets";
  private static final String STAGE_CONTEXT_ROTATE = "Rotate Ozone S3 Secrets";
  private static final String BASE_LOG_DIR = "/tmp/ambari";

  @Inject
  private AmbariManagementController controller;

  @Inject
  private AmbariMetaInfo ambariMetaInfo;

  @Inject
  private StageFactory stageFactory;

  @Inject
  private RequestFactory requestFactory;

  @Inject
  private ActionManager actionManager;

  @Inject
  private RoleGraphFactory roleGraphFactory;

  @Inject
  private Configuration configuration;

  /**
   * Whether installing these services calls for the secrets: the cluster has OZONE, and either
   * OZONE itself or a service declaring an Ozone S3 secret is among them.
   */
  public boolean isNeededOnInstall(Cluster cluster, Collection<String> installedServices) throws AmbariException {
    if (cluster == null || installedServices == null
      || !cluster.getServices().containsKey(OzoneS3SecretsServerAction.OZONE_SERVICE)) {
      return false;
    }
    for (String serviceName : installedServices) {
      if (OzoneS3SecretsServerAction.OZONE_SERVICE.equals(serviceName) || declaresSecret(cluster, serviceName)) {
        return true;
      }
    }
    return false;
  }

  private boolean declaresSecret(Cluster cluster, String serviceName) throws AmbariException {
    Service service = cluster.getServices().get(serviceName);
    if (service == null) {
      return false;
    }
    ServiceInfo serviceInfo = ambariMetaInfo.getService(service.getDesiredStackId().getStackName(),
      service.getDesiredStackId().getStackVersion(), serviceName);
    if (serviceInfo == null) {
      return false;
    }
    for (PropertyInfo property : serviceInfo.getProperties()) {
      if (property.getPropertyTypes().contains(PropertyInfo.PropertyType.OZONE_S3_SECRET)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Appends the stage of the action to the request (a new request when null).
   *
   * @param operation {@link OzoneS3SecretsServerAction#OPERATION_ENSURE} or
   *                  {@link OzoneS3SecretsServerAction#OPERATION_ROTATE}
   */
  public RequestStageContainer addStage(Cluster cluster, RequestStageContainer requestStageContainer,
                                        String operation) throws AmbariException {
    boolean rotate = OzoneS3SecretsServerAction.OPERATION_ROTATE.equalsIgnoreCase(operation);
    String context = rotate ? STAGE_CONTEXT_ROTATE : STAGE_CONTEXT_ENSURE;

    if (requestStageContainer == null) {
      requestStageContainer = new RequestStageContainer(actionManager.getNextRequestId(), null, requestFactory,
        actionManager);
      requestStageContainer.setRequestContext(context);
    }

    Map<String, String> commandParameters = new HashMap<>();
    commandParameters.put(OzoneS3SecretsServerAction.OPERATION,
      rotate ? OzoneS3SecretsServerAction.OPERATION_ROTATE : OzoneS3SecretsServerAction.OPERATION_ENSURE);
    commandParameters.put(OzoneS3SecretsServerAction.AUTHENTICATED_USER_NAME, controller.getAuthName());

    Stage stage = stageFactory.createNew(requestStageContainer.getId(),
      BASE_LOG_DIR + File.pathSeparator + requestStageContainer.getId(), cluster.getClusterName(),
      cluster.getClusterId(), context, "{}", "{}");
    stage.setStageId(requestStageContainer.getLastStageId());
    stage.addServerActionCommand(OzoneS3SecretsServerAction.class.getName(), null, Role.AMBARI_SERVER_ACTION,
      RoleCommand.EXECUTE, cluster.getClusterName(),
      new ServiceComponentHostServerActionEvent(RootComponent.AMBARI_SERVER.name(), StageUtils.getHostName(),
        System.currentTimeMillis()),
      commandParameters, context, controller.findConfigurationTagsWithOverrides(cluster, null, null),
      configuration.getDefaultServerTaskTimeout(), false, false);

    RoleGraph roleGraph = roleGraphFactory.createNew(controller.getRoleCommandOrder(cluster));
    roleGraph.build(stage);
    requestStageContainer.addStages(roleGraph.getStages());
    return requestStageContainer;
  }
}
