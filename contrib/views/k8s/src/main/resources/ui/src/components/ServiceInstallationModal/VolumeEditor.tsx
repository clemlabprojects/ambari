import React, { useEffect } from 'react';
import { Card, Form, Input, Select, Alert } from 'antd';
import type { MountSpec } from '../../types/MountSpec';

const { Option } = Select;

const VolumeEditor: React.FC<{ specs: MountSpec[] }> = ({ specs }) => {
  const form = Form.useFormInstance();

  // Initialize mount defaults ONLY if they are missing
  useEffect(() => {
    const currentMounts = form.getFieldValue('mounts') || {};
    const updates: any = {};
    let hasUpdates = false;

    for (const s of specs) {
      // Only set default if this specific mount key is missing
      if (!currentMounts[s.key]) {
        updates[s.key] = {
          type: s.defaults.type,
          mountPath: s.defaultMountPath,
          size: s.defaults.size || '10Gi',
          storageClass: s.defaults.storageClass || '',
          accessModes: s.defaults.accessModes || ['ReadWriteOnce'],
        };
        hasUpdates = true;
      }
    }

    if (hasUpdates) {
      // Merge new defaults into existing mounts
      form.setFieldsValue({ 
        mounts: { ...currentMounts, ...updates } 
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [JSON.stringify(specs.map(s => s.key))]); // Safer dependency check

  return (
    <Card title="Storage / Mounts" size="small" style={{ marginBottom: 16 }}>
      {specs.map(s => (
        <div key={s.key} style={{ borderTop: '1px solid #f0f0f0', paddingTop: 12, marginTop: 12 }}>
          <div style={{ fontWeight: 600, marginBottom: 8 }}>{s.label}</div>

          <Form.Item name={['mounts', s.key, 'type']} label="Type" initialValue={s.defaults.type}>
            <Select style={{ maxWidth: 240 }}>
              {s.supportedTypes.map(t => (
                <Option key={t} value={t}>
                  {t}
                </Option>
              ))}
            </Select>
          </Form.Item>

          <Form.Item
            name={['mounts', s.key, 'mountPath']}
            label="Mount path"
            initialValue={s.defaultMountPath}
            rules={[{ required: true, message: 'Mount path is required' }]}
          >
            <Input style={{ maxWidth: 400 }} />
          </Form.Item>

          {/* PVC-only fields */}
          <Form.Item noStyle shouldUpdate>
            {({ getFieldValue }) =>
              getFieldValue(['mounts', s.key, 'type']) === 'pvc' ? (
                <>
                  <Form.Item name={['mounts', s.key, 'size']} label="Size" initialValue={s.defaults.size}>
                    <Input placeholder="e.g. 20Gi" style={{ maxWidth: 240 }} />
                  </Form.Item>
                  <Form.Item name={['mounts', s.key, 'storageClass']} label="StorageClass">
                    <Input placeholder="(default)" style={{ maxWidth: 240 }} />
                  </Form.Item>
                  <Form.Item name={['mounts', s.key, 'accessModes']} label="Access modes" initialValue={s.defaults.accessModes}>
                    <Select mode="multiple" style={{ maxWidth: 400 }}>
                      <Option value="ReadWriteOnce">ReadWriteOnce</Option>
                      <Option value="ReadOnlyMany">ReadOnlyMany</Option>
                      <Option value="ReadWriteMany">ReadWriteMany</Option>
                    </Select>
                  </Form.Item>
                </>
              ) : null
            }
          </Form.Item>

          {/* S3 placeholder */}
          <Form.Item noStyle shouldUpdate>
            {({ getFieldValue }) =>
              getFieldValue(['mounts', s.key, 'type']) === 's3' ? (
                <Alert type="info" message="S3-backed PV is not implemented yet in this UI. Choose emptyDir or PVC for now." showIcon />
              ) : null
            }
          </Form.Item>
        </div>
      ))}
    </Card>
  );
};

export default VolumeEditor;
