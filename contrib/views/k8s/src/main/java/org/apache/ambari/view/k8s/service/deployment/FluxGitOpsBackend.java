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

import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.service.helm.HelmClientDefault;
import org.apache.ambari.view.k8s.service.gitops.ChartFetcher;
import org.apache.ambari.view.k8s.service.gitops.GitOpsProfile;
import org.apache.ambari.view.k8s.service.gitops.GitOpsProfileStore;
import org.apache.ambari.view.k8s.service.gitops.ProfileRenderer;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.service.HelmRepositoryService;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.HelmReleaseDTO;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.GitCredentialService;
import org.apache.ambari.view.k8s.service.HelmService;
import org.apache.ambari.view.k8s.service.ImageRegistryOverrides;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.service.TrustBundleService;
import org.apache.ambari.view.k8s.service.SecurityProfileService;
import org.apache.ambari.view.k8s.service.SecurityMappingService;
import org.apache.ambari.view.k8s.service.CommandService;
import org.apache.ambari.view.k8s.service.DependencyRules;
import org.apache.ambari.view.k8s.service.FluxApiVersions;
import org.apache.ambari.view.k8s.service.ConfigResolutionService;
import org.apache.ambari.view.k8s.service.ContextService;
import org.apache.ambari.view.k8s.service.GlobalConfigService;
import org.apache.ambari.view.k8s.service.StackDefinitionService;
import org.apache.ambari.view.k8s.service.ProxySupport;
import org.apache.ambari.view.k8s.service.ViewConfigurationService;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.apache.ambari.view.k8s.utils.CommandUtils.AmbariConfigRef;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest.ConfigInstantiation;
import org.apache.ambari.view.k8s.model.stack.StackConfig;
import org.apache.ambari.view.k8s.model.stack.StackProperty;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flux GitOps backend: renders HelmRelease/HelmRepository YAML, commits to Git and returns the commit SHA.
 * The manifests are written by {@link FluxManifests}, from names {@link DeployRequestValidator} checked, into the
 * release's own directory of the repository. Passwords and secrets never reach Git: each HelmRelease reads them from
 * its own Secret through {@code spec.valuesFrom} (see {@link GitOpsSecretValues}). Deploys submitted to the view go
 * through {@link GitOpsPlanBackend}: the command plan of a direct deploy, whose GITOPS_COMMIT step calls
 * {@link #commit}.
 *
 * <p>Every change lands on the branch Flux reconciles (the base branch, recorded with the release): a direct commit is
 * pushed to it, and a pull request is opened into it ({@link GitPullRequests}) from a branch of its own, started from
 * the base branch for each change unless the pull request of the previous change is still open. An uninstall removes
 * the release's manifests the same way: by a direct commit, or by a pull request whose merge the release's record
 * waits for.</p>
 *
 * <p>Layout under the path prefix: each release's directory ({@code <prefix>/<namespace>/<release>}) holds its
 * HelmRelease and the HelmRepository it reads its chart from, named {@code <release>-<repository>} in the release's
 * namespace, so two releases never define the same object and none refers across namespaces. The dependencies several
 * releases share (KEDA, kube-prometheus-stack, the keytab webhook) are kept in {@link #SHARED_DIR}, with their own
 * chart sources in their namespaces and annotated so Flux never prunes them: no release's uninstall removes them. A
 * platform that keeps its chart sources in one namespace sets {@link #SOURCE_NAMESPACE_PROPERTY}; each chart repository
 * then has one HelmRepository there, in {@code <prefix>/_shared/sources/}, which no uninstall removes either. A chart
 * repository that needs credentials or trusts a private CA gets them from Secrets written next to its HelmRepository
 * through the Kubernetes API, never from Git.</p>
 */
public class FluxGitOpsBackend implements DeploymentBackend {
    private static final Logger LOG = LoggerFactory.getLogger(FluxGitOpsBackend.class);

    // Retry configuration constants
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MS = 500L;

    /** URL schemes JGit reaches a Git repository with over SSH. */
    private static final Set<String> SSH_SCHEMES = Set.of("ssh", "git+ssh", "ssh+git");

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

    // Flux API versions KDPS writes to Git — promoted from v2beta2/v1beta2 (deprecated in Flux 2.3, removed in Flux
    // 3.x). The cluster is read at the version it serves (FluxApiVersions).
    static final String FLUX_HR_API_VERSION = FluxApiVersions.Kind.HELM_RELEASE.group() + "/"
            + FluxApiVersions.Kind.HELM_RELEASE.current();
    static final String FLUX_SRC_API_VERSION = FluxApiVersions.Kind.HELM_REPOSITORY.group() + "/"
            + FluxApiVersions.Kind.HELM_REPOSITORY.current();

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

    /** What Flux accepts as a duration: one or more numbers each followed by ms, s, m or h. */
    private static final java.util.regex.Pattern FLUX_DURATION =
            java.util.regex.Pattern.compile("([0-9]+(\\.[0-9]+)?(ms|s|m|h))+");

    /** Time a deploy's wait for Flux allows besides the install attempts: Flux fetching the commit and the sources. */
    private static final int READY_MARGIN_SECONDS = 600;

    /** One number and its unit within a duration. */
    private static final java.util.regex.Pattern DURATION_PART =
            java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)");

    /**
     * The files a deploy writes into its release's directory: its HelmRelease and the HelmRepository of its chart. An
     * uninstall removes these files and nothing else. A file of {@link #SHARED_DIR} is never named
     * {@code helmrelease.yaml}, which marks a release's directory.
     */
    static final String HELM_RELEASE_FILE = "helmrelease.yaml";
    static final String HELM_REPOSITORY_FILE = "helmrepository.yaml";

    /**
     * The directory of a release where earlier versions wrote its dependencies, a {@code <dependency>-helmrelease.yaml}
     * per dependency and a {@code <repository>-helmrepository.yaml} per other chart repository. Moved to
     * {@link #SHARED_DIR} by the next deploy or uninstall of the release.
     */
    static final String DEPENDENCIES_DIR = "dependencies";

    /**
     * The directory, next to the namespaces under the path prefix, of what belongs to no release:
     * {@code <namespace>/<dependency>-helmrelease.yaml} and {@code <namespace>/<dependency>-helmrepository.yaml} per
     * dependency, and {@code sources/<namespace>/<repository>-helmrepository.yaml} per chart source kept in one
     * namespace. No uninstall removes anything there. A namespace cannot be named so: Kubernetes names start with a
     * letter or digit.
     */
    static final String SHARED_DIR = "_shared";
    static final String SOURCES_DIR = "sources";

    /**
     * View property: the namespace the platform keeps its Flux chart sources in, such as flux-system. When set, every
     * release reads its chart from one HelmRepository per chart repository there, kept in
     * {@code <prefix>/_shared/sources/}. When left empty, each release reads its chart from a HelmRepository of its
     * own, in its own namespace, which multi-tenant Flux setups (no references across namespaces, tenants limited to
     * their namespaces) accept.
     */
    public static final String SOURCE_NAMESPACE_PROPERTY = "k8s.view.gitops.source.namespace";

    /** Suffixes of the Secrets KDPS writes next to a chart source: its credentials, the CA of its server. */
    static final String SOURCE_AUTH_SUFFIX = "-repo-auth";
    static final String SOURCE_CA_SUFFIX = "-repo-ca";

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
         * @param repoId    the chart repository the commit reads its chart from, whose image registry and credentials
         *                  its images are pulled with
         * @return its values by Helm path
         * @throws Exception when it cannot be prepared
         */
        Map<String, String> of(String name, String namespace, String repoId) throws Exception;
    }

    /**
     * What a GitOps commit did.
     *
     * @param id                what {@link #apply} returns: the commit, or {@code NO_CHANGES}
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
    private GitPullRequests pullRequests;
    private final CommandUtils commandUtils;
    private final HelmService helmService;
    private final ConfigResolutionService configResolutionService = new ConfigResolutionService();
    private final SecurityProfileService securityProfileService;
    private final GlobalConfigService globalConfigService = new GlobalConfigService();
    private final StackDefinitionService stackDefinitionService = new StackDefinitionService();
    private Duration workspaceLockWait = WORKSPACE_LOCK_WAIT;
    /** Reads the credential KDPS stores for a chart repository. */
    private java.util.function.Function<HelmRepoEntity, String> repositorySecret;

    /**
     * @param fluxRoot root directory where manifests will be written (simulates Git workspace).
     */
    public FluxGitOpsBackend(Path fluxRoot, KubernetesService kubernetesService, ViewContext viewContext) {
        this(fluxRoot, kubernetesService, viewContext, null);
    }

    /**
     * The client of the Git providers' pull-request API (GitHub, GitLab, Gitea), through the view's outbound proxy for
     * the hosts it proxies. The proxy settings are read at each request, so a later change applies without a restart.
     */
    static Supplier<HttpClient> pullRequestClient(ViewContext viewContext) {
        return ProxySupport.clients(() -> HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)),
                () -> ViewConfigurationService.outboundProxy(viewContext));
    }

    FluxGitOpsBackend(Path fluxRoot, KubernetesService kubernetesService, ViewContext viewContext, HttpClient httpClient) {
        this.fluxRoot = fluxRoot;
        this.kubernetesService = kubernetesService;
        this.viewContext = viewContext;
        this.gitCredentialService = new GitCredentialService(viewContext);
        this.releaseMetadataService = new ReleaseMetadataService(viewContext);
        this.pullRequests = httpClient == null
                ? GitPullRequests.through(pullRequestClient(viewContext))
                : new GitPullRequests(httpClient);
        this.commandUtils = new CommandUtils(viewContext, kubernetesService);
        this.helmService = new HelmService(viewContext);
        this.securityProfileService = new SecurityProfileService(viewContext);
        this.repositorySecret = repo -> new HelmRepositoryService(viewContext).readPlainSecret(repo);
    }

    /** How long an operation waits for another one on the same release ({@link #WORKSPACE_LOCK_WAIT}); for tests. */
    void setWorkspaceLockWait(Duration wait) {
        this.workspaceLockWait = wait;
    }

    /** How the credential stored for a chart repository is read; for tests. */
    void setRepositorySecret(java.util.function.Function<HelmRepoEntity, String> repositorySecret) {
        this.repositorySecret = repositorySecret;
    }

    /** The client of the Git servers' pull request APIs; for tests. */
    void setPullRequests(GitPullRequests pullRequests) {
        this.pullRequests = pullRequests;
    }

    /** The GitOps output profiles; read from the view settings unless a test sets them. */
    private GitOpsProfileStore profileStore;
    /** Downloads the chart a file profile copies into the repository. */
    private ChartFetcher chartFetcher;
    /** Logs in to a chart repository before its chart is downloaded, as installs do. */
    private java.util.function.Consumer<String> repositoryLogin;

    void setProfileStore(GitOpsProfileStore profileStore) {
        this.profileStore = profileStore;
    }

    void setChartFetcher(ChartFetcher chartFetcher, java.util.function.Consumer<String> repositoryLogin) {
        this.chartFetcher = chartFetcher;
        this.repositoryLogin = repositoryLogin;
    }

    private GitOpsProfileStore profileStore() {
        if (profileStore == null) {
            profileStore = new GitOpsProfileStore(new InstanceSettings(viewContext));
        }
        return profileStore;
    }

    private ChartFetcher chartFetcher() {
        if (chartFetcher == null) {
            HelmClientDefault helm = new HelmClientDefault();
            chartFetcher = new ChartFetcher(helm::dependencyUpdate);
        }
        return chartFetcher;
    }

    private java.util.function.Consumer<String> repositoryLogin() {
        if (repositoryLogin == null) {
            repositoryLogin = repoId -> new HelmRepositoryService(viewContext).loginOrSync(repoId);
        }
        return repositoryLogin;
    }

    /**
     * A file profile writes the release's own chart only: a dependency the deploy would install (one the platform does
     * not provide, the settings need, and the cluster does not have) stops it before anything is prepared.
     */
    private void refuseDependenciesToInstall(GitOpsProfile profile, HelmDeployRequest request,
                                             DeploymentContext context, PlannedRelease planned) {
        if (request.getDependencies() == null) {
            return;
        }
        for (Map.Entry<String, Object> dep : request.getDependencies().entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = dep.getValue() instanceof Map
                    ? new LinkedHashMap<>((Map<String, Object>) dep.getValue()) : new LinkedHashMap<>();
            String chart = stringOrNull(spec.get("chart"));
            if (chart == null || chart.isBlank()) {
                continue;
            }
            DependencyRules.applyNamespaceFromForm(spec, request.getFormValues());
            if (gitOpsSkipReason(dep.getKey(), spec, request, false, context, planned) == null) {
                throw new IllegalArgumentException("The GitOps profile '" + profile.name + "' writes only the "
                        + "release's own chart, but this deploy also needs " + dep.getKey() + " (chart " + chart
                        + "), which is not on the cluster. Install it first, or deploy with the flux profile or with "
                        + "Direct Helm.");
            }
        }
    }

    /**
     * Writes a deploy with a file profile: the chart, as its repository publishes it, under the profile's chart path,
     * then the files the profile's rules render around it. A {@code createIfMissing} file already in the repository is
     * left as it is.
     *
     * @return the chart path in the repository, or {@code null} when the profile copies no chart
     */
    private Path writeProfileFiles(GitOpsProfile profile, GitClient gitClient, Path repoDir, List<Path> writtenFiles,
                                   String namespace, String release, String serviceKey, String repoName, String chart,
                                   String version, GitOpsSecretValues.Split split, String valuesSecretName)
            throws IOException {
        HelmRepoEntity repo = checkChartSource(repoName);
        String repositoryUrl;
        if (isOciRepository(repoName)) {
            repositoryUrl = "oci://" + repo.getUrl().trim().replaceFirst("^oci://", "");
        } else if (HelmRepositoryService.isAnonymous(repo)) {
            repositoryUrl = repo.getUrl().trim();
        } else {
            throw new IllegalArgumentException("Chart repository '" + repoName + "' needs a password, and a GitOps "
                    + "profile can copy charts from an OCI registry or a public HTTP repository only. Mirror the chart "
                    + "to an OCI registry, or deploy with the flux profile.");
        }

        Path chartDir = null;
        if (profile.chartPath != null && !profile.chartPath.isBlank()) {
            chartDir = Path.of(ProfileRenderer.repositoryPath(profile.chartPath, "the chart path"));
            repositoryLogin().accept(repoName);
            Path work = Files.createTempDirectory(fluxRoot, "chart-");
            Map<String, byte[]> chartFiles;
            try {
                chartFiles = chartFetcher().fetch(repositoryUrl, chart, version, work);
            } finally {
                deleteRecursively(work);
            }
            // Replaced as a whole, so a file the new version no longer has goes too.
            gitClient.deletePath(chartDir);
            Map<Path, byte[]> written = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> file : chartFiles.entrySet()) {
                written.put(chartDir.resolve(file.getKey()), file.getValue());
            }
            gitClient.write(new GitClient.Change(written, Set.of()));
        }

        List<String> secretValues = new ArrayList<>();
        collectStrings(split.secretValues(), secretValues);
        ProfileRenderer.RenderContext ctx = new ProfileRenderer.RenderContext(release, namespace, serviceKey, chart,
                version, repositoryUrl, split.publicValues(), List.of(), List.of(namespace),
                viewContext.getUsername(), java.time.Instant.now(), valuesSecretName);
        for (ProfileRenderer.RenderedFile file : ProfileRenderer.render(profile, ctx, secretValues)) {
            Path path = Path.of(file.path());
            if (GitOpsProfile.MODE_CREATE_IF_MISSING.equals(file.mode()) && Files.exists(repoDir.resolve(path))) {
                continue;
            }
            gitClient.writeFile(path, file.content());
            writtenFiles.add(path);
        }
        logFluxInfo(namespace, release, "apply", "Wrote the release with GitOps profile %s%s", profile.name,
                chartDir != null ? ", chart " + chart + " " + version + " in " + chartDir : "");
        return chartDir;
    }

    /** Every string of a nested map or list. */
    private static void collectStrings(Object value, List<String> out) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collectStrings(v, out));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> collectStrings(v, out));
        } else if (value != null) {
            out.add(String.valueOf(value));
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.debug("Could not delete {}: {}", p, e.toString());
                }
            });
        } catch (IOException e) {
            LOG.debug("Could not delete {}: {}", dir, e.toString());
        }
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
        return ContextService.DEFAULT_CONTEXT_ID.equals(selectedContextId(request))
                && !CommandService.deployUsesExternalKeytab(form);
    }

    /** The platform context the deploy targets: the request's, else the form's, else the default one. */
    static String selectedContextId(HelmDeployRequest request) {
        Map<String, Object> form = request.getFormValues() == null ? Map.of() : request.getFormValues();
        Object fromForm = ConfigResolutionService.getByDottedPath(form, "platformContextId");
        String contextId = request.getPlatformContextId();
        if (contextId == null || contextId.isBlank()) {
            contextId = fromForm == null || String.valueOf(fromForm).isBlank() ? ContextService.DEFAULT_CONTEXT_ID
                    : String.valueOf(fromForm);
        }
        return contextId;
    }

    /**
     * The krb5.conf of the platform context the deploy targets, as a direct deploy takes it: a backend in another realm
     * (CDP, a remote ODP) needs its own, not this host's.
     *
     * @return the krb5.conf, or {@code null} for the default context or one that has none (this host's is used)
     */
    String contextKrb5Conf(HelmDeployRequest request) {
        String contextId = selectedContextId(request);
        if (ContextService.DEFAULT_CONTEXT_ID.equals(contextId)) {
            return null;
        }
        try {
            org.apache.ambari.view.k8s.model.ResolvedContext resolved =
                    new ContextService(viewContext).resolve(contextId, null, null);
            Map<String, String> fields = resolved == null ? null : resolved.getResolvedFields();
            String krb5 = fields == null ? null : fields.get("kerberos.krb5Conf");
            return krb5 == null || krb5.isBlank() ? null : krb5;
        } catch (Exception ex) {
            LOG.warn("Could not resolve the krb5.conf of platform context {}: {}", contextId, ex.toString());
            return null;
        }
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
        return DependencyRules.gitOpsReuseSkipReason(name, spec, request, kubernetesService, this::helmReleaseExists);
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
     * Checks the chart repository of a deploy and of each of its dependencies before anything is created or committed:
     * configured in KDPS, and with what Flux pulls its charts with when it needs credentials.
     *
     * @throws IllegalStateException naming the repository and what is missing
     */
    private void checkChartSources(String repoName, Map<String, Object> dependencies) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        ids.add(repoName);
        if (dependencies != null) {
            for (Object spec : dependencies.values()) {
                Object depRepo = spec instanceof Map<?, ?> m ? m.get("repoId") : null;
                ids.add(depRepo == null || String.valueOf(depRepo).isBlank() ? repoName : String.valueOf(depRepo));
            }
        }
        ids.forEach(this::checkChartSource);
    }

    /**
     * A chart repository configured in KDPS that Flux can pull from: anonymous, or with an existing Secret named for
     * it, or with the user name and password KDPS stores for it.
     *
     * @throws IllegalStateException when it is not configured in KDPS, or needs credentials KDPS does not have
     */
    private HelmRepoEntity checkChartSource(String repoId) {
        HelmRepoEntity repo = new HelmRepositoryService(viewContext).get(repoId);
        if (repo == null || repo.getUrl() == null || repo.getUrl().isBlank()) {
            throw new IllegalStateException("Chart repository '" + repoId + "' is not configured in KDPS: add it under "
                    + "Configuration > Helm repositories before deploying with GitOps.");
        }
        if (needsCredentials(repo) && blankToNull(repo.getFluxSecretName()) == null) {
            login(repo);
        }
        return repo;
    }

    /** Whether Flux needs credentials to pull charts from the repository. */
    private static boolean needsCredentials(HelmRepoEntity repo) {
        return repo.getAuthMode() != null && !repo.getAuthMode().isBlank()
                && !"anonymous".equalsIgnoreCase(repo.getAuthMode().trim());
    }

    /** The user name and password Flux pulls charts with. */
    private record Login(String username, String password) {}

    /**
     * The user name and password KDPS stores for a chart repository, which Flux pulls its charts with.
     *
     * @throws IllegalStateException when one of them is missing
     */
    private Login login(HelmRepoEntity repo) {
        String username = blankToNull(repo.getUsername());
        String password = blankToNull(repositorySecret.apply(repo));
        if (username == null || password == null) {
            throw new IllegalStateException("Chart repository '" + repo.getId() + "' needs credentials, and Flux pulls"
                    + " its charts with a user name and password read from a Secret next to the chart source: KDPS has"
                    + " no " + (username == null ? "user name" : "password") + " stored for it. Enter it under"
                    + " Configuration > Helm repositories, or name there an existing Secret that holds the credentials"
                    + " (GitOps credentials Secret), then deploy again.");
        }
        return new Login(username, password);
    }

    /**
     * The namespace the platform keeps its chart sources in ({@link #SOURCE_NAMESPACE_PROPERTY}), or {@code null} when
     * each release reads its chart from a source of its own.
     *
     * @throws IllegalArgumentException when the property does not name a namespace
     */
    String sourceNamespace() {
        String configured = viewContext == null || viewContext.getProperties() == null ? null
                : viewContext.getProperties().get(SOURCE_NAMESPACE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return null;
        }
        DeployRequestValidator.checkDnsLabel("The namespace of the GitOps chart sources (view property "
                + SOURCE_NAMESPACE_PROPERTY + ")", configured.trim());
        return configured.trim();
    }

    /**
     * A Secret KDPS writes next to a chart source, through the Kubernetes API. Exactly one of the forms: a registry
     * login ({@code registryServer} set), a CA ({@code caPem} set), else a user name and password.
     */
    private record SourceSecret(String namespace, String name, String registryServer, String username, String password,
                                String caPem) {}

    /**
     * A Flux chart source as a deploy writes it.
     *
     * @param name      the HelmRepository
     * @param namespace its namespace
     * @param file      where its manifest is written in Git
     * @param yaml      its manifest
     * @param secrets   the Secrets KDPS writes next to it before the commit
     */
    record ChartSource(String name, String namespace, Path file, String yaml, List<SourceSecret> secrets) {
        /** The namespace a HelmRelease in {@code helmReleaseNamespace} names in its reference: none for its own. */
        String referencedFrom(String helmReleaseNamespace) {
            return namespace.equals(helmReleaseNamespace) ? null : namespace;
        }
    }

    /**
     * The Flux HelmRepository a HelmRelease reads its chart from. By default its own, named
     * {@code <helmRelease>-<repository>} in the HelmRelease's namespace and written to {@code file}: no two releases
     * define the same source, and no HelmRelease refers to another namespace. With {@link #SOURCE_NAMESPACE_PROPERTY}
     * set, the one source of the repository in that namespace, written to {@code <prefix>/_shared/sources/}.
     *
     * @param repoId               the KDPS chart repository
     * @param helmRelease          the HelmRelease reading it
     * @param helmReleaseNamespace its namespace
     * @param file                 where a source of its own is written
     * @param sharedDir            the {@link #SHARED_DIR} of the path prefix
     * @param shared               whether the HelmRelease belongs to no release (a dependency), so Flux never prunes
     *                             its source either
     * @throws IllegalStateException when the repository is not configured in KDPS, needs credentials KDPS does not
     *                               have, or names a truststore KDPS cannot read
     */
    ChartSource chartSource(String repoId, String helmRelease, String helmReleaseNamespace, Path file, Path sharedDir,
                            boolean shared) {
        HelmRepoEntity repo = checkChartSource(repoId);
        String central = sourceNamespace();
        String name = central != null ? repoId : helmRelease + "-" + repoId;
        String namespace = central != null ? central : helmReleaseNamespace;
        Path where = central != null ? centralSourceFile(sharedDir, central, repoId) : file;
        List<SourceSecret> secrets = new ArrayList<>();
        String secretRef = null;
        if (needsCredentials(repo)) {
            secretRef = blankToNull(repo.getFluxSecretName());
            if (secretRef == null) {
                Login login = login(repo);
                secretRef = name + SOURCE_AUTH_SUFFIX;
                boolean oci = "OCI".equalsIgnoreCase(repo.getType());
                secrets.add(new SourceSecret(namespace, secretRef, oci ? registryServer(repo.getUrl()) : null,
                        login.username(), login.password(), null));
            }
        }
        String certSecretRef = blankToNull(repo.getFluxCertSecretName());
        if (certSecretRef == null && blankToNull(repo.getCaTruststores()) != null) {
            certSecretRef = name + SOURCE_CA_SUFFIX;
            secrets.add(new SourceSecret(namespace, certSecretRef, null, null, null, truststorePem(repo)));
        }
        String yaml = renderHelmRepository(name, namespace, repo.getType(), repo.getUrl(), secretRef, certSecretRef,
                shared || central != null);
        return new ChartSource(name, namespace, where, yaml, secrets);
    }

    /**
     * The CA certificates of the KDPS truststores a chart repository names, for the {@code ca.crt} Flux verifies its
     * server with.
     *
     * @throws IllegalStateException when one of them cannot be read
     */
    private String truststorePem(HelmRepoEntity repo) {
        org.apache.ambari.view.k8s.service.TruststoreRegistryService registry =
                new org.apache.ambari.view.k8s.service.TruststoreRegistryService(viewContext, kubernetesService);
        StringBuilder pem = new StringBuilder();
        for (String name : repo.getCaTruststores().split(",")) {
            if (name.isBlank()) {
                continue;
            }
            try {
                String ca = registry.caPemByName(name.trim());
                pem.append(ca.endsWith("\n") ? ca : ca + "\n");
            } catch (Exception ex) {
                throw new IllegalStateException("Chart repository '" + repo.getId() + "' names the truststore '"
                        + DeployRequestValidator.shown(name.trim()) + "' for the CA of its server, which KDPS could not"
                        + " read, so nothing was committed to Git: " + ex.getMessage(), ex);
            }
        }
        return pem.toString();
    }

    /** The registry host of an OCI chart repository URL, which its login is for. */
    static String registryServer(String url) {
        String host = url.trim().replaceFirst("^[a-zA-Z]+://", "");
        int slash = host.indexOf('/');
        return slash > 0 ? host.substring(0, slash) : host;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Writes the Secrets of the chart sources of a deploy, so Flux finds them once it applies the commit. Never written
     * to Git.
     *
     * @throws IllegalStateException when one cannot be written; nothing is committed then
     */
    private void writeSourceSecrets(List<SourceSecret> secrets, String namespace, String release) {
        Map<String, String> labels = Map.of(MANAGED_BY_LABEL, MANAGED_BY_VALUE, "app.kubernetes.io/managed-by", "kdps");
        Set<String> written = new java.util.HashSet<>();
        for (SourceSecret secret : secrets) {
            if (!written.add(secret.namespace() + "/" + secret.name())) {
                continue;
            }
            try {
                if (secret.caPem() != null) {
                    kubernetesService.createOrUpdateOpaqueSecret(secret.namespace(), secret.name(),
                            Map.of("ca.crt", secret.caPem().getBytes(java.nio.charset.StandardCharsets.UTF_8)), labels);
                } else if (secret.registryServer() != null) {
                    kubernetesService.createOrUpdateDockerConfigSecret(secret.namespace(), secret.name(),
                            secret.registryServer(), secret.username(), secret.password(), labels);
                } else {
                    Map<String, byte[]> data = new LinkedHashMap<>();
                    data.put("username", secret.username().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    data.put("password", secret.password().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    kubernetesService.createOrUpdateOpaqueSecret(secret.namespace(), secret.name(), data, labels);
                }
                logFluxInfo(namespace, release, "secrets", "Stored what Flux reads the chart source with in Secret"
                        + " %s/%s", secret.namespace(), secret.name());
            } catch (Exception ex) {
                throw new IllegalStateException("Could not store what Flux pulls the charts of " + namespace + "/"
                        + release + " with in the Kubernetes Secret " + secret.namespace() + "/" + secret.name()
                        + ", so nothing was committed to Git: " + ex.getMessage(), ex);
            }
        }
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

    /**
     * How long Flux lets Helm install or upgrade the release of a service: the {@code gitops.timeout} of its
     * definition, else {@link #HELM_TIMEOUT}. A value Flux would reject is ignored, since it would stop the
     * HelmRelease from being applied at all.
     */
    private String helmTimeout(String serviceKey) {
        if (serviceKey == null || serviceKey.isBlank()) return HELM_TIMEOUT;
        try {
            StackServiceDef def = stackDefinitionService.getServiceDefinition(serviceKey);
            return helmTimeout(def);
        } catch (Exception e) {
            LOG.warn("Could not read the GitOps timeout of service definition {}: {}", serviceKey, e.toString());
            return HELM_TIMEOUT;
        }
    }

    /** The timeout a service definition asks for, else {@link #HELM_TIMEOUT}. */
    static String helmTimeout(StackServiceDef def) {
        Object configured = def == null || def.gitops == null ? null : def.gitops.get("timeout");
        String timeout = configured == null ? null : String.valueOf(configured).trim();
        if (timeout == null || timeout.isEmpty()) return HELM_TIMEOUT;
        if (!FLUX_DURATION.matcher(timeout).matches()) {
            LOG.warn("Ignored the GitOps timeout '{}' of service definition {}: not a duration such as 20m; using {}",
                    timeout, def.name, HELM_TIMEOUT);
            return HELM_TIMEOUT;
        }
        return timeout;
    }

    /**
     * How long a deploy waits for Flux to report the release of a service Ready: Flux may run the first install
     * {@link FluxManifests#INSTALL_RETRIES} more times after the first attempt timed out, each as long as the
     * HelmRelease timeout of the service, and needs a margin besides to fetch the commit and the chart and to install
     * the releases this one depends on. A wait shorter than that would fail a deploy Flux then completes. An upgrade
     * is not retried: Flux marks it failed after one timeout and the wait ends then, not at this limit.
     *
     * @param def          the service definition, or {@code null} for the default timeout
     * @param floorSeconds the shortest wait, whatever the timeout
     */
    public static int readyTimeoutSeconds(StackServiceDef def, int floorSeconds) {
        long attempts = FluxManifests.INSTALL_RETRIES + 1L;
        long seconds = durationSeconds(helmTimeout(def)) * attempts + READY_MARGIN_SECONDS;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(floorSeconds, seconds));
    }

    /** The seconds of a duration that matched {@link #FLUX_DURATION}, rounded up: {@code 1h30m} is 5400. */
    private static long durationSeconds(String duration) {
        java.util.regex.Matcher part = DURATION_PART.matcher(duration);
        double seconds = 0;
        while (part.find()) {
            double amount = Double.parseDouble(part.group(1));
            seconds += switch (part.group(2)) {
                case "ms" -> amount / 1000;
                case "s" -> amount;
                case "m" -> amount * 60;
                default -> amount * 3600;
            };
        }
        return (long) Math.ceil(seconds);
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

    /**
     * Renders a Flux HelmRepository; OCI URLs get the oci:// scheme and {@code type: oci}.
     *
     * @param secretRef     the Secret of its credentials, {@code null} for none
     * @param certSecretRef the Secret of the CA of its server, {@code null} for none
     * @param shared        whether other releases read it, so Flux never prunes it
     */
    static String renderHelmRepository(String name, String namespace, String type, String url, String secretRef,
                                       String certSecretRef, boolean shared) {
        boolean oci = "OCI".equalsIgnoreCase(type);
        String effectiveUrl = url.trim();
        if (oci) {
            effectiveUrl = "oci://" + effectiveUrl.replaceFirst("^[a-zA-Z]+://", "");
        }
        return FluxManifests.helmRepository(name, namespace, effectiveUrl, oci, secretRef, certSecretRef, shared);
    }

    /**
     * Refuses a GitOps deploy that could not be committed, before anything is prepared for it: no Git repository, no
     * credentials to push to it, a working branch other than the base branch for a direct commit, a pull request on a
     * Git server KDPS cannot open one on, no chart repository or chart version, a release directory inside or above
     * that of another release KDPS deployed to the repository, a chart repository that needs credentials KDPS does not
     * have. The checks {@link #commit} makes, without touching Git or changing the request.
     *
     * @throws IllegalArgumentException naming what is missing
     * @throws IllegalStateException    when a chart repository is not configured in KDPS, or needs credentials KDPS
     *                                  does not have
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
        sourceNamespace();
        checkChartSources(repoName, request.getDependencies());
        String commitMode = firstNonBlank(given.getCommitMode(),
                existingMeta != null ? existingMeta.getGitCommitMode() : null);
        checkWorkingBranch(commitMode, given.getBranch(),
                firstNonBlank(given.getBaseBranch() == null ? null : given.getBaseBranch().trim(),
                        recordedBaseBranch(existingMeta), DEFAULT_BASE_BRANCH), existingMeta);
        if (isPullRequestMode(commitMode)) {
            GitPullRequests.candidates(repoUrl.trim());
        }
        GitHostKeys.check(given.getSshKnownHosts());
        GitSecrets gitSecrets = gitSecrets(given, repoUrl.trim(), request.getNamespace(), request.getReleaseName(),
                existingMeta);
        checkGitAccess(repoUrl.trim(), commitMode, gitSecrets.token(), gitSecrets.sshKey());
    }

    /** The base branch of a release that never named one: Git's usual default branch. */
    static final String DEFAULT_BASE_BRANCH = "main";

    /** Whether a commit mode opens a pull request rather than committing to the base branch. */
    static boolean isPullRequestMode(String commitMode) {
        return "PR_MODE".equalsIgnoreCase(commitMode);
    }

    /**
     * Refuses a working branch that would send the change elsewhere than its commit mode says. A direct commit goes to
     * the base branch, which Flux reconciles: a change pushed anywhere else would never be applied, and a working
     * branch only names the branch of a pull request. A pull request is opened from its working branch into the base
     * branch: a working branch that is the base branch would push the change straight to the branch Flux reconciles,
     * without the review the operator chose, and leave nothing to merge. The branch recorded for the release is let
     * through, and ignored, for a redeploy that sends the record back as it read it.
     *
     * @throws IllegalArgumentException naming both branches
     */
    static void checkWorkingBranch(String commitMode, String branch, String baseBranch, K8sReleaseEntity existingMeta) {
        if (branch == null || branch.isBlank()) {
            return;
        }
        String named = branch.trim();
        boolean recorded = existingMeta != null && named.equals(existingMeta.getGitBranch());
        if (isPullRequestMode(commitMode)) {
            if (named.equals(baseBranch) && !recorded) {
                throw new IllegalArgumentException("A pull request is opened from its working branch into the base"
                        + " branch " + baseBranch + ", which Flux reconciles, so the working branch cannot be "
                        + baseBranch + " as well: the change would reach " + baseBranch + " without review. Leave the"
                        + " working branch empty, name another branch, or choose Direct commit.");
            }
            return;
        }
        if (named.equals(baseBranch) || recorded) {
            return;
        }
        throw new IllegalArgumentException("Direct commit pushes the change to the base branch " + baseBranch
                + ", which Flux reconciles, so the working branch '" + DeployRequestValidator.shown(branch.trim())
                + "' cannot be used: a working branch is only the branch of a pull request. Leave it empty, or choose"
                + " to open a pull request.");
    }

    /**
     * The Git credentials a deploy pushes with, and the SSH host keys the Git server must present.
     *
     * @param alias      the credential store alias they were read from, {@code null} when given with the deploy
     * @param knownHosts known_hosts lines of the Git server, {@code null} when none is given or recorded
     */
    private record GitSecrets(String token, String sshKey, String alias, String knownHosts) {}

    /**
     * The token or SSH key given with the deploy, else the one stored for the release, and the token stored next to
     * an SSH key. The Git server's host keys likewise: given with the deploy, else, when the deploy goes to the SSH
     * server the release was recorded on ({@link #sameSshServer}), recorded for the release or with its credential.
     * A stored secret is only read from an alias of the release itself and for the Git host it was entered for
     * ({@link #storedSecret}); an alias the deploy names next to a token or key it gives is not used.
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
        // The host keys recorded for the release are those of the server it was deployed to: a deploy to another one
        // has none, so the server's key is checked or trusted on first use as for a new release.
        String recordedHostKeys = null;
        if (existingMeta != null && sameSshServer(existingMeta.getGitRepoUrl(), repoUrl)) {
            recordedHostKeys = firstNonBlank(existingMeta.getGitSshKnownHosts(), gitCredentialService.knownHosts(
                    firstNonBlank(alias, existingMeta.getGitCredentialAlias())));
        }
        return new GitSecrets(token, sshKey, alias, firstNonBlank(git.getSshKnownHosts(), recordedHostKeys));
    }

    /**
     * Whether two Git repository URLs are reached over SSH on the same server: the same host ({@link
     * GitCredentialService#hostOf}) and port.
     */
    static boolean sameSshServer(String repoUrl, String otherRepoUrl) {
        if (!isSshRepoUrl(repoUrl) || !isSshRepoUrl(otherRepoUrl)) {
            return false;
        }
        String host = GitCredentialService.hostOf(repoUrl);
        return host != null && host.equals(GitCredentialService.hostOf(otherRepoUrl))
                && sshPort(repoUrl) == sshPort(otherRepoUrl);
    }

    /** The SSH port of a Git repository URL, 22 when it names none. */
    private static int sshPort(String repoUrl) {
        try {
            int port = new URIish(repoUrl.trim()).getPort();
            return port > 0 ? port : 22;
        } catch (URISyntaxException e) {
            return -1;
        }
    }

    /**
     * The Git credentials stored for a release, for the repository its metadata records, and the host keys recorded
     * for its Git server: what an uninstall, a pull request poll or any other operation without a deploy request uses.
     */
    private GitSecrets recordedGitSecrets(K8sReleaseEntity meta) {
        String alias = meta.getGitCredentialAlias();
        // Without a repository nothing is sent anywhere: a removal from Git is then skipped.
        if (alias == null || alias.isBlank() || meta.getGitRepoUrl() == null || meta.getGitRepoUrl().isBlank()) {
            return new GitSecrets(null, null, null, meta.getGitSshKnownHosts());
        }
        String resolved = storedSecret(alias, meta.getGitRepoUrl(), meta.getNamespace(), meta.getReleaseName(), meta);
        boolean sshKey = resolved != null && resolved.contains("BEGIN");
        return new GitSecrets(sshKey ? null : resolved, sshKey ? resolved : null, alias,
                firstNonBlank(meta.getGitSshKnownHosts(), gitCredentialService.knownHosts(alias)));
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
        boolean isSsh = isSshRepoUrl(repoUrl);
        boolean hasToken = token != null && !token.isBlank();
        boolean hasSshKey = sshKey != null && !sshKey.isBlank();
        if (isPullRequestMode(commitMode) && !hasToken) {
            throw new IllegalArgumentException("Pull request mode needs an access token of the Git server: KDPS opens"
                    + " the pull request with it. Give the token with the deploy (over SSH, next to the SSH key).");
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
     * @return the commit, or {@code NO_CHANGES} when Git already holds the release
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
     * <p>A direct commit is pushed to the base branch, which Flux reconciles. A pull request goes on the branch of the
     * release's previous pull request while that one is still open; otherwise on a new branch started from the base
     * branch as the server holds it, from which a pull request into the base branch is opened. Either way the change
     * reaches the base branch once merged, and never brings back a change merged or dropped before.</p>
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
        // Each release owns its own git workspace directory.
        // Operations on the same release are serialised via a per-release lock;
        // operations on different releases still run in parallel.
        // Taken before the release's record is read: whether the previous pull request is joined or closed is decided
        // from it, and a deploy or uninstall that ran meanwhile may have opened another one, which a record read
        // earlier would not know of and leave open.
        Path repoDir = workspaceForRelease(namespace, release);
        ReentrantLock workspaceLock = lockWorkspace(namespace, release);
        try {
        final var existingMeta = releaseMetadataService.find(namespace, release);
        // A redeploy that names no platform context stays on the release's: an external context must not inherit the
        // local cluster's Kerberos state.
        CommandService.defaultPlatformContext(request, existingMeta);

        // Whether this deploy names its base branch, before the release's record or main fills it in.
        final boolean baseNamed = request.getGit() != null && request.getGit().getBaseBranch() != null
                && !request.getGit().getBaseBranch().isBlank();
        HelmDeployRequest.GitOptions git = mergeGitOptions(request, existingMeta);
        if (git == null || git.getRepoUrl() == null || git.getRepoUrl().isBlank()) {
            throw new IllegalArgumentException("Flux GitOps requires git.repoUrl");
        }

        String repoUrl = git.getRepoUrl().trim();
        GitCredentialService.checkRepoUrl(repoUrl);

        // The profile writing the deploy into Git: as given, else the release's. A file profile decides how the change
        // is delivered, whatever the deploy asked.
        GitOpsProfile profile = profileStore().get(git.getProfile());
        if (profile == null) {
            throw new IllegalArgumentException("The GitOps profile '" + git.getProfile() + "' does not exist. Choose "
                    + "another one, or save it again on the GitOps profiles page.");
        }
        final GitOpsProfile filesProfile = GitOpsProfile.KIND_FILES.equals(profile.kind) ? profile : null;
        if (filesProfile != null) {
            git.setCommitMode(GitOpsProfile.DELIVERY_PULL_REQUEST.equals(filesProfile.delivery.mode)
                    ? "PR_MODE" : "DIRECT_COMMIT");
        }

        logFluxInfo(namespace, release, "apply", "Starting Flux GitOps deploy (mode=%s, repo=%s)", git.getCommitMode(), repoUrl);

        // The branch Flux reconciles: as given, else as recorded for the release, else main.
        final String baseBranch = git.getBaseBranch();
        final boolean prMode = isPullRequestMode(git.getCommitMode());
        // Recorded as the release's base branch when the deploy names it, the record tells it, the release is new, or
        // a direct commit puts the release on it; never main merely assumed for a pull request release recorded before
        // its base branch was (knownBaseBranch), which an uninstall would then take for where Flux reads it.
        final String baseToRecord = existingMeta == null || baseNamed || !prMode
                || knownBaseBranch(existingMeta) != null ? baseBranch : null;
        checkWorkingBranch(git.getCommitMode(), git.getBranch(), baseBranch, existingMeta);
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
        // Every chart source, checked before anything is created or committed: an unknown repository, or one whose
        // credentials KDPS does not have, stops here.
        sourceNamespace();
        checkChartSources(repoName, request.getDependencies());
        String chartInSource = chartNameInSource(chart, isOciRepository(repoName));

        String pathOverride = git.getPathPrefix();
        String storedPath = existingMeta != null ? existingMeta.getGitPath() : null;
        Path targetDir = resolveTargetPath(pathOverride, storedPath, namespace, release);
        checkApartFromRecordedReleases(targetDir, repoUrl, namespace, release);
        Path sharedDir = sharedDirectory(targetDir);

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

        checkNoRemovalPending(existingMeta, repoUrl, token);
        // Before anything is prepared or pushed: a pull request the Git server cannot take would leave the change on a
        // branch nobody merges, and the reuse of the previous pull request's branch depends on whether it is open, as
        // does the closing of one this deploy no longer goes through.
        WorkBranch work = workBranch(prMode, git.getBranch(), baseBranch, existingMeta, repoUrl, token,
                filesProfile != null ? filesProfile.delivery.branchPrefix + release : "flux");

        // Over SSH the Git server must present a host key given or recorded for the release. Without one, only an
        // explicit trust on first use lets the deploy accept the key presented, which is then recorded and required.
        GitClient gitClient = new GitClient(repoDir, repoUrl, baseBranch, token, sshKey)
                .withAuthor(viewContext.getUsername(), viewContext.getUsername() + "@ambari")
                .withTimeout(gitTimeoutSeconds(viewContext))
                .withProxy(ViewConfigurationService.outboundProxy(viewContext))
                .withHostKeys(gitSecrets.knownHosts())
                .withHostKeyTrustOnFirstUse(gitSecrets.knownHosts() == null
                        && Boolean.TRUE.equals(git.getTrustHostKeyOnFirstUse()));
        try {
        withGitRetry(() -> { gitClient.sync(); return null; }, "sync " + repoUrl);

        final String branch = work.name();
        git.setBranch(branch);
        if (prMode) {
            // The open pull request's branch as the server holds it, or a new one from the base branch: never a local
            // branch left over from a change merged or dropped since.
            gitClient.checkoutWorkBranch(branch, baseBranch);
        } else {
            // Never a base branch the server does not have: pushed, the commit would create a branch Flux does not
            // read (a repository whose branch is master, deployed to with the default main), and wait for Flux in vain.
            gitClient.requireOnServer(baseBranch, "a direct commit goes to the branch Flux reconciles, and KDPS"
                    + " does not create it");
            withGitRetry(() -> { gitClient.checkoutBranch(branch); return null; }, "checkout " + branch);
        }
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

        // 3. Ensure the image pull secret exists. The values reference it with the repository's image registry, once
        //    the overrides are known (releaseImageOverrides). A planned deploy's step ensured it, and the planned
        //    overrides reference it exactly as the Helm install would.
        if (planned == null && request.getSecretName() != null && !request.getSecretName().isBlank()) {
            try {
                String effectiveRepoId = firstNonBlank(request.getRepoId(), repoName);
                helmService.ensureImagePullSecretFromRepo(effectiveRepoId, namespace, request.getSecretName(), null);
                logFluxInfo(namespace, release, "automation", "Ensured image pull secret %s/%s", namespace, request.getSecretName());
            } catch (Exception ex) {
                LOG.warn("Failed to ensure image pull secret {}/{}: {}", namespace, request.getSecretName(), ex.getMessage());
                // Continue - secret might already exist
            }
        }

        // 4. Handle dependencies: create HelmRelease YAMLs for each dependency
        // Dependencies are committed to Git with the main HelmRelease, where they belong to no release: other releases
        // rely on the operators they install, so no uninstall removes them.
        // Note: Flux will reconcile them in parallel (no explicit ordering)
        List<PendingDependency> pendingDeps = new ArrayList<>();
        // Every file this deploy writes, searched for passwords and secrets before the commit.
        List<Path> writtenFiles = new ArrayList<>();
        if (filesProfile != null) {
            refuseDependenciesToInstall(filesProfile, request, context, planned);
        } else if (request.getDependencies() != null && !request.getDependencies().isEmpty()) {
            // The dependency being prepared, and its namespace once known, for the message that stops the deploy.
            String depKey = null;
            String depNamespace = null;
            try {
                logFluxInfo(namespace, release, "automation", "Processing %s dependencies", request.getDependencies().size());
                Path legacyDepDir = targetDir.resolve(DEPENDENCIES_DIR);

                for (Map.Entry<String, Object> depEntry : request.getDependencies().entrySet()) {
                    depKey = depEntry.getKey();
                    depNamespace = null;
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
                    // The namespace the form picked for it, which also places its file.
                    DependencyRules.applyNamespaceFromForm(depSpec, request.getFormValues());
                    depNamespace = firstNonBlank(stringOrNull(depSpec.get("namespace")), namespace);
                    // The same dependencies as a direct deploy: none on OpenShift that the platform provides, none
                    // that the settings do not need, none already on the cluster. One Flux already manages is kept
                    // even though it is now "on the cluster": Flux installed it, for this release or another one.
                    // "Manages" = published on the base branch, which Flux reads, not merely present in the local
                    // workspace or on a pull request branch, which is not reconciled until merged: in the shared
                    // directory, or in this release's own where earlier versions wrote it.
                    Path depFile = sharedDependencyFile(sharedDir, depNamespace, depReleaseName,
                            "-" + HELM_RELEASE_FILE);
                    Path legacyDepFile = dependencyFile(legacyDepDir, depReleaseName, "-" + HELM_RELEASE_FILE);
                    Boolean onBase = gitClient.publishedOn(depFile, baseBranch);
                    boolean managedHere = Boolean.TRUE.equals(onBase)
                            || Boolean.TRUE.equals(gitClient.publishedOn(legacyDepFile, baseBranch));
                    String skipped = gitOpsSkipReason(depKey, depSpec, request, managedHere, context, planned);
                    if (skipped != null) {
                        // A published file is left in place: the operator it installs (KEDA, the keytab webhook) is
                        // shared, and other releases may rely on it. One never published (e.g. in a still-open PR
                        // branch) is removed, so merging it cannot install a second copy. When the lookup failed,
                        // nothing is deleted. One in this release's directory moves to the shared directory below.
                        if (!managedHere && onBase != null) {
                            gitClient.deletePath(depFile);
                        }
                        logFluxInfo(namespace, release, "automation", "Dependency %s not written: %s", depKey, skipped);
                        continue;
                    }

                    // service.json names it chartVersion; "version" is kept for older definitions. Never the main
                    // chart's version, which belongs to another chart: without one, the HelmRelease names none.
                    String depVersion = firstNonBlank(CommandService.pinnedChartVersion(stringOrNull(depSpec.get("chartVersion"))),
                            CommandService.pinnedChartVersion(stringOrNull(depSpec.get("version"))));
                    
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
                    // Use dependency's repoId or fall back to main repo
                    String depRepoName = firstNonBlank(stringOrNull(depSpec.get("repoId")), repoName);
                    if (planned != null && planned.dependencyOverrides() != null) {
                        // What its Helm install would receive in a direct deploy: the repository's image registry
                        // and pull Secret (created in its namespace now), the keytab webhook's CA bundle and backend
                        // URL, its resolved dynamic values such as the Kerberos realm.
                        Map<String, String> depOverrides;
                        try {
                            depOverrides = planned.dependencyOverrides().of(depKey, depNamespace, depRepoName);
                        } catch (IllegalArgumentException ex) {
                            // Not the path refusal rethrown as it is below: reported with the dependency it stopped.
                            throw new IllegalStateException(ex.getMessage(), ex);
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

                    // Without a plan, the image settings its definition gives it, as a direct deploy's Helm install
                    // of it gets them: the image registry of the repository its chart comes from, and its own pull
                    // Secret, created in its namespace. Never the main release's Secret, which does not exist there.
                    if (planned == null) {
                        applyOverridesKeepingGiven(depValues, dependencyImageOverrides(depKey, depSpec, depNamespace,
                                depRepoName));
                    }
                    
                    // Rendered once the passwords and secrets of the deploy are known, so none of them is written
                    // to Git in the dependency's values either.
                    pendingDeps.add(new PendingDependency(depReleaseName, depNamespace,
                            chartNameInSource(depChart, isOciRepository(depRepoName)), depVersion, depRepoName,
                            depValues, depFile, legacyDepFile));

                    logFluxInfo(namespace, release, "automation", "Added dependency HelmRelease %s/%s (chart=%s, version=%s)",
                            depNamespace, depReleaseName, depChart, firstNonBlank(depVersion, "not pinned"));
                }
            logFluxInfo(namespace, release, "automation", "Generated %s dependency HelmReleases and dependsOn", pendingDeps.size());
            } catch (IllegalArgumentException ex) {
                // Nothing is committed for a dependency whose name would place its file outside the release's directory.
                throw ex;
            } catch (Exception ex) {
                // A direct deploy stops when a dependency cannot be installed: nothing is committed either. A
                // dependency the deploy needs (those it does not were skipped above) that cannot be prepared stops it:
                // committed without it, the release would wait on a HelmRelease that never comes.
                throw new IllegalStateException("GitOps deploy of " + namespace + "/" + release + " stopped: dependency "
                        + depKey + " could not be prepared" + (depNamespace != null ? " in namespace " + depNamespace : "")
                        + ", so nothing was committed to Git: " + ex.getMessage(), ex);
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
        // What the operator set explicitly, which the image settings taken from the chart repository do not replace.
        final Map<String, String> operatorOverrides = new LinkedHashMap<>(explicitOverrides);
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
                // Prepared as a direct deploy prepares it, from the selected context's krb5.conf when it has one; no
                // commit points the chart at a ConfigMap that was never written.
                String krb5ConfigMapName = commandUtils.prepareKrb5ConfConfigMap(namespace, release,
                        contextKrb5Conf(request));
                addOverride(explicitOverrides, "global.security.kerberos.enabled", "true");
                addOverride(explicitOverrides, "global.security.kerberos.configMapName", krb5ConfigMapName);
            }
            // Truststore for downstream TLS clients — shared with the direct deploy path via
            // TrustBundleService: merges (deduped) the Ambari server SSL CA + Ambari Internal CA +
            // operator truststores (defaults + those selected for this release), and provisions even
            // when Ambari itself is not on SSL. Passwords are credential-store/alias resolved. A release
            // that needs the Secret stops here, before anything is committed, when it cannot be written.
            TrustBundleService.ProvisionResult truststoreResult = new TrustBundleService(viewContext, kubernetesService)
                    .provisionForDeploy(namespace, release, serviceKey, request.getTruststoreRefs());
            truststoreResult.warnings().forEach(w -> logFluxWarn(namespace, release, "automation", "%s", w));
            if (truststoreResult.provisioned()) {
                for (Map.Entry<String, String> override : truststoreResult.helmOverrides().entrySet()) {
                    addOverride(explicitOverrides, override.getKey(), override.getValue());
                }
                LOG.info("Provisioned truststore Secret '{}' ({} CA(s)) in namespace '{}'",
                        truststoreResult.secretName(), truststoreResult.caCount(), namespace);
            } else {
                LOG.info("Truststore provisioning produced no CAs; skipping truststore wiring");
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
        // The repository's image registry and the image pull Secret, as a direct deploy passes them to Helm. A planned
        // deploy's overrides hold them already.
        if (planned == null) {
            releaseImageOverrides(request, repoName, serviceKey, mergedValues, explicitOverrides, operatorOverrides);
        }
        // The security profile's passwords are marked until the overrides are applied: record them first.
        secrets.addMarkedOverrides(explicitOverrides);
        // Apply all overrides into the values map
        applyOverridesToValues(mergedValues, explicitOverrides);
        // Drop helper _ov to avoid leaking into chart values
        mergedValues.remove("_ov");
        request.setValues(mergedValues);
        addServicePasswords(secrets, serviceKey, request, mergedValues);

        // Only a direct commit, to the branch Flux reconciles, is applied as soon as it is pushed. A pull request must not
        // alter the running release before it is merged, yet Flux applies a change of the Secret the release reads at
        // once.
        // A file profile's deploy is applied by whatever reads the repository (a pipeline, Argo CD), never on commit.
        boolean appliedOnCommit = filesProfile == null && !prMode;
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
            // Its manifest as this release wrote it before: in the shared directory, or still in its own.
            Path depManifest = repoDir.resolve(Files.exists(repoDir.resolve(dep.file())) ? dep.file()
                    : dep.legacyFile());
            StoredSecrets depStored = storedSecrets(dep.namespace(), dep.releaseName(), depManifest);
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
            // A file profile's pipeline reads the Secret under its one stable name, whether or not the change is merged.
            ValuesSecret valuesSecret = valuesSecret(namespace, release, split.secretValues(), stored,
                    appliedOnCommit || filesProfile != null);
            valuesSecrets.add(valuesSecret);
            valuesSecretName = valuesSecret.name();
        }

        String hrYaml = null;
        String specDigest = null;
        List<SourceSecret> sourceSecrets = new ArrayList<>();
        // Where a file profile copies the chart: published content, which the deploy's secrets are not searched in.
        Path chartDir = null;
        if (filesProfile != null) {
            chartDir = writeProfileFiles(filesProfile, gitClient, repoDir, writtenFiles, namespace, release, serviceKey,
                    repoName, chartInSource, version, split, valuesSecretName);
        } else {
            // The chart source of the release, and the Secrets Flux reads it with, written before the commit.
            ChartSource source = chartSource(repoName, release, namespace, targetDir.resolve(HELM_REPOSITORY_FILE),
                    sharedDir, false);
            sourceSecrets.addAll(source.secrets());

            // Dependencies an earlier version wrote into this release's directory move to the shared directory, in this
            // commit: those written above replace their old copy, the others go on as they were. One that cannot move yet
            // is written where it is, with its chart source next to it.
            Set<Path> stayed = migrateLegacyDependencies(gitClient, repoDir, targetDir, sharedDir,
                    pendingDeps.stream().map(PendingDependency::file).collect(Collectors.toSet()), baseBranch, namespace,
                    release);
            Map<Path, String> sourceFiles = new LinkedHashMap<>();
            sourceFiles.put(source.file(), source.yaml());
            List<DepRef> depRefs = new ArrayList<>();
            for (int i = 0; i < pendingDeps.size(); i++) {
                PendingDependency dep = pendingDeps.get(i);
                boolean inPlace = stayed.contains(dep.file());
                Path depFile = inPlace ? dep.legacyFile() : dep.file();
                Path depSourceFile = inPlace
                        ? dependencyFile(dep.legacyFile().getParent(), dep.releaseName(), "-" + HELM_REPOSITORY_FILE)
                        : sharedDependencyFile(sharedDir, dep.namespace(), dep.releaseName(), "-" + HELM_REPOSITORY_FILE);
                ChartSource depSource = chartSource(dep.repoName(), dep.releaseName(), dep.namespace(), depSourceFile,
                        sharedDir, true);
                sourceSecrets.addAll(depSource.secrets());
                sourceFiles.put(depSource.file(), depSource.yaml());
                GitOpsSecretValues.Split depSplit = secrets.splitContaining(dep.values(), storedDeps.get(i).documents());
                String depValuesSecretName = null;
                if (depSplit.hasSecrets()) {
                    ValuesSecret depSecret = valuesSecret(dep.namespace(), dep.releaseName(), depSplit.secretValues(),
                            storedDeps.get(i), appliedOnCommit);
                    valuesSecrets.add(depSecret);
                    depValuesSecretName = depSecret.name();
                }
                gitClient.writeFile(depFile, FluxManifests.helmRelease(dep.releaseName(), dep.namespace(), null,
                        FluxManifests.helmReleaseSpec(dep.chart(), dep.version(), depSource.name(),
                                depSource.referencedFrom(dep.namespace()), List.of(), depValuesSecretName,
                                depSplit.publicValues()), true));
                writtenFiles.add(depFile);
                // Only a dependency whose HelmRelease is written: the release must never wait on one Git does not hold.
                depRefs.add(new DepRef(dep.releaseName(), dep.namespace()));
            }
            Map<String, Object> hrSpec = FluxManifests.helmReleaseSpec(chartInSource, version, source.name(),
                    source.referencedFrom(namespace), depRefs, valuesSecretName, split.publicValues(),
                    helmTimeout(serviceKey));
            specDigest = specDigest(FluxManifests.specYaml(hrSpec));
            hrYaml = FluxManifests.helmRelease(release, namespace, specDigest, hrSpec);
            if (!source.file().equals(targetDir.resolve(HELM_REPOSITORY_FILE))) {
                // The release reads the platform's source: one of its own, written by a deploy before, goes.
                gitClient.deletePath(targetDir.resolve(HELM_REPOSITORY_FILE));
            }
            for (Map.Entry<Path, String> sourceFile : sourceFiles.entrySet()) {
                gitClient.writeFile(sourceFile.getKey(), sourceFile.getValue());
                writtenFiles.add(sourceFile.getKey());
            }
            removeCopiesOfSharedSources(gitClient, repoDir, sharedDir, namespace, release);
            gitClient.writeFile(targetDir.resolve(HELM_RELEASE_FILE), hrYaml);
            writtenFiles.add(targetDir.resolve(HELM_RELEASE_FILE));
        }

        // Fail closed: nothing that holds a password or secret is committed, whatever wrote it there.
        String leakingFile = fileLeakingSecrets(repoDir, gitClient, writtenFiles, secrets, chartDir);
        if (leakingFile != null) {
            throw new IllegalStateException("GitOps deploy of " + namespace + "/" + release + " stopped: "
                    + leakingFile + " would contain a password or secret value, so nothing was committed to Git. "
                    + "A very short or common password can match ordinary settings; if so, choose a stronger one.");
        }

        // Before anything reaches the cluster or Git: once this deploy is recorded, nothing in KDPS remembers it.
        closeSupersededPullRequest(work.supersededPullRequest(), existingMeta, repoUrl, token);

        String headBeforeCommit = gitClient.headCommit();
        // Written before the commit, so Flux never applies a HelmRelease whose chart source or values Secret misses
        // its Secret.
        writeSourceSecrets(sourceSecrets, namespace, release);
        List<ValuesSecret> secretsWritten = writeValuesSecrets(valuesSecrets);

        String commitMsg = filesProfile != null
                ? "KDPS deploy " + namespace + "/" + release + " (profile " + filesProfile.name + ")"
                : prMode
                ? "Flux GitOps (PR) apply " + namespace + "/" + release
                : "Flux GitOps apply " + namespace + "/" + release;

        String commitSha;
        try {
            // Once the branch moved on the server, the release is written again on top of it, where another release
            // may have taken a directory around this one's since the check above.
            commitSha = commitAndPush(gitClient, commitMsg,
                    () -> checkApartFromOtherReleases(repoDir, targetDir, namespace, release),
                    namespace + "/" + release);
        } catch (Exception commitEx) {
            // Nothing reached Git, so nothing of this deploy may stay ahead of it: neither the Secrets Flux would apply
            // to the running release, nor a local commit the next deploy would take as published.
            undoUnpublished(gitClient, headBeforeCommit, secretsWritten);
            throw commitEx;
        }
        boolean committed = commitSha != null;
        String alias = keepGitCredential(git, gitSecrets, repoUrl, namespace, release);
        String hostKeys = firstNonBlank(GitHostKeys.compact(gitSecrets.knownHosts()), gitClient.capturedHostKey());
        if (alias != null) {
            gitCredentialService.storeKnownHosts(alias, hostKeys);
        }
        // A change that did not land where Flux reads is applied anyway once Git already holds it there: a pull request
        // merged since, which a redeploy then finds with nothing new to commit.
        boolean appliedNow = appliedOnCommit || (!committed && hrYaml != null && hrYaml.equals(
                gitClient.publishedContent(targetDir.resolve(HELM_RELEASE_FILE), baseBranch)));
        String recordedBranch = existingMeta != null ? existingMeta.getGitBranch() : null;
        String prUrl = null;
        String prNumber = null;
        String prState = null;
        if (!committed) {
            // Git already holds the change. The release's pull request is still the one recorded, open or merged since;
            // not after a direct commit, nor one that proposed the release's removal and was closed since
            // (checkNoRemovalPending), which the release no longer waits for, nor one this deploy closed: the base branch
            // then holds the release.
            if (prMode && existingMeta != null && !existingMeta.isGitPendingDelete()
                    && work.supersededPullRequest() == null) {
                GitPullRequests.PullRequestInfo open = work.openPullRequest();
                prUrl = open != null ? open.url() : existingMeta.getGitPrUrl();
                prNumber = open != null ? open.id() : existingMeta.getGitPrNumber();
                prState = open != null ? firstNonBlank(open.state(), "open") : existingMeta.getGitPrState();
            } else {
                recordedBranch = baseBranch;
            }
            if (secretsWritten.isEmpty()) {
                LOG.info("Flux GitOps found no changes for {}/{}; skipping commit", namespace, release);
                if (existingMeta != null) {
                    // Git is unchanged, but the release may now be deployed with another commit mode or base branch,
                    // which its next deploys and its uninstall follow, or no longer wait for a removal closed unmerged.
                    try {
                        releaseMetadataService.recordGitChange(namespace, release, firstNonBlank(recordedBranch, branch),
                                baseToRecord, git.getCommitMode(), prUrl, prNumber, prState, specDigest);
                        releaseMetadataService.recordGitProfile(namespace, release,
                                filesProfile != null ? filesProfile.name : null);
                    } catch (Exception ex) {
                        LOG.warn("Failed to record the Git state of {}/{}: {}", namespace, release, ex.toString());
                    }
                }
                // Git is unchanged, but a Git credential given with this deploy replaced the one recorded.
                if (existingMeta != null && alias != null && !alias.equalsIgnoreCase(recordedAlias)) {
                    try {
                        releaseMetadataService.recordGitCredentialAlias(namespace, release, alias);
                        forgetReplacedAlias(recordedAlias, namespace, release);
                    } catch (Exception ex) {
                        LOG.warn("Failed to record the Git credential of {}/{}: {}", namespace, release, ex.toString());
                    }
                }
                recordHostKeys(namespace, release, hostKeys);
                // The branch that holds the release: the base branch once it does, else the pull request's.
                return new CommitResult("NO_CHANGES",
                        firstNonBlank(gitClient.headCommit(), existingMeta != null ? existingMeta.getGitCommitSha() : null),
                        false, repoUrl, appliedNow ? baseBranch : firstNonBlank(recordedBranch, branch), baseBranch,
                        appliedNow, prUrl, prState, specDigest);
            }
            // Only passwords or secrets changed: nothing to commit, but Flux must read the Secret again. A change not
            // applied yet never asks for it: its Secret is read only once the change is merged.
            if (appliedOnCommit) {
                requestReconcile(secretsWritten);
            }
            commitSha = firstNonBlank(gitClient.headCommit(), existingMeta != null ? existingMeta.getGitCommitSha() : null);
            logFluxInfo(namespace, release, "apply", "Updated the passwords and secrets only; Git is unchanged at %s", commitSha);
        } else if (prMode) {
            recordedBranch = branch;
            GitPullRequests.PullRequestInfo info = work.openPullRequest();
            if (info == null) {
                try {
                    info = pullRequests.open(repoUrl, baseBranch, branch, commitMsg, token);
                } catch (Exception ex) {
                    throw new IllegalStateException("The change of " + namespace + "/" + release + " was pushed to"
                            + " branch " + branch + " of " + repoUrl + ", but its pull request into " + baseBranch
                            + " could not be opened, so Flux will not apply it: " + ex.getMessage() + " Open the pull"
                            + " request by hand, or deploy again.", ex);
                }
                logFluxInfo(namespace, release, "apply", "Opened pull request %s into %s", info.url(), baseBranch);
            } else {
                logFluxInfo(namespace, release, "apply", "Added the change to pull request %s, still open",
                        info.url());
            }
            prUrl = info.url();
            prNumber = info.id();
            prState = firstNonBlank(info.state(), "open");
        } else {
            recordedBranch = branch;
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
            // What KDPS created for the release outside Git (keytab, truststore, autoscaler account...), which the
            // uninstall removes: a cluster-scoped one only when recorded here.
            try {
                releaseMetadataService.recordKdpsObjects(namespace, release,
                        kubernetesService.listKdpsObjectsForRelease(namespace, release));
            } catch (RuntimeException e) {
                LOG.warn("Could not record the objects KDPS created for {}/{}: {}", namespace, release, e.toString());
            }
            recordHostKeys(namespace, release, hostKeys);
            // Exactly where the change went: a direct commit forgets an earlier pull request, and a deploy ends a
            // removal that waited for its merge.
            releaseMetadataService.recordGitChange(namespace, release, firstNonBlank(recordedBranch, branch),
                    baseToRecord, git.getCommitMode(), prUrl, prNumber, prState, specDigest);
            releaseMetadataService.recordGitProfile(namespace, release, filesProfile != null ? filesProfile.name : null);
            if (alias != null && recordedAlias != null && !alias.equalsIgnoreCase(recordedAlias)) {
                forgetReplacedAlias(recordedAlias, namespace, release);
            }
        } catch (Exception ex) {
            LOG.warn("Failed to persist Flux metadata for {}/{}: {}", namespace, release, ex.toString());
        }

        return new CommitResult(commitSha, commitSha, committed, repoUrl, firstNonBlank(recordedBranch, branch),
                baseBranch, appliedNow, prUrl, prState, specDigest);

        } catch (Exception applyEx) {
            // Reset workspace so the next deploy attempt starts from what the server holds.
            resetWorkspaceOnFailure(gitClient, repoDir);
            throw applyEx;
        }
        } finally {
            workspaceLock.unlock();
        }
    }

    /**
     * The Git options of a deploy, completed from the release's record: its repository, its credential, its commit mode
     * and the base branch Flux reconciles ({@link #recordedBaseBranch}, else main). Never the working branch, which
     * only a pull request has, and which each change chooses again ({@link #workBranch}); nor is the base
     * branch ever taken from it.
     */
    private HelmDeployRequest.GitOptions mergeGitOptions(HelmDeployRequest request, K8sReleaseEntity existingMeta) {
        HelmDeployRequest.GitOptions git = request.getGit();
        if (git == null) {
            git = new HelmDeployRequest.GitOptions();
            request.setGit(git);
        }
        if ((git.getRepoUrl() == null || git.getRepoUrl().isBlank()) && existingMeta != null) {
            git.setRepoUrl(existingMeta.getGitRepoUrl());
        }
        git.setBaseBranch(firstNonBlank(git.getBaseBranch() == null ? null : git.getBaseBranch().trim(),
                recordedBaseBranch(existingMeta), DEFAULT_BASE_BRANCH));
        if (git.getBranch() != null) {
            git.setBranch(git.getBranch().isBlank() ? null : git.getBranch().trim());
        }
        if ((git.getCredentialAlias() == null || git.getCredentialAlias().isBlank()) && existingMeta != null) {
            git.setCredentialAlias(existingMeta.getGitCredentialAlias());
        }
        if ((git.getCommitMode() == null || git.getCommitMode().isBlank()) && existingMeta != null) {
            git.setCommitMode(existingMeta.getGitCommitMode());
        }
        if ((git.getProfile() == null || git.getProfile().isBlank()) && existingMeta != null) {
            git.setProfile(existingMeta.getGitProfile());
        }
        return git;
    }

    /**
     * The branch a deploy pushes its change to.
     *
     * @param name                  the branch
     * @param openPullRequest       the pull request already open for it, which the change joins; {@code null} when a
     *                              new one is opened once the change is pushed, or for a direct commit
     * @param supersededPullRequest the release's pull request still open that this deploy no longer goes through,
     *                              closed before the change is committed ({@link #closeSupersededPullRequest});
     *                              {@code null} when there is none
     */
    private record WorkBranch(String name, GitPullRequests.PullRequestInfo openPullRequest,
                              GitPullRequests.PullRequestInfo supersededPullRequest) {}

    /**
     * The branch of a deploy. A direct commit goes to the base branch. A pull request goes on the working branch the
     * deploy names, when it is not the one recorded for the release; else on the branch of the release's previous pull
     * request while that one is still open and was opened into this deploy's base branch, so the change joins it; else on
     * a new branch, {@code flux-<uuid>}, which the commit starts from the base branch. A pull request merged or closed is
     * never added to: its branch may be gone, or hold commits the base branch already has or never will. Nor is one
     * opened into another base branch: merged, it would reach a branch Flux no longer reads. Never the base branch
     * itself, which only a direct commit pushes to.
     *
     * <p>The release's previous pull request, still open, that the change does not join is returned for closing: a
     * switch to a direct commit, to another base branch or to another working branch. Left open and forgotten by the
     * release's record, it would be merged one day, and bring back what it proposes over this deploy, or, after an
     * uninstall, the release itself, which KDPS would no longer know.</p>
     *
     * @throws GitPullRequests.UnsupportedGitServerException when KDPS cannot open pull requests on the Git server
     * @throws IllegalStateException when the state of the previous pull request cannot be read
     */
    private WorkBranch workBranch(boolean prMode, String requested, String baseBranch, K8sReleaseEntity existingMeta,
                                  String repoUrl, String token, String branchPrefix) throws Exception {
        if (prMode) {
            pullRequests.api(repoUrl, token);
        }
        boolean previousRecorded = existingMeta != null && isPullRequestMode(existingMeta.getGitCommitMode())
                && !existingMeta.isGitPendingDelete() && sameRepository(repoUrl, existingMeta.getGitRepoUrl());
        String recorded = previousRecorded && !baseBranch.equals(existingMeta.getGitBranch())
                ? existingMeta.getGitBranch() : null;
        boolean named = requested != null && !requested.equals(recorded) && !requested.equals(baseBranch);
        boolean joins = prMode && recorded != null && !named && baseBranch.equals(recordedBaseBranch(existingMeta));
        // A merged pull request stays merged: nothing to join or to close.
        String number = previousRecorded && !GitPullRequests.isMerged(existingMeta.getGitPrState())
                ? existingMeta.getGitPrNumber() : null;
        GitPullRequests.PullRequestInfo previous = null;
        if (number != null && !number.isBlank()) {
            String namespace = existingMeta.getNamespace();
            String release = existingMeta.getReleaseName();
            String pullRequest = firstNonBlank(existingMeta.getGitPrUrl(), "#" + number);
            String why = joins
                    ? "a change joins that pull request only while it is open, and goes to a new one otherwise."
                    : "this deploy no longer goes through that pull request, which KDPS closes while it is open:"
                            + " merged later, it would bring back what it proposes.";
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("KDPS reads the state of pull request " + pullRequest + " of "
                        + namespace + "/" + release + " with the access token of the Git server, which this deploy"
                        + " does not give, so nothing was committed: " + why + " Deploy again giving the token.");
            }
            try {
                previous = pullRequests.get(repoUrl, number, token);
            } catch (Exception ex) {
                throw new IllegalStateException("KDPS could not read the state of pull request " + pullRequest + " of "
                        + namespace + "/" + release + ", so nothing was committed: " + why + " " + ex.getMessage(),
                        ex);
            }
            releaseMetadataService.updatePrInfo(namespace, release, previous.url(), previous.id(), previous.state());
            if (!GitPullRequests.isOpen(previous.state())) {
                previous = null;
            }
        }
        if (joins && previous != null) {
            return new WorkBranch(recorded, previous, null);
        }
        String branch = !prMode ? baseBranch : named ? requested : GitClient.generateBranch(branchPrefix);
        return new WorkBranch(branch, null, previous);
    }

    /**
     * Closes, through the Git server's API, the release's pull request still open that this deploy no longer goes
     * through ({@link #workBranch}), before anything of the deploy reaches the cluster or Git: once the deploy is
     * recorded, nothing in KDPS remembers that pull request, and no uninstall would close it.
     *
     * @param superseded the pull request, {@code null} for none
     * @throws IllegalStateException naming the pull request, when it could not be closed; nothing was committed
     */
    private void closeSupersededPullRequest(GitPullRequests.PullRequestInfo superseded, K8sReleaseEntity meta,
                                            String repoUrl, String token) {
        if (superseded == null) {
            return;
        }
        String namespace = meta.getNamespace();
        String releaseName = meta.getReleaseName();
        String number = firstNonBlank(superseded.id(), meta.getGitPrNumber());
        String pullRequest = firstNonBlank(superseded.url(), meta.getGitPrUrl(), "#" + number);
        GitPullRequests.PullRequestInfo closed;
        try {
            closed = pullRequests.close(repoUrl, number, token);
        } catch (Exception ex) {
            throw new IllegalStateException("KDPS could not close pull request " + pullRequest + " of " + namespace
                    + "/" + releaseName + ", still open, which this deploy no longer goes through, so nothing was"
                    + " committed: merged later, it would bring back what it proposes. " + ex.getMessage() + " Close it"
                    + " without merging it, then deploy again.", ex);
        }
        releaseMetadataService.updatePrInfo(namespace, releaseName, firstNonBlank(closed.url(), superseded.url()),
                firstNonBlank(closed.id(), number), firstNonBlank(closed.state(), "closed"));
        logFluxInfo(namespace, releaseName, "apply", "Closed pull request %s, still open, which this deploy no longer"
                + " goes through", pullRequest);
    }

    /**
     * Refuses a deploy of a release whose removal is proposed in a pull request still open: merged after the deploy,
     * it would take the release out of Git again. Once that pull request is merged or closed, the deploy goes ahead and
     * the release no longer waits for its removal.
     *
     * @throws IllegalStateException naming the pull request
     */
    private void checkNoRemovalPending(K8sReleaseEntity meta, String repoUrl, String token) {
        if (meta == null || !meta.isGitPendingDelete() || meta.getGitPrNumber() == null
                || meta.getGitPrNumber().isBlank() || !sameRepository(repoUrl, meta.getGitRepoUrl())) {
            return;
        }
        String release = meta.getNamespace() + "/" + meta.getReleaseName();
        String pullRequest = firstNonBlank(meta.getGitPrUrl(), "#" + meta.getGitPrNumber());
        String state;
        try {
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("the deploy gives no access token to read it with.");
            }
            state = pullRequests.get(repoUrl, meta.getGitPrNumber(), token).state();
        } catch (Exception ex) {
            throw new IllegalStateException("The removal of " + release + " is proposed in pull request " + pullRequest
                    + ", whose state KDPS could not read, so nothing was deployed: " + ex.getMessage() + " Close the"
                    + " pull request without merging it to keep the release, then deploy it again.", ex);
        }
        if (GitPullRequests.isOpen(state)) {
            throw new IllegalStateException("The removal of " + release + " is proposed in pull request " + pullRequest
                    + ", still open, so nothing was deployed: merged later, it would remove the release again. Close it"
                    + " without merging it to keep the release, then deploy it again; or merge it, then uninstall the"
                    + " release to finish its removal.");
        }
    }

    /** The name {@link GitClient#generateBranch} gives a pull request branch. */
    private static final java.util.regex.Pattern GENERATED_BRANCH = java.util.regex.Pattern.compile(
            "flux(-remove)?-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /**
     * The branch Flux reconciles for a GitOps release, as its record tells: the base branch recorded for it. For a
     * release recorded before its base branch was: the branch a direct commit went to when the operator named it, which
     * is the branch Flux reads; otherwise main. Never the branch of a pull request, nor one KDPS generated.
     *
     * <p>Earlier versions pushed a direct commit to the working branch and recorded no base branch, so a release they
     * deployed by direct commit only ever ran when its operator named the branch Flux reconciles as the working branch.
     * Taking main for it instead would move its next changes, and its uninstall, away from where Flux reads it.</p>
     *
     * @return the branch, or {@code null} without a record
     */
    public static String recordedBaseBranch(K8sReleaseEntity meta) {
        if (meta == null) {
            return null;
        }
        String known = knownBaseBranch(meta);
        return known != null ? known : DEFAULT_BASE_BRANCH;
    }

    /**
     * The base branch the release's record tells ({@link #recordedBaseBranch}), or {@code null} when it tells none and
     * main is only assumed: a pull request release, or one committed to a branch KDPS generated, recorded before base
     * branches were. A deploy never records an assumed main as the release's base branch, nor does the Releases page
     * send it back as one: an uninstall takes a recorded base branch that no longer holds the release for proof that
     * Flux no longer reads it.
     */
    public static String knownBaseBranch(K8sReleaseEntity meta) {
        if (meta == null) {
            return null;
        }
        if (meta.getGitBaseBranch() != null && !meta.getGitBaseBranch().isBlank()) {
            return meta.getGitBaseBranch();
        }
        String branch = meta.getGitBranch();
        if (!isPullRequestMode(meta.getGitCommitMode()) && branch != null && !branch.isBlank()
                && !GENERATED_BRANCH.matcher(branch).matches()) {
            return branch;
        }
        return null;
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

    /**
     * The release as Flux reports it, with its pull request. A release whose removal waits for its merge is
     * {@code PENDING_DELETE} whatever Flux reports, which is still the running release. Not once that pull request is
     * closed without its merge: the release keeps running, and no longer waits for anything.
     */
    @Override
    public HelmReleaseDTO status(String namespace, String releaseName) throws Exception {
        HelmReleaseDTO dto = fluxStatus(namespace, releaseName);
        var meta = releaseMetadataService.find(namespace, releaseName);
        if (meta != null && meta.isGitPendingDelete()) {
            String pullRequest = firstNonBlank(dto.gitPrUrl, meta.getGitPrUrl(), meta.getGitPrNumber());
            String base = recordedBaseBranch(meta);
            if (GitPullRequests.isClosed(firstNonBlank(dto.gitPrState, meta.getGitPrState()))) {
                dto.gitPendingDelete = false;
                dto.message = appendMessage("The pull request " + pullRequest + " removing the release was closed"
                        + " without being merged into " + base + ": the release keeps running. Uninstall it again to"
                        + " propose its removal once more.", dto.message);
                return dto;
            }
            dto.gitPendingDelete = true;
            dto.status = "PENDING_DELETE";
            dto.message = appendMessage(GitPullRequests.isMerged(dto.gitPrState)
                    ? "The pull request " + pullRequest + " removing the release is merged into " + base + ": uninstall"
                            + " the release again to finish removing it from the cluster and KDPS."
                    : "The removal of the release is proposed in pull request " + pullRequest + " into " + base
                            + ": the release keeps running until it is merged.", dto.message);
        }
        return dto;
    }

    private HelmReleaseDTO fluxStatus(String namespace, String releaseName) {
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
            FluxApiVersions.Selection helmReleaseApi = fluxApi(FluxApiVersions.Kind.HELM_RELEASE);

            GenericKubernetesResource hr = kubernetesService.getClient()
                    .genericKubernetesResources(helmReleaseApi.context())
                    .inNamespace(namespace)
                    .withName(releaseName)
                    .get();

            if (hr == null) {
                notOnCluster(dto, meta, helmReleaseApi);
                return dto;
            }
            if (hr.getMetadata() != null && hr.getMetadata().getGeneration() != null) {
                dto.desiredGeneration = String.valueOf(hr.getMetadata().getGeneration());
            }
            String sourceName = null;
            // Flux reads a reference without a namespace in the HelmRelease's own.
            String sourceNamespace = namespace;
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
                    CustomResourceDefinitionContext repoCtx = helmRepositoryContext();
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
     * The status of a release whose HelmRelease the cluster does not return. Flux not serving the HelmRelease API at
     * all is said as such: the cluster answers "not found" for an API it does not serve as for a release Flux has not
     * applied. Otherwise what the release waits for: its pull request to be merged, Flux to apply a merged change, or
     * Flux to reconcile. A status the pull request's state already gave (merged, closed, not pushed) is kept.
     */
    static void notOnCluster(HelmReleaseDTO dto, K8sReleaseEntity meta, FluxApiVersions.Selection helmReleaseApi) {
        if (!helmReleaseApi.served()) {
            dto.status = "UNKNOWN";
            dto.message = appendMessage(dto.message, helmReleaseApi.problem());
        } else if ("PENDING_PR".equals(dto.status) || "PROGRESSING".equals(dto.status)
                || "FAILED".equals(dto.status)) {
            dto.message = appendMessage(dto.message, "HelmRelease not on the cluster yet");
        } else if (dto.gitPrUrl != null && !dto.gitPrUrl.isBlank() && GitPullRequests.isOpen(dto.gitPrState)) {
            dto.status = "PENDING_PR";
            dto.message = appendMessage(dto.message, "Waiting for the pull request " + dto.gitPrUrl + " to be merged"
                    + (meta == null ? "" : " into " + recordedBaseBranch(meta))
                    + ": Flux installs the release once it is merged");
        } else {
            dto.status = "NOT_FOUND";
            dto.message = dto.message == null
                    ? "HelmRelease not found; waiting for Flux to reconcile"
                    : dto.message + " | HelmRelease not found";
        }
        dto.reconcileMessage = dto.message;
    }

    /**
     * Lightweight existence check to avoid a second status call for PR state handling. {@code false} when it cannot
     * tell, Flux not serving the HelmRelease API included.
     */
    private boolean hrNotAvailable(String namespace, String releaseName) {
        try {
            FluxApiVersions.Selection helmReleaseApi = fluxApi(FluxApiVersions.Kind.HELM_RELEASE);
            if (!helmReleaseApi.served()) {
                return false;
            }
            GenericKubernetesResource hr = kubernetesService.getClient()
                    .genericKubernetesResources(helmReleaseApi.context())
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
     * The version at which the cluster serves a kind of Flux object (see {@link FluxApiVersions}): the one KDPS writes
     * ({@link #FLUX_HR_API_VERSION}, {@link #FLUX_SRC_API_VERSION}), else the one of a Flux older than 2.3.
     */
    private FluxApiVersions.Selection fluxApi(FluxApiVersions.Kind kind) {
        return FluxApiVersions.resolve(kubernetesService == null ? null : kubernetesService.getClient(), kind);
    }

    private CustomResourceDefinitionContext helmReleaseContext() {
        return fluxApi(FluxApiVersions.Kind.HELM_RELEASE).context();
    }

    private CustomResourceDefinitionContext helmRepositoryContext() {
        return fluxApi(FluxApiVersions.Kind.HELM_REPOSITORY).context();
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
            GitPullRequests.PullRequestInfo info = pullRequests.get(meta.getGitRepoUrl(), meta.getGitPrNumber(), token);
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
     * Uninstalls a GitOps release with what its record holds: its manifests come out of the branch Flux reconciles the
     * way the release was deployed, by a direct commit or through a pull request, in the release's own workspace under
     * its lock. A direct commit removes the release at once: once the removal is pushed, its Git credentials are
     * deleted, then its HelmRelease from the cluster, then its record. Its chart source goes with its directory, which
     * Flux prunes; nothing another release reads (a shared dependency, a chart source of the platform) is removed. A
     * pull request leaves the release running and its record waiting for the merge
     * ({@link K8sReleaseEntity#isGitPendingDelete}); the uninstall that finds the branch Flux reconciles without the
     * release finishes the removal as a direct commit does. The release's own pull request, when still open, is
     * withdrawn first, so that merging it later cannot install the release again.
     *
     * @throws NotOwnDirectoryException when the recorded directory is not the release's own, or holds another
     *                                  HelmRelease; nothing is removed
     * @throws GitOpsBusyException      when another GitOps operation on the release holds its workspace; nothing is
     *                                  removed
     * @throws RuntimeException         when Git refused or could not take the removal, or the pull request could not
     *                                  be opened: the release keeps its record and its Git credential
     */
    @Override
    public void destroy(String namespace, String releaseName) throws Exception {
        // Held from before the record is read until the record is deleted (the removal from Git takes it again): the
        // pull request withdrawn and the one recorded for the removal must be those of the record as the last deploy
        // left it, not as it was before that deploy opened another pull request.
        ReentrantLock workspaceLock = lockWorkspace(namespace, releaseName);
        try {
        var meta = releaseMetadataService.find(namespace, releaseName);
        if (meta == null || !"FLUX_GITOPS".equalsIgnoreCase(meta.getDeploymentMode())) {
            LOG.info("Flux destroy skipped for {}/{} because deploymentMode is not FLUX_GITOPS or metadata missing", namespace, releaseName);
            return;
        }
        logFluxInfo(namespace, releaseName, "destroy", "Destroy requested for repo=%s base=%s path=%s (mode=%s)",
                meta.getGitRepoUrl(), recordedBaseBranch(meta), meta.getGitPath(), meta.getGitCommitMode());
        if (meta.getGitRepoUrl() != null && !meta.getGitRepoUrl().isBlank()) {
            // Refused before anything is removed, from Git or from the cluster (a HelmRelease deleted there while its
            // manifest stays in Git is recreated by Flux), and the record is kept so the uninstall can be retried
            // once it names the release's own directory.
            releaseDirectory(meta.getGitPath(), namespace, releaseName);
        }
        try {
            if (isPullRequestMode(meta.getGitCommitMode())) {
                if (proposeRemoval(meta)) {
                    // Flux keeps running the release until the removal is merged: nothing else is removed yet.
                    return;
                }
                forgetGitCredentials(meta);
            } else {
                removeFromGit(meta);
            }
        } catch (NotOwnDirectoryException | GitOpsBusyException refused) {
            // Nothing was removed from Git, so nothing is from the cluster either, and the release keeps its record
            // and its Git credential.
            throw refused;
        } catch (Exception ex) {
            // The release's manifests may still be on the branch Flux reconciles: its HelmRelease deleted from the
            // cluster would only be recreated. Nothing else is removed, and the record and the Git credential are
            // kept, so the uninstall can be retried.
            LOG.warn("Flux destroy git cleanup failed for {}/{}: {}", namespace, releaseName, ex.toString());
            throw new RuntimeException("Flux destroy encountered issues: Git cleanup failed: " + ex.getMessage(), ex);
        }
        // The branch Flux reconciles no longer holds the release. Flux removes it when it prunes what Git no longer
        // holds; deleted here too for a Flux that does not.
        deleteHelmRelease(namespace, releaseName);
        try {
            releaseMetadataService.delete(namespace, releaseName);
        } catch (Exception ex) {
            throw new RuntimeException("Flux destroy encountered issues: Failed to delete metadata: " + ex.getMessage(),
                    ex);
        }
        } finally {
            workspaceLock.unlock();
        }
    }

    /**
     * The removal of a pull request release, proposed in a pull request of its own into the branch Flux reconciles,
     * from a new branch started from it: never on the release's own pull request branch, which may be merged, gone, or
     * hold a change still under review. A pull request of the release still open is withdrawn first
     * ({@link #withdrawPullRequest}): merged later, it would bring the release back. Done once: an uninstall that finds
     * the removal's pull request still open waits for it, one that finds it closed without its merge proposes the
     * removal again. Once the branch Flux reconciles no longer holds the release (the removal merged, the release removed
     * from it by hand, or never merged there), and KDPS can tell that Flux reads nothing of it from elsewhere
     * ({@link #checkGoneFromBase}), the values Secrets of the removed HelmReleases are deleted and the uninstall goes on.
     *
     * @return whether the uninstall waits for the removal's pull request to be merged; the release's record then says so
     * @throws IllegalStateException when the removal cannot be proposed: no access token is saved for the release to
     *                               open the pull request with, it could not be opened once the removal was pushed, or
     *                               the release's own pull request could not be withdrawn; or when KDPS cannot tell
     *                               that Flux no longer reads the release from Git. Nothing was removed.
     */
    private boolean proposeRemoval(K8sReleaseEntity meta) throws Exception {
        String namespace = meta.getNamespace();
        String releaseName = meta.getReleaseName();
        String repoUrl = meta.getGitRepoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            LOG.warn("Flux destroy of {}/{}: no Git repository is recorded, nothing to remove from Git", namespace,
                    releaseName);
            return false;
        }
        Path releaseDir = releaseDirectory(meta.getGitPath(), namespace, releaseName);
        String base = recordedBaseBranch(meta);
        GitSecrets secrets = recordedGitSecrets(meta);
        String token = secrets.token();
        Path repoDir = workspaceForRelease(namespace, releaseName);
        ReentrantLock workspaceLock = lockWorkspace(namespace, releaseName);
        try {
        GitClient gitClient = new GitClient(repoDir, repoUrl, base, token, secrets.sshKey())
                .withAuthor(viewContext.getUsername(), viewContext.getUsername() + "@ambari")
                .withTimeout(gitTimeoutSeconds(viewContext))
                .withProxy(ViewConfigurationService.outboundProxy(viewContext))
                .withHostKeys(secrets.knownHosts());
        try {
        withGitRetry(() -> { gitClient.sync(); return null; }, "destroy-sync");
        // A branch the server does not have holds nothing, which says nothing of where Flux reads the release.
        boolean baseOnServer = gitClient.isOnServer(base);
        Boolean onBase = baseOnServer ? gitClient.publishedOn(releaseDir.resolve(HELM_RELEASE_FILE), base) : Boolean.FALSE;
        if (onBase == null) {
            throw new IOException("Branch " + base + " of " + repoUrl + " could not be read");
        }
        String release = namespace + "/" + releaseName;
        if (!onBase) {
            checkGoneFromBase(meta, base, baseOnServer);
            logFluxInfo(namespace, releaseName, "destroy", "Branch %s no longer holds %s: finishing the removal", base,
                    releaseDir);
            withdrawPullRequest(meta, gitClient, repoDir, releaseDir, base, token);
            Set<NamespacedName> helmReleases = removedHelmReleases(meta);
            deleteValuesSecrets(ownSourceSecrets(meta), helmReleases);
            return false;
        }
        String pending = meta.isGitPendingDelete() ? meta.getGitPrNumber() : null;
        if (pending != null && !pending.isBlank()) {
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("The removal of " + release + " is proposed in pull request "
                        + firstNonBlank(meta.getGitPrUrl(), "#" + pending) + ", whose state KDPS cannot read without"
                        + " an access token of the Git server.");
            }
            GitPullRequests.PullRequestInfo proposed = pullRequests.get(repoUrl, pending, token);
            releaseMetadataService.updatePrInfo(namespace, releaseName, proposed.url(), proposed.id(),
                    proposed.state());
            if (GitPullRequests.isOpen(proposed.state())) {
                logFluxInfo(namespace, releaseName, "destroy", "The removal waits for pull request %s to be merged",
                        proposed.url());
                return true;
            }
            logFluxInfo(namespace, releaseName, "destroy", "Pull request %s was closed without its merge: proposing"
                    + " the removal again", proposed.url());
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("KDPS removes " + release + " through a pull request into " + base + ", which"
                    + " it opens with the access token of the Git server, and none is saved for the release (deployed"
                    + " over SSH), so nothing was removed. Remove " + releaseDir + " from " + base + " by hand or"
                    + " through a pull request of your own, then uninstall the release again to finish.");
        }
        // Before anything is pushed: a removal on a branch nobody proposes for a merge would never be applied.
        pullRequests.api(repoUrl, token);
        withdrawPullRequest(meta, gitClient, repoDir, releaseDir, base, token);
        String branch = GitClient.generateBranch("flux-remove");
        gitClient.checkoutWorkBranch(branch, base);
        checkOwnHelmRelease(repoDir, releaseDir, namespace, releaseName);
        List<Path> files = releaseManifests(repoDir.resolve(releaseDir));
        Set<NamespacedName> helmReleases = helmReleasesIn(files);
        helmReleases.add(new NamespacedName(namespace, releaseName));
        migrateLegacyDependencies(gitClient, repoDir, releaseDir, sharedDirectory(releaseDir), Set.of(), base,
                namespace, releaseName);
        for (Path manifest : files) {
            gitClient.deletePath(repoDir.relativize(manifest));
        }
        String title = "Flux GitOps (PR) delete " + releaseDir;
        String sha = commitAndPush(gitClient, title,
                () -> checkOwnHelmRelease(repoDir, releaseDir, namespace, releaseName), release + " (uninstall)");
        if (sha == null) {
            return false;
        }
        GitPullRequests.PullRequestInfo opened;
        try {
            opened = pullRequests.open(repoUrl, base, branch, title, token);
        } catch (Exception ex) {
            throw new IllegalStateException("The removal of " + release + " was pushed to branch " + branch + " of "
                    + repoUrl + ", but its pull request into " + base + " could not be opened: " + ex.getMessage()
                    + " Open it by hand and merge it, then uninstall the release again to finish.", ex);
        }
        releaseMetadataService.recordPendingRemoval(namespace, releaseName, branch, opened.url(), opened.id(),
                firstNonBlank(opened.state(), "open"), helmReleases.stream()
                        .map(hr -> hr.namespace() + "/" + hr.name()).collect(Collectors.toList()));
        logFluxInfo(namespace, releaseName, "destroy", "Removal proposed in pull request %s into %s; the release keeps"
                + " running until it is merged", opened.url(), base);
        return true;
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
     * Refuses to take a pull request release missing from the base branch for one gone from Git, unless that base
     * branch tells where Flux reads it: on the server, and recorded for the release (every deploy since went there), or
     * one the release's removal was proposed into. Main, assumed for a release recorded before its base branch was, or
     * a base branch gone from the server (renamed, deleted) says nothing; nor does it when Flux reads another branch.
     * Only a cluster without the release's HelmRelease then shows that Flux runs nothing of it.
     *
     * @throws IllegalStateException saying how to uninstall the release; nothing was removed
     */
    private void checkGoneFromBase(K8sReleaseEntity meta, String base, boolean baseOnServer) {
        boolean recorded = meta.getGitBaseBranch() != null && !meta.getGitBaseBranch().isBlank();
        if (baseOnServer && (recorded || meta.isGitPendingDelete())) {
            return;
        }
        if (Boolean.FALSE.equals(helmReleaseOnCluster(meta.getNamespace(), meta.getReleaseName()))) {
            return;
        }
        String release = meta.getNamespace() + "/" + meta.getReleaseName();
        throw new IllegalStateException(stillReconciled(baseOnServer
                ? "Branch " + base + ", which KDPS takes for the base branch of " + release + " because none is recorded"
                        + " for it, does not hold the release"
                : "Branch " + base + " is not on " + meta.getGitRepoUrl(), release));
    }

    /**
     * Why an uninstall removed nothing when KDPS cannot tell where Flux reads a release that is still on the cluster,
     * and how the operator uninstalls it.
     *
     * @param found what KDPS found on the branch it took for the release's base branch
     */
    private static String stillReconciled(String found, String release) {
        return found + ", and the HelmRelease of " + release + " is still on the cluster (or KDPS could not check it),"
                + " so nothing was removed: Flux may reconcile the release from another branch. Deploy the release"
                + " again once, naming the branch Flux reconciles as its base branch, then uninstall it.";
    }

    /**
     * Whether the release's HelmRelease is on the cluster: what Flux runs, whatever branch it reads it from.
     *
     * @return TRUE or FALSE; {@code null} when the cluster could not be asked
     */
    private Boolean helmReleaseOnCluster(String namespace, String releaseName) {
        if (kubernetesService == null || kubernetesService.getClient() == null) {
            return null;
        }
        try {
            FluxApiVersions.Selection helmReleaseApi = fluxApi(FluxApiVersions.Kind.HELM_RELEASE);
            if (!helmReleaseApi.served()) {
                LOG.warn("Could not look for HelmRelease {}/{} on the cluster: {}", namespace, releaseName,
                        helmReleaseApi.problem());
                return null;
            }
            return kubernetesService.getClient()
                    .genericKubernetesResources(helmReleaseApi.context())
                    .inNamespace(namespace)
                    .withName(releaseName)
                    .get() != null;
        } catch (Exception e) {
            LOG.warn("Could not look for HelmRelease {}/{} on the cluster: {}", namespace, releaseName, e.toString());
            return null;
        }
    }

    /**
     * Withdraws the release's own pull request when it is still open as the release is uninstalled: merged later, it
     * would install the release again from the branch Flux reconciles, unknown to KDPS and without its values Secret.
     * Closed through the Git server's API with the release's access token. Without one (a release deployed over SSH),
     * whether it is open cannot be read: the release's manifests are removed from its branch instead, while the server
     * has it and it holds them, which leaves the pull request nothing to bring back. Nothing is done for a removal's
     * pull request, nor for a release without a pull request.
     *
     * @throws IllegalStateException naming the pull request, when it could be neither closed nor emptied
     */
    private void withdrawPullRequest(K8sReleaseEntity meta, GitClient gitClient, Path repoDir, Path releaseDir,
                                     String base, String token) throws Exception {
        String number = meta.getGitPrNumber();
        if (meta.isGitPendingDelete() || !isPullRequestMode(meta.getGitCommitMode()) || number == null
                || number.isBlank()) {
            return;
        }
        String namespace = meta.getNamespace();
        String releaseName = meta.getReleaseName();
        String release = namespace + "/" + releaseName;
        String repoUrl = meta.getGitRepoUrl();
        String pullRequest = firstNonBlank(meta.getGitPrUrl(), "#" + number);
        if (token != null && !token.isBlank()) {
            try {
                GitPullRequests.PullRequestInfo current = pullRequests.get(repoUrl, number, token);
                if (!GitPullRequests.isOpen(current.state())) {
                    return;
                }
                GitPullRequests.PullRequestInfo closed = pullRequests.close(repoUrl, number, token);
                releaseMetadataService.updatePrInfo(namespace, releaseName, firstNonBlank(closed.url(), current.url()),
                        firstNonBlank(closed.id(), number), firstNonBlank(closed.state(), "closed"));
                logFluxInfo(namespace, releaseName, "destroy", "Closed pull request %s, still open, of the release"
                        + " uninstalled", pullRequest);
                return;
            } catch (Exception ex) {
                throw new IllegalStateException("KDPS could not close pull request " + pullRequest + " of " + release
                        + ", which merged later would install the release again, so nothing was removed: "
                        + ex.getMessage() + " Close it without merging it, then uninstall the release again.", ex);
            }
        }
        String branch = meta.getGitBranch();
        if (branch == null || branch.isBlank() || branch.equals(base) || !gitClient.isOnServer(branch)
                || !Boolean.TRUE.equals(gitClient.publishedOn(releaseDir.resolve(HELM_RELEASE_FILE), branch))) {
            return;
        }
        try {
            gitClient.checkoutWorkBranch(branch, base);
            checkOwnHelmRelease(repoDir, releaseDir, namespace, releaseName);
            migrateLegacyDependencies(gitClient, repoDir, releaseDir, sharedDirectory(releaseDir), Set.of(), base,
                    namespace, releaseName);
            for (Path manifest : releaseManifests(repoDir.resolve(releaseDir))) {
                gitClient.deletePath(repoDir.relativize(manifest));
            }
            commitAndPush(gitClient, "Flux GitOps delete " + releaseDir,
                    () -> checkOwnHelmRelease(repoDir, releaseDir, namespace, releaseName), release + " (uninstall)");
            logFluxInfo(namespace, releaseName, "destroy", "Removed %s from branch %s of pull request %s", releaseDir,
                    branch, pullRequest);
        } catch (NotOwnDirectoryException refused) {
            throw refused;
        } catch (Exception ex) {
            throw new IllegalStateException("KDPS could not remove " + release + " from branch " + branch + " of pull"
                    + " request " + pullRequest + ", whose state it cannot read without an access token of the Git"
                    + " server, so nothing was removed: merged later, that pull request would install the release"
                    + " again. " + ex.getMessage() + " Close it without merging it and delete its branch, then"
                    + " uninstall the release again.", ex);
        }
    }

    /**
     * The Secrets KDPS writes for the chart source of a release of its own ({@link #chartSource}): those of
     * {@code <release>-<repository>} in its namespace.
     */
    private static java.util.Set<NamespacedName> ownSourceSecrets(K8sReleaseEntity meta) {
        java.util.Set<NamespacedName> secrets = new java.util.LinkedHashSet<>();
        if (meta.getRepoId() != null && !meta.getRepoId().isBlank()) {
            String source = meta.getReleaseName() + "-" + meta.getRepoId();
            secrets.add(new NamespacedName(meta.getNamespace(), source + SOURCE_AUTH_SUFFIX));
            secrets.add(new NamespacedName(meta.getNamespace(), source + SOURCE_CA_SUFFIX));
        }
        return secrets;
    }

    /**
     * The HelmReleases a removal proposed in a pull request took out of Git: those it recorded, and the release's own.
     */
    private static Set<NamespacedName> removedHelmReleases(K8sReleaseEntity meta) {
        Set<NamespacedName> helmReleases = new java.util.LinkedHashSet<>();
        helmReleases.add(new NamespacedName(meta.getNamespace(), meta.getReleaseName()));
        if (meta.getGitRemovedHelmReleases() != null) {
            for (String recorded : meta.getGitRemovedHelmReleases()) {
                int slash = recorded.indexOf('/');
                if (slash > 0 && slash < recorded.length() - 1) {
                    helmReleases.add(new NamespacedName(recorded.substring(0, slash), recorded.substring(slash + 1)));
                }
            }
        }
        return helmReleases;
    }

    /**
     * Refuses, before anything is removed, the uninstall of a GitOps release whose recorded Git credential cannot be
     * used to remove it from Git: one KDPS did not save for the release, one entered for another Git server than the
     * repository recorded, or one no longer in Ambari's credential store. Removed from KDPS anyway, the release would
     * stay in Git, where Flux keeps applying it, with nothing left in KDPS to remove it. Over SSH, also a release with
     * no host key recorded for its Git server, as one deployed by an earlier version: the removal would be refused
     * when connecting, and an uninstall cannot give the host key.
     *
     * @param meta the release's metadata
     * @throws IllegalStateException saying why, and that deploying the release again with its Git token or SSH key
     *                               (or the Git server's host key) lets it be uninstalled
     */
    public void checkRemovable(K8sReleaseEntity meta) {
        String reason;
        try {
            GitSecrets secrets = recordedGitSecrets(meta);
            if (secrets.sshKey() != null && secrets.knownHosts() == null && isSshRepoUrl(meta.getGitRepoUrl())) {
                throw new IllegalStateException("KDPS cannot remove " + meta.getNamespace() + "/"
                        + meta.getReleaseName() + " from Git, so nothing was removed: no SSH host key is recorded for"
                        + " its Git server, and KDPS only connects to a Git server whose host key it can check."
                        + " Deploy the release again once, giving the Git server host key (or choosing to trust the"
                        + " key the Git server presents), then uninstall it.");
            }
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
     * Whether a Git repository URL is reached over SSH, read as JGit reads it when it picks a transport: a URL with a
     * host and the scheme {@code ssh}, {@code git+ssh} or {@code ssh+git}, or the scp-like {@code [user@]host:path}.
     */
    static boolean isSshRepoUrl(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return false;
        }
        URIish uri;
        try {
            uri = new URIish(repoUrl.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return uri.getPath() != null && !uri.getPath().isEmpty();
        }
        return SSH_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
    }

    /**
     * Removes a GitOps release's manifests from Git by a direct commit to the branch Flux reconciles
     * ({@link #recordedBaseBranch}), with what its metadata records only: the repository, the base branch and the path
     * it was deployed to, and the Git credential KDPS saved for it. Nothing a request carries is used, so the stored
     * credential only ever goes to the repository the release was deployed from. As any removal from Git
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
        String base = recordedBaseBranch(meta);
        destroy(repoUrl, base, base, meta.getGitPath(), secrets.token(), secrets.sshKey(), secrets.knownHosts(),
                meta.getNamespace(), meta.getReleaseName());
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

    /**
     * Records the Git server's host keys a deploy connected with for the release, which its later operations (the
     * uninstall included) then require.
     */
    private void recordHostKeys(String namespace, String release, String hostKeys) {
        if (hostKeys == null) {
            return;
        }
        try {
            releaseMetadataService.recordGitSshKnownHosts(namespace, release, hostKeys);
        } catch (Exception ex) {
            LOG.warn("Failed to record the Git server host keys of {}/{}: {}", namespace, release, ex.toString());
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
     * Delete the manifests of a release from Git and push the change, in the release's own workspace so it is
     * serialised with the deploys of the same release. Only the files a deploy of the release writes into its own
     * directory ({@link #releaseDirectory}) are deleted ({@link #releaseManifests}): never a blank path or the bare
     * path prefix, nothing in another release's directory, even one below the release's, no other file, and nothing of
     * the shared directory. Dependencies an earlier version wrote into the release's directory are moved there
     * ({@link #migrateLegacyDependencies}): other releases may rely on them.
     *
     * @param repoUrl          Git repository URL.
     * @param branch           branch to push the removal to (will be created if absent, unless it is the one Flux
     *                         reconciles).
     * @param reconciledBranch the branch Flux reconciles, or null when unknown: the values Secrets of the removed
     *                         HelmReleases are deleted only once it no longer holds their manifests. When the server
     *                         does not have it, nothing is removed from Git, and the uninstall is refused unless the
     *                         cluster has no HelmRelease of the release
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
     * @throws IOException               when the server does not have the branch Flux reconciles and the release's
     *                                   HelmRelease is still on the cluster, or KDPS could not check it; nothing is
     *                                   removed
     */
    public void destroy(String repoUrl, String branch, String reconciledBranch, String repoPath,
                        String authToken, String sshKey,
                        String namespace, String releaseName) throws Exception {
        destroy(repoUrl, branch, reconciledBranch, repoPath, authToken, sshKey, null, namespace, releaseName);
    }

    /**
     * As {@link #destroy(String, String, String, String, String, String, String, String)}, over SSH with the host keys
     * the Git server must present.
     *
     * @param sshKnownHosts known_hosts lines of the Git server; {@code null} when none is recorded, and an SSH
     *                      connection is then refused with the key the server presented
     */
    public void destroy(String repoUrl, String branch, String reconciledBranch, String repoPath,
                        String authToken, String sshKey, String sshKnownHosts,
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
                .withTimeout(gitTimeoutSeconds(viewContext))
                .withProxy(ViewConfigurationService.outboundProxy(viewContext))
                .withHostKeys(sshKnownHosts);
        try {
        withGitRetry(() -> { gitClient.sync(); return null; }, "destroy-sync");
        if (reconciledBranch != null && !gitClient.isOnServer(reconciledBranch)) {
            // A branch Flux reconciles that the server does not have holds nothing, which says nothing of where Flux
            // reads the release; pushed there, the removal would create a branch from whatever the workspace started
            // from. Nothing to remove only when Flux runs nothing of the release.
            if (!Boolean.FALSE.equals(helmReleaseOnCluster(namespace, releaseName))) {
                throw new IOException(stillReconciled("Branch " + reconciledBranch + " is not on " + repoUrl,
                        namespace + "/" + releaseName));
            }
            LOG.warn("Branch {} is not on {} and the cluster has no HelmRelease {}/{}: nothing to remove from Git",
                    reconciledBranch, repoUrl, namespace, releaseName);
            deleteValuesSecrets(new java.util.LinkedHashSet<>(),
                    new java.util.LinkedHashSet<>(List.of(new NamespacedName(namespace, releaseName))));
            return;
        }
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
        valuesSecrets.addAll(sourceSecretsIn(files));
        List<Path> manifests = files.stream().map(repoDir::relativize).collect(Collectors.toList());
        // Before the release's own chart source goes: a dependency moved as it is may read it.
        migrateLegacyDependencies(gitClient, repoDir, releaseDir, sharedDirectory(releaseDir), Set.of(),
                reconciledBranch, namespace, releaseName);
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

        /** @param message what is in progress, and when to try again */
        public GitOpsBusyException(String message) {
            super(message);
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
     * The {@link #SHARED_DIR} of the path prefix a release's directory ({@code <prefix>/<namespace>/<release>}) is
     * under.
     */
    static Path sharedDirectory(Path releaseDir) {
        Path prefix = releaseDir.getParent() == null ? null : releaseDir.getParent().getParent();
        return prefix == null ? Path.of(SHARED_DIR) : prefix.resolve(SHARED_DIR);
    }

    /**
     * The file of a dependency, or of its chart source, in the shared directory: {@code <namespace>/<name><suffix>}.
     *
     * @throws IllegalArgumentException when the namespace or the name would place it anywhere else
     */
    static Path sharedDependencyFile(Path sharedDir, String namespace, String name, String suffix) {
        return dependencyFile(sharedDir.resolve(pathSegment("namespace", namespace)), name, suffix);
    }

    /**
     * The file of a chart source kept in one namespace for every release, in the shared directory:
     * {@code sources/<namespace>/<name>-helmrepository.yaml}.
     */
    static Path centralSourceFile(Path sharedDir, String namespace, String name) {
        return dependencyFile(sharedDir.resolve(SOURCES_DIR).resolve(pathSegment("namespace", namespace)), name,
                "-" + HELM_REPOSITORY_FILE);
    }

    /**
     * Moves the dependencies an earlier version wrote into a release's directory ({@link #DEPENDENCIES_DIR}) to the
     * shared directory, where no uninstall removes them: the operators they install (KEDA, kube-prometheus-stack, the
     * keytab webhook) may serve other releases. A dependency this change writes to the shared directory itself only
     * loses its old copy; one never published on the branch Flux reconciles (only on a pull request branch) is removed,
     * so a merge cannot install it. One moved as it is keeps reading the chart source it read, which moves along to
     * {@code sources/}; the chart sources of the directory go otherwise. What moves is annotated so Flux never prunes
     * it. Nothing is moved over a file the shared directory already holds.
     * <p>
     * Flux reads that annotation on the object on the cluster, not in Git: a Flux Kustomization that covers the
     * release's directory but not the shared one would prune a dependency whose file leaves it, and so uninstall the
     * operator for every release. So before a published dependency leaves the directory, its HelmRelease on the cluster
     * is marked ({@link KubernetesService#keepFromFluxPrune}). One KDPS cannot mark stays where it is, annotated, with
     * the chart source it reads, and the next deploy or uninstall of the release tries again.
     *
     * @param releaseDir       the release's directory
     * @param written          the files of the shared directory this change writes dependencies to
     * @param reconciledBranch the branch Flux reconciles, {@code null} when unknown
     * @return the files of {@code written} whose dependency stays in the release's directory, where the change writes
     *         it instead
     * @throws IOException when the directory cannot be read or written
     */
    private Set<Path> migrateLegacyDependencies(GitClient gitClient, Path repoDir, Path releaseDir, Path sharedDir,
                                                Set<Path> written, String reconciledBranch, String namespace,
                                                String release) throws IOException {
        Path legacyDir = repoDir.resolve(releaseDir).resolve(DEPENDENCIES_DIR);
        if (!Files.isDirectory(legacyDir)) {
            return Set.of();
        }
        List<Path> files;
        try (java.util.stream.Stream<Path> listed = Files.list(legacyDir)) {
            files = listed.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        Map<NamespacedName, String> sources = new LinkedHashMap<>();
        collectHelmRepositories(repoDir.resolve(releaseDir).resolve(HELM_REPOSITORY_FILE), sources);
        files.stream().filter(f -> f.getFileName().toString().endsWith("-" + HELM_REPOSITORY_FILE))
                .forEach(f -> collectHelmRepositories(f, sources));
        Set<Path> stayed = new java.util.LinkedHashSet<>();
        // The chart sources the dependencies that stay go on reading.
        Set<NamespacedName> stayedSources = new java.util.LinkedHashSet<>();
        for (Path file : files) {
            if (!file.getFileName().toString().endsWith("-" + HELM_RELEASE_FILE)) {
                continue;
            }
            Path legacy = repoDir.relativize(file);
            List<Map<?, ?>> documents = helmReleaseDocuments(file);
            Map<?, ?> metadata = documents.size() == 1 ? (Map<?, ?>) documents.get(0).get("metadata") : null;
            Path target;
            try {
                target = metadata == null ? null : sharedDependencyFile(sharedDir,
                        String.valueOf(metadata.get("namespace")), String.valueOf(metadata.get("name")),
                        "-" + HELM_RELEASE_FILE);
            } catch (IllegalArgumentException unusable) {
                target = null;
            }
            if (target == null) {
                logFluxWarn(namespace, release, "migrate", "Left %s in place: it does not hold one HelmRelease KDPS can"
                        + " move", legacy);
                continue;
            }
            NamespacedName helmRelease = new NamespacedName(String.valueOf(metadata.get("namespace")),
                    String.valueOf(metadata.get("name")));
            boolean unpublished = reconciledBranch != null
                    && Boolean.FALSE.equals(gitClient.publishedOn(legacy, reconciledBranch));
            if (!unpublished && !keptOnCluster(helmReleaseContext(), helmRelease)) {
                gitClient.writeFile(legacy, FluxManifests.keptByFlux(Files.readString(file)));
                stayed.add(target);
                NamespacedName source = sourceRefOf(documents.get(0));
                if (!written.contains(target) && source != null) {
                    stayedSources.add(source);
                }
                logFluxWarn(namespace, release, "migrate", "Left %s in place: KDPS could not mark HelmRelease %s/%s on"
                        + " the cluster so that Flux does not prune it, and a Flux Kustomization covering this"
                        + " release's directory but not %s would uninstall it for every release once its file moved."
                        + " The next deploy or uninstall of the release tries again.", legacy, helmRelease.namespace(),
                        helmRelease.name(), sharedDir);
                continue;
            }
            if (written.contains(target)) {
                logFluxInfo(namespace, release, "migrate", "Replaced %s by %s", legacy, target);
            } else if (unpublished) {
                logFluxInfo(namespace, release, "migrate", "Removed %s, never published on %s", legacy,
                        reconciledBranch);
            } else if (!Files.exists(repoDir.resolve(target))) {
                gitClient.writeFile(target, FluxManifests.keptByFlux(Files.readString(file)));
                NamespacedName source = sourceRefOf(documents.get(0));
                if (source != null && sources.containsKey(source)) {
                    // Best effort: a chart source Flux prunes is applied again from its new file, and its HelmRelease
                    // only waits for it meanwhile; nothing is uninstalled.
                    keptOnCluster(helmRepositoryContext(), source);
                    Path sourceTarget = centralSourceFile(sharedDir, source.namespace(), source.name());
                    if (!Files.exists(repoDir.resolve(sourceTarget))) {
                        gitClient.writeFile(sourceTarget, FluxManifests.keptByFlux(sources.get(source)));
                    }
                }
                logFluxInfo(namespace, release, "migrate", "Moved %s to %s, where no uninstall removes it", legacy,
                        target);
            } else {
                logFluxInfo(namespace, release, "migrate", "Removed %s: %s already holds it", legacy, target);
            }
            gitClient.deletePath(legacy);
        }
        for (Path file : files) {
            if (!file.getFileName().toString().endsWith("-" + HELM_REPOSITORY_FILE)) {
                continue;
            }
            Map<NamespacedName, String> defined = new LinkedHashMap<>();
            collectHelmRepositories(file, defined);
            if (defined.size() == 1 && stayedSources.removeAll(defined.keySet())) {
                gitClient.writeFile(repoDir.relativize(file), FluxManifests.keptByFlux(Files.readString(file)));
            } else {
                gitClient.deletePath(repoDir.relativize(file));
            }
        }
        // What is left was in the release's own chart source file, which this change rewrites or removes.
        for (NamespacedName source : stayedSources) {
            if (sources.containsKey(source)) {
                try {
                    gitClient.writeFile(dependencyFile(releaseDir.resolve(DEPENDENCIES_DIR), source.name(),
                            "-" + HELM_REPOSITORY_FILE), FluxManifests.keptByFlux(sources.get(source)));
                } catch (IllegalArgumentException unusable) {
                    logFluxWarn(namespace, release, "migrate", "Could not keep chart source %s/%s next to the"
                            + " dependencies left in place: %s", source.namespace(), source.name(),
                            unusable.getMessage());
                }
            }
        }
        removeCopiesOfSharedSources(gitClient, repoDir, sharedDir, namespace, release);
        return stayed;
    }

    /**
     * Removes, from the directories of the releases under the path prefix, every copy of a chart source that
     * {@code <prefix>/_shared/sources/} holds. Earlier versions committed the source of each release as
     * {@code flux-system/<repository>} in the release's directory, and kept a copy next to the dependencies left there.
     * Once {@code sources/} defines the same object, a Flux Kustomization over the prefix would find it twice, which
     * kustomize refuses, and then applies nothing at all. The HelmReleases that read the copy go on reading the same
     * object, now from {@code sources/}. Each copy is first marked on the cluster so that a Flux Kustomization covering
     * only that release's directory does not prune it, best effort: a source Flux prunes is applied again from
     * {@code sources/}, and its HelmReleases only wait for it meanwhile.
     *
     * @param sharedDir the {@link #SHARED_DIR} of the path prefix
     * @throws IOException when the directories cannot be read
     */
    private void removeCopiesOfSharedSources(GitClient gitClient, Path repoDir, Path sharedDir, String namespace,
                                             String release) throws IOException {
        Path sourcesDir = repoDir.resolve(sharedDir).resolve(SOURCES_DIR);
        if (!Files.isDirectory(sourcesDir)) {
            return;
        }
        Map<NamespacedName, String> shared = new LinkedHashMap<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(sourcesDir, 2)) {
            walk.filter(f -> f.getFileName().toString().endsWith("-" + HELM_REPOSITORY_FILE)).sorted()
                    .forEach(f -> collectHelmRepositories(f, shared));
        }
        if (shared.isEmpty()) {
            return;
        }
        Path prefix = repoDir.resolve(sharedDir).getParent();
        Path sharedRoot = repoDir.resolve(sharedDir);
        List<Path> copies;
        // <namespace>/<release>/helmrepository.yaml, <namespace>/<release>/dependencies/<source>-helmrepository.yaml
        try (java.util.stream.Stream<Path> walk = Files.walk(prefix, 4)) {
            copies = walk.filter(Files::isRegularFile)
                    .filter(f -> !f.startsWith(sharedRoot) && !f.startsWith(repoDir.resolve(".git")))
                    .filter(f -> {
                        Path relative = prefix.relativize(f);
                        String name = f.getFileName().toString();
                        return relative.getNameCount() == 3 && HELM_REPOSITORY_FILE.equals(name)
                                || relative.getNameCount() == 4
                                && DEPENDENCIES_DIR.equals(relative.getName(2).toString())
                                && name.endsWith("-" + HELM_REPOSITORY_FILE);
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }
        for (Path copy : copies) {
            Map<NamespacedName, String> defined = new LinkedHashMap<>();
            collectHelmRepositories(copy, defined);
            if (defined.size() != 1 || !shared.containsKey(defined.keySet().iterator().next())) {
                continue;
            }
            NamespacedName source = defined.keySet().iterator().next();
            keptOnCluster(helmRepositoryContext(), source);
            gitClient.deletePath(repoDir.relativize(copy));
            logFluxInfo(namespace, release, "sources", "Removed %s: chart source %s/%s is kept in %s",
                    repoDir.relativize(copy), source.namespace(), source.name(), repoDir.relativize(sourcesDir));
        }
    }

    /**
     * Whether an object on the cluster is safe from Flux's pruning once its file moves: marked, or not on the cluster.
     * {@code false} without a cluster to ask.
     */
    private boolean keptOnCluster(CustomResourceDefinitionContext context, NamespacedName object) {
        return kubernetesService != null
                && kubernetesService.keepFromFluxPrune(context, object.namespace(), object.name());
    }

    /** Reads the documents of a manifest file. */
    private static final com.fasterxml.jackson.databind.ObjectReader YAML_READER =
            new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                    .readerFor(Map.class);

    /** Adds the HelmRepository of a manifest file holding one, by namespace and name, with the file's text. */
    private static void collectHelmRepositories(Path file, Map<NamespacedName, String> sources) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (com.fasterxml.jackson.databind.MappingIterator<Object> docs = YAML_READER.readValues(file.toFile())) {
            List<Object> all = docs.readAll();
            if (all.size() == 1 && all.get(0) instanceof Map<?, ?> doc && "HelmRepository".equals(doc.get("kind"))
                    && doc.get("metadata") instanceof Map<?, ?> metadata && metadata.get("name") != null
                    && metadata.get("namespace") != null) {
                sources.put(new NamespacedName(String.valueOf(metadata.get("namespace")),
                        String.valueOf(metadata.get("name"))), Files.readString(file));
            }
        } catch (Exception ex) {
            LOG.warn("Could not read the HelmRepository of {}: {}", file, ex.toString());
        }
    }

    /**
     * The chart source a HelmRelease document reads, by namespace and name: a reference without a namespace is to the
     * HelmRelease's own. {@code null} when it names none.
     */
    private static NamespacedName sourceRefOf(Map<?, ?> helmRelease) {
        Map<?, ?> metadata = (Map<?, ?>) helmRelease.get("metadata");
        Object spec = helmRelease.get("spec");
        Object chart = spec instanceof Map<?, ?> s ? s.get("chart") : null;
        Object chartSpec = chart instanceof Map<?, ?> c ? c.get("spec") : null;
        Object ref = chartSpec instanceof Map<?, ?> cs ? cs.get("sourceRef") : null;
        if (!(ref instanceof Map<?, ?> sourceRef) || sourceRef.get("name") == null) {
            return null;
        }
        Object namespace = sourceRef.get("namespace");
        return new NamespacedName(namespace == null || String.valueOf(namespace).isBlank()
                ? String.valueOf(metadata.get("namespace")) : String.valueOf(namespace),
                String.valueOf(sourceRef.get("name")));
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
     * Whether the reset Flux last handled is the one asked for or a later one: an operator's Retry reconcile can
     * replace the value before Flux handles it. Values that are not instants are compared as they are.
     */
    static boolean handledAtOrAfter(String handled, String requested) {
        if (handled == null) return false;
        if (handled.equals(requested)) return true;
        try {
            return !java.time.Instant.parse(handled).isBefore(java.time.Instant.parse(requested));
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
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
                && !handledAtOrAfter(Objects.toString(status.get("lastHandledResetAt"), null), resetRequestedAt)) {
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
     * Deletes a HelmRelease from the cluster, which has Flux uninstall its Helm release. Best effort: Flux also removes
     * a HelmRelease the branch it reconciles no longer holds when it prunes.
     */
    private void deleteHelmRelease(String namespace, String releaseName) {
        if (kubernetesService == null || kubernetesService.getClient() == null) {
            return;
        }
        try {
            FluxApiVersions.Selection helmReleaseApi = fluxApi(FluxApiVersions.Kind.HELM_RELEASE);
            if (!helmReleaseApi.served()) {
                LOG.warn("Could not delete HelmRelease {}/{} from the cluster: {}", namespace, releaseName,
                        helmReleaseApi.problem());
                return;
            }
            kubernetesService.getClient()
                    .genericKubernetesResources(helmReleaseApi.context())
                    .inNamespace(namespace)
                    .withName(releaseName)
                    .delete();
            LOG.info("Deleted HelmRelease {}/{} from cluster", namespace, releaseName);
        } catch (Exception ex) {
            LOG.warn("Could not delete HelmRelease {}/{} from the cluster; Flux removes it if it prunes what Git no"
                    + " longer holds: {}", namespace, releaseName, ex.toString());
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
     * Adds the image settings the Helm install of the release receives in a direct deploy: the chart repository's
     * image registry, in the property the deploy names ({@code global.imageRegistry} by default), the image paths of
     * charts that ignore that property (JupyterHub), and the image pull Secret as plain names, also in the paths the
     * service definition adds (OpenMetadata's Airflow and OpenSearch). What the operator set explicitly is kept; the
     * image paths then follow the registry the operator chose.
     *
     * @param operatorOverrides the operator's own overrides ({@code _ov}), which win
     */
    private void releaseImageOverrides(HelmDeployRequest request, String repoName, String serviceKey,
                                       Map<String, Object> values, Map<String, String> overrides,
                                       Map<String, String> operatorOverrides) {
        String registryProperty = firstNonBlank(request.getImageGlobalRegistryProperty(),
                ImageRegistryOverrides.DEFAULT_REGISTRY_PROPERTY).trim();
        String registry = effectiveImageRegistry(repoName);
        Map<String, String> image = new LinkedHashMap<>();
        if (registry != null) {
            image.put(registryProperty, registry);
        }
        ImageRegistryOverrides.addReleasePullSecret(viewContext, request.getSecretName(), serviceKey, image);
        for (Map.Entry<String, String> setting : image.entrySet()) {
            if (operatorOverrides.containsKey(setting.getKey())) {
                LOG.info("Keeping the explicit override {} of {}/{} rather than the chart repository's {}",
                        setting.getKey(), request.getNamespace(), request.getReleaseName(), setting.getValue());
            } else {
                overrides.put(setting.getKey(), setting.getValue());
            }
        }
        ImageRegistryOverrides.applyImageRegistryPaths(viewContext, serviceKey,
                firstNonBlank(operatorOverrides.get(registryProperty), registry), values, overrides);
    }

    /**
     * The image settings the Helm install of a dependency receives in a direct deploy, from its definition: the
     * image registry of the repository its chart comes from (else the definition's {@code imageRepository}), in the
     * property the definition names, and the definition's own image pull Secret, created now in the dependency's
     * namespace from that repository's credentials and attached to the service accounts the definition lists.
     */
    private Map<String, String> dependencyImageOverrides(String name, Map<String, Object> spec, String namespace,
                                                         String repoId) {
        Map<String, String> overrides = new LinkedHashMap<>();
        String registryProperty = stringOrNull(spec.get("imageGlobalRegistryProperty"));
        if (registryProperty != null && !registryProperty.isBlank()) {
            String registry = firstNonBlank(effectiveImageRegistry(repoId), stringOrNull(spec.get("imageRepository")));
            if (registry != null) {
                overrides.put(registryProperty.trim(), registry.trim());
            }
        }
        String secretName = stringOrNull(spec.get("secretName"));
        if (secretName != null && !secretName.isBlank()) {
            List<String> serviceAccounts = new ArrayList<>();
            if (spec.get("serviceAccounts") instanceof List<?> accounts) {
                accounts.forEach(account -> {
                    if (account instanceof String s && !s.isBlank()) serviceAccounts.add(s);
                });
            }
            try {
                helmService.ensureImagePullSecretFromRepo(repoId, namespace, secretName, serviceAccounts);
            } catch (Exception ex) {
                LOG.warn("Failed to ensure image pull secret {}/{} for dependency {}: {}", namespace, secretName, name,
                        ex.getMessage());
            }
            overrides.put("imagePullSecrets[0].name", secretName);
            ImageRegistryOverrides.applyImagePullSecretTargets(viewContext, secretName, spec.get("serviceKey"),
                    overrides);
        }
        return overrides;
    }

    /** The image registry of a chart repository (its registry host override, else its host, and its project), or null. */
    private String effectiveImageRegistry(String repoId) {
        if (repoId == null || repoId.isBlank()) return null;
        try {
            String registry = new HelmRepositoryService(viewContext).getEffectiveImageRegistry(repoId);
            return registry == null || registry.isBlank() ? null : registry.trim();
        } catch (Exception ex) {
            LOG.warn("Could not resolve the image registry of chart repository {}: {}", repoId, ex.toString());
            return null;
        }
    }

    /** Applies {@code overrides} to {@code values}, except at the paths where the values already hold something. */
    static void applyOverridesKeepingGiven(Map<String, Object> values, Map<String, String> overrides) {
        Map<String, String> missing = new LinkedHashMap<>();
        overrides.forEach((path, value) -> {
            if (ImageRegistryOverrides.valueAt(values, path) == null) missing.put(path, value);
        });
        applyOverridesToValues(values, missing);
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

    /**
     * A dependency HelmRelease, rendered once the passwords and secrets of the deploy are known.
     *
     * @param file       its file in the shared directory
     * @param legacyFile where an earlier version wrote it, in the release's own directory
     */
    private record PendingDependency(String releaseName, String namespace, String chart, String version,
                                     String repoName, Map<String, Object> values, Path file, Path legacyFile) {}

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
        return fileLeakingSecrets(repoDir, gitClient, writtenFiles, secrets, null);
    }

    /**
     * Same as {@link #fileLeakingSecrets(Path, GitClient, List, GitOpsSecretValues)}, leaving out the files under
     * {@code excluded}: a chart copied as published, whose own default settings are not the deploy's and could match a
     * short secret by chance.
     */
    static String fileLeakingSecrets(Path repoDir, GitClient gitClient, List<Path> writtenFiles,
                                     GitOpsSecretValues secrets, Path excluded) throws IOException {
        if (secrets.isEmpty()) {
            return null;
        }
        java.util.Set<Path> files = new java.util.LinkedHashSet<>();
        writtenFiles.forEach(f -> files.add(f.normalize()));
        gitClient.changedFiles().forEach(f -> files.add(f.normalize()));
        for (Path file : files) {
            if (excluded != null && file.startsWith(excluded)) {
                continue;
            }
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
     * {@code helmrepository.yaml}. Nothing else in the directory, and nothing in a directory below it, which may be
     * another release's or hold other content of the repository; dependencies an earlier version wrote there are moved
     * to the shared directory instead ({@link #migrateLegacyDependencies}).
     *
     * @param dir the release's directory in the Git workspace
     */
    static List<Path> releaseManifests(Path dir) {
        List<Path> found = new ArrayList<>();
        for (String name : List.of(HELM_RELEASE_FILE, HELM_REPOSITORY_FILE)) {
            if (Files.isRegularFile(dir.resolve(name))) {
                found.add(dir.resolve(name));
            }
        }
        return found;
    }

    /**
     * The Secrets KDPS wrote for the chart sources of manifest files of the Git workspace: their credentials and the CA
     * of their server, by the names KDPS gives them. A Secret the repository names (one of SOPS, Sealed Secrets or
     * External Secrets) is not among them.
     */
    static java.util.Set<NamespacedName> sourceSecretsIn(List<Path> files) {
        java.util.Set<NamespacedName> found = new java.util.LinkedHashSet<>();
        for (Path file : files) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try (com.fasterxml.jackson.databind.MappingIterator<Object> docs = YAML_READER.readValues(file.toFile())) {
                while (docs.hasNext()) {
                    if (!(docs.next() instanceof Map<?, ?> doc) || !"HelmRepository".equals(doc.get("kind"))
                            || !(doc.get("metadata") instanceof Map<?, ?> metadata) || metadata.get("name") == null
                            || metadata.get("namespace") == null || !(doc.get("spec") instanceof Map<?, ?> spec)) {
                        continue;
                    }
                    String name = String.valueOf(metadata.get("name"));
                    for (Map.Entry<String, String> ref : Map.of("secretRef", SOURCE_AUTH_SUFFIX,
                            "certSecretRef", SOURCE_CA_SUFFIX).entrySet()) {
                        if (spec.get(ref.getKey()) instanceof Map<?, ?> secret
                                && (name + ref.getValue()).equals(String.valueOf(secret.get("name")))) {
                            found.add(new NamespacedName(String.valueOf(metadata.get("namespace")),
                                    name + ref.getValue()));
                        }
                    }
                }
            } catch (Exception ex) {
                LOG.warn("Could not read the HelmRepositories of {}: {}", file, ex.toString());
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
}
