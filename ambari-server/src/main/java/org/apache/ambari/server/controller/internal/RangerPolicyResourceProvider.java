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
import java.util.EnumSet;
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
import org.apache.ambari.server.security.authorization.RoleAuthorization;
import org.apache.ambari.server.serveraction.ranger.EnsureRangerPolicyServerAction;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.svccomphost.ServiceComponentHostServerActionEvent;
import org.apache.commons.lang.StringUtils;
import org.springframework.security.core.Authentication;

import com.google.gson.Gson;
import com.google.inject.Inject;

/**
 * Resource provider for {@code POST /api/v1/clusters/{cluster}/ranger_policy}.
 *
 * <p>Schedules {@link EnsureRangerPolicyServerAction} — a server-side action that grants
 * a user access on a Ranger service repository using the (decrypted) Ranger admin
 * credentials held by the Ambari server. This lets external Ambari views (e.g. the KDPS
 * k8s view) provision Ranger policies without ever handling the Ranger admin password.
 *
 * <p>With {@code revoke} set to {@code true} the same request takes the access back instead of
 * granting it (see {@link EnsureRangerPolicyServerAction}).
 *
 * <p>Because the grant is made with those administrator credentials, only cluster administrators
 * of the cluster named in the request may ask for it.
 *
 * <p>Mirrors {@link RangerPluginResourceProvider} 1:1 in structure.
 */
@StaticallyInject
public class RangerPolicyResourceProvider extends AbstractControllerResourceProvider {

    private static final String PROPERTY_CLUSTER_NAME          = "cluster_name";
    private static final String PROPERTY_CLUSTER_NAME_NS       = "Clusters/cluster_name";

    private static final String PROPERTY_SERVICE_NAME          = "rangerServiceName";
    private static final String PROPERTY_SERVICE_NAME_NS       = "RangerPolicy/rangerServiceName";

    private static final String PROPERTY_USER_NAME             = "userName";
    private static final String PROPERTY_USER_NAME_NS          = "RangerPolicy/userName";

    private static final String PROPERTY_ACCESS_TYPES          = "accessTypes";
    private static final String PROPERTY_ACCESS_TYPES_NS       = "RangerPolicy/accessTypes";

    private static final String PROPERTY_RESOURCES_JSON        = "resourcesJson";
    private static final String PROPERTY_RESOURCES_JSON_NS     = "RangerPolicy/resourcesJson";

    private static final String PROPERTY_POLICY_NAME_HINT      = "policyNameHint";
    private static final String PROPERTY_POLICY_NAME_HINT_NS   = "RangerPolicy/policyNameHint";

    private static final String PROPERTY_POLICY_DESCRIPTION    = "policyDescription";
    private static final String PROPERTY_POLICY_DESCRIPTION_NS = "RangerPolicy/policyDescription";

    private static final String PROPERTY_TIMEOUT               = "timeoutSeconds";
    private static final String PROPERTY_TIMEOUT_NS            = "RangerPolicy/timeoutSeconds";
    private static final String PROPERTY_POLICY_TYPE           = "policyType";
    private static final String PROPERTY_POLICY_TYPE_NS        = "RangerPolicy/policyType";
    private static final String PROPERTY_MASK_TYPE             = "maskType";
    private static final String PROPERTY_MASK_TYPE_NS          = "RangerPolicy/maskType";
    private static final String PROPERTY_MASK_CONDITION        = "maskConditionExpr";
    private static final String PROPERTY_MASK_CONDITION_NS     = "RangerPolicy/maskConditionExpr";
    private static final String PROPERTY_MASK_VALUE            = "maskValueExpr";
    private static final String PROPERTY_MASK_VALUE_NS         = "RangerPolicy/maskValueExpr";
    private static final String PROPERTY_ROW_FILTER            = "rowFilterExpr";
    private static final String PROPERTY_ROW_FILTER_NS         = "RangerPolicy/rowFilterExpr";
    private static final String PROPERTY_GROUPS                = "groups";
    private static final String PROPERTY_GROUPS_NS             = "RangerPolicy/groups";
    private static final String PROPERTY_REVOKE                = "revoke";
    private static final String PROPERTY_REVOKE_NS             = "RangerPolicy/revoke";

    private static final String PROPERTY_CONTEXT               = "context";
    private static final String PROPERTY_CONTEXT_NS            = "RequestInfo/context";

    private static final Set<String> PROPERTY_IDS;
    private static final Map<Resource.Type, String> KEY_PROPERTY_IDS;

    static {
        Set<String> propertyIds = new HashSet<>();
        Collections.addAll(propertyIds,
                PROPERTY_CLUSTER_NAME, PROPERTY_CLUSTER_NAME_NS,
                PROPERTY_SERVICE_NAME, PROPERTY_SERVICE_NAME_NS,
                PROPERTY_USER_NAME, PROPERTY_USER_NAME_NS,
                PROPERTY_ACCESS_TYPES, PROPERTY_ACCESS_TYPES_NS,
                PROPERTY_RESOURCES_JSON, PROPERTY_RESOURCES_JSON_NS,
                PROPERTY_POLICY_NAME_HINT, PROPERTY_POLICY_NAME_HINT_NS,
                PROPERTY_POLICY_DESCRIPTION, PROPERTY_POLICY_DESCRIPTION_NS,
                PROPERTY_TIMEOUT, PROPERTY_TIMEOUT_NS,
                PROPERTY_CONTEXT, PROPERTY_CONTEXT_NS,
                PROPERTY_POLICY_TYPE, PROPERTY_POLICY_TYPE_NS,
                PROPERTY_MASK_TYPE, PROPERTY_MASK_TYPE_NS,
                PROPERTY_MASK_CONDITION, PROPERTY_MASK_CONDITION_NS,
                PROPERTY_MASK_VALUE, PROPERTY_MASK_VALUE_NS,
                PROPERTY_ROW_FILTER, PROPERTY_ROW_FILTER_NS,
                PROPERTY_GROUPS, PROPERTY_GROUPS_NS,
                PROPERTY_REVOKE, PROPERTY_REVOKE_NS
        );
        PROPERTY_IDS = Collections.unmodifiableSet(propertyIds);

        Map<Resource.Type, String> keyPropertyIds = new HashMap<>();
        keyPropertyIds.put(Resource.Type.Cluster, PROPERTY_CLUSTER_NAME_NS);
        KEY_PROPERTY_IDS = Collections.unmodifiableMap(keyPropertyIds);
    }

    /**
     * A policy grant is made with the Ranger administrator credentials Ambari holds for the cluster,
     * so it is reserved to cluster administrators.
     */
    private static final Set<RoleAuthorization> REQUIRED_CREATE_AUTHORIZATIONS =
            EnumSet.of(RoleAuthorization.CLUSTER_TOGGLE_KERBEROS);

    private static final Gson GSON = new Gson();

    @Inject
    private static StageFactory stageFactory;

    @Inject
    private static RequestFactory requestFactory;

    private final AmbariManagementController managementController;
    private final Clusters clusters;
    private final ActionManager actionManager;

    @Inject
    public RangerPolicyResourceProvider(AmbariManagementController controller) {
        super(Resource.Type.RANGER_POLICY, PROPERTY_IDS, KEY_PROPERTY_IDS, controller);
        this.managementController = controller;
        this.clusters = controller.getClusters();
        this.actionManager = controller.getActionManager();
        setRequiredCreateAuthorizations(REQUIRED_CREATE_AUTHORIZATIONS);
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
    protected boolean isAuthorizedToCreateResources(Authentication authentication, Request request) {
        return isAuthorizedOnRequestedClusters(authentication, request,
                RangerPolicyResourceProvider::clusterNameOf, getRequiredCreateAuthorizations());
    }

    @Override
    protected RequestStatus createResourcesAuthorized(Request request)
            throws SystemException, UnsupportedPropertyException,
            ResourceAlreadyExistsException, NoSuchParentResourceException {

        Set<Map<String, Object>> items = request.getProperties();
        if (items == null || items.isEmpty()) {
            throw new SystemException("No properties supplied");
        }

        RequestStatusResponse statusResponse = null;

        for (Map<String, Object> properties : items) {
            String clusterName       = clusterNameOf(properties);
            String rangerServiceName = firstNonBlank(
                    stringValue(properties.get(PROPERTY_SERVICE_NAME)),
                    stringValue(properties.get(PROPERTY_SERVICE_NAME_NS)));
            String userName          = firstNonBlank(
                    stringValue(properties.get(PROPERTY_USER_NAME)),
                    stringValue(properties.get(PROPERTY_USER_NAME_NS)));
            String accessTypes       = firstNonBlank(
                    stringValue(properties.get(PROPERTY_ACCESS_TYPES)),
                    stringValue(properties.get(PROPERTY_ACCESS_TYPES_NS)));
            String resourcesJson     = firstNonBlank(
                    stringValue(properties.get(PROPERTY_RESOURCES_JSON)),
                    stringValue(properties.get(PROPERTY_RESOURCES_JSON_NS)));
            String policyNameHint    = firstNonBlank(
                    stringValue(properties.get(PROPERTY_POLICY_NAME_HINT)),
                    stringValue(properties.get(PROPERTY_POLICY_NAME_HINT_NS)));
            String policyDescription = firstNonBlank(
                    stringValue(properties.get(PROPERTY_POLICY_DESCRIPTION)),
                    stringValue(properties.get(PROPERTY_POLICY_DESCRIPTION_NS)));
            String context           = firstNonBlank(
                    stringValue(properties.get(PROPERTY_CONTEXT)),
                    stringValue(properties.get(PROPERTY_CONTEXT_NS)),
                    "Ensure Ranger policy");
            int timeoutSeconds       = firstInt(
                    integerValue(properties.get(PROPERTY_TIMEOUT)),
                    integerValue(properties.get(PROPERTY_TIMEOUT_NS)),
                    120);
            // Policy shape: 0 = access (default), 1 = data masking, 2 = row filtering.
            int policyType           = firstInt(
                    integerValue(properties.get(PROPERTY_POLICY_TYPE)),
                    integerValue(properties.get(PROPERTY_POLICY_TYPE_NS)),
                    0);
            String maskType          = firstNonBlank(
                    stringValue(properties.get(PROPERTY_MASK_TYPE)),
                    stringValue(properties.get(PROPERTY_MASK_TYPE_NS)));
            String maskConditionExpr = firstNonBlank(
                    stringValue(properties.get(PROPERTY_MASK_CONDITION)),
                    stringValue(properties.get(PROPERTY_MASK_CONDITION_NS)));
            String maskValueExpr     = firstNonBlank(
                    stringValue(properties.get(PROPERTY_MASK_VALUE)),
                    stringValue(properties.get(PROPERTY_MASK_VALUE_NS)));
            String rowFilterExpr     = firstNonBlank(
                    stringValue(properties.get(PROPERTY_ROW_FILTER)),
                    stringValue(properties.get(PROPERTY_ROW_FILTER_NS)));
            String groups            = firstNonBlank(
                    stringValue(properties.get(PROPERTY_GROUPS)),
                    stringValue(properties.get(PROPERTY_GROUPS_NS)));
            boolean revoke           = Boolean.parseBoolean(firstNonBlank(
                    stringValue(properties.get(PROPERTY_REVOKE)),
                    stringValue(properties.get(PROPERTY_REVOKE_NS))));
            if (StringUtils.isBlank(accessTypes) && policyType != 0) {
                accessTypes = "select";
            }

            if (StringUtils.isBlank(clusterName)) {
                throw new SystemException("clusterName is required");
            }
            if (StringUtils.isBlank(rangerServiceName)) {
                throw new SystemException("rangerServiceName is required");
            }
            if (StringUtils.isBlank(userName) && StringUtils.isBlank(groups)) {
                throw new SystemException("userName or groups is required");
            }
            if (policyType < 0 || policyType > 2) {
                throw new SystemException("policyType must be 0 (access), 1 (data mask) or 2 (row filter)");
            }
            if (policyType == 1 && StringUtils.isBlank(maskType)) {
                throw new SystemException("maskType is required for policyType=1");
            }
            if (policyType == 2 && StringUtils.isBlank(rowFilterExpr)) {
                throw new SystemException("rowFilterExpr is required for policyType=2");
            }
            if (revoke && policyType != 0) {
                throw new SystemException("revoke applies to access policies only (policyType=0)");
            }
            if (StringUtils.isBlank(accessTypes)) {
                throw new SystemException("accessTypes is required");
            }
            if (StringUtils.isBlank(resourcesJson)) {
                throw new SystemException("resourcesJson is required");
            }
            if (StringUtils.isBlank(policyNameHint)) {
                throw new SystemException("policyNameHint is required");
            }

            try {
                Cluster cluster     = clusters.getCluster(clusterName);
                long requestId      = actionManager.getNextRequestId();
                String logDirectory = BASE_LOG_DIR + File.separator + requestId;

                Map<String, String> commandParameters = new LinkedHashMap<>();
                commandParameters.put("clusterName", clusterName);
                commandParameters.put("rangerServiceName", rangerServiceName);
                if (StringUtils.isNotBlank(userName)) {
                    commandParameters.put("userName", userName);
                }
                commandParameters.put("accessTypes", accessTypes);
                commandParameters.put("policyType", String.valueOf(policyType));
                if (StringUtils.isNotBlank(maskType)) {
                    commandParameters.put("maskType", maskType);
                }
                if (StringUtils.isNotBlank(maskConditionExpr)) {
                    commandParameters.put("maskConditionExpr", maskConditionExpr);
                }
                if (StringUtils.isNotBlank(maskValueExpr)) {
                    commandParameters.put("maskValueExpr", maskValueExpr);
                }
                if (StringUtils.isNotBlank(rowFilterExpr)) {
                    commandParameters.put("rowFilterExpr", rowFilterExpr);
                }
                if (StringUtils.isNotBlank(groups)) {
                    commandParameters.put("groups", groups);
                }
                if (revoke) {
                    commandParameters.put("revoke", "true");
                }
                commandParameters.put("resourcesJson", resourcesJson);
                commandParameters.put("policyNameHint", policyNameHint);
                if (StringUtils.isNotBlank(policyDescription)) {
                    commandParameters.put("policyDescription", policyDescription);
                }
                commandParameters.put("timeoutSeconds", String.valueOf(timeoutSeconds));

                String commandParametersJson = GSON.toJson(commandParameters);
                String hostParametersJson    = "{}";

                Stage stage = stageFactory.createNew(
                        requestId,
                        logDirectory,
                        cluster.getClusterName(),
                        cluster.getClusterId(),
                        context,
                        commandParametersJson,
                        hostParametersJson
                );
                stage.setStageId(1L);

                stage.addServerActionCommand(
                        EnsureRangerPolicyServerAction.class.getName(),
                        null,
                        Role.AMBARI_SERVER_ACTION,
                        RoleCommand.EXECUTE,
                        cluster.getClusterName(),
                        new ServiceComponentHostServerActionEvent(null, System.currentTimeMillis()),
                        commandParameters,
                        revoke ? "Revoke Ranger access" : "Ensure Ranger policy",
                        managementController.findConfigurationTagsWithOverrides(cluster, null, null),
                        timeoutSeconds,
                        false,
                        false
                );

                List<Stage> stages = new ArrayList<>();
                stages.add(stage);

                String clusterHostInfo =
                        org.apache.ambari.server.utils.StageUtils.getGson()
                                .toJson(org.apache.ambari.server.utils.StageUtils.getClusterHostInfo(cluster));

                Map<String, String> executionParameters = Collections.emptyMap();
                ExecuteActionRequest executeActionRequest =
                        new ExecuteActionRequest(cluster.getClusterName(),
                                "ENSURE_RANGER_POLICY",
                                executionParameters,
                                false);

                actionManager.sendActions(stages, clusterHostInfo, executeActionRequest);
                statusResponse = new RequestStatusResponse(requestId);

            } catch (AmbariException exception) {
                throw new SystemException("Cluster not found: " + clusterName, exception);
            } catch (Exception exception) {
                throw new SystemException("Failed to enqueue Ranger policy grant for service '" +
                        rangerServiceName + "': " + exception.getMessage(), exception);
            }
        }

        return getRequestStatus(statusResponse, null);
    }

    @Override
    public Set<Resource> getResources(Request request, Predicate predicate)
            throws SystemException, UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException {
        throw new UnsupportedOperationException("GET not supported for RangerPolicy");
    }

    @Override
    public RequestStatus updateResources(Request request, Predicate predicate)
            throws SystemException, UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException {
        throw new UnsupportedOperationException("PUT not supported for RangerPolicy");
    }

    // helpers

    /**
     * The cluster a request item acts on. Authorization and the operation both resolve it here so
     * that the permission check is made on the cluster that is really touched.
     */
    private static String clusterNameOf(Map<String, Object> properties) {
        return firstNonBlank(
                stringValue(properties.get(PROPERTY_CLUSTER_NAME)),
                stringValue(properties.get(PROPERTY_CLUSTER_NAME_NS)));
    }

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
