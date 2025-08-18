import React, { useEffect, useState } from "react";
import type { ColumnsType } from "antd/es/table";
import { Form,  Input, Select, Button, Table, Space, Popconfirm, Tooltip, Spin, message } from "antd";
import { CheckCircleTwoTone, DeleteOutlined, ReloadOutlined } from "@ant-design/icons";
import { getHelmRepos, saveHelmRepo, deleteHelmRepo, loginHelmRepo, type HelmRepo} from '../api/client';
import type HelmRepo from '../types';
import { required, url, slug, trim } from "../utils/formRules"; 
export default function RepositoriesPage() {
  /** ---------------- state --------------- */
  const [repos, setRepos] = useState<HelmRepo[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [syncing, setSyncing] = useState<Record<string, boolean>>({});
  /** -------------- effects --------------- */
  const fetchRepos = async () => {
    setLoading(true);
    try {
      const data = await getHelmRepos();
      setRepos(data);
    } catch (err: any) {
      message.error(`Erreur chargement dépôts : ${err}`);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchRepos();
  }, []);

  /** ---------------- form submit --------------- */
  const onFinish = async (values: any) => {
    setSaving(true);
    try {
      const { secret, ...entity } = values;
      await saveHelmRepo(entity, secret || undefined);
      message.success("Dépôt enregistré");
      fetchRepos();
      form.resetFields();
    } catch (err: any) {
      message.error(`Erreur d'enregistrement : ${err}`);
    } finally {
      setSaving(false);
    }
  };

  const [form] = Form.useForm();

  /** ---------------- actions table --------------- */
  const handleDelete = async (id: string) => {
    try {
      await deleteHelmRepo(id);
      message.success("Dépôt supprimé");
      fetchRepos();
    } catch (err: any) {
      message.error(`Erreur suppression : ${err}`);
    }
  };

  const handleLogin = async (id: string) => {
    try {
      setSyncing(s => ({ ...s, [id]: true }));
      await loginHelmRepo(id);
      message.success("Login / sync OK");
      fetchRepos();
    } catch (err: any) {
      message.error(`Erreur login : ${err}`);
    } finally {
      setSyncing(s => { const c = { ...s }; delete c[id]; return c; });
    }
  };

  /** ---------------- table columns --------------- */
  const columns: ColumnsType<HelmRepo> = [
    {
      title: "ID",
      dataIndex: "id",
      key: "id",
      width: 160,
    },
    {
      title: "Nom",
      dataIndex: "name",
      key: "name",
    },
    {
      title: "Type",
      dataIndex: "type",
      key: "type",
      width: 80,
    },
    {
      title: "URL",
      dataIndex: "url",
      key: "url",
      render: (text) => (
        <span style={{ maxWidth: 280, display: "inline-block", overflow: "hidden", textOverflow: "ellipsis" }}>{text}</span>
      ),
    },
    {
      title: "Auth",
      dataIndex: "authMode",
      key: "authMode",
      width: 110,
      render: (_, r) => (
        <span>
          {r.authMode}
          {r.authInvalid && <span style={{ color: "red", marginLeft: 4 }}>(invalid)</span>}
        </span>
      ),
    },
    {
      title: "Actions",
      key: "actions",
      width: 120,
      render: (_, r) => (
        <Space>
          <Tooltip title="Login / Sync">
            <Button
              size="small"
              icon={<CheckCircleTwoTone twoToneColor="#52c41a" />}
              loading={!!syncing[r.id]}
              onClick={() =>
                handleLogin(r.id)}
            />
          </Tooltip>
          <Popconfirm title="Confirmer la suppression ?" onConfirm={() => handleDelete(r.id)}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];


  /** ---------------- render --------------- */
  return (
    <div style={{ padding: 24 }}>
      <h1 style={{ fontSize: 20, marginBottom: 16 }}>Dépôts Helm</h1>

      {/* ---- form ---- */}
      <Form
        id="repoForm"
        name="repoForm"
        layout="vertical"
        form={form}
        initialValues={{ type: "HTTP", authMode: "anonymous" }}
        onFinish={onFinish}
        onFinishFailed={({ errorFields }) => console.warn("repoForm errors:", errorFields)}
        onValuesChange={(_, all) => console.error("repoForm values:", all)}
      >
        <Space wrap align="end">
          <Form.Item
            name="id"
            label="ID"
            getValueFromEvent={trim}
            rules={[ required("id is required"), slug ]}
          >
            <Input placeholder="bitnami" />
          </Form.Item>

          <Form.Item
            name="name"
            label="Nom"
            rules={[ required("name is required") ]}
          >
            <Input placeholder="bitnami" />
          </Form.Item>

          <Form.Item
            name="type"
            label="Type"
            rules={[ required("type is required") ]}
          >
            <Select
              style={{ width: 120 }}
              options={[ { value: "HTTP", label: "HTTP" }, { value: "OCI", label: "OCI" } ]}
            />
          </Form.Item>

          <Form.Item
            name="url"
            label="URL"
            rules={[ required("url is required"), url("Enter a valid repo URL") ]}
          >
            <Input placeholder="https://charts.bitnami.com/bitnami" style={{ width: 260 }} />
          </Form.Item>

          <Form.Item
            name="authMode"
            label="Auth"
            rules={[ required("authMode is required") ]}
          >
            <Select
              style={{ width: 140 }}
              options={[
                { value: "anonymous", label: "anonymous" },
                { value: "basic",     label: "basic"     },
                { value: "token",     label: "token"     },
              ]}
            />
          </Form.Item>

          <Form.Item name="username" label="Username">
            <Input placeholder="user" style={{ width: 120 }} />
          </Form.Item>

          <Form.Item name="secret" label="Password / Token">
            <Input.Password placeholder="secret" style={{ width: 160 }} />
          </Form.Item>

          <Form.Item>
            <Button form="repoForm" type="primary" htmlType="submit" loading={saving}>
              Enregistrer
            </Button>
          </Form.Item>
        </Space>
      </Form>


      {/* ---- table ---- */}
      <Spin spinning={loading} tip="Chargement...">
        <Table rowKey="id" columns={columns} dataSource={repos} pagination={false} style={{ marginTop: 24 }} />
      </Spin>

      <Button type="link" icon={<ReloadOutlined />} onClick={fetchRepos} style={{ marginTop: 16 }}>
        Rafraîchir
      </Button>
    </div>
  );
}
