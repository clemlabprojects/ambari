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

/**
 * One setting of the view instance, shared by every user of the instance: the cluster connection
 * (kubeconfig pointer, OpenShift login, proxy), the encrypted Helm repository and platform-context
 * secrets, the view settings JSON and the monitoring state. Read and written through
 * {@link InstanceSettings} only.
 *
 * <p>Ambari's per-user instance data cannot hold these: it returns only the rows of the user making the
 * request, so whatever one administrator configured was invisible to every other user and to background
 * work started from another user's request. The view's DataStore belongs to the instance instead.</p>
 *
 * <p>Persistence caveats (Ambari DataStore): the entity is mapped by bean introspection, so it keeps to
 * scalar String properties; every String column holds at most 3000 characters
 * ({@link InstanceSettings#MAX_VALUE_LENGTH}). A row whose value is {@code null} records that the setting
 * was removed (see {@link InstanceSettings#remove}).</p>
 */
@Entity
@Table(name = "k8s_setting")
public class KdpsSettingEntity {

    /** The setting key, e.g. {@code kubeconfig.path} or {@code helm.repo.secret.<repoId>}. */
    @Id
    @Column(length = 255)
    private String id;

    /** The stored value (secrets are already encrypted); {@code null} once the setting was removed. */
    @Column(length = 3000)
    private String value;

    public KdpsSettingEntity() {
        // required by the DataStore
    }

    public KdpsSettingEntity(String id, String value) {
        this.id = id;
        this.value = value;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
