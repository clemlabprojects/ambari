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
package org.apache.ambari.view.k8s.model;

import org.apache.ambari.view.k8s.store.TrinoCatalogEntity;

import java.util.ArrayList;
import java.util.List;

/** Wire shape of a reusable Trino catalog (request and response). */
public class TrinoCatalogDTO {
    public String id;
    public String name;
    public String connectorName;
    public String description;
    public String propertiesText;
    public String createdBy;
    public String createdAt;
    public String updatedAt;
    /** Releases ("namespace/release") currently holding a snapshot of this catalog. Response only. */
    public List<String> usedBy = new ArrayList<>();

    public static TrinoCatalogDTO fromEntity(TrinoCatalogEntity e) {
        TrinoCatalogDTO d = new TrinoCatalogDTO();
        d.id = e.getId();
        d.name = e.getName();
        d.connectorName = e.getConnectorName();
        d.description = e.getDescription();
        d.propertiesText = e.getPropertiesText();
        d.createdBy = e.getCreatedBy();
        d.createdAt = e.getCreatedAt();
        d.updatedAt = e.getUpdatedAt();
        return d;
    }
}
