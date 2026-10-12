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
  - When the change waits for a merge (pull request mode, or a working branch Flux does not reconcile), the operation
    ends SUCCEEDED after the commit. Its result, returned as `result` by `GET /commands/{id}` and the operations list,
    says so (`waitingForMerge`, `message`, `pullRequestUrl`, `branch`, `notRun`), and the remaining steps are marked as
    not run. The UI shows that message, and the pull request link, instead of reporting the deploy completed.
    Redeploying the release once the change is merged runs them: the commit then finds nothing to change. Only
    operations return a result: those of their steps stay internal.
- Every deploy, in either mode and from the UI or the API, is refused (400) before anything is prepared when a name
  would not be accepted by Helm or Kubernetes: the release name (Helm's rule, at most 53 characters), the namespace,
  and the name and namespace of each dependency (DNS-1123 labels). A GitOps deploy also needs chart repository ids
  that are DNS-1123 labels, a chart name (optionally after `<repo>/`) and a chart version made of version or range
  characters only (no line break, `#` or `:`); `GITOPS_COMMIT` checks them again. A direct deploy keeps every chart
  reference Helm resolves (an `oci://` reference, with or without a tag, or the URL of a chart archive) and refuses
  only a chart or version with a line break, another control character, or a space within the chart. The manifests
  are built as maps and written by a YAML serializer, so no value can add a key or another document; the release's
  directory is `<path prefix>/<namespace>/<release>` and a dependency's files stay in its `dependencies` directory.
  Each release keeps a directory of its own: a GitOps deploy whose directory would be inside, or would hold, the
  directory of another release of the same repository (as the branch holds it, or as KDPS recorded it for a release
  still in an open pull request), or whose `helmrelease.yaml` already holds another HelmRelease, is refused (400)
  before anything is prepared or committed. An uninstall deletes only the files the release's deploys write into its
  own directory (`helmrelease.yaml`, `helmrepository.yaml` and the `-helmrelease.yaml` and `-helmrepository.yaml` files
  of its `dependencies` directory): any other file, and the directory of a release below it, stays. A blank path, the
  bare path prefix, another release's directory, or a `helmrelease.yaml` holding another HelmRelease, is refused (400)
  and nothing is removed, from Git or from the cluster. The release keeps its Polaris credential and its record, so
  the uninstall can be retried once the record is corrected.
- Flux applies what is committed with its own rights, not with those of the account KDPS uses: by default Flux's
  controllers are cluster administrators. For production, lock Flux down to tenants (`--default-service-account`, or
  a `serviceAccountName` on each tenant's Kustomization) and use pull request mode, so a change is reviewed before
  Flux applies it.
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
