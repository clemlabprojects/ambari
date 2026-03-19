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

import com.marcnuri.helm.Release;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Interface for Helm client operations
 */
public interface HelmClient {
    
    void ensureHttpRepo(Path repositoriesConfig, String name, URI url, String username, String passwordOrToken);
    
    void ociLogin(String server, String username, String passwordOrToken, Path registryConfig);

    List<Release> list(String namespace, String kubeconfigContents, boolean deployedOnly);

    // Primary install method with full parameter set
    Release install(String chartRef, String chartVersion, boolean isOci,String releaseName, String namespace,
                   Path repositoriesConfig, String kubeconfigContents,
                   Map<String, Object> values,
                   int timeoutSec, boolean createNamespace, boolean wait, boolean atomic, boolean dryRun);

    // Convenience install method for backward compatibility
    default Release install(String chartRef, String chartVersion, boolean isOci,String releaseName, String namespace,
                           Path repositoriesConfig, String kubeconfigContents,
                           Map<String, Object> values,
                           int timeoutSec, boolean createNamespace, boolean wait, boolean atomic) {
        return install(chartRef,chartVersion,isOci, releaseName, namespace, repositoriesConfig, kubeconfigContents,
                      values, timeoutSec, createNamespace, wait, atomic, false);
    }

    // Primary upgrade method with full parameter set
    Release upgrade(String chartRef, String chartVersion, boolean isOci, String releaseName, String namespace,
                   Path repositoriesConfig, String kubeconfigContents,
                   Map<String, Object> values,
                   int timeoutSec, boolean wait, boolean atomic, boolean dryRun);

    // Convenience upgrade method for backward compatibility
    default Release upgrade(String chartRef, String chartVersion, boolean isOci, String releaseName, String namespace,
                           Path repositoriesConfig, String kubeconfigContents,
                           Map<String, Object> values,
                           int timeoutSec, boolean wait, boolean atomic) {
        return upgrade(chartRef, chartVersion, isOci, releaseName, namespace, repositoriesConfig, kubeconfigContents,
                      values, timeoutSec, wait, atomic, false);
    }

    void uninstall(String releaseName, String namespace, String kubeconfigContents);

    /**
     * Rollback functionality - currently not supported by helm-java library
     */
    default void rollback(String releaseName, String namespace, int revision, String kubeconfigContents) {
        throw new UnsupportedOperationException("Rollback not supported by helm-java yet.");
    }

    /**
     * Run `helm show values` on the given chartRef (optionally with version)
     * and return the raw YAML as a String.
     */
    String showValues(String chartRef, String versionOpt, java.nio.file.Path repoConfigPath);

    /**
     * Run `helm show chart` on the given chartRef (optionally with version)
     * and return the raw YAML as a String.
     */
    default String showChart(String chartRef, String versionOpt, java.nio.file.Path repoConfigPath) {
        throw new UnsupportedOperationException("showChart not implemented");
    }
}
