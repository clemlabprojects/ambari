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
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/helm")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class HelmResource {

  private static final Logger LOG = LoggerFactory.getLogger(HelmResource.class);

  @Inject
  private ViewContext viewContext;

  @Inject
  private ViewConfigurationService cfg;

  public HelmResource(ViewContext context) {
    this.viewContext = context;
    this.cfg = new ViewConfigurationService(viewContext);
  }

  private String kubeconfig() {
    return cfg.getKubeconfigContents();
  }


  @GET
  @Path("/releases")
  public List<HelmReleaseDTO> list(@QueryParam("namespace") String ns) {
    String kube = kubeconfig();
    List<Release> releases = new HelmService(viewContext).list(ns, kube);
    ReleaseMetadataService rms = new ReleaseMetadataService(viewContext);

    List<HelmReleaseDTO> out = new ArrayList<>();
    for (Release r : releases) {
      HelmReleaseDTO d = HelmReleaseDTO.from(r);
      // enrichissement
      K8sReleaseEntity meta = rms.find(d.namespace, d.name);
      if (meta != null) {
        d.managedByUi = meta.isManagedByUi();
        d.serviceKey  = meta.getServiceKey();
        d.repoId      = meta.getRepoId();
        d.chartRef    = meta.getChartRef();
      } else {
        d.managedByUi = false;
        // fallback très léger: on peut tenter d'inférer serviceKey par le chartRef vs charts.json ici si tu veux
      }
      out.add(d);
    }
    return out;
  }

  // old implementation, returnin Release directly does not work with Ambari's JSON serializer
  // @POST
  // @Path("/deploy")
  // public Release deploy(HelmDeployRequest req,
  //                       @QueryParam("repoId") String repoId,
  //                       @QueryParam("version") String version) {
  //   return new HelmService(viewContext).deployOrUpgrade(req, kubeconfig(), repoId, version);
  // }
  @POST
  @Path("/deploy")
  @Consumes(javax.ws.rs.core.MediaType.APPLICATION_JSON)
  @Produces(javax.ws.rs.core.MediaType.APPLICATION_JSON)
  public javax.ws.rs.core.Response deploy(
      org.apache.ambari.view.k8s.requests.HelmDeployRequest req,
      @QueryParam("repoId")      String repoId,
      @QueryParam("version")     String version,
      @QueryParam("kubeContext") String kubeContext,
      @QueryParam("timeoutSec")  @DefaultValue("900")  int timeoutSec,
      @QueryParam("wait")        @DefaultValue("true") boolean wait,
      @QueryParam("atomic")      @DefaultValue("false") boolean atomic) {

    try {
      if (kubeContext == null) {
        kubeContext = kubeconfig();
      }
      HelmService svc = new HelmService(viewContext);
      Release rel = svc.deployOrUpgrade(
          req, kubeContext, repoId, version, timeoutSec, wait, atomic);

      return javax.ws.rs.core.Response.ok(toReleaseDto(rel)).build();
    } catch (Exception e) {
      LOG.error("Deploy failed", e);
      return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR)
          .entity(java.util.Map.of("error", e.getMessage()))
          .build();
    }
  }

  @POST
  @Path("/upgrade")
  public HelmReleaseDTO upgrade(HelmUpgradeRequest body) {
    if (body == null) throw new IllegalArgumentException("Empty request");

    // Build the same request type used by deploy:
    HelmDeployRequest req = new HelmDeployRequest();
    req.setReleaseName(body.getReleaseName());
    req.setNamespace(body.getNamespace());

    // Prefer explicit chartRef if provided; else use chartName (normalized by repoId in service)
    String chart = body.getChartRef() != null && !body.getChartRef().isBlank()
        ? body.getChartRef()
        : body.getChartName();
    if (chart == null || chart.isBlank()) {
      throw new IllegalArgumentException("chartRef or chartName is required");
    }
    req.setChart(chart);
    req.setValues(body.getValues());

    Release rel = new HelmService(viewContext)
        .deployOrUpgrade(req, kubeconfig(), body.getRepoId(), body.getVersion());

    return HelmReleaseDTO.from(rel);
  }

  private static Map<String, Object> toReleaseDto(com.marcnuri.helm.Release rel) {
    Map<String, Object> dto = new LinkedHashMap<>();
    if (rel == null) {
      dto.put("status", "UNKNOWN");
      return dto;
    }
    dto.put("name", rel.getName());
    dto.put("namespace", rel.getNamespace());
    try { dto.put("revision", rel.getRevision()); } catch (Throwable ignored) {}
    try { dto.put("status",   rel.getStatus());   } catch (Throwable ignored) {}
    return dto;
  }

  @DELETE
  @Path("/release/{namespace}/{name}")
  public Response uninstall(@PathParam("namespace") String ns,
                            @PathParam("name") String name) {
    new HelmService(viewContext).uninstall(ns, name, kubeconfig());
    return Response.ok().build();
  }

  @POST
  @Path("/rollback")
  public Response rollback(@QueryParam("namespace") String ns,
                           @QueryParam("name") String name,
                           @QueryParam("revision") int rev) {
    new HelmService(viewContext).rollback(ns, name, rev, kubeconfig());
    return Response.ok().build();
  }

  @GET
  @Path("validate")
  @Produces(MediaType.APPLICATION_JSON)
  public Response validate(@QueryParam("repoId") String repoId,
                           @QueryParam("chart")  String chart,
                           @QueryParam("version") String version) {

    try {
      HelmRepositoryService repoSvc = new HelmRepositoryService(this.viewContext);
      java.nio.file.Path repoYaml = repoSvc.ensureHttpRepo(repoId);
      HelmClientDefault helmClient = new HelmClientDefault();
      boolean ok    = helmClient.existsInRepo(repoYaml, chart, version);
      String latest = ok ? helmClient.latestVersion(repoYaml, chart) : null;

      return Response.ok(Map.of("exists", ok, "latest", latest)).build();
    } catch (Exception e) {
      return Response.serverError()
                     .entity(Map.of("message", e.getMessage()))
                     .build();
    }
  }

  @POST @Path("/validate")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response validate(HelmDeployRequest body,
                          @QueryParam("repoId") String repoId,
                          @QueryParam("version") String version,
                          @QueryParam("kubeContext") String kubeContext) {
    try {
      String chartRef = body.getChart();
      var paths = new PathConfig(viewContext);
      var helmClient = new HelmClientDefault();
      var repos = new HelmRepositoryService(viewContext, helmClient);

      // Ensure repositories.yaml is present + repo registered when possible
      if (repoId != null && !repoId.isBlank()) {
        try {
          repos.loginOrSync(repoId); // HTTP -> ensureHttpRepo; OCI -> ociLogin (no repo file)
        } catch (Exception ex) {
          LOG.warn("Repo {} sync failed during validate: {}", repoId, ex.toString());
        }
      } else {
        // Try to infer repo id from chartRef prefix (repo/chart)
        int idx = chartRef != null ? chartRef.indexOf('/') : -1;
        if (idx > 0) {
          String maybeRepoId = chartRef.substring(0, idx);
          try {
            repos.ensureHttpRepo(maybeRepoId);
          } catch (Exception ex) {
            LOG.debug("No ensure for inferred repo {}: {}", maybeRepoId, ex.toString());
          }
        }
      }

      boolean exists = helmClient.existsInRepo(paths.repositoriesConfig(), chartRef, version);
      String latest = helmClient.latestVersion(paths.repositoriesConfig(), chartRef);

      Map<String,Object> out = new java.util.LinkedHashMap<>();
      out.put("exists", exists);
      out.put("resolvedChartRef", chartRef);
      out.put("latest", latest);

      return Response.ok(out).build();
    } catch (Exception e) {
      LOG.error("Validate failed", e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(java.util.Map.of("error", e.getMessage()))
          .build();
    }
  }
}

