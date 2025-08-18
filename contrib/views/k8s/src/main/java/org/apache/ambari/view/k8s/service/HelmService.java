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


public class HelmService {

  private final ViewContext ctx;
  private final HelmClient helm;
  private final HelmRepositoryService repos;
  private final PathConfig paths;

  private static final Logger LOG = LoggerFactory.getLogger(HelmService.class);


  public HelmService(ViewContext ctx) {
    this(ctx, new HelmClientDefault());
  }

  public HelmService(ViewContext ctx, HelmClient helm) {
    this.ctx = ctx;
    this.helm = helm;
    this.repos = new HelmRepositoryService(ctx, helm);
    this.paths = repos.paths();
  }

  public List<Release> list(String namespace, String kubeconfig) {
    LOG.info("Listing Helm releases in namespace: '{}', kubeconfig: '{}'", namespace, kubeconfig);
    if (namespace == null) {
      return helm.list(null, kubeconfig, false);
    } else {
      return helm.list(namespace, kubeconfig, false);
    }
  }

  public void uninstall(String namespace, String name, String kubeconfig) {
    helm.uninstall(name, namespace, kubeconfig);
  }

  public void rollback(String namespace, String name, int revision, String kubeconfig) {
    helm.rollback(name, namespace, revision, kubeconfig);
  }

  public Release deployOrUpgrade(
      HelmDeployRequest req, String kubeconfig, String repoIdOpt, String versionOpt) {

    // valeurs par défaut souhaitées
    int     timeoutSec = 900;
    boolean wait       = true;
    boolean atomic     = false;

    return deployOrUpgrade(req, kubeconfig, repoIdOpt, versionOpt, timeoutSec, wait, atomic);
  }

  public Release deployOrUpgrade(HelmDeployRequest req, String kubeconfig, String repoIdOpt, String versionOpt, int timeoutSec, boolean wait, boolean atomic) {
    final String namespace = req.getNamespace();
    final String release   = req.getReleaseName();
    Map<String,Object> values = req.getValues();
    String chartRef  = req.getChart();

    LOG.info("Upsert Helm release: ns={}, name={}, chart={}, repoId={}, version={}",
      namespace, release, chartRef, repoIdOpt, versionOpt);

    // Normalize chartRef if repoId is provided
    if (repoIdOpt != null && !repoIdOpt.isBlank()) {
      HelmRepoEntity repo = repos.get(repoIdOpt);
      if (repo == null) {
        throw new IllegalArgumentException("Unknown repository: " + repoIdOpt);
      }
      if ("HTTP".equalsIgnoreCase(repo.getType())) {
        repos.ensureHttpRepo(repo.getId()); // ensures repo is in repositories.yaml (and login if needed)
        if (!chartRef.contains("/")) {
          chartRef = repo.getName() + "/" + chartRef; // e.g. bitnami/trino
        }
      } else {
        // OCI
        repos.ociLogin(repo.getId());
        if (!chartRef.startsWith("oci://")) {
          String base = repo.getUrl();
          if (base.endsWith("/")) base = base.substring(0, base.length()-1);
          chartRef = "oci://" + base + "/" + chartRef;
        }
      }
    }

    // Decide path based on actual presence of the release
    boolean exists = false;
    try {
      exists = helm.list(namespace, kubeconfig, true).stream()
        .anyMatch(r -> release.equals(r.getName()));
    } catch (Exception e) {
      LOG.warn("Could not list releases to detect existence, will try install then upgrade fallback: {}", e.toString());
    }

    if (!exists) {
      try {
        LOG.info("Release doesn't exist → install: chartRef={}, ns={}, name={}", chartRef, namespace, release);
        return helm.install(
          chartRef, release, namespace,
          paths.repositoriesConfig(), kubeconfig,
          values, timeoutSec, /*createNs*/ true, /*wait*/ true, /*atomic*/ false, /*dryRun*/ false);
      } catch (IllegalStateException alreadyExists) {
        // Helm sometimes races: if it says "already exists", we immediately upgrade
        final String msg = alreadyExists.getMessage();
        if (msg != null && msg.toLowerCase(Locale.ROOT).contains("already exists")) {
          LOG.warn("Install reported 'already exists', switching to upgrade.");
          return helm.upgrade(
            chartRef, release, namespace,
            paths.repositoriesConfig(), kubeconfig,
            values, timeoutSec, /*wait*/ true, /*atomic*/ false, /*dryRun*/ false);
        }
        throw alreadyExists;
      }
    } else {
      LOG.info("Release exists → upgrade: chartRef={}, ns={}, name={}", chartRef, namespace, release);
      return helm.install(
          chartRef, release, namespace,
          paths.repositoriesConfig(), kubeconfig,
          values, timeoutSec, /*createNs*/ true, /*wait*/ true, /*atomic*/ false, /*dryRun*/ false);
    }
  }

  public HelmClient helm() { return this.helm; }
}
