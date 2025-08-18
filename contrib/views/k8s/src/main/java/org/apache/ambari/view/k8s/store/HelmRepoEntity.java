package org.apache.ambari.view.k8s.store;

import org.apache.ambari.view.k8s.store.base.BaseModel;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/** Metadata for a Helm repository/registry (no secret value here). */
@Entity
@Table(name = "k8s_helm_repo")
public class HelmRepoEntity extends BaseModel {

  @Id
  @Column(length = 128)
  @Override public String getId() { return super.getId(); }
  @Override public void setId(String id) { super.setId(id); }

  @Column(length = 16, nullable = false)
  private String type;        // HTTP | OCI

  @Column(length = 255, nullable = false)
  private String name;        // for HTTP, helm repo name; logical name otherwise

  @Column(length = 1024, nullable = false)
  private String url;         // HTTP: https://...  OCI: registry.host/namespace (no oci:// prefix)

  @Column(length = 16, nullable = false)
  private String authMode;    // anonymous | basic | token

  @Column(length = 128)
  private String username;

  @Column(length = 128)
  private String secretRef;   // pointer in InstanceData (encrypted); never expose

  private boolean authInvalid;

  public String getType() { return type; }
  public void setType(String type) { this.type = type; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getUrl() { return url; }
  public void setUrl(String url) { this.url = url; }
  public String getAuthMode() { return authMode; }
  public void setAuthMode(String authMode) { this.authMode = authMode; }
  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public String getSecretRef() { return secretRef; }
  public void setSecretRef(String secretRef) { this.secretRef = secretRef; }
  public boolean isAuthInvalid() { return authInvalid; }
  public void setAuthInvalid(boolean authInvalid) { this.authInvalid = authInvalid; }

  @Override
  @Column(length = 40)
  public String getCreatedAt() { return super.getCreatedAt(); }
  
  @Override
  public void setCreatedAt(String createdAt) { super.setCreatedAt(createdAt); }

  @Override
  @Column(length = 40)
  public String getUpdatedAt() { return super.getUpdatedAt(); }

  @Override
  public void setUpdatedAt(String updatedAt) { super.setUpdatedAt(updatedAt); }
}
