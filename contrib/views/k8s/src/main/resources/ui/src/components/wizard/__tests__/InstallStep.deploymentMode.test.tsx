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
import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import InstallStep from '../InstallStep';
import { TargetClusterContext } from '../../../context/TargetClusterContext';

jest.mock('../../../api/client', () => ({
  getClusterCapabilities: jest.fn(() => Promise.resolve({ platform: 'kubernetes' })),
  listGitOpsProfiles: jest.fn(() => Promise.resolve({ canConfigure: false, items: [
    { name: 'flux', kind: 'flux', builtIn: true, variables: {}, delivery: { mode: 'commit' }, files: [], protect: [] },
    { name: 'environments', kind: 'files', chartPath: 'chart', variables: {},
      delivery: { mode: 'pullRequest', branchPrefix: 'kdps/' }, files: [], protect: [] },
  ] })),
}));
// The installation modal pulls in the service icons, which jest does not load; the general step does not use it.
jest.mock('../../ServiceInstallationModal', () => ({
  resolveAuthCascade: jest.fn(),
  applyAuthCascadeToFields: jest.fn(),
}));

const renderGeneral = (props: { upgrade?: boolean; deployedMode?: string; deploymentMode: string; profile?: string }) =>
  render(
    <InstallStep
      definition={{}}
      data={{ releaseName: 'trino', namespace: 'trino', deploymentMode: props.deploymentMode,
        git: { repoUrl: 'https://git.example.org/platform/gitops.git', profile: props.profile } }}
      onChange={() => undefined}
      mode="general"
      upgrade={props.upgrade}
      deployedMode={props.deployedMode}
    />,
  );

describe('InstallStep deployment mode', () => {
  beforeEach(() => localStorage.clear());

  it('Upgrade/Config of a release written to Git cannot switch it to another mode', () => {
    renderGeneral({ upgrade: true, deployedMode: 'FLUX_GITOPS', deploymentMode: 'FLUX_GITOPS' });
    expect(screen.getByRole('radio', { name: 'Direct Helm' })).toBeDisabled();
    expect(screen.getByRole('radio', { name: 'GitOps with Flux' })).toBeDisabled();
    expect(screen.getByRole('radio', { name: 'GitOps with a repository layout' })).toBeDisabled();
    expect(screen.getByRole('radio', { name: 'GitOps with Flux' })).toBeChecked();
  });

  it('an install offers the three modes', () => {
    renderGeneral({ deploymentMode: 'DIRECT_HELM' });
    expect(screen.getByRole('radio', { name: 'Direct Helm' })).toBeEnabled();
    expect(screen.getByRole('radio', { name: 'GitOps with Flux' })).toBeEnabled();
    expect(screen.getByRole('radio', { name: 'GitOps with a repository layout' })).toBeEnabled();
  });

  it('Upgrade/Config of a release deployed with Helm offers the GitOps modes', () => {
    renderGeneral({ upgrade: true, deployedMode: 'DIRECT_HELM', deploymentMode: 'DIRECT_HELM' });
    expect(screen.getByRole('radio', { name: 'GitOps with Flux' })).toBeEnabled();
    expect(screen.getByRole('radio', { name: 'GitOps with a repository layout' })).toBeEnabled();
  });

  it('a Flux release left without a path prefix goes to the folder of its cluster', () => {
    const { unmount } = renderGeneral({ deploymentMode: 'FLUX_GITOPS' });
    expect(screen.getByPlaceholderText('clusters/default')).toBeInTheDocument();
    unmount();
    render(
      <TargetClusterContext.Provider value="k8s21">
        <InstallStep definition={{}} mode="general" onChange={() => undefined}
          data={{ releaseName: 'trino', namespace: 'trino', deploymentMode: 'FLUX_GITOPS',
            git: { repoUrl: 'https://git.example.org/platform/gitops.git' } }} />
      </TargetClusterContext.Provider>,
    );
    expect(screen.getByPlaceholderText('clusters/k8s21')).toBeInTheDocument();
  });

  it('a repository layout asks for its profile and the repository, not the Flux settings', async () => {
    renderGeneral({ deploymentMode: 'GITOPS_LAYOUT', profile: 'environments' });
    expect(await screen.findByText('Written as the profile environments says')).toBeInTheDocument();
    expect(screen.getByText('GitOps profile')).toBeInTheDocument();
    expect(screen.getByText('Git repository URL')).toBeInTheDocument();
    expect(screen.queryByText('Path prefix')).not.toBeInTheDocument();
    expect(screen.queryByText('Commit mode')).not.toBeInTheDocument();
  });

  it('GitOps with Flux asks for the Flux settings and no profile', async () => {
    renderGeneral({ deploymentMode: 'FLUX_GITOPS' });
    await waitFor(() => expect(screen.getByText('Path prefix')).toBeInTheDocument());
    expect(screen.getByText('Commit mode')).toBeInTheDocument();
    expect(screen.queryByText('GitOps profile')).not.toBeInTheDocument();
  });

  it('lists the saved Git repositories without the Git Repositories page being opened first', () => {
    localStorage.setItem('k8s-view-git-repos', JSON.stringify([
      { id: 'r1', name: 'platform', url: 'https://git.example.org/platform/gitops.git', type: 'HTTPS' }]));
    renderGeneral({ deploymentMode: 'FLUX_GITOPS' });
    expect(screen.getByText('Choose a saved repository, or enter its URL below')).toBeInTheDocument();
  });
});
