// ui/src/pages/NodesPage.tsx
import React from 'react';
import { Typography, Table, Progress, Tag, Spin, Result } from 'antd';
import { useClusterStatus } from '../context/ClusterStatusContext';
import StatusTag from '../components/common/StatusTag';
import './Page.css';

const { Title } = Typography;

const NodesPage: React.FC = () => {
    const { status, nodes } = useClusterStatus();

    if (status === 'error') {
        return <Result status="warning" title="Données des nœuds non disponibles." subTitle="Impossible de récupérer les informations du cluster." />;
    }

    if (!nodes) {
      return <div style={{ textAlign: 'center', padding: '50px' }}><Spin size="large" /></div>;
    }

    const columns = [
        { title: 'Nom', dataIndex: 'name', key: 'name', sorter: (a: any, b: any) => a.name.localeCompare(b.name) },
        { title: 'État', dataIndex: 'status', key: 'status', render: (status: any) => <StatusTag status={status} /> },
        { title: 'Rôles', dataIndex: 'roles', key: 'roles', render: (roles: string[]) => roles.map(role => <Tag key={role}>{role}</Tag>) },
        { title: 'CPU', dataIndex: 'cpuUsage', key: 'cpuUsage', render: (usage: number) => <Progress percent={usage * 100} /> },
        { title: 'Mémoire', dataIndex: 'memoryUsage', key: 'memoryUsage', render: (usage: number) => <Progress percent={usage * 100} status="success" /> },
    ];

    return (
        <div>
            <div className="page-header">
                <Title level={2}>Noeuds du Cluster</Title>
            </div>
            <Table columns={columns} dataSource={nodes} rowKey="id" loading={status === 'loading'} />
        </div>
    );
};

export default NodesPage;