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
import { useSearchParams } from 'react-router-dom';
import { Alert, Button, Card, Col, Empty, Form, Input, Modal, Popconfirm, Radio, Row, Segmented, Select, Space, Spin, Table, Tabs, Tag, Tooltip, Typography, message } from 'antd';
import { ArrowLeftOutlined, BranchesOutlined, CopyOutlined, DeleteOutlined, EyeOutlined, PlusOutlined, SaveOutlined, WarningOutlined } from '@ant-design/icons';
import Editor, { DiffEditor, type OnMount } from '@monaco-editor/react';
import yaml from 'yaml';
import { deleteGitOpsProfile, getHelmRepos, listGitOpsProfiles, previewGitOpsProfile, saveGitOpsProfile, type GitOpsFileRule, type GitOpsPreview, type GitOpsProfile } from '../api/client';
import type { HelmRepo } from '../types';
import { usePermissions } from '../hooks/usePermissions';
import { serverMessage } from '../utils/serverMessage';
import FieldsHelp from '../components/gitops/FieldsHelp';
import FileExplorer, { PathSegment } from '../components/gitops/FileExplorer';
import { FileView } from '../components/gitops/RepositoryPreview';
import { changeLabel, editorLanguage } from '../components/gitops/previewTree';
import { blankProfile, copyOfProfile, forSaving } from '../components/gitops/profileDraft';
import { chartEntries, chartEntryOf, chartFileProblem, examplePath, normalizePath, repositoryEntries, repositoryTree, type RepositoryEntry, type RepositoryNode } from '../components/gitops/virtualRepository';

const CHANGE_COLORS: Record<string, string> = { new: 'green', changed: 'gold', removed: 'red', kept: 'blue' };

const SAVED_GIT_REPOS_KEY = 'k8s-view-git-repos';
const savedGitRepos = (): Array<{ id: string; name: string; url: string }> => {
  try { return JSON.parse(localStorage.getItem(SAVED_GIT_REPOS_KEY) || '[]'); } catch { return []; }
};

/** The example deploy a profile is previewed with: deployed nowhere. */
interface Sample {
  releaseName: string;
  namespace: string;
  repoId?: string;
  chart: string;
  version: string;
  valuesYaml: string;
  repoUrl: string;
  baseBranch: string;
  authToken: string;
}

const DEFAULT_SAMPLE: Sample = {
  releaseName: 'trino', namespace: 'analytics', chart: 'trino', version: '', valuesYaml: 'server:\n  workers: 2\n',
  repoUrl: '', baseBranch: 'main', authToken: '',
};

/** The GitOps profiles: the list, and one profile opened from it (?profile=<name>). */
export default function GitOpsProfilesPage() {
  const [params, setParams] = useSearchParams();
  const opened = params.get('profile');
  const [profiles, setProfiles] = React.useState<GitOpsProfile[]>([]);
  const [canConfigure, setCanConfigure] = React.useState(false);
  const [loading, setLoading] = React.useState(true);
  const [loadError, setLoadError] = React.useState<string | null>(null);
  /** A profile created or copied and not saved yet. */
  const [unsaved, setUnsaved] = React.useState<GitOpsProfile | null>(null);

  const load = React.useCallback(() => {
    setLoading(true);
    return listGitOpsProfiles()
      .then(r => { setProfiles(r.items || []); setCanConfigure(!!r.canConfigure); setLoadError(null); })
      .catch(e => setLoadError(serverMessage(e, 'Could not load the GitOps profiles.')))
      .finally(() => setLoading(false));
  }, []);
  React.useEffect(() => { void load(); }, [load]);

  const open = (name: string | null) => setParams(name ? { profile: name } : {});
  const askName = (title: string, onName: (name: string) => void) => {
    let name = '';
    Modal.confirm({
      title,
      icon: <BranchesOutlined />,
      content: (
        <div>
          <Typography.Paragraph type="secondary">The name operators choose in the deploy wizard.</Typography.Paragraph>
          <Input autoFocus placeholder="lowercase letters, digits and dashes" onChange={e => { name = e.target.value.trim(); }} />
        </div>
      ),
      okText: 'Create',
      onOk: () => {
        if (!/^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$/.test(name)) {
          message.error('A profile name has lowercase letters, digits and dashes, and starts and ends with a letter or digit.');
          return Promise.reject();
        }
        if (profiles.some(p => p.name === name)) {
          message.error(`A profile named ${name} exists already.`);
          return Promise.reject();
        }
        onName(name);
        return undefined;
      },
    });
  };
  const create = (from?: GitOpsProfile) => askName(from ? `Copy the profile ${from.name}` : 'New GitOps profile', name => {
    setUnsaved(from ? copyOfProfile(from, name) : blankProfile(name));
    open(name);
  });

  const saved = profiles.find(p => p.name === opened);
  const current = unsaved && unsaved.name === opened ? unsaved : saved;

  if (!opened || (!current && !loading)) {
    return (
      <ProfileList profiles={profiles} loading={loading} loadError={loadError} canConfigure={canConfigure}
        missing={opened && !loading ? opened : null} onOpen={open} onNew={() => create()} onCopy={create} />
    );
  }
  if (!current) return <Spin />;
  return (
    <ProfileEditor
      key={`${current.name}:${current.version ?? 'new'}`}
      saved={current === unsaved ? null : current}
      initial={current}
      canConfigure={canConfigure}
      onBack={() => { setUnsaved(null); open(null); }}
      onCopy={create}
      onSaved={async name => { setUnsaved(null); await load(); open(name); }}
      onDeleted={async () => { setUnsaved(null); await load(); open(null); }}
    />
  );
}

const ProfileList: React.FC<{
  profiles: GitOpsProfile[]; loading: boolean; loadError: string | null; canConfigure: boolean; missing: string | null;
  onOpen: (name: string) => void; onNew: () => void; onCopy: (p: GitOpsProfile) => void;
}> = ({ profiles, loading, loadError, canConfigure, missing, onOpen, onNew, onCopy }) => (
  <Card
    title={<Space><BranchesOutlined />GitOps profiles</Space>}
    extra={canConfigure && <Button type="primary" icon={<PlusOutlined />} onClick={onNew}>New profile</Button>}
  >
    <Typography.Paragraph type="secondary" style={{ maxWidth: 900 }}>
      A GitOps profile decides what a GitOps deploy writes into the Git repository. The built-in <b>flux</b> profile
      writes a HelmRelease that Flux applies. A profile of your own copies the chart, as published, into a folder of the
      repository and writes the files your Argo CD or CI reads around it. Operators choose the profile in the deploy
      wizard. Passwords and secrets are never written to Git: they stay in a Kubernetes Secret the files can name.
    </Typography.Paragraph>
    {loadError && <Alert type="error" showIcon message={loadError} style={{ marginBottom: 12 }} />}
    {missing && <Alert type="warning" showIcon message={`No GitOps profile named ${missing}.`} style={{ marginBottom: 12 }} />}
    <Table
      rowKey="name"
      loading={loading}
      dataSource={profiles}
      pagination={false}
      onRow={p => ({ onClick: () => onOpen(p.name), style: { cursor: 'pointer' } })}
      columns={[
        { title: 'Name', dataIndex: 'name', render: (n: string) => <a>{n}</a> },
        { title: 'What it writes', dataIndex: 'description', render: (d: string) => <Typography.Text type="secondary">{d}</Typography.Text> },
        {
          title: 'Kind', key: 'kind',
          render: (_: unknown, p: GitOpsProfile) => (p.kind === 'flux' ? <Tag>built-in, Flux</Tag> : p.builtIn ? <Tag>built-in</Tag> : <Tag color="blue">files</Tag>),
        },
        {
          title: 'Delivery', key: 'delivery',
          render: (_: unknown, p: GitOpsProfile) => (p.kind !== 'files' ? '—' : p.delivery?.mode === 'pullRequest' ? 'Pull request' : 'Direct commit'),
        },
        { title: 'Version', dataIndex: 'version', render: (v: number, p: GitOpsProfile) => (p.builtIn ? '—' : v) },
        {
          title: '', key: 'actions', align: 'right' as const,
          render: (_: unknown, p: GitOpsProfile) => canConfigure && p.kind === 'files' && (
            <Button size="small" icon={<CopyOutlined />} onClick={e => { e.stopPropagation(); onCopy(p); }}>Copy</Button>
          ),
        },
      ]}
    />
  </Card>
);

interface EditorProps {
  /** The profile as saved; null for one not saved yet. */
  saved: GitOpsProfile | null;
  initial: GitOpsProfile;
  canConfigure: boolean;
  onBack: () => void;
  onCopy: (p: GitOpsProfile) => void;
  onSaved: (name: string) => void;
  onDeleted: () => void;
}

const ProfileEditor: React.FC<EditorProps> = ({ saved, initial, canConfigure, onBack, onCopy, onSaved, onDeleted }) => {
  const { permissions } = usePermissions();
  const [draft, setDraft] = React.useState<GitOpsProfile>(() => JSON.parse(JSON.stringify(initial)));
  const [selected, setSelected] = React.useState<string | undefined>(() => (initial.files.length ? 'rule:0' : undefined));
  const [emptyFolders, setEmptyFolders] = React.useState<string[]>([]);
  const [saving, setSaving] = React.useState(false);
  const [sample, setSample] = React.useState<Sample>(DEFAULT_SAMPLE);
  const [sampleOpen, setSampleOpen] = React.useState(false);
  const [preview, setPreview] = React.useState<GitOpsPreview | null>(null);
  const [previewedFor, setPreviewedFor] = React.useState('');
  const [previewError, setPreviewError] = React.useState<string | null>(null);
  const [previewing, setPreviewing] = React.useState(false);

  const editable = canConfigure && !draft.builtIn;
  const files = draft.kind === 'files';
  const changed = !saved || JSON.stringify(forSaving(draft)) !== JSON.stringify(forSaving(saved));
  const update = (patch: Partial<GitOpsProfile>) => setDraft(d => ({ ...d, ...patch }));
  const updateRule = (i: number, patch: Partial<GitOpsFileRule>) =>
    setDraft(d => ({ ...d, files: d.files.map((r, j) => (j === i ? { ...r, ...patch } : r)) }));

  const example = { release: sample.releaseName, namespace: sample.namespace, chart: sample.chart, version: sample.version };
  const entries = files ? repositoryEntries(draft, example, preview, emptyFolders) : [];
  const nodes = repositoryTree(entries);
  const stale = !!preview && previewedFor !== JSON.stringify({ draft, sample });
  const selectedEntry = entries.find(e => e.key === selected);
  const chartPath = normalizePath(draft.chartPath || '');
  // The chart's own entries in the chart folder (Chart.yaml, templates/...): copied as published, never written.
  const ownEntries = chartEntries(chartPath, preview);
  const chartEntry = (path: string) => chartEntryOf(chartPath, path, ownEntries);

  const save = async () => {
    setSaving(true);
    try {
      const result = await saveGitOpsProfile(forSaving(draft));
      message.success(`Profile ${result.name} saved (version ${result.version}).`);
      onSaved(result.name);
    } catch (e) {
      message.error(serverMessage(e), 10);
    } finally {
      setSaving(false);
    }
  };
  const remove = async () => {
    try {
      await deleteGitOpsProfile(draft.name);
      message.success(`Profile ${draft.name} deleted.`);
      onDeleted();
    } catch (e) {
      message.error(serverMessage(e), 10);
    }
  };

  const runPreview = async () => {
    setPreviewing(true);
    setPreviewError(null);
    const key = JSON.stringify({ draft, sample });
    try {
      let values: any = {};
      try { values = yaml.parse(sample.valuesYaml || '') || {}; } catch (e: any) { throw new Error(`Example values: ${e?.message || 'invalid YAML'}`); }
      const result = await previewGitOpsProfile({
        releaseName: sample.releaseName,
        namespace: sample.namespace,
        chart: sample.chart,
        repoId: sample.repoId,
        version: sample.version || undefined,
        deploymentMode: 'FLUX_GITOPS',
        values,
        git: { repoUrl: sample.repoUrl || undefined, baseBranch: sample.baseBranch || undefined, authToken: sample.authToken || undefined, profile: draft.name },
      }, forSaving(draft));
      setPreview(result);
      setPreviewedFor(key);
      setSampleOpen(false);
    } catch (e) {
      setPreviewError(serverMessage(e));
    } finally {
      setPreviewing(false);
    }
  };

  // Explorer actions.
  const createFile = (path: string): string | undefined => {
    const p = normalizePath(path);
    if (!p || p.split('/').some(s => s === '..' || s === '.')) return 'Give a name that stays inside the repository.';
    if (chartEntry(p)) return chartFileProblem(chartPath, chartEntry(p)!);
    if (draft.files.some(r => normalizePath(r.path) === p)) return 'The profile already has a file with this path.';
    update({ files: [...draft.files, { path: p, mode: 'always', content: '' }] });
    setEmptyFolders(f => f.filter(x => !p.startsWith(`${x}/`)));
    setSelected(`rule:${draft.files.length}`);
    return undefined;
  };
  const createFolder = (path: string): string | undefined => {
    const p = normalizePath(path);
    if (!p || p.split('/').some(s => s === '..' || s === '.')) return 'Give a name that stays inside the repository.';
    if (chartEntry(p)) return chartFileProblem(chartPath, chartEntry(p)!);
    if (!entries.some(e => e.path === p || e.path.startsWith(`${p}/`))) setEmptyFolders(f => [...f, p]);
    setSelected(entries.find(e => e.path === p && e.kind !== 'rule')?.key || `dir:${p}`);
    return undefined;
  };
  const rulesUnder = (folder: string) => draft.files
    .map((r, i) => ({ path: normalizePath(r.path), i }))
    .filter(r => r.path.startsWith(`${folder}/`));
  const canDelete = (n: RepositoryNode) => {
    if (n.entry?.kind === 'rule' || n.entry?.kind === 'folder') return true;
    return !!n.children && n.entry?.kind !== 'chartFolder' && !chartEntry(n.path) && rulesUnder(n.path).length > 0;
  };
  const deleteNode = (n: RepositoryNode) => {
    if (n.entry?.kind === 'rule') {
      update({ files: draft.files.filter((_, j) => j !== n.entry!.ruleIndex) });
    } else {
      const gone = new Set(rulesUnder(n.path).map(r => r.i));
      update({ files: draft.files.filter((_, j) => !gone.has(j)) });
      setEmptyFolders(f => f.filter(x => x !== n.path && !x.startsWith(`${n.path}/`)));
    }
    setSelected(undefined);
  };
  const badges = (n: RepositoryNode): React.ReactNode => {
    const e = n.entry;
    if (!e) return null;
    const status = e.file && e.file.change !== 'unchanged'
      ? <Tag color={CHANGE_COLORS[e.file.change]} style={{ marginInlineEnd: 0 }}>{changeLabel(e.file)}</Tag> : null;
    if (e.kind === 'chartFolder') return <Tag style={{ marginInlineEnd: 0 }}>chart</Tag>;
    if (e.kind === 'rule') {
      const rule = draft.files[e.ruleIndex!];
      return (
        <Space size={4}>
          {rule.mode === 'createIfMissing' && <Tooltip title="Created once, then owned by your team"><Tag color="purple" style={{ marginInlineEnd: 0 }}>once</Tag></Tooltip>}
          {status}
          {e.problem && <Tooltip title={e.problem}><WarningOutlined style={{ color: '#d4380d' }} /></Tooltip>}
        </Space>
      );
    }
    if (e.kind === 'repository') return e.file?.protectedPath ? <Tag style={{ marginInlineEnd: 0 }}>protected</Tag> : null;
    return status;
  };

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Space wrap style={{ justifyContent: 'space-between', width: '100%' }}>
        <Space wrap>
          <Button type="link" icon={<ArrowLeftOutlined />} style={{ paddingInline: 0 }} onClick={onBack}>GitOps profiles</Button>
          <Typography.Text type="secondary">/</Typography.Text>
          <Typography.Title level={4} style={{ margin: 0 }}>{draft.name}</Typography.Title>
          {draft.builtIn ? <Tag>built-in, read-only</Tag> : saved ? <Tag color="blue">version {saved.version}</Tag> : <Tag color="orange">not saved yet</Tag>}
          {editable && saved && changed && <Tag color="orange">unsaved changes</Tag>}
        </Space>
        <Space wrap>
          {files && <FieldsHelp profile={draft} example={example} />}
          {canConfigure && files && <Button icon={<CopyOutlined />} onClick={() => onCopy(draft)}>Copy</Button>}
          {editable && saved && (
            <Popconfirm title={`Delete the profile ${draft.name}?`} description="Refused while a release is deployed with it." onConfirm={remove}>
              <Button danger icon={<DeleteOutlined />}>Delete</Button>
            </Popconfirm>
          )}
          {editable && <Button type="primary" icon={<SaveOutlined />} loading={saving} disabled={!changed} onClick={save}>Save</Button>}
        </Space>
      </Space>

      {!files ? (
        <Alert type="info" showIcon message={draft.description}
          description="The default profile: KDPS writes a HelmRelease and a HelmRepository per release, which Flux applies. Create a profile of your own to write another layout." />
      ) : (
        <>
          {draft.builtIn && <Alert type="info" showIcon message="A built-in profile cannot be changed: copy it to adapt it." />}
          <Card size="small" title="Settings">
            <SettingsForm draft={draft} editable={editable} update={update} />
          </Card>
          <Card size="small" title="Files"
            extra={<Typography.Text type="secondary">What each deploy with this profile writes around the chart.</Typography.Text>}>
            {previewError && <Alert type="error" showIcon closable onClose={() => setPreviewError(null)} message={previewError} style={{ marginBottom: 8 }} />}
            {preview && (
              <Alert type={stale ? 'warning' : 'success'} showIcon style={{ marginBottom: 8 }}
                message={stale
                  ? 'The profile or the example changed since the preview: preview again to see the files as they would be now.'
                  : `Preview of ${sample.releaseName} in ${sample.namespace}, chart ${preview.chart} ${preview.version}, ${preview.repoUrl ? `branch ${preview.baseBranch} of ${preview.repoUrl}` : 'empty repository'}.`}
                description={preview.notes.length ? preview.notes.join(' ') : undefined}
                action={<Space direction="vertical">
                  <Button size="small" onClick={stale ? runPreview : () => setSampleOpen(true)} loading={previewing}>{stale ? 'Preview again' : 'Change the example'}</Button>
                  <Button size="small" type="text" onClick={() => setPreview(null)}>Clear</Button>
                </Space>} />
            )}
            <Row gutter={16} wrap={false}>
              <Col flex="380px">
                <FileExplorer
                  nodes={nodes}
                  selectedKey={selected}
                  onSelect={setSelected}
                  editable={editable}
                  onCreateFile={createFile}
                  onCreateFolder={createFolder}
                  canDelete={canDelete}
                  onDelete={deleteNode}
                  badges={badges}
                  extra={permissions?.canWrite && (
                    <Tooltip title="Preview the files of an example deploy: nothing is written">
                      <Button type="text" size="small" icon={<EyeOutlined />} onClick={() => setSampleOpen(true)}>Preview</Button>
                    </Tooltip>
                  )}
                />
              </Col>
              <Col flex="auto" style={{ minWidth: 0 }}>
                <SelectionPane
                  entry={selectedEntry}
                  selectedKey={selected}
                  draft={draft}
                  editable={editable}
                  previewed={!!preview}
                  example={example}
                  updateRule={updateRule}
                  update={update}
                />
              </Col>
            </Row>
          </Card>
          <PreviewDialog open={sampleOpen} sample={sample} set={patch => setSample(s => ({ ...s, ...patch }))}
            running={previewing} error={previewError} onRun={runPreview} onClose={() => setSampleOpen(false)} />
        </>
      )}
    </Space>
  );
};

const SettingsForm: React.FC<{ draft: GitOpsProfile; editable: boolean; update: (patch: Partial<GitOpsProfile>) => void }> = ({ draft, editable, update }) => {
  const variables = Object.entries(draft.variables || {});
  const setVariables = (list: Array<[string, string]>) => update({ variables: Object.fromEntries(list) });
  return (
    <Form layout="vertical" disabled={!editable} requiredMark={false}>
      <Row gutter={24}>
        <Col xs={24} lg={12}>
          <Form.Item label="Description" extra="Shown to operators when they choose a profile in the deploy wizard.">
            <Input value={draft.description} onChange={e => update({ description: e.target.value })} />
          </Form.Item>
          <Form.Item label="Chart folder"
            extra="Where the chart is copied, complete and as its repository publishes it. Its own files (Chart.yaml, values.yaml, templates/, charts/…) are replaced on every deploy; other folders inside it, such as environments/, can hold the profile's files and your team's. Leave empty to copy no chart (Argo CD then pulls it from the chart repository).">
            <Input value={draft.chartPath} placeholder="chart" onChange={e => update({ chartPath: e.target.value })} />
          </Form.Item>
          <Form.Item label="Protected paths" extra="Files KDPS never writes nor deletes, as glob patterns such as docs/**.">
            <Select mode="tags" value={draft.protect} onChange={v => update({ protect: v })} tokenSeparators={[',', ' ']} open={false} suffixIcon={null} placeholder="docs/**" />
          </Form.Item>
        </Col>
        <Col xs={24} lg={12}>
          <Form.Item label="How a deploy reaches the repository"
            extra="A pull request lets someone review the change before it reaches the branch your Argo CD or CI reads.">
            <Radio.Group value={draft.delivery?.mode} onChange={e => update({ delivery: { ...draft.delivery, mode: e.target.value } })}>
              <Radio value="pullRequest">Pull request</Radio>
              <Radio value="commit">Direct commit to the branch</Radio>
            </Radio.Group>
          </Form.Item>
          {draft.delivery?.mode === 'pullRequest' && (
            <Form.Item label="Branch prefix" extra="The branches of the pull requests start with it, so your CI can tell them apart.">
              <Input value={draft.delivery?.branchPrefix} onChange={e => update({ delivery: { ...draft.delivery, branchPrefix: e.target.value } })} />
            </Form.Item>
          )}
          <Form.Item label="Variables" extra="Values the files can use as {{ vars.<name> }}, such as a team or an environment name.">
            <Space direction="vertical" style={{ width: '100%' }}>
              {variables.map(([k, v], i) => (
                <Space.Compact key={i} style={{ width: '100%' }}>
                  <Input style={{ width: '40%' }} placeholder="name" value={k}
                    onChange={e => setVariables(variables.map((x, j) => (j === i ? [e.target.value, x[1]] : x)))} />
                  <Input placeholder="value" value={v}
                    onChange={e => setVariables(variables.map((x, j) => (j === i ? [x[0], e.target.value] : x)))} />
                  {editable && <Button icon={<DeleteOutlined />} onClick={() => setVariables(variables.filter((_, j) => j !== i))} />}
                </Space.Compact>
              ))}
              {editable && <Button size="small" icon={<PlusOutlined />} onClick={() => setVariables([...variables, ['', '']])}>Add variable</Button>}
            </Space>
          </Form.Item>
        </Col>
      </Row>
    </Form>
  );
};

/** What the explorer's selection shows: a file's template and result, a folder, or a file of the repository. */
const SelectionPane: React.FC<{
  entry?: RepositoryEntry; selectedKey?: string; draft: GitOpsProfile; editable: boolean; previewed: boolean;
  example: { release: string; namespace: string; chart?: string; version?: string };
  updateRule: (i: number, patch: Partial<GitOpsFileRule>) => void; update: (patch: Partial<GitOpsProfile>) => void;
}> = ({ entry, selectedKey, draft, editable, previewed, example, updateRule, update }) => {
  if (entry?.kind === 'rule') {
    return <RuleEditor entry={entry} rule={draft.files[entry.ruleIndex!]} draft={draft} editable={editable} previewed={previewed}
      example={example} onChange={patch => updateRule(entry.ruleIndex!, patch)} />;
  }
  if (entry?.kind === 'chartFolder') {
    return (
      <Space direction="vertical" style={{ width: '100%' }}>
        <Typography.Title level={5} style={{ margin: 0 }}>Chart folder <Typography.Text code>{entry.path}</Typography.Text></Typography.Title>
        <Typography.Paragraph type="secondary">
          Each deploy copies the chart here, complete and as its repository publishes it: the chart's own files
          (Chart.yaml, values.yaml, templates/, charts/ and the other entries of its package) are replaced, and a file
          the new version no longer has goes. Other folders of this folder are left to the profile's files and your
          team's: select this folder and use New folder or New file to add one. Preview the files to list the chart's
          own files.
        </Typography.Paragraph>
        <Input addonBefore="Folder" value={draft.chartPath} disabled={!editable} onChange={e => update({ chartPath: e.target.value })} style={{ maxWidth: 420 }} />
      </Space>
    );
  }
  if (entry?.file) return <FileView file={entry.file} />;
  if (selectedKey?.startsWith('dir:') || entry?.kind === 'folder') {
    const folder = selectedKey!.slice(4);
    return (
      <Space direction="vertical">
        <Typography.Title level={5} style={{ margin: 0 }}>Folder <PathSegment text={folder} /></Typography.Title>
        <Typography.Text type="secondary">
          New files and folders go into this folder.
          {entry?.kind === 'folder' && ' It is kept once it holds a file: Git has no empty folders.'}
        </Typography.Text>
      </Space>
    );
  }
  return (
    <Empty description={editable ? 'Select a file, or add one with the New file button of the explorer.' : 'Select a file.'} />
  );
};

const RuleEditor: React.FC<{
  entry: RepositoryEntry; rule: GitOpsFileRule; draft: GitOpsProfile; editable: boolean; previewed: boolean;
  example: { release: string; namespace: string; chart?: string; version?: string };
  onChange: (patch: Partial<GitOpsFileRule>) => void;
}> = ({ entry, rule, draft, editable, previewed, example, onChange }) => {
  const result = entry.file;
  const editorRef = React.useRef<Parameters<OnMount>[0] | null>(null);
  // A field inserted where the cursor is in the template, or at its end when the template is not on screen.
  const insertInTemplate = (text: string) => {
    const ed = editorRef.current;
    const selection = ed?.getSelection();
    if (ed && selection) {
      ed.executeEdits('fields-help', [{ range: selection, text, forceMoveMarkers: true }]);
      ed.focus();
    } else {
      onChange({ content: `${rule.content || ''}${text}` });
    }
  };
  const resultView = !result ? (
    <Empty description={previewed ? 'Not in the last preview: preview again.' : 'Use Preview in the explorer to see what this file holds for an example deploy.'} />
  ) : result.change === 'changed' && result.current != null ? (
    <DiffEditor height="420px" language={editorLanguage(entry.path)} original={result.current} modified={result.content || ''}
      options={{ readOnly: true, renderSideBySide: true, minimap: { enabled: false } }} />
  ) : (
    <>
      {result.change === 'kept' && <Alert type="info" showIcon style={{ marginBottom: 6 }} message="Already in the repository: KDPS leaves it as it is." />}
      <Editor height="420px" language={editorLanguage(entry.path)} value={result.content ?? ''}
        options={{ readOnly: true, minimap: { enabled: false }, scrollBeyondLastLine: false }} />
    </>
  );
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Space wrap align="center">
        <Typography.Text strong>Path</Typography.Text>
        <Input style={{ width: 420 }} value={rule.path} disabled={!editable} onChange={e => onChange({ path: e.target.value })}
          placeholder="environments/{{ namespace }}/values.yaml" />
        {editable && (
          <FieldsHelp compact profile={draft} example={example} onInsertTemplate={insertInTemplate}
            onInsertPath={text => onChange({ path: `${rule.path || ''}${text}` })} />
        )}
        <Segmented value={rule.mode} disabled={!editable} onChange={v => onChange({ mode: v as GitOpsFileRule['mode'] })}
          options={[{ value: 'always', label: 'Written by every deploy' }, { value: 'createIfMissing', label: 'Created once' }]} />
      </Space>
      <Typography.Text type="secondary">
        {rule.mode === 'createIfMissing'
          ? 'Written by the first deploy only, then owned by your team: KDPS never rewrites it.'
          : 'Written again by every deploy: edit the template here, not the file in Git.'}
        {' '}For the example deploy: <Typography.Text code>{examplePath(rule.path, draft, example) || '—'}</Typography.Text>.
        {' '}The path and the template can use fields such as <Typography.Text code>{'{{ namespace }}'}</Typography.Text>: see
        {' '}Fields and filters.
      </Typography.Text>
      {entry.problem && <Alert type="error" showIcon message={entry.problem} />}
      <Tabs size="small" items={[
        {
          key: 'template', label: 'Template',
          children: (
            <Editor height="420px" language={editorLanguage(rule.path || '')} value={rule.content}
              onMount={ed => { editorRef.current = ed; }}
              onChange={v => onChange({ content: v ?? '' })}
              options={{ readOnly: !editable, minimap: { enabled: false }, scrollBeyondLastLine: false }} />
          ),
        },
        { key: 'result', label: result ? `Result (${changeLabel(result)})` : 'Result', children: resultView },
      ]} />
    </Space>
  );
};

/** The example deploy the files are previewed with: its chart is downloaded and its branch read, nothing written. */
const PreviewDialog: React.FC<{
  open: boolean; sample: Sample; set: (patch: Partial<Sample>) => void; running: boolean; error: string | null;
  onRun: () => void; onClose: () => void;
}> = ({ open, sample, set, running, error, onRun, onClose }) => {
  const [helmRepos, setHelmRepos] = React.useState<HelmRepo[]>([]);
  React.useEffect(() => {
    if (open) getHelmRepos().then(r => setHelmRepos(r || [])).catch(() => setHelmRepos([]));
  }, [open]);
  const ready = !!(sample.repoId && sample.chart && sample.releaseName && sample.namespace);
  return (
    <Modal open={open} onCancel={onClose} width={760} title="Preview with an example deploy"
      okText="Preview" okButtonProps={{ icon: <EyeOutlined />, loading: running, disabled: !ready }} onOk={onRun}>
      <Typography.Paragraph type="secondary">
        KDPS renders the files for this example, downloads its chart and reads the branch, as a deploy would. Nothing is
        written to Git or to the cluster.
      </Typography.Paragraph>
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}
      <Form layout="vertical" requiredMark={false}>
        <Typography.Text strong>Release</Typography.Text>
        <Row gutter={16}>
          <Col span={12}><Form.Item label="Release name"><Input value={sample.releaseName} onChange={e => set({ releaseName: e.target.value })} /></Form.Item></Col>
          <Col span={12}><Form.Item label="Namespace"><Input value={sample.namespace} onChange={e => set({ namespace: e.target.value })} /></Form.Item></Col>
        </Row>
        <Typography.Text strong>Chart</Typography.Text>
        <Row gutter={16}>
          <Col span={10}>
            <Form.Item label="Helm repository">
              <Select value={sample.repoId} placeholder="Select" onChange={v => set({ repoId: v })} options={helmRepos.map(r => ({ value: r.id, label: r.name }))} />
            </Form.Item>
          </Col>
          <Col span={8}><Form.Item label="Chart name" extra="As the repository names it."><Input value={sample.chart} onChange={e => set({ chart: e.target.value })} /></Form.Item></Col>
          <Col span={6}><Form.Item label="Version"><Input value={sample.version} placeholder="1.2.3" onChange={e => set({ version: e.target.value })} /></Form.Item></Col>
        </Row>
        <Typography.Text strong>Git repository</Typography.Text>{' '}
        <Typography.Text type="secondary">optional: without one, the preview is for an empty repository</Typography.Text>
        <Row gutter={16} style={{ marginTop: 8 }}>
          <Col span={12}>
            <Form.Item label="Saved repository">
              <Select allowClear placeholder="Select" onChange={id => set({ repoUrl: savedGitRepos().find(r => r.id === id)?.url || '' })}
                options={savedGitRepos().map(r => ({ value: r.id, label: r.name }))} />
            </Form.Item>
          </Col>
          <Col span={12}><Form.Item label="Base branch"><Input value={sample.baseBranch} onChange={e => set({ baseBranch: e.target.value })} /></Form.Item></Col>
          <Col span={12}><Form.Item label="Repository URL"><Input value={sample.repoUrl} placeholder="https://git.example.org/team/repo.git" onChange={e => set({ repoUrl: e.target.value })} /></Form.Item></Col>
          <Col span={12}><Form.Item label="Access token" extra="Used for this preview only, not saved."><Input.Password value={sample.authToken} onChange={e => set({ authToken: e.target.value })} /></Form.Item></Col>
        </Row>
        <Form.Item label="Example chart values" extra="YAML, as the deploy wizard would compute it.">
          <Editor height="140px" language="yaml" value={sample.valuesYaml} onChange={v => set({ valuesYaml: v ?? '' })}
            options={{ minimap: { enabled: false }, scrollBeyondLastLine: false }} />
        </Form.Item>
      </Form>
    </Modal>
  );
};
