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
import { Alert, Button, Card, Empty, Input, Space, Spin, Table, Tag, Tooltip, Typography } from 'antd';
import { DatabaseOutlined, ReloadOutlined } from '@ant-design/icons';
import { listTruststores, type TruststoreCert, type TruststoreSummary } from '../api/client';
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
            ] as any}
          />
        )}
      </Card>
    </>
  );
};

export default TruststoresPage;
