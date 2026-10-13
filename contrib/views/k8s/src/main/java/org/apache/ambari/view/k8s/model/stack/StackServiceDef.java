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
    /**
     * OpenShift SCC grants this service's pods require — declared when bundled subcharts pin a
     * UID/GID or run as root and can't run under the default {@code restricted-v2} SCC. Each entry
     * is {@code {"scc": "anyuid", "serviceAccounts": ["default", "{{releaseName}}-airflow-scheduler", ...]}}.
     *
     * <p>Grants are PER-SERVICEACCOUNT, never to the whole {@code system:serviceaccounts:<ns>} group.
     * A group grant is subtly wrong: it also makes SAs that must stay on {@code restricted-v2} (e.g.
     * the OpenMetadata server, whose pods set {@code runAsNonRoot} with no UID and rely on the
     * platform to inject a namespace-range UID) PREFER {@code anyuid} — which injects no UID, so the
     * image runs as root and the pod fails "container has runAsNonRoot and image will run as root".
     * Listing only the SAs that genuinely need the elevated SCC leaves every other SA (server,
     * postgres) on {@code restricted-v2}. {@code {{releaseName}}} is expanded to the release name.
     *
     * <p>On an OpenShift target the deploy preflight ({@code ensureSccGrants}) leaves service accounts
     * that may already use their SCC alone, grants the others through one RoleBinding per release
     * and SCC when the view's account may, else blocks with the {@code oc adm policy add-scc-to-user}
     * commands and how to let the account grant SCCs. A GitOps deploy only checks
     * ({@code checkSccGrants}); an uninstall removes the release's RoleBindings. Ignored on vanilla
     * Kubernetes.
     */
    public List<Map<String, Object>> openshiftScc;

    /**
     * Chart values a deploy on OpenShift gets when it does not set them itself, keyed by dotted values path, e.g.
     * {@code {"scheduling.userScheduler.enabled": false}} for JupyterHub, whose user scheduler needs a ClusterRole
     * that an account limited to its own projects cannot create. The wizard sets the same values through bindings
     * gated on {@code isOpenShift}; this covers the deploys that do not run them (a request sent straight to the API,
     * a GitOps deploy driven from Git, a chart upgrade from the Releases page), in every deployment mode
     * ({@code CommandService.applyOpenShiftValueDefaults}).
     * A value the deploy already carries is kept. Keys starting with "_" are comments. Ignored on
     * plain Kubernetes.
     */
    public Map<String, Object> openshiftValueDefaults;

    /**
     * Platforms the service can be deployed on, as the cluster capabilities name them: {@code "kubernetes"} (plain
     * Kubernetes) and {@code "openshift"}. Absent or empty means every platform. The catalog hides a service once the
     * cluster is known to be a platform the list leaves out, and the deploy refuses it there
     * ({@code CommandService.checkSupportedPlatform}), so a deploy sent straight to the API or in GitOps mode is
     * refused too. For a chart that cannot run on a platform at all, e.g. GitLab, whose upstream chart pins user IDs
     * that OpenShift's default SCC rejects: without it the install waits for pods that are never created and fails
     * with a timeout that does not say why.
     */
    public List<String> platforms;

    /**
     * What the deploy answers on a platform {@link #platforms} leaves out: why, and what to do instead. Optional; the
     * default names the service and the platform.
     */
    public String unsupportedPlatformMessage;

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
     * Values an earlier version of this definition wrote and this one no longer does, keyed by dotted path: the
     * reason they were retired (the value is removed), or, for a list of lines,
     * {@code {"reason": ..., "linesStartingWith": [...]}} (only those lines are removed). An upgrade or a redeploy
     * starts from the release's values, so without this they would be sent again, e.g. a secret a binding used to copy
     * into a plain environment variable or a configuration line. The wizard leaves them out of the values it sends and
     * the server removes them from every set of release values it sends to Helm (a deploy, a chart upgrade from the
     * Releases page, a catalog saved on the Trino Catalogs page: {@code CommandService.applyDefinitionValueRewrites}),
     * after the {@link #valueAliases}; keys starting with "_" are comments.
     */
    public Map<String, Object> retiredValues;

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
     * Optional per-FEATURE chart version ranges, keyed by the dotted path of the toggle that turns
     * the feature on (e.g. <code>{"tagSync.enabled": "&gt;=1.43.11"}</code>). The range is enforced
     * only when that toggle is actually on, so a service can keep a permissive
     * {@link #requiredChartVersion} for everyone while still refusing a combination that would be a
     * silent no-op — a toggle whose chart-side template only exists in a later chart.
     *
     * <p>Without this, enabling such a toggle against an older chart deploys cleanly and simply does
     * nothing, which is far worse than an error: Atlas tag sync did exactly that when the wizard
     * still defaulted to a chart with no tag-projector template.
     */
    public Map<String, String> featureChartVersions;

    /**
     * Optional chart version ranges from which the chart keeps a secret in a Kubernetes Secret, keyed by the dotted
     * path that carries it: {@code {"range": ">=0.12.49", "label": "the security profile's LDAP bind password"}}.
     * An older chart writes it into a ConfigMap or a plain environment variable, which anyone allowed to read
     * ConfigMaps or Deployments can read. The path is looked up in the deploy's values, its form and the values its
     * security profile adds. Raising {@link #requiredChartVersion} instead would refuse a resync or a security refresh
     * of every release on an older chart, so a deploy that carries the wizard's form is refused (the operator picks a
     * later chart there) and any other one is warned in its operation log
     * ({@code CommandService.checkSecretChartVersions}). Keys starting with "_" are comments.
     */
    public Map<String, Object> secretChartVersions;

    /**
     * Secrets of credentials KDPS creates for the release before Helm installs it (an administrator password, the
     * passwords of the bundled databases), with random values kept from one deploy to the next and across an
     * uninstall; the chart only gets their names. See {@link GeneratedSecret} and {@code GeneratedSecretService}.
     */
    public List<GeneratedSecret> generatedSecrets;

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
     * (e.g. GITLAB) simply omit the block.
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
     * Value paths that hold plain image references (name or name:tag) in charts that ignore
     * {@code global.imageRegistry}, e.g. Z2JH's {@code hub.image.name} or
     * {@code singleuser.extraContainers[0].image}. At deploy time the view rewrites the registry
     * prefix of each listed value to the effective image registry of the selected Helm repository,
     * so a private mirror works without touching the wizard (AMBARI-690). An entry is either the
     * path itself or {@code {"path": ..., "default": ...}}; the default is used when the request
     * carries no value for the path (API-driven deploys that skip the wizard bindings), so the
     * chart's upstream default image is never pulled.
     */
    public List<Object> imageRegistryPaths;

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

    /**
     * Post-deploy steps the operator can run again from the release's row on the Releases page,
     * e.g. {@code {"key":"om-ranger-tagsync","label":"Re-register Ranger TagSync source",
     * "endpoint":"actions/om-ranger-tagsync"}}. An entry may ask the operator to confirm first with
     * {@code "warnUnlessValueTrue": {"path": "<values path>", "message": "..."}} when that value of
     * the deployed release is not {@code true}. Declared here so the definition the UI receives keeps
     * them: properties this class does not declare are dropped when service.json is read.
     */
    public List<Map<String, Object>> replayableActions;

    /**
     * Settings of the service's Flux HelmRelease in GitOps mode: {@code {"timeout": "20m"}} is how long Flux lets Helm
     * install or upgrade the release and wait for it to be ready, a Go duration such as {@code 600s}, {@code 20m} or
     * {@code 1h30m}. Absent, a HelmRelease gets the 15 minutes a direct deploy gives Helm. Set it for a chart that
     * needs longer, such as one that pulls many large images or runs long migrations during its first install.
     */
    public Map<String, Object> gitops;
}
