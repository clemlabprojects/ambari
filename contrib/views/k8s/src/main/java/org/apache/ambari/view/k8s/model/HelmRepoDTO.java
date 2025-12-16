package org.apache.ambari.view.k8s.model;

import org.apache.ambari.view.k8s.store.HelmRepoEntity;

/**
 * Data Transfer Object for Helm repository information
 */
public class HelmRepoDTO {
  public String id;
  public String type;       // HTTP | OCI
  public String name;
  public String url;
  public String authMode;   // anonymous | basic | token
  public String username;
  public Boolean authInvalid;
  public String lastChecked; // ISO-8601
  public String imageProject;
  public String imageRegistryHostOverride; // optional, could even be hidden from normal UI

  public static HelmRepoDTO fromEntity(HelmRepoEntity e) {
    HelmRepoDTO d = new HelmRepoDTO();
    d.id = e.getId();
    d.type = e.getType();
    d.name = e.getName();
    d.url = e.getUrl();
    d.authMode = e.getAuthMode();
    d.username = e.getUsername();
    d.authInvalid = e.isAuthInvalid();
    d.imageProject = e.getImageProject();
    d.imageRegistryHostOverride = e.getImageRegistryHostOverride();

    return d;
  }
}