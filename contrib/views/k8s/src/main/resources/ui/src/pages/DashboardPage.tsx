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
import { Row, Col, Typography, Card, List, Tag, Spin, Statistic, Timeline, Result, Space, Alert, Button, message } from 'antd';
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip as RechartsTooltip, Legend } from 'recharts';
import { CheckCircleTwoTone, CloseCircleTwoTone, InfoCircleTwoTone, WarningTwoTone, ClockCircleOutlined } from '@ant-design/icons';
import { useClusterStatus } from '../context/ClusterStatusContext';
import { installMonitoring, getMonitoringDiscovery } from '../api/client';
import './Page.css';

const { Title, Text, Paragraph } = Typography;

const DashboardPage: React.FC = () => {
    const { status, stats, components, events, monitoringState, refresh } = useClusterStatus();
    console.log('DEBUG: DashboardPage - Cluster status:', status);
    
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

    console.log('DEBUG: DashboardPage - Cluster stats:', stats);
    console.log('DEBUG: DashboardPage - Cluster components:', components);
    console.log('DEBUG: DashboardPage - Cluster events:', events);

    const safeComponents = Array.isArray(components) ? components : [];
    const safeEvents = Array.isArray(events) ? events : [];
    
    const helmChartData = stats ? [
        { name: 'Deployed', value: stats.helm.deployed },
        { name: 'Pending', value: stats.helm.pending },
        { name: 'Failed', value: stats.helm.failed },
    ] : [];
    const COLORS = ['#52c41a', '#faad14', '#ff4d4f'];

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
            {monitoringState?.state && (
              <Alert
                banner
                style={{ marginTop: 0 }}
                message={
                  <Space>
                    <span>Monitoring bootstrap</span>
                    <Tag color={monitoringState.state === 'COMPLETED' ? 'green' : monitoringState.state === 'FAILED' ? 'red' : 'blue'}>
                      {monitoringState.state}
                    </Tag>
                  </Space>
                }
                description={monitoringState.message}
                type={monitoringState.state === 'FAILED' ? 'error' : 'info'}
                showIcon
                action={
                  <Space>
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
                    <Button size="small" onClick={() => refresh(true)}>Refresh status</Button>
                  </Space>
                }
              />
            )}
            <Row gutter={[24, 24]}>
                <Col xs={24} lg={8}>
                    <Card title="Helm Deployment Health" style={{ height: '100%' }} bodyStyle={{ minHeight: 420 }}>
                        {(!stats) ? (
                          <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
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
                                          <text x={x} y={y} fill="#000" fontSize={12} textAnchor={x > cx ? 'start' : 'end'} dominantBaseline="central">
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
                          <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
                        ) : (
                          <>
                            <div style={{textAlign: 'center', marginBottom: '5px'}}>
                                <Statistic
                                    title="Healthy components"
                                    value={healthyComponents}
                                    suffix={`/ ${safeComponents.length}`}
                                    valueStyle={{ color: healthyComponents === safeComponents.length ? '#3f8600' : '#cf1322' }}
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
                           <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
                         ) : (
                           <Timeline
                              mode="left"
                              items={safeEvents.map(event => ({
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
                         )}
                    </Card>
                </Col>
            </Row>
        </div>
    );
};

export default DashboardPage;
