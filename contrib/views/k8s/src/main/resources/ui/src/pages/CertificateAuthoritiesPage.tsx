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

import React, { useEffect, useState } from 'react';
import { Alert, Button, Form, Input, message, Modal, Space, Table, Tag, Typography, Upload, Popconfirm } from 'antd';
import { CloudUploadOutlined, DeleteOutlined, RocketOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { API_BASE_URL, getClusterCapabilities, type ClusterCapabilities } from '../api/client';

const { Title, Text, Paragraph } = Typography;

/**
 * Certificate Authority registry UI. Operators upload Company Issuing CAs once;
 * each CA can drive either the view's internal signing pipeline (signedByCompanyCA mode)
 * OR be promoted to a cert-manager ClusterIssuer that any service can pick via the
 * signedByClusterIssuer mode.
 *
 * One root of trust, two issuance paths.
 */

interface CaEntry {
  caName: string;
  secretName: string;
  namespace: string;
  subject: string;
  issuer: string;
  serial: string;
  notBefore: string;
  notAfter: string;
  uploadedBy: string;
  uploadedAt: string;
  description?: string;
}

const fetchJson = async <T,>(path: string, init?: RequestInit): Promise<T> => {
  const res = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', 'X-Requested-By': 'ambari', ...(init?.headers || {}) },
  });
  if (!res.ok) throw new Error(await res.text().catch(() => res.statusText));
  const ct = res.headers.get('Content-Type') || '';
  return ct.includes('json') ? (res.json() as Promise<T>) : (undefined as unknown as T);
};

const CertificateAuthoritiesPage: React.FC = () => {
  const [items, setItems] = useState<CaEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [capabilities, setCapabilities] = useState<ClusterCapabilities | null>(null);
  const [promotedIssuers, setPromotedIssuers] = useState<Record<string, { name: string; ready: boolean }>>({});

  const refresh = async () => {
    setLoading(true);
    try {
      const data = await fetchJson<{ items: CaEntry[] }>('/pki/cas');
      setItems(data.items || []);
      // Refresh the promotion map by listing ClusterIssuers and matching by source label.
      const caps = await getClusterCapabilities();
      setCapabilities(caps);
      if (caps.certManager.installed) {
        const issuers = await fetchJson<Array<{ value: string; ready: string }>>('/discovery/cluster-issuers?includeNotReady=true');
        const map: Record<string, { name: string; ready: boolean }> = {};
        for (const ca of data.items || []) {
          const expected = 'clemlab-' + ca.caName.toLowerCase().replace(/[^a-z0-9-]/g, '-');
          const issuer = issuers.find(i => i.value === expected);
          if (issuer) map[ca.caName] = { name: issuer.value, ready: issuer.ready === 'true' };
        }
        setPromotedIssuers(map);
      }
    } catch (e: any) {
      message.error('Failed to load CA registry: ' + (e?.message || e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { refresh(); }, []);

  const onPromote = async (entry: CaEntry) => {
    try {
      const res = await fetchJson<{ clusterIssuerName: string; ready: boolean }>(
        `/pki/cas/${encodeURIComponent(entry.caName)}/promote-to-cluster-issuer`,
        { method: 'POST' }
      );
      message.success(`Promoted to ClusterIssuer ${res.clusterIssuerName} (${res.ready ? 'ready' : 'pending'})`);
      refresh();
    } catch (e: any) {
      message.error('Promote failed: ' + (e?.message || e));
    }
  };

  const onDelete = async (entry: CaEntry) => {
    try {
      await fetchJson(`/pki/cas/${encodeURIComponent(entry.caName)}`, { method: 'DELETE' });
      message.success('CA deleted');
      refresh();
    } catch (e: any) {
      message.error('Delete failed: ' + (e?.message || e));
    }
  };

  const onUpload = async (vals: { caName: string; description?: string; caCertPem: string; caKeyPem: string }) => {
    try {
      await fetchJson('/pki/cas', { method: 'POST', body: JSON.stringify(vals) });
      message.success(`CA "${vals.caName}" uploaded`);
      setUploadOpen(false);
      refresh();
    } catch (e: any) {
      message.error('Upload failed: ' + (e?.message || e));
    }
  };

  const cols = [
    {
      title: 'CA name',
      dataIndex: 'caName',
      key: 'caName',
      render: (v: string) => <Text strong>{v}</Text>,
    },
    {
      title: 'Subject',
      dataIndex: 'subject',
      key: 'subject',
      render: (v: string) => <Text style={{ fontFamily: 'monospace', fontSize: 12 }}>{v}</Text>,
    },
    {
      title: 'Expires',
      dataIndex: 'notAfter',
      key: 'notAfter',
      render: (v: string) => {
        const d = new Date(v);
        const days = Math.round((d.getTime() - Date.now()) / 86_400_000);
        const colour = days < 30 ? 'error' : days < 180 ? 'warning' : 'default';
        return (
          <Space size={4}>
            <Tag color={colour}>{days < 0 ? 'EXPIRED' : `${days}d`}</Tag>
            <Text type="secondary">{d.toLocaleDateString()}</Text>
          </Space>
        );
      },
    },
    {
      title: 'cert-manager ClusterIssuer',
      key: 'promoted',
      render: (_: any, r: CaEntry) => {
        const promoted = promotedIssuers[r.caName];
        if (!capabilities?.certManager.installed) {
          return <Text type="secondary">cert-manager not installed</Text>;
        }
        if (promoted) {
          return (
            <Space>
              <Tag color={promoted.ready ? 'success' : 'warning'}>{promoted.name}</Tag>
              {!promoted.ready && <Text type="secondary">(reconciling)</Text>}
            </Space>
          );
        }
        return <Text type="secondary">—</Text>;
      },
    },
    {
      title: 'Uploaded',
      key: 'uploaded',
      render: (_: any, r: CaEntry) => (
        <Space direction="vertical" size={0}>
          <Text>{r.uploadedBy}</Text>
          <Text type="secondary" style={{ fontSize: 11 }}>{new Date(r.uploadedAt).toLocaleString()}</Text>
        </Space>
      ),
    },
    {
      title: 'Actions',
      key: 'actions',
      width: 280,
      render: (_: any, r: CaEntry) => (
        <Space>
          {capabilities?.certManager.installed && !promotedIssuers[r.caName] && (
            <Button size="small" icon={<RocketOutlined />} onClick={() => onPromote(r)}>
              Promote to ClusterIssuer
            </Button>
          )}
          {capabilities?.certManager.installed && promotedIssuers[r.caName] && (
            <Button size="small" icon={<RocketOutlined />} onClick={() => onPromote(r)}>
              Re-promote
            </Button>
          )}
          <Popconfirm
            title={`Delete CA "${r.caName}"?`}
            description="Existing leaves stay valid until they expire. Promoted ClusterIssuer (if any) is NOT deleted automatically."
            okType="danger"
            okText="Delete"
            onConfirm={() => onDelete(r)}
          >
            <Button size="small" danger icon={<DeleteOutlined />}>Delete</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: 16 }}>
      <Space style={{ marginBottom: 16, justifyContent: 'space-between', width: '100%' }}>
        <Title level={3} style={{ margin: 0 }}>
          <SafetyCertificateOutlined /> Certificate Authorities
        </Title>
        <Space>
          <Button onClick={refresh} loading={loading}>Refresh</Button>
          <Button type="primary" icon={<CloudUploadOutlined />} onClick={() => setUploadOpen(true)}>
            Upload Company CA
          </Button>
        </Space>
      </Space>

      <Paragraph type="secondary">
        Upload your company's issuing CA once. Each CA can be used directly by the view's
        TLS signing pipeline (when picking <em>Sign with Company CA</em> in a service install),
        or <strong>promoted to a cert-manager ClusterIssuer</strong> so any service installed
        via the <em>Sign via cert-manager</em> TLS mode can issue leaves chained to it.
        Either path uses the same root of trust.
      </Paragraph>

      {capabilities && !capabilities.certManager.installed && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="cert-manager not installed"
          description="Promotion to ClusterIssuer is disabled until cert-manager is installed in the cluster. Self-signing via the view's internal pipeline still works."
        />
      )}

      <Table
        rowKey="caName"
        loading={loading}
        columns={cols as any}
        dataSource={items}
        size="small"
        pagination={false}
      />

      <UploadModal open={uploadOpen} onCancel={() => setUploadOpen(false)} onSubmit={onUpload} />
    </div>
  );
};

const UploadModal: React.FC<{ open: boolean; onCancel: () => void; onSubmit: (v: any) => void }> = ({ open, onCancel, onSubmit }) => {
  const [form] = Form.useForm();
  const onFinish = (vals: any) => onSubmit(vals);
  return (
    <Modal
      open={open}
      onCancel={onCancel}
      title="Upload Company Issuing CA"
      okText="Upload"
      onOk={() => form.submit()}
      width={720}
    >
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 12 }}
        message="Private key in transit"
        description="The CA private key is uploaded over the (TLS-protected) view connection and stored as a kubernetes.io/tls Secret in the ambari-pki namespace. Encryption-at-rest is the cluster's etcd-encryption configuration; ensure it's enabled (KMS provider recommended)."
      />
      <Form layout="vertical" form={form} onFinish={onFinish}>
        <Form.Item name="caName" label="Name" rules={[{ required: true, pattern: /^[a-z0-9-]+$/, message: 'lowercase letters, digits and dashes only' }]}>
          <Input placeholder="acme-issuing-ca" />
        </Form.Item>
        <Form.Item name="description" label="Description">
          <Input placeholder="ACME Corp issuing CA, signs ingress leaves" />
        </Form.Item>
        <Form.Item name="caCertPem" label="CA certificate (PEM)" rules={[{ required: true }]}>
          <Input.TextArea rows={6} placeholder="-----BEGIN CERTIFICATE-----..." />
        </Form.Item>
        <Form.Item name="caKeyPem" label="CA private key (PEM)" rules={[{ required: true }]}>
          <Input.TextArea rows={6} placeholder="-----BEGIN PRIVATE KEY-----..." />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default CertificateAuthoritiesPage;
