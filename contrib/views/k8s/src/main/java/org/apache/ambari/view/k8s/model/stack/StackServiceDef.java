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

package org.apache.ambari.view.k8s.model.stack;

import org.apache.ambari.view.k8s.model.FormField;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class StackServiceDef {
    public String name;          // "SUPERSET"
    public String label;         // "Apache Superset"
    public String chart;         // "apache/superset"
    public String description;
    public String version;
    public List<FormField> form; // Reuses your existing dynamic form definitions

    // Additional fields used by bindings/variables/etc.
    public String pattern;
    public String secretName;
    public Map<String, Object> dependencies;
    public List<Map<String, Object>> endpoints;
    public List<Map<String, Object>> mounts;
    public List<Map<String, Object>> variables;
    public List<Map<String, Object>> bindings;
    /**
     * Optional catalog enrichment specs. These are applied by the backend to augment
     * catalog content (e.g., Trino Hive catalog) with dynamic values such as
     * Kerberos principals from Ambari, without hardcoding service logic.
     */
    public List<Map<String, Object>> catalogEnrichments;
    public Map<String, Map<String, Object>> ranger;
    public List<Map<String, Object>> requiredConfigMaps;
    public List<String> dynamicValues;
    public List<Map<String, Object>> tls;
    public List<Map<String, Object>> kerberos;
    /**
     * OIDC client registration entries. Each entry drives an OIDC_REGISTER_CLIENT +
     * OIDC_CREATE_SECRET command pair during deploy (parallel to kerberos[]).
     * Fields per entry:
     *   key               – logical name (default: "default")
     *   clientIdTemplate  – template for the Keycloak client_id (tokens: {{releaseName}}, {{namespace}}, {{realm}})
     *   secretNameTemplate – K8s Secret name template (default: "{{releaseName}}-oidc-client")
     *   redirectUriTemplate – redirect URI template (tokens: {{ingressHost}}, {{releaseName}}, {{namespace}})
     *   vaultPath         – optional Vault KV path; when set credentials are also written to Vault
     *   publicClient      – boolean, default false
     *   standardFlowEnabled – boolean, default true
     *   enabled           – boolean, default true
     */
    public List<Map<String, Object>> oidc;
    /**
     * Optional post-deploy actions run as command steps after the main Helm install.
     * Currently supports:
     *   postDeploy.ambariViewInstance — auto-provisions a linked Ambari view instance.
     */
    public Map<String, Object> postDeploy;
}
