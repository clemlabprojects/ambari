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
import type { FormInstance } from 'antd';
import { Button, Form, Input, Typography } from 'antd';
import { ConnectorLogo } from './ConnectorGallery';
import { CATALOG_TEMPLATES, renderTemplate, type CatalogTemplate } from './trinoCatalogTemplates';

const { Text } = Typography;

/**
 * The connector-specific part of a catalog definition, shared by the live catalog editor and the
 * reusable Trino Catalogs page: chosen-connector header (logo, name, tagline, "Change connector"),
 * the template's fields (or raw properties for the generic connector) and the editable rendered
 * properties preview. The owner holds the antd form and the preview text.
 */
export function previewFor(template: CatalogTemplate, values: Record<string, any>): string {
  return template.id === 'generic' ? (values.__raw || '') : renderTemplate(template, values);
}

export const TEMPLATE_FIELD_NAMES = CATALOG_TEMPLATES.flatMap(t => t.fields.map(f => f.key)).concat(['__raw']);

export default function CatalogDefinitionForm({ template, form, preview, setPreview, onChangeConnector }: {
  template: CatalogTemplate; form: FormInstance; preview: string; setPreview: (s: string) => void; onChangeConnector: () => void;
}) {
  return (
    <>
      <div style={{ display: 'flex', gap: 14, alignItems: 'center', padding: 12, border: '1px solid #f0f0f0', borderRadius: 8, marginBottom: 12 }}>
        <ConnectorLogo id={template.id} size={44} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontWeight: 600 }}>{template.label} <Text type="secondary" style={{ fontWeight: 400 }}>— {template.tagline}</Text></div>
          <Text type="secondary" style={{ fontSize: 12 }}>{template.description}</Text>
        </div>
        <Button size="small" onClick={onChangeConnector}>Change connector</Button>
      </div>
      {template.id === 'generic' ? (
        <Form.Item name="__raw" label="Catalog properties" rules={[{ required: true, message: 'connector.name=… is required' }]}>
          <Input.TextArea rows={8} spellCheck={false} style={{ fontFamily: 'monospace' }} placeholder={'connector.name=kafka\nkafka.nodes=k1:9092'}
            onChange={(e) => setPreview(e.target.value)} />
        </Form.Item>
      ) : (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
            {template.fields.map(f => (
              <Form.Item key={f.key} name={f.key} label={f.label} initialValue={f.defaultValue} rules={f.required ? [{ required: true, message: `${f.label} is required` }] : []}
                extra={f.secret ? (f.help || 'Prefer a Secret exposed as an env var and reference it as ${ENV:VAR}.') : f.help}>
                <Input placeholder={f.placeholder} spellCheck={false} />
              </Form.Item>
            ))}
          </div>
          <Form.Item label={<span>Resulting properties <Text type="secondary">(editable)</Text></span>}>
            <Input.TextArea rows={6} value={preview} onChange={(e) => setPreview(e.target.value)} spellCheck={false} style={{ fontFamily: 'monospace' }} placeholder="Fill the fields above…" />
          </Form.Item>
        </>
      )}
      {/* keeps the preview in sync while typing in the template fields */}
      <Form.Item noStyle shouldUpdate>{() => { const v = form.getFieldsValue(); const p = previewFor(template, v); if (template.id !== 'generic' && p !== preview && Object.keys(v).some(k => v[k])) { setTimeout(() => setPreview(p), 0); } return null; }}</Form.Item>
    </>
  );
}
