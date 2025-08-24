package org.apache.ambari.view.k8s.store;

import org.apache.ambari.view.k8s.store.base.BaseModel;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * Tracking entity for Helm releases managed by the UI.
 * ID = namespace + ":" + releaseName
 */
@Entity
@Table(name = "k8s_release")
public class K8sReleaseEntity extends BaseModel {

    @Id
    @Column(length = 512) // "ns:release" can be long
    @Override 
    public String getId() { 
        return super.getId(); 
    }
    
    @Override 
    public void setId(String id) { 
        super.setId(id); 
    }

    @Column(length = 255, nullable = false)
    private String namespace;

    @Column(length = 255, nullable = false)
    private String releaseName;

    // Service key from charts.json used during deployment (e.g., "trino", "prometheus")
    @Column(length = 128)
    private String serviceKey;

    // Chart reference used ("repo/chart", "oci://…", URL .tgz)
    @Column(length = 1024)
    private String chartRef;

    // Logical repository chosen in UI (id), optional
    @Column(length = 128)
    private String repoId;

    // Chart version if provided
    @Column(length = 64)
    private String version;

    // Canonical hash of values (debug/audit)
    @Column(length = 128)
    private String valuesHash;

    // Deployment UUID (multi-resource correlation)
    @Column(length = 64)
    private String deploymentId;

    // Flag: managed by UI (true if registered here)
    private boolean managedByUi;

    @Column(length = 40)
    private String createdAt;

    @Column(length = 40)
    private String updatedAt;

    // Getters and Setters
    public String getNamespace() { 
        return namespace; 
    }
    
    public void setNamespace(String namespace) { 
        this.namespace = namespace; 
    }
    
    public String getReleaseName() { 
        return releaseName; 
    }
    
    public void setReleaseName(String releaseName) { 
        this.releaseName = releaseName; 
    }
    
    public String getServiceKey() { 
        return serviceKey; 
    }
    
    public void setServiceKey(String serviceKey) { 
        this.serviceKey = serviceKey; 
    }
    
    public String getChartRef() { 
        return chartRef; 
    }
    
    public void setChartRef(String chartRef) { 
        this.chartRef = chartRef; 
    }
    
    public String getRepoId() { 
        return repoId; 
    }
    
    public void setRepoId(String repoId) { 
        this.repoId = repoId; 
    }
    
    public String getVersion() { 
        return version; 
    }
    
    public void setVersion(String version) { 
        this.version = version; 
    }
    
    public String getValuesHash() { 
        return valuesHash; 
    }
    
    public void setValuesHash(String valuesHash) { 
        this.valuesHash = valuesHash; 
    }
    
    public String getDeploymentId() { 
        return deploymentId; 
    }
    
    public void setDeploymentId(String deploymentId) { 
        this.deploymentId = deploymentId; 
    }
    
    public boolean isManagedByUi() { 
        return managedByUi; 
    }
    
    public void setManagedByUi(boolean managedByUi) { 
        this.managedByUi = managedByUi; 
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

    // ID Helper method
    public static String idOf(String ns, String name) { 
        return ns + ":" + name; 
    }
}
