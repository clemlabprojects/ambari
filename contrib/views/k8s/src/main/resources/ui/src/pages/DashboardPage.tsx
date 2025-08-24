// ui/src/pages/DashboardPage.tsx
import React from 'react';
import { Row, Col, Typography, Card, List, Tag, Spin, Statistic, Timeline, Result } from 'antd';
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip as RechartsTooltip, Legend } from 'recharts';
import { CheckCircleTwoTone, CloseCircleTwoTone, InfoCircleTwoTone, WarningTwoTone, ClockCircleOutlined } from '@ant-design/icons';
import { useClusterStatus } from '../context/ClusterStatusContext';
import './Page.css';

const { Title, Text, Paragraph } = Typography;

const DashboardPage: React.FC = () => {
    const { status, stats, components, events, mainLoaderActive } = useClusterStatus();
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

    // If data is being loaded, display a spinner.
    console.log('DEBUG: DashboardPage - Cluster stats:', stats);
    console.log('DEBUG: DashboardPage - Cluster components:', components);
    console.log('DEBUG: DashboardPage - Cluster events:', events);
    console.log('DEBUG: DashboardPage - Main loader active:', mainLoaderActive);
    if (mainLoaderActive){
        return <div style={{ textAlign: 'center', padding: '50px' }}><Spin size="large" /></div>;
    }
    
    const helmChartData = [
        { name: 'Deployed', value: stats.helm.deployed },
        { name: 'Pending', value: stats.helm.pending },
        { name: 'Failed', value: stats.helm.failed },
    ];
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

    const healthyComponents = components.filter(c => c.status === 'Healthy').length;

    return (
        <div>
            <div className="page-header">
                <Title level={2}>Dashboard</Title>
                <Paragraph type="secondary" style={{maxWidth: '600px', textAlign: 'left'}}>
                    Overview of cluster status, control plane component health, and application deployments via Helm.
                </Paragraph>
            </div>
            <Row gutter={[24, 24]}>
                <Col xs={24} lg={8}>
                    <Card title="Helm Deployment Health" style={{ height: '100%' }}>
                        <div style={{ height: 300, display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center' }}>
                            <ResponsiveContainer width="100%" height="100%">
                                <PieChart>
                                    <Pie data={helmChartData} dataKey="value" nameKey="name" cx="50%" cy="50%" innerRadius={60} outerRadius={80} paddingAngle={5} labelLine={false} label={({ cx, cy, midAngle, innerRadius, outerRadius, percent }) => {
                                        const radius = innerRadius + (outerRadius - innerRadius) * 0.5;
                                        const x = cx + radius * Math.cos(-midAngle * (Math.PI / 180));
                                        const y = cy + radius * Math.sin(-midAngle * (Math.PI / 180));
                                        return (
                                            <text x={x} y={y} fill="white" textAnchor={x > cx ? 'start' : 'end'} dominantBaseline="central">
                                                {`${(percent * 100).toFixed(0)}%`}
                                            </text>
                                        );
                                    }}>
                                        {helmChartData.map((entry, index) => (
                                            <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                                        ))}
                                    </Pie>
                                    <RechartsTooltip formatter={(value) => `${value} release(s)`} />
                                    <Legend iconSize={10} layout="vertical" verticalAlign="middle" align="right" />
                                </PieChart>
                            </ResponsiveContainer>
                        </div>
                        <Statistic title="Total releases" value={stats.helm.total} style={{textAlign: 'center', marginTop: '16px'}}/>
                    </Card>
                </Col>
                <Col xs={24} lg={8}>
                     <Card title="Control Plane Component Status" style={{ height: '100%' }}>
                        <div style={{textAlign: 'center', marginBottom: '24px'}}>
                            <Statistic
                                title="Healthy components"
                                value={healthyComponents}
                                suffix={`/ ${components.length}`}
                                valueStyle={{ color: healthyComponents === components.length ? '#3f8600' : '#cf1322' }}
                            />
                        </div>
                        <List
                            size="small"
                            dataSource={components}
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
                    </Card>
                </Col>
                <Col xs={24} lg={8}>
                     <Card title="Recent Event Stream" style={{ height: '100%' }}>
                         <Timeline
                            mode="left"
                            items={events.map(event => ({
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
                    </Card>
                </Col>
            </Row>
        </div>
    );
};

export default DashboardPage;
