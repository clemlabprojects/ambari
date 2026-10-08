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

// ui/src/pages/DashboardPage.tsx
import React from 'react';
import { Row, Col, Typography, Card, List, Tag, Skeleton, Empty, Statistic, Timeline, Result, Space, Alert, Button, message } from 'antd';
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip as RechartsTooltip, Legend } from 'recharts';
import { CheckCircleTwoTone, CloseCircleTwoTone, InfoCircleTwoTone, WarningTwoTone, ClockCircleOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useClusterStatus } from '../context/ClusterStatusContext';
import { installMonitoring, getMonitoringDiscovery } from '../api/client';
import './Page.css';

const { Title, Text, Paragraph } = Typography;

const DashboardPage: React.FC = () => {
    const { status, stats, components, events, monitoringState, refresh } = useClusterStatus();
    const navigate = useNavigate();

    // If the connection failed, display a message instead of content.
    if (status === 'error') {
        return (
            <Result
                status="warning"
                title="Dashboard data not available."
                subTitle="Unable to retrieve cluster information due to a connection error."
            />
        );
    }

    const safeComponents = Array.isArray(components) ? components : [];
    const safeEvents = Array.isArray(events) ? events : [];
    
    const helmChartData = stats ? [
        { name: 'Deployed', value: stats.helm.deployed },
        { name: 'Pending', value: stats.helm.pending },
        { name: 'Failed', value: stats.helm.failed },
    ] : [];
    // Clemlab palette: mint (deployed) / amber (pending) / rose (failed).
    const COLORS = ['#00d9a8', '#fbbf24', '#fb7185'];

    const getEventTimelineItem = (event: (typeof events)[0]) => {
        switch (event.type) {
            case 'Alert': 
                return { color: 'red', dot: <CloseCircleTwoTone twoToneColor="#ff4d4f" /> };
            case 'Warning': 
                return { color: 'gold', dot: <WarningTwoTone twoToneColor="#faad14" /> };
            case 'Info':
            default: 
                return { color: 'blue', dot: <InfoCircleTwoTone twoToneColor="#1677ff" /> };
        }
    };

    const healthyComponents = safeComponents.filter(c => c.status === 'Healthy').length;

    // Compact, clickable capacity tiles (real utilization, not a fake sparkline).
    const pct = (m?: { used: number; total: number }) => (m && m.total ? Math.round((m.used / m.total) * 100) : 0);
    // Backend emits used < 0 (a sentinel) when no metrics source is available (e.g. OpenShift with no
    // reachable/authorized Thanos and no metrics-server). Render "N/A" rather than a bogus negative %.
    const metricUnavailable = (m?: { used: number; total: number }) => !m || m.used < 0 || !isFinite(m.used);
    const projectScope = stats?.scope === 'projects';
    const basisLabel = (b?: string) => (b === 'quota' ? 'quota' : b === 'requests' ? 'requested' : '');
    const amount = (n: number) => (n >= 10 ? n.toFixed(0) : n.toFixed(n >= 1 ? 1 : 2));
    // Project scope: absolute figures with what they are measured against, so nobody reads them as cluster capacity.
    const projectHint = (m: { used: number; total: number; basis?: string }, unit: string) =>
        m.total > 0 ? `${amount(m.used)} of ${amount(m.total)} ${unit} · ${basisLabel(m.basis)}` : `${amount(m.used)} ${unit} used`;
    const nodesHidden = projectScope && !!stats && stats.nodes.total === 0;
    const usageValue = (m: { used: number; total: number }, unit: string) => {
        if (metricUnavailable(m)) return 'N/A';
        if (projectScope && !m.total) return `${amount(m.used)} ${unit}`;
        return `${pct(m)}%`;
    };
    const capTile = (label: string, value: React.ReactNode, ratio: number, target: string, hint?: string) => {
        const r = Math.max(0, Math.min(1, isFinite(ratio) ? ratio : 0));
        const warn = r > 0.85;
        return (
            <div
                className="kdps-kpi kdps-kpi-clickable"
                role="button"
                tabIndex={0}
                title={`Go to ${label}`}
                onClick={() => navigate(target)}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); navigate(target); } }}
            >
                <div className="kdps-kpi-label">{label}</div>
                <div className={warn ? 'kdps-kpi-value kdps-kpi-warn' : 'kdps-kpi-value'}>{value}</div>
                <div className={warn ? 'kdps-kpi-bar warn' : 'kdps-kpi-bar'}><span style={{ width: `${Math.round(r * 100)}%` }} /></div>
                {hint && <div className="kdps-kpi-hint" style={{ fontSize: 12, opacity: 0.7, marginTop: 4 }}>{hint}</div>}
            </div>
        );
    };

    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <div className="page-header" style={{ alignItems: 'flex-start' }}>
                <div>
                  <Title level={2} style={{ marginBottom: 4 }}>Dashboard</Title>
                  <Paragraph type="secondary" style={{maxWidth: '900px', textAlign: 'left', marginBottom: 0}}>
                      Overview of cluster status, control plane component health, and application deployments via Helm.
                  </Paragraph>
                </div>
            </div>
            {(monitoringState?.state === 'RUNNING' || monitoringState?.state === 'FAILED') && (
              <Alert
                banner
                style={{ marginTop: 0 }}
                message={
                  <Space>
                    <span>Monitoring bootstrap</span>
                    <Tag color={monitoringState.state === 'FAILED' ? 'red' : 'blue'}>
                      {monitoringState.state === 'RUNNING' ? 'Installing…' : 'Not found'}
                    </Tag>
                  </Space>
                }
                description={monitoringState.message}
                type={monitoringState.state === 'FAILED' ? 'error' : 'info'}
                showIcon
                action={
                  <Space>
                    {monitoringState.state === 'FAILED' && (
                      <Button
                        size="small"
                        onClick={async () => {
                          try {
                            await installMonitoring();
                            message.success('Monitoring bootstrap requested');
                            await getMonitoringDiscovery().catch(() => {});
                            await refresh();
                          } catch (e:any) {
                            message.error(e?.message || 'Bootstrap failed');
                          }
                        }}
                      >
                        Retry bootstrap
                      </Button>
                    )}
                    <Button size="small" onClick={() => refresh(true)}>Refresh status</Button>
                  </Space>
                }
              />
            )}
            {stats && projectScope && (
              <Paragraph type="secondary" style={{ marginBottom: -8 }}>
                CPU, memory and pods cover the {stats.projects ?? ''} project{stats.projects === 1 ? '' : 's'} this
                account can use, measured against their quotas where every project sets one, otherwise against what
                their pods request. Cluster-wide usage needs cluster monitoring rights.
              </Paragraph>
            )}
            {stats && (
              <div className="kdps-kpis">
                {capTile('Nodes Ready', nodesHidden ? 'N/A' : `${stats.nodes.used}/${stats.nodes.total}`, stats.nodes.total ? stats.nodes.used / stats.nodes.total : 0, '/nodes',
                         nodesHidden ? 'not visible to this account' : undefined)}
                {capTile(projectScope ? 'CPU · your projects' : 'CPU', usageValue(stats.cpu, 'cores'), metricUnavailable(stats.cpu) ? 0 : (stats.cpu.total ? stats.cpu.used / stats.cpu.total : 0), '/nodes',
                         projectScope && !metricUnavailable(stats.cpu) ? projectHint(stats.cpu, 'cores') : undefined)}
                {capTile(projectScope ? 'Memory · your projects' : 'Memory', usageValue(stats.memory, 'GiB'), metricUnavailable(stats.memory) ? 0 : (stats.memory.total ? stats.memory.used / stats.memory.total : 0), '/nodes',
                         projectScope && !metricUnavailable(stats.memory) ? projectHint(stats.memory, 'GiB') : undefined)}
                {capTile(projectScope ? 'Pods · your projects' : 'Pods', `${stats.pods.used}/${stats.pods.total}`, stats.pods.total ? stats.pods.used / stats.pods.total : 0, '/workloads',
                         projectScope && stats.pods.basis === 'quota' ? 'running · of quota' : projectScope ? 'running · of all pods' : undefined)}
              </div>
            )}
            <Row gutter={[24, 24]}>
                <Col xs={24} lg={8}>
                    <Card title="Helm Deployment Health" style={{ height: '100%' }} bodyStyle={{ minHeight: 420 }}>
                        {(!stats) ? (
                          status === 'loading'
                            ? <Skeleton active paragraph={{ rows: 5 }} />
                            : <Empty description="No Helm data available" />
                        ) : (
                        <>
                        <div style={{ height: 300, display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center' }}>
                            <ResponsiveContainer width="100%" height="100%">
                                <PieChart>
                                    <Pie
                                      data={helmChartData}
                                      dataKey="value"
                                      nameKey="name"
                                      cx="50%"
                                      cy="50%"
                                      innerRadius={50}
                                      outerRadius={90}
                                      paddingAngle={4}
                                      labelLine={false}
                                      label={({ cx, cy, midAngle, innerRadius, outerRadius, percent, value }) => {
                                        const radius = innerRadius + (outerRadius - innerRadius) * 0.65;
                                        const x = cx + radius * Math.cos(-midAngle * (Math.PI / 180));
                                        const y = cy + radius * Math.sin(-midAngle * (Math.PI / 180));
                                        return (
                                          <text x={x} y={y} fill="#08231d" fontSize={12} fontWeight={600} textAnchor={x > cx ? 'start' : 'end'} dominantBaseline="central">
                                            {`${Math.round(percent * 100)}% (${value})`}
                                          </text>
                                        );
                                      }}
                                    >
                                        {helmChartData.map((entry, index) => (
                                            <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                                        ))}
                                    </Pie>
                                    <RechartsTooltip formatter={(value) => `${value} release(s)`} />
                                    <Legend iconSize={10} layout="vertical" verticalAlign="middle" align="right" />
                                </PieChart>
                            </ResponsiveContainer>
                        </div>
                        <div style={{ display: 'flex', justifyContent: 'space-around', marginTop: 12 }}>
                            {helmChartData.map((item, idx) => (
                              <Space key={item.name} direction="vertical" align="center" size={2}>
                                <Tag color={COLORS[idx]}>{item.name}</Tag>
                                <Text strong>{item.value}</Text>
                              </Space>
                            ))}
                        </div>
                        <div style={{ textAlign: 'center', marginTop: 8 }}>
                          <Tag color="geekblue">
                            Metrics source: {stats?.source || 'unknown'}
                          </Tag>
                        </div>
                        <Statistic title="Total releases" value={stats.helm.total} style={{textAlign: 'center', marginTop: '12px'}}/>
                        </>
                        )}
                    </Card>
                </Col>
                <Col xs={24} lg={8}>
                     <Card title="Control Plane Component Status" style={{ height: '100%' }}>
                        {safeComponents.length === 0 ? (
                          status === 'loading'
                            ? <Skeleton active paragraph={{ rows: 4 }} />
                            : <Empty description="No component data" />
                        ) : (
                          <>
                            <div style={{textAlign: 'center', marginBottom: '5px'}}>
                                <Statistic
                                    title="Healthy components"
                                    value={healthyComponents}
                                    suffix={`/ ${safeComponents.length}`}
                                    valueStyle={{ color: healthyComponents === safeComponents.length ? '#00d9a8' : '#fb7185' }}
                                />
                            </div>
                            <List
                                size="small"
                                dataSource={safeComponents}
                                renderItem={(item) => (
                                    <List.Item>
                                        <List.Item.Meta
                                            avatar={item.status === 'Healthy' ? <CheckCircleTwoTone twoToneColor="#52c41a" /> : <CloseCircleTwoTone twoToneColor="#ff4d4f" />}
                                            title={<Text>{item.name}</Text>}
                                        />
                                        <Tag color={item.status === 'Healthy' ? 'green' : 'red'}>{item.status}</Tag>
                                    </List.Item>
                                )}
                            />
                          </>
                        )}
                    </Card>
                </Col>
                <Col xs={24} lg={8}>
                     <Card title="Recent Event Stream" style={{ height: '100%' }}>
                         {safeEvents.length === 0 ? (
                           status === 'loading'
                             ? <Skeleton active paragraph={{ rows: 4 }} />
                             : <Empty description="No recent events" />
                         ) : (
                           <div style={{ maxHeight: 360, overflowY: 'auto', paddingRight: 8 }}>
                           <Timeline
                              mode="left"
                              items={safeEvents.slice(0, 50).map(event => ({
                                  ...getEventTimelineItem(event),
                                  children: (
                                      <>
                                          <Text strong>{event.message}</Text>
                                          <br />
                                          <Text type="secondary" style={{fontSize: '12px'}}><ClockCircleOutlined /> {event.timestamp}</Text>
                                      </>
                                  )
                              }))}
                           />
                           </div>
                         )}
                    </Card>
                </Col>
            </Row>
        </div>
    );
};

export default DashboardPage;
