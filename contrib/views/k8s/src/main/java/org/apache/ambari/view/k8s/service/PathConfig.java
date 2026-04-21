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

    /**
     * Constructs a {@code PathConfig} from the given view context, resolving all Helm-related
     * directory paths from view properties with sensible defaults.
     *
     * @param ctx the Ambari view context supplying the {@code k8s.view.working.dir},
     *            {@code k8s.view.helm.repositoriesConfig}, and {@code k8s.view.helm.registryConfig} properties
     */
    public PathConfig(ViewContext ctx) {
        String workingDirectory = getProperty(ctx, "k8s.view.working.dir", "/var/lib/ambari/views/work/K8S-VIEW{3}");
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

    /**
     * Creates all required Helm directories ({@code workDir}, {@code repositoriesConfig} parent,
     * and {@code registryConfig} parent) if they do not already exist.
     *
     * @throws RuntimeException if any directory cannot be created
     */
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
