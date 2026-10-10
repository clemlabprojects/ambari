/*
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

package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * When a service's dependency (service.json {@code dependencies}: KEDA, kube-prometheus-stack, the keytab webhook...)
 * is not installed for a deploy. Shared by the direct Helm and the GitOps deployment modes, so both install the same
 * dependencies.
 *
 * <p>Rules, in order: {@code skipOnOpenShift}, {@code onlyWhenValueTrue} (a values path that must be true),
 * {@code skipIfCrdExists} (an operator already serves the CRD), {@code skipIfPrometheusPresent} (the Prometheus the
 * deploy queries is already running) and {@code skipIfReleaseExists} (a Helm release of that name is in the
 * dependency's namespace).
 */
public final class DependencyRules {

    private static final Logger LOG = LoggerFactory.getLogger(DependencyRules.class);

    private DependencyRules() {
    }

    /**
     * Places the dependency in the namespace named by its {@code namespaceFromForm} form field, when that field is
     * filled (e.g. KEDA's {@code keda.namespace}). Modifies {@code spec}.
     *
     * @param spec       the dependency's spec
     * @param formValues the deploy form's values, may be null
     */
    public static void applyNamespaceFromForm(Map<String, Object> spec, Map<String, Object> formValues) {
        Object field = spec.get("namespaceFromForm");
        if (field == null || String.valueOf(field).isBlank() || formValues == null) {
            return;
        }
        Object chosen = ConfigResolutionService.getByDottedPath(formValues, String.valueOf(field));
        if (chosen != null && !String.valueOf(chosen).isBlank()) {
            spec.put("namespace", String.valueOf(chosen).trim());
        }
    }

    /**
     * Why a dependency is not installed for this deploy.
     *
     * @param name          the dependency's release name
     * @param spec          its spec (namespace already resolved, see {@link #applyNamespaceFromForm})
     * @param request       the deploy
     * @param k8s           the cluster
     * @param releaseExists whether a Helm release exists, as (namespace, name)
     * @return the reason shown to the operator, or {@code null} to install it
     */
    public static String skipReason(String name, Map<String, Object> spec, HelmDeployRequest request,
                                    KubernetesService k8s, BiPredicate<String, String> releaseExists) {
        String reason = settingsSkipReason(name, spec, request, k8s);
        return reason != null ? reason : reuseSkipReason(name, spec, request, k8s, releaseExists);
    }

    /**
     * The rules that depend on the platform and the release's settings only ({@code skipOnOpenShift},
     * {@code onlyWhenValueTrue}).
     *
     * @return the reason shown to the operator, or {@code null}
     */
    public static String settingsSkipReason(String name, Map<String, Object> spec, HelmDeployRequest request,
                                            KubernetesService k8s) {
        if (flag(spec, "skipOnOpenShift") && k8s.isOpenShiftCluster()) {
            LOG.info("Skipping dependency '{}' on OpenShift (skipOnOpenShift=true)", name);
            return "Skipped on OpenShift — the platform's built-in stack is used instead.";
        }
        String onlyWhenValueTrue = text(spec, "onlyWhenValueTrue");
        if (onlyWhenValueTrue != null) {
            Map<String, Object> values = request.getValues() == null ? Collections.emptyMap() : request.getValues();
            if (!Boolean.parseBoolean(String.valueOf(ConfigResolutionService.getByDottedPath(values, onlyWhenValueTrue)))) {
                LOG.info("Skipping dependency '{}' — {} is not true for this release.", name, onlyWhenValueTrue);
                return "Skipped — not needed with these settings (" + onlyWhenValueTrue + " is off).";
            }
        }
        return null;
    }

    /**
     * The rules that reuse what is already on the cluster ({@code skipIfCrdExists}, {@code skipIfPrometheusPresent},
     * {@code skipIfReleaseExists}).
     *
     * @return the reason shown to the operator, or {@code null}
     */
    public static String reuseSkipReason(String name, Map<String, Object> spec, HelmDeployRequest request,
                                         KubernetesService k8s, BiPredicate<String, String> releaseExists) {
        // An operator already on the cluster, detected by its CRD whatever namespace or release it came from (e.g.
        // KEDA, including the OpenShift Custom Metrics Autoscaler): never install a second, conflicting one.
        String crd = text(spec, "skipIfCrdExists");
        if (crd != null) {
            boolean present = false;
            try {
                present = k8s.crdExists(crd);
            } catch (Exception e) {
                LOG.warn("skipIfCrdExists check for dependency '{}' ({}) failed; proceeding with install: {}",
                        name, crd, e.toString());
            }
            if (present) {
                LOG.info("Skipping dependency '{}' — CRD '{}' is already served (operator present on the cluster).", name, crd);
                return "Skipped — an existing operator serving '" + crd + "' was found; reusing it.";
            }
        }
        // Only when the deploy queries it: a Prometheus elsewhere is no use to an autoscaler pointed at the default
        // kube-prometheus-stack address.
        if (flag(spec, "skipIfPrometheusPresent")) {
            Optional<PrometheusDiscovery.Instance> reused = PrometheusDiscovery.reusableFor(
                    k8s.listPrometheusInstances(), KedaThanosScope.firstTriggerAddress(request.getValues()));
            if (reused.isPresent()) {
                PrometheusDiscovery.Instance found = reused.get();
                LOG.info("Skipping dependency '{}' — Prometheus {}/{} is already running at {}.",
                        name, found.namespace(), found.name(), found.url());
                return "Skipped — Prometheus " + found.namespace() + "/" + found.name() + " is already running; reusing it.";
            }
        }
        String namespace = text(spec, "namespace");
        if (flag(spec, "skipIfReleaseExists") && namespace != null) {
            if (releaseExists.test(namespace, name)) {
                LOG.info("Skipping dependency install because release exists: {} in {}", name, namespace);
                return "Dependency already installed as Helm release '" + name + "' in namespace '" + namespace + "'";
            }
            LOG.info("Dependency {} not found in {}; proceeding with install", name, namespace);
        }
        return null;
    }

    private static boolean flag(Map<String, Object> spec, String key) {
        Object v = spec.get(key);
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(String.valueOf(v));
    }

    private static String text(Map<String, Object> spec, String key) {
        Object v = spec.get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }
}
