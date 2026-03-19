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

import com.marcnuri.helm.Release;

import java.util.List;

public class HelmReleaseDTO {
    public String id;
    public String name;
    public String namespace;
    public String chart;
    public String version;
    public String appVersion;
    public String status;
    public String updated;

    public boolean managedByUi;
    public String serviceKey;
    public String repoId;
    public String chartRef;
    public boolean restartRequired;
    public String securityProfile;
    public boolean securityProfileStale;
    public String message;
    public String lastAppliedRevision;
    public String lastAttemptedRevision;
    public String lastHandledReconcileAt;
    public String deploymentMode; // DIRECT_HELM (default) or FLUX_GITOPS
    public String gitCommitSha;
    public String gitBranch;
    public String gitPath;
    public String gitRepoUrl;
    public String gitPrUrl;
    public String gitPrNumber;
    public String gitPrState;
    public String sourceStatus;
    public String sourceMessage;
    public String sourceName;
    public String sourceNamespace;
    public String reconcileState;
    public String reconcileMessage;
    public String observedGeneration;
    public String desiredGeneration;
    public boolean staleGeneration;
    public String lastTransitionTime;
    public java.util.List<java.util.Map<String, String>> conditions;
    public java.util.List<java.util.Map<String, String>> sourceConditions;
    public String conditionSummary;
    public String sourceConditionSummary;
    public List<ReleaseEndpointDTO> endpoints;

    private static String parseVersionFromChart(String chart) {
        if (chart == null || chart.isBlank()) return null;
        // Common helm string: "<name>-<version>" optionally with repo prefix already stripped.
        int idx = chart.lastIndexOf('-');
        if (idx > 0 && idx < chart.length() - 1) {
            String maybeVersion = chart.substring(idx + 1);
            // crude semver-ish check
            if (maybeVersion.matches("\\d+\\.\\d+.*")) {
                return maybeVersion;
            }
        }
        return null;
    }

    public static HelmReleaseDTO from(Release r) {
        HelmReleaseDTO dto = new HelmReleaseDTO();
        dto.id = r.getName();
        dto.name = r.getName();
        dto.namespace = r.getNamespace();
        dto.chart = r.getChart();
        dto.version = parseVersionFromChart(r.getChart()); // best-effort from chart string
        dto.appVersion = r.getAppVersion();
        dto.status = String.valueOf(r.getStatus());
        dto.updated = null;
        return dto;
    }
}
