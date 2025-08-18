// ui/src/api/client.ts
import { getMockClusterStats, getMockComponentStatuses, getMockClusterEvents, getMockNodes, getMockHelmReleases, getChartsJSON, getMockHelmRepos } from './mock';
import type {ClusterService} from '../types/ServiceTypes';
import type {HelmRepo} from '../types';
const API_BASE_URL = `/api/v1/views/K8S-VIEW/versions/1.0.0.1/instances/K8S_VIEW_INSTANCE/resources/api`;

async function fetchJson<T = unknown>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const resp = await fetch(`${API_BASE_URL}${path}`, {
    headers: { "Content-Type": "application/json" },
    credentials: "include",    // si tu relies à un cookie de session
    ...options,
  });
  if (!resp.ok) {
    const text = await resp.text();
    throw new Error(`HTTP ${resp.status} – ${text || resp.statusText}`);
  }
  if (resp.status === 204) return undefined as unknown as T; // No-content
  return resp.json() as Promise<T>;
}

function fetchWithTimeout(input: RequestInfo, init: RequestInit = {}, ms = 60000): Promise<Response> {
  const ac = new AbortController();
  const id = setTimeout(() => ac.abort(), ms);
  return fetch(input, { ...init, signal: ac.signal })
    .finally(() => clearTimeout(id));
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

export const getComponentStatuses = async () => {
  if (import.meta.env.DEV) {
    if (sessionStorage.getItem('isUnconfigured')) throw new Error('unconfigured');
    return getMockComponentStatuses();
  }
  const response = await fetch(`${API_BASE_URL}/cluster/componentstatuses`);
  return handleApiResponse(response);
};

export const getClusterEvents = async () => {
  if (import.meta.env.DEV) {
    if (sessionStorage.getItem('isUnconfigured')) throw new Error('unconfigured');
    return getMockClusterEvents();
  }
  const response = await fetch(`${API_BASE_URL}/cluster/events`);
  return handleApiResponse(response);
};

export const getNodes = async () => {
    if (import.meta.env.DEV) {
        if (sessionStorage.getItem('isUnconfigured')) throw new Error('unconfigured');
        return getMockNodes();
    }
    const response = await fetch(`${API_BASE_URL}/nodes`);
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

/**
 * Récupère le contenu du kubeconfig actuel depuis le backend.
 */
export const getKubeconfigContent = async (): Promise<string> => {
  // En mode dev, on retourne un exemple simple
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

export const getAvailableServices = async () => {
        // --- API CALL using fetch ---
  if (import.meta.env.DEV) {
    return getChartsJSON();
  }
  const response = await fetch(`${API_BASE_URL}/charts/available`);
  return handleApiResponse(response);

}

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
            { label: 'Hive Metastore (Principal)', value: 'thrift://metastore.hadoop.local:9083' },
            { label: 'Hive Metastore (Secondaire)', value: 'thrift://metastore-ha.hadoop.local:9083' }
        ]), 500));
    }
    return Promise.resolve([]);
};

/**
 * Vérifie, côté serveur, que le chart / version existe bien dans le dépôt sélectionné
 * (ou dans l’index global si repoId absent).
 *
 * @param payload ─ même signature que deployHelm ; seul `chart` est réellement
 *                 utilisé, les autres champs sont simplement ignorés par l’API
 *                 (on garde la signature homogène pour éviter d’avoir deux
 *                 objets différents côté UI).
 * @param params  ─ URLSearchParams avec au moins `repoId` et, optionnellement,
 *                 `version`, `kubeContext`, etc.
 *
 * @returns       ─ { exists:boolean, latest?:string }
 */
export async function validateChart(
  payload: { chart: string; releaseName: string; namespace: string; values: any; serviceKey?: string },
  params: URLSearchParams = new URLSearchParams()) {
  
    const url = `${API_BASE_URL}/helm/validate?${params.toString()}`;
    const res = await fetchWithTimeout(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }, 30000); // 30s is enough for validate
    if (!res.ok) throw new Error(`Validate failed: ${res.status}`);
    return res.json();
}

export async function deployHelm(
  payload: {
  chart: string; releaseName: string; namespace: string; values: any; serviceKey?: string;
  }, params: URLSearchParams = new URLSearchParams()) {
  const url = `${API_BASE_URL}/helm/deploy?${params.toString()}`;
  const res = await fetchWithTimeout(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  }, 300000); // 5 min for install/upgrade
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.error || `Deploy failed: ${res.status}`);
  }
  return res.json().catch(() => ({}));
}

export const getHelmRepos = () => {
  if (import.meta.env.DEV) {
    return getMockHelmRepos();
  }else {
    return fetchJson<HelmRepo[]>("/helm/repos");
  }
}

export const saveHelmRepo = (repo: HelmRepo, secret?: string) =>
  fetchJson<void>(
    "/helm/repos" + (secret ? `?secret=${encodeURIComponent(secret)}` : ""),
    { method: "POST", body: JSON.stringify(repo) }
  );

export const deleteHelmRepo = (id: string) =>
  fetchJson<void>(`/helm/repos/${id}`, { method: "DELETE" });

export async function loginHelmRepo(id: string) {
  const res = await fetch(`${API_BASE_URL}/helm/repos/${encodeURIComponent(id)}/login`, {
    method: 'POST',
  });

  if (res.status === 204) return { ok: true }; // No Content

  if (!res.ok) {
    throw new Error(`Login failed: ${res.status} ${res.statusText}`);
  }

  const text = await res.text();        // ne pas faire res.json() direct
  if (!text) return { ok: true };       // 200 vide → OK

  try {
    return JSON.parse(text);            // 200 JSON → OK
  } catch {
    return { ok: true, raw: text };     // 200 non-JSON → toléré
  }
}


/**
 * Détermine si un dépôt nécessite (ou re-nécessite) une authentification.
 *
 * • anonymous  → jamais besoin de login
 * • basic|token
 *      - si authInvalid === true => besoin d’un nouveau login
 *      - si authInvalid === false => on considère déjà loggé
 *      - undefined                => on ne sait pas ⇒ demande login
 */
export function needsLogin(repoId: string, repos: HelmRepo[]): boolean {
  const repo = repos.find((r) => r.id === repoId);
  if (!repo) return false; // dépôt inconnu ⇒ on laisse passer

  if (repo.authMode === 'anonymous') return false;

  return repo.authInvalid !== false; // true ou undefined  ⇒ login
}

/* ------------------------------------------------------------------ */
/* 2) loginRepo                                                       */
/* ------------------------------------------------------------------ */

/**
 * Demande au backend de (re-)faire le login / la synchro du dépôt.
 * Les identifiants sont récupérés depuis la base Ambari.
 *
 * @param repoId identifiant du dépôt
 */
export async function loginRepo(repoId: string) {
  const url = `${API_BASE_URL}/helm/repos/${encodeURIComponent(repoId)}/login`;

  const res = await fetch(url, { method: 'POST' });
  if (!res.ok) throw new Error(`Login repo failed: ${res.status}`);
  return res.json().catch(() => ({}));      // la plupart du temps {}, mais on garde homogène
}

/**
 * Désinstalle une release Helm.
 *
 * @param name nom de la release
 * @param namespace namespace de la release
 * @returns 
 */

export async function uninstallHelm(name: string, namespace: string) {
  const url = `${API_BASE_URL}/helm/release/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`;
  const res = await fetch(url, { method: 'DELETE' });
  if (!res.ok) throw new Error(`Uninstall failed: ${res.status}`);
  // backend returns empty body → don't .json()
  return;
}

/**
 *  Met à jour une release Helm.
 *  Si `chartRef` est fourni, il sera utilisé pour mettre à jour le chart.
 *  Si `repoId` est fourni, il sera utilisé pour trouver le chart dans le dépôt.
 *  Si `chartName` et `version` sont fournis, ils seront utilisés
 *  pour spécifier le chart et sa version.
 *  Si `reuseValues` est `true`, les valeurs existantes seront réutilisées.
 *  Si `timeoutSeconds` est fourni, il sera utilisé pour définir le délai d'attente.
 * @param payload payload pour l'upgrade d'une release Helm
 * @param payload.releaseName nom de la release à mettre à jour
 * @param payload.namespace namespace de la release
 * @param payload.chartRef référence du chart (nom ou nom@version)
 * @returns 
 */
export async function upgradeHelm(
  payload: { chart: string; releaseName: string; namespace: string; values: any; serviceKey?: string },
  params: URLSearchParams = new URLSearchParams()
) {
  const url = `${API_BASE_URL}/helm/upgrade?${params.toString()}`;
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error(`Upgrade failed: ${res.status}`);
  return res.json().catch(() => ({}));
}

export async function submitHelmDeploy(payload: {
  chart: string; releaseName: string; namespace: string; values: any; serviceKey?: string;
}, params: URLSearchParams = new URLSearchParams()) {
  const url = `${API_BASE_URL}/commands/helm/deploy?${params.toString()}`;
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type':'application/json' },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error(`Submit failed: ${res.status}`);
  return res.json() as Promise<{id: string}>;
}

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

export async function getCommandStatus(id: string) {
  const res = await fetch(`${API_BASE_URL}/commands/${id}`);
  if (!res.ok) throw new Error(`Status failed: ${res.status}`);
  return res.json() as Promise<CommandStatus>;
}