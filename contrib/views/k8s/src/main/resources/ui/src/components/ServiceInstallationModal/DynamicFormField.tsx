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
import { Card, Checkbox, Form, Input, InputNumber, Select } from 'antd';
import type { FormField } from '../../types/ServiceTypes';
import ServiceSelect from './ServiceSelect';

const { Option } = Select;

const DynamicFormField: React.FC<{ field: FormField; upgradeMode?: boolean }> = ({ field, upgradeMode }) => {
  const form = Form.useFormInstance();
  const rules = [{ required: (field as any).required, message: `Field '${field.label}' is required.` }];
  const isLocked = upgradeMode && (field.name === 'releaseName' || field.name === 'namespace');
  const disabledProp = (field as any).disabled || isLocked;
  const nameParts = field.name.replace(/\\\./g, '__DOT__').split('.').map((p: string) => p.replace(/__DOT__/g, '.'));

  const condition = (field as any).condition;
  // Always call useWatch in the same order; pass undefined when no condition
  const watchedValue = Form.useWatch(condition ? condition.field.split('.') : undefined, form);
  const isVisible = condition ? watchedValue === condition.value : true;
  if (!isVisible) return null;

  switch (field.type) {
    case 'group':
      return (
        <Card title={field.label} size="small" style={{ marginBottom: 16 }}>
          {(field as any).fields.map((subField: FormField) => (
            <DynamicFormField key={subField.name} field={subField} />
          ))}
        </Card>
      );
    case 'service-select':
    case 'hadoop-discovery': {
      const f = field as any;
      if (f.targetHost || f.targetPort) {
        return (
          <ServiceSelect
            field={f}
            onValueSelect={(svcOrVal) => {
              const host = typeof svcOrVal === 'object' ? svcOrVal?.value : svcOrVal;
              const port = typeof svcOrVal === 'object' ? svcOrVal?.port : undefined;
              if (f.targetHost && host != null) form.setFieldValue(f.targetHost.split('.'), host);
              if (f.targetPort && port != null) form.setFieldValue(f.targetPort.split('.'), Number(port));
            }}
          />
        );
      }
      return <ServiceSelect field={f} />;
    }
    case 'monitoring-discovery':
      return (
        <ServiceSelect
          field={field as any}
          onValueSelect={(val) => {
            try {
              const parsed = typeof val === 'string' ? JSON.parse(val) : val;
              if (parsed?.namespace || parsed?.release) {
                form.setFieldsValue({
                  monitoring: {
                    namespace: parsed.namespace,
                    release: parsed.release,
                  },
                });
              }
            } catch (e) {
              // ignore parse errors
            }
          }}
        />
      );
    case 'k8s-discovery': {
      const f = field as any;
      if (f.targetHost || f.targetPort) {
        return (
          <ServiceSelect
            field={f}
            onValueSelect={(svcOrVal) => {
              const host = typeof svcOrVal === 'object' ? svcOrVal?.value : svcOrVal;
              const port = typeof svcOrVal === 'object' ? svcOrVal?.port : undefined;
              if (f.targetHost && host != null) form.setFieldValue(f.targetHost.split('.'), host);
              if (f.targetPort && port != null) form.setFieldValue(f.targetPort.split('.'), Number(port));
            }}
          />
        );
      }
      return <ServiceSelect field={f} />;
    }
    case 'select':
      return (
        <Form.Item name={nameParts} label={field.label} rules={rules} help={field.help}>
          <Select disabled={disabledProp}>
            {(field as any).options.map((opt: any) => (
              <Option key={opt.value} value={opt.value}>
                {opt.label}
              </Option>
            ))}
          </Select>
        </Form.Item>
      );
    case 'number':
      return (
        <Form.Item name={nameParts} label={field.label} rules={rules} help={field.help}>
          <InputNumber style={{ width: '100%' }} disabled={disabledProp} />
        </Form.Item>
      );
    case 'boolean':
      return (
        <Form.Item name={nameParts} valuePropName="checked" help={field.help} style={{ marginBottom: 8 }}>
          <Checkbox disabled={disabledProp}>{field.label}</Checkbox>
        </Form.Item>
      );
    case 'string':
    default:
      return (
        <Form.Item name={nameParts} label={field.label} rules={rules} help={field.help}>
          <Input
            disabled={disabledProp}
            onChange={(e) => {
              // For Hive override, also populate the base metastore field so bindings pick it up.
              if (field.name === 'ui_hive_metastore_uri_override') {
                form.setFieldsValue({ ui_hive_metastore_uri: e.target.value });
              }
            }}
          />
        </Form.Item>
      );
  }
};

export default DynamicFormField;
