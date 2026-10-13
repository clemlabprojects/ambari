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

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.ambari.view.k8s.store.base.BaseModel;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.net.URI;

/**
 * Entity representing a Helm repository configuration.
 * Stores metadata for Helm repositories and registries without secret values.
 *
 * <p>Ambari's DataStore keeps every bean property of an entity, whatever its JPA annotations, and the view migration
 * copies every one of them to the next version: no property may ever carry the registry secret. The request that
 * saves a repository carries it apart ({@code HelmRepoRequest}).</p>
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
    private String secretRef;   // key of the encrypted secret in InstanceSettings; never expose

    @Column(length = 256)
    private String imageProject; // e.g. "clemlabprojects"

    @Column(length = 512)
    private String imageRegistryHostOverride; // optional: e.g. "another.registry.com"

    private boolean authInvalid;

    /**
     * GitOps: an existing Secret Flux pulls charts with (created by SOPS, Sealed Secrets or External Secrets), in
     * place of the one KDPS writes from the stored credentials. It must exist in every namespace of a chart source.
     */
    @Column(length = 253)
    private String fluxSecretName;

    /** GitOps: an existing Secret holding the {@code ca.crt} Flux verifies the repository's server with. */
    @Column(length = 253)
    private String fluxCertSecretName;

    /**
     * GitOps: KDPS truststores (comma-separated names) holding the CA of the repository's server, written to a
     * {@code ca.crt} Secret next to each chart source when no CA Secret is named.
     */
    @Column(length = 1024)
    private String caTruststores;

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
    
    /**
     * The view setting holding this repository's encrypted secret. Set by the server only: never read from or written
     * to a request body, and never used to look the secret up (see {@code HelmRepositoryService.readPlainSecret}).
     */
    @JsonIgnore
    public String getSecretRef() { 
        return secretRef; 
    }
    
    @JsonIgnore
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

    public String getFluxSecretName() {
        return fluxSecretName;
    }

    public void setFluxSecretName(String fluxSecretName) {
        this.fluxSecretName = fluxSecretName;
    }

    public String getFluxCertSecretName() {
        return fluxCertSecretName;
    }

    public void setFluxCertSecretName(String fluxCertSecretName) {
        this.fluxCertSecretName = fluxCertSecretName;
    }

    public String getCaTruststores() {
        return caTruststores;
    }

    public void setCaTruststores(String caTruststores) {
        this.caTruststores = caTruststores;
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
