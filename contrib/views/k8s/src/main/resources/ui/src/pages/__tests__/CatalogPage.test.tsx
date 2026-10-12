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
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

jest.mock('../../api/client', () => ({
  getAvailableServices: jest.fn(),
  getClusterCapabilities: jest.fn(),
  getCatalogAppVersions: jest.fn(),
}));
jest.mock('../../assets/services', () => ({ serviceIcon: () => '' }));

import { getAvailableServices, getClusterCapabilities, getCatalogAppVersions } from '../../api/client';
import CatalogPage from '../CatalogPage';

/** The service catalog leaves out a service that cannot run on the cluster's platform (GitLab on OpenShift). */
describe('CatalogPage', () => {
  const services = {
    GITLAB: { label: 'GitLab CE', chart: 'gitlab', platforms: ['kubernetes'] },
    TRINO: { label: 'Trino', chart: 'trinodb/trino' },
  };
  const capabilities = (platform?: 'kubernetes' | 'openshift') => platform && ({
    platform,
    openshift: { routeCrd: platform === 'openshift' },
    certManager: { installed: false, clusterIssuerCrd: false, certificateCrd: false },
    externalSecrets: { installed: false, secretStoreCrd: false, clusterSecretStoreCrd: false, externalSecretCrd: false },
  });

  const renderOn = async (platform?: 'kubernetes' | 'openshift') => {
    (getAvailableServices as jest.Mock).mockResolvedValue(services);
    if (platform) {
      (getClusterCapabilities as jest.Mock).mockResolvedValue(capabilities(platform));
    } else {
      (getClusterCapabilities as jest.Mock).mockRejectedValue(new Error('probe failed'));
    }
    (getCatalogAppVersions as jest.Mock).mockResolvedValue({});
    render(<MemoryRouter><CatalogPage /></MemoryRouter>);
    await screen.findByText('Trino');
  };

  beforeEach(() => jest.clearAllMocks());

  it('does not offer GitLab on OpenShift', async () => {
    await renderOn('openshift');
    expect(screen.queryByText('GitLab CE')).toBeNull();
  });

  it('offers GitLab on plain Kubernetes', async () => {
    await renderOn('kubernetes');
    expect(screen.getByText('GitLab CE')).toBeTruthy();
  });

  it('hides nothing when the platform could not be detected', async () => {
    await renderOn(undefined);
    expect(screen.getByText('GitLab CE')).toBeTruthy();
  });
});
