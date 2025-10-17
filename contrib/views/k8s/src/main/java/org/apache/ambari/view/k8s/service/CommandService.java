package org.apache.ambari.view.k8s.service;

import com.google.gson.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.internal.LinkedTreeMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.marcnuri.helm.Release;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandStatus;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.requests.KeytabRequest;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.HelmRepoRepo;

import org.apache.hadoop.fs.shell.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Orchestrator service: transforms user requests into a durable plan (FSM) and executes
 * steps with backoff, using two pools: one for timers (wake-ups) and one for workers.
 *
 * NOTE on planFor(): it is used during submitDeploy() to build and persist the step plan.
 * At runtime, runNextStep() reloads that persisted plan from the entity (so the workflow
 * can resume after restarts). That's why planFor() isn't called again during execution.
 */
public class CommandService {

    private static final Logger LOG = LoggerFactory.getLogger(CommandService.class);

    private final ViewContext ctx;
    private final DataStore dataStore;
    private final HelmRepoRepo repositoryDao;
    private final Gson gson = new GsonBuilder().create();

    // External dependencies (adapters) — injected
    private final HelmService helmService;
    private final KubernetesService kubernetesService;
    private final WebHookConfigurationService webHookConfigurationService;

    // Separate pools: scheduling vs execution
    private final ScheduledExecutorService timer;
    private final ExecutorService workers;

    // Singleton per View instance
    private static final ConcurrentMap<String, CommandService> INSTANCES = new ConcurrentHashMap<>();

    public static CommandService get(ViewContext ctx) {
        return INSTANCES.computeIfAbsent(ctx.getInstanceName(), k -> new CommandService(ctx));
    }
    public CommandService(ViewContext ctx) {
        this.ctx = ctx;
        this.dataStore = ctx.getDataStore();
        this.helmService = new HelmService(ctx);
        this.workers = Executors.newFixedThreadPool(Math.max(1, 2));
        this.kubernetesService = new KubernetesService(ctx);
        this.repositoryDao =  new HelmRepoRepo(ctx.getDataStore());
        this.timer = Executors.newScheduledThreadPool(1);
        this.webHookConfigurationService = new WebHookConfigurationService(this.ctx, this.kubernetesService);
    }

    // -------------------- Public API --------------------

    public String submitKeytabRequest(KeytabRequest req,
                                      MultivaluedMap<String,String> callerHeaders,
                                      URI baseUri) {
        Objects.requireNonNull(req, "KeytabRequest");
        Objects.requireNonNull(req.getPrincipal(), "principal");
        Objects.requireNonNull(req.getNamespace(), "namespace");
        Objects.requireNonNull(req.getSecretName(), "secretName");

        final String id  = java.util.UUID.randomUUID().toString();
        final String now = java.time.Instant.now().toString();

        // ---- root command ----
        CommandEntity root = new CommandEntity();
        root.setId(id);
        root.setViewInstance(ctx.getInstanceName());
        // Root type just needs to be a valid enum; it isn’t executed itself.
        root.setType(org.apache.ambari.view.k8s.model.CommandType.KEYTAB_ISSUE_PRINCIPAL.name());
        root.setTitle("Ad-hoc keytab for " + req.getPrincipal());

        // params (we keep everything together; children read what they need)
        Map<String,Object> params = new LinkedHashMap<>();
        params.put("principalFqdn", req.getPrincipal());
        params.put("cluster", this.ctx.getCluster());
        params.put("namespace", req.getNamespace());
        params.put("secretName", req.getSecretName());
        params.put("keyNameInSecret", firstNonBlank(req.getKeyNameInSecret(), "service.keytab"));

        // persist caller session/headers + baseUri to reuse in worker
        params.put("_callerHeaders", headersToPersistableMap(callerHeaders));
        params.put("_baseUri", baseUri != null ? baseUri.toString() : null);

        // status for root
        CommandStatusEntity rootSt = new CommandStatusEntity();
        rootSt.setId(id + "-status");
        rootSt.setViewInstance(ctx.getInstanceName());
        rootSt.setState(CommandState.PENDING.name());
        rootSt.setCreatedAt(now);
        rootSt.setUpdatedAt(now);
        rootSt.setAttempt(0);
        root.setCommandStatusId(rootSt.getId());

        // ---- child #1: issue principal & keytab via Ambari action ----
        final String c1Id = id + "-" + java.util.UUID.randomUUID();
        CommandEntity c1 = new CommandEntity();
        c1.setId(c1Id);
        c1.setViewInstance(ctx.getInstanceName());
        c1.setType(CommandType.KEYTAB_ISSUE_PRINCIPAL.name());
        c1.setTitle("Ambari: generate keytab for " + req.getPrincipal());
        c1.setParamsJson(new com.google.gson.Gson().toJson(params));

        CommandStatusEntity c1St = new CommandStatusEntity();
        c1St.setId(c1Id + "-status");
        c1St.setAttempt(0);
        c1St.setState(CommandState.PENDING.name());
        c1St.setCreatedAt(now);
        c1St.setUpdatedAt(now);
        c1.setCommandStatusId(c1St.getId());

        // ---- child #2: create/update Opaque secret ----
        final String c2Id = id + "-" + java.util.UUID.randomUUID();
        CommandEntity c2 = new CommandEntity();
        c2.setId(c2Id);
        c2.setViewInstance(ctx.getInstanceName());
        c2.setType(CommandType.KEYTAB_CREATE_SECRET.name());
        c2.setTitle("K8s: create/update secret " + req.getSecretName());
        c2.setParamsJson(new com.google.gson.Gson().toJson(params));

        CommandStatusEntity c2St = new CommandStatusEntity();
        c2St.setId(c2Id + "-status");
        c2St.setAttempt(0);
        c2St.setState(CommandState.PENDING.name());
        c2St.setCreatedAt(now);
        c2St.setUpdatedAt(now);
        c2.setCommandStatusId(c2St.getId());

        // children list in order
        java.util.List<String> children = new java.util.ArrayList<>();
        children.add(c1Id);
        children.add(c2Id);

        root.setChildListJson(new com.google.gson.Gson().toJson(children));
        root.setParamsJson(new com.google.gson.Gson().toJson(params));

        // persist
        store(rootSt);
        store(root);
        store(c1St);
        store(c1);
        store(c2St);
        store(c2);

        // queue
        scheduleNow(id);
        return id;
    }

    public String submitDeploy(HelmDeployRequest request, String repoId, String version, String kubeContext, String commandsURL) {
        final String id = UUID.randomUUID().toString();
        final String now = Instant.now().toString();

        // Required fields
        Objects.requireNonNull(request.getChart(), "chart is required");
        Objects.requireNonNull(request.getReleaseName(), "releaseName is required");
        Objects.requireNonNull(request.getNamespace(), "namespace is required");

        CommandEntity rootCommand = new CommandEntity();
        rootCommand.setId(id);
        rootCommand.setViewInstance(ctx.getInstanceName());
        rootCommand.setType(CommandType.K8S_MANAGER_RELEASE_DEPLOY.name());

        List<String> childCommands = new ArrayList<>();
        // command params
        Map<String, Object> params = new LinkedHashMap<>();
        if ((request.getValues() != null) && !request.getValues().isEmpty()) {
//            params.put("values", request.getValues());
            String valuesJson = gson.toJson(request.getValues());
            String valuesPath = this.kubernetesService.getConfigurationService()
                    .writeCacheFile("values-" + id, valuesJson);
            params.put("valuesPath", valuesPath);
        }
        if (version != null && !version.isBlank()) {
            params.put("version", version);
        }
        if (request.getSecretName() != null && !request.getSecretName().isEmpty()) {
            params.put("secretName", request.getSecretName());
        }
        LOG.info("recevied mounts are {} ", request.getMounts());



        HelmRepoEntity repository = repositoryDao.findById(repoId);
        params.put("chart", repository.getName()+"/"+request.getChart());
        params.put("releaseName", request.getReleaseName());
        params.put("namespace", request.getNamespace());

        // command status
        CommandStatusEntity e = new CommandStatusEntity();
        e.setId(id + "-status");
        e.setViewInstance(ctx.getInstanceName());
        e.setState(CommandState.PENDING.name());
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        e.setAttempt(0);

        rootCommand.setCommandStatusId(e.getId());
        rootCommand.setTitle("Release Installation: "+request.getReleaseName());

        // storing the Command Status
        store(e);

        // assume: Map<String,Object> params already built for the root command
        if (request.getMounts() != null && !request.getMounts().isEmpty()) {
            LOG.info("Creating mounts command");

            // 1) include mounts into params for the child command
            params.put("mounts", request.getMounts()); // <-- this is a Map now

            // 2) create child command IDs
            final String subId = UUID.randomUUID().toString();
            final String mountId = rootCommand.getId() + "-" + subId;

            CommandEntity mountsCommand = new CommandEntity();
            mountsCommand.setType(CommandType.K8S_MOUNTS_DIR_EXEC.name());
            mountsCommand.setId(mountId);
            mountsCommand.setTitle("Create mounts for " + params.get("releaseName"));

            // 3) child status
            CommandStatusEntity mountsStatus = new CommandStatusEntity();
            mountsStatus.setId(mountId + "-status");
            mountsStatus.setAttempt(0);
            mountsStatus.setState(CommandState.PENDING.name());
            mountsStatus.setCreatedAt(now);
            mountsStatus.setUpdatedAt(now);
            mountsStatus.setWorkerId(null);

            mountsCommand.setCommandStatusId(mountsStatus.getId());

            // 4) IMPORTANT: put the params (including mounts) into the child
            mountsCommand.setParamsJson(gson.toJson(params));

            store(mountsStatus);
            store(mountsCommand);

            childCommands.add(mountId);
        }
        /**
        *  1. adding the HelmRepoLogin Step
        * */
        // computing the child command for the root Command
        // the first step is always to validate/login into the helm repo if it is defined

        params.put("repoId", repoId);
        String helmRepoLoginId = createHelmRepoCommand(rootCommand, repoId);
        childCommands.add(helmRepoLoginId);
        rootCommand.setParamsJson(gson.toJson(params));
        rootCommand.setChildListJson(gson.toJson(childCommands));
        LOG.info("Root Command params are: {} ",rootCommand.getParamsJson());
        store(rootCommand);
        /**
         *  2. checking dependencies
         * */
         LOG.info("Processing dependencies if any for the release: {} ",request.getReleaseName());
        if (request.getDependencies()!= null){
            if(request.getDependencies().size() > 0){
                request.getDependencies().entrySet().stream().forEach(
                        dependency -> {
                            LOG.info("Processing dependency: {} ",dependency.getKey());
                            createDependencyCommands(rootCommand, dependency.getValue(), dependency.getKey(), repoId, commandsURL);
                        }
                );
            }
        }

        /**
         * 3. Doing the dry run of the real chart installation
         *
         */
        createRealChartInstallationCommands(rootCommand, repoId, true);
        createRealChartInstallationCommands(rootCommand, repoId, false);

        LOG.info("Queued DEPLOY_COMPOSITE id={} steps={} at {}", id, childCommands.size(), now);
        scheduleNow(id);
        return id;
    }

    private void createRealChartInstallationCommands(CommandEntity cmd, String repoId, boolean isDrRun){
        Type listType = new TypeToken<Map<String, Object>>(){}.getType();
        Map<String, Object> params;
        try {
            params = gson.fromJson(cmd.getParamsJson(), listType);
            if (params == null) {
                params = new LinkedHashMap<>();
            }
        } catch (Exception e) {
            params = new LinkedHashMap<>();
        }
        CommandEntity dryRunChartInstall = new CommandEntity();
        String subId = UUID.randomUUID().toString();
        String id = cmd.getId() + "-" + subId;
        final String now = Instant.now().toString();
        String chart = (String) params.get("chart");
        String releaseName = (String) params.get("releaseName");
        String namespace = (String) params.get("namespace");
        String secretName = (String) params.get("secretName");

        dryRunChartInstall.setTitle("Chart Installation " + chart+ " Installation " );
        dryRunChartInstall.setId(id);
        if (isDrRun){
            dryRunChartInstall.setType(CommandType.HELM_DEPLOY_DRY_RUN.name());
        }else {
            dryRunChartInstall.setType(CommandType.HELM_DEPLOY.name());
        }
        dryRunChartInstall.setViewInstance(ctx.getInstanceName());

        //        propagate params from root to child
        dryRunChartInstall.setParamsJson(cmd.getParamsJson());


        CommandStatusEntity dryRunChartInstallStatus = new CommandStatusEntity();
        dryRunChartInstallStatus.setId(id+"-status");
        dryRunChartInstallStatus.setAttempt(0);
        dryRunChartInstallStatus.setState(CommandState.PENDING.name());
        dryRunChartInstallStatus.setCreatedAt(now);
        dryRunChartInstallStatus.setUpdatedAt(now);
        dryRunChartInstallStatus.setWorkerId(null);

        dryRunChartInstall.setCommandStatusId(dryRunChartInstallStatus.getId());

        store(dryRunChartInstall);
        store(dryRunChartInstallStatus);

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

        store(cmd);



    }
    /**
     * there are three steps each time
     * 1. CRDS check (if creds checks fails we do create the dry-run/install command)
     * 2. dry run of release installation
     * 3. release installation
     */
    private void createDependencyCommands(CommandEntity cmd, Object dependency, String dependencyName, String repoId, String commandsURL) {
        if (dependency instanceof Map) {
            // Use generic Map instead of LinkedTreeMap to avoid class loader issues
            @SuppressWarnings("unchecked")
            Map<String, Object> depMap = (Map<String, Object>) dependency;
            
            LOG.info("Creating commands for dependency: {} ", dependencyName);
            
            // Accessing the files of a dependency
            String chart = (String) depMap.get("chart");
            String chartVersion = (String) depMap.get("chartVersion");
            String imageTag = (String) depMap.get("imageTag");
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
            params.put("chart", repository.getName()+"/"+chart);
            params.put("namespace", chartNamespace);
            params.put("chartVersion", chartVersion);
            params.put("imageTag", imageTag);
            params.put("imageRepository", imageRepository);
            params.put("releaseName", dependencyName);
            params.put("images", images);
            params.put("isWebhook", isWebhook);
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
    private String createHelmRepoCommand(CommandEntity cmd, String repoId){
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
        cmdStatus.setCreatedAt(now);
        cmdStatus.setUpdatedAt(now);
        cmdStatus.setAttempt(0);
        cmdStatus.setId(id + "-status");

        helmRepoLogin.setCommandStatusId(cmdStatus.getId());
        store(cmdStatus);
        store(helmRepoLogin);
        return id;

    }
    public CommandStatus getStatus(String id) {
        CommandEntity e = find(id);
        CommandStatusEntity eStatus = findCommandStatusById(e.getCommandStatusId());
        if (e == null) return null;
        CommandStatus s = new CommandStatus();
        s.id = e.getId();
        s.type = CommandType.valueOf(e.getType());
        s.state = CommandState.valueOf(eStatus.getState());
        s.message = eStatus.getMessage();
        s.createdAt = eStatus.getCreatedAt();
        s.updatedAt = eStatus.getUpdatedAt();
        s.percent = computePercentage(e);
        refreshCurrentStepSnapshot(e);
        LOG.info("Getting status information for command with id: {} ",s.id);
        LOG.debug("Params are: {} ",e.getParamsJson());
        
        int stepIndex = java.util.Optional.ofNullable(e.getParamsJson())
                .map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
                .map(o -> o.getAsJsonObject("progress")).map(p -> p.get("currentStepIndex"))
                .map(JsonElement::getAsInt).orElse(-1);
        if (stepIndex == -1) stepIndex = 0; // convert to 0-based
        s.step = stepIndex;
        return s;
    }
    // inside CommandService
    private int computePercentage(CommandEntity cmd) {
        final String json = cmd.getChildListJson();
        if (json == null || json.isBlank()) {
            return 0;
        }

        Type listType = new TypeToken<ArrayList<String>>() {}.getType();
        List<String> childIds;
        try {
            childIds = gson.fromJson(json, listType);
        } catch (Exception ex) {
            LOG.warn("Failed to parse childListJson for {}: {}", cmd.getId(), ex.toString());
            return 0;
        }

        if (childIds == null || childIds.isEmpty()) {
            return 0;
        }

        int total = childIds.size();
        int succeeded = 0;

        for (String childId : childIds) {
            if (childId == null || childId.isBlank()) {
                continue;
            }

            CommandEntity child = find(childId);
            if (child == null) {
                LOG.debug("Child {} listed in {} but not found in datastore", childId, cmd.getId());
                continue;
            }

            CommandStatusEntity st = findCommandStatusById(child.getCommandStatusId());
            if (st == null) {
                LOG.debug("Status missing for child {}", childId);
                continue;
            }

            if (CommandState.SUCCEEDED.name().equals(st.getState())) {
                succeeded++;
            }
        }

        int percent = (int) Math.round(succeeded * 100.0 / total);
        LOG.debug("Computed success percentage for {} -> {}/{} = {}%", cmd.getId(), succeeded, total, percent);
        return percent;
    }

    public void cancel(String id) {
        CommandEntity e = find(id);
        CommandStatusEntity eStatus = findCommandStatusById(e.getCommandStatusId());
        if (e == null) return;
        if (!CommandState.SUCCEEDED.name().equals(eStatus.getState()) &&
            !CommandState.FAILED.name().equals(eStatus.getState())) {
            eStatus.setState(CommandState.CANCELED.name());
            eStatus.setUpdatedAt(Instant.now().toString());
            eStatus.setMessage("Canceled by user");
            store(e);
        }
    }


    // -------------------- Scheduling & execution --------------------

    private void scheduleNow(String id) {
        timer.schedule(() -> workers.submit(() -> runNextStep(id)), 0, TimeUnit.SECONDS);
    }

    private void rescheduleWithBackoff(String id, int attempt) {
        long delay = (long) Math.min(300, Math.pow(2, Math.min(attempt, 6))); // 1,2,4,8,16,32,64… cap 300s
        timer.schedule(() -> workers.submit(() -> runNextStep(id)), delay, TimeUnit.SECONDS);
    }

    // recursvie method


    private void runNextStep(String id) {
        // 0) Load root & status (the orchestrator is the root command id)
        CommandEntity root = find(id);
        if (root == null) return;

        LOG.info("Running next step for command with id: {} ", root.getId());
        Type mapType = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();
        Map<String, Object> rootParams = gson.fromJson(root.getParamsJson(), mapType);
        CommandStatusEntity rootSt = findCommandStatusById(root.getCommandStatusId());
        if (rootSt == null) return;
        if (isTerminal(rootSt.getState())) return; // terminal guard

        // 1) Parse ordered list of child IDs
        List<String> childIds = Collections.emptyList();
        final String json = root.getChildListJson();
        if (json != null && !json.isBlank()) {
            try {
                Type listType = new TypeToken<ArrayList<String>>() {}.getType();
                childIds = gson.fromJson(json, listType);
                if (childIds == null) childIds = Collections.emptyList();
            } catch (Exception ex) {
                LOG.warn("[{}] Invalid childListJson: {}", id, ex.toString());
                // If plan cannot be read, fail fast
                fail(rootSt, "Invalid step plan (childListJson).");
                return;
            }
        }

        // 2) If no children, mark root succeeded
        if (childIds.isEmpty()) {
            LOG.info("[{}] No child steps defined, marking as SUCCEEDED", id);
            succeed(rootSt, "No steps to execute.");
            refreshCurrentStepSnapshot(root);
            return;
        }

        // 3) Scan children to decide “current step”
        Integer runningIdx = null, pendingIdx = null;
        CommandEntity runningChild = null, pendingChild = null;
        CommandStatusEntity runningChildSt = null, pendingChildSt = null;

        for (int i = 0; i < childIds.size(); i++) {
            String childId = childIds.get(i);
            if (childId == null || childId.isBlank()) continue;
            LOG.info("Processing child command with id: {} for root command with id: {} ", childId, id);
            CommandEntity child = find(childId);
            LOG.info("Child Title is: {} ", child.getTitle());
            if (child == null) {
                // If a child is missing, fail root (plan integrity error)
                fail(rootSt, "Missing child command: " + childId);
                refreshCurrentStepSnapshot(root);
                return;
            }

            CommandStatusEntity st = findCommandStatusById(child.getCommandStatusId());
            if (st == null || st.getState() == null) {
                fail(rootSt, "Missing status for child: " + childId);
                refreshCurrentStepSnapshot(root);
                return;
            }

            String state = st.getState();
            if (CommandState.FAILED.name().equals(state)) {
                fail(rootSt, "Step failed: " + child.getTitle());
                refreshCurrentStepSnapshot(root);
                return;
            }
            if (CommandState.CANCELED.name().equals(state)) {
                rootSt.setState(CommandState.CANCELED.name());
                rootSt.setMessage("Canceled at step: " + child.getTitle());
                rootSt.setUpdatedAt(Instant.now().toString());
                store(rootSt);
                refreshCurrentStepSnapshot(root);
                return;
            }
            if (CommandState.RUNNING.name().equals(state) && runningIdx == null) {
                runningIdx = i; runningChild = child; runningChildSt = st;
                break; // RUNNING takes precedence
            }
            if (CommandState.PENDING.name().equals(state) && pendingIdx == null) {
                pendingIdx = i; pendingChild = child; pendingChildSt = st;
                // keep scanning in case a RUNNING exists earlier
            }
        }

        // 4) If nothing RUNNING/PENDING remains, all steps are SUCCEEDED
        if (runningIdx == null && pendingIdx == null) {
            succeed(rootSt, "All steps completed.");
            refreshCurrentStepSnapshot(root);
            return;
        }

        // 5) Choose the child to execute now
        final boolean pickedRunning = (runningIdx != null);

        final CommandEntity child = pickedRunning ? runningChild : pendingChild;
        final CommandStatusEntity childSt = pickedRunning ? runningChildSt : pendingChildSt;

        // 6) Ensure root is RUNNING and snapshot is fresh
        if (!CommandState.RUNNING.name().equals(rootSt.getState())) {
            rootSt.setState(CommandState.RUNNING.name());
        }
        rootSt.setMessage("Running: " + (child.getTitle() != null ? child.getTitle() : child.getType()));
        rootSt.setUpdatedAt(Instant.now().toString());
        store(rootSt);

        // If the child was PENDING, mark it RUNNING
        if (CommandState.PENDING.name().equals(childSt.getState())) {
            childSt.setState(CommandState.RUNNING.name());
            LOG.info("[{}] Starting step {}/{}: {}",
                    id,
                    (pickedRunning ? runningIdx + 1 : pendingIdx + 1),
                    childIds.size(),
                    (child.getTitle() != null ? child.getTitle() : child.getType())
            );
            childSt.setMessage("Starting: " + (child.getTitle() != null ? child.getTitle() : child.getType()));
            childSt.setUpdatedAt(Instant.now().toString());
            store(childSt);
        }

        // Keep UI step snapshot up-to-date
        refreshCurrentStepSnapshot(root);

        // 7) Execute the selected child step
        try {
            // Build a HelmDeployRequest from root params for the generic step methods you already have
            HelmDeployRequest rootReq = buildRequestFromRootParams(root);
            LOG.info("[{}] Executing step {}/{}: {}",
                    id,
                    (pickedRunning ? runningIdx + 1 : pendingIdx + 1),
                    childIds.size(),
                    (child.getTitle() != null ? child.getTitle() : child.getType())
            );
//            read repoId from rootCommandEntoty in params
            mapType = new TypeToken<LinkedHashMap<String, Object>>() {
            }.getType();
            Map<String, Object> childParams = gson.fromJson(child.getParamsJson(), mapType);

            String repoId = (String) rootParams.get("repoId");
            LOG.info("Using repoId: {} for step execution", repoId);
            LOG.info("Child Command params are: {} ", child.getParamsJson());
            switch (CommandType.valueOf(child.getType())) {
                case HELM_REPO_LOGIN -> {
                    LOG.info("Performing helm repo login for repoId: {} ", repoId);
                    stepRepoLogin(rootReq, repoId);
                }
                case POST_VERIFY -> stepPostVerify(rootReq);
                case K8S_MOUNTS_DIR_EXEC -> {

                    String namespace = (String) childParams.get("namespace");
                    String releaseName = (String) childParams.get("releaseName");

                    this.kubernetesService.createNamespace(namespace);
                    this.kubernetesService.ensureWebhookEnabledNamespace(namespace);

                    Map<String, Object> mounts = normalizeMountsObject(childParams.get("mounts"));
                    LOG.info("Mounts execution for release {} in ns {}: {}", releaseName, namespace, mounts);

                    // Create PVCs etc.
                    this.kubernetesService.createMounts(namespace, releaseName, mounts);
                }
                // ---- Dependency steps (implement these as you wire them) ----
                case HELM_DEPLOY_DEPENDENCY_CRD -> {
                    String crd = (String) childParams.get("crd");
                    String crdResourcePath = (String) childParams.get("crd-resource-path");
                    String releaseName = (String) childParams.get("releaseName");
                    String releaseNamespace = (String) childParams.get("namespace");
                    boolean crdExists = this.kubernetesService.crdExists(crd);
                    if (!crdExists) {
                        LOG.info("CRD: {} does not exist, proceeding with installation", crd);
                        LOG.info("Processing CRD resource path: {} for release: {} in namespace: {} ", crdResourcePath, releaseName, releaseNamespace);
                        String workingDirectory = this.kubernetesService.getConfigurationService().getViewResourcePath();
                        this.kubernetesService.applyYaml(workingDirectory + "/" + crdResourcePath, releaseName, releaseNamespace);
                    } else {
                        LOG.info("CRD: {} already exists, skipping installation", crd);
                    }
                }
                case HELM_DEPLOY_DRY_RUN_DEPENDENCY_RELEASE -> {
                    String chartName = (String) childParams.get("chart");


                    String chartVersion = (String) childParams.get("chartVersion");
                    String imageTag = (String) childParams.get("imageTag");
                    String imageRepository = (String) childParams.get("imageRepository");

                    Object secretName = childParams.get("secretName");
                    Object serviceAccountsObj = childParams.get("serviceAccounts");
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
                    List<String> images = new ArrayList<>();
                    Object imagesObj = childParams.get("images");
                    if (imagesObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Object> imagesList = (List<Object>) imagesObj;
                        for (Object img : imagesList) {
                            if (img instanceof String) {
                                images.add((String) img);
                            }
                        }
                    }
                    HelmRepoEntity repository = repositoryDao.findById(repoId);
                    Map<String, String> overrideProperties = new HashMap<String, String>();
                    for (String img : images) {
                        if (!img.contains(":")) {
                            overrideProperties.put("image.repository", imageRepository + "/" + img);
                            overrideProperties.put("image.registry", repository.getUrl());
                            overrideProperties.put("image.tag", imageTag);
                            LOG.info("Injecting in values.yaml image.repository value: {}", imageRepository + "/" + img);
                            LOG.info("Injecting in values.yaml image.registry   value: {}", repository.getUrl());
                            LOG.info("Injecting in values.yaml image.tag        value: {}", imageTag);
                        } else {
                            overrideProperties.put("image." + img.split(":")[0] + ".repository", imageRepository + "/" + img.split(":")[1]);
                            overrideProperties.put("image." + img.split(":")[0] + ".registry", repository.getUrl());
                            overrideProperties.put("image." + img.split(":")[0] + ".tag", imageTag);
                            LOG.info("Injecting in values.yaml image." + img.split(":")[0] + ".repository value: {}", imageRepository + "/" + img.split(":")[1]);
                            LOG.info("Injecting in values.yaml image." + img.split(":")[0] + ".registry   value: {}", repository.getUrl());
                            LOG.info("Injecting in values.yaml image." + img.split(":")[0] + ".tag        value: {}", imageTag);
                        }
                    }
                    boolean isWebhook = false;
                    Object isWebhookObj = childParams.get("isWebhook");
                    if (isWebhookObj instanceof Boolean) {
                        isWebhook = (Boolean) isWebhookObj;
                    } else if (isWebhookObj instanceof String) {
                        isWebhook = Boolean.parseBoolean((String) isWebhookObj);
                    }
                    // if the dependency is a custom webhook we need to inject the property backend.url in order to the webhook to know the views url
                    if (isWebhook) {
                        String backend_url = (String) childParams.get("backend.url");
                        LOG.info("Injecting backend.url property with value: {} for webhook dependency", backend_url);
                        overrideProperties.put("backend.url", backend_url);
                        overrideProperties.put("webhook.caBundle", this.webHookConfigurationService.currentCaBundleBase64());
                    }
                    if (!images.isEmpty() && secretName == null) {
                        throw new IllegalStateException("Image pull secret (secretName) must be defined when images are specified for dependency: " + chartName);
                    }
                    String namespace = (String) childParams.get("namespace");
                    this.kubernetesService.createNamespace(namespace);
                    this.kubernetesService.ensureWebhookEnabledNamespace(namespace);
                    String releaseName = (String) childParams.get("releaseName");
                    if (secretName != null) {
                        LOG.info("Ensuring image pull secret: {} in namespace: {} for service accounts: {} ", secretName, namespace, serviceAccounts);
                        this.helmService.ensureImagePullSecretFromRepo(repoId, namespace, (String) secretName, serviceAccounts);
                        overrideProperties.put("imagePullSecrets[0].name", (String) secretName);
                    }

                    LOG.info("Performing dry-run of dependency release: {} in namespace: {} with chart: {} and version: {} ", releaseName, namespace, chartName, chartVersion);
                    this.helmService.deployOrUpgrade(
                            chartName,
                            releaseName,
                            namespace, childParams, overrideProperties, this.kubernetesService.getConfigurationService().getKubeconfigContents(), repoId, chartVersion, true);
                }
                case HELM_DEPLOY_DEPENDENCY_RELEASE -> {
                    String chartName = (String) childParams.get("chart");
                    String chartVersion = (String) childParams.get("chartVersion");
                    String namespace = (String) childParams.get("namespace");
                    String releaseName = (String) childParams.get("releaseName");
                    String imageTag = (String) childParams.get("imageTag");
                    String imageRepository = (String) childParams.get("imageRepository");

                    Object secretName = childParams.get("secretName");
                    Object serviceAccountsObj = childParams.get("serviceAccounts");
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
                    LOG.info("Installing dependency release: {} in namespace: {} with chart: {} and version: {} ", releaseName, namespace, chartName, chartVersion);
                    Map<String, String> overrideProperties = new HashMap<>();
                    HelmRepoEntity repository = repositoryDao.findById(repoId);
                    List<String> images = new ArrayList<>();
                    Object imagesObj = childParams.get("images");
                    if (imagesObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Object> imagesList = (List<Object>) imagesObj;
                        for (Object img : imagesList) {
                            if (img instanceof String) {
                                images.add((String) img);
                            }
                        }
                    }
                    for (String img : images) {
                        if (!img.contains(":")) {
                            overrideProperties.put("image.repository", imageRepository + "/" + img);
                            overrideProperties.put("image.registry", repository.getUrl());
                            overrideProperties.put("image.tag", imageTag);
                            LOG.info("Injecting in values.yaml image.repository value: {}", imageRepository + "/" + img);
                            LOG.info("Injecting in values.yaml image.registry   value: {}", repository.getUrl());
                            LOG.info("Injecting in values.yaml image.tag        value: {}", imageTag);
                        } else {
                            overrideProperties.put("image." + img.split(":")[0] + ".repository", imageRepository + "/" + img.split(":")[1]);
                            overrideProperties.put("image." + img.split(":")[0] + ".registry", repository.getUrl());
                            overrideProperties.put("image." + img.split(":")[0] + ".tag", imageTag);
                            LOG.info("Injecting in values.yaml image." + img.split(":")[0] + ".repository value: {}", imageRepository + "/" + img.split(":")[1]);
                            LOG.info("Injecting in values.yaml image." + img.split(":")[0] + ".registry   value: {}", repository.getUrl());
                            LOG.info("Injecting in values.yaml image." + img.split(":")[0] + ".tag        value: {}", imageTag);
                        }
                    }
                    boolean isWebhook = false;
                    Object isWebhookObj = childParams.get("isWebhook");
                    if (isWebhookObj instanceof Boolean) {
                        isWebhook = (Boolean) isWebhookObj;
                    } else if (isWebhookObj instanceof String) {
                        isWebhook = Boolean.parseBoolean((String) isWebhookObj);
                    }
                    if (isWebhook) {
                        String backend_url = (String) childParams.get("backend.url");
                        overrideProperties.put("backend.url", backend_url);
                        overrideProperties.put("webhook.caBundle", this.webHookConfigurationService.currentCaBundleBase64());
                        LOG.info("Injecting backend.url property with value: {} for webhook dependency", backend_url);
                        LOG.info("Injecting webhook.caBundle with CurrentCA");

                    }

                    if (secretName != null) {
                        LOG.info("Ensuring image pull secret: {} in namespace: {} for service accounts: {} ", secretName, namespace, serviceAccounts);
                        this.helmService.ensureImagePullSecretFromRepo(repoId, namespace, (String) secretName, serviceAccounts);
                        overrideProperties.put("imagePullSecrets[0].name", (String) secretName);
                    }
                    this.helmService.deployOrUpgrade(
                            chartName,
                            releaseName,
                            namespace, childParams, overrideProperties, this.kubernetesService.getConfigurationService().getKubeconfigContents(), repoId, chartVersion, true);
                }

                case HELM_DEPLOY_DRY_RUN -> {
                    String chartName = (String) childParams.get("chart");
                    String namespace = (String) childParams.get("namespace");
                    String releaseName = (String) childParams.get("releaseName");
                    String version = (String) childParams.get("version");

                    Object valuesObj = childParams.get("values");


//                    Map<String,Object> valuesMap = extractValues(valuesObj);

                    Map<String, Object> valuesMap = loadValuesFromParams(childParams);
                    Map<String, String> overrideProperties = new HashMap<>();
//                    overrideProperties.put("server.autoscaling.enabled", "false"); // HPA off (mutually exclusive)
//                    overrideProperties.put("server.keda.enabled", "true");
//                    overrideProperties.put("server.keda.minReplicaCount", "0");     // scale to zero
//                    overrideProperties.put("server.keda.maxReplicaCount", "20");    // pick your max
//                    overrideProperties.put("server.keda.pollingInterval", "30");    // seconds
//                    overrideProperties.put("server.keda.cooldownPeriod", "300");    // seconds

                    this.kubernetesService.createNamespace(namespace);
                    this.kubernetesService.ensureWebhookEnabledNamespace(namespace);
                    Object secretName = childParams.get("secretName");
                    if (secretName != null) {
                        LOG.info("Ensuring image pull secret: {} in namespace: {} for service accounts: {} ", secretName, namespace, null);
                        this.helmService.ensureImagePullSecretFromRepo(repoId, namespace, (String) secretName, null);
                        overrideProperties.put("imagePullSecrets[0].name", (String) secretName);
                    }
                    this.helmService.deployOrUpgrade(
                            chartName,
                            releaseName,
                            namespace, valuesMap, overrideProperties, this.kubernetesService.getConfigurationService().getKubeconfigContents(), repoId, version, true);
                }
                case HELM_DEPLOY -> {
                    String chartName = (String) childParams.get("chart");
                    String namespace = (String) childParams.get("namespace");
                    String releaseName = (String) childParams.get("releaseName");
                    String version = (String) childParams.get("version");

//                    Object valuesObj = childParams.get("values");
//                    Map<String,Object> valuesMap = extractValues(valuesObj);
                    Map<String, Object> valuesMap = loadValuesFromParams(childParams);
                    Map<String, String> overrideProperties = new HashMap<>();


                    this.kubernetesService.createNamespace(namespace);
                    this.kubernetesService.ensureWebhookEnabledNamespace(namespace);
                    Object secretName = childParams.get("secretName");
                    if (secretName != null) {
                        LOG.info("Ensuring image pull secret: {} in namespace: {} for service accounts: {} ", secretName, namespace, null);
                        this.helmService.ensureImagePullSecretFromRepo(repoId, namespace, (String) secretName, null);
                        overrideProperties.put("imagePullSecrets[0].name", (String) secretName);
                    }
                    this.helmService.deployOrUpgrade(
                            chartName,
                            releaseName,
                            namespace, valuesMap, overrideProperties, this.kubernetesService.getConfigurationService().getKubeconfigContents(), repoId, version, false);
                }
                // in CommandService.runNextStep(...) switch
                case KEYTAB_ISSUE_PRINCIPAL -> {
                    String principalFqdn = (String) childParams.get("principalFqdn");
                    Objects.requireNonNull(principalFqdn, "principalFqdn");

                    // cluster can be null -> Ambari action can deduce, but we’ll require it for clarity if present
                    String cluster = (String) childParams.get("cluster");

                    // caller context
                    @SuppressWarnings("unchecked")
                    Map<String, Object> persistedHeaders = (Map<String, Object>) childParams.get("_callerHeaders");
                    String baseUriStr = (String) childParams.get("_baseUri");
                    if (baseUriStr == null || baseUriStr.isBlank()) {
                        throw new IllegalStateException("Missing baseUri in command params (_baseUri)");
                    }
                    URI baseUri = URI.create(baseUriStr);

                    // 1) submit Ambari action
                    String requestId = ambariSubmitGenerateAdhocKeytab(baseUri, cluster, principalFqdn, persistedHeaders);

                    // 2) poll completion
                    Map<String, Object> completed = ambariWaitRequestComplete(baseUri, cluster, requestId, persistedHeaders,
                            java.time.Duration.ofSeconds(60), java.time.Duration.ofSeconds(2));

                    // 3) extract keytab_b64
                    String keytabB64 = ambariFindKeytabB64(completed);
                    if (keytabB64 == null || keytabB64.isBlank()) {
                        throw new IllegalStateException("Ambari action completed but returned no keytab_b64");
                    }

                    Map<String, Object> res = new LinkedHashMap<>();
                    res.put("principal", principalFqdn);
                    res.put("keytabB64", keytabB64);
                    childSt.setResultJson(gson.toJson(res));
                }


                case KEYTAB_CREATE_SECRET -> {
                    // read params for this step
                    String namespace      = (String) childParams.get("namespace");
                    String secretName     = (String) childParams.get("secretName");
                    String keyNameInSecret= (String) childParams.get("keyNameInSecret");

                    Objects.requireNonNull(namespace, "namespace");
                    Objects.requireNonNull(secretName, "secretName");
                    if (keyNameInSecret == null || keyNameInSecret.isBlank()) {
                        keyNameInSecret = "service.keytab";
                    }

                    // locate the sibling KEYTAB_ISSUE_PRINCIPAL and fetch its resultJson
                    java.lang.reflect.Type listType = new com.google.gson.reflect.TypeToken<java.util.ArrayList<String>>(){}.getType();
                    java.util.List<String> siblings = gson.fromJson(root.getChildListJson(), listType);
                    if (siblings == null) siblings = java.util.Collections.emptyList();

                    String issuerId = siblings.stream()
                            .filter(cid -> {
                                CommandEntity c = findCommandById(cid);
                                return c != null && CommandType.KEYTAB_ISSUE_PRINCIPAL.name().equals(c.getType());
                            })
                            .findFirst()
                            .orElse(null);

                    if (issuerId == null) {
                        throw new IllegalStateException("Missing KEYTAB_ISSUE_PRINCIPAL sibling before KEYTAB_CREATE_SECRET");
                    }

                    CommandEntity issuerCmd = findCommandById(issuerId);
                    CommandStatusEntity issuerSt = findCommandStatusById(issuerCmd.getCommandStatusId());
                    if (issuerSt == null || issuerSt.getResultJson() == null || issuerSt.getResultJson().isBlank()) {
                        throw new IllegalStateException("Issuer step has no resultJson with keytabB64");
                    }

                    java.util.Map<String,Object> issuerRes =
                            gson.fromJson(issuerSt.getResultJson(), java.util.Map.class);
                    String keytabB64 = issuerRes == null ? null : (String) issuerRes.get("keytabB64");
                    if (keytabB64 == null || keytabB64.isBlank()) {
                        throw new IllegalStateException("Issuer resultJson does not contain keytabB64");
                    }

                    byte[] keytabBytes = java.util.Base64.getDecoder().decode(keytabB64);

                    // (optional) ensure namespace exists — harmless if it already exists
                    try {
                        this.kubernetesService.createNamespace(namespace);
                    } catch (Exception ignore) {
                        // ignore: namespace may already exist or user may not want auto-create
                    }

                    // write/update the Opaque Secret (relies on your KubeUtil helper)
                    // signature: createOrUpdateOpaqueSecret(namespace, secretName, dataKey, dataBytes)
                    this.kubernetesService.createOrUpdateOpaqueSecret(
                            namespace, secretName, keyNameInSecret, keytabBytes
                    );

                    // store a tiny result payload for this step
                    java.util.Map<String,Object> res = new java.util.LinkedHashMap<>();
                    res.put("secretName", secretName);
                    res.put("namespace", namespace);
                    res.put("dataKey", keyNameInSecret);
                    childSt.setResultJson(gson.toJson(res));
                }


                default -> throw new IllegalStateException("Unsupported step type: " + child.getType());
            }

            // Success path for the child
            childSt.setState(CommandState.SUCCEEDED.name());
            childSt.setMessage("Done: " + (child.getTitle() != null ? child.getTitle() : child.getType()));
            childSt.setAttempt(0);
            childSt.setUpdatedAt(Instant.now().toString());
            store(childSt);

            // Update snapshot & continue with the next step
            refreshCurrentStepSnapshot(root);
            LOG.info("[{}] Completed step: {}", id, (child.getTitle() != null ? child.getTitle() : child.getType()));
            scheduleNow(id);

        } catch (RetryableException rex) {
            int attempt = childSt.getAttempt() == null ? 0 : childSt.getAttempt();
            attempt += 1;
            childSt.setAttempt(attempt);
            childSt.setMessage("Retryable error: " + trim(rex.getMessage()));
            childSt.setUpdatedAt(Instant.now().toString());
            store(childSt);

            // Keep root RUNNING, back off, and try again
            rescheduleWithBackoff(id, attempt);

        } catch (Exception ex) {
            // Fail child and propagate to root
            childSt.setState(CommandState.FAILED.name());
            childSt.setMessage("Failed: " + trim(ex.getMessage()));
            childSt.setUpdatedAt(Instant.now().toString());
            store(childSt);
            LOG.error("Step execution failed: {}", ex.toString(), ex);
            fail(rootSt, "Failed at step '" + (child.getTitle() != null ? child.getTitle() : child.getType()) + "': " + trim(ex.getMessage()));
            refreshCurrentStepSnapshot(root);
        }
    }

    private static String trim(String m) { return m == null ? null : (m.length() > 512 ? m.substring(0,512) + "…" : m); }

    private boolean isTerminal(String state) {
        return CommandState.SUCCEEDED.name().equals(state)
            || CommandState.FAILED.name().equals(state)
            || CommandState.CANCELED.name().equals(state);
    }

    // -------------------- Step implementations (now fully wired via adapters) --------------------

    private void stepRepoLogin(HelmDeployRequest req, String repoId) {
        helmService.repoLogin(req, repoId);
    }

    private void stepDryRun(HelmDeployRequest req) {
        // todo implement
    }

    private void stepInstallOrUpgrade(HelmDeployRequest req) {
        // todo implement
    }

    private void stepPostVerify(HelmDeployRequest req) {
            // todo implement
    }

    // -------------------- Persistence helpers --------------------

    private void succeed(CommandStatusEntity e, String message) {
        e.setState(CommandState.SUCCEEDED.name());
        e.setMessage(message);
        e.setUpdatedAt(Instant.now().toString());
        store(e);
    }

    private void fail(CommandStatusEntity e, String message) {
        e.setState(CommandState.FAILED.name());
        e.setMessage(message);
        e.setUpdatedAt(Instant.now().toString());
        store(e);
    }

    private void store(CommandStatusEntity e) {
        try { dataStore.store(e); }
        catch (PersistenceException ex) {
            LOG.error("Persist error", ex);
        }
    }

    private void store(CommandEntity e) {
        try { dataStore.store(e); }
        catch (PersistenceException ex) {
            LOG.error("Persist error", ex);
        }
    }
    private CommandStatusEntity findCommandStatusById(String id) {
        try { return dataStore.find(CommandStatusEntity.class, id); }
        catch (PersistenceException ex) { LOG.error("Find error", ex); return null; }
    }

    private CommandEntity find(String id) {
        try { return dataStore.find(CommandEntity.class, id); }
        catch (PersistenceException ex) { LOG.error("Find error", ex); return null; }
    }
   private CommandEntity findCommandById(String id) {
        try { return dataStore.find(CommandEntity.class, id); }
        catch (PersistenceException ex) { LOG.error("Find error", ex); return null; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeMountsObject(Object raw) {
        if (raw == null) return java.util.Collections.emptyMap();
        if (raw instanceof Map) return (Map<String, Object>) raw;
        if (raw instanceof String s) {
            if (s.isBlank()) return java.util.Collections.emptyMap();
            try {
                return new com.google.gson.Gson().fromJson(s, Map.class);
            } catch (Exception ignore) {
                return java.util.Collections.emptyMap();
            }
        }
        // tolerate the (wrong) array-of-specs case by ignoring
        return java.util.Collections.emptyMap();
    }
    // -------------------- Errors --------------------

    /** Marker for transient failures deserving a retry/backoff. */
    static class RetryableException extends RuntimeException {
        public RetryableException(String m) { super(m); }
        public RetryableException(String m, Throwable t) { super(m, t); }
    }
    /** Build a HelmDeployRequest from the root command's paramsJson (chart, releaseName, namespace, version, values). */
    private HelmDeployRequest buildRequestFromRootParams(CommandEntity root) {
        HelmDeployRequest req = new HelmDeployRequest();
        try {
            Type mapType = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();
            Map<String, Object> p = (root.getParamsJson() != null && !root.getParamsJson().isBlank())
                    ? gson.fromJson(root.getParamsJson(), mapType) : Map.of();

            Object chart = p.get("chart");
            Object releaseName = p.get("releaseName");
            Object namespace = p.get("namespace");
            Object version = p.get("version");
            @SuppressWarnings("unchecked")
            Map<String,Object> values = (p.get("values") instanceof Map) ? (Map<String,Object>) p.get("values") : null;

            // These setters assume your HelmDeployRequest has standard mutators.
            if (chart instanceof String) req.setChart((String) chart);
            if (releaseName instanceof String) req.setReleaseName((String) releaseName);
            if (namespace instanceof String) req.setNamespace((String) namespace);
            if (version instanceof String) req.setVersion((String) version);
            if (values != null) req.setValues(values);

        } catch (Exception ex) {
            LOG.warn("[{}] Could not reconstruct HelmDeployRequest from paramsJson: {}", root.getId(), ex.toString());
        }
        return req;
    }
    // inside CommandService

    /**
     * Computes the "current step" for the given parent command and writes a snapshot into
     * parent.paramsJson under key "progress".
     *
     * Rules:
     * - Pick the first RUNNING child if any.
     * - Otherwise pick the first PENDING child.
     * - If none found (all terminal or missing), snapshot will have null currentStepId and state.
     *
     * The snapshot shape (inside paramsJson):
     * {
     *   "progress": {
     *     "currentStepId": "...",
     *     "currentStepIndex": 3,      // 1-based
     *     "totalSteps": 7,
     *     "currentStepTitle": "...",
     *     "currentStepType": "HELM_DEPLOY_...",
     *     "currentStepState": "RUNNING" | "PENDING" | null,
     *     "percentage": "42%"         // from computePercentage(parent)
     *   },
     *   ... other existing params ...
     * }
     */
    private void refreshCurrentStepSnapshot(CommandEntity parent) {
        // 1) Read ordered child IDs
        final String json = parent.getChildListJson();
        List<String> childIds = Collections.emptyList();
        if (json != null && !json.isBlank()) {
            try {
                Type listType = new TypeToken<ArrayList<String>>() {}.getType();
                childIds = gson.fromJson(json, listType);
            } catch (Exception ex) {
                LOG.warn("Failed to parse childListJson for {}: {}", parent.getId(), ex.toString());
            }
        }
        if (childIds == null) childIds = Collections.emptyList();

        // 2) Scan once: remember first RUNNING and first PENDING
        Integer runningIdx = null, pendingIdx = null;
        CommandEntity runningChild = null, pendingChild = null;
        CommandStatusEntity runningSt = null, pendingSt = null;

        for (int i = 0; i < childIds.size(); i++) {
            String childId = childIds.get(i);
            if (childId == null || childId.isBlank()) continue;

            CommandEntity child = find(childId);
            if (child == null) {
                LOG.debug("Child {} (listed by {}) not found", childId, parent.getId());
                continue;
            }
            CommandStatusEntity st = findCommandStatusById(child.getCommandStatusId());
            if (st == null || st.getState() == null) {
                LOG.debug("Status missing for child {}", childId);
                continue;
            }

            CommandState state;
            try {
                state = CommandState.valueOf(st.getState());
            } catch (IllegalArgumentException iae) {
                LOG.warn("Unknown state '{}' for child {}", st.getState(), childId);
                continue;
            }

            if (state == CommandState.RUNNING && runningIdx == null) {
                runningIdx = i; runningChild = child; runningSt = st;
                break; // first RUNNING wins, we can stop early
            }
            if (state == CommandState.PENDING && pendingIdx == null) {
                pendingIdx = i; pendingChild = child; pendingSt = st;
            }
        }

        // 3) Decide the "current" choice
        Integer idx = runningIdx != null ? runningIdx : pendingIdx;
        CommandEntity currentChild = runningIdx != null ? runningChild : pendingChild;
        CommandStatusEntity currentSt = runningIdx != null ? runningSt : pendingSt;

        // 4) Build/merge paramsJson with a "progress" snapshot
        Map<String, Object> params;
        try {
            Type mapType = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();
            params = (parent.getParamsJson() != null && !parent.getParamsJson().isBlank())
                    ? gson.fromJson(parent.getParamsJson(), mapType)
                    : new LinkedHashMap<>();
            if (params == null) params = new LinkedHashMap<>();
        } catch (Exception ex) {
            LOG.warn("Failed to parse paramsJson for {}: {}", parent.getId(), ex.toString());
            params = new LinkedHashMap<>();
        }

        Map<String, Object> progress = new LinkedHashMap<>();

        progress.put("currentStepId", currentChild != null ? currentChild.getId() : null);
        progress.put("currentStepIndex", (idx != null ? idx + 1 : null)); // 1-based for UI
        progress.put("totalSteps", childIds.size());
        progress.put("currentStepTitle", currentChild != null ? currentChild.getTitle() : null);
        progress.put("currentStepType", currentChild != null ? currentChild.getType() : null);
        progress.put("currentStepState", currentSt != null ? currentSt.getState() : null);
        progress.put("percentage", computePercentage(parent)); // reuse your existing helper

        params.put("progress", progress);
        LOG.info("Setting progress of CommandEntity {} to {}", parent.getId(), progress);
        parent.setParamsJson(gson.toJson(params));
        store(parent); // persist the snapshot on the root command
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractValues(Object raw) {
        if (raw instanceof Map) return (Map<String, Object>) raw;
        return java.util.Collections.emptyMap();
    }

    private Map<String, Object> loadValuesFromParams(Map<String, Object> params) {
        // Prefer file path if present
        Object valuesPathObj = params.get("valuesPath");
        if (valuesPathObj instanceof String path && !path.isBlank()) {
            String json = this.kubernetesService.getConfigurationService().readCacheFile(path);
            try {
                Type t = new TypeToken<Map<String, Object>>(){}.getType();
                Map<String,Object> m = gson.fromJson(json, t);
                return m != null ? m : java.util.Collections.emptyMap();
            } catch (Exception e) {
                LOG.warn("Invalid JSON in valuesPath {}: {}", path, e.toString());
                return java.util.Collections.emptyMap();
            }
        }
        // Fallback to inlined "values" if any (keeps backward compat)
        return extractValues(params.get("values"));
    }

    // ----- helpers for submitKeytabRequest -----
    private static Map<String, Object> headersToPersistableMap(javax.ws.rs.core.MultivaluedMap<String,String> h) {
        if (h == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        h.forEach((k, v) -> out.put(k, v == null ? List.of() : new java.util.ArrayList<>(v)));
        return out;
    }
    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    // ----- Ambari REST helpers -----
    @SuppressWarnings("unchecked")
    private String ambariSubmitGenerateAdhocKeytab(java.net.URI baseUri,
                                                   String cluster,
                                                   String principal,
                                                   Map<String,Object> callerHeaders) {
        // Build URL using UriInfo’s base (same JVM)
        String path = "/api/v1";
        if (cluster != null && !cluster.isBlank()) {
            path += "/clusters/" + url(cluster) + "/requests";
        } else {
            // If cluster omitted, Ambari can resolve via current context if configured; but cluster is normally required.
            // We keep this as a fallback to be more tolerant, but recommend supplying cluster in KeytabRequest.
            path += "/requests";
        }

        String body = """
        {
          "RequestInfo": {
            "context": "Generate ad-hoc keytab",
            "action": "GENERATE_ADHOC_KEYTAB",
            "parameters": { "principal": %s }
          },
          "Requests/resource_filters": []
        }
        """.formatted(jsonString(principal));

        HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Content-Type", "application/json")
                .header("X-Requested-By", "ambari")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));

        copySessionHeaders(b, callerHeaders);

        try {
            var http = HttpClient.newHttpClient();
            var resp = http.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                throw new IllegalStateException("Ambari action submit failed: " + resp.statusCode() + " " + resp.body());
            }
            var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
            String idText = tree.path("Requests").path("id").asText(null);
            if (idText == null || idText.isBlank()) {
                // some Ambari builds return an int
                int id = tree.path("Requests").path("id").asInt(-1);
                if (id < 0) throw new IllegalStateException("Ambari submit response missing Requests.id");
                return String.valueOf(id);
            }
            return idText;
        } catch (Exception e) {
            throw new RuntimeException("Failed to submit GENERATE_ADHOC_KEYTAB", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> ambariWaitRequestComplete(java.net.URI baseUri,
                                                         String cluster,
                                                         String requestId,
                                                         Map<String,Object> callerHeaders,
                                                         java.time.Duration timeout,
                                                         java.time.Duration poll) {
        String path = "/api/v1/clusters/" + url(cluster) + "/requests/" + url(requestId)
                + "?fields=Requests/request_status,tasks/Tasks/status,tasks/Tasks/structured_out";
        var http = HttpClient.newHttpClient();
        long deadline = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadline) {
            var b = HttpRequest.newBuilder(baseUri.resolve(path)).GET();
            copySessionHeaders(b, callerHeaders);
            try {
                var resp = http.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 300) {
                    throw new IllegalStateException("Ambari poll failed: " + resp.statusCode() + " " + resp.body());
                }
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var root = mapper.readTree(resp.body());
                String st = root.path("Requests").path("request_status").asText("");
                if ("COMPLETED".equalsIgnoreCase(st)) {
                    return mapper.convertValue(root, Map.class);
                }
                if ("FAILED".equalsIgnoreCase(st) || "ABORTED".equalsIgnoreCase(st) || "TIMEDOUT".equalsIgnoreCase(st)) {
                    throw new IllegalStateException("Ambari request ended with status: " + st);
                }
                Thread.sleep(poll.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while polling Ambari request", ie);
            } catch (Exception e) {
                throw new RuntimeException("Error polling Ambari request", e);
            }
        }
        throw new RuntimeException("Timed out waiting Ambari request " + requestId);
    }

    @SuppressWarnings("unchecked")
    private String ambariFindKeytabB64(Map<String,Object> completedJson) {
        if (completedJson == null) return null;
        Object tasksObj = completedJson.get("tasks");
        if (!(tasksObj instanceof java.util.List<?> list)) return null;
        for (Object o : list) {
            if (!(o instanceof Map<?,?> m)) continue;
            Object tasksNode = m.get("Tasks");
            if (!(tasksNode instanceof Map<?,?> t)) continue;
            Object so = t.get("structured_out");
            if (so instanceof Map<?,?> soMap) {
                Object v = soMap.get("keytab_b64");
                if (v != null) return String.valueOf(v);
            } else if (so instanceof String s && !s.isBlank()) {
                try {
                    var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
                    var n = tree.path("keytab_b64");
                    if (!n.isMissingNode()) return n.asText();
                } catch (Exception ignore) {}
            }
        }
        return null;
    }

    private static void copySessionHeaders(java.net.http.HttpRequest.Builder b, Map<String,Object> persisted) {
        if (persisted == null) return;
        // We mainly need Cookie / Authorization (depending on your Ambari auth mode)
        for (var e : persisted.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();
            if (val == null) continue;
            String value;
            if (val instanceof java.util.List<?> l) {
                value = String.join("; ", l.stream().map(String::valueOf).toList());
            } else {
                value = String.valueOf(val);
            }
            if (value.isBlank()) continue;

            if ("x-requested-by".equalsIgnoreCase(key)) continue; // we set it ourselves
            if ("cookie".equalsIgnoreCase(key)) { b.header("Cookie", value); continue; }
            if ("authorization".equalsIgnoreCase(key)) { b.header("Authorization", value); continue; }
            // copy other headers defensively
            b.header(key, value);
        }
    }

    private static String url(String s) {
        try { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }
    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String ambariApiBaseFrom(UriInfo ui) {
        String base = ui.getBaseUri().toString(); // ends with '/api/v1/views/...'
        int i = base.indexOf("/api/v1/views/");
        if (i > 0) {
            return base.substring(0, i + "/api/v1".length()); // -> http(s)://host:port/api/v1
        }
        // Fallback: if for some reason we didn’t find views segment, assume {base}/api/v1
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base; // already points to /api/v1 in all normal View requests
    }
}