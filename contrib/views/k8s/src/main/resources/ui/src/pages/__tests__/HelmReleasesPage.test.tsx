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
  getReleaseKdpsObjects: jest.fn(),
  getReleaseExternalRegistrations: jest.fn(),
  getReleaseActionRefusals: jest.fn(),
  getReleaseHistory: jest.fn(),
  rollbackReleaseToRevision: jest.fn(),
}));
jest.mock('../../assets/services', () => ({ serviceIcon: () => '' }));
jest.mock('../../context/ClusterStatusContext', () => ({
  useClusterStatus: () => ({ status: 'connected', refresh: jest.fn(), nodes: [], events: [] }),
}));

import {
  getAvailableServices, getClusterCapabilities, getHelmReleases, getReleaseActionRefusals, getReleaseHistory,
  getReleaseExternalRegistrations, getReleaseKdpsObjects, getReleaseStatus, getReleaseTlsState,
  rollbackReleaseToRevision, uninstallHelm,
} from '../../api/client';
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
    (getReleaseActionRefusals as jest.Mock).mockResolvedValue({ refusals: {} });
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

/**
 * An uninstall the cluster or Git refuses leaves the release installed, with its record and its Polaris access: the
 * page shows the reason and reloads the list, where the release still is. One that succeeds says what became of the
 * release's Polaris access, and the release leaves the list.
 */
describe('HelmReleasesPage uninstall outcome', () => {
  const user = userEvent.setup();
  const trino = {
    id: 'analytics/trino', name: 'trino', namespace: 'analytics', chart: 'trino-1.43.16', version: '1.43.16',
    status: 'deployed', managedByUi: true, deploymentMode: 'DIRECT_HELM', serviceKey: 'TRINO',
  };

  beforeEach(() => {
    jest.clearAllMocks();
    invalidateCapabilities();
    (getAvailableServices as jest.Mock).mockResolvedValue({});
    (getClusterCapabilities as jest.Mock).mockResolvedValue({ platform: 'kubernetes' });
    (getReleaseTlsState as jest.Mock).mockResolvedValue([]);
    (getReleaseStatus as jest.Mock).mockResolvedValue({});
    (getReleaseActionRefusals as jest.Mock).mockResolvedValue({ refusals: {} });
  });

  /** Opens the uninstall confirmation of the release; returns the dialog. */
  const openConfirm = async () => {
    render(
      <PermissionsContext.Provider value={{ permissions: { canWrite: true, canConfigure: true } as any, loading: false }}>
        <MemoryRouter><HelmReleasesPage /></MemoryRouter>
      </PermissionsContext.Provider>);
    await screen.findByText('trino');
    await user.click(screen.getByRole('button', { name: 'more' }));
    // Modals of earlier cases can still be in the document: take the menu's item, then this case's confirmation.
    const before = screen.queryAllByText('Uninstall trino?').length;
    await user.click(await screen.findByRole('menuitem', { name: /Uninstall/ }));
    await waitFor(() => expect(screen.queryAllByText('Uninstall trino?').length).toBeGreaterThan(before));
    const dialogs = await screen.findAllByRole('dialog');
    return dialogs.filter((d) => d.textContent?.includes('Uninstall trino?')).pop() as HTMLElement;
  };

  it('says the Polaris principal is revoked before uninstalling', async () => {
    (getHelmReleases as jest.Mock).mockResolvedValue({ items: [trino], total: 1 });

    const confirm = await openConfirm();

    expect(within(confirm).getByText(/revokes its Polaris principal and role/)).toBeTruthy();
  });

  it('lists the objects KDPS created for the release, which go with it', async () => {
    (getHelmReleases as jest.Mock).mockResolvedValue({ items: [trino], total: 1 });
    (getReleaseKdpsObjects as jest.Mock).mockResolvedValue(['Secret/trino-keytab', 'ServiceAccount/trino-thanos-token']);

    const confirm = await openConfirm();

    expect(within(confirm).getByText(/deletes what it created for this release outside its chart: Secret\/trino-keytab,/))
      .toBeTruthy();
    expect(getReleaseKdpsObjects).toHaveBeenCalledWith('trino', 'analytics');
  });

  it('still asks to confirm when the objects cannot be listed', async () => {
    (getHelmReleases as jest.Mock).mockResolvedValue({ items: [trino], total: 1 });
    (getReleaseKdpsObjects as jest.Mock).mockRejectedValue(new Error('cluster unreachable'));

    const confirm = await openConfirm();

    expect(within(confirm).queryByText(/deletes what it created for this release/)).toBeNull();
    expect(within(confirm).getByRole('button', { name: 'Uninstall' })).toBeTruthy();
  });

  it('shows why the release could not be uninstalled and keeps it listed', async () => {
    (getHelmReleases as jest.Mock).mockResolvedValue({ items: [trino], total: 1 });
    const reason = 'Uninstall failed: 502: Helm could not uninstall analytics/trino: another operation'
      + ' (install/upgrade/rollback) is in progress. The release is still installed and keeps its record and its'
      + ' catalog access. Fix the cause, then uninstall it again.';
    (uninstallHelm as jest.Mock).mockRejectedValue(new Error(reason));

    const confirm = await openConfirm();
    const loads = (getHelmReleases as jest.Mock).mock.calls.length;
    await user.click(within(confirm).getByRole('button', { name: 'Uninstall' }));

    expect(await screen.findByText('trino was not uninstalled')).toBeTruthy();
    expect(screen.getByText(reason)).toBeTruthy();
    await waitFor(() => expect((getHelmReleases as jest.Mock).mock.calls.length).toBeGreaterThan(loads));
    expect(screen.getAllByText('trino').length).toBeGreaterThan(0);
    expect(screen.queryByText('Release trino uninstalled successfully.')).toBeNull();
  });

  it('lists what KDPS takes back outside Kubernetes and passes the chosen way to the uninstall', async () => {
    (getHelmReleases as jest.Mock).mockResolvedValue({ items: [trino], total: 1 });
    (getReleaseExternalRegistrations as jest.Mock).mockImplementation(async (_n: string, _ns: string, mode: string) => ({
      removed: ["Keycloak client 'trino-analytics'", ...(mode === 'all' ? ["Ranger repository 'trino-analytics'"] : [])],
      left: [],
      kept: mode === 'all' ? [] : [{ object: "Ranger repository 'trino-analytics'", reason: 'kept with the policies in it' }],
      recorded: true,
    }));
    (uninstallHelm as jest.Mock).mockResolvedValue({});

    const confirm = await openConfirm();

    expect(within(confirm).getByText(/KDPS also removes: Keycloak client 'trino-analytics'\./)).toBeTruthy();
    expect(within(confirm).getByText(/It keeps: Ranger repository 'trino-analytics'/)).toBeTruthy();
    await user.click(within(confirm).getByText(/Remove everything, the Ranger repositories of the release included/));
    expect(await within(confirm).findByText(/also removes: .*Ranger repository 'trino-analytics'\./)).toBeTruthy();
    await user.click(within(confirm).getByRole('button', { name: 'Uninstall' }));

    await waitFor(() => expect(uninstallHelm).toHaveBeenCalled());
    const params = (uninstallHelm as jest.Mock).mock.calls[0][2] as URLSearchParams;
    expect(params.get('externalCleanup')).toBe('all');
  });

  it('shows what became of the Polaris access and reloads the list', async () => {
    (getHelmReleases as jest.Mock)
      .mockResolvedValueOnce({ items: [trino], total: 1 })
      .mockResolvedValue({ items: [], total: 0 });
    (uninstallHelm as jest.Mock).mockResolvedValue({
      polarisCleanup: "Removed the Polaris principal 'trino-analytics' and its role.",
    });

    const confirm = await openConfirm();
    await user.click(within(confirm).getByRole('button', { name: 'Uninstall' }));

    expect(await screen.findByText("Removed the Polaris principal 'trino-analytics' and its role.")).toBeTruthy();
    await waitFor(() => expect(getHelmReleases).toHaveBeenCalledTimes(2));
  });
});

/**
 * The repair actions of a release run against the platform context it was deployed on. The page asks the backend,
 * when the menu opens, which of them that context cannot perform, and disables those with the reason.
 */
describe('HelmReleasesPage repair actions', () => {
  const user = userEvent.setup();
  const reason = 'Kerberos for release analytics/trino does not come from the cluster this Ambari manages but from its'
    + " platform context 'CDP production': KDPS does not issue its keytabs.";

  beforeEach(() => {
    jest.clearAllMocks();
    invalidateCapabilities();
    (getReleaseStatus as jest.Mock).mockResolvedValue({});
  });

  it('disables an action the release\'s platform context cannot perform, and keeps the others', async () => {
    (getAvailableServices as jest.Mock).mockResolvedValue({
      TRINO: { label: 'Trino', chart: 'trinodb/trino', kerberos: [{ key: 'coordinator' }], oidc: [{ key: 'trino' }] },
    });
    (getClusterCapabilities as jest.Mock).mockResolvedValue({ platform: 'kubernetes' });
    (getHelmReleases as jest.Mock).mockResolvedValue({
      items: [{
        id: 'analytics/trino', name: 'trino', namespace: 'analytics', chart: 'trino-1.43.16', version: '1.43.16',
        status: 'deployed', managedByUi: true, serviceKey: 'TRINO', platformContextId: 'cdp-prod',
      }],
      total: 1,
    });
    (getReleaseTlsState as jest.Mock).mockResolvedValue([]);
    (getReleaseActionRefusals as jest.Mock).mockResolvedValue({ refusals: { keytabs: reason } });
    render(
      <PermissionsContext.Provider value={{ permissions: { canWrite: true, canConfigure: true } as any, loading: false }}>
        <MemoryRouter><HelmReleasesPage /></MemoryRouter>
      </PermissionsContext.Provider>);
    await screen.findByText('trino');
    await waitFor(() => expect(getAvailableServices).toHaveBeenCalled());
    await act(async () => { await Promise.resolve(); });

    const row = screen.getByText('trino').closest('tr') as HTMLElement;
    const buttons = within(row).getAllByRole('button');
    await user.click(buttons[buttons.length - 1]);

    await waitFor(() => expect(getReleaseActionRefusals).toHaveBeenCalledWith('analytics', 'trino'));
    const keytabs = await screen.findByRole('menuitem', { name: /Regenerate keytabs/ });
    await waitFor(() => expect(keytabs.getAttribute('aria-disabled')).toBe('true'));
    const oidc = screen.getByRole('menuitem', { name: /Re-register OIDC client/ });
    expect(oidc.getAttribute('aria-disabled')).not.toBe('true');
  });
});

/**
 * A release deployed with Flux GitOps is rolled back through Git: Flux would undo a Helm rollback at its next
 * reconcile. Its revision history is shown read-only, with what brings an earlier version back.
 */
describe('HelmReleasesPage revision history of a GitOps release', () => {
  const user = userEvent.setup();
  const history = [
    { revision: 3, status: 'deployed', chart: 'trino-1.43.16', app_version: '476', description: 'Upgrade complete' },
    { revision: 2, status: 'superseded', chart: 'trino-1.43.15', app_version: '476', description: 'Upgrade complete' },
  ];

  const openHistory = async (deploymentMode: string) => {
    (getAvailableServices as jest.Mock).mockResolvedValue({});
    (getClusterCapabilities as jest.Mock).mockResolvedValue({ platform: 'kubernetes' });
    (getHelmReleases as jest.Mock).mockResolvedValue({
      items: [{
        id: 'analytics/trino', name: 'trino', namespace: 'analytics', chart: 'trino-1.43.16', version: '1.43.16',
        status: 'deployed', managedByUi: true, deploymentMode,
      }],
      total: 1,
    });
    (getReleaseTlsState as jest.Mock).mockResolvedValue([]);
    (getReleaseStatus as jest.Mock).mockResolvedValue({});
    (getReleaseActionRefusals as jest.Mock).mockResolvedValue({ refusals: {} });
    (getReleaseHistory as jest.Mock).mockResolvedValue(history);
    render(
      <PermissionsContext.Provider value={{ permissions: { canWrite: true, canConfigure: true } as any, loading: false }}>
        <MemoryRouter><HelmReleasesPage /></MemoryRouter>
      </PermissionsContext.Provider>);
    await screen.findByText('trino');
    const row = screen.getByText('trino').closest('tr') as HTMLElement;
    const buttons = within(row).getAllByRole('button');
    await user.click(buttons[buttons.length - 1]);
  };

  beforeEach(() => {
    jest.clearAllMocks();
    invalidateCapabilities();
  });

  it('shows the history read-only, with how to go back through Git', async () => {
    await openHistory('FLUX_GITOPS');

    await user.click(await screen.findByText('Revision history (read-only)…'));

    expect(await screen.findByText('Read-only: this release is deployed with Flux GitOps')).toBeTruthy();
    expect(screen.getByText(/revert or change the release in Git/)).toBeTruthy();
    const rollback = (await screen.findByText('Rollback to 2')).closest('button') as HTMLButtonElement;
    expect(rollback.disabled).toBe(true);
    await user.click(rollback);
    expect(rollbackReleaseToRevision).not.toHaveBeenCalled();
  });

  it('keeps the rollback of a release deployed with Helm', async () => {
    await openHistory('DIRECT_HELM');

    await user.click(await screen.findByText('Revision history…'));

    const rollback = (await screen.findByText('Rollback to 2')).closest('button') as HTMLButtonElement;
    expect(rollback.disabled).toBe(false);
    expect(screen.queryByText('Read-only: this release is deployed with Flux GitOps')).toBeNull();
  });
});

/**
 * A GitOps release Flux has not installed (its pull request not merged yet) is listed from its record: the cluster holds
 * none of its values, so no action that redeploys it from them is offered. Its Uninstall and Retry reconcile are.
 */
describe('HelmReleasesPage GitOps release not installed by Flux yet', () => {
  const user = userEvent.setup();

  beforeEach(() => {
    jest.clearAllMocks();
    invalidateCapabilities();
  });

  it('offers no redeploy action on a release waiting for its pull request', async () => {
    (getAvailableServices as jest.Mock).mockResolvedValue({ TRINO: { label: 'Trino', chart: 'trinodb/trino' } });
    (getClusterCapabilities as jest.Mock).mockResolvedValue({ platform: 'kubernetes' });
    (getHelmReleases as jest.Mock).mockResolvedValue({
      items: [{
        id: 'trino', name: 'trino', namespace: 'analytics', chart: 'clemlab-trino', version: '1.43.16',
        status: 'PENDING_PR', managedByUi: true, serviceKey: 'TRINO', deploymentMode: 'FLUX_GITOPS',
        gitCommitMode: 'PR_MODE', gitPrUrl: 'https://git.example.org/platform/gitops/pulls/7', gitPrState: 'open',
        securityProfile: 'ldap', securityProfileStale: true, restartRequired: true, installed: false,
        catalogManagement: false,
      }],
      total: 1,
    });
    (getReleaseTlsState as jest.Mock).mockResolvedValue([]);
    // The status endpoint does not know whether Flux installed the release: the list's answer must survive its refresh.
    (getReleaseStatus as jest.Mock).mockResolvedValue({
      name: 'trino', namespace: 'analytics', status: 'PENDING_PR', deploymentMode: 'FLUX_GITOPS',
      installed: null, catalogManagement: null,
    });
    (getReleaseActionRefusals as jest.Mock).mockResolvedValue({ refusals: {} });
    render(
      <PermissionsContext.Provider value={{ permissions: { canWrite: true, canConfigure: true } as any, loading: false }}>
        <MemoryRouter><HelmReleasesPage /></MemoryRouter>
      </PermissionsContext.Provider>);
    await screen.findByText('trino');
    await waitFor(() => expect(getAvailableServices).toHaveBeenCalled());
    await waitFor(() => expect(getReleaseStatus).toHaveBeenCalledWith('analytics', 'trino'));
    await act(async () => { await Promise.resolve(); });

    const row = screen.getByText('trino').closest('tr') as HTMLElement;
    const buttons = within(row).getAllByRole('button');
    await user.click(buttons[buttons.length - 1]);

    for (const name of [/Upgrade \/ Config/, /Resync \/ restart/, /Refresh security profile/, /Worker autoscaling/,
      /Revision history/]) {
      const item = await screen.findByRole('menuitem', { name });
      expect(item.getAttribute('aria-disabled')).toBe('true');
    }
    expect(screen.getByRole('menuitem', { name: /Uninstall/ }).getAttribute('aria-disabled')).not.toBe('true');
    expect(screen.getByRole('menuitem', { name: /Retry reconcile/ }).getAttribute('aria-disabled')).not.toBe('true');
    expect(screen.queryByRole('menuitem', { name: /Catalogs/ })).toBeNull();
    await user.click(screen.getByRole('menuitem', { name: /Revision history/ }));
    expect(getReleaseHistory).not.toHaveBeenCalled();
  });
});

describe('HelmReleasesPage release of a service this build no longer ships', () => {
  const user = userEvent.setup();

  beforeEach(() => {
    jest.clearAllMocks();
    invalidateCapabilities();
  });

  it('offers only its history and its uninstall', async () => {
    (getAvailableServices as jest.Mock).mockResolvedValue({ TRINO: { label: 'Trino', chart: 'trinodb/trino' } });
    (getClusterCapabilities as jest.Mock).mockResolvedValue({ platform: 'kubernetes' });
    (getHelmReleases as jest.Mock).mockResolvedValue({
      items: [{
        id: 'hub', name: 'hub', namespace: 'analytics', chart: 'jupyterhub', version: '4.0.0',
        status: 'deployed', managedByUi: true, serviceKey: 'JUPYTERHUB', deploymentMode: 'DIRECT_HELM',
        securityProfile: 'ldap', securityProfileStale: true, restartRequired: true, installed: true,
      }],
      total: 1,
    });
    (getReleaseTlsState as jest.Mock).mockResolvedValue([]);
    (getReleaseStatus as jest.Mock).mockResolvedValue({
      name: 'hub', namespace: 'analytics', status: 'deployed', deploymentMode: 'DIRECT_HELM',
    });
    (getReleaseActionRefusals as jest.Mock).mockResolvedValue({ refusals: {} });
    render(
      <PermissionsContext.Provider value={{ permissions: { canWrite: true, canConfigure: true } as any, loading: false }}>
        <MemoryRouter><HelmReleasesPage /></MemoryRouter>
      </PermissionsContext.Provider>);
    await screen.findByText('hub');
    await waitFor(() => expect(getAvailableServices).toHaveBeenCalled());
    await act(async () => { await Promise.resolve(); });

    const row = screen.getByText('hub').closest('tr') as HTMLElement;
    const buttons = within(row).getAllByRole('button');
    await user.click(buttons[buttons.length - 1]);

    for (const name of [/Upgrade \/ Config/, /Resync \/ restart/, /Refresh security profile/]) {
      const item = await screen.findByRole('menuitem', { name });
      expect(item.getAttribute('aria-disabled')).toBe('true');
    }
    expect(screen.getByRole('menuitem', { name: /Revision history/ }).getAttribute('aria-disabled')).not.toBe('true');
    expect(screen.getByRole('menuitem', { name: /Uninstall/ }).getAttribute('aria-disabled')).not.toBe('true');
  });
});
