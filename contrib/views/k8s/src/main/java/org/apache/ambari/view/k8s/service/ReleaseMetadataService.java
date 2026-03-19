package org.apache.ambari.view.k8s.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressRuleValue;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.ReleaseEndpointDTO;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseGitEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseGitRepo;
import org.apache.ambari.view.k8s.store.K8sReleaseRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for managing release metadata and Helm secret annotations
 */
/**
 * Stores/loads metadata for Helm releases deployed via the view.
 * Handles endpoint snapshot recording and best-effort discovery of ingress/LB endpoints.
 * Thread-safe via underlying repo/client usage; no shared mutable state beyond injected repo.
 */
public class ReleaseMetadataService {
    private static final Logger LOG = LoggerFactory.getLogger(ReleaseMetadataService.class);

    private final ViewContext viewContext;
    private final K8sReleaseRepo releaseRepository;
    private final K8sReleaseGitRepo releaseGitRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReleaseMetadataService(ViewContext ctx) {
        this.viewContext = ctx;
        this.releaseRepository = new K8sReleaseRepo(ctx);
        this.releaseGitRepository = new K8sReleaseGitRepo(ctx);
    }

    public K8sReleaseEntity find(String namespace, String name) {
        return releaseRepository.findById(K8sReleaseEntity.idOf(namespace, name));
    }

    /**
     * Delete a release metadata record by namespace/release.
     *
     * @param namespace namespace of the release
     * @param name      release name
     */
    public void delete(String namespace, String name) {
        String releaseId = K8sReleaseEntity.idOf(namespace, name);
        releaseRepository.deleteById(releaseId);
        releaseGitRepository.deleteById(releaseId);
    }

    /**
     * Persist only the PR state for an existing release.
     *
     * @param namespace namespace of the release
     * @param name      release name
     * @param prState   state string (open/closed/merged, etc.)
     */
    public void updatePrState(String namespace, String name, String prState) {
        if (prState == null) {
            return;
        }
        String releaseId = K8sReleaseEntity.idOf(namespace, name);
        K8sReleaseEntity releaseEntity = releaseRepository.findById(releaseId);
        K8sReleaseGitEntity gitEntity = getOrCreateGitEntity(releaseId, releaseEntity);
        if (gitEntity != null) {
            gitEntity.setPrState(prState);
            releaseGitRepository.upsert(gitEntity);
        }
    }

    public void updatePrInfo(String namespace, String name, String prUrl, String prNumber, String prState) {
        if ((prUrl == null || prUrl.isBlank()) && (prNumber == null || prNumber.isBlank()) && (prState == null || prState.isBlank())) {
            return;
        }
        String releaseId = K8sReleaseEntity.idOf(namespace, name);
        K8sReleaseEntity releaseEntity = releaseRepository.findById(releaseId);
        K8sReleaseGitEntity gitEntity = getOrCreateGitEntity(releaseId, releaseEntity);
        if (gitEntity == null) {
            return;
        }
        boolean dirty = false;
        if (prUrl != null && !prUrl.isBlank() && !prUrl.equals(gitEntity.getPrUrl())) {
            gitEntity.setPrUrl(prUrl);
            dirty = true;
        }
        if (prNumber != null && !prNumber.isBlank() && !prNumber.equals(gitEntity.getPrNumber())) {
            gitEntity.setPrNumber(prNumber);
            dirty = true;
        }
        if (prState != null && !prState.isBlank() && !prState.equals(gitEntity.getPrState())) {
            gitEntity.setPrState(prState);
            dirty = true;
        }
        if (dirty) {
            releaseGitRepository.upsert(gitEntity);
        }
    }

    /**
     * Return all managed releases recorded in the datastore.
     */
    public List<K8sReleaseEntity> findAll() {
        return new java.util.ArrayList<>(releaseRepository.findAll());
    }

    public K8sReleaseGitEntity findGit(String namespace, String name) {
        String releaseId = K8sReleaseEntity.idOf(namespace, name);
        return releaseGitRepository.findById(releaseId);
    }

    /**
     * Returns the logical identifiers of Helm releases wired to the provided security profile.
     *
     * @param profileName security profile name
     * @return list of namespace/release strings
     */
    public List<String> findReleasesUsingProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return java.util.Collections.emptyList();
        }
        List<String> results = new java.util.ArrayList<>();
        for (K8sReleaseEntity entity : releaseRepository.findAll()) {
            if (profileName.equals(entity.getSecurityProfile())) {
                results.add(entity.getNamespace() + "/" + entity.getReleaseName());
            }
        }
        return results;
    }

    /**
     * Returns the logical identifiers of Helm releases wired to the provided Vault profile.
     *
     * @param profileName vault profile name
     * @return list of namespace/release strings
     */
    public List<String> findReleasesUsingVaultProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return java.util.Collections.emptyList();
        }
        List<String> results = new java.util.ArrayList<>();
        for (K8sReleaseEntity entity : releaseRepository.findAll()) {
            if (profileName.equals(entity.getVaultProfile())) {
                results.add(entity.getNamespace() + "/" + entity.getReleaseName());
            }
        }
        return results;
    }

    public void recordInstallOrUpgrade(
            String namespace,
            String releaseName,
            String serviceKey,           // can be null if unknown
            String chartRef,
            String repoId,               // can be null
            String version,              // can be null
            Map<String,Object> values,   // can be null
            String deploymentId,         // UUID generated by UI or backend; can be null
            List<Map<String,Object>> endpoints,
            String globalConfigVersion,
            String securityProfile,
            String securityProfileHash,
            String vaultProfile,
            String vaultProfileHash,
            String deploymentMode,
            String gitCommitSha,
            String gitBranch,
            String gitPath,
            String gitRepoUrl,
            String gitCredentialAlias,
            String gitCommitMode,
            String gitPrUrl,
            String gitPrNumber,
            String gitPrState
    ) {
        String releaseId = K8sReleaseEntity.idOf(namespace, releaseName);
        K8sReleaseEntity entity = releaseRepository.findById(releaseId);
        boolean isNewEntity = (entity == null);
        
        if (entity == null) {
            entity = new K8sReleaseEntity();
            entity.setId(releaseId);
            entity.setNamespace(namespace);
            entity.setReleaseName(releaseName);
            entity.setCreatedAt(Instant.now().toString());
        }

        entity.setManagedByUi(true);
        if (serviceKey != null && !serviceKey.isBlank()) entity.setServiceKey(serviceKey);
        if (chartRef != null && !chartRef.isBlank()) entity.setChartRef(chartRef);
        if (repoId != null && !repoId.isBlank()) entity.setRepoId(repoId);
        if (version != null && !version.isBlank()) entity.setVersion(version);
        if (deploymentId != null && !deploymentId.isBlank()) entity.setDeploymentId(deploymentId);

        if (globalConfigVersion != null && !globalConfigVersion.isBlank()) {
            entity.setGlobalConfigVersion(globalConfigVersion);
        }
        if (securityProfile != null && !securityProfile.isBlank()) {
            entity.setSecurityProfile(securityProfile);
        }
        if (securityProfileHash != null && !securityProfileHash.isBlank()) {
            entity.setSecurityProfileHash(securityProfileHash);
        }
        if (vaultProfile != null && !vaultProfile.isBlank()) {
            entity.setVaultProfile(vaultProfile);
        }
        if (vaultProfileHash != null && !vaultProfileHash.isBlank()) {
            entity.setVaultProfileHash(vaultProfileHash);
        }
        if (deploymentMode != null && !deploymentMode.isBlank()) {
            entity.setDeploymentMode(deploymentMode);
        }
        if (gitCommitSha != null && !gitCommitSha.isBlank()) {
            entity.setGitCommitSha(gitCommitSha);
        }
        if (gitBranch != null && !gitBranch.isBlank()) {
            entity.setGitBranch(gitBranch);
        }
        if (gitPath != null && !gitPath.isBlank()) {
            entity.setGitPath(gitPath);
        }
        if (gitRepoUrl != null && !gitRepoUrl.isBlank()) {
            entity.setGitRepoUrl(gitRepoUrl);
        }
        if (gitCredentialAlias != null && !gitCredentialAlias.isBlank()) {
            entity.setGitCredentialAlias(gitCredentialAlias);
        }
        if (gitCommitMode != null && !gitCommitMode.isBlank()) {
            entity.setGitCommitMode(gitCommitMode);
        }
        if (gitPrNumber != null && !gitPrNumber.isBlank()) {
            entity.setGitPrNumber(gitPrNumber);
        }

        // store endpoints snapshot as JSON
        if (endpoints != null && !endpoints.isEmpty()) {
            try {
                LOG.info("Endpoint detected, storing them");
                entity.setEndpointsJson(objectMapper.writeValueAsString(endpoints));
            } catch (Exception ex) {
                LOG.warn("Failed to serialize endpoints for {}:{}", namespace, releaseName, ex);
            }
        } else {
            entity.setEndpointsJson(null);
        }
        entity.setUpdatedAt(Instant.now().toString());

        if (isNewEntity) {
            releaseRepository.create(entity);
        } else {
            releaseRepository.update(entity);
        }

        // Persist PR info in dedicated Git table (avoid exceeding Ambari 65k limit on main entity).
        if ((gitPrUrl != null && !gitPrUrl.isBlank())
                || (gitPrNumber != null && !gitPrNumber.isBlank())
                || (gitPrState != null && !gitPrState.isBlank())) {
            K8sReleaseGitEntity gitEntity = getOrCreateGitEntity(releaseId, entity);
            if (gitEntity != null) {
                if (gitPrUrl != null && !gitPrUrl.isBlank()) gitEntity.setPrUrl(gitPrUrl);
                if (gitPrNumber != null && !gitPrNumber.isBlank()) gitEntity.setPrNumber(gitPrNumber);
                if (gitPrState != null && !gitPrState.isBlank()) gitEntity.setPrState(gitPrState);
                releaseGitRepository.upsert(gitEntity);
            }
        }
    }

    private K8sReleaseGitEntity getOrCreateGitEntity(String releaseId, K8sReleaseEntity releaseEntity) {
        K8sReleaseGitEntity existing = releaseGitRepository.findById(releaseId);
        if (existing != null) {
            return existing;
        }
        if (releaseEntity == null) {
            return null;
        }
        K8sReleaseGitEntity created = new K8sReleaseGitEntity();
        created.setReleaseId(releaseId);
        created.setRelease(releaseEntity);
        return created;
    }

    public void annotateHelmSecret(String namespace, String releaseName, Map<String,String> annotations) {
        try {
            KubernetesService kubernetesService = KubernetesService.get(viewContext);
            KubernetesClient kubernetesClient = kubernetesService.getClient();
            
            if (kubernetesClient == null) {
                LOG.info("Kubernetes client not configured; skip helm secret annotation");
                return;
            }

            List<Secret> secrets = kubernetesClient.secrets()
                    .inNamespace(namespace)
                    .withLabel("owner", "helm")
                    .list().getItems();

            // Find the secret "sh.helm.release.v1.<release>.vN" with max N
            String secretPrefix = "sh.helm.release.v1." + releaseName + ".v";
            Secret latestSecret = secrets.stream()
                    .filter(secret -> secret.getMetadata() != null && secret.getMetadata().getName() != null)
                    .filter(secret -> secret.getMetadata().getName().startsWith(secretPrefix))
                    .max(Comparator.comparingInt(secret -> parseRevision(secret.getMetadata().getName(), secretPrefix)))
                    .orElse(null);

            if (latestSecret == null) {
                LOG.warn("No helm secret found for {} in namespace {}", releaseName, namespace);
                return;
            }

            final String secretName = latestSecret.getMetadata().getName();
            kubernetesClient.secrets().inNamespace(namespace).withName(secretName).edit(secret -> {
                Map<String,String> existingAnnotations = secret.getMetadata().getAnnotations();
                if (existingAnnotations == null) {
                    existingAnnotations = new HashMap<>();
                }
                existingAnnotations.putAll(annotations);
                secret.getMetadata().setAnnotations(existingAnnotations);
                return secret;
            });

            LOG.info("Annotated helm secret {} with {}", secretName, annotations);
        } catch (Exception ex) {
            LOG.warn("Failed to annotate helm secret for {}:{} -> {}", namespace, releaseName, ex.toString());
        }
    }

    private static int parseRevision(String secretName, String prefix) {
        try {
            String revisionSuffix = secretName.substring(prefix.length());
            return Integer.parseInt(revisionSuffix);
        } catch (Exception e) {
            return -1;
        }
    }

    private String canonicalJson(Map<String,Object> values) throws JsonProcessingException {
        // Stable canonical JSON (ObjectMapper's default key order is suitable for simple hashing)
        return objectMapper.writeValueAsString(values);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = messageDigest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public void recordEndpoints(String namespace,
                                String releaseName,
                                List<Map<String,Object>> endpoints) {
        if (endpoints == null) {
            return;
        }
        try {
            String releaseId = K8sReleaseEntity.idOf(namespace, releaseName);
            K8sReleaseEntity entity = releaseRepository.findById(releaseId);
            if (entity == null) {
                LOG.warn("recordEndpoints: no metadata entity found for {}/{}; skipping endpoints", namespace, releaseName);
                return;
            }

            String json = objectMapper.writeValueAsString(endpoints);
            entity.setEndpointsJson(json);
            entity.setUpdatedAt(Instant.now().toString());
            releaseRepository.update(entity);

            LOG.info("Recorded {} endpoints for release {}/{}", endpoints.size(), namespace, releaseName);
        } catch (Exception e) {
            LOG.warn("Failed to record endpoints for {}/{}: {}", namespace, releaseName, e.toString());
        }
    }

    /**
     * Discover external endpoints (Ingress / LoadBalancer services) for a given release.
     * No new service class, just piggybacks on KubernetesService.
     */
    public List<ReleaseEndpointDTO> discoverExternalClusterEndpoints(String namespace, String releaseName) {
        List<ReleaseEndpointDTO> result = new ArrayList<>();

        try {
            KubernetesService ks = KubernetesService.get(viewContext);
            KubernetesClient client = ks.getClient();
            if (client == null) {
                LOG.info("discoverClusterEndpoints: no Kubernetes client, skipping discovery");
                return result;
            }

            // Helm sets app.kubernetes.io/instance=<releaseName> on all its stuff
            Map<String, String> selector = Map.of("app.kubernetes.io/instance", releaseName);

            // 1) Ingresses (vanilla Kubernetes)
            List<Ingress> ingresses = client.network()
                    .v1()
                    .ingresses()
                    .inNamespace(namespace)
                    .withLabels(selector)
                    .list()
                    .getItems();
            // Fallback: if no labeled ingress, try name-based contains releaseName
            if (ingresses.isEmpty()) {
                ingresses = client.network().v1().ingresses()
                        .inNamespace(namespace)
                        .list()
                        .getItems()
                        .stream()
                        .filter(i -> i.getMetadata() != null && i.getMetadata().getName() != null
                                && i.getMetadata().getName().toLowerCase(Locale.ROOT).contains(releaseName.toLowerCase(Locale.ROOT)))
                        .collect(Collectors.toList());
            }

            for (Ingress ing : ingresses) {
                if (ing.getSpec() == null) continue;

                // Precompute TLS hosts declared on this ingress. If any TLS block exists,
                // we prefer https even when host is null/missing to avoid serving http links
                // when TLS is configured at the ingress level.
                Set<String> tlsHosts = new HashSet<>();
                List<IngressTLS> tlsList = ing.getSpec().getTls();
                if (tlsList != null) {
                    for (IngressTLS tls : tlsList) {
                        if (tls.getHosts() != null) {
                            tlsHosts.addAll(tls.getHosts());
                        }
                    }
                }
                boolean ingressHasTls = tlsList != null && !tlsList.isEmpty();

                if (ing.getSpec().getRules() == null) continue;
                for (IngressRule rule : ing.getSpec().getRules()) {
                    String host = rule.getHost();
                    HTTPIngressRuleValue http = rule.getHttp();
                    if (http == null || http.getPaths() == null) continue;

                    boolean useHttps = (host != null && tlsHosts.contains(host)) || ingressHasTls;
                    String scheme = useHttps ? "https" : "http";

                    for (HTTPIngressPath p : http.getPaths()) {
                        String path = (p.getPath() != null) ? p.getPath() : "/";
                        String url = scheme + "://" + host + path;

                        ReleaseEndpointDTO dto = new ReleaseEndpointDTO();
                        dto.setId("ingress-" + safeId(host, path));
                        dto.setLabel("Ingress " + (host != null ? host : ""));
                        dto.setUrl(url);
                        dto.setKind("public");
                        dto.setDescription("Discovered from Ingress " +
                                (ing.getMetadata() != null ? ing.getMetadata().getName() : ""));
                        result.add(dto);
                    }
                }
            }

            // 2) Services of type LoadBalancer
            List<Service> services = client.services()
                    .inNamespace(namespace)
                    .withLabels(selector)
                    .list()
                    .getItems();
            if (services.isEmpty()) {
                services = client.services()
                        .inNamespace(namespace)
                        .list()
                        .getItems()
                        .stream()
                        .filter(s -> s.getMetadata() != null && s.getMetadata().getName() != null
                                && s.getMetadata().getName().toLowerCase(Locale.ROOT).contains(releaseName.toLowerCase(Locale.ROOT)))
                        .collect(Collectors.toList());
            }

            for (Service svc : services) {
                if (svc.getSpec() == null) continue;
                if (!"LoadBalancer".equalsIgnoreCase(svc.getSpec().getType())) continue;

                String externalHost = null;
                if (svc.getStatus() != null
                        && svc.getStatus().getLoadBalancer() != null
                        && svc.getStatus().getLoadBalancer().getIngress() != null
                        && !svc.getStatus().getLoadBalancer().getIngress().isEmpty()) {
                    var ingress = svc.getStatus().getLoadBalancer().getIngress().get(0);
                    if (ingress.getHostname() != null) {
                        externalHost = ingress.getHostname();
                    } else if (ingress.getIp() != null) {
                        externalHost = ingress.getIp();
                    }
                }

                if (externalHost == null) {
                    continue;
                }

                // pick first port or a port named "http"
                ServicePort chosen = null;
                List<ServicePort> ports = svc.getSpec().getPorts();
                if (ports != null && !ports.isEmpty()) {
                    for (ServicePort sp : ports) {
                        if ("http".equalsIgnoreCase(sp.getName())) {
                            chosen = sp;
                            break;
                        }
                    }
                    if (chosen == null) {
                        chosen = ports.get(0);
                    }
                }

                String url = "http://" + externalHost
                        + (chosen != null ? ":" + chosen.getPort() : "");

                ReleaseEndpointDTO dto = new ReleaseEndpointDTO();
                dto.setId("lb-" + svc.getMetadata().getName());
                dto.setLabel("LoadBalancer " + svc.getMetadata().getName());
                dto.setUrl(url);
                dto.setKind("public");
                dto.setDescription("Discovered from Service of type LoadBalancer");
                result.add(dto);
            }

        } catch (Exception ex) {
            LOG.warn("discoverClusterEndpoints failed for {}/{}: {}", namespace, releaseName, ex.toString());
        }

        return result;
    }

    private static String safeId(String host, String path) {
        String h = (host == null ? "nohost" : host.replaceAll("[^a-zA-Z0-9]", "-"));
        String p = (path == null ? "" : path.replaceAll("[^a-zA-Z0-9]", "-"));
        return (h + "-" + p).replaceAll("-+", "-");
    }
}
