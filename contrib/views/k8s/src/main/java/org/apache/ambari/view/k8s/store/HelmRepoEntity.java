/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.view.k8s.store;

import org.apache.ambari.view.k8s.store.base.BaseModel;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Transient;
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

    /**
     * Transient carrier for the plain-text registry secret.
     * Never persisted to the DataStore; read from the JSON request body and
     * consumed immediately by HelmRepositoryService.save() to store in the
     * credential store. Keeping it on the entity avoids a separate wrapper DTO
     * while still ensuring the secret never travels as a URL query parameter.
     */
    @Transient
    private String plainSecret;

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

    public String getPlainSecret() {
        return plainSecret;
    }

    public void setPlainSecret(String plainSecret) {
        this.plainSecret = plainSecret;
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
