// ui/src/pages/HelmReleasesPage.tsx
import React, { useState } from 'react';
import { Typography, Button, Table, Input, Space, Menu, Dropdown, Spin, Result } from 'antd';
import { PlusOutlined, MoreOutlined, SyncOutlined, DeleteOutlined } from '@ant-design/icons';
import { useClusterStatus } from '../context/ClusterStatusContext';
import StatusTag from '../components/common/StatusTag';
import PermissionGuard from '../components/common/PermissionGuard';
import './Page.css';

const { Title } = Typography;
const { Search } = Input;

const HelmReleasesPage: React.FC = () => {
    const { status, helmReleases } = useClusterStatus();
    const [isModalVisible, setIsModalVisible] = useState(false);

    if (status === 'error') {
        return <Result status="warning" title="Données des releases Helm non disponibles." subTitle="Impossible de récupérer les informations du cluster." />;
    }

    if (!helmReleases) {
      return <div style={{ textAlign: 'center', padding: '50px' }}><Spin size="large" /></div>;
    }

    const menu = (record: any) => (
        <Menu>
            <PermissionGuard requires="canWrite">
                <Menu.Item key="1" icon={<SyncOutlined />}>Mettre à jour</Menu.Item>
                <Menu.Item key="2" icon={<DeleteOutlined />} danger>Désinstaller</Menu.Item>
            </PermissionGuard>
        </Menu>
    );

    const columns = [
        { title: 'Nom', dataIndex: 'name', key: 'name', sorter: (a: any, b: any) => a.name.localeCompare(b.name) },
        { title: 'Namespace', dataIndex: 'namespace', key: 'namespace', sorter: (a: any, b: any) => a.namespace.localeCompare(b.namespace) },
        { title: 'Chart', dataIndex: 'chart', key: 'chart' },
        { title: 'Version', dataIndex: 'version', key: 'version' },
        { title: 'État', dataIndex: 'status', key: 'status', render: (status: any) => <StatusTag status={status} /> },
        {
            title: 'Actions',
            key: 'actions',
            render: (_: any, record: any) => (
                 <PermissionGuard requires="canWrite">
                    <Dropdown overlay={menu(record)} trigger={['click']}>
                        <Button type="text" icon={<MoreOutlined />} />
                    </Dropdown>
                 </PermissionGuard>
            ),
        },
    ];

    return (
        <div>
            <div className="page-header">
                <Title level={2}>Charts Helm</Title>
                <Space>
                    <Search placeholder="Rechercher un release..." style={{ width: 250 }} />
                    <PermissionGuard requires="canWrite">
                        <Button type="primary" icon={<PlusOutlined />} onClick={() => setIsModalVisible(true)}>
                            Installer un Chart
                        </Button>
                    </PermissionGuard>
                </Space>
            </div>
            <Table columns={columns} dataSource={helmReleases} rowKey="id" loading={status === 'loading'} />
        </div>
    );
};

export default HelmReleasesPage;
