package org.apache.ambari.view.k8s.service.helm;

import com.marcnuri.helm.Release;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface HelmClient {
  void ensureHttpRepo(Path repositoriesConfig, String name, URI url, String username, String passwordOrToken);
  void ociLogin(String server, String username, String passwordOrToken, Path registryConfig);

  List<Release> list(String namespace, String kubeconfigContents, boolean deployedOnly);


  // ✅ Canonical
  Release install(
      String chartRef, String releaseName, String namespace,
      Path repositoriesConfig, String kubeconfigContents,
      Map<String,Object> values,
      int timeoutSec, boolean createNamespace, boolean wait, boolean atomic, boolean dryRun);

  // ✅ Back-compat (3 booleans) — delegates to canonical (dryRun=false)
  default Release install(
      String chartRef, String releaseName, String namespace,
      Path repositoriesConfig, String kubeconfigContents,
      Map<String,Object> values,
      int timeoutSec, boolean createNamespace, boolean wait, boolean atomic) {
    return install(chartRef, releaseName, namespace, repositoriesConfig, kubeconfigContents,
        values, timeoutSec, createNamespace, wait, atomic, false);
  }

  // ✅ Canonical
  Release upgrade(
      String chartRef, String releaseName, String namespace,
      Path repositoriesConfig, String kubeconfigContents,
      Map<String,Object> values,
      int timeoutSec, boolean wait, boolean atomic, boolean dryRun);

  // ✅ Back-compat (2 booleans) — delegates to canonical (dryRun=false)
  default Release upgrade(
      String chartRef, String releaseName, String namespace,
      Path repositoriesConfig, String kubeconfigContents,
      Map<String,Object> values,
      int timeoutSec, boolean wait, boolean atomic) {
    return upgrade(chartRef, releaseName, namespace, repositoriesConfig, kubeconfigContents,
        values, timeoutSec, wait, atomic, false);
  }


  void uninstall(String releaseName, String namespace, String kubeconfigContents);

  /** helm-java currently lacks rollback; implement later when supported. */
  default void rollback(String releaseName, String namespace, int revision, String kubeconfigContents) {
    throw new UnsupportedOperationException("Rollback not supported by helm-java yet.");
  }
}
