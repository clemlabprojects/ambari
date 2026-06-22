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
 * Represents the type of Helm command being executed
 */
public enum CommandType {

    /** Top-level composite command that orchestrates a full release deploy pipeline. */
    K8S_MANAGER_RELEASE_DEPLOY,

    /** Executes scripts or binaries from a mounted directory inside a pod. */
    K8S_MOUNTS_DIR_EXEC,

    /** Validates that all required Ambari stack configuration properties are present before deployment. */
    CLUSTER_STACK_CONFIGURATION_REQUIREMENTS,

    /** Checks whether a chart exists in a Helm repository without modifying state. */
    HELM_REPO_VALIDATE,

    /** Registers a new Helm repository in the local repository index. */
    HELM_REPO_ADD,

    /** Removes a previously registered Helm repository from the local index. */
    HELM_REPO_REMOVE,

    /** Authenticates against an OCI or HTTP Helm repository. */
    HELM_REPO_LOGIN,

    /** Runs {@code helm install --dry-run} to validate rendering without applying changes. */
    HELM_DEPLOY_DRY_RUN,

    /** Materialises Ambari configuration into Kubernetes ConfigMaps or Secrets before install. */
    CONFIG_MATERIALIZE,

    /** Installs a new Helm release into a namespace. */
    HELM_DEPLOY,

    /** Upgrades an existing Helm release to a new chart version or values set. */
    HELM_UPGRADE,

    /** Uninstalls an existing Helm release and cleans up its resources. */
    HELM_UNINSTALL,

    /** Installs CRDs required by a dependency chart before the main chart is deployed. */
    HELM_DEPLOY_DEPENDENCY_CRD,

    /** Dry-runs a dependency release to validate its chart template before installation. */
    HELM_DEPLOY_DRY_RUN_DEPENDENCY_RELEASE,

    /** Installs a dependency Helm release that the main chart requires to function. */
    HELM_DEPLOY_DEPENDENCY_RELEASE,

    /** Confirms that a prerequisite dependency is already satisfied in the cluster. */
    DEPENDENCY_SATISFIED,

    /** Runs post-deploy verification checks to confirm the release is healthy. */
    POST_VERIFY,

    /** Root command for a Kerberos keytab provisioning workflow, spawning principal sub-steps. */
    KEYTAB_REQUEST_ROOT,

    /** Creates a Kerberos principal if it does not exist and generates its keytab (idempotent). */
    KEYTAB_ISSUE_PRINCIPAL,

    /** Writes a generated Kerberos keytab into a Kubernetes Opaque Secret. */
    KEYTAB_CREATE_SECRET,

    /** Root command for re-running keytab generation for an already-deployed release. */
    REGENERATE_KEYTABS,

    /** Registers or updates an OIDC client in Keycloak, returning the client id and secret. */
    OIDC_REGISTER_CLIENT,

    /** Writes OIDC client credentials into a Kubernetes Secret and optionally into Vault. */
    OIDC_CREATE_SECRET,

    /** Root command for a standalone OIDC client re-registration, mirroring {@link #REGENERATE_KEYTABS}. */
    REGISTER_OIDC_CLIENT,

    /** Creates or updates a Ranger service repository for the deployed application. */
    RANGER_REPOSITORY_CREATION,

    /** Creates the service account user in Ranger required by the deployed application. */
    RANGER_USER_CREATION,

    /** Re-applies an existing Ranger repository configuration without a full Helm upgrade. */
    RANGER_REPOSITORY_REAPPLY,

    /**
     * Registers OpenMetadata as a tag source in the ODP Ranger TagSync component.
     * Mints an Unlimited-TTL JWT for the OM ingestion-bot (via the OmBotJwtClient
     * exec-in-pod helper), writes the JWT + source endpoint + source class into
     * Ambari's {@code ranger-tagsync-site} stack config, then triggers a Ranger
     * TagSync component restart so the new source is picked up.
     *
     * <p>This step is OM-specific because the JWT-minting path uses OM's
     * Fernet-encrypted user_entity table + OM REST. Future TagSync source types
     * (Atlas-as-source, Hive metastore tags, etc.) would need their own
     * service-specific enums.
     *
     * <p>Driven by service.json entries with {@code type: "ranger-tagsync-source"}
     * under the OPENMETADATA service's {@code ranger} block.
     */
    OM_RANGER_TAGSYNC_REGISTER,

    /**
     * Standalone replay of {@link #OM_RANGER_TAGSYNC_REGISTER}, triggered by the
     * "Re-register Ranger TagSync source" button on the KDPS release row. Shares
     * its executor body with the deploy-time enum; the separate enum exists so
     * the command tree's run history shows replays distinctly from initial
     * registrations (mirrors the {@link #RANGER_REPOSITORY_REAPPLY} pattern).
     */
    OM_RANGER_TAGSYNC_REAPPLY,

    /**
     * Registers Apache Atlas as a federated metadata source in OpenMetadata.
     * Replaces the helm post-install hook Job previously shipped in the OM
     * chart. The step:
     * <ol>
     *   <li>Mints an Unlimited-TTL JWT for the OM ingestion-bot via
     *       {@code OmBotJwtClient}</li>
     *   <li>PUTs {@code /api/v1/services/metadataServices} on OM REST with
     *       the Atlas connection (URL + credentials Secret + database service
     *       names list)</li>
     *   <li>PUTs {@code /api/v1/services/databaseServices} for the federated
     *       hive-entities landing zone</li>
     *   <li>PUTs {@code /api/v1/services/ingestionPipelines} with the
     *       operator-selected schedule</li>
     *   <li>Triggers the first ingestion run</li>
     * </ol>
     *
     * <p>Driven by the {@code atlasFederation.enabled=true} form value in the
     * OPENMETADATA wizard.
     */
    OM_ATLAS_FEDERATION_REGISTER,

    /**
     * Standalone replay of {@link #OM_ATLAS_FEDERATION_REGISTER}, triggered by
     * the "Re-register Atlas federation" button on the KDPS release row.
     */
    OM_ATLAS_FEDERATION_REAPPLY,

    /**
     * Provisions the OpenMetadata federation user in an Ambari-managed Atlas
     * (basic-auth mode only). Writes {@code openmetadata.federation.username} +
     * {@code openmetadata.federation.password_hash} into the {@code atlas-env}
     * Ambari config. The ODP ATLAS stack scripts ({@code metadata.py}) read
     * these on the next Atlas restart and append a line to Atlas's
     * {@code users-credentials.properties} file. KDPS does NOT trigger the
     * Atlas restart — the operator drives it via the normal "Restart Required"
     * Ambari badge (Atlas restart blast-radius is larger than KDPS should own).
     *
     * <p>Idempotent: re-running with the same username/hash detects no diff in
     * Ambari config and is a no-op. The plaintext password is held only in an
     * operator-readable K8s Secret in the OM release namespace; the Atlas
     * config + credentials file only hold the SHA-256 hash.
     *
     * <p>Skipped when Atlas auth mode is kerberos/ldap (those modes don't use
     * basic auth, so no user provisioning is needed).
     */
    ATLAS_USER_PROVISION_OM,

    /**
     * Grants the OpenMetadata federation user the configured role in Atlas's
     * simple-authorizer policy file ({@code atlas-simple-authz-policy.json}).
     * The role lookup is sourced from {@code atlas-env.openmetadata.federation.role}
     * (default {@code ROLE_USER}) — set by {@link #ATLAS_USER_PROVISION_OM}
     * already, so this step typically writes nothing new; the actual file
     * merge happens in the ODP {@code metadata.py} hook on Atlas restart.
     *
     * <p>Exists as a distinct step (rather than folded into the user-provision
     * step) so the operator's command tree shows two clear lines: "user
     * created" + "ACL granted". Skipped when Atlas's authorizer is the
     * Ranger plugin (in that case {@link #RANGER_POLICY_CREATE_ATLAS_OM_READ}
     * is queued instead).
     */
    ATLAS_SIMPLE_AUTHZ_GRANT_OM,

    /**
     * Creates a read-only Ranger policy in the Atlas service repository that
     * grants the OpenMetadata federation user (basic-auth username OR Kerberos
     * principal, depending on Atlas auth mode) permission to read Atlas
     * entities + classifications. Used when Atlas's authorizer is the Ranger
     * plugin ({@code atlas.authorizer.impl=…ranger…}).
     *
     * <p>After POSTing the policy via {@code /service/public/v2/api/policy},
     * the step polls {@code /service/public/v2/api/policy/{id}} to confirm
     * the policy is queryable — that's the proxy signal that the Atlas-side
     * Ranger plugin has pulled it (the plugin's policy-cache reload runs on
     * a short interval; the lookup also forces a refresh on the Admin side).
     *
     * <p>Idempotent: a GET by-name first short-circuits the POST if the policy
     * already exists for this OM user + Atlas service repo combination.
     */
    RANGER_POLICY_CREATE_ATLAS_OM_READ,

    /** Automatically provisions a linked Ambari view instance after a successful deploy. */
    AMBARI_VIEW_PROVISION,

    /**
     * Mints an ingress TLS leaf certificate (PEM cert + key) and writes it into a
     * {@code kubernetes.io/tls} Secret, so the Helm install step can reference it via
     * {@code ingress.tls[].secretName}. Step params carry the signing-CA selection:
     * {@code "ca": "ambariInternal"} reuses Ambari's internal CA (testing),
     * {@code "ca": "companyUploaded"} loads a CA from the {@link
     * org.apache.ambari.view.k8s.service.CaRegistryService} by name.
     */
    INGRESS_TLS_SELFSIGN,

    /**
     * Root command type for an operator-initiated TLS certificate renewal of a running release.
     * Schedules one INGRESS_TLS_SELFSIGN sub-step per host whose Secret is k8s-view-managed,
     * one cert-manager Certificate annotation patch per host managed by cert-manager, and an
     * ExternalSecret refresh per host backed by external-secrets. Operator-managed Secrets
     * (source=external) are skipped with an explanatory message.
     */
    TLS_RENEW,

    /**
     * Writes a cert-manager.io/v1 Certificate resource for an ingress, so cert-manager
     * provisions (and auto-renews) the leaf Secret the chart references via
     * {@code ingress.tls[].secretName}. Step params: {@code namespace}, {@code certName},
     * {@code secretName}, {@code issuerName}, {@code issuerKind}, {@code dnsNames}.
     */
    INGRESS_TLS_CERTMANAGER,

    /**
     * Writes an external-secrets.io/v1 ExternalSecret referencing a SecretStore. Used to
     * sync a TLS leaf (or any other secret bundle) from Vault PKI / AWS Secrets Manager / etc.
     * into a K8s Secret the chart can mount.
     */
    INGRESS_TLS_EXTERNAL_SECRET,
}
