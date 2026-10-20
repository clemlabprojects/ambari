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

import type { GitOpsPreviewFile } from '../../api/client';

/** A folder or file of the repository preview tree. */
export interface PreviewNode {
  key: string;
  name: string;
  /** Set on a file. */
  file?: GitOpsPreviewFile;
  children?: PreviewNode[];
}

/** Whether the deploy writes or removes the file. */
export const isChange = (f: GitOpsPreviewFile): boolean =>
  f.change === 'new' || f.change === 'changed' || f.change === 'removed';

/**
 * The preview's files as a folder tree, folders first then files, each by name. With `onlyChanges`, only the files
 * the deploy writes or removes, and the folders holding them.
 */
export function previewTree(files: GitOpsPreviewFile[], onlyChanges: boolean): PreviewNode[] {
  const root: PreviewNode = { key: '', name: '', children: [] };
  for (const f of files) {
    if (onlyChanges && !isChange(f)) continue;
    const parts = f.path.split('/');
    let node = root;
    parts.forEach((part, i) => {
      const key = parts.slice(0, i + 1).join('/');
      const last = i === parts.length - 1;
      let child = node.children!.find(c => c.key === key && !!c.file === last);
      if (!child) {
        child = last ? { key, name: part, file: f } : { key, name: part, children: [] };
        node.children!.push(child);
      }
      node = child;
    });
  }
  const sort = (n: PreviewNode) => {
    n.children?.sort((a, b) => (a.file ? 1 : 0) - (b.file ? 1 : 0) || a.name.localeCompare(b.name));
    n.children?.forEach(sort);
  };
  sort(root);
  return root.children!;
}

/** What the deploy does to a file, in a few words. */
export function changeLabel(f: GitOpsPreviewFile): string {
  switch (f.change) {
    case 'new': return 'new';
    case 'changed': return 'changed';
    case 'removed': return 'removed';
    case 'kept': return 'kept as is';
    default: return 'unchanged';
  }
}

/** Why a file can or cannot be edited before the deploy, for the operator. */
export function fileExplanation(f: GitOpsPreviewFile): string {
  if (f.origin === 'chart') {
    return f.change === 'removed'
      ? 'Not in this chart version: removed with the chart, which is replaced as a whole.'
      : 'Copied from the chart package as published. Read-only: an edit would fork the chart.';
  }
  if (f.origin === 'repository') {
    return f.protectedPath
      ? 'Protected by the profile: KDPS never writes nor deletes it.'
      : 'Not written by the profile: KDPS leaves it as it is.';
  }
  if (f.change === 'kept') {
    return 'Created once and already in the repository: KDPS never rewrites it. Edit it in Git.';
  }
  if (f.editable) {
    return 'Created once, then owned by your team: you can edit it now, and KDPS never rewrites it afterwards.';
  }
  return 'Written again by every deploy: change the wizard setting or the profile template that produces it.';
}

/** The Monaco language of a file, from its name. */
export function editorLanguage(path: string): string {
  const name = path.split('/').pop() || '';
  if (/\.ya?ml$/i.test(name)) return 'yaml';
  if (/\.json$/i.test(name)) return 'json';
  if (/\.md$/i.test(name)) return 'markdown';
  // Monaco has no Groovy; Java highlights a Jenkinsfile well enough.
  if (/^Jenkinsfile/.test(name) || /\.groovy$/i.test(name)) return 'java';
  if (/\.(sh|bash)$/i.test(name)) return 'shell';
  if (/\.properties$/i.test(name)) return 'ini';
  return 'plaintext';
}
