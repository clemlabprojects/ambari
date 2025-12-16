package org.apache.ambari.view.k8s.resources;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.marcnuri.helm.Release;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.HelmReleaseDTO;
import org.apache.ambari.view.k8s.model.ReleaseEndpointDTO;
import org.apache.ambari.view.k8s.model.HelmReleasesResponse;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.requests.HelmUpgradeRequest;
import org.apache.ambari.view.k8s.service.*;
import org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend;
import org.apache.ambari.view.k8s.service.helm.HelmClientDefault;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.service.PathConfig;
import org.apache.ambari.view.k8s.service.CommandService;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST resource for Helm operations (list/deploy/upgrade/rollback).
 * Keeps a short-lived cache of release listings to avoid hammering the cluster
 * on frequent UI polling. Stateless otherwise; thread-safe because state is confined
 * to static cache or request scope.
 */
@Path("/helm")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class HelmResource {

    private static final Logger LOG = LoggerFactory.getLogger(HelmResource.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    private ViewContext viewContext;

    @Inject
    private ViewConfigurationService configService;

    private final KubernetesService kubernetesService;
    private final FluxGitOpsBackend fluxGitOpsBackend;
    private final CommandService commandService;
    private final ReleaseMetadataService releaseMetadataService;

    // Lightweight in-memory cache for releases to avoid hammering Helm/K8s on frequent polls
    private static final Map<String, CachedReleases> RELEASE_CACHE = new ConcurrentHashMap<>();
    private static final long RELEASE_CACHE_TTL_MILLIS = 10_000; // 10s


    public HelmResource(ViewContext context) {
        this.viewContext = context;
        this.configService = new ViewConfigurationService(viewContext);
        this.kubernetesService = KubernetesService.get(viewContext);
        this.fluxGitOpsBackend = new FluxGitOpsBackend(new PathConfig(viewContext).workDir(), this.kubernetesService, viewContext);
        this.commandService = new CommandService(viewContext);
        this.releaseMetadataService = new ReleaseMetadataService(viewContext);
    }

    /**
     * Health check for a repo id.
     */
    @GET
    @Path("/repos/{id}/check")
    public Response checkRepo(@PathParam("id") String id) {
        try {
            boolean ok = new HelmRepositoryService(viewContext).check(id);
            return Response.ok(Map.of("id", id, "ok", ok)).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

  /**
   * Trigger monitoring (kube-prometheus-stack) bootstrap using the provided repoId (or default).
   */
  @POST
  @Path("/monitoring/install")
  public Response installMonitoring(@QueryParam("repoId") String repoId) {
    try {
      var info = KubernetesService.get(viewContext).ensureMonitoringInstalled(repoId);
      if (info == null) {
        return Response.serverError().entity(Map.of("error", "Monitoring install failed or not configured")).build();
      }
      return Response.ok(Map.of("namespace", info.namespace(), "release", info.release(), "url", info.url())).build();
    } catch (Exception e) {
      return Response.serverError().entity(Map.of("error", e.getMessage())).build();
    }
  }

    private String getKubeconfigContents() {
        return configService.getKubeconfigContents();
    }

    @GET
    @Path("/releases")
    public HelmReleasesResponse list(@QueryParam("namespace") String namespace,
                                     @QueryParam("limit") @DefaultValue("50") int limit,
                                     @QueryParam("offset") @DefaultValue("0") int offset) {
        final String kubeconfigContent = getKubeconfigContents();
        final HelmService helmService = new HelmService(viewContext);
        final GlobalConfigService globalConfigService = new GlobalConfigService();
        final String currentGlobalFingerprint = globalConfigService.fingerprint();
        final SecurityProfileService securityProfileService = new SecurityProfileService(viewContext);

        List<Release> releases = helmService.list(namespace, kubeconfigContent);
        int total = releases.size();

        int from = Math.min(Math.max(offset, 0), total);
        int to = Math.min(from + Math.max(limit, 1), total);
        List<Release> page = releases.subList(from, to);

        List<HelmReleaseDTO> releaseList = new ArrayList<>();
        Map<String,String> versionCache = new HashMap<>();

        for (Release release : page) {
            HelmReleaseDTO releaseDto = HelmReleaseDTO.from(release);

            List<ReleaseEndpointDTO> allEndpoints = new ArrayList<>();

            K8sReleaseEntity metadata = releaseMetadataService.find(releaseDto.namespace, releaseDto.name);
            if (metadata != null) {
                releaseDto.managedByUi = metadata.isManagedByUi();
                releaseDto.serviceKey = metadata.getServiceKey();
                releaseDto.repoId = metadata.getRepoId();
                releaseDto.chartRef = metadata.getChartRef();
                if (metadata.getVersion() != null && !metadata.getVersion().isBlank()) {
                    releaseDto.version = metadata.getVersion();
                }
                releaseDto.restartRequired =
                        currentGlobalFingerprint != null
                                && metadata.getGlobalConfigVersion() != null
                                && !currentGlobalFingerprint.equals(metadata.getGlobalConfigVersion());
                if (metadata.getServiceKey() == null || metadata.getServiceKey().isBlank()) {
                    releaseDto.restartRequired = false;
                }
                releaseDto.securityProfile = metadata.getSecurityProfile();
                releaseDto.deploymentMode = metadata.getDeploymentMode();
                releaseDto.gitCommitSha = metadata.getGitCommitSha();
                releaseDto.gitBranch = metadata.getGitBranch();
                releaseDto.gitPath = metadata.getGitPath();
                releaseDto.gitRepoUrl = metadata.getGitRepoUrl();
                releaseDto.gitPrUrl = metadata.getGitPrUrl();
                releaseDto.gitPrNumber = metadata.getGitPrNumber();
                releaseDto.gitPrState = metadata.getGitPrState();

                // Backend-aware status lookup (Flux or direct Helm)
                try {
                    HelmReleaseDTO refreshed = commandService.statusViaBackend(releaseDto.namespace, releaseDto.name, metadata.getDeploymentMode());
                    if (refreshed != null) {
                        if (refreshed.status != null) releaseDto.status = refreshed.status;
                        if (refreshed.message != null) releaseDto.message = refreshed.message;
                        releaseDto.lastAppliedRevision = refreshed.lastAppliedRevision;
                        releaseDto.lastAttemptedRevision = refreshed.lastAttemptedRevision;
                        releaseDto.lastHandledReconcileAt = refreshed.lastHandledReconcileAt;
                        releaseDto.sourceStatus = refreshed.sourceStatus;
                        releaseDto.sourceMessage = refreshed.sourceMessage;
                        releaseDto.sourceName = refreshed.sourceName;
                        releaseDto.sourceNamespace = refreshed.sourceNamespace;
                        releaseDto.reconcileState = refreshed.reconcileState;
                        releaseDto.reconcileMessage = refreshed.reconcileMessage;
                        releaseDto.observedGeneration = refreshed.observedGeneration;
                        releaseDto.desiredGeneration = refreshed.desiredGeneration;
                        releaseDto.staleGeneration = refreshed.staleGeneration;
                        releaseDto.conditions = refreshed.conditions;
                        releaseDto.sourceConditions = refreshed.sourceConditions;
                        releaseDto.lastTransitionTime = refreshed.lastTransitionTime;
                        if (refreshed.gitPrState != null) {
                            releaseDto.gitPrState = refreshed.gitPrState;
                        }
                        if (refreshed.gitPrUrl != null) {
                            releaseDto.gitPrUrl = refreshed.gitPrUrl;
                        }
                    }
                } catch (Exception ex) {
                    LOG.warn("Backend status lookup failed for {}/{}: {}", releaseDto.namespace, releaseDto.name, ex.toString());
                }

                if (metadata.getSecurityProfile() != null && !metadata.getSecurityProfile().isBlank()) {
                    try {
                        var currentProfile = securityProfileService.resolveProfile(metadata.getSecurityProfile());
                        String currentHash = securityProfileService.fingerprint(currentProfile);
                        String storedHash = metadata.getSecurityProfileHash();
                        if (currentHash != null && storedHash != null && !currentHash.equals(storedHash)) {
                            releaseDto.securityProfileStale = true;
                        }
                    } catch (Exception ex) {
                        LOG.warn("Failed to compare security profile for {}/{}: {}", releaseDto.namespace, releaseDto.name, ex.toString());
                    }
                }

                String endpointsJson = metadata.getEndpointsJson();
                if (endpointsJson != null && !endpointsJson.isEmpty()) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> rawList =
                                objectMapper.readValue(endpointsJson, List.class);

                        if (rawList != null && !rawList.isEmpty()) {
                            for (Map<String, Object> e : rawList) {
                                ReleaseEndpointDTO dto = new ReleaseEndpointDTO();
                                if (e.get("id") != null) dto.setId(String.valueOf(e.get("id")));
                                if (e.get("label") != null) dto.setLabel(String.valueOf(e.get("label")));
                                if (e.get("url") != null) dto.setUrl(String.valueOf(e.get("url")));
                                if (e.get("description") != null) dto.setDescription(String.valueOf(e.get("description")));
                                if (e.get("kind") != null) dto.setKind(String.valueOf(e.get("kind")));
                                allEndpoints.add(dto);
                            }
                        }
                    } catch (Exception ex) {
                        LOG.warn("Failed to parse endpointsJson for {}/{}: {}",
                                releaseDto.namespace, releaseDto.name, ex.toString());
                    }
                }
            } else {
                releaseDto.managedByUi = false;
                releaseDto.restartRequired = false;
            }

            allEndpoints.addAll(
                    releaseMetadataService.discoverExternalClusterEndpoints(releaseDto.namespace, releaseDto.name)
            );

            if (!allEndpoints.isEmpty()) {
                releaseDto.endpoints = allEndpoints;
            }

            // If version is still missing, attempt to resolve via helm show chart
            if (releaseDto.version == null || releaseDto.version.isBlank()) {
                String chartRef = metadata != null && metadata.getChartRef() != null && !metadata.getChartRef().isBlank()
                        ? metadata.getChartRef()
                        : releaseDto.chart;
                String cacheKey = chartRef + "::" + (metadata != null ? metadata.getVersion() : "");
                String resolved = versionCache.get(cacheKey);
                if (resolved == null) {
                    resolved = helmService.resolveChartVersion(chartRef, metadata != null ? metadata.getVersion() : null);
                    versionCache.put(cacheKey, resolved == null ? "" : resolved);
                }
                if (resolved != null && !resolved.isBlank()) {
                    releaseDto.version = resolved;
                }
            }

            releaseList.add(releaseDto);
        }

        return new HelmReleasesResponse(releaseList, total);
    }

    @GET
    @Path("/releases/{namespace}/{release}/status")
    public Response releaseStatus(@PathParam("namespace") String namespace,
                                  @PathParam("release") String releaseName) {
        try {
            K8sReleaseEntity metadata = releaseMetadataService.find(namespace, releaseName);
            String deploymentMode = metadata != null ? metadata.getDeploymentMode() : null;
            HelmReleaseDTO status = commandService.statusViaBackend(namespace, releaseName, deploymentMode);
            if (status == null) {
                status = new HelmReleaseDTO();
                status.name = releaseName;
                status.namespace = namespace;
                status.status = "UNKNOWN";
            }
            if (metadata != null) {
                status.managedByUi = metadata.isManagedByUi();
                status.repoId = metadata.getRepoId();
                status.serviceKey = metadata.getServiceKey();
                status.deploymentMode = metadata.getDeploymentMode();
                status.gitCommitSha = metadata.getGitCommitSha();
                status.gitBranch = metadata.getGitBranch();
                status.gitPath = metadata.getGitPath();
                status.gitRepoUrl = metadata.getGitRepoUrl();
                status.gitPrUrl = metadata.getGitPrUrl();
                status.gitPrNumber = metadata.getGitPrNumber();
                status.gitPrState = metadata.getGitPrState();
                status.securityProfile = metadata.getSecurityProfile();
            }
            return Response.ok(status).build();
        } catch (Exception ex) {
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    @POST
    @Path("/deploy")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response deploy(HelmDeployRequest deployRequest,
                          @QueryParam("repoId") String repositoryId,
                          @QueryParam("version") String chartVersion,
                          @QueryParam("kubeContext") String kubeContext,
                          @QueryParam("timeoutSec") @DefaultValue("900") int timeoutSeconds,
                          @QueryParam("wait") @DefaultValue("true") boolean waitForCompletion,
                          @QueryParam("atomic") @DefaultValue("false") boolean atomicUpgrade) {

        try {
            if (kubeContext == null) {
                kubeContext = getKubeconfigContents();
            }
            
            HelmService helmService = new HelmService(viewContext);
            Release deployedRelease = helmService.deployOrUpgrade(
                deployRequest, kubeContext, repositoryId, chartVersion
              );
            RELEASE_CACHE.clear(); // invalidate cache after mutation
            LOG.info("Deployed release: {} in namespace: {} with id:{}", deployedRelease.getName(), deployedRelease.getNamespace());
            return Response.ok(buildReleaseDto(deployedRelease)).build();
        } catch (Exception e) {
            LOG.error("Deployment failed", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/upgrade")
    public HelmReleaseDTO upgrade(HelmUpgradeRequest upgradeRequest) {
        if (upgradeRequest == null) {
            throw new IllegalArgumentException("Empty request");
        }

        // Build the same request type used by deploy
        HelmDeployRequest deployRequest = new HelmDeployRequest();
        deployRequest.setReleaseName(upgradeRequest.getReleaseName());
        deployRequest.setNamespace(upgradeRequest.getNamespace());

        // Prefer explicit chartRef if provided; else use chartName (normalized by repoId in service)
        String chartReference = upgradeRequest.getChartRef() != null && !upgradeRequest.getChartRef().isBlank()
                ? upgradeRequest.getChartRef()
                : upgradeRequest.getChartName();
                
        if (chartReference == null || chartReference.isBlank()) {
            throw new IllegalArgumentException("chartRef or chartName is required");
        }
        
        deployRequest.setChart(chartReference);
        deployRequest.setValues(upgradeRequest.getValues());

        Release upgradedRelease = new HelmService(viewContext)
                .deployOrUpgrade(deployRequest, getKubeconfigContents(), 
                               upgradeRequest.getRepoId(), upgradeRequest.getVersion());

        RELEASE_CACHE.clear(); // invalidate cache after mutation
        return HelmReleaseDTO.from(upgradedRelease);
    }

    private static Map<String, Object> buildReleaseDto(Release release) {
        Map<String, Object> releaseDto = new LinkedHashMap<>();
        if (release == null) {
            releaseDto.put("status", "UNKNOWN");
            return releaseDto;
        }
        
        releaseDto.put("name", release.getName());
        releaseDto.put("namespace", release.getNamespace());
        
        try { 
            releaseDto.put("revision", release.getRevision()); 
        } catch (Throwable ignored) {
            // Ignore revision extraction errors
        }
        
        try { 
            releaseDto.put("status", release.getStatus()); 
        } catch (Throwable ignored) {
            // Ignore status extraction errors
        }
        
        return releaseDto;
    }

    private static List<HelmReleaseDTO> getCachedReleases(String namespace) {
        String key = namespace == null ? "__ALL__" : namespace;
        CachedReleases cached = RELEASE_CACHE.get(key);
        if (cached == null) return null;
        if (System.currentTimeMillis() - cached.timestampMs > RELEASE_CACHE_TTL_MILLIS) {
            RELEASE_CACHE.remove(key);
            return null;
        }
        return cached.releases;
    }

    private static void putCachedReleases(String namespace, List<HelmReleaseDTO> releases) {
        String key = namespace == null ? "__ALL__" : namespace;
        RELEASE_CACHE.put(key, new CachedReleases(System.currentTimeMillis(), releases));
    }

    private static final class CachedReleases {
        final long timestampMs;
        final List<HelmReleaseDTO> releases;
        CachedReleases(long ts, List<HelmReleaseDTO> releases) {
            this.timestampMs = ts;
            this.releases = releases;
        }
    }

    @DELETE
    @Path("/release/{namespace}/{name}")
    public Response uninstall(@PathParam("namespace") String namespace,
                             @PathParam("name") String releaseName,
                             @QueryParam("gitRepoUrl") String gitRepoUrl,
                             @QueryParam("gitAuthToken") String gitAuthToken,
                             @QueryParam("gitSshKey") String gitSshKey,
                             @QueryParam("gitBranch") String gitBranch) {
        K8sReleaseEntity meta = releaseMetadataService.find(namespace, releaseName);
        // If this was deployed via Flux GitOps, try to invoke the Flux backend first so Git manifests are removed.
        try {
            if (meta != null && "FLUX_GITOPS".equalsIgnoreCase(meta.getDeploymentMode())) {
                try {
                    String branchToUse = gitBranch != null && !gitBranch.isBlank() ? gitBranch : meta.getGitBranch();
                    String repoUrlToUse = gitRepoUrl != null && !gitRepoUrl.isBlank() ? gitRepoUrl : meta.getGitRepoUrl();
                    String repoPath = meta.getGitPath();
                    String authTokenToUse = gitAuthToken;
                    String sshKeyToUse = gitSshKey;
                    if ((authTokenToUse == null || authTokenToUse.isBlank()) && (sshKeyToUse == null || sshKeyToUse.isBlank())) {
                        GitCredentialService gitCredentialService = new GitCredentialService(viewContext);
                        String resolved = gitCredentialService.resolveSecret(meta.getGitCredentialAlias());
                        // Heuristic: if it looks like an RSA/PEM key, treat as sshKey, else token
                        if (resolved != null && resolved.contains("BEGIN")) {
                            sshKeyToUse = resolved;
                        } else {
                            authTokenToUse = resolved;
                        }
                    }
                    fluxGitOpsBackend.destroy(repoUrlToUse, branchToUse, repoPath, authTokenToUse, sshKeyToUse);
                    LOG.info("Requested Flux GitOps destroy for {}/{} (repo={}, branch={}, path={})",
                            namespace, releaseName, repoUrlToUse, branchToUse, repoPath);
                } catch (Exception ex) {
                    LOG.warn("Flux destroy failed for {}/{}: {}", namespace, releaseName, ex.toString());
                    return Response.serverError().entity(Map.of(
                            "error", "Flux destroy failed: " + ex.getMessage(),
                            "release", releaseName,
                            "namespace", namespace
                    )).build();
                }
            } else {
                // Use backend dispatcher (defaults to direct Helm).
                commandService.destroyViaBackend(namespace, releaseName, meta != null ? meta.getDeploymentMode() : null);
            }
        } finally {
            try {
                if (meta != null) {
                    releaseMetadataService.delete(namespace, releaseName);
                }
            } catch (Exception ex) {
                LOG.warn("Failed to delete metadata for {}/{} after uninstall: {}", namespace, releaseName, ex.toString());
            }
            RELEASE_CACHE.clear(); // invalidate cache after mutation
        }
        return Response.ok().build();
    }

    @POST
    @Path("/rollback")
    public Response rollback(@QueryParam("namespace") String namespace,
                            @QueryParam("name") String releaseName,
                            @QueryParam("revision") int revision) {
        new HelmService(viewContext).rollback(namespace, releaseName, revision, getKubeconfigContents());
        RELEASE_CACHE.clear(); // invalidate cache after mutation
        return Response.ok().build();
    }

    @GET
    @Path("validate")
    @Produces(MediaType.APPLICATION_JSON)
    public Response validate(@QueryParam("repoId") String repositoryId,
                            @QueryParam("chart") String chartName,
                            @QueryParam("version") String chartVersion) {

        try {
            HelmRepositoryService repositoryService = new HelmRepositoryService(this.viewContext);
            java.nio.file.Path repositoryYaml = repositoryService.ensureHttpRepo(repositoryId);
            HelmClientDefault helmClient = new HelmClientDefault();
            
            boolean chartExists = helmClient.existsInRepo(repositoryYaml, chartName, chartVersion);
            String latestVersion = chartExists ? helmClient.latestVersion(repositoryYaml, chartName) : null;

            return Response.ok(Map.of("exists", chartExists, "latest", latestVersion)).build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("message", e.getMessage()))
                    .build();
        }
    }

    @POST 
    @Path("/validate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response validate(HelmDeployRequest requestBody,
                            @QueryParam("repoId") String repositoryId,
                            @QueryParam("version") String chartVersion,
                            @QueryParam("kubeContext") String kubeContext) {
        try {
            String chartReference = requestBody.getChart();
            PathConfig pathConfig = new PathConfig(viewContext);
            HelmClientDefault helmClient = new HelmClientDefault();
            HelmRepositoryService repositoryService = new HelmRepositoryService(viewContext, helmClient);

            // Ensure repositories.yaml is present + repo registered when possible
            if (repositoryId != null && !repositoryId.isBlank()) {
                try {
                    repositoryService.loginOrSync(repositoryId); // HTTP -> ensureHttpRepo; OCI -> ociLogin (no repo file)
                } catch (Exception ex) {
                    LOG.warn("Repository {} sync failed during validation: {}", repositoryId, ex.toString());
                }
            } else {
                // Try to infer repo id from chartRef prefix (repo/chart)
                int separatorIndex = chartReference != null ? chartReference.indexOf('/') : -1;
                if (separatorIndex > 0) {
                    String inferredRepoId = chartReference.substring(0, separatorIndex);
                    try {
                        repositoryService.ensureHttpRepo(inferredRepoId);
                    } catch (Exception ex) {
                        LOG.debug("No ensure for inferred repository {}: {}", inferredRepoId, ex.toString());
                    }
                }
            }

            boolean chartExists = helmClient.existsInRepo(pathConfig.repositoriesConfig(), chartReference, chartVersion);
            String latestVersion = helmClient.latestVersion(pathConfig.repositoriesConfig(), chartReference);

            Map<String, Object> validationResult = new LinkedHashMap<>();
            validationResult.put("exists", chartExists);
            validationResult.put("resolvedChartRef", chartReference);
            validationResult.put("latest", latestVersion);

            return Response.ok(validationResult).build();
        } catch (Exception e) {
            LOG.error("Validation failed", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }


    @GET
    @Path("/{namespace}/{releaseName}/values")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getValues(@PathParam("namespace") String namespace,
                              @PathParam("releaseName") String releaseName) {
        try {
            // Call the method we just added to KubernetesService
            Map<String, Object> values = this.kubernetesService.getHelmReleaseValues(namespace, releaseName);
            return Response.ok(values).build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Collections.singletonMap("error", e.getMessage()))
                    .build();
        }
    }
}
