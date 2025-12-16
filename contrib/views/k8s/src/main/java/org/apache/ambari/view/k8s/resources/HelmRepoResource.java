package org.apache.ambari.view.k8s.resources;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.HelmRepoDTO;
import org.apache.ambari.view.k8s.service.HelmRepositoryService;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HelmRepoResource {

  private static final Logger LOG = LoggerFactory.getLogger(HelmRepoResource.class);


  @Inject
  private ViewContext viewContext;

  public HelmRepoResource(ViewContext context) {
    this.viewContext = context;
  }

  @GET
  public List<HelmRepoDTO> list() {
    var svc = new HelmRepositoryService(viewContext);
    var repos = svc.list().stream().map(HelmRepoDTO::fromEntity).collect(Collectors.toList());
    LOG.info("found repos: {}", repos.size());
    for(HelmRepoDTO helmRepoDTO : repos){
      LOG.info("repoinfo: id: {}, imageProject: {},  url:{}, name:{}, " +
              "", helmRepoDTO.id, helmRepoDTO.imageProject, helmRepoDTO.url, helmRepoDTO.name);
    }
    return svc.list().stream().map(HelmRepoDTO::fromEntity).collect(Collectors.toList());
  }

  @POST
  public HelmRepoDTO save(HelmRepoEntity e, @QueryParam("secret") String plainSecret) {
    var svc = new HelmRepositoryService(viewContext);
    HelmRepoEntity out = svc.save(e, plainSecret);
    return HelmRepoDTO.fromEntity(out);
  }

  @POST
  @Path("{id}/login")
  public Response login(@PathParam("id") String id) {
    new HelmRepositoryService(viewContext).loginOrSync(id);
    return Response.noContent().build(); // 204
  }

  @DELETE @Path("{id}")
  public Response delete(@PathParam("id") String id) {
    new HelmRepositoryService(viewContext).delete(id);
    return Response.ok().build();
  }
}
