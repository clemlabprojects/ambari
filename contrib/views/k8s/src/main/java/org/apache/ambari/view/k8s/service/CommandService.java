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
import org.apache.ambari.view.k8s.model.RangerConfigDefaultsProvider;
import org.apache.ambari.view.k8s.model.RangerConfigDefaultsRegistry;
import org.apache.ambari.view.k8s.model.stack.StackConfig;
import org.apache.ambari.view.k8s.model.stack.StackProperty;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.requests.KeytabRequest;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.HelmRepoRepo;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.model.HelmReleaseDTO;
import com.marcnuri.helm.Release;
import org.apache.ambari.view.k8s.service.deployment.DeploymentBackend;
import org.apache.ambari.view.k8s.service.deployment.DeploymentContext;
import org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend;
import org.apache.ambari.view.k8s.service.deployment.GitClient;
import org.apache.ambari.view.k8s.service.deployment.HelmDirectBackend;

import org.apache.ambari.view.k8s.utils.*;
import org.apache.ambari.view.k8s.client.KeycloakAdminClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MultivaluedMap;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Collection;
import java.util.Comparator;
import java.util.concurrent.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final String VIEW_SETTINGS_KEY = "view.settings.json";
    private static final String KERBEROS_INJECTION_MODE_WEBHOOK = "WEBHOOK";
    private static final String KERBEROS_INJECTION_MODE_PRE_PROVISIONED = "PRE_PROVISIONED";
    private static final int MAX_RETRYABLE_ATTEMPTS = Integer.getInteger("k8s.view.command.maxRetryableAttempts", 5);

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
        this.commandLogService = CommandLogService.get(this.ctx);
        PathConfig pathConfig = new PathConfig(ctx);
        pathConfig.ensureDirs();
        this.fluxGitOpsBackend = new FluxGitOpsBackend(pathConfig.workDir(), kubernetesService, ctx);
        // Dispatcher registry (can be extended later)
        this.deploymentBackends.put(DeploymentMode.FLUX_GITOPS.name(), fluxGitOpsBackend);
        this.deploymentBackends.put(DeploymentMode.DIRECT_HELM.name(), new HelmDirectBackend(this));
        recoverOrphanedCommands();
        LOG.info("CommandService initialized for {}. Consider adding DB indexes on command.id, command_status_id, created_at and pruning terminal commands periodically to keep polling fast.", ctx.getInstanceName());
    }

    /**
     * On startup, any command whose status is still RUNNING was orphaned by a JVM crash or restart.
     * Mark them FAILED so the UI stops polling and the user can retry.
     */
    private void recoverOrphanedCommands() {
        try {
            String now = java.time.Instant.now().toString();
            Collection<CommandStatusEntity> all = dataStore.findAll(CommandStatusEntity.class, null);
            int recovered = 0;
            for (CommandStatusEntity st : all) {
                if (CommandState.RUNNING.name().equals(st.getState())) {
                    st.setState(CommandState.FAILED.name());
                    st.setMessage("Operation interrupted by server restart.");
                    st.setUpdatedAt(now);
                    dataStore.store(st);
                    recovered++;
                }
            }
            if (recovered > 0) {
                LOG.warn("Marked {} orphaned RUNNING command(s) as FAILED after restart.", recovered);
            }
        } catch (Exception e) {
            LOG.warn("Orphan recovery failed (non-fatal): {}", e.toString());
        }
    }

    /**
     * Append a line to the per-command log with minimal risk of throwing.
     * Uses async writer inside CommandLogService.
     */
    // While a child step is executing, its command id is set here so that everything logged to the root
    // operation is ALSO tee'd into that step's own log — giving each sub-step its own logs in the UI.
    private final ThreadLocal<String> activeStepLogId = new ThreadLocal<>();

    private void appendCommandLog(String commandId, String message) {
        try {
            commandLogService.append(commandId, message);
        } catch (Exception ex) {
            LOG.debug("Failed to append command log for {}: {}", commandId, ex.toString());
        }
        String stepId = activeStepLogId.get();
        if (stepId != null && !stepId.equals(commandId)) {
            try {
                commandLogService.append(stepId, message);
            } catch (Exception ex) {
                LOG.debug("Failed to tee command log to step {}: {}", stepId, ex.toString());
            }
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

    /**
     * Resolve the Kerberos keytab injection mode from view instance settings.
     *
     * @return normalized injection mode ("WEBHOOK" or "PRE_PROVISIONED"); defaults to WEBHOOK
     */
    private String resolveKerberosInjectionModeFromSettings() {
        String defaultMode = KERBEROS_INJECTION_MODE_WEBHOOK;
        try {
            String rawSettingsJson = ctx.getInstanceData(VIEW_SETTINGS_KEY);
            if (rawSettingsJson == null || rawSettingsJson.isBlank()) {
                return defaultMode;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> rawSettings = objectMapper.readValue(rawSettingsJson, Map.class);
            Object kerberosSettingsObj = rawSettings.get("kerberos");
            if (!(kerberosSettingsObj instanceof Map)) {
                return defaultMode;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> kerberosSettings = (Map<String, Object>) kerberosSettingsObj;
            Object injectionModeObj = kerberosSettings.get("injectionMode");
            if (injectionModeObj == null) {
                return defaultMode;
            }
            String injectionMode = injectionModeObj.toString().trim();
            if (injectionMode.isBlank()) {
                return defaultMode;
            }
            return injectionMode.toUpperCase(Locale.ROOT);
        } catch (Exception ex) {
            LOG.warn("Failed to read Kerberos injection mode from settings; defaulting to {}. Error={}",
                    defaultMode, ex.toString());
            return defaultMode;
        }
    }

    /**
     * Parse a loosely-typed boolean value (String/Boolean/Number).
     *
     * @param value raw value to parse
     * @param defaultValue fallback when parsing fails
     * @return parsed boolean
     */
    private static boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s) || "yes".equals(s) || "1".equals(s)) return true;
        if ("false".equals(s) || "no".equals(s) || "0".equals(s)) return false;
        return defaultValue;
    }

    /** Null-safe nested-map lookup: returns {@code map.get(key)} when {@code node} is a Map, else null. */
    private static Object mapGet(Object node, String key) {
        return (node instanceof Map) ? ((Map<?, ?>) node).get(key) : null;
    }

    /**
     * Check if a dependency release already exists in the target namespace.
     *
     * @param namespace dependency namespace
     * @param releaseName dependency release name
     * @return true when a release with the same name exists
     */
    private boolean dependencyReleaseExists(String namespace, String releaseName) {
        if (namespace == null || namespace.isBlank() || releaseName == null || releaseName.isBlank()) {
            return false;
        }
        try {
            String kubeconfig = this.kubernetesService.getConfigurationService().getKubeconfigContents();
            if (kubeconfig == null || kubeconfig.isBlank()) {
                LOG.warn("Cannot check dependency release existence; kubeconfig is empty");
                return false;
            }
            return this.helmService.list(namespace, kubeconfig).stream()
                    .anyMatch(r -> releaseName.equals(r.getName()));
        } catch (Exception ex) {
            LOG.warn("Failed to check existing dependency release {}/{}: {}", namespace, releaseName, ex.toString());
            return false;
        }
    }

    /**
     * Check whether the injection mode indicates webhook-based keytab injection.
     *
     * @param kerberosInjectionMode raw injection mode value (may be null)
     * @return true when webhook mode is enabled
     */
    private boolean isWebhookInjectionMode(String kerberosInjectionMode) {
        if (kerberosInjectionMode == null || kerberosInjectionMode.isBlank()) {
            return true;
        }
        return KERBEROS_INJECTION_MODE_WEBHOOK.equalsIgnoreCase(kerberosInjectionMode);
    }

    /**
     * Normalize the Kerberos entries coming from the service definition. Each entry is a map
     * and may be keyed by a logical name (e.g., "service") in the request payload.
     *
     * @param kerberosSpec raw kerberos spec map from the request payload
     * @return list of entry maps with a "key" field populated when possible
     */
    private List<Map<String, Object>> normalizeKerberosEntries(Map<String, Object> kerberosSpec) {
        if (kerberosSpec == null || kerberosSpec.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<String, Object> entry : kerberosSpec.entrySet()) {
            Object rawEntry = entry.getValue();
            if (!(rawEntry instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entryMap = new LinkedHashMap<>((Map<String, Object>) rawEntry);
            // Preserve the logical key for logging/debugging.
            entryMap.putIfAbsent("key", entry.getKey());
            entries.add(entryMap);
        }
        return entries;
    }

    /**
     * Resolve whether a Kerberos entry is enabled. Missing or blank values default to true.
     *
     * @param enabledValue the raw "enabled" value from the entry map
     * @return true if the entry should be processed
     */
    private boolean isKerberosEntryEnabled(Object enabledValue) {
        if (enabledValue == null) {
            return true;
        }
        if (enabledValue instanceof Boolean) {
            return (Boolean) enabledValue;
        }
        String normalized = enabledValue.toString().trim();
        if (normalized.isBlank()) {
            return true;
        }
        return !"false".equalsIgnoreCase(normalized);
    }

    /**
     * Convert a raw value to a non-empty string, falling back to a default when empty.
     *
     * @param rawValue raw object from configuration or request payload
     * @param defaultValue fallback value when rawValue is null/blank
     * @return normalized string value
     */
    private String resolveStringValue(Object rawValue, String defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        String candidateValue = rawValue.toString().trim();
        if (candidateValue.isBlank() || "null".equalsIgnoreCase(candidateValue)) {
            return defaultValue;
        }
        return candidateValue;
    }

    /**
     * Parse the Kerberos cluster-enabled flag from a params object.
     *
     * @param rawValue raw boolean value (Boolean/string) from params
     * @return true if Kerberos is enabled in the Ambari cluster
     */
    private boolean isKerberosClusterEnabled(Object rawValue) {
        if (rawValue == null) {
            // Unknown: default to true to preserve legacy webhook behavior when detection is unavailable.
            return true;
        }
        if (rawValue instanceof Boolean) {
            return (Boolean) rawValue;
        }
        String normalized = rawValue.toString().trim();
        if (normalized.isBlank()) {
            return false;
        }
        return "true".equalsIgnoreCase(normalized);
    }

    /**
     * Render a Kerberos principal template using the provided variables.
     * Supported tokens: {{service}}, {{namespace}}, {{releaseName}}, {{realm}}.
     *
     * @param template raw template string (may be null/blank)
     * @param serviceName service name to inject
     * @param namespace Kubernetes namespace
     * @param releaseName Helm release name
     * @param realm Kerberos realm
     * @return rendered principal string (never null)
     */
    private String renderKerberosPrincipalTemplate(String template,
                                                   String serviceName,
                                                   String namespace,
                                                   String releaseName,
                                                   String realm) {
        return renderKerberosPrincipalTemplate(template, serviceName, namespace, releaseName, realm, null);
    }

    /**
     * Same as the 5-arg version, but accepts an additional map of {{token}} → value
     * substitutions. Used by the keytab pre-provisioning step in directHelmDeploy to
     * make wizard form fields (e.g. kerberosHostFqdn) addressable from
     * principalTemplate / secretNameTemplate. FreeIPA 4.12 enforces FQDN syntax on
     * service principals, so the operator needs to supply the FQDN form via the
     * wizard rather than us hardcoding a cluster suffix in service.json.
     */
    private String renderKerberosPrincipalTemplate(String template,
                                                   String serviceName,
                                                   String namespace,
                                                   String releaseName,
                                                   String realm,
                                                   Map<String, String> extraTokens) {
        String templateValue = template == null ? "" : template;
        String resolvedValue = templateValue;
        resolvedValue = replaceTemplateToken(resolvedValue, "service", serviceName);
        resolvedValue = replaceTemplateToken(resolvedValue, "namespace", namespace);
        resolvedValue = replaceTemplateToken(resolvedValue, "releaseName", releaseName);
        resolvedValue = replaceTemplateToken(resolvedValue, "realm", realm);
        if (extraTokens != null) {
            for (Map.Entry<String, String> e : extraTokens.entrySet()) {
                resolvedValue = replaceTemplateToken(resolvedValue, e.getKey(), e.getValue());
            }
        }
        return resolvedValue;
    }

    /**
     * Replace a single {{token}} placeholder in a template with a concrete value.
     *
     * @param templateValue template to mutate
     * @param tokenName token to replace (without braces)
     * @param tokenValue replacement value
     * @return template with token substituted
     */
    private String replaceTemplateToken(String templateValue, String tokenName, String tokenValue) {
        if (templateValue == null || templateValue.isBlank()) {
            return templateValue;
        }
        String safeValue = tokenValue == null ? "" : tokenValue;
        Pattern tokenPattern = Pattern.compile("\\{\\{\\s*" + Pattern.quote(tokenName) + "\\s*\\}\\}");
        return tokenPattern.matcher(templateValue).replaceAll(Matcher.quoteReplacement(safeValue));
    }

    /**
     * Resolve the Kerberos realm from Ambari, using the dynamic source map key.
     *
     * @param ambariActionClient Ambari client used for config lookup
     * @param clusterName Ambari cluster name
     * @return resolved realm string or null when unavailable
     */
    private String resolveKerberosRealmFromAmbari(AmbariActionClient ambariActionClient, String clusterName) {
        if (ambariActionClient == null) {
            LOG.warn("AmbariActionClient is null; cannot resolve Kerberos realm");
            return null;
        }
        if (clusterName == null || clusterName.isBlank()) {
            LOG.warn("Cluster name is blank; cannot resolve Kerberos realm");
            return null;
        }
        try {
            AmbariConfigRef realmRef = CommandUtils.DYNAMIC_SOURCE_MAP.get("AMBARI_KERBEROS_REALM");
            if (realmRef == null) {
                LOG.warn("Dynamic source map does not define AMBARI_KERBEROS_REALM");
                return null;
            }
            String kerberosRealmValue = ambariActionClient.getDesiredConfigProperty(clusterName, realmRef.type, realmRef.key);
            if (kerberosRealmValue == null || kerberosRealmValue.isBlank()) {
                LOG.warn("Kerberos realm resolved to empty for cluster {}", clusterName);
                return null;
            }
            LOG.info("Resolved Kerberos realm '{}' from Ambari for cluster {}", kerberosRealmValue, clusterName);
            return kerberosRealmValue;
        } catch (Exception ex) {
            LOG.warn("Failed to resolve Kerberos realm from Ambari for cluster {}: {}", clusterName, ex.toString());
            return null;
        }
    }

    /**
     * Resolve the secret name for a Kerberos entry, supporting an optional template.
     *
     * @param kerberosEntry Kerberos entry map from the request/service definition
     * @param serviceName service name used in template expansion
     * @param namespace Kubernetes namespace
     * @param releaseName Helm release name
     * @param kerberosRealm Kerberos realm for template expansion
     * @return resolved secret name (never null, falls back to releaseName-keytab)
     */
    private String resolveKerberosEntrySecretName(Map<String, Object> kerberosEntry,
                                                  String serviceName,
                                                  String namespace,
                                                  String releaseName,
                                                  String kerberosRealm) {
        if (kerberosEntry == null) {
            return releaseName + "-keytab";
        }
        String secretNameTemplate = resolveStringValue(kerberosEntry.get("secretNameTemplate"), null);
        if (secretNameTemplate != null && !secretNameTemplate.isBlank()) {
            String renderedSecretName = renderKerberosPrincipalTemplate(
                    secretNameTemplate,
                    serviceName,
                    namespace,
                    releaseName,
                    kerberosRealm == null ? "" : kerberosRealm
            );
            if (renderedSecretName != null && !renderedSecretName.isBlank()) {
                LOG.info("Resolved Kerberos secretName from template '{}': {}", secretNameTemplate, renderedSecretName);
                return renderedSecretName;
            }
            LOG.warn("Kerberos secretNameTemplate rendered to empty; falling back to explicit secretName or release default.");
        }
        String explicitSecretName = resolveStringValue(kerberosEntry.get("secretName"), null);
        if (explicitSecretName != null && !explicitSecretName.isBlank()) {
            return explicitSecretName;
        }
        return releaseName + "-keytab";
    }

    /**
     * Build a flattened template variable map for Kerberos entries.
     * Keys are exposed as "kerberos.entries.<entryKey>.<field>" for template rendering.
     *
     * @param kerberosEntries normalized Kerberos entry list
     * @param namespace Kubernetes namespace
     * @param releaseName Helm release name
     * @param kerberosRealm resolved Kerberos realm (may be null)
     * @return map of template variables (never null)
     */
    private Map<String, Object> buildKerberosTemplateVariablesForEntries(List<Map<String, Object>> kerberosEntries,
                                                                         String namespace,
                                                                         String releaseName,
                                                                         String kerberosRealm) {
        Map<String, Object> templateVariables = new LinkedHashMap<>();
        if (kerberosRealm != null && !kerberosRealm.isBlank()) {
            templateVariables.put("kerberos.realm", kerberosRealm);
        }
        if (kerberosEntries == null || kerberosEntries.isEmpty()) {
            LOG.info("No Kerberos entries available to expose template variables.");
            return templateVariables;
        }

        int kerberosEntryIndex = 0;
        for (Map<String, Object> kerberosEntry : kerberosEntries) {
            kerberosEntryIndex++;
            if (!isKerberosEntryEnabled(kerberosEntry.get("enabled"))) {
                LOG.info("Skipping Kerberos template variables for disabled entry {}", kerberosEntry.getOrDefault("key", kerberosEntryIndex));
                continue;
            }

            String entryKey = resolveStringValue(kerberosEntry.get("key"), "entry-" + kerberosEntryIndex);
            String serviceName = resolveStringValue(kerberosEntry.get("serviceName"), releaseName);
            String principalTemplate = resolveStringValue(
                    kerberosEntry.get("principalTemplate"),
                    "{{service}}-{{namespace}}@{{realm}}"
            );
            String resolvedPrincipal = renderKerberosPrincipalTemplate(
                    principalTemplate,
                    serviceName,
                    namespace,
                    releaseName,
                    kerberosRealm == null ? "" : kerberosRealm
            );
            String secretName = resolveKerberosEntrySecretName(
                    kerberosEntry,
                    serviceName,
                    namespace,
                    releaseName,
                    kerberosRealm
            );
            String keyNameInSecret = resolveStringValue(kerberosEntry.get("keyNameInSecret"), "service.keytab");
            String mountPath = resolveStringValue(kerberosEntry.get("mountPath"), "/etc/security/keytabs");

            String templatePrefix = "kerberos.entries." + entryKey + ".";
            templateVariables.put(templatePrefix + "entryKey", entryKey);
            templateVariables.put(templatePrefix + "serviceName", serviceName);
            templateVariables.put(templatePrefix + "principalTemplate", principalTemplate);
            templateVariables.put(templatePrefix + "principal", resolvedPrincipal);
            templateVariables.put(templatePrefix + "secretName", secretName);
            templateVariables.put(templatePrefix + "keyNameInSecret", keyNameInSecret);
            templateVariables.put(templatePrefix + "mountPath", mountPath);
            templateVariables.put(templatePrefix + "keytabPath", mountPath + "/" + keyNameInSecret);
            if (kerberosRealm != null && !kerberosRealm.isBlank()) {
                templateVariables.put(templatePrefix + "realm", kerberosRealm);
            }
            LOG.info("Exposed Kerberos template variables for entry {}: principal={}, secretName={}, keyNameInSecret={}",
                    entryKey, resolvedPrincipal, secretName, keyNameInSecret);
        }

        LOG.info("Built {} Kerberos template variables for {} entries", templateVariables.size(), kerberosEntries.size());
        return templateVariables;
    }

    /**
     * Merge template variables into params with collision-safe logging.
     *
     * @param params mutable params map to augment
     * @param templateVariables template variables to merge
     * @param variableGroupName logical group name for logging
     */
    private void mergeTemplateVariablesIntoParams(Map<String, Object> params,
                                                  Map<String, Object> templateVariables,
                                                  String variableGroupName) {
        if (params == null || templateVariables == null || templateVariables.isEmpty()) {
            LOG.info("No {} template variables to merge into params.", variableGroupName);
            return;
        }
        int mergedCount = 0;
        for (Map.Entry<String, Object> entry : templateVariables.entrySet()) {
            String templateKey = entry.getKey();
            Object templateValue = entry.getValue();
            if (params.containsKey(templateKey)) {
                LOG.warn("Template variable '{}' already exists in params; skipping overwrite for group {}", templateKey, variableGroupName);
                continue;
            }
            params.put(templateKey, templateValue);
            mergedCount++;
        }
        LOG.info("Merged {} {} template variables into params", mergedCount, variableGroupName);
    }

    /**
     * Append Kerberos keytab provisioning steps (issue principal + create Secret) to the current command plan.
     * These steps are inserted ahead of Helm install when the view is configured for pre-provisioned keytabs.
     *
     * @param rootCommand root command entity for the release
     * @param childCommandIds ordered list of child command ids to append to
     * @param rootParams base params used by the release (copied into child params)
     * @param principalFqdn fully qualified principal to request from Ambari
     * @param namespace target Kubernetes namespace for the Secret
     * @param secretName target Secret name for the keytab
     * @param keyNameInSecret key name inside the Secret data
     * @param kerberosEntryKey logical entry name for logging and traceability
     */
    private void appendPreProvisionedKeytabSteps(CommandEntity rootCommand,
                                                 List<String> childCommandIds,
                                                 Map<String, Object> rootParams,
                                                 String principalFqdn,
                                                 String namespace,
                                                 String secretName,
                                                 String keyNameInSecret,
                                                 String kerberosEntryKey) {
        final String now = Instant.now().toString();

        // ---- child #1: issue principal & keytab via Ambari action ----
        String issueCommandId = rootCommand.getId() + "-" + UUID.randomUUID();
        Map<String, Object> issueParams = new LinkedHashMap<>(rootParams);
        issueParams.put("principalFqdn", principalFqdn);
        issueParams.put("namespace", namespace);
        issueParams.put("secretName", secretName);
        issueParams.put("keyNameInSecret", keyNameInSecret);
        issueParams.put("kerberosEntryKey", kerberosEntryKey);

        CommandEntity issueCommand = new CommandEntity();
        issueCommand.setId(issueCommandId);
        issueCommand.setViewInstance(ctx.getInstanceName());
        issueCommand.setType(CommandType.KEYTAB_ISSUE_PRINCIPAL.name());
        issueCommand.setTitle("Ambari: generate keytab for " + principalFqdn);
        issueCommand.setParamsJson(gson.toJson(issueParams));

        CommandStatusEntity issueStatus = new CommandStatusEntity();
        issueStatus.setId(issueCommandId + "-status");
        issueStatus.setAttempt(0);
        issueStatus.setState(CommandState.PENDING.name());
        issueStatus.setCreatedBy(ctx.getUsername());
        issueStatus.setCreatedAt(now);
        issueStatus.setUpdatedAt(now);
        issueCommand.setCommandStatusId(issueStatus.getId());

        // ---- child #2: create/update Opaque secret ----
        String createSecretCommandId = rootCommand.getId() + "-" + UUID.randomUUID();
        Map<String, Object> createParams = new LinkedHashMap<>(issueParams);
        createParams.put("keytabIssuerId", issueCommandId);

        CommandEntity createSecretCommand = new CommandEntity();
        createSecretCommand.setId(createSecretCommandId);
        createSecretCommand.setViewInstance(ctx.getInstanceName());
        createSecretCommand.setType(CommandType.KEYTAB_CREATE_SECRET.name());
        createSecretCommand.setTitle("K8s: create/update secret " + secretName);
        createSecretCommand.setParamsJson(gson.toJson(createParams));

        CommandStatusEntity createSecretStatus = new CommandStatusEntity();
        createSecretStatus.setId(createSecretCommandId + "-status");
        createSecretStatus.setAttempt(0);
        createSecretStatus.setState(CommandState.PENDING.name());
        createSecretStatus.setCreatedBy(ctx.getUsername());
        createSecretStatus.setCreatedAt(now);
        createSecretStatus.setUpdatedAt(now);
        createSecretCommand.setCommandStatusId(createSecretStatus.getId());

        // Persist both child commands so they are visible in the root plan.
        store(issueStatus);
        store(issueCommand);
        store(createSecretStatus);
        store(createSecretCommand);

        childCommandIds.add(issueCommandId);
        childCommandIds.add(createSecretCommandId);

        LOG.info("Added Kerberos keytab steps for entry {}: issueId={}, createId={}", kerberosEntryKey, issueCommandId, createSecretCommandId);
    }

    // =========================================================================
    // OIDC client registration lifecycle (mirrors Kerberos PRE_PROVISIONED)
    // =========================================================================

    /**
     * Append OIDC client registration steps (register in Keycloak + create K8s Secret) to the
     * current command plan.  Called during deploy when the service definition contains oidc[].
     */
    private void appendOidcRegistrationSteps(CommandEntity rootCommand,
                                             List<String> childCommandIds,
                                             Map<String, Object> rootParams,
                                             String desiredClientId,
                                             String redirectUri,
                                             String secretName,
                                             String oidcEntryKey,
                                             String vaultPath,
                                             Map<String, Object> extraStepParams) {
        final String now = Instant.now().toString();

        // Step 1: register/update the OIDC client in Keycloak
        String registerCmdId = rootCommand.getId() + "-oidc-" + UUID.randomUUID();
        Map<String, Object> registerParams = new LinkedHashMap<>(rootParams);
        registerParams.put("oidcClientId", desiredClientId);
        registerParams.put("oidcRedirectUri", redirectUri != null ? redirectUri : "");
        registerParams.put("oidcSecretName", secretName);
        registerParams.put("oidcEntryKey", oidcEntryKey);
        if (vaultPath != null && !vaultPath.isBlank()) {
            registerParams.put("oidcVaultPath", vaultPath);
        }
        if (extraStepParams != null && !extraStepParams.isEmpty()) {
            registerParams.putAll(extraStepParams);
        }

        CommandEntity registerCmd = new CommandEntity();
        registerCmd.setId(registerCmdId);
        registerCmd.setViewInstance(ctx.getInstanceName());
        registerCmd.setType(CommandType.OIDC_REGISTER_CLIENT.name());
        registerCmd.setTitle("Keycloak: register OIDC client " + desiredClientId);
        registerCmd.setParamsJson(gson.toJson(registerParams));

        CommandStatusEntity registerStatus = new CommandStatusEntity();
        registerStatus.setId(registerCmdId + "-status");
        registerStatus.setAttempt(0);
        registerStatus.setState(CommandState.PENDING.name());
        registerStatus.setCreatedBy(ctx.getUsername());
        registerStatus.setCreatedAt(now);
        registerStatus.setUpdatedAt(now);
        registerCmd.setCommandStatusId(registerStatus.getId());

        // Step 2: write credentials to K8s Secret (and optionally Vault)
        String createSecretCmdId = rootCommand.getId() + "-oidc-" + UUID.randomUUID();
        Map<String, Object> createParams = new LinkedHashMap<>(registerParams);
        createParams.put("oidcRegisterId", registerCmdId);

        CommandEntity createSecretCmd = new CommandEntity();
        createSecretCmd.setId(createSecretCmdId);
        createSecretCmd.setViewInstance(ctx.getInstanceName());
        createSecretCmd.setType(CommandType.OIDC_CREATE_SECRET.name());
        createSecretCmd.setTitle("K8s: create/update OIDC secret " + secretName);
        createSecretCmd.setParamsJson(gson.toJson(createParams));

        CommandStatusEntity createSecretStatus = new CommandStatusEntity();
        createSecretStatus.setId(createSecretCmdId + "-status");
        createSecretStatus.setAttempt(0);
        createSecretStatus.setState(CommandState.PENDING.name());
        createSecretStatus.setCreatedBy(ctx.getUsername());
        createSecretStatus.setCreatedAt(now);
        createSecretStatus.setUpdatedAt(now);
        createSecretCmd.setCommandStatusId(createSecretStatus.getId());

        store(registerStatus);
        store(registerCmd);
        store(createSecretStatus);
        store(createSecretCmd);

        childCommandIds.add(registerCmdId);
        childCommandIds.add(createSecretCmdId);

        LOG.info("Added OIDC registration steps for entry {}: registerId={}, createSecretId={}",
                oidcEntryKey, registerCmdId, createSecretCmdId);
    }

    /**
     * Append only the OIDC_CREATE_SECRET step for an external OIDC profile where the admin
     * pre-supplied clientId/clientSecret in the security profile (no Keycloak registration needed).
     */
    private void appendOidcExternalSecretStep(CommandEntity rootCommand,
                                              List<String> childCommandIds,
                                              Map<String, Object> rootParams,
                                              String externalClientId,
                                              String externalClientSecret,
                                              String externalIssuerUrl,
                                              String secretName,
                                              String oidcEntryKey,
                                              String vaultPath,
                                              Map<String, Object> extraStepParams) {
        final String now = Instant.now().toString();
        String createSecretCmdId = rootCommand.getId() + "-oidc-" + UUID.randomUUID();

        Map<String, Object> createParams = new LinkedHashMap<>(rootParams);
        createParams.put("oidcSecretName", secretName);
        createParams.put("oidcEntryKey", oidcEntryKey);
        createParams.put("oidcExternalClientId", externalClientId != null ? externalClientId : "");
        createParams.put("oidcExternalClientSecret", externalClientSecret != null ? externalClientSecret : "");
        if (externalIssuerUrl != null && !externalIssuerUrl.isBlank()) {
            createParams.put("oidcExternalIssuerUrl", externalIssuerUrl);
        }
        if (vaultPath != null && !vaultPath.isBlank()) {
            createParams.put("oidcVaultPath", vaultPath);
        }
        if (extraStepParams != null && !extraStepParams.isEmpty()) {
            createParams.putAll(extraStepParams);
        }

        CommandEntity createSecretCmd = new CommandEntity();
        createSecretCmd.setId(createSecretCmdId);
        createSecretCmd.setViewInstance(ctx.getInstanceName());
        createSecretCmd.setType(CommandType.OIDC_CREATE_SECRET.name());
        createSecretCmd.setTitle("K8s: create/update OIDC secret " + secretName + " (external)");
        createSecretCmd.setParamsJson(gson.toJson(createParams));

        CommandStatusEntity createSecretStatus = new CommandStatusEntity();
        createSecretStatus.setId(createSecretCmdId + "-status");
        createSecretStatus.setAttempt(0);
        createSecretStatus.setState(CommandState.PENDING.name());
        createSecretStatus.setCreatedBy(ctx.getUsername());
        createSecretStatus.setCreatedAt(now);
        createSecretStatus.setUpdatedAt(now);
        createSecretCmd.setCommandStatusId(createSecretStatus.getId());

        store(createSecretStatus);
        store(createSecretCmd);
        childCommandIds.add(createSecretCmdId);

        LOG.info("Added external OIDC secret step for entry {}: createSecretId={}", oidcEntryKey, createSecretCmdId);
    }

    /**
     * Resolve the OIDC issuer URL from Ambari cluster config.
     * Returns the oidc_issuer_url property if non-blank; otherwise computes it as
     * {oidc_admin_url}/realms/{oidc_realm}.
     */
    private String resolveOidcIssuerUrl(AmbariActionClient client, String cluster) {
        try {
            String explicit = client.getDesiredConfigProperty(cluster, "oidc-env", "oidc_issuer_url");
            if (explicit != null && !explicit.isBlank()) return explicit;
            String adminUrl = client.getDesiredConfigProperty(cluster, "oidc-env", "oidc_admin_url");
            String realm    = client.getDesiredConfigProperty(cluster, "oidc-env", "oidc_realm");
            if (adminUrl != null && realm != null) {
                return adminUrl.replaceAll("/$", "") + "/realms/" + realm;
            }
        } catch (Exception ex) {
            LOG.warn("Could not resolve OIDC issuer URL from cluster {}: {}", cluster, ex.toString());
        }
        return null;
    }

    /**
     * Build the GitLab OmniAuth provider YAML stored in the provider K8s secret.
     * GitLab reads this YAML to configure the openid_connect strategy.
     */
    private String buildGitlabOmniauthProviderYaml(String clientId, String clientSecret, String issuerUrl, String callbackUrl) {
        String safeClientId     = clientId     != null ? clientId     : "";
        String safeClientSecret = clientSecret != null ? clientSecret : "";
        String safeIssuerUrl    = issuerUrl    != null ? issuerUrl    : "";
        String safeCallbackUrl  = callbackUrl  != null ? callbackUrl  : "";
        return "name: openid_connect\n"
             + "label: \"SSO\"\n"
             + "args:\n"
             + "  name: openid_connect\n"
             + "  scope:\n"
             + "    - openid\n"
             + "    - profile\n"
             + "    - email\n"
             + "  response_type: code\n"
             + "  issuer: " + safeIssuerUrl + "\n"
             + "  discovery: true\n"
             + "  client_auth_method: query\n"
             + "  uid_field: preferred_username\n"
             + "  pkce: true\n"
             + "  client_options:\n"
             + "    identifier: " + safeClientId + "\n"
             + "    secret: " + safeClientSecret + "\n"
             + "    redirect_uri: " + safeCallbackUrl + "\n";
    }

    /**
     * Render an OIDC template string replacing {{releaseName}}, {{namespace}}, {{realm}}.
     * Validates the result contains only safe characters to prevent injection.
     */
    private String renderOidcTemplate(String template, String releaseName, String namespace, String realm) {
        return renderOidcTemplate(template, releaseName, namespace, realm, null);
    }

    private String renderOidcTemplate(String template, String releaseName, String namespace, String realm,
                                       Map<String, String> extraVars) {
        if (template == null || template.isBlank()) return "";
        String result = template
                .replace("{{releaseName}}", releaseName != null ? releaseName : "")
                .replace("{{namespace}}", namespace != null ? namespace : "")
                .replace("{{realm}}", realm != null ? realm : "");
        if (extraVars != null) {
            for (Map.Entry<String, String> e : extraVars.entrySet()) {
                result = result.replace("{{" + e.getKey() + "}}", e.getValue() != null ? e.getValue() : "");
            }
        }
        return result;
    }

    /**
     * Resolve service variables (from the service.json "variables" section) against Helm deploy params.
     * Handles variables with from.type="form": reads the value at from.field (dotted path) from params.
     * Any {{token}} used in redirectUriTemplate can be resolved this way — no hardcoded field names.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> resolveServiceVariablesFromParams(List<Map<String, Object>> variables,
                                                                   Map<String, Object> params) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (variables == null || variables.isEmpty() || params == null) return resolved;

        // Chart values are stored in a file (valuesPath) rather than inline in params.
        // Load them once so form fields like ingress.host can be resolved.
        Map<String, Object> chartValues = null;
        String valuesPath = resolveStringValue(params.get("valuesPath"), null);
        if (valuesPath != null) {
            try {
                String json = java.nio.file.Files.readString(java.nio.file.Paths.get(valuesPath));
                chartValues = gson.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, Object>>(){}.getType());
            } catch (Exception ex) {
                LOG.warn("resolveServiceVariablesFromParams: could not load chart values from {}: {}", valuesPath, ex.toString());
            }
        }

        for (Map<String, Object> variable : variables) {
            String name = resolveStringValue(variable.get("name"), null);
            if (name == null || name.isBlank()) continue;
            Map<String, Object> from = (Map<String, Object>) variable.get("from");
            if (from == null) continue;
            String type  = resolveStringValue(from.get("type"), "");
            String field = resolveStringValue(from.get("field"), null);
            if ("form".equals(type) && field != null) {
                // 1. Try params directly (flat or nested)
                Object value = getByPath(params, field);
                if (value == null) value = params.get(field);
                // 1b. Fall back to params.formValues — the wizard's snapshot of the raw form state
                // (with envelope keys stripped). This is the only way the server can see
                // excludeFromValues form fields like jupyterHost: they're stripped from the chart
                // values by buildFinalValues and never appear at the top level of the request body,
                // but they ARE needed here to render OIDC redirectUriTemplate and other server-side
                // templates that reference {{jupyterHost}}.
                if (value == null) {
                    Object formValuesObj = params.get("formValues");
                    if (formValuesObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> fv = (Map<String, Object>) formValuesObj;
                        value = getByPath(fv, field);
                        if (value == null) value = fv.get(field);
                    }
                }
                // 2. Fall back to chart values file (covers excludeFromValues fields resolved via bindings)
                if (value == null && chartValues != null) {
                    value = getByPath(chartValues, field);
                    if (value == null) value = chartValues.get(field);
                }
                // 3. For X.host fields excluded from values, bindings write to X.hosts[] instead.
                //    Two shapes are supported by upstream charts:
                //      a. List<Map> e.g. openmetadata: hosts: [{host: "...", paths: [...]}]
                //      b. List<String> e.g. superset (Bitnami): hosts: ["..."]
                if (value == null && chartValues != null && field.endsWith(".host") && !field.contains("tls")) {
                    String hostsField = field.substring(0, field.length() - 5) + ".hosts";
                    Object hostsList = getByPath(chartValues, hostsField);
                    if (hostsList == null) hostsList = chartValues.get(hostsField);
                    if (hostsList instanceof List && !((List<?>) hostsList).isEmpty()) {
                        Object first = ((List<?>) hostsList).get(0);
                        if (first instanceof Map) {
                            Object host = ((Map<?, ?>) first).get("host");
                            if (host != null) value = host;
                        } else if (first instanceof String && !((String) first).isBlank()) {
                            value = first;
                        }
                    }
                }
                if (value != null) {
                    resolved.put(name, String.valueOf(value));
                }
            }
        }
        return resolved;
    }

    /**
     * Resolve service variables for the OIDC re-registration action, where deploy-time params
     * are not available. Falls back to querying cluster resources.
     *
     * Currently handles variables whose from.field ends with ".host" (i.e. ingress host fields)
     * by reading the first Ingress host from the cluster for this release.
     * Other variable types are left unresolved and the template token stays blank.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> resolveServiceVariablesFromCluster(List<Map<String, Object>> variables,
                                                                    String namespace, String releaseName) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (variables == null || variables.isEmpty()) return resolved;

        String ingressHost = null;  // fetched lazily

        for (Map<String, Object> variable : variables) {
            String name = resolveStringValue(variable.get("name"), null);
            if (name == null || name.isBlank()) continue;
            Map<String, Object> from = (Map<String, Object>) variable.get("from");
            if (from == null) continue;
            String type  = resolveStringValue(from.get("type"), "");
            String field = resolveStringValue(from.get("field"), null);
            if ("form".equals(type) && field != null
                    && field.endsWith(".host") && !field.contains("tls")) {
                // Ingress host field — read from the live Kubernetes Ingress
                if (ingressHost == null) {
                    ingressHost = readIngressHostFromCluster(namespace, releaseName);
                }
                if (!ingressHost.isBlank()) {
                    resolved.put(name, ingressHost);
                }
            }
        }
        return resolved;
    }

    /** Read the first Ingress host for a release from the cluster. Returns empty string if none found. */
    private String readIngressHostFromCluster(String namespace, String releaseName) {
        try {
            var ingresses = kubernetesService.getClient()
                    .network().v1().ingresses()
                    .inNamespace(namespace)
                    .withLabel("app.kubernetes.io/instance", releaseName)
                    .list().getItems();
            if (ingresses == null || ingresses.isEmpty()) {
                ingresses = kubernetesService.getClient()
                        .network().v1().ingresses()
                        .inNamespace(namespace)
                        .list().getItems().stream()
                        .filter(ing -> ing.getMetadata() != null
                                && ing.getMetadata().getName() != null
                                && ing.getMetadata().getName().contains(releaseName))
                        .toList();
            }
            if (ingresses != null && !ingresses.isEmpty()) {
                var rules = ingresses.get(0).getSpec() != null ? ingresses.get(0).getSpec().getRules() : null;
                if (rules != null && !rules.isEmpty() && rules.get(0).getHost() != null) {
                    return rules.get(0).getHost();
                }
            }
        } catch (Exception ex) {
            LOG.warn("readIngressHostFromCluster {}/{}: {}", namespace, releaseName, ex.toString());
        }
        return "";
    }

    /**
     * Validate that a client ID contains only safe characters.
     * Prevents injection via templates.
     */
    private static void validateOidcClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) return;
        if (!clientId.matches("[a-zA-Z0-9._/@:+\\-]{1,255}")) {
            throw new IllegalArgumentException(
                    "OIDC client ID contains invalid characters: " + clientId);
        }
    }

    private static void validateKerberosPrincipal(String principal) {
        if (principal == null || principal.isBlank()) return;
        // Allow the character set valid in Kerberos principals: primary[/instance]@REALM
        // Reject anything that could be used for injection (shell metacharacters, whitespace, etc.)
        if (!principal.matches("[a-zA-Z0-9._/@:\\-]{1,512}")) {
            throw new IllegalArgumentException(
                    "Kerberos principal contains invalid characters: " + principal);
        }
    }

    /**
     * Submit a standalone OIDC client re-registration for an already-deployed release.
     * Parallel to submitReleaseKeytabRegeneration().
     */
    public String submitReleaseOidcRegistration(String namespace,
                                                String releaseName,
                                                MultivaluedMap<String, String> callerHeaders,
                                                URI baseUri) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(releaseName, "releaseName");
        Objects.requireNonNull(baseUri, "baseUri");

        K8sReleaseEntity releaseMetadata = new ReleaseMetadataService(ctx).find(namespace, releaseName);
        if (releaseMetadata == null || releaseMetadata.getServiceKey() == null
                || releaseMetadata.getServiceKey().isBlank()) {
            throw new IllegalArgumentException(
                    "Release " + namespace + "/" + releaseName + " is not managed by the UI.");
        }

        StackServiceDef serviceDefinition =
                new StackDefinitionService(ctx).getServiceDefinition(releaseMetadata.getServiceKey());
        if (serviceDefinition == null || serviceDefinition.oidc == null || serviceDefinition.oidc.isEmpty()) {
            throw new IllegalArgumentException(
                    "Service definition for " + releaseMetadata.getServiceKey() + " has no OIDC entries.");
        }

        Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(callerHeaders);
        String clusterName;
        try {
            clusterName = commandUtils.resolveClusterName(baseUri.toString(), authHeaders);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to resolve Ambari cluster name.", ex);
        }
        AmbariActionClient ambariClient =
                new AmbariActionClient(ctx, baseUri.toString(), clusterName, authHeaders);

        String oidcRealm = "";
        try {
            oidcRealm = ambariClient.getDesiredConfigProperty(clusterName, "oidc-env", "oidc_realm");
        } catch (Exception ex) {
            LOG.warn("Could not read oidc_realm from cluster {}: {}", clusterName, ex.toString());
        }

        final String commandId = UUID.randomUUID().toString();
        final String now = Instant.now().toString();

        CommandEntity rootCommand = new CommandEntity();
        rootCommand.setId(commandId);
        rootCommand.setViewInstance(ctx.getInstanceName());
        rootCommand.setType(CommandType.REGISTER_OIDC_CLIENT.name());
        rootCommand.setTitle("Register OIDC client(s) for " + releaseName);

        CommandStatusEntity rootStatus = new CommandStatusEntity();
        rootStatus.setId(commandId + "-status");
        rootStatus.setViewInstance(ctx.getInstanceName());
        rootStatus.setCreatedBy(ctx.getUsername());
        rootStatus.setState(CommandState.PENDING.name());
        rootStatus.setCreatedAt(now);
        rootStatus.setUpdatedAt(now);
        rootStatus.setAttempt(0);
        rootCommand.setCommandStatusId(rootStatus.getId());

        Map<String, Object> rootParams = new LinkedHashMap<>();
        rootParams.put("releaseName", releaseName);
        rootParams.put("namespace", namespace);
        rootParams.put("serviceKey", releaseMetadata.getServiceKey());
        rootParams.put("_cluster", clusterName);
        rootParams.put("_baseUri", baseUri.toString());
        rootParams.put("_callerHeaders", AmbariActionClient.headersToPersistableMap(callerHeaders));
        rootParams.put("oidcRealm", oidcRealm);

        List<String> childCommandIds = new ArrayList<>();
        boolean stepsAdded = false;

        int idx = 0;
        for (Map<String, Object> entry : serviceDefinition.oidc) {
            idx++;
            if (!isEntryEnabled(entry.get("enabled"))) continue;
            String entryKey  = resolveStringValue(entry.get("key"), "entry-" + idx);
            String clientIdT = resolveStringValue(entry.get("clientIdTemplate"), "{{releaseName}}-{{namespace}}");
            String secretT   = resolveStringValue(entry.get("secretNameTemplate"), "{{releaseName}}-oidc-client");
            String redirectT = resolveStringValue(entry.get("redirectUriTemplate"), "");
            String vaultPath = resolveStringValue(entry.get("vaultPath"), null);

            String clientId   = renderOidcTemplate(clientIdT,  releaseName, namespace, oidcRealm);
            String secretName = renderOidcTemplate(secretT,     releaseName, namespace, oidcRealm);
            Map<String, String> reRegExtraVars = resolveServiceVariablesFromCluster(
                    serviceDefinition.variables, namespace, releaseName);
            String redirectUri= renderOidcTemplate(redirectT,   releaseName, namespace, oidcRealm, reRegExtraVars);

            appendOidcRegistrationSteps(rootCommand, childCommandIds, rootParams,
                    clientId, redirectUri, secretName, entryKey, vaultPath, Collections.emptyMap());
            stepsAdded = true;
        }

        if (!stepsAdded) {
            throw new IllegalStateException("No OIDC entries were scheduled for release " + releaseName);
        }

        rootCommand.setParamsJson(gson.toJson(rootParams));
        rootCommand.setChildListJson(gson.toJson(childCommandIds));
        store(rootStatus);
        store(rootCommand);
        scheduleNow(commandId);
        return commandId;
    }

    /** Null-safe enabled check used for both kerberos and oidc entries. */
    private static boolean isEntryEnabled(Object v) {
        if (v == null) return true;
        if (v instanceof Boolean b) return b;
        return !"false".equalsIgnoreCase(String.valueOf(v));
    }

    /**
     * Build a Kerberos spec map (key -> entry map) from a service definition list.
     * This normalizes the service.json list into the payload shape expected by normalizeKerberosEntries().
     *
     * @param kerberosEntries list of Kerberos entry maps from the service definition
     * @return normalized map keyed by entry key (never null)
     */
    private Map<String, Object> buildKerberosSpecMapFromServiceDefinition(List<Map<String, Object>> kerberosEntries) {
        if (kerberosEntries == null || kerberosEntries.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> kerberosSpecMap = new LinkedHashMap<>();
        int kerberosEntryIndex = 0;
        // Iterate through each Kerberos entry and preserve its logical key for traceability.
        for (Map<String, Object> kerberosEntry : kerberosEntries) {
            kerberosEntryIndex++;
            if (kerberosEntry == null || kerberosEntry.isEmpty()) {
                LOG.warn("Skipping empty Kerberos entry at index {}", kerberosEntryIndex);
                continue;
            }
            String kerberosEntryKey = resolveStringValue(kerberosEntry.get("key"), null);
            if (kerberosEntryKey == null || kerberosEntryKey.isBlank()) {
                kerberosEntryKey = "entry-" + kerberosEntryIndex;
                LOG.warn("Kerberos entry missing key; using fallback key {}", kerberosEntryKey);
            }
            kerberosSpecMap.put(kerberosEntryKey, new LinkedHashMap<>(kerberosEntry));
        }
        return kerberosSpecMap;
    }

    /**
     * Resolve the Ranger repository name for the current release.
     * Uses the repository name property defined in the Ranger plugin settings.
     *
     * @param rangerPluginSettingsSpec Ranger plugin settings map
     * @param effectiveValues merged Helm values map for the release
     * @param request current deploy request for contextual fallbacks
     * @return repository name (never blank; falls back to release-namespace)
     */
    private String resolveRangerRepositoryName(Map<String, Object> rangerPluginSettingsSpec,
                                               Map<String, Object> effectiveValues,
                                               HelmDeployRequest request) {
        String repositoryNameProperty = resolveStringValue(
                rangerPluginSettingsSpec != null ? rangerPluginSettingsSpec.get("repository_name_property") : null,
                "ranger.serviceName"
        );
        String resolvedRepositoryName = null;
        if (effectiveValues != null && repositoryNameProperty != null && !repositoryNameProperty.isBlank()) {
            Object repositoryNameRawValue = ConfigResolutionService.getByDottedPath(effectiveValues, repositoryNameProperty);
            if (repositoryNameRawValue != null) {
                resolvedRepositoryName = repositoryNameRawValue.toString();
            }
        }
        if (resolvedRepositoryName == null || resolvedRepositoryName.isBlank()) {
            resolvedRepositoryName = request.getReleaseName() + "-" + request.getNamespace();
            LOG.warn("Ranger repository name not found at helm path '{}'; using fallback '{}'",
                    repositoryNameProperty, resolvedRepositoryName);
        }
        return resolvedRepositoryName;
    }

    /**
     * Resolve the Ranger service type for repository creation.
     *
     * @param rangerPluginSettingsSpec Ranger plugin settings map
     * @param request current deploy request for contextual fallbacks
     * @return resolved service type (never blank)
     */
    private String resolveRangerServiceType(Map<String, Object> rangerPluginSettingsSpec,
                                            HelmDeployRequest request) {
        String serviceTypeValue = resolveStringValue(
                rangerPluginSettingsSpec != null ? rangerPluginSettingsSpec.get("service_type") : null,
                null
        );
        if (serviceTypeValue == null || serviceTypeValue.isBlank()) {
            String serviceKeyValue = request.getServiceKey();
            serviceTypeValue = serviceKeyValue != null ? serviceKeyValue.toLowerCase(Locale.ROOT) : "unknown";
            LOG.warn("Ranger service_type not defined in service spec; defaulting to {}", serviceTypeValue);
        }
        return serviceTypeValue;
    }

    /**
     * Extract a Ranger plugin user name from the service ranger spec, if present.
     *
     * @param rangerSpec ranger spec map keyed by logical name
     * @return plugin user name or null if not defined
     */
    private String extractRangerPluginUserName(Map<String, Map<String, Object>> rangerSpec) {
        if (rangerSpec == null || rangerSpec.isEmpty()) {
            return null;
        }
        // Search all ranger entries for a plugin username hint.
        for (Map.Entry<String, Map<String, Object>> rangerSpecEntry : rangerSpec.entrySet()) {
            Map<String, Object> rangerEntry = rangerSpecEntry.getValue();
            if (rangerEntry == null) {
                continue;
            }
            String pluginUserNameValue = resolveStringValue(rangerEntry.get("plugin_username"), null);
            if (pluginUserNameValue == null || pluginUserNameValue.isBlank()) {
                pluginUserNameValue = resolveStringValue(rangerEntry.get("pluginUserName"), null);
            }
            if (pluginUserNameValue != null && !pluginUserNameValue.isBlank()) {
                return pluginUserNameValue;
            }
        }
        return null;
    }

    /**
     * Extract a Ranger plugin password from the service ranger spec, if present.
     * This is optional because many deployments rely on pre-configured credentials.
     *
     * @param rangerSpec ranger spec map keyed by logical name
     * @return plugin password or null if not defined
     */
    private String extractRangerPluginUserPassword(Map<String, Map<String, Object>> rangerSpec) {
        if (rangerSpec == null || rangerSpec.isEmpty()) {
            return null;
        }
        // Search all ranger entries for a plugin password hint.
        for (Map.Entry<String, Map<String, Object>> rangerSpecEntry : rangerSpec.entrySet()) {
            Map<String, Object> rangerEntry = rangerSpecEntry.getValue();
            if (rangerEntry == null) {
                continue;
            }
            String pluginUserPasswordValue = resolveStringValue(rangerEntry.get("plugin_password"), null);
            if (pluginUserPasswordValue == null || pluginUserPasswordValue.isBlank()) {
                pluginUserPasswordValue = resolveStringValue(rangerEntry.get("pluginUserPassword"), null);
            }
            if (pluginUserPasswordValue != null && !pluginUserPasswordValue.isBlank()) {
                return pluginUserPasswordValue;
            }
        }
        return null;
    }

    /**
     * Determine whether a Ranger repository is configured for Kerberos-based authentication.
     * We treat the presence of Kerberos principal/keytab hints as a signal that plugin user
     * creation should be skipped (Ambari requires a password for that path).
     *
     * @param rangerSpec ranger spec map keyed by logical name
     * @return true when Kerberos hints are present
     */
    private boolean isKerberosRangerSpec(Map<String, Map<String, Object>> rangerSpec) {
        if (rangerSpec == null || rangerSpec.isEmpty()) {
            return false;
        }
        // Scan each ranger entry for Kerberos-related configuration hints.
        for (Map.Entry<String, Map<String, Object>> rangerSpecEntry : rangerSpec.entrySet()) {
            Map<String, Object> rangerSpecEntryMap = rangerSpecEntry.getValue();
            if (rangerSpecEntryMap == null || rangerSpecEntryMap.isEmpty()) {
                continue;
            }
            String krb5PrincipalServiceValue = resolveStringValue(rangerSpecEntryMap.get("krb5_princ_srv"), null);
            if (krb5PrincipalServiceValue != null && !krb5PrincipalServiceValue.isBlank()) {
                return true;
            }
            String krb5KeytabPathValue = resolveStringValue(rangerSpecEntryMap.get("krb5_keytab_path"), null);
            if (krb5KeytabPathValue != null && !krb5KeytabPathValue.isBlank()) {
                return true;
            }
            String kerberosPrincipalValue = resolveStringValue(rangerSpecEntryMap.get("kerberos_principal"), null);
            if (kerberosPrincipalValue != null && !kerberosPrincipalValue.isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create or update Ranger-related ConfigMaps/Secrets required by charts.
     * The behavior is driven entirely by the service.json Ranger spec entries.
     *
     * @param request deploy request containing the Ranger spec
     * @param params mutable params map used to store Helm overrides and Ranger metadata
     * @param effectiveValues merged Helm values map for repository name resolution
     * @param ambariActionClient Ambari client for config resolution (may be null)
     * @param cluster Ambari cluster name (may be null when Ambari is unavailable)
     * @param ambariAliasResolver resolver for Ambari credential aliases
     */
    private void materializeRangerResources(HelmDeployRequest request,
                                            Map<String, Object> params,
                                            Map<String, Object> effectiveValues,
                                            AmbariActionClient ambariActionClient,
                                            String cluster,
                                            AmbariAliasResolver ambariAliasResolver) {
        Map<String, Map<String, Object>> rangerSpec = request.getRanger();
        if (rangerSpec == null || rangerSpec.isEmpty()) {
            return;
        }

        Map<String, Object> rangerPluginSettingsSpec = rangerSpec.get("ranger-plugin-settings");
        String rangerRepositoryName = resolveRangerRepositoryName(rangerPluginSettingsSpec, effectiveValues, request);
        String rangerServiceType = resolveRangerServiceType(rangerPluginSettingsSpec, request);
        RangerConfigDefaultsProvider rangerDefaultsProvider =
                RangerConfigDefaultsRegistry.resolve(rangerServiceType);
        boolean kerberosClusterEnabled = Boolean.TRUE.equals(params.get("kerberosClusterEnabled"));
        Map<String, String> rangerExtraServiceConfigs = new LinkedHashMap<>();

        AmbariAliasResolver aliasResolver = ambariAliasResolver != null
                ? ambariAliasResolver
                : new AmbariAliasResolver(ctx);

        for (Map.Entry<String, Map<String, Object>> rangerEntry : rangerSpec.entrySet()) {
            Map<String, Object> spec = rangerEntry.getValue();
            if (spec == null || spec.isEmpty()) {
                continue;
            }
            String type = resolveStringValue(spec.get("type"), null);
            if (type == null || type.isBlank()) {
                LOG.warn("Skipping Ranger entry {} because type is missing", rangerEntry.getKey());
                continue;
            }
            if ("ranger-plugin-settings".equals(type)) {
                continue;
            }

            String name = resolveStringValue(spec.get("cm_name"), null);
            String kind = resolveStringValue(spec.get("cm_type"), null);
            if (name == null || name.isBlank() || kind == null || kind.isBlank()) {
                LOG.warn("Skipping Ranger entry {} because cm_name/cm_type is missing", rangerEntry.getKey());
                continue;
            }
            boolean asSecret = "secret".equalsIgnoreCase(kind);
            boolean doCreation = true;
            String xmlKey = name + ".xml";
            String xml = null;

            switch (type) {
                case "ranger-security": {
                    Map<String, String> rangerSecurityConfig = new LinkedHashMap<>();
                    if (rangerDefaultsProvider != null) {
                        rangerSecurityConfig.putAll(rangerDefaultsProvider.security(rangerServiceType));
                        rangerSecurityConfig.putAll(rangerDefaultsProvider.pluginProperties(rangerServiceType));
                    }

                    String policyMgrSslFilePath = resolveStringValue(spec.get("ranger_policymgr_file"), null);
                    String policyMgrSslHelmProperty = resolveStringValue(spec.get("helm_values_ranger_policymgr_file"), null);
                    if (policyMgrSslFilePath != null && policyMgrSslHelmProperty != null) {
                        this.commandUtils.addOverride(params, policyMgrSslHelmProperty, policyMgrSslFilePath);
                    }

                    String rangerPluginSuperUsers = resolveStringValue(spec.get("superusers"), null);
                    if (rangerPluginSuperUsers != null && !rangerPluginSuperUsers.isBlank()) {
                        rangerSecurityConfig.put(
                                "ranger.plugin." + rangerServiceType + ".super.users",
                                rangerPluginSuperUsers
                        );
                    }

                    String rangerPolicyCacheDir = resolveStringValue(spec.get("ranger_policycache_dir"), null);
                    String rangerPolicyCacheDirHelmProperty = resolveStringValue(spec.get("ranger_policycache_dir_helm_property"), null);
                    if (rangerPolicyCacheDir != null && rangerPolicyCacheDirHelmProperty != null
                            && !rangerPolicyCacheDir.isBlank() && !rangerPolicyCacheDirHelmProperty.isBlank()) {
                        this.commandUtils.addOverride(params, rangerPolicyCacheDirHelmProperty, rangerPolicyCacheDir);
                        rangerSecurityConfig.put(
                                "ranger.plugin." + rangerServiceType + ".policy.cache.dir",
                                rangerPolicyCacheDir
                        );
                    }

                    if (kerberosClusterEnabled && ambariActionClient != null && cluster != null && !cluster.isBlank()) {
                        try {
                            String kerberosRealm = ambariActionClient.getDesiredConfigProperty(
                                    cluster,
                                    this.commandUtils.DYNAMIC_SOURCE_MAP.get("AMBARI_KERBEROS_REALM").type,
                                    this.commandUtils.DYNAMIC_SOURCE_MAP.get("AMBARI_KERBEROS_REALM").key
                            );
                            String rangerPrincipalPrefix = resolveStringValue(spec.get("krb5_princ_srv"), null);
                            if (rangerPrincipalPrefix == null || rangerPrincipalPrefix.isBlank()) {
                                LOG.warn("Ranger Kerberos principal prefix (krb5_princ_srv) missing for {}", type);
                            } else {
                                String rangerKeytabPath = resolveStringValue(
                                        spec.get("krb5_keytab_path"),
                                        "/etc/security/keytabs/service.keytab"
                                );
                                rangerPrincipalPrefix = rangerPrincipalPrefix + "-" + request.getNamespace();
                                rangerSecurityConfig.put(
                                        "ranger.plugin." + rangerServiceType + ".ugi.login.type",
                                        "keytab"
                                );
                                rangerSecurityConfig.put(
                                        "ranger.plugin." + rangerServiceType + ".ugi.keytab.principal",
                                        rangerPrincipalPrefix + "@" + kerberosRealm
                                );
                                rangerSecurityConfig.put(
                                        "ranger.plugin." + rangerServiceType + ".ugi.keytab.file",
                                        rangerKeytabPath
                                );
                                rangerSecurityConfig.put(
                                        "ranger.plugin." + rangerServiceType + ".ugi.initialize",
                                        "true"
                                );
                                rangerExtraServiceConfigs.put("policy.download.auth.users", rangerPrincipalPrefix);
                                rangerExtraServiceConfigs.put("tag.download.auth.users", rangerPrincipalPrefix);
                            }
                        } catch (Exception ex) {
                            LOG.warn("Failed to resolve Kerberos realm for Ranger config: {}", ex.toString());
                        }
                    } else if (!kerberosClusterEnabled) {
                        String rangerPluginUserName = resolveStringValue(spec.get("plugin_username"), null);
                        if (rangerPluginUserName != null && !rangerPluginUserName.isBlank()) {
                            String pluginPasswordSecretName = resolveStringValue(
                                    spec.get("plugin_password_secret_name"),
                                    "ranger-plugin-rest-client-password"
                            );
                            String pluginPasswordSecretKey = resolveStringValue(
                                    spec.get("plugin_password_secret_k"),
                                    "password"
                            );
                            String pluginPassword = kubernetesService
                                    .readOpaqueSecretKeyAsString(
                                            request.getNamespace(),
                                            pluginPasswordSecretName,
                                            pluginPasswordSecretKey
                                    )
                                    .orElse(null);
                            if (pluginPassword == null) {
                                pluginPassword = UUID.randomUUID().toString();
                                Map<String, byte[]> pwdData = new LinkedHashMap<>();
                                pwdData.put(pluginPasswordSecretKey, pluginPassword.getBytes(StandardCharsets.UTF_8));
                                kubernetesService.createOrUpdateOpaqueSecret(
                                        request.getNamespace(),
                                        pluginPasswordSecretName,
                                        pwdData
                                );
                                LOG.info("Created Ranger plugin password secret {} in namespace {}",
                                        pluginPasswordSecretName, request.getNamespace());
                            } else {
                                LOG.info("Reusing Ranger plugin password secret {} in namespace {}",
                                        pluginPasswordSecretName, request.getNamespace());
                            }
                            params.put("_rangerPluginUserName", rangerPluginUserName);
                            params.put("_rangerPluginUserPassword", pluginPassword);
                            rangerSecurityConfig.put(
                                    "ranger.plugin." + rangerServiceType + ".policy.rest.client.username",
                                    rangerPluginUserName
                            );
                            rangerSecurityConfig.put(
                                    "ranger.plugin." + rangerServiceType + ".policy.rest.client.password",
                                    pluginPassword
                            );
                        }
                    }

                    rangerSecurityConfig.put(
                            "ranger.plugin." + rangerServiceType + ".service.name",
                            rangerRepositoryName
                    );
                    if (policyMgrSslFilePath != null && !policyMgrSslFilePath.isBlank()) {
                        rangerSecurityConfig.put(
                                "ranger.plugin." + rangerServiceType + ".policy.rest.ssl.config.file",
                                policyMgrSslFilePath
                        );
                    }

                    if (ambariActionClient != null && cluster != null && !cluster.isBlank()) {
                        try {
                            String rangerPolicyMgrExternalUrl = ambariActionClient.getDesiredConfigProperty(
                                    cluster,
                                    "admin-properties",
                                    "policymgr_external_url"
                            );
                            if (rangerPolicyMgrExternalUrl != null && !rangerPolicyMgrExternalUrl.isBlank()) {
                                rangerSecurityConfig.put(
                                        "ranger.plugin." + rangerServiceType + ".policy.rest.url",
                                        rangerPolicyMgrExternalUrl
                                );
                            }
                        } catch (Exception ex) {
                            LOG.warn("Failed to resolve Ranger policymgr external URL: {}", ex.toString());
                        }
                    }

                    String helmValuesProperty = resolveStringValue(spec.get("helm_values_property"), null);
                    if (helmValuesProperty != null && !helmValuesProperty.isBlank()) {
                        this.commandUtils.addOverride(params, helmValuesProperty, name);
                    }
                    xml = HadoopSiteXml.render(rangerSecurityConfig);
                    xmlKey = name + ".xml";
                    break;
                }
                case "ranger-audit": {
                    String helmValuesProperty = resolveStringValue(spec.get("helm_values_property"), null);
                    if (helmValuesProperty != null && !helmValuesProperty.isBlank()) {
                        this.commandUtils.addOverride(params, helmValuesProperty, name);
                    }
                    Map<String, String> auditConfig = new LinkedHashMap<>();
                    if (rangerDefaultsProvider != null) {
                        auditConfig.putAll(rangerDefaultsProvider.audit());
                    }
                    xml = HadoopSiteXml.render(auditConfig);
                    xmlKey = name + ".xml";
                    break;
                }
                case "ranger-policymgr-ssl": {
                    Map<String, String> policymgrSsl = new LinkedHashMap<>();
                    if (rangerDefaultsProvider != null) {
                        policymgrSsl.putAll(rangerDefaultsProvider.policymgrSsl());
                    }

                    String credProviderPath = resolveStringValue(spec.get("ranger_plugin_credential_provider_path"), null);
                    String credProviderPathHelm = resolveStringValue(spec.get("ranger_plugin_credential_provider_path_helm_prop"), null);
                    if (credProviderPath != null && credProviderPathHelm != null) {
                        this.commandUtils.addOverride(params, credProviderPathHelm, credProviderPath);
                    }
                    String credProviderFile = resolveStringValue(spec.get("ranger_plugin_credential_provider_file"), null);
                    String credProviderFileHelm = resolveStringValue(spec.get("ranger_plugin_credential_provider_file_helm_prop"), null);
                    if (credProviderFile != null && credProviderFileHelm != null) {
                        this.commandUtils.addOverride(params, credProviderFileHelm, credProviderFile);
                    }
                    String credProviderSecretName = resolveStringValue(
                            spec.get("ranger_plugin_credential_provider_secretName"),
                            null
                    );
                    String credProviderSecretNameHelm = resolveStringValue(
                            spec.get("ranger_plugin_credential_provider_secretName_helm_prop"),
                            null
                    );
                    if (credProviderSecretName != null && credProviderSecretNameHelm != null) {
                        this.commandUtils.addOverride(params, credProviderSecretNameHelm, credProviderSecretName);
                    }

                    String truststorePath = ctx.getAmbariProperty("ssl.trustStore.path");
                    if (truststorePath != null && !truststorePath.isBlank()
                            && credProviderPath != null && credProviderFile != null && credProviderSecretName != null) {
                        try {
                            String truststorePassProp = Optional.ofNullable(ctx.getAmbariProperty("ssl.trustStore.password"))
                                    .orElse("");
                            char[] truststorePass = aliasResolver.resolve(ctx, truststorePassProp);
                            RangerCredentialWriter writer = new RangerCredentialWriter(kubernetesService);
                            String jceksPath = credProviderPath + "/" + credProviderFile;
                            Map<String, String> props = writer.ensureJceksSecretForTruststorePassword(
                                    request.getNamespace(),
                                    credProviderSecretName,
                                    jceksPath,
                                    RangerCredentialWriter.DEFAULT_ALIAS,
                                    truststorePass,
                                    Map.of("app", rangerServiceType),
                                    Map.of("purpose", "ranger-ssl-password")
                            );
                            policymgrSsl.put("xasecure.policymgr.clientssl.truststore.password", "crypted");
                            policymgrSsl.putAll(props);
                        } catch (Exception ex) {
                            LOG.warn("Failed to generate Ranger credential provider: {}", ex.toString());
                        }
                    }

                    String helmValuesProperty = resolveStringValue(spec.get("helm_values_property"), null);
                    if (helmValuesProperty != null && !helmValuesProperty.isBlank()) {
                        this.commandUtils.addOverride(params, helmValuesProperty, name);
                    }
                    xml = HadoopSiteXml.render(policymgrSsl);
                    xmlKey = name + "-" + rangerServiceType + ".xml";
                    break;
                }
                case "ranger-admin-truststore": {
                    String helmValuesSecretNameKey = resolveStringValue(spec.get("helm_values_property"), null);
                    if (helmValuesSecretNameKey != null && !helmValuesSecretNameKey.isBlank()) {
                        this.commandUtils.addOverride(params, helmValuesSecretNameKey, name);
                    }
                    String helmValuesPasswordSecretKey = resolveStringValue(spec.get("truststore_password_helm_values_property"), null);
                    String helmValuesPasswordSecretValue = resolveStringValue(spec.get("truststore_password_secret_name"), null);
                    if (helmValuesPasswordSecretKey != null && helmValuesPasswordSecretValue != null) {
                        this.commandUtils.addOverride(params, helmValuesPasswordSecretKey, helmValuesPasswordSecretValue);
                    }
                    String helmValuesFileKey = resolveStringValue(spec.get("truststore_file_key_helm_values_property"), null);
                    String helmValuesFileValue = resolveStringValue(spec.get("truststore_file_key"), null);
                    if (helmValuesFileKey != null && helmValuesFileValue != null) {
                        this.commandUtils.addOverride(params, helmValuesFileKey, helmValuesFileValue);
                    }
                    String helmValuesPasswordKey = resolveStringValue(spec.get("truststore_password_secret_key_name"), null);
                    String helmValuesPasswordValue = resolveStringValue(spec.get("truststore_password_secret_key_value"), null);
                    if (helmValuesPasswordKey != null && helmValuesPasswordValue != null) {
                        this.commandUtils.addOverride(params, helmValuesPasswordKey, helmValuesPasswordValue);
                    }

                    String truststorePath = ctx.getAmbariProperty("ssl.trustStore.path");
                    if (truststorePath != null && !truststorePath.isBlank()) {
                        try {
                            String truststoreType = Optional.ofNullable(ctx.getAmbariProperty("ssl.trustStore.type"))
                                    .filter(s -> !s.isBlank()).orElse("JKS");
                            String truststorePassProp = Optional.ofNullable(ctx.getAmbariProperty("ssl.trustStore.password"))
                                    .orElse("");
                            char[] truststorePass = aliasResolver.resolve(ctx, truststorePassProp);
                            if (helmValuesFileValue != null && !helmValuesFileValue.isBlank()) {
                                kubernetesService.ensureOrRotateTruststoreSecretFromLocalFile(
                                        request.getNamespace(),
                                        name,
                                        truststorePath,
                                        truststoreType,
                                        truststorePass,
                                        helmValuesFileValue,
                                        365,
                                        null
                                );
                            }
                            if (helmValuesPasswordSecretValue != null && !helmValuesPasswordSecretValue.isBlank()
                                    && helmValuesPasswordValue != null && !helmValuesPasswordValue.isBlank()) {
                                kubernetesService.createOrUpdateOpaqueSecret(
                                        request.getNamespace(),
                                        helmValuesPasswordSecretValue,
                                        helmValuesPasswordValue,
                                        new String(truststorePass).getBytes(StandardCharsets.UTF_8)
                                );
                            }
                        } catch (Exception ex) {
                            LOG.warn("Failed to provision Ranger admin truststore: {}", ex.toString());
                        }
                    }
                    doCreation = false;
                    break;
                }
                case "ranger-admin-tls-client": {
                    doCreation = false;
                    break;
                }
                default:
                    LOG.info("Skipping unsupported Ranger spec type {}", type);
                    doCreation = false;
            }

            if (doCreation) {
                if (xml == null || xml.isBlank()) {
                    LOG.warn("Skipping Ranger config {} because XML content is empty", name);
                    continue;
                }
                if (asSecret) {
                    Map<String, byte[]> data = new LinkedHashMap<>();
                    data.put(xmlKey, xml.getBytes(StandardCharsets.UTF_8));
                    kubernetesService.createOrUpdateOpaqueSecret(request.getNamespace(), name, data);
                } else {
                    Map<String, String> data = new LinkedHashMap<>();
                    data.put(xmlKey, xml);
                    kubernetesService.createOrUpdateConfigMap(
                            request.getNamespace(),
                            name,
                            data,
                            Map.of("managed-by", "ambari-k8s-view"),
                            Map.of()
                    );
                }
            }
        }

        if (!rangerExtraServiceConfigs.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, String> existingConfigs = (Map<String, String>) params.get("_rangerServiceConfigs");
            Map<String, String> mergedConfigs = new LinkedHashMap<>();
            if (existingConfigs != null && !existingConfigs.isEmpty()) {
                mergedConfigs.putAll(existingConfigs);
            }
            mergedConfigs.putAll(rangerExtraServiceConfigs);
            params.put("_rangerServiceConfigs", mergedConfigs);
            LOG.info("Added Ranger service configs derived from security settings: {}", mergedConfigs);
        }
    }

    /**
     * Plan a Ranger repository creation step for a release. This is used during initial install
     * and for reapply actions, and it is fully driven by the service.json ranger spec.
     *
     * @param rootCommand root command entity
     * @param childCommands mutable list of child command ids
     * @param request helm deploy request containing release context
     * @param params root params map (mutated to include ranger metadata)
     * @param effectiveValues merged Helm values map for templated Ranger configs
     * @param ambariActionClient Ambari client used for config resolution
     * @param cluster Ambari cluster name (for logging/config resolution)
     */
    private void planRangerRepositoryCreation(CommandEntity rootCommand,
                                              List<String> childCommands,
                                              HelmDeployRequest request,
                                              Map<String, Object> params,
                                              Map<String, Object> effectiveValues,
                                              AmbariActionClient ambariActionClient,
                                              String cluster) {
        Map<String, Map<String, Object>> rangerSpec = request.getRanger();
        if (rangerSpec == null || rangerSpec.isEmpty()) {
            LOG.info("No Ranger spec provided for release {}; skipping Ranger repository planning.", request.getReleaseName());
            return;
        }
        // Ranger plugin settings drive repository naming and extra configs.
        Map<String, Object> rangerPluginSettingsSpec = rangerSpec.get("ranger-plugin-settings");
        if (rangerPluginSettingsSpec == null || rangerPluginSettingsSpec.isEmpty()) {
            LOG.info("No ranger-plugin-settings in spec for release {}; skipping plugin-repository creation. "
                    + "TagSync sources (if any) are handled by planRangerTagSyncSources, called separately.",
                    request.getReleaseName());
            return;
        }

        String resolvedRepositoryName = resolveRangerRepositoryName(rangerPluginSettingsSpec, effectiveValues, request);
        String resolvedServiceType = resolveRangerServiceType(rangerPluginSettingsSpec, request);
        String rangerPluginUserName = resolveStringValue(params.get("_rangerPluginUserName"), null);
        if (rangerPluginUserName == null || rangerPluginUserName.isBlank()) {
            rangerPluginUserName = extractRangerPluginUserName(rangerSpec);
        }
        String rangerPluginUserPassword = resolveStringValue(params.get("_rangerPluginUserPassword"), null);
        if (rangerPluginUserPassword == null || rangerPluginUserPassword.isBlank()) {
            rangerPluginUserPassword = extractRangerPluginUserPassword(rangerSpec);
        }

        // Build extra Ranger service configs from templated specs, if available.
        Map<String, String> rangerServiceConfigs = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, String> existingServiceConfigs = (Map<String, String>) params.get("_rangerServiceConfigs");
        if (existingServiceConfigs != null && !existingServiceConfigs.isEmpty()) {
            rangerServiceConfigs.putAll(existingServiceConfigs);
        }
        if (ambariActionClient != null && cluster != null && !cluster.isBlank()) {
            Map<String, String> computedConfigs = configResolutionService.computeExtraRangerConfigs(
                    rangerPluginSettingsSpec,
                    params,
                    effectiveValues,
                    ambariActionClient,
                    cluster
            );
            if (computedConfigs != null && !computedConfigs.isEmpty()) {
                rangerServiceConfigs.putAll(computedConfigs);
            }
        } else {
            LOG.warn("Ambari client unavailable; skipping Ranger extra config resolution for release {}", request.getReleaseName());
        }

        // Persist ranger metadata into params for the child command step.
        params.put("_rangerSpec", rangerSpec);
        params.put("_rangerRepositoryName", resolvedRepositoryName);
        params.put("_rangerServiceType", resolvedServiceType);
        params.put("_rangerPluginUserName", rangerPluginUserName);
        params.put("_rangerPluginUserPassword", rangerPluginUserPassword);
        params.put("_rangerServiceConfigs", rangerServiceConfigs);

        // Ensure the helm values contain the repository name if it was missing.
        String repositoryNameProperty = resolveStringValue(rangerPluginSettingsSpec.get("repository_name_property"), null);
        if (repositoryNameProperty != null && !repositoryNameProperty.isBlank()) {
            Object repositoryNameValue = effectiveValues != null
                    ? ConfigResolutionService.getByDottedPath(effectiveValues, repositoryNameProperty)
                    : null;
            if (repositoryNameValue == null || repositoryNameValue.toString().isBlank()) {
                LOG.info("Injecting Ranger repository name override {} -> {}", repositoryNameProperty, resolvedRepositoryName);
                this.commandUtils.addOverride(params, repositoryNameProperty, resolvedRepositoryName);
            }
        }

        LOG.info("Planning Ranger repository action: repo='{}', serviceType='{}', pluginUser='{}'",
                resolvedRepositoryName, resolvedServiceType, rangerPluginUserName);

        // Add the Ranger repository creation step to the command plan.
        this.commandPlanFactory.createRangerPluginRepository(rootCommand, rangerSpec, params, childCommands);
    }

    /**
     * Planning sibling for {@code ranger-tagsync-source} entries — gated by a
     * {@code ranger-tagsync-settings} block in the service.json {@code ranger}
     * map. Mirrors {@link #planRangerRepositoryCreation}'s {@code ranger-plugin-settings}
     * gate: the settings block carries chart-wide TagSync wiring (endpoint helm
     * path, fernet Secret coordinates, OM REST shape, bot username) and signals
     * "this service supports TagSync registration." Per-source entries
     * ({@code ranger-tagsync-source}) carry identity-only fields.
     *
     * <p>The split makes a service that only registers TagSync sources
     * (OPENMETADATA — OM is a tag SOURCE, not a Ranger plugin consumer) work
     * cleanly: no spurious "Ranger plugin settings missing" warning, no need to
     * fake a plugin-settings block.
     */
    void planRangerTagSyncSources(CommandEntity rootCommand,
                                  List<String> childCommandsIgnored,
                                  HelmDeployRequest request,
                                  Map<String, Object> params) {
        // childCommandsIgnored is kept for symmetry with planRangerRepositoryCreation's
        // signature but is unused — createRangerTagSyncRegister self-persists
        // rootCommand.childListJson so the post-deploy call site doesn't need to
        // re-save the root afterwards.
        Map<String, Map<String, Object>> rangerSpec = request.getRanger();
        if (rangerSpec == null || rangerSpec.isEmpty()) {
            return;
        }
        Map<String, Object> tagSyncSettingsSpec = rangerSpec.get("ranger-tagsync-settings");
        if (tagSyncSettingsSpec == null || tagSyncSettingsSpec.isEmpty()) {
            LOG.info("No ranger-tagsync-settings in spec for release {}; skipping TagSync source registration.",
                    request.getReleaseName());
            return;
        }
        // Persist settings into params so the runtime handler (registerRangerTagSyncSource)
        // can read endpoint helm path, fernet Secret name template, OM bot wiring, etc.
        params.put("_tagSyncSettings", tagSyncSettingsSpec);

        // Defer to the plan factory to walk ranger-tagsync-source entries.
        this.commandPlanFactory.createRangerTagSyncRegister(rootCommand, rangerSpec, params);
    }

    /**
     * Build catalog enrichment overrides from the service definition.
     * This method is service-agnostic and driven entirely by catalogEnrichments specs.
     *
     * @param catalogEnrichmentSpecList list of enrichment specs from service.json
     * @param params root params map (used for templating)
     * @param effectiveValues merged Helm values (used to read existing catalog content)
     * @param ambariActionClient Ambari client for config lookups
     * @param clusterName Ambari cluster name
     * @param kerberosEnabled true when Kerberos is enabled on the Ambari cluster
     * @return map of Helm overrides (path -> updated catalog string)
     */
    private Map<String, String> buildCatalogEnrichmentOverrides(List<Map<String, Object>> catalogEnrichmentSpecList,
                                                                Map<String, Object> params,
                                                                Map<String, Object> effectiveValues,
                                                                AmbariActionClient ambariActionClient,
                                                                String clusterName,
                                                                boolean kerberosEnabled) {
        Map<String, String> catalogOverrides = new LinkedHashMap<>();
        if (catalogEnrichmentSpecList == null || catalogEnrichmentSpecList.isEmpty()) {
            LOG.info("No catalog enrichment specs found; skipping catalog enrichment.");
            return catalogOverrides;
        }

        Map<String, Object> templateEnv = this.configResolutionService.buildTemplateEnv(params, clusterName);
        int enrichmentIndex = 0;
        for (Map<String, Object> catalogEnrichmentSpec : catalogEnrichmentSpecList) {
            enrichmentIndex++;
            if (catalogEnrichmentSpec == null || catalogEnrichmentSpec.isEmpty()) {
                LOG.warn("Skipping empty catalog enrichment spec at index {}", enrichmentIndex);
                continue;
            }
            String enrichmentName = resolveStringValue(
                    catalogEnrichmentSpec.get("name"),
                    "catalog-enrichment-" + enrichmentIndex
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> whenSpec = (Map<String, Object>) catalogEnrichmentSpec.get("when");
            if (!shouldApplyCatalogEnrichment(whenSpec, kerberosEnabled)) {
                LOG.info("Catalog enrichment '{}' skipped due to 'when' conditions.", enrichmentName);
                continue;
            }

            String targetPath = resolveCatalogEnrichmentTargetPath(catalogEnrichmentSpec);
            if (targetPath == null || targetPath.isBlank()) {
                LOG.warn("Catalog enrichment '{}' missing target.path; skipping.", enrichmentName);
                continue;
            }

            String currentCatalogContent = catalogOverrides.get(targetPath);
            if (currentCatalogContent == null) {
                List<String> basePathList = resolveCatalogEnrichmentBasePaths(catalogEnrichmentSpec);
                if (basePathList != null && !basePathList.isEmpty()) {
                    currentCatalogContent = mergeCatalogBaseContents(
                            basePathList,
                            catalogOverrides,
                            effectiveValues,
                            enrichmentName,
                            targetPath
                    );
                }
                if (currentCatalogContent == null) {
                    Object currentCatalogValue = ConfigResolutionService.getByDottedPath(effectiveValues, targetPath);
                    currentCatalogContent = currentCatalogValue == null ? "" : String.valueOf(currentCatalogValue);
                }
            }
            List<String> catalogLines = parseCatalogContentToLines(currentCatalogContent);
            Map<String, Integer> catalogKeyIndexMap = indexCatalogKeyPositions(catalogLines);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> propertySpecList =
                    (List<Map<String, Object>>) catalogEnrichmentSpec.get("properties");
            if (propertySpecList == null || propertySpecList.isEmpty()) {
                LOG.warn("Catalog enrichment '{}' has no properties; skipping.", enrichmentName);
                continue;
            }

            boolean updatesApplied = false;
            for (Map<String, Object> propertySpec : propertySpecList) {
                if (propertySpec == null || propertySpec.isEmpty()) {
                    continue;
                }
                String propertyKey = resolveStringValue(propertySpec.get("key"), null);
                if (propertyKey == null || propertyKey.isBlank()) {
                    LOG.warn("Catalog enrichment '{}' has a property with empty key; skipping.", enrichmentName);
                    continue;
                }
                boolean overrideExisting = asBoolean(propertySpec.get("overrideExisting"), true);
                String resolvedValue = resolveCatalogPropertyValue(
                        propertySpec,
                        params,
                        effectiveValues,
                        ambariActionClient,
                        clusterName,
                        templateEnv
                );
                if (resolvedValue == null) {
                    LOG.warn("Catalog enrichment '{}' property '{}' resolved to null; skipping.", enrichmentName, propertyKey);
                    continue;
                }
                if (resolvedValue.isBlank()) {
                    LOG.warn("Catalog enrichment '{}' property '{}' resolved to blank value; applying anyway.", enrichmentName, propertyKey);
                }

                if (catalogKeyIndexMap.containsKey(propertyKey)) {
                    if (!overrideExisting) {
                        LOG.info("Catalog enrichment '{}' leaves existing property '{}' untouched (overrideExisting=false).", enrichmentName, propertyKey);
                        continue;
                    }
                    int existingIndex = catalogKeyIndexMap.get(propertyKey);
                    catalogLines.set(existingIndex, propertyKey + "=" + resolvedValue);
                    updatesApplied = true;
                } else {
                    catalogLines.add(propertyKey + "=" + resolvedValue);
                    catalogKeyIndexMap.put(propertyKey, catalogLines.size() - 1);
                    updatesApplied = true;
                }
            }

            if (updatesApplied) {
                String updatedCatalogContent = normalizeCatalogContent(catalogLines);
                catalogOverrides.put(targetPath, updatedCatalogContent);
                LOG.info("Catalog enrichment '{}' produced override for {} ({} chars).",
                        enrichmentName, targetPath, updatedCatalogContent.length());
            } else {
                LOG.info("Catalog enrichment '{}' produced no updates for {}.", enrichmentName, targetPath);
            }
        }

        return catalogOverrides;
    }

    /**
     * Resolve the target path for a catalog enrichment spec.
     *
     * @param catalogEnrichmentSpec raw enrichment spec map
     * @return dotted helm path or null when missing
     */
    private String resolveCatalogEnrichmentTargetPath(Map<String, Object> catalogEnrichmentSpec) {
        if (catalogEnrichmentSpec == null || catalogEnrichmentSpec.isEmpty()) {
            return null;
        }
        Object targetSpecObj = catalogEnrichmentSpec.get("target");
        if (targetSpecObj instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> targetSpecMap = (Map<String, Object>) targetSpecObj;
            return resolveStringValue(targetSpecMap.get("path"), null);
        }
        if (targetSpecObj instanceof String) {
            return resolveStringValue(targetSpecObj, null);
        }
        return resolveStringValue(catalogEnrichmentSpec.get("path"), null);
    }

    /**
     * Determine whether a catalog enrichment should run, based on its "when" block.
     *
     * @param whenSpec optional when block (may be null)
     * @param kerberosEnabled true if Kerberos is enabled on the Ambari cluster
     * @return true when enrichment should run
     */
    private boolean shouldApplyCatalogEnrichment(Map<String, Object> whenSpec, boolean kerberosEnabled) {
        if (whenSpec == null || whenSpec.isEmpty()) {
            return true;
        }
        Object kerberosEnabledSpec = whenSpec.get("kerberosEnabled");
        if (kerberosEnabledSpec != null) {
            boolean expectedKerberosEnabled = asBoolean(kerberosEnabledSpec, false);
            if (expectedKerberosEnabled != kerberosEnabled) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolve optional base paths for catalog enrichment.
     * When provided, base paths are merged in order to build the initial catalog content before
     * applying enrichment updates to the target path.
     *
     * @param catalogEnrichmentSpec raw enrichment spec map
     * @return ordered list of base paths (may be empty)
     */
    private List<String> resolveCatalogEnrichmentBasePaths(Map<String, Object> catalogEnrichmentSpec) {
        if (catalogEnrichmentSpec == null || catalogEnrichmentSpec.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> basePathSet = new LinkedHashSet<>();

        Object targetSpecObj = catalogEnrichmentSpec.get("target");
        if (targetSpecObj instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> targetSpecMap = (Map<String, Object>) targetSpecObj;
            addCatalogEnrichmentBasePaths(basePathSet, targetSpecMap.get("basePaths"));
            String targetBasePathValue = resolveStringValue(targetSpecMap.get("basePath"), null);
            if (targetBasePathValue != null && !targetBasePathValue.isBlank()) {
                basePathSet.add(targetBasePathValue);
            }
        }

        addCatalogEnrichmentBasePaths(basePathSet, catalogEnrichmentSpec.get("basePaths"));
        String basePathValue = resolveStringValue(catalogEnrichmentSpec.get("basePath"), null);
        if (basePathValue != null && !basePathValue.isBlank()) {
            basePathSet.add(basePathValue);
        }

        if (!basePathSet.isEmpty()) {
            LOG.info("Resolved catalog enrichment base paths: {}", basePathSet);
        }

        return new ArrayList<>(basePathSet);
    }

    /**
     * Merge base catalog content from multiple paths into a single catalog string.
     *
     * @param basePathList ordered list of base paths to merge
     * @param catalogOverrides existing catalog overrides computed so far
     * @param effectiveValues merged Helm values
     * @param enrichmentName name of the catalog enrichment (for logging)
     * @param targetPath target catalog path being enriched
     * @return merged catalog content, or null when no base content was found
     */
    private String mergeCatalogBaseContents(List<String> basePathList,
                                            Map<String, String> catalogOverrides,
                                            Map<String, Object> effectiveValues,
                                            String enrichmentName,
                                            String targetPath) {
        if (basePathList == null || basePathList.isEmpty()) {
            return null;
        }

        List<String> mergedCatalogLines = null;
        Map<String, Integer> mergedCatalogKeyIndexMap = null;

        for (String basePathEntry : basePathList) {
            if (basePathEntry == null || basePathEntry.isBlank()) {
                continue;
            }
            String baseCatalogContent = null;
            if (catalogOverrides != null && catalogOverrides.containsKey(basePathEntry)) {
                baseCatalogContent = catalogOverrides.get(basePathEntry);
                LOG.info("Catalog enrichment '{}' resolved base path '{}' from overrides for target {}.",
                        enrichmentName, basePathEntry, targetPath);
            } else {
                Object baseValueObj = ConfigResolutionService.getByDottedPath(effectiveValues, basePathEntry);
                if (baseValueObj != null) {
                    baseCatalogContent = String.valueOf(baseValueObj);
                    LOG.info("Catalog enrichment '{}' resolved base path '{}' from Helm values for target {}.",
                            enrichmentName, basePathEntry, targetPath);
                }
            }

            if (baseCatalogContent == null || baseCatalogContent.isBlank()) {
                LOG.warn("Catalog enrichment '{}' base path '{}' is empty for target {}.",
                        enrichmentName, basePathEntry, targetPath);
                continue;
            }

            if (mergedCatalogLines == null) {
                mergedCatalogLines = parseCatalogContentToLines(baseCatalogContent);
                mergedCatalogKeyIndexMap = indexCatalogKeyPositions(mergedCatalogLines);
                continue;
            }

            List<String> baseCatalogLines = parseCatalogContentToLines(baseCatalogContent);
            for (String baseCatalogLine : baseCatalogLines) {
                String propertyKey = extractCatalogPropertyKey(baseCatalogLine);
                if (propertyKey == null || propertyKey.isBlank()) {
                    continue;
                }
                if (mergedCatalogKeyIndexMap.containsKey(propertyKey)) {
                    int existingIndex = mergedCatalogKeyIndexMap.get(propertyKey);
                    mergedCatalogLines.set(existingIndex, propertyKey + "=" + extractCatalogPropertyValue(baseCatalogLine));
                } else {
                    mergedCatalogLines.add(propertyKey + "=" + extractCatalogPropertyValue(baseCatalogLine));
                    mergedCatalogKeyIndexMap.put(propertyKey, mergedCatalogLines.size() - 1);
                }
            }
        }

        if (mergedCatalogLines == null || mergedCatalogLines.isEmpty()) {
            return null;
        }
        return normalizeCatalogContent(mergedCatalogLines);
    }

    /**
     * Add base paths into a target set from a mixed basePaths object.
     *
     * @param basePathSet ordered set of base paths to populate
     * @param basePathsObj basePaths object (string or list)
     */
    private void addCatalogEnrichmentBasePaths(Set<String> basePathSet, Object basePathsObj) {
        if (basePathSet == null || basePathsObj == null) {
            return;
        }
        if (basePathsObj instanceof String) {
            String basePathValue = resolveStringValue(basePathsObj, null);
            if (basePathValue != null && !basePathValue.isBlank()) {
                basePathSet.add(basePathValue);
            }
            return;
        }
        if (basePathsObj instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<Object> basePathList = (List<Object>) basePathsObj;
            for (Object basePathObj : basePathList) {
                String basePathValue = resolveStringValue(basePathObj, null);
                if (basePathValue != null && !basePathValue.isBlank()) {
                    basePathSet.add(basePathValue);
                }
            }
            return;
        }
        LOG.warn("Unsupported basePaths type {}; expected string or list.", basePathsObj.getClass().getName());
    }

    /**
     * Extract a catalog property key from a raw line.
     *
     * @param rawLine raw catalog line
     * @return property key or null if not a property line
     */
    private String extractCatalogPropertyKey(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String trimmedLine = rawLine.trim();
        if (trimmedLine.isBlank() || trimmedLine.startsWith("#")) {
            return null;
        }
        int separatorIndex = trimmedLine.indexOf('=');
        if (separatorIndex <= 0) {
            return null;
        }
        String key = trimmedLine.substring(0, separatorIndex).trim();
        return key.isBlank() ? null : key;
    }

    /**
     * Extract a catalog property value from a raw line.
     *
     * @param rawLine raw catalog line
     * @return property value or empty string if not found
     */
    private String extractCatalogPropertyValue(String rawLine) {
        if (rawLine == null) {
            return "";
        }
        String trimmedLine = rawLine.trim();
        int separatorIndex = trimmedLine.indexOf('=');
        if (separatorIndex < 0 || separatorIndex >= trimmedLine.length() - 1) {
            return "";
        }
        return trimmedLine.substring(separatorIndex + 1).trim();
    }

    /**
     * Parse catalog content into a list of raw lines, preserving comments and blank lines.
     *
     * @param catalogContent raw catalog content string
     * @return mutable list of lines (never null)
     */
    private List<String> parseCatalogContentToLines(String catalogContent) {
        List<String> lines = new ArrayList<>();
        if (catalogContent == null || catalogContent.isBlank()) {
            return lines;
        }
        String[] rawLines = catalogContent.split("\\r?\\n", -1);
        for (String rawLine : rawLines) {
            lines.add(rawLine);
        }
        return lines;
    }

    /**
     * Index key positions inside catalog lines so we can update in-place.
     *
     * @param catalogLines list of catalog lines
     * @return map of property key -> line index
     */
    private Map<String, Integer> indexCatalogKeyPositions(List<String> catalogLines) {
        Map<String, Integer> keyIndexMap = new LinkedHashMap<>();
        if (catalogLines == null || catalogLines.isEmpty()) {
            return keyIndexMap;
        }
        for (int lineIndex = 0; lineIndex < catalogLines.size(); lineIndex++) {
            String rawLine = catalogLines.get(lineIndex);
            if (rawLine == null) {
                continue;
            }
            String trimmedLine = rawLine.trim();
            if (trimmedLine.isBlank() || trimmedLine.startsWith("#")) {
                continue;
            }
            int separatorIndex = trimmedLine.indexOf('=');
            if (separatorIndex <= 0) {
                continue;
            }
            String key = trimmedLine.substring(0, separatorIndex).trim();
            if (!key.isBlank() && !keyIndexMap.containsKey(key)) {
                keyIndexMap.put(key, lineIndex);
            }
        }
        return keyIndexMap;
    }

    /**
     * Normalize catalog lines into a single string, preserving original order.
     *
     * @param catalogLines list of catalog lines
     * @return normalized catalog content string
     */
    private String normalizeCatalogContent(List<String> catalogLines) {
        if (catalogLines == null || catalogLines.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int lineIndex = 0; lineIndex < catalogLines.size(); lineIndex++) {
            if (lineIndex > 0) {
                builder.append("\n");
            }
            builder.append(catalogLines.get(lineIndex));
        }
        return builder.toString();
    }

    /**
     * Resolve a catalog property value from a property spec.
     *
     * @param propertySpec property spec map from service.json
     * @param params root params map (for templating context)
     * @param effectiveValues merged Helm values (for helm-derived properties)
     * @param ambariActionClient Ambari client for config lookups
     * @param clusterName Ambari cluster name
     * @param templateEnv template environment map
     * @return resolved string value or null if not resolved
     */
    private String resolveCatalogPropertyValue(Map<String, Object> propertySpec,
                                               Map<String, Object> params,
                                               Map<String, Object> effectiveValues,
                                               AmbariActionClient ambariActionClient,
                                               String clusterName,
                                               Map<String, Object> templateEnv) {
        String directValue = resolveStringValue(propertySpec.get("value"), null);
        if (directValue != null) {
            return directValue;
        }

        // valueFromContext: resolve a "<capability>.<field>" key from the selected platform
        // context's resolved fields (e.g. an EXTERNAL CDP context's hive.servicePrincipal).
        // Preferred over valueFromAmbari so a property can declare BOTH — the context value wins
        // for external contexts, and it falls through to valueFromAmbari/valueFromTemplate for the
        // MANAGED (Ambari-hosting) path, leaving managed deploys unchanged.
        String contextLookup = resolveStringValue(propertySpec.get("valueFromContext"), null);
        if (contextLookup != null && !contextLookup.isBlank()) {
            try {
                Map<String, String> ctxResolved = contextResolvedFieldsForDeploy(
                        params == null ? null : params.get("formValues"), ambariActionClient, clusterName);
                String ctxValue = (ctxResolved == null) ? null : ctxResolved.get(contextLookup);
                if (ctxValue != null && !ctxValue.isBlank()) {
                    return ctxValue;
                }
                LOG.info("Catalog enrichment valueFromContext '{}' not set on the selected context; falling back to other sources.", contextLookup);
            } catch (Exception ex) {
                LOG.warn("Failed to resolve valueFromContext {} for catalog enrichment: {}", contextLookup, ex.toString());
            }
        }

        String ambariLookup = resolveStringValue(propertySpec.get("valueFromAmbari"), null);
        if (ambariLookup != null && !ambariLookup.isBlank()) {
            if (ambariActionClient == null || clusterName == null || clusterName.isBlank()) {
                LOG.warn("Ambari client unavailable; cannot resolve valueFromAmbari {}", ambariLookup);
            } else {
                String[] parts = ambariLookup.split("/", 2);
                if (parts.length == 2) {
                    try {
                        String ambariValue = ambariActionClient.getDesiredConfigProperty(clusterName, parts[0], parts[1]);
                        return resolveStringValue(ambariValue, null);
                    } catch (Exception ex) {
                        LOG.warn("Failed to resolve Ambari property {} for catalog enrichment: {}", ambariLookup, ex.toString());
                    }
                } else {
                    LOG.warn("Invalid valueFromAmbari format '{}'; expected 'configType/property'.", ambariLookup);
                }
            }
        }

        Object valueFromTemplateObj = propertySpec.get("valueFromTemplate");
        if (valueFromTemplateObj != null) {
            if (valueFromTemplateObj instanceof String) {
                String templateValue = (String) valueFromTemplateObj;
                return ConfigResolutionService.renderTemplate(templateValue, templateEnv);
            }
            if (valueFromTemplateObj instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> templateMap = (Map<String, Object>) valueFromTemplateObj;
                String templateValue = resolveStringValue(templateMap.get("template"), null);
                if (templateValue == null || templateValue.isBlank()) {
                    LOG.warn("valueFromTemplate object missing 'template' field; skipping.");
                    return null;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> templateProperties = (Map<String, Object>) templateMap.get("properties");
                return resolveCatalogTemplateValueWithProperties(
                        templateValue,
                        templateProperties,
                        params,
                        effectiveValues,
                        ambariActionClient,
                        clusterName,
                        templateEnv
                );
            }
            LOG.warn("Unsupported valueFromTemplate type {}; expected string or object.", valueFromTemplateObj.getClass().getName());
        }

        return null;
    }

    /**
     * Resolve a template value that includes an optional properties block.
     *
     * @param templateValue template string
     * @param templateProperties map of property definitions (may be null)
     * @param params root params map
     * @param effectiveValues merged Helm values
     * @param ambariActionClient Ambari client for config lookups
     * @param clusterName Ambari cluster name
     * @param templateEnv base template environment map
     * @return rendered template string
     */
    private String resolveCatalogTemplateValueWithProperties(String templateValue,
                                                             Map<String, Object> templateProperties,
                                                             Map<String, Object> params,
                                                             Map<String, Object> effectiveValues,
                                                             AmbariActionClient ambariActionClient,
                                                             String clusterName,
                                                             Map<String, Object> templateEnv) {
        if (templateProperties == null || templateProperties.isEmpty()) {
            return ConfigResolutionService.renderTemplate(templateValue, templateEnv);
        }
        Map<String, Object> resolvedPropertyValues = new LinkedHashMap<>();
        for (Map.Entry<String, Object> propertyEntry : templateProperties.entrySet()) {
            String propertyName = propertyEntry.getKey();
            Object propertyDefObj = propertyEntry.getValue();
            if (!(propertyDefObj instanceof Map<?, ?>)) {
                resolvedPropertyValues.put(propertyName, propertyDefObj);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> propertyDef = (Map<String, Object>) propertyDefObj;
            Object resolvedValue = this.configResolutionService.resolveInnerPropertyValue(
                    propertyName,
                    propertyDef,
                    params,
                    effectiveValues,
                    ambariActionClient,
                    clusterName,
                    templateEnv
            );
            if (resolvedValue != null) {
                resolvedPropertyValues.put(propertyName, resolvedValue);
            }
        }
        Map<String, Object> mergedTemplateEnv = new LinkedHashMap<>(templateEnv);
        mergedTemplateEnv.putAll(resolvedPropertyValues);
        return ConfigResolutionService.renderTemplate(templateValue, mergedTemplateEnv);
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
     * Submit a command to regenerate Kerberos keytabs for a deployed release.
     * This replays the pre-provisioned keytab creation steps without re-deploying the chart.
     *
     * @param namespace release namespace
     * @param releaseName release name
     * @param callerHeaders HTTP headers from the caller (for Ambari auth)
     * @param baseUri base URI for the view (used to reach Ambari API)
     * @return command id for tracking
     */
    public String submitReleaseKeytabRegeneration(String namespace,
                                                  String releaseName,
                                                  MultivaluedMap<String, String> callerHeaders,
                                                  URI baseUri) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(releaseName, "releaseName");
        Objects.requireNonNull(baseUri, "baseUri");

        K8sReleaseEntity releaseMetadata = new ReleaseMetadataService(ctx).find(namespace, releaseName);
        if (releaseMetadata == null || releaseMetadata.getServiceKey() == null || releaseMetadata.getServiceKey().isBlank()) {
            throw new IllegalArgumentException("Release " + namespace + "/" + releaseName + " is not managed by the UI.");
        }

        String kerberosInjectionMode = resolveKerberosInjectionModeFromSettings();
        if (!KERBEROS_INJECTION_MODE_PRE_PROVISIONED.equalsIgnoreCase(kerberosInjectionMode)) {
            throw new IllegalStateException("Kerberos regeneration is only supported in PRE_PROVISIONED mode (current: " + kerberosInjectionMode + ").");
        }

        // Resolve Ambari cluster and initialize client for Kerberos checks.
        Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(callerHeaders);
        String clusterName;
        try {
            // Resolve Ambari cluster name from the view base URI and caller headers.
            clusterName = commandUtils.resolveClusterName(baseUri.toString(), authHeaders);
        } catch (Exception ex) {
            LOG.warn("Failed to resolve Ambari cluster name for keytab regeneration on {}/{}: {}",
                    namespace, releaseName, ex.toString());
            throw new IllegalStateException("Unable to resolve Ambari cluster name for keytab regeneration.", ex);
        }
        AmbariActionClient ambariActionClient = new AmbariActionClient(ctx, baseUri.toString(), clusterName, authHeaders);

        boolean kerberosClusterEnabled = false;
        try {
            String securityEnabledValue = ambariActionClient.getDesiredConfigProperty(clusterName, "cluster-env", "security_enabled");
            kerberosClusterEnabled = "true".equalsIgnoreCase(securityEnabledValue);
        } catch (Exception ex) {
            LOG.warn("Failed to determine Kerberos state for cluster {}: {}", clusterName, ex.toString());
        }
        if (!kerberosClusterEnabled) {
            throw new IllegalStateException("Kerberos is disabled on cluster " + clusterName + "; keytab regeneration skipped.");
        }

        StackDefinitionService stackDefinitionService = new StackDefinitionService(ctx);
        StackServiceDef serviceDefinition = stackDefinitionService.getServiceDefinition(releaseMetadata.getServiceKey());
        if (serviceDefinition == null || serviceDefinition.kerberos == null || serviceDefinition.kerberos.isEmpty()) {
            throw new IllegalArgumentException("Service definition for " + releaseMetadata.getServiceKey() + " has no Kerberos entries.");
        }

        // Resolve Kerberos realm from Ambari.
        String kerberosRealm = resolveKerberosRealmFromAmbari(ambariActionClient, clusterName);
        if (kerberosRealm == null || kerberosRealm.isBlank()) {
            throw new IllegalStateException("Kerberos realm is empty; cannot regenerate keytabs.");
        }

        final String commandId = UUID.randomUUID().toString();
        final String now = Instant.now().toString();

        CommandEntity rootCommand = new CommandEntity();
        rootCommand.setId(commandId);
        rootCommand.setViewInstance(ctx.getInstanceName());
        rootCommand.setType(CommandType.REGENERATE_KEYTABS.name());
        rootCommand.setTitle("Regenerate keytabs for " + releaseName);

        CommandStatusEntity rootStatus = new CommandStatusEntity();
        rootStatus.setId(commandId + "-status");
        rootStatus.setViewInstance(ctx.getInstanceName());
        rootStatus.setCreatedBy(ctx.getUsername());
        rootStatus.setState(CommandState.PENDING.name());
        rootStatus.setCreatedAt(now);
        rootStatus.setUpdatedAt(now);
        rootStatus.setAttempt(0);
        rootCommand.setCommandStatusId(rootStatus.getId());

        Map<String, Object> rootParams = new LinkedHashMap<>();
        rootParams.put("releaseName", releaseName);
        rootParams.put("namespace", namespace);
        rootParams.put("serviceKey", releaseMetadata.getServiceKey());
        rootParams.put("kerberosInjectionMode", kerberosInjectionMode);
        rootParams.put("_cluster", clusterName);
        rootParams.put("_baseUri", baseUri.toString());
        rootParams.put("_callerHeaders", AmbariActionClient.headersToPersistableMap(callerHeaders));

        List<String> childCommandIds = new ArrayList<>();

        Map<String, Object> kerberosSpecMap = buildKerberosSpecMapFromServiceDefinition(serviceDefinition.kerberos);
        List<Map<String, Object>> kerberosEntries = normalizeKerberosEntries(kerberosSpecMap);
        if (kerberosEntries.isEmpty()) {
            throw new IllegalStateException("No Kerberos entries found for service " + releaseMetadata.getServiceKey());
        }

        int kerberosEntryIndex = 0;
        boolean keytabStepsAdded = false;
        // Create keytab generation steps for each Kerberos entry.
        for (Map<String, Object> kerberosEntry : kerberosEntries) {
            kerberosEntryIndex++;
            if (!isKerberosEntryEnabled(kerberosEntry.get("enabled"))) {
                LOG.info("Skipping disabled Kerberos entry {}", kerberosEntry.getOrDefault("key", kerberosEntryIndex));
                continue;
            }
            String entryKey = resolveStringValue(kerberosEntry.get("key"), "entry-" + kerberosEntryIndex);
            String serviceName = resolveStringValue(kerberosEntry.get("serviceName"), releaseName);
            String principalTemplate = resolveStringValue(kerberosEntry.get("principalTemplate"), "{{service}}-{{namespace}}@{{realm}}");

            String principalFqdn = renderKerberosPrincipalTemplate(
                    principalTemplate,
                    serviceName,
                    namespace,
                    releaseName,
                    kerberosRealm
            );
            if (principalFqdn == null || principalFqdn.isBlank()) {
                LOG.warn("Kerberos principal resolved to empty for entry {}; skipping.", entryKey);
                continue;
            }
            validateKerberosPrincipal(principalFqdn);

            String secretName = resolveKerberosEntrySecretName(
                    kerberosEntry,
                    serviceName,
                    namespace,
                    releaseName,
                    kerberosRealm
            );
            String keyNameInSecret = resolveStringValue(kerberosEntry.get("keyNameInSecret"), "service.keytab");

            LOG.info("Regenerating keytab for entry {}: principal={}, secret={}, keyName={}",
                    entryKey, principalFqdn, secretName, keyNameInSecret);

            appendPreProvisionedKeytabSteps(
                    rootCommand,
                    childCommandIds,
                    rootParams,
                    principalFqdn,
                    namespace,
                    secretName,
                    keyNameInSecret,
                    entryKey
            );
            keytabStepsAdded = true;
        }

        if (!keytabStepsAdded) {
            throw new IllegalStateException("No keytab steps were scheduled for release " + releaseName);
        }

        rootCommand.setParamsJson(gson.toJson(rootParams));
        rootCommand.setChildListJson(gson.toJson(childCommandIds));

        store(rootStatus);
        store(rootCommand);

        scheduleNow(commandId);
        return commandId;
    }

    /**
     * Submit a command to reapply the Ranger repository configuration for a release.
     * This replays the Ranger repository creation step without a Helm upgrade.
     *
     * @param namespace release namespace
     * @param releaseName release name
     * @param callerHeaders HTTP headers from the caller (for Ambari auth)
     * @param baseUri base URI for the view (used to reach Ambari API)
     * @return command id for tracking
     */
    public String submitReleaseRangerRepositoryReapply(String namespace,
                                                       String releaseName,
                                                       MultivaluedMap<String, String> callerHeaders,
                                                       URI baseUri) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(releaseName, "releaseName");
        Objects.requireNonNull(baseUri, "baseUri");

        ReleaseMetadataService metadataService = new ReleaseMetadataService(ctx);
        K8sReleaseEntity releaseMetadata = metadataService.find(namespace, releaseName);
        if (releaseMetadata == null || releaseMetadata.getServiceKey() == null || releaseMetadata.getServiceKey().isBlank()) {
            throw new IllegalArgumentException("Release " + namespace + "/" + releaseName + " is not managed by the UI.");
        }

        StackDefinitionService stackDefinitionService = new StackDefinitionService(ctx);
        StackServiceDef serviceDefinition = stackDefinitionService.getServiceDefinition(releaseMetadata.getServiceKey());
        if (serviceDefinition == null || serviceDefinition.ranger == null || serviceDefinition.ranger.isEmpty()) {
            throw new IllegalArgumentException("Service definition for " + releaseMetadata.getServiceKey() + " has no Ranger spec.");
        }

        Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(callerHeaders);
        String clusterName;
        try {
            // Resolve Ambari cluster name from the view base URI and caller headers.
            clusterName = commandUtils.resolveClusterName(baseUri.toString(), authHeaders);
        } catch (Exception ex) {
            LOG.warn("Failed to resolve Ambari cluster name for Ranger reapply on {}/{}: {}",
                    namespace, releaseName, ex.toString());
            throw new IllegalStateException("Unable to resolve Ambari cluster name for Ranger reapply.", ex);
        }
        AmbariActionClient ambariActionClient = new AmbariActionClient(ctx, baseUri.toString(), clusterName, authHeaders);

        Map<String, Object> releaseValues = kubernetesService.getHelmReleaseValues(namespace, releaseName);
        Map<String, Object> effectiveValues = releaseValues != null ? releaseValues : Collections.emptyMap();

        // Try to merge chart defaults for a richer templating context.
        if (releaseMetadata.getChartRef() != null && releaseMetadata.getRepoId() != null) {
            try {
                Map<String, Object> defaultValues = helmService.getRepositoryService()
                        .showValuesAsMap(releaseMetadata.getChartRef(), releaseMetadata.getRepoId(), releaseMetadata.getVersion());
                effectiveValues = configResolutionService.deepMerge(
                        defaultValues != null ? defaultValues : Collections.emptyMap(),
                        effectiveValues
                );
            } catch (Exception ex) {
                LOG.warn("Failed to merge chart defaults for Ranger reapply on {}/{}: {}", namespace, releaseName, ex.toString());
            }
        }

        HelmDeployRequest rangerRequest = new HelmDeployRequest();
        rangerRequest.setReleaseName(releaseName);
        rangerRequest.setNamespace(namespace);
        rangerRequest.setServiceKey(releaseMetadata.getServiceKey());
        rangerRequest.setChart(releaseMetadata.getChartRef());
        rangerRequest.setRanger(serviceDefinition.ranger);

        final String commandId = UUID.randomUUID().toString();
        final String now = Instant.now().toString();

        CommandEntity rootCommand = new CommandEntity();
        rootCommand.setId(commandId);
        rootCommand.setViewInstance(ctx.getInstanceName());
        rootCommand.setType(CommandType.RANGER_REPOSITORY_REAPPLY.name());
        rootCommand.setTitle("Reapply Ranger repository for " + releaseName);

        CommandStatusEntity rootStatus = new CommandStatusEntity();
        rootStatus.setId(commandId + "-status");
        rootStatus.setViewInstance(ctx.getInstanceName());
        rootStatus.setCreatedBy(ctx.getUsername());
        rootStatus.setState(CommandState.PENDING.name());
        rootStatus.setCreatedAt(now);
        rootStatus.setUpdatedAt(now);
        rootStatus.setAttempt(0);
        rootCommand.setCommandStatusId(rootStatus.getId());

        Map<String, Object> rootParams = new LinkedHashMap<>();
        rootParams.put("chart", releaseMetadata.getChartRef());
        rootParams.put("releaseName", releaseName);
        rootParams.put("namespace", namespace);
        rootParams.put("serviceKey", releaseMetadata.getServiceKey());
        rootParams.put("repoId", releaseMetadata.getRepoId());
        rootParams.put("version", releaseMetadata.getVersion());
        rootParams.put("_cluster", clusterName);
        rootParams.put("_baseUri", baseUri.toString());
        rootParams.put("_callerHeaders", AmbariActionClient.headersToPersistableMap(callerHeaders));

        AmbariAliasResolver ambariAliasResolver = new AmbariAliasResolver(ctx);
        materializeRangerResources(
                rangerRequest,
                rootParams,
                effectiveValues,
                ambariActionClient,
                clusterName,
                ambariAliasResolver
        );

        List<String> childCommandIds = new ArrayList<>();
        // Plan the Ranger repository creation step with current values.
        planRangerRepositoryCreation(
                rootCommand,
                childCommandIds,
                rangerRequest,
                rootParams,
                effectiveValues,
                ambariActionClient,
                clusterName
        );

        if (childCommandIds.isEmpty()) {
            throw new IllegalStateException("No Ranger repository steps were scheduled for release " + releaseName);
        }

        rootCommand.setParamsJson(gson.toJson(rootParams));
        rootCommand.setChildListJson(gson.toJson(childCommandIds));

        store(rootStatus);
        store(rootCommand);

        scheduleNow(commandId);
        return commandId;
    }

    /**
     * Replay the OpenMetadata→Ranger TagSync source registration for a deployed release.
     * Reads the {@code ranger-tagsync-source} entries from the service.json {@code ranger}
     * block and queues one {@link CommandType#OM_RANGER_TAGSYNC_REGISTER} child per entry —
     * the same body the deploy-time step runs. Cluster + Ambari headers come from the
     * caller (reapply runs as the operator hitting the button, not as the deploy actor).
     *
     * @return command id for polling
     */
    public String submitReleaseOmRangerTagSyncReapply(String namespace,
                                                      String releaseName,
                                                      MultivaluedMap<String, String> callerHeaders,
                                                      URI baseUri) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(releaseName, "releaseName");
        Objects.requireNonNull(baseUri, "baseUri");

        ReleaseMetadataService metadataService = new ReleaseMetadataService(ctx);
        K8sReleaseEntity releaseMetadata = metadataService.find(namespace, releaseName);
        if (releaseMetadata == null || releaseMetadata.getServiceKey() == null
                || releaseMetadata.getServiceKey().isBlank()) {
            throw new IllegalArgumentException("Release " + namespace + "/" + releaseName + " is not managed by the UI.");
        }

        StackDefinitionService stackDefinitionService = new StackDefinitionService(ctx);
        StackServiceDef serviceDefinition = stackDefinitionService.getServiceDefinition(releaseMetadata.getServiceKey());
        if (serviceDefinition == null || serviceDefinition.ranger == null || serviceDefinition.ranger.isEmpty()) {
            throw new IllegalArgumentException("Service definition for " + releaseMetadata.getServiceKey()
                    + " has no Ranger spec — nothing to replay.");
        }

        Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(callerHeaders);
        String clusterName;
        try {
            clusterName = commandUtils.resolveClusterName(baseUri.toString(), authHeaders);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to resolve Ambari cluster name for OM TagSync reapply.", ex);
        }

        final String commandId = UUID.randomUUID().toString();
        final String now = Instant.now().toString();

        CommandEntity rootCommand = new CommandEntity();
        rootCommand.setId(commandId);
        rootCommand.setViewInstance(ctx.getInstanceName());
        rootCommand.setType(CommandType.OM_RANGER_TAGSYNC_REAPPLY.name());
        rootCommand.setTitle("Re-register OM→Ranger TagSync source for " + releaseName);

        CommandStatusEntity rootStatus = new CommandStatusEntity();
        rootStatus.setId(commandId + "-status");
        rootStatus.setViewInstance(ctx.getInstanceName());
        rootStatus.setCreatedBy(ctx.getUsername());
        rootStatus.setState(CommandState.PENDING.name());
        rootStatus.setCreatedAt(now);
        rootStatus.setUpdatedAt(now);
        rootStatus.setAttempt(0);
        rootCommand.setCommandStatusId(rootStatus.getId());

        Map<String, Object> rootParams = new LinkedHashMap<>();
        rootParams.put("chart", releaseMetadata.getChartRef());
        rootParams.put("releaseName", releaseName);
        rootParams.put("namespace", namespace);
        rootParams.put("serviceKey", releaseMetadata.getServiceKey());
        rootParams.put("repoId", releaseMetadata.getRepoId());
        rootParams.put("version", releaseMetadata.getVersion());
        rootParams.put("_cluster", clusterName);
        rootParams.put("_baseUri", baseUri.toString());
        rootParams.put("_callerHeaders", AmbariActionClient.headersToPersistableMap(callerHeaders));

        // Plumb ranger-tagsync-settings into params so the runtime handler reads
        // chart-wide TagSync wiring (mirrors the deploy-time planRangerTagSyncSources).
        Map<String, Object> tagSyncSettingsSpec = serviceDefinition.ranger.get("ranger-tagsync-settings");
        if (tagSyncSettingsSpec == null || tagSyncSettingsSpec.isEmpty()) {
            throw new IllegalStateException(
                    "Service definition for " + releaseMetadata.getServiceKey()
                            + " has no ranger-tagsync-settings block — nothing to replay.");
        }
        rootParams.put("_tagSyncSettings", tagSyncSettingsSpec);

        // Initialize root with an empty child list before the plan-factory appends.
        rootCommand.setParamsJson(gson.toJson(rootParams));
        rootCommand.setChildListJson(gson.toJson(new ArrayList<String>()));
        store(rootStatus);
        store(rootCommand);

        // createRangerTagSyncRegister self-manages rootCommand.childListJson + re-stores
        // the root, so we don't need to do it again afterwards.
        this.commandPlanFactory.createRangerTagSyncRegister(
                rootCommand, serviceDefinition.ranger, rootParams);

        // Refresh from datastore to read what the plan-factory wrote, so we can
        // detect the no-children case without confusing the operator.
        List<String> children;
        try {
            children = gson.fromJson(rootCommand.getChildListJson(),
                    new TypeToken<ArrayList<String>>(){}.getType());
            if (children == null) children = Collections.emptyList();
        } catch (Exception e) {
            children = Collections.emptyList();
        }
        if (children.isEmpty()) {
            throw new IllegalStateException(
                    "No ranger-tagsync-source entries declared for " + releaseMetadata.getServiceKey()
                            + " — nothing to replay.");
        }

        scheduleNow(commandId);
        return commandId;
    }

    /**
     * Replay the OpenMetadata Atlas-federation registration for a deployed release.
     * Queues a single {@link CommandType#OM_ATLAS_FEDERATION_REGISTER} child whose
     * body reads the current release values, mints a fresh Unlimited JWT, and
     * upserts the four OM REST entities. The pre-flight check rejects the request
     * early if the release isn't OPENMETADATA or has {@code atlasFederation.enabled=false}.
     *
     * @return command id for polling
     */
    public String submitReleaseOmAtlasFederationReapply(String namespace,
                                                        String releaseName,
                                                        MultivaluedMap<String, String> callerHeaders,
                                                        URI baseUri) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(releaseName, "releaseName");
        Objects.requireNonNull(baseUri, "baseUri");

        ReleaseMetadataService metadataService = new ReleaseMetadataService(ctx);
        K8sReleaseEntity releaseMetadata = metadataService.find(namespace, releaseName);
        if (releaseMetadata == null || releaseMetadata.getServiceKey() == null
                || releaseMetadata.getServiceKey().isBlank()) {
            throw new IllegalArgumentException("Release " + namespace + "/" + releaseName + " is not managed by the UI.");
        }

        // The values check (atlasFederation.enabled) is intentionally deferred to
        // the step body — the submit path stays uniform, the body raises a clear
        // IllegalStateException when the toggle was flipped off after deploy.

        final String commandId = UUID.randomUUID().toString();
        final String now = Instant.now().toString();

        CommandEntity rootCommand = new CommandEntity();
        rootCommand.setId(commandId);
        rootCommand.setViewInstance(ctx.getInstanceName());
        rootCommand.setType(CommandType.OM_ATLAS_FEDERATION_REAPPLY.name());
        rootCommand.setTitle("Re-register OM Atlas federation for " + releaseName);

        CommandStatusEntity rootStatus = new CommandStatusEntity();
        rootStatus.setId(commandId + "-status");
        rootStatus.setViewInstance(ctx.getInstanceName());
        rootStatus.setCreatedBy(ctx.getUsername());
        rootStatus.setState(CommandState.PENDING.name());
        rootStatus.setCreatedAt(now);
        rootStatus.setUpdatedAt(now);
        rootStatus.setAttempt(0);
        rootCommand.setCommandStatusId(rootStatus.getId());

        // Resolve Ambari context (cluster name + AmbariActionClient) so the dispatcher
        // can discover Atlas auth/acl modes and the per-step bodies can write configs.
        // Same shape as the OM_RANGER_TAGSYNC_REAPPLY submit path.
        Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(callerHeaders);
        String clusterName = null;
        AmbariActionClient ambariActionClient = null;
        try {
            clusterName = commandUtils.resolveClusterName(baseUri.toString(), authHeaders);
            ambariActionClient = new AmbariActionClient(ctx, baseUri.toString(), clusterName, authHeaders);
        } catch (Exception ex) {
            LOG.warn("Atlas federation reapply: could not resolve Ambari cluster ({}). "
                    + "Falling back to OM-only path (no Atlas-side provisioning will run).", ex.toString());
        }

        Map<String, Object> rootParams = new LinkedHashMap<>();
        rootParams.put("chart", releaseMetadata.getChartRef());
        rootParams.put("releaseName", releaseName);
        rootParams.put("namespace", namespace);
        rootParams.put("serviceKey", releaseMetadata.getServiceKey());
        rootParams.put("repoId", releaseMetadata.getRepoId());
        rootParams.put("version", releaseMetadata.getVersion());
        rootParams.put("_callerHeaders", AmbariActionClient.headersToPersistableMap(callerHeaders));
        if (clusterName != null) rootParams.put("_cluster", clusterName);
        rootParams.put("_baseUri", baseUri.toString());

        // Root needs an empty childListJson before the plan-factory appends to it.
        rootCommand.setParamsJson(gson.toJson(rootParams));
        rootCommand.setChildListJson(gson.toJson(new ArrayList<String>()));
        store(rootStatus);
        store(rootCommand);

        // Re-run the same dispatcher submitDeploy uses, so the reapply chain mirrors
        // what would happen on a fresh install: ATLAS_USER_PROVISION_OM /
        // ATLAS_SIMPLE_AUTHZ_GRANT_OM / RANGER_POLICY_CREATE_ATLAS_OM_READ (whichever
        // apply for the discovered (auth, acl) modes) BEFORE OM_ATLAS_FEDERATION_REGISTER.
        try {
            HelmDeployRequest reqForDispatch = new HelmDeployRequest();
            reqForDispatch.setNamespace(namespace);
            reqForDispatch.setReleaseName(releaseName);
            queueAtlasFederationProvisioningSteps(rootCommand, reqForDispatch, rootParams,
                    clusterName, ambariActionClient);
        } catch (Exception ex) {
            LOG.warn("Atlas federation reapply: provisioning dispatch failed ({}). "
                    + "Continuing with bare OM_ATLAS_FEDERATION_REGISTER step.", ex.toString());
        }

        // createOmAtlasFederationRegister self-manages rootCommand.childListJson +
        // re-stores the root, so we don't need to do it again after this call.
        this.commandPlanFactory.createOmAtlasFederationRegister(rootCommand, rootParams);

        scheduleNow(commandId);
        return commandId;
    }

    /**
     * Re-issue TLS leaf certificates for every host of a running release, regardless of which
     * provisioning system issued the existing Secret. Dispatches per source:
     * <ul>
     *   <li>{@code k8s-view-self-signed} — re-runs INGRESS_TLS_SELFSIGN with the labels we
     *       saved on the original Secret (ca-mode + optional ca-name + release name). The
     *       Secret is overwritten in place, nginx-ingress picks up the new cert on its
     *       Secret watcher within seconds.</li>
     *   <li>{@code cert-manager} — annotates the cert-manager.io Certificate CR with
     *       {@code cert-manager.io/issue-temporary-certificate=true} and bumps
     *       {@code renewBefore} just enough to trigger immediate re-issuance.</li>
     *   <li>{@code external-secrets} — annotates the ExternalSecret with the standard
     *       force-sync annotation so ESO re-pulls from Vault on the next reconcile.</li>
     *   <li>{@code external} — skipped with a clear log entry; the operator owns it.</li>
     * </ul>
     * Per-host steps run in parallel where the controllers allow it (cert-manager / ESO are
     * eventually-consistent CR patches that don't queue).
     *
     * @return command id for status polling
     */
    public String submitReleaseTlsRenewal(String namespace,
                                          String releaseName,
                                          MultivaluedMap<String, String> callerHeaders,
                                          URI baseUri) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(releaseName, "releaseName");

        ReleaseTlsService tlsSvc = new ReleaseTlsService(ctx, kubernetesService);
        List<Map<String, Object>> entries = tlsSvc.describe(namespace, releaseName);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("No Ingress objects found for " + namespace + "/" + releaseName);
        }

        final String commandId = UUID.randomUUID().toString();
        final String now = Instant.now().toString();

        CommandEntity rootCommand = new CommandEntity();
        rootCommand.setId(commandId);
        rootCommand.setViewInstance(ctx.getInstanceName());
        rootCommand.setType(CommandType.TLS_RENEW.name());
        rootCommand.setTitle("Renew TLS cert(s) for " + releaseName);

        CommandStatusEntity rootStatus = new CommandStatusEntity();
        rootStatus.setId(commandId + "-status");
        rootStatus.setViewInstance(ctx.getInstanceName());
        rootStatus.setCreatedBy(ctx.getUsername());
        rootStatus.setState(CommandState.PENDING.name());
        rootStatus.setCreatedAt(now);
        rootStatus.setUpdatedAt(now);
        rootStatus.setAttempt(0);
        rootCommand.setCommandStatusId(rootStatus.getId());

        Map<String, Object> rootParams = new LinkedHashMap<>();
        rootParams.put("namespace", namespace);
        rootParams.put("releaseName", releaseName);
        rootParams.put("_baseUri", baseUri == null ? "" : baseUri.toString());
        rootParams.put("_callerHeaders", AmbariActionClient.headersToPersistableMap(callerHeaders));
        rootCommand.setParamsJson(gson.toJson(rootParams));

        List<String> childIds = new ArrayList<>();
        int planned = 0, skippedExternal = 0;
        for (Map<String, Object> e : entries) {
            String source = String.valueOf(e.getOrDefault("source", "external"));
            String secretName = (String) e.get("secretName");
            List<?> hosts = (List<?>) e.getOrDefault("hosts", List.of());
            String primaryHost = hosts.isEmpty() ? "" : String.valueOf(hosts.get(0));
            if (secretName == null || secretName.isBlank() || primaryHost.isBlank()) continue;

            switch (source) {
                case "k8s-view-self-signed" -> {
                    Map<String, String> labels = readSecretLabels(namespace, secretName);
                    String caMode = labels.getOrDefault("ambari.clemlab.com/ca-mode", "ambariInternal");
                    String caName = labels.get("ambari.clemlab.com/ca-name");
                    List<String> extraSans = new ArrayList<>();
                    for (int i = 1; i < hosts.size(); i++) extraSans.add(String.valueOf(hosts.get(i)));
                    String stepId = this.commandPlanFactory.appendIngressTlsSelfSignStep(
                            rootCommand, releaseName, namespace, secretName, primaryHost,
                            extraSans, caMode, caName, null);
                    childIds.add(stepId);
                    planned++;
                }
                case "cert-manager" -> {
                    String stepId = appendCertManagerRenewStep(rootCommand, namespace, secretName, primaryHost);
                    childIds.add(stepId);
                    planned++;
                }
                case "external-secrets" -> {
                    String stepId = appendExternalSecretRenewStep(rootCommand, namespace, secretName);
                    childIds.add(stepId);
                    planned++;
                }
                default -> skippedExternal++;
            }
        }

        if (planned == 0) {
            throw new IllegalStateException("Nothing to renew for " + namespace + "/" + releaseName
                    + (skippedExternal > 0 ? " (all " + skippedExternal + " TLS Secret(s) are operator-managed)" : ""));
        }

        rootCommand.setChildListJson(gson.toJson(childIds));
        store(rootStatus);
        store(rootCommand);
        LOG.info("TLS_RENEW: planned {} step(s), skipped {} external secret(s) for {}/{}",
                planned, skippedExternal, namespace, releaseName);
        scheduleNow(commandId);
        return commandId;
    }

    /**
     * Plan an INGRESS_TLS_CERTMANAGER step under the given root command. Used by the
     * install pipeline (operation=create) — the Certificate CR is applied pre-deploy so
     * cert-manager already has the Secret ready by the time the chart references it.
     */
    private String appendIngressTlsCertManagerStep(CommandEntity root, String namespace,
                                                   String certName, String secretName,
                                                   String issuerName, String issuerKind,
                                                   List<String> dnsNames, Integer durationHours) {
        String stepId = root.getId() + "-tls-cm-" + UUID.randomUUID();
        CommandEntity step = new CommandEntity();
        step.setId(stepId);
        step.setViewInstance(ctx.getInstanceName());
        step.setType(CommandType.INGRESS_TLS_CERTMANAGER.name());
        step.setTitle("cert-manager: create Certificate " + secretName);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operation", "create");
        params.put("namespace", namespace);
        params.put("certName", certName);
        params.put("secretName", secretName);
        params.put("issuerName", issuerName);
        params.put("issuerKind", issuerKind == null || issuerKind.isBlank() ? "ClusterIssuer" : issuerKind);
        params.put("dnsNames", dnsNames);
        if (durationHours != null) params.put("durationHours", durationHours);
        step.setParamsJson(gson.toJson(params));
        CommandStatusEntity st = new CommandStatusEntity();
        st.setId(stepId + "-status");
        st.setViewInstance(ctx.getInstanceName());
        st.setCreatedBy(ctx.getUsername());
        st.setState(CommandState.PENDING.name());
        String now = Instant.now().toString();
        st.setCreatedAt(now);
        st.setUpdatedAt(now);
        st.setAttempt(0);
        step.setCommandStatusId(st.getId());
        store(st);
        store(step);
        return stepId;
    }

    /**
     * Plan an INGRESS_TLS_EXTERNAL_SECRET step under the given root command. Used by the
     * install pipeline — the ExternalSecret CR is applied pre-deploy so ESO has already
     * synced the Secret from the upstream store by the time the chart references it.
     */
    private String appendIngressTlsExternalSecretStep(CommandEntity root, String namespace,
                                                       String esName, String secretName,
                                                       String storeName, String storeKind,
                                                       String remoteKey, String refreshInterval) {
        String stepId = root.getId() + "-tls-es-" + UUID.randomUUID();
        CommandEntity step = new CommandEntity();
        step.setId(stepId);
        step.setViewInstance(ctx.getInstanceName());
        step.setType(CommandType.INGRESS_TLS_EXTERNAL_SECRET.name());
        step.setTitle("external-secrets: create ExternalSecret " + secretName);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operation", "create");
        params.put("namespace", namespace);
        params.put("externalSecretName", esName);
        params.put("secretName", secretName);
        params.put("secretStoreName", storeName);
        params.put("secretStoreKind", storeKind == null || storeKind.isBlank() ? "ClusterSecretStore" : storeKind);
        params.put("remoteKey", remoteKey);
        params.put("refreshInterval", refreshInterval == null || refreshInterval.isBlank() ? "1h" : refreshInterval);
        step.setParamsJson(gson.toJson(params));
        CommandStatusEntity st = new CommandStatusEntity();
        st.setId(stepId + "-status");
        st.setViewInstance(ctx.getInstanceName());
        st.setCreatedBy(ctx.getUsername());
        st.setState(CommandState.PENDING.name());
        String now = Instant.now().toString();
        st.setCreatedAt(now);
        st.setUpdatedAt(now);
        st.setAttempt(0);
        step.setCommandStatusId(st.getId());
        store(st);
        store(step);
        return stepId;
    }

    /** Reads labels of a Secret, returning empty map if missing or unreadable. */
    private Map<String, String> readSecretLabels(String namespace, String secretName) {
        try {
            var secret = kubernetesService.getSecret(namespace, secretName);
            if (secret != null && secret.getMetadata() != null && secret.getMetadata().getLabels() != null) {
                return secret.getMetadata().getLabels();
            }
        } catch (Exception ignore) {}
        return Map.of();
    }

    /**
     * Plan an INGRESS_TLS_CERTMANAGER step that simply annotates the existing Certificate CR
     * to force re-issue. The Certificate must already exist (it's created by the install path
     * when tlsMode=signedByClusterIssuer); we just patch it.
     */
    private String appendCertManagerRenewStep(CommandEntity root, String namespace, String secretName, String primaryHost) {
        String stepId = root.getId() + "-tls-cm-" + UUID.randomUUID();
        CommandEntity step = new CommandEntity();
        step.setId(stepId);
        step.setViewInstance(ctx.getInstanceName());
        step.setType(CommandType.INGRESS_TLS_CERTMANAGER.name());
        step.setTitle("cert-manager: force renew " + namespace + "/" + secretName);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("namespace", namespace);
        params.put("secretName", secretName);
        params.put("primaryHost", primaryHost);
        params.put("operation", "renew");
        step.setParamsJson(gson.toJson(params));
        CommandStatusEntity st = new CommandStatusEntity();
        st.setId(stepId + "-status");
        st.setViewInstance(ctx.getInstanceName());
        st.setCreatedBy(ctx.getUsername());
        st.setState(CommandState.PENDING.name());
        String now = Instant.now().toString();
        st.setCreatedAt(now);
        st.setUpdatedAt(now);
        st.setAttempt(0);
        step.setCommandStatusId(st.getId());
        store(st);
        store(step);
        return stepId;
    }

    /**
     * Plan an INGRESS_TLS_EXTERNAL_SECRET step that annotates the ExternalSecret to force
     * an immediate refresh from the upstream secret store.
     */
    private String appendExternalSecretRenewStep(CommandEntity root, String namespace, String secretName) {
        String stepId = root.getId() + "-tls-es-" + UUID.randomUUID();
        CommandEntity step = new CommandEntity();
        step.setId(stepId);
        step.setViewInstance(ctx.getInstanceName());
        step.setType(CommandType.INGRESS_TLS_EXTERNAL_SECRET.name());
        step.setTitle("external-secrets: force refresh " + namespace + "/" + secretName);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("namespace", namespace);
        params.put("secretName", secretName);
        params.put("operation", "refresh");
        step.setParamsJson(gson.toJson(params));
        CommandStatusEntity st = new CommandStatusEntity();
        st.setId(stepId + "-status");
        st.setViewInstance(ctx.getInstanceName());
        st.setCreatedBy(ctx.getUsername());
        st.setState(CommandState.PENDING.name());
        String now = Instant.now().toString();
        st.setCreatedAt(now);
        st.setUpdatedAt(now);
        st.setAttempt(0);
        step.setCommandStatusId(st.getId());
        store(st);
        store(step);
        return stepId;
    }

    /**
     * Entry point for in-place Helm release upgrades. Reads the deployed values from the running
     * release so they are preserved, persists a single {@code HELM_UPGRADE} step, and schedules execution.
     *
     * @param namespace      target Kubernetes namespace
     * @param releaseName    Helm release name to upgrade
     * @param targetVersion  chart version to install; required, must differ from the running one
     * @param callerHeaders  request headers used for downstream Ambari calls (cluster resolution, logs)
     * @param baseUri        Ambari base URI used to resolve the cluster context
     * @return the root command id, suitable for polling via the commands API
     */
    public String submitReleaseUpgrade(String namespace,
                                       String releaseName,
                                       String targetVersion,
                                       MultivaluedMap<String, String> callerHeaders,
                                       URI baseUri) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(releaseName, "releaseName");
        Objects.requireNonNull(baseUri, "baseUri");
        if (targetVersion == null || targetVersion.isBlank()) {
            throw new IllegalArgumentException("targetVersion is required");
        }

        ReleaseMetadataService metadataService = new ReleaseMetadataService(ctx);
        K8sReleaseEntity releaseMetadata = metadataService.find(namespace, releaseName);
        if (releaseMetadata == null) {
            throw new IllegalArgumentException("Release " + namespace + "/" + releaseName + " is not managed by the UI.");
        }
        if (releaseMetadata.getChartRef() == null || releaseMetadata.getChartRef().isBlank()) {
            throw new IllegalStateException("Release metadata missing chartRef for " + namespace + "/" + releaseName);
        }
        if (releaseMetadata.getRepoId() == null || releaseMetadata.getRepoId().isBlank()) {
            throw new IllegalStateException("Release metadata missing repoId for " + namespace + "/" + releaseName);
        }
        if (targetVersion.equals(releaseMetadata.getVersion())) {
            throw new IllegalArgumentException("Release " + namespace + "/" + releaseName + " is already at version " + targetVersion);
        }

        // Preserve operator-supplied values: read the merged values from the deployed release.
        Map<String, Object> deployedValues = kubernetesService.getHelmReleaseValues(namespace, releaseName);
        if (deployedValues == null) {
            deployedValues = Collections.emptyMap();
        }

        final String commandId = UUID.randomUUID().toString();
        final String now = Instant.now().toString();

        CommandEntity rootCommand = new CommandEntity();
        rootCommand.setId(commandId);
        rootCommand.setViewInstance(ctx.getInstanceName());
        rootCommand.setType(CommandType.K8S_MANAGER_RELEASE_DEPLOY.name());
        rootCommand.setTitle("Upgrade release " + releaseName + " to chart " + targetVersion);

        CommandStatusEntity rootStatus = new CommandStatusEntity();
        rootStatus.setId(commandId + "-status");
        rootStatus.setViewInstance(ctx.getInstanceName());
        rootStatus.setCreatedBy(ctx.getUsername());
        rootStatus.setState(CommandState.PENDING.name());
        rootStatus.setCreatedAt(now);
        rootStatus.setUpdatedAt(now);
        rootStatus.setAttempt(0);
        rootCommand.setCommandStatusId(rootStatus.getId());

        // Persist deployed values to a cache file so the HELM_UPGRADE step picks them up
        // through the standard loadValuesFromParams() flow used by HELM_DEPLOY.
        String valuesPath = kubernetesService.getConfigurationService()
                .writeCacheFile("values-" + commandId, gson.toJson(deployedValues));

        Map<String, Object> rootParams = new LinkedHashMap<>();
        rootParams.put("chart", releaseMetadata.getChartRef());
        rootParams.put("releaseName", releaseName);
        rootParams.put("namespace", namespace);
        rootParams.put("serviceKey", releaseMetadata.getServiceKey());
        rootParams.put("repoId", releaseMetadata.getRepoId());
        rootParams.put("version", targetVersion);
        rootParams.put("valuesPath", valuesPath);
        rootParams.put("_baseUri", baseUri.toString());
        if (callerHeaders != null) {
            rootParams.put("_callerHeaders", AmbariActionClient.headersToPersistableMap(callerHeaders));
        }

        // Create the single HELM_UPGRADE child step.
        String subId = UUID.randomUUID().toString();
        String childId = commandId + "-" + subId;
        CommandEntity upgradeStep = new CommandEntity();
        upgradeStep.setId(childId);
        upgradeStep.setViewInstance(ctx.getInstanceName());
        upgradeStep.setType(CommandType.HELM_UPGRADE.name());
        upgradeStep.setTitle("Helm upgrade " + releaseName + " to " + targetVersion);
        upgradeStep.setParamsJson(gson.toJson(rootParams));

        CommandStatusEntity upgradeStepStatus = new CommandStatusEntity();
        upgradeStepStatus.setId(childId + "-status");
        upgradeStepStatus.setAttempt(0);
        upgradeStepStatus.setState(CommandState.PENDING.name());
        upgradeStepStatus.setCreatedBy(ctx.getUsername());
        upgradeStepStatus.setCreatedAt(now);
        upgradeStepStatus.setUpdatedAt(now);
        upgradeStep.setCommandStatusId(upgradeStepStatus.getId());

        store(upgradeStepStatus);
        store(upgradeStep);

        List<String> childCommandIds = new ArrayList<>();
        // Ensure the repo is logged in before the upgrade so the new chart version is pullable.
        String helmRepoLoginId = this.commandPlanFactory.createHelmRepoCommand(rootCommand, releaseMetadata.getRepoId());
        childCommandIds.add(helmRepoLoginId);
        childCommandIds.add(upgradeStep.getId());

        rootCommand.setParamsJson(gson.toJson(rootParams));
        rootCommand.setChildListJson(gson.toJson(childCommandIds));

        store(rootStatus);
        store(rootCommand);

        scheduleNow(commandId);
        return commandId;
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

        // Apply value-path aliases and version compatibility checks before the backend
        // dispatch so every code path (direct helm, flux GitOps, dry-run preview) sees
        // the same rewritten values map.
        applyValueAliasesAndVersionCheck(request, version);

        // Fail-fast on a Terminating namespace. The backends create the namespace
        // anyway as part of the helm install, but they do so several steps into
        // the plan — by which point the operator has already seen Krb5 ConfigMap
        // / image-pull-Secret 403s with confusing K8s wording. Running the wait
        // here surfaces the "still terminating, try again in N seconds" message
        // immediately and avoids spinning up a half-built command tree on a
        // namespace that's about to disappear.
        kubernetesService.createNamespace(request.getNamespace());

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
     * Apply two service-definition-driven transforms to the request before backend dispatch.
     *
     * <p><b>valueAliases.</b> The shared form schema (KDPS/_shared/ingress.json) uses
     * canonical field paths like {@code ingress.ingressClassName} that match the Kubernetes
     * Ingress spec, but individual charts may consume different paths (OpenMetadata uses
     * {@code ingress.className}, Trino uses {@code ingress.className}). The service.json
     * declares <code>"valueAliases": { "ingress.ingressClassName": "ingress.className" }</code>
     * to bridge the gap. At submit time we rewrite both <code>request.values</code> and any
     * pending overrides in <code>request._ov</code> so the alias is applied uniformly.
     *
     * <p><b>requiredChartVersion.</b> If the service.json carries
     * <code>"requiredChartVersion": "&gt;=0.12.24"</code>, compare against the resolved
     * chart version. Refuse the install with a clear error if they don't match — prevents
     * silent breakage when chart and service.json drift apart.
     */
    private void applyValueAliasesAndVersionCheck(HelmDeployRequest request, String resolvedVersion) {
        if (request.getServiceKey() == null || request.getServiceKey().isBlank()) return;
        StackServiceDef def;
        try {
            def = new StackDefinitionService(this.ctx).getServiceDefinition(request.getServiceKey());
        } catch (Exception ex) {
            LOG.warn("Could not load service definition for {} (skipping aliases + version check): {}",
                    request.getServiceKey(), ex.toString());
            return;
        }

        // ----- chart version compatibility check
        if (def.requiredChartVersion != null && !def.requiredChartVersion.isBlank()
                && resolvedVersion != null && !resolvedVersion.isBlank()) {
            if (!isChartVersionCompatible(resolvedVersion, def.requiredChartVersion)) {
                throw new IllegalArgumentException(
                        "Chart version " + resolvedVersion + " does not match the range '"
                                + def.requiredChartVersion + "' declared in service.json (" + request.getServiceKey()
                                + "). Update the chart version or the service.json compatibility range.");
            }
            LOG.info("Chart version {} satisfies range '{}' for {}", resolvedVersion, def.requiredChartVersion, request.getServiceKey());
        }

        // ----- value path rewrites
        if (def.valueAliases == null || def.valueAliases.isEmpty()) return;
        Map<String, Object> values = request.getValues();
        for (Map.Entry<String, String> e : def.valueAliases.entrySet()) {
            String from = e.getKey();
            String to = e.getValue();
            if (from == null || from.isBlank() || to == null || to.isBlank() || from.equals(to)) continue;
            rewriteDottedPath(values, from, to);
            LOG.info("Applied valueAlias for {}: {} -> {}", request.getServiceKey(), from, to);
        }
    }

    /**
     * Moves the value at dotted path {@code fromPath} to dotted path {@code toPath}, leaving
     * any other keys untouched. Both paths are interpreted as nested-map keys (no list-index
     * support — list indices in chart values are not aliased today). No-op if the source
     * is absent.
     */
    @SuppressWarnings("unchecked")
    private void rewriteDottedPath(Map<String, Object> root, String fromPath, String toPath) {
        if (root == null) return;
        String[] from = fromPath.split("\\.");
        Map<String, Object> srcParent = root;
        for (int i = 0; i < from.length - 1; i++) {
            Object next = srcParent.get(from[i]);
            if (!(next instanceof Map)) return; // path doesn't exist
            srcParent = (Map<String, Object>) next;
        }
        if (!srcParent.containsKey(from[from.length - 1])) return;
        Object value = srcParent.remove(from[from.length - 1]);

        String[] to = toPath.split("\\.");
        Map<String, Object> dstParent = root;
        for (int i = 0; i < to.length - 1; i++) {
            Object next = dstParent.get(to[i]);
            if (!(next instanceof Map)) {
                Map<String, Object> child = new LinkedHashMap<>();
                dstParent.put(to[i], child);
                next = child;
            }
            dstParent = (Map<String, Object>) next;
        }
        dstParent.put(to[to.length - 1], value);
    }

    /**
     * Minimal semver-range matcher supporting the forms we actually use:
     *   ">=X.Y.Z"   inclusive lower bound
     *   "X.Y.Z"     exact match
     *   "&gt;X.Y.Z"      strict lower bound
     *   "&lt;=X.Y.Z" / "&lt;X.Y.Z"  upper bounds
     * Multiple constraints separated by commas (AND).
     */
    private boolean isChartVersionCompatible(String actual, String range) {
        if (range == null || range.isBlank()) return true;
        String act = actual.trim();
        // strip leading 'v'
        if (act.startsWith("v") || act.startsWith("V")) act = act.substring(1);
        for (String constraintRaw : range.split(",")) {
            String constraint = constraintRaw.trim();
            if (constraint.isEmpty()) continue;
            String op = "=";
            String ver;
            if (constraint.startsWith(">=")) { op = ">="; ver = constraint.substring(2).trim(); }
            else if (constraint.startsWith("<=")) { op = "<="; ver = constraint.substring(2).trim(); }
            else if (constraint.startsWith(">"))  { op = ">";  ver = constraint.substring(1).trim(); }
            else if (constraint.startsWith("<"))  { op = "<";  ver = constraint.substring(1).trim(); }
            else if (constraint.startsWith("="))  { op = "=";  ver = constraint.substring(1).trim(); }
            else                                    { op = "=";  ver = constraint; }
            int cmp = compareSemver(act, ver);
            boolean ok = switch (op) {
                case ">=" -> cmp >= 0;
                case ">" -> cmp > 0;
                case "<=" -> cmp <= 0;
                case "<" -> cmp < 0;
                default -> cmp == 0;
            };
            if (!ok) return false;
        }
        return true;
    }

    private int compareSemver(String a, String b) {
        String[] pa = a.split("[.-]", -1);
        String[] pb = b.split("[.-]", -1);
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < pa.length ? pa[i] : "0";
            String sb = i < pb.length ? pb[i] : "0";
            try {
                int ia = Integer.parseInt(sa);
                int ib = Integer.parseInt(sb);
                if (ia != ib) return Integer.compare(ia, ib);
            } catch (NumberFormatException nfe) {
                int sc = sa.compareTo(sb);
                if (sc != 0) return sc;
            }
        }
        return 0;
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
        if (request.getTls() != null && !request.getTls().isEmpty()) {
            params.put("tls", request.getTls());
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
        if (request.getFormValues() != null && !request.getFormValues().isEmpty()) {
            // Carry the wizard's form-state snapshot through to OIDC step planning.
            // resolveServiceVariablesFromParams reads this as a fallback when a variable's
            // form.field is excludeFromValues (and therefore absent from both top-level
            // params and the chart values file). Without this, {{jupyterHost}} in
            // JupyterHub's redirectUriTemplate would render empty and Keycloak rejects
            // the client registration with 400 invalid_input "A redirect URI is not a valid URI".
            params.put("formValues", request.getFormValues());
        }
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
                request.getFormValues(),
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

        // External-service credential routing — when the operator declared external
        // service targets in service.json#externalServiceTargets, for each entry
        // whose URL override is non-empty, read the chosen auth mode + Secret and
        // write the helm overrides from the mode's applyTo block. No-op when no
        // entries are external (regression-safe — Ambari-managed deploys
        // unaffected). See docs/EXTERNAL_SERVICE_TARGETS.md for the contract.
        applyExternalServiceCredentials(request, params);

        // Resolve Kerberos injection mode from view settings and pass it down to Helm values + step params.
        String kerberosInjectionMode = resolveKerberosInjectionModeFromSettings();
        params.put("kerberosInjectionMode", kerberosInjectionMode);
        this.commandUtils.addOverride(params, "global.security.kerberos.injectionMode", kerberosInjectionMode);
        boolean preProvisionedKerberosMode =
                KERBEROS_INJECTION_MODE_PRE_PROVISIONED.equalsIgnoreCase(kerberosInjectionMode);
        if (preProvisionedKerberosMode) {
            LOG.info("Kerberos injection mode is PRE_PROVISIONED; mutating webhook dependency will be skipped.");
        } else {
            LOG.info("Kerberos injection mode is WEBHOOK; mutating webhook dependency remains enabled.");
        }
        if (request.getTls() != null && !request.getTls().isEmpty()) {
            TlsManager tlsManager = new TlsManager(this.kubernetesService, this.ctx);
            tlsManager.applyTls(request.getTls(), request, params);
        }
        // Create ingress TLS Secret upfront if provided by the request (company CA or user-provided cert).
        if (request.getIngressTlsUpload() != null && !request.getIngressTlsUpload().isEmpty()) {
            Map<String, Object> ingressTlsUpload = request.getIngressTlsUpload();
            String secretName = String.valueOf(ingressTlsUpload.getOrDefault("secretName", request.getReleaseName() + "-ingress-tls"));
            String certPem = String.valueOf(ingressTlsUpload.getOrDefault("certPem", ""));
            String keyPem = String.valueOf(ingressTlsUpload.getOrDefault("keyPem", ""));
            if (!certPem.isBlank() && !keyPem.isBlank()) {
                LOG.info("Creating/updating ingress TLS secret '{}' in namespace '{}' for release '{}' (ingress TLS upload present)", secretName, request.getNamespace(), request.getReleaseName());
                LOG.info("Ingress TLS secret creation input: cert length={} chars, key length={} chars", certPem.length(), keyPem.length());
                LOG.info("Ingress TLS secret creation labels/annotations not provided; using defaults");
                kubernetesService.createOrUpdateTlsSecret(
                        request.getNamespace(),
                        secretName,
                        certPem.getBytes(),
                        keyPem.getBytes(),
                        null,
                        null
                );
                // Inject the standard ingress.tls[0] list shape every KDPS chart understands.
                // We don't rely on the UI's `ingress-setup` binding here because that binding
                // is gated on a form field (`ingress.tlsSecret`) that is only populated in the
                // bringYourOwn flow — uploadLeaf and selfSign flows would otherwise leave the
                // rendered Ingress object without a `tls:` block, and the discovered endpoint
                // URL falls back to http://.
                Object ingressHostVal = (request.getValues() != null)
                        ? (request.getValues().get("ingress.host") != null
                                ? request.getValues().get("ingress.host")
                                : getByPath(request.getValues(), "ingress.host"))
                        : null;
                if (ingressHostVal != null && !String.valueOf(ingressHostVal).isBlank()) {
                    this.commandUtils.addOverride(params, "ingress.tls[0].secretName", secretName);
                    this.commandUtils.addOverride(params, "ingress.tls[0].hosts[0]", String.valueOf(ingressHostVal));
                } else {
                    LOG.warn("Ingress TLS upload created Secret '{}' but ingress.host is missing — cannot write ingress.tls[] override; chart will render Ingress without TLS.", secretName);
                }
            } else {
                LOG.warn("Ingress TLS upload present but cert/key are empty; skipping ingress TLS secret creation for release {}", request.getReleaseName());
            }
        }

        // ----- INGRESS_TLS_SELFSIGN: mint a leaf signed by Ambari Internal CA or a Company CA.
        // Triggered by request.ingressTlsSelfSign={ mode: "ambariInternal"|"companyUploaded",
        // caName: <registry-name>, secretName: <override>, ingressHost: <override>, validityDays: <int> }.
        // This step creates the Secret before HELM_DEPLOY_DRY_RUN and then writes the
        // canonical ingress.tls[0] list shape into the Helm override map so the chart's
        // ingress.yaml renders a TLS spec — every KDPS chart's template iterates
        // .Values.ingress.tls (list of {secretName, hosts}), so a scalar ingress.tlsSecret
        // would be ignored and the K8s Ingress would have no TLS section.
        Map<String, Object> ingressTlsSelfSign = request.getIngressTlsSelfSign();
        if (ingressTlsSelfSign != null && !ingressTlsSelfSign.isEmpty()) {
            String mode = String.valueOf(ingressTlsSelfSign.getOrDefault("mode", "ambariInternal"));
            String caName = (String) ingressTlsSelfSign.get("caName");
            String secretName = String.valueOf(ingressTlsSelfSign.getOrDefault(
                    "secretName", request.getReleaseName() + "-ingress-tls"));
            // Default the host to whatever ingress.host the form provided (resolved earlier via values).
            String ingressHost = (String) ingressTlsSelfSign.get("ingressHost");
            if (ingressHost == null || ingressHost.isBlank()) {
                Object fromValues = getByPath(request.getValues(), "ingress.host");
                if (fromValues == null && request.getValues() != null) {
                    fromValues = request.getValues().get("ingress.host");
                }
                if (fromValues != null) ingressHost = String.valueOf(fromValues);
            }
            Integer validityDays = null;
            Object vd = ingressTlsSelfSign.get("validityDays");
            if (vd instanceof Number) validityDays = ((Number) vd).intValue();
            else if (vd instanceof String s && !s.isBlank()) {
                try { validityDays = Integer.parseInt(s.trim()); } catch (NumberFormatException ignore) {}
            }
            List<String> extraSans = new ArrayList<>();
            Object sansRaw = ingressTlsSelfSign.get("extraSans");
            if (sansRaw instanceof List<?> l) {
                for (Object o : l) if (o != null) extraSans.add(String.valueOf(o));
            } else {
                extraSans.add(request.getReleaseName() + "." + request.getNamespace() + ".svc");
                extraSans.add(request.getReleaseName() + "." + request.getNamespace() + ".svc.cluster.local");
            }

            if (ingressHost == null || ingressHost.isBlank()) {
                LOG.warn("INGRESS_TLS_SELFSIGN requested but no ingress.host found; skipping");
            } else {
                String stepId = this.commandPlanFactory.appendIngressTlsSelfSignStep(
                        rootCommand,
                        request.getReleaseName(),
                        request.getNamespace(),
                        secretName,
                        ingressHost,
                        extraSans,
                        mode,
                        caName,
                        validityDays
                );
                childCommands.add(stepId);
                // Write the canonical list shape (ingress.tls[0]) so the chart's
                // {{- if .Values.ingress.tls }}{{- range ... }} block renders.
                // Helm's --set list-index syntax is the same as HelmService.applyOverrides
                // expects, which materialises ingress.tls as List<Map> in finalValues.
                this.commandUtils.addOverride(params, "ingress.tls[0].secretName", secretName);
                this.commandUtils.addOverride(params, "ingress.tls[0].hosts[0]", ingressHost);
                LOG.info("Planned INGRESS_TLS_SELFSIGN: stepId={}, secret={}/{}, host={}, mode={}, ca={}",
                        stepId, request.getNamespace(), secretName, ingressHost, mode, caName);
            }
        }

        // ----- INGRESS_TLS_CERTMANAGER: write a cert-manager.io Certificate so cert-manager
        // produces (and auto-renews) the leaf Secret. The chart references the Secret name in
        // ingress.tls[0].secretName as it would for any other TLS mode — the difference is
        // who fills the Secret.
        Map<String, Object> ingressTlsCM = request.getIngressTlsCertManager();
        if (ingressTlsCM != null && !ingressTlsCM.isEmpty()) {
            String issuerName = (String) ingressTlsCM.get("issuerName");
            String issuerKind = String.valueOf(ingressTlsCM.getOrDefault("issuerKind", "ClusterIssuer"));
            String secretName = String.valueOf(ingressTlsCM.getOrDefault("secretName", request.getReleaseName() + "-ingress-tls"));
            String certName = String.valueOf(ingressTlsCM.getOrDefault("certName", secretName));
            String ingressHost = (String) ingressTlsCM.get("ingressHost");
            if (ingressHost == null || ingressHost.isBlank()) {
                Object fromValues = getByPath(request.getValues(), "ingress.host");
                if (fromValues == null && request.getValues() != null) fromValues = request.getValues().get("ingress.host");
                if (fromValues != null) ingressHost = String.valueOf(fromValues);
            }
            Integer durationHours = null;
            Object dh = ingressTlsCM.get("durationHours");
            if (dh instanceof Number n) durationHours = n.intValue();
            else if (dh instanceof String s && !s.isBlank()) {
                try { durationHours = Integer.parseInt(s.trim()); } catch (NumberFormatException ignore) {}
            }
            List<String> dnsNames = new ArrayList<>();
            if (ingressHost != null && !ingressHost.isBlank()) dnsNames.add(ingressHost);
            Object sansRaw = ingressTlsCM.get("extraSans");
            if (sansRaw instanceof List<?> l) for (Object o : l) if (o != null) dnsNames.add(String.valueOf(o));

            if (issuerName == null || issuerName.isBlank() || dnsNames.isEmpty()) {
                // Fail loud instead of skip-and-warn: a silent skip plus a downstream OIDC
                // preflight failure is the worst combination for diagnosis. Throw now so
                // the operator sees the problem at submit time.
                throw new IllegalArgumentException(
                        "INGRESS_TLS_CERTMANAGER: issuerName and at least one DNS name (ingress.host) are required."
                                + " Pick a ClusterIssuer from the dropdown in the wizard's TLS mode section.");
            } else {
                String stepId = appendIngressTlsCertManagerStep(rootCommand, request.getNamespace(),
                        certName, secretName, issuerName, issuerKind, dnsNames, durationHours);
                childCommands.add(stepId);
                this.commandUtils.addOverride(params, "ingress.tls[0].secretName", secretName);
                this.commandUtils.addOverride(params, "ingress.tls[0].hosts[0]", dnsNames.get(0));
                LOG.info("Planned INGRESS_TLS_CERTMANAGER: stepId={}, secret={}/{}, issuer={}/{}",
                        stepId, request.getNamespace(), secretName, issuerKind, issuerName);
            }
        }

        // ----- INGRESS_TLS_EXTERNAL_SECRET: write an ExternalSecret so external-secrets
        // syncs an upstream secret (typically Vault PKI) into the K8s Secret the chart wants.
        Map<String, Object> ingressTlsES = request.getIngressTlsExternalSecret();
        if (ingressTlsES != null && !ingressTlsES.isEmpty()) {
            String storeName = (String) ingressTlsES.get("secretStoreName");
            String storeKind = String.valueOf(ingressTlsES.getOrDefault("secretStoreKind", "ClusterSecretStore"));
            String remoteKey = (String) ingressTlsES.get("remoteKey");
            String secretName = String.valueOf(ingressTlsES.getOrDefault("secretName", request.getReleaseName() + "-ingress-tls"));
            String esName = String.valueOf(ingressTlsES.getOrDefault("externalSecretName", secretName));
            String refreshInterval = String.valueOf(ingressTlsES.getOrDefault("refreshInterval", "1h"));
            String ingressHost = (String) ingressTlsES.get("ingressHost");
            if (ingressHost == null || ingressHost.isBlank()) {
                Object fromValues = getByPath(request.getValues(), "ingress.host");
                if (fromValues == null && request.getValues() != null) fromValues = request.getValues().get("ingress.host");
                if (fromValues != null) ingressHost = String.valueOf(fromValues);
            }
            if (storeName == null || storeName.isBlank() || remoteKey == null || remoteKey.isBlank()) {
                throw new IllegalArgumentException(
                        "INGRESS_TLS_EXTERNAL_SECRET: secretStoreName and remoteKey are required."
                                + " Pick a SecretStore from the dropdown and provide the upstream key/path.");
            } else {
                String stepId = appendIngressTlsExternalSecretStep(rootCommand, request.getNamespace(),
                        esName, secretName, storeName, storeKind, remoteKey, refreshInterval);
                childCommands.add(stepId);
                this.commandUtils.addOverride(params, "ingress.tls[0].secretName", secretName);
                if (ingressHost != null && !ingressHost.isBlank()) {
                    this.commandUtils.addOverride(params, "ingress.tls[0].hosts[0]", ingressHost);
                }
                LOG.info("Planned INGRESS_TLS_EXTERNAL_SECRET: stepId={}, secret={}/{}, store={}/{}, key={}",
                        stepId, request.getNamespace(), secretName, storeKind, storeName, remoteKey);
            }
        }
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
        rootCommand.setTitle("Install release " + request.getReleaseName());

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
        boolean kerberosDetectionAvailable = false;
        try {
            if (ambariActionClient != null) {
                String securityEnabledCluster = ambariActionClient.getDesiredConfigProperty(
                        cluster, "cluster-env", "security_enabled"
                );
                kerberosEnabled = "true".equalsIgnoreCase(securityEnabledCluster);
                kerberosDetectionAvailable = true;
            } else {
                LOG.warn("AmbariActionClient is null; skipping Kerberos detection and ConfigMap creation");
            }
        } catch (Exception ex) {
            LOG.warn("Could not determine Kerberos state from Ambari, assuming disabled: {}", ex.toString());
        }

        if (kerberosDetectionAvailable) {
            params.put("kerberosClusterEnabled", kerberosEnabled);
        } else {
            LOG.warn("Kerberos detection unavailable; leaving global.security.kerberos.enabled unchanged");
        }
        // A selected EXTERNAL platform context may target a backend (e.g. CDP) in a DIFFERENT
        // Kerberos realm than the Ambari-hosting cluster. When that context supplies a krb5.conf,
        // use it for this deploy (and enable Kerberos wiring) regardless of the hosting cluster's
        // own security state — the deployed pods must speak the backend realm, not ours.
        String contextKrb5 = null;
        try {
            Map<String, String> ctxResolvedKrb =
                    contextResolvedFieldsForDeploy(request.getFormValues(), ambariActionClient, cluster);
            if (ctxResolvedKrb != null) {
                contextKrb5 = ctxResolvedKrb.get("kerberos.krb5Conf");
            }
        } catch (Exception ex) {
            LOG.warn("Could not resolve platform-context krb5.conf for deploy: {}", ex.toString());
        }
        boolean contextKerberos = (contextKrb5 != null && !contextKrb5.isBlank());

        if (kerberosEnabled || contextKerberos) {
            LOG.info("Kerberos enabled for this deploy (hostingCluster={}, fromContext={}); preparing krb5.conf ConfigMap for Helm Chart",
                    kerberosEnabled, contextKerberos);

            // Derive a unique ConfigMap name per release to avoid collisions across multiple installs
            String releaseName = request.getReleaseName() != null ? request.getReleaseName().trim() : "";
            String krb5ConfigMapName = (releaseName.isEmpty() ? "krb5-conf" : (releaseName + "-krb5-conf"));

            // 1) Create/update the ConfigMap — from the selected context's krb5.conf when supplied,
            //    otherwise from the hosting cluster's /etc/krb5.conf.
            this.commandUtils.ensureKrb5ConfConfigMap(request.getNamespace(), krb5ConfigMapName, contextKrb5);

            // 2) Inject Helm overrides so the chart mounts it:
            //    global.security.kerberos.enabled = true
            //    global.security.kerberos.configMapName = <cm name>
            this.commandUtils.addOverride(params, "global.security.kerberos.enabled", "true");
            this.commandUtils.addOverride(params, "global.security.kerberos.configMapName", krb5ConfigMapName);
        } else if (kerberosDetectionAvailable) {
            // Explicitly disable Kerberos in chart values when neither the Ambari cluster nor the
            // selected context provides Kerberos.
            this.commandUtils.addOverride(params, "global.security.kerberos.enabled", "false");
            LOG.info("Kerberos is disabled on cluster {} and no context krb5.conf supplied; overriding chart values with global.security.kerberos.enabled=false", cluster);
        }

        // Build Kerberos template variables for later templating (catalog enrichments, etc.).
        List<Map<String, Object>> kerberosEntryList = normalizeKerberosEntries(request.getKerberos());
        String kerberosRealm = null;
        if (kerberosEnabled) {
            kerberosRealm = resolveKerberosRealmFromAmbari(ambariActionClient, cluster);
        }
        Map<String, Object> kerberosTemplateVariableMap = buildKerberosTemplateVariablesForEntries(
                kerberosEntryList,
                request.getNamespace(),
                request.getReleaseName(),
                kerberosRealm
        );
        mergeTemplateVariablesIntoParams(params, kerberosTemplateVariableMap, "kerberos");

        // ---------- Truststore from Ambari truststore (for downstream TLS clients)
        // This builds a Secret from the company CA truststore and also injects the Ambari internal CA cert
        // so both can be trusted by downstream clients. Helm wiring uses global.security.tls.*. ----------
        try {
            String truststorePath = ctx.getAmbariProperty("ssl.trustStore.path");
            if (truststorePath != null && !truststorePath.isBlank()) {
                String truststoreType = Optional.ofNullable(ctx.getAmbariProperty("ssl.trustStore.type"))
                        .filter(s -> !s.isBlank()).orElse("JKS");
                String truststorePassProp = Optional.ofNullable(ctx.getAmbariProperty("ssl.trustStore.password")).orElse("");
                char[] truststorePassword = ambariAliasResolver.resolve(ctx, truststorePassProp);

                byte[] truststoreBytes = Files.readAllBytes(Paths.get(truststorePath));
                String truststoreSecretName = request.getReleaseName() + "-truststore";
                Map<String, byte[]> data = new LinkedHashMap<>();
                // Merge internal CA cert into the company truststore (single keystore/Secret to mount).
                byte[] mergedTruststoreBytes = truststoreBytes;
                StringBuilder pemBundle = new StringBuilder();
                try {
                    KeyStore keyStore = WebHookConfigurationService.loadKeyStore(Paths.get(truststorePath), truststorePassword, truststoreType);
                    // Append existing certificates to PEM bundle and track if internal CA is already present.
                    var aliases = keyStore.aliases();
                    while (aliases.hasMoreElements()) {
                        String alias = aliases.nextElement();
                        Certificate certificate = keyStore.getCertificate(alias);
                        if (certificate instanceof X509Certificate x509) {
                            pemBundle.append("-----BEGIN CERTIFICATE-----\n")
                                    .append(java.util.Base64.getMimeEncoder(64, new byte[]{'\n'})
                                            .encodeToString(x509.getEncoded()))
                                    .append("\n-----END CERTIFICATE-----\n");
                        }
                    }
                    // Load Ambari internal CA cert and add it if not already present.
                    WebHookConfigurationService webHookCfg = new WebHookConfigurationService(ctx, this.kubernetesService);
                    var internalCa = webHookCfg.ensureAmbariCertificateAuthority();
                    X509Certificate internalCaCert = (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                            .generateCertificate(new java.io.ByteArrayInputStream(internalCa.caCertificatePem().getBytes(StandardCharsets.UTF_8)));
                    boolean hasInternalCa = false;
                    var verifyAliases = keyStore.aliases();
                    while (verifyAliases.hasMoreElements()) {
                        String alias = verifyAliases.nextElement();
                        Certificate certificate = keyStore.getCertificate(alias);
                        if (certificate instanceof X509Certificate x509) {
                            try {
                                x509.verify(internalCaCert.getPublicKey());
                                hasInternalCa = true;
                                break;
                            } catch (Exception ignored) {
                                // not the same cert
                            }
                        }
                    }
                    if (!hasInternalCa) {
                        keyStore.setCertificateEntry("ambari-internal-ca", internalCaCert);
                        pemBundle.append("-----BEGIN CERTIFICATE-----\n")
                                .append(java.util.Base64.getMimeEncoder(64, new byte[]{'\n'})
                                        .encodeToString(internalCaCert.getEncoded()))
                                .append("\n-----END CERTIFICATE-----\n");
                        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                            keyStore.store(baos, truststorePassword);
                            mergedTruststoreBytes = baos.toByteArray();
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to merge internal CA into truststore {}; using original truststore only: {}", truststorePath, e.toString());
                }

                data.put("truststore.jks", mergedTruststoreBytes);
                data.put("truststore.password", new String(truststorePassword).getBytes(StandardCharsets.UTF_8));
                if (pemBundle.length() > 0) {
                    data.put("ca.crt", pemBundle.toString().getBytes(StandardCharsets.UTF_8));
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

                LOG.info("Provisioned truststore Secret '{}' in namespace '{}' from Ambari truststore {}", truststoreSecretName, request.getNamespace(), truststorePath);
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

        if (ranger != null && !ranger.isEmpty()) {
            materializeRangerResources(
                    request,
                    params,
                    effectiveValues,
                    ambariActionClient,
                    cluster,
                    ambariAliasResolver
            );
            overrides = this.commandUtils.loadValuesFromParams(params);
            if (overrides == null) {
                overrides = Collections.emptyMap();
            }
            effectiveValues = this.configResolutionService.deepMerge(defaultValues, overrides);
        }

        // Apply catalog enrichments (e.g., Kerberos properties) when defined in service.json.
        try {
            if (request.getServiceKey() != null && !request.getServiceKey().isBlank()) {
                StackDefinitionService catalogStackDefinitionService = new StackDefinitionService(this.ctx);
                StackServiceDef catalogServiceDefinition = catalogStackDefinitionService.getServiceDefinition(request.getServiceKey());
                List<Map<String, Object>> catalogEnrichmentSpecList =
                        catalogServiceDefinition != null ? catalogServiceDefinition.catalogEnrichments : null;
                // Use the persisted Kerberos cluster flag when available to decide if Kerberos-only catalog enrichments apply.
                boolean kerberosEnabledForCatalogEnrichments = kerberosEnabled;
                if (params != null && params.containsKey("kerberosClusterEnabled")) {
                    kerberosEnabledForCatalogEnrichments = asBoolean(
                            params.get("kerberosClusterEnabled"),
                            kerberosEnabled
                    );
                }
                // A selected EXTERNAL context (e.g. CDP) supplies its own realm/krb5, so Kerberos-only
                // catalog enrichments must apply even when the Ambari-hosting cluster itself is not
                // Kerberized (or is in a different realm).
                if (contextKerberos) {
                    kerberosEnabledForCatalogEnrichments = true;
                }
                LOG.info("Catalog enrichment Kerberos flag resolved to {} (detected={}, paramsOverridePresent={})",
                        kerberosEnabledForCatalogEnrichments,
                        kerberosEnabled,
                        params != null && params.containsKey("kerberosClusterEnabled"));
                Map<String, String> catalogOverrideMap = buildCatalogEnrichmentOverrides(
                        catalogEnrichmentSpecList,
                        params,
                        effectiveValues,
                        ambariActionClient,
                        cluster,
                        kerberosEnabledForCatalogEnrichments
                );
                if (catalogOverrideMap != null && !catalogOverrideMap.isEmpty()) {
                    for (Map.Entry<String, String> overrideEntry : catalogOverrideMap.entrySet()) {
                        String overridePath = overrideEntry.getKey();
                        String overrideValue = overrideEntry.getValue();
                        this.commandUtils.addOverride(params, overridePath, overrideValue);
                        LOG.info("Applied catalog enrichment override {} ({} chars)", overridePath,
                                overrideValue != null ? overrideValue.length() : 0);
                    }
                    overrides = this.commandUtils.loadValuesFromParams(params);
                    if (overrides == null) {
                        overrides = Collections.emptyMap();
                    }
                    effectiveValues = this.configResolutionService.deepMerge(defaultValues, overrides);
                } else {
                    LOG.info("No catalog enrichment overrides applied for serviceKey {}", request.getServiceKey());
                }
            } else {
                LOG.info("ServiceKey missing; skipping catalog enrichment.");
            }
        } catch (Exception ex) {
            LOG.warn("Failed to apply catalog enrichments for release {}: {}", request.getReleaseName(), ex.toString());
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
            // ranger-plugin-settings drives plugin repository creation (Trino, etc.).
            // Plugin-repo creation talks directly to Ranger Admin REST and doesn't
            // depend on the chart's pods, so it stays pre-helm.
            //
            // TagSync source registration is intentionally NOT called here — the
            // step body needs a Running airflow scheduler pod to mint the OM
            // ingestion-bot JWT, which only exists after the helm install
            // completes. planRangerTagSyncSources is called from the post-deploy
            // block below (after createRealChartInstallationCommands), where
            // AMBARI_VIEW_PROVISION and OM_ATLAS_FEDERATION_REGISTER also live.
            planRangerRepositoryCreation(rootCommand, childCommands, request, params, effectiveValues, ambariActionClient, cluster);
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
        Map<String, Object> dependenciesToProcess = request.getDependencies();
        if (dependenciesToProcess != null && !dependenciesToProcess.isEmpty()) {
            if (kerberosDetectionAvailable && !kerberosEnabled && dependenciesToProcess.containsKey("kerberos-keytab-mutating-webhook")) {
                dependenciesToProcess = new LinkedHashMap<>(dependenciesToProcess);
                dependenciesToProcess.remove("kerberos-keytab-mutating-webhook");
                LOG.info("Removed kerberos-keytab-mutating-webhook dependency because Kerberos is disabled on the cluster");
            }
            if (preProvisionedKerberosMode && dependenciesToProcess.containsKey("kerberos-keytab-mutating-webhook")) {
                dependenciesToProcess = new LinkedHashMap<>(dependenciesToProcess);
                dependenciesToProcess.remove("kerberos-keytab-mutating-webhook");
                LOG.info("Removed kerberos-keytab-mutating-webhook dependency due to PRE_PROVISIONED mode");
            }
            for (Map.Entry<String, Object> dependencyEntry : dependenciesToProcess.entrySet()) {
                Object dependencySpec = dependencyEntry.getValue();
                boolean skipIfReleaseExists = false;
                String dependencyNamespace = null;
                // Propagate injection mode to dependency steps so they can skip webhook label work.
                if (dependencySpec instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dependencySpecMap = (Map<String, Object>) dependencySpec;
                    dependencySpecMap.put("kerberosInjectionMode", kerberosInjectionMode);
                    if (kerberosDetectionAvailable) {
                        dependencySpecMap.put("kerberosClusterEnabled", kerberosEnabled);
                    }
                    skipIfReleaseExists = asBoolean(dependencySpecMap.get("skipIfReleaseExists"), false);
                    dependencyNamespace = resolveStringValue(dependencySpecMap.get("namespace"), null);
                }

                String dependencyReleaseName = dependencyEntry.getKey();

                // OpenShift: skip dependencies flagged skipOnOpenShift (e.g. kube-prometheus-stack, which
                // conflicts with the platform's built-in Prometheus operator — same monitoring.coreos.com
                // CRDs). Trino still autoscales via KEDA against the built-in (user-workload) monitoring.
                if (dependencySpec instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> depSkipMap = (Map<String, Object>) dependencySpec;
                    if (asBoolean(depSkipMap.get("skipOnOpenShift"), false) && this.kubernetesService.isOpenShiftCluster()) {
                        LOG.info("Skipping dependency '{}' on OpenShift (skipOnOpenShift=true)", dependencyReleaseName);
                        this.commandPlanFactory.createDependencySatisfiedCommand(
                                rootCommand, dependencyReleaseName, depSkipMap,
                                "Skipped on OpenShift — the platform's built-in monitoring stack is used instead.");
                        continue;
                    }
                }

                if (skipIfReleaseExists && dependencyReleaseName != null && dependencyNamespace != null) {
                    if (dependencyReleaseExists(dependencyNamespace, dependencyReleaseName)) {
                        String reason = "Dependency already installed as Helm release '" + dependencyReleaseName
                                + "' in namespace '" + dependencyNamespace + "'";
                        LOG.info("Skipping dependency install because release exists: {} in {}", dependencyReleaseName, dependencyNamespace);
                        if (dependencySpec instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> dependencySpecMap = (Map<String, Object>) dependencySpec;
                            this.commandPlanFactory.createDependencySatisfiedCommand(
                                    rootCommand,
                                    dependencyReleaseName,
                                    dependencySpecMap,
                                    reason
                            );
                        }
                        continue;
                    }
                    LOG.info("Dependency {} not found in {}; proceeding with install", dependencyReleaseName, dependencyNamespace);
                }
                LOG.info("Processing dependency: {} ", dependencyEntry.getKey());
                this.commandPlanFactory.createDependencyCommands(
                        rootCommand,
                        dependencyEntry.getValue(),
                        dependencyEntry.getKey(),
                        repoId,
                        commandsURL,
                        callerHeaders,
                        baseUri
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

        // 2b. KEDA/Thanos autoscaling on OpenShift: the chart's TriggerAuthentication reads a bearer
        // token from a Secret in the release namespace so worker autoscaling can query the platform's
        // built-in (user-workload) monitoring. Ensure that Secret (SA bound to cluster-monitoring-view +
        // a service-account-token Secret) exists — create it when the view has the rights, otherwise
        // BLOCK the deploy with an actionable message so a cluster admin can provision it first, rather
        // than shipping a Trino that never scales. (detect, else request — see ensureKedaThanosTokenSecret)
        if (this.kubernetesService.isOpenShiftCluster()) {
            Map<String, Object> deployValues = request.getValues();
            Object kedaNode = mapGet(mapGet(deployValues, "server"), "keda");
            Object triggerAuthNode = mapGet(kedaNode, "triggerAuthentication");
            if (triggerAuthNode instanceof Map
                    && asBoolean(((Map<?, ?>) triggerAuthNode).get("enabled"), false)) {
                String tokenSecretName = resolveStringValue(((Map<?, ?>) triggerAuthNode).get("secretName"), null);
                if (tokenSecretName == null || tokenSecretName.isBlank()) {
                    throw new IllegalStateException("Cannot deploy " + request.getReleaseName()
                            + " with OpenShift KEDA autoscaling: server.keda.triggerAuthentication.secretName is not set.");
                }
                String problem = this.kubernetesService.ensureKedaThanosTokenSecret(
                        request.getNamespace(), tokenSecretName, tokenSecretName);
                if (problem != null) {
                    throw new IllegalStateException("Cannot deploy " + request.getReleaseName()
                            + " with OpenShift KEDA autoscaling: " + problem);
                }
                LOG.info("KEDA/Thanos monitoring token Secret '{}' ensured for release {} in namespace {}",
                        tokenSecretName, request.getReleaseName(), request.getNamespace());
            }
        }

        // 2a. Pre-provision Kerberos keytabs if the view is configured for it.
        // Skip Ambari keytab issuance when the operator supplied an external keytab (via a service's
        // externalServiceTargets kerberos mode): the pods use that operator-provided keytab
        // (PRE_PROVISIONED), and principals in a backend (e.g. CDP) realm are not issuable by the
        // hosting KDC anyway.
        boolean externalKeytabProvided = deployUsesExternalKeytab(request.getFormValues());
        if (preProvisionedKerberosMode && externalKeytabProvided) {
            LOG.info("External Kerberos keytab supplied for this deploy; skipping Ambari keytab issuance (pods use the operator-provided keytab).");
        } else if (preProvisionedKerberosMode) {
            boolean keytabStepsAdded = false;
            if (!kerberosEnabled) {
                LOG.info("Kerberos is disabled on the Ambari cluster; skipping pre-provisioned keytab creation.");
            } else if (ambariActionClient == null || cluster == null || cluster.isBlank()) {
                LOG.warn("Ambari client or cluster is unavailable; skipping pre-provisioned keytab creation.");
            } else {
                List<Map<String, Object>> kerberosEntries = kerberosEntryList;
                if (kerberosEntries.isEmpty()) {
                    LOG.info("No Kerberos entries found in the request; skipping keytab provisioning.");
                } else {
                    if (kerberosRealm == null || kerberosRealm.isBlank()) {
                        LOG.warn("Kerberos realm is empty; skipping pre-provisioned keytab creation.");
                    } else {
                        boolean helmOverridesApplied = false;
                        int kerberosEntryIndex = 0;
                        for (Map<String, Object> kerberosEntry : kerberosEntries) {
                            kerberosEntryIndex++;
                            if (!isKerberosEntryEnabled(kerberosEntry.get("enabled"))) {
                                LOG.info("Skipping disabled Kerberos entry {}", kerberosEntry.getOrDefault("key", kerberosEntryIndex));
                                continue;
                            }
                            String entryKey = resolveStringValue(
                                    kerberosEntry.get("key"), "entry-" + kerberosEntryIndex);
                            String serviceName = resolveStringValue(
                                    kerberosEntry.get("serviceName"), request.getReleaseName());
                            String principalTemplate = resolveStringValue(
                                    kerberosEntry.get("principalTemplate"), "{{service}}-{{namespace}}@{{realm}}");
                            // Flatten formValues to <name,String> so principalTemplate can address
                            // wizard form fields by name (e.g. {{kerberosHostFqdn}}). Required
                            // because FreeIPA 4.12 rejects non-FQDN service host components and we
                            // don't want to hardcode any cluster's DNS suffix in service.json.
                            Map<String, String> kerberosExtraTokens = new LinkedHashMap<>();
                            if (request.getFormValues() != null) {
                                for (Map.Entry<String, Object> e : request.getFormValues().entrySet()) {
                                    Object v = e.getValue();
                                    if (v != null) kerberosExtraTokens.put(e.getKey(), String.valueOf(v));
                                }
                            }
                            String principalFqdn = renderKerberosPrincipalTemplate(
                                    principalTemplate,
                                    serviceName,
                                    request.getNamespace(),
                                    request.getReleaseName(),
                                    kerberosRealm,
                                    kerberosExtraTokens
                            );
                            if (principalFqdn == null || principalFqdn.isBlank()) {
                                LOG.warn("Kerberos principal resolved to empty for entry {}; skipping.", entryKey);
                                continue;
                            }
                            validateKerberosPrincipal(principalFqdn);

                            String secretName = resolveKerberosEntrySecretName(
                                    kerberosEntry,
                                    serviceName,
                                    request.getNamespace(),
                                    request.getReleaseName(),
                                    kerberosRealm
                            );
                            String keyNameInSecret = resolveStringValue(
                                    kerberosEntry.get("keyNameInSecret"), "service.keytab");
                            String mountPath = resolveStringValue(
                                    kerberosEntry.get("mountPath"), "/etc/security/keytabs");

                            LOG.info("Pre-provisioning keytab for entry {}: principal={}, secret={}, key={}, mountPath={}",
                                    entryKey, principalFqdn, secretName, keyNameInSecret, mountPath);

                            appendPreProvisionedKeytabSteps(
                                    rootCommand,
                                    childCommands,
                                    params,
                                    principalFqdn,
                                    request.getNamespace(),
                                    secretName,
                                    keyNameInSecret,
                                    entryKey
                            );
                            keytabStepsAdded = true;

                            // Only one secret can be wired to global.security.kerberos.keytab.* today.
                            if (!helmOverridesApplied) {
                                this.commandUtils.addOverride(params, "global.security.kerberos.keytab.secretName", secretName);
                                this.commandUtils.addOverride(params, "global.security.kerberos.keytab.secretDataKey", keyNameInSecret);
                                if (mountPath != null && !mountPath.isBlank()) {
                                    this.commandUtils.addOverride(params, "global.security.kerberos.keytab.mountPath", mountPath);
                                }
                                helmOverridesApplied = true;
                            } else {
                                LOG.warn("Multiple Kerberos entries detected; Helm overrides already set from the first entry.");
                            }
                        }
                    }
                }
            }
            if (keytabStepsAdded) {
                rootCommand.setChildListJson(gson.toJson(childCommands));
                rootCommand.setParamsJson(gson.toJson(params));
                store(rootCommand);
            }
        }

        // 2b. Pre-provision OIDC client credentials if the service definition has oidc[] entries.
        if (request.getServiceKey() != null && !request.getServiceKey().isBlank()) {
            StackDefinitionService oidcSds = new StackDefinitionService(this.ctx);
            StackServiceDef oidcServiceDef = oidcSds.getServiceDefinition(request.getServiceKey());
            List<Map<String, Object>> oidcEntries =
                    (oidcServiceDef != null && oidcServiceDef.oidc != null) ? oidcServiceDef.oidc : Collections.emptyList();

            if (!oidcEntries.isEmpty()) {
                String oidcRealm = "";
                if (ambariActionClient != null && cluster != null && !cluster.isBlank()) {
                    try {
                        oidcRealm = ambariActionClient.getDesiredConfigProperty(cluster, "oidc-env", "oidc_realm");
                    } catch (Exception ex) {
                        LOG.warn("Could not read oidc_realm from cluster {}: {}", cluster, ex.toString());
                    }
                }
                params.put("oidcRealm", oidcRealm);

                // Determine OIDC source: "external" = admin pre-supplied credentials; "internal" = Keycloak registration
                boolean oidcIsExternal = securityCfg != null
                        && securityCfg.oidc != null
                        && "external".equalsIgnoreCase(securityCfg.oidc.source);
                String externalClientId     = oidcIsExternal && securityCfg.oidc.clientId     != null ? securityCfg.oidc.clientId     : null;
                String externalClientSecret = oidcIsExternal && securityCfg.oidc.clientSecret != null ? securityCfg.oidc.clientSecret : null;
                String externalIssuerUrl    = oidcIsExternal && securityCfg.oidc.issuerUrl    != null ? securityCfg.oidc.issuerUrl    : null;

                boolean oidcStepsAdded = false;
                boolean oidcHelmOverridesApplied = false;
                int oidcIdx = 0;
                for (Map<String, Object> entry : oidcEntries) {
                    oidcIdx++;
                    if (!isEntryEnabled(entry.get("enabled"))) {
                        LOG.info("Skipping disabled OIDC entry {}", entry.getOrDefault("key", oidcIdx));
                        continue;
                    }
                    String entryKey  = resolveStringValue(entry.get("key"), "entry-" + oidcIdx);
                    String secretT   = resolveStringValue(entry.get("secretNameTemplate"), "{{releaseName}}-oidc-client");
                    String vaultPath = resolveStringValue(entry.get("vaultPath"), null);
                    String secretName = renderOidcTemplate(secretT, request.getReleaseName(), request.getNamespace(), oidcRealm);

                    boolean gitlabOmniauth = Boolean.TRUE.equals(entry.get("gitlabOmniauth"))
                            || "true".equalsIgnoreCase(String.valueOf(entry.get("gitlabOmniauth")));

                    Map<String, Object> extraOidcParams = new LinkedHashMap<>();
                    // Forward client flow flags declared in service.json oidc[] entry.
                    // Defaults match the historical KeycloakAdminClient behavior
                    // (publicClient=false, standardFlowEnabled=true, implicitFlowEnabled=false).
                    extraOidcParams.put("publicClient",
                            Boolean.TRUE.equals(entry.get("publicClient"))
                                    || "true".equalsIgnoreCase(String.valueOf(entry.get("publicClient"))));
                    Object stdFlowEntry = entry.get("standardFlowEnabled");
                    extraOidcParams.put("standardFlowEnabled",
                            stdFlowEntry == null || Boolean.parseBoolean(String.valueOf(stdFlowEntry)));
                    extraOidcParams.put("implicitFlowEnabled",
                            Boolean.TRUE.equals(entry.get("implicitFlowEnabled"))
                                    || "true".equalsIgnoreCase(String.valueOf(entry.get("implicitFlowEnabled"))));
                    String providerSecretName = null;
                    if (gitlabOmniauth) {
                        providerSecretName = request.getReleaseName() + "-omniauth-provider";
                        extraOidcParams.put("oidcGitlabOmniauth", "true");
                        extraOidcParams.put("oidcProviderSecretName", providerSecretName);
                        // The actual callback URL is filled in below once we've resolved
                        // the redirect URI via the service-definition variable pipeline
                        // (which knows how to substitute gitlabDomain etc.).
                    }

                    if (oidcIsExternal) {
                        // External OIDC: no Keycloak registration — write the secret directly from profile credentials
                        appendOidcExternalSecretStep(rootCommand, childCommands, params,
                                externalClientId, externalClientSecret, externalIssuerUrl,
                                secretName, entryKey, vaultPath, extraOidcParams);
                    } else {
                        // Internal OIDC: register client in Keycloak, then create secret
                        String clientIdT  = resolveStringValue(entry.get("clientIdTemplate"), "{{releaseName}}-{{namespace}}");
                        String redirectT  = resolveStringValue(entry.get("redirectUriTemplate"), "");
                        String clientId   = renderOidcTemplate(clientIdT, request.getReleaseName(), request.getNamespace(), oidcRealm);
                        validateOidcClientId(clientId);
                        Map<String, String> oidcExtraVars = resolveServiceVariablesFromParams(
                                oidcServiceDef != null ? oidcServiceDef.variables : null, params);
                        String redirectUri = renderOidcTemplate(redirectT, request.getReleaseName(), request.getNamespace(), oidcRealm, oidcExtraVars);
                        if (redirectUri.contains("{{")) {
                            LOG.warn("OIDC redirectUri still contains unresolved tokens after variable substitution: {}", redirectUri);
                        }
                        if (gitlabOmniauth) {
                            // The provider YAML written by OIDC_CREATE_SECRET needs the
                            // same callback URL that we register with Keycloak. Earlier
                            // code path tried to read gitlab.global.hosts.domain straight
                            // out of `params`, which doesn't hold the nested chart values
                            // tree at this point — it returned an empty string and the
                            // resulting YAML pointed at `http://gitlab./users/...`,
                            // breaking the OAuth flow. Reusing the resolved redirectUri
                            // is the single source of truth and keeps Keycloak's
                            // registered URI and GitLab's emitted URI byte-identical.
                            extraOidcParams.put("oidcGitlabCallbackUrl", redirectUri);
                        }
                        appendOidcRegistrationSteps(rootCommand, childCommands, params,
                                clientId, redirectUri, secretName, entryKey, vaultPath, extraOidcParams);
                    }
                    oidcStepsAdded = true;

                    if (!oidcHelmOverridesApplied) {
                        if (gitlabOmniauth) {
                            // GitLab uses OmniAuth provider secret rather than global.security.auth.*
                            this.commandUtils.addOverride(params, "gitlab.global.appConfig.omniauth.enabled", "true");
                            this.commandUtils.addOverride(params, "gitlab.global.appConfig.omniauth.allowSingleSignOn[0]", "openid_connect");
                            this.commandUtils.addOverride(params, "gitlab.global.appConfig.omniauth.blockAutoCreatedUsers", "false");
                            this.commandUtils.addOverride(params, "gitlab.global.appConfig.omniauth.providers[0].secret", providerSecretName);
                        } else {
                            // Standard global.security.auth.* wiring (Superset, Trino, etc.)
                            this.commandUtils.addOverride(params, "global.security.auth.mode", "oidc");
                            this.commandUtils.addOverride(params, "global.security.auth.oidc.secretRef.name", secretName);
                            this.commandUtils.addOverride(params, "global.security.auth.oidc.secretRef.clientIdKey", "client_id");
                            this.commandUtils.addOverride(params, "global.security.auth.oidc.secretRef.clientSecretKey", "client_secret");
                        }
                        oidcHelmOverridesApplied = true;
                    }
                }

                if (oidcStepsAdded) {
                    // Resolve issuer URL: external profiles carry it in the profile DTO; internal from Ambari
                    if (oidcIsExternal) {
                        if (externalIssuerUrl != null && !externalIssuerUrl.isBlank()) {
                            this.commandUtils.addOverride(params, "global.security.auth.oidc.issuerUrl", externalIssuerUrl);
                        }
                    } else if (ambariActionClient != null && cluster != null) {
                        String issuerUrl = resolveOidcIssuerUrl(ambariActionClient, cluster);
                        if (issuerUrl != null && !issuerUrl.isBlank()) {
                            this.commandUtils.addOverride(params, "global.security.auth.oidc.issuerUrl", issuerUrl);
                        }
                    }
                    rootCommand.setChildListJson(gson.toJson(childCommands));
                    rootCommand.setParamsJson(gson.toJson(params));
                    store(rootCommand);
                }
            }
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

        // 4. Post-deploy: auto-provision an Ambari view instance if the service spec defines it.
        //    This creates a linked SQL-ASSISTANT-VIEW instance for each sql-assistant-* Helm release,
        //    removing all manual wiring after deploy.
        if (request.getServiceKey() != null && !request.getServiceKey().isBlank()) {
            try {
                StackDefinitionService stackDefSvc = new StackDefinitionService(this.ctx);
                var serviceDef = stackDefSvc.getServiceDefinition(request.getServiceKey());
                if (serviceDef != null && serviceDef.postDeploy != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> viewSpec =
                            (Map<String, Object>) serviceDef.postDeploy.get("ambariViewInstance");
                    if (viewSpec != null) {
                        this.commandPlanFactory.createAmbariViewProvisionCommand(
                                rootCommand,
                                viewSpec,
                                request.getReleaseName(),
                                request.getNamespace()
                        );
                        LOG.info("Queued AMBARI_VIEW_PROVISION post-deploy step for release '{}' (serviceKey={})",
                                request.getReleaseName(), request.getServiceKey());
                    }

                    // Ranger TagSync source registration runs post-deploy because the
                    // OM_RANGER_TAGSYNC_REGISTER step body exec-s into an airflow
                    // scheduler pod to mint the OM bot JWT — the pod doesn't exist
                    // until the helm install completes. Gating + plumbing of
                    // _tagSyncSettings into params happens inside planRangerTagSyncSources.
                    if (request.getRanger() != null) {
                        // Thread the selected platform context into params so the TagSync step
                        // resolves Ranger ownership (managed vs external) from the context — the
                        // per-service Ranger discovery fields were removed. _cluster/_baseUri/
                        // _callerHeaders are already present in params (managed branch uses them).
                        String tagSyncPcId = stringValue(ConfigResolutionService.getByDottedPath(
                                request.getFormValues() != null ? request.getFormValues()
                                        : Collections.emptyMap(), "platformContextId"));
                        if (!tagSyncPcId.isBlank()) params.put("_platformContextId", tagSyncPcId);
                        planRangerTagSyncSources(rootCommand, /* childCommands ignored, method self-persists */ null, request, params);
                    }

                    // Base Hive ingestion: layer 1 of Atlas+Hive federation. Queue BEFORE the
                    // Atlas federation step below so the Hive DatabaseService + table entities
                    // exist before Atlas tries to enrich them (the Atlas connector is
                    // enrichment-only). Declared via platformOp (op=hive.baseIngestion) or the
                    // postDeploy.baseIngestionHive marker, gated on baseIngestion.hiveEnabled=true.
                    boolean hiveBaseDeclared = hasPlatformOp(serviceDef, "hive.baseIngestion")
                            || (serviceDef.postDeploy != null && serviceDef.postDeploy.get("baseIngestionHive") != null);
                    if (hiveBaseDeclared) {
                        Object hiveEnabledRaw = ConfigResolutionService.getByDottedPath(
                                request.getValues() != null ? request.getValues() : Collections.emptyMap(),
                                "baseIngestion.hiveEnabled");
                        if (hiveEnabledRaw != null && "true".equalsIgnoreCase(String.valueOf(hiveEnabledRaw))) {
                            Map<String, Object> hiveParams = new LinkedHashMap<>();
                            hiveParams.put("releaseName", request.getReleaseName());
                            hiveParams.put("namespace", request.getNamespace());
                            hiveParams.put("serviceKey", request.getServiceKey());
                            String hivePcId = stringValue(ConfigResolutionService.getByDottedPath(
                                    request.getFormValues() != null ? request.getFormValues()
                                            : Collections.emptyMap(), "platformContextId"));
                            if (!hivePcId.isBlank()) hiveParams.put("_platformContextId", hivePcId);
                            if (params.get("_cluster") != null) hiveParams.put("_cluster", params.get("_cluster"));
                            if (params.get("_baseUri") != null) hiveParams.put("_baseUri", params.get("_baseUri"));
                            if (params.get("_callerHeaders") != null) hiveParams.put("_callerHeaders", params.get("_callerHeaders"));
                            this.commandPlanFactory.createOmHiveBaseIngestionRegister(rootCommand, hiveParams);
                            LOG.info("Queued OM_HIVE_BASE_INGESTION_REGISTER post-deploy step for release '{}'",
                                    request.getReleaseName());
                        }
                    }

                    // Superset (or any service building a Hive database connection) needs its
                    // Kerberos principal granted SELECT on the Hive Ranger repo: Superset runs
                    // schema/column reflection as the SERVICE principal (queries impersonate the
                    // end-user, but reflection does not), so without this grant Ranger denies
                    // DESCRIBE and tables list with no columns. Declared via platformOp
                    // (op=ranger.hiveGrant), gated on hiveDb.enabled=true. Delegated to the Ambari
                    // server so the view never handles the Ranger admin password.
                    if (hasPlatformOp(serviceDef, "ranger.hiveGrant")) {
                        Object hiveDbEnabledRaw = ConfigResolutionService.getByDottedPath(
                                request.getValues() != null ? request.getValues() : Collections.emptyMap(),
                                "hiveDb.enabled");
                        if (hiveDbEnabledRaw == null) {
                            hiveDbEnabledRaw = ConfigResolutionService.getByDottedPath(
                                    request.getFormValues() != null ? request.getFormValues()
                                            : Collections.emptyMap(), "hiveDb.enabled");
                        }
                        if (hiveDbEnabledRaw != null && "true".equalsIgnoreCase(String.valueOf(hiveDbEnabledRaw))) {
                            // Ranger short name = <kerberos serviceName>-<namespace> (the realm is
                            // stripped by Ranger's auth_to_local). serviceName comes from the
                            // service.json kerberos[] block (e.g. "superset-dashboard").
                            String kerbServiceName = request.getReleaseName();
                            if (serviceDef.kerberos != null && !serviceDef.kerberos.isEmpty()) {
                                kerbServiceName = resolveStringValue(
                                        serviceDef.kerberos.get(0).get("serviceName"), kerbServiceName);
                            }
                            String principal = kerbServiceName + "-" + request.getNamespace();

                            Map<String, Object> grantParams = new LinkedHashMap<>();
                            grantParams.put("releaseName", request.getReleaseName());
                            grantParams.put("namespace", request.getNamespace());
                            grantParams.put("serviceKey", request.getServiceKey());
                            grantParams.put("_principal", principal);
                            grantParams.put("_hiveRangerServiceName", cluster + "_hive");
                            if (params.get("_cluster") != null) grantParams.put("_cluster", params.get("_cluster"));
                            if (params.get("_baseUri") != null) grantParams.put("_baseUri", params.get("_baseUri"));
                            if (params.get("_callerHeaders") != null) grantParams.put("_callerHeaders", params.get("_callerHeaders"));
                            this.commandPlanFactory.createRangerPolicyGrantHiveRead(rootCommand, grantParams);
                            LOG.info("Queued RANGER_POLICY_GRANT_HIVE_READ post-deploy step for release '{}' "
                                    + "(principal='{}', hiveService='{}')",
                                    request.getReleaseName(), principal, cluster + "_hive");
                        }
                    }

                    // Atlas federation: queue only when the wizard toggle is on. The step
                    // body re-checks atlasFederation.enabled at execute time so a reapply
                    // after the toggle was flipped off still fails loudly instead of
                    // silently succeeding.
                    //
                    // ----- Auth-mode-aware dispatch -----
                    // For Ambari-managed Atlas (the host operator's Atlas is part of the same
                    // Ambari cluster KDPS is running in), we discover the Atlas auth + ACL
                    // modes from Atlas's stack config and queue the right pre-OM steps:
                    //
                    //   basic auth   → ATLAS_USER_PROVISION_OM
                    //   simple ACL   → ATLAS_SIMPLE_AUTHZ_GRANT_OM
                    //   ranger ACL   → RANGER_POLICY_CREATE_ATLAS_OM_READ
                    //
                    // For an external Atlas (URL override + operator-supplied creds) we skip
                    // all Atlas-side provisioning and just queue OM_ATLAS_FEDERATION_REGISTER.
                    //
                    // All paths end with OM_ATLAS_FEDERATION_REGISTER as the FINAL step so
                    // the OM REST call happens AFTER Atlas-side wiring is in place.
                    // Atlas federation fires when declared either as a platformOp
                    // (op=atlas.federation, the framework path) OR via the legacy
                    // postDeploy.atlasFederation block (backward compat) — and the toggle is on.
                    boolean federationDeclared = hasPlatformOp(serviceDef, "atlas.federation")
                            || (serviceDef.postDeploy != null && serviceDef.postDeploy.get("atlasFederation") != null);
                    if (federationDeclared) {
                        Object enabledRaw = ConfigResolutionService.getByDottedPath(
                                request.getValues() != null ? request.getValues() : Collections.emptyMap(),
                                "atlasFederation.enabled");
                        if (enabledRaw != null && "true".equalsIgnoreCase(String.valueOf(enabledRaw))) {
                            try {
                                queueAtlasFederationProvisioningSteps(
                                        rootCommand, request, params, cluster, ambariActionClient);
                            } catch (Exception ex) {
                                LOG.warn("Atlas federation provisioning dispatch failed for '{}': {} "
                                        + "(continuing with bare OM_ATLAS_FEDERATION_REGISTER step)",
                                        request.getReleaseName(), ex.toString());
                            }
                            // Always queue the OM-side REST registration last. Thread the platform
                            // context + Ambari params so the register step resolves Atlas URL/creds
                            // from the context instead of per-service form fields.
                            Map<String, Object> atlasParams = new LinkedHashMap<>();
                            atlasParams.put("releaseName", request.getReleaseName());
                            atlasParams.put("namespace", request.getNamespace());
                            atlasParams.put("serviceKey", request.getServiceKey());
                            String pcId = stringValue(ConfigResolutionService.getByDottedPath(
                                    request.getFormValues() != null ? request.getFormValues()
                                            : Collections.emptyMap(), "platformContextId"));
                            if (!pcId.isBlank()) atlasParams.put("_platformContextId", pcId);
                            if (params.get("_cluster") != null) atlasParams.put("_cluster", params.get("_cluster"));
                            if (params.get("_baseUri") != null) atlasParams.put("_baseUri", params.get("_baseUri"));
                            if (params.get("_callerHeaders") != null) atlasParams.put("_callerHeaders", params.get("_callerHeaders"));
                            this.commandPlanFactory.createOmAtlasFederationRegister(
                                    rootCommand, atlasParams);
                            LOG.info("Queued OM_ATLAS_FEDERATION_REGISTER post-deploy step for release '{}'",
                                    request.getReleaseName());
                        }
                    }
                }
            } catch (Exception ex) {
                LOG.warn("Could not queue post-deploy Ambari view provision step for '{}': {}",
                        request.getReleaseName(), ex.toString());
            }
        }

        // Publish correlation ConfigMap so the mutating webhook can attach keytab requests to this command tree.
        if (isWebhookInjectionMode(kerberosInjectionMode)) {
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
        } else {
            LOG.info("Skipping command correlation ConfigMap; Kerberos injection mode is {}", kerberosInjectionMode);
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
        // Show the command's stable title as the operation name (immutable across the run),
        // not the live status text — progress is conveyed by `state`. Mirrors listChildren /
        // listCommands so every view (header, list, tree) shows the same fixed title rather
        // than mutating to "Running: …" / "All steps completed." / an error string.
        s.message = (e.getTitle() != null && !e.getTitle().isBlank()) ? e.getTitle() : eStatus.getMessage();
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
                if (!cs.hasChildren && cs.state == CommandState.RUNNING && cs.percent <= 0) {
                    // Leaf commands do not have a natural percentage; surface a midpoint while running.
                    cs.percent = 50;
                }
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

        int totalChildCount = childIds.size();
        int completedChildCount = 0;
        Integer firstRunningChildIndex = null;
        Integer firstPendingChildIndex = null;

        for (int childIndex = 0; childIndex < childIds.size(); childIndex++) {
            String childId = childIds.get(childIndex);
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

            CommandState childState;
            try {
                childState = CommandState.valueOf(st.getState());
            } catch (IllegalArgumentException ex) {
                LOG.debug("Unknown state '{}' for child {}", st.getState(), childId);
                continue;
            }

            if (childState == CommandState.SUCCEEDED || childState == CommandState.FAILED || childState == CommandState.CANCELED) {
                completedChildCount++;
                continue;
            }

            if (childState == CommandState.RUNNING && firstRunningChildIndex == null) {
                firstRunningChildIndex = childIndex;
            }
            if (childState == CommandState.PENDING && firstPendingChildIndex == null) {
                firstPendingChildIndex = childIndex;
            }
        }

        if (completedChildCount >= totalChildCount) {
            return 100;
        }

        if (firstRunningChildIndex != null) {
            int runningPercent = (int) Math.round(((firstRunningChildIndex + 0.5) * 100.0) / totalChildCount);
            int boundedRunningPercent = Math.min(99, Math.max(1, runningPercent));
            LOG.debug("Computed in-progress percentage for {} -> runningIndex={}, total={}, percent={}%",
                    cmd.getId(), firstRunningChildIndex, totalChildCount, boundedRunningPercent);
            return boundedRunningPercent;
        }

        if (firstPendingChildIndex != null) {
            int pendingPercent = (int) Math.round((firstPendingChildIndex * 100.0) / totalChildCount);
            int boundedPendingPercent = Math.min(99, Math.max(0, pendingPercent));
            LOG.debug("Computed pending percentage for {} -> pendingIndex={}, total={}, percent={}%",
                    cmd.getId(), firstPendingChildIndex, totalChildCount, boundedPendingPercent);
            return boundedPendingPercent;
        }

        int completedPercent = (int) Math.round(completedChildCount * 100.0 / totalChildCount);
        int boundedCompletedPercent = Math.min(99, Math.max(0, completedPercent));
        LOG.debug("Computed completion percentage for {} -> {}/{} = {}%",
                cmd.getId(), completedChildCount, totalChildCount, boundedCompletedPercent);
        return boundedCompletedPercent;
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
            // Re-record the endpoints snapshot from live cluster state before marking
            // SUCCEEDED. The install-time snapshot is computed from service.json url
            // templates which hardcode http:// — for chart-managed TLS releases (e.g.
            // GitLab) the chart's Ingress is what actually decides the scheme. Live
            // discovery reads spec.tls[] and produces the truthful URL.
            refreshEndpointSnapshotFromLive(root, rootParams);
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
        // Tee everything logged during this step into the step's own log (see appendCommandLog).
        activeStepLogId.set(child.getId());
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
                    // Only label the namespace for webhook admission when webhook mode is enabled.
                    String kerberosInjectionMode = String.valueOf(
                            childParams.getOrDefault("kerberosInjectionMode", KERBEROS_INJECTION_MODE_WEBHOOK)
                    );
                    boolean kerberosClusterEnabled = isKerberosClusterEnabled(childParams.get("kerberosClusterEnabled"));
                    if (isWebhookInjectionMode(kerberosInjectionMode) && kerberosClusterEnabled) {
                        this.kubernetesService.ensureWebhookEnabledNamespace(namespace);
                    } else {
                        LOG.info("Skipping webhook label on namespace {} (Kerberos mode={}, clusterEnabled={})",
                                namespace, kerberosInjectionMode, kerberosClusterEnabled);
                    }

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
                        String workingDirectory = this.kubernetesService.getConfigurationService().getExtractDir();
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
                    // Dependency namespaces only need webhook labels in webhook mode when Kerberos is enabled.
                    String kerberosInjectionMode = String.valueOf(
                            childParams.getOrDefault("kerberosInjectionMode", KERBEROS_INJECTION_MODE_WEBHOOK)
                    );
                    boolean kerberosClusterEnabled = isKerberosClusterEnabled(childParams.get("kerberosClusterEnabled"));
                    if (isWebhookInjectionMode(kerberosInjectionMode) && kerberosClusterEnabled) {
                        this.kubernetesService.ensureWebhookEnabledNamespace(namespace);
                    } else {
                        LOG.info("Skipping webhook label on dependency namespace {} (Kerberos mode={}, clusterEnabled={})",
                                namespace, kerberosInjectionMode, kerberosClusterEnabled);
                    }
                    String releaseName = (String) childParams.get("releaseName");
                    if (secretName != null) {
                        LOG.info("Ensuring image pull secret: {} in namespace: {} for service accounts: {} ", secretName, namespace, serviceAccounts);
                        this.helmService.ensureImagePullSecretFromRepo(repoId, namespace, (String) secretName, serviceAccounts);
                        overrideProperties.put("imagePullSecrets[0].name", (String) secretName);
                        applyImagePullSecretTargets(secretName, childParams.get("serviceKey"), overrideProperties);
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
                        applyImagePullSecretTargets(secretName, childParams.get("serviceKey"), overrideProperties);
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
                case DEPENDENCY_SATISFIED -> {
                    String releaseName = (String) childParams.get("releaseName");
                    String namespace = (String) childParams.get("namespace");
                    String reason = (String) childParams.get("reason");
                    LOG.info("Dependency {} already satisfied in {}. {}", releaseName, namespace, reason);
                    appendCommandLog(id, "Dependency already satisfied: release=" + releaseName + " namespace=" + namespace);
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
                    Map<String, Map<String, Object>> rangerSpec =
                            (Map<String, Map<String, Object>>) childParams.get("_rangerSpec");
                    boolean kerberosRangerSpec = isKerberosRangerSpec(rangerSpec);

                    if (pluginUserName != null && !pluginUserName.isBlank()
                            && (pluginUserPassword == null || pluginUserPassword.isBlank())) {
                        if (kerberosRangerSpec) {
                            LOG.info("Skipping Ranger plugin user creation for repo '{}' because Kerberos configuration is present.",
                                    rangerRepositoryName);
                            pluginUserName = null;
                            pluginUserPassword = null;
                        } else {
                            throw new IllegalStateException(
                                    "Ranger plugin user password is required when plugin user name is provided.");
                        }
                    }


                    // Default serviceType unless overridden from params
                    String serviceType = resolveStringValue(childParams.get("_rangerServiceType"), null);
                    if (serviceType == null || serviceType.isBlank()) {
                        String serviceKey = resolveStringValue(childParams.get("serviceKey"), "unknown");
                        serviceType = serviceKey.toLowerCase(Locale.ROOT);
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
                case OM_RANGER_TAGSYNC_REGISTER -> {
                    String result = registerRangerTagSyncSource(childParams);
                    if (result != null) childSt.setResultJson(result);
                }
                case OM_ATLAS_FEDERATION_REGISTER -> {
                    String result = registerOmAtlasFederation(childParams);
                    if (result != null) childSt.setResultJson(result);
                }
                case OM_HIVE_BASE_INGESTION_REGISTER -> {
                    String result = registerOmHiveBaseIngestion(childParams);
                    if (result != null) childSt.setResultJson(result);
                }
                case ATLAS_USER_PROVISION_OM -> {
                    String result = provisionAtlasUserForOm(childParams);
                    if (result != null) childSt.setResultJson(result);
                }
                case ATLAS_SIMPLE_AUTHZ_GRANT_OM -> {
                    String result = grantAtlasSimpleAuthzForOm(childParams);
                    if (result != null) childSt.setResultJson(result);
                }
                case RANGER_POLICY_CREATE_ATLAS_OM_READ -> {
                    String result = createRangerAtlasReadPolicyForOm(childParams);
                    if (result != null) childSt.setResultJson(result);
                }
                case RANGER_POLICY_GRANT_VIA_AMBARI -> {
                    String result = grantRangerAtlasReadViaAmbari(childParams);
                    if (result != null) childSt.setResultJson(result);
                }
                case RANGER_POLICY_GRANT_HIVE_READ -> {
                    String result = grantRangerHiveReadViaAmbari(childParams);
                    if (result != null) childSt.setResultJson(result);
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
                    // Dependency namespaces only need webhook labels in webhook mode when Kerberos is enabled.
                    String kerberosInjectionMode = String.valueOf(
                            childParams.getOrDefault("kerberosInjectionMode", KERBEROS_INJECTION_MODE_WEBHOOK)
                    );
                    boolean kerberosClusterEnabled = isKerberosClusterEnabled(childParams.get("kerberosClusterEnabled"));
                    if (isWebhookInjectionMode(kerberosInjectionMode) && kerberosClusterEnabled) {
                        this.kubernetesService.ensureWebhookEnabledNamespace(namespace);
                    } else {
                        LOG.info("Skipping webhook label on dependency namespace {} (Kerberos mode={}, clusterEnabled={})",
                                namespace, kerberosInjectionMode, kerberosClusterEnabled);
                    }
                    Object secretName = childParams.get("secretName");
                    if (secretName != null) {
                        LOG.info("Ensuring image pull secret: {} in namespace: {} for service accounts: {} ", secretName, namespace, null);
                        this.helmService.ensureImagePullSecretFromRepo(repoId, namespace, (String) secretName, null);
                        overrideProperties.put("imagePullSecrets[0].name", (String) secretName);
                        // Bitnami sub-charts (e.g. Redis inside GitLab) expect global.imagePullSecrets as a list of
                        // plain secret names (strings), not {name: ...} maps. Using the [0] index without .name
                        // produces the correct string-list format that renderPullSecrets handles.
                        overrideProperties.put("global.imagePullSecrets[0]", (String) secretName);
                        applyImagePullSecretTargets(secretName, childParams.get("serviceKey"), overrideProperties);
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
                    // Main release namespaces only need webhook labels in webhook mode when Kerberos is enabled.
                    String kerberosInjectionMode = String.valueOf(
                            childParams.getOrDefault("kerberosInjectionMode", KERBEROS_INJECTION_MODE_WEBHOOK)
                    );
                    boolean kerberosClusterEnabled = isKerberosClusterEnabled(childParams.get("kerberosClusterEnabled"));
                    if (isWebhookInjectionMode(kerberosInjectionMode) && kerberosClusterEnabled) {
                        this.kubernetesService.ensureWebhookEnabledNamespace(namespace);
                    } else {
                        LOG.info("Skipping webhook label on namespace {} (Kerberos mode={}, clusterEnabled={})",
                                namespace, kerberosInjectionMode, kerberosClusterEnabled);
                    }
                    Object secretName = childParams.get("secretName");
                    if (secretName != null) {
                        LOG.info("Ensuring image pull secret: {} in namespace: {} for service accounts: {} ", secretName, namespace, null);
                        this.helmService.ensureImagePullSecretFromRepo(repoId, namespace, (String) secretName, null);
                        overrideProperties.put("imagePullSecrets[0].name", (String) secretName);
                        overrideProperties.put("global.imagePullSecrets[0]", (String) secretName);
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
                case HELM_UPGRADE -> {
                    String chartName = (String) childParams.get("chart");
                    String namespace = (String) childParams.get("namespace");
                    String releaseName = (String) childParams.get("releaseName");
                    String version = (String) childParams.get("version");
                    String imageGlobalRegistryProperty = (String) childParams.get("imageGlobalRegistryProperty");

                    // Existing release values are pre-loaded by submitUpgrade() via the standard
                    // valuesPath mechanism so loadValuesFromParams returns the deployed values.
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
                    this.configResolutionService.mergeDynamicOverrides(childParams, overrideProperties);

                    // Re-apply image registry override so the upgrade pulls from the same registry.
                    if (repoId != null && !repoId.isBlank()) {
                        if (imageGlobalRegistryProperty != null && !imageGlobalRegistryProperty.isEmpty()) {
                            overrideProperties.put(imageGlobalRegistryProperty,
                                    this.helmService.getRepositoryService().getEffectiveImageRegistry(repoId));
                        } else {
                            overrideProperties.put("global.imageRegistry",
                                    this.helmService.getRepositoryService().getEffectiveImageRegistry(repoId));
                        }
                    }

                    Object secretName = childParams.get("secretName");
                    if (secretName != null) {
                        overrideProperties.put("imagePullSecrets[0].name", (String) secretName);
                        overrideProperties.put("global.imagePullSecrets[0]", (String) secretName);
                        applyImagePullSecretTargets(secretName, childParams.get("serviceKey"), overrideProperties);
                    }

                    appendCommandLog(id, "Upgrading release: " + releaseName + " chart=" + chartName + " version=" + version + " repoId=" + repoId);
                    ScheduledFuture<?> heartbeatUpgrade = startHeartbeat(id, () -> {
                        CommandStatusEntity st = findCommandStatusById(child.getCommandStatusId());
                        return st != null && CommandState.RUNNING.name().equals(st.getState());
                    });
                    try {
                        runWithCancellation(id, child.getCommandStatusId(), () ->
                                this.helmService.deployOrUpgrade(
                                        chartName,
                                        releaseName,
                                        namespace, valuesMap, overrideProperties,
                                        this.kubernetesService.getConfigurationService().getKubeconfigContents(),
                                        repoId, version, false));
                        this.kubernetesService.ensureReleaseLabelsOnIngresses(namespace, releaseName);
                    } finally {
                        if (heartbeatUpgrade != null) heartbeatUpgrade.cancel(false);
                    }

                    // Refresh release metadata with the new chart version so the UI shows it.
                    try {
                        ReleaseMetadataService metadataService = new ReleaseMetadataService(this.ctx);
                        K8sReleaseEntity existing = metadataService.find(namespace, releaseName);
                        if (existing != null) {
                            metadataService.recordInstallOrUpgrade(
                                    namespace,
                                    releaseName,
                                    existing.getServiceKey(),
                                    existing.getChartRef(),
                                    existing.getRepoId(),
                                    version,
                                    valuesMap,
                                    id,
                                    null,
                                    existing.getGlobalConfigVersion(),
                                    existing.getSecurityProfile(),
                                    existing.getSecurityProfileHash(),
                                    existing.getDeploymentMode(),
                                    existing.getGitCommitSha(),
                                    existing.getGitBranch(),
                                    existing.getGitPath(),
                                    existing.getGitRepoUrl(),
                                    existing.getGitCredentialAlias(),
                                    existing.getGitCommitMode(),
                                    existing.getGitPrUrl(),
                                    existing.getGitPrNumber(),
                                    existing.getGitPrState()
                            );
                            LOG.info("Updated release metadata for {}/{} to chart version {}", namespace, releaseName, version);
                        }
                    } catch (Exception ex) {
                        LOG.warn("Failed to refresh release metadata after upgrade for {}/{}: {}", namespace, releaseName, ex.toString());
                    }
                }
                case INGRESS_TLS_SELFSIGN -> {
                    String namespace = (String) childParams.get("namespace");
                    String secretName = (String) childParams.get("secretName");
                    String ingressHost = (String) childParams.get("ingressHost");
                    String caMode = String.valueOf(childParams.getOrDefault("ca", "ambariInternal"));
                    String caName = (String) childParams.get("caName"); // for companyUploaded
                    Integer validityDays = null;
                    Object vd = childParams.get("validityDays");
                    if (vd instanceof Number) validityDays = ((Number) vd).intValue();
                    else if (vd instanceof String s && !s.isBlank()) {
                        try { validityDays = Integer.parseInt(s.trim()); } catch (NumberFormatException ignore) {}
                    }
                    // SANs: include any cluster-internal service name caller may want trusted.
                    List<String> extraSans = new ArrayList<>();
                    Object sansRaw = childParams.get("extraSans");
                    if (sansRaw instanceof List<?> l) {
                        for (Object o : l) if (o != null) extraSans.add(String.valueOf(o));
                    }

                    if (namespace == null || namespace.isBlank()
                            || secretName == null || secretName.isBlank()
                            || ingressHost == null || ingressHost.isBlank()) {
                        throw new IllegalStateException("INGRESS_TLS_SELFSIGN: namespace, secretName, and ingressHost are required");
                    }

                    WebHookConfigurationService webHookCfg = new WebHookConfigurationService(ctx, this.kubernetesService);
                    WebHookConfigurationService.CertificateAuthorityMaterial ca;
                    if ("companyUploaded".equalsIgnoreCase(caMode)) {
                        if (caName == null || caName.isBlank()) {
                            throw new IllegalStateException("INGRESS_TLS_SELFSIGN: caName is required when ca=companyUploaded");
                        }
                        CaRegistryService caRegistry = new CaRegistryService(ctx, this.kubernetesService);
                        CaRegistryService.CaEntry entry = caRegistry.get(caName);
                        ca = webHookCfg.loadUploadedCertificateAuthority(entry.namespace(), entry.secretName());
                        appendCommandLog(id, "INGRESS_TLS_SELFSIGN: using company CA '" + caName + "' (subject=" + entry.subject() + ")");
                    } else {
                        ca = webHookCfg.ensureAmbariCertificateAuthority();
                        appendCommandLog(id, "INGRESS_TLS_SELFSIGN: using Ambari Internal CA (testing trust)");
                    }

                    this.kubernetesService.createNamespace(namespace);
                    Map<String, String> labels = new LinkedHashMap<>();
                    labels.put("ambari.clemlab.com/release", (String) childParams.getOrDefault("releaseName", ""));
                    labels.put("ambari.clemlab.com/ca-mode", caMode);
                    if (caName != null) labels.put("ambari.clemlab.com/ca-name",
                            caName.toLowerCase().replaceAll("[^a-z0-9._-]", "-"));

                    var result = webHookCfg.issueIngressTlsSecret(namespace, secretName, ingressHost,
                            extraSans, validityDays, ca, labels);
                    appendCommandLog(id, "INGRESS_TLS_SELFSIGN: wrote " + namespace + "/" + secretName +
                            " (cert chars=" + result.certificatePem().length() + ", key chars=" + result.privateKeyPem().length() + ")");
                }
                case INGRESS_TLS_CERTMANAGER -> {
                    // Two operations supported via the `operation` param:
                    //  - "create": create or update a cert-manager.io/v1 Certificate CR (Day 4 path,
                    //    invoked from the install pipeline).
                    //  - "renew":  force cert-manager to re-issue an existing Certificate (Day 3
                    //    path, invoked from the TLS_RENEW root command). We do this by deleting
                    //    the leaf Secret — the cert-manager controller observes the missing
                    //    Secret and mints a fresh one against the same Certificate spec. This
                    //    is the only force-renew trigger that works across every cert-manager
                    //    version we ship (earlier code annotated `issue-temporary-certificate`
                    //    which is for *initial* issuance, not renewal — pure no-op for an
                    //    already-issued cert).
                    String namespace = (String) childParams.get("namespace");
                    String operation = String.valueOf(childParams.getOrDefault("operation", "create"));
                    if ("renew".equals(operation)) {
                        String secretName = (String) childParams.get("secretName");
                        try {
                            // Drop a marker annotation on the Certificate first so an operator
                            // can correlate the renew in audit logs / kubectl describe. Done
                            // before delete so the marker is on the surviving CR. Best-effort —
                            // if the patch fails we still want the delete to happen.
                            try {
                                this.kubernetesService.patchCertManagerCertificate(namespace, secretName,
                                        Map.of("metadata", Map.of("annotations",
                                                Map.of("ambari.clemlab.com/renew-requested-at", Instant.now().toString()))));
                            } catch (Exception annotateEx) {
                                LOG.warn("INGRESS_TLS_CERTMANAGER renew: annotate failed (continuing with secret delete): {}", annotateEx.toString());
                            }
                            boolean existed = this.kubernetesService.getSecret(namespace, secretName) != null;
                            this.kubernetesService.deleteSecret(namespace, secretName);
                            appendCommandLog(id, "INGRESS_TLS_CERTMANAGER: " +
                                    (existed
                                            ? "deleted Secret " + namespace + "/" + secretName + "; cert-manager will re-issue"
                                            : "Secret " + namespace + "/" + secretName + " was already absent; cert-manager will create it on next reconcile"));
                        } catch (Exception ex) {
                            throw new IllegalStateException("Failed to force renew Certificate "
                                    + namespace + "/" + secretName + ": " + ex.getMessage(), ex);
                        }
                    } else {
                        // Create / update path. Reads spec fields from params.
                        String certName = String.valueOf(childParams.getOrDefault("certName", childParams.get("secretName")));
                        String secretName = (String) childParams.get("secretName");
                        String issuerName = (String) childParams.get("issuerName");
                        String issuerKind = String.valueOf(childParams.getOrDefault("issuerKind", "ClusterIssuer"));
                        List<String> dnsNames = childParams.get("dnsNames") instanceof List<?> l
                                ? l.stream().map(String::valueOf).toList()
                                : List.of();
                        Integer durationHours = childParams.get("durationHours") instanceof Number n
                                ? n.intValue() : 2160; // 90 days
                        if (issuerName == null || issuerName.isBlank()) {
                            throw new IllegalStateException("INGRESS_TLS_CERTMANAGER: issuerName is required");
                        }
                        this.kubernetesService.applyCertManagerCertificate(namespace, certName, secretName,
                                issuerName, issuerKind, dnsNames, durationHours);
                        appendCommandLog(id, "INGRESS_TLS_CERTMANAGER: applied Certificate "
                                + namespace + "/" + certName + " issuer=" + issuerKind + "/" + issuerName);
                    }
                }
                case INGRESS_TLS_EXTERNAL_SECRET -> {
                    String namespace = (String) childParams.get("namespace");
                    String operation = String.valueOf(childParams.getOrDefault("operation", "create"));
                    if ("refresh".equals(operation)) {
                        String secretName = (String) childParams.get("secretName");
                        // ESO refresh: annotate the ExternalSecret with force-sync timestamp.
                        try {
                            this.kubernetesService.patchExternalSecret(namespace, secretName,
                                    Map.of("metadata", Map.of("annotations",
                                            Map.of("force-sync", Instant.now().toString()))));
                            appendCommandLog(id, "INGRESS_TLS_EXTERNAL_SECRET: forced refresh on "
                                    + namespace + "/" + secretName);
                        } catch (Exception ex) {
                            throw new IllegalStateException("Failed to refresh ExternalSecret "
                                    + namespace + "/" + secretName + ": " + ex.getMessage(), ex);
                        }
                    } else {
                        // Create path: write an ExternalSecret CR.
                        String esName = String.valueOf(childParams.getOrDefault("externalSecretName", childParams.get("secretName")));
                        String secretName = (String) childParams.get("secretName");
                        String storeName = (String) childParams.get("secretStoreName");
                        String storeKind = String.valueOf(childParams.getOrDefault("secretStoreKind", "ClusterSecretStore"));
                        String remoteKey = (String) childParams.get("remoteKey");
                        String refreshInterval = String.valueOf(childParams.getOrDefault("refreshInterval", "1h"));
                        if (storeName == null || storeName.isBlank() || remoteKey == null || remoteKey.isBlank()) {
                            throw new IllegalStateException("INGRESS_TLS_EXTERNAL_SECRET: secretStoreName + remoteKey are required");
                        }
                        this.kubernetesService.applyExternalSecret(namespace, esName, secretName,
                                storeName, storeKind, remoteKey, refreshInterval);
                        appendCommandLog(id, "INGRESS_TLS_EXTERNAL_SECRET: applied ExternalSecret "
                                + namespace + "/" + esName + " store=" + storeKind + "/" + storeName);
                    }
                }
                case TLS_RENEW -> {
                    // Root command — no inline work; child steps already scheduled when planned.
                    appendCommandLog(id, "TLS_RENEW: orchestrating child step(s)");
                }
                case AMBARI_VIEW_PROVISION -> {
                    String viewName      = (String) childParams.get("viewName");
                    String viewVersion   = (String) childParams.get("viewVersion");
                    String instanceName  = (String) childParams.get("instanceName");
                    String instanceLabel = (String) childParams.get("instanceLabel");
                    String instanceDesc  = (String) childParams.get("instanceDescription");
                    String serviceUrl    = (String) childParams.get("serviceUrl");
                    String baseUriStr    = (String) childParams.get("_baseUri");

                    if (baseUriStr == null || baseUriStr.isBlank()) {
                        throw new IllegalStateException("AMBARI_VIEW_PROVISION: _baseUri is missing from params");
                    }
                    String ambariApiBase = URI.create(baseUriStr).resolve("/api/v1").toString();
                    Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(childParams.get("_callerHeaders"));

                    LOG.info("AMBARI_VIEW_PROVISION: view={}/{} instance={} serviceUrl={}",
                            viewName, viewVersion, instanceName, serviceUrl);
                    appendCommandLog(id, "Provisioning Ambari view instance "
                            + viewName + "/" + viewVersion + "/" + instanceName
                            + " → " + serviceUrl);

                    var ambariClient = new AmbariActionClient(ctx, ambariApiBase, authHeaders);
                    ambariClient.createOrUpdateViewInstance(
                            viewName,
                            viewVersion,
                            instanceName,
                            instanceLabel,
                            instanceDesc,
                            serviceUrl != null
                                    ? Map.of("sql.assistant.semantic.service.url", serviceUrl)
                                    : Map.of()
                    );
                    appendCommandLog(id, "View instance '" + instanceName + "' provisioned successfully.");
                    LOG.info("AMBARI_VIEW_PROVISION completed for instance '{}'", instanceName);
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
                        LOG.debug("Persisted caller headers keys: {}", AmbariActionClient.describePersistedHeaders(callerHeadersObj));
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

                    LOG.info("Ambari request id={} for principal={} cluster={} baseUri={} completed; fetching payload metadata…",
                            reqId, principalFqdn, cluster, baseUri);

                    // Fetch with a short retry loop (handles persist/visibility lag)
                    Optional<AmbariActionClient.AdhocKeytabPayloadMetadata> payloadMetadataOpt =
                            ambariActionClient.fetchIssuedKeytabPayloadMetadataFromTasks(reqId);
                    for (int i = 0; i < 10 && payloadMetadataOpt.isEmpty(); i++) {   // ~5 seconds total
                        try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        payloadMetadataOpt = ambariActionClient.fetchIssuedKeytabPayloadMetadataFromTasks(reqId);
                    }

                    AmbariActionClient.AdhocKeytabPayloadMetadata payloadMetadata = payloadMetadataOpt.orElseThrow(() ->
                            new IllegalStateException("Ambari action " + reqId +
                                    " completed but payload_ref not found in structured_out."));

                    Map<String, Object> res = new LinkedHashMap<>();
                    res.put("requestId", reqId);
                    res.put("payloadRef", payloadMetadata.payloadRef());
                    res.put("payloadSha256", payloadMetadata.payloadSha256());
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
                        LOG.debug("Persisted caller headers keys: {}", AmbariActionClient.describePersistedHeaders(callerHeadersObj));
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

                    String issuerId = (String) childParams.get("keytabIssuerId");
                    if (issuerId == null || issuerId.isBlank()) {
                        issuerId = siblings.stream()
                                .filter(cid -> {
                                    CommandEntity c = findCommandById(cid);
                                    return c != null && CommandType.KEYTAB_ISSUE_PRINCIPAL.name().equals(c.getType());
                                })
                                .findFirst()
                                .orElse(null);
                    }

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
                        throw new IllegalStateException("Issuer step has no resultJson with payloadRef");
                    }

                    Map<String,Object> issuerRes =
                            gson.fromJson(issuerSt.getResultJson(), java.util.Map.class);
                    String payloadRef = issuerRes == null ? null : (String) issuerRes.get("payloadRef");
                    if (payloadRef == null || payloadRef.isBlank()) {
                        throw new IllegalStateException("Issuer resultJson does not contain payloadRef");
                    }
                    String payloadSha256 = issuerRes == null ? null : (String) issuerRes.get("payloadSha256");
                    if (payloadSha256 == null || payloadSha256.isBlank()) {
                        throw new IllegalStateException("Issuer resultJson does not contain payloadSha256");
                    }

                    String baseUriStr = (String) childParams.get("_baseUri");
                    if (baseUriStr == null || baseUriStr.isBlank()) {
                        throw new IllegalStateException("Missing baseUri in command params (_baseUri)");
                    }
                    String ambariApiBase = URI.create(baseUriStr).resolve("/api/v1").toString();
                    Map<String,String> authHeaders = AmbariActionClient.toAuthHeaders(childParams.get("_callerHeaders"));
                    String cluster = this.commandUtils.resolveClusterName(baseUriStr, authHeaders);
                    var ambariActionClient = new AmbariActionClient(ctx, ambariApiBase, cluster, authHeaders);

                    String verifiedSha256 = null;
                    Optional<byte[]> existingSecretBytes =
                            this.kubernetesService.readOpaqueSecretKeyAsBytes(namespace, secretName, keyNameInSecret);
                    if (existingSecretBytes.isPresent() && payloadSha256.equals(sha256Hex(existingSecretBytes.get()))) {
                        LOG.info("KEYTAB_CREATE_SECRET: Secret '{}/{}' already contains the expected keytab payload", namespace, secretName);
                        try {
                            ambariActionClient.ackAdhocKeytabPayload(payloadRef);
                        } catch (Exception ackEx) {
                            throw new CommandUtils.RetryableException("Secret already present but failed to acknowledge payload: " + trim(ackEx.getMessage()), ackEx);
                        }
                        verifiedSha256 = payloadSha256;
                    }

                    if (verifiedSha256 == null) {
                        byte[] keytabBytes = null;
                        try {
                            String keytabB64 = ambariActionClient.readAdhocKeytabPayload(payloadRef);
                            keytabBytes = java.util.Base64.getDecoder().decode(keytabB64);
                        } catch (Exception payloadEx) {
                            Optional<byte[]> secretBytesAfterReadFailure =
                                    this.kubernetesService.readOpaqueSecretKeyAsBytes(namespace, secretName, keyNameInSecret);
                            if (secretBytesAfterReadFailure.isPresent() && payloadSha256.equals(sha256Hex(secretBytesAfterReadFailure.get()))) {
                                LOG.info("KEYTAB_CREATE_SECRET: payload '{}' already acknowledged; accepting matching Secret '{}/{}'",
                                        payloadRef, namespace, secretName);
                                verifiedSha256 = payloadSha256;
                            } else if (isPayloadUnavailableError(payloadEx)) {
                                throw new IllegalStateException(
                                        String.format("Keytab payload '%s' is no longer available and Secret '%s/%s' could not be verified",
                                                payloadRef, namespace, secretName),
                                        payloadEx);
                            } else {
                                throw new CommandUtils.RetryableException("Failed to read keytab payload from Ambari: " + trim(payloadEx.getMessage()), payloadEx);
                            }
                        }

                        if (verifiedSha256 == null) {
                            if (keytabBytes == null || keytabBytes.length == 0) {
                                throw new CommandUtils.RetryableException("Issued keytab payload is empty");
                            }
                            // Ensure namespace exists. This is harmless if it already exists.
                            try {
                                this.kubernetesService.createNamespace(namespace);
                            } catch (Exception ignore) {
                                // ignore: namespace may already exist or user may not want auto-create
                            }

                            try {
                                this.kubernetesService.createOrUpdateOpaqueSecret(
                                        namespace, secretName, keyNameInSecret, keytabBytes
                                );

                                Optional<byte[]> verifiedSecretBytes =
                                        this.kubernetesService.readOpaqueSecretKeyAsBytes(namespace, secretName, keyNameInSecret);
                                if (verifiedSecretBytes.isEmpty()) {
                                    throw new CommandUtils.RetryableException("Secret verification failed: Kubernetes Secret is missing the expected key");
                                }
                                verifiedSha256 = sha256Hex(verifiedSecretBytes.get());
                                if (!payloadSha256.equals(verifiedSha256)) {
                                    throw new CommandUtils.RetryableException("Secret verification failed: stored keytab hash does not match issued payload");
                                }

                                try {
                                    ambariActionClient.ackAdhocKeytabPayload(payloadRef);
                                } catch (Exception ackEx) {
                                    throw new CommandUtils.RetryableException("Secret verified but failed to acknowledge keytab payload: " + trim(ackEx.getMessage()), ackEx);
                                }
                            } catch (CommandUtils.RetryableException retryableException) {
                                throw retryableException;
                            } catch (Exception ex) {
                                throw new CommandUtils.RetryableException("Failed to create or verify Kubernetes Secret " + namespace + "/" + secretName + ": " + trim(ex.getMessage()), ex);
                            }
                        }
                    }

                    java.util.Map<String,Object> res = new java.util.LinkedHashMap<>();
                    res.put("secretName", secretName);
                    res.put("namespace", namespace);
                    res.put("dataKey", keyNameInSecret);
                    res.put("verifiedSha256", verifiedSha256);
                    childSt.setResultJson(gson.toJson(res));
                }


                case OIDC_REGISTER_CLIENT -> {
                    LOG.info("OIDC_REGISTER_CLIENT starting for id={} title={}", child.getId(), child.getTitle());

                    // Pre-flight: OAuth callbacks require https — Keycloak rejects http redirect URIs for
                    // non-localhost clients, and the cluster-internal token-exchange call requires the
                    // proxy to terminate TLS. Refuse to register the client if the rendered chart values
                    // don't have a TLS configuration on the ingress; this short-circuits a hard-to-diagnose
                    // "Invalid parameter: redirect_uri" or "502 Bad Gateway" several steps later.
                    // The param key was renamed `oidcRedirectUri` (vs the legacy `redirectUri`) when the
                    // OIDC step plumbing was extended; keep the legacy alias as a fallback so old plans
                    // persisted to disk continue to validate correctly after a redeploy.
                    {
                        String redirectUri = (String) childParams.get("oidcRedirectUri");
                        if (redirectUri == null || redirectUri.isBlank()) {
                            redirectUri = (String) childParams.get("redirectUri");
                        }
                        Object rootParamsRaw = rootParams; // resolved at function top
                        String redirectScheme = (redirectUri != null && redirectUri.contains("://"))
                                ? redirectUri.substring(0, redirectUri.indexOf("://")).toLowerCase()
                                : "";
                        // Service definitions declare how they expect TLS to be wired via
                        // `securityCoupling.tlsProvisioning`:
                        //   "k8s-view"       (default) — view participates in the unified ingress.tls[] pipeline
                        //   "chart-managed"  — chart owns its own TLS (GitLab uses this)
                        //   "external"       — operator manages TLS entirely outside, view stays hands-off
                        // The preflight only enforces the ingress.tls[] check for the "k8s-view"
                        // provisioning model; other models bypass it.
                        // Backward compat: legacy `requireTls: false` is still read for one chart cycle.
                        boolean skipIngressTlsCheck = false;
                        try {
                            String svcKey = (String) childParams.get("serviceKey");
                            if (svcKey != null && !svcKey.isBlank()) {
                                StackServiceDef preflightDef =
                                        new StackDefinitionService(this.ctx).getServiceDefinition(svcKey);
                                if (preflightDef != null && preflightDef.securityCoupling != null) {
                                    Object tlsProv = preflightDef.securityCoupling.get("tlsProvisioning");
                                    if (tlsProv != null) {
                                        String s = String.valueOf(tlsProv).trim().toLowerCase();
                                        if (!s.isEmpty() && !"k8s-view".equals(s)) {
                                            skipIngressTlsCheck = true;
                                        }
                                    } else {
                                        Object legacyRequireTls = preflightDef.securityCoupling.get("requireTls");
                                        if (legacyRequireTls != null
                                                && !"true".equalsIgnoreCase(String.valueOf(legacyRequireTls))
                                                && !Boolean.TRUE.equals(legacyRequireTls)) {
                                            skipIngressTlsCheck = true;
                                        }
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            LOG.debug("OIDC pre-flight: could not read securityCoupling, falling back to strict check: {}",
                                    ex.toString());
                        }
                        if ("https".equals(redirectScheme) && !skipIngressTlsCheck) {
                            // Look at the chart values that will be sent to Helm to confirm tls is present.
                            Map<String, Object> chartVals = this.commandUtils.loadValuesFromParams(rootParams);
                            Object ingress = (chartVals != null) ? chartVals.get("ingress") : null;
                            boolean tlsConfigured = false;
                            if (ingress instanceof Map<?, ?> ingMap) {
                                Object tls = ingMap.get("tls");
                                if (tls instanceof List<?> tlsList && !tlsList.isEmpty()) tlsConfigured = true;
                                Object tlsSecret = ingMap.get("tlsSecret");
                                if (tlsSecret instanceof String s && !s.isBlank()) tlsConfigured = true;
                            }
                            // Any TLS-provisioning sibling step counts as "TLS configured" — the Secret
                            // will exist (or be reconciled) before HELM_DEPLOY actually rolls out:
                            //   INGRESS_TLS_SELFSIGN          → view mints + writes Secret in-line
                            //   INGRESS_TLS_CERTMANAGER       → view writes Certificate CR, cert-manager fills the Secret
                            //   INGRESS_TLS_EXTERNAL_SECRET   → view writes ExternalSecret, ESO syncs from upstream store
                            // We check the rootCommand child list for any of those step types.
                            if (!tlsConfigured) {
                                try {
                                    Type listTy = new TypeToken<ArrayList<String>>(){}.getType();
                                    List<String> siblings = gson.fromJson(root.getChildListJson(), listTy);
                                    if (siblings != null) {
                                        for (String sid : siblings) {
                                            CommandEntity s = find(sid);
                                            if (s == null) continue;
                                            String t = s.getType();
                                            if (CommandType.INGRESS_TLS_SELFSIGN.name().equals(t)
                                                    || CommandType.INGRESS_TLS_CERTMANAGER.name().equals(t)
                                                    || CommandType.INGRESS_TLS_EXTERNAL_SECRET.name().equals(t)) {
                                                tlsConfigured = true; break;
                                            }
                                        }
                                    }
                                } catch (Exception ignore) {}
                            }
                            if (!tlsConfigured) {
                                throw new IllegalStateException("OIDC_REGISTER_CLIENT pre-flight: redirectUri is https but the chart values have no ingress.tls and no INGRESS_TLS_SELFSIGN step is planned. " +
                                        "Configure an ingress TLS mode (bringYourOwn / uploadLeaf / signedByAmbariCA / signedByCompanyCA) before enabling OIDC, otherwise the OAuth callback will fail with 502 or Keycloak will reject the redirect_uri.");
                            }
                        }
                    }

                    String baseUriStr = (String) childParams.get("_baseUri");
                    if (baseUriStr == null || baseUriStr.isBlank()) {
                        throw new IllegalStateException("Missing _baseUri in OIDC_REGISTER_CLIENT params");
                    }
                    Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(childParams.get("_callerHeaders"));
                    String clstr = this.commandUtils.resolveClusterName(baseUriStr, authHeaders);
                    String ambariApiBase = URI.create(baseUriStr).resolve("/api/v1").toString();
                    var ambariCli = new AmbariActionClient(ctx, ambariApiBase, clstr, authHeaders);

                    // Load Keycloak admin settings from Ambari oidc-env config
                    String oidcAdminUrl    = ambariCli.getDesiredConfigProperty(clstr, "oidc-env", "oidc_admin_url");
                    String oidcAdminRealm  = ambariCli.getDesiredConfigProperty(clstr, "oidc-env", "oidc_admin_realm");
                    String oidcRealm       = ambariCli.getDesiredConfigProperty(clstr, "oidc-env", "oidc_realm");
                    String adminClientId   = ambariCli.getDesiredConfigProperty(clstr, "oidc-env", "oidc_admin_client_id");
                    String adminClientSecRaw = ambariCli.getDesiredConfigProperty(clstr, "oidc-env", "oidc_admin_client_secret");
                    String verifyTlsStr    = ambariCli.getDesiredConfigProperty(clstr, "oidc-env", "oidc_verify_tls");
                    boolean verifyTls = !"false".equalsIgnoreCase(verifyTlsStr);

                    if (oidcAdminUrl == null || oidcAdminUrl.isBlank()) {
                        throw new IllegalStateException("oidc_admin_url is not set in cluster oidc-env config");
                    }
                    if (oidcAdminRealm == null || oidcAdminRealm.isBlank()) oidcAdminRealm = "master";
                    if (oidcRealm     == null || oidcRealm.isBlank())      oidcRealm = "odp";
                    if (adminClientId == null || adminClientId.isBlank())  adminClientId = "admin-cli";

                    // Resolve client secret: supports ${alias=...} expressions stored in oidc-env
                    AmbariAliasResolver oidcAliasResolver = new AmbariAliasResolver(ctx);
                    char[] resolvedSecretChars = oidcAliasResolver.resolve(ctx, adminClientSecRaw != null ? adminClientSecRaw : "");
                    String adminClientSec = (resolvedSecretChars != null) ? new String(resolvedSecretChars) : "";

                    // Admin username/password come from the Ambari credential store (same alias used
                    // by ConfigureOidcServerAction), never from plain-text config properties.
                    // Stored by Ansible enable_oidc.yml as a PrincipalKeyCredential at oidc.admin.credential.
                    org.apache.ambari.view.k8s.security.PrincipalKeyCredential oidcAdminCred =
                            oidcAliasResolver.getOidcAdminCredential(clstr);
                    String adminUsername = (oidcAdminCred != null) ? oidcAdminCred.getPrincipal() : "";
                    String adminPassword = (oidcAdminCred != null && oidcAdminCred.getKey() != null)
                            ? new String(oidcAdminCred.getKey()) : "";

                    String desiredClientId = (String) childParams.get("oidcClientId");
                    String redirectUri     = (String) childParams.get("oidcRedirectUri");
                    Object publicClientObj = childParams.get("publicClient");
                    boolean publicClient   = Boolean.parseBoolean(String.valueOf(publicClientObj));
                    Object stdFlowObj      = childParams.get("standardFlowEnabled");
                    boolean stdFlow        = stdFlowObj == null || Boolean.parseBoolean(String.valueOf(stdFlowObj));
                    Object implFlowObj     = childParams.get("implicitFlowEnabled");
                    boolean implFlow       = Boolean.parseBoolean(String.valueOf(implFlowObj));

                    validateOidcClientId(desiredClientId);

                    var keycloakClient = new org.apache.ambari.view.k8s.client.KeycloakAdminClient(
                            oidcAdminUrl, oidcAdminRealm, oidcRealm,
                            adminClientId, adminClientSec,
                            adminUsername, adminPassword,
                            verifyTls);

                    appendCommandLog(child.getId(), "Registering OIDC client '" + desiredClientId
                            + "' in realm '" + oidcRealm + "'");

                    var oidcResult = keycloakClient.registerClient(
                            desiredClientId, redirectUri, publicClient, stdFlow, implFlow, !publicClient);

                    String issuerUrl = resolveOidcIssuerUrl(ambariCli, clstr);
                    if (issuerUrl == null || issuerUrl.isBlank()) {
                        issuerUrl = oidcResult.issuerUrl();
                    }

                    Map<String, Object> res = new LinkedHashMap<>();
                    res.put("clientId",   oidcResult.clientId());
                    res.put("issuerUrl",  issuerUrl);
                    // clientSecret intentionally NOT logged; stored encrypted via childSt.resultJson
                    res.put("clientSecret", oidcResult.clientSecret());
                    childSt.setResultJson(gson.toJson(res));
                    appendCommandLog(child.getId(), "OIDC client registered successfully: clientId=" + oidcResult.clientId());
                    LOG.info("OIDC_REGISTER_CLIENT completed: clientId={}, issuerUrl={}", oidcResult.clientId(), issuerUrl);
                }

                case OIDC_CREATE_SECRET -> {
                    LOG.info("OIDC_CREATE_SECRET starting for id={} title={}", child.getId(), child.getTitle());

                    String namespace   = (String) childParams.get("namespace");
                    String secretName  = (String) childParams.get("oidcSecretName");
                    String vaultPath   = (String) childParams.get("oidcVaultPath");
                    Objects.requireNonNull(namespace,  "namespace");
                    Objects.requireNonNull(secretName, "oidcSecretName");

                    // External OIDC: admin pre-supplied credentials in the security profile
                    String clientId;
                    String clientSecret;
                    String issuerUrl;
                    if (childParams.containsKey("oidcExternalClientId")) {
                        clientId     = (String) childParams.get("oidcExternalClientId");
                        clientSecret = (String) childParams.get("oidcExternalClientSecret");
                        issuerUrl    = (String) childParams.get("oidcExternalIssuerUrl");
                        if (clientId == null || clientId.isBlank()) {
                            throw new IllegalStateException("External OIDC: oidcExternalClientId is empty");
                        }
                    } else {
                        // Internal OIDC: locate sibling OIDC_REGISTER_CLIENT result
                        String registerId = (String) childParams.get("oidcRegisterId");
                        if (registerId == null || registerId.isBlank()) {
                            Type listType = new com.google.gson.reflect.TypeToken<ArrayList<String>>(){}.getType();
                            List<String> siblings = gson.fromJson(root.getChildListJson(), listType);
                            if (siblings != null) {
                                registerId = siblings.stream()
                                        .filter(cid -> {
                                            CommandEntity c = findCommandById(cid);
                                            return c != null && CommandType.OIDC_REGISTER_CLIENT.name().equals(c.getType());
                                        })
                                        .findFirst().orElse(null);
                            }
                        }
                        if (registerId == null) {
                            throw new IllegalStateException("Missing OIDC_REGISTER_CLIENT sibling before OIDC_CREATE_SECRET");
                        }

                        CommandEntity registerCmd = findCommandById(registerId);
                        CommandStatusEntity registerSt = findCommandStatusById(registerCmd.getCommandStatusId());
                        if (registerSt == null || registerSt.getResultJson() == null || registerSt.getResultJson().isBlank()) {
                            throw new IllegalStateException("OIDC_REGISTER_CLIENT has no resultJson with credentials");
                        }

                        Map<String, Object> regRes = gson.fromJson(registerSt.getResultJson(), Map.class);
                        clientId     = (String) regRes.get("clientId");
                        clientSecret = (String) regRes.get("clientSecret");
                        issuerUrl    = (String) regRes.get("issuerUrl");
                        if (clientId == null || clientId.isBlank()) {
                            throw new IllegalStateException("OIDC_REGISTER_CLIENT resultJson missing clientId");
                        }
                    }

                    // Build multi-key secret payload
                    Map<String, byte[]> secretData = new LinkedHashMap<>();
                    secretData.put("client_id",     clientId.getBytes(StandardCharsets.UTF_8));
                    secretData.put("client_secret", (clientSecret != null ? clientSecret : "")
                            .getBytes(StandardCharsets.UTF_8));
                    if (issuerUrl != null && !issuerUrl.isBlank()) {
                        secretData.put("issuer_url", issuerUrl.getBytes(StandardCharsets.UTF_8));
                    }

                    Map<String, String> secretLabels = Map.of(
                            "managed-by",                  "ambari-k8s-view",
                            "ambari.clemlab.com/type",     "oidc-client",
                            "ambari.clemlab.com/clientId", clientId
                    );

                    try {
                        this.kubernetesService.createNamespace(namespace);
                    } catch (Exception ignore) {}

                    this.kubernetesService.createOrUpdateOpaqueSecret(namespace, secretName, secretData);
                    appendCommandLog(child.getId(), "K8s Secret '" + namespace + "/" + secretName + "' created/updated");
                    LOG.info("OIDC_CREATE_SECRET: Secret '{}/{}' written for client '{}'", namespace, secretName, clientId);

                    Map<String, Object> res = new LinkedHashMap<>();

                    // GitLab OmniAuth: create a provider secret with the full YAML provider config
                    if ("true".equals(childParams.get("oidcGitlabOmniauth"))) {
                        String providerSecretName = (String) childParams.get("oidcProviderSecretName");
                        String callbackUrl = (String) childParams.get("oidcGitlabCallbackUrl");
                        if (providerSecretName != null && !providerSecretName.isBlank()) {
                            String providerYaml = buildGitlabOmniauthProviderYaml(clientId, clientSecret, issuerUrl, callbackUrl);
                            Map<String, byte[]> providerData = new LinkedHashMap<>();
                            providerData.put("provider", providerYaml.getBytes(StandardCharsets.UTF_8));
                            this.kubernetesService.createOrUpdateOpaqueSecret(namespace, providerSecretName, providerData);
                            appendCommandLog(child.getId(), "K8s Secret '" + namespace + "/" + providerSecretName + "' (GitLab OmniAuth) created/updated");
                            LOG.info("OIDC_CREATE_SECRET: GitLab OmniAuth secret '{}/{}' written", namespace, providerSecretName);
                            res.put("gitlabProviderSecretName", providerSecretName);
                        }
                    }

                    // Optionally write to Vault when a vaultPath is configured
                    if (vaultPath != null && !vaultPath.isBlank()) {
                        try {
                            String vaultAddr = ctx.getProperties() == null ? null
                                    : ctx.getProperties().get("k8s.view.vault.address");
                            String vaultToken = ctx.getProperties() == null ? null
                                    : ctx.getProperties().get("k8s.view.vault.token");
                            if (vaultAddr != null && !vaultAddr.isBlank()
                                    && vaultToken != null && !vaultToken.isBlank()) {
                                writeOidcToVault(vaultAddr, vaultToken, vaultPath, clientId, clientSecret, issuerUrl);
                                appendCommandLog(child.getId(), "Vault path '" + vaultPath + "' updated");
                                LOG.info("OIDC_CREATE_SECRET: Vault path '{}' updated for client '{}'", vaultPath, clientId);
                            } else {
                                LOG.info("OIDC_CREATE_SECRET: vaultPath set but k8s.view.vault.address/token not configured; skipping Vault write");
                            }
                        } catch (Exception vaultEx) {
                            LOG.warn("OIDC_CREATE_SECRET: Vault write failed (non-fatal, K8s Secret was written): {}", vaultEx.toString());
                            appendCommandLog(child.getId(), "Vault write failed (non-fatal): " + vaultEx.getMessage());
                        }
                    }

                    res.put("secretName",  secretName);
                    res.put("namespace",   namespace);
                    res.put("clientId",    clientId);
                    res.put("issuerUrl",   issuerUrl);
                    if (vaultPath != null && !vaultPath.isBlank()) res.put("vaultPath", vaultPath);
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
            childSt.setState(CommandState.RUNNING.name());
            childSt.setMessage("Retryable error (" + attempt + "/" + MAX_RETRYABLE_ATTEMPTS + "): " + trim(rex.getMessage()));
            childSt.setUpdatedAt(Instant.now().toString());
            store(childSt);

            if (attempt >= MAX_RETRYABLE_ATTEMPTS) {
                childSt.setState(CommandState.FAILED.name());
                childSt.setMessage("Failed after " + attempt + " retry attempts: " + trim(rex.getMessage()));
                childSt.setUpdatedAt(Instant.now().toString());
                store(childSt);
                LOG.error("Step exhausted retry attempts: {}", rex.toString(), rex);
                try { commandLogService.append(id, "Step exhausted retry attempts: " + trim(rex.getMessage())); } catch (Exception ignored) {}
                fail(rootSt, "Failed at step '" + (child.getTitle() != null ? child.getTitle() : child.getType()) + "' after " + attempt + " retry attempts: " + trim(rex.getMessage()));
                refreshCurrentStepSnapshot(root);
            } else {
                try { commandLogService.append(id, "Retrying step (" + attempt + "/" + MAX_RETRYABLE_ATTEMPTS + "): " + trim(rex.getMessage())); } catch (Exception ignored) {}
                rescheduleWithBackoff(id, attempt);
            }

        } catch (Exception ex) {
            final String stepTitle = child.getTitle() != null ? child.getTitle() : child.getType();

            // ── Abort / cancellation ──────────────────────────────────────────────────
            // runWithCancellation throws "Canceled by user" after calling future.cancel().
            // cancelRecursive() has already set the child status to CANCELED; don't
            // overwrite it with FAILED.  Also ensure the root ends as CANCELED, not FAILED.
            if ("Canceled by user".equals(ex.getMessage())) {
                CommandStatusEntity freshChild = findCommandStatusById(child.getCommandStatusId());
                if (freshChild != null && CommandState.CANCELED.name().equals(freshChild.getState())) {
                    LOG.info("[{}] Step '{}' was canceled by user — preserving CANCELED state", id, stepTitle);
                    try { commandLogService.append(id, "Step canceled: " + stepTitle); } catch (Exception ignored) {}
                    // Ensure root is also CANCELED (not FAILED)
                    CommandStatusEntity freshRoot = findCommandStatusById(root.getCommandStatusId());
                    if (freshRoot != null && !CommandState.FAILED.name().equals(freshRoot.getState())) {
                        freshRoot.setState(CommandState.CANCELED.name());
                        freshRoot.setMessage("Canceled by user");
                        freshRoot.setUpdatedAt(Instant.now().toString());
                        store(freshRoot);
                    }
                    refreshCurrentStepSnapshot(root);
                    return;
                }
            }

            // ── YAML parse error recovery ─────────────────────────────────────────────
            // helm-java can throw a YAML parse error when parsing the install/upgrade
            // *response* even though the release was successfully deployed to the cluster.
            // Before marking the step as failed, verify whether the release is actually up.
            final String exMsg = ex.getMessage() != null ? ex.getMessage() : "";
            if (exMsg.contains("YAML parse error") || exMsg.contains("error converting YAML to JSON")) {
                Map<String, Object> childParams = null;
                try {
                    childParams = gson.fromJson(resolveParamsJson(child), mapType);
                } catch (Exception ignored) {}

                if (childParams != null) {
                    final String releaseName = (String) childParams.get("releaseName");
                    final String namespace    = (String) childParams.get("namespace");
                    final String kubeconfig   = this.kubernetesService.getConfigurationService().getKubeconfigContents();
                    if (releaseName != null && namespace != null) {
                        Optional<Release> deployed = this.helmService.findDeployedRelease(releaseName, namespace, kubeconfig);
                        if (deployed.isPresent()) {
                            LOG.warn("[{}] Helm response parse error for '{}' but release is deployed — treating as success: {}",
                                    id, releaseName, exMsg);
                            try { commandLogService.append(id,
                                    "Warning: Helm response parsing failed but release '" + releaseName + "' is deployed. Continuing."); }
                            catch (Exception ignored) {}
                            childSt.setState(CommandState.SUCCEEDED.name());
                            childSt.setMessage("Deployed (response parse warning)");
                            childSt.setUpdatedAt(Instant.now().toString());
                            store(childSt);
                            this.kubernetesService.ensureReleaseLabelsOnIngresses(namespace, releaseName);
                            refreshCurrentStepSnapshot(root);
                            scheduleNow(id);
                            return;
                        }
                    }
                }
            }

            // ── Regular failure ───────────────────────────────────────────────────────
            childSt.setState(CommandState.FAILED.name());
            childSt.setMessage("Failed: " + trim(ex.getMessage()));
            childSt.setUpdatedAt(Instant.now().toString());
            store(childSt);
            LOG.error("Step execution failed: {}", ex.toString(), ex);
            appendCommandLog(id, "Step failed: " + trim(ex.getMessage()));
            fail(rootSt, "Failed at step '" + stepTitle + "': " + trim(ex.getMessage()));
            refreshCurrentStepSnapshot(root);
        } finally {
            activeStepLogId.remove();
        }
    }

    private static String trim(String m) { return m == null ? null : (m.length() > 512 ? m.substring(0,512) + "…" : m); }

    private static boolean isPayloadUnavailableError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String text = ((current.getMessage() == null ? "" : current.getMessage()) + " " + current)
                    .toLowerCase(Locale.ROOT);
            if (text.contains("404")
                    || text.contains("410")
                    || text.contains("gone")
                    || text.contains("not found")
                    || text.contains("already consumed")
                    || text.contains("expired")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String sha256Hex(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

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

    /**
     * For HELM_DEPLOY / HELM_UPGRADE root commands, replace the persisted endpoint
     * snapshot with what live cluster discovery sees right now. This fixes the case
     * where service.json url templates hardcoded http:// but the chart materialized
     * an Ingress with spec.tls[] (chart-managed TLS), so the snapshot would otherwise
     * show the wrong scheme until the user refreshed. Best-effort: any failure is
     * logged and swallowed.
     */
    private void refreshEndpointSnapshotFromLive(CommandEntity root, Map<String, Object> rootParams) {
        try {
            if (root == null || rootParams == null) return;
            String type = root.getType();
            if (!CommandType.HELM_DEPLOY.name().equals(type)
                    && !CommandType.HELM_UPGRADE.name().equals(type)) {
                return;
            }
            Object nsObj = rootParams.get("namespace");
            Object rnObj = rootParams.get("releaseName");
            if (!(nsObj instanceof String) || !(rnObj instanceof String)) return;
            String namespace = (String) nsObj;
            String releaseName = (String) rnObj;
            if (namespace.isBlank() || releaseName.isBlank()) return;

            ReleaseMetadataService metadataService = new ReleaseMetadataService(this.ctx);
            List<org.apache.ambari.view.k8s.model.ReleaseEndpointDTO> live =
                    metadataService.discoverExternalClusterEndpoints(namespace, releaseName);
            if (live == null || live.isEmpty()) {
                // Keep the install-time snapshot rather than wiping it — Ingress may
                // not be observable yet (rare; helm install applied it synchronously
                // but the API server is briefly slow), and a stale snapshot is better
                // than no snapshot.
                LOG.info("refreshEndpointSnapshotFromLive: no live endpoints yet for {}/{}, keeping install-time snapshot",
                        namespace, releaseName);
                return;
            }
            List<Map<String, Object>> asMaps = new ArrayList<>(live.size());
            for (org.apache.ambari.view.k8s.model.ReleaseEndpointDTO dto : live) {
                Map<String, Object> m = new LinkedHashMap<>();
                if (dto.getId() != null) m.put("id", dto.getId());
                if (dto.getLabel() != null) m.put("label", dto.getLabel());
                if (dto.getUrl() != null) m.put("url", dto.getUrl());
                if (dto.getDescription() != null) m.put("description", dto.getDescription());
                if (dto.getKind() != null) m.put("kind", dto.getKind());
                asMaps.add(m);
            }
            metadataService.recordEndpoints(namespace, releaseName, asMaps);
        } catch (Exception ex) {
            LOG.warn("refreshEndpointSnapshotFromLive failed (best-effort): {}", ex.toString());
        }
    }

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
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return null;
        }
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
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return null;
        }
        try {
            SecurityProfileService profileService = new SecurityProfileService(ctx);
            SecurityProfilesDTO profiles = profileService.loadProfiles();
            if (profiles == null || profiles.profiles == null || profiles.profiles.isEmpty()) {
                return null;
            }
            // Only honor an explicitly requested profile. If the user did not select one, do not auto-apply any profile.
            if (profiles.profiles.containsKey(requestedProfile)) {
                return requestedProfile;
            }
            return null;
        } catch (Exception ex) {
            LOG.warn("Could not resolve security profile name: {}", ex.toString());
            return null;
        }
    }

    /**
     * Build the merged property delta that the {@code OM_RANGER_TAGSYNC_REGISTER} step writes
     * into the cluster's {@code ranger-tagsync-site} config. Extracted as a static method
     * so unit tests can validate it without a live Ambari client.
     *
     * <p>Inputs flow from the service.json {@code ranger.*} entry of type
     * {@code ranger-tagsync-source}, plus a few resolved-at-runtime values (OM endpoint,
     * JWT). The returned map is suitable for handing to
     * {@link AmbariActionClient#updateClusterConfig(String, String, Map, String)}.
     *
     * <p>Idempotency is handled by {@code updateClusterConfig} (it compares against
     * current desired-config and skips the PUT if nothing changed); this method just
     * computes the desired-state delta.
     *
     * @param spec              the service.json ranger entry (must have {@code type:
     *                          "ranger-tagsync-source"}; other types raise IAE)
     * @param entryKey          the entry's logical name (for the source key, e.g.
     *                          {@code openmetadatarest})
     * @param omEndpoint        resolved OM REST endpoint URL
     * @param jwt               the OM ingestion-bot JWT (Bearer token)
     * @param currentSources    current value of {@code ranger.tagsync.sources} on the
     *                          cluster (CSV); used to dedup-append
     * @return ordered map of properties to set; never null
     */
    static Map<String, String> buildTagSyncRangerSiteOverrides(Map<String, Object> spec,
                                                               String entryKey,
                                                               String omEndpoint,
                                                               String jwt,
                                                               String currentSources) {
        if (spec == null) throw new IllegalArgumentException("spec is required");
        Object type = spec.get("type");
        if (type == null || !"ranger-tagsync-source".equals(String.valueOf(type))) {
            throw new IllegalArgumentException("expected type=ranger-tagsync-source, got: " + type);
        }
        if (entryKey == null || entryKey.isBlank()) throw new IllegalArgumentException("entryKey is required");
        if (omEndpoint == null || omEndpoint.isBlank()) throw new IllegalArgumentException("omEndpoint is required");
        if (jwt == null || jwt.isBlank()) throw new IllegalArgumentException("jwt is required");

        // Use the entry key as the unique "source key" within ranger-tagsync-site
        // (this is also what the operator types into ranger.tagsync.sources CSV).
        String sourceKey = entryKey;

        Map<String, String> props = new LinkedHashMap<>();
        // ----- per-source endpoint + auth + class -----
        props.put("ranger.tagsync.source." + sourceKey + ".endpoint", omEndpoint);
        props.put("ranger.tagsync.source." + sourceKey + ".token", jwt);
        Object sourceClass = spec.get("source_class");
        if (sourceClass != null && !String.valueOf(sourceClass).isBlank()) {
            props.put("ranger.tagsync.source." + sourceKey + ".class", String.valueOf(sourceClass));
        }
        // Reasonable default polling interval: 30s; operators can override via Ambari UI later.
        props.putIfAbsent("ranger.tagsync.source." + sourceKey + ".cookies.timeout.millis", "30000");

        // ----- service-name mapper: maps OM database-service name → Ranger Trino service name -----
        // The spec may declare a static mapping or rely on the wizard's form-supplied helm values.
        // We accept either a literal {trinoIngestionServiceFqn → rangerTrinoServiceName} pair
        // (provided by the plan via _tagSyncTrinoServiceFqn / _tagSyncRangerTrinoService keys)
        // or skip the mapper line — operators can wire it manually.
        Object trinoFqn = spec.get("trinoIngestionServiceFqn");
        Object rangerTrinoSvc = spec.get("rangerTrinoServiceName");
        if (trinoFqn != null && rangerTrinoSvc != null
                && !String.valueOf(trinoFqn).isBlank() && !String.valueOf(rangerTrinoSvc).isBlank()) {
            props.put("ranger.tagsync.atlas.openmetadata.servicename.mapper."
                            + trinoFqn + ".ranger.service",
                    String.valueOf(rangerTrinoSvc));
        }

        // ----- append source key to ranger.tagsync.sources (dedup, comma-separated) -----
        java.util.LinkedHashSet<String> sources = new java.util.LinkedHashSet<>();
        if (currentSources != null && !currentSources.isBlank()) {
            for (String s : currentSources.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) sources.add(t);
            }
        }
        sources.add(sourceKey);
        props.put("ranger.tagsync.sources", String.join(",", sources));

        return props;
    }

    /**
     * Renders the {@code ranger-tagsync-site} property delta as an XML snippet suitable
     * for pasting into an external Ranger TagSync host's configuration file. Used when
     * the operator targets an external Ranger (not part of this Ambari cluster) —
     * since KDPS cannot mutate that cluster's config, we surface the values to apply
     * manually. Stored on the command's result JSON and appended to the deploy log.
     *
     * <p>Output mirrors Ranger's hadoop-conf style XML for direct file paste.
     */
    static String renderTagSyncXmlSnippet(Map<String, String> propsToSet) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- ranger-tagsync-site.xml snippet rendered by KDPS for external Ranger TagSync -->\n");
        sb.append("<!-- Paste into your external Ranger TagSync host's ranger-tagsync-site.xml -->\n");
        sb.append("<configuration>\n");
        for (Map.Entry<String, String> e : propsToSet.entrySet()) {
            sb.append("  <property>\n");
            sb.append("    <name>").append(e.getKey()).append("</name>\n");
            // Redact JWT/password-looking values in the rendered log — operators get the
            // actual value from the K8s Secret KDPS provisioned, the snippet is for
            // structure only.
            String v = e.getValue();
            String safe = (e.getKey().endsWith(".token") || e.getKey().endsWith(".password"))
                    ? "<REDACTED — read from K8s Secret>"
                    : (v == null ? "" : v);
            sb.append("    <value>").append(safe).append("</value>\n");
            sb.append("  </property>\n");
        }
        sb.append("</configuration>\n");
        return sb.toString();
    }

    /**
     * Body of the {@code OM_RANGER_TAGSYNC_REGISTER} step. Extracted so the dispatch site
     * stays small and so the integration logic (read OM endpoint + JWT, merge into
     * ranger-tagsync-site, restart Ranger TagSync) is testable with mocked clients.
     *
     * <p><b>Branches on Ranger ownership:</b>
     * <ul>
     *   <li><b>Ambari-managed Ranger</b> (no {@code ranger.tagSync.adminUrlOverride} in
     *       helm values): write {@code ranger-tagsync-site} via Ambari, restart the
     *       {@code RANGER_TAGSYNC} component, return {@code {configChanged, restartRequestId}}.</li>
     *   <li><b>External Ranger</b> (operator supplied a URL override): KDPS cannot mutate
     *       the foreign cluster's config or restart its services — render the
     *       {@code ranger-tagsync-site} snippet for manual paste and return
     *       {@code {mode: "external", xmlSnippet, externalRangerUrl,
     *       credsSecretName}}. Operator finishes the wiring by hand. This keeps the
     *       integration honest about its limits instead of silently doing nothing.</li>
     * </ul>
     *
     * @return a small JSON payload to record on the command's status; never null
     * @throws Exception when any required input is missing or an Ambari call fails
     *                   (only on the Ambari-managed branch)
     */
    @SuppressWarnings("unchecked")
    String registerRangerTagSyncSource(Map<String, Object> childParams) throws Exception {
        String cluster     = (String) childParams.get("_cluster");
        String releaseName = (String) childParams.get("releaseName");
        String namespace   = (String) childParams.get("namespace");
        String entryKey    = (String) childParams.get("_tagSyncEntryKey");
        Map<String, Object> spec = (Map<String, Object>) childParams.get("_tagSyncSpec");
        if (entryKey == null || entryKey.isBlank() || spec == null) {
            throw new IllegalStateException("Missing _tagSyncEntryKey/_tagSyncSpec in OM_RANGER_TAGSYNC_REGISTER params");
        }
        // ranger-tagsync-settings carries chart-wide TagSync wiring (endpoint helm path,
        // fernet Secret coordinates, OM REST shape, bot username). Plan-factory populated
        // _tagSyncSettings before queueing this step.
        Map<String, Object> settings = (Map<String, Object>) childParams.get("_tagSyncSettings");
        if (settings == null) settings = Collections.emptyMap();

        // ----- Resolve OM endpoint from helm values -----
        // Settings drives the helm path (chart-specific); per-source `endpoint_helm_prop`
        // remains an honored override for the rare case a chart has more than one source
        // landing in different helm paths.
        //
        // Two callers, two value sources (mirrors registerOmAtlasFederation):
        //   1. Deploy-time: params carry valuesPath → loadValuesFromParams.
        //   2. Reapply-time (actions/om-ranger-tagsync): no valuesPath → fall
        //      back to the live helm release values.
        Map<String, Object> values = this.commandUtils.loadValuesFromParams(childParams);
        if (values == null || values.isEmpty()) {
            values = kubernetesService.getHelmReleaseValues(namespace, releaseName);
            if (values == null) values = Collections.emptyMap();
            LOG.info("OM_RANGER_TAGSYNC_REGISTER: read live helm release values for {}/{} ({} keys)",
                    namespace, releaseName, values.size());
        }
        String endpointHelmPath = String.valueOf(spec.getOrDefault(
                "endpoint_helm_prop",
                settings.getOrDefault("endpoint_helm_prop", "ranger.tagSync.endpoint")));
        Object endpointRaw = ConfigResolutionService.getByDottedPath(values, endpointHelmPath);
        String omEndpoint = endpointRaw == null ? "" : String.valueOf(endpointRaw);
        if (omEndpoint.isBlank()) {
            throw new IllegalStateException("Could not resolve OM endpoint at helm path '" + endpointHelmPath
                    + "' — the operator must enable Ranger TagSync and provide an endpoint in the wizard.");
        }

        // ----- Mint a fresh Unlimited-TTL JWT for OM's ingestion-bot via the
        // OM-deps airflow scheduler pod. The previous design read the JWT from a
        // K8s Secret that a helm hook Job populated; chart 1.13.5 deletes that
        // Job (and the Secret), so the Ambari step now mints the JWT itself.
        // See OmBotJwtClient for the why-exec-not-JDBC rationale.
        //
        // Fernet Secret coordinates come from settings (chart-specific) with sensible
        // defaults — release-name templated since the Secret is per-release.
        String fernetSecretNameTpl = String.valueOf(
                settings.getOrDefault("fernet_secret_name_template", "{{releaseName}}-fernet"));
        String fernetSecretName = fernetSecretNameTpl.replace("{{releaseName}}", releaseName);
        String fernetSecretKey = String.valueOf(settings.getOrDefault("fernet_secret_key", "fernet-key"));
        String fernetKey = null;
        try {
            io.fabric8.kubernetes.api.model.Secret fernetSec =
                    this.kubernetesService.getSecret(namespace, fernetSecretName);
            if (fernetSec != null && fernetSec.getData() != null
                    && fernetSec.getData().containsKey(fernetSecretKey)) {
                fernetKey = new String(
                        java.util.Base64.getDecoder().decode(fernetSec.getData().get(fernetSecretKey)),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ex) {
            LOG.warn("OM_RANGER_TAGSYNC_REGISTER: could not read '{}/{}' Secret key '{}', falling back to chart default: {}",
                    namespace, fernetSecretName, fernetSecretKey, ex.toString());
        }
        // OM service host + port from settings (chart-specific). Default: just the
        // release name + 8585 (matches OM chart's `<release>` Service name).
        String omSvcTpl = String.valueOf(settings.getOrDefault("om_service_template", "{{releaseName}}"));
        String omSvc = omSvcTpl.replace("{{releaseName}}", releaseName);
        Object omPortRaw = settings.get("om_port");
        Integer omPort = (omPortRaw instanceof Number) ? ((Number) omPortRaw).intValue() :
                (omPortRaw != null && !String.valueOf(omPortRaw).isBlank() ? Integer.parseInt(String.valueOf(omPortRaw)) : Integer.valueOf(8585));
        String jwt = org.apache.ambari.view.k8s.service.om.OmBotJwtClient.mintUnlimitedJwt(
                this.kubernetesService.getClient(),
                namespace,
                releaseName,
                fernetKey,
                omSvc,
                omPort,
                java.time.Duration.ofSeconds(120));

        // ----- Branch on Ranger ownership via the selected Platform Context -----
        // The per-service Ranger discovery fields were removed (feedback: TagSync must
        // "use the selected KDPS context", not per-service service discovery). Ranger now
        // comes from the context the operator picked in the right-pane selector:
        //   - EXTERNAL context  → carries the foreign Ranger's URL (+ admin creds). KDPS
        //     can't mutate a foreign cluster's ranger-tagsync-site, so we render a snippet
        //     for the operator to apply (the external branch below).
        //   - MANAGED / VIRTUAL → the Ambari-owned Ranger; KDPS mutates ranger-tagsync-site
        //     and restarts RANGER_TAGSYNC directly (the managed branch below).
        // Mirrors the context resolution in registerOmAtlasFederation.
        org.apache.ambari.view.k8s.model.ResolvedContext rc = resolvePlatformContextForStep(childParams);
        String externalRangerUrl = "";
        String externalRangerMode = "";
        String externalRangerSecretName = "";
        boolean external = rc != null && "EXTERNAL".equalsIgnoreCase(stringValue(rc.getKind()));
        if (external) {
            externalRangerUrl = stringValue(rc.getRangerUrl());
            if (externalRangerUrl.isEmpty()) {
                throw new IllegalStateException("Selected platform context '" + stringValue(rc.getName())
                        + "' is EXTERNAL but resolved no Ranger URL — fill the context's Ranger capability "
                        + "(rangerUrl) before enabling TagSync.");
            }
            // An external context expresses Ranger creds as admin user/password; surface
            // that as "basic" on the rendered snippet's advisory metadata. The snippet is
            // applied by the operator on the foreign TagSync host, so no K8s Secret is read here.
            if (!stringValue(rc.getRangerAdminUsername()).isEmpty()) {
                externalRangerMode = "basic";
            }
        }

        // Always compute the property delta — both branches need it. Don't pass currentSources
        // on the external branch (we can't read the foreign cluster's config).
        String currentSources = external
                ? null
                : new AmbariActionClient(ctx,
                        java.net.URI.create((String) childParams.get("_baseUri")).resolve("/api/v1").toString(),
                        cluster,
                        AmbariActionClient.toAuthHeaders(childParams.get("_callerHeaders")))
                                .getDesiredConfigProperty(cluster, "ranger-tagsync-site", "ranger.tagsync.sources");
        Map<String, String> propsToSet = buildTagSyncRangerSiteOverrides(spec, entryKey, omEndpoint, jwt, currentSources);

        if (external) {
            // No Ambari mutation, no restart. Surface what the operator must apply.
            String snippet = renderTagSyncXmlSnippet(propsToSet);
            LOG.info("OM_RANGER_TAGSYNC_REGISTER (external) source='{}' rangerUrl='{}' authMode='{}' "
                            + "— rendered {} props to snippet (see command status JSON / deploy log). "
                            + "Operator must paste into external Ranger TagSync host's ranger-tagsync-site.xml "
                            + "and restart that daemon.",
                    entryKey, externalRangerUrl, externalRangerMode, propsToSet.size());
            LOG.info("--- ranger-tagsync-site snippet (external) ---\n{}", snippet);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "external");
            result.put("sourceKey", entryKey);
            result.put("externalRangerUrl", externalRangerUrl);
            if (!externalRangerMode.isEmpty()) result.put("externalAuthMode", externalRangerMode);
            if (!externalRangerSecretName.isEmpty()) result.put("credsSecretName", externalRangerSecretName);
            result.put("xmlSnippet", snippet);
            result.put("propertiesCount", propsToSet.size());
            return gson.toJson(result);
        }

        // ----- Ambari-managed Ranger path (current behavior, regression-safe) -----
        if (cluster == null || cluster.isBlank()) {
            throw new IllegalStateException("Missing _cluster in OM_RANGER_TAGSYNC_REGISTER params (Ambari-managed branch)");
        }
        String baseUriStr = (String) childParams.get("_baseUri");
        if (baseUriStr == null || baseUriStr.isBlank()) {
            throw new IllegalStateException("Missing _baseUri in OM_RANGER_TAGSYNC_REGISTER params (Ambari-managed branch)");
        }
        java.net.URI baseUri = java.net.URI.create(baseUriStr);
        String ambariApiBase = baseUri.resolve("/api/v1").toString();
        Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(childParams.get("_callerHeaders"));
        var ambari = new AmbariActionClient(ctx, ambariApiBase, cluster, authHeaders);

        LOG.info("OM_RANGER_TAGSYNC_REGISTER (Ambari-managed): writing {} props to ranger-tagsync-site for source '{}' on cluster '{}'",
                propsToSet.size(), entryKey, cluster);
        boolean wrote = ambari.updateClusterConfig(cluster, "ranger-tagsync-site", propsToSet, "om-tagsync-" + releaseName);

        Integer restartReqId = null;
        if (wrote) {
            int reqId = ambari.submitComponentRestart(cluster, "RANGER", "RANGER_TAGSYNC",
                    "KDPS: restart Ranger TagSync after registering source '" + entryKey + "'");
            restartReqId = reqId;
            boolean ok = ambari.waitUntilComplete(reqId, 600L, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok) {
                throw new IllegalStateException("Ranger TagSync restart did not complete (Ambari requestId=" + reqId + ")");
            }
            LOG.info("Ranger TagSync restarted; source '{}' is now active", entryKey);
        } else {
            LOG.info("ranger-tagsync-site already had source '{}'; skipping Ranger TagSync restart", entryKey);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "ambari-managed");
        result.put("sourceKey", entryKey);
        result.put("configChanged", wrote);
        if (restartReqId != null) result.put("restartRequestId", restartReqId);
        return gson.toJson(result);
    }

    /**
     * Body of the {@code OM_ATLAS_FEDERATION_REGISTER} step. Mints an Unlimited-TTL
     * JWT for the OM ingestion-bot, then calls OM REST four times to set up the
     * federation: {@code metadataServices} (Atlas), {@code databaseServices}
     * (CustomDatabase landing zone), {@code ingestionPipelines} (metadata pipeline
     * with the operator-chosen schedule), and a {@code trigger} POST to start the
     * first run.
     *
     * <p>Replaces the helm post-install hook Job the OM chart used to ship.
     * Idempotent: PUTs are upserts in OM, so a replay registers no duplicates
     * and the operator can re-run safely.
     *
     * @return a small JSON payload to record on the command's status; never null
     * @throws Exception when any required input is missing or the OM REST flow fails
     */
    @SuppressWarnings("unchecked")
    /**
     * Resolve the platform context for a running step from its params
     * ({@code _platformContextId} / {@code _cluster} / {@code _baseUri} / {@code _callerHeaders}).
     * Returns null on failure (callers fall back to legacy form values).
     */
    private org.apache.ambari.view.k8s.model.ResolvedContext resolvePlatformContextForStep(
            Map<String, Object> childParams) {
        try {
            String cluster = (String) childParams.get("_cluster");
            String baseUriStr = (String) childParams.get("_baseUri");
            String contextId = stringValue(childParams.get("_platformContextId"));
            AmbariActionClient ambari = null;
            if (cluster != null && baseUriStr != null) {
                Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(childParams.get("_callerHeaders"));
                java.net.URI baseUri = java.net.URI.create(baseUriStr);
                ambari = new AmbariActionClient(ctx, baseUri.resolve("/api/v1").toString(), cluster, authHeaders);
            }
            return new ContextService(ctx).resolve(contextId.isBlank() ? null : contextId, ambari, cluster);
        } catch (Exception e) {
            LOG.warn("resolvePlatformContextForStep failed: {}", e.toString());
            return null;
        }
    }

    String registerOmAtlasFederation(Map<String, Object> childParams) throws Exception {
        String releaseName = (String) childParams.get("releaseName");
        String namespace   = (String) childParams.get("namespace");
        if (releaseName == null || releaseName.isBlank() || namespace == null || namespace.isBlank()) {
            throw new IllegalStateException("Missing releaseName/namespace in OM_ATLAS_FEDERATION_REGISTER params");
        }

        // Two callers, two value sources:
        //   1. Deploy-time (createOmAtlasFederationRegister via submitDeploy):
        //      params carry a `valuesPath` pointing at the cache file with the
        //      resolved-by-binding helm values. loadValuesFromParams reads that.
        //   2. Reapply-time (submitReleaseOmAtlasFederationReapply via the
        //      `actions/om-atlas-federation` REST endpoint): there's no in-flight
        //      deploy, so no valuesPath. Fall back to the LIVE helm release
        //      values pulled from helm's Secret in the release namespace.
        Map<String, Object> values = this.commandUtils.loadValuesFromParams(childParams);
        if (values == null || values.isEmpty()) {
            values = kubernetesService.getHelmReleaseValues(namespace, releaseName);
            if (values == null) values = Collections.emptyMap();
            LOG.info("OM_ATLAS_FEDERATION_REGISTER: read live helm release values for {}/{} ({} keys)",
                    namespace, releaseName, values.size());
        }

        // Surface a clear error early if the operator turned the toggle off (e.g. by
        // editing values then hitting Reapply) instead of letting OM REST fail mid-flow.
        Object enabledRaw = ConfigResolutionService.getByDottedPath(values, "atlasFederation.enabled");
        if (enabledRaw == null || !"true".equalsIgnoreCase(String.valueOf(enabledRaw))) {
            throw new IllegalStateException("atlasFederation.enabled is false in the current release values — "
                    + "either enable the toggle in the wizard and upgrade the release, or run a reapply "
                    + "after fixing the values.");
        }

        // Atlas URL + bot user now come from the platform context (the per-service form fields
        // were removed). Resolve the context once; fall back to any legacy form values if present.
        org.apache.ambari.view.k8s.model.ResolvedContext rc = resolvePlatformContextForStep(childParams);

        String atlasHostPort = stringValue(ConfigResolutionService.getByDottedPath(values, "atlasFederation.hostPort"));
        if (atlasHostPort.isBlank() && rc != null) {
            atlasHostPort = stringValue(rc.getAtlasUrl());
        }
        if (atlasHostPort.isBlank()) {
            throw new IllegalStateException("Atlas URL could not be resolved from the platform context "
                    + "(context id='" + stringValue(childParams.get("_platformContextId")) + "'). "
                    + "Ensure the context's Atlas capability resolves (managed) or is filled (external).");
        }
        String atlasUsername = stringValue(ConfigResolutionService.getByDottedPath(values, "atlasFederation.username"));
        if (atlasUsername.isBlank() && rc != null) {
            // EXTERNAL contexts may carry the federation user; otherwise use the canonical default
            // (the KDPS-provisioned secret below also overrides this with the real provisioned user).
            atlasUsername = stringValue(rc.getResolvedFields().get("atlas.federationUser"));
        }
        if (atlasUsername.isBlank()) {
            atlasUsername = "openmetadata-federation";
        }
        String schedule = stringValue(ConfigResolutionService.getByDottedPath(values, "atlasFederation.schedule"));
        if (schedule.isBlank()) {
            schedule = "0 */6 * * *";
        }

        // Atlas credentials — three sources, in priority order:
        //   1. atlasFederation.password from form values (operator typed it in the wizard)
        //   2. atlasFederation.existingSecret named in form values, key `password`
        //      (operator pre-created the Secret out-of-band)
        //   3. <release>-atlas-federation Secret (KDPS-provisioned by the prior
        //      ATLAS_USER_PROVISION_OM step). This is the canonical path on
        //      Ambari-managed Atlas — the dispatcher queued ATLAS_USER_PROVISION_OM
        //      which created the Secret + wrote the hash to Ambari atlas-env.
        //
        // The username comes from the same Secret when source #3 is used (so the
        // basic auth on OM REST matches what ATLAS_USER_PROVISION_OM put in
        // users-credentials.properties on Atlas restart).
        String atlasPassword = stringValue(ConfigResolutionService.getByDottedPath(values, "atlasFederation.password"));
        String credsSource = "values.atlasFederation.password";
        if (atlasPassword.isBlank()) {
            String existingSecret = stringValue(
                    ConfigResolutionService.getByDottedPath(values, "atlasFederation.existingSecret"));
            if (!existingSecret.isBlank()) {
                io.fabric8.kubernetes.api.model.Secret sec =
                        this.kubernetesService.getSecret(namespace, existingSecret);
                if (sec != null && sec.getData() != null && sec.getData().containsKey("password")) {
                    atlasPassword = new String(
                            java.util.Base64.getDecoder().decode(sec.getData().get("password")),
                            java.nio.charset.StandardCharsets.UTF_8);
                    credsSource = "operator-secret:" + existingSecret;
                }
            }
        }
        if (atlasPassword.isBlank() && rc != null && rc.getAtlasFederationPassword() != null
                && !rc.getAtlasFederationPassword().isBlank()) {
            // EXTERNAL context carries the federation bot password.
            atlasPassword = rc.getAtlasFederationPassword();
            credsSource = "external-context:" + stringValue(childParams.get("_platformContextId"));
        }
        if (atlasPassword.isBlank()) {
            // Fallback: the KDPS-provisioned Secret (ATLAS_USER_PROVISION_OM result).
            String kdpsSecret = releaseName + "-atlas-federation";
            io.fabric8.kubernetes.api.model.Secret sec =
                    this.kubernetesService.getSecret(namespace, kdpsSecret);
            if (sec != null && sec.getData() != null && sec.getData().containsKey("password")) {
                atlasPassword = new String(
                        java.util.Base64.getDecoder().decode(sec.getData().get("password")),
                        java.nio.charset.StandardCharsets.UTF_8);
                // Also derive the username from this Secret — the dispatcher used a
                // canonical name and ATLAS_USER_PROVISION_OM persisted it here.
                if (sec.getData().containsKey("username")) {
                    String secretUsername = new String(
                            java.util.Base64.getDecoder().decode(sec.getData().get("username")),
                            java.nio.charset.StandardCharsets.UTF_8);
                    if (atlasUsername.equals("openmetadata-ingestion") || atlasUsername.isBlank()) {
                        // Operator left the wizard's "username" field at its OM-bot default;
                        // override with the actual federation user that exists in Atlas.
                        atlasUsername = secretUsername;
                    }
                }
                credsSource = "kdps-secret:" + kdpsSecret;
            }
        }
        if (atlasPassword.isBlank()) {
            throw new IllegalStateException("Atlas password not found — set atlasFederation.password in the wizard, "
                    + "point atlasFederation.existingSecret at an Opaque Secret with a 'password' key, OR ensure "
                    + "the ATLAS_USER_PROVISION_OM step ran successfully (it creates a per-release Secret named "
                    + "'" + releaseName + "-atlas-federation' that this step falls back to).");
        }
        LOG.info("OM_ATLAS_FEDERATION_REGISTER: using credentials source={} username={}", credsSource, atlasUsername);

        // databaseServiceNames is bound as a list (csvToList) in the OM service.json;
        // tolerate both list and CSV-string shapes for robustness.
        Object dbsRaw = ConfigResolutionService.getByDottedPath(values, "atlasFederation.databaseServiceNames");
        List<String> dbs = new ArrayList<>();
        if (dbsRaw instanceof List<?> l) {
            for (Object o : l) {
                if (o == null) continue;
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) dbs.add(s);
            }
        } else if (dbsRaw != null) {
            for (String s : String.valueOf(dbsRaw).split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) dbs.add(t);
            }
        }

        // Mint the JWT (chart-default Fernet unless the operator's <release>-fernet
        // Secret carries an override).
        String fernetKey = null;
        try {
            io.fabric8.kubernetes.api.model.Secret fernetSec =
                    this.kubernetesService.getSecret(namespace, releaseName + "-fernet");
            if (fernetSec != null && fernetSec.getData() != null
                    && fernetSec.getData().containsKey("fernet-key")) {
                fernetKey = new String(
                        java.util.Base64.getDecoder().decode(fernetSec.getData().get("fernet-key")),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ex) {
            LOG.warn("OM_ATLAS_FEDERATION_REGISTER: could not read '{}/{}-fernet' Secret, falling back to chart default: {}",
                    namespace, releaseName, ex.toString());
        }
        String jwt = org.apache.ambari.view.k8s.service.om.OmBotJwtClient.mintUnlimitedJwt(
                this.kubernetesService.getClient(),
                namespace, releaseName, fernetKey,
                java.time.Duration.ofSeconds(120));

        org.apache.ambari.view.k8s.service.om.OmAtlasFederationClient.Result result =
                org.apache.ambari.view.k8s.service.om.OmAtlasFederationClient.register(
                        this.kubernetesService.getClient(),
                        namespace, releaseName, jwt,
                        atlasHostPort, atlasUsername, atlasPassword,
                        dbs, schedule,
                        java.time.Duration.ofSeconds(180));

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("metadataServiceId", result.metadataServiceId);
        resultMap.put("databaseServiceName", result.databaseServiceName);
        resultMap.put("pipelineId", result.pipelineId);
        resultMap.put("deployStatus", result.deployStatus);
        resultMap.put("triggerStatus", result.triggerStatus);
        return gson.toJson(resultMap);
    }

    /**
     * Body of the {@code OM_HIVE_BASE_INGESTION_REGISTER} step — layer 1 of
     * Atlas+Hive federation. Mints an Unlimited-TTL JWT for the OM ingestion-bot,
     * then calls OM REST to create the Hive DatabaseService (e.g. {@code hive-clemlab}),
     * a metadata ingestion pipeline, deploys it (generates the Airflow DAG) and
     * triggers the first run. The Atlas federation step (queued after this) then
     * enriches the tables this creates.
     *
     * <p>Hive connection details come from the materialised helm values (the
     * {@code baseIngestion.*} form fields) with the selected platform context as
     * a fallback for host/port and auth mode. When the cluster is Kerberized the
     * connector uses SPNEGO over the HiveServer2 HTTP transport against the
     * ambient ccache maintained by the OM-deps Airflow kerberos renewer.
     *
     * <p>Idempotent: OM PUTs are upserts, so a replay registers no duplicates.
     */
    /**
     * Derive the OpenMetadata Hive connector scheme from HiveServer2 transport + TLS — the same
     * rule {@code ManagedContextResolver} applies to hive-site, lifted out so an EXTERNAL context
     * (no backing Ambari for KDPS to read) can supply raw transport properties and still have the
     * engine compute the scheme. http+TLS → {@code hive+https}, http → {@code hive+http},
     * anything else (binary) → {@code hive}.
     */
    static String computeHiveSchemeFromTransport(String transport, boolean ssl) {
        if (transport != null && transport.trim().equalsIgnoreCase("http")) {
            return ssl ? "hive+https" : "hive+http";
        }
        return "hive";
    }

    String registerOmHiveBaseIngestion(Map<String, Object> childParams) throws Exception {
        String releaseName = (String) childParams.get("releaseName");
        String namespace   = (String) childParams.get("namespace");
        if (releaseName == null || releaseName.isBlank() || namespace == null || namespace.isBlank()) {
            throw new IllegalStateException("Missing releaseName/namespace in OM_HIVE_BASE_INGESTION_REGISTER params");
        }

        // Same two value sources as the federation step: deploy-time valuesPath cache,
        // or live helm release values on a reapply.
        Map<String, Object> values = this.commandUtils.loadValuesFromParams(childParams);
        if (values == null || values.isEmpty()) {
            values = kubernetesService.getHelmReleaseValues(namespace, releaseName);
            if (values == null) values = Collections.emptyMap();
            LOG.info("OM_HIVE_BASE_INGESTION_REGISTER: read live helm release values for {}/{} ({} keys)",
                    namespace, releaseName, values.size());
        }

        Object enabledRaw = ConfigResolutionService.getByDottedPath(values, "baseIngestion.hiveEnabled");
        if (enabledRaw == null || !"true".equalsIgnoreCase(String.valueOf(enabledRaw))) {
            throw new IllegalStateException("baseIngestion.hiveEnabled is false in the current release values — "
                    + "enable the toggle in the wizard and upgrade the release, or run a reapply after fixing values.");
        }

        org.apache.ambari.view.k8s.model.ResolvedContext rc = resolvePlatformContextForStep(childParams);

        String serviceName = stringValue(ConfigResolutionService.getByDottedPath(values, "baseIngestion.hiveServiceName"));
        if (serviceName.isBlank()) serviceName = "hive-clemlab";
        // Connection scheme/host/auth are DELIVERED BY KDPS from the selected platform context —
        // computed from Ambari hive-site for a MANAGED context (hive.scheme / hive.hs2HostPort /
        // hive.authMode), or operator-supplied for an EXTERNAL one. The baseIngestion.* form fields
        // are optional OVERRIDES: blank means "use the value the context delivered". This is why the
        // operator never types 'hive+https' — KDPS derives it from transport.mode + use.SSL.
        java.util.Map<String, String> rf = (rc != null && rc.getResolvedFields() != null)
                ? rc.getResolvedFields() : java.util.Collections.<String, String>emptyMap();

        String scheme = stringValue(ConfigResolutionService.getByDottedPath(values, "baseIngestion.hiveScheme"));
        if (scheme.isBlank()) scheme = stringValue(rf.get("hive.scheme"));
        if (scheme.isBlank()) {
            // EXTERNAL context with no backing Ambari: the operator supplies raw HiveServer2
            // transport properties (transport.mode + TLS) and the KDPS engine derives the scheme
            // — the SAME rule ManagedContextResolver applies to hive-site — so the operator never
            // hand-types 'hive+https'.
            String transport = stringValue(ConfigResolutionService.getByDottedPath(values, "baseIngestion.hiveTransport"));
            if (!transport.isBlank()) {
                boolean ssl = "true".equalsIgnoreCase(stringValue(ConfigResolutionService.getByDottedPath(values, "baseIngestion.hiveUseSSL")));
                scheme = computeHiveSchemeFromTransport(transport, ssl);
            }
        }
        if (scheme.isBlank()) scheme = "hive+https";

        // host:port — override, else the context's HTTP-transport-aware endpoint (hive.hs2HostPort),
        // else fall back to parsing the legacy hive.hs2JdbcUrl (binary port).
        String hostPort = stringValue(ConfigResolutionService.getByDottedPath(values, "baseIngestion.hiveHostPort"));
        if (hostPort.isBlank()) hostPort = stringValue(rf.get("hive.hs2HostPort"));
        if (hostPort.isBlank()) {
            String hs2 = stringValue(rf.get("hive.hs2JdbcUrl"));
            if (hs2.startsWith("jdbc:hive2://")) {
                String hp = hs2.substring("jdbc:hive2://".length());
                int slash = hp.indexOf('/');
                if (slash >= 0) hp = hp.substring(0, slash);
                hostPort = hp.trim();
            }
        }
        if (hostPort.isBlank()) {
            throw new IllegalStateException("HiveServer2 host:port could not be resolved from the platform context "
                    + "(hive.hs2HostPort). Select a context whose Hive capability resolves, or set "
                    + "baseIngestion.hiveHostPort as an override.");
        }

        // auth mode — override, else the context's computed value, else infer from a kerberized cluster.
        String authMode = stringValue(ConfigResolutionService.getByDottedPath(values, "baseIngestion.hiveAuthMode"));
        if (authMode.isBlank()) authMode = stringValue(rf.get("hive.authMode"));
        if (authMode.isBlank()) {
            authMode = (rc != null && rc.getKerberosRealm() != null && !rc.getKerberosRealm().isBlank())
                    ? "kerberos" : "none";
        }
        String kerberosServiceName = stringValue(ConfigResolutionService.getByDottedPath(values, "baseIngestion.hiveKerberosServiceName"));
        if (kerberosServiceName.isBlank()) kerberosServiceName = "hive";

        // username/password only used for ldap/basic; null for kerberos/none.
        String username = stringValue(ConfigResolutionService.getByDottedPath(values, "baseIngestion.hiveUsername"));
        String password = stringValue(ConfigResolutionService.getByDottedPath(values, "baseIngestion.hivePassword"));

        String schedule = stringValue(ConfigResolutionService.getByDottedPath(values, "baseIngestion.hiveSchedule"));
        if (schedule.isBlank()) schedule = "0 */12 * * *";
        String schemaIncludes = stringValue(ConfigResolutionService.getByDottedPath(values, "baseIngestion.hiveSchemaIncludes"));

        LOG.info("OM_HIVE_BASE_INGESTION_REGISTER: service='{}' scheme='{}' hostPort='{}' auth='{}' schedule='{}'",
                serviceName, scheme, hostPort, authMode, schedule);

        // Layer 1.5: grant the provisioned OM ingestion principal read access in the Hive Ranger
        // service so its metadata queries aren't denied. Only for kerberos auth (the provisioned
        // oma-ing principal); ldap/basic deployments use an operator-managed Hive user. Non-fatal —
        // a grant failure logs a warning but still deploys the pipeline (operator can grant manually).
        if ("kerberos".equals(authMode)) {
            grantHiveRangerReadForOmIngestion(childParams, rc, namespace, releaseName);
        }

        // Mint the JWT (chart-default Fernet unless the operator's <release>-fernet override exists).
        String fernetKey = null;
        try {
            io.fabric8.kubernetes.api.model.Secret fernetSec =
                    this.kubernetesService.getSecret(namespace, releaseName + "-fernet");
            if (fernetSec != null && fernetSec.getData() != null
                    && fernetSec.getData().containsKey("fernet-key")) {
                fernetKey = new String(
                        java.util.Base64.getDecoder().decode(fernetSec.getData().get("fernet-key")),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ex) {
            LOG.warn("OM_HIVE_BASE_INGESTION_REGISTER: could not read '{}/{}-fernet' Secret, using chart default: {}",
                    namespace, releaseName, ex.toString());
        }
        String jwt = org.apache.ambari.view.k8s.service.om.OmBotJwtClient.mintUnlimitedJwt(
                this.kubernetesService.getClient(),
                namespace, releaseName, fernetKey,
                java.time.Duration.ofSeconds(120));

        org.apache.ambari.view.k8s.service.om.OmBaseIngestionClient.Result result =
                org.apache.ambari.view.k8s.service.om.OmBaseIngestionClient.register(
                        this.kubernetesService.getClient(),
                        namespace, releaseName, jwt,
                        serviceName, "Hive", scheme, hostPort,
                        authMode, kerberosServiceName,
                        username.isBlank() ? null : username,
                        password.isBlank() ? null : password,
                        schedule, schemaIncludes,
                        java.time.Duration.ofSeconds(180));

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("databaseServiceId", result.databaseServiceId);
        resultMap.put("databaseServiceName", result.databaseServiceName);
        resultMap.put("pipelineId", result.pipelineId);
        resultMap.put("deployStatus", result.deployStatus);
        resultMap.put("triggerStatus", result.triggerStatus);
        return gson.toJson(resultMap);
    }

    /**
     * Grant the provisioned OM ingestion principal ({@code oma-ing-<ns>}) read access in the Hive
     * Ranger service so the SPNEGO base-ingestion's metadata queries aren't denied by the Hive
     * plugin. MANAGED context → delegate to the Ambari server (it holds the Ranger admin creds, the
     * view never sees them); EXTERNAL context → direct Ranger REST with the context creds; no Ranger
     * in context → log that the operator must grant it. Non-fatal: any failure is logged and the
     * ingestion pipeline is still deployed (operator can grant manually).
     */
    private void grantHiveRangerReadForOmIngestion(Map<String, Object> childParams,
            org.apache.ambari.view.k8s.model.ResolvedContext rc, String namespace, String releaseName) {
        // Ranger maps the kerberos principal oma-ing-<ns>@<realm> to user oma-ing-<ns> (KDPS kerberos
        // block: serviceName 'oma-ing', principalTemplate '{service}-{namespace}@{realm}').
        String principal = "oma-ing-" + namespace;
        String cluster = stringValue(childParams.get("_cluster"));
        String hiveService = cluster.isBlank() ? "hive" : cluster + "_hive";
        String resources = "{\"database\":[\"*\"],\"table\":[\"*\"],\"column\":[\"*\"]}";
        String policyName = "kdps-openmetadata-" + releaseName + "-hive-read";
        try {
            String baseUriStr = stringValue(childParams.get("_baseUri"));
            if (rc != null && rc.isRangerManaged() && !cluster.isBlank() && !baseUriStr.isBlank()) {
                Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(childParams.get("_callerHeaders"));
                AmbariActionClient ambari = new AmbariActionClient(ctx,
                        java.net.URI.create(baseUriStr).resolve("/api/v1").toString(), cluster, authHeaders);
                int req = ambari.submitRangerPolicyGrant(hiveService, principal, "select", resources,
                        policyName, "KDPS: OpenMetadata Hive metadata-ingestion read access", 60,
                        "KDPS base ingestion: grant Hive select to " + principal);
                if (ambari.waitUntilComplete(req, 90, java.util.concurrent.TimeUnit.SECONDS)) {
                    LOG.info("OM_HIVE_BASE_INGESTION: granted Hive 'select' to '{}' on '{}' via Ambari (req {})",
                            principal, hiveService, req);
                } else {
                    LOG.warn("OM_HIVE_BASE_INGESTION: Ambari ranger_policy request {} for '{}' did not complete; "
                            + "grant Hive access manually if ingestion is denied.", req, principal);
                }
            } else if (rc != null && rc.getRangerUrl() != null && !rc.getRangerUrl().isBlank()
                    && rc.getRangerAdminUsername() != null && !rc.getRangerAdminUsername().isBlank()) {
                long pid = org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.createOrFindHiveReadPolicy(
                        rc.getRangerUrl(), rc.getRangerAdminUsername(), rc.getRangerAdminPassword(),
                        hiveService, policyName, principal, java.util.concurrent.TimeUnit.SECONDS.toMillis(45));
                LOG.info("OM_HIVE_BASE_INGESTION: external Ranger Hive read policy id={} for '{}' on '{}'",
                        pid, principal, hiveService);
            } else {
                LOG.warn("OM_HIVE_BASE_INGESTION: Ranger is not part of the platform context — grant Hive read "
                        + "access to '{}' manually (Ranger service '{}', database/table/column=* with 'select'), "
                        + "or the Hive plugin will deny the ingestion's metadata queries.", principal, hiveService);
            }
        } catch (Exception e) {
            LOG.warn("OM_HIVE_BASE_INGESTION: Ranger Hive grant for '{}' failed ({}). Pipeline still deployed; "
                    + "grant access manually if needed.", principal, e.toString());
        }
    }

    private static String stringValue(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /**
     * Dispatch helper: looks at the deploy-time atlas-federation context (the
     * resolved Atlas endpoint + auth/acl modes — either discovered from the
     * Ambari-managed Atlas or operator-supplied for an external Atlas) and
     * queues the right Atlas-side provisioning steps BEFORE the final
     * OM_ATLAS_FEDERATION_REGISTER step (which the caller queues after this
     * returns).
     *
     * <p>Step matrix (Ambari-managed Atlas):
     * <pre>
     *   basic   + simple   → ATLAS_USER_PROVISION_OM, ATLAS_SIMPLE_AUTHZ_GRANT_OM
     *   basic   + ranger   → ATLAS_USER_PROVISION_OM, RANGER_POLICY_CREATE_ATLAS_OM_READ
     *   kerberos + simple  → (no user provision)    , ATLAS_SIMPLE_AUTHZ_GRANT_OM (principal-based)
     *   kerberos + ranger  → (no user provision)    , RANGER_POLICY_CREATE_ATLAS_OM_READ (principal-based)
     *   ldap     + any     → (no user provision)    , ACL step keyed on the operator-typed LDAP user
     * </pre>
     *
     * <p>External Atlas (no Ambari-managed Atlas in the cluster) skips everything;
     * the caller's OM_ATLAS_FEDERATION_REGISTER step uses operator-supplied creds.
     */
    @SuppressWarnings("unchecked")
    /** True when the service definition declares a {@code platformOps} entry with the given op name. */
    private static boolean hasPlatformOp(StackServiceDef def, String opName) {
        if (def == null || def.platformOps == null) return false;
        for (Map<String, Object> op : def.platformOps) {
            if (op != null && opName.equals(String.valueOf(op.get("op")))) return true;
        }
        return false;
    }

    private void queueAtlasFederationProvisioningSteps(CommandEntity rootCommand,
                                                       HelmDeployRequest request,
                                                       Map<String, Object> params,
                                                       String cluster,
                                                       AmbariActionClient ambariActionClient) throws Exception {
        if (cluster == null || cluster.isBlank() || ambariActionClient == null) {
            LOG.info("Atlas federation dispatch: no Ambari cluster context — external Atlas only path, "
                    + "queueing OM_ATLAS_FEDERATION_REGISTER with operator-supplied creds.");
            return;
        }
        // Detect whether Atlas is Ambari-managed by probing for ATLAS_SERVER hosts.
        java.util.List<String> atlasHosts;
        try {
            atlasHosts = ambariActionClient.getComponentHosts(cluster, "ATLAS", "ATLAS_SERVER");
        } catch (Exception ex) {
            LOG.info("Atlas federation dispatch: Ambari ATLAS host probe failed ({}), "
                    + "assuming external Atlas.", ex.toString());
            return;
        }
        if (atlasHosts == null || atlasHosts.isEmpty()) {
            LOG.info("Atlas federation dispatch: no Ambari-managed ATLAS_SERVER in cluster '{}' — "
                    + "external Atlas path.", cluster);
            return;
        }

        // Resolve auth + acl modes from Atlas config (mirrors DiscoveryResource priority:
        // basic > ldap > kerberos — matches what OM's Atlas connector can actually use).
        String kerberosOn = ambariActionClient.getDesiredConfigProperty(cluster,
                "application-properties", "atlas.authentication.method.kerberos");
        String ldapOn = ambariActionClient.getDesiredConfigProperty(cluster,
                "application-properties", "atlas.authentication.method.ldap");
        String fileOn = ambariActionClient.getDesiredConfigProperty(cluster,
                "application-properties", "atlas.authentication.method.file");
        String authMode;
        if ("true".equalsIgnoreCase(fileOn != null ? fileOn.trim() : "")) {
            authMode = "basic";
        } else if ("true".equalsIgnoreCase(ldapOn != null ? ldapOn.trim() : "")) {
            authMode = "ldap";
        } else if ("true".equalsIgnoreCase(kerberosOn != null ? kerberosOn.trim() : "")) {
            authMode = "kerberos";
        } else {
            authMode = "none";
        }
        String authzImpl = ambariActionClient.getDesiredConfigProperty(cluster,
                "application-properties", "atlas.authorizer.impl");
        String aclMode = (authzImpl != null && authzImpl.trim().toLowerCase().contains("ranger"))
                ? "ranger" : "simple";

        LOG.info("Atlas federation dispatch: cluster='{}' detected authMode={} aclMode={} (Ambari-managed Atlas)",
                cluster, authMode, aclMode);

        // OM's Atlas connector ONLY speaks HTTP Basic (no kerberos/SSO). When Atlas is Ambari-managed
        // and federation is enabled, KDPS makes the Basic path work regardless of the currently-
        // detected authMode: the user-provision step asserts atlas.authentication.method.file=true
        // (idempotent; coexists with kerberos/ldap) and provisions the federation user. So drive the
        // Basic provisioning path even if file auth is currently off — otherwise federation would be
        // silently skipped on a kerberos/ldap-only Atlas and OM could never authenticate.
        if (!"basic".equals(authMode)) {
            LOG.info("Atlas federation dispatch: current authMode='{}' is not Basic, but OM requires it — KDPS will enable "
                    + "file auth (coexisting with existing methods) and provision the federation user.", authMode);
            authMode = "basic";
        }

        // Stash discovered modes on params so each step body can re-read them
        // (e.g. the principal kind decision in RANGER_POLICY_CREATE_ATLAS_OM_READ).
        params.put("_atlasAuthMode", authMode);
        params.put("_atlasAclMode", aclMode);

        // ----- 1) User provision (KDPS enables file auth + provisions the Basic user) -----
        if ("basic".equals(authMode)) {
            Map<String, Object> stepParams = new LinkedHashMap<>(params);
            stepParams.put("_atlasFederationRole", "ROLE_USER");
            // ④ operator-provided federation Secret (e.g. Vault via external-secrets) — the same
            //    Secret the OM-side connection reads (atlasFederation.existingSecret), keeping the
            //    Atlas-side hash and OM-side password in sync. Blank ⇒ KDPS generates + manages it.
            String existingSecret = stringValue(ConfigResolutionService.getByDottedPath(
                    request.getFormValues() != null ? request.getFormValues() : java.util.Collections.emptyMap(),
                    "atlasFederation.existingSecret"));
            if (!existingSecret.isBlank()) stepParams.put("_atlasFederationSecret", existingSecret);
            // ② operator's deploy-time Atlas-restart authorization (confirmation modal). Default:
            //    authorized; "false" ⇒ the step writes config but skips the restart (and warns).
            Object restartAuth = request.getFormValues() != null
                    ? request.getFormValues().get("_atlasRestartAuthorized") : null;
            if (restartAuth != null) stepParams.put("_atlasRestartAuthorized", String.valueOf(restartAuth));
            // Atlas REST URL for the post-write auth-probe — prefer the form's federation hostPort
            // (exactly what the OM connector will hit); the step derives it from cluster config when blank.
            String atlasHostPort = stringValue(ConfigResolutionService.getByDottedPath(
                    request.getFormValues() != null ? request.getFormValues() : java.util.Collections.emptyMap(),
                    "atlasFederation.hostPort"));
            if (!atlasHostPort.isBlank()) stepParams.put("_atlasHostPort", atlasHostPort);
            this.commandPlanFactory.createAtlasUserProvisionOm(rootCommand, stepParams);
            LOG.info("Atlas federation dispatch: queued ATLAS_USER_PROVISION_OM (federationSecret={}, restartAuthorized={})",
                    existingSecret.isBlank() ? "<kdps-managed>" : existingSecret,
                    restartAuth != null ? restartAuth : "<default:true>");
        }

        // ----- 2) ACL grant — simple OR ranger -----
        if ("simple".equals(aclMode)) {
            if ("basic".equals(authMode)) {
                // The simple-authz grant only makes sense if we have a user identity
                // recorded in atlas-env (which ATLAS_USER_PROVISION_OM just did).
                this.commandPlanFactory.createAtlasSimpleAuthzGrantOm(rootCommand, params);
                LOG.info("Atlas federation dispatch: queued ATLAS_SIMPLE_AUTHZ_GRANT_OM");
            } else {
                // Kerberos / LDAP + simple authz: requires writing the principal/user
                // into simple-authz-policy.json directly. Out of scope for this
                // iteration — log a clear warning so the operator knows the
                // federation will fail authz unless they patch the file by hand.
                LOG.warn("Atlas federation dispatch: ({},{}) requires writing principal '{}' into "
                                + "atlas-simple-authz-policy.json manually — KDPS does not yet automate "
                                + "this combination. The OM_ATLAS_FEDERATION_REGISTER REST call will "
                                + "likely return 403 until the operator grants access.",
                        authMode, aclMode, "openmetadata");
            }
        } else if ("ranger".equals(aclMode)) {
            Map<String, Object> stepParams = new LinkedHashMap<>(params);

            // Resolve the platform context. The wizard may pass a context id
            // (formValues.platformContextId); absent → the MANAGED default. A MANAGED
            // context delegates the Ranger grant to the Ambari server (no password handled
            // by the view); an EXTERNAL context carries its own Ranger URL + admin creds.
            Map<String, Object> formValues = request.getFormValues() != null
                    ? request.getFormValues()
                    : (request.getValues() != null ? request.getValues() : Collections.emptyMap());
            String platformContextId = stringValue(
                    ConfigResolutionService.getByDottedPath(formValues, "platformContextId"));
            org.apache.ambari.view.k8s.model.ResolvedContext rc =
                    new ContextService(ctx).resolve(platformContextId, ambariActionClient, cluster);
            stepParams.put("_platformContextId", platformContextId);

            // Principal to grant depends on Atlas auth mode.
            String principal;
            if ("kerberos".equals(authMode)) {
                // Reuse the OM ingestion-bot Kerberos principal KDPS already provisions
                // for the OM-Ranger TagSync source. Pattern: <serviceName>-<namespace>@<realm>.
                String kerbRealm = rc.getKerberosRealm();
                if (kerbRealm == null || kerbRealm.isBlank()) {
                    kerbRealm = ambariActionClient.getDesiredConfigProperty(cluster, "kerberos-env", "realm");
                }
                if (kerbRealm == null || kerbRealm.isBlank()) {
                    LOG.warn("Atlas federation dispatch: Kerberos auth mode but realm is empty — "
                            + "the Ranger grant step will fail at execute time.");
                }
                principal = "oma-ing-" + request.getNamespace() + "@"
                        + (kerbRealm == null ? "REALM-MISSING" : kerbRealm.trim());
            } else if ("basic".equals(authMode)) {
                principal = "openmetadata-federation";  // matches ATLAS_USER_PROVISION_OM default
            } else /* ldap */ {
                principal = stringValue(ConfigResolutionService.getByDottedPath(
                        request.getValues() != null ? request.getValues() : Collections.emptyMap(),
                        "atlasFederation.username"));
                if (principal.isBlank()) principal = "openmetadata-federation";
            }
            stepParams.put("_omPrincipal", principal);

            String atlasRepo = rc.getAtlasRangerServiceName();
            if (atlasRepo == null || atlasRepo.isBlank()) {
                atlasRepo = cluster + "_atlas";
            }
            stepParams.put("_atlasRangerServiceName", atlasRepo);

            if (rc.isRangerManaged()) {
                // MANAGED context → delegate to the Ambari server (server-side action reads
                // the decrypted ranger-env credentials). No password is handled by the view.
                this.commandPlanFactory.createRangerPolicyGrantViaAmbari(rootCommand, stepParams);
                LOG.info("Atlas federation dispatch: queued RANGER_POLICY_GRANT_VIA_AMBARI "
                        + "principal='{}' service='{}' (managed context)", principal, atlasRepo);
            } else {
                // EXTERNAL context → the view calls Ranger Admin REST directly with the
                // context's stored URL + credentials.
                stepParams.put("_rangerUrl", rc.getRangerUrl());
                stepParams.put("_rangerAdminUsername", rc.getRangerAdminUsername());
                stepParams.put("_rangerAdminPassword", rc.getRangerAdminPassword());
                stepParams.put("_atlasAuthMode", authMode);
                this.commandPlanFactory.createRangerPolicyCreateAtlasOmRead(rootCommand, stepParams);
                LOG.info("Atlas federation dispatch: queued RANGER_POLICY_CREATE_ATLAS_OM_READ "
                        + "principal='{}' service='{}' (external context '{}')",
                        principal, atlasRepo, rc.getName());
            }
        }
    }

    // ---------------------------------------------------------------------
    // Atlas federation provisioning steps (Ambari-managed Atlas only).
    //
    // Three discrete steps cooperate to grant the OpenMetadata federation
    // user the right to read from Atlas:
    //   1) ATLAS_USER_PROVISION_OM   — writes the Atlas-side basic-auth user
    //      (atlas-env props) + holds the plaintext password in a K8s Secret
    //      in the OM namespace so the subsequent OM_ATLAS_FEDERATION_REGISTER
    //      step can read it.
    //   2) ATLAS_SIMPLE_AUTHZ_GRANT_OM — for simple-authz Atlas authorizer:
    //      surfaces the user in the simple-authz-policy.json (the actual
    //      file merge happens in the ODP ATLAS package's metadata.py on
    //      Atlas restart).
    //   3) RANGER_POLICY_CREATE_ATLAS_OM_READ — for the Ranger Atlas
    //      authorizer: POST a read-only policy to Ranger Admin REST + poll
    //      until it's readable back (proxy signal for plugin-pull).
    //
    // Each is idempotent: re-running with the same effective inputs produces
    // no externally-visible change. That's load-bearing for replayability.
    //
    // None of these steps trigger an Atlas restart — the operator drives it
    // through Ambari's normal "Restart Required" badge. Atlas restart blast-
    // radius is larger than KDPS should auto-trigger (unlike Ranger TagSync
    // where we do).
    // ---------------------------------------------------------------------

    /**
     * Body of {@code ATLAS_USER_PROVISION_OM}. Reads (or generates + persists)
     * the OM federation credentials in a K8s Secret named
     * {@code <release>-atlas-federation}, then writes the resolved username +
     * SHA-256 password hash into the Ambari {@code atlas-env} config so the
     * ODP {@code metadata.py} hook materialises a corresponding line in
     * Atlas's {@code users-credentials.properties} on the next Atlas restart.
     *
     * @return JSON status: {@code {username, configChanged, secretName}}
     */
    @SuppressWarnings("unchecked")
    /**
     * Read-only config-drift check for a deployed OM release's Atlas federation. Compares the LIVE
     * Ambari Atlas config against what KDPS requires, so an operator who edits Atlas by hand (e.g.
     * disables file auth, removes the federation user, rotates the password) doesn't silently break
     * a running OM federation. Never mutates anything — purely diagnostic.
     *
     * <p>Checks: (1) {@code atlas.authentication.method.file=true} (OM's connector is Basic-only);
     * (2) the {@code openmetadata.federation.username} in atlas-env matches the release's K8s Secret;
     * (3) the password hash in atlas-env matches the Secret's password; (4) whether ATLAS_SERVER has
     * a pending stale-config restart. Returns a never-null report consumed by the KDPS view.
     */
    public Map<String, Object> checkAtlasFederationConfig(String namespace, String releaseName,
            Map<String, java.util.List<String>> callerHeaders, java.net.URI baseUri) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        java.util.List<Map<String, Object>> checks = new java.util.ArrayList<>();
        report.put("namespace", namespace);
        report.put("release", releaseName);
        report.put("checks", checks);

        Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(callerHeaders);
        String cluster = commandUtils.resolveClusterName(baseUri.toString(), authHeaders);
        AmbariActionClient ambari = new AmbariActionClient(ctx, baseUri.resolve("/api/v1").toString(), cluster, authHeaders);

        // Expected user/hash from the release's federation Secret (operator-provided or KDPS-managed).
        String secretName = releaseName + "-atlas-federation";
        String expUser = null, expHash = null, expPlain = null;
        try {
            io.fabric8.kubernetes.api.model.Secret sec = kubernetesService.getSecret(namespace, secretName);
            if (sec != null && sec.getData() != null) {
                java.util.Base64.Decoder dec = java.util.Base64.getDecoder();
                if (sec.getData().containsKey("username"))
                    expUser = new String(dec.decode(sec.getData().get("username")), java.nio.charset.StandardCharsets.UTF_8);
                if (sec.getData().containsKey("password")) {
                    expPlain = new String(dec.decode(sec.getData().get("password")), java.nio.charset.StandardCharsets.UTF_8);
                    expHash = org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.sha256Hex(expPlain);
                }
            }
        } catch (Exception ignore) { /* secret may be absent on a non-federated release */ }
        report.put("federationConfigured", expUser != null);

        String fileOn = ambari.getDesiredConfigProperty(cluster, "application-properties", "atlas.authentication.method.file");
        boolean fileOk = "true".equalsIgnoreCase(fileOn != null ? fileOn.trim() : "");
        checks.add(driftCheck("atlas.method.file", fileOk, "critical",
                fileOk ? "Atlas file-based (Basic) auth is enabled."
                        : "Atlas file-based auth is DISABLED (atlas.authentication.method.file=" + fileOn + "). OM's connector "
                          + "only speaks HTTP Basic, so ingestion will get 401.",
                fileOk ? null : "Re-run the OM deploy (KDPS re-asserts it idempotently) or set "
                          + "atlas.authentication.method.file=true in Ambari and restart Atlas."));

        String liveUser = ambari.getDesiredConfigProperty(cluster, "atlas-env", "openmetadata.federation.username");
        String liveHash = ambari.getDesiredConfigProperty(cluster, "atlas-env", "openmetadata.federation.password_hash");
        boolean userOk = expUser != null && expUser.equals(liveUser);
        checks.add(driftCheck("atlas.federation.user", userOk, "critical",
                (liveUser == null || liveUser.isBlank())
                        ? "No openmetadata.federation.username present in atlas-env."
                        : "atlas-env user='" + liveUser + "'" + (userOk ? " matches the release Secret."
                          : " does NOT match the release Secret user '" + expUser + "'."),
                userOk ? null : "Re-run the OM Atlas-federation step (actions/om-atlas-federation) to re-provision the user."));
        boolean hashOk = expHash != null && expHash.equals(liveHash);
        checks.add(driftCheck("atlas.federation.passwordHash", hashOk, "critical",
                hashOk ? "Federation password hash in atlas-env matches the release Secret."
                        : "Federation password hash in atlas-env does NOT match the release Secret (password rotated or edited by hand).",
                hashOk ? null : "Re-run the OM Atlas-federation step so atlas-env and the Secret are re-synced, then restart Atlas."));

        boolean restartPending = ambari.componentRestartRequired(cluster, "ATLAS", "ATLAS_SERVER");
        report.put("restartPending", restartPending);
        checks.add(driftCheck("atlas.restartPending", !restartPending, "warning",
                restartPending ? "Atlas has a pending config-restart (stale configs) — recent changes are not yet loaded, "
                          + "so the federation user may be inactive."
                        : "Atlas has no pending config-restart.",
                restartPending ? "Restart ATLAS_SERVER from Ambari so the latest users-credentials.properties is loaded." : null));

        // DECISIVE runtime check: does the federation user actually authenticate to Atlas RIGHT NOW?
        // users-credentials.properties is a DERIVED file (ODP metadata.py hook, regenerated only on restart),
        // so stale_configs above can read "clean" even when the user was never materialized. This probe is the
        // only signal that catches a config-correct-but-not-loaded Atlas (the exact gap that broke federation).
        String atlasRestUrl = org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.resolveAtlasRestUrl(ambari, cluster);
        org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.AtlasAuthProbe probe =
                (expUser != null && expPlain != null)
                        ? org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.probeFederationUser(atlasRestUrl, expUser, expPlain)
                        : org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.AtlasAuthProbe.UNKNOWN;
        boolean authActive = probe == org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.AtlasAuthProbe.LOADED;
        boolean authNotLoaded = probe == org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.AtlasAuthProbe.NOT_LOADED;
        report.put("federationUserActive", authActive);
        checks.add(driftCheck("atlas.federation.authProbe", authActive, authNotLoaded ? "critical" : "warning",
                authActive
                    ? "Federation user '" + expUser + "' authenticates to Atlas (" + atlasRestUrl + ") — federation is LIVE."
                : authNotLoaded
                    ? "Federation user '" + expUser + "' is NOT loaded in Atlas's running store (HTTP 401) at " + atlasRestUrl + ". "
                      + "atlas-env is correct, but Atlas hasn't materialized users-credentials.properties (regenerated only on restart)."
                    : "Could not probe Atlas auth at " + atlasRestUrl + " (no creds / unreachable) — inconclusive.",
                authNotLoaded
                    ? "Restart ATLAS_SERVER (the ODP hook writes the user into users-credentials.properties), or re-run the OM "
                      + "Atlas-federation deploy with restart authorized — the deploy now self-heals via this same probe."
                    : null));

        boolean ok = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("ok")) || !"critical".equals(c.get("severity")));
        report.put("ok", ok);
        LOG.info("checkAtlasFederationConfig({}/{}): ok={} fileOk={} userOk={} hashOk={} restartPending={} authProbe={}",
                namespace, releaseName, ok, fileOk, userOk, hashOk, restartPending, probe);
        return report;
    }

    private static Map<String, Object> driftCheck(String id, boolean ok, String severity, String detail, String remediation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("ok", ok);
        m.put("severity", severity);
        m.put("detail", detail);
        if (remediation != null) m.put("remediation", remediation);
        return m;
    }

    /**
     * One-click "Fix &amp; restart Atlas" from the config-check modal. Re-asserts the federation config
     * idempotently (file auth + atlas-env user/hash from the release Secret) and submits an ATLAS_SERVER
     * restart, returning the Ambari request id so the UI can poll progress. Non-blocking. The federation
     * user becomes active once Atlas finishes restarting (the ODP metadata.py hook materializes it).
     */
    public Map<String, Object> fixAndRestartAtlasFederation(String namespace, String releaseName,
            Map<String, java.util.List<String>> callerHeaders, java.net.URI baseUri) throws Exception {
        Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(callerHeaders);
        String cluster = commandUtils.resolveClusterName(baseUri.toString(), authHeaders);
        AmbariActionClient ambari = new AmbariActionClient(ctx, baseUri.resolve("/api/v1").toString(), cluster, authHeaders);

        org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.Credentials creds =
                org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.resolveOrCreate(
                        this.kubernetesService.getClient(), namespace, releaseName, null);
        boolean fileAuthChanged = org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.ensureFileAuthEnabled(ambari, cluster);
        boolean envChanged = org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.writeAtlasEnvConfig(
                ambari, cluster, creds.username, creds.passwordSha256Hex, "ROLE_USER");

        int requestId = ambari.submitComponentRestart(cluster, "ATLAS", "ATLAS_SERVER",
                "KDPS: fix & restart Atlas to load OpenMetadata federation user '" + creds.username + "'");
        String atlasUrl = org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.resolveAtlasRestUrl(ambari, cluster);

        LOG.info("fixAndRestartAtlasFederation({}/{}): user='{}' fileAuthChanged={} envChanged={} atlasRestartRequestId={}",
                namespace, releaseName, creds.username, fileAuthChanged, envChanged, requestId);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("requestId", requestId);
        r.put("username", creds.username);
        r.put("atlasUrl", atlasUrl);
        r.put("configChanged", fileAuthChanged || envChanged);
        r.put("message", "Atlas restart submitted (request " + requestId
                + "). The federation user activates once Atlas finishes restarting.");
        return r;
    }

    /** Proxy an Ambari request's live status + progress (for the Fix &amp; restart progress bar). */
    public Map<String, Object> getAmbariRequestProgress(int requestId,
            Map<String, java.util.List<String>> callerHeaders, java.net.URI baseUri) throws Exception {
        Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(callerHeaders);
        String cluster = commandUtils.resolveClusterName(baseUri.toString(), authHeaders);
        AmbariActionClient ambari = new AmbariActionClient(ctx, baseUri.resolve("/api/v1").toString(), cluster, authHeaders);
        return ambari.getRequestProgress(requestId);
    }

    String provisionAtlasUserForOm(Map<String, Object> childParams) throws Exception {
        String releaseName = (String) childParams.get("releaseName");
        String namespace   = (String) childParams.get("namespace");
        String cluster     = (String) childParams.get("_cluster");
        if (releaseName == null || namespace == null || cluster == null) {
            throw new IllegalStateException("Missing releaseName/namespace/_cluster in ATLAS_USER_PROVISION_OM params");
        }
        String overrideUsername = stringValue(childParams.get("_atlasFederationUsername"));
        String role = stringValue(childParams.get("_atlasFederationRole"));
        if (role.isBlank()) role = "ROLE_USER";

        // 1. Resolve federation credentials. The operator may supply their own Secret
        //    (e.g. Vault-synced via external-secrets) through atlasFederation.existingSecret; else
        //    KDPS generates + manages <release>-atlas-federation. Same Secret feeds the OM side.
        String operatorSecret = stringValue(childParams.get("_atlasFederationSecret"));
        org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.Credentials creds =
                org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.resolveOrCreate(
                        this.kubernetesService.getClient(), namespace, releaseName, overrideUsername, operatorSecret);

        // 2. Ambari config (both merge-idempotent → no-op causes no new config version):
        //    (a) assert file-based auth is enabled — OM's Atlas connector only speaks HTTP Basic;
        //    (b) write the federation user's hash into atlas-env (→ users-credentials.properties).
        String baseUriStr = (String) childParams.get("_baseUri");
        Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(childParams.get("_callerHeaders"));
        java.net.URI baseUri = java.net.URI.create(baseUriStr);
        AmbariActionClient ambari = new AmbariActionClient(ctx, baseUri.resolve("/api/v1").toString(), cluster, authHeaders);
        boolean fileAuthChanged = org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.ensureFileAuthEnabled(ambari, cluster);
        boolean envChanged = org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.writeAtlasEnvConfig(
                ambari, cluster, creds.username, creds.passwordSha256Hex, role);

        // 3. Decide if Atlas needs a restart. Atlas loads its file user store only at startup, so the
        //    federation user is active only after a restart. The DECISIVE signal is a live auth-probe:
        //    users-credentials.properties is a DERIVED file the ODP metadata.py hook regenerates ONLY on
        //    restart, so Ambari's stale_configs (which tracks atlas-env, not that file) can read "false"
        //    even when the user was never materialized into the running store. So probe Atlas directly:
        //      NOT_LOADED (401) → restart (materializes the user);
        //      LOADED (200/403) → already active → never restart (idempotent, even if we just rewrote config);
        //      UNKNOWN (can't reach Atlas) → fall back to the config-change/stale heuristic.
        //    The restart is gated on the operator's deploy-time authorization; a failed/slow restart is
        //    logged, not fatal.
        boolean configChanged = fileAuthChanged || envChanged;
        boolean staleConfigs = ambari.componentRestartRequired(cluster, "ATLAS", "ATLAS_SERVER");
        String atlasRestUrl = stringValue(childParams.get("_atlasHostPort"));
        if (atlasRestUrl.isBlank()) {
            atlasRestUrl = org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.resolveAtlasRestUrl(ambari, cluster);
        }
        org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.AtlasAuthProbe probe =
                org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.probeFederationUser(
                        atlasRestUrl, creds.username, creds.plaintextPassword);
        boolean needsRestart;
        String restartReason;
        switch (probe) {
            case LOADED:
                needsRestart = false; restartReason = "auth-probe: user already authenticates (active)"; break;
            case NOT_LOADED:
                needsRestart = true;  restartReason = "auth-probe: user NOT loaded in Atlas (401) — restart materializes it"; break;
            default: // UNKNOWN
                needsRestart = configChanged || staleConfigs;
                restartReason = "auth-probe inconclusive — fallback (configChanged=" + configChanged + ", stale=" + staleConfigs + ")";
        }
        boolean restartAuthorized = !"false".equalsIgnoreCase(stringValue(childParams.get("_atlasRestartAuthorized")));

        LOG.info("ATLAS_USER_PROVISION_OM: release='{}' user='{}' role='{}' secret='{}' credsSource={} fileAuthChanged={} "
                        + "envChanged={} staleConfigs={} authProbe={} atlasUrl='{}' needsRestart={} ({}) restartAuthorized={}",
                releaseName, creds.username, role,
                (operatorSecret != null && !operatorSecret.isBlank() ? operatorSecret : releaseName + "-atlas-federation"),
                creds.preExisting ? "operator-or-existing" : "kdps-generated",
                fileAuthChanged, envChanged, staleConfigs, probe, atlasRestUrl, needsRestart, restartReason, restartAuthorized);

        Integer atlasRestartRequestId = null;
        boolean restartSkipped = false;
        org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.AtlasAuthProbe postProbe = probe;
        if (needsRestart && restartAuthorized) {
            try {
                int reqId = ambari.submitComponentRestart(cluster, "ATLAS", "ATLAS_SERVER",
                        "KDPS: restart Atlas to activate the OpenMetadata federation user '" + creds.username + "'");
                atlasRestartRequestId = reqId;
                boolean ok = ambari.waitUntilComplete(reqId, 900L, java.util.concurrent.TimeUnit.SECONDS);
                if (ok) {
                    // Re-probe to CONFIRM the restart actually materialized + activated the user (closes the loop).
                    postProbe = org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.probeFederationUser(
                            atlasRestUrl, creds.username, creds.plaintextPassword);
                    if (postProbe == org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.AtlasAuthProbe.NOT_LOADED) {
                        LOG.warn("ATLAS_USER_PROVISION_OM: Atlas restarted (requestId={}) but federation user '{}' STILL gets 401 — "
                                + "verify atlas.authentication.method.file=true is in effect and the ODP metadata.py hook ran.",
                                reqId, creds.username);
                    } else {
                        LOG.info("ATLAS_USER_PROVISION_OM: Atlas restarted (requestId={}); federation user '{}' post-restart probe={} ({}).",
                                reqId, creds.username, postProbe,
                                postProbe == org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.AtlasAuthProbe.LOADED
                                        ? "now authenticates" : "could not re-probe — assume active once Atlas is fully up");
                    }
                } else {
                    LOG.warn("ATLAS_USER_PROVISION_OM: Atlas restart (requestId={}) did not complete in time; the federation "
                            + "user may not be active yet — OM ingestion could see 401 until Atlas finishes restarting.", reqId);
                }
            } catch (Exception ex) {
                LOG.warn("ATLAS_USER_PROVISION_OM: could not trigger Atlas restart via Ambari ({}). The user IS written to "
                        + "atlas-env — restart ATLAS_SERVER manually so Atlas loads the federation user.", ex.toString());
            }
        } else if (needsRestart) {
            restartSkipped = true;
            LOG.warn("ATLAS_USER_PROVISION_OM: Atlas restart REQUIRED ({}) but the operator chose to SKIP. "
                    + "Federation user '{}' will NOT be active until ATLAS_SERVER is restarted — OM ingestion will 401 until then.",
                    restartReason, creds.username);
        } else {
            LOG.info("ATLAS_USER_PROVISION_OM: no Atlas restart needed ({}).", restartReason);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", creds.username);
        result.put("role", role);
        result.put("secretName", (operatorSecret != null && !operatorSecret.isBlank()) ? operatorSecret : releaseName + "-atlas-federation");
        result.put("credentialSource", creds.preExisting ? "operator-or-existing" : "kdps-generated");
        result.put("fileAuthChanged", fileAuthChanged);
        result.put("configChanged", configChanged);
        result.put("authProbe", probe.toString());
        result.put("needsRestart", needsRestart);
        result.put("restartAuthorized", restartAuthorized);
        result.put("restartRequiredFor", "ATLAS/ATLAS_SERVER");
        if (atlasRestartRequestId != null) {
            result.put("atlasRestartRequestId", atlasRestartRequestId);
            result.put("federationActiveAfterRestart",
                    postProbe == org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.AtlasAuthProbe.LOADED);
        }
        if (restartSkipped) result.put("atlasRestartSkipped", true);
        return gson.toJson(result);
    }

    /**
     * Body of {@code ATLAS_SIMPLE_AUTHZ_GRANT_OM}. The real file merge happens
     * in the ODP {@code metadata.py} hook on Atlas restart (it reads
     * {@code atlas-env.openmetadata.federation.role} and appends the user→role
     * mapping into {@code atlas-simple-authz-policy.json}). This step exists
     * so the operator's command tree shows two clear lines (user provisioned
     * + ACL granted), and so we can re-check the configured role in case
     * the dispatch flow was customized.
     *
     * <p>The step body itself is a thin verification — it confirms the
     * three required atlas-env keys are set. The actual ACL is applied on
     * Atlas restart by the stack hook.
     */
    @SuppressWarnings("unchecked")
    String grantAtlasSimpleAuthzForOm(Map<String, Object> childParams) throws Exception {
        String cluster = (String) childParams.get("_cluster");
        String baseUriStr = (String) childParams.get("_baseUri");
        if (cluster == null || baseUriStr == null) {
            throw new IllegalStateException("Missing _cluster/_baseUri in ATLAS_SIMPLE_AUTHZ_GRANT_OM params");
        }
        Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(childParams.get("_callerHeaders"));
        java.net.URI baseUri = java.net.URI.create(baseUriStr);
        AmbariActionClient ambari = new AmbariActionClient(ctx, baseUri.resolve("/api/v1").toString(), cluster, authHeaders);

        String user = ambari.getDesiredConfigProperty(cluster, "atlas-env", "openmetadata.federation.username");
        String hash = ambari.getDesiredConfigProperty(cluster, "atlas-env", "openmetadata.federation.password_hash");
        String role = ambari.getDesiredConfigProperty(cluster, "atlas-env", "openmetadata.federation.role");
        if (user == null || user.isBlank() || hash == null || hash.isBlank()) {
            throw new IllegalStateException("ATLAS_SIMPLE_AUTHZ_GRANT_OM: expected "
                    + "atlas-env.openmetadata.federation.username + .password_hash to be set "
                    + "by the previous ATLAS_USER_PROVISION_OM step but they're empty. Check the command tree.");
        }
        LOG.info("ATLAS_SIMPLE_AUTHZ_GRANT_OM: user='{}' role='{}' — ACL will be applied to "
                        + "atlas-simple-authz-policy.json by the ODP metadata.py hook on next Atlas restart.",
                user, role);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", user);
        result.put("role", role);
        result.put("aclTarget", "atlas-simple-authz-policy.json");
        result.put("appliedBy", "ODP/ATLAS metadata.py on next Atlas restart");
        result.put("restartRequiredFor", "ATLAS/ATLAS_SERVER");
        return gson.toJson(result);
    }

    /**
     * Body of {@code RANGER_POLICY_CREATE_ATLAS_OM_READ} — the EXTERNAL-context path.
     * The Ranger Admin URL + credentials come from the resolved external context
     * (params {@code _rangerUrl} / {@code _rangerAdminUsername} / {@code _rangerAdminPassword}),
     * since an external cluster has no {@code ranger-env} on this Ambari to read. Delegates
     * to {@code OmAtlasProvisioning.createOrFindAtlasReadPolicy} (idempotent grant + poll).
     *
     * <p>MANAGED contexts never reach here — they use {@code RANGER_POLICY_GRANT_VIA_AMBARI},
     * which delegates to the Ambari server so the view never handles the Ranger password.
     *
     * @return JSON: {@code {policyId, policyName, principal, atlasServiceName}}
     */
    String createRangerAtlasReadPolicyForOm(Map<String, Object> childParams) throws Exception {
        String cluster = (String) childParams.get("_cluster");
        String releaseName = (String) childParams.get("releaseName");

        String rangerUrl  = stringValue(childParams.get("_rangerUrl"));
        String rangerUser = stringValue(childParams.get("_rangerAdminUsername"));
        String rangerPass = stringValue(childParams.get("_rangerAdminPassword"));
        if (rangerUrl.isBlank() || rangerUser.isBlank() || rangerPass.isBlank()) {
            throw new IllegalStateException(
                    "External-context Ranger grant requires _rangerUrl + _rangerAdminUsername + "
                    + "_rangerAdminPassword in step params (set on the EXTERNAL platform context). "
                    + "A MANAGED context should use RANGER_POLICY_GRANT_VIA_AMBARI instead.");
        }

        String atlasService = stringValue(childParams.get("_atlasRangerServiceName"));
        if (atlasService.isBlank()) atlasService = (cluster == null ? "" : cluster) + "_atlas";

        String principal = stringValue(childParams.get("_omPrincipal"));
        boolean isKerberos = "kerberos".equalsIgnoreCase(stringValue(childParams.get("_atlasAuthMode")));
        if (principal.isBlank()) {
            throw new IllegalStateException("Missing _omPrincipal in RANGER_POLICY_CREATE_ATLAS_OM_READ params.");
        }

        String policyName = "kdps-openmetadata-" + releaseName + "-read";
        LOG.info("RANGER_POLICY_CREATE_ATLAS_OM_READ (external): rangerUrl='{}' atlasService='{}' principal='{}' "
                + "isKerberos={} policyName='{}'", rangerUrl, atlasService, principal, isKerberos, policyName);

        long policyId = org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.createOrFindAtlasReadPolicy(
                rangerUrl, rangerUser, rangerPass, atlasService, policyName, principal, isKerberos,
                java.util.concurrent.TimeUnit.SECONDS.toMillis(45));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policyId", policyId);
        result.put("policyName", policyName);
        result.put("atlasServiceName", atlasService);
        result.put("principal", principal);
        result.put("rangerUrl", rangerUrl);
        return gson.toJson(result);
    }

    /**
     * Body of {@code RANGER_POLICY_GRANT_VIA_AMBARI} — the MANAGED-context path. Delegates the
     * Ranger grant to the Ambari server (POST {@code /clusters/{c}/ranger_policy}), which runs
     * {@code EnsureRangerPolicyServerAction} with the Ranger admin credentials it holds — the
     * view never sees the password.
     *
     * <p>Issues two grants because the Atlas Ranger servicedef forbids a single policy from
     * spanning both resource sets:
     * <ul>
     *   <li>entities: {@code (entity-type, entity-classification, entity)} → {@code entity-read}</li>
     *   <li>types: {@code (type-category, type)} → {@code type-read}</li>
     * </ul>
     *
     * @return JSON: {@code {atlasServiceName, principal, entitiesRequestId, typesRequestId}}
     */
    String grantRangerAtlasReadViaAmbari(Map<String, Object> childParams) throws Exception {
        String cluster = (String) childParams.get("_cluster");
        String releaseName = (String) childParams.get("releaseName");
        String baseUriStr = (String) childParams.get("_baseUri");
        if (cluster == null || baseUriStr == null) {
            throw new IllegalStateException("Missing _cluster/_baseUri in RANGER_POLICY_GRANT_VIA_AMBARI params");
        }
        Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(childParams.get("_callerHeaders"));
        java.net.URI baseUri = java.net.URI.create(baseUriStr);
        AmbariActionClient ambari = new AmbariActionClient(ctx,
                baseUri.resolve("/api/v1").toString(), cluster, authHeaders);

        String atlasService = stringValue(childParams.get("_atlasRangerServiceName"));
        if (atlasService.isBlank()) atlasService = cluster + "_atlas";

        String principal = stringValue(childParams.get("_omPrincipal"));
        if (principal.isBlank()) {
            throw new IllegalStateException("Missing _omPrincipal in RANGER_POLICY_GRANT_VIA_AMBARI params.");
        }

        String base = "kdps-openmetadata-" + releaseName + "-read";
        int timeoutSeconds = 60;

        // Grant 1: entity read on the entity resource set.
        int entitiesReq = ambari.submitRangerPolicyGrant(
                atlasService, principal, "entity-read",
                "{\"entity-type\":[\"*\"],\"entity-classification\":[\"*\"],\"entity\":[\"*\"]}",
                base + "-entities",
                "KDPS: OpenMetadata federation read access (entities)",
                timeoutSeconds,
                "KDPS Atlas federation: grant entity-read to " + principal);
        if (!ambari.waitUntilComplete(entitiesReq, timeoutSeconds + 30, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("Ambari ranger_policy request " + entitiesReq
                    + " (entities) did not complete successfully");
        }

        // Grant 2: type read on the type resource set.
        int typesReq = ambari.submitRangerPolicyGrant(
                atlasService, principal, "type-read",
                "{\"type-category\":[\"*\"],\"type\":[\"*\"]}",
                base + "-types",
                "KDPS: OpenMetadata federation read access (types)",
                timeoutSeconds,
                "KDPS Atlas federation: grant type-read to " + principal);
        if (!ambari.waitUntilComplete(typesReq, timeoutSeconds + 30, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("Ambari ranger_policy request " + typesReq
                    + " (types) did not complete successfully");
        }

        LOG.info("RANGER_POLICY_GRANT_VIA_AMBARI: granted entity-read+type-read to '{}' on '{}' "
                + "(Ambari requests {} + {})", principal, atlasService, entitiesReq, typesReq);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("atlasServiceName", atlasService);
        result.put("principal", principal);
        result.put("entitiesRequestId", entitiesReq);
        result.put("typesRequestId", typesReq);
        result.put("via", "ambari-server-action");
        return gson.toJson(result);
    }

    /**
     * Body of {@link CommandType#RANGER_POLICY_GRANT_HIVE_READ}: grants the deploying service's
     * Kerberos principal {@code select} on the Hive Ranger service repo, delegating to the Ambari
     * server (POST {@code /clusters/{c}/ranger_policy}) so the KDPS view never handles the Ranger
     * admin password. A single grant covers the full database/table/column resource set, which is
     * what Hive needs for {@code DESCRIBE}/column reflection.
     *
     * <p>Params (threaded from the post-deploy dispatch): {@code _cluster}, {@code _baseUri},
     * {@code _callerHeaders} (Ambari plumbing), {@code _principal} (Ranger short name, e.g.
     * {@code superset-dashboard-<ns>}), {@code _hiveRangerServiceName} (defaults to
     * {@code <cluster>_hive}), {@code releaseName} (policy-name uniqueness).
     */
    String grantRangerHiveReadViaAmbari(Map<String, Object> childParams) throws Exception {
        String cluster = (String) childParams.get("_cluster");
        String releaseName = (String) childParams.get("releaseName");
        String baseUriStr = (String) childParams.get("_baseUri");
        if (cluster == null || baseUriStr == null) {
            throw new IllegalStateException("Missing _cluster/_baseUri in RANGER_POLICY_GRANT_HIVE_READ params");
        }
        Map<String, String> authHeaders = AmbariActionClient.toAuthHeaders(childParams.get("_callerHeaders"));
        java.net.URI baseUri = java.net.URI.create(baseUriStr);
        AmbariActionClient ambari = new AmbariActionClient(ctx,
                baseUri.resolve("/api/v1").toString(), cluster, authHeaders);

        String hiveService = stringValue(childParams.get("_hiveRangerServiceName"));
        if (hiveService.isBlank()) hiveService = cluster + "_hive";

        String principal = stringValue(childParams.get("_principal"));
        if (principal.isBlank()) {
            throw new IllegalStateException("Missing _principal in RANGER_POLICY_GRANT_HIVE_READ params.");
        }

        String policyName = "kdps-" + releaseName + "-hive-read";
        int timeoutSeconds = 60;

        // One grant: select on database/table/column. Hive maps DESCRIBE (and thus Superset's
        // column reflection) to the select privilege, so this is the minimum that unblocks
        // "tables list but show no columns".
        int req = ambari.submitRangerPolicyGrant(
                hiveService, principal, "select",
                "{\"database\":[\"*\"],\"table\":[\"*\"],\"column\":[\"*\"]}",
                policyName,
                "KDPS: Superset service user Hive read (table/column reflection)",
                timeoutSeconds,
                "KDPS Superset->Hive: grant select to " + principal);
        if (!ambari.waitUntilComplete(req, timeoutSeconds + 30, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("Ambari ranger_policy request " + req
                    + " (hive read) did not complete successfully");
        }

        LOG.info("RANGER_POLICY_GRANT_HIVE_READ: granted select to '{}' on '{}' (Ambari request {})",
                principal, hiveService, req);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hiveServiceName", hiveService);
        result.put("principal", principal);
        result.put("requestId", req);
        result.put("via", "ambari-server-action");
        return gson.toJson(result);
    }

    /**
     * Routes an operator-supplied Kerberos credentials Secret (declared via a form field
     * with {@code credentialKind: "kerberos"}) into the chart's existing
     * {@code global.security.kerberos.*} pipeline. Used when targeting an external
     * Kerberized service (e.g. Hive Metastore on a foreign realm) — the operator pre-creates
     * a K8s Secret with binary key {@code keytab} and string key {@code principal}, then
     * references its name in the wizard; KDPS reads the principal and overrides the helm
     * values so OM mounts the operator's keytab instead of Ambari's generated one.
     *
     * <p>The Secret MUST exist in the release namespace before submitDeploy; KDPS does not
     * create or mirror it. Missing-Secret raises a clear IllegalStateException so the
     * operator sees the error in the wizard's deploy log instead of a confusing pod-start
     * failure later.
     *
     * <p>No-op when the form field is absent or blank — internal-Kerberos deploys retain
     * the existing Ambari-driven keytab pipeline byte-for-byte (regression-safe).
     *
     * <p>Field convention: {@code hive.externalKerberosCredsSecret}. Hardcoded here because
     * Hive is the only consumer today; future external-Kerberos services can opt in by
     * adding the same form-field name to their service.json or by generalizing this lookup
     * to walk all {@code credentialKind: "kerberos"} fields.
     */
    /**
     * For each entry in the service definition's {@code externalServiceTargets}
     * map, decides whether the entry is in <em>external</em> mode (operator
     * supplied a URL override) and, if so, reads the chosen auth mode + Secret
     * and writes the helm overrides from the mode's {@code applyTo} block.
     *
     * <p>Per-entry flow:
     * <ol>
     *   <li>Read {@code urlOverrideField} from {@code request.formValues}. If
     *       blank → skip (Ambari-managed, internal pipeline handles it).</li>
     *   <li>Read {@code modeField} for the chosen mode. Default to the single
     *       declared mode when only one exists.</li>
     *   <li>Look up the mode in {@code authModes}. Unknown mode → IAE.</li>
     *   <li>Read the mode's {@code secretField} from form values to get the
     *       K8s Secret name. {@code none} mode skips this.</li>
     *   <li>Load the Secret from the release namespace. Missing Secret → IAE
     *       with a "create it ahead of deploy" message.</li>
     *   <li>Validate every key in {@code secretKeys} is present.</li>
     *   <li>For each {@code applyTo} entry, interpolate the template via
     *       {@link ExternalCredentialResolver} and write as a helm override.</li>
     * </ol>
     *
     * <p>No-op when {@code externalServiceTargets} is null/empty (services that
     * don't declare external integrations). No-op per-entry when its URL
     * override is blank — internal-cluster paths are byte-identical to before
     * this method existed.
     */
    @SuppressWarnings("unchecked")
    void applyExternalServiceCredentials(HelmDeployRequest request, Map<String, Object> params) {
        String serviceKey = request.getServiceKey();
        if (serviceKey == null || serviceKey.isBlank()) return;

        org.apache.ambari.view.k8s.model.stack.StackServiceDef def;
        try {
            def = new StackDefinitionService(this.ctx).getServiceDefinition(serviceKey);
        } catch (Exception ex) {
            LOG.warn("applyExternalServiceCredentials: could not load service def {}: {}", serviceKey, ex.toString());
            return;
        }
        if (def == null || def.externalServiceTargets == null || def.externalServiceTargets.isEmpty()) return;

        Map<String, Object> formValues = request.getFormValues();
        if (formValues == null) formValues = java.util.Collections.emptyMap();

        for (Map.Entry<String, org.apache.ambari.view.k8s.model.stack.ExternalServiceTarget> entry
                : def.externalServiceTargets.entrySet()) {
            String targetKey = entry.getKey();
            org.apache.ambari.view.k8s.model.stack.ExternalServiceTarget target = entry.getValue();
            if (target == null || target.urlOverrideField == null || target.urlOverrideField.isBlank()) continue;
            if (target.authModes == null || target.authModes.isEmpty()) continue;

            // (1) URL override gate — blank ⇒ Ambari-managed, skip entirely
            Object urlRaw = ConfigResolutionService.getByDottedPath(formValues, target.urlOverrideField);
            String url = urlRaw == null ? "" : String.valueOf(urlRaw).trim();
            if (url.isEmpty()) {
                LOG.debug("applyExternalServiceCredentials: target '{}' has empty URL override — internal/Ambari-managed", targetKey);
                continue;
            }

            // (2) Mode resolution
            String modeName = null;
            if (target.modeField != null && !target.modeField.isBlank()) {
                Object modeRaw = ConfigResolutionService.getByDottedPath(formValues, target.modeField);
                modeName = modeRaw == null ? null : String.valueOf(modeRaw).trim();
            }
            if (modeName == null || modeName.isEmpty()) {
                if (target.authModes.size() == 1) {
                    modeName = target.authModes.keySet().iterator().next();
                } else {
                    throw new IllegalStateException(
                            "External target '" + targetKey + "' has multiple auth modes but no chosen mode at '"
                                    + target.modeField + "'. The wizard's auth-method dropdown was not filled in.");
                }
            }
            var mode = target.authModes.get(modeName);
            if (mode == null) {
                throw new IllegalStateException(
                        "External target '" + targetKey + "': mode '" + modeName
                                + "' is not declared in service.json#externalServiceTargets." + targetKey
                                + ".authModes (available: " + target.authModes.keySet() + ")");
            }

            // (3) Secret lookup (skip for modes with no secretField, e.g. "none")
            String secretName = null;
            Map<String, String> secretData = null;
            if (mode.secretField != null && !mode.secretField.isBlank()) {
                Object secretRaw = ConfigResolutionService.getByDottedPath(formValues, mode.secretField);
                secretName = secretRaw == null ? "" : String.valueOf(secretRaw).trim();
                if (secretName.isEmpty()) {
                    throw new IllegalStateException(
                            "External target '" + targetKey + "' mode '" + modeName + "' requires a K8s Secret name "
                                    + "in form field '" + mode.secretField + "' — operator left it blank.");
                }
                String namespace = request.getNamespace();
                io.fabric8.kubernetes.api.model.Secret sec;
                try {
                    sec = this.kubernetesService.getSecret(namespace, secretName);
                } catch (Exception ex) {
                    throw new IllegalStateException("Failed to read Secret '" + namespace + "/" + secretName
                            + "' for external target '" + targetKey + "': " + ex.getMessage(), ex);
                }
                if (sec == null) {
                    throw new IllegalStateException(
                            "External target '" + targetKey + "' references K8s Secret '" + namespace + "/"
                                    + secretName + "' which does not exist. Create it ahead of deploy with these keys: "
                                    + (mode.secretKeys == null ? "(none)" : mode.secretKeys));
                }
                secretData = sec.getData() == null ? java.util.Collections.emptyMap() : sec.getData();
                if (mode.secretKeys != null) {
                    for (String k : mode.secretKeys) {
                        if (!secretData.containsKey(k)) {
                            throw new IllegalStateException(
                                    "Secret '" + namespace + "/" + secretName + "' is missing required key '" + k
                                            + "' (auth mode '" + modeName + "' for target '" + targetKey + "' requires "
                                            + mode.secretKeys + ").");
                        }
                    }
                }
            }

            // (4) Resolve applyTo via the template engine + write helm overrides
            var resolverCtx = new ExternalCredentialResolver.ResolverContext(secretName, secretData, formValues);
            Map<String, String> overrides = ExternalCredentialResolver.resolveApplyTo(mode, resolverCtx);
            for (Map.Entry<String, String> ov : overrides.entrySet()) {
                this.commandUtils.addOverride(params, ov.getKey(), ov.getValue());
            }
            LOG.info("applyExternalServiceCredentials: target='{}' mode='{}' url='{}' wrote {} helm overrides",
                    targetKey, modeName, url, overrides.size());
        }
    }

    /**
     * Inject the image-pull secret name into the extra helm value paths declared by
     * the service definition's {@code imagePullSecretTargets} list. KDPS always
     * writes two universal paths inline at the call sites
     * ({@code imagePullSecrets[0].name} and {@code global.imagePullSecrets[0]});
     * this method handles the chart-specific carve-outs (e.g. the airflow sub-chart
     * inside OpenMetadata which reads {@code dependencies.airflow.registry.secretName}
     * rather than honoring {@code global.imagePullSecrets}).
     *
     * <p>No-op when secretName, serviceKey, or the service.json's
     * {@code imagePullSecretTargets} is absent — i.e. existing services unaffected.
     */
    private void applyImagePullSecretTargets(Object secretName, Object serviceKey,
                                             Map<String, String> overrideProperties) {
        if (secretName == null) return;
        String svcKey = serviceKey == null ? null : String.valueOf(serviceKey);
        if (svcKey == null || svcKey.isBlank()) return;
        try {
            StackServiceDef def = new StackDefinitionService(this.ctx).getServiceDefinition(svcKey);
            if (def == null || def.imagePullSecretTargets == null) return;
            for (String path : def.imagePullSecretTargets) {
                if (path == null || path.isBlank()) continue;
                overrideProperties.put(path, String.valueOf(secretName));
                LOG.info("Image-pull-secret cascade: set {}='{}' from service.json imagePullSecretTargets ({})",
                        path, secretName, svcKey);
            }
        } catch (Exception ex) {
            LOG.warn("Could not load service def {} for pull-secret target injection: {}",
                    svcKey, ex.toString());
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

        // Apply auth-mode specific mappings. The auth-mode override always wins
        // against the Step-3 form value for the same helm path (helm --set beats
        // values.yaml), so the Step-1 → Step-3 cascade is enforced here regardless
        // of what the wizard form rendered. Logged at INFO so operators can see
        // the cascade in deploy logs when they think they set the field manually.
        for (Map.Entry<String, String> entry : modeMapping.entrySet()) {
            String fromPath = entry.getKey();
            String helmPath = entry.getValue();
            Object val = getByPath(asMap, fromPath);
            if (val == null) continue;
            String strVal = String.valueOf(val);
            if (strVal.isBlank()) continue;
            CommandUtils.addOverride(params, helmPath, strVal);
            if ("global.security.auth.mode".equals(helmPath)) {
                LOG.info("Security profile cascade: set {}='{}' from selected profile (mode='{}')",
                        helmPath, strVal, cfg.mode);
            }
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
     * Resolve a non-managed platform context's {@code <capability>.<field>} values for a deploy, so
     * dynamic-value tokens (Kerberos realm, OIDC issuer/realm) can be sourced from the selected
     * context rather than this cluster's Ambari config. Returns an empty map for the managed/default
     * context (or when none is selected), keeping the managed deploy path unchanged.
     */
    private Map<String, String> contextResolvedFieldsForDeploy(Object formValuesObj,
                                                               AmbariActionClient ambari, String cluster) {
        try {
            if (!(formValuesObj instanceof Map)) {
                return Collections.emptyMap();
            }
            Object pc = ((Map<?, ?>) formValuesObj).get("platformContextId");
            String pcId = pc == null ? null : String.valueOf(pc);
            if (pcId == null || pcId.isBlank() || ContextService.DEFAULT_CONTEXT_ID.equals(pcId)) {
                return Collections.emptyMap();
            }
            org.apache.ambari.view.k8s.model.ResolvedContext rc =
                    new ContextService(ctx).resolve(pcId, ambari, cluster);
            return (rc != null && rc.getResolvedFields() != null) ? rc.getResolvedFields() : Collections.emptyMap();
        } catch (Exception e) {
            LOG.warn("Context-aware dynamicValues: could not resolve platform context: {}", e.toString());
            return Collections.emptyMap();
        }
    }

    /**
     * True when the deploy form supplied an external Kerberos keytab secret via a service's
     * {@code externalServiceTargets} kerberos mode (e.g. {@code hive.externalKeytabSecret} for
     * Trino or {@code hiveDb.externalKeytabSecret} for Superset). When set, the deployed pods use
     * that operator-provided keytab, so the view must NOT issue an Ambari keytab.
     */
    private boolean deployUsesExternalKeytab(Map<String, Object> formValues) {
        if (formValues == null) {
            return false;
        }
        for (String path : new String[]{"hive.externalKeytabSecret", "hiveDb.externalKeytabSecret"}) {
            Object v = ConfigResolutionService.getByDottedPath(formValues, path);
            if (v != null && !String.valueOf(v).isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve dynamic override tokens (e.g., AMBARI_KERBEROS_REALM:path) into Helm override map.
     *
     * @param dynamicValuesObj raw dynamicValues object from params (usually a List<String>)
     * @param formValues       wizard form state (carries platformContextId for context-aware tokens)
     * @param baseUri          Ambari base URI for property lookup
     * @param callerHeaders    headers to propagate to Ambari
     * @return map of helmPath -> value or null when nothing resolved
     */
    private Map<String, String> resolveDynamicOverrides(Object dynamicValuesObj, Object formValues, URI baseUri, Map<String, String> callerHeaders) {
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
            // When a non-managed platform context is selected, source tokens from it.
            Map<String, String> ctxResolved = contextResolvedFieldsForDeploy(formValues, ambariActionClient, cluster);
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
                // Optional path-suffix syntax: "TOKEN[/sub/path]:helm.path" — strip the
                // bracketed suffix off the source key so DYNAMIC_SOURCE_MAP lookup hits,
                // and remember it to append to the resolved value below. Mirrors the
                // sister parser in CommandPlanFactory; keeping both in sync.
                String suffix = "";
                int lb = sourceKey.indexOf('[');
                if (lb > 0 && sourceKey.endsWith("]")) {
                    suffix = sourceKey.substring(lb + 1, sourceKey.length() - 1);
                    sourceKey = sourceKey.substring(0, lb);
                }
                AmbariConfigRef ref = CommandUtils.DYNAMIC_SOURCE_MAP.get(sourceKey);
                if (ref == null) {
                    LOG.warn("No mapping for dynamic token '{}', skipping property fetch", token);
                    continue;
                }
                try {
                    // Non-managed platform context takes precedence over local Ambari config.
                    String value = CommandUtils.contextValueForToken(sourceKey, ctxResolved);
                    if (value == null || value.isBlank()) {
                        value = ambariActionClient.getDesiredConfigProperty(cluster, ref.type, ref.key);
                    }
                    // AMBARI_OIDC_ISSUER_URL fallback: in many clusters oidc-env.oidc_issuer_url
                    // is left blank because operators only set oidc_admin_url + oidc_realm.
                    // resolveOidcIssuerUrl() composes admin_url + "/realms/" + realm; reuse it
                    // so services that map this token (Jupyter, etc.) don't render an empty value.
                    if ((value == null || value.isBlank()) && "AMBARI_OIDC_ISSUER_URL".equals(sourceKey)) {
                        value = resolveOidcIssuerUrl(ambariActionClient, cluster);
                    }
                    if (value == null || value.isBlank()) {
                        LOG.warn("Resolved empty value for token '{}' (type={}, key={})", token, ref.type, ref.key);
                        continue;
                    }
                    String resolvedValue = suffix.isEmpty() ? value
                            : (value.endsWith("/") ? value.substring(0, value.length() - 1) : value) + suffix;
                    resolved.put(helmPath, resolvedValue);
                    LOG.info("Resolved dynamic '{}' -> {} = '{}'", token, helmPath, resolvedValue);
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
    /** Path segment regex matching {@code key} or {@code key[idx]}.
     *  See HelmService.LIST_SEGMENT — same shape, kept here to avoid a cross-file
     *  dependency just for one constant. */
    private static final java.util.regex.Pattern PATH_LIST_SEG =
            java.util.regex.Pattern.compile("^(?<key>[^\\[\\]]+)(\\[(?<idx>\\d+)])?$");

    /**
     * Navigate a nested {@code Map<String,Object>} tree by a dotted path,
     * honoring Helm-style list indices in each segment (e.g. {@code ingress.hosts[0]}).
     *
     * <p>This used to do a naive {@code split(".")} which treated
     * {@code ingress.hosts[0]} as a literal map key {@code hosts[0]} and returned
     * null — same family of bug as HelmService.insertNested and bindings.ts'
     * pathToParts before they were fixed. Kept aligned with those.
     */
    private Object getByPath(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) return null;
        String[] parts = path.split("\\.");
        Object current = root;
        for (String p : parts) {
            if (current == null) return null;
            java.util.regex.Matcher m = PATH_LIST_SEG.matcher(p);
            if (!m.matches()) return null;
            String key = m.group("key");
            String idxStr = m.group("idx");
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(key);
            if (idxStr != null) {
                int idx = Integer.parseInt(idxStr);
                if (!(current instanceof List)) return null;
                List<Object> list = (List<Object>) current;
                if (idx < 0 || idx >= list.size()) return null;
                current = list.get(idx);
            }
        }
        return current;
    }

    /**
     * Shutdown all executor services gracefully.
     * Should be called when the view instance is being destroyed.
     */
    public void shutdown() {
        LOG.info("Shutting down CommandService for instance: {}", ctx.getInstanceName());
        
        // Shutdown heartbeat scheduler
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
            try {
                if (!heartbeatScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    heartbeatScheduler.shutdownNow();
                    LOG.warn("Heartbeat scheduler did not terminate gracefully");
                }
            } catch (InterruptedException e) {
                heartbeatScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // Shutdown helm executor
        if (helmExecutor != null) {
            helmExecutor.shutdown();
            try {
                if (!helmExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    helmExecutor.shutdownNow();
                    LOG.warn("Helm executor did not terminate gracefully");
                }
            } catch (InterruptedException e) {
                helmExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // Shutdown timer
        if (timer != null) {
            timer.shutdown();
            try {
                if (!timer.awaitTermination(5, TimeUnit.SECONDS)) {
                    timer.shutdownNow();
                    LOG.warn("Timer executor did not terminate gracefully");
                }
            } catch (InterruptedException e) {
                timer.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // Shutdown workers
        if (workers != null) {
            workers.shutdown();
            try {
                if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                    workers.shutdownNow();
                    LOG.warn("Worker executor did not terminate gracefully");
                }
            } catch (InterruptedException e) {
                workers.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // Remove from instances map
        INSTANCES.remove(ctx.getInstanceName());
        LOG.info("CommandService shutdown complete for instance: {}", ctx.getInstanceName());
    }

    /**
     * Shutdown all CommandService instances (for application shutdown).
     */
    public static void shutdownAll() {
        LOG.info("Shutting down all CommandService instances");
        for (CommandService service : INSTANCES.values()) {
            try {
                service.shutdown();
            } catch (Exception e) {
                LOG.error("Error shutting down CommandService instance", e);
            }
        }
        INSTANCES.clear();
    }

    /**
     * Write OIDC client credentials to HashiCorp Vault KV v2 at the given path.
     * Uses the Vault HTTP API directly (no SDK dependency).
     * This is a best-effort complement to the K8s Secret — failures are non-fatal.
     *
     * @param vaultAddr    Vault address (e.g. https://vault.example.com)
     * @param vaultToken   Vault token with write permission on vaultPath
     * @param vaultPath    KV v2 path, e.g. "secret/data/oidc/superset-prod"
     * @param clientId     OIDC client_id
     * @param clientSecret OIDC client_secret
     * @param issuerUrl    OIDC issuer URL
     */
    private void writeOidcToVault(String vaultAddr, String vaultToken, String vaultPath,
                                   String clientId, String clientSecret, String issuerUrl) throws Exception {
        // KV v2 write: POST {vaultAddr}/v1/{vaultPath}
        // Payload: {"data": {"client_id": "...", "client_secret": "...", "issuer_url": "..."}}
        String url = vaultAddr.replaceAll("/$", "") + "/v1/" + vaultPath;
        String body = "{\"data\":{\"client_id\":" + jsonStr(clientId)
                + ",\"client_secret\":" + jsonStr(clientSecret)
                + ",\"issuer_url\":" + jsonStr(issuerUrl) + "}}";

        var req = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("X-Vault-Token", vaultToken)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();

        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();
        var resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("Vault write to '" + vaultPath
                    + "' returned HTTP " + resp.statusCode());
        }
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
