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
import { Alert, Button, Card, Checkbox, Empty, Form, Input, Modal, Popconfirm, Space, Spin, Switch, Table, Tag, Tooltip, Typography, Upload, message } from 'antd';
import { DatabaseOutlined, ReloadOutlined, PlusOutlined, DeleteOutlined, UploadOutlined } from '@ant-design/icons';
import { listTruststores, createTruststore, setTruststoreDefault, deleteTruststore, type TruststoreCert, type TruststoreSummary } from '../api/client';
import { useNamespace, ALL_NAMESPACES } from '../context/NamespaceContext';

const { Title, Paragraph, Text } = Typography;

const fmtDays = (n: number) => {
  if (n < 0) return <Tag color="red" style={{ margin: 0 }}>expired</Tag>;
  if (n < 30) return <Tag color="orange" style={{ margin: 0 }}>{n}d</Tag>;
  if (n < 90) return <Tag color="gold" style={{ margin: 0 }}>{n}d</Tag>;
  return <Tag color="default" style={{ margin: 0 }}>{n}d</Tag>;
};

const TruststoresPage: React.FC = () => {
  const { namespace } = useNamespace();
  const [items, setItems] = React.useState<TruststoreSummary[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);
  const [filter, setFilter] = React.useState('');

  const load = React.useCallback(() => {
    setLoading(true); setError(null);
    listTruststores(namespace === ALL_NAMESPACES ? undefined : namespace)
      .then(setItems)
      .catch(e => setError(e?.message || String(e)))
      .finally(() => setLoading(false));
  }, [namespace]);

  React.useEffect(() => { load(); }, [load]);

  const [createOpen, setCreateOpen] = React.useState(false);
  const [saving, setSaving] = React.useState(false);
  const [busyRow, setBusyRow] = React.useState<string | null>(null);
  const [form] = Form.useForm();

  const submitCreate = async () => {
    try {
      const v = await form.validateFields();
      setSaving(true);
      await createTruststore(v.name, v.caCertPem, !!v.makeDefault, v.description);
      message.success(`Truststore "${v.name}" created`);
      setCreateOpen(false);
      form.resetFields();
      load();
    } catch (e: any) {
      if (e?.errorFields) return; // form validation error, already shown inline
      message.error(e?.message || String(e));
    } finally {
      setSaving(false);
    }
  };

  const toggleDefault = async (row: TruststoreSummary, value: boolean) => {
    const key = `${row.namespace}/${row.name}`;
    setBusyRow(key);
    try {
      await setTruststoreDefault(row.namespace, row.name, value);
      message.success(`"${row.name}" ${value ? 'set as default' : 'removed from defaults'}`);
      load();
    } catch (e: any) {
      message.error(e?.message || String(e));
    } finally {
      setBusyRow(null);
    }
  };

  const removeTruststore = async (row: TruststoreSummary) => {
    const key = `${row.namespace}/${row.name}`;
    setBusyRow(key);
    try {
      await deleteTruststore(row.namespace, row.name);
      message.success(`Truststore "${row.name}" deleted`);
      load();
    } catch (e: any) {
      message.error(e?.message || String(e));
    } finally {
      setBusyRow(null);
    }
  };

  // Read a .pem/.crt/.cer file the operator picks and drop its text into the PEM field.
  const beforeUpload = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => {
      const existing = form.getFieldValue('caCertPem');
      const text = String(reader.result || '');
      form.setFieldsValue({ caCertPem: existing ? `${existing.trimEnd()}\n${text}` : text });
    };
    reader.readAsText(file);
    return false; // prevent antd's automatic upload
  };

  const filtered = React.useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) return items;
    return items.filter(s =>
      `${s.name} ${s.namespace} ${s.releaseName}`.toLowerCase().includes(needle)
    );
  }, [items, filter]);

  /** Expanded per-truststore row: render each parsed cert with subject/issuer/expiry.
   *  Empty bundle gets an explicit Empty state so an operator sees "ca.crt key
   *  is missing" rather than a confusing blank panel. */
  const expandedRow = (row: TruststoreSummary) => {
    if (!row.certificates || row.certificates.length === 0) {
      return <Empty description={row.pemReady ? 'ca.crt could not be parsed' : 'no ca.crt key'} />;
    }
    return (
      <Table
        size="small"
        rowKey={(c) => c.serialNumber}
        dataSource={row.certificates}
        pagination={false}
        columns={[
          { title: 'Subject', dataIndex: 'subject', render: (s: string) => <Text code style={{ fontSize: 11 }}>{s}</Text> },
          { title: 'Issuer', dataIndex: 'issuer', render: (s: string) => <Text code style={{ fontSize: 11 }}>{s}</Text> },
          { title: 'Not after', dataIndex: 'notAfter', render: (s: string) => new Date(s).toLocaleString() },
          { title: 'Expires in', dataIndex: 'daysUntilExpiry', render: (n: number) => fmtDays(n) },
          { title: 'CA', dataIndex: 'isCa', render: (b: boolean) => b ? <Tag color="purple" style={{ margin: 0 }}>CA</Tag> : null, width: 60 },
          { title: 'Serial', dataIndex: 'serialNumber', render: (s: string) => <Text type="secondary" style={{ fontSize: 10 }}>{s.slice(0, 16)}…</Text> },
        ] as any}
      />
    );
  };

  return (
    <>
      <Title level={3} style={{ marginTop: 0 }}>
        <DatabaseOutlined style={{ marginRight: 8 }} />Truststores
      </Title>
      <Paragraph type="secondary">
        Lists Secrets matching the <Text code>&lt;release&gt;-truststore</Text> convention created
        by the security-profile install step. Each Secret carries the merged Ambari/company CA
        bundle in three formats (<Text code>ca.crt</Text>, <Text code>truststore.jks</Text>, <Text code>truststore.password</Text>).
        See <Text code>docs/OUTBOUND_TLS_TRUSTSTORE.md</Text> for the schema.
      </Paragraph>

      <Card
        size="small"
        title={
          <Space>
            <Input.Search
              allowClear
              placeholder="Filter by release / namespace / name"
              onChange={e => setFilter(e.target.value)}
              style={{ width: 320 }}
            />
            <Tooltip title="Refresh">
              <Button icon={<ReloadOutlined />} onClick={load} />
            </Tooltip>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              Create truststore
            </Button>
          </Space>
        }
        extra={
          <Space size={6}>
            <Tag color="blue" style={{ margin: 0 }}>{items.length} truststore(s)</Tag>
            {namespace !== ALL_NAMESPACES && <Tag color="default" style={{ margin: 0 }}>ns: {namespace}</Tag>}
          </Space>
        }
        styles={{ body: { padding: 0 } }}
      >
        {error && (
          <Alert type="error" showIcon message={error} style={{ margin: 12 }} />
        )}
        {loading ? (
          <div style={{ textAlign: 'center', padding: 32 }}><Spin /></div>
        ) : (
          <Table
            size="small"
            rowKey={(r) => `${r.namespace}/${r.name}`}
            dataSource={filtered}
            pagination={{ pageSize: 20, size: 'small' }}
            expandable={{ expandedRowRender: expandedRow }}
            columns={[
              { title: 'Namespace', dataIndex: 'namespace', sorter: (a: TruststoreSummary, b: TruststoreSummary) => a.namespace.localeCompare(b.namespace) },
              { title: 'Release', dataIndex: 'releaseName', sorter: (a: TruststoreSummary, b: TruststoreSummary) => a.releaseName.localeCompare(b.releaseName) },
              { title: 'Secret', dataIndex: 'name', render: (s: string) => <Text code style={{ fontSize: 12 }}>{s}</Text> },
              { title: 'CAs', dataIndex: 'caCount', render: (n: number) => <Tag color={n > 0 ? 'green' : 'default'} style={{ margin: 0 }}>{n}</Tag>, width: 80 },
              {
                title: 'Earliest expiry',
                key: 'expiry',
                render: (_v: any, row: TruststoreSummary) => {
                  if (!row.certificates || row.certificates.length === 0) return <Text type="secondary">—</Text>;
                  const earliest = row.certificates.reduce((min: TruststoreCert, c: TruststoreCert) =>
                    (c.daysUntilExpiry < min.daysUntilExpiry ? c : min), row.certificates[0]);
                  return fmtDays(earliest.daysUntilExpiry);
                },
              },
              {
                title: 'Format',
                key: 'format',
                render: (_v: any, row: TruststoreSummary) => (
                  <Space size={4}>
                    <Tooltip title="ca.crt PEM bundle present">
                      <Tag color={row.pemReady ? 'green' : 'default'} style={{ margin: 0 }}>PEM</Tag>
                    </Tooltip>
                    <Tooltip title="truststore.jks + truststore.password present">
                      <Tag color={row.jvmReady ? 'green' : 'default'} style={{ margin: 0 }}>JKS</Tag>
                    </Tooltip>
                  </Space>
                ),
              },
              {
                title: 'Type',
                key: 'type',
                width: 110,
                render: (_v: any, row: TruststoreSummary) => row.managed
                  ? <Tag color="geekblue" style={{ margin: 0 }}>managed</Tag>
                  : <Tooltip title="Auto-created for a release; edit via the install wizard"><Tag style={{ margin: 0 }}>release</Tag></Tooltip>,
              },
              {
                title: 'Default',
                key: 'default',
                width: 90,
                render: (_v: any, row: TruststoreSummary) => (
                  <Tooltip title={row.managed ? 'Trust this in every release' : 'Only managed truststores can be marked default'}>
                    <Switch
                      size="small"
                      checked={!!row.isDefault}
                      disabled={!row.managed || busyRow === `${row.namespace}/${row.name}`}
                      onChange={(v) => toggleDefault(row, v)}
                    />
                  </Tooltip>
                ),
              },
              {
                title: '',
                key: 'actions',
                width: 60,
                render: (_v: any, row: TruststoreSummary) => row.managed ? (
                  <Popconfirm
                    title="Delete this truststore?"
                    description="Releases already deployed keep their merged bundle until redeployed."
                    okText="Delete"
                    okButtonProps={{ danger: true }}
                    onConfirm={() => removeTruststore(row)}
                  >
                    <Button size="small" danger type="text" icon={<DeleteOutlined />}
                            loading={busyRow === `${row.namespace}/${row.name}`} />
                  </Popconfirm>
                ) : null,
              },
            ] as any}
          />
        )}
      </Card>

      <Modal
        title="Create truststore"
        open={createOpen}
        onCancel={() => { setCreateOpen(false); form.resetFields(); }}
        onOk={submitCreate}
        okText="Create"
        confirmLoading={saving}
        destroyOnClose
        width={640}
      >
        <Paragraph type="secondary" style={{ marginTop: 0 }}>
          Paste (or upload) a company's <Text strong>public</Text> certificate in PEM format — a trust
          anchor, no private key. Mark it default to trust it in every release, or select it per
          release in the install wizard (step 3).
        </Paragraph>
        <Form form={form} layout="vertical" initialValues={{ makeDefault: false }}>
          <Form.Item
            name="name"
            label="Name"
            rules={[
              { required: true, message: 'A name is required' },
              { pattern: /[a-zA-Z0-9]/, message: 'Must contain at least one alphanumeric character' },
            ]}
          >
            <Input placeholder="e.g. acme-corp-ad" autoComplete="off" />
          </Form.Item>
          <Form.Item name="description" label="Description (optional)">
            <Input placeholder="e.g. Active Directory LDAPS issuing CA" autoComplete="off" />
          </Form.Item>
          <Form.Item
            name="caCertPem"
            label="Certificate PEM"
            rules={[
              { required: true, message: 'Paste or upload a certificate PEM' },
              {
                validator: (_r, v) =>
                  v && v.includes('BEGIN CERTIFICATE')
                    ? Promise.resolve()
                    : Promise.reject(new Error('Expected a PEM containing "BEGIN CERTIFICATE"')),
              },
            ]}
          >
            <Input.TextArea rows={8} placeholder={'-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----'} style={{ fontFamily: 'monospace', fontSize: 12 }} />
          </Form.Item>
          <Space style={{ marginBottom: 12 }}>
            <Upload accept=".pem,.crt,.cer,.txt" showUploadList={false} beforeUpload={beforeUpload}>
              <Button icon={<UploadOutlined />}>Upload .pem / .crt file</Button>
            </Upload>
          </Space>
          <Form.Item name="makeDefault" valuePropName="checked">
            <Checkbox>Trust in every release by default</Checkbox>
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
};

export default TruststoresPage;
