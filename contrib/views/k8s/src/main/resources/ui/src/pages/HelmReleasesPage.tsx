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

// ui/src/pages/HelmReleasesPage.tsx
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Typography, Button, Table, Input, Space, Modal, message, Dropdown, Spin, Result, Tag, Tooltip, List, Switch, Descriptions, Badge } from 'antd';
import { useNavigate } from 'react-router-dom';
import { getAvailableServices, getReleaseValues, uninstallHelm, getHelmReleases, getReleaseStatus, submitHelmDeploy, listCommands, regenerateReleaseKeytabs, reapplyReleaseRangerRepository } from '../api/client';
import type { AvailableServices } from '../types/ServiceTypes';
import type { HelmRelease } from '../types';
import type { MenuProps } from 'antd';
import { PlusOutlined, MoreOutlined, SyncOutlined, DeleteOutlined, ReloadOutlined, InfoCircleOutlined, KeyOutlined, SafetyCertificateOutlined, ExperimentOutlined } from '@ant-design/icons';
import { useClusterStatus } from '../context/ClusterStatusContext';
import StatusTag from '../components/common/StatusTag';
import PermissionGuard from '../components/common/PermissionGuard';
import BackgroundOperationsModal from '../components/common/BackgroundOperationsModal';

import './Page.css';

const { Title, Text } = Typography;
const { Search } = Input;

const HelmReleasesPage: React.FC = () => {
  const { status, refresh } = useClusterStatus();
  const navigate = useNavigate();
  const [serviceDefinitions, setServiceDefinitions] = useState<AvailableServices>({});
  const [isCommandDrawerOpen, setIsCommandDrawerOpen] = useState(false);
  const [watchedCommandId, setWatchedCommandId] = useState<string | undefined>(undefined);
  const [isInstallPickerOpen, setIsInstallPickerOpen] = useState(false);
  const [helmReleases, setHelmReleases] = useState<HelmRelease[]>([]);
  const [totalReleases, setTotalReleases] = useState(0);
  const [pageIndex, setPageIndex] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [isLoading, setIsLoading] = useState(false);
  const [showAllReleases, setShowAllReleases] = useState(false);
  const [statusByRelease, setStatusByRelease] = useState<Record<string, HelmRelease>>({});
  const [statusRefreshing, setStatusRefreshing] = useState<Record<string, boolean>>({});
  const [autoRefreshEnabled, setAutoRefreshEnabled] = useState(false);
  const [statusModalRelease, setStatusModalRelease] = useState<HelmRelease | null>(null);
  const [operationsCount, setOperationsCount] = useState<number>(0);

  const loadOperationsCount = useCallback(async () => {
    try {
      const commands = await listCommands(10, 0);
      const running = (commands || []).filter((c: any) => c.state === 'RUNNING' || c.state === 'PENDING').length;
      setOperationsCount(running);
    } catch {
      setOperationsCount(0);
    }
  }, []);

  useEffect(() => {
    /**
     * Fetch service definitions once so we can render friendly labels and
     * service metadata (including managed-by-UI hints). Errors are swallowed
     * because the page can still render releases without this data; we avoid
     * blocking the UI on non-critical metadata.
     */
    getAvailableServices().then(setServiceDefinitions).catch(() => {});
    // Load a lightweight count of running/pending background operations for the badge.
    void loadOperationsCount();
  }, [loadOperationsCount]);

  /**
   * Build GitOps options for a release when it is managed by Flux. This keeps
   * submit payloads consistent across refresh/resync actions. Returns undefined
   * for non-GitOps releases so callers can merge cleanly.
   */
  const gitOptionsForRelease = useCallback((release: HelmRelease) => {
    if (release.deploymentMode !== 'FLUX_GITOPS') return undefined;
    return {
      repoUrl: release.gitRepoUrl,
      branch: release.gitBranch,
      commitMode: release.gitPrNumber ? 'PR_MODE' : 'DIRECT_COMMIT',
    };
  }, []);

  const releaseKey = useCallback((release: HelmRelease) => `${release.namespace}/${release.name}`, []);

  /**
   * Merge live status (queried separately) into a base release record without
   * mutating originals. This allows us to cache statuses by key and keep the
   * table source stable while refreshing statuses in the background.
   */
  const mergeReleaseStatus = useCallback((release: HelmRelease) => {
    const key = releaseKey(release);
    const liveStatus = statusByRelease[key];
    return liveStatus ? { ...release, ...liveStatus } : release;
  }, [releaseKey, statusByRelease]);

  /**
   * Refresh a single release status from the backend and merge it into the
   * cached map. Caller can request toast on failure. Status-refresh state is
   * tracked per release to drive inline spinners for the action buttons.
   */
  const refreshReleaseStatus = useCallback(async (release: HelmRelease, options?: { notifyOnError?: boolean }) => {
    const key = releaseKey(release);
    setStatusRefreshing((previous) => ({ ...previous, [key]: true }));
    try {
      const latestStatus = await getReleaseStatus(release.namespace, release.name);
      setStatusByRelease((previous) => ({ ...previous, [key]: latestStatus }));
    } catch (err: any) {
      if (options?.notifyOnError) {
        message.error(err?.message || `Unable to refresh status for ${release.name}`);
      }
    } finally {
      setStatusRefreshing((previous) => ({ ...previous, [key]: false }));
    }
  }, [releaseKey]);

  /**
   * Batch-refresh statuses for releases that we manage (UI-installed) or that
   * are GitOps-managed. This avoids spamming the API for releases the user
   * might not care about while keeping visible items fresh. The calls are
   * fire-and-forget to avoid blocking the UI; individual refresh handles its
   * own spinner state per row.
   */
  const refreshAllStatuses = useCallback(() => {
    if (helmReleases.length === 0) return;
    helmReleases
      .filter((release) => release.deploymentMode === 'FLUX_GITOPS' || release.managedByUi)
      .forEach((release) => { void refreshReleaseStatus(release); });
  }, [helmReleases, refreshReleaseStatus]);

  useEffect(() => {
    if (!helmReleases.length) return;
    refreshAllStatuses();
  }, [helmReleases, refreshAllStatuses]);

  useEffect(() => {
    if (!autoRefreshEnabled) return undefined;
    const interval = setInterval(() => {
      refreshAllStatuses();
    }, 30_000);
    return () => clearInterval(interval);
  }, [autoRefreshEnabled, refreshAllStatuses]);

  useEffect(() => {
    // Refresh badge count when the modal is closed to reflect completed tasks.
    if (!isCommandDrawerOpen) {
      void loadOperationsCount();
    }
  }, [isCommandDrawerOpen, loadOperationsCount]);

  /**
   * Retrieve releases from the backend with paging, update the table source,
   * and reset pagination state. Errors are surfaced to the user and we clear
   * stale data on failure to avoid displaying outdated rows.
   */
  const fetchReleases = React.useCallback(async (pageNum = pageIndex, size = pageSize) => {
    setIsLoading(true);
    try {
      const offset = (pageNum - 1) * size;
      const releasesResponse = await getHelmReleases(size, offset);
      setHelmReleases(releasesResponse.items || []);
      setTotalReleases(releasesResponse.total || 0);
      setPageIndex(pageNum);
      setPageSize(size);
    } catch (e: any) {
      const errorMessage = e?.message || 'Failed to load releases';
      console.error('Failed to fetch Helm releases:', e);
      message.error(errorMessage);
      // Set empty state on error to prevent showing stale data
      setHelmReleases([]);
      setTotalReleases(0);
    } finally {
      setIsLoading(false);
    }
  }, [pageIndex, pageSize]);

  useEffect(() => {
    void fetchReleases(1, pageSize);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

    /**
     * Re-apply the security profile for a release by re-submitting its current
     * values. This triggers backend logic to regenerate secrets/config required
     * by the selected profile. Command is tracked via the background ops modal
     * when an ID is returned.
     */
    const refreshSecurityProfile = async (release: HelmRelease) => {
      const hide = message.loading('Refreshing security profile...', 0);
      try {
        const currentValues = await getReleaseValues(release.namespace, release.name);
        const payload = {
          chart: release.chartRef || release.chart,
          releaseName: release.name,
          namespace: release.namespace,
          values: currentValues,
          serviceKey: release.serviceKey,
          securityProfile: release.securityProfile,
          repoId: release.repoId,
          deploymentMode: release.deploymentMode,
          git: gitOptionsForRelease(release)
        };
        const response = await submitHelmDeploy(payload as any);
        if (response?.id) {
          setWatchedCommandId(response.id);
          setIsCommandDrawerOpen(true);
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

  /**
   * Re-sync a release (or trigger a restart) by re-submitting current values.
   * This is used when restartRequired or securityProfileStale flags are set.
   * The request is tracked in the background ops modal when an ID is returned.
   */
  const resyncRelease = async (release: HelmRelease, reason: string) => {
    const hide = message.loading(reason, 0);
    try {
      const currentValues = await getReleaseValues(release.namespace, release.name);
      const payload = {
        chart: release.chartRef || release.chart,
        releaseName: release.name,
        namespace: release.namespace,
        values: currentValues,
        serviceKey: release.serviceKey,
        securityProfile: release.securityProfile,
        repoId: release.repoId,
        deploymentMode: release.deploymentMode,
        git: gitOptionsForRelease(release)
      };
      const response = await submitHelmDeploy(payload as any);
      if (response?.id) {
        setWatchedCommandId(response.id);
        setIsCommandDrawerOpen(true);
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

  /**
   * Re-run keytab generation for a release without redeploying Helm.
   * This schedules a background command and opens the operations drawer for tracking.
   */
  const triggerKeytabRegeneration = async (release: HelmRelease) => {
    const hide = message.loading(`Regenerating keytabs for ${release.name}...`, 0);
    try {
      const response = await regenerateReleaseKeytabs(release.namespace, release.name);
      if (response?.id) {
        setWatchedCommandId(response.id);
        setIsCommandDrawerOpen(true);
        message.success('Keytab regeneration started');
      } else {
        message.success('Keytab regeneration submitted');
      }
    } catch (e: any) {
      message.error(e?.message || 'Failed to regenerate keytabs');
    } finally {
      hide();
    }
  };

  /**
   * Re-apply the Ranger repository configuration for a release.
   * This mirrors the install-time Ranger setup without touching the chart.
   */
  const triggerRangerRepositoryReapply = async (release: HelmRelease) => {
    const hide = message.loading(`Reapplying Ranger repository for ${release.name}...`, 0);
    try {
      const response = await reapplyReleaseRangerRepository(release.namespace, release.name);
      if (response?.id) {
        setWatchedCommandId(response.id);
        setIsCommandDrawerOpen(true);
        message.success('Ranger repository reapply started');
      } else {
        message.success('Ranger repository reapply submitted');
      }
    } catch (e: any) {
      message.error(e?.message || 'Failed to reapply Ranger repository');
    } finally {
      hide();
    }
  };

    // legacy commandHistory kept for label building; actual statuses fetched via shared modal
    
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

    // All hooks must be called before any early returns
    const displayed = useMemo(() => (showAllReleases ? helmReleases : helmReleases.filter(r => r.managedByUi)), [helmReleases, showAllReleases]);

    if (status === 'error') {
        return <Result status="warning" title="Helm releases data not available." subTitle="Unable to retrieve cluster information." />;
    }

    if (isLoading && helmReleases.length === 0) {
      return <div style={{ textAlign: 'center', padding: '50px' }}><Spin size="large" /></div>;
    }

    const buildMenuItems = (record: HelmRelease): MenuProps['items'] => {
      // Resolve the service definition to know which post-install actions are supported.
      const serviceDefinition = record.serviceKey ? serviceDefinitions[record.serviceKey] : undefined;
      const supportsKerberosRegeneration = !!(serviceDefinition?.kerberos && serviceDefinition.kerberos.length > 0);
      const supportsRangerReapply = !!(serviceDefinition?.ranger && Object.keys(serviceDefinition.ranger).length > 0);

      return ([
        {
          key: 'update',
          icon: <SyncOutlined />,
          label: 'Upgrade / Config',
          disabled: !record.serviceKey,
          onClick: () => {
            if (!record.serviceKey) {
              message.warning('Upgrade wizard is only available for UI-managed services.');
              return;
            }
            navigate(`/services/${record.serviceKey}`, {
              state: {
                mode: 'upgrade',
                releaseName: record.name,
                namespace: record.namespace,
                repoId: record.repoId,
                deploymentMode: record.deploymentMode,
                git: gitOptionsForRelease(record),
              },
            });
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
        ...(supportsKerberosRegeneration ? [{
          key: 'regenerate-keytabs',
          icon: <KeyOutlined />,
          label: 'Regenerate keytabs',
          onClick: () => triggerKeytabRegeneration(record)
        }] : []),
        ...(supportsRangerReapply ? [{
          key: 'reapply-ranger',
          icon: <SafetyCertificateOutlined />,
          label: 'Reapply Ranger repository',
          onClick: () => triggerRangerRepositoryReapply(record)
        }] : []),
        ...(record.serviceKey === 'SQL-ASSISTANT' ? [{
          key: 'open-sql-assistant',
          icon: <ExperimentOutlined />,
          label: 'Open SQL Assistant',
          onClick: () => {
            const url = `${window.location.origin}/views/SQL-ASSISTANT-VIEW/1.0.0.0/SQL_ASSISTANT_INSTANCE/`;
            window.open(url, '_blank', 'noreferrer');
          }
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
    };

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
                <Tag style={{ marginLeft: 4 }} color="default">
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
        { title: 'Status', dataIndex: 'status', key: 'status', width: 220, render: (_: any, record: HelmRelease) => {
            const key = releaseKey(record);
            const combined = mergeReleaseStatus(record);
            const fluxTip = combined.deploymentMode === 'FLUX_GITOPS'
              ? [
                  combined.gitRepoUrl ? `Repo: ${combined.gitRepoUrl}` : null,
                  combined.gitBranch ? `Branch: ${combined.gitBranch}` : null,
                  combined.gitPath ? `Path: ${combined.gitPath}` : null,
                  combined.gitCommitSha ? `Commit: ${combined.gitCommitSha}` : null,
                  combined.gitPrNumber ? `PR: ${combined.gitPrNumber}` : null,
                  combined.gitPrUrl ? `PR URL: ${combined.gitPrUrl}` : null,
                  combined.gitPrState ? `PR State: ${combined.gitPrState}` : null,
                  combined.sourceName ? `Source: ${combined.sourceNamespace || 'flux-system'}/${combined.sourceName}` : null,
                  combined.sourceStatus ? `Source status: ${combined.sourceStatus}` : null,
                  combined.sourceMessage ? `Source msg: ${combined.sourceMessage}` : null,
                  combined.reconcileState ? `Reconcile: ${combined.reconcileState}` : null,
                  combined.reconcileMessage ? `Reconcile msg: ${combined.reconcileMessage}` : null,
                  combined.observedGeneration ? `ObservedGeneration=${combined.observedGeneration}` : null,
                  combined.desiredGeneration ? `DesiredGeneration=${combined.desiredGeneration}` : null,
                  combined.staleGeneration ? `Reconciling generation (observed ${combined.observedGeneration || '?'})` : null,
                  combined.lastTransitionTime ? `Last transition: ${combined.lastTransitionTime}` : null,
                  combined.conditions && combined.conditions.length ? `Conditions: ${combined.conditions.map((c: any) => `${c.type || '?'}=${c.status || '?'}`).join('; ')}` : null,
                  combined.sourceConditions && combined.sourceConditions.length ? `Repo: ${combined.sourceConditions.map((c: any) => `${c.type || '?'}=${c.status || '?'}`).join('; ')}` : null,
                  combined.lastAppliedRevision ? `LastApplied: ${combined.lastAppliedRevision}` : null,
                  combined.lastAttemptedRevision ? `LastAttempted: ${combined.lastAttemptedRevision}` : null,
                  combined.lastHandledReconcileAt ? `ReconciledAt: ${combined.lastHandledReconcileAt}` : null,
                  combined.message ? `Message: ${combined.message}` : null
                ].filter(Boolean).join(' · ')
              : undefined;
            const gitTooltip = combined.deploymentMode === 'FLUX_GITOPS'
              ? [
                  combined.gitRepoUrl ? `Repo: ${combined.gitRepoUrl}` : null,
                  combined.gitBranch ? `Branch: ${combined.gitBranch}` : null,
                  combined.gitPath ? `Path: ${combined.gitPath}` : null
                ].filter(Boolean).join('\n')
              : undefined;

            return (
              <Space size={4} direction="vertical" align="start">
                <Space size={4}>
                  <Tooltip title={combined.message || fluxTip || 'Release status'}>
                    <StatusTag status={combined.status} />
                  </Tooltip>
                  <Button type="link" size="small" onClick={() => setStatusModalRelease(combined)}>Details</Button>
                  <Tooltip title="Refresh status">
                    <Button
                      type="text"
                      size="small"
                      icon={<SyncOutlined />}
                      loading={!!statusRefreshing[key]}
                      onClick={() => refreshReleaseStatus(record, { notifyOnError: true })}
                    />
                  </Tooltip>
                  {gitTooltip ? (
                    <Tooltip title={gitTooltip}>
                      <InfoCircleOutlined style={{ color: '#8c8c8c' }} />
                    </Tooltip>
                  ) : null}
                </Space>
                {combined.staleGeneration ? <Tag color="blue">Reconciling</Tag> : null}
                {combined.restartRequired && combined.serviceKey ? <Tag color="orange">Restart required</Tag> : null}
                {combined.securityProfileStale ? <Tag color="orange">Security refresh</Tag> : null}
                {combined.deploymentMode === 'FLUX_GITOPS' ? (
                  <Tooltip title={fluxTip || 'Flux GitOps'}>
                    <Tag color="blue">GitOps</Tag>
                  </Tooltip>
                ) : null}
                {combined.deploymentMode === 'FLUX_GITOPS' && combined.sourceStatus ? (
                  <Tooltip title={combined.sourceMessage || 'HelmRepository status'}>
                    <Tag color={combined.sourceStatus?.toLowerCase() === 'true' || combined.sourceStatus?.toLowerCase() === 'ready' ? 'green' : 'orange'}>
                      Repo {combined.sourceStatus}
                    </Tag>
                  </Tooltip>
                ) : null}
                {combined.gitPrUrl ? (
                  <Tooltip title={combined.gitPrUrl}>
                    <Tag color={combined.gitPrState === 'merged' ? 'green' : combined.gitPrState === 'closed' ? 'red' : 'purple'}>
                      <a href={combined.gitPrUrl} target="_blank" rel="noreferrer" style={{ color: 'inherit' }}>
                        PR {combined.gitPrNumber || ''}{combined.gitPrState ? ` (${combined.gitPrState})` : ''}
                      </a>
                    </Tag>
                  </Tooltip>
                ) : combined.gitPrState ? (
                  <Tag color={combined.gitPrState === 'merged' ? 'green' : combined.gitPrState === 'closed' ? 'red' : 'purple'}>
                    PR {combined.gitPrState}
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

    return (
        <div style={{ minHeight: '720px' }}>
            <div className="page-header">
                <Title level={2}>Helm Charts</Title>
                <Space wrap>
                    <Search placeholder="Search for a release..." style={{ width: 250 }} />
                    <Space>
                      <span>Show all</span>
                      <Switch size="small" checked={showAllReleases} onChange={setShowAllReleases} />
                    </Space>
                    <Space align="center" size={4}>
                      <Switch size="small" checked={autoRefreshEnabled} onChange={setAutoRefreshEnabled} />
                      <span>Auto status refresh</span>
                    </Space>
                    <Badge count={operationsCount} offset={[8, 0]}>
                      <Button onClick={() => { setIsCommandDrawerOpen(true); }}>
                          Background operations
                      </Button>
                    </Badge>
                    <PermissionGuard requires="canWrite">
                      <Button type="primary" icon={<PlusOutlined />} onClick={() => setIsInstallPickerOpen(true)}>
                          Install Service
                      </Button>
                    </PermissionGuard>
                    <Button icon={<ReloadOutlined />} onClick={() => { fetchReleases(pageIndex, pageSize); refresh(); }}>
                        Refresh
                    </Button>
                </Space>
            </div>
            <Table
              columns={columns}
              dataSource={displayed}
              rowKey={(releaseRecord:any) => `${releaseRecord.namespace}/${releaseRecord.name}`}
              loading={isLoading || status === 'loading'}
              size="small"
              pagination={{
                current: pageIndex,
                pageSize,
                total: showAllReleases ? totalReleases : displayed.length,
                onChange: (p, ps) => fetchReleases(p, ps),
                showSizeChanger: true,
                showTotal: (t, range) => `${range[0]}-${range[1]} of ${t}`
              }}
            />

            <Modal
              title={`Release status — ${statusModalRelease?.namespace || ''}/${statusModalRelease?.name || ''}`}
              open={!!statusModalRelease}
              onCancel={() => setStatusModalRelease(null)}
              footer={<Button onClick={() => setStatusModalRelease(null)}>Close</Button>}
              width={640}
            >
              <Descriptions column={1} size="small" bordered>
                <Descriptions.Item label="Status">
                  <Space>
                    <StatusTag status={statusModalRelease?.status || 'unknown'} />
                    <span>{statusModalRelease?.message || 'No status message'}</span>
                  </Space>
                </Descriptions.Item>
                <Descriptions.Item label="GitOps">
                  {statusModalRelease?.deploymentMode === 'FLUX_GITOPS' ? (
                    <Space direction="vertical" size="small">
                      <span>Repo: {statusModalRelease.gitRepoUrl || '—'}</span>
                      <span>Branch: {statusModalRelease.gitBranch || '—'}</span>
                      <span>Path: {statusModalRelease.gitPath || '—'}</span>
                      {statusModalRelease.gitPrState ? (
                        <span>
                          PR {statusModalRelease.gitPrNumber || ''} — {statusModalRelease.gitPrState}
                          {statusModalRelease.gitPrUrl ? (
                            <a href={statusModalRelease.gitPrUrl} target="_blank" rel="noreferrer" style={{ marginLeft: 8 }}>
                              View
                            </a>
                          ) : null}
                        </span>
                      ) : null}
                    </Space>
                  ) : (
                    'Direct Helm deployment'
                  )}
                </Descriptions.Item>
                <Descriptions.Item label="Reconcile">
                  <Space direction="vertical" size="small">
                    <span>Observed generation: {statusModalRelease?.observedGeneration || '—'}</span>
                    <span>Desired generation: {statusModalRelease?.desiredGeneration || '—'}</span>
                    <span>Last reconcile: {statusModalRelease?.lastHandledReconcileAt || '—'}</span>
                    <span>Last transition: {statusModalRelease?.lastTransitionTime || '—'}</span>
                    {statusModalRelease?.staleGeneration ? <Tag color="blue">Reconciling</Tag> : null}
                  </Space>
                </Descriptions.Item>
                <Descriptions.Item label="Conditions">
                  {statusModalRelease?.conditions?.length ? statusModalRelease.conditions.map((cond, idx) => (
                    <div key={`${idx}-${cond.type}`}>
                      <strong>{cond.type || 'Condition'}</strong> – {cond.status || 'Unknown'} {cond.message ? `· ${cond.message}` : ''}
                    </div>
                  )) : '—'}
                </Descriptions.Item>
                <Descriptions.Item label="Repo health">
                  {statusModalRelease?.sourceConditions?.length ? statusModalRelease.sourceConditions.map((cond, idx) => (
                    <div key={`source-${idx}-${cond.type}`}>
                      <strong>{cond.type || 'Source'}</strong> – {cond.status || 'Unknown'} {cond.message ? `· ${cond.message}` : ''}
                    </div>
                  )) : '—'}
                </Descriptions.Item>
              </Descriptions>
            </Modal>

            <Modal
              title="Select a service to install"
              open={isInstallPickerOpen}
              onCancel={() => setIsInstallPickerOpen(false)}
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
                        setIsInstallPickerOpen(false);
                        navigate(`/services/${key}`);
                      }}
                    >
                      {serviceDefinitions[key]?.label || key}
                    </Button>
                  </List.Item>
                )}
              />
            </Modal>

            <BackgroundOperationsModal
              open={isCommandDrawerOpen}
              onClose={() => {
                setIsCommandDrawerOpen(false);
                setWatchedCommandId(undefined);
              }}
              watchCommandId={watchedCommandId}
              onAutoClose={() => {
                fetchReleases(pageIndex, pageSize);
                setWatchedCommandId(undefined);
              }}
            />
        </div>
    );
};

export default HelmReleasesPage;
