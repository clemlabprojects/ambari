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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ambari.view.k8s.store.KdpsContextEntity;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Read DTO for a platform context. Exposes the non-secret config and the NAMES of stored
 * secrets (so the UI can show "set"/"not set") but never the secret values.
 */
public class ContextDTO {

    private static final Gson GSON = new Gson();

    public String id;
    public String name;
    public String kind;
    public String clusterName;
    public String description;
    public Map<String, Object> config = new LinkedHashMap<>();
    public List<String> secretKeys = new ArrayList<>();
    public String createdAt;
    public String updatedAt;

    public static ContextDTO fromEntity(KdpsContextEntity e) {
        ContextDTO d = new ContextDTO();
        d.id = e.getId();
        d.name = e.getName();
        d.kind = e.getKind();
        d.clusterName = e.getClusterName();
        d.description = e.getDescription();
        if (e.getConfigJson() != null && !e.getConfigJson().isBlank()) {
            try {
                Map<String, Object> m = GSON.fromJson(e.getConfigJson(),
                        new TypeToken<LinkedHashMap<String, Object>>(){}.getType());
                if (m != null) {
                    d.config = m;
                }
            } catch (Exception ignored) {
                // leave config empty on parse failure
            }
        }
        if (e.getSecretKeys() != null && !e.getSecretKeys().isBlank()) {
            for (String k : e.getSecretKeys().split(",")) {
                if (!k.isBlank()) {
                    d.secretKeys.add(k.trim());
                }
            }
        }
        d.createdAt = e.getCreatedAt();
        d.updatedAt = e.getUpdatedAt();
        return d;
    }
}
