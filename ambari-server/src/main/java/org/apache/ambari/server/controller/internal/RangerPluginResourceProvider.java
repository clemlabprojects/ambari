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

package org.apache.ambari.server.controller.internal;

import static org.apache.ambari.server.controller.KerberosHelperImpl.BASE_LOG_DIR;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.Role;
import org.apache.ambari.server.RoleCommand;
import org.apache.ambari.server.StaticallyInject;
import org.apache.ambari.server.actionmanager.ActionManager;
import org.apache.ambari.server.actionmanager.RequestFactory;
import org.apache.ambari.server.actionmanager.Stage;
import org.apache.ambari.server.actionmanager.StageFactory;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.ExecuteActionRequest;
import org.apache.ambari.server.controller.RequestStatusResponse;
import org.apache.ambari.server.controller.spi.NoSuchParentResourceException;
import org.apache.ambari.server.controller.spi.NoSuchResourceException;
import org.apache.ambari.server.controller.spi.Predicate;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.RequestStatus;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.ResourceAlreadyExistsException;
import org.apache.ambari.server.controller.spi.SystemException;
import org.apache.ambari.server.controller.spi.UnsupportedPropertyException;
import org.apache.ambari.server.serveraction.ranger.CreateRangerServiceServerAction;
import org.apache.ambari.server.serveraction.ranger.UpsertRangerUserServerAction;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.svccomphost.ServiceComponentHostServerActionEvent;
import org.apache.commons.lang.StringUtils;

import com.google.gson.Gson;
import com.google.inject.Inject;

@StaticallyInject
public class RangerPluginResourceProvider extends AbstractControllerResourceProvider {

    private static final String PROPERTY_CLUSTER_NAME              = "cluster_name";
    private static final String PROPERTY_CLUSTER_NAME_NS           = "Clusters/cluster_name";

    private static final String PROPERTY_REPOSITORY_NAME           = "rangerRepositoryName";
    private static final String PROPERTY_REPOSITORY_NAME_NS        = "RangerPlugin/rangerRepositoryName";

    private static final String PROPERTY_SERVICE_TYPE              = "serviceType";
    private static final String PROPERTY_SERVICE_TYPE_NS           = "RangerPlugin/serviceType";

    private static final String PROPERTY_PLUGIN_USER_NAME          = "pluginUserName";
    private static final String PROPERTY_PLUGIN_USER_NAME_NS       = "RangerPlugin/pluginUserName";

    private static final String PROPERTY_PLUGIN_USER_PASSWORD      = "pluginUserPassword";
    private static final String PROPERTY_PLUGIN_USER_PASSWORD_NS   = "RangerPlugin/pluginUserPassword";

    private static final String PROPERTY_TIMEOUT                   = "timeoutSeconds";
    private static final String PROPERTY_TIMEOUT_NS                = "RangerPlugin/timeoutSeconds";

    private static final String PROPERTY_CONTEXT                   = "context";
    private static final String PROPERTY_CONTEXT_NS                = "RequestInfo/context";

    private static final String PROPERTY_REPOSITORY_DESCRIPTION     = "repositoryDescription";
    private static final String PROPERTY_REPOSITORY_DESCRIPTION_NS  = "RangerPlugin/repositoryDescription";

    private static final String PROPERTY_SERVICE_CONFIGS            = "serviceConfigs";
    private static final String PROPERTY_SERVICE_CONFIGS_NS         = "RangerPlugin/serviceConfigs";

    private static final Set<String> PROPERTY_IDS;
    private static final Map<Resource.Type, String> KEY_PROPERTY_IDS;

    static {
        Set<String> propertyIds = new HashSet<>();
        Collections.addAll(propertyIds,
                PROPERTY_CLUSTER_NAME, PROPERTY_CLUSTER_NAME_NS,
                PROPERTY_REPOSITORY_NAME, PROPERTY_REPOSITORY_NAME_NS,
                PROPERTY_SERVICE_TYPE, PROPERTY_SERVICE_TYPE_NS,
                PROPERTY_PLUGIN_USER_NAME, PROPERTY_PLUGIN_USER_NAME_NS,
                PROPERTY_PLUGIN_USER_PASSWORD, PROPERTY_PLUGIN_USER_PASSWORD_NS,
                PROPERTY_TIMEOUT, PROPERTY_TIMEOUT_NS,
                PROPERTY_CONTEXT, PROPERTY_CONTEXT_NS,
                PROPERTY_REPOSITORY_DESCRIPTION, PROPERTY_REPOSITORY_DESCRIPTION_NS,
                PROPERTY_SERVICE_CONFIGS, PROPERTY_SERVICE_CONFIGS_NS
        );
        PROPERTY_IDS = Collections.unmodifiableSet(propertyIds);

        Map<Resource.Type, String> keyPropertyIds = new HashMap<>();
        keyPropertyIds.put(Resource.Type.Cluster, PROPERTY_CLUSTER_NAME_NS);
        KEY_PROPERTY_IDS = Collections.unmodifiableMap(keyPropertyIds);
    }

    private static final Gson GSON = new Gson();

    @Inject
    private static StageFactory stageFactory;

    @Inject
    private static RequestFactory requestFactory;

    private final AmbariManagementController managementController;
    private final Clusters clusters;
    private final ActionManager actionManager;

    @Inject
    public RangerPluginResourceProvider(AmbariManagementController controller) {
        super(Resource.Type.RANGER_PLUGIN_REPOSITORY, PROPERTY_IDS, KEY_PROPERTY_IDS, controller);
        this.managementController = controller;
        this.clusters = controller.getClusters();
        this.actionManager = controller.getActionManager();
    }

    @Override
    protected Set<String> getPKPropertyIds() {
        return new HashSet<>(KEY_PROPERTY_IDS.values());
    }

    @Override
    public Map<Resource.Type, String> getKeyPropertyIds() {
        return KEY_PROPERTY_IDS;
    }

    @Override
    public Set<String> getPropertyIds() {
        return PROPERTY_IDS;
    }

    @Override
    public RequestStatus createResources(Request request)
            throws SystemException, UnsupportedPropertyException,
            ResourceAlreadyExistsException, NoSuchParentResourceException {

        Set<Map<String, Object>> items = request.getProperties();
        if (items == null || items.isEmpty()) {
            throw new SystemException("No properties supplied");
        }

        RequestStatusResponse statusResponse = null;

        for (Map<String, Object> properties : items) {
            String clusterName          = firstNonBlank(
                    stringValue(properties.get(PROPERTY_CLUSTER_NAME)),
                    stringValue(properties.get(PROPERTY_CLUSTER_NAME_NS)));
            String rangerRepositoryName = firstNonBlank(
                    stringValue(properties.get(PROPERTY_REPOSITORY_NAME)),
                    stringValue(properties.get(PROPERTY_REPOSITORY_NAME_NS)));
            String serviceType          = firstNonBlank(
                    stringValue(properties.get(PROPERTY_SERVICE_TYPE)),
                    stringValue(properties.get(PROPERTY_SERVICE_TYPE_NS)));
            String pluginUserName       = firstNonBlank(
                    stringValue(properties.get(PROPERTY_PLUGIN_USER_NAME)),
                    stringValue(properties.get(PROPERTY_PLUGIN_USER_NAME_NS)));
            String pluginUserPassword   = firstNonBlank(
                    stringValue(properties.get(PROPERTY_PLUGIN_USER_PASSWORD)),
                    stringValue(properties.get(PROPERTY_PLUGIN_USER_PASSWORD_NS)));
            String context              = firstNonBlank(
                    stringValue(properties.get(PROPERTY_CONTEXT)),
                    stringValue(properties.get(PROPERTY_CONTEXT_NS)),
                    "Configure Ranger plugin");
            int timeoutSeconds          = firstInt(
                    integerValue(properties.get(PROPERTY_TIMEOUT)),
                    integerValue(properties.get(PROPERTY_TIMEOUT_NS)),
                    600);

            String repositoryDescription = firstNonBlank(
                    stringValue(properties.get(PROPERTY_REPOSITORY_DESCRIPTION)),
                    stringValue(properties.get(PROPERTY_REPOSITORY_DESCRIPTION_NS)));

            if (StringUtils.isBlank(clusterName)) {
                throw new SystemException("clusterName is required");
            }
            if (StringUtils.isBlank(rangerRepositoryName)) {
                throw new SystemException("rangerRepositoryName is required");
            }

            try {
                Cluster cluster     = clusters.getCluster(clusterName);
                long requestId      = actionManager.getNextRequestId();
                String logDirectory = BASE_LOG_DIR + File.separator + requestId;
                String serviceConfigsJson = firstNonBlank(
                        stringValue(properties.get(PROPERTY_SERVICE_CONFIGS)),
                        stringValue(properties.get(PROPERTY_SERVICE_CONFIGS_NS)));

                // commandParams shared across stages
                Map<String, String> commandParameters = new LinkedHashMap<>();
                commandParameters.put("clusterName", clusterName);
                commandParameters.put("rangerRepositoryName", rangerRepositoryName);
                if (StringUtils.isNotBlank(serviceType)) {
                    commandParameters.put("serviceType", serviceType);
                }
                if (StringUtils.isNotBlank(pluginUserName)) {
                    commandParameters.put("pluginUserName", pluginUserName);
                }
                if (StringUtils.isNotBlank(pluginUserPassword)) {
                    commandParameters.put("pluginUserPassword", pluginUserPassword);
                }

                if (StringUtils.isNotBlank(repositoryDescription)){
                    commandParameters.put("repositoryDescription", repositoryDescription);
                }
                if (StringUtils.isNotBlank(serviceConfigsJson)) {
                    commandParameters.put("serviceConfigsJson", serviceConfigsJson);
                }

                String commandParametersJson = GSON.toJson(commandParameters);
                String hostParametersJson    = "{}";

                // Stage 1: create/ensure Ranger service
                Stage stageRepository = stageFactory.createNew(
                        requestId,
                        logDirectory,
                        cluster.getClusterName(),
                        cluster.getClusterId(),
                        context + " (repository)",
                        commandParametersJson,
                        hostParametersJson
                );
                stageRepository.setStageId(1L);

                stageRepository.addServerActionCommand(
                        CreateRangerServiceServerAction.class.getName(),
                        null,
                        Role.AMBARI_SERVER_ACTION,
                        RoleCommand.EXECUTE,
                        cluster.getClusterName(),
                        new ServiceComponentHostServerActionEvent(null, System.currentTimeMillis()),
                        commandParameters,
                        "Create Ranger service",
                        managementController.findConfigurationTagsWithOverrides(cluster, null, null),
                        timeoutSeconds,
                        false,
                        false
                );

                List<Stage> stages = new ArrayList<>();
                stages.add(stageRepository);

                // Stage 2 (optional): create/update Ranger user
                if (StringUtils.isNotBlank(pluginUserName)) {
                    Stage stageUser = stageFactory.createNew(
                            requestId,
                            logDirectory,
                            cluster.getClusterName(),
                            cluster.getClusterId(),
                            context + " (user)",
                            commandParametersJson,
                            hostParametersJson
                    );
                    stageUser.setStageId(2L);

                    stageUser.addServerActionCommand(
                            UpsertRangerUserServerAction.class.getName(),
                            null,
                            Role.AMBARI_SERVER_ACTION,
                            RoleCommand.EXECUTE,
                            cluster.getClusterName(),
                            new ServiceComponentHostServerActionEvent(null, System.currentTimeMillis()),
                            commandParameters,
                            "Create or update Ranger user",
                            managementController.findConfigurationTagsWithOverrides(cluster, null, null),
                            timeoutSeconds,
                            false,
                            false
                    );
                    stages.add(stageUser);
                }

                String clusterHostInfo =
                        org.apache.ambari.server.utils.StageUtils.getGson()
                                .toJson(org.apache.ambari.server.utils.StageUtils.getClusterHostInfo(cluster));

                Map<String, String> executionParameters = Collections.emptyMap();
                ExecuteActionRequest executeActionRequest =
                        new ExecuteActionRequest(cluster.getClusterName(),
                                "CONFIGURE_RANGER_PLUGIN",
                                executionParameters,
                                false);

                actionManager.sendActions(stages, clusterHostInfo, executeActionRequest);
                statusResponse = new RequestStatusResponse(requestId);

            } catch (AmbariException exception) {
                throw new SystemException("Cluster not found: " + clusterName, exception);
            } catch (Exception exception) {
                throw new SystemException("Failed to enqueue Ranger plugin configuration for repository '" +
                        rangerRepositoryName + "': " + exception.getMessage(), exception);
            }
        }

        return getRequestStatus(statusResponse, null);
    }

    @Override
    public Set<Resource> getResources(Request request, Predicate predicate)
            throws SystemException, UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException {
        throw new UnsupportedOperationException("GET not supported for RangerPlugin");
    }

    @Override
    public RequestStatus updateResources(Request request, Predicate predicate)
            throws SystemException, UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException {
        throw new UnsupportedOperationException("PUT not supported for RangerPlugin");
    }

    // helpers, still boring

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int firstInt(Integer first, Integer second, int defaultValue) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return defaultValue;
    }
}