// ui/src/api/client.ts
import { getMockClusterStats, getMockComponentStatuses, getMockClusterEvents, getMockNodes, getMockHelmReleases, getChartsJSON, getMockHelmRepos, getMockCommandStatus, getMockCommands } from './mock';
import { getMockSecurityConfig, getMockPermissions } from './mock';
import type {ClusterService} from '../types/ServiceTypes';
import type {HelmRepo, UserPermissions} from '../types';
import type { KubeNamespace, KubePod, KubeService, KubeEvent } from '../types/KubeTypes';
// Dev-mode flag: Vite statically replaces process.env.NODE_ENV at build time, and jest provides it at
// run time — unlike import.meta.env, which is a syntax error under CommonJS test transforms.
declare const process: { env: Record<string, string | undefined> } | undefined;
const IS_DEV: boolean = (() => { try { return typeof process !== 'undefined' && !!process.env && process.env.NODE_ENV === 'development'; } catch { return false; } })();

/**
 * Resolve API base from current view URL so bumps to the view version or instance
 * do not break the frontend. Falls back to the known default if parsing fails.
 */
const resolveApiBase = (): string => {
  // Keep the version equal to view.xml's <version>: ViewXmlMigrationContractTest checks it.
  const fallback = `/api/v1/views/K8S-VIEW/versions/1.0.0.10/instances/K8S_VIEW_INSTANCE/resources/api`;
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

/**
 * Ambari refuses a state-changing call to its REST API (POST, PUT, DELETE) that lacks this header,
 * with 400 "CSRF protection is turned on". The header is what tells a call made by this UI apart from
 * a form that another web site makes the browser submit with the operator's session: such a form
 * cannot add custom headers.
 */
export const CSRF_HEADER = 'X-Requested-By';
const CSRF_HEADER_VALUE = 'ambari';

/**
 * Returns a copy of the request options whose headers carry {@link CSRF_HEADER}. The headers the
 * caller set are kept, whatever form they were given in, and so is the caller's own CSRF header.
 */
export function withCsrfHeader(init: RequestInit = {}): RequestInit {
  const headers: Record<string, string> = {};
  const given = init.headers;
  if (given) {
    if (typeof Headers !== 'undefined' && given instanceof Headers) {
      given.forEach((value, name) => { headers[name] = value; });
    } else if (Array.isArray(given)) {
      given.forEach(([name, value]) => { headers[name] = value; });
    } else {
      Object.assign(headers, given);
    }
  }
  const csrfHeader = CSRF_HEADER.toLowerCase();
  if (!Object.keys(headers).some((name) => name.toLowerCase() === csrfHeader)) {
    headers[CSRF_HEADER] = CSRF_HEADER_VALUE;
  }
  return { ...init, headers };
}

/**
 * fetch() for every call to the Ambari REST API, API_BASE_URL included. Going through one function
 * means no call can miss the CSRF header; a jest test fails on any raw fetch() added elsewhere.
 */
export function apiFetch(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
  return fetch(input, withCsrfHeader(init));
}

/**
 * When the Ambari session expires (e.g. after an ambari-server restart) the backend rejects
 * requests with 401, or 403 "missing authentication token". Ambari's own UI redirects to its
 * login page in that case; the view must do the same instead of surfacing a raw error and
 * getting stuck. Redirect the TOP window (the view is embedded in the Ambari page) to Ambari's
 * login route so the operator re-authenticates. Guarded so a burst of concurrently-failing
 * requests (e.g. the dashboard/ops-modal pollers) only triggers a single navigation.
 *
 * Returns true when it handled an auth-expiry status (caller should stop / throw a benign error).
 * A 403 is only treated as auth-expiry when its body looks like an authentication failure, so a
 * legitimate application 403 (RBAC etc.) is NOT mistaken for a logged-out session.
 */
let authRedirecting = false;
export function handleAuthExpiry(status: number, body?: string): boolean {
  const b = (body || '').toLowerCase();
  // A rejected CLUSTER token (backend marks it "K8S_UNAUTHORIZED") is NOT an Ambari-session expiry:
  // it means the stored kube/OpenShift credentials went stale, not that the operator is logged out of
  // Ambari. Never bounce them to the Ambari login for that — it is surfaced as a "disconnected" cluster
  // badge and (for openshift-login) auto-healed server-side instead.
  if (b.includes('k8s_unauthorized')) return false;
  // Likewise a refusal of the cluster for lack of rights ("K8S_FORBIDDEN"): its text often names a token or
  // authentication (serviceaccounts/token, authentication.k8s.io) without the Ambari session being at fault.
  if (b.includes('k8s_forbidden')) return false;
  const isAuthExpiry =
    status === 401 ||
    (status === 403 && (b.includes('authentication') || b.includes('token') || b.includes('not authenticated') || b.includes('csrf')));
  if (!isAuthExpiry) return false;
  if (!authRedirecting) {
    authRedirecting = true;
    try {
      (window.top || window)!.location.assign('/#/login');
    } catch {
      window.location.assign('/#/login');
    }
  }
  return true;
}

/**
 * Lightweight session heartbeat: pings a cheap authenticated endpoint purely to detect an expired
 * Ambari session and trigger the login redirect (via handleAuthExpiry) — the way the main Ambari
 * dashboard continuously polls and bounces to login. Runs on every page (AppLayout), never throws,
 * and a network blip is ignored (only a real 401 / auth-403 redirects).
 */
export const pingSession = async (): Promise<void> => {
  try {
    const res = await apiFetch(`${API_BASE_URL}/cluster/configured`, { credentials: 'include', cache: 'no-store' });
    if (!res.ok) {
      handleAuthExpiry(res.status, await res.text().catch(() => ''));
    }
  } catch {
    /* transient network error — not an auth failure, ignore */
  }
};

/**
 * The signed-in user's KDPS role and what it allows (GET /users/me/permissions). The development server has no
 * backend, so it gets the admin mock there.
 */
export const getCurrentUserPermissions = async (): Promise<UserPermissions> => {
  if (IS_DEV) {
    return getMockPermissions('ADMIN');
  }
  const response = await apiFetch(`${API_BASE_URL}/users/me/permissions`, {
    credentials: 'include',
    cache: 'no-store',
    headers: { 'X-Requested-By': 'ambari' },
  });
  return handleApiResponse(response);
};

// Backend uses single-L "CANCELED"; keep backward compatibility with old "CANCELLED"
export type CommandState = 'PENDING'|'RUNNING'|'SUCCEEDED'|'FAILED'|'CANCELLED'|'CANCELED';

/**
 * What a GitOps deploy does in Git, as the backend words it: the answer to the deploy carries the mode and the branch
 * the request names, the operation's result (result.gitOps) the commit, the pull request and whether Git
 * already held the change once its commit step ran.
 */
export type GitOpsDeployResult = {
  mode: 'FLUX_GITOPS';
  /** The commit holding the change; the one Git already held when nothing was committed. */
  commit?: string;
  /** The branch the change goes to: the branch Flux reconciles, or the pull request's. */
  branch?: string;
  /** The pull request of the change, when one was opened. */
  prUrl?: string;
  /** Its state when known: open, merged, closed. */
  prState?: string;
  /** Git already held the change, so nothing was committed. Absent until the commit step ran. */
  noChanges?: boolean;
};

/** The answer to an accepted deploy: the operation to follow, the deploy's mode and, for GitOps, what it does in Git. */
export type DeployAccepted = {
  id: string;
  mode?: 'DIRECT_HELM' | 'FLUX_GITOPS';
  gitOps?: GitOpsDeployResult;
};

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

export interface ReplayableAction {
  key: string;
  label: string;
  endpoint: string;
  skipIfValueEmpty?: string;
  /** Ask the operator to confirm when this value of the deployed release is not true. */
  warnUnlessValueTrue?: { path: string; message: string };
}

export interface StackServiceDef {
  name: string;
  label: string;
  chart: string;
  description: string;
  form: any[]; // FormField[]
  replayableActions?: ReplayableAction[];
  /** Values an earlier version of the definition wrote, left out of what an upgrade sends (see removeRetiredValues). */
  retiredValues?: Record<string, unknown>;
}

export interface StackConfig {
  name: string;
  description: string;
  properties: StackProperty[];
}

export const getGlobalConfigs = async (): Promise<StackConfig[]> => {
  if (IS_DEV) {
    return (await import('./mock')).getStackConfigsMock('GLOBAL') as any;
  }
  const res = await apiFetch(`./api/v1/globals/configurations`);
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
    groupRdnAttribute?: string;
    groupAuthPattern?: string;
    referral?: string;
    startTls?: boolean;
    adUrl?: string;
    adBaseDn?: string;
    adBindDn?: string;
    adBindPassword?: string;
    adUserSearchFilter?: string;
    adDomain?: string;
    /** On a read: whether a bind password is stored (the password itself comes masked). */
    bindPasswordSet?: boolean;
    /** On a read: whether an Active Directory bind password is stored. */
    adBindPasswordSet?: boolean;
  };
  oidc?: {
    issuerUrl?: string;
    clientId?: string;
    clientSecret?: string;
    /** External source only: the audience the provider puts in its access tokens (blank = the client id). */
    audience?: string;
    /** On a read: whether a client secret is stored (the secret itself comes masked). */
    clientSecretSet?: boolean;
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

// Workloads browsing helpers (namespaces, pods, services, logs)
export const getNamespaces = async (): Promise<KubeNamespace[]> => {
  const res = await apiFetch(`${API_BASE_URL}/workloads/namespaces`, { credentials: 'include' });
  return handleApiResponse(res);
};

export const getPods = async (ns: string, labelSelector?: string): Promise<KubePod[]> => {
  const params = new URLSearchParams();
  if (labelSelector) params.set('labelSelector', labelSelector);
  const res = await apiFetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/pods?${params.toString()}`, { credentials: 'include' });
  return handleApiResponse(res);
};

export const getServices = async (ns: string, labelSelector?: string): Promise<KubeService[]> => {
  const params = new URLSearchParams();
  if (labelSelector) params.set('labelSelector', labelSelector);
  const res = await apiFetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/services?${params.toString()}`, { credentials: 'include' });
  return handleApiResponse(res);
};

/** Row shape from /workloads/deployments — kept loose because the UI shows
 *  raw fields. Backend fills the same keys for both namespace-scoped and
 *  cluster-wide lists. */
export interface DeploymentRow {
  namespace: string;
  name: string;
  replicas: number | null;
  readyReplicas: number | null;
  availableReplicas: number | null;
  image: string | null;
  creationTimestamp: string | null;
}

export const listDeployments = async (namespace?: string): Promise<DeploymentRow[]> => {
  const qs = namespace && namespace !== '*' ? `?namespace=${encodeURIComponent(namespace)}` : '';
  const res = await apiFetch(`${API_BASE_URL}/workloads/deployments${qs}`, { credentials: 'include' });
  return handleApiResponse(res);
};

export interface ConfigMapRow {
  namespace: string;
  name: string;
  keys: number;
  bytes: number;
  creationTimestamp: string | null;
}

export const listConfigMaps = async (namespace?: string): Promise<ConfigMapRow[]> => {
  const qs = namespace && namespace !== '*' ? `?namespace=${encodeURIComponent(namespace)}` : '';
  const res = await apiFetch(`${API_BASE_URL}/workloads/configmaps${qs}`, { credentials: 'include' });
  return handleApiResponse(res);
};

export interface IngressRow {
  namespace: string;
  name: string;
  ingressClass: string | null;
  hosts: string[];
  tlsSecrets: number;
  address: string | null;
  creationTimestamp: string | null;
}

export const listIngresses = async (namespace?: string): Promise<IngressRow[]> => {
  const qs = namespace && namespace !== '*' ? `?namespace=${encodeURIComponent(namespace)}` : '';
  const res = await apiFetch(`${API_BASE_URL}/workloads/ingresses${qs}`, { credentials: 'include' });
  return handleApiResponse(res);
};

// OpenShift Routes — the platform's edge object (charts emit these instead of Ingresses on OpenShift).
export interface RouteRow {
  namespace: string;
  name: string;
  host: string | null;
  path: string | null;
  url: string | null;
  service: string | null;
  port: string | number | null;
  tls: string | null;
  admitted: boolean;
  creationTimestamp: string | null;
}

export const listRoutes = async (namespace?: string): Promise<RouteRow[]> => {
  const qs = namespace && namespace !== '*' ? `?namespace=${encodeURIComponent(namespace)}` : '';
  const res = await apiFetch(`${API_BASE_URL}/workloads/routes${qs}`, { credentials: 'include' });
  return handleApiResponse(res);
};

export const getPodLogs = async (ns: string, pod: string, container?: string, tailLines?: number): Promise<string> => {
  const params = new URLSearchParams();
  if (container) params.set('container', container);
  if (tailLines) params.set('tailLines', tailLines.toString());
  const res = await apiFetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/pods/${encodeURIComponent(pod)}/log?${params.toString()}`, {
    credentials: 'include'
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Failed to fetch logs (${res.status})`);
  }
  return res.text();
};

export const getNamespaceEvents = async (ns: string): Promise<KubeEvent[]> => {
  const res = await apiFetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/events`, { credentials: 'include' });
  return handleApiResponse(res);
};

export const getPodEvents = async (ns: string, pod: string): Promise<KubeEvent[]> => {
  const res = await apiFetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/pods/${encodeURIComponent(pod)}/events`, { credentials: 'include' });
  return handleApiResponse(res);
};

export const describePod = async (ns: string, pod: string): Promise<string> => {
  const res = await apiFetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/pods/${encodeURIComponent(pod)}/describe`, { credentials: 'include' });
  if (!res.ok) throw new Error(await res.text());
  return res.text();
};

export const describeService = async (ns: string, svc: string): Promise<string> => {
  const res = await apiFetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/services/${encodeURIComponent(svc)}/describe`, { credentials: 'include' });
  if (!res.ok) throw new Error(await res.text());
  return res.text();
};

/**
 * Deletes a pod. Without `graceSeconds` the pod shuts down within its own grace period; a grace period of 0
 * force-deletes it and the server only accepts that together with `force`.
 */
export const deletePod = async (ns: string, pod: string, graceSeconds?: number, force?: boolean): Promise<void> => {
  const params = new URLSearchParams();
  if (graceSeconds != null) params.set('graceSeconds', graceSeconds.toString());
  if (force) params.set('force', 'true');
  const res = await apiFetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/pods/${encodeURIComponent(pod)}?${params.toString()}`, { method: 'DELETE', credentials: 'include' });
  if (!res.ok) throw new Error(await res.text());
};

export const restartPod = async (ns: string, pod: string): Promise<void> => {
  const res = await apiFetch(`${API_BASE_URL}/workloads/namespaces/${encodeURIComponent(ns)}/pods/${encodeURIComponent(pod)}/restart`, { method: 'POST', credentials: 'include' });
  if (!res.ok) throw new Error(await res.text());
};

/** The `code` of the 412 the server answers when it has no Ambari master key to encrypt credentials with. */
export const MASTER_KEY_UNAVAILABLE = 'MASTER_KEY_UNAVAILABLE';

/**
 * What to do when the server refused to store a credential because it has no Ambari master key, or `null` for any
 * other answer. A 412 without that code means the view has no cluster connection yet.
 */
export function masterKeyRefusal(status: number, text: string): string | null {
  if (status !== 412 || !text || !text.includes(MASTER_KEY_UNAVAILABLE)) return null;
  try {
    const body = JSON.parse(text);
    return body?.code === MASTER_KEY_UNAVAILABLE ? String(body.error || '') || null : null;
  } catch {
    return null;
  }
}

/** The code of a refusal on a protected cluster: the action needs the cluster's name, typed by the operator. */
export const PROTECTED_CLUSTER_NOT_CONFIRMED = 'PROTECTED_CLUSTER_NOT_CONFIRMED';

/** An uninstall, rollback or upgrade refused on a protected cluster until the operator types the cluster's name. */
export class ProtectedClusterError extends Error {
  /** The cluster whose name the operator types. */
  readonly cluster: { id: string; name: string };

  constructor(message: string, cluster: { id: string; name: string }) {
    super(message);
    this.name = 'ProtectedClusterError';
    this.cluster = cluster;
  }
}

/** The refusal on a protected cluster an answer carries, if it is one. */
const protectedClusterRefusal = (status: number, text: string): ProtectedClusterError | null => {
  if (status !== 409) return null;
  try {
    const body = JSON.parse(text);
    return body?.code === PROTECTED_CLUSTER_NOT_CONFIRMED && body.cluster
      ? new ProtectedClusterError(String(body.error || ''), body.cluster) : null;
  } catch {
    return null;
  }
};

/** A path of the API carrying the cluster's name the operator typed, when there is one. */
const confirmingCluster = (path: string, confirmCluster?: string): string =>
  !confirmCluster ? path : `${path}${path.includes('?') ? '&' : '?'}confirmCluster=${encodeURIComponent(confirmCluster)}`;

async function fetchJson<T = unknown>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const response = await apiFetch(`${API_BASE_URL}${path}`, {
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    ...options,
  });
  if (!response.ok) {
    const text = await response.text();
    if (handleAuthExpiry(response.status, text)) throw new Error('Session expired — redirecting to Ambari login');
    const noMasterKey = masterKeyRefusal(response.status, text);
    if (noMasterKey) throw new Error(noMasterKey);
    const unconfirmed = protectedClusterRefusal(response.status, text);
    if (unconfirmed) throw unconfirmed;
    throw new Error(`HTTP ${response.status} – ${text || response.statusText}`);
  }
  if (response.status === 204) return undefined as unknown as T; // No-content
  // Tolerate a successful response with an EMPTY body (e.g. a 200 OK from DELETE): calling
  // response.json() on "" throws "SyntaxError: unexpected end of data". Read the text first and only
  // parse when there's something to parse, so void endpoints resolve cleanly (and callers refresh).
  const body = await response.text();
  if (!body) return undefined as unknown as T;
  return JSON.parse(body) as T;
}

function fetchWithTimeout(input: RequestInfo, init: RequestInit = {}, timeoutMs = 60000): Promise<Response> {
  const abortController = new AbortController();
  const timeoutId = setTimeout(() => abortController.abort(), timeoutMs);
  return apiFetch(input, { ...init, signal: abortController.signal })
    .finally(() => clearTimeout(timeoutId));
}

const handleApiResponse = async (response: Response) => {
  if (response.ok) {
    return response.json();
  }
  const errorText = await response.text();
  if (handleAuthExpiry(response.status, errorText)) throw new Error('Session expired — redirecting to Ambari login');

  // A missing Ambari master key is answered 412 too, but the view may well be configured.
  const noMasterKey = masterKeyRefusal(response.status, errorText);
  if (noMasterKey) throw new Error(noMasterKey);

  // Reliably detect the "unconfigured" state from the backend
  if (response.status === 412 || (errorText && errorText.includes("not configured with a kubeconfig"))) {
    throw new Error('unconfigured');
  }
  throw new Error(errorText || `Request failed with status ${response.status}`);
};

export const deleteHelmRepo = (id: string) =>
  fetchJson<void>(`/helm/repos/${id}`, { method: "DELETE" });

// ---------------------------------------------------------------------------
// Platform contexts: named ODP environments (Atlas/Ranger/Kerberos endpoints +
// auth) that KDPS services integrate against. The MANAGED default is auto-seeded
// from the Ambari-managed cluster; EXTERNAL contexts are operator-defined.
// ---------------------------------------------------------------------------

export interface PlatformContext {
  id: string;
  name: string;
  kind: "MANAGED" | "EXTERNAL" | "REMOTE" | "CDP";
  clusterName?: string;
  description?: string;
  config?: Record<string, any>;
  secretKeys?: string[];
  // write-only: plaintext secrets keyed by name (e.g. { rangerAdminPassword: "..." })
  secrets?: Record<string, string>;
  createdAt?: string;
  updatedAt?: string;
}

export interface ResolvedContext {
  id: string;
  name: string;
  kind: string;
  clusterName?: string;
  atlasUrl?: string;
  atlasAuthMode?: string;
  /** Atlas signs users in against LDAP, also when file authentication (reported first in atlasAuthMode) is on too. */
  atlasLdapAuth?: boolean;
  atlasAclMode?: string;
  atlasManaged?: boolean;
  atlasRangerServiceName?: string;
  kerberosRealm?: string;
  rangerManaged?: boolean;
  rangerUrl?: string;
  rangerAdminUsername?: string;
  /** REMOTE only — the remote Ambari base URL (display; no creds). */
  remoteAmbariUrl?: string;
  /** REMOTE only — discovered remote-cluster info (Ambari version, running stack, last contact). */
  ambariVersion?: string;
  stackName?: string;
  stackVersion?: string;
  lastContactAt?: string;
  /** Generic <capability>.<field> → resolved value map (e.g. oidc.issuerUrl, hive.metastoreUri). */
  resolvedFields?: Record<string, string>;
  /** Names (<capability>.<field>) of secret fields that have a value set. */
  secretFieldsSet?: string[];
  /** Messages for the operator about how the context is stored, such as a value to review. */
  notices?: string[];
}

/** Result of a live "test connection & list clusters" probe against a remote Ambari. */
export interface RemoteProbeResult {
  ok: boolean;
  clusters?: string[];
  ambariVersion?: string;
  cmVersion?: string;    // Cloudera Manager product version (CDP probe)
  apiVersion?: string;   // CM REST API version (CDP probe)
  error?: string;
}

export interface ContextFieldDef {
  name: string;
  /** Key the value is stored under in the context config; defaults to name (see utils/contextFieldKeys). */
  configKey?: string;
  label?: string;
  type?: string;        // string | password | enum | boolean
  secret?: boolean;
  managedResolver?: string;
  appliesTo?: string;   // EXTERNAL | MANAGED
  default?: any;
  options?: string[];
  placeholder?: string;
  help?: string;
  group?: string;       // UI grouping bucket; falls back to capability label
}
export interface ContextCapabilitySchema {
  capability: string;
  label?: string;
  order?: number;
  fields: ContextFieldDef[];
}

export const getContextSchema = (): Promise<ContextCapabilitySchema[]> =>
  fetchJson<ContextCapabilitySchema[]>("/contexts/schema");

export const getContexts = (): Promise<PlatformContext[]> =>
  fetchJson<PlatformContext[]>("/contexts");

export const getContext = (id: string): Promise<PlatformContext> =>
  fetchJson<PlatformContext>(`/contexts/${encodeURIComponent(id)}`);

export const getResolvedContext = (id: string): Promise<ResolvedContext> =>
  fetchJson<ResolvedContext>(`/contexts/${encodeURIComponent(id)}/resolved`);

export const saveContext = (ctx: PlatformContext): Promise<PlatformContext> =>
  fetchJson<PlatformContext>("/contexts", {
    method: "POST",
    body: JSON.stringify(ctx),
  });

export const deleteContext = (id: string): Promise<void> =>
  fetchJson<void>(`/contexts/${encodeURIComponent(id)}`, { method: "DELETE" });

export interface PreflightCheck { component: string; ok?: boolean; skipped?: boolean; message?: string; detail?: string; }
export interface PreflightResult { context?: string; kind?: string; autoConfigureRemote?: boolean; ok?: boolean; checks?: PreflightCheck[]; error?: string; }
/** Verify a context's remote Ranger/Atlas are reachable + configured (used when the operator
 * disabled auto-configuration and set them up manually). Read-only. */
export const preflightContext = (id: string): Promise<PreflightResult> =>
  fetchJson<PreflightResult>(`/contexts/${encodeURIComponent(id)}/preflight`, { method: "POST" });

/** A context entry from the uploaded kubeconfig (which cluster this view instance can target). */
export interface KubeconfigContext {
  name: string;
  cluster?: string;
  namespace?: string;
  server?: string;
  current?: boolean;
  selected?: boolean;
}

/** Contexts available in the uploaded kubeconfig — for choosing which cluster this view targets. */
export const getKubeconfigContexts = (): Promise<KubeconfigContext[]> =>
  fetchJson<KubeconfigContext[]>("/cluster/contexts");

/** Persist the chosen kubeconfig context for this view instance (rebuilds the Kubernetes client). */
export const selectKubeconfigContext = (context: string): Promise<{ message: string }> =>
  fetchJson<{ message: string }>("/cluster/context", {
    method: "POST",
    body: JSON.stringify({ context }),
  });

export interface OpenShiftLogin {
  apiUrl: string;
  username: string;
  password: string;
  caData?: string;
  insecure?: boolean;
}

/**
 * Configure the view to connect to OpenShift with a username/password. The backend mints and
 * auto-renews an API token (oc-login flow), so it survives token expiry — for clusters where only
 * console login is available. Rebuilds the client; throws with the backend error on failure.
 */
export const saveOpenShiftLogin = (login: OpenShiftLogin): Promise<{ message: string }> =>
  fetchJson<{ message: string }>("/cluster/openshift-login", {
    method: "POST",
    body: JSON.stringify(login),
  });

/** Dry-run an OpenShift login (mint a token) without persisting. Returns {ok, message|error}. */
export const testOpenShiftLogin = (login: OpenShiftLogin): Promise<{ ok: boolean; message?: string; error?: string }> =>
  fetchJson<{ ok: boolean; message?: string; error?: string }>("/cluster/openshift-login/test", {
    method: "POST",
    body: JSON.stringify(login),
  });

/**
 * The ServiceAccount of the pod Ambari runs in, which KDPS can connect with when Ambari runs in the cluster it manages
 * (GET /cluster/in-cluster). Never carries the token.
 */
export interface InClusterServiceAccountStatus {
  /** Whether the pod has a ServiceAccount token, CA and API server address to connect with. */
  available: boolean;
  /** Why not, when it is not available. */
  reason?: string | null;
  /** Whether KDPS connects with it now. */
  active: boolean;
  authMode: string;
  /** system:serviceaccount:<namespace>:<name> */
  username?: string | null;
  serviceAccount?: string | null;
  namespace?: string | null;
  apiServer?: string;
}

/** Whether KDPS can connect with the Ambari pod's ServiceAccount, and as whom. */
export const getInClusterServiceAccount = (): Promise<InClusterServiceAccountStatus> =>
  fetchJson<InClusterServiceAccountStatus>("/cluster/in-cluster");

/**
 * Connect KDPS with the Ambari pod's ServiceAccount, for every user of the view (rebuilds the Kubernetes client and
 * checks it reaches the cluster). Throws with the backend error, e.g. when the pod has no ServiceAccount token.
 */
export const connectWithInClusterServiceAccount = (): Promise<{ message: string; username?: string | null; note?: string }> =>
  fetchJson<{ message: string; username?: string | null; note?: string }>("/cluster/in-cluster", { method: "POST" });

export interface ProxySettings {
  enabled: boolean;
  url: string;
  username: string;
  password?: string;
  noProxy: string;
  passwordSet?: boolean;
}

export interface PreflightCheck { name: string; group: string; allowed: boolean; remediation?: string; advisory?: boolean; note?: string; }
export interface PreflightResult { platform: string; namespace: string; allOk: boolean; checks: PreflightCheck[]; }

/** RBAC preflight (oc auth can-i) for the connected account against a target namespace. */
export const getPreflight = (namespace: string): Promise<PreflightResult> =>
  fetchJson<PreflightResult>(`/cluster/preflight?namespace=${encodeURIComponent(namespace)}`);

/** Whether the server found the Ambari master key KDPS encrypts the kubeconfig and every credential with. */
export interface MasterKeyStatus { available: boolean; message?: string; code?: string; }

/** Asked before anything is uploaded: without the master key, saving a credential is refused (412). */
export const getMasterKeyStatus = (): Promise<MasterKeyStatus> => fetchJson<MasterKeyStatus>("/cluster/master-key");

/** View-wide outbound proxy for reaching internet Helm/Git repos (Kubernetes API stays direct). */
export const getProxySettings = (): Promise<ProxySettings> => fetchJson<ProxySettings>("/cluster/proxy");
export const saveProxySettings = (s: ProxySettings): Promise<{ message: string }> =>
  fetchJson<{ message: string }>("/cluster/proxy", { method: "POST", body: JSON.stringify(s) });

/**
 * Live "test connection & list clusters" for a remote Ambari, before saving a REMOTE context.
 * Returns the clusters the remote Ambari manages + its version, or {ok:false, error} on failure.
 * The password is sent only to authenticate this probe and is never persisted.
 */
export const probeRemoteContext = (req: {
  remoteAmbariUrl: string;
  remoteUsername: string;
  remotePassword: string;
  verifySsl: boolean;
}): Promise<RemoteProbeResult> =>
  fetchJson<RemoteProbeResult>("/contexts/probe-remote", {
    method: "POST",
    body: JSON.stringify(req),
  });

/**
 * Live "test connection & list clusters" for a Cloudera Manager, used before a CDP context is
 * saved. The password authenticates only this probe and is never persisted.
 */
export const probeCdpContext = (req: {
  cmUrl: string;
  cmUsername: string;
  cmPassword: string;
  verifySsl: boolean;
}): Promise<RemoteProbeResult> =>
  fetchJson<RemoteProbeResult>("/contexts/probe-cdp", {
    method: "POST",
    body: JSON.stringify(req),
  });

/** A KDPS service-advisor recommendation for one enable/disable toggle. */
export interface ToggleRecommendation {
  field: string;
  recommend: boolean;
  reason: string;
}

/**
 * Ask the KDPS service advisor to recommend on/off defaults for the given advisor-tagged
 * fields, based on what the selected platform context resolves. Best-effort: the backend
 * always returns a (possibly empty) list, so callers can apply whatever comes back.
 */
export const getContextAdvice = (
  id: string,
  service: string,
  fields: { name: string; advisor: string }[]
): Promise<{ recommendations: ToggleRecommendation[] }> =>
  fetchJson(`/contexts/${encodeURIComponent(id)}/advice`, {
    method: "POST",
    body: JSON.stringify({ service, fields }),
  });

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
  const response = await apiFetch(`${API_BASE_URL}/helm/repos/${id}/check`, {
    method: 'GET',
    credentials: 'include'
  });
  return handleApiResponse(response);
}

export async function installMonitoring(repoId?: string, force?: boolean) {
  const qs = new URLSearchParams();
  if (repoId) qs.set('repoId', repoId);
  if (force) qs.set('force', 'true');
  const query = qs.toString() ? `?${qs.toString()}` : '';
  const response = await apiFetch(`${API_BASE_URL}/helm/monitoring/install${query}`, {
    method: 'POST',
    credentials: 'include'
  });
  return handleApiResponse(response);
}

export async function resetMonitoringCache() {
  const response = await apiFetch(`${API_BASE_URL}/helm/monitoring/reset`, {
    method: 'POST',
    credentials: 'include'
  });
  return handleApiResponse(response);
}


export const getClusterEvents = async () => {
  if (IS_DEV) {
    if (sessionStorage.getItem('isUnconfigured')) throw new Error('unconfigured');
    return getMockClusterEvents();
  }
  const response = await apiFetch(`${API_BASE_URL}/cluster/events`);
  return handleApiResponse(response);
};

/**
 * Fetches existing Ambari cluster services (e.g. Hive Metastore URI).
 * Calls GET /api/discovery/ambari?serviceType=...
 */
export const getClusterServices = async (serviceType: string): Promise<ClusterService[]> => {
    // No more mock. Call the backend.
    const params = new URLSearchParams({ serviceType });
    const response = await apiFetch(`${API_BASE_URL}/discovery/ambari?${params.toString()}`, {
      credentials: 'include'
    });
    return handleApiResponse(response);
};

/**
 * Discover monitoring stack (kube-prometheus-stack) and return namespace/release.
 */
/** A path of the API on a cluster: unchanged for the default cluster (none named, or named `default`). */
export const onCluster = (path: string, cluster?: string): string =>
  !cluster || cluster === 'default' ? path : `${path}${path.includes('?') ? '&' : '?'}cluster=${encodeURIComponent(cluster)}`;

export const getMonitoringDiscovery = async (cluster?: string): Promise<{
  namespace: string; release: string; url?: string; queryUrl?: string;
  serviceMonitorLabels?: Record<string, string>; warning?: string;
}> => {
  const response = await apiFetch(`${API_BASE_URL}${onCluster('/discovery/monitoring/prometheus', cluster)}`, { credentials: 'include' });
  return handleApiResponse(response);
};

/**
 * Discover an existing KEDA operator on the cluster (autoscaling), so the Trino deploy can reuse it
 * instead of installing a conflicting one. Detection is CRD/operator-based (finds it in any namespace,
 * incl. the OpenShift Custom Metrics Autoscaler in openshift-keda).
 */
export const getKedaDiscovery = async (cluster?: string): Promise<{ present: boolean; namespace?: string; release?: string; source?: string; message?: string }> => {
  const response = await apiFetch(`${API_BASE_URL}${onCluster('/discovery/keda', cluster)}`, { credentials: 'include' });
  return handleApiResponse(response);
};

export const getViewSettings = async (): Promise<any> => {
  const response = await apiFetch(`${API_BASE_URL}/configurations/settings`, { credentials: 'include' });
  if (!response.ok) {
    const txt = await response.text();
    throw new Error(txt || 'Failed to load settings');
  }
  return response.json();
};

/**
 * Saves the view settings. The answer carries a `warning` when the settings were saved but need the administrator's
 * attention (the keytab webhook's account kept while the webhook is still installed, for instance).
 */
export const saveViewSettings = async (settings: any): Promise<{ warning?: string }> => {
  const response = await apiFetch(`${API_BASE_URL}/configurations/settings`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings)
  });
  if (!response.ok) {
    const txt = await response.text();
    throw new Error(txt || 'Failed to save settings');
  }
  const txt = await response.text();
  try {
    const answer = txt ? JSON.parse(txt) : {};
    return typeof answer?.warning === 'string' ? { warning: answer.warning } : {};
  } catch {
    return {};
  }
};

export interface SecurityProfiles {
  defaultProfile?: string;
  profiles: Record<string, SecurityConfig>;
  /** Set on a read when some stored profiles could not be read and are missing from `profiles`. */
  incomplete?: boolean;
  /**
   * On a save, the profiles the page read before editing: only those left out of `profiles` are removed, so a
   * profile created since, or never shown, is kept.
   */
  loadedProfiles?: string[];
}

export const getSecurityConfig = async (): Promise<SecurityProfiles> => {
  if (IS_DEV) {
    return getMockSecurityConfig();
  }
  const res = await apiFetch(`${API_BASE_URL}/configurations/security`, { credentials: 'include' });
  if (!res.ok) {
    const txt = await res.text();
    throw new Error(txt || 'Failed to load security config');
  }
  return res.json();
};

export const saveSecurityConfig = async (cfg: SecurityProfiles): Promise<void> => {
  const res = await apiFetch(`${API_BASE_URL}/configurations/security`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(cfg || {})
  });
  if (!res.ok) {
    // The server names the profile it refused and why: too large to be saved, or still used by releases.
    const txt = await res.text();
    let reason = txt;
    try {
      reason = JSON.parse(txt)?.error || txt;
    } catch { /* not JSON: shown as it is */ }
    throw new Error(reason || 'Failed to save security config');
  }
};

export interface SecurityProfileUsage {
  releases: string[];
}

export const getSecuritySchema = async (): Promise<any> => {
  if (IS_DEV) {
    const { getMockSecuritySchema } = await import('./mock');
    return getMockSecuritySchema();
  }
  const res = await apiFetch(`${API_BASE_URL}/configurations/security/schema`, { credentials: 'include' });
  if (!res.ok) {
    const txt = await res.text();
    throw new Error(txt || 'Failed to load security schema');
  }
  return res.json();
};

export const getSecurityProfileUsage = async (profile: string): Promise<SecurityProfileUsage> => {
  if (!profile) {
    throw new Error('Profile name is required');
  }
  const res = await apiFetch(`${API_BASE_URL}/configurations/security/${encodeURIComponent(profile)}/usage`, { credentials: 'include' });
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
  const res = await apiFetch(`${API_BASE_URL}/configurations/security/${encodeURIComponent(profile)}`, {
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


/**
 * Fetches running Kubernetes services matching a specific label.
 * Calls GET /api/discovery/k8s?label=...
 */

export const getDiscoveredK8sServices = async (labelSelector: string, cluster?: string): Promise<ClusterService[]> => {
    // In IS_DEV you might want to return mock data,
    // otherwise call the real backend.
    if (IS_DEV) {
       console.log(`Mocking K8s discovery for label: ${labelSelector}`);
       return [
         { label: 'trino-test-1 (mock)', value: 'trino-test-1.default.svc.cluster.local' }
       ];
    }

    const params = new URLSearchParams({ label: labelSelector });
    if (cluster) params.set('cluster', cluster);
    // Note: ensure API_BASE_URL includes the /resources/api part as defined in your file
    const response = await apiFetch(`${API_BASE_URL}/discovery/k8s?${params.toString()}`);
    return handleApiResponse(response);
};

/**
 * Fetches Kubernetes Secrets for a "secret-discovery" form field. Two modes:
 *  - by LABEL (cross-namespace): the Company CA picker surfaces labelled Secrets in `ambari-pki`.
 *  - by NAMESPACE (label-less): list every user-selectable Secret in a namespace so an operator can
 *    pick a hand-created one (e.g. an external Kerberos keytab Secret that carries no KDPS label).
 * Calls GET /api/discovery/secrets?label=... or ?namespace=...
 */
export const getDiscoveredK8sSecrets = async (labelSelector?: string, namespace?: string, cluster?: string): Promise<ClusterService[]> => {
    if (IS_DEV) {
       console.log(`Mocking K8s Secret discovery (label=${labelSelector ?? ''}, namespace=${namespace ?? ''})`);
       return [];
    }
    const params = new URLSearchParams();
    if (labelSelector) params.set('label', labelSelector);
    if (namespace) params.set('namespace', namespace);
    if (cluster) params.set('cluster', cluster);
    const response = await apiFetch(`${API_BASE_URL}/discovery/secrets?${params.toString()}`);
    return handleApiResponse(response);
};

/** cert-manager.io ClusterIssuer/Issuer discovery (Ready=True by default). */
export const getDiscoveredClusterIssuers = async (includeNotReady = false, cluster?: string): Promise<ClusterService[]> => {
    if (IS_DEV) return [];
    const params = new URLSearchParams();
    if (includeNotReady) params.set('includeNotReady', 'true');
    if (cluster) params.set('cluster', cluster);
    const response = await apiFetch(`${API_BASE_URL}/discovery/cluster-issuers${params.toString() ? '?' + params : ''}`);
    return handleApiResponse(response);
};

/** external-secrets.io SecretStore/ClusterSecretStore discovery (Ready=True by default). */
export const getDiscoveredSecretStores = async (includeNotReady = false, cluster?: string): Promise<ClusterService[]> => {
    if (IS_DEV) return [];
    const params = new URLSearchParams();
    if (includeNotReady) params.set('includeNotReady', 'true');
    if (cluster) params.set('cluster', cluster);
    const response = await apiFetch(`${API_BASE_URL}/discovery/secret-stores${params.toString() ? '?' + params : ''}`);
    return handleApiResponse(response);
}

export const getClusterStats = async (forceRefresh = false) => {
  if (IS_DEV) {
    if (sessionStorage.getItem('isUnconfigured')) {
      throw new Error('unconfigured');
    }
    return getMockClusterStats();
  }
  const params = new URLSearchParams();
  if (forceRefresh) params.set('forceRefresh', 'true');
  const response = await apiFetch(`${API_BASE_URL}/cluster/stats?${params.toString()}`);
  return handleApiResponse(response);
};

export async function getCommandStatus(id: string) {
  if (IS_DEV) {
    return getMockCommandStatus(id);
  }
  // Explicit fetch keeps polling simple and avoids any header/caching surprises
  const response = await apiFetch(`${API_BASE_URL}/commands/${id}`, {
    credentials: 'include',
    cache: 'no-store',
  });
  if (!response.ok) throw new Error(`Status failed: ${response.status}`);
  return response.json() as Promise<CommandStatus>;
}

export async function listCommands(limit = 10, offset = 0) {
  if (IS_DEV) {
    return getMockCommands(limit, offset);
  }
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  const response = await apiFetch(`${API_BASE_URL}/commands?${params.toString()}`, {
    credentials: 'include',
    cache: 'no-store',
  });
  if (!response.ok) throw new Error(`List failed: ${response.status}`);
  return response.json() as Promise<CommandStatus[]>;
}

export async function getCommandLogs(id: string, offset = 0, limit = 65536) {
  const params = new URLSearchParams({ offset: String(offset), limit: String(limit) });
  const response = await apiFetch(`${API_BASE_URL}/commands/${id}/logs?${params.toString()}`, {
    credentials: 'include',
    cache: 'no-store'
  });
  if (!response.ok) throw new Error(`Logs failed: ${response.status}`);
  return response.json() as Promise<{ content: string; nextOffset: number; eof: boolean; size: number }>;
}

export async function listChildCommands(id: string) {
  const response = await apiFetch(`${API_BASE_URL}/commands/${id}/children`, {
    credentials: 'include',
    cache: 'no-store',
  });
  if (!response.ok) throw new Error(`List children failed: ${response.status}`);
  return response.json() as Promise<CommandStatus[]>;
}

export async function cancelCommand(id: string) {
  const response = await apiFetch(`${API_BASE_URL}/commands/${id}/cancel`, { method: 'POST' });
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(text || `Cancel failed: ${response.status}`);
  }
}

/**
 * Resume a FAILED operation from the step that failed: re-runs that sub-step and continues,
 * skipping the already-succeeded steps. Recovers from transient sub-step failures without
 * re-running the whole KDPS wizard flow.
 */
export async function resumeCommand(id: string) {
  const response = await apiFetch(`${API_BASE_URL}/commands/${id}/resume`, { method: 'POST' });
  if (!response.ok) {
    let msg = `Resume failed: ${response.status}`;
    try {
      const body = await response.json();
      if (body?.error) msg = body.error;
    } catch { /* fall back to text */
      const text = await response.text().catch(() => '');
      if (text) msg = text;
    }
    throw new Error(msg);
  }
}

export async function refreshDependencies() {
  const response = await apiFetch(`${API_BASE_URL}/commands/dependencies/refresh`, {
    method: 'POST',
    credentials: 'include'
  });
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(text || `Refresh failed: ${response.status}`);
  }
}

export const getComponentStatuses = async () => {
  if (IS_DEV) {
    if (sessionStorage.getItem('isUnconfigured')) throw new Error('unconfigured');
    return getMockComponentStatuses();
  }
  const response = await apiFetch(`${API_BASE_URL}/cluster/componentstatuses`);
  return handleApiResponse(response);
};

export const getHelmReleases = async (limit = 20, offset = 0, managedOnly = false) => {
    if (IS_DEV) {
        if (sessionStorage.getItem('isUnconfigured')) throw new Error('unconfigured');
        return getMockHelmReleases();
    }
    // managedOnly filters to KDPS-managed releases SERVER-SIDE (before pagination) so managed
    // releases that sort onto a later page are not hidden by a page-local client filter.
    const params = new URLSearchParams({ limit: String(limit), offset: String(offset), managedOnly: String(managedOnly) });
    const response = await apiFetch(`${API_BASE_URL}/helm/releases?${params.toString()}`, { credentials: 'include' });
    return handleApiResponse(response);
};

/**
 * Fetches the latest backend-aggregated status for a release via Flux/Helm backends.
 */
export const getReleaseStatus = async (namespace: string, releaseName: string, cluster?: string): Promise<HelmRelease> => {
  const response = await apiFetch(`${API_BASE_URL}${onCluster(`/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/status`, cluster)}`, {
    credentials: 'include',
    cache: 'no-store'
  });
  return handleApiResponse(response);
};

/**
 * Trigger an idempotent keytab regeneration workflow for a deployed release.
 * The backend schedules a background command and returns its id for tracking.
 */
export const regenerateReleaseKeytabs = async (namespace: string, releaseName: string, cluster?: string) => {
  const requestPath = `/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/actions/keytabs`;
  return fetchJson<{ id: string; href?: string }>(onCluster(requestPath, cluster), { method: 'POST' });
};

/**
 * Adopt a release into the KDPS view: stamps the KDPS managed-by label on its Helm release
 * Secret(s) so it appears in the Releases default filter. For releases installed before automatic
 * tagging existed (or by another tool). Idempotent; namespace-scoped.
 */
export const adoptRelease = async (namespace: string, releaseName: string, cluster?: string) => {
  const requestPath = `/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/actions/adopt`;
  return fetchJson<{ adopted: boolean; taggedSecrets: number }>(onCluster(requestPath, cluster), { method: 'POST' });
};

/**
 * Asks Flux to try a GitOps release again after it gave up on it (a first install that timed out, a prerequisite
 * fixed outside Git), without a new commit. Resolves once the request is on the HelmRelease; Flux then reports the
 * outcome in the release's status.
 */
export const retryReleaseReconcile = async (namespace: string, releaseName: string, cluster?: string) => {
  const requestPath = `/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/actions/retry-reconcile`;
  return fetchJson<{ requested: boolean; requestedAt: string }>(onCluster(requestPath, cluster), { method: 'POST' });
};

/**
 * Re-register the OIDC client in Keycloak for a deployed release.
 * Idempotent: updates the existing client if it already exists.
 */
export const registerReleaseOidcClient = async (namespace: string, releaseName: string, cluster?: string) => {
  const requestPath = `/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/actions/oidc`;
  return fetchJson<{ id: string; href?: string }>(onCluster(requestPath, cluster), { method: 'POST' });
};

/**
 * Why "Regenerate keytabs" (`keytabs`), "Re-register OIDC client" (`oidc`) and "Reapply Ranger repository" (`ranger`)
 * cannot run for a deployed release: the platform context it runs on cannot perform them, or it was deployed without
 * OIDC login. An action left out can run.
 */
export const getReleaseActionRefusals = async (namespace: string, releaseName: string, cluster?: string) => {
  const requestPath = `/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/actions/availability`;
  return fetchJson<{ refusals: Record<string, string> }>(onCluster(requestPath, cluster));
};

/**
 * Re-apply the Ranger repository configuration for a deployed release.
 * The backend replays the same install-time Ranger action without a Helm upgrade.
 */
export const reapplyReleaseRangerRepository = async (namespace: string, releaseName: string, cluster?: string) => {
  const requestPath = `/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/actions/ranger`;
  return fetchJson<{ id: string; href?: string }>(onCluster(requestPath, cluster), { method: 'POST' });
};

/** One Atlas-federation config-drift check result. */
export interface AtlasFederationCheckItem {
  id: string;
  ok: boolean;
  severity: 'critical' | 'warning' | string;
  detail: string;
  remediation?: string;
}
/** Report returned by the Atlas-federation config-drift check. */
export interface AtlasFederationCheck {
  namespace?: string;
  release?: string;
  ok: boolean;
  federationConfigured: boolean;
  /** How OpenMetadata signs in to Atlas: a user in Atlas's file user store, or an LDAP service account. */
  federationAuth?: 'file' | 'ldap';
  /** False when nothing is kept in Atlas for the release (LDAP service account): "Fix & restart Atlas" does not apply. */
  fixRestartAvailable?: boolean;
  restartPending?: boolean;
  federationUserActive?: boolean;
  checks: AtlasFederationCheckItem[];
}
/**
 * Read-only Atlas-federation config-drift check for a deployed release. Surfaces operator-side
 * Atlas changes (file auth disabled, federation user removed/renamed, password rotated, pending
 * Atlas restart) that would break a running OM federation. Mutates nothing.
 */
export const checkReleaseAtlasFederation = async (
  namespace: string,
  releaseName: string,
  cluster?: string,
): Promise<AtlasFederationCheck> => {
  const requestPath = `/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/actions/atlas-federation-check`;
  return fetchJson<AtlasFederationCheck>(onCluster(requestPath, cluster), { method: 'GET' });
};

/** Result of the one-click "Fix & restart Atlas" action — returns the Ambari request id to poll. */
export interface AtlasFixRestartResult {
  requestId: number;
  username?: string;
  atlasUrl?: string;
  configChanged?: boolean;
  message?: string;
}
/** Live status of an Ambari request (for the Fix & restart progress bar). */
export interface AmbariRequestProgress {
  requestId: number;
  status: string;
  progressPercent: number;
  completedTasks?: number;
  totalTasks?: number;
}
/**
 * Re-assert the Atlas federation config (idempotent) and restart ATLAS_SERVER. Returns the Ambari
 * request id; poll {@link getAmbariRequestProgress} until status is COMPLETED, then re-run the check.
 */
export const fixRestartAtlasFederation = async (
  namespace: string,
  releaseName: string,
  cluster?: string,
): Promise<AtlasFixRestartResult> => {
  const requestPath = `/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/actions/atlas-federation-fix-restart`;
  return fetchJson<AtlasFixRestartResult>(onCluster(requestPath, cluster), { method: 'POST' });
};
/** Poll an Ambari request's status + progress percent. */
export const getAmbariRequestProgress = async (
  namespace: string,
  releaseName: string,
  requestId: number,
  cluster?: string,
): Promise<AmbariRequestProgress> => {
  const requestPath = `/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/actions/ambari-request/${requestId}`;
  return fetchJson<AmbariRequestProgress>(onCluster(requestPath, cluster), { method: 'GET' });
};

/**
 * Generic dispatcher for service.json-declared replayable actions. The endpoint
 * path is the one the service definition surfaces (e.g. {@code actions/om-atlas-federation}),
 * so adding a new replayable post-deploy step doesn't require a UI change.
 */
export const triggerReplayableAction = async (
  namespace: string,
  releaseName: string,
  endpoint: string,
  cluster?: string,
) => {
  const requestPath = `/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/${endpoint}`;
  return fetchJson<{ id: string; href?: string }>(onCluster(requestPath, cluster), { method: 'POST' });
};

/**
 * Upgrade a deployed release to a new chart version in place. Backend preserves the
 * deployed values and only changes the chart version. Returns the background command id.
 */
export const upgradeReleaseChart = async (namespace: string, releaseName: string, version: string, cluster?: string,
                                          confirmCluster?: string) => {
  const requestPath = `/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/actions/chart-upgrade?version=${encodeURIComponent(version)}`;
  return fetchJson<{ id: string; href?: string }>(confirmingCluster(onCluster(requestPath, cluster), confirmCluster), { method: 'POST' });
};

/**
 * Roll back a deployed release to a specific Helm revision picked by the operator.
 * Returns 200 on success with the revision that was applied.
 */
export const rollbackReleaseToRevision = async (namespace: string, releaseName: string, revision: number, cluster?: string,
                                                confirmCluster?: string) => {
  const requestPath = `/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/actions/rollback?revision=${revision}`;
  return fetchJson<{ ok?: boolean; revision?: number }>(confirmingCluster(onCluster(requestPath, cluster), confirmCluster), { method: 'POST' });
};

export interface HelmHistoryEntry {
  revision: number;
  updated?: string;
  status?: string;
  chart?: string;
  app_version?: string;
  description?: string;
}

/**
 * Fetch the Helm revision history for a release so the UI can render the picker
 * modal that drives `rollbackReleaseToRevision`.
 */
export const getReleaseHistory = async (namespace: string, releaseName: string, cluster?: string) => {
  const requestPath = `/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/history`;
  return fetchJson<HelmHistoryEntry[]>(onCluster(requestPath, cluster));
};

/**
 * Per-host TLS state for a release. Returns one entry per Ingress × TLS Secret
 * combo describing source (k8s-view-self-signed / cert-manager / external-secrets / external),
 * status (valid / expiring-warning / expiring-soon / expired / no-tls / secret-missing),
 * issuer, expiry, SANs. Drives the TLS column + Renew button on the Releases page.
 */
export interface ReleaseTlsEntry {
  ingressName: string;
  namespace: string;
  secretName: string | null;
  hosts: string[];
  source?: 'k8s-view-self-signed' | 'cert-manager' | 'external-secrets' | 'external';
  status: 'valid' | 'expiring-warning' | 'expiring-soon' | 'expired' | 'no-tls' | 'secret-missing' | 'no-tls-crt' | 'no-cert-in-secret' | 'read-error';
  issuer?: string;
  subject?: string;
  notBefore?: string;
  notAfter?: string;
  daysUntilExpiry?: number;
  serial?: string;
  sans?: string[];
  error?: string;
  /** k8s-view-self-signed only: the CA the leaf was minted with (ambariInternal | companyUploaded). */
  caMode?: string;
  /** k8s-view-self-signed with a company CA: its registry name, lowercased and sanitized as a label. */
  caName?: string;
  /** external only: KDPS wrote this Secret from a certificate and key uploaded at deploy time. */
  uploaded?: boolean;
}
export const getReleaseTlsState = async (namespace: string, releaseName: string, cluster?: string): Promise<ReleaseTlsEntry[]> => {
  const requestPath = `/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/tls`;
  return fetchJson<ReleaseTlsEntry[]>(onCluster(requestPath, cluster));
};

/**
 * Cluster capability snapshot — drives the wizard's adaptive TLS dropdown
 * (cert-manager / ESO / OpenShift detection). Cached for 60 s on the backend.
 */
export interface ClusterCapabilities {
  platform: 'kubernetes' | 'openshift';
  openshift: { routeCrd: boolean };
  certManager: { installed: boolean; clusterIssuerCrd: boolean; certificateCrd: boolean };
  externalSecrets: { installed: boolean; secretStoreCrd: boolean; clusterSecretStoreCrd: boolean; externalSecretCrd: boolean };
  // Whether an Ingress would be served: always on OpenShift (Route), on Kubernetes only with an
  // ingress controller (IngressClass present). defaultClass is the one to write into the values.
  ingress?: { available: boolean; classes: string[]; defaultClass: string | null };
}
export const getClusterCapabilities = async (cluster?: string): Promise<ClusterCapabilities> => {
  return fetchJson<ClusterCapabilities>(onCluster('/cluster/capabilities', cluster));
};

/** A Kubernetes cluster KDPS connects to, as GET /clusters lists it; never a credential. */
export interface KubernetesCluster {
  id: string;
  name: string;
  description?: string;
  protected: boolean;
  default: boolean;
  configured: boolean;
  authMode?: string;
  /** KDPS connects without verifying the API server's certificate (kubeconfig or OpenShift login set so). */
  insecure?: boolean;
}

/** The clusters KDPS knows, the default one first. */
export const listKubernetesClusters = async (): Promise<KubernetesCluster[]> => fetchJson<KubernetesCluster[]>('/clusters');

/** Adds a cluster, or describes again one KDPS knows. Administrators only. */
export const saveKubernetesCluster = (id: string, cluster: { name: string; description?: string; protected: boolean }) =>
  fetchJson<KubernetesCluster>(`/clusters/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(cluster) });

/** Removes a cluster and forgets its connection; refused while KDPS has releases on it. Administrators only. */
export const removeKubernetesCluster = (id: string) =>
  fetchJson<{ message: string }>(`/clusters/${encodeURIComponent(id)}`, { method: 'DELETE' });

/** Whether KDPS reaches a cluster with its saved connection now; never throws for an unreachable cluster. */
export const getNamedClusterLiveness = (id: string): Promise<ClusterLiveness> =>
  fetchJson<ClusterLiveness>(`/clusters/${encodeURIComponent(id)}/liveness`);

/** Saves a kubeconfig as a cluster's connection. Administrators only. */
export const uploadClusterKubeconfig = async (id: string, kubeconfig: Blob): Promise<string> => {
  const response = await apiFetch(`${API_BASE_URL}/clusters/${encodeURIComponent(id)}/config`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/octet-stream' },
    credentials: 'include',
    body: kubeconfig,
  });
  const text = await response.text();
  if (!response.ok) {
    let reason = text;
    try { reason = JSON.parse(text)?.error || text; } catch { /* plain text */ }
    throw new Error(reason || `HTTP ${response.status}`);
  }
  try { return JSON.parse(text)?.message || 'Kubeconfig saved'; } catch { return text || 'Kubeconfig saved'; }
};

/** The contexts of a cluster's uploaded kubeconfig. Administrators only. */
export const getClusterKubeconfigContexts = (id: string): Promise<KubeconfigContext[]> =>
  fetchJson<KubeconfigContext[]>(`/clusters/${encodeURIComponent(id)}/contexts`);

/** The kubeconfig context KDPS connects to a cluster with. Administrators only. */
export const selectClusterKubeconfigContext = (id: string, context: string) =>
  fetchJson<{ message: string }>(`/clusters/${encodeURIComponent(id)}/context`, {
    method: 'POST',
    body: JSON.stringify({ context }),
  });

/** Saves an OpenShift login as a cluster's connection; another cluster than the default needs its CA or "insecure". */
export const saveClusterOpenShiftLogin = (id: string, login: OpenShiftLogin) =>
  fetchJson<{ message: string }>(`/clusters/${encodeURIComponent(id)}/openshift-login`, {
    method: 'POST',
    body: JSON.stringify(login),
  });

/** Connects to a cluster with the Ambari pod's ServiceAccount (only the cluster Ambari's pod runs in). */
export const connectClusterWithInClusterServiceAccount = (id: string) =>
  fetchJson<{ message: string; username?: string | null; note?: string }>(
    `/clusters/${encodeURIComponent(id)}/in-cluster`, { method: 'POST' });

/**
 * Live connectivity outcome from the backend `/cluster/liveness` probe. Unlike `isViewConfigured`
 * (which only says whether credentials were ever stored), this reflects whether those credentials
 * still authenticate against the cluster right now. `state` mirrors the backend `ConnectionHealth`.
 */
export interface ClusterLiveness {
  state: 'CONNECTED' | 'UNAUTHENTICATED' | 'UNREACHABLE' | 'UNCONFIGURED';
  message: string;
}

/**
 * Actively probes the cluster API to drive the honest connection badge. The endpoint always answers
 * 200 with a {@link ClusterLiveness} body (probe failures are reported in `state`, not as HTTP errors),
 * so this call itself does not throw for a dead token — the caller inspects `state`.
 */
export const getClusterLiveness = async (): Promise<ClusterLiveness> => {
  if (IS_DEV) {
    return { state: 'CONNECTED', message: 'dev mock' };
  }
  return fetchJson<ClusterLiveness>('/cluster/liveness');
};

export const getHelmRepos = () => {
  if (IS_DEV) {
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
  if (IS_DEV) {
    return "apiVersion: v1\nclusters:\n- name: mocked-cluster\n  cluster:\n    server: https://mock.server:6443";
  }

  const response = await apiFetch(`${API_BASE_URL}/cluster/config`);

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(errorText || 'Failed to fetch kubeconfig content');
  }
  return response.text();
};

export const getNodes = async (limit = 200, offset = 0) => {
  if (IS_DEV) {
    if (sessionStorage.getItem('isUnconfigured')) throw new Error('unconfigured');
    return { items: getMockNodes(), total: getMockNodes().length };
  }
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  const response = await apiFetch(`${API_BASE_URL}/nodes?${params.toString()}`);
  return handleApiResponse(response);
};

export const isViewConfigured = async () => {
  if (IS_DEV) {
    return !sessionStorage.getItem('isUnconfigured');
  }
  const response = await apiFetch(`${API_BASE_URL}/cluster/configured`);
  try {
    const data = await handleApiResponse(response);
    return data.message === "CONFIGURED";
  } catch (e) {
    return false;
  }
};

export async function loginHelmRepo(id: string) {
  const response = await apiFetch(`${API_BASE_URL}/helm/repos/${encodeURIComponent(id)}/login`, {
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

  const response = await apiFetch(url, { method: 'POST' });
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
  // secret is embedded in the JSON body (as plainSecret) — never in the URL
  // to prevent it appearing in server logs and browser history.
  fetchJson<void>("/helm/repos", {
    method: "POST",
    body: JSON.stringify({ ...repo, plainSecret: secret ?? null }),
  });

export async function submitHelmDeploy(payload: {
  chart: string;
  releaseName: string;
  namespace: string;
  values: any;
  serviceKey?: string;
  securityProfile?: string;
  truststoreRefs?: string[];
  repoId?: string;
  deploymentMode?: string;
  git?: any;
  stackConfigOverrides?: Record<string, any>;
  secretName?: string;
  endpoints?: any;
  mounts?: any;
  tls?: any;
  ingressTlsUpload?: any;
  ingressTlsSelfSign?: any;
  ingressTlsCertManager?: any;
  ingressTlsExternalSecret?: any;
  // Lets the ingress certificate above overwrite a Secret KDPS did not mint (an uploaded or the operator's own
  // certificate). Sent only once the operator confirmed the replacement; never overrides cert-manager or ESO.
  replaceExistingTls?: boolean;
  dependencies?: any;
  ranger?: any;
  requiredConfigMaps?: any;
  dynamicValues?: any;
  // Optional chart version, sent in the body and as the deploy's ?version= (helm --version). Blank ⇒ a
  // redeploy keeps the version the release runs; a first install takes the newest chart (direct Helm) or the
  // service definition's version (GitOps). For OCI charts the version MUST go here, never tagged onto the chart ref.
  version?: string;
  // Snapshot of the wizard's raw form state (envelope keys stripped) so the backend can
  // read excludeFromValues form fields when resolving service-definition variables like
  // {{jupyterHost}} in OIDC redirectUriTemplate. See HelmDeployRequest.formValues.
  formValues?: Record<string, any>;
}, params: URLSearchParams = new URLSearchParams()) {
  // always propagate repoId as a query param to help the backend resolve the repository deterministically
  if (payload.repoId && !params.has('repoId')) {
    params.set('repoId', payload.repoId);
  }
  // chart-version override → helm --version (never appended to an OCI chart ref)
  if (payload.version && String(payload.version).trim() && !params.has('version')) {
    params.set('version', String(payload.version).trim());
  }
  const url = `${API_BASE_URL}/commands/helm/deploy?${params.toString()}`;
  const response = await apiFetch(url, {
    method: 'POST',
    headers: { 'Content-Type':'application/json' },
    body: JSON.stringify(payload),
  });
  if (!response.ok) {
    // Surface the server error body — without this we lose the actual reason
    // (e.g. "Chart version 1.13.1 does not match the range '>=1.13.2'") and
    // the operator only sees "Submit failed: 400" which is useless.
    let detail = '';
    let body: any = null;
    try {
      body = await response.clone().json();
      detail = body?.error ? `: ${body.error}` : '';
    } catch {
      try { detail = `: ${await response.text()}`; } catch { /* keep empty */ }
    }
    if (response.status === 409 && body?.code === PROTECTED_CLUSTER_NOT_CONFIRMED && body.cluster) {
      throw new ProtectedClusterError(String(body.error || ''), body.cluster);
    }
    const err = new Error(`Submit failed: ${response.status}${detail}`);
    (err as any).status = response.status;
    // A refusal the wizard can resolve by asking the operator (an existing ingress certificate Secret to replace)
    // carries its code and the Secret it names.
    if (body?.code) {
      (err as any).code = body.code;
      (err as any).namespace = body.namespace;
      (err as any).secretName = body.secretName;
    }
    throw err;
  }
  return response.json() as Promise<DeployAccepted>;
}

/** What an uninstall took back, and what it could not take back itself and leaves to an administrator. */
export interface UninstallNotes {
  /** The removal of a GitOps release waits for its pull request to be merged: the release keeps running until then. */
  pendingDelete?: boolean;
  /** The pull request removing the release. */
  pullRequestUrl?: string;
  /** What finishes the removal. */
  message?: string;
  /** What became of the release's Polaris access: the principal removed, or what is left to remove in Polaris. */
  polarisCleanup?: string;
  /** What became of the release's single sign-on: the Keycloak client and the Secrets holding its client secret removed, or what is left to remove by hand. */
  oidcCleanup?: string;
  /** OpenShift SCC grants that KDPS did not take back (made outside KDPS, or still used by another release), with the platform team's commands. */
  sccCleanup?: string;
  /** The Ranger account of the release's Atlas tag sync that KDPS could not disable, or a Ranger password to change. */
  tagSyncCleanup?: string;
  /** What became of the objects KDPS created for the release outside its chart (keytab, truststore, autoscaler account...). */
  kdpsObjectsCleanup?: KdpsObjectsCleanup;
  /** What became of what KDPS registered for the release outside Kubernetes (Ranger access, Atlas, Ranger TagSync, ...). */
  externalCleanup?: ExternalCleanupReport;
}

/** How far the uninstall of a release goes outside Kubernetes. */
export type ExternalCleanupMode = 'credentials' | 'all' | 'none';

/**
 * What the uninstall of a release removes, could not remove (`left`, with what to do) and leaves on purpose (`kept`) of
 * what KDPS registered for it outside Kubernetes: Keycloak clients, Ranger access and repositories, Kerberos principals,
 * the Atlas federation user, the OpenMetadata source of Ranger TagSync, Ambari view instances and Vault copies.
 */
export interface ExternalCleanupReport {
  removed: string[];
  left: KdpsObjectLeftover[];
  kept?: KdpsObjectLeftover[];
  note?: string;
  /** In a preview: false when KDPS recorded nothing for the release (one deployed by an earlier version). */
  recorded?: boolean;
}

/** An object KDPS created for a release that its uninstall could not remove, why, and the command that removes it. */
export interface KdpsObjectLeftover {
  object: string;
  reason: string;
  command?: string;
}

/** What the uninstall of a release removed of the objects KDPS created for it outside its chart, and what it left. */
export interface KdpsObjectsCleanup {
  /** The objects removed, as `<Kind>/<name>`. */
  removed: string[];
  left: KdpsObjectLeftover[];
  /** Objects left on purpose: one another release still uses, or one KDPS cannot prove it created. */
  kept?: KdpsObjectLeftover[];
  /** The same, as the operator reads it. */
  note?: string;
}

/**
 * The objects KDPS created for a release outside its chart, which its uninstall removes, as `<Kind>/<name>`: what the
 * uninstall confirmation lists. Empty when there are none or they cannot be listed.
 */
export async function getReleaseKdpsObjects(name: string, namespace: string, cluster?: string): Promise<string[]> {
  const url = `${API_BASE_URL}${onCluster(`/helm/release/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}/kdps-objects`, cluster)}`;
  const response = await apiFetch(url, { method: 'GET' });
  if (!response.ok) return [];
  const body: any = await response.json().catch(() => ({}));
  return Array.isArray(body?.objects) ? body.objects.map(String) : [];
}

/**
 * What the uninstall of a release would do outside Kubernetes with the given mode, for its confirmation to list. Null
 * when it cannot be read: the uninstall still does it, and its result says what was done.
 */
export async function getReleaseExternalRegistrations(
  name: string,
  namespace: string,
  mode: ExternalCleanupMode,
  cluster?: string,
): Promise<ExternalCleanupReport | null> {
  const url = `${API_BASE_URL}${onCluster(`/helm/release/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`
    + `/external-registrations?mode=${encodeURIComponent(mode)}`, cluster)}`;
  const response = await apiFetch(url, { method: 'GET' });
  if (!response.ok) return null;
  const body: any = await response.json().catch(() => null);
  if (!body || typeof body !== 'object') return null;
  return {
    removed: Array.isArray(body.removed) ? body.removed.map(String) : [],
    left: Array.isArray(body.left) ? body.left : [],
    kept: Array.isArray(body.kept) ? body.kept : [],
    note: typeof body.note === 'string' ? body.note : undefined,
    recorded: body.recorded === true,
  };
}

/**
 * Uninstalls a Helm release.
 *
 * @param name release name
 * @param namespace release namespace
 * @returns the notes the server returns; empty when there is nothing left to do
 */
export async function uninstallHelm(
  name: string,
  namespace: string,
  params: URLSearchParams = new URLSearchParams(),
  cluster?: string,
): Promise<UninstallNotes> {
  if (cluster && cluster !== 'default') params.set('cluster', cluster);
  const url = `${API_BASE_URL}/helm/release/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}?${params.toString()}`;
  const response = await apiFetch(url, { method: 'DELETE' });
  if (!response.ok) {
    // The server says why it refused and what to do: another operation on the release is running, or a GitOps
    // release must be deployed again with its Git token before KDPS can remove it from Git.
    let detail = '';
    try {
      const text = await response.text();
      const unconfirmed = protectedClusterRefusal(response.status, text);
      if (unconfirmed) throw unconfirmed;
      const body: any = JSON.parse(text);
      detail = body?.error ? `: ${body.error}` : '';
    } catch (e) {
      if (e instanceof ProtectedClusterError) throw e;
      /* keep the status only */
    }
    throw new Error(`Uninstall failed: ${response.status}${detail}`);
  }
  // The body is empty when there is nothing to report.
  return response.json().catch(() => ({}));
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
  const response = await apiFetch(url, {
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
  const response = await apiFetch(`${API_BASE_URL}/configurations`);
  if (!response.ok) throw new Error('Failed to fetch configurations');
  return response.json();
};

export const getManagedConfigContent = async (namespace: string, name: string, filename: string): Promise<string> => {
  const response = await apiFetch(`${API_BASE_URL}/configurations/${namespace}/${name}?filename=${filename}`);
  if (!response.ok) throw new Error('Failed to fetch content');
  const json = await response.json();
  return json.content || '';
};

export const saveManagedConfig = async (config: ManagedConfig) => {
  const response = await apiFetch(`${API_BASE_URL}/configurations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(config)
  });
  if (!response.ok) throw new Error('Failed to save config');
};

export const deleteManagedConfig = async (name: string, namespace: string) => {
  const response = await apiFetch(`${API_BASE_URL}/configurations/${namespace}/${name}`, {
    method: 'DELETE',
  });
  if (!response.ok) throw new Error('Failed to delete config');
};


/**
 * Fetches the current user-supplied values for a specific Helm release.
 * Maps to Backend: GET /api/v1/helm/{namespace}/{releaseName}/values
 */
export const getReleaseValues = async (namespace: string, releaseName: string, cluster?: string): Promise<any> => {
  const url = `${API_BASE_URL}${onCluster(`/helm/${encodeURIComponent(namespace)}/${encodeURIComponent(releaseName)}/values`, cluster)}`;
  
  const response = await apiFetch(url, {
    method: 'GET',
    headers: { 'Content-Type': 'application/json' },
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
  if (IS_DEV) {
    return (await import('./mock')).getStackServiceMock(name);
  }
  const res = await apiFetch(`${API_BASE_URL}/services/${name}`);
  if (!res.ok) throw new Error("Failed to load service definition");
  return res.json();
};

export const getStackConfigs = async (name: string): Promise<StackConfig[]> => {
  if (IS_DEV) {
    return (await import('./mock')).getStackConfigsMock(name) as any;
  }
  const res = await apiFetch(`${API_BASE_URL}/services/${name}/configurations`);
  if (!res.ok) throw new Error("Failed to load configs");
  return res.json();
};


export const getAvailableServices = async () => {
  if (IS_DEV) {
    // Use KDPS-style mocks in dev
    return getChartsJSON();
  }
  const response = await apiFetch(`${API_BASE_URL}/services`);
  return handleApiResponse(response);
}

/** One CRD as exposed by GET /crds. Shape mirrors KubernetesService.CrdSummary. */
export interface CrdSummary {
  name: string;
  group: string | null;
  kind: string | null;
  scope: string | null;
  versions: string[];
  established: boolean;
}

/** List every CRD registered on the cluster. Used by the Operators page. */
export const listCrds = async (): Promise<CrdSummary[]> => {
  return fetchJson<CrdSummary[]>('/crds');
};

/** {serviceName -> appVersion} for every catalog entry. Backend resolves via
 *  helm show chart in parallel and caches; first call is slow, subsequent are
 *  instant. Empty string for services whose chart couldn't be probed. */
export const getCatalogAppVersions = async (): Promise<Record<string, string>> => {
  return fetchJson<Record<string, string>>('/catalog/app-versions');
};

/** One parsed certificate inside a truststore's ca.crt PEM bundle. */
export interface TruststoreCert {
  subject: string;
  issuer: string;
  notAfter: string;
  daysUntilExpiry: number;
  serialNumber: string;
  isCa: boolean;
}

/** Snapshot of one <release>-truststore Secret. */
export interface TruststoreSummary {
  namespace: string;
  name: string;
  releaseName: string;
  caCount: number;
  jvmReady: boolean;
  pemReady: boolean;
  certificates: TruststoreCert[];
  /** Trusted by every release (default truststore), or by every release of its project for a project one. */
  isDefault?: boolean;
  /** Created via the Truststores tab (can be edited/deleted). */
  managed?: boolean;
  /**
   * For a truststore of the Truststores tab: `shared` when KDPS keeps it, any release on any cluster may trust it (its
   * `namespace` is then the truststore project of Cluster Settings, which names it); `project` in a project, only the
   * releases of that project may.
   */
  scope?: 'shared' | 'project';
}

/**
 * The shared truststores KDPS keeps, and the Secrets matching `*-truststore` on a cluster (the default one when none is
 * named), with parsed ca.crt summaries.
 */
export const listTruststores = async (namespace?: string, cluster?: string): Promise<TruststoreSummary[]> => {
  const qs = namespace && namespace !== '*' ? `?namespace=${encodeURIComponent(namespace)}` : '';
  return fetchJson<TruststoreSummary[]>(onCluster(`/truststores${qs}`, cluster));
};

/** The project that names the shared truststores, and whether the caller may change them. */
export interface TruststoreRegistryStatus {
  namespace: string;
  canWrite: boolean;
  /** Why the caller may not change them. */
  message?: string;
}

export const getTruststoreRegistry = async (): Promise<TruststoreRegistryStatus> =>
  fetchJson<TruststoreRegistryStatus>('/truststores/registry');

/**
 * Create a truststore from a public certificate PEM (trust anchor, no private key): a shared one without `namespace`,
 * a project one in `namespace`.
 */
export const createTruststore = async (
  name: string,
  caCertPem: string,
  makeDefault: boolean,
  description?: string,
  namespace?: string,
): Promise<{ namespace: string; name: string; caCount: number; isDefault: boolean; managed: boolean; scope: 'shared' | 'project' }> =>
  fetchJson(`/truststores`, {
    method: 'POST',
    body: JSON.stringify({ name, caCertPem, default: makeDefault, description, namespace }),
  });

/** Toggle the default flag on a managed truststore (multiple may be default). */
export const setTruststoreDefault = async (
  namespace: string,
  name: string,
  value: boolean,
): Promise<{ namespace: string; name: string; isDefault: boolean }> =>
  fetchJson(`/truststores/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}/default`, {
    method: 'PUT',
    body: JSON.stringify({ default: value }),
  });

/** Delete a managed truststore. */
export const deleteTruststore = async (namespace: string, name: string): Promise<void> =>
  fetchJson<void>(`/truststores/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`, {
    method: 'DELETE',
  });

// ---------------------------------------------------------------------------------------------
// Reusable Trino catalogs — defined once on the Trino Catalogs page, attached to releases in the
// deploy wizard (customCatalogs.refs). The release gets a snapshot of the properties; `usedBy`
// lists the releases currently holding one.
// ---------------------------------------------------------------------------------------------
export interface TrinoCatalog {
  id?: string;
  name: string;
  connectorName?: string;
  description?: string;
  propertiesText: string;
  createdBy?: string;
  createdAt?: string;
  updatedAt?: string;
  usedBy?: string[];
  /**
   * Properties that write a credential out instead of reading it with ${ENV:KEY} (a catalog saved before KDPS refused
   * them). Until it is edited, an Upgrade/Config of a release using it is refused. Response only.
   */
  literalCredentials?: string[];
}

export const getTrinoCatalogs = (): Promise<TrinoCatalog[]> =>
  fetchJson<TrinoCatalog[]>("/trino-catalogs");

export const saveTrinoCatalog = (catalog: TrinoCatalog): Promise<TrinoCatalog> =>
  fetchJson<TrinoCatalog>("/trino-catalogs", { method: "POST", body: JSON.stringify(catalog) });

export const deleteTrinoCatalog = (id: string): Promise<void> =>
  fetchJson<void>(`/trino-catalogs/${encodeURIComponent(id)}`, { method: "DELETE" });

// ---------------------------------------------------------------------------------------------
// Live Trino catalog management on a deployed release (runs as the logged-in operator; Ranger
// authorises). Backed by /helm/releases/{ns}/{release}/catalogs.
// ---------------------------------------------------------------------------------------------
export interface ReleaseCatalog {
  name: string;
  /** managed (KDPS-built hive/iceberg) | reusable | inline | unmanaged | builtin */
  source: 'managed' | 'reusable' | 'inline' | 'default' | 'unmanaged' | 'builtin' | string;
  connector?: string;
  live: boolean;
  persisted: boolean;
  reusableId?: string;
  properties?: string;
}
/**
 * How a catalog change was saved to the release: on a release deployed with Flux GitOps, by a deploy that commits the
 * values to Git, whose operation is named; nothing more for a Helm upgrade.
 */
export interface CatalogSaved {
  committedToGit?: boolean;
  commandId?: string;
}
const relCat = (ns: string, rel: string) => `/helm/releases/${encodeURIComponent(ns)}/${encodeURIComponent(rel)}/catalogs`;
export const listReleaseCatalogs = (ns: string, rel: string, cluster?: string): Promise<ReleaseCatalog[]> =>
  fetchJson<ReleaseCatalog[]>(onCluster(relCat(ns, rel), cluster));
export const createReleaseCatalog = (ns: string, rel: string, body: { name: string; properties: string; persist: boolean }, cluster?: string) =>
  fetchJson<{ name: string; connector?: string; live: boolean; persisted?: boolean } & CatalogSaved>(onCluster(relCat(ns, rel), cluster), { method: 'POST', body: JSON.stringify(body) });
export const dropReleaseCatalog = (ns: string, rel: string, name: string, forget = true, cluster?: string) =>
  fetchJson<{ name: string } & CatalogSaved>(onCluster(`${relCat(ns, rel)}/${encodeURIComponent(name)}?forget=${forget}`, cluster), { method: 'DELETE' });
export const testReleaseCatalog = (ns: string, rel: string, name: string, cluster?: string) =>
  fetchJson<{ schemas: string[] }>(onCluster(`${relCat(ns, rel)}/${encodeURIComponent(name)}/test`, cluster), { method: 'POST' });
/** Releases → "Ranger policy…": one Ranger policy (allow / mask / row filter) on a Trino release's repo or its tag service. */
export type RangerPolicyRequest = {
  target: 'resource' | 'tag';
  policyType: 0 | 1 | 2;
  resources?: { catalog?: string; schema?: string; table?: string; column?: string };
  tag?: string;
  tagServiceName?: string;
  users?: string;
  groups?: string;
  accessTypes?: string;
  maskType?: string;
  maskValueExpr?: string;
  maskConditionExpr?: string;
  rowFilterExpr?: string;
  policyName?: string;
  description?: string;
};
export type RangerPolicyResult = {
  rangerServiceName: string; policyName: string; policyType: number; accessTypes: string;
  via: 'ambari-server-action' | 'context-ranger-rest'; policyId?: number; requestId?: number; rangerUrl?: string;
};
export const createReleaseRangerPolicy = (ns: string, rel: string, body: RangerPolicyRequest, cluster?: string) =>
  fetchJson<RangerPolicyResult>(onCluster(`/helm/releases/${encodeURIComponent(ns)}/${encodeURIComponent(rel)}/actions/ranger-policy`, cluster),
    { method: 'POST', body: JSON.stringify(body) });

export const adoptReleaseCatalog = (ns: string, rel: string, name: string, cluster?: string) =>
  fetchJson<{ name: string; properties: string } & CatalogSaved>(onCluster(`${relCat(ns, rel)}/${encodeURIComponent(name)}/adopt`, cluster), { method: 'POST' });

// ---------------------------------------------------------------------------
// GitOps output profiles: how a GitOps deploy writes a release into Git (the built-in Flux layout, or file rules
// around a chart copied as published), and the repository preview of a deploy.
// ---------------------------------------------------------------------------

export type GitOpsFileMode = 'always' | 'createIfMissing';

export interface GitOpsFileRule {
  path: string;
  mode: GitOpsFileMode;
  content: string;
}

export interface GitOpsProfile {
  name: string;
  description?: string;
  /** 'flux' (the built-in HelmRelease layout) or 'files' (file rules). */
  kind: 'flux' | 'files';
  builtIn?: boolean;
  version?: number;
  chartPath?: string;
  variables: Record<string, string>;
  delivery: { mode: 'pullRequest' | 'commit'; branchPrefix?: string; tag?: string };
  files: GitOpsFileRule[];
  protect: string[];
}

export interface GitOpsPreviewFile {
  path: string;
  /** 'chart' (copied as published), 'file' (a rule of the profile) or 'repository' (not managed by the profile). */
  origin: 'chart' | 'file' | 'repository';
  change: 'new' | 'changed' | 'unchanged' | 'removed' | 'kept';
  mode?: GitOpsFileMode | null;
  editable: boolean;
  protectedPath: boolean;
  binary: boolean;
  content?: string | null;
  current?: string | null;
}

export interface GitOpsPreview {
  profile: string;
  repoUrl?: string | null;
  baseBranch?: string | null;
  chart: string;
  version: string;
  chartPath?: string | null;
  files: GitOpsPreviewFile[];
  notes: string[];
}

const gitOpsProfilePath = (name: string) => `/gitops/profiles/${encodeURIComponent(name)}`;

export const listGitOpsProfiles = () =>
  fetchJson<{ items: GitOpsProfile[]; canConfigure: boolean }>('/gitops/profiles');

export const saveGitOpsProfile = (profile: GitOpsProfile) =>
  fetchJson<GitOpsProfile>(gitOpsProfilePath(profile.name), { method: 'PUT', body: JSON.stringify(profile) });

export const deleteGitOpsProfile = (name: string) =>
  fetchJson<void>(gitOpsProfilePath(name), { method: 'DELETE' });

/**
 * The repository as a deploy would leave it, with nothing written. `request` is the deploy request as the wizard
 * submits it; `profile`, a profile not saved yet, is previewed in place of the one the request names.
 */
export const previewGitOpsProfile = (request: Record<string, any>, profile?: GitOpsProfile) =>
  fetchJson<GitOpsPreview>('/gitops/profiles/preview', {
    method: 'POST',
    body: JSON.stringify({ request, profile: profile || null }),
  });
