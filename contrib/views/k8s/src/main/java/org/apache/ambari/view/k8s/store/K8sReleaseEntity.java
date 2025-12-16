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

package org.apache.ambari.view.k8s.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ambari.view.k8s.store.base.BaseModel;

import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracking entity for Helm releases managed by the UI.
 * ID = namespace + ":" + releaseName
 *
 * This model keeps core, short columns in the main table and pushes
 * variable/optional data into dedicated tables (attributes, git, endpoints)
 * to avoid breaching Ambari DataStore string length limits.
 */
@Access(AccessType.FIELD)
@Entity
@Table(name = "k8s_release2", indexes = {
        @Index(name = "idx_release_ns", columnList = "namespace"),
        @Index(name = "idx_release_name", columnList = "releaseName")
})
public class K8sReleaseEntity extends BaseModel {

    @Id
    @Column(length = 512) // "ns:release" can be long
    @Override
    public String getId() {
        return super.getId();
    }

    @Override
    public void setId(String id) {
        super.setId(id);
    }

    @Column(length = 255, nullable = false)
    private String namespace;

    @Column(length = 255, nullable = false)
    private String releaseName;

    @Column(length = 255)
    private String serviceKey;

    @Column(length = 255)
    private String chartRef;

    @Column(length = 255)
    private String repoId;

    @Column(length = 128)
    private String version;

    @Column(length = 255)
    private String deploymentId;

    @Column(length = 64)
    private String deploymentMode;

    @Column(length = 128)
    private String globalConfigVersion;

    @Column(length = 255)
    private String securityProfile;

    @Column(length = 255)
    private String securityProfileHash;

    @Column(length = 255)
    private String gitCommitSha;

    @Column(length = 255)
    private String gitBranch;

    @Column(length = 512)
    private String gitRepoUrl;

    @Column(length = 512)
    private String gitPath;

    @Column(length = 255)
    private String gitCredentialAlias;

    @Column(length = 64)
    private String gitCommitMode;

    @Column(length = 512)
    private String gitPrUrl;

    @Column(length = 64)
    private String gitPrNumber;

    @Column(length = 64)
    private String gitPrState;

    // Flag: managed by UI (true if registered here)
    private boolean managedByUi;

    // timestamps are transient to stay well below Ambari's 65K row limit
    @Transient
    private String createdAt;

    @Transient
    private String updatedAt;

    // Small cache of endpoints (computed on the fly but cached to avoid recompute).
    // Marked transient to keep total string column count under Ambari's 65k aggregation limit.
    @Transient
    private String endpointsJson;

    @Transient
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Basic columns
    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getReleaseName() {
        return releaseName;
    }

    public void setReleaseName(String releaseName) {
        this.releaseName = releaseName;
    }

    public boolean isManagedByUi() {
        return managedByUi;
    }

    public void setManagedByUi(boolean managedByUi) {
        this.managedByUi = managedByUi;
    }

    @Override
    @Transient
    @java.beans.Transient
    public String getCreatedAt() {
        return createdAt;
    }

    @Override
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    @Transient
    @java.beans.Transient
    public String getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getServiceKey() {
        return serviceKey;
    }

    public void setServiceKey(String serviceKey) {
        this.serviceKey = serviceKey;
    }

    public String getChartRef() {
        return chartRef;
    }

    public void setChartRef(String chartRef) {
        this.chartRef = chartRef;
    }

    public String getRepoId() {
        return repoId;
    }

    public void setRepoId(String repoId) {
        this.repoId = repoId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public void setDeploymentId(String deploymentId) {
        this.deploymentId = deploymentId;
    }

    public String getDeploymentMode() {
        return deploymentMode;
    }

    public void setDeploymentMode(String deploymentMode) {
        this.deploymentMode = deploymentMode;
    }

    public String getGlobalConfigVersion() {
        return globalConfigVersion;
    }

    public void setGlobalConfigVersion(String globalConfigVersion) {
        this.globalConfigVersion = globalConfigVersion;
    }

    public String getSecurityProfile() {
        return securityProfile;
    }

    public void setSecurityProfile(String securityProfile) {
        this.securityProfile = securityProfile;
    }

    public String getSecurityProfileHash() {
        return securityProfileHash;
    }

    public void setSecurityProfileHash(String securityProfileHash) {
        this.securityProfileHash = securityProfileHash;
    }

    // ID Helper method
    public static String idOf(String ns, String name) {
        return ns + ":" + name;
    }

    // ---------- Git metadata (flat columns) ----------
    public String getGitCommitSha() {
        return gitCommitSha;
    }

    public void setGitCommitSha(String gitCommitSha) {
        this.gitCommitSha = gitCommitSha;
    }

    public String getGitBranch() {
        return gitBranch;
    }

    public void setGitBranch(String gitBranch) {
        this.gitBranch = gitBranch;
    }

    public String getGitRepoUrl() {
        return gitRepoUrl;
    }

    public void setGitRepoUrl(String gitRepoUrl) {
        this.gitRepoUrl = gitRepoUrl;
    }

    public String getGitPath() {
        return gitPath;
    }

    public void setGitPath(String gitPath) {
        this.gitPath = gitPath;
    }

    public String getGitCredentialAlias() {
        return gitCredentialAlias;
    }

    public void setGitCredentialAlias(String gitCredentialAlias) {
        this.gitCredentialAlias = gitCredentialAlias;
    }

    public String getGitCommitMode() {
        return gitCommitMode;
    }

    public void setGitCommitMode(String gitCommitMode) {
        this.gitCommitMode = gitCommitMode;
    }

    public String getGitPrUrl() {
        return gitPrUrl;
    }

    public void setGitPrUrl(String gitPrUrl) {
        this.gitPrUrl = gitPrUrl;
    }

    public String getGitPrNumber() {
        return gitPrNumber;
    }

    public void setGitPrNumber(String gitPrNumber) {
        this.gitPrNumber = gitPrNumber;
    }

    public String getGitPrState() {
        return gitPrState;
    }

    public void setGitPrState(String gitPrState) {
        this.gitPrState = gitPrState;
    }

    // ---------- Endpoints stored as JSON array ----------
    @Transient
    @java.beans.Transient
    public List<K8sReleaseEndpointEntity> getEndpoints() {
        if (endpointsJson == null || endpointsJson.isBlank()) {
            return new ArrayList<>();
        }
        List<K8sReleaseEndpointEntity> list = new ArrayList<>();
        try {
            var node = MAPPER.readTree(endpointsJson);
            if (node.isArray()) {
                for (var item : node) {
                    K8sReleaseEndpointEntity ep = new K8sReleaseEndpointEntity();
                    ep.setName(item.path("name").asText(null));
                    ep.setType(item.path("type").asText(null));
                    ep.setUrl(item.path("url").asText(null));
                    list.add(ep);
                }
            }
        } catch (Exception ignored) {
            // return empty
        }
        return list;
    }

    public void setEndpoints(List<K8sReleaseEndpointEntity> eps) {
        if (eps == null || eps.isEmpty()) {
            endpointsJson = null;
            return;
        }
        ArrayNode arrayNode = MAPPER.createArrayNode();
        for (K8sReleaseEndpointEntity ep : eps) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("name", ep.getName());
            node.put("type", ep.getType());
            node.put("url", ep.getUrl());
            arrayNode.add(node);
        }
        endpointsJson = arrayNode.toString();
    }

    @Transient
    @java.beans.Transient
    public String getEndpointsJson() {
        return endpointsJson;
    }

    public void setEndpointsJson(String endpointsJson) {
        this.endpointsJson = endpointsJson;
    }
}
