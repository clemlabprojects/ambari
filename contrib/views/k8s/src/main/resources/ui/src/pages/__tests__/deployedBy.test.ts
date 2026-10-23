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

import { deployedBy } from '../deployedBy';

describe('how a release reaches the cluster', () => {
  it('tells Helm, Flux and a GitOps profile apart', () => {
    expect(deployedBy({ deploymentMode: 'DIRECT_HELM' }).mark.label).toBe('Helm');
    expect(deployedBy({ deploymentMode: 'FLUX_GITOPS' }).mark.label).toBe('Git · Flux');
    expect(deployedBy({ deploymentMode: 'FLUX_GITOPS', gitProfile: 'flux' }).state).toBeUndefined();
    expect(deployedBy({ deploymentMode: 'FLUX_GITOPS', gitProfile: 'environments' }).mark.label).toBe('Git · environments');
  });

  it('says where a release written with a profile stands', () => {
    const p = { deploymentMode: 'FLUX_GITOPS', gitProfile: 'environments' };
    expect(deployedBy({ ...p, installed: false }).state!.label).toBe('In Git, not installed yet');
    expect(deployedBy({ ...p, installed: false, gitPrState: 'open' }).state!.label).toBe('Pull request open');
    expect(deployedBy({ ...p, installed: false, gitPrState: 'merged' }).state!.label).toBe('In Git, not installed yet');
    expect(deployedBy({ ...p, installed: true }).state!.label).toBe('Installed');
  });
});
