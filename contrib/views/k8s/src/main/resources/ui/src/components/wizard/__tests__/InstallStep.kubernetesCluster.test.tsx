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

import { render, screen, fireEvent } from '@testing-library/react';
import InstallStep from '../InstallStep';
import type { KubernetesCluster } from '../../../api/client';

jest.mock('../../../api/client', () => ({
  getClusterCapabilities: jest.fn(() => Promise.resolve({ platform: 'kubernetes' })),
  listGitOpsProfiles: jest.fn(() => Promise.resolve({ canConfigure: false, items: [] })),
}));
// The installation modal pulls in the service icons, which jest does not load; the general step does not use it.
jest.mock('../../ServiceInstallationModal', () => ({
  resolveAuthCascade: jest.fn(),
  applyAuthCascadeToFields: jest.fn(),
}));

const cluster = (id: string, name: string, configured = true): KubernetesCluster =>
  ({ id, name, protected: false, default: id === 'default', configured });

const renderGeneral = (clusters: KubernetesCluster[], upgrade = false, onChange = jest.fn()) =>
  render(
    <InstallStep definition={{}} data={{ releaseName: 'trino', namespace: 'trino', deploymentMode: 'DIRECT_HELM' }}
      onChange={onChange} mode="general" upgrade={upgrade} kubernetesClusters={clusters} />,
  );

describe('InstallStep Kubernetes cluster', () => {
  beforeEach(() => localStorage.clear());

  it('asks nothing while KDPS knows a single cluster', () => {
    renderGeneral([cluster('default', 'Default cluster')]);
    expect(screen.queryByText('Kubernetes cluster')).toBeNull();
  });

  it('asks which cluster to deploy to when KDPS knows several, the default one first chosen', () => {
    renderGeneral([cluster('default', 'Development OpenShift'), cluster('prod', 'Production OpenShift')]);
    expect(screen.getByText('Kubernetes cluster')).toBeInTheDocument();
    expect(screen.getByText('Development OpenShift')).toBeInTheDocument();
  });

  it('offers a cluster without a connection, but not to choose', () => {
    renderGeneral([cluster('default', 'Development OpenShift'), cluster('prod', 'Production OpenShift', false)]);
    const item = screen.getByText('Kubernetes cluster').closest('.ant-form-item') as HTMLElement;
    fireEvent.mouseDown(item.querySelector('.ant-select-selector') as HTMLElement);
    const option = screen.getByText('Production OpenShift (no connection yet)').closest('.ant-select-item-option');
    expect(option).toHaveClass('ant-select-item-option-disabled');
  });

  it('keeps a release on the cluster it was deployed to: an upgrade does not ask', () => {
    renderGeneral([cluster('default', 'Development OpenShift'), cluster('prod', 'Production OpenShift')], true);
    expect(screen.queryByText('Kubernetes cluster')).toBeNull();
  });
});
