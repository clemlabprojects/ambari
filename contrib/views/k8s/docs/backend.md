<!---
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Backend Overview

## Key Services
- CommandService
  - submitDeploy(): builds root command plan, stores in DataStore, schedules runNextStep().
  - runNextStep(): walks child commands, executes steps, updates status/percentage.
  - listCommands(limit, offset): returns root commands (those with childListJson).
  - listChildren(id): returns direct children for tree view.
  - cancel(id): marks status CANCELED when not terminal.
  - computePercentage(): success ratio of child steps.
- KubernetesService
  - Kubernetes client setup; caches service lookups and cluster stats (Caffeine).
  - ensureReleaseLabelsOnIngresses(): patches ingresses with instance label.
  - ensureImagePullSecretFromRepo(), createNamespace(), mounts handling.
- HelmResource
  - /helm/releases (cached 10s), deploy/upgrade/rollback/uninstall endpoints.
- ReleaseMetadataService
  - Records install/upgrade metadata (values hash, endpoints).
  - discoverExternalClusterEndpoints(): ingress/LB discovery with label fallback.
- StackDefinitionService
  - Loads KDPS `service.json` + configs; caches per view instance.

## API Endpoints
- POST `/commands/helm/deploy` (payload: chart, releaseName, namespace, values, mounts, dependencies, ranger, requiredConfigMaps, dynamicValues, endpoints, secretName, stackConfigOverrides, serviceKey, version, repoId)
- GET `/commands?limit=&offset=` (roots)
- GET `/commands/{id}` (status)
- GET `/commands/{id}/children` (direct children)
- POST `/commands/{id}/cancel`
- GET `/stack/services`, `/stack/services/{name}`, `/stack/services/{name}/configurations`
- GET `/helm/releases`

## DTOs
- HelmDeployRequest: tolerant setters for mounts (list->map), requiredConfigMaps, ranger, endpoints, etc.
- CommandStatus: includes hasChildren flag for tree navigation.

## Logging/Threading
- CommandService uses scheduled timer + worker pool; DataStore access wrapped; no shared mutable state outside injected components.
- Logs avoid secrets; include IDs (commandId/release/namespace) on operations. A planned deploy is logged at INFO as a
  summary (operation id, release, namespace, chart, version, mode, parameter names); its parameters go to DEBUG only,
  through `HelmSetValues.redactJson`, which hides the wizard's whole form (`formValues`), PEM private keys and values
  under secret keys (password, secret, token, secretKey, accessKey, privateKey, sshKey, apiKey, credentials, keytab,
  `key`).

## Operation files (secrets at rest)
- The parameters of an operation and of each step are kept in `<working dir>/params/<command>-params.json`, and the
  chart values of a deploy or upgrade in `<working dir>/cache/values-*.json`. Both are encrypted with Ambari's master
  key (the `encrypted-v1` envelope also used for the Ambari session of an operation), files `0600` in directories
  `0700` whatever the umask. Without a persisted master key Ambari's default passphrase is used, so the permissions
  are then the only protection.
- The wizard's form is kept without its secrets that no step reads: the `password`, `content` and `sensitive` fields
  of the service definition (`SensitiveFields`), and any PEM private key. A GitOps deploy keeps the password fields,
  which its commit looks for to keep their copies out of Git. Secrets a step needs that live in an encrypted store are
  read there when the step runs: the Ranger admin password of an external platform context, the client secret of an
  external OIDC provider (security profile).
- The files of an operation whose steps all succeeded are deleted when it ends. Those of a failed or canceled
  operation are kept for Resume, until it ended more than 30 days ago or 50 more recent operations ended that way
  (Ambari properties `k8s.view.operations.retention.days` and `k8s.view.operations.retention.count`); it can then no
  longer be resumed. When the view starts, these rules are applied to every operation, files no operation refers to
  are deleted after an hour, and, once, the files an earlier version wrote in clear are encrypted and made owner-only.
- On a system that ran an earlier version, the logs and these files may hold the Superset `SECRET_KEY`, uploaded
  ingress TLS keys, static S3 keys and the security profile's passwords: rotate them, and purge old
  `ambari-server.log` files and the log indices they were forwarded to.

## Stored credentials and what the API returns
- Kept encrypted with Ambari's master key in the instance settings, never in an entity row: the bind passwords and
  OIDC client secret of a security profile (`secprofile.<profile>.<field>`, the profile row stores them as `null`), a
  chart repository's registry password or token (`helm.repo.secret.<id>`; `POST /helm/repos` takes it in its body,
  `HelmRepoRequest`, and `HelmRepoEntity` has no property for it), the Thanos object store keys
  (`monitoring.thanos.accessKey` / `secretKey`, apart from `view.settings.json`), and the client secret Keycloak gives
  a client a deploy registers (`oidc.client.secret.<registration step>`, deleted once `OIDC_CREATE_SECRET` has written
  it, or when the operation's files are purged; never in the step result).
- Never returned: `GET /contexts/{id}/resolved` sends `ResolvedContext.toClientView()`, which leaves out the Ranger
  admin password, the Atlas federation password and the Keycloak admin client secret (`secretFieldsSet` says which are
  set). `GET /configurations/security` and `GET /configurations/settings` send `********` and a `<field>Set` flag for
  each stored secret; posting the mask or a blank keeps it. `GET /helm/{namespace}/{release}/values` serves only
  releases KDPS deployed and shows their secrets (service definition password/content/sensitive fields and the values
  they are seeded from, values under secret keys, PEM private keys) as `********`; a redeploy that sends it back gets
  the deployed value put back on the server (in a list, by the entry's `name`, or by position while no entry was added
  or removed), and is refused when there is none or it can no longer be matched. Bare `key` values (tolerations,
  affinity terms, `secretKeyRef`) and keys naming a Secret (`existingSecret`, `tlsSecret`...) are not hidden.
- Rows earlier versions wrote are cleaned: security profile rows on their first read, the step results holding a
  Keycloak client secret and the repositories' foreign secret references when the view starts (rows are updated in
  place, never removed and stored again); the previous view version's repository rows, step results and security profile
  rows by the data migration, which also encrypts the Thanos keys of the shared view settings at once and removes them
  from the copies of `view.settings.json` each user kept in their instance data (new and previous version). This version's repository tables are created without the column earlier versions kept the registry
  secret in.

## Caches
- HelmResource releases: in-memory 10s TTL.
- KubernetesService: service lookups (30s), cluster stats (10s).
- StackDefinitionService caches service defs/configs.

## GitOps (Flux) deploys
- A deploy with `deploymentMode: FLUX_GITOPS` runs the same command plan as a direct Helm deploy: keytab and its
  Secret, SSO client and its Secret, Ranger plugin files and repository, Hadoop configuration, TLS keystore, ingress
  certificate, OpenShift SCC check, stack configurations, Polaris, Airflow DAG. Only the end of the plan differs:
  - `GITOPS_COMMIT` replaces the Helm dry run and install. It writes the HelmRelease to Git with every value the Helm
    install would receive (the plan's overrides and resolved dynamic values included), and the dependencies as
    HelmReleases of their own that Flux installs first (`dependsOn`). Each dependency gets what its Helm install step
    would pass: the repository's image registry and its pull Secret (created in the dependency's namespace), its
    fullnameOverride, its resolved dynamic values, and for the keytab webhook the CA bundle and backend URL; in the
    WEBHOOK injection mode the webhook's namespace and certificates are prepared when the deploy is planned, as for a
    direct deploy. Every HelmRelease gets `timeout: 15m`, the 900 seconds Helm gets in a direct deploy. Passwords and
    secrets go to the `<release>-kdps-values` Secret, read through `valuesFrom`. Nothing is committed when a
    preparation step fails or a dependency cannot be prepared, and a step run again commits nothing when Git already
    holds the release.
  - `FLUX_WAIT_READY` waits until the HelmRelease on the cluster carries the committed `ambari.clemlab.com/spec-digest`
    annotation and Flux reports it Ready (default 20 minutes, `-Dk8s.view.gitops.readyTimeoutSeconds`). While it
    waits, the log shows what Flux reports on the Ready condition (install under way, chart not available, install
    failed and retried). A release Flux gives up on (Stalled) fails the step with the cause from the Ready condition
    and Flux's reason for giving up. Flux does not try such a release again on its own: a redeploy that commits
    nothing new, or a resumed wait, asks it to (`reconcile.fluxcd.io/resetAt`, as `flux reconcile helmrelease --reset`)
    and waits for the outcome of that new attempt. The same holds for a HelmRelease the release depends on: while
    Flux holds the release back for one (`DependencyNotReady`, never Stalled), the wait reads the HelmReleases its
    `dependsOn` names, and one Flux gave up on fails the step at once with that dependency's cause, or is asked to try
    again by a redeploy or resume. The post-deploy steps (Ranger grants, TagSync, OpenMetadata and Atlas registration,
    view provisioning) run after it.
  - When the change waits for a merge (pull request mode), the operation ends SUCCEEDED after the commit. Its result, returned as `result` by `GET /commands/{id}` and the operations list,
    says so (`waitingForMerge`, `message`, `pullRequestUrl`, `branch`, `notRun`), and the remaining steps are marked as
    not run. The UI shows that message, and the pull request link, instead of reporting the deploy completed.
    Redeploying the release once the change is merged runs them: the commit then finds nothing to change. Only
    operations return a result: those of their steps stay internal.
- Every GitOps change lands on the base branch, the branch Flux reconciles (`git.baseBranch`, recorded with the
  release; a redeploy that names none keeps the recorded one, else `main`). A direct commit (`DIRECT_COMMIT`) is pushed
  to it; a deploy naming another working branch is refused (400), and so is one whose base branch the server does not
  have, unless the repository has no branch at all yet. A pull request (`PR_MODE`, which needs the Git
  server's access token) goes on the branch of the release's previous pull request while that one is open, otherwise
  on a new branch (`flux-<uuid>`, or the working branch the deploy names, which cannot be the base branch) started from
  the base branch as the server holds it, and a pull request into the base branch is opened; the branch, the pull
  request number and its URL are recorded. The previous pull request is joined only when it was opened into the same
  base branch. A deploy that no longer goes through the release's open pull request (direct commit, another base
  branch, another working branch) closes it before anything is committed, and is refused when it cannot. A base branch
  only assumed (`main` for a pull request release recorded before base branches were) is never recorded by a redeploy
  that names none, and the Releases page flags it (`gitBaseBranchAssumed`) and does not send it back. A deploy with nothing new to commit still records its commit mode and base
  branch, which the next deploys and the uninstall follow. Pull requests are opened through the API of the Git server: github.com through api.github.com, another
  GitHub server through `/api/v3`, GitLab through `/api/v4` and Gitea through `/api/v1`, with the port and context path
  of the repository URL; a server whose name does not tell is asked which one it is. Any other Git server (Bitbucket,
  Azure DevOps) is refused before anything is pushed, and a pull request the server does not open fails the deploy
  with the server's reason.
- Layout under the path prefix (default `clusters/default`), meant for one Flux Kustomization over the prefix:
  - `<namespace>/<release>/helmrelease.yaml` and `helmrepository.yaml`: the release, and the chart source it reads,
    `<release>-<repository>` in the release's namespace. The HelmRelease's `sourceRef` names no namespace.
  - `_shared/<namespace>/<dependency>-helmrelease.yaml` and `<dependency>-helmrepository.yaml`: the dependencies the
    releases share (KEDA, kube-prometheus-stack, the keytab webhook), each with its chart source in its own namespace,
    annotated `kustomize.toolkit.fluxcd.io/prune: disabled`. A deploy keeps one Git already holds there (adding it to
    `dependsOn`) even when it is on the cluster; otherwise the reuse rules apply, and in GitOps mode a keytab webhook
    already installed as a Helm release is reused too. No uninstall changes `_shared`.
  - `_shared/sources/<namespace>/<repository>-helmrepository.yaml`: with the view property
    `k8s.view.gitops.source.namespace` set, the one chart source of each repository in that namespace, read by every
    release (`sourceRef.namespace` set), annotated the same way. Flux must then allow references across namespaces.
    Earlier versions committed a copy of the same object in each release's directory (`helmrepository.yaml`, and
    `dependencies/<source>-helmrepository.yaml`): every deploy, and every uninstall that moves dependencies there,
    removes the copies of a source `sources/` holds, under the whole path prefix, so kustomize never finds it twice. Each copy is annotated on the cluster first,
    best effort, as for a moved dependency.
  - A release deployed by an earlier version kept its dependencies in `<namespace>/<release>/dependencies/`, with its
    chart sources in flux-system. Its next deploy or uninstall moves each dependency it does not write again to
    `_shared` as it was, with the flux-system source it reads (to `_shared/sources/flux-system/`); one never published
    on the base branch is removed instead. Before a published dependency leaves the release's directory, its
    HelmRelease on the cluster is annotated `kustomize.toolkit.fluxcd.io/prune: disabled` through the Kubernetes API
    (field manager `kdps`, which Flux's server-side apply leaves alone), and so is the chart source it keeps reading,
    best effort: Flux reads that annotation on the cluster, so a Kustomization over the release's directory alone does
    not uninstall the operator when the file moves out of it. A dependency KDPS cannot annotate (the account may not
    change its namespace) stays where it is, annotated in Git, with the chart source it reads next to it
    (`dependencies/<source>-helmrepository.yaml`, a copy when it was the release's own), and the next deploy or
    uninstall tries again; a deploy writes such a dependency there too.
  - Per-release or per-namespace Kustomizations need a Kustomization of their own for `_shared` (`prune: false`),
    added before upgrading: without it, a dependency written there is never applied and the HelmRelease that
    `dependsOn` it never installs. Hand changes to the committed manifests are overwritten by the next deploy: use the
    Kustomization's `spec.patches` instead.
- A chart repository that needs credentials (`authMode` basic or token) is read with a Secret next to each chart
  source: `<source>-repo-auth`, a `kubernetes.io/dockerconfigjson` registry login for OCI or `username`/`password` for
  HTTP, written by the view from the stored user name and password through the Kubernetes API before the commit
  (`spec.secretRef`). The repository can name an existing Secret instead (`fluxSecretName`, for SOPS, Sealed Secrets
  or External Secrets), which must exist in each source namespace. Without either, the deploy is refused before
  anything is prepared. A private CA comes from the KDPS truststores the repository names (`caTruststores`), written to
  `<source>-repo-ca` (`ca.crt`, `spec.certSecretRef`), or from an existing Secret (`fluxCertSecretName`). None of
  these Secrets is ever written to Git; the view deletes those it wrote for a release's own source when the release is
  removed from Git.
- An uninstall of a GitOps release (`DELETE /helm/release/{ns}/{name}`) removes it the way it was deployed. By direct
  commit, the removal is pushed to the base branch, then the HelmRelease is deleted from the cluster, with the
  release's Git credential and record; its chart source goes with its directory, which Flux prunes, and nothing the
  releases share is removed. Through
  a pull request, the removal is pushed to a new branch (`flux-remove-<uuid>`) from the base branch and a pull request
  into the base branch is opened: the answer is 202 with `pendingDelete`, `pullRequestUrl` and a `message`, the release
  keeps running and is shown as `PENDING_DELETE`, and nothing is removed from the cluster. Uninstalling it again while
  the pull request is open changes nothing, after it was closed unmerged proposes the removal again, and once the base
  branch no longer holds the release finishes the uninstall. A deploy of the release is refused while its removal pull
  request is open; once that pull request is closed unmerged, the release is no longer shown as `PENDING_DELETE`, and
  a deploy, even with nothing new, ends the wait for the removal. The release's own pull request, when still open, is
  closed first (without an access token, the release is removed from its branch instead), so merging it later cannot
  install the release again. A removal Git refuses keeps the release and its record (500), and so does an uninstall
  that cannot tell where Flux reads the release while its HelmRelease is still on the cluster: a base branch the
  server no longer has, or `main` assumed for a pull request release recorded before its base branch was and not
  holding it. Deploying the release once, naming its base branch, then lets it be uninstalled.
- Every deploy, in either mode and from the UI or the API, is refused (400) before anything is prepared when a name
  would not be accepted by Helm or Kubernetes: the release name (Helm's rule, at most 53 characters), the namespace,
  and the name and namespace of each dependency (DNS-1123 labels). A GitOps deploy also needs chart repository ids
  that are DNS-1123 labels, a chart name (optionally after `<repo>/`) and a chart version made of version or range
  characters only (no line break, `#` or `:`); `GITOPS_COMMIT` checks them again. A direct deploy keeps every chart
  reference Helm resolves (an `oci://` reference, with or without a tag, or the URL of a chart archive) and refuses
  only a chart or version with a line break, another control character, or a space within the chart. The manifests
  are built as maps and written by a YAML serializer, so no value can add a key or another document; the release's
  directory is `<path prefix>/<namespace>/<release>` and a dependency's files stay in
  `<path prefix>/_shared/<namespace>/`.
  Each release keeps a directory of its own: a GitOps deploy whose directory would be inside, or would hold, the
  directory of another release of the same repository (as the branch holds it, or as KDPS recorded it for a release
  still in an open pull request), or whose `helmrelease.yaml` already holds another HelmRelease, is refused (400)
  before anything is prepared or committed. An uninstall deletes only the files the release's deploys write into its
  own directory (`helmrelease.yaml`, `helmrepository.yaml`; the files of a `dependencies` directory an earlier version
  wrote are moved to `_shared`): any other file, and the directory of a release below it, stays. A blank path, the
  bare path prefix, another release's directory, or a `helmrelease.yaml` holding another HelmRelease, is refused (400)
  and nothing is removed, from Git or from the cluster. The release keeps its Polaris credential and its record, so
  the uninstall can be retried once the record is corrected.
- Flux applies what is committed with its own rights, not with those of the account KDPS uses: by default Flux's
  controllers are cluster administrators. For production, lock Flux down to tenants (`--default-service-account`, or
  a `serviceAccountName` on each tenant's Kustomization) and use pull request mode, so a change is reviewed before
  Flux applies it. Tenant Kustomizations over the releases' directories also need one over `_shared`, run by an
  account allowed in the dependencies' namespaces (see the layout above).
- The Secrets the preparation creates (keytabs, SSO client, Ranger plugin, Hadoop configuration, certificates,
  truststore, values Secret) are created by the view in the release namespace and deliberately kept out of Git.
  Re-applying the Git repository alone does not recreate them: redeploy the release from the view.
- The GitOps deploy returns an operation id like a direct deploy, polled through `GET /commands/{id}`; an unknown id
  answers 404.
- The Git token and SSH key of a GitOps deploy are stored in Ambari's credential store when it is submitted, each under
  an alias of its own; the operation keeps only the aliases. A deploy whose credential the store cannot take is
  refused (400) rather than keeping the secret with the operation.
- A deploy may name a saved Git credential (`git.credentialAlias`) only when it is one the view saved (alias starting
  with `gitops-` or `git-`) or the one the release already uses; any other alias of Ambari's credential store is
  refused (400) and never read. `git.authTokenAlias` is set by the view only: a value sent with a deploy is discarded.

## Notable Behaviors
- Ranger: merged via ConfigResolutionService.mergeRangerOverrides() into params.
- Endpoints: computed from service.json endpoints + discovered ingress/LB endpoints.
- Mounts: normalized list->map in HelmDeployRequest; stored on child mount command.
- Background operations UI uses listCommands + listChildren for tree navigation.
