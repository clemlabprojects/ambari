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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Write model for creating/updating a platform context (the {@code POST /contexts} body).
 *
 * <p>Deliberately <em>not</em> the persisted {@link org.apache.ambari.view.k8s.store.KdpsContextEntity}:
 * it carries the structured {@link #config} map and the plaintext {@link #secrets} map, neither of
 * which can live on the entity (Ambari's view persistence would try to map a {@code Map} property to
 * an unsupported column type). {@code ContextService} validates this request, serializes
 * {@link #config} into the entity's {@code configJson}, and encrypts {@link #secrets} into the view
 * instance data — the plaintext secrets never touch the DataStore and are never echoed back.
 */
public class ContextRequest {

    public String id;
    public String name;
    public String kind;             // MANAGED | EXTERNAL | REMOTE (defaults to EXTERNAL)
    public String clusterName;
    public String description;

    /** Non-secret connection settings (e.g. atlasUrl, rangerUrl, remoteAmbariUrl, …). */
    public Map<String, Object> config = new LinkedHashMap<>();

    /** Plaintext secrets keyed by name (e.g. {@code rangerAdminPassword}); encrypted on save. */
    public Map<String, String> secrets = new LinkedHashMap<>();
}
