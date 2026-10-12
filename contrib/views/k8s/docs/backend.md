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
- Logs avoid secrets; include IDs (commandId/release/namespace) on operations.

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
