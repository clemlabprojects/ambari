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
 */

// ui/src/components/layout/AppLayout.tsx

import React, { useEffect } from 'react';
import { Layout, Menu, Tag, Alert, Button, Space, Breadcrumb } from 'antd';
import { NavLink, useLocation } from 'react-router-dom';
import {
  CodeOutlined,
  HistoryOutlined,
  SettingOutlined,
  ThunderboltOutlined,
  DatabaseOutlined,
} from '@ant-design/icons';
import { useAssistantStatus } from '../../context/AssistantStatusContext';
import './AppLayout.css';

const { Header, Content } = Layout;

const AppLayout: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const location = useLocation();
  const { status, health, error, refresh } = useAssistantStatus();

  // Stabilise layout inside Ambari iframe (same trick as k8s view)
  useEffect(() => {
    try {
      const parentDocument = window?.parent?.document;
      if (!parentDocument || parentDocument === document) return;
      const parentBody = parentDocument.body;
      const parentHtml = parentDocument.documentElement;
      const hadContribClass = parentBody.classList.contains('contribview');
      if (hadContribClass) {
        parentBody.classList.remove('contribview');
        parentBody.classList.add('sql-view-stabilized');
      }
      parentHtml.classList.add('sql-view-no-scroll');
      parentBody.classList.add('sql-view-no-scroll');
      return () => {
        parentHtml.classList.remove('sql-view-no-scroll');
        parentBody.classList.remove('sql-view-no-scroll');
        parentBody.classList.remove('sql-view-stabilized');
      };
    } catch {
      return;
    }
  }, []);

  const menuItems = [
    {
      key: '/',
      icon: <CodeOutlined />,
      label: <NavLink to="/">Query</NavLink>,
    },
    {
      key: '/schema',
      icon: <DatabaseOutlined />,
      label: <NavLink to="/schema">Schema</NavLink>,
    },
    {
      key: '/history',
      icon: <HistoryOutlined />,
      label: <NavLink to="/history">History</NavLink>,
    },
    {
      key: '/configuration',
      icon: <SettingOutlined />,
      label: <NavLink to="/configuration">Settings</NavLink>,
    },
  ];

  const breadcrumbMap: Record<string, string> = {
    '/': 'Query',
    '/schema': 'Schema Browser',
    '/history': 'Query History',
    '/configuration': 'Settings',
  };

  const breadcrumbItems = React.useMemo(() => {
    const label = breadcrumbMap[location.pathname] ?? 'SQL Assistant';
    return [
      { title: <NavLink to="/">SQL Assistant</NavLink> },
      { title: label },
    ];
  }, [location.pathname]);

  const llmStatus = health?.components?.llm;
  const llmOk = llmStatus?.status === 'ok';

  return (
    <Layout className="app-layout">
      <Header className="app-header">
        <div className="header-left">
          <div className="logo">
            <ThunderboltOutlined style={{ fontSize: 20, color: '#1677ff' }} />
            <span className="logo-text">SQL Assistant</span>
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
          {status === 'ready' && (
            <Space size={6}>
              {llmOk
                ? <Tag color="blue">{health?.components?.llm ? `LLM: ${health.components.llm.detail.split('.')[0]}` : 'LLM'}</Tag>
                : <Tag color="orange">LLM: degraded</Tag>
              }
              <Tag color="green">CONNECTED</Tag>
            </Space>
          )}
          {status === 'error' && <Tag color="red">SERVICE UNREACHABLE</Tag>}
          {status === 'loading' && <Tag>Connecting…</Tag>}
        </div>
      </Header>

      <Content className="app-content">
        <div className="content-shell">
          <div className="main-scroll">
            <Breadcrumb className="app-breadcrumb" items={breadcrumbItems} />

            {status === 'error' && (
              <Alert
                message="SQL Assistant service is not reachable"
                description={
                  error
                    ? `${error}. Ensure the semantic service is running and the URL is correct in Settings.`
                    : 'Check that the Python semantic service is running.'
                }
                type="error"
                showIcon
                action={
                  <Space>
                    <Button size="small" onClick={() => { void refresh(); }}>Retry</Button>
                    <NavLink to="/configuration">
                      <Button size="small" type="primary">Settings</Button>
                    </NavLink>
                  </Space>
                }
                style={{ marginBottom: 16 }}
              />
            )}

            <div className="page-content">{children}</div>
          </div>
        </div>
      </Content>
    </Layout>
  );
};

export default AppLayout;
