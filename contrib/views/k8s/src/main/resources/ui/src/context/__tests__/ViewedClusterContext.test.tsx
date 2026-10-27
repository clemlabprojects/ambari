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
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
jest.mock('../../api/client', () => ({ listKubernetesClusters: jest.fn() }));

import { listKubernetesClusters } from '../../api/client';
import ClusterSelector from '../../components/layout/ClusterSelector';
import { useTargetCluster } from '../TargetClusterContext';
import { ViewedClusterProvider, useViewedCluster } from '../ViewedClusterContext';

const defaultCluster = { id: 'default', name: 'Default cluster', protected: false, default: true, configured: true };
const prod = { id: 'prod', name: 'Production', protected: true, default: false, configured: true };

/** What a page reads: the cluster its requests name (none for the default cluster), and what discovery follows. */
const Probe = () => {
  const { cluster } = useViewedCluster();
  return <span>{`viewed=${cluster ?? 'default'} target=${useTargetCluster() ?? 'default'}`}</span>;
};

const view = () => render(<ViewedClusterProvider><ClusterSelector /><Probe /></ViewedClusterProvider>);

/** The top-bar choice of the cluster the Releases, Workloads, Nodes and Dashboard pages show. */
describe('ViewedClusterProvider', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
  });

  it('shows no choice while KDPS knows one cluster: everything stays on the default one', async () => {
    (listKubernetesClusters as jest.Mock).mockResolvedValue([defaultCluster]);

    view();

    await waitFor(() => expect(listKubernetesClusters).toHaveBeenCalled());
    expect(screen.queryByLabelText('cluster selector')).toBeNull();
    expect(screen.getByText('viewed=default target=default')).toBeTruthy();
  });

  it('views the cluster chosen, remembers it, and has discovery follow it', async () => {
    (listKubernetesClusters as jest.Mock).mockResolvedValue([defaultCluster, prod]);

    view();
    await userEvent.click(await screen.findByRole('combobox'));
    await userEvent.click(await screen.findByText('Production (protected)'));

    expect(await screen.findByText('viewed=prod target=prod')).toBeTruthy();
    expect(localStorage.getItem('kdps.cluster')).toBe('prod');
  });

  it('falls back to the default cluster when the one remembered is gone', async () => {
    localStorage.setItem('kdps.cluster', 'removed');
    (listKubernetesClusters as jest.Mock).mockResolvedValue([defaultCluster, prod]);

    view();

    await screen.findByRole('combobox');
    expect(screen.getByText('viewed=default target=default')).toBeTruthy();
  });
});
