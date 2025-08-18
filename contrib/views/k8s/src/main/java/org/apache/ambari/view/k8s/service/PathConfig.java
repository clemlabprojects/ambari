package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.ViewContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PathConfig {
  private final Path workDir;
  private final Path repositoriesConfig;
  private final Path registryConfig;

  public PathConfig(ViewContext ctx) {
    String wd  = get(ctx, "k8s.view.working.dir", "/var/lib/ambari/views/work/k8s-view");
    String rep = get(ctx, "k8s.view.helm.repositoriesConfig", "");
    String reg = get(ctx, "k8s.view.helm.registryConfig", "");

    this.workDir = Paths.get(wd);
    this.repositoriesConfig = rep.isBlank()
        ? workDir.resolve("helm").resolve("repositories.yaml")
        : Paths.get(rep);
    this.registryConfig = reg.isBlank()
        ? workDir.resolve("helm").resolve("registry").resolve("config.json")
        : Paths.get(reg);
  }

  private static String get(ViewContext ctx, String key, String deflt) {
    var p = ctx.getProperties();
    String v = p != null ? p.get(key) : null;
    return (v == null || v.isBlank()) ? deflt : v;
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
