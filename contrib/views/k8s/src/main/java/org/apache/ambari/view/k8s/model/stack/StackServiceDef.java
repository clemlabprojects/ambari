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
    /** Default Helm repo id this catalog entry expects the chart to come from.
     *  Read by the /catalog/app-versions endpoint to construct an OCI/HTTP
     *  chartRef for `helm show chart`. Optional — when null the resolver tries
     *  the raw chart name (works if helm has it cached locally). */
    public String defaultRepo;
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

    /**
     * Declarative external-service auth wiring. See
     * {@code docs/EXTERNAL_SERVICE_TARGETS.md} for the full contract.
     *
     * <p>Each entry models one outbound connection the chart can make to a service
     * that is either Ambari-managed (no URL override → internal pipeline fires)
     * or external (URL override set → operator picks an auth mode and Secret,
     * KDPS resolves the Secret and writes helm overrides per the mode's
     * {@code applyTo} block).
     *
     * <p>The wizard reads this block to render auth-mode dropdowns + conditional
     * Secret pickers. The deploy pipeline reads it to resolve credentials and
     * apply helm overrides.
     *
     * <p>No-op when null — services that don't need external integrations
     * (e.g. SQL-ASSISTANT) simply omit the block.
     */
    public java.util.Map<String, ExternalServiceTarget> externalServiceTargets;

    /**
     * Extra helm value paths where the image-pull secret name must be written, in
     * addition to the two universal paths KDPS always sets
     * (<code>imagePullSecrets[0].name</code> and <code>global.imagePullSecrets[0]</code>).
     *
     * <p>Needed because not every sub-chart honors <code>global.imagePullSecrets</code>.
     * For example, the airflow sub-chart used by OpenMetadata reads its own
     * <code>registry.secretName</code> field; without an entry here the airflow pods
     * spawn with no pull secret and ImagePullBackOff against the private Harbor.
     * The opensearch sub-chart has a similar carve-out using
     * <code>imagePullSecrets[0].name</code> nested under its own key.
     *
     * <p>Example in service.json:
     * <pre>
     *   "imagePullSecretTargets": [
     *     "dependencies.airflow.registry.secretName"
     *   ]
     * </pre>
     *
     * <p>This keeps the secret name a single source of truth (resolved server-side
     * from the repo binding via <code>ensureImagePullSecretFromRepo</code>) and avoids
     * hard-coding it in chart values.yaml.
     */
    public List<String> imagePullSecretTargets;

    /**
     * Platform-context requirements (see docs/CONTEXT_FRAMEWORK.md). Each entry declares a
     * capability + the fields the service needs from its resolved context, optionally gated
     * by a {@code when} form-value path and scoped by {@code appliesTo} (EXTERNAL|MANAGED).
     * Drives dynamic requiredness on external contexts + the deploy-time satisfaction gate.
     * Example entry: {@code {"capability":"atlas","fields":["federationUser","federationPassword"],
     * "when":"atlasFederation.enabled","appliesTo":"EXTERNAL"}}.
     */
    public List<Map<String, Object>> requiresContext;

    /**
     * Declarative platform integration operations run post-deploy against the resolved
     * context (see docs/CONTEXT_FRAMEWORK.md). Each entry: {@code {"op":"atlas.federation",
     * "when":"atlasFederation.enabled"}}. Generalizes the previously-bespoke ranger/atlas gates.
     */
    public List<Map<String, Object>> platformOps;
}
