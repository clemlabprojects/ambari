// ui/src/components/layout/AppLayout.tsx
import React from 'react';
import { Layout, Menu, Space, Spin, Tag, Tooltip, Progress, Alert, Button } from 'antd';
import { NavLink, useLocation } from 'react-router-dom';
import { CodeSandboxOutlined, SettingOutlined, DashboardOutlined, CloudServerOutlined, PoweroffOutlined } from '@ant-design/icons';
import { usePermissions } from '../../hooks/usePermissions';
import { useClusterStatus } from '../../context/ClusterStatusContext';
import './AppLayout.css';

const { Header, Content } = Layout;

const AppLayout: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const location = useLocation();
  const { permissions, loading } = usePermissions();
  const { status, stats, error, fetchData } = useClusterStatus();

  if (loading || !permissions) {
    return <div className="app-loading"><Spin size="large" /></div>;
  }

  const menuItems = [
    { key: '/', icon: <DashboardOutlined />, label: <NavLink to="/">Dashboard</NavLink> },
    { key: '/nodes', icon: <CloudServerOutlined />, label: <NavLink to="/nodes">Noeuds</NavLink> },
    { key: '/helm', icon: <CodeSandboxOutlined />, label: <NavLink to="/helm">Charts Helm</NavLink> },
  ];

  if (permissions.canConfigure) {
    menuItems.push({
      key: '/configuration',
      icon: <SettingOutlined />,
      label: <NavLink to="/configuration">Configuration</NavLink>,
    });
  }

  return (
    <Layout className="app-layout">
      <Header className="app-header">
        <div className="header-left">
          <div className="logo">
            <PoweroffOutlined style={{color: '#1677ff', fontSize: '28px'}}/>
            <h1>Ambari K8S View</h1>
          </div>
          <Menu
            theme="light"
            mode="horizontal"
            selectedKeys={[location.pathname]}
            items={menuItems}
            className="header-menu"
          />
        </div>
        <div className="header-right">
            <Tag color="blue">{permissions.role}</Tag>
            {status === 'connected' && <Tag color="green">CONNECTÉ</Tag>}
            {status === 'error' && <Tag color="red">ERREUR DE CONNEXION</Tag>}
        </div>
      </Header>
      <Content className="app-content">
        {status === 'connected' && stats && (
          <div className="global-stats-bar">
              {/* ... Barre de statistiques ... */}
          </div>
        )}
        
        {/* NOUVELLE GESTION D'ERREUR */}
        {status === 'error' && (
            <Alert
                message="Impossible de se connecter au cluster Kubernetes"
                description={error || "Une erreur inconnue est survenue."}
                type="error"
                showIcon
                action={
                    <Space>
                        <Button size="small" type="ghost" onClick={fetchData}>
                            Réessayer
                        </Button>
                        <NavLink to="/configuration">
                            <Button size="small" type="primary">
                                Reconfigurer
                            </Button>
                        </NavLink>
                    </Space>
                }
                style={{ marginBottom: '24px' }}
            />
        )}

        <div className="page-content">
            {children}
        </div>
      </Content>
    </Layout>
  );
};

export default AppLayout;
