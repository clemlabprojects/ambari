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

jest.mock('../../api/client');

import { getSecurityConfig, getSecuritySchema, saveSecurityConfig } from '../../api/client';
import GlobalSecurityPage from '../GlobalSecurityPage';

/**
 * Saving the security profiles stores what the page sends and may remove what it leaves out, so the page only saves
 * once it has loaded them, and tells the server which ones it showed: a profile created since is never removed.
 */
describe('GlobalSecurityPage', () => {
  const user = userEvent.setup();

  beforeEach(() => {
    jest.clearAllMocks();
    (getSecuritySchema as jest.Mock).mockResolvedValue(undefined);
    (saveSecurityConfig as jest.Mock).mockResolvedValue(undefined);
  });

  const saveButton = () => screen.getByRole('button', { name: /Save/ });

  it('turns saving and deleting off while the profiles have not loaded', async () => {
    (getSecurityConfig as jest.Mock).mockRejectedValue(new Error('The security profiles could not be read'));

    render(<GlobalSecurityPage />);

    expect(await screen.findByText('The security profiles could not be loaded.')).toBeInTheDocument();
    await waitFor(() => expect(saveButton()).toBeDisabled());
    expect(screen.getByRole('button', { name: /Delete profile/ })).toBeDisabled();
    await user.click(saveButton());
    expect(saveSecurityConfig).not.toHaveBeenCalled();
  });

  it('allows saving again once a refresh loads them', async () => {
    (getSecurityConfig as jest.Mock)
      .mockRejectedValueOnce(new Error('The security profiles could not be read'))
      .mockResolvedValue({ defaultProfile: 'corp-ldap', profiles: { 'corp-ldap': { mode: 'ldap' } } });

    render(<GlobalSecurityPage />);
    await screen.findByText('The security profiles could not be loaded.');
    await user.click(screen.getByRole('button', { name: /Refresh/ }));

    await waitFor(() => expect(saveButton()).toBeEnabled());
    expect(screen.queryByText('The security profiles could not be loaded.')).not.toBeInTheDocument();
  });

  it('names the profiles it loaded when it saves', async () => {
    (getSecurityConfig as jest.Mock).mockResolvedValue({
      defaultProfile: 'corp-ldap',
      profiles: { 'corp-ldap': { mode: 'ldap' }, 'ctx-oidc-default': { mode: 'oidc', derivedFromContext: 'default' } },
    });

    render(<GlobalSecurityPage />);
    await waitFor(() => expect(getSecurityConfig).toHaveBeenCalled());
    await waitFor(() => expect(saveButton()).toBeEnabled());
    await user.click(saveButton());

    await waitFor(() => expect(saveSecurityConfig).toHaveBeenCalledTimes(1));
    const payload = (saveSecurityConfig as jest.Mock).mock.calls[0][0];
    expect(payload.loadedProfiles.sort()).toEqual(['corp-ldap', 'ctx-oidc-default']);
    expect(Object.keys(payload.profiles).sort()).toEqual(['corp-ldap', 'ctx-oidc-default']);
    expect(payload.defaultProfile).toBe('corp-ldap');
  });

  it('shows a stored secret as set, never its mask, and leaves it blank so the server keeps it', async () => {
    (getSecuritySchema as jest.Mock).mockResolvedValue({
      properties: [
        { name: 'mode', type: 'select', default: 'none' },
        { name: 'ldap.url', type: 'string', displayName: 'LDAP URL', modes: ['ldap'] },
        { name: 'ldap.bindPassword', type: 'password', displayName: 'Bind password', modes: ['ldap'] },
      ],
    });
    (getSecurityConfig as jest.Mock).mockResolvedValue({
      defaultProfile: 'corp-ldap',
      profiles: { 'corp-ldap': { mode: 'ldap', ldap: { url: 'ldaps://ldap.corp', bindPassword: '********', bindPasswordSet: true } } },
    });

    render(<GlobalSecurityPage />);

    const password = await screen.findByPlaceholderText('Set - leave blank to keep');
    expect(password).toHaveValue('');
    await waitFor(() => expect(saveButton()).toBeEnabled());
    await user.click(saveButton());

    await waitFor(() => expect(saveSecurityConfig).toHaveBeenCalledTimes(1));
    const sent = (saveSecurityConfig as jest.Mock).mock.calls[0][0].profiles['corp-ldap'];
    expect(sent.ldap.url).toBe('ldaps://ldap.corp');
    expect(sent.ldap.bindPassword ?? '').toBe('');
  });
});
