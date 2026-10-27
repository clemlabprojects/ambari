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
import { render, screen, waitFor } from '@testing-library/react';

jest.mock('../../api/client', () => ({ getNodes: jest.fn() }));
jest.mock('../../context/ClusterStatusContext', () => ({
  useClusterStatus: () => ({ status: 'connected' }),
}));
let mockViewedCluster: string | undefined;
jest.mock('../../context/ViewedClusterContext', () => ({
  useViewedCluster: () => ({ cluster: mockViewedCluster }),
}));

import { getNodes } from '../../api/client';
import NodesPage from '../NodesPage';

/** Nodes page: an account limited to its own projects gets an explanation instead of an error. */
describe('NodesPage', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockViewedCluster = undefined;
  });

  it('lists the nodes of the cluster chosen in the top bar', async () => {
    mockViewedCluster = 'prod';
    (getNodes as jest.Mock).mockResolvedValue({ items: [], total: 0 });
    render(<NodesPage />);
    await waitFor(() => expect(getNodes).toHaveBeenCalledWith(50, 0, 'prod'));
  });

  it('says nodes are not visible to a restricted account', async () => {
    (getNodes as jest.Mock).mockResolvedValue({ items: [], total: 0, restricted: true });
    render(<NodesPage />);
    expect(await screen.findByText('Nodes are not visible to this account')).toBeTruthy();
  });

  it('lists the nodes of an account that may read them', async () => {
    (getNodes as jest.Mock).mockResolvedValue({
      items: [{ id: 'n1', name: 'worker-1', status: 'Ready', roles: ['worker'], cpuUsage: 0.5, memoryUsage: 0.25 }],
      total: 1,
    });
    render(<NodesPage />);
    await waitFor(() => expect(screen.getByText('worker-1')).toBeTruthy());
    expect(screen.queryByText('Nodes are not visible to this account')).toBeNull();
  });
});
