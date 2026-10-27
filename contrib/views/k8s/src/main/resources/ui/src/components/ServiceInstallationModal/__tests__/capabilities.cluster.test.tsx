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
import React from 'react';
import { render, screen } from '@testing-library/react';
import { getClusterCapabilities } from '../../../api/client';
import { invalidateCapabilities, useCapabilities, useClusterCapabilities } from '../capabilities';
import { TargetClusterContext } from '../../../context/TargetClusterContext';

jest.mock('../../../api/client', () => ({
  getClusterCapabilities: jest.fn((cluster?: string) =>
    Promise.resolve({ platform: cluster === 'k8s21' ? 'kubernetes' : 'openshift' })),
}));

const Platform: React.FC<{ cluster?: string; named?: boolean }> = ({ cluster, named }) => {
  const fromContext = useCapabilities();
  const fromName = useClusterCapabilities(cluster);
  const caps = named ? fromName : fromContext;
  return <span>{caps ? caps.platform : 'loading'}</span>;
};

beforeEach(() => {
  invalidateCapabilities();
  (getClusterCapabilities as jest.Mock).mockClear();
});

it('reads the capabilities of the cluster named, whatever cluster the page around it shows', async () => {
  // The deploy wizard sits under the cluster shown in the top bar (an OpenShift here) and deploys to k8s21.
  render(<Platform cluster="k8s21" named />);
  expect(await screen.findByText('kubernetes')).toBeTruthy();
  expect(getClusterCapabilities).toHaveBeenCalledWith('k8s21');
});

it('reads the default cluster when none is named', async () => {
  render(<TargetClusterContext.Provider value="k8s21"><Platform named /></TargetClusterContext.Provider>);
  expect(await screen.findByText('openshift')).toBeTruthy();
  expect(getClusterCapabilities).toHaveBeenCalledWith(undefined);
});

it('reads the cluster of the TargetClusterContext for the fields under the wizard', async () => {
  render(<TargetClusterContext.Provider value="k8s21"><Platform /></TargetClusterContext.Provider>);
  expect(await screen.findByText('kubernetes')).toBeTruthy();
});

it('the deploy wizard names its cluster: it reads capabilities outside the TargetClusterContext it provides', () => {
  const page = fs.readFileSync(path.join(__dirname, '../../../pages/ServiceWizardPage.tsx'), 'utf8');
  expect(page).toContain('useClusterCapabilities(targetCluster)');
  expect(page).not.toMatch(/\buseCapabilities\(\)/);
});
