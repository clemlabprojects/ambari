package org.apache.ambari.view.k8s.service;

import com.marcnuri.helm.Release;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.service.helm.HelmClientDefault;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for managing Helm operations including deployments, upgrades, and releases
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
        LOG.info("Listing Helm releases in namespace: '{}', kubeconfig: '{}'", namespace, kubeconfig);
        if (namespace == null) {
            return helmClient.list(null, kubeconfig, false);
        } else {
            return helmClient.list(namespace, kubeconfig, false);
        }
    }

    public void uninstall(String namespace, String name, String kubeconfig) {
        helmClient.uninstall(name, namespace, kubeconfig);
    }

    public void rollback(String namespace, String name, int revision, String kubeconfig) {
        helmClient.rollback(name, namespace, revision, kubeconfig);
    }

    public Release deployOrUpgrade(
            HelmDeployRequest req, String kubeconfig, String repoIdOpt, String versionOpt) {

        // Default values for deployment configuration
        int timeoutSeconds = 900;
        boolean wait = true;
        boolean atomic = false;

        return deployOrUpgrade(req, kubeconfig, repoIdOpt, versionOpt, timeoutSeconds, wait, atomic);
    }

    public Release deployOrUpgrade(HelmDeployRequest req, String kubeconfig, String repoIdOpt, 
                                  String versionOpt, int timeoutSec, boolean wait, boolean atomic) {
        final String namespace = req.getNamespace();
        final String releaseName = req.getReleaseName();
        Map<String, Object> values = req.getValues();
        String chartReference = req.getChart();

        LOG.info("Upsert Helm release: ns={}, name={}, chart={}, repoId={}, version={}",
                namespace, releaseName, chartReference, repoIdOpt, versionOpt);

        // Normalize chartRef if repoId is provided
        if (repoIdOpt != null && !repoIdOpt.isBlank()) {
            HelmRepoEntity repository = repositoryService.get(repoIdOpt);
            if (repository == null) {
                throw new IllegalArgumentException("Unknown repository: " + repoIdOpt);
            }
            if ("HTTP".equalsIgnoreCase(repository.getType())) {
                repositoryService.ensureHttpRepo(repository.getId()); // ensures repo is in repositories.yaml (and login if needed)
                if (!chartReference.contains("/")) {
                    chartReference = repository.getName() + "/" + chartReference; // e.g. bitnami/trino
                }
            } else {
                // OCI
                repositoryService.ociLogin(repository.getId());
                if (!chartReference.startsWith("oci://")) {
                    String baseUrl = repository.getUrl();
                    if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                    chartReference = "oci://" + baseUrl + "/" + chartReference;
                }
            }
        }

        // Decide path based on actual presence of the release
        boolean releaseExists = false;
        try {
            releaseExists = helmClient.list(namespace, kubeconfig, true).stream()
                    .anyMatch(r -> releaseName.equals(r.getName()));
        } catch (Exception e) {
            LOG.warn("Could not list releases to detect existence, will try install then upgrade fallback: {}", e.toString());
        }

        if (!releaseExists) {
            try {
                LOG.info("Release doesn't exist → install: chartRef={}, ns={}, name={}", chartReference, namespace, releaseName);
                return helmClient.install(
                        chartReference, releaseName, namespace,
                        pathConfiguration.repositoriesConfig(), kubeconfig,
                        values, timeoutSec, /*createNs*/ true, /*wait*/ true, /*atomic*/ false, /*dryRun*/ false);
            } catch (IllegalStateException alreadyExists) {
                // Helm sometimes races: if it says "already exists", we immediately upgrade
                final String errorMessage = alreadyExists.getMessage();
                if (errorMessage != null && errorMessage.toLowerCase(Locale.ROOT).contains("already exists")) {
                    LOG.warn("Install reported 'already exists', switching to upgrade.");
                    return helmClient.upgrade(
                            chartReference, releaseName, namespace,
                            pathConfiguration.repositoriesConfig(), kubeconfig,
                            values, timeoutSec, /*wait*/ true, /*atomic*/ false, /*dryRun*/ false);
                }
                throw alreadyExists;
            }
        } else {
            LOG.info("Release exists → upgrade: chartRef={}, ns={}, name={}", chartReference, namespace, releaseName);
            return helmClient.install(
                    chartReference, releaseName, namespace,
                    pathConfiguration.repositoriesConfig(), kubeconfig,
                    values, timeoutSec, /*createNs*/ true, /*wait*/ true, /*atomic*/ false, /*dryRun*/ false);
        }
    }

    public HelmClient helm() { 
        return this.helmClient; 
    }
}
