package org.apache.ambari.view.k8s.store.base;

/** Simple base POJO implementing Indexed + When. */
public abstract class BaseModel implements Indexed, When {
  private String id;
  private String createdAt;
  private String updatedAt;

  @Override public String getId() { return id; }
  @Override public void setId(String id) { this.id = id; }

  @Override public String getCreatedAt() { return createdAt; }
  @Override public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

  @Override public String getUpdatedAt() { return updatedAt; }
  @Override public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
