// ui/src/context/ClusterStatusContext.tsx
import React, { createContext, useState, useEffect, useContext } from 'react';
import type { ServiceDefinition, ClusterStats, ComponentStatus, ClusterEvent, ClusterNode, HelmReleasePage } from '../types';
import { isViewConfigured, getClusterStats, getComponentStatuses, getClusterEvents, getNodes, getHelmReleases, getAvailableServices, getMonitoringDiscovery } from '../api/client';

type Status = 'loading' | 'connected' | 'unconfigured' | 'error' | 'configuring';

interface ClusterStatusContextType {
  status: Status;
  stats: ClusterStats | null;
  components: ComponentStatus[] | null;
  nodes: ClusterNode[] | null; // Addition
  helmReleases: HelmReleasePage | null; // Addition
  events: ClusterEvent[] | null;
  error: string | null;
  mainLoaderActive?: boolean; // Optional loader status
  fetchData: () => Promise<void>;
  refresh: () => Promise<void>;
  setClusterStatus: (status: Status) => void; // Optional setter for status
  checkViewIsConfigured?: () => boolean; // Optional method to check if the cluster is configured
  monitoringState?: { state?: string; message?: string };
}

const ClusterStatusContext = createContext<ClusterStatusContextType | undefined>(undefined);

export const ClusterStatusProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [status, setStatus] = useState<Status>('loading');
  const [availableServices, setAvailableServices] = useState<ServiceDefinition[] | null>(null);
  const [viewConfigured, setViewConfigured] = useState<boolean | null>(null);
  const [stats, setStats] = useState<ClusterStats | null>(null);
  const [components, setComponents] = useState<ComponentStatus[] | null>(null);
  const [events, setEvents] = useState<ClusterEvent[] | null>(null);
  const [nodes, setNodes] = useState<ClusterNode[] | null>(null);
  const [helmReleases, setHelmReleases] = useState<HelmReleasePage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [mainLoaderActive, setMainLoaderActive] = useState<boolean>(true);
  const [monitoringState, setMonitoringState] = useState<{ state?: string; message?: string }>({});

  const setClusterStatus = (newStatus: Status) => {
    setStatus(newStatus);
  }

  const checkViewIsConfigured = async () => {
    setStatus('loading');
    setError(null);
    try {
      const isConfigured = await isViewConfigured();
      console.log('DEBUG: View configured:', isConfigured);
      setViewConfigured(isConfigured);
      // IMPORTANT: do NOT fetch here. Let the caller decide.
      return isConfigured;
    } catch (e: any) {
      setStatus('error');
      setError(e.message);
      return false;
    }
  };

  const fetchData = React.useCallback(async (forceRefresh = false) => {
    console.log('DEBUG: Fetching cluster data...');
    setStatus('loading');
    setMainLoaderActive(true);
    setError(null);
    try {
      // Kick off stats but don't block the rest of the UI
      getClusterStats(forceRefresh)
        .then(setStats)
        .catch((e: any) => {
          if (e?.message === 'unconfigured') {
            setStatus('unconfigured');
          } else {
            console.warn('Stats fetch failed', e);
          }
        });

      const results = await Promise.allSettled([
        getComponentStatuses(),
        getClusterEvents(),
        getNodes(),
        getHelmReleases(),
      ]);

      const [componentsRes, eventsRes, nodesRes, helmRes] = results;
      if (componentsRes.status === 'fulfilled') setComponents(componentsRes.value);
      if (eventsRes.status === 'fulfilled') setEvents(eventsRes.value);
      if (nodesRes.status === 'fulfilled') {
        const nodesData = nodesRes.value as any;
        setNodes(nodesData?.items || nodesData || null);
      }
      if (helmRes.status === 'fulfilled') setHelmReleases(helmRes.value);

      // Consider connected if any core call succeeded and we are not marked unconfigured/error
      setStatus((prev) => (prev === 'unconfigured' || prev === 'error') ? prev : 'connected');

      // Update monitoring bootstrap state
      try {
        const disc = await getMonitoringDiscovery();
        setMonitoringState({ state: (disc as any)?.state, message: (disc as any)?.message });
      } catch (e:any) {
        // ignore; keep previous state
      }
    } catch (e: any) {
      if (e.message === 'unconfigured') {
        setStatus('unconfigured');
      } else {
        setStatus('error');
        setError(e.message);
      }
    } finally {
      setMainLoaderActive(false);
    }
  }, []);

  const refresh = React.useCallback(async () => {
    console.log('API CALL: DEBUG: Refreshing cluster data...');
    await fetchData(true);
  }, [fetchData]);


  useEffect(() => {
    // Guard against StrictMode double-invoke in development
    const didRun = (window as any).__clusterStatusInitRan ?? false;
    if (process.env.NODE_ENV !== 'production' && didRun) return;
    (window as any).__clusterStatusInitRan = true;

    const init = async () => {
      if (viewConfigured) {
        await refresh(); // call once
        return;
      }

      console.log('DEBUG: First Effect, Checking if view is configured...');
      const configured = await checkViewIsConfigured();
      if (configured) {
        console.log('DEBUG: View is configured, fetching data...');
        await refresh(); // call once
      } else {
        setStatus('unconfigured');
      }
    };

    void init();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []); // keep empty on purpose

  return (
    <ClusterStatusContext.Provider value={{
      mainLoaderActive,
      status,
      stats,
      components,
      events,
      nodes,
      helmReleases,
      error,
      monitoringState,
      fetchData,
      refresh,
      setClusterStatus }}>
      {children}
    </ClusterStatusContext.Provider>
  );
};

export const useClusterStatus = () => {
  const context = useContext(ClusterStatusContext);
  if (context === undefined) {
    throw new Error('useClusterStatus must be used within a ClusterStatusProvider');
  }
  return context;
};
