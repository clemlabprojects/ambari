package org.apache.ambari.view.k8s.service.helm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.marcnuri.helm.*;
// ajoute (ou fusionne) :
import com.marcnuri.helm.Helm;
import com.marcnuri.helm.SearchCommand;
import com.marcnuri.helm.SearchResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class HelmClientDefault implements HelmClient {

  private static final Logger LOG = LoggerFactory.getLogger(HelmClientDefault.class);

  @Override
  public void ensureHttpRepo(Path repositoriesConfig, String name, URI url, String username, String passwordOrToken) {
    Helm.repo().add()
        .withRepositoryConfig(repositoriesConfig)
        .withName(name)
        .withUrl(url)
        .call();

    Helm.repo().update()
        .withRepositoryConfig(repositoriesConfig)
        .call();
  }

  @Override
  public void ociLogin(String server, String username, String passwordOrToken, Path registryConfig) {
    // helm-java: use withHost(server) (not withServer)
    Helm.registry().login()
        .withHost(server)
        .withUsername(username)
        .withPassword(passwordOrToken)
        .call();
  }

  @Override
  public List<Release> list(String namespace, String kubeconfigContents, boolean deployedOnly) {
    LOG.info("Listing releases in namespace: '{}', kubeconfigContents: '{}'", namespace, kubeconfigContents);
    var cmd = Helm.list()
        .withNamespace(namespace)
        .withKubeConfigContents(kubeconfigContents);
    
    if (namespace != null) {
      cmd = cmd.withNamespace(namespace);
    } else {
      // IMPORTANT: list across all namespaces if none specified
      cmd = cmd.allNamespaces();
    }
    return (deployedOnly ? cmd.deployed() : cmd.all()).call();
  }

  @Override
  public Release install(String chartRef, String releaseName, String namespace,
                        Path repositoriesConfig, String kubeconfigContents,
                        Map<String, Object> values,
                        int timeoutSeconds, boolean createNamespace, boolean wait, boolean atomic, boolean dryRun) {
    Path valuesFile = writeValues(values);
    try {
      InstallCommand cmd = Helm.install(chartRef)
          .withName(releaseName)
          .withNamespace(namespace)
          .withTimeout(timeoutSeconds)
          .withKubeConfigContents(kubeconfigContents)
          .withValuesFile(valuesFile);

      if (createNamespace) cmd = cmd.createNamespace();
      if (atomic)          cmd = cmd.atomic();
      if (wait)            cmd = cmd.waitReady();
      // if (dryRun)       cmd = cmd.dryRun(); // active si votre helm-java le supporte

      if (!chartRef.startsWith("oci://")) {
        cmd = cmd.withRepositoryConfig(repositoriesConfig);
      }
      return cmd.call();
    } finally {
      try { Files.deleteIfExists(valuesFile); } catch (Exception ignored) {}
    }
  }

  @Override
  public Release upgrade(String chartRef, String releaseName, String namespace,
                        Path repositoriesConfig, String kubeconfigContents,
                        Map<String, Object> values,
                        int timeoutSeconds, boolean wait, boolean atomic, boolean dryRun) {
    Path valuesFile = writeValues(values);
    try {
      UpgradeCommand cmd = Helm.upgrade(chartRef)
          .withName(releaseName)
          .withNamespace(namespace)
          .withTimeout(timeoutSeconds)
          .withKubeConfigContents(kubeconfigContents)
          .withValuesFile(valuesFile);

      if (atomic) cmd = cmd.atomic();
      if (wait)   cmd = cmd.waitReady();
      // if (dryRun) cmd = cmd.dryRun(); // si disponible

      if (!chartRef.startsWith("oci://")) {
        cmd = cmd.withRepositoryConfig(repositoriesConfig);
      }
      return cmd.call();
    } finally {
      try { Files.deleteIfExists(valuesFile); } catch (Exception ignored) {}
    }
  }

  @Override
  public void uninstall(String releaseName, String namespace, String kubeconfigContents) {
    Helm.uninstall(releaseName)
        .withNamespace(namespace)
        .withKubeConfigContents(kubeconfigContents)
        .call();
  }

  /**
  * Vérifie si un chart (et optionnellement une version) est listé
  * dans le dépôt pointé par <repositories.yaml>.
  *
  * @param repoFile   Path vers le fichier repositories.yaml
  * @param chart      "trino" ou "prometheus-community/prometheus"
  * @param version    version cible (nullable). Si null → on ignore la version.
  * @return           true si au moins une occurence trouvée.
  */
  public boolean existsInRepo(Path repoFile, String chart, String version) {
    List<SearchResult> hits = searchRepo(repoFile, chart);
    if (version == null || version.isBlank()) {
      return !hits.isEmpty();
    }
    return hits.stream().anyMatch(r -> version.equals(r.getChartVersion()));
  }

  /**
  * Renvoie la version la plus récente du chart présent dans le dépôt.
  * @return  ex. "0.18.0" ou null si introuvable
  */
  public String latestVersion(Path repoFile, String chart) {
    return searchRepo(repoFile, chart).stream()
        .max(Comparator.comparing(SearchResult::getChartVersion, this::semverCompare))
        .map(SearchResult::getChartVersion)
        .orElse(null);
  }

  private static Path writeValues(Map<String, Object> values) {
    try {
      Path tmp = Files.createTempFile("values-", ".yaml");
      new ObjectMapper(new YAMLFactory()).writeValue(tmp.toFile(), values != null ? values : Map.of());
      return tmp;
    } catch (Exception e) {
      throw new RuntimeException("Cannot write temporary values.yaml", e);
    }
  }


  /* ------------------------------------------------------------------ */
  /*  helpers privés                                                    */
  /* ------------------------------------------------------------------ */

  private List<SearchResult> searchRepo(Path repoFile, String chart) {
    try {
      return Helm.search()
          .repo()                              // sous-commande « repo »
          .withKeyword(chart)                  // filtre
          .withRepositoryConfig(repoFile)      // --repository-config
          .call();                             // exécute → List<SearchResult>
    } catch (Exception e) {
      LOG.warn("helm search repo failed", e);
      return Collections.emptyList();
    }
  }

  /** Comparateur semver très simple (ex. "1.10.2" > "1.9.9") */
  private int semverCompare(String v1, String v2) {
    String[] a = v1.split("\\.");
    String[] b = v2.split("\\.");
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      int ai = i < a.length ? Integer.parseInt(a[i].replaceAll("\\D", "")) : 0;
      int bi = i < b.length ? Integer.parseInt(b[i].replaceAll("\\D", "")) : 0;
      if (ai != bi) return Integer.compare(ai, bi);
    }
    return 0;
  }


}
