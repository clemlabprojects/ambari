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

package org.apache.ambari.view.k8s.requests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;

/**
 * The body of POST /helm/repos: the repository as the Helm repositories page edits it, and the registry password or
 * token typed in with it. The secret travels in the body only, never in the URL, and never reaches the stored
 * repository ({@link HelmRepoEntity} has no property for it): the server encrypts it into the view settings.
 *
 * <p>What the server decides is not read from the request: the secret reference, whether the credentials need to be
 * entered again, the timestamps. Other properties the page sends back are ignored.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HelmRepoRequest {
    public String id;
    public String type;
    public String name;
    public String url;
    public String authMode;
    public String username;
    public String imageProject;
    public String imageRegistryHostOverride;
    /** The registry password or token; {@code null} or blank keeps the stored one. */
    public String plainSecret;

    /**
     * The repository to save. A property the request leaves out stays {@code null}, which keeps its stored value.
     *
     * @return a new entity, without the secret
     */
    public HelmRepoEntity toEntity() {
        HelmRepoEntity entity = new HelmRepoEntity();
        entity.setId(id);
        entity.setType(type);
        entity.setName(name);
        entity.setUrl(url);
        entity.setAuthMode(authMode);
        entity.setUsername(username);
        entity.setImageProject(imageProject);
        entity.setImageRegistryHostOverride(imageRegistryHostOverride);
        return entity;
    }
}
