package org.apache.ambari.view.k8s.service;

import com.google.gson.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandStatus;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.model.DeploymentMode;
import org.apache.ambari.view.k8s.model.RangerTrinoConfigDefaults;
import org.apache.ambari.view.k8s.model.stack.StackConfig;
import org.apache.ambari.view.k8s.model.stack.StackProperty;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.requests.KeytabRequest;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.HelmRepoRepo;
import org.apache.ambari.view.k8s.model.HelmReleaseDTO;
import com.marcnuri.helm.Release;
import org.apache.ambari.view.k8s.service.deployment.DeploymentBackend;
import org.apache.ambari.view.k8s.service.deployment.DeploymentContext;
import org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend;
import org.apache.ambari.view.k8s.service.deployment.GitClient;
import org.apache.ambari.view.k8s.service.deployment.HelmDirectBackend;

import org.apache.ambari.view.k8s.utils.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MultivaluedMap;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import java.util.Comparator;
import java.util.concurrent.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO;
import org.apache.ambari.view.k8s.dto.security.SecurityProfilesDTO;
import org.apache.ambari.view.k8s.service.SecurityProfileService;
import org.apache.ambari.view.k8s.service.SecurityMappingService;
import org.apache.ambari.view.k8s.utils.CommandUtils.AmbariConfigRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.k8s.service.ViewConfigurationService;

/**
 * Orchestrator service: transforms user requests into a durable plan (FSM) and executes
 * steps with backoff, using two pools: one for timers (wake-ups) and one for workers.
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
    private final ConfigResolutionService configResolutionService;
    private final CommandUtils commandUtils;
    private final CommandPlanFactory commandPlanFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // External dependencies (adapters) — injected
    private final HelmService helmService;
    private final KubernetesService kubernetesService;
    private final WebHookConfigurationService webHookConfigurationService;
    private final CommandLogService commandLogService;
    private final FluxGitOpsBackend fluxGitOpsBackend;
    private final Map<String, DeploymentBackend> deploymentBackends = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(1);
    private final ExecutorService helmExecutor = Executors.newCachedThreadPool();

    // Separate pools: scheduling vs execution
    private final ScheduledExecutorService timer;
    private final ExecutorService workers;

    // Singleton per View instance
    private static final ConcurrentMap<String, CommandService> INSTANCES = new ConcurrentHashMap<>();

    public static CommandService get(ViewContext ctx) {
        return INSTANCES.computeIfAbsent(ctx.getInstanceName(), k -> new CommandService(ctx));
    }

    /**
     * Expose the view context for helper adapters that still need direct access to
     * Ambari view services (filesystem, instance data, etc.).
     *
     * @return current view context bound to this service
     */
    public ViewContext getCtx() {
        return ctx;
    }
    public CommandService(ViewContext ctx) {
        this.ctx = ctx;
        this.dataStore = ctx.getDataStore();
        this.helmService = new HelmService(ctx);
        this.workers = Executors.newCachedThreadPool();
        this.kubernetesService = KubernetesService.get(ctx);
        this.repositoryDao =  new HelmRepoRepo(ctx.getDataStore());
        this.timer = Executors.newScheduledThreadPool(1);
        this.webHookConfigurationService = new WebHookConfigurationService(this.ctx, this.kubernetesService);
        this.configResolutionService = new ConfigResolutionService();
        this.commandUtils = new CommandUtils(this.ctx,this.kubernetesService);
        this.commandPlanFactory = new CommandPlanFactory(this.ctx, this.gson, this.webHookConfigurationService, this.commandUtils);
        this.commandLogService = new CommandLogService(this.ctx);
        PathConfig pathConfig = new PathConfig(ctx);
        pathConfig.ensureDirs();
        this.fluxGitOpsBackend = new FluxGitOpsBackend(pathConfig.workDir(), kubernetesService, ctx);
        // Dispatcher registry (can be extended later)
        this.deploymentBackends.put(DeploymentMode.FLUX_GITOPS.name(), fluxGitOpsBackend);
        this.deploymentBackends.put(DeploymentMode.DIRECT_HELM.name(), new HelmDirectBackend(this));
        LOG.info("CommandService initialized for {}. Consider adding DB indexes on command.id, command_status_id, created_at and pruning terminal commands periodically to keep polling fast.", ctx.getInstanceName());
    }

    /**
     * Append a line to the per-command log with minimal risk of throwing.
     * Uses async writer inside CommandLogService.
     */
    private void appendCommandLog(String commandId, String message) {
        try {
            commandLogService.append(commandId, message);
        } catch (Exception ex) {
            LOG.debug("Failed to append command log for {}: {}", commandId, ex.toString());
        }
    }

    /**
     * Start a short-lived heartbeat that logs status every 2s until the supplied future is done
     * or the commandId transitions to a terminal state.
     */
    private ScheduledFuture<?> startHeartbeat(String commandId, Callable<Boolean> stillRunningCheck) {
        return heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                boolean running = stillRunningCheck.call();
                if (!running) return;
                appendCommandLog(commandId, "Still running...");
            } catch (Exception ex) {
                appendCommandLog(commandId, "Heartbeat check failed: " + ex.toString());
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    /**
     * Run a Helm/k8s task and abort if the child status becomes CANCELED.
     * Polls every second to check cancellation.
     */
    private <T> T runWithCancellation(String commandId, String childStatusId, Callable<T> task) throws Exception {
        Future<T> future = helmExecutor.submit(task);
        for (;;) {
            try {
                return future.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                CommandStatusEntity st = findCommandStatusById(childStatusId);
                if (st != null && CommandState.CANCELED.name().equals(st.getState())) {
                    appendCommandLog(commandId, "Cancelling running task on user abort");
                    future.cancel(true);
                    throw new IllegalStateException("Canceled by user");
                }
                // continue waiting
            }
        }
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
        params.put("keyNameInSecret", AmbariActionClient.firstNonBlank(req.getKeyNameInSecret(), "service.keytab"));
        if (req.getParentCommandId() != null && !req.getParentCommandId().isBlank()) {
            params.put("parentCommandId", req.getParentCommandId());
        }

        // persist caller session/headers + baseUri to reuse in worker
        params.put("_callerHeaders", AmbariActionClient.headersToPersistableMap(callerHeaders));
        params.put("_baseUri", baseUri != null ? baseUri.toString() : null);

        // status for root
        CommandStatusEntity rootSt = new CommandStatusEntity();
        rootSt.setId(id + "-status");
        rootSt.setViewInstance(ctx.getInstanceName());
        rootSt.setCreatedBy(ctx.getUsername());
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
        c1St.setCreatedBy(ctx.getUsername());
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
        c2St.setCreatedBy(ctx.getUsername());
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

        // If a parent command id was provided, attach this keytab request under that parent for proper hierarchy.
        if (req.getParentCommandId() != null && !req.getParentCommandId().isBlank()) {
            try {
                CommandEntity parent = find(req.getParentCommandId());
                if (parent != null) {
                    List<String> parentChildren = new ArrayList<>();
                    if (parent.getChildListJson() != null && !parent.getChildListJson().isBlank()) {
                        Type listType = new TypeToken<ArrayList<String>>() {}.getType();
                        List<String> existing = gson.fromJson(parent.getChildListJson(), listType);
                        if (existing != null) parentChildren.addAll(existing);
                    }
                    if (!parentChildren.contains(id)) {
                        parentChildren.add(id);
                        parent.setChildListJson(gson.toJson(parentChildren));
                        store(parent);
                    }
                }
            } catch (Exception e) {
                LOG.warn("Failed to link keytab command {} to parent {}", id, req.getParentCommandId(), e);
            }
        }

        // queue
        scheduleNow(id);
        return id;
    }

    /**
     * Entry point for deploy requests. Creates the orchestration plan, persists it, and schedules execution.
     * Thread-safe: no shared mutable state beyond the DataStore and thread pools.
     */
    public String submitDeploy(HelmDeployRequest request, String repoId, String version, String kubeContext, String commandsURL, MultivaluedMap<String,String> callerHeaders,
                               URI baseUri, AmbariAliasResolver ambariAliasResolver) {
        // Required fields
        Objects.requireNonNull(request.getChart(), "chart is required");
        Objects.requireNonNull(request.getReleaseName(), "releaseName is required");
        Objects.requireNonNull(request.getNamespace(), "namespace is required");
        String deploymentMode = request.getDeploymentMode() == null ? "DIRECT_HELM" : request.getDeploymentMode();
        LOG.info("Submit deploy for release {} in namespace {} using deploymentMode={}",
                request.getReleaseName(), request.getNamespace(), deploymentMode);

        DeploymentContext context = new DeploymentContext(
                repoId,
                version,
                kubeContext,
                commandsURL,
                callerHeaders,
                baseUri,
                ambariAliasResolver
        );

        DeploymentBackend backend = deploymentBackends.get(deploymentMode.toUpperCase(Locale.ROOT));
        if (backend == null) {
            LOG.warn("No backend registered for deploymentMode {}, defaulting to DIRECT_HELM", deploymentMode);
            backend = deploymentBackends.get(DeploymentMode.DIRECT_HELM.name());
        }
        if (backend == null) {
            throw new IllegalStateException("No deployment backend available");
        }
        try {
            return backend.apply(request, context);
        } catch (Exception ex) {
            LOG.error("Deployment failed for {}/{} using mode {}: {}", request.getNamespace(), request.getReleaseName(), deploymentMode, ex.toString(), ex);
            throw new RuntimeException(ex);
        }
    }

    /**
     * Legacy direct Helm deployment path, extracted for backend dispatcher reuse.
     */
    public String directHelmDeploy(HelmDeployRequest request, String repoId, String version, String kubeContext, String commandsURL, MultivaluedMap<String,String> callerHeaders,
                               URI baseUri, AmbariAliasResolver ambariAliasResolver) {
        final String id = UUID.randomUUID().toString();
        final String now = Instant.now().toString();

        CommandEntity rootCommand = new CommandEntity();
        rootCommand.setId(id);
        rootCommand.setViewInstance(ctx.getInstanceName());
        rootCommand.setType(CommandType.K8S_MANAGER_RELEASE_DEPLOY.name());

        List<String> childCommands = new ArrayList<>();
        // command params
        Map<String, Object> params = new LinkedHashMap<>();
        if ((request.getValues() != null) && !request.getValues().isEmpty()) {
            String valuesJson = gson.toJson(request.getValues());
            String valuesPath = this.kubernetesService.getConfigurationService()
                    .writeCacheFile("values-" + id, valuesJson);
            params.put("valuesPath", valuesPath);
        }
        if (request.getStackConfigOverrides() != null && !request.getStackConfigOverrides().isEmpty()) {
            params.put("stackOverrides", request.getStackConfigOverrides());
        }
        if (version != null && !version.isBlank()) {
            params.put("version", version);
        }
        if (request.getSecretName() != null && !request.getSecretName().isEmpty()) {
            params.put("secretName", request.getSecretName());
        }
        if (request.getSecurityProfile() != null && !request.getSecurityProfile().isBlank()) {
            params.put("securityProfile", request.getSecurityProfile());
        }
        if (request.getDeploymentMode() != null && !request.getDeploymentMode().isBlank()) {
            params.put("deploymentMode", request.getDeploymentMode());
        }
        if (request.getGit() != null) {
            params.put("git", request.getGit());
        }
        LOG.info("recevied mounts are {} ", request.getMounts());

        String effectiveRepoId = repoId;
        if (effectiveRepoId == null || effectiveRepoId.isBlank()) {
            effectiveRepoId = request.getRepoId();
        }
        if (effectiveRepoId == null || effectiveRepoId.isBlank()) {
            try {
                ReleaseMetadataService metadataService = new ReleaseMetadataService(this.ctx);
                var meta = metadataService.find(request.getNamespace(), request.getReleaseName());
                if (meta != null && meta.getRepoId() != null && !meta.getRepoId().isBlank()) {
                    effectiveRepoId = meta.getRepoId();
                }
            } catch (Exception ex) {
                LOG.warn("Failed to resolve repoId from release metadata for {}/{}: {}", request.getNamespace(), request.getReleaseName(), ex.toString());
            }
        }
        if (effectiveRepoId == null || effectiveRepoId.isBlank()) {
            try {
                Collection<HelmRepoEntity> repos = repositoryDao.findAll();
                if (repos != null && !repos.isEmpty()) {
                    if (repos.size() == 1) {
                        HelmRepoEntity only = repos.iterator().next();
                        effectiveRepoId = only.getId();
                        LOG.info("Resolved repoId from single configured repository: {}", effectiveRepoId);
                    } else {
                        HelmRepoEntity first = repos.iterator().next();
                        effectiveRepoId = first.getId();
                        LOG.warn("Repository id not provided for release {}; defaulting to first configured repo {}. Please set repo explicitly for predictability.", request.getReleaseName(), effectiveRepoId);
                    }
                }
            } catch (Exception ex) {
                LOG.warn("Failed to inspect repositories for fallback repoId: {}", ex.toString());
            }
        }
        if (effectiveRepoId == null || effectiveRepoId.isBlank()) {
            throw new IllegalArgumentException("Repository id is required for release " + request.getReleaseName() + ". Please select a repository.");
        }
        HelmRepoEntity repository = repositoryDao.findById(effectiveRepoId);
        params.put("repoId", effectiveRepoId);
        params.put("chart", request.getChart());
        params.put("releaseName", request.getReleaseName());
        params.put("namespace", request.getNamespace());
        if (request.getServiceKey() != null && !request.getServiceKey().isBlank()) {
            params.put("serviceKey", request.getServiceKey());
            try {
                StackDefinitionService stackDefSvc = new StackDefinitionService(this.ctx);
                var serviceDef = stackDefSvc.getServiceDefinition(request.getServiceKey());
                if (serviceDef != null && serviceDef.dynamicValues != null && !serviceDef.dynamicValues.isEmpty()) {
                    params.put("dynamicValues", serviceDef.dynamicValues);
                    LOG.info("Loaded dynamicValues from service definition {} -> {}", request.getServiceKey(), serviceDef.dynamicValues);
                } else {
                    LOG.info("No dynamicValues defined for serviceKey {}", request.getServiceKey());
                }
            } catch (Exception ex) {
                LOG.warn("Could not load service definition for {} to resolve dynamicValues: {}", request.getServiceKey(), ex.toString());
            }
        }
        Map<String, String> resolvedDynamicOverrides = resolveDynamicOverrides(
                params.get("dynamicValues"),
                baseUri,
                AmbariActionClient.toAuthHeaders(callerHeaders)
        );
        if (resolvedDynamicOverrides != null && !resolvedDynamicOverrides.isEmpty()) {
            params.put("_dynamicOverrides", resolvedDynamicOverrides);
        }
        if (request.getImageGlobalRegistryProperty() != null){
            params.put("imageGlobalRegistryProperty", request.getImageGlobalRegistryProperty());
        }
        // Apply global security overrides if configured
        String resolvedSecurityProfile = resolveSecurityProfileName(request.getSecurityProfile());
        if (resolvedSecurityProfile != null && !resolvedSecurityProfile.isBlank()) {
            params.put("securityProfile", resolvedSecurityProfile);
        }
        SecurityConfigDTO securityCfg = loadSecurityConfig(resolvedSecurityProfile);
        String securityProfileHash = null;
        try {
            securityProfileHash = new SecurityProfileService(ctx).fingerprint(securityCfg);
        } catch (Exception ex) {
            LOG.warn("Could not fingerprint security profile {}: {}", resolvedSecurityProfile, ex.toString());
        }
        applySecurityOverrides(securityCfg, params);
        if (baseUri != null) {
            params.put("_baseUri", baseUri.toString());
        }
        if (callerHeaders != null) {
            params.put("_callerHeaders", AmbariActionClient.headersToPersistableMap(callerHeaders));
        }
        // command status
        CommandStatusEntity commandStatusEntity = new CommandStatusEntity();
        commandStatusEntity.setId(id + "-status");
        commandStatusEntity.setViewInstance(ctx.getInstanceName());
        commandStatusEntity.setCreatedBy(ctx.getUsername());
        commandStatusEntity.setState(CommandState.PENDING.name());
        commandStatusEntity.setCreatedAt(now);
        commandStatusEntity.setUpdatedAt(now);
        commandStatusEntity.setAttempt(0);

        rootCommand.setCommandStatusId(commandStatusEntity.getId());
        rootCommand.setTitle("Release Installation: "+request.getReleaseName());

        // creates required hadoop related secrets/config map from charts.json for before running the helm chart
        // preparing config map secrets before running commands
        AmbariActionClient ambariActionClient = null;
        List<Map<String, Object>> hadoopRequiredConfigMaps = request.getRequiredConfigMaps();
        Map<String, Map<String, Object>> ranger = request.getRanger();
        String cluster = null;
        if ((hadoopRequiredConfigMaps != null) || (ranger != null)) {
            try {
                cluster = this.commandUtils.resolveClusterName(baseUri.toString(), AmbariActionClient.toAuthHeaders(callerHeaders));
                params.put("_cluster", cluster);
            } catch (Exception ex) {
                LOG.error("Could not fetch cluster name, dynamic Values will be ignored");
            }
            ambariActionClient = new AmbariActionClient(ctx, baseUri.toString(), cluster, AmbariActionClient.toAuthHeaders(callerHeaders));
        }
        // Fallback: even when no requiredConfigMaps/ranger, we still need Ambari access for Kerberos detection
        if (ambariActionClient == null) {
            try {
                cluster = this.commandUtils.resolveClusterName(baseUri.toString(), AmbariActionClient.toAuthHeaders(callerHeaders));
                params.put("_cluster", cluster);
                ambariActionClient = new AmbariActionClient(ctx, baseUri.toString(), cluster, AmbariActionClient.toAuthHeaders(callerHeaders));
            } catch (Exception ex) {
                LOG.warn("Could not initialize AmbariActionClient for kerberos detection: {}", ex.toString());
            }
        }
        boolean kerberosEnabled = false;
        try {
            if (ambariActionClient != null) {
                String securityEnabledCluster = ambariActionClient.getDesiredConfigProperty(
                        cluster, "cluster-env", "security_enabled"
                );
                kerberosEnabled = "true".equalsIgnoreCase(securityEnabledCluster);
            } else {
                LOG.warn("AmbariActionClient is null; skipping Kerberos detection and ConfigMap creation");
            }
        } catch (Exception ex) {
            LOG.warn("Could not determine Kerberos state from Ambari, assuming disabled: {}", ex.toString());
        }

        if (kerberosEnabled) {
            LOG.info("Kerberos is enabled on cluster {}, preparing krb5.conf ConfigMap for Helm Chart", cluster);

            // Derive a unique ConfigMap name per release to avoid collisions across multiple installs
            String releaseName = request.getReleaseName() != null ? request.getReleaseName().trim() : "";
            String krb5ConfigMapName = (releaseName.isEmpty() ? "krb5-conf" : (releaseName + "-krb5-conf"));

            // 1) Create/update the ConfigMap from /etc/krb5.conf
            this.commandUtils.ensureKrb5ConfConfigMap(request.getNamespace(), krb5ConfigMapName);

            // 2) Inject Helm overrides so the chart mounts it:
            //    global.security.kerberos.enabled = true
            //    global.security.kerberos.configMapName = <cm name>
            this.commandUtils.addOverride(params, "global.security.kerberos.enabled", "true");
            this.commandUtils.addOverride(params, "global.security.kerberos.configMapName", krb5ConfigMapName);
        }

        // ---------- Truststore from Ambari truststore (for downstream TLS clients) ----------
        try {
            String tsPath = ctx.getAmbariProperty("ssl.trustStore.path");
            if (tsPath != null && !tsPath.isBlank()) {
                String tsType = Optional.ofNullable(ctx.getAmbariProperty("ssl.trustStore.type"))
                        .filter(s -> !s.isBlank()).orElse("JKS");
                String tsPassProp = Optional.ofNullable(ctx.getAmbariProperty("ssl.trustStore.password")).orElse("");
                char[] tsPass = ambariAliasResolver.resolve(ctx, tsPassProp);

                byte[] tsBytes = Files.readAllBytes(Paths.get(tsPath));
                String truststoreSecretName = request.getReleaseName() + "-truststore";
                Map<String, byte[]> data = new LinkedHashMap<>();
                data.put("truststore.jks", tsBytes);
                data.put("truststore.password", new String(tsPass).getBytes(StandardCharsets.UTF_8));

                // Also provide a PEM bundle for clients (Python, etc.)
                try {
                    KeyStore ks = WebHookConfigurationService.loadKeyStore(Paths.get(tsPath), tsPass, tsType);
                    StringBuilder pem = new StringBuilder();
                    var aliases = ks.aliases();
                    while (aliases.hasMoreElements()) {
                        String alias = aliases.nextElement();
                        Certificate cert = ks.getCertificate(alias);
                        if (cert instanceof X509Certificate x509) {
                            pem.append("-----BEGIN CERTIFICATE-----\n")
                                    .append(java.util.Base64.getMimeEncoder(64, new byte[]{'\n'})
                                            .encodeToString(x509.getEncoded()))
                                    .append("\n-----END CERTIFICATE-----\n");
                        }
                    }
                    if (pem.length() > 0) {
                        data.put("ca.crt", pem.toString().getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to build PEM bundle from Ambari truststore: {}", e.toString());
                }
                kubernetesService.createOrUpdateOpaqueSecret(
                        request.getNamespace(),
                        truststoreSecretName,
                        data
                );

                this.commandUtils.addOverride(params, "global.security.tls.enabled", "true");
                this.commandUtils.addOverride(params, "global.security.tls.truststore.enabled", "true");
                this.commandUtils.addOverride(params, "global.security.tls.truststoreSecret", truststoreSecretName);
                this.commandUtils.addOverride(params, "global.security.tls.truststoreKey", "truststore.jks");
                this.commandUtils.addOverride(params, "global.security.tls.truststorePasswordKey", "truststore.password");

                LOG.info("Provisioned truststore Secret '{}' in namespace '{}' from Ambari truststore {}", truststoreSecretName, request.getNamespace(), tsPath);
            } else {
                LOG.info("Ambari truststore path not configured; skipping truststore Secret provisioning");
            }
        } catch (Exception ex) {
            LOG.warn("Failed to provision truststore Secret from Ambari truststore: {}", ex.toString());
        }
        if (hadoopRequiredConfigMaps != null) {
            LOG.info("Parsing Required Hadoop Config Maps");
            Map<String, AmbariActionClient.AmbariConfigPropertiesTypes> defaultConfigs = ambariActionClient.getDefaultConfigTypes();
            for (Map<String, Object> spec : hadoopRequiredConfigMaps) {
                String name = spec.get("cm_name").toString();
                String kind = spec.get("cm_type").toString(); // "config-map" | "secret"
                @SuppressWarnings("unchecked")
                List<String> files = (List<String>) spec.get("config_properties");

                this.commandUtils.addOverride(params, "hadoopConf.enabled", "true");
                if (name.isBlank() || files == null || files.isEmpty()) continue;

                boolean asSecret = "secret".equalsIgnoreCase(kind);
                if (asSecret) {
                    Map<String, byte[]> data = new LinkedHashMap<>();
                    for (String f : files) {
                        if(defaultConfigs.containsKey(f)) {
                            try {
                                LOG.info("Fetching and Parsing default configs: {} from cluster {}", f, cluster);
                                Map<String, String> fetchedConfig = ambariActionClient.getDesiredConfigProperties(cluster, f);
                                String xml = HadoopSiteXml.render(fetchedConfig);
                                if (xml != null) data.put( f + ".xml", xml.getBytes(StandardCharsets.UTF_8));
                            } catch (Exception ex) {
                                LOG.error("Failed to fetch config {} from cluster {}", f, cluster);
                                throw new RuntimeException(ex);
                            }
                        }
                    }
                    if (!data.isEmpty()) {
                        LOG.info("creating config map {} holding configurations for {}", name, files.toArray().toString());
                        kubernetesService.createOrUpdateOpaqueSecret(
                                request.getNamespace(), name, data);
                    }
                } else {
                    Map<String, String> data = new LinkedHashMap<>();
                    for (String f : files) {
                        try {
                            LOG.info("Fetching and Parsing default configs: {} from cluster {}", f, cluster);
                            Map<String, String> fetchedConfig = ambariActionClient.getDesiredConfigProperties(cluster, f);
                            String xml = HadoopSiteXml.render(fetchedConfig);
                            if (xml != null) data.put(f + ".xml", xml);
                        } catch (Exception ex) {
                            LOG.error("Failed to fetch config {} from cluster {}", f, cluster);
                            throw new RuntimeException(ex);
                        }
                    }
                    if (!data.isEmpty()) {
                        LOG.info("creating secret {} holding configurations for {}", name, files.toArray().toString());
                        String hadoopConfigMapNameHelmProperty = (String) spec.get("helm_values_property");
                        if (hadoopConfigMapNameHelmProperty != null){
                            this.commandUtils.addOverride(params, hadoopConfigMapNameHelmProperty, name);
                        }
                        kubernetesService.createOrUpdateConfigMap(
                                request.getNamespace(), name, data,
                                Map.of("managed-by","ambari-k8s-view"), Map.of());
                    }
                }
            }
        }
        Map<String, Object> defaultValues;
        try {
            LOG.info("Fetchings Default values from chartName: {}, with repoId: {}, version: {}" ,request.getChart(), repoId, version);
            LOG.info("Constructed repository name: {}", repository.getName());
            defaultValues = this.helmService.getRepositoryService().showValuesAsMap(request.getChart() , repoId, version);
        } catch (Exception e) {
            LOG.warn("Execution Planner: failed to compute default helm values for chart {}: {}",
                    request.getChart(), e.toString());
            throw new RuntimeException(e);
        }
        Map<String, Object> overrides = this.commandUtils.loadValuesFromParams(params);
        if (overrides == null) {
            overrides = Collections.emptyMap();
        }

        Map<String, Object> effectiveValues = this.configResolutionService.deepMerge(defaultValues, overrides);
        if (effectiveValues.isEmpty()) {
            LOG.error("CRITICAL: effectiveValues is EMPTY. 'helm show values' failed or parsing failed.");
        }

        List<Map<String, Object>> resolvedEndpoints = this.configResolutionService
                .computeEndpointsForRelease(request.getEndpoints(), params, effectiveValues, ambariActionClient, cluster);
        if (resolvedEndpoints == null) {
            resolvedEndpoints = java.util.Collections.emptyList();
        }
        LOG.info("Computed list of endpoints: {}", resolvedEndpoints.toArray().toString());

        try {
            ReleaseMetadataService metadataService = new ReleaseMetadataService(this.ctx);
            GlobalConfigService globalConfigService = new GlobalConfigService();
            String globalFingerprint = globalConfigService.fingerprint();
            metadataService.recordInstallOrUpgrade(
                    request.getNamespace(),
                    request.getReleaseName(),
                    request.getServiceKey(),
                    request.getChart(),
                    effectiveRepoId,
                    version,
                    effectiveValues,
                    id,                  // deploymentId -> command id for traceability
                    resolvedEndpoints,   // ✅ the computed list
                    globalFingerprint,
                    resolvedSecurityProfile,
                    securityProfileHash,
                    request.getDeploymentMode(),
                    null, // gitCommitSha (set by GitOps backend in future)
                    null, // gitBranch
                    null, // gitPath
                    null, // gitRepoUrl
                    null, // gitCredentialAlias
                    null, // gitCommitMode
                    null, // gitPrUrl
                    null, // gitPrNumber
                    null  // gitPrState
            );
        } catch (Exception ex) {
            LOG.warn("Failed to record metadata/endpoints for {}/{}: {}", request.getNamespace(), request.getReleaseName(), ex.toString());
        }
        if (ranger != null) {
            Map<String, String> extraConfigs = new HashMap<>();
            LOG.info("Required ranger plugin installation detected");
            for (Map.Entry<String, Map<String, Object>> rangerRequest : ranger.entrySet()) {
                Map<String, Object> spec = rangerRequest.getValue();
                LOG.info("Reading ranger item: {}", spec);
                String type = spec.get("type").toString();        // e.g. "ranger-security", "ranger-audit", "ranger-policymgr-ssl",
                if (type.isBlank()) continue;
                String name = null;
                String kind = null;
                String serviceName = null;
                boolean asSecret = false;
                if (!type.equals("ranger-plugin-settings")){
                    name = spec.get("cm_name").toString();
                    kind = spec.get("cm_type").toString();     // "config-map" | "secret"
                    serviceName = spec.get("service_type").toString();     // "config-map" | "secret"
                    asSecret = "secret".equalsIgnoreCase(kind);
                }
                String xml = "";
                String xmlPutName= "";
                String rangerRepositoryName = cluster+ "_"+ request.getNamespace();
                boolean doCreation = true;


                switch (type) {
                    case "ranger-plugin-settings":
                        LOG.info("Generating ranger-plugin-settings for releaseName: {}", request.getReleaseName());
                        String helmValuesRangerRepositoryNameKey = spec.get("repository_name_property").toString();
                        this.commandUtils.addOverride(params, helmValuesRangerRepositoryNameKey, rangerRepositoryName);
                        LOG.info("Overriding ranger-plugin-settings helm value: {} with {}",helmValuesRangerRepositoryNameKey, rangerRepositoryName);
                        String chartName = request.getChart();
                        if (chartName == null || chartName.isBlank()) {
                            LOG.warn("ranger-plugin-settings: chart name missing in params, skipping effective values computation");
                            break;
                        }
                        extraConfigs.putAll(this.configResolutionService.computeExtraRangerConfigs(
                                spec,               // "ranger-plugin-settings" spec (with extraConfigs section)
                                params,             // deploy params (namespace, releaseName, _cluster, etc.)
                                effectiveValues,    // merged helm values: defaults + overrides
                                ambariActionClient, // Ambari client for ambari-config sources
                                cluster             // resolved Ambari cluster name
                        ));
                        break;
                    case "ranger-security": {
                        LOG.info("Xml Type detected is ranger-security");
                        Map<String, String> rangerSecurityPluginConfig = RangerTrinoConfigDefaults.security(serviceName);
                        rangerSecurityPluginConfig.putAll(RangerTrinoConfigDefaults.pluginProperties(serviceName));

                        String rangerPolicyMgrSSLFilePath = (String) spec.get("ranger_policymgr_file");
                        String rangerPolicyMgrSSLFilePathHelmValue = (String) spec.get("helm_values_ranger_policymgr_file");
                        this.commandUtils.addOverride(params, rangerPolicyMgrSSLFilePathHelmValue, rangerPolicyMgrSSLFilePath);

                        String rangerPluginSuperUsers = (String) spec.get("superusers");
                        if (rangerPluginSuperUsers != null && !rangerPluginSuperUsers.isBlank()){
                            rangerSecurityPluginConfig.put(
                                    "ranger.plugin." + serviceName + ".super.users", rangerPluginSuperUsers
                            );
                        }

                        String rangerPolicyCacheDir = (String) spec.get("ranger_policycache_dir");
                        String rangerPolicyCacheDirHelmProperty = (String) spec.get("ranger_policycache_dir_helm_property");
                        if ( rangerPolicyCacheDirHelmProperty != null && rangerPolicyCacheDir != null ){
                            if ( !rangerPolicyCacheDirHelmProperty.isBlank() && !rangerPolicyCacheDir.isBlank()){
                                LOG.info("Configuring helm chart with: {}:{}", rangerPolicyCacheDirHelmProperty, rangerPolicyCacheDir);
                                LOG.info("Configuring ranger-security file with: {}:{}", "ranger.plugin." + serviceName + ".policy.cache.dir", rangerPolicyCacheDir);
                                rangerSecurityPluginConfig.put("ranger.plugin." + serviceName + ".policy.cache.dir", rangerPolicyCacheDir);
                                extraConfigs.put(rangerPolicyCacheDirHelmProperty,rangerPolicyCacheDir);
                            }
                        }
                        try {
                            String securityEnabledCluster = ambariActionClient.getDesiredConfigProperty(
                                    cluster, "cluster-env", "security_enabled"
                            );
                            var securityEnabled = (securityEnabledCluster.toLowerCase().equals("true"));
                            if (securityEnabled){
                                LOG.info("Kerberos is configured on the cluster {}, configuring Kerberos related Ranger Plugin configs", cluster);
                                String kerberosRealm = ambariActionClient.getDesiredConfigProperty(
                                        cluster,
                                        this.commandUtils.DYNAMIC_SOURCE_MAP.get("AMBARI_KERBEROS_REALM").type,
                                        this.commandUtils.DYNAMIC_SOURCE_MAP.get("AMBARI_KERBEROS_REALM").key
                                );
                                String rangerPluginUserPrincipalPrefix = (String) spec.get("krb5_princ_srv");
                                if( rangerPluginUserPrincipalPrefix == null ){
                                    LOG.error("Kerberos is configured on the cluster {}, required config krb5_princ_srv is missing in {}", cluster, type);
                                }else {
                                    rangerPluginUserPrincipalPrefix = rangerPluginUserPrincipalPrefix + "-" + request.getNamespace() ;
                                    rangerSecurityPluginConfig.put(
                                            "ranger.plugin." + serviceName + ".ugi.login.type", "keytab"
                                    );
                                    rangerSecurityPluginConfig.put(
                                            "ranger.plugin." + serviceName + ".ugi.keytab.principal",
                                            rangerPluginUserPrincipalPrefix + "@" + kerberosRealm
                                    );
                                    rangerSecurityPluginConfig.put(
                                            "ranger.plugin." + serviceName + ".ugi.keytab.file",
                                            "/etc/security/keytabs/service.keytab"
                                    );
                                    rangerSecurityPluginConfig.put(
                                            "ranger.plugin." + serviceName + ".ugi.initialize",
                                            "true"
                                    );
                                    extraConfigs.put("policy.download.auth.users", rangerPluginUserPrincipalPrefix);
                                    extraConfigs.put("tag.download.auth.users", rangerPluginUserPrincipalPrefix);
                                    LOG.info("Ranger Plugin Kerberos Principal is {} with realm: {}", rangerPluginUserPrincipalPrefix, kerberosRealm);
                                }
                            }else{
                                LOG.info("Kerberos is not enabled on the cluster {}, configuring simple auth/password", cluster);
                                // --- plugin REST client auth (username + password from Secret) ---
                                String rangerPolicyPluginUserName = (String) spec.get("plugin_username");
                                // Optional override for secret name / key from spec; otherwise use sane defaults
                                String pluginPasswordSecretName =
                                        (String) spec.getOrDefault("plugin_password_secret_name", "ranger-plugin-rest-client-password");
                                String pluginPasswordSecretKey =
                                        (String) spec.getOrDefault("plugin_password_secret_k", "password");

                                String pluginPassword = null;

                                if (rangerPolicyPluginUserName != null && !rangerPolicyPluginUserName.isBlank()) {
                                    // store for later (RANGER_REPOSITORY_CREATION step)
                                    params.put("_rangerPluginUserName", rangerPolicyPluginUserName);

                                    // 1) try to reuse existing Secret
                                    pluginPassword = kubernetesService
                                            .readOpaqueSecretKeyAsString(request.getNamespace(), pluginPasswordSecretName, pluginPasswordSecretKey)
                                            .orElse(null);

                                    // 2) generate password if Secret does not exist or key not present
                                    if (pluginPassword == null) {
                                        LOG.info("Generating new secret for storing Ranger plugin REST client password", pluginPasswordSecretName);
                                        pluginPassword = UUID.randomUUID().toString(); // or something stronger if you care

                                        Map<String, byte[]> pwdData = new LinkedHashMap<>();
                                        pwdData.put(pluginPasswordSecretKey, pluginPassword.getBytes(StandardCharsets.UTF_8));

                                        LOG.info("Creating new Secret {} for Ranger plugin REST client password in namespace {}",
                                                pluginPasswordSecretName, request.getNamespace());
                                        kubernetesService.createOrUpdateOpaqueSecret(
                                                request.getNamespace(),
                                                pluginPasswordSecretName,
                                                pwdData
                                        );
                                    } else {
                                        LOG.info("Reusing existing Secret {} for Ranger plugin REST client password", pluginPasswordSecretName);
                                    }

                                    // store for later Ambari call
                                    params.put("_rangerPluginUserPassword", pluginPassword);
                                    params.put("_rangerPluginUserPasswordSecretName", pluginPasswordSecretName);
                                    params.put("_rangerPluginUserPasswordSecretKey", pluginPasswordSecretKey);

                                    // Configure plugin properties with the correct keys:
                                    rangerSecurityPluginConfig.put(
                                            "ranger.plugin." + serviceName + ".policy.rest.client.username",
                                            rangerPolicyPluginUserName);
                                    rangerSecurityPluginConfig.put(
                                            "ranger.plugin." + serviceName + ".policy.rest.client.password",
                                            pluginPassword);
                                } else {
                                    LOG.info("No ranger plugin username defined in spec; REST client basic auth will not be configured.");
                                }
                            }
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }

                        // configuring plugin main settings on ssl and repository name
                        rangerSecurityPluginConfig.put("ranger.plugin." + serviceName + ".service.name", rangerRepositoryName);
                        rangerSecurityPluginConfig.put("ranger.plugin." + serviceName + ".policy.rest.ssl.config.file", rangerPolicyMgrSSLFilePath);

                        LOG.info("Configuring Ranger repository name: {}", rangerRepositoryName);
                        params.put("_rangerRepositoryName", rangerRepositoryName);

                        // fetch policy mgr external url
                        try {
                            String rangerPolicyMgrExternalUrl = ambariActionClient.getDesiredConfigProperty(
                                    cluster, "admin-properties", "policymgr_external_url"
                            );
                            rangerSecurityPluginConfig.put("ranger.plugin." + serviceName + ".policy.rest.url", rangerPolicyMgrExternalUrl);
                            LOG.info("Configuring Ranger policymgr external url: {}", rangerPolicyMgrExternalUrl);
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }

                        xml = HadoopSiteXml.render(rangerSecurityPluginConfig);
                        String helmValuesRangerAuditPluginKey = spec.get("helm_values_property").toString();
                        this.commandUtils.addOverride(params, helmValuesRangerAuditPluginKey, name);
                        xmlPutName = name + ".xml";
                        break;
                    }
                    case "ranger-audit": {
                        LOG.info("Xml Type detected is ranger-audit");
                        xml = HadoopSiteXml.render(RangerTrinoConfigDefaults.audit());
                        String helmValuesRangerAuditPluginKey = spec.get("helm_values_property").toString();
                        this.commandUtils.addOverride(params, helmValuesRangerAuditPluginKey, name);
                        xmlPutName = name + ".xml";
                        break;
                    }
                    case "ranger-policymgr-ssl": {
                        LOG.info("Xml Type detected is ranger-policymgr-ssl");
                        Map<String, String> policymgrSsl = RangerTrinoConfigDefaults.policymgrSsl();
                        // get bytes from wherever you keep them (Ambari/Secret/file)
                        // we use the exact same logic as webhook bootstrap to read the defaut truststore when required

                        // Configuring Hadoop credential path in chart override
                        // hadoop credential provider should come into two part 1 - the directory  and 2 - the file name

                        // 1 - parsing here the file directory
                        String rangerPluginCredentialProviderPath = (String) spec
                                .get("ranger_plugin_credential_provider_path");
                        String rangerPluginCredentialProviderPathHelmProperty = (String) spec
                                .get("ranger_plugin_credential_provider_path_helm_prop");
                        if (rangerPluginCredentialProviderPath != null && rangerPluginCredentialProviderPathHelmProperty != null){
                            this.commandUtils.addOverride(params, rangerPluginCredentialProviderPathHelmProperty, rangerPluginCredentialProviderPath);
                        }
                        // 2 - parsing here the file name
                        String rangerPluginCredentialProviderFile = (String) spec.get("ranger_plugin_credential_provider_file");
                        String rangerPluginCredentialProviderFileHelmProperty = (String) spec.get("ranger_plugin_credential_provider_file_helm_prop");

                        if ( rangerPluginCredentialProviderFile != null && rangerPluginCredentialProviderFileHelmProperty != null){
                            this.commandUtils.addOverride(params, rangerPluginCredentialProviderFileHelmProperty, rangerPluginCredentialProviderFile);
                        }

                        // 3 - parsing the secret Name
                        String rangerPluginCredentialProviderSecretName = spec
                                .get("ranger_plugin_credential_provider_secretName").toString();
                        String rangerPluginCredentialProviderSecretNameHelmProperty = spec
                                .get("ranger_plugin_credential_provider_secretName_helm_prop").toString();
                        this.commandUtils.addOverride(params, rangerPluginCredentialProviderSecretNameHelmProperty, rangerPluginCredentialProviderSecretName);

                        LOG.info("Configuring chart with ranger plugin credential provider path {}", rangerPluginCredentialProviderPath+"/"+rangerPluginCredentialProviderFile);
                        final String truststorePath = ctx.getAmbariProperty("ssl.trustStore.path");
                        boolean truststoreExists = (truststorePath != null && !truststorePath.isBlank());
                        if (truststoreExists) {
                            try {
                                final String truststoreType = Optional.ofNullable(ctx.getAmbariProperty("ssl.trustStore.type"))
                                        .filter(s -> !s.isBlank()).orElse("JKS");

                                // Read property (could be plain or ${alias=...})
                                final String truststorePassProp = Optional.ofNullable(ctx.getAmbariProperty("ssl.trustStore.password"))
                                        .orElse("");

                                LOG.info("Successfully loaded local truststore password");
//                                            char[] truststorePass = ambariAliasResolver.resolve(ctx, truststorePassProp);
                                char[] truststorePass = ambariAliasResolver.resolve(ctx, truststorePassProp);
                                RangerCredentialWriter writer = new RangerCredentialWriter(kubernetesService);

                                LOG.info("Adding sslTrustStore alias into Hadoop Credential Provider file {}", rangerPluginCredentialProviderPath+"/"+rangerPluginCredentialProviderFile);
                                Map<String,String> props = writer.ensureJceksSecretForTruststorePassword(
                                        /* namespace   */ request.getNamespace(),
                                        /* secretName  */ rangerPluginCredentialProviderSecretName,
                                        /* mountPath   */ rangerPluginCredentialProviderPath+"/"+rangerPluginCredentialProviderFile,
                                        /* alias       */ "sslTrustStore",
                                        /* password    */ truststorePass,
                                        /* labels      */ Map.of("app", serviceName),
                                        /* annotations */ Map.of("purpose","ranger-ssl-password")
                                );
                                policymgrSsl.put("xasecure.policymgr.clientssl.truststore.password",new String("crypted"));
                                policymgrSsl.putAll(props);
                                /**
                                 * we can read from the RangerRESTClient.java that xasecure.policymgr.clientssl.truststore.password is simply ignored
                                 * starting with ranger 2.5.0, so we need to create a hadoop credential provider
                                 *
                                 */

                                // start of the hadop credential provider store creation
                                // we need to create it first locally in the /tmp folder before uploading it as a secret


                            }catch (Exception ex){
                                LOG.error("Failed to load default truststore JKS");
                                throw  ex;
                            }
                        }
                        xml = HadoopSiteXml.render(policymgrSsl);
                        String helmValuesRangerAuditPluginKey = spec.get("helm_values_property").toString();
                        this.commandUtils.addOverride(params, helmValuesRangerAuditPluginKey, name);

                        xmlPutName = name + "-"+ serviceName + ".xml";
                        break;
                    }
                    case "ranger-admin-truststore": {
                        LOG.info("Xml Type detected is ranger-admin-truststore");
                        LOG.info("Generating custom action to create truststore from ambari'ca");
                        // the helm property which designated the k8s secret where the truststore is contained
                        // the value of the secret name is in cm_name
                        String helmValuesTruststoreSecretNameKey = spec.get("helm_values_property").toString();
                        this.commandUtils.addOverride(params, helmValuesTruststoreSecretNameKey, name);

                        // the helm property which designates the k8s secret where the truststore'password is contained
                        String helmValuesTruststorePasswordSecretNameKey = spec.get("truststore_password_helm_values_property").toString();
                        String helmValuesTruststorePasswordSecretNameValue = spec.get("truststore_password_secret_name").toString();
                        this.commandUtils.addOverride(params, helmValuesTruststorePasswordSecretNameKey, helmValuesTruststorePasswordSecretNameValue);

                        // the helm property which designates the truststore file path
                        // example ranger.admin.ssl.truststore.fileKey
                        String helmValuesTruststorePasswordFileNameKey = spec.get("truststore_file_key_helm_values_property").toString();
                        String helmValuesTruststorePasswordFileNameValue = spec.get("truststore_file_key").toString(); // example truststore.jks
                        this.commandUtils.addOverride(params, helmValuesTruststorePasswordFileNameKey, helmValuesTruststorePasswordFileNameValue);

                        // the name of the password key in the secret
                        String helmValuesTruststorePasswordSecretNameDataKey = spec.get("truststore_password_secret_key_name").toString();
                        String helmValuesTruststorePasswordSecretNameDataValue = spec.get("truststore_password_secret_key_value").toString();
                        this.commandUtils.addOverride(params, helmValuesTruststorePasswordSecretNameDataKey, helmValuesTruststorePasswordSecretNameDataValue);

                        // get bytes from wherever you keep them (Ambari/Secret/file)
                        // we use the exact same logic as webhook bootstrap to read the defaut truststore when required
                        final String truststorePath = ctx.getAmbariProperty("ssl.trustStore.path");
                        boolean truststoreExists = (truststorePath != null && !truststorePath.isBlank());
                        if (truststoreExists) {
                            try {
                                final String truststoreType = Optional.ofNullable(ctx.getAmbariProperty("ssl.trustStore.type"))
                                        .filter(s -> !s.isBlank()).orElse("JKS");

                                // Read property (could be plain or ${alias=...})
                                final String truststorePassProp = Optional.ofNullable(ctx.getAmbariProperty("ssl.trustStore.password"))
                                        .orElse("");

                                char[] truststorePass = ambariAliasResolver.resolve(ctx, truststorePassProp);
                                LOG.info("Creating truststore secret with data key {}, and as secret: {}",helmValuesTruststorePasswordFileNameValue, name );
                                this.kubernetesService.ensureOrRotateTruststoreSecretFromLocalFile(
                                        request.getNamespace(),
                                        name, truststorePath, truststoreType, truststorePass, helmValuesTruststorePasswordFileNameValue,365,null
                                );
                                LOG.info("Creating truststore'password secret with data key {}, and as secret: {}",helmValuesTruststorePasswordFileNameValue, helmValuesTruststorePasswordSecretNameValue );
                                this.kubernetesService.createOrUpdateOpaqueSecret (
                                        request.getNamespace(),
                                        helmValuesTruststorePasswordSecretNameValue, helmValuesTruststorePasswordSecretNameDataValue, new String(truststorePass).getBytes(StandardCharsets.UTF_8)
                                );
                                xmlPutName = helmValuesTruststorePasswordFileNameValue;

                            }catch (Exception ex){
                                LOG.error("Failed to load default truststore JKS");
                                throw  ex;
                            }
                        }
                        doCreation = false;
                        break;
                    }
                    case "ranger-admin-tls-client": {
                        // Map.of("tls.crt", crtBytes, "tls.key", keyBytes)
//                        Map<String, byte[]> tls = obtainTlsKeypair();
//                        if (tls != null && !tls.isEmpty()) {
//                            kubernetesService.createOrUpdateOpaqueSecret(req.getNamespace(), name, tls);
//                        }
                        break;
                    }
                    default:
                        // ignore unknown
                }
                if (!type.equals("ranger-plugin-settings") && doCreation){
                    LOG.info("creating configmap {}", name);
                    if (asSecret){
                        Map<String, byte[]> data = new LinkedHashMap<>();
                        if (xmlPutName.isBlank() && xmlPutName.equals("")) xmlPutName = name;
                        LOG.info("Inserting key: {} in secret {}",xmlPutName, name);
                        if (xml != null) data.put(xmlPutName, xml.getBytes(StandardCharsets.UTF_8));
                        this.kubernetesService.createOrUpdateOpaqueSecret(request.getNamespace(), name, data);
                    }else{
                        Map<String, String> data = new LinkedHashMap<>();
                        if (xmlPutName.isBlank() && xmlPutName.equals("")) xmlPutName = name;
                        LOG.info("Inserting key: {} in configmap {}",xmlPutName, name);
                        if (xml != null) data.put(xmlPutName, xml);
                        this.kubernetesService.createOrUpdateConfigMap(
                                request.getNamespace(), name, data,
                                Map.of("managed-by","ambari-k8s-view"), Map.of());
                    }
                }

            }
            // 3) Attach them to params so RANGER_REPOSITORY_CREATION can use them later
            if (!extraConfigs.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, String> existing =
                        (Map<String, String>) params.get("_rangerServiceConfigs");
                if (existing == null) {
                    existing = new LinkedHashMap<>();
                    params.put("_rangerServiceConfigs", existing);
                }
                existing.putAll(extraConfigs);
                LOG.info("Computed extra Ranger service configs: {}", extraConfigs);
            }
            this.commandPlanFactory.createRangerPluginRepository(rootCommand, ranger, params, childCommands);
        }
        // storing the Command Status
        store(commandStatusEntity);

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
            mountsStatus.setCreatedBy(ctx.getUsername());
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
        if (repoId != null && !repoId.isBlank()) {
        String helmRepoLoginId = this.commandPlanFactory.createHelmRepoCommand(rootCommand, effectiveRepoId);
            childCommands.add(helmRepoLoginId);
        } else {
            LOG.warn("No repoId provided; skipping Helm repo login step for {}", request.getReleaseName());
        }

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
                            this.commandPlanFactory.createDependencyCommands(rootCommand, dependency.getValue(), dependency.getKey(), repoId, commandsURL,  callerHeaders, baseUri);
                        }
                );
            }
        }

        // Dependencies appended their steps directly into the root child list, so refresh our in-memory list
        try {
            Type listType = new TypeToken<ArrayList<String>>(){}.getType();
            List<String> merged = gson.fromJson(rootCommand.getChildListJson(), listType);
            if (merged != null) {
                childCommands = merged;
            } else {
                childCommands = new ArrayList<>();
            }
        } catch (Exception ex) {
            LOG.warn("Failed to refresh child list after dependencies, continuing with existing list: {}", ex.toString());
        }

        // 2bis. Materialize stack configurations into Secrets before Helm install
        if (request.getServiceKey() != null && !request.getServiceKey().isBlank()) {
            String cfgCmdId = this.commandPlanFactory.createConfigMaterializeCommand(
                    rootCommand,
                    request.getServiceKey(),
                    request.getReleaseName(),
                    request.getNamespace(),
                    request.getStackConfigOverrides());
            childCommands.add(cfgCmdId);
            rootCommand.setChildListJson(gson.toJson(childCommands));
            store(rootCommand);
        }

        /**
         * 3. Doing the dry run of the real chart installation
         *
         */
        this.commandPlanFactory.createRealChartInstallationCommands(rootCommand, repoId, true);
        this.commandPlanFactory.createRealChartInstallationCommands(rootCommand, repoId, false);

        // Publish correlation ConfigMap so the mutating webhook can attach keytab requests to this command tree.
        try {
            String cmName = "k8s-view-metadata";
            Map<String, String> data = new HashMap<>();
            data.put(request.getReleaseName() + ".commandId", id);
            this.kubernetesService.createOrUpdateConfigMap(
                    request.getNamespace(),
                    cmName,
                    data,
                    Map.of("managed-by", "ambari-k8s-view"),
                    Map.of()
            );
            LOG.info("Published command correlation ConfigMap {} in namespace {} for release {}", cmName, request.getNamespace(), request.getReleaseName());
        } catch (Exception e) {
            LOG.warn("Could not write correlation ConfigMap for release {} in namespace {}: {}", request.getReleaseName(), request.getNamespace(), e.toString());
        }

        LOG.info("Queued DEPLOY_COMPOSITE id={} steps={} at {}", id, childCommands.size(), now);
        scheduleNow(id);
        return id;
    }

    /**
     * Retrieve a direct Helm release status for the given release. Uses Helm CLI via HelmService
     * and filters the result. This is intentionally lightweight and does not hit Flux.
     */
    public HelmReleaseDTO directHelmStatus(String namespace, String releaseName) {
        try {
            final String kubeconfigContent = new ViewConfigurationService(ctx).getKubeconfigContents();
            List<com.marcnuri.helm.Release> releases = helmService.list(namespace, kubeconfigContent);
            return releases.stream()
                    .filter(r -> releaseName.equals(r.getName()))
                    .findFirst()
                    .map(HelmReleaseDTO::from)
                    .orElse(null);
        } catch (Exception ex) {
            LOG.warn("directHelmStatus failed for {}/{}: {}", namespace, releaseName, ex.toString());
            return null;
        }
    }

    /**
     * Uninstall a release via direct Helm (no GitOps path).
     */
    public void directHelmDestroy(String namespace, String releaseName) {
        try {
            final String kubeconfigContent = new ViewConfigurationService(ctx).getKubeconfigContents();
            new HelmService(ctx).uninstall(namespace, releaseName, kubeconfigContent);
        } catch (Exception ex) {
            LOG.warn("directHelmDestroy failed for {}/{}: {}", namespace, releaseName, ex.toString());
            throw new RuntimeException(ex);
        }
    }

    /**
     * Destroy a release using the correct backend based on deploymentMode.
     */
    public void destroyViaBackend(String namespace, String releaseName, String deploymentMode) {
        String mode = deploymentMode == null ? DeploymentMode.DIRECT_HELM.name() : deploymentMode.toUpperCase(Locale.ROOT);
        DeploymentBackend backend = deploymentBackends.get(mode);
        if (backend == null) {
            LOG.warn("No backend found for mode {} when destroying {}/{}; falling back to direct Helm", mode, namespace, releaseName);
            directHelmDestroy(namespace, releaseName);
            return;
        }
        try {
            backend.destroy(namespace, releaseName);
        } catch (Exception ex) {
            LOG.warn("Backend destroy failed for mode {} {}/{}: {}", mode, namespace, releaseName, ex.toString());
            throw new RuntimeException(ex);
        }
    }

    /**
     * Status using the correct backend (Flux vs direct).
     */
    public HelmReleaseDTO statusViaBackend(String namespace, String releaseName, String deploymentMode) {
        String mode = deploymentMode == null ? DeploymentMode.DIRECT_HELM.name() : deploymentMode.toUpperCase(Locale.ROOT);
        DeploymentBackend backend = deploymentBackends.get(mode);
        if (backend == null) {
            LOG.warn("No backend found for mode {} when checking status of {}/{}; falling back to direct Helm", mode, namespace, releaseName);
            return directHelmStatus(namespace, releaseName);
        }
        try {
            return backend.status(namespace, releaseName);
        } catch (Exception ex) {
            LOG.warn("Backend status failed for mode {} {}/{}: {}", mode, namespace, releaseName, ex.toString());
            return null;
        }
    }

    public CommandStatus getStatus(String id) {
        CommandEntity e = find(id);
        CommandStatusEntity eStatus = findCommandStatusById(e.getCommandStatusId());
        if (e == null) return null;
        CommandStatus s = new CommandStatus();
        s.id = e.getId();
        s.hasChildren = (e.getChildListJson() != null && !e.getChildListJson().isBlank());
        s.type = CommandType.valueOf(e.getType());
        s.state = CommandState.valueOf(eStatus.getState());
        s.message = eStatus.getMessage();
        s.createdBy = eStatus.getCreatedBy();
        s.createdAt = eStatus.getCreatedAt();
        s.updatedAt = eStatus.getUpdatedAt();
        s.percent = cachedPercentage(e);
        LOG.info("Getting status information for command with id: {} ", s.id);
        LOG.debug("Params are: {} ", e.getParamsJson());

        int stepIndex = java.util.Optional.ofNullable(resolveParamsJson(e))
                .map(JsonParser::parseString)
                .map(JsonElement::getAsJsonObject)
                .map(o -> o.getAsJsonObject("progress"))
                .map(p -> p.get("currentStepIndex"))
                .map(JsonElement::getAsInt)
                .orElse(-1);
        if (stepIndex == -1) stepIndex = 0; // convert to 0-based
        s.step = stepIndex;
        return s;
    }

    /**
     * Returns a paginated list of recent commands (sorted by updatedAt desc).
     */
    public List<CommandStatus> listCommands(int limit, int offset) {
        Collection<CommandEntity> all;
        try {
            all = dataStore.findAll(CommandEntity.class, null);
        } catch (PersistenceException e) {
            throw new RuntimeException(e);
        }
        List<CommandStatus> out = new ArrayList<>();
        for (CommandEntity e : all) {
            // Heuristic: only list roots (those with a plan/children)
            if (e.getChildListJson() == null || e.getChildListJson().isBlank()) continue;
            CommandStatusEntity st = findCommandStatusById(e.getCommandStatusId());
            if (st == null) continue;
            CommandStatus cs = new CommandStatus();
            cs.id = e.getId();
            cs.hasChildren = true;
            cs.type = CommandType.valueOf(e.getType());
            cs.state = CommandState.valueOf(st.getState());
            cs.message = (e.getTitle() != null && !e.getTitle().isBlank()) ? e.getTitle() : st.getMessage();
            cs.createdBy = st.getCreatedBy();
            cs.createdAt = st.getCreatedAt();
            cs.updatedAt = st.getUpdatedAt();
            cs.percent = computePercentage(e);
            cs.step = 0;
            out.add(cs);
        }
        out.sort(Comparator.comparing((CommandStatus c) -> c.updatedAt == null ? "" : c.updatedAt).reversed());
        int from = Math.min(offset, out.size());
        int to = Math.min(out.size(), from + limit);
        return out.subList(from, to);
    }

    /**
     * List direct children of a command.
     */
    public List<CommandStatus> listChildren(String parentId) {
        CommandEntity parent = find(parentId);
        if (parent == null || parent.getChildListJson() == null || parent.getChildListJson().isBlank()) {
            return Collections.emptyList();
        }
        List<CommandStatus> out = new ArrayList<>();
        try {
            Type listType = new TypeToken<ArrayList<String>>() {}.getType();
            List<String> childIds = gson.fromJson(parent.getChildListJson(), listType);
            if (childIds == null) return Collections.emptyList();
            for (String cid : childIds) {
                if (cid == null || cid.isBlank()) continue;
                CommandEntity child = find(cid);
                if (child == null) continue;
                CommandStatusEntity st = findCommandStatusById(child.getCommandStatusId());
                if (st == null) continue;
                CommandStatus cs = new CommandStatus();
                cs.id = child.getId();
                cs.hasChildren = (child.getChildListJson() != null && !child.getChildListJson().isBlank());
                cs.type = CommandType.valueOf(child.getType());
                cs.state = CommandState.valueOf(st.getState());
                cs.message = (child.getTitle() != null && !child.getTitle().isBlank()) ? child.getTitle() : st.getMessage();
                cs.createdBy = st.getCreatedBy();
                cs.createdAt = st.getCreatedAt();
                cs.updatedAt = st.getUpdatedAt();
                cs.percent = computePercentage(child);
                if (!cs.hasChildren && (cs.state == CommandState.SUCCEEDED || cs.state == CommandState.FAILED || cs.state == CommandState.CANCELED)) {
                    cs.percent = 100;
                }
                cs.step = 0;
                out.add(cs);
            }
        } catch (Exception ex) {
            LOG.warn("Failed to list children for {}: {}", parentId, ex.toString());
        }
        return out;
    }

    /**
     * Try to reuse precomputed progress percentage when available to avoid
     * scanning all children on every poll. Falls back to computePercentage.
     */
    private int cachedPercentage(CommandEntity cmd) {
        try {
            if (cmd.getParamsJson() != null) {
                String resolved = resolveParamsJson(cmd);
                JsonObject obj = JsonParser.parseString(resolved).getAsJsonObject();
                if (obj.has("progress")) {
                    JsonObject prog = obj.getAsJsonObject("progress");
                    if (prog.has("percentage") && prog.get("percentage").isJsonPrimitive()) {
                        JsonPrimitive prim = prog.getAsJsonPrimitive("percentage");
                        if (prim.isNumber()) {
                            return prim.getAsInt();
                        }
                        if (prim.isString()) {
                            String pstr = prim.getAsString();
                            try { return Integer.parseInt(pstr.replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception ex) {
            LOG.debug("Failed to reuse cached percentage for {}: {}", cmd.getId(), ex.toString());
        }
        return computePercentage(cmd);
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
        cancelRecursive(id);
    }

    private void cancelRecursive(String id) {
        CommandEntity e = find(id);
        if (e == null) return;
        CommandStatusEntity eStatus = findCommandStatusById(e.getCommandStatusId());
        if (eStatus != null
                && !CommandState.SUCCEEDED.name().equals(eStatus.getState())
                && !CommandState.FAILED.name().equals(eStatus.getState())
                && !CommandState.CANCELED.name().equals(eStatus.getState())) {
            eStatus.setState(CommandState.CANCELED.name());
            eStatus.setUpdatedAt(Instant.now().toString());
            eStatus.setMessage("Canceled by user");
            store(eStatus);
            store(e);
            try { commandLogService.append(id, "Canceled by user"); } catch (Exception ignored) {}
        }

        // Propagate to children
        if (e.getChildListJson() != null && !e.getChildListJson().isBlank()) {
            try {
                Type listType = new TypeToken<ArrayList<String>>() {}.getType();
                List<String> childIds = gson.fromJson(e.getChildListJson(), listType);
                if (childIds != null) {
                    for (String cid : childIds) {
                        if (cid != null && !cid.isBlank()) {
                            cancelRecursive(cid);
                        }
                    }
                }
            } catch (Exception ex) {
                LOG.warn("Failed to propagate cancel for {}: {}", id, ex.toString());
            }
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

    // recursive method

    private void runNextStep(String id) {
        // 0) Load root & status (the orchestrator is the root command id)
        CommandEntity root = find(id);
        if (root == null) return;

        LOG.info("Running next step for command with id: {} ", root.getId());
        Type mapType = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();
        String resolvedRootParams = resolveParamsJson(root);
        Map<String, Object> rootParams = gson.fromJson(resolvedRootParams, mapType);
        CommandStatusEntity rootSt = findCommandStatusById(root.getCommandStatusId());
        if (rootSt == null) return;
        if (isTerminal(rootSt.getState())) return; // terminal guard
        if (CommandState.CANCELED.name().equals(rootSt.getState())) {
            LOG.info("Command {} is canceled, stopping execution", id);
            return;
        }

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
            if (CommandState.CANCELED.name().equals(st.getState())) {
                fail(rootSt, "Canceled by user");
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
            appendCommandLog(id, "Starting step: " + (child.getTitle() != null ? child.getTitle() : child.getType()));
            childSt.setMessage("Starting: " + (child.getTitle() != null ? child.getTitle() : child.getType()));
            childSt.setUpdatedAt(Instant.now().toString());
            store(childSt);
        }

        // Keep UI step snapshot up-to-date
        refreshCurrentStepSnapshot(root);

        // 7) Execute the selected child step
        try {
            // Build a HelmDeployRequest from root params for the generic step methods you already have
            HelmDeployRequest rootReq = this.commandUtils.buildRequestFromRootParams(root);
            LOG.info("[{}] Executing step {}/{}: {}",
                    id,
                    (pickedRunning ? runningIdx + 1 : pendingIdx + 1),
                    childIds.size(),
                    (child.getTitle() != null ? child.getTitle() : child.getType())
            );
            try { commandLogService.append(id, "Executing step: " + (child.getTitle() != null ? child.getTitle() : child.getType())); } catch (Exception ignored) {}
//            read repoId from rootCommandEntoty in params
            mapType = new TypeToken<LinkedHashMap<String, Object>>() {
            }.getType();
            String rawChildParams = resolveParamsJson(child);
            Map<String, Object> childParams = gson.fromJson(rawChildParams, mapType);

            String repoId = (String) rootParams.get("repoId");
            LOG.info("Using repoId: {} for step execution", repoId);
            LOG.debug("Child Command params are: {} ", child.getParamsJson());
            switch (CommandType.valueOf(child.getType())) {
                case HELM_REPO_LOGIN -> {
                    LOG.info("Performing helm repo login for repoId: {} ", repoId);
                    appendCommandLog(id, "Helm repo login for repoId=" + repoId);
                    stepRepoLogin(rootReq, repoId);
                    appendCommandLog(id, "Helm repo login completed for repoId=" + repoId);
                }
                case POST_VERIFY -> stepPostVerify(rootReq);
                case K8S_MOUNTS_DIR_EXEC -> {

                    String namespace = (String) childParams.get("namespace");
                    String releaseName = (String) childParams.get("releaseName");

                    this.kubernetesService.createNamespace(namespace);
                    this.kubernetesService.ensureWebhookEnabledNamespace(namespace);

                    Map<String, Object> mounts = this.commandUtils.normalizeMountsObject(childParams.get("mounts"));
                    LOG.info("Mounts execution for release {} in ns {}: {}", releaseName, namespace, mounts);
                    appendCommandLog(id, "Creating mounts for " + releaseName + " in " + namespace + " mounts=" + mounts.keySet());

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
                    Object imageTagObj = childParams.get("imageTag");
                    String imageTag = null;
                    if (imageTagObj instanceof String) {
                        imageTag = (String) imageTagObj;
                    }
                    String imageRepository = (String) childParams.get("imageRepository");
                    String imageGlobalRegistryProperty = (String) childParams.get("imageGlobalRegistryProperty");
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
                    /*
                     here we parse the image property of a deendency which allow ambari to override images name for each chart
                     it was the first implementation, now we do use a global registry which is easier to override
                     */
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
                    /**
                     the logic is to part images[] direct which can look like:
                     "images": [
                     "kerberos-keytab-mutating-webhook"
                     ], for hand custom images
                     or
                     "images": [
                     "kerberos-keytab-mutating-webhook:custom-image:tag"
                     ], for chart images override with tag override
                     or
                     "images": [
                     "kerberos-keytab-mutating-webhook:custom-image"
                     ], for chart images override with default tag

                     */
                    for (String img : images) {
                        if (!img.contains(":")) {
                            overrideProperties.put("image.repository", imageRepository + "/" + img);
                            overrideProperties.put("image.registry", repository.getUrl());
                            LOG.info("Injecting in values.yaml image.repository value: {}", imageRepository + "/" + img);
                            LOG.info("Injecting in values.yaml image.registry   value: {}", repository.getUrl());
                            if (imageTag != null){
                                overrideProperties.put("image.tag", imageTag);
                                LOG.info("Injecting in values.yaml image.tag        value: {}", imageTag);
                            }else{
                                LOG.warn("No tag specified for image: {} and no default imageTag provided", img);
                                LOG.warn("Skipping injection of image." + imageRepository + ".tag value. The default chart value will be used.");
                            }
                        } else {
                            String[] imgParts = img.split(":");
                            overrideProperties.put("image." + imgParts[0] + ".repository", imageRepository + "/" + imgParts[1]);
                            overrideProperties.put("image." + imgParts[0] + ".registry", repository.getUrl());
                            LOG.info("Injecting in values.yaml image." + imgParts[0] + ".repository value: {}", imageRepository + "/" + imgParts[1]);
                            LOG.info("Injecting in values.yaml image." + imgParts[0] + ".registry   value: {}", repository.getUrl());
                            if (imgParts.length > 2) {
                                LOG.info("Injecting in values.yaml image." + imgParts[0] + ".tag        value: {}", imgParts[2]);
                                overrideProperties.put("image." + imgParts[0] + ".tag", imgParts[2]);
                            }else{
                                if (imageTag != null){
                                    LOG.info("Injecting in values.yaml image." + imgParts[0] + ".tag        value: {}", imageTag);
                                    overrideProperties.put("image." + imgParts[0] + ".tag", imageTag);
                                }else{
                                    LOG.warn("No tag specified for image: {} and no default imageTag provided", img);
                                    LOG.warn("Skipping injection of image." + imgParts[0] + ".tag value. The default chart value will be used.");
                                }
                            }

                        }
                    }

                    /*
                    we parse the isWebhook
                    if the dependency is a webhook, we need to setup authentication and a lot of wiring between
                    the mutating-webhook and ambari
                     */
                    boolean isWebhook = false;
                    Object isWebhookObj = childParams.get("isWebhook");
                    if (isWebhookObj instanceof Boolean) {
                        isWebhook = (Boolean) isWebhookObj;
                    } else if (isWebhookObj instanceof String) {
                        isWebhook = Boolean.parseBoolean((String) isWebhookObj);
                    }
                    /**
                     * the fullname override allow us to control the name of the instance, and as a consequence
                     * to fix secrets or svc names
                     */
                    Object fullnameOverrideObj = childParams.get("fullnameOverride");
                    String fullnameOverride = null;
                    if (fullnameOverrideObj instanceof String) {
                        fullnameOverride = (String) fullnameOverrideObj;
                        overrideProperties.put("fullnameOverride", fullnameOverride);
                        LOG.info("Injecting fullnameOverride property with value: {} for dependency: {}", fullnameOverride, chartName);
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


                    // Always merge dynamic overrides if present (e.g., AMBARI_KERBEROS_REALM -> global.security.kerberos.realm)
                    this.configResolutionService.mergeDynamicOverrides(childParams, overrideProperties);
                    /**
                     * take care of creating the namespace if it does not exist
                     *
                     */
                    String namespace = (String) childParams.get("namespace");
                    this.kubernetesService.createNamespace(namespace);
                    this.kubernetesService.ensureWebhookEnabledNamespace(namespace);
                    String releaseName = (String) childParams.get("releaseName");
                    if (secretName != null) {
                        LOG.info("Ensuring image pull secret: {} in namespace: {} for service accounts: {} ", secretName, namespace, serviceAccounts);
                        this.helmService.ensureImagePullSecretFromRepo(repoId, namespace, (String) secretName, serviceAccounts);
                        overrideProperties.put("imagePullSecrets[0].name", (String) secretName);
                    }
                    if (imageGlobalRegistryProperty != null && !imageGlobalRegistryProperty.isEmpty()) {
                        overrideProperties.put(imageGlobalRegistryProperty, imageRepository);
                        LOG.info("Injecting in values.yaml global image registry property: {} with value: {}", imageGlobalRegistryProperty, imageRepository);
                    }
                    LOG.info("Performing dry-run of dependency release: {} in namespace: {} with chart: {} and version: {} ", releaseName, namespace, chartName, chartVersion);
                    appendCommandLog(id, "Dry-run dependency: release=" + releaseName + " chart=" + chartName + " version=" + chartVersion + " repoId=" + repoId);
                    ScheduledFuture<?> heartbeat = startHeartbeat(id, () -> {
                        CommandStatusEntity st = findCommandStatusById(child.getCommandStatusId());
                        return st != null && CommandState.RUNNING.name().equals(st.getState());
                    });
                    try {
                        runWithCancellation(id, child.getCommandStatusId(), () ->
                                this.helmService.deployOrUpgrade(
                                        chartName,
                                        releaseName,
                                        namespace, childParams, overrideProperties, this.kubernetesService.getConfigurationService().getKubeconfigContents(), repoId, chartVersion, true));
                    } finally {
                        if (heartbeat != null) heartbeat.cancel(false);
                    }
                }
                case HELM_DEPLOY_DEPENDENCY_RELEASE -> {
                    String chartName = (String) childParams.get("chart");
                    String chartVersion = (String) childParams.get("chartVersion");
                    String namespace = (String) childParams.get("namespace");
                    String releaseName = (String) childParams.get("releaseName");
                    Object imageTagObj = childParams.get("imageTag");
                    String imageTag = null;
                    if (imageTagObj instanceof String) {
                        imageTag = (String) imageTagObj;
                    }
                    String imageRepository = (String) childParams.get("imageRepository");
                    String imageGlobalRegistryProperty = (String) childParams.get("imageGlobalRegistryProperty");
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
                    appendCommandLog(id, "Install dependency: release=" + releaseName + " chart=" + chartName + " version=" + chartVersion + " repoId=" + repoId);
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
                    /**
                     the logic is to part images[] direct which can look like:
                     "images": [
                     "kerberos-keytab-mutating-webhook"
                     ], for hand custom images
                     or
                     "images": [
                     "kerberos-keytab-mutating-webhook:custom-image:tag"
                     ], for chart images override with tag override
                     or
                     "images": [
                     "kerberos-keytab-mutating-webhook:custom-image"
                     ], for chart images override with default tag

                     */
                    for (String img : images) {
                        if (!img.contains(":")) {

                            overrideProperties.put("image.repository", imageRepository + "/" + img);
                            overrideProperties.put("image.registry", repository.getUrl());
                            LOG.info("Injecting in values.yaml image.repository value: {}", imageRepository + "/" + img);
                            LOG.info("Injecting in values.yaml image.registry   value: {}", repository.getUrl());
                            if (imageTag != null){
                                overrideProperties.put("image.tag", imageTag);
                                LOG.info("Injecting in values.yaml image.tag        value: {}", imageTag);
                            }else{
                                LOG.warn("No tag specified for image: {} and no default imageTag provided", img);
                                LOG.warn("Skipping injection of image." + imageRepository + ".tag value. The default chart value will be used.");
                            }
                        } else {
                            String[] imgParts = img.split(":");
                            overrideProperties.put("image." + imgParts[0] + ".repository", imageRepository + "/" + imgParts[1]);
                            overrideProperties.put("image." + imgParts[0] + ".registry", repository.getUrl());
                            LOG.info("Injecting in values.yaml image." + imgParts[0] + ".repository value: {}", imageRepository + "/" + imgParts[1]);
                            LOG.info("Injecting in values.yaml image." + imgParts[0] + ".registry   value: {}", repository.getUrl());
                            if (imgParts.length > 2) {
                                LOG.info("Injecting in values.yaml image." + imgParts[0] + ".tag        value: {}", imgParts[2]);
                                overrideProperties.put("image." + imgParts[0] + ".tag", imgParts[2]);
                            }else{
                                if (imageTag != null){
                                    LOG.info("Injecting in values.yaml image." + imgParts[0] + ".tag        value: {}", imageTag);
                                    overrideProperties.put("image." + imgParts[0] + ".tag", imageTag);
                                }else{
                                    LOG.warn("No tag specified for image: {} and no default imageTag provided", img);
                                    LOG.warn("Skipping injection of image." + imgParts[0] + ".tag value. The default chart value will be used.");
                                }
                            }

                        }
                    }
                    Object fullnameOverrideObj = childParams.get("fullnameOverride");
                    String fullnameOverride = null;
                    if (fullnameOverrideObj instanceof String) {
                        fullnameOverride = (String) fullnameOverrideObj;
                        overrideProperties.put("fullnameOverride", fullnameOverride);
                        LOG.info("Injecting fullnameOverride property with value: {} for dependency: {}", fullnameOverride, chartName);
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

                    // Always merge dynamic overrides (e.g., AMBARI_KERBEROS_REALM) for dependency installs
                    this.configResolutionService.mergeDynamicOverrides(childParams, overrideProperties);

                    if (secretName != null) {
                        LOG.info("Ensuring image pull secret: {} in namespace: {} for service accounts: {} ", secretName, namespace, serviceAccounts);
                        this.helmService.ensureImagePullSecretFromRepo(repoId, namespace, (String) secretName, serviceAccounts);
                        overrideProperties.put("imagePullSecrets[0].name", (String) secretName);
                    }
                    if (imageGlobalRegistryProperty != null && !imageGlobalRegistryProperty.isEmpty()) {
                        overrideProperties.put(imageGlobalRegistryProperty, imageRepository);
                        LOG.info("Injecting in values.yaml global image registry property: {} with value: {}", imageGlobalRegistryProperty, imageRepository);
                    }
                    ScheduledFuture<?> heartbeat = startHeartbeat(id, () -> {
                        CommandStatusEntity st = findCommandStatusById(child.getCommandStatusId());
                        return st != null && CommandState.RUNNING.name().equals(st.getState());
                    });
                    try {
                        runWithCancellation(id, child.getCommandStatusId(), () ->
                                this.helmService.deployOrUpgrade(
                                        chartName,
                                        releaseName,
                                        namespace, childParams, overrideProperties, this.kubernetesService.getConfigurationService().getKubeconfigContents(), repoId, chartVersion, false));
                    } finally {
                        if (heartbeat != null) heartbeat.cancel(false);
                    }

                    // Persist dependency release metadata with version so UI can render it
                    try {
                        ReleaseMetadataService metadataService = new ReleaseMetadataService(this.ctx);
                        GlobalConfigService globalConfigService = new GlobalConfigService();
                        String globalFingerprint = globalConfigService.fingerprint();
                        metadataService.recordInstallOrUpgrade(
                                namespace,
                                releaseName,
                                null,            // serviceKey (unknown for deps)
                                chartName,
                                repoId,
                                chartVersion,
                                null,            // values hash not needed here
                                null,            // deploymentId
                                java.util.Collections.emptyList(),
                                globalFingerprint,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        );
                    } catch (Exception ex) {
                        LOG.warn("Failed to record dependency metadata for {}/{}: {}", namespace, releaseName, ex.toString());
                    }
                }
                case RANGER_REPOSITORY_CREATION -> {
                    // this step will create the Ranger plugin repository for the chart plugin
                    String chartName   = (String) childParams.get("chart");
                    String namespace   = (String) childParams.get("namespace");
                    String cluster     = (String) childParams.get("_cluster");
                    String releaseName = (String) childParams.get("releaseName");
                    String version     = (String) childParams.get("version");

                    String baseUriStr = (String) childParams.get("_baseUri");
                    if (baseUriStr == null || baseUriStr.isBlank()) {
                        throw new IllegalStateException("Missing baseUri in command params (_baseUri)");
                    }
                    URI baseUri = URI.create(baseUriStr);

                    // Build Ambari API base from the same host the View is running on
                    String ambariApiBase = baseUri.resolve("/api/v1").toString();

                    // Auth headers from the original caller session
                    Map<String,String> authHeaders =
                            AmbariActionClient.toAuthHeaders(childParams.get("_callerHeaders"));

                    // Ranger repository name (computed earlier during submitDeploy & stored in params)
                    String rangerRepositoryName = (String) childParams.get("_rangerRepositoryName");
                    if (rangerRepositoryName == null || rangerRepositoryName.isBlank()) {
                        throw new IllegalStateException("Missing Ranger repository name in params (_rangerRepositoryName)");
                    }

                    LOG.info("Preparing Ranger plugin repository creation: repo='{}', chart='{}', release='{}', ns='{}', cluster='{}'",
                            rangerRepositoryName, chartName, releaseName, namespace, cluster);

                    // Timeout handling (seconds)
                    Integer timeoutSeconds = null;
                    Object timeoutObj = childParams.get("timeout");
                    if (timeoutObj instanceof Number) {
                        timeoutSeconds = ((Number) timeoutObj).intValue();
                    } else if (timeoutObj instanceof String s && !s.isBlank()) {
                        try { timeoutSeconds = Integer.parseInt(s.trim()); } catch (NumberFormatException ignore) {}
                    }
                    if (timeoutSeconds == null) timeoutSeconds = 600;

                    // Client that talks to Ambari’s REST API with the caller session
                    var ambariActionClient = new AmbariActionClient(ctx, ambariApiBase, cluster, authHeaders);

                    // ---- Resolve plugin user / password from params or ranger spec ----
                    String pluginUserName = (String) childParams.get("_rangerPluginUserName");
                    String pluginUserPassword = (String) childParams.get("_rangerPluginUserPassword");


                    // Default serviceType unless overridden from params
                    String serviceType = "trino";
                    Object stObj = childParams.get("_rangerServiceType");
                    if (stObj instanceof String s && !s.isBlank()) {
                        serviceType = s;
                    }

                    String repoDescription =
                            "Ranger repository '" + rangerRepositoryName + "' for release '" + releaseName
                                    + "' (chart=" + chartName + ", namespace=" + namespace + ")";

                    String context =
                            "Configure Ranger plugin for repository '" + rangerRepositoryName + "'";

                    Map<String, String> rangerServiceConfigs =
                            (Map<String, String>) childParams.get("_rangerServiceConfigs");

                    LOG.info("Submitting Ambari Ranger plugin repository request: repo='{}', serviceType='{}', pluginUser='{}', rangerServiceConfig={}",
                            rangerRepositoryName, serviceType, pluginUserName,rangerServiceConfigs);

                    int reqId = ambariActionClient.submitRangerPluginRepository(
                            rangerRepositoryName,
                            serviceType,
                            pluginUserName,
                            pluginUserPassword,
                            timeoutSeconds,
                            context,
                            repoDescription,
                            rangerServiceConfigs   // <--- pass the map straight through
                    );

                    LOG.info("Ambari Ranger plugin repository request submitted, requestId={}", reqId);

                    boolean ok = ambariActionClient.waitUntilComplete(
                            reqId,
                            timeoutSeconds.longValue(),
                            java.util.concurrent.TimeUnit.SECONDS
                    );
                    if (!ok) {
                        throw new IllegalStateException(
                                "Ambari Ranger plugin repository request " + reqId + " did not complete successfully");
                    }

                    LOG.info("Ranger plugin repository '{}' configured successfully via Ambari request {}",
                            rangerRepositoryName, reqId);

                    // optional: store a tiny result payload
                    Map<String,Object> res = new LinkedHashMap<>();
                    res.put("rangerRepositoryName", rangerRepositoryName);
                    res.put("requestId", reqId);
                    childSt.setResultJson(gson.toJson(res));
                }
                // ... inside runNextStep ...
                case CONFIG_MATERIALIZE -> {
                    String serviceName = (String) childParams.get("serviceName");
                    String releaseName = (String) childParams.get("releaseName");
                    String namespace   = (String) childParams.get("namespace");
                    Map<String, Object> overrides = (Map<String, Object>) childParams.get("stackOverrides");
                    if (overrides == null) overrides = Collections.emptyMap();

                    // 1. Load the Base Definitions
                    StackDefinitionService stackDef = new StackDefinitionService();
                    List<StackConfig> configs = stackDef.getServiceConfigurations(serviceName);

                    // 2. Ensure Namespace
                    kubernetesService.createNamespace(namespace);

                    // 3. Process each Config File (e.g., superset-env, superset-files)
                    for (StackConfig cfg : configs) {
                        String secretName = releaseName + "-" + cfg.name; // Convention: <release>-<configName>
                        Map<String, byte[]> secretData = new LinkedHashMap<>();

                        for (StackProperty prop : cfg.properties) {
                            // Key to find override: "configName/propName"
                            String overrideKey = cfg.name + "/" + prop.name;
                            Object finalValue = overrides.containsKey(overrideKey) ? overrides.get(overrideKey) : prop.value;

                            if (finalValue == null) continue;

                            // If type is content/template, the property name is the filename
                            String dataKey = "content".equals(prop.type) ? prop.name : prop.name;

                            secretData.put(dataKey, String.valueOf(finalValue).getBytes(StandardCharsets.UTF_8));
                        }

                        if (!secretData.isEmpty()) {
                            LOG.info("Materializing Secret {} in {}", secretName, namespace);
                            Map<String, String> labels = Map.of(
                                    "managed-by", "ambari-k8s-view",
                                    "ambari.clemlab.com/service", serviceName,
                                    "ambari.clemlab.com/release", releaseName
                            );
                            // Create/Update the secret in K8s
                            kubernetesService.createOrUpdateOpaqueSecret(namespace, secretName, secretData);
                        }
                    }
                }
                case HELM_DEPLOY_DRY_RUN -> {
                    String chartName = (String) childParams.get("chart");
                    String namespace = (String) childParams.get("namespace");
                    String releaseName = (String) childParams.get("releaseName");
                    String version = (String) childParams.get("version");
                    String imageGlobalRegistryProperty = (String) childParams.get("imageGlobalRegistryProperty");
                    Object valuesObj = childParams.get("values");

                    Map<String, Object> valuesMap = this.commandUtils.loadValuesFromParams(childParams);
                    Map<String, String> overrideProperties = new HashMap<>();
                    // Collect explicit override pairs encoded as:
                    //   _o_<name>_k   -> helm.path.to.override
                    //   _o_<name>_v -> value-to-set
//                    Map<String, String> overrideKeys = new LinkedHashMap<>();
//                    Map<String, String> overrideValues = new LinkedHashMap<>();


//                    for (Map.Entry<String, Object> e : childParams.entrySet()) {
//                        String k = e.getKey();
//                        if (k == null || !k.startsWith("_o_")) continue;
//                        Object v = e.getValue();
//                        if (v == null) continue;
//                        String s = String.valueOf(v).trim();
//                        if (s.isEmpty()) continue;
//
//                        if (k.endsWith("_k")) {
//                            String name = k.substring("_o_".length(), k.length() - "_k".length());
//                            if (!name.isBlank()) overrideKeys.put(name, s);
//                        } else if (k.endsWith("_v")) {
//                            String name = k.substring("_o_".length(), k.length() - "_v".length());
//                            if (!name.isBlank()) overrideValues.put(name, s);
//                        }
//                    }
                    // 1) load shared overrides from "_ov"
                    Object ovRaw = childParams.get("_ov");
                    if (ovRaw instanceof Map<?, ?> ovMap) {
                        for (Map.Entry<?, ?> e : ovMap.entrySet()) {
                            if (e.getKey() == null || e.getValue() == null) continue;
                            String helmPath = String.valueOf(e.getKey()).trim();
                            String val = String.valueOf(e.getValue());
                            if (!helmPath.isEmpty()) {
                                overrideProperties.put(helmPath, val);
                                LOG.info("Applying explicit override {} -> {}", helmPath, val);
                            }
                        }
                    }
                    // For each override that has both key and value, inject into overrideProperties
//                    for (String name : overrideKeys.keySet()) {
//                        if (overrideValues.containsKey(name)) {
//                            String helmPath = overrideKeys.get(name);
//                            String val = overrideValues.get(name);
//                            if (helmPath != null && !helmPath.isBlank() && val != null) {
//                                overrideProperties.put(helmPath, val);
//                                LOG.info("Applying explicit override {} -> {}", helmPath, val);
//                            }
//                        } else {
//                            LOG.warn("Ignoring override '{}' because matching _v is missing", name);
//                        }
//                    }

                    // Apply dynamic value bindings (e.g., AMBARI_KERBEROS_REALM) on top of explicit overrides
                    this.configResolutionService.mergeDynamicOverrides(childParams, overrideProperties);

                    this.kubernetesService.createNamespace(namespace);
                    this.kubernetesService.ensureWebhookEnabledNamespace(namespace);
                    Object secretName = childParams.get("secretName");
                    if (secretName != null) {
                        LOG.info("Ensuring image pull secret: {} in namespace: {} for service accounts: {} ", secretName, namespace, null);
                        this.helmService.ensureImagePullSecretFromRepo(repoId, namespace, (String) secretName, null);
                        overrideProperties.put("imagePullSecrets[0].name", (String) secretName);
                        LOG.info("TODO//: remove global.imagePullSecrets hardcoded Ensuring image pull secret: {} in namespace: {} for service accounts: {} ", secretName, namespace, null);
                        overrideProperties.put("global.imagePullSecrets[0].name", (String) secretName);
                    }

                    HelmRepoEntity repository = repositoryDao.findById(repoId);
                    if (imageGlobalRegistryProperty != null && !imageGlobalRegistryProperty.isEmpty()) {
                        LOG.info("Resolving Repository Name for: {}", this.helmService.getRepositoryService().getEffectiveImageRegistry(repoId));
                        overrideProperties.put(imageGlobalRegistryProperty, this.helmService.getRepositoryService().getEffectiveImageRegistry(repoId));
                        LOG.info("Execution Action {}: Injecting in values.yaml global image registry property: {} with value: {}", CommandType.valueOf(child.getType()), this.helmService.getRepositoryService().getImageRegistry(repoId), imageGlobalRegistryProperty);
                    }else {
                        overrideProperties.put("global.imageRegistry", this.helmService.getRepositoryService().getEffectiveImageRegistry(repoId));
                        LOG.info("Execution Action {}: Injecting 'global.imageRegistry' in values.yaml with value: {}", CommandType.valueOf(child.getType()), this.helmService.getRepositoryService().getEffectiveImageRegistry(repoId));
                    }

                    appendCommandLog(id, "Dry-run main release: " + releaseName + " chart=" + chartName + " version=" + version + " repoId=" + repoId);
                    ScheduledFuture<?> heartbeat = startHeartbeat(id, () -> {
                        CommandStatusEntity st = findCommandStatusById(child.getCommandStatusId());
                        return st != null && CommandState.RUNNING.name().equals(st.getState());
                    });
                    try {
                        runWithCancellation(id, child.getCommandStatusId(), () ->
                                this.helmService.deployOrUpgrade(
                                        chartName,
                                        releaseName,
                                        namespace, valuesMap, overrideProperties, this.kubernetesService.getConfigurationService().getKubeconfigContents(), repoId, version, true));
                        this.kubernetesService.ensureReleaseLabelsOnIngresses(namespace, releaseName);
                    } finally {
                        if (heartbeat != null) heartbeat.cancel(false);
                    }
                }
                case HELM_DEPLOY -> {
                    String chartName = (String) childParams.get("chart");
                    String namespace = (String) childParams.get("namespace");
                    String releaseName = (String) childParams.get("releaseName");
                    String version = (String) childParams.get("version");
                    String imageGlobalRegistryProperty = (String) childParams.get("imageGlobalRegistryProperty");
                    String cluster     = (String) childParams.get("_cluster");

                    Map<String, Object> valuesMap = this.commandUtils.loadValuesFromParams(childParams);
                    Map<String, String> overrideProperties = new HashMap<>();

                    Object ovRaw = childParams.get("_ov");
                    if (ovRaw instanceof Map<?, ?> ovMap) {
                        for (Map.Entry<?, ?> e : ovMap.entrySet()) {
                            if (e.getKey() == null || e.getValue() == null) continue;
                            String helmPath = String.valueOf(e.getKey()).trim();
                            String val = String.valueOf(e.getValue());
                            if (!helmPath.isEmpty()) {
                                overrideProperties.put(helmPath, val);
                                LOG.info("Applying explicit override {} -> {}", helmPath, val);
                            }
                        }
                    }


                    // Apply dynamic value bindings (e.g., AMBARI_KERBEROS_REALM) on top of explicit overrides
                    this.configResolutionService.mergeDynamicOverrides(childParams, overrideProperties);

                    this.kubernetesService.createNamespace(namespace);
                    this.kubernetesService.ensureWebhookEnabledNamespace(namespace);
                    Object secretName = childParams.get("secretName");
                    if (secretName != null) {
                        LOG.info("Ensuring image pull secret: {} in namespace: {} for service accounts: {} ", secretName, namespace, null);
                        this.helmService.ensureImagePullSecretFromRepo(repoId, namespace, (String) secretName, null);
                        overrideProperties.put("imagePullSecrets[0].name", (String) secretName);
                        LOG.info("TODO RUN//: remove global.imagePullSecrets hardcoded Ensuring image pull secret: {} in namespace: {} for service accounts: {} ", secretName, namespace, null);
                        overrideProperties.put("global.imagePullSecrets[0].name", (String) secretName);
                    }

                    if (imageGlobalRegistryProperty != null && !imageGlobalRegistryProperty.isEmpty()) {
                        LOG.info("Resolving Repository Name for: {}", this.helmService.getRepositoryService().getEffectiveImageRegistry(repoId));
                        overrideProperties.put(imageGlobalRegistryProperty, this.helmService.getRepositoryService().getEffectiveImageRegistry(repoId));
                        LOG.info("Execution Action {}: Injecting in values.yaml global image registry property: {} with value: {}", CommandType.valueOf(child.getType()), this.helmService.getRepositoryService().getImageRegistry(repoId), imageGlobalRegistryProperty);
                    }else {
                        overrideProperties.put("global.imageRegistry", this.helmService.getRepositoryService().getEffectiveImageRegistry(repoId));
                        LOG.info("Execution Action {}: Injecting 'global.imageRegistry' in values.yaml with value: {}", CommandType.valueOf(child.getType()), this.helmService.getRepositoryService().getEffectiveImageRegistry(repoId));
                    }
                    /**
                     * service and images should ne able to be configured one by one for the repositories
                     * so right now the repositories are read from the charts Values.yaml
                     * in the clemlab powered charts, there is a global.registry configuration
                     */
                    appendCommandLog(id, "Installing release: " + releaseName + " chart=" + chartName + " version=" + version + " repoId=" + repoId);
                    ScheduledFuture<?> heartbeatDeploy = startHeartbeat(id, () -> {
                        CommandStatusEntity st = findCommandStatusById(child.getCommandStatusId());
                        return st != null && CommandState.RUNNING.name().equals(st.getState());
                    });
                    try {
                        runWithCancellation(id, child.getCommandStatusId(), () ->
                                this.helmService.deployOrUpgrade(
                                        chartName,
                                        releaseName,
                                        namespace, valuesMap, overrideProperties, this.kubernetesService.getConfigurationService().getKubeconfigContents(), repoId, version, false));
                        this.kubernetesService.ensureReleaseLabelsOnIngresses(namespace, releaseName);
                    } finally {
                        if (heartbeatDeploy != null) heartbeatDeploy.cancel(false);
                    }

                    /** storing metadata for the deployed helm release **/
//                    @SuppressWarnings("unchecked")
//                    List<Map<String, Object>> endpointSpecs =
//                            (List<Map<String, Object>>) childParams.get("endpoints");
//
//                    Map<String, Object> templateEnv = new LinkedHashMap<>();
//                    templateEnv.put("releaseName", releaseName);
//                    templateEnv.put("namespace", namespace);
//
//                    String baseUriStr = (String) childParams.get("_baseUri");
//                    if (baseUriStr == null || baseUriStr.isBlank()) {
//                        throw new IllegalStateException("Missing baseUri in command params (_baseUri)");
//                    }
//                    URI baseUri = URI.create(baseUriStr);
//
//                    // Build Ambari API base from the same host the View is running on
//                    String ambariApiBase = baseUri.resolve("/api/v1").toString();
//
//                    // Auth headers from the original caller session
//                    Map<String,String> authHeaders =
//                            AmbariActionClient.toAuthHeaders(childParams.get("_callerHeaders"));
//
//                    // Ranger repository name (computed earlier during submitDeploy & stored in params)
//                    String rangerRepositoryName = (String) childParams.get("_rangerRepositoryName");
//                    if (rangerRepositoryName == null || rangerRepositoryName.isBlank()) {
//                        throw new IllegalStateException("Missing Ranger repository name in params (_rangerRepositoryName)");
//                    }
//
//                    LOG.info("Preparing Ranger plugin repository creation: repo='{}', chart='{}', release='{}', ns='{}', cluster='{}'",
//                            rangerRepositoryName, chartName, releaseName, namespace, cluster);
//
//                    // Timeout handling (seconds)
//                    Integer timeoutSeconds = null;
//                    Object timeoutObj = childParams.get("timeout");
//                    if (timeoutObj instanceof Number) {
//                        timeoutSeconds = ((Number) timeoutObj).intValue();
//                    } else if (timeoutObj instanceof String s && !s.isBlank()) {
//                        try { timeoutSeconds = Integer.parseInt(s.trim()); } catch (NumberFormatException ignore) {}
//                    }
//                    if (timeoutSeconds == null) timeoutSeconds = 600;
//
//                    // Client that talks to Ambari’s REST API with the caller session
//                    var ambariActionClient = new AmbariActionClient(ctx, ambariApiBase, cluster, authHeaders);


                }
                // in CommandService.runNextStep(...) switch
                case KEYTAB_ISSUE_PRINCIPAL -> {
                    LOG.info("KEYTAB_ISSUE_PRINCIPAL step starting for child id={} title={}", child.getId(), child.getTitle());
                    LOG.info("Request params: principalFqdn={}, cluster={}, namespace={}, secretName={}, keyNameInSecret={}, baseUri={}",
                            childParams.get("principalFqdn"),
                            childParams.get("cluster"),
                            childParams.get("namespace"),
                            childParams.get("secretName"),
                            childParams.get("keyNameInSecret"),
                            childParams.get("_baseUri"));

                    Object callerHeadersObj = childParams.get("_callerHeaders");
                    if (callerHeadersObj != null) {
                        LOG.debug("Persisted caller headers: {}", callerHeadersObj);
                    }

                    // Required inputs
                    String principalFqdn = (String) childParams.get("principalFqdn");
                    Objects.requireNonNull(principalFqdn, "principalFqdn");

                    String baseUriStr = (String) childParams.get("_baseUri");
                    if (baseUriStr == null || baseUriStr.isBlank()) {
                        throw new IllegalStateException("Missing baseUri in command params (_baseUri)");
                    }
                    URI baseUri = URI.create(baseUriStr);

                    // Build Ambari API base from the same host the View is running on
                    String ambariApiBase = baseUri.resolve("/api/v1").toString();

                    // Auth headers from the original caller session
                    Map<String,String> authHeaders = AmbariActionClient.toAuthHeaders(childParams.get("_callerHeaders"));

                    // Resolve target cluster (bound view or auto-discover)
                    String cluster = this.commandUtils.resolveClusterName(baseUriStr, authHeaders);

                    // Optional parameters (support both camelCase and snake_case for convenience)
                    String kdcType = AmbariActionClient.firstNonBlank(
                            (String) childParams.get("kdcType"),
                            (String) childParams.get("kdc_type")
                    );

                    String defaultRealm = AmbariActionClient.firstNonBlank(
                            (String) childParams.get("defaultRealm"),
                            (String) childParams.get("default_realm")
                    );
                    if (defaultRealm == null && principalFqdn.contains("@")) {
                        defaultRealm = principalFqdn.substring(principalFqdn.indexOf('@') + 1);
                    }

                    Integer timeoutSeconds = null;
                    Object timeoutObj = childParams.get("timeout");
                    if (timeoutObj instanceof Number) {
                        timeoutSeconds = ((Number) timeoutObj).intValue();
                    } else if (timeoutObj instanceof String s && !s.isBlank()) {
                        try { timeoutSeconds = Integer.parseInt(s.trim()); } catch (NumberFormatException ignore) {}
                    }
                    if (timeoutSeconds == null) timeoutSeconds = 600; // sensible default

                    String context = "Generate ad-hoc keytab for " + principalFqdn;

                    // Client that talks to Ambari’s REST API with the caller session
                    var ambariActionClient = new AmbariActionClient(ctx, ambariApiBase, cluster, authHeaders);
                    LOG.info("submitting principal request creation {} to ambari server", principalFqdn);
                    int reqId = ambariActionClient.submitGenerateAdhocKeytab(
                            principalFqdn,
                            kdcType,
                            defaultRealm,
                            timeoutSeconds,
                            context
                    );

                    // Wait for completion (cap by our timeout)
                    boolean ok = ambariActionClient.waitUntilComplete(
                            reqId,
                            java.util.concurrent.TimeUnit.SECONDS.toSeconds(timeoutSeconds),
                            java.util.concurrent.TimeUnit.SECONDS
                    );
                    if (!ok) throw new IllegalStateException("Ambari request " + reqId + " did not complete successfully");

                    LOG.info("Ambari request id={} for principal={} cluster={} baseUri={} completed; fetching keytab…",
                            reqId, principalFqdn, cluster, baseUri);

                    // add some comment to check re-compilation
                    // Fetch with a short retry loop (handles persist/visibility lag)
                    Optional<String> keytabB64Opt = ambariActionClient.fetchKeytabBase64FromTasks(reqId);
                    for (int i = 0; i < 10 && keytabB64Opt.isEmpty(); i++) {   // ~5 seconds total
                        try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        keytabB64Opt = ambariActionClient.fetchKeytabBase64FromTasks(reqId);
                    }

                    String keytabB64 = keytabB64Opt.orElseThrow(() ->
                            new IllegalStateException("Ambari action " + reqId +
                                    " completed but keytab_b64 not found in structured_out."));

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
                    LOG.info("KEYTAB_CREATE_SECRET step starting for child id={} title={}", child.getId(), child.getTitle());
                    LOG.info("Request params: namespace={}, secretName={}, keyNameInSecret={}, baseUri={}",
                            childParams.get("namespace"),
                            childParams.get("secretName"),
                            childParams.get("keyNameInSecret"),
                            childParams.get("_baseUri"));
                    Object callerHeadersObj = childParams.get("_callerHeaders");
                    if (callerHeadersObj != null) {
                        LOG.debug("Persisted caller headers: {}", callerHeadersObj);
                    }
                    Objects.requireNonNull(namespace, "namespace");
                    Objects.requireNonNull(secretName, "secretName");
                    if (keyNameInSecret == null || keyNameInSecret.isBlank()) {
                        keyNameInSecret = "service.keytab";
                    }

                    // locate the sibling KEYTAB_ISSUE_PRINCIPAL and fetch its resultJson
                    Type listType = new com.google.gson.reflect.TypeToken<java.util.ArrayList<String>>(){}.getType();
                    List<String> siblings = gson.fromJson(root.getChildListJson(), listType);
                    if (siblings == null) siblings = Collections.emptyList();

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
                    LOG.info("KEYTAB_CREATE_SECRET: preparing to create secret '{}' in namespace '{}' (keyNameInSecret={}). IssuerId={}",
                            secretName, namespace, keyNameInSecret, issuerId);

                    // quick preview of the issuer command/status for debugging
                    CommandEntity issuerPreview = findCommandById(issuerId);
                    if (issuerPreview == null) {
                        LOG.warn("Issuer command with id={} referenced by KEYTAB_CREATE_SECRET not found in datastore", issuerId);
                    } else {
                        CommandStatusEntity issuerPreviewSt = findCommandStatusById(issuerPreview.getCommandStatusId());
                        LOG.info("Issuer command preview: id={} title={} type={} state={}",
                                issuerPreview.getId(),
                                issuerPreview.getTitle(),
                                issuerPreview.getType(),
                                issuerPreviewSt == null ? "MISSING" : issuerPreviewSt.getState());
                    }
                    CommandEntity issuerCmd = findCommandById(issuerId);
                    CommandStatusEntity issuerSt = findCommandStatusById(issuerCmd.getCommandStatusId());
                    if (issuerSt == null || issuerSt.getResultJson() == null || issuerSt.getResultJson().isBlank()) {
                        throw new IllegalStateException("Issuer step has no resultJson with keytabB64");
                    }

                    Map<String,Object> issuerRes =
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
            try { commandLogService.append(id, "Step succeeded: " + (child.getTitle() != null ? child.getTitle() : child.getType())); } catch (Exception ignored) {}

            // Update snapshot & continue with the next step
            refreshCurrentStepSnapshot(root);
            LOG.info("[{}] Completed step: {}", id, (child.getTitle() != null ? child.getTitle() : child.getType()));
            scheduleNow(id);

        } catch (CommandUtils.RetryableException rex) {
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
            try { commandLogService.append(id, "Step failed: " + trim(ex.getMessage())); } catch (Exception ignored) {}
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

    /**
     * Restart shared dependencies (e.g., Kerberos mutating webhook) so they pull the latest image.
     * Best-effort: logs warnings on failure.
     */
    public void refreshSharedDependencies() {
        try {
            String ns = ctx.getAmbariProperty("k8s.view.webhooks.namespace");
            if (ns == null || ns.isBlank()) ns = "ambari";
            String deployName = "kerberos-keytab-mutating-webhook";
            LOG.info("Refreshing webhook deployment {} in namespace {}", deployName, ns);
            kubernetesService.restartDeployment(ns, deployName);
        } catch (Exception e) {
            LOG.warn("Failed to refresh shared dependencies: {}", e.toString());
        }
    }

    private void store(CommandEntity e) {
        try {
            String rawParams = e.getParamsJson();
            if (rawParams != null && !rawParams.startsWith("@@file:")) {
                // Always externalize params to a file for predictability and to avoid datastore limits.
                try {
                    ViewConfigurationService cfg = new ViewConfigurationService(ctx);
                    String base = cfg.getConfigurationDirectoryPath();
                    Path paramsDir = Paths.get(base, "params");
                    Files.createDirectories(paramsDir);
                    Path filePath = paramsDir.resolve(e.getId() + "-params.json");
                    Files.writeString(filePath, rawParams, StandardCharsets.UTF_8);
                    e.setParamsJson("@@file:" + filePath.toString());
                    LOG.info("Persisting params for command {} to file {}", e.getId(), filePath);
                } catch (Exception ioEx) {
                    LOG.warn("Could not externalize paramsJson for {}: {}", e.getId(), ioEx.toString());
                }
            }
            dataStore.store(e);
        } catch (PersistenceException ex) {
            LOG.error("Persist error", ex);
        }
    }

    /**
     * Load the persisted global security configuration from the datastore.
     *
     * @param requestedProfile explicit profile name requested by the caller (may be null/blank)
     * @return SecurityConfigDTO or null when absent/invalid.
     */
    private SecurityConfigDTO loadSecurityConfig(String requestedProfile) {
        try {
            SecurityProfileService profileService = new SecurityProfileService(ctx);
            return profileService.resolveProfile(requestedProfile);
        } catch (Exception ex) {
            LOG.warn("Could not load global security configuration: {}", ex.toString());
            return null;
        }
    }

    /**
     * Resolve the profile name to use, considering defaults.
     *
     * @param requestedProfile explicit profile name requested by caller; may be null/blank
     * @return resolved profile name or null when no profiles are stored
     */
    private String resolveSecurityProfileName(String requestedProfile) {
        try {
            SecurityProfileService profileService = new SecurityProfileService(ctx);
            SecurityProfilesDTO profiles = profileService.loadProfiles();
            if (profiles == null || profiles.profiles == null || profiles.profiles.isEmpty()) {
                return null;
            }
            if (requestedProfile != null && profiles.profiles.containsKey(requestedProfile)) {
                return requestedProfile;
            }
            if (profiles.defaultProfile != null && profiles.profiles.containsKey(profiles.defaultProfile)) {
                return profiles.defaultProfile;
            }
            return profiles.profiles.keySet().stream().findFirst().orElse(null);
        } catch (Exception ex) {
            LOG.warn("Could not resolve security profile name: {}", ex.toString());
            return null;
        }
    }

    /**
     * Translate global security configuration into Helm overrides (global.security.*).
     *
     * @param cfg    security config
     * @param params params map to mutate with _ov entries
     */
    private void applySecurityOverrides(SecurityConfigDTO cfg, Map<String, Object> params) {
        if (cfg == null || cfg.mode == null || cfg.mode.isBlank()) {
            return;
        }

        SecurityMappingService mappingService = new SecurityMappingService(ctx);
        Map<String, String> modeMapping = mappingService.mappingForMode(cfg.mode.toLowerCase());
        Map<String, String> tlsMapping = mappingService.mappingForMode("tls");

        Map<String, Object> asMap = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(cfg, Map.class);

        // Apply auth-mode specific mappings
        for (Map.Entry<String, String> entry : modeMapping.entrySet()) {
            String fromPath = entry.getKey();
            String helmPath = entry.getValue();
            Object val = getByPath(asMap, fromPath);
            if (val == null) continue;
            String strVal = String.valueOf(val);
            if (strVal.isBlank()) continue;
            CommandUtils.addOverride(params, helmPath, strVal);
        }

        // Apply TLS/truststore mappings
        for (Map.Entry<String, String> entry : tlsMapping.entrySet()) {
            String fromPath = entry.getKey();
            String helmPath = entry.getValue();
            Object val = getByPath(asMap, fromPath);
            if (val == null) continue;
            String strVal = String.valueOf(val);
            if (strVal.isBlank()) continue;
            CommandUtils.addOverride(params, helmPath, strVal);
        }

        if (cfg.extraProperties != null && !cfg.extraProperties.isEmpty()) {
            for (Map.Entry<String, Object> entry : cfg.extraProperties.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                CommandUtils.addOverride(params, entry.getKey(), value);
            }
        }
    }

    /**
     * Resolve dynamic override tokens (e.g., AMBARI_KERBEROS_REALM:path) into Helm override map.
     *
     * @param dynamicValuesObj raw dynamicValues object from params (usually a List<String>)
     * @param baseUri          Ambari base URI for property lookup
     * @param callerHeaders    headers to propagate to Ambari
     * @return map of helmPath -> value or null when nothing resolved
     */
    private Map<String, String> resolveDynamicOverrides(Object dynamicValuesObj, URI baseUri, Map<String, String> callerHeaders) {
        if (dynamicValuesObj == null) {
            return null;
        }
        List<String> tokens = new ArrayList<>();
        if (dynamicValuesObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) dynamicValuesObj;
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    tokens.add(s.trim());
                }
            }
        }
        if (tokens.isEmpty() || baseUri == null) {
            return null;
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        try {
            String cluster = commandUtils.resolveClusterName(baseUri.toString(), callerHeaders);
            AmbariActionClient ambariActionClient = new AmbariActionClient(ctx, baseUri.toString(), cluster, callerHeaders);
            for (String token : tokens) {
                String[] parts = token.split(":", 2);
                if (parts.length != 2) {
                    LOG.warn("Invalid dynamic value token '{}', expected 'SOURCE:helm.path'", token);
                    continue;
                }
                String sourceKey = parts[0].trim();
                String helmPath = parts[1].trim();
                if (helmPath.isEmpty()) {
                    LOG.warn("Empty helm path in token '{}', skipping", token);
                    continue;
                }
                AmbariConfigRef ref = CommandUtils.DYNAMIC_SOURCE_MAP.get(sourceKey);
                if (ref == null) {
                    LOG.warn("No mapping for dynamic token '{}', skipping property fetch", token);
                    continue;
                }
                try {
                    String value = ambariActionClient.getDesiredConfigProperty(cluster, ref.type, ref.key);
                    if (value == null || value.isBlank()) {
                        LOG.warn("Resolved empty value for token '{}' (type={}, key={})", token, ref.type, ref.key);
                        continue;
                    }
                    resolved.put(helmPath, value);
                    LOG.info("Resolved dynamic '{}' -> {} = '{}'", token, helmPath, value);
                } catch (Exception ex) {
                    LOG.warn("Failed to resolve token '{}' (type={}, key={}): {}", token, ref.type, ref.key, ex.toString());
                }
            }
        } catch (Exception ex) {
            LOG.warn("Failed to resolve dynamic overrides: {}", ex.toString());
        }
        return resolved.isEmpty() ? null : resolved;
    }

    private String resolveParamsJson(CommandEntity cmd) {
        return CommandUtils.resolveParamsPayload(cmd != null ? cmd.getParamsJson() : null);
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
        // If parent is already terminal/canceled, don't overwrite the snapshot
        CommandStatusEntity parentSt = findCommandStatusById(parent.getCommandStatusId());
        if (parentSt == null) return;
        if (isTerminal(parentSt.getState())) return;

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
            String resolvedParentParams = resolveParamsJson(parent);
            params = (resolvedParentParams != null && !resolvedParentParams.isBlank())
                    ? gson.fromJson(resolvedParentParams, mapType)
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

    /**
     * Fetch a nested value from a Map using dotted path (e.g., "ldap.bindDn").
     */
    @SuppressWarnings("unchecked")
    private Object getByPath(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) return null;
        String[] parts = path.split("\\.");
        Object current = root;
        for (String p : parts) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(p);
            if (current == null) return null;
        }
        return current;
    }
}
