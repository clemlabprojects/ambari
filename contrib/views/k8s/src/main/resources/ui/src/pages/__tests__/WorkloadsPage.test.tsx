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
import userEvent from '@testing-library/user-event';
import { Modal } from 'antd';
import { MemoryRouter } from 'react-router-dom';

jest.mock('../../api/client', () => ({
  getNamespaces: jest.fn(),
  getPods: jest.fn(),
  getServices: jest.fn(),
  getNamespaceEvents: jest.fn(),
  getClusterCapabilities: jest.fn(),
  deletePod: jest.fn(),
  restartPod: jest.fn(),
}));

import {
  deletePod, getClusterCapabilities, getNamespaceEvents, getNamespaces, getPods, getServices, restartPod,
} from '../../api/client';
import { PermissionsContext } from '../../context/PermissionsContext';
import WorkloadsPage from '../WorkloadsPage';

/**
 * Restarting or deleting a pod asks first, naming the pod, and lets it shut down within its own grace period; only
 * a pod that is already Terminating can be forced out.
 */
describe('WorkloadsPage pod actions', () => {
  const user = userEvent.setup();
  const pod = (name: string, extra: Record<string, unknown> = {}) => ({
    name, namespace: 'analytics', phase: 'Running', containers: [{ name: 'main', ready: true, restartCount: 0 }], ...extra,
  });

  const renderWith = async (pods: unknown[]) => {
    (getNamespaces as jest.Mock).mockResolvedValue([{ name: 'analytics' }]);
    (getPods as jest.Mock).mockResolvedValue(pods);
    (getServices as jest.Mock).mockResolvedValue([]);
    (getNamespaceEvents as jest.Mock).mockResolvedValue([]);
    (getClusterCapabilities as jest.Mock).mockResolvedValue({ platform: 'kubernetes' });
    (deletePod as jest.Mock).mockResolvedValue(undefined);
    (restartPod as jest.Mock).mockResolvedValue(undefined);
    render(
      <PermissionsContext.Provider value={{ permissions: { canWrite: true } as any, loading: false }}>
        <MemoryRouter><WorkloadsPage /></MemoryRouter>
      </PermissionsContext.Provider>);
    await screen.findByText(/(db-0|trino-worker-0)/);
  };

  /** Opens the Actions menu of the first pod row and picks the item. */
  const choose = async (item: string) => {
    await user.click(screen.getAllByRole('button', { name: 'Actions' })[0]);
    await user.click(await screen.findByText(item));
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  afterEach(() => {
    // A confirmation left open would outlive the test and answer the next one's queries.
    Modal.destroyAll();
  });

  it('asks before restarting a pod and names it', async () => {
    await renderWith([pod('trino-worker-0')]);

    await choose('Restart');

    expect(await screen.findByText('Restart pod trino-worker-0?')).toBeTruthy();
    expect(restartPod).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Restart' }));
    await waitFor(() => expect(restartPod).toHaveBeenCalledWith('analytics', 'trino-worker-0'));
  });

  it('asks before deleting a pod and leaves the grace period to the pod', async () => {
    await renderWith([pod('trino-worker-0')]);

    await choose('Delete');

    expect(await screen.findByText('Delete pod trino-worker-0?')).toBeTruthy();
    expect(screen.queryByText('Force delete (no grace period)')).toBeNull();
    expect(deletePod).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(deletePod).toHaveBeenCalledWith('analytics', 'trino-worker-0', undefined, false));
  });

  it('does not delete when the confirmation is cancelled', async () => {
    await renderWith([pod('trino-worker-0')]);

    await choose('Delete');
    await user.click(await screen.findByRole('button', { name: 'Cancel' }));

    expect(deletePod).not.toHaveBeenCalled();
  });

  it('warns that a StatefulSet pod is a stateful service', async () => {
    await renderWith([pod('db-0', { ownerKind: 'StatefulSet' })]);

    await choose('Restart');

    expect(await screen.findByText(/belongs to a StatefulSet/)).toBeTruthy();
  });

  it('offers a forced delete only for a pod that is already Terminating', async () => {
    await renderWith([pod('db-0', { deletionTimestamp: '2026-10-13T10:00:00Z' })]);

    await choose('Delete');
    await user.click(await screen.findByText('Force delete (no grace period)'));
    await user.click(screen.getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(deletePod).toHaveBeenCalledWith('analytics', 'db-0', 0, true));
  });
});
