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

import type { GitOpsProfile } from '../../api/client';

/** A new file profile: the chart in one folder and one values file per environment, delivered by pull request. */
export function blankProfile(name: string): GitOpsProfile {
  return {
    name,
    description: '',
    kind: 'files',
    chartPath: 'chart',
    variables: {},
    delivery: { mode: 'pullRequest', branchPrefix: 'kdps/' },
    files: [{ path: 'environments/{{ namespace }}/values.yaml', mode: 'always', content: '{{ values | toYaml }}\n' }],
    protect: [],
  };
}

/** A copy of a profile under another name, to edit and save as a new one. */
export function copyOfProfile(profile: GitOpsProfile, name: string): GitOpsProfile {
  const copy: GitOpsProfile = JSON.parse(JSON.stringify(profile));
  copy.name = name;
  copy.builtIn = false;
  delete copy.version;
  copy.description = profile.description ? `${profile.description} (copy of ${profile.name})` : `Copy of ${profile.name}`;
  return copy;
}

/** The profile as saved: variables without a name are left out. */
export function forSaving(profile: GitOpsProfile): GitOpsProfile {
  const variables = Object.fromEntries(
    Object.entries(profile.variables || {}).filter(([k]) => k.trim() !== '').map(([k, v]) => [k.trim(), v]));
  return { ...profile, variables };
}
