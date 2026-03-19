package org.apache.ambari.view.k8s.store;

import org.apache.ambari.view.k8s.store.base.BaseModel;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * Stores mapping between Vault profile fields and Helm override paths.
 */
@Entity
@Table(name = "vault_mapping")
public class VaultMappingEntity extends BaseModel {

    public static final String SINGLETON_ID = "vault-mapping";

    @Id
    @Column(length = 64)
    @Override
    public String getId() {
        return super.getId();
    }

    @Override
    public void setId(String id) {
        super.setId(id);
    }

    @Column(length = 8000)
    private String defaultJson;

    @Column(length = 8000)
    private String overridesJson;

    @Column(length = 40)
    private String createdAt;

    @Column(length = 40)
    private String updatedAt;

    public String getDefaultJson() {
        return defaultJson;
    }

    public void setDefaultJson(String defaultJson) {
        this.defaultJson = defaultJson;
    }

    public String getOverridesJson() {
        return overridesJson;
    }

    public void setOverridesJson(String overridesJson) {
        this.overridesJson = overridesJson;
    }

    @Override
    public String getCreatedAt() {
        return createdAt;
    }

    @Override
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
