package org.apache.ambari.view.k8s.store;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.store.base.BaseRepo;

public class K8sReleaseRepo extends BaseRepo<K8sReleaseEntity> {
  public K8sReleaseRepo(ViewContext ctx) {
    super(K8sReleaseEntity.class, ctx.getDataStore());
  }
}
