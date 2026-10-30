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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MultivaluedMap;
import java.lang.reflect.Type;
import java.net.URI;
import java.time.Instant;
import java.util.*;

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
    /** Resolves the dynamic values of the release chart and of its dependencies. */
    private final DynamicValuesResolver dynamicValuesResolver;

    /**
     * Constructs a {@code CommandPlanFactory} with the services required for building command plans.
     *
     * @param ctx            the Ambari view context providing data store access and instance metadata
     * @param gson           the Gson instance used for JSON serialisation of command params
     * @param webHookService the webhook service used when building webhook-related sub-commands
     * @param commandUtils   utility helper for resolving cluster names and Ambari properties
     */
    public CommandPlanFactory(ViewContext ctx, Gson gson, WebHookConfigurationService webHookService, CommandUtils commandUtils) {
        this.ctx = ctx;
        this.dataStore = ctx.getDataStore();
        this.repositoryDao = new HelmRepoRepo(ctx.getDataStore());
        this.gson = gson;
        this.webHookService = webHookService;
        this.commandUtils = commandUtils;
        this.dynamicValuesResolver = new DynamicValuesResolver(ctx, commandUtils);
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
        Map<String, Object> params = rootParamsWithDynamicOverrides(cmd);
        String chart = (String) params.get("chart");
        if (isDrRun) {
            appendReleaseStep(cmd, params, CommandType.HELM_DEPLOY_DRY_RUN, "Helm: validate chart " + chart + " (dry-run)");
        } else {
            appendReleaseStep(cmd, params, CommandType.HELM_DEPLOY, "Helm: install chart " + chart);
        }
    }

    /**
     * The GitOps end of a release deploy, in place of the Helm dry run and install: the commit of the HelmRelease
     * (with the same parameters, dynamic values included, as the Helm install would get), then, in the Flux layout,
     * the wait for Flux to apply it. A deploy written with a GitOps profile ends at the commit: what reads the
     * repository (a pipeline, Argo CD, an operator syncing by hand) deploys it, at a time KDPS does not know.
     *
     * @param cmd            root command
     * @param waitForFlux    whether Flux applies the commit (the Flux layout), so the wait is planned
     * @param timeoutSeconds how long the wait lasts before it fails
     * @param pollSeconds    how often it reads the HelmRelease
     */
    public void createGitOpsReleaseCommands(CommandEntity cmd, boolean waitForFlux, int timeoutSeconds, int pollSeconds) {
        Map<String, Object> params = rootParamsWithDynamicOverrides(cmd);
        String releaseName = (String) params.get("releaseName");
        String namespace = (String) params.get("namespace");
        appendReleaseStep(cmd, params, CommandType.GITOPS_COMMIT, "GitOps: commit HelmRelease " + releaseName + " to Git");
        if (!waitForFlux) {
            return;
        }

        Map<String, Object> waitParams = new LinkedHashMap<>();
        waitParams.put("releaseName", releaseName);
        waitParams.put("namespace", namespace);
        waitParams.put("timeoutSeconds", timeoutSeconds);
        waitParams.put("pollSeconds", pollSeconds);
        appendStep(cmd, gson.toJson(waitParams), CommandType.FLUX_WAIT_READY,
                "Flux: wait for HelmRelease " + releaseName + " to be ready");
    }

    /**
     * The root command's parameters with the service's dynamic values (e.g. AMBARI_KERBEROS_REALM -> helm path)
     * resolved into {@code _dynamicOverrides}.
     */
    private Map<String, Object> rootParamsWithDynamicOverrides(CommandEntity cmd) {
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
        String baseUriStr = params.get("_baseUri") instanceof String s ? s : null;
        Map<String, String> resolvedDynamicOverrides = dynamicValuesResolver.resolve(params.get("dynamicValues"),
                params.get("formValues"), baseUriStr, AmbariActionClient.toAuthHeaders(params.get("_callerHeaders")),
                "the release chart");
        if (!resolvedDynamicOverrides.isEmpty()) {
            params.put("_dynamicOverrides", resolvedDynamicOverrides);
        }
        return params;
    }

    /**
     * Appends a release step that carries the root's parameters, dynamic values included, and keeps those on the root
     * as well so later steps reuse them.
     */
    private void appendReleaseStep(CommandEntity cmd, Map<String, Object> params, CommandType type, String title) {
        // Serialize once after enrichment
        String enrichedParamsJson = gson.toJson(params);
        cmd.setParamsJson(enrichedParamsJson);
        appendStep(cmd, enrichedParamsJson, type, title);
    }

    /** Creates a pending step and appends it to the root command's plan. */
    private void appendStep(CommandEntity cmd, String paramsJson, CommandType type, String title) {
        String id = cmd.getId() + "-" + UUID.randomUUID();
        final String now = Instant.now().toString();

        CommandEntity step = new CommandEntity();
        step.setId(id);
        step.setType(type.name());
        step.setTitle(title);
        step.setViewInstance(ctx.getInstanceName());
        step.setParamsJson(paramsJson);

        CommandStatusEntity status = new CommandStatusEntity();
        status.setId(id + "-status");
        status.setAttempt(0);
        status.setState(CommandState.PENDING.name());
        status.setCreatedBy(ctx.getUsername());
        status.setCreatedAt(now);
        status.setUpdatedAt(now);
        status.setWorkerId(null);

        step.setCommandStatusId(status.getId());

        this.store(step);
        this.store(status);

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
        childCommands.add(id);
        cmd.setChildListJson(gson.toJson(childCommands));

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
                        dependencyCrdCheck.setTitle("Kubernetes: install CRDs for " + dependencyName);
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


                        LOG.info("Dependency CRD command params are: {} ", HelmSetValues.redactJson(dependencyCrdCheck.getParamsJson()));
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
            helmChartDryRunCmd.setTitle("Helm: validate dependency " + dependencyName + " (dry-run)");
            helmChartDryRunCmd.setViewInstance(ctx.getInstanceName());
            LOG.info("Creating HelmDeployDryRun command with id: {} for dependency: {} ", id, dependencyName);
            Map<String, Object> params = dependencyReleaseParams(cmd, depMap, dependencyName, commandsURL, callerHeaders, baseUri);

            helmChartDryRunCmd.setParamsJson(gson.toJson(params));

            LOG.info("Dependency DryRun command params are: {} ", HelmSetValues.redactJson(helmChartDryRunCmd.getParamsJson()));
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
            helmChartRunCmd.setTitle("Helm: install dependency " + dependencyName);
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
     * The parameters of the Helm steps that install a dependency: its chart, version and namespace, its image settings,
     * the keytab webhook's backend URL, its image pull Secret and service accounts, and its dynamic values resolved
     * from Ambari (or the selected platform context) into {@code _dynamicOverrides}. A GitOps deploy keeps them for its
     * commit, which writes into the dependency's HelmRelease what its Helm install would receive.
     *
     * @param cmd            root command, whose form values name the platform context of the deploy
     * @param depMap         the dependency as the service definition declares it
     * @param dependencyName the dependency's release name
     * @param commandsURL    the view's commands URL, which the keytab webhook calls back
     * @return the parameters of the dependency's Helm steps
     */
    public Map<String, Object> dependencyReleaseParams(CommandEntity cmd, Map<String, Object> depMap, String dependencyName,
                                                       String commandsURL, MultivaluedMap<String, String> callerHeaders,
                                                       URI baseUri) {
        Object isWebhookObj = depMap.get("isWebhook");
        boolean isWebhook = isWebhookObj instanceof Boolean b ? b
                : isWebhookObj instanceof String s && Boolean.parseBoolean(s);
        List<String> images = new ArrayList<>();
        if (depMap.get("images") instanceof List<?> imagesList) {
            for (Object img : imagesList) {
                if (img instanceof String s) {
                    images.add(s);
                }
            }
        }
        Object secretName = depMap.get("secretName");
        Object serviceAccountsObj = depMap.get("serviceAccounts");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chart", depMap.get("chart"));
        params.put("namespace", depMap.get("namespace"));
        params.put("chartVersion", depMap.get("chartVersion"));
        params.put("imageTag", depMap.get("imageTag"));
        params.put("imageGlobalRegistryProperty", depMap.get("imageGlobalRegistryProperty"));
        params.put("imageRepository", depMap.get("imageRepository"));
        params.put("releaseName", dependencyName);
        params.put("images", images);
        params.put("isWebhook", isWebhook);
        params.put("fullnameOverride", depMap.get("fullnameOverride") instanceof String s ? s : null);

        // Dependency charts inherit the parent deploy's platform context (carried in the root command's formValues).
        Object parentFormValues = null;
        if (!DynamicValuesResolver.entriesOf(depMap.get("dynamicValues")).isEmpty()) {
            try {
                Map<String, Object> rootP = gson.fromJson(CommandUtils.resolveParamsPayload(cmd.getParamsJson()),
                        new TypeToken<Map<String, Object>>(){}.getType());
                if (rootP != null) parentFormValues = rootP.get("formValues");
            } catch (Exception ignore) {
                // no parent form values → managed/default behaviour
            }
        }
        Map<String, String> resolvedDynamicOverrides = dynamicValuesResolver.resolve(depMap.get("dynamicValues"),
                parentFormValues, baseUri == null ? null : baseUri.toString(),
                AmbariActionClient.toAuthHeaders(callerHeaders), "dependency " + dependencyName);

        // Attach resolved map once; both dry-run and run commands will reuse it
        if (!resolvedDynamicOverrides.isEmpty()) {
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
        return params;
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
        // A skipped/already-present dependency does no work, so it no longer gets its own (empty,
        // pre-succeeded) sub-step in the operation tree — that just added a click-through step with
        // nothing to show. Instead the skip is recorded as a line in the operation's aggregate log,
        // where the operator sees WHY the dependency was not installed alongside the real steps.
        String ns = dependencySpec != null && dependencySpec.get("namespace") != null
                ? String.valueOf(dependencySpec.get("namespace")) : null;
        String line = "Dependency '" + dependencyName + "'"
                + (ns != null ? " (namespace " + ns + ")" : "")
                + " skipped: " + reason;
        try {
            CommandLogService.get(ctx).append(cmd.getId(), line);
        } catch (Exception e) {
            LOG.debug("Could not log skipped dependency {} to {}: {}", dependencyName, cmd.getId(), e.toString());
        }
        LOG.info("Dependency {} already present/skipped ({}); logged to operation, no sub-step created",
                dependencyName, reason);
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
        rangerRequirements.setTitle("Ranger: create plugin repository");
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
     * Plans one {@code OM_RANGER_TAGSYNC_REGISTER} child command per
     * {@code type: "ranger-tagsync-source"} entry in the service.json {@code ranger} block.
     *
     * <p>The step body reads the OM ingestion-bot JWT by exec-ing into the
     * airflow scheduler pod, then writes the source's endpoint/JWT/class into
     * Ambari's {@code ranger-tagsync-site} config and triggers a Ranger TagSync
     * component restart so the source is picked up.
     *
     * <p><b>Plan ordering matters:</b> because the JWT exec needs a Running
     * airflow scheduler pod, callers MUST queue this AFTER
     * {@link #createRealChartInstallationCommands} (post-deploy), not before.
     * Self-managing {@code rootCommand.childListJson} (same pattern as
     * {@link #createAmbariViewProvisionCommand}) ensures the step actually
     * appears in the tree when called from the post-deploy block, where
     * submitDeploy has already done its last persist.
     *
     * <p>Idempotency comes from the step body — re-running with the same source
     * name detects no diff and skips the config write + restart.
     *
     * <p>A release with Ranger TagSync turned off gets the same step as a check
     * ({@code _tagSyncCheckOnly} in {@code params}): it only reads the cluster's
     * {@code ranger-tagsync-site}, and its title says so.
     *
     * @param rootCommand   parent command this step hangs off
     * @param rangerRequest the service.json {@code ranger} map (key → entry spec)
     * @param params        root params propagated to the step (must carry {@code _baseUri},
     *                      {@code _cluster}, {@code _callerHeaders}, {@code namespace},
     *                      {@code releaseName}, {@code _tagSyncSettings})
     */
    public void createRangerTagSyncRegister(CommandEntity rootCommand,
                                            Map<String, Map<String, Object>> rangerRequest,
                                            Map<String, Object> params) {
        if (rangerRequest == null || rangerRequest.isEmpty()) {
            return;
        }
        Type listType = new TypeToken<ArrayList<String>>(){}.getType();

        for (Map.Entry<String, Map<String, Object>> entry : rangerRequest.entrySet()) {
            Map<String, Object> spec = entry.getValue();
            if (spec == null) continue;
            Object typeRaw = spec.get("type");
            if (typeRaw == null || !"ranger-tagsync-source".equals(String.valueOf(typeRaw))) {
                continue;
            }

            LOG.info("Planning Ranger TagSync source registration: name='{}'", entry.getKey());

            final String now = Instant.now().toString();
            String id = rootCommand.getId() + "-tagsync-" + UUID.randomUUID();

            // Carry the per-entry spec into the step's params; the step body needs the
            // source class, cm_name, and any sourcename mapper.
            Map<String, Object> stepParams = new LinkedHashMap<>(params);
            stepParams.put("_tagSyncEntryKey", entry.getKey());
            stepParams.put("_tagSyncSpec", spec);

            CommandEntity tagSyncCmd = new CommandEntity();
            tagSyncCmd.setTitle("true".equalsIgnoreCase(String.valueOf(params.get("_tagSyncCheckOnly")))
                    ? "Ranger: check TagSync configuration"
                    : "Ranger: register TagSync source '" + entry.getKey() + "'");
            tagSyncCmd.setId(id);
            tagSyncCmd.setType(CommandType.OM_RANGER_TAGSYNC_REGISTER.name());
            tagSyncCmd.setViewInstance(ctx.getInstanceName());
            tagSyncCmd.setParamsJson(gson.toJson(stepParams));

            CommandStatusEntity status = new CommandStatusEntity();
            status.setId(id + "-status");
            status.setAttempt(0);
            status.setState(CommandState.PENDING.name());
            status.setCreatedBy(ctx.getUsername());
            status.setCreatedAt(now);
            status.setUpdatedAt(now);
            status.setWorkerId(null);

            tagSyncCmd.setCommandStatusId(status.getId());

            // Append to root's child list — mirrors createAmbariViewProvisionCommand /
            // createOmAtlasFederationRegister. submitDeploy's post-deploy block does
            // not re-store rootCommand after itself, so each plan-factory method
            // queued there must persist its own addition.
            List<String> children;
            try {
                children = gson.fromJson(rootCommand.getChildListJson(), listType);
                if (children == null) children = new ArrayList<>();
            } catch (Exception e) {
                children = new ArrayList<>();
            }
            children.add(id);
            rootCommand.setChildListJson(gson.toJson(children));

            store(status);
            store(tagSyncCmd);
            store(rootCommand);
        }
    }

    /**
     * Plans a single {@code ATLAS_USER_PROVISION_OM} child command — writes the
     * OM federation user into Ambari's {@code atlas-env} (the ODP ATLAS stack
     * scripts materialize it into {@code users-credentials.properties} on the
     * next Atlas restart). Self-persists rootCommand.childListJson.
     */
    public void createAtlasUserProvisionOm(CommandEntity rootCommand, Map<String, Object> params) {
        queueAtlasFederationStep(rootCommand, params,
                CommandType.ATLAS_USER_PROVISION_OM,
                "Atlas: provision OpenMetadata federation user",
                "-atlas-user-prov-");
    }

    /**
     * Plans a single {@code ATLAS_SIMPLE_AUTHZ_GRANT_OM} child command — verifies
     * the simple-authz grant is wired (the actual file write happens in the ODP
     * ATLAS metadata.py hook on Atlas restart).
     */
    public void createAtlasSimpleAuthzGrantOm(CommandEntity rootCommand, Map<String, Object> params) {
        queueAtlasFederationStep(rootCommand, params,
                CommandType.ATLAS_SIMPLE_AUTHZ_GRANT_OM,
                "Atlas: grant OpenMetadata user in simple-authz policy",
                "-atlas-simple-authz-");
    }

    /**
     * Plans a single {@code RANGER_POLICY_CREATE_ATLAS_OM_READ} child command —
     * creates a read-only Ranger policy in the Atlas service repo for the OM
     * federation principal (basic username OR Kerberos principal depending on
     * Atlas auth mode).
     */
    public void createRangerPolicyCreateAtlasOmRead(CommandEntity rootCommand, Map<String, Object> params) {
        queueAtlasFederationStep(rootCommand, params,
                CommandType.RANGER_POLICY_CREATE_ATLAS_OM_READ,
                "Ranger: create OpenMetadata read-only policy on Atlas repo",
                "-ranger-atlas-policy-");
    }

    /**
     * Plans a single {@code RANGER_POLICY_GRANT_VIA_AMBARI} child command — grants the OM
     * federation user read access on the Atlas service repo by delegating to the Ambari
     * server (which holds the Ranger admin password). Used for a MANAGED platform context.
     */
    public void createRangerPolicyGrantViaAmbari(CommandEntity rootCommand, Map<String, Object> params) {
        queueAtlasFederationStep(rootCommand, params,
                CommandType.RANGER_POLICY_GRANT_VIA_AMBARI,
                "Ranger: grant OpenMetadata read access on Atlas repo",
                "-ranger-atlas-grant-");
    }

    /**
     * Plans a single {@code RANGER_POLICY_GRANT_HIVE_READ} child command — grants the deploying
     * service's Kerberos principal {@code select} on the Hive Ranger service repo by delegating
     * to the Ambari server (which holds the Ranger admin password). Only for a service that reads
     * Hive as its own identity and cannot impersonate the end user (Trino's Hive catalog); one that
     * impersonates needs no such grant. Reuses {@link #queueAtlasFederationStep} — a generic
     * single-step queuer despite the Atlas-flavoured name.
     */
    public void createRangerPolicyGrantHiveRead(CommandEntity rootCommand, Map<String, Object> params) {
        queueAtlasFederationStep(rootCommand, params,
                CommandType.RANGER_POLICY_GRANT_HIVE_READ,
                "Ranger: grant service identity SELECT on Hive repo",
                "-ranger-hive-grant-");
    }

    /**
     * Plans a single {@code RANGER_POLICY_GRANT_TRINO_IMPERSONATE} child command — grants the
     * deploying service's Trino auth principal {@code impersonate} on the Trino Ranger service repo
     * so a Superset-style Trino database connection can run each user's queries as that user. Dual
     * path (context direct-REST vs Ambari-server) is chosen at execute time. Reuses the generic
     * {@link #queueAtlasFederationStep} single-step queuer.
     */
    public void createRangerPolicyGrantTrinoImpersonate(CommandEntity rootCommand, Map<String, Object> params) {
        queueAtlasFederationStep(rootCommand, params,
                CommandType.RANGER_POLICY_GRANT_TRINO_IMPERSONATE,
                "Ranger: grant Superset impersonate on Trino repo",
                "-ranger-trino-grant-");
    }

    /**
     * Plans a single {@code RANGER_POLICY_GRANT_POLARIS_CATALOG} child command — grants the
     * release's Polaris principal the run of its own catalog through Ranger, on the clusters where
     * Polaris delegates authorization to Ranger and refuses its own grants. Queued by the
     * provisioning step at execute time, because whether Polaris refused is only known then. Dual
     * path (context direct-REST vs Ambari-server) is chosen at execute time too.
     */
    public String createRangerPolicyGrantPolarisCatalog(CommandEntity rootCommand, Map<String, Object> params) {
        return queueAtlasFederationStep(rootCommand, params,
                CommandType.RANGER_POLICY_GRANT_POLARIS_CATALOG,
                "Ranger: grant the release its Polaris catalog",
                "-ranger-polaris-grant-");
    }

    /**
     * Plans the {@code POLARIS_PROVISION_CATALOG} step. Queued before the chart is installed, so
     * the credential Secret exists by the time the pods mount it. Reuses the generic single-step
     * queuer: one child, no children of its own, params snapshot from the dispatcher.
     */
    public String createPolarisProvision(CommandEntity rootCommand, Map<String, Object> params) {
        return queueAtlasFederationStep(rootCommand, params,
                CommandType.POLARIS_PROVISION_CATALOG,
                "Polaris: provision catalog access for this release",
                "-polaris-provision-");
    }

    /**
     * Plans the {@code DBT_PUBLISH_AIRFLOW_DAG} step. Queued after the chart is installed, because
     * the DAG refers to the scheduled-run template the chart creates.
     */
    public String createDbtPublishAirflowDag(CommandEntity rootCommand, Map<String, Object> params) {
        return queueAtlasFederationStep(rootCommand, params,
                CommandType.DBT_PUBLISH_AIRFLOW_DAG,
                "Airflow: publish the dbt DAG to git",
                "-dbt-airflow-dag-");
    }

    /**
     * Shared queue-step helper for the three Atlas federation provisioning step
     * types. All follow the same shape (one child, no children of their own,
     * params snapshot from the dispatcher, self-persisting root child list).
     */
    private String queueAtlasFederationStep(CommandEntity rootCommand,
                                          Map<String, Object> params,
                                          CommandType type,
                                          String title,
                                          String idMarker) {
        final String now = Instant.now().toString();
        String id = rootCommand.getId() + idMarker + UUID.randomUUID();

        CommandEntity cmd = new CommandEntity();
        cmd.setTitle(title);
        cmd.setId(id);
        cmd.setType(type.name());
        cmd.setViewInstance(ctx.getInstanceName());
        cmd.setParamsJson(gson.toJson(params));

        CommandStatusEntity status = new CommandStatusEntity();
        status.setId(id + "-status");
        status.setAttempt(0);
        status.setState(CommandState.PENDING.name());
        status.setCreatedBy(ctx.getUsername());
        status.setCreatedAt(now);
        status.setUpdatedAt(now);

        cmd.setCommandStatusId(status.getId());

        // Self-persist into root.childListJson (mirrors createAmbariViewProvisionCommand
        // and createOmAtlasFederationRegister — submitDeploy's post-deploy block doesn't
        // re-save root after this call, so we have to).
        java.lang.reflect.Type listType = new TypeToken<ArrayList<String>>(){}.getType();
        List<String> children;
        try {
            children = gson.fromJson(rootCommand.getChildListJson(), listType);
            if (children == null) children = new ArrayList<>();
        } catch (Exception e) {
            children = new ArrayList<>();
        }
        children.add(id);
        rootCommand.setChildListJson(gson.toJson(children));

        store(status);
        store(cmd);
        store(rootCommand);
        LOG.info("Queued {} step {} on root {}", type, id, rootCommand.getId());
        return id;
    }

    /**
     * Plans a single {@code OM_ATLAS_FEDERATION_REGISTER} child command when the
     * service.json {@code postDeploy.atlasFederation} block is non-null AND the
     * deploy-time form values toggled {@code atlasFederation.enabled} on.
     *
     * <p>The runtime body reads the Atlas connection params from the materialised
     * helm values, reads the bot JWT via the airflow scheduler pod, and
     * drives the four OM REST calls. The plan factory just queues the work — all
     * value lookup happens at execute time so a reapply picks up the current
     * release values rather than a snapshot of the original deploy.
     *
     * <p>Self-manages {@code rootCommand.childListJson}: reads the current list,
     * appends the new child id, and re-persists the root. Mirrors the pattern in
     * {@link #createAmbariViewProvisionCommand} — submitDeploy doesn't re-save
     * the root after the post-deploy block, so any plan-factory method called
     * there has to persist the root itself or the new child will be orphaned
     * (created in the datastore but absent from the command tree).
     *
     * @param rootCommand    parent command this step hangs off
     * @param params         root params propagated to the step (must carry
     *                       {@code namespace}, {@code releaseName})
     */
    public void createOmAtlasFederationRegister(CommandEntity rootCommand,
                                                Map<String, Object> params) {
        final String now = Instant.now().toString();
        String id = rootCommand.getId() + "-atlas-fed-" + UUID.randomUUID();

        CommandEntity atlasCmd = new CommandEntity();
        atlasCmd.setTitle("OpenMetadata: register Atlas federation");
        atlasCmd.setId(id);
        atlasCmd.setType(CommandType.OM_ATLAS_FEDERATION_REGISTER.name());
        atlasCmd.setViewInstance(ctx.getInstanceName());
        atlasCmd.setParamsJson(gson.toJson(params));

        CommandStatusEntity status = new CommandStatusEntity();
        status.setId(id + "-status");
        status.setAttempt(0);
        status.setState(CommandState.PENDING.name());
        status.setCreatedBy(ctx.getUsername());
        status.setCreatedAt(now);
        status.setUpdatedAt(now);
        status.setWorkerId(null);

        atlasCmd.setCommandStatusId(status.getId());

        // Append to root's child list — mirrors createAmbariViewProvisionCommand
        Type listType = new TypeToken<ArrayList<String>>(){}.getType();
        List<String> children;
        try {
            children = gson.fromJson(rootCommand.getChildListJson(), listType);
            if (children == null) children = new ArrayList<>();
        } catch (Exception e) {
            children = new ArrayList<>();
        }
        children.add(id);
        rootCommand.setChildListJson(gson.toJson(children));

        store(status);
        store(atlasCmd);
        store(rootCommand);

        LOG.info("Queued OM_ATLAS_FEDERATION_REGISTER step {} on root {}", id, rootCommand.getId());
    }

    /**
     * Queue the OpenMetadata base Hive ingestion registration step. This is the
     * first layer of Atlas+Hive federation — it creates the real OM Hive
     * DatabaseService + metadata pipeline that the Atlas federation step then
     * enriches. submitDeploy queues this BEFORE
     * {@link #createOmAtlasFederationRegister} so the tables exist before Atlas
     * tries to patch them. Self-manages {@code rootCommand.childListJson} and
     * re-stores the root (mirrors {@link #createOmAtlasFederationRegister}).
     *
     * @param rootCommand parent command this step hangs off
     * @param params      root params propagated to the step (must carry
     *                    {@code namespace}, {@code releaseName})
     */
    /**
     * Queue the OpenMetadata Trino base-ingestion registration (see
     * {@link CommandType#OM_TRINO_BASE_INGESTION_REGISTER}). Same one-child shape as the Hive step.
     */
    public void createOmTrinoBaseIngestionRegister(CommandEntity rootCommand, Map<String, Object> params) {
        queueAtlasFederationStep(rootCommand, params,
                CommandType.OM_TRINO_BASE_INGESTION_REGISTER,
                "OpenMetadata: register base Trino ingestion",
                "-trino-base-");
    }

    public void createOmHiveBaseIngestionRegister(CommandEntity rootCommand,
                                                  Map<String, Object> params) {
        final String now = Instant.now().toString();
        String id = rootCommand.getId() + "-hive-base-" + UUID.randomUUID();

        CommandEntity hiveCmd = new CommandEntity();
        hiveCmd.setTitle("OpenMetadata: register base Hive ingestion");
        hiveCmd.setId(id);
        hiveCmd.setType(CommandType.OM_HIVE_BASE_INGESTION_REGISTER.name());
        hiveCmd.setViewInstance(ctx.getInstanceName());
        hiveCmd.setParamsJson(gson.toJson(params));

        CommandStatusEntity status = new CommandStatusEntity();
        status.setId(id + "-status");
        status.setAttempt(0);
        status.setState(CommandState.PENDING.name());
        status.setCreatedBy(ctx.getUsername());
        status.setCreatedAt(now);
        status.setUpdatedAt(now);
        status.setWorkerId(null);

        hiveCmd.setCommandStatusId(status.getId());

        // Append to root's child list — mirrors createOmAtlasFederationRegister
        Type listType = new TypeToken<ArrayList<String>>(){}.getType();
        List<String> children;
        try {
            children = gson.fromJson(rootCommand.getChildListJson(), listType);
            if (children == null) children = new ArrayList<>();
        } catch (Exception e) {
            children = new ArrayList<>();
        }
        children.add(id);
        rootCommand.setChildListJson(gson.toJson(children));

        store(status);
        store(hiveCmd);
        store(rootCommand);

        LOG.info("Queued OM_HIVE_BASE_INGESTION_REGISTER step {} on root {}", id, rootCommand.getId());
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
        LOG.info("HelmRepoLogin params are: {} ", HelmSetValues.redactJson(gson.toJson(params)));
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
     * Append an {@link CommandType#INGRESS_TLS_SELFSIGN} pre-deploy step that mints
     * a TLS leaf cert into a {@code kubernetes.io/tls} Secret for the ingress.
     * Runs before {@code HELM_DEPLOY_DRY_RUN} so the chart's {@code ingress.tls[].secretName}
     * reference resolves.
     *
     * @param cmd          root command entity
     * @param releaseName  release name (used as the cert label and for log lines)
     * @param namespace    target namespace where the chart will be installed
     * @param secretName   K8s Secret name to create (typically {@code <release>-ingress-tls})
     * @param ingressHost  primary DNS name (CN + first SAN)
     * @param extraSans    additional DNS SANs (cluster service name etc.)
     * @param caMode       {@code ambariInternal} or {@code companyUploaded}
     * @param caName       registry name when {@code caMode = companyUploaded}, otherwise null
     * @param validityDays validity in days (null = service default, capped at 825)
     * @return generated step id (added to the caller's child-list)
     */
    public String appendIngressTlsSelfSignStep(CommandEntity cmd,
                                               String releaseName,
                                               String namespace,
                                               String secretName,
                                               String ingressHost,
                                               java.util.List<String> extraSans,
                                               String caMode,
                                               String caName,
                                               Integer validityDays) {
        return appendIngressTlsSelfSignStep(cmd, releaseName, namespace, secretName, ingressHost, extraSans, caMode,
                caName, validityDays, false, false);
    }

    /**
     * Append an {@link CommandType#INGRESS_TLS_SELFSIGN} step, as
     * {@link #appendIngressTlsSelfSignStep(CommandEntity, String, String, String, String, java.util.List, String, String, Integer)},
     * saying what to do with a Secret that already exists.
     *
     * @param replaceExisting whether a Secret KDPS did not mint may be written over (the operator confirmed it)
     * @param keepValidLeaf   whether a leaf KDPS minted with the same CA and names, still valid, is kept as it is
     *                        instead of being re-minted (a deploy keeps it; "Renew TLS" does not)
     */
    public String appendIngressTlsSelfSignStep(CommandEntity cmd,
                                               String releaseName,
                                               String namespace,
                                               String secretName,
                                               String ingressHost,
                                               java.util.List<String> extraSans,
                                               String caMode,
                                               String caName,
                                               Integer validityDays,
                                               boolean replaceExisting,
                                               boolean keepValidLeaf) {
        String subId = UUID.randomUUID().toString();
        String id = cmd.getId() + "-tls-" + subId;
        final String now = Instant.now().toString();
        CommandEntity step = new CommandEntity();
        step.setId(id);
        step.setViewInstance(ctx.getInstanceName());
        step.setType(CommandType.INGRESS_TLS_SELFSIGN.name());
        step.setTitle("TLS: mint ingress cert " + secretName);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("releaseName", releaseName);
        params.put("namespace", namespace);
        params.put("secretName", secretName);
        params.put("ingressHost", ingressHost);
        params.put("extraSans", extraSans != null ? extraSans : java.util.Collections.emptyList());
        params.put("ca", caMode);
        if (caName != null) params.put("caName", caName);
        if (validityDays != null) params.put("validityDays", validityDays);
        if (replaceExisting) params.put("replaceExisting", true);
        if (keepValidLeaf) params.put("keepValidLeaf", true);
        step.setParamsJson(gson.toJson(params));

        CommandStatusEntity status = new CommandStatusEntity();
        status.setId(id + "-status");
        status.setViewInstance(ctx.getInstanceName());
        status.setCreatedBy(ctx.getUsername());
        status.setState(CommandState.PENDING.name());
        status.setCreatedAt(now);
        status.setUpdatedAt(now);
        status.setAttempt(0);
        step.setCommandStatusId(status.getId());

        store(status);
        store(step);
        LOG.info("INGRESS_TLS_SELFSIGN planned id={} secret={}/{} host={} caMode={} caName={}",
                id, namespace, secretName, ingressHost, caMode, caName);
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
        cmd.setTitle("Ambari: provision view instance for " + releaseName);

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
                if (rawParams != null && !CommandParamsStore.isFileReference(rawParams)) {
                    // Encrypted, owner-only, without the form's secrets no step reads (see CommandParamsStore).
                    cmd.setParamsJson(CommandParamsStore.persist(ctx, cmd.getId(), rawParams));
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
        cmd.setTitle("Ambari: materialize stack configurations");

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
