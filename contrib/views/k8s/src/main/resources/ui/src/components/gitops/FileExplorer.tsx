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

import React from 'react';
import { Button, Input, Popconfirm, Space, Tooltip, Typography, theme, type InputRef } from 'antd';
import {
  DeleteOutlined, DownOutlined, FileAddOutlined, FileOutlined, FolderAddOutlined, FolderOpenOutlined, FolderOutlined,
  MinusSquareOutlined, RightOutlined,
} from '@ant-design/icons';
import { PATH_FIELDS, folderKeys, isFolderNode, joinPath, segmentParts, targetFolder, type RepositoryNode } from './virtualRepository';

const FIELD_HELP: Record<string, string> = {
  namespace: "the release's namespace: one file per environment",
  release: "the release's name: one file per release",
  service: 'the KDPS service deployed',
  'chart.name': 'the name of the chart',
};

/** A file or folder name, its template fields shown as chips. */
export const PathSegment: React.FC<{ text: string }> = ({ text }) => {
  const { token } = theme.useToken();
  return (
    <>
      {segmentParts(text).map((p, i) => (p.field ? (
        <Tooltip key={i} title={`Replaced by ${FIELD_HELP[p.field] || p.field} when deploying`}>
          <span style={{
            color: token.colorPrimary, border: `1px solid ${token.colorPrimaryBorder}`, borderRadius: 4,
            padding: '0 4px', margin: '0 1px', fontSize: 12, background: token.colorPrimaryBg,
          }}>{p.field}</span>
        </Tooltip>
      ) : <span key={i}>{p.text}</span>))}
    </>
  );
};

interface Props {
  nodes: RepositoryNode[];
  selectedKey?: string;
  onSelect: (key: string) => void;
  /** Whether files and folders can be added and removed. */
  editable: boolean;
  /** Adds a file at a path; answers why it cannot, if so. */
  onCreateFile: (path: string) => string | undefined;
  /** Adds an empty folder at a path; answers why it cannot, if so. */
  onCreateFolder: (path: string) => string | undefined;
  /** Whether a node can be removed, and removes it. */
  canDelete: (node: RepositoryNode) => boolean;
  onDelete: (node: RepositoryNode) => void;
  /** What is shown at the end of a row: its status. */
  badges: (node: RepositoryNode) => React.ReactNode;
  /** Shown in the header, next to the buttons. */
  extra?: React.ReactNode;
}

type Creating = { kind: 'file' | 'folder'; folder: string } | null;

/**
 * The files of a profile as an explorer: folders open and close, the selected row stays highlighted, and a new file or
 * folder goes into the selected folder (or the folder of the selected file). A '/' in a new name creates the folders.
 */
const FileExplorer: React.FC<Props> = ({ nodes, selectedKey, onSelect, editable, onCreateFile, onCreateFolder, canDelete, onDelete, badges, extra }) => {
  const { token } = theme.useToken();
  const [collapsed, setCollapsed] = React.useState<Set<string>>(new Set());
  const [creating, setCreating] = React.useState<Creating>(null);
  const [name, setName] = React.useState('');
  const [error, setError] = React.useState<string | null>(null);
  const [hovered, setHovered] = React.useState<string | null>(null);
  const input = React.useRef<InputRef>(null);

  const startCreating = (kind: 'file' | 'folder') => {
    const folder = targetFolder(nodes, selectedKey);
    // The folder the item goes into is opened, as in an editor's explorer.
    const opening: string[] = [];
    const walk = (list: RepositoryNode[]) => list.forEach(n => {
      if (!isFolderNode(n)) return;
      if (folder === n.path || folder.startsWith(`${n.path}/`)) opening.push(n.key);
      walk(n.children!);
    });
    walk(nodes);
    setCollapsed(prev => new Set([...prev].filter(k => !opening.includes(k))));
    setCreating({ kind, folder });
    setName('');
    setError(null);
    setTimeout(() => input.current?.focus(), 0);
  };
  const finishCreating = () => {
    if (!creating) return;
    const path = joinPath(creating.folder, name);
    if (!name.trim()) { setCreating(null); return; }
    const problem = creating.kind === 'file' ? onCreateFile(path) : onCreateFolder(path);
    if (problem) { setError(problem); return; }
    setCreating(null);
  };
  const insertField = (field: string) => {
    setName(n => `${n}{{ ${field} }}`);
    setTimeout(() => input.current?.focus(), 0);
  };

  const indent = (depth: number) => 8 + depth * 14;

  const creatingRow = (depth: number) => creating && (
    <div style={{ paddingLeft: indent(depth), paddingRight: 8, paddingTop: 2, paddingBottom: 4 }}>
      <Input
        ref={input}
        size="small"
        value={name}
        status={error ? 'error' : undefined}
        prefix={creating.kind === 'file' ? <FileOutlined /> : <FolderOutlined />}
        placeholder={creating.kind === 'file' ? 'values.yaml' : 'folder name'}
        onChange={e => { setName(e.target.value); setError(null); }}
        onPressEnter={finishCreating}
        onKeyDown={e => { if (e.key === 'Escape') setCreating(null); }}
      />
      <div style={{ fontSize: 12, color: token.colorTextSecondary, marginTop: 4, lineHeight: '20px' }}>
        {error ? <span style={{ color: token.colorError }}>{error}</span> : (
          <>
            In <code>{creating.folder || 'the repository root'}</code>. A <code>/</code> in the name creates folders.
            Enter to create, Esc to cancel. Insert:{' '}
            {PATH_FIELDS.map(f => (
              <a key={f} onMouseDown={e => e.preventDefault()} onClick={() => insertField(f)} style={{ marginRight: 6 }}>{f}</a>
            ))}
          </>
        )}
      </div>
    </div>
  );

  const rows = (list: RepositoryNode[], depth: number): React.ReactNode[] => list.flatMap(n => {
    const folder = isFolderNode(n);
    const open = folder && !collapsed.has(n.key);
    const selected = n.key === selectedKey;
    const row = (
      <div
        key={n.key}
        role="treeitem"
        aria-selected={selected}
        aria-expanded={folder ? open : undefined}
        onClick={() => {
          onSelect(n.key);
          if (folder) {
            setCollapsed(prev => {
              const next = new Set(prev);
              if (next.has(n.key) || !selected) next.delete(n.key); else next.add(n.key);
              return next;
            });
          }
        }}
        onMouseEnter={() => setHovered(n.key)}
        onMouseLeave={() => setHovered(h => (h === n.key ? null : h))}
        style={{
          display: 'flex', alignItems: 'center', gap: 6, minHeight: 28, cursor: 'pointer',
          paddingLeft: indent(depth), paddingRight: 8,
          background: selected ? token.colorPrimaryBg : hovered === n.key ? token.colorFillTertiary : undefined,
          borderLeft: `2px solid ${selected ? token.colorPrimary : 'transparent'}`,
          color: n.entry?.kind === 'repository' ? token.colorTextTertiary : undefined,
        }}
      >
        <span style={{ width: 12, fontSize: 10, color: token.colorTextTertiary }}>
          {folder ? (open ? <DownOutlined /> : <RightOutlined />) : null}
        </span>
        {folder ? (open ? <FolderOpenOutlined /> : <FolderOutlined />) : <FileOutlined />}
        <span style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}><PathSegment text={n.name} /></span>
        <span style={{ flex: 1 }} />
        {badges(n)}
        {editable && canDelete(n) && hovered === n.key && (
          <Popconfirm
            title={folder ? `Remove the folder ${n.path} and its files from the profile?` : `Remove ${n.path} from the profile?`}
            onConfirm={e => { e?.stopPropagation(); onDelete(n); }}
            onCancel={e => e?.stopPropagation()}
          >
            <DeleteOutlined onClick={e => e.stopPropagation()} style={{ color: token.colorTextSecondary }} />
          </Popconfirm>
        )}
      </div>
    );
    const inside = folder && open ? [
      ...(creating && creating.folder === n.path ? [<React.Fragment key={`${n.key}:new`}>{creatingRow(depth + 1)}</React.Fragment>] : []),
      ...rows(n.children!, depth + 1),
    ] : [];
    return [row, ...inside];
  });

  const iconButton = (title: string, icon: React.ReactNode, onClick: () => void, disabled = false) => (
    <Tooltip title={title}><Button type="text" size="small" icon={icon} onClick={onClick} disabled={disabled} /></Tooltip>
  );

  return (
    <div style={{ border: `1px solid ${token.colorBorderSecondary}`, borderRadius: token.borderRadius, overflow: 'hidden' }}>
      <div style={{ display: 'flex', alignItems: 'center', padding: '4px 8px', background: token.colorFillQuaternary, borderBottom: `1px solid ${token.colorBorderSecondary}` }}>
        <Typography.Text strong style={{ fontSize: 12, letterSpacing: 0.5 }}>FILES IN THE GIT REPOSITORY</Typography.Text>
        <span style={{ flex: 1 }} />
        <Space size={0}>
          {extra}
          {iconButton('New file in the selected folder', <FileAddOutlined />, () => startCreating('file'), !editable)}
          {iconButton('New folder in the selected folder', <FolderAddOutlined />, () => startCreating('folder'), !editable)}
          {iconButton('Collapse all folders', <MinusSquareOutlined />, () => setCollapsed(new Set(folderKeys(nodes))))}
        </Space>
      </div>
      <div role="tree" style={{ padding: '4px 0', maxHeight: 560, overflow: 'auto' }}>
        {creating && creating.folder === '' && creatingRow(0)}
        {rows(nodes, 0)}
        {!nodes.length && !creating && (
          <div style={{ padding: 12, color: token.colorTextSecondary }}>
            No file yet{editable ? ': add one with the New file button above.' : '.'}
          </div>
        )}
      </div>
    </div>
  );
};

export default FileExplorer;
