import React, { useEffect, useState } from "react";
import type { ColumnsType } from "antd/es/table";
import { Col, Form, Input, Select, Button, Table, Space, Popconfirm, Row, Tooltip, Spin, message, Tag } from "antd";
import { DeleteOutlined, ReloadOutlined, EditOutlined } from "@ant-design/icons";
import { required, url } from "../utils/formRules";

interface GitRepo {
  id: string;
  name: string;
  url: string;
  type: 'HTTPS' | 'SSH';
  credentialAlias?: string;
  description?: string;
}

// Simple in-memory storage (can be replaced with backend API later)
const STORAGE_KEY = 'k8s-view-git-repos';

const loadRepos = (): GitRepo[] => {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored ? JSON.parse(stored) : [];
  } catch {
    return [];
  }
};

const saveRepos = (repos: GitRepo[]) => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(repos));
  } catch (e) {
    console.error('Failed to save Git repos', e);
  }
};

export default function GitRepositoriesPage() {
  const [repos, setRepos] = useState<GitRepo[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const fetchRepos = async () => {
    setLoading(true);
    try {
      const data = loadRepos();
      setRepos(data);
    } catch (err: any) {
      message.error(`Repository loading error: ${err}`);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchRepos();
  }, []);

  const onFinish = async (values: any) => {
    setSaving(true);
    try {
      const { secret, ...entity } = values;
      const newRepo: GitRepo = {
        id: entity.id,
        name: entity.name,
        url: entity.url,
        type: entity.type || (entity.url.startsWith('git@') || entity.url.startsWith('ssh://') ? 'SSH' : 'HTTPS'),
        credentialAlias: secret ? `git-${entity.id}-${Date.now()}` : undefined,
        description: entity.description,
      };

      const updated = repos.find(r => r.id === newRepo.id)
        ? repos.map(r => r.id === newRepo.id ? newRepo : r)
        : [...repos, newRepo];
      
      saveRepos(updated);
      setRepos(updated);
      message.success("Repository saved");
      form.resetFields();
    } catch (err: any) {
      message.error(`Save error: ${err}`);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      const updated = repos.filter(r => r.id !== id);
      saveRepos(updated);
      setRepos(updated);
      message.success("Repository deleted");
    } catch (err: any) {
      message.error(`Delete error: ${err}`);
    }
  };

  const columns: ColumnsType<GitRepo> = [
    {
      title: "ID",
      dataIndex: "id",
      key: "id",
      width: 140,
    },
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      width: 160,
    },
    {
      title: "Type",
      dataIndex: "type",
      key: "type",
      width: 80,
      render: (type: string) => <Tag>{type}</Tag>,
    },
    {
      title: "Repository URL",
      dataIndex: "url",
      key: "url",
      render: (text: string) => (
        <span
          style={{
            maxWidth: 400,
            display: "inline-block",
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
          title={text}
        >
          {text}
        </span>
      ),
    },
    {
      title: "Description",
      dataIndex: "description",
      key: "description",
      render: (text: string | undefined) => text || <span style={{ color: "#999" }}>–</span>,
    },
    {
      title: "Actions",
      key: "actions",
      fixed: "right",
      width: 100,
      render: (_: any, r) => (
        <Space>
          <Tooltip title="Edit">
            <Button
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                form.setFieldsValue({
                  id: r.id,
                  name: r.name,
                  url: r.url,
                  type: r.type,
                  description: r.description,
                  // Note: secret is not populated for security reasons
                });
                window.scrollTo({ top: 0, behavior: 'smooth' });
              }}
            />
          </Tooltip>
          <Popconfirm
            title="Confirm deletion?"
            onConfirm={() => handleDelete(r.id)}
          >
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: 24 }}>
      <h1 style={{ fontSize: 20, marginBottom: 16 }}>Git Repositories</h1>
      <p style={{ color: '#666', marginBottom: 24 }}>
        Manage Git repositories for Flux GitOps deployments. Credentials are stored securely in the credential store.
      </p>

      <Form
        id="gitRepoForm"
        name="gitRepoForm"
        layout="vertical"
        form={form}
        initialValues={{ type: "HTTPS" }}
        onFinish={onFinish}
      >
        <Row gutter={[16, 8]}>
          <Col xs={24} md={4}>
            <Form.Item
              name="id"
              label="ID"
              rules={[required("id is required")]}
            >
              <Input placeholder="my-git-repo" />
            </Form.Item>
          </Col>
          <Col xs={24} md={4}>
            <Form.Item
              name="name"
              label="Name"
              rules={[required("name is required")]}
            >
              <Input placeholder="My Git Repo" />
            </Form.Item>
          </Col>
          <Col xs={24} md={3}>
            <Form.Item
              name="type"
              label="Type"
              rules={[required("type is required")]}
            >
              <Select
                style={{ width: "100%" }}
                options={[
                  { value: "HTTPS", label: "HTTPS" },
                  { value: "SSH", label: "SSH" },
                ]}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={7}>
            <Form.Item
              name="url"
              label="Repository URL"
              rules={[required("url is required"), url("Enter a valid Git repository URL")]}
            >
              <Input placeholder="https://git.example.com/org/repo.git or git@git.example.com:org/repo.git" />
            </Form.Item>
          </Col>
          <Col xs={24} md={6}>
            <Form.Item name="description" label="Description">
              <Input placeholder="Optional description" />
            </Form.Item>
          </Col>
        </Row>

        <Row gutter={[16, 8]} align="bottom">
          <Col xs={24} md={8}>
            <Form.Item name="secret" label="Auth Token / SSH Key">
              <Input.Password placeholder="Token (HTTPS) or SSH private key (SSH)" />
            </Form.Item>
          </Col>
          <Col xs={24} md={16}>
            <Form.Item label=" ">
              <div style={{ textAlign: "left" }}>
                <Button
                  form="gitRepoForm"
                  type="primary"
                  htmlType="submit"
                  loading={saving}
                >
                  Save
                </Button>
              </div>
            </Form.Item>
          </Col>
        </Row>
      </Form>

      <Spin spinning={loading} tip="Loading...">
        <Table
          rowKey="id"
          columns={columns}
          dataSource={repos}
          pagination={false}
          style={{ marginTop: 24 }}
        />
      </Spin>

      <Button type="link" icon={<ReloadOutlined />} onClick={fetchRepos} style={{ marginTop: 16 }}>
        Refresh
      </Button>
    </div>
  );
}

// Export function to get repos for use in other components
export const getGitRepos = (): GitRepo[] => {
  return loadRepos();
};

// Initialize global reference for form components
if (typeof window !== 'undefined') {
  (window as any).__gitRepos = loadRepos();
}

