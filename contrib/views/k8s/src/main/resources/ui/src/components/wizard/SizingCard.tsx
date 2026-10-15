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
import { Button, Card, Modal, Segmented, Space, Typography } from 'antd';
import { DashboardOutlined } from '@ant-design/icons';
import InstallStep from './InstallStep';
import { applySize, currentSize, CUSTOM_SIZE, formOnly, type Sizing } from './sizing';

interface SizingCardProps {
  definition: any;
  sizing: Sizing;
  data: any;
  onChange: (updated: any) => void;
  upgrade?: boolean;
}

/**
 * Sizes of a service (S, M, L, XL) at the wizard's chart step, and a dialog holding the fields they fill, for any
 * other value. The size shown is read from the values, so an upgrade shows the size the release runs with, or Custom.
 */
const SizingCard: React.FC<SizingCardProps> = ({ definition, sizing, data, onChange, upgrade = false }) => {
  const [open, setOpen] = React.useState(false);
  const size = currentSize(sizing, data);
  const preset = sizing.presets.find((p) => p.key === size);
  const sizingForm = React.useMemo(
    () => ({ ...definition, form: formOnly(definition?.form, sizing.fields) }),
    [definition, sizing.fields],
  );

  return (
    <Card size="small" style={{ marginBottom: 16 }}
      title={<span style={{ fontSize: 15 }}><DashboardOutlined /> Sizing</span>}
      extra={<Button size="small" onClick={() => setOpen(true)}>Edit sizing…</Button>}>
      <Space direction="vertical" style={{ width: '100%' }}>
        <Segmented
          value={size}
          onChange={(v) => {
            const chosen = sizing.presets.find((p) => p.key === v);
            if (chosen) onChange(applySize(data, chosen));
            else setOpen(true);
          }}
          options={[
            ...sizing.presets.map((p) => ({ value: p.key, label: p.key, title: p.label })),
            { value: CUSTOM_SIZE, label: 'Custom' },
          ]}
        />
        <Typography.Text type="secondary">
          {preset ? `${preset.label}: ${preset.summary}.` : 'Custom: the values set in Edit sizing.'}
        </Typography.Text>
      </Space>
      <Modal title="Sizing" open={open} onCancel={() => setOpen(false)} onOk={() => setOpen(false)}
        okText="Done" cancelButtonProps={{ style: { display: 'none' } }} width={760} destroyOnClose>
        <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
          Changes apply as you type; a value that matches no size shows the size as Custom.
        </Typography.Paragraph>
        <InstallStep definition={sizingForm} data={data} onChange={onChange} mode="chart" upgrade={upgrade} embedded />
      </Modal>
    </Card>
  );
};

export default SizingCard;
