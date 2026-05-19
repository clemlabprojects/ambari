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
import { Alert, Card, Col, Empty, Input, Row, Spin, Table, Tag, Tooltip, Typography, Space } from 'antd';
import { CheckCircleTwoTone, CloseCircleTwoTone, ThunderboltOutlined, ApiOutlined } from '@ant-design/icons';
import { getClusterCapabilities, listCrds, type ClusterCapabilities, type CrdSummary } from '../api/client';

const { Title, Paragraph, Text } = Typography;

/** Display name + helper-link for each curated operator the view cares about.
 *  Keep the list short on purpose — these are the operators KDPS actually
 *  drives. New entries should map to a real capability check, not a vague
 *  "useful operator" so the page stays honest. */
const CURATED_OPERATORS: {
  key: string;
  label: string;
  vendor: string;
  helpUrl: string;
  /** Resolve installed state from /cluster/capabilities. */
  resolve: (caps: ClusterCapabilities | undefined) => { installed: boolean; details: string };
}[] = [
  {
    key: 'cert-manager',
    label: 'cert-manager',
    vendor: 'cert-manager.io',
    helpUrl: 'https://cert-manager.io/docs/installation/',
    resolve: caps => ({
      installed: !!caps?.certManager?.installed,
      details: caps?.certManager?.installed
        ? 'CRDs detected: ClusterIssuer, Certificate. Wizard can use signedByClusterIssuer.'
        : 'Install cert-manager to enable the signedByClusterIssuer TLS mode.',
    }),
  },
  {
    key: 'external-secrets',
    label: 'External Secrets Operator',
    vendor: 'external-secrets.io',
    helpUrl: 'https://external-secrets.io/latest/introduction/getting-started/',
    resolve: caps => ({
      installed: !!caps?.externalSecrets?.installed,
      details: caps?.externalSecrets?.installed
        ? 'CRDs detected: ExternalSecret + (Cluster)SecretStore. Wizard can use sourcedFromExternalSecret.'
        : 'Install ESO to source TLS material from Vault or another external secret store.',
    }),
  },
  {
    key: 'openshift-route',
    label: 'OpenShift Route',
    vendor: 'route.openshift.io',
    helpUrl: 'https://docs.openshift.com/container-platform/latest/networking/routes/route-configuration.html',
    resolve: caps => ({
      installed: !!caps?.openshift?.routeCrd,
      details: caps?.openshift?.routeCrd
        ? 'Cluster looks like OpenShift; Route-based ingress is available.'
        : 'Not OpenShift; ingress will use standard Ingress objects.',
    }),
  },
];

const OperatorsPage: React.FC = () => {
  const [capabilities, setCapabilities] = React.useState<ClusterCapabilities | undefined>(undefined);
  const [crds, setCrds] = React.useState<CrdSummary[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);
  const [filter, setFilter] = React.useState('');

  React.useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.all([
      getClusterCapabilities().catch(e => { throw e; }),
      listCrds().catch(() => [] as CrdSummary[]),
    ]).then(([caps, c]) => {
      if (cancelled) return;
      setCapabilities(caps);
      setCrds(c || []);
    }).catch(e => {
      if (!cancelled) setError(e?.message || String(e));
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => { cancelled = true; };
  }, []);

  const filteredCrds = React.useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) return crds;
    return crds.filter(c =>
      `${c.name || ''} ${c.group || ''} ${c.kind || ''}`.toLowerCase().includes(needle)
    );
  }, [crds, filter]);

  return (
    <>
      <Title level={3} style={{ marginTop: 0 }}>
        <ThunderboltOutlined style={{ marginRight: 8 }} />Operators
      </Title>
      <Paragraph type="secondary">
        Curated operators the KDPS wizard knows how to drive. Anything beyond
        this list is browseable below under "Custom Resource Definitions" but
        the wizard won't reason about it.
      </Paragraph>

      {error && (
        <Alert type="error" showIcon message="Failed to load operators" description={error} style={{ marginBottom: 16 }} />
      )}

      {loading ? (
        <div style={{ textAlign: 'center', padding: 32 }}><Spin /></div>
      ) : (
        <>
          <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
            {CURATED_OPERATORS.map(op => {
              const { installed, details } = op.resolve(capabilities);
              return (
                <Col xs={24} sm={12} md={8} key={op.key}>
                  <Card
                    size="small"
                    title={
                      <Space>
                        {installed
                          ? <CheckCircleTwoTone twoToneColor="#52c41a" />
                          : <CloseCircleTwoTone twoToneColor="#bfbfbf" />}
                        <span>{op.label}</span>
                      </Space>
                    }
                    extra={
                      <Tag color={installed ? 'green' : 'default'} style={{ margin: 0 }}>
                        {installed ? 'installed' : 'not installed'}
                      </Tag>
                    }
                  >
                    <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
                      <Text type="secondary" style={{ fontSize: 11 }}>{op.vendor}</Text>
                    </Paragraph>
                    <Paragraph style={{ fontSize: 12, marginBottom: 8 }}>{details}</Paragraph>
                    <a href={op.helpUrl} target="_blank" rel="noreferrer">
                      Operator docs ↗
                    </a>
                  </Card>
                </Col>
              );
            })}
          </Row>

          <Title level={4}>
            <ApiOutlined style={{ marginRight: 8 }} />Custom Resource Definitions
            <Tag color="blue" style={{ marginLeft: 8 }}>{crds.length}</Tag>
          </Title>
          <Paragraph type="secondary">
            Every CRD currently registered on this cluster. Use the filter to
            find a specific group (e.g. <Text code>cert-manager.io</Text>) or kind.
          </Paragraph>
          <Input.Search
            allowClear
            placeholder="Filter by name, group or kind"
            onChange={e => setFilter(e.target.value)}
            style={{ maxWidth: 360, marginBottom: 12 }}
          />
          {filteredCrds.length === 0 ? (
            <Empty description="No CRDs match the filter" />
          ) : (
            <Table
              size="small"
              rowKey="name"
              dataSource={filteredCrds}
              pagination={{ pageSize: 25, size: 'small', showSizeChanger: true }}
              columns={[
                { title: 'Name', dataIndex: 'name', sorter: (a, b) => a.name.localeCompare(b.name) },
                { title: 'Group', dataIndex: 'group', sorter: (a, b) => (a.group || '').localeCompare(b.group || '') },
                { title: 'Kind', dataIndex: 'kind' },
                {
                  title: 'Scope',
                  dataIndex: 'scope',
                  render: (s: string) => s ? <Tag color={s === 'Cluster' ? 'purple' : 'blue'} style={{ margin: 0 }}>{s}</Tag> : null,
                },
                {
                  title: 'Served versions',
                  dataIndex: 'versions',
                  render: (vs: string[]) => (vs || []).map(v => <Tag key={v} style={{ margin: 2 }}>{v}</Tag>),
                },
                {
                  title: 'Status',
                  dataIndex: 'established',
                  render: (e: boolean) => e
                    ? <Tooltip title="Established=True"><Tag color="green" style={{ margin: 0 }}>established</Tag></Tooltip>
                    : <Tooltip title="Not established; CRD may still be reconciling"><Tag color="orange" style={{ margin: 0 }}>pending</Tag></Tooltip>,
                },
              ]}
            />
          )}
        </>
      )}
    </>
  );
};

export default OperatorsPage;
