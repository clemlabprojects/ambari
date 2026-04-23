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
  Tooltip, message, Tag, Modal, Typography, Empty, Divider, Row, Col,
} from "antd";
import { DeleteOutlined, ReloadOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import { required, url } from "../utils/formRules";
import './Page.css';

const { Title, Text } = Typography;

interface GitRepo {
  id: string;
  name: string;
  url: string;
  type: 'HTTPS' | 'SSH';
  credentialAlias?: string;
  description?: string;
}

const STORAGE_KEY = 'k8s-view-git-repos';
const loadRepos = (): GitRepo[] => {
  try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]'); } catch { return []; }
};
const persistRepos = (repos: GitRepo[]) => {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(repos)); } catch { /* storage unavailable */ }
};

export default function GitRepositoriesPage() {
  const [repos, setRepos] = useState<GitRepo[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingRepo, setEditingRepo] = useState<GitRepo | null>(null);
  const [form] = Form.useForm();
  const typeWatch = Form.useWatch("type", form);

  const fetchRepos = () => {
    setLoading(true);
    try { setRepos(loadRepos()); }
    catch (err: any) { message.error(`Failed to load: ${err}`); }
    finally { setLoading(false); }
  };

  useEffect(() => { fetchRepos(); }, []);

  const openAdd = () => {
    setEditingRepo(null);
    form.resetFields();
    form.setFieldsValue({ type: "HTTPS" });
    setModalOpen(true);
  };

  const openEdit = (r: GitRepo) => {
    setEditingRepo(r);
    form.resetFields();
    form.setFieldsValue({ id: r.id, name: r.name, url: r.url, type: r.type, description: r.description, secret: '' });
    setModalOpen(true);
  };

  const closeModal = () => {
    setModalOpen(false);
    setEditingRepo(null);
    form.resetFields();
  };

  const onFinish = async (values: any) => {
    setSaving(true);
    try {
      const { secret, ...entity } = values;
      const repo: GitRepo = {
        id: entity.id,
        name: entity.name,
        url: entity.url,
        type: entity.type || (entity.url?.startsWith('git@') || entity.url?.startsWith('ssh://') ? 'SSH' : 'HTTPS'),
        credentialAlias: secret ? `git-${entity.id}-${Date.now()}` : editingRepo?.credentialAlias,
        description: entity.description,
      };
      const updated = repos.find(r => r.id === repo.id)
        ? repos.map(r => r.id === repo.id ? repo : r)
        : [...repos, repo];
      persistRepos(updated);
      setRepos(updated);
      message.success(editingRepo ? "Repository updated" : "Repository added");
      closeModal();
    } catch (err: any) {
      message.error(`Save error: ${err}`);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = (id: string) => {
    const updated = repos.filter(r => r.id !== id);
    persistRepos(updated);
    setRepos(updated);
    message.success("Repository deleted");
  };

  const urlRules = typeWatch === 'SSH'
    ? [required("URL is required")]
    : [required("URL is required"), url("Enter a valid HTTPS URL")];

  const columns: ColumnsType<GitRepo> = [
    { title: "ID", dataIndex: "id", key: "id", width: 140 },
    { title: "Name", dataIndex: "name", key: "name", width: 200 },
    {
      title: "Type", dataIndex: "type", key: "type", width: 90,
      render: (t: string) => <Tag color={t === 'SSH' ? 'purple' : 'blue'}>{t}</Tag>,
    },
    {
      title: "Repository URL", dataIndex: "url", key: "url",
      render: (text: string) => <Text ellipsis={{ tooltip: text }} style={{ maxWidth: 400 }}>{text}</Text>,
    },
    {
      title: "Description", dataIndex: "description", key: "description",
      render: (t?: string) => t || <Text type="secondary">–</Text>,
    },
    {
      title: "Actions", key: "actions", fixed: "right", width: 90,
      render: (_: any, r: GitRepo) => (
        <Space>
          <Tooltip title="Edit"><Button size="small" icon={<EditOutlined />} onClick={() => openEdit(r)} /></Tooltip>
          <Popconfirm title="Delete this repository?" onConfirm={() => handleDelete(r.id)}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div className="page-header">
        <div>
          <Title level={2} style={{ marginBottom: 2 }}>Git Repositories</Title>
          <Text type="secondary">Git repositories used as sources for Flux GitOps deployments.</Text>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={fetchRepos}>Refresh</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openAdd}>Add repository</Button>
        </Space>
      </div>

      <Table
        rowKey="id"
        columns={columns}
        dataSource={repos}
        loading={loading}
        pagination={false}
        size="small"
        style={{ marginTop: 8 }}
        locale={{ emptyText: <Empty description="No Git repositories yet. Click «Add repository» to get started." /> }}
      />

      {/* ── Add / Edit modal ── */}
      <Modal
        title={editingRepo ? `Edit — ${editingRepo.id}` : 'Add Git repository'}
        open={modalOpen}
        onCancel={closeModal}
        onOk={() => form.submit()}
        okText={editingRepo ? 'Update' : 'Add'}
        confirmLoading={saving}
        width={480}
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
            <Col span={10}>
              <Form.Item name="id" label="ID" rules={[required("required")]}
                tooltip="Unique identifier for this repository. Cannot be changed after creation.">
                <Input placeholder="my-gitlab" disabled={!!editingRepo} />
              </Form.Item>
            </Col>
            <Col span={14}>
              <Form.Item name="name" label="Display name" rules={[required("required")]}>
                <Input placeholder="My GitLab instance" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="description" label="Description">
            <Input placeholder="Optional — e.g. production GitLab for GitOps" />
          </Form.Item>

          {/* ── Connection ── */}
          <Divider orientation="left" orientationMargin={0} style={{ fontSize: 12, color: '#8c8c8c' }}>
            Connection
          </Divider>
          <Form.Item name="type" label="Protocol" rules={[required("required")]}>
            <Select options={[
              { value: "HTTPS", label: "HTTPS — username & token" },
              { value: "SSH", label: "SSH — private key" },
            ]} />
          </Form.Item>
          <Form.Item name="url" label="Repository URL" rules={urlRules}>
            <Input placeholder={
              typeWatch === 'SSH'
                ? 'git@gitlab.example.com:org/repo.git'
                : 'https://gitlab.example.com/org/repo.git'
            } />
          </Form.Item>

          {/* ── Authentication ── */}
          <Divider orientation="left" orientationMargin={0} style={{ fontSize: 12, color: '#8c8c8c' }}>
            Authentication
          </Divider>
          <Form.Item
            name="secret"
            label={typeWatch === 'SSH' ? 'SSH private key' : 'Access token'}
            tooltip={typeWatch === 'SSH'
              ? 'PEM-encoded SSH private key (-----BEGIN ... KEY-----).'
              : 'Personal access token or password. Leave blank when editing to keep the existing credential.'}
          >
            <Input.Password placeholder={editingRepo ? "unchanged" : (typeWatch === 'SSH' ? '-----BEGIN ... KEY-----' : 'glpat-xxxx')} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export const getGitRepos = (): GitRepo[] => loadRepos();

if (typeof window !== 'undefined') {
  (window as any).__gitRepos = loadRepos();
}
