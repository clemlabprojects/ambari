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
export interface HelmRelease {
  id: string;
  name: string;
  namespace: string;
  chart: string;
  version: string;
  appVersion?: string;
  status: 'deployed' | 'pending_upgrade' | 'failed' | 'uninstalling';
  updated?: string;

  // --- champs enrichis par le backend (BDD/annotations) ---
  managedByUi?: boolean;
  serviceKey?: string;   // ex: "trino", "prometheus"
  repoId?: string;
  chartRef?: string;
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
  nodes: { used: number; total: number };
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

export interface FormField {
  name: string;
  label: string;
  type: 'string' | 'number' | 'boolean';
  required?: boolean;
  defaultValue?: any;
  help?: string;
}
export interface ServiceDefinition {
  label: string;
  chart: string;
  form: FormField[];
}

export type ComponentStatusType = 'Healthy' | 'Unhealthy';
export interface ComponentStatus {
    name: string;
    status: ComponentStatusType;
}

export interface HelmRepo {
  id: string;
  name: string;
  type: "HTTP" | "OCI";
  url: string;
  authMode: "anonymous" | "basic" | "token";
  username?: string;
  authInvalid?: boolean;
}
