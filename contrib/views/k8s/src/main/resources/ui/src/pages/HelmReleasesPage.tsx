// ui/src/pages/HelmReleasesPage.tsx
import React, { useEffect, useState } from 'react';
import { Typography, Button, Table, Input, Space, Modal, message, Dropdown, Spin, Result, Tag, Tooltip } from 'antd';
import { getAvailableServices } from '../api/client';
import type { AvailableServices } from '../types/ServiceTypes';
import type { HelmRelease } from '../types';
import type { MenuProps } from 'antd';
import { PlusOutlined, MoreOutlined, SyncOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import { useClusterStatus } from '../context/ClusterStatusContext';
import StatusTag from '../components/common/StatusTag';
import PermissionGuard from '../components/common/PermissionGuard';
import ServiceInstallationModal from '../components/ServiceInstallationModal';

import { uninstallHelm } from '../api/client';

import './Page.css';

const { Title } = Typography;
const { Search } = Input;

const HelmReleasesPage: React.FC = () => {
    // Assuming useClusterStatus provides a refresh function
    const { status, helmReleases, refresh } = useClusterStatus();
    const [isModalVisible, setIsModalVisible] = useState(false);
    const [upgradeTarget, setUpgradeTarget] = useState<any | null>(null);
    const [svcDefs, setSvcDefs] = useState<AvailableServices>({});

    useEffect(() => {
        getAvailableServices().then(setSvcDefs).catch(() => {});
    }, []);
    // This function will be called by the modal on successful deployment
    const handleDeploymentSuccess = () => {
      // refreshClusterStatus(); // Uncomment this to trigger a real data refresh
      console.log("Refreshing cluster status...");
    };

    const renderServiceCell = (r: HelmRelease) => {
        const key = r.serviceKey;
        const label = key && svcDefs[key]?.label ? svcDefs[key].label : (key || '—');

        return (
            <Space size={6}>
            <span>{label}</span>
            {r.managedByUi ? (
                <Tooltip title="Déployé via cette UI">
                <Tag color="blue">UI</Tag>
                </Tooltip>
            ) : null}
            </Space>
        );
    };

    if (status === 'error') {
        return <Result status="warning" title="Données des releases Helm non disponibles." subTitle="Impossible de récupérer les informations du cluster." />;
    }

    if (!helmReleases) {
      return <div style={{ textAlign: 'center', padding: '50px' }}><Spin size="large" /></div>;
    }


    const buildMenuItems = (record: any): MenuProps['items'] => ([
    // {
    //     key: 'update',
    //     icon: <SyncOutlined />,
    //     label: 'Mettre à jour',
    //     onClick: () => {
    //       setUpgradeTarget(record);      // keep selected release
    //       setIsModalVisible(true);       // open modal in upgrade mode
    //     }
    // },
    // {
    //     type: 'divider',
    // },
    {
        key: 'uninstall',
        icon: <DeleteOutlined />,
        danger: true,
        label: 'Désinstaller',
        onClick: () => {
          Modal.confirm({
            title: `Désinstaller ${record.name} ?`,
            content: `Cette action supprimera la release dans le namespace ${record.namespace}.`,
            okText: 'Désinstaller',
            okButtonProps: { danger: true },
            cancelText: 'Annuler',
            onOk: async () => {
              try {
                await uninstallHelm(record.name, record.namespace);
                message.success(`Release ${record.name} désinstallée avec succès.`);
                await refresh()
                handleDeploymentSuccess(); // refresh
              } catch (e: any) {
                message.error(e?.message || 'Erreur lors de la désinstallation');
              }
            }
          });
        }
    },
    ]);

    const columns = [
        { title: 'Nom', dataIndex: 'name', key: 'name', sorter: (a: any, b: any) => a.name.localeCompare(b.name) },
        { title: 'Namespace', dataIndex: 'namespace', key: 'namespace', sorter: (a: any, b: any) => a.namespace.localeCompare(b.namespace) },
        { title: 'Chart', dataIndex: 'chart', key: 'chart' },
        { title: 'Version', dataIndex: 'version', key: 'version' },
        { title: 'Service', key: 'service', render: (_: any, r: HelmRelease) => renderServiceCell(r) },
        { title: 'État', dataIndex: 'status', key: 'status', render: (status: any) => <StatusTag status={status} /> },
        {
        title: 'Actions',
        key: 'actions',
        render: (_: any, record: any) => {
            const items = buildMenuItems(record);
            return (
            <PermissionGuard requires="canWrite">
                <Dropdown
                menu={{
                    items,
                    onClick: ({ key }) => {
                    if (key === 'update') {
                        // TODO: open upgrade modal for `record`
                    } else if (key === 'uninstall') {
                        // TODO: confirm + uninstall `record`
                    }
                    }
                }}
                trigger={['click']}
                >
                <Button type="text" icon={<MoreOutlined />} />
                </Dropdown>
            </PermissionGuard>
            );
        },
        },
    ];

    return (
        <div>
            <div className="page-header">
                <Title level={2}>Charts Helm</Title>
                <Space>
                    <Search placeholder="Rechercher un release..." style={{ width: 250 }} />
                    <PermissionGuard requires="canWrite">
                        <Button type="primary" icon={<PlusOutlined />} onClick={() => { setUpgradeTarget(null); setIsModalVisible(true); }}>
                            Installer un Service
                        </Button>
                    </PermissionGuard>
                    <Button icon={<ReloadOutlined />} onClick={refresh}>
                        Rafraîchir
                    </Button>
                </Space>
            </div>
            <Table
              columns={columns}
              dataSource={helmReleases}
              rowKey={(r:any) => `${r.namespace}/${r.name}`}   // clé stable
              loading={status === 'loading'}
            />

            {/* Render the modal and pass the necessary props */}
            <ServiceInstallationModal
            visible={isModalVisible}
            onClose={() => { setIsModalVisible(false); setUpgradeTarget(null); }}
            onDeploy={() => { setIsModalVisible(false); setUpgradeTarget(null); handleDeploymentSuccess(); refresh(); }}
            // NEW: upgrade mode + prefill if a row was selected
            mode={upgradeTarget ? 'upgrade' : 'deploy'}
            initialRelease={
                upgradeTarget
                ? { name: upgradeTarget.name, namespace: upgradeTarget.namespace, chart: upgradeTarget.chart }
                : undefined
            }
            />
        </div>
    );
};

export default HelmReleasesPage;