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
import { Typography, Button, Table, Input, Space, Modal, message, Dropdown, Skeleton, Result, Tag, Tooltip, Switch, Descriptions, Badge, Row, Col } from 'antd';
import { useNavigate } from 'react-router-dom';
import { getAvailableServices, getReleaseValues, uninstallHelm, getHelmReleases, getReleaseStatus, submitHelmDeploy, listCommands, regenerateReleaseKeytabs, reapplyReleaseRangerRepository, registerReleaseOidcClient, upgradeReleaseChart, rollbackReleaseToRevision, getReleaseHistory, getReleaseTlsState, type ReleaseTlsEntry } from '../api/client';
import { API_BASE_URL } from '../api/client';
import type { HelmHistoryEntry } from '../api/client';
import type { AvailableServices } from '../types/ServiceTypes';
import type { HelmRelease } from '../types';
import type { MenuProps } from 'antd';
import { PlusOutlined, MoreOutlined, SyncOutlined, DeleteOutlined, ReloadOutlined, InfoCircleOutlined, KeyOutlined, SafetyCertificateOutlined, ExperimentOutlined, ArrowUpOutlined, RollbackOutlined } from '@ant-design/icons';
import { useClusterStatus } from '../context/ClusterStatusContext';
import StatusTag from '../components/common/StatusTag';
import PermissionGuard from '../components/common/PermissionGuard';
import BackgroundOperationsModal from '../components/common/BackgroundOperationsModal';
import { SERVICE_ICONS } from '../assets/services';

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
  // TLS state per release, keyed by "<namespace>/<releaseName>". Loaded lazily after the
  // release list arrives so the page doesn't stall waiting for cert parses on large lists.
  const [tlsByRelease, setTlsByRelease] = useState<Record<string, ReleaseTlsEntry[]>>({});
  const [autoRefreshEnabled, setAutoRefreshEnabled] = useState(false);
  const [statusModalRelease, setStatusModalRelease] = useState<HelmRelease | null>(null);
  const [operationsCount, setOperationsCount] = useState<number>(0);
  const [historyModalRelease, setHistoryModalRelease] = useState<HelmRelease | null>(null);
  const [historyEntries, setHistoryEntries] = useState<HelmHistoryEntry[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | undefined>(undefined);
  const [rollingBackRevision, setRollingBackRevision] = useState<number | undefined>(undefined);

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
      const items = releasesResponse.items || [];
      setHelmReleases(items);
      setTotalReleases(releasesResponse.total || 0);
      setPageIndex(pageNum);
      setPageSize(size);
      // Kick off per-release TLS state lookups in parallel — non-blocking, populates
      // the badges as results arrive. A failure on any single release just leaves
      // that row showing the empty placeholder.
      items.forEach((r) => {
        const key = `${r.namespace}/${r.name}`;
        getReleaseTlsState(r.namespace, r.name)
          .then((entries) => setTlsByRelease((prev) => ({ ...prev, [key]: entries })))
          .catch(() => setTlsByRelease((prev) => ({ ...prev, [key]: [] })));
      });
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

    const triggerOidcRegistration = async (release: HelmRelease) => {
    const hide = message.loading(`Registering OIDC client for ${release.name}...`, 0);
    try {
      const response = await registerReleaseOidcClient(release.namespace, release.name);
      if (response?.id) {
        setWatchedCommandId(response.id);
        setIsCommandDrawerOpen(true);
        message.success('OIDC client registration started');
      } else {
        message.success('OIDC client registration submitted');
      }
    } catch (e: any) {
      message.error(e?.message || 'Failed to register OIDC client');
    } finally {
      hide();
    }
  };

  /**
   * Return the chart version published in the service catalog (service.json) for a release,
   * or undefined when the release is not bound to a known service.
   */
  const catalogVersionFor = useCallback((release: HelmRelease): string | undefined => {
    if (!release.serviceKey) return undefined;
    return serviceDefinitions[release.serviceKey]?.version;
  }, [serviceDefinitions]);

  /**
   * Detect a chart version drift between what is deployed and what the catalog ships with.
   * Returns the catalog version when an in-place upgrade is offered, otherwise undefined.
   */
  const upgradeAvailableFor = useCallback((release: HelmRelease): string | undefined => {
    const catalogVersion = catalogVersionFor(release);
    if (!catalogVersion) return undefined;
    if (!release.version) return undefined;
    if (release.version === catalogVersion) return undefined;
    if (release.deploymentMode === 'FLUX_GITOPS') return undefined;
    return catalogVersion;
  }, [catalogVersionFor]);

  /**
   * Open the Revision History modal for a release. The modal fetches `helm history`
   * via the backend and lets the operator pick a concrete revision to roll back to,
   * removing the "what does previous mean?" ambiguity of the old action.
   */
  const openHistoryModal = useCallback(async (release: HelmRelease) => {
    setHistoryModalRelease(release);
    setHistoryEntries([]);
    setHistoryError(undefined);
    setHistoryLoading(true);
    try {
      const entries = await getReleaseHistory(release.namespace, release.name);
      // Newest first so the current revision sits at the top.
      const sorted = [...(entries || [])].sort((a, b) => (b.revision ?? 0) - (a.revision ?? 0));
      setHistoryEntries(sorted);
    } catch (e: any) {
      setHistoryError(e?.message || 'Failed to load revision history');
    } finally {
      setHistoryLoading(false);
    }
  }, []);

  /**
   * Roll back to a specific revision picked from the history modal. Used by the per-row
   * action button inside the modal; closes the modal on success.
   */
  const performRollbackToRevision = useCallback(async (release: HelmRelease, revision: number) => {
    setRollingBackRevision(revision);
    try {
      await rollbackReleaseToRevision(release.namespace, release.name, revision);
      message.success(`Rolled back ${release.name} to revision ${revision}`);
      setHistoryModalRelease(null);
      void refreshReleaseStatus(release, { notifyOnError: false });
    } catch (e: any) {
      message.error(e?.message || 'Failed to roll back release');
    } finally {
      setRollingBackRevision(undefined);
    }
  }, [refreshReleaseStatus]);

  /**
   * Trigger an in-place chart upgrade for a release after explicit user confirmation.
   * The backend preserves deployed values and only bumps the chart version.
   */
  const triggerChartUpgrade = async (release: HelmRelease, targetVersion: string) => {
    Modal.confirm({
      title: `Upgrade ${release.name} to chart ${targetVersion}?`,
      content: (
        <Space direction="vertical" size={4}>
          <Text>This will run a Helm upgrade preserving the values currently deployed.</Text>
          <Text type="secondary">Current chart version: {release.version || 'unknown'}</Text>
          <Text type="secondary">Target chart version: {targetVersion}</Text>
        </Space>
      ),
      okText: 'Upgrade',
      cancelText: 'Cancel',
      onOk: async () => {
        const hide = message.loading(`Upgrading ${release.name} to ${targetVersion}...`, 0);
        try {
          const response = await upgradeReleaseChart(release.namespace, release.name, targetVersion);
          if (response?.id) {
            setWatchedCommandId(response.id);
            setIsCommandDrawerOpen(true);
            message.success('Chart upgrade started');
          } else {
            message.success('Chart upgrade submitted');
          }
        } catch (e: any) {
          message.error(e?.message || 'Failed to upgrade chart');
        } finally {
          hide();
        }
      }
    });
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
      return <Skeleton active paragraph={{ rows: 6 }} style={{ padding: 16 }} />;
    }

    const buildMenuItems = (record: HelmRelease): MenuProps['items'] => {
      // Resolve the service definition to know which post-install actions are supported.
      const serviceDefinition = record.serviceKey ? serviceDefinitions[record.serviceKey] : undefined;
      const supportsKerberosRegeneration = !!(serviceDefinition?.kerberos && serviceDefinition.kerberos.length > 0);
      const supportsRangerReapply = !!(serviceDefinition?.ranger && Object.keys(serviceDefinition.ranger).length > 0);
      const supportsOidcRegistration = !!(serviceDefinition?.oidc && serviceDefinition.oidc.length > 0);
      const upgradeTargetVersion = upgradeAvailableFor(record);

      return ([
        ...(upgradeTargetVersion ? [{
          key: 'upgrade-chart',
          icon: <ArrowUpOutlined />,
          label: `Upgrade chart to ${upgradeTargetVersion}`,
          onClick: () => triggerChartUpgrade(record, upgradeTargetVersion),
        }] : []),
        {
          key: 'rollback',
          icon: <RollbackOutlined />,
          label: 'Revision history…',
          onClick: () => { void openHistoryModal(record); },
        },
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
                securityProfile: record.securityProfile,
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
        ...(supportsOidcRegistration ? [{
          key: 'register-oidc',
          icon: <SafetyCertificateOutlined />,
          label: 'Re-register OIDC client',
          onClick: () => triggerOidcRegistration(record)
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

    /**
     * Render a small badge per TLS entry of a release: green/yellow/red on expiry,
     * grey on "no TLS" / external, with a tooltip carrying issuer + SAN + source +
     * a "Renew" button when the source is renew-capable.
     */
    const renderTlsBadges = (release: HelmRelease) => {
      const key = `${release.namespace}/${release.name}`;
      const entries = tlsByRelease[key];
      if (entries === undefined) return <Text type="secondary">…</Text>;
      if (entries.length === 0) return <Text type="secondary">—</Text>;
      const renewable = entries.some(e =>
        e.source === 'k8s-view-self-signed' || e.source === 'cert-manager' || e.source === 'external-secrets'
      );
      const onRenew = async () => {
        try {
          const res = await fetch(
            `${API_BASE_URL}/helm/releases/${encodeURIComponent(release.namespace)}/${encodeURIComponent(release.name)}/tls/renew`,
            { method: 'POST', headers: { 'X-Requested-By': 'ambari' } }
          );
          if (!res.ok) throw new Error(await res.text());
          const body = await res.json();
          message.success('TLS renewal scheduled');
          setWatchedCommandId(body.id);
          setIsCommandDrawerOpen(true);
        } catch (e: any) {
          message.error('Failed to schedule renewal: ' + (e?.message || e));
        }
      };
      return (
        <Space size={4} direction="vertical" style={{ width: '100%' }}>
          {entries.map((e, i) => {
            const tone: Record<string, 'success' | 'warning' | 'error' | 'default'> = {
              valid: 'success',
              'expiring-warning': 'warning',
              'expiring-soon': 'warning',
              expired: 'error',
              'no-tls': 'default',
              'secret-missing': 'error',
              'no-tls-crt': 'error',
              'no-cert-in-secret': 'error',
              'read-error': 'error',
            };
            const sourceLabel: Record<string, string> = {
              'k8s-view-self-signed': 'self-signed (k8s-view)',
              'cert-manager': 'cert-manager',
              'external-secrets': 'external-secrets',
              'external': 'operator-managed',
            };
            const txt = e.status === 'valid'
              ? `${e.daysUntilExpiry}d`
              : e.status === 'expiring-warning' || e.status === 'expiring-soon'
                ? `Expires ${e.daysUntilExpiry}d`
                : e.status;
            const tip = (
              <div>
                <div><b>Status:</b> {e.status}</div>
                {e.source && <div><b>Source:</b> {sourceLabel[e.source] ?? e.source}</div>}
                {e.issuer && <div><b>Issuer:</b> {e.issuer}</div>}
                {e.notAfter && <div><b>Expires:</b> {new Date(e.notAfter).toLocaleString()}</div>}
                {e.sans && e.sans.length > 0 && <div><b>SANs:</b> {e.sans.join(', ')}</div>}
                {e.secretName && <div><b>Secret:</b> {e.namespace}/{e.secretName}</div>}
              </div>
            );
            return (
              <Tooltip key={i} title={tip}>
                <Tag color={tone[e.status] || 'default'}>{txt}</Tag>
              </Tooltip>
            );
          })}
          {renewable && (
            <Button size="small" type="link" onClick={onRenew} style={{ padding: 0 }}>
              Renew
            </Button>
          )}
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
        {
          title: 'Chart',
          dataIndex: 'chart',
          key: 'chart',
          render: (_: any, r: HelmRelease) => {
            const upgradeTargetVersion = upgradeAvailableFor(r);
            return (
              <Space size={4} direction="vertical" align="start">
                <span>{r.chart}</span>
                {r.version ? (
                  <Space size={4}>
                    <Text type="secondary" style={{ fontSize: 12 }}>v{r.version}</Text>
                    {upgradeTargetVersion ? (
                      <Tooltip title={`Catalog ships chart v${upgradeTargetVersion}. Click the row menu to upgrade in place.`}>
                        <Tag color="gold" style={{ marginLeft: 0 }}>Upgrade → v{upgradeTargetVersion}</Tag>
                      </Tooltip>
                    ) : null}
                  </Space>
                ) : null}
              </Space>
            );
          }
        },
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

            const extraTags: React.ReactNode[] = [];
            if (combined.staleGeneration) extraTags.push(<Tag key="reconciling" color="blue">Reconciling</Tag>);
            if (combined.restartRequired && combined.serviceKey) extraTags.push(<Tag key="restart" color="orange">Restart required</Tag>);
            if (combined.securityProfileStale) extraTags.push(<Tag key="security" color="orange">Security refresh</Tag>);
            if (combined.deploymentMode === 'FLUX_GITOPS') extraTags.push(<Tooltip key="gitops" title={fluxTip || 'Flux GitOps'}><Tag color="blue">GitOps</Tag></Tooltip>);
            if (combined.deploymentMode === 'FLUX_GITOPS' && combined.sourceStatus) {
              const isReady = combined.sourceStatus?.toLowerCase() === 'true' || combined.sourceStatus?.toLowerCase() === 'ready';
              extraTags.push(<Tooltip key="repo" title={combined.sourceMessage || 'HelmRepository status'}><Tag color={isReady ? 'green' : 'orange'}>Repo {combined.sourceStatus}</Tag></Tooltip>);
            }
            if (combined.gitPrUrl) {
              const prColor = combined.gitPrState === 'merged' ? 'green' : combined.gitPrState === 'closed' ? 'red' : 'purple';
              extraTags.push(<Tooltip key="pr" title={combined.gitPrUrl}><Tag color={prColor}><a href={combined.gitPrUrl} target="_blank" rel="noreferrer" style={{ color: 'inherit' }}>PR {combined.gitPrNumber || ''}{combined.gitPrState ? ` (${combined.gitPrState})` : ''}</a></Tag></Tooltip>);
            } else if (combined.gitPrState) {
              const prColor = combined.gitPrState === 'merged' ? 'green' : combined.gitPrState === 'closed' ? 'red' : 'purple';
              extraTags.push(<Tag key="prstate" color={prColor}>PR {combined.gitPrState}</Tag>);
            }

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
                {extraTags.length > 0 && (
                  <Space size={2} wrap>
                    {extraTags[0]}
                    {extraTags.length > 1 && (
                      <Tooltip title={<Space direction="vertical" size={2}>{extraTags.slice(1)}</Space>}>
                        <Tag style={{ cursor: 'pointer' }}>+{extraTags.length - 1} more</Tag>
                      </Tooltip>
                    )}
                  </Space>
                )}
              </Space>
            );
          } },
        { title: 'TLS', key: 'tls', width: 140, render: (_: any, r: HelmRelease) => renderTlsBadges(r) },
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
                <Title level={2}>Releases</Title>
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
              title={`Revision history — ${historyModalRelease?.namespace || ''}/${historyModalRelease?.name || ''}`}
              open={!!historyModalRelease}
              onCancel={() => setHistoryModalRelease(null)}
              footer={<Button onClick={() => setHistoryModalRelease(null)}>Close</Button>}
              width={820}
            >
              {historyError ? (
                <Text type="danger">{historyError}</Text>
              ) : (
                <Table
                  size="small"
                  rowKey="revision"
                  loading={historyLoading}
                  dataSource={historyEntries}
                  pagination={{ pageSize: 10, showSizeChanger: false, hideOnSinglePage: true }}
                  columns={[
                    { title: 'Rev', dataIndex: 'revision', key: 'revision', width: 60 },
                    { title: 'Chart', dataIndex: 'chart', key: 'chart' },
                    { title: 'App', dataIndex: 'app_version', key: 'app_version', width: 100 },
                    {
                      title: 'Status', dataIndex: 'status', key: 'status', width: 110,
                      render: (s: string) => {
                        const color = s === 'deployed' ? 'green'
                          : s === 'failed' ? 'red'
                          : s === 'superseded' ? 'default'
                          : 'blue';
                        return <Tag color={color}>{s || 'unknown'}</Tag>;
                      }
                    },
                    {
                      title: 'Updated', dataIndex: 'updated', key: 'updated', width: 170,
                      render: (v: string) => v ? new Date(v).toLocaleString() : '—'
                    },
                    { title: 'Description', dataIndex: 'description', key: 'description' },
                    {
                      title: '', key: 'actions', width: 160,
                      render: (_: any, entry: HelmHistoryEntry) => {
                        // Top revision is current deployment — disable rollback to self.
                        const isCurrent = entry.status === 'deployed';
                        if (isCurrent) {
                          return <Tag color="green">Current</Tag>;
                        }
                        return (
                          <Button
                            danger
                            size="small"
                            icon={<RollbackOutlined />}
                            loading={rollingBackRevision === entry.revision}
                            onClick={() => {
                              if (!historyModalRelease) return;
                              Modal.confirm({
                                title: `Roll back to revision ${entry.revision}?`,
                                content: (
                                  <Space direction="vertical" size={4}>
                                    <Text>Chart: <b>{entry.chart || '—'}</b></Text>
                                    <Text>App version: {entry.app_version || '—'}</Text>
                                    <Text>Status: {entry.status || '—'}</Text>
                                    <Text type="secondary">{entry.description || ''}</Text>
                                  </Space>
                                ),
                                okText: 'Rollback',
                                okButtonProps: { danger: true },
                                cancelText: 'Cancel',
                                onOk: () => performRollbackToRevision(historyModalRelease, entry.revision)
                              });
                            }}
                          >
                            Rollback to {entry.revision}
                          </Button>
                        );
                      }
                    }
                  ]}
                />
              )}
            </Modal>

            <Modal
              title="Service catalog"
              open={isInstallPickerOpen}
              onCancel={() => setIsInstallPickerOpen(false)}
              footer={null}
              width={700}
            >
              <Row gutter={[12, 12]} style={{ marginTop: 8 }}>
                {Object.keys(serviceDefinitions).length === 0 && (
                  <Col span={24}>
                    <Typography.Text type="secondary">No services available.</Typography.Text>
                  </Col>
                )}
                {Object.keys(serviceDefinitions).map((key) => {
                  const svc = serviceDefinitions[key];
                  const icon = SERVICE_ICONS[key.toUpperCase()];
                  return (
                    <Col key={key} xs={24} sm={12}>
                      <div
                        role="button"
                        tabIndex={0}
                        className="catalog-card"
                        onClick={() => { setIsInstallPickerOpen(false); navigate(`/services/${key}`); }}
                        onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { setIsInstallPickerOpen(false); navigate(`/services/${key}`); } }}
                      >
                        <div style={{ width: 44, height: 44, flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f5f7fa', borderRadius: 8 }}>
                          {icon
                            ? <img src={icon} alt="" aria-hidden="true" style={{ width: 28, height: 28, objectFit: 'contain' }} />
                            : <span style={{ fontSize: 20, fontWeight: 700, color: '#1677ff' }}>{(svc?.label || key).charAt(0)}</span>
                          }
                        </div>
                        <div style={{ flex: 1, minWidth: 0 }}>
                          <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, marginBottom: 3 }}>
                            <span style={{ fontWeight: 600, fontSize: 14, color: '#1a1a1a' }}>{svc?.label || key}</span>
                            {svc?.version && <span style={{ fontSize: 11, color: '#8c8c8c' }}>v{svc.version}</span>}
                          </div>
                          {svc?.description && (
                            <span style={{ fontSize: 12, color: '#595959', lineHeight: '1.5', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
                              {svc.description}
                            </span>
                          )}
                        </div>
                      </div>
                    </Col>
                  );
                })}
              </Row>
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
