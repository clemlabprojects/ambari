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
  let isVisible = true;
  if (condition) {
    const watchedValue = Form.useWatch(condition.field.split('.'), form);
    isVisible = watchedValue === condition.value;
  }
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
          <Input disabled={disabledProp} />
        </Form.Item>
      );
  }
};

export default DynamicFormField;
