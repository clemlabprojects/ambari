// ui/src/components/layout/AppLayout.tsx
import React, { useEffect } from 'react';
import { Layout, Menu, Space, Spin, Tag, Tooltip, Progress, Alert, Button, Card, Badge, Breadcrumb } from 'antd';
import { NavLink, useLocation } from 'react-router-dom';
import {
  CodeSandboxOutlined,
  SettingOutlined,
  DashboardOutlined,
  CloudServerOutlined,
  PoweroffOutlined,
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
  console.log('DEBUG: Current location:', location.pathname);
  // const { permissions, loading } = usePermissions();
  const { status, stats, error, fetchData } = useClusterStatus();
  const [opsOpen, setOpsOpen] = React.useState(false);
  const [opsCount, setOpsCount] = React.useState<number>(0);
  console.log('DEBUG: AppLayout - Cluster nodes in the stats variable:', stats);
  console.log('DEBUG: AppLayout - Cluster ready nodes:', stats?.nodes.ready);
  console.log('DEBUG: Cluster status:', status);

  useEffect(() => {
    // Ambari sets "contribview" on <body> when rendering a view; that layout is meant
    // for stripped shells and can cause bounce when the top header toggles visibility.
    // Remove it for this view so the normal shell sizing rules apply.
    try {
      const parentDoc = window?.parent?.document;
      if (!parentDoc || parentDoc === document) return;
      const body = parentDoc.body;
      const hadClass = body.classList.contains('contribview');
      if (hadClass) {
        body.classList.remove('contribview');
        body.classList.add('k8s-view-stabilized');
      }
      return () => {
        body.classList.remove('k8s-view-stabilized');
      };
    } catch (e) {
      // ignore cross-origin issues
      return;
    }
  }, []);

  React.useEffect(() => {
    const loadOpsCount = async () => {
      try {
        const cmds = await listCommands(10, 0);
        const running = (cmds || []).filter((c: any) => c.state === 'RUNNING' || c.state === 'PENDING').length;
        setOpsCount(running);
      } catch {
        setOpsCount(0);
      }
    };
    void loadOpsCount();
    if (!opsOpen) void loadOpsCount();
  }, [opsOpen]);

  const breadcrumbMap: Record<string, string> = {
    '/': 'Dashboard',
    '/helm': 'Helm Charts',
    '/repositories': 'Helm Repositories',
    '/nodes': 'Nodes',
    '/workloads': 'Workloads',
    '/global-security': 'Global Security',
    '/managed-configs': 'Profiles',
    '/configuration': 'Settings',
  };

  const breadcrumbItems = React.useMemo(() => {
    const path = location.pathname;
    const label = breadcrumbMap[path] || 'View';
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

  const renderStatCard = (label: string, used?: number, total?: number, unitLabel?: string, successWhenEqual?: boolean) => {
    const unavailable = used == null || used < 0 || total == null || total <= 0;
    const pct = unavailable ? 0 : Math.min(100, Math.round((used / total) * 100));
    const status = successWhenEqual && used === total ? 'success' : undefined;
    return (
      <Card size="small" className="stat-card" bordered>
        <div className="stat-card-header">
          <span className="stat-card-label">{label}</span>
          {!unavailable && (
            <Tag color={status === 'success' ? 'green' : 'blue'}>
              {`${pct}%`}
            </Tag>
          )}
        </div>
        {!unavailable && <Progress percent={pct} size="small" status={status as any} showInfo={false} />}
        <div className="stat-card-value">
          {unavailable ? 'N/A' : `${used.toFixed(1)} / ${unitLabel ? `${total} ${unitLabel}` : total}`}
        </div>
      </Card>
    );
  };

  const isConfigPage = location.pathname.startsWith('/configuration');

  if (isConfigPage) {
    return (
      <div className="app-layout">
        <div className="page-content">
          {children}
        </div>
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
            <Space size={8} style={{ marginLeft: '12px' }}>
              {stats?.nodes && <Tag icon={<CloudServerOutlined />} color="default">{`${stats.nodes.ready}/${stats.nodes.total} nodes`}</Tag>}
              {stats?.pods && <Tag color="default">{`${stats.pods.used}/${stats.pods.total} pods`}</Tag>}
              {typeof stats?.cpu?.used === 'number' && <Tag color="default">{`${stats.cpu.used.toFixed(1)}/${stats.cpu.total} cores`}</Tag>}
              {typeof stats?.memory?.used === 'number' && <Tag color="default">{`${stats.memory.used.toFixed(1)}/${stats.memory.total} GiB`}</Tag>}
              <Button size="small" onClick={() => setOpsOpen(true)}>
                Background Ops {opsCount > 0 && <Badge count={opsCount} offset={[8, -2]} />}
              </Button>
            </Space>
        </div>
      </Header>
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
                            <Button size="small" type="ghost" onClick={fetchData}>
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
        open={opsOpen}
        onClose={() => setOpsOpen(false)}
      />
    </Layout>
  );
};

export default AppLayout;
