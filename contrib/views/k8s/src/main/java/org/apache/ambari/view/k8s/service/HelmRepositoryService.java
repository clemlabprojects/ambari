package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.service.helm.HelmClientDefault;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.HelmRepoRepo;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Objects;

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
        return repositoryDao.upsert(entity);
    }

    public void delete(String id) {
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
        String password = readPlainSecret(entity.getSecretRef());
        helmClient.ociLogin(entity.getUrl(), entity.getUsername(), password, pathConfig.registryConfig());
        entity.setAuthInvalid(false);
        entity.setUpdatedAt(Instant.now().toString());
        repositoryDao.update(entity);
    }

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

    private HelmRepoEntity mustGet(String id) {
        HelmRepoEntity entity = repositoryDao.findById(id);
        if (entity == null) throw new IllegalArgumentException("Unknown repository: " + id);
        return entity;
    }

    public PathConfig paths() { 
        return pathConfig; 
    }
}
