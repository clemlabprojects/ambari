package org.apache.ambari.view.k8s.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

public class ReleaseMetadataService {
  private static final Logger LOG = LoggerFactory.getLogger(ReleaseMetadataService.class);

  private final ViewContext ctx;
  private final K8sReleaseRepo repo;

  private final ObjectMapper om = new ObjectMapper();

  public ReleaseMetadataService(ViewContext ctx) {
    this.ctx = ctx;
    this.repo = new K8sReleaseRepo(ctx);
  }

  public K8sReleaseEntity find(String ns, String name) {
    return repo.findById(K8sReleaseEntity.idOf(ns, name));
  }

  public void recordInstallOrUpgrade(
      String namespace,
      String releaseName,
      String serviceKey,           // peut être null si inconnu
      String chartRef,
      String repoId,               // peut être null
      String version,              // peut être null
      Map<String,Object> values,   // peut être null
      String deploymentId          // UUID généré côté UI ou backend; peut être null
  ) {
    String id = K8sReleaseEntity.idOf(namespace, releaseName);
    K8sReleaseEntity e = repo.findById(id);
    boolean create = (e == null);
    if (e == null) {
      e = new K8sReleaseEntity();
      e.setId(id);
      e.setNamespace(namespace);
      e.setReleaseName(releaseName);
      e.setCreatedAt(Instant.now().toString());
    }

    e.setManagedByUi(true);
    if (serviceKey != null && !serviceKey.isBlank()) e.setServiceKey(serviceKey);
    if (chartRef  != null && !chartRef.isBlank())   e.setChartRef(chartRef);
    if (repoId    != null && !repoId.isBlank())     e.setRepoId(repoId);
    if (version   != null && !version.isBlank())    e.setVersion(version);
    if (deploymentId != null && !deploymentId.isBlank()) e.setDeploymentId(deploymentId);

    if (values != null) {
      try {
        e.setValuesHash(sha256Hex(canonicalJson(values)));
      } catch (Exception ex) {
        LOG.warn("Failed to hash values for {}:{}", namespace, releaseName, ex);
      }
    }

    e.setUpdatedAt(Instant.now().toString());

    if (create) repo.create(e);
    else repo.update(e);
  }

  public void annotateHelmSecret(String namespace, String releaseName, Map<String,String> annotations) {
    try {
      KubernetesService ks = new KubernetesService(ctx);
      KubernetesClient kc = ks.getClient(); // nécessite getClient() public (cf. patch plus bas)
      if (kc == null) {
        LOG.info("Kubernetes client not configured; skip helm secret annotation");
        return;
      }

      List<Secret> secrets = kc.secrets()
          .inNamespace(namespace)
          .withLabel("owner", "helm")
          .list().getItems();

      // Cherche le secret "sh.helm.release.v1.<release>.vN" au N max
      String prefix = "sh.helm.release.v1." + releaseName + ".v";
      Secret latest = secrets.stream()
          .filter(s -> s.getMetadata() != null && s.getMetadata().getName() != null)
          .filter(s -> s.getMetadata().getName().startsWith(prefix))
          .max(Comparator.comparingInt(s -> parseRevision(s.getMetadata().getName(), prefix)))
          .orElse(null);

      if (latest == null) {
        LOG.warn("No helm secret found for {} in ns {}", releaseName, namespace);
        return;
      }

      final String name = latest.getMetadata().getName();
      kc.secrets().inNamespace(namespace).withName(name).edit(s -> {
        Map<String,String> ann = s.getMetadata().getAnnotations();
        if (ann == null) ann = new HashMap<>();
        ann.putAll(annotations);
        s.getMetadata().setAnnotations(ann);
        return s;
      });

      LOG.info("Annotated helm secret {} with {}", name, annotations);
    } catch (Exception ex) {
      LOG.warn("Failed to annotate helm secret for {}:{} -> {}", namespace, releaseName, ex.toString());
    }
  }

  private static int parseRevision(String secretName, String prefix) {
    try {
      String tail = secretName.substring(prefix.length());
      return Integer.parseInt(tail);
    } catch (Exception e) {
      return -1;
    }
  }

  private String canonicalJson(Map<String,Object> values) throws JsonProcessingException {
    // JSON canonique stable (ordre de clés par défaut d’ObjectMapper convient pour hash simple)
    return om.writeValueAsString(values);
  }

  private static String sha256Hex(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte x : b) sb.append(String.format("%02x", x));
      return sb.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
