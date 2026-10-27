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

import React from 'react';
import { listKubernetesClusters, type KubernetesCluster } from '../api/client';
import { TargetClusterContext } from './TargetClusterContext';

const STORAGE_KEY = 'kdps.cluster';

interface ViewedClusterCtx {
  /** The viewed cluster's id, for the requests that name it: undefined for the default cluster, whose requests do not. */
  cluster?: string;
  /** The viewed cluster, once the list is read. */
  viewed?: KubernetesCluster;
  /** Every cluster KDPS knows, the default one first. */
  clusters: KubernetesCluster[];
  /** Views another cluster, remembered in this browser. */
  setCluster: (id: string) => void;
  /** Reads the clusters again, after one was added, changed or removed. */
  reloadClusters: () => Promise<void>;
}

const Ctx = React.createContext<ViewedClusterCtx>({
  clusters: [], setCluster: () => undefined, reloadClusters: async () => undefined,
});

const remembered = (): string => {
  try { return localStorage.getItem(STORAGE_KEY) || 'default'; } catch { return 'default'; }
};

/**
 * The Kubernetes cluster the Releases, Workloads, Nodes and Dashboard pages show, chosen in the top bar once KDPS knows
 * more than one. Pages read it with {@link useViewedCluster}; what they discover through the capabilities of a cluster
 * follows it too. A cluster no longer known falls back to the default one.
 */
export const ViewedClusterProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [clusters, setClusters] = React.useState<KubernetesCluster[]>([]);
  const [id, setId] = React.useState<string>(remembered);

  const reloadClusters = React.useCallback(async () => {
    try {
      setClusters(await listKubernetesClusters() || []);
    } catch {
      setClusters([]);
    }
  }, []);
  React.useEffect(() => { void reloadClusters(); }, [reloadClusters]);

  const setCluster = React.useCallback((next: string) => {
    setId(next);
    try { localStorage.setItem(STORAGE_KEY, next); } catch { /* remembered for this page only */ }
  }, []);

  // Until the list is read, only the default cluster is viewed: a remembered one may have been removed.
  const viewed = clusters.find(c => c.id === id) || clusters.find(c => c.default);
  const cluster = viewed && !viewed.default ? viewed.id : undefined;
  const value = React.useMemo(() => ({ cluster, viewed, clusters, setCluster, reloadClusters }),
    [cluster, viewed, clusters, setCluster, reloadClusters]);
  return (
    <Ctx.Provider value={value}>
      <TargetClusterContext.Provider value={cluster}>{children}</TargetClusterContext.Provider>
    </Ctx.Provider>
  );
};

export const useViewedCluster = () => React.useContext(Ctx);
