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

import type { HelmRelease } from '../types';

export interface Mark {
  label: string;
  color: string;
  tip: string;
}

/**
 * How a release reaches the cluster: installed by KDPS with Helm, written to Git as a HelmRelease Flux applies, or
 * written to Git with a GitOps profile for an Argo CD or a CI to deploy. For the last, `state` says where the release
 * stands, since KDPS does not install it: what Helm reports only applies once something installed it.
 */
export function deployedBy(release: Pick<HelmRelease, 'deploymentMode' | 'gitProfile' | 'installed' | 'gitPrState'>)
  : { mark: Mark; state?: Mark } {
  if (release.deploymentMode !== 'FLUX_GITOPS') {
    return { mark: { label: 'Helm', color: 'default', tip: 'Installed by KDPS with Helm.' } };
  }
  const profile = release.gitProfile;
  if (!profile || profile === 'flux') {
    return { mark: { label: 'Git · Flux', color: 'blue', tip: 'Written to Git as a HelmRelease, which Flux applies.' } };
  }
  const mark = {
    label: `Git · ${profile}`, color: 'geekblue',
    tip: `Written to Git with the GitOps profile ${profile}. Your Argo CD or CI deploys it from the repository.`,
  };
  const prState = (release.gitPrState || '').toLowerCase();
  if (prState && prState !== 'merged' && prState !== 'closed') {
    return { mark, state: { label: 'Pull request open', color: 'purple', tip: 'The change waits for its pull request to be merged; what reads the repository installs it then.' } };
  }
  if (release.installed === false) {
    return { mark, state: { label: 'In Git, not installed yet', color: 'default', tip: 'KDPS wrote the release to Git. Your Argo CD or CI has not installed it on the cluster yet.' } };
  }
  return { mark, state: { label: 'Installed', color: 'green', tip: 'Helm on the cluster lists the release.' } };
}
