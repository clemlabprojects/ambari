package org.apache.ambari.view.k8s.store;

import org.apache.ambari.view.k8s.store.base.BaseRepo;
import org.apache.ambari.view.DataStore;

/**
 * Repository class for managing Helm repository entities
 */
public class HelmRepoRepo extends BaseRepo<HelmRepoEntity> {
    
    public HelmRepoRepo(DataStore dataStore) {
        super(HelmRepoEntity.class, dataStore);
    }
}
