// ui/src/api/client.ts
import { getMockClusterStats, getMockComponentStatuses, getMockClusterEvents } from './mock';

const API_BASE_URL = `/api/v1/views/K8S_VIEW/versions/1.0.0.0/instances/K8S_VIEW_INSTANCE/resources/api`;

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
  return handleApiResponse(response);
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