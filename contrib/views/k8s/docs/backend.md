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

## Notable Behaviors
- Ranger: merged via ConfigResolutionService.mergeRangerOverrides() into params.
- Endpoints: computed from service.json endpoints + discovered ingress/LB endpoints.
- Mounts: normalized list->map in HelmDeployRequest; stored on child mount command.
- Background operations UI uses listCommands + listChildren for tree navigation.
