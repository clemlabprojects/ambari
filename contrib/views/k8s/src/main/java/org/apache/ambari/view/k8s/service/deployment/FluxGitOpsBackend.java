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
package org.apache.ambari.view.k8s.service.deployment;

import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.service.HelmRepositoryService;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.HelmReleaseDTO;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.GitCredentialService;
import org.apache.ambari.view.k8s.service.HelmService;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.service.TrustBundleService;
import org.apache.ambari.view.k8s.service.SecurityProfileService;
import org.apache.ambari.view.k8s.service.SecurityMappingService;
import org.apache.ambari.view.k8s.service.CommandService;
import org.apache.ambari.view.k8s.service.DependencyRules;
import org.apache.ambari.view.k8s.service.ConfigResolutionService;
import org.apache.ambari.view.k8s.service.ContextService;
import org.apache.ambari.view.k8s.service.GlobalConfigService;
import org.apache.ambari.view.k8s.service.StackDefinitionService;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.apache.ambari.view.k8s.utils.CommandUtils.AmbariConfigRef;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest.ConfigInstantiation;
import org.apache.ambari.view.k8s.model.stack.StackConfig;
import org.apache.ambari.view.k8s.model.stack.StackProperty;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flux GitOps backend: renders HelmRelease/HelmRepository YAML, commits to Git and returns the commit SHA.
 * The manifests are written by {@link FluxManifests}, from names {@link DeployRequestValidator} checked, into the
 * release's own directory of the repository. Passwords and secrets never reach Git: each HelmRelease reads them from
 * its own Secret through {@code spec.valuesFrom} (see {@link GitOpsSecretValues}). Deploys submitted to the view go
 * through {@link GitOpsPlanBackend}: the command plan of a direct deploy, whose GITOPS_COMMIT step calls
 * {@link #commit}.
 * Status/destroy are still minimal and should be enhanced to read HelmRelease CRs and delete manifests.
 */
public class FluxGitOpsBackend implements DeploymentBackend {
    private static final Logger LOG = LoggerFactory.getLogger(FluxGitOpsBackend.class);

    // Retry configuration constants
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MS = 500L;
    private static final long HTTP_RETRY_BASE_DELAY_MS = 250L;

    /**
     * How many times a change is pushed when the branch keeps moving on the server meanwhile (other deploys pushing
     * to it), each time made again on top of the server's branch.
     */
    static final int MAX_PUSH_ROUNDS = 5;

    /**
     * How long an operation on a release waits for another one on the same release to finish with its Git workspace
     * before it gives up: well under the minute after which the browser stops waiting for an uninstall.
     */
    static final Duration WORKSPACE_LOCK_WAIT = Duration.ofSeconds(30);

    /**
     * View property: how many seconds a Git clone, fetch or push may go without any data from the server before it
     * fails ({@link GitClient#DEFAULT_TIMEOUT_SECONDS} when not set).
     */
    public static final String GIT_TIMEOUT_PROPERTY = "k8s.view.git.timeout.seconds";

    // Flux API versions — promoted from v2beta2/v1beta2 (deprecated in Flux 2.3, removed in Flux 3.x)
    static final String FLUX_HR_API_VERSION  = "helm.toolkit.fluxcd.io/v2";
    static final String FLUX_SRC_API_VERSION = "source.toolkit.fluxcd.io/v1";

    /**
     * Annotation of the committed HelmRelease holding a digest of its spec. Flux copies it to the cluster when it
     * applies the manifest, which tells a deploy waiting for its release that Flux applied this version and not the
     * previous one. Derived from the content only, so committing the same release again changes nothing.
     */
    public static final String SPEC_DIGEST_ANNOTATION = "ambari.clemlab.com/spec-digest";

    /**
     * How long Flux lets Helm install or upgrade a release and wait for it to be ready: the 900 seconds a direct
     * deploy gives Helm. Flux's own default, 5 minutes, would fail a release a direct deploy installs, such as a first
     * start that pulls large images.
     */
    static final String HELM_TIMEOUT = "15m";

    /**
     * The files a deploy writes into its release's directory: its HelmRelease and HelmRepository, and in
     * {@link #DEPENDENCIES_DIR} a {@code <dependency>-helmrelease.yaml} per dependency and a
     * {@code <repository>-helmrepository.yaml} per other chart repository. An uninstall removes these files and
     * nothing else; a dependency file is never named {@code helmrelease.yaml}, which marks a release's directory.
     */
    static final String HELM_RELEASE_FILE = "helmrelease.yaml";
    static final String HELM_REPOSITORY_FILE = "helmrepository.yaml";
    static final String DEPENDENCIES_DIR = "dependencies";

    /**
     * What the command plan of a deploy prepared for its release before the commit (keytab, SSO client, Ranger,
     * Hadoop configuration, certificates, image pull secret, Kerberos and truststore wiring).
     *
     * @param overrides       every value the plan would have passed to Helm, by Helm path; values from password fields
     *                        keep their mark ({@link org.apache.ambari.view.k8s.service.HelmSetValues#textIfSecret}),
     *                        so they go to the values Secret
     * @param kerberosEnabled whether the plan found Kerberos on for this deploy, {@code null} when it could not tell
     * @param dependencyOverrides what the Helm install of each dependency the commit writes would receive in a direct
     *                            deploy; {@code null} when the plan prepared none
     */
    public record PlannedRelease(Map<String, String> overrides, Boolean kerberosEnabled,
                                 DependencyOverrides dependencyOverrides) {
        public PlannedRelease(Map<String, String> overrides, Boolean kerberosEnabled) {
            this(overrides, kerberosEnabled, null);
        }
    }

    /** The values a dependency's Helm install receives on top of its chart's, as the deploy's plan prepares them. */
    @FunctionalInterface
    public interface DependencyOverrides {
        /**
         * Prepares the dependency as a direct deploy prepares its Helm install (its image pull Secret, for one) and
         * returns what that install would receive.
         *
         * @param name      the dependency, as the service definition names it
         * @param namespace the namespace it is installed in
         * @return its values by Helm path
         * @throws Exception when it cannot be prepared
         */
        Map<String, String> of(String name, String namespace) throws Exception;
    }

    /**
     * What a GitOps commit did.
     *
     * @param id                what {@link #apply} returns: the commit, {@code NO_CHANGES}, or {@code PR-STUB:<sha>}
     *                          for a pull request commit that could not be pushed
     * @param commitSha         the commit Git holds for the release, when known
     * @param committed         whether this deploy created a commit; false when Git already held the release
     * @param repoUrl           the Git repository
     * @param branch            the branch the change was written to
     * @param baseBranch        the branch Flux reconciles, when known
     * @param appliedOnCommit   whether Flux applies the change as it is: a direct commit to the branch Flux reconciles,
     *                          or a change already merged there. False while it waits for a merge.
     * @param pullRequestUrl    the pull request of the change, when one was opened
     * @param pullRequestState  its state, when known
     * @param specDigest        the {@link #SPEC_DIGEST_ANNOTATION} of the committed HelmRelease
     */
    public record CommitResult(String id, String commitSha, boolean committed, String repoUrl, String branch,
                               String baseBranch, boolean appliedOnCommit, String pullRequestUrl,
                               String pullRequestState, String specDigest) {}

    /**
     * Where a HelmRelease on the cluster stands for the version a deploy committed.
     *
     * @param ready   Flux applied that version and reports the release Ready
     * @param failed  Flux gave up on that version (Stalled); waiting longer will not help
     * @param message what Flux reports, for the operator
     * @param applied the HelmRelease on the cluster is that version: what is left is up to Flux and Helm
     */
    public record Readiness(boolean ready, boolean failed, String message, boolean applied) {}

    /**
     * One lock per release key (namespace:releaseName).
     * Ensures concurrent apply/destroy operations on the SAME release are serialised
     * while operations on DIFFERENT releases still run in parallel.
     */
    private static final ConcurrentHashMap<String, ReentrantLock> WORKSPACE_LOCKS = new ConcurrentHashMap<>();

    private final Path fluxRoot;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final KubernetesService kubernetesService;
    private final ViewContext viewContext;
    private final GitCredentialService gitCredentialService;
    private final ReleaseMetadataService releaseMetadataService;
    private final HttpClient httpClient;
    private final Duration httpTimeout = Duration.ofSeconds(10);
    private final CommandUtils commandUtils;
    private final HelmService helmService;
    private final ConfigResolutionService configResolutionService = new ConfigResolutionService();
    private final SecurityProfileService securityProfileService;
    private final GlobalConfigService globalConfigService = new GlobalConfigService();
    private final StackDefinitionService stackDefinitionService = new StackDefinitionService();
    private Duration workspaceLockWait = WORKSPACE_LOCK_WAIT;

    /**
     * @param fluxRoot root directory where manifests will be written (simulates Git workspace).
     */
    public FluxGitOpsBackend(Path fluxRoot, KubernetesService kubernetesService, ViewContext viewContext) {
        this(fluxRoot, kubernetesService, viewContext, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    FluxGitOpsBackend(Path fluxRoot, KubernetesService kubernetesService, ViewContext viewContext, HttpClient httpClient) {
        this.fluxRoot = fluxRoot;
        this.kubernetesService = kubernetesService;
        this.viewContext = viewContext;
        this.gitCredentialService = new GitCredentialService(viewContext);
        this.releaseMetadataService = new ReleaseMetadataService(viewContext);
        this.httpClient = httpClient == null
                ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                : httpClient;
        this.commandUtils = new CommandUtils(viewContext, kubernetesService);
        this.helmService = new HelmService(viewContext);
        this.securityProfileService = new SecurityProfileService(viewContext);
    }

    /** How long an operation waits for another one on the same release ({@link #WORKSPACE_LOCK_WAIT}); for tests. */
    void setWorkspaceLockWait(Duration wait) {
        this.workspaceLockWait = wait;
    }

    /**
     * The timeout of Git clones, fetches and pushes: the {@link #GIT_TIMEOUT_PROPERTY} view property, else
     * {@link GitClient#DEFAULT_TIMEOUT_SECONDS}.
     */
    public static int gitTimeoutSeconds(ViewContext viewContext) {
        String configured = viewContext == null || viewContext.getProperties() == null ? null
                : viewContext.getProperties().get(GIT_TIMEOUT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return GitClient.DEFAULT_TIMEOUT_SECONDS;
        }
        try {
            int seconds = Integer.parseInt(configured.trim());
            if (seconds > 0) {
                return seconds;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        LOG.warn("Ignoring the view property {}='{}': not a positive number of seconds; Git operations time out "
                + "after {} seconds", GIT_TIMEOUT_PROPERTY, configured, GitClient.DEFAULT_TIMEOUT_SECONDS);
        return GitClient.DEFAULT_TIMEOUT_SECONDS;
    }

    /**
     * The {@code global.security.kerberos.enabled} value for a GitOps deploy.
     *
     * @param profileKerberos       the selected security profile asks for Kerberos
     * @param ambariClusterKerberos Kerberos state of the Ambari cluster, {@code null} when unknown
     * @return "true", "false", or {@code null} to leave the chart default
     */
    static String kerberosEnabledValue(boolean profileKerberos, Boolean ambariClusterKerberos) {
        if (profileKerberos || Boolean.TRUE.equals(ambariClusterKerberos)) {
            return "true";
        }
        return ambariClusterKerberos == null ? null : "false";
    }

    /**
     * Whether the Ambari cluster's Kerberos state applies to this deploy: only on the managed (default) context and
     * without an operator-supplied keytab. Direct deploys apply the same rule; an external context must not inherit
     * the local realm.
     */
    static boolean followsAmbariKerberos(HelmDeployRequest request) {
        Map<String, Object> form = request.getFormValues() == null ? Map.of() : request.getFormValues();
        Object fromForm = ConfigResolutionService.getByDottedPath(form, "platformContextId");
        String contextId = request.getPlatformContextId();
        if (contextId == null || contextId.isBlank()) {
            contextId = fromForm == null || String.valueOf(fromForm).isBlank() ? ContextService.DEFAULT_CONTEXT_ID
                    : String.valueOf(fromForm);
        }
        return ContextService.DEFAULT_CONTEXT_ID.equals(contextId) && !CommandService.deployUsesExternalKeytab(form);
    }

    /**
     * Asks Ambari whether its cluster is Kerberized.
     *
     * @return TRUE or FALSE; FALSE when Ambari manages no cluster; {@code null} when Ambari cannot be asked
     */
    private Boolean ambariClusterKerberos(DeploymentContext context) {
        if (context == null || context.getBaseUri() == null) {
            return null;
        }
        String base = context.getBaseUri().toString();
        Map<String, String> headers = AmbariActionClient.toAuthHeaders(context.getCallerHeaders());
        try {
            String cluster = commandUtils.resolveClusterName(base, headers);
            String securityEnabled = new AmbariActionClient(viewContext, base, cluster, headers)
                    .getDesiredConfigProperty(cluster, "cluster-env", "security_enabled");
            return "true".equalsIgnoreCase(securityEnabled);
        } catch (CommandUtils.NoAmbariClusterException noCluster) {
            LOG.info("Ambari manages no cluster: no Kerberos to inherit");
            return Boolean.FALSE;
        } catch (Exception ex) {
            LOG.warn("Could not determine the Kerberos state from Ambari, leaving the chart default: {}", ex.toString());
            return null;
        }
    }

    /**
     * Why a dependency is not written to Git for this deploy, or {@code null}: the keytab webhook outside the WEBHOOK
     * Kerberos injection mode, then the rules direct deploys apply ({@link DependencyRules}).
     *
     * @param planned what the deploy's command plan prepared, whose Kerberos state then decides; {@code null} to ask
     *                Ambari
     */
    private String gitOpsSkipReason(String name, Map<String, Object> spec, HelmDeployRequest request,
                                    boolean managedHere, DeploymentContext context, PlannedRelease planned) {
        if ("kerberos-keytab-mutating-webhook".equals(name)) {
            if (!"WEBHOOK".equals(CommandService.resolveKerberosInjectionMode(viewContext))) {
                return "Skipped — the keytab webhook is only used in the WEBHOOK Kerberos injection mode.";
            }
            boolean kerberosOff = planned != null
                    ? Boolean.FALSE.equals(planned.kerberosEnabled())
                    : followsAmbariKerberos(request) && Boolean.FALSE.equals(ambariClusterKerberos(context));
            if (kerberosOff) {
                return "Skipped — Kerberos is disabled on the cluster.";
            }
        }
        DependencyRules.applyNamespaceFromForm(spec, request.getFormValues());
        String reason = DependencyRules.settingsSkipReason(name, spec, request, kubernetesService);
        if (reason != null || managedHere) {
            return reason;
        }
        return DependencyRules.reuseSkipReason(name, spec, request, kubernetesService, this::helmReleaseExists);
    }

    /** Whether a Helm release of that name is installed in the namespace. */
    private boolean helmReleaseExists(String namespace, String name) {
        try {
            String kubeconfig = kubernetesService.getConfigurationService().getKubeconfigContents();
            return kubeconfig != null && !kubeconfig.isBlank()
                    && helmService.list(namespace, kubeconfig).stream().anyMatch(r -> name.equals(r.getName()));
        } catch (Exception e) {
            LOG.warn("Could not check for Helm release {}/{}: {}", namespace, name, e.toString());
            return false;
        }
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * The Flux HelmRepository of the release's chart repository and of each dependency's, by repository id.
     *
     * @throws IllegalStateException when one of them is not configured in KDPS
     */
    private Map<String, String> chartRepositoryYamls(String repoName, Map<String, Object> dependencies) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(repoName, chartRepositoryYaml(repoName));
        if (dependencies != null) {
            for (Object spec : dependencies.values()) {
                Object depRepo = spec instanceof Map<?, ?> m ? m.get("repoId") : null;
                String id = depRepo == null || String.valueOf(depRepo).isBlank() ? repoName : String.valueOf(depRepo);
                sources.computeIfAbsent(id, this::chartRepositoryYaml);
            }
        }
        return sources;
    }

    /**
     * The Flux HelmRepository for a chart repository configured in KDPS: its URL, and {@code type: oci} for OCI
     * registries (Flux needs the {@code oci://} scheme and the type to pull charts from a registry).
     *
     * @param repoId KDPS chart repository id
     * @return the HelmRepository manifest
     * @throws IllegalStateException when the repository is not configured in KDPS
     */
    String chartRepositoryYaml(String repoId) {
        HelmRepoEntity repo = new HelmRepositoryService(viewContext).get(repoId);
        if (repo == null || repo.getUrl() == null || repo.getUrl().isBlank()) {
            throw new IllegalStateException("Chart repository '" + repoId + "' is not configured in KDPS: add it under "
                    + "Configuration > Helm repositories before deploying with GitOps.");
        }
        return renderHelmRepository(repoId, repo.getType(), repo.getUrl(), repo.getAuthMode());
    }

    /** Whether a KDPS chart repository is an OCI registry. */
    private boolean isOciRepository(String repoId) {
        HelmRepoEntity repo = new HelmRepositoryService(viewContext).get(repoId);
        return repo != null && "OCI".equalsIgnoreCase(repo.getType());
    }

    /**
     * The chart name as the Flux source knows it. An HTTP repository indexes its charts by bare name, so the
     * {@code <repo>/<chart>} form of a service definition (Helm's local repository alias) becomes {@code <chart>}.
     * An OCI registry keeps the path below the registry URL, which is where a direct deploy pulls the chart from,
     * without a tag: the version is pinned separately.
     *
     * @param chart     the chart reference of the deploy
     * @param ociSource whether the chart comes from an OCI registry
     * @return the {@code spec.chart.spec.chart} value
     */
    static String chartNameInSource(String chart, boolean ociSource) {
        if (chart == null) return null;
        String name = chart.trim();
        int slash = name.lastIndexOf('/');
        if (!ociSource) {
            return name.substring(slash + 1);
        }
        int colon = name.lastIndexOf(':');
        return colon > slash ? name.substring(0, colon) : name;
    }

    /**
     * The chart version written to the HelmRelease: the one this deploy names, else the release's recorded one, else
     * (first install only) the service definition's. Never "latest": Flux reads the version as a SemVer range and
     * rejects that word, and leaving it out would move the release to the newest chart.
     *
     * @throws IllegalArgumentException when no version can be found
     */
    private String chartVersion(HelmDeployRequest request, DeploymentContext context, K8sReleaseEntity existingMeta,
                                String serviceKey) {
        String version = firstNonBlank(
                CommandService.pinnedChartVersion(context != null ? context.getVersion() : null),
                CommandService.pinnedChartVersion(request.getVersion()),
                CommandService.pinnedChartVersion(existingMeta != null ? existingMeta.getVersion() : null),
                existingMeta == null ? CommandService.pinnedChartVersion(catalogVersion(serviceKey)) : null);
        if (version == null) {
            throw new IllegalArgumentException("Select a chart version for " + request.getReleaseName() + ": a GitOps"
                    + " deploy pins it in the HelmRelease, and none was given or recorded for this release.");
        }
        return version;
    }

    /** The chart version a service definition ships with, or {@code null}. */
    private String catalogVersion(String serviceKey) {
        if (serviceKey == null || serviceKey.isBlank()) return null;
        try {
            StackServiceDef def = stackDefinitionService.getServiceDefinition(serviceKey);
            return def == null ? null : def.version;
        } catch (Exception e) {
            LOG.warn("Could not read the chart version of service definition {}: {}", serviceKey, e.toString());
            return null;
        }
    }

    /**
     * Stops a deploy on OpenShift when service accounts of the release may not use the SCCs the service declares
     * ({@code openshiftScc}), with the command the platform team runs. Never grants (see
     * {@link KubernetesService#checkSccGrants}).
     *
     * @throws IllegalStateException when a grant is missing
     */
    private void requireSccGrants(HelmDeployRequest request, String serviceKey) {
        if (serviceKey == null || serviceKey.isBlank() || !kubernetesService.isOpenShiftCluster()) return;
        StackServiceDef def;
        try {
            def = stackDefinitionService.getServiceDefinition(serviceKey);
        } catch (Exception e) {
            LOG.warn("SCC preflight for {} could not run: {}", serviceKey, e.toString());
            return;
        }
        if (def == null || def.openshiftScc == null || def.openshiftScc.isEmpty()) return;
        List<String> warnings = new ArrayList<>();
        String problem = kubernetesService.checkSccGrants(request.getNamespace(), request.getReleaseName(),
                def.openshiftScc, warnings);
        if (problem != null) {
            throw new IllegalStateException("Cannot deploy " + request.getReleaseName()
                    + " on OpenShift — required Security Context Constraints are not granted.\n" + problem);
        }
        warnings.forEach(w -> logFluxWarn(request.getNamespace(), request.getReleaseName(), "automation", "%s", w));
        LOG.info("The OpenShift SCCs service {} declares are granted to release {} in namespace {}",
                serviceKey, request.getReleaseName(), request.getNamespace());
    }

    /** Renders a Flux HelmRepository; OCI URLs get the oci:// scheme and {@code type: oci}. */
    static String renderHelmRepository(String name, String type, String url, String authMode) {
        boolean oci = "OCI".equalsIgnoreCase(type);
        String effectiveUrl = url.trim();
        if (oci) {
            effectiveUrl = "oci://" + effectiveUrl.replaceFirst("^[a-zA-Z]+://", "");
        }
        if (authMode != null && !authMode.isBlank() && !"anonymous".equalsIgnoreCase(authMode)) {
            LOG.warn("Chart repository {} needs credentials: Flux pulls it only with a secretRef, which KDPS does not "
                    + "write yet; create the Secret in flux-system and reference it from HelmRepository {}", name, name);
        }
        return FluxManifests.helmRepository(name, effectiveUrl, oci);
    }

    /**
     * Refuses a GitOps deploy that could not be committed, before anything is prepared for it: no Git repository, no
     * credentials to push to it, no chart repository or chart version, a release directory inside or above that of
     * another release KDPS deployed to the repository. The checks {@link #commit} makes, without touching Git or
     * changing the request.
     *
     * @throws IllegalArgumentException naming what is missing
     * @throws IllegalStateException    when a chart repository is not configured in KDPS
     */
    public void checkCommittable(HelmDeployRequest request, DeploymentContext context) {
        K8sReleaseEntity existingMeta = releaseMetadataService.find(request.getNamespace(), request.getReleaseName());
        HelmDeployRequest.GitOptions given = request.getGit() != null ? request.getGit() : new HelmDeployRequest.GitOptions();
        String repoUrl = firstNonBlank(given.getRepoUrl(), existingMeta != null ? existingMeta.getGitRepoUrl() : null);
        if (repoUrl == null) {
            throw new IllegalArgumentException("Flux GitOps requires git.repoUrl");
        }
        GitCredentialService.checkRepoUrl(repoUrl);
        checkApartFromRecordedReleases(resolveTargetPath(given.getPathPrefix(),
                        existingMeta != null ? existingMeta.getGitPath() : null, request.getNamespace(),
                        request.getReleaseName()),
                repoUrl, request.getNamespace(), request.getReleaseName());
        String repoName = firstNonBlank(context != null ? context.getRepoId() : null, request.getRepoId(),
                existingMeta != null ? existingMeta.getRepoId() : null);
        if (repoName == null) {
            throw new IllegalArgumentException("Select a chart repository for " + request.getReleaseName() + ": a GitOps"
                    + " deploy names it in the HelmRelease, and none was given or recorded for this release.");
        }
        chartVersion(request, context, existingMeta,
                firstNonBlank(request.getServiceKey(), existingMeta != null ? existingMeta.getServiceKey() : null));
        chartRepositoryYamls(repoName, request.getDependencies());
        GitSecrets gitSecrets = gitSecrets(given, repoUrl.trim(), request.getNamespace(), request.getReleaseName(),
                existingMeta);
        checkGitAccess(repoUrl.trim(), firstNonBlank(given.getCommitMode(),
                existingMeta != null ? existingMeta.getGitCommitMode() : null), gitSecrets.token(), gitSecrets.sshKey());
    }

    /**
     * The Git credentials a deploy pushes with.
     *
     * @param alias the credential store alias they were read from, {@code null} when given with the deploy
     */
    private record GitSecrets(String token, String sshKey, String alias) {}

    /**
     * The token or SSH key given with the deploy, else the one stored for the release, and the token stored next to
     * an SSH key. A stored secret is only read from an alias of the release itself and for the Git host it was
     * entered for ({@link #storedSecret}); an alias the deploy names next to a token or key it gives is not used.
     *
     * @param repoUrl      the repository the deploy pushes to
     * @param existingMeta the release as recorded, {@code null} for a new one
     * @throws IllegalArgumentException when the deploy names an alias that is not the release's, or the stored secret
     *                                  was entered for another Git host
     */
    private GitSecrets gitSecrets(HelmDeployRequest.GitOptions git, String repoUrl, String namespace, String release,
                                  K8sReleaseEntity existingMeta) {
        String token = git.getAuthToken();
        String sshKey = git.getSshKey();
        String alias = null;
        if (!givesGitSecret(git)) {
            alias = firstNonBlank(git.getCredentialAlias(), existingMeta != null ? existingMeta.getGitCredentialAlias() : null);
            if (alias != null) {
                String resolved = storedSecret(alias, repoUrl, namespace, release, existingMeta);
                if (resolved != null && resolved.contains("BEGIN")) {
                    sshKey = resolved;
                } else {
                    token = resolved;
                }
            }
        }
        String tokenAlias = git.getAuthTokenAlias();
        if (tokenAlias != null && !tokenAlias.isBlank()) {
            checkReleaseAlias(tokenAlias, namespace, release, null);
            if (token == null || token.isBlank()) {
                token = storedSecret(tokenAlias, repoUrl, namespace, release, existingMeta);
            }
        }
        return new GitSecrets(token, sshKey, alias);
    }

    /**
     * The Git credentials stored for a release, for the repository its metadata records: what an uninstall, a pull
     * request poll or any other operation without a deploy request uses.
     */
    private GitSecrets recordedGitSecrets(K8sReleaseEntity meta) {
        String alias = meta.getGitCredentialAlias();
        // Without a repository nothing is sent anywhere: a removal from Git is then skipped.
        if (alias == null || alias.isBlank() || meta.getGitRepoUrl() == null || meta.getGitRepoUrl().isBlank()) {
            return new GitSecrets(null, null, null);
        }
        String resolved = storedSecret(alias, meta.getGitRepoUrl(), meta.getNamespace(), meta.getReleaseName(), meta);
        boolean sshKey = resolved != null && resolved.contains("BEGIN");
        return new GitSecrets(sshKey ? null : resolved, sshKey ? resolved : null, alias);
    }

    /**
     * Reads a Git secret KDPS stored for a release, for a deploy to {@code repoUrl}. Refused unless the alias is the
     * release's ({@link #checkReleaseAlias}) and the secret was entered for the host of {@code repoUrl}: Ambari's
     * credential store holds other secrets too (its database and LDAP passwords, for two), and a token sent to another
     * host is a token given away. A secret stored by an earlier version, which kept no host, is bound to the
     * repository the release was last deployed from.
     *
     * @throws IllegalArgumentException naming what was refused
     */
    private String storedSecret(String alias, String repoUrl, String namespace, String release,
                                K8sReleaseEntity existingMeta) {
        String recordedAlias = existingMeta != null ? existingMeta.getGitCredentialAlias() : null;
        checkReleaseAlias(alias, namespace, release, recordedAlias);
        String bound = gitCredentialService.boundHost(alias);
        if (bound == null && existingMeta != null && alias.equalsIgnoreCase(recordedAlias)) {
            bound = GitCredentialService.hostOf(existingMeta.getGitRepoUrl());
        }
        String host = GitCredentialService.hostOf(repoUrl);
        if (bound == null || !bound.equals(host)) {
            throw new IllegalArgumentException("The Git credential KDPS saved for " + namespace + "/" + release
                    + " was entered for " + (bound == null || bound.isEmpty() ? "another repository" : bound)
                    + ", not for " + (host == null || host.isEmpty() ? repoUrl : host) + ". Give the access token or SSH"
                    + " key with the deploy to use " + repoUrl + ".");
        }
        return gitCredentialService.resolveSecret(alias);
    }

    /**
     * Refuses a credential store alias that does not hold a Git credential KDPS saved for this release: the release's
     * own alias ({@link GitCredentialService#releaseAlias}), one its deploys keep a new credential under until it has
     * reached Git ({@link GitCredentialService#pendingAlias}, {@link GitCredentialService#pendingTokenAlias}), or the
     * alias recorded for it when KDPS created that alias. Any other alias may hold another release's credential or one
     * of Ambari's own secrets.
     *
     * @param alias         the alias to read
     * @param recordedAlias the alias recorded for the release, {@code null} for a new one
     * @throws IllegalArgumentException naming the alias
     */
    void checkReleaseAlias(String alias, String namespace, String release, String recordedAlias) {
        String instance = viewContext.getInstanceName();
        boolean own = alias.equalsIgnoreCase(GitCredentialService.releaseAlias(instance, namespace, release))
                || alias.equalsIgnoreCase(GitCredentialService.pendingAlias(instance, namespace, release))
                || alias.equalsIgnoreCase(GitCredentialService.pendingTokenAlias(instance, namespace, release));
        boolean recorded = alias.equalsIgnoreCase(recordedAlias) && GitCredentialService.isViewAlias(recordedAlias);
        if (own || recorded) {
            return;
        }
        throw new IllegalArgumentException("The credential alias '" + alias + "' does not hold a Git credential saved"
                + " by KDPS for " + namespace + "/" + release + ". A GitOps deploy only reuses the Git token or SSH key"
                + " KDPS saved for the release itself; otherwise give the token or SSH key with the deploy.");
    }

    /** Whether the deploy gives its token or SSH key itself, rather than an alias of the credential store. */
    private static boolean givesGitSecret(HelmDeployRequest.GitOptions git) {
        return (git.getAuthToken() != null && !git.getAuthToken().isBlank())
                || (git.getSshKey() != null && !git.getSshKey().isBlank());
    }

    /**
     * Once a deploy has reached Git, the Git credential the release pushes with from then on: a token or SSH key the
     * deploy gave, or kept under its pending alias when it was submitted, becomes the release's
     * ({@link GitCredentialService#releaseAlias}), replacing what the release had there; otherwise the alias the
     * credential was read from. Never done before: a mistyped token, or a failed move to another Git server, would
     * otherwise replace the credential every later operation on the release (uninstall included) relies on.
     *
     * @return the alias, or {@code null} when the new credential could not be stored, and the alias recorded for the
     *         release stays
     */
    private String keepGitCredential(HelmDeployRequest.GitOptions git, GitSecrets secrets, String repoUrl,
                                     String namespace, String release) {
        String instance = viewContext.getInstanceName();
        String releaseAlias = GitCredentialService.releaseAlias(instance, namespace, release);
        String pendingAlias = GitCredentialService.pendingAlias(instance, namespace, release);
        // The token a deploy gave next to its SSH key served its commit only.
        String pendingTokenAlias = GitCredentialService.pendingTokenAlias(instance, namespace, release);
        if (pendingTokenAlias.equalsIgnoreCase(git.getAuthTokenAlias())) {
            gitCredentialService.removeSecret(pendingTokenAlias);
        }
        String kept;
        if (givesGitSecret(git)) {
            kept = gitCredentialService.storeSecret(releaseAlias,
                    git.getSshKey() != null && !git.getSshKey().isBlank() ? git.getSshKey() : git.getAuthToken(), repoUrl);
        } else if (pendingAlias.equalsIgnoreCase(secrets.alias())) {
            kept = gitCredentialService.promote(pendingAlias, releaseAlias);
        } else {
            return secrets.alias();
        }
        if (kept == null) {
            LOG.warn("The Git credential of {}/{} could not be stored in Ambari's credential store: the next "
                    + "operation on the release needs it again", namespace, release);
        }
        return kept;
    }

    /**
     * Refuses Git options a commit could not be pushed with.
     *
     * @throws IllegalArgumentException naming what is missing
     */
    static void checkGitAccess(String repoUrl, String commitMode, String token, String sshKey) {
        boolean isHttps = repoUrl.startsWith("https://") || repoUrl.startsWith("http://");
        boolean isSsh = repoUrl.startsWith("git@") || repoUrl.startsWith("ssh://");
        boolean hasToken = token != null && !token.isBlank();
        boolean hasSshKey = sshKey != null && !sshKey.isBlank();
        if ("PR_MODE".equalsIgnoreCase(commitMode) && isHttps && !hasToken) {
            throw new IllegalArgumentException("PR mode requires an HTTPS token");
        }
        if (isHttps && !hasToken && !hasSshKey) {
            throw new IllegalArgumentException("GitOps over HTTPS requires authToken or sshKey");
        }
        if (isSsh && !hasSshKey && !hasToken) {
            throw new IllegalArgumentException("GitOps over SSH requires sshKey or HTTPS token");
        }
    }

    /**
     * Writes and commits the release's manifests at once, preparing only what this backend prepares itself (namespace,
     * krb5.conf ConfigMap, truststore, mounts, image pull secret). Deploys submitted to the view do not come here:
     * their command plan prepares the release as a direct deploy does and its GITOPS_COMMIT step calls
     * {@link #commit}.
     *
     * @return the commit, {@code NO_CHANGES} when Git already holds the release, or {@code PR-STUB:<sha>}
     */
    @Override
    public String apply(HelmDeployRequest request, DeploymentContext context) throws Exception {
        return commit(request, context, null).id();
    }

    /**
     * Writes the release's HelmRelease, HelmRepository and dependency manifests to Git and commits them (or opens a
     * pull request), the passwords and secrets going to the values Secrets the HelmReleases read. Idempotent: when Git
     * already holds the release as given, nothing is committed.
     *
     * @param planned what the deploy's command plan prepared, whose overrides are written into the values; {@code null}
     *                when nothing was planned, and this backend prepares Kerberos, the truststore, the mounts and the
     *                image pull secret itself
     */
    public CommitResult commit(HelmDeployRequest request, DeploymentContext context, PlannedRelease planned)
            throws Exception {
        // Checked again here, not only when the deploy is submitted: a planned deploy commits from the parameters it
        // saved, and every name below goes into the manifests and their paths in Git. By the GitOps rules whatever
        // mode the request names: a chart URL a direct deploy accepts cannot be written into a HelmRelease.
        DeployRequestValidator.validateForGitOps(request, context != null ? context.getRepoId() : null,
                context != null ? context.getVersion() : null);

        final String namespace = request.getNamespace();
        final String release = request.getReleaseName();
        final var existingMeta = releaseMetadataService.find(namespace, release);
        // A redeploy that names no platform context stays on the release's: an external context must not inherit the
        // local cluster's Kerberos state.
        CommandService.defaultPlatformContext(request, existingMeta);

        // The branch Flux reconciles, as configured: mergeGitOptions falls back to the working branch for a redeploy
        // that names no base branch, which says nothing about where Flux reads. Null when it was never recorded.
        String requestedBase = request.getGit() != null ? request.getGit().getBaseBranch() : null;
        String configuredBase = firstNonBlank(requestedBase,
                existingMeta != null ? existingMeta.getGitBaseBranch() : null,
                existingMeta == null ? "main" : null);
        HelmDeployRequest.GitOptions git = mergeGitOptions(request, existingMeta);
        if (git == null || git.getRepoUrl() == null || git.getRepoUrl().isBlank()) {
            throw new IllegalArgumentException("Flux GitOps requires git.repoUrl");
        }

        String repoUrl = git.getRepoUrl().trim();
        GitCredentialService.checkRepoUrl(repoUrl);

        logFluxInfo(namespace, release, "apply", "Starting Flux GitOps deploy (mode=%s, repo=%s)", git.getCommitMode(), repoUrl);

        String baseBranch = firstNonBlank(git.getBaseBranch(), "main");
        // The chart repository and version as a direct deploy resolves them: what this deploy names, else what the
        // release was deployed with. Written into the HelmRelease, so nothing is guessed.
        String repoName = firstNonBlank(context != null ? context.getRepoId() : null, request.getRepoId(),
                existingMeta != null ? existingMeta.getRepoId() : null);
        if (repoName == null) {
            throw new IllegalArgumentException("Select a chart repository for " + release + ": a GitOps deploy names it"
                    + " in the HelmRelease, and none was given or recorded for this release.");
        }
        // Also when they come from the release's record rather than from this deploy.
        DeployRequestValidator.checkRepositoryIfGiven("Chart repository", repoName);
        request.setRepoId(repoName);
        String serviceKey = firstNonBlank(request.getServiceKey(), existingMeta != null ? existingMeta.getServiceKey() : null);
        String version = chartVersion(request, context, existingMeta, serviceKey);
        DeployRequestValidator.checkChartVersionIfGiven("Chart version", version);
        request.setVersion(version);
        String chart = request.getChart();
        // Every chart source, resolved before anything is created or committed: an unknown repository stops here.
        Map<String, String> chartSources = chartRepositoryYamls(repoName, request.getDependencies());
        String chartInSource = chartNameInSource(chart, isOciRepository(repoName));

        String pathOverride = git.getPathPrefix();
        String storedPath = existingMeta != null ? existingMeta.getGitPath() : null;
        Path targetDir = resolveTargetPath(pathOverride, storedPath, namespace, release);
        checkApartFromRecordedReleases(targetDir, repoUrl, namespace, release);

        // Resolve credentials: prefer provided, else the release's stored ones. A new one, given in clear or by the
        // pending alias a planned deploy stored it under when it was submitted, becomes the release's only once the
        // deploy has reached Git with it (keepGitCredential).
        String recordedAlias = existingMeta != null ? existingMeta.getGitCredentialAlias() : null;
        GitSecrets gitSecrets = gitSecrets(git, repoUrl, namespace, release, existingMeta);
        String token = gitSecrets.token();
        String sshKey = gitSecrets.sshKey();
        checkGitAccess(repoUrl, git.getCommitMode(), token, sshKey);

        // The security profile, read before anything is created or committed: a deploy that names one that is gone
        // stops here rather than committing the release without its login settings. A planned deploy applied its
        // profile when it was planned.
        String resolvedSecurityProfile = planned == null ? resolveSecurityProfileName(request.getSecurityProfile()) : null;
        SecurityConfigDTO securityCfg = loadSecurityConfig(resolvedSecurityProfile);

        // Each release owns its own git workspace directory.
        // Operations on the same release are serialised via a per-release lock;
        // operations on different releases still run in parallel.
        Path repoDir = workspaceForRelease(namespace, release);
        ReentrantLock workspaceLock = lockWorkspace(namespace, release);
        try {

        GitClient gitClient = new GitClient(repoDir, repoUrl, baseBranch, token, sshKey)
                .withAuthor(viewContext.getUsername(), viewContext.getUsername() + "@ambari")
                .withTimeout(gitTimeoutSeconds(viewContext));
        try {
        withGitRetry(() -> { gitClient.sync(); return null; }, "sync " + repoUrl);

        String branchCandidate = firstNonBlank(git.getBranch(), existingMeta != null ? existingMeta.getGitBranch() : null, GitClient.generateBranch("flux"));
        final String branch = branchCandidate;
        git.setBranch(branch);
        withGitRetry(() -> { gitClient.checkoutBranch(branch); return null; }, "checkout " + branch);
        // A pull request or working branch merged into the branch Flux reads as new commits, and deleted on the server
        // since: started again from there, so a redeploy neither pushes its merged commits again nor proposes them
        // once more with its own change.
        gitClient.restartIfMergedInto(configuredBase);
        // As the branch holds them, which also covers releases deployed to the repository from elsewhere.
        checkApartFromOtherReleases(repoDir, targetDir, namespace, release);

        Files.createDirectories(repoDir.resolve(targetDir));

        // ============================================================
        // AUTOMATION: Pre-deployment setup (mounts, ConfigMaps, secrets)
        // ============================================================
        logFluxInfo(namespace, release, "automation", "Starting pre-deployment automation steps");

        // 1. Ensure the namespace exists; label it for the keytab webhook only in the WEBHOOK Kerberos injection mode
        //    (the default PRE_PROVISIONED mode does not use the label, and a project-limited account may not set it).
        try {
            kubernetesService.createNamespace(namespace);
            if ("WEBHOOK".equals(org.apache.ambari.view.k8s.service.CommandService.resolveKerberosInjectionMode(viewContext))) {
                kubernetesService.ensureWebhookEnabledNamespace(namespace);
            }
            LOG.info("Ensured namespace {} exists", namespace);
        } catch (Exception ex) {
            LOG.warn("Failed to ensure namespace {}: {}", namespace, ex.getMessage());
            // Continue - namespace might already exist
        }

        // 1b. OpenShift: ServiceMonitor right and the KEDA monitoring token, exactly as in direct mode. A refusal
        //     stops the deploy before anything is committed to Git. A planned deploy did it when it was planned.
        if (planned == null) {
            org.apache.ambari.view.k8s.service.OpenShiftMonitoringSetup.prepare(kubernetesService, request);
        }

        // 1c. OpenShift: the security context constraints the service's pods need, detected as in direct mode. A planned
        //     deploy has already checked them, and granted them when allowed, like a direct deploy; without a plan a
        //     missing grant stops the deploy before anything is committed to Git.
        if (planned == null) {
            requireSccGrants(request, serviceKey);
        }

        // 2. Create mounts/PVCs if specified (a planned deploy has a step of its own for them)
        if (planned == null && request.getMounts() != null) {
            try {
                Map<String, Object> normalizedMounts = CommandUtils.normalizeMountsObject(request.getMounts());
                if (!normalizedMounts.isEmpty()) {
                    kubernetesService.createMounts(namespace, release, normalizedMounts);
                    logFluxInfo(namespace, release, "automation", "Created %s mount(s)", normalizedMounts.size());
                }
            } catch (Exception ex) {
                LOG.warn("Failed to create mounts for {}/{}: {}", namespace, release, ex.getMessage());
                // Continue - mounts might be optional or already exist
            }
        }

        // 3. Ensure image pull secret exists and inject into values. A planned deploy's step ensured it, and the
        //    planned overrides reference it exactly as the Helm install would.
        if (planned == null && request.getSecretName() != null && !request.getSecretName().isBlank()) {
            try {
                String effectiveRepoId = firstNonBlank(request.getRepoId(), repoName);
                helmService.ensureImagePullSecretFromRepo(effectiveRepoId, namespace, request.getSecretName(), null);
                
                // Inject image pull secret into values
                if (request.getValues() == null) {
                    request.setValues(new LinkedHashMap<>());
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> values = (Map<String, Object>) request.getValues();
                
                // Add imagePullSecrets array if not present
                if (!values.containsKey("imagePullSecrets")) {
                    values.put("imagePullSecrets", new ArrayList<>());
                }
                @SuppressWarnings("unchecked")
                List<Object> imagePullSecrets = (List<Object>) values.get("imagePullSecrets");
                boolean hasSecret = imagePullSecrets.stream()
                        .anyMatch(s -> s instanceof Map && request.getSecretName().equals(((Map<?, ?>) s).get("name")));
                if (!hasSecret) {
                    imagePullSecrets.add(Map.of("name", request.getSecretName()));
                }
                
                // Also add to global.imagePullSecrets for compatibility
                if (!values.containsKey("global")) {
                    values.put("global", new LinkedHashMap<>());
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> global = (Map<String, Object>) values.get("global");
                if (!global.containsKey("imagePullSecrets")) {
                    global.put("imagePullSecrets", new ArrayList<>());
                }
                @SuppressWarnings("unchecked")
                List<Object> globalImagePullSecrets = (List<Object>) global.get("imagePullSecrets");
                boolean hasGlobalSecret = globalImagePullSecrets.stream()
                        .anyMatch(s -> s instanceof Map && request.getSecretName().equals(((Map<?, ?>) s).get("name")));
                if (!hasGlobalSecret) {
                    globalImagePullSecrets.add(Map.of("name", request.getSecretName()));
                }
                
                logFluxInfo(namespace, release, "automation", "Ensured image pull secret %s/%s", namespace, request.getSecretName());
            } catch (Exception ex) {
                LOG.warn("Failed to ensure image pull secret {}/{}: {}", namespace, request.getSecretName(), ex.getMessage());
                // Continue - secret might already exist
            }
        }

        // 4. Handle dependencies: create HelmRelease YAMLs for each dependency
        // Dependencies are committed to Git alongside the main HelmRelease
        // Note: Flux will reconcile them in parallel (no explicit ordering)
        List<DepRef> depRefs = new ArrayList<>();
        List<PendingDependency> pendingDeps = new ArrayList<>();
        // Every file this deploy writes, searched for passwords and secrets before the commit.
        List<Path> writtenFiles = new ArrayList<>();
        if (request.getDependencies() != null && !request.getDependencies().isEmpty()) {
            try {
                logFluxInfo(namespace, release, "automation", "Processing %s dependencies", request.getDependencies().size());
                Path depDir = targetDir.resolve(DEPENDENCIES_DIR);
                Files.createDirectories(repoDir.resolve(depDir));
                
                for (Map.Entry<String, Object> depEntry : request.getDependencies().entrySet()) {
                    String depKey = depEntry.getKey();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> depSpec = depEntry.getValue() instanceof Map
                            ? (Map<String, Object>) depEntry.getValue()
                            : new LinkedHashMap<>();
                    
                    String depChart = stringOrNull(depSpec.get("chart"));
                    if (depChart == null || depChart.isBlank()) {
                        LOG.warn("Skipping dependency {} with missing chart name", depKey);
                        continue;
                    }
                    String depReleaseName = firstNonBlank(stringOrNull(depSpec.get("releaseName")), depKey);
                    // The same dependencies as a direct deploy: none on OpenShift that the platform provides, none
                    // that the settings do not need, none already on the cluster. One this release already manages
                    // through Flux is kept even though it is now "on the cluster": Flux installed it.
                    // "Manages" = published on the base branch, not merely present in the local workspace.
                    Path depFile = dependencyFile(depDir, depReleaseName, "-" + HELM_RELEASE_FILE);
                    // (the base branch; in direct-commit mode also the working branch this release pushes to, which
                    // Flux reads; never a PR branch, which is not reconciled until merged)
                    Boolean onBase = gitClient.publishedOn(depFile, baseBranch);
                    Boolean onWorking = "PR_MODE".equalsIgnoreCase(git.getCommitMode()) ? Boolean.FALSE
                            : gitClient.publishedOn(depFile, branch);
                    boolean managedHere = Boolean.TRUE.equals(onBase) || Boolean.TRUE.equals(onWorking);
                    String skipped = gitOpsSkipReason(depKey, depSpec, request, managedHere, context, planned);
                    if (skipped != null) {
                        // A published file is left in place: the operator it installs (KEDA, the keytab webhook) is
                        // shared, and other releases may rely on it. One never published (e.g. in a still-open PR
                        // branch) is removed, so merging it cannot install a second copy. When the lookup failed,
                        // nothing is deleted.
                        if (!managedHere && onBase != null && onWorking != null) {
                            gitClient.deletePath(depFile);
                        }
                        logFluxInfo(namespace, release, "automation", "Dependency %s not written: %s", depKey, skipped);
                        continue;
                    }

                    // service.json names it chartVersion; "version" is kept for older definitions. Never the main
                    // chart's version, which belongs to another chart: without one, the HelmRelease names none.
                    String depVersion = firstNonBlank(CommandService.pinnedChartVersion(stringOrNull(depSpec.get("chartVersion"))),
                            CommandService.pinnedChartVersion(stringOrNull(depSpec.get("version"))));
                    String depNamespace = firstNonBlank(stringOrNull(depSpec.get("namespace")), namespace);
                    depRefs.add(new DepRef(depReleaseName, depNamespace));
                    
                    // Create namespace for dependency if different
                    if (!depNamespace.equals(namespace)) {
                        try {
                            kubernetesService.createNamespace(depNamespace);
                            kubernetesService.ensureWebhookEnabledNamespace(depNamespace);
                        } catch (Exception ex) {
                            LOG.warn("Failed to create namespace {} for dependency {}: {}", depNamespace, depKey, ex.getMessage());
                        }
                    }
                    
                    // Build dependency values
                    @SuppressWarnings("unchecked")
                    Map<String, Object> depValues = depSpec.containsKey("values") && depSpec.get("values") instanceof Map
                            ? (Map<String, Object>) depSpec.get("values")
                            : new LinkedHashMap<>();
                    if (planned != null && planned.dependencyOverrides() != null) {
                        // What its Helm install would receive in a direct deploy: the repository's image registry
                        // and pull Secret (created in its namespace now), the keytab webhook's CA bundle and backend
                        // URL, its resolved dynamic values such as the Kerberos realm.
                        Map<String, String> depOverrides;
                        try {
                            depOverrides = planned.dependencyOverrides().of(depKey, depNamespace);
                        } catch (Exception ex) {
                            throw new IllegalStateException("GitOps deploy of " + namespace + "/" + release
                                    + " stopped: dependency " + depKey + " could not be prepared in namespace "
                                    + depNamespace + ", so nothing was committed to Git: " + ex.getMessage(), ex);
                        }
                        applyOverridesToValues(depValues, depOverrides);
                    }

                    // Handle webhook-specific configurations
                    Object isWebhookObj = depSpec.get("isWebhook");
                    boolean isWebhook = Boolean.TRUE.equals(isWebhookObj) || "true".equalsIgnoreCase(String.valueOf(isWebhookObj));
                    if (isWebhook && planned == null) {
                        // Without a plan nothing computes the webhook's CA bundle and backend URL: the dependency's
                        // own values must hold them.
                        LOG.info("Dependency {} is a webhook - ensure webhook config is in values", depKey);
                    }

                    // Use dependency's repoId or fall back to main repo
                    String depRepoName = firstNonBlank(stringOrNull(depSpec.get("repoId")), repoName);

                    // Inject image pull secret if specified. Not for a planned deploy: as in a direct deploy, a
                    // dependency uses the pull Secret its own definition names, created in its namespace.
                    if (planned == null && request.getSecretName() != null && !request.getSecretName().isBlank()) {
                        if (!depValues.containsKey("imagePullSecrets")) {
                            depValues.put("imagePullSecrets", new ArrayList<>());
                        }
                        @SuppressWarnings("unchecked")
                        List<Object> depImagePullSecrets = (List<Object>) depValues.get("imagePullSecrets");
                        boolean hasDepSecret = depImagePullSecrets.stream()
                                .anyMatch(s -> s instanceof Map && request.getSecretName().equals(((Map<?, ?>) s).get("name")));
                        if (!hasDepSecret) {
                            depImagePullSecrets.add(Map.of("name", request.getSecretName()));
                        }
                    }
                    
                    // Rendered once the passwords and secrets of the deploy are known, so none of them is written
                    // to Git in the dependency's values either.
                    pendingDeps.add(new PendingDependency(depReleaseName, depNamespace,
                            chartNameInSource(depChart, isOciRepository(depRepoName)), depVersion, depRepoName,
                            depValues, depFile));
                    if (!depRepoName.equals(repoName)) {
                        // A dependency from another chart repository needs its own Flux source.
                        Path depRepoFile = dependencyFile(depDir, depRepoName, "-" + HELM_REPOSITORY_FILE);
                        gitClient.writeFile(depRepoFile, chartSources.get(depRepoName));
                        writtenFiles.add(depRepoFile);
                    }
                    
                    logFluxInfo(namespace, release, "automation", "Added dependency HelmRelease %s/%s (chart=%s, version=%s)",
                            depNamespace, depReleaseName, depChart, firstNonBlank(depVersion, "not pinned"));
                }
            logFluxInfo(namespace, release, "automation", "Generated %s dependency HelmReleases and dependsOn", depRefs.size());
            } catch (Exception ex) {
                // A direct deploy stops when a dependency cannot be installed: nothing is committed either. Nor is
                // anything for a dependency whose name would place its file outside the release's directory.
                if (planned != null || ex instanceof IllegalArgumentException) {
                    throw ex;
                }
                LOG.warn("Failed to process dependencies for {}/{}: {}", namespace, release, ex.getMessage(), ex);
                // Continue - dependencies might be optional or can be added manually
            }
        }

        logFluxInfo(namespace, release, "automation", "Completed pre-deployment automation steps");
        // ============================================================

        // --- Apply security profile, dynamic overrides, stack globals before rendering values ---
        Map<String, Object> mergedValues = request.getValues() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.getValues());
        Map<String, String> explicitOverrides = new LinkedHashMap<>();
        // Honor existing _ov if present
        Object existingOv = mergedValues.get("_ov");
        if (existingOv instanceof Map<?, ?> ovMap) {
            for (Map.Entry<?, ?> e : ovMap.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    explicitOverrides.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
        if (planned != null) {
            // The command plan applied the security profile it was asked for, Kerberos and the truststore with the
            // rest of the release's preparation, in the order a direct deploy applies them: applied again here, the
            // profile would undo what the plan set on top of it (the SSO login mode, for one).
            explicitOverrides.putAll(planned.overrides());
        } else {
            // Security profile
            if (resolvedSecurityProfile != null && !resolvedSecurityProfile.isBlank()) {
                request.setSecurityProfile(resolvedSecurityProfile);
            }
            applySecurityOverrides(securityCfg, explicitOverrides);
            // Kerberos: on when the security profile asks for it or the Ambari cluster is Kerberized, off when Ambari
            // reports no Kerberos (or manages no cluster) - the same rule as direct deploys. Left to the chart otherwise.
            boolean profileKerberos = securityCfg != null && securityCfg.mode != null
                    && securityCfg.mode.toLowerCase().contains("kerberos");
            // External contexts keep their own rules (Kerberos only from the operator's keytab), as before.
            Boolean ambariKerberos = followsAmbariKerberos(request) ? ambariClusterKerberos(context) : null;
            String kerberosEnabled = kerberosEnabledValue(profileKerberos, ambariKerberos);
            if ("false".equals(kerberosEnabled)) {
                // Not committed for a release that runs with Kerberos on a context nobody recorded: its pods would lose
                // their keytab and krb5.conf.
                String kerberosProblem = CommandService.kerberosSwitchOffProblem(kubernetesService, request);
                if (kerberosProblem != null) {
                    throw new IllegalStateException(kerberosProblem);
                }
                addOverride(explicitOverrides, "global.security.kerberos.enabled", "false");
            } else if ("true".equals(kerberosEnabled)) {
                String krb5ConfigMapName = (release.isBlank() ? "krb5-conf" : (release + "-krb5-conf"));
                try {
                    commandUtils.ensureKrb5ConfConfigMap(namespace, krb5ConfigMapName);
                    addOverride(explicitOverrides, "global.security.kerberos.enabled", "true");
                    addOverride(explicitOverrides, "global.security.kerberos.configMapName", krb5ConfigMapName);
                } catch (Exception ex) {
                    LOG.warn("Failed to prepare Kerberos config map for {}/{}: {}", namespace, release, ex.getMessage());
                }
            }
            // Truststore for downstream TLS clients — shared with the direct deploy path via
            // TrustBundleService: merges (deduped) the Ambari server SSL CA + Ambari Internal CA +
            // operator truststores (defaults + those selected for this release), and provisions even
            // when Ambari itself is not on SSL. Passwords are credential-store/alias resolved.
            try {
                TrustBundleService trustBundleService = new TrustBundleService(viewContext, kubernetesService);
                TrustBundleService.ProvisionResult truststoreResult = trustBundleService.provisionReleaseTruststore(
                        namespace, release, request.getTruststoreRefs());
                if (truststoreResult.provisioned()) {
                    for (Map.Entry<String, String> override : truststoreResult.helmOverrides().entrySet()) {
                        addOverride(explicitOverrides, override.getKey(), override.getValue());
                    }
                    LOG.info("Provisioned truststore Secret '{}' ({} CA(s)) in namespace '{}'",
                            truststoreResult.secretName(), truststoreResult.caCount(), namespace);
                } else {
                    LOG.info("Truststore provisioning produced no CAs; skipping truststore wiring");
                }
            } catch (Exception ex) {
                LOG.warn("Failed to provision truststore Secret: {}", ex.toString());
            }
        }
        // The view's Kerberos injection mode, written as a direct deploy writes it. Left to the chart (WEBHOOK by
        // default), the pods would wait for a keytab webhook that the default PRE_PROVISIONED mode never installs.
        addOverride(explicitOverrides, "global.security.kerberos.injectionMode",
                CommandService.resolveKerberosInjectionMode(viewContext));
        // Passwords and secrets of this deploy, from the definitions: none of them may be written to Git.
        GitOpsSecretValues secrets = new GitOpsSecretValues();
        // Global stack configs / config instantiations. Not for a planned deploy: a direct deploy passes neither to
        // Helm (its plan writes the stack configurations to Secrets), and the planned overrides are what Helm gets.
        if (planned == null) {
            try {
                // Apply bundled global configs (e.g., security-env) into values._ov via overrides
                List<StackConfig> globals = globalConfigService.listGlobalConfigs();
                if (globals != null) {
                    for (StackConfig cfg : globals) {
                        if (cfg.properties == null) continue;
                        for (StackProperty p : cfg.properties) {
                            if (p.name == null || p.name.isBlank()) continue;
                            if (p.value == null || String.valueOf(p.value).isBlank()) continue;
                            addOverride(explicitOverrides, p.name, String.valueOf(p.value));
                            secrets.addPasswordProperty(p.name, p);
                        }
                    }
                }
                // Apply per-request stackConfigOverrides
                if (request.getStackConfigOverrides() != null && !request.getStackConfigOverrides().isEmpty()) {
                    request.getStackConfigOverrides().forEach((k, v) -> {
                        if (k != null && v != null && !k.isBlank()) {
                            addOverride(explicitOverrides, k, String.valueOf(v));
                        }
                    });
                }
            } catch (Exception ex) {
                LOG.warn("Failed to apply global or stack config instantiations: {}", ex.toString());
            }
        }
        // Dynamic overrides (pre-resolved) in values._dynamicOverrides
        ConfigResolutionService.mergeDynamicOverrides(mergedValues, explicitOverrides);
        // Resolve dynamic tokens via Ambari if provided in _dynamicTokens
        try {
            Map<String, String> resolvedTokens = resolveDynamicTokens(mergedValues, release);
            if (resolvedTokens != null) {
                resolvedTokens.forEach((k, v) -> addOverride(explicitOverrides, k, v));
            }
        } catch (Exception ex) {
            LOG.warn("Failed to resolve dynamic tokens for {}: {}", release, ex.toString());
        }
        // The security profile's passwords are marked until the overrides are applied: record them first.
        secrets.addMarkedOverrides(explicitOverrides);
        // Apply all overrides into the values map
        applyOverridesToValues(mergedValues, explicitOverrides);
        // Drop helper _ov to avoid leaking into chart values
        mergedValues.remove("_ov");
        request.setValues(mergedValues);
        addServicePasswords(secrets, serviceKey, request, mergedValues);

        boolean prMode = "PR_MODE".equalsIgnoreCase(git.getCommitMode());
        // Only a direct commit to the branch Flux reconciles is applied as soon as it is pushed. Any other change (a pull
        // request, a working branch still to merge, a branch whose role is unknown) must not alter the running release,
        // yet Flux applies a change of the Secret the release reads at once.
        boolean appliedOnCommit = !prMode && branch.equals(configuredBase);
        // Once secret, always secret: a redeploy that re-submits the release's current values (resync, security
        // refresh, autoscaling) carries no form, yet those values hold what Flux merged in from the values Secrets.
        // What the Secrets hold now is recorded before anything is split.
        StoredSecrets stored = storedSecrets(namespace, release, repoDir.resolve(targetDir).resolve(HELM_RELEASE_FILE));
        for (Map<String, Object> document : stored.documents()) {
            secrets.addStoredPaths(document);
            secrets.addStoredValues(document);
        }
        List<StoredSecrets> storedDeps = new ArrayList<>();
        for (PendingDependency dep : pendingDeps) {
            StoredSecrets depStored = storedSecrets(dep.namespace(), dep.releaseName(), repoDir.resolve(dep.file()));
            depStored.documents().forEach(secrets::addStoredValues);
            storedDeps.add(depStored);
        }
        secrets.addValuesAtPaths(mergedValues);

        // Passwords and secrets go to a Secret in the release namespace, read by the HelmRelease (spec.valuesFrom).
        // They are removed from spec.values, not blanked: Flux merges spec.values over valuesFrom.
        GitOpsSecretValues.Split split = secrets.split(mergedValues);
        List<ValuesSecret> valuesSecrets = new ArrayList<>();
        String valuesSecretName = null;
        if (split.hasSecrets()) {
            ValuesSecret valuesSecret = valuesSecret(namespace, release, split.secretValues(), stored, appliedOnCommit);
            valuesSecrets.add(valuesSecret);
            valuesSecretName = valuesSecret.name();
        }

        String repoYaml = chartSources.get(repoName);

        Map<String, Object> hrSpec = FluxManifests.helmReleaseSpec(chartInSource, version, repoName, depRefs,
                valuesSecretName, split.publicValues());
        String specDigest = specDigest(FluxManifests.specYaml(hrSpec));
        String hrYaml = FluxManifests.helmRelease(release, namespace, specDigest, hrSpec);

        for (int i = 0; i < pendingDeps.size(); i++) {
            PendingDependency dep = pendingDeps.get(i);
            GitOpsSecretValues.Split depSplit = secrets.splitContaining(dep.values(), storedDeps.get(i).documents());
            String depValuesSecretName = null;
            if (depSplit.hasSecrets()) {
                ValuesSecret depSecret = valuesSecret(dep.namespace(), dep.releaseName(), depSplit.secretValues(),
                        storedDeps.get(i), appliedOnCommit);
                valuesSecrets.add(depSecret);
                depValuesSecretName = depSecret.name();
            }
            gitClient.writeFile(dep.file(), FluxManifests.helmRelease(dep.releaseName(), dep.namespace(), null,
                    FluxManifests.helmReleaseSpec(dep.chart(), dep.version(), dep.repoName(), List.of(),
                            depValuesSecretName, depSplit.publicValues())));
            writtenFiles.add(dep.file());
        }
        gitClient.writeFile(targetDir.resolve(HELM_REPOSITORY_FILE), repoYaml);
        gitClient.writeFile(targetDir.resolve(HELM_RELEASE_FILE), hrYaml);
        writtenFiles.add(targetDir.resolve(HELM_REPOSITORY_FILE));
        writtenFiles.add(targetDir.resolve(HELM_RELEASE_FILE));

        // Fail closed: nothing that holds a password or secret is committed, whatever wrote it there.
        String leakingFile = fileLeakingSecrets(repoDir, gitClient, writtenFiles, secrets);
        if (leakingFile != null) {
            throw new IllegalStateException("GitOps deploy of " + namespace + "/" + release + " stopped: "
                    + leakingFile + " would contain a password or secret value, so nothing was committed to Git. "
                    + "A very short or common password can match ordinary settings; if so, choose a stronger one.");
        }

        String headBeforeCommit = gitClient.headCommit();
        // Written before the commit, so Flux never applies a HelmRelease whose values Secret is missing.
        List<ValuesSecret> secretsWritten = writeValuesSecrets(valuesSecrets);

        String commitMsg = prMode
                ? "Flux GitOps (PR) apply " + namespace + "/" + release
                : "Flux GitOps apply " + namespace + "/" + release;

        boolean canPush = (token != null && !token.isBlank()) || (sshKey != null && !sshKey.isBlank());
        String commitSha = null;
        try {
            if (prMode && !canPush) {
                // PR intent without credentials: keep a local commit as a stub and surface to the operator.
                String stubSha = gitClient.commit(commitMsg);
                if (stubSha != null) {
                    commitSha = "PR-STUB:" + stubSha;
                    LOG.warn("Flux GitOps PR mode requested but no credentials provided; created local commit stub {}", commitSha);
                }
            } else {
                // Once the branch moved on the server, the release is written again on top of it, where another
                // release may have taken a directory around this one's since the check above.
                commitSha = commitAndPush(gitClient, commitMsg,
                        () -> checkApartFromOtherReleases(repoDir, targetDir, namespace, release),
                        namespace + "/" + release);
            }
        } catch (Exception commitEx) {
            // Nothing reached Git, so nothing of this deploy may stay ahead of it: neither the Secrets Flux would apply
            // to the running release, nor a local commit the next deploy would take as published.
            undoUnpublished(gitClient, headBeforeCommit, secretsWritten);
            throw commitEx;
        }
        boolean committed = commitSha != null;
        String alias = keepGitCredential(git, gitSecrets, repoUrl, namespace, release);
        // A change that did not land where Flux reads is applied anyway once Git already holds it there: a pull request
        // or working branch merged since, which a redeploy then finds with nothing new to commit.
        boolean appliedNow = appliedOnCommit || (!committed && hrYaml.equals(
                gitClient.publishedContent(targetDir.resolve(HELM_RELEASE_FILE), configuredBase)));
        if (!committed) {
            if (secretsWritten.isEmpty()) {
                LOG.info("Flux GitOps found no changes for {}/{}; skipping commit", namespace, release);
                // Git is unchanged, but a Git credential given with this deploy replaced the one recorded.
                if (existingMeta != null && alias != null && !alias.equalsIgnoreCase(recordedAlias)) {
                    try {
                        releaseMetadataService.recordGitCredentialAlias(namespace, release, alias);
                        forgetReplacedAlias(recordedAlias, namespace, release);
                    } catch (Exception ex) {
                        LOG.warn("Failed to record the Git credential of {}/{}: {}", namespace, release, ex.toString());
                    }
                }
                return new CommitResult("NO_CHANGES",
                        firstNonBlank(gitClient.headCommit(), existingMeta != null ? existingMeta.getGitCommitSha() : null),
                        false, repoUrl, branch, configuredBase, appliedNow,
                        existingMeta != null ? existingMeta.getGitPrUrl() : null,
                        existingMeta != null ? existingMeta.getGitPrState() : null, specDigest);
            }
            // Only passwords or secrets changed: nothing to commit, but Flux must read the Secret again. A change not
            // applied yet never asks for it: its Secret is read only once the change is merged.
            if (appliedOnCommit) {
                requestReconcile(secretsWritten);
            }
            commitSha = firstNonBlank(gitClient.headCommit(), existingMeta != null ? existingMeta.getGitCommitSha() : null);
            logFluxInfo(namespace, release, "apply", "Updated the passwords and secrets only; Git is unchanged at %s", commitSha);
        }
        String prUrl = null;
        String prNumber = null;
        String prState = null;
        if (prMode && committed) {
            // Attempt to open a PR/MR on supported providers.
            try {
                String prToken = token;
                PullRequestInfo info = (prToken != null && !prToken.isBlank()) ? createPullRequest(git.getRepoUrl(), baseBranch, branch, commitMsg, prToken) : null;
                if (info != null) {
                    prUrl = info.url;
                    prNumber = info.id;
                    prState = firstNonBlank(info.state, "open");
                    LOG.info("Flux GitOps PR created for {}/{}: {}", namespace, release, prUrl);
                } else if (!canPush) {
                    LOG.warn("Flux GitOps PR mode without credentials: PR creation skipped; commit is local stub.");
                    prState = "pending";
                } else {
                    LOG.warn("Flux GitOps PR creation skipped/unsupported for repo {}", git.getRepoUrl());
                    prState = "pending";
                }
            } catch (Exception ex) {
                LOG.warn("Flux GitOps PR creation failed for repo {} branch {}: {}", git.getRepoUrl(), branch, ex.toString());
                prState = "pending";
            }
        }

        LOG.info("Flux GitOps wrote manifests for {}/{} into {} branch {} commit {} (prMode={})",
                namespace, release, targetDir, branch, commitSha, prMode);

        // Persist git metadata alongside release for future destroy/status
        try {
            releaseMetadataService.recordInstallOrUpgrade(
                    namespace,
                    release,
                    request.getServiceKey(),
                    request.getPlatformContextId(),
                    chart,
                    repoName,
                    version,
                    request.getValues(),
                    commitSha, // deploymentId
                    java.util.Collections.emptyList(),
                    null, // global config fingerprint unknown here
                    request.getSecurityProfile(),
                    null, // security profile hash not computed here
                    "FLUX_GITOPS",
                    commitSha,
                    branch,
                    targetDir.toString(),
                    repoUrl,
                    alias,
                    git.getCommitMode(),
                    prUrl,
                    prNumber,
                    prState
            );
            releaseMetadataService.recordTruststoreRefs(namespace, release, request.getTruststoreRefs());
            releaseMetadataService.recordGitBaseBranch(namespace, release, configuredBase);
            if (alias != null && recordedAlias != null && !alias.equalsIgnoreCase(recordedAlias)) {
                forgetReplacedAlias(recordedAlias, namespace, release);
            }
        } catch (Exception ex) {
            LOG.warn("Failed to persist Flux metadata for {}/{}: {}", namespace, release, ex.toString());
        }

        if (!committed && prUrl == null && existingMeta != null) {
            // Only the passwords changed: the change Git holds is still the one of the release's pull request.
            prUrl = existingMeta.getGitPrUrl();
            prState = existingMeta.getGitPrState();
        }
        return new CommitResult(commitSha, commitSha, committed, repoUrl, branch, configuredBase, appliedNow, prUrl,
                prState, specDigest);

        } catch (Exception applyEx) {
            // Reset workspace so the next deploy attempt starts from what the server holds.
            resetWorkspaceOnFailure(gitClient, repoDir);
            throw applyEx;
        }
        } finally {
            workspaceLock.unlock();
        }
    }

    private HelmDeployRequest.GitOptions mergeGitOptions(HelmDeployRequest request, K8sReleaseEntity existingMeta) {
        HelmDeployRequest.GitOptions git = request.getGit();
        if (git == null) {
            git = new HelmDeployRequest.GitOptions();
            request.setGit(git);
        }
        if ((git.getRepoUrl() == null || git.getRepoUrl().isBlank()) && existingMeta != null) {
            git.setRepoUrl(existingMeta.getGitRepoUrl());
        }
        if ((git.getBaseBranch() == null || git.getBaseBranch().isBlank()) && existingMeta != null) {
            git.setBaseBranch(existingMeta.getGitBranch());
        }
        if ((git.getBranch() == null || git.getBranch().isBlank()) && existingMeta != null) {
            git.setBranch(existingMeta.getGitBranch());
        }
        if ((git.getCredentialAlias() == null || git.getCredentialAlias().isBlank()) && existingMeta != null) {
            git.setCredentialAlias(existingMeta.getGitCredentialAlias());
        }
        if ((git.getCommitMode() == null || git.getCommitMode().isBlank()) && existingMeta != null) {
            git.setCommitMode(existingMeta.getGitCommitMode());
        }
        return git;
    }

    private Path resolveTargetPath(String pathOverride, String storedPath, String namespace, String release) {
        if (pathOverride != null && !pathOverride.isBlank()) {
            return targetPath(pathOverride, namespace, release);
        }
        if (storedPath != null && !storedPath.isBlank()) {
            return releaseDirectory(storedPath, namespace, release);
        }
        return targetPath("clusters/default", namespace, release);
    }

    @Override
    public HelmReleaseDTO status(String namespace, String releaseName) throws Exception {
        HelmReleaseDTO dto = new HelmReleaseDTO();
        dto.name = releaseName;
        dto.namespace = namespace;
        dto.status = "UNKNOWN";
        dto.reconcileMessage = null;
        var meta = releaseMetadataService.find(namespace, releaseName);
        if (meta != null) {
            dto.gitPrUrl = meta.getGitPrUrl();
            dto.gitPrNumber = meta.getGitPrNumber();
            dto.gitPrState = meta.getGitPrState();
        }
        boolean hasPr = meta != null && meta.getGitPrNumber() != null && !meta.getGitPrNumber().isBlank();

        // If commit was PR-stubbed, surface that before hitting the cluster.
        try {
            if (meta != null && meta.getGitCommitSha() != null && meta.getGitCommitSha().startsWith("PR-STUB:")) {
                dto.status = "PENDING_PR";
                dto.message = "Flux PR mode (local commit " + meta.getGitCommitSha().substring("PR-STUB:".length()) + ") not pushed";
                dto.lastHandledReconcileAt = null;
                // still continue to check cluster; if resource exists, it will override
            }
            if (meta != null && meta.getGitPrUrl() != null) {
                dto.message = (dto.message == null ? "" : dto.message + " | ") + "PR: " + meta.getGitPrUrl();
                dto.lastAttemptedRevision = meta.getGitPrNumber();
                dto.gitPrUrl = meta.getGitPrUrl();
                dto.gitPrNumber = meta.getGitPrNumber();
            }
            refreshPrState(meta, dto);
        } catch (Exception e) {
            // Expected: PR state refresh may fail if Git provider API is unavailable or rate-limited
            // This is non-critical, so we continue with the rest of the status check
            LOG.debug("Failed to refresh PR state for {}/{}: {}", dto.namespace, dto.name, e.getMessage());
        }

        if (kubernetesService == null || kubernetesService.getClient() == null) {
            dto.message = "Flux status unavailable (no k8s client)";
            return dto;
        }

        try {
            CustomResourceDefinitionContext ctx = new CustomResourceDefinitionContext.Builder()
                    .withGroup("helm.toolkit.fluxcd.io")
                    .withVersion("v2beta2")
                    .withScope("Namespaced")
                    .withPlural("helmreleases")
                    .build();

            GenericKubernetesResource hr = kubernetesService.getClient()
                    .genericKubernetesResources(ctx)
                    .inNamespace(namespace)
                    .withName(releaseName)
                    .get();

            if (hr == null) {
                dto.status = "NOT_FOUND";
                dto.message = dto.message == null
                        ? "HelmRelease not found; waiting for Flux to reconcile"
                        : dto.message + " | HelmRelease not found";
                dto.reconcileMessage = dto.message;
                return dto;
            }
            if (hr.getMetadata() != null && hr.getMetadata().getGeneration() != null) {
                dto.desiredGeneration = String.valueOf(hr.getMetadata().getGeneration());
            }
            String sourceName = null;
            String sourceNamespace = "flux-system";
            Object specObj = hr.get("spec");
            if (specObj instanceof java.util.Map) {
                java.util.Map<?, ?> spec = (java.util.Map<?, ?>) specObj;
                Object chartSpecObj = spec.get("chart");
                if (chartSpecObj instanceof java.util.Map) {
                    Object specMap = ((java.util.Map<?, ?>) chartSpecObj).get("spec");
                    if (specMap instanceof java.util.Map) {
                        Object sourceRefObj = ((java.util.Map<?, ?>) specMap).get("sourceRef");
                        if (sourceRefObj instanceof java.util.Map) {
                            java.util.Map<?, ?> src = (java.util.Map<?, ?>) sourceRefObj;
                            sourceName = Optional.ofNullable(src.get("name")).map(Object::toString).orElse(null);
                            sourceNamespace = Optional.ofNullable(src.get("namespace")).map(Object::toString).orElse(sourceNamespace);
                        }
                    }
                }
            }
            dto.status = "UNKNOWN";
            Object statusObj = hr.get("status");
            if (statusObj instanceof java.util.Map) {
                java.util.Map<?, ?> status = (java.util.Map<?, ?>) statusObj;
                dto.lastAppliedRevision = Optional.ofNullable(status.get("lastAppliedRevision")).map(Object::toString).orElse(null);
                dto.lastAttemptedRevision = Optional.ofNullable(status.get("lastAttemptedRevision")).map(Object::toString).orElse(null);
                dto.lastHandledReconcileAt = Optional.ofNullable(status.get("lastHandledReconcileAt")).map(Object::toString).orElse(null);
                dto.observedGeneration = Optional.ofNullable(status.get("observedGeneration")).map(Object::toString).orElse(null);
                dto.reconcileState = Optional.ofNullable(status.get("phase")).map(Object::toString).orElse(null);
                Object condObj = status.get("conditions");
                if (condObj instanceof java.util.List) {
                    @SuppressWarnings("unchecked")
                    java.util.List<java.util.Map<String, Object>> conds = (java.util.List<java.util.Map<String, Object>>) condObj;
                    evaluateConditions(dto, conds);
                    dto.reconcileMessage = dto.message;
                }
                Object hrObserved = status.get("observedGeneration");
                if (hrObserved != null) {
                    dto.message = appendMessage(dto.message, "ObservedGeneration=" + hrObserved);
                }
            }
            try {
                long desired = dto.desiredGeneration != null ? Long.parseLong(dto.desiredGeneration) : -1L;
                long observed = dto.observedGeneration != null ? Long.parseLong(dto.observedGeneration) : -1L;
                evaluateGenerationStaleness(dto, desired, observed);
            } catch (NumberFormatException e) {
                // Expected: Generation values may be non-numeric or malformed in some edge cases
                // This is non-critical for status display, so we continue without staleness evaluation
                LOG.debug("Could not parse generation values for {}/{}: desired={}, observed={}", 
                    dto.namespace, dto.name, dto.desiredGeneration, dto.observedGeneration);
            }
            if (dto.status == null || dto.status.isBlank()) {
                dto.status = "UNKNOWN";
            }
            // Enrich with source (HelmRepository) health if possible
            try {
                String repoToCheck = sourceName;
                if (repoToCheck == null && meta != null) {
                    repoToCheck = meta.getRepoId();
                }
                if (repoToCheck != null) {
                    CustomResourceDefinitionContext repoCtx = new CustomResourceDefinitionContext.Builder()
                            .withGroup("source.toolkit.fluxcd.io")
                            .withVersion("v1beta2")
                            .withScope("Namespaced")
                            .withPlural("helmrepositories")
                            .build();
                    GenericKubernetesResource repo = kubernetesService.getClient()
                            .genericKubernetesResources(repoCtx)
                            .inNamespace(sourceNamespace)
                            .withName(repoToCheck)
                            .get();
                    if (repo != null && repo.get("status") instanceof java.util.Map) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> repoStatus = (java.util.Map<String, Object>) repo.get("status");
                        Object condObj = repoStatus.get("conditions");
                        if (condObj instanceof java.util.List) {
                            @SuppressWarnings("unchecked")
                            java.util.List<java.util.Map<String, Object>> conds = (java.util.List<java.util.Map<String, Object>>) condObj;
                            evaluateSourceConditions(dto, conds);
                        }
                        dto.sourceName = repoToCheck;
                        dto.sourceNamespace = sourceNamespace;
                    }
                }
            } catch (Exception inner) {
                LOG.debug("Flux source status lookup failed for {}/{}: {}", namespace, releaseName, inner.toString());
            }
            return dto;
        } catch (Exception ex) {
            LOG.warn("Failed to read Flux status for {}/{}: {}", namespace, releaseName, ex.toString());
            dto.message = "Flux status error: " + ex.getMessage();
            return dto;
        }
    }

    /**
     * Lightweight existence check to avoid a second status call for PR state handling.
     */
    private boolean hrNotAvailable(String namespace, String releaseName) {
        try {
            CustomResourceDefinitionContext ctx = new CustomResourceDefinitionContext.Builder()
                    .withGroup("helm.toolkit.fluxcd.io")
                    .withVersion("v2beta2")
                    .withScope("Namespaced")
                    .withPlural("helmreleases")
                    .build();
            GenericKubernetesResource hr = kubernetesService.getClient()
                    .genericKubernetesResources(ctx)
                    .inNamespace(namespace)
                    .withName(releaseName)
                    .get();
            return hr == null;
        } catch (Exception e) {
            LOG.debug("hrNotAvailable check failed for {}/{}: {}", namespace, releaseName, e.toString());
            return false;
        }
    }

    /**
     * Simple holder for PR/MR info.
     */
    static class PullRequestInfo {
        final String url;
        final String id;
        final String state;
        final String message;
        PullRequestInfo(String url, String id) { this(url, id, null, null); }
        PullRequestInfo(String url, String id, String state, String message) {
            this.url = url;
            this.id = id;
            this.state = state;
            this.message = message;
        }
    }

    static final class PullRequestParser {
        private static final Gson PARSER = new GsonBuilder().create();

        static PullRequestInfo parseGithub(String body, String defaultId) {
            if (body == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = PARSER.fromJson(body, Map.class);
            if (parsed == null) {
                return null;
            }
            Object url = parsed.get("html_url");
            Object state = parsed.get("state");
            Object merged = parsed.get("merged");
            Object draft = parsed.get("draft");
            String stateStr = state != null ? state.toString() : null;
            if (Boolean.TRUE.equals(merged)) {
                stateStr = "merged";
            } else if (Boolean.TRUE.equals(draft) && stateStr != null && "open".equalsIgnoreCase(stateStr)) {
                stateStr = "draft";
            }
            String message = Optional.ofNullable(parsed.get("mergeable_state")).map(Object::toString).orElse(null);
            String id = formatId(parsed.get("number"), defaultId);
            return new PullRequestInfo(url != null ? url.toString() : null, id, stateStr, message);
        }

        static PullRequestInfo parseGitlab(String body, String defaultId) {
            if (body == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = PARSER.fromJson(body, Map.class);
            if (parsed == null) {
                return null;
            }
            Object url = parsed.get("web_url");
            Object state = parsed.get("state");
            String stateStr = state != null ? state.toString() : null;
            if (parsed.containsKey("merged_at") && parsed.get("merged_at") != null) {
                stateStr = "merged";
            }
            String message = Optional.ofNullable(parsed.get("detailed_merge_status")).map(Object::toString).orElse(null);
            String id = formatId(parsed.get("iid"), defaultId);
            return new PullRequestInfo(url != null ? url.toString() : null, id, stateStr, message);
        }

        private static String formatId(Object value, String fallback) {
            if (value instanceof Number) {
                return String.valueOf(((Number) value).longValue());
            }
            return value != null ? value.toString() : fallback;
        }
    }

    /**
     * Create a PR/MR on GitHub or GitLab if supported.
     *
     * @param repoUrl    remote URL ending with .git or HTTPS form.
     * @param baseBranch target branch (base).
     * @param headBranch source branch (head).
     * @param title      PR/MR title.
     * @param token      OAuth/token with repo write perms (required).
     * @return PullRequestInfo or null if unsupported.
     */
    private PullRequestInfo createPullRequest(String repoUrl, String baseBranch, String headBranch, String title, String token) throws Exception {
        if (token == null || token.isBlank() || repoUrl == null) {
            LOG.warn("Skipping PR creation because token or repoUrl is missing");
            return null;
        }
        java.net.URI uri = pullRequestRepoUri(repoUrl);
        if (uri == null) {
            return null;
        }
        String host = uri.getHost();
        String path = uri.getPath();
        if (path.startsWith("/")) path = path.substring(1);
        if (host.contains("github")) {
            String[] parts = path.split("/");
            if (parts.length < 2) return null;
            String owner = parts[0];
            String repo = parts[1];
            String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/pulls";
            Map<String, String> pullRequest = new LinkedHashMap<>();
            pullRequest.put("title", title);
            pullRequest.put("head", headBranch);
            pullRequest.put("base", baseBranch);
            String body = gson.toJson(pullRequest);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(apiUrl))
                    .timeout(httpTimeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = sendHttpWithRetry(req, "create-pr-github");
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                PullRequestInfo info = PullRequestParser.parseGithub(resp.body(), headBranch);
                return info != null ? info : new PullRequestInfo(extractJsonField(resp.body(), "html_url"), extractJsonField(resp.body(), "number"), "open", null);
            }
            logFluxWarn("createPR", repoUrl, "CREATE", "GitHub PR creation failed (status %d)", resp.statusCode());
        } else {
            String project = java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8);
            String apiUrl = uri.getScheme() + "://" + host + "/api/v4/projects/" + project + "/merge_requests";
            String body = "title=" + java.net.URLEncoder.encode(title, java.nio.charset.StandardCharsets.UTF_8)
                    + "&source_branch=" + java.net.URLEncoder.encode(headBranch, java.nio.charset.StandardCharsets.UTF_8)
                    + "&target_branch=" + java.net.URLEncoder.encode(baseBranch, java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(apiUrl))
                    .timeout(httpTimeout)
                    .header("PRIVATE-TOKEN", token)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = sendHttpWithRetry(req, "create-pr-gitlab");
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                PullRequestInfo info = PullRequestParser.parseGitlab(resp.body(), headBranch);
                return info != null ? info : new PullRequestInfo(extractJsonField(resp.body(), "web_url"), extractJsonField(resp.body(), "iid"), "opened", null);
            }
            logFluxWarn("createPR", repoUrl, "CREATE", "GitLab MR creation failed (status %d)", resp.statusCode());
        }
        return null;
    }

    /**
     * Fetch current PR/MR state from GitHub or GitLab.
     *
     * @param repoUrl   git URL
     * @param prNumber  PR number/IID
     * @param token     auth token (required)
     * @return state string (open/closed/merged etc) or null if unsupported/failure
     */
    private PullRequestInfo fetchPullRequestState(String repoUrl, String prNumber, String token) throws Exception {
        if (repoUrl == null || prNumber == null || token == null) {
            return null;
        }
        java.net.URI uri = pullRequestRepoUri(repoUrl);
        if (uri == null) {
            return null;
        }
        String host = uri.getHost();
        String path = uri.getPath();
        if (path.startsWith("/")) path = path.substring(1);
        if (path.isBlank()) {
            return null;
        }
        if (host.contains("github")) {
            String[] parts = path.split("/");
            if (parts.length < 2) return null;
            String owner = parts[0];
            String repo = parts[1];
            String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/pulls/" + prNumber;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(apiUrl))
                    .timeout(httpTimeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> resp = sendHttpWithRetry(req, "fetch-pr-github");
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return PullRequestParser.parseGithub(resp.body(), prNumber);
            }
            LOG.debug("GitHub PR state lookup failed: status {} body {}", resp.statusCode(), resp.body());
        } else {
            String project = java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8);
            String apiUrl = uri.getScheme() + "://" + host + "/api/v4/projects/" + project + "/merge_requests/" + prNumber;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(apiUrl))
                    .timeout(httpTimeout)
                    .header("PRIVATE-TOKEN", token)
                    .GET()
                    .build();
            HttpResponse<String> resp = sendHttpWithRetry(req, "fetch-pr-gitlab");
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return PullRequestParser.parseGitlab(resp.body(), prNumber);
            }
            LOG.debug("GitLab MR state lookup failed: status {} body {}", resp.statusCode(), resp.body());
        }
        return null;
    }

    /** The access token stored for a release, for the repository it records; {@code null} when there is none. */
    private String resolveToken(K8sReleaseEntity meta) {
        try {
            return recordedGitSecrets(meta).token();
        } catch (Exception ex) {
            LOG.debug("Failed to resolve the Git token of {}/{}: {}", meta.getNamespace(), meta.getReleaseName(),
                    ex.toString());
        }
        return null;
    }

    private void refreshPrState(K8sReleaseEntity meta, HelmReleaseDTO dto) {
        if (meta == null || meta.getGitPrNumber() == null || meta.getGitPrNumber().isBlank()) {
            return;
        }
        String token = resolveToken(meta);
        if (token == null) {
            return;
        }
        try {
            PullRequestInfo info = fetchPullRequestState(meta.getGitRepoUrl(), meta.getGitPrNumber(), token);
            if (info == null) {
                return;
            }
            dto.gitPrState = firstNonBlank(info.state, dto.gitPrState);
            dto.gitPrUrl = firstNonBlank(info.url, dto.gitPrUrl);
            releaseMetadataService.updatePrInfo(meta.getNamespace(), meta.getReleaseName(), dto.gitPrUrl, info.id, dto.gitPrState);
            String prMessage = "PR state: " + dto.gitPrState;
            if (info.message != null && !info.message.isBlank()) {
                prMessage += " (" + info.message + ")";
            }
            dto.message = appendMessage(dto.message, prMessage);
            if (dto.gitPrState != null) {
                boolean hrMissing = hrNotAvailable(meta.getNamespace(), meta.getReleaseName());
                if ("merged".equalsIgnoreCase(dto.gitPrState) && hrMissing) {
                    dto.status = "PROGRESSING";
                    dto.message = appendMessage(dto.message, "PR merged; waiting for Flux reconciliation");
                } else if ("closed".equalsIgnoreCase(dto.gitPrState) && hrMissing) {
                    dto.status = "FAILED";
                    dto.message = appendMessage(dto.message, "PR closed without merge");
                }
            }
        } catch (Exception ex) {
            logFluxWarn(meta != null ? meta.getNamespace() : null, meta != null ? meta.getReleaseName() : null,
                    "pr-poll", "PR polling failed: %s", ex.toString());
        }
    }

    /**
     * Very small JSON field extractor to avoid extra dependencies.
     */
    private String extractJsonField(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(":", idx);
        if (colon < 0) return null;
        int startQuote = json.indexOf("\"", colon);
        int endQuote = json.indexOf("\"", startQuote + 1);
        if (startQuote < 0 || endQuote < 0) return null;
        return json.substring(startQuote + 1, endQuote);
    }

    @Override
    public void destroy(String namespace, String releaseName) throws Exception {
        var meta = releaseMetadataService.find(namespace, releaseName);
        if (meta == null || !"FLUX_GITOPS".equalsIgnoreCase(meta.getDeploymentMode())) {
            LOG.info("Flux destroy skipped for {}/{} because deploymentMode is not FLUX_GITOPS or metadata missing", namespace, releaseName);
            return;
        }
        logFluxInfo(namespace, releaseName, "destroy", "Destroy requested for repo=%s branch=%s path=%s", meta.getGitRepoUrl(), meta.getGitBranch(), meta.getGitPath());
        List<String> errors = new ArrayList<>();
        String repoUrl = meta.getGitRepoUrl();
        String branch = meta.getGitBranch();
        String path = meta.getGitPath();
        if (repoUrl != null && !repoUrl.isBlank()) {
            // Refused before anything is removed, from Git or from the cluster (a HelmRelease deleted there while its
            // manifest stays in Git is recreated by Flux), and the record is kept so the uninstall can be retried
            // once it names the release's own directory.
            releaseDirectory(path, namespace, releaseName);
        }
        String token = null;
        String sshKey = null;
        try {
            GitSecrets secrets = recordedGitSecrets(meta);
            token = secrets.token();
            sshKey = secrets.sshKey();
        } catch (Exception ex) {
            errors.add("Credential resolution failed: " + ex.getMessage());
        }
        try {
            destroy(repoUrl, branch, reconciledBranch(meta), path, token, sshKey, namespace, releaseName);
        } catch (NotOwnDirectoryException | GitOpsBusyException refused) {
            // Found once the branch is read, or the release's workspace is busy: nothing was removed from Git, so
            // nothing is from the cluster either, and the release keeps its Git credential.
            throw refused;
        } catch (Exception ex) {
            // The release's manifests may still be in Git, where Flux keeps applying them: its HelmRelease deleted
            // from the cluster would only be recreated. Nothing else is removed, and the record and the Git credential
            // are kept, so the uninstall can be retried.
            errors.add("Git cleanup failed: " + ex.getMessage());
            LOG.warn("Flux destroy git cleanup failed for {}/{}: {}", namespace, releaseName, ex.toString());
            throw new RuntimeException("Flux destroy encountered issues: " + String.join(" | ", errors), ex);
        }
        // Only once the removal is pushed.
        forgetGitCredentials(meta);
        // Best-effort cluster cleanup of HR/HelmRepository
        try {
            deleteHelmRelease(meta.getNamespace(), meta.getReleaseName());
            if (meta.getRepoId() != null) {
                deleteHelmRepository(meta.getRepoId());
            }
        } catch (Exception ex) {
            errors.add("Cluster cleanup failed: " + ex.getMessage());
            LOG.warn("Flux destroy: cluster cleanup failed for {}/{}: {}", namespace, releaseName, ex.toString());
        }
        try {
            releaseMetadataService.delete(namespace, releaseName);
        } catch (Exception ex) {
            errors.add("Failed to delete metadata: " + ex.getMessage());
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException("Flux destroy encountered issues: " + String.join(" | ", errors));
        }
    }

    /**
     * Refuses, before anything is removed, the uninstall of a GitOps release whose recorded Git credential cannot be
     * used to remove it from Git: one KDPS did not save for the release, one entered for another Git server than the
     * repository recorded, or one no longer in Ambari's credential store. Removed from KDPS anyway, the release would
     * stay in Git, where Flux keeps applying it, with nothing left in KDPS to remove it.
     *
     * @param meta the release's metadata
     * @throws IllegalStateException saying why, and that deploying the release again with its Git token or SSH key
     *                               lets it be uninstalled
     */
    public void checkRemovable(K8sReleaseEntity meta) {
        String reason;
        try {
            GitSecrets secrets = recordedGitSecrets(meta);
            if (secrets.alias() == null || secrets.token() != null || secrets.sshKey() != null) {
                return;
            }
            reason = "The Git credential KDPS saved for it is no longer in Ambari's credential store.";
        } catch (IllegalArgumentException refused) {
            reason = refused.getMessage();
        }
        throw new IllegalStateException("KDPS cannot remove " + meta.getNamespace() + "/" + meta.getReleaseName()
                + " from Git with the Git credential recorded for it, so nothing was removed. " + reason
                + " Deploy the release again once, giving its Git access token or SSH key, then uninstall it.");
    }

    /**
     * Removes a GitOps release's manifests from Git with what its metadata records only: the repository, branch and
     * path it was deployed to, and the Git credential KDPS saved for it. Nothing a request carries is used, so the
     * stored credential only ever goes to the repository the release was deployed from. As any removal from Git
     * ({@link #destroy(String, String, String, String, String, String, String, String)}), it runs in the release's own
     * workspace under its lock and deletes only the manifests the release wrote into its own directory
     * ({@link #releaseDirectory}, {@link #releaseManifests}), committed once and pushed, made again on top of the
     * server's branch when it moved meanwhile. Once the removal is pushed, the release's Git credentials are deleted
     * from Ambari's credential store.
     *
     * @param meta the release's metadata
     * @throws NotOwnDirectoryException when the recorded directory is not the release's own, or holds another
     *                                  HelmRelease, also on the server's branch after it moved; nothing is removed
     *                                  and the credential is kept
     * @throws GitOpsBusyException      when another GitOps operation on the release keeps its workspace past
     *                                  {@link #WORKSPACE_LOCK_WAIT}; nothing is removed and the credential is kept
     * @throws Exception                when Git refused the removal ({@link GitPushRejectedException}) or could not
     *                                  take it (a timeout, an unreachable server); the workspace is reset to the
     *                                  server's branch and the credential is kept
     */
    public void removeFromGit(K8sReleaseEntity meta) throws Exception {
        String repoUrl = meta.getGitRepoUrl();
        if (repoUrl != null && !repoUrl.isBlank()) {
            // Before the credential is read: it is only ever used on the release's own directory.
            releaseDirectory(meta.getGitPath(), meta.getNamespace(), meta.getReleaseName());
        }
        GitSecrets secrets = recordedGitSecrets(meta);
        destroy(repoUrl, meta.getGitBranch(), reconciledBranch(meta), meta.getGitPath(), secrets.token(),
                secrets.sshKey(), meta.getNamespace(), meta.getReleaseName());
        forgetGitCredentials(meta);
    }

    /**
     * Deletes the Git credentials KDPS saved for a release that is gone from Git: the alias recorded for it, the
     * release's own alias and those its deploys keep a new credential under, each unless another release still pushes
     * with it.
     */
    private void forgetGitCredentials(K8sReleaseEntity meta) {
        String instance = viewContext.getInstanceName();
        java.util.Set<String> aliases = new java.util.LinkedHashSet<>();
        if (meta.getGitCredentialAlias() != null && !meta.getGitCredentialAlias().isBlank()) {
            aliases.add(meta.getGitCredentialAlias());
        }
        aliases.add(GitCredentialService.releaseAlias(instance, meta.getNamespace(), meta.getReleaseName()));
        aliases.add(GitCredentialService.pendingAlias(instance, meta.getNamespace(), meta.getReleaseName()));
        aliases.add(GitCredentialService.pendingTokenAlias(instance, meta.getNamespace(), meta.getReleaseName()));
        for (String alias : aliases) {
            forgetAlias(alias, meta.getNamespace(), meta.getReleaseName());
        }
    }

    /** Deletes the alias a release pushed with before a deploy gave it a new Git credential. */
    private void forgetReplacedAlias(String replaced, String namespace, String release) {
        if (replaced != null && !replaced.isBlank()) {
            forgetAlias(replaced, namespace, release);
        }
    }

    /**
     * Deletes an alias KDPS created, unless a release other than {@code namespace/release} records it. Kept when the
     * releases cannot be read.
     */
    private void forgetAlias(String alias, String namespace, String release) {
        if (!GitCredentialService.isViewAlias(alias)) {
            return;
        }
        String releaseId = K8sReleaseEntity.idOf(namespace, release);
        try {
            boolean usedElsewhere = releaseMetadataService.findAll().stream()
                    .anyMatch(other -> !releaseId.equals(other.getId()) && alias.equalsIgnoreCase(other.getGitCredentialAlias()));
            if (usedElsewhere) {
                return;
            }
        } catch (RuntimeException e) {
            LOG.warn("Kept the Git credential alias {}: the releases using it could not be read: {}", alias, e.toString());
            return;
        }
        gitCredentialService.removeSecret(alias);
    }

    /**
     * Deletes the copies of Git credentials earlier versions stored at every GitOps deploy and that no release of this
     * view instance pushes with (see {@link GitCredentialService#removeUnreferencedLegacySecrets}).
     *
     * @return the number of copies deleted, {@code -1} when the cleanup did not run
     */
    public int removeOrphanedGitCredentials() {
        List<String> referenced = releaseMetadataService.findAll().stream()
                .map(K8sReleaseEntity::getGitCredentialAlias)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return gitCredentialService.removeUnreferencedLegacySecrets(referenced);
    }

    /**
     * As {@link #destroy(String, String, String, String, String, String, String, String)}, for a removal pushed to the
     * branch Flux reconciles.
     */
    void destroy(String repoUrl, String branch, String repoPath,
                 String authToken, String sshKey,
                 String namespace, String releaseName) throws Exception {
        destroy(repoUrl, branch, (branch == null || branch.isBlank()) ? "main" : branch, repoPath, authToken, sshKey,
                namespace, releaseName);
    }

    /**
     * The branch Flux reconciles for a GitOps release, as far as its metadata tells: the recorded base branch; for a
     * direct commit without one, the branch it pushes to; null for a pull request without one, whose base is unknown.
     */
    public static String reconciledBranch(K8sReleaseEntity meta) {
        if (meta == null) {
            return null;
        }
        if (meta.getGitBaseBranch() != null && !meta.getGitBaseBranch().isBlank()) {
            return meta.getGitBaseBranch();
        }
        if ("PR_MODE".equalsIgnoreCase(meta.getGitCommitMode())) {
            return null;
        }
        return (meta.getGitBranch() == null || meta.getGitBranch().isBlank()) ? "main" : meta.getGitBranch();
    }

    /**
     * Delete the manifests of a release from Git and push the change, in the release's own workspace so it is
     * serialised with the deploys of the same release. Only the files a deploy of the release writes into its own
     * directory ({@link #releaseDirectory}) are deleted ({@link #releaseManifests}): never a blank path or the bare
     * path prefix, nothing in another release's directory, even one below the release's, and no other file.
     *
     * @param repoUrl          Git repository URL.
     * @param branch           branch to push the removal to (will be created if absent).
     * @param reconciledBranch the branch Flux reconciles, or null when unknown: the values Secrets of the removed
     *                         HelmReleases are deleted only once it no longer holds their manifests
     * @param repoPath         the directory recorded for the release, relative to the repo root.
     * @param authToken        HTTPS token (optional).
     * @param sshKey           SSH private key (optional).
     * @param namespace        namespace of the release.
     * @param releaseName      name of the release.
     * @throws NotOwnDirectoryException when the recorded directory is not the release's own, or its
     *                                   {@code helmrelease.yaml} holds another HelmRelease; nothing is removed
     * @throws GitOpsBusyException       when another GitOps operation on the release keeps its workspace past
     *                                   {@link #WORKSPACE_LOCK_WAIT}; nothing is removed
     * @throws GitPushRejectedException  when the server refused the removal; the workspace is reset to the server's
     *                                   branch
     */
    public void destroy(String repoUrl, String branch, String reconciledBranch, String repoPath,
                        String authToken, String sshKey,
                        String namespace, String releaseName) throws Exception {
        if (repoUrl == null || repoUrl.isBlank()) {
            LOG.warn("Flux destroy skipped because repoUrl is missing");
            return;
        }
        Path releaseDir = releaseDirectory(repoPath, namespace, releaseName);
        String effectiveBranch = (branch == null || branch.isBlank()) ? "main" : branch;
        // Use the same per-release workspace as apply() so both operations share the clone.
        Path repoDir = workspaceForRelease(namespace, releaseName);
        ReentrantLock workspaceLock = lockWorkspace(namespace, releaseName);
        try {
        GitClient gitClient = new GitClient(repoDir, repoUrl, effectiveBranch, authToken, sshKey)
                .withTimeout(gitTimeoutSeconds(viewContext));
        try {
        withGitRetry(() -> { gitClient.sync(); return null; }, "destroy-sync");
        withGitRetry(() -> { gitClient.checkoutBranch(effectiveBranch); return null; }, "destroy-checkout");
        // As for a deploy: the removal is made on top of the branch the working branch was merged into. On top of its
        // merged commits instead, it would come to nothing once merged: together they change nothing since the commit
        // the working branch started from, and the branch Flux reads would keep the release.
        gitClient.restartIfMergedInto(reconciledBranch);

        // Only the files a deploy of the release writes, never the whole directory: the path prefix is free, so the
        // directory of another release, or other content of the repository, may lie below this one.
        checkOwnHelmRelease(repoDir, releaseDir, namespace, releaseName);
        List<Path> files = releaseManifests(repoDir.resolve(releaseDir));
        // The HelmReleases removed here and their values Secrets, read while their manifests are still there.
        java.util.Set<NamespacedName> helmReleases = helmReleasesIn(files);
        helmReleases.add(new NamespacedName(namespace, releaseName));
        java.util.Set<NamespacedName> valuesSecrets = valuesSecretsIn(files);
        List<Path> manifests = files.stream().map(repoDir::relativize).collect(Collectors.toList());
        logFluxInfo(namespace, releaseName, "destroy", "Removing %s", manifests);
        for (Path manifest : manifests) {
            gitClient.deletePath(manifest);
        }
        // Once the branch moved on the server, the removal is made again on top of it, provided the directory still
        // holds the release's own HelmRelease there.
        String sha = commitAndPush(gitClient, "Flux GitOps delete " + releaseDir,
                () -> checkOwnHelmRelease(repoDir, releaseDir, namespace, releaseName),
                namespace + "/" + releaseName + " (uninstall)");
        if (sha != null) {
            LOG.info("Flux GitOps deleted {} on branch {} commit {}", releaseDir, effectiveBranch, sha);
        } else {
            LOG.info("Flux GitOps delete skipped commit because no changes detected at {}", releaseDir);
        }
        // Only once the removal is published where Flux reads: until then Flux keeps applying the HelmReleases that
        // read them. A removal pushed to another branch (that of a pull request, a working branch still to merge)
        // leaves them running from the branch Flux reconciles.
        if (reconciledBranch == null) {
            LOG.warn("Kept the values Secrets {}: the branch Flux reconciles is not known, so its HelmReleases may still "
                    + "read them; delete them once the removal of {} is merged", valuesSecrets, releaseDir);
        } else if (!reconciledBranch.equals(effectiveBranch)
                && java.util.stream.Stream.concat(java.util.stream.Stream.of(releaseDir.resolve(HELM_RELEASE_FILE)),
                                manifests.stream())
                        .anyMatch(manifest -> !Boolean.FALSE.equals(gitClient.publishedOn(manifest, reconciledBranch)))) {
            LOG.warn("Kept the values Secrets {}: {} is still on branch {}, which Flux reconciles; delete them once the "
                    + "removal is merged there", valuesSecrets, releaseDir, reconciledBranch);
        } else {
            deleteValuesSecrets(valuesSecrets, helmReleases);
        }
        } catch (Exception destroyEx) {
            // Nothing of a failed removal may stay in the workspace for the next operation to take as published.
            resetWorkspaceOnFailure(gitClient, repoDir);
            throw destroyEx;
        }
        } finally {
            workspaceLock.unlock();
        }
    }

    /**
     * Compute the target directory path for the given prefix/namespace/release.
     *
     * @param pathPrefix user-provided prefix (can be relative).
     * @param namespace  namespace of the release, a single path segment.
     * @param release    release name, a single path segment.
     * @return resolved path under the configured flux root.
     * @throws IllegalArgumentException when the namespace or the release would name another directory
     */
    public Path targetPath(String pathPrefix, String namespace, String release) {
        return validateRelative(Path.of(pathPrefix)
                .resolve(pathSegment("namespace", namespace))
                .resolve(pathSegment("release name", release)));
    }

    static List<java.util.Map<String, String>> mapConditions(java.util.List<java.util.Map<String, Object>> conds) {
        List<java.util.Map<String, String>> list = new ArrayList<>();
        for (java.util.Map<String, Object> c : conds) {
            java.util.Map<String, String> mapped = new LinkedHashMap<>();
            mapped.put("type", Objects.toString(c.get("type"), ""));
            mapped.put("status", Objects.toString(c.get("status"), ""));
            mapped.put("reason", Objects.toString(c.get("reason"), ""));
            mapped.put("message", Objects.toString(c.get("message"), ""));
            mapped.put("lastTransitionTime", Objects.toString(c.get("lastTransitionTime"), ""));
            list.add(mapped);
        }
        return list;
    }

    // -----------------------------------------------------------------------
    // Workspace helpers
    // -----------------------------------------------------------------------

    /**
     * Strips characters that are unsafe in directory names, lowercases the result.
     * Used to build deterministic, filesystem-safe workspace directory names.
     */
    private static String sanitizePathComponent(String s) {
        if (s == null || s.isBlank()) return "unknown";
        return s.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase();
    }

    /**
     * Returns the per-release git workspace directory.
     * Each release gets its own clone so concurrent deploys to different
     * releases never interfere.  Concurrent operations on the SAME release
     * are serialised via {@link #lockWorkspace}.
     */
    private Path workspaceForRelease(String namespace, String releaseName) {
        return fluxRoot.resolve(
            "gitops-" + sanitizePathComponent(namespace) + "-" + sanitizePathComponent(releaseName)
        );
    }

    /** Returns (and creates if absent) the per-release ReentrantLock, taken through {@link #lockWorkspace}. */
    private ReentrantLock lockForRelease(String namespace, String releaseName) {
        return WORKSPACE_LOCKS.computeIfAbsent(
            namespace + ":" + releaseName,
            k -> new ReentrantLock()
        );
    }

    /**
     * Takes the lock of the release's workspace, waiting at most {@link #WORKSPACE_LOCK_WAIT} for another operation
     * on the release to release it. Never waits for good: an operation stuck on an unresponsive Git server must not
     * pin every later request for the release until Ambari is restarted.
     *
     * @return the lock, held; unlock it in a finally block
     * @throws GitOpsBusyException when another operation on the release still holds it
     */
    private ReentrantLock lockWorkspace(String namespace, String releaseName) throws InterruptedException {
        ReentrantLock lock = lockForRelease(namespace, releaseName);
        if (!lock.tryLock(workspaceLockWait.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new GitOpsBusyException(namespace, releaseName);
        }
        return lock;
    }

    /**
     * Another GitOps deploy or uninstall of the release is still using its Git workspace. Nothing was done; the
     * operation can be retried once the other one has finished.
     */
    public static final class GitOpsBusyException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public GitOpsBusyException(String namespace, String releaseName) {
            super("Another GitOps operation on " + namespace + "/" + releaseName + " is in progress. Try again once it"
                    + " has finished.");
        }
    }

    /**
     * Best-effort reset of a git workspace after a failed operation: back to the server's branch as last fetched, so
     * the next attempt neither starts from a dirty tree nor takes a commit that was never pushed for published.
     */
    private void resetWorkspaceOnFailure(GitClient gitClient, Path repoDir) {
        if (!Files.exists(repoDir.resolve(".git"))) return;
        try {
            gitClient.resetToRemote();
        } catch (Exception ex) {
            LOG.warn("Could not reset workspace {} after failure: {}", repoDir, ex.getMessage());
        }
    }

    /** A check made again on the server's branch before a change is written again over it. */
    @FunctionalInterface
    private interface BranchCheck {
        void check() throws Exception;
    }

    /**
     * Commits the working tree, once, and pushes it ({@link #pushRebasing}). When there is nothing new to commit but
     * the branch holds commits the server does not have, those are pushed: a deploy never reports that Git already
     * holds the release while the server lacks it. Commits merged since into the branch Flux reads are not among them:
     * the branch was started again from there once checked out ({@link GitClient#restartIfMergedInto}).
     *
     * @param recheck   run on the server's branch before the change is made again on top of it
     * @param operation what is pushed, for the logs
     * @return the commit the server now holds with the change, or {@code null} when it already held it
     */
    private String commitAndPush(GitClient gitClient, String message, BranchCheck recheck, String operation)
            throws Exception {
        String sha = gitClient.commit(message);
        if (sha == null) {
            int unpublished = gitClient.unpublishedCommits();
            if (unpublished == 0) {
                return null;
            }
            LOG.warn("Git branch of {} holds {} commit(s) the server does not have: pushing them", operation, unpublished);
        }
        return pushRebasing(gitClient, message, recheck, operation);
    }

    /**
     * Pushes the commit the workspace is on. A push that fails on the way (the network, the server busy) is tried
     * again as it is ({@link #withGitRetry}). One refused because the branch moved on the server since it was fetched
     * (another deploy pushed to it first) is made again on top of the server's branch: fetched, reset to it, this
     * operation's change written again over it and committed, then pushed, up to {@link #MAX_PUSH_ROUNDS} times. Any
     * other refusal (a protected branch, a server hook, a branch name the server cannot create) fails with the server's
     * message.
     *
     * @return the commit the server now holds with the change, or {@code null} when it already held it
     * @throws GitPushRejectedException when the server refused the push; nothing changed on the server
     */
    private String pushRebasing(GitClient gitClient, String message, BranchCheck recheck, String operation)
            throws Exception {
        for (int round = 1; ; round++) {
            try {
                return withGitRetry(gitClient::push, "push " + operation);
            } catch (GitPushRejectedException rejected) {
                if (!rejected.mayBeBranchMoved() || round >= MAX_PUSH_ROUNDS) {
                    throw rejected;
                }
                GitClient.Change change = gitClient.unpublishedChange();
                try {
                    withGitRetry(() -> { gitClient.fetch(); return null; }, "fetch " + operation);
                } catch (IOException fetchFailed) {
                    rejected.addSuppressed(fetchFailed);
                    throw rejected;
                }
                // Told by the server's branch, not by the server's words: the words of a lost race also cover refusals
                // a new attempt cannot fix. Made again over a branch that did not move, or that the server does not
                // have, the change would only find the workspace's own unpushed commit and be taken as already in Git.
                if (!gitClient.branchMovedOnServer()) {
                    throw rejected;
                }
                LOG.info("Git branch {} moved on the server while {} was pushed: writing the change again on top of it"
                        + " (attempt {}/{})", rejected.getBranch(), operation, round + 1, MAX_PUSH_ROUNDS);
                gitClient.resetToRemote();
                recheck.check();
                gitClient.write(change);
                if (gitClient.commit(message) == null) {
                    // Nothing new to commit on top of the server's branch is the server already holding the change,
                    // provided the workspace holds nothing the server lacks.
                    if (gitClient.unpublishedCommits() > 0) {
                        throw rejected;
                    }
                    LOG.info("Git branch {} already holds the change of {}", rejected.getBranch(), operation);
                    return null;
                }
            }
        }
    }

    /**
     * A path in the Git repository, normalised: relative, without "..", without control characters.
     *
     * @throws IllegalArgumentException for any other path
     */
    static Path validateRelative(Path path) {
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("Flux GitOps path must be relative to repo: " + path);
        }
        // Checked before normalising: "team-a/../team-b/trino" normalises to a path without "..", yet it is the
        // directory of another release.
        for (Path part : path) {
            if ("..".equals(part.toString())) {
                throw new IllegalArgumentException("Flux GitOps path cannot traverse outside repo: " + path);
            }
            if (part.toString().chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Flux GitOps path cannot contain control characters: "
                        + DeployRequestValidator.shown(path.toString()));
            }
        }
        return path.normalize();
    }

    /**
     * A name used as one segment of a path in the Git repository: a namespace, a release, a dependency.
     *
     * @param what what the name names, for the message
     * @return the name
     * @throws IllegalArgumentException when it could name another directory ({@code /}, {@code \}, {@code .},
     *                                  {@code ..}) or holds a control character
     */
    static String pathSegment(String what, String name) {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.chars().anyMatch(c -> c == '/' || c == '\\' || Character.isISOControl(c))) {
            String shown = name == null ? "" : DeployRequestValidator.shown(name);
            throw new IllegalArgumentException("The " + what + " '" + shown + "' cannot name a directory in Git: it"
                    + " must be a single name, without '/', '\\' or '..'.");
        }
        return name;
    }

    /**
     * A file of the dependencies directory of a release, named after a dependency or a chart repository.
     *
     * @param dependenciesDir the {@code dependencies} directory of the release
     * @param name            the dependency or chart repository
     * @param suffix          the rest of the file name, such as {@code -helmrelease.yaml}
     * @throws IllegalArgumentException when the name would place the file anywhere else in the repository
     */
    static Path dependencyFile(Path dependenciesDir, String name, String suffix) {
        Path file = dependenciesDir.resolve(pathSegment("dependency", name) + suffix);
        if (!dependenciesDir.equals(file.normalize().getParent())) {
            throw new IllegalArgumentException("The dependency '" + DeployRequestValidator.shown(name)
                    + "' cannot name a file outside " + dependenciesDir + " in Git.");
        }
        return file;
    }

    /**
     * The directory recorded for a release in the Git repository, checked to be the release's own: a relative path
     * that ends with {@code <namespace>/<release>}. A redeploy writes into it and an uninstall deletes the release's
     * manifests from it, so a blank path, the bare path prefix or another release's directory is refused.
     *
     * @param recordedPath the directory recorded for the release
     * @param namespace    namespace of the release
     * @param release      name of the release
     * @return the directory, normalised
     * @throws NotOwnDirectoryException when the path is blank or is not the release's own directory
     * @throws IllegalArgumentException when the namespace, the release or the path is malformed
     */
    public static Path releaseDirectory(String recordedPath, String namespace, String release) {
        Path own = Path.of(pathSegment("namespace", namespace), pathSegment("release name", release));
        if (recordedPath == null || recordedPath.isBlank()) {
            throw new NotOwnDirectoryException("No Git directory is recorded for release " + own + ", so KDPS changes"
                    + " nothing in Git for it; remove its manifests from the repository by hand if needed.");
        }
        Path path = validateRelative(Path.of(recordedPath));
        if (!path.endsWith(own)) {
            throw new NotOwnDirectoryException("The Git directory recorded for release " + own + ", '"
                    + DeployRequestValidator.shown(recordedPath) + "', is not the release's own directory"
                    + " (<path prefix>/" + own + "), so KDPS changes nothing in it.");
        }
        return path;
    }

    /**
     * A Git directory refused for a release: not the release's own, inside or above the directory of another
     * release, or holding another release's HelmRelease. Nothing was written to or removed from Git for the release,
     * nor removed from the cluster.
     */
    public static final class NotOwnDirectoryException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public NotOwnDirectoryException(String message) {
            super(message);
        }
    }

    /**
     * Refuses a release directory that is inside, the same as or above the directory recorded for another release
     * deployed to the same Git repository, so each release has a directory of its own and the deploys of one never
     * write among the other's manifests. Covers releases whose manifests are not on the branch yet, such as one
     * proposed in a pull request still open.
     *
     * @param releaseDir the directory the release's manifests are written to
     * @param repoUrl    the Git repository they are written to
     * @throws NotOwnDirectoryException naming the other release and its directory
     */
    void checkApartFromRecordedReleases(Path releaseDir, String repoUrl, String namespace, String release) {
        String id = K8sReleaseEntity.idOf(namespace, release);
        List<K8sReleaseEntity> others = releaseMetadataService.findAll().stream()
                .filter(other -> other != null && !id.equals(other.getId())
                        && !(namespace.equals(other.getNamespace()) && release.equals(other.getReleaseName()))
                        && other.getGitPath() != null
                        && !other.getGitPath().isBlank() && sameRepository(repoUrl, other.getGitRepoUrl()))
                .sorted(java.util.Comparator.comparing(K8sReleaseEntity::getGitPath))
                .collect(Collectors.toList());
        for (K8sReleaseEntity other : others) {
            Path otherDir;
            try {
                otherDir = validateRelative(Path.of(other.getGitPath()));
            } catch (IllegalArgumentException unusable) {
                continue;
            }
            String otherRelease = other.getNamespace() + "/" + other.getReleaseName();
            if (releaseDir.startsWith(otherDir) || otherDir.startsWith(releaseDir)) {
                throw new NotOwnDirectoryException(nestedMessage(releaseDir, namespace, release, otherDir,
                        otherRelease));
            }
        }
    }

    /**
     * Refuses a release directory of the Git workspace that is inside the directory of another release, holds one,
     * or holds the HelmRelease of another release. A directory is a release's when it holds a
     * {@code helmrelease.yaml} naming the HelmRelease {@code <namespace>/<release>} its path ends with, as a deploy
     * writes it.
     *
     * @param workspace  the Git workspace, checked out on the branch the release is written to
     * @param releaseDir the release's directory, relative to the workspace
     * @throws NotOwnDirectoryException naming the other release and its directory
     * @throws IOException              when the directory cannot be read
     */
    static void checkApartFromOtherReleases(Path workspace, Path releaseDir, String namespace, String release)
            throws IOException {
        checkOwnHelmRelease(workspace, releaseDir, namespace, release);
        // A release's directory is <prefix>/<namespace>/<release>: no directory of fewer segments is one.
        for (Path parent = releaseDir.getParent(); parent != null && parent.getNameCount() >= 2;
             parent = parent.getParent()) {
            NamespacedName owner = releaseOf(workspace, parent);
            if (owner != null) {
                throw new NotOwnDirectoryException(nestedMessage(releaseDir, namespace, release, parent,
                        owner.namespace() + "/" + owner.name()));
            }
        }
        Path own = workspace.resolve(releaseDir);
        if (!Files.isDirectory(own)) {
            return;
        }
        List<Path> below;
        try (java.util.stream.Stream<Path> walk = Files.walk(own)) {
            below = walk.filter(file -> HELM_RELEASE_FILE.equals(String.valueOf(file.getFileName())))
                    .map(Path::getParent)
                    .filter(dir -> !dir.equals(own))
                    .map(workspace::relativize)
                    .sorted()
                    .collect(Collectors.toList());
        }
        for (Path dir : below) {
            NamespacedName owner = releaseOf(workspace, dir);
            if (owner != null) {
                throw new NotOwnDirectoryException(nestedMessage(releaseDir, namespace, release, dir,
                        owner.namespace() + "/" + owner.name()));
            }
        }
    }

    /**
     * Refuses a release directory of the Git workspace whose {@code helmrelease.yaml} holds a HelmRelease other than
     * the release's: a deploy would overwrite it, an uninstall delete it.
     *
     * @throws NotOwnDirectoryException naming the HelmRelease found
     */
    static void checkOwnHelmRelease(Path workspace, Path releaseDir, String namespace, String release) {
        for (Map<?, ?> doc : helmReleaseDocuments(workspace.resolve(releaseDir).resolve(HELM_RELEASE_FILE))) {
            Map<?, ?> metadata = (Map<?, ?>) doc.get("metadata");
            String found = metadata.get("namespace") + "/" + metadata.get("name");
            if (!found.equals(namespace + "/" + release)) {
                throw new NotOwnDirectoryException("The Git directory of release " + namespace + "/" + release + ", '"
                        + releaseDir + "', holds the HelmRelease " + DeployRequestValidator.shown(found)
                        + " in " + HELM_RELEASE_FILE + ", so KDPS changes nothing in it. Choose another path prefix,"
                        + " or move that HelmRelease out of the directory.");
            }
        }
    }

    private static String nestedMessage(Path releaseDir, String namespace, String release, Path otherDir,
                                        String otherRelease) {
        String relation = releaseDir.equals(otherDir) ? "is also the directory"
                : releaseDir.startsWith(otherDir) ? "is inside the directory" : "holds the directory";
        return "The Git directory of release " + namespace + "/" + release + ", '" + releaseDir + "', " + relation
                + " of release " + DeployRequestValidator.shown(otherRelease) + ", '" + otherDir + "', so KDPS"
                + " writes nothing there: each release needs a directory of its own, neither inside nor above"
                + " another release's. Choose a path prefix that gives it one, such as the default clusters/default.";
    }

    /**
     * The release whose directory a directory of the Git workspace is, or {@code null}: the HelmRelease
     * {@code <namespace>/<release>} its path ends with, in its {@code helmrelease.yaml}.
     */
    static NamespacedName releaseOf(Path workspace, Path dir) {
        if (dir.getNameCount() < 2) {
            return null;
        }
        String namespace = dir.getName(dir.getNameCount() - 2).toString();
        String release = dir.getFileName().toString();
        for (Map<?, ?> doc : helmReleaseDocuments(workspace.resolve(dir).resolve(HELM_RELEASE_FILE))) {
            Map<?, ?> metadata = (Map<?, ?>) doc.get("metadata");
            if (namespace.equals(String.valueOf(metadata.get("namespace")))
                    && release.equals(String.valueOf(metadata.get("name")))) {
                return new NamespacedName(namespace, release);
            }
        }
        return null;
    }

    /**
     * Whether two URLs name the same Git repository, as far as their text tells: equal but for case, a trailing
     * {@code /} or {@code .git}.
     */
    static boolean sameRepository(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return repositoryKey(a).equalsIgnoreCase(repositoryKey(b));
    }

    private static String repositoryKey(String url) {
        String key = url.trim();
        while (key.endsWith("/")) {
            key = key.substring(0, key.length() - 1);
        }
        return key.endsWith(".git") ? key.substring(0, key.length() - ".git".length()) : key;
    }

    /** The values as a YAML document, without the document start marker nor folded lines. */
    static String valuesYaml(Map<String, Object> values) throws IOException {
        com.fasterxml.jackson.dataformat.yaml.YAMLFactory yf =
            com.fasterxml.jackson.dataformat.yaml.YAMLFactory.builder()
                .disable(com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .disable(com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.SPLIT_LINES)
                .build();
        return new com.fasterxml.jackson.databind.ObjectMapper(yf)
                .writeValueAsString(values)
                .trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String appendMessage(String existing, String addition) {
        if (addition == null || addition.isBlank()) {
            return existing;
        }
        return (existing == null || existing.isBlank()) ? addition : existing + " | " + addition;
    }

    /**
     * Derive a status/message from Flux conditions. This captures Ready/Progressing/Stalled
     * and produces a compact summary for operators.
     *
     * @param dto    target DTO to update
     * @param conds  list of condition maps from HelmRelease.status.conditions
     */
    static void evaluateConditions(HelmReleaseDTO dto, java.util.List<java.util.Map<String, Object>> conds) {
        dto.conditions = mapConditions(conds);
        // Ready drives primary state
        java.util.Map<String, Object> ready = conds.stream()
                .filter(c -> "Ready".equalsIgnoreCase(Objects.toString(c.get("type"), "")))
                .findFirst().orElse(null);
        if (ready != null) {
            String st = Objects.toString(ready.get("status"), "Unknown");
            if ("True".equalsIgnoreCase(st)) {
                dto.status = "SUCCEEDED";
            } else if ("False".equalsIgnoreCase(st)) {
                dto.status = "FAILED";
            } else {
                dto.status = "PROGRESSING";
            }
            String ltt = Objects.toString(ready.get("lastTransitionTime"), null);
            if (ltt != null) {
                dto.lastTransitionTime = ltt;
            }
            String msg = Objects.toString(ready.get("message"), null);
            String reason = Objects.toString(ready.get("reason"), null);
            dto.message = appendMessage(dto.message, (reason != null ? reason + ": " : "") + (msg != null ? msg : ""));
        }

        // Progressing gives detail while reconciling
        java.util.Map<String, Object> progressing = conds.stream()
                .filter(c -> "Progressing".equalsIgnoreCase(Objects.toString(c.get("type"), "")))
                .findFirst().orElse(null);
        if (progressing != null) {
            if (dto.status == null || "UNKNOWN".equals(dto.status)) {
                dto.status = "PROGRESSING";
            }
            String progMsg = Objects.toString(progressing.get("message"), null);
            if (progMsg != null && (dto.message == null || dto.message.isBlank())) {
                dto.message = appendMessage(dto.message, progMsg);
            }
        }

        // Stalled (common Flux condition) marks failure.
        java.util.Map<String, Object> stalled = conds.stream()
                .filter(c -> "Stalled".equalsIgnoreCase(Objects.toString(c.get("type"), "")))
                .findFirst().orElse(null);
        if (stalled != null) {
            String st = Objects.toString(stalled.get("status"), "");
            if ("True".equalsIgnoreCase(st)) {
                dto.status = "FAILED";
            }
            String stallMsg = Objects.toString(stalled.get("message"), null);
            String stallReason = Objects.toString(stalled.get("reason"), null);
            if (stallMsg != null) {
                dto.message = appendMessage(dto.message, (stallReason != null ? stallReason + ": " : "") + stallMsg);
            }
        }

        // Capture a concise condition summary for operators.
        StringBuilder summary = new StringBuilder();
        for (java.util.Map<String, Object> c : conds) {
            String type = Objects.toString(c.get("type"), "");
            String st = Objects.toString(c.get("status"), "");
            String reason = Objects.toString(c.get("reason"), "");
            String msg = Objects.toString(c.get("message"), "");
            if (summary.length() > 0) {
                summary.append(" | ");
            }
            summary.append(type).append("=").append(st);
            if (!reason.isBlank()) {
                summary.append(" (").append(reason).append(")");
            }
            if (!msg.isBlank()) {
                summary.append(" ").append(msg);
            }
        }
        if (summary.length() > 0) {
            dto.message = appendMessage(dto.message, summary.toString());
        }
        dto.conditionSummary = summary.length() > 0 ? summary.toString() : null;

        if (dto.status == null || dto.status.isBlank()) {
            dto.status = "UNKNOWN";
        }
    }

    /** The value of the {@link #SPEC_DIGEST_ANNOTATION} for a HelmRelease spec, as written to Git. */
    static String specDigest(String spec) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(spec.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Where a HelmRelease on the cluster stands for the version a deploy committed. Ready once Flux has applied that
     * version (its {@link #SPEC_DIGEST_ANNOTATION}), handled the reconcile or reset requested for it, and reports it
     * Ready. Failed when Flux gave up on it (Stalled), with the cause Flux reports on its Ready condition. Otherwise
     * still waiting, with what Flux reports meanwhile: it has not pulled the commit yet, or is installing, upgrading or
     * retrying the release.
     *
     * @param helmRelease    the HelmRelease on the cluster, {@code null} when it is not there
     * @param expectedDigest the annotation of the committed version; {@code null} to take the version on the cluster
     */
    public static Readiness readiness(GenericKubernetesResource helmRelease, String expectedDigest) {
        return readiness(helmRelease, expectedDigest, null);
    }

    /**
     * {@link #readiness(GenericKubernetesResource, String)} after asking Flux to try again a release it gave up on.
     *
     * @param resetRequestedAt the reset asked for ({@link KubernetesService#requestFluxReset}): until Flux has handled
     *                         it, its Stalled condition is the one from before. {@code null} when none was asked for.
     */
    public static Readiness readiness(GenericKubernetesResource helmRelease, String expectedDigest,
                                      String resetRequestedAt) {
        if (helmRelease == null) {
            return new Readiness(false, false,
                    "the HelmRelease is not on the cluster yet: Flux has not applied the commit", false);
        }
        io.fabric8.kubernetes.api.model.ObjectMeta metadata = helmRelease.getMetadata();
        Map<String, String> annotations = metadata == null || metadata.getAnnotations() == null
                ? Map.of() : metadata.getAnnotations();
        if (expectedDigest != null && !expectedDigest.equals(annotations.get(SPEC_DIGEST_ANNOTATION))) {
            return new Readiness(false, false,
                    "Flux has not applied the commit yet: the HelmRelease on the cluster is still the previous version",
                    false);
        }
        long generation = metadata == null || metadata.getGeneration() == null ? 0L : metadata.getGeneration();
        Map<?, ?> status = helmRelease.get("status") instanceof Map<?, ?> s ? s : Map.of();
        long observedGeneration = asLong(status.get("observedGeneration"));
        List<Map<?, ?>> conditions = new ArrayList<>();
        if (status.get("conditions") instanceof List<?> listed) {
            for (Object c : listed) {
                if (c instanceof Map<?, ?> m) conditions.add(m);
            }
        }
        Map<?, ?> ready = currentCondition(conditions, "Ready", generation, observedGeneration);
        Map<?, ?> stalled = currentCondition(conditions, "Stalled", generation, observedGeneration);
        boolean isReady = ready != null && isTrue(ready);
        boolean isStalled = stalled != null && isTrue(stalled);

        // A request Flux has not handled yet: the state it reports Ready or given up on predates it.
        String pending = null;
        if (resetRequestedAt != null
                && !resetRequestedAt.equals(Objects.toString(status.get("lastHandledResetAt"), null))) {
            pending = "Flux has not started its new attempt at the release yet";
        } else {
            String requestedAt = annotations.get("reconcile.fluxcd.io/requestedAt");
            if (requestedAt != null
                    && !requestedAt.equals(Objects.toString(status.get("lastHandledReconcileAt"), null))) {
                pending = "Flux has not read the new passwords and secrets of the release yet";
            }
        }
        if (pending != null) {
            // Meanwhile, what Flux reports of the attempt under way.
            String underWay = ready != null && !isReady && !isStalled ? describeCondition(ready) : "";
            return new Readiness(false, false, underWay.isBlank() ? pending : underWay, true);
        }
        if (isStalled) {
            // Flux words why it gave up (RetriesExceeded) on Stalled, and what failed on Ready.
            String gaveUp = describeCondition(stalled);
            String cause = ready != null && !isReady ? describeCondition(ready) : "";
            return new Readiness(false, true,
                    cause.isBlank() || cause.equals(gaveUp) ? gaveUp : cause + " (" + gaveUp + ")", true);
        }
        if (ready == null) {
            return new Readiness(false, false, "Flux has not reconciled the latest change of the HelmRelease yet", true);
        }
        String state = describeCondition(ready);
        return new Readiness(isReady, false, state.isBlank() ? "Flux has not reported the state of the release yet"
                : state, true);
    }

    /**
     * The HelmReleases that hold a HelmRelease back: those its {@code spec.dependsOn} names, when Flux reports it not
     * Ready because one of them is not (DependencyNotReady). Flux then neither installs nor upgrades it, and never marks
     * it Stalled, so a dependency Flux gave up on is seen only on the dependency itself.
     *
     * @param helmRelease the HelmRelease on the cluster, {@code null} when it is not there
     * @return the HelmReleases it depends on, a dependency without a namespace in its own; empty when Flux does not
     *         report it waiting for them
     */
    public static List<NamespacedName> awaitedDependencies(GenericKubernetesResource helmRelease) {
        if (helmRelease == null) {
            return List.of();
        }
        io.fabric8.kubernetes.api.model.ObjectMeta metadata = helmRelease.getMetadata();
        long generation = metadata == null || metadata.getGeneration() == null ? 0L : metadata.getGeneration();
        Map<?, ?> status = helmRelease.get("status") instanceof Map<?, ?> s ? s : Map.of();
        List<Map<?, ?>> conditions = new ArrayList<>();
        if (status.get("conditions") instanceof List<?> listed) {
            for (Object c : listed) {
                if (c instanceof Map<?, ?> m) conditions.add(m);
            }
        }
        Map<?, ?> ready = currentCondition(conditions, "Ready", generation, asLong(status.get("observedGeneration")));
        if (ready == null || isTrue(ready) || !"DependencyNotReady".equals(Objects.toString(ready.get("reason"), ""))) {
            return List.of();
        }
        String ownNamespace = metadata == null ? null : metadata.getNamespace();
        List<NamespacedName> dependencies = new ArrayList<>();
        Map<?, ?> spec = helmRelease.get("spec") instanceof Map<?, ?> s ? s : Map.of();
        if (spec.get("dependsOn") instanceof List<?> dependsOn) {
            for (Object entry : dependsOn) {
                if (!(entry instanceof Map<?, ?> dependency) || dependency.get("name") == null) continue;
                String namespace = Objects.toString(dependency.get("namespace"), "");
                dependencies.add(new NamespacedName(namespace.isBlank() ? ownNamespace : namespace,
                        String.valueOf(dependency.get("name"))));
            }
        }
        return dependencies;
    }

    /**
     * The condition of the given type when Flux set it for the HelmRelease's latest change, else {@code null}. Flux
     * records on each condition the generation it set it for, while {@code status.observedGeneration} only moves once
     * the release is Ready or Stalled: it says nothing of a release Flux is still installing or retrying, and is read
     * only for a condition that records none.
     */
    private static Map<?, ?> currentCondition(List<Map<?, ?>> conditions, String type, long generation,
                                              long observedGeneration) {
        Map<?, ?> condition = conditions.stream()
                .filter(c -> type.equalsIgnoreCase(Objects.toString(c.get("type"), "")))
                .findFirst().orElse(null);
        if (condition == null) {
            return null;
        }
        long setFor = condition.get("observedGeneration") != null
                ? asLong(condition.get("observedGeneration")) : observedGeneration;
        return setFor >= generation ? condition : null;
    }

    private static boolean isTrue(Map<?, ?> condition) {
        return "True".equalsIgnoreCase(Objects.toString(condition.get("status"), ""));
    }

    /** A condition as Flux words it: "reason: message". */
    private static String describeCondition(Map<?, ?> condition) {
        String reason = Objects.toString(condition.get("reason"), "");
        String message = Objects.toString(condition.get("message"), "");
        if (reason.isBlank()) return message;
        return message.isBlank() ? reason : reason + ": " + message;
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        try {
            return value == null ? -1L : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    static void evaluateGenerationStaleness(HelmReleaseDTO dto, long desired, long observed) {
        if (desired >= 0 && observed >= 0 && observed < desired) {
            dto.staleGeneration = true;
            if (dto.status == null || dto.status.isBlank() || "UNKNOWN".equalsIgnoreCase(dto.status)) {
                dto.status = "PROGRESSING";
            }
            dto.message = appendMessage(dto.message, "Waiting for generation " + desired + " (observed " + observed + ")");
        }
    }

    /**
     * Evaluate HelmRepository conditions to expose source health.
     */
    static void evaluateSourceConditions(HelmReleaseDTO dto, java.util.List<java.util.Map<String, Object>> conds) {
        dto.sourceConditions = mapConditions(conds);
        java.util.Map<String, Object> ready = conds.stream()
                .filter(c -> "Ready".equalsIgnoreCase(Objects.toString(c.get("type"), "")))
                .findFirst().orElse(null);
        if (ready != null) {
            dto.sourceStatus = Objects.toString(ready.get("status"), "Unknown");
            String msg = Objects.toString(ready.get("message"), "");
            String reason = Objects.toString(ready.get("reason"), "");
            dto.sourceMessage = (reason.isBlank() ? "" : reason + ": ") + msg;
        }
        if (dto.sourceStatus == null || dto.sourceStatus.isBlank()) {
            dto.sourceStatus = "Unknown";
        }
        if (dto.reconcileMessage == null && dto.sourceMessage != null) {
            dto.reconcileMessage = dto.sourceMessage;
        }
        StringBuilder summary = new StringBuilder();
        for (java.util.Map<String, Object> c : conds) {
            String type = Objects.toString(c.get("type"), "");
            String st = Objects.toString(c.get("status"), "");
            String reason = Objects.toString(c.get("reason"), "");
            if (summary.length() > 0) {
                summary.append(" | ");
            }
            summary.append(type).append("=").append(st);
            if (!reason.isBlank()) {
                summary.append(" (").append(reason).append(")");
            }
        }
        dto.sourceConditionSummary = summary.length() > 0 ? summary.toString() : null;
    }

    /**
     * Delete a HelmRelease from the cluster (best effort).
     */
    private void deleteHelmRelease(String namespace, String releaseName) {
        if (kubernetesService == null || kubernetesService.getClient() == null) {
            return;
        }
        try {
            CustomResourceDefinitionContext ctx = new CustomResourceDefinitionContext.Builder()
                    .withGroup("helm.toolkit.fluxcd.io")
                    .withVersion("v2beta2")
                    .withScope("Namespaced")
                    .withPlural("helmreleases")
                    .build();
            kubernetesService.getClient()
                    .genericKubernetesResources(ctx)
                    .inNamespace(namespace)
                    .withName(releaseName)
                    .delete();
            LOG.info("Deleted HelmRelease {}/{} from cluster", namespace, releaseName);
        } catch (Exception ex) {
            LOG.debug("Failed to delete HelmRelease {}/{}: {}", namespace, releaseName, ex.toString());
        }
    }

    /**
     * Delete a HelmRepository from the flux-system namespace (best effort).
     */
    private void deleteHelmRepository(String repoName) {
        if (kubernetesService == null || kubernetesService.getClient() == null) {
            return;
        }
        try {
            CustomResourceDefinitionContext ctx = new CustomResourceDefinitionContext.Builder()
                    .withGroup("source.toolkit.fluxcd.io")
                    .withVersion("v1beta2")
                    .withScope("Namespaced")
                    .withPlural("helmrepositories")
                    .build();
            kubernetesService.getClient()
                    .genericKubernetesResources(ctx)
                    .inNamespace("flux-system")
                    .withName(repoName)
                    .delete();
            LOG.info("Deleted HelmRepository {} from cluster", repoName);
        } catch (Exception ex) {
            LOG.debug("Failed to delete HelmRepository {}: {}", repoName, ex.toString());
        }
    }

    /**
     * Execute a git operation with a tiny retry window to absorb transient network hiccups. A refused push, refused
     * credentials and a timeout fail at once ({@link GitClient#isRetryable}): trying again gets the same answer, or
     * waits the whole timeout again while the release's workspace stays locked.
     *
     * @param op    git callable to run
     * @param label human-readable label for logging
     */
    private <T> T withGitRetry(Callable<T> op, String label) throws Exception {
        IOException lastIo = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                return op.call();
            } catch (IOException io) {
                lastIo = io;
                if (!GitClient.isRetryable(io)) {
                    LOG.warn("Git operation {} failed: {}", label, io.getMessage());
                    throw io;
                }
                LOG.warn("Git operation {} failed (attempt {}/{}): {}", label, attempt, MAX_RETRY_ATTEMPTS, io.getMessage());
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    throw io;
                }
                // Exponential backoff: base delay * attempt number
                long backoffDelayMs = RETRY_BASE_DELAY_MS * attempt;
                Thread.sleep(backoffDelayMs);
            }
        }
        if (lastIo != null) {
            throw lastIo;
        }
        return op.call();
    }

    private HttpResponse<String> sendHttpWithRetry(HttpRequest request, String label) throws InterruptedException, IOException {
        IOException lastIo = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 500 && attempt < MAX_RETRY_ATTEMPTS) {
                    lastIo = new IOException("HTTP " + response.statusCode());
                    logFluxWarn(null, label, "http", "Request returned %d, retrying", response.statusCode());
                    Thread.sleep(HTTP_RETRY_BASE_DELAY_MS * attempt);
                    continue;
                }
                return response;
            } catch (SocketTimeoutException toe) {
                lastIo = toe;
                logFluxWarn(null, label, "http-timeout", "Timeout on attempt %d: %s", attempt, toe.getMessage());
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    throw toe;
                }
                Thread.sleep(HTTP_RETRY_BASE_DELAY_MS * attempt);
            } catch (IOException io) {
                lastIo = io;
                logFluxWarn(null, label, "http", "HTTP request failed on attempt %d: %s", attempt, io.getMessage());
                if (attempt >= 3) {
                    throw io;
                }
                Thread.sleep(250L * attempt);
            }
        }
        if (lastIo != null) {
            throw lastIo;
        }
        throw new IOException("Failed to execute HTTP request for " + label);
    }

    /* ------------------------ Security + overrides helpers ------------------------ */

    /**
     * The security profile a deploy without a command plan applies: the one it names, else the default profile (or
     * the first one). A named profile is never swapped for another.
     *
     * @return the profile name, or null when the deploy names none and none is stored
     * @throws IllegalArgumentException when the named profile does not exist
     * @throws IllegalStateException    when the profiles cannot be read
     */
    private String resolveSecurityProfileName(String requestedProfile) {
        if (requestedProfile != null && !requestedProfile.isBlank()) {
            securityProfileService.requireProfile(requestedProfile);
            return requestedProfile;
        }
        var profiles = securityProfileService.loadProfiles();
        if (profiles == null || profiles.profiles == null || profiles.profiles.isEmpty()) {
            return null;
        }
        if (profiles.defaultProfile != null && profiles.profiles.containsKey(profiles.defaultProfile)) {
            return profiles.defaultProfile;
        }
        return profiles.profiles.keySet().stream().findFirst().orElse(null);
    }

    /**
     * The settings of the profile {@link #resolveSecurityProfileName} chose.
     *
     * @return the named profile, or null when none is named
     * @throws IllegalArgumentException when the named profile no longer exists
     * @throws IllegalStateException    when the profiles cannot be read
     */
    private SecurityConfigDTO loadSecurityConfig(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return null;
        }
        return securityProfileService.requireProfile(profileName);
    }

    private void applySecurityOverrides(SecurityConfigDTO cfg, Map<String, String> overrides) {
        if (cfg == null || cfg.mode == null || cfg.mode.isBlank()) {
            return;
        }
        SecurityMappingService mappingService = new SecurityMappingService(viewContext);
        Map<String, String> modeMapping = mappingService.mappingForMode(cfg.mode.toLowerCase());
        Map<String, String> tlsMapping = mappingService.mappingForMode("tls");
        @SuppressWarnings("unchecked")
        Map<String, Object> asMap = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(cfg, Map.class);

        // Auth-mode specific mappings
        for (Map.Entry<String, String> entry : modeMapping.entrySet()) {
            Object val = getByPath(asMap, entry.getKey());
            if (val == null) continue;
            String strVal = String.valueOf(val);
            if (strVal.isBlank()) continue;
            addOverride(overrides, entry.getValue(),
                    org.apache.ambari.view.k8s.service.HelmSetValues.textIfSecret(entry.getKey(), strVal));
        }
        // TLS mappings
        for (Map.Entry<String, String> entry : tlsMapping.entrySet()) {
            Object val = getByPath(asMap, entry.getKey());
            if (val == null) continue;
            String strVal = String.valueOf(val);
            if (strVal.isBlank()) continue;
            addOverride(overrides, entry.getValue(),
                    org.apache.ambari.view.k8s.service.HelmSetValues.textIfSecret(entry.getKey(), strVal));
        }
        // Extra properties
        if (cfg.extraProperties != null) {
            cfg.extraProperties.forEach((k, v) -> {
                if (k != null && v != null && !k.isBlank() && !String.valueOf(v).isBlank()) {
                    addOverride(overrides, k, String.valueOf(v));
                }
            });
        }
    }

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

    private void addOverride(Map<String, String> overrides, String helmPath, String value) {
        if (helmPath == null || helmPath.isBlank() || value == null) return;
        overrides.put(helmPath, value);
    }

    /**
     * Resolve dynamic tokens from Ambari configs, similar to CommandService.resolveDynamicOverrides.
     * Looks for _dynamicTokens in the values map: list of strings "SOURCE:helm.path".
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> resolveDynamicTokens(Map<String, Object> values, String release) {
        if (values == null) return null;
        Object tokensObj = values.get("_dynamicTokens");
        if (!(tokensObj instanceof List)) return null;

        List<String> tokens = new ArrayList<>();
        for (Object o : (List<?>) tokensObj) {
            if (o != null) {
                String s = String.valueOf(o).trim();
                if (!s.isBlank()) tokens.add(s);
            }
        }
        if (tokens.isEmpty()) return null;

        Map<String, String> resolved = new LinkedHashMap<>();
        try {
            String cluster = viewContext.getAmbariProperty("cluster.name");
            String ambariUrl = viewContext.getAmbariProperty("ambari.server.url");
            Map<String, String> callerHeaders = Map.of();
            AmbariActionClient ambariActionClient = new AmbariActionClient(viewContext, ambariUrl, cluster, callerHeaders);

            for (String token : tokens) {
                String[] parts = token.split(":", 2);
                if (parts.length != 2) {
                    LOG.warn("Invalid dynamic token '{}', expected 'SOURCE:helm.path'", token);
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
            LOG.warn("Failed to resolve dynamic overrides for release {}: {}", release, ex.toString());
        }
        return resolved.isEmpty() ? null : resolved;
    }

    /**
     * Apply dotted-path overrides into the values map, creating intermediate maps/arrays as needed.
     * @param values target values map (mutated)
     * @param overrides helm-style dotted path -> string value
     */
    @SuppressWarnings("unchecked")
    static void applyOverridesToValues(Map<String, Object> values, Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) return;
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            String path = e.getKey();
            String val = e.getValue();
            if (path == null || path.isBlank()) continue;
            setAtPath(values, path, parseScalar(val));
        }
    }

    /**
     * Types an override value the way {@code helm --set} does, like direct deploys ({@code HelmSetValues}). Left as
     * text, "false" would be truthy in the chart templates.
     */
    static Object parseScalar(String raw) {
        return org.apache.ambari.view.k8s.service.HelmSetValues.typed(raw);
    }

    /** Match a path segment with an optional list-index suffix, e.g. {@code customCAs[0]}. */
    private static final java.util.regex.Pattern LIST_SEGMENT =
            java.util.regex.Pattern.compile("^(?<key>[^\\[\\]]+)(\\[(?<idx>\\d+)])?$");

    /**
     * Set a value at a dotted path, creating nested maps and lists as needed.
     *
     * <p>Supports Helm-style list-index syntax in each segment, e.g.
     * {@code certificates.customCAs[0].secret} writes
     * {@code certificates: { customCAs: [ { secret: ... } ] }} — not a literal
     * key named {@code customCAs[0]}. This mirrors {@code HelmService.applyOverrides};
     * keeping the two implementations in sync matters because both code paths feed
     * the same chart and disagreement produces silently-wrong YAML.
     */
    @SuppressWarnings("unchecked")
    static void setAtPath(Map<String, Object> root, String dottedPath, Object value) {
        String[] parts = dottedPath.split("\\.");
        Object cursor = root;
        for (int i = 0; i < parts.length; i++) {
            boolean last = (i == parts.length - 1);
            java.util.regex.Matcher m = LIST_SEGMENT.matcher(parts[i]);
            if (!m.matches()) continue;

            String key = m.group("key");
            String idxStr = m.group("idx");

            if (!(cursor instanceof Map)) {
                // Defensive: caller guarantees we start from a Map; this can only happen
                // if a prior segment was a list-index leading nowhere. Reset to a fresh map.
                cursor = new LinkedHashMap<String, Object>();
            }
            Map<String, Object> map = (Map<String, Object>) cursor;

            if (idxStr == null) {
                if (last) {
                    map.put(key, value);
                } else {
                    Object next = map.get(key);
                    if (!(next instanceof Map) && !(next instanceof List)) {
                        next = new LinkedHashMap<String, Object>();
                        map.put(key, next);
                    }
                    cursor = next;
                }
            } else {
                int idx = Integer.parseInt(idxStr);
                Object next = map.get(key);
                if (!(next instanceof List)) {
                    next = new ArrayList<>();
                    map.put(key, next);
                }
                List<Object> list = (List<Object>) next;
                while (list.size() <= idx) list.add(null);
                if (last) {
                    list.set(idx, value);
                } else {
                    Object elem = list.get(idx);
                    if (!(elem instanceof Map)) {
                        elem = new LinkedHashMap<String, Object>();
                        list.set(idx, elem);
                    }
                    cursor = elem;
                }
            }
        }
    }

    /* ------------------------ Secret values helpers ------------------------ */

    /** Label and value marking the objects this view creates (the same as on its other Secrets). */
    static final String MANAGED_BY_LABEL = "managed-by";
    static final String MANAGED_BY_VALUE = "ambari-k8s-view";

    /** A namespaced object, by namespace and name. */
    public record NamespacedName(String namespace, String name) {}

    /** A dependency HelmRelease, rendered once the passwords and secrets of the deploy are known. */
    private record PendingDependency(String releaseName, String namespace, String chart, String version,
                                     String repoName, Map<String, Object> values, Path file) {}

    /** Label naming the HelmRelease a values Secret belongs to. */
    static final String RELEASE_LABEL = "ambari.clemlab.com/release";

    /**
     * The values Secret of one HelmRelease for this deploy.
     *
     * @param name     the Secret the HelmRelease reads
     * @param yaml     the values document to store in it
     * @param previous what the Secret held before this deploy; empty when it did not exist
     */
    private record ValuesSecret(String namespace, String helmRelease, String name, String yaml, Optional<String> previous) {
        boolean unchanged() {
            return previous.map(yaml::equals).orElse(false);
        }
    }

    /**
     * The values Secrets of a HelmRelease before this deploy.
     *
     * @param referenced the values Secret its manifest in the workspace reads, or null
     * @param payloads   the values document of each Secret read, by name; empty when the Secret does not exist
     * @param documents  those documents read as values
     */
    private record StoredSecrets(String referenced, Map<String, Optional<String>> payloads,
                                 List<Map<String, Object>> documents) {
        Optional<String> payload(String name) {
            return payloads.getOrDefault(name, Optional.empty());
        }
    }

    /**
     * Records the password fields of the service's form and stack configurations, and the values its bindings copy
     * those form fields into. A chart deployed without a service definition has none.
     *
     * @param values the release's values with every override applied: a redeploy that re-submits the release's
     *               values carries the stack configuration overrides there only
     */
    private void addServicePasswords(GitOpsSecretValues secrets, String serviceKey, HelmDeployRequest request,
                                     Map<String, Object> values) {
        if (serviceKey == null || serviceKey.isBlank()) {
            return;
        }
        try {
            StackServiceDef definition = stackDefinitionService.getServiceDefinition(serviceKey);
            secrets.addPasswordFields(definition.form, formFields(request));
            secrets.addPasswordCopies(definition.form, definition.variables, definition.bindings);
        } catch (Exception ex) {
            LOG.warn("Could not read the form of service {}: its password fields are not known: {}", serviceKey, ex.toString());
        }
        try {
            List<StackConfig> configs = stackDefinitionService.getServiceConfigurations(serviceKey);
            secrets.addPasswordConfigs(configs, request.getStackConfigOverrides());
            secrets.addPasswordConfigs(configs, GitOpsSecretValues.configOverridesIn(values));
        } catch (Exception ex) {
            LOG.warn("Could not read the configurations of service {}: their password properties are not known: {}",
                    serviceKey, ex.toString());
        }
    }

    /**
     * The service form as filled in. The wizard sends its TLS and Kerberos blocks in request fields of their own, not
     * in the form values, yet they hold form fields too, such as the HTTPS keystore password.
     */
    static Map<String, Object> formFields(HelmDeployRequest request) {
        Map<String, Object> fields = request.getFormValues() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getFormValues());
        if (request.getTls() != null) {
            fields.putIfAbsent("tls", request.getTls());
        }
        if (request.getKerberos() != null) {
            fields.putIfAbsent("kerberos", request.getKerberos());
        }
        return fields;
    }

    /**
     * The values Secret a HelmRelease reads for this deploy. A direct commit to the branch Flux reconciles uses the
     * HelmRelease's own Secret. Any other change (a pull request, a working branch still to merge) must not change the
     * running release before it is merged, yet Flux applies a change of the Secret the release reads at once: a
     * changed document goes to a new Secret that only the committed HelmRelease reads.
     *
     * @param appliedOnCommit whether the commit lands on the branch Flux reconciles, in direct-commit mode
     */
    private ValuesSecret valuesSecret(String namespace, String helmRelease, Map<String, Object> values,
                                      StoredSecrets stored, boolean appliedOnCommit) throws IOException {
        String yaml = valuesYaml(values);
        String name;
        if (appliedOnCommit) {
            name = GitOpsSecretValues.secretName(helmRelease);
        } else if (stored.referenced() != null && stored.payload(stored.referenced()).map(yaml::equals).orElse(false)) {
            name = stored.referenced();
        } else {
            name = GitOpsSecretValues.changeSecretName(helmRelease);
        }
        return new ValuesSecret(namespace, helmRelease, name, yaml, stored.payload(name));
    }

    /**
     * The values Secrets of a HelmRelease before this deploy: the one named after it and the one its manifest in the
     * workspace reads.
     *
     * @param manifest the HelmRelease manifest in the workspace, which may not exist yet
     * @throws IllegalStateException when a Secret cannot be read while the manifest reads one: what it holds would no
     *                               longer be known as secret
     */
    private StoredSecrets storedSecrets(String namespace, String helmRelease, Path manifest) {
        String referenced = referencedValuesSecret(manifest, helmRelease);
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        names.add(GitOpsSecretValues.secretName(helmRelease));
        if (referenced != null) {
            names.add(referenced);
        }
        Map<String, Optional<String>> payloads = new LinkedHashMap<>();
        List<Map<String, Object>> documents = new ArrayList<>();
        for (String name : names) {
            try {
                Optional<String> yaml = kubernetesService.readOpaqueSecretKeyAsString(namespace, name,
                        GitOpsSecretValues.VALUES_KEY);
                yaml = yaml == null ? Optional.empty() : yaml;
                payloads.put(name, yaml);
                if (yaml.isPresent()) {
                    documents.add(GitOpsSecretValues.readValuesYaml(yaml.get()));
                }
            } catch (Exception ex) {
                if (referenced == null) {
                    // The manifest reads no values Secret, so nothing such a Secret holds ever reached the release's
                    // values: there is no secret to lose. A dependency in a namespace the view may not read Secrets in
                    // (KEDA, monitoring) deploys as it did before.
                    LOG.warn("Could not read Secret {}/{}; HelmRelease {} reads no values Secret, so none is taken "
                            + "into account: {}", namespace, name, helmRelease, ex.toString());
                    continue;
                }
                throw new IllegalStateException("GitOps deploy of " + namespace + "/" + helmRelease + " stopped: "
                        + "could not read the Kubernetes Secret " + namespace + "/" + name + " that holds its passwords "
                        + "and secrets, so nothing was committed to Git: " + ex.getMessage(), ex);
            }
        }
        return new StoredSecrets(referenced, payloads, documents);
    }

    /**
     * The first file written or changed in the Git workspace for this deploy that contains a password or secret,
     * or {@code null}.
     *
     * @param repoDir      the workspace
     * @param writtenFiles the files this deploy wrote, relative to the workspace (also those left unchanged)
     */
    static String fileLeakingSecrets(Path repoDir, GitClient gitClient, List<Path> writtenFiles,
                                     GitOpsSecretValues secrets) throws IOException {
        if (secrets.isEmpty()) {
            return null;
        }
        java.util.Set<Path> files = new java.util.LinkedHashSet<>();
        writtenFiles.forEach(f -> files.add(f.normalize()));
        gitClient.changedFiles().forEach(f -> files.add(f.normalize()));
        for (Path file : files) {
            Path absolute = repoDir.resolve(file);
            if (Files.isRegularFile(absolute)
                    && secrets.leaksInto(new String(Files.readAllBytes(absolute), java.nio.charset.StandardCharsets.UTF_8))) {
                return file.toString();
            }
        }
        return null;
    }

    /**
     * Creates or updates the values Secrets whose document changed: managed by this view, labelled with the
     * HelmRelease, and watched by Flux so a change is applied without a commit. When one cannot be written, those
     * already written are put back.
     *
     * @return the Secrets written
     * @throws IllegalStateException when a Secret cannot be written; nothing is committed then
     */
    private List<ValuesSecret> writeValuesSecrets(List<ValuesSecret> valuesSecrets) {
        List<ValuesSecret> written = new ArrayList<>();
        for (ValuesSecret valuesSecret : valuesSecrets) {
            if (valuesSecret.unchanged()) {
                // Written again, it would only make Flux upgrade the release for nothing.
                continue;
            }
            try {
                putValuesSecret(valuesSecret.namespace(), valuesSecret.helmRelease(), valuesSecret.name(), valuesSecret.yaml());
                written.add(valuesSecret);
                logFluxInfo(valuesSecret.namespace(), valuesSecret.helmRelease(), "secrets",
                        "Stored the passwords and secrets in Secret %s", valuesSecret.name());
            } catch (Exception ex) {
                restoreValuesSecrets(written);
                throw new IllegalStateException("Could not store the passwords and secrets of "
                        + valuesSecret.namespace() + "/" + valuesSecret.helmRelease() + " in the Kubernetes Secret "
                        + valuesSecret.namespace() + "/" + valuesSecret.name() + ", so nothing was committed to Git: "
                        + ex.getMessage(), ex);
            }
        }
        return written;
    }

    private void putValuesSecret(String namespace, String helmRelease, String name, String yaml) {
        Map<String, String> labels = Map.of(
                MANAGED_BY_LABEL, MANAGED_BY_VALUE,
                "app.kubernetes.io/managed-by", "kdps",
                RELEASE_LABEL, helmRelease,
                "reconcile.fluxcd.io/watch", "Enabled");
        kubernetesService.createOrUpdateOpaqueSecret(namespace, name, GitOpsSecretValues.VALUES_KEY,
                yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8), labels, null, null);
    }

    /**
     * Puts the values Secrets written by a deploy that then failed back as they were: their previous document, or
     * deleted when they did not exist before. Best effort.
     */
    private void restoreValuesSecrets(List<ValuesSecret> written) {
        for (ValuesSecret valuesSecret : written) {
            try {
                if (valuesSecret.previous().isPresent()) {
                    putValuesSecret(valuesSecret.namespace(), valuesSecret.helmRelease(), valuesSecret.name(),
                            valuesSecret.previous().get());
                } else {
                    kubernetesService.deleteSecret(valuesSecret.namespace(), valuesSecret.name());
                }
                logFluxInfo(valuesSecret.namespace(), valuesSecret.helmRelease(), "secrets",
                        "Put Secret %s back as it was before the failed deploy", valuesSecret.name());
            } catch (Exception ex) {
                logFluxWarn(valuesSecret.namespace(), valuesSecret.helmRelease(), "secrets",
                        "Could not put Secret %s back as it was before the failed deploy: %s", valuesSecret.name(),
                        ex.toString());
            }
        }
    }

    /**
     * Undoes what a deploy did ahead of Git when its commit or push failed: the local commit, then the values Secrets
     * it wrote. Best effort.
     *
     * @param headBeforeCommit the commit the workspace was on before this deploy committed
     */
    private void undoUnpublished(GitClient gitClient, String headBeforeCommit, List<ValuesSecret> written) {
        try {
            gitClient.resetTo(headBeforeCommit);
        } catch (Exception ex) {
            LOG.warn("Could not drop the unpublished commit from the workspace: {}", ex.toString());
        }
        restoreValuesSecrets(written);
    }

    /**
     * Asks Flux to read the values Secrets again for the HelmReleases already on the cluster. Best effort: the Secrets'
     * watch label triggers the same reconcile on recent Flux versions.
     */
    private void requestReconcile(List<ValuesSecret> written) {
        for (ValuesSecret valuesSecret : written) {
            try {
                if (!kubernetesService.requestFluxReconcile(valuesSecret.namespace(), valuesSecret.helmRelease())) {
                    LOG.info("HelmRelease {}/{} is not on the cluster yet: Flux reads its values Secret when it applies it",
                            valuesSecret.namespace(), valuesSecret.helmRelease());
                }
            } catch (Exception ex) {
                LOG.warn("Could not ask Flux to reconcile HelmRelease {}/{}: {}", valuesSecret.namespace(),
                        valuesSecret.helmRelease(), ex.toString());
            }
        }
    }

    /**
     * The values Secret a HelmRelease manifest reads among the HelmRelease's own ({@code <name>-kdps-values} or one of
     * a proposed change), or null when the manifest does not exist or reads none.
     */
    static String referencedValuesSecret(Path manifest, String helmRelease) {
        for (Map<?, ?> doc : helmReleaseDocuments(manifest)) {
            Object metadata = doc.get("metadata");
            if (metadata instanceof Map<?, ?> meta && helmRelease.equals(String.valueOf(meta.get("name")))) {
                List<String> own = ownValuesSecrets(doc, helmRelease);
                if (!own.isEmpty()) {
                    return own.get(0);
                }
            }
        }
        return null;
    }

    /**
     * The manifests a deploy writes into a release's directory that are there: its {@code helmrelease.yaml} and
     * {@code helmrepository.yaml}, and the {@code <dependency>-helmrelease.yaml} and
     * {@code <repository>-helmrepository.yaml} files of its {@code dependencies} directory. Nothing else in the
     * directory, and nothing in a directory below it, which may be another release's or hold other content of the
     * repository.
     *
     * @param dir the release's directory in the Git workspace
     * @throws IOException when its dependencies directory cannot be listed
     */
    static List<Path> releaseManifests(Path dir) throws IOException {
        List<Path> found = new ArrayList<>();
        for (String name : List.of(HELM_RELEASE_FILE, HELM_REPOSITORY_FILE)) {
            if (Files.isRegularFile(dir.resolve(name))) {
                found.add(dir.resolve(name));
            }
        }
        Path dependencies = dir.resolve(DEPENDENCIES_DIR);
        if (Files.isDirectory(dependencies)) {
            try (java.util.stream.Stream<Path> files = Files.list(dependencies)) {
                files.filter(Files::isRegularFile)
                        .filter(file -> {
                            String name = file.getFileName().toString();
                            return name.endsWith("-" + HELM_RELEASE_FILE) || name.endsWith("-" + HELM_REPOSITORY_FILE);
                        })
                        .sorted()
                        .forEach(found::add);
            }
        }
        return found;
    }

    /** The HelmReleases of manifest files of the Git workspace, by namespace and name. */
    static java.util.Set<NamespacedName> helmReleasesIn(List<Path> files) {
        java.util.Set<NamespacedName> found = new java.util.LinkedHashSet<>();
        for (Path file : files) {
            for (Map<?, ?> doc : helmReleaseDocuments(file)) {
                Map<?, ?> metadata = (Map<?, ?>) doc.get("metadata");
                found.add(new NamespacedName(String.valueOf(metadata.get("namespace")), String.valueOf(metadata.get("name"))));
            }
        }
        return found;
    }

    /**
     * The values Secrets of the HelmReleases of manifest files of the Git workspace: the one named after each
     * HelmRelease, and the HelmRelease's other values Secrets its {@code valuesFrom} reads.
     */
    static java.util.Set<NamespacedName> valuesSecretsIn(List<Path> files) {
        java.util.Set<NamespacedName> found = new java.util.LinkedHashSet<>();
        for (Path file : files) {
            for (Map<?, ?> doc : helmReleaseDocuments(file)) {
                Map<?, ?> metadata = (Map<?, ?>) doc.get("metadata");
                String hrNamespace = String.valueOf(metadata.get("namespace"));
                String hrName = String.valueOf(metadata.get("name"));
                found.add(new NamespacedName(hrNamespace, GitOpsSecretValues.secretName(hrName)));
                ownValuesSecrets(doc, hrName).forEach(name -> found.add(new NamespacedName(hrNamespace, name)));
            }
        }
        return found;
    }

    /** The HelmRelease documents of a manifest file that name their namespace; none when it cannot be read. */
    private static List<Map<?, ?>> helmReleaseDocuments(Path file) {
        List<Map<?, ?>> found = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return found;
        }
        com.fasterxml.jackson.databind.ObjectMapper yaml =
                new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        try (com.fasterxml.jackson.databind.MappingIterator<Object> docs = yaml.readerFor(Map.class).readValues(file.toFile())) {
            while (docs.hasNext()) {
                if (docs.next() instanceof Map<?, ?> doc && "HelmRelease".equals(doc.get("kind"))
                        && doc.get("metadata") instanceof Map<?, ?> metadata
                        && metadata.get("name") != null && metadata.get("namespace") != null) {
                    found.add(doc);
                }
            }
        } catch (Exception ex) {
            LOG.warn("Could not read the HelmReleases of {}: {}", file, ex.toString());
        }
        return found;
    }

    /** The Secrets a HelmRelease document reads through {@code valuesFrom} that are values Secrets of its own. */
    private static List<String> ownValuesSecrets(Map<?, ?> helmRelease, String name) {
        List<String> own = new ArrayList<>();
        Object valuesFrom = helmRelease.get("spec") instanceof Map<?, ?> spec ? spec.get("valuesFrom") : null;
        if (valuesFrom instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> ref && "Secret".equals(ref.get("kind")) && ref.get("name") != null
                        && GitOpsSecretValues.isValuesSecretOf(name, String.valueOf(ref.get("name")))) {
                    own.add(String.valueOf(ref.get("name")));
                }
            }
        }
        return own;
    }

    /**
     * Deletes the values Secrets this view created for the removed HelmReleases: those named in their manifests, their
     * own, and those of changes proposed for them that no manifest names any more (a pull request closed unmerged, a
     * Secret replaced by a later change). A Secret without this view's label is left alone.
     */
    private void deleteValuesSecrets(java.util.Set<NamespacedName> referenced, java.util.Set<NamespacedName> helmReleases) {
        if (kubernetesService == null) {
            return;
        }
        java.util.Set<NamespacedName> secrets = new java.util.LinkedHashSet<>(referenced);
        for (NamespacedName helmRelease : helmReleases) {
            secrets.add(new NamespacedName(helmRelease.namespace(), GitOpsSecretValues.secretName(helmRelease.name())));
            try {
                kubernetesService.listSecretNames(helmRelease.namespace(),
                                Map.of(MANAGED_BY_LABEL, MANAGED_BY_VALUE, RELEASE_LABEL, helmRelease.name())).stream()
                        .filter(name -> GitOpsSecretValues.isValuesSecretOf(helmRelease.name(), name))
                        .forEach(name -> secrets.add(new NamespacedName(helmRelease.namespace(), name)));
            } catch (Exception ex) {
                LOG.warn("Could not list the values Secrets of HelmRelease {}/{}: {}", helmRelease.namespace(),
                        helmRelease.name(), ex.toString());
            }
        }
        for (NamespacedName ref : secrets) {
            try {
                io.fabric8.kubernetes.api.model.Secret secret = kubernetesService.getSecret(ref.namespace(), ref.name());
                if (secret == null) {
                    continue;
                }
                Map<String, String> labels = secret.getMetadata() == null ? null : secret.getMetadata().getLabels();
                if (labels == null || !MANAGED_BY_VALUE.equals(labels.get(MANAGED_BY_LABEL))) {
                    LOG.warn("Secret {}/{} was not created by this view; left in place", ref.namespace(), ref.name());
                    continue;
                }
                kubernetesService.deleteSecret(ref.namespace(), ref.name());
                LOG.info("Deleted the values Secret {}/{}", ref.namespace(), ref.name());
            } catch (Exception ex) {
                LOG.warn("Could not delete the values Secret {}/{}: {}", ref.namespace(), ref.name(), ex.toString());
            }
        }
    }

    /* ------------------------ DependsOn helper ------------------------ */
    /** Lightweight holder for dependency references in Flux HelmReleases. */
    record DepRef(String name, String namespace) {}

    private void logFluxInfo(String namespace, String release, String phase, String message, Object... args) {
        String context = buildContext(namespace, release);
        String formatted = (args == null || args.length == 0) ? message : String.format(message, args);
        LOG.info("[FluxGitOps:{}][{}] {}", context, phase, formatted);
    }

    private void logFluxWarn(String namespace, String release, String phase, String message, Object... args) {
        String context = buildContext(namespace, release);
        String formatted = (args == null || args.length == 0) ? message : String.format(message, args);
        LOG.warn("[FluxGitOps:{}][{}] {}", context, phase, formatted);
    }

    private String buildContext(String namespace, String release) {
        String ns = (namespace == null || namespace.isBlank()) ? "-" : namespace;
        String rel = (release == null || release.isBlank()) ? "-" : release;
        return ns + "/" + rel;
    }

    /**
     * The repository as the pull request calls read it (over HTTPS, without the {@code .git} suffix), whose host gets
     * the access token; {@code null} when that is not the host the release's Git credential is bound to
     * ({@link GitCredentialService#hostOf}).
     */
    static java.net.URI pullRequestRepoUri(String repoUrl) {
        String host = GitCredentialService.hostOf(repoUrl);
        if (host == null || host.isEmpty()) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(normalizeRepoUrl(repoUrl));
            return host.equalsIgnoreCase(uri.getHost()) && uri.getPath() != null ? uri : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizeRepoUrl(String repoUrl) {
        if (repoUrl == null) return null;
        String trimmed = repoUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        // Only the suffix: a host may hold ".git" too (code.gitlab.example), and must not change.
        if (trimmed.endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - ".git".length());
        }
        if (trimmed.startsWith("git@")) {
            trimmed = "https://" + trimmed.substring(4).replace(":", "/");
        }
        return trimmed;
    }
}
