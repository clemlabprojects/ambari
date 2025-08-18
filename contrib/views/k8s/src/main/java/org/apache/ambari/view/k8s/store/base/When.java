package org.apache.ambari.view.k8s.store.base;

/** Marker interface: entity tracks creation/update timestamps (ISO-8601 strings). */
public interface When {
  String getCreatedAt();
  void setCreatedAt(String createdAt);
  String getUpdatedAt();
  void setUpdatedAt(String updatedAt);
}
