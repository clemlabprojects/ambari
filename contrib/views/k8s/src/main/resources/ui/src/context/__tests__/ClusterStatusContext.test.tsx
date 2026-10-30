/*
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
import { render, screen } from '@testing-library/react';

let mockViewedCluster: string | undefined;
jest.mock('../ViewedClusterContext', () => ({
  useViewedCluster: () => ({ cluster: mockViewedCluster, viewed: { name: mockViewedCluster ? 'Development' : 'Default cluster' } }),
}));
jest.mock('../../components/ServiceInstallationModal/capabilities', () => ({ invalidateCapabilities: jest.fn() }));
jest.mock('../../api/client', () => ({
  isViewConfigured: jest.fn(),
  getClusterStats: jest.fn(),
  getComponentStatuses: jest.fn(),
  getClusterEvents: jest.fn(),
  getNodes: jest.fn(),
  getHelmReleases: jest.fn(),
  getMonitoringDiscovery: jest.fn(),
  getClusterLiveness: jest.fn(),
}));

import * as client from '../../api/client';
import { ClusterStatusProvider, useClusterStatus } from '../ClusterStatusContext';

const Probe = () => {
  const { status, connectionMessage } = useClusterStatus();
  return <span>{`status=${status} message=${connectionMessage ?? ''}`}</span>;
};

/** Every call a cluster without a connection refuses, the way the server answers it (412). */
const noConnection = () => {
  const refuse = () => Promise.reject(new Error('unconfigured'));
  for (const call of ['getClusterStats', 'getComponentStatuses', 'getClusterEvents', 'getNodes', 'getHelmReleases']) {
    (client as any)[call].mockImplementation(refuse);
  }
  (client.getMonitoringDiscovery as jest.Mock).mockResolvedValue({});
  (client.getClusterLiveness as jest.Mock).mockResolvedValue({ state: 'UNCONFIGURED', message: 'No configuration' });
};

/**
 * The status the top bar and the Dashboard show is the viewed cluster's. Another cluster without a connection is
 * disconnected, with what to do: the setup screen stays the default cluster's.
 */
describe('ClusterStatusProvider', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (window as any).__clusterStatusInitRan = false;
    noConnection();
  });

  it('says another cluster has no connection yet, without turning the view into its setup screen', async () => {
    mockViewedCluster = 'dev';
    (client.isViewConfigured as jest.Mock).mockResolvedValue(true);

    render(<ClusterStatusProvider><Probe /></ClusterStatusProvider>);

    expect(await screen.findByText(/^status=disconnected message=KDPS has no connection to the cluster Development yet/))
      .toBeTruthy();
    expect(client.getClusterLiveness).toHaveBeenCalledWith('dev');
    expect(client.getNodes).not.toHaveBeenCalled();
  });

  it('reads the data of the viewed cluster once it answers', async () => {
    mockViewedCluster = 'dev';
    (client.isViewConfigured as jest.Mock).mockResolvedValue(true);
    (client.getClusterLiveness as jest.Mock).mockResolvedValue({ state: 'CONNECTED', message: '' });
    (client.getNodes as jest.Mock).mockResolvedValue({ items: [] });

    render(<ClusterStatusProvider><Probe /></ClusterStatusProvider>);

    expect(await screen.findByText('status=connected message=')).toBeTruthy();
    expect(client.getClusterStats).toHaveBeenCalledWith(true, 'dev');
    expect(client.getNodes).toHaveBeenCalledWith(200, 0, 'dev');
  });

  it('shows a cluster that does not answer disconnected as soon as the probe says so', async () => {
    // A dead API server: every call waits for the Kubernetes client to give up, the probe included.
    mockViewedCluster = undefined;
    (client.isViewConfigured as jest.Mock).mockResolvedValue(true);
    const never = () => new Promise(() => {});
    for (const call of ['getClusterStats', 'getComponentStatuses', 'getClusterEvents', 'getNodes', 'getHelmReleases']) {
      (client as any)[call].mockImplementation(never);
    }
    (client.getClusterLiveness as jest.Mock).mockResolvedValue({ state: 'UNREACHABLE', message: 'Get https://api:6443/version: EOF' });

    render(<ClusterStatusProvider><Probe /></ClusterStatusProvider>);

    expect(await screen.findByText('status=disconnected message=Get https://api:6443/version: EOF')).toBeTruthy();
    expect(client.getComponentStatuses).not.toHaveBeenCalled();
    expect(client.getMonitoringDiscovery).not.toHaveBeenCalled();
  });

  it('leaves the status to the cluster calls when the probe cannot be sent', async () => {
    mockViewedCluster = undefined;
    (client.isViewConfigured as jest.Mock).mockResolvedValue(true);
    (client.getClusterLiveness as jest.Mock).mockRejectedValue(new Error('Failed to fetch'));
    (client.getClusterStats as jest.Mock).mockResolvedValue({});
    (client.getNodes as jest.Mock).mockResolvedValue({ items: [] });

    render(<ClusterStatusProvider><Probe /></ClusterStatusProvider>);

    expect(await screen.findByText('status=connected message=')).toBeTruthy();
  });

  it('keeps the setup screen for the default cluster without a connection', async () => {
    mockViewedCluster = undefined;
    (client.isViewConfigured as jest.Mock).mockResolvedValue(false);

    render(<ClusterStatusProvider><Probe /></ClusterStatusProvider>);

    expect(await screen.findByText('status=unconfigured message=')).toBeTruthy();
  });
});
