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
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
jest.mock('../../api/client', () => ({
  listKubernetesClusters: jest.fn(),
  getNamedClusterLiveness: jest.fn(),
  saveKubernetesCluster: jest.fn(),
  removeKubernetesCluster: jest.fn(),
  uploadClusterKubeconfig: jest.fn(),
  getClusterKubeconfigContexts: jest.fn(),
  selectClusterKubeconfigContext: jest.fn(),
  saveClusterOpenShiftLogin: jest.fn(),
  connectClusterWithInClusterServiceAccount: jest.fn(),
}));

import {
  getNamedClusterLiveness, listKubernetesClusters, removeKubernetesCluster, saveKubernetesCluster,
} from '../../api/client';
import { PermissionsContext } from '../../context/PermissionsContext';
import KubernetesClustersPage, { CLUSTER_ID, connectionLabel } from '../KubernetesClustersPage';

const clusters = [
  { id: 'default', name: 'Default cluster', protected: false, default: true, configured: true, authMode: 'kubeconfig', insecure: false },
  { id: 'prod', name: 'Production', description: 'OpenShift in the datacenter', protected: true, default: false,
    configured: true, authMode: 'openshift-login', insecure: true },
  { id: 'dev', name: 'Development', protected: false, default: false, configured: false },
];

const page = (canConfigure: boolean) => render(
  <PermissionsContext.Provider value={{ permissions: { canWrite: canConfigure, canConfigure } as any, loading: false }}>
    <MemoryRouter><KubernetesClustersPage /></MemoryRouter>
  </PermissionsContext.Provider>);

const row = async (name: string) => (await screen.findByText(name)).closest('tr') as HTMLElement;

/**
 * The Kubernetes clusters page: every user reads the clusters with their connection state, each checked on its own;
 * administrators add, edit and remove them.
 */
describe('KubernetesClustersPage', () => {
  const user = userEvent.setup();

  beforeEach(() => {
    jest.clearAllMocks();
    (listKubernetesClusters as jest.Mock).mockResolvedValue(clusters);
  });

  it('shows each cluster as soon as its own state is known, one that does not answer holding back nothing', async () => {
    (getNamedClusterLiveness as jest.Mock).mockImplementation((id: string) => (id === 'prod'
      ? new Promise(() => undefined)
      : Promise.resolve({ state: 'CONNECTED', message: 'Cluster reachable and credentials valid.' })));

    page(false);

    expect(await within(await row('Default cluster')).findByText('Connected')).toBeTruthy();
    expect(within(await row('Production')).getByLabelText('checking the connection')).toBeTruthy();
    expect(within(await row('Development')).getByText('No connection yet')).toBeTruthy();
    expect(getNamedClusterLiveness).not.toHaveBeenCalledWith('dev');
  });

  it('says which cluster skips TLS verification and which is protected', async () => {
    (getNamedClusterLiveness as jest.Mock).mockResolvedValue({ state: 'UNREACHABLE', message: 'Cluster API is unreachable.' });

    page(false);

    const prod = await row('Production');
    expect(within(prod).getByText('TLS not verified')).toBeTruthy();
    expect(within(prod).getByText('protected')).toBeTruthy();
    expect(within(prod).getByText('OpenShift login')).toBeTruthy();
    expect(await within(prod).findByText('Unreachable')).toBeTruthy();
    expect(within(await row('Default cluster')).queryByText('TLS not verified')).toBeNull();
  });

  it('offers nothing to change to a user who cannot configure the view', async () => {
    (getNamedClusterLiveness as jest.Mock).mockResolvedValue({ state: 'CONNECTED', message: '' });

    page(false);

    await row('Production');
    expect(screen.queryByRole('button', { name: /Add a cluster/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Edit/ })).toBeNull();
  });

  it('adds a cluster, then asks for its connection', async () => {
    (getNamedClusterLiveness as jest.Mock).mockResolvedValue({ state: 'CONNECTED', message: '' });
    (saveKubernetesCluster as jest.Mock).mockResolvedValue({ id: 'qa', name: 'QA', protected: false, default: false, configured: false });

    page(true);
    await user.click(await screen.findByRole('button', { name: /Add a cluster/ }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText('Identifier'), 'qa');
    await user.type(within(dialog).getByLabelText('Name'), 'QA');
    await user.click(within(dialog).getByRole('button', { name: 'Add' }));

    await waitFor(() => expect(saveKubernetesCluster).toHaveBeenCalledWith('qa', { name: 'QA', description: '', protected: false }));
    expect(await screen.findByText('Connection of QA')).toBeTruthy();
  });

  it('says why a cluster was not removed', async () => {
    (getNamedClusterLiveness as jest.Mock).mockResolvedValue({ state: 'CONNECTED', message: '' });
    (removeKubernetesCluster as jest.Mock).mockRejectedValue(new Error("HTTP 409 – {\"error\":\"KDPS still has releases on the cluster 'prod': analytics/trino. Uninstall them first; the cluster was not removed.\"}"));

    page(true);
    await user.click(within(await row('Production')).getByRole('button', { name: 'remove prod' }));
    await user.click(await screen.findByRole('button', { name: 'Remove' }));

    expect(await screen.findByText('Production was not removed')).toBeTruthy();
    expect(screen.getByText(/KDPS still has releases on the cluster 'prod': analytics\/trino/)).toBeTruthy();
  });

  it('names the connection and takes only usable identifiers', () => {
    expect(connectionLabel({ configured: false, authMode: 'kubeconfig' })).toBe('No connection yet');
    expect(connectionLabel({ configured: true, authMode: 'in-cluster' })).toBe("Ambari pod's ServiceAccount");
    expect(CLUSTER_ID.test('prod-eu1')).toBe(true);
    expect(CLUSTER_ID.test('Prod')).toBe(false);
    expect(CLUSTER_ID.test('-prod')).toBe(false);
  });
});
