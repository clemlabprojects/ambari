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

import com.marcnuri.helm.Release;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.service.helm.HelmClientDefault;
import org.apache.ambari.view.k8s.service.helm.HelmOperationRegistry;
import org.apache.ambari.view.k8s.service.helm.HelmReleaseHistory;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;

import java.util.*;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
/**
 * Service for managing Helm operations including deployments, upgrades, and releases.
 *
 * Key fixes:
 * - NEVER tag OCI chart refs with ":<version>". Use --version instead.
 * - Unified deployOrUpgrade overloads to a single canonical path.
 * - Fixed logging/variable mix-ups and the path that used install() in the upgrade branch.
 * - Consistent use of dryRun/wait/atomic/timeout across code paths.
 * - Safer handling of overrideOptions -> values map.
 */
public class HelmService {

    private static final Logger LOG = LoggerFactory.getLogger(HelmService.class);

    private final ViewContext viewContext;
    private final HelmClient helmClient;
    private final Supplier<NamespaceScope> namespaceScope;
     final HelmRepositoryService repositoryService;
    private final PathConfig pathConfiguration;

    /**
     * Constructs a {@code HelmService} using the default {@link HelmClientDefault} implementation.
     *
     * @param ctx the Ambari view context providing configuration and the instance settings
     */
    public HelmService(ViewContext ctx) {
        this(ctx, new HelmClientDefault());
    }

    /**
     * Constructs a {@code HelmService} with an explicit Helm client. The namespace scope is the one of the view
     * instance's {@link KubernetesService}, looked up on first use (only cluster-wide listings need it).
     *
     * @param ctx  the Ambari view context providing configuration and the instance settings
     * @param helm the Helm client to delegate low-level Helm operations to
     */
    public HelmService(ViewContext ctx, HelmClient helm) {
        this(ctx, helm, () -> KubernetesService.get(Objects.requireNonNull(ctx, "view context")).namespaceScope());
    }

    /**
     * Constructs a {@code HelmService} with an explicit Helm client and namespace scope.
     *
     * @param ctx            the Ambari view context providing configuration and the instance settings
     * @param helm           the Helm client to delegate low-level Helm operations to
     * @param namespaceScope supplies the scope used when the account may not list releases across the cluster;
     *                       called only for cluster-wide listings
     */
    public HelmService(ViewContext ctx, HelmClient helm, Supplier<NamespaceScope> namespaceScope) {
        this.viewContext = ctx;
        this.helmClient = helm;
        this.namespaceScope = Objects.requireNonNull(namespaceScope, "namespaceScope");
        this.repositoryService = new HelmRepositoryService(ctx, helm);
        this.pathConfiguration = repositoryService.paths();
    }

    /**
     * Lists all Helm releases in the given namespace.
     *
     * @param namespace  the Kubernetes namespace to query, or {@code null} for all namespaces (an empty string is
     *                   passed to Helm as is)
     * @param kubeconfig content of the view's own kubeconfig: when the account may not list all namespaces, the
     *                   namespaces walked come from the view's connection, so another cluster's kubeconfig must
     *                   only be used with an explicit namespace
     * @return list of discovered Helm releases, by release name then namespace when listing all namespaces
     */
    public List<Release> list(String namespace, String kubeconfig) {
        if (namespace != null) {
            LOG.info("Listing Helm releases in namespace: '{}'", namespace);
            return helmClient.list(namespace, kubeconfig, false);
        }
        // All namespaces. Helm keeps each release in a Secret, so this needs "list secrets" across the cluster;
        // an account limited to its own projects is refused and the scope lists namespace by namespace instead.
        List<Release> releases = new ArrayList<>(namespaceScope.get().listAcrossNamespaces(
                NamespaceScope.Resource.SECRETS,
                () -> {
                    LOG.info("Listing Helm releases in all namespaces");
                    return helmClient.list(null, kubeconfig, false);
                },
                ns -> helmClient.list(ns, kubeconfig, false),
                HelmService::isRbacRefusal,
                PER_NAMESPACE_WALK));
        // Same order whichever way they were listed, so pages are stable when an account's rights change.
        releases.sort(RELEASE_ORDER);
        return releases;
    }

    /**
     * Whether Helm should create the release namespace ({@code --create-namespace}). Only when it does not exist:
     * Helm sends the creation request even for an existing namespace, and an account limited to its own projects is
     * refused on rights before existence is considered, failing every install into a pre-provisioned project.
     */
    private boolean helmShouldCreateNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return true;
        }
        try {
            return namespaceScope.get().namespaceMissing(namespace);
        } catch (RuntimeException e) {
            LOG.debug("Namespace check unavailable, letting Helm create {} if needed: {}", namespace, e.toString());
            return true;
        }
    }

    /** Helm's own order for "list in all namespaces": by release name, then namespace. */
    private static final Comparator<Release> RELEASE_ORDER = Comparator
            .comparing(Release::getName, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Release::getNamespace, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Kubernetes' wording for an RBAC refusal, as Helm passes it on in plain text (Helm errors carry no status code),
     * e.g. {@code secrets is forbidden: User "u" cannot list resource "secrets" in API group "" in the namespace "x"}.
     */
    private static final Pattern RBAC_REFUSAL = Pattern.compile("is forbidden: User \"[^\"]*\" cannot [a-z]+ resource \"");

    /** Cause chains deeper than this are not inspected. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** Per-namespace Helm listings in flight at once, shared by every view instance; each is a full Helm round-trip. */
    private static final int PER_NAMESPACE_LIST_PARALLELISM = 4;

    /**
     * Deadline for listing every namespace one by one; namespaces that have not answered by then are left out of the
     * listing (logged as a warning). A Helm call blocked inside the native library cannot be interrupted and keeps
     * its pool thread until it returns; the deadline keeps the caller's request thread from waiting on it.
     */
    private static final Duration PER_NAMESPACE_LIST_TIMEOUT = Duration.ofSeconds(60);

    private static final AtomicInteger PER_NAMESPACE_THREAD_ID = new AtomicInteger();

    private static final ExecutorService PER_NAMESPACE_LIST_POOL = Executors.newFixedThreadPool(
            PER_NAMESPACE_LIST_PARALLELISM, r -> {
                Thread t = new Thread(r, "helm-list-per-namespace-" + PER_NAMESPACE_THREAD_ID.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    private static final NamespaceScope.Parallel PER_NAMESPACE_WALK =
            new NamespaceScope.Parallel(PER_NAMESPACE_LIST_POOL, PER_NAMESPACE_LIST_TIMEOUT);

    /** Stops the shared per-namespace listing pool; called once when the view shuts down. */
    static void shutdownExecutor() {
        PER_NAMESPACE_LIST_POOL.shutdownNow();
    }

    /**
     * True when a Helm call failed because the account lacks a Kubernetes right. A refusal for
     * {@code system:anonymous} means the credentials were not sent or not accepted, and does not count.
     *
     * @param e the failure raised by the Helm client
     * @return {@code true} for an RBAC refusal reported by Helm, or a rights refusal from the Kubernetes client
     */
    static boolean isRbacRefusal(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && RBAC_REFUSAL.matcher(m).find()) {
                return !NamespaceScope.isAnonymous(m);
            }
        }
        return NamespaceScope.isForbidden(e);
    }

    /**
     * Uninstalls a Helm release from the specified namespace.
     *
     * <p>The release is held in the {@link HelmOperationRegistry} until Helm returns: the uninstall is refused
     * while another operation on the release is running, and no deploy, upgrade or rollback starts meanwhile.
     *
     * @param namespace  the Kubernetes namespace containing the release
     * @param name       the Helm release name to uninstall
     * @param kubeconfig path or content of the kubeconfig used to authenticate
     * @throws HelmOperationRegistry.ReleaseBusyException when another Helm operation on the release is running
     */
    public void uninstall(String namespace, String name, String kubeconfig) {
        HelmOperationRegistry.run(namespace, name, () -> {
            helmClient.uninstall(name, namespace, kubeconfig);
            return null;
        });
    }

    /**
     * Rolls back a Helm release to a specific revision.
     *
     * <p>The release is held in the {@link HelmOperationRegistry} until Helm returns. While it waits, the newest
     * revision is pending-rollback; without the hold, an upgrade started meanwhile would take that revision for
     * one left behind by an interrupted operation and roll the release back a second time on top of this one.
     *
     * @param namespace  the Kubernetes namespace containing the release
     * @param name       the Helm release name to roll back
     * @param revision   the target revision number to roll back to
     * @param kubeconfig path or content of the kubeconfig used to authenticate
     * @throws HelmOperationRegistry.ReleaseBusyException when another Helm operation on the release is running
     */
    public void rollback(String namespace, String name, int revision, String kubeconfig) {
        HelmOperationRegistry.run(namespace, name, () -> {
            helmClient.rollback(name, namespace, revision, kubeconfig);
            return null;
        });
    }

    /**
     * Lists the Helm history for a release. Returns the parsed JSON output of
     * {@code helm history -o json} ordered by revision ascending.
     */
    public List<Map<String, Object>> history(String namespace, String name, String kubeconfig) {
        return helmClient.history(name, namespace, kubeconfig);
    }

    /* --------------------------- Public entry points --------------------------- */

    // Original entry point kept (defaults + dryRun=false)
    /**
     * Installs or upgrades a Helm release using defaults (dryRun=false, wait=true, atomic=false, timeout=900s).
     *
     * @param req        the deploy request containing chart name, release name, namespace, and values
     * @param kubeconfig path or content of the kubeconfig used to authenticate
     * @param repoIdOpt  optional repository ID to resolve the chart from; may be {@code null}
     * @param versionOpt optional chart version constraint; may be {@code null}
     * @return the resulting Helm {@link Release}
     */
    public Release deployOrUpgrade(HelmDeployRequest req, String kubeconfig, String repoIdOpt, String versionOpt) {
        int timeoutSeconds = 900;
        boolean wait = true;
        boolean atomic = false;
        boolean dryRun = false;
        return deployOrUpgrade(
                req.getChart(),
                req.getReleaseName(),
                req.getNamespace(),
                req.getValues(),
                Collections.emptyMap(),
                kubeconfig,
                repoIdOpt,
                versionOpt,
                timeoutSeconds,
                wait,
                atomic,
                dryRun
        );
    }

    // New overload to match callers that also pass dryRun explicitly with a HelmDeployRequest
    /**
     * Installs or upgrades a Helm release from a {@link HelmDeployRequest}, with explicit dry-run control.
     *
     * @param req        the deploy request containing chart name, release name, namespace, and values
     * @param kubeconfig path or content of the kubeconfig used to authenticate
     * @param repoIdOpt  optional repository ID to resolve the chart from; may be {@code null}
     * @param versionOpt optional chart version constraint; may be {@code null}
     * @param dryRun     when {@code true} Helm performs a dry run without making cluster changes
     * @return the resulting Helm {@link Release}
     */
    public Release deployOrUpgrade(HelmDeployRequest req, String kubeconfig, String repoIdOpt, String versionOpt, boolean dryRun) {
        int timeoutSeconds = 900;
        boolean wait = true;
        boolean atomic = false;
        return deployOrUpgrade(
                req.getChart(),
                req.getReleaseName(),
                req.getNamespace(),
                req.getValues(),
                Collections.emptyMap(),
                kubeconfig,
                repoIdOpt,
                versionOpt,
                timeoutSeconds,
                wait,
                atomic,
                dryRun
        );
    }

    // Overload preserved from your original shapes (defaults + dryRun=true)
    /**
     * Installs or upgrades a Helm release using per-field parameters and dry-run mode enabled.
     *
     * @param chartName   the chart name or OCI reference
     * @param releaseName the Helm release name
     * @param namespace   the target Kubernetes namespace
     * @param values      chart values to pass as {@code --values}
     * @param kubeconfig  path or content of the kubeconfig used to authenticate
     * @param repoIdOpt   optional repository ID to resolve the chart from; may be {@code null}
     * @param versionOpt  optional chart version constraint; may be {@code null}
     * @return the resulting Helm {@link Release}
     */
    public Release deployOrUpgrade(
            String chartName,
            String releaseName,
            String namespace,
            Map<String, Object> values,
            String kubeconfig,
            String repoIdOpt,
            String versionOpt
    ) {
        int timeoutSeconds = 900;
        boolean wait = true;
        boolean atomic = false;
        boolean dryRun = true;
        return deployOrUpgrade(chartName, releaseName, namespace, values, Collections.emptyMap(), kubeconfig, repoIdOpt, versionOpt, timeoutSeconds, wait, atomic, dryRun);
    }

    // *** Overload added to satisfy CommandService calls ***
    // matches: deployOrUpgrade(String, String, String, Map<String,Object>, Map<String,String>, String kubeconfig, String repoId, String version, boolean dryRun)
    /**
     * Installs or upgrades a Helm release with override options and explicit dry-run control.
     *
     * @param chartName       the chart name or OCI reference
     * @param releaseName     the Helm release name
     * @param namespace       the target Kubernetes namespace
     * @param values          chart values to pass as {@code --values}
     * @param overrideOptions additional dot-path key/value overrides applied on top of {@code values}
     * @param kubeconfig      path or content of the kubeconfig used to authenticate
     * @param repoIdOpt       optional repository ID to resolve the chart from; may be {@code null}
     * @param versionOpt      optional chart version constraint; may be {@code null}
     * @param dryRun          when {@code true} Helm performs a dry run without making cluster changes
     * @return the resulting Helm {@link Release}
     */
    public Release deployOrUpgrade(
            String chartName,
            String releaseName,
            String namespace,
            Map<String, Object> values,
            Map<String, String> overrideOptions,
            String kubeconfig,
            String repoIdOpt,
            String versionOpt,
            boolean dryRun
    ) {
        return deployOrUpgrade(chartName, releaseName, namespace, values, overrideOptions, kubeconfig, repoIdOpt,
                versionOpt, dryRun, null);
    }

    /**
     * Installs or upgrades a Helm release with override options and explicit dry-run control, reporting any
     * recovery action (rollback of an interrupted upgrade, reinstall of a release that never deployed) to the
     * operation's log.
     *
     * @param chartName       the chart name or OCI reference
     * @param releaseName     the Helm release name
     * @param namespace       the target Kubernetes namespace
     * @param values          chart values to pass as {@code --values}
     * @param overrideOptions additional dot-path key/value overrides applied on top of {@code values}
     * @param kubeconfig      path or content of the kubeconfig used to authenticate
     * @param repoIdOpt       optional repository ID to resolve the chart from; may be {@code null}
     * @param versionOpt      optional chart version constraint; may be {@code null}
     * @param dryRun          when {@code true} Helm performs a dry run without making cluster changes
     * @param operationLog    receives each recovery action, for the operation's log; may be {@code null}, in
     *                        which case an interrupted upgrade is reported as an error instead of rolled back
     * @return the resulting Helm {@link Release}
     */
    public Release deployOrUpgrade(
            String chartName,
            String releaseName,
            String namespace,
            Map<String, Object> values,
            Map<String, String> overrideOptions,
            String kubeconfig,
            String repoIdOpt,
            String versionOpt,
            boolean dryRun,
            Consumer<String> operationLog
    ) {
        int timeoutSeconds = 900;
        boolean wait = true;
        if(dryRun){
            wait = false;
        }
        boolean atomic = false;
        return deployOrUpgrade(chartName, releaseName, namespace, values, overrideOptions, kubeconfig, repoIdOpt,
                versionOpt, timeoutSeconds, wait, atomic, dryRun, operationLog);
    }

    // Full canonical implementation
    /**
     * Canonical install-or-upgrade implementation with full control over all Helm flags.
     * Checks whether the release already exists to decide between {@code helm install} and {@code helm upgrade}.
     * There is no operation log to record a rollback in, so an interrupted upgrade fails instead of being rolled
     * back (see the variant taking an {@code operationLog}).
     *
     * @param chartName       the chart name or OCI reference
     * @param releaseName     the Helm release name
     * @param namespace       the target Kubernetes namespace
     * @param values          chart values to pass as {@code --values}
     * @param overrideOptions additional dot-path key/value overrides applied on top of {@code values}
     * @param kubeconfig      path or content of the kubeconfig used to authenticate
     * @param repoIdOpt       optional repository ID to resolve the chart from; may be {@code null}
     * @param versionOpt      optional chart version constraint; may be {@code null}
     * @param timeoutSec      Helm operation timeout in seconds
     * @param wait            when {@code true} Helm waits until all resources are ready
     * @param atomic          when {@code true} Helm rolls back automatically on failure
     * @param dryRun          when {@code true} Helm performs a dry run without making cluster changes
     * @return the resulting Helm {@link Release}
     */
    public Release deployOrUpgrade(
            String chartName,
            String releaseName,
            String namespace,
            Map<String, Object> values,
            Map<String, String> overrideOptions,
            String kubeconfig,
            String repoIdOpt,
            String versionOpt,
            int timeoutSec,
            boolean wait,
            boolean atomic,
            boolean dryRun
    ) {
        return deployOrUpgrade(chartName, releaseName, namespace, values, overrideOptions, kubeconfig, repoIdOpt,
                versionOpt, timeoutSec, wait, atomic, dryRun, null);
    }

    /**
     * Canonical install-or-upgrade implementation, reporting recovery actions to the operation's log.
     *
     * <p>A real (not dry-run) install or upgrade holds the release in the {@link HelmOperationRegistry} until Helm
     * returns, so a second change of the same release is refused while one is running; a dry run is refused
     * too, since it would validate against a release about to change.
     *
     * <p>When an upgrade fails because the release's last revision is still pending, or because it has no deployed
     * revision, the release's Helm history decides what happens; KDPS never uninstalls a release that has served:
     * <ul>
     *   <li>no revision was ever deployed and the last one is pending-install or failed (an interrupted or failed
     *       first install): the release is uninstalled and installed again;</li>
     *   <li>the last revision is pending-upgrade or pending-rollback over an earlier good revision (an upgrade
     *       interrupted by a restart or a cancel): the release is rolled back to that revision and the upgrade
     *       retried once, unless a Helm operation KDPS started on it is still running or there is no
     *       {@code operationLog} to record the rollback in, in which case the upgrade fails and nothing is
     *       changed;</li>
     *   <li>anything else, or a history that cannot be read: the upgrade fails and nothing is changed.</li>
     * </ul>
     * A dry run changes nothing, so it never recovers: a pending revision fails the validation. Once the operation
     * is canceled, the Helm call already running finishes but no further one starts: a rollback is not followed by
     * the retried upgrade, nor an uninstall by the new install.
     *
     * @param chartName       the chart name or OCI reference
     * @param releaseName     the Helm release name
     * @param namespace       the target Kubernetes namespace
     * @param values          chart values to pass as {@code --values}
     * @param overrideOptions additional dot-path key/value overrides applied on top of {@code values}
     * @param kubeconfig      path or content of the kubeconfig used to authenticate
     * @param repoIdOpt       optional repository ID to resolve the chart from; may be {@code null}
     * @param versionOpt      optional chart version constraint; may be {@code null}
     * @param timeoutSec      Helm operation timeout in seconds
     * @param wait            when {@code true} Helm waits until all resources are ready
     * @param atomic          when {@code true} Helm rolls back automatically on failure
     * @param dryRun          when {@code true} Helm performs a dry run without making cluster changes
     * @param operationLog    receives each recovery action, for the operation's log; may be {@code null}, in
     *                        which case an interrupted upgrade is reported as an error instead of rolled back
     * @return the resulting Helm {@link Release}
     * @throws HelmOperationRegistry.ReleaseBusyException when another Helm operation on the release is running
     */
    public Release deployOrUpgrade(
            String chartName,
            String releaseName,
            String namespace,
            Map<String, Object> values,
            Map<String, String> overrideOptions,
            String kubeconfig,
            String repoIdOpt,
            String versionOpt,
            int timeoutSec,
            boolean wait,
            boolean atomic,
            boolean dryRun,
            Consumer<String> operationLog
    ) {
        if (dryRun) {
            HelmOperationRegistry.requireIdle(namespace, releaseName);
            return installOrUpgrade(chartName, releaseName, namespace, values, overrideOptions, kubeconfig, repoIdOpt,
                    versionOpt, timeoutSec, wait, atomic, true, operationLog);
        }
        return HelmOperationRegistry.run(namespace, releaseName, () -> installOrUpgrade(chartName, releaseName,
                namespace, values, overrideOptions, kubeconfig, repoIdOpt, versionOpt, timeoutSec, wait, atomic,
                false, operationLog));
    }

    private Release installOrUpgrade(
            String chartName,
            String releaseName,
            String namespace,
            Map<String, Object> values,
            Map<String, String> overrideOptions,
            String kubeconfig,
            String repoIdOpt,
            String versionOpt,
            int timeoutSec,
            boolean wait,
            boolean atomic,
            boolean dryRun,
            Consumer<String> operationLog
    ) {
        LOG.info("HelmService.deployOrUpgrade called with chartName='{}', releaseName='{}', namespace='{}', repoId='{}', version='{}', timeoutSec={}, wait={}, atomic={}, dryRun={}",
                chartName, releaseName, namespace, repoIdOpt, versionOpt, timeoutSec, wait, atomic, dryRun);
        final RepoResolution rr = resolveChartRef(chartName, repoIdOpt);
        LOG.info("Resolved chart reference: {}", rr.chartRef);
        Map<String, Object> finalValues = applyOverrides(expandDotPaths(values), overrideOptions);

        final String chartRef = rr.chartRef; // never ":version" for OCI
        final String chartWithVersionArg = appendVersionArg(chartRef, versionOpt);

        LOG.info("Upsert Helm release: ns={}, name={}, chartRef={}, repoId={}, version={}, dryRun={}, wait={}, atomic={}",
                namespace, releaseName, chartRef, repoIdOpt, versionOpt, dryRun, wait, atomic);

        boolean releaseExists = false;
        try {
            // Use all=true to detect releases in any state (deployed, failed, pending-*).
            // Using deployedOnly=true would miss failed/pending releases, causing install
            // to fail with "cannot re-use a name that is still in use".
            releaseExists = helmClient.list(namespace, kubeconfig, false).stream()
                    .anyMatch(r -> releaseName.equals(r.getName()));
        } catch (Exception e) {
            LOG.warn("Could not list releases to detect existence, will try install then upgrade fallback: {}", e.toString());
        }

        final Supplier<Release> upgrade = () -> helmClient.upgrade(
                chartRef,
                versionOpt,
                rr.isOci,
                releaseName,
                namespace,
                pathConfiguration.repositoriesConfig(),
                kubeconfig,
                finalValues,
                timeoutSec,
                /*wait*/ wait,
                /*atomic*/ atomic,
                /*dryRun*/ dryRun
        );
        final Supplier<Release> freshInstall = () -> helmClient.install(
                chartRef,
                versionOpt,
                rr.isOci,
                releaseName,
                namespace,
                pathConfiguration.repositoriesConfig(),
                kubeconfig,
                finalValues,
                timeoutSec,
                /*createNs*/ helmShouldCreateNamespace(namespace),
                /*wait*/ wait,
                /*atomic*/ atomic,
                /*dryRun*/ dryRun
        );

        if (!releaseExists) {
            try {
                LOG.info("Release doesn't exist → install: chartRef={}, ns={}, name={}, version={}, dryRun={}", chartRef, namespace, releaseName, versionOpt, dryRun);
                LOG.info("Passing ars: chartWithVersionArg='{}', releaseName='{}', namespace='{}', kubeconfig='{}', timeoutSec={}, wait={}, atomic={}, dryRun={}'",
                        chartWithVersionArg, releaseName, namespace, (kubeconfig != null ? "[PROVIDED]" : "[NULL]"), timeoutSec, wait, atomic, dryRun);
                final boolean createNamespace = helmShouldCreateNamespace(namespace);
                // Log an equivalent helm CLI for debugging/audit (values shown as map if present)
                {
                    List<String> parts = new ArrayList<>();
                    parts.add("helm");
                    parts.add("install");
                    // helper inline escaping
                    java.util.function.Function<String,String> q = s -> {
                        if (s == null) return "''";
                        return "'" + s.replace("'", "'\"'\"'") + "'";
                    };

                    parts.add(q.apply(releaseName));
                    parts.add(q.apply(chartRef));
                    if (versionOpt != null && !versionOpt.isBlank()) {
                        parts.add("--version");
                        parts.add(q.apply(versionOpt.trim()));
                    }

                    parts.add("--namespace");
                    parts.add(q.apply(namespace));
                    if (createNamespace) parts.add("--create-namespace");

                    if (wait) parts.add("--wait");
                    if (atomic) parts.add("--atomic");

                    parts.add("--timeout");
                    parts.add(q.apply(timeoutSec + "s"));

                    if (dryRun) parts.add("--dry-run");

                    if (kubeconfig != null && !kubeconfig.isBlank()) {
                        // avoid logging sensitive kubeconfig path/contents
                        parts.add("--kubeconfig");
                        parts.add("'[REDACTED]'");
                    }

                    String repoCfg = pathConfiguration.repositoriesConfig().getFileName().toString();
                    if (repoCfg != null && !repoCfg.isBlank()) {
                        parts.add("--repository-config");
                        parts.add(q.apply(repoCfg));
                    }

                    // indicate that values are provided (don't inline potentially large/secret YAML)
                    if (finalValues != null && !finalValues.isEmpty()) {
                        parts.add("--values");
                        parts.add("'-'"); // implies values via stdin / file
                    }

                    String cmdLine = String.join(" ", parts);
                    LOG.info("Equivalent helm command: {}", cmdLine);
                    if (finalValues != null && !finalValues.isEmpty()) {
                        // Keys only: the values hold the release's passwords (LDAP bind, OIDC client secret).
                        LOG.info("Helm values (top-level keys): {}", finalValues.keySet());
                    }
                }
                return helmClient.install(
                        chartRef,
                        versionOpt,
                        resolveChartRef(chartName,repoIdOpt).isOci,
                        releaseName,
                        namespace,
                        pathConfiguration.repositoriesConfig(),
                        kubeconfig,
                        finalValues,
                        timeoutSec,
                        /*createNs*/ createNamespace,
                        /*wait*/ wait,
                        /*atomic*/ atomic,
                        /*dryRun*/ dryRun
                );
            } catch (IllegalStateException alreadyExists) {
                final String msg = alreadyExists.getMessage();
                final String msgLower = msg != null ? msg.toLowerCase(Locale.ROOT) : "";
                if (msgLower.contains("already exists") || msgLower.contains("cannot re-use a name that is still in use")) {
                    LOG.warn("Install reported '{}', switching to upgrade.", msg);
                    return upgradeOrRecover(releaseName, namespace, kubeconfig, dryRun, upgrade, freshInstall, operationLog);
                }
                throw alreadyExists;
            }
        } else {
            LOG.info("Release exists → upgrade: chartRef={}, ns={}, name={}, version={}, dryRun={}", chartRef, namespace, releaseName, versionOpt, dryRun);
            return upgradeOrRecover(releaseName, namespace, kubeconfig, dryRun, upgrade, freshInstall, operationLog);
        }
    }

    /**
     * Helm's {@code errPending}: the release's last revision is still pending-install, pending-upgrade or
     * pending-rollback. Helm 3.16 raises it whatever the earlier revisions are, so a release can be serving
     * from a deployed revision and still return it.
     */
    static final String ERR_PENDING = "another operation (install/upgrade/rollback) is in progress";

    /** Helm's {@code ErrNoDeployedReleases}. */
    static final String ERR_NO_DEPLOYED = "has no deployed releases";

    /**
     * Runs the upgrade and, when Helm refuses it because of the release's state, recovers only as far as the
     * release's history makes safe (see the canonical {@code deployOrUpgrade}).
     */
    private Release upgradeOrRecover(String releaseName, String namespace, String kubeconfig, boolean dryRun,
                                     Supplier<Release> upgrade, Supplier<Release> freshInstall,
                                     Consumer<String> operationLog) {
        try {
            return upgrade.get();
        } catch (RuntimeException upErr) {
            final boolean pending = messageContains(upErr, ERR_PENDING);
            if (!pending && !messageContains(upErr, ERR_NO_DEPLOYED)) {
                throw upErr;
            }
            final String release = namespace + "/" + releaseName;
            stopIfCanceled(release, upErr);

            final HelmReleaseHistory history;
            try {
                history = HelmReleaseHistory.of(helmClient.history(releaseName, namespace, kubeconfig));
            } catch (RuntimeException historyErr) {
                IllegalStateException failure = new IllegalStateException("The upgrade of release " + release
                        + " failed (" + upErr.getMessage() + ") and its Helm history could not be read, so nothing "
                        + "was changed: " + historyErr.getMessage(), historyErr);
                failure.addSuppressed(upErr);
                throw failure;
            }
            final HelmReleaseHistory.Revision last = history.last();
            final HelmReleaseHistory.Revision good = history.lastGood();

            // A first install that never completed: nothing has served yet, so starting over loses nothing.
            if (last != null && !history.everDeployed()
                    && (last.is(HelmReleaseHistory.PENDING_INSTALL) || last.is(HelmReleaseHistory.FAILED))) {
                if (dryRun) {
                    if (pending) {
                        throw new IllegalStateException("Release " + release + " has an install that is still "
                                + "pending (revision " + last.number() + " " + last.status() + ") and has never been "
                                + "deployed. Nothing was changed. Wait for that install to finish, or resume the "
                                + "operation that started it.", upErr);
                    }
                    // Validates the chart the real step will install once the release is cleared.
                    stopIfCanceled(release, upErr);
                    return freshInstall.get();
                }
                stopIfCanceled(release, upErr);
                report(operationLog, "Release " + release + " has never been deployed (revision " + last.number()
                        + " is " + last.status() + "): uninstalling it before installing it again.");
                boolean uninstalled = false;
                try {
                    helmClient.uninstall(releaseName, namespace, kubeconfig);
                    uninstalled = true;
                } catch (Exception ue) {
                    report(operationLog, "Uninstalling the never-deployed release " + release + " failed ("
                            + ue.getMessage() + "); installing it anyway.");
                }
                // The uninstall cannot be interrupted, so a cancel may have come in meanwhile: the operator asked
                // to stop, so do not start an install that can wait for up to the full Helm timeout.
                if (Thread.currentThread().isInterrupted()) {
                    String canceled = (uninstalled ? "Uninstalled" : "Tried to uninstall")
                            + " the never-deployed release " + release + "; it was not installed again because the "
                            + "operation was canceled.";
                    report(operationLog, canceled);
                    throw new IllegalStateException(canceled, upErr);
                }
                return freshInstall.get();
            }

            // An upgrade or rollback cut short (server restart, cancel) while an earlier revision still serves.
            if (last != null && good != null
                    && (last.is(HelmReleaseHistory.PENDING_UPGRADE) || last.is(HelmReleaseHistory.PENDING_ROLLBACK))) {
                final String interrupted = "Release " + release + " has an interrupted "
                        + (last.is(HelmReleaseHistory.PENDING_UPGRADE) ? "upgrade" : "rollback")
                        + " (revision " + last.number() + " " + last.status() + "); roll back to revision "
                        + good.number() + " or wait for it to finish.";
                if (dryRun) {
                    throw new IllegalStateException(interrupted + " Nothing was changed.", upErr);
                }
                if (HelmOperationRegistry.isRunningElsewhere(namespace, releaseName)) {
                    throw new IllegalStateException(interrupted + " A Helm operation that KDPS started on this "
                            + "release is still running.", upErr);
                }
                // The rollback restarts the release on its previous configuration and drops the interrupted
                // change. A caller with no operation log (a catalog saved to the release, a REST call) would get
                // back a plain success with no sign of either, so the operator decides instead.
                if (operationLog == null) {
                    throw new IllegalStateException(interrupted + " Nothing was changed: roll it back from Revision "
                            + "history on the Releases page, then try again.", upErr);
                }
                stopIfCanceled(release, upErr);
                report(operationLog, "Release " + release + " has an interrupted operation (revision "
                        + last.number() + " is " + last.status() + ") while revision " + good.number() + " is "
                        + good.status() + ". No Helm operation started by KDPS is running on it: rolling back to "
                        + "revision " + good.number() + ", then retrying the upgrade once.");
                try {
                    helmClient.rollback(releaseName, namespace, good.number(), kubeconfig);
                } catch (RuntimeException rollbackErr) {
                    throw new IllegalStateException("Rolling release " + release + " back to revision "
                            + good.number() + " failed, so the upgrade was not retried: " + rollbackErr.getMessage(),
                            rollbackErr);
                }
                final String rolledBack = "Rolled back release " + release + " to revision " + good.number();
                // The rollback waits for the release to be ready and cannot be interrupted, so a cancel may have
                // come in meanwhile: the operator asked to stop, so do not start the upgrade again.
                if (Thread.currentThread().isInterrupted()) {
                    String canceled = rolledBack + "; the upgrade was not retried because the operation was canceled.";
                    report(operationLog, canceled);
                    throw new IllegalStateException(canceled, upErr);
                }
                report(operationLog, rolledBack + "; retrying the upgrade.");
                try {
                    return upgrade.get();
                } catch (RuntimeException retryErr) {
                    throw new IllegalStateException("The upgrade of release " + release + " failed again after "
                            + "rolling back to revision " + good.number() + ": " + retryErr.getMessage(), retryErr);
                }
            }

            String state = last == null ? "an empty Helm history" : "its last " + last;
            throw new IllegalStateException("The upgrade of release " + release + " failed (" + upErr.getMessage()
                    + "). With " + state + ", KDPS has no safe way to recover it, so nothing was changed."
                    + (history.everDeployed()
                        ? " A release that has been deployed is only uninstalled when you confirm it from the "
                          + "Releases page."
                        : ""), upErr);
        }
    }

    /**
     * Refuses to start a recovery action once the operation was canceled: the cancel interrupts the thread running
     * the Helm calls, but each call it makes runs to its end, so this is checked before every new one.
     */
    private static void stopIfCanceled(String release, RuntimeException upErr) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("The upgrade of release " + release + " was canceled; nothing was "
                    + "rolled back or uninstalled. Helm reported: " + upErr.getMessage(), upErr);
        }
    }

    /** True when the failure, or one of its causes, carries Helm's error text. */
    private static boolean messageContains(Throwable e, String helmError) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.toLowerCase(Locale.ROOT).contains(helmError)) {
                return true;
            }
        }
        return false;
    }

    /** Logs a recovery action and hands it to the operation's log, where the operator sees it. */
    private static void report(Consumer<String> operationLog, String message) {
        LOG.warn(message);
        if (operationLog != null) {
            try {
                operationLog.accept(message);
            } catch (RuntimeException e) {
                LOG.debug("Could not write to the operation log: {}", e.toString());
            }
        }
    }

    /* --------------------------- Repo login (restored) --------------------------- */
    /**
     * Authenticates against the repository referenced by {@code repoIdOpt} before a deploy.
     * For HTTP repos this updates the local repositories.yaml; for OCI it performs a registry login.
     *
     * @param req       the deploy request whose chart reference may be used for logging
     * @param repoIdOpt optional repository ID; when {@code null} or blank no login is performed
     */
    public void repoLogin(HelmDeployRequest req, String repoIdOpt){
        String chartReference = req.getChart();
        if (repoIdOpt != null && !repoIdOpt.isBlank()) {
            HelmRepoEntity repository = repositoryService.get(repoIdOpt);
            if (repository == null) {
                throw new IllegalArgumentException("Unknown repository: " + repoIdOpt);
            }
            LOG.info("Using repoId: {} for repo login", repoIdOpt);

            if ("HTTP".equalsIgnoreCase(repository.getType())) {
                repositoryService.ensureHttpRepo(repository.getId());
            } else {
                repositoryService.ociLogin(repository.getId());
                LOG.info("Using OCI registry for chart: {}", chartReference);
            }
        }
    }

    /* --------------------------- Helpers --------------------------- */

    private static class RepoResolution {
        final String chartRef;
        final boolean isOci;
        RepoResolution(String chartRef, boolean isOci) {
            this.chartRef = chartRef;
            this.isOci = isOci;
        }
    }

    private RepoResolution resolveChartRef(String chartName, String repoIdOpt) {
        String chartRef = chartName;
        boolean isOci = false;

        if (repoIdOpt != null && !repoIdOpt.isBlank()) {
            HelmRepoEntity repository = repositoryService.get(repoIdOpt);
            if (repository == null) {
                throw new IllegalArgumentException("Unknown repository: " + repoIdOpt);
            }

            if ("HTTP".equalsIgnoreCase(repository.getType())) {
                repositoryService.ensureHttpRepo(repository.getId());
                if (!chartRef.contains("/")) {
                    chartRef = repository.getName() + "/" + chartRef; // e.g. bitnami/trino
                }
            } else {
                // OCI
                isOci = true;
                repositoryService.ociLogin(repository.getId());
                String baseUrl = repository.getUrl();
                if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                if (!chartRef.startsWith("oci://")) {
                    chartRef = "oci://" + baseUrl + "/" + chartRef;
                }
            }
        }

        if (isOci && chartRef.contains(":")) {
            int lastSlash = chartRef.lastIndexOf('/') ;
            int lastColon = chartRef.lastIndexOf(':');
            if (lastColon > lastSlash) {
                String withoutTag = chartRef.substring(0, lastColon);
                LOG.warn("Stripped tag from OCI chart ref '{}' -> '{}' (version will be provided via --version).", chartRef, withoutTag);
                chartRef = withoutTag;
            }
        }

        return new RepoResolution(chartRef, isOci);
    }

    private String appendVersionArg(String chartRef, String versionOpt) {
        if (versionOpt == null || versionOpt.isBlank()) return chartRef;
        return chartRef + " --version " + versionOpt.trim();
    }

    // put near class top
    private static final java.util.regex.Pattern LIST_SEGMENT =
            java.util.regex.Pattern.compile("^(?<key>[^\\[\\]]+)(\\[(?<idx>\\d+)])?$");

    /**
     * Chart value keys whose immediate sub-maps are keyed by FILENAME (the keys contain dots that
     * are part of the filename, e.g. {@code import_datasources.yaml}, {@code superset_config.py}),
     * not dot-paths. Their sub-maps must be carried through {@code expandDotPaths} verbatim so the
     * filename keys are preserved and the chart can {@code tpl} each value as a string.
     */
    private static final java.util.Set<String> LITERAL_KEY_MAPS =
            java.util.Set.of("extraConfigs", "configOverrides", "configOverridesFiles");

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyOverrides(Map<String, Object> values, Map<String, String> overrideOptions) {
        Map<String, Object> result = (values == null) ? new HashMap<>() : new HashMap<>(values);
        if (overrideOptions == null || overrideOptions.isEmpty()) return result;

        for (Map.Entry<String, String> e : overrideOptions.entrySet()) {
            final String compoundKey = e.getKey();
            final String rawValue = e.getValue();
            if (compoundKey == null || compoundKey.isBlank()) continue;

            String[] parts = compoundKey.split("\\.");
            Object cursor = result;

            for (int i = 0; i < parts.length; i++) {
                boolean last = (i == parts.length - 1);
                var m = LIST_SEGMENT.matcher(parts[i]);
                if (!m.matches()) continue;

                String key = m.group("key");
                String idxStr = m.group("idx");

                // ensure we're at a map
                if (!(cursor instanceof Map)) {
                    cursor = new HashMap<String, Object>();
                }
                Map<String, Object> map = (Map<String, Object>) cursor;

                if (idxStr == null) {
                    // plain map segment
                    if (last) {
                        map.put(key, parseValue(rawValue));
                    } else {
                        Object next = map.get(key);
                        if (!(next instanceof Map) && !(next instanceof List)) {
                            // default to Map; if next segment is a list we'll convert then
                            next = new HashMap<String, Object>();
                            map.put(key, next);
                        }
                        cursor = next;
                    }
                } else {
                    // list segment key[NN]
                    int idx = Integer.parseInt(idxStr);
                    Object next = map.get(key);
                    if (!(next instanceof List)) {
                        next = new ArrayList<>();
                        map.put(key, next);
                    }
                    List<Object> list = (List<Object>) next;
                    while (list.size() <= idx) list.add(null);

                    if (last) {
                        list.set(idx, parseValue(rawValue));
                    } else {
                        Object elem = list.get(idx);
                        if (!(elem instanceof Map)) {
                            elem = new HashMap<String, Object>();
                            list.set(idx, elem);
                        }
                        cursor = elem;
                    }
                }
            }
        }
        LOG.info("Applied {} override option(s) to values (lists supported).", overrideOptions.size());
        return result;
    }

    /**
     * Normalize a map that may contain dot-delimited keys into a proper tree of nested maps.
     * This allows existing override helpers to continue using strings like
     * "prometheus.ingress.enabled" while still shipping the structured YAML the Helm chart expects.
     */
    @SuppressWarnings("unchecked")
    // package-private for unit testing, and for the GitOps commit, which writes the values Helm would get
    static Map<String, Object> expandDotPaths(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            // Each dot-path is inserted into the nested map tree so Helm sees a proper structure.
            insertNested(result, entry.getKey(), entry.getValue());
        }
        return result;
    }

    /**
     * Insert a single dot-delimited key/value pair into the nested target map.
     * Intermediate nodes are created as {@link LinkedHashMap} instances.
     */
    @SuppressWarnings("unchecked")
    private static void insertNested(Map<String, Object> target, String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        String[] parts;
        if (key.contains("/")) {
            /*
             * Labels and annotations often contain slashes (e.g. security.clemlab.com/needs-keytab)
             * and must remain a single key. Avoid expanding dotted paths within keys that already
             * use slash separators to prevent converting them into nested maps.
             */
            parts = new String[]{key};
        } else {
            parts = key.split("\\.");
        }
        // Each part may carry a Helm-style list-index suffix (e.g. customCAs[0]).
        // Without honoring it here, a binding path like `certificates.customCAs[0].secret`
        // ends up as a literal map key `customCAs[0]` in the final values — produces
        // structurally wrong YAML and the chart silently sees no customCAs list.
        // applyOverrides already handles this; mirror its LIST_SEGMENT logic here.
        Object cursor = target;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isBlank()) {
                continue;
            }
            boolean last = (i == parts.length - 1);
            java.util.regex.Matcher m = LIST_SEGMENT.matcher(part);
            String mapKey;
            String idxStr;
            if (m.matches()) {
                mapKey = m.group("key");
                idxStr = m.group("idx");
            } else {
                // Annotation-style keys (slash separators) won't match LIST_SEGMENT;
                // treat them as a plain map key with no index.
                mapKey = part;
                idxStr = null;
            }
            if (!(cursor instanceof Map)) {
                // Caller guarantees target is a Map; defensive reset if a previous
                // segment's bookkeeping went sideways.
                cursor = new LinkedHashMap<String, Object>();
            }
            Map<String, Object> map = (Map<String, Object>) cursor;
            if (idxStr == null) {
                if (last) {
                    // File-content maps (extraConfigs, configOverrides, …) are keyed by FILENAME
                    // (e.g. "import_datasources.yaml", "superset_config.py"). Their keys' dots are
                    // part of the filename, NOT path separators — expanding them would turn
                    // extraConfigs."import_datasources.yaml" into {import_datasources:{yaml:…}} and
                    // break charts that `tpl` each value as a string. Keep such sub-maps literal.
                    if (LITERAL_KEY_MAPS.contains(mapKey) && value instanceof Map) {
                        map.put(mapKey, value);
                    } else {
                        map.put(mapKey, expandIfNeeded(value));
                    }
                } else {
                    Object next = map.get(mapKey);
                    if (!(next instanceof Map) && !(next instanceof List)) {
                        next = new LinkedHashMap<String, Object>();
                        map.put(mapKey, next);
                    }
                    cursor = next;
                }
            } else {
                int idx = Integer.parseInt(idxStr);
                Object next = map.get(mapKey);
                if (!(next instanceof List)) {
                    next = new ArrayList<Object>();
                    map.put(mapKey, next);
                }
                List<Object> list = (List<Object>) next;
                while (list.size() <= idx) list.add(null);
                if (last) {
                    list.set(idx, expandIfNeeded(value));
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

    @SuppressWarnings("unchecked")
    private static Object expandIfNeeded(Object value) {
        if (value instanceof Map) {
            return expandDotPaths((Map<String, Object>) value);
        }
        return value;
    }


    /** Types an override like {@code helm --set} ({@link HelmSetValues}). */
    private Object parseValue(String rawValue) {
        return HelmSetValues.typed(rawValue);
    }


    /**
     * Returns the underlying {@link HelmClient} for advanced or low-level Helm operations.
     *
     * @return the Helm client in use by this service
     */
    public HelmClient helm() {
        return this.helmClient;
    }

    /**
     * Best-effort chart version lookup via `helm show chart`.
     * Returns null on error or if version cannot be determined.
     */
    public String resolveChartVersion(String chartRef, String versionOpt) {
        if (chartRef == null || chartRef.isBlank()) return null;
        try {
            String yaml = helmClient.showChart(chartRef, versionOpt, pathConfiguration.repositoriesConfig());
            if (yaml == null || yaml.isBlank()) return null;
            Map<String,Object> parsed = new ObjectMapper(new YAMLFactory()).readValue(yaml, Map.class);
            Object v = parsed.get("version");
            return v != null ? String.valueOf(v) : null;
        } catch (Exception e) {
            LOG.warn("Failed to resolve chart version for {}: {}", chartRef, e.toString());
            return null;
        }
    }

    /** In-memory cache of {chart, version, repoId} -> appVersion. Helm show chart is
     *  slow (OCI / HTTP fetch) so we cache aggressively. TTL is generous because chart
     *  metadata only changes when the operator pushes a new chart. */
    private static final Map<String, AppVersionCacheEntry> APP_VERSION_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long APP_VERSION_TTL_MS = 10 * 60 * 1000L; // 10 min

    private static class AppVersionCacheEntry {
        final String value;
        final long expiresAt;
        AppVersionCacheEntry(String value, long expiresAt) { this.value = value; this.expiresAt = expiresAt; }
    }

    /**
     * Resolve a chart's {@code appVersion} (the component version of what's
     * actually deployed — e.g. GitLab 17.11.2 inside chart 8.11.2) using
     * {@code helm show chart}. Result is cached for {@link #APP_VERSION_TTL_MS}
     * ms because the underlying CLI call is slow. Best-effort: returns null
     * on any failure (network, missing repo login, missing chart, …).
     *
     * @param chartName chart name as declared in service.json (e.g. "gitlab")
     * @param repoIdOpt optional repository id; resolves the OCI/HTTP prefix
     * @param versionOpt optional chart version pin
     */
    public String resolveChartAppVersion(String chartName, String repoIdOpt, String versionOpt) {
        if (chartName == null || chartName.isBlank()) return null;
        String cacheKey = chartName + "|" + (repoIdOpt == null ? "" : repoIdOpt) + "|" + (versionOpt == null ? "" : versionOpt);
        long now = System.currentTimeMillis();
        AppVersionCacheEntry cached = APP_VERSION_CACHE.get(cacheKey);
        if (cached != null && cached.expiresAt > now) {
            return cached.value;
        }
        // Build a list of candidate repo IDs to try in order: the declared
        // defaultRepo first, then every other repo we know about. Some catalogs
        // declare a defaultRepo string that doesn't match the actual stored
        // repo id (legacy entries), so falling back to "try them all" lets
        // appVersion resolve anyway.
        List<String> repoCandidates = new ArrayList<>();
        if (repoIdOpt != null && !repoIdOpt.isBlank()) repoCandidates.add(repoIdOpt);
        try {
            for (HelmRepoEntity r : repositoryService.list()) {
                if (r.getId() != null && !repoCandidates.contains(r.getId())) {
                    repoCandidates.add(r.getId());
                }
            }
        } catch (Exception ignore) {
            // continue with whatever we have
        }
        // Try chart name as-is plus, if it contains '/', the bare last segment.
        // service.json values like "trinodb/trino" or "apache/superset" prefix
        // an HTTP-repo-style vendor that doesn't apply to an OCI repo — but
        // the bare "trino"/"superset" usually still resolves there.
        List<String> chartCandidates = new ArrayList<>();
        chartCandidates.add(chartName);
        int slash = chartName.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < chartName.length()) {
            String bare = chartName.substring(slash + 1);
            if (!chartCandidates.contains(bare)) chartCandidates.add(bare);
        }
        String appVersion = null;
        outer:
        for (String chart : chartCandidates) {
            for (String repoId : repoCandidates) {
                try {
                    RepoResolution rr = resolveChartRef(chart, repoId);
                    String yaml = helmClient.showChart(rr.chartRef, versionOpt, pathConfiguration.repositoriesConfig());
                    if (yaml != null && !yaml.isBlank()) {
                        Map<String, Object> parsed = new ObjectMapper(new YAMLFactory()).readValue(yaml, Map.class);
                        Object v = parsed.get("appVersion");
                        if (v != null) {
                            appVersion = String.valueOf(v);
                            break outer;
                        }
                    }
                } catch (Exception e) {
                    LOG.debug("appVersion lookup miss for {} via repo {}: {}", chart, repoId, e.toString());
                }
            }
        }
        APP_VERSION_CACHE.put(cacheKey, new AppVersionCacheEntry(appVersion, now + APP_VERSION_TTL_MS));
        return appVersion;
    }

    /** Turn a URL like https://registry.clemlab.com/whatever into 'registry.clemlab.com' */
    private static String normalizeRegistryServer(String url) {
        if (url == null) return "";
        String u = url.trim();
        // Strip scheme
        if (u.startsWith("http://")) u = u.substring(7);
        else if (u.startsWith("https://")) u = u.substring(8);
        // Strip path
        int slash = u.indexOf('/');
        if (slash > 0) u = u.substring(0, slash);
        // Strip trailing slash/whitespace
        return u.replaceAll("/+$", "").trim();
    }

    /**
     * Creates or refreshes a {@code kubernetes.io/dockerconfigjson} imagePullSecret derived from
     * the credentials stored for the given repository, and optionally patches service accounts.
     *
     * @param repoId                 the repository ID whose credentials will populate the secret
     * @param namespace              the Kubernetes namespace where the secret will be created
     * @param secretName             the desired name of the imagePullSecret
     * @param serviceAccountsToPatch list of service account names to patch with the secret; may be {@code null}
     */
    public void ensureImagePullSecretFromRepo(String repoId,
                                              String namespace,
                                              String secretName,
                                              List<String> serviceAccountsToPatch) {
        Objects.requireNonNull(repoId, "repoId");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(secretName, "secretName");
        if (serviceAccountsToPatch == null) serviceAccountsToPatch = List.of();

        HelmRepoEntity repoEntity = repositoryService.mustGet(repoId);
        final String url = repoEntity.getUrl();
        final String registryServer = normalizeRegistryServer(url); // e.g. registry.clemlab.com
        final String username = repoEntity.getUsername();
        final String password = repositoryService.readPlainSecret(repoEntity.getSecretRef());

        // If the repo is marked anonymous, we still create a secret only if creds exist; else skip
        if (repositoryService.isAnonymous(repoEntity) && (username == null || username.isBlank() || password == null || password.isBlank())) {
            LOG.info("Repo {} is anonymous and no creds found → skipping imagePullSecret creation.", repoId);
            return;
        }
        LOG.info("Ensuring imagePullSecret '{}' in namespace '{}' for repoId '{}' (registry server: '{}')",
                secretName, namespace, repoId, registryServer);
        KubernetesService kubernetesService = KubernetesService.get(this.viewContext);
        kubernetesService.ensureImagePullSecretWithUsernameAndPassword(repoId,namespace,secretName,username,password,registryServer,serviceAccountsToPatch);
    }

    /**
     * Retrieves the default {@code values.yaml} for a chart and returns it as a parsed map.
     *
     * @param chartName  the chart name or OCI reference
     * @param repoIdOpt  optional repository ID used for authentication; may be {@code null}
     * @param versionOpt optional chart version constraint; may be {@code null}
     * @return parsed values map, or an empty map if no values could be retrieved
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> showValuesAsMap(String chartName, String repoIdOpt, String versionOpt) {
        return this.repositoryService.showValuesAsMap(chartName,repoIdOpt,versionOpt);
    }

    /**
     * Returns the {@link HelmRepositoryService} used by this service for repository management.
     *
     * @return the repository service instance
     */
    public HelmRepositoryService getRepositoryService(){
        return this.repositoryService;
    }
}
