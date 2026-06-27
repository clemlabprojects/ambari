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
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.service.*;
import org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend;
import org.apache.ambari.view.k8s.service.helm.HelmClientDefault;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.service.PathConfig;
import org.apache.ambari.view.k8s.service.CommandService;
import org.apache.ambari.view.k8s.utils.AmbariLoopbackUrlResolver;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    private final AuthHelper authHelper;

    // Lightweight in-memory cache for releases to avoid hammering Helm/K8s on frequent polls
    private static final Map<String, CachedReleases> RELEASE_CACHE = new ConcurrentHashMap<>();
    private static final long RELEASE_CACHE_TTL_MILLIS = 10_000; // 10s


    public HelmResource(ViewContext context) {
        this.viewContext = context;
        this.configService = new ViewConfigurationService(viewContext);
        this.kubernetesService = KubernetesService.get(viewContext);
        this.fluxGitOpsBackend = new FluxGitOpsBackend(new PathConfig(viewContext).workDir(), this.kubernetesService, viewContext);
        this.commandService = CommandService.get(viewContext);
        this.releaseMetadataService = new ReleaseMetadataService(viewContext);
        this.authHelper = new AuthHelper(viewContext);
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
  public Response installMonitoring(@QueryParam("repoId") String repoId,
                                    @QueryParam("force") @DefaultValue("false") boolean force) {
    try {
      var info = KubernetesService.get(viewContext).ensureMonitoringInstalled(repoId, force);
      if (info == null) {
        return Response.serverError().entity(Map.of("error", "Monitoring install failed or not configured")).build();
      }
      return Response.ok(Map.of("namespace", info.namespace(), "release", info.release(), "url", info.url())).build();
    } catch (Exception e) {
      return Response.serverError().entity(Map.of("error", e.getMessage())).build();
    }
  }

    /**
     * Reset cached monitoring state (prometheus URL/repoId/bootstrap state) to force re-bootstrap.
     */
    @POST
    @Path("/monitoring/reset")
    public Response resetMonitoring() {
        try {
            KubernetesService.get(viewContext).resetMonitoringCache();
            return Response.ok(Map.of("status", "reset")).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    private String getKubeconfigContents() {
        return configService.getKubeconfigContents();
    }

    /**
     * List all Helm releases across the cluster, optionally filtered by namespace.
     * Results are paginated; each entry is enriched with metadata, status, endpoints,
     * and version information resolved in parallel.
     *
     * @param namespace optional Kubernetes namespace to filter results; lists all namespaces when null
     * @param limit     maximum number of releases to return per page
     * @param offset    zero-based index of the first result to return
     * @return paginated response containing release DTOs and total count
     */
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
        // Use a small thread pool to parallelize heavy lookups (status, endpoints, version).
        final int poolSize = Math.max(2, Math.min(8, page.size()));
        ExecutorService workerPool = Executors.newFixedThreadPool(poolSize);
        List<Future<?>> workerFutures = new ArrayList<>();
        LOG.info("Refreshing Helm releases with parallel workers: {} item(s), pool size {}", page.size(), poolSize);

        for (Release release : page) {
            HelmReleaseDTO releaseDto = HelmReleaseDTO.from(release);

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

            } else {
                releaseDto.managedByUi = false;
                releaseDto.restartRequired = false;
            }

            // Heavy work per release in parallel: status, endpoints, version.
            workerFutures.add(workerPool.submit(() -> {
                try {
                    // Backend-aware status lookup
                    if (metadata != null) {
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
                    }

                    // Endpoints: combine stored endpointsJson and live discovery. Live state is the
                    // truth (it sees chart-managed Ingress with spec.tls[] and picks https://), so
                    // we dedup by id and let live win — the install-time snapshot is only used as
                    // a fallback for entries that live discovery couldn't find.
                    LinkedHashMap<String, ReleaseEndpointDTO> endpointsById = new LinkedHashMap<>();
                    for (ReleaseEndpointDTO live :
                            releaseMetadataService.discoverExternalClusterEndpoints(releaseDto.namespace, releaseDto.name)) {
                        if (live.getId() != null) endpointsById.put(live.getId(), live);
                    }
                    if (metadata != null && metadata.getEndpointsJson() != null && !metadata.getEndpointsJson().isEmpty()) {
                        try {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> rawList =
                                    objectMapper.readValue(metadata.getEndpointsJson(), List.class);
                            if (rawList != null) {
                                for (Map<String, Object> e : rawList) {
                                    ReleaseEndpointDTO dto = new ReleaseEndpointDTO();
                                    if (e.get("id") != null) dto.setId(String.valueOf(e.get("id")));
                                    if (e.get("label") != null) dto.setLabel(String.valueOf(e.get("label")));
                                    if (e.get("url") != null) dto.setUrl(String.valueOf(e.get("url")));
                                    if (e.get("description") != null) dto.setDescription(String.valueOf(e.get("description")));
                                    if (e.get("kind") != null) dto.setKind(String.valueOf(e.get("kind")));
                                    if (dto.getId() != null) endpointsById.putIfAbsent(dto.getId(), dto);
                                }
                            }
                        } catch (Exception ex) {
                            LOG.warn("Failed to parse endpointsJson for {}/{}: {}", releaseDto.namespace, releaseDto.name, ex.toString());
                        }
                    }
                    if (!endpointsById.isEmpty()) {
                        releaseDto.endpoints = new ArrayList<>(endpointsById.values());
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
                } catch (Exception ex) {
                    LOG.warn("Async refresh failed for {}/{}: {}", releaseDto.namespace, releaseDto.name, ex.toString());
                }
                return null;
            }));

            releaseList.add(releaseDto);
        }

        // Wait for parallel status refreshes to complete before responding.
        try {
            for (Future<?> future : workerFutures) {
                future.get();
            }
        } catch (Exception ex) {
            LOG.warn("One or more status refresh tasks failed: {}", ex.toString());
        } finally {
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException ie) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        return new HelmReleasesResponse(releaseList, total);
    }

    /**
     * Retrieve the current status and metadata for a single Helm release.
     *
     * @param namespace   Kubernetes namespace of the release
     * @param releaseName name of the Helm release
     * @return the release status DTO, or an error response on failure
     */
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

    /**
     * Re-run Kerberos keytab creation steps for a deployed release without re-installing the chart.
     * This schedules an async command which can be tracked in the background operations list.
     *
     * @param namespace release namespace
     * @param releaseName release name
     * @param requestHeaders caller headers used for Ambari auth
     * @param uriInfo request URI context for building command links
     * @return HTTP 202 with command id and href
     */
    @POST
    @Path("/releases/{namespace}/{release}/actions/keytabs")
    public Response regenerateReleaseKeytabs(@PathParam("namespace") String namespace,
                                             @PathParam("release") String releaseName,
                                             @Context HttpHeaders requestHeaders,
                                             @Context UriInfo uriInfo) {
        try {
            authHelper.checkWritePermission();
            LOG.info("Submitting keytab regeneration for release {}/{}", namespace, releaseName);
            String commandId = commandService.submitReleaseKeytabRegeneration(
                    namespace,
                    releaseName,
                    requestHeaders.getRequestHeaders(),
                    uriInfo.getBaseUri()
            );
            URI commandLocation = UriBuilder.fromUri(getCommandsUrl(uriInfo)).path(commandId).build();
            return Response.status(Response.Status.ACCEPTED)
                    .entity(Map.of("id", commandId, "href", commandLocation.toString()))
                    .location(commandLocation)
                    .build();
        } catch (ForbiddenException fe) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", fe.getMessage()))
                    .build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", iae.getMessage()))
                    .build();
        } catch (Exception ex) {
            LOG.warn("Keytab regeneration failed for {}/{}: {}", namespace, releaseName, ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    /**
     * Re-run Ranger repository creation for a deployed release without a Helm upgrade.
     * The backend reuses the same service.json-defined Ranger specs as install time.
     *
     * @param namespace release namespace
     * @param releaseName release name
     * @param requestHeaders caller headers used for Ambari auth
     * @param uriInfo request URI context for building command links
     * @return HTTP 202 with command id and href
     */
    @POST
    @Path("/releases/{namespace}/{release}/actions/ranger")
    public Response reapplyReleaseRangerRepository(@PathParam("namespace") String namespace,
                                                   @PathParam("release") String releaseName,
                                                   @Context HttpHeaders requestHeaders,
                                                   @Context UriInfo uriInfo) {
        try {
            authHelper.checkWritePermission();
            LOG.info("Submitting Ranger repository reapply for release {}/{}", namespace, releaseName);
            String commandId = commandService.submitReleaseRangerRepositoryReapply(
                    namespace,
                    releaseName,
                    requestHeaders.getRequestHeaders(),
                    uriInfo.getBaseUri()
            );
            URI commandLocation = UriBuilder.fromUri(getCommandsUrl(uriInfo)).path(commandId).build();
            return Response.status(Response.Status.ACCEPTED)
                    .entity(Map.of("id", commandId, "href", commandLocation.toString()))
                    .location(commandLocation)
                    .build();
        } catch (ForbiddenException fe) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", fe.getMessage()))
                    .build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", iae.getMessage()))
                    .build();
        } catch (Exception ex) {
            LOG.warn("Ranger repository reapply failed for {}/{}: {}", namespace, releaseName, ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    /**
     * Replay the OpenMetadata→Ranger TagSync source registration for a deployed release.
     * Mints a fresh ingestion-bot JWT via exec-in-pod, writes the source into Ambari's
     * {@code ranger-tagsync-site} stack config, and restarts the Ranger TagSync
     * component. Only valid for releases whose service.json declares a
     * {@code ranger-tagsync-source} entry.
     */
    @POST
    @Path("/releases/{namespace}/{release}/actions/om-ranger-tagsync")
    public Response reapplyReleaseOmRangerTagSync(@PathParam("namespace") String namespace,
                                                  @PathParam("release") String releaseName,
                                                  @Context HttpHeaders requestHeaders,
                                                  @Context UriInfo uriInfo) {
        try {
            authHelper.checkWritePermission();
            LOG.info("Submitting OM→Ranger TagSync reapply for release {}/{}", namespace, releaseName);
            // Use the loopback Ambari API base, NOT uriInfo.getBaseUri() — the
            // latter points at the VIEW resource path (.../resources/api/) which
            // 404s when resolveClusterName tries /clusters under it. The deploy
            // submit path does the same conversion (CommandResource.submitDeploy).
            String commandId = commandService.submitReleaseOmRangerTagSyncReapply(
                    namespace,
                    releaseName,
                    requestHeaders.getRequestHeaders(),
                    AmbariLoopbackUrlResolver.resolveApiBaseUri(viewContext)
            );
            URI commandLocation = UriBuilder.fromUri(getCommandsUrl(uriInfo)).path(commandId).build();
            return Response.status(Response.Status.ACCEPTED)
                    .entity(Map.of("id", commandId, "href", commandLocation.toString()))
                    .location(commandLocation)
                    .build();
        } catch (ForbiddenException fe) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", fe.getMessage())).build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", iae.getMessage())).build();
        } catch (Exception ex) {
            LOG.warn("OM→Ranger TagSync reapply failed for {}/{}: {}", namespace, releaseName, ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    /**
     * Replay the OpenMetadata Atlas-federation registration for a deployed release.
     * Mints a fresh ingestion-bot JWT and upserts the four OM REST entities
     * (metadataService, databaseService, ingestionPipeline, trigger).
     */
    @POST
    @Path("/releases/{namespace}/{release}/actions/om-atlas-federation")
    public Response reapplyReleaseOmAtlasFederation(@PathParam("namespace") String namespace,
                                                    @PathParam("release") String releaseName,
                                                    @Context HttpHeaders requestHeaders,
                                                    @Context UriInfo uriInfo) {
        try {
            authHelper.checkWritePermission();
            LOG.info("Submitting OM Atlas federation reapply for release {}/{}", namespace, releaseName);
            // Use the loopback Ambari API base, NOT uriInfo.getBaseUri() — same fix
            // as actions/om-ranger-tagsync. The view's base URI points at the VIEW
            // resource path and 404s when resolveClusterName tries /clusters under it,
            // also fails SSL cert validation when the view is fronted by a different
            // hostname than the loopback API base.
            //
            // Ranger credentials are no longer passed here: a MANAGED platform context
            // delegates the grant to the Ambari server (which holds the password) and an
            // EXTERNAL context carries its own stored credentials.
            String commandId = commandService.submitReleaseOmAtlasFederationReapply(
                    namespace,
                    releaseName,
                    requestHeaders.getRequestHeaders(),
                    AmbariLoopbackUrlResolver.resolveApiBaseUri(viewContext)
            );
            URI commandLocation = UriBuilder.fromUri(getCommandsUrl(uriInfo)).path(commandId).build();
            return Response.status(Response.Status.ACCEPTED)
                    .entity(Map.of("id", commandId, "href", commandLocation.toString()))
                    .location(commandLocation)
                    .build();
        } catch (ForbiddenException fe) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", fe.getMessage())).build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", iae.getMessage())).build();
        } catch (Exception ex) {
            LOG.warn("OM Atlas federation reapply failed for {}/{}: {}", namespace, releaseName, ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    /**
     * Read-only Atlas-federation config-drift check for a deployed release. Surfaces whether an
     * operator's manual Atlas edits (file auth disabled, federation user removed, password rotated,
     * or a pending Atlas restart) would break the running OM federation. Mutates nothing.
     */
    @GET
    @Path("/releases/{namespace}/{release}/actions/atlas-federation-check")
    public Response checkReleaseAtlasFederationConfig(@PathParam("namespace") String namespace,
                                                      @PathParam("release") String releaseName,
                                                      @Context HttpHeaders requestHeaders) {
        try {
            authHelper.checkWritePermission();
            Map<String, Object> report = commandService.checkAtlasFederationConfig(
                    namespace, releaseName,
                    requestHeaders.getRequestHeaders(),
                    AmbariLoopbackUrlResolver.resolveApiBaseUri(viewContext));
            return Response.ok(report).build();
        } catch (ForbiddenException fe) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", fe.getMessage())).build();
        } catch (Exception ex) {
            LOG.warn("Atlas federation config-check failed for {}/{}: {}", namespace, releaseName, ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    /**
     * One-click "Fix &amp; restart Atlas" from the config-check modal: re-asserts the federation config
     * idempotently and restarts ATLAS_SERVER, returning the Ambari request id so the UI can poll progress.
     */
    @POST
    @Path("/releases/{namespace}/{release}/actions/atlas-federation-fix-restart")
    public Response fixAndRestartAtlasFederation(@PathParam("namespace") String namespace,
                                                 @PathParam("release") String releaseName,
                                                 @Context HttpHeaders requestHeaders) {
        try {
            authHelper.checkWritePermission();
            Map<String, Object> result = commandService.fixAndRestartAtlasFederation(
                    namespace, releaseName,
                    requestHeaders.getRequestHeaders(),
                    AmbariLoopbackUrlResolver.resolveApiBaseUri(viewContext));
            return Response.ok(result).build();
        } catch (ForbiddenException fe) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", fe.getMessage())).build();
        } catch (Exception ex) {
            LOG.warn("Atlas federation fix-restart failed for {}/{}: {}", namespace, releaseName, ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    /** Poll an Ambari request's live status + progress (used by the Fix &amp; restart progress bar). */
    @GET
    @Path("/releases/{namespace}/{release}/actions/ambari-request/{requestId}")
    public Response getAmbariRequestProgress(@PathParam("namespace") String namespace,
                                             @PathParam("release") String releaseName,
                                             @PathParam("requestId") int requestId,
                                             @Context HttpHeaders requestHeaders) {
        try {
            authHelper.checkWritePermission();
            Map<String, Object> progress = commandService.getAmbariRequestProgress(
                    requestId,
                    requestHeaders.getRequestHeaders(),
                    AmbariLoopbackUrlResolver.resolveApiBaseUri(viewContext));
            return Response.ok(progress).build();
        } catch (ForbiddenException fe) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", fe.getMessage())).build();
        } catch (Exception ex) {
            LOG.warn("Ambari request progress poll failed for request {}: {}", requestId, ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    /**
     * Register (or re-register) the OIDC client(s) for a deployed release without re-installing
     * the chart.  Reads oidc[] from the service definition, calls Keycloak admin API, and
     * writes/rotates the resulting credentials in a Kubernetes Secret.
     *
     * @param namespace      release namespace
     * @param releaseName    release name
     * @param requestHeaders caller headers for Ambari/Keycloak auth
     * @param uriInfo        request URI context for building command links
     * @return HTTP 202 with command id and href
     */
    @POST
    @Path("/releases/{namespace}/{release}/actions/oidc")
    public Response registerReleaseOidcClient(@PathParam("namespace") String namespace,
                                              @PathParam("release") String releaseName,
                                              @Context HttpHeaders requestHeaders,
                                              @Context UriInfo uriInfo) {
        try {
            authHelper.checkWritePermission();
            LOG.info("Submitting OIDC client registration for release {}/{}", namespace, releaseName);
            String commandId = commandService.submitReleaseOidcRegistration(
                    namespace,
                    releaseName,
                    requestHeaders.getRequestHeaders(),
                    uriInfo.getBaseUri()
            );
            URI commandLocation = UriBuilder.fromUri(getCommandsUrl(uriInfo)).path(commandId).build();
            return Response.status(Response.Status.ACCEPTED)
                    .entity(Map.of("id", commandId, "href", commandLocation.toString()))
                    .location(commandLocation)
                    .build();
        } catch (ForbiddenException fe) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", fe.getMessage()))
                    .build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", iae.getMessage()))
                    .build();
        } catch (Exception ex) {
            LOG.warn("OIDC registration failed for {}/{}: {}", namespace, releaseName, ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    /**
     * Roll back a deployed release to a specific Helm revision picked by the operator.
     *
     * @param namespace      release namespace
     * @param releaseName    release name
     * @param revision       target Helm revision number (required, must be > 0)
     * @return HTTP 200 on success
     */
    @POST
    @Path("/releases/{namespace}/{release}/actions/rollback")
    public Response rollbackReleaseToRevision(@PathParam("namespace") String namespace,
                                              @PathParam("release") String releaseName,
                                              @QueryParam("revision") int revision) {
        try {
            authHelper.checkWritePermission();
            if (revision <= 0) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "revision query parameter is required and must be > 0"))
                        .build();
            }
            LOG.info("Rolling back release {}/{} to revision {}", namespace, releaseName, revision);
            new HelmService(viewContext).rollback(namespace, releaseName, revision, getKubeconfigContents());
            RELEASE_CACHE.clear();
            return Response.ok(Map.of("ok", true, "revision", revision)).build();
        } catch (ForbiddenException fe) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", fe.getMessage()))
                    .build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", iae.getMessage()))
                    .build();
        } catch (Exception ex) {
            LOG.warn("Rollback failed for {}/{}: {}", namespace, releaseName, ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    /**
     * List the Helm revision history for a release. Each entry mirrors {@code helm history -o json}:
     * {@code revision}, {@code updated}, {@code status}, {@code chart}, {@code app_version}, {@code description}.
     *
     * @param namespace   release namespace
     * @param releaseName release name
     * @return ordered list of revisions; 200 on success or 500 with the helm CLI error otherwise
     */
    @GET
    @Path("/releases/{namespace}/{release}/history")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getReleaseHistory(@PathParam("namespace") String namespace,
                                      @PathParam("release") String releaseName) {
        try {
            List<Map<String, Object>> history = new HelmService(viewContext)
                    .history(namespace, releaseName, getKubeconfigContents());
            return Response.ok(history).build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", iae.getMessage()))
                    .build();
        } catch (Exception ex) {
            LOG.warn("Failed to read helm history for {}/{}: {}", namespace, releaseName, ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    /**
     * Describe the current TLS state of a release's Ingress(es).
     * Returns one entry per (Ingress × TLS Secret) combo:
     * <pre>
     *   [{
     *     "ingressName": "superset",
     *     "namespace": "dashboarding",
     *     "secretName": "superset-ingress-tls",
     *     "hosts": ["superset.example.com"],
     *     "source": "k8s-view-self-signed" | "cert-manager" | "external-secrets" | "external",
     *     "status": "valid" | "expiring-warning" | "expiring-soon" | "expired" | "no-tls" | ...,
     *     "issuer": "...",
     *     "subject": "...",
     *     "notBefore": "...",
     *     "notAfter": "...",
     *     "daysUntilExpiry": 87,
     *     "sans": ["..."]
     *   }, ...]
     * </pre>
     */
    @GET
    @Path("/releases/{namespace}/{release}/tls")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getReleaseTlsState(@PathParam("namespace") String namespace,
                                       @PathParam("release") String releaseName) {
        try {
            org.apache.ambari.view.k8s.service.KubernetesService ks =
                    org.apache.ambari.view.k8s.service.KubernetesService.get(viewContext);
            org.apache.ambari.view.k8s.service.ReleaseTlsService svc =
                    new org.apache.ambari.view.k8s.service.ReleaseTlsService(viewContext, ks);
            return Response.ok(svc.describe(namespace, releaseName)).build();
        } catch (Exception ex) {
            LOG.warn("Failed to describe TLS state for {}/{}: {}", namespace, releaseName, ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    /**
     * Operator-triggered renewal of all TLS Secrets for a release. Returns the command id;
     * the UI polls /commands/{id} for progress. Internally schedules one child step per
     * (host × Secret), with dispatch by source:
     *   - k8s-view-self-signed → re-runs INGRESS_TLS_SELFSIGN
     *   - cert-manager         → patches the Certificate CR to force re-issue
     *   - external-secrets     → patches the ExternalSecret to force a Vault re-sync
     *   - external             → no-op (operator owns it)
     */
    @POST
    @Path("/releases/{namespace}/{release}/tls/renew")
    @Produces(MediaType.APPLICATION_JSON)
    public Response renewReleaseTls(@PathParam("namespace") String namespace,
                                    @PathParam("release") String releaseName,
                                    @Context HttpHeaders headers,
                                    @Context UriInfo uriInfo) {
        try {
            String commandId = org.apache.ambari.view.k8s.service.CommandService.get(viewContext)
                    .submitReleaseTlsRenewal(namespace, releaseName,
                            headers.getRequestHeaders(), uriInfo.getBaseUri());
            return Response.ok(Map.of("id", commandId)).build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", iae.getMessage())).build();
        } catch (Exception ex) {
            LOG.warn("Failed to schedule TLS renewal for {}/{}: {}", namespace, releaseName, ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    /**
     * Upgrade an existing Helm release in place to a new chart version, preserving the values
     * captured when the release was originally deployed (read from the running release).
     *
     * @param namespace      release namespace
     * @param releaseName    release name
     * @param targetVersion  chart version to upgrade to; required
     * @param requestHeaders caller headers for downstream Ambari calls
     * @param uriInfo        request URI context for building command links
     * @return HTTP 202 with command id and href
     */
    @POST
    @Path("/releases/{namespace}/{release}/actions/chart-upgrade")
    public Response upgradeReleaseChart(@PathParam("namespace") String namespace,
                                        @PathParam("release") String releaseName,
                                        @QueryParam("version") String targetVersion,
                                        @Context HttpHeaders requestHeaders,
                                        @Context UriInfo uriInfo) {
        try {
            authHelper.checkWritePermission();
            LOG.info("Submitting chart upgrade for release {}/{} → version {}", namespace, releaseName, targetVersion);
            String commandId = commandService.submitReleaseUpgrade(
                    namespace,
                    releaseName,
                    targetVersion,
                    requestHeaders.getRequestHeaders(),
                    uriInfo.getBaseUri()
            );
            URI commandLocation = UriBuilder.fromUri(getCommandsUrl(uriInfo)).path(commandId).build();
            return Response.status(Response.Status.ACCEPTED)
                    .entity(Map.of("id", commandId, "href", commandLocation.toString()))
                    .location(commandLocation)
                    .build();
        } catch (ForbiddenException fe) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", fe.getMessage()))
                    .build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", iae.getMessage()))
                    .build();
        } catch (Exception ex) {
            LOG.warn("Chart upgrade failed for {}/{}: {}", namespace, releaseName, ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    /**
     * Deploy or upgrade a Helm chart synchronously.
     *
     * @param deployRequest    chart, release name, namespace, and Helm values to apply
     * @param repositoryId     optional repository id to look up the chart
     * @param chartVersion     optional chart version to install; uses the latest when omitted
     * @param kubeContext      optional kubeconfig content; defaults to the view-configured kubeconfig
     * @param timeoutSeconds   maximum seconds to wait for the release to become ready
     * @param waitForCompletion whether to block until all Kubernetes resources are ready
     * @param atomicUpgrade    whether to roll back automatically on failure
     * @return the deployed release details, or an error response on failure
     */
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

    /**
     * Upgrade an existing Helm release to a new chart or values set.
     *
     * @param upgradeRequest release name, namespace, chart reference, version, repo id, and new values
     * @return the updated release DTO
     */
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

    /**
     * Build the commands URL for this view instance to expose command status endpoints.
     *
     * @param uriInfo JAX-RS request context for base URI
     * @return fully qualified commands URL for this view instance
     */
    private String getCommandsUrl(UriInfo uriInfo) {
        String base = UriBuilder.fromUri(uriInfo.getBaseUri())
                .path("views")
                .path(viewContext.getViewName())
                .path("versions")
                .path(viewContext.getViewDefinition().getVersion())
                .path("instances")
                .path(viewContext.getInstanceName())
                .build()
                .toString();

        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return base + "resources/api/commands";
    }

    /**
     * Uninstall a Helm release and delete its stored metadata.
     * For Flux GitOps releases, the corresponding Git manifests are removed first.
     *
     * @param namespace    Kubernetes namespace of the release
     * @param releaseName  name of the Helm release to uninstall
     * @param gitRepoUrl   optional Git repository URL to override the stored value (Flux mode only)
     * @param gitAuthToken optional Git token to override the stored credential (Flux mode only)
     * @param gitSshKey    optional SSH private key to override the stored credential (Flux mode only)
     * @param gitBranch    optional Git branch to override the stored value (Flux mode only)
     * @return HTTP 200 on success, or an error response on failure
     */
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

    /**
     * Roll back a Helm release to a previously installed revision.
     *
     * @param namespace   Kubernetes namespace of the release
     * @param releaseName name of the Helm release to roll back
     * @param revision    target revision number to restore
     * @return HTTP 200 on success, or an error response on failure
     */
    @POST
    @Path("/rollback")
    public Response rollback(@QueryParam("namespace") String namespace,
                            @QueryParam("name") String releaseName,
                            @QueryParam("revision") int revision) {
        new HelmService(viewContext).rollback(namespace, releaseName, revision, getKubeconfigContents());
        RELEASE_CACHE.clear(); // invalidate cache after mutation
        return Response.ok().build();
    }

    /**
     * Check whether a chart exists in the given repository and report the latest available version.
     *
     * @param repositoryId repository id whose index to search
     * @param chartName    name of the chart to look up
     * @param chartVersion specific chart version to check; pass null to check any version
     * @return JSON with {@code exists} (boolean) and {@code latest} (version string) fields
     */
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

    /**
     * Validate a chart reference from a deploy request, ensuring the repository is synced
     * and returning the resolved chart existence and latest version.
     *
     * @param requestBody  deploy request carrying the chart reference to validate
     * @param repositoryId optional repository id to sync before validating; inferred from chart prefix when absent
     * @param chartVersion optional chart version to verify
     * @param kubeContext  optional kubeconfig context (currently unused during validation)
     * @return JSON with {@code exists}, {@code resolvedChartRef}, and {@code latest} fields
     */
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


    /**
     * Retrieve the computed Helm values currently applied to a release.
     *
     * @param namespace   Kubernetes namespace of the release
     * @param releaseName name of the Helm release
     * @return the effective values map, or an error response on failure
     */
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
