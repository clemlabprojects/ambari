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
  Tooltip, message, Alert, Tag, Modal, Typography, Descriptions, Switch,
} from "antd";
import {
  DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined, ApiOutlined,
  DatabaseOutlined, SafetyCertificateOutlined, KeyOutlined, LockOutlined,
  CloudServerOutlined,
} from "@ant-design/icons";
import {
  getContexts, saveContext, deleteContext, getResolvedContext, getContextSchema,
  probeRemoteContext,
  type PlatformContext, type ResolvedContext, type ContextCapabilitySchema,
  type RemoteProbeResult,
} from "../api/client";
import "./Page.css";

const { Title, Text, Paragraph } = Typography;

export default function ContextsPage() {
  const [contexts, setContexts] = useState<PlatformContext[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<PlatformContext | null>(null);
  const [resolved, setResolved] = useState<Record<string, ResolvedContext>>({});
  const [detail, setDetail] = useState<{ ctx: PlatformContext; r?: ResolvedContext } | null>(null);
  const [schema, setSchema] = useState<ContextCapabilitySchema[]>([]);
  const [form] = Form.useForm();
  // Drives the add/edit form: EXTERNAL shows the per-capability fields; REMOTE shows the
  // "connect to a remote cluster's Ambari" fields.
  const watchedKind = Form.useWatch("kind", form);
  // REMOTE "test connection & list clusters": result populates the cluster picker + shows the
  // remote Ambari version. Reset whenever the modal (re)opens.
  const [probing, setProbing] = useState(false);
  const [probeResult, setProbeResult] = useState<RemoteProbeResult | null>(null);

  /** Live-test the remote Ambari connection and list its clusters (REMOTE form helper). */
  const doProbe = async () => {
    const v = form.getFieldsValue(["remoteAmbariUrl", "remoteUsername", "remotePassword", "verifySsl"]);
    if (!v.remoteAmbariUrl || !v.remoteUsername || !v.remotePassword) {
      message.warning("Enter the Ambari URL, username and password first.");
      return;
    }
    setProbing(true);
    try {
      const res = await probeRemoteContext({
        remoteAmbariUrl: v.remoteAmbariUrl,
        remoteUsername: v.remoteUsername,
        remotePassword: v.remotePassword,
        verifySsl: v.verifySsl !== false,
      });
      setProbeResult(res);
      if (res.ok) {
        message.success(`Connected — ${res.clusters?.length ?? 0} cluster(s) found.`);
        // Auto-select the single cluster, or keep the current value if it is in the list.
        const list = res.clusters ?? [];
        const cur = form.getFieldValue("clusterName");
        if (list.length === 1) form.setFieldsValue({ clusterName: list[0] });
        else if (cur && !list.includes(cur)) { /* keep typed value */ }
      } else {
        message.error(res.error || "Connection failed.");
      }
    } catch (e: any) {
      setProbeResult({ ok: false, error: e?.message || "Connection failed." });
      message.error(e?.message || "Connection failed.");
    } finally {
      setProbing(false);
    }
  };

  useEffect(() => { getContextSchema().then(setSchema).catch(() => setSchema([])); }, []);

  // Form field name for a capability field — flattened "<capability>.<name>".
  const fieldKey = (cap: string, name: string) => `${cap}__${name}`;

  const fetchContexts = async () => {
    setLoading(true);
    try {
      const data = await getContexts();
      setContexts(data);
      // Resolve every context (operational/security view) — best-effort per context.
      const entries = await Promise.all(data.map(async (c) => {
        try { return [c.id, await getResolvedContext(c.id)] as const; }
        catch { return [c.id, undefined] as const; }
      }));
      const map: Record<string, ResolvedContext> = {};
      for (const [id, r] of entries) if (r) map[id] = r;
      setResolved(map);
    } catch (err: any) {
      message.error(`Context loading error: ${err}`);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchContexts(); }, []);

  // External-context schema fields: every capability field that isn't managed-only.
  const externalFields = () =>
    schema.flatMap((cap) =>
      (cap.fields || [])
        .filter((f) => f.appliesTo !== "MANAGED")
        .map((f) => ({ cap, f })));

  const openAdd = () => {
    setEditing(null);
    setProbeResult(null);
    form.resetFields();
    const init: any = { kind: "EXTERNAL", verifySsl: true };
    externalFields().forEach(({ cap, f }) => {
      if (!f.secret && f.default !== undefined) init[fieldKey(cap.capability, f.name)] = f.default;
    });
    form.setFieldsValue(init);
    setModalOpen(true);
  };

  const openEdit = (ctx: PlatformContext) => {
    setEditing(ctx);
    setProbeResult(null);
    form.resetFields();
    const vals: any = { id: ctx.id, name: ctx.name, kind: ctx.kind,
      clusterName: ctx.clusterName, description: ctx.description };
    if (ctx.kind === "REMOTE") {
      vals.remoteAmbariUrl = ctx.config?.remoteAmbariUrl;
      vals.remoteUsername = ctx.config?.remoteUsername;
      vals.verifySsl = ctx.config?.verifySsl !== false; // default true
      // password intentionally not prefilled — write-only, shown as "(unchanged)"
    } else {
      externalFields().forEach(({ cap, f }) => {
        if (!f.secret) vals[fieldKey(cap.capability, f.name)] = ctx.config?.[f.name] ?? f.default;
      });
    }
    form.setFieldsValue(vals);
    setModalOpen(true);
  };

  const onFinish = async (values: any) => {
    setSaving(true);
    try {
      let payload: PlatformContext;
      if (values.kind === "REMOTE") {
        // "Connect to a remote cluster": KDPS discovers the remote cluster's services from its
        // Ambari. The password is write-only — sent only when typed (no clobber on edit) and
        // stored encrypted server-side.
        payload = {
          id: values.id, name: values.name, kind: "REMOTE",
          clusterName: values.clusterName, description: values.description,
          config: {
            remoteAmbariUrl: values.remoteAmbariUrl,
            remoteUsername: values.remoteUsername,
            verifySsl: values.verifySsl !== false, // default true; false = ignore self-signed cert
          },
          secrets: {},
        };
        if (values.remotePassword) payload.secrets!.remotePassword = values.remotePassword;
      } else {
        payload = {
          id: values.id, name: values.name, kind: "EXTERNAL",
          clusterName: values.clusterName, description: values.description,
          config: {}, secrets: {},
        };
        externalFields().forEach(({ cap, f }) => {
          const v = values[fieldKey(cap.capability, f.name)];
          if (v === undefined || v === null || v === "") return;
          if (f.secret) payload.secrets![f.name] = v;     // only sent when typed → no clobber on edit
          else payload.config![f.name] = v;
        });
      }
      await saveContext(payload);
      message.success(editing ? "Context updated" : "Context created");
      setModalOpen(false);
      fetchContexts();
    } catch (err: any) {
      message.error(`Save error: ${err}`);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await deleteContext(id);
      message.success("Context deleted");
      fetchContexts();
    } catch (err: any) {
      message.error(`Delete error: ${err}`);
    }
  };

  const columns: ColumnsType<PlatformContext> = [
    { title: "Name", dataIndex: "name", key: "name", render: (n: string, c) => (
        <Space direction="vertical" size={0}>
          <Text strong>{n}</Text>
          {c.clusterName && <Text type="secondary" style={{ fontSize: 12 }}>{c.clusterName}</Text>}
        </Space>
      ) },
    {
      title: "Type", dataIndex: "kind", key: "kind", width: 110,
      render: (k: string) => k === "MANAGED" ? <Tag color="blue">Managed</Tag>
        : k === "REMOTE" ? <Tag color="geekblue">Remote Ambari</Tag>
        : <Tag color="purple">External</Tag>,
    },
    {
      title: "Components", key: "components",
      render: (_: any, c: PlatformContext) => {
        const r = resolved[c.id];
        if (!r) return <Text type="secondary">resolving…</Text>;
        const tags = [];
        if (r.atlasManaged || r.atlasUrl) tags.push(<Tag key="a" icon={<DatabaseOutlined />} color="cyan">Atlas</Tag>);
        if (r.rangerManaged || r.rangerUrl) tags.push(<Tag key="r" icon={<SafetyCertificateOutlined />} color="volcano">Ranger</Tag>);
        if (r.kerberosRealm) tags.push(<Tag key="k" icon={<KeyOutlined />} color="geekblue">Kerberos</Tag>);
        return tags.length ? <Space size={4} wrap>{tags}</Space> : <Text type="secondary">—</Text>;
      },
    },
    {
      // The platform's security posture — NOT Atlas's own login mode. A cluster can run
      // Kerberos + OIDC + Ranger while Atlas's internal auth is "basic"; surfacing only
      // atlasAuthMode here read as "the context is insecure", which it isn't. We show the
      // real platform auth/authorization and relegate Atlas's own mode to a labelled detail.
      title: "Security", key: "security",
      render: (_: any, c: PlatformContext) => {
        const r = resolved[c.id];
        if (!r) return <Text type="secondary">resolving…</Text>;
        const oidc = r.resolvedFields?.["oidc.issuerUrl"];
        const ranger = r.rangerManaged || !!r.rangerUrl || r.atlasAclMode === "ranger";
        const tags = [];
        if (r.kerberosRealm) tags.push(<Tag key="k" color="geekblue"><KeyOutlined /> Kerberos · {r.kerberosRealm}</Tag>);
        if (oidc) tags.push(<Tag key="o" color="green">OIDC SSO</Tag>);
        if (ranger) tags.push(<Tag key="r" color="volcano">Ranger authz</Tag>);
        if (!r.kerberosRealm && !oidc) tags.push(<Tag key="n" color="default">no platform auth</Tag>);
        if (r.atlasManaged && r.atlasAuthMode) {
          tags.push(<Tag key="aa" color="default" style={{ opacity: 0.75 }}>Atlas login: {r.atlasAuthMode}</Tag>);
        }
        return <Space size={4} wrap>{tags}</Space>;
      },
    },
    {
      title: "Actions", key: "actions", fixed: "right", width: 150,
      render: (_: any, c: PlatformContext) => {
        // MANAGED is virtual: connection + security are resolved live from Ambari and ops are
        // delegated to the Ambari server, so there is nothing to edit here (edit the cluster in
        // Ambari). Show that explicitly rather than an empty cell that looks broken.
        if (c.kind === "MANAGED") {
          return (
            <Tooltip title="Managed by Ambari — connection and security are resolved live; manage the cluster in Ambari.">
              <Tag icon={<LockOutlined />} color="default">Ambari-managed</Tag>
            </Tooltip>
          );
        }
        // EXTERNAL and REMOTE are operator-defined → editable and deletable.
        return (
          <Space onClick={(e) => e.stopPropagation()}>
            <Tooltip title="Edit">
              <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(c)} />
            </Tooltip>
            <Popconfirm title="Delete this context?" onConfirm={() => handleDelete(c.id)}>
              <Button size="small" danger icon={<DeleteOutlined />} />
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  return (
    <div>
      <div className="page-header" style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <Title level={2}><ApiOutlined /> Platform Contexts</Title>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={fetchContexts}>Refresh</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openAdd}>Add context</Button>
        </Space>
      </div>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="What is a platform context?"
        description={
          <span>
            A context is an ODP platform environment (Atlas, Ranger, Kerberos) that KDPS services
            integrate against. The <b>Managed</b> context is the cluster this Ambari manages — its
            endpoints and credentials are resolved live, and Ranger operations are delegated to the
            Ambari server (no admin password handled by KDPS). Add an <b>External</b> context to
            integrate with a cluster Ambari does not manage (e.g. behind Knox).
          </span>
        }
      />

      <Table
        columns={columns}
        dataSource={contexts}
        rowKey="id"
        loading={loading}
        pagination={false}
        scroll={{ x: 900 }}
        onRow={(c) => ({
          onClick: () => setDetail({ ctx: c, r: resolved[c.id] }),
          style: { cursor: "pointer" },
        })}
      />

      <Modal
        title={detail ? <span><ApiOutlined /> {detail.ctx.name}</span> : null}
        open={!!detail}
        footer={<Button onClick={() => setDetail(null)}>Close</Button>}
        onCancel={() => setDetail(null)}
        width={620}
      >
        {detail && (() => {
          const r = detail.r;
          const managed = detail.ctx.kind === "MANAGED";
          const remote = detail.ctx.kind === "REMOTE";
          // remote-cluster info: prefer the freshly-resolved values, fall back to the cached config.
          const ambariVersion = r?.ambariVersion || detail.ctx.config?.ambariVersion;
          const stackName = r?.stackName || detail.ctx.config?.stackName;
          const stackVersion = r?.stackVersion || detail.ctx.config?.stackVersion;
          const lastContactAt = r?.lastContactAt || detail.ctx.config?.lastContactAt;
          return (
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="Type">
                {managed ? <Tag color="blue">Managed</Tag>
                  : remote ? <Tag color="geekblue">Remote Ambari</Tag>
                  : <Tag color="purple">External</Tag>}
              </Descriptions.Item>
              {detail.ctx.clusterName && (
                <Descriptions.Item label="Cluster">{detail.ctx.clusterName}</Descriptions.Item>
              )}
              {remote && (
                <Descriptions.Item label="Remote Ambari">
                  <Space direction="vertical" size={0}>
                    <Text copyable>{r?.remoteAmbariUrl || detail.ctx.config?.remoteAmbariUrl || "—"}</Text>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      Ambari {ambariVersion || "—"}
                      {stackName ? ` · stack ${stackName}` : ""}
                      {stackVersion ? ` (${stackVersion})` : ""}
                      {detail.ctx.config?.verifySsl === false ? " · ⚠ SSL verification off" : ""}
                    </Text>
                    {lastContactAt && (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        last contact: {new Date(lastContactAt).toLocaleString()}
                      </Text>
                    )}
                  </Space>
                </Descriptions.Item>
              )}
              <Descriptions.Item label="Atlas">
                {r?.atlasManaged || r?.atlasUrl
                  ? <Space direction="vertical" size={0}>
                      <Text copyable>{r?.atlasUrl || "—"}</Text>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        auth: {r?.atlasAuthMode || "—"} · authorization: {r?.atlasAclMode || "—"}
                      </Text>
                    </Space>
                  : <Text type="secondary">not present</Text>}
              </Descriptions.Item>
              <Descriptions.Item label="Atlas Ranger repo">{r?.atlasRangerServiceName || "—"}</Descriptions.Item>
              <Descriptions.Item label="Ranger">
                {managed
                  ? <Text type="secondary">managed by this Ambari (policies applied server-side)</Text>
                  : <Space direction="vertical" size={0}>
                      <Text copyable>{r?.rangerUrl || detail.ctx.config?.rangerUrl || "—"}</Text>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        admin user: {r?.rangerAdminUsername || detail.ctx.config?.rangerAdminUsername || "admin"}
                        {detail.ctx.secretKeys?.includes("rangerAdminPassword") ? " · password set" : " · no password set"}
                      </Text>
                    </Space>}
              </Descriptions.Item>
              <Descriptions.Item label="Kerberos">
                {r?.kerberosRealm ? <Tag color="geekblue">{r.kerberosRealm}</Tag> : <Text type="secondary">none</Text>}
              </Descriptions.Item>
              {detail.ctx.description && (
                <Descriptions.Item label="Description">{detail.ctx.description}</Descriptions.Item>
              )}
            </Descriptions>
          );
        })()}
      </Modal>

      <Modal
        title={editing ? `Edit context — ${editing.id}` : "Add context"}
        open={modalOpen}
        onOk={() => form.submit()}
        confirmLoading={saving}
        onCancel={() => setModalOpen(false)}
        width={640}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={onFinish}>
          <Form.Item name="id" label="ID"
            rules={[{ required: true, message: "id is required" },
                    { pattern: /^[a-z0-9-]+$/, message: "lowercase letters, digits and dashes only" }]}>
            <Input placeholder="e.g. prod-atlas" disabled={!!editing} />
          </Form.Item>
          <Form.Item name="name" label="Name" rules={[{ required: true }]}>
            <Input placeholder="Human-readable name" />
          </Form.Item>
          <Form.Item name="description" label="Description">
            <Input.TextArea rows={2} />
          </Form.Item>

          <Form.Item name="kind" label="Type" initialValue="EXTERNAL"
            tooltip="External: you provide each service's endpoint and credentials. Remote Ambari: KDPS connects to another cluster's Ambari and discovers its services automatically.">
            <Select disabled={!!editing} options={[
              { value: "EXTERNAL", label: "External — manual endpoints & credentials" },
              { value: "REMOTE", label: "Remote Ambari — connect to another cluster" },
            ]} />
          </Form.Item>

          {/* REMOTE: connect to a remote cluster's Ambari; KDPS discovers its services. */}
          {watchedKind === "REMOTE" && (
            <>
              <Form.Item name="remoteAmbariUrl" label="Remote Ambari URL" rules={[{ required: true }]}
                tooltip="Base URL of the remote cluster's Ambari, e.g. https://ambari.remote.example.com:8443">
                <Input placeholder="https://ambari-host:8443" />
              </Form.Item>
              <Form.Item name="remoteUsername" label="Ambari username" rules={[{ required: true }]}>
                <Input placeholder="admin" autoComplete="off" />
              </Form.Item>
              <Form.Item name="remotePassword" label="Ambari password"
                rules={editing ? [] : [{ required: true }]}
                tooltip="Stored encrypted; never displayed or returned by the API.">
                <Input.Password placeholder={editing ? "(unchanged)" : ""} autoComplete="new-password" />
              </Form.Item>
              <Form.Item name="verifySsl" label="Verify TLS certificate" valuePropName="checked"
                initialValue={true}
                tooltip="Turn OFF to accept a self-signed / untrusted certificate on the remote Ambari (trust-all). Leave ON for a CA-signed certificate.">
                <Switch checkedChildren="Verify" unCheckedChildren="Ignore SSL" />
              </Form.Item>

              <Form.Item>
                <Button icon={<CloudServerOutlined />} loading={probing} onClick={doProbe}>
                  Test connection &amp; list clusters
                </Button>
              </Form.Item>
              {probeResult && (
                <Form.Item>
                  {probeResult.ok
                    ? <Alert type="success" showIcon
                        message={`Connected${probeResult.ambariVersion ? ` — Ambari ${probeResult.ambariVersion}` : ""}`}
                        description={`${probeResult.clusters?.length ?? 0} cluster(s) discovered. Pick the remote cluster below.`} />
                    : <Alert type="error" showIcon message="Connection failed" description={probeResult.error} />}
                </Form.Item>
              )}

              <Form.Item name="clusterName" label="Remote cluster name" rules={[{ required: true }]}
                tooltip="The cluster on the remote Ambari to source the context from. Run the test above to pick from the discovered list, or type it.">
                {probeResult?.ok && (probeResult.clusters?.length ?? 0) > 0
                  ? <Select showSearch placeholder="Select a discovered cluster"
                      options={(probeResult.clusters || []).map((c) => ({ value: c, label: c }))} />
                  : <Input placeholder="e.g. prod" />}
              </Form.Item>
            </>
          )}

          {/* EXTERNAL: fields rendered from the per-capability context schema
              (KDPS/contexts/capabilities/*.json). A company adds a capability by dropping a
              fragment file — this form picks it up. */}
          {watchedKind !== "REMOTE" && schema.map((cap) => {
            const fields = (cap.fields || []).filter((f) => f.appliesTo !== "MANAGED");
            if (!fields.length) return null;
            return (
              <div key={cap.capability}>
                <Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 4, fontWeight: 600 }}>
                  {cap.label || cap.capability}
                </Paragraph>
                {fields.map((f) => {
                  const key = fieldKey(cap.capability, f.name);
                  let input: React.ReactNode;
                  if (f.type === "password") {
                    input = <Input.Password placeholder={editing && f.secret ? "(unchanged)" : f.placeholder} />;
                  } else if (f.type === "enum") {
                    input = <Select options={(f.options || []).map((o) => ({ value: o, label: o }))} />;
                  } else if (f.type === "boolean") {
                    input = <Select options={[{ value: true, label: "true" }, { value: false, label: "false" }]} />;
                  } else {
                    input = <Input placeholder={f.placeholder} />;
                  }
                  return (
                    <Form.Item key={key} name={key} label={f.label || f.name} tooltip={f.help}>
                      {input}
                    </Form.Item>
                  );
                })}
              </div>
            );
          })}
        </Form>
      </Modal>
    </div>
  );
}
