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
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

jest.mock('../../api/client');

import { getHelmRepos, saveHelmRepo, loginHelmRepo, deleteHelmRepo } from '../../api/client';
import RepositoriesPage from '../HelmRepositoriesPage';

/** Helm repositories page: add / validate / login-sync / delete, against the current (modal-based) UI. */
describe('RepositoriesPage', () => {
  const user = userEvent.setup();
  const repo = { id: 'bitnami', name: 'Bitnami', type: 'HTTP', url: 'https://charts.bitnami.com/bitnami', authMode: 'anonymous' } as any;

  beforeEach(() => {
    jest.clearAllMocks();
    (getHelmRepos as jest.Mock).mockResolvedValue([]);
    (saveHelmRepo as jest.Mock).mockImplementation(async (r: any) => r);
  });

  const openAddModal = async () => {
    render(<RepositoriesPage />);
    await waitFor(() => expect(getHelmRepos).toHaveBeenCalled());
    await user.click(screen.getByRole('button', { name: /Add repository/i }));
    return screen.getByRole('dialog');
  };

  it('adds an anonymous HTTP repository', async () => {
    const dialog = await openAddModal();
    await user.type(within(dialog).getByLabelText(/^ID$/i), 'bitnami');
    await user.type(within(dialog).getByLabelText(/Display name/i), 'Bitnami');
    await user.type(within(dialog).getByLabelText(/Chart index URL/i), 'https://charts.bitnami.com/bitnami');
    await user.click(within(dialog).getByRole('button', { name: /^Add$/ }));
    await waitFor(() => expect(saveHelmRepo).toHaveBeenCalledWith(expect.objectContaining({ id: 'bitnami', name: 'Bitnami', url: 'https://charts.bitnami.com/bitnami' }), undefined)); // (entity, secret)
    await waitFor(() => expect(getHelmRepos).toHaveBeenCalledTimes(2)); // initial load + refresh after save
  });

  it('shows validation errors when required fields are missing', async () => {
    const dialog = await openAddModal();
    await user.click(within(dialog).getByRole('button', { name: /^Add$/ }));
    await waitFor(() => expect(within(dialog).getAllByText(/required/i).length).toBeGreaterThanOrEqual(3));
    expect(saveHelmRepo).not.toHaveBeenCalled();
  });

  it('calls login/sync for a repository', async () => {
    (getHelmRepos as jest.Mock).mockResolvedValue([repo]);
    render(<RepositoriesPage />);
    await user.click(await screen.findByRole('button', { name: /Login \/ Sync/i }));
    await waitFor(() => expect(loginHelmRepo).toHaveBeenCalledWith('bitnami'));
  });

  it('deletes a repository after confirmation', async () => {
    (getHelmRepos as jest.Mock).mockResolvedValue([repo]);
    render(<RepositoriesPage />);
    await user.click(await screen.findByRole('button', { name: /^Delete$/ }));
    await user.click(await screen.findByRole('button', { name: /^OK$/i }));
    await waitFor(() => expect(deleteHelmRepo).toHaveBeenCalledWith('bitnami'));
  });
});
