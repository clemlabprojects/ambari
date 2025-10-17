package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.ViewContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration class for managing Helm-related directory paths
 */
public class PathConfig {
    private final Path workDir;
    private final Path repositoriesConfig;
    private final Path registryConfig;

    public PathConfig(ViewContext ctx) {
        String workingDirectory = getProperty(ctx, "k8s.view.working.dir", "/var/lib/ambari/views/work/K8S-VIEW{1.0.0.1}");
        String repositoriesPath = getProperty(ctx, "k8s.view.helm.repositoriesConfig", "");
        String registryPath = getProperty(ctx, "k8s.view.helm.registryConfig", "");

        this.workDir = Paths.get(workingDirectory);
        this.repositoriesConfig = repositoriesPath.isBlank()
            ? workDir.resolve("helm").resolve("repositories.yaml")
            : Paths.get(repositoriesPath);
        this.registryConfig = registryPath.isBlank()
            ? workDir.resolve("helm").resolve("registry").resolve("config.json")
            : Paths.get(registryPath);
    }

    private static String getProperty(ViewContext ctx, String key, String defaultValue) {
        var properties = ctx.getProperties();
        String value = properties != null ? properties.get(key) : null;
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    public void ensureDirs() {
        try {
            Files.createDirectories(workDir);
            Files.createDirectories(repositoriesConfig.getParent());
            Files.createDirectories(registryConfig.getParent());
        } catch (Exception e) {
            throw new RuntimeException("Failed creating helm directories", e);
        }
    }

    public Path workDir() { return workDir; }
    public Path repositoriesConfig() { return repositoriesConfig; }
    public Path registryConfig() { return registryConfig; }
}
