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
import { Descriptions, Typography, Card, Tag, Alert } from 'antd';

type Props = {
  def: any;
  install: Record<string, any>;
  repoLabel?: string;
  /** Human label of the selected platform context (e.g. "prod-ext (external)"). */
  contextLabel?: string;
};

/**
 * Final wizard step: a pre-flight summary the operator confirms before deploy.
 * Previously this only listed service/chart/release/namespace; it now surfaces
 * the chart version, repository, platform context, and the well-known optional
 * selections (TLS, ingress host, Atlas federation) — but only when they are
 * actually set, so a value is never guessed or shown empty. The complete chart
 * values stay in the values.yaml preview panel beside the wizard.
 */
const ReviewStep: React.FC<Props> = ({ def, install, repoLabel, contextLabel }) => {
  const chart = def?.chart;
  const version = def?.version;
  const release = install?.releaseName;
  const namespace = install?.namespace;

  const tlsEnabled =
    install?.tls?.enabled ?? install?.requireTls ?? install?.ingress?.tls ?? undefined;
  const ingressHost = install?.ingress?.host ?? install?.ingressHost ?? undefined;
  const atlasOn = install?.atlasFederation?.enabled;

  const codeOrMissing = (v?: string) =>
    v ? <Typography.Text code>{v}</Typography.Text> : <Typography.Text type="danger">not set</Typography.Text>;

  return (
    <Card bordered>
      <Typography.Title level={4} style={{ marginTop: 0 }}>Review &amp; deploy</Typography.Title>
      <Typography.Paragraph type="secondary" style={{ marginTop: -4 }}>
        Confirm the details below, then deploy. The complete chart values are shown in the
        values.yaml preview panel.
      </Typography.Paragraph>

      <Descriptions column={1} bordered size="small">
        <Descriptions.Item label="Service">
          <Typography.Text strong>{def?.label || def?.name || '—'}</Typography.Text>
        </Descriptions.Item>
        {def?.description && (
          <Descriptions.Item label="Description">{def.description}</Descriptions.Item>
        )}
        <Descriptions.Item label="Chart">
          {chart || '—'}{version ? <Tag style={{ marginLeft: 8 }}>v{version}</Tag> : null}
        </Descriptions.Item>
        <Descriptions.Item label="Release name">{codeOrMissing(release)}</Descriptions.Item>
        <Descriptions.Item label="Namespace">{codeOrMissing(namespace)}</Descriptions.Item>
        {repoLabel && <Descriptions.Item label="Repository">{repoLabel}</Descriptions.Item>}
        {contextLabel && <Descriptions.Item label="Platform context">{contextLabel}</Descriptions.Item>}
        {tlsEnabled !== undefined && (
          <Descriptions.Item label="TLS">
            {tlsEnabled ? <Tag color="green">Enabled</Tag> : <Tag>Disabled</Tag>}
          </Descriptions.Item>
        )}
        {ingressHost && <Descriptions.Item label="Ingress host">{ingressHost}</Descriptions.Item>}
        {atlasOn !== undefined && (
          <Descriptions.Item label="Atlas federation">
            {atlasOn ? <Tag color="blue">On</Tag> : <Tag>Off</Tag>}
          </Descriptions.Item>
        )}
      </Descriptions>

      {(!release || !namespace) && (
        <Alert
          style={{ marginTop: 12 }}
          type="warning"
          showIcon
          message="Missing required fields"
          description="Release name and namespace are required. Go back to General Info to set them before deploying."
        />
      )}
    </Card>
  );
};

export default ReviewStep;
