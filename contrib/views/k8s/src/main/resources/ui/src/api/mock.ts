// ui/src/api/mock.ts
import { UserPermissions, UserRole, HelmRelease, ClusterNode, ClusterStats, ClusterEvent, ComponentStatus } from '../types';

// ... (getMockPermissions, getMockHelmReleases, getMockNodes, getMockClusterStats restent les mêmes) ...

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
    nodes: { ready: 4, total: 6 },
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
