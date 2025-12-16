package org.apache.ambari.view.k8s.store;

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.k8s.store.base.BaseModel;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * Persistent entity to store a single security profile.
 */
@Entity
@Table(name = "k8s_security_profile")
public class SecurityProfileEntity extends BaseModel {

    @Id
    @Column(length = 255)
    @Override
    public String getId() {
        return super.getId();
    }

    @Override
    public void setId(String id) {
        super.setId(id);
    }

    @Column(length = 255, nullable = false)
    private String viewInstance;    // view instance name for scoping

    private boolean isDefault;

    @Column(length = 8000)
    private String profileJson;     // serialized SecurityConfigDTO

    public String getViewInstance() { return viewInstance; }
    public void setViewInstance(String viewInstance) { this.viewInstance = viewInstance; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean aDefault) { isDefault = aDefault; }

    public String getProfileJson() { return profileJson; }
    public void setProfileJson(String profileJson) { this.profileJson = profileJson; }

    /**
     * Convenience upsert helper.
     */
    public static void upsert(DataStore ds, SecurityProfileEntity e) throws PersistenceException {
        ds.store(e);
    }
}
