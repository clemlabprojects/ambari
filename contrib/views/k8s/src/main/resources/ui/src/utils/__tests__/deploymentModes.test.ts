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

import { savedGitRepos, submittedDeployment, wizardMode, writesToGit } from '../deploymentModes';

describe('the deployment modes of the wizard', () => {
  it('shows a release written with a file profile as a repository layout', () => {
    expect(wizardMode('DIRECT_HELM', undefined)).toBe('DIRECT_HELM');
    expect(wizardMode('FLUX_GITOPS', undefined)).toBe('FLUX_GITOPS');
    expect(wizardMode('FLUX_GITOPS', 'flux')).toBe('FLUX_GITOPS');
    expect(wizardMode('FLUX_GITOPS', 'environments')).toBe('GITOPS_LAYOUT');
    expect(writesToGit('GITOPS_LAYOUT') && writesToGit('FLUX_GITOPS') && !writesToGit('DIRECT_HELM')).toBe(true);
  });

  it('sends a repository layout as a GitOps deploy with its profile and no Flux setting', () => {
    const git = { repoUrl: 'u', baseBranch: 'main', profile: 'environments', pathPrefix: 'p', commitMode: 'PR_MODE', branch: 'b', authToken: 't' };
    expect(submittedDeployment('GITOPS_LAYOUT', git)).toEqual({
      deploymentMode: 'FLUX_GITOPS',
      git: { repoUrl: 'u', baseBranch: 'main', profile: 'environments', authToken: 't' },
    });
  });

  it('sends GitOps with Flux without a profile, and Direct Helm unchanged', () => {
    const git = { repoUrl: 'u', profile: 'environments', fileEdits: { a: 'b' }, commitMode: 'PR_MODE' };
    expect(submittedDeployment('FLUX_GITOPS', git)).toEqual({ deploymentMode: 'FLUX_GITOPS', git: { repoUrl: 'u', commitMode: 'PR_MODE' } });
    expect(submittedDeployment('DIRECT_HELM', git)).toEqual({ deploymentMode: 'DIRECT_HELM', git });
    expect(submittedDeployment(undefined, undefined).deploymentMode).toBe('DIRECT_HELM');
  });

  it('reads the saved Git repositories, and nothing from damaged storage', () => {
    localStorage.setItem('k8s-view-git-repos', JSON.stringify([{ id: 'r1', name: 'platform', url: 'u', type: 'HTTPS' }]));
    expect(savedGitRepos().map(r => r.id)).toEqual(['r1']);
    localStorage.setItem('k8s-view-git-repos', '{not json');
    expect(savedGitRepos()).toEqual([]);
  });
});
