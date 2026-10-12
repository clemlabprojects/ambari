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
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { PermissionsProvider } from '../PermissionsContext';
import { usePermissions } from '../../hooks/usePermissions';
import PermissionGuard from '../../components/common/PermissionGuard';
import RequirePermission from '../../components/common/RequirePermission';

/** The UI takes the role from GET /users/me/permissions and shows a read-only view whenever it cannot. */
describe('PermissionsProvider', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    jest.spyOn(console, 'warn').mockImplementation(() => undefined);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  const respond = (status: number, body: unknown) => fetchMock.mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    text: async () => (typeof body === 'string' ? body : JSON.stringify(body)),
  });

  const Probe = () => {
    const { permissions, loading } = usePermissions();
    if (loading) return <span>loading</span>;
    return <span data-testid="role">{`${permissions?.role} write=${permissions?.canWrite} configure=${permissions?.canConfigure}`}</span>;
  };

  const renderWithProvider = (children: React.ReactNode) => render(
    <PermissionsProvider>
      <MemoryRouter>{children}</MemoryRouter>
    </PermissionsProvider>,
  );

  it('uses the role returned by the backend', async () => {
    respond(200, { role: 'OPERATOR', canConfigure: false, canWrite: true });
    renderWithProvider(<Probe />);

    expect(await screen.findByTestId('role')).toHaveTextContent('OPERATOR write=true configure=false');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0][0])).toMatch(/\/resources\/api\/users\/me\/permissions$/);
  });

  it('falls back to a read-only view when the role cannot be read', async () => {
    respond(500, 'boom');
    renderWithProvider(<Probe />);

    expect(await screen.findByTestId('role')).toHaveTextContent('VIEWER write=false configure=false');
  });

  it('falls back to a read-only view when the request fails', async () => {
    fetchMock.mockRejectedValue(new Error('network down'));
    renderWithProvider(<Probe />);

    expect(await screen.findByTestId('role')).toHaveTextContent('VIEWER write=false configure=false');
  });

  it('does not trust a malformed answer', async () => {
    respond(200, { role: 'ROOT', canConfigure: true, canWrite: true });
    renderWithProvider(<Probe />);

    expect(await screen.findByTestId('role')).toHaveTextContent('VIEWER write=false configure=false');
  });

  it('only counts flags the backend explicitly set to true', async () => {
    respond(200, { role: 'ADMIN', canConfigure: 'yes', canWrite: true });
    renderWithProvider(<Probe />);

    expect(await screen.findByTestId('role')).toHaveTextContent('ADMIN write=true configure=false');
  });

  it('hides write actions from a viewer and keeps them for an operator', async () => {
    respond(200, { role: 'VIEWER', canConfigure: false, canWrite: false });
    const { unmount } = renderWithProvider(
      <><Probe /><PermissionGuard requires="canWrite"><button>Uninstall</button></PermissionGuard></>,
    );
    await screen.findByTestId('role');
    expect(screen.queryByRole('button', { name: 'Uninstall' })).not.toBeInTheDocument();
    unmount();

    respond(200, { role: 'OPERATOR', canConfigure: false, canWrite: true });
    renderWithProvider(<PermissionGuard requires="canWrite"><button>Uninstall</button></PermissionGuard>);
    expect(await screen.findByRole('button', { name: 'Uninstall' })).toBeInTheDocument();
  });

  it('explains why a configuration page is refused to an operator', async () => {
    respond(200, { role: 'OPERATOR', canConfigure: false, canWrite: true });
    renderWithProvider(<RequirePermission requires="canConfigure"><div>Platform contexts editor</div></RequirePermission>);

    expect(await screen.findByText('This page is for KDPS administrators')).toBeInTheDocument();
    expect(screen.queryByText('Platform contexts editor')).not.toBeInTheDocument();
  });

  it('opens a configuration page for an admin', async () => {
    respond(200, { role: 'ADMIN', canConfigure: true, canWrite: true });
    renderWithProvider(<RequirePermission requires="canConfigure"><div>Platform contexts editor</div></RequirePermission>);

    await waitFor(() => expect(screen.getByText('Platform contexts editor')).toBeInTheDocument());
  });
});
