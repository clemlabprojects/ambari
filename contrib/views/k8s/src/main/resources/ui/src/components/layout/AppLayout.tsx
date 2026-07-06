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
import { Layout, Menu, Tag, Alert, Button, Breadcrumb, Tooltip, Space, Badge, Skeleton } from 'antd';
import { NavLink, useLocation } from 'react-router-dom';
import {
  BellOutlined,
  ContainerOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  DeploymentUnitOutlined,
  HomeOutlined,
  LockOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  NodeIndexOutlined,
  ProfileOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  ShopOutlined,
  ThunderboltOutlined,
  ApiOutlined,
  BulbOutlined,
  BulbFilled,
} from '@ant-design/icons';
import { useClusterStatus } from '../../context/ClusterStatusContext';
import { useThemeMode } from '../../context/ThemeModeContext';
import BackgroundOperationsModal from '../common/BackgroundOperationsModal';
import NamespaceSelector from './NamespaceSelector';
import { listCommands, getClusterCapabilities } from '../../api/client';
import './AppLayout.css';

const { Header, Content, Sider } = Layout;

/** Storage key for the user's sidebar collapse preference. */
const SIDEBAR_COLLAPSED_KEY = 'kdps.sidebar.collapsed';

/** Path -> human label, used both by the menu and the breadcrumb so the two
 *  stay in sync. Update this map when adding a new page. */
const ROUTE_LABELS: Record<string, string> = {
  '/': 'Dashboard',
  '/catalog': 'Service Catalog',
  '/helm': 'Releases',
  '/repositories': 'Helm Repositories',
  '/git-repositories': 'Git Repositories',
  '/nodes': 'Nodes',
  '/contexts': 'Platform Contexts',
  '/workloads': 'Workloads',
  '/global-security': 'Security Profile',
  '/certificate-authorities': 'Certificate Authorities',
  '/truststores': 'Truststores',
  '/operators': 'Operators',
  '/managed-configs': 'Config Profiles',
  '/configuration': 'Cluster Settings',
};

/** Section label lookup so breadcrumb shows "Security › Certificate Authorities"
 *  not just "Certificate Authorities". Routes not in this map get just their
 *  leaf label. */
const ROUTE_SECTIONS: Record<string, string> = {
  '/catalog': 'Catalog',
  '/helm': 'Releases',
  '/repositories': 'Catalog',
  '/git-repositories': 'Catalog',
  '/workloads': 'Workloads',
  '/global-security': 'Security',
  '/certificate-authorities': 'Security',
  '/truststores': 'Security',
  '/operators': 'Operators',
  '/nodes': 'Administration',
  '/contexts': 'Administration',
  '/managed-configs': 'Administration',
  '/configuration': 'Administration',
};

const AppLayout: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const location = useLocation();
  const { status, error, fetchData } = useClusterStatus();
  const { mode, toggle: toggleTheme } = useThemeMode();

  // Detected target platform (kubernetes | openshift) from the same /cluster/capabilities probe
  // that gates the OpenShift-only deploy fields. Surfaced in the brand so the operator can see at
  // a glance which platform KDPS detected — and so a mis-detection is immediately visible.
  const [platform, setPlatform] = React.useState<string | null>(null);
  React.useEffect(() => {
    let alive = true;
    getClusterCapabilities()
      .then(c => { if (alive) setPlatform(c?.platform ?? null); })
      .catch(() => { if (alive) setPlatform(null); });
    return () => { alive = false; };
  }, []);

  // Collapse state survives reloads. Read once on mount; ignore parse errors.
  const [collapsed, setCollapsed] = React.useState<boolean>(() => {
    try { return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1'; } catch { return false; }
  });
  const toggleCollapsed = React.useCallback(() => {
    setCollapsed(c => {
      const next = !c;
      try { localStorage.setItem(SIDEBAR_COLLAPSED_KEY, next ? '1' : '0'); } catch { /* ignore */ }
      return next;
    });
  }, []);

  const [isOperationsModalOpen, setIsOperationsModalOpen] = React.useState(false);
  const [operationsCount, setOperationsCount] = React.useState<number>(0);

  React.useEffect(() => {
    // Light poll of background operations so the bell shows a live count.
    // We refresh on modal close to reflect just-finished work without a
    // dedicated interval (UI is generally low-traffic).
    const loadOperationsCount = async () => {
      try {
        const commands = await listCommands(20, 0);
        const runningOrPending = (commands || []).filter((command: any) =>
          command.state === 'RUNNING' || command.state === 'PENDING').length;
        setOperationsCount(runningOrPending);
      } catch {
        setOperationsCount(0);
      }
    };
    void loadOperationsCount();
    const id = window.setInterval(loadOperationsCount, 15000);
    return () => window.clearInterval(id);
  }, [isOperationsModalOpen]);

  /**
   * Build the sidebar menu. We use sub-menus (groups) to keep the top-level
   * list short (<= 8) while still surfacing every page directly under one
   * click. Order is intentional: top of nav is what most users hit on every
   * session (Dashboard, Catalog, Releases), bottom is admin / one-off setup.
   */
  // Flat, always-open sections (antd Menu type:'group') instead of collapsible
  // sub-menus — matches the control-plane design: every destination visible at a
  // glance under an uppercase section label, no expand/collapse.
  const menuItems = React.useMemo(() => ([
    { key: '/', icon: <HomeOutlined />, label: <NavLink to="/">Dashboard</NavLink> },
    {
      type: 'group' as const, label: 'Catalog',
      children: [
        { key: '/catalog', icon: <ShopOutlined />, label: <NavLink to="/catalog">Service Catalog</NavLink> },
        { key: '/helm', icon: <DeploymentUnitOutlined />, label: <NavLink to="/helm">Releases</NavLink> },
        { key: '/workloads', icon: <NodeIndexOutlined />, label: <NavLink to="/workloads">Workloads</NavLink> },
        { key: '/repositories', icon: <ContainerOutlined />, label: <NavLink to="/repositories">Helm Repositories</NavLink> },
        { key: '/git-repositories', icon: <ContainerOutlined />, label: <NavLink to="/git-repositories">Git Repositories</NavLink> },
      ],
    },
    {
      type: 'group' as const, label: 'Security',
      children: [
        { key: '/global-security', icon: <LockOutlined />, label: <NavLink to="/global-security">Security Profile</NavLink> },
        { key: '/certificate-authorities', icon: <SafetyCertificateOutlined />, label: <NavLink to="/certificate-authorities">Certificate Authorities</NavLink> },
        { key: '/truststores', icon: <DatabaseOutlined />, label: <NavLink to="/truststores">Truststores</NavLink> },
      ],
    },
    {
      type: 'group' as const, label: 'Operators',
      children: [
        { key: '/operators', icon: <ThunderboltOutlined />, label: <NavLink to="/operators">Operators</NavLink> },
      ],
    },
    {
      type: 'group' as const, label: 'Administration',
      children: [
        { key: '/nodes', icon: <CloudServerOutlined />, label: <NavLink to="/nodes">Nodes</NavLink> },
        { key: '/contexts', icon: <ApiOutlined />, label: <NavLink to="/contexts">Platform Contexts</NavLink> },
        { key: '/managed-configs', icon: <ProfileOutlined />, label: <NavLink to="/managed-configs">Config Profiles</NavLink> },
        { key: '/configuration', icon: <SettingOutlined />, label: <NavLink to="/configuration">Cluster Settings</NavLink> },
      ],
    },
  ]), []);

  const breadcrumbItems = React.useMemo(() => {
    const currentPath = location.pathname;
    const label = ROUTE_LABELS[currentPath] || 'View';
    const section = ROUTE_SECTIONS[currentPath];
    const items: { title: React.ReactNode }[] = [{ title: <NavLink to="/">Home</NavLink> }];
    if (section) items.push({ title: section });
    if (label && label !== section) items.push({ title: label });
    return items;
  }, [location.pathname]);

  const isConfigPage = location.pathname.startsWith('/configuration');
  if (isConfigPage) {
    // First-run configuration screen renders without the chrome so a
    // disconnected view doesn't expose a broken sidebar.
    return <div className="app-layout" style={{ overflowY: 'auto' }}>{children}</div>;
  }

  return (
    <Layout className="app-layout">
      <Sider
        className="app-sider"
        collapsible
        collapsed={collapsed}
        trigger={null}
        width={232}
        collapsedWidth={64}
      >
        <div className="app-sider-brand">
          <div className="app-sider-brand-logo">K8S</div>
          {!collapsed && (
            <div className="app-sider-brand-text">
              <div className="brand-title">KDPS</div>
              {/* Detected platform moved to the header (next to the connection
                  status) so it is visible on every page and the brand block
                  stays a clean two lines inside the fixed-height header. */}
              <div className="brand-subtitle">Data Platform</div>
            </div>
          )}
        </div>
        <div className="app-sider-menu-wrap">
          <Menu
            mode="inline"
            selectedKeys={[location.pathname]}
            items={menuItems as any}
            inlineIndent={18}
          />
        </div>
        {!collapsed && (
          <div className="app-sider-footer">
            <span>v1.0.0</span>
            {status === 'connected' && <Tag color="green" style={{ margin: 0 }}>connected</Tag>}
            {status === 'error' && <Tag color="red" style={{ margin: 0 }}>error</Tag>}
          </div>
        )}
      </Sider>

      <Layout className="app-main">
        <Header className="app-header">
          <div className="header-left">
            <Tooltip title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}>
              <Button
                className="header-collapse-btn"
                icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
                onClick={toggleCollapsed}
                aria-label="toggle sidebar"
              />
            </Tooltip>
            <Breadcrumb className="app-breadcrumb" items={breadcrumbItems} />
          </div>
          <div className="header-right">
            <Space size={8} align="center">
              <NamespaceSelector />
              {platform && (
                <Tooltip title="Detected target platform">
                  <Tag
                    color={platform === 'openshift' ? 'gold' : 'blue'}
                    style={{ margin: 0 }}
                  >
                    {platform === 'openshift' ? 'OpenShift' : 'Kubernetes'}
                  </Tag>
                </Tooltip>
              )}
              {status === 'connected' && <Tag color="green" style={{ margin: 0 }}><span className="kdps-live-dot" />CONNECTED</Tag>}
              {status === 'error' && <Tag color="red" style={{ margin: 0 }}>CONNECTION ERROR</Tag>}
              <Tooltip title={mode === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}>
                <Button
                  icon={mode === 'dark' ? <BulbFilled /> : <BulbOutlined />}
                  onClick={toggleTheme}
                  className="action-button"
                  aria-label="toggle color mode"
                />
              </Tooltip>
              {/* Global, always-accessible entry to background operations. Labeled
                  (not a bare bell) so it reads as the operations center on every
                  page; the count badge surfaces in-flight work at a glance. */}
              <Badge count={operationsCount} size="small" offset={[-2, 2]}>
                <Button
                  icon={<BellOutlined />}
                  onClick={() => setIsOperationsModalOpen(true)}
                  className="action-button"
                  aria-label="background operations"
                >
                  Operations
                </Button>
              </Badge>
            </Space>
          </div>
        </Header>

        <Content className="app-content">
          <div className="main-scroll">
            {status === 'error' && (
              <Alert
                message="Unable to connect to Kubernetes cluster"
                description={error || 'An unknown error occurred.'}
                type="error"
                showIcon
                action={
                  <Space>
                    <Button size="small" onClick={fetchData}>Retry</Button>
                    <NavLink to="/configuration">
                      <Button size="small" type="primary">Reconfigure</Button>
                    </NavLink>
                  </Space>
                }
                style={{ marginBottom: 12 }}
              />
            )}
            {/* #6: render the branded shell (sider + header) immediately and
                show a content skeleton while the cluster-status probe resolves,
                instead of blocking the whole app behind a full-screen spinner.
                The view feels instant and the operator sees where they are. */}
            <div className="page-content">
              {status === 'loading'
                ? <Skeleton active paragraph={{ rows: 8 }} title style={{ padding: 8 }} />
                : children}
            </div>
          </div>
        </Content>
      </Layout>

      <BackgroundOperationsModal
        open={isOperationsModalOpen}
        onClose={() => setIsOperationsModalOpen(false)}
      />
    </Layout>
  );
};

export default AppLayout;
