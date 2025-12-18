import React, { useEffect, useState } from "react";
import type { ColumnsType } from "antd/es/table";
import { Col, Form,  Input, Select, Button, Table, Space, Popconfirm, Row, Tooltip, Spin, message, Alert, Tag } from "antd";
import { CheckCircleTwoTone, DeleteOutlined, ReloadOutlined, EditOutlined } from "@ant-design/icons";
import { getHelmRepos, saveHelmRepo, deleteHelmRepo, loginHelmRepo, checkHelmRepo, installMonitoring, getMonitoringDiscovery} from '../api/client';
import type HelmRepo from '../types';
import { required, url, slug, trim, domain } from "../utils/formRules"; 

export default function RepositoriesPage() {
  /** ---------------- state --------------- */
  const [repos, setRepos] = useState<HelmRepo[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [syncing, setSyncing] = useState<Record<string, boolean>>({});
  const [selectedRepo, setSelectedRepo] = useState<string>();
  const [monitoringState, setMonitoringState] = useState<{ state?: string; message?: string; namespace?: string; release?: string }>({});
  const [form] = Form.useForm();
  /** -------------- effects --------------- */
  const fetchRepos = async () => {
    setLoading(true);
    try {
      const data = await getHelmRepos();
      setRepos(data);
      if (!selectedRepo && data.length > 0) setSelectedRepo(data[0].id);

      // Fetch monitoring discovery to display bootstrap state
      try {
        const disc = await getMonitoringDiscovery();
        setMonitoringState({
          state: (disc as any)?.state,
          message: (disc as any)?.message,
          namespace: disc?.namespace,
          release: disc?.release
        });
      } catch (e:any) {
        setMonitoringState({ state: 'UNKNOWN', message: e?.message });
      }
    } catch (err: any) {
      message.error(`Repository loading error: ${err}`);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchRepos();
  }, []);


  const monitoringCta = repos.length === 0 ? (
    <div style={{ marginBottom: 12 }}>
      <Row justify="space-between" align="middle">
        <Col>
          <strong>Monitoring bootstrap requires at least one Helm repository.</strong>
        </Col>
        <Col>
          <Button size="small" onClick={() => message.info('Add a repository then use "Check monitoring installation".')}>Help</Button>
        </Col>
      </Row>
    </div>) : null;

  /** ---------------- form submit --------------- */
  const onFinish = async (values: any) => {
    setSaving(true);
    try {
      const { secret, ...entity } = values;
      await saveHelmRepo(entity, secret || undefined);
      message.success("Repository saved");
      fetchRepos();
      form.resetFields();
      setSelectedRepo(entity.id);
    } catch (err: any) {
      message.error(`Save error: ${err}`);
    } finally {
      setSaving(false);
    }
  };

  /** ---------------- actions table --------------- */
  const handleDelete = async (id: string) => {
    try {
      await deleteHelmRepo(id);
      message.success("Repository deleted");
    } catch (err: any) {
      message.error(`Delete error: ${err}`);
    } finally {
      fetchRepos(); // always refresh, even if something is slightly off
    }
  };

  const handleLogin = async (id: string) => {
    try {
      setSyncing(s => ({ ...s, [id]: true }));
      await loginHelmRepo(id);
      message.success("Login / sync OK");
    } catch (err: any) {
      message.error(`Login error: ${err}`);
    } finally {
      setSyncing(s => {
        const c = { ...s };
        delete c[id];
        return c;
      });
      fetchRepos();
    }
  };

  /** ---------------- table columns --------------- */
  const columns: ColumnsType<HelmRepo> = [
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
    },
    {
      title: "Chart Repo URL",
      dataIndex: "url",
      key: "url",
      render: (text: string) => (
        <span
          style={{
            maxWidth: 260,
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
      title: "Image Project",
      dataIndex: "imageProject",
      key: "imageProject",
      width: 140,
      render: (text: string | undefined) => text || <span style={{ color: "#999" }}>–</span>,
    },
    {
      title: "Registry Host Override",
      dataIndex: "imageRegistryHostOverride",
      key: "imageRegistryHostOverride",
      width: 180,
      render: (text: string | undefined) =>
        text ? (
          <span title={text}>{text}</span>
        ) : (
          <span style={{ color: "#999" }}>derived from URL</span>
        ),
    },
    {
      title: "Auth",
      dataIndex: "authMode",
      key: "authMode",
      width: 120,
      render: (_: any, r) => (
        <span>
          {r.authMode}
          {r.username && r.authMode !== "anonymous" && (
            <span style={{ color: "#666", marginLeft: 4 }}>({r.username})</span>
          )}
          {r.authInvalid && (
            <span style={{ color: "red", marginLeft: 4 }}>(invalid)</span>
          )}
        </span>
      ),
    },
    {
      title: "Last checked",
      dataIndex: "lastChecked",
      key: "lastChecked",
      width: 160,
      render: (value: string | undefined) =>
        value ? new Date(value).toLocaleString() : <span style={{ color: "#999" }}>–</span>,
    },
    {
      title: "Actions",
      key: "actions",
      fixed: "right",
      width: 130,
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
                  type: r.type,
                  url: r.url,
                  imageProject: r.imageProject,
                  imageRegistryHostOverride: r.imageRegistryHostOverride,
                  authMode: r.authMode,
                  username: r.username,
                  secret: '', // Clear secret field for security
                  // Note: secret is not populated for security reasons
                });
                // Scroll to form
                setTimeout(() => {
                  window.scrollTo({ top: 0, behavior: 'smooth' });
                }, 100);
              }}
            />
          </Tooltip>
          <Tooltip title="Login / Sync">
            <Button
              size="small"
              icon={<CheckCircleTwoTone twoToneColor="#52c41a" />}
              loading={!!syncing[r.id]}
              onClick={() => handleLogin(r.id)}
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
  let returnDynamicRules = () => {
    const type = form.getFieldValue("type");

    if (type === "OCI") {
      // OCI: host or host + optional path, no scheme
      return [
        required("url is required"),
        {
          validator: (_: any, value: string) => {
            if (!value) return Promise.resolve();
            const v = String(value).trim();

            // allow: host, host:port, host/path, host:port/path
            const regex = /^[a-zA-Z0-9.-]+(:\d+)?(\/[a-zA-Z0-9._\-\/]+)?$/;

            if (regex.test(v)) {
              return Promise.resolve();
            }
            return Promise.reject(
              "Enter a valid OCI registry URL like 'registry.clemlab.com' or 'registry.clemlab.com/path'"
            );
          },
        },
      ];
    }

    // HTTP: use your existing `url()` rule, full http(s) URL with scheme
    return [
      required("url is required"),
      url("Enter a valid HTTP(S) repo URL"),
    ];
  };

  /** ---------------- render --------------- */
  return (
    <div style={{ padding: 24 }}>
      <h1 style={{ fontSize: 20, marginBottom: 16 }}>Helm Repositories</h1>

      {monitoringState.state && (
        <Alert
          banner
          style={{ marginBottom: 12 }}
          message={
            <Space>
              <span>Monitoring bootstrap</span>
              <Tag color={monitoringState.state === 'COMPLETED' ? 'green' : monitoringState.state === 'FAILED' ? 'red' : 'blue'}>
                {monitoringState.state}
              </Tag>
              {monitoringState.release && monitoringState.namespace && (
                <span>{monitoringState.release} / {monitoringState.namespace}</span>
              )}
            </Space>
          }
          description={monitoringState.message}
          type={monitoringState.state === 'FAILED' ? 'error' : monitoringState.state === 'RUNNING' ? 'warning' : 'info'}
          showIcon
        />
      )}

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
        {/* ---- Row 1: general repo info ---- */}
        <Row gutter={[16, 8]}>
          {/* ID */}
          <Col xs={24} md={4}>
            <Form.Item
              name="id"
              label="ID"
              getValueFromEvent={trim}
              rules={[required("id is required"), slug]}
            >
              <Input placeholder="bitnami" />
            </Form.Item>
          </Col>

          {/* Name */}
          <Col xs={24} md={4}>
            <Form.Item
              name="name"
              label="Name"
              rules={[required("name is required")]}
            >
              <Input placeholder="bitnami" />
            </Form.Item>
          </Col>

          {/* Type */}
          <Col xs={24} md={3}>
            <Form.Item
              name="type"
              label="Type"
              rules={[required("type is required")]}
            >
              <Select
                style={{ width: "100%" }}
                options={[
                  { value: "HTTP", label: "HTTP" },
                  { value: "OCI", label: "OCI" },
                ]}
                onChange={(value: string) => {
                  form.setFieldsValue({ type: value });
                  setSyncing(s => ({ ...s })); // force re-render for dynamic rules
                }}
              />
            </Form.Item>
          </Col>

          {/* Chart Repo URL */}
          <Col xs={24} md={7}>
            <Form.Item
              name="url"
              label="Chart Repo URL"
              rules={returnDynamicRules()}
            >
              <Input placeholder="https://registry.clemlab.com/charts" />
            </Form.Item>
          </Col>

          {/* Image project / namespace */}
          <Col xs={24} md={6}>
            <Form.Item
              name="imageProject"
              label="Image Registry / Project"
              tooltip="Project / namespace (e.g. 'clemlabprojects'). Host is derived from the Chart Repo URL."
            >
              <Input placeholder="clemlabprojects" />
            </Form.Item>
          </Col>
          {/* 4 + 4 + 3 + 7 + 6 = 24 on md */}
        </Row>

        {/* ---- Row 2: auth & save ---- */}
        <Row gutter={[16, 8]} align="bottom">
          {/* Auth mode */}
          <Col xs={24} md={4}>
            <Form.Item
              name="authMode"
              label="Auth"
              rules={[required("authMode is required")]}
            >
              <Select
                style={{ width: "100%" }}
                options={[
                  { value: "anonymous", label: "anonymous" },
                  { value: "basic", label: "basic" },
                  { value: "token", label: "token" },
                ]}
              />
            </Form.Item>
          </Col>

          {/* Username */}
          <Col xs={24} md={4}>
            <Form.Item name="username" label="Username">
              <Input placeholder="user" />
            </Form.Item>
          </Col>

          {/* Password / Token */}
          <Col xs={24} md={6}>
            <Form.Item name="secret" label="Password / Token">
              <Input.Password placeholder="secret" />
            </Form.Item>
          </Col>

          {/* Save button */}
          <Col xs={24} md={10}>
            <Form.Item label=" ">
              <div style={{ textAlign: "left" }}>
                <Button
                  form="repoForm"
                  type="primary"
                  htmlType="submit"
                  loading={saving}
                >
                  Save
                </Button>
              </div>
            </Form.Item>
          </Col>
          {/* 4 + 4 + 6 + 10 = 24 on md */}
        </Row>
      </Form>

      {/* Monitoring install helpers */}
      <div style={{ marginTop: 12, marginBottom: 12 }}>
        <Space>
          <Select
            value={selectedRepo}
            placeholder="Select repo for monitoring"
            style={{ minWidth: 220 }}
            onChange={setSelectedRepo}
            options={repos.map(r => ({ value: r.id, label: `${r.name || r.id} (${r.type})` }))}
          />
          <Button
            type="primary"
            disabled={!selectedRepo}
            onClick={async () => {
              if (!selectedRepo) { message.info("Select a repository first"); return; }
              try {
                await installMonitoring(selectedRepo);
                message.success("Monitoring install requested");
              } catch (e:any) {
                message.error(e?.message || "Monitoring install failed");
              }
            }}
          >
            Install monitoring
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
              } catch (e:any) {
                message.error(e?.message || "Discovery failed");
              }
            }}
          >
            Check monitoring installation
          </Button>
        </Space>
      </div>

      {/* ---- table ---- */}
      <Spin spinning={loading} tip="Loading...">
        <Table
          rowKey="id"
          columns={columns}
          dataSource={repos}
          pagination={false}
          size="small"
          style={{ marginTop: 16 }}
        />
      </Spin>

      <Button type="link" icon={<ReloadOutlined />} onClick={fetchRepos} style={{ marginTop: 16 }}>
        Refresh
      </Button>
    </div>
  );
}
