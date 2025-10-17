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
import ServiceInstallationModal from '../components/ServiceInstallationModal/index.tsx';

import { uninstallHelm } from '../api/client';

import './Page.css';

const { Title } = Typography;
const { Search } = Input;

const HelmReleasesPage: React.FC = () => {
    // Assuming useClusterStatus provides a refresh function
    const { status, helmReleases, refresh } = useClusterStatus();
    const [isModalVisible, setIsModalVisible] = useState(false);
    const [upgradeTarget, setUpgradeTarget] = useState<any | null>(null);
    const [serviceDefinitions, setServiceDefinitions] = useState<AvailableServices>({});

    useEffect(() => {
        getAvailableServices().then(setServiceDefinitions).catch(() => {});
    }, []);
    
    // This function will be called by the modal on successful deployment
    const handleDeploymentSuccess = () => {
      // refreshClusterStatus(); // Uncomment this to trigger a real data refresh
      console.log("Refreshing cluster status...");
    };

    const renderServiceCell = (releaseRecord: HelmRelease) => {
        const serviceKey = releaseRecord.serviceKey;
        const serviceLabel = serviceKey && serviceDefinitions[serviceKey]?.label ? serviceDefinitions[serviceKey].label : (serviceKey || '—');

        return (
            <Space size={6}>
            <span>{serviceLabel}</span>
            {releaseRecord.managedByUi ? (
                <Tooltip title="Deployed via this UI">
                <Tag color="blue">UI</Tag>
                </Tooltip>
            ) : null}
            </Space>
        );
    };

    if (status === 'error') {
        return <Result status="warning" title="Helm releases data not available." subTitle="Unable to retrieve cluster information." />;
    }

    if (!helmReleases) {
      return <div style={{ textAlign: 'center', padding: '50px' }}><Spin size="large" /></div>;
    }


    const buildMenuItems = (record: any): MenuProps['items'] => ([
    // {
    //     key: 'update',
    //     icon: <SyncOutlined />,
    //     label: 'Update',
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
        label: 'Uninstall',
        onClick: () => {
          Modal.confirm({
            title: `Uninstall ${record.name}?`,
            content: `This action will remove the release in namespace ${record.namespace}.`,
            okText: 'Uninstall',
            okButtonProps: { danger: true },
            cancelText: 'Cancel',
            onOk: async () => {
              try {
                await uninstallHelm(record.name, record.namespace);
                message.success(`Release ${record.name} uninstalled successfully.`);
                await refresh()
                handleDeploymentSuccess(); // refresh
              } catch (e: any) {
                message.error(e?.message || 'Error during uninstallation');
              }
            }
          });
        }
    },
    ]);

    const columns = [
        { title: 'Name', dataIndex: 'name', key: 'name', sorter: (a: any, b: any) => a.name.localeCompare(b.name) },
        { title: 'Namespace', dataIndex: 'namespace', key: 'namespace', sorter: (a: any, b: any) => a.namespace.localeCompare(b.namespace) },
        { title: 'Chart', dataIndex: 'chart', key: 'chart' },
        { title: 'Version', dataIndex: 'version', key: 'version' },
        { title: 'Service', key: 'service', render: (_: any, releaseRecord: HelmRelease) => renderServiceCell(releaseRecord) },
        { title: 'Status', dataIndex: 'status', key: 'status', render: (status: any) => <StatusTag status={status} /> },
        {
        title: 'Actions',
        key: 'actions',
        render: (_: any, record: any) => {
            const menuItems = buildMenuItems(record);
            return (
            <PermissionGuard requires="canWrite">
                <Dropdown
                menu={{
                    items: menuItems,
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
                <Title level={2}>Helm Charts</Title>
                <Space>
                    <Search placeholder="Search for a release..." style={{ width: 250 }} />
                    <PermissionGuard requires="canWrite">
                        <Button type="primary" icon={<PlusOutlined />} onClick={() => { setUpgradeTarget(null); setIsModalVisible(true); }}>
                            Install Service
                        </Button>
                    </PermissionGuard>
                    <Button icon={<ReloadOutlined />} onClick={refresh}>
                        Refresh
                    </Button>
                </Space>
            </div>
            <Table
              columns={columns}
              dataSource={helmReleases}
              rowKey={(releaseRecord:any) => `${releaseRecord.namespace}/${releaseRecord.name}`}   // stable key
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