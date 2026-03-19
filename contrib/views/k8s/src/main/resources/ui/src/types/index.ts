// ui/src/types/index.ts
export type UserRole = "ADMIN" | "OPERATOR" | "VIEWER";

export interface UserPermissions {
  role: UserRole;
  canConfigure: boolean;
  canWrite: boolean;
}

export interface ReleaseEndpoint {
  id: string;
  label?: string;
  url?: string;
  kind?: 'service' | 'ingress' | 'route';
  protocol?: 'http' | 'https';
  internal?: boolean;
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
  securityProfile?: string;
  securityProfileStale?: boolean;
  vaultProfile?: string;
  vaultProfileStale?: boolean;
  restartRequired?: boolean;
  deploymentMode?: string; // DIRECT_HELM | FLUX_GITOPS
  gitCommitSha?: string;
  gitBranch?: string;
  gitPath?: string;
  gitRepoUrl?: string;
  gitPrUrl?: string;
  gitPrNumber?: string;
  gitPrState?: string;
  sourceStatus?: string;
  sourceMessage?: string;
  sourceName?: string;
  sourceNamespace?: string;
  reconcileState?: string;
  reconcileMessage?: string;
  observedGeneration?: string;
  desiredGeneration?: string;
  staleGeneration?: boolean;
  lastTransitionTime?: string;
  conditions?: Array<Record<string, string>>;
  sourceConditions?: Array<Record<string, string>>;
  message?: string;
  lastAppliedRevision?: string;
  lastAttemptedRevision?: string;
  lastHandledReconcileAt?: string;
  // ---- mapping from chart definition to release ----
  endpoints?: ReleaseEndpoint[];
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
  defaultRepo?: string;
  // Optional backend-driven metadata for post-install actions (e.g., keytab/ranger replay).
  kerberos?: Array<Record<string, any>>;
  ranger?: Record<string, Record<string, any>>;
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
  imageProject?: string;
  imageRegistryHostOverride?: string;
}

export interface HelmReleasePage {
  items: HelmRelease[];
  total: number;
}
