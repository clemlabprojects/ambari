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

import type { GitOpsPreviewFile } from '../../../api/client';
import { editorLanguage, fileExplanation, previewTree } from '../previewTree';
import { blankProfile, copyOfProfile, forSaving } from '../profileDraft';

const file = (path: string, patch: Partial<GitOpsPreviewFile> = {}): GitOpsPreviewFile => ({
  path, origin: 'file', change: 'unchanged', mode: 'always', editable: false, protectedPath: false, binary: false,
  content: '', current: null, ...patch,
});

describe('the repository preview tree', () => {
  const files = [
    file('docs/runbook.md', { origin: 'repository' }),
    file('chart/Chart.yaml', { origin: 'chart', change: 'changed' }),
    file('chart/templates/old.yaml', { origin: 'chart', change: 'removed' }),
    file('environments/prod/values.yaml', { change: 'new' }),
    file('OWNERS.md', { mode: 'createIfMissing', change: 'kept' }),
  ];

  it('lists folders before files, each by name', () => {
    const tree = previewTree(files, false);
    expect(tree.map(n => n.name)).toEqual(['chart', 'docs', 'environments', 'OWNERS.md']);
    expect(tree[0].children!.map(n => n.name)).toEqual(['templates', 'Chart.yaml']);
  });

  it('keeps only the files the deploy writes or removes, and their folders', () => {
    const tree = previewTree(files, true);
    expect(tree.map(n => n.name)).toEqual(['chart', 'environments']);
    const leaves: string[] = [];
    const walk = (nodes: ReturnType<typeof previewTree>) => nodes.forEach(n => (n.file ? leaves.push(n.key) : walk(n.children!)));
    walk(tree);
    expect(leaves.sort()).toEqual(['chart/Chart.yaml', 'chart/templates/old.yaml', 'environments/prod/values.yaml']);
  });

  it('tells why a file can or cannot be edited', () => {
    expect(fileExplanation(file('a.yaml', { origin: 'chart' }))).toMatch(/Read-only/);
    expect(fileExplanation(file('a.yaml', { mode: 'createIfMissing', change: 'new', editable: true }))).toMatch(/you can edit it now/);
    expect(fileExplanation(file('a.yaml'))).toMatch(/every deploy/);
    expect(fileExplanation(file('docs/runbook.md', { origin: 'repository', protectedPath: true }))).toMatch(/Protected/);
  });

  it('highlights files by their name', () => {
    expect(editorLanguage('environments/prod/values.yaml')).toBe('yaml');
    expect(editorLanguage('Jenkinsfile')).toBe('java');
    expect(editorLanguage('README')).toBe('plaintext');
  });
});

describe('profile drafts', () => {
  it('copies a profile under a new name, as a profile to save', () => {
    const original = { ...blankProfile('base'), builtIn: true, version: 3 };
    const copy = copyOfProfile(original, 'team');
    expect(copy.name).toBe('team');
    expect(copy.builtIn).toBe(false);
    expect(copy.version).toBeUndefined();
    copy.files[0].path = 'changed';
    expect(original.files[0].path).toBe('environments/{{ namespace }}/values.yaml');
  });

  it('saves variables that have a name only', () => {
    const p = { ...blankProfile('x'), variables: { team: 'data', '': 'lost', ' env ': 'prod' } };
    expect(forSaving(p).variables).toEqual({ team: 'data', env: 'prod' });
  });
});
