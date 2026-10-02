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

/**
 * A reusable Trino catalog definition: the operator writes a catalog's {@code .properties} once
 * and attaches it to any number of Trino releases from the deploy wizard. The release gets a
 * SNAPSHOT of the properties in its chart values (the ConfigMap stays the durable truth for the
 * running release) plus a reference back to this object, so a later edit here does not change a
 * running release until the operator re-applies it.
 *
 * <p>Persistence caveats (Ambari DataStore): every String property is stored as 3000 chars and the
 * entity total is capped at 65000 — keep the property count small and {@code propertiesText} at or
 * under 3000 chars (enforced by {@code TrinoCatalogService}). Registering this entity in view.xml
 * required a view version bump: the DataStore only creates tables for entities listed at the
 * version the instance was created with.
 */
@Entity
@Table(name = "k8s_trino_catalog")
public class TrinoCatalogEntity extends BaseModel {

    @Id
    @Column(length = 128)
    @Override
    public String getId() { return super.getId(); }
    @Override
    public void setId(String id) { super.setId(id); }

    /** Trino catalog name ([a-z][a-z0-9_]*) — also the key under {@code catalogs} in the chart values. */
    @Column(length = 128, nullable = false)
    private String name;

    /** {@code connector.name} extracted from the properties, for listing/filtering. */
    @Column(length = 64)
    private String connectorName;

    @Column(length = 1024)
    private String description;

    /** The catalog's .properties text, verbatim (max 3000 chars). */
    @Column(length = 3000)
    private String propertiesText;

    @Column(length = 128)
    private String createdBy;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getConnectorName() { return connectorName; }
    public void setConnectorName(String connectorName) { this.connectorName = connectorName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPropertiesText() { return propertiesText; }
    public void setPropertiesText(String propertiesText) { this.propertiesText = propertiesText; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

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
