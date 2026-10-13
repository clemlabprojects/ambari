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
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

jest.mock('../../api/client', () => ({
  getAvailableServices: jest.fn(),
  getClusterCapabilities: jest.fn(),
  getHelmReleases: jest.fn(),
  getReleaseTlsState: jest.fn(),
  getReleaseStatus: jest.fn(),
  uninstallHelm: jest.fn(),
}));
jest.mock('../../assets/services', () => ({ serviceIcon: () => '' }));
jest.mock('../../context/ClusterStatusContext', () => ({
  useClusterStatus: () => ({ status: 'connected', refresh: jest.fn(), nodes: [], events: [] }),
}));

import { getAvailableServices, getClusterCapabilities, getHelmReleases, getReleaseStatus, getReleaseTlsState, uninstallHelm } from '../../api/client';
import { invalidateCapabilities } from '../../components/ServiceInstallationModal/capabilities';
import { PermissionsContext } from '../../context/PermissionsContext';
import HelmReleasesPage from '../HelmReleasesPage';

/**
 * The Releases page's "Install Service" picker is a second service catalog: like the Catalog page, it leaves out a
 * service that cannot run on the cluster's platform (GitLab on OpenShift).
 */
describe('HelmReleasesPage install picker', () => {
  const user = userEvent.setup();
  const services = {
    GITLAB: { label: 'GitLab CE', chart: 'gitlab', platforms: ['kubernetes'] },
    TRINO: { label: 'Trino', chart: 'trinodb/trino' },
  };

  /** Renders the page on a cluster of that platform (undefined: the probe failed) and opens the picker. */
  const openPicker = async (platform?: 'kubernetes' | 'openshift') => {
    (getAvailableServices as jest.Mock).mockResolvedValue(services);
    if (platform) {
      (getClusterCapabilities as jest.Mock).mockResolvedValue({ platform });
    } else {
      (getClusterCapabilities as jest.Mock).mockRejectedValue(new Error('probe failed'));
    }
    (getHelmReleases as jest.Mock).mockResolvedValue({ items: [], total: 0 });
    render(
      <PermissionsContext.Provider value={{ permissions: { canWrite: true, canConfigure: true } as any, loading: false }}>
        <MemoryRouter><HelmReleasesPage /></MemoryRouter>
      </PermissionsContext.Provider>);
    await waitFor(() => expect(getClusterCapabilities).toHaveBeenCalled());
    await waitFor(() => expect(getAvailableServices).toHaveBeenCalled());
    // Let the probe and the service list settle, so the picker shows what the operator would see.
    await act(async () => { await Promise.resolve(); });
    await user.click(screen.getByRole('button', { name: /Install Service/i }));
    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText('Trino');
    return dialog;
  };

  beforeEach(() => {
    jest.clearAllMocks();
    // The capability probe is cached for the whole page; each case probes its own cluster.
    invalidateCapabilities();
  });

  it('does not offer GitLab on OpenShift', async () => {
    const dialog = await openPicker('openshift');
    await waitFor(() => expect(within(dialog).queryByText('GitLab CE')).toBeNull());
    expect(within(dialog).queryByText('No services available.')).toBeNull();
  });

  it('offers GitLab on plain Kubernetes', async () => {
    const dialog = await openPicker('kubernetes');
    expect(within(dialog).getByText('GitLab CE')).toBeTruthy();
  });

  it('hides nothing when the platform could not be detected', async () => {
    const dialog = await openPicker(undefined);
    expect(within(dialog).getByText('GitLab CE')).toBeTruthy();
  });
});

/**
 * The uninstall of a GitOps release deployed through pull requests opens a pull request removing it: the page says so,
 * with the pull request, rather than reporting the release uninstalled.
 */
describe('HelmReleasesPage uninstall through a pull request', () => {
  const user = userEvent.setup();
  const trino = {
    id: 'trino', name: 'trino', namespace: 'analytics', chart: 'trino-1.43.14', version: '1.43.14',
    status: 'deployed', managedByUi: true, deploymentMode: 'FLUX_GITOPS', gitCommitMode: 'PR_MODE',
    gitBaseBranch: 'main', gitRepoUrl: 'https://git.example.org/platform/gitops.git',
  };

  beforeEach(() => {
    jest.clearAllMocks();
    invalidateCapabilities();
    (getAvailableServices as jest.Mock).mockResolvedValue({});
    (getClusterCapabilities as jest.Mock).mockResolvedValue({ platform: 'kubernetes' });
    (getHelmReleases as jest.Mock).mockResolvedValue({ items: [trino], total: 1 });
    (getReleaseTlsState as jest.Mock).mockResolvedValue([]);
    (getReleaseStatus as jest.Mock).mockResolvedValue({});
  });

  it('shows the pull request that removes the release, which keeps running until it is merged', async () => {
    (uninstallHelm as jest.Mock).mockResolvedValue({
      pendingDelete: true,
      pullRequestUrl: 'https://git.example.org/platform/gitops/pulls/7',
      message: 'The removal of analytics/trino is proposed in pull request '
        + 'https://git.example.org/platform/gitops/pulls/7 into main. The release keeps running until it is merged;'
        + ' uninstall it again then to finish removing it from the cluster and KDPS.',
    });
    render(
      <PermissionsContext.Provider value={{ permissions: { canWrite: true, canConfigure: true } as any, loading: false }}>
        <MemoryRouter><HelmReleasesPage /></MemoryRouter>
      </PermissionsContext.Provider>);
    await screen.findByText('trino');

    await user.click(screen.getByRole('button', { name: 'more' }));
    await user.click(await screen.findByText('Uninstall'));
    const confirm = await screen.findByRole('dialog');
    await user.click(within(confirm).getByRole('button', { name: 'Uninstall' }));

    expect(await screen.findByText('Removal of trino proposed in a pull request')).toBeTruthy();
    expect(screen.getByText(/The release keeps running until it is merged/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'https://git.example.org/platform/gitops/pulls/7' })).toBeTruthy();
    expect(screen.queryByText('Release trino uninstalled successfully.')).toBeNull();
    expect(uninstallHelm).toHaveBeenCalledWith('trino', 'analytics', expect.any(URLSearchParams));
  });
});
