// ui/src/types/index.ts
export type UserRole = "ADMIN" | "OPERATOR" | "VIEWER";

export interface UserPermissions {
  role: UserRole;
  canConfigure: boolean;
  canWrite: boolean;
}

export interface HelmRelease {
  id: string;
  name: string;
  namespace: string;
  chart: string;
  version: string;
  status: 'deployed' | 'pending_upgrade' | 'failed' | 'uninstalling';
}

export interface ClusterNode {
    id: string;
    name: string;
    status: 'Ready' | 'NotReady' | 'SchedulingDisabled';
    roles: string[];
    cpuUsage: number; // 0 to 1
    memoryUsage: number; // 0 to 1
}

export interface ClusterStats {
  cpu: { used: number; total: number };
  memory: { used: number; total: number };
  pods: { used: number; total: number };
  nodes: { ready: number; total: number };
  helm: { deployed: number; pending: number; failed: number; total: number };
}

// NOUVEAUX TYPES
export type ClusterEventType = 'Alert' | 'Info' | 'Warning';
export interface ClusterEvent {
    id: string;
    type: ClusterEventType;
    message: string;
    timestamp: string;
}

export type ComponentStatusType = 'Healthy' | 'Unhealthy';
export interface ComponentStatus {
    name: string;
    status: ComponentStatusType;
}
