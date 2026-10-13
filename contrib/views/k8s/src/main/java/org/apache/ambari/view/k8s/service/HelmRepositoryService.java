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
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.service.helm.HelmClientDefault;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.HelmRepoRepo;
import org.apache.ambari.view.k8s.store.InstanceSettings;

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

    /** Secret reference of a repository with no stored secret; empty, because the DataStore never writes a null. */
    private static final String NO_SECRET = "";

    private final ViewContext viewContext;
    private final HelmRepoRepo repositoryDao;
    private final HelmClient helmClient;
    /** Holds the repository credentials, shared by every user of the view instance. */
    private final InstanceSettings settings;
    private final PathConfig pathConfig;

    /**
     * Constructs a {@code HelmRepositoryService} using the default {@link HelmClientDefault}.
     *
     * @param ctx the Ambari view context providing configuration and the instance settings
     */
    public HelmRepositoryService(ViewContext ctx) {
        this(ctx, new HelmClientDefault());
    }

    /**
     * Constructs a {@code HelmRepositoryService} with an explicit Helm client.
     *
     * @param ctx  the Ambari view context providing configuration and the instance settings
     * @param helm the Helm client to use for repository operations
     */
    public HelmRepositoryService(ViewContext ctx, HelmClient helm) {
        this.viewContext = ctx;
        this.repositoryDao = new HelmRepoRepo(ctx.getDataStore());
        this.settings = new InstanceSettings(ctx);
        this.helmClient = helm;
        this.pathConfig = new PathConfig(ctx);
        this.pathConfig.ensureDirs();
    }

    // Repository CRUD Operations

    /**
     * Returns all configured Helm repository entities.
     *
     * @return collection of all repository entities; never {@code null}
     */
    public Collection<HelmRepoEntity> list() {
        return repositoryDao.findAll();
    }

    /**
     * Finds a repository entity by its unique identifier.
     *
     * @param id the repository ID to look up
     * @return the matching entity, or {@code null} if not found
     */
    public HelmRepoEntity get(String id) {
        return repositoryDao.findById(id);
    }

    /**
     * Persists (creates or updates) a repository entity, encrypting and storing the credential secret.
     *
     * <p>The secret is only ever kept under this repository's own key ({@link #secretKey}), whatever secret reference
     * the request carries. A stored secret is dropped, and the repository marked as needing its credentials again,
     * when the registry it would be sent to changes without a new secret: its URL, registry host, type or user name.
     * Otherwise a credential entered for one registry would be sent to another.</p>
     *
     * @param entity      the repository entity to save; must not be {@code null}
     * @param plainSecret the plaintext password/token to encrypt and store; may be {@code null} if no credentials
     * @return the persisted entity after upsert
     */
    public HelmRepoEntity save(HelmRepoEntity entity, String plainSecret) {
        Objects.requireNonNull(entity, "repo must not be null");
        validate(entity);

        HelmRepoEntity existing = repositoryDao.findById(entity.getId());
        String secretKey = secretKey(entity.getId());

        if (plainSecret != null && !plainSecret.isBlank()) {
            settings.putSecret(secretKey, plainSecret);
            entity.setSecretRef(secretKey);
            entity.setAuthInvalid(false);
        } else if (settings.get(secretKey) != null && (existing == null || registryChanged(existing, entity))) {
            settings.remove(secretKey);
            entity.setSecretRef(NO_SECRET);
            entity.setAuthInvalid(true);
            LOG.warn("The stored credentials of repository {} were dropped: they were entered for another registry "
                    + "and must be entered again", entity.getId());
        } else {
            entity.setSecretRef(existing != null && secretKey.equals(existing.getSecretRef()) ? secretKey : NO_SECRET);
            entity.setAuthInvalid(false);
        }

        // timestamps handled by BaseRepo via When, but ensure updatedAt now if not present
        if (entity.getUpdatedAt() == null) {
            entity.setUpdatedAt(Instant.now().toString());
        }
        LOG.debug("Saving entity with id: {}, imgaeProject: {}, url: {}, effectiveImageRegistry: {}, name: {}", entity.getId(), entity.getImageProject(), entity.getUrl(), entity.getEffectiveImageRegistry(), entity.getName());
        return repositoryDao.upsert(entity);
    }

    /**
     * Whether saving {@code incoming} over {@code existing} sends the repository's credentials somewhere else: another
     * URL, registry host, type or user name. A property the request leaves out keeps its stored value, as the
     * DataStore does when it updates the row.
     */
    static boolean registryChanged(HelmRepoEntity existing, HelmRepoEntity incoming) {
        HelmRepoEntity after = new HelmRepoEntity();
        after.setType(incoming.getType() != null ? incoming.getType() : existing.getType());
        after.setUrl(incoming.getUrl() != null ? incoming.getUrl() : existing.getUrl());
        after.setImageRegistryHostOverride(incoming.getImageRegistryHostOverride() != null
                ? incoming.getImageRegistryHostOverride() : existing.getImageRegistryHostOverride());
        String username = incoming.getUsername() != null ? incoming.getUsername() : existing.getUsername();
        return !Objects.equals(trimmed(existing.getUrl()), trimmed(after.getUrl()))
                || !trimmed(existing.getType()).equalsIgnoreCase(trimmed(after.getType()))
                || !Objects.equals(trimmed(existing.getUsername()), trimmed(username))
                || !trimmed(existing.getEffectiveRegistryHost()).equalsIgnoreCase(trimmed(after.getEffectiveRegistryHost()));
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Deletes a repository and its stored credentials.
     * Throws {@link IllegalStateException} if any managed release still references the repository.
     *
     * @param id the ID of the repository to delete
     * @throws IllegalStateException if the repository is still in use by at least one release
     */
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
        // Only this repository's own key: a secret reference stored by an earlier version may name another setting.
        settings.remove(secretKey(id));
        repositoryDao.deleteById(id);
    }

    /**
     * Clears a secret reference earlier versions could keep in a repository row naming another view setting than the
     * repository's own key (that of the OpenShift password, for one). Run when the view starts; a row already clean is
     * left untouched.
     *
     * <p>The registry secret earlier versions kept in a column of the row is not handled here. This version's rows sit
     * in tables created without that column, the data migration copies no value into it, and it blanks it in the
     * previous view version's rows. Rows are only ever updated in place: removing one and storing it again would lose
     * the repository if the second write failed.</p>
     *
     * @return the number of repositories cleaned
     */
    public int clearForeignSecretRefs() {
        int cleaned = 0;
        for (HelmRepoEntity repo : repositoryDao.findAll()) {
            String ref = repo.getSecretRef();
            if (ref == null || ref.isEmpty() || ref.equals(secretKey(repo.getId()))) {
                continue;
            }
            repo.setSecretRef(NO_SECRET);
            LOG.warn("Repository {} referred to the view setting '{}' for its credentials, which belongs to "
                    + "something else; the reference was cleared", repo.getId(), ref);
            repositoryDao.update(repo);
            cleaned++;
        }
        return cleaned;
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

    /**
     * Decrypts and returns the credential stored for a repository. Only the repository's own key is read, never the
     * secret reference stored with it: that way no repository can be made to send another repository's secret, or
     * any other secret of the view, to its registry.
     *
     * @param entity the repository; may be {@code null}
     * @return the decrypted plaintext credential, or {@code null} if none is stored
     */
    public String readPlainSecret(HelmRepoEntity entity) {
        if (entity == null || entity.getId() == null || entity.getId().isBlank()) return null;
        return settings.getSecret(secretKey(entity.getId()));
    }

    /** The view setting a repository's secret is stored under. */
    static String secretKey(String repoId) {
        return SECRET_PREFIX + repoId;
    }

    // Helm Operations

    /**
     * Resolves the human-readable name of a repository from its ID.
     *
     * @param repoId the repository ID to look up
     * @return the repository name
     * @throws RuntimeException wrapping {@link AmbariException} if the repository is not found
     */
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
    /**
     * Ensures the HTTP Helm repository is registered in the local {@code repositories.yaml}.
     *
     * @param repoId the ID of the HTTP repository to register
     * @return the path to the updated {@code repositories.yaml} file
     * @throws IllegalArgumentException if the repository is not of type HTTP
     */
    public Path ensureHttpRepo(String repoId) {
        HelmRepoEntity entity = mustGet(repoId);
        if (!"HTTP".equalsIgnoreCase(entity.getType())) {
            throw new IllegalArgumentException("Repository " + repoId + " is not HTTP");
        }
        String password = readPlainSecret(entity);
        Path configPath = pathConfig.repositoriesConfig();
        helmClient.ensureHttpRepo(configPath, entity.getName(), URI.create(entity.getUrl()), 
                                 entity.getUsername(), password);
        entity.setAuthInvalid(false);
        entity.setUpdatedAt(Instant.now().toString());
        repositoryDao.update(entity);
        return configPath;
    }

    /**
     * Authenticates against an OCI registry and stores the credentials in the local registry config.
     *
     * @param repoId the ID of the OCI repository to log in to
     * @throws IllegalArgumentException if the repository is not of type OCI
     */
    public void ociLogin(String repoId) {
        HelmRepoEntity entity = mustGet(repoId);
        if (!"OCI".equalsIgnoreCase(entity.getType())) {
            throw new IllegalArgumentException("Repository " + repoId + " is not OCI");
        }
        if (!(entity.getAuthMode() != null && entity.getAuthMode().equalsIgnoreCase("anonymous"))) {
            LOG.info("Performing OCI login for repository id: {}", repoId);
            String password = readPlainSecret(entity);
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

    /**
     * Returns the repository entity for the given ID, throwing if it does not exist.
     *
     * @param id the repository ID to look up
     * @return the repository entity; never {@code null}
     * @throws IllegalArgumentException if no repository with the given ID is found
     */
    public HelmRepoEntity mustGet(String id) {
        HelmRepoEntity entity = repositoryDao.findById(id);
        if (entity == null) throw new IllegalArgumentException("Unknown repository: " + id);
        return entity;
    }

    /**
     * Returns the {@link PathConfig} holding Helm directory paths for this service.
     *
     * @return the path configuration
     */
    public PathConfig paths() {
        return pathConfig;
    }

    /**
     * Returns {@code true} if the repository is configured for anonymous (unauthenticated) access.
     *
     * @param e the repository entity to inspect
     * @return {@code true} when the auth mode is "anonymous" (case-insensitive)
     */
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

    /**
     * Resolves the registry hostname (e.g. {@code registry.example.com}) for the given repository.
     *
     * @param repoId the repository ID to look up
     * @return the registry host string, or {@code null} if it cannot be determined
     */
    public String getRegistryHost(String repoId) {
        HelmRepoEntity entity = mustGet(repoId);
        return computeRegistryHost(entity);
    }

    /**
     * Resolves the full image registry path (host + optional project, e.g. {@code registry.example.com/myproject})
     * for the given repository.
     *
     * @param repoId the repository ID to look up
     * @return the image registry path, or {@code null} if it cannot be determined
     */
    public String getImageRegistry(String repoId) {
        HelmRepoEntity entity = mustGet(repoId);
        return computeImageRegistry(entity);
    }

    /**
     * Returns the effective image registry value stored on the repository entity.
     *
     * @param repoId the repository ID to look up
     * @return the effective image registry string from the entity, or {@code null} if not set
     */
    public String getEffectiveImageRegistry(String repoId){
        HelmRepoEntity entity = mustGet(repoId);
        return entity.getEffectiveImageRegistry();
    }
}
