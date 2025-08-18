package org.apache.ambari.view.k8s.store.base;

/** Marker interface: entity has a string ID. */
public interface Indexed {
  String getId();
  void setId(String id);
}
