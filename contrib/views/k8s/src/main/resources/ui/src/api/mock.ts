// ui/src/api/mock.ts
import { UserPermissions, UserRole, HelmRelease, ClusterNode, ClusterStats, ClusterEvent, ComponentStatus } from '../types';
// ... (getMockPermissions, getMockHelmReleases, getMockNodes, getMockClusterStats restent les mêmes) ...
import type { HelmRepo } from '../types';
export const getMockPermissions = (role: UserRole): UserPermissions => {
  switch (role) {
    case 'ADMIN':
      return { role, canConfigure: true, canWrite: true };
    case 'OPERATOR':
      return { role, canConfigure: false, canWrite: true };
    case 'VIEWER':
    default:
      return { role, canConfigure: false, canWrite: false };
  }
};

export const getMockHelmReleases = (): HelmRelease[] => ([
  { id: 'trino-prod', name: 'trino-prod', namespace: 'data-lake', chart: 'trino', version: '0.9.0', status: 'deployed' },
  { id: 'prometheus-stack', name: 'prometheus-stack', namespace: 'monitoring', chart: 'kube-prometheus-stack', version: '45.2.1', status: 'deployed' },
  { id: 'cert-manager', name: 'cert-manager', namespace: 'kube-system', chart: 'cert-manager', version: 'v1.9.1', status: 'pending_upgrade' },
  { id: 'old-service', name: 'old-service', namespace: 'default', chart: 'legacy-app', version: '1.0.0', status: 'failed' },
  { id: 'grafana', name: 'grafana', namespace: 'monitoring', chart: 'grafana', version: '6.58.4', status: 'uninstalling' },
  { id: 'argo-cd', name: 'argo-cd', namespace: 'cicd', chart: 'argo-cd', version: '5.51.6', status: 'deployed' },
  { id: 'elastic-operator', name: 'elastic-operator', namespace: 'logging', chart: 'eck-operator', version: '2.11.0', status: 'deployed' },
  { id: 'kafka-cluster', name: 'kafka-cluster', namespace: 'messaging', chart: 'strimzi-kafka-operator', version: '0.38.0', status: 'failed' },
]);

export const getMockNodes = (): ClusterNode[] => ([
    { id: 'worker-01', name: 'worker-01.example.com', status: 'Ready', roles: ['worker'], cpuUsage: 0.7, memoryUsage: 0.65 },
    { id: 'worker-02', name: 'worker-02.example.com', status: 'Ready', roles: ['worker'], cpuUsage: 0.5, memoryUsage: 0.40 },
    { id: 'worker-03', name: 'worker-03.example.com', status: 'Ready', roles: ['worker', 'storage'], cpuUsage: 0.8, memoryUsage: 0.75 },
    { id: 'master-01', name: 'master-01.example.com', status: 'Ready', roles: ['master', 'control-plane'], cpuUsage: 0.2, memoryUsage: 0.30 },
    { id: 'master-02', name: 'master-02.example.com', status: 'NotReady', roles: ['master', 'control-plane'], cpuUsage: 0.0, memoryUsage: 0.0 },
    { id: 'gpu-node-1', name: 'gpu-node-1.k8s.local', status: 'SchedulingDisabled', roles: ['worker', 'gpu'], cpuUsage: 0.9, memoryUsage: 0.8 },
]);

export const getMockClusterStats = (): ClusterStats => ({
    cpu: { used: 9.8, total: 32 },
    memory: { used: 45.2, total: 128 },
    pods: { used: 157, total: 400 },
    nodes: { used: 1, total: 2 },
    helm: { deployed: 4, pending: 1, failed: 2, total: 8 },
});


// NOUVELLES DONNÉES DE SIMULATION
export const getMockComponentStatuses = (): ComponentStatus[] => ([
    { name: 'API Server', status: 'Healthy' },
    { name: 'Controller Manager', status: 'Healthy' },
    { name: 'Scheduler', status: 'Healthy' },
    { name: 'etcd-0', status: 'Healthy' },
    { name: 'etcd-1', status: 'Unhealthy' },
]);

export const getMockClusterEvents = (): ClusterEvent[] => ([
    { id: 'evt1', type: 'Alert', message: 'Pod "trino-worker-3" in CrashLoopBackOff', timestamp: 'il y a 2 min' },
    { id: 'evt2', type: 'Warning', message: 'Node "worker-02" memory usage is over 85%', timestamp: 'il y a 15 min' },
    { id: 'evt3', type: 'Info', message: 'Helm release "grafana" was upgraded to 6.58.5', timestamp: 'il y a 1h' },
    { id: 'evt4', type: 'Alert', message: 'PersistentVolume "pvc-data-trino-0" is unbound', timestamp: 'il y a 3h' },
]);

export const getAvailableServices = (): string[] => (
    ['trino', 'prometheus', 'grafana', 'cert-manager', 'argo-cd']
);

export async function getMockHelmRepos(init?: { signal?: AbortSignal; delayMs?: number }): Promise<HelmRepo[]> {
  const { signal, delayMs = 400 } = init ?? {};

  // simple sleep with abort support
  const sleep = (ms: number) =>
    new Promise<void>((resolve, reject) => {
      const t = setTimeout(resolve, ms);
      if (signal) {
        const onAbort = () => {
          clearTimeout(t);
          reject(new DOMException('Aborted', 'AbortError'));
        };
        signal.addEventListener('abort', onAbort, { once: true });
      }
    });

  await sleep(delayMs);

  return [
    {
      id: 'bitnami',
      type: 'HTTP',
      name: 'Bitnami',
      url: 'https://charts.bitnami.com/bitnami',
      authMode: 'anonymous',
      authInvalid: false,
      createdAt: '2025-08-01T12:00:00Z',
      updatedAt: '2025-08-01T12:00:00Z',
    },
    {
      id: 'my-http',
      type: 'HTTP',
      name: 'Private HTTP',
      url: 'https://helm.example.com/charts',
      authMode: 'basic',
      username: 'ci',
      authInvalid: false,
      createdAt: '2025-08-02T08:00:00Z',
      updatedAt: '2025-08-02T08:10:00Z',
    },
    {
      id: 'ghcr',
      type: 'OCI',
      name: 'GitHub Container Registry',
      url: 'ghcr.io/stefanprodan/charts',
      authMode: 'anonymous',
      authInvalid: false,
      createdAt: '2025-07-30T09:00:00Z',
      updatedAt: '2025-07-30T09:00:00Z',
    },
    {
      id: 'ecr',
      type: 'OCI',
      name: 'AWS ECR',
      url: '123456789012.dkr.ecr.eu-west-1.amazonaws.com/helm',
      authMode: 'token',
      username: 'AWS',
      authInvalid: true, // simulate an expired/invalid login
      createdAt: '2025-08-02T10:00:00Z',
      updatedAt: '2025-08-02T10:05:00Z',
    },
  ];
}

export const getChartsJSON = (): any => (
    {
      "trino": {
        "label": "Trino",
        "chart": "trinodb/trino",
        "form": [
          { "name": "releaseName", "label": "Nom du Release", "type": "string", "required": true },
          { "name": "namespace", "label": "Namespace Kubernetes", "type": "string", "required": true, "defaultValue": "default" },
          {
            "name": "networkAccess",
            "label": "Accès Réseau (Exposition du service Trino)",
            "type": "group",
            "fields": [
              {
                "name": "service.enabled",
                "label": "Activer l'accès interne (ClusterIP)",
                "type": "boolean",
                "defaultValue": true,
                "disabled": true,
                "help": "Un service interne est toujours créé pour la communication dans le cluster."
              },
              {
                "name": "nodePort.enabled",
                "label": "Activer l'accès externe (NodePort)",
                "type": "boolean",
                "defaultValue": false,
                "help": "Crée un second service de type NodePort pour l'accès depuis l'extérieur du cluster (ex: Hue)."
              },
              {
                "name": "nodePort.port",
                "label": "Port du Nœud (Optionnel)",
                "type": "number",
                "help": "Laisser vide pour un port aléatoire (recommandé). Doit être entre 30000-32767.",
                "condition": { "field": "nodePort.enabled", "value": true }
              }
            ]
          },
          {
            "name": "coordinatorResources",
            "label": "Ressources du Coordinateur",
            "type": "group",
            "fields": [
              { "name": "coordinator.resources.requests.cpu", "label": "CPU Demandé", "type": "string", "defaultValue": "1" },
              { "name": "coordinator.resources.requests.memory", "label": "Mémoire Demandée", "type": "string", "defaultValue": "4Gi" },
              { "name": "coordinator.resources.limits.cpu", "label": "CPU Limite", "type": "string", "defaultValue": "2" },
              { "name": "coordinator.resources.limits.memory", "label": "Mémoire Limite", "type": "string", "defaultValue": "8Gi" }
            ]
          },
          {
            "name": "workerResources",
            "label": "Ressources des Workers",
            "type": "group",
            "fields": [
              { "name": "worker.replicas", "label": "Nombre de Workers", "type": "number", "defaultValue": 3 },
              { "name": "worker.resources.requests.cpu", "label": "CPU Demandé / Worker", "type": "string", "defaultValue": "1" },
              { "name": "worker.resources.requests.memory", "label": "Mémoire Demandée / Worker", "type": "string", "defaultValue": "4Gi" },
              { "name": "worker.resources.limits.cpu", "label": "CPU Limite / Worker", "type": "string", "defaultValue": "2" },
              { "name": "worker.resources.limits.memory", "label": "Mémoire Limite / Worker", "type": "string", "defaultValue": "8Gi" }
            ]
          },
          {
            "name": "hadoopIntegration",
            "label": "Intégration Hadoop",
            "type": "group",
            "fields": [
              {
                "name": "additionalCatalogs.hive.config.hive\\.metastore\\.uri",
                "label": "Service Hive Metastore",
                "type": "service-select",
                "serviceType": "HIVE_METASTORE",
                "help": "Sélectionnez un service Hive Metastore existant pour créer un catalogue."
              }
            ]
          }
        ]
      },
      "prometheus": {
        "label": "Prometheus",
        "chart": "prometheus-community/prometheus",
        "form": [
          { "name": "releaseName", "label": "Nom du Release", "type": "string", "required": true },
          { "name": "namespace", "label": "Namespace Kubernetes", "type": "string", "required": true, "defaultValue": "monitoring" },
          { "name": "alertmanager.enabled", "label": "Activer Alertmanager", "type": "boolean", "defaultValue": true },
          { "name": "server.retention", "label": "Rétention des données", "type": "string", "defaultValue": "14d", "help": "Exemples : 14d, 2w, 1y" }
        ]
      }
    })