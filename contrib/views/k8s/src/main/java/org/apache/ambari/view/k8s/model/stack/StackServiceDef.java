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
     *   implicitFlowEnabled – boolean, default false (set true for clients whose frontend
     *                         uses response_type=id_token, e.g. OpenMetadata custom-oidc)
     *   enabled           – boolean, default true
     */
    public List<Map<String, Object>> oidc;
    /**
     * Optional post-deploy actions run as command steps after the main Helm install.
     * Currently supports:
     *   postDeploy.ambariViewInstance — auto-provisions a linked Ambari view instance.
     */
    public Map<String, Object> postDeploy;

    /**
     * Maps a canonical form-field path to the chart-specific values.yaml path it must
     * be written to. Use this when the shared form schema (e.g. KDPS/_shared/ingress.json)
     * declares <code>ingress.ingressClassName</code> but the chart's values.yaml reads
     * <code>ingress.className</code>. At deploy time the backend rewrites every value
     * map and every <code>_ov</code> override key matching the LHS to the RHS path.
     * Example:
     * <pre>
     *   "valueAliases": {
     *     "ingress.ingressClassName": "ingress.className"
     *   }
     * </pre>
     */
    public Map<String, String> valueAliases;

    /**
     * Declarative rule for how the wizard couples auth profile selection with ingress
     * + TLS settings. The wizard reads this at step 1 → step 3 transition to enforce
     * the rule visibly (banner + auto-defaults) instead of hardcoding it in TypeScript.
     *
     * Fields:
     *   requireIngress  – when true and an auth profile is picked, force ingress.enabled=true (default true)
     *   requireTls      – when true and an auth profile is picked, bump ingress.tlsMode off "none" (default true)
     *   minTlsMode      – default TLS mode to apply when auto-bumping (default "signedByAmbariCA")
     *
     * Backwards compatibility: if absent, the wizard applies requireIngress=true,
     * requireTls=true, minTlsMode="signedByAmbariCA" — matching the legacy behavior.
     */
    public Map<String, Object> securityCoupling;

    /**
     * Optional chart version range this service.json was tested against (semver
     * range, e.g. "&gt;=0.12.24"). The deploy controller compares the resolved chart
     * version against this range and refuses the install with a clear error if it
     * doesn't match. Prevents silent breakage when the chart and the service.json
     * drift apart.
     */
    public String requiredChartVersion;
}
