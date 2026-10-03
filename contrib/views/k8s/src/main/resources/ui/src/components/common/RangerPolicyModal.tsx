/*
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
import { useState } from 'react';
import { Alert, Form, Input, Modal, Radio, Select, Space, Typography, message } from 'antd';
import { SafetyCertificateOutlined } from '@ant-design/icons';
import { createReleaseRangerPolicy, type RangerPolicyRequest, type RangerPolicyResult } from '../../api/client';
import type { HelmRelease } from '../../types';

const { Text } = Typography;

/** Ranger's data-mask types for Trino (the tag service variant is prefixed server-side). */
export const MASK_TYPES: { value: string; label: string; hint: string }[] = [
  { value: 'MASK', label: 'Redact', hint: 'letters → x, digits → n' },
  { value: 'MASK_SHOW_LAST_4', label: 'Show last 4', hint: 'everything but the last four characters' },
  { value: 'MASK_SHOW_FIRST_4', label: 'Show first 4', hint: 'everything but the first four characters' },
  { value: 'MASK_HASH', label: 'Hash', hint: 'replace by a hash of the value' },
  { value: 'MASK_NULL', label: 'Nullify', hint: 'return NULL' },
  { value: 'MASK_DATE_SHOW_YEAR', label: 'Date: year only', hint: 'dates become 1 January of the year' },
  { value: 'CUSTOM', label: 'Custom expression', hint: 'a Trino expression over {col}' },
];

export const ACCESS_TYPES = ['select', 'insert', 'delete', 'create', 'drop', 'alter', 'use', 'show', 'execute', 'impersonate'];

/** Translate the form into the request body the backend expects (exported for tests). */
export function toRequest(v: any): RangerPolicyRequest {
  const policyType = Number(v.policyType ?? 0) as 0 | 1 | 2;
  const body: RangerPolicyRequest = {
    target: v.target === 'tag' ? 'tag' : 'resource',
    policyType,
    users: (v.users || '').trim() || undefined,
    groups: (v.groups || '').trim() || undefined,
    policyName: (v.policyName || '').trim() || undefined,
    description: (v.description || '').trim() || undefined,
  };
  if (body.target === 'tag') {
    body.tag = (v.tag || '').trim();
    if ((v.tagServiceName || '').trim()) body.tagServiceName = v.tagServiceName.trim();
  } else {
    body.resources = {
      catalog: (v.catalog || '*').trim() || '*',
      schema: (v.schema || '*').trim() || '*',
      table: (v.table || '*').trim() || '*',
      column: (v.column || '*').trim() || '*',
    };
  }
  if (policyType === 0) body.accessTypes = (v.accessTypes || ['select']).join(',');
  if (policyType === 1) {
    body.maskType = v.maskType;
    if (v.maskType === 'CUSTOM') body.maskValueExpr = (v.maskValueExpr || '').trim();
  }
  if (policyType === 2) body.rowFilterExpr = (v.rowFilterExpr || '').trim();
  return body;
}

/**
 * Releases → "Ranger policy…" for a Trino release: create one Ranger policy without leaving KDPS.
 * Resource policies land on the release's own repo; tag policies on its tag service. The backend
 * routes to the Ambari server (managed context) or to the context's Ranger (external context).
 */
export default function RangerPolicyModal({ release, onClose }: { release: HelmRelease | null; onClose: () => void }) {
  const [form] = Form.useForm();
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<RangerPolicyResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const target = Form.useWatch('target', form) ?? 'resource';
  const policyType = Number(Form.useWatch('policyType', form) ?? 0);
  const maskType = Form.useWatch('maskType', form);

  const submit = async () => {
    if (!release) return;
    let values: any;
    try { values = await form.validateFields(); } catch { return; }
    setBusy(true); setError(null); setResult(null);
    try {
      const r = await createReleaseRangerPolicy(release.namespace, release.name, toRequest(values));
      setResult(r);
      message.success(`Ranger policy '${r.policyName}' applied on '${r.rangerServiceName}'`);
    } catch (e: any) {
      setError(e?.message || String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={!!release}
      title={<Space><SafetyCertificateOutlined /> Ranger policy — {release?.name}</Space>}
      onCancel={onClose}
      onOk={submit}
      okText="Create policy"
      okButtonProps={{ loading: busy }}
      width={720}
      destroyOnClose
    >
      <Form form={form} layout="vertical" initialValues={{ target: 'resource', policyType: 0, accessTypes: ['select'], catalog: '*', schema: '*', table: '*', column: '*', maskType: 'MASK_HASH' }}>
        <Form.Item name="target" label="Applies to">
          <Radio.Group>
            <Radio.Button value="resource">A Trino resource (catalog / schema / table / column)</Radio.Button>
            <Radio.Button value="tag">Everything carrying a tag</Radio.Button>
          </Radio.Group>
        </Form.Item>
        {target === 'tag' ? (
          <>
            <Form.Item name="tag" label="Tag" rules={[{ required: true, message: 'Tag name as Ranger stores it' }]} extra="The tag as Ranger stores it (OpenMetadata 'PII.Sensitive' arrives as 'Sensitive'; Atlas classifications keep their name).">
              <Input placeholder="Sensitive" />
            </Form.Item>
            <Form.Item name="tagServiceName" label="Tag service (optional)" extra="Blank = the tag service this release's repo is linked to (Ambari clusters: <cluster>_tag).">
              <Input placeholder="clemlabtest_tag" />
            </Form.Item>
          </>
        ) : (
          <Space.Compact block>
            <Form.Item name="catalog" label="Catalog" style={{ flex: 1 }}><Input placeholder="*" /></Form.Item>
            <Form.Item name="schema" label="Schema" style={{ flex: 1 }}><Input placeholder="*" /></Form.Item>
            <Form.Item name="table" label="Table" style={{ flex: 1 }}><Input placeholder="*" /></Form.Item>
            <Form.Item name="column" label="Column" style={{ flex: 1 }}><Input placeholder="*" /></Form.Item>
          </Space.Compact>
        )}
        <Form.Item name="policyType" label="Policy">
          <Radio.Group>
            <Radio.Button value={0}>Allow access</Radio.Button>
            <Radio.Button value={1}>Mask a column</Radio.Button>
            <Radio.Button value={2}>Filter rows</Radio.Button>
          </Radio.Group>
        </Form.Item>
        <Space.Compact block>
          <Form.Item name="users" label="User" style={{ flex: 1 }} extra="One Ranger user (optional when groups are given)"><Input placeholder="alice" /></Form.Item>
          <Form.Item name="groups" label="Groups" style={{ flex: 1 }} extra="Comma-separated; 'public' = everyone"><Input placeholder="analysts, public" /></Form.Item>
        </Space.Compact>
        {policyType === 0 && (
          <Form.Item name="accessTypes" label="Access types" rules={[{ required: true, message: 'Pick at least one' }]}>
            <Select mode="multiple" options={ACCESS_TYPES.map(a => ({ value: a, label: a }))} />
          </Form.Item>
        )}
        {policyType === 1 && (
          <>
            <Form.Item name="maskType" label="Mask" rules={[{ required: true }]}>
              <Select options={MASK_TYPES.map(m => ({ value: m.value, label: `${m.label} — ${m.hint}` }))} />
            </Form.Item>
            {maskType === 'CUSTOM' && (
              <Form.Item name="maskValueExpr" label="Expression" rules={[{ required: true, message: 'Trino expression, e.g. regexp_replace({col}, \'.\', \'*\')' }]}>
                <Input placeholder="concat(substr({col}, 1, 2), '***')" />
              </Form.Item>
            )}
            {target !== 'tag' && <Alert type="info" showIcon message="A masking policy targets exactly one column — no wildcard in Column." style={{ marginBottom: 12 }} />}
          </>
        )}
        {policyType === 2 && (
          <Form.Item name="rowFilterExpr" label="Row filter (SQL predicate)" rules={[{ required: true, message: 'e.g. region = \'EU\'' }]}>
            <Input placeholder="nationkey < 5" />
          </Form.Item>
        )}
        <Space.Compact block>
          <Form.Item name="policyName" label="Policy name (optional)" style={{ flex: 1 }} extra="Blank = kdps-<release>-<kind>-<resource>; an existing name is appended to"><Input /></Form.Item>
          <Form.Item name="description" label="Description (optional)" style={{ flex: 1 }}><Input /></Form.Item>
        </Space.Compact>
      </Form>
      {error && <Alert type="error" showIcon message="Ranger refused the policy" description={error} style={{ marginTop: 8 }} />}
      {result && (
        <Alert type="success" showIcon style={{ marginTop: 8 }} message={`Policy '${result.policyName}' on '${result.rangerServiceName}'`}
          description={<Text type="secondary">{result.via === 'ambari-server-action' ? `Applied by the Ambari server (request ${result.requestId}).` : `Applied directly in Ranger (policy id ${result.policyId}).`} Trino picks it up within its policy refresh interval (about 30 s).</Text>} />
      )}
    </Modal>
  );
}
