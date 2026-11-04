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
import * as fs from 'fs';
import * as path from 'path';
import { fireEvent, render, screen } from '@testing-library/react';
import ContextsPage from '../ContextsPage';
import { testContextKdcAccount } from '../../api/client';

const kerberos = JSON.parse(fs.readFileSync(
  path.join(__dirname, '../../../../KDPS/contexts/capabilities/kerberos.json'), 'utf8'));

jest.mock('../../api/client', () => ({
  getContexts: jest.fn(() => Promise.resolve([{
    id: 'cdp-prod', name: 'CDP production', kind: 'EXTERNAL', secretKeys: ['kdcAdminPassword'],
    config: { realm: 'CORP.EXAMPLE.COM', kdcType: 'active-directory', kdcAdminPrincipal: 'cm-kdc@CORP.EXAMPLE.COM',
      kdcLdapUrl: 'ldaps://dc01.corp.example.com', kdcContainerDn: 'OU=cloudera,DC=corp,DC=example,DC=com' },
  }])),
  getContextSchema: jest.fn(() => Promise.resolve([kerberos])),
  saveContext: jest.fn(),
  deleteContext: jest.fn(),
  getResolvedContext: jest.fn(() => Promise.resolve(null)),
  probeRemoteContext: jest.fn(),
  probeCdpContext: jest.fn(),
  preflightContext: jest.fn(),
  testContextKdcAccount: jest.fn(),
}));

jest.setTimeout(30_000);

describe('the KDC account of a platform context', () => {
  it('is tested as saved, and the answer stays in the dialog', async () => {
    (testContextKdcAccount as jest.Mock).mockResolvedValue({ ok: true,
      message: 'The ACTIVE_DIRECTORY KDC of realm CORP.EXAMPLE.COM accepts cm-kdc@CORP.EXAMPLE.COM, and its '
        + 'organizational unit OU=cloudera,DC=corp,DC=example,DC=com can be searched.' });
    const { container } = render(<ContextsPage />);
    await screen.findByText('CDP production');

    fireEvent.click((container.querySelector('.anticon-edit') as HTMLElement).closest('button')!);
    expect(await screen.findByText('KDC account (release principals)')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Test the saved KDC account/ }));

    expect(await screen.findByText(/accepts cm-kdc@CORP.EXAMPLE.COM/)).toBeInTheDocument();
    expect(testContextKdcAccount).toHaveBeenCalledWith('cdp-prod');
  });

  it('says why the KDC refused the account', async () => {
    (testContextKdcAccount as jest.Mock).mockResolvedValue({ ok: false,
      error: 'HTTP 502: The ACTIVE_DIRECTORY KDC of realm CORP.EXAMPLE.COM did not accept cm-kdc@CORP.EXAMPLE.COM: '
        + 'invalid credentials' });
    const { container } = render(<ContextsPage />);
    await screen.findByText('CDP production');

    fireEvent.click((container.querySelector('.anticon-edit') as HTMLElement).closest('button')!);
    fireEvent.click(await screen.findByRole('button', { name: /Test the saved KDC account/ }));

    expect(await screen.findByText(/invalid credentials/)).toBeInTheDocument();
  });
});
