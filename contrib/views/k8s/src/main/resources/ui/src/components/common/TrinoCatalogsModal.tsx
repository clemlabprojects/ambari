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
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import type { ColumnsType } from 'antd/es/table';
import { Alert, Button, Checkbox, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import { DatabaseOutlined, DeleteOutlined, ExperimentOutlined, PlusOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import {
  adoptReleaseCatalog, createReleaseCatalog, dropReleaseCatalog, listReleaseCatalogs, testReleaseCatalog,
  type ReleaseCatalog,
} from '../../api/client';
import { CATALOG_TEMPLATES, renderTemplate } from './trinoCatalogTemplates';
import type { HelmRelease } from '../../types';

const { Text, Paragraph } = Typography;

const SOURCE_TAG: Record<string, { color: string; label: string; hint: string }> = {
  managed: { color: 'geekblue', label: 'KDPS-managed', hint: 'Built from the platform context (hive/iceberg toggles). Change it in Upgrade/Config.' },
  reusable: { color: 'purple', label: 'Reusable', hint: 'Snapshot of a catalog from the Trino Catalogs page.' },
  inline: { color: 'blue', label: 'Persisted', hint: 'Kept in the release values — survives restarts.' },
  unmanaged: { color: 'volcano', label: 'Unmanaged', hint: 'Exists in Trino only (created outside KDPS). It disappears at the next restart unless you adopt it.' },
  builtin: { color: 'default', label: 'Built-in', hint: 'Trino system catalog.' },
};

/** Turn fetchJson's "HTTP 4xx – {\"error\":\"…\"}" into the backend's sentence. */
export function errorText(e: unknown): string {
  const raw = String((e as any)?.message || e || '');
  const m = /\{"error":"(.*)"\}\s*$/.exec(raw);
  if (m) { try { return JSON.parse(`"${m[1]}"`); } catch { return m[1]; } }
  return raw.replace(/^HTTP \d+ – /, '');
}

/**
 * Live catalogs of a deployed Trino release. Every action runs on the coordinator AS THE
 * LOGGED-IN OPERATOR (KDPS service user + impersonation), so a Ranger denial is the operator's
 * own permission problem and is shown as such.
 */
export default function TrinoCatalogsModal({ release, onClose }: { release: HelmRelease | null; onClose: () => void }) {
  const [rows, setRows] = useState<ReleaseCatalog[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [templateId, setTemplateId] = useState('postgresql');
  const [form] = Form.useForm();
  const [preview, setPreview] = useState('');
  const [persist, setPersist] = useState(true);
  const [schemas, setSchemas] = useState<{ name: string; list: string[] } | null>(null);

  const ns = release?.namespace || ''; const rel = release?.name || '';
  const template = useMemo(() => CATALOG_TEMPLATES.find(t => t.id === templateId)!, [templateId]);

  const load = useCallback(async () => {
    if (!release) return;
    setLoading(true); setError(null);
    try { setRows(await listReleaseCatalogs(ns, rel)); }
    catch (e) { setError(errorText(e)); }
    finally { setLoading(false); }
  }, [release, ns, rel]);

  useEffect(() => { if (release) { setRows([]); setSchemas(null); setCreating(false); void load(); } }, [release, load]);

  const refreshPreview = () => {
    const v = form.getFieldsValue();
    setPreview(template.id === 'generic' ? (v.__raw || '') : renderTemplate(template, v));
  };

  const doCreate = async () => {
    const v = await form.validateFields();
    const name = String(v.__name || '').trim();
    const properties = template.id === 'generic' ? String(v.__raw || '') : (preview || renderTemplate(template, v));
    setBusy('create');
    try {
      const r = await createReleaseCatalog(ns, rel, { name, properties, persist });
      message.success(`Catalog '${r.name}' created${r.persisted ? ' and saved to the release' : ' (runtime only)'}`);
      setCreating(false); form.resetFields(); setPreview('');
      await load();
    } catch (e) { message.error(errorText(e), 10); }
    finally { setBusy(null); }
  };

  const doDrop = async (c: ReleaseCatalog) => {
    setBusy(c.name);
    try { await dropReleaseCatalog(ns, rel, c.name, c.persisted); message.success(`Catalog '${c.name}' dropped`); await load(); }
    catch (e) { message.error(errorText(e), 10); }
    finally { setBusy(null); }
  };
  const doTest = async (c: ReleaseCatalog) => {
    setBusy(c.name);
    try { const r = await testReleaseCatalog(ns, rel, c.name); setSchemas({ name: c.name, list: r.schemas || [] }); }
    catch (e) { message.error(errorText(e), 10); }
    finally { setBusy(null); }
  };
  const doAdopt = async (c: ReleaseCatalog) => {
    setBusy(c.name);
    try { await adoptReleaseCatalog(ns, rel, c.name); message.success(`Catalog '${c.name}' saved to the release — it now survives restarts`); await load(); }
    catch (e) { message.error(errorText(e), 10); }
    finally { setBusy(null); }
  };

  const columns: ColumnsType<ReleaseCatalog> = [
    { title: 'Catalog', dataIndex: 'name', key: 'name', render: (n: string) => <Text strong><DatabaseOutlined /> {n}</Text> },
    { title: 'Connector', dataIndex: 'connector', key: 'connector', width: 130, render: (v?: string) => v ? <Tag>{v}</Tag> : <Text type="secondary">—</Text> },
    {
      title: 'Source', dataIndex: 'source', key: 'source', width: 150,
      render: (s: string) => { const t = SOURCE_TAG[s] || { color: 'default', label: s, hint: '' }; return <Tooltip title={t.hint}><Tag color={t.color}>{t.label}</Tag></Tooltip>; },
    },
    {
      title: 'State', key: 'state', width: 170,
      render: (_: unknown, c) => (
        <Space size={4}>
          <Tag color={c.live ? 'green' : 'default'}>{c.live ? 'live' : 'not loaded'}</Tag>
          <Tag color={c.persisted ? 'green' : 'orange'}>{c.persisted ? 'persisted' : 'volatile'}</Tag>
        </Space>
      ),
    },
    {
      title: '', key: 'actions', align: 'right', width: 170,
      render: (_: unknown, c) => (
        <Space>
          {c.live && c.source !== 'builtin' ? <Tooltip title="SHOW SCHEMAS as you"><Button size="small" icon={<ExperimentOutlined />} loading={busy === c.name} onClick={() => doTest(c)} /></Tooltip> : null}
          {c.source === 'unmanaged' ? <Tooltip title="Save this runtime-only catalog into the release so it survives restarts"><Button size="small" icon={<SaveOutlined />} loading={busy === c.name} onClick={() => doAdopt(c)}>Adopt</Button></Tooltip> : null}
          {c.source !== 'builtin' && c.source !== 'managed' ? (
            <Popconfirm title={`Drop catalog '${c.name}'?`} description={c.persisted ? 'Dropped in Trino now and removed from the release values.' : 'Dropped in Trino now.'} okText="Drop" okButtonProps={{ danger: true }} onConfirm={() => doDrop(c)}>
              <Tooltip title="DROP CATALOG as you"><Button size="small" danger icon={<DeleteOutlined />} loading={busy === c.name} /></Tooltip>
            </Popconfirm>
          ) : null}
        </Space>
      ),
    },
  ];

  return (
    <Modal
      title={<span><DatabaseOutlined /> Catalogs — {ns}/{rel}</span>}
      open={!!release}
      onCancel={onClose}
      footer={<Space><Button icon={<ReloadOutlined />} onClick={load} loading={loading}>Refresh</Button><Button onClick={onClose}>Close</Button></Space>}
      width={960}
      destroyOnClose
    >
      <Paragraph type="secondary" style={{ marginTop: 0 }}>
        Actions run on the coordinator as <Text strong>you</Text>: Ranger decides what you may create, test or drop.
        A catalog created here is applied immediately and, when "save to release" is on, written to the release values so it comes back after a restart.
      </Paragraph>
      {error ? <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} /> : null}
      <Table<ReleaseCatalog> size="small" rowKey="name" columns={columns} dataSource={rows} loading={loading} pagination={false} />
      {schemas ? (
        <Alert style={{ marginTop: 12 }} type="success" showIcon closable onClose={() => setSchemas(null)}
          message={`'${schemas.name}' answers — ${schemas.list.length} schema(s)`}
          description={schemas.list.length ? schemas.list.join(', ') : 'No schema visible to you in this catalog.'} />
      ) : null}
      <div style={{ marginTop: 16 }}>
        {!creating ? (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setCreating(true); setPreview(''); }}>New catalog</Button>
        ) : (
          <Form form={form} layout="vertical" onValuesChange={refreshPreview} requiredMark="optional">
            <Space align="start" style={{ width: '100%' }} size="large">
              <Form.Item name="__name" label="Catalog name" rules={[{ required: true, message: 'Give the catalog a name' }, { pattern: /^[a-z][a-z0-9_]*$/, message: 'lower-case letters, digits, underscores' }]} style={{ minWidth: 220 }}>
                <Input placeholder="postgres_prod" />
              </Form.Item>
              <Form.Item label="Connector" style={{ minWidth: 340 }}>
                <Select value={templateId} onChange={(v) => { setTemplateId(v); form.resetFields(CATALOG_TEMPLATES.flatMap(t => t.fields.map(f => f.key)).concat(['__raw'])); setPreview(''); }}
                  options={CATALOG_TEMPLATES.map(t => ({ value: t.id, label: t.label }))} />
              </Form.Item>
            </Space>
            <Paragraph type="secondary" style={{ marginTop: -8 }}>{template.description}</Paragraph>
            {template.id === 'generic' ? (
              <Form.Item name="__raw" label="Catalog properties" rules={[{ required: true, message: 'connector.name=… is required' }]}>
                <Input.TextArea rows={8} spellCheck={false} style={{ fontFamily: 'monospace' }} placeholder={'connector.name=kafka\nkafka.nodes=k1:9092'} />
              </Form.Item>
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
                {template.fields.map(f => (
                  <Form.Item key={f.key} name={f.key} label={f.label} initialValue={f.defaultValue} rules={f.required ? [{ required: true, message: `${f.label} is required` }] : []}
                    extra={f.secret ? (f.help || 'Prefer a Secret exposed as an env var and reference it as ${ENV:VAR}.') : f.help}>
                    <Input placeholder={f.placeholder} spellCheck={false} />
                  </Form.Item>
                ))}
              </div>
            )}
            {template.id !== 'generic' ? (
              <Form.Item label={<span>Resulting properties <Text type="secondary">(editable)</Text></span>}>
                <Input.TextArea rows={6} value={preview} onChange={(e) => setPreview(e.target.value)} spellCheck={false} style={{ fontFamily: 'monospace' }} placeholder="Fill the fields above…" />
              </Form.Item>
            ) : null}
            <Space>
              <Button type="primary" icon={<PlusOutlined />} loading={busy === 'create'} onClick={doCreate}>Create catalog</Button>
              <Checkbox checked={persist} onChange={(e) => setPersist(e.target.checked)}>save to release (survives restarts)</Checkbox>
              <Button onClick={() => { setCreating(false); form.resetFields(); }}>Cancel</Button>
            </Space>
          </Form>
        )}
      </div>
    </Modal>
  );
}
