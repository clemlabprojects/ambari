// ui/src/api/client.ts
import { getMockClusterStats, getMockComponentStatuses, getMockClusterEvents, getMockNodes, getMockHelmReleases, getChartsJSON, getMockHelmRepos } from './mock';
import type {ClusterService} from '../types/ServiceTypes';
import type {HelmRepo} from '../types';

const API_BASE_URL = `/api/v1/views/K8S-VIEW/versions/1.0.0.1/instances/K8S_VIEW_INSTANCE/resources/api`;

export type CommandState = 'PENDING'|'RUNNING'|'SUCCEEDED'|'FAILED'|'CANCELLED';

export type CommandStatus = {
  id: string;
  state: CommandState;
  percent: number;
  step: number;
  message: string;
  error?: string;
  result?: any;
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

export const getAvailableServices = async () => {
  if (import.meta.env.DEV) {
    return getChartsJSON();
  }
  const response = await fetch(`${API_BASE_URL}/charts/available`);
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
 * Fetches existing cluster services of a specific type (e.g., HIVE_METASTORE).
 * @param serviceType The type of service to fetch from the backend.
 */
export const getClusterServices = async (serviceType: string): Promise<ClusterService[]> => {
    // In a real implementation, this would call your backend which in turn queries Ambari or another service registry.
    console.log(`Fetching cluster services of type: ${serviceType}`);
    // MOCK IMPLEMENTATION FOR NOW
    if (serviceType === 'HIVE_METASTORE') {
        return new Promise(resolve => setTimeout(() => resolve([
            { label: 'Hive Metastore (Primary)', value: 'thrift://metastore.hadoop.local:9083' },
            { label: 'Hive Metastore (Secondary)', value: 'thrift://metastore-ha.hadoop.local:9083' }
        ]), 500));
    }
    return Promise.resolve([]);
};

export const getClusterStats = async () => {
  if (import.meta.env.DEV) {
    if (sessionStorage.getItem('isUnconfigured')) {
      throw new Error('unconfigured');
    }
    return getMockClusterStats();
  }
  const response = await fetch(`${API_BASE_URL}/cluster/stats`);
  return handleApiResponse(response);
};

export async function getCommandStatus(id: string) {
  const response = await fetch(`${API_BASE_URL}/commands/${id}`);
  if (!response.ok) throw new Error(`Status failed: ${response.status}`);
  return response.json() as Promise<CommandStatus>;
}

export const getComponentStatuses = async () => {
  if (import.meta.env.DEV) {
    if (sessionStorage.getItem('isUnconfigured')) throw new Error('unconfigured');
    return getMockComponentStatuses();
  }
  const response = await fetch(`${API_BASE_URL}/cluster/componentstatuses`);
  return handleApiResponse(response);
};

export const getHelmReleases = async () => {
    if (import.meta.env.DEV) {
        if (sessionStorage.getItem('isUnconfigured')) throw new Error('unconfigured');
        return getMockHelmReleases();
    }
    const response = await fetch(`${API_BASE_URL}/helm/releases`);
    return handleApiResponse(response);
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

export const getNodes = async () => {
    if (import.meta.env.DEV) {
        if (sessionStorage.getItem('isUnconfigured')) throw new Error('unconfigured');
        return getMockNodes();
    }
    const response = await fetch(`${API_BASE_URL}/nodes`);
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
  chart: string; releaseName: string; namespace: string; values: any; serviceKey?: string;
}, params: URLSearchParams = new URLSearchParams()) {
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
export async function uninstallHelm(name: string, namespace: string) {
  const url = `${API_BASE_URL}/helm/release/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`;
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