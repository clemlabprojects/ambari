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
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import GitOpsProfilesPage from '../GitOpsProfilesPage';
import { blankProfile } from '../../components/gitops/profileDraft';

const mockExample = { ...blankProfile('gitops-example'), description: 'One values file per environment', version: 2 };

jest.mock('../../api/client', () => ({
  listGitOpsProfiles: jest.fn(() => Promise.resolve({ canConfigure: true, items: [
    { name: 'flux', kind: 'flux', builtIn: true, description: 'Flux layout', variables: {}, delivery: { mode: 'commit' }, files: [], protect: [] },
    mockExample,
  ] })),
  getHelmRepos: jest.fn(() => Promise.resolve([])),
  previewGitOpsProfile: jest.fn(),
  saveGitOpsProfile: jest.fn(),
  deleteGitOpsProfile: jest.fn(),
}));
jest.mock('../../hooks/usePermissions', () => ({
  usePermissions: () => ({ permissions: { role: 'ADMIN', canConfigure: true, canWrite: true }, loading: false }),
}));
jest.mock('@monaco-editor/react', () => ({
  __esModule: true,
  default: ({ value }: { value?: string }) => <pre>{value}</pre>,
  DiffEditor: () => <pre />,
}));

describe('the GitOps profiles page', () => {
  it('opens on the list of profiles, and a profile only once chosen', async () => {
    render(<MemoryRouter><GitOpsProfilesPage /></MemoryRouter>);

    expect(await screen.findByText('gitops-example')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /New profile/ })).toBeInTheDocument();
    expect(screen.queryByText('Settings')).not.toBeInTheDocument();

    fireEvent.click(screen.getByText('gitops-example'));

    expect(await screen.findByText('Settings')).toBeInTheDocument();
    expect(screen.getByText('FILES IN THE GIT REPOSITORY')).toBeInTheDocument();
    expect(screen.getByText('How a deploy reaches the repository')).toBeInTheDocument();
    // The first file is open in the editor.
    expect(screen.getByDisplayValue('environments/{{ namespace }}/values.yaml')).toBeInTheDocument();

    // The fields reference opens from the header, and from the file it adds a field to the path.
    fireEvent.click(screen.getByRole('button', { name: /Fields and filters/ }));
    expect(await screen.findByText('secretValues.secretName')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('Close'));
    fireEvent.click(screen.getAllByRole('button', { name: /question-circle/ }).find(b => !/Fields and filters/.test(b.textContent || ''))!);
    fireEvent.click((await screen.findAllByRole('button', { name: 'Path' }))[0]);
    expect(screen.getByDisplayValue('environments/{{ namespace }}/values.yaml{{ release }}')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /GitOps profiles/ }));
    expect(await screen.findByRole('button', { name: /New profile/ })).toBeInTheDocument();
  });
});
