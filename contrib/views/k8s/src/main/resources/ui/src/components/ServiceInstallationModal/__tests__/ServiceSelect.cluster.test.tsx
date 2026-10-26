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

import { render, waitFor } from '@testing-library/react';
import { Form } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import ServiceSelect from '../ServiceSelect';
import { TargetClusterContext } from '../../../context/TargetClusterContext';
import { getDiscoveredK8sSecrets, getDiscoveredClusterIssuers } from '../../../api/client';

jest.mock('../../../api/client', () => ({
  getClusterServices: jest.fn(() => Promise.resolve([])),
  getDiscoveredK8sServices: jest.fn(() => Promise.resolve([])),
  getDiscoveredK8sSecrets: jest.fn(() => Promise.resolve([])),
  getDiscoveredClusterIssuers: jest.fn(() => Promise.resolve([])),
  getDiscoveredSecretStores: jest.fn(() => Promise.resolve([])),
  getMonitoringDiscovery: jest.fn(() => Promise.resolve({})),
  getKedaDiscovery: jest.fn(() => Promise.resolve({ present: false })),
}));
jest.mock('../../../context/ClusterStatusContext', () => ({ useClusterStatus: () => ({ connectionEpoch: 0 }) }));

const renderOn = (cluster: string | undefined, field: any) =>
  render(
    <MemoryRouter>
      <TargetClusterContext.Provider value={cluster}>
        <Form initialValues={{ namespace: 'analytics' }}>
          <ServiceSelect field={field} />
        </Form>
      </TargetClusterContext.Provider>
    </MemoryRouter>,
  );

describe('ServiceSelect on the cluster the wizard deploys to', () => {
  beforeEach(() => jest.clearAllMocks());

  it('lists the Secrets of the namespace on that cluster', async () => {
    renderOn('prod', { name: 'hive.externalKeytabSecret', label: 'Keytab Secret', type: 'secret-discovery' });
    await waitFor(() => expect(getDiscoveredK8sSecrets).toHaveBeenCalledWith(undefined, 'analytics', 'prod'));
  });

  it('names no cluster for the default one', async () => {
    renderOn(undefined, { name: 'issuer', label: 'Issuer', type: 'cluster-issuer-discovery' });
    await waitFor(() => expect(getDiscoveredClusterIssuers).toHaveBeenCalledWith(false, undefined));
  });
});
