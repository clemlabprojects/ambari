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

import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // The KDPS platform context (Atlas/Ranger integration target) the release was deployed against,
    // so Upgrade/Config can re-select it instead of falling back to the ambari-managed default.
    @Column(length = 255)
    private String platformContextId;

    @Column(length = 255)
    private String chartRef;

    @Column(length = 255)
    private String repoId;

    @Column(length = 128)
    private String version;

    // The KDPS command id that deployed this release (commit SHA for the Flux path). Persisted: it is
    // the only structured link from a release back to the operation that produced it — CommandEntity
    // holds namespace/releaseName only inside paramsJson, so without this there is no queryable
    // release -> deployment trace.
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

    // ---------------------------------------------------------------------------------------
    // Git (Flux GitOps) metadata — ONE persisted column holding a small JSON object instead of
    // nine separate columns. Ambari's DataStore forces every String property to 3000 chars against
    // a 65000-per-entity total, i.e. a hard ceiling of 21 String properties; nine flat git columns
    // consumed almost half the budget for metadata only FLUX_GITOPS releases ever set. Folding them
    // here frees 8 slots. The nine getters/setters below are preserved and simply read/write this
    // JSON, so every existing call site is unchanged.
    // Size budget: the worst realistic case (repoUrl+path+prUrl ~512 each, the rest short) is well
    // under the 3000-char per-property limit the DataStore enforces on store().
    // ---------------------------------------------------------------------------------------
    @Column(length = 3000)
    private String gitMetaJson;

    // Reusable Trino catalogs attached at deploy time: JSON {catalogName: {id, hash}}. The release
    // values hold a SNAPSHOT of each catalog's properties; this column keeps the reference (which
    // object, which content version) so Upgrade/Config can re-select them and the catalog page can
    // show where a catalog is used. Null for every non-Trino release.
    @Column(length = 3000)
    private String catalogRefsJson;

    /** Parsed view of {@link #gitMetaJson}; rebuilt lazily and invalidated whenever the JSON is set. */
    @Transient
    private Map<String, String> gitMetaCache;

    // Flag: managed by UI (true if registered here)
    private boolean managedByUi;

    // ISO-8601 timestamps (e.g. "2025-04-15T10:30:00Z") — persisted so they
    // survive Ambari restarts and upgrades.  Length 40 is generous for any
    // ISO-8601 variant; they add negligible bytes to the row.
    @Column(length = 40)
    private String createdAt;

    @Column(length = 40)
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
    public String getCreatedAt() {
        return createdAt;
    }

    @Override
    public void setCreatedAt(String createdAt) {
        if (createdAt == null) {
            this.createdAt = Instant.now().toString();
        } else {
            this.createdAt = createdAt;
        }
    }

    @Override
    public String getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = (updatedAt != null) ? updatedAt : Instant.now().toString();
    }

    public String getServiceKey() {
        return serviceKey;
    }

    public void setServiceKey(String serviceKey) {
        this.serviceKey = serviceKey;
    }

    public String getPlatformContextId() {
        return platformContextId;
    }

    public void setPlatformContextId(String platformContextId) {
        this.platformContextId = platformContextId;
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

    public String getCatalogRefsJson() { return catalogRefsJson; }
    public void setCatalogRefsJson(String catalogRefsJson) { this.catalogRefsJson = catalogRefsJson; }

    // ---------- Git metadata (folded into the single gitMetaJson column) ----------

    /** The only git property the DataStore persists. Setting it invalidates the parsed cache. */
    public String getGitMetaJson() {
        return gitMetaJson;
    }

    public void setGitMetaJson(String gitMetaJson) {
        this.gitMetaJson = gitMetaJson;
        this.gitMetaCache = null;
    }

    /** Lazily parse {@link #gitMetaJson}; never returns null, and never throws on malformed JSON. */
    private Map<String, String> gitMeta() {
        Map<String, String> cache = this.gitMetaCache;
        if (cache == null) {
            final Map<String, String> parsed = new LinkedHashMap<>();
            if (gitMetaJson != null && !gitMetaJson.isBlank()) {
                try {
                    JsonNode node = MAPPER.readTree(gitMetaJson);
                    if (node != null && node.isObject()) {
                        node.fields().forEachRemaining(e -> {
                            if (e.getValue() != null && !e.getValue().isNull()) {
                                parsed.put(e.getKey(), e.getValue().asText());
                            }
                        });
                    }
                } catch (Exception ignore) {
                    // Malformed/legacy content: treat as empty rather than breaking the whole release row.
                }
            }
            this.gitMetaCache = parsed;
            cache = parsed;
        }
        return cache;
    }

    /** Write one git attribute, re-serialising the column. A null/blank value removes the key. */
    private void putGitMeta(String key, String value) {
        Map<String, String> meta = gitMeta();
        if (value == null || value.isBlank()) {
            meta.remove(key);
        } else {
            meta.put(key, value);
        }
        try {
            this.gitMetaJson = meta.isEmpty() ? null : MAPPER.writeValueAsString(meta);
        } catch (Exception e) {
            // Should not happen for a Map<String,String>; keep the previous JSON rather than corrupting it.
        }
    }

    public String getGitCommitSha() {
        return gitMeta().get("commitSha");
    }

    public void setGitCommitSha(String gitCommitSha) {
        putGitMeta("commitSha", gitCommitSha);
    }

    public String getGitBranch() {
        return gitMeta().get("branch");
    }

    public void setGitBranch(String gitBranch) {
        putGitMeta("branch", gitBranch);
    }

    public String getGitRepoUrl() {
        return gitMeta().get("repoUrl");
    }

    public void setGitRepoUrl(String gitRepoUrl) {
        putGitMeta("repoUrl", gitRepoUrl);
    }

    public String getGitPath() {
        return gitMeta().get("path");
    }

    public void setGitPath(String gitPath) {
        putGitMeta("path", gitPath);
    }

    public String getGitCredentialAlias() {
        return gitMeta().get("credentialAlias");
    }

    public void setGitCredentialAlias(String gitCredentialAlias) {
        putGitMeta("credentialAlias", gitCredentialAlias);
    }

    public String getGitCommitMode() {
        return gitMeta().get("commitMode");
    }

    public void setGitCommitMode(String gitCommitMode) {
        putGitMeta("commitMode", gitCommitMode);
    }

    public String getGitPrUrl() {
        return gitMeta().get("prUrl");
    }

    public void setGitPrUrl(String gitPrUrl) {
        putGitMeta("prUrl", gitPrUrl);
    }

    public String getGitPrNumber() {
        return gitMeta().get("prNumber");
    }

    public void setGitPrNumber(String gitPrNumber) {
        putGitMeta("prNumber", gitPrNumber);
    }

    public String getGitPrState() {
        return gitMeta().get("prState");
    }

    public void setGitPrState(String gitPrState) {
        putGitMeta("prState", gitPrState);
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
