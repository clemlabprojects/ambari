/**
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

package org.apache.ambari.view.k8s.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.HelmRepoRepo;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.apache.ambari.view.k8s.service.ViewConfigurationService;

import org.apache.ambari.view.k8s.utils.CommandUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MultivaluedMap;
import java.lang.reflect.Type;
import java.net.URI;
import java.time.Instant;
import java.util.*;

import static org.apache.ambari.view.k8s.utils.CommandUtils.AmbariConfigRef;
import static org.apache.ambari.view.k8s.utils.CommandUtils.DYNAMIC_SOURCE_MAP;

/**
 * Responsible for creating the Tree of Commands (Plans) and persisting them to DataStore.
 * Extracted from CommandService.
 */
public class CommandPlanFactory {
    private static final Logger LOG = LoggerFactory.getLogger(CommandPlanFactory.class);

    private final DataStore dataStore;
    private final ViewContext ctx;
    private final HelmRepoRepo repositoryDao;
    private final Gson gson;
    private final CommandUtils commandUtils;

    // Injected service to access webHook logic if needed inside planning
    private final WebHookConfigurationService webHookService;

    public CommandPlanFactory(ViewContext ctx, Gson gson, WebHookConfigurationService webHookService, CommandUtils commandUtils) {
        this.ctx = ctx;
        this.dataStore = ctx.getDataStore();
        this.repositoryDao = new HelmRepoRepo(ctx.getDataStore());
        this.gson = gson;
        this.webHookService = webHookService;
        this.commandUtils = commandUtils;
    }

    /**
     * The method is a submethod of submitRequest deploy command
     * it does create the helm install/upgrade command of the requested chart
     * it does execute a dry run and a real execution if the dryRun does successfully execute
     * the dryRun is done synchronously the real execution can be done async
     * @param cmd
     * @param repoId
     * @param isDrRun
     */
    public void createRealChartInstallationCommands(CommandEntity cmd, String repoId, boolean isDrRun){

        // Replace calls to 'store(e)' with local 'store(e)' helper in this class
        Type listType = new TypeToken<Map<String, Object>>(){}.getType();
        Map<String, Object> params;
        try {
            String rawParams = CommandUtils.resolveParamsPayload(cmd.getParamsJson());
            params = gson.fromJson(rawParams, listType);
            if (params == null) {
                params = new LinkedHashMap<>();
            }
        } catch (Exception e) {
            params = new LinkedHashMap<>();
        }

        // Resolve dynamic value tokens (e.g., AMBARI_KERBEROS_REALM -> helm path) for the main chart
        Map<String, String> resolvedDynamicOverrides = null;
        Object dynamicValuesObj = params.get("dynamicValues");
        List<String> dynamicValues = new ArrayList<>();
        if (dynamicValuesObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) dynamicValuesObj;
            for (Object o : l) {
                if (o instanceof String s && !s.isBlank()) {
                    dynamicValues.add(s.trim());
                }
            }
        }
        if (!dynamicValues.isEmpty()) {
            try {
                String baseUriStr = (String) params.get("_baseUri");
                Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(params.get("_callerHeaders"));
                String cluster = this.commandUtils.resolveClusterName(baseUriStr, authHeaders);
                var ambariActionClient = new AmbariActionClient(ctx, baseUriStr, cluster, authHeaders);

                resolvedDynamicOverrides = new LinkedHashMap<>();
                for (String dynamic : dynamicValues) {
                    String[] parts = dynamic.split(":", 2);
                    if (parts.length != 2) {
                        LOG.warn("Invalid dynamic value token '{}', expected 'SOURCE:helm.path'", dynamic);
                        continue;
                    }
                    String token = parts[0].trim();
                    String helmPath = parts[1].trim();
                    if (helmPath.isEmpty()) {
                        LOG.warn("Empty helm path in token '{}', skipping", dynamic);
                        continue;
                    }
                    AmbariConfigRef ref = DYNAMIC_SOURCE_MAP.get(token);
                    if (ref == null) {
                        LOG.warn("No mapping for dynamic token '{}', skipping property fetch", token);
                        continue;
                    }
                    try {
                        String v = ambariActionClient.getDesiredConfigProperty(cluster, ref.type, ref.key);
                        if (v == null || v.isBlank()) {
                            LOG.warn("Resolved empty value for token '{}' (type={}, key={}), skipping", token, ref.type, ref.key);
                            continue;
                        }
                        resolvedDynamicOverrides.put(helmPath, v);
                        LOG.info("Resolved dynamic '{}' -> {} = '{}'", token, helmPath, v);
                    } catch (Exception ex) {
                        LOG.warn("Failed to resolve token '{}' (type={}, key={}): {}", token, ref.type, ref.key, ex.toString());
                    }
                }
            } catch (Exception ex) {
                LOG.error("Could not resolve dynamic values for main chart: {}", ex.toString());
            }
        }
        if (resolvedDynamicOverrides != null && !resolvedDynamicOverrides.isEmpty()) {
            params.put("_dynamicOverrides", resolvedDynamicOverrides);
        }

        // Serialize once after enrichment
        String enrichedParamsJson = gson.toJson(params);

        CommandEntity dryRunChartInstall = new CommandEntity();
        String subId = UUID.randomUUID().toString();
        String id = cmd.getId() + "-" + subId;
        final String now = Instant.now().toString();
        String chart = (String) params.get("chart");
        String releaseName = (String) params.get("releaseName");
        String namespace = (String) params.get("namespace");

        dryRunChartInstall.setTitle("Chart Installation " + chart+ " Installation " );
        dryRunChartInstall.setId(id);
        if (isDrRun){
        dryRunChartInstall.setType(CommandType.HELM_DEPLOY_DRY_RUN.name());
        }else {
            dryRunChartInstall.setType(CommandType.HELM_DEPLOY.name());
        }
        dryRunChartInstall.setViewInstance(ctx.getInstanceName());

        //        propagate params from root to child (with dynamic overrides)
        dryRunChartInstall.setParamsJson(enrichedParamsJson);


        CommandStatusEntity dryRunChartInstallStatus = new CommandStatusEntity();
        dryRunChartInstallStatus.setId(id+"-status");
        dryRunChartInstallStatus.setAttempt(0);
        dryRunChartInstallStatus.setState(CommandState.PENDING.name());
        dryRunChartInstallStatus.setCreatedBy(ctx.getUsername());
        dryRunChartInstallStatus.setCreatedAt(now);
        dryRunChartInstallStatus.setUpdatedAt(now);
        dryRunChartInstallStatus.setWorkerId(null);

        dryRunChartInstall.setCommandStatusId(dryRunChartInstallStatus.getId());

        this.store(dryRunChartInstall);
        this.store(dryRunChartInstallStatus);

        Type listTypeCmds = new TypeToken<ArrayList<String>>(){}.getType();
        List<String> childCommands;
        try {
            childCommands = gson.fromJson(cmd.getChildListJson(), listTypeCmds);
            if (childCommands == null) {
                childCommands = new ArrayList<>();
            }
        } catch (Exception e) {
            childCommands = new ArrayList<>();
        }

        for (String childId : childCommands) {
            CommandEntity childCmd = findCommandById(childId);
            if (childCmd != null) {
                LOG.info("Found child command with id: {} for root command with id: {} ", childId, cmd.getId());
            }
        }
        childCommands.add(dryRunChartInstall.getId());
        cmd.setChildListJson(gson.toJson(childCommands));
        // Persist enriched params on the root as well so later steps reuse them
        cmd.setParamsJson(enrichedParamsJson);

        this.store(cmd);

    }

    /**
     * there are three steps each time
     * 1. CRDS check (if creds checks fails we do create the dry-run/install command)
     * 2. dry run of release installation
     * 3. release installation
     */
    public void createDependencyCommands(CommandEntity cmd, Object dependency, String dependencyName, String repoId, String commandsURL, MultivaluedMap<String,String> callerHeaders,
                                          URI baseUri) {
        // Note: You will need to access ConfigResolutionService.mergeDynamicOverrides via a passed instance
        // or make that method static utility.
        if (dependency instanceof Map) {
            // Use generic Map instead of LinkedTreeMap to avoid class loader issues
            @SuppressWarnings("unchecked")
            Map<String, Object> depMap = (Map<String, Object>) dependency;

            LOG.info("Creating commands for dependency: {} ", dependencyName);

            // Accessing the files of a dependency
            String chart = (String) depMap.get("chart");
            String chartVersion = (String) depMap.get("chartVersion");
            String imageTag = (String) depMap.get("imageTag");
            String imageGlobalRegistryProperty = (String) depMap.get("imageGlobalRegistryProperty");
            String imageRepository = (String) depMap.get("imageRepository");
            Object secretName = depMap.get("secretName");
            Object serviceAccountsObj = depMap.get("serviceAccounts");
            boolean isWebhook = false;
            Object isWebhookObj = depMap.get("isWebhook");

            if (isWebhookObj instanceof Boolean) {
                isWebhook = (Boolean) isWebhookObj;
            } else if (isWebhookObj instanceof String) {
                isWebhook = Boolean.parseBoolean((String) isWebhookObj);
            }
            List<String> serviceAccounts = new ArrayList<>();
            if (serviceAccountsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> serviceAccountsList = (List<Object>) serviceAccountsObj;
                for (Object sa : serviceAccountsList) {
                    if (sa instanceof String) {
                        serviceAccounts.add((String) sa);
                    }
                }
            }
            String chartNamespace = (String) depMap.get("namespace");
            LOG.info("Dependency details - Chart: {}, Version: {}, Namespace: {}, ImageTag: {}, ImageRepository: {}",
                    chart, chartVersion, chartNamespace, imageTag, imageRepository);


            // Handle the CRDs list safely
            Object crdsObj = depMap.get("crds");
            List<String> crds = new ArrayList<>();

            if (crdsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> crdsList = (List<Object>) crdsObj;
                for (Object crd : crdsList) {
                    if (crd instanceof String) {
                        crds.add((String) crd);
                    }
                }
            }

            List<String> images = new ArrayList<>();
            Object imagesObj = depMap.get("images");
            if (imagesObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> imagesList = (List<Object>) imagesObj;
                for (Object img : imagesList) {
                    if (img instanceof String) {
                        images.add((String) img);
                    }
                }
            }
            Object fullnameOverrideObj = depMap.get("fullnameOverride");
            String fullnameOverride = null;
            if (fullnameOverrideObj instanceof String) {
                fullnameOverride = (String) fullnameOverrideObj;
            }
            LOG.info("Dependency details - FullnameOverride: {}", fullnameOverride);
            // need to use sequential stream as we do insert/update object in the datastore.
            crds.stream().forEach(
                    crd -> {
                        LOG.info("Processing CRD: {} for dependency: {} ", crd, dependencyName);
                        // creating the HELM_DEPLOY_DEPENDENCY_CRD type CMD and it's status
                        String subId = UUID.randomUUID().toString();
                        String id = cmd.getId() + "-" + subId;
                        final String now = Instant.now().toString();
                        CommandEntity dependencyCrdCheck = new CommandEntity();
                        dependencyCrdCheck.setTitle("CRD "+ dependencyName+ " Installation" );
                        dependencyCrdCheck.setId(id);
                        dependencyCrdCheck.setType(CommandType.HELM_DEPLOY_DEPENDENCY_CRD.name());
                        dependencyCrdCheck.setViewInstance(ctx.getInstanceName());
                        Map<String, Object> params = new LinkedHashMap<>();
                        params.put("crd", crd);
                        params.put("crd-resource-path", chart+"-"+chartVersion+"-crds.yaml");
                        params.put("namespace", chartNamespace);
                        params.put("chartVersion", chartVersion);
                        params.put("imageTag", imageTag);
                        params.put("imageGlobalRegistryProperty", imageGlobalRegistryProperty);
                        params.put("imageRepository", imageRepository);
                        params.put("releaseName", dependencyName);
                        params.put("images", images);
                        dependencyCrdCheck.setParamsJson(gson.toJson(params));
                        LOG.info("Crd: {} should be installed in the namespace: {} for the dependency: {} ", crd, chartNamespace, dependencyName);


                        LOG.info("Dependency CRD command params are: {} ",dependencyCrdCheck.getParamsJson());
                        CommandStatusEntity dependencyCrdCheckStatus = new CommandStatusEntity();
                        dependencyCrdCheckStatus.setId(id+"-status");
                        dependencyCrdCheckStatus.setAttempt(0);
                        dependencyCrdCheckStatus.setState(CommandState.PENDING.name());
                        dependencyCrdCheckStatus.setCreatedBy(ctx.getUsername());
                        dependencyCrdCheckStatus.setCreatedAt(now);
                        dependencyCrdCheckStatus.setUpdatedAt(now);
                        dependencyCrdCheckStatus.setWorkerId(null);

                        dependencyCrdCheck.setCommandStatusId(dependencyCrdCheckStatus.getId());

                        // Fix the child commands handling
                        Type listType = new TypeToken<ArrayList<String>>(){}.getType();
                        List<String> childCommands;
                        try {
                            childCommands = gson.fromJson(cmd.getChildListJson(), listType);
                            if (childCommands == null) {
                                childCommands = new ArrayList<>();
                            }
                        } catch (Exception e) {
                            childCommands = new ArrayList<>();
                        }

                        childCommands.add(dependencyCrdCheck.getId());

                        LOG.info("Created CRD check command with id: {} for dependency: {} ", dependencyCrdCheck.getId(), dependencyName);
                        // saving both object (cdrCmd and its associated status)
                        store(dependencyCrdCheck);
                        store(dependencyCrdCheckStatus);

                        LOG.info("Adding CRD check command with id: {} to the root command with id: {} ", dependencyCrdCheck.getId(), cmd.getId());
                        // updating the cmd
                        cmd.setChildListJson(gson.toJson(childCommands));
                        store(cmd);
                    }
            );

            // getting cmd information so we can populate it later
            Type listType = new TypeToken<ArrayList<String>>(){}.getType();
            List<String> childCommands;
            try {
                childCommands = gson.fromJson(cmd.getChildListJson(), listType);
                if (childCommands == null) {
                    childCommands = new ArrayList<>();
                }
            } catch (Exception e) {
                childCommands = new ArrayList<>();
            }

            for (String childId : childCommands) {
                CommandEntity childCmd = findCommandById(childId);
                if (childCmd != null) {
                    LOG.info("Found child command with id: {} for root command with id: {} ", childId, cmd.getId());
                }
            }

            // creating the dependency dry run command
            CommandEntity helmChartDryRunCmd = new CommandEntity();
            String subId = UUID.randomUUID().toString();
            String id = cmd.getId() + "-" + subId;
            final String now = Instant.now().toString();
            helmChartDryRunCmd.setId(id);
            helmChartDryRunCmd.setType(CommandType.HELM_DEPLOY_DRY_RUN_DEPENDENCY_RELEASE.name());
            helmChartDryRunCmd.setTitle("DryRun of the dependency "+ dependencyName);
            helmChartDryRunCmd.setViewInstance(ctx.getInstanceName());
            LOG.info("Creating HelmDeployDryRun command with id: {} for dependency: {} ", id, dependencyName);
            Map<String, Object> params = new LinkedHashMap<>();
            HelmRepoEntity repository = repositoryDao.findById(repoId);
            params.put("chart", chart);
            params.put("namespace", chartNamespace);
            params.put("chartVersion", chartVersion);
            params.put("imageTag", imageTag);
            params.put("imageGlobalRegistryProperty", imageGlobalRegistryProperty);
            params.put("imageRepository", imageRepository);
            params.put("releaseName", dependencyName);
            params.put("images", images);
            params.put("isWebhook", isWebhook);
            params.put("fullnameOverride", fullnameOverride);

            /**
             * start to check if dynamic values are given and prepare parsing and fetching
             */
            Object dynamicValuesObj = depMap.get("dynamicValues");
            List<String> dynamicValues = new ArrayList<>();
            if (dynamicValuesObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> l = (List<Object>) dynamicValuesObj;
                for (Object o : l) {
                    if (o instanceof String s && !s.isBlank()) {
                        dynamicValues.add(s.trim()); // each string like "SRC:helm.path"
                    }
                }
            }
            Map<String, String> resolvedDynamicOverrides = null;

            if (!dynamicValues.isEmpty()) {
                LOG.info("Fetching cluster name from Ambari Server in order to fetch properties");
                String cluster = null;
                try {
                    cluster = this.commandUtils.resolveClusterName(baseUri.toString(), AmbariActionClient.toAuthHeaders(callerHeaders));
                } catch (Exception e) {
                    LOG.error("Could not fetch cluster name, dynamic Values will be ignored");
                }
                var ambariActionClient = new AmbariActionClient(ctx, baseUri.toString(), cluster, AmbariActionClient.toAuthHeaders(callerHeaders));
                LOG.info("Dynamic Values will be set with cluster name {}", cluster);

                resolvedDynamicOverrides = new LinkedHashMap<>();
                for (String dynamic : dynamicValues) {
                    String[] parts = dynamic.split(":", 2); // IMPORTANT: limit=2
                    if (parts.length != 2) {
                        LOG.warn("Invalid dynamic value token '{}', expected 'SOURCE:helm.path'", dynamic);
                        continue;
                    }
                    String token = parts[0].trim();
                    String helmPath = parts[1].trim();
                    if (helmPath.isEmpty()) {
                        LOG.warn("Empty helm path in token '{}', skipping", dynamic);
                        continue;
                    }
                    AmbariConfigRef ref = DYNAMIC_SOURCE_MAP.get(token);
                    if (ref == null) {
                        LOG.warn("No mapping for dynamic token '{}', skipping property fetch", token);
                        continue;
                    }
                    try {
                        String v = ambariActionClient.getDesiredConfigProperty(cluster, ref.type, ref.key);
                        if (v == null || v.isBlank()) {
                            LOG.warn("Resolved empty value for token '{}' (type={}, key={}), skipping", token, ref.type, ref.key);
                            continue;
                        }
                        resolvedDynamicOverrides.put(helmPath, v);
                        LOG.info("Resolved dynamic '{}' -> {} = '{}'", token, helmPath, v);
                    } catch (Exception ex) {
                        LOG.warn("Failed to resolve token '{}' (type={}, key={}): {}", token, ref.type, ref.key, ex.toString());
                    }
                }
            }

            // Attach resolved map once; both dry-run and run commands will reuse it
            if (resolvedDynamicOverrides != null && !resolvedDynamicOverrides.isEmpty()) {
                params.put("_dynamicOverrides", resolvedDynamicOverrides);
            }

            if (isWebhook){
                params.put("backend.url", commandsURL);
            }
            if (secretName != null){
                params.put("secretName", secretName);
            }
            if (serviceAccountsObj != null){
                params.put("serviceAccounts", serviceAccountsObj);
            }

            helmChartDryRunCmd.setParamsJson(gson.toJson(params));

            LOG.info("Dependency DryRun command params are: {} ",helmChartDryRunCmd.getParamsJson());
            CommandStatusEntity helmChartDryRunCmdStatus = new CommandStatusEntity();
            helmChartDryRunCmdStatus.setId(id+"-status");
            helmChartDryRunCmdStatus.setAttempt(0);
            helmChartDryRunCmdStatus.setState(CommandState.PENDING.name());
            helmChartDryRunCmdStatus.setCreatedBy(ctx.getUsername());
            helmChartDryRunCmdStatus.setCreatedAt(now);
            helmChartDryRunCmdStatus.setUpdatedAt(now);
            helmChartDryRunCmdStatus.setWorkerId(null);

            helmChartDryRunCmd.setCommandStatusId(helmChartDryRunCmdStatus.getId());

            // creating the dependency run command
            CommandEntity helmChartRunCmd = new CommandEntity();
            subId = UUID.randomUUID().toString();
            id = cmd.getId() + "-" + subId;

            helmChartRunCmd.setId(id);
            helmChartRunCmd.setType(CommandType.HELM_DEPLOY_DEPENDENCY_RELEASE.name());
            helmChartRunCmd.setTitle("Run of the dependency "+ dependencyName);
            helmChartRunCmd.setViewInstance(ctx.getInstanceName());

            helmChartRunCmd.setParamsJson(gson.toJson(params));

            CommandStatusEntity helmChartRunCmdStatus = new CommandStatusEntity();
            helmChartRunCmdStatus.setId(id+"-status");
            helmChartRunCmdStatus.setAttempt(0);
            helmChartRunCmdStatus.setState(CommandState.PENDING.name());
            helmChartRunCmdStatus.setCreatedBy(ctx.getUsername());
            helmChartRunCmdStatus.setCreatedAt(now);
            helmChartRunCmdStatus.setUpdatedAt(now);
            helmChartRunCmdStatus.setWorkerId(null);

            helmChartRunCmd.setCommandStatusId(helmChartRunCmdStatus.getId());

            childCommands.add(helmChartDryRunCmd.getId());
            childCommands.add(helmChartRunCmd.getId());
            cmd.setChildListJson(gson.toJson(childCommands));
            store(cmd);
            store(helmChartDryRunCmd);
            store(helmChartDryRunCmdStatus);
            store(helmChartRunCmd);
            store(helmChartRunCmdStatus);

            LOG.info("Dependency Chart={} create with properties Version={}, Namespace={}, CRDs={}",
                    chart, chartVersion, chartNamespace, crds);
        } else {
            LOG.warn("Dependency is not a JSON object: {}", dependency);
        }
    }

    /**
     * Create a no-op dependency step that marks the dependency as already satisfied.
     * This is used when a shared dependency release is detected and install should be skipped.
     *
     * @param cmd root command
     * @param dependencyName helm release name for the dependency
     * @param dependencySpec dependency spec map (for namespace/context)
     * @param reason message to attach to the command status
     */
    public void createDependencySatisfiedCommand(CommandEntity cmd,
                                                 String dependencyName,
                                                 Map<String, Object> dependencySpec,
                                                 String reason) {
        final String now = Instant.now().toString();
        String id = cmd.getId() + "-" + UUID.randomUUID();

        CommandEntity satisfied = new CommandEntity();
        satisfied.setId(id);
        satisfied.setType(CommandType.DEPENDENCY_SATISFIED.name());
        satisfied.setTitle("Dependency already present: " + dependencyName);
        satisfied.setViewInstance(ctx.getInstanceName());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("releaseName", dependencyName);
        if (dependencySpec != null) {
            Object namespace = dependencySpec.get("namespace");
            if (namespace != null) {
                params.put("namespace", namespace);
            }
        }
        params.put("reason", reason);
        satisfied.setParamsJson(gson.toJson(params));

        CommandStatusEntity status = new CommandStatusEntity();
        status.setId(id + "-status");
        status.setAttempt(0);
        status.setState(CommandState.SUCCEEDED.name());
        status.setCreatedBy(ctx.getUsername());
        status.setCreatedAt(now);
        status.setUpdatedAt(now);
        status.setMessage(reason);
        status.setWorkerId(null);
        satisfied.setCommandStatusId(status.getId());

        Type listType = new TypeToken<ArrayList<String>>() {}.getType();
        List<String> childCommands;
        try {
            childCommands = gson.fromJson(cmd.getChildListJson(), listType);
            if (childCommands == null) {
                childCommands = new ArrayList<>();
            }
        } catch (Exception e) {
            childCommands = new ArrayList<>();
        }
        childCommands.add(satisfied.getId());
        cmd.setChildListJson(gson.toJson(childCommands));

        store(cmd);
        store(status);
        store(satisfied);

        LOG.info("Dependency {} already present; added satisfied step {}", dependencyName, satisfied.getId());
    }

    /**
     * this method does create the ranger plugin repository creation sub-command
     * each helm chart can have a plugin ranger plugin which can be wired to a ranger repository
     * the ranger repository will be created if it does not exist
     * @param rootCommand
     * @param rangerRequest
     */
    public void createRangerPluginRepository(CommandEntity rootCommand,
                                              Map<String, Map<String, Object>> rangerRequest,
                                              Map<String, Object> params,
                                              List<String> childCommands) {
        LOG.info("Planning Ranger Plugin Repository Action");

        final String now = Instant.now().toString();
        String id = rootCommand.getId() + "-" + UUID.randomUUID();

        // Just extend existing params
//        params.put("_rangerSpec", gson.toJson(rangerRequest));

        CommandEntity rangerRequirements = new CommandEntity();
        rangerRequirements.setTitle("Ranger Repository Creation");
        rangerRequirements.setId(id);
        rangerRequirements.setType(CommandType.RANGER_REPOSITORY_CREATION.name());
        rangerRequirements.setViewInstance(ctx.getInstanceName());
        rangerRequirements.setParamsJson(gson.toJson(params));

        CommandStatusEntity rangerRequirementsStatus = new CommandStatusEntity();
        rangerRequirementsStatus.setId(id + "-status");
        rangerRequirementsStatus.setAttempt(0);
        rangerRequirementsStatus.setState(CommandState.PENDING.name());
        rangerRequirementsStatus.setCreatedBy(ctx.getUsername());
        rangerRequirementsStatus.setCreatedAt(now);
        rangerRequirementsStatus.setUpdatedAt(now);
        rangerRequirementsStatus.setWorkerId(null);

        rangerRequirements.setCommandStatusId(rangerRequirementsStatus.getId());

        store(rangerRequirementsStatus);
        store(rangerRequirements);

        childCommands.add(rangerRequirements.getId());
    }

    /**
     * this method is a submethod of submitDeploy which handle the creation of the helm repository commands
     * @param cmd
     * @param repoId
     * @return
     */
    public String createHelmRepoCommand(CommandEntity cmd, String repoId){
        String subId = UUID.randomUUID().toString();
        String id = cmd.getId() + "-" + subId;
        final String now = Instant.now().toString();
        LOG.info("Creating HelmRepoLogin command with id: {} for repoId: {}", id, repoId);
        CommandEntity helmRepoLogin = new CommandEntity();
        helmRepoLogin.setId(id);
        helmRepoLogin.setViewInstance(ctx.getInstanceName());
        helmRepoLogin.setType(CommandType.HELM_REPO_LOGIN.name());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("repoId", repoId);
        LOG.info("HelmRepoLogin params are: {} ",params);
        helmRepoLogin.setParamsJson(gson.toJson(params));

        CommandStatusEntity cmdStatus = new CommandStatusEntity();
        cmdStatus.setViewInstance(ctx.getInstanceName());
        cmdStatus.setState(CommandState.PENDING.name());
        cmdStatus.setCreatedBy(ctx.getUsername());
        cmdStatus.setCreatedAt(now);
        cmdStatus.setUpdatedAt(now);
        cmdStatus.setAttempt(0);
        cmdStatus.setId(id + "-status");

        helmRepoLogin.setCommandStatusId(cmdStatus.getId());
        store(cmdStatus);
        store(helmRepoLogin);
        return id;

    }

    /**
     * Creates a post-deploy step that provisions (or updates) an Ambari view instance
     * for the just-deployed Helm release.  The step is appended to the root command's
     * child list after the main HELM_DEPLOY command, so it only runs once the chart is
     * successfully installed.
     *
     * <p>The {@code viewSpec} comes from {@code service.json → postDeploy.ambariViewInstance}
     * and supports the following keys:
     * <ul>
     *   <li>{@code viewName}           — Ambari view name (default: SQL-ASSISTANT-VIEW)</li>
     *   <li>{@code viewVersion}        — View version string (default: 1.0.0.0)</li>
     *   <li>{@code serviceUrlTemplate} — URL template with {@code {{releaseName}}} and {@code {{namespace}}} tokens</li>
     * </ul>
     *
     * @param rootCmd     root command entity (must already be persisted)
     * @param viewSpec    postDeploy.ambariViewInstance spec map from service.json
     * @param releaseName Helm release name (becomes the Ambari view instance name)
     * @param namespace   Kubernetes namespace (used for URL template resolution)
     */
    public void createAmbariViewProvisionCommand(
            CommandEntity rootCmd,
            Map<String, Object> viewSpec,
            String releaseName,
            String namespace
    ) {
        final String now = Instant.now().toString();
        String id = rootCmd.getId() + "-" + UUID.randomUUID();

        // Resolve the service URL template — supports {{releaseName}} and {{namespace}}
        String serviceUrlTemplate = (String) viewSpec.getOrDefault(
                "serviceUrlTemplate",
                "http://{{releaseName}}-ollama.{{namespace}}.svc.cluster.local:8090"
        );
        String serviceUrl = serviceUrlTemplate
                .replace("{{releaseName}}", releaseName)
                .replace("{{namespace}}", namespace);

        String viewName    = (String) viewSpec.getOrDefault("viewName",    "SQL-ASSISTANT-VIEW");
        String viewVersion = (String) viewSpec.getOrDefault("viewVersion", "1.0.0.0");
        String instanceName = viewSpec.containsKey("instanceName")
                ? (String) viewSpec.get("instanceName")
                : releaseName;
        String instanceLabel = "SQL Assistant — " + releaseName;
        String instanceDesc  = "Auto-provisioned after Helm deploy of " + releaseName
                + " in namespace " + namespace;

        CommandEntity cmd = new CommandEntity();
        cmd.setId(id);
        cmd.setViewInstance(ctx.getInstanceName());
        cmd.setType(CommandType.AMBARI_VIEW_PROVISION.name());
        cmd.setTitle("Provision Ambari view instance: " + releaseName);

        // Build params — copy auth context from root so the step can call Ambari REST
        Map<String, Object> params = new LinkedHashMap<>();
        Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
        try {
            String rawRoot = CommandUtils.resolveParamsPayload(rootCmd.getParamsJson());
            Map<String, Object> rootParams = gson.fromJson(rawRoot, mapType);
            if (rootParams != null) {
                if (rootParams.containsKey("_baseUri"))       params.put("_baseUri",       rootParams.get("_baseUri"));
                if (rootParams.containsKey("_callerHeaders")) params.put("_callerHeaders", rootParams.get("_callerHeaders"));
            }
        } catch (Exception e) {
            LOG.warn("Could not copy auth context to AMBARI_VIEW_PROVISION params for {}: {}", id, e.toString());
        }
        params.put("viewName",             viewName);
        params.put("viewVersion",          viewVersion);
        params.put("instanceName",         instanceName);
        params.put("instanceLabel",        instanceLabel);
        params.put("instanceDescription",  instanceDesc);
        params.put("serviceUrl",           serviceUrl);

        cmd.setParamsJson(gson.toJson(params));

        CommandStatusEntity status = new CommandStatusEntity();
        status.setId(id + "-status");
        status.setAttempt(0);
        status.setState(CommandState.PENDING.name());
        status.setCreatedBy(ctx.getUsername());
        status.setCreatedAt(now);
        status.setUpdatedAt(now);
        cmd.setCommandStatusId(status.getId());

        // Append to root's child list
        Type listType = new TypeToken<ArrayList<String>>(){}.getType();
        List<String> children;
        try {
            children = gson.fromJson(rootCmd.getChildListJson(), listType);
            if (children == null) children = new ArrayList<>();
        } catch (Exception e) {
            children = new ArrayList<>();
        }
        children.add(id);
        rootCmd.setChildListJson(gson.toJson(children));

        store(status);
        store(cmd);
        store(rootCmd);

        LOG.info("Added AMBARI_VIEW_PROVISION step {} — will provision view instance '{}' at '{}'",
                id, releaseName, serviceUrl);
    }

    // Helper to store within this factory
    private void store(Object e) {
        try {
            if (e instanceof CommandEntity) {
                CommandEntity cmd = (CommandEntity) e;
                String rawParams = cmd.getParamsJson();
                if (rawParams != null && !rawParams.startsWith("@@file:")) {
                    try {
                        ViewConfigurationService cfg = new ViewConfigurationService(ctx);
                        String base = cfg.getConfigurationDirectoryPath();
                        Path paramsDir = Paths.get(base, "params");
                        Files.createDirectories(paramsDir);
                        Path filePath = paramsDir.resolve(cmd.getId() + "-params.json");
                        Files.writeString(filePath, rawParams, StandardCharsets.UTF_8);
                        cmd.setParamsJson("@@file:" + filePath.toString());
                        LOG.info("Persisting params for command {} to file {}", cmd.getId(), filePath);
                    } catch (Exception ioEx) {
                        LOG.warn("Could not externalize paramsJson for {}: {}", cmd.getId(), ioEx.toString());
                    }
                }
                dataStore.store(cmd);
            } else {
                dataStore.store(e);
            }
        }
        catch (PersistenceException ex) { LOG.error("Persist error", ex); }
    }

    private CommandEntity findCommandById(String id) {
        try { return dataStore.find(CommandEntity.class, id); }
        catch (PersistenceException ex) { return null; }
    }

    // -------------------- global config management ----------------
    /**
     * Creates the step to materialize Global Configurations (DB -> K8s Secret).
     */
    public String createConfigMaterializeCommand(CommandEntity rootCmd,
                                                 String serviceName,
                                                 String releaseName,
                                                 String namespace,
                                                 Map<String, Object> stackOverrides) {
        String subId = UUID.randomUUID().toString();
        String id = rootCmd.getId() + "-" + subId;

        CommandEntity cmd = new CommandEntity();
        cmd.setId(id);
        cmd.setViewInstance(ctx.getInstanceName());
        cmd.setType(CommandType.CONFIG_MATERIALIZE.name());
        cmd.setTitle("Materialize Configurations");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("serviceName", serviceName);
        params.put("releaseName", releaseName);
        params.put("namespace", namespace);
        // Pass the user's Step 3 overrides to the execution engine
        if (stackOverrides != null) params.put("stackOverrides", stackOverrides);

        cmd.setParamsJson(gson.toJson(params));

        CommandStatusEntity status = new CommandStatusEntity();
        status.setId(id + "-status");
        status.setState(CommandState.PENDING.name());
        status.setCreatedBy(ctx.getUsername());
        status.setCreatedAt(Instant.now().toString());
        status.setUpdatedAt(Instant.now().toString());
        status.setAttempt(0);

        cmd.setCommandStatusId(status.getId());
        store(status);
        store(cmd);
        return id;
    }
}
