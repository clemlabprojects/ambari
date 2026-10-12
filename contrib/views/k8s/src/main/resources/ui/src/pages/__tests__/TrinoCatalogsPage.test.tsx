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

jest.mock('../../api/client');

import { getTrinoCatalogs } from '../../api/client';
import TrinoCatalogsPage, { literalCredentialsWarning } from '../TrinoCatalogsPage';

/**
 * A reusable catalog saved before KDPS refused credentials written out still holds one, and a release using it would
 * keep it in its catalog ConfigMap: the page points the operator to it, naming the properties, never their values.
 */
describe('TrinoCatalogsPage', () => {
  const legacy = {
    id: 'cat-1', name: 'legacy', connectorName: 'mysql', usedBy: ['analytics/trino'],
    propertiesText: 'connector.name=mysql\nconnection-password=hunter2\n', literalCredentials: ['connection-password'],
  };
  const fromEnv = {
    id: 'cat-2', name: 'pg', connectorName: 'postgresql', usedBy: [],
    propertiesText: 'connector.name=postgresql\nconnection-password=${ENV:PG_PASSWORD}\n', literalCredentials: [],
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('flags the catalogs that write a credential out', async () => {
    (getTrinoCatalogs as jest.Mock).mockResolvedValue([legacy, fromEnv]);

    render(<TrinoCatalogsPage />);

    expect(await screen.findByText('A catalog writes a credential out: legacy')).toBeInTheDocument();
    expect(screen.getAllByText('Credential written out')).toHaveLength(1);
    expect(screen.queryByText(/hunter2/)).not.toBeInTheDocument();
  });

  it('shows no warning when every catalog reads its credentials from the environment', async () => {
    (getTrinoCatalogs as jest.Mock).mockResolvedValue([fromEnv]);

    render(<TrinoCatalogsPage />);

    await waitFor(() => expect(screen.getByText('pg')).toBeInTheDocument());
    expect(screen.queryByText(/credential out/)).not.toBeInTheDocument();
    expect(screen.queryByText('Credential written out')).not.toBeInTheDocument();
  });

  it('names the properties and what to do', () => {
    expect(literalCredentialsWarning(fromEnv as any)).toBeUndefined();
    const warning = literalCredentialsWarning({ ...legacy, literalCredentials: ['connection-password', 's3.aws-secret-key'] } as any);
    expect(warning).toContain('connection-password, s3.aws-secret-key are written out');
    expect(warning).toContain('${ENV:KEY}');
    expect(warning).toContain('Secrets with catalog credentials');
  });
});
