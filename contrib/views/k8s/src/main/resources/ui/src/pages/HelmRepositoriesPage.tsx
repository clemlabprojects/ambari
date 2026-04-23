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

import React, { useEffect, useState } from "react";
import type { ColumnsType } from "antd/es/table";
import {
  Form, Input, Select, Button, Table, Space, Popconfirm,
  Tooltip, message, Alert, Tag, Modal, Typography, Empty, Divider, Row, Col,
} from "antd";
import {
  CheckCircleTwoTone, DeleteOutlined, ReloadOutlined,
  EditOutlined, PlusOutlined,
} from "@ant-design/icons";
import {
  getHelmRepos, saveHelmRepo, deleteHelmRepo, loginHelmRepo,
  installMonitoring, getMonitoringDiscovery,
} from '../api/client';
import type HelmRepo from '../types';
import { required, url, slug, trim } from "../utils/formRules";
import './Page.css';

const { Title, Text } = Typography;

export default function RepositoriesPage() {
  const [repos, setRepos] = useState<HelmRepo[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [syncing, setSyncing] = useState<Record<string, boolean>>({});
  const [modalOpen, setModalOpen] = useState(false);
  const [editingRepo, setEditingRepo] = useState<HelmRepo | null>(null);
  const [selectedRepo, setSelectedRepo] = useState<string>();
  const [monitoringState, setMonitoringState] = useState<{ state?: string; message?: string; namespace?: string; release?: string }>({});
  const [form] = Form.useForm();
  const repoTypeWatch = Form.useWatch("type", form);

  const fetchRepos = async () => {
    setLoading(true);
    try {
      const data = await getHelmRepos();
      setRepos(data);
      if (!selectedRepo && data.length > 0) setSelectedRepo(data[0].id);
      try {
        const disc = await getMonitoringDiscovery();
        setMonitoringState({
          state: (disc as any)?.state,
          message: (disc as any)?.message,
          namespace: disc?.namespace,
          release: disc?.release,
        });
      } catch {
        setMonitoringState({});
      }
    } catch (err: any) {
      message.error(`Repository loading error: ${err}`);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchRepos(); }, []);

  const openAdd = () => {
    setEditingRepo(null);
    form.resetFields();
    form.setFieldsValue({ type: "HTTP", authMode: "anonymous" });
    setModalOpen(true);
  };

  const openEdit = (r: HelmRepo) => {
    setEditingRepo(r);
    form.resetFields();
    form.setFieldsValue({
      id: r.id, name: r.name, type: r.type, url: r.url,
      imageProject: r.imageProject,
      imageRegistryHostOverride: r.imageRegistryHostOverride,
      authMode: r.authMode, username: r.username, secret: '',
    });
    setModalOpen(true);
  };

  const closeModal = () => {
    setModalOpen(false);
    setEditingRepo(null);
    form.resetFields();
  };

  const handleOk = () => {
    form.submit();
  };

  const onFinish = async (values: any) => {
    setSaving(true);
    try {
      const { secret, ...entity } = values;
      await saveHelmRepo(entity, secret || undefined);
      message.success(editingRepo ? "Repository updated" : "Repository added");
      closeModal();
      fetchRepos();
      setSelectedRepo(entity.id);
    } catch (err: any) {
      message.error(`Save error: ${err}`);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await deleteHelmRepo(id);
      message.success("Repository deleted");
    } catch (err: any) {
      message.error(`Delete error: ${err}`);
    } finally {
      fetchRepos();
    }
  };

  const handleLogin = async (id: string) => {
    setSyncing(s => ({ ...s, [id]: true }));
    try {
      await loginHelmRepo(id);
      message.success("Login / sync OK");
    } catch (err: any) {
      message.error(`Login error: ${err}`);
    } finally {
      setSyncing(s => { const c = { ...s }; delete c[id]; return c; });
      fetchRepos();
    }
  };

  const urlRules = () => {
    if (repoTypeWatch === "OCI") {
      return [
        required("URL is required"),
        {
          validator: (_: any, value: string) => {
            if (!value) return Promise.resolve();
            const ok = /^[a-zA-Z0-9.-]+(:\d+)?(\/[a-zA-Z0-9._\-\/]+)?$/.test(value.trim());
            return ok
              ? Promise.resolve()
              : Promise.reject("Enter a registry host, e.g. registry.clemlab.com");
          },
        },
      ];
    }
    return [required("URL is required"), url("Enter a valid HTTP(S) URL")];
  };

  const columns: ColumnsType<HelmRepo> = [
    { title: "ID", dataIndex: "id", key: "id", width: 140 },
    { title: "Name", dataIndex: "name", key: "name", width: 160 },
    {
      title: "Type", dataIndex: "type", key: "type", width: 80,
      render: (t: string) => <Tag color={t === 'OCI' ? 'blue' : 'default'}>{t}</Tag>,
    },
    {
      title: "URL", dataIndex: "url", key: "url",
      render: (text: string) => <Text ellipsis={{ tooltip: text }} style={{ maxWidth: 260 }}>{text}</Text>,
    },
    {
      title: "Image project", dataIndex: "imageProject", key: "imageProject", width: 140,
      render: (t?: string) => t || <Text type="secondary">–</Text>,
    },
    {
      title: "Auth", dataIndex: "authMode", key: "authMode", width: 140,
      render: (_: any, r: HelmRepo) => (
        <Space size={4}>
          <Tag>{r.authMode}</Tag>
          {r.username && r.authMode !== "anonymous" && <Text type="secondary">({r.username})</Text>}
          {r.authInvalid && <Tag color="error">invalid</Tag>}
        </Space>
      ),
    },
    {
      title: "Last checked", dataIndex: "lastChecked", key: "lastChecked", width: 150,
      render: (v?: string) => v
        ? <Text type="secondary">{new Date(v).toLocaleString()}</Text>
        : <Text type="secondary">–</Text>,
    },
    {
      title: "Actions", key: "actions", fixed: "right", width: 120,
      render: (_: any, r: HelmRepo) => (
        <Space>
          <Tooltip title="Edit">
            <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(r)} />
          </Tooltip>
          <Tooltip title="Login / Sync">
            <Button
              size="small"
              icon={<CheckCircleTwoTone twoToneColor="#52c41a" />}
              loading={!!syncing[r.id]}
              onClick={() => handleLogin(r.id)}
            />
          </Tooltip>
          <Popconfirm title="Delete this repository?" onConfirm={() => handleDelete(r.id)}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      {/* ── Page header ── */}
      <div className="page-header">
        <div>
          <Title level={2} style={{ marginBottom: 2 }}>Helm Repositories</Title>
          <Text type="secondary">Chart repositories used to install and upgrade services.</Text>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={fetchRepos}>Refresh</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openAdd}>Add repository</Button>
        </Space>
      </div>

      {/* ── Monitoring alert (only when actionable) ── */}
      {(monitoringState.state === 'RUNNING' || monitoringState.state === 'FAILED') && (
        <Alert
          banner
          style={{ marginBottom: 12, marginTop: 8 }}
          message={
            <Space>
              <span>Monitoring bootstrap</span>
              <Tag color={monitoringState.state === 'FAILED' ? 'red' : 'blue'}>
                {monitoringState.state === 'FAILED' ? 'Not found' : 'Installing…'}
              </Tag>
              {monitoringState.release && monitoringState.namespace && (
                <Text type="secondary">{monitoringState.release} / {monitoringState.namespace}</Text>
              )}
            </Space>
          }
          description={monitoringState.message}
          type={monitoringState.state === 'FAILED' ? 'error' : 'info'}
          showIcon
        />
      )}

      {/* ── Monitoring bootstrap actions ── */}
      {repos.length > 0 && (
        <div style={{ marginBottom: 12, marginTop: 8 }}>
          <Space wrap>
            <Text type="secondary">Monitoring bootstrap:</Text>
            <Select
              value={selectedRepo}
              placeholder="Select repository"
              style={{ minWidth: 200 }}
              onChange={setSelectedRepo}
              options={repos.map(r => ({ value: r.id, label: `${r.name || r.id} (${r.type})` }))}
            />
            <Button
              type="primary"
              disabled={!selectedRepo}
              onClick={async () => {
                if (!selectedRepo) return;
                try {
                  await installMonitoring(selectedRepo);
                  message.success("Monitoring install requested");
                } catch (e: any) {
                  message.error(e?.message || "Monitoring install failed");
                }
              }}
            >
              Install
            </Button>
            <Button
              onClick={async () => {
                try {
                  const res = await getMonitoringDiscovery();
                  if (res?.namespace && res?.release) {
                    message.success(`Monitoring found: ${res.release} in ${res.namespace}`);
                  } else {
                    message.info("Monitoring not found");
                  }
                } catch (e: any) {
                  message.error(e?.message || "Discovery failed");
                }
              }}
            >
              Check installation
            </Button>
          </Space>
        </div>
      )}

      {/* ── Table ── */}
      <Table
        rowKey="id"
        columns={columns}
        dataSource={repos}
        loading={loading}
        pagination={false}
        size="small"
        locale={{ emptyText: <Empty description="No repositories yet. Click «Add repository» to get started." /> }}
      />

      {/* ── Add / Edit modal ── */}
      <Modal
        title={editingRepo ? `Edit — ${editingRepo.id}` : 'Add Helm repository'}
        open={modalOpen}
        onCancel={closeModal}
        onOk={handleOk}
        okText={editingRepo ? 'Update' : 'Add'}
        confirmLoading={saving}
        width={520}
        destroyOnClose={false}
      >
        <Form
          layout="vertical"
          form={form}
          onFinish={onFinish}
          style={{ marginTop: 8 }}
        >
          {/* ── Identity ── */}
          <Divider orientation="left" orientationMargin={0} style={{ fontSize: 12, color: '#8c8c8c', marginTop: 0 }}>
            Identity
          </Divider>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="id" label="ID" getValueFromEvent={trim} rules={[required("required"), slug]}
                tooltip="Unique identifier used internally (e.g. clemlabprojects). Cannot be changed after creation.">
                <Input placeholder="clemlabprojects" disabled={!!editingRepo} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="name" label="Display name" rules={[required("required")]}>
                <Input placeholder="Clemlab Harbor" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="type" label="Repository type" rules={[required("required")]}
            tooltip="HTTP — traditional Helm repo index. OCI — registry like Harbor or ECR.">
            <Select options={[{ value: "HTTP", label: "HTTP (charts index)" }, { value: "OCI", label: "OCI (registry)" }]} />
          </Form.Item>

          {/* ── Connection ── */}
          <Divider orientation="left" orientationMargin={0} style={{ fontSize: 12, color: '#8c8c8c' }}>
            Connection
          </Divider>
          <Form.Item
            name="url"
            label={repoTypeWatch === 'OCI' ? 'Registry host' : 'Chart index URL'}
            rules={urlRules()}
            tooltip={repoTypeWatch === 'OCI'
              ? 'Hostname (and optional path prefix) of the OCI registry, e.g. registry.clemlab.com'
              : 'Full URL of the Helm HTTP index, e.g. https://charts.helm.sh/stable'}
          >
            <Input placeholder={repoTypeWatch === 'OCI' ? 'registry.clemlab.com' : 'https://charts.example.com'} />
          </Form.Item>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="imageProject" label="Image project"
                tooltip="Harbor project used for Docker image pulls (e.g. clemlabprojects). Derived from the URL if left blank.">
                <Input placeholder="clemlabprojects" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="imageRegistryHostOverride" label="Registry host override"
                tooltip="Override the registry hostname used for image pulls. Leave blank to derive from the URL.">
                <Input placeholder="registry.clemlab.com" />
              </Form.Item>
            </Col>
          </Row>

          {/* ── Authentication ── */}
          <Divider orientation="left" orientationMargin={0} style={{ fontSize: 12, color: '#8c8c8c' }}>
            Authentication
          </Divider>
          <Form.Item name="authMode" label="Auth mode" rules={[required("required")]}>
            <Select options={[
              { value: "anonymous", label: "Anonymous — no credentials" },
              { value: "basic", label: "Basic — username & password" },
              { value: "token", label: "Token — bearer token" },
            ]} />
          </Form.Item>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="username" label="Username">
                <Input placeholder="robot$viewer" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="secret" label="Password / Token"
                tooltip="Leave blank when editing to keep the existing credential.">
                <Input.Password placeholder={editingRepo ? "unchanged" : "secret"} />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </div>
  );
}
