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

import java.util.Map;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import javax.persistence.Transient;

import org.apache.ambari.view.k8s.store.base.BaseModel;

/**
 * A <em>Platform Context</em> — a named ODP platform environment that KDPS services
 * integrate against (Atlas, Ranger, Kerberos, …). Two kinds:
 *
 * <ul>
 *   <li>{@code MANAGED} — the Ambari-managed ODP cluster. Connection details, auth modes
 *       and credentials are resolved live from Ambari at deploy time, and privileged
 *       Ranger operations are delegated to the Ambari server (which holds the Ranger admin
 *       password). {@link #clusterName} names the Ambari cluster. {@link #configJson} is
 *       typically empty.</li>
 *   <li>{@code EXTERNAL} — a cluster Ambari does not manage (e.g. fronted by Knox). All
 *       connection details live in {@link #configJson} and credentials are stored
 *       encrypted in the view instance data (see {@link #secretKeys}).</li>
 * </ul>
 *
 * Distinct from any kubeconfig / "kube-context" notion — this is the data-platform side.
 */
@Entity
@Table(name = "k8s_context")
public class KdpsContextEntity extends BaseModel {

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

    @Column(length = 255, nullable = false)
    private String name;

    @Column(length = 16, nullable = false)
    private String kind;            // MANAGED | EXTERNAL

    @Column(length = 255)
    private String clusterName;     // MANAGED: the Ambari cluster name

    @Column(length = 1024)
    private String description;

    /**
     * JSON object of non-secret connection settings for EXTERNAL contexts, e.g.
     * {@code {"atlasUrl":"https://...","atlasAuthMode":"basic","atlasAclMode":"ranger",
     * "rangerUrl":"https://...","rangerAdminUsername":"admin"}}.
     */
    @Lob
    private String configJson;

    /** Comma-separated names of secrets stored (encrypted) in instance data for this context. */
    @Column(length = 512)
    private String secretKeys;

    /**
     * Transient inbound carrier: parsed non-secret config (bound from the request body).
     * Persisted as {@link #configJson}; never read back from the DataStore.
     */
    @Transient
    private Map<String, Object> config;

    /**
     * Transient inbound carrier: plaintext secrets keyed by name (e.g. {@code rangerAdminPassword}).
     * Encrypted into instance data by the service layer and never persisted nor echoed back.
     */
    @Transient
    private Map<String, String> secrets;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    // @Lob on the getter (property access) so the serialized config isn't capped at the default
    // column length — the field-level @Lob is ignored once @Id is on getId().
    @Lob
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public String getSecretKeys() { return secretKeys; }
    public void setSecretKeys(String secretKeys) { this.secretKeys = secretKeys; }

    // This entity uses property access (the @Id is on getId()), so @Transient MUST be on the
    // getters: a getter returning Map cannot be mapped, and leaving it persistent makes EclipseLink
    // reject the whole entity ("not registered as an entity"), breaking all EXTERNAL/REMOTE context
    // persistence. config is serialized into configJson; secrets are an inbound-only carrier.
    @Transient
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }

    @Transient
    public Map<String, String> getSecrets() { return secrets; }
    public void setSecrets(Map<String, String> secrets) { this.secrets = secrets; }

    @Override
    @Column(length = 40)
    public String getCreatedAt() { return super.getCreatedAt(); }

    @Override
    public void setCreatedAt(String createdAt) { super.setCreatedAt(createdAt); }

    @Override
    @Column(length = 40)
    public String getUpdatedAt() { return super.getUpdatedAt(); }

    @Override
    public void setUpdatedAt(String updatedAt) { super.setUpdatedAt(updatedAt); }
}
