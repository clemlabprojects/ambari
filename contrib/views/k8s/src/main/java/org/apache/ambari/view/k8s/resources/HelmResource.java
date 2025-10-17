package org.apache.ambari.view.k8s.resources;

import com.marcnuri.helm.Release;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.model.HelmReleaseDTO;
import org.apache.ambari.view.k8s.requests.HelmUpgradeRequest;
import org.apache.ambari.view.k8s.service.HelmService;
import org.apache.ambari.view.k8s.service.HelmRepositoryService;
import org.apache.ambari.view.k8s.service.ViewConfigurationService;
import org.apache.ambari.view.k8s.service.helm.HelmClientDefault;
import org.apache.ambari.view.k8s.service.PathConfig;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST resource for Helm operations including deployments, upgrades, and releases management
 */
@Path("/helm")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class HelmResource {

    private static final Logger LOG = LoggerFactory.getLogger(HelmResource.class);

    @Inject
    private ViewContext viewContext;

    @Inject
    private ViewConfigurationService configService;

    public HelmResource(ViewContext context) {
        this.viewContext = context;
        this.configService = new ViewConfigurationService(viewContext);
    }

    private String getKubeconfigContents() {
        return configService.getKubeconfigContents();
    }

    @GET
    @Path("/releases")
    public List<HelmReleaseDTO> list(@QueryParam("namespace") String namespace) {
        String kubeconfigContent = getKubeconfigContents();
        List<Release> releases = new HelmService(viewContext).list(namespace, kubeconfigContent);
        ReleaseMetadataService metadataService = new ReleaseMetadataService(viewContext);

        List<HelmReleaseDTO> releaseList = new ArrayList<>();
        for (Release release : releases) {
            HelmReleaseDTO releaseDto = HelmReleaseDTO.from(release);
            
            // Enrich with metadata if available
            K8sReleaseEntity metadata = metadataService.find(releaseDto.namespace, releaseDto.name);
            if (metadata != null) {
                releaseDto.managedByUi = metadata.isManagedByUi();
                releaseDto.serviceKey = metadata.getServiceKey();
                releaseDto.repoId = metadata.getRepoId();
                releaseDto.chartRef = metadata.getChartRef();
            } else {
                releaseDto.managedByUi = false;
                // Light fallback: could try to infer serviceKey from chartRef vs charts.json here if needed
            }
            releaseList.add(releaseDto);
        }
        return releaseList;
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

    @DELETE
    @Path("/release/{namespace}/{name}")
    public Response uninstall(@PathParam("namespace") String namespace,
                             @PathParam("name") String releaseName) {
        new HelmService(viewContext).uninstall(namespace, releaseName, getKubeconfigContents());
        return Response.ok().build();
    }

    @POST
    @Path("/rollback")
    public Response rollback(@QueryParam("namespace") String namespace,
                            @QueryParam("name") String releaseName,
                            @QueryParam("revision") int revision) {
        new HelmService(viewContext).rollback(namespace, releaseName, revision, getKubeconfigContents());
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
}

