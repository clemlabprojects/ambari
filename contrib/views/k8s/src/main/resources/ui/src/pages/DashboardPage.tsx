// ui/src/pages/DashboardPage.tsx
import React from 'react';
import { Row, Col, Typography, Card, List, Tag, Spin, Statistic, Timeline, Result } from 'antd';
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip as RechartsTooltip, Legend } from 'recharts';
import { CheckCircleTwoTone, CloseCircleTwoTone, InfoCircleTwoTone, WarningTwoTone, ClockCircleOutlined } from '@ant-design/icons';
import { useClusterStatus } from '../context/ClusterStatusContext'; // CHEMIN D'IMPORTATION CORRIGÉ
import './Page.css';

const { Title, Text, Paragraph } = Typography;

const DashboardPage: React.FC = () => {
    const { status, stats, components, events } = useClusterStatus();

    // Si la connexion a échoué, on affiche un message à la place du contenu.
    if (status === 'error') {
        return (
            <Result
                status="warning"
                title="Données du tableau de bord non disponibles."
                subTitle="Impossible de récupérer les informations du cluster en raison d'une erreur de connexion."
            />
        );
    }

    // Si les données sont en cours de chargement, on affiche un spinner.
    if (!stats || !components || !events) {
      return <div style={{ textAlign: 'center', padding: '50px' }}><Spin size="large" /></div>;
    }
    
    const helmChartData = [
        { name: 'Déployés', value: stats.helm.deployed },
        { name: 'En attente', value: stats.helm.pending },
        { name: 'Échoués', value: stats.helm.failed },
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
                <Title level={2}>Tableau de Bord</Title>
                <Paragraph type="secondary" style={{maxWidth: '600px', textAlign: 'left'}}>
                    Vue d'ensemble de l'état du cluster, de la santé des composants du control plane et des déploiements applicatifs via Helm.
                </Paragraph>
            </div>
            <Row gutter={[24, 24]}>
                <Col xs={24} lg={8}>
                    <Card title="Santé des Déploiements Helm" style={{ height: '100%' }}>
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
                        <Statistic title="Total des releases" value={stats.helm.total} style={{textAlign: 'center', marginTop: '16px'}}/>
                    </Card>
                </Col>
                <Col xs={24} lg={8}>
                     <Card title="État des Composants du Control Plane" style={{ height: '100%' }}>
                        <div style={{textAlign: 'center', marginBottom: '24px'}}>
                            <Statistic
                                title="Composants sains"
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
                     <Card title="Flux d'Événements Récents" style={{ height: '100%' }}>
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
