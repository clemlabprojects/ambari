package org.apache.ambari.view.k8s.store;

import org.apache.ambari.view.k8s.store.base.BaseRepo;
import org.apache.ambari.view.DataStore;

public class HelmRepoRepo extends BaseRepo<HelmRepoEntity> {
  public HelmRepoRepo(DataStore ds) {
    super(HelmRepoEntity.class, ds);
  }
}
