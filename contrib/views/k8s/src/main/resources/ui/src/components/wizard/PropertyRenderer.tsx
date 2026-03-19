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
import { Input, Switch, InputNumber, Typography, Tag, Space, Tooltip } from 'antd';
import { InfoCircleOutlined, LockOutlined, FileTextOutlined } from '@ant-design/icons';
import Editor from '@monaco-editor/react';
import type { StackProperty } from '../../api/client';

const { Text } = Typography;

interface PropertyRendererProps {
  configName: string;
  property: StackProperty;
  currentValue: any; // The override value (undefined if not overridden)
  onChange: (configName: string, propName: string, value: any) => void;
}

const PropertyRenderer: React.FC<PropertyRendererProps> = ({ configName, property, currentValue, onChange }) => {
  const val = currentValue !== undefined ? currentValue : property.value;
  const isModified = currentValue !== undefined && currentValue !== property.value;
  const [error, setError] = React.useState<string | null>(null);
  const [confirm, setConfirm] = React.useState('');
  const isPassword = property.type === "password";
  const mismatch = isPassword && currentValue !== undefined && confirm !== "" && confirm !== val;

  React.useEffect(() => {
    if (!isPassword) {
      setError(null);
      return;
    }
    if (property.required && (!val || val === "")) {
      setError("Password is required");
    } else if (mismatch) {
      setError("Passwords do not match");
    } else {
      setError(null);
    }
  }, [isPassword, val, confirm, mismatch, property.required]);

  const handleChange = (v: any) => onChange(configName, property.name, v);

  const label = (
    <div style={{ marginBottom: 4 }}>
      <Space>
        <Text strong>{property.displayName || property.name}</Text>
        {isModified && <Tag color="orange">Modified</Tag>}
        {property.required && <Tag color="red">Required</Tag>}
        {property.description && (
          <Tooltip title={property.description}>
            <InfoCircleOutlined style={{ color: '#999' }} />
          </Tooltip>
        )}
      </Space>
      <div style={{ fontSize: 12, color: '#888', fontFamily: 'monospace' }}>{property.name}</div>
    </div>
  );

  if (isPassword) {
    return (
      <div style={{ marginBottom: 16 }}>
        {label}
        <Input.Password 
          value={val} 
          onChange={e => handleChange(e.target.value)} 
          prefix={<LockOutlined />}
          placeholder="Enter secret..."
          style={{ marginBottom: 8 }}
        />
        <Input.Password
          value={confirm}
          onChange={e => setConfirm(e.target.value)}
          prefix={<LockOutlined />}
          placeholder="Confirm secret..."
          status={mismatch ? 'error' : undefined}
        />
        {error && <div style={{ color: '#ff4d4f', marginTop: 4 }}>{error}</div>}
      </div>
    );
  }

  if (property.type === 'boolean') {
    return (
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', border: '1px solid #f0f0f0', padding: 8, borderRadius: 4, marginBottom: 16 }}>
        {label}
        <Switch checked={val === true || val === 'true'} onChange={handleChange} />
      </div>
    );
  }

  if (property.type === 'content') {
    return (
      <div style={{ marginBottom: 24 }}>
        {label}
        <div style={{ border: '1px solid #d9d9d9', borderRadius: 4 }}>
          <div style={{ padding: '4px 8px', background: '#fafafa', borderBottom: '1px solid #f0f0f0', fontSize: 12 }}>
            <FileTextOutlined /> {property.language || 'text'} content
          </div>
          <Editor
            height="300px"
            language={property.language || 'plaintext'}
            value={val}
            onChange={(v) => handleChange(v || '')}
            options={{ minimap: { enabled: false }, scrollBeyondLastLine: false, fontSize: 12 }}
          />
        </div>
      </div>
    );
  }

  if (property.type === 'int') {
    return (
      <div style={{ marginBottom: 16 }}>
        {label}
        <InputNumber style={{ width: '100%' }} value={val} onChange={handleChange} addonAfter={property.unit} />
      </div>
    );
  }

  return (
    <div style={{ marginBottom: 16 }}>
      {label}
      <Input value={val} onChange={e => handleChange(e.target.value)} />
    </div>
  );
};

export default PropertyRenderer;
