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

package org.apache.ambari.view.k8s.service.helm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.marcnuri.helm.*;
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

/**
 * Default implementation of HelmClient interface using helm-java library
 */
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
        LOG.info("Listing releases in namespace: '{}'", namespace);
        var command = Helm.list()
            .withNamespace(namespace)
            .withKubeConfigContents(kubeconfigContents);
        
        if (namespace != null) {
            command = command.withNamespace(namespace);
        } else {
            // IMPORTANT: list across all namespaces if none specified
            command = command.allNamespaces();
        }
        return (deployedOnly ? command.deployed() : command.all()).call();
    }

    @Override
    public Release install(String chartRef, String chartVersion, boolean isOci, String releaseName, String namespace,
                          Path repositoriesConfig, String kubeconfigContents,
                          Map<String, Object> values,
                          int timeoutSeconds, boolean createNamespace, boolean wait, boolean atomic, boolean dryRun) {
        Path valuesFile = writeValues(values);
        try {
            InstallCommand installCommand = Helm.install(chartRef)
                .withName(releaseName)
                .withNamespace(namespace)
                .withTimeout(timeoutSeconds)
                .withKubeConfigContents(kubeconfigContents)
                .withValuesFile(valuesFile);

            if (createNamespace) installCommand = installCommand.createNamespace();
            if (atomic) installCommand = installCommand.atomic();
            if (wait) installCommand = installCommand.waitReady();
            // CRITICAL: apply dry-run when requested. Without it the "validate chart (dry-run)" step
            // performs a REAL install — which for a chart with post-install hook jobs (e.g. OpenMetadata's
            // airflow create-user/migrate-db) BLOCKS waiting on those hooks and leaves a stuck
            // pending release, so the next deploy fails with "another operation in progress".
            if (dryRun) installCommand = installCommand.dryRun();

            if (!chartRef.startsWith("oci://")) {
                installCommand = installCommand.withRepositoryConfig(repositoriesConfig);
            }
            return installCommand.call();
        } finally {
            try { 
                Files.deleteIfExists(valuesFile); 
            } catch (Exception ignored) {
                // Ignore cleanup errors
            }
        }
    }

    @Override
    public Release upgrade(String chartRef, String chartVersion, boolean isOci, String releaseName, String namespace,
                           Path repositoriesConfig, String kubeconfigContents,
                           Map<String, Object> values,
                           int timeoutSeconds, boolean wait, boolean atomic, boolean dryRun)
     {
        Path valuesFile = writeValues(values);
        try {
            UpgradeCommand upgradeCommand = Helm.upgrade(chartRef)
                .withName(releaseName)
                .withNamespace(namespace)
                .withTimeout(timeoutSeconds)
                .withKubeConfigContents(kubeconfigContents)
                .withValuesFile(valuesFile);

            if (atomic) upgradeCommand = upgradeCommand.atomic();
            if (wait) upgradeCommand = upgradeCommand.waitReady();
            // Apply dry-run when requested (see install() — a "dry-run" that really installs blocks
            // on post-install hooks and leaves a stuck pending release).
            if (dryRun) upgradeCommand = upgradeCommand.dryRun();

            if (!chartRef.startsWith("oci://")) {
                upgradeCommand = upgradeCommand.withRepositoryConfig(repositoriesConfig);
            }
            return upgradeCommand.call();
        } finally {
            try { 
                Files.deleteIfExists(valuesFile); 
            } catch (Exception ignored) {
                // Ignore cleanup errors
            }
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
     * Checks if a chart (and optionally a version) is listed
     * in the repository pointed to by repositories.yaml.
     *
     * @param repoFile   Path to the repositories.yaml file
     * @param chart      "trino" or "prometheus-community/prometheus"
     * @param version    target version (nullable). If null -> ignore version.
     * @return           true if at least one occurrence found.
     */
    public boolean existsInRepo(Path repoFile, String chart, String version) {
        List<SearchResult> searchResults = searchRepo(repoFile, chart);
        if (version == null || version.isBlank()) {
            return !searchResults.isEmpty();
        }
        return searchResults.stream().anyMatch(result -> version.equals(result.getChartVersion()));
    }

    /**
     * Returns the latest version of the chart present in the repository.
     * @return  ex. "0.18.0" or null if not found
     */
    public String latestVersion(Path repoFile, String chart) {
        return searchRepo(repoFile, chart).stream()
            .max(Comparator.comparing(SearchResult::getChartVersion, this::semverCompare))
            .map(SearchResult::getChartVersion)
            .orElse(null);
    }

    private static Path writeValues(Map<String, Object> values) {
        try {
            Path tempFile = Files.createTempFile("values-", ".yaml");
            new ObjectMapper(new YAMLFactory()).writeValue(tempFile.toFile(), values != null ? values : Map.of());
            return tempFile;
        } catch (Exception e) {
            throw new RuntimeException("Cannot write temporary values.yaml", e);
        }
    }

    // Private helper methods

    private List<SearchResult> searchRepo(Path repoFile, String chart) {
        try {
            return Helm.search()
                .repo()                              // repo subcommand
                .withKeyword(chart)                  // filter
                .withRepositoryConfig(repoFile)      // --repository-config
                .call();                             // execute -> List<SearchResult>
        } catch (Exception e) {
            LOG.warn("helm search repo failed", e);
            return Collections.emptyList();
        }
    }

    /** Simple semver comparator (ex. "1.10.2" > "1.9.9") */
    private int semverCompare(String version1, String version2) {
        String[] partsA = version1.split("\\.");
        String[] partsB = version2.split("\\.");
        for (int i = 0; i < Math.max(partsA.length, partsB.length); i++) {
            int partA = i < partsA.length ? Integer.parseInt(partsA[i].replaceAll("\\D", "")) : 0;
            int partB = i < partsB.length ? Integer.parseInt(partsB[i].replaceAll("\\D", "")) : 0;
            if (partA != partB) return Integer.compare(partA, partB);
        }
        return 0;
    }
    /**
     * Return the *rendered* default values.yaml for a chart as a Map.
     *
     * This is basically `helm show values <chart> --version <v> --repository-config <repo>` wired
     * through helm-java, then parsed from YAML into a Java Map.
     *
     * @param chartRef          Fully resolved chart reference:
     *                          - HTTP repo: "bitnami/trino"
     *                          - OCI repo:  "oci://registry.example.com/trino"
     * @param versionOpt      Optional chart version. If null/blank, Helm will pick latest.
     * @param repoConfigPath Path to repositories.yaml (same one you already use elsewhere).
     * @return Parsed values as a Map<String,Object>. Empty map on error.
     */
    @Override
    public String showValues(String chartRef, String versionOpt, Path repoConfigPath) {
        try {
            // For 0.0.15:
            // - Use Helm.show(String chartRef) for repo/OCI references
            // - Then .values() to target "helm show values"
            ShowCommand showCommand = Helm.show(chartRef);

            ShowCommand.ShowSubcommand valuesCmd = showCommand.values();

            // Version is optional, same semantics as CLI's --version
            if (versionOpt != null && !versionOpt.isBlank()) {
                valuesCmd = valuesCmd.withVersion(versionOpt.trim());
            }

            // helm-java returns the YAML as a String, like "helm show values" would
            String yaml = valuesCmd.call();
            if (yaml == null) {
                LOG.warn("helm show values returned null for chartRef={} version={}", chartRef, versionOpt);
                return "";
            }
            return yaml;
        } catch (Exception e) {
            LOG.warn("Failed to run 'helm show values' for chartRef={} version={}: {}",
                    chartRef, versionOpt, e.toString(), e);
            return "";
        }
    }

    @Override
    public String showChart(String chartRef, String versionOpt, Path repoConfigPath) {
        try {
            ShowCommand showCommand = Helm.show(chartRef);
            ShowCommand.ShowSubcommand chartCmd = showCommand.chart();
            if (versionOpt != null && !versionOpt.isBlank()) {
                chartCmd = chartCmd.withVersion(versionOpt.trim());
            }
            return chartCmd.call();
        } catch (Exception e) {
            LOG.warn("Failed to run 'helm show chart' for chartRef={} version={}: {}",
                    chartRef, versionOpt, e.toString(), e);
            return "";
        }
    }

    @Override
    public void rollback(String releaseName, String namespace, int revision, String kubeconfigContents) {
        Path kubeconfig = writeKubeconfigToTempFile(kubeconfigContents);
        try {
            List<String> cmd = new java.util.ArrayList<>();
            cmd.add(resolveHelmBinary());
            cmd.add("rollback");
            cmd.add(releaseName);
            if (revision > 0) {
                cmd.add(String.valueOf(revision));
            }
            cmd.add("--namespace");
            cmd.add(namespace);
            cmd.add("--kubeconfig");
            cmd.add(kubeconfig.toString());
            cmd.add("--wait");
            String output = runHelmCli(cmd, /*allowFailure*/ false);
            LOG.info("helm rollback succeeded for {}/{} -> revision {}: {}", namespace, releaseName, revision, output.trim());
        } finally {
            try { Files.deleteIfExists(kubeconfig); } catch (Exception ignored) {}
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> history(String releaseName, String namespace, String kubeconfigContents) {
        Path kubeconfig = writeKubeconfigToTempFile(kubeconfigContents);
        try {
            // Cap at 50 even when retention is configured higher — anything older is
            // archaeological and the picker UI tops out at a few pages anyway.
            List<String> cmd = List.of(
                    resolveHelmBinary(), "history", releaseName,
                    "--namespace", namespace,
                    "--kubeconfig", kubeconfig.toString(),
                    "--max", "50",
                    "-o", "json"
            );
            String json = runHelmCli(cmd, /*allowFailure*/ false);
            if (json == null || json.isBlank()) {
                return Collections.emptyList();
            }
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read helm history for " + namespace + "/" + releaseName + ": " + e.getMessage(), e);
        } finally {
            try { Files.deleteIfExists(kubeconfig); } catch (Exception ignored) {}
        }
    }

    /**
     * Resolve the helm CLI binary path. The Ambari server runs with a minimal PATH so we
     * cannot rely on plain "helm"; we probe the standard locations and fall back to PATH.
     */
    private static String resolveHelmBinary() {
        String[] candidates = {"/usr/local/sbin/helm", "/usr/local/bin/helm", "/opt/local/helm/helm"};
        for (String candidate : candidates) {
            if (java.nio.file.Files.isExecutable(java.nio.file.Paths.get(candidate))) {
                return candidate;
            }
        }
        return "helm";
    }

    private static Path writeKubeconfigToTempFile(String contents) {
        if (contents == null || contents.isBlank()) {
            throw new IllegalArgumentException("kubeconfig contents are required for helm CLI invocation");
        }
        try {
            Path tempFile = Files.createTempFile("kubeconfig-", ".yaml");
            Files.writeString(tempFile, contents, java.nio.charset.StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tempFile,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException nonPosix) {
                tempFile.toFile().setReadable(false, false);
                tempFile.toFile().setReadable(true, true);
            }
            return tempFile;
        } catch (Exception e) {
            throw new RuntimeException("Cannot write temporary kubeconfig file: " + e.getMessage(), e);
        }
    }

    private static String runHelmCli(List<String> command, boolean allowFailure) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code != 0 && !allowFailure) {
                throw new RuntimeException("helm command failed (" + code + "): "
                        + String.join(" ", command) + " output=" + out);
            }
            return out;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Failed to run helm command: " + e.getMessage(), e);
        }
    }
}
