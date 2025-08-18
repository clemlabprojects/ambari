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

public class HelmRepositoryService {

  private static final Logger LOG = LoggerFactory.getLogger(HelmRepositoryService.class);

  private static final String SECRET_PREFIX = "helm.repo.secret.";

  private final ViewContext viewContext;
  private final HelmRepoRepo repo;
  private final HelmClient helm;
  private final EncryptionService encryption = new EncryptionService();
  private final PathConfig paths;

  public HelmRepositoryService(ViewContext ctx) {
    this(ctx, new HelmClientDefault());
  }

  public HelmRepositoryService(ViewContext ctx, HelmClient helm) {
    this.viewContext = ctx;
    this.repo = new HelmRepoRepo(ctx.getDataStore());
    this.helm = helm;
    this.paths = new PathConfig(ctx);
    this.paths.ensureDirs();
  }

  /* ------------------- CRUD ------------------- */

  public Collection<HelmRepoEntity> list() {
    return repo.findAll();
  }

  public HelmRepoEntity get(String id) {
    return repo.findById(id);
  }

  public HelmRepoEntity save(HelmRepoEntity e, String plainSecret) {
    Objects.requireNonNull(e, "repo must not be null");
    validate(e);

    if (plainSecret != null && !plainSecret.isBlank()) {
      String ref = SECRET_PREFIX + e.getId();
      String encB64 = Base64.getEncoder().encodeToString(encryption.encrypt(plainSecret.getBytes()));
      viewContext.putInstanceData(ref, encB64);
      e.setSecretRef(ref);
    }

    e.setAuthInvalid(false);
    // timestamps handled by BaseRepo via When, but ensure updatedAt now if not present
    if (e.getUpdatedAt() == null) e.setUpdatedAt(Instant.now().toString());
    return repo.upsert(e);
  }

  public void delete(String id) {
    HelmRepoEntity e = repo.findById(id);
    if (e != null && e.getSecretRef() != null) {
      viewContext.removeInstanceData(e.getSecretRef());
    }
    repo.deleteById(id);
  }

  private void validate(HelmRepoEntity e) {
    if (e.getId() == null || e.getId().isBlank())
      throw new IllegalArgumentException("id is required");
    if (e.getType() == null || e.getType().isBlank())
      throw new IllegalArgumentException("type is required (HTTP|OCI)");
    if (e.getName() == null || e.getName().isBlank())
      throw new IllegalArgumentException("name is required");
    if (e.getUrl() == null || e.getUrl().isBlank())
      throw new IllegalArgumentException("url is required");
    if (e.getAuthMode() == null || e.getAuthMode().isBlank())
      e.setAuthMode("anonymous");
  }

  /* ------------------- Secrets ------------------- */

  public String readPlainSecret(String secretRef) {
    if (secretRef == null || secretRef.isBlank()) return null;
    String b64 = viewContext.getInstanceData(secretRef);
    if (b64 == null) return null;
    byte[] cipher = Base64.getDecoder().decode(b64);
    byte[] plain  = encryption.decrypt(cipher);
    return new String(plain);
  }

  /* ------------------- Helm actions ------------------- */

  public Path ensureHttpRepo(String repoId) {
    HelmRepoEntity e = mustGet(repoId);
    if (!"HTTP".equalsIgnoreCase(e.getType())) {
      throw new IllegalArgumentException("Repository " + repoId + " is not HTTP");
    }
    String pwd = readPlainSecret(e.getSecretRef());
    Path cfg = paths.repositoriesConfig();
    helm.ensureHttpRepo(cfg, e.getName(), URI.create(e.getUrl()), e.getUsername(), pwd);
    e.setAuthInvalid(false);
    e.setUpdatedAt(Instant.now().toString());
    repo.update(e);
    return cfg;
  }

  public void ociLogin(String repoId) {
    HelmRepoEntity e = mustGet(repoId);
    if (!"OCI".equalsIgnoreCase(e.getType())) {
      throw new IllegalArgumentException("Repository " + repoId + " is not OCI");
    }
    String pwd = readPlainSecret(e.getSecretRef());
    helm.ociLogin(e.getUrl(), e.getUsername(), pwd, paths.registryConfig());
    e.setAuthInvalid(false);
    e.setUpdatedAt(Instant.now().toString());
    repo.update(e);
  }

  
  public void loginOrSync(String repoId) {
    LOG.info("Attempting to login or sync repository with id: {}", repoId);
    try {
      HelmRepoEntity e = mustGet(repoId);
      if ("HTTP".equalsIgnoreCase(e.getType())) {
        LOG.info("Ensuring HTTP repository: {}", e.getName());
        ensureHttpRepo(repoId);
      } else {
        LOG.info("Logging in to OCI repository: {}", e.getName());
        ociLogin(repoId);
      }
    } catch (RuntimeException ex) {
      LOG.error("Failed to login or sync repository {}: {}", repoId, ex.getMessage());
      HelmRepoEntity e = repo.findById(repoId);
      if (e != null) {
        LOG.warn("Marking repository {} as authInvalid due to error: {}", repoId, ex.getMessage());
        // Mark the repository as authInvalid if an error occurs during login or sync
        e.setAuthInvalid(true);
        e.setUpdatedAt(Instant.now().toString());
        repo.update(e);
      }
      throw ex;
    }
  }

  private HelmRepoEntity mustGet(String id) {
    HelmRepoEntity e = repo.findById(id);
    if (e == null) throw new IllegalArgumentException("Unknown repository: " + id);
    return e;
  }

  public PathConfig paths() { return paths; }
}
