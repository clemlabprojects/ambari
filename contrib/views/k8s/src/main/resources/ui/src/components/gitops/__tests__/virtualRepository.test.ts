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

import type { GitOpsPreview, GitOpsPreviewFile } from '../../../api/client';
import { blankProfile } from '../profileDraft';
import { chartEntries, chartEntryOf, examplePath, folderKeys, joinPath, repositoryEntries, repositoryTree, segmentParts, targetFolder } from '../virtualRepository';

const example = { release: 'trino', namespace: 'analytics', chart: 'trino', version: '1.43.20' };

const profile = () => {
  const p = blankProfile('environments');
  p.variables = { team: 'data' };
  p.files.push({ path: 'environments/{{ namespace }}/README.md', mode: 'createIfMissing', content: '# {{ release }}\n' });
  p.files.push({ path: 'teams/{{ vars.team }}/{{ release }}.yaml', mode: 'always', content: '' });
  return p;
};

const previewed = (path: string, patch: Partial<GitOpsPreviewFile> = {}): GitOpsPreviewFile => ({
  path, origin: 'file', change: 'new', mode: 'always', editable: false, protectedPath: false, binary: false,
  content: 'x', current: null, ...patch,
});

describe('the files of a profile, as an explorer shows them', () => {
  it('fills a path with the example deploy and the variables, and leaves what it cannot fill', () => {
    expect(examplePath('environments/{{ namespace }}/{{ release }}.yaml', profile(), example)).toBe('environments/analytics/trino.yaml');
    expect(examplePath('teams/{{ vars.team }}/x', profile(), example)).toBe('teams/data/x');
    expect(examplePath('{{ chartPath }}-values/{{ timestamp }}.yaml', profile(), example)).toBe('chart-values/{{ timestamp }}.yaml');
  });

  it('lists each file by its path as the profile writes it, and the chart folder', () => {
    const entries = repositoryEntries(profile(), example);
    expect(entries.map(e => [e.kind, e.path])).toEqual([
      ['chartFolder', 'chart'],
      ['rule', 'environments/{{ namespace }}/values.yaml'],
      ['rule', 'environments/{{ namespace }}/README.md'],
      ['rule', 'teams/{{ vars.team }}/{{ release }}.yaml'],
    ]);
    expect(entries.every(e => !e.problem)).toBe(true);
  });

  it('says why a deploy would refuse a file', () => {
    const p = profile();
    p.files.push({ path: 'chart/values.yaml', mode: 'always', content: '' });
    p.files.push({ path: 'environments/{{ namespace }}/values.yaml', mode: 'always', content: '' });
    p.files.push({ path: '../outside.yaml', mode: 'always', content: '' });
    p.files.push({ path: '', mode: 'always', content: '' });
    const problems = repositoryEntries(p, example).filter(e => e.problem).map(e => e.problem);
    expect(problems).toEqual([
      expect.stringMatching(/chart folder/),
      expect.stringMatching(/same path/),
      expect.stringMatching(/inside the repository/),
      expect.stringMatching(/No path/),
    ]);
  });

  it('puts the chart files of a preview in the chart folder and shows what the repository already holds', () => {
    const preview: GitOpsPreview = {
      profile: 'environments', chart: 'trino', version: '1.43.20', chartPath: 'chart', notes: [],
      files: [
        previewed('chart/Chart.yaml', { origin: 'chart' }),
        previewed('chart/templates/old.yaml', { origin: 'chart', change: 'removed' }),
        previewed('environments/analytics/values.yaml', { change: 'changed', current: 'y' }),
        previewed('environments/team-b/values.yaml', { origin: 'repository', change: 'unchanged' }),
        previewed('docs/runbook.md', { origin: 'repository', change: 'unchanged', protectedPath: true }),
      ],
    };
    const entries = repositoryEntries(profile(), example, preview);
    expect(entries.find(e => e.key === 'rule:0')!.file!.change).toBe('changed');
    expect(entries.find(e => e.key === 'rule:1')!.file).toBeUndefined();
    const tree = repositoryTree(entries);
    const chart = tree.find(n => n.key === 'chart-folder')!;
    expect(chart.children!.map(n => n.name)).toEqual(['templates', 'Chart.yaml']);
    const environments = tree.find(n => n.name === 'environments')!;
    expect(environments.children!.map(n => n.name)).toEqual(['{{ namespace }}', 'team-b']);
  });

  it('keeps a folder created empty until a file goes into it', () => {
    const p = profile();
    expect(repositoryEntries(p, example, null, ['ci', 'environments']).filter(e => e.kind === 'folder').map(e => e.path))
      .toEqual(['ci']);
  });

  it('is a tree with folders first, and a new file goes into the selected folder or next to the selected file', () => {
    const nodes = repositoryTree(repositoryEntries(profile(), example));
    expect(nodes.map(n => n.name)).toEqual(['chart', 'environments', 'teams']);
    expect(nodes[1].children![0].children!.map(n => n.name)).toEqual(['README.md', 'values.yaml']);
    expect(folderKeys(nodes)).toContain('dir:environments/{{ namespace }}');
    expect(targetFolder(nodes, 'dir:environments/{{ namespace }}')).toBe('environments/{{ namespace }}');
    expect(targetFolder(nodes, 'rule:2')).toBe('teams/{{ vars.team }}');
    expect(targetFolder(nodes, undefined)).toBe('');
    expect(joinPath('environments/{{ namespace }}', 'ci//deploy.yaml ')).toBe('environments/{{ namespace }}/ci/deploy.yaml');
  });

  it('tells the template fields of a name apart from its text', () => {
    expect(segmentParts('{{ release }}-values.yaml')).toEqual([
      { text: '{{ release }}', field: 'release' }, { text: '-values.yaml' },
    ]);
  });
});

describe('files beside the chart in the chart folder', () => {
  it('lets the profile write in a folder of the chart folder, never in the chart\'s own entries', () => {
    const p = profile();
    p.files.push({ path: 'chart/environments/{{ namespace }}.yaml', mode: 'always', content: '' });
    p.files.push({ path: 'chart/templates/extra.yaml', mode: 'always', content: '' });
    p.files.push({ path: 'chart/dashboards/extra.json', mode: 'always', content: '' });
    const preview: GitOpsPreview = {
      profile: 'environments', chart: 'trino', version: '1.43.20', chartPath: 'chart', notes: [],
      files: [previewed('chart/Chart.yaml', { origin: 'chart' }), previewed('chart/dashboards/a.json', { origin: 'chart' })],
    };
    const byPath = (entries: ReturnType<typeof repositoryEntries>) =>
      Object.fromEntries(entries.filter(e => e.kind === 'rule').map(e => [e.path, e.problem]));

    const before = byPath(repositoryEntries(p, example));
    expect(before['chart/environments/{{ namespace }}.yaml']).toBeUndefined();
    expect(before['chart/templates/extra.yaml']).toMatch(/Part of the chart \(templates\)/);
    // A folder of the chart's package is only known once a preview listed its files.
    expect(before['chart/dashboards/extra.json']).toBeUndefined();
    expect(byPath(repositoryEntries(p, example, preview))['chart/dashboards/extra.json']).toMatch(/Part of the chart \(dashboards\)/);
    expect(chartEntryOf('chart', 'chart/environments/a.yaml', chartEntries('chart', preview))).toBeUndefined();
  });
});
