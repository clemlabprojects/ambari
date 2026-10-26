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

import org.apache.ambari.view.k8s.service.cluster.ClusterRef;
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
 * ID = namespace + ":" + releaseName on the default cluster, "@" + cluster + ":" + namespace + ":" + releaseName on
 * another one (see {@link #idOf(ClusterRef, String, String)}).
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
    // Small per-release metadata (the Flux GitOps attributes and the selected truststores) — ONE persisted column
    // holding a small JSON object. Started as the git metadata column, instead of
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

    // What KDPS registered for the release outside Kubernetes (Keycloak clients, Ranger access, Kerberos principals,
    // Atlas, Ranger TagSync, view instances, Vault copies), which its uninstall takes back: see ProvisioningLedger.
    // A column of its own rather than a key of gitMetaJson, which is already close to full for a GitOps release.
    @Column(length = 3000)
    private String provisioningJson;

    /** What a release recorded since ledgers exist holds when KDPS registered nothing for it. */
    static final String EMPTY_LEDGER = "{}";

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

    /** Git metadata key of the Git server's SSH host keys, the one attribute that gives way when the column is full. */
    @Transient
    private static final String SSH_KNOWN_HOSTS = "sshKnownHosts";

    /** Metadata key of the objects KDPS created for the release ({@link #getKdpsObjects}). */
    @Transient
    private static final String KDPS_OBJECTS = "kdpsObjects";

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
    /** The id of a release of the default cluster. */
    public static String idOf(String ns, String name) {
        return idOf(ClusterRef.DEFAULT, ns, name);
    }

    /**
     * The id of a release: {@code namespace:release} on the default cluster, as before KDPS knew several clusters, and
     * {@code @cluster:namespace:release} on another one. Neither a cluster id nor a namespace holds ':' or '@', so the
     * cluster is read back from the id without ambiguity ({@link #clusterOfId}).
     */
    public static String idOf(ClusterRef cluster, String ns, String name) {
        return cluster.isDefault() ? ns + ":" + name : "@" + cluster.id() + ":" + ns + ":" + name;
    }

    /** The cluster of a release id; the default one for an id without a cluster. */
    public static ClusterRef clusterOfId(String id) {
        if (id != null && id.startsWith("@")) {
            int colon = id.indexOf(':');
            if (colon > 1) {
                return ClusterRef.of(id.substring(1, colon));
            }
        }
        return ClusterRef.DEFAULT;
    }

    /** The cluster the release is on. Not a bean property: it is read from the id, never stored apart. */
    public ClusterRef clusterRef() {
        return clusterOfId(getId());
    }

    public String getCatalogRefsJson() { return catalogRefsJson; }
    public void setCatalogRefsJson(String catalogRefsJson) { this.catalogRefsJson = catalogRefsJson; }

    /** The only ledger property the DataStore persists (see {@link #provisioningLedger}). */
    public String getProvisioningJson() { return provisioningJson; }
    public void setProvisioningJson(String provisioningJson) { this.provisioningJson = provisioningJson; }

    /** What KDPS registered for the release outside Kubernetes; empty when nothing is recorded. */
    public ProvisioningLedger provisioningLedger() {
        return ProvisioningLedger.parse(provisioningJson);
    }

    /**
     * Records the ledger. One that would no longer fit the column keeps what was recorded before, marked as missing
     * something ({@link ProvisioningLedger#fitting}): the DataStore would refuse the whole release record otherwise.
     */
    public void recordProvisioningLedger(ProvisioningLedger ledger) {
        ProvisioningLedger fitting = ProvisioningLedger.fitting(provisioningLedger(),
                ledger == null ? new ProvisioningLedger() : ledger, InstanceSettings.MAX_VALUE_LENGTH);
        String json = fitting.toJson();
        // Even an empty ledger is stored, so that a release recorded since ledgers exist is told apart from one
        // deployed earlier, whose ledger is unknown (see hasProvisioningLedger).
        this.provisioningJson = json == null ? EMPTY_LEDGER : json;
    }

    /** Whether the release has a ledger, which a release deployed before KDPS kept ledgers does not. */
    public boolean hasProvisioningLedger() {
        return provisioningJson != null && !provisioningJson.isBlank();
    }

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

    /**
     * Write one git attribute, re-serialising the column. A null/blank value removes the key. The Git server's SSH host
     * keys give way to any other attribute that would no longer fit the column with them: the DataStore refuses a
     * longer value when the release is stored, by then after Git was changed, while the host keys stay recorded with
     * the release's Git credential.
     */
    private void putGitMeta(String key, String value) {
        Map<String, String> meta = gitMeta();
        if (value == null || value.isBlank()) {
            meta.remove(key);
        } else {
            meta.put(key, value);
        }
        try {
            String json = meta.isEmpty() ? null : MAPPER.writeValueAsString(meta);
            if (json != null && json.length() > InstanceSettings.MAX_VALUE_LENGTH && !SSH_KNOWN_HOSTS.equals(key)
                    && meta.remove(SSH_KNOWN_HOSTS) != null) {
                json = meta.isEmpty() ? null : MAPPER.writeValueAsString(meta);
            }
            this.gitMetaJson = json;
        } catch (Exception e) {
            // Should not happen for a Map<String,String>; keep the previous JSON rather than corrupting it.
        }
    }

    /**
     * Truststores selected for the release in the deploy wizard (null when none was ever recorded). Kept in the
     * metadata JSON rather than a column of its own: a new column changes the entity, which needs a view version bump.
     */
    public java.util.List<String> getTruststoreRefs() {
        String json = gitMeta().get("truststoreRefs");
        if (json == null) {
            return null;
        }
        try {
            java.util.List<String> refs = new java.util.ArrayList<>();
            MAPPER.readTree(json).forEach(n -> refs.add(n.asText()));
            return refs;
        } catch (Exception malformed) {
            return null;
        }
    }

    /** Records the selected truststores; an empty list means "none" (the defaults only), null forgets the choice. */
    public void setTruststoreRefs(java.util.List<String> refs) {
        try {
            putGitMeta("truststoreRefs", refs == null ? null : MAPPER.writeValueAsString(refs));
        } catch (Exception e) {
            // A list of strings always serialises; keep the previous value otherwise.
        }
    }

    /**
     * The objects KDPS created for the release outside its chart, as {@code <Kind>/<name>}: what its uninstall removes,
     * a cluster-scoped object only when named here. Empty when a deploy found none; null when no deploy ever recorded
     * them (one by an earlier version, which did not label them either). Kept in the metadata JSON like the
     * truststores, for the same reason.
     */
    public java.util.List<String> getKdpsObjects() {
        String json = gitMeta().get(KDPS_OBJECTS);
        if (json == null) {
            return null;
        }
        try {
            java.util.List<String> objects = new java.util.ArrayList<>();
            MAPPER.readTree(json).forEach(n -> objects.add(n.asText()));
            return objects;
        } catch (Exception malformed) {
            return null;
        }
    }

    /**
     * Records the objects KDPS created for the release, an empty list included (a deploy that labels what it creates
     * found none). When they would not fit the column with the rest of the metadata, only the cluster-scoped ones are
     * kept, which the uninstall cannot find by their labels alone; the namespaced ones it finds by their labels.
     */
    public void setKdpsObjects(java.util.List<String> objects) {
        try {
            putGitMeta(KDPS_OBJECTS, objects == null ? null : MAPPER.writeValueAsString(objects));
            if (objects != null && gitMetaJson != null && gitMetaJson.length() > InstanceSettings.MAX_VALUE_LENGTH) {
                java.util.List<String> clusterScoped = objects.stream()
                        .filter(o -> o != null && o.startsWith("ClusterRoleBinding/")).toList();
                putGitMeta(KDPS_OBJECTS, MAPPER.writeValueAsString(clusterScoped));
                if (gitMetaJson != null && gitMetaJson.length() > InstanceSettings.MAX_VALUE_LENGTH) {
                    putGitMeta(KDPS_OBJECTS, null);
                }
            }
        } catch (Exception e) {
            // A list of strings always serialises; keep the previous value otherwise.
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

    /**
     * The base branch of a GitOps release, which Flux reconciles (null when never recorded). The working branch
     * ({@link #getGitBranch}) is another one for a pull request, still to merge.
     */
    public String getGitBaseBranch() {
        return gitMeta().get("baseBranch");
    }

    public void setGitBaseBranch(String gitBaseBranch) {
        putGitMeta("baseBranch", gitBaseBranch);
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

    /**
     * The SSH host keys of the release's Git server, as known_hosts lines (null when none is recorded): a connection
     * over SSH requires the server to present one of them.
     */
    public String getGitSshKnownHosts() {
        return gitMeta().get(SSH_KNOWN_HOSTS);
    }

    /**
     * Records the SSH host keys of the release's Git server. When the metadata would no longer fit its column, the
     * release keeps no host keys at all ({@link #getGitSshKnownHosts} gives null), rather than the previous ones: those
     * may be keys the server no longer presents, and would take precedence over the current ones recorded with the
     * release's Git credential. Dropped later when another Git attribute needs the room ({@link #putGitMeta}).
     */
    public void setGitSshKnownHosts(String knownHosts) {
        putGitMeta(SSH_KNOWN_HOSTS, knownHosts);
        if (gitMetaJson != null && gitMetaJson.length() > InstanceSettings.MAX_VALUE_LENGTH) {
            putGitMeta(SSH_KNOWN_HOSTS, null);
        }
    }

    public String getGitCommitMode() {
        return gitMeta().get("commitMode");
    }

    /** The GitOps output profile the release is written into Git with; {@code null} for the Flux layout. */
    public String getGitProfile() {
        return gitMeta().get("profile");
    }

    public void setGitProfile(String gitProfile) {
        putGitMeta("profile", gitProfile);
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

    /**
     * The spec digest annotation of the HelmRelease the last GitOps change of the release committed, which Flux copies
     * to the cluster once it has applied that change (null when never recorded).
     */
    public String getGitSpecDigest() {
        return gitMeta().get("specDigest");
    }

    public void setGitSpecDigest(String gitSpecDigest) {
        putGitMeta("specDigest", gitSpecDigest);
    }

    /**
     * Whether the uninstall of a GitOps release waits for its removal to be merged into the branch Flux reconciles: the
     * release still runs, and its pull request is the one recorded ({@link #getGitPrUrl}).
     */
    public boolean isGitPendingDelete() {
        return "true".equals(gitMeta().get("pendingDelete"));
    }

    public void setGitPendingDelete(boolean pendingDelete) {
        putGitMeta("pendingDelete", pendingDelete ? "true" : null);
    }

    /**
     * The HelmReleases a removal waiting for its merge takes out of Git, as {@code <namespace>/<name>}: the release's
     * and its dependencies'. Their values Secrets are deleted once it is merged, when Git no longer names them.
     * {@code null} when none is recorded.
     */
    public java.util.List<String> getGitRemovedHelmReleases() {
        String json = gitMeta().get("removedHelmReleases");
        if (json == null) {
            return null;
        }
        try {
            java.util.List<String> names = new java.util.ArrayList<>();
            MAPPER.readTree(json).forEach(n -> names.add(n.asText()));
            return names;
        } catch (Exception malformed) {
            return null;
        }
    }

    public void setGitRemovedHelmReleases(java.util.List<String> names) {
        try {
            putGitMeta("removedHelmReleases", names == null || names.isEmpty() ? null : MAPPER.writeValueAsString(names));
        } catch (Exception e) {
            // A list of strings always serialises; keep the previous value otherwise.
        }
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
