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
    case 'hadoop-discovery':
      return <ServiceSelect field={field as any} />;
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
    case 'k8s-discovery': // Add this case
      return <ServiceSelect field={field as any} />;
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
        <Form.Item name={nameParts} label={field.label} valuePropName="checked" help={field.help}>
          <Checkbox disabled={disabledProp} />
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
