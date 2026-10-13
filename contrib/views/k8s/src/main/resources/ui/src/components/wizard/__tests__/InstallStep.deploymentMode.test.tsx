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
import { render, screen } from '@testing-library/react';
import InstallStep from '../InstallStep';

jest.mock('../../../api/client', () => ({
  getClusterCapabilities: jest.fn(() => Promise.resolve({ platform: 'kubernetes' })),
}));
// The installation modal pulls in the service icons, which jest does not load; the general step does not use it.
jest.mock('../../ServiceInstallationModal', () => ({
  resolveAuthCascade: jest.fn(),
  applyAuthCascadeToFields: jest.fn(),
}));

const renderGeneral = (props: { upgrade?: boolean; deployedMode?: string; deploymentMode: string }) =>
  render(
    <InstallStep
      definition={{}}
      data={{ releaseName: 'trino', namespace: 'trino', deploymentMode: props.deploymentMode,
        git: { repoUrl: 'https://git.example.org/platform/gitops.git' } }}
      onChange={() => undefined}
      mode="general"
      upgrade={props.upgrade}
      deployedMode={props.deployedMode}
    />,
  );

describe('InstallStep deployment mode', () => {
  it('Upgrade/Config of a release deployed with Flux GitOps cannot switch it to Direct Helm', () => {
    renderGeneral({ upgrade: true, deployedMode: 'FLUX_GITOPS', deploymentMode: 'FLUX_GITOPS' });
    expect(screen.getByRole('radio', { name: 'Direct Helm' })).toBeDisabled();
    expect(screen.getByRole('radio', { name: 'Flux GitOps' })).toBeDisabled();
    expect(screen.getByRole('radio', { name: 'Flux GitOps' })).toBeChecked();
  });

  it('an install offers both modes', () => {
    renderGeneral({ deploymentMode: 'DIRECT_HELM' });
    expect(screen.getByRole('radio', { name: 'Direct Helm' })).toBeEnabled();
    expect(screen.getByRole('radio', { name: 'Flux GitOps' })).toBeEnabled();
  });

  it('Upgrade/Config of a release deployed with Helm offers both modes', () => {
    renderGeneral({ upgrade: true, deployedMode: 'DIRECT_HELM', deploymentMode: 'DIRECT_HELM' });
    expect(screen.getByRole('radio', { name: 'Flux GitOps' })).toBeEnabled();
  });
});
