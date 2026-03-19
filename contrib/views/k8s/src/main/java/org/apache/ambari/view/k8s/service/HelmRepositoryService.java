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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.AmbariException;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.service.helm.HelmClientDefault;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.HelmRepoRepo;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for managing Helm repository configurations and operations
 */
public class HelmRepositoryService {

    private static final Logger LOG = LoggerFactory.getLogger(HelmRepositoryService.class);

    private static final String SECRET_PREFIX = "helm.repo.secret.";

    private final ViewContext viewContext;
    private final HelmRepoRepo repositoryDao;
    private final HelmClient helmClient;
    private final EncryptionService encryptionService = new EncryptionService();
    private final PathConfig pathConfig;

    public HelmRepositoryService(ViewContext ctx) {
        this(ctx, new HelmClientDefault());
    }

    public HelmRepositoryService(ViewContext ctx, HelmClient helm) {
        this.viewContext = ctx;
        this.repositoryDao = new HelmRepoRepo(ctx.getDataStore());
        this.helmClient = helm;
        this.pathConfig = new PathConfig(ctx);
        this.pathConfig.ensureDirs();
    }

    // Repository CRUD Operations

    public Collection<HelmRepoEntity> list() {
        return repositoryDao.findAll();
    }

    public HelmRepoEntity get(String id) {
        return repositoryDao.findById(id);
    }

    public HelmRepoEntity save(HelmRepoEntity entity, String plainSecret) {
        Objects.requireNonNull(entity, "repo must not be null");
        validate(entity);

        if (plainSecret != null && !plainSecret.isBlank()) {
            String secretReference = SECRET_PREFIX + entity.getId();
            String encryptedBase64 = Base64.getEncoder().encodeToString(
                encryptionService.encrypt(plainSecret.getBytes()));
            viewContext.putInstanceData(secretReference, encryptedBase64);
            entity.setSecretRef(secretReference);

        }

        entity.setAuthInvalid(false);
        // timestamps handled by BaseRepo via When, but ensure updatedAt now if not present
        if (entity.getUpdatedAt() == null) {
            entity.setUpdatedAt(Instant.now().toString());
        }
        LOG.debug("Saving entity with id: {}, imgaeProject: {}, url: {}, effectiveImageRegistry: {}, name: {}", entity.getId(), entity.getImageProject(), entity.getUrl(), entity.getEffectiveImageRegistry(), entity.getName());
        return repositoryDao.upsert(entity);
    }

    public void delete(String id) {
        // Prevent deletion if any managed release still references this repo
        try {
            ReleaseMetadataService rms = new ReleaseMetadataService(viewContext);
            var releases = rms.findAll();
            boolean inUse = releases.stream().anyMatch(r -> id.equals(r.getRepoId()));
            if (inUse) {
                throw new IllegalStateException("Repository " + id + " is still referenced by managed releases; delete or migrate those releases first.");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("Failed to verify repo usage before delete: {}", e.toString());
        }
        HelmRepoEntity entity = repositoryDao.findById(id);
        if (entity != null && entity.getSecretRef() != null) {
            viewContext.removeInstanceData(entity.getSecretRef());
        }
        repositoryDao.deleteById(id);
    }

    private void validate(HelmRepoEntity entity) {
        if (entity.getId() == null || entity.getId().isBlank())
            throw new IllegalArgumentException("id is required");
        if (entity.getType() == null || entity.getType().isBlank())
            throw new IllegalArgumentException("type is required (HTTP|OCI)");
        if (entity.getName() == null || entity.getName().isBlank())
            throw new IllegalArgumentException("name is required");
        if (entity.getUrl() == null || entity.getUrl().isBlank())
            throw new IllegalArgumentException("url is required");
        if (entity.getAuthMode() == null || entity.getAuthMode().isBlank())
            entity.setAuthMode("anonymous");
    }

    // Secret Management

    public String readPlainSecret(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) return null;
        String base64Data = viewContext.getInstanceData(secretRef);
        if (base64Data == null) return null;
        byte[] cipherData = Base64.getDecoder().decode(base64Data);
        byte[] plainData = encryptionService.decrypt(cipherData);
        return new String(plainData);
    }

    // Helm Operations

    public String getRepositoryNameFromId(String repoId){
        HelmRepoEntity entity = repositoryDao.findById(repoId);
        if (entity != null ){
            return entity.getName();
        }else {
            try {
                throw new AmbariException("Helm Repository not found with id "+repoId);
            } catch (AmbariException e) {
                throw new RuntimeException(e);
            }
        }

    }
    public Path ensureHttpRepo(String repoId) {
        HelmRepoEntity entity = mustGet(repoId);
        if (!"HTTP".equalsIgnoreCase(entity.getType())) {
            throw new IllegalArgumentException("Repository " + repoId + " is not HTTP");
        }
        String password = readPlainSecret(entity.getSecretRef());
        Path configPath = pathConfig.repositoriesConfig();
        helmClient.ensureHttpRepo(configPath, entity.getName(), URI.create(entity.getUrl()), 
                                 entity.getUsername(), password);
        entity.setAuthInvalid(false);
        entity.setUpdatedAt(Instant.now().toString());
        repositoryDao.update(entity);
        return configPath;
    }

    public void ociLogin(String repoId) {
        HelmRepoEntity entity = mustGet(repoId);
        if (!"OCI".equalsIgnoreCase(entity.getType())) {
            throw new IllegalArgumentException("Repository " + repoId + " is not OCI");
        }
        if (!(entity.getAuthMode() != null && entity.getAuthMode().equalsIgnoreCase("anonymous"))) {
            LOG.info("Performing OCI login for repository id: {}", repoId);
            String password = readPlainSecret(entity.getSecretRef());
            LOG.info("Logging in to OCI registry at URL: {}", entity.getUrl());
            LOG.info("Using username: {}", entity.getUsername() != null ? entity.getUsername() : "(none)");
            LOG.info("Using password: {}", password != null ? "************" : "(none)");
            helmClient.ociLogin(entity.getUrl(), entity.getUsername(), password, pathConfig.registryConfig());
            entity.setAuthInvalid(false);
            entity.setUpdatedAt(Instant.now().toString());
            repositoryDao.update(entity);
        }
    }

    /**
     * Try to login or sync with give repoId
     * @param repoId needs ti correspond the one given at creation in the frontend like clemlabprojects
     */
    public void loginOrSync(String repoId) {
        LOG.info("Attempting to login or sync repository with id: {}", repoId);
        try {
            HelmRepoEntity entity = mustGet(repoId);
            if ("HTTP".equalsIgnoreCase(entity.getType())) {
                LOG.info("Ensuring HTTP repository: {}", entity.getName());
                ensureHttpRepo(repoId);
            } else {
                LOG.info("Logging in to OCI repository: {}", entity.getName());
                ociLogin(repoId);
            }
        } catch (RuntimeException ex) {
            LOG.error("Failed to login or sync repository {}: {}", repoId, ex.getMessage());
            HelmRepoEntity entity = repositoryDao.findById(repoId);
            if (entity != null) {
                LOG.warn("Marking repository {} as authInvalid due to error: {}", repoId, ex.getMessage());
                // Mark the repository as authInvalid if an error occurs during login or sync
                entity.setAuthInvalid(true);
                entity.setUpdatedAt(Instant.now().toString());
                repositoryDao.update(entity);
            }
            throw ex;
        }
    }

    public HelmRepoEntity mustGet(String id) {
        HelmRepoEntity entity = repositoryDao.findById(id);
        if (entity == null) throw new IllegalArgumentException("Unknown repository: " + id);
        return entity;
    }

    public PathConfig paths() { 
        return pathConfig; 
    }

    public static boolean isAnonymous(HelmRepoEntity e) {
        return e.getAuthMode() != null && e.getAuthMode().equalsIgnoreCase("anonymous");
    }

    /**
     * Lightweight health check for a repository.
     * Logs in/syncs and returns true if successful, else false.
     */
    public boolean check(String repoId) {
        try {
            loginOrSync(repoId);
            return true;
        } catch (Exception e) {
            LOG.warn("Repo check failed for {}: {}", repoId, e.getMessage());
            return false;
        }
    }

    /**
     * Return first available repo id (best-effort) or null if none.
     */
    public String firstRepoIdOrNull() {
        var all = repositoryDao.findAll();
        if (all == null || all.isEmpty()) return null;
        return all.iterator().next().getId();
    }

    /**
     * Authenticates (if needed) and retrieves the default values.yaml for a chart.
     * * @param chartName The name of the chart (e.g., "clemlab-trino")
     * @param repoId    The ID of the repository config to use for auth/url
     * @param version   Optional version (can be null)
     * @return Map of values, or empty map on failure
     */
    public Map<String, Object> showValuesAsMap(String chartName, String repoId, String version) {
        // 1. Ensure we are authenticated (OCI Login or HTTP Repo Update)
        // This populates repositories.yaml (HTTP) or registry.json (OCI)
        loginOrSync(repoId);
        // 2. Resolve the reference string (e.g. "oci://registry.../chart" or "my-repo/chart")
        LOG.info("Resolving reference with chartName: {}, repoId {} ", chartName, repoId);
        RepoResolution chartRef = this.resolveChartRef(chartName, repoId);

        LOG.debug("Executing 'helm show values' for chartRef: {} and version: {}", chartRef.chartRef, version);

        // 4. Parse YAML
        try {
            // 3. Run the command
            String yaml = helmClient.showValues(chartRef.chartRef, version, pathConfig.repositoriesConfig());

            if (yaml == null || yaml.isBlank()) {
                LOG.warn("showValuesAsMap: returned empty YAML for chartRef={}", chartRef);
                return Collections.emptyMap();
            }else {
                LOG.info("Successfully downloaded default values for chart");
                LOG.debug("YAML found is: {}", yaml);
            }
            ObjectMapper om = new ObjectMapper(new YAMLFactory());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = om.readValue(yaml, Map.class);
            LOG.debug("YAML as result is: {}", yaml);
            return (result != null) ? result : Collections.emptyMap();
        } catch (Exception e) {
            String msg = "Failed to resolve chart '" + chartName + "' in repo '" + repoId + "' (version=" + version + ")";
            LOG.error("{}: {}", msg, e.toString());
            throw new IllegalArgumentException(msg + ": " + e.getMessage(), e);
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
            HelmRepoEntity repository = this.get(repoIdOpt);
            if (repository == null) {
                throw new IllegalArgumentException("Unknown repository: " + repoIdOpt);
            }

            if ("HTTP".equalsIgnoreCase(repository.getType())) {
                LOG.info("Repository charts directory with name is {}, is of type of repository is HTTP", repository.getName());
                this.ensureHttpRepo(repository.getId());
                if (!chartRef.contains("/")) {
                    chartRef = repository.getName() + "/" + chartRef; // e.g. bitnami/trino
                }
            } else {
                // OCI
//                LOG.info("Repository charts directory with name is {}, is of type of repository is OCI", repository.getName());
//                isOci = true;
//                this.ociLogin(repository.getId());
//                String baseUrl = repository.getUrl();
//                if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
//                if (!chartRef.startsWith("oci://")) {
//                    chartRef = "oci://" + baseUrl + "/" + repository.getName() + "/" + chartRef;
//                }
                LOG.info("Repository charts directory with name is {}, is of type of repository is OCI", repository.getName());
                isOci = true;
                this.ociLogin(repository.getId());

                String baseUrl = repository.getUrl().trim();
                if (baseUrl.endsWith("/")) {
                    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                }

                if (!chartRef.startsWith("oci://")) {
                    // Treat baseUrl as *full* registry path prefix, don't inject repo name again
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

    private String computeRegistryHost(HelmRepoEntity entity) {
        // 1. explicit override wins
        if (entity.getImageRegistryHostOverride() != null &&
                !entity.getImageRegistryHostOverride().isBlank()) {
            return entity.getImageRegistryHostOverride().trim();
        }

        String type = entity.getType();
        String url  = entity.getUrl();

        if (url == null || url.isBlank()) {
            return null;
        }

        try {
            if ("HTTP".equalsIgnoreCase(type)) {
                // Example: https://registry.clemlab.com/clemlabprojects/charts
                URI uri = URI.create(url);
                return uri.getHost();       // registry.clemlab.com
            } else {
                // OCI: we store "registry.clemlab.com" or "registry.clemlab.com/clemlabprojects/charts"
                String trimmed = url.trim();
                int slash = trimmed.indexOf('/');
                return (slash > 0) ? trimmed.substring(0, slash) : trimmed; // registry.clemlab.com
            }
        } catch (Exception e) {
            LOG.warn("Failed to compute registry host from url '{}' for repo '{}': {}",
                    url, entity.getId(), e.toString());
            return null;
        }
    }

    private String computeImageRegistry(HelmRepoEntity entity) {
        String host = computeRegistryHost(entity); // "registry.clemlab.com"
        if (host == null || host.isBlank()) {
            return null;
        }
        String project = entity.getImageProject(); // e.g. "clemlabprojects"
        if (project == null || project.isBlank()) {
            return host;
        }
        return host + "/" + project.trim();        // "registry.clemlab.com/clemlabprojects"
    }

    public String getRegistryHost(String repoId) {
        HelmRepoEntity entity = mustGet(repoId);
        return computeRegistryHost(entity);
    }

    public String getImageRegistry(String repoId) {
        HelmRepoEntity entity = mustGet(repoId);
        return computeImageRegistry(entity);
    }

    public String getEffectiveImageRegistry(String repoId){
        HelmRepoEntity entity = mustGet(repoId);
        return entity.getEffectiveImageRegistry();
    }
}
