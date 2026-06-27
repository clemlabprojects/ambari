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

/**
 * The fully-resolved view of a {@link org.apache.ambari.view.k8s.store.KdpsContextEntity}
 * that step bodies consume at deploy time. For a MANAGED context the fields are populated
 * live from Ambari; for an EXTERNAL context they come from the stored config + decrypted
 * secrets.
 *
 * <p>The crucial flag is {@link #rangerManaged}: when {@code true}, Ranger operations must
 * be delegated to the Ambari server (which holds the Ranger admin password) — the view
 * never sees the credential. When {@code false} (external), the view performs Ranger REST
 * calls directly with {@link #rangerAdminUsername}/{@link #rangerAdminPassword}.
 */
public class ResolvedContext {

    private String id;
    private String name;
    private String kind;            // MANAGED | EXTERNAL
    private String clusterName;     // Ambari cluster (MANAGED)

    // Atlas
    private String atlasUrl;        // scheme://host:port (no trailing slash)
    private String atlasAuthMode;   // basic | ldap | kerberos | none
    private String atlasAclMode;    // ranger | simple
    private boolean atlasManaged;   // Atlas is an Ambari-managed service in this context
    private String atlasRangerServiceName; // Ranger service repo name for Atlas (e.g. <cluster>_atlas)
    private String kerberosRealm;

    // Ranger
    private boolean rangerManaged;  // true → delegate ops to Ambari server; false → view-side REST
    private String rangerUrl;       // EXTERNAL only
    private String rangerAdminUsername; // EXTERNAL only
    private String rangerAdminPassword; // EXTERNAL only (decrypted; never logged)
    private String atlasFederationUser;     // EXTERNAL only
    private String atlasFederationPassword; // EXTERNAL only (decrypted; never logged)

    /**
     * Generic, schema-driven view of every resolved non-secret field, keyed
     * {@code <capability>.<field>} (e.g. {@code atlas.url}, {@code hive.metastoreUri}).
     * Populated from the managed resolver (MANAGED) or stored config (EXTERNAL). Secret
     * fields are NOT included here — only their presence is reflected via {@link #secretFieldsSet}.
     * This is what the schema-driven UI renders; the typed accessors above are what ops consume.
     */
    private java.util.Map<String, String> resolvedFields = new java.util.LinkedHashMap<>();

    /** Names ({@code <capability>.<field>}) of secret fields that have a value set. */
    private java.util.List<String> secretFieldsSet = new java.util.ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getAtlasUrl() { return atlasUrl; }
    public void setAtlasUrl(String atlasUrl) { this.atlasUrl = atlasUrl; }

    public String getAtlasAuthMode() { return atlasAuthMode; }
    public void setAtlasAuthMode(String atlasAuthMode) { this.atlasAuthMode = atlasAuthMode; }

    public String getAtlasAclMode() { return atlasAclMode; }
    public void setAtlasAclMode(String atlasAclMode) { this.atlasAclMode = atlasAclMode; }

    public boolean isAtlasManaged() { return atlasManaged; }
    public void setAtlasManaged(boolean atlasManaged) { this.atlasManaged = atlasManaged; }

    public String getAtlasRangerServiceName() { return atlasRangerServiceName; }
    public void setAtlasRangerServiceName(String atlasRangerServiceName) { this.atlasRangerServiceName = atlasRangerServiceName; }

    public String getKerberosRealm() { return kerberosRealm; }
    public void setKerberosRealm(String kerberosRealm) { this.kerberosRealm = kerberosRealm; }

    public boolean isRangerManaged() { return rangerManaged; }
    public void setRangerManaged(boolean rangerManaged) { this.rangerManaged = rangerManaged; }

    public String getRangerUrl() { return rangerUrl; }
    public void setRangerUrl(String rangerUrl) { this.rangerUrl = rangerUrl; }

    public String getRangerAdminUsername() { return rangerAdminUsername; }
    public void setRangerAdminUsername(String rangerAdminUsername) { this.rangerAdminUsername = rangerAdminUsername; }

    public String getRangerAdminPassword() { return rangerAdminPassword; }
    public void setRangerAdminPassword(String rangerAdminPassword) { this.rangerAdminPassword = rangerAdminPassword; }

    public String getAtlasFederationUser() { return atlasFederationUser; }
    public void setAtlasFederationUser(String v) { this.atlasFederationUser = v; }

    public String getAtlasFederationPassword() { return atlasFederationPassword; }
    public void setAtlasFederationPassword(String v) { this.atlasFederationPassword = v; }

    public java.util.Map<String, String> getResolvedFields() { return resolvedFields; }
    public void setResolvedFields(java.util.Map<String, String> resolvedFields) { this.resolvedFields = resolvedFields; }

    public java.util.List<String> getSecretFieldsSet() { return secretFieldsSet; }
    public void setSecretFieldsSet(java.util.List<String> secretFieldsSet) { this.secretFieldsSet = secretFieldsSet; }
}
