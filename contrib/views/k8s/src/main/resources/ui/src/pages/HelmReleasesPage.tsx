// ui/src/pages/HelmReleasesPage.tsx
import React, { useEffect, useState } from 'react';
import { Typography, Button, Table, Input, Space, Modal, message, Dropdown, Spin, Result, Tag, Tooltip, Progress, List, Switch } from 'antd';
import { useNavigate } from 'react-router-dom';
import { getAvailableServices, getReleaseValues, uninstallHelm, getCommandStatus, listCommands, getHelmReleases, type CommandStatus, submitHelmDeploy } from '../api/client';
import type { AvailableServices } from '../types/ServiceTypes';
import type { HelmRelease } from '../types';
import type { MenuProps } from 'antd';
import { PlusOutlined, MoreOutlined, SyncOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import { useClusterStatus } from '../context/ClusterStatusContext';
import StatusTag from '../components/common/StatusTag';
import PermissionGuard from '../components/common/PermissionGuard';
import ServiceInstallationModal from '../components/ServiceInstallationModal/index.tsx';
import BackgroundOperationsModal from '../components/common/BackgroundOperationsModal';

import './Page.css';

const { Title, Text } = Typography;
const { Search } = Input;

const HelmReleasesPage: React.FC = () => {
    const { status, refresh } = useClusterStatus();
    const navigate = useNavigate();
  const [isModalVisible, setIsModalVisible] = useState(false);
  const [upgradeTarget, setUpgradeTarget] = useState<any | null>(null);
  const [serviceDefinitions, setServiceDefinitions] = useState<AvailableServices>({});
  const [commandDrawerOpen, setCommandDrawerOpen] = useState(false);
  const [watchedCommandId, setWatchedCommandId] = useState<string | undefined>(undefined);
  const [installPickerOpen, setInstallPickerOpen] = useState(false);
  const [commandHistory, setCommandHistory] = useState<Array<{id: string; label: string}>>([]);
  const [releases, setReleases] = useState<HelmRelease[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [showAll, setShowAll] = useState(false);

  useEffect(() => {
      getAvailableServices().then(setServiceDefinitions).catch(() => {});
  }, []);

  const gitOptsFor = (record: HelmRelease) => {
    if (record.deploymentMode !== 'FLUX_GITOPS') return undefined;
    return {
      repoUrl: record.gitRepoUrl,
      branch: record.gitBranch,
      commitMode: record.gitPrNumber ? 'PR_MODE' : 'DIRECT_COMMIT'
    };
  };

  const fetchReleases = React.useCallback(async (pageNum = page, size = pageSize) => {
    setLoading(true);
    try {
      const offset = (pageNum - 1) * size;
      const res = await getHelmReleases(size, offset);
      setReleases(res.items || []);
      setTotal(res.total || 0);
      setPage(pageNum);
      setPageSize(size);
    } catch (e: any) {
      message.error(e?.message || 'Failed to load releases');
    } finally {
      setLoading(false);
    }
  }, [page, pageSize]);

  useEffect(() => {
    void fetchReleases(1, pageSize);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

    const refreshSecurityProfile = async (record: HelmRelease) => {
      const hide = message.loading('Refreshing security profile...', 0);
      try {
        const currentValues = await getReleaseValues(record.namespace, record.name);
        const payload = {
        chart: record.chartRef || record.chart,
        releaseName: record.name,
        namespace: record.namespace,
        values: currentValues,
        serviceKey: record.serviceKey,
        securityProfile: record.securityProfile,
        repoId: record.repoId,
        deploymentMode: record.deploymentMode,
        git: gitOptsFor(record)
      };
      const res = await submitHelmDeploy(payload as any);
      if (res?.id) {
        setWatchedCommandId(res.id);
        setCommandDrawerOpen(true);
        message.success('Security refresh started');
      } else {
        message.success('Security refresh submitted');
      }
    } catch (e: any) {
      message.error(e?.message || 'Failed to refresh security profile');
    } finally {
      hide();
    }
  };

  const resyncRelease = async (record: HelmRelease, reason: string) => {
    const hide = message.loading(reason, 0);
    try {
      const currentValues = await getReleaseValues(record.namespace, record.name);
      const payload = {
        chart: record.chartRef || record.chart,
        releaseName: record.name,
        namespace: record.namespace,
        values: currentValues,
        serviceKey: record.serviceKey,
        securityProfile: record.securityProfile,
        repoId: record.repoId,
        deploymentMode: record.deploymentMode,
        git: gitOptsFor(record)
      };
      const res = await submitHelmDeploy(payload as any);
      if (res?.id) {
        setWatchedCommandId(res.id);
        setCommandDrawerOpen(true);
        message.success('Resync started');
      } else {
        message.success('Resync submitted');
      }
    } catch (e: any) {
      message.error(e?.message || 'Failed to resync release');
    } finally {
      hide();
    }
  };

    // legacy commandHistory kept for label building; actual statuses fetched via shared modal
    
    const handleDeploymentSuccess = () => {
      fetchReleases(page, pageSize);
      refresh(); 
    };

    const renderServiceCell = (releaseRecord: HelmRelease) => {
        const serviceKey = releaseRecord.serviceKey;
        const serviceLabel = serviceKey && serviceDefinitions[serviceKey]?.label ? serviceDefinitions[serviceKey].label : (serviceKey || '—');

        return (
            <Space size={4} direction="vertical" align="start">
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

    if (loading && releases.length === 0) {
      return <div style={{ textAlign: 'center', padding: '50px' }}><Spin size="large" /></div>;
    }

    const buildMenuItems = (record: any): MenuProps['items'] => ([
    {
        key: 'update',
        icon: <SyncOutlined />,
        label: 'Upgrade / Config',
        onClick: async () => {
          const hideLoading = message.loading("Fetching current configuration...", 0);
          try {
            // 1. Fetch current values from backend (returns JSON object)
            const currentValues = await getReleaseValues(record.namespace, record.name);
            
            // 2. Prepare target object with all necessary metadata + values
            setUpgradeTarget({
              ...record, // includes name, namespace, chart, version, repoId
              currentValues: currentValues 
            });

            // 3. Open Modal
            setIsModalVisible(true);
          } catch (e: any) {
            console.error(e);
            message.error(e.message || "Failed to load current configuration");
          } finally {
            hideLoading();
          }
        }
    },
    ...(record.securityProfile ? [{
        key: 'refresh-security',
        icon: <ReloadOutlined />,
        disabled: !record.securityProfileStale,
        label: record.securityProfileStale ? 'Refresh security profile' : 'Refresh security profile (up-to-date)',
        onClick: () => refreshSecurityProfile(record)
    }] : []),
    ...((record.serviceKey && (record.restartRequired || record.securityProfileStale)) ? [{
        key: 'resync',
        icon: <ReloadOutlined />,
        label: 'Resync / restart',
        onClick: () => resyncRelease(record, 'Resyncing release...')
    }] : []),
    {
        type: 'divider',
    },
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
                const params = new URLSearchParams();
                if (record.repoId) params.set('repoId', record.repoId);
                if (record.deploymentMode) params.set('deploymentMode', record.deploymentMode);
                await uninstallHelm(record.name, record.namespace, params);
                message.success(`Release ${record.name} uninstalled successfully.`);
                await refresh();
              } catch (e: any) {
                message.error(e?.message || 'Error during uninstallation');
              }
            }
          });
        }
    },
    ]);

    const renderEndpoints = (release: HelmRelease) => {
    if (!release.endpoints || release.endpoints.length === 0) {
        return '—';
    }

    return (
        <Space size={4} direction="vertical">
        {release.endpoints.map(ep => (
            <div key={ep.id || ep.url}>
            {ep.url ? (
                <a href={ep.url} target="_blank" rel="noreferrer">
                {ep.label || ep.id || ep.url}
                </a>
            ) : (
                <span>{ep.label || ep.id}</span>
            )}

            {ep.internal && (
                <Tag size="small" style={{ marginLeft: 4 }} color="default">
                internal
                </Tag>
            )}
            </div>
        ))}
        </Space>
    );
    };

    const columns = [
        {
          title: 'Name',
          dataIndex: 'name',
          key: 'name',
          sorter: (a: any, b: any) => a.name.localeCompare(b.name),
          render: (_: any, r: HelmRelease) => (
            <Space>
              <Text
                strong
                style={{ cursor: 'pointer' }}
                onClick={() => navigate('/workloads', {
                  state: {
                    namespace: r.namespace,
                    labelSelector: `release=${r.name}`
                  }
                })}
              >
                {r.name}
              </Text>
            </Space>
          )
        },
        { title: 'Namespace', dataIndex: 'namespace', key: 'namespace', sorter: (a: any, b: any) => a.namespace.localeCompare(b.namespace) },
        { title: 'Chart', dataIndex: 'chart', key: 'chart' },
        // { title: 'Version', dataIndex: 'version', key: 'version', render: (_: any, r: HelmRelease) => r.version || r.appVersion || '—' },
        { title: 'App Version', dataIndex: 'appVersion', key: 'appVersion', render: (v: any) => v || '—' },
        { title: 'Service', key: 'service', width: 130, render: (_: any, releaseRecord: HelmRelease) => renderServiceCell(releaseRecord) },
        { title: 'Security', key: 'securityProfile', width: 140, render: (_: any, r: HelmRelease) => (
          <Space size={4} direction="vertical" align="start">
            <span>{r.securityProfile || '—'}</span>
            {r.securityProfileStale ? <Tag color="orange">Stale</Tag> : null}
          </Space>
        ) },
        { title: 'Status', dataIndex: 'status', key: 'status', width: 170, render: (_: any, r: any) => {
            const fluxTip = r.deploymentMode === 'FLUX_GITOPS'
              ? [
                  r.gitRepoUrl ? `Repo: ${r.gitRepoUrl}` : null,
                  r.gitBranch ? `Branch: ${r.gitBranch}` : null,
                  r.gitPath ? `Path: ${r.gitPath}` : null,
                  r.gitCommitSha ? `Commit: ${r.gitCommitSha}` : null,
                  r.gitPrNumber ? `PR: ${r.gitPrNumber}` : null,
                  r.gitPrUrl ? `PR URL: ${r.gitPrUrl}` : null,
                  r.gitPrState ? `PR State: ${r.gitPrState}` : null,
                  r.sourceName ? `Source: ${r.sourceNamespace || 'flux-system'}/${r.sourceName}` : null,
                  r.sourceStatus ? `Source status: ${r.sourceStatus}` : null,
                  r.sourceMessage ? `Source msg: ${r.sourceMessage}` : null,
                  r.reconcileState ? `Reconcile: ${r.reconcileState}` : null,
                  r.reconcileMessage ? `Reconcile msg: ${r.reconcileMessage}` : null,
                  r.observedGeneration ? `ObservedGeneration=${r.observedGeneration}` : null,
                  r.desiredGeneration ? `DesiredGeneration=${r.desiredGeneration}` : null,
                  r.staleGeneration ? `Reconciling generation (observed ${r.observedGeneration || '?'})` : null,
                  r.lastTransitionTime ? `Last transition: ${r.lastTransitionTime}` : null,
                  r.conditions && r.conditions.length ? `Conditions: ${r.conditions.map((c: any) => `${c.type || '?'}=${c.status || '?'}`).join('; ')}` : null,
                  r.sourceConditions && r.sourceConditions.length ? `Repo: ${r.sourceConditions.map((c: any) => `${c.type || '?'}=${c.status || '?'}`).join('; ')}` : null,
                  r.lastAppliedRevision ? `LastApplied: ${r.lastAppliedRevision}` : null,
                  r.lastAttemptedRevision ? `LastAttempted: ${r.lastAttemptedRevision}` : null,
                  r.lastHandledReconcileAt ? `ReconciledAt: ${r.lastHandledReconcileAt}` : null,
                  r.message ? `Message: ${r.message}` : null
                ].filter(Boolean).join(' · ')
              : undefined;

            return (
              <Space size={4} direction="vertical" align="start">
                <Tooltip title={r.message || fluxTip}>
                  <StatusTag status={r.status} />
                </Tooltip>
                {r.staleGeneration ? <Tag color="blue">Reconciling</Tag> : null}
                {r.restartRequired && r.serviceKey ? <Tag color="orange">Restart required</Tag> : null}
                {r.securityProfileStale ? <Tag color="orange">Security refresh</Tag> : null}
                {r.deploymentMode === 'FLUX_GITOPS' ? (
                  <Tooltip title={fluxTip || 'Flux GitOps'}>
                    <Tag color="blue">GitOps</Tag>
                  </Tooltip>
                ) : null}
                {r.deploymentMode === 'FLUX_GITOPS' && r.sourceStatus ? (
                  <Tooltip title={r.sourceMessage || 'HelmRepository status'}>
                    <Tag color={r.sourceStatus?.toLowerCase() === 'true' || r.sourceStatus?.toLowerCase() === 'ready' ? 'green' : 'orange'}>
                      Repo {r.sourceStatus}
                    </Tag>
                  </Tooltip>
                ) : null}
                {r.gitPrUrl ? (
                  <Tooltip title={r.gitPrUrl}>
                    <Tag color={r.gitPrState === 'merged' ? 'green' : r.gitPrState === 'closed' ? 'red' : 'purple'}>
                      <a href={r.gitPrUrl} target="_blank" rel="noreferrer" style={{ color: 'inherit' }}>
                        PR {r.gitPrNumber || ''}{r.gitPrState ? ` (${r.gitPrState})` : ''}
                      </a>
                    </Tag>
                  </Tooltip>
                ) : r.gitPrState ? (
                  <Tag color={r.gitPrState === 'merged' ? 'green' : r.gitPrState === 'closed' ? 'red' : 'purple'}>
                    PR {r.gitPrState}
                  </Tag>
                ) : null}
              </Space>
            );
          } },
        { title: 'Endpoints', key: 'endpoints', render: (_: any, r: HelmRelease) => renderEndpoints(r) },
        {
        title: 'Actions',
        key: 'actions',
        width: 90,
        render: (_: any, record: any) => {
            const menuItems = buildMenuItems(record);
            return (
            <PermissionGuard requires="canWrite">
                <Dropdown menu={{ items: menuItems }} trigger={['click']}>
                    <Button type="text" icon={<MoreOutlined />} />
                </Dropdown>
            </PermissionGuard>
            );
        },
        },
    ];

    const displayed = showAll ? releases : releases.filter(r => r.managedByUi);

    return (
        <div style={{ minHeight: '720px' }}>
            <div className="page-header">
                <Title level={2}>Helm Charts</Title>
                <Space wrap>
                    <Search placeholder="Search for a release..." style={{ width: 250 }} />
                    <Space>
                      <span>Show all</span>
                      <Switch size="small" checked={showAll} onChange={setShowAll} />
                    </Space>
                    <Button onClick={() => { setCommandDrawerOpen(true); }}>
                        Background operations
                    </Button>
                    <PermissionGuard requires="canWrite">
                      <Button type="primary" icon={<PlusOutlined />} onClick={() => setInstallPickerOpen(true)}>
                          Install Service
                      </Button>
                    </PermissionGuard>
                    <Button icon={<ReloadOutlined />} onClick={() => { fetchReleases(page, pageSize); refresh(); }}>
                        Refresh
                    </Button>
                </Space>
            </div>
            <Table
              columns={columns}
              dataSource={displayed}
              rowKey={(releaseRecord:any) => `${releaseRecord.namespace}/${releaseRecord.name}`}
              loading={loading || status === 'loading'}
              pagination={{
                current: page,
                pageSize,
                total: showAll ? total : displayed.length,
                onChange: (p, ps) => fetchReleases(p, ps),
                showSizeChanger: true,
                showTotal: (t, range) => `${range[0]}-${range[1]} of ${t}`
              }}
            />

            <ServiceInstallationModal
                visible={isModalVisible}
                onClose={() => { setIsModalVisible(false); setUpgradeTarget(null); }}
                onDeploy={() => { setIsModalVisible(false); setUpgradeTarget(null); handleDeploymentSuccess(); }}
                // Determine mode based on whether we clicked "Upgrade" (upgradeTarget exists) or "Install"
                mode={upgradeTarget ? 'upgrade' : 'deploy'}
                // Pass the full object (including currentValues) to the modal
                initialRelease={upgradeTarget}
            />

            <Modal
              title="Select a service to install"
              open={installPickerOpen}
              onCancel={() => setInstallPickerOpen(false)}
              footer={null}
              width={520}
            >
              <List
                dataSource={Object.keys(serviceDefinitions)}
                locale={{ emptyText: 'No services available' }}
                renderItem={(key) => (
                  <List.Item>
                    <Button
                      block
                      type="default"
                      onClick={() => {
                        setInstallPickerOpen(false);
                        navigate(`/services/${key}`);
                      }}
                    >
                      {serviceDefinitions[key]?.label || key}
                    </Button>
                  </List.Item>
                )}
              />
            </Modal>

            <Button
              style={{ position: 'fixed', top: 16, right: 16, zIndex: 1000 }}
              onClick={() => setCommandDrawerOpen(true)}
            >
              Background operations
            </Button>
            <BackgroundOperationsModal
              open={commandDrawerOpen}
              onClose={() => {
                setCommandDrawerOpen(false);
                setWatchedCommandId(undefined);
              }}
              watchCommandId={watchedCommandId}
              onAutoClose={() => {
                fetchReleases(page, pageSize);
                setWatchedCommandId(undefined);
              }}
            />
        </div>
    );
};

export default HelmReleasesPage;
