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
import React, { useCallback, useEffect, useMemo, useState } from "react";
import type { ColumnsType } from "antd/es/table";
import {
  Alert, Button, Form, Input, Modal, Popconfirm, Space, Table, Tag, Tooltip, Typography, message,
} from "antd";
import { DatabaseOutlined, DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from "@ant-design/icons";
import { deleteTrinoCatalog, getTrinoCatalogs, saveTrinoCatalog, type TrinoCatalog } from "../api/client";
import "./Page.css";

const { Title, Text, Paragraph } = Typography;

const NAME_RE = /^[a-z][a-z0-9_]*$/;
const PLACEHOLDER = [
  "connector.name=postgresql",
  "connection-url=jdbc:postgresql://db.example.com:5432/analytics",
  "connection-user=${ENV:PG_USER}",
  "connection-password=${ENV:PG_PASSWORD}",
].join("\n");

/** connector.name from the properties text, or undefined. Mirrors the backend extraction. */
export function connectorOf(text: string | undefined): string | undefined {
  const m = /^\s*connector\.name\s*=\s*(\S+)\s*$/m.exec(text || "");
  return m ? m[1] : undefined;
}

/**
 * Reusable Trino catalogs: write a catalog's .properties once, attach it to any Trino release from
 * the deploy wizard ("Custom catalogs" step). The release holds a copy taken at deploy time, so
 * editing here changes nothing until the operator re-applies it from Upgrade/Config.
 */
export default function TrinoCatalogsPage() {
  const [catalogs, setCatalogs] = useState<TrinoCatalog[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<TrinoCatalog | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [form] = Form.useForm<TrinoCatalog>();
  const propertiesText = Form.useWatch("propertiesText", form);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setCatalogs(await getTrinoCatalogs());
      setError(null);
    } catch (e: any) {
      setError(e?.message || "Could not load the catalogs");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setModalOpen(true);
  };
  const openEdit = (c: TrinoCatalog) => {
    setEditing(c);
    form.setFieldsValue({ name: c.name, description: c.description, propertiesText: c.propertiesText });
    setModalOpen(true);
  };

  const submit = async () => {
    const v = await form.validateFields();
    setSaving(true);
    try {
      const saved = await saveTrinoCatalog({ ...(editing?.id ? { id: editing.id } : {}), ...v });
      message.success(editing ? `Catalog '${saved.name}' updated` : `Catalog '${saved.name}' created`);
      setModalOpen(false);
      await load();
    } catch (e: any) {
      // Backend messages are operator-readable (name rules, missing connector.name, size, duplicates).
      message.error(String(e?.message || e).replace(/^HTTP \d+ – /, "").replace(/^\{"error":"(.*)"\}$/, "$1"), 8);
    } finally {
      setSaving(false);
    }
  };

  const remove = async (c: TrinoCatalog) => {
    try {
      await deleteTrinoCatalog(c.id!);
      message.success(`Catalog '${c.name}' deleted`);
      await load();
    } catch (e: any) {
      message.error(String(e?.message || e).replace(/^HTTP \d+ – /, "").replace(/^\{"error":"(.*)"\}$/, "$1"), 10);
    }
  };

  const columns: ColumnsType<TrinoCatalog> = useMemo(() => [
    {
      title: "Catalog", dataIndex: "name", key: "name",
      render: (name: string, c) => (
        <Space direction="vertical" size={0}>
          <Text strong><DatabaseOutlined /> {name}</Text>
          {c.description ? <Text type="secondary">{c.description}</Text> : null}
        </Space>
      ),
    },
    { title: "Connector", dataIndex: "connectorName", key: "connector", width: 160, render: (v: string) => v ? <Tag>{v}</Tag> : <Text type="secondary">—</Text> },
    {
      title: "Used by", dataIndex: "usedBy", key: "usedBy", width: 260,
      render: (used: string[] | undefined) => used && used.length
        ? <Space wrap size={[4, 4]}>{used.map(u => <Tag key={u} color="blue">{u}</Tag>)}</Space>
        : <Text type="secondary">no release yet</Text>,
    },
    { title: "Updated", dataIndex: "updatedAt", key: "updatedAt", width: 180, render: (v: string) => v ? new Date(v).toLocaleString() : "—" },
    {
      title: "", key: "actions", width: 110, align: "right",
      render: (_: unknown, c) => (
        <Space>
          <Tooltip title="Edit"><Button size="small" icon={<EditOutlined />} onClick={() => openEdit(c)} /></Tooltip>
          <Popconfirm
            title={`Delete catalog '${c.name}'?`}
            description={c.usedBy && c.usedBy.length ? "Attached releases keep their copy, but the deletion will be refused until they are detached." : "This cannot be undone."}
            okText="Delete" okButtonProps={{ danger: true }} onConfirm={() => remove(c)}
          >
            <Tooltip title="Delete"><Button size="small" danger icon={<DeleteOutlined />} /></Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ], []); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="page">
      <div className="page-header" style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <div>
          <Title level={3} style={{ marginBottom: 4 }}>Trino Catalogs</Title>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>
            Define a catalog once, attach it to any Trino release from the deploy wizard. A release keeps the copy taken at
            deploy time — edits here apply to a release when you re-run Upgrade/Config on it.
          </Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => load()} loading={loading}>Refresh</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>New catalog</Button>
        </Space>
      </div>
      {error ? <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} /> : null}
      <Table<TrinoCatalog>
        rowKey={(c) => c.id || c.name}
        columns={columns}
        dataSource={catalogs}
        loading={loading}
        pagination={false}
        locale={{ emptyText: "No reusable catalog yet. Create one, then pick it in the Trino wizard under Custom catalogs." }}
      />

      <Modal
        title={editing ? `Edit catalog '${editing.name}'` : "New reusable catalog"}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={submit}
        okText={editing ? "Save" : "Create"}
        confirmLoading={saving}
        width={720}
        destroyOnClose
      >
        <Form form={form} layout="vertical" requiredMark="optional">
          <Form.Item
            name="name" label="Catalog name"
            tooltip="Becomes the catalog name in every Trino release that uses it"
            rules={[
              { required: true, message: "Give the catalog a name" },
              { pattern: NAME_RE, message: "Lower-case letters, digits and underscores, starting with a letter" },
              { validator: (_, v) => (v === "hive" || v === "iceberg") ? Promise.reject(new Error("'hive' and 'iceberg' are built by KDPS from the platform context")) : Promise.resolve() },
            ]}
          >
            <Input placeholder="postgres_prod" disabled={!!editing && !!(editing.usedBy && editing.usedBy.length)} />
          </Form.Item>
          <Form.Item name="description" label="Description">
            <Input placeholder="Production PostgreSQL (analytics schema)" maxLength={1024} />
          </Form.Item>
          <Form.Item
            name="propertiesText" label="Catalog properties"
            extra={
              <Space direction="vertical" size={0}>
                <Text type="secondary">One key=value per line, exactly as in a Trino catalog .properties file. Connector detected: <Tag>{connectorOf(propertiesText) || "none yet"}</Tag></Text>
                <Text type="secondary">Keep passwords out: reference a Secret with ${"{"}ENV:VAR{"}"} instead of pasting them.</Text>
              </Space>
            }
            rules={[
              { required: true, message: "Add the catalog's properties (at least connector.name)" },
              { validator: (_, v) => connectorOf(v) ? Promise.resolve() : Promise.reject(new Error("A connector.name line is required")) },
              { max: 3000, message: "At most 3000 characters" },
            ]}
          >
            <Input.TextArea rows={10} spellCheck={false} placeholder={PLACEHOLDER} style={{ fontFamily: "monospace" }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
