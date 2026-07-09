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
    private String kind;            // MANAGED | EXTERNAL | REMOTE
    private String clusterName;     // Ambari cluster (MANAGED / REMOTE)
    private String remoteAmbariUrl; // REMOTE only — the remote Ambari base URL (for display; no creds)

    // REMOTE-cluster info (for display): discovered live on resolve, cached into the context config.
    private String ambariVersion;   // remote Ambari server version (e.g. 2.7.11.0)
    private String stackName;       // remote running stack name (e.g. ODP-1.3)
    private String stackVersion;    // remote running stack repository version (e.g. 1.3.2.0-1)
    private String lastContactAt;   // ISO-8601 timestamp of the last successful contact with the remote Ambari

    // Atlas
    private String atlasUrl;        // scheme://host:port (no trailing slash)
    private String atlasAuthMode;   // basic | ldap | kerberos | none
    private String atlasAclMode;    // ranger | simple
    private boolean atlasManaged;   // Atlas is an Ambari-managed service in this context
    private String atlasRangerServiceName; // Ranger service repo name for Atlas (e.g. <cluster>_atlas)
    private String kerberosRealm;

    // When false, KDPS must NOT push configuration to this context's remote Ranger/Atlas
    // (create repositories, policies, tagsync, atlas federation). The operator opted to configure
    // those components themselves; KDPS instead surfaces the required manual steps + preflight
    // "verify" checks. This gates the WRITE/push paths ONLY — property RESOLUTION (reads) is
    // unaffected. Defaults to true (KDPS auto-configures). Meaningful for EXTERNAL/REMOTE contexts.
    private boolean autoConfigureRemote = true;

    // Ranger
    private boolean rangerManaged;  // true → delegate ops to Ambari server; false → view-side REST
    private String rangerUrl;       // EXTERNAL only
    private String rangerAdminUsername; // EXTERNAL only
    private String rangerAdminPassword; // EXTERNAL only (decrypted; never logged)
    private String atlasFederationUser;     // EXTERNAL only
    private String atlasFederationPassword; // EXTERNAL only (decrypted; never logged)

    // OIDC / Keycloak — the coordinates + admin (client-registration) credentials KDPS uses to
    // register a service's OIDC client. Populated for EXTERNAL (typed config + decrypted secret) and
    // REMOTE (remote oidc-env). The default local MANAGED context leaves these null so the OIDC
    // registration step keeps reading the local Ambari oidc-env (unchanged behaviour).
    private String oidcIssuerUrl;
    private String oidcRealm;
    private String oidcAdminUrl;
    private String oidcAdminRealm;
    private String oidcAdminClientId;
    private String oidcAdminClientSecret; // decrypted; never logged
    private String oidcVerifyTls;
    private String oidcPrincipalDomain;

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

    public String getRemoteAmbariUrl() { return remoteAmbariUrl; }
    public void setRemoteAmbariUrl(String remoteAmbariUrl) { this.remoteAmbariUrl = remoteAmbariUrl; }

    public String getAmbariVersion() { return ambariVersion; }
    public void setAmbariVersion(String ambariVersion) { this.ambariVersion = ambariVersion; }

    public String getStackName() { return stackName; }
    public void setStackName(String stackName) { this.stackName = stackName; }

    public String getStackVersion() { return stackVersion; }
    public void setStackVersion(String stackVersion) { this.stackVersion = stackVersion; }

    public String getLastContactAt() { return lastContactAt; }
    public void setLastContactAt(String lastContactAt) { this.lastContactAt = lastContactAt; }

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
    public boolean isAutoConfigureRemote() { return autoConfigureRemote; }
    public void setAutoConfigureRemote(boolean autoConfigureRemote) { this.autoConfigureRemote = autoConfigureRemote; }

    public String getRangerUrl() { return rangerUrl; }
    public void setRangerUrl(String rangerUrl) { this.rangerUrl = rangerUrl; }

    public String getRangerAdminUsername() { return rangerAdminUsername; }
    public void setRangerAdminUsername(String rangerAdminUsername) { this.rangerAdminUsername = rangerAdminUsername; }

    public String getRangerAdminPassword() { return rangerAdminPassword; }
    public void setRangerAdminPassword(String rangerAdminPassword) { this.rangerAdminPassword = rangerAdminPassword; }

    public String getOidcIssuerUrl() { return oidcIssuerUrl; }
    public void setOidcIssuerUrl(String v) { this.oidcIssuerUrl = v; }
    public String getOidcRealm() { return oidcRealm; }
    public void setOidcRealm(String v) { this.oidcRealm = v; }
    public String getOidcAdminUrl() { return oidcAdminUrl; }
    public void setOidcAdminUrl(String v) { this.oidcAdminUrl = v; }
    public String getOidcAdminRealm() { return oidcAdminRealm; }
    public void setOidcAdminRealm(String v) { this.oidcAdminRealm = v; }
    public String getOidcAdminClientId() { return oidcAdminClientId; }
    public void setOidcAdminClientId(String v) { this.oidcAdminClientId = v; }
    public String getOidcAdminClientSecret() { return oidcAdminClientSecret; }
    public void setOidcAdminClientSecret(String v) { this.oidcAdminClientSecret = v; }
    public String getOidcVerifyTls() { return oidcVerifyTls; }
    public void setOidcVerifyTls(String v) { this.oidcVerifyTls = v; }
    public String getOidcPrincipalDomain() { return oidcPrincipalDomain; }
    public void setOidcPrincipalDomain(String v) { this.oidcPrincipalDomain = v; }

    /** True when this context carries OIDC at all (an issuer URL) — the signal that an OIDC security
     *  profile should be derived from it. */
    public boolean hasOidc() {
        return oidcIssuerUrl != null && !oidcIssuerUrl.isBlank();
    }

    /**
     * True when this context carries the admin (client-registration) credentials KDPS needs to
     * register a service's OIDC client itself (source=internal): a Keycloak admin base URL, a
     * registration client id and its secret. When false but {@link #hasOidc()} is true, the context
     * describes a Keycloak whose clients must be pre-registered (source=external).
     */
    public boolean hasContextOidcAdminCreds() {
        return oidcAdminUrl != null && !oidcAdminUrl.isBlank()
                && oidcAdminClientId != null && !oidcAdminClientId.isBlank()
                && oidcAdminClientSecret != null && !oidcAdminClientSecret.isBlank();
    }

    /**
     * True when this context carries everything KDPS needs to drive Ranger DIRECTLY over REST:
     * an Admin URL plus admin username+password. This is the single, context-kind-agnostic switch
     * every Ranger operation (repository creation, policy/grant creation) uses to choose between the
     * direct-REST path (this context's Ranger) and the Ambari-server-delegated path. Populated for
     * EXTERNAL contexts from stored config, and for a MANAGED/remote context when the operator has
     * entered admin credentials on it; blank on the default managed context (→ Ambari delegation).
     */
    public boolean hasDirectRangerCreds() {
        return rangerUrl != null && !rangerUrl.isBlank()
                && rangerAdminUsername != null && !rangerAdminUsername.isBlank()
                && rangerAdminPassword != null && !rangerAdminPassword.isBlank();
    }

    public String getAtlasFederationUser() { return atlasFederationUser; }
    public void setAtlasFederationUser(String v) { this.atlasFederationUser = v; }

    public String getAtlasFederationPassword() { return atlasFederationPassword; }
    public void setAtlasFederationPassword(String v) { this.atlasFederationPassword = v; }

    public java.util.Map<String, String> getResolvedFields() { return resolvedFields; }
    public void setResolvedFields(java.util.Map<String, String> resolvedFields) { this.resolvedFields = resolvedFields; }

    public java.util.List<String> getSecretFieldsSet() { return secretFieldsSet; }
    public void setSecretFieldsSet(java.util.List<String> secretFieldsSet) { this.secretFieldsSet = secretFieldsSet; }
}
