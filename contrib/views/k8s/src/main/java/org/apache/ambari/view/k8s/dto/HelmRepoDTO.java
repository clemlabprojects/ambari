package org.apache.ambari.view.k8s.api.dto;

import org.apache.ambari.view.k8s.store.HelmRepoEntity;

public class HelmRepoDTO {
  public String id;
  public String type;       // HTTP | OCI
  public String name;
  public String url;
  public String authMode;   // anonymous | basic | token
  public String username;
  public Boolean authInvalid;
  public String lastChecked; // ISO-8601

  public static HelmRepoDTO fromEntity(HelmRepoEntity e) {
    HelmRepoDTO d = new HelmRepoDTO();
    d.id = e.getId();
    d.type = e.getType();
    d.name = e.getName();
    d.url = e.getUrl();
    d.authMode = e.getAuthMode();
    d.username = e.getUsername();
    d.authInvalid = e.isAuthInvalid();
    return d;
  }
}
