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

// ui/src/components/layout/AppLayout.tsx
import React from 'react';
import { Layout, Menu, Space, Spin, Tag, Alert, Button, Breadcrumb } from 'antd';
import { NavLink, useLocation } from 'react-router-dom';
import {
  CodeSandboxOutlined,
  SettingOutlined,
  DashboardOutlined,
  CloudServerOutlined,
  FileTextOutlined,
  LockOutlined
} from '@ant-design/icons';
// import { usePermissions } from '../../hooks/usePermissions';
import { useClusterStatus } from '../../context/ClusterStatusContext';
import BackgroundOperationsModal from '../common/BackgroundOperationsModal';
import { listCommands } from '../../api/client';
import './AppLayout.css';

const { Header, Content } = Layout;

const AppLayout: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const location = useLocation();
  const { status, stats: clusterStats, error, fetchData } = useClusterStatus();
  const [isOperationsModalOpen, setIsOperationsModalOpen] = React.useState(false);
  const [operationsCount, setOperationsCount] = React.useState<number>(0);
  // No theme toggle: default light palette

  /**
   * Produce a human-readable usage label for the stats bar. Handles missing
   * values gracefully (showing N/A) and formats numbers to a fixed precision
   * to keep the bar compact. This helper keeps UI formatting logic isolated
   * from render JSX, which makes the render branch easier to skim.
   * Inputs:
   * - used: current usage, may be undefined or -1 when metrics are unavailable.
   * - total: overall capacity, may be undefined if metrics could not be fetched.
   * - unit: optional unit suffix (e.g., "cores", "GiB") for readability.
   * - decimals: precision to display, defaults to one decimal place to
   *   balance readability and noise in the slim bar.
   * Outputs:
   * - String formatted as "<used>/<total> unit" or "N/A/<total> unit"
   *   when only totals are known.
   */
  const formatUsageLabel = (used?: number, total?: number, unit?: string, decimals = 1) => {
    const hasUsedValue = typeof used === 'number' && used >= 0;
    const hasTotalValue = typeof total === 'number' && total >= 0;
    if (hasUsedValue && hasTotalValue) {
      return `avg ${used!.toFixed(decimals)}/${total!.toFixed(decimals)} ${unit || ''}`.trim();
    }
    if (hasTotalValue) {
      return `N/A/${total!.toFixed(decimals)} ${unit || ''}`.trim();
    }
    return `N/A ${unit || ''}`.trim();
  };


  React.useEffect(() => {
    /**
     * Poll background operations to surface a small badge in the header.
     * We request only a few items to keep API load minimal and filter for
     * RUNNING/PENDING states. Errors are logged silently because this badge
     * is purely informational. When the modal closes, we refresh once more to
     * reflect any completed tasks without adding a dedicated polling interval.
     */
    const loadOperationsCount = async () => {
      try {
        const commands = await listCommands(10, 0);
        const runningOrPending = (commands || []).filter((command: any) =>
          command.state === 'RUNNING' || command.state === 'PENDING').length;
        setOperationsCount(runningOrPending);
      } catch (err) {
        console.error('Failed to load background operations count:', err);
        setOperationsCount(0);
      }
    };
    void loadOperationsCount();
    if (!isOperationsModalOpen) void loadOperationsCount();
  }, [isOperationsModalOpen]);

  const breadcrumbMap: Record<string, string> = {
    '/': 'Dashboard',
    '/helm': 'Helm Charts',
    '/repositories': 'Helm Repositories',
    '/git-repositories': 'Git Repositories',
    '/nodes': 'Nodes',
    '/workloads': 'Workloads',
    '/global-security': 'Global Security',
    '/managed-configs': 'Profiles',
    '/configuration': 'Settings',
  };

  const breadcrumbItems = React.useMemo(() => {
    /**
     * Resolve user-friendly labels for the current route to show a simple
     * breadcrumb trail. Centralizing the map keeps navigation text consistent
     * across the app. Memoization ensures this is recomputed only when the
     * path changes, keeping renders light.
     */
    const currentPath = location.pathname;
    const label = breadcrumbMap[currentPath] || 'View';
    return [
      { title: <NavLink to="/">Home</NavLink> },
      { title: label },
    ];
  }, [location.pathname]);
  
  if (status === 'loading') {
    return <div className="app-loading"><Spin size="large" /></div>;
  }

  const menuItems = [
    { key: '/', icon: <DashboardOutlined />, label: <NavLink to="/">Dashboard</NavLink> },
    { key: '/helm', icon: <CodeSandboxOutlined />, label: <NavLink to="/helm">Helm Charts</NavLink> },
    { key: '/repositories', icon: <CodeSandboxOutlined />, label: <NavLink to="/repositories">Helm Repositories</NavLink> },
    { key: '/git-repositories', icon: <CodeSandboxOutlined />, label: <NavLink to="/git-repositories">Git Repositories</NavLink> },
    { key: '/nodes', icon: <CloudServerOutlined />, label: <NavLink to="/nodes">Nodes</NavLink> },
    { key: '/workloads', icon: <CloudServerOutlined />, label: <NavLink to="/workloads">Workloads</NavLink> },
    {
      key: '/config-group',
      icon: <SettingOutlined />,
      label: 'Configuration',
      children: [
        { key: '/global-security', icon: <LockOutlined />, label: <NavLink to="/global-security">Security</NavLink> },
        { key: '/managed-configs', icon: <FileTextOutlined />, label: <NavLink to="/managed-configs">Profiles</NavLink> },
        { key: '/configuration', icon: <SettingOutlined />, label: <NavLink to="/configuration">Settings</NavLink> },
      ],
    },
  ];

  const isConfigPage = location.pathname.startsWith('/configuration');

  if (isConfigPage) {
    return (
      <div className="app-layout" style={{ overflowY: 'auto' }}>
        {children}
      </div>
    );
  }

  return (
    <Layout className="app-layout">
      <Header className="app-header">
        <div className="header-left">
          <Menu
            theme="light"
            mode="horizontal"
            selectedKeys={[location.pathname]}
            items={menuItems}
            className="header-menu"
          />
        </div>
        <div className="header-right">
            {/* <Tag color="blue">{permissions.role}</Tag> */}
            {status === 'connected' && <Tag color="green">CONNECTED</Tag>}
            {status === 'error' && <Tag color="red">CONNECTION ERROR</Tag>}
        </div>
      </Header>
      {/* Slim stats bar UNDER navigation menu to prevent overflow */}
      {status === 'connected' && clusterStats && (
        <div
          className="stats-bar"
          style={{
            background: 'var(--surface-muted)',
            borderBottom: '1px solid var(--border)',
            padding: '4px 16px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            gap: '6px',
            fontSize: '11px',
            height: '28px',
            flexShrink: 0,
            position: 'relative',
            zIndex: 10,
            overflow: 'hidden',
            whiteSpace: 'nowrap',
            marginTop: '5px',
            borderRadius: 14,
            boxShadow: 'none',
          }}
        >
          <Space size={6} style={{ marginLeft: 'auto' }}>
            <Button size="small" onClick={() => setIsOperationsModalOpen(true)}>
              Background Ops
              {operationsCount > 0 && (
                <span style={{
                  marginLeft: 5,
                  background: '#1677ff',
                  color: '#fff',
                  borderRadius: 10,
                  padding: '0 5px',
                  fontSize: 10,
                  lineHeight: '16px',
                  display: 'inline-block',
                  verticalAlign: 'middle',
                }}>
                  {operationsCount}
                </span>
              )}
            </Button>
            {clusterStats?.nodes && (
              <Tag
                icon={<CloudServerOutlined />}
                color="default"
                style={{ margin: 0, fontSize: '11px', lineHeight: '20px' }}
              >{`${clusterStats.nodes.used || 0}/${clusterStats.nodes.total} nodes`}</Tag>
            )}
            {clusterStats?.pods && (
              <Tag
                color="default"
                style={{ margin: 0, fontSize: '11px', lineHeight: '20px' }}
              >{`${clusterStats.pods.used}/${clusterStats.pods.total} pods`}</Tag>
            )}
            {clusterStats?.cpu && (
              <Tag
                color="default"
                style={{ margin: 0, fontSize: '11px', lineHeight: '20px' }}
              >
                {formatUsageLabel(clusterStats.cpu.used, clusterStats.cpu.total, 'cores')}
              </Tag>
            )}
            {clusterStats?.memory && (
              <Tag
                color="default"
                style={{ margin: 0, fontSize: '11px', lineHeight: '20px' }}
              >
                {formatUsageLabel(clusterStats.memory.used, clusterStats.memory.total, 'GiB')}
              </Tag>
            )}
          </Space>
        </div>
      )}
      <Content className="app-content">
        <div className="content-shell">
          <div className="main-scroll">
            <Breadcrumb className="app-breadcrumb" items={breadcrumbItems} />
            {status === 'error' && (
                <Alert
                    message="Unable to connect to Kubernetes cluster"
                    description={error || "An unknown error occurred."}
                    type="error"
                    showIcon
                    action={
                        <Space>
                            <Button size="small" type="default" onClick={fetchData}>
                                Retry
                            </Button>
                            <NavLink to="/configuration">
                                <Button size="small" type="primary">
                                    Reconfigure
                                </Button>
                            </NavLink>
                        </Space>
                    }
                    style={{ marginBottom: '16px' }}
                />
            )}

            <div className="page-content">
                {children}
            </div>
          </div>
        </div>
      </Content>
      <BackgroundOperationsModal
        open={isOperationsModalOpen}
        onClose={() => setIsOperationsModalOpen(false)}
      />
    </Layout>
  );
};

export default AppLayout;
