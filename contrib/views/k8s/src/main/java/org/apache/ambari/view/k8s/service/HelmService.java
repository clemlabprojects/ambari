package org.apache.ambari.view.k8s.service;

import com.marcnuri.helm.Release;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.service.helm.HelmClientDefault;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final HelmRepositoryService repositoryService;
    private final PathConfig pathConfiguration;

    public HelmService(ViewContext ctx) {
        this(ctx, new HelmClientDefault());
    }

    public HelmService(ViewContext ctx, HelmClient helm) {
        this.viewContext = ctx;
        this.helmClient = helm;
        this.repositoryService = new HelmRepositoryService(ctx, helm);
        this.pathConfiguration = repositoryService.paths();
    }

    public List<Release> list(String namespace, String kubeconfig) {
        LOG.info("Listing Helm releases in namespace: '{}'", namespace);
        return helmClient.list(namespace, kubeconfig, false);
    }

    public void uninstall(String namespace, String name, String kubeconfig) {
        helmClient.uninstall(name, namespace, kubeconfig);
    }

    public void rollback(String namespace, String name, int revision, String kubeconfig) {
        helmClient.rollback(name, namespace, revision, kubeconfig);
    }

    /* --------------------------- Public entry points --------------------------- */

    // Original entry point kept (defaults + dryRun=false)
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
        int timeoutSeconds = 900;
        boolean wait = true;
        if(dryRun){
            wait = false;
        }
        boolean atomic = false;
        return deployOrUpgrade(chartName, releaseName, namespace, values, overrideOptions, kubeconfig, repoIdOpt, versionOpt, timeoutSeconds, wait, atomic, dryRun);
    }

    // Full canonical implementation
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
        LOG.info("HelmService.deployOrUpgrade called with chartName='{}', releaseName='{}', namespace='{}', repoId='{}', version='{}', timeoutSec={}, wait={}, atomic={}, dryRun={}",
                chartName, releaseName, namespace, repoIdOpt, versionOpt, timeoutSec, wait, atomic, dryRun);
        final RepoResolution rr = resolveChartRef(chartName, repoIdOpt);
        LOG.info("Resolved chart reference: {}", rr.chartRef);
        Map<String, Object> finalValues = applyOverrides(values, overrideOptions);

        final String chartRef = rr.chartRef; // never ":version" for OCI
        final String chartWithVersionArg = appendVersionArg(chartRef, versionOpt);

        LOG.info("Upsert Helm release: ns={}, name={}, chartRef={}, repoId={}, version={}, dryRun={}, wait={}, atomic={}",
                namespace, releaseName, chartRef, repoIdOpt, versionOpt, dryRun, wait, atomic);

        boolean releaseExists = false;
        try {
            releaseExists = helmClient.list(namespace, kubeconfig, true).stream()
                    .anyMatch(r -> releaseName.equals(r.getName()));
        } catch (Exception e) {
            LOG.warn("Could not list releases to detect existence, will try install then upgrade fallback: {}", e.toString());
        }

        if (!releaseExists) {
            try {
                LOG.info("Release doesn't exist → install: chartRef={}, ns={}, name={}, version={}, dryRun={}", chartRef, namespace, releaseName, versionOpt, dryRun);
                LOG.info("Passing ars: chartWithVersionArg='{}', releaseName='{}', namespace='{}', kubeconfig='{}', timeoutSec={}, wait={}, atomic={}, dryRun={}'",
                        chartWithVersionArg, releaseName, namespace, (kubeconfig != null ? "[PROVIDED]" : "[NULL]"), timeoutSec, wait, atomic, dryRun);
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
                    parts.add("--create-namespace");

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
                        LOG.info("Helm values (map): {}", finalValues);
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
                        /*createNs*/ true,
                        /*wait*/ wait,
                        /*atomic*/ atomic,
                        /*dryRun*/ dryRun
                );
            } catch (IllegalStateException alreadyExists) {
                final String msg = alreadyExists.getMessage();
                if (msg != null && msg.toLowerCase(Locale.ROOT).contains("already exists")) {
                    LOG.warn("Install reported 'already exists', switching to upgrade.");
                    return helmClient.upgrade(
                            chartRef,
                            versionOpt,
                            resolveChartRef(chartName,repoIdOpt).isOci,
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
                }
                throw alreadyExists;
            }
        } else {
            LOG.info("Release exists → upgrade: chartRef={}, ns={}, name={}, version={}, dryRun={}", chartRef, namespace, releaseName, versionOpt, dryRun);
            return helmClient.upgrade(
                        chartRef,
                        versionOpt,
                        resolveChartRef(chartName,repoIdOpt).isOci,
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
        }
    }

    /* --------------------------- Repo login (restored) --------------------------- */
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


    private Object parseValue(String rawValue) {
        if (rawValue == null) return null;
        String t = rawValue.trim();
        if ("true".equalsIgnoreCase(t) || "false".equalsIgnoreCase(t)) {
            return Boolean.valueOf(t);
        }
        try {
            if (t.contains(".")) return Double.valueOf(t);
            return Integer.valueOf(t);
        } catch (NumberFormatException ignored) {
            return rawValue;
        }
    }


    public HelmClient helm() {
        return this.helmClient;
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
        KubernetesService kubernetesService = new KubernetesService(this.viewContext);
        kubernetesService.ensureImagePullSecretWithUsernameAndPassword(repoId,namespace,secretName,username,password,registryServer,serviceAccountsToPatch);
    }

}
