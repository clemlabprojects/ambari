// ui/src/api/client.ts
import { getMockClusterStats, getMockComponentStatuses, getMockClusterEvents, getMockNodes, getMockHelmReleases, getChartsJSON, getMockHelmRepos, getMockCommandStatus, getMockCommands } from './mock';
import { getMockSecurityConfig } from './mock';
import type {ClusterService} from '../types/ServiceTypes';
import type {HelmRepo} from '../types';
import type { KubeNamespace, KubePod, KubeService, KubeEvent } from '../types/KubeTypes';

/**
 * Resolve API base from current view URL so bumps to the view version or instance
 * do not break the frontend. Falls back to the known default if parsing fails.
 */
const resolveApiBase = (): string => {
  const fallback = `/api/v1/views/K8S-VIEW/versions/1.0.0.1/instances/K8S_VIEW_INSTANCE/resources/api`;
  if (typeof window === 'undefined') return fallback;
  try {
    // Examples of pathname we might see:
    //  /#/main/views/K8S-VIEW/1.0.0.1/K8S_VIEW_INSTANCE
    //  /main/views/K8S-VIEW/1.0.0.1/K8S_VIEW_INSTANCE
    const path = window.location.pathname + window.location.hash;
    const match = path.match(/views\/([^/]+)\/([^/]+)\/([^/#?]+)/);
    if (!match) return fallback;
    const [, viewName, version, instance] = match;
    return `/api/v1/views/${viewName}/versions/${version}/instances/${instance}/resources/api`;
  } catch {
    return fallback;
  }
};

export const API_BASE_URL = resolveApiBase();

// Backend uses single-L "CANCELED"; keep backward compatibility with old "CANCELLED"
export type CommandState = 'PENDING'|'RUNNING'|'SUCCEEDED'|'FAILED'|'CANCELLED'|'CANCELED';

export type CommandStatus = {
  id: string;
  hasChildren?: boolean;
  type?: string;
  state: CommandState;
  percent: number;
  step: number;
  message: string;
  createdBy?: string;
  error?: string;
  result?: any;
  createdAt?: string;
  updatedAt?: string;
};

export interface StackServiceDef {
  name: string;
  label: string;
  chart: string;
  description: string;
  form: any[]; // FormField[]
}

export interface StackConfig {
  name: string;
  description: string;
  properties: StackProperty[];
}

export const getGlobalConfigs = async (): Promise<StackConfig[]> => {
  if (import.meta.env.DEV) {
    return (await import('./mock')).getStackConfigsMock('GLOBAL') as any;
  }
  const res = await fetch(`./api/v1/globals/configurations`, { headers: { 'X-Requested-By': 'ambari' } });
  if (!res.ok) throw new Error('Failed to load global configs');
  return res.json();
};

export interface StackProperty {
  name: string;
  displayName: string;
  description?: string;
  type: 'string' | 'int' | 'boolean' | 'password' | 'content';
  value: any;
  required?: boolean;
  language?: string;
  unit?: string;
}

export interface SecurityConfig {
  mode?: 'none' | 'ldap' | 'ad' | 'oidc';
  ldap?: {
    url?: string;
    bindDn?: string;
    bindPassword?: string;
    userDnTemplate?: string;
    userSearchFilter?: string;
    baseDn?: string;
    groupSearchBase?: string;
    groupSearchFilter?: string;
    groupAuthPattern?: string;
    referral?: string;
    startTls?: boolean;
    adUrl?: string;
    adBaseDn?: string;
    adBindDn?: string;
    adBindPassword?: string;
    adUserSearchFilter?: string;
    adDomain?: string;
  };
  oidc?: {
    issuerUrl?: string;
    clientId?: string;
    clientSecret?: string;
    scopes?: string;
    redirectUri?: string;
    userClaim?: string;
    groupsClaim?: string;
    skipTlsVerify?: boolean;
    caSecret?: string;
  };
  tls?: {
    truststoreSecret?: string;
    truststorePasswordKey?: string;
    truststoreKey?: string;
  };
  extraProperties?: Record<string, any>;
}

export interface VaultConfig {
  enabled?: boolean;
  address?: string;
  namespace?: string;
  auth?: {
    method?: 'kubernetes' | 'approle';
    mountPath?: string;
    kubernetes?: {
      role?: string;
    };
    approle?: {
      roleId?: string;
      secretId?: string;
    };
  };
  csi?: {
    caCertSecret?: string;
  };
  extraProperties?: Record<string, any>;
}

// Workloads browsing helpers (namespaces, pods, services, logs)
export const getNamespaces = async (): Promise<KubeNamespace[]> => {
  const res = await fetch(`${API_BASE_URL}/workloads/namespaces`, { credentials: 'include' });
  return handleApiResponse(res);
};

export const getPods = async (ns: string, labelSelector?: string): Promise<KubePod[]> => {
  const params = new URLSearchParams();
  if (labelSelector) params.set('labelSelector', labelSelector);
  const res = await fetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/pods?${params.toString()}`, { credentials: 'include' });
  return handleApiResponse(res);
};

export const getServices = async (ns: string, labelSelector?: string): Promise<KubeService[]> => {
  const params = new URLSearchParams();
  if (labelSelector) params.set('labelSelector', labelSelector);
  const res = await fetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/services?${params.toString()}`, { credentials: 'include' });
  return handleApiResponse(res);
};

export const getPodLogs = async (ns: string, pod: string, container?: string, tailLines?: number): Promise<string> => {
  const params = new URLSearchParams();
  if (container) params.set('container', container);
  if (tailLines) params.set('tailLines', tailLines.toString());
  const res = await fetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/pods/${encodeURIComponent(pod)}/log?${params.toString()}`, {
    credentials: 'include'
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Failed to fetch logs (${res.status})`);
  }
  return res.text();
};

export const getNamespaceEvents = async (ns: string): Promise<KubeEvent[]> => {
  const res = await fetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/events`, { credentials: 'include' });
  return handleApiResponse(res);
};

export const getPodEvents = async (ns: string, pod: string): Promise<KubeEvent[]> => {
  const res = await fetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/pods/${encodeURIComponent(pod)}/events`, { credentials: 'include' });
  return handleApiResponse(res);
};

export const describePod = async (ns: string, pod: string): Promise<string> => {
  const res = await fetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/pods/${encodeURIComponent(pod)}/describe`, { credentials: 'include' });
  if (!res.ok) throw new Error(await res.text());
  return res.text();
};

export const describeService = async (ns: string, svc: string): Promise<string> => {
  const res = await fetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/services/${encodeURIComponent(svc)}/describe`, { credentials: 'include' });
  if (!res.ok) throw new Error(await res.text());
  return res.text();
};

export const deletePod = async (ns: string, pod: string, graceSeconds?: number): Promise<void> => {
  const params = new URLSearchParams();
  if (graceSeconds != null) params.set('graceSeconds', graceSeconds.toString());
  const res = await fetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/pods/${encodeURIComponent(pod)}?${params.toString()}`, { method: 'DELETE', credentials: 'include' });
  if (!res.ok) throw new Error(await res.text());
};

export const restartPod = async (ns: string, pod: string): Promise<void> => {
  const res = await fetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/pods/${encodeURIComponent(pod)}/restart`, { method: 'POST', credentials: 'include' });
  if (!res.ok) throw new Error(await res.text());
};

async function fetchJson<T = unknown>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    ...options,
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`HTTP ${response.status} – ${text || response.statusText}`);
  }
  if (response.status === 204) return undefined as unknown as T; // No-content
  return response.json() as Promise<T>;
}

function fetchWithTimeout(input: RequestInfo, init: RequestInit = {}, timeoutMs = 60000): Promise<Response> {
  const abortController = new AbortController();
  const timeoutId = setTimeout(() => abortController.abort(), timeoutMs);
  return fetch(input, { ...init, signal: abortController.signal })
    .finally(() => clearTimeout(timeoutId));
}

const handleApiResponse = async (response: Response) => {
  if (response.ok) {
    return response.json();
  }
  const errorText = await response.text();
  
  // Reliably detect the "unconfigured" state from the backend
  if (response.status === 412 || (errorText && errorText.includes("not configured with a kubeconfig"))) {
    throw new Error('unconfigured');
  }
  throw new Error(errorText || `Request failed with status ${response.status}`);
};

export const deleteHelmRepo = (id: string) =>
  fetchJson<void>(`/helm/repos/${id}`, { method: "DELETE" });

export async function deployHelm(
  payload: {
  chart: string; releaseName: string; namespace: string; values: any; serviceKey?: string;
  }, params: URLSearchParams = new URLSearchParams()) {
  const url = `${API_BASE_URL}/helm/deploy?${params.toString()}`;
  const response = await fetchWithTimeout(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  }, 300000); // 5 min for install/upgrade
  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.error || `Deploy failed: ${response.status}`);
  }
  return response.json().catch(() => ({}));
}

export async function checkHelmRepo(id: string) {
  const response = await fetch(`${API_BASE_URL}/helm/repos/${id}/check`, {
    method: 'GET',
    credentials: 'include'
  });
  return handleApiResponse(response);
}

export async function installMonitoring(repoId?: string) {
  const params = repoId ? `?repoId=${encodeURIComponent(repoId)}` : '';
  const response = await fetch(`${API_BASE_URL}/helm/monitoring/install${params}`, {
    method: 'POST',
    credentials: 'include'
  });
  return handleApiResponse(response);
}

export async function resetMonitoringCache() {
  const response = await fetch(`${API_BASE_URL}/helm/monitoring/reset`, {
    method: 'POST',
    credentials: 'include'
  });
  return handleApiResponse(response);
}


export const getClusterEvents = async () => {
  if (import.meta.env.DEV) {
    if (sessionStorage.getItem('isUnconfigured')) throw new Error('unconfigured');
    return getMockClusterEvents();
  }
  const response = await fetch(`${API_BASE_URL}/cluster/events`);
  return handleApiResponse(response);
};

/**
 * Fetches existing Ambari cluster services (e.g. Hive Metastore URI).
 * Calls GET /api/discovery/ambari?serviceType=...
 */
export const getClusterServices = async (serviceType: string): Promise<ClusterService[]> => {
    // No more mock. Call the backend.
    const params = new URLSearchParams({ serviceType });
    const response = await fetch(`${API_BASE_URL}/discovery/ambari?${params.toString()}`, {
      credentials: 'include'
    });
    return handleApiResponse(response);
};

/**
 * Discover monitoring stack (kube-prometheus-stack) and return namespace/release.
 */
export const getMonitoringDiscovery = async (): Promise<{ namespace: string; release: string; url?: string }> => {
  const response = await fetch(`${API_BASE_URL}/discovery/monitoring/prometheus`, { credentials: 'include' });
  return handleApiResponse(response);
};

export const getViewSettings = async (): Promise<any> => {
  const response = await fetch(`${API_BASE_URL}/configurations/settings`, { credentials: 'include' });
  if (!response.ok) {
    const txt = await response.text();
    throw new Error(txt || 'Failed to load settings');
  }
  return response.json();
};

export const saveViewSettings = async (settings: any): Promise<void> => {
  const response = await fetch(`${API_BASE_URL}/configurations/settings`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings)
  });
  if (!response.ok) {
    const txt = await response.text();
    throw new Error(txt || 'Failed to save settings');
  }
};

export interface SecurityProfiles {
  defaultProfile?: string;
  profiles: Record<string, SecurityConfig>;
}

export interface VaultProfiles {
  defaultProfile?: string;
  profiles: Record<string, VaultConfig>;
}

export const getSecurityConfig = async (): Promise<SecurityProfiles> => {
  if (import.meta.env.DEV) {
    return getMockSecurityConfig();
  }
  const res = await fetch(`${API_BASE_URL}/configurations/security`, { credentials: 'include' });
  if (!res.ok) {
    const txt = await res.text();
    throw new Error(txt || 'Failed to load security config');
  }
  return res.json();
};

export const getVaultConfig = async (): Promise<VaultProfiles> => {
  const res = await fetch(`${API_BASE_URL}/configurations/vault`, { credentials: 'include' });
  if (!res.ok) {
    const txt = await res.text();
    throw new Error(txt || 'Failed to load vault config');
  }
  return res.json();
};

export const saveSecurityConfig = async (cfg: SecurityProfiles): Promise<void> => {
  const res = await fetch(`${API_BASE_URL}/configurations/security`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(cfg || {})
  });
  if (!res.ok) {
    const txt = await res.text();
    throw new Error(txt || 'Failed to save security config');
  }
};

export const saveVaultConfig = async (cfg: VaultProfiles): Promise<void> => {
  const res = await fetch(`${API_BASE_URL}/configurations/vault`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(cfg || {})
  });
  if (!res.ok) {
    const txt = await res.text();
    throw new Error(txt || 'Failed to save vault config');
  }
};

export interface SecurityProfileUsage {
  releases: string[];
}

export interface VaultProfileUsage {
  releases: string[];
}

export const getSecuritySchema = async (): Promise<any> => {
  if (import.meta.env.DEV) {
    const { getMockSecuritySchema } = await import('./mock');
    return getMockSecuritySchema();
  }
  const res = await fetch(`${API_BASE_URL}/configurations/security/schema`, { credentials: 'include' });
  if (!res.ok) {
    const txt = await res.text();
    throw new Error(txt || 'Failed to load security schema');
  }
  return res.json();
};

export const getVaultSchema = async (): Promise<any> => {
  const res = await fetch(`${API_BASE_URL}/configurations/vault/schema`, { credentials: 'include' });
  if (!res.ok) {
    const txt = await res.text();
    throw new Error(txt || 'Failed to load vault schema');
  }
  return res.json();
};

export const getSecurityProfileUsage = async (profile: string): Promise<SecurityProfileUsage> => {
  if (!profile) {
    throw new Error('Profile name is required');
  }
  const res = await fetch(`${API_BASE_URL}/configurations/security/${encodeURIComponent(profile)}/usage`, { credentials: 'include' });
  if (!res.ok) {
    const txt = await res.text();
    throw new Error(txt || 'Failed to load profile usage');
  }
  return res.json();
};

export const getVaultProfileUsage = async (profile: string): Promise<VaultProfileUsage> => {
  if (!profile) {
    throw new Error('Profile name is required');
  }
  const res = await fetch(`${API_BASE_URL}/configurations/vault/${encodeURIComponent(profile)}/usage`, { credentials: 'include' });
  if (!res.ok) {
    const txt = await res.text();
    throw new Error(txt || 'Failed to load profile usage');
  }
  return res.json();
};

export const deleteSecurityProfile = async (profile: string): Promise<void> => {
  if (!profile) {
    throw new Error('Profile name is required');
  }
  const res = await fetch(`${API_BASE_URL}/configurations/security/${encodeURIComponent(profile)}`, {
    method: 'DELETE',
    credentials: 'include'
  });
  if (!res.ok) {
    let payload: any = null;
    try {
      payload = await res.json();
    } catch {
      payload = null;
    }
    const err = new Error(payload?.error || 'Failed to delete security profile');
    (err as any).status = res.status;
    (err as any).releases = payload?.releases || [];
    throw err;
  }
};

export const deleteVaultProfile = async (profile: string): Promise<void> => {
  if (!profile) {
    throw new Error('Profile name is required');
  }
  const res = await fetch(`${API_BASE_URL}/configurations/vault/${encodeURIComponent(profile)}`, {
    method: 'DELETE',
    credentials: 'include'
  });
  if (!res.ok) {
    let payload: any = null;
    try {
      payload = await res.json();
    } catch {
      payload = null;
    }
    const err = new Error(payload?.error || 'Failed to delete vault profile');
    (err as any).status = res.status;
    (err as any).releases = payload?.releases || [];
    throw err;
  }
};


/**
 * Fetches running Kubernetes services matching a specific label.
 * Calls GET /api/discovery/k8s?label=...
 */

export const getDiscoveredK8sServices = async (labelSelector: string): Promise<ClusterService[]> => {
    // In import.meta.env.DEV you might want to return mock data, 
    // otherwise call the real backend.
    if (import.meta.env.DEV) {
       console.log(`Mocking K8s discovery for label: ${labelSelector}`);
       return [
         { label: 'trino-test-1 (mock)', value: 'trino-test-1.default.svc.cluster.local' }
       ];
    }

    const params = new URLSearchParams({ label: labelSelector });
    // Note: ensure API_BASE_URL includes the /resources/api part as defined in your file
    const response = await fetch(`${API_BASE_URL}/discovery/k8s?${params.toString()}`);
    return handleApiResponse(response);
}

export const getClusterStats = async (forceRefresh = false) => {
  if (import.meta.env.DEV) {
    if (sessionStorage.getItem('isUnconfigured')) {
      throw new Error('unconfigured');
    }
    return getMockClusterStats();
  }
  const params = new URLSearchParams();
  if (forceRefresh) params.set('forceRefresh', 'true');
  const response = await fetch(`${API_BASE_URL}/cluster/stats?${params.toString()}`);
  return handleApiResponse(response);
};

export async function getCommandStatus(id: string) {
  if (import.meta.env.DEV) {
    return getMockCommandStatus(id);
  }
  // Explicit fetch keeps polling simple and avoids any header/caching surprises
  const response = await fetch(`${API_BASE_URL}/commands/${id}`, {
    credentials: 'include',
    cache: 'no-store',
  });
  if (!response.ok) throw new Error(`Status failed: ${response.status}`);
  return response.json() as Promise<CommandStatus>;
}

export async function listCommands(limit = 10, offset = 0) {
  if (import.meta.env.DEV) {
    return getMockCommands(limit, offset);
  }
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  const response = await fetch(`${API_BASE_URL}/commands?${params.toString()}`, {
    credentials: 'include',
    cache: 'no-store',
  });
  if (!response.ok) throw new Error(`List failed: ${response.status}`);
  return response.json() as Promise<CommandStatus[]>;
}

export async function getCommandLogs(id: string, offset = 0, limit = 65536) {
  const params = new URLSearchParams({ offset: String(offset), limit: String(limit) });
  const response = await fetch(`${API_BASE_URL}/commands/${id}/logs?${params.toString()}`, {
    credentials: 'include',
    cache: 'no-store'
  });
  if (!response.ok) throw new Error(`Logs failed: ${response.status}`);
  return response.json() as Promise<{ content: string; nextOffset: number; eof: boolean; size: number }>;
}

export async function listChildCommands(id: string) {
  const response = await fetch(`${API_BASE_URL}/commands/${id}/children`, {
    credentials: 'include',
    cache: 'no-store',
  });
  if (!response.ok) throw new Error(`List children failed: ${response.status}`);
  return response.json() as Promise<CommandStatus[]>;
}

export async function cancelCommand(id: string) {
  const response = await fetch(`${API_BASE_URL}/commands/${id}/cancel`, { method: 'POST' });
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(text || `Cancel failed: ${response.status}`);
  }
}

export async function refreshDependencies() {
  const response = await fetch(`${API_BASE_URL}/commands/dependencies/refresh`, {
    method: 'POST',
    credentials: 'include'
  });
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(text || `Refresh failed: ${response.status}`);
  }
}

export const getComponentStatuses = async () => {
  if (import.meta.env.DEV) {
    if (sessionStorage.getItem('isUnconfigured')) throw new Error('unconfigured');
    return getMockComponentStatuses();
  }
  const response = await fetch(`${API_BASE_URL}/cluster/componentstatuses`);
  return handleApiResponse(response);
};

export const getHelmReleases = async (limit = 20, offset = 0) => {
    if (import.meta.env.DEV) {
        if (sessionStorage.getItem('isUnconfigured')) throw new Error('unconfigured');
        return getMockHelmReleases();
    }
    const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
    const response = await fetch(`${API_BASE_URL}/helm/releases?${params.toString()}`, { credentials: 'include' });
    return handleApiResponse(response);
};

/**
 * Fetches the latest backend-aggregated status for a release via Flux/Helm backends.
 */
export const getReleaseStatus = async (namespace: string, releaseName: string): Promise<HelmRelease> => {
  const response = await fetch(`${API_BASE_URL}/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/status`, {
    credentials: 'include',
    cache: 'no-store'
  });
  return handleApiResponse(response);
};

/**
 * Trigger an idempotent keytab regeneration workflow for a deployed release.
 * The backend schedules a background command and returns its id for tracking.
 */
export const regenerateReleaseKeytabs = async (namespace: string, releaseName: string) => {
  const requestPath = `/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/actions/keytabs`;
  return fetchJson<{ id: string; href?: string }>(requestPath, { method: 'POST' });
};

/**
 * Re-apply the Ranger repository configuration for a deployed release.
 * The backend replays the same install-time Ranger action without a Helm upgrade.
 */
export const reapplyReleaseRangerRepository = async (namespace: string, releaseName: string) => {
  const requestPath = `/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/actions/ranger`;
  return fetchJson<{ id: string; href?: string }>(requestPath, { method: 'POST' });
};

export const getHelmRepos = () => {
  if (import.meta.env.DEV) {
    return getMockHelmRepos();
  } else {
    return fetchJson<HelmRepo[]>("/helm/repos");
  }
}

/**
 * Retrieves the current kubeconfig content from the backend.
 */
export const getKubeconfigContent = async (): Promise<string> => {
  // In dev mode, return a simple example
  if (import.meta.env.DEV) {
    return "apiVersion: v1\nclusters:\n- name: mocked-cluster\n  cluster:\n    server: https://mock.server:6443";
  }

  const response = await fetch(`${API_BASE_URL}/cluster/config`, {
    headers: {
      'X-Requested-By': 'ambari',
    },
  });

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(errorText || 'Failed to fetch kubeconfig content');
  }
  return response.text();
};

export const getNodes = async (limit = 200, offset = 0) => {
  if (import.meta.env.DEV) {
    if (sessionStorage.getItem('isUnconfigured')) throw new Error('unconfigured');
    return { items: getMockNodes(), total: getMockNodes().length };
  }
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  const response = await fetch(`${API_BASE_URL}/nodes?${params.toString()}`);
  return handleApiResponse(response);
};

export const isViewConfigured = async () => {
  if (import.meta.env.DEV) {
    return !sessionStorage.getItem('isUnconfigured');
  }
  const response = await fetch(`${API_BASE_URL}/cluster/configured`);
  try {
    const data = await handleApiResponse(response);
    return data.message === "CONFIGURED";
  } catch (e) {
    return false;
  }
};

export async function loginHelmRepo(id: string) {
  const response = await fetch(`${API_BASE_URL}/helm/repos/${encodeURIComponent(id)}/login`, {
    method: 'POST',
  });

  if (response.status === 204) return { ok: true }; // No Content

  if (!response.ok) {
    throw new Error(`Login failed: ${response.status} ${response.statusText}`);
  }

  const text = await response.text();
  if (!text) return { ok: true }; // Empty 200 response

  try {
    return JSON.parse(text); // 200 with JSON
  } catch {
    return { ok: true, raw: text }; // 200 with non-JSON (tolerated)
  }
}

/**
 * Requests the backend to (re-)login/sync the repository.
 * Credentials are retrieved from the Ambari database.
 *
 * @param repoId repository identifier
 */
export async function loginRepo(repoId: string) {
  const url = `${API_BASE_URL}/helm/repos/${encodeURIComponent(repoId)}/login`;

  const response = await fetch(url, { method: 'POST' });
  if (!response.ok) throw new Error(`Login repo failed: ${response.status}`);
  return response.json().catch(() => ({}));
}

/**
 * Determines if a repository needs (or re-needs) authentication.
 *
 * • anonymous → never needs login
 * • basic|token
 *      - if authInvalid === true => needs new login
 *      - if authInvalid === false => considered already logged in
 *      - undefined => unknown ⇒ request login
 */
export function needsLogin(repoId: string, repos: HelmRepo[]): boolean {
  const repository = repos.find((r) => r.id === repoId);
  if (!repository) return false; // Unknown repository ⇒ let it pass

  if (repository.authMode === 'anonymous') return false;

  return repository.authInvalid !== false; // true or undefined ⇒ login
}

export const saveHelmRepo = (repo: HelmRepo, secret?: string) =>
  fetchJson<void>(
    "/helm/repos" + (secret ? `?secret=${encodeURIComponent(secret)}` : ""),
    { method: "POST", body: JSON.stringify(repo) }
  );

export async function submitHelmDeploy(payload: {
  chart: string;
  releaseName: string;
  namespace: string;
  values: any;
  serviceKey?: string;
  securityProfile?: string;
  vaultProfile?: string;
  repoId?: string;
  deploymentMode?: string;
  git?: any;
  stackConfigOverrides?: Record<string, any>;
  secretName?: string;
  endpoints?: any;
  mounts?: any;
  tls?: any;
  ingressTlsUpload?: any;
  dependencies?: any;
  ranger?: any;
  requiredConfigMaps?: any;
  dynamicValues?: any;
}, params: URLSearchParams = new URLSearchParams()) {
  // always propagate repoId as a query param to help the backend resolve the repository deterministically
  if (payload.repoId && !params.has('repoId')) {
    params.set('repoId', payload.repoId);
  }
  const url = `${API_BASE_URL}/commands/helm/deploy?${params.toString()}`;
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type':'application/json' },
    body: JSON.stringify(payload),
  });
  if (!response.ok) throw new Error(`Submit failed: ${response.status}`);
  return response.json() as Promise<{id: string}>;
}

/**
 * Uninstalls a Helm release.
 *
 * @param name release name
 * @param namespace release namespace
 */
export async function uninstallHelm(
  name: string,
  namespace: string,
  params: URLSearchParams = new URLSearchParams()
) {
  const url = `${API_BASE_URL}/helm/release/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}?${params.toString()}`;
  const response = await fetch(url, { method: 'DELETE' });
  if (!response.ok) throw new Error(`Uninstall failed: ${response.status}`);
  // backend returns empty body → don't call .json()
  return;
}

/**
 * Updates a Helm release.
 * If `chartRef` is provided, it will be used to update the chart.
 * If `repoId` is provided, it will be used to find the chart in the repository.
 * If `chartName` and `version` are provided, they will be used
 * to specify the chart and its version.
 * If `reuseValues` is `true`, existing values will be reused.
 * If `timeoutSeconds` is provided, it will be used to set the timeout.
 * 
 * @param payload payload for upgrading a Helm release
 * @param payload.releaseName name of the release to update
 * @param payload.namespace release namespace
 * @param payload.chartRef chart reference (name or name@version)
 */
export async function upgradeHelm(
  payload: { chart: string; releaseName: string; namespace: string; values: any; serviceKey?: string },
  params: URLSearchParams = new URLSearchParams()
) {
  const url = `${API_BASE_URL}/helm/upgrade?${params.toString()}`;
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!response.ok) throw new Error(`Upgrade failed: ${response.status}`);
  return response.json().catch(() => ({}));
}

/**
 * Validates on the server side that the chart/version exists in the selected repository
 * (or in the global index if repoId is absent).
 *
 * @param payload Same signature as deployHelm; only `chart` is actually used,
 *                other fields are simply ignored by the API
 * @param params  URLSearchParams with at least `repoId` and optionally
 *                `version`, `kubeContext`, etc.
 *
 * @returns       { exists:boolean, latest?:string }
 */
export async function validateChart(
  payload: { chart: string; releaseName: string; namespace: string; values: any; serviceKey?: string },
  params: URLSearchParams = new URLSearchParams()) {
  
    const url = `${API_BASE_URL}/helm/validate?${params.toString()}`;
    const response = await fetchWithTimeout(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }, 30000); // 30s is enough for validate
    if (!response.ok) throw new Error(`Validate failed: ${response.status}`);
    return response.json();
}

// Add to your existing client.ts

export interface ManagedConfig {
  name: string;
  namespace: string;
  type: string;       // e.g. "superset-python"
  filename: string;   // e.g. "superset_config.py"
  language: string;   // e.g. "python"
  description?: string;
  content?: string;
  isDefault?: boolean; // True if seeded by system
}

export const listManagedConfigs = async (): Promise<ManagedConfig[]> => {
  // Pass namespace=dashboarding or handle in backend logic
  // Using relative path assuming proxy is set up or base URL matches
  const response = await fetch(`${API_BASE_URL}/configurations`, {
    headers: { 'X-Requested-By': 'ambari' }
  });
  if (!response.ok) throw new Error('Failed to fetch configurations');
  return response.json();
};

export const getManagedConfigContent = async (namespace: string, name: string, filename: string): Promise<string> => {
  const response = await fetch(`${API_BASE_URL}/configurations/${namespace}/${name}?filename=${filename}`, {
    headers: { 'X-Requested-By': 'ambari' }
  });
  if (!response.ok) throw new Error('Failed to fetch content');
  const json = await response.json();
  return json.content || '';
};

export const saveManagedConfig = async (config: ManagedConfig) => {
  const response = await fetch(`${API_BASE_URL}/configurations`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Requested-By': 'ambari'
    },
    body: JSON.stringify(config)
  });
  if (!response.ok) throw new Error('Failed to save config');
};

export const deleteManagedConfig = async (name: string, namespace: string) => {
  const response = await fetch(`${API_BASE_URL}/configurations/${namespace}/${name}`, {
    method: 'DELETE',
    headers: { 'X-Requested-By': 'ambari' }
  });
  if (!response.ok) throw new Error('Failed to delete config');
};


/**
 * Fetches the current user-supplied values for a specific Helm release.
 * Maps to Backend: GET /api/v1/helm/{namespace}/{releaseName}/values
 */
export const getReleaseValues = async (namespace: string, releaseName: string): Promise<any> => {
  // Construct the URL. Adjust the base path ('./api/v1') if your setup differs.
  const url = `${API_BASE_URL}/helm/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/values`;
  
  const response = await fetch(url, {
    method: 'GET',
    headers: { 
      'Content-Type': 'application/json',
      'X-Requested-By': 'ambari' // Required by Ambari CSRF protection
    }
  });

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`Failed to fetch release values: ${response.status} ${errorText}`);
  }

  // The backend returns a JSON object (Map<String, Object>) representing the values.yaml
  return response.json();
};

/** Stack Definition */
export const getStackService = async (name: string): Promise<StackServiceDef> => {
  if (import.meta.env.DEV) {
    return (await import('./mock')).getStackServiceMock(name);
  }
  const res = await fetch(`${API_BASE_URL}/services/${name}`, { headers: { 'X-Requested-By': 'ambari' } });
  if (!res.ok) throw new Error("Failed to load service definition");
  return res.json();
};

export const getStackConfigs = async (name: string): Promise<StackConfig[]> => {
  if (import.meta.env.DEV) {
    return (await import('./mock')).getStackConfigsMock(name) as any;
  }
  const res = await fetch(`${API_BASE_URL}/services/${name}/configurations`, { headers: { 'X-Requested-By': 'ambari' } });
  if (!res.ok) throw new Error("Failed to load configs");
  return res.json();
};


export const getAvailableServices = async () => {
  if (import.meta.env.DEV) {
    // Use KDPS-style mocks in dev
    return getChartsJSON();
  }
  const response = await fetch(`${API_BASE_URL}/services`, { headers: { 'X-Requested-By': 'ambari' } });
  return handleApiResponse(response);
}
