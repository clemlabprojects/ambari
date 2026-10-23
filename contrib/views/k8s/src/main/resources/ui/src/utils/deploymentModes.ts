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

import type { GitRepo } from '../pages/GitRepositoriesPage';

/**
 * The deployment modes the deploy wizard offers. The server knows two (DIRECT_HELM and FLUX_GITOPS): "GitOps with a
 * repository layout" is FLUX_GITOPS with a file profile, written by the files the profile describes rather than by a
 * HelmRelease.
 */
export const DIRECT_HELM = 'DIRECT_HELM';
export const GITOPS_FLUX = 'FLUX_GITOPS';
export const GITOPS_LAYOUT = 'GITOPS_LAYOUT';

export const DEPLOYMENT_MODE_LABELS: Record<string, string> = {
  [DIRECT_HELM]: 'Direct Helm',
  [GITOPS_FLUX]: 'GitOps with Flux',
  [GITOPS_LAYOUT]: 'GitOps with a repository layout',
};

/** Whether a wizard mode writes the release to Git. */
export const writesToGit = (mode?: string): boolean => mode === GITOPS_FLUX || mode === GITOPS_LAYOUT;

/** The wizard mode of a deployed release: one written with a file profile is "GitOps with a repository layout". */
export function wizardMode(deploymentMode?: string, gitProfile?: string): string {
  if (deploymentMode !== GITOPS_FLUX) return DIRECT_HELM;
  return gitProfile && gitProfile !== 'flux' ? GITOPS_LAYOUT : GITOPS_FLUX;
}

/**
 * The deployment mode and Git options the server receives for the mode chosen in the wizard. A repository layout
 * sends its profile and none of the Flux settings (the profile decides how the change is delivered); Flux sends no
 * profile, so a release keeps the one it was written with and a new one gets the Flux layout.
 */
export function submittedDeployment(mode: string | undefined, git: Record<string, any> | undefined)
  : { deploymentMode: string; git: Record<string, any> | undefined } {
  if (mode === GITOPS_LAYOUT) {
    const { pathPrefix: _p, commitMode: _c, branch: _b, ...rest } = git || {};
    return { deploymentMode: GITOPS_FLUX, git: rest };
  }
  if (mode === GITOPS_FLUX) {
    const { profile: _p, fileEdits: _e, ...rest } = git || {};
    return { deploymentMode: GITOPS_FLUX, git: rest };
  }
  return { deploymentMode: DIRECT_HELM, git };
}

const SAVED_GIT_REPOS_KEY = 'k8s-view-git-repos';

/** The Git repositories saved on the Git Repositories page, in this browser. */
export function savedGitRepos(): GitRepo[] {
  try {
    const saved = JSON.parse(localStorage.getItem(SAVED_GIT_REPOS_KEY) || '[]');
    return Array.isArray(saved) ? saved : [];
  } catch {
    return [];
  }
}
