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

import org.apache.ambari.view.k8s.store.HelmRepoEntity;

/**
 * Data Transfer Object for Helm repository information
 */
public class HelmRepoDTO {
  public String id;
  public String type;       // HTTP | OCI
  public String name;
  public String url;
  public String authMode;   // anonymous | basic | token
  public String username;
  public Boolean authInvalid;
  public String lastChecked; // ISO-8601
  public String imageProject;
  public String imageRegistryHostOverride; // optional, could even be hidden from normal UI

  public static HelmRepoDTO fromEntity(HelmRepoEntity e) {
    HelmRepoDTO d = new HelmRepoDTO();
    d.id = e.getId();
    d.type = e.getType();
    d.name = e.getName();
    d.url = e.getUrl();
    d.authMode = e.getAuthMode();
    d.username = e.getUsername();
    d.authInvalid = e.isAuthInvalid();
    d.imageProject = e.getImageProject();
    d.imageRegistryHostOverride = e.getImageRegistryHostOverride();

    return d;
  }
}