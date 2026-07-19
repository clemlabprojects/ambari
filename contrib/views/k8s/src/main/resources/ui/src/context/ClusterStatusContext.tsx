/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// ui/src/context/ClusterStatusContext.tsx
import React, { createContext, useState, useEffect, useContext } from 'react';
import type { ServiceDefinition, ClusterStats, ComponentStatus, ClusterEvent, ClusterNode, HelmReleasePage } from '../types';
import { isViewConfigured, getClusterStats, getComponentStatuses, getClusterEvents, getNodes, getHelmReleases, getMonitoringDiscovery, getClusterLiveness } from '../api/client';
import { invalidateCapabilities } from '../components/ServiceInstallationModal/capabilities';

// 'disconnected' = the view IS configured but its stored credentials no longer authenticate against
// the cluster (token revoked/expired) or the API is unreachable. Distinct from 'unconfigured' (no
// credentials ever stored) and 'error' (an unexpected client/Ambari-session failure).
type Status = 'loading' | 'connected' | 'disconnected' | 'unconfigured' | 'error' | 'configuring';

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
  /** Human-readable reason for a 'disconnected' status (e.g. "credentials rejected (401)"). */
  connectionMessage?: string | null;
  /**
   * Increments every time the connection transitions back to 'connected' from a down state. Data that
   * was fetched while disconnected (e.g. the step-3 cluster-resource pickers) depends on this so it
   * re-fetches on reconnect instead of staying stale until a browser refresh.
   */
  connectionEpoch: number;
  /** Runs a live connectivity probe and updates the badge (connected / disconnected). Never throws. */
  checkLiveness: () => Promise<void>;
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
  const [connectionMessage, setConnectionMessage] = useState<string | null>(null);
  const [connectionEpoch, setConnectionEpoch] = useState<number>(0);

  // Mirror `status` into a ref so the periodic liveness probe can read the current value without
  // being re-created on every status change (keeps the 8s heartbeat interval stable).
  const statusRef = React.useRef<Status>('loading');
  React.useEffect(() => { statusRef.current = status; }, [status]);

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

      // Derive the badge HONESTLY. If any core call actually returned data, we are definitively
      // connected. If EVERY core call failed, do not blindly claim "connected" (the old bug) — run an
      // authoritative liveness probe to tell "cluster down / token dead" apart from a transient hiccup.
      const anyFulfilled = results.some((r) => r.status === 'fulfilled');
      if (anyFulfilled) {
        setConnectionMessage(null);
        setStatus((prev) => (prev === 'unconfigured' || prev === 'error') ? prev : 'connected');
      } else {
        try {
          const health = await getClusterLiveness();
          if (health.state === 'CONNECTED') {
            setConnectionMessage(null);
            setStatus('connected');
          } else if (health.state === 'UNCONFIGURED') {
            setStatus('unconfigured');
          } else {
            console.warn('All cluster calls failed and liveness probe reports', health.state, '-', health.message);
            setConnectionMessage(health.message);
            setStatus('disconnected');
          }
        } catch (probeErr) {
          console.warn('All cluster calls failed and the liveness probe itself failed', probeErr);
          setConnectionMessage('Cluster API is unreachable.');
          setStatus('disconnected');
        }
      }

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

  /**
   * Periodic, cheap liveness probe that keeps the connection badge honest between full data refreshes.
   * Called by the app-wide heartbeat (AppLayout). It:
   *  - flips the badge to 'disconnected' the moment the stored credentials stop authenticating, and
   *  - on RECOVERY (disconnected/error -> connected) invalidates the capability cache, bumps
   *    {@link connectionEpoch} (so step-3 pickers re-fetch), and pulls fresh cluster data — so the UI
   *    heals without the operator having to reload the browser.
   * Never throws: a probe/network failure leaves the current status untouched.
   */
  const checkLiveness = React.useCallback(async () => {
    const prev = statusRef.current;
    // Don't let a background probe stomp on the setup wizard / initial-load states.
    if (prev === 'unconfigured' || prev === 'configuring' || prev === 'loading') return;

    let health;
    try {
      health = await getClusterLiveness();
    } catch (e) {
      // Probe request to Ambari itself failed (not a cluster-auth signal) — ignore this tick.
      console.warn('Liveness probe request failed', e);
      return;
    }

    if (health.state === 'CONNECTED') {
      if (prev === 'disconnected' || prev === 'error') {
        console.info('Cluster connection restored — invalidating caches and refreshing dependent data.');
        setConnectionMessage(null);
        invalidateCapabilities();
        setConnectionEpoch((e) => e + 1);
        void fetchData(true);
      }
      setStatus('connected');
    } else if (health.state === 'UNCONFIGURED') {
      setStatus('unconfigured');
    } else {
      // UNAUTHENTICATED or UNREACHABLE.
      if (prev !== 'disconnected') {
        console.warn('Cluster connection lost:', health.state, '-', health.message);
      }
      setConnectionMessage(health.message);
      setStatus('disconnected');
    }
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
      connectionMessage,
      connectionEpoch,
      checkLiveness,
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
