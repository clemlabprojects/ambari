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

// ui/src/pages/ConfigurationPage.tsx

import React, { useEffect, useState } from 'react';
import {
  Card,
  Descriptions,
  Button,
  Typography,
  Tag,
  Space,
  Alert,
  Spin,
  Divider,
} from 'antd';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ReloadOutlined,
  LinkOutlined,
} from '@ant-design/icons';
import { getHealth, getConfig, getViewConfig } from '../api/client';
import type { HealthResponse, ServiceConfig, ViewConfig } from '../types';
import './Page.css';

const ConfigurationPage: React.FC = () => {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [config, setConfig] = useState<ServiceConfig | null>(null);
  const [viewConfig, setViewConfig] = useState<ViewConfig | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const [h, c, vc] = await Promise.allSettled([
        getHealth(),
        getConfig(),
        getViewConfig(),
      ]);
      if (h.status === 'fulfilled') setHealth(h.value);
      if (c.status === 'fulfilled') setConfig(c.value);
      if (vc.status === 'fulfilled') setViewConfig(vc.value);
      if (h.status === 'rejected') setError(String(h.reason));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const StatusIcon: React.FC<{ ok: boolean }> = ({ ok }) =>
    ok ? (
      <CheckCircleOutlined style={{ color: '#52c41a' }} />
    ) : (
      <CloseCircleOutlined style={{ color: '#ff4d4f' }} />
    );

  return (
    <div style={{ padding: 24, maxWidth: 800 }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: 16,
        }}
      >
        <Typography.Title level={4} style={{ margin: 0 }}>
          Settings &amp; Health
        </Typography.Title>
        <Button
          size="small"
          icon={<ReloadOutlined />}
          onClick={() => { void load(); }}
          loading={loading}
        >
          Refresh
        </Button>
      </div>

      {loading && !health && (
        <div style={{ textAlign: 'center', padding: 40 }}>
          <Spin tip="Probing services…" />
        </div>
      )}

      {error && !loading && (
        <Alert
          type="error"
          message="Cannot reach the SQL Assistant semantic service"
          description={
            <>
              <p style={{ margin: '4px 0' }}>{error}</p>
              <p style={{ margin: 0 }}>
                Ensure the Python service is running and the URL is configured
                correctly in Ambari view properties{' '}
                <strong>({ViewConfigurationService_PARAM_URL})</strong>.
              </p>
            </>
          }
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}

      {/* View configuration */}
      {viewConfig && (
        <Card
          title={
            <Space>
              <LinkOutlined />
              View Configuration
            </Space>
          }
          size="small"
          style={{ marginBottom: 16 }}
        >
          <Descriptions size="small" column={1}>
            <Descriptions.Item label="Semantic Service URL">
              <Typography.Text copyable code>
                {viewConfig.serviceUrl}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Configured">
              <Tag color={viewConfig.configured ? 'green' : 'orange'}>
                {viewConfig.configured ? 'Yes' : 'Using default (localhost:8090)'}
              </Tag>
            </Descriptions.Item>
          </Descriptions>
          {!viewConfig.configured && (
            <Alert
              type="warning"
              message='Set "Semantic Service URL" in Ambari → Views → SQL Assistant → Edit → sql.assistant.semantic.service.url'
              showIcon
              style={{ marginTop: 8 }}
              banner
            />
          )}
        </Card>
      )}

      {/* Adapter configuration */}
      {config && (
        <Card title="Adapter Configuration" size="small" style={{ marginBottom: 16 }}>
          <Descriptions size="small" column={2}>
            <Descriptions.Item label="LLM Adapter">
              <Tag color="blue">{config.llm_adapter}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="LLM Model">
              <Typography.Text code style={{ fontSize: 12 }}>
                {config.llm_model}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Catalog Adapter">
              <Tag color="purple">{config.catalog_adapter}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="Polaris URL">
              <Typography.Text code style={{ fontSize: 12 }}>
                {config.polaris_url}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Trino">
              <Typography.Text code style={{ fontSize: 12 }}>
                {config.trino_host}:{config.trino_port}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Hive">
              <Typography.Text code style={{ fontSize: 12 }}>
                {config.hive_host}:{config.hive_port}
              </Typography.Text>
            </Descriptions.Item>
          </Descriptions>
        </Card>
      )}

      {/* Health */}
      {health && (
        <Card
          title={
            <Space>
              {health.status === 'ok' ? (
                <CheckCircleOutlined style={{ color: '#52c41a' }} />
              ) : (
                <CloseCircleOutlined style={{ color: '#ff4d4f' }} />
              )}
              Component Health
              <Tag color={health.status === 'ok' ? 'green' : 'orange'}>
                {health.status.toUpperCase()}
              </Tag>
            </Space>
          }
          size="small"
        >
          {Object.entries(health.components).map(([name, comp]) => (
            <div key={name} style={{ marginBottom: 10 }}>
              <Space>
                <StatusIcon ok={comp.status === 'ok'} />
                <Typography.Text strong style={{ fontSize: 12 }}>
                  {name}
                </Typography.Text>
                <Tag
                  color={comp.status === 'ok' ? 'green' : 'red'}
                  style={{ fontSize: 11 }}
                >
                  {comp.status}
                </Tag>
              </Space>
              <div style={{ marginLeft: 20 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {comp.detail}
                </Typography.Text>
                {comp.available_models && comp.available_models.length > 0 && (
                  <div style={{ marginTop: 4 }}>
                    {comp.available_models.map((m) => (
                      <Tag key={m} style={{ fontSize: 11, margin: '2px 2px 0 0' }}>
                        {m}
                      </Tag>
                    ))}
                  </div>
                )}
              </div>
              <Divider style={{ margin: '8px 0' }} />
            </div>
          ))}
        </Card>
      )}
    </div>
  );
};

// Keep service URL param label in sync with Java constant
const ViewConfigurationService_PARAM_URL = 'sql.assistant.semantic.service.url';

export default ConfigurationPage;
