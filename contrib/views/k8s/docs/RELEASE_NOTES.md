<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# KDPS release notes

## Ambari 2.8.2.0

### Stored credentials are encrypted

The KDPS view now keeps these credentials encrypted with the Ambari master key, and no longer returns them to the
browser (pages show that a value is set, and leaving the field blank keeps it):

- the bind passwords of LDAP and Active Directory security profiles, and the client secret of external OIDC profiles;
- the passwords and tokens of authenticated chart registries;
- the Thanos object store access key and secret key;
- the Keycloak client secret created for each release deployed with OIDC.

When the view is upgraded, the copies earlier builds kept in clear in the Ambari database are encrypted or removed.
Database backups taken before the upgrade still hold them in clear, and so may the data Ambari keeps for the previous
version of the view. Treat those backups as sensitive, and rotate:

- the Keycloak client secrets of releases deployed with an earlier build (regenerate the secret in Keycloak, then
  redeploy the release);
- the AD/LDAP bind passwords of your security profiles;
- the client secrets of external OIDC security profiles;
- the passwords and tokens of authenticated chart registries;
- the Thanos object store keys, if you set them.

After rotating, enter the new values in the view: Security Profile, Helm Repositories and Cluster Settings.

### The Ambari master key is required

KDPS encrypts the kubeconfig and every credential it keeps with the Ambari master key, and no longer falls back to a
passphrase built into the view. **Classic (RPM) installs need a master key:** run `ambari-server setup-security`,
option "Encrypt passwords stored in ambari.properties file", and persist the master key (or set
`AMBARI_SECURITY_MASTER_KEY` in the Ambari server's environment), then restart Ambari.

- Until then, the Configuration page says so, and saving a credential is refused with HTTP 412 and these steps: the
  kubeconfig upload, the OpenShift login, a proxy password, platform contexts, chart repositories, security profiles,
  the Thanos keys and deploys. Nothing is stored.
- The view finds the key where Ambari does: the `master` file in the directory named by `security.master.key.location`,
  or else by `security.server.keys_dir`, in `ambari.properties`. A key directory moved away from
  `/var/lib/ambari-server/keys` is followed.
- Credentials an earlier build stored with its built-in passphrase are encrypted again with the master key on the first
  start where the key is found, whether they are used or not (the view's settings, the kubeconfig file, operation
  files). Ambari logs one warning when it finds them, and names any credential that must be entered again. The built-in passphrase was public, so treat those earlier copies, and the database
  backups and files that hold them, as unencrypted: rotate the credentials listed above, and the kubeconfig token or
  OpenShift password.
- The kubeconfig file, the OpenShift CA file and the view's working directory are now readable by the Ambari account
  only (files `0600`, directory `0700`).

Ambari deployed with the `ambari` chart is not affected: the chart and the image already require a master key.

### Services not in this release

These services were offered by pre-release builds and are no longer in the service catalog.

- **SQL Assistant**: not ready for production use. Its model server would be reachable without authentication
  through the Route or Ingress the wizard turned on by default. It needs to download its model from the internet, which
  an air-gapped cluster cannot do. It also cannot query a Trino that uses TLS or a Kerberized HiveServer2. The SQL
  Assistant Ambari view is not packaged either.
- **dbt**: not ready for production use. It cannot be installed on a Kerberized cluster, whatever the authentication
  mode. Its Trino password or token would be kept in the chart values, and committed to Git in GitOps mode. Its
  documentation site would be published without login or TLS. To run dbt, use your own tooling against the Trino
  that KDPS deploys.
- **JupyterHub**: not part of this release. It is no longer offered in the catalog or in the install list of the
  Releases page. A JupyterHub release deployed by a pre-release build stays in the Releases list and can be
  uninstalled, but it can no longer be deployed again or upgraded from KDPS. The container images that pre-release
  builds published for it have been withdrawn: do not run them.

### Services with limits

- **GitLab CE** is offered on plain Kubernetes only, for development clusters. On OpenShift it is not in the
  catalog, and a deploy sent to the API is refused with "GitLab is not supported on OpenShift in this release; register
  your existing Git server under Git Repositories." The GitLab chart runs its pods under fixed user IDs that
  OpenShift's default security policy (the `restricted-v2` SCC) rejects, so the install could never finish there.
  GitOps deployments work with your existing Git server once it is registered under Git Repositories.

### GitOps deployments

- Every change of a GitOps release now lands on the base branch that Flux reconciles (main unless you choose another
  one). A direct commit is pushed to that branch; the "Working branch" field is only offered for pull requests, and a
  direct commit naming another branch is refused.
- In pull request mode, each change is added to the release's pull request while it is open. Once that pull request is
  merged or closed, the next change opens a new pull request into the base branch from a new branch. A change that no
  longer goes through the release's open pull request (a switch to direct commit, another base branch or another
  working branch) closes that pull request first, so merging it later cannot bring back what it proposed. Pull requests are
  opened on GitHub (github.com and GitHub Enterprise), GitLab (including a custom port or path) and Gitea; any other
  Git server is refused with a clear error, and pull request mode needs the access token of the Git server.
- Uninstalling a release deployed in pull request mode opens a pull request that removes it. The release keeps running,
  shown as "Removal pending", until that pull request is merged; uninstall it again then to finish removing it from the
  cluster and KDPS.
- Releases deployed in direct commit mode by an earlier version with an empty working branch were committed to a branch
  of their own, which Flux never read. Their next deploy commits them to main.
- A direct commit is refused when the Git server does not have the base branch (for example a repository whose branch
  is master, deployed to with the default main), instead of creating a branch Flux does not read. A pull request whose
  working branch is the base branch itself is refused too: the change would reach that branch without review.
- Uninstalling a release whose pull request is still open closes that pull request, so merging it later cannot bring
  the release back. If its removal pull request is closed without being merged, the release is no longer shown as
  "Removal pending".
- An uninstall is refused, and the release kept, when KDPS cannot tell where Flux reads it: its base branch was deleted
  on the Git server, or it was deployed in pull request mode by an earlier version, which did not record its base
  branch, and main does not hold it. Deploy the release once more, naming its base branch, then uninstall it.
- Each release now reads its chart from a chart source of its own, in its own namespace (`<release>-<repository>`),
  committed in the release's directory. Two releases from the same chart repository no longer commit the same object
  twice, which stopped a Flux Kustomization covering both from applying anything; no release refers to another
  namespace, so Flux setups that refuse references across namespaces, or that apply each tenant's changes with an
  account limited to its own namespaces, accept them. A platform that keeps its chart sources in one namespace sets the
  view property "GitOps Chart Source Namespace": each chart repository then has one source there, committed once
  under `<path prefix>/_shared/sources/`. The copies of those sources that releases deployed by an earlier version hold
  in their directories are removed by the same commit, so setting the property needs no redeploy of those releases
  first.
- Dependencies that several releases share (KEDA, kube-prometheus-stack, the keytab webhook) are committed under
  `<path prefix>/_shared/` instead of the directory of the release that first needed them, and marked so Flux never
  prunes them. Uninstalling a release no longer removes them for the other releases, and no longer deletes the chart
  source other releases read from the cluster. The next deploy or uninstall of a release deployed by an earlier version
  moves its dependencies there, once it has marked each one on the cluster so that Flux does not prune it; one it
  cannot mark (for example a dependency in a namespace its Kubernetes account may not change) stays in the release's
  directory, marked in Git, and the next deploy or uninstall tries again.
- Upgrade step for platforms that apply KDPS releases with one Flux Kustomization per release or per namespace,
  instead of one over the whole path prefix: before upgrading, add a Flux Kustomization over
  `<path prefix>/_shared` with `prune: false`. Without it, the dependencies of new deploys are never installed, the
  releases that need them wait for them, and the chart sources of a GitOps Chart Source Namespace are never created.
- Chart repositories that need credentials or use a private CA work in GitOps mode. KDPS writes the credentials
  (a registry login for OCI, a user name and password for HTTP) and the CA of the KDPS truststores the repository
  names to Secrets next to each chart source through the Kubernetes API, never to Git. A repository can instead name
  existing Secrets (for example created by SOPS, Sealed Secrets or External Secrets). A deploy whose repository needs
  credentials KDPS does not have is refused with a clear message instead of committing a source Flux cannot read.
- GitOps over SSH checks the Git server's host key. KDPS only connects to a server presenting the key given in the new
  "Git server host key (known_hosts line)" field (the deploy forms, and the Git repositories page for a saved SSH
  repository), as printed by `ssh-keyscan [-p port] host` or read from the server's `/etc/ssh/ssh_host_ed25519_key.pub`.
  Without one, the deploy is refused and its message shows the key the server presented and its SHA-256 fingerprint:
  check it with the Git server's administrator, then paste the line the message gives. A server presenting another key
  is refused, for the deploy and for the uninstall. The key is recorded for the release once a deploy has reached Git,
  so later deploys and the uninstall need nothing more. Trusting the key presented on the first deploy, without
  checking it, is offered as an explicit option; the key is then recorded and required from then on.
- Upgrade step for releases already deployed over SSH: deploy them once with the Git server's host key, or their next
  deploy is refused with the key to check, and their uninstall is refused until such a deploy has recorded the key.
- SSH deploy keys as `ssh-keygen` writes them now work: Ed25519 (recommended), ECDSA and RSA keys in OpenSSH format
  (`-----BEGIN OPENSSH PRIVATE KEY-----`) or PEM format, signed with SHA-2 as current OpenSSH servers require. The key
  must have no passphrase. KDPS no longer reads anything from the SSH settings of the user Ambari runs as (its keys,
  known hosts or `~/.ssh/config`). HTTPS with an access token remains the simplest transport when the Git server's
  certificate is in a KDPS truststore.

### Authentication

- The service wizard warns when Trino, Superset or OpenMetadata would be published through an Ingress (a Route on
  OpenShift) without a security profile that requires sign-in, and deploys only once you confirm it. Without one,
  Trino accepts any user name a client sends.
- With OIDC, Trino accepts only the JWTs issued for its own OIDC client (their audience must be the client id), not
  every token of the realm. KDPS adds that audience to the tokens of the clients it registers: Trino's own, and
  Superset's when Superset connects to a Trino deployed by KDPS. A Trino deploy also adds its audience to the tokens of
  the Superset releases already connecting to it in OIDC mode, before Trino restarts, so they keep working without a
  redeploy; the deploy log names any it could not update, to redeploy (Upgrade/Config, no change needed). With a
  security profile whose OIDC client you register yourself (External source), Trino requires the profile's new JWT
  Audience, or its Client ID when that is blank: set it to the audience your identity provider issues (an application
  ID URI such as `api://...`, for instance) and make sure the applications calling Trino get tokens with it.

### Kerberos keytab webhook (WEBHOOK injection mode)

This concerns only sites that set **Settings > Kerberos injection mode** to WEBHOOK. The default, PRE_PROVISIONED,
does not use the keytab webhook and is not affected.

Earlier builds created the Ambari local account `webhook-user` with the **Ambari Administrator** role, wrote its password
into the Secret `ambari-mutating-webhooks/webhook-user`, and the webhook chart let the webhook read, change and delete
every Secret of the Kubernetes cluster. Anyone able to read that Secret could sign in to Ambari as an administrator.
Now:

- The webhook proves each keytab request with the client certificate KDPS issues to it; KDPS refuses a request it
  cannot verify.
- Each KDPS view instance gives its webhook an Ambari account of its own, `webhook-user-<instance>` (the instance name
  in lower case). It may only open that view instance (View User), and its password is derived from that
  certificate's key: no password is stored in Kubernetes. View instances that manage different Kubernetes clusters
  no longer share one account.
- Keytabs are issued with the Ambari credentials of the operator who deployed the release, only for releases KDPS
  deployed, only for principals named after the release's namespace, and only into the pod's own keytab Secret
  (`keytab-<pod>`). The webhook itself cannot have a keytab issued: the deploy's credentials are kept only while the
  deploy runs. A pod created after its deploy ended (a restart, a scale-out, a drained node) gets a copy of the keytab
  another Secret of the namespace holds for the same principal. When there is none, or the KDC no longer accepts it,
  the pod waits for its keytab Secret, and the keytab operation in KDPS (and the Ambari server log) fails naming the
  release to redeploy: redeploy it (Upgrade/Config, no change needed).
- The webhook's client certificate is valid for a year. KDPS renews it for the same key, so the account's password
  does not change, once fewer than 90 days are left: at a WEBHOOK-mode deploy, when the view starts, and daily
  after that, with no deploy needed. If a renewal fails, the Ambari server log says so, as an error once fewer than
  30 days are left. An expired certificate makes KDPS refuse the webhook's keytab requests, and the Kubernetes cluster
  then refuses every new Kerberized pod; redeploying a release in WEBHOOK mode renews it.
- The webhook chart (0.2.0, image 1.0.0.10) creates no ClusterRole or ClusterRoleBinding and mounts no ServiceAccount
  token: the webhook makes no Kubernetes API call.

After the upgrade:

1. In every KDPS view instance that uses the WEBHOOK mode, redeploy each Kerberized Trino or Superset release
   (Upgrade/Config, no change needed). The deploy installs the new webhook and sets up its own account. The first such
   deploy on the Ambari server also deletes the account `webhook-user` that earlier versions shared between all the view
   instances, an Ambari Administrator: from then on, the earlier webhook of an instance not yet redeployed refuses the
   pods of its releases when they restart or scale, until that instance redeploys. Its pods cannot restart anyway:
   KDPS deletes the password Secret they mount when it starts. In GitOps mode, check that the keytab webhook's
   HelmRelease under `_shared/` names chart 0.2.0 once the deploy is committed.
2. In Ambari, **Manage Ambari > Users**, check that `webhook-user` is gone (or, if you set
   `k8s.view.webhooks.keytab-webhook.backend.webhookUsername`, the account of that name), and that no
   `webhook-user-<instance>` account is an Ambari Administrator.
3. If you leave the WEBHOOK mode, first redeploy every release deployed in WEBHOOK mode: once the webhook's account is
   removed, the webhook refuses their pods when they restart or scale. Saving **Settings** with another injection
   mode removes the account of that view instance only, and only once the keytab webhook is no longer installed in the
   Kubernetes cluster: while it is, KDPS keeps the account and says so. Uninstall the webhook, then save the settings
   again. Removing an account takes an Ambari administrator: when a view administrator who is not one saves the
   settings, KDPS keeps the account and says so.
4. In each Kubernetes cluster where the WEBHOOK mode was used, check that the Secret
   `ambari-mutating-webhooks/webhook-user` is gone (KDPS deletes it when it starts, and at the next WEBHOOK deploy;
   with several view instances it may be left in some clusters: delete it by hand there), and that the webhook can no longer read Secrets:
   `kubectl auth can-i get secrets --as=system:serviceaccount:ambari-mutating-webhooks:keytab-webhook -A` must answer
   `no`.
5. The earlier password was readable by anyone allowed to read Secrets in `ambari-mutating-webhooks` (namespace
   administrators, backup and GitOps tools). Review the Ambari audit log for actions of `webhook-user` you do not
   expect.

### OIDC client secrets

- The **Rotate Secrets** button is removed from Admin > OIDC. It only ran OIDC provisioning again, which reads each
  client's current secret back from Keycloak, so no client secret was ever changed.
- To rotate the secret of an OIDC client, regenerate it in Keycloak (Clients > *client* > Credentials > Regenerate
  Secret), then:
  - for a cluster service, click **Run OIDC Provisioning** in Admin > OIDC, which stores the new secret in Ambari and
    in the service configuration, and restart the affected services. OIDC sign-in to the service fails from the
    regeneration until its restart. The button is shown only on a Kerberized cluster; on a cluster without Kerberos,
    send the same provisioning request through the REST API
    (`PUT /api/v1/clusters/<cluster>?configure_oidc_only=true` with `{"Clusters":{"security_type":"NONE"}}`), then
    restart the services marked Restart Required.
  - for a KDPS release, redeploy it (Upgrade/Config, no change needed), which writes the new secret into the release's
    Kubernetes Secret, and restart its pods if the redeploy did not. The release's **Re-register OIDC client** action
    does not replace the redeploy here.
- The full procedure is in the Ambari OIDC guide,
  [Rotate the client secret of a service's OIDC client](../../../../ambari-server/docs/security/sso/oidc.md#rotate-a-service-oidc-client-secret).

### Uninstalling a release takes back what KDPS registered for it outside Kubernetes

- KDPS now records with each release what it registers for it outside Kubernetes: its Keycloak clients, the Ranger
  access it grants (and the Ranger repository it creates), the Kerberos principals it has issued, the OpenMetadata
  federation user in Atlas, the OpenMetadata source of Ranger TagSync, the SQL Assistant view instance and the Vault
  copy of the client credentials.
- The uninstall confirmation lists what will be removed and what will be kept, and offers three choices:
  - **Remove access and credentials** (the default): the Keycloak client, the Ranger access KDPS granted, the Atlas
    federation user (and Atlas file
    authentication when KDPS turned it on), the OpenMetadata source of Ranger TagSync (Ranger TagSync is restarted),
    the view instance and the Vault copy. The Ranger repository of the release is kept with the policies operators
    added to it.
  - **Remove everything**: the same, and the Ranger repository of the release when KDPS created it (a repository
    Ranger already had, or one named by the operator, is never deleted). A repository of the cluster's own
    Ranger (managed context) is listed for deletion in Ranger, since KDPS does not hold that Ranger's administrator.
  - **Keep everything outside Kubernetes**, the single sign-on client included.
- The Ranger access of an account KDPS minted for its releases (such as the OpenMetadata ingestion account of a
  namespace) is taken back from every policy over the resources it was granted, since KDPS may have added it to an
  existing one. The access of an account the operator named (an LDAP or OIDC service user, an external keytab, the
  account of an operator's Secret) is taken back only from the policy KDPS created for the release, so the access the
  account holds elsewhere stays.
- Something another release still uses (the same Ranger account, principal, Atlas federation user, view instance or
  Vault path) is kept. While a release deployed with an earlier build may use the same minted account, only the policy
  KDPS created for the uninstalled release is changed, and the access left elsewhere is listed; such a release also
  keeps view instances and Vault copies in place. A view instance that existed before the release was deployed is
  never deleted. Kerberos
  principals are listed with the command that deletes them: KDPS cannot delete them. Atlas is not restarted: restart it
  from Ambari to drop the federation user. Whatever cannot be removed is shown after the uninstall with what to do.
- The Ranger account of a release's Atlas tag sync, which holds the Ranger administrator role, is disabled on every
  uninstall, whatever the choice.
- Releases deployed with an earlier build have no such record until they are deployed again: their uninstall says so.
  The Ranger revoke of a managed context needs this Ambari server version (`ownedPolicyOnly`); an older server refuses
  it, and the access is listed for removal by hand.

### Keycloak prerequisites

- KDPS registers the OIDC clients of its releases through the Keycloak admin API, with the settings of the cluster's
  OIDC service (`oidc-env`). Before deploying a release with OIDC sign-in:
  - Keycloak must be reachable at `oidc_admin_url` from the Ambari server (or the Ambari pod), with its TLS certificate
    trusted by Ambari's truststore, and the release's pods must reach the issuer URL and trust its CA.
  - The service realm (`oidc_realm`) must exist; KDPS creates clients in it, never the realm.
  - The Keycloak admin principal needs the `manage-clients` and `view-clients` roles on the service realm.
  - The admin credential must be in Ambari's persistent credential store (`ambari-server setup-security`): KDPS does
    not read the temporary store, so a credential kept only there is not found, and it is lost when Ambari restarts.
    A confidential admin client with a service account (`oidc_admin_client_secret`) is the alternative.
- The full list, the order to follow and the errors a missing prerequisite produces are in the Ambari OIDC guide,
  [Keycloak prerequisites](../../../../ambari-server/docs/security/sso/oidc.md#keycloak-prerequisites).

### Single sign-on for cluster services

- `ambari-server setup-sso-oidc` no longer asks whether Ambari should manage OIDC single sign-on for cluster services
  such as Ranger. That setting was never applied to any service. The `--sso-oidc-manage-services` and
  `--sso-oidc-enabled-services` options are still accepted but only print a warning, and the stored values are cleared.
- Single sign-on for Ranger Admin goes through Knox SSO (`ambari-server setup-sso` with `--sso-manage-services` and
  `--sso-enabled-services`), which can federate to Keycloak.
