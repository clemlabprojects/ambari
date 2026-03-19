package org.apache.ambari.view.k8s.store;

import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.ViewContext;

/**
 * Repository for Git/PR metadata stored separately from the main release entity.
 * This avoids Ambari DataStore's 65k-per-entity string column limit.
 */
public class K8sReleaseGitRepo {

    private final ViewContext viewContext;

    public K8sReleaseGitRepo(ViewContext ctx) {
        this.viewContext = ctx;
    }

    public K8sReleaseGitEntity findById(String releaseId) {
        try {
            return viewContext.getDataStore().find(K8sReleaseGitEntity.class, releaseId);
        } catch (PersistenceException e) {
            throw new RuntimeException(e);
        }
    }

    public K8sReleaseGitEntity upsert(K8sReleaseGitEntity entity) {
        try {
            viewContext.getDataStore().store(entity);
            return entity;
        } catch (PersistenceException e) {
            throw new RuntimeException(e);
        }
    }

    public void deleteById(String releaseId) {
        try {
            K8sReleaseGitEntity existing = findById(releaseId);
            if (existing != null) {
                viewContext.getDataStore().remove(existing);
            }
        } catch (PersistenceException e) {
            throw new RuntimeException(e);
        }
    }
}
