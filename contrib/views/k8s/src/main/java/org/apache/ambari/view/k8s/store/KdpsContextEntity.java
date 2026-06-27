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

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import org.apache.ambari.view.k8s.store.base.BaseModel;

/**
 * A <em>Platform Context</em> — a named ODP platform environment that KDPS services
 * integrate against (Atlas, Ranger, Kerberos, …). Two persisted kinds plus a virtual default:
 *
 * <ul>
 *   <li>{@code MANAGED} — the Ambari-managed ODP cluster. Synthesized live and never persisted
 *       (see {@code ContextService.managedDefault}); privileged Ranger operations are delegated
 *       to the Ambari server.</li>
 *   <li>{@code EXTERNAL} — a cluster Ambari does not manage. Non-secret connection details live
 *       in {@link #configJson}; credentials are stored encrypted in the view instance data
 *       (tracked by {@link #secretKeys}).</li>
 *   <li>{@code REMOTE} — like MANAGED but discovered against a different cluster's Ambari over the
 *       network with stored credentials.</li>
 * </ul>
 *
 * <h3>Persistence contract — read before adding fields</h3>
 * Ambari's view {@code DataStoreImpl} persists this entity by <em>bean-property introspection</em>:
 * it builds a dynamic EclipseLink type with one {@code DS_<property>} column per read/write bean
 * property and <strong>ignores all JPA annotations</strong> (@Transient, @Lob, @Column lengths).
 * Every String property is stored with a fixed 3000-char column. Consequently:
 * <ul>
 *   <li>this entity exposes <strong>only scalar (String) bean properties</strong> — a {@code Map}
 *       or other non-scalar getter would be mapped to an unsupported column type (e.g. PostgreSQL
 *       {@code hstore}) and break all context persistence;</li>
 *   <li>the inbound non-secret config map and plaintext secrets are carried on
 *       {@link org.apache.ambari.view.k8s.model.ContextRequest} (never persisted as-is) and are
 *       folded into {@link #configJson} / encrypted instance data by {@code ContextService}.</li>
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
    private String kind;            // MANAGED | EXTERNAL | REMOTE

    @Column(length = 255)
    private String clusterName;     // MANAGED/REMOTE: the (remote) Ambari cluster name

    @Column(length = 1024)
    private String description;

    /**
     * JSON object of non-secret connection settings, e.g.
     * {@code {"atlasUrl":"https://...","atlasAuthMode":"basic","rangerUrl":"https://...",
     * "rangerAdminUsername":"admin"}}. Stored as a plain String column (Ambari caps it at 3000
     * chars — far beyond any realistic context config; {@code ContextService} guards the length).
     */
    private String configJson;

    /** Comma-separated names of secrets stored (encrypted) in instance data for this context. */
    @Column(length = 512)
    private String secretKeys;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public String getSecretKeys() { return secretKeys; }
    public void setSecretKeys(String secretKeys) { this.secretKeys = secretKeys; }

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
