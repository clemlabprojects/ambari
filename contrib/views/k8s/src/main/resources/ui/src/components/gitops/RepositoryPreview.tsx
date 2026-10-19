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
import { Alert, Button, Empty, Space, Switch, Tag, Tree, Typography } from 'antd';
import { FileOutlined, FolderOutlined, LockOutlined } from '@ant-design/icons';
import Editor, { DiffEditor } from '@monaco-editor/react';
import type { GitOpsPreview, GitOpsPreviewFile } from '../../api/client';
import { changeLabel, editorLanguage, fileExplanation, isChange, previewTree, type PreviewNode } from './previewTree';

const CHANGE_COLORS: Record<string, string> = {
  new: 'green',
  changed: 'gold',
  removed: 'red',
  kept: 'blue',
  unchanged: 'default',
};

interface Props {
  preview: GitOpsPreview;
  /** Edited content by path, for files the operator may edit (created once, not in the repository yet). */
  edits?: Record<string, string>;
  /** Called with the new content of an editable file, or null to go back to the rendered one. Omit for read-only. */
  onEdit?: (path: string, content: string | null) => void;
}

/**
 * The repository as a GitOps deploy would leave it: a tree of every file, marked new, changed, removed, kept or
 * unchanged, and the selected file's content, with a diff for a changed file. Only a file the profile creates once and
 * the repository does not have yet can be edited.
 */
const RepositoryPreview: React.FC<Props> = ({ preview, edits = {}, onEdit }) => {
  const [onlyChanges, setOnlyChanges] = React.useState(false);
  const firstChange = preview.files.find(isChange) || preview.files[0];
  const [selected, setSelected] = React.useState<string | undefined>(firstChange?.path);
  const file = preview.files.find(f => f.path === selected);

  const counts = preview.files.reduce<Record<string, number>>((acc, f) => {
    acc[f.change] = (acc[f.change] || 0) + 1;
    return acc;
  }, {});

  const toTreeData = (nodes: PreviewNode[]): any[] => nodes.map(n => ({
    key: n.key,
    isLeaf: !!n.file,
    icon: n.file ? (n.file.protectedPath ? <LockOutlined /> : <FileOutlined />) : <FolderOutlined />,
    title: n.file ? (
      <span>
        {n.name}{' '}
        {n.file.change !== 'unchanged' && (
          <Tag color={CHANGE_COLORS[n.file.change]} style={{ marginLeft: 4 }}>{changeLabel(n.file)}</Tag>
        )}
        {edits[n.file.path] !== undefined && <Tag color="purple">edited</Tag>}
      </span>
    ) : n.name,
    children: n.children ? toTreeData(n.children) : undefined,
  }));

  return (
    <div>
      <Space wrap style={{ marginBottom: 8 }}>
        <Typography.Text type="secondary">
          {preview.repoUrl ? <>{preview.repoUrl}, branch <b>{preview.baseBranch}</b></> : 'Empty repository'}
          {' · '}chart {preview.chart} {preview.version}
          {preview.chartPath ? <> in <code>{preview.chartPath}</code></> : null}
        </Typography.Text>
        {(['new', 'changed', 'removed', 'kept'] as const).filter(c => counts[c]).map(c => (
          <Tag key={c} color={CHANGE_COLORS[c]}>{counts[c]} {c === 'kept' ? 'kept as is' : c}</Tag>
        ))}
        <span>
          <Switch size="small" checked={onlyChanges} onChange={setOnlyChanges} /> Only files the deploy changes
        </span>
      </Space>
      {preview.notes.map((n, i) => <Alert key={i} type="info" showIcon message={n} style={{ marginBottom: 8 }} />)}
      <div style={{ display: 'flex', gap: 12, minHeight: 420 }}>
        <div style={{ width: 320, flex: '0 0 320px', overflow: 'auto', maxHeight: 520, border: '1px solid #f0f0f0', padding: 4 }}>
          <Tree
            showIcon
            defaultExpandAll
            selectedKeys={selected ? [selected] : []}
            onSelect={(keys, info) => { if ((info.node as any).isLeaf) setSelected(String(keys[0])); }}
            treeData={toTreeData(previewTree(preview.files, onlyChanges))}
          />
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          {file ? <FileView file={file} edited={edits[file.path]} onEdit={onEdit} /> : <Empty description="Select a file" />}
        </div>
      </div>
    </div>
  );
};

export const FileView: React.FC<{ file: GitOpsPreviewFile; edited?: string; onEdit?: Props['onEdit'] }> = ({ file, edited, onEdit }) => {
  const language = editorLanguage(file.path);
  const editable = file.editable && !!onEdit;
  let body: React.ReactNode;
  if (file.binary) {
    body = <Empty description="Binary file: not shown" />;
  } else if (file.content == null && file.change !== 'removed') {
    body = <Empty description="Too large to show" />;
  } else if (file.change === 'changed' && file.current != null) {
    body = (
      <DiffEditor height="460px" language={language} original={file.current} modified={file.content || ''}
        options={{ readOnly: true, renderSideBySide: true, minimap: { enabled: false } }} />
    );
  } else if (file.change === 'removed') {
    body = <Empty description="Removed by the deploy" />;
  } else {
    body = (
      <Editor height="460px" language={language} value={edited ?? file.content ?? ''}
        onChange={editable ? (v) => onEdit!(file.path, v ?? '') : undefined}
        options={{ readOnly: !editable, minimap: { enabled: false }, scrollBeyondLastLine: false }} />
    );
  }
  return (
    <div>
      <Space wrap style={{ marginBottom: 6 }}>
        <Typography.Text code>{file.path}</Typography.Text>
        <Tag color={CHANGE_COLORS[file.change]}>{changeLabel(file)}</Tag>
        {file.mode && <Tag>{file.mode === 'createIfMissing' ? 'created once' : 'every deploy'}</Tag>}
        {editable && edited !== undefined && (
          <Button size="small" onClick={() => onEdit!(file.path, null)}>Back to the generated content</Button>
        )}
      </Space>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 6 }}>{fileExplanation(file)}</Typography.Paragraph>
      {body}
    </div>
  );
};

export default RepositoryPreview;
