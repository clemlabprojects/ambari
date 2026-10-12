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
import org.apache.ambari.server.serveraction.kerberos.AdhocKeytabPrincipalPolicy;
import org.apache.ambari.server.serveraction.kerberos.GenerateAdhocKeytabServerAction;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.svccomphost.ServiceComponentHostServerActionEvent;
import org.apache.commons.lang.StringUtils;
import org.springframework.security.core.Authentication;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Provider;

/**
 * Resource provider for {@code POST /api/v1/clusters/{cluster}/adhoc_keytab}.
 *
 * <p>Schedules {@link GenerateAdhocKeytabServerAction}, which creates the principal in the cluster's
 * KDC if needed and stores its keytab for a single read. Only cluster administrators may ask for it,
 * and only for principals {@link AdhocKeytabPrincipalPolicy} accepts for the Kubernetes namespace
 * the request names as the keytab's destination: never one of the identities the cluster itself
 * relies on.
 */
@StaticallyInject
public class AdhocKeytabResourceProvider extends AbstractControllerResourceProvider {

    private static final String PROP_CLUSTER_NAME      = "cluster_name";
    private static final String PROP_CLUSTER_NAME_NS   = "Clusters/cluster_name";
    private static final String PROP_PRINCIPAL         = "principal";
    private static final String PROP_PRINCIPAL_NS      = "AdhocKeytab/principal";
    private static final String PROP_NAMESPACE         = "namespace";
    private static final String PROP_NAMESPACE_NS      = "AdhocKeytab/namespace";
    private static final String PROP_KDC_TYPE          = "kdc_type";
    private static final String PROP_KDC_TYPE_NS       = "AdhocKeytab/kdc_type";
    private static final String PROP_DEFAULT_REALM     = "default_realm";
    private static final String PROP_DEFAULT_REALM_NS  = "AdhocKeytab/default_realm";
    private static final String PROP_TIMEOUT           = "timeout";
    private static final String PROP_TIMEOUT_NS        = "AdhocKeytab/timeout";
    private static final String PROP_CONTEXT           = "context";
    private static final String PROP_CONTEXT_NS        = "RequestInfo/context";

    private static final Set<String> PROPERTY_IDS;
    private static final Map<Resource.Type, String> KEY_PROPERTY_IDS;

    static {
        Set<String> p = new HashSet<>();
        Collections.addAll(p,
                PROP_CLUSTER_NAME, PROP_CLUSTER_NAME_NS,
                PROP_PRINCIPAL, PROP_PRINCIPAL_NS,
                PROP_NAMESPACE, PROP_NAMESPACE_NS,
                PROP_KDC_TYPE, PROP_KDC_TYPE_NS,
                PROP_DEFAULT_REALM, PROP_DEFAULT_REALM_NS,
                PROP_TIMEOUT, PROP_TIMEOUT_NS,
                PROP_CONTEXT, PROP_CONTEXT_NS
        );
        PROPERTY_IDS = Collections.unmodifiableSet(p);

        Map<Resource.Type, String> k = new HashMap<>();
        k.put(Resource.Type.Cluster, PROP_CLUSTER_NAME_NS);
        KEY_PROPERTY_IDS = Collections.unmodifiableMap(k);
    }

    /**
     * Issuing a keytab hands out a Kerberos identity created with the KDC administrator credentials
     * Ambari holds, so it requires the same authorization as managing the cluster's Kerberos setup.
     */
    private static final Set<RoleAuthorization> REQUIRED_CREATE_AUTHORIZATIONS =
            EnumSet.of(RoleAuthorization.CLUSTER_TOGGLE_KERBEROS);

    private static final Gson GSON = new Gson();

    @Inject
    private static StageFactory stageFactory;

    @Inject
    private static RequestFactory requestFactory;

    /**
     * Resolved on first use: the policy reaches the Kerberos helper, which cannot be built while the
     * injector that performs static injection is still being created.
     */
    @Inject
    private static Provider<AdhocKeytabPrincipalPolicy> principalPolicy;

    private final AmbariManagementController amc;
    private final Clusters clusters;
    private final ActionManager actionManager;

    @Inject
    public AdhocKeytabResourceProvider(AmbariManagementController controller) {
        super(Resource.Type.ADHOC_KEYTAB, PROPERTY_IDS, KEY_PROPERTY_IDS, controller);
        this.amc = controller;
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
                AdhocKeytabResourceProvider::clusterNameOf, getRequiredCreateAuthorizations());
    }

    @Override
    protected RequestStatus createResourcesAuthorized(Request request)
            throws SystemException, UnsupportedPropertyException,
            ResourceAlreadyExistsException, NoSuchParentResourceException {

        Set<Map<String, Object>> items = request.getProperties();
        RequestStatusResponse response = null;
        if (items == null || items.isEmpty()) {
            throw new SystemException("No properties supplied");
        }

        // Every principal is checked before the first keytab is scheduled, so a request mixing an
        // accepted and a refused principal does nothing at all. This stays outside the catch-all
        // below so that a refusal answers 403 (or 400 when malformed) instead of a server error.
        for (Map<String, Object> props : items) {
            final String clusterName = clusterNameOf(props);
            final String principal   = principalOf(props);
            if (StringUtils.isBlank(clusterName)) throw new SystemException("cluster_name is required");
            if (StringUtils.isBlank(principal))   throw new SystemException("principal is required");
            principalPolicy.get().verify(clusterOf(clusterName), principal,
                    firstNonBlank(str(props.get(PROP_NAMESPACE)), str(props.get(PROP_NAMESPACE_NS))));
        }

        List<Long> requestIds = new ArrayList<>();

        for (Map<String, Object> props : items) {
            final String clusterName  = clusterNameOf(props);
            final String principal    = principalOf(props);
            final String kdcType      = firstNonBlank(str(props.get(PROP_KDC_TYPE)),
                    str(props.get(PROP_KDC_TYPE_NS)));
            final String defaultRealm = firstNonBlank(str(props.get(PROP_DEFAULT_REALM)),
                    str(props.get(PROP_DEFAULT_REALM_NS)));
            final String context      = firstNonBlank(str(props.get(PROP_CONTEXT)),
                    str(props.get(PROP_CONTEXT_NS)),
                    ("Kerberos Webhook Keytab generation"));
//            (principal == null
//                    ? "Kerberos Webhook Keytab generation"
//                    : "Kerberos Webhook Keytab generation" + principal));
            final int timeoutSecs     = firstInt(asInt(props.get(PROP_TIMEOUT)),
                    asInt(props.get(PROP_TIMEOUT_NS)),
                    600);

            final Cluster cluster = clusterOf(clusterName);

            try {
                final long requestId = actionManager.getNextRequestId();

                // Stage-level params
                Map<String, String> cmdParams = new LinkedHashMap<>();
                cmdParams.put("principal", principal);
                if (StringUtils.isNotBlank(kdcType))      cmdParams.put("kdc_type", kdcType);
                if (StringUtils.isNotBlank(defaultRealm)) cmdParams.put("default_realm", defaultRealm);

                final String commandParamsJson = GSON.toJson(cmdParams);
                final String hostParamsJson    = "{}";
                final String logDir            = BASE_LOG_DIR + File.separator + requestId;

                Stage stage = stageFactory.createNew(
                        requestId,
                        logDir,
                        cluster.getClusterName(),
                        cluster.getClusterId(),
                        context,
                        commandParamsJson,
                        hostParamsJson
                );
                stage.setStageId(1L);

                stage.addServerActionCommand(
                        GenerateAdhocKeytabServerAction.class.getName(), // action by FQCN
                        null,                                            // user
                        Role.AMBARI_SERVER_ACTION,
                        RoleCommand.EXECUTE,
                        cluster.getClusterName(),
                        new ServiceComponentHostServerActionEvent(null, System.currentTimeMillis()),                                            // SCH event (none for server action)
                        cmdParams,
                        "Generate Adhoc Keytab",
                        amc.findConfigurationTagsWithOverrides(cluster, null, null),
                        timeoutSecs,
                        false,
                        false
                );

                // Build clusterHostInfo JSON and let ActionManager do the rest
                final String clusterHostInfo =
                        org.apache.ambari.server.utils.StageUtils.getGson()
                                .toJson(org.apache.ambari.server.utils.StageUtils.getClusterHostInfo(cluster));

                List<RequestResourceFilter> filters = Collections.emptyList();
                Map<String, String> execParams = Collections.emptyMap();

                // Pick a stable label for "command" even though the Stage has a server action attached.
                // This label shows up in the Requests table and logs; it doesn’t drive the Stage itself.
                String commandLabel = "GENERATE_ADHOC_KEYTAB";

                ExecuteActionRequest executeActionRequest = new ExecuteActionRequest(cluster.getClusterName(), commandLabel, execParams, false);
                actionManager.sendActions(Collections.singletonList(stage), clusterHostInfo, executeActionRequest);
                response = new RequestStatusResponse(requestId);
                requestIds.add(requestId);

            } catch (Exception e) {
                throw new SystemException("Failed to enqueue adhoc keytab for '" + principal + "': " + e.getMessage(), e);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (requestIds.size() == 1) payload.put("id", requestIds.get(0));
        else                        payload.put("ids", requestIds);
        return getRequestStatus(response, null);
    }

    @Override
    public Set<Resource> getResources(Request request, Predicate predicate)
            throws SystemException, UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException {
        throw new UnsupportedOperationException("GET not supported for AdhocKeytab");
    }

    @Override
    public RequestStatus updateResources(Request request, Predicate predicate)
            throws SystemException, UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException {
        throw new UnsupportedOperationException("PUT not supported for AdhocKeytab");
    }


    // helpers

    /**
     * The cluster a request item acts on. Authorization and the operation both resolve it here so
     * that the permission check is made on the cluster that is really touched.
     */
    private static String clusterNameOf(Map<String, Object> props) {
        return firstNonBlank(str(props.get(PROP_CLUSTER_NAME)), str(props.get(PROP_CLUSTER_NAME_NS)));
    }

    private static String principalOf(Map<String, Object> props) {
        return firstNonBlank(str(props.get(PROP_PRINCIPAL)), str(props.get(PROP_PRINCIPAL_NS)));
    }

    private Cluster clusterOf(String clusterName) throws SystemException {
        try {
            return clusters.getCluster(clusterName);
        } catch (AmbariException e) {
            throw new SystemException("Cluster not found: " + clusterName, e);
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static String firstNonBlank(String... vals) { for (String v : vals) if (StringUtils.isNotBlank(v)) return v; return null; }
    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception ignore) { return null; }
    }
    private static int firstInt(Integer... vals) { for (Integer v : vals) if (v != null) return v; return 0; }
}
