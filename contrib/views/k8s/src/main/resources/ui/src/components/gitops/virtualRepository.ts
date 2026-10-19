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

import type { GitOpsPreview, GitOpsPreviewFile, GitOpsProfile } from '../../api/client';

/** The example deploy a profile is previewed with: its names fill the rules' paths. */
export interface ExampleDeploy {
  release: string;
  namespace: string;
  chart?: string;
  version?: string;
}

/** What a node of the explorer is. */
export type EntryKind =
  | 'chartFolder' // the folder the chart is copied to, before a preview lists its files
  | 'rule'        // a file a rule of the profile writes, by its path as written in the profile
  | 'chart'       // a file of the chart, as the last preview downloaded it
  | 'repository'  // a file already in the repository that the profile does not write
  | 'folder';     // a folder created in the explorer that holds no file yet

export interface RepositoryEntry {
  /** Unique: `rule:<index>`, `chart-folder`, `file:<path>` or `dir:<path>`. */
  key: string;
  path: string;
  kind: EntryKind;
  ruleIndex?: number;
  /** The file as the last preview showed it. */
  file?: GitOpsPreviewFile;
  /** Why a deploy would refuse the rule. */
  problem?: string;
}

const TOKEN = /\{\{\s*([^|}]+?)\s*((?:\|[^|}]+)*)\s*\}\}/g;

/** The fields a path can name, in the order the explorer offers them. */
export const PATH_FIELDS = ['namespace', 'release', 'service', 'chart.name'] as const;

/**
 * A rule's path with the example deploy's names in place of its fields. A field the page cannot fill (one the server
 * computes) is left as written, so the operator sees it.
 */
export function examplePath(path: string, profile: GitOpsProfile, example: ExampleDeploy): string {
  return (path || '').replace(TOKEN, (whole, ref: string) => {
    const r = ref.trim();
    if (r.startsWith('vars.')) return profile.variables?.[r.slice(5)] || whole;
    const known: Record<string, string | undefined> = {
      release: example.release,
      namespace: example.namespace,
      'chart.name': example.chart,
      'chart.version': example.version,
      chartPath: profile.chartPath,
    };
    return known[r] || whole;
  });
}

/** A path relative to the repository root, without leading, trailing or doubled slashes. */
export const normalizePath = (p: string): string => (p || '').trim().split('/').map(s => s.trim()).filter(Boolean).join('/');

/** The folder holding a path, '' at the root. */
export const parentOf = (path: string): string => (path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : '');

/** A path inside a folder ('' is the root). */
export const joinPath = (folder: string, name: string): string => normalizePath(folder ? `${folder}/${name}` : name);

/**
 * The repository as the profile writes it: each rule's file by its path as written in the profile, the chart folder,
 * folders created in the explorer that hold no file yet, and, once a preview ran, the chart's files and the files
 * already in the repository. A rule the deploy would refuse says why; the checks use the example deploy's names.
 */
export function repositoryEntries(profile: GitOpsProfile, example: ExampleDeploy, preview?: GitOpsPreview | null,
                                  emptyFolders: string[] = []): RepositoryEntry[] {
  const out: RepositoryEntry[] = [];
  const previewed = new Map((preview?.files || []).map(f => [f.path, f]));
  const chartPath = normalizePath(profile.chartPath || '');
  if (chartPath) {
    const chartFiles = preview && preview.chartPath === chartPath ? preview.files.filter(f => f.origin === 'chart') : [];
    out.push({ key: 'chart-folder', path: chartPath, kind: 'chartFolder' });
    chartFiles.forEach(f => out.push({ key: `file:${f.path}`, path: f.path, kind: 'chart', file: f }));
  }
  const rendered = new Set<string>();
  const templates = new Set<string>();
  profile.files.forEach((rule, i) => {
    const path = normalizePath(rule.path);
    const example_ = normalizePath(examplePath(rule.path, profile, example));
    let problem: string | undefined;
    if (!path) {
      problem = 'No path yet.';
    } else if ((rule.path || '').split('/').some(s => s.trim() === '..' || s.trim() === '.')) {
      problem = 'The path must stay inside the repository.';
    } else if (chartPath && (example_ === chartPath || example_.startsWith(`${chartPath}/`))) {
      problem = 'Inside the chart folder, which is copied as published: no file may be written there.';
    } else if (templates.has(path) || rendered.has(example_)) {
      problem = 'Another file of the profile has the same path.';
    }
    if (path) templates.add(path);
    if (example_) rendered.add(example_);
    out.push({
      key: `rule:${i}`,
      path: path || `new file ${i + 1}`,
      kind: 'rule',
      ruleIndex: i,
      file: example_ ? previewed.get(example_) : undefined,
      problem,
    });
  });
  (preview?.files || [])
    .filter(f => f.origin === 'repository' && !rendered.has(f.path))
    .forEach(f => out.push({ key: `file:${f.path}`, path: f.path, kind: 'repository', file: f }));
  emptyFolders.map(normalizePath).filter(Boolean)
    .filter(folder => !out.some(e => e.path === folder || e.path.startsWith(`${folder}/`)))
    .forEach(folder => out.push({ key: `dir:${folder}`, path: folder, kind: 'folder' }));
  return out;
}

/** A folder or a file of the explorer. */
export interface RepositoryNode {
  key: string;
  name: string;
  /** The folder's or file's path. */
  path: string;
  /** Set on a file, on the chart folder and on an empty folder. */
  entry?: RepositoryEntry;
  children?: RepositoryNode[];
}

/** Whether a node is shown as a folder. */
export const isFolderNode = (n: RepositoryNode): boolean => !!n.children;

/** The entries as a folder tree, folders first then files, each by name. The chart folder holds the chart's files. */
export function repositoryTree(entries: RepositoryEntry[]): RepositoryNode[] {
  const root: RepositoryNode = { key: 'dir:', name: '', path: '', children: [] };
  const folder = (parts: string[]): RepositoryNode => {
    let node = root;
    parts.forEach((part, i) => {
      const path = parts.slice(0, i + 1).join('/');
      let dir = node.children!.find(c => c.path === path && c.children);
      if (!dir) {
        dir = { key: `dir:${path}`, name: part, path, children: [] };
        node.children!.push(dir);
      }
      node = dir;
    });
    return node;
  };
  for (const entry of entries) {
    const parts = entry.path.split('/').filter(Boolean);
    if (entry.kind === 'chartFolder' || entry.kind === 'folder') {
      const dir = folder(parts);
      dir.key = entry.key;
      dir.entry = entry;
      continue;
    }
    const parent = folder(parts.slice(0, -1));
    parent.children!.push({ key: entry.key, name: parts[parts.length - 1], path: entry.path, entry });
  }
  const sort = (n: RepositoryNode) => {
    n.children?.sort((a, b) => Number(!isFolderNode(a)) - Number(!isFolderNode(b)) || a.name.localeCompare(b.name));
    n.children?.forEach(sort);
  };
  sort(root);
  return root.children!;
}

/** Every folder key of a tree. */
export function folderKeys(nodes: RepositoryNode[]): string[] {
  return nodes.flatMap(n => (n.children ? [n.key, ...folderKeys(n.children)] : []));
}

/** The folder a new file goes into when a node is selected: the folder itself, or the folder of the file. */
export function targetFolder(nodes: RepositoryNode[], selectedKey: string | undefined): string {
  const find = (list: RepositoryNode[]): RepositoryNode | undefined => {
    for (const n of list) {
      if (n.key === selectedKey) return n;
      const inside = n.children && find(n.children);
      if (inside) return inside;
    }
    return undefined;
  };
  const node = selectedKey ? find(nodes) : undefined;
  if (!node) return '';
  return isFolderNode(node) ? node.path : parentOf(node.path);
}

/** The parts of a path segment: plain text, and the template fields it names. */
export function segmentParts(segment: string): Array<{ text: string; field?: string }> {
  const parts: Array<{ text: string; field?: string }> = [];
  let last = 0;
  segment.replace(TOKEN, (whole, ref: string, _filters: string, offset: number) => {
    if (offset > last) parts.push({ text: segment.slice(last, offset) });
    parts.push({ text: whole, field: ref.trim() });
    last = offset + whole.length;
    return whole;
  });
  if (last < segment.length) parts.push({ text: segment.slice(last) });
  return parts;
}
