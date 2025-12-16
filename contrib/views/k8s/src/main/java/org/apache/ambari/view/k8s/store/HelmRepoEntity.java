package org.apache.ambari.view.k8s.store;

import org.apache.ambari.view.k8s.store.base.BaseModel;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.net.URI;

/**
 * Entity representing a Helm repository configuration.
 * Stores metadata for Helm repositories and registries without secret values.
 */
@Entity
@Table(name = "k8s_helm_repo")
public class HelmRepoEntity extends BaseModel {

    @Id
    @Column(length = 128)
    @Override 
    public String getId() { 
        return super.getId(); 
    }
    
    @Override 
    public void setId(String id) { 
        super.setId(id); 
    }

    @Column(length = 16, nullable = false)
    private String type;        // HTTP | OCI

    @Column(length = 255, nullable = false)
    private String name;        // for HTTP, helm repo name; logical name otherwise

    @Column(length = 1024, nullable = false)
    private String url;         // HTTP: https://...  OCI: registry.host/namespace (no oci:// prefix)

    @Column(length = 16, nullable = false)
    private String authMode;    // anonymous | basic | token

    @Column(length = 128)
    private String username;

    @Column(length = 128)
    private String secretRef;   // pointer in InstanceData (encrypted); never expose

    @Column(length = 256)
    private String imageProject; // e.g. "clemlabprojects"

    @Column(length = 512)
    private String imageRegistryHostOverride; // optional: e.g. "another.registry.com"

    private boolean authInvalid;


    // Getters and setters
    public String getType() { 
        return type; 
    }
    
    public void setType(String type) { 
        this.type = type; 
    }
    
    public String getName() { 
        return name; 
    }
    
    public void setName(String name) { 
        this.name = name; 
    }
    
    public String getUrl() { 
        return url; 
    }
    
    public void setUrl(String url) { 
        this.url = url; 
    }
    
    public String getAuthMode() { 
        return authMode; 
    }
    
    public void setAuthMode(String authMode) { 
        this.authMode = authMode; 
    }
    
    public String getUsername() { 
        return username; 
    }
    
    public void setUsername(String username) { 
        this.username = username; 
    }
    
    public String getSecretRef() { 
        return secretRef; 
    }
    
    public void setSecretRef(String secretRef) { 
        this.secretRef = secretRef; 
    }
    
    public boolean isAuthInvalid() { 
        return authInvalid; 
    }
    
    public void setAuthInvalid(boolean authInvalid) { 
        this.authInvalid = authInvalid; 
    }

    @Override
    @Column(length = 40)
    public String getCreatedAt() { 
        return super.getCreatedAt(); 
    }
    
    @Override
    public void setCreatedAt(String createdAt) { 
        super.setCreatedAt(createdAt); 
    }

    @Override
    @Column(length = 40)
    public String getUpdatedAt() { 
        return super.getUpdatedAt(); 
    }

    @Override
    public void setUpdatedAt(String updatedAt) { 
        super.setUpdatedAt(updatedAt); 
    }

    public String getImageProject() {
        return imageProject;
    }

    public void setImageProject(String imageProject) {
        this.imageProject = imageProject;
    }

    public String getImageRegistryHostOverride() {
        return imageRegistryHostOverride;
    }

    public void setImageRegistryHostOverride(String imageRegistryHostOverride) {
        this.imageRegistryHostOverride = imageRegistryHostOverride;
    }

    public String getEffectiveRegistryHost() {
        // 1. explicit override wins
        if (imageRegistryHostOverride != null && !imageRegistryHostOverride.isBlank()) {
            return imageRegistryHostOverride.trim();
        }

        // 2. derive from url
        if (type != null && type.equalsIgnoreCase("HTTP")) {
            // url like https://charts.clemlab.com/bitnami
            try {
                URI uri = URI.create(url);
                return uri.getHost();
            } catch (Exception e) {
                return null;
            }
        } else {
            // OCI: stored as "registry.host/namespace"
            if (url == null) return null;
            int slash = url.indexOf('/');
            return (slash > 0) ? url.substring(0, slash) : url;
        }
    }

    public String getEffectiveImageRegistry() {
        String host = getEffectiveRegistryHost();
        if (host == null || host.isBlank()) return null;
        if (imageProject == null || imageProject.isBlank()) return host;
        return host + "/" + imageProject.trim();
    }
}
