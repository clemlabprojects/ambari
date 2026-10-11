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

import java.util.Map;

/**
 * What a deploy needs on OpenShift so that its metrics reach the platform monitoring and its KEDA autoscaling can read
 * them, for every deployment mode (direct Helm and GitOps).
 *
 * <ol>
 *   <li>{@link #scopeAutoscalingToProject}: before any values are saved, point the KEDA triggers at the release's
 *       project when the account may not grant cluster-wide monitoring access (see {@link KedaThanosScope}).</li>
 *   <li>{@link #prepare}: before the release is installed, check the account may create ServiceMonitors and provision
 *       the autoscaler's monitoring token, or stop with what the platform team has to grant.</li>
 * </ol>
 */
public final class OpenShiftMonitoringSetup {

    private static final Logger LOG = LoggerFactory.getLogger(OpenShiftMonitoringSetup.class);

    private OpenShiftMonitoringSetup() {
    }

    /** Whether the KEDA triggers of this deploy read the project's monitoring only (Thanos tenancy port). */
    static boolean usesProjectScope(KubernetesService kubernetesService, HelmDeployRequest request) {
        return kubernetesService.isOpenShiftCluster()
                && KedaThanosScope.kedaEnabled(request.getValues())
                && KedaThanosScope.triggerAuthenticationEnabled(request.getValues())
                && !kubernetesService.canBindClusterRoles();
    }

    /**
     * Points the KEDA triggers at the release's project when the account may not create ClusterRoleBindings. Call
     * before the values are saved by any deployment mode.
     *
     * @return {@code true} when the triggers read the project's monitoring only
     */
    public static boolean scopeAutoscalingToProject(KubernetesService kubernetesService, HelmDeployRequest request) {
        if (!usesProjectScope(kubernetesService, request)) {
            return false;
        }
        int scoped = KedaThanosScope.scopeTriggersToNamespace(request.getValues(), request.getNamespace());
        LOG.info("KEDA autoscaling for {}/{}: this account may not bind cluster roles; {} trigger(s) read the "
                + "project's monitoring through the Thanos tenancy port", request.getNamespace(), request.getReleaseName(), scoped);
        return true;
    }

    /**
     * Checks the account may create ServiceMonitors and provisions the KEDA monitoring token. Does nothing outside
     * OpenShift.
     *
     * @throws IllegalStateException with the grants a platform team has to make, when the account lacks them
     */
    public static void prepare(KubernetesService kubernetesService, HelmDeployRequest request) {
        if (!kubernetesService.isOpenShiftCluster()) {
            return;
        }
        Map<String, Object> values = request.getValues();
        // ServiceMonitors (how the service's metrics reach OpenShift monitoring) need the monitoring-edit role in the
        // project, which a project admin does not hold by default. Without it the install fails half-way through.
        if (KedaThanosScope.serviceMonitorsEnabled(values)
                && !kubernetesService.canI("create", "monitoring.coreos.com", "servicemonitors", null, request.getNamespace())) {
            throw new IllegalStateException("Cannot deploy " + request.getReleaseName() + ": this account may not create "
                    + "ServiceMonitors in project " + request.getNamespace() + " (needed so OpenShift monitoring collects "
                    + "the service's metrics, which autoscaling reads). Ask the platform team to grant it, per project:\n"
                    + "  oc policy add-role-to-user monitoring-edit <account KDPS connects with> -n " + request.getNamespace());
        }
        // Autoscaling off: no autoscaler, so no monitoring token.
        if (!KedaThanosScope.kedaEnabled(values) || !KedaThanosScope.triggerAuthenticationEnabled(values)) {
            return;
        }
        String tokenSecretName = tokenSecretName(values);
        if (tokenSecretName == null || tokenSecretName.isBlank()) {
            throw new IllegalStateException("Cannot deploy " + request.getReleaseName()
                    + " with OpenShift KEDA autoscaling: server.keda.triggerAuthentication.secretName is not set.");
        }
        String problem = kubernetesService.ensureKedaThanosTokenSecret(request.getNamespace(), tokenSecretName,
                tokenSecretName, usesProjectScope(kubernetesService, request));
        if (problem != null) {
            throw new IllegalStateException("Cannot deploy " + request.getReleaseName()
                    + " with OpenShift KEDA autoscaling: " + problem);
        }
        LOG.info("KEDA/Thanos monitoring token Secret '{}' ensured for release {} in namespace {}",
                tokenSecretName, request.getReleaseName(), request.getNamespace());
    }

    private static String tokenSecretName(Map<String, Object> values) {
        Object server = values == null ? null : values.get("server");
        Object keda = server instanceof Map<?, ?> s ? s.get("keda") : null;
        Object auth = keda instanceof Map<?, ?> k ? k.get("triggerAuthentication") : null;
        Object name = auth instanceof Map<?, ?> a ? a.get("secretName") : null;
        return name == null ? null : String.valueOf(name);
    }
}
