// ui/src/context/ClusterStatusContext.tsx
import React, { createContext, useState, useEffect, useContext } from 'react';
import { ClusterStats, ComponentStatus, ClusterEvent, ClusterNode, HelmRelease } from '../types';
import { isViewConfigured, getClusterStats, getComponentStatuses, getClusterEvents, getNodes, getHelmReleases } from '../api/client';

type Status = 'loading' | 'connected' | 'unconfigured' | 'error';

interface ClusterStatusContextType {
  status: Status;
  stats: ClusterStats | null;
  components: ComponentStatus[] | null;
  nodes: ClusterNode[] | null; // Ajout
  helmReleases: HelmRelease[] | null; // Ajout
  events: ClusterEvent[] | null;
  error: string | null;
  fetchData: () => void;
  checkViewIsConfigured?: () => boolean; // Optional method to check if the cluster is configured
}

const ClusterStatusContext = createContext<ClusterStatusContextType | undefined>(undefined);

export const ClusterStatusProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [status, setStatus] = useState<Status>('loading');
  const [viewConfigured, setViewConfigured] = useState<boolean | null>(null);
  const [stats, setStats] = useState<ClusterStats | null>(null);
  const [components, setComponents] = useState<ComponentStatus[] | null>(null);
  const [events, setEvents] = useState<ClusterEvent[] | null>(null);
  const [nodes, setNodes] = useState<ClusterNode[] | null>(null);
  const [helmReleases, setHelmReleases] = useState<HelmRelease[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const checkViewIsConfigured = async () => {
    setStatus('loading');
    setError(null);
    try {
    const isViewConfiguredWithConfig = await isViewConfigured();
    setViewConfigured(isViewConfiguredWithConfig);
    return viewConfigured;
    } catch (e: any) {
      setViewConfigured(false);
      if (e.message === 'unconfigured') {
        setStatus('unconfigured');
      } else {
        setStatus('error');
        setError(e.message);
      }
      return viewConfigured;
    }
  }


  const fetchData = async () => {
    setStatus('loading');
    setError(null);
    try {
      const [statsData, componentsData, eventsData, nodesData, helmReleasesData] = await Promise.all([
        getClusterStats(),
        getComponentStatuses(),
        getClusterEvents(),
        getNodes(),
        getHelmReleases(),
      ]);
      
      setStats(statsData);
      setComponents(componentsData);
      setEvents(eventsData);
      setNodes(nodesData);
      setHelmReleases(helmReleasesData);
      setStatus('connected');

    } catch (e: any) {
      if (e.message === 'unconfigured') {
        setStatus('unconfigured');
      } else {
        setStatus('error');
        setError(e.message);
      }
    }
  };

  useEffect(() => {
    if (viewConfigured){
      fetchData();
    }else {
      checkViewIsConfigured().then((configured) => {
        if (configured) {
          fetchData();
        } else {
          setStatus('unconfigured');
        }
      }).catch((e: any) => {
        setStatus('error');
        setError(e.message);
      });
    }
    
  }, []);

  return (
    <ClusterStatusContext.Provider value={{ status, stats, components, events, nodes, helmReleases, error, fetchData }}>
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
